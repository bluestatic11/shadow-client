//! Mojang version manifest + library/asset downloads + Java launch args.
//!
//! This module is the Rust port of the original `mojang.py`. All upstream URLs
//! and JSON shapes are the same — we just talk to Mojang's official CDN via
//! reqwest instead of urllib.

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

pub const VERSION_MANIFEST: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
pub const RESOURCE_BASE: &str = "https://resources.download.minecraft.net";
pub const UA: &str = "ShadowClient/1.0 (native rust launcher)";
pub const LAUNCHER_BRAND: &str = "Shadow Client";
pub const LAUNCHER_VERSION: &str = "1.0";

/// (os_name, arch) in Mojang's naming convention. Mojang uses "osx" for macOS,
/// "x64" / "arm64" for arch.
pub fn detect_os() -> (&'static str, &'static str) {
    let os_name = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    };
    let arch = if cfg!(target_arch = "x86_64") {
        "x64"
    } else if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "x86"
    };
    (os_name, arch)
}

#[derive(Debug, Deserialize, Clone)]
pub struct ManifestLatest {
    pub release: String,
    #[serde(default)]
    pub snapshot: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct VersionEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct Manifest {
    pub latest: ManifestLatest,
    pub versions: Vec<VersionEntry>,
}

/// HTTP GET helper. Retries up to 3× on transient errors. Returns bytes; the
/// caller deserialises if they want JSON.
pub async fn http_get_bytes(client: &reqwest::Client, url: &str) -> Result<bytes::Bytes> {
    let mut last_err: Option<anyhow::Error> = None;
    for attempt in 0..3 {
        let res = client
            .get(url)
            .header("User-Agent", UA)
            .send()
            .await;
        match res {
            Ok(r) => match r.error_for_status() {
                Ok(r) => match r.bytes().await {
                    Ok(b) => return Ok(b),
                    Err(e) => last_err = Some(e.into()),
                },
                Err(e) => last_err = Some(e.into()),
            },
            Err(e) => last_err = Some(e.into()),
        }
        // Quick exponential backoff (50ms, 250ms, …) before retry
        tokio::time::sleep(std::time::Duration::from_millis(50 << (attempt * 2))).await;
    }
    Err(last_err.unwrap_or_else(|| anyhow!("HTTP GET {url} failed without an error")))
}

pub async fn http_get_json<T: for<'de> Deserialize<'de>>(
    client: &reqwest::Client,
    url: &str,
) -> Result<T> {
    let bytes = http_get_bytes(client, url).await?;
    serde_json::from_slice(&bytes)
        .with_context(|| format!("decoding JSON from {url}"))
}

/// SHA-1 hex of a file's contents.
fn sha1_file(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut hasher = Sha1::new();
    let mut buf = [0u8; 1 << 16];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 { break; }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// Download a file to disk with optional sha1 verification. Skips if the file
/// already exists and matches the expected hash.
pub async fn download_file(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    expected_sha1: Option<&str>,
) -> Result<()> {
    // Cache hit?
    if dest.exists() {
        if let Some(want) = expected_sha1 {
            if let Ok(have) = sha1_file(dest) {
                if have.eq_ignore_ascii_case(want) {
                    return Ok(());
                }
            }
        } else {
            // No sha1 to check — assume the cached file is fine (saves a re-download).
            return Ok(());
        }
    }
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("creating {}", parent.display()))?;
    }
    let bytes = http_get_bytes(client, url).await?;
    if let Some(want) = expected_sha1 {
        let mut hasher = Sha1::new();
        hasher.update(&bytes);
        let got = hex::encode(hasher.finalize());
        if !got.eq_ignore_ascii_case(want) {
            bail!("sha1 mismatch for {url}: got {got}, expected {want}");
        }
    }
    // Atomic write: write to .part then rename so a crash mid-download can't
    // leave a half-written file on disk.
    let tmp = dest.with_extension(format!(
        "{}.part",
        dest.extension().and_then(|s| s.to_str()).unwrap_or("")
    ));
    std::fs::write(&tmp, &bytes)
        .with_context(|| format!("writing {}", tmp.display()))?;
    std::fs::rename(&tmp, dest)
        .with_context(|| format!("renaming {} -> {}", tmp.display(), dest.display()))?;
    Ok(())
}

/// Fetch the global Mojang version manifest.
pub async fn fetch_manifest(client: &reqwest::Client) -> Result<Manifest> {
    http_get_json(client, VERSION_MANIFEST).await
}

