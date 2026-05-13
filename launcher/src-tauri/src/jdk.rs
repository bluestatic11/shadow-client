//! Find a usable Java interpreter on disk + auto-download from Adoptium when
//! nothing on the system is new enough. Rust port of `jdk.py`.

use anyhow::{anyhow, bail, Context, Result};
use std::path::{Path, PathBuf};

use crate::mojang;

/// Locations to probe for a working Java. Order matters: project-bundled
/// JDKs first, then system installs, then PATH lookup. Returns the highest-
/// major-version usable java[w].exe.
pub fn find_java(project_root: &Path) -> Option<PathBuf> {
    let bin = if cfg!(target_os = "windows") { "java.exe" } else { "java" };

    let mut candidates: Vec<PathBuf> = Vec::new();

    // Project-local bundled JDKs (jdk-21/, jdk-25/, etc.).
    if let Ok(entries) = std::fs::read_dir(project_root) {
        for e in entries.flatten() {
            let p = e.path();
            if p.is_dir()
                && p.file_name()
                    .and_then(|n| n.to_str())
                    .map(|n| n.starts_with("jdk-"))
                    .unwrap_or(false)
            {
                // Walk a couple of levels for nested layouts (jdk-21/jdk-21.0.x/bin).
                walk_for_java(&p, bin, &mut candidates, 3);
            }
        }
    }

    // JAVA_HOME / common system install paths.
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let p = PathBuf::from(java_home).join("bin").join(bin);
        if p.exists() { candidates.push(p); }
    }
    let system_locations: &[&str] = &[
        "C:/Program Files/Eclipse Adoptium/jdk-25",
        "C:/Program Files/Eclipse Adoptium/jdk-21",
        "C:/Program Files/Microsoft/jdk-21",
        "C:/Program Files/Java/jdk-21",
        "C:/Program Files/Java/jdk-25",
        "/usr/lib/jvm/java-21-openjdk",
        "/usr/lib/jvm/java-25-openjdk",
        "/opt/homebrew/opt/openjdk@21",
        "/opt/homebrew/opt/openjdk@25",
    ];
    for loc in system_locations {
        let p = PathBuf::from(loc).join("bin").join(bin);
        if p.exists() { candidates.push(p); }
    }

    // Bare command — let the OS resolve it from PATH.
    candidates.push(PathBuf::from(bin));

    // Probe each candidate with -version and pick highest major version.
    let mut best: Option<(u32, PathBuf)> = None;
    for c in candidates {
        if let Some(major) = java_major_version(&c) {
            if best.as_ref().map(|(b, _)| major > *b).unwrap_or(true) {
                best = Some((major, c));
            }
        }
    }
    best.map(|(_, p)| p)
}

fn walk_for_java(root: &Path, bin: &str, out: &mut Vec<PathBuf>, depth: u32) {
    if depth == 0 { return; }
    let Ok(entries) = std::fs::read_dir(root) else { return };
    for e in entries.flatten() {
        let p = e.path();
        if p.is_dir() && p.file_name().and_then(|n| n.to_str()) == Some("bin") {
            let exe = p.join(bin);
            if exe.is_file() { out.push(exe); }
        } else if p.is_dir() {
            walk_for_java(&p, bin, out, depth - 1);
        }
    }
}

/// Probe `<java> -version`, parse the major version, return None on failure.
///
/// Handles both Java naming schemes:
///   * Legacy (Java 5-8): version string is `1.X.Y` (e.g. `1.8.0_281`). The
///     real major version is the SECOND component — `1.8.0` is Java 8, not
///     Java 1. Treating the leading "1" as the major was the bug that
///     reported v0.3.2 users with Java 8 as "Java 1 is too old".
///   * Modern (Java 9+): version string is `X.Y.Z` or just `X` (e.g.
///     `21.0.6`, `25`). The first component IS the major.
pub fn java_major_version(java: &Path) -> Option<u32> {
    let out = std::process::Command::new(java).arg("-version").output().ok()?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    let idx = text.find("version")?;
    let rest = &text[idx + "version".len()..];
    let quoted = rest.split('"').nth(1)?;
    let parts: Vec<&str> = quoted
        .split(|c| c == '.' || c == '-' || c == '_' || c == '+')
        .collect();
    let first: u32 = parts.first()?.parse().ok()?;
    if first == 1 && parts.len() >= 2 {
        parts[1].parse().ok()
    } else {
        Some(first)
    }
}

