//! Account file management + Microsoft OAuth. Rust port of `auth.py`.
//!
//! Microsoft device-code flow:
//!   1. POST /devicecode → get a code + verification URL
//!   2. Open the verification URL in the user's browser (with the code
//!      pre-filled via `verification_uri_complete` — no typing required)
//!   3. Poll /token every few seconds until the user finishes signing in
//!   4. Exchange the MS token through Xbox Live → XSTS → Minecraft Services
//!   5. Hit /minecraft/profile to resolve the Mojang username + UUID
//!   6. Save the resulting Account with refresh_token so future launches
//!      can refresh silently without prompting again

use anyhow::{anyhow, bail, Context, Result};
use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};
use std::path::Path;

pub const MS_CLIENT_ID: &str = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";
pub const DEVICE_CODE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
pub const TOKEN_URL: &str       = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
pub const XBL_URL: &str         = "https://user.auth.xboxlive.com/user/authenticate";
pub const XSTS_URL: &str        = "https://xsts.auth.xboxlive.com/xsts/authorize";
pub const MC_LOGIN_URL: &str    = "https://api.minecraftservices.com/authentication/login_with_xbox";
pub const MC_PROFILE_URL: &str  = "https://api.minecraftservices.com/minecraft/profile";

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

// ───── Microsoft device-code flow ──────────────────────────────────────

#[derive(Debug, Deserialize)]
struct DeviceCodeResp {
    device_code: String,
    user_code: String,
    verification_uri: String,
    #[serde(default)]
    verification_uri_complete: Option<String>,
    #[serde(default)]
    interval: Option<u64>,
    #[serde(default)]
    expires_in: Option<u64>,
}

#[derive(Debug, Deserialize)]
struct TokenResp {
    access_token: String,
    #[serde(default)]
    refresh_token: Option<String>,
}

#[derive(Debug, Deserialize)]
struct AuthErrorResp {
    error: String,
    #[serde(default)]
    error_description: Option<String>,
}

#[derive(Debug, Deserialize)]
struct XblXui { uhs: String }
#[derive(Debug, Deserialize)]
struct XblDisplayClaims { xui: Vec<XblXui> }
#[derive(Debug, Deserialize)]
struct XblResp {
    #[serde(rename = "Token")] token: String,
    #[serde(rename = "DisplayClaims")] display_claims: XblDisplayClaims,
}

#[derive(Debug, Deserialize)]
struct XstsResp {
    #[serde(rename = "Token")] token: String,
}

#[derive(Debug, Deserialize)]
struct McLoginResp {
    access_token: String,
}

#[derive(Debug, Deserialize)]
struct McProfile {
    id: String,
    name: String,
}

/// Information surfaced back to the front-end immediately after we get the
/// device code, so the corner-widget Sign-in dropdown can show "Open this
/// URL and enter `ABCD-1234`" while we poll in the background. The
/// `verification_uri_complete` URL embeds the code already, so the user
/// usually doesn't need to type it.
#[derive(Debug, Serialize, Clone)]
pub struct DeviceCodePrompt {
    pub user_code: String,
    pub verification_uri: String,
    pub verification_uri_complete: String,
    pub expires_in: u64,
}

