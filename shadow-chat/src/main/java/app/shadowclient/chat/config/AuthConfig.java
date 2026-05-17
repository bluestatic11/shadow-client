package app.shadowclient.chat.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read-only view of the auth file the launcher writes for us.
 *
 * The launcher logs the user into Microsoft and dumps the resulting
 * access token (plus the user's Minecraft profile) into
 * {@code <gameDir>/shadow-chat-auth.json}. We never write this file —
 * the launcher owns it, and if it's missing or unusable we just
 * sit dormant (overlay still toggles but shows a "not signed in"
 * placeholder).
 *
 * <p>Schema (mirrors what the launcher emits):
 * <pre>{@code
 * {
 *   "relay_url": "wss://shadow-chat-relay.bluestatic11.workers.dev",
 *   "token":     "<MSA access token, or null/empty if offline mode>",
 *   "uuid":      "<dashed uuid>",
 *   "name":      "<MC display name>"
 * }
 * }</pre>
 */
public final class AuthConfig {

    /** Filename inside the Fabric game dir. */
    public static final String FILENAME = "shadow-chat-auth.json";

    private final String relayUrl;
    private final String token;
    private final String uuid;
    private final String name;

    private AuthConfig(String relayUrl, String token, String uuid, String name) {
        this.relayUrl = relayUrl;
        this.token = token;
        this.uuid = uuid;
        this.name = name;
    }

    public String relayUrl() { return relayUrl; }
    public String token() { return token; }
    public String uuid() { return uuid; }
    public String name() { return name; }

    /**
     * True when we have everything we need to connect.
     * Anything missing (file absent, token null/empty, fields missing) → false.
     */
    public boolean isUsable() {
        return relayUrl != null && !relayUrl.isBlank()
                && token != null && !token.isBlank()
                && uuid != null && !uuid.isBlank()
                && name != null && !name.isBlank();
    }

    /** Sentinel "no auth" instance — keeps callers null-free. */
    public static AuthConfig empty() {
        return new AuthConfig(null, null, null, null);
    }

    /**
     * Load the auth file. Returns {@link #empty()} on any failure —
     * we deliberately swallow exceptions because the launcher might
     * still be writing the file at startup, or the user might just
     * be running the mod standalone without the launcher.
     */
    public static AuthConfig load() {
        Path path = FabricLoader.getInstance().getGameDir().resolve(FILENAME);
        if (!Files.isRegularFile(path)) {
            return empty();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String relayUrl = stringOrNull(obj, "relay_url");
            String token    = stringOrNull(obj, "token");
            String uuid     = stringOrNull(obj, "uuid");
            String name     = stringOrNull(obj, "name");
            return new AuthConfig(relayUrl, token, uuid, name);
        } catch (Exception e) {
            // Malformed JSON, IO error, etc. Treat as "not signed in".
            return empty();
        }
    }

    private static String stringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }
}
