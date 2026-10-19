package org.kelvinizer.elements;

import java.util.List;

/**
 * 每帧的轨道几何，是唯一计算轨道 x 位置的地方；音符、触摸、特效和反馈都用它。无限轨（默认）时每条轨道保持自己的宽度
 * （乘全局缩放），从中轴（x 960 加镜头平移）往两边排，露出整个屏幕；有限轨时按各轨道当前的宽度权重分配以中轴为中心、宽 1920 × 全局缩放的一段。
 * 纯 Java，每帧更新不分配内存；比 {@link #MIN_ACTIVE_WIDTH} 窄的轨道既不绘制也不能触摸。
 *
 * <p>Per-frame lane geometry (ARCHITECTURE §13.1): the single place that computes lane x
 * positions. Gameplay uses it for every lane x (notes, touch, FX, feedback). Pure Java, no GL.
 *
 * <p>The caller passes what can be seen, {@code [left, right]} (the screen,
 * {@link #VIEW_LEFT}..{@link #VIEW_RIGHT} stretched); the chart's {@link JudgementLine} gives the
 * global lane scale {@code g} ({@link JudgementLine#laneScaleAt}) and the camera frame {@code c}
 * ({@link JudgementLine#cameraFrameAt}, owner 2026-10-13 05:29), which puts the lanes' axis (the
 * border between lanes −1 and 1) at {@code TILE_CENTRE + c·xScale} ({@link #axis()}). Two modes
 * ({@link Chart#laneLimit()}):
 * <ul>
 *   <li><b>Tiled</b> (unlimited lanes, owner 2026-10-13): lane i is {@code wᵢ·g·xScale} wide;
 *       lanes with positive ids follow each other rightwards from the axis, negative ids
 *       leftwards. Each span is then clipped to {@code [left, right]} (the window), so a lane
 *       outside it has zero width at the window edge (not drawn, not touchable) and the lanes
 *       still tile {@code [fieldLeft, fieldRight]} in id order. Ids are taken as contiguous (the
 *       loader fills the gaps). <b>Inserted lanes</b> (owner 2026-10-15, {@link Lane#inserted()}):
 *       lane 0 sits centred on the axis; any other inserted lane pushes the lanes on both of its
 *       sides apart by half its width each; and all spans shrink about the window centre by
 *       {@code z = S / (S + I)} ({@code S} the window width, {@code I} the inserted lanes' current
 *       widths), so the lanes at the window edges stay there and the inserted lanes take their room
 *       from the lanes on the screen. <b>Deleted lanes</b> (owner 2026-10-17, {@link Lane#deleted()})
 *       mirror this: once its deletion starts, a lane that was not inserted gives back how much
 *       narrower it is than at that start ({@code I} counts it negative, at most {@link #MAX_WIDEN}
 *       times wider), so the lanes on its two sides close up by half each and the screen's lanes
 *       widen to fill the window.</li>
 *   <li><b>Proportional</b> (finite lanes, and every layout set from a plain lane array): the
 *       lanes divide the window in proportion to their current width weights: with
 *       {@code W = Σ wᵢ} and {@code s = (right − left) / W}, lane i spans
 *       {@code left + s·Σ_{j<i} wⱼ} to {@code + s·wᵢ}. If {@code W ≤ 0} (or the window is empty
 *       or inverted) every lane has zero width at {@code left}. For finite lanes of a chart the
 *       window is the frame {@code axis ± 960·g·xScale} (the whole screen by default); from a
 *       plain lane array it is {@code [left, right]} as passed.</li>
 * </ul>
 *
 * <p>Usage: {@link #setLanes} once per chart, then {@link #update} once per frame; the
 * accessors read the last update. {@code update} is O(lanes) and never allocates, and every
 * accessor is O(1) except {@link #indexOf} and {@link #indexAtX} (O(log lanes)).
 *
 * <p>A lane narrower than {@link #MIN_ACTIVE_WIDTH} ref units is neither drawn nor touchable
 * ({@link #active(int)} is false and {@link #indexAtX} skips it).
 */
