package org.kelvinizer.elements;

import java.util.List;

/**
 * v2 谱面的一条轨道（不可变）：用非 0 的 id 标识，负数在左，按 id 从小到大排列，两条判定线共用。
 * 宽度权重和透明度都可以随拍数变化；透明度只作用于轨道自己的装饰（分隔线、刻度、光晕），不影响音符。
 * 插入的轨道（2026-10-15）在插入开始前不存在，插入时从初始大小长到最终大小，0 号轨只能是插入的轨道。
 * 删除的轨道（2026-10-17）从删除开始时的宽度缩到最终大小（默认 0），删完以后不存在。
 *
 * <p>One lane of chart format v2 (ARCHITECTURE §13.1), immutable.
 *
 * <p>A lane is identified by a non-zero id; negative ids sit left of positive ids and lanes are
 * laid out in ascending id order. Both judgement lines share the lane set. Each lane has two
 * animated channels evaluated with {@link Events#calculateValue}:
 * <ul>
 *   <li><b>width</b> (base {@value #DEFAULT_WIDTH}): negative or non-finite values count as 0.
 *       With unlimited lanes (the default) it is the lane's width in reference units; with a
 *       finite {@code laneCount} it is a weight and {@link LaneLayout} divides the frame in
 *       proportion (see {@link Chart#laneLimit()}).</li>
 *   <li><b>opacity</b> (base 1, clamped 0..1): multiplies the lane's own furniture only
 *       (separators, ticks, glows), never its notes.</li>
 * </ul>
 *
 * <p>An <b>inserted</b> lane (owner 2026-10-15, chart key {@code insert}) has width 0 before its
 * insertion starts, follows the insertion event (initial → final, eased) while it runs and is a
 * normal lane after it, with {@code width} = the event's final value. Only an inserted lane may
 * have id 0 (inserted between −1 and 1). With unlimited lanes {@link LaneLayout} squeezes the
 * other lanes to make room on the screen.
 *
 * <p>A <b>deleted</b> lane (owner 2026-10-17, chart key {@code delete}) is normal until its deletion
 * starts, follows the deletion event (initial → final, eased; by default from its width at the start
 * down to 0) while it runs and has width 0 after it. With unlimited lanes {@link LaneLayout} widens
 * the other lanes to take back its room. A lane may be both inserted and deleted; once the deletion
 * starts it wins.
 */
public final class Lane {
    /** Default width weight (8 lanes × 240 fill the 1920-wide screen). */
    public static final float DEFAULT_WIDTH = 240f;
    /** Default opacity. */
    public static final float DEFAULT_OPACITY = 1f;

    private final int id;
    private final float width;
    private final float opacity;
    private final List<Events> widthEvents;
    private final List<Events> opacityEvents;
    private final boolean implicit;
    private final Events insert;
    private final Events deletion;
    /** Width the lane would have at the start of its deletion without it (what the deletion gives back). */
    private final float deletedFrom;

    /** Creates a lane with default width, opacity and no events. */
    public Lane(int id) {
        this(id, DEFAULT_WIDTH, DEFAULT_OPACITY, null, null);
    }

    private Lane(int id, boolean implicit) {
        this.id = id;
        this.width = DEFAULT_WIDTH;
        this.opacity = DEFAULT_OPACITY;
        this.widthEvents = Events.sortedCopy(null);
        this.opacityEvents = Events.sortedCopy(null);
        this.implicit = implicit;
        this.insert = null;
        this.deletion = null;
        this.deletedFrom = 0f;
    }

    /**
     * @return a default lane the loader adds for unlimited lanes (an id gap, or a lane the screen
     *         can reach); it is not written back to the file
     */
    static Lane implicitLane(int id) {
        if (id == 0) throw new IllegalArgumentException("lane id 0 is invalid");
        return new Lane(id, true);
    }

    /**
     * @param id            non-zero lane id
     * @param width         base width weight
     * @param opacity       base opacity
     * @param widthEvents   width channel events (copied and sorted; null = none)
     * @param opacityEvents opacity channel events (copied and sorted; null = none)
     * @throws IllegalArgumentException when {@code id == 0}
     */
    public Lane(int id, float width, float opacity, List<Events> widthEvents, List<Events> opacityEvents) {
        this(id, width, opacity, widthEvents, opacityEvents, null);
    }

    /**
     * A lane that may be inserted during the song (owner 2026-10-15).
     *
     * @param id            lane id; 0 only for an inserted lane
     * @param width         base width weight (an inserted lane's width once its insertion is over;
     *                      the reader passes the insertion's final value)
     * @param opacity       base opacity
     * @param widthEvents   width channel events (copied and sorted; null = none), used after the insertion
     * @param opacityEvents opacity channel events (copied and sorted; null = none)
     * @param insert        the insertion (start and end beat, initial and final width, exponent), or null
     *                      for a lane that is always there
     * @throws IllegalArgumentException when {@code id == 0} and {@code insert} is null
     */
    public Lane(int id, float width, float opacity, List<Events> widthEvents, List<Events> opacityEvents,
                Events insert) {
        this(id, width, opacity, widthEvents, opacityEvents, insert, null);
    }

