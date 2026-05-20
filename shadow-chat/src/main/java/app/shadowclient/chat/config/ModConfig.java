package app.shadowclient.chat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable, mod-owned config persisted at
 * {@code <gameDir>/shadow-chat-config.json}. Tracks groups the user has
 * joined and which channel they last had active.
 *
 * <p>Loaded once at startup, mutated in-memory, and rewritten on every
 * change via {@link #save()}. The file is tiny so we don't bother with
 * dirty-flag debouncing.
 */
public final class ModConfig {

    public static final String FILENAME = "shadow-chat-config.json";

    /** "server" → the auto-joined server channel; "group:<uuid>" → a saved group. */
    public static final String CHANNEL_SERVER = "server";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A group the user has created or been invited to. */
    public static final class Group {
        public String id;   // The UUID that doubles as the shared join secret.
        public String name; // User-friendly label (defaults to "Group N" or whatever they pass).

        public Group() {} // for Gson

        public Group(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Group g)) return false;
            return Objects.equals(id, g.id);
        }
        @Override public int hashCode() { return Objects.hashCode(id); }
    }

    private final List<Group> joinedGroups = new ArrayList<>();
    private String activeChannel = CHANNEL_SERVER;
    /**
     * Whether the user has opted in to voice. Mirrored across sessions so
     * that joining a server and pressing PTT just works without re-clicking
     * the Join Voice button every time.
     */
    private boolean autoJoinVoice = false;

    private ModConfig() {}

    public List<Group> joinedGroups() { return joinedGroups; }
    public String activeChannel() { return activeChannel; }
    public boolean autoJoinVoice() { return autoJoinVoice; }

    public void setActiveChannel(String channel) {
        this.activeChannel = channel;
        save();
    }

    public void setAutoJoinVoice(boolean v) {
        this.autoJoinVoice = v;
        save();
    }

    /** Add a group if its id isn't already saved. Returns true if newly added. */
    public boolean addGroup(Group g) {
        if (g == null || g.id == null || g.id.isBlank()) return false;
        for (Group existing : joinedGroups) {
            if (Objects.equals(existing.id, g.id)) return false;
        }
        joinedGroups.add(g);
        save();
        return true;
    }

    /** Remove a group by id. Returns true if removed. */
    public boolean removeGroup(String id) {
        boolean removed = joinedGroups.removeIf(g -> Objects.equals(g.id, id));
        if (removed) {
            // If we removed the active group, fall back to server channel
            // so the overlay doesn't point at a stale ID.
            if (("group:" + id).equals(activeChannel)) {
                activeChannel = CHANNEL_SERVER;
            }
            save();
        }
        return removed;
    }

    public Group findGroup(String id) {
        for (Group g : joinedGroups) {
            if (Objects.equals(g.id, id)) return g;
        }
        return null;
    }

    private static Path path() {
        return FabricLoader.getInstance().getGameDir().resolve(FILENAME);
    }

    /** Load from disk; on any failure return a fresh empty config (which will be saved on first mutation). */
    public static ModConfig load() {
        ModConfig cfg = new ModConfig();
        Path p = path();
        if (!Files.isRegularFile(p)) return cfg;
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            if (obj.has("active_channel") && !obj.get("active_channel").isJsonNull()) {
                cfg.activeChannel = obj.get("active_channel").getAsString();
            }
            if (obj.has("auto_join_voice") && !obj.get("auto_join_voice").isJsonNull()) {
                cfg.autoJoinVoice = obj.get("auto_join_voice").getAsBoolean();
            }
            if (obj.has("joined_groups") && obj.get("joined_groups").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("joined_groups");
                for (var el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject g = el.getAsJsonObject();
                    String id = g.has("id") && !g.get("id").isJsonNull() ? g.get("id").getAsString() : null;
                    String name = g.has("name") && !g.get("name").isJsonNull() ? g.get("name").getAsString() : null;
                    if (id != null && !id.isBlank()) {
                        cfg.joinedGroups.add(new Group(id, name == null ? id : name));
                    }
                }
            }
        } catch (Exception ignored) {
            // Treat as empty; the next save() will overwrite.
        }
        return cfg;
    }

    /**
     * Persist the current state. Errors are logged but not thrown —
     * losing the config across a restart is annoying but not fatal,
     * and we never want config IO to crash the game.
     */
    public void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("active_channel", activeChannel);
            root.addProperty("auto_join_voice", autoJoinVoice);
            JsonArray arr = new JsonArray();
            for (Group g : joinedGroups) {
                JsonObject o = new JsonObject();
                o.addProperty("id", g.id);
                o.addProperty("name", g.name);
                arr.add(o);
            }
            root.add("joined_groups", arr);
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best-effort: nothing else we can do here. Mod keeps running.
        }
    }
}