pub fn resolve_version<'a>(manifest: &'a Manifest, version_id: &str) -> Result<&'a VersionEntry> {
    let target = if version_id.is_empty() || version_id == "latest" {
        manifest.latest.release.as_str()
    } else {
        version_id
    };
    manifest
        .versions
        .iter()
        .find(|v| v.id == target)
        .ok_or_else(|| anyhow!("Minecraft version {target} not in Mojang manifest"))
}

/// Download (or load cached) the version JSON for a given manifest entry.
pub async fn fetch_version_json(
    client: &reqwest::Client,
    entry: &VersionEntry,
    cache_dir: &Path,
) -> Result<serde_json::Value> {
    let dest = cache_dir
        .join("versions")
        .join(&entry.id)
        .join(format!("{}.json", entry.id));
    download_file(client, &entry.url, &dest, entry.sha1.as_deref()).await?;
    let raw = std::fs::read(&dest)
        .with_context(|| format!("reading {}", dest.display()))?;
    Ok(serde_json::from_slice(&raw)?)
}

/// Convert "group:artifact:version[:classifier]" to a Maven relative path.
pub fn maven_to_path(coord: &str) -> Result<String> {
    let parts: Vec<&str> = coord.split(':').collect();
    if parts.len() < 3 {
        bail!("invalid maven coord: {coord}");
    }
    let group = parts[0].replace('.', "/");
    let artifact = parts[1];
    let version = parts[2];
    let classifier = if parts.len() >= 4 {
        format!("-{}", parts[3])
    } else {
        String::new()
    };
    Ok(format!(
        "{group}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"
    ))
}

/// Apply Mojang's allow/disallow rule evaluation. Rules can gate on OS, arch,
/// and feature flags. We never enable feature flags (no --demo / quickPlay).
pub fn rule_allows(
    rules: &serde_json::Value,
    os_name: &str,
    arch: &str,
) -> bool {
    let Some(rules) = rules.as_array() else {
        return true;
    };
    let mut allowed = false;
    for rule in rules {
        let action = rule.get("action").and_then(|v| v.as_str()).unwrap_or("allow");
        let mut matched = true;
        if let Some(os_obj) = rule.get("os").and_then(|v| v.as_object()) {
            if let Some(name) = os_obj.get("name").and_then(|v| v.as_str()) {
                if name != os_name { matched = false; }
            }
            if matched {
                if let Some(rule_arch) = os_obj.get("arch").and_then(|v| v.as_str()) {
                    if rule_arch != arch { matched = false; }
                }
            }
        }
        if let Some(feats) = rule.get("features").and_then(|v| v.as_object()) {
            for (_feat, want) in feats {
                // We never enable any feature flags, so any positive feature
                // rule (want=true) doesn't match for us, and any negative
                // (want=false) does.
                let want = want.as_bool().unwrap_or(false);
                if want { matched = false; break; }
            }
        }
        if matched {
            allowed = action == "allow";
        }
    }
    allowed
}

/// One library download task.
#[derive(Debug, Clone)]
pub struct LibTask {
    pub url: String,
    pub dest: PathBuf,
    pub sha1: Option<String>,
    pub is_native: bool,
}

/// Collect every download task for a library entry, respecting OS rules.
fn library_downloads(
    lib: &serde_json::Value,
    os_name: &str,
    arch: &str,
    lib_root: &Path,
) -> Vec<LibTask> {
    let mut out = Vec::new();
    if let Some(rules) = lib.get("rules") {
        if !rule_allows(rules, os_name, arch) {
            return out;
        }
    }
    let downloads = lib.get("downloads");
    if let Some(art) = downloads.and_then(|d| d.get("artifact")) {
        let url = art.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string();
        let path = art.get("path").and_then(|v| v.as_str()).unwrap_or_default().to_string();
        let sha1 = art.get("sha1").and_then(|v| v.as_str()).map(String::from);
        // Modern (1.19+) natives: artifact name has ":natives-<os>" suffix.
        let name = lib.get("name").and_then(|v| v.as_str()).unwrap_or_default();
        let is_native = name.contains(&format!(":natives-{os_name}"))
            || name.ends_with(&format!("-natives-{os_name}"));
        out.push(LibTask {
            url,
            dest: lib_root.join(&path),
            sha1,
            is_native,
        });
    }
    // Legacy natives (pre-1.19): "natives" map of os→classifier.
    if let Some(natives) = lib.get("natives").and_then(|v| v.as_object()) {
        if let Some(classifier) = natives.get(os_name).and_then(|v| v.as_str()) {
            let classifier = classifier.replace("${arch}", if arch == "x64" { "64" } else { "32" });
            if let Some(cls) = downloads
                .and_then(|d| d.get("classifiers"))
                .and_then(|c| c.get(&classifier))
            {
                let url = cls.get("url").and_then(|v| v.as_str()).unwrap_or_default().to_string();
                let path = cls.get("path").and_then(|v| v.as_str()).unwrap_or_default().to_string();
                let sha1 = cls.get("sha1").and_then(|v| v.as_str()).map(String::from);
                out.push(LibTask {
                    url,
                    dest: lib_root.join(&path),
                    sha1,
                    is_native: true,
                });
            }
        }
    }
    out
}

