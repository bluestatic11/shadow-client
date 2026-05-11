"""Generate + package the Shadow Client branding mod.

Output: <project>/game_dir/mods/shadowclient-<version>.jar

The mod ships as a pure-resources Fabric mod (no Java code). Fabric Loader
auto-loads resource overrides from every enabled mod, so dropping this jar in
`mods/` replaces the logos, splash text, and menu strings across every MC
instance we build.
"""
from __future__ import annotations

import json
import random
import zipfile
from io import BytesIO
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
OUT_DIR = ROOT / "game_dir" / "mods"
CONFIG_DIR = ROOT / "game_dir" / "config"
MC_VERSIONS_DIR = ROOT / "game_dir" / "versions"
NAME = "Shadow Client"
MOD_ID = "shadowclient"
VERSION = "1.0.0"

COMBAT_RED = (140, 10, 18)   # deep blood red — visceral + still reads at particle size
GLINT_RED  = (195, 22, 32)   # mid-bright blood red — used for enchanted glint

# ---- colors ------------------------------------------------------------------
# Blood red + black theme. PURPLE / DEEP / GHOST kept as names for backward
# compat with existing code; values are now the red palette.
PURPLE = (175, 18, 26, 255)       # accent — deep blood red
DEEP   = (40, 6, 8, 255)          # shadow tint — near-black with red bias
BLACK  = (0, 0, 0, 255)
WHITE  = (240, 240, 240, 255)
GHOST  = (200, 40, 40, 220)       # subtle red glow for watermarks


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(f"C:/Windows/Fonts/{name}", size)


# ---- image generators --------------------------------------------------------


def _text_logo(text: str, w: int, h: int, *, font_file: str, glow: bool = True,
               main: tuple = PURPLE, shadow: tuple = DEEP) -> Image.Image:
    """Render `text` centered, large, with a soft purple glow + drop shadow."""
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    # fit the font size so the text fills ~80% of width
    size = h
    while size > 8:
        f = font(font_file, size)
        bbox = f.getbbox(text)
        tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        if tw <= w * 0.88 and th <= h * 0.80:
            break
        size -= 2
    f = font(font_file, size)
    bbox = f.getbbox(text)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (w - tw) // 2 - bbox[0]
    y = (h - th) // 2 - bbox[1]

    # drop shadow
    sh = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(sh).text((x + 3, y + 4), text, font=f, fill=BLACK)
    sh = sh.filter(ImageFilter.GaussianBlur(radius=2))
    img.alpha_composite(sh)

    if glow:
        g = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(g).text((x, y), text, font=f, fill=(*main[:3], 180))
        g = g.filter(ImageFilter.GaussianBlur(radius=6))
        img.alpha_composite(g)

    # main text
    ImageDraw.Draw(img).text((x, y), text, font=f, fill=main)
    # thin deep outline for contrast
    ImageDraw.Draw(img).text((x, y), text, font=f, fill=shadow, stroke_width=1, stroke_fill=shadow)
    ImageDraw.Draw(img).text((x, y), text, font=f, fill=main)
    return img