public final class LaneLayout {
    /** Lanes narrower than this (ref units) are not drawn and not touchable. */
    public static final float MIN_ACTIVE_WIDTH = 1f;
    /** x of the lanes' axis at camera frame 0: lane 1 begins and lane −1 ends here (the reference centre). */
    public static final float TILE_CENTRE = 960f;
    /** Left edge of the reference screen, what the lanes show across ({@link #update}). */
    public static final float VIEW_LEFT = 0f;
    /** Right edge of the reference screen. */
    public static final float VIEW_RIGHT = 1920f;
    /** Most lanes' worth of TIDE pattern across a window ({@link #patternLanes()}). */
    public static final int MAX_PATTERN_LANES = 128;
    /** Tiled mode: the most deleted lanes widen the others by (when they give back nearly the whole window). */
    public static final float MAX_WIDEN = 8f;

    private Lane[] lanes = new Lane[0];
    private int[] ids = new int[0];
    private float[] weights = new float[0];
    private float[] lefts = new float[0];
    private float[] widths = new float[0];
    private float[] opacities = new float[0];
    private int count;
    private float windowLeft;
    private float windowRight;
    private float fieldLeft;
    private float fieldRight;
    private float totalWeight;
    private boolean tiled;
    /** Source of the global lane scale and the camera frame; null = 1 and 0 (a plain lane array). */
    private JudgementLine lines;
    private float axis = TILE_CENTRE;
    private int patternLanes = 1;
    private float patternWidth;
    private float patternOrigin;
    /** Tiled mode: the factor inserted lanes shrink (deleted lanes widen) the others by this frame (1 without any). */
    private float squeeze = 1f;

    /** Creates an empty layout (no lanes); call {@link #setLanes} first. */
    public LaneLayout() {
    }

    /** Creates a layout for {@code lanes} (see {@link #setLanes(Lane[])}). */
    public LaneLayout(Lane[] lanes) {
        setLanes(lanes);
    }

    /** Creates a layout for the lanes of {@code chart}, in its lane mode. */
    public LaneLayout(Chart chart) {
        setLanes(chart);
    }

    /**
     * Sets the lanes of {@code chart} and its lane mode: tiled for unlimited lanes, else
     * proportional over the camera frame; both with the chart's global lane scale and camera frame.
     */
    public void setLanes(Chart chart) {
        setLanes(chart.lanes());
        tiled = chart.unlimitedLanes();
        lines = chart.lineEvents();
        if (tiled) {
            // the screen over the narrowest pattern, so the bound holds at every beat
            float p = Lane.DEFAULT_WIDTH * Math.min(1f, lines.laneScaleMin());
            float w = VIEW_RIGHT - VIEW_LEFT;
            patternLanes = !(p > 0f) || w / p >= MAX_PATTERN_LANES ? MAX_PATTERN_LANES
                : Math.max(1, (int) Math.ceil(w / p));
            // each inserted lane squeezes about one more lane's pattern onto the screen
            for (int i = 0; i < count; i++) {
                if (lanes[i].inserted()) patternLanes = Math.min(MAX_PATTERN_LANES, patternLanes + 2);
            }
        }
    }

    /**
     * Sets the lane set (copied by reference into internal arrays, which are reused when large
     * enough), proportional mode. Geometry is zero until the next {@link #update}.
     *
     * @param lanes lanes in strictly ascending id order (as {@link Chart#lanes()} returns them)
     * @throws IllegalArgumentException when a lane is null or the ids are not strictly ascending
     */
    public void setLanes(Lane[] lanes) {
        int n = lanes != null ? lanes.length : 0;
        for (int i = 0; i < n; i++) {
            if (lanes[i] == null) throw new IllegalArgumentException("null lane at " + i);
            if (i > 0 && lanes[i].id() <= lanes[i - 1].id()) {
                throw new IllegalArgumentException("lane ids must be strictly ascending: "
                    + lanes[i - 1].id() + " then " + lanes[i].id());
            }
        }
        if (this.lanes.length < n) {
            this.lanes = new Lane[n];
            ids = new int[n];
            weights = new float[n];
            lefts = new float[n];
            widths = new float[n];
            opacities = new float[n];
        }
        for (int i = 0; i < this.lanes.length; i++) {
            this.lanes[i] = i < n ? lanes[i] : null;
        }
        count = n;
        tiled = false;
        lines = null;
        patternLanes = Math.max(1, n);
        for (int i = 0; i < n; i++) {
            ids[i] = lanes[i].id();
            weights[i] = 0f;
            lefts[i] = 0f;
            widths[i] = 0f;
            opacities[i] = 0f;
        }
        windowLeft = windowRight = fieldLeft = fieldRight = totalWeight = 0f;
    }

