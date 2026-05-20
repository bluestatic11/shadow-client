//! Shadow Chat launcher-side glue.
//!
//! Two responsibilities:
//!   1. Write a tiny `shadow-chat-auth.json` next to the game's working dir
//!      on every launch so the mod can read auth + relay info on startup.
//!   2. Provide the placeholder GitHub Release URL the mod-installer should
//!      hand-install on each setup pass.
//!
//! The auth file schema is locked by the mod side — do not change field
//! names or shape without bumping a protocol version on both ends.
//!
//! Schema:
//! ```json
//! {
//!   "relay_url": "wss://shadow-chat-relay.bluestatic11.workers.dev",
//!   "token": "<account.access_token, or null if user_type != 'msa'>",
//!   "uuid":  "<dashed UUID from account.uuid>",
//!   "name":  "<account.username>"
//! }
//! ```
//!
//! The token field is JSON `null` (not the string "null") when the account
//! is in offline mode — the mod uses that to stay dormant rather than
//! attempting a relay connection with bogus credentials.

use anyhow::{Context, Result};
use serde::Serialize;
use std::path::Path;

use crate::auth::Account;

/// Default relay URL — the live Cloudflare Worker deployed from
/// `chat-relay/`. Overridable at launch via the `SHADOW_CHAT_RELAY`
/// env var so devs can point at a local Wrangler instance.
pub const DEFAULT_RELAY_URL: &str = "wss://shadow-chat-relay.edisongushf.workers.dev";

/// Filename written into the profile working directory on each launch.
/// The mod looks for this exact name relative to its run dir.
pub const AUTH_FILENAME: &str = "shadow-chat-auth.json";

/// IPC drop-file the launcher writes to signal the mod to do something
/// on its next tick. Mod polls for it, executes, deletes. JSON shape:
/// `{ "action": "open-chat" | "open-chat-with", "target": "<username>?" }`.
pub const COMMAND_FILENAME: &str = "shadow-chat-command.json";

/// Placeholder GitHub Release URL for the mod jar. The mod is being built
/// in a sibling task and this release does not exist yet — once it ships
/// the URL will resolve and the auto-installer will pull it on the next
/// setup pass.
pub const MOD_JAR_URL: &str =
    "https://github.com/bluestatic11/shadow-client/releases/download/chat-mod-v0.1.16/shadow-chat-0.1.16.jar";

/// MC versions the mod is currently built for. The auto-installer scopes
/// the entry to these versions only — older / unsupported versions skip
/// it silently the same way they skip Nvidium etc.
pub const SUPPORTED_MC_VERSIONS: &[&str] = &["1.21.10", "1.21.11"];

/// On-disk schema. Field names are wire-format — do not rename.
#[derive(Debug, Serialize)]
struct AuthFile<'a> {
    relay_url: String,
    /// JSON null when the account is offline. `Option` + skipping nothing
    /// gives us exactly that with serde_json.
    token: Option<&'a str>,
    uuid: &'a str,
    name: &'a str,
}

/// Resolve the relay URL, honoring `SHADOW_CHAT_RELAY` if set.
pub fn relay_url() -> String {
    std::env::var("SHADOW_CHAT_RELAY")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| DEFAULT_RELAY_URL.to_string())
}

/// Write an IPC command file the mod will pick up on its next poll.
/// Action is one of "open-chat" or "open-chat-with"; target is the
/// friend username when relevant (None otherwise). Writes are best
/// effort — the caller should treat a None result as "mod might not
/// see the signal" and gracefully degrade.
pub fn write_command_file(profile_dir: &Path, action: &str, target: Option<&str>) -> Result<()> {
    std::fs::create_dir_all(profile_dir)
        .with_context(|| format!("creating {}", profile_dir.display()))?;
    let mut obj = serde_json::Map::new();
    obj.insert("action".into(), serde_json::Value::String(action.to_string()));
    if let Some(t) = target {
        obj.insert("target".into(), serde_json::Value::String(t.to_string()));
    }
    // Include a timestamp so a stale file from a crashed prior session
    // can be detected by the mod and dropped instead of acted on.
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    obj.insert("created_at_ms".into(), serde_json::Value::Number(now_ms.into()));
    let body = serde_json::to_string_pretty(&serde_json::Value::Object(obj))
        .context("serializing shadow-chat-command.json")?;
    let dest = profile_dir.join(COMMAND_FILENAME);
    let tmp = dest.with_extension("json.tmp");
    std::fs::write(&tmp, body)
        .with_context(|| format!("writing {}", tmp.display()))?;
    std::fs::rename(&tmp, &dest)
        .with_context(|| format!("renaming {} into place", dest.display()))?;
    Ok(())
}

/// Write `shadow-chat-auth.json` into `profile_dir`. Errors are returned
/// so the caller can decide whether to log + continue (launch path) or
/// surface them (test path). The launch path uses `.ok()` to make this
/// best-effort — a chat-mod write failure must never block the game.
pub fn write_auth_file(profile_dir: &Path, account: &Account) -> Result<()> {
    std::fs::create_dir_all(profile_dir)
        .with_context(|| format!("creating {}", profile_dir.display()))?;

    let is_msa = account.user_type == "msa";
    let payload = AuthFile {
        relay_url: relay_url(),
        token: if is_msa { Some(account.access_token.as_str()) } else { None },
        uuid: &account.uuid,
        name: &account.username,
    };

    let body = serde_json::to_string_pretty(&payload)
        .context("serializing shadow-chat-auth.json")?;

    let dest = profile_dir.join(AUTH_FILENAME);
    let tmp = dest.with_extension("json.tmp");
    std::fs::write(&tmp, body)
        .with_context(|| format!("writing {}", tmp.display()))?;
    std::fs::rename(&tmp, &dest)
        .with_context(|| format!("renaming {} -> {}", tmp.display(), dest.display()))?;
    Ok(())
}

/// Short human-readable status line for the launch progress feed.
/// `enabled (msa)` / `dormant (offline mode)` / `disabled (write failed: …)`.
pub fn status_line(account: &Account) -> String {
    if account.user_type == "msa" {
        format!("Shadow Chat: enabled ({})", account.username)
    } else {
        "Shadow Chat: dormant (offline mode — sign in with Microsoft to enable)".to_string()
    }
}
