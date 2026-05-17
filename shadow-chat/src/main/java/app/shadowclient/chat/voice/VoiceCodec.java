package app.shadowclient.chat.voice;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

/**
 * Thin wrapper around Concentus' Opus encoder + decoder, fixed to the
 * configuration the rest of the voice stack expects.
 *
 * <p>Audio format is locked at:
 * <ul>
 *   <li>48 kHz sample rate</li>
 *   <li>Mono (1 channel)</li>
 *   <li>16-bit signed PCM</li>
 *   <li>20 ms frames = 960 samples = 1920 bytes raw</li>
 *   <li>Target bitrate ~24 kbps, complexity 5 — voice quality with
 *       modest CPU cost on the codec thread.</li>
 * </ul>
 *
 * <p>Encoder is single-threaded — only {@link VoiceCapture}'s thread
 * touches it. Decoder is constructed per-sender from
 * {@link VoicePlayback} and only the mixer thread touches each instance.
 * Concentus does not document thread safety so this single-owner
 * discipline is necessary.
 */
public final class VoiceCodec {

    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 1;
    /** Samples per Opus frame (20 ms @ 48 kHz). Concentus only allows
     *  one of {120, 240, 480, 960, 1920, 2880}; 960 is the sweet spot
     *  for VoIP latency vs overhead. */
    public static final int FRAME_SIZE_SAMPLES = 960;
    /** Raw PCM bytes per frame: 960 samples × 2 bytes/sample. */
    public static final int FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2;

    /** Conservative encoded-packet ceiling. Real packets land near 60 B
     *  at our bitrate, but Opus headers can briefly spike so we leave
     *  headroom. */
    static final int MAX_OPUS_PACKET_BYTES = 1500;

    /** New encoder. Constructor throws if Concentus is sad about the
     *  config, which would be a programming error rather than a runtime
     *  fault, so we surface it via OpusException to the caller. */
    public static OpusEncoder newEncoder() throws OpusException {
        OpusEncoder enc = new OpusEncoder(SAMPLE_RATE, CHANNELS,
                OpusApplication.OPUS_APPLICATION_VOIP);
        enc.setBitrate(24_000);
        enc.setComplexity(5);
        // Forward error correction + 10% expected loss → tolerable
        // recovery on a janky connection without doubling the bitrate.
        enc.setUseInbandFEC(true);
        enc.setPacketLossPercent(10);
        return enc;
    }

    /** New decoder. Caller keeps one per remote sender. */
    public static OpusDecoder newDecoder() throws OpusException {
        return new OpusDecoder(SAMPLE_RATE, CHANNELS);
    }

    /**
     * Encode one frame of mono PCM. {@code pcm} must contain exactly
     * {@link #FRAME_SIZE_SAMPLES} samples; anything else is rejected so
     * we don't shift Opus' internal frame counter under the rug.
     * Returns a newly-allocated byte array sized to the encoded payload.
     */
    public static byte[] encode(OpusEncoder enc, short[] pcm) throws OpusException {
        if (pcm == null || pcm.length != FRAME_SIZE_SAMPLES) {
            throw new IllegalArgumentException("expected " + FRAME_SIZE_SAMPLES + " samples, got " + (pcm == null ? -1 : pcm.length));
        }
        byte[] out = new byte[MAX_OPUS_PACKET_BYTES];
        int written = enc.encode(pcm, 0, FRAME_SIZE_SAMPLES, out, 0, out.length);
        if (written <= 0) return new byte[0];
        byte[] packed = new byte[written];
        System.arraycopy(out, 0, packed, 0, written);
        return packed;
    }

    /**
     * Decode one Opus packet into PCM. Always returns a buffer of
     * exactly {@link #FRAME_SIZE_SAMPLES} samples (Concentus pads with
     * silence if the packet was shorter than one frame).
     */
    public static short[] decode(OpusDecoder dec, byte[] opus) throws OpusException {
        short[] out = new short[FRAME_SIZE_SAMPLES];
        // The 0/0 + last `false` means "no FEC" — Opus' in-band FEC
        // would kick in if the previous packet was lost, but we don't
        // currently track packet sequence numbers on the wire so we
        // settle for the simpler decode path. Quality is fine for
        // push-to-talk where the speaker tolerates the odd glitch.
        int produced = dec.decode(opus, 0, opus == null ? 0 : opus.length,
                out, 0, FRAME_SIZE_SAMPLES, false);
        if (produced <= 0) return new short[FRAME_SIZE_SAMPLES];
        if (produced == FRAME_SIZE_SAMPLES) return out;
        // Concentus may return fewer samples for SILK-mode packets at
        // lower sampling rates; pad the trailing samples to zero so the
        // mixer's frame alignment doesn't drift.
        short[] padded = new short[FRAME_SIZE_SAMPLES];
        System.arraycopy(out, 0, padded, 0, Math.min(produced, FRAME_SIZE_SAMPLES));
        return padded;
    }

    private VoiceCodec() {}
}
