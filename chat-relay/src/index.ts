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

import { verifyToken } from './auth';

// Re-export the DO so Workers' runtime can find the class binding
// declared in wrangler.toml.
export { ChatRoom } from './chat-room';

interface Env {
  CHAT_ROOM: DurableObjectNamespace;
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

    if (url.pathname === '/') {
      return new Response(
        'Shadow Chat relay. Connect via WebSocket to /ws?token=...&channel=...\n',
        { headers: { 'content-type': 'text/plain' } },
      );
    }

    return new Response('not found', { status: 404 });
  },
} satisfies ExportedHandler<Env>;

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

  const profile = await verifyToken(token);
  if (!profile) {
    return new Response('invalid or expired Minecraft token', { status: 401 });
  }

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
