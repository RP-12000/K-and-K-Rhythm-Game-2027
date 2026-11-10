package org.kelvinizer.elements;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 一段扭曲（谱面 {@code "distortion"} 键里的一段，不可变）：起止拍和强度（可以渐变）。0 不扭曲，1 是漩涡定下的扭曲，2 是两倍。
 * 只是画面效果，引擎从不读取；{@link #of} 会修正非法值，从不抛异常。
 *
 * <p>One distortion segment (CHART_FORMAT_v4 §6.11, Kelvin 2026-10-23 16:57 / 17:01), immutable and pure Java: the
 * slow water wobble of how a note or a veil looks. Strength 0 is still, 1 is the veil's approved wobble (two sine
 * layers of 9 and 3.5 reference units on a 6 s loop), 2 is double. Only a show: the engine never reads it.
 *
 * <p>Chart key (notes of every type and veils, optional), written like {@code "glitch"}: one segment as an object,
 * or several as an array of objects:
 * <pre>
 * "distortion": [ { "from": 64, "to": 66, "intensity": 0, "intensityEnd": 2 }, { "from": 80, "intensity": 0.5 } ]
 * </pre>
 * {@code from} / {@code to} (beats, both inclusive; absent = unbounded; {@code duration} may stand for {@code to}),
 * {@code intensity} (0..{@value #MAX_INTENSITY}, default {@value #DEFAULT_INTENSITY}) eased to {@code intensityEnd}
 * (default = {@code intensity}) with {@code exponent} (default 1) or a custom ease {@code curve} (7.207), as a glitch
 * segment ramps. Outside every segment a
 * note is still ({@value #NOTE_BASE}) and a veil keeps its own wobble ({@value #VEIL_BASE}): {@link #strengthAt}.
 * Where segments overlap, the one that started last wins.
 */
public final class NoteDistortion {
    /** Default {@code intensity}: the veil's own wobble. */
    public static final float DEFAULT_INTENSITY = 1f;
    /** Largest {@code intensity}. */
    public static final float MAX_INTENSITY = 4f;
    /** Strength of a note outside its segments. */
    public static final float NOTE_BASE = 0f;
    /** Strength of a veil outside its segments. */
    public static final float VEIL_BASE = 1f;

    private final float from;
    private final float to;
    private final float intensity;
    private final float intensityEnd;
    private final float exponent;
    /** The custom ease of the ramp ({@link EaseCurve#clean}), or null for {@code p^exponent}. */
    private final float[] curve;

    private NoteDistortion(float from, float to, float intensity, float intensityEnd, float exponent, float[] curve) {
        this.from = from;
        this.to = to;
        this.intensity = intensity;
        this.intensityEnd = intensityEnd;
        this.exponent = exponent;
        this.curve = curve;
    }

    /**
     * Builds a sanitised segment; never throws. {@code from} / {@code to}: NaN or ±∞ → unbounded, swapped when
     * reversed; {@code intensity}: NaN → {@link #DEFAULT_INTENSITY}; {@code intensityEnd}: NaN → the intensity; both
     * clamped to 0..{@link #MAX_INTENSITY}; {@code exponent}: NaN → 1, clamped as a glitch segment's.
     */
    public static NoteDistortion of(float from, float to, float intensity, float intensityEnd, float exponent) {
        return of(from, to, intensity, intensityEnd, exponent, null);
    }

    /** {@link #of} with a custom ease of the ramp, as {@link NoteGlitch#of} takes one. */
    public static NoteDistortion of(float from, float to, float intensity, float intensityEnd, float exponent,
                                    float[] curve) {
        float lo = Float.isNaN(from) || Float.isInfinite(from) ? Float.NEGATIVE_INFINITY : from;
        float hi = Float.isNaN(to) || Float.isInfinite(to) ? Float.POSITIVE_INFINITY : to;
        if (lo > hi) {
            float t = lo;
            lo = hi;
            hi = t;
        }
        float i0 = Float.isNaN(intensity) ? DEFAULT_INTENSITY : clamp(intensity, 0f, MAX_INTENSITY);
        float i1 = Float.isNaN(intensityEnd) ? i0 : clamp(intensityEnd, 0f, MAX_INTENSITY);
        float[] c = EaseCurve.clean(curve);
        float ex = c != null || Float.isNaN(exponent) ? 1f
            : clamp(exponent, NoteGlitch.MIN_EXPONENT, NoteGlitch.MAX_EXPONENT);
        return new NoteDistortion(lo, hi, i0, i1, ex, c);
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
    /** @return strength at {@code from}. */
    public float intensity() { return intensity; }
    /** @return strength at {@code to} (equal to {@link #intensity()} for a steady segment). */
    public float intensityEnd() { return intensityEnd; }
    /** @return easing exponent of the ramp (1 with a custom ease). */
    public float exponent() { return exponent; }
    /** @return a copy of the custom ease of the ramp ({@link EaseCurve}), or null */
    public float[] curve() { return curve != null ? curve.clone() : null; }

    /** @return true when {@code beat} lies in {@code [from, to]}. */
    public boolean covers(float beat) {
        return beat >= from && beat <= to;
    }

    /**
     * @return the strength at {@code beat} inside the segment (0 outside), ramped like a glitch's intensity (along a
     *         custom ease held to 0..{@link #MAX_INTENSITY})
     */
    public float intensityAt(float beat) {
        if (!covers(beat)) return 0f;
        if (intensityEnd == intensity || !hasFrom() || !hasTo()) return intensity;
        float span = to - from;
        if (!(span > 0f)) return intensityEnd;
        float p = clamp((beat - from) / span, 0f, 1f);
        if (curve != null) {
            return clamp(intensity + (intensityEnd - intensity) * EaseCurve.at(curve, p), 0f, MAX_INTENSITY);
        }
        return intensity + (intensityEnd - intensity) * (float) Math.pow(p, exponent);
    }

    /** @return of the segments covering {@code beat}, the one that starts last (the later in the list on a tie). */
    public static NoteDistortion activeAt(List<NoteDistortion> segments, float beat) {
        NoteDistortion best = null;
        for (int i = 0, n = segments.size(); i < n; i++) {
            NoteDistortion d = segments.get(i);
            if (d.covers(beat) && (best == null || d.from >= best.from)) best = d;
        }
        return best;
    }

    /**
     * @return the strength at {@code beat}: the active segment's, or {@code base} when none covers it
     *         ({@link #NOTE_BASE} for a note, {@link #VEIL_BASE} for a veil)
     */
    public static float strengthAt(List<NoteDistortion> segments, float beat, float base) {
        if (segments.isEmpty()) return base;
        NoteDistortion d = activeAt(segments, beat);
        return d == null ? base : d.intensityAt(beat);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NoteDistortion)) return false;
        NoteDistortion d = (NoteDistortion) o;
        return Float.compare(from, d.from) == 0 && Float.compare(to, d.to) == 0
            && Float.compare(intensity, d.intensity) == 0 && Float.compare(intensityEnd, d.intensityEnd) == 0
            && Float.compare(exponent, d.exponent) == 0 && Arrays.equals(curve, d.curve);
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(from);
        h = 31 * h + Float.floatToIntBits(to);
        h = 31 * h + Float.floatToIntBits(intensity);
        h = 31 * h + Float.floatToIntBits(intensityEnd);
        h = 31 * h + Float.floatToIntBits(exponent);
        return curve != null ? 31 * h + Arrays.hashCode(curve) : h;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "distortion(%s..%s, %s→%s%s)", from, to, intensity, intensityEnd,
            curve != null ? ", curve " + Arrays.toString(curve) : "");
    }
}
