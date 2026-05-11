"""Shadow Client — a from-scratch Minecraft launcher tuned for max FPS.

Commands:
  setup  [--username NAME] [--online] [--version V] [--heap MB] [--gc g1|zgc]
         [--no-mods]
  login
  launch [--heap MB] [--gc g1|zgc] [--username NAME]
  update-mods

The first `setup` run downloads vanilla, installs Fabric, and pulls the
performance mod stack. `launch` re-uses the installed state. Login is only
needed if you want to play on online-mode / real Mojang servers — offline
mode works out-of-the-box for singleplayer + offline-mode LAN servers.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
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
GAME_DIR = HERE / "game_dir"
ACCOUNT_FILE = GAME_DIR / "mc-client-account.json"
STATE_FILE = HERE / "installed.json"

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


def _state_load() -> dict:
    if not STATE_FILE.exists():
        return {}
    try:
        return json.loads(STATE_FILE.read_text())
    except (json.JSONDecodeError, OSError) as e:
        print(f"[state] {STATE_FILE.name} corrupt ({e}); treating as empty. "
              f"Re-run `setup` to rebuild.")
        return {}


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
    GAME_DIR.mkdir(parents=True, exist_ok=True)

    print("[setup] fetching Mojang version manifest…")
    manifest = mojang.fetch_manifest()
    vanilla_entry = mojang.resolve_version(manifest, args.version)
    mc_version = vanilla_entry["id"]
    print(f"[setup] target MC version: {mc_version}")

    vanilla = mojang.fetch_version_json(vanilla_entry, GAME_DIR)

    if not args.no_fabric:
        loader_v = fabric.latest_loader(mc_version)
        print(f"[setup] Fabric loader {loader_v}")
        fabric_profile = fabric.fetch_profile(mc_version, loader_v)
        version = fabric.merge_into_vanilla(vanilla, fabric_profile)
    else:
        version = vanilla
        loader_v = None

    mojang.download_libraries(version, GAME_DIR)
    mojang.download_client_jar(vanilla, GAME_DIR)
    mojang.download_assets(vanilla, GAME_DIR)

    # Persist the resolved merged version JSON so `launch` doesn't refetch Fabric.
    version_json_path = GAME_DIR / "versions" / version["id"] / f"{version['id']}.json"
    _atomic_write(version_json_path, json.dumps(version, indent=2))

    installed_mods: list[str] = []
    if not args.no_mods and not args.no_fabric:
        print("[setup] installing performance mods…")
        installed_mods, _ = mods.install_mods(GAME_DIR / "mods", mc_version)

    # Seed options.txt if absent
    opts = GAME_DIR / "options.txt"
    if not opts.exists():
        _atomic_write(opts, jvm.OPTIONS_TXT)
        print("[setup] wrote default options.txt")

    _state_save({
        "mc_version": mc_version,
        "fabric_loader": loader_v,
        "version_id": version["id"],
        "vanilla_version_id": vanilla["id"],
        "client_jar": str((GAME_DIR / "versions" / vanilla["id"] / f"{vanilla['id']}.jar").resolve()),
        "installed_mods": installed_mods,
    })
    print(f"[setup] done — {len(installed_mods)} mods installed")

    if not ACCOUNT_FILE.exists():
        acct = auth.offline(args.username)
        acct.save(ACCOUNT_FILE)
        print(f"[setup] created offline account for '{args.username}' — run `login` for online play")

    # Auto-build the Shadow HUD mod — best-effort; don't fail setup if javac absent.
    build_py = HERE / "branding" / "hud_mod" / "build.py"
    if build_py.exists():
        print("[setup] compiling Shadow HUD…")
        rc = subprocess.call([sys.executable, str(build_py)])
        if rc != 0:
            print("[setup] Shadow HUD compile skipped (JDK or build-deps missing)")
    return 0


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
    """Refresh only the launcher-managed performance mods.

    Scoped deletion — we only remove jars whose *filename* starts with the slug
    of a mod we installed previously. User-dropped mods, Hacker Mode imports,
    Shadow Client branding + HUD jars, and the gametweaks rename are all left
    alone.
    """
    state = _state_load()
    if not state:
        raise SystemExit("Run `setup` first")
    mods_dir = GAME_DIR / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)

    # Delete only jars whose filename heuristically belongs to a previously-
    # managed slug. Never rmtree the whole directory.
    removed = 0
    prev_slugs = [s.lower() for s in state.get("installed_mods", [])]
    for jar in list(mods_dir.glob("*.jar")):
        name_lc = jar.name.lower()
        if any(name_lc.startswith(slug) for slug in prev_slugs):
            jar.unlink()
            removed += 1

    installed, skipped = mods.install_mods(mods_dir, state["mc_version"])
    state["installed_mods"] = installed
    _state_save(state)
    print(f"[update-mods] removed={removed}  installed={len(installed)}  skipped={len(skipped)}")
    print(f"[update-mods] user-added mods preserved: "
          f"{len(list(mods_dir.glob('*.jar'))) - len(installed)} jars")
    return 0


def _promote_staged_mods() -> None:
    """Replace mods with their sibling `.jar.new` build artifacts if any exist.

    Happens when the HUD (or any mod) was rebuilt while MC held a handle on
    the previous jar. This pass runs right before launch, so restarts just work.
    """
    mods_dir = GAME_DIR / "mods"
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


def cmd_launch(args: argparse.Namespace) -> int:
    _promote_staged_mods()
    state = _state_load()
    if not state:
        raise SystemExit("Run `setup` first")

    version_id = state["version_id"]
    version_json = GAME_DIR / "versions" / version_id / f"{version_id}.json"
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
        jar = GAME_DIR / "libraries" / artifact["path"]
        if jar.exists():
            classpath.append(jar)

    natives_dir = GAME_DIR / "versions" / version_id / "natives"
    client_jar = Path(state["client_jar"])

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

    # JVM flags — we pass library.path/classpath via the vanilla arg substitution.
    jvm_extra = jvm.flags(args.heap, gc=args.gc)

    all_args, main_class = mojang.build_args(
        version,
        username=acct_obj.username,
        uuid=acct_obj.uuid,
        access_token=acct_obj.access_token,
        user_type=acct_obj.user_type,
        game_dir=GAME_DIR,
        assets_dir=GAME_DIR / "assets",
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
        raise SystemExit(f"Java {jmaj} can't run MC {state['mc_version']} (needs {required}).")
    all_args = filter_args_for_java(all_args, jmaj)
    cmd = [str(java), *all_args]
    print(f"[launch] {java}  (Java {jmaj})")
    print(f"[launch] main: {main_class}")
    print(f"[launch] heap: {args.heap}M  gc: {args.gc}")
    print(f"[launch] user: {acct_obj.username} ({acct_obj.user_type})")
    print(f"[launch] mods: {len(state.get('installed_mods', []))} installed")

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
    cmd_log = GAME_DIR / "last-launch-cmd.log"
    cmd_log.write_text("\n".join(redacted), encoding="utf-8")
    print(f"[launch] command logged: {cmd_log}")
    print("[launch] Minecraft is starting — first boot can take 30-60s…")
    print("-" * 60)

    # Tee Java's output to both console and a log file so silent crashes leave
    # a trail. Runs with cwd=game_dir so MC's relative paths resolve there.
    # HIGH_PRIORITY_CLASS (=0x80) nudges the Windows scheduler to prefer our
    # Java process when there's CPU contention with background stuff.
    creationflags = 0
    if platform.system() == "Windows":
        creationflags = 0x80  # HIGH_PRIORITY_CLASS
    log_path = GAME_DIR / "launch.log"
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        proc = subprocess.Popen(
            cmd, cwd=GAME_DIR,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
            creationflags=creationflags,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            log.write(line)
        rc = proc.wait()
    print("-" * 60)
    print(f"[launch] Java exited with code {rc}")
    print(f"[launch] full log: {log_path}")
    return rc


# ---- cli ---------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="client.py")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("setup", help="download Minecraft + install Fabric + mods")
    s.add_argument("--username", default="Player")
    s.add_argument("--version", default="latest", help='Minecraft version or "latest"')
    s.add_argument("--no-fabric", action="store_true")
    s.add_argument("--no-mods", action="store_true")

    lg = sub.add_parser("login", help="import or sign into a Microsoft account")
    lg.add_argument("--source", choices=["prism", "microsoft"], default="prism",
                    help="'prism' (default) reuses your PrismLauncher token; "
                         "'microsoft' runs the device-code flow (may fail if the "
                         "public client ID is disabled)")
    sub.add_parser("update-mods", help="redownload the performance mod stack")
    sub.add_parser("build-hud",   help="compile + install the Shadow HUD mod (FPS/coords/biome overlay)")

    l = sub.add_parser("launch", help="launch Minecraft")
    l.add_argument("--heap", type=int, default=6144, help="heap size in MB (default 6144)")
    l.add_argument("--gc", choices=["g1", "zgc", "safe"], default="g1",
                   help="GC profile; 'safe' = bare minimum flags for troubleshooting")
    l.add_argument("--java", help="path to java / javaw executable")
    l.add_argument("--username")

    args = p.parse_args(argv)
    return {"setup": cmd_setup, "login": cmd_login, "launch": cmd_launch,
            "update-mods": cmd_update_mods, "build-hud": cmd_build_hud}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
