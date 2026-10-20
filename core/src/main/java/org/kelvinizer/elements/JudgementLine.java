package org.kelvinizer.elements;

import java.util.List;

/**
 * 谱面的判定线几何（JSON {@code "LineEvents"}，不可变）：两条判定线的高度、全局轨道缩放、镜头平移（camera frame），以及让它们动起来的事件。
 * 以前的左右边框（窗口）2026-10-13 起不再有：轨道露出整个屏幕。
 *
 * <p>The chart's line geometry (JSON {@code "LineEvents"}), immutable: base y of the two judgement
 * lines, the global lane scale, the camera frame, and the events that animate them (times in
 * beats, values in ref units). Evaluate with {@link #upperAt}, {@link #lowerAt},
 * {@link #laneScaleAt}, {@link #cameraFrameAt} (O(log events), no allocation, thread-safe).
 *
 * <p>Global lane scaling (owner 2026-10-13): {@link #laneScaleAt} multiplies every lane's width
 * about the lanes' axis. Scaling some lanes only is their own width events.
 *
 * <p>Camera frame (owner 2026-10-13 05:29): {@link #cameraFrameAt} moves the lanes' axis (the
 * border between lanes −1 and 1) from x {@link LaneLayout#TILE_CENTRE}; positive moves it and
 * every lane right. It replaces the window ({@code leftBound} / {@code rightBound} and their
 * events): the lanes show across the whole screen. Format v1 windows load as a lane scale and a
 * camera frame ({@link ChartMapper}).
 */
public final class JudgementLine {
    /**
     * Default sky line y: 1080, 120 under the top of the reference frame (Kelvin 2026-10-19 14:55: 「我觉得换成1080/120」;
     * 1066.67 in chart format v3, 960 in the old 1080-high frame).
     */
    public static final float DEFAULT_UPPER = 1080f;
    /** Default ground line y: 120 (133.33 in chart format v3, 120 in the old 1080-high frame). */
    public static final float DEFAULT_LOWER = 120f;
    /** Reference screen left x; also the default left of an old format v2 window ({@link ChartMapper}). */
    public static final float DEFAULT_LEFT = 0f;
    /** Reference screen right x; also the default right of an old format v2 window. */
    public static final float DEFAULT_RIGHT = 1920f;

    private final float upperBase;
    private final float lowerBase;
    private final List<Events> upperEvents;
    private final List<Events> lowerEvents;
    private final float laneScaleBase;
    private final List<Events> laneScaleEvents;
    private final float cameraBase;
    private final List<Events> cameraEvents;

    /**
     * @param upper             base sky line y
     * @param lower             base ground line y
     * @param upperEvents       sky line y events ({@code JudgementUp}; copied and sorted; null = none)
     * @param lowerEvents       ground line y events ({@code JudgementDown})
     * @param laneScale         base global lane scale (1 = lanes at their own widths)
     * @param laneScaleEvents   global lane scale events ({@code LaneScale})
     * @param cameraFrame       base camera frame: the lanes' axis offset from x 960 (positive = right)
     * @param cameraFrameEvents camera frame events ({@code CameraFrame})
     */
    public JudgementLine(float upper, float lower, List<Events> upperEvents, List<Events> lowerEvents,
                         float laneScale, List<Events> laneScaleEvents, float cameraFrame,
                         List<Events> cameraFrameEvents) {
        this.upperBase = upper;
        this.lowerBase = lower;
        this.upperEvents = Events.sortedCopy(upperEvents);
        this.lowerEvents = Events.sortedCopy(lowerEvents);
        this.laneScaleBase = laneScale;
        this.laneScaleEvents = Events.sortedCopy(laneScaleEvents);
        this.cameraBase = cameraFrame;
        this.cameraEvents = Events.sortedCopy(cameraFrameEvents);
    }

    /** @return the defaults: lines at 1080 / 120, lane scale 1, camera frame 0, no events. */
    public static JudgementLine defaults() {
        return new JudgementLine(DEFAULT_UPPER, DEFAULT_LOWER, null, null, 1f, null, 0f, null);
    }

