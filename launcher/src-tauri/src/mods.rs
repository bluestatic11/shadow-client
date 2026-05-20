//! Modrinth performance-mod installer. Rust port of `mods.py`.

use anyhow::Result;
use std::path::Path;

use crate::mojang::{self, download_file};
use crate::shadow_chat;

pub const MODRINTH: &str = "https://api.modrinth.com/v2";

/// Direct-URL mods that aren't on Modrinth. (slug, filename, url, mc_versions, critical).
/// `mc_versions` is the set of MC versions this jar supports — entries
/// scoped to a version not in this list are skipped silently the same
/// way the Modrinth installer skips slugs with no matching build.
///
/// Shadow Chat lives here because its jar is published on GitHub
/// Releases, not Modrinth. The URL below is a placeholder pointing at a
/// release that doesn't exist yet — the sibling mod-build task will
/// publish that asset, and the next setup pass after the release goes
/// live will pull it in. Until then, `install_mods` logs a skip line
/// and moves on, exactly like a missing Modrinth build.
pub const DIRECT_URL_MODS: &[DirectMod] = &[
    DirectMod {
        slug: "shadow-chat",
        filename: "shadow-chat-0.1.21.jar",
        url: shadow_chat::MOD_JAR_URL,
        mc_versions: shadow_chat::SUPPORTED_MC_VERSIONS,
        critical: false,
    },
];

pub struct DirectMod {
    pub slug: &'static str,
    pub filename: &'static str,
    pub url: &'static str,
    pub mc_versions: &'static [&'static str],
    pub critical: bool,
}

/// (slug, description, critical) — same list and order as the Python.
pub const PERFORMANCE_MODS: &[(&str, &str, bool)] = &[
    ("fabric-api",            "required by most fabric mods",               true),
    ("sodium",                "modern rendering engine — biggest FPS win",  true),
    ("lithium",               "game logic / tick optimizations",            true),
    ("ferrite-core",          "reduces memory footprint",                   true),
    ("immediatelyfast",       "faster HUD / GUI rendering",                 true),
    ("entityculling",         "skip rendering occluded entities",           true),
    ("dynamic-fps",           "drop FPS when window is unfocused",          true),
    ("nvidium",               "NVIDIA mesh-shader accelerated chunks",      false),
    ("ebe",                   "Enhanced Block Entities — baked chests",     false),
    ("particle-core",         "GPU-batched particle rendering",             false),
    ("scalablelux",           "modern lighting engine",                     false),
    ("krypton",               "network stack optimizations",                false),
    ("moreculling",           "extra block culling wins",                   false),
    ("memoryleakfix",         "patches known memory leaks",                 false),
    ("modernfix",             "many bug + memory fixes",                    false),
    ("badoptimizations",      "misc small wins",                            false),
    ("packet-fixer",          "fixes server packet stalls",                 false),
    ("language-reload",       "faster launch + lower language memory",      false),
    ("lmd",                   "Let Me Despawn — fewer idle mobs",           false),
    ("get-it-together-drops", "merge item entities on the ground",          false),
    ("rrls",                  "Remove Reloading Screen",                    false),
    ("puzzle",                "resource-pack / particle cache",             false),
    ("sodium-extra",          "extra sodium options",                       false),
    ("almanac",               "lib: needed by Let Me Despawn",              false),
    ("fabric-language-kotlin", "lib: needed by Particle Core",              false),
    ("fzzy-config",           "lib: needed by Particle Core",               false),
    ("forge-config-api-port", "lib: needed by RRLS",                        false),
];

