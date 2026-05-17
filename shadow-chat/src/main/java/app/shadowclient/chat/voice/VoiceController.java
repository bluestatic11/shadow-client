package app.shadowclient.chat.voice;

import app.shadowclient.chat.relay.RelayClient;

import java.util.UUID;

/**
 * Owns the capture + playback singletons and wires them together.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #init(RelayClient)} — called once at mod startup. Opens
 *       audio hardware on background threads and hooks
 *       {@link #onVoiceFrame(UUID, byte[])} into the relay's binary
 *       frame dispatch.</li>
 *   <li>{@link #setTransmitting(boolean)} — called every client tick
 *       with the current state of the push-to-talk keybind.</li>
 *   <li>{@link #onChannelChange()} — called when the active channel
 *       changes; drops decoder state so an old speaker doesn't
 *       linger.</li>
 *   <li>{@link #shutdown()} — called on client stop.</li>
 * </ul>
 *
 * <p>This class is intentionally a thin glue layer; all the real work
 * is in {@link VoiceCapture} / {@link VoicePlayback} / {@link VoiceCodec}.
 */
public final class VoiceController {

    private final VoicePlayback playback = new VoicePlayback();
    private final VoiceCapture capture;
    private RelayClient relay;

    /** Tracks the push-to-talk key state so we only call start/stop on
     *  the rising/falling edges (cheap to call but cleaner this way). */
    private boolean transmittingNow = false;

    public VoiceController() {
        // The capture's sink closure writes through to whatever relay
        // is current at send-time, so re-binding the relay later (if
        // we ever need to) doesn't strand old packets.
        this.capture = new VoiceCapture(this::sendOpus);
    }

    public VoicePlayback playback() { return playback; }
    public VoiceCapture capture() { return capture; }

    /** Are we actively transmitting on the wire right now? */
    public boolean isTransmitting() { return transmittingNow && capture.isAvailable(); }

    /**
     * One-shot setup: open audio hardware, remember the relay handle.
     * Idempotent.
     */
    public void init(RelayClient relay) {
        this.relay = relay;
        playback.init();
        capture.init();
    }

    /**
     * Forward a voice frame from the WebSocket layer into the mixer.
     * Wired up at sink-registration time (see ShadowChatClient).
     */
    public void onVoiceFrame(UUID sender, byte[] opus) {
        playback.submitPacket(sender, opus);
    }

    /**
     * Update transmit state. Idempotent — repeated identical calls are
     * cheap. Called every tick from {@code ClientTickEvents.END_CLIENT_TICK}.
     */
    public void setTransmitting(boolean shouldTransmit) {
        if (shouldTransmit == transmittingNow) return;
        transmittingNow = shouldTransmit;
        if (shouldTransmit) {
            capture.start();
        } else {
            capture.stop();
        }
    }

    /** Drop per-speaker decoder state. Called on channel switches. */
    public void onChannelChange() {
        playback.resetSpeakers();
    }

    /** Tear down on shutdown. */
    public void shutdown() {
        setTransmitting(false);
        capture.shutdown();
        playback.shutdown();
    }

    // ------------------------------------------------------------------

    private void sendOpus(byte[] opus) {
        RelayClient r = this.relay;
        if (r == null) return;
        r.sendVoice(opus);
    }
}
