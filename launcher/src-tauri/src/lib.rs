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
    let profile = version;

    with_busy(app, busy, |app| async move {
        let app1 = app.clone();
        let progress = move |line: String| emit_line(&app1, "stdout", line);
        let app2 = app.clone();
        let on_line = move |kind: String, line: String| emit_line(&app2, &kind, line);
        let code = tokio::task::spawn_blocking(move || {
            // launch shells out to Java synchronously — keep it on a blocking
            // thread so we don't stall the tokio runtime.
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all().build().unwrap();
            rt.block_on(setup::launch(
                here, state_file, profile, heap_mb, gc, username, progress, on_line,
            ))
        }).await.map_err(|e| anyhow::anyhow!(e))??;
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

#[tauri::command]
async fn microsoft_login(
    _app: tauri::AppHandle,
    _busy: State<'_, AppState>,
) -> Result<i32, String> {
    // TODO: native Rust port of the MSA device-code flow. For now, the
    // corner widget falls back to offline mode and the user can ignore it.
    Err("Microsoft sign-in is not yet implemented in the Rust port — \
         offline mode works for singleplayer and offline-mode servers. \
         Online-mode multiplayer support coming in a follow-up release.".into())
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
            project_path,
            check_python,
            diagnostics,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