/// Run the full device-code flow synchronously. Calls `progress` with
/// human-readable status updates ("Waiting for sign-in…", "Got Xbox Live
/// token", etc.). On success, returns a populated `Account` that the
/// caller should `save()` to disk.
///
/// The `on_prompt` callback is fired exactly once, right after Microsoft
/// hands us the device code. The Tauri command uses it to open the URL
/// in the user's default browser (via the shell plugin) without blocking
/// the polling loop.
pub async fn microsoft_device_login(
    progress: impl Fn(String) + Send + Sync,
    on_prompt: impl FnOnce(DeviceCodePrompt) + Send + Sync,
) -> Result<Account> {
    use std::time::{Duration, Instant};

    let client = reqwest::Client::builder()
        .user_agent(crate::mojang::UA)
        .timeout(Duration::from_secs(60))
        .build()?;

    progress("Requesting device code from Microsoft…".into());
    let dc: DeviceCodeResp = client
        .post(DEVICE_CODE_URL)
        .form(&[
            ("client_id", MS_CLIENT_ID),
            ("scope", "XboxLive.signin offline_access"),
        ])
        .send()
        .await?
        .error_for_status()
        .with_context(|| "device-code request failed")?
        .json()
        .await?;

    let verification_uri_complete = dc
        .verification_uri_complete
        .clone()
        .unwrap_or_else(|| format!("{}?otc={}", dc.verification_uri, dc.user_code));
    let prompt = DeviceCodePrompt {
        user_code: dc.user_code.clone(),
        verification_uri: dc.verification_uri.clone(),
        verification_uri_complete: verification_uri_complete.clone(),
        expires_in: dc.expires_in.unwrap_or(900),
    };
    on_prompt(prompt);

    progress(format!(
        "Browser will open. If it doesn't, go to {} and enter code: {}",
        dc.verification_uri, dc.user_code
    ));

    // Poll until the user finishes signing in (or the device code expires).
    let mut interval = dc.interval.unwrap_or(5);
    let deadline = Instant::now() + Duration::from_secs(dc.expires_in.unwrap_or(900));
    let (ms_access, ms_refresh) = loop {
        if Instant::now() >= deadline {
            bail!("Microsoft sign-in timed out — please try again.");
        }
        tokio::time::sleep(Duration::from_secs(interval)).await;
        let resp = client
            .post(TOKEN_URL)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", MS_CLIENT_ID),
                ("device_code", &dc.device_code),
            ])
            .send()
            .await?;
        if resp.status().is_success() {
            let tok: TokenResp = resp.json().await?;
            break (tok.access_token, tok.refresh_token.unwrap_or_default());
        }
        // Inspect the error to decide whether to keep polling or bail.
        let err: AuthErrorResp = match resp.json().await {
            Ok(e) => e,
            Err(_) => bail!("Microsoft sign-in failed (couldn't parse error)"),
        };
        match err.error.as_str() {
            "authorization_pending" => { /* user hasn't entered code yet */ }
            "slow_down" => { interval += 5; }
            "expired_token" => bail!("Sign-in code expired — please try again."),
            "access_denied" => bail!("Sign-in cancelled."),
            other => bail!("Microsoft sign-in error: {}{}",
                other,
                err.error_description.map(|d| format!(" — {d}")).unwrap_or_default()),
        }
    };

    // ms_access → Xbox Live → XSTS → Minecraft Services → profile.
    progress("Exchanging Microsoft token for Xbox Live…".into());
    let xbl: XblResp = client
        .post(XBL_URL)
        .json(&serde_json::json!({
            "Properties": {
                "AuthMethod": "RPS",
                "SiteName": "user.auth.xboxlive.com",
                "RpsTicket": format!("d={}", ms_access),
            },
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT",
        }))
        .send().await?
        .error_for_status().with_context(|| "Xbox Live auth failed")?
        .json().await?;
    let xbl_token = xbl.token;
    let uhs = xbl
        .display_claims
        .xui
        .first()
        .ok_or_else(|| anyhow!("XBL response missing display claims"))?
        .uhs
        .clone();

    progress("Exchanging Xbox Live for XSTS…".into());
    let xsts: XstsResp = client
        .post(XSTS_URL)
        .json(&serde_json::json!({
            "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbl_token] },
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT",
        }))
        .send().await?
        .error_for_status().with_context(|| "XSTS auth failed")?
        .json().await?;

    progress("Getting Minecraft access token…".into());
    let mc: McLoginResp = client
        .post(MC_LOGIN_URL)
        .json(&serde_json::json!({
            "identityToken": format!("XBL3.0 x={};{}", uhs, xsts.token),
        }))
        .send().await?
        .error_for_status().with_context(|| "Minecraft Services login failed")?
        .json().await?;

    progress("Fetching Minecraft profile…".into());
    let profile: McProfile = client
        .get(MC_PROFILE_URL)
        .bearer_auth(&mc.access_token)
        .send().await?
        .error_for_status().with_context(|| {
            "No Minecraft profile on this account — does it own a copy of Minecraft?"
        })?
        .json().await?;

    // Mojang returns UUIDs un-hyphenated. The launch arg builder + the
    // server want hyphenated form, so we expand here.
    if profile.id.len() != 32 {
        bail!("Mojang returned an unexpected UUID format: {}", profile.id);
    }
    let p = &profile.id;
    let dashed = format!(
        "{}-{}-{}-{}-{}",
        &p[0..8], &p[8..12], &p[12..16], &p[16..20], &p[20..]
    );

    progress(format!("Signed in as {}.", profile.name));
    Ok(Account {
        username: profile.name,
        uuid: dashed,
        access_token: mc.access_token,
        user_type: "msa".to_string(),
        refresh_token: ms_refresh,
        msa_client_id: MS_CLIENT_ID.to_string(),
        refresh_updated_at: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs_f64())
            .unwrap_or(0.0),
    })
}
