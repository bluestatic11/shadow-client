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
  /**
   * Discord-style voice opt-in flag. False by default; flipped by
   * voice:join / voice:leave ops. Outbound voice frames from this
   * member are forwarded only to OTHER members whose inVoice is also
   * true; if this member is false, their own voice uploads are
   * silently dropped (no DO ops burned on people who aren't in VC).
   */
  inVoice: boolean;
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

    const member: Member = { uuid, name, socket: serverSide, recentMsgs: [], inVoice: false };
    this.members.set(uuid, member);

    serverSide.addEventListener('message', (event) => {
      const data = event.data;
      if (typeof data === 'string') {
        // JSON control / chat frame.
        this.handleClientMessage(member, data);
      } else if (data instanceof ArrayBuffer) {
        // Binary frame — currently only push-to-talk voice (marker 0x01).
        // Forward as-is to other members with the sender UUID prepended.
        this.handleBinary(member, data);
      }
      // Anything else (Blob — Workers shouldn't deliver this) silently dropped.
    });
    const close = () => {
      // Only remove if this specific socket is still the member's
      // socket — handles the race where two close events fire after
      // a "replaced by new connection" replacement.
      const m = this.members.get(uuid);
      if (m && m.socket === serverSide) {
        const wasInVoice = m.inVoice;
        this.members.delete(uuid);
        this.broadcastPresence();
        // If they were in voice, the roster needs to update too.
        if (wasInVoice) this.broadcastVoiceRoster();
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

  /**
   * Handle a binary frame from the client. Currently we only know one
   * frame type — 0x01 = push-to-talk voice. The relay never decodes the
   * audio; we just prepend the verified sender UUID and forward to
   * every OTHER member of the room (the sender already hears themselves
   * via their own mic monitoring; echoing it back would feedback-loop).
   *
   * Frame format reminder:
   *   Uplink:    [0x01][opus_bytes …]
   *   Downlink:  [0x01][sender_uuid 16 bytes][opus_bytes …]
   */
  private handleBinary(from: Member, raw: ArrayBuffer) {
    if (raw.byteLength === 0 || raw.byteLength > 2048) return;
    const view = new Uint8Array(raw);
    const marker = view[0];
    if (marker !== 0x01) return;  // unknown frame type — silently drop

    const uuidBytes = uuidToBytes(from.uuid);
    if (!uuidBytes) return;  // member uuid not valid — shouldn't happen

    // Allocate the downlink buffer: 1 byte marker + 16 byte uuid + payload.
    const payload = view.subarray(1);
    const out = new Uint8Array(1 + 16 + payload.byteLength);
    out[0] = 0x01;
    out.set(uuidBytes, 1);
    out.set(payload, 17);

    // Voice fan-out:
    //  - sender must be inVoice (otherwise we drop their upload — they
    //    haven't opted in, no reason to broadcast their mic)
    //  - receiver must be inVoice (otherwise they don't hear voice)
    //  - sender themselves is always skipped (no self-echo)
    // The double gate means voice traffic only flows between members
    // who have BOTH explicitly joined the VC for this channel —
    // matches the Discord-style "join voice" UX.
    if (!from.inVoice) return;
    for (const m of this.members.values()) {
      if (m.uuid === from.uuid) continue;
      if (!m.inVoice) continue;
      try { m.socket.send(out.buffer); } catch (_) {}
    }
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

    if (msg.op === 'voice:join') {
      if (!from.inVoice) {
        from.inVoice = true;
        this.broadcastVoiceRoster();
      }
      return;
    }

    if (msg.op === 'voice:leave') {
      if (from.inVoice) {
        from.inVoice = false;
        this.broadcastVoiceRoster();
      }
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

  /** Authoritative snapshot of who's currently in the voice room.
   *  Sent on every voice:join / voice:leave AND whenever a member
   *  disconnects while inVoice (handled implicitly by the close
   *  handler calling broadcastPresence + broadcastVoiceRoster). */
  private broadcastVoiceRoster() {
    const members = [...this.members.values()]
      .filter(m => m.inVoice)
      .map(m => ({ uuid: m.uuid, name: m.name }));
    this.broadcast({ op: 'voice:roster', members });
  }
}

/**
 * Convert a dashed Minecraft UUID ("069a79f4-44e9-4726-a5be-fca90e38aaf5")
 * to its 16-byte big-endian binary form, matching java.util.UUID's
 * `getMostSignificantBits()` + `getLeastSignificantBits()` layout that
 * the mod uses to reconstruct the UUID on the receive side.
 *
 * Returns null on malformed input — callers drop the frame in that
 * case rather than guessing at member identity.
 */
function uuidToBytes(dashedUuid: string): Uint8Array | null {
  const hex = dashedUuid.replace(/-/g, '');
  if (hex.length !== 32) return null;
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i++) {
    const byte = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    if (Number.isNaN(byte)) return null;
    bytes[i] = byte;
  }
  return bytes;
}
