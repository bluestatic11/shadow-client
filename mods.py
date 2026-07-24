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
    # exordium removed 2026-07-12: maintenance-mode mod that batches HUD
    # rendering into a framebuffer; officially unsupported alongside UI mods,
    # and the top real-world cause of "Pose stack not empty" crashes. This
    # stack is HUD-heavy (ShadowHud + Health Indicators + Combat Hitboxes +
    # Crosshair Addons + Armor HUD ...), so Exordium fighting them for render
    # state is the prime suspect for the intermittent render-pass crash.

    # --- CPU / memory / network ------------------------------------------
    ("krypton",            "network stack optimizations",               False),
    ("c2me-fabric",        "parallel chunk loading/gen across cores",   False),
    ("threadtweak",        "thread scheduling — smoother frametimes",   False),
    ("moreculling",        "extra block culling wins",                  False),
    ("memoryleakfix",      "patches known memory leaks",                False),
    ("modernfix",          "many bug + memory fixes",                   False),
    ("badoptimizations",   "misc small wins",                           False),
    ("packet-fixer",       "fixes server packet stalls",                False),
    ("language-reload",    "faster launch + lower language memory",     False),

    # --- entity / world tuning -------------------------------------------
    ("lmd",                "Let Me Despawn — fewer idle mobs",          False),
    ("get-it-together-drops","merge item entities on the ground",       False),
    ("clumps",             "merge XP orbs (fewer entities; helps singleplayer)", False),
    ("ksyxis",             "faster world load (skip spawn-chunk preload)", False),
    ("distanthorizons",    "LOD renderer — huge render distance for cheap", False),
    ("bobby",              "cache chunks past the server's render distance", False),
    ("debugify",           "fixes dozens of vanilla bugs incl. perf bugs", False),
    ("fastquit",           "save-and-quit without the freeze (async world save)", False),
    ("fast-ip-ping",       "faster server-list pings",                  False),
    ("rrls",               "Remove Reloading Screen (no stutter reload)",False),
    ("puzzle",             "resource-pack / particle cache",            False),

    # --- options UI ------------------------------------------------------
    ("sodium-extra",       "extra sodium options (zoom, etc.)",         False),

    # --- PvP / QoL (Lunar-style, server-legal) ---------------------------
    # Only rendering/info/QoL — nothing that changes reach, targeting, or
    # automates actions (same line ShadowHud draws: EnemyArmor/DamageIndicator
    # /AutoEat were all removed as unfair).
    ("appleskin",          "hunger + saturation overlay (food timing)", False),
    ("mouse-tweaks",       "inventory drag/scroll QoL",                 False),
    ("zoomify",            "Lunar-style zoom key",                      False),
    ("no-chat-reports",    "strip chat-report signatures (PvP-server standard)", False),
    ("better-ping-display-fabric", "numeric ping in the tab list",      False),
    ("armor-hud",          "armor pieces + durability by the hotbar",   False),
    # low-fire-reborn removed 2026-07-11: installed today (was pending during
    # the crash-free days) and modifies fire-OVERLAY rendering — a render-pass
    # matrix op, exactly what "Pose stack not empty" crashes point to. Prime
    # suspect for the returning crash. Low fire is covered by the LT3 resource
    # pack + dynamic-fire-overlay anyway.
    ("dynamic-fire-overlay","low fire — shrink/lower the burning overlay", False),
    ("shieldscale",        "low shield — custom shield scale/position", False),
    ("shield-statuses",    "color-coded shield states (up/disabled/cooldown)", False),
    ("complete-shield-fixes", "shield sound + blocking-animation bugfixes", False),
    ("shield-crosshair-indicator", "crosshair icon when target blocks + axe-disable warning", False),
    ("old-shield-animation", "revert 23w40a third-person shield arm pose", False),
    ("3dskinlayers",       "3D skin layers (hats/jackets render with depth)", False),
    ("not-enough-animations", "vanilla-style third-person animations (ladders, eating, maps)", False),
    # Crystal PvP standard (3.5M downloads): client-side prediction that
    # hides broken crystals immediately instead of waiting on the server
    # round-trip. Allowed on most CPvP servers, but rules vary — some
    # (e.g. VTL) ban optimizers outright; that's on the player to check.
    ("marlow-crystal-optimizer", "crystal PvP — no wait on server crystal cleanup", False),

    # --- Walksy CPvP suite + combat hitboxes ------------------------------
    # Deliberately skipped from the same ecosystem: totem-pop-chams
    # (through-walls highlight = ESP), client-kits / rebind-quick-swap
    # (automation-adjacent).
    ("modmenu",            "in-game mod list + config screens",         False),
    ("extrapvp",           "hitbox highlights, elytra + gapple indicators", False),
    ("crosshair-addons-public", "Lunar-style crosshair customization",  False),
    ("crosshair-attack-indicator", "crosshair tints red when target in reach", False),
    ("consumableoptimizer","no desync on gapples/pots (Walksy optimizer)", False),
    ("anchor-optimizer",   "anchor PvP — no wait on server anchor cleanup", False),
    ("no-death-animation", "instant death poof — see totem pops clearly", False),
    # 2.9M dl. Overlaps ShadowHud's NoHurtCam module — leave that module OFF
    # when this is installed; the mod adds per-axis/FOV shake control on top.
    ("betterhurtcam",      "adjustable/disable hurt-cam shake",          False),

    # --- cherry-picked from the SODIUM community pack (stable builds only) -
    ("iris",               "shader support (OptiFine-style, Sodium-native)", False),
    ("reeses-sodium-options", "better video-settings UI for Sodium",    False),
    ("sodium-fullbright",  "fullbright toggle (Lunar-standard)",        False),
    ("tiertagger",         "tierlist ranks on players in tab/nametags", False),
    ("in-game-account-switcher", "switch MC accounts without restarting", False),
    ("scoreboardtweaks",   "hide/shrink the sidebar scoreboard",        False),
    ("cursorcentered-fix", "cursor re-centers when menus close",        False),
    ("multidisplayfix",    "fullscreen on the right monitor (multi-display)", False),
    # slug really is "cpvp" — this is Totem Tweaks (2M dl): small totem pop.
    # Skipped from the same search: inventory-totem/void-totem (mechanics,
    # server-side anyway), Silly's/Plus auto-totems (automation = cheat).
    ("cpvp",               "Totem Tweaks — small totem pop, custom pop anim", False),
    ("capes",              "cape support (OptiFine/Cosmetica capes render)", False),
    # (re-added 2026-07-21 — cleared of suspicion; crashes traced to
    # display-link hardware, not these mods)
    ("xaeros-minimap",     "minimap + waypoints (turn OFF entity radar on PvP servers)", False),
    ("xaeros-world-map",   "full explorable world map with waypoints",  False),
    ("chat-heads",         "player face next to their chat messages",   False),
    # clears the fog/overlay when your camera is submerged in water/lava/
    # powder snow — see through it clearly. Rendering-only (honor-system on
    # servers that ban vision mods, like fullbright/ClearSight).
    ("clear-waterlavapowdersnow", "see through water/lava/powder snow when submerged", False),
    ("status-effect-bars", "potion effects as timer bars",              False),
    # Kept at user's request despite being prime suspect for the 2026-07-08
    # "Pose stack not empty" PvP crashes — if they recur, first try setting
    # armorRenderingEnabled:false in config/healthindicators.json before
    # removing (the armor-icon path over custom server gear is the likely
    # broken half).
    ("health-indicators",  "hearts above players/mobs (synced HP only)",False),
    ("combat-hitboxes",    "hitboxes recolor when target is in your sights (1.4M dl)", False),

    # --- shared library dependencies (required by mods above) ------------
    # These aren't FPS mods themselves — they're libraries other mods pull
    # in. Without them Fabric refuses to launch. Order doesn't matter at
    # install time, but keeping them last makes the log tidier.
    ("almanac",                "lib: needed by Let Me Despawn",         False),
    ("yacl",                   "lib: needed by Zoomify, Combat Hitboxes, Debugify", False),
    ("architectury-api",       "lib: needed by Health Indicators",      False),
    ("walksylib",              "lib: needed by Shield Statuses + Shield Fixes", False),
    ("cloth-config",           "lib: needed by Shield Crosshair Indicator", False),
    ("placeholder-api",        "lib: needed by Mod Menu",               False),
    ("ukulib",                 "lib: needed by BetterHurtCam",          False),
    # owo-lib removed with low-fire-reborn (its only consumer) 2026-07-11
    ("fabric-language-kotlin", "lib: needed by Particle Core",          False),
    ("fzzy-config",            "lib: needed by Particle Core",          False),
    ("forge-config-api-port",  "lib: needed by RRLS",                   False),
]


