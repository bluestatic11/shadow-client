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
    return installed, skipped
