"""Shadow Client — a from-scratch Minecraft launcher tuned for max FPS.

Commands:
  setup  [--username NAME] [--online] [--version V] [--heap MB] [--gc g1|zgc]
         [--no-mods]
  login
  launch [--heap MB] [--gc g1|zgc] [--username NAME]
  update-mods
  doctor [--profile NAME] [--fix]

The first `setup` run downloads vanilla, installs Fabric, and pulls the
performance mod stack. `launch` re-uses the installed state. Login is only
needed if you want to play on online-mode / real Mojang servers — offline
mode works out-of-the-box for singleplayer + offline-mode LAN servers.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

# Windows default console is cp1252 — coerce to UTF-8 so progress prints don't crash.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import auth
import fabric
import jdk as jdk_mod
import jvm
import mods
import mojang

HERE = Path(__file__).resolve().parent
# SHARED_DIR holds everything that's version-keyed by name and safe to share
# across MC versions: vanilla JARs, libraries (hash-named), assets (hash-named),
# the .fabric cache, and the single Microsoft account file.
SHARED_DIR = HERE / "game_dir"
# Each MC version gets its own profile dir under here: mods/, saves/,
# options.txt, screenshots/, config/. Switching versions in the launcher
# switches the active profile so mod conflicts can't happen.
PROFILES_DIR = SHARED_DIR / "profiles"
# Back-compat alias — older code paths referenced GAME_DIR directly. New code
# should call `resolve_dirs(profile)` to get a (profile_dir, shared_dir) tuple.
GAME_DIR = SHARED_DIR
ACCOUNT_FILE = SHARED_DIR / "mc-client-account.json"
STATE_FILE = HERE / "installed.json"


def resolve_dirs(profile: str | None) -> tuple[Path, Path]:
    """Return ``(profile_dir, shared_dir)``.

    Per-version files (mods, saves, options.txt, screenshots, config) live in
    ``profile_dir``. Per-name files (vanilla JARs, libraries, assets) live in
    ``shared_dir`` so multiple profiles can share them. If ``profile`` is
    ``None`` we fall back to the legacy single-folder layout — both paths
    become ``SHARED_DIR`` — so users running ``client.py`` from a shell
    without ``--profile`` keep their old install working.
    """
    if not profile:
        return SHARED_DIR, SHARED_DIR
    pd = PROFILES_DIR / profile
    pd.mkdir(parents=True, exist_ok=True)
    return pd, SHARED_DIR

# Java autodetect. Prefer the newest bundled JDK in the project so mods that
# demand Java 22+ (e.g. c2me's native-math sub-module) always get what they
# want, then fall back to common system install paths.
_CANDIDATE_JAVAS = [
    Path(os.environ.get("JAVA_HOME", "")) / "bin" / ("java.exe" if platform.system() == "Windows" else "java"),
    Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-delta/bin/java.exe",
    Path.home() / "AppData/Roaming/PrismLauncher/java/java-runtime-gamma/bin/java.exe",
    Path("C:/Program Files/Eclipse Adoptium/jdk-25/bin/java.exe"),
    Path("C:/Program Files/Eclipse Adoptium/jdk-21/bin/java.exe"),
    Path("C:/Program Files/Microsoft/jdk-21/bin/java.exe"),
    Path("/usr/lib/jvm/java-21-openjdk/bin/java"),
    Path("/opt/homebrew/opt/openjdk@21/bin/java"),
]


def find_java() -> Path:
    """Return the newest usable JDK on disk.

    Scans project-local bundled JDKs (jdk-*/), then the candidate list. Probes
    each candidate with `-version`; drops anything that returns 0 (corrupt,
    32-bit-incompatible, missing dependencies). Picks the highest major
    version among what's left — so once we download JDK 25 for an MC that
    needed it, every launch uses 25 and mods requiring Java 22+ work.
    """
    here = Path(__file__).resolve().parent
    candidates: list[Path] = []
    bin_name = "java.exe" if platform.system() == "Windows" else "java"
    for folder in sorted(here.glob("jdk-*")):
        if folder.is_dir():
            for j in folder.rglob(bin_name):
                if j.is_file() and "bin" in j.parts:
                    candidates.append(j)
    for c in _CANDIDATE_JAVAS:
        # Skip the JAVA_HOME-less "bin/java.exe" relative stub.
        if c and c.is_absolute() and c.exists():
            candidates.append(c)
    if not candidates:
        raise SystemExit("No JDK found. Set JAVA_HOME or pass --java PATH.")

    probed = [(java_major_version(c), c) for c in candidates]
    usable = [(v, c) for v, c in probed if v > 0]
    if not usable:
        raise SystemExit(
            "All JDK candidates failed `-version` probe. "
            f"Checked: {[str(c) for c in candidates]}"
        )
    # Highest major wins; ties broken by the earlier candidate (project-local
    # jdk-N/ folders are listed first, so they get priority).
    usable.sort(key=lambda vc: (-vc[0], candidates.index(vc[1])))
    return usable[0][1]


def java_major_version(java_exe: Path) -> int:
    """Probe `java -version` and extract the major version, or 0 on any error.

    Never raises — find_java's `max(..., key=java_major_version)` has to handle
    corrupt / 32-bit-incompatible / non-executable candidates silently.
    """
    try:
        r = subprocess.run([str(java_exe), "-version"],
                           capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return 0
    m = re.search(r'version\s+"(\d+)', (r.stdout or "") + (r.stderr or ""))
    return int(m.group(1)) if m else 0


# Flags Mojang / Fabric put into their version JSONs that newer Java versions
# understand but older ones reject. Keyed by the minimum Java major version
# that actually recognises the flag; if the runtime is older, we strip it.
_JVM_ARG_MIN_JAVA: dict[str, int] = {
    "--sun-misc-unsafe-memory-access": 23,   # JEP 471
    "--enable-native-access":          17,   # JEP 454 / foreign memory
}


def filter_args_for_java(args: list[str], java_major: int) -> list[str]:
    out: list[str] = []
    for a in args:
        # match `--flag=value` and bare `--flag`
        key = a.split("=", 1)[0]
        min_v = _JVM_ARG_MIN_JAVA.get(key)
        if min_v is not None and java_major < min_v:
            print(f"[launch] stripping '{a}' (needs Java {min_v}, you have {java_major})")
            continue
        out.append(a)
    return out


def _migrate_legacy_files(profile_name: str) -> None:
    """Move single-folder-install files into ``game_dir/profiles/<profile>/``.

    Runs the first time a user upgrades to the per-version-profiles layout.
    Shared, name/hash-keyed dirs (versions/, libraries/, assets/, .fabric/)
    stay at SHARED_DIR. Per-user-state dirs (mods, saves, screenshots etc.)
    move into the named profile.

    Uses ``Path.rename`` — atomic on the same filesystem and silent if the
    file is already at its destination. Failures of individual files are
    logged but never abort the migration.
    """
    new_dir = PROFILES_DIR / profile_name
    new_dir.mkdir(parents=True, exist_ok=True)
    subdirs = ["mods", "saves", "config", "screenshots", "resourcepacks",
               "shaderpacks", "logs", "crash-reports"]
    files = ["options.txt", "optionsof.txt", "servers.dat",
             "usercache.json", "usernamecache.json", "realms_persistence.json",
             "hotbar.nbt", "command_history.txt"]
    moved = 0
    for sub in subdirs:
        old = SHARED_DIR / sub
        new = new_dir / sub
        if old.exists() and not new.exists():
            try:
                old.rename(new)
                print(f"[migrate] {sub} → profiles/{profile_name}/")
                moved += 1
            except OSError as e:
                print(f"[migrate] could not move {sub}: {e}")
    for f in files:
        old = SHARED_DIR / f
        new = new_dir / f
        if old.exists() and not new.exists():
            try:
                old.rename(new)
                moved += 1
            except OSError:
                pass
    if moved:
        print(f"[migrate] moved {moved} item(s) into profile '{profile_name}'")


def _state_load() -> dict:
    """Load installed.json. Always returns the new ``profiles``-keyed schema.

    Older single-version state files (top-level ``mc_version``, ``client_jar``)
    trigger a one-shot migration: the user's existing mods/saves/options
    move into ``game_dir/profiles/<mc_version>/`` and installed.json is
    rewritten in the new shape. Idempotent — subsequent loads see the new
    schema and skip the migration.
    """
    if not STATE_FILE.exists():
        return {"profiles": {}, "last_used": None}
    try:
        s = json.loads(STATE_FILE.read_text())
    except (json.JSONDecodeError, OSError) as e:
        print(f"[state] {STATE_FILE.name} corrupt ({e}); treating as empty. "
              f"Re-run `setup` to rebuild.")
        return {"profiles": {}, "last_used": None}
    # Old schema: top-level mc_version → migrate files + rewrite state.
    if "mc_version" in s and "profiles" not in s:
        legacy_name = s.get("mc_version") or "__legacy__"
        print(f"[migrate] upgrading to per-version profiles "
              f"(legacy install → '{legacy_name}')")
        _migrate_legacy_files(legacy_name)
        new_state = {
            "profiles": {legacy_name: {
                "mc_version":         s.get("mc_version"),
                "fabric_loader":      s.get("fabric_loader"),
                "version_id":         s.get("version_id"),
                "vanilla_version_id": s.get("vanilla_version_id"),
                "client_jar":         s.get("client_jar"),
                "installed_mods":     s.get("installed_mods", []),
            }},
            "last_used": legacy_name,
        }
        _state_save(new_state)
        return new_state
    s.setdefault("profiles", {})
    s.setdefault("last_used", None)
    return s


def _resolve_profile(state: dict, requested: str | None) -> str | None:
    """Pick which profile to operate on.

    1. Explicit ``--profile`` wins.
    2. Otherwise reuse ``last_used`` from state.
    3. Otherwise return None (caller decides — e.g. setup uses the resolved
       MC version as the new profile name).
    """
    if requested:
        return requested
    return state.get("last_used")


def _atomic_write(path: Path, content: str, *, encoding: str = "utf-8") -> None:
    """Write `content` to `path` via a sibling tmpfile + atomic replace, so a
    crash mid-write can't leave a half-written JSON on disk."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(content, encoding=encoding)
    tmp.replace(path)