# Mods that are version-locked to a sibling and must NOT auto-update to the
# newest build. Iris and Sodium ship as a matched pair — each Iris release
# supports exactly ONE Sodium API version, so picking "newest of each"
# independently crashes Fabric at load with an incompatibility error. Pin
# Sodium to the version the current Iris requires. **When Iris ships a build
# that supports a newer Sodium, bump or delete this entry** — check the Iris
# version's Sodium dependency on Modrinth. Value is a substring matched
# against the Modrinth version_number.
VERSION_PINS: dict[str, str] = {
    # The Iris/Sodium/Sodium-Extra trio is version-locked together:
    #   Iris 1.10.7 (newest)        needs Sodium >=0.8.7  AND  <0.8.13
    #   Sodium Extra 0.9.3 (newest) needs Sodium >=0.8.13
    # These two can't both run their newest on one Sodium. Iris + Sodium
    # 0.8.12 is the shader-capable pair, so we pin Sodium to 0.8.12 and hold
    # Sodium Extra at 0.9.1 (last build that works with 0.8.12 — verified
    # loading together). WHEN IRIS SHIPS A BUILD SUPPORTING 0.8.13, unpin
    # all three so the whole trio jumps forward together.
    "sodium": "0.8.12",
    "sodium-extra": "0.9.1",
}


def _pick_version(slug: str, mc_version: str, loader: str = "fabric") -> dict[str, Any] | None:
    """Return the newest Modrinth version compatible with (mc_version, loader).

    Only accepts **exact** `mc_version` matches in `game_versions`. The previous
    fallback ("same 1.21.x series") pulled mods built against 1.21.1 into
    1.21.11 installs, which crashed at mixin-apply time when method signatures
    had changed — see ModernFix/Noisium earlier. Better to miss a mod than
    install a poisoned one.
    """
    pin = VERSION_PINS.get(slug)
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
        # Version-locked mods (see VERSION_PINS): skip any build whose version
        # string doesn't contain the pinned token. Release sorts ahead of its
        # own betas in Modrinth's newest-first order, so the first match is the
        # pinned release, not a beta of it.
        if pin and pin not in v.get("version_number", ""):
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
SHADOW_CHAT_VERSION = "0.1.48"
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
