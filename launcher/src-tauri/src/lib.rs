// Shadow Client launcher — Tauri 2 backend.
//
// All heavy lifting (MC version downloading, Fabric installation, mod
// management, account auth) is done by the existing Python launcher at
// ../../client.py. We expose Tauri commands that subprocess-call those
// scripts and stream their output back to the UI.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Mutex;
use serde::{Deserialize, Serialize};
use tauri::{Emitter, Manager, State};

/// Resolve the launcher's "project root" — the directory containing
/// `client.py` and the helper Python modules. This is the working directory
/// `client.py` is invoked from, so it has to actually contain the file.
///
/// We probe an expanded set of candidate locations because the file's home
/// depends on how the launcher is being run:
///
///  * **Dev** (`cargo run` from `launcher/src-tauri/`) — CWD is the crate
///    dir, project root is two levels up.
///  * **NSIS install on Windows** — Tauri's bundler copies declared
///    `bundle.resources` into `<install_dir>\resources\` next to the .exe.
///  * **MSI install on Windows** — same as NSIS in practice.
///  * **DMG / .app on macOS** — Tauri puts resources inside
///    `Contents/Resources/` of the .app bundle.
///  * **AppImage on Linux** — extracted to `<exe_dir>/resources/` on first run.
///
/// We hunt through all of these. The order doesn't matter — first match wins.
fn project_root() -> PathBuf {
    let exe = std::env::current_exe().ok();
    let exe_dir = exe.as_ref().and_then(|p| p.parent()).map(|p| p.to_path_buf());

    // Walk a few levels up from the exe so we cover nested layouts
    // (e.g. macOS .app/Contents/MacOS/<exe> wants ../Resources).
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(dir) = &exe_dir {
        candidates.push(dir.join("resources"));     // <exe_dir>/resources (Windows NSIS/MSI)
        candidates.push(dir.join("../Resources")); // <exe_dir>/../Resources (macOS .app)
        candidates.push(dir.clone());               // <exe_dir> (flat layout)
        // Walk up to 3 ancestors looking for a folder with client.py
        let mut p = dir.clone();
        for _ in 0..3 {
            if let Some(parent) = p.parent() {
                p = parent.to_path_buf();
                candidates.push(p.join("resources"));
                candidates.push(p.clone());
            }
        }
    }
    // Dev mode: CWD is launcher/src-tauri or launcher/.
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("../.."));
        candidates.push(cwd.join(".."));
        candidates.push(cwd.clone());
    }

    for c in &candidates {
        let probe = c.join("client.py");
        if probe.exists() {
            return c.canonicalize().unwrap_or(c.clone());
        }
    }
    // Last-ditch: return exe_dir (we'll fail loudly when client.py can't be
    // spawned, but at least the error message points somewhere debuggable).
    exe_dir.unwrap_or_else(|| std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")))
}

/// Check whether `client.py` is reachable from the resolved project root.
/// We want to fail with a friendly error before spawning Python, since
/// "python: can't open file '<root>/client.py': [Errno 2]" is much less
/// helpful than "Shadow Client install is incomplete — reinstall from
/// the website."
fn ensure_client_py(root: &Path) -> Result<(), String> {
    if root.join("client.py").exists() {
        return Ok(());
    }
    Err(format!(
        "Shadow Client install is incomplete — client.py not found at {} .\n\
         The .exe is the launcher only; the Python sources didn't come with it.\n\
         Reinstall the latest .exe from \
         https://github.com/bluestatic11/shadow-client/releases/latest \
         (v0.2.12 or newer bundles the sources automatically).",
        root.display()
    ))
}

/// One profile's worth of installed state, as written by `client.py setup`.
/// Matches the dict shape stored under `installed.json -> profiles -> <name>`.
#[derive(Serialize, Deserialize, Clone, Default)]
pub struct ProfileState {
    pub mc_version: Option<String>,
    pub fabric_loader: Option<String>,
    pub installed_mods: Vec<String>,
}