def _state_save(state: dict) -> None:
    _atomic_write(STATE_FILE, json.dumps(state, indent=2))


# ---- commands ----------------------------------------------------------------


def cmd_setup(args: argparse.Namespace) -> int:
    SHARED_DIR.mkdir(parents=True, exist_ok=True)

    print("[setup] fetching Mojang version manifest…")
    manifest = mojang.fetch_manifest()
    vanilla_entry = mojang.resolve_version(manifest, args.version)
    mc_version = vanilla_entry["id"]

    # Profile defaults to the resolved MC version, so each version gets its
    # own isolated mods/saves folder automatically. Users who explicitly pass
    # --profile can name their profile whatever they like.
    profile = args.profile or mc_version
    profile_dir, shared_dir = resolve_dirs(profile)
    print(f"[setup] target MC version: {mc_version}")
    print(f"[setup] profile: {profile}  → {profile_dir.relative_to(HERE)}")

    vanilla = mojang.fetch_version_json(vanilla_entry, shared_dir)

    if not args.no_fabric:
        loader_v = fabric.latest_loader(mc_version)
        print(f"[setup] Fabric loader {loader_v}")
        fabric_profile = fabric.fetch_profile(mc_version, loader_v)
        version = fabric.merge_into_vanilla(vanilla, fabric_profile)
    else:
        version = vanilla
        loader_v = None

    # All three of these are version- or hash-keyed inside SHARED_DIR, so they
    # naturally share across profiles without conflict.
    mojang.download_libraries(version, shared_dir)
    mojang.download_client_jar(vanilla, shared_dir)
    mojang.download_assets(vanilla, shared_dir)

    # Persist the resolved merged version JSON so `launch` doesn't refetch Fabric.
    version_json_path = shared_dir / "versions" / version["id"] / f"{version['id']}.json"
    _atomic_write(version_json_path, json.dumps(version, indent=2))

    # Mods go into the per-version profile — that's the whole point of
    # profiles, so the 1.21.11 mod set never collides with the 1.21.5 set.
    installed_mods: list[str] = []
    if not args.no_mods and not args.no_fabric:
        print(f"[setup] installing performance mods into profile '{profile}'…")
        installed_mods, _ = mods.install_mods(profile_dir / "mods", mc_version)

    # Per-profile options.txt — different versions can have different
    # Sodium / Iris graphics settings without stepping on each other.
    opts = profile_dir / "options.txt"
    if not opts.exists():
        _atomic_write(opts, jvm.OPTIONS_TXT)
        print(f"[setup] wrote default options.txt into profile '{profile}'")

    # Per-profile state. Existing entries for OTHER profiles are preserved so
    # the user doesn't lose their 1.21.5 install just because they re-set-up
    # 1.21.11.
    state = _state_load()
    state["profiles"][profile] = {
        "mc_version": mc_version,
        "fabric_loader": loader_v,
        "version_id": version["id"],
        "vanilla_version_id": vanilla["id"],
        "client_jar": str((shared_dir / "versions" / vanilla["id"] / f"{vanilla['id']}.jar").resolve()),
        "installed_mods": installed_mods,
    }
    state["last_used"] = profile
    _state_save(state)
    print(f"[setup] done — profile '{profile}' has {len(installed_mods)} mods, "
          f"{len(state['profiles'])} profile(s) total")

    # Account is shared across profiles — one Microsoft sign-in covers them all.
    if not ACCOUNT_FILE.exists():
        acct = auth.offline(args.username)
        acct.save(ACCOUNT_FILE)
        print(f"[setup] created offline account for '{args.username}' — run `login` for online play")

    # Auto-build the Shadow HUD mod — best-effort. The build script drops the
    # jar at SHARED_DIR/mods/shadowhud-1.0.0.jar (legacy location); we mirror
    # it into the active profile's mods folder so the loader picks it up.
    build_py = HERE / "branding" / "hud_mod" / "build.py"
    if build_py.exists():
        print("[setup] compiling Shadow HUD…")
        rc = subprocess.call([sys.executable, str(build_py)])
        if rc != 0:
            print("[setup] Shadow HUD compile skipped (JDK or build-deps missing)")
        _sync_hud_into_profile(profile_dir, shared_dir)
    return 0


