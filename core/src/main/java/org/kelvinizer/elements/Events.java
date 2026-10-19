package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 谱面里一段带缓动的关键帧（不可变，时间以拍为单位）：音符、判定线、窗口和轨道的动画都由它组成。
 * 不在任何事件内时取该通道的基础值，而不是上一段的终值。
 *
 * <p>One eased keyframe segment of an animated chart value (immutable). Times are in beats.
 *
 * <p>Inside {@code [startTime, endTime]} the value is
 * {@code initial + (final − initial) · p^exponent} with {@code p = (t − start) / (end − start)}
 * ({@link #valueAt(float)}). Outside every event of a list, {@link #calculateValue} returns the
 * channel's base value, <b>not</b> the last final value: charts hold a value by adding a
 * constant event (e.g. the window squeeze of the shipped charts holds 0..1920 with a 526–562
 * event). Every channel uses this rule: note movement / rotation / opacity / tail height, the
 * judgement-line, window and global lane scale events, and the lane width / opacity channels of
 * chart format v2.
 */
public final class Events {
    /** Event channel. The JSON {@code "type"} string is the constant name (read case-insensitively). */
    public enum TYPE {
        /** Note distance from its judgement line, in ref units. */
        Movement,
        /** Note rotation in degrees. */
        Rotation,
        /** Note opacity ({@code DisappearEvents}) and lane opacity ({@code opacityEvents}). */
        Opacity,
        /** Window left edge x (format v1; read as {@link #LaneScale} and {@link #CameraFrame} since 2026-10-13). */
        LeftBound,
        /** Window right edge x (format v1, as {@link #LeftBound}). */
        RightBound,
        /** Sky (upper) line y. */
        JudgementUp,
        /** Ground (lower) line y. */
        JudgementDown,
        /** Lane width weight (chart format v2). */
        Width,
        /**
         * Visible tail length in ref units of a STREAM, TIDE STREAM or LANE MINE
         * ({@code HeightEvents}, ARCHITECTURE §20 "Tail motion"); drawing only.
         */
        Height,
        /** Factor on every lane width ({@code LineEvents.laneScaleEvents}, global lane scaling). */
        LaneScale,
        /** Offset of the lanes' axis from x 960, positive = right ({@code LineEvents.cameraFrameEvents}). */
        CameraFrame;

        private static final TYPE[] VALUES = values();

        /** @return the type named {@code name} ignoring case and surrounding blanks, or null when unknown. */
        public static TYPE parse(String name) {
            if (name == null) return null;
            String s = name.trim();
            for (TYPE t : VALUES) {
                if (t.name().equalsIgnoreCase(s)) return t;
            }
            return null;
        }
    }

    private final TYPE type;
    private final float startTime;
    private final float endTime;
    private final float initialValue;
    private final float finalValue;
    private final float exponent;

    /**
     * @param type         channel (may be null for an unknown type; the event still evaluates)
     * @param startTime    start beat
     * @param endTime      end beat
     * @param initialValue value at {@code startTime}
     * @param finalValue   value at {@code endTime}
     * @param exponent     easing exponent (1 = linear)
     */
    public Events(TYPE type, float startTime, float endTime, float initialValue, float finalValue, float exponent) {
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
        this.initialValue = initialValue;
        this.finalValue = finalValue;
        this.exponent = exponent;
    }

    /** @return channel, or null when the JSON type was missing or unknown. */
    public TYPE type() { return type; }
    /** @return the channel name, or "" when unknown. */
    public String typeName() { return type != null ? type.name() : ""; }
    /** @return start beat. */
    public float startTime() { return startTime; }
    /** @return end beat. */
    public float endTime() { return endTime; }
    /** @return value at the start. */
    public float initialValue() { return initialValue; }
    /** @return value at the end. */
    public float finalValue() { return finalValue; }
    /** @return easing exponent. */
    public float exponent() { return exponent; }

    /** @return true when {@code start ≤ time ≤ end}. */
    public boolean contains(float time) {
        return startTime <= time && time <= endTime;
    }

    /**
     * @return the eased value at {@code time}: the final value for a zero-length event or after
     *         the end, otherwise {@code initial + (final − initial)·p^exponent}
     */
    public float valueAt(float time) {
        if (endTime == startTime || time > endTime) {
            return finalValue;
        }
        return (float) (initialValue + (finalValue - initialValue)
            * Math.pow((time - startTime) / (endTime - startTime), exponent));
    }

    /**
     * Evaluates a channel at {@code time}: binary search for the last event (by start time) that
     * starts at or before {@code time}; its eased value if it still covers {@code time}, else
     * {@code base}. O(log n), no allocation.
     *
     * @param events events sorted by start time (as every model list is)
     * @param time   beat
     * @param base   value outside every event
     */
    public static float calculateValue(List<Events> events, float time, float base) {
        int low = 0;
        int high = events.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (events.get(mid).startTime <= time) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        if (low > 0) {
            Events e = events.get(low - 1);
            if (e.contains(time)) return e.valueAt(time);
        }
        return base;
    }

    /**
     * @return an unmodifiable copy of {@code events} (null entries dropped) stably sorted by start
     *         time, as {@link #calculateValue} requires; a shared empty list for null or empty input
     */
    static List<Events> sortedCopy(List<Events> events) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        ArrayList<Events> copy = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            Events e = events.get(i);
            if (e != null) copy.add(e);
        }
        if (copy.isEmpty()) return Collections.emptyList();
        copy.sort(BY_START);
        return Collections.unmodifiableList(copy);
    }

    private static final Comparator<Events> BY_START = new Comparator<Events>() {
        @Override
        public int compare(Events a, Events b) {
            return Float.compare(a.startTime, b.startTime);
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Events)) return false;
        Events e = (Events) o;
        return type == e.type
            && Float.compare(startTime, e.startTime) == 0
            && Float.compare(endTime, e.endTime) == 0
            && Float.compare(initialValue, e.initialValue) == 0
            && Float.compare(finalValue, e.finalValue) == 0
            && Float.compare(exponent, e.exponent) == 0;
    }

    @Override
    public int hashCode() {
        int h = type != null ? type.hashCode() : 0;
        h = 31 * h + Float.floatToIntBits(startTime);
        h = 31 * h + Float.floatToIntBits(endTime);
        h = 31 * h + Float.floatToIntBits(initialValue);
        h = 31 * h + Float.floatToIntBits(finalValue);
        return 31 * h + Float.floatToIntBits(exponent);
    }

    @Override
    public String toString() {
        return typeName() + "[" + startTime + ".." + endTime + ": " + initialValue + "→" + finalValue
            + " ^" + exponent + "]";
    }
}
