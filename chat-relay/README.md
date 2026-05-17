# shadow-chat-relay

WebSocket relay backing Shadow Chat — the in-game chat overlay shipped
inside the Shadow Client launcher's bundled `shadow-chat` Fabric mod.

## What it does

- Authenticates clients by their Microsoft / Minecraft access token
  against Mojang's profile endpoint. The relay never trusts a UUID the
  client claims — it derives the UUID from the verified token.
- Routes each authenticated WebSocket to a per-channel Durable Object.
  - `server:<mc-server-host>` — auto-joined by every Shadow Client user
    currently on that MC server.
  - `group:<random-128-bit-id>` — private group. The ID itself is the
    shared secret; share the join link with friends to invite them.
- Inside each Durable Object: tracks presence, fans messages out to
  everyone in the room, rate-limits message rate per user.

No global database. No message history (yet). No server-to-server
chat. Each channel is its own isolated DO.

## Setup

```bash
cd chat-relay
npm install
npx wrangler login         # one-time, links to your Cloudflare account
npx wrangler deploy        # ships to https://shadow-chat-relay.<account>.workers.dev
```

For local dev:

```bash
npx wrangler dev
# Worker now reachable at ws://localhost:8787/ws
```

## Wire protocol

Connect: `wss://<your-worker>.workers.dev/ws?token=<msa-access-token>&channel=server:hypixel.net`

Once upgraded, both sides exchange JSON frames:

```jsonc
// client → relay
{ "op": "msg", "text": "hello" }

// relay → client
{ "op": "msg", "from": "<uuid>", "name": "Notch",
  "text": "hello", "ts": 1736901234567 }
{ "op": "presence", "users": [{ "uuid": "...", "name": "..." }, ...] }
{ "op": "error", "msg": "..." }
```

See `src/types.ts` for the canonical definition shared with the Java
mod.

## Cost ballpark

Cloudflare's free tier covers:
- 100 000 Worker requests / day (one per WebSocket upgrade)
- 1 million Durable Object requests / month
- Unlimited socket lifetime under the daily request cap

That's enough for low-thousands of concurrent Shadow Client users
chatting all day before paid tier kicks in (~$5/mo at modest scale).
