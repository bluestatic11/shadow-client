//! End-to-end setup + launch orchestration. Rust port of `cmd_setup` and
//! `cmd_launch` from `client.py`.

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

use crate::{auth::Account, fabric, jdk, jvm, mods, mojang, shadow_chat};

/// Per-profile install state stored under `installed.json`.
#[derive(Debug, Serialize, Deserialize, Default, Clone)]
pub struct ProfileState {
    pub mc_version: Option<String>,
    pub fabric_loader: Option<String>,
    pub version_id: Option<String>,
    pub vanilla_version_id: Option<String>,
    pub client_jar: Option<String>,
    #[serde(default)] pub installed_mods: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize, Default, Clone)]
pub struct State {
    #[serde(default)] pub profiles: std::collections::BTreeMap<String, ProfileState>,
    #[serde(default)] pub last_used: Option<String>,
}

pub fn load_state(state_file: &Path) -> State {
    let Ok(raw) = std::fs::read(state_file) else { return State::default() };
    let Ok(json): std::result::Result<serde_json::Value, _> = serde_json::from_slice(&raw) else {
        return State::default();
    };
    // Migration from the legacy single-profile schema.
    if json.get("mc_version").is_some() && json.get("profiles").is_none() {
        let legacy_name = json
            .get("mc_version")
            .and_then(|v| v.as_str())
            .unwrap_or("__legacy__")
            .to_string();
        let p: ProfileState = serde_json::from_value(json.clone()).unwrap_or_default();
        let mut profiles = std::collections::BTreeMap::new();
        profiles.insert(legacy_name.clone(), p);
        return State { profiles, last_used: Some(legacy_name) };
    }
    serde_json::from_value(json).unwrap_or_default()
}

pub fn save_state(state_file: &Path, state: &State) -> Result<()> {
    if let Some(parent) = state_file.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = state_file.with_extension("tmp");
    std::fs::write(&tmp, serde_json::to_string_pretty(state)?)?;
    std::fs::rename(&tmp, state_file)?;
    Ok(())
}

/// Resolve (profile_dir, shared_dir) for a given profile name.
/// Shared dir holds version JARs / libraries / assets (cacheable across versions).
/// Profile dir holds the per-version mods/, saves/, options.txt etc.
pub fn resolve_dirs(here: &Path, profile: Option<&str>) -> (PathBuf, PathBuf) {
    let shared = here.join("game_dir");
    match profile {
        Some(p) if !p.is_empty() => (shared.join("profiles").join(p), shared),
        _ => (shared.clone(), shared),
    }
}

/// Setup phase — downloads MC + libraries + assets + Fabric + mods. Emits
/// human-readable progress lines via the provided callback so the UI can
/// surface them in the status line + log view.
pub async fn setup(
    here: PathBuf,
    state_file: PathBuf,
    username: String,
    version_arg: String,
    profile_name: Option<String>,
    progress: impl Fn(String) + Send + Sync + Clone + 'static,
) -> Result<()> {
    let client = build_http_client()?;

    progress("Fetching Mojang version manifest…".into());
    let manifest = mojang::fetch_manifest(&client).await?;
    let entry = mojang::resolve_version(&manifest, &version_arg)?.clone();
    let mc_version = entry.id.clone();

    let profile = profile_name.unwrap_or_else(|| mc_version.clone());
    let (profile_dir, shared_dir) = resolve_dirs(&here, Some(&profile));
    progress(format!("Target MC version: {mc_version}  profile: {profile}"));
    std::fs::create_dir_all(&profile_dir)?;
    std::fs::create_dir_all(&shared_dir)?;

    progress(format!("Downloading version JSON for {mc_version}…"));
    let vanilla = mojang::fetch_version_json(&client, &entry, &shared_dir).await?;

    progress("Resolving latest Fabric loader…".into());
    let loader_version = fabric::latest_loader(&client, &mc_version).await?;
    progress(format!("Fabric loader {loader_version}"));
    let fabric_profile = fabric::fetch_profile(&client, &mc_version, &loader_version).await?;
    let merged = fabric::merge_into_vanilla(&vanilla, &fabric_profile)?;

    // Cache the merged version JSON so launch doesn't need to refetch Fabric.
    let merged_id = merged
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or(&mc_version)
        .to_string();
    let merged_path = shared_dir
        .join("versions")
        .join(&merged_id)
        .join(format!("{merged_id}.json"));
    if let Some(parent) = merged_path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&merged_path, serde_json::to_string_pretty(&merged)?)?;