def _sync_hud_into_profile(profile_dir: Path, shared_dir: Path) -> None:
    """Mirror the freshly built Shadow HUD jar from the legacy SHARED_DIR/mods/
    location into the active profile's mods folder. Idempotent + safe to run
    every launch. If MC is currently holding the destination file open we
    stage as .jar.new and let ``_promote_staged_mods`` swap it in next time."""
    if profile_dir == shared_dir:
        return  # legacy layout — HUD already lives in the right place
    src = shared_dir / "mods" / "shadowhud-1.0.0.jar"
    if not src.exists():
        return
    dst_dir = profile_dir / "mods"
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst = dst_dir / src.name
    # Only re-copy if the source is newer (avoids rewriting every launch when
    # nothing changed).
    if dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime:
        return
    try:
        if dst.exists():
            dst.unlink()
        shutil.copy2(src, dst)
        print(f"[setup] HUD synced → {dst.relative_to(HERE)}")
    except PermissionError:
        # MC has the destination open — stage and let _promote_staged_mods
        # swap it on next launch.
        staged = dst.with_suffix(dst.suffix + ".new")
        try:
            shutil.copy2(src, staged)
            print(f"[setup] HUD staged at {staged.name} (MC holds the active jar)")
        except OSError as e:
            print(f"[setup] HUD sync failed: {e}")


def cmd_login(args: argparse.Namespace) -> int:
    """Sign in for online play.

    Default (`--source prism`) tries PrismLauncher's cached account first —
    that's the fastest path when Prism is set up. If Prism's refresh token
    has been invalidated by Microsoft (AADSTS70000, common after a few
    months of not opening Prism), we automatically fall back to the
    interactive device-code flow so the user is never stuck.
    """
    GAME_DIR.mkdir(parents=True, exist_ok=True)
    if args.source == "prism":
        try:
            acct = auth.from_prism_launcher()
            print(f"[login] imported PrismLauncher account: {acct.username}")
        except auth.PrismRefreshExpired as e:
            print(f"[login] Prism auth stale ({e}).")
            print("[login] Falling back to a direct Microsoft sign-in — "
                  "you only have to do this once, it'll cache after.")
            acct = auth.microsoft_login()
            print(f"[login] signed in as {acct.username} ({acct.uuid})")
    else:
        acct = auth.microsoft_login()
        print(f"[login] signed in as {acct.username} ({acct.uuid})")
    acct.save(ACCOUNT_FILE)
    return 0


def cmd_logout(args: argparse.Namespace) -> int:
    """Sign out of the Microsoft account.

    Replaces the cached account file with an offline-mode record using the
    requested username. The Microsoft refresh_token + access_token are thrown
    away, so next launch is offline-mode unless the user signs in again.
    """
    SHARED_DIR.mkdir(parents=True, exist_ok=True)
    acct = auth.offline(args.username or "Player")
    acct.save(ACCOUNT_FILE)
    print(f"[logout] reverted to offline account '{acct.username}'")
    return 0


def cmd_build_hud(args: argparse.Namespace) -> int:
    """Compile + install the Shadow HUD mod (top-left FPS/coords/biome overlay)."""
    build_py = HERE / "branding" / "hud_mod" / "build.py"
    if not build_py.exists():
        raise SystemExit(f"hud_mod build script missing: {build_py}")
    rc = subprocess.call([sys.executable, str(build_py)])
    if rc != 0:
        print("[build-hud] compile failed — see output above")
    return rc


def cmd_update_mods(args: argparse.Namespace) -> int:
    """Refresh only the launcher-managed performance mods for the active profile.

    Scoped deletion — we only remove jars whose *filename* starts with the slug
    of a mod we installed previously. User-dropped mods, Hacker Mode imports,
    Shadow Client branding + HUD jars, and the gametweaks rename are all left
    alone.
    """
    state = _state_load()
    profile = _resolve_profile(state, getattr(args, "profile", None))
    if not profile or profile not in state.get("profiles", {}):
        raise SystemExit("Run `setup` first")
    profile_dir, _ = resolve_dirs(profile)
    p_state = state["profiles"][profile]
    mods_dir = profile_dir / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)

    # Delete only jars whose filename heuristically belongs to a previously-
    # managed slug. Never rmtree the whole directory.
    removed = 0
    prev_slugs = [s.lower() for s in p_state.get("installed_mods", [])]
    for jar in list(mods_dir.glob("*.jar")):
        name_lc = jar.name.lower()
        if any(name_lc.startswith(slug) for slug in prev_slugs):
            jar.unlink()
            removed += 1

    installed, skipped = mods.install_mods(mods_dir, p_state["mc_version"])
    p_state["installed_mods"] = installed
    _state_save(state)
    print(f"[update-mods] profile={profile}  removed={removed}  "
          f"installed={len(installed)}  skipped={len(skipped)}")
    print(f"[update-mods] user-added mods preserved: "
          f"{len(list(mods_dir.glob('*.jar'))) - len(installed)} jars")
    return 0


def _mod_stem_prefix(filename: str) -> str | None:
    """``shadow-chat-0.1.42.jar`` -> ``shadow-chat-``.

    Finds the last ``-`` that is immediately followed by a digit (the
    version boundary) so versioned siblings of the same mod can be
    matched. Returns None when the name has no obvious version suffix —
    callers then skip the sibling sweep rather than guessing.
    """
    stem = filename[:-4] if filename.endswith(".jar") else filename
    for i in range(len(stem) - 1, 0, -1):
        if stem[i] == "-" and i + 1 < len(stem) and stem[i + 1].isdigit():
            return stem[: i + 1]
    return None


