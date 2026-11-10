package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 几条按名字的运动通道：每条有基础值和运动事件（事件外回到基础值），没写就是默认值。键、漩涡和水帘的三维运动（纵深、绕横轴和竖轴转、
 * 宽和高的倍数）、漩涡顶点的移动和轨道的原点、下落方向、天地距离都用它。
 *
 * <p>Named motion channels (Kelvin 2026-11-07 22:54 「“指数”改为“缓动”，其他的都可以」, the Charter's motion page §⑤;
 * CHART_FORMAT §3): each a base value (JSON key: its name) and motion events (its name + {@code "Events"}), read with
 * {@link Events#calculateValue}, so the value returns to the base outside them. {@link #SOLID} are a key's, a veil's
 * and a curtain's 3D motion: depth {@code z} (0 the columns' front, positive inward), turns about the horizontal
 * and the vertical axis {@code rotationX}, {@code rotationY} (degrees) and drawn size factors {@code scaleX},
 * {@code scaleY}; {@link #POINT} a veil point's offsets; {@link #LANE} a lane's pose as an oriented box (Kelvin
 * 2026-11-08 23:10 – 11-09 00:13): its origin, its fall, the distance between its two lines, and the factor on its
 * reach past the lines. A scale defaults to 1, a lane's channels to {@link #defaultOf} (some to "not written", NaN),
 * everything else to 0. {@link #of} gives null for plain channels (defaults, no events), so an object without them is
 * exactly as before.
 */
public final class Channels {
    /** A key's, a veil's and a curtain's channels: depth, the two turns, width and height factors. */
    public static final String[] SOLID = {"z", "rotationX", "rotationY", "scaleX", "scaleY"};
    public static final int Z = 0, ROT_X = 1, ROT_Y = 2, SCALE_X = 3, SCALE_Y = 4;
    /** A veil point's offsets ({@code pointEvents}): across, up and in depth ({@link #MOVE_X} …). */
    public static final String[] POINT = {"x", "y", "z"};
    public static final int MOVE_X = 0, MOVE_Y = 1, MOVE_Z = 2;
    /**
     * A lane's channels (Kelvin 2026-11-08 23:42 「加一个法向量参数+轨道原点相对于绝对原点的坐标」, 11-09 00:13 the normal
     * made the fall 「那就改」): its origin, the centre of its box (across from the centre line as a key's x; up from
     * the frame's centre; in depth from the columns' middle, positive inward), its fall (the way its ground keys fall,
     * a direction; (0, −1, 0) straight down), the distance between its ground and sky line, and the factor on its
     * reach past the lines, and its base's length along its depth (Kelvin 2026-11-09 05:00 「然后轨道的事件改为“底面事件”，
     * 长宽都能改」; its width is the lane's own). originX, originY and distance are not written by default (NaN): a
     * lane without originX keeps its place in the row, one without originY or distance follows the chart's lines.
     */
    public static final String[] LANE = {"originX", "originY", "originZ", "fallX", "fallY", "fallZ", "distance",
        "scaleY", "length"};
    public static final int ORIGIN_X = 0, ORIGIN_Y = 1, ORIGIN_Z = 2, FALL_X = 3, FALL_Y = 4, FALL_Z = 5,
        DISTANCE = 6, LANE_REACH = 7, LENGTH = 8;
    /**
     * {@link #LANE}'s defaults: originX, originY and distance not written, the columns' middle, falling down, the
     * columns' depth (240).
     */
    private static final float[] LANE_DEFAULTS = {Float.NaN, Float.NaN, 0f, 0f, -1f, 0f, Float.NaN, 1f, 240f};
    /**
     * A lane of chart format v7 (Kelvin 2026-11-09 18:44, {@link Lane7}): its fall u, its origin v, its base's width
     * and length (a), where its sky face and its base lie from the origin along its up axis (S, F) and how far its
     * column goes on past each (s, f). Read {@link #held}: a value holds between its segments.
     */
    public static final String[] LANE7 = {"ux", "uy", "uz", "vx", "vy", "vz", "w", "h", "S", "s", "F", "f"};
    public static final int U_X = 0, U_Y = 1, U_Z = 2, V_X = 3, V_Y = 4, V_Z = 5, A_W = 6, A_H = 7, SKY = 8,
        SKY_REACH = 9, GROUND = 10, GROUND_REACH = 11;
    /** {@link #LANE7}'s defaults ({@link Lane7}). */
    private static final float[] LANE7_DEFAULTS = {0f, -1f, 0f, 0f, 0f, 0f, 240f, 240f, 480f, 820f, -480f, 820f};
    /** The camera of chart format v7 (its position P, {@link Chart7#camera}), {@link #held}. */
    public static final String[] CAMERA7 = {"x", "y", "z"};

    private final String[] keys;
    private final float[] base;
    private final List<List<Events>> events;
    /** True for a v7 set ({@link #held}): a value holds after its segment, not back to the base. */
    private final boolean held;

    private Channels(String[] keys, float[] base, List<List<Events>> events) {
        this.keys = keys;
        this.base = base;
        this.events = events;
        this.held = keys == LANE7 || keys == CAMERA7;
    }

    /**
     * The channels of a chart format v7 object ({@link #LANE7}, {@link #CAMERA7}; Kelvin 2026-11-09 18:44): each a list
     * of simple functions read with {@link Events#heldValue}, so a value holds between segments and after the last
     * one, and before the first one it is {@code base}. Never null.
     *
     * @param keys   {@link #LANE7} or {@link #CAMERA7}
     * @param base   each channel's value before its first segment, in key order (its value when it has none)
     * @param events each channel's segments, in key order (null or a missing entry: none)
     */
    public static Channels held(String[] keys, float[] base, List<List<Events>> events) {
        float[] b = new float[keys.length];
        List<List<Events>> ev = new ArrayList<>(keys.length);
        for (int c = 0; c < keys.length; c++) {
            float v = base != null && c < base.length ? base[c] : Float.NaN;
            b[c] = Float.isFinite(v) ? v + 0f : defaultOf(keys, c);
            ev.add(Events.sortedCopy(events != null && c < events.size() ? events.get(c) : null));
        }
        return new Channels(keys, b, Collections.unmodifiableList(ev));
    }

    /**
     * @param keys   {@link #SOLID}, {@link #POINT} or {@link #LANE}
     * @param base   the base values in key order (null, a short array or NaN: the default)
     * @param events the events in key order (null or a missing entry: none)
     * @return the channels, or null when every one is plain (its default, no events)
     */
    public static Channels of(String[] keys, float[] base, List<List<Events>> events) {
        float[] b = new float[keys.length];
        List<List<Events>> ev = new ArrayList<>(keys.length);
        boolean plain = true;
        for (int c = 0; c < keys.length; c++) {
            float v = base != null && c < base.length ? base[c] : Float.NaN;
            b[c] = Float.isFinite(v) ? v + 0f : defaultOf(keys, c); // + 0 folds −0 into +0
            List<Events> e = Events.sortedCopy(events != null && c < events.size() ? events.get(c) : null);
            ev.add(e);
            plain &= isDefault(keys, c, b[c]) && e.isEmpty();
        }
        return plain ? null : new Channels(keys, b, Collections.unmodifiableList(ev));
    }

    /** @return the default of channel c of {@code keys}: a lane's own ({@link #LANE}; NaN: not written), 1 or 0 */
    public static float defaultOf(String[] keys, int c) {
        if (keys == LANE) return LANE_DEFAULTS[c];
        if (keys == LANE7) return LANE7_DEFAULTS[c];
        if (keys == CAMERA7) return c == 2 ? -Chart7.CAMERA_DIST : 0f;
        return keys[c].startsWith("scale") ? 1f : 0f;
    }

    /** @return true when {@code v} is channel c's default (a NaN default: v is NaN), so it need not be written */
    public static boolean isDefault(String[] keys, int c, float v) {
        return Float.floatToIntBits(v + 0f) == Float.floatToIntBits(defaultOf(keys, c));
    }

    /** @return true when channel c is written: a base other than its default, or events */
    public boolean written(int c) {
        return !isDefault(keys, c, base[c]) || !events.get(c).isEmpty();
    }

    /** @return channel c at {@code beat} */
    public float at(int c, float beat) {
        if (held) return Events.heldValue(events.get(c), beat, base[c]);
        return Events.calculateValue(events.get(c), beat, base[c]);
    }

    /** @return channel c ({@code channels} null: its default) at {@code beat} */
    public static float at(Channels channels, String[] keys, int c, float beat) {
        return channels != null ? channels.at(c, beat) : defaultOf(keys, c);
    }

    /** @return the keys, in channel order ({@link #SOLID}, {@link #POINT} or {@link #LANE}) */
    public String[] keys() { return keys; }

    /** @return channel c's base value */
    public float base(int c) { return base[c]; }

    /** @return channel c's events (sorted, unmodifiable) */
    public List<Events> events(int c) { return events.get(c); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Channels)) return false;
        Channels c = (Channels) o;
        return Arrays.equals(keys, c.keys) && Arrays.equals(base, c.base) && events.equals(c.events);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(base) + events.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("channels(");
        for (int c = 0; c < keys.length; c++) {
            if (c > 0) s.append(", ");
            s.append(keys[c]).append(' ').append(base[c]);
            if (!events.get(c).isEmpty()) s.append(" +").append(events.get(c).size());
        }
        return s.append(')').toString();
    }
}