/// Returned to the front-end. Carries the per-profile detail for the version
/// the UI asked about, plus the list of every profile the user has set up so
/// the version picker can mark them visually.
#[derive(Serialize, Deserialize, Clone, Default)]
pub struct InstalledState {
    pub mc_version: Option<String>,
    pub fabric_loader: Option<String>,
    pub installed_mods: Vec<String>,
    /// Names of every profile present in installed.json (= every MC version
    /// the user has previously set up).
    pub installed_profiles: Vec<String>,
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

    // Fail fast if client.py is missing — much better error than the cryptic
    // "[Errno 2] No such file or directory" Python would produce.
    if let Err(msg) = ensure_client_py(&root) {
        *busy.busy.lock().unwrap() = false;
        return Err(msg);
    }

    // Pick a Python interpreter. Same logic as check_python — first try the
    // bundled embeddable distribution (Windows installs ship one), then fall
    // back to PATH lookups. We use the probe results from check_python
    // rather than blind-spawning candidates: the Microsoft Store stub spawns
    // successfully but isn't Python (it just prints "install from Store"
    // and exits 9009), so picking-the-first-that-spawns is wrong.
    let bundled = bundled_python(&root);
    let probe = check_python();
    if !probe.ok && bundled.is_none() {
        *busy.busy.lock().unwrap() = false;
        return Err(format!(
            "Python isn't available.\n\n{}\n\n\
             Fix: reinstall Shadow Client from \
             https://github.com/bluestatic11/shadow-client/releases/latest \
             — the latest .exe bundles Python so you don't need to install \
             it yourself. Or install Python 3.11+ from \
             https://www.python.org/downloads/ (tick \"Add to PATH\").",
            probe.detail
        ));
    }
    // Build the actual command. Prefer the bundled python.exe path; else
    // resolve from the probe detail (it contains "via `py`" or similar).
    let py_cmd: std::ffi::OsString = if let Some(b) = bundled {
        b.into_os_string()
    } else if probe.detail.contains("via `py`") {
        "py".into()
    } else if probe.detail.contains("via `python3`") {
        "python3".into()
    } else {
        "python".into()
    };

