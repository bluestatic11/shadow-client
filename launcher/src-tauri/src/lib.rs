//! Shadow Client launcher — Tauri 2 backend.
//!
//! v0.3.0: 100% native Rust. Used to subprocess `python client.py …` for
//! everything; now we talk to Mojang / Fabric / Modrinth directly via
//! reqwest and spawn Java ourselves. No Python anywhere — installer is
//! back to its original ~3 MB and "Python not found" can never happen
//! again.

mod auth;
mod fabric;
mod jdk;
mod jvm;
mod mods;
mod mojang;
mod setup;
mod shadow_chat;

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use serde::{Deserialize, Serialize};
use tauri::{Emitter, Manager, State};

/// The "project root" — where game_dir/, jdk-*/ and installed.json live.
/// On installed builds this is the parent of the .exe; in dev it's two
/// levels up from src-tauri/.
fn project_root() -> PathBuf {
    let exe = std::env::current_exe().ok();
    let exe_dir = exe.as_ref().and_then(|p| p.parent()).map(|p| p.to_path_buf());

    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(dir) = &exe_dir {
        candidates.push(dir.join("resources"));
        candidates.push(dir.join("../Resources"));
        candidates.push(dir.clone());
        let mut p = dir.clone();
        for _ in 0..3 {
            if let Some(parent) = p.parent() {
                p = parent.to_path_buf();
                candidates.push(p.join("resources"));
                candidates.push(p.clone());
            }
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("../.."));
        candidates.push(cwd.join(".."));
        candidates.push(cwd.clone());
    }

    // Prefer a candidate that already has game_dir/ — that's an established
    // install. Otherwise fall back to a writable user-data dir.
    for c in &candidates {
        if c.join("game_dir").exists() {
            return c.canonicalize().unwrap_or(c.clone());
        }
    }
    // First-launch path: write to %APPDATA%/ShadowClient (or platform equivalent)
    // so we don't try to write into Program Files where the user may not have
    // permission.
    user_data_root()
}

fn user_data_root() -> PathBuf {
    // dirs-next would be cleaner but we don't want another dep. Manual.
    #[cfg(target_os = "windows")]
    {
        if let Ok(appdata) = std::env::var("APPDATA") {
            return PathBuf::from(appdata).join("ShadowClient");
        }
    }
    #[cfg(target_os = "macos")]
    {
        if let Ok(home) = std::env::var("HOME") {
            return PathBuf::from(home).join("Library").join("Application Support").join("ShadowClient");
        }
    }
    if let Ok(home) = std::env::var("HOME") {
        return PathBuf::from(home).join(".shadowclient");
    }
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

/// Tracks if a long-running task is in flight so the UI can disable PLAY etc.
#[derive(Default)]
pub struct AppState {
    pub busy: Mutex<bool>,
}

#[derive(Serialize, Clone)]
pub struct ProcessEvent {
    pub kind: String,
    pub line: String,
    pub exit_code: Option<i32>,
}

/// Run a setup-or-launch closure with progress + line callbacks that emit
/// `python-output` events to the front-end. (Event name kept as
/// "python-output" for back-compat with existing JS listeners — content is
/// now from Rust, but the channel name is just a label.)
async fn with_busy<F, Fut, T>(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    work: F,
) -> Result<T, String>
where
    F: FnOnce(tauri::AppHandle) -> Fut,
    Fut: std::future::Future<Output = anyhow::Result<T>>,
{
    {
        let mut b = busy.busy.lock().unwrap();
        if *b { return Err("another task already running".into()); }
        *b = true;
    }
    let result = work(app.clone()).await;
    *busy.busy.lock().unwrap() = false;
    result.map_err(|e| format!("{e:#}"))
}

fn emit_line(app: &tauri::AppHandle, kind: &str, line: String) {
    let _ = app.emit("python-output", ProcessEvent {
        kind: kind.into(), line, exit_code: None,
    });
}

// ─────────────────────── Commands ───────────────────────

#[tauri::command]
async fn setup_client(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    username: String,
    version: Option<String>,
) -> Result<i32, String> {
    let here = project_root();
    let state_file = here.join("installed.json");
    let version_arg = version.clone().unwrap_or_else(|| "latest".into());
    let profile = version;

    with_busy(app, busy, |app| async move {
        let app2 = app.clone();
        let progress = move |line: String| {
            emit_line(&app2, "stdout", line);
        };
        setup::setup(here, state_file, username, version_arg, profile, progress).await?;
        Ok(0)
    }).await
}

#[tauri::command]
async fn launch_game(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    heap_mb: u32,
    gc: String,
    username: Option<String>,
    version: Option<String>,
) -> Result<i32, String> {
    let here = project_root();
    let state_file = here.join("installed.json");
    let profile = version.clone();
    let version_for_event = version.unwrap_or_else(|| "unknown".into());

    with_busy(app, busy, |app| async move {
        let app1 = app.clone();
        let progress = move |line: String| emit_line(&app1, "stdout", line);
        let app2 = app.clone();
        let on_line = move |kind: String, line: String| emit_line(&app2, &kind, line);

        // Emit a "mc-started" event so the front-end can flip the "You" row
        // in the friends panel to a "Playing X" state. The corresponding
        // "mc-exited" fires after child.wait() returns below.
        //
        // Clone first so the moved-into-json! one doesn't deny us a copy
        // for the exit event later.
        let version_for_exit = version_for_event.clone();
        let _ = app.emit("mc-started", serde_json::json!({
            "version": version_for_event,
        }));
        let app_for_exit = app.clone();

        let code = tokio::task::spawn_blocking(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all().build().unwrap();
            rt.block_on(setup::launch(
                here, state_file, profile, heap_mb, gc, username, progress, on_line,
            ))
        }).await.map_err(|e| anyhow::anyhow!(e))??;

        let _ = app_for_exit.emit("mc-exited", serde_json::json!({
            "version": version_for_exit,
            "code": code,
        }));
        Ok(code)
    }).await
}

