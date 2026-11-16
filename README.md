# Shadow Client

A free Minecraft launcher and client for 1.21 and above, built around the
Fabric performance-mod stack.

**Download:** installers for Windows, macOS and Linux are on the
[releases page](https://github.com/bluestatic11/shadow-client/releases/latest).
Website: https://bluestatic11.github.io/shadow-client/

The installer sets up Fabric and the performance mods and manages profiles,
mods and resource packs. The Shadow HUD mod in this repo is separate; see
[The HUD mod](#the-hud-mod-shadow-hud) for where it works.

## What's in this repo

```
shadow-client/
├── client.py            ── Python launcher CLI: setup, login, launch, update-mods
├── auth.py              ── Microsoft OAuth (with Prism Launcher import fallback)
├── mojang.py            ── Vanilla MC version manifest + asset/library downloader
├── fabric.py            ── Fabric Loader installer
├── mods.py              ── Modrinth-driven performance-mod installer
├── jvm.py               ── Tuned JVM flags (G1GC / ZGC profiles)
├── jdk.py               ── JDK auto-detect / bundled-JDK helper
│
├── branding/hud_mod/    ── Shadow HUD Fabric mod (~14k LOC, 180+ modules)
│   ├── src/             ── Java source (reflection-based, no compile-time MC deps)
│   ├── stubs/           ── Tiny stub classes for the few mixin signatures
│   ├── resources/       ── Mixin manifest + cape/cosm PNGs
│   └── build.py         ── Compile + package script (no Gradle wrapper needed)
│
├── launcher/            ── Tauri 2 desktop app (Rust shell + HTML/CSS/JS UI)
│   ├── src/             ── Frontend
│   ├── src-tauri/       ── Backend (calls into client.py via subprocess)
│   └── README.md        ── Build instructions
│
└── website/             ── Landing page (deployed to GitHub Pages)
    └── README.md        ── Deploy instructions
```

## Quick start (CLI launcher)

```bash
# Windows / macOS / Linux — Python 3.11+ required
python client.py setup --username YourName
python client.py launch
```

First `setup` downloads the latest Minecraft release (pass `--version 1.21.11`
to pick one), installs Fabric, and fetches every performance mod in `mods.py`
that has a build for that version (Sodium, Lithium, FerriteCore,
ImmediatelyFast, Iris and others). Mods without a build for your version are
skipped. It also tries to compile the Shadow HUD mod.

For online Microsoft-account play: `python client.py login`.

## Quick start (GUI launcher)

See `launcher/README.md`. Prereqs: Node 20+, Rust (rustup), Tauri prereqs.

```bash
cd launcher
npm install
npm run dev          # dev window with hot reload
npm run build        # production .exe / .dmg / .AppImage
```

## Performance mods

Shadow Client has no renderer of its own. It installs the community
performance mods (Sodium, Lithium, FerriteCore, ImmediatelyFast and others)
and launches with tuned JVM flags. Which mods you get depends on the
Minecraft version, because each mod is only installed if it has a build for it.

## The HUD mod (Shadow HUD)

180+ toggleable in-game modules, all with their own config panel.

**Where it works:** the CLI `setup` builds it, and it runs on Minecraft
1.21.11. It does not load on 26.x yet, because Fabric replaced the HUD API it
hooks into. The desktop installer does not include it.

- **Display** — FPS, HP, XYZ, Time, Compass, Day, Direction (with full settings)
- **Combat** — CPS, Combo, Reach, Killstreak, Hit markers, etc.
- **Inventory** — Item counts (totems, pearls, arrows, gold), durability avg, shulker tooltips
- **Cosmetics** — Cape, dragon wings (solid 3D mesh), halos, fairies, trails
- **World** — Coordinates, biome, light level, weather, fire/freeze timers, void warnings
- **Utility** — Auto-reconnect, auto-respawn, auto-GG, anti-AFK, drag-to-position HUD editor
- **Theming** — 8 swatches + custom HSV picker for every accent color
- **Visual tweaks** — Custom sword length, glint color/strength, block-face highlight

All draggable, scalable, themeable. Each module has its own gear icon → config panel.

## License

All rights reserved. Edison only. Source available for verification and
non-commercial inspection. Not affiliated with Mojang, Microsoft, or any other
Minecraft client.

Minecraft is a trademark of Mojang Studios.
