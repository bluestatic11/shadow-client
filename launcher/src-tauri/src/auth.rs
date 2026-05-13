//! Account file management. Rust port of `auth.py`.
//!
//! Microsoft OAuth is not yet implemented in Rust — offline mode only. The
//! corner-widget Sign-in flow falls back gracefully (the UI just stays in
//! its "not signed in" state). MS auth lands in a follow-up commit.

use anyhow::{Context, Result};
use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct Account {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
    pub user_type: String,        // "legacy" (offline) or "msa"
    #[serde(default)] pub refresh_token: String,
    #[serde(default)] pub msa_client_id: String,
    #[serde(default)] pub refresh_updated_at: f64,
}

impl Account {
    pub fn offline(username: &str) -> Self {
        Self {
            username: username.to_string(),
            uuid: offline_uuid(username),
            // MC accepts any non-empty string in offline mode — the server
            // doesn't verify it. We pass the same value Mojang's vanilla
            // launcher passes for "no-auth" profiles.
            access_token: "0".to_string(),
            user_type: "legacy".to_string(),
            refresh_token: String::new(),
            msa_client_id: String::new(),
            refresh_updated_at: 0.0,
        }
    }

    pub fn load(path: &Path) -> Option<Self> {
        let raw = std::fs::read(path).ok()?;
        serde_json::from_slice(&raw).ok()
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let tmp = path.with_extension(format!(
            "{}.tmp",
            path.extension().and_then(|s| s.to_str()).unwrap_or("json")
        ));
        std::fs::write(&tmp, serde_json::to_string_pretty(self)?)
            .with_context(|| format!("writing {}", tmp.display()))?;
        std::fs::rename(&tmp, path)?;
        Ok(())
    }
}

/// Deterministic offline UUID — exactly what Mojang's server computes for
/// players who join without authenticating: MD5("OfflinePlayer:" + name),
/// version-3-namespace formatted.
pub fn offline_uuid(username: &str) -> String {
    let mut h = Md5::new();
    h.update(format!("OfflinePlayer:{username}").as_bytes());
    let mut bytes = h.finalize();
    // RFC-4122 version 3 + variant bits.
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let s = format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0], bytes[1], bytes[2], bytes[3],
        bytes[4], bytes[5],
        bytes[6], bytes[7],
        bytes[8], bytes[9],
        bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15],
    );
    s
}