#[tauri::command]
async fn update_mods(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    version: Option<String>,
) -> Result<i32, String> {
    let here = project_root();
    let state_file = here.join("installed.json");
    let state = setup::load_state(&state_file);
    let profile = version
        .or(state.last_used.clone())
        .ok_or_else(|| "Run setup first.".to_string())?;
    let ps = state.profiles.get(&profile)
        .cloned()
        .ok_or_else(|| format!("Profile '{profile}' not installed"))?;
    let mc_version = ps.mc_version.unwrap_or_default();

    with_busy(app, busy, |app| async move {
        let app2 = app.clone();
        let progress = move |line: String| emit_line(&app2, "stdout", line);
        let client = reqwest::Client::builder()
            .user_agent(mojang::UA).build()?;
        let (profile_dir, _) = setup::resolve_dirs(&here, Some(&profile));
        let mods_dir = profile_dir.join("mods");
        progress(format!("Refreshing mods for profile '{profile}'…"));
        mods::install_mods(&client, &mods_dir, &mc_version, progress.clone()).await?;
        Ok(0)
    }).await
}

/// Open a URL in the user's default browser. Avoids the tauri-plugin-shell
/// `ShellExt` API (its signature shifts between v2 minor releases and bit us
/// in v0.3.5) — std::process::Command is rock-stable and works on all three
/// target platforms.
fn open_url_in_browser(url: &str) -> std::io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        // `start ""` with the empty title is the canonical way to spawn the
        // default-protocol-handler on Windows without `cmd` re-parsing the
        // URL's & / ? characters as command separators.
        std::process::Command::new("cmd")
            .args(["/c", "start", "", url])
            .spawn()
            .map(|_| ())
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open").arg(url).spawn().map(|_| ())
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open").arg(url).spawn().map(|_| ())
    }
}