    let mut cmd = Command::new(&py_cmd);
    cmd.arg("client.py")
        .args(&args)
        .current_dir(&root)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            *busy.busy.lock().unwrap() = false;
            return Err(format!(
                "Failed to start {:?}: {}. Try reinstalling Shadow Client.",
                py_cmd, e
            ));
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
    version: Option<String>,
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
    // Tell client.py which per-version profile to launch. If we don't pass
    // this, it falls back to last_used (still correct, just less explicit).
    if let Some(v) = version {
        args.push("--profile".into());
        args.push(v);
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
        // Pass it as both --version (which MC version to download) AND
        // --profile (where to store this version's mods/saves). client.py
        // defaults --profile to the resolved version when omitted, but we
        // pass it explicitly so a user picking "1.21" vs "1.21.0" both land
        // in a profile named exactly what they picked.
        args.push("--version".into());
        args.push(v.clone());
        args.push("--profile".into());
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
    version: Option<String>,
) -> Result<i32, String> {
    let mut args: Vec<String> = vec!["update-mods".into()];
    if let Some(v) = version {
        args.push("--profile".into());
        args.push(v);
    }
    run_python(&app, args, busy)
}

#[tauri::command]
fn read_state(version: Option<String>) -> Result<Option<InstalledState>, String> {
    let root = project_root();
    let state_file = root.join("installed.json");
    if !state_file.exists() {
        return Ok(None);
    }
    let raw = std::fs::read_to_string(&state_file).map_err(|e| e.to_string())?;
    let json: serde_json::Value = serde_json::from_str(&raw).map_err(|e| e.to_string())?;

    // Two on-disk shapes are valid:
    //   1. New: { "profiles": { "1.21.11": {...}, "1.21.10": {...} }, "last_used": "1.21.11" }
    //   2. Old: { "mc_version": "1.21.11", ..., "installed_mods": [...] }
    // We want to expose a uniform InstalledState for the UI.
    let (profiles_map, last_used) = if let Some(profiles) = json.get("profiles").and_then(|p| p.as_object()) {
        (profiles.clone(), json.get("last_used").and_then(|v| v.as_str()).map(String::from))
    } else if json.get("mc_version").is_some() {
        // Legacy single-profile shape — synthesise a one-entry map.
        let legacy_name = json.get("mc_version")
            .and_then(|v| v.as_str())
            .unwrap_or("__legacy__")
            .to_string();
        let mut map = serde_json::Map::new();
        map.insert(legacy_name.clone(), json.clone());
        (map, Some(legacy_name))
    } else {
        return Ok(None);
    };

    // Pick which profile the caller wants to know about.
    let pick = version
        .as_deref()
        .or(last_used.as_deref())
        .map(String::from);
    let profile_obj = pick.as_ref().and_then(|name| profiles_map.get(name));

    let installed_profiles: Vec<String> = profiles_map.keys().cloned().collect();

    let st = match profile_obj {
        Some(p) => {
            let ps: ProfileState = serde_json::from_value(p.clone()).unwrap_or_default();
            InstalledState {
                mc_version:         ps.mc_version,
                fabric_loader:      ps.fabric_loader,
                installed_mods:     ps.installed_mods,
                installed_profiles,
            }
        }
        // The requested version isn't installed — return None for it but
        // still surface the list of profiles the user DOES have, so the UI
        // can render them in the picker.
        None => InstalledState {
            mc_version:     None,
            fabric_loader:  None,
            installed_mods: Vec::new(),
            installed_profiles,
        },
    };
    Ok(Some(st))
}

#[tauri::command]
fn list_mods(version: Option<String>) -> Result<Vec<String>, String> {
    let root = project_root();
    // Try the per-version profile dir first. Fall back to the legacy
    // game_dir/mods/ if no profile is set up yet (or if the user came from
    // an older install).
    let candidate_dirs: Vec<std::path::PathBuf> = match version.as_deref() {
        Some(v) => vec![
            root.join("game_dir").join("profiles").join(v).join("mods"),
            root.join("game_dir").join("mods"),
        ],
        None => vec![root.join("game_dir").join("mods")],
    };
    let mods_dir = candidate_dirs.into_iter().find(|p| p.exists());
    let Some(mods_dir) = mods_dir else {
        return Ok(vec![]);
    };
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

/// A self-test bundle the UI can render in Settings → Diagnostics. Lists
/// everything we know about the install state so a user can paste it back
/// to support without us needing them to dig through %LOCALAPPDATA% or
/// open dev tools.
#[derive(Serialize, Clone)]
pub struct Diagnostics {
    pub launcher_version: String,
    pub exe_path: String,
    pub exe_dir: String,
    pub project_root: String,
    pub project_root_has_client_py: bool,
    pub python: Option<PythonProbe>,
    pub cwd: String,
    pub candidates_checked: Vec<String>,
    pub candidates_with_client_py: Vec<String>,
    pub resource_files_present: Vec<String>,
}

#[tauri::command]
fn diagnostics() -> Diagnostics {
    let exe = std::env::current_exe().ok();
    let exe_path = exe.as_ref().map(|p| p.display().to_string()).unwrap_or_default();
    let exe_dir = exe.as_ref()
        .and_then(|p| p.parent())
        .map(|p| p.display().to_string())
        .unwrap_or_default();

    // Re-run the candidate search to surface WHAT was checked, not just the
    // final winner. Mirrors project_root()'s logic but records every probe.
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(p) = exe.as_ref().and_then(|p| p.parent()) {
        candidates.push(p.join("resources"));
        candidates.push(p.join("../Resources"));
        candidates.push(p.to_path_buf());
        let mut cur = p.to_path_buf();
        for _ in 0..3 {
            if let Some(parent) = cur.parent() {
                cur = parent.to_path_buf();
                candidates.push(cur.join("resources"));
                candidates.push(cur.clone());
            }
        }
    }
    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("../.."));
        candidates.push(cwd.join(".."));
        candidates.push(cwd.clone());
    }

