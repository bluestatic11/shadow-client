# Shadow Client — Session Handoff (moving to new PC)

_Saved from a Claude Code session, June 2026. Everything below is what was done, what's
pending, and how to get set up on the new machine._

---

## 0. FIRST THING on the new PC — migration checklist

Copy the **whole** `C:/Users/ediso/Downloads/mc-client/` folder to the new PC (same path is
easiest). It contains the git repo, `game_dir/` (your MC install, mods, worlds, account),
and the portable toolchains referenced below. Also copy these that live OUTSIDE mc-client:

- [ ] `C:/Users/ediso/Downloads/mc-client/` — the repo + game_dir + jdk-25 (bundled)
- [ ] `C:/Users/ediso/Downloads/jdk21-portable/` — build JDK for the gametweaks/shadow-chat mods
- [ ] `C:/Users/ediso/Downloads/gradle-portable-9/` — portable Gradle 9.4.1
- [ ] `C:/Users/ediso/Downloads/node-portable/` — portable Node (relay typecheck + JS `--check`)
- [ ] `C:/Users/ediso/.lunarclient/profiles/1.21/mods/fabric-1.21.11/` — Lunar mod mirror (backup of your mod set + tuned options.txt)
- [ ] `C:/Users/ediso/.claude/` — Claude Code memory files (so the assistant keeps its notes). Key ones: `projects/C--Users-ediso-Downloads/memory/*.md`
- [ ] The **Shadow Client launcher app** — reinstall it fresh on the new PC (see §6). Don't copy the AppData install; just re-run the installer.
- [ ] Your **Microsoft/Minecraft account** re-login (the token in game_dir is short-lived).

**GPU preference** (fixes MC running on the weak iGPU): on the new PC, set the MC Java to the
NVIDIA GPU. Windows Settings → Display → Graphics → add `...\mc-client\jdk-25\<jdk>\bin\java.exe`
and `javaw.exe` → **High performance**. (On this PC it was a registry entry that reset when the
JDK auto-updated — re-check it after any JDK bump.)

---

## 1. What changed this session (with commit hashes)

All commits are **local only — NOT pushed** (author `Bluestatic11`). To ship the launcher
features you must push + cut a CI release (see §4).

### ShadowHud mod (`branding/hud_mod/src/shadowhud/ShadowHud.java`) — DEPLOYED, live on MC restart
- `aa1cb10` — **Fixed Wings crash.** Wings drew into the 1.21.11 `lines` render layer which
  needs a `LineWidth` vertex element it never wrote → `IllegalStateException` crash on enable.
  Retired the wireframe path; Wings/AngelWings/Cape now render via the solid-mesh path.
- `ba60414` / `fdf0f2c` — **CombatMusic module.** Plays hype music during PvP (you hit a player,
  or take damage with a player within 14 blocks; ~8s combat tag). Default track `music_disc.pigstep`.
  **Custom track:** drop a PCM `.wav` at `game_dir/profiles/1.21.11/config/shadowclient-combatmusic.wav`
  and it plays via javax.sound instead (independent of MC volume).
