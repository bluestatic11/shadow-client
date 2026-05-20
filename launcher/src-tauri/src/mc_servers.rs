//! Read the user's local `servers.dat` files (per-profile) to learn
//! every Minecraft server they've ever added in the Multiplayer menu.
//!
//! Used by the friends panel to auto-populate the server-ping list so
//! we can detect friends on shared servers without the user having to
//! manually paste host names. Friends usually play on the same servers
//! as the user, so the user's own server list is the best free hint.
//!
//! Vanilla MC writes `servers.dat` as **uncompressed NBT** (Named
//! Binary Tag) — no gzip wrapper. Structure:
//!
//! ```text
//! TAG_Compound("")
//!   TAG_List("servers")  // elements are TAG_Compound
//!     TAG_Compound("")
//!       TAG_String("name") = "Hypixel"
//!       TAG_String("ip")   = "mc.hypixel.net"
//!       ...
//!     ...
//! ```
//!
//! We walk the file recursively, find the "servers" list, and pull
//! `name` + `ip` out of each entry. Everything else (icon, hidden flag,
//! acceptTextures, etc.) is skipped. The `ip` field is `host` or
//! `host:port` — we split on the last colon.

use anyhow::{anyhow, Context, Result};
use serde::Serialize;
use std::collections::HashSet;
use std::path::Path;

#[derive(Serialize, Clone, Debug)]
pub struct McServerEntry {
    pub name: String,
    pub host: String,
    /// 0 means "use default 25565". MC stores port inline with the IP
    /// when non-default; we split it out here so the JS side doesn't
    /// have to re-parse.
    pub port: u16,
    /// Best-effort identifier for the launcher profile the entry came
    /// from. Helps the front-end deduplicate when the same server
    /// shows up in multiple profiles.
    pub source: String,
}

/// Walk all per-profile servers.dat files under `game_dir/profiles/`
/// plus the legacy `game_dir/servers.dat` if it exists, deduplicate by
/// host:port, and return the merged list.
pub fn collect_all(game_dir: &Path) -> Result<Vec<McServerEntry>> {
    let mut out: Vec<McServerEntry> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    let push = |entries: Vec<McServerEntry>, out: &mut Vec<McServerEntry>, seen: &mut HashSet<String>| {
        for e in entries {
            let key = format!("{}:{}", e.host.to_lowercase(), e.port);
            if seen.insert(key) {
                out.push(e);
            }
        }
    };
    // Legacy/global path.
    let legacy = game_dir.join("servers.dat");
    if legacy.exists() {
        if let Ok(entries) = read_servers_dat(&legacy, "global") {
            push(entries, &mut out, &mut seen);
        }
    }
    // Per-profile paths under <game_dir>/profiles/<profile>/servers.dat.
    let profiles_dir = game_dir.join("profiles");
    if profiles_dir.is_dir() {
        if let Ok(read) = std::fs::read_dir(&profiles_dir) {
            for entry in read.flatten() {
                let path = entry.path().join("servers.dat");
                if !path.is_file() { continue; }
                let profile = entry.file_name().to_string_lossy().to_string();
                if let Ok(entries) = read_servers_dat(&path, &profile) {
                    push(entries, &mut out, &mut seen);
                }
            }
        }
    }
    Ok(out)
}

/// Parse a single servers.dat file. Returns Err only on outright I/O
/// failure or hard-corrupt NBT; missing/empty `servers` list yields
/// Ok(vec![]).
pub fn read_servers_dat(path: &Path, source: &str) -> Result<Vec<McServerEntry>> {
    let bytes = std::fs::read(path)
        .with_context(|| format!("reading {}", path.display()))?;
    let mut p = NbtParser::new(&bytes);

    // Root is a named TAG_Compound; in vanilla the name is empty.
    let tag = p.read_u8()?;
    if tag != TAG_COMPOUND {
        return Err(anyhow!("expected root TAG_Compound, got {tag}"));
    }
    let _name = p.read_string()?;

    let mut entries = Vec::new();
    while let Some((tag, name)) = p.read_named_tag()? {
        if tag == TAG_LIST && name == "servers" {
            entries = read_server_list(&mut p, source)?;
        } else {
            p.skip_payload(tag)?;
        }
    }
    Ok(entries)
}

