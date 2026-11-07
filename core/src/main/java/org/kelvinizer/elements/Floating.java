package org.kelvinizer.elements;

import java.util.List;

/**
 * 悬浮键（泡泡）在哪里、多大、填了多少：空泡泡在原地浮现，从中心被颜色填满，正好满的时候点；位置、大小、填充都能用事件变。
 * 这里只有谱面写的内容和按拍子算出来的值，不碰屏幕。
 *
 * <p>Where a floating note is, how big and how full (CHART_FORMAT §11, Kelvin 2026-11-06 14:44–15:51: bubbles become
 * floating notes that appear in place, Cytus-like; the approved mock-up v3). In reference units: the centre's x from
 * the screen's centre line, its y 0–1200 from the bottom, the diameter. Each of them may move with eased events that
 * hold their end value until the next event starts (as an orbit centre does, {@link Events#heldValue}); before the
 * first event the written value.
 *
 * <p>The <b>fill</b> ({@link #fillAt}) is how far the colour still is from the bubble's edge, as a diameter (Kelvin
 * 15:43: 「气泡填充的程度有点类似于另外一个运动事件，所以是可以overflow」): 0 is exactly full, the moment to tap; more is
 * inside (the diameter itself: empty); less than 0 spills past the edge; more than the diameter has passed the centre
 * and comes out the other way, drawn as a disc of diameter {@code |diameter − fill|}. Without fill events it runs
 * evenly from empty when the note appears to full on its beat and on beyond (a late finger sees it spill); with
 * events, before the first the first one's start value, between two the earlier one's end value, after the last on
 * at that event's end speed (like a note past its line).
 *
 * <p>Immutable and thread-safe; nothing allocates once built.
 */
public final class Floating {
    /**
     * Diameter of a tap or STREAM that writes none: 250 (Kelvin 2026-11-06 21:22: 「手机上圆圈实在是太小了。默认大小改为250
     * 看一下」; 160 before, from 14:58).
     */
    public static final float TAP_SIZE = 250f;
    /** Diameter of a DRAG that writes none: 190, about the 14:58 ratio to a tap (120 : 160 before 21:22). */
    public static final float DRAG_SIZE = 190f;
    /** Diameter of a MINE / of a LONG MINE that writes none: 190 / 220, the mock-up v3 120 / 140 scaled with a tap. */
    public static final float MINE_SIZE = 190f, LONG_MINE_SIZE = 220f;
    /** Smallest and largest diameter drawn and judged; sizes outside are clamped where they are used. */
    public static final float MIN_SIZE = 2f, MAX_SIZE = 2400f;
    /** y of a note that writes none: the middle of the screen. */
    public static final float DEFAULT_Y = 600f;
    /** A note without fill events or {@code appear} shows this many beats before its own. */
    public static final float APPROACH_BEATS = Note.DEFAULT_APPROACH_BEATS;

    private final float x;
    private final float y;
    private final float size;
    private final float appear;
    private final List<Events> xEvents;
    private final List<Events> yEvents;
    private final List<Events> sizeEvents;
    private final List<Events> fillEvents;
    private final String chain;

    /**
     * @param x          centre x from the screen's centre line (non-finite: 0)
     * @param y          centre y from the bottom (non-finite: {@link #DEFAULT_Y})
     * @param size       diameter (not positive or non-finite: {@code defaultSize})
     * @param defaultSize the type's diameter ({@link #defaultSize})
     * @param appear     beat it shows from (NaN: the first fill event's start, else {@link #APPROACH_BEATS} before
     *                   {@code judgement}); never after {@code judgement}
     * @param judgement  its beat
     * @param xEvents    events for x (copied and sorted; null for none)
     * @param yEvents    events for y
     * @param sizeEvents events for the diameter
     * @param fillEvents fill events
     * @param chain      DRAGs that write the same chain are one route (trimmed; null or empty for none)
     */
    public Floating(float x, float y, float size, float defaultSize, float appear, float judgement,
                    List<Events> xEvents, List<Events> yEvents, List<Events> sizeEvents, List<Events> fillEvents,
                    String chain) {
        this.x = Float.isFinite(x) ? x : 0f;
        this.y = Float.isFinite(y) ? y : DEFAULT_Y;
        this.size = Float.isFinite(size) && size > 0f ? size : defaultSize;
        this.xEvents = Events.sortedCopy(xEvents);
        this.yEvents = Events.sortedCopy(yEvents);
        this.sizeEvents = Events.sortedCopy(sizeEvents);
        this.fillEvents = Events.sortedCopy(fillEvents);
        float a = appear;
        if (!Float.isFinite(a)) {
            a = this.fillEvents.isEmpty() ? judgement - APPROACH_BEATS : this.fillEvents.get(0).startTime();
        }
        this.appear = Float.isFinite(judgement) ? Math.min(a, judgement) : a;
        String c = chain == null ? "" : chain.trim();
        this.chain = c.isEmpty() ? null : c;
    }

