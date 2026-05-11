"""Write max-FPS configs for every mod in the stack.

Idempotent: run it any time to re-apply. Each config is written directly to
game_dir/config/ (or the specific path each mod expects).

Knobs are chosen conservatively — aggressive enough to push past 600 FPS on
this rig, safe enough that no mod should crash. If anything misbehaves, delete
the file and relaunch to get the mod's default config back.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
GAME = ROOT / "game_dir"
CFG = GAME / "config"


def write(path: Path, content: str | dict, *, mkparents: bool = True, label: str = "") -> None:
    if mkparents:
        path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, dict):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")
    rel = path.relative_to(ROOT)
    print(f"  wrote {rel}" + (f"  ({label})" if label else ""))


# ---- options.txt (vanilla) ---------------------------------------------------

OPTIONS_TXT = """\
version:3955
renderDistance:8
simulationDistance:5
maxFps:2000
enableVsync:false
graphicsMode:0
ao:0
biomeBlendRadius:0
entityShadows:false
entityDistanceScaling:0.5
particles:0
fancyGraphics:false
cloudHeight:0
renderClouds:"false"
fullscreen:false
smoothLighting:0
mipmapLevels:0
useNativeTransport:true
guiScale:0
fov:0.525
mouseSensitivity:0.2605633802816901
glintStrength:0.85
glintSpeed:0.5
chatVisibility:0
resourcePacks:["vanilla"]
gamma:1.0
attackIndicator:1
chatOpacity:1.0
chatScale:1.0
chatWidth:1.0
darkMojangStudiosBackground:true
enableVibrator:false
narrator:0
autoJump:false
chatHeightFocused:1.0
chatHeightUnfocused:0.44366195797920227
glDebugVerbosity:0
hideLightningFlashes:true
hideSplashTexts:false
joinedFirstServer:true
reducedDebugInfo:false
showAutosaveIndicator:false
showSubtitles:false
skipMultiplayerWarning:true
skipRealms32bitWarning:true
tutorialStep:none
"""


# ---- sodium 0.8.x schema -----------------------------------------------------

SODIUM_OPTIONS = {
    "quality": {
        "hidden_fluid_culling": True,
        "improved_fluid_shaping": False,
    },
    "performance": {
        "chunk_builder_threads": 0,            # 0 = auto (uses all cores)
        "chunk_build_defer_mode": "ALWAYS",     # never block main thread on chunk rebuilds
        "animate_only_visible_textures": True,  # skip offscreen animated textures
        "use_entity_culling": True,
        "use_fog_occlusion": True,
        "use_block_face_culling": True,
        "use_no_error_g_l_context": True,       # skip GL error checking
        "quad_splitting_mode": "SAFE",
    },
    "advanced": {
        "enable_memory_tracing": False,
        "use_advanced_staging_buffers": True,   # async GPU uploads
        "cpu_render_ahead_limit": 3,
    },
    "debug": {"terrain_sorting_enabled": True},
    "notifications": {
        "has_cleared_donation_button": True,
        "has_seen_donation_prompt": True,
    },
}


SODIUM_EXTRA_OPTIONS = {
    "animation_settings": {
        "animations": True,
        "water": True, "lava": True, "fire": True, "portal": True,
        "block_animations": True, "sculk_sensor": True, "items": True,
    },
    "particle_settings": {
        "particles":              True,
        "rain_splash":            False,  # heavy on GPU, off for FPS
        "block_breaking":         True,
        "ambient_entity_effect":  True,   # <- potion effect swirls ON
        "damage_indicator":       True,   # <- critical hit sparkles ON
        "other":                  {},     # must be an object per Sodium Extra schema
    },
    "detail_settings": {
        "sky": True, "sun_moon": True, "stars": True, "weather": False,
        "biome_colors": True, "sky_colors": True,
    },
    "render_settings": {
        "light_updates": True,
        "item_frame_name_tags": False, "player_name_tags": True,
        "toasts": True,
        "advancement_toast": False, "recipe_toast": False,
        "system_toast": True, "tutorial_toast": False,
    },
    "extra_settings": {
        "overlay_corner": "TOP_LEFT",
        "text_contrast": "BACKGROUND",
        "show_fps": True,
        "show_fps_extended": True,
        "show_coords": False,
        "reduce_resolution_on_mac": False,
        "use_adaptive_sync": False,
        "cloud_height": 192,
        "cull_entities": True,
        "limit_entity_fps_to_vanilla": False,
        "disable_flashing_entities": False,
        "linear_flame_animation": True,
    },
}


# ---- dynamic fps: hard throttle when unfocused/hidden -----------------------

DYNAMIC_FPS = {
    "idle": {
        "enabled": True,
        "seconds": 30,
        "mouse_unfocused": True,
        "mouse_unfocused_fps": 1,
    },
    "reduce_fps_when_unfocused": True,
    "reduce_fps_when_hidden": True,
    "unfocused_fps": 10,
    "hidden_fps": 1,
    "restore_fps_when_hovered": False,
    "uncap_menu_frame_rate": True,
    "run_gc_on_unfocus": True,
    "volume_multiplier_unfocused": 0.0,
    "volume_multiplier_hidden": 0.0,
    "disable_toasts": False,
}


# ---- entity culling ---------------------------------------------------------

ENTITY_CULLING = {
    "skipMarkerArmorStands": True,
    "skipEntityCulling": False,
    "tracingDistance": 128,
    "cullEntity": True,
    "cullBlockEntities": True,
    "cullPlayers": False,
    "cullNametags": False,
    "renderNametagsThroughWalls": False,
    "disabledEntity": [],
    "disabledBlockEntity": [],
    "updatesPerSecond": 20,
    "debugMode": False,
    "culledEntityColor": "0xFF0000",
    "culledBlockEntityColor": "0xFF8000",
}


# ---- moreculling: all culling heuristics on ---------------------------------

MORECULLING = {
    "cullingMode": "ALL_NEIGHBORS_MATCH",
    "leavesCullingMode": "SMART",
    "advancedLeavesCulling": True,
    "chestCulling": True,
    "blockBreakingParticleCulling": True,
    "itemFrameNameTagCulling": True,
    "itemFrameMapCulling": True,
    "signTextCulling": True,
    "bookshelfCulling": True,
    "shulkerBoxCulling": True,
    "enchantingTableCulling": True,
    "useBlockFaceCullingOnNeighboringBlocks": True,
    "useGraphicsMode": True,
    "airInvisCheckType": "FAST",
}


# ---- immediately fast -------------------------------------------------------

IMMEDIATELYFAST = {
    "font_atlas_resizing": True,
    "fast_text_lookup": True,
    "fast_buffer_upload": True,
    "hud_batching": True,
    "sign_text_buffering": True,
    "map_atlas_generation": True,
    "fast_language_loading": True,
    "experimental_disable_error_checking": False,
}


# ---- exordium: cap each HUD element's re-render rate -----------------------

EXORDIUM = {
    "hotbar":           {"enabled": True, "fps": 60},
    "crosshair":        {"enabled": True, "fps": 60},
    "chat":             {"enabled": True, "fps": 30},
    "health":           {"enabled": True, "fps": 30},
    "food":             {"enabled": True, "fps": 30},
    "air":              {"enabled": True, "fps": 30},
    "armor":            {"enabled": True, "fps": 30},
    "experience":       {"enabled": True, "fps": 20},
    "tab_list":         {"enabled": True, "fps": 15},
    "scoreboard":       {"enabled": True, "fps": 30},
    "title":            {"enabled": True, "fps": 60},
    "debug":            {"enabled": True, "fps": 10},
    "statusEffects":    {"enabled": True, "fps": 10},
    "subtitles":        {"enabled": True, "fps": 15},
    "item_tooltip":     {"enabled": True, "fps": 30},
}


# ---- ModernFix: all perf/bug mixins on -------------------------------------

MODERNFIX_MIXINS = """\
# All performance + bugfix mixins on. Comment a line out to disable a specific
# patch if it ever conflicts with another mod.

