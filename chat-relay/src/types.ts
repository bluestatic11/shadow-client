// Wire-format types for Shadow Chat. The mod (Java) and the relay
// (TypeScript) speak this shared JSON protocol over WebSocket.
//
// Both directions are framed as { op, ...payload } so adding new message
// kinds is just "add a new op". Unknown ops are silently ignored on
// both sides for forward compatibility.

/** Anything the *client* (the in-game mod) sends to the relay. */
export type ClientMessage =
  // Send a message into the room this socket is connected to.
  // Length-capped server-side to prevent abuse.
  | { op: 'msg'; text: string }
  // Optional: server-side typing indicator. May be ignored in MVP.
  | { op: 'typing' };

/** Anything the *relay* sends back to the connected mod. */
export type ServerMessage =
  // A chat message from someone in the room (could be yourself — the
  // relay echoes your own messages back so the mod doesn't need to
  // optimistically render and then reconcile).
  | { op: 'msg';
      from: string;       // UUID of sender
      name: string;       // display name of sender at send time
      text: string;       // already trimmed + length-capped server-side
      ts: number;         // unix millis, server time
    }
  // Membership change. Sent whenever someone joins/leaves the room so
  // the mod can keep its "who's here" list fresh.
  | { op: 'presence';
      users: { uuid: string; name: string }[];
    }
  // Per-socket error (rate-limit hit, bad op, etc.). The connection
  // stays open — only the offending message is rejected.
  | { op: 'error'; msg: string };