def _promote_staged_mods(mods_dir: Path) -> None:
    """Replace mods with their sibling `.jar.new` build artifacts if any exist.

    Happens when the HUD (or any mod) was rebuilt while MC held a handle on
    the previous jar. This pass runs right before launch, so restarts just work.

    After promoting a VERSIONED jar (name-1.2.3.jar), older siblings of the
    same mod are swept: the staged file usually carries a NEW version while
    the lock-holder was the old one (shadow-chat-0.1.41.jar held open while
    shadow-chat-0.1.42.jar.new staged). Promoting without the sweep would
    leave both jars in the folder — and Fabric refuses to launch on a
    duplicate mod id.
    """
    if not mods_dir.exists():
        return
    for staged in mods_dir.glob("*.jar.new"):
        target = staged.with_suffix("")  # drops .new, leaves .jar
        try:
            if target.exists():
                target.unlink()
            staged.replace(target)
            print(f"[launch] promoted staged {target.name}")
        except PermissionError:
            print(f"[launch] can't replace {target.name} (still locked?) — skipping")
            continue
        prefix = _mod_stem_prefix(target.name)
        if not prefix:
            continue
        for stale in mods_dir.glob(f"{prefix}*.jar"):
            if stale.name == target.name:
                continue
            try:
                stale.unlink()
                print(f"[launch] removed stale {stale.name} (superseded by {target.name})")
            except OSError as e:
                print(f"[launch] couldn't remove stale {stale.name}: {e}")


def _write_shadow_chat_auth(profile_dir: Path, acct) -> None:
    """Write ``shadow-chat-auth.json`` into the profile dir so the in-game
    Shadow Chat mod can authenticate against the relay.

    The mod reads ``<gameDir>/shadow-chat-auth.json`` once at startup, and
    here gameDir == profile_dir. We rewrite it on EVERY launch with the
    just-refreshed Microsoft access token. The relay verifies that token
    and returns 401 the moment it expires (~24 h), so a stale file makes
    chat silently fail to connect ("send doesn't work"). Offline accounts
    get ``token: null`` so the mod stays dormant instead of hammering the
    relay with bogus credentials.

    Mirrors the Tauri launcher's ``shadow_chat::write_auth_file``. Schema
    is locked by the mod's ``AuthConfig`` — do not rename fields. Best
    effort: a failure here must never block the launch.
    """
    relay_url = (os.environ.get("SHADOW_CHAT_RELAY") or "").strip() \
        or "wss://shadow-chat-relay.edisongushf.workers.dev"
    is_msa = getattr(acct, "user_type", "") == "msa"
    token = acct.access_token if (is_msa and acct.access_token) else None
    payload = {
        "relay_url": relay_url,
        "token": token,
        "uuid": acct.uuid,
        "name": acct.username,
    }
    try:
        profile_dir.mkdir(parents=True, exist_ok=True)
        dest = profile_dir / "shadow-chat-auth.json"
        tmp = dest.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        tmp.replace(dest)
        print(f"[chat] shadow-chat-auth.json refreshed → "
              f"{'enabled' if token else 'dormant (offline mode)'}")
    except Exception as e:
        print(f"[chat] could not write shadow-chat-auth.json: {e}")


def _auth_refresh_loop(proc: subprocess.Popen, profile_dir: Path,
                       account_file: Path, prism_path: Path | None) -> None:
    """Keep ``shadow-chat-auth.json`` fresh for the WHOLE play session.

    The Microsoft access token the relay verifies lives only ~24 h. Without
    this, an all-day grind or an AFK farm would lose chat the moment the
    socket dropped and tried to reconnect with a dead token. Writing it once
    at launch isn't enough — so while MC is alive we re-refresh the token and
    rewrite the auth file every ~20 min. The in-game mod re-reads the file
    and hot-swaps the token at runtime, so playtime length stops mattering.

    Daemon thread: it polls ``proc`` and returns the instant MC exits, and
    dies with the launcher regardless. All failures are swallowed + logged —
    a refresh hiccup must never take down the launcher or the game.
    """
    interval_s = 20 * 60
    while True:
        # Sleep in 1 s slices so we notice MC closing within a second
        # instead of holding the process open for up to 20 min.
        for _ in range(interval_s):
            if proc.poll() is not None:
                return
            time.sleep(1)
        if proc.poll() is not None:
            return
        try:
            acct = auth.Account.load(account_file)
            if acct is None:
                continue
            if acct.user_type == "msa":
                try:
                    use_prism = prism_path if (prism_path and prism_path.exists()) else None
                    if acct.refresh_if_needed(account_file, prism_path=use_prism):
                        print(f"[chat] background-refreshed access token for {acct.username}")
                except Exception as e:
                    print(f"[chat] background token refresh failed: {e}")
            _write_shadow_chat_auth(profile_dir, acct)
        except Exception as e:
            print(f"[chat] background auth refresh error: {e}")


