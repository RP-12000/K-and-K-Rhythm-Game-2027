package org.kelvinizer.elements;

import java.util.List;
import java.util.Locale;

/**
 * 一个音符的一段故障效果（谱面的 {@code "glitch"} 键里的一段，不可变）：起止拍、强度（可以渐强渐弱）、跳变速度，以及色散、错位、闪断三种成分的比重。
 * 故障只是画面效果，引擎从不读取；{@link #of} 会修正非法值，从不抛异常。
 *
 * <p>One glitch segment of a note (CHART_FORMAT §6.9, Kelvin 2026-10-14), immutable and pure Java.
 * A glitch is only a show: the engine never reads it, and it never changes a judgement or an
 * input.
 *
 * <p>Chart key (v2 notes of every type, optional): one segment as an object, or several as an
 * array of objects:
 * <pre>
 * "glitch": [ { "from": 64, "duration": 2, "intensity": 0, "intensityEnd": 1 },
 *             { "from": 70, "to": 72, "intensity": 0.5, "slice": 0 } ]
 * </pre>
 * <ul>
 *   <li>{@code from} / {@code to} (beats, both inclusive; absent = unbounded; {@code duration}
 *       may stand for {@code to}): the segment glitches the note only inside it.</li>
 *   <li>{@code intensity} (0..1, default {@value #DEFAULT_INTENSITY}) at {@code from}, eased to
 *       {@code intensityEnd} (default = {@code intensity}) at {@code to} with the power curve of
 *       the events ({@code exponent}, default 1): {@link #intensityAt}.</li>
 *   <li>{@code rate}: new random pattern this many times per beat (default
 *       {@value #DEFAULT_RATE}; 0 keeps one pattern for the whole segment).</li>
 *   <li>{@code split} (colour split), {@code slice} (sideways slices) and {@code flicker}
 *       (brief dimming): weights of the three parts, 0..{@value #MAX_WEIGHT}.</li>
 * </ul>
 * Where segments overlap, the one that started last wins, as with events.
 */
public final class NoteGlitch {
    /** Default {@code intensity}. */
    public static final float DEFAULT_INTENSITY = 0.5f;
    /** Default {@code rate} (new pattern this many times per beat). */
    public static final float DEFAULT_RATE = 8f;
    /** Largest {@code rate}. */
    public static final float MAX_RATE = 64f;
    /** Default {@code split} weight (colour split). */
    public static final float DEFAULT_SPLIT = 1f;
    /** Default {@code slice} weight (sideways slices). */
    public static final float DEFAULT_SLICE = 1f;
    /** Default {@code flicker} weight (brief dimming). */
    public static final float DEFAULT_FLICKER = 0.3f;
    /** Largest weight of a part. */
    public static final float MAX_WEIGHT = 2f;
    /** Smallest {@code exponent}. */
    public static final float MIN_EXPONENT = 0.05f;
    /** Largest {@code exponent}. */
    public static final float MAX_EXPONENT = 20f;

    private final float from;
    private final float to;
    private final float intensity;
    private final float intensityEnd;
    private final float exponent;
    private final float rate;
    private final float split;
    private final float slice;
    private final float flicker;

    private NoteGlitch(float from, float to, float intensity, float intensityEnd, float exponent, float rate,
                       float split, float slice, float flicker) {
        this.from = from;
        this.to = to;
        this.intensity = intensity;
        this.intensityEnd = intensityEnd;
        this.exponent = exponent;
        this.rate = rate;
        this.split = split;
        this.slice = slice;
        this.flicker = flicker;
    }

    /**
     * Builds a sanitised segment; never throws.
     * <ul>
     *   <li>{@code from} / {@code to}: NaN or ±∞ → unbounded; {@code from > to} → swapped;</li>
     *   <li>{@code intensity}: NaN → {@link #DEFAULT_INTENSITY}; {@code intensityEnd}: NaN → the
     *       intensity; both clamped to 0..1;</li>
     *   <li>{@code exponent}: NaN → 1, clamped to {@link #MIN_EXPONENT}..{@link #MAX_EXPONENT};</li>
     *   <li>{@code rate}: NaN or negative → {@link #DEFAULT_RATE}, at most {@link #MAX_RATE};</li>
     *   <li>{@code split}, {@code slice}, {@code flicker}: NaN → their defaults, clamped to
     *       0..{@link #MAX_WEIGHT}.</li>
     * </ul>
     */
    public static NoteGlitch of(float from, float to, float intensity, float intensityEnd, float exponent,
                                float rate, float split, float slice, float flicker) {
        float lo = Float.isNaN(from) || Float.isInfinite(from) ? Float.NEGATIVE_INFINITY : from;
        float hi = Float.isNaN(to) || Float.isInfinite(to) ? Float.POSITIVE_INFINITY : to;
        if (lo > hi) {
            float t = lo;
            lo = hi;
            hi = t;
        }
        float i0 = Float.isNaN(intensity) ? DEFAULT_INTENSITY : clamp(intensity, 0f, 1f);
        float i1 = Float.isNaN(intensityEnd) ? i0 : clamp(intensityEnd, 0f, 1f);
        float ex = Float.isNaN(exponent) ? 1f : clamp(exponent, MIN_EXPONENT, MAX_EXPONENT);
        float r = Float.isNaN(rate) || rate < 0f ? DEFAULT_RATE : Math.min(rate, MAX_RATE);
        return new NoteGlitch(lo, hi, i0, i1, ex, r, weight(split, DEFAULT_SPLIT), weight(slice, DEFAULT_SLICE),
            weight(flicker, DEFAULT_FLICKER));
    }

