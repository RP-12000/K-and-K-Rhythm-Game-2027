package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.List;

/**
 * 海面：一整片深蓝色的面，和轨道是同一种物件：原点（正中心）、下落方向（默认朝下）、宽和深（都可以按拍变化），和下落方向垂直，穿过
 * 轨道之间的间隔；键和浪花顺着下落方向落在上面，它自己不判定、不回收。
 *
 * <p>A sea (海面; Kelvin 2026-11-07 16:04 「浪花判定面和普通浪花做一些区分，改成深蓝色，然后不用再回收判定」, 「浪花判定面上也可以
 * 落下普通浪花」, 16:05 「浪花判定面的名字叫"海面"」, 15:38 「无视轨道之间的间隙，可以直接穿过去」; the design thread's 3D page,
 * confirmed 16:12, its heave 16:14–16:16). Since 2026-11-08 (23:10「当作一个三维空间内的长方体，类似于海面」; 11-09 00:08
 * 「海面中心也默认为（0，0，0）」, 00:13 the normal made the fall, 「那就改」) it is the same kind of object as a lane: eight
 * channels, each a base value and motion events ({@link Events#calculateValue}): its origin, the centre of its
 * surface (across from the centre line as a key's {@code x}; up from the frame's centre; in depth from the columns'
 * middle, positive inward), its fall (the way keys fall onto it; default straight down) and its width and depth along
 * its surface, centred on the origin. It lies square to its fall, turned from level about its origin as a lane is
 * ({@link LaneLayout#turn}): drawn level first, then turned. Shown from {@link #from()} to {@link #to()}, fading in and
 * out over {@link #FADE_BEATS}. Notes land on it by its index ({@link Note#seaIndex()}); it is drawing only, never
 * judged. The keys before 2026-11-08 ({@link #OLD_KEYS}) are read and converted ({@link #converted}).
 */
public final class Sea {
    /** The channels' JSON keys, in channel order. */
    public static final String[] KEYS = {"originX", "originY", "originZ", "fallX", "fallY", "fallZ", "width",
        "depth"};
    /** The channels' defaults: mid-screen, the columns' depth, level, the whole frame wide, as deep as the columns. */
    private static final float[] DEFAULTS = {0f, 0f, 0f, 0f, -1f, 0f, 1920f, 240f};
    public static final int ORIGIN_X = 0, ORIGIN_Y = 1, ORIGIN_Z = 2, FALL_X = 3, FALL_Y = 4, FALL_Z = 5,
        WIDTH = 6, DEPTH = 7, CHANNELS = 8;
    /**
     * The keys before 2026-11-08, read and converted ({@link #converted}): the left and right end, the height at the
     * middle (reference y from the bottom), the tilt (degrees, the right end up) and the front and back of the depth.
     */
    public static final String[] OLD_KEYS = {"x0", "x1", "y", "tilt", "z0", "z1"};
    private static final float[] OLD_DEFAULTS = {-960f, 960f, 600f, 0f, 0f, 240f};
    private static final int X0 = 0, X1 = 1, Y = 2, TILT = 3, Z0 = 4, Z1 = 5;
    /** The reference frame's middle height: an origin's y 0. */
    public static final float MID_Y = 600f;
    /** The middle of the columns' depth in a key's z (0 the columns' front): an origin's z 0. */
    public static final float MID_Z = 120f;
    /** The least share of its across axis along the screen's x the flat picture's lean uses ({@link #leanDeg}). */
    public static final float MIN_UP = 0.05f;
    /** Beats a sea fades in after {@link #from()} and out before {@link #to()}. */
    public static final float FADE_BEATS = 0.5f;

    private final float[] base = new float[CHANNELS];
    private final List<List<Events>> events = new ArrayList<>(CHANNELS);
    private final float from;
    private final float to;