#[tauri::command]
async fn microsoft_login(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
) -> Result<i32, String> {
    let here = project_root();
    let account_file = here.join("game_dir").join("mc-client-account.json");
    with_busy(app, busy, |app| async move {
        let app2 = app.clone();
        let progress = move |line: String| emit_line(&app2, "stdout", line);

        // Pop the browser open on the verification URL as soon as we get
        // the device code, so the user doesn't have to type anything.
        let on_prompt = move |p: auth::DeviceCodePrompt| {
            let _ = open_url_in_browser(&p.verification_uri_complete);
        };

        let account = auth::microsoft_device_login(progress, on_prompt).await?;
        if let Some(parent) = account_file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        account.save(&account_file)?;
        Ok(0)
    }).await
}

#[tauri::command]
async fn logout_account(
    _app: tauri::AppHandle,
    _busy: State<'_, AppState>,
    username: String,
) -> Result<i32, String> {
    let here = project_root();
    let account_file = here.join("game_dir").join("mc-client-account.json");
    auth::Account::offline(&username)
        .save(&account_file)
        .map_err(|e| format!("{e:#}"))?;
    Ok(0)
}

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct AccountInfo {
    pub username: Option<String>,
    pub uuid: Option<String>,
    pub user_type: Option<String>,
}

#[tauri::command]
fn read_account() -> Result<Option<AccountInfo>, String> {
    let here = project_root();
    let account_file = here.join("game_dir").join("mc-client-account.json");
    let Some(a) = auth::Account::load(&account_file) else { return Ok(None) };
    Ok(Some(AccountInfo {
        username: Some(a.username),
        uuid: Some(a.uuid),
        user_type: Some(a.user_type),
    }))
}

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct InstalledState {
    pub mc_version: Option<String>,
    pub fabric_loader: Option<String>,
    pub installed_mods: Vec<String>,
    pub installed_profiles: Vec<String>,
}

#[tauri::command]
fn read_state(version: Option<String>) -> Result<Option<InstalledState>, String> {
    let here = project_root();
    let state_file = here.join("installed.json");
    let state = setup::load_state(&state_file);
    let installed_profiles: Vec<String> = state.profiles.keys().cloned().collect();
    let pick = version.or(state.last_used.clone());
    let p = pick.as_ref().and_then(|name| state.profiles.get(name));
    if let Some(p) = p {
        Ok(Some(InstalledState {
            mc_version: p.mc_version.clone(),
            fabric_loader: p.fabric_loader.clone(),
            installed_mods: p.installed_mods.clone(),
            installed_profiles,
        }))
    } else if !installed_profiles.is_empty() {
        Ok(Some(InstalledState {
            installed_profiles,
            ..Default::default()
        }))
    } else {
        Ok(None)
    }
}

#[tauri::command]
fn list_mods(version: Option<String>) -> Result<Vec<String>, String> {
    let here = project_root();
    let candidates: Vec<PathBuf> = match version.as_deref() {
        Some(v) => vec![
            here.join("game_dir").join("profiles").join(v).join("mods"),
            here.join("game_dir").join("mods"),
        ],
        None => vec![here.join("game_dir").join("mods")],
    };
    let Some(dir) = candidates.into_iter().find(|p| p.exists()) else {
        return Ok(vec![]);
    };
    let mut out: Vec<String> = std::fs::read_dir(&dir)
        .map_err(|e| e.to_string())?
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            (name.ends_with(".jar") || name.ends_with(".jar.disabled")).then_some(name)
        })
        .collect();
    out.sort();
    Ok(out)
}

#[tauri::command]
fn project_path() -> String {
    project_root().display().to_string()
}