    /** @return sky line y at {@code beat}. */
    public float upperAt(float beat) {
        return Events.calculateValue(upperEvents, beat, upperBase);
    }

    /** @return ground line y at {@code beat}. */
    public float lowerAt(float beat) {
        return Events.calculateValue(lowerEvents, beat, lowerBase);
    }

    /** @return y of {@code line} at {@code beat}. */
    public float yAt(Line line, float beat) {
        return line == Line.UPPER ? upperAt(beat) : lowerAt(beat);
    }

    /**
     * @return the factor on every lane width at {@code beat}, clamped to ≥ 0 (negative, NaN or
     *         infinite → 0)
     */
    public float laneScaleAt(float beat) {
        float k = laneScaleEvents.isEmpty() ? laneScaleBase : Events.calculateValue(laneScaleEvents, beat, laneScaleBase);
        return k > 0f && k < Float.POSITIVE_INFINITY ? k : 0f;
    }

    /** @return the smallest global lane scale: the base or an event's end value, clamped to ≥ 0 as {@link #laneScaleAt}. */
    public float laneScaleMin() {
        float k = extreme(laneScaleBase, laneScaleEvents, false);
        return k > 0f && k < Float.POSITIVE_INFINITY ? k : 0f;
    }

    /**
     * @return the camera frame at {@code beat}: x offset of the lanes' axis from x 960 in ref
     *         units, positive = right (NaN or infinite → 0)
     */
    public float cameraFrameAt(float beat) {
        float c = cameraEvents.isEmpty() ? cameraBase : Events.calculateValue(cameraEvents, beat, cameraBase);
        return c > Float.NEGATIVE_INFINITY && c < Float.POSITIVE_INFINITY ? c : 0f;
    }

    /** @return the smallest camera frame: the base or an event's end value (eased values stay between them). */
    public float cameraFrameMin() {
        float c = extreme(cameraBase, cameraEvents, false);
        return c > Float.NEGATIVE_INFINITY && c < Float.POSITIVE_INFINITY ? c : 0f;
    }

    /** @return the largest camera frame: the base or an event's end value. */
    public float cameraFrameMax() {
        float c = extreme(cameraBase, cameraEvents, true);
        return c > Float.NEGATIVE_INFINITY && c < Float.POSITIVE_INFINITY ? c : 0f;
    }

    /**
     * @return the largest ({@code max}) or smallest value of a channel: its base or an event's end
     *         values, each NaN or infinite one counted as 0 (what {@link #laneScaleAt} and
     *         {@link #cameraFrameAt} show for it)
     */
    private static float extreme(float base, List<Events> events, boolean max) {
        float v = finite(base);
        for (int i = 0; i < events.size(); i++) {
            Events e = events.get(i);
            float a = finite(e.initialValue());
            float b = finite(e.finalValue());
            v = max ? Math.max(v, Math.max(a, b)) : Math.min(v, Math.min(a, b));
        }
        return v;
    }

    private static float finite(float v) {
        return v > Float.NEGATIVE_INFINITY && v < Float.POSITIVE_INFINITY ? v : 0f;
    }

    /** @return base sky line y. */
    public float upperBase() { return upperBase; }
    /** @return base ground line y. */
    public float lowerBase() { return lowerBase; }
    /** @return sky line events, sorted by start (unmodifiable). */
    public List<Events> upperEvents() { return upperEvents; }
    /** @return ground line events, sorted by start (unmodifiable). */
    public List<Events> lowerEvents() { return lowerEvents; }
    /** @return base global lane scale. */
    public float laneScaleBase() { return laneScaleBase; }
    /** @return global lane scale events, sorted by start (unmodifiable). */
    public List<Events> laneScaleEvents() { return laneScaleEvents; }
    /** @return base camera frame. */
    public float cameraFrameBase() { return cameraBase; }
    /** @return camera frame events, sorted by start (unmodifiable). */
    public List<Events> cameraFrameEvents() { return cameraEvents; }
}