    /** @return the diameter of a type that writes none, without a duration ({@link #defaultSize(NoteType, float)}) */
    public static float defaultSize(NoteType type) {
        return defaultSize(type, 0f);
    }

    /**
     * @return the diameter of a note that writes none: {@link #DRAG_SIZE} for a DRAG, {@link #MINE_SIZE} for a MINE,
     *         {@link #LONG_MINE_SIZE} for one with a duration, else {@link #TAP_SIZE}
     */
    public static float defaultSize(NoteType type, float duration) {
        if (type == NoteType.MINE) return duration > 0f ? LONG_MINE_SIZE : MINE_SIZE;
        return type == NoteType.DRAG ? DRAG_SIZE : TAP_SIZE;
    }

    /** @return the written centre x (before any event) */
    public float x() { return x; }

    /** @return the written centre y */
    public float y() { return y; }

    /** @return the written diameter (or the type's when none was written) */
    public float size() { return size; }

    /** @return the beat the note shows from */
    public float appear() { return appear; }

    /**
     * @param judgement the note's beat
     * @return the beat it would show from without {@code appear}: the first fill event's start, else
     *         {@link #APPROACH_BEATS} before {@code judgement} (writers leave {@code appear} out when it is this)
     */
    public float defaultAppear(float judgement) {
        float a = fillEvents.isEmpty() ? judgement - APPROACH_BEATS : fillEvents.get(0).startTime();
        return Math.min(a, judgement);
    }

    /** @return the events for x, sorted (empty for none) */
    public List<Events> xEvents() { return xEvents; }

    /** @return the events for y */
    public List<Events> yEvents() { return yEvents; }

    /** @return the events for the diameter */
    public List<Events> sizeEvents() { return sizeEvents; }

    /** @return the fill events */
    public List<Events> fillEvents() { return fillEvents; }

    /** @return the DRAG route it belongs to, or null */
    public String chain() { return chain; }

    /** @return the centre's x at {@code beat} */
    public float xAt(float beat) {
        return xEvents.isEmpty() ? x : Events.heldValue(xEvents, beat, x);
    }

    /** @return the centre's y at {@code beat} */
    public float yAt(float beat) {
        return yEvents.isEmpty() ? y : Events.heldValue(yEvents, beat, y);
    }

    /** @return the diameter at {@code beat}, within {@link #MIN_SIZE}–{@link #MAX_SIZE} */
    public float sizeAt(float beat) {
        float d = sizeEvents.isEmpty() ? size : Events.heldValue(sizeEvents, beat, size);
        return d > MIN_SIZE ? (d < MAX_SIZE ? d : MAX_SIZE) : MIN_SIZE;
    }

    /**
     * @param beat      the beat
     * @param judgement the note's beat
     * @return the fill at {@code beat} (see the class doc): 0 full, the diameter empty, below 0 spilling over
     */
    public float fillAt(float beat, float judgement) {
        if (fillEvents.isEmpty()) {
            float span = judgement - appear;
            if (!(span > 0f)) return 0f;
            return sizeAt(beat) * (judgement - beat) / span;
        }
        Events first = fillEvents.get(0);
        if (beat < first.startTime()) return first.initialValue();
        int lo = 0;
        int hi = fillEvents.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (fillEvents.get(mid).startTime() <= beat) lo = mid + 1;
            else hi = mid;
        }
        Events e = fillEvents.get(lo - 1);
        if (beat <= e.endTime()) return e.valueAt(beat);
        if (lo < fillEvents.size()) return e.finalValue();
        return e.finalValue() + endSpeed(e) * (beat - e.endTime());
    }

    /** @return the eased event's rate of change at its end, per beat (as a note past its line keeps moving) */
    private static float endSpeed(Events e) {
        float dt = e.endTime() - e.startTime();
        if (!(dt > 0f)) return 0f;
        float v = (e.finalValue() - e.initialValue()) * e.exponent() / dt;
        return Float.isFinite(v) ? v : 0f;
    }

    /**
     * @return the diameter of the coloured disc for a fill on a bubble of diameter {@code d}: {@code |d − fill|}
     */
    public static float discDiameter(float d, float fill) {
        return Math.abs(d - fill);
    }

    @Override
    public String toString() {
        return "float (" + x + ", " + y + ") d " + size + " from " + appear
            + (fillEvents.isEmpty() ? "" : " fill " + fillEvents.size()) + (chain != null ? " chain " + chain : "");
    }
}