/// Write a user-provided .jar mod into the chosen profile's mods folder.
///
/// Called from the Mods settings tab when the user picks one or more
/// .jar files through the native file picker. `bytes` arrives as a JSON
/// array of u8 from the JS side (Tauri's default serde encoding for
/// Vec<u8> over IPC).
///
/// Defensive measures:
///   - Reject anything that doesn't end in `.jar`.
///   - Strip path separators from the filename so a malicious manifest
///     can't write outside the mods directory (e.g. `..\..\evil.jar`).
///   - Refuse paths that contain `..` segments after sanitising.
#[tauri::command]
fn add_mod_jar(
    version: Option<String>,
    name: String,
    bytes: Vec<u8>,
) -> Result<String, String> {
    use std::path::Path;
    let here = project_root();
    let mods_dir = match version.as_deref() {
        Some(v) if !v.is_empty() =>
            here.join("game_dir").join("profiles").join(v).join("mods"),
        _ => here.join("game_dir").join("mods"),
    };
    std::fs::create_dir_all(&mods_dir).map_err(|e| e.to_string())?;

    // file_name() strips any directory components — so even a malicious
    // input like "../../../evil.exe" reduces to "evil.exe".
    let safe = Path::new(&name)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    if safe.is_empty() || safe.contains("..") {
        return Err(format!("invalid mod filename: {name}"));
    }
    if !safe.to_lowercase().ends_with(".jar") {
        return Err(format!("only .jar files are accepted; got {safe}"));
    }
    let dest = mods_dir.join(&safe);
    std::fs::write(&dest, &bytes)
        .map_err(|e| format!("writing {}: {e}", dest.display()))?;
    Ok(safe)
}

// ───── Cosmetics persistence ──────────────────────────────────
//
// Stored as a JSON object at <project_root>/cosmetics.json with one key
// per slot (back / head / trail / accent). Values are short string IDs
// (e.g. "cape", "halo", "fairies", or "#ff2030" for the accent color).
// The Shadow HUD mod will read this file at game launch once it's
// bundled in a follow-up release.

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Cosmetics {
    #[serde(default)] pub back:   Option<String>,
    #[serde(default)] pub head:   Option<String>,
    #[serde(default)] pub trail:  Option<String>,
    /// Added v0.3.16. Optional on disk so older cosmetics.json files load.
    #[serde(default)] pub aura:   Option<String>,
    #[serde(default)] pub accent: Option<String>,
}

fn cosmetics_file() -> PathBuf {
    project_root().join("cosmetics.json")
}

#[tauri::command]
fn read_cosmetics() -> Cosmetics {
    let Ok(raw) = std::fs::read(cosmetics_file()) else { return Cosmetics::default() };
    serde_json::from_slice(&raw).unwrap_or_default()
}

#[tauri::command]
fn save_cosmetics(cosm: Cosmetics) -> Result<(), String> {
    let path = cosmetics_file();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let body = serde_json::to_string_pretty(&cosm).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, body).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
    Ok(())
}

// ───── Friends list ───────────────────────────────────────────
//
// Stored as a JSON array at <project_root>/friends.json. Pure
// launcher-local data — no Mojang account lookup, no presence/online
// status check, no third-party servers. Just usernames the user has
// added so they can see them in the sidebar.

#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Friend {
    pub username: String,
    /// Epoch seconds when added. Used to sort the list with newest first.
    #[serde(default)]
    pub added_at: u64,
    /// Presence-status fields. All optional and only populated when a
    /// real presence service is wired up in a future release. The shape
    /// is locked in now so older friends.json files migrate forward
    /// without losing user data.
    #[serde(default)]
    pub status: Option<String>,      // "online" | "playing" | "in_menu" | None=offline
    #[serde(default)]
    pub server: Option<String>,      // e.g. "hypixel.net" / "Singleplayer"
    #[serde(default)]
    pub version: Option<String>,     // e.g. "26.1.2"
    #[serde(default)]
    pub last_seen: Option<u64>,      // epoch seconds (None = never seen)
}

fn friends_file() -> PathBuf {
    project_root().join("friends.json")
}

fn load_friends() -> Vec<Friend> {
    let Ok(raw) = std::fs::read(friends_file()) else { return Vec::new() };
    serde_json::from_slice(&raw).unwrap_or_default()
}

