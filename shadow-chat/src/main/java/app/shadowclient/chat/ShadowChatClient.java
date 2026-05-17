package app.shadowclient.chat;

import app.shadowclient.chat.config.AuthConfig;
import app.shadowclient.chat.config.ModConfig;
import app.shadowclient.chat.relay.Messages;
import app.shadowclient.chat.relay.RelayClient;
import app.shadowclient.chat.ui.ChatInputScreen;
import app.shadowclient.chat.ui.ChatOverlay;
import app.shadowclient.chat.ui.InputState;
import app.shadowclient.chat.ui.Keybinds;
import app.shadowclient.chat.voice.VoiceController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;

/**
 * Top-level entry point for the Shadow Chat mod.
 *
 * <p>Coordinates the moving pieces:
 * <ul>
 *   <li>Loads {@link AuthConfig} (launcher-written) and {@link ModConfig} (mod-owned) at startup.</li>
 *   <li>Owns the singleton {@link RelayClient} and routes its callbacks
 *       back to {@link InputState} via the Minecraft client thread.</li>
 *   <li>Listens for join/disconnect events on multiplayer servers and
 *       opens/closes WebSocket connections accordingly.</li>
 *   <li>Drives the hotkey → overlay focus flow.</li>
 *   <li>Parses slash commands typed in the overlay's input field.</li>
 * </ul>
 *
 * <p>Exposed as a process-wide singleton because Fabric mod entrypoints
 * are constructed by the loader and several leaf classes (the overlay,
 * the input screen) need a stable handle without dragging the instance
 * through their constructors.
 */
public final class ShadowChatClient implements ClientModInitializer {

    private static final Logger LOG = LoggerFactory.getLogger("shadow-chat");

    private static ShadowChatClient instance;
    /** @return the global singleton, initialized once Fabric calls {@link #onInitializeClient()}. */
    public static ShadowChatClient get() {
        if (instance == null) throw new IllegalStateException("ShadowChatClient not yet initialized");
        return instance;
    }

    private AuthConfig auth;
    private ModConfig modConfig;
    private InputState uiState;
    private ChatOverlay overlay;
    private RelayClient relay;
    private VoiceController voice;

    /** Last status line to show in the overlay banner (auth state, connection state, …). */
    private volatile String statusLine = "";