/// Adoptium binary-download URL for a given major Java version. Hands back
/// the latest GA build for the user's OS + arch.
fn adoptium_url(major: u32) -> Result<String> {
    let (os_name, arch) = mojang::detect_os();
    let os_map = match os_name {
        "windows" => "windows",
        "osx"     => "mac",
        _         => "linux",
    };
    let arch_map = match arch {
        "x64"   => "x64",
        "arm64" => "aarch64",
        _       => "x86",
    };
    Ok(format!(
        "https://api.adoptium.net/v3/binary/latest/{major}/ga/{os_map}/{arch_map}/jdk/hotspot/normal/eclipse"
    ))
}

/// Download (or use the already-extracted copy of) a JDK at the given major
/// version. Extracts into `<project_root>/jdk-<major>/`.
///
/// Windows path is full: download .zip from Adoptium → extract via the zip
/// crate → find java.exe under the extracted layout.
///
/// Unix path is currently not implemented — we bail with a clear error and
/// the user gets the Adoptium URL to do it themselves. Adding tar.gz support
/// is a follow-up.
pub async fn download_jdk(
    client: &reqwest::Client,
    major: u32,
    into: &Path,
    progress: &dyn Fn(String),
) -> Result<PathBuf> {
    std::fs::create_dir_all(into)?;
    let bin = if cfg!(target_os = "windows") { "java.exe" } else { "java" };

    // Already extracted?
    let mut existing = Vec::new();
    walk_for_java(into, bin, &mut existing, 4);
    if let Some(found) = existing.into_iter().next() {
        if let Some(v) = java_major_version(&found) {
            if v >= major {
                return Ok(found);
            }
        }
    }

    if !cfg!(target_os = "windows") {
        bail!(
            "Automatic JDK download is currently Windows-only. \
             Install Java {major}+ from https://adoptium.net/temurin/releases/ \
             (pick 'JDK', not 'JRE') and reopen Shadow Client."
        );
    }

    let url = adoptium_url(major)?;
    progress(format!("Downloading JDK {major} from Adoptium (~200 MB)…"));
    let bytes = mojang::http_get_bytes(client, &url).await
        .with_context(|| format!("downloading {url}"))?;
    let archive = into.join(format!("jdk-{major}.zip"));
    std::fs::write(&archive, &bytes)?;

    progress(format!("Extracting JDK {major}…"));
    extract_zip(&archive, into)?;
    let _ = std::fs::remove_file(&archive);

    let mut found = Vec::new();
    walk_for_java(into, bin, &mut found, 4);
    found.into_iter().next()
        .ok_or_else(|| anyhow!("JDK extracted but couldn't find {bin} in {}", into.display()))
}

/// Extract a .zip into `dest_dir`, preserving the archive's directory
/// structure. Used by the Adoptium download path.
fn extract_zip(zip_path: &Path, dest_dir: &Path) -> Result<()> {
    let f = std::fs::File::open(zip_path)?;
    let mut archive = zip::ZipArchive::new(f)?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i)?;
        let Some(out_path_rel) = entry.enclosed_name() else { continue };
        let out_path = dest_dir.join(out_path_rel);
        if entry.is_dir() {
            std::fs::create_dir_all(&out_path)?;
        } else {
            if let Some(p) = out_path.parent() {
                std::fs::create_dir_all(p)?;
            }
            let mut out = std::fs::File::create(&out_path)?;
            std::io::copy(&mut entry, &mut out)?;
        }
    }
    Ok(())
}