    /** Same as {@link #setLanes(Lane[])} for a list. */
    public void setLanes(List<Lane> lanes) {
        setLanes(lanes != null ? lanes.toArray(new Lane[0]) : null);
    }

    /** {@link #update(float, float, float, float)} in reference units ({@code xScale} 1). */
    public void update(float beat, float left, float right) {
        update(beat, left, right, 1f);
    }

    /**
     * Recomputes every lane's span and opacity for one frame. O(lanes), no allocation.
     *
     * @param beat   chart time in beats (evaluates the lane width / opacity, global lane scale and
     *               camera frame events)
     * @param left   left x of what can be seen: the screen, {@link #VIEW_LEFT} stretched
     * @param right  right x of what can be seen: {@link #VIEW_RIGHT} stretched
     * @param xScale world units per reference unit, about {@link #TILE_CENTRE}, when the caller
     *               passes a stretched screen (the gameplay field)
     */
    public void update(float beat, float left, float right, float xScale) {
        float kx = xScale > 0f && xScale < Float.POSITIVE_INFINITY ? xScale : 1f;
        float g = lines != null ? lines.laneScaleAt(beat) : 1f;
        axis = TILE_CENTRE + (lines != null ? lines.cameraFrameAt(beat) : 0f) * kx;
        if (lines != null && !tiled) {
            // finite lanes: the camera frame, 1920 wide at scale 1 (the whole screen by default)
            float half = (TILE_CENTRE - VIEW_LEFT) * g * kx;
            left = axis - half;
            right = axis + half;
        }
        windowLeft = left;
        windowRight = right;
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            Lane lane = lanes[i];
            float w = lane.widthAt(beat);
            weights[i] = w;
            sum += w;
            opacities[i] = lane.opacityAt(beat);
        }
        totalWeight = sum;
        float span = right - left;
        if (tiled) {
            // TIDE pattern (owner 2026-10-13): a default lane times the scale, capped at 1
            tile(left, right, kx * g, beat);
            // never sparser than the default, also when deleted lanes widen the others (owner 2026-10-17)
            float f = Math.min(g, 1f) * squeeze;
            if (f > 1f) f = 1f;
            float p = Lane.DEFAULT_WIDTH * kx * f;
            float least = span / patternLanes;
            patternWidth = p > least ? p : least;
            return;
        }
        patternWidth = span / patternLanes;
        patternOrigin = left;
        if (!(sum > 0f) || !(span > 0f) || sum == Float.POSITIVE_INFINITY || span == Float.POSITIVE_INFINITY) {
            // W ≤ 0 or an empty/inverted window: every lane collapses at the left edge.
            float x = left == left ? left : 0f;
            for (int i = 0; i < count; i++) {
                lefts[i] = x;
                widths[i] = 0f;
            }
            fieldLeft = fieldRight = x;
            return;
        }
        float s = span / sum;
        float cum = 0f;
        for (int i = 0; i < count; i++) {
            lefts[i] = left + s * cum;
            widths[i] = s * weights[i];
            cum += weights[i];
        }
        fieldLeft = left;
        fieldRight = right;
    }

    /**
     * Tiled mode: lanes 1, 2, … rightwards and −1, −2, … leftwards from the axis, each
     * {@code weight·k} wide ({@code k = xScale·g}). A lane that reaches into the window keeps its
     * whole span, even past the screen edge (so lanes slide off the screen instead of squeezing,
     * owner 2026-10-13 camera frame); one wholly outside collapses to zero width at its end
     * nearer the window. The field is the span of the lanes that reach into the window. Inserted
     * lanes (owner 2026-10-15): lane 0 is centred on the axis, another inserted lane moves the
     * lanes on its two sides apart by half its width each, then every span shrinks about the
     * window centre by {@link #squeeze()} so the window edges keep their lanes. Deleted lanes (owner
     * 2026-10-17) do the opposite with the room they give back ({@link #room}).
     */
    private void tile(float left, float right, float k, float beat) {
        squeeze = 1f;
        patternOrigin = axis;
        if (!(right > left)) {
            // NaN or an empty/inverted window: every lane collapses at the left edge.
            float x = left == left ? left : 0f;
            for (int i = 0; i < count; i++) {
                lefts[i] = x;
                widths[i] = 0f;
            }
            fieldLeft = fieldRight = x;
            return;
        }
        int p = 0;
        while (p < count && ids[p] < 0) p++;
        // lane 0 (always an inserted lane) sits centred on the axis
        int q = p < count && ids[p] == 0 ? p + 1 : p;
        float half = q > p ? weights[p] * k * 0.5f : 0f;
        float room = 2f * half;
        float shift = 0f;
        float edge = axis + half;
        for (int i = q; i < count; i++) {
            float w = weights[i] * k;
            lefts[i] = edge;
            widths[i] = w;
            edge += w;
            float r = room(i, w, k, beat);
            if (r != 0f) {
                room += r;
                shift -= 0.5f * r;
            }
        }
        edge = axis - half;
        for (int i = p - 1; i >= 0; i--) {
            float w = weights[i] * k;
            edge -= w;
            lefts[i] = edge;
            widths[i] = w;
            float r = room(i, w, k, beat);
            if (r != 0f) {
                room += r;
                shift += 0.5f * r;
            }
        }
        if (q > p) {
            lefts[p] = axis - half;
            widths[p] = 2f * half;
        }
        // Inserted lanes take their room from the screen (owner 2026-10-15), deleted lanes give it back
        // (2026-10-17): every span shrinks or widens about the window centre so the lanes at the window
        // edges stay there.
        float c = 0.5f * (left + right);
        float s = right - left;
        float z = room != 0f && room > Float.NEGATIVE_INFINITY && room < Float.POSITIVE_INFINITY
            ? s / Math.max(s + room, s / MAX_WIDEN) : 1f;
        squeeze = z;
        patternOrigin = c + z * (axis + half + shift - c);
        axis = c + z * (axis + shift - c);
        int first = -1, last = -1;
        for (int i = 0; i < count; i++) {
            float a = c + z * (lefts[i] + shift - c);
            if (place(i, a, a + widths[i] * z, left, right)) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) {
            fieldLeft = fieldRight = Math.min(Math.max(axis, left), right);
        } else {
            fieldLeft = lefts[first];
            fieldRight = lefts[last] + widths[last];
        }
    }

    /**
     * Tiled mode: the room lane {@code i} takes from the lanes on the screen (+) or gives back to them
     * (−) this frame, {@code w} being its width times {@code k}: an inserted lane takes its whole width
     * (also while it is deleted again); any other deleted lane, once its deletion has started, gives back
     * how much narrower it is than at that start ({@link Lane#deletedFrom()}).
     */
    private float room(int i, float w, float k, float beat) {
        Lane l = lanes[i];
        if (l.inserted()) return w;
        Events d = l.deletion();
        return d != null && beat >= d.startTime() ? w - l.deletedFrom() * k : 0f;
    }

    /**
     * Lane {@code i} spans {@code [a, b]} when it reaches into {@code (left, right)}; else zero
     * width at its end nearer the window (lefts stay non-decreasing).
     *
     * @return true when the lane reaches into the window
     */
    private boolean place(int i, float a, float b, float left, float right) {
        if (b > left && a < right) {
            lefts[i] = a;
            widths[i] = b - a;
            return true;
        }
        lefts[i] = b <= left ? b : a;
        widths[i] = 0f;
        return false;
    }

    /** @return true when the lanes keep their widths (unlimited lanes), false when they share the window. */
    public boolean tiled() { return tiled; }

    /**
     * @return lanes' worth of TIDE pattern (ground wave, pearls) across the window, the bound its
     *         quads are counted with: the lane count (at least 1) when proportional; tiled, the
     *         reference screen over the chart's narrowest {@link #patternWidth} (at most
     *         {@link #MAX_PATTERN_LANES}), the same at every beat
     */
    public int patternLanes() { return patternLanes; }

    /**
     * @return the width the TIDE pattern repeats with at the last update: the window width over
     *         {@link #patternLanes} when proportional (the mean lane width); tiled (owner
     *         2026-10-13), a default lane times the global lane scale, capped at 1, times
     *         {@code xScale} and the {@link #squeeze()} (that product capped at 1 too, when deleted lanes
     *         widen the others), so the pattern follows the scale and never widens past the default (at
     *         least the window width over {@link #patternLanes})
     */
    public float patternWidth() { return patternWidth; }

    /**
     * @return x the TIDE pattern is anchored at: the window left (proportional) or, tiled, the
     *         {@link #axis} (the right edge of lane 0 when the chart inserts one)
     */
    public float patternOrigin() { return patternOrigin; }

    /**
     * @return x of the lanes' axis (the border between lanes −1 and 1, the centre of lane 0) at the
     *         last update: {@link #TILE_CENTRE} plus the camera frame times {@code xScale}; tiled, then
     *         moved by inserted lanes other than 0 and pulled towards the window centre by the
     *         {@link #squeeze()}
     */
    public float axis() { return axis; }

    /**
     * @return tiled lanes: the factor every span shrinks by this frame so the inserted lanes fit
     *         on the screen ({@code S / (S + I)}, 1 without inserted lanes), or widens by (above 1, at most
     *         {@link #MAX_WIDEN}) when deleted lanes give their room back; 1 when the lanes share the window
     */
    public float squeeze() { return tiled ? squeeze : 1f; }
    /** @return number of lanes. */
    public int count() { return count; }
    /** @return id of the lane at index {@code i} (ascending). */
    public int idAt(int i) { return ids[i]; }
    /** @return the lane at index {@code i}. */
    public Lane lane(int i) { return lanes[i]; }

    /** @return the index of lane {@code laneId}, or −1 when the layout has no such lane. O(log lanes). */
    public int indexOf(int laneId) {
        int lo = 0;
        int hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int v = ids[mid];
            if (v < laneId) lo = mid + 1;
            else if (v > laneId) hi = mid - 1;
            else return mid;
        }
        return -1;
    }

    /** @return left x of lane {@code i}. */
    public float left(int i) { return lefts[i]; }
    /** @return screen width of lane {@code i} in ref units (≥ 0). */
    public float width(int i) { return widths[i]; }
    /** @return right x of lane {@code i}. */
    public float right(int i) { return lefts[i] + widths[i]; }
    /** @return centre x of lane {@code i}. */
    public float centre(int i) { return lefts[i] + widths[i] * 0.5f; }
    /** @return opacity of lane {@code i} (0..1), for its furniture only. */
    public float opacity(int i) { return opacities[i]; }
    /** @return the evaluated width weight of lane {@code i} (≥ 0). */
    public float weight(int i) { return weights[i]; }
    /** @return Σ of the evaluated width weights. */
    public float totalWeight() { return totalWeight; }

    /** @return true when lane {@code i} is at least {@link #MIN_ACTIVE_WIDTH} wide (drawn and touchable). */
    public boolean active(int i) {
        return widths[i] >= MIN_ACTIVE_WIDTH;
    }

    /** @return left edge of the lane field (the window's left, or the collapse point). */
    public float fieldLeft() { return fieldLeft; }
    /** @return right edge of the lane field (the window's right, or the collapse point). */
    public float fieldRight() { return fieldRight; }
    /** @return window left passed to the last {@link #update} (a TIDE bar spans the window). */
    public float windowLeft() { return windowLeft; }
    /** @return window right passed to the last {@link #update}. */
    public float windowRight() { return windowRight; }

    /**
     * Resolves an x to a lane. Lane i covers {@code [left(i), right(i))}; a point on a boundary
     * belongs to the lane on its right, and the field's right edge belongs to the last lane.
     * O(log lanes), no allocation.
     *
     * @return the lane index under {@code x}, or −1 when {@code x} is outside the field, the field
     *         is empty, or the lane under {@code x} is narrower than {@link #MIN_ACTIVE_WIDTH}
     */
    public int indexAtX(float x) {
        if (count == 0 || !(x >= fieldLeft) || x > fieldRight || !(fieldRight > fieldLeft)) return -1;
        // Last lane whose left edge is at or before x (lefts are non-decreasing).
        int lo = 0;
        int hi = count - 1;
        int i = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lefts[mid] <= x) {
                i = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        // Only at the field's right edge (or a rounding ulp before it) can the candidate end
        // before x: step back over zero-width lanes collapsed there to the last real lane.
        if (x >= lefts[i] + widths[i]) {
            while (i > 0 && widths[i] <= 0f) i--;
        }
        return widths[i] >= MIN_ACTIVE_WIDTH ? i : -1;
    }
}
