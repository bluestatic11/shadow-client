//! Modrinth performance-mod installer. Rust port of `mods.py`.

use anyhow::Result;
use std::path::Path;

use crate::mojang::{self, download_file};

pub const MODRINTH: &str = "https://api.modrinth.com/v2";

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
    let total = PERFORMANCE_MODS.len();
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
        download_file(client, url, &dest, sha1).await?;
        installed.push(slug.to_string());
    }
    Ok((installed, skipped))
}