bugfix.anvil_too_expensive=true
bugfix.item_stack_size=true
bugfix.packet_leak=true

perf.cache_blockstate_cache=true
perf.cache_model_parts=true
perf.dedup_blockstates=true
perf.dynamic_resources=true
perf.faster_singleplayer_load=true
perf.faster_texture_loading=true
perf.forge_config_restore=true
perf.fix_lifecycle_events=true
perf.kill_boss_bars_rendering=true
perf.mixin_backports=true
perf.nuke_progress_screen=true
perf.parallel_model_loading=true
perf.patch_chunk_serializer=true
perf.reduce_loading_screen_freezes=true
perf.skip_first_datapack_reload=true

launch.class_search_cache=true
launch.dedicated_reload_executor=true
launch.disable_log4j_stream_logging=true
launch.early_exit_bootscreen=true
"""


# ---- Bad Optimizations: all on ---------------------------------------------

BAD_OPTIMIZATIONS = """\
# BadOptimizations — keep every tweak on.
# Paths of tweak:  true = enabled  |  false = disabled
# Generated by Shadow Client tuner.

cache_frustum_check=true
cache_translations=true
faster_gc_on_close=true
faster_random=true
optimize_chunk_serializer=true
optimize_light_engine_queue=true
optimize_particles=true
optimize_texture_mipmaps=true
skip_rain_rendering_when_disabled=true
"""


# ---- FastQuit --------------------------------------------------------------

FASTQUIT = {
    "createWorldBackup": False,
    "gracefulShutdown": False,
}


# ---- ThreadTweak -----------------------------------------------------------

THREADTWEAK_TOML = """\
# Thread-pool tuning for MC's worker executors.
# `multiplier` values are multiplied by Runtime.availableProcessors().

"Worker Threads" {
    multiplier = 1.0
    priority = 5
}

