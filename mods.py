"""Performance mod installer.

Pulls Fabric-compatible performance mods straight from Modrinth's public API.
All chosen mods are pure optimizations — they change rendering, memory, and
chunk internals, but do not alter gameplay or break multiplayer anti-cheat.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mojang import _http_get, _download

MODRINTH = "https://api.modrinth.com/v2"

# (slug, description, critical?) — ordered by impact on FPS.
#
# The list includes a few slugs that don't yet ship a 1.21.11 build
# (nvidium, ebe) — _pick_version skips them with a "skip" message and
# picks them up automatically the next time you run `update-mods` once
# their maintainers release. Keeping them here documents intent.
PERFORMANCE_MODS: list[tuple[str, str, bool]] = [
    # --- core renderer / game-logic stack (must-have) ---------------------
    ("fabric-api",         "required by most fabric mods",              True),
    ("sodium",             "modern rendering engine — biggest FPS win", True),
    ("lithium",            "game logic / tick optimizations",           True),
    ("ferrite-core",       "reduces memory footprint",                  True),
    ("immediatelyfast",    "faster HUD / GUI rendering",                True),
    ("entityculling",      "skip rendering occluded entities",          True),
    ("dynamic-fps",        "drop FPS when window is unfocused",         True),

    # --- GPU-side extras (big wins when supported) -----------------------
    ("nvidium",            "NVIDIA mesh-shader accelerated chunks",     False),
    ("ebe",                "Enhanced Block Entities — baked chests",    False),
    ("particle-core",      "GPU-batched particle rendering",            False),
    ("scalablelux",        "modern lighting engine (Phosphor successor)",False),

    # --- CPU / memory / network ------------------------------------------
    ("krypton",            "network stack optimizations",               False),
    ("moreculling",        "extra block culling wins",                  False),
    ("memoryleakfix",      "patches known memory leaks",                False),
    ("modernfix",          "many bug + memory fixes",                   False),
    ("badoptimizations",   "misc small wins",                           False),
    ("packet-fixer",       "fixes server packet stalls",                False),
    ("language-reload",    "faster launch + lower language memory",     False),

    # --- entity / world tuning -------------------------------------------
    ("lmd",                "Let Me Despawn — fewer idle mobs",          False),
    ("get-it-together-drops","merge item entities on the ground",       False),
    ("rrls",               "Remove Reloading Screen (no stutter reload)",False),
    ("puzzle",             "resource-pack / particle cache",            False),

    # --- options UI ------------------------------------------------------
    ("sodium-extra",       "extra sodium options (zoom, etc.)",         False),

    # --- shared library dependencies (required by mods above) ------------
    # These aren't FPS mods themselves — they're libraries other mods pull
    # in. Without them Fabric refuses to launch. Order doesn't matter at
    # install time, but keeping them last makes the log tidier.
    ("almanac",                "lib: needed by Let Me Despawn",         False),
    ("fabric-language-kotlin", "lib: needed by Particle Core",          False),
    ("fzzy-config",            "lib: needed by Particle Core",          False),
    ("forge-config-api-port",  "lib: needed by RRLS",                   False),
]


def _pick_version(slug: str, mc_version: str, loader: str = "fabric") -> dict[str, Any] | None:
    """Return the newest Modrinth version compatible with (mc_version, loader).

    Only accepts **exact** `mc_version` matches in `game_versions`. The previous
    fallback ("same 1.21.x series") pulled mods built against 1.21.1 into
    1.21.11 installs, which crashed at mixin-apply time when method signatures
    had changed — see ModernFix/Noisium earlier. Better to miss a mod than
    install a poisoned one.
    """
    try:
        versions = json.loads(_http_get(f"{MODRINTH}/project/{slug}/version"))
    except Exception as e:
        print(f"[mods] {slug}: lookup failed ({e})")
        return None
    for v in versions:  # Modrinth returns newest first
        if loader not in v.get("loaders", []):
            continue
        if mc_version not in v.get("game_versions", []):
            continue
        if v.get("version_type") == "alpha":
            continue
        return v
    return None


# ─── Direct-URL mods (not on Modrinth) ──────────────────────────────────
# Shadow Chat is published on GitHub Releases by CI (release-shadow-chat.yml
# fires on every chat-mod-v* tag). Keep VERSION in sync with the Tauri
# launcher's MOD_JAR_URL in launcher/src-tauri/src/shadow_chat.rs — both
# sides are bumped together in the release convention. Without this entry,
# run.bat users never received the chat mod (or its updates) from
# setup / update-mods; only the Tauri launcher's installer carried it.
SHADOW_CHAT_VERSION = "0.1.39"
SHADOW_CHAT_URL = (
    "https://github.com/bluestatic11/shadow-client/releases/download/"
    f"chat-mod-v{SHADOW_CHAT_VERSION}/shadow-chat-{SHADOW_CHAT_VERSION}.jar"
)
SHADOW_CHAT_MC_VERSIONS = ("1.21.10", "1.21.11", "26.1.2")


def _install_shadow_chat(mods_dir: Path, mc_version: str) -> bool:
    """Fetch-then-sweep install of the Shadow Chat jar.

    Download the new version FIRST, and only then delete older
    shadow-chat-*.jar files — if the release isn't published yet (launcher
    version bumped ahead of the tag) the old jar stays as a working
    fallback. Two jars with the same mod id crash Fabric on duplicate-mod,
    so the sweep is mandatory once the new one is on disk. Non-fatal on
    failure; the next setup / update-mods retries.
    """
    if mc_version not in SHADOW_CHAT_MC_VERSIONS:
        print(f"[mods]   skip shadow-chat — not built for MC {mc_version}")
        return False
    filename = f"shadow-chat-{SHADOW_CHAT_VERSION}.jar"
    dest = mods_dir / filename
    if dest.exists():
        return True  # current version already installed
    print(f"[mods] shadow-chat       -> {filename}  (in-game chat overlay)")
    try:
        _download(SHADOW_CHAT_URL, dest, None)
    except Exception as e:
        print(f"[mods]   shadow-chat download failed ({e}) — keeping any existing jar")
        return False
    for stale in mods_dir.glob("shadow-chat-*.jar"):
        if stale.name != filename:
            try:
                stale.unlink()
                print(f"[mods]   removed stale {stale.name}")
            except OSError:
                # Locked (MC running) — Fabric would see a duplicate next
                # launch, so park the new jar as .new for _promote_staged_mods
                # and put the old one back in charge.
                try:
                    dest.rename(dest.with_suffix(".jar.new"))
                    print(f"[mods]   {stale.name} is locked — staged {filename}.new for next launch")
                except OSError:
                    pass
                return False
    return True


def install_mods(mods_dir: Path, mc_version: str) -> tuple[list[str], list[str]]:
    mods_dir.mkdir(parents=True, exist_ok=True)
    installed: list[str] = []
    skipped: list[str] = []
    for slug, desc, critical in PERFORMANCE_MODS:
        v = _pick_version(slug, mc_version)
        if not v:
            msg = f"  skip {slug} — no {mc_version} fabric build"
            if critical:
                msg += "  (!)"
            print(f"[mods] {msg}")
            skipped.append(slug)
            continue
        file = v["files"][0]
        dest = mods_dir / file["filename"]
        print(f"[mods] {slug:18} → {file['filename']}  ({desc})")
        _download(file["url"], dest, file.get("hashes", {}).get("sha1"))
        installed.append(slug)

    # Direct-URL mods (GitHub Releases, not Modrinth).
    if _install_shadow_chat(mods_dir, mc_version):
        installed.append("shadow-chat")
    else:
        skipped.append("shadow-chat")
    return installed, skipped
