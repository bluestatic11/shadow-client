//! Find a usable Java interpreter on disk. Rust port of the discovery half
//! of `jdk.py` — the auto-download-from-Adoptium half is deferred (we error
//! out with a clear "install Java" message instead).

use std::path::{Path, PathBuf};

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

/// Probe `<java> -version`, parse the major version, return 0 on any failure.
pub fn java_major_version(java: &Path) -> Option<u32> {
    let out = std::process::Command::new(java).arg("-version").output().ok()?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr),
    );
    // `java -version` output: ` openjdk version "21.0.6" 2024-...`
    let idx = text.find("version")?;
    let rest = &text[idx + "version".len()..];
    let quoted = rest.split('"').nth(1)?;
    let major = quoted.split(|c| c == '.' || c == '-').next()?;
    major.parse().ok()
}
