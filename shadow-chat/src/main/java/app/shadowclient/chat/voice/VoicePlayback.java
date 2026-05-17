package app.shadowclient.chat.voice;

import org.concentus.OpusDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decoder pool + mixer + speaker output.
 *
 * <p>Threading:
 * <ul>
 *   <li>{@link #submitPacket(UUID, byte[])} is called from the
 *       WebSocket executor thread — it just enqueues onto a per-sender
 *       concurrent queue and returns.</li>
 *   <li>A dedicated mixer thread wakes every 20 ms, pops at most one
 *       packet per sender, decodes, sums into a single PCM frame, and
 *       writes it to the {@link SourceDataLine}.</li>
 * </ul>
 *
 * <p>Mixing is simple sample-wise summation with hard clipping. No
 * gain control, no dynamic range compression — the relay is expected
 * to gate at the auth layer, so the typical case is one or two
 * simultaneous speakers and clipping is rare.
 *
 * <p>"Currently speaking" detection: each successful decode stamps a
 * timestamp into {@link #lastFrameTimeMs}. The UI calls
 * {@link #currentSpeakers()} every frame and styles names accordingly.
 */
public final class VoicePlayback {

    private static final Logger LOG = LoggerFactory.getLogger("shadow-chat/voice-playback");

    /** Output PCM format. Matches {@link VoiceCodec} + capture. */
    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            VoiceCodec.SAMPLE_RATE,
            16,
            VoiceCodec.CHANNELS,
            VoiceCodec.CHANNELS * 2,
            VoiceCodec.SAMPLE_RATE,
            false /* little-endian */);

    /** Speaker indicator window — UUIDs with a successful decode in the
     *  last N ms are considered "speaking". 500 ms is enough to bridge
     *  the gap between 20 ms frames without flickering off on normal
     *  speech cadence. */
    private static final long SPEAKER_WINDOW_MS = 500L;

    /** Per-sender pending-packet queue. */
    private final Map<UUID, ConcurrentLinkedQueue<byte[]>> queues = new ConcurrentHashMap<>();
    /** Per-sender decoder (stateful, single-owner: only mixer touches). */
    private final Map<UUID, OpusDecoder> decoders = new HashMap<>();
    /** Last-frame-time per sender (for speaker indicator). */
    private final Map<UUID, Long> lastFrameTimeMs = new ConcurrentHashMap<>();

    private SourceDataLine line;
    private Thread mixer;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private volatile boolean available = false;

    /** Whether the platform has a working output device. */
    public boolean isAvailable() { return available; }

    /**
     * Open the output line and start the mixer thread. Safe to call
     * once; subsequent calls are no-ops.
     */
    public synchronized void init() {
        if (mixer != null) return;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                LOG.warn("No audio output line supporting 48 kHz 16-bit mono PCM; voice playback disabled.");
                available = false;
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(FORMAT, VoiceCodec.FRAME_SIZE_BYTES * 5);
            line.start();
            available = true;
        } catch (LineUnavailableException e) {
            LOG.warn("Output line unavailable; voice playback disabled. cause={}", e.toString());
            available = false;
            return;
        } catch (Exception e) {
            LOG.warn("Unexpected error opening output line; voice playback disabled. cause={}", e.toString());
            available = false;
            return;
        }

        mixer = new Thread(this::pump, "shadow-chat-voice-mixer");
        mixer.setDaemon(true);
        mixer.start();
        LOG.info("Voice playback ready (format={})", FORMAT);
    }

    /**
     * Enqueue an Opus packet from {@code sender}. Decoding happens on
     * the mixer thread — this method is fire-and-forget.
     *
     * <p>Per-sender queue length is loosely capped to avoid unbounded
     * memory if the mixer falls behind (lost output line, GC stall):
     * any backlog of more than ~25 frames (500 ms) is discarded.
     */
    public void submitPacket(UUID sender, byte[] opus) {
        if (sender == null || opus == null || opus.length == 0) return;
        if (!available) return;
        ConcurrentLinkedQueue<byte[]> q = queues.computeIfAbsent(sender, k -> new ConcurrentLinkedQueue<>());
        q.offer(opus);
        // Trim runaway backlogs.
        while (q.size() > 25) q.poll();
    }

    /**
     * UUIDs that have produced a decoded frame in the last
     * {@value #SPEAKER_WINDOW_MS} ms. Caller may iterate freely; the
     * returned list is a snapshot.
     */
    public List<UUID> currentSpeakers() {
        long cutoff = System.currentTimeMillis() - SPEAKER_WINDOW_MS;
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : lastFrameTimeMs.entrySet()) {
            if (e.getValue() >= cutoff) out.add(e.getKey());
        }
        return out;
    }

    /**
     * Drop all per-sender state. Called when the active channel
     * changes so a voice from the previous channel doesn't bleed
     * through. Safe to call from any thread.
     */
    public synchronized void resetSpeakers() {
        queues.clear();
        decoders.clear();
        lastFrameTimeMs.clear();
    }

    /** Tear down the line + mixer thread. */
    public synchronized void shutdown() {
        alive.set(false);
        if (mixer != null) mixer.interrupt();
        if (line != null) {
            try { line.drain(); } catch (Exception ignored) {}
            try { line.stop(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
            line = null;
        }
        queues.clear();
        decoders.clear();
        lastFrameTimeMs.clear();
        mixer = null;
    }

    // ------------------------------------------------------------------

    /**
     * Mixer loop. Roughly:
     * <pre>
     *   loop forever:
     *     mix = zero[FRAME_SIZE]
     *     for each sender with a queued packet:
     *       decoded = decode(packet)
     *       mix += decoded   // saturating
     *     write mix to SourceDataLine
     * </pre>
     *
     * <p>We don't sleep explicitly — {@link SourceDataLine#write} blocks
     * once its internal buffer fills up, which gives us natural pacing
     * at the audio clock. Empty frames are also written so the line
     * doesn't underrun (= choppy intro on the next real frame).
     */
    private void pump() {
        int[] mix = new int[VoiceCodec.FRAME_SIZE_SAMPLES];
        short[] saturated = new short[VoiceCodec.FRAME_SIZE_SAMPLES];
        byte[] outBytes = new byte[VoiceCodec.FRAME_SIZE_BYTES];
        ShortBuffer scratch = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

        while (alive.get()) {
            // Reset accumulator.
            for (int i = 0; i < mix.length; i++) mix[i] = 0;

            boolean anyAudio = false;
            // Snapshot the senders so a concurrent reset doesn't trip
            // ConcurrentModificationException.
            List<Map.Entry<UUID, ConcurrentLinkedQueue<byte[]>>> entries;
            synchronized (this) {
                entries = new ArrayList<>(queues.entrySet());
            }
            for (Map.Entry<UUID, ConcurrentLinkedQueue<byte[]>> e : entries) {
                UUID sender = e.getKey();
                byte[] packet = e.getValue().poll();
                if (packet == null) continue;
                OpusDecoder dec;
                synchronized (this) {
                    dec = decoders.get(sender);
                    if (dec == null) {
                        try {
                            dec = VoiceCodec.newDecoder();
                            decoders.put(sender, dec);
                        } catch (Exception ex) {
                            LOG.debug("decoder init failed for {}: {}", sender, ex.toString());
                            continue;
                        }
                    }
                }
                short[] pcm;
                try {
                    pcm = VoiceCodec.decode(dec, packet);
                } catch (Exception ex) {
                    LOG.debug("decode failed for {}: {}", sender, ex.toString());
                    continue;
                }
                // Accumulate as int to avoid intermediate overflow when
                // 3+ loud speakers stack.
                for (int i = 0; i < mix.length; i++) {
                    mix[i] += pcm[i];
                }
                lastFrameTimeMs.put(sender, System.currentTimeMillis());
                anyAudio = true;
            }

            // Clip to short range.
            for (int i = 0; i < mix.length; i++) {
                int v = mix[i];
                if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                saturated[i] = (short) v;
            }
            scratch.rewind();
            scratch.put(saturated);

            if (anyAudio) {
                try {
                    line.write(outBytes, 0, outBytes.length);
                } catch (Exception ex) {
                    LOG.debug("output write failed: {}", ex.toString());
                }
            } else {
                // No active speakers — sleep a frame's worth so we
                // don't burn a CPU core polling the empty queues.
                try { Thread.sleep(20); } catch (InterruptedException ignored) { break; }
            }
        }
    }
}