    /**
     * A lane that may be inserted and / or deleted during the song (owner 2026-10-15, 2026-10-17).
     *
     * @param id            lane id; 0 only for an inserted lane
     * @param width         base width weight (see the six-argument constructor)
     * @param opacity       base opacity
     * @param widthEvents   width channel events (copied and sorted; null = none)
     * @param opacityEvents opacity channel events (copied and sorted; null = none)
     * @param insert        the insertion, or null
     * @param deletion      the deletion (start and end beat, initial and final width, exponent), or null for
     *                      a lane that stays; from its start beat it overrides everything else, and the lane
     *                      is 0 wide after its end
     * @throws IllegalArgumentException when {@code id == 0} and {@code insert} is null
     */
    public Lane(int id, float width, float opacity, List<Events> widthEvents, List<Events> opacityEvents,
                Events insert, Events deletion) {
        if (id == 0 && insert == null) throw new IllegalArgumentException("lane id 0 is invalid");
        this.id = id;
        this.width = width;
        this.opacity = opacity;
        this.widthEvents = Events.sortedCopy(widthEvents);
        this.opacityEvents = Events.sortedCopy(opacityEvents);
        this.implicit = false;
        this.insert = insert;
        this.deletion = deletion;
        this.deletedFrom = deletion != null ? normalWidthAt(deletion.startTime()) : 0f;
    }

    /** @return the lane id (0 only for an inserted lane). */
    public int id() { return id; }
    /** @return base width weight as authored. */
    public float baseWidth() { return width; }
    /** @return base opacity as authored. */
    public float baseOpacity() { return opacity; }
    /** @return width events, sorted by start (unmodifiable). */
    public List<Events> widthEvents() { return widthEvents; }
    /** @return opacity events, sorted by start (unmodifiable). */
    public List<Events> opacityEvents() { return opacityEvents; }
    /** @return true for a lane the loader added (not in the file's {@code lanes}; not written back). */
    public boolean implicit() { return implicit; }
    /** @return true for a lane inserted during the song ({@link #insert()} is not null). */
    public boolean inserted() { return insert != null; }
    /** @return the insertion (start and end beat, initial and final width, exponent), or null. */
    public Events insert() { return insert; }
    /** @return true for a lane deleted during the song ({@link #deletion()} is not null). */
    public boolean deleted() { return deletion != null; }
    /** @return the deletion (start and end beat, initial and final width, exponent), or null. */
    public Events deletion() { return deletion; }
    /**
     * @return the width the lane would have at the start of its deletion without it (insertion and width
     *         events only; ≥ 0), the room the deletion gives back to the other lanes; 0 without a deletion
     */
    public float deletedFrom() { return deletedFrom; }

    /**
     * @return the width weight at {@code beat}, clamped to ≥ 0 (negative, NaN or infinite → 0); an
     *         inserted lane is 0 before its insertion and follows the insertion while it runs; a deleted
     *         lane follows its deletion from the start beat and is 0 after the end beat
     */
    public float widthAt(float beat) {
        if (deletion != null && beat >= deletion.startTime()) {
            float w = beat > deletion.endTime() ? 0f : deletion.valueAt(beat);
            return w > 0f && w < Float.POSITIVE_INFINITY ? w : 0f;
        }
        return normalWidthAt(beat);
    }

    /** {@link #widthAt} without the deletion: the insertion, then the width channel; clamped to ≥ 0. */
    private float normalWidthAt(float beat) {
        float w;
        if (insert != null && !(beat > insert.endTime())) {
            w = beat < insert.startTime() ? 0f : insert.valueAt(beat);
        } else {
            w = widthEvents.isEmpty() ? width : Events.calculateValue(widthEvents, beat, width);
        }
        return w > 0f && w < Float.POSITIVE_INFINITY ? w : 0f;
    }

    /** @return the opacity at {@code beat}, clamped to 0..1 (NaN → 0). */
    public float opacityAt(float beat) {
        float o = opacityEvents.isEmpty() ? opacity : Events.calculateValue(opacityEvents, beat, opacity);
        if (!(o > 0f)) return 0f;
        return o < 1f ? o : 1f;
    }

    @Override
    public String toString() {
        return "Lane(" + id + ", w " + width + ", a " + opacity + ", " + widthEvents.size() + "/"
            + opacityEvents.size() + " events" + (insert != null ? ", inserted " + insert.startTime() + ".."
            + insert.endTime() : "") + (deletion != null ? ", deleted " + deletion.startTime() + ".."
            + deletion.endTime() : "") + ")";
    }
}