/// Download every library + extract its natives. Returns the classpath
/// (non-native jars only) and the resolved natives dir.
pub async fn download_libraries(
    client: &reqwest::Client,
    version: &serde_json::Value,
    root: &Path,
    progress: impl Fn(String) + Send + Sync + 'static,
) -> Result<(Vec<PathBuf>, PathBuf)> {
    let (os_name, arch) = detect_os();
    let lib_root = root.join("libraries");
    let version_id = version
        .get("id")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("version JSON missing 'id'"))?;
    let natives_dir = root.join("versions").join(version_id).join("natives");
    std::fs::create_dir_all(&natives_dir)?;

    let libs = version
        .get("libraries")
        .and_then(|v| v.as_array())
        .ok_or_else(|| anyhow!("version JSON missing 'libraries' array"))?;

    let mut tasks: Vec<LibTask> = Vec::new();
    let mut classpath: Vec<PathBuf> = Vec::new();
    for lib in libs {
        for t in library_downloads(lib, os_name, arch, &lib_root) {
            if !t.is_native {
                classpath.push(t.dest.clone());
            }
            tasks.push(t);
        }
    }

    let total = tasks.len();
    progress(format!("downloading {total} libraries…"));

    // Parallel downloads — 16-at-a-time matches the Python pool size.
    // Progress messages come from the awaiter loop, not from inside the
    // futures, so we don't need to share the progress closure across threads.
    use futures_util::stream::{FuturesUnordered, StreamExt};
    let mut pending: FuturesUnordered<_> = tasks
        .into_iter()
        .map(|t| {
            let client = client.clone();
            let natives_dir = natives_dir.clone();
            async move {
                download_file(&client, &t.url, &t.dest, t.sha1.as_deref()).await?;
                if t.is_native {
                    extract_native_jar(&t.dest, &natives_dir)?;
                }
                anyhow::Ok(())
            }
        })
        .collect();

    let mut done = 0usize;
    while let Some(r) = pending.next().await {
        r?;
        done += 1;
        if done % 8 == 0 || done == total {
            progress(format!("  libraries {done}/{total}"));
        }
    }

    Ok((classpath, natives_dir))
}

/// Extract DLL / .so / .dylib from a native .jar (which is just a zip) into
/// the version's natives/ directory.
fn extract_native_jar(jar: &Path, natives_dir: &Path) -> Result<()> {
    let f = std::fs::File::open(jar)
        .with_context(|| format!("opening {}", jar.display()))?;
    let mut zip = zip::ZipArchive::new(f)
        .with_context(|| format!("reading zip {}", jar.display()))?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let name = entry.name().to_string();
        if name.ends_with('/') || name.contains("META-INF") {
            continue;
        }
        let lower = name.to_lowercase();
        if !(lower.ends_with(".dll")
            || lower.ends_with(".so")
            || lower.ends_with(".dylib")
            || lower.ends_with(".jnilib"))
        {
            continue;
        }
        let basename = Path::new(&name)
            .file_name()
            .map(|s| s.to_owned())
            .ok_or_else(|| anyhow!("no basename in {name}"))?;
        let out_path = natives_dir.join(&basename);
        let mut out = std::fs::File::create(&out_path)?;
        std::io::copy(&mut entry, &mut out)?;
    }
    Ok(())
}

