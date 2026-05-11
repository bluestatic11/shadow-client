"""Install FPS-boosting resource packs from Modrinth and enable them.

Packs picked to stack without overlap:
  - zerofog                 → kills fog rendering (huge open-world win)
  - particle-lite           → shrinks particle textures to ~1 px
  - no-shade-+-fps-boost    → disables block-face shading calculations
  - bluefps                 → installed but not enabled; toggle in options
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from mods import _pick_version
from mojang import _download

ROOT = Path(__file__).resolve().parent.parent
GAME = ROOT / "game_dir"
PACKS_DIR = GAME / "resourcepacks"
OPTIONS_TXT = GAME / "options.txt"
STATE = json.loads((ROOT / "installed.json").read_text())
MC = STATE["mc_version"]

# Slug, filename-prefix, enable-by-default
# All left off so the user picks which to apply via the in-game resource pack
# screen. Zips still land in game_dir/resourcepacks/.
PACKS: list[tuple[str, str, bool]] = [
    ("zerofog",              "zerofog",              False),
    ("particle-lite",        "particle-lite",        False),
    ("no-shade-+-fps-boost", "no-shade",             False),
    ("bluefps",              "bluefps",              False),
]


def install() -> list[str]:
    PACKS_DIR.mkdir(parents=True, exist_ok=True)
    enabled: list[str] = []
    for slug, short, default_on in PACKS:
        # Resource packs use loader=minecraft in Modrinth's metadata
        v = _pick_version(slug, MC, loader="minecraft")
        if not v:
            # Resource packs often don't list every MC version — try any release
            try:
                import urllib.request
                url = f"https://api.modrinth.com/v2/project/{slug}/version"
                raw = urllib.request.urlopen(url, timeout=30).read()
                vs = json.loads(raw)
                v = next((x for x in vs if x.get("version_type") == "release"), None)
            except Exception:
                v = None
        if not v:
            print(f"[rp] miss {slug}")
            continue
        f = v["files"][0]
        dest = PACKS_DIR / f["filename"]
        print(f"[rp] {'ON ' if default_on else 'off'}  {slug:28}  {f['filename']}")
        _download(f["url"], dest, f.get("hashes", {}).get("sha1"))
        if default_on:
            enabled.append(f["filename"])
    return enabled


def update_options(enabled_filenames: list[str]) -> None:
    if not OPTIONS_TXT.exists():
        print("[rp] no options.txt — skipping enable")
        return
    # Minecraft reads packs bottom-up (later = higher priority). Put vanilla
    # first, then each pack, last-written wins on overlapping textures.
    pack_entries = ['"vanilla"'] + [f'"file/{n}"' for n in enabled_filenames]
    new_line = f'resourcePacks:[{",".join(pack_entries)}]'

    lines = OPTIONS_TXT.read_text(encoding="utf-8").splitlines()
    replaced = False
    for i, ln in enumerate(lines):
        if ln.startswith("resourcePacks:"):
            lines[i] = new_line
            replaced = True
            break
    if not replaced:
        lines.append(new_line)
    OPTIONS_TXT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"[rp] enabled in options.txt: {enabled_filenames}")


if __name__ == "__main__":
    enabled = install()
    update_options(enabled)