    let candidates_strs: Vec<String> = candidates.iter()
        .map(|p| p.display().to_string())
        .collect();
    let with_client_py: Vec<String> = candidates.iter()
        .filter(|p| p.join("client.py").exists())
        .map(|p| p.display().to_string())
        .collect();

    let root = project_root();
    let root_has = root.join("client.py").exists();

    // List a few key files we EXPECT to find in the project root so the user
    // can see if the bundle is incomplete.
    let expected = [
        "client.py", "auth.py", "mojang.py", "fabric.py", "mods.py", "jvm.py",
        "jdk.py", "branding/hud_mod/build.py",
    ];
    let present: Vec<String> = expected.iter()
        .filter(|f| root.join(f).exists())
        .map(|s| s.to_string())
        .collect();

    Diagnostics {
        launcher_version: env!("CARGO_PKG_VERSION").to_string(),
        exe_path,
        exe_dir,
        project_root: root.display().to_string(),
        project_root_has_client_py: root_has,
        python: Some(check_python()),
        cwd: std::env::current_dir()
            .map(|p| p.display().to_string())
            .unwrap_or_default(),
        candidates_checked: candidates_strs,
        candidates_with_client_py: with_client_py,
        resource_files_present: present,
    }
}

/// Result of probing for a usable Python interpreter.
#[derive(Serialize, Clone)]
pub struct PythonProbe {
    /// True if at least one of `python` / `python3` / `py` answered `--version`
    /// AND reported a major.minor >= 3.11. (3.11 is the minimum for several
    /// modern stdlib features client.py relies on.)
    pub ok: bool,
    /// Friendly description of what we found, e.g. "Python 3.12.4 via `py`".
    pub detail: String,
}

/// Look for the bundled embeddable Python distribution that the installer
/// drops into <root>/python/. Returns the absolute path to its python.exe
/// if present. On macOS/Linux we don't bundle Python (system python3 is
/// expected), so this only finds it on Windows installs.
fn bundled_python(root: &Path) -> Option<PathBuf> {
    let candidates = [
        root.join("python").join("python.exe"),
        root.join("python").join("bin").join("python3"),
        root.join("python").join("bin").join("python"),
    ];
    for c in candidates {
        if c.exists() {
            return Some(c);
        }
    }
    None
}

/// True if the candidate output looks like Windows' Microsoft Store Python
/// stub (`C:\Users\…\AppData\Local\Microsoft\WindowsApps\python.exe`).
/// The stub spawns successfully but prints a redirect-to-Store message and
/// exits 9009 — we have to recognise the message and skip it explicitly.
fn looks_like_store_stub(text: &str) -> bool {
    let lower = text.to_lowercase();
    lower.contains("python was not found")
        || lower.contains("microsoft store")
        || lower.contains("ms-windows-store:")
        || lower.contains("app execution alias")
}

