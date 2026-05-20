//! Minecraft Server List Ping client.
//!
//! Implements the modern (post-1.7) status protocol so the launcher
//! can ask any vanilla / Spigot / Paper / etc. server "who's online
//! right now". Used to detect friends playing on shared servers even
//! when those friends aren't running Shadow Client (i.e. our
//! presence relay can't see them).
//!
//! Protocol summary (Wiki.vg / wiki.vg/Server_List_Ping):
//!   1. TCP connect to host:port.
//!   2. Send Handshake packet (id=0x00):
//!        - VarInt: protocol version (47 = MC 1.8; any reasonable
//!          value works — servers respond to a status request
//!          regardless of the announced protocol).
//!        - String: server address (as the client sees it).
//!        - u16: server port.
//!        - VarInt: next state (1 = status).
//!   3. Send Status Request packet (id=0x00, empty payload).
//!   4. Read response: VarInt length, VarInt id (0x00), VarInt
//!      JSON-string length, then JSON.
//!   5. Disconnect.
//!
//! The JSON includes:
//!   - version: { name, protocol }
//!   - players: { online, max, sample: [{name, id}] }
//!   - description: chat component for MOTD
//!
//! Sample limitations: large servers (Hypixel, etc.) only include ~10
//! randomly-rotated names per ping, so match rate is low. Smaller
//! servers return the full player list. That's a property of the
//! protocol, not our implementation.

use anyhow::{anyhow, Context, Result};
use serde::Serialize;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(4);
const READ_TIMEOUT: Duration = Duration::from_secs(6);
/// Hard cap on the JSON body — anything bigger is almost certainly a
/// malformed response or a server trying to flood the client.
const MAX_JSON_BYTES: usize = 64 * 1024;
const MAX_VARINT_LEN: usize = 5;

#[derive(Serialize, Clone, Debug)]
pub struct ServerStatus {
    pub online: bool,
    /// Server-reported MC version string (e.g. "Paper 1.21.11", "1.21.1").
    /// None when the server's response didn't include the field.
    pub version: Option<String>,
    /// Connection protocol number — corresponds to MC version.
    pub protocol: Option<u32>,
    pub online_players: Option<u32>,
    pub max_players: Option<u32>,
    /// Usernames the server included in its `players.sample[]` array.
    /// Most servers include up to 12 names. Hypixel-scale servers
    /// rotate the sample, so calling this repeatedly gets different
    /// names — that's expected.
    pub sample_names: Vec<String>,
}

/// Ping a server's status endpoint. Returns Ok(...) with `online=false`
/// when we couldn't reach it; only protocol-level corruption produces an
/// Err. Callers should treat "we couldn't ping" the same as "no friends
/// detected here" rather than spamming the user with errors.
pub async fn ping(host: &str, port: u16) -> Result<ServerStatus> {
    let conn = timeout(CONNECT_TIMEOUT, TcpStream::connect((host, port))).await;
    let mut stream = match conn {
        Ok(Ok(s)) => s,
        _ => {
            return Ok(ServerStatus {
                online: false,
                version: None,
                protocol: None,
                online_players: None,
                max_players: None,
                sample_names: Vec::new(),
            });
        }
    };
    stream.set_nodelay(true).ok();

    // --- handshake ---
    let mut payload = Vec::with_capacity(64);
    write_varint(&mut payload, 0); // packet id
    write_varint(&mut payload, 47); // protocol version — any reasonable value works
    write_string(&mut payload, host);
    payload.extend_from_slice(&port.to_be_bytes());
    write_varint(&mut payload, 1); // next state = status

    let mut framed = Vec::with_capacity(payload.len() + 8);
    write_varint(&mut framed, payload.len() as i32);
    framed.extend_from_slice(&payload);
    stream.write_all(&framed).await.context("writing handshake")?;

    // --- status request (empty packet, id=0) ---
    stream.write_all(&[1u8, 0u8]).await.context("writing status request")?;

    // --- read status response ---
    let total_len = timeout(READ_TIMEOUT, read_varint_async(&mut stream))
        .await
        .map_err(|_| anyhow!("server did not respond in time"))??;
    if total_len <= 0 || (total_len as usize) > MAX_JSON_BYTES + 16 {
        return Err(anyhow!("server returned implausible response length {total_len}"));
    }
    let mut body = vec![0u8; total_len as usize];
    timeout(READ_TIMEOUT, stream.read_exact(&mut body))
        .await
        .map_err(|_| anyhow!("server stalled mid-response"))?
        .context("reading status response body")?;

    let (packet_id, rest) = read_varint_slice(&body)?;
    if packet_id != 0 {
        return Err(anyhow!("unexpected response packet id {packet_id}"));
    }
    let (json_len, after_len) = read_varint_slice(rest)?;
    if (json_len as usize) > MAX_JSON_BYTES {
        return Err(anyhow!("server returned oversized JSON ({json_len} bytes)"));
    }
    let json_bytes = after_len
        .get(..json_len as usize)
        .ok_or_else(|| anyhow!("truncated JSON in status response"))?;

    let parsed: serde_json::Value = serde_json::from_slice(json_bytes)
        .context("parsing status JSON")?;

    let version = parsed
        .pointer("/version/name")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let protocol = parsed
        .pointer("/version/protocol")
        .and_then(|v| v.as_u64())
        .map(|n| n as u32);
    let online_players = parsed
        .pointer("/players/online")
        .and_then(|v| v.as_u64())
        .map(|n| n as u32);
    let max_players = parsed
        .pointer("/players/max")
        .and_then(|v| v.as_u64())
        .map(|n| n as u32);
    let mut sample_names = Vec::new();
    if let Some(arr) = parsed.pointer("/players/sample").and_then(|v| v.as_array()) {
        for entry in arr {
            if let Some(name) = entry.get("name").and_then(|n| n.as_str()) {
                if !name.is_empty() {
                    sample_names.push(name.to_string());
                }
            }
        }
    }

    Ok(ServerStatus {
        online: true,
        version,
        protocol,
        online_players,
        max_players,
        sample_names,
    })
}

// ---------------------------------------------------------------- varint helpers

fn write_varint(buf: &mut Vec<u8>, mut value: i32) {
    loop {
        let mut byte = (value & 0x7F) as u8;
        value = ((value as u32) >> 7) as i32;
        if value != 0 {
            byte |= 0x80;
            buf.push(byte);
        } else {
            buf.push(byte);
            return;
        }
    }
}

fn write_string(buf: &mut Vec<u8>, s: &str) {
    let bytes = s.as_bytes();
    write_varint(buf, bytes.len() as i32);
    buf.extend_from_slice(bytes);
}

async fn read_varint_async<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<i32> {
    let mut result: i32 = 0;
    for i in 0..MAX_VARINT_LEN {
        let mut byte = [0u8; 1];
        r.read_exact(&mut byte).await.context("reading varint")?;
        let b = byte[0];
        result |= ((b & 0x7F) as i32) << (7 * i);
        if (b & 0x80) == 0 {
            return Ok(result);
        }
    }
    Err(anyhow!("varint too long"))
}

fn read_varint_slice(data: &[u8]) -> Result<(i32, &[u8])> {
    let mut result: i32 = 0;
    for i in 0..MAX_VARINT_LEN {
        let b = *data.get(i).ok_or_else(|| anyhow!("truncated varint"))?;
        result |= ((b & 0x7F) as i32) << (7 * i);
        if (b & 0x80) == 0 {
            return Ok((result, &data[i + 1..]));
        }
    }
    Err(anyhow!("varint too long"))
}
