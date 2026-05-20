// Presence Durable Object.
//
// One global DO instance (DO name = "presence-hub") stores recent
// "I'm playing Minecraft" heartbeats from every Shadow Client launcher.
// Friends panel polls /presence/query with a list of friend UUIDs and
// gets back which of them are currently online + what server they're
// on + what launcher they're using.
//
// Storage: in-memory only. Cloudflare DOs are sticky to one location
// and survive ~30s of idle before hibernation, so an in-memory Map is
// fine for short-lived presence. Anything older than ENTRY_TTL_MS
// (5 min) is considered stale and filtered out on read.

/** Maximum time we trust a presence entry without a fresh heartbeat. */
const ENTRY_TTL_MS = 5 * 60_000;

/** Per-UUID rate limit on heartbeat writes. */
const MIN_HEARTBEAT_INTERVAL_MS = 10_000;

/** Cap on the size of a single /presence/query request. */
const MAX_QUERY_UUIDS = 256;

export interface PresenceEntry {
  /** Dashed Minecraft UUID. */
  uuid: string;
  /** Display name at the time of the last heartbeat. */
  name: string;
  /** Launcher publishing the heartbeat, e.g. "shadow-client". */
  launcher: string;
  /** Launcher version, e.g. "0.3.53". Optional. */
  version?: string;
  /** Current MC server host the user is on, e.g. "hypixel.net". */
  server?: string;
  /** Status keyword — "playing" while MC is up, "idle" while in launcher only. */
  status: 'playing' | 'idle';
  /** Epoch-ms when this entry was last updated. */
  lastSeenAt: number;
}

interface StoredEntry extends PresenceEntry {
  /** Internal — when we last accepted a write, used for rate-limit gate. */
  _writeAt: number;
}

export class PresenceHub {
  private entries = new Map<string, StoredEntry>();

  // Cloudflare DO required constructor signature.
  constructor(_state: DurableObjectState, _env: unknown) {}

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname.endsWith('/heartbeat') && req.method === 'POST') {
      return this.handleHeartbeat(req);
    }
    if (url.pathname.endsWith('/query') && req.method === 'POST') {
      return this.handleQuery(req);
    }
    return new Response('not found', { status: 404 });
  }

  private async handleHeartbeat(req: Request): Promise<Response> {
    let body: unknown;
    try { body = await req.json(); } catch { return jsonError(400, 'invalid json'); }
    if (!body || typeof body !== 'object') return jsonError(400, 'invalid body');
    const b = body as {
      uuid?: unknown; name?: unknown; launcher?: unknown;
      version?: unknown; server?: unknown; status?: unknown;
    };
    if (typeof b.uuid !== 'string' || !isUuid(b.uuid)) return jsonError(400, 'bad uuid');
    if (typeof b.name !== 'string' || !b.name) return jsonError(400, 'bad name');
    if (typeof b.launcher !== 'string' || !b.launcher) return jsonError(400, 'bad launcher');
    const status = b.status === 'playing' ? 'playing' : 'idle';
    const now = Date.now();
    const existing = this.entries.get(b.uuid);
    if (existing && now - existing._writeAt < MIN_HEARTBEAT_INTERVAL_MS) {
      // Drop spam-rate writes silently — caller still gets a 200 so
      // their polling doesn't backoff.
      return jsonOk({ accepted: false, retryAfterMs: MIN_HEARTBEAT_INTERVAL_MS });
    }
    const entry: StoredEntry = {
      uuid: b.uuid,
      name: clip(b.name, 32),
      launcher: clip(b.launcher, 32),
      version: typeof b.version === 'string' ? clip(b.version, 24) : undefined,
      server: typeof b.server === 'string' ? clip(b.server, 64) : undefined,
      status,
      lastSeenAt: now,
      _writeAt: now,
    };
    this.entries.set(b.uuid, entry);

    // Opportunistic eviction — keep the map small so cold-start
    // reads stay fast.
    if (this.entries.size > 4096) this.evictStale(now);

    return jsonOk({ accepted: true });
  }

  private async handleQuery(req: Request): Promise<Response> {
    let body: unknown;
    try { body = await req.json(); } catch { return jsonError(400, 'invalid json'); }
    if (!body || typeof body !== 'object') return jsonError(400, 'invalid body');
    const b = body as { uuids?: unknown };
    if (!Array.isArray(b.uuids)) return jsonError(400, 'bad uuids');
    const uuids = b.uuids.filter((u): u is string => typeof u === 'string' && isUuid(u))
                          .slice(0, MAX_QUERY_UUIDS);
    const now = Date.now();
    const out: PresenceEntry[] = [];
    for (const u of uuids) {
      const e = this.entries.get(u);
      if (!e) continue;
      if (now - e.lastSeenAt > ENTRY_TTL_MS) continue;
      const { _writeAt: _w, ...pub } = e;
      out.push(pub);
    }
    return jsonOk({ entries: out });
  }

  private evictStale(now: number) {
    for (const [uuid, e] of this.entries) {
      if (now - e.lastSeenAt > ENTRY_TTL_MS) this.entries.delete(uuid);
    }
  }
}

function isUuid(s: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
}

function clip(s: string, max: number): string {
  return s.length <= max ? s : s.slice(0, max);
}

function jsonOk(payload: unknown): Response {
  return new Response(JSON.stringify(payload), {
    headers: { 'content-type': 'application/json' },
  });
}

function jsonError(code: number, msg: string): Response {
  return new Response(JSON.stringify({ error: msg }), {
    status: code,
    headers: { 'content-type': 'application/json' },
  });
}