pub async fn download_client_jar(
    client: &reqwest::Client,
    version: &serde_json::Value,
    root: &Path,
) -> Result<PathBuf> {
    let dl = version
        .get("downloads")
        .and_then(|d| d.get("client"))
        .ok_or_else(|| anyhow!("version JSON missing downloads.client"))?;
    let url = dl.get("url").and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("downloads.client.url missing"))?;
    let sha1 = dl.get("sha1").and_then(|v| v.as_str());
    let id = version.get("id").and_then(|v| v.as_str()).unwrap_or("unknown");
    let dest = root.join("versions").join(id).join(format!("{id}.jar"));
    download_file(client, url, &dest, sha1).await?;
    Ok(dest)
}

pub async fn download_assets(
    client: &reqwest::Client,
    version: &serde_json::Value,
    root: &Path,
    progress: impl Fn(String) + Send + Sync + 'static,
) -> Result<PathBuf> {
    let assets_root = root.join("assets");
    let ai = version
        .get("assetIndex")
        .ok_or_else(|| anyhow!("version JSON missing assetIndex"))?;
    let ai_id = ai.get("id").and_then(|v| v.as_str()).unwrap_or("legacy");
    let ai_url = ai.get("url").and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("assetIndex.url missing"))?;
    let ai_sha1 = ai.get("sha1").and_then(|v| v.as_str());

    let index_path = assets_root.join("indexes").join(format!("{ai_id}.json"));
    download_file(client, ai_url, &index_path, ai_sha1).await?;
    let raw = std::fs::read(&index_path)?;
    let index: serde_json::Value = serde_json::from_slice(&raw)?;

    let objects = index
        .get("objects")
        .and_then(|v| v.as_object())
        .ok_or_else(|| anyhow!("asset index missing 'objects'"))?;

    let mut tasks: Vec<(String, PathBuf, String)> = Vec::with_capacity(objects.len());
    for (_name, obj) in objects {
        let h = obj.get("hash").and_then(|v| v.as_str()).unwrap_or_default();
        if h.len() < 2 { continue; }
        let rel = format!("{}/{}", &h[..2], h);
        let url = format!("{RESOURCE_BASE}/{rel}");
        let dest = assets_root.join("objects").join(&rel);
        tasks.push((url, dest, h.to_string()));
    }
    let total = tasks.len();
    progress(format!("downloading {total} assets…"));

    use futures_util::stream::{FuturesUnordered, StreamExt};
    let semaphore = std::sync::Arc::new(tokio::sync::Semaphore::new(32));

    let mut pending: FuturesUnordered<_> = tasks
        .into_iter()
        .map(|(url, dest, sha1)| {
            let client = client.clone();
            let sem = semaphore.clone();
            async move {
                let _permit = sem.acquire_owned().await.unwrap();
                download_file(&client, &url, &dest, Some(&sha1)).await?;
                anyhow::Ok(())
            }
        })
        .collect();
    let mut done = 0usize;
    while let Some(r) = pending.next().await {
        r?;
        done += 1;
        if done % 100 == 0 || done == total {
            progress(format!("  assets {done}/{total}"));
        }
    }

    Ok(assets_root)
}

