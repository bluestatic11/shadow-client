// Shadow Client launcher — Tauri 2 backend.
//
// All heavy lifting (MC version downloading, Fabric installation, mod
// management, account auth) is done by the existing Python launcher at
// ../../client.py. We expose Tauri commands that subprocess-call those
// scripts and stream their output back to the UI.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;
use serde::{Deserialize, Serialize};
use tauri::{Emitter, Manager, State};

/// Resolve the launcher's "project root" — the parent of the launcher/
/// directory, which contains client.py + game_dir/ + branding/.
/// In dev (cargo run), CWD is launcher/src-tauri, so we walk up 2 levels.
/// In production (installed app), the bundle is shipped with these files
/// adjacent — see the install-time resource copy in the NSIS preprocess.
fn project_root() -> PathBuf {
    // Try a few candidate locations until one has client.py
    let candidates = [
        // Dev: launcher/src-tauri/../../
        std::env::current_dir().ok().map(|p| p.join("../..")),
        // Dev (alternative): cwd is launcher/, parent has client.py
        std::env::current_dir().ok().map(|p| p.join("..")),
        // Installed: app data alongside the .exe
        std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.join("resources"))),
        std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|p| p.to_path_buf())),
    ];
    for c in candidates.iter().flatten() {
        if c.join("client.py").exists() {
            return c.canonicalize().unwrap_or(c.clone());
        }
    }
    // Last-ditch fallback: just return cwd
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

#[derive(Serialize, Deserialize, Clone)]
pub struct InstalledState {
    pub mc_version: Option<String>,
    pub fabric_loader: Option<String>,
    pub installed_mods: Vec<String>,
}

#[derive(Serialize, Clone)]
pub struct ProcessEvent {
    pub kind: String,         // "stdout" | "stderr" | "exit"
    pub line: String,
    pub exit_code: Option<i32>,
}

#[derive(Default)]
pub struct AppState {
    /// Tracks if a long-running Python subprocess is in flight so the
    /// UI can disable the PLAY button etc. while a build is happening.
    pub busy: Mutex<bool>,
}

/// Run `python client.py <args>` from the project root. Streams stdout
/// and stderr as events to the front-end. Blocks until exit.
fn run_python(
    app: &tauri::AppHandle,
    args: Vec<String>,
    busy: State<AppState>,
) -> Result<i32, String> {
    {
        let mut b = busy.busy.lock().unwrap();
        if *b { return Err("another task already running".into()); }
        *b = true;
    }
    let root = project_root();
    let mut cmd = Command::new("python");
    cmd.arg("client.py")
        .args(&args)
        .current_dir(&root)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            *busy.busy.lock().unwrap() = false;
            return Err(format!("failed to spawn python: {} — is Python on PATH?", e));
        }
    };
    // Stream stdout/stderr in background threads so the UI sees lines live.
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let app_out = app.clone();
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader};
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = app_out.emit("python-output", ProcessEvent {
                kind: "stdout".into(), line, exit_code: None,
            });
        }
    });
    let app_err = app.clone();
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader};
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = app_err.emit("python-output", ProcessEvent {
                kind: "stderr".into(), line, exit_code: None,
            });
        }
    });
    let status = child.wait().map_err(|e| e.to_string())?;
    let code = status.code().unwrap_or(-1);
    let _ = app.emit("python-output", ProcessEvent {
        kind: "exit".into(),
        line: format!("process exited with code {}", code),
        exit_code: Some(code),
    });
    *busy.busy.lock().unwrap() = false;
    Ok(code)
}

#[tauri::command]
async fn launch_game(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    heap_mb: u32,
    gc: String,
    username: Option<String>,
) -> Result<i32, String> {
    let mut args = vec![
        "launch".into(),
        "--heap".into(), heap_mb.to_string(),
        "--gc".into(), gc,
    ];
    if let Some(u) = username {
        args.push("--username".into());
        args.push(u);
    }
    run_python(&app, args, busy)
}

#[tauri::command]
async fn setup_client(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    username: String,
    version: Option<String>,
) -> Result<i32, String> {
    let mut args = vec![
        "setup".into(),
        "--username".into(), username,
    ];
    if let Some(v) = version {
        args.push("--version".into());
        args.push(v);
    }
    run_python(&app, args, busy)
}

#[tauri::command]
async fn microsoft_login(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
) -> Result<i32, String> {
    run_python(&app, vec!["login".into()], busy)
}

#[tauri::command]
async fn update_mods(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
) -> Result<i32, String> {
    run_python(&app, vec!["update-mods".into()], busy)
}

#[tauri::command]
fn read_state() -> Result<Option<InstalledState>, String> {
    let root = project_root();
    let state_file = root.join("installed.json");
    if !state_file.exists() {
        return Ok(None);
    }
    let s = std::fs::read_to_string(&state_file).map_err(|e| e.to_string())?;
    let st: InstalledState = serde_json::from_str(&s).map_err(|e| e.to_string())?;
    Ok(Some(st))
}

#[tauri::command]
fn list_mods() -> Result<Vec<String>, String> {
    let root = project_root();
    let mods_dir = root.join("game_dir").join("mods");
    if !mods_dir.exists() {
        return Ok(vec![]);
    }
    let entries = std::fs::read_dir(&mods_dir).map_err(|e| e.to_string())?;
    let mut out: Vec<String> = entries
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            if name.ends_with(".jar") || name.ends_with(".jar.disabled") {
                Some(name)
            } else {
                None
            }
        })
        .collect();
    out.sort();
    Ok(out)
}

#[tauri::command]
fn project_path() -> String {
    project_root().display().to_string()
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
            update_mods,
            read_state,
            list_mods,
            project_path,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
