package app.shadowclient.chat.relay;

import app.shadowclient.chat.config.AuthConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    }

    private final HttpClient http;
    private final AuthConfig auth;

    /**
     * Single in-flight socket. Holding {@code null} means we're idle.
     * We keep this as an atomic reference so a stray onClose for an
     * already-replaced socket doesn't clobber the new one.
     */
    private final AtomicReference<Session> current = new AtomicReference<>();

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

    /** Close the current socket if any. Safe to call multiple times. */
    public void disconnect() {
        closeCurrent("client closed");
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
     * logical message (the API allows for chunking).
     */
    private final class Listener implements WebSocket.Listener {
        private final Session session;
        private final StringBuilder buf = new StringBuilder();

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
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // Only notify if we're still the live session — switching
            // channels triggers a close on the old socket that we
            // shouldn't surface to the UI as "you got disconnected".
            if (current.compareAndSet(session, null)) {
                session.sink.onDisconnected(session.channel,
                        reason == null || reason.isBlank() ? "code " + statusCode : reason);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (current.compareAndSet(session, null)) {
                session.sink.onDisconnected(session.channel, rootCause(error));
            }
        }
    }
}