    /**
     * @param base   the channels' base values in channel order (NaN or a short array: the default)
     * @param events the channels' events in channel order (null or a missing entry: none)
     * @param from   first beat shown (NaN: always)
     * @param to     last beat shown (NaN: always)
     */
    public Sea(float[] base, List<List<Events>> events, float from, float to) {
        for (int c = 0; c < CHANNELS; c++) {
            float b = base != null && c < base.length ? base[c] : Float.NaN;
            this.base[c] = Float.isFinite(b) ? b : DEFAULTS[c];
            this.events.add(Events.sortedCopy(events != null && c < events.size() ? events.get(c) : null));
        }
        this.from = Float.isFinite(from) ? from : Float.NEGATIVE_INFINITY;
        this.to = Float.isFinite(to) ? to : Float.POSITIVE_INFINITY;
    }

    /**
     * A sea as read (2026-11-08): its channels ({@link #KEYS}), and for each one not written, what the keys before
     * ({@link #OLD_KEYS}) give: originX the middle of x0 and x1, width x1 − x0, originY y − 600, originZ the middle of
     * z0 and z1 less 120, depth z1 − z0, the fall (sin tilt, −cos tilt, 0): straight down turned by the tilt. Bases
     * convert exactly; events too when only one of a pair has them or theirs line up ({@link Events#combine}), a
     * tilt's fall at its events' ends.
     *
     * @param old       the old keys' base values in their order (NaN: not written; null: none)
     * @param oldEvents the old keys' events in their order (null or a missing entry: none)
     */
    public static Sea converted(float[] base, List<List<Events>> events, float[] old, List<List<Events>> oldEvents,
                                float from, float to) {
        float[] b = new float[CHANNELS];
        List<List<Events>> e = new ArrayList<>(CHANNELS);
        for (int c = 0; c < CHANNELS; c++) {
            b[c] = base != null && c < base.length ? base[c] : Float.NaN;
            e.add(events != null && c < events.size() ? events.get(c) : null);
        }
        boolean[] w = new boolean[OLD_KEYS.length];
        float[] ob = new float[OLD_KEYS.length];
        List<List<Events>> oe = new ArrayList<>(OLD_KEYS.length);
        for (int c = 0; c < OLD_KEYS.length; c++) {
            float v = old != null && c < old.length ? old[c] : Float.NaN;
            List<Events> ev = oldEvents != null && c < oldEvents.size() ? oldEvents.get(c) : null;
            w[c] = Float.isFinite(v) || ev != null && !ev.isEmpty();
            ob[c] = Float.isFinite(v) ? v : OLD_DEFAULTS[c];
            oe.add(ev);
        }
        if (w[X0] || w[X1]) {
            pair(b, e, ORIGIN_X, WIDTH, ob[X0], oe.get(X0), ob[X1], oe.get(X1));
        }
        if (w[Z0] || w[Z1]) {
            boolean own = written(b, e, ORIGIN_Z);
            pair(b, e, ORIGIN_Z, DEPTH, ob[Z0], oe.get(Z0), ob[Z1], oe.get(Z1));
            if (!own) { // the old depths are a key's z (0 the columns' front)
                b[ORIGIN_Z] -= MID_Z;
                e.set(ORIGIN_Z, Events.mapValues(e.get(ORIGIN_Z), v -> v - MID_Z));
            }
        }
        if (w[Y] && !written(b, e, ORIGIN_Y)) {
            b[ORIGIN_Y] = ob[Y] - MID_Y;
            e.set(ORIGIN_Y, Events.mapValues(oe.get(Y), v -> v - MID_Y));
        }
        if (w[TILT] && !written(b, e, FALL_X) && !written(b, e, FALL_Y) && !written(b, e, FALL_Z)) {
            float t = (float) Math.toRadians(ob[TILT]);
            b[FALL_X] = (float) Math.sin(t);
            b[FALL_Y] = (float) -Math.cos(t);
            e.set(FALL_X, Events.mapValues(oe.get(TILT), v -> (float) Math.sin(Math.toRadians(v))));
            e.set(FALL_Y, Events.mapValues(oe.get(TILT), v -> (float) -Math.cos(Math.toRadians(v))));
        }
        return new Sea(b, e, from, to);
    }