    progress("Downloading libraries…".into());
    let (_classpath, _natives) =
        mojang::download_libraries(&client, &merged, &shared_dir, progress.clone()).await?;

    progress("Downloading client JAR…".into());
    let client_jar = mojang::download_client_jar(&client, &vanilla, &shared_dir).await?;

    progress("Downloading game assets (this is the long part)…".into());
    mojang::download_assets(&client, &vanilla, &shared_dir, progress.clone()).await?;

    progress("Installing performance mods…".into());
    let mods_dir = profile_dir.join("mods");
    let (installed_mods, _skipped) =
        mods::install_mods(&client, &mods_dir, &mc_version, progress.clone()).await?;

    // Seed options.txt
    let opts = profile_dir.join("options.txt");
    if !opts.exists() {
        std::fs::write(&opts, jvm::OPTIONS_TXT)?;
    }

    // Persist account file (offline) for the launch phase.
    let account_file = shared_dir.join("mc-client-account.json");
    if !account_file.exists() {
        Account::offline(&username).save(&account_file)?;
    }

    // Write profile state.
    let mut state = load_state(&state_file);
    let profile_state = ProfileState {
        mc_version: Some(mc_version),
        fabric_loader: Some(loader_version),
        version_id: Some(merged_id),
        vanilla_version_id: Some(vanilla.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string()),
        client_jar: Some(client_jar.display().to_string()),
        installed_mods,
    };
    state.profiles.insert(profile.clone(), profile_state);
    state.last_used = Some(profile);
    save_state(&state_file, &state)?;

    progress("Setup complete.".into());
    Ok(())
}