fn save_friends(friends: &[Friend]) -> Result<(), String> {
    let path = friends_file();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let tmp = path.with_extension("json.tmp");
    let body = serde_json::to_string_pretty(friends).map_err(|e| e.to_string())?;
    std::fs::write(&tmp, body).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn friends_list() -> Vec<Friend> {
    load_friends()
}

#[tauri::command]
fn friends_add(username: String) -> Result<Vec<Friend>, String> {
    let username = username.trim().to_string();
    if username.is_empty() {
        return Err("Username can't be empty".into());
    }
    if username.len() > 16 {
        return Err("Minecraft usernames are 16 chars max".into());
    }
    // Mojang allows letters, digits, underscore in usernames. We accept the
    // same set so people don't get rejected for a valid name.
    if !username.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err("Usernames may only contain letters, digits, and underscores".into());
    }
    let mut friends = load_friends();
    if friends.iter().any(|f| f.username.eq_ignore_ascii_case(&username)) {
        return Err(format!("'{}' is already on your friends list", username));
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    // The Friend struct gained optional status/server/version/last_seen
    // fields in v0.3.11 — use Default::default() to fill them in rather
    // than spelling each None out at every construction site.
    friends.insert(0, Friend {
        username,
        added_at: now,
        ..Default::default()
    });
    save_friends(&friends)?;
    Ok(friends)
}

#[tauri::command]
fn friends_remove(username: String) -> Result<Vec<Friend>, String> {
    let mut friends = load_friends();
    friends.retain(|f| !f.username.eq_ignore_ascii_case(&username));
    save_friends(&friends)?;
    Ok(friends)
}

/// Best-effort FPS estimate based on the user's CPU + render distance + heap.
///
/// The exact number isn't meant to be authoritative — actual FPS depends on
/// GPU, mod stack, scene complexity, monitor refresh rate, OS scheduling,
/// and a hundred other things we can't measure from inside the launcher.
/// But "600+" hardcoded was misleading for laptop / low-end users, and
/// "300" hardcoded would be insulting to a Ryzen 9 user. A rough
/// estimate that reacts to the user's actual config is a meaningful upgrade.
///
/// Inputs:
///   - CPU core count via std::thread::available_parallelism (cheap proxy
///     for "CPU tier" — modern high-core-count CPUs ≈ better single-thread
///     perf too, since Minecraft is largely single-threaded in the render
///     path)
///   - Render distance from the active profile's options.txt (defaults to
///     the vanilla 16 if the file's missing or malformed)
///   - RAM heap from the JS-side setting (we receive it as a parameter)
///
/// Returns a single number (e.g. 420) suitable for displaying as "420+".
#[tauri::command]
fn estimate_fps(version: Option<String>, heap_mb: Option<u32>) -> u32 {
    // Cores → "CPU tier" baseline. Empirical-ish: modern desktop Ryzen 9
    // (16 cores / 32 threads) hits ~1000+ FPS on 16-chunk Sodium 1.21+.
    // A 4-core laptop i5 might do 150-200. The ladder roughly matches
    // common consumer chips.
    let cores = std::thread::available_parallelism()
        .map(|n| n.get() as u32)
        .unwrap_or(4);
    // Conservative — previous values (1100 / 800 / 550 / 380 / …) were way
    // over what users actually see in-game because they assumed best-case
    // GPU, light scene, and zero background load. Cut roughly in half so
    // the displayed number under-promises and the user is pleasantly
    // surprised at runtime rather than disappointed.
    let base: u32 = match cores {
        c if c >= 24 => 550,    // Threadripper / dual-CPU
        c if c >= 16 => 400,    // Ryzen 9 / i9
        c if c >= 12 => 290,    // Ryzen 7 / i7
        c if c >= 8  => 210,    // Ryzen 5 / i5 mid
        c if c >= 6  => 150,    // older i5 / mobile Ryzen 5
        c if c >= 4  => 95,     // i3 / low-end / laptop dual-with-HT
        _            => 55,     // very old / netbook
    };

    // Render distance multiplier. MC FPS scales roughly inversely with the
    // square root of view radius — 32 chunks is ~70% the FPS of 16, 8 is
    // ~140%. We round to whole-percent factors for readability.
    let rd = read_render_distance_for(&version);
    let rd_factor: f32 = match rd {
        0..=6   => 1.7,
        7..=10  => 1.35,
        11..=14 => 1.10,
        15..=18 => 1.0,
        19..=24 => 0.75,
        25..=32 => 0.55,
        33..=48 => 0.35,
        _       => 0.20,
    };

    // Heap penalty if the user gave Java less than ~3 GB — they'll get
    // GC stutter that drops steady-state FPS even if peak is high. Above
    // 4 GB, more heap doesn't help (MC doesn't allocate that much).
    let heap_factor: f32 = match heap_mb.unwrap_or(4096) {
        h if h < 2048 => 0.7,
        h if h < 3072 => 0.85,
        h if h < 4096 => 0.95,
        _             => 1.0,
    };

    ((base as f32) * rd_factor * heap_factor).round() as u32
}

fn read_render_distance_for(version: &Option<String>) -> u32 {
    let here = project_root();
    let candidates: Vec<std::path::PathBuf> = match version {
        Some(v) => vec![
            here.join("game_dir").join("profiles").join(v).join("options.txt"),
            here.join("game_dir").join("options.txt"),
        ],
        None => vec![here.join("game_dir").join("options.txt")],
    };
    for path in candidates {
        if let Ok(content) = std::fs::read_to_string(&path) {
            for line in content.lines() {
                if let Some(value) = line.strip_prefix("renderDistance:") {
                    if let Ok(n) = value.trim().parse::<u32>() {
                        return n;
                    }
                }
            }
        }
    }
    16  // Mojang's default
}

/// Recursive size of game_dir, in megabytes. Used by the home-screen stat
/// tile ("Cached: 1.5 GB"). Walks the tree on a tokio blocking thread so
/// big installs (~1.5+ GB across multiple version profiles) don't freeze
/// the UI thread.
#[tauri::command]
async fn disk_usage_mb() -> u64 {
    tokio::task::spawn_blocking(|| {
        let here = project_root();
        let game_dir = here.join("game_dir");
        folder_size_bytes(&game_dir) / (1024 * 1024)
    })
    .await
    .unwrap_or(0)
}

fn folder_size_bytes(path: &Path) -> u64 {
    if !path.exists() { return 0; }
    let mut total: u64 = 0;
    let Ok(entries) = std::fs::read_dir(path) else { return 0; };
    for entry in entries.flatten() {
        let p = entry.path();
        if let Ok(meta) = entry.metadata() {
            if meta.is_file() {
                total += meta.len();
            } else if meta.is_dir() {
                total += folder_size_bytes(&p);
            }
        }
    }
    total
}

/// Open the game_dir in the OS's default file explorer (Explorer on Windows,
/// Finder on macOS, the user's preferred file manager on Linux via xdg-open).
/// Backs the "📂 Open folder" home-screen quick action tile.
#[tauri::command]
fn open_folder() -> Result<(), String> {
    let here = project_root();
    let game_dir = here.join("game_dir");
    // Make sure the dir exists — opening a missing folder is a confusing
    // no-op on Windows ("file not found" silent error).
    std::fs::create_dir_all(&game_dir).map_err(|e| e.to_string())?;

    let spawned = {
        #[cfg(target_os = "windows")]
        { std::process::Command::new("explorer").arg(&game_dir).spawn() }
        #[cfg(target_os = "macos")]
        { std::process::Command::new("open").arg(&game_dir).spawn() }
        #[cfg(target_os = "linux")]
        { std::process::Command::new("xdg-open").arg(&game_dir).spawn() }
    };
    spawned.map(|_| ()).map_err(|e| e.to_string())
}

/// In-launcher self-update. Downloads the given installer URL to a temp
/// directory, spawns it (NSIS / DMG / AppImage will detect the existing
/// install and upgrade in place), then exits the current process so the
/// installer can replace the .exe without "file in use" errors.
///
/// The JS layer fetches the asset URL from the GitHub Releases API and
/// hands it to us here. We trust the URL because it comes from a GitHub
/// HTTPS API response — no signature verification, but the URL itself is
/// authenticated end-to-end via TLS to api.github.com.
/// Sweep stale installers out of the auto-update temp dir.
///
/// Every in-app auto-update writes the new installer to
/// `%TEMP%\ShadowClient-update\Shadow.Client_<v>_x64-setup.exe` and spawns
/// it. The installer ALWAYS completes before our process exits (we wait
/// 800 ms to make sure), so the temp file becomes garbage as soon as
/// install finishes. Without cleanup, every release leaves a 3-4 MB .exe
/// behind in %TEMP% forever. Run on boot as fire-and-forget — failures
/// are silently ignored, this is housekeeping not critical path.
#[tauri::command]
fn sweep_update_temp() {
    let dir = std::env::temp_dir().join("ShadowClient-update");
    if !dir.exists() { return; }
    let Ok(entries) = std::fs::read_dir(&dir) else { return };
    let now = std::time::SystemTime::now();
    for e in entries.flatten() {
        // Only sweep files older than 30 min, so an in-flight update we
        // just kicked off isn't deleted out from under us by a second
        // launcher instance the user happened to open at the same time.
        if let Ok(meta) = e.metadata() {
            if let Ok(modified) = meta.modified() {
                if let Ok(age) = now.duration_since(modified) {
                    if age.as_secs() > 30 * 60 {
                        let _ = std::fs::remove_file(e.path());
                    }
                }
            }
        }
    }
}

#[tauri::command]
async fn install_update(
    app: tauri::AppHandle,
    url: String,
) -> Result<(), String> {
    use std::time::Duration;

    let temp_dir = std::env::temp_dir().join("ShadowClient-update");
    std::fs::create_dir_all(&temp_dir).map_err(|e| e.to_string())?;

    // Use the filename from the URL so the installer launches with a
    // sensible name on the user's screen (vs. some opaque tmp file).
    let filename = url
        .rsplit('/')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or("Shadow.Client_update.exe");
    let dest = temp_dir.join(filename);

    // Stream the download so big files (~80 MB AppImage) don't sit in RAM.
    let client = reqwest::Client::builder()
        .user_agent(mojang::UA)
        .timeout(Duration::from_secs(600))
        .build()
        .map_err(|e| e.to_string())?;
    let resp = client
        .get(&url)
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .map_err(|e| format!("download failed: {e}"))?;
    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("download failed mid-stream: {e}"))?;
    std::fs::write(&dest, &bytes)
        .map_err(|e| format!("writing {}: {e}", dest.display()))?;

    // Make the file executable on Unix (NSIS .exe doesn't need it, but
    // AppImage / .deb dpkg-launcher does).
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(&dest)
            .map_err(|e| e.to_string())?
            .permissions();
        perms.set_mode(0o755);
        let _ = std::fs::set_permissions(&dest, perms);
    }

    // Spawn the installer DETACHED — it must outlive us so it can replace
    // our .exe on disk after we exit.
    //
    // v0.3.27: pass `/S` (NSIS silent flag) so the "Shadow Client Setup"
    // wizard NEVER pops up. Previously the user had to click through the
    // installer's welcome/location/finish pages on every auto-update,
    // which feels like "doing the setup again" — the whole point of
    // auto-update is that it should be invisible.
    //
    // For auto-restart-after-install we wrap the installer call in a
    // cmd.exe one-liner:
    //     <installer> /S  &  start "" "<current launcher .exe>"
    // cmd waits for the installer to finish (& runs sequentially with no
    // short-circuit on error) then relaunches our own .exe path. NSIS
    // installs to the same path it was originally installed at, so the
    // path we capture BEFORE spawning is the same path the new launcher
    // ends up at.
    let current_exe = std::env::current_exe().ok();

    let spawn_result = {
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            // DETACHED_PROCESS (0x8) | CREATE_NEW_PROCESS_GROUP (0x200)
            //                        | CREATE_NO_WINDOW (0x08000000)
            // The NO_WINDOW flag keeps the cmd helper from flashing a
            // console window during the silent install.
            const CREATE_FLAGS: u32 = 0x00000008 | 0x00000200 | 0x08000000;
            if let Some(launcher_exe) = current_exe.as_ref() {
                // 2 s wait gives our process time to fully exit before
                // NSIS tries to write to the locked .exe. Without this,
                // the file overwrite races our shutdown and can fail
                // with "file in use".
                let cmd_line = format!(
                    r#"timeout /t 2 /nobreak >nul & "{}" /S & start "" "{}""#,
                    dest.display(),
                    launcher_exe.display()
                );
                std::process::Command::new("cmd")
                    .args(["/C", &cmd_line])
                    .creation_flags(CREATE_FLAGS)
                    .spawn()
            } else {
                // No idea where we live — best effort: install silently
                // without auto-relaunch. The user reopens manually.
                let cmd_line = format!(
                    r#"timeout /t 2 /nobreak >nul & "{}" /S"#,
                    dest.display()
                );
                std::process::Command::new("cmd")
                    .args(["/C", &cmd_line])
                    .creation_flags(CREATE_FLAGS)
                    .spawn()
            }
        }
        #[cfg(target_os = "macos")]
        {
            // DMG can't be silently installed; just open it and let the
            // user drag the .app into Applications. Same behaviour as
            // before — macOS auto-update was never one-click.
            std::process::Command::new("open").arg(&dest).spawn()
        }
        #[cfg(all(unix, not(target_os = "macos")))]
        {
            // AppImage: just run it — it replaces itself in place.
            std::process::Command::new(&dest).spawn()
        }
    };
    spawn_result.map_err(|e| format!("running installer {}: {e}", dest.display()))?;

    // Give the installer a beat to actually start before we vanish, so
    // the cmd helper has time to spawn the installer process before our
    // .exe gets a chance to be replaced on disk.
    tokio::time::sleep(Duration::from_millis(800)).await;
    app.exit(0);
    Ok(())
}

