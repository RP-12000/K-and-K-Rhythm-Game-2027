package org.kelvinizer.elements;

import java.util.Locale;

/**
 * 一个音符的拖拽残影参数（谱面的 {@code "trail"} 键，不可变）：长度、密度、透明度、衰减、模式 PATH / LEVEL 和起止拍。
 * 残影只是画面效果，引擎从不读取；{@link #of} 会修正非法值，从不抛异常。
 *
 * <p>Afterimage trail "拖拽残影" of one note (ARCHITECTURE §17, DESIGN §17), immutable and pure
 * Java. A trail is only a show: the engine never reads it.
 *
 * <p>Chart key (v2 notes of every type, optional, absent = {@link #NONE}):
 * <pre>
 * "trail": { "length": 0.5, "density": 16, "alpha": 0.6, "fade": 1.0, "mode": "path", "from": 30, "to": 32 }
 * </pre>
 * <ul>
 *   <li>{@code length} (beats, ≥ 0) and {@code density} (copies per beat, &gt; 0) give the copy
 *       count {@code n = min(MAX_TRAIL_COPIES, floor(length·density + 1e-4))}; copy k (1 = nearest
 *       … n = farthest) shows the note {@code k/density} beats ago ({@link #spacingBeats()}).</li>
 *   <li>{@code alpha} (0..1) and {@code fade} (0.05..20): copy k has opacity
 *       {@code alpha · (1 − k/(n+1))^fade} ({@link #alphaOf(int)}), times the note's own opacity at
 *       the copy's moment; the renderer caps it at {@link #READABILITY_CAP} × the live note's.</li>
 *   <li>{@code mode}: {@link Mode#PATH} (the note exactly as it was) or {@link Mode#LEVEL}
 *       (sideways position, lane width and angle of that moment, current distance from the line).</li>
 *   <li>{@code from} / {@code to} (beats): only copies whose moment lies in {@code [from, to]}
 *       are shown; absent = unbounded.</li>
 * </ul>
 * Use {@link #of} to build a sanitised trail (it never throws); it returns {@link #NONE} when
 * no copy would exist.
 */
public final class NoteTrail {
    /** Most copies one trail can have. */
    public static final int MAX_TRAIL_COPIES = 32;
    /** Default {@code alpha} (opacity of the nearest copy relative to the note). */
    public static final float DEFAULT_ALPHA = 0.6f;
    /** Default {@code fade} exponent. */
    public static final float DEFAULT_FADE = 1f;
    /** Smallest {@code fade} exponent. */
    public static final float MIN_FADE = 0.05f;
    /** Largest {@code fade} exponent. */
    public static final float MAX_FADE = 20f;
    /** Readable default {@code length} for charters (DESIGN §17.2; not applied when the key is absent). */
    public static final float SUGGESTED_LENGTH = 0.5f;
    /** Readable default {@code density} for charters (DESIGN §17.2: 8 copies with the suggested length). */
    public static final float SUGGESTED_DENSITY = 16f;
    /** A copy is never brighter than this × the live note (DESIGN §17.1 readability guard). */
    public static final float READABILITY_CAP = 0.85f;
    /** Rounding slack in the copy count, so 0.5 × 16 is exactly 8 copies. */
    private static final double COUNT_EPS = 1e-4;

    /** How a copy is placed (ARCHITECTURE §17). */
    public enum Mode {
        /** The note exactly as it was {@code k/density} beats ago (the default). */
        PATH("path"),
        /** Sideways position, lane width and angle of that moment; the current distance from the line. */
        LEVEL("level");

        /** Name used in chart JSON ({@code "mode"}). */
        public final String json;

        Mode(String json) {
            this.json = json;
        }

        /** @return the mode named {@code name} ignoring case and surrounding blanks, or null when unknown. */
        public static Mode fromJson(String name) {
            if (name == null) return null;
            String s = name.trim();
            if (PATH.json.equalsIgnoreCase(s)) return PATH;
            if (LEVEL.json.equalsIgnoreCase(s)) return LEVEL;
            return null;
        }
    }

    /** No trail: {@link #copies()} is 0. {@link Note#trail()} returns it for notes without one. */
    public static final NoteTrail NONE = new NoteTrail(0f, 0f, DEFAULT_ALPHA, DEFAULT_FADE, Mode.PATH,
        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 0);

    private final float length;
    private final float density;
    private final float alpha;
    private final float fade;
    private final Mode mode;
    private final float from;
    private final float to;
    private final int copies;
    private final float spacing;

    private NoteTrail(float length, float density, float alpha, float fade, Mode mode, float from,
                      float to, int copies) {
        this.length = length;
        this.density = density;
        this.alpha = alpha;
        this.fade = fade;
        this.mode = mode;
        this.from = from;
        this.to = to;
        this.copies = copies;
        this.spacing = copies > 0 ? 1f / density : 0f;
    }