/// Launch phase — read the per-profile state, build the Java command line,
/// spawn Java, and stream its stdout/stderr back to the caller.
pub async fn launch(
    here: PathBuf,
    state_file: PathBuf,
    profile: Option<String>,
    heap_mb: u32,
    gc: String,
    username: Option<String>,
    progress: impl Fn(String) + Send + Sync + 'static,
    on_line: impl Fn(String, String) + Send + Sync + 'static,
) -> Result<i32> {
    let state = load_state(&state_file);
    let profile_name = profile
        .clone()
        .or(state.last_used.clone())
        .ok_or_else(|| anyhow!("No profile to launch — run setup first."))?;
    let p = state
        .profiles
        .get(&profile_name)
        .ok_or_else(|| anyhow!("Profile '{profile_name}' not installed — run setup first."))?
        .clone();
    let (profile_dir, shared_dir) = resolve_dirs(&here, Some(&profile_name));

    let version_id = p
        .version_id
        .as_deref()
        .ok_or_else(|| anyhow!("profile state missing version_id"))?;
    let version_json_path = shared_dir
        .join("versions").join(version_id).join(format!("{version_id}.json"));
    let version: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&version_json_path)
            .with_context(|| format!("reading {}", version_json_path.display()))?)?;

    // Rebuild classpath from on-disk libraries.
    let (os_name, arch) = mojang::detect_os();
    let mut classpath: Vec<PathBuf> = Vec::new();
    let libs = version.get("libraries").and_then(|v| v.as_array()).cloned().unwrap_or_default();
    for lib in libs {
        if let Some(rules) = lib.get("rules") {
            if !mojang::rule_allows(rules, os_name, arch) { continue; }
        }
        let Some(art) = lib.get("downloads").and_then(|d| d.get("artifact")) else { continue };
        let Some(path) = art.get("path").and_then(|v| v.as_str()) else { continue };
        // Skip native-only libs from classpath
        let name = lib.get("name").and_then(|v| v.as_str()).unwrap_or_default();
        if name.contains(&format!(":natives-{os_name}"))
            || name.ends_with(&format!("-natives-{os_name}"))
        { continue; }
        let jar = shared_dir.join("libraries").join(path);
        if jar.exists() { classpath.push(jar); }
    }
    let natives_dir = shared_dir.join("versions").join(version_id).join("natives");
    let client_jar = PathBuf::from(p.client_jar.as_deref().unwrap_or(""));

    // Account
    let account_file = shared_dir.join("mc-client-account.json");
    let mut acct = Account::load(&account_file).unwrap_or_else(|| {
        Account::offline(username.as_deref().unwrap_or("Player"))
    });
    if !account_file.exists() { let _ = acct.save(&account_file); }

    // v0.3.37: refresh the Microsoft token if it's older than 12 h. MS
    // access tokens nominally expire at 24 h; refreshing at the half-life
    // gives us comfortable headroom and stops the launcher from silently
    // breaking online play + chat a day after sign-in. On failure (no
    // network, refresh token revoked), fall back to the cached token —
    // better to try and fail visibly in Mojang's auth than to bail
    // without ever spawning Java.
    if auth::needs_refresh(&acct) {
        progress("Refreshing Microsoft sign-in (token is >12h old)…".into());
        // Pass `&progress` directly — Fn trait is implemented on
        // references, so the borrow-capturing closure pattern is
        // unnecessary and the borrow checker is happier without it
        // crossing the await boundary.
        match auth::refresh_account(&acct, &progress).await {
            Ok(fresh) => {
                let _ = fresh.save(&account_file);
                acct = fresh;
                progress("Microsoft sign-in refreshed.".into());
            }
            Err(e) => {
                progress(format!(
                    "MS token refresh failed (non-fatal, using cached token): {e:#}"
                ));
            }
        }
    }

    let jvm_extra = jvm::flags(heap_mb, &gc);

    let (mut all_args, main_class) = mojang::build_launch_args(
        &version,
        &acct.username, &acct.uuid, &acct.access_token, &acct.user_type,
        &profile_dir,
        &shared_dir.join("assets"),
        &natives_dir,
        &classpath,
        &client_jar,
        &jvm_extra,
    )?;

    // Pick Java. If nothing on disk is new enough, auto-download from
    // Adoptium into <here>/jdk-<major>/ so the user doesn't have to.
    let required_major = version
        .get("javaVersion").and_then(|v| v.get("majorVersion"))
        .and_then(|v| v.as_u64()).map(|n| n as u32).unwrap_or(21);

    let mut java = jdk::find_java(&here);
    let mut java_major = java.as_ref().and_then(|p| jdk::java_major_version(p)).unwrap_or(0);

    if java.is_none() || java_major < required_major {
        if java_major == 0 {
            progress("No Java on system — downloading JDK from Adoptium…".into());
        } else {
            progress(format!(
                "Installed Java {java_major} is older than the required Java {required_major} — \
                 downloading the right one from Adoptium…"
            ));
        }
        let client = build_http_client()?;
        let into = here.join(format!("jdk-{required_major}"));
        let progress_ref: &dyn Fn(String) = &progress;
        let downloaded = jdk::download_jdk(&client, required_major, &into, progress_ref).await?;
        progress(format!("JDK installed at {}", downloaded.display()));
        java_major = jdk::java_major_version(&downloaded).unwrap_or(required_major);
        java = Some(downloaded);
    }
    let java = java.ok_or_else(|| anyhow!(
        "No Java available. Install Java {required_major}+ from \
         https://adoptium.net/temurin/releases/ and reopen Shadow Client."
    ))?;
    if java_major < required_major {
        bail!(
            "Downloaded Java {java_major} but Minecraft {} needs Java {required_major}+. \
             That shouldn't happen — please report this.",
            p.mc_version.as_deref().unwrap_or("")
        );
    }
    all_args = jvm::filter_args_for_java(all_args, java_major);

    progress(format!("Java {java_major} at {}", java.display()));
    progress(format!("Main class: {main_class}"));
    progress(format!("User: {} ({})", acct.username, acct.user_type));
    progress(format!("Heap: {heap_mb}M  GC: {gc}"));

    // Dump the full command line to <here>/last-launch-cmd.log so that if
    // Java crashes (e.g. UnsatisfiedLinkError) we can diagnose it from the
    // recorded invocation — `progress(...)` output is gone the moment the
    // launcher window is reopened, but this file persists. Also writes the
    // natives_dir on its own line + a directory listing so we can confirm
    // lwjgl.dll & friends actually got extracted before launch.
    let log_path = here.join("last-launch-cmd.log");
    let _ = write_launch_log(
        &log_path, &java, java_major, &all_args, &profile_dir, &natives_dir,
    );
    progress(format!("Launch command logged → {}", log_path.display()));
    progress("Starting Minecraft — first boot can take 30-60s…".into());

    // v0.3.35: top-up the chat mod (and any future direct-URL mod) on
    // every launch. Profiles set up before a particular mod was added
    // to DIRECT_URL_MODS never received it via the initial setup pass,
    // so this fills the gap. Also self-heals if the user deleted the
    // jar manually OR if a new mod version is published (stale older
    // jars get removed before the new one is installed).
    // Fast path on the common case (everything already present) does
    // zero network I/O — just a few file existence checks.
    if let Some(mc_ver) = p.mc_version.as_deref() {
        let mods_dir = profile_dir.join("mods");
        let client = build_http_client()?;
        // Pass &progress directly — Fn is implemented on references, so
        // no wrapper closure is needed. Avoids a borrow-checker fight
        // with the lifetime of the borrow across the await boundary.
        match mods::ensure_direct_mods_present(&client, &mods_dir, mc_ver, &progress).await {
            Ok(added) if !added.is_empty() => {
                progress(format!("Topped up {} direct-URL mod(s)", added.len()));
            }
            Ok(_) => {}
            Err(e) => progress(format!("Mod top-up failed (non-fatal): {e:#}")),
        }
    }

    // Hand the Shadow Chat mod its auth + relay info via a small JSON file
    // dropped into profile_dir (== Java's CWD). Best-effort: if anything
    // here fails, log it and keep going — chat-mod is non-essential.
    match shadow_chat::write_auth_file(&profile_dir, &acct) {
        Ok(()) => progress(shadow_chat::status_line(&acct)),
        Err(e) => progress(format!("Shadow Chat: disabled (couldn't write auth file: {e:#})")),
    }

    // Spawn Java. We capture stdout/stderr line-by-line via an mpsc channel
    // so the UI sees output live instead of waiting for process exit.
    use std::process::Stdio;
    use std::io::{BufRead, BufReader};
    let mut cmd = std::process::Command::new(&java);
    cmd.args(&all_args).current_dir(&profile_dir)
        .stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = cmd.spawn().with_context(|| {
        format!("spawning {}", java.display())
    })?;
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();

    let (tx, rx) = std::sync::mpsc::channel::<(String, String)>();
    let tx_out = tx.clone();
    let t_out = std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = tx_out.send(("stdout".into(), line));
        }
    });
    let t_err = std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = tx.send(("stderr".into(), line));
        }
    });

    // Drain the channel on this thread, calling on_line for each line. When
    // both readers drop their sender ends the channel closes and recv()
    // returns Err — that's our signal to wait for Java to fully exit.
    for (kind, line) in rx { on_line(kind, line); }

    let status = child.wait()?;
    let _ = t_out.join();
    let _ = t_err.join();
    Ok(status.code().unwrap_or(-1))
}