def cmd_launch(args: argparse.Namespace) -> int:
    state = _state_load()
    profile = _resolve_profile(state, getattr(args, "profile", None))
    if not profile or profile not in state.get("profiles", {}):
        raise SystemExit("Run `setup` first")
    profile_dir, shared_dir = resolve_dirs(profile)
    p_state = state["profiles"][profile]

    # Pre-launch hygiene for the active profile:
    #  1) sync the HUD from the legacy build location (user may have rebuilt
    #     it via `python branding/hud_mod/build.py` without re-running setup).
    #  2) swap in any .jar.new builds that couldn't overwrite a held jar.
    _sync_hud_into_profile(profile_dir, shared_dir)
    _promote_staged_mods(profile_dir / "mods")

    # Remember which profile we just launched, so the next `launch` with no
    # --profile arg reuses the same one.
    if state.get("last_used") != profile:
        state["last_used"] = profile
        _state_save(state)

    print(f"[launch] profile: {profile}  ({profile_dir.relative_to(HERE)})")

    version_id = p_state["version_id"]
    version_json = shared_dir / "versions" / version_id / f"{version_id}.json"
    version = json.loads(version_json.read_text())

    # Rebuild classpath from libraries already on disk
    os_name, arch = mojang.detect_os()
    classpath: list[Path] = []
    for lib in version["libraries"]:
        if "rules" in lib and not mojang._rule_allows(lib["rules"], os_name, arch):
            continue
        artifact = lib.get("downloads", {}).get("artifact")
        if not artifact:
            continue
        jar = shared_dir / "libraries" / artifact["path"]
        if jar.exists():
            classpath.append(jar)

    natives_dir = shared_dir / "versions" / version_id / "natives"
    client_jar = Path(p_state["client_jar"])

    # Account
    acct_obj = auth.Account.load(ACCOUNT_FILE)
    if acct_obj is None:
        acct_obj = auth.offline(args.username or "Player")
        acct_obj.save(ACCOUNT_FILE)

    # Auto-refresh the Minecraft access token if it's near expiry.
    # For MSA accounts we stored a refresh_token on sign-in; this swaps it for
    # a fresh access_token silently so the user doesn't have to open Prism or
    # re-run `login` every 24 hours. Refresh rotation also happens here — the
    # rotated refresh_token survives for ~90 days, and because we re-save on
    # every successful refresh, it stays alive indefinitely.
    if acct_obj.user_type == "msa":
        prism_path = Path.home() / "AppData/Roaming/PrismLauncher/accounts.json"
        try:
            if acct_obj.refresh_if_needed(
                    ACCOUNT_FILE,
                    prism_path=prism_path if prism_path.exists() else None):
                print(f"[auth] refreshed access token for {acct_obj.username}")
        except Exception as e:
            print(f"[auth] could not refresh token: {e}")
            print("[auth] run `client.py login` to re-authenticate")

    # Write the Shadow Chat auth file so the in-game chat mod can reach the
    # relay. The mod reads <gameDir>/shadow-chat-auth.json at startup, and
    # gameDir == profile_dir here. We rewrite it on EVERY launch with the
    # freshly-refreshed access token: the relay verifies the Microsoft token
    # and 401s once it expires (~24h), so a stale file silently breaks chat
    # ("send doesn't work"). The Tauri launcher does this in its PLAY flow;
    # run.bat / client.py users need the same treatment.
    _write_shadow_chat_auth(profile_dir, acct_obj)

    # JVM flags — we pass library.path/classpath via the vanilla arg substitution.
    jvm_extra = jvm.flags(args.heap, gc=args.gc)

    all_args, main_class = mojang.build_args(
        version,
        username=acct_obj.username,
        uuid=acct_obj.uuid,
        access_token=acct_obj.access_token,
        user_type=acct_obj.user_type,
        # MC's gameDir = the profile dir, so mods/saves/options.txt all
        # resolve to the per-version folder. Assets are name/hash-keyed and
        # stay in the shared dir so we don't redownload ~300 MB per version.
        game_dir=profile_dir,
        assets_dir=shared_dir / "assets",
        natives_dir=natives_dir,
        classpath=classpath,
        client_jar=client_jar,
        jvm_extra=jvm_extra,
    )

    # Resolve Java: prefer an explicit --java, otherwise pick one new enough for
    # this MC version. If nothing on disk is new enough, fetch a bundled JDK.
    required = int(version.get("javaVersion", {}).get("majorVersion", 21))
    if args.java:
        java = Path(args.java)
    else:
        java = find_java()
        if java_major_version(java) < required:
            print(f"[launch] installed Java is too old (need {required}); fetching bundled JDK…")
            java = jdk_mod.download_jdk(required, HERE / f"jdk-{required}")

    jmaj = java_major_version(java)
    if jmaj < required:
        raise SystemExit(f"Java {jmaj} can't run MC {p_state['mc_version']} (needs {required}).")
    all_args = filter_args_for_java(all_args, jmaj)
    cmd = [str(java), *all_args]
    print(f"[launch] {java}  (Java {jmaj})")
    print(f"[launch] main: {main_class}")
    print(f"[launch] heap: {args.heap}M  gc: {args.gc}")
    print(f"[launch] user: {acct_obj.username} ({acct_obj.user_type})")
    print(f"[launch] mods: {len(p_state.get('installed_mods', []))} installed")

    # Persist the java command for diagnostics, with the access token redacted
    # so the log file can be shared without leaking multiplayer auth.
    redacted = []
    skip_next = False
    for tok in cmd:
        if skip_next:
            redacted.append("<redacted>")
            skip_next = False
            continue
        if tok in ("--accessToken", "--auth_access_token"):
            redacted.append(tok)
            skip_next = True
        elif tok.startswith("--accessToken="):
            redacted.append("--accessToken=<redacted>")
        else:
            redacted.append(tok)
    # Per-profile diagnostics. Crash reports, last-launch-cmd.log and
    # launch.log all live next to the profile's mods/ so support stuff
    # never crosses versions.
    cmd_log = profile_dir / "last-launch-cmd.log"
    cmd_log.write_text("\n".join(redacted), encoding="utf-8")
    print(f"[launch] command logged: {cmd_log}")
    print("[launch] Minecraft is starting — first boot can take 30-60s…")
    print("-" * 60)

    # Tee Java's output to both console and a log file so silent crashes leave
    # a trail. Runs with cwd=profile_dir so MC's relative paths (saves/,
    # screenshots/, crash-reports/) resolve into the per-version folder.
    # HIGH_PRIORITY_CLASS (=0x80) nudges the Windows scheduler to prefer our
    # Java process when there's CPU contention with background stuff.
    creationflags = 0
    if platform.system() == "Windows":
        creationflags = 0x80  # HIGH_PRIORITY_CLASS
    log_path = profile_dir / "launch.log"
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen(
            cmd, cwd=profile_dir,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
            creationflags=creationflags,
        )
        assert proc.stdout is not None
        # Keep chat auth alive for the whole session — re-refreshes the
        # token + rewrites shadow-chat-auth.json every ~20 min so sessions
        # longer than the ~24 h token lifetime (AFK farms, all-day grinds)
        # don't silently lose chat. Daemon thread, dies when MC exits.
        if acct_obj.user_type == "msa":
            prism_path = Path.home() / "AppData/Roaming/PrismLauncher/accounts.json"
            threading.Thread(
                target=_auth_refresh_loop,
                args=(proc, profile_dir, ACCOUNT_FILE, prism_path),
                daemon=True,
            ).start()
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            log.write(line)
        rc = proc.wait()
    print("-" * 60)
    print(f"[launch] Java exited with code {rc}")
    print(f"[launch] full log: {log_path}")
    return rc


# ---- doctor -------------------------------------------------------------------

# HTTP health endpoint of the same worker the mod connects to over wss://
# (see _write_shadow_chat_auth's default relay_url).
RELAY_HEALTH_URL = "https://shadow-chat-relay.edisongushf.workers.dev/health"
RELAY_HOST = "shadow-chat-relay.edisongushf.workers.dev"


def _ws_upgrade_status(host: str, token: str, channel: str, ua: str) -> int:
    """Open the real /ws WebSocket handshake and return its HTTP status.

    The relay's /health endpoint only proves the Worker is *up* — it never
    touches token verification. The actual auth path is the /ws upgrade,
    which calls Mojang to verify the token: 101 = accepted, 401 = rejected
    (expired token, OR the relay can't reach Mojang from its egress — the
    single most common "chat doesn't work" cause). stdlib http.client chokes
    on a 101 response, so we hand-roll the handshake over a raw TLS socket
    and read just the status line. Raises on any socket/TLS error.
    """
    import socket
    import ssl
    from urllib.parse import quote

    path = f"/ws?token={quote(token, safe='')}&channel={quote(channel, safe='')}"
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        f"User-Agent: {ua}\r\n"
        "\r\n"
    )
    ctx = ssl.create_default_context()
    raw = socket.create_connection((host, 443), timeout=12)
    try:
        with ctx.wrap_socket(raw, server_hostname=host) as s:
            s.sendall(req.encode("ascii"))
            # The status line ("HTTP/1.1 101 ...") arrives in the first
            # packet; read a small chunk and parse it.
            data = s.recv(256)
        line = data.split(b"\r\n", 1)[0].decode("ascii", "replace")
        parts = line.split(" ", 2)
        return int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else -1
    finally:
        try:
            raw.close()
        except OSError:
            pass


