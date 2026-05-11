"""Additive installer: grab every top-tier FPS mod available for our MC
version from Modrinth and drop it into game_dir/mods/ without disturbing the
Hacker Mode stack or the Shadow Client branding jar.

Also writes aggressive options.txt + sodium-options.json tuned for 600+ FPS.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from mods import _pick_version
from mojang import _download

ROOT = Path(__file__).resolve().parent.parent
STATE = json.loads((ROOT / "installed.json").read_text())
MC = STATE["mc_version"]
MODS_DIR = ROOT / "game_dir" / "mods"

# Curated performance mods. Each will be skipped if it doesn't have a Fabric
# build for our MC, or if a jar with the same name is already present.
FPS_SLUGS: list[tuple[str, str]] = [
    # Core renderers / game-logic (keep if already present)
    ("sodium",                 "block/terrain renderer rewrite"),
    ("lithium",                "game-logic tick tuning"),
    ("ferrite-core",           "memory footprint"),
    ("krypton",                "network stack"),
    ("c2me-fabric",            "chunk-loading threads"),
    ("entityculling",          "skip occluded entities"),
    ("dynamic-fps",            "throttle when unfocused"),
    ("immediatelyfast",        "HUD/text rendering"),
    # Extras you don't have yet:
    ("sodium-extra",           "fog/clouds/weather toggles + FPS uncap"),
    ("reeses-sodium-options",  "slicker sodium UI"),
    # ("modernfix",            "mass bug + perf fixes"),   # author lists 1.21.x
    #                                                      # compat but actual
    #                                                      # mixins crash on 1.21.11
    #                                                      # (method_18096 target gone)
    ("badoptimizations",       "misc tiny wins"),
    ("moreculling",            "extra culling heuristics"),
    ("enhancedblockentities",  "block-entity render opt"),
    ("cull-less-leaves",       "transparent leaf rendering"),
    ("memoryleakfix",          "patches known leaks"),
    ("debugify",               "bug fixes incl. perf"),
    ("noisium",                "worldgen noise perf"),
    ("chunky",                 "pre-generate chunks"),
    ("fastanim",               "skip off-screen entity anims"),
    ("fastquit",               "instant world exit"),
    ("threadtweak",            "JVM thread-pool tuning"),
    ("alternate-current",      "redstone engine rewrite"),
    ("methodhandle-optimizer", "JIT warmup win"),
    ("lazydfu",                "faster startup (DFU lazy-load)"),
    ("exordium",               "HUD-only re-render"),
    ("lazy-language-loader",   "faster language/asset load"),
    # Sodium shader/PBR support (optional; users can remove)
    # "iris",                  # uncomment if you want shaders
]

# Bucket any mod file whose name begins with a token we already shipped so we
# don't double-install.
def already_have(slug: str) -> bool:
    needle = slug.replace("-fabric", "").replace("_", "-").lower().split("-")[0]
    for f in MODS_DIR.glob("*.jar"):
        if needle in f.name.lower():
            return True
    return False


def install() -> None:
    MODS_DIR.mkdir(parents=True, exist_ok=True)
    added = skipped = missing = 0
    for slug, desc in FPS_SLUGS:
        if already_have(slug):
            print(f"[fps] skip {slug:28} already installed")
            skipped += 1
            continue
        v = _pick_version(slug, MC)
        if not v:
            print(f"[fps] miss {slug:28} no {MC} fabric build on modrinth")
            missing += 1
            continue
        f = v["files"][0]
        dest = MODS_DIR / f["filename"]
        print(f"[fps]  add {slug:28} -> {f['filename']}  ({desc})")
        _download(f["url"], dest, f.get("hashes", {}).get("sha1"))
        added += 1
    print(f"\n[fps] done. added={added}  skipped={skipped}  missing={missing}")


OPTIONS_TXT_MAX_FPS = """\
version:3955
renderDistance:5
simulationDistance:5
maxFps:0
enableVsync:false
graphicsMode:0
ao:0
biomeBlendRadius:0
entityShadows:false
entityDistanceScaling:0.5
particles:2
fancyGraphics:false
cloudHeight:0
renderClouds:"false"
fullscreen:false
smoothLighting:0
mipmapLevels:0
useNativeTransport:true
guiScale:2
chatVisibility:0
resourcePacks:[]
gamma:1.0
attackIndicator:1
chatHeightFocused:1.0
chatHeightUnfocused:0.44366195797920227
chatOpacity:1.0
chatScale:0.5
chatWidth:0.5
darkMojangStudiosBackground:true
enableVibrator:false
narrator:0
soundCategory_master:0.3
"""


SODIUM_OPTIONS = {
    "quality": {
        "cloud_quality": "OFF",
        "weather_quality": "FAST",
        "leaves_quality": "FAST",
        "enable_vignette": False,
    },
    "advanced": {
        "use_persistent_mapping": True,
        "cpu_render_ahead_limit": 3,
        "allow_direct_memory_access": True,
        "use_nvidia_workarounds": True,
        "arena_memory_allocator": "ASYNC",
    },
    "performance": {
        "chunk_builder_threads": 0,
        "always_defer_chunk_updates": True,
        "animate_only_visible_textures": True,
        "use_entity_culling": True,
        "use_particle_culling": True,
        "use_fog_occlusion": True,
        "use_block_face_culling": True,
        "use_no_error_gl_context": True,
    },
    "notifications": {"hide_donation_button": True},
}


SODIUM_EXTRA_OPTIONS = {
    "animation_settings": {
        "animations": True,
        "water": True, "lava": True, "fire": True, "portal": True,
        "block_animations": True, "sculk_sensor": True, "items": True,
    },
    "particle_settings": {
        "particles": True,
        "rain_splash": False, "block_breaking": True, "ambient_entity_effect": False,
    },
    "detail_settings": {
        "sky": True, "sun_moon": True, "stars": True, "weather": True,
        "biome_colors": True, "sky_colors": True,
    },
    "render_settings": {
        "light_updates": True, "item_frame_name_tags": False,
        "player_name_tags": True, "toasts": True,
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
    },
}


def tune_options() -> None:
    opts = ROOT / "game_dir" / "options.txt"
    opts.write_text(OPTIONS_TXT_MAX_FPS, encoding="utf-8")
    print(f"[fps] wrote {opts.relative_to(ROOT)} (render=5, maxFps=0, all fancy OFF)")

    cfg = ROOT / "game_dir" / "config"
    cfg.mkdir(parents=True, exist_ok=True)
    (cfg.parent / "sodium-options.json").write_text(json.dumps(SODIUM_OPTIONS, indent=2), encoding="utf-8")
    print("[fps] wrote game_dir/sodium-options.json (FPS uncapped, culling max)")
    (cfg / "sodium-extra-options.json").write_text(json.dumps(SODIUM_EXTRA_OPTIONS, indent=2), encoding="utf-8")
    print("[fps] wrote config/sodium-extra-options.json (animations/particles trimmed)")


if __name__ == "__main__":
    install()
    tune_options()
