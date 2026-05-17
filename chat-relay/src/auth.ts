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
