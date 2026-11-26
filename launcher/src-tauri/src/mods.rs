//! Modrinth performance-mod installer. Rust port of `mods.py`.

use anyhow::Result;
use std::collections::HashMap;
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
        filename: "shadow-chat-0.1.48.jar",
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
    ("cloth-config",          "lib: needed by More Culling",                false),
    ("iris",                  "shader support",                             false),
    ("modmenu",               "in-game mod list + config screens",          false),
    ("combat-hitboxes",       "hitbox colour when a target is in reach",    false),
    ("hitcolor",              "hit players flash a colour",                 false),
    ("yacl",                  "lib: needed by HitColor",                    false),
    ("placeholder-api",       "lib: needed by Mod Menu",                    false),
    ("fabric-language-kotlin", "lib: needed by Particle Core",              false),
    ("fzzy-config",           "lib: needed by Particle Core",               false),
    ("forge-config-api-port", "lib: needed by RRLS",                        false),
];

/// Newest Modrinth version compatible with (mc_version, loader=fabric).
/// Exact `mc_version` match only — avoids the 1.21.1-mod-into-1.21.11 trap.
/// Exact Modrinth version_number to use for a mod on one Minecraft version
/// (mc, slug, version_number), when the newest release conflicts with another
/// mod in the list. 26.3: every Sodium from 0.9.3 on marks Iris <= 1.12.0 as
/// incompatible, and More Culling 1.9.2 marks Sodium <= 0.9.3-beta.1 as
/// incompatible. Drop these once Iris 1.12 is out for 26.3. Keep in sync with
/// VERSION_PINS in mods.py.
pub const VERSION_PINS: &[(&str, &str, &str)] = &[
    ("26.3", "sodium", "mc26.3-0.9.2-fabric"),
    ("26.3", "sodium-extra", "mc26.3-0.9.5+fabric"),
    ("26.3", "moreculling", "1.9.1"),
];

/// Newest release for (mc_version, fabric); a beta only when the mod has no
/// release for that version, never an alpha. Betas are often ahead of the
/// rest of the ecosystem (Sodium betas declare current Iris incompatible).
pub async fn pick_version(
    client: &reqwest::Client,
    slug: &str,
    mc_version: &str,
) -> Option<serde_json::Value> {
    let url = format!("{MODRINTH}/project/{slug}/version");
    let arr: serde_json::Value = mojang::http_get_json(client, &url).await.ok()?;
    let arr = arr.as_array()?;
    let pin = VERSION_PINS
        .iter()
        .find(|(mc, s, _)| *mc == mc_version && *s == slug)
        .map(|(_, _, v)| *v);
    let matching: Vec<&serde_json::Value> = arr
        .iter()
        .filter(|v| {
            let fabric = v.get("loaders").and_then(|l| l.as_array())
                .map_or(false, |l| l.iter().any(|x| x.as_str() == Some("fabric")));
            let mc = v.get("game_versions").and_then(|g| g.as_array())
                .map_or(false, |g| g.iter().any(|x| x.as_str() == Some(mc_version)));
            fabric && mc
        })
        .collect();
    if let Some(pin) = pin {
        if let Some(v) = matching.iter().find(|v| {
            v.get("version_number").and_then(|x| x.as_str()) == Some(pin)
        }) {
            return Some((*v).clone());
        }
    }
    let of_type = |t: &str| {
        matching.iter().find(|v| v.get("version_type").and_then(|x| x.as_str()) == Some(t))
    };
    of_type("release").or_else(|| of_type("beta")).map(|v| (*v).clone())
}

/// File in a profile folder listing the Modrinth project IDs the user removed
/// with the Remove button. install_mods never reinstalls them.
pub const REMOVED_MODS_FILE: &str = "removed-mods.json";

pub fn load_removed(profile_dir: &Path) -> Vec<String> {
    std::fs::read(profile_dir.join(REMOVED_MODS_FILE))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

pub fn save_removed(profile_dir: &Path, ids: &[String]) -> Result<()> {
    std::fs::write(profile_dir.join(REMOVED_MODS_FILE), serde_json::to_vec_pretty(ids)?)?;
    Ok(())
}

/// Map each .jar in `mods_dir` to its Modrinth project ID (one batched
/// lookup by SHA-1). Jars Modrinth doesn't know are left out. Best effort:
/// any network failure returns an empty map.
async fn projects_of_jars(client: &reqwest::Client, mods_dir: &Path) -> HashMap<String, String> {
    let mut by_hash: HashMap<String, String> = HashMap::new();
    if let Ok(rd) = std::fs::read_dir(mods_dir) {
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if !name.ends_with(".jar") { continue; }
            if let Ok(h) = mojang::sha1_file(&e.path()) {
                by_hash.insert(h, name);
            }
        }
    }
    let mut out = HashMap::new();
    if by_hash.is_empty() { return out; }
    let hashes: Vec<&String> = by_hash.keys().collect();
    let body = serde_json::json!({ "hashes": hashes, "algorithm": "sha1" });
    let resp = client
        .post(format!("{MODRINTH}/version_files"))
        .header("User-Agent", mojang::UA)
        .json(&body)
        .send()
        .await;
    let Ok(resp) = resp else { return out };
    let Ok(val) = resp.json::<serde_json::Value>().await else { return out };
    if let Some(obj) = val.as_object() {
        for (hash, ver) in obj {
            let pid = ver.get("project_id").and_then(|v| v.as_str());
            if let (Some(name), Some(pid)) = (by_hash.get(hash), pid) {
                out.insert(name.clone(), pid.to_string());
            }
        }
    }
    out
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
    // Mods the user removed with the Remove button stay removed, and each
    // jar already present is mapped to its Modrinth project so an update can
    // delete the old version instead of leaving two copies (Fabric refuses
    // to start with duplicate mods).
    let removed = mods_dir.parent().map(load_removed).unwrap_or_default();
    let existing = projects_of_jars(client, mods_dir).await;
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
        let project_id = v.get("project_id").and_then(|p| p.as_str()).unwrap_or_default().to_string();
        if !project_id.is_empty() && removed.iter().any(|r| *r == project_id) {
            progress(format!("  skip {slug} — you removed it"));
            skipped.push(slug.to_string());
            continue;
        }
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
            Ok(()) => {
                installed.push(slug.to_string());
                for (name, pid) in &existing {
                    if !project_id.is_empty() && *pid == project_id && name.as_str() != filename {
                        if std::fs::remove_file(mods_dir.join(name)).is_ok() {
                            progress(format!("  removed old {name}"));
                        }
                    }
                }
            }
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
