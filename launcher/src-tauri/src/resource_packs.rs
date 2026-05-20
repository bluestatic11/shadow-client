//! Install a Minecraft resource-pack .zip into the active profile's
//! `resourcepacks/` directory.
//!
//! Wired to drag-drop in the front-end: dropping a .zip anywhere on
//! the launcher window calls into `install_pack` here. The pack still
//! has to be ENABLED inside MC's own Options → Resource Packs menu —
//! we deliberately don't touch options.txt because the user's existing
//! pack ordering shouldn't be clobbered by a copy-in.

use anyhow::{anyhow, Context, Result};
use serde::Serialize;
use std::path::{Path, PathBuf};

/// Hard cap on what we'll copy. Vanilla resource packs are typically
/// 1-100 MB; this catches accidental "I dropped my Minecraft modpack
/// zip" mistakes before they exhaust disk.
const MAX_PACK_BYTES: u64 = 512 * 1024 * 1024;

#[derive(Serialize, Clone, Debug)]
pub struct InstalledPack {
    pub filename: String,
    pub bytes: u64,
    /// Absolute path where it landed — handy for the front-end status.
    pub dest_path: String,
}

/// Copy a resource-pack .zip into `<profile_dir>/resourcepacks/`. Returns
/// the destination filename + size. Errors:
///   - source missing / not a file
///   - source not a .zip
///   - source larger than MAX_PACK_BYTES
///   - destination dir creation failed
///   - IO error during copy
pub fn install_pack(profile_dir: &Path, src: &Path) -> Result<InstalledPack> {
    if !src.is_file() {
        return Err(anyhow!("dropped item is not a file: {}", src.display()));
    }
    let ext = src.extension().and_then(|s| s.to_str()).unwrap_or("");
    if !ext.eq_ignore_ascii_case("zip") {
        return Err(anyhow!("only .zip resource packs are accepted (got .{ext})"));
    }
    let meta = std::fs::metadata(src).context("reading source metadata")?;
    let size = meta.len();
    if size > MAX_PACK_BYTES {
        return Err(anyhow!(
            "file is {} MB; resource packs should stay under {} MB \
             (was that maybe a modpack?)",
            size / (1024 * 1024),
            MAX_PACK_BYTES / (1024 * 1024)
        ));
    }
    let filename = src.file_name()
        .and_then(|s| s.to_str())
        .ok_or_else(|| anyhow!("source path has no filename"))?
        .to_string();
    let packs_dir = profile_dir.join("resourcepacks");
    std::fs::create_dir_all(&packs_dir)
        .with_context(|| format!("creating {}", packs_dir.display()))?;
    let dest = packs_dir.join(&filename);
    // Copy via a temp path + rename so a partial copy can't leave a
    // half-written zip that MC would later fail to parse.
    let tmp = dest.with_extension("zip.partial");
    std::fs::copy(src, &tmp)
        .with_context(|| format!("copying to {}", tmp.display()))?;
    // Best-effort: if a same-named pack already exists, overwrite.
    if dest.exists() {
        let _ = std::fs::remove_file(&dest);
    }
    std::fs::rename(&tmp, &dest)
        .with_context(|| format!("renaming {} → {}", tmp.display(), dest.display()))?;
    Ok(InstalledPack {
        filename,
        bytes: size,
        dest_path: dest.to_string_lossy().to_string(),
    })
}

/// List currently-installed packs in the profile's resourcepacks/ dir.
/// Returns (name, bytes) per .zip file, sorted by mtime newest-first
/// so the UI can show the most-recently-dropped at the top.
pub fn list_packs(profile_dir: &Path) -> Vec<InstalledPack> {
    let dir = profile_dir.join("resourcepacks");
    let Ok(read) = std::fs::read_dir(&dir) else { return Vec::new(); };
    let mut out: Vec<(InstalledPack, std::time::SystemTime)> = Vec::new();
    for entry in read.flatten() {
        let path = entry.path();
        if !path.is_file() { continue; }
        let ext = path.extension().and_then(|s| s.to_str()).unwrap_or("");
        if !ext.eq_ignore_ascii_case("zip") { continue; }
        let meta = match std::fs::metadata(&path) { Ok(m) => m, Err(_) => continue };
        let mtime = meta.modified().unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        let filename = path.file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_string();
        out.push((
            InstalledPack {
                filename,
                bytes: meta.len(),
                dest_path: path.to_string_lossy().to_string(),
            },
            mtime,
        ));
    }
    out.sort_by(|a, b| b.1.cmp(&a.1));
    out.into_iter().map(|(p, _)| p).collect()
}

/// Remove a resource pack by filename. Returns true if a file was
/// actually deleted, false otherwise — caller decides whether the
/// no-op is an error.
pub fn remove_pack(profile_dir: &Path, filename: &str) -> Result<bool> {
    // Defensive: reject paths that try to escape the resourcepacks dir
    // via "../foo" or absolute components. Only accept bare filenames.
    if filename.contains('/') || filename.contains('\\') || filename.contains("..") {
        return Err(anyhow!("filename must be a bare name with no path components"));
    }
    let dest: PathBuf = profile_dir.join("resourcepacks").join(filename);
    if !dest.is_file() {
        return Ok(false);
    }
    std::fs::remove_file(&dest)
        .with_context(|| format!("removing {}", dest.display()))?;
    Ok(true)
}
