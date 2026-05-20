package app.shadowclient.chat.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wire-format helpers for the Shadow Chat relay.
 *
 * The protocol is intentionally untyped at the boundary — every frame
 * is just {@code { "op": "...", ...payload }} — so we don't pull in a
 * full marshalling layer. We hand-roll one method per op to keep
 * dependencies minimal (Gson is already available transitively).
 *
 * <p>Mirrors {@code chat-relay/src/types.ts}. If you add a new op,
 * add it on both sides.
 */
public final class Messages {

    private Messages() {}

    // ---------- outbound (client → relay) ----------

    /** Build the JSON for a chat send. */
    public static String encodeMsg(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "msg");
        o.addProperty("text", text);
        return o.toString();
    }

    /** Announce "I'm in voice and want to hear other people in voice". */
    public static String encodeVoiceJoin() {
        JsonObject o = new JsonObject();
        o.addProperty("op", "voice:join");
        return o.toString();
    }

    /** Announce "I'm leaving voice — stop sending me other people's audio". */
    public static String encodeVoiceLeave() {
        JsonObject o = new JsonObject();
        o.addProperty("op", "voice:leave");
        return o.toString();
    }

    // ---------- inbound (relay → client) ----------

    /** Sentinel for a parse failure or unknown op. */
    public static final ServerEvent UNKNOWN = new ServerEvent.Unknown();

    /** Tagged-union of everything the relay can send. */
    public sealed interface ServerEvent {
        record ChatMessage(String fromUuid, String name, String text, long ts) implements ServerEvent {}
        record Presence(List<User> users) implements ServerEvent {}
        record ErrorMsg(String message) implements ServerEvent {}
        /** List of UUIDs currently opted-in to voice on this channel. */
        record VoiceRoster(List<String> uuids) implements ServerEvent {}
        final class Unknown implements ServerEvent { Unknown() {} }

        record User(String uuid, String name) {}
    }

    /**
     * Parse a frame from the relay. Returns {@link #UNKNOWN} for
     * anything we can't make sense of — the caller should just drop
     * it. Throwing here would let one bad frame kill the socket loop.
     */
    public static ServerEvent decode(String raw) {
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonObject()) return UNKNOWN;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("op") || obj.get("op").isJsonNull()) return UNKNOWN;
            String op = obj.get("op").getAsString();
            return switch (op) {
                case "msg"          -> decodeChatMessage(obj);
                case "presence"     -> decodePresence(obj);
                case "error"        -> decodeError(obj);
                case "voice:roster" -> decodeVoiceRoster(obj);
                default             -> UNKNOWN;
            };
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    private static ServerEvent decodeChatMessage(JsonObject obj) {
        String from = stringOr(obj, "from", "");
        String name = stringOr(obj, "name", "?");
        String text = stringOr(obj, "text", "");
        long ts = obj.has("ts") && obj.get("ts").isJsonPrimitive()
                ? obj.get("ts").getAsLong() : System.currentTimeMillis();
        return new ServerEvent.ChatMessage(from, name, text, ts);
    }

    private static ServerEvent decodePresence(JsonObject obj) {
        List<ServerEvent.User> users = new ArrayList<>();
        if (obj.has("users") && obj.get("users").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("users");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject u = el.getAsJsonObject();
                String uuid = stringOr(u, "uuid", "");
                String name = stringOr(u, "name", "?");
                users.add(new ServerEvent.User(uuid, name));
            }
        }
        return new ServerEvent.Presence(Collections.unmodifiableList(users));
    }

    private static ServerEvent decodeError(JsonObject obj) {
        return new ServerEvent.ErrorMsg(stringOr(obj, "msg", "unknown error"));
    }

    private static ServerEvent decodeVoiceRoster(JsonObject obj) {
        List<String> uuids = new ArrayList<>();
        if (obj.has("uuids") && obj.get("uuids").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("uuids");
            for (JsonElement el : arr) {
                if (el.isJsonPrimitive()) uuids.add(el.getAsString());
            }
        }
        return new ServerEvent.VoiceRoster(Collections.unmodifiableList(uuids));
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }
}