#[derive(Serialize, Clone)]
pub struct PythonProbe { pub ok: bool, pub detail: String }

#[tauri::command]
fn check_python() -> PythonProbe {
    // Kept for back-compat with the existing JS banner. Now that we don't
    // need Python at all, this always returns ok=true with a "Python not
    // required" detail so the banner stays hidden.
    PythonProbe {
        ok: true,
        detail: "native Rust port — Python is no longer required".into(),
    }
}

#[derive(Serialize, Clone)]
pub struct Diagnostics {
    pub launcher_version: String,
    pub exe_path: String,
    pub exe_dir: String,
    pub project_root: String,
    pub project_root_has_game_dir: bool,
    pub java_detected: Option<String>,
    pub java_major: u32,
    pub cwd: String,
}

#[tauri::command]
fn diagnostics() -> Diagnostics {
    let exe = std::env::current_exe().ok();
    let exe_path = exe.as_ref().map(|p| p.display().to_string()).unwrap_or_default();
    let exe_dir = exe.as_ref().and_then(|p| p.parent())
        .map(|p| p.display().to_string()).unwrap_or_default();
    let root = project_root();
    let java = jdk::find_java(&root);
    let java_major = java.as_ref()
        .and_then(|p| jdk::java_major_version(p))
        .unwrap_or(0);
    Diagnostics {
        launcher_version: env!("CARGO_PKG_VERSION").to_string(),
        exe_path,
        exe_dir,
        project_root: root.display().to_string(),
        project_root_has_game_dir: root.join("game_dir").exists(),
        java_detected: java.map(|p| p.display().to_string()),
        java_major,
        cwd: std::env::current_dir().map(|p| p.display().to_string()).unwrap_or_default(),
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AppState::default())
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![
            launch_game,
            setup_client,
            microsoft_login,
            logout_account,
            update_mods,
            read_state,
            read_account,
            list_mods,
            add_mod_jar,
            project_path,
            check_python,
            diagnostics,
            install_update,
            sweep_update_temp,
            disk_usage_mb,
            open_folder,
            estimate_fps,
            friends_list,
            friends_add,
            friends_remove,
            read_cosmetics,
            save_cosmetics,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
