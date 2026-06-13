// Shadow Chat relay — Worker entry point.
//
// The Worker is just a thin router that:
//   1. Authenticates incoming WebSocket upgrades (verifies the user's
//      Microsoft access token against Mojang's profile endpoint).
//   2. Forwards the upgraded socket to the right ChatRoom Durable
//      Object, identified by the `channel` query param.
//
// All the actual chat logic — presence, fan-out, rate-limit — lives in
// chat-room.ts inside the DO so per-channel state stays isolated and
// scales horizontally.

import { verifyTokenOffline } from './auth';

// Re-export the DOs so Workers' runtime can find the class bindings
// declared in wrangler.toml.
export { ChatRoom } from './chat-room';
export { PresenceHub } from './presence';

interface Env {
  CHAT_ROOM: DurableObjectNamespace;
  PRESENCE: DurableObjectNamespace;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);

    // Cheap liveness probe for monitoring / uptime checks.
    if (url.pathname === '/health') {
      return new Response('ok\n', { headers: { 'content-type': 'text/plain' } });
    }

    if (url.pathname === '/ws') {
      return handleWsUpgrade(req, env, url);
    }

    if (url.pathname === '/presence/heartbeat' && req.method === 'POST') {
      return handlePresenceHeartbeat(req, env);
    }
    if (url.pathname === '/presence/query' && req.method === 'POST') {
      return handlePresenceQuery(req, env);
    }

    if (url.pathname === '/') {
      return new Response(
        'Shadow Chat relay. Connect via WebSocket to /ws?token=...&channel=...\n',
        { headers: { 'content-type': 'text/plain' } },
      );
    }

    return new Response('not found', { status: 404 });
  },
} satisfies ExportedHandler<Env>;

/**
 * POST /presence/heartbeat
 * Headers: Authorization: Bearer <msa token>
 * Body:    { launcher, version?, server?, status }
 *
 * The relay verifies the token to learn the caller's verified UUID +
 * name (we don't trust the client to claim its own identity), then
 * forwards to the singleton PresenceHub DO.
 */
async function handlePresenceHeartbeat(req: Request, env: Env): Promise<Response> {
  const auth = req.headers.get('authorization') || '';
  const token = auth.startsWith('Bearer ') ? auth.slice('Bearer '.length) : '';
  if (!token) {
    return new Response(JSON.stringify({ error: 'missing bearer token' }), {
      status: 401, headers: { 'content-type': 'application/json' },
    });
  }
  // Offline verification (same reason as the /ws path — the Worker can't
  // reach Mojang). UUID comes from the verified token; name from the
  // client-supplied body (cosmetic).
  const profile = verifyTokenOffline(token);
  if (!profile) {
    return new Response(JSON.stringify({ error: 'invalid token' }), {
      status: 401, headers: { 'content-type': 'application/json' },
    });
  }
  let body: unknown = {};
  try { body = await req.json(); } catch {}
  if (!body || typeof body !== 'object') body = {};
  const bodyName = (body as { name?: unknown }).name;
  const merged = {
    ...(body as Record<string, unknown>),
    uuid: profile.uuid,
    name: typeof bodyName === 'string' && bodyName.trim()
      ? bodyName.trim().slice(0, 32)
      : 'Player-' + profile.uuid.slice(0, 8),
  };
  const stub = env.PRESENCE.get(env.PRESENCE.idFromName('presence-hub'));
  return stub.fetch('https://do/heartbeat', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(merged),
  });
}

/**
 * POST /presence/query
 * Body:    { uuids: ["..."] }
 *
 * Returns presence entries for any of the queried UUIDs that have
 * heartbeat'd in the last 5 minutes. Unauthenticated — the result is
 * just "is this player playing MC right now" which the player can
 * already discover by joining their server, so leaking it doesn't
 * matter. Cuts a round-trip per friends-panel refresh.
 */
async function handlePresenceQuery(req: Request, env: Env): Promise<Response> {
  const stub = env.PRESENCE.get(env.PRESENCE.idFromName('presence-hub'));
  return stub.fetch('https://do/query', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: await req.text(),
  });
}

async function handleWsUpgrade(req: Request, env: Env, url: URL): Promise<Response> {
  // Reject anything that isn't an actual WebSocket upgrade — catches
  // someone hitting /ws in a browser by mistake.
  if (req.headers.get('Upgrade') !== 'websocket') {
    return new Response('expected WebSocket upgrade', { status: 426 });
  }

  const token = url.searchParams.get('token');
  const channel = url.searchParams.get('channel');

  if (!token) return new Response('missing token query param', { status: 400 });
  if (!channel) return new Response('missing channel query param', { status: 400 });

  // Validate channel name — only allow our known prefixes plus a small
  // character set. Stops someone from creating arbitrary DO names that
  // could collide with future internal channels.
  if (!/^(server|group|dm):[A-Za-z0-9._:\-]{1,128}$/.test(channel)) {
    return new Response('invalid channel name', { status: 400 });
  }

  // Offline verification — read identity from the token's own signed
  // claims instead of calling Mojang, because the Worker's egress IP is
  // blocked by Mojang's bot protection (see verifyTokenOffline). This is
  // what unbreaks chat: the old per-connection Mojang fetch 401'd every
  // token from inside the Worker.
  const profile = verifyTokenOffline(token);
  if (!profile) {
    return new Response('invalid or expired Minecraft token', { status: 401 });
  }
  // The token carries the UUID but not the display name, so take it from
  // the client (cosmetic only — identity is the verified uuid). Old mods
  // that don't send `name` fall back to a short uuid tag.
  const rawName = url.searchParams.get('name');
  profile.name = rawName && rawName.trim()
    ? rawName.trim().slice(0, 32)
    : 'Player-' + profile.uuid.slice(0, 8);

  // Forward to the channel's Durable Object. Cloudflare's idFromName
  // gives us a stable DO instance keyed by the channel string, so any
  // two clients addressing the same channel meet in the same DO.
  const id = env.CHAT_ROOM.idFromName(channel);
  const stub = env.CHAT_ROOM.get(id);

  // The DO trusts the uuid/name in the forwarded URL because only
  // this Worker can call it — Durable Object bindings aren't exposed
  // to the public internet directly.
  const doUrl = new URL('https://chat-room/');
  doUrl.searchParams.set('uuid', profile.uuid);
  doUrl.searchParams.set('name', profile.name);

  return stub.fetch(doUrl.toString(), {
    headers: { 'Upgrade': 'websocket' },
  });
}
