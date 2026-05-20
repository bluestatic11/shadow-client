package app.shadowclient.chat.ipc;

import app.shadowclient.chat.ShadowChatClient;
import app.shadowclient.chat.ui.DiscordChatScreen;
import app.shadowclient.chat.ui.InputState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Polled IPC channel from the launcher to the mod.
 *
 * <p>The launcher writes a one-shot JSON command to
 * {@code <gameDir>/shadow-chat-command.json}. This watcher runs every
 * 10 client ticks (~½s) and, when the file exists:
 * <ol>
 *   <li>Parses the JSON ({@code {"action": "...", "target": "...?",
 *       "created_at_ms": ...}}).</li>
 *   <li>Drops the file unconditionally so stale commands from a
 *       crashed prior session don't fire on next launch.</li>
 *   <li>Defers stale commands (older than 60s) without acting.</li>
 *   <li>Runs the action when the client is in a state that can honor
 *       it (e.g. open-chat needs the player to be loaded).</li>
 * </ol>
 *
 * <p>Actions:
 * <ul>
 *   <li>{@code open-chat} — pop the Discord-style fullscreen chat
 *       screen open on the active channel.</li>
 *   <li>{@code open-chat-with} — same, plus append a system line
 *       calling out the target friend so the user knows who they
 *       came in to chat with.</li>
 * </ul>
 *
 * <p>Polled rather than file-watched because Fabric Loader doesn't
 * expose a portable file-watch API and the launcher writes once per
 * launch — half-second poll is cheap and good enough.
 */
public final class CommandFile {

    private static final Logger LOG = LoggerFactory.getLogger("shadow-chat/cmd-file");

    public static final String FILENAME = "shadow-chat-command.json";

    /** Tick cadence between poll attempts. ~½s at 20 ticks/sec. */
    private static final int POLL_TICKS = 10;

    /** Discard commands older than this — they probably came from a
     *  prior session that died before the mod could pick them up. */
    private static final long STALE_MS = 60_000L;

    private int tickCounter = 0;

    /** Pending command we couldn't execute yet (waiting on the player
     *  to load in). Replayed every tick until honored or stale. */
    private Pending pending = null;

    private static final class Pending {
        final String action;
        final String target;
        final long createdAtMs;
        Pending(String action, String target, long createdAtMs) {
            this.action = action;
            this.target = target;
            this.createdAtMs = createdAtMs;
        }
        boolean isStale() {
            return createdAtMs > 0 && System.currentTimeMillis() - createdAtMs > STALE_MS;
        }
    }

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        // Always try to honor a deferred command first — most likely
        // the player wasn't ready last tick and is ready now.
        if (pending != null) {
            if (pending.isStale()) {
                pending = null;
            } else if (tryExecute(client, pending)) {
                pending = null;
            }
        }

        if (++tickCounter < POLL_TICKS) return;
        tickCounter = 0;

        Path file = FabricLoader.getInstance().getGameDir().resolve(FILENAME);
        if (!Files.isRegularFile(file)) return;

        // Read + delete first so a parse failure or crash doesn't loop
        // forever on the same poison-pill command.
        String body;
        try {
            body = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("couldn't read {}: {}", file, e.toString());
            tryDelete(file);
            return;
        }
        tryDelete(file);

        Pending cmd = parse(body);
        if (cmd == null) return;
        if (cmd.isStale()) {
            LOG.debug("ignoring stale IPC command (created {} ms ago)",
                    System.currentTimeMillis() - cmd.createdAtMs);
            return;
        }
        if (!tryExecute(client, cmd)) {
            // Defer for replay on a future tick.
            pending = cmd;
        }
    }

    private static Pending parse(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            String action = obj.has("action") && !obj.get("action").isJsonNull()
                    ? obj.get("action").getAsString() : null;
            if (action == null || action.isBlank()) return null;
            String target = obj.has("target") && !obj.get("target").isJsonNull()
                    ? obj.get("target").getAsString() : null;
            long created = obj.has("created_at_ms") && obj.get("created_at_ms").isJsonPrimitive()
                    ? obj.get("created_at_ms").getAsLong() : 0L;
            return new Pending(action, target, created);
        } catch (Exception e) {
            LOG.warn("couldn't parse IPC command: {}", e.toString());
            return null;
        }
    }

    /** Attempt to execute the command. Returns false to request a retry. */
    private static boolean tryExecute(Minecraft client, Pending cmd) {
        // We need a level loaded and no other Screen already up — the
        // user might still be at the title screen, or have a different
        // modal open we shouldn't preempt.
        if (client == null) return false;
        if (client.level == null || client.player == null) return false;
        if (client.screen != null) return false;

        ShadowChatClient sc;
        try { sc = ShadowChatClient.get(); }
        catch (IllegalStateException ignored) { return false; }
        if (sc == null) return false;

        switch (cmd.action) {
            case "open-chat" -> {
                sc.uiState().setOverlayVisible(true);
                client.setScreen(new DiscordChatScreen(null));
                return true;
            }
            case "open-chat-with" -> {
                sc.uiState().setOverlayVisible(true);
                if (cmd.target != null && !cmd.target.isBlank()) {
                    sc.uiState().append(sc.uiState().activeChannel(),
                            InputState.DisplayLine.system(
                                    "Launcher opened chat to find " + cmd.target
                                    + " — try /group create to invite them"));
                }
                client.setScreen(new DiscordChatScreen(null));
                return true;
            }
            default -> {
                LOG.warn("unknown IPC action: {}", cmd.action);
                return true; // drop unknown so we don't retry forever
            }
        }
    }

    private static void tryDelete(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException e) { LOG.warn("couldn't delete {}: {}", file, e.toString()); }
    }
}