def _jwt_hours_left(token: str) -> float | None:
    """Hours until the JWT's `exp` claim; negative if already past.

    Returns None when the token isn't a parseable JWT. Same decode as
    auth._jwt_expired - kept separate because doctor wants the margin
    ("23.4h left"), not just the boolean verdict.
    """
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        exp = float(json.loads(base64.urlsafe_b64decode(payload)).get("exp", 0))
        return (exp - time.time()) / 3600.0
    except Exception:
        return None


def _minecraft_running() -> bool | None:
    """Best-effort: is a launcher-spawned Minecraft alive right now?

    Returns True if a java/javaw process whose command line references THIS
    install (SHARED_DIR) is running, False if we scanned and found none, and
    None if we couldn't tell (no supported probe, or the probe errored). The
    None case matters: `doctor --fix` refuses to rewrite the auth file unless
    it is *sure* MC is down, because the running game reads that file once at
    startup and a concurrent rewrite races with it.

    Windows only for the positive path (PowerShell + CIM command-line scan);
    on other OSes we return None so the caller stays conservative. Every
    failure mode collapses to None, never a traceback.
    """
    if platform.system() != "Windows":
        return None
    try:
        marker = str(SHARED_DIR.resolve())
    except Exception:
        marker = str(SHARED_DIR)
    # Match java*.exe processes whose CommandLine mentions our install root.
    # CIM (Get-CimInstance) is the supported replacement for the removed
    # wmic.exe on Windows 11. -replace strips backslashes from both sides so
    # path-separator quirks (C:\ vs C:/) never cause a false negative.
    ps = (
        "$m = ($env:SC_MARKER -replace '\\\\','/'); "
        "$p = Get-CimInstance Win32_Process -Filter "
        "\"Name='java.exe' OR Name='javaw.exe'\" -ErrorAction SilentlyContinue | "
        "Where-Object { $_.CommandLine -and "
        "(($_.CommandLine -replace '\\\\','/') -like ('*' + $m + '*')) }; "
        "if ($p) { 'RUNNING' } else { 'STOPPED' }"
    )
    try:
        r = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
            capture_output=True, text=True, timeout=20,
            env={**os.environ, "SC_MARKER": marker},
        )
    except (OSError, subprocess.SubprocessError):
        return None
    out = (r.stdout or "").strip()
    if "RUNNING" in out:
        return True
    if "STOPPED" in out:
        return False
    return None


def _doctor_fix_chat_auth(profile_dir: Path, acct, report) -> str | None:
    """Try to auto-recover an expired chat token (only called by --fix).

    Mirrors what `launch` does every boot: force a Microsoft token refresh,
    then rewrite shadow-chat-auth.json so the in-game mod can reconnect -
    auth.Account.refresh_if_needed -> _write_shadow_chat_auth, both of which
    already exist and are battle-tested. Returns the FRESH token string on a
    successful refresh (so the caller can re-check expiry + reuse it for the
    relay-auth probe), or None if it bailed / failed. Emits its own FIX/WARN
    report lines describing exactly what it did. Wrapped end-to-end so a
    network blip or a half-installed tree degrades to a WARN, never a crash.

    Refuses to act unless three preconditions hold, because a rewrite races
    the running game's one-shot read of the file:
      * we have a Microsoft (msa) account with refresh material, and
      * no launcher-spawned Minecraft is currently running, and
      * we could positively confirm MC is down (a 'maybe' is treated as up).
    """
    if acct is None or getattr(acct, "user_type", "") != "msa":
        report("WARN", "chat auth fix",
               "skipped - need a Microsoft account to refresh (run "
               "`client.py login`)")
        return None
    running = _minecraft_running()
    if running is not False:
        why = ("Minecraft is running" if running
               else "could not confirm Minecraft is closed")
        report("WARN", "chat auth fix",
               f"skipped - {why}; close the game, then re-run `doctor --fix` "
               "(the game reads the auth file once at startup, so refreshing "
               "it underneath a live session would not take effect anyway)")
        return None
    try:
        prism_path = Path.home() / "AppData/Roaming/PrismLauncher/accounts.json"
        use_prism = prism_path if prism_path.exists() else None
        refreshed = acct.refresh_if_needed(ACCOUNT_FILE, prism_path=use_prism,
                                           force=True)
    except Exception as e:
        report("WARN", "chat auth fix",
               f"token refresh failed ({e}); run `client.py login` to "
               "re-authenticate")
        return None
    if not refreshed:
        report("WARN", "chat auth fix",
               "Microsoft declined the refresh (refresh token may be expired) "
               "- run `client.py login`")
        return None
    # _write_shadow_chat_auth is itself best-effort (prints its own [chat]
    # line and swallows IO errors), so this won't raise either.
    _write_shadow_chat_auth(profile_dir, acct)
    report("FIX", "chat auth fix",
           f"refreshed token for '{acct.username}' and rewrote "
           "shadow-chat-auth.json")
    new_token = getattr(acct, "access_token", None)
    return new_token or None


