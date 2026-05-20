package app.shadowclient.chat.relay;

import app.shadowclient.chat.config.AuthConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin wrapper around {@link java.net.http.WebSocket} that owns one
 * connection at a time to the Shadow Chat relay.
 *
 * <p>The mod creates a single {@code RelayClient} at startup and asks
 * it to {@link #connect(String, EventSink) connect} to a channel
 * whenever the active channel changes. Switching channels closes the
 * old socket first — the server enforces one-channel-per-socket.
 *
 * <p>All callbacks fire on the HTTP client's executor thread; the
 * caller (overlay/UI) must hop to the Minecraft client thread before
 * touching game state.
 */
public final class RelayClient {

    /** Callbacks invoked as the relay pushes events. Implementers must be thread-safe. */
    public interface EventSink {
        /** Connection established and ready to send. */
        default void onConnected(String channel) {}
        /** Connection closed (clean or unclean). {@code reason} is best-effort. */
        default void onDisconnected(String channel, String reason) {}
        /** A frame arrived — already parsed. */
        void onEvent(Messages.ServerEvent event);
        /**
         * A binary voice frame arrived from another participant on the
         * same channel. {@code sender} is the verified UUID stamped by
         * the relay; {@code opus} is the raw codec packet. Default no-op
         * so callers that don't care about voice (older sinks, tests)
         * keep working unchanged.
         */
        default void onVoice(UUID sender, byte[] opus) {}
    }

    /** Voice frame marker byte — first byte of every binary frame on the wire. */
    public static final byte FRAME_VOICE = 0x01;

    /** Hard cap on inbound binary frame size; anything bigger is silently dropped. */
    private static final int MAX_BINARY_FRAME_BYTES = 2048;

    private final HttpClient http;
    private final AuthConfig auth;

    /**
     * Single in-flight socket. Holding {@code null} means we're idle.
     * We keep this as an atomic reference so a stray onClose for an
     * already-replaced socket doesn't clobber the new one.
     */
    private final AtomicReference<Session> current = new AtomicReference<>();

    /**
     * True when the user (or app shutdown) explicitly closed the
     * connection. Stops {@link #scheduleReconnect} from re-establishing
     * a connection the user already walked away from. Reset to false
     * on every explicit {@link #connect}.
     */
    private final AtomicBoolean manuallyDisconnected = new AtomicBoolean(false);

    public RelayClient(AuthConfig auth) {
        this.auth = auth;
        // Plain HttpClient with a sane connect timeout. The default
        // executor is a small ForkJoinPool which is fine for our
        // single-socket workload.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Current channel string, or null if disconnected. */
    public String currentChannel() {
        Session s = current.get();
        return s == null ? null : s.channel;
    }

    /**
     * Open (or replace) the active connection to the given channel.
     * Always closes the previous socket first.
     *
     * <p>Returns a future that completes when the upgrade succeeds.
     * Failures are surfaced via {@link EventSink#onDisconnected(String, String)}.
     */
    public CompletableFuture<Void> connect(String channel, EventSink sink) {
        // An explicit connect always re-enables auto-reconnect. The
        // reconnect path itself calls back into this method, so this
        // also handles "stay on this channel until the user says
        // otherwise" semantics across retries.
        manuallyDisconnected.set(false);
        closeCurrent("switching channel");

        if (!auth.isUsable()) {
            // Caller should have checked, but be defensive.
            sink.onDisconnected(channel, "not signed in");
            return CompletableFuture.completedFuture(null);
        }

        URI uri;
        try {
            uri = buildUri(auth.relayUrl(), auth.token(), channel);
        } catch (Exception e) {
            sink.onDisconnected(channel, "bad relay URL: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }

        Session session = new Session(channel, sink);
        // Stake our claim before kicking off the async build — if a
        // second connect() races us we want one of them to "win" and
        // the other to be cleanly torn down.
        current.set(session);

        return http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, session.listener)
                .thenAccept(ws -> {
                    session.socket = ws;
                    sink.onConnected(channel);
                })
                .exceptionally(err -> {
                    // Connection failed — clear if we're still current.
                    current.compareAndSet(session, null);
                    sink.onDisconnected(channel, rootCause(err));
                    return null;
                });
    }

    /**
     * Send a chat message on the active connection. Silently dropped
     * if no connection is open — the overlay should already gate this
     * via its "not connected" state.
     */
    public void sendMessage(String text) {
        Session s = current.get();
        if (s == null || s.socket == null) return;
        try {
            s.socket.sendText(Messages.encodeMsg(text), true);
        } catch (Exception ignored) {
            // Send failed — the listener's onClose/onError will kick in.
        }
    }

    /**
     * Opt this connection in to voice. Until called, the relay won't
     * fan out anyone else's voice frames to us (and ours don't reach
     * anyone unless they've also opted in). Silently dropped if no
     * connection is open.
     */
    public void joinVoice() {
        Session s = current.get();
        if (s == null || s.socket == null) return;
        try { s.socket.sendText(Messages.encodeVoiceJoin(), true); } catch (Exception ignored) {}
    }

    /** Opposite of {@link #joinVoice} — leave the voice room without disconnecting. */
    public void leaveVoice() {
        Session s = current.get();
        if (s == null || s.socket == null) return;
        try { s.socket.sendText(Messages.encodeVoiceLeave(), true); } catch (Exception ignored) {}
    }

    /**
     * Send a binary voice frame on the active connection. Uplink wire
     * format is {@code [FRAME_VOICE][opus...]} — the relay verifies the
     * sender from the socket's auth and prepends the sender UUID before
     * fanning out to peers, so we deliberately do NOT include any UUID
     * here.
     *
     * <p>Silently dropped if no connection is open or the opus payload
     * is empty / oversize. We don't surface errors here — voice frames
     * are inherently lossy and the next packet is along in 20 ms.
     */
    public void sendVoice(byte[] opus) {
        if (opus == null || opus.length == 0) return;
        if (opus.length + 1 > MAX_BINARY_FRAME_BYTES) return;
        Session s = current.get();
        if (s == null || s.socket == null) return;
        ByteBuffer buf = ByteBuffer.allocate(opus.length + 1);
        buf.put(FRAME_VOICE);
        buf.put(opus);
        buf.flip();
        try {
            s.socket.sendBinary(buf, true);
        } catch (Exception ignored) {
            // Best-effort; the listener's onClose/onError handles real failures.
        }
    }

    /** Close the current socket if any. Safe to call multiple times.
     *  Suppresses any pending auto-reconnect — the user (or shutdown
     *  hook) explicitly wants out, we don't second-guess that. */
    public void disconnect() {
        manuallyDisconnected.set(true);
        closeCurrent("client closed");
    }

    /**
     * Fire-and-forget single-retry reconnect. If the relay's still down
     * after 3 seconds we give up; the next user action (switching
     * channel, joining a server) will retry from scratch. Covers the
     * common case — relay restart, transient network blip — without
     * looping indefinitely on a hard failure (banned, no internet).
     */
    private void scheduleReconnect(String channel, EventSink sink) {
        if (manuallyDisconnected.get()) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(3000); }
            catch (InterruptedException e) { return; }
            // Re-check intent — user may have disconnected during the
            // 3-second wait, or another connect() may have already
            // landed a new session.
            if (manuallyDisconnected.get()) return;
            if (current.get() != null) return;
            connect(channel, sink);
        }, "shadow-chat-reconnect");
        t.setDaemon(true);
        t.start();
    }

    private void closeCurrent(String reason) {
        Session s = current.getAndSet(null);
        if (s != null && s.socket != null) {
            try {
                s.socket.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception ignored) {
                // Best-effort. abort() is the hard kill.
                try { s.socket.abort(); } catch (Exception ignored2) {}
            }
        }
    }

    // ---------- internals ----------

    private static URI buildUri(String relayUrl, String token, String channel) {
        // relayUrl may already have a trailing slash; normalize.
        String base = relayUrl;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // The relay expects ws://...; if the launcher gave us http(s)
        // by mistake, swap the scheme — the rest of the URI is identical.
        if (base.startsWith("https://")) base = "wss://" + base.substring("https://".length());
        else if (base.startsWith("http://")) base = "ws://" + base.substring("http://".length());

        String t = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String c = URLEncoder.encode(channel, StandardCharsets.UTF_8);
        return URI.create(base + "/ws?token=" + t + "&channel=" + c);
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }

    /** One open WebSocket plus the listener bound to its lifecycle. */
    private final class Session {
        final String channel;
        final EventSink sink;
        final Listener listener;
        volatile WebSocket socket;

        Session(String channel, EventSink sink) {
            this.channel = channel;
            this.sink = sink;
            this.listener = new Listener(this);
        }
    }

    /**
     * WebSocket listener. Buffers partial text frames since
     * {@link WebSocket.Listener#onText} can fire multiple times per
     * logical message (the API allows for chunking). Same buffering
     * applies to binary frames — JDK can deliver a fragmented payload.
     */
    private final class Listener implements WebSocket.Listener {
        private final Session session;
        private final StringBuilder buf = new StringBuilder();
        /**
         * Accumulator for partial binary frames. Allocated on demand
         * because most frames arrive in one piece on a healthy network
         * and we don't want to hold a 2 KB buffer per idle socket.
         */
        private ByteBuffer binBuf = null;

        Listener(Session session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            // Need to call request explicitly to receive messages —
            // java.net.http.WebSocket uses a pull model.
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String full = buf.toString();
                buf.setLength(0);
                try {
                    Messages.ServerEvent ev = Messages.decode(full);
                    session.sink.onEvent(ev);
                } catch (Exception ignored) {
                    // Never let a callback exception kill the socket.
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            // Fast path: single-fragment frame and no accumulator yet.
            // We avoid the extra copy in the steady state.
            if (last && binBuf == null) {
                dispatchBinary(data);
            } else {
                // Slow path: accumulate fragments into binBuf.
                if (binBuf == null) {
                    // Cap the initial allocation at our hard limit so a
                    // misbehaving peer can't make us blow up memory.
                    int cap = Math.min(MAX_BINARY_FRAME_BYTES, data.remaining() + 256);
                    binBuf = ByteBuffer.allocate(cap);
                }
                if (binBuf.remaining() < data.remaining()) {
                    // Would overflow our cap → frame is too big, drop it.
                    binBuf = null;
                } else {
                    binBuf.put(data);
                    if (last) {
                        binBuf.flip();
                        dispatchBinary(binBuf);
                        binBuf = null;
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        /**
         * Decode a fully-assembled downlink binary frame and route it.
         * Downlink format: {@code [FRAME_VOICE][16-byte sender UUID][opus...]}.
         * Anything else is silently dropped — forward-compat for future
         * frame markers without breaking older clients.
         */
        private void dispatchBinary(ByteBuffer data) {
            try {
                int remaining = data.remaining();
                if (remaining < 1 || remaining > MAX_BINARY_FRAME_BYTES) return;
                byte marker = data.get();
                if (marker != FRAME_VOICE) return;
                // Downlink must include the 16-byte sender UUID stamped
                // by the relay. Anything shorter is a malformed/spoofed
                // frame; drop it.
                if (data.remaining() < 16 + 1) return;
                long msb = data.getLong();
                long lsb = data.getLong();
                UUID sender = new UUID(msb, lsb);
                byte[] opus = new byte[data.remaining()];
                data.get(opus);
                try {
                    session.sink.onVoice(sender, opus);
                } catch (Exception ignored) {
                    // Never let a callback exception kill the socket.
                }
            } catch (Exception ignored) {
                // Malformed frame — drop silently.
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // Only notify if we're still the live session — switching
            // channels triggers a close on the old socket that we
            // shouldn't surface to the UI as "you got disconnected".
            if (current.compareAndSet(session, null)) {
                session.sink.onDisconnected(session.channel,
                        reason == null || reason.isBlank() ? "code " + statusCode : reason);
                // Retry on anything except a clean close. NORMAL_CLOSURE
                // (1000) means the close was deliberate (we asked for
                // it, or the relay banned/rate-limited us) — don't loop.
                // Everything else (1001 going-away from a Worker
                // redeploy, 1006 abnormal closure from a dropped
                // connection, etc.) is worth one retry.
                if (statusCode != WebSocket.NORMAL_CLOSURE) {
                    scheduleReconnect(session.channel, session.sink);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (current.compareAndSet(session, null)) {
                session.sink.onDisconnected(session.channel, rootCause(error));
                scheduleReconnect(session.channel, session.sink);
            }
        }
    }
}
