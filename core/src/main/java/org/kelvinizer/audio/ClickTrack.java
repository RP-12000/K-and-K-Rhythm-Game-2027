package org.kelvinizer.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * 延迟校准用的节拍声：120 BPM，4 下预备拍加 24 下测量拍，每 4 拍一个高音重拍，共 15 秒。
 * 第一次使用时合成并缓存为 {@code cache/calib_120.wav}，文件大小不对时重新生成。
 *
 * <p>The calibration click track (DESIGN §9.4), synthesised on first use.
 *
 * <p>44.1 kHz, mono, 16-bit PCM, 120 BPM: {@link #LEAD_IN} lead-in clicks followed by
 * {@link #MEASURED} measured clicks, the first at {@link #FIRST_BEAT_MS}. Each click is a
 * 1 kHz sine of 30 ms (2 ms linear attack, exponential decay τ = 6 ms); every 4th beat
 * (beat 0, 4, 8, …) is an accent at 1.5 kHz. Total length {@link #LENGTH_MS} (15 s).
 *
 * <p>The file is cached at {@code Gdx.files.local("cache/calib_120.wav")} on every platform
 * (DESIGN §9.4). On desktop "local" is the working directory, which is the {@code assets}
 * folder during development, so {@code /assets/cache/} is git-ignored. A truncated cache file
 * (wrong size) is regenerated.
 */
public final class ClickTrack {
    /** Sample rate in Hz. */
    public static final int SAMPLE_RATE = 44_100;
    /** Tempo. */
    public static final int BPM = 120;
    /** Beat length in ms. */
    public static final float BEAT_MS = 60_000f / BPM;
    /** Lead-in clicks (shown as a visual 4-3-2-1). */
    public static final int LEAD_IN = 4;
    /** Measured clicks. */
    public static final int MEASURED = 24;
    /** Measured clicks used by the first-run quick mode. */
    public static final int QUICK_MEASURED = 12;
    /** Total clicks in the file. */
    public static final int TOTAL_BEATS = LEAD_IN + MEASURED;
    /** Time of beat 0 in ms (a little silence lets the output warm up). */
    public static final float FIRST_BEAT_MS = 500f;
    /** File length in ms. */
    public static final int LENGTH_MS = 15_000;
    /** Click frequency in Hz. */
    public static final float CLICK_HZ = 1000f;
    /** Accent frequency in Hz. */
    public static final float ACCENT_HZ = 1500f;
    /** Click length in ms. */
    public static final float CLICK_MS = 30f;
    /** Attack in ms. */
    public static final float ATTACK_MS = 2f;
    /** Decay time constant in ms. */
    public static final float DECAY_TAU_MS = 6f;
    /** Cache path (relative to the cache root). */
    public static final String FILE_NAME = "cache/calib_120.wav";

    private static final float AMPLITUDE = 0.8f;

    private ClickTrack() {
    }

    /** @return time of beat {@code i} in ms (beat 0 is the first lead-in click). */
    public static float beatTimeMs(int i) {
        return FIRST_BEAT_MS + i * BEAT_MS;
    }

    /** @return time of measured beat {@code k} (0-based, after the lead-in) in ms. */
    public static float measuredBeatTimeMs(int k) {
        return beatTimeMs(LEAD_IN + k);
    }

    /** @return true when beat {@code i} is an accent (every 4th beat, starting with beat 0). */
    public static boolean isAccent(int i) {
        return i % 4 == 0;
    }

    /**
     * @return index of the beat nearest to {@code timeMs} (may be negative or ≥ TOTAL_BEATS
     *         outside the track)
     */
    public static int nearestBeat(double timeMs) {
        return (int) Math.round((timeMs - FIRST_BEAT_MS) / BEAT_MS);
    }

    /** @return the cache file handle {@code local("cache/calib_120.wav")} (not necessarily existing yet). */
    public static FileHandle file() {
        return Gdx.files.local(FILE_NAME);
    }

    /**
     * Returns the cached click track, synthesising it first when missing or the wrong size.
     *
     * @return the WAV file, or null when it could not be written
     */
    public static FileHandle ensure() {
        FileHandle f = file();
        long expected = 44L + 2L * sampleCount();
        try {
            if (f.exists() && f.length() == expected) return f;
            f.parent().mkdirs();
            FileHandle tmp = f.sibling(f.name() + ".tmp");
            tmp.writeBytes(synthesize(), false);
            tmp.moveTo(f);
            return f;
        } catch (RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("ClickTrack", "Cannot write " + f.path(), e);
            return null;
        }
    }

    /** @return number of samples in the file. */
    public static int sampleCount() {
        return (int) ((long) SAMPLE_RATE * LENGTH_MS / 1000L);
    }

    /** @return the complete WAV file (RIFF header + PCM) as bytes. Pure, for tests too. */
    public static byte[] synthesize() {
        int n = sampleCount();
        short[] pcm = new short[n];
        int clickSamples = Math.round(CLICK_MS * SAMPLE_RATE / 1000f);
        for (int b = 0; b < TOTAL_BEATS; b++) {
            int start = Math.round(beatTimeMs(b) * SAMPLE_RATE / 1000f);
            float hz = isAccent(b) ? ACCENT_HZ : CLICK_HZ;
            for (int i = 0; i < clickSamples && start + i < n; i++) {
                float tMs = i * 1000f / SAMPLE_RATE;
                float env = tMs < ATTACK_MS ? tMs / ATTACK_MS
                    : (float) Math.exp(-(tMs - ATTACK_MS) / DECAY_TAU_MS);
                double v = AMPLITUDE * env * Math.sin(2.0 * Math.PI * hz * i / SAMPLE_RATE);
                pcm[start + i] = (short) Math.round(v * Short.MAX_VALUE);
            }
        }
        int dataBytes = n * 2;
        byte[] out = new byte[44 + dataBytes];
        writeAscii(out, 0, "RIFF");
        writeIntLE(out, 4, 36 + dataBytes);
        writeAscii(out, 8, "WAVE");
        writeAscii(out, 12, "fmt ");
        writeIntLE(out, 16, 16);
        writeShortLE(out, 20, 1);                 // PCM
        writeShortLE(out, 22, 1);                 // mono
        writeIntLE(out, 24, SAMPLE_RATE);
        writeIntLE(out, 28, SAMPLE_RATE * 2);     // byte rate
        writeShortLE(out, 32, 2);                 // block align
        writeShortLE(out, 34, 16);                // bits per sample
        writeAscii(out, 36, "data");
        writeIntLE(out, 40, dataBytes);
        for (int i = 0; i < n; i++) writeShortLE(out, 44 + 2 * i, pcm[i]);
        return out;
    }

    private static void writeAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void writeShortLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }
}
