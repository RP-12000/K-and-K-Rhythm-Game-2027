package org.kelvinizer.elements;

import java.util.List;

import org.kelvinizer.Params;

/**
 * 每帧的轨道几何，是唯一计算轨道 x 位置的地方；音符、触摸、特效和反馈都用它。无限轨（默认）时每条轨道保持自己的宽度
 * （乘全局缩放），从中轴（x 960 加镜头平移）往两边排，露出整个屏幕；谱面的有限轨（2026-11-08 起每条轨道是单独的物件）每条轨道用自己的宽度，
 * 一条挨一条以中轴为中心，加减轨道不挤别的。纯 Java，每帧更新不分配内存；比 {@link #MIN_ACTIVE_WIDTH} 窄的轨道既不绘制也不能触摸。
 * 轨道的整列平移（2026-11-07）也在这里算。
 *
 * <p>Per-frame lane geometry (ARCHITECTURE §13.1): the single place that computes lane x
 * positions. Gameplay uses it for every lane x (notes, touch, FX, feedback). Pure Java, no GL.
 *
 * <p>The caller passes what can be seen, {@code [left, right]} (the screen,
 * {@link #VIEW_LEFT}..{@link #VIEW_RIGHT} stretched); the chart's {@link JudgementLine} gives the
 * global lane scale {@code g} ({@link JudgementLine#laneScaleAt}) and the camera frame {@code c}
 * ({@link JudgementLine#cameraFrameAt}, Kelvin 2026-10-13 05:29), which puts the lanes' axis (the
 * border between lanes −1 and 1) at {@code TILE_CENTRE + c·xScale} ({@link #axis()}). Two modes
 * ({@link Chart#laneLimit()}):
 * <ul>
 *   <li><b>Tiled</b> (unlimited lanes, Kelvin 2026-10-13): lane i is {@code wᵢ·g·xScale} wide;
 *       lanes with positive ids follow each other rightwards from the axis, negative ids
 *       leftwards. Each span is then clipped to {@code [left, right]} (the window), so a lane
 *       outside it has zero width at the window edge (not drawn, not touchable) and the lanes
 *       still tile {@code [fieldLeft, fieldRight]} in id order. Ids are taken as contiguous (the
 *       loader fills the gaps). <b>Inserted lanes</b> (Kelvin 2026-10-15, {@link Lane#inserted()}):
 *       lane 0 sits centred on the axis; any other inserted lane pushes the lanes on both of its
 *       sides apart by half its width each; and all spans shrink about the window centre by
 *       {@code z = S / (S + I)} ({@code S} the window width, {@code I} the inserted lanes' current
 *       widths), so the lanes at the window edges stay there and the inserted lanes take their room
 *       from the lanes on the screen. <b>Deleted lanes</b> (Kelvin 2026-10-17, {@link Lane#deleted()})
 *       mirror this: once its deletion starts, a lane that was not inserted gives back how much
 *       narrower it is than at that start ({@code I} counts it negative, at most {@link #MAX_WIDEN}
 *       times wider), so the lanes on its two sides close up by half each and the screen's lanes
 *       widen to fill the window.</li>
 *   <li><b>Proportional</b> (every layout set from a plain lane array): the
 *       lanes divide the window in proportion to their current width weights: with
 *       {@code W = Σ wᵢ} and {@code s = (right − left) / W}, lane i spans
 *       {@code left + s·Σ_{j<i} wⱼ} to {@code + s·wᵢ}. If {@code W ≤ 0} (or the window is empty
 *       or inverted) every lane has zero width at {@code left}. The window is {@code [left, right]} as
 *       passed.</li>
 *   <li><b>Own widths</b> (the finite lanes of a chart, Kelvin 2026-11-08 02:48 「就是把每个轨道当作单独的object处理」):
 *       lane i is {@code wᵢ / 240 · laneWidth · g · xScale} wide ({@link Chart#laneWidth()}), whatever the others do;
 *       the lanes sit side by side, the block centred on the axis, so adding, inserting or deleting a lane or widening
 *       one moves the others aside instead of resizing them. The window is the frame {@code axis ± 960·g·xScale} (the
 *       whole screen by default).</li>
 * </ul>
 *
 * <p>Usage: {@link #setLanes} once per chart, then {@link #update} once per frame; the
 * accessors read the last update. {@code update} is O(lanes) and never allocates, and every
 * accessor is O(1) except {@link #indexOf} and {@link #indexAtX} (O(log lanes)).
 *
 * <p>A lane narrower than {@link #MIN_ACTIVE_WIDTH} ref units is neither drawn nor touchable
 * ({@link #active(int)} is false and {@link #indexAtX} skips it).
 *
 * <p>A lane's pose (Kelvin 2026-11-08 23:10 – 11-09 00:13, {@link Lane#space()}, {@link Channels#LANE}): a lane that
 * writes its origin's x leaves the row (finite lanes: the others close up) and is centred there ({@link #freeX}; in
 * its span, so everywhere a lane's x is read; {@link #indexAtX} then looks at every lane, O(lanes)); the rest is read
 * out: how far its ground and sky line lie from the chart's ({@link #lift}: its origin's y and its distance, each
 * following the chart's lines while not written), its column's depth ({@link #moveZ}), its reach ({@link #reach}) and
 * its fall ({@link #turned}, {@link #pose}), which the view turns the lane by ({@link #turn}).
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
    /**
     * Gap between the columns of two finite lanes, in reference units (Kelvin 2026-11-07 06:36 「轨道数量默认改回6轨，
     * 中间间隔一点」: 6 lanes of 320 are a 280 column and a 40 gap). Drawing only: a lane is still judged over its whole
     * width, so a press in a gap goes to the nearer lane.
     */
    public static final float GAP = 40f;

    private Lane[] lanes = new Lane[0];
    private int[] ids = new int[0];
    private float[] weights = new float[0];
    private float[] lefts = new float[0];
    private float[] widths = new float[0];
    private float[] opacities = new float[0];
    /** Each lane's red as a mine lane this frame ({@link #mine}). */
    private float[] mines = new float[0];
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
    /** The axis before inserted lanes move it, and world units per reference unit: where laneless points go. */
    private float freeAxis = TILE_CENTRE;
    private float freeUnit = 1f;
    /** World units per reference unit across, without the lines' lane scale: where space keys go. */
    private float unitX = 1f;
    /** The player's key size ({@link #setKeyScale}): 1 draws everything as charted. */
    private float keyScale = 1f;
    /**
     * A chart's finite lanes, each its own width (Kelvin 2026-11-08, {@link Chart#laneWidth()}): reference units per
     * width unit, a default lane {@link Lane#DEFAULT_WIDTH} being {@code laneWidth}; 0 for tiled and plain lanes.
     */
    private float unit;
    private float beat;
    private int patternLanes = 1;
    private float patternWidth;
    private float patternOrigin;
    /** Tiled mode: the factor inserted lanes shrink (deleted lanes widen) the others by this frame (1 without any). */
    private float squeeze = 1f;
    /**
     * Each lane's pose this frame ({@link #POSE} floats from {@code i·POSE}, Kelvin 2026-11-08 23:42): how far its
     * ground and sky line lie above the chart's, how much deeper its column's front lies than an unmoved column's
     * (its origin's depth from the columns' middle, inward, and half of how much shorter than 240 its base is), its
     * reach factor past its ground line, its unit fall (chart axes: z inward), its origin's x, its base's length along
     * its depth, its reach factor past its sky line, the unit normal it faces (0 0 0: none, {@link #turn}) and the
     * factor it is drawn at about its box's middle (its groups' size, chart version 2.1; 1 in none); reference units.
     */
    private float[] poses = new float[0];
    /** Floats a pose takes ({@link #poses}), and where each one is. */
    public static final int POSE = 14, P_LIFT_G = 0, P_LIFT_S = 1, P_Z = 2, P_REACH = 3, P_FX = 4, P_FY = 5, P_FZ = 6,
        P_X = 7, P_LEN = 8, P_REACH_S = 9, P_NX = 10, P_NY = 11, P_NZ = 12, P_K = 13;
    /** A column's depth, its base's length along it by default ({@link Channels#LENGTH}), reference units. */
    public static final float DEFAULT_LENGTH = 2f * Sea.MID_Z;
    /** True for a lane that writes its origin's x ({@link Channels#ORIGIN_X}): it is placed there, not in the row. */
    private boolean[] free = new boolean[0];
    /** True for a lane turned from falling straight down this frame ({@link #turned}). */
    private boolean[] turns = new boolean[0];
    /** True when some lane has a pose of its own ({@link Lane#space()}). */
    private boolean moving;
    /** True when some lane is placed apart from the row: lanes may then overlap or swap places. */
    private boolean shifted;
    /** True when some lane is turned or sized ({@link #scale}) this frame. */
    private boolean turning;
    /** True for the lanes of a chart format v7 chart ({@link Lane#isV7()}): no gap between a column and its slot. */
    private boolean v7;
    /** True when some lane is turned or sized or its lines lie apart from the chart's ({@link #posed()}). */
    private boolean posed;
    /** A lane's axes, worked out turning it with its group ({@link #pose7}). */
    private final float[] axesTmp = new float[9];

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
     * Sets the lanes of {@code chart} and its lane mode: tiled for unlimited lanes, else each its own width
     * ({@link Chart#laneWidth()}); both with the chart's global lane scale and camera frame.
     */
    public void setLanes(Chart chart) {
        setLanes(chart.lanes());
        tiled = chart.unlimitedLanes();
        lines = chart.lineEvents();
        // each lane its own width (Kelvin 2026-11-08 02:48)
        float own = chart.laneWidth();
        unit = tiled ? 0f : own / Lane.DEFAULT_WIDTH;
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
            mines = new float[n];
            poses = new float[n * POSE];
            free = new boolean[n];
            turns = new boolean[n];
        }
        for (int i = 0; i < this.lanes.length; i++) {
            this.lanes[i] = i < n ? lanes[i] : null;
        }
        count = n;
        tiled = false;
        unit = 0f;
        lines = null;
        patternLanes = 0;
        moving = shifted = turning = posed = v7 = false;
        for (int i = 0; i < n; i++) {
            ids[i] = lanes[i].id();
            weights[i] = 0f;
            lefts[i] = 0f;
            widths[i] = 0f;
            opacities[i] = 0f;
            mines[i] = 0f;
            plain(poses, i * POSE);
            turns[i] = false;
            Channels c = lanes[i].space();
            moving |= c != null;
            // a v7 lane is always where its origin is (Kelvin 2026-11-09 18:44)
            free[i] = c != null && (lanes[i].isV7() || c.written(Channels.ORIGIN_X));
            shifted |= free[i];
            v7 |= lanes[i].isV7();
            // a lane that draws nothing of its own (a carrier, ChartV7Carry) adds no wave to the TIDE pattern
            if (!lanes[i].drawsNothing()) patternLanes++;
        }
        patternLanes = Math.max(1, patternLanes);
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
        arrange(beat, left, right, xScale);
        turning = posed = false;
        if (moving) move(beat);
    }

    /**
     * The lanes' poses this frame (Kelvin 2026-11-08 23:42, {@link Lane#space()}): a lane placed by its origin's x
     * centred there (at the lane scale), the rest into {@link #poses}.
     */
    private void move(float beat) {
        float upper = lines != null ? lines.upperAt(beat) : JudgementLine.DEFAULT_UPPER;
        float lower = lines != null ? lines.lowerAt(beat) : JudgementLine.DEFAULT_LOWER;
        for (int i = 0; i < count; i++) {
            Channels c = lanes[i].space();
            if (c == null) continue;
            int o = i * POSE;
            if (c.keys() == Channels.LANE7) pose7(c, lanes[i].group7(), beat, upper, lower, poses, o, axesTmp);
            else pose(c, beat, upper, lower, poses, o);
            if (free[i]) lefts[i] = freeX(poses[o + P_X]) - widths[i] * 0.5f;
            turns[i] = turned(poses, o);
            boolean sized = poses[o + P_K] != 1f;
            turning |= turns[i] || sized;
            posed |= turns[i] || sized || poses[o + P_LIFT_G] != 0f || poses[o + P_LIFT_S] != 0f;
        }
    }

    /** Sets the pose at {@code o} of {@code p} to a lane's without channels: as the chart's lines, falling down. */
    private static void plain(float[] p, int o) {
        p[o + P_LIFT_G] = p[o + P_LIFT_S] = p[o + P_Z] = p[o + P_FX] = p[o + P_FZ] = p[o + P_X] = 0f;
        p[o + P_NX] = p[o + P_NY] = p[o + P_NZ] = 0f;
        p[o + P_REACH] = p[o + P_REACH_S] = p[o + P_K] = 1f;
        p[o + P_FY] = -1f;
        p[o + P_LEN] = DEFAULT_LENGTH;
    }

    /**
     * The pose of a lane with channels c at {@code beat} into {@code p} from {@code o}, the chart's lines at
     * {@code upper} and {@code lower} then: its lines (its origin's y, from the frame's middle, and its distance, each
     * the lines' while not written: their middle, their distance; exactly theirs while neither is), its origin's depth,
     * its reach, its fall (normalised; none: straight down) and its origin's x (0 while not written).
     */
    static void pose(Channels c, float beat, float upper, float lower, float[] p, int o) {
        float y = c.at(Channels.ORIGIN_Y, beat);
        float d = c.at(Channels.DISTANCE, beat);
        if (Float.isNaN(y) && Float.isNaN(d)) {
            p[o + P_LIFT_G] = p[o + P_LIFT_S] = 0f;
        } else {
            float mid = Float.isNaN(y) ? 0.5f * (upper + lower) : Sea.MID_Y + y;
            float half = 0.5f * (Float.isNaN(d) ? upper - lower : d);
            p[o + P_LIFT_G] = finite(mid - half - lower);
            p[o + P_LIFT_S] = finite(mid + half - upper);
        }
        float l = Math.abs(c.at(Channels.LENGTH, beat));
        p[o + P_LEN] = l < Float.POSITIVE_INFINITY ? l : DEFAULT_LENGTH;
        // the origin is the box's middle (Kelvin 2026-11-09 05:00): a shorter base brings its front in
        p[o + P_Z] = finite(c.at(Channels.ORIGIN_Z, beat)) + 0.5f * (DEFAULT_LENGTH - p[o + P_LEN]);
        float k = c.at(Channels.LANE_REACH, beat);
        p[o + P_REACH] = p[o + P_REACH_S] = k > 0f && k < Float.POSITIVE_INFINITY ? k : 0f;
        float fx = c.at(Channels.FALL_X, beat), fy = c.at(Channels.FALL_Y, beat), fz = c.at(Channels.FALL_Z, beat);
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 1e-6f && len < Float.POSITIVE_INFINITY) {
            p[o + P_FX] = fx / len;
            p[o + P_FY] = fy / len;
            p[o + P_FZ] = fz / len;
        } else {
            p[o + P_FX] = p[o + P_FZ] = 0f;
            p[o + P_FY] = -1f;
        }
        p[o + P_X] = finite(c.at(Channels.ORIGIN_X, beat));
        p[o + P_NX] = p[o + P_NY] = p[o + P_NZ] = 0f;
        p[o + P_K] = 1f;
    }

    /**
     * The pose of a lane of chart format v7 (Kelvin 2026-11-09 18:44, {@link Channels#LANE7}) at {@code beat} into
     * {@code p} from {@code o}, the chart's lines at {@code upper} and {@code lower} then: its sky face lies {@code S}
     * and its base {@code F} from its origin v along its up axis (against its fall u), so its box's middle, which it
     * turns about, lies {@code (S + F) / 2} along it; its base is {@code w} across and {@code h} deep; its column goes
     * on {@code f} past its base and {@code s} past its sky face (factors on the chart's 820). In a group (chart
     * version 2.1, {@code group} its pose; null: none) all that is in the group's axes: its box's middle goes where
     * the group takes it, it turns with the group and is drawn at the group's size about its box's middle
     * ({@link #P_K}).
     *
     * @param a nine floats to work in
     */
    static void pose7(Channels c, Group7.Pose group, float beat, float upper, float lower, float[] p, int o,
                      float[] a) {
        float fx = c.at(Channels.U_X, beat), fy = c.at(Channels.U_Y, beat), fz = c.at(Channels.U_Z, beat);
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > 1e-6f && len < Float.POSITIVE_INFINITY) {
            fx /= len;
            fy /= len;
            fz /= len;
        } else { // none: straight down
            fx = fz = 0f;
            fy = -1f;
        }
        p[o + P_FX] = fx;
        p[o + P_FY] = fy;
        p[o + P_FZ] = fz;
        float nx = c.at(Channels.N_X, beat), ny = c.at(Channels.N_Y, beat), nz = c.at(Channels.N_Z, beat);
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        boolean facing = nl > 1e-6f && nl < Float.POSITIVE_INFINITY; // not written: NaN, none
        p[o + P_NX] = facing ? nx / nl : 0f;
        p[o + P_NY] = facing ? ny / nl : 0f;
        p[o + P_NZ] = facing ? nz / nl : 0f;
        float sky = finite(c.at(Channels.SKY, beat)), ground = finite(c.at(Channels.GROUND, beat));
        float mid = 0.5f * (sky + ground), half = 0.5f * (sky - ground);
        // the box's middle: the origin plus mid along the up axis (−fall)
        float y = Sea.MID_Y + finite(c.at(Channels.V_Y, beat)) - fy * mid;
        float x = finite(c.at(Channels.V_X, beat) - fx * mid);
        float z = finite(c.at(Channels.V_Z, beat) - fz * mid);
        p[o + P_K] = 1f;
        if (group != null) { // where its group takes it
            Group7.Pose t = group.at(beat);
            t.point(x, y - Sea.MID_Y, z, a);
            x = finite(a[0]);
            y = Sea.MID_Y + finite(a[1]);
            z = finite(a[2]);
            if (t.turned) turnWith(t.m, p, o, a);
            p[o + P_K] = t.k;
        }
        p[o + P_LIFT_G] = finite(y - half - lower);
        p[o + P_LIFT_S] = finite(y + half - upper);
        p[o + P_X] = x;
        float l = Math.abs(c.at(Channels.A_H, beat));
        p[o + P_LEN] = l < Float.POSITIVE_INFINITY ? l : DEFAULT_LENGTH;
        p[o + P_Z] = z + 0.5f * (DEFAULT_LENGTH - p[o + P_LEN]);
        float g = c.at(Channels.GROUND_REACH, beat) / Chart.DEFAULT_EXTEND;
        float k = c.at(Channels.SKY_REACH, beat) / Chart.DEFAULT_EXTEND;
        p[o + P_REACH] = g > 0f && g < Float.POSITIVE_INFINITY ? g : 0f;
        p[o + P_REACH_S] = k > 0f && k < Float.POSITIVE_INFINITY ? k : 0f;
    }

    /**
     * Turns the fall and the facing of the pose at {@code o} of {@code p} by {@code m} (rows; a group's turn): its up
     * and depth axes go through m, and its fall is against the one, the normal it faces against the other, so it
     * faces where the group turns it ({@code a}: nine floats to work in).
     */
    private static void turnWith(float[] m, float[] p, int o, float[] a) {
        turn(p[o + P_FX], p[o + P_FY], p[o + P_FZ], p[o + P_NX], p[o + P_NY], p[o + P_NZ], a);
        for (int r = 0; r < 3; r++) {
            p[o + P_FX + r] = -(m[3 * r] * a[3] + m[3 * r + 1] * a[4] + m[3 * r + 2] * a[5]);
            p[o + P_NX + r] = -(m[3 * r] * a[6] + m[3 * r + 1] * a[7] + m[3 * r + 2] * a[8]);
        }
    }

    /** @return true when the pose at {@code o} of {@code p} is turned from falling straight down */
    private static boolean turned(float[] p, int o) {
        if (Math.abs(p[o + P_FX]) > 1e-6f || Math.abs(p[o + P_FZ]) > 1e-6f || !(p[o + P_FY] < 0f)) return true;
        // falling straight down: turned when it faces, level, other than the player (−z)
        float nx = p[o + P_NX], nz = p[o + P_NZ];
        float h = (float) Math.sqrt(nx * nx + nz * nz);
        return h > 1e-6f && (Math.abs(nx) > 1e-6f * h || !(nz < 0f));
    }

    /**
     * The turn of a lane or a sea that falls along (fx, fy, fz) (Kelvin 2026-11-09 00:10 「“法向量“是不是改为”下落向量“
     * 好一点，也就是地键下落的方向？」; chart axes: x right, y up, z inward; any length, none or NaN: straight down): the
     * shortest turn that takes straight down (0, −1, 0) to it, straight up half a turn about the line of sight (upside
     * down, its front still to the player). Into {@code out}: the turned across axis (+x), up axis (+y, from the ground
     * line to the sky line: against the fall) and depth axis (+z), three floats each.
     *
     * @return true when it is turned (not straight down)
     */
    public static boolean turn(float fx, float fy, float fz, float[] out) {
        double len = Math.sqrt((double) fx * fx + (double) fy * fy + (double) fz * fz);
        double bx = 0, by = -1, bz = 0;
        if (len > 1e-9 && len < Double.POSITIVE_INFINITY) {
            bx = fx / len;
            by = fy / len;
            bz = fz / len;
        }
        double s = bx * bx + bz * bz;
        if (s < 1e-12) { // straight down: as it is; straight up: half a turn about z
            float k = by < 0 ? 1f : -1f;
            out[0] = k; out[1] = 0f; out[2] = 0f;
            out[3] = 0f; out[4] = k; out[5] = 0f;
            out[6] = 0f; out[7] = 0f; out[8] = 1f;
            return k < 0f;
        }
        // R = I + [k]x + [k]x^2 / (1 + c), k = down x b = (-bz, 0, bx), c = -by; 1/(1 + c) without cancellation
        double q = by > 0 ? (1 + by) / s : 1 / (1 - by);
        out[0] = (float) (1 - bx * bx * q); // across: R e_x
        out[1] = (float) bx;
        out[2] = (float) (-bx * bz * q);
        out[3] = (float) -bx;               // up: R e_y = -b
        out[4] = (float) -by;
        out[5] = (float) -bz;
        out[6] = (float) (-bx * bz * q);    // depth: R e_z
        out[7] = (float) bz;
        out[8] = (float) (1 - bz * bz * q);
        return true;
    }

    /**
     * {@link #turn} for a lane that also faces n (Kelvin 2026-11-10 17:59 「那就加一个法向量朝向」, 18:00 「和下落向量
     * 一样」; {@link Lane7#n}): its up axis is against its fall, its front faces n as near as that lets it (its depth
     * axis is −n without its part along the up axis) and its across axis is up × depth. n of any length; none (zero or
     * NaN) or along the fall: {@link #turn}.
     *
     * @return true when it is turned (its axes are not the chart's)
     */
    public static boolean turn(float fx, float fy, float fz, float nx, float ny, float nz, float[] out) {
        double len = Math.sqrt((double) fx * fx + (double) fy * fy + (double) fz * fz);
        double ux = 0, uy = 1, uz = 0;
        if (len > 1e-9 && len < Double.POSITIVE_INFINITY) {
            ux = -fx / len;
            uy = -fy / len;
            uz = -fz / len;
        }
        double nl = Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (!(nl > 1e-9 && nl < Double.POSITIVE_INFINITY)) return turn(fx, fy, fz, out);
        double d = (nx * ux + ny * uy + nz * uz) / nl;
        double dx = -(nx / nl - d * ux), dy = -(ny / nl - d * uy), dz = -(nz / nl - d * uz);
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(dl > 1e-6)) return turn(fx, fy, fz, out); // along the fall
        dx /= dl;
        dy /= dl;
        dz /= dl;
        out[0] = (float) (uy * dz - uz * dy); // across: up x depth
        out[1] = (float) (uz * dx - ux * dz);
        out[2] = (float) (ux * dy - uy * dx);
        out[3] = (float) ux;
        out[4] = (float) uy;
        out[5] = (float) uz;
        out[6] = (float) dx;
        out[7] = (float) dy;
        out[8] = (float) dz;
        return Math.abs(out[0] - 1f) > 1e-6f || Math.abs(out[4] - 1f) > 1e-6f || Math.abs(out[8] - 1f) > 1e-6f;
    }

    private static float finite(float v) {
        return v > Float.NEGATIVE_INFINITY && v < Float.POSITIVE_INFINITY ? v : 0f;
    }

    /** {@link #update(float, float, float, float)} without the lanes' own moves. */
    private void arrange(float beat, float left, float right, float xScale) {
        this.beat = beat;
        float kx = xScale > 0f && xScale < Float.POSITIVE_INFINITY ? xScale : 1f;
        float g = lines != null ? lines.laneScaleAt(beat) : 1f;
        axis = TILE_CENTRE + (lines != null ? lines.cameraFrameAt(beat) : 0f) * kx;
        freeAxis = axis;
        freeUnit = g * kx;
        unitX = kx;
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
            mines[i] = lane.mineAt(beat);
        }
        totalWeight = sum;
        float span = right - left;
        if (tiled) {
            // TIDE pattern (Kelvin 2026-10-13): a default lane times the scale, capped at 1
            tile(left, right, kx * g, beat);
            // never sparser than the default, also when deleted lanes widen the others (Kelvin 2026-10-17)
            float f = Math.min(g, 1f) * squeeze;
            if (f > 1f) f = 1f;
            float p = Lane.DEFAULT_WIDTH * kx * f;
            float least = span / patternLanes;
            patternWidth = p > least ? p : least;
            return;
        }
        patternWidth = span / patternLanes;
        patternOrigin = left;
        if (unit > 0f && sum >= 0f && sum < Float.POSITIVE_INFINITY) {
            // each lane its own width (Kelvin 2026-11-08 02:39): side by side, the block centred on the axis; a lane
            // placed by its origin's x is not in the row (23:42), the others close up
            float k = unit * g * kx;
            float row = sum;
            for (int i = 0; shifted && i < count; i++) if (free[i]) row -= weights[i];
            float x = axis - k * row * 0.5f;
            float cum = 0f;
            for (int i = 0; i < count; i++) {
                lefts[i] = x + k * cum;
                widths[i] = k * weights[i];
                if (!free[i]) cum += weights[i];
            }
            fieldLeft = x;
            fieldRight = x + k * row;
            return;
        }
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
     * Kelvin 2026-10-13 camera frame); one wholly outside collapses to zero width at its end
     * nearer the window. The field is the span of the lanes that reach into the window. Inserted
     * lanes (Kelvin 2026-10-15): lane 0 is centred on the axis, another inserted lane moves the
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
        // Inserted lanes take their room from the screen (Kelvin 2026-10-15), deleted lanes give it back
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
     *         quads are counted with: the lanes that draw something of their own (at least 1, not those that
     *         {@link Lane#drawsNothing}) when proportional; tiled, the
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
     * @return the world x of a point {@code refX} reference units right of the screen's centre line, scaled and moved
     *         as the lanes are (global lane scale, camera frame, stretch) but by no lane: inserted and deleted lanes
     *         leave it in place. Where a key with its own x is ({@link Note#xAt}; a sea's, a DRAG written with x).
     */
    public float freeX(float refX) { return freeAxis + refX * freeUnit; }

    /** @return world units per reference unit across at the last {@link #update}, without the lane scale */
    public float unitX() { return unitX; }

    /** @return the world width of a laneless key: a default lane ({@link Lane#DEFAULT_WIDTH}) at the same scale. */
    public float freeWidth() { return Lane.DEFAULT_WIDTH * freeUnit; }

    /**
     * Sets the player's key size (the setting, 0.8–1.3; Kelvin 2026-11-09 14:25 「“按键缩放”相当于把轨道进行缩放」「和无轨按键
     * 进行缩放」): a key is drawn its base that many times as large, a finite lane's column ({@link #columnLeft},
     * {@link #columnWidth}) as wide about its centre and its base as long back from its front ({@link #length}); where
     * the lanes are, what they judge by x and the turns about their origins stay as charted (in 3D a press meets the
     * columns as drawn, View3D.toField). Kept across {@link #setLanes}; 1 by default (the chart editor); not positive
     * or not finite: 1.
     */
    public void setKeyScale(float s) { keyScale = s > 0f && s < Float.POSITIVE_INFINITY ? s : 1f; }

    /** @return the player's key size ({@link #setKeyScale}), 1 by default */
    public float keyScale() { return keyScale; }

    /** @return the factor the columns are drawn at: the key size on a chart's finite lanes, else 1 */
    private float columnScale() { return lines != null && !tiled ? keyScale : 1f; }

    /** @return the chart beat of the last {@link #update}. */
    public float beat() { return beat; }

    /**
     * @return the x of lane {@code v} when it may lie between two lanes (Kelvin 2026-11-07 04:02:
     *         「定轨状态支持小数轨道」). A lane's id is its centre. Between the neighbouring lanes L &lt; v &lt; R (by id),
     *         with {@code f = (v − L) / (R − L)}, the first half of the way runs from lane L's centre to the border of
     *         L and R and the second half on to lane R's centre: 1.5 is the border of lanes 1 and 2, 1.25 a quarter of
     *         lane 1 right of its centre, and 0 without a lane 0 the border of lanes −1 and 1. Beyond the outer lanes
     *         it goes on by the outer lane's width per id; with no lanes it is the {@link #axis()}.
     */
    public float betweenX(float v) {
        if (count == 0) return axis;
        int r = firstAtOrAbove(v);
        if (r < count && ids[r] == v) return centre(r);
        if (r == 0) return centre(0) - (ids[0] - v) * widths[0];
        if (r == count) return centre(count - 1) + (v - ids[count - 1]) * widths[count - 1];
        int l = r - 1;
        float f = (v - ids[l]) / (ids[r] - ids[l]);
        float border = right(l);
        if (f <= 0.5f) return centre(l) + (border - centre(l)) * (f * 2f);
        return border + (centre(r) - border) * ((f - 0.5f) * 2f);
    }

    /**
     * @return the width at lane {@code v} ({@link #betweenX}): a lane's own width, between two lanes their widths
     *         mixed by {@code f}, beyond the outer lanes the outer lane's; with no lanes {@link #freeWidth()}
     */
    public float betweenWidth(float v) {
        if (count == 0) return freeWidth();
        int r = firstAtOrAbove(v);
        if (r < count && ids[r] == v) return widths[r];
        if (r == 0) return widths[0];
        if (r == count) return widths[count - 1];
        int l = r - 1;
        float f = (v - ids[l]) / (ids[r] - ids[l]);
        return widths[l] + (widths[r] - widths[l]) * f;
    }

    /**
     * @return where key {@code n} is at {@code beat}, the centre it is judged by: a laneless key's own x
     *         ({@link #freeX}), a key between lanes at {@link #betweenX}, any other at its lane's centre (the window's
     *         centre without one). A lane key's drawing-only offsetX is not in it.
     */
    public float keyCentre(Note n, float beat) {
        if (n.spacePath()) return freeAxis + n.xAt(beat) * unitX; // its own x from the centre line, by no lane
        if (n.frozen != null && n.type().inLane() && !n.laneless()) return freeAxis + n.frozen[F_X] * unitX;
        if (n.laneless()) return freeX(n.xAt(beat));
        if (n.between()) return betweenX(n.lanePos());
        int i = n.laneIndex();
        return i >= 0 && i < count ? centre(i) : (windowLeft + windowRight) * 0.5f;
    }

    /**
     * @return the width key {@code n} is judged by: {@link #freeWidth()} for a laneless key, {@link #betweenWidth} for
     *         a key between lanes, else its lane's width (0 without one)
     */
    public float keyWidth(Note n) {
        if (n.frozen != null) return n.frozen[F_W] * unitX; // a space key: its lane's at its beat
        if (n.laneless()) return freeWidth();
        if (n.between()) return betweenWidth(n.lanePos());
        int i = n.laneIndex();
        return i >= 0 && i < count ? widths[i] : 0f;
    }

    /** @return the first lane index whose id is at least {@code v} ({@link #count()} when none). */
    private int firstAtOrAbove(float v) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ids[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

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
    /** @return the world gap between two columns: {@link #GAP} at the lane scale for finite lanes, else 0. */
    public float gap() { return lines != null && !tiled && !v7 ? GAP * freeUnit : 0f; }

    /**
     * @return true when each lane is drawn as a column of its own (lines, ticks and TIDEs over each one): lanes with a
     *         {@link #gap()}, and a v7 chart's, each its own object with no gap to its slot
     */
    public boolean separate() { return gap() > 0f || v7; }
    /**
     * @return left x of lane {@code i}'s column: half a {@link #gap()} in from its left (at most to its centre); at a
     *         key size ({@link #setKeyScale}) its scaled width about the lane's centre
     */
    public float columnLeft(int i) {
        if (columnScale() == 1f) return lefts[i] + Math.min(gap(), widths[i]) * 0.5f;
        return centre(i) - columnWidth(i) * 0.5f;
    }

    /** @return width of lane {@code i}'s column: the lane less a {@link #gap()}, at least 0, times the key size */
    public float columnWidth(int i) {
        float w = Math.max(0f, widths[i] - gap());
        float k = columnScale();
        return k == 1f ? w : w * k;
    }
    /**
     * @return how red lane {@code i} is this frame as a mine lane (0..1; Kelvin 2026-11-10 23:01 「这些轨道会变红」,
     *         {@link Lane#mineAt}): its column and its pieces of the judgement lines turn that much to the mine red
     */
    public float mine(int i) { return mines[i]; }
    /** @return opacity of lane {@code i} (0..1), for its furniture only. */
    public float opacity(int i) { return opacities[i]; }

    /**
     * @return lane {@code i}'s opacity for its pieces of the judgement lines and their glows: a v7 lane's {@code o}
     *         fades everything it draws (Kelvin 2026-11-09 21:37 「加回透明度」); 1 for other lanes, whose lines are the
     *         chart's
     */
    public float lineOpacity(int i) { return v7 ? opacities[i] : 1f; }
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
        if (shifted) return movedIndexAtX(x);
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

    /**
     * {@link #indexAtX} while lanes are moved across (Kelvin 2026-11-07 22:54): they may overlap, swap places or leave
     * the field, so every active lane over x counts and the one whose centre is nearest wins.
     */
    private int movedIndexAtX(float x) {
        int best = -1;
        float far = Float.POSITIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            if (!(widths[i] >= MIN_ACTIVE_WIDTH) || !(x >= lefts[i]) || !(x < lefts[i] + widths[i])) continue;
            float d = Math.abs(x - centre(i));
            if (d < far) {
                far = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * @return how far lane {@code i}'s {@code line} lies above the chart's this frame (Kelvin 2026-11-08 23:10: its
     *         origin's y and its distance), reference units; 0 for a lane without them
     */
    public float lift(int i, Line line) {
        return poses[i * POSE + (line == Line.UPPER ? P_LIFT_S : P_LIFT_G)];
    }

    /**
     * @return how much deeper lane {@code i}'s column's front lies than an unmoved column's this frame, reference
     *         units: its origin's depth (from the columns' middle, inward) and half of how much shorter than 240 its
     *         base is ({@link #length}); its column reaches {@link #length} deeper
     */
    public float moveZ(int i) { return poses[i * POSE + P_Z]; }

    /**
     * @return lane {@code i}'s base's length along its depth this frame as drawn, reference units (240 by default):
     *         the chart's ({@link #chartLength}) times the key size on finite lanes, back from its front
     */
    public float length(int i) {
        float k = columnScale();
        return k == 1f ? poses[i * POSE + P_LEN] : poses[i * POSE + P_LEN] * k;
    }

    /** @return lane {@code i}'s base's length as charted this frame: its origin lies half of it behind its front */
    public float chartLength(int i) { return poses[i * POSE + P_LEN]; }

    /** @return the factor on lane {@code i}'s column's reach past its ground line this frame (≥ 0; 1 by default) */
    public float reach(int i) { return poses[i * POSE + P_REACH]; }

    /** @return the factor on lane {@code i}'s column's reach past its sky line this frame (≥ 0; 1 by default) */
    public float reachSky(int i) { return poses[i * POSE + P_REACH_S]; }

    /** @return float {@code k} ({@link #P_FX} …) of lane {@code i}'s pose this frame */
    public float pose(int i, int k) { return poses[i * POSE + k]; }

    /** @return true when lane {@code i} is turned from falling straight down this frame ({@link #turn}) */
    public boolean turned(int i) { return turns[i]; }

    /**
     * @return the factor lane {@code i} is drawn at about its box's middle this frame (its groups' size, chart version
     *         2.1): its column, its keys and how far they fall; 1 for a lane in no group
     */
    public float scale(int i) { return poses[i * POSE + P_K]; }

    /** Lane {@code i}'s axes this frame into {@code out} ({@link #turn}, facing its n when it has one). */
    public void axes(int i, float[] out) {
        int o = i * POSE;
        turn(poses[o + P_FX], poses[o + P_FY], poses[o + P_FZ], poses[o + P_NX], poses[o + P_NY], poses[o + P_NZ], out);
    }

    /** @return true when some lane is turned or sized ({@link #scale}) this frame */
    public boolean turning() { return turning; }

    /** @return true when some lane is turned or sized or its lines lie apart from the chart's ({@link #lift}) */
    public boolean posed() { return posed; }

    /** @return true when some lane has a pose of its own ({@link Lane#space()}): its column may move this song */
    public boolean moving() { return moving; }

    /**
     * @return the lane whose pose key n takes ({@link #lift}, {@link #turned}): its lane, between two lanes the nearer
     *         (the left one at the middle); −1 for a key without one (its own x, or off the lanes)
     */
    public int keyLane(Note n) {
        if (count == 0 || n.laneless() || !n.type().inLane()) return -1;
        if (!n.between()) {
            int i = n.laneIndex();
            return i >= 0 && i < count ? i : -1;
        }
        float v = n.lanePos();
        int r = firstAtOrAbove(v);
        if (r < count && ids[r] == v) return r;
        if (r == 0) return 0;
        if (r == count) return count - 1;
        return v - ids[r - 1] <= ids[r] - v ? r - 1 : r;
    }

    /** @return how far key n's line lies above the chart's ({@link #lift} of its lane, {@link #keyLane}; else 0) */
    public float keyLift(Note n) {
        return keyLift(n, n.line());
    }

    /** @return how far {@code line} of key n's lane lies above the chart's (as {@link #keyLift}) */
    public float keyLift(Note n, Line line) {
        if (n.spaceTrack() && lines != null) { // its lines stay where they were at its beat, or at the frame's middle
            float now = line == Line.UPPER ? lines.upperAt(beat) : lines.lowerAt(beat);
            if (n.spacePath()) return Params.REF_H * 0.5f - now;
            float[] f = n.frozen;
            if (f != null) return f[line == Line.UPPER ? F_LINE_S : F_LINE_G] - now;
        }
        int i = moving ? keyLane(n) : -1;
        return i >= 0 ? lift(i, line) : 0f;
    }

    /** @return the depth of key n's lane's column's front ({@link #moveZ}, as {@link #keyLift}) */
    public float keyMoveZ(Note n) {
        if (n.spacePath()) return 0f; // its own z is where it is
        if (n.frozen != null) return n.frozen[F_Z];
        int i = moving ? keyLane(n) : -1;
        return i >= 0 ? moveZ(i) : 0f;
    }

    /** @return how deep key n is drawn: its lane's base's length ({@link #keyChartLength}) times the key size */
    public float keyLength(Note n) {
        float l = keyChartLength(n);
        return keyScale == 1f ? l : l * keyScale;
    }

    /** @return key n's lane's base's length along its depth as charted ({@link #chartLength}, as {@link #keyMoveZ}) */
    public float keyChartLength(Note n) {
        if (n.spacePath()) return DEFAULT_LENGTH;
        if (n.frozen != null) return n.frozen[F_LEN];
        int i = moving ? keyLane(n) : -1;
        return i >= 0 ? chartLength(i) : DEFAULT_LENGTH;
    }

    // ------------------------------------------------------------------ space keys

    /** What {@link #freezeSpaceKeys} keeps of a space key's lane at its beat, reference units ({@link Note#frozen}). */
    public static final int F_X = 0, F_W = 1, F_LINE_G = 2, F_LINE_S = 3, F_Z = 4, F_FX = 5, F_FY = 6, F_FZ = 7,
        F_LEN = 8, F_NX = 9, F_NY = 10, F_NZ = 11, FROZEN_COUNT = 12;

    /**
     * Keeps each space key's lane as it is at the key's judgement beat (Kelvin 2026-11-08 23:20 「按键的轨迹可以选择跟所在
     * 海面/判定线绑定或者跟着绝对空间坐标绑定」, {@link Note#spaceTrack()}): its width, and for a key on a lane without a
     * path of its own ({@link Note#spacePath()}) where the lane is, its lines (with their shake) and its fall. Such a
     * key then falls on that frozen lane; lane, line and row moves and the lane scale after it leave it be, the view
     * still turns it. Call again after the chart's lanes, lines or space keys change; the engine does on load.
     */
    public static void freezeSpaceKeys(Chart chart) {
        LaneLayout lay = null;
        for (Note n : chart.notes()) {
            if (!n.spaceTrack()) {
                n.frozen = null;
                continue;
            }
            if (lay == null) lay = new LaneLayout(chart);
            float b = n.judgement();
            n.frozen = null;
            lay.update(b, VIEW_LEFT, VIEW_RIGHT);
            float[] f = new float[FROZEN_COUNT];
            f[F_W] = lay.keyWidth(n);
            f[F_X] = lay.keyCentre(n, b) - lay.freeAxis;
            f[F_LINE_G] = lay.lines.lowerAt(b) + lay.keyLift(n, Line.LOWER);
            f[F_LINE_S] = lay.lines.upperAt(b) + lay.keyLift(n, Line.UPPER);
            f[F_Z] = lay.keyMoveZ(n);
            f[F_LEN] = lay.keyChartLength(n);
            int i = lay.keyLane(n);
            boolean turned = i >= 0 && lay.turned(i);
            f[F_FX] = turned ? lay.pose(i, P_FX) : 0f;
            f[F_FY] = turned ? lay.pose(i, P_FY) : -1f;
            f[F_FZ] = turned ? lay.pose(i, P_FZ) : 0f;
            f[F_NX] = turned ? lay.pose(i, P_NX) : 0f;
            f[F_NY] = turned ? lay.pose(i, P_NY) : 0f;
            f[F_NZ] = turned ? lay.pose(i, P_NZ) : 0f;
            n.frozen = f;
        }
    }

    /**
     * @return true when space key n falls on a lane frozen turned ({@link #freezeSpaceKeys}, not one with a path); its
     *         axes into {@code out} ({@link #turn})
     */
    public static boolean frozenTurn(Note n, float[] out) {
        float[] f = n.frozen;
        return f != null && !n.spacePath() && n.type().inLane() && !n.laneless()
            && turn(f[F_FX], f[F_FY], f[F_FZ], f[F_NX], f[F_NY], f[F_NZ], out);
    }
}
