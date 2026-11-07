package org.kelvinizer.elements;

import java.util.Collections;
import java.util.List;

/**
 * 叠加层（Kelvin 2026-11-05，和谱面编辑器商定）：同一个通道上可以互相重叠的事件，每个事件在它的拍子里把自己的值加到原来的值上，
 * 事件外面加 0。键的横坐标、离线距离、旋转，判定线的高度和镜头都可以有。
 *
 * <p>Additive layers of one channel (a key's x, a note's distance from its line or its rotation; a line's height or
 * the camera): every event adds its eased value while it runs, events may overlap, and outside every event the
 * layers add 0. An event runs from its start beat to its end beat; its end beat counts unless another event of the
 * same list starts on it, so a chain of events hands over without adding the joint twice (a zero-length event counts
 * on its beat). Immutable; {@link #at} is O(log n + events running), allocation-free and thread-safe.
 */
public final class Layers {
    /** No layers: adds 0 everywhere. */
    public static final Layers NONE = new Layers(Collections.<Events>emptyList());

    private final List<Events> events;
    /** {@code reach[k]}: the latest end among {@code events[0..k]}, so the scan back stops early. */
    private final float[] reach;
    private final float low;
    private final float high;

    private Layers(List<Events> sorted) {
        events = sorted;
        reach = new float[sorted.size()];
        float r = Float.NEGATIVE_INFINITY;
        float lo = 0f;
        float hi = 0f;
        for (int k = 0; k < sorted.size(); k++) {
            Events e = sorted.get(k);
            r = Math.max(r, e.endTime());
            reach[k] = r;
            lo += Math.min(0f, least(e));
            hi += Math.max(0f, most(e));
        }
        low = lo;
        high = hi;
    }

    /**
     * @param events layer events (copied and sorted by start, stable, as every model list; the {@link Events} objects
     *               are kept, so editor keys stay attached; null or empty = {@link #NONE})
     */
    public static Layers of(List<Events> events) {
        List<Events> sorted = Events.sortedCopy(events);
        return sorted.isEmpty() ? NONE : new Layers(sorted);
    }

    /** @return true when there is no layer event. */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /** @return the events, sorted by start (unmodifiable). */
    public List<Events> events() {
        return events;
    }

    /** @return what the layers add at {@code beat}: the sum of the events running then (0 outside every event). */
    public float at(float beat) {
        int n = events.size();
        if (n == 0) return 0f;
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).startTime() <= beat) lo = mid + 1;
            else hi = mid;
        }
        // events[0 .. lo) start at or before beat; one ending on beat hands over when another starts on it
        boolean handOver = lo > 0 && events.get(lo - 1).startTime() == beat;
        float sum = 0f;
        for (int k = lo - 1; k >= 0 && reach[k] >= beat; k--) {
            Events e = events.get(k);
            float end = e.endTime();
            if (end < beat || end == beat && handOver && e.startTime() < end) continue;
            sum += e.valueAt(beat);
        }
        return sum;
    }

    /** @return the least the layers can add at any beat (≤ 0): every event at its lower end value where that is below 0. */
    public float low() {
        return low;
    }

    /** @return the most the layers can add at any beat (≥ 0): every event at its higher end value where above 0. */
    public float high() {
        return high;
    }

    /** @return the least the layers can add at a beat of {@code [a, b]} (≤ 0; an eased event stays between its ends). */
    public float low(float a, float b) {
        float v = 0f;
        for (int k = 0; k < events.size(); k++) {
            Events e = events.get(k);
            if (e.startTime() > b) break;
            if (e.endTime() >= a) v += Math.min(0f, least(e));
        }
        return v;
    }

    /** @return the most the layers can add at a beat of {@code [a, b]} (≥ 0). */
    public float high(float a, float b) {
        float v = 0f;
        for (int k = 0; k < events.size(); k++) {
            Events e = events.get(k);
            if (e.startTime() > b) break;
            if (e.endTime() >= a) v += Math.max(0f, most(e));
        }
        return v;
    }

    private static float least(Events e) {
        return finite(Math.min(e.initialValue(), e.finalValue()));
    }

    private static float most(Events e) {
        return finite(Math.max(e.initialValue(), e.finalValue()));
    }

    private static float finite(float v) {
        return v > Float.NEGATIVE_INFINITY && v < Float.POSITIVE_INFINITY ? v : 0f;
    }

    @Override
    public String toString() {
        return events.isEmpty() ? "Layers[]" : "Layers" + events;
    }
}