def cmd_doctor(args: argparse.Namespace) -> int:
    """One-shot self-diagnosis for the "chat doesn't work" class of problems.

    Walks the dependency chain a working in-game chat needs - Java, profile +
    mods, the shadow-chat jar, the account, the per-profile
    shadow-chat-auth.json token, and the relay - printing one labeled
    PASS/FAIL/WARN line per finding with a concrete fix.

    Read-only by default: unlike resolve_dirs() it never mkdirs, never writes
    state, and every probe is wrapped so a half-installed (or never-installed)
    tree degrades to clean FAIL/WARN lines instead of a traceback. Output is
    strictly ASCII - doctor output is exactly the thing that gets pasted from
    broken cp1252 consoles.

    `--fix` enables exactly one mutation: if the chat-auth token is EXPIRED
    (the most common "chat doesn't work" cause) AND no launcher-spawned
    Minecraft is running, it force-refreshes the Microsoft token and rewrites
    shadow-chat-auth.json - the same auth.Account.refresh_if_needed ->
    _write_shadow_chat_auth path `launch` runs on every boot - then re-checks
    the token. Plain `doctor` stays 100% read-only.
    """
    fix = bool(getattr(args, "fix", False))
    labels: list[str] = []

    def report(label: str, name: str, detail: str) -> None:
        labels.append(label)
        print(f"[{label}] {name}: {detail}")

    print("Shadow Client doctor - checking the chat/launch dependency chain")
    if fix:
        print("(--fix on: will auto-refresh an EXPIRED chat token if MC is closed)")
    print("-" * 60)

    # 1. Java ------------------------------------------------------------
    try:
        java = find_java()
        major = java_major_version(java)
        if major >= 21:
            report("PASS", "java", f"{java} (Java {major})")
        else:
            report("FAIL", "java",
                   f"{java} is Java {major}, MC needs 21+ (fix: install a "
                   "JDK 21+ or point JAVA_HOME at one; `launch` can also "
                   "auto-download a bundled JDK)")
    except SystemExit as e:
        report("FAIL", "java", str(e))
    except Exception as e:
        report("FAIL", "java", f"probe failed: {e}")

    # 2. Profile ----------------------------------------------------------
    try:
        state = _state_load()
    except Exception as e:
        state = {"profiles": {}, "last_used": None}
        report("WARN", "state", f"{STATE_FILE.name} unreadable ({e}) - "
               "treating as empty (fix: re-run setup)")
    profile = _resolve_profile(state, getattr(args, "profile", None))
    profile_dir: Path | None = None
    mods_dir: Path | None = None
    if not profile:
        report("FAIL", "profile",
               f"no --profile given and no last_used in {STATE_FILE.name} "
               "(fix: run setup once, or pass --profile NAME)")
    elif profile not in state.get("profiles", {}):
        known = ", ".join(sorted(state.get("profiles", {}))) or "none"
        report("FAIL", "profile",
               f"'{profile}' not in {STATE_FILE.name} (known: {known}) "
               "(fix: run setup)")
    else:
        # resolve_dirs() mkdirs as a side effect; doctor stays read-only,
        # so build the same paths by hand.
        profile_dir = PROFILES_DIR / profile
        mods_dir = profile_dir / "mods"
        if not profile_dir.is_dir():
            report("FAIL", "profile",
                   f"'{profile}' is in state but its dir is missing: "
                   f"{profile_dir} (fix: run setup)")
        elif not mods_dir.is_dir():
            report("FAIL", "profile",
                   f"'{profile}' has no mods dir: {mods_dir} "
                   "(fix: run setup or update-mods)")
        else:
            jar_count = len(list(mods_dir.glob("*.jar")))
            report("PASS", "profile",
                   f"'{profile}' -> {profile_dir} ({jar_count} mod jar(s))")
            if not list(mods_dir.glob("fabric-api-*.jar")):
                report("WARN", "profile",
                       "no fabric-api-*.jar in mods - shadow-chat (and most "
                       "mods) won't load without it (fix: run update-mods)")

    # 3. Shadow Chat jar ----------------------------------------------------
    if mods_dir is None:
        report("WARN", "shadow-chat jar",
               "skipped - no usable profile (fix the profile check first)")
    else:
        jars = sorted(mods_dir.glob("shadow-chat-*.jar")) if mods_dir.is_dir() else []
        if not jars:
            report("FAIL", "shadow-chat jar",
                   f"no shadow-chat-*.jar in {mods_dir} (fix: run update-mods)")
        elif len(jars) > 1:
            names = ", ".join(j.name for j in jars)
            report("FAIL", "shadow-chat jar",
                   f"{len(jars)} copies on disk ({names}) - duplicate mod id "
                   "crashes Fabric at boot (fix: run update-mods, it sweeps "
                   "stale jars)")
        else:
            jar = jars[0]
            ver = jar.stem[len("shadow-chat-"):]
            # A .jar.new sibling means the new version is already staged
            # and self-promotes at the next launch — "run update-mods"
            # would be misleading advice (it can't replace a locked jar
            # either; the stage-and-promote dance exists for exactly
            # this window while MC holds the old file open).
            staged = sorted(mods_dir.glob("shadow-chat-*.jar.new"))
            if ver != mods.SHADOW_CHAT_VERSION and staged:
                staged_ver = staged[0].name[len("shadow-chat-"):-len(".jar.new")]
                report("PASS", "shadow-chat jar",
                       f"{staged_ver} staged - promotes at next launch "
                       f"({jar.name} active until then)")
            elif ver != mods.SHADOW_CHAT_VERSION:
                report("WARN", "shadow-chat jar",
                       f"{jar.name} is stale - launcher ships "
                       f"{mods.SHADOW_CHAT_VERSION} (fix: run update-mods)")
            else:
                report("PASS", "shadow-chat jar", f"{jar.name} (current)")

    # 4. Account ------------------------------------------------------------
    try:
        acct = auth.Account.load(ACCOUNT_FILE)
    except Exception as e:
        acct = None
        report("FAIL", "account",
               f"{ACCOUNT_FILE.name} unreadable ({e}) "
               "(fix: run `client.py login`)")
    else:
        if acct is None:
            report("FAIL", "account",
                   f"{ACCOUNT_FILE} missing - next launch falls back to "
                   "offline mode (fix: run `client.py login` for online "
                   "play + chat)")
        elif acct.user_type == "msa":
            report("PASS", "account", f"Microsoft account '{acct.username}' (msa)")
        else:
            report("WARN", "account",
                   f"offline account '{acct.username}' ({acct.user_type}) - "
                   "chat disabled - run `client.py login`")

    # 5. Chat auth file -------------------------------------------------------
    token = None  # hoisted: the relay-auth probe (#7) reuses it
    if profile_dir is None:
        report("WARN", "chat auth",
               "skipped - no usable profile (fix the profile check first)")
    else:
        auth_file = profile_dir / "shadow-chat-auth.json"
        readable = False
        if not auth_file.exists():
            report("FAIL", "chat auth",
                   f"{auth_file} missing (fix: launch via run.bat once - it "
                   "writes the file)")
        else:
            try:
                token = json.loads(auth_file.read_text(encoding="utf-8")).get("token")
                readable = True
            except Exception as e:
                report("WARN", "chat auth",
                       f"{auth_file.name} unparseable ({e}) (fix: launch via "
                       "run.bat - it rewrites the file)")
        if readable:
            if not token:
                report("WARN", "chat auth",
                       f"{auth_file.name} has token: null - chat is dormant "
                       "(offline account at last launch; run `client.py "
                       "login`, then launch)")
            else:
                hours = _jwt_hours_left(token)
                if hours is None:
                    report("WARN", "chat auth",
                           "token is not a parseable JWT (fix: launch via "
                           "run.bat - it rewrites the file)")
                else:
                    try:
                        # Reuse the launcher's own expiry logic (120s skew).
                        expired = auth._jwt_expired(token)
                    except Exception:
                        expired = hours <= 0
                    if expired and fix:
                        # INFO (not FAIL): we're about to repair it, so the
                        # final verdict should reflect the post-fix state, not
                        # this transient pre-fix one. The subsequent PASS/WARN
                        # (or the fixer's own WARN) is what counts.
                        report("INFO", "chat auth",
                               "token EXPIRED - attempting auto-refresh "
                               "(--fix)...")
                        fresh = _doctor_fix_chat_auth(profile_dir, acct, report)
                        if fresh:
                            # Re-check the freshly written token so the verdict
                            # and the relay-auth probe (#7) both see the new one.
                            token = fresh
                            new_hours = _jwt_hours_left(fresh)
                            try:
                                still_expired = auth._jwt_expired(fresh)
                            except Exception:
                                still_expired = (new_hours is not None
                                                 and new_hours <= 0)
                            if still_expired:
                                report("WARN", "chat auth",
                                       "refreshed token still reads as expired "
                                       "(clock skew?) - try `client.py login`")
                            else:
                                margin = (f" ({new_hours:.1f}h left)"
                                          if new_hours is not None else "")
                                report("PASS", "chat auth",
                                       f"token VALID after refresh{margin}")
                    elif expired:
                        report("FAIL", "chat auth",
                               "token EXPIRED (fix: re-run with `doctor --fix` "
                               "to auto-refresh, or launch via run.bat - it "
                               "rewrites the file)")
                    else:
                        report("PASS", "chat auth",
                               f"token VALID ({hours:.1f}h left)")

    # 6. Relay ----------------------------------------------------------------
    try:
        # Cloudflare's edge 403s the default Python-urllib User-Agent, which
        # would make this check cry "relay down" forever. Send the launcher's
        # UA (same one auth.py uses) so we measure the relay, not the bot wall.
        req = urllib.request.Request(RELAY_HEALTH_URL,
                                     headers={"User-Agent": mojang.UA})
        with urllib.request.urlopen(req, timeout=10) as r:
            code = r.status
        if code == 200:
            report("PASS", "relay", f"{RELAY_HEALTH_URL} -> HTTP {code}")
        else:
            report("FAIL", "relay",
                   f"{RELAY_HEALTH_URL} -> HTTP {code} (relay reachable but "
                   "unhealthy - chat may be down for everyone)")
    except Exception as e:
        report("FAIL", "relay",
               f"{RELAY_HEALTH_URL} -> {e} (no internet, a firewall, or the "
               "relay is down)")

    # 7. Relay auth path ------------------------------------------------------
    # /health only proves the Worker is up - it never verifies a token, so a
    # green relay check used to sit next to a totally broken chat. THIS probes
    # the real /ws auth handshake with the actual token, which is the leg that
    # actually decides whether chat connects in-game.
    if not token:
        report("WARN", "relay auth",
               "skipped - no usable token (fix the chat auth check first)")
    else:
        try:
            ws_code = _ws_upgrade_status(RELAY_HOST, token, "server:doctor", mojang.UA)
            if ws_code == 101:
                report("PASS", "relay auth",
                       "/ws handshake -> 101 (relay accepts your token)")
            elif ws_code == 401:
                report("FAIL", "relay auth",
                       "/ws handshake -> 401: relay REJECTED a token Mojang "
                       "itself accepts. The relay verifies tokens by calling "
                       "Mojang from its Cloudflare-Worker egress; a consistent "
                       "401 means that egress can't reach Mojang (IP block) - "
                       "chat will not connect for anyone until the relay is "
                       "redeployed or its verification path is changed")
            elif ws_code == 400:
                report("FAIL", "relay auth",
                       "/ws handshake -> 400 (bad token/channel encoding - "
                       "report this, it is a client bug)")
            else:
                report("WARN", "relay auth",
                       f"/ws handshake -> HTTP {ws_code} (unexpected; relay "
                       "may be mid-deploy)")
        except Exception as e:
            report("FAIL", "relay auth",
                   f"/ws handshake failed to connect: {e}")

    # Verdict -------------------------------------------------------------
    print("-" * 60)
    issues = labels.count("FAIL") + labels.count("WARN")
    if issues == 0:
        print("All checks passed - chat should work in-game (press ; )")
        return 0
    print(f"{issues} issue(s) found - see fixes above")
    return 1


