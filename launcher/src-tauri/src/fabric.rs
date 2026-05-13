//! Fabric mod loader integration. Rust port of `fabric.py`.

use anyhow::{anyhow, bail, Result};

use crate::mojang::{self, maven_to_path};

pub const FABRIC_META: &str = "https://meta.fabricmc.net/v2";
pub const FABRIC_MAVEN: &str = "https://maven.fabricmc.net";

/// Latest Fabric loader version for a given Minecraft version.
pub async fn latest_loader(client: &reqwest::Client, mc_version: &str) -> Result<String> {
    let url = format!("{FABRIC_META}/versions/loader/{mc_version}");
    let arr: serde_json::Value = mojang::http_get_json(client, &url).await?;
    let first = arr
        .as_array()
        .and_then(|a| a.first())
        .ok_or_else(|| anyhow!("No Fabric loader available for MC {mc_version}"))?;
    let v = first
        .get("loader")
        .and_then(|v| v.get("version"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("Fabric loader JSON unexpected shape"))?;
    Ok(v.to_string())
}

/// Fetch the Fabric "profile" JSON — the launcher manifest fragment that
/// lists Fabric's extra libraries + the alternative main class.
pub async fn fetch_profile(
    client: &reqwest::Client,
    mc_version: &str,
    loader_version: &str,
) -> Result<serde_json::Value> {
    let url = format!("{FABRIC_META}/versions/loader/{mc_version}/{loader_version}/profile/json");
    mojang::http_get_json(client, &url).await
}

/// Merge Fabric on top of vanilla. Fabric's libs win on name collision.
pub fn merge_into_vanilla(
    vanilla: &serde_json::Value,
    fabric: &serde_json::Value,
) -> Result<serde_json::Value> {
    let mut merged = vanilla.clone();
    let merged_obj = merged
        .as_object_mut()
        .ok_or_else(|| anyhow!("vanilla JSON is not an object"))?;

    if let Some(id) = fabric.get("id").and_then(|v| v.as_str()) {
        merged_obj.insert("id".into(), serde_json::Value::String(id.to_string()));
    }
    if let Some(mc) = fabric.get("mainClass").and_then(|v| v.as_str()) {
        merged_obj.insert("mainClass".into(), serde_json::Value::String(mc.to_string()));
    }

    // Libraries — normalise Fabric's lean entries (just `name` + `url`) into
    // full Mojang-style `downloads.artifact` blocks, then merge.
    let fabric_libs_raw = fabric
        .get("libraries")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let normalised = normalise_libraries(&fabric_libs_raw)?;

    let mut existing: std::collections::HashSet<String> = normalised
        .iter()
        .filter_map(|l| l.get("name").and_then(|v| v.as_str()).map(String::from))
        .collect();

    let mut combined: Vec<serde_json::Value> = normalised;
    if let Some(vlibs) = merged_obj
        .get("libraries")
        .and_then(|v| v.as_array())
        .cloned()
    {
        for lib in vlibs {
            let name = lib.get("name").and_then(|v| v.as_str()).unwrap_or_default();
            if existing.contains(name) { continue; }
            existing.insert(name.to_string());
            combined.push(lib);
        }
    }
    merged_obj.insert("libraries".into(), serde_json::Value::Array(combined));

    // Append Fabric's arguments.jvm / arguments.game onto the vanilla ones.
    if let Some(fa) = fabric.get("arguments").and_then(|v| v.as_object()) {
        let entry = merged_obj
            .entry("arguments")
            .or_insert_with(|| serde_json::json!({"jvm": [], "game": []}));
        let obj = entry.as_object_mut().ok_or_else(|| anyhow!("arguments not object"))?;
        for key in ["jvm", "game"] {
            if let Some(extra) = fa.get(key).and_then(|v| v.as_array()) {
                let v = obj.entry(key).or_insert_with(|| serde_json::Value::Array(vec![]));
                let arr = v.as_array_mut().ok_or_else(|| anyhow!("{key} not array"))?;
                for e in extra { arr.push(e.clone()); }
            }
        }
    }
    Ok(merged)
}

fn normalise_libraries(libs: &[serde_json::Value]) -> Result<Vec<serde_json::Value>> {
    let mut out = Vec::with_capacity(libs.len());
    for lib in libs {
        let has_artifact = lib
            .get("downloads")
            .and_then(|d| d.get("artifact"))
            .is_some();
        if has_artifact {
            out.push(lib.clone());
            continue;
        }
        let name = lib
            .get("name")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow!("Fabric lib entry missing name"))?;
        let base = lib
            .get("url")
            .and_then(|v| v.as_str())
            .unwrap_or(FABRIC_MAVEN);
        let base = if base.ends_with('/') {
            base.to_string()
        } else {
            format!("{base}/")
        };
        let path = maven_to_path(name)?;
        let url = format!("{base}{path}");
        let entry = serde_json::json!({
            "name": name,
            "downloads": {
                "artifact": {
                    "path": path,
                    "url": url,
                }
            }
        });
        out.push(entry);
    }
    if out.is_empty() {
        bail!("Fabric profile contained no libraries");
    }
    Ok(out)
}
