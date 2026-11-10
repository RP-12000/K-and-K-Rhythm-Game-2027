package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 漩涡 VEIL：谱面里一块会动的遮挡区，圆形或多边形，不属于任何轨道。在它里面按下的手指抬起前什么都判定不了；它不改变音符的时间。
 * 位置、角度、透明度按事件变化，也可以有故障效果（只是画面）。不可变，{@link #of} 会修正非法值，从不抛异常。
 *
 * <p>A veil (CHART_FORMAT_v4 §6.10, Kelvin 2026-10-23 16:16 / 16:17): a circle or a polygon placed freely in the
 * reference frame (not tied to a lane or a line) from {@link #startTime()} to {@link #endTime()}. A finger whose
 * touch-down lands inside a veil that is showing ({@link #blocksAt}) judges nothing until it lifts; notes under it
 * keep their timing. The centre moves ({@code xEvents}, {@code yEvents}), the shape turns about the centre
 * ({@code rotationEvents}, degrees counter-clockwise) and fades ({@code opacityEvents}, 0 = off); every channel
 * takes its base value outside its events, as everywhere in a chart (CHART_FORMAT_v4 §4). Glitch segments
 * ({@link NoteGlitch}, CHART_FORMAT_v4 §6.9) and distortion segments ({@link NoteDistortion}, §6.11; strength 1
 * outside them, Kelvin 16:57) change only how the veil looks, never what it blocks. No trails.
 *
 * <p>The shape is in reference units about the centre: a circle of {@link #radius()}, or a polygon of 3 to
 * {@link #MAX_POINTS} points ({@link #points()}, x right, y up, any order around the centre; the polygon may be
 * concave, a self-crossing outline counts by the even-odd rule). On screen the centre and the shape stretch with
 * the field ({@code x · kx}, {@code y · ky}, like every other chart event; gameplay {@code Veils}). Pure Java,
 * immutable and thread-safe.
 */
public final class Veil {
    /** The two shapes (Kelvin 16:14: 「就是两种不同的：圆形和多边形的」). */
    public enum Shape {
        /** A circle: concentric rings. */
        CIRCLE("circle"),
        /** A polygon: a caustic net. */
        POLYGON("polygon");

        /** The {@code "shape"} string in a chart. */
        public final String json;

        Shape(String json) {
            this.json = json;
        }

        /** @return the shape named {@code name} ignoring case and blanks, or null when unknown. */
        public static Shape parse(String name) {
            if (name == null) return null;
            String s = name.trim().toLowerCase(Locale.ROOT);
            for (Shape v : values()) {
                if (v.json.equals(s)) return v;
            }
            return null;
        }
    }

    /** Most points of a polygon (the shader's limit); further points are dropped. */
    public static final int MAX_POINTS = 16;
    /** Radius of a circle that gives none, in reference units. */
    public static final float DEFAULT_RADIUS = 200f;
    /** Smallest radius and largest coordinate magnitude of the shape, in reference units. */
    public static final float MIN_SIZE = 1f, MAX_SIZE = 4000f;
    /** A curtain's blur when the chart gives none (reference units). */
    public static final float DEFAULT_BLUR = 16f;
    /** The strongest blur a curtain takes (reference units). */
    public static final float MAX_BLUR = 64f;

    private final Shape shape;
    private final float radius;
    private final float[] points;
    private final float x;
    private final float y;
    private final float rotation;
    private final float opacity;
    private final float startTime;
    private final float endTime;
    private final List<Events> xEvents;
    private final List<Events> yEvents;
    private final List<Events> rotationEvents;
    private final List<Events> opacityEvents;
    private final List<NoteGlitch> glitches;
    private final List<NoteDistortion> distortions;
    private final float extent;
    /** Curtain blur (reference units); 0 for a veil, which never blurs. */
    private final float blur;
    private final List<Events> blurEvents;
    /** Its 3D motion ({@link Channels#SOLID}) and its points' moves (one entry a point, null: unmoved), or null. */
    private final Channels space;
    private final Channels[] moves;

    private Veil(Shape shape, float radius, float[] points, float x, float y, float rotation, float opacity,
                 float startTime, float endTime, List<Events> xEvents, List<Events> yEvents,
                 List<Events> rotationEvents, List<Events> opacityEvents, List<NoteGlitch> glitches,
                 List<NoteDistortion> distortions, float blur, List<Events> blurEvents, Channels space,
                 Channels[] moves) {
        this.shape = shape;
        this.radius = radius;
        this.points = points;
        this.x = x;
        this.y = y;
        this.rotation = rotation;
        this.opacity = opacity;
        this.startTime = startTime;
        this.endTime = endTime;
        this.xEvents = Events.sortedCopy(xEvents);
        this.yEvents = Events.sortedCopy(yEvents);
        this.rotationEvents = Events.sortedCopy(rotationEvents);
        this.opacityEvents = Events.sortedCopy(opacityEvents);
        this.glitches = glitches == null || glitches.isEmpty() ? Collections.<NoteGlitch>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(glitches));
        List<NoteDistortion> d = new ArrayList<>();
        if (distortions != null) {
            for (NoteDistortion seg : distortions) {
                if (seg != null) d.add(seg);
            }
        }
        this.distortions = d.isEmpty() ? Collections.<NoteDistortion>emptyList() : Collections.unmodifiableList(d);
        float e = 0f;
        if (shape == Shape.CIRCLE) {
            e = radius;
        } else {
            for (int i = 0; i < points.length; i += 2) e = Math.max(e, (float) Math.hypot(points[i], points[i + 1]));
        }
        this.extent = e;
        this.blur = blur;
        this.blurEvents = Events.sortedCopy(blurEvents);
        this.space = space;
        this.moves = moves;
    }

    /**
     * Builds a sanitised veil; never throws. {@link #isValid} tells whether it has a usable shape.
     * <ul>
     *   <li>{@code shape} null: a polygon when {@code points} holds at least one point, else a circle;</li>
     *   <li>{@code radius}: NaN, ±∞ or ≤ 0 → {@link #DEFAULT_RADIUS}; clamped to
     *       {@link #MIN_SIZE}..{@link #MAX_SIZE};</li>
     *   <li>{@code points} (x0, y0, x1, y1, …): pairs with a non-finite value are dropped, values clamped to
     *       ±{@link #MAX_SIZE}, at most {@link #MAX_POINTS} points kept; fewer than 3 left = invalid;</li>
     *   <li>{@code x}, {@code y}, {@code rotation}: NaN or ±∞ → 960, 600, 0; {@code opacity}: NaN → 1,
     *       clamped to 0..1;</li>
     *   <li>{@code startTime} / {@code endTime}: NaN or ±∞ → unbounded; swapped when reversed;</li>
     *   <li>{@code glitches}, {@code distortions}: null = none; null entries are left out.</li>
     * </ul>
     */
    public static Veil of(Shape shape, float radius, float[] points, float x, float y, float rotation,
                          float opacity, float startTime, float endTime, List<Events> xEvents, List<Events> yEvents,
                          List<Events> rotationEvents, List<Events> opacityEvents, List<NoteGlitch> glitches,
                          List<NoteDistortion> distortions) {
        float[] pts = cleanPoints(points);
        Shape s = shape != null ? shape : pts != null && pts.length > 0 ? Shape.POLYGON : Shape.CIRCLE;
        float r = !(radius > 0f) || radius == Float.POSITIVE_INFINITY ? DEFAULT_RADIUS
            : Math.max(MIN_SIZE, Math.min(MAX_SIZE, radius));
        if (s == Shape.CIRCLE || pts == null) pts = new float[0];
        float lo = finite(startTime) ? startTime : Float.NEGATIVE_INFINITY;
        float hi = finite(endTime) ? endTime : Float.POSITIVE_INFINITY;
        if (lo > hi) {
            float t = lo;
            lo = hi;
            hi = t;
        }
        float op = Float.isNaN(opacity) ? 1f : Math.max(0f, Math.min(1f, opacity));
        return new Veil(s, r, pts, finite(x) ? x : 960f, finite(y) ? y : 600f, finite(rotation) ? rotation : 0f, op,
            lo, hi, xEvents, yEvents, rotationEvents, opacityEvents, glitches, distortions, 0f, null, null, null);
    }

    /**
     * This shape as a curtain (水帘, CHART_FORMAT §6.1): the same veil, blurring what is under it with
     * {@code blur} reference units (NaN or ±∞ → {@link #DEFAULT_BLUR}, clamped to 0..{@link #MAX_BLUR}) and
     * {@code blurEvents} (null = none). A curtain never blocks touches; only the veils list is judged.
     */
    public Veil withBlur(float blur, List<Events> blurEvents) {
        float b = finite(blur) ? Math.max(0f, Math.min(MAX_BLUR, blur)) : DEFAULT_BLUR;
        return new Veil(shape, radius, points, x, y, rotation, opacity, startTime, endTime, xEvents, yEvents,
            rotationEvents, opacityEvents, glitches, distortions, b, blurEvents, space, moves);
    }

    /**
     * This veil with a 3D motion (Kelvin 2026-11-07 22:54, CHART_FORMAT §6.1): {@code space} its
     * {@link Channels#SOLID} channels (null: none; depth, the two turns and the size factors, about its centre), and
     * {@code pointMoves} its points' {@link Channels#POINT} offsets by point (null or a null entry: unmoved; a circle
     * has none).
     */
    public Veil withSpace(Channels space, Channels[] pointMoves) {
        Channels s = space != null && space.keys() == Channels.SOLID ? space : null;
        Channels[] m = null;
        for (int i = 0; pointMoves != null && i < Math.min(pointMoves.length, pointCount()); i++) {
            if (pointMoves[i] == null || pointMoves[i].keys() != Channels.POINT) continue;
            if (m == null) m = new Channels[pointCount()];
            m[i] = pointMoves[i];
        }
        return new Veil(shape, radius, points, x, y, rotation, opacity, startTime, endTime, xEvents, yEvents,
            rotationEvents, opacityEvents, glitches, distortions, blur, blurEvents, s, m);
    }

    private static float[] cleanPoints(float[] in) {
        if (in == null) return null;
        float[] out = new float[Math.min(in.length / 2, MAX_POINTS) * 2];
        int n = 0;
        for (int i = 0; i + 1 < in.length && n < out.length; i += 2) {
            if (!finite(in[i]) || !finite(in[i + 1])) continue;
            out[n++] = Math.max(-MAX_SIZE, Math.min(MAX_SIZE, in[i]));
            out[n++] = Math.max(-MAX_SIZE, Math.min(MAX_SIZE, in[i + 1]));
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** @return false for a polygon with fewer than 3 points (the loader drops it). */
    public boolean isValid() {
        return shape == Shape.CIRCLE || points.length >= 6;
    }

    /** @return the shape. */
    public Shape shape() { return shape; }
    /** @return the circle's radius in reference units ({@link #DEFAULT_RADIUS} for a polygon). */
    public float radius() { return radius; }
    /** @return number of polygon points (0 for a circle). */
    public int pointCount() { return points.length / 2; }
    /** @return polygon point i's x about the centre, in reference units. */
    public float pointX(int i) { return points[2 * i]; }
    /** @return polygon point i's y about the centre, in reference units. */
    public float pointY(int i) { return points[2 * i + 1]; }
    /** @return a copy of the polygon's points (x0, y0, x1, y1, …; empty for a circle). */
    public float[] points() { return points.clone(); }
    /** @return farthest distance of the outline from the centre, in reference units. */
    public float extent() { return extent; }
    /** @return base centre x (outside {@code xEvents}). */
    public float baseX() { return x; }
    /** @return base centre y (outside {@code yEvents}). */
    public float baseY() { return y; }
    /** @return base rotation in degrees (outside {@code rotationEvents}). */
    public float baseRotation() { return rotation; }
    /** @return base opacity (outside {@code opacityEvents}). */
    public float baseOpacity() { return opacity; }
    /** @return first beat the veil exists (−∞ when unbounded). */
    public float startTime() { return startTime; }
    /** @return last beat the veil exists (+∞ when unbounded). */
    public float endTime() { return endTime; }
    /** @return true when {@code startTime} was given. */
    public boolean hasStart() { return startTime != Float.NEGATIVE_INFINITY; }
    /** @return true when {@code endTime} was given. */
    public boolean hasEnd() { return endTime != Float.POSITIVE_INFINITY; }
    /** @return centre x events, sorted by start. */
    public List<Events> xEvents() { return xEvents; }
    /** @return centre y events, sorted by start. */
    public List<Events> yEvents() { return yEvents; }
    /** @return rotation events, sorted by start. */
    public List<Events> rotationEvents() { return rotationEvents; }
    /** @return opacity events, sorted by start. */
    public List<Events> opacityEvents() { return opacityEvents; }
    /** @return glitch segments (look only). */
    public List<NoteGlitch> glitches() { return glitches; }
    /** @return distortion segments (look only; strength {@link NoteDistortion#VEIL_BASE} outside them). */
    public List<NoteDistortion> distortions() { return distortions; }

    /** @return the curtain's own blur (reference units); 0 for a veil. */
    public float blur() { return blur; }

    /** @return the curtain's blur events (sorted, unmodifiable). */
    public List<Events> blurEvents() { return blurEvents; }

    /** @return its 3D motion ({@link Channels#SOLID}), or null */
    public Channels space() { return space; }

    /** @return true when a point moves ({@link #pointMoves}) */
    public boolean hasPointMoves() { return moves != null; }

    /** @return point i's offsets ({@link Channels#POINT}), or null when it does not move */
    public Channels pointMoves(int i) { return moves != null ? moves[i] : null; }

    /** @return its depth at {@code beat} (reference units, positive inward; drawing only) */
    public float zAt(float beat) {
        return Channels.at(space, Channels.SOLID, Channels.Z, beat);
    }

    /**
     * @return how wide its outline is drawn and blocks at {@code beat}, of its width: its {@code scaleX} times how much
     *         its turn about the vertical axis shows of it (a card turned edge on is 0)
     */
    public float shapeX(float beat) {
        if (space == null) return 1f;
        return Math.abs(space.at(Channels.SCALE_X, beat) * (float) Math.cos(Math.toRadians(space.at(Channels.ROT_Y,
            beat))));
    }

    /** @return {@link #shapeX} up: {@code scaleY} and the turn about the horizontal axis */
    public float shapeY(float beat) {
        if (space == null) return 1f;
        return Math.abs(space.at(Channels.SCALE_Y, beat) * (float) Math.cos(Math.toRadians(space.at(Channels.ROT_X,
            beat))));
    }

    /** @return point i's x at {@code beat} with its offset ({@link #pointMoves}), reference units about the centre */
    public float pointXAt(int i, float beat) {
        return points[2 * i] + Channels.at(pointMoves(i), Channels.POINT, Channels.MOVE_X, beat);
    }

    /** @return point i's y at {@code beat} with its offset */
    public float pointYAt(int i, float beat) {
        return points[2 * i + 1] + Channels.at(pointMoves(i), Channels.POINT, Channels.MOVE_Y, beat);
    }

    /** @return point i's depth at {@code beat} from the veil's own ({@link #zAt}; drawing only) */
    public float pointZAt(int i, float beat) {
        return Channels.at(pointMoves(i), Channels.POINT, Channels.MOVE_Z, beat);
    }

    /** @return {@link #extent()} with the points where they are at {@code beat} */
    public float extentAt(float beat) {
        if (moves == null) return extent;
        float e = 0f;
        for (int i = 0; i < pointCount(); i++) {
            e = Math.max(e, (float) Math.hypot(pointXAt(i, beat), pointYAt(i, beat)));
        }
        return e;
    }

    /** @return {@link #contains} with the points where they are at {@code beat} */
    public boolean containsAt(float lx, float ly, float beat) {
        if (moves == null) return contains(lx, ly);
        boolean in = false;
        int n = points.length / 2;
        float xj = pointXAt(n - 1, beat), yj = pointYAt(n - 1, beat);
        for (int i = 0; i < n; i++) {
            float xi = pointXAt(i, beat), yi = pointYAt(i, beat);
            if (onSegment(lx, ly, xi, yi, xj, yj)) return true;
            if ((yi > ly) != (yj > ly) && lx < (xj - xi) * (ly - yi) / (yj - yi) + xi) in = !in;
            xj = xi;
            yj = yi;
        }
        return in;
    }

    /** @return the curtain's blur at {@code beat} (reference units, 0..{@link #MAX_BLUR}). */
    public float blurAt(float beat) {
        float b = Events.calculateValue(blurEvents, beat, blur);
        return b > 0f ? Math.min(MAX_BLUR, b) : 0f;
    }

    /** @return the distortion strength at {@code beat} (0 still, 1 the veil's own wobble, 2 double). */
    public float distortionAt(float beat) {
        return NoteDistortion.strengthAt(distortions, beat, NoteDistortion.VEIL_BASE);
    }

    /** @return true when {@code beat} lies in [startTime, endTime]. */
    public boolean existsAt(float beat) {
        return beat >= startTime && beat <= endTime;
    }

    /** @return centre x at {@code beat}, reference units. */
    public float xAt(float beat) {
        return Events.calculateValue(xEvents, beat, x);
    }

    /** @return centre y at {@code beat}, reference units. */
    public float yAt(float beat) {
        return Events.calculateValue(yEvents, beat, y);
    }

    /** @return rotation at {@code beat}, degrees counter-clockwise. */
    public float rotationAt(float beat) {
        return Events.calculateValue(rotationEvents, beat, rotation);
    }

    /** @return opacity at {@code beat}, 0..1 (0 outside [startTime, endTime]). */
    public float opacityAt(float beat) {
        if (!existsAt(beat)) return 0f;
        float a = Events.calculateValue(opacityEvents, beat, opacity);
        return a > 0f ? Math.min(1f, a) : 0f;
    }

    /** @return true when the veil blocks touches at {@code beat}: it exists and its opacity is above 0. */
    public boolean blocksAt(float beat) {
        return opacityAt(beat) > 0f;
    }

    /**
     * @return true when the point (lx, ly), in reference units about the centre in the veil's own (unrotated)
     *         frame, lies inside the exact outline (the edge counts as inside)
     */
    public boolean contains(float lx, float ly) {
        if (shape == Shape.CIRCLE) return lx * lx + ly * ly <= radius * radius;
        boolean in = false;
        int n = points.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = points[2 * i], yi = points[2 * i + 1];
            float xj = points[2 * j], yj = points[2 * j + 1];
            if (onSegment(lx, ly, xi, yi, xj, yj)) return true;
            if ((yi > ly) != (yj > ly) && lx < (xj - xi) * (ly - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static boolean onSegment(float px, float py, float ax, float ay, float bx, float by) {
        float cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        if (Math.abs(cross) > 1e-3f * (Math.abs(bx - ax) + Math.abs(by - ay) + 1f)) return false;
        return px >= Math.min(ax, bx) - 1e-3f && px <= Math.max(ax, bx) + 1e-3f
            && py >= Math.min(ay, by) - 1e-3f && py <= Math.max(ay, by) + 1e-3f;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Veil)) return false;
        Veil v = (Veil) o;
        return shape == v.shape && Float.compare(radius, v.radius) == 0 && Arrays.equals(points, v.points)
            && Float.compare(x, v.x) == 0 && Float.compare(y, v.y) == 0
            && Float.compare(rotation, v.rotation) == 0 && Float.compare(opacity, v.opacity) == 0
            && Float.compare(startTime, v.startTime) == 0 && Float.compare(endTime, v.endTime) == 0
            && xEvents.equals(v.xEvents) && yEvents.equals(v.yEvents) && rotationEvents.equals(v.rotationEvents)
            && opacityEvents.equals(v.opacityEvents) && glitches.equals(v.glitches)
            && distortions.equals(v.distortions) && Float.compare(blur, v.blur) == 0
            && blurEvents.equals(v.blurEvents) && java.util.Objects.equals(space, v.space)
            && Arrays.equals(moves, v.moves);
    }

    @Override
    public int hashCode() {
        int h = shape.hashCode();
        h = 31 * h + Float.floatToIntBits(radius);
        h = 31 * h + Arrays.hashCode(points);
        h = 31 * h + Float.floatToIntBits(x);
        h = 31 * h + Float.floatToIntBits(y);
        h = 31 * h + Float.floatToIntBits(startTime);
        return 31 * h + Float.floatToIntBits(endTime);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "veil(%s, %s at %s, %s, beats %s..%s)", shape.json,
            shape == Shape.CIRCLE ? "r " + radius : pointCount() + " points", x, y, startTime, endTime);
    }
}