# ---- cli ---------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="client.py")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("setup", help="download Minecraft + install Fabric + mods")
    s.add_argument("--username", default="Player")
    s.add_argument("--version", default="latest", help='Minecraft version or "latest"')
    s.add_argument("--profile", default=None,
                   help="Per-version profile name (mods/saves go to "
                        "game_dir/profiles/<profile>/). Defaults to the MC "
                        "version so each version gets its own isolated profile.")
    s.add_argument("--no-fabric", action="store_true")
    s.add_argument("--no-mods", action="store_true")

    lg = sub.add_parser("login", help="import or sign into a Microsoft account")
    lg.add_argument("--source", choices=["prism", "microsoft"], default="prism",
                    help="'prism' (default) reuses your PrismLauncher token; "
                         "'microsoft' runs the device-code flow (may fail if the "
                         "public client ID is disabled)")

    um = sub.add_parser("update-mods", help="redownload the performance mod stack")
    um.add_argument("--profile", default=None,
                    help="Which profile to update. Defaults to last_used.")

    lo = sub.add_parser("logout", help="sign out of the Microsoft account (back to offline)")
    lo.add_argument("--username", default="Player",
                    help="Offline username to revert to after sign-out.")

    sub.add_parser("build-hud",   help="compile + install the Shadow HUD mod (FPS/coords/biome overlay)")

    d = sub.add_parser("doctor", help="diagnose chat / launch problems (read-only self-check)")
    d.add_argument("--profile", default=None,
                   help="Which profile to inspect. Defaults to last_used.")
    d.add_argument("--fix", action="store_true",
                   help="Auto-refresh an EXPIRED chat-auth token when MC is "
                        "closed (rewrites shadow-chat-auth.json). Without this "
                        "flag, doctor only reports and never mutates.")

    l = sub.add_parser("launch", help="launch Minecraft")
    l.add_argument("--heap", type=int, default=6144, help="heap size in MB (default 6144)")
    l.add_argument("--gc", choices=["g1", "zgc", "safe"], default="g1",
                   help="GC profile; 'safe' = bare minimum flags for troubleshooting")
    l.add_argument("--java", help="path to java / javaw executable")
    l.add_argument("--username")
    l.add_argument("--profile", default=None,
                   help="Which version's profile to launch. Defaults to last_used.")

    args = p.parse_args(argv)
    return {"setup": cmd_setup, "login": cmd_login, "logout": cmd_logout,
            "launch": cmd_launch, "update-mods": cmd_update_mods,
            "build-hud": cmd_build_hud, "doctor": cmd_doctor}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