/// Newest Modrinth version compatible with (mc_version, loader=fabric).
/// Exact `mc_version` match only — avoids the 1.21.1-mod-into-1.21.11 trap.
pub async fn pick_version(
    client: &reqwest::Client,
    slug: &str,
    mc_version: &str,
) -> Option<serde_json::Value> {
    let url = format!("{MODRINTH}/project/{slug}/version");
    let arr: serde_json::Value = mojang::http_get_json(client, &url).await.ok()?;
    let arr = arr.as_array()?;
    for v in arr {
        let loaders = v.get("loaders").and_then(|v| v.as_array())?;
        if !loaders.iter().any(|l| l.as_str() == Some("fabric")) { continue; }
        let gv = v.get("game_versions").and_then(|v| v.as_array())?;
        if !gv.iter().any(|g| g.as_str() == Some(mc_version)) { continue; }
        if v.get("version_type").and_then(|v| v.as_str()) == Some("alpha") { continue; }
        return Some(v.clone());
    }
    None
}

pub async fn install_mods(
    client: &reqwest::Client,
    mods_dir: &Path,
    mc_version: &str,
    progress: impl Fn(String) + Send + Sync,
) -> Result<(Vec<String>, Vec<String>)> {
    std::fs::create_dir_all(mods_dir)?;
    let mut installed = Vec::new();
    let mut skipped = Vec::new();
    let total = PERFORMANCE_MODS.len() + DIRECT_URL_MODS.len();
    for (i, (slug, desc, critical)) in PERFORMANCE_MODS.iter().enumerate() {
        progress(format!("  [{}/{}] {} — {}", i + 1, total, slug, desc));
        let v = match pick_version(client, slug, mc_version).await {
            Some(v) => v,
            None => {
                let msg = if *critical { " (critical missing!)" } else { "" };
                progress(format!("  skip {slug} — no {mc_version} fabric build{msg}"));
                skipped.push(slug.to_string());
                continue;
            }
        };
        let files = v.get("files").and_then(|f| f.as_array()).cloned().unwrap_or_default();
        let Some(file) = files.first() else { skipped.push(slug.to_string()); continue };
        let filename = file
            .get("filename").and_then(|v| v.as_str())
            .unwrap_or_else(|| slug);
        let url = file.get("url").and_then(|v| v.as_str()).unwrap_or_default();
        let sha1 = file
            .get("hashes").and_then(|h| h.get("sha1")).and_then(|v| v.as_str());
        let dest = mods_dir.join(filename);
        // Fail soft: one Modrinth hiccup shouldn't abort the entire
        // setup pass. Log + skip + keep going so the user at least gets
        // a partially-working install (which they can complete by
        // re-running "Update perf stack" from the mods tab later).
        // Previously a single `?` here turned a transient CDN flake
        // into "setup failed, deleted half the profile" pain.
        match download_file(client, url, &dest, sha1).await {
            Ok(()) => installed.push(slug.to_string()),
            Err(e) => {
                let msg = if *critical { " (critical!)" } else { "" };
                progress(format!("  skip {slug} — download failed: {e:#}{msg}"));
                skipped.push(slug.to_string());
            }
        }
    }

    // Direct-URL mods (Shadow Chat etc). Scoped to specific MC versions
    // and downloaded without a sha1 (GitHub Releases don't publish one in
    // a machine-readable form). Download failures are non-fatal — they
    // get logged + recorded in `skipped` so the user (and the launcher
    // UI) can see what was missed.
    let base = PERFORMANCE_MODS.len();
    for (i, m) in DIRECT_URL_MODS.iter().enumerate() {
        progress(format!(
            "  [{}/{}] {} — direct download",
            base + i + 1,
            total,
            m.slug
        ));
        if !m.mc_versions.iter().any(|v| *v == mc_version) {
            progress(format!(
                "  skip {} — not built for MC {mc_version}",
                m.slug
            ));
            skipped.push(m.slug.to_string());
            continue;
        }
        let dest = mods_dir.join(m.filename);
        match download_file(client, m.url, &dest, None).await {
            Ok(()) => installed.push(m.slug.to_string()),
            Err(e) => {
                let msg = if m.critical { " (critical missing!)" } else { "" };
                progress(format!(
                    "  skip {} — download failed: {e:#}{msg}",
                    m.slug
                ));
                skipped.push(m.slug.to_string());
            }
        }
    }

    Ok((installed, skipped))
}

