package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自定义线条（谱面 {@code "strokes"} 里的一条，不可变）：表演用、从不判定的直线或曲线，可以画入、收回、改透明度、故障和扭曲。
 * 这里只有谱面写的内容和按拍子算出来的值，不碰屏幕；{@link #of} 会修正非法值，从不抛异常。
 *
 * <p>A custom line (自定义线条, CHART_FORMAT §13; Kelvin 2026-11-06 18:44 「加那种自定义线条，可以自己画在上面，曲线直线都可以
 * …这种自定义线条是表演用的，也可glitch，透明，distort，并且要和drag的那种线条做区分」, the approved mock-up v4), immutable and
 * pure Java. Its points are in reference units like a floating note's centre: x from the screen's centre line, y 0–1200
 * from the bottom. It is straight pieces between them, or a Catmull-Rom curve through them ({@link #curve}), back to
 * the first when {@link #closed}. It shows from {@link #from} to {@link #to} (beats), drawn in from its start over
 * {@link #drawIn} beats and taken back from its start over the last {@link #drawOut} beats. It has a width, a colour
 * and an opacity ({@link #opacityAt}), glitch segments and distortion segments as notes have (CHART_FORMAT_v4 §6.9,
 * §6.11), and is drawn under the notes, or over them when {@link #front}. Never judged: the engine never reads it.
 */
public final class Stroke {
    /** Width of a line that writes none, and the widest one (reference units). */
    public static final float DEFAULT_WIDTH = 4f, MAX_WIDTH = 200f;
    /** Colour of a line that writes none (RGBA8888): the ink white of the pair lines. */
    public static final int DEFAULT_COLOR = 0xEAF2FFFF;
    /** Most points a line keeps, and the farthest a point may be from the screen's centre (reference units). */
    public static final int MAX_POINTS = 256;
    public static final float MAX_REACH = 20000f;
    /** Pieces a curve gets between two of its points, at least. */
    static final int CURVE_PIECES = 4;

    private final float[] points;
    private final boolean curve;
    private final boolean closed;
    private final boolean front;
    private final float from;
    private final float to;
    private final float drawIn;
    private final float drawOut;
    private final float width;
    private final int color;
    private final float opacity;
    private final List<Events> opacityEvents;
    private final List<NoteGlitch> glitches;
    private final List<NoteDistortion> distortions;
    private final boolean fixed;

    private Stroke(float[] points, boolean curve, boolean closed, boolean front, float from, float to, float drawIn,
                   float drawOut, float width, int color, float opacity, List<Events> opacityEvents,
                   List<NoteGlitch> glitches, List<NoteDistortion> distortions, boolean fixed) {
        this.points = points;
        this.curve = curve;
        this.closed = closed;
        this.front = front;
        this.from = from;
        this.to = to;
        this.drawIn = drawIn;
        this.drawOut = drawOut;
        this.width = width;
        this.color = color;
        this.opacity = opacity;
        this.opacityEvents = opacityEvents;
        this.glitches = glitches;
        this.distortions = distortions;
        this.fixed = fixed;
    }

    /**
     * Builds a sanitised line; never throws.
     *
     * @param points   x, y pairs (null: none); pairs that are not two finite numbers within {@link #MAX_REACH} are
     *                 left out, past {@link #MAX_POINTS} the rest
     * @param from     first beat it shows (NaN or ±∞: from the start); swapped with {@code to} when after it
     * @param to       last beat it shows (NaN or ±∞: to the end)
     * @param drawIn   beats to draw it in (not finite or below 0: 0, at once)
     * @param drawOut  beats to take it back before {@code to} (as {@code drawIn}; none without a {@code to})
     * @param width    reference units (not positive or not finite: {@link #DEFAULT_WIDTH}; at most {@link #MAX_WIDTH})
     * @param color    RGBA8888
     * @param opacity  0..1 (NaN: 1)
     * @param opacityEvents opacity events (copied; null for none)
     * @param glitches glitch segments (null for none)
     * @param distortions distortion segments (null for none)
     * @return the line, or null when fewer than two points are left
     */
    public static Stroke of(float[] points, boolean curve, boolean closed, boolean front, float from, float to,
                            float drawIn, float drawOut, float width, int color, float opacity,
                            List<Events> opacityEvents, List<NoteGlitch> glitches, List<NoteDistortion> distortions) {
        boolean fixed = false;
        int n = points == null ? 0 : points.length / 2;
        float[] kept = new float[2 * Math.min(n, MAX_POINTS)];
        int k = 0;
        for (int i = 0; i < n; i++) {
            float x = points[2 * i], y = points[2 * i + 1];
            if (!(Math.abs(x) <= MAX_REACH) || !(Math.abs(y) <= MAX_REACH) || k == kept.length) {
                fixed = true;
                continue;
            }
            kept[k++] = x;
            kept[k++] = y;
        }
        if (points != null && points.length % 2 != 0) fixed = true;
        if (k < 4) return null;
        if (k < kept.length) {
            float[] t = new float[k];
            System.arraycopy(kept, 0, t, 0, k);
            kept = t;
        }
        if (closed && k < 6) { // a closed line needs three points
            closed = false;
            fixed = true;
        }
        float lo = Float.isNaN(from) || Float.isInfinite(from) ? Float.NEGATIVE_INFINITY : from;
        float hi = Float.isNaN(to) || Float.isInfinite(to) ? Float.POSITIVE_INFINITY : to;
        if (lo > hi) {
            float t = lo;
            lo = hi;
            hi = t;
            fixed = true;
        }
        if (!(drawIn >= 0f) || drawIn == Float.POSITIVE_INFINITY) {
            drawIn = 0f;
            fixed = true;
        }
        if (!(drawOut >= 0f) || drawOut == Float.POSITIVE_INFINITY) {
            drawOut = 0f;
            fixed = true;
        }
        if (!(width > 0f) || width == Float.POSITIVE_INFINITY) {
            width = DEFAULT_WIDTH;
            fixed = true;
        } else if (width > MAX_WIDTH) {
            width = MAX_WIDTH;
            fixed = true;
        }
        if (Float.isNaN(opacity)) {
            opacity = 1f;
            fixed = true;
        } else if (opacity < 0f || opacity > 1f) {
            opacity = Math.max(0f, Math.min(1f, opacity));
            fixed = true;
        }
        return new Stroke(kept, curve, closed, front, lo, hi, drawIn, drawOut, width, color, opacity,
            copy(opacityEvents), glitches == null ? Collections.emptyList() : Collections.unmodifiableList(glitches),
            distortions == null ? Collections.emptyList() : Collections.unmodifiableList(distortions), fixed);
    }

    private static List<Events> copy(List<Events> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        List<Events> out = new ArrayList<>(events);
        out.sort((a, b) -> Float.compare(a.startTime(), b.startTime()));
        return Collections.unmodifiableList(out);
    }

    /** @return the points, x and y in turn (the line's own array: do not change it) */
    public float[] points() { return points; }
    /** @return how many points it has (2..{@link #MAX_POINTS}) */
    public int pointCount() { return points.length / 2; }
    /** @return true for a Catmull-Rom curve through the points, false for straight pieces */
    public boolean curve() { return curve; }
    /** @return true when it goes back to its first point */
    public boolean closed() { return closed; }
    /** @return true when it is drawn over the notes (under them otherwise) */
    public boolean front() { return front; }
    /** @return first beat it shows (−∞ when it shows from the start) */
    public float from() { return from; }
    /** @return last beat it shows (+∞ when it shows to the end) */
    public float to() { return to; }
    /** @return beats it takes to be drawn in */
    public float drawIn() { return drawIn; }
    /** @return beats it takes to be taken back, ending at {@link #to} */
    public float drawOut() { return drawOut; }
    /** @return width in reference units */
    public float width() { return width; }
    /** @return colour, RGBA8888 */
    public int color() { return color; }
    /** @return opacity before its opacity events */
    public float baseOpacity() { return opacity; }
    /** @return opacity events, sorted (unmodifiable) */
    public List<Events> opacityEvents() { return opacityEvents; }
    /** @return glitch segments (unmodifiable) */
    public List<NoteGlitch> glitches() { return glitches; }
    /** @return distortion segments (unmodifiable) */
    public List<NoteDistortion> distortions() { return distortions; }
    /** @return true when building it had to change a value (for the load report; not a value of the line) */
    boolean fixed() { return fixed; }

    /** @return true when it is on screen at {@code beat} ({@link #from} … {@link #to}, both included) */
    public boolean showsAt(float beat) {
        return beat >= from && beat <= to;
    }

    /**
     * @return its opacity at {@code beat} (0 when it does not show): its events over its own value, drawn as a note's
     *         ({@link Note#shownOpacity}: past 1 it blinks)
     */
    public float opacityAt(float beat) {
        if (!showsAt(beat)) return 0f;
        return Note.shownOpacity(Events.calculateValue(opacityEvents, beat, opacity));
    }

    /** @return the share of its length drawn in at {@code beat}, 0..1 (eased) */
    public float headAt(float beat) {
        if (drawIn <= 0f || from == Float.NEGATIVE_INFINITY) return 1f;
        return smooth((beat - from) / drawIn);
    }

    /** @return the share of its length taken back from its start at {@code beat}, 0..1 (eased) */
    public float tailAt(float beat) {
        if (drawOut <= 0f || to == Float.POSITIVE_INFINITY) return 0f;
        return smooth((beat - (to - drawOut)) / drawOut);
    }

    private static float smooth(float u) {
        u = u < 0f ? 0f : u > 1f ? 1f : u;
        return u * u * (3f - 2f * u);
    }

    /**
     * @param step the most reference units between two points of a straight piece (and about that on a curve)
     * @return the line traced as points (x, y in turn, reference units): every written point of a straight line and
     *         points between them, or a Catmull-Rom curve through the written points; a closed line ends on its
     *         first point. A new array each call.
     */
    public float[] trace(float step) {
        step = Math.max(0.5f, step);
        int n = pointCount();
        int spans = closed ? n : n - 1;
        float[] out = new float[64];
        int k = 0;
        out[k++] = points[0];
        out[k++] = points[1];
        for (int s = 0; s < spans; s++) {
            int a = s, b = (s + 1) % n;
            float ax = points[2 * a], ay = points[2 * a + 1], bx = points[2 * b], by = points[2 * b + 1];
            float len = (float) Math.hypot(bx - ax, by - ay);
            int pieces = Math.max(curve ? CURVE_PIECES : 1, (int) Math.ceil(len / step));
            int p0 = curve ? (closed ? (a - 1 + n) % n : Math.max(a - 1, 0)) : a;
            int p3 = curve ? (closed ? (b + 1) % n : Math.min(b + 1, n - 1)) : b;
            for (int q = 1; q <= pieces; q++) {
                float u = q / (float) pieces;
                if (k + 2 > out.length) {
                    float[] t = new float[out.length * 2];
                    System.arraycopy(out, 0, t, 0, k);
                    out = t;
                }
                if (curve) {
                    out[k++] = catmullRom(points[2 * p0], ax, bx, points[2 * p3], u);
                    out[k++] = catmullRom(points[2 * p0 + 1], ay, by, points[2 * p3 + 1], u);
                } else {
                    out[k++] = ax + (bx - ax) * u;
                    out[k++] = ay + (by - ay) * u;
                }
            }
        }
        float[] t = new float[k];
        System.arraycopy(out, 0, t, 0, k);
        return t;
    }

    /** @return the uniform Catmull-Rom curve through p1 (u 0) and p2 (u 1), with p0 and p3 beside them */
    static float catmullRom(float p0, float p1, float p2, float p3, float u) {
        float u2 = u * u, u3 = u2 * u;
        return 0.5f * (2f * p1 + (p2 - p0) * u + (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2
            + (3f * p1 - p0 - 3f * p2 + p3) * u3);
    }
}
