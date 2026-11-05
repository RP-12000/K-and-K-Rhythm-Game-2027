package org.kelvinizer.audio;

/**
 * 解码好的歌曲声音数据：立体声 16 位，按混音器的采样率，分成一块一块存（不要一整块大数组）。
 * 解码线程往后追加，放声音的线程同时从前面读。
 *
 * <p>A decoded song: 16-bit stereo frames at the mixer's rate, kept in chunks of {@link #CHUNK} frames so a long
 * song needs no single huge array. One thread {@link #append appends} while another reads frames below
 * {@link #frames()}: a chunk is complete before the count that covers it is published.
 */
public final class PcmBuffer {
    private static final int CHUNK_BITS = 15;
    /** Frames per chunk (about 0.7 s at 48 kHz). */
    public static final int CHUNK = 1 << CHUNK_BITS;

    private volatile short[][] chunks = new short[16][];
    private volatile long frames;
    private volatile boolean done;
    private volatile boolean failed;

    /** Writer: appends {@code n} frames of {@code stereo} (interleaved, −1..1, clipped) from frame {@code from}. */
    public void append(float[] stereo, int from, int n) {
        long f = frames;
        short[][] c = chunks;
        for (int i = 0; i < n; i++, f++) {
            int k = (int) (f >>> CHUNK_BITS);
            if (k >= c.length) {
                short[][] grown = new short[c.length * 2][];
                System.arraycopy(c, 0, grown, 0, c.length);
                chunks = c = grown;
            }
            short[] chunk = c[k];
            if (chunk == null) c[k] = chunk = new short[CHUNK * 2];
            int o = (int) (f & (CHUNK - 1)) * 2;
            chunk[o] = toShort(stereo[(from + i) * 2]);
            chunk[o + 1] = toShort(stereo[(from + i) * 2 + 1]);
        }
        frames = f;
    }

    private static short toShort(float v) {
        float s = v * 32767f;
        return (short) (s > 32767f ? 32767 : s < -32768f ? -32768 : Math.round(s));
    }

    /** Writer: the song is complete ({@code ok}) or cut short by an error (what was decoded stays playable). */
    public void finish(boolean ok) {
        failed = !ok;
        done = true;
    }

    /** @return frames ready to read */
    public long frames() {
        return frames;
    }

    /** @return true once no more frames will come */
    public boolean done() {
        return done;
    }

    /** @return true when decoding stopped on an error */
    public boolean failed() {
        return failed;
    }

    /** @return the chunk holding frame {@code frame} (stereo interleaved; read only frames below {@link #frames()}) */
    short[] chunk(long frame) {
        return chunks[(int) (frame >>> CHUNK_BITS)];
    }

    /** @return the index in its {@link #chunk} of frame {@code frame}'s left sample */
    static int offset(long frame) {
        return (int) (frame & (CHUNK - 1)) * 2;
    }
}
