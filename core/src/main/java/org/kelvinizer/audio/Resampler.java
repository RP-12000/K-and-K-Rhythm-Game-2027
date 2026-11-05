package org.kelvinizer.audio;

/**
 * 歌曲的采样率转换（比如 44100 → 48000）：加窗 sinc 插值，一边解码一边转，音质比线性插值好（线性会发闷、有杂音）。
 * 采样率相同就原样通过。
 *
 * <p>Streaming stereo sample-rate converter for songs: Kaiser-windowed sinc over {@value #TAPS} input frames,
 * {@value #PHASES} tabulated phases with linear interpolation between them (linear interpolation of the samples
 * themselves dulls the highs and folds images back into the audible band). Output frame {@code i} is the song at
 * input time {@code i · src / dst}: no delay is added, so song positions keep their meaning. Equal rates pass
 * through untouched. Not thread-safe; the decoder thread owns it.
 */
public final class Resampler {
    static final int TAPS = 32;
    private static final int HALF = TAPS / 2;
    static final int PHASES = 256;
    private static final double KAISER_BETA = 8.6;

    private final PcmBuffer out;
    private final boolean pass;
    private final double step;
    private final float[] table;
    /** Input frames not yet consumed, stereo; frame {@code base} of the input is at index 0. */
    private float[] in = new float[4096 * 2];
    private int inFrames;
    private long base;
    private long inTotal;
    private long outTotal;
    private float[] block = new float[1024 * 2];

    /** Converts {@code srcRate} audio to {@code dstRate} and appends it to {@code out}. */
    public Resampler(int srcRate, int dstRate, PcmBuffer out) {
        this.out = out;
        pass = srcRate == dstRate;
        step = (double) srcRate / dstRate;
        table = pass ? null : kernel(Math.min(1.0, (double) dstRate / srcRate) * 0.95);
        // HALF − 1 frames of silence before the song, so output frame 0 sits on input frame 0
        base = -(HALF - 1);
        inFrames = HALF - 1;
    }

    private static float[] kernel(double cutoff) {
        float[] t = new float[(PHASES + 1) * TAPS];
        double i0b = besselI0(KAISER_BETA);
        for (int p = 0; p <= PHASES; p++) {
            double frac = (double) p / PHASES;
            double sum = 0.0;
            for (int j = 0; j < TAPS; j++) {
                double x = j - (HALF - 1) - frac; // input frame minus output position
                double r = x / HALF;
                double w = Math.abs(r) >= 1.0 ? 0.0 : besselI0(KAISER_BETA * Math.sqrt(1.0 - r * r)) / i0b;
                double s = x == 0.0 ? 1.0 : Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
                double v = cutoff * s * w;
                t[p * TAPS + j] = (float) v;
                sum += v;
            }
            for (int j = 0; j < TAPS; j++) t[p * TAPS + j] /= (float) sum; // unity gain at DC
        }
        return t;
    }

    private static double besselI0(double x) {
        double sum = 1.0, term = 1.0, q = x * x / 4.0;
        for (int k = 1; k < 50; k++) {
            term *= q / ((double) k * k);
            sum += term;
            if (term < sum * 1e-12) break;
        }
        return sum;
    }

    /** Feeds {@code n} stereo frames (interleaved, −1..1) starting at frame {@code from} of {@code stereo}. */
    public void push(float[] stereo, int from, int n) {
        inTotal += n;
        if (pass) {
            out.append(stereo, from, n);
            outTotal += n;
            return;
        }
        if ((inFrames + n) * 2 > in.length) {
            float[] grown = new float[Math.max(in.length * 2, (inFrames + n) * 2)];
            System.arraycopy(in, 0, grown, 0, inFrames * 2);
            in = grown;
        }
        System.arraycopy(stereo, from * 2, in, inFrames * 2, n * 2);
        inFrames += n;
        run(false);
    }

    /** The input ended: converts what is left, as if silence followed. */
    public void finish() {
        if (pass) return;
        if ((inFrames + HALF) * 2 > in.length) {
            float[] grown = new float[(inFrames + HALF) * 2];
            System.arraycopy(in, 0, grown, 0, inFrames * 2);
            in = grown;
        }
        java.util.Arrays.fill(in, inFrames * 2, (inFrames + HALF) * 2, 0f);
        inFrames += HALF;
        run(true);
    }

    private void run(boolean last) {
        long want = last ? (long) Math.ceil(inTotal / step - 1e-9) : Long.MAX_VALUE;
        int b = 0;
        while (outTotal < want) {
            double pos = outTotal * step; // in input frames
            long c = (long) Math.floor(pos);
            int first = (int) (c - (HALF - 1) - base); // index in `in` of the first tap
            if (first + TAPS > inFrames) break;
            double ph = (pos - c) * PHASES;
            int p = (int) ph;
            if (p >= PHASES) p = PHASES - 1;
            float f = (float) (ph - p);
            int t0 = p * TAPS, t1 = t0 + TAPS;
            float l = 0f, r = 0f;
            for (int j = 0; j < TAPS; j++) {
                float w = table[t0 + j] + (table[t1 + j] - table[t0 + j]) * f;
                int k = (first + j) * 2;
                l += in[k] * w;
                r += in[k + 1] * w;
            }
            block[b * 2] = l;
            block[b * 2 + 1] = r;
            outTotal++;
            if (++b * 2 == block.length) {
                out.append(block, 0, b);
                b = 0;
            }
        }
        if (b > 0) out.append(block, 0, b);
        // drop input no longer reached by any tap
        long keepFrom = (long) Math.floor(outTotal * step) - (HALF - 1);
        int drop = (int) Math.max(0L, Math.min(inFrames, keepFrom - base));
        if (drop > 0) {
            System.arraycopy(in, drop * 2, in, 0, (inFrames - drop) * 2);
            inFrames -= drop;
            base += drop;
        }
    }
}
