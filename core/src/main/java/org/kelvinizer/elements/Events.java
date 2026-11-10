package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
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
 * ({@link #valueAt(float)}), or with a custom ease ({@code "curve"}, {@link EaseCurve}) the curve's progress at
 * {@code p} in place of {@code p^exponent}. Outside every event of a list, {@link #calculateValue} returns the
 * channel's base value, <b>not</b> the last final value: charts hold a value by adding a
 * constant event (e.g. the window squeeze of the shipped charts holds 0..1920 with a 526–562
 * event). Every channel uses this rule: note movement / rotation / opacity / tail height, the
 * judgement-line, window and global lane scale events, and the lane width / opacity channels of
 * chart format v2. The one exception is a note's orbit centre ({@link NoteOrbit}): it stays where an
 * event leaves it ({@link #heldValue}).
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
    /** The custom ease ({@link EaseCurve#clean}), or null for {@code p^exponent}. */
    private final float[] curve;

    /**
     * @param type         channel (may be null for an unknown type; the event still evaluates)
     * @param startTime    start beat
     * @param endTime      end beat
     * @param initialValue value at {@code startTime}
     * @param finalValue   value at {@code endTime}
     * @param exponent     easing exponent (1 = linear)
     */
    public Events(TYPE type, float startTime, float endTime, float initialValue, float finalValue, float exponent) {
        this(type, startTime, endTime, initialValue, finalValue, exponent, null);
    }

    /**
     * The six-argument event with a custom ease.
     *
     * @param curve the {@code "curve"} points ({@link EaseCurve}); null, or a list that is not 6n + 2 finite
     *              numbers, keeps {@code exponent}. With a curve the exponent is 1: {@code "ease"} is ignored.
     */
    public Events(TYPE type, float startTime, float endTime, float initialValue, float finalValue, float exponent,
                  float[] curve) {
        this.type = type;
        this.startTime = startTime;
        this.endTime = endTime;
        this.initialValue = initialValue;
        this.finalValue = finalValue;
        this.curve = EaseCurve.clean(curve);
        this.exponent = this.curve != null ? 1f : exponent;
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
    /** @return easing exponent (1 with a custom ease). */
    public float exponent() { return exponent; }
    /** @return a copy of the custom ease points ({@link EaseCurve}), or null when the event eases by its exponent */
    public float[] curve() { return curve != null ? curve.clone() : null; }
    /** @return true when the event has a custom ease */
    public boolean hasCurve() { return curve != null; }

    /** @return the progress's rate at the end per time fraction: the exponent, or the custom ease's end slope */
    public float endSlope() { return curve != null ? EaseCurve.endSlope(curve) : exponent; }

    /** @return true when {@code o} eases the same way (exponent and custom ease) */
    public boolean sameEase(Events o) {
        return Float.compare(exponent, o.exponent) == 0 && Arrays.equals(curve, o.curve);
    }

    /** @return an event on channel {@code type} with this one's beats and ease and other values */
    public Events withValues(TYPE type, float initialValue, float finalValue) {
        return new Events(type, startTime, endTime, initialValue, finalValue, exponent, curve);
    }

    /** @return true when {@code start ≤ time ≤ end}. */
    public boolean contains(float time) {
        return startTime <= time && time <= endTime;
    }

    /**
     * @return the eased value at {@code time}: the final value for a zero-length event or after
     *         the end, otherwise {@code initial + (final − initial)·p^exponent} (or the custom ease's progress)
     */
    public float valueAt(float time) {
        if (endTime == startTime || time > endTime) {
            return finalValue;
        }
        float p = (time - startTime) / (endTime - startTime);
        if (curve != null) return initialValue + (finalValue - initialValue) * EaseCurve.at(curve, p);
        return (float) (initialValue + (finalValue - initialValue) * Math.pow(p, exponent));
    }

    /**
     * Evaluates a channel at {@code time}: binary search for the last event (by start time) that
     * starts at or before {@code time}; its eased value if it still covers {@code time}, else
     * {@code base}. Before the first event, when that one starts by beat 0: its value as it starts, so the run's first
     * frame shows what an event at the song's start sets (Kelvin 2026-11-08 19:31「如果有事件位于0拍及以前，那么游戏一进去的
     * 界面就是那个事件的样子」), however early the run starts. O(log n), no allocation.
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
        } else if (!events.isEmpty() && events.get(0).startTime <= 0f) {
            Events e = events.get(0);
            return e.valueAt(e.startTime);
        }
        return base;
    }

    /**
     * Like {@link #calculateValue}, but an event's final value holds after it ends, until the next event starts
     * (a note's orbit centre, Kelvin 2026-10-29: a key stays where a move took it).
     * Before the first event: {@code base}.
     *
     * @param events events sorted by start time
     * @param time   beat
     * @param base   value before the first event
     */
    public static float heldValue(List<Events> events, float time, float base) {
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
        return low > 0 ? events.get(low - 1).valueAt(time) : base;
    }

    /** A value map for {@link #mapValues}. */
    public interface ValueMap {
        float apply(float v);
    }

    /** A map of two channels' values for {@link #combine}. */
    public interface ValuePair {
        float apply(float a, float b);
    }

    /** @return {@code events} (null: none) with every value v as {@code f(v)}, the same beats and ease */
    public static List<Events> mapValues(List<Events> events, ValueMap f) {
        List<Events> out = new ArrayList<>();
        for (int i = 0; events != null && i < events.size(); i++) {
            Events e = events.get(i);
            if (e != null) out.add(e.withValues(e.type, f.apply(e.initialValue), f.apply(e.finalValue)));
        }
        return out;
    }

    /**
     * @return the events of {@code f(a, b)} for two channels, a (events {@code ea}, base {@code ab}) and b: exact when
     *         only one has events or when theirs line up (the same beats and ease); else each one's events with the
     *         other's value at their ends, an approximation (a chart's old keys converted, 2026-11-08)
     */
    public static List<Events> combine(List<Events> ea, float ab, List<Events> eb, float bb, ValuePair f) {
        List<Events> a = sortedCopy(ea), b = sortedCopy(eb), out = new ArrayList<>();
        boolean aligned = a.size() == b.size();
        for (int i = 0; aligned && i < a.size(); i++) {
            Events x = a.get(i), y = b.get(i);
            aligned = x.startTime == y.startTime && x.endTime == y.endTime && x.sameEase(y);
        }
        if (aligned) {
            for (int i = 0; i < a.size(); i++) {
                Events x = a.get(i), y = b.get(i);
                out.add(x.withValues(x.type, f.apply(x.initialValue, y.initialValue),
                    f.apply(x.finalValue, y.finalValue)));
            }
            return out;
        }
        for (Events x : a) {
            out.add(x.withValues(x.type, f.apply(x.initialValue, calculateValue(b, x.startTime, bb)),
                f.apply(x.finalValue, calculateValue(b, x.endTime, bb))));
        }
        for (Events y : b) {
            out.add(y.withValues(y.type, f.apply(calculateValue(a, y.startTime, ab), y.initialValue),
                f.apply(calculateValue(a, y.endTime, ab), y.finalValue)));
        }
        out.sort(BY_START);
        return out;
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

    /**
     * Equal timing, values and ease (exponent and custom ease). The {@link #type} is left out: an event's channel
     * is the list it is in, and format v5 has no type key, so an event reads back equal whichever format it came
     * from.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Events)) return false;
        Events e = (Events) o;
        return Float.compare(startTime, e.startTime) == 0
            && Float.compare(endTime, e.endTime) == 0
            && Float.compare(initialValue, e.initialValue) == 0
            && Float.compare(finalValue, e.finalValue) == 0
            && Float.compare(exponent, e.exponent) == 0
            && Arrays.equals(curve, e.curve);
    }

    @Override
    public int hashCode() {
        int h = Float.floatToIntBits(startTime);
        h = 31 * h + Float.floatToIntBits(endTime);
        h = 31 * h + Float.floatToIntBits(initialValue);
        h = 31 * h + Float.floatToIntBits(finalValue);
        h = 31 * h + Float.floatToIntBits(exponent);
        return curve != null ? 31 * h + Arrays.hashCode(curve) : h;
    }

    @Override
    public String toString() {
        return typeName() + "[" + startTime + ".." + endTime + ": " + initialValue + "→" + finalValue
            + (curve != null ? " curve" + Arrays.toString(curve) : " ^" + exponent) + "]";
    }
}
