// Token verification — proves the client owns the Microsoft / Minecraft
// account they claim. We do NOT trust the UUID the client sends; we
// derive it from a Microsoft access token by hitting Mojang's profile
// endpoint. That endpoint only returns 200 with a real profile when
// the token actually belongs to that account.
//
// Cost: one outbound fetch per WebSocket connection (i.e. once per game
// launch, not per message). Acceptable. Could be cached short-term with
// a Workers KV / DO storage layer if rate limits ever bite.

export interface Profile {
  /** Dashed Minecraft UUID, e.g. "069a79f4-44e9-4726-a5be-fca90e38aaf5" */
  uuid: string;
  /** Current Minecraft username (changes if user renames their account). */
  name: string;
}

const PROFILE_URL = 'https://api.minecraftservices.com/minecraft/profile';

/**
 * Verify a Microsoft / Minecraft access token. Returns the verified
 * profile (UUID + name) or null if the token is missing, expired, or
 * doesn't correspond to a Minecraft-owning account.
 *
 * The relay treats `null` as "reject this WebSocket upgrade with 401".
 */
/* eslint-disable @typescript-eslint/no-unused-vars */
// NOTE: superseded by verifyTokenOffline — kept for reference / a future
// world where the Worker can reach Mojang again (e.g. behind a proxy).
// Not called anywhere right now.
export async function verifyToken(token: string): Promise<Profile | null> {
  if (!token || token.length < 16) return null;

  let resp: Response;
  try {
    resp = await fetch(PROFILE_URL, {
      headers: {
        'Authorization': `Bearer ${token}`,
        // A descriptive UA helps if Mojang ever debugs rate-limit issues
        // on their end — easier than tracing back from raw IPs.
        'User-Agent': 'ShadowChat-Relay/1.0 (+https://shadowclient.app)',
      },
    });
  } catch {
    // Network blip or DNS hiccup. Treat as unauth so the client retries
    // on a future connection rather than getting stuck in a half-state.
    return null;
  }

  if (!resp.ok) return null;

  let data: unknown;
  try { data = await resp.json(); } catch { return null; }
  if (!data || typeof data !== 'object') return null;

  const d = data as { id?: unknown; name?: unknown };
  if (typeof d.id !== 'string' || typeof d.name !== 'string') return null;
  if (d.id.length !== 32 || !/^[0-9a-fA-F]+$/.test(d.id)) return null;

  return { uuid: dashUuid(d.id), name: d.name };
}

/** "069a79f444e94726a5befca90e38aaf5" → "069a79f4-44e9-4726-a5be-fca90e38aaf5" */
function dashUuid(id: string): string {
  return [
    id.slice(0, 8),
    id.slice(8, 12),
    id.slice(12, 16),
    id.slice(16, 20),
    id.slice(20, 32),
  ].join('-');
}

/**
 * Offline token verification — decode + validate WITHOUT calling Mojang.
 *
 * Why this exists: the relay runs on Cloudflare Workers, and Mojang/
 * Akamai's bot protection on api.minecraftservices.com blocks datacenter
 * egress IPs — so {@link verifyToken}'s per-connection profile fetch gets
 * a non-200 from inside the Worker and returns null, 401ing every token
 * even when it's perfectly valid (confirmed: same token returns HTTP 200
 * from a residential IP but the Worker's /ws upgrade is a hard 401). This
 * path removes the Mojang dependency entirely: a Minecraft access token
 * is an RS256 JWT that carries the player's own UUID in `profiles.mc`, so
 * we read identity straight from the token after checking it's a
 * well-formed, unexpired JWT.
 *
 * SECURITY NOTE: we do NOT verify the RSA signature — Mojang doesn't
 * publish a stable JWKS for these tokens, so there's no reliable key to
 * check against from a Worker. That means a determined attacker could
 * craft a JWT claiming any UUID and appear as that player in chat. For a
 * private friends relay this is an accepted tradeoff against chat being
 * completely down; the forged identity can do nothing but show up in the
 * room (no perms, no account access). The display name is supplied by
 * the client separately and is purely cosmetic.
 *
 * Returns {uuid, name:''} (the caller fills name from the query param) or
 * null to reject the upgrade with 401.
 */
export function verifyTokenOffline(token: string): Profile | null {
  if (!token || token.length < 16) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;

  let payload: { exp?: unknown; nbf?: unknown; profiles?: unknown };
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    // atob -> binary string -> bytes -> UTF-8 JSON (claims may be unicode).
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    payload = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }

  const now = Math.floor(Date.now() / 1000);
  if (typeof payload.exp === 'number' && payload.exp <= now) return null;
  // Allow 60s of clock skew on not-before.
  if (typeof payload.nbf === 'number' && payload.nbf > now + 60) return null;

  const profiles = payload.profiles as { mc?: unknown } | undefined;
  const mc = profiles?.mc;
  if (typeof mc !== 'string') return null;
  const hex = mc.replace(/-/g, '');
  if (hex.length !== 32 || !/^[0-9a-fA-F]+$/.test(hex)) return null;

  return { uuid: dashUuid(hex.toLowerCase()), name: '' };
}