"I/O Threads" {
    multiplier = 0.5
    priority = 5
}
"""


# ---- c2me (chunk loading) — trust Hacker Mode's values, append if missing --

# ---- mini-hud: start OFF to save HUD draw cost (user toggles with H) -------

MINIHUD = {
    "Generic": {
        "coordinateFormat": "x: %.2f y: %.2f z: %.2f",
        "mainRenderingToggle": False,   # ← disable by default, user hits H to enable
    },
    "InfoToggles": {k: False for k in [
        "infoBiome","infoBiomeRegistryName","infoBlockPosition","infoBlockProperties",
        "infoChunkPosition","infoChunkSections","infoChunkSectionsLine","infoChunkUpdates",
        "infoCoordinates","infoCoordinatesScaled","infoDifficulty","infoDimensionId",
        "infoEntities","infoEntitiesClientWorld","infoFacing","infoFPS","infoFurnaceXp",
        "infoHoneyLevel","infoHorseSpeed","infoHorseJump","infoLightLevel","infoLookingAtBlock",
        "infoLookingAtBlockChunk","infoLookingAtEntity","infoMemoryUsage","infoParticleCount",
        "infoPlayerPitch","infoPlayerYaw","infoRotationPitch","infoRotationYaw",
        "infoServerTPS","infoSlimeChunk","infoSpeed","infoSpeedAxis","infoSpeedHV","infoTileEntities",
        "infoTime","infoTimeDayModulo","infoTimeReal","infoTimeTotal","infoWeather","infoWorldTime",
    ]},
}


# ---- NotEnoughAnimations: leave anims on (they're cheap) but kill a few ----

NEA = {
    "noFlyingAnimation": False,
    "customSwimAnimation": True,
    "animatedBow": True,
    "animatedCrossbow": True,
    "animatedShield": True,
    "animatedSpear": True,
    "animatedEating": True,
    "animatedMap": True,
    "animateTpose": False,
    "animationSmoothing": 1.0,
}


# ---- Combat HitBox — keep toggled off (costs FPS in busy PvP) --------------

COMBAT_HITBOX = {
    "enabled": True,
    "hitboxColor": "#FF0000",
    "hitboxThickness": 1.0,
}


def _merge_options_txt(base: str, existing: Path) -> str:
    """Overlay `base` onto the current options.txt so we tune the keys we own
    without wiping the user's keybinds / in-game tweaks.

    `base` values win on conflict. Anything in `existing` that's not in `base`
    (keybinds, sound categories, version-new options MC added later) is kept.
    """
    base_lines = {}
    for ln in base.splitlines():
        if ':' in ln:
            k, _, v = ln.partition(':')
            base_lines[k] = v
    existing_lines: dict[str, str] = {}
    if existing.exists():
        for ln in existing.read_text(encoding='utf-8').splitlines():
            if ':' in ln:
                k, _, v = ln.partition(':')
                existing_lines[k] = v
    merged = dict(existing_lines)
    merged.update(base_lines)
    # Keep the MC-written `version:` if it's newer than our seed
    if 'version' in existing_lines and 'version' in base_lines:
        try:
            if int(existing_lines['version']) > int(base_lines['version']):
                merged['version'] = existing_lines['version']
        except ValueError:
            pass
    return '\n'.join(f'{k}:{v}' for k, v in merged.items()) + '\n'


def main() -> None:
    print("[tune] writing max-FPS configs…")
    CFG.mkdir(parents=True, exist_ok=True)

    options_file = GAME / "options.txt"
    merged = _merge_options_txt(OPTIONS_TXT, options_file)
    write(options_file,                                     merged,                    label="merged (keybinds preserved)")
    write(CFG  / "sodium-options.json",                     SODIUM_OPTIONS,            label="sodium perf-max")
    write(CFG  / "sodium-extra-options.json",               SODIUM_EXTRA_OPTIONS,      label="clouds off, toasts slim")
    write(CFG  / "dynamic_fps.json",                        DYNAMIC_FPS,               label="unfocused=10 hidden=1")
    write(CFG  / "entityculling.json",                      ENTITY_CULLING,            label="aggressive culling")
    write(CFG  / "moreculling.json",                        MORECULLING,               label="all culls on")
    write(CFG  / "immediatelyfast.json",                    IMMEDIATELYFAST,           label="all fast-paths on")
    write(CFG  / "exordium.json",                           EXORDIUM,                  label="HUD re-render caps")
    write(CFG  / "modernfix-mixins.properties",             MODERNFIX_MIXINS,          label="all perf mixins on")
    write(CFG  / "badoptimizations.properties",             BAD_OPTIMIZATIONS,         label="all tweaks on")
    write(CFG  / "fastquit.json",                           FASTQUIT,                  label="no backups on quit")
    write(CFG  / "threadtweak.toml",                        THREADTWEAK_TOML,          label="thread pools tuned")
    write(CFG  / "minihud.json",                            MINIHUD,                   label="HUD off by default")
    write(CFG  / "notenoughanimations.json",                NEA,                       label="anims pruned")
    write(CFG  / "combat-hitboxes.json",                    COMBAT_HITBOX,             label="enabled")

    print("[tune] done. Launch → expect 600+ FPS at spawn.")


if __name__ == "__main__":
    main()
