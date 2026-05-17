// ChatRoom — one Durable Object instance per channel.
//
// A "channel" is just a string the parent Worker uses to address the DO:
//   - "server:hypixel.net"  → everyone on that MC server right now
//   - "group:<uuid>"        → private group (the UUID IS the secret;
//                              anyone with it can join, generated as a
//                              128-bit random ID so unguessable in practice)
//   - "dm:<uuidA>:<uuidB>"  → future: 1-to-1 DM (uuids sorted to dedupe)
//
// Cloudflare hashes the channel name to a stable DO instance, so two
// sockets opening the same channel get routed to the same DO and can
// see each other's messages. No global state in the Worker, no DB —
// presence + fan-out happen inside the DO itself.

import type { ClientMessage, ServerMessage } from './types';

interface Member {
  uuid: string;
  name: string;
  socket: WebSocket;
  /** Sliding-window message timestamps for rate limiting. */
  recentMsgs: number[];
}

/** Cap each message at this many chars after trim. Stops "wall of text"
 *  spam from one user crowding everyone else's chat. */
const MAX_MSG_LEN = 500;

/** Rate limit: at most this many messages per RATE_WINDOW_MS per user. */
const RATE_LIMIT = 5;
const RATE_WINDOW_MS = 3000;

export class ChatRoom implements DurableObject {
  /** uuid → member. Map gives us O(1) join/leave + iteration for fan-out. */
  private members = new Map<string, Member>();
  private state: DurableObjectState;

  constructor(state: DurableObjectState, _env: unknown) {
    this.state = state;
  }

  /**
   * The parent Worker forwards an authenticated WebSocket upgrade here
   * with `?uuid=...&name=...` in the URL. We trust those because the
   * Worker validated the auth token before constructing this URL.
   */
  async fetch(req: Request): Promise<Response> {
    if (req.headers.get('Upgrade') !== 'websocket') {
      return new Response('expected WebSocket upgrade', { status: 426 });
    }

    const url = new URL(req.url);
    const uuid = url.searchParams.get('uuid');
    const name = url.searchParams.get('name');
    if (!uuid || !name) {
      return new Response('missing uuid/name (worker bug)', { status: 500 });
    }

    const pair = new WebSocketPair();
    const clientSide = pair[0];
    const serverSide = pair[1];
    serverSide.accept();

    // If the same UUID was already connected, close the old socket
    // first. Lets a user reconnect after a network hiccup without
    // their old ghost lingering in the presence list.
    const old = this.members.get(uuid);
    if (old) {
      try { old.socket.close(1000, 'replaced by new connection'); } catch (_) {}
      this.members.delete(uuid);
    }

    const member: Member = { uuid, name, socket: serverSide, recentMsgs: [] };
    this.members.set(uuid, member);

    serverSide.addEventListener('message', (event) => {
      const raw = typeof event.data === 'string' ? event.data : '';
      this.handleClientMessage(member, raw);
    });
    const close = () => {
      // Only remove if this specific socket is still the member's
      // socket — handles the race where two close events fire after
      // a "replaced by new connection" replacement.
      const m = this.members.get(uuid);
      if (m && m.socket === serverSide) {
        this.members.delete(uuid);
        this.broadcastPresence();
      }
    };
    serverSide.addEventListener('close', close);
    serverSide.addEventListener('error', close);

    // Broadcast presence so existing members see the new arrival.
    // Also lets the new joiner see who's already here on their first
    // presence event.
    this.broadcastPresence();

    return new Response(null, { status: 101, webSocket: clientSide });
  }

  private handleClientMessage(from: Member, raw: string) {
    let msg: ClientMessage;
    try {
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object') return;
      msg = parsed as ClientMessage;
    } catch {
      return;
    }

    if (msg.op === 'msg') {
      const text = String(msg.text ?? '').trim().slice(0, MAX_MSG_LEN);
      if (!text) return;

      if (!this.checkRateLimit(from)) {
        this.sendTo(from, {
          op: 'error',
          msg: `slow down — max ${RATE_LIMIT} messages per ${RATE_WINDOW_MS / 1000}s`,
        });
        return;
      }

      const out: ServerMessage = {
        op: 'msg',
        from: from.uuid,
        name: from.name,
        text,
        ts: Date.now(),
      };
      this.broadcast(out);
      return;
    }

    // Unknown op → silently drop. Forward-compat with future client
    // versions that emit ops this build doesn't understand.
  }

  /** Returns true if the message is allowed; false if rate limit hit. */
  private checkRateLimit(m: Member): boolean {
    const now = Date.now();
    const cutoff = now - RATE_WINDOW_MS;
    m.recentMsgs = m.recentMsgs.filter(ts => ts > cutoff);
    if (m.recentMsgs.length >= RATE_LIMIT) return false;
    m.recentMsgs.push(now);
    return true;
  }

  private sendTo(m: Member, msg: ServerMessage) {
    const data = JSON.stringify(msg);
    try { m.socket.send(data); } catch (_) {}
  }

  private broadcast(msg: ServerMessage) {
    const data = JSON.stringify(msg);
    for (const m of this.members.values()) {
      try { m.socket.send(data); } catch (_) {}
    }
  }

  private broadcastPresence() {
    const users = [...this.members.values()].map(m => ({
      uuid: m.uuid,
      name: m.name,
    }));
    this.broadcast({ op: 'presence', users });
  }
}
