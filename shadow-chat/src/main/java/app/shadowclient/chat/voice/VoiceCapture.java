package app.shadowclient.chat.voice;

import org.concentus.OpusEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Mic capture thread. Opens a {@link TargetDataLine} once at startup,
 * reads 20 ms frames in a loop, encodes to Opus, and hands each packet
 * off to a consumer (which forwards to the relay).
 *
 * <p>Push-to-talk gating is done by toggling {@link #capturing} — the
 * line stays open and is continuously drained even when we aren't
 * transmitting, so the OS doesn't tear down the audio path on every
 * keypress (which causes a glaringly audible 200+ ms latency stutter
 * on the first transmitted frame on Windows).
 *
 * <p>If the platform has no microphone (or all input devices are in
 * use by another process), this becomes a no-op: a single log line and
 * {@link #isAvailable()} returns false forever after. The rest of the
 * mod keeps working.
 */
public final class VoiceCapture {

    private static final Logger LOG = LoggerFactory.getLogger("shadow-chat/voice-capture");

    /** PCM format we ask the OS for. Matches {@link VoiceCodec}. */
    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            VoiceCodec.SAMPLE_RATE,
            16,
            VoiceCodec.CHANNELS,
            VoiceCodec.CHANNELS * 2,
            VoiceCodec.SAMPLE_RATE,
            false /* little-endian */);

    private final Consumer<byte[]> opusSink;
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean alive = new AtomicBoolean(true);

    private TargetDataLine line;
    private OpusEncoder encoder;
    private Thread worker;
    private volatile boolean available = false;

    public VoiceCapture(Consumer<byte[]> opusSink) {
        this.opusSink = opusSink;
    }

    /** Whether a usable microphone was found at init time. */
    public boolean isAvailable() { return available; }

    /** Whether we're currently transmitting (push-to-talk held). */
    public boolean isTransmitting() { return capturing.get(); }

    /**
     * One-time hardware init + worker thread spin-up. Safe to call
     * multiple times — second-and-later calls are no-ops.
     */
    public synchronized void init() {
        if (worker != null) return;
        try {
            this.encoder = VoiceCodec.newEncoder();
        } catch (Exception e) {
            LOG.warn("Could not initialize Opus encoder; voice capture disabled. cause={}", e.toString());
            available = false;
            return;
        }
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                LOG.warn("No audio input line supporting 48 kHz 16-bit mono PCM; voice capture disabled.");
                available = false;
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            // Internal buffer big enough for ~5 frames. Anything more
            // adds latency; anything less and the OS may overrun under
            // GC pauses.
            line.open(FORMAT, VoiceCodec.FRAME_SIZE_BYTES * 5);
            line.start();
            available = true;
        } catch (LineUnavailableException e) {
            LOG.warn("Mic in use by another process; voice capture disabled. cause={}", e.toString());
            available = false;
            line = null;
            return;
        } catch (Exception e) {
            LOG.warn("Unexpected error opening mic; voice capture disabled. cause={}", e.toString());
            available = false;
            line = null;
            return;
        }

        worker = new Thread(this::pump, "shadow-chat-voice-capture");
        worker.setDaemon(true);
        worker.start();
        LOG.info("Voice capture ready (format={})", FORMAT);
    }

    /** Begin transmitting on the next frame boundary. */
    public void start() {
        if (!available) return;
        capturing.set(true);
    }

    /** Stop transmitting; the line stays open for instant re-arm. */
    public void stop() {
        capturing.set(false);
    }

    /** Permanently release the mic. Called from
     *  {@link app.shadowclient.chat.voice.VoiceController#shutdown()}. */
    public synchronized void shutdown() {
        alive.set(false);
        capturing.set(false);
        if (worker != null) worker.interrupt();
        if (line != null) {
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
            line = null;
        }
        encoder = null;
        worker = null;
    }

    // ------------------------------------------------------------------

    /**
     * Worker loop. Continuously drains the mic line (whether or not
     * we're transmitting) so the audio path stays warm; only encodes +
     * emits when {@link #capturing} is true.
     */
    private void pump() {
        byte[] frame = new byte[VoiceCodec.FRAME_SIZE_BYTES];
        short[] samples = new short[VoiceCodec.FRAME_SIZE_SAMPLES];
        ShortBuffer scratch = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

        while (alive.get()) {
            int filled = 0;
            try {
                // line.read may return fewer bytes than requested if
                // the stream is closing; loop until we've got a full
                // 20 ms frame or the line dies.
                while (filled < frame.length && alive.get()) {
                    int n = line.read(frame, filled, frame.length - filled);
                    if (n <= 0) break;
                    filled += n;
                }
            } catch (Exception e) {
                LOG.debug("mic read failed: {}", e.toString());
                break;
            }
            if (filled < frame.length) {
                // Partial / errored frame. Skip it rather than encoding
                // half-zero garbage.
                continue;
            }
            if (!capturing.get()) {
                // PTT not held — read the audio to keep the line moving
                // but don't bother encoding it.
                continue;
            }
            scratch.rewind();
            scratch.get(samples);
            try {
                byte[] opus = VoiceCodec.encode(encoder, samples);
                if (opus.length > 0 && opusSink != null) {
                    opusSink.accept(opus);
                }
            } catch (Exception e) {
                // Encoding shouldn't fail at steady state, but if it
                // does don't kill the thread — just drop this frame.
                LOG.debug("opus encode failed: {}", e.toString());
            }
        }
    }
}