    /**
     * Builds a sanitised trail (ARCHITECTURE §17); never throws.
     * <ul>
     *   <li>{@code length}: NaN, ±∞ or negative → 0 (no trail);</li>
     *   <li>{@code density}: NaN, ≤ 0 or +∞ → no trail;</li>
     *   <li>{@code alpha}: NaN → {@link #DEFAULT_ALPHA}, otherwise clamped to 0..1;</li>
     *   <li>{@code fade}: NaN → {@link #DEFAULT_FADE}, otherwise clamped to
     *       {@link #MIN_FADE}..{@link #MAX_FADE};</li>
     *   <li>{@code mode}: null → {@link Mode#PATH};</li>
     *   <li>{@code from} / {@code to}: NaN or ±∞ → unbounded (−∞ / +∞), so a non-finite bound
     *       never closes the window; {@code from > to} → swapped.</li>
     * </ul>
     *
     * @return the trail, or {@link #NONE} when it has no copy
     */
    public static NoteTrail of(float length, float density, float alpha, float fade, Mode mode, float from, float to) {
        if (!(length > 0f) || length == Float.POSITIVE_INFINITY) return NONE;
        if (!(density > 0f) || density == Float.POSITIVE_INFINITY) return NONE;
        int n = copyCount(length, density);
        if (n <= 0) return NONE;
        float a = Float.isNaN(alpha) ? DEFAULT_ALPHA : Math.max(0f, Math.min(1f, alpha));
        float f = Float.isNaN(fade) ? DEFAULT_FADE : Math.max(MIN_FADE, Math.min(MAX_FADE, fade));
        float lo = Float.isNaN(from) || Float.isInfinite(from) ? Float.NEGATIVE_INFINITY : from;
        float hi = Float.isNaN(to) || Float.isInfinite(to) ? Float.POSITIVE_INFINITY : to;
        if (lo > hi) {
            float t = lo;
            lo = hi;
            hi = t;
        }
        return new NoteTrail(length, density, a, f, mode != null ? mode : Mode.PATH, lo, hi, n);
    }

    /**
     * @return {@code min(MAX_TRAIL_COPIES, floor(length·density + 1e-4))} for finite positive
     *         inputs, else 0
     */
    public static int copyCount(float length, float density) {
        if (!(length > 0f) || !(density > 0f)) return 0;
        double v = Math.floor((double) length * density + COUNT_EPS);
        if (!(v >= 1.0)) return 0;
        return v >= MAX_TRAIL_COPIES ? MAX_TRAIL_COPIES : (int) v;
    }

    /** @return true for {@link #NONE} (or any trail without copies). */
    public boolean isNone() { return copies == 0; }
    /** @return length in beats as authored (sanitised). */
    public float length() { return length; }
    /** @return copies per beat. */
    public float density() { return density; }
    /** @return opacity of the nearest copy relative to the note (0..1). */
    public float alpha() { return alpha; }
    /** @return fade exponent (0.05..20). */
    public float fade() { return fade; }
    /** @return placement mode. */
    public Mode mode() { return mode; }
    /** @return earliest moment (beats) a copy may show (−∞ when unbounded). */
    public float from() { return from; }
    /** @return latest moment (beats) a copy may show (+∞ when unbounded). */
    public float to() { return to; }
    /** @return true when {@code from} was given. */
    public boolean hasFrom() { return from != Float.NEGATIVE_INFINITY; }
    /** @return true when {@code to} was given. */
    public boolean hasTo() { return to != Float.POSITIVE_INFINITY; }

    /** @return number of copies n (0..{@value #MAX_TRAIL_COPIES}). */
    public int copies() { return copies; }

    /** @return beats between two copies ({@code 1/density}; 0 for {@link #NONE}). */
    public float spacingBeats() { return spacing; }

    /** @return how far back the farthest copy reaches, {@code n/density} beats (≤ {@link #length()}). */
    public float spanBeats() { return copies * spacing; }

    /** @return the moment of copy k at {@code beat}: {@code beat − k/density}. */
    public float momentOf(float beat, int k) {
        return beat - k * spacing;
    }

    /** @return true when {@code moment} lies in {@code [from, to]}. */
    public boolean inWindow(float moment) {
        return moment >= from && moment <= to;
    }

    /**
     * @return opacity of copy k relative to the note, {@code alpha · (1 − k/(n+1))^fade}, for
     *         {@code 1 ≤ k ≤ n}; 0 otherwise
     */
    public float alphaOf(int k) {
        if (k < 1 || k > copies) return 0f;
        double base = 1.0 - k / (double) (copies + 1);
        return (float) (alpha * Math.pow(base, fade));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NoteTrail)) return false;
        NoteTrail t = (NoteTrail) o;
        if (copies == 0 && t.copies == 0) return true;
        return copies == t.copies && mode == t.mode
            && Float.compare(length, t.length) == 0 && Float.compare(density, t.density) == 0
            && Float.compare(alpha, t.alpha) == 0 && Float.compare(fade, t.fade) == 0
            && Float.compare(from, t.from) == 0 && Float.compare(to, t.to) == 0;
    }

    @Override
    public int hashCode() {
        if (copies == 0) return 0;
        int h = mode.hashCode();
        h = 31 * h + Float.floatToIntBits(length);
        h = 31 * h + Float.floatToIntBits(density);
        h = 31 * h + Float.floatToIntBits(alpha);
        h = 31 * h + Float.floatToIntBits(fade);
        h = 31 * h + Float.floatToIntBits(from);
        return 31 * h + Float.floatToIntBits(to);
    }

    @Override
    public String toString() {
        if (copies == 0) return "trail(none)";
        return String.format(Locale.ROOT, "trail(%s %d copies, len %s, dens %s, a %s, fade %s%s%s)", mode.json, copies,
            length, density, alpha, fade, hasFrom() ? ", from " + from : "", hasTo() ? ", to " + to : "");
    }
}