    /** Sets channels {@code mid} and {@code size} from an old pair of ends (a: base, events; b too), if unwritten. */
    private static void pair(float[] b, List<List<Events>> e, int mid, int size, float a0, List<Events> ae, float b0,
                             List<Events> be) {
        if (!written(b, e, mid)) {
            b[mid] = 0.5f * (a0 + b0);
            e.set(mid, Events.combine(ae, a0, be, b0, (x, y) -> 0.5f * (x + y)));
        }
        if (!written(b, e, size)) {
            b[size] = b0 - a0;
            e.set(size, Events.combine(ae, a0, be, b0, (x, y) -> y - x));
        }
    }

    private static boolean written(float[] b, List<List<Events>> e, int c) {
        return Float.isFinite(b[c]) || e.get(c) != null && !e.get(c).isEmpty();
    }

    /** @return channel c ({@link #ORIGIN_X} … {@link #DEPTH}) at {@code beat} */
    public float at(int c, float beat) {
        return Events.calculateValue(events.get(c), beat, base[c]);
    }

    /** @return channel c's base value */
    public float base(int c) { return base[c]; }

    /** @return channel c's default (written only when the base differs) */
    public static float defaultOf(int c) { return DEFAULTS[c]; }

    /** @return channel c's events (sorted, unmodifiable) */
    public List<Events> events(int c) { return events.get(c); }

    /** @return the first beat shown (−∞: always) */
    public float from() { return from; }

    /** @return the last beat shown (+∞: always) */
    public float to() { return to; }

    /** @return how much of the sea shows at {@code beat}: 0 outside its beats, fading in and out at their ends */
    public float shownAt(float beat) {
        if (!(beat >= from && beat <= to)) return 0f;
        return Math.min(1f, Math.min((beat - from) / FADE_BEATS, (to - beat) / FADE_BEATS));
    }

    /** @return its left end at {@code beat} before its turn: reference units from the centre line, as a key's x */
    public float xMin(float beat) { return at(ORIGIN_X, beat) - 0.5f * at(WIDTH, beat); }

    /** @return its right end at {@code beat} before its turn */
    public float xMax(float beat) { return at(ORIGIN_X, beat) + 0.5f * at(WIDTH, beat); }

    /** @return its front at {@code beat} before its turn: the depth nearest the viewer, as a key's z (0 the front) */
    public float zMin(float beat) { return MID_Z + at(ORIGIN_Z, beat) - 0.5f * Math.abs(at(DEPTH, beat)); }

    /** @return its back at {@code beat} before its turn (as {@link #zMin}) */
    public float zMax(float beat) { return MID_Z + at(ORIGIN_Z, beat) + 0.5f * Math.abs(at(DEPTH, beat)); }

    /** @return the height of its origin at {@code beat}: reference y from the bottom of the frame */
    public float yAt(float beat) { return MID_Y + at(ORIGIN_Y, beat); }

    /**
     * Its turn at {@code beat} ({@link LaneLayout#turn} of its fall) into {@code out}: its across, up and depth axes
     * (chart axes, z inward), three floats each.
     *
     * @return true when it is turned from level
     */
    public boolean turn(float beat, float[] out) {
        return LaneLayout.turn(at(FALL_X, beat), at(FALL_Y, beat), at(FALL_Z, beat), out);
    }

    /**
     * @return how many degrees it leans in the flat picture at {@code beat}, its right end up (its across axis as
     *         turned, seen from the front; at most as steep as {@link #MIN_UP} across allows): an old tilt exactly
     */
    public float leanDeg(float beat) {
        float[] a = LEAN;
        if (!turn(beat, a)) return 0f;
        float c = a[0] < 0f ? Math.min(-MIN_UP, a[0]) : Math.max(MIN_UP, a[0]);
        return (float) Math.toDegrees(Math.atan2(a[1], c));
    }

    private static final float[] LEAN = new float[9]; // leanDeg's scratch (drawing: one thread)
}