    @Override
    public void onInitializeClient() {
        instance = this;
        LOG.info("Shadow Chat initializing…");

        // Configs first — overlay rendering and connect attempts both
        // depend on having these loaded.
        this.auth = AuthConfig.load();
        this.modConfig = ModConfig.load();
        this.uiState = new InputState();
        this.uiState.setActiveChannel(modConfig.activeChannel());

        this.overlay = new ChatOverlay(uiState, modConfig);
        this.overlay.register();

        Keybinds.register();

        this.relay = new RelayClient(auth);
        // Voice runs alongside text on the same WebSocket. Initialized
        // here so audio hardware is open before the first channel join.
        // If mic/output aren't available the controller logs once and
        // stays a no-op; nothing else in the mod cares.
        this.voice = new VoiceController();
        this.voice.init(relay);

        // Set initial banner so the overlay says something useful even
        // before the player joins a server.
        if (!auth.isUsable()) {
            statusLine = "Not signed in - chat disabled";
        } else {
            statusLine = "Signed in as " + auth.name();
        }

        // ---- lifecycle wiring ----

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            // Nothing to do beyond what we already did in onInitializeClient.
            // Hook is here so a future implementation can defer heavy
            // init until the window is up without changing call sites.
            LOG.info("Shadow Chat ready (auth={})", auth.isUsable() ? "yes" : "no");
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            // Best-effort cleanup — Loom dev runs sometimes leave
            // sockets dangling otherwise.
            try { relay.disconnect(); } catch (Exception ignored) {}
            try { voice.shutdown(); } catch (Exception ignored) {}
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            String channel = deriveServerChannel(client);
            if (channel == null) {
                statusLine = "Singleplayer - server chat unavailable";
                return;
            }
            if (!auth.isUsable()) {
                // Don't even try connecting; keep the banner clear about why.
                statusLine = "Not signed in - chat disabled";
                return;
            }
            // Auto-join the server channel on every join. If the user
            // had a group selected last session we still connect to
            // the server channel first; the chip switcher lets them
            // jump back.
            connectTo(ModConfig.CHANNEL_SERVER);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            statusLine = "Disconnected from server";
            try { relay.disconnect(); } catch (Exception ignored) {}
        });

        // Hotkey poll. consumeClick() drains the queued GLFW press
        // events so we react exactly once per tap. The PTT key is a
        // hold-style binding so we read isDown() each tick rather than
        // draining click events.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (Keybinds.TOGGLE_CHAT.consumeClick()) {
                handleToggleHotkey(client);
            }
            // Push-to-talk: gate transmission on key-held state.
            // Suppress while any Screen is open so the player can use
            // the V key for paste in the chat input field without
            // accidentally going hot-mic. Note that we don't suppress
            // for our own ChatInputScreen because typing there also
            // wants Ctrl+V to be a paste, not a PTT trigger.
            boolean held = Keybinds.PUSH_TO_TALK.isDown() && client.screen == null;
            voice.setTransmitting(held);
        });

        LOG.info("Shadow Chat initialized");
    }

    // ---------------------------------------------------------------- exposed to UI

    /** Banner line shown at the top of the overlay. May be empty. */
    public String statusLine() { return statusLine; }

    /** Voice subsystem (capture/playback) — used by the overlay for the
     *  "speaking" indicator and PTT hint. May be null very briefly
     *  during startup before {@link #onInitializeClient()} finishes. */
    public VoiceController voice() { return voice; }

    /** Look up a display name for a UUID from the active channel's
     *  presence list. Returns the short uuid prefix if not found —
     *  better than rendering a 36-char UUID in the speaker indicator. */
    public String displayNameForUuid(UUID uuid) {
        if (uuid == null) return "?";
        String key = uuid.toString();
        for (Messages.ServerEvent.User u : uiState.presenceFor(uiState.activeChannel())) {
            if (key.equalsIgnoreCase(u.uuid())) return u.name();
        }
        return key.substring(0, 8);
    }

    /**
     * Called by {@link ChatInputScreen} when the user hits Enter.
     * Splits slash commands off; anything else is a chat message.
     */
    public void submitInput(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return;

        if (text.startsWith("/")) {
            handleSlashCommand(text);
            return;
        }

        // Plain chat — forward to relay if connected, else echo a system error.
        if (relay.currentChannel() == null) {
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.error("Not connected — message not sent"));
            return;
        }
        relay.sendMessage(text);
    }

    // ---------------------------------------------------------------- internals

    private void handleToggleHotkey(Minecraft client) {
        // Two-step semantics so the player gets both halves of "open
        // AND focus" from one keypress, matching the task spec:
        //   1. Make the overlay visible if it wasn't.
        //   2. Open the focused input screen.
        // A second press while focused (Screen is open) is impossible
        // because the keymapping is consumed by the Screen first, so
        // toggling off lives on Esc.
        if (!uiState.isOverlayVisible()) {
            uiState.setOverlayVisible(true);
        }
        // Only push a Screen if one isn't already open — avoid stacking.
        if (client.screen == null) {
            client.setScreen(new ChatInputScreen(overlay));
        }
    }

    /**
     * Derive a server channel string from the current connection.
     * Returns {@code null} for singleplayer / LAN where there's no
     * meaningful "everyone on this server" cohort.
     */
    private static String deriveServerChannel(Minecraft client) {
        // Singleplayer integrated server doesn't count.
        if (client.hasSingleplayerServer() || client.isLocalServer()) return null;

        ServerData sd = client.getCurrentServer();
        if (sd == null || sd.ip == null || sd.ip.isBlank()) return null;
        if (sd.isLan()) return null;

        String host = sd.ip.trim().toLowerCase(Locale.ROOT);
        // Strip port. Naive split on the last ':' so IPv6 literals
        // would break, but the relay's channel regex doesn't allow
        // colons in the host part anyway — IPv6 servers are vanishingly
        // rare and would need URL-style bracketing to be unambiguous.
        int colon = host.lastIndexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        return "server:" + host;
    }

    /**
     * Open (or switch to) a channel. The {@code channelKey} is what
     * {@link InputState} and {@link ModConfig} use:
     * <ul>
     *   <li>{@code "server"} — the auto-joined server channel.</li>
     *   <li>{@code "group:<uuid>"} — a saved group.</li>
     * </ul>
     */
    public void switchChannel(String channelKey) {
        uiState.setActiveChannel(channelKey);
        modConfig.setActiveChannel(channelKey);
        connectTo(channelKey);
    }

    /**
     * Resolve a channelKey to the wire channel string and open a
     * connection. No-op if not signed in or we can't derive a server
     * channel right now.
     */
    private void connectTo(String channelKey) {
        if (!auth.isUsable()) {
            statusLine = "Not signed in - chat disabled";
            return;
        }

        String wireChannel;
        if (ModConfig.CHANNEL_SERVER.equals(channelKey)) {
            String derived = deriveServerChannel(Minecraft.getInstance());
            if (derived == null) {
                statusLine = "Singleplayer - server chat unavailable";
                try { relay.disconnect(); } catch (Exception ignored) {}
                return;
            }
            wireChannel = derived;
        } else if (channelKey != null && channelKey.startsWith("group:")) {
            wireChannel = channelKey; // already in wire form
        } else {
            statusLine = "Unknown channel: " + channelKey;
            return;
        }

        statusLine = "Connecting to " + wireChannel + "…";
        final String activeChannelKey = channelKey;
        // Channel changed → drop any decoder state for speakers from
        // the old channel so their voice doesn't bleed in.
        if (voice != null) voice.onChannelChange();
        relay.connect(wireChannel, new RelayClient.EventSink() {
            @Override
            public void onConnected(String ch) {
                runOnClient(() -> {
                    statusLine = "Connected: " + ch;
                    uiState.append(activeChannelKey,
                            InputState.DisplayLine.system("Joined " + ch));
                });
            }

            @Override
            public void onDisconnected(String ch, String reason) {
                runOnClient(() -> {
                    statusLine = "Disconnected: " + (reason == null ? "" : reason);
                    uiState.append(activeChannelKey,
                            InputState.DisplayLine.system("Left " + ch
                                    + (reason == null || reason.isBlank() ? "" : " (" + reason + ")")));
                });
            }

            @Override
            public void onEvent(Messages.ServerEvent event) {
                runOnClient(() -> {
                    if (event instanceof Messages.ServerEvent.ChatMessage cm) {
                        uiState.append(activeChannelKey,
                                InputState.DisplayLine.chat(cm.name(), cm.text(), cm.ts()));
                    } else if (event instanceof Messages.ServerEvent.Presence p) {
                        uiState.setPresence(activeChannelKey, p.users());
                    } else if (event instanceof Messages.ServerEvent.ErrorMsg em) {
                        uiState.append(activeChannelKey,
                                InputState.DisplayLine.error(em.message()));
                    }
                    // Unknown events: silently dropped (forward-compat).
                });
            }

            @Override
            public void onVoice(UUID sender, byte[] opus) {
                // Voice frames are hot-path; skip the client-thread
                // bounce and hand straight to the mixer (which runs on
                // its own thread). UI side reads currentSpeakers()
                // from render code which already hops to the right
                // thread.
                if (voice != null) voice.onVoiceFrame(sender, opus);
            }
        });
    }

    /**
     * Bounce a runnable onto the Minecraft client thread. All our
     * callbacks from the relay arrive on the HTTP executor pool, and
     * touching UI state from off-thread is asking for racy renders
     * and the occasional ConcurrentModificationException.
     */
    private static void runOnClient(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) { r.run(); return; }
        mc.execute(r);
    }

    // ---------------------------------------------------------------- slash commands

    /**
     * Slash commands handled entirely on the client:
     * <ul>
     *   <li>{@code /group create <label>} — new group with random UUID; switches to it.</li>
     *   <li>{@code /group join <uuid>} — save & switch to an existing group.</li>
     *   <li>{@code /group leave} — leave the currently-active group.</li>
     *   <li>{@code /whoami} — print uuid + name (debug aid).</li>
     * </ul>
     */
    private void handleSlashCommand(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length == 0) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "/group" -> handleGroupCommand(parts, line);
            case "/whoami" -> {
                String msg = auth.isUsable()
                        ? auth.name() + " (" + auth.uuid() + ")"
                        : "not signed in";
                uiState.append(uiState.activeChannel(), InputState.DisplayLine.system(msg));
            }
            default -> uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.error("Unknown command: " + parts[0]));
        }
    }

    private void handleGroupCommand(String[] parts, String fullLine) {
        if (parts.length < 2) {
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.error("Usage: /group create <label> | /group join <uuid> | /group leave"));
            return;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                // Label is everything after "/group create "
                String label;
                if (parts.length >= 3) {
                    int idx = fullLine.indexOf(parts[1]) + parts[1].length();
                    label = fullLine.substring(idx).trim();
                    if (label.isEmpty()) label = "Untitled group";
                } else {
                    label = "Group " + (modConfig.joinedGroups().size() + 1);
                }
                String id = UUID.randomUUID().toString();
                ModConfig.Group g = new ModConfig.Group(id, label);
                modConfig.addGroup(g);
                uiState.append(uiState.activeChannel(),
                        InputState.DisplayLine.system("Created group '" + label
                                + "'. Share this ID to invite: " + id));
                switchChannel("group:" + id);
            }
            case "join" -> {
                if (parts.length < 3 || parts[2].isBlank()) {
                    uiState.append(uiState.activeChannel(),
                            InputState.DisplayLine.error("Usage: /group join <uuid>"));
                    return;
                }
                String id = parts[2].trim();
                // Best-effort uuid sanity check — we don't reject if it
                // doesn't parse because the relay's channel regex is the
                // ultimate authority, but warn the user.
                try { UUID.fromString(id); } catch (IllegalArgumentException ex) {
                    uiState.append(uiState.activeChannel(),
                            InputState.DisplayLine.error("That doesn't look like a group ID, but trying anyway…"));
                }
                modConfig.addGroup(new ModConfig.Group(id, "Group " + (modConfig.joinedGroups().size() + 1)));
                switchChannel("group:" + id);
            }
            case "leave" -> {
                String active = uiState.activeChannel();
                if (active == null || !active.startsWith("group:")) {
                    uiState.append(uiState.activeChannel(),
                            InputState.DisplayLine.error("Not in a group right now"));
                    return;
                }
                String id = active.substring("group:".length());
                modConfig.removeGroup(id);
                uiState.clear(active);
                uiState.append(ModConfig.CHANNEL_SERVER,
                        InputState.DisplayLine.system("Left group " + id));
                switchChannel(ModConfig.CHANNEL_SERVER);
            }
            default -> uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.error("Unknown /group subcommand: " + parts[1]));
        }
    }
}
