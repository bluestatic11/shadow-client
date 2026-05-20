//! Friend presence — publish our own "playing MC" status to the relay
//! and query friends' statuses for the launcher's sidebar.
//!
//! Wire format mirrors the chat-relay's `/presence/heartbeat` and
//! `/presence/query` endpoints (see chat-relay/src/presence.ts). The
//! relay verifies the bearer token on heartbeat to learn the caller's
//! verified UUID + display name — we don't trust the client to claim
//! its own identity.

use anyhow::{anyhow, Context, Result};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::time::Duration;

use crate::shadow_chat;

const HTTP_TIMEOUT: Duration = Duration::from_secs(8);

/** Convert the configured wss:// relay URL into https://, which is
 *  what the presence HTTP endpoints sit behind. */
fn http_relay_base() -> String {
    let mut url = shadow_chat::relay_url();
    if let Some(rest) = url.strip_prefix("wss://") {
        url = format!("https://{rest}");
    } else if let Some(rest) = url.strip_prefix("ws://") {
        url = format!("http://{rest}");
    }
    url.trim_end_matches('/').to_string()
}

/// Body of a /presence/heartbeat POST. The launcher chooses these
/// fields; the relay overrides `uuid` + `name` from the token.
#[derive(Serialize)]
struct HeartbeatBody {
    /// Launcher identifier — we're always "shadow-client", but the
    /// relay accepts any string so future third-party clients can
    /// publish under their own name.
    launcher: &'static str,
    /// Semver string of the launcher emitting this heartbeat.
    version: String,
    /// MC server host the user is on, or None when in the launcher /
    /// singleplayer.
    server: Option<String>,
    /// "playing" while MC is up, "idle" while the user is in the
    /// launcher only.
    status: &'static str,
}

/// One entry returned by /presence/query — mirrors the relay-side
/// PresenceEntry interface. Names are camelCase on the wire to match
/// the TypeScript producer; we rename on deserialize.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PresenceEntry {
    pub uuid: String,
    pub name: String,
    pub launcher: String,
    #[serde(default)]
    pub version: Option<String>,
    #[serde(default)]
    pub server: Option<String>,
    pub status: String,
    #[serde(rename = "lastSeenAt")]
    pub last_seen_at: u64,
}

#[derive(Deserialize)]
struct QueryResponse {
    #[serde(default)]
    entries: Vec<PresenceEntry>,
}

/// Publish a heartbeat for the signed-in user.
///
/// `token` must be a real Microsoft / Minecraft access token — the
/// relay verifies it before accepting the entry. `status` should be
/// "playing" or "idle"; anything else is normalized to "idle" by the
/// relay.
pub async fn heartbeat(
    token: &str,
    launcher_version: String,
    server: Option<String>,
    playing: bool,
) -> Result<()> {
    if token.len() < 16 {
        return Err(anyhow!("no valid access token — sign in with Microsoft first"));
    }
    let url = format!("{}/presence/heartbeat", http_relay_base());
    let client = Client::builder().timeout(HTTP_TIMEOUT).build()?;
    let body = HeartbeatBody {
        launcher: "shadow-client",
        version: launcher_version,
        server,
        status: if playing { "playing" } else { "idle" },
    };
    let resp = client
        .post(&url)
        .bearer_auth(token)
        .json(&body)
        .send()
        .await
        .context("contacting presence relay")?;
    if !resp.status().is_success() {
        let code = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        return Err(anyhow!("presence relay returned {code}: {body}"));
    }
    Ok(())
}

/// Look up presence for a list of UUIDs. Returns only the UUIDs that
/// have heartbeat'd within the relay's TTL window (5 min).
pub async fn query(uuids: Vec<String>) -> Result<Vec<PresenceEntry>> {
    if uuids.is_empty() {
        return Ok(Vec::new());
    }
    let url = format!("{}/presence/query", http_relay_base());
    let client = Client::builder().timeout(HTTP_TIMEOUT).build()?;
    #[derive(Serialize)]
    struct Body { uuids: Vec<String> }
    let resp = client
        .post(&url)
        .json(&Body { uuids })
        .send()
        .await
        .context("contacting presence relay")?;
    if !resp.status().is_success() {
        return Ok(Vec::new());
    }
    let parsed: QueryResponse = resp.json().await.unwrap_or(QueryResponse { entries: Vec::new() });
    Ok(parsed.entries)
}

/// Resolve a Minecraft username → dashed UUID via Mojang's public
/// profile API. Used by `friends_add` so the launcher has a stable
/// identifier to query presence for, independent of name changes.
pub async fn resolve_username_to_uuid(username: &str) -> Result<(String, String)> {
    let url = format!("https://api.mojang.com/users/profiles/minecraft/{}", username);
    let client = Client::builder().timeout(HTTP_TIMEOUT).build()?;
    let resp = client.get(&url).send().await.context("contacting Mojang")?;
    if resp.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(anyhow!("'{username}' isn't a registered Minecraft account"));
    }
    if !resp.status().is_success() {
        return Err(anyhow!("Mojang returned {} for username lookup", resp.status()));
    }
    #[derive(Deserialize)]
    struct ProfileResp {
        id: String,
        name: String,
    }
    let p: ProfileResp = resp.json().await.context("parsing Mojang response")?;
    if p.id.len() != 32 || !p.id.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(anyhow!("Mojang returned malformed id: {}", p.id));
    }
    let dashed = format!(
        "{}-{}-{}-{}-{}",
        &p.id[0..8],
        &p.id[8..12],
        &p.id[12..16],
        &p.id[16..20],
        &p.id[20..32],
    );
    Ok((dashed, p.name))
}