fn build_http_client() -> Result<reqwest::Client> {
    let client = reqwest::Client::builder()
        .user_agent(mojang::UA)
        .timeout(std::time::Duration::from_secs(120))
        .build()?;
    Ok(client)
}

/// Write a human-readable record of the Java command line + natives state
/// to `last-launch-cmd.log`. Errors are best-effort; never fail the launch
/// because the log couldn't be written.
fn write_launch_log(
    log_path: &Path,
    java: &Path,
    java_major: u32,
    args: &[String],
    cwd: &Path,
    natives_dir: &Path,
) -> Result<()> {
    use std::fmt::Write;
    let mut s = String::with_capacity(4096);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    writeln!(s, "# Shadow Client launch log").ok();
    writeln!(s, "# unix_ts={now}").ok();
    writeln!(s, "# java={}", java.display()).ok();
    writeln!(s, "# java_major={java_major}").ok();
    writeln!(s, "# cwd={}", cwd.display()).ok();
    writeln!(s, "# natives_dir={}", natives_dir.display()).ok();
    writeln!(s).ok();
    writeln!(s, "# === args (one per line) ===").ok();
    for a in args {
        writeln!(s, "{a}").ok();
    }
    writeln!(s).ok();
    writeln!(s, "# === natives directory contents ===").ok();
    match std::fs::read_dir(natives_dir) {
        Ok(rd) => {
            let mut entries: Vec<(String, u64)> = rd
                .filter_map(|e| e.ok())
                .map(|e| {
                    let name = e.file_name().to_string_lossy().to_string();
                    let size = e.metadata().map(|m| m.len()).unwrap_or(0);
                    (name, size)
                })
                .collect();
            entries.sort_by(|a, b| a.0.cmp(&b.0));
            if entries.is_empty() {
                writeln!(s, "(empty)").ok();
            } else {
                for (name, size) in entries {
                    writeln!(s, "{size:>10}  {name}").ok();
                }
            }
        }
        Err(e) => {
            writeln!(s, "(unreadable: {e})").ok();
        }
    }
    if let Some(parent) = log_path.parent() {
        std::fs::create_dir_all(parent).ok();
    }
    std::fs::write(log_path, s)?;
    Ok(())
}