/// Build the Java command-line for a given version + account + options.
/// Returns (full_args, main_class). full_args = jvm_args + main_class +
/// game_args; ready to hand to `Command::new(java).args(full_args)`.
#[allow(clippy::too_many_arguments)]
pub fn build_launch_args(
    version: &serde_json::Value,
    username: &str,
    uuid: &str,
    access_token: &str,
    user_type: &str,
    game_dir: &Path,
    assets_dir: &Path,
    natives_dir: &Path,
    classpath: &[PathBuf],
    client_jar: &Path,
    jvm_extra: &[String],
) -> Result<(Vec<String>, String)> {
    let (os_name, arch) = detect_os();
    let cp_sep = if cfg!(target_os = "windows") { ";" } else { ":" };
    let mut cp_parts: Vec<String> = classpath
        .iter()
        .map(|p| p.display().to_string())
        .collect();
    cp_parts.push(client_jar.display().to_string());
    let cp = cp_parts.join(cp_sep);
    let asset_index = version
        .get("assetIndex")
        .and_then(|v| v.get("id"))
        .and_then(|v| v.as_str())
        .unwrap_or("legacy");
    let version_id = version.get("id").and_then(|v| v.as_str()).unwrap_or("unknown");
    let version_type = version.get("type").and_then(|v| v.as_str()).unwrap_or("release");

    let mut tokens: HashMap<&str, String> = HashMap::new();
    tokens.insert("auth_player_name", username.to_string());
    tokens.insert("version_name", version_id.to_string());
    tokens.insert("game_directory", game_dir.display().to_string());
    tokens.insert("assets_root", assets_dir.display().to_string());
    tokens.insert("assets_index_name", asset_index.to_string());
    tokens.insert("auth_uuid", uuid.to_string());
    tokens.insert("auth_access_token", access_token.to_string());
    tokens.insert("clientid", LAUNCHER_BRAND.to_string());
    tokens.insert("auth_xuid", "0".to_string());
    tokens.insert("user_type", user_type.to_string());
    tokens.insert("version_type", version_type.to_string());
    tokens.insert("user_properties", "{}".to_string());
    tokens.insert("natives_directory", natives_dir.display().to_string());
    tokens.insert("launcher_name", LAUNCHER_BRAND.to_string());
    tokens.insert("launcher_version", LAUNCHER_VERSION.to_string());
    tokens.insert("classpath", cp.clone());
    tokens.insert(
        "game_assets",
        assets_dir.join("virtual").join("legacy").display().to_string(),
    );
    tokens.insert("auth_session", access_token.to_string());

    fn subst(s: &str, tokens: &HashMap<&str, String>) -> String {
        let mut out = String::with_capacity(s.len());
        let mut chars = s.chars().peekable();
        while let Some(c) = chars.next() {
            if c == '$' && chars.peek() == Some(&'{') {
                chars.next(); // consume '{'
                let mut name = String::new();
                while let Some(&n) = chars.peek() {
                    if n == '}' { chars.next(); break; }
                    name.push(n);
                    chars.next();
                }
                if let Some(v) = tokens.get(name.as_str()) {
                    out.push_str(v);
                } else {
                    out.push_str("${");
                    out.push_str(&name);
                    out.push('}');
                }
            } else {
                out.push(c);
            }
        }
        out
    }

    fn flatten(
        seq: &serde_json::Value,
        tokens: &HashMap<&str, String>,
        os_name: &str,
        arch: &str,
    ) -> Vec<String> {
        let mut out = Vec::new();
        let Some(arr) = seq.as_array() else { return out };
        for item in arr {
            if let Some(s) = item.as_str() {
                out.push(subst(s, tokens));
            } else if let Some(obj) = item.as_object() {
                let rules = obj.get("rules").cloned().unwrap_or(serde_json::Value::Null);
                if !rules.is_null() && !rule_allows(&rules, os_name, arch) {
                    continue;
                }
                if let Some(v) = obj.get("value") {
                    if let Some(s) = v.as_str() {
                        out.push(subst(s, tokens));
                    } else if let Some(a) = v.as_array() {
                        for x in a {
                            if let Some(s) = x.as_str() {
                                out.push(subst(s, tokens));
                            }
                        }
                    }
                }
            }
        }
        out
    }

    let main_class = version
        .get("mainClass")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("version JSON missing mainClass"))?
        .to_string();

    let (mut jvm_args, game_args) = if let Some(args) = version.get("arguments") {
        let jvm = flatten(args.get("jvm").unwrap_or(&serde_json::Value::Null), &tokens, os_name, arch);
        let game = flatten(args.get("game").unwrap_or(&serde_json::Value::Null), &tokens, os_name, arch);
        (jvm, game)
    } else {
        // Legacy pre-1.13 format.
        let jvm = vec![
            format!("-Djava.library.path={}", natives_dir.display()),
            format!("-Dminecraft.launcher.brand={LAUNCHER_BRAND}"),
            format!("-Dminecraft.launcher.version={LAUNCHER_VERSION}"),
            "-cp".to_string(),
            cp.clone(),
        ];
        let game_str = version.get("minecraftArguments").and_then(|v| v.as_str()).unwrap_or("");
        let game = game_str.split_whitespace().map(|t| subst(t, &tokens)).collect();
        (jvm, game)
    };
    jvm_args.extend(jvm_extra.iter().cloned());

    let mut out = jvm_args;
    out.push(main_class.clone());
    out.extend(game_args);
    Ok((out, main_class))
}

// Tiny hex helper — avoids pulling the `hex` crate.
mod hex {
    pub fn encode<T: AsRef<[u8]>>(data: T) -> String {
        let mut s = String::with_capacity(data.as_ref().len() * 2);
        for b in data.as_ref() {
            s.push_str(&format!("{b:02x}"));
        }
        s
    }
}