/// Top-up pass that runs on every game launch. Walks DIRECT_URL_MODS and
/// downloads anything missing from the profile mods folder.
///
/// Why: the full `install_mods` only runs during the initial setup
/// pass. If we add a new direct-URL mod (e.g. shadow-chat) in a later
/// launcher version, existing profiles never pick it up unless the
/// user manually re-runs setup. This top-up function fills that gap —
/// every launch checks for + installs any missing direct mods that
/// are scoped to the current MC version.
///
/// Fast path (everything already installed) does no network IO — just
/// a few file existence checks. Designed to be called from
/// setup::launch right before spawning Java, no perceptible delay.
pub async fn ensure_direct_mods_present(
    client: &reqwest::Client,
    mods_dir: &std::path::Path,
    mc_version: &str,
    progress: impl Fn(String) + Send + Sync,
) -> anyhow::Result<Vec<String>> {
    let mut added: Vec<String> = Vec::new();
    std::fs::create_dir_all(mods_dir).ok();

    for m in DIRECT_URL_MODS.iter() {
        if !m.mc_versions.iter().any(|v| *v == mc_version) {
            continue;
        }
        let dest = mods_dir.join(m.filename);
        if dest.exists() {
            continue;
        }

        // Try the download FIRST, then sweep stale prior-version jars.
        // The earlier ordering removed the old jar before fetch — which
        // left the user with NO chat mod if the new version's release
        // hadn't been published yet (e.g. launcher version bumped
        // ahead of the mod tag). Fetch-then-sweep keeps the old jar in
        // place as a working fallback when the new download 404s.
        progress(format!("  Shadow Chat: installing {} into profile…", m.filename));
        match download_file(client, m.url, &dest, None).await {
            Ok(()) => {
                progress(format!("  Shadow Chat: {} installed", m.filename));
                added.push(m.slug.to_string());
                // Sweep prior-version jars NOW that the new one is on
                // disk. Fabric refuses to load duplicate modids, so we
                // need to delete the old jar — but only after the new
                // one is confirmed present.
                if let Some(stem_prefix) = mod_stem_prefix(m.filename) {
                    if let Ok(entries) = std::fs::read_dir(mods_dir) {
                        for e in entries.flatten() {
                            let fname = e.file_name().to_string_lossy().to_string();
                            if fname == m.filename { continue; }
                            if fname.starts_with(stem_prefix) && fname.ends_with(".jar") {
                                progress(format!(
                                    "  Shadow Chat: removed stale {}", fname
                                ));
                                let _ = std::fs::remove_file(e.path());
                            }
                        }
                    }
                }
            }
            Err(e) => {
                progress(format!(
                    "  Shadow Chat: download failed ({e:#}) — keeping existing mod jar if any. \
                     Next launch will retry."
                ));
            }
        }
    }
    Ok(added)
}

/// `"shadow-chat-0.1.1.jar"` → `Some("shadow-chat-")`. Used to detect
/// stale prior-version jars of the same mod. Splits on the LAST `-`
/// before what looks like a version digit; falls back to None if the
/// filename doesn't have an obvious version suffix (in which case we
/// just don't try to delete anything older).
fn mod_stem_prefix(filename: &str) -> Option<&str> {
    let stem = filename.strip_suffix(".jar")?;
    // Walk back from the end to find the last "-N" boundary.
    let mut bytes = stem.as_bytes().iter().enumerate().rev();
    while let Some((i, b)) = bytes.next() {
        if *b == b'-' {
            // Confirm what follows is a digit (i.e. version starts here).
            if i + 1 < stem.len() && stem.as_bytes()[i + 1].is_ascii_digit() {
                return Some(&filename[..i + 1]);
            }
        }
    }
    None
}
