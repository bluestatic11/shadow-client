# Shadow Client launcher

Tauri 2 desktop app — small Rust shell + vanilla HTML/CSS/JS frontend.
Wraps the existing Python launcher (`../client.py`) so users get a graphical
installer instead of a CLI.

## Prereqs (one-time)

1. **Rust** — install via https://rustup.rs (Windows: download `rustup-init.exe`, run it, accept defaults)
2. **Node.js 20+** — https://nodejs.org (use the LTS installer)
3. **Tauri prerequisites for Windows** — open PowerShell as Admin and run:
   ```
   winget install Microsoft.EdgeWebView2Runtime
   ```
   You also need Microsoft Visual Studio Build Tools with the "Desktop development with C++" workload. Rustup will tell you if it's missing.
4. **Python 3.11+** on PATH (the launcher subprocess-calls `python client.py`)

## First-time dev run

From this `launcher/` folder:

```
npm install
npm run dev
```

That compiles the Rust backend (first build is slow — ~3-5 min cold), spawns a
local dev server for the HTML, and opens a window. Hot-reload works for the
frontend; Rust changes require Ctrl-C + re-run.

## Building a release installer

```
npm run build
```

Output goes to `src-tauri/target/release/bundle/`:

- `nsis/ShadowClient_0.1.0_x64-setup.exe` — Windows installer (~12 MB)
- `msi/ShadowClient_0.1.0_x64_en-US.msi` — alternative MSI installer
- `dmg/ShadowClient_0.1.0_x64.dmg` — macOS (only when building on a Mac)
- `appimage/shadow-client_0.1.0_amd64.AppImage` — Linux

Upload the `.exe` to **GitHub Releases** under
`github.com/Bluestatic11/shadow-client-launcher/releases/latest` so the
website's download buttons resolve.

## Architecture

```
launcher/
├── package.json           # npm metadata + tauri scripts
├── src/                   # frontend (no build step, served as-is)
│   ├── index.html         # tab shell + page sections
│   ├── styles.css         # Shadow Client brand
│   └── main.js            # tab nav, invoke() calls to Rust
└── src-tauri/             # Rust backend
    ├── Cargo.toml
    ├── tauri.conf.json    # bundle config, window size, update endpoint
    ├── build.rs           # Tauri build helper
    ├── capabilities/
    │   └── default.json   # permission manifest (Tauri 2 explicit perms)
    ├── icons/
    │   └── icon.png       # placeholder — replace with real artwork
    └── src/
        ├── main.rs        # entry (calls into lib.rs)
        └── lib.rs         # commands: launch_game, setup_client, update_mods,
                           #           microsoft_login, list_mods, read_state
```

The Rust shell discovers the project root by walking up from the launcher
binary looking for `client.py`. So during dev (`cargo run` inside `launcher/`)
the parent dir's `client.py` is found; in the installed app, the launcher
expects `client.py` and friends to ship next to the `.exe` (added by the
NSIS installer's resource copy).

## What still needs to happen

- **Bundle Python** — currently the user must have Python on PATH. To make
  the app truly turn-key, embed a portable Python distribution via
  `tauri.conf.json → bundle.resources` and have `lib.rs` invoke it instead
  of relying on system `python`.
- **Code signing** — Windows shows a SmartScreen warning on unsigned `.exe`.
  Buy a code-signing cert (~$200/yr) when the user base justifies it.
- **Auto-updater** — `tauri.conf.json` already lists an update endpoint at
  `https://shadowclient.app/updates/{{target}}/{{current_version}}`. Host a
  `latest.json` manifest there once you have the domain.
- **Account UI** — the sidebar's account chip currently just opens the
  Microsoft sign-in flow. Wire it to read the saved account file
  (`game_dir/mc-client-account.json`) and show the actual MS username.
- **Mods tab actions** — add disable/enable buttons (toggle the `.disabled`
  suffix) and drag-drop install for user-supplied jars.

## License

All rights reserved. Edison only.