- `c83a649` — **Fixed Killstreak/HitsDealt/DPS** being dead unless Combo was also on (hit
  detection was trapped inside the Combo module's block; now ungated).
- `672df52` — **Fixed HitsTaken/CombatTime** being dead unless Damage was also on (same class of bug).
- `013daf6` — **Trimmed 17 redundant/novelty modules** (Facing, Compass, DirectionWord, Coords4TP,
  DateTime, Uptime, PingHistory, TabCount, GameTick, ToggleSprint, Waypoint, WaypointDist, WaterMark,
  TitleAnim, WelcomeMsg, FakePing, RotationLog). 199→182 modules. **Fixed Reach** (was measuring
  distance to *any* crosshair hit incl. blocks/empty air → jumped constantly; now entity-only, exact hitbox distance).
- `7a82af4` — **Removed particle cosmetics** (Trail/Halo/Fairies/Footsteps) that cluttered the view;
  reworked the arrow trail into a **sparse crit-particle** trail (throttled, 1 particle/arrow).
- `16a2611` — **Added CritReady, GappleCount, Waystones** modules (someone/prior added these; in the build).
- Note: a **GearView** "held items above heads" module was built then removed at your request
  (`git reset`, gone).

### shadow-chat mod (`shadow-chat/…/qol/CooldownHud.java`) — DEPLOYED as `shadow-chat-0.1.48.jar`
- `acf1caa` / `8bb8871` — **Cooldown HUD** (`/cooldowns on`): lists your items on cooldown
  (legendaries, golden heads, ability items) with exact time remaining, read from `ItemCooldowns`
  via reflection (exact tick math, not estimation).
- **Shulker tooltips** — was in progress at end of session (show shulker box contents on hover),
  wired into the QoL pack next to RecipePreview. **Check whether it got finished/built** — if the
  `qol/ShulkerTooltip.java` (or similar) file exists and is registered in `Qol.java`, build + deploy;
  if not, it still needs writing (use the `DataComponents.CONTAINER` / `ItemContainerContents`
  component — verified present in 1.21.11).

### Launcher (Tauri app) — COMMITTED, needs a release build to reach the installed app
- `c3d67b1` — **launcher 0.3.107: Modrinth browse-all.** Upgraded the Mods → Modrinth search into a
  full browser (empty query browses popular; sort + category dropdowns; Load more pagination).
- `05a89e5` — **launcher 0.3.108: live Network monitor** on the home screen. Pings Hoplite + saved
  servers over TCP (game hosts block ICMP), color-coded, 20s refresh, with a route verdict that flags
  "your route is degraded — restart your router" when Hoplite is >2.2× its ~85ms baseline.
  Backend: `server_ping::ping` now returns `latency_ms`.

---

## 2. Deployment state

| Thing | State |
|---|---|
| ShadowHud (all fixes + CombatMusic) | ✅ deployed to `game_dir/profiles/1.21.11/mods/shadowhud-1.0.0.jar` + Lunar mirror |
| shadow-chat 0.1.48 (Cooldown HUD) | ✅ deployed to both mod dirs |
| gametweaks mod | ❌ deleted (removed earlier at your request) |
| freecam mod (`freecam_by_kapiteon`) | ⚠️ **DISABLED** (renamed `.jar.disabled`) — it crashed on every server disconnect. Don't re-enable the same version. |
| Launcher 0.3.107 / 0.3.108 features | 📦 committed locally, **not built/released** — needs CI (§4) |
| Chat relay offline-verify fix | 📦 committed, **not deployed** — blocked on Cloudflare login (§3) |

---

## 3. Open / pending items

1. **Cape cosmetic still doesn't render** — couldn't diagnose remotely. To fix: in MC, enable
   `Cape` (and `WingsSolid`) and press **F5** (renders behind you), then check `logs/latest.log`
   for `[ShadowHud][TexCape]` / `[CapeSolid]` / `step N fail` lines — those say exactly which path failed.
2. **Chat relay deploy** (makes the in-game `;` Shadow Chat actually connect). The fix is committed
   in `chat-relay/` but the relay 401s all tokens until deployed. Run `chat-relay/DEPLOY-RELAY.bat`
   (wrangler login → deploy) when you have the Cloudflare account. Until then, Shadow Chat won't connect.
3. **Cut launcher release 0.3.108** so the Modrinth browse + Network monitor reach your app. Requires
   push + tag → CI builds → install (manually, the auto-updater is buggy — see §6).
4. **Bug-review workflow** — was about to run an adversarial review of this session's reflection-heavy
   code (CombatMusic, cooldown reflection, PvP rewiring, the Rust I couldn't compile). Not yet run.
5. **Shulker tooltips** — verify finished/built (see §1).

---

## 4. Build + deploy commands (portable toolchain; system Java is 1.8, must override)

### ShadowHud (`branding/hud_mod/`) — build.py + JDK 25
```bash
"C:/Users/ediso/AppData/Local/Programs/Python/Python313/python.exe" branding/hud_mod/build.py
# outputs game_dir/mods/shadowhud-1.0.0.jar; then copy to:
#   game_dir/profiles/1.21.11/mods/shadowhud-1.0.0.jar
#   C:/Users/ediso/.lunarclient/profiles/1.21/mods/fabric-1.21.11/shadowhud-1.0.0.jar
# If MC is running the jar is locked — stage as shadowhud-1.0.0.jar.new and rename on MC close.
```

### shadow-chat (`shadow-chat/`) — portable JDK21 + Gradle 9.4.1
```bash
export JAVA_HOME="C:/Users/ediso/Downloads/jdk21-portable/jdk-21.0.10+7"
cd "C:/Users/ediso/Downloads/mc-client/shadow-chat"
"C:/Users/ediso/Downloads/gradle-portable-9/gradle-9.4.1/bin/gradle.bat" build --console=plain --no-daemon
# output build/libs/shadow-chat-<ver>.jar → deploy to both mod dirs, DELETE the old versioned jar first
```

### Launcher — CANNOT build locally (no MSVC link.exe). CI builds on a pushed `v*` tag.
JS can be syntax-checked: `node-portable/.../node.exe --check launcher/src/main.js`.
Relay TS typecheck: `node.exe node_modules/typescript/bin/tsc --noEmit` from `chat-relay/`.

**Deploy dirs (mods go to BOTH):**
- `C:/Users/ediso/Downloads/mc-client/game_dir/profiles/1.21.11/mods/`
- `C:/Users/ediso/.lunarclient/profiles/1.21/mods/fabric-1.21.11/`

---

## 5. Hardware / network verdict (from the ping investigation)

Your PC is **good** — nothing to upgrade for Minecraft:
- **CPU:** i7-12700H (14c/20t, P-cores ~4.7GHz) — excellent for MC (single-thread bound).
- **GPU:** RTX 3050 Ti — overkill for MC; confirmed MC runs on it (nvidia-smi), ~29% load.
- **RAM:** 16GB (6GB to MC). A little tight with lots of background apps open.
- **JVM:** already using Aikar's flags + 6GB heap (optimal).

**The ping problems were NOT your PC:**
- **Bandwidth hogs** (Medal auto-clipping, OneDrive, Epic) were saturating WiFi → jitter. Killed them.
  Medal is the repeat offender — close it while gaming.
- **Constant 200ms on Hoplite** (normally 80ms): Hoplite's server is in **Virginia (OVH)**, you're in
  **Washington** — should be ~80ms. The extra ~120ms is a **bad ISP route** (traffic detoured through
  OVH's network, even a France hop). Fix: **restart your router**; if persistent, a gaming VPN
  (ExitLag/NoPing free trial) reroutes around it. It clears on its own usually.
- **Wired Ethernet** is the single best fix for rubber-banding ("lag back").

---

## 6. Launcher app (Tauri desktop app) — reinstall on new PC

- Installs to `AppData/Local/Shadow Client/`. **Reinstall fresh** on the new PC (don't copy AppData).
- The **auto-updater in old builds is broken** — it runs the downloaded installer with a malformed
  command and just closes ("update keeps crashing"). Fix = install the update manually:
  `Unblock-File <setup.exe>` then `Start-Process <setup.exe> -ArgumentList "/S" -Wait`. The update server
  is `shadowclient.app`; it downloads setups to `%TEMP%/ShadowClient-update/`.

---

## 7. Where the raw stuff lives

- **Full chat transcript (JSONL):** `C:/Users/ediso/.claude/projects/C--Users-ediso-Downloads/4d4084ce-bfb2-4d86-a52f-cc73606dff7a.jsonl`
- **Assistant memory notes:** `C:/Users/ediso/.claude/projects/C--Users-ediso-Downloads/memory/` (MEMORY.md + individual .md files — shadow-chat-deploy, launcher-app-troubleshooting, worktree-junction-data-loss, keep-base-settings, etc.)
- **This repo:** git remote `bluestatic11/shadow-client` (but local commits this session are unpushed).

⚠️ **Hard-won lesson:** never `rd /s /q` / `rm -rf` a git worktree that might contain directory
junctions — it follows them and wiped `game_dir` once. Recover the mod set from the Lunar mirror.