fn read_server_list(p: &mut NbtParser, source: &str) -> Result<Vec<McServerEntry>> {
    let elem_tag = p.read_u8()?;
    let count = p.read_i32()? as usize;
    if elem_tag != TAG_COMPOUND || count == 0 {
        // Empty list or weird shape — bail gracefully.
        return Ok(Vec::new());
    }
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let mut name: Option<String> = None;
        let mut ip: Option<String> = None;
        while let Some((tag, k)) = p.read_named_tag()? {
            match (tag, k.as_str()) {
                (TAG_STRING, "name") => name = Some(p.read_string()?),
                (TAG_STRING, "ip")   => ip = Some(p.read_string()?),
                _ => p.skip_payload(tag)?,
            }
        }
        if let Some(ip_raw) = ip {
            let (host, port) = split_host_port(&ip_raw);
            if !host.is_empty() {
                out.push(McServerEntry {
                    name: name.unwrap_or_else(|| host.clone()),
                    host,
                    port,
                    source: source.to_string(),
                });
            }
        }
    }
    Ok(out)
}

fn split_host_port(ip: &str) -> (String, u16) {
    let s = ip.trim().to_lowercase();
    if let Some(idx) = s.rfind(':') {
        // Guard against accidental IPv6 literals (they'd have multiple
        // colons) — only treat as host:port when there's exactly one.
        if s[..idx].contains(':') {
            return (s, 0);
        }
        if let Ok(p) = s[idx + 1..].parse::<u16>() {
            return (s[..idx].to_string(), p);
        }
    }
    (s, 0)
}

// ---------------------------------------------------------- NBT parser

const TAG_END: u8 = 0;
const TAG_BYTE: u8 = 1;
const TAG_SHORT: u8 = 2;
const TAG_INT: u8 = 3;
const TAG_LONG: u8 = 4;
const TAG_FLOAT: u8 = 5;
const TAG_DOUBLE: u8 = 6;
const TAG_BYTE_ARRAY: u8 = 7;
const TAG_STRING: u8 = 8;
const TAG_LIST: u8 = 9;
const TAG_COMPOUND: u8 = 10;
const TAG_INT_ARRAY: u8 = 11;
const TAG_LONG_ARRAY: u8 = 12;

struct NbtParser<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> NbtParser<'a> {
    fn new(buf: &'a [u8]) -> Self { Self { buf, pos: 0 } }

    fn read_bytes(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.pos + n > self.buf.len() {
            return Err(anyhow!("unexpected EOF reading {n} bytes at offset {}", self.pos));
        }
        let out = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(out)
    }
    fn read_u8(&mut self) -> Result<u8>  { Ok(self.read_bytes(1)?[0]) }
    fn read_i16(&mut self) -> Result<i16> { let b = self.read_bytes(2)?; Ok(i16::from_be_bytes([b[0], b[1]])) }
    fn read_i32(&mut self) -> Result<i32> { let b = self.read_bytes(4)?; Ok(i32::from_be_bytes([b[0], b[1], b[2], b[3]])) }
    fn read_i64(&mut self) -> Result<i64> { let b = self.read_bytes(8)?; Ok(i64::from_be_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]])) }
    fn read_u16(&mut self) -> Result<u16> { let b = self.read_bytes(2)?; Ok(u16::from_be_bytes([b[0], b[1]])) }

    fn read_string(&mut self) -> Result<String> {
        let len = self.read_u16()? as usize;
        let bytes = self.read_bytes(len)?;
        Ok(String::from_utf8_lossy(bytes).into_owned())
    }

    /// Read one named tag. Returns None when we hit TAG_End.
    fn read_named_tag(&mut self) -> Result<Option<(u8, String)>> {
        let tag = self.read_u8()?;
        if tag == TAG_END { return Ok(None); }
        let name = self.read_string()?;
        Ok(Some((tag, name)))
    }

    /// Skip the payload of a tag (we've already consumed its type byte
    /// and name). Used to walk past everything we don't care about.
    fn skip_payload(&mut self, tag: u8) -> Result<()> {
        match tag {
            TAG_BYTE => { self.read_bytes(1)?; }
            TAG_SHORT => { self.read_bytes(2)?; }
            TAG_INT | TAG_FLOAT => { self.read_bytes(4)?; }
            TAG_LONG | TAG_DOUBLE => { self.read_bytes(8)?; }
            TAG_STRING => { let n = self.read_u16()? as usize; self.read_bytes(n)?; }
            TAG_BYTE_ARRAY => { let n = self.read_i32()? as usize; self.read_bytes(n)?; }
            TAG_INT_ARRAY => { let n = self.read_i32()? as usize; self.read_bytes(n * 4)?; }
            TAG_LONG_ARRAY => { let n = self.read_i32()? as usize; self.read_bytes(n * 8)?; }
            TAG_LIST => {
                let elem_tag = self.read_u8()?;
                let count = self.read_i32()? as usize;
                for _ in 0..count { self.skip_payload(elem_tag)?; }
            }
            TAG_COMPOUND => {
                while let Some((t, _)) = self.read_named_tag()? { self.skip_payload(t)?; }
            }
            _ => return Err(anyhow!("unknown NBT tag type {tag}")),
        }
        Ok(())
    }
}