def _mod_icon(size: int = 256) -> Image.Image:
    """Square SC icon — blood-red glow over a near-black rounded-square."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # near-black background with a subtle red undertone
    pad = size // 20
    d.rounded_rectangle([pad, pad, size - pad, size - pad],
                        radius=size // 8,
                        fill=(18, 4, 6, 255),
                        outline=(*PURPLE[:3], 255),
                        width=max(2, size // 64))
    # glow halo
    halo = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(halo).ellipse([size * 0.15, size * 0.15, size * 0.85, size * 0.85],
                                 fill=(*PURPLE[:3], 80))
    halo = halo.filter(ImageFilter.GaussianBlur(radius=size // 16))
    img.alpha_composite(halo)
    # big "SC" centered
    logo = _text_logo("SC", int(size * 0.75), int(size * 0.75),
                      font_file="impact.ttf", main=WHITE, shadow=DEEP)
    img.alpha_composite(logo, ((size - logo.width) // 2, (size - logo.height) // 2))
    return img


def _tint_particle(src_png: bytes, color: tuple[int, int, int]) -> bytes:
    """Re-color a vanilla particle sprite.

    MC's particle PNGs are LA (luminance + alpha). The luminance gives the
    brightness, alpha gives the shape. We replace RGB with `color`, scaled per
    pixel by the original luminance, and keep alpha from the source. Result is
    a particle that renders as `color` but preserves every shape + falloff of
    the original animation frame.
    """
    img = Image.open(BytesIO(src_png))
    if img.mode == "LA":
        lum, alpha = img.split()
    elif img.mode == "RGBA":
        # some vanilla sprites are full RGBA — compute a luminance ourselves
        r, g, b, alpha = img.split()
        lum = Image.eval(r.convert("L"), lambda v: v)  # channel merge approximation
        lum = Image.merge("RGB", (r, g, b)).convert("L")
    else:
        alpha = img.convert("RGBA").split()[-1]
        lum = img.convert("L")

    w, h = img.size
    px_l = lum.load()
    px_a = alpha.load()
    out = Image.new("RGBA", (w, h))
    px_o = out.load()
    for y in range(h):
        for x in range(w):
            a = px_a[x, y]
            if a == 0:
                continue
            brightness = px_l[x, y] / 255.0
            # Scale alpha by luminance so faint parts of the original stay faint
            final_alpha = int(a * brightness)
            px_o[x, y] = (color[0], color[1], color[2], final_alpha)

    buf = BytesIO()
    out.save(buf, "PNG")
    return buf.getvalue()


def _recolor_to_red(src_png: bytes, color: tuple[int, int, int],
                    *, boost: float = 1.0, alpha_boost: float = 1.0) -> bytes:
    """Remap any colored MC texture to a red version that preserves luminance.

    Works on RGB / RGBA / palette images. Each pixel's brightness (max of RGB)
    determines how bright the output red is; the alpha channel is preserved.

    Args:
      color:        target (R, G, B)
      boost:        multiplier on luminance (>1.0 brightens faint pixels,
                    lets dim streaks in the glint animation actually show up)
      alpha_boost:  multiplier on alpha (>1.0 makes the shimmer more opaque)
    """
    img = Image.open(BytesIO(src_png))
    if img.mode in ("P", "RGB", "L"):
        img = img.convert("RGBA")
    w, h = img.size
    px = img.load()
    out = Image.new("RGBA", (w, h))
    po = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            bright = min(1.0, (max(r, g, b) / 255.0) * boost)
            new_a  = min(255, int(a * alpha_boost))
            po[x, y] = (
                int(color[0] * bright),
                int(color[1] * bright),
                int(color[2] * bright),
                new_a,
            )
    buf = BytesIO()
    out.save(buf, "PNG")
    return buf.getvalue()


def _red_enchanted_glints() -> dict[str, bytes]:
    """Remap the three enchantment-glint textures to a bright, highly-visible
    blood red. Boost flags pump luminance + alpha so faint parts of the
    scrolling shimmer are actually readable on an enchanted item.
    """
    out: dict[str, bytes] = {}
    for path in (
        "assets/minecraft/textures/misc/enchanted_glint_item.png",
        "assets/minecraft/textures/misc/enchanted_glint_armor.png",
        "assets/minecraft/textures/particle/glint.png",
    ):
        src = _vanilla_particle_bytes(path)
        if src:
            out[path] = _recolor_to_red(src, GLINT_RED, boost=1.15, alpha_boost=1.0)
    return out


def _vanilla_particle_bytes(path: str) -> bytes | None:
    """Return the raw PNG bytes of a vanilla MC particle at `path`.

    Walks the installed MC client jar(s) under `game_dir/versions/`. Returns
    None if the asset isn't found — caller can skip gracefully.
    """
    for version_dir in MC_VERSIONS_DIR.glob("*"):
        if not version_dir.is_dir():
            continue
        for jar in version_dir.glob("*.jar"):
            try:
                with zipfile.ZipFile(jar) as z:
                    if path in z.namelist():
                        return z.read(path)
            except zipfile.BadZipFile:
                continue
    return None


def _red_combat_particles() -> dict[str, bytes]:
    """Generate red recolors of the crit + sweep combat particles."""
    out: dict[str, bytes] = {}
    # Crit hit — single frame
    crit = _vanilla_particle_bytes("assets/minecraft/textures/particle/critical_hit.png")
    if crit:
        out["assets/minecraft/textures/particle/critical_hit.png"] = _tint_particle(crit, COMBAT_RED)

    # Enchanted hit — same behavior, also red for consistency
    ench = _vanilla_particle_bytes("assets/minecraft/textures/particle/enchanted_hit.png")
    if ench:
        out["assets/minecraft/textures/particle/enchanted_hit.png"] = _tint_particle(ench, COMBAT_RED)

    # Sweep attack — 8 animation frames
    for i in range(8):
        p = f"assets/minecraft/textures/particle/sweep_{i}.png"
        src = _vanilla_particle_bytes(p)
        if src:
            out[p] = _tint_particle(src, COMBAT_RED)
    return out


def _panorama_face(idx: int) -> Image.Image:
    """A dark panorama with a deep-red blood haze center-band."""
    w, h = 1024, 1024
    img = Image.new("RGBA", (w, h), BLACK)
    d = ImageDraw.Draw(img)
    # vertical gradient: pure black top/bottom, blood-red haze in the middle
    for y in range(h):
        t = y / h
        k = 1 - abs(0.5 - t) * 2   # peaks at center
        r = int(8 + 70 * k)        # red dominates
        g = int(2 + 6  * k)        # almost no green
        b = int(4 + 8  * k)        # almost no blue
        d.line([(0, y), (w, y)], fill=(r, g, b, 255))
    # sparse embers instead of stars — faint red dots
    rng = random.Random(0xC0DE + idx)
    for _ in range(220):
        x = rng.randint(0, w - 1)
        y = rng.randint(0, h - 1)
        a = rng.randint(40, 180)
        d.point((x, y), fill=(220, 80, 80, a))
    # faint ghost "SC" on one face only
    if idx == 0:
        logo = _text_logo("SC", 800, 800, font_file="impact.ttf",
                          main=(*GHOST[:3], 30), shadow=(30, 4, 6, 30))
        img.alpha_composite(logo, ((w - 800) // 2, (h - 800) // 2))
    return img


# ---- assembler ---------------------------------------------------------------


def _fabric_mod_json() -> bytes:
    data = {
        "schemaVersion": 1,
        "id": MOD_ID,
        "version": VERSION,
        "name": NAME,
        "description": (
            "Shadow Client — tuned Fabric distribution with custom branding, "
            "optimized JVM tuning, and a curated performance mod stack. "
            "Press 'Mods' on the title screen to browse every loaded mod."
        ),
        "authors": ["Shadow Client"],
        "contact": {"homepage": "https://example.invalid/shadow-client"},
        "license": "All Rights Reserved",
        "icon": "assets/shadowclient/icon.png",
        "environment": "client",
        "depends": {"fabricloader": ">=0.14.0", "minecraft": ">=1.20"},
        # Rich metadata ModMenu picks up automatically
        "custom": {
            "modmenu": {
                "badges": ["library"],
                "update_checker": False,
                "links": {
                    "modmenu.discord": "https://example.invalid/discord",
                    "modmenu.issues": "https://example.invalid/issues",
                },
            },
        },
    }
    return json.dumps(data, indent=2).encode("utf-8")


def _pack_mcmeta() -> bytes:
    # Modern MC requires min_format + max_format when declaring support for
    # formats higher than 64. `supported_formats` alone is rejected as
    # "missing mandatory fields".
    data = {"pack": {
        "pack_format": 48,
        "min_format": [1, 0],
        "max_format": [99, 0],
        "supported_formats": [1, 99],
        "description": f"{NAME} branding",
    }}
    return json.dumps(data, indent=2).encode("utf-8")


SPLASHES = [
    "Shadow Client!",
    "Max FPS unlocked!",
    "Built from scratch!",
    "Cached lib Gb go brrr",
    "Running JDK 25!",
    "Sodium + Lithium = <3",
    "Fabric all day.",
    "Powered by vibes.",
    "You are a shadow.",
    "Shhh… rendering.",
    "Entity culling: active.",
    "60+ FPS or bust.",
    "Your heap is preallocated.",
    "G1GC pauses are chef's kiss.",
    "Look at all this RAM.",
    "No Mojang studios splash here.",
    "Also try PvP Legacy!",
    "Best viewed at 240hz.",
    "Frame times: buttery.",
    "Mixin me up.",
    "Knot just any client.",
    "Watermark by vibes.",
]


LANG_EN_US: dict[str, str] = {
    # Title screen bottom-left copyright line area
    "title.credits": f"{NAME} v{VERSION}",
    "menu.singleplayer": "Singleplayer",
    "menu.multiplayer": "Multiplayer",
    # Narrator title, harmless override
    "narrator.screen.title": f"{NAME} — Title Screen",
    # Status screens
    "menu.reloading": "Reloading Shadow Client resources…",
    "menu.loadingLevel": "Entering the shadows…",
    "menu.generatingLevel": "Weaving darkness…",
    "menu.savingLevel": "Saving your shadow…",
    "connect.connecting": "Sliding through the shadows…",
    "multiplayer.downloadingTerrain": "Conjuring terrain…",
}


def build() -> Path:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    jar = OUT_DIR / f"{MOD_ID}-{VERSION}.jar"

    entries: dict[str, bytes] = {
        "fabric.mod.json": _fabric_mod_json(),
        "pack.mcmeta": _pack_mcmeta(),
        "assets/minecraft/texts/splashes.txt": ("\n".join(SPLASHES) + "\n").encode("utf-8"),
        "assets/minecraft/lang/en_us.json": json.dumps(LANG_EN_US, indent=2).encode("utf-8"),
    }

    # Title screen "MINECRAFT" replacement → "SHADOW"
    buf = BytesIO()
    _text_logo("SHADOW", 512, 128, font_file="impact.ttf").save(buf, "PNG")
    entries["assets/minecraft/textures/gui/title/minecraft.png"] = buf.getvalue()

    # "JAVA EDITION" subtitle → "CLIENT"
    buf = BytesIO()
    _text_logo("CLIENT", 256, 64, font_file="impact.ttf").save(buf, "PNG")
    entries["assets/minecraft/textures/gui/title/edition.png"] = buf.getvalue()

    # Early splash (Mojang Studios → Shadow Client)
    buf = BytesIO()
    _text_logo("SHADOW CLIENT", 512, 128, font_file="impact.ttf").save(buf, "PNG")
    entries["assets/minecraft/textures/gui/title/mojangstudios.png"] = buf.getvalue()

    # Mod icon — ModMenu picks this up via fabric.mod.json `icon` field
    buf = BytesIO()
    _mod_icon(256).save(buf, "PNG")
    entries["assets/shadowclient/icon.png"] = buf.getvalue()

    # Red critical-hit + sweep-attack particles (10 sprites total)
    red_combat = _red_combat_particles()
    entries.update(red_combat)
    if red_combat:
        print(f"[brand] red combat particles: {len(red_combat)} sprites")

    # Red enchanted-glint textures (item glint, armor glint, enchanting particle)
    red_glint = _red_enchanted_glints()
    entries.update(red_glint)
    if red_glint:
        print(f"[brand] red enchanted glints: {len(red_glint)} sprites")

    # Title-screen panorama (6 cube faces). Faces 0-3 are sides, 4 top, 5 bottom.
    for i in range(6):
        buf = BytesIO()
        _panorama_face(i).save(buf, "PNG")
        entries[f"assets/minecraft/textures/gui/title/background/panorama_{i}.png"] = buf.getvalue()

    # Write the jar. If the live jar is locked (Minecraft running), stage at
    # `<jar>.new` and the next build/launch can swap it in.
    target = jar
    if jar.exists():
        try:
            jar.unlink()
        except PermissionError:
            target = jar.with_suffix(jar.suffix + ".new")
            if target.exists():
                try: target.unlink()
                except PermissionError: pass
            print(f"[brand] {jar.name} locked (Minecraft running?) — staging at {target.name}")
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for path, data in entries.items():
            z.writestr(path, data)

    size_kb = target.stat().st_size // 1024
    print(f"[brand] built {target.name}  ({size_kb} KB, {len(entries)} entries)")

    # Seed ModMenu's user config (only if absent — don't trample user tweaks)
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    seed_cfg = HERE / "modmenu_config.json"
    dest_cfg = CONFIG_DIR / "modmenu.json"
    if seed_cfg.exists() and not dest_cfg.exists():
        dest_cfg.write_bytes(seed_cfg.read_bytes())
        print(f"[brand] seeded {dest_cfg.relative_to(ROOT)}")
    return jar


if __name__ == "__main__":
    build()