    private static float weight(float w, float def) {
        return Float.isNaN(w) ? def : clamp(w, 0f, MAX_WEIGHT);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    /** @return first beat of the segment (−∞ when unbounded). */
    public float from() { return from; }
    /** @return last beat of the segment (+∞ when unbounded). */
    public float to() { return to; }
    /** @return true when {@code from} was given. */
    public boolean hasFrom() { return from != Float.NEGATIVE_INFINITY; }
    /** @return true when {@code to} was given. */
    public boolean hasTo() { return to != Float.POSITIVE_INFINITY; }
    /** @return intensity at {@code from} (0..1). */
    public float intensity() { return intensity; }
    /** @return intensity at {@code to} (0..1; equal to {@link #intensity()} for a steady segment). */
    public float intensityEnd() { return intensityEnd; }
    /** @return easing exponent of the intensity ramp. */
    public float exponent() { return exponent; }
    /** @return new patterns per beat (0 = one pattern for the whole segment). */
    public float rate() { return rate; }
    /** @return colour split weight (0..{@value #MAX_WEIGHT}). */
    public float split() { return split; }
    /** @return sideways slice weight (0..{@value #MAX_WEIGHT}). */
    public float slice() { return slice; }
    /** @return flicker weight (0..{@value #MAX_WEIGHT}). */
    public float flicker() { return flicker; }

    /** @return true when {@code beat} lies in {@code [from, to]}. */
    public boolean covers(float beat) {
        return beat >= from && beat <= to;
    }

    /**
     * @return the intensity at {@code beat}: 0 outside the segment; inside,
     *         {@code intensity + (intensityEnd − intensity)·p^exponent} with
     *         {@code p = (beat − from)/(to − from)} (a segment with an open end, or of zero
     *         length, keeps {@code intensity} / takes {@code intensityEnd})
     */
    public float intensityAt(float beat) {
        if (!covers(beat)) return 0f;
        if (intensityEnd == intensity || !hasFrom() || !hasTo()) return intensity;
        float span = to - from;
        if (!(span > 0f)) return intensityEnd;
        float p = clamp((beat - from) / span, 0f, 1f);
        return intensity + (intensityEnd - intensity) * (float) Math.pow(p, exponent);
    }

    /**
     * @return the pattern number at {@code beat}: {@code floor((beat − from)·rate·speed)} (from
     *         beat 0 when {@code from} is open); constant when the rate is 0
     * @param speed rate multiplier (0.5 with "reduce flashing")
     */
    public long step(float beat, float speed) {
        double r = (double) rate * speed;
        if (!(r > 0.0)) return 0L;
        double t = hasFrom() ? (double) beat - from : beat;
        return (long) Math.floor(t * r);
    }

    /**
     * @return the segment of {@code segments} that glitches the note at {@code beat}: of those
     *         covering it, the one that starts last (the later one in the list on a tie); null when
     *         none does
     */
    public static NoteGlitch activeAt(List<NoteGlitch> segments, float beat) {
        NoteGlitch best = null;
        for (int i = 0, n = segments.size(); i < n; i++) {
            NoteGlitch g = segments.get(i);
            if (g.covers(beat) && (best == null || g.from >= best.from)) best = g;
        }
        return best;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NoteGlitch)) return false;
        NoteGlitch g = (NoteGlitch) o;
        return Float.compare(from, g.from) == 0 && Float.compare(to, g.to) == 0
            && Float.compare(intensity, g.intensity) == 0 && Float.compare(intensityEnd, g.intensityEnd) == 0
            && Float.compare(exponent, g.exponent) == 0 && Float.compare(rate, g.rate) == 0
            && Float.compare(split, g.split) == 0 && Float.compare(slice, g.slice) == 0
            && Float.compare(flicker, g.flicker) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(from);
        h = 31 * h + Float.floatToIntBits(to);
        h = 31 * h + Float.floatToIntBits(intensity);
        h = 31 * h + Float.floatToIntBits(intensityEnd);
        h = 31 * h + Float.floatToIntBits(exponent);
        h = 31 * h + Float.floatToIntBits(rate);
        h = 31 * h + Float.floatToIntBits(split);
        h = 31 * h + Float.floatToIntBits(slice);
        return 31 * h + Float.floatToIntBits(flicker);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "glitch(%s..%s, %s→%s, rate %s, split %s, slice %s, flicker %s)",
            from, to, intensity, intensityEnd, rate, split, slice, flicker);
    }
}