/// Probe for a working Python interpreter. Returns ok=false (with a
/// human-readable `detail`) if nothing works — the JS layer renders a
/// clear "install Python" banner in that case.
#[tauri::command]
fn check_python() -> PythonProbe {
    // First try the bundled Python (only Windows installs ship one).
    let root = project_root();
    if let Some(bundled) = bundled_python(&root) {
        if let Ok(o) = std::process::Command::new(&bundled).arg("--version").output() {
            if o.status.success() {
                let text = format!(
                    "{}{}",
                    String::from_utf8_lossy(&o.stdout),
                    String::from_utf8_lossy(&o.stderr),
                );
                if let Some(v) = parse_python_version(&text) {
                    return PythonProbe {
                        ok: true,
                        detail: format!("bundled Python {} ({})", v, bundled.display()),
                    };
                }
            }
        }
    }

    // Fall back to PATH lookup. Windows ships a `py` launcher in System32 by
    // default; mac/Linux usually have `python3`.
    let candidates = ["py", "python3", "python"];
    for exe in candidates {
        // `python --version` writes to stderr on old Python, stdout on new.
        let Ok(o) = std::process::Command::new(exe).arg("--version").output() else {
            continue;
        };
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&o.stdout),
            String::from_utf8_lossy(&o.stderr),
        );
        // Microsoft Store stub spawns fine but isn't Python — recognise + skip.
        if looks_like_store_stub(&text) {
            continue;
        }
        if !o.status.success() {
            continue;
        }
        if let Some(v) = parse_python_version(&text) {
            // Parse major.minor
            let mut parts = v.split('.');
            let major: u32 = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
            let minor: u32 = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
            if major > 3 || (major == 3 && minor >= 11) {
                return PythonProbe {
                    ok: true,
                    detail: format!("Python {} via `{}`", v, exe),
                };
            }
        }
    }
    PythonProbe {
        ok: false,
        detail: "No Python 3.11+ found. The bundled Python from the installer is missing, \
                 and no system `python` / `python3` / `py` answered to --version.".into(),
    }
}

fn parse_python_version(text: &str) -> Option<String> {
    let start = text.find("Python ")?;
    let rest = &text[start + "Python ".len()..];
    let v: String = rest.chars()
        .take_while(|c| c.is_ascii_digit() || *c == '.')
        .collect();
    if v.is_empty() { None } else { Some(v) }
}

/// Public profile info exposed to the front-end after Microsoft sign-in.
///
/// Deliberately does NOT include `access_token` / `refresh_token` — those
/// are Minecraft Services credentials and should never leave the Rust/Python
/// process. The launcher's UI only needs to render the player's name + avatar;
/// the token gets read directly by `client.py launch` when it fires off Java.
#[derive(Serialize, Deserialize, Clone, Default)]
pub struct AccountInfo {
    pub username: Option<String>,
    pub uuid: Option<String>,
    /// "msa" for Microsoft-signed-in, "legacy" for offline. Anything else
    /// surfaces as the literal value so future schema additions don't break
    /// the front-end.
    pub user_type: Option<String>,
}

/// Read the cached account file written by `auth.py` after a successful
/// `client.py login`. Used by the UI to (a) detect on boot that the user is
/// already signed in from a previous session, and (b) show the resolved
/// Mojang username after a fresh sign-in.
#[tauri::command]
fn read_account() -> Result<Option<AccountInfo>, String> {
    let root = project_root();
    let account_file = root.join("game_dir").join("mc-client-account.json");
    if !account_file.exists() {
        return Ok(None);
    }
    let raw = std::fs::read_to_string(&account_file).map_err(|e| e.to_string())?;
    let json: serde_json::Value = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
    Ok(Some(AccountInfo {
        username:  json.get("username").and_then(|v| v.as_str()).map(String::from),
        uuid:      json.get("uuid").and_then(|v| v.as_str()).map(String::from),
        user_type: json.get("user_type").and_then(|v| v.as_str()).map(String::from),
    }))
}

/// Sign out: clear the Microsoft tokens and replace the cached account with
/// a deterministic offline account for the requested username. We invoke
/// `client.py logout` rather than rewriting the JSON directly so the same
/// auth.offline() helper that `setup` uses produces the file — keeps the
/// schema in one place.
#[tauri::command]
async fn logout_account(
    app: tauri::AppHandle,
    busy: State<'_, AppState>,
    username: String,
) -> Result<i32, String> {
    run_python(
        &app,
        vec!["logout".into(), "--username".into(), username],
        busy,
    )
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
