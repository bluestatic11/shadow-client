package app.shadowclient.chat;

import app.shadowclient.chat.config.AuthConfig;
import app.shadowclient.chat.config.ModConfig;
import app.shadowclient.chat.ipc.CommandFile;
import app.shadowclient.chat.relay.Messages;
import app.shadowclient.chat.relay.RelayClient;
import app.shadowclient.chat.ui.ChatOverlay;
import app.shadowclient.chat.ui.DiscordChatScreen;
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

    /**
     * Whether we've sent {@code voice:join} on the current connection.
     * Reset on every channel switch / disconnect — the relay forgets
     * opt-in state when the socket closes.
     */
    private volatile boolean inVoice = false;

    /**
     * Click-to-talk state from the chat screen's mic button. When true
     * we transmit continuously (hot mic) regardless of the PTT
     * keybind. Reset by clicking the mic again or by closing the chat
     * with no chat-screen click for safety; ORed with PTT keybind in
     * the tick handler so both inputs work.
     */
    private volatile boolean hotMicOn = false;

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

        // IPC drop-file watcher — lets the launcher signal us to (e.g.)
        // open the chat screen automatically on world load. See
        // CommandFile for the JSON schema.
        new CommandFile().register();

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
            inVoice = false;
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
            // Transmission is the union of two inputs:
            //   - PTT keybind held + no Screen open (so V-as-paste in
            //     our own chat input doesn't accidentally hot-mic);
            //   - the chat screen's click-to-talk Mic button toggle,
            //     which fires regardless of Screen state because the
            //     user chose it explicitly.
            boolean pttHeld = Keybinds.PUSH_TO_TALK.isDown() && client.screen == null;
            voice.setTransmitting(pttHeld || hotMicOn);
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

    /** Mod-owned saved config (groups, last active channel). */
    public ModConfig modConfig() { return modConfig; }

    /** In-memory UI state (history, presence, active channel). */
    public InputState uiState() { return uiState; }

    /** Launcher-written auth config (token, uuid, name). */
    public AuthConfig authConfig() { return auth; }

    /**
     * Return the host of the currently-joined MC server (e.g. "hypixel.net"),
     * or an empty string if not in multiplayer / on singleplayer. Used by the
     * Discord-style screen to render a meaningful channel name in the
     * sidebar's SERVER row instead of the cryptic "server" key.
     */
    public String currentServerHost() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return "";
        String c = deriveServerChannel(mc);
        if (c == null) return "";
        return c.startsWith("server:") ? c.substring("server:".length()) : c;
    }

    /** True when we've opted in to voice on the current channel. */
    public boolean isInVoice() { return inVoice; }

    /** True while the chat-screen Mic button is in its on (transmitting) state. */
    public boolean isHotMicOn() { return hotMicOn; }

    /**
     * Toggle the hot-mic state from the chat screen's Mic button. If
     * we're not already opted-in to voice, the toggle opt-ins first
     * so the very first frame actually reaches the relay. Auto-leaves
     * voice is NOT done on toggle-off — the user might still want
     * incoming voice from others.
     */
    public void toggleHotMic() {
        boolean turningOn = !hotMicOn;
        if (turningOn && !inVoice && relay.currentChannel() != null) {
            // Opt-in to voice first; otherwise our frames bounce off
            // the relay's inVoice-receivers gate.
            relay.joinVoice();
            inVoice = true;
            modConfig.setAutoJoinVoice(true);
        }
        hotMicOn = turningOn;
    }

    /**
     * Flip the voice opt-in state for the current connection. Sends
     * {@code voice:join} or {@code voice:leave} depending on direction.
     * Also persists the choice to {@link ModConfig} so we auto-rejoin
     * after a reconnect / channel switch. No-op if not connected —
     * the user gets a system-line nudge so they're not confused by
     * silent failure.
     */
    public void toggleVoiceOptIn() {
        if (relay.currentChannel() == null) {
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.system("Not connected — can't join voice yet"));
            return;
        }
        if (inVoice) {
            relay.leaveVoice();
            inVoice = false;
            modConfig.setAutoJoinVoice(false);
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.system("Left voice"));
        } else {
            relay.joinVoice();
            inVoice = true;
            modConfig.setAutoJoinVoice(true);
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.system("Joined voice — hold V to talk"));
        }
    }

    /**
     * Called by the fullscreen chat screen's "+ Create group" button. Mirrors
     * the {@code /group create} slash command but doesn't require text input —
     * spins up a default-named group with a fresh UUID and switches to it.
     */
    public void createGroupFromUi() {
        String label = "Group " + (modConfig.joinedGroups().size() + 1);
        String id = UUID.randomUUID().toString();
        ModConfig.Group g = new ModConfig.Group(id, label);
        modConfig.addGroup(g);
        uiState.append(uiState.activeChannel(),
                InputState.DisplayLine.system("Created group '" + label
                        + "'. Share this ID to invite: " + id));
        switchChannel("group:" + id);
    }

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
     * Called by {@link DiscordChatScreen} when the user hits Enter.
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
        //   2. Open the focused fullscreen chat screen.
        // A second press while focused (Screen is open) is impossible
        // because the keymapping is consumed by the Screen first, so
        // toggling off lives on Esc.
        if (!uiState.isOverlayVisible()) {
            uiState.setOverlayVisible(true);
        }
        // Only push a Screen if one isn't already open — avoid stacking.
        if (client.screen == null) {
            client.setScreen(new DiscordChatScreen(overlay));
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
        // The relay forgets voice opt-in on socket close — mirror that.
        inVoice = false;
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
                    // Auto-rejoin voice if the user previously opted in.
                    // Done after we've appended the "Joined" line so the
                    // ordering reads sensibly.
                    if (modConfig.autoJoinVoice()) {
                        relay.joinVoice();
                        inVoice = true;
                        uiState.append(activeChannelKey,
                                InputState.DisplayLine.system("Auto-rejoined voice"));
                    }
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
                        // Bump unread count if this channel isn't currently
                        // active. Skip our own echoed messages so the badge
                        // doesn't light up immediately after we sent something.
                        boolean isSelf = auth.isUsable()
                                && cm.fromUuid() != null
                                && cm.fromUuid().equalsIgnoreCase(auth.uuid());
                        if (!isSelf) uiState.incrementUnread(activeChannelKey);
                    } else if (event instanceof Messages.ServerEvent.Presence p) {
                        uiState.setPresence(activeChannelKey, p.users());
                    } else if (event instanceof Messages.ServerEvent.ErrorMsg em) {
                        uiState.append(activeChannelKey,
                                InputState.DisplayLine.error(em.message()));
                    } else if (event instanceof Messages.ServerEvent.VoiceRoster vr) {
                        uiState.setVoiceRoster(activeChannelKey, vr.uuids());
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
     * Slash commands handled entirely on the client.
     * <ul>
     *   <li>{@code /help} — print this list.</li>
     *   <li>{@code /group create <label>} — new group with random UUID; switches to it.</li>
     *   <li>{@code /group join <uuid>} — save and switch to an existing group.</li>
     *   <li>{@code /group leave} — leave the currently-active group.</li>
     *   <li>{@code /voice join} / {@code /voice leave} — toggle voice opt-in via keyboard.</li>
     *   <li>{@code /coords} — send your X/Y/Z to the active channel.</li>
     *   <li>{@code /whoami} — print uuid + name (debug aid).</li>
     * </ul>
     */
    private void handleSlashCommand(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length == 0) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "/help" -> printHelp();
            case "/group" -> handleGroupCommand(parts, line);
            case "/voice" -> handleVoiceCommand(parts);
            case "/whoami" -> {
                String msg = auth.isUsable()
                        ? auth.name() + " (" + auth.uuid() + ")"
                        : "not signed in";
                uiState.append(uiState.activeChannel(), InputState.DisplayLine.system(msg));
            }
            case "/coords" -> handleCoordsCommand();
            default -> uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.error("Unknown command: " + parts[0] + " — try /help"));
        }
    }

    private void printHelp() {
        String[] lines = {
                "Shadow Chat commands:",
                "/group create <label> — make a private group; share the ID it prints",
                "/group join <uuid>    — join an existing group",
                "/group leave          — leave the current group",
                "/voice join | leave   — opt in / out of voice on this channel",
                "/coords               — paste your X/Y/Z into chat",
                "/whoami               — show your UUID and display name",
                "/help                 — show this list",
        };
        for (String l : lines) {
            uiState.append(uiState.activeChannel(), InputState.DisplayLine.system(l));
        }
    }

    private void handleVoiceCommand(String[] parts) {
        if (parts.length < 2) {
            uiState.append(uiState.activeChannel(), InputState.DisplayLine.error(
                    "Usage: /voice join | /voice leave"));
            return;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> { if (!inVoice) toggleVoiceOptIn(); }
            case "leave" -> { if (inVoice) toggleVoiceOptIn(); }
            default -> uiState.append(uiState.activeChannel(), InputState.DisplayLine.error(
                    "Unknown /voice subcommand: " + parts[1]));
        }
    }

    /**
     * Send the player's current X/Y/Z to the active channel as a chat
     * message. Triggered by typing {@code /coords} (keyboard) or by
     * clicking the Coords chip in the input row (mouse, since v0.1.3).
     */
    private void handleCoordsCommand() {
        var coords = app.shadowclient.chat.cmd.CoordsHelper.currentCoords();
        if (coords.isEmpty()) {
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.error("No player loaded — /coords only works in-world."));
            return;
        }
        // If we're not connected to a channel, echo locally so the
        // user at least sees the format. Otherwise send for real.
        if (relay.currentChannel() == null) {
            uiState.append(uiState.activeChannel(),
                    InputState.DisplayLine.system("(would send) " + coords.get()));
            return;
        }
        relay.sendMessage(coords.get());
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
