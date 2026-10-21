package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 谱面中的一个音符（v2 模型）：位置、旋转、透明度和尾长都是谱面拍数的纯函数。轨道水雷碰到判定线后就固定在线上。
 *
 * <p>One note of a chart (format v2 model, ARCHITECTURE §13.2). Chart data is immutable; the
 * derived indices ({@link #index()}, {@link #laneIndex()}, {@link #slot()}, groups, sync,
 * {@link #sharesBeat()}, ms times) are filled once when the owning {@link Chart} is built.
 *
 * <p>Runtime judgement state (judged, grade, held, …) does not live here: the engine keeps it
 * in arrays indexed by {@link #index()}, so a chart can be replayed without reloading.
 *
 * <p>Motion (all pure functions of the chart beat, no allocation):
 * <ul>
 *   <li>{@link #positionAt}: distance from the note's judgement line in ref units (lower-line
 *       notes sit at {@code lineY + pos}, upper-line notes at {@code lineY − pos}). Outside its
 *       movement events a note is parked at {@link #HIDDEN_POSITION}, except a hold (or a TIDE
 *       STREAM) after its judgement time, whose head sticks to the line (0). A LANE MINE is
 *       <b>pinned</b> from its judgement beat on (Kelvin 2026-10-12 03:03: 「轨道水雷没法上下移动。碰到判定线就不能再动了」):
 *       distance exactly 0 and angle frozen at {@code angleAt(judgement)}, whatever movement or
 *       rotation events follow.</li>
 *   <li><b>Authored motion is used as written</b> (Kelvin 2026-10-13): beats no movement event
 *       covers are off screen, also just before the judgement, so a note can show its motion
 *       early and be judged later unseen. A missed tap slides on past the line only when a
 *       movement event reaches the judgement (within {@link #APPROACH_PROBE_BEATS}).</li>
 *   <li><b>Default approach</b>: only a note with <b>no</b> movement events moves
 *       {@link #DEFAULT_SPAWN_DISTANCE} → 0 linearly over the {@link #DEFAULT_APPROACH_BEATS}
 *       beats before its judgement (the speed of the shipped charts) and
 *       {@link #usesDefaultApproach()} is true. Exception (Kelvin 2026-10-12 21:09, as in the
 *       original game): when its authored {@code appearance} is at or after its judgement it has no
 *       approach. A tap, TIDE drop or mine stays hidden (a judgement point cued by another note,
 *       e.g. a bouncing one), and a hold, TIDE STREAM or LANE MINE appears on its line at its
 *       judgement and shrinks to its end. Format v1 charts keep their old look: the converter
 *       gives every v1 note whose motion misses its judgement the default approach as its
 *       movement ({@link #v1NeedsApproach}).</li>
 *   <li>{@link #angleAt} (degrees, base 0) and {@link #opacityAt} (base 1, from
 *       {@code DisappearEvents}). The note turns about its <b>pivot</b> (Kelvin 2026-10-16:
 *       「旋转一个note可以选择旋转中心的坐标，以这个note的几何中心为原点」): {@link #pivotX()},
 *       {@link #pivotY()} in reference units in the note's own frame (a sky note's frame is turned
 *       180° with it), origin (0, 0) = the centre of its head (a hold, TIDE STREAM or LANE MINE:
 *       the centre of its base, the tail left out; a TIDE: the middle of the on-screen part of its
 *       bar), the default. Drawing only.</li>
 *   <li>{@link #tailHeightAt}: visible tail length of a hold, TIDE STREAM band or LANE MINE
 *       column: the containing {@code HeightEvents} event's value (ARCHITECTURE §20 "Tail
 *       motion": a bar may keep growing while held), else the default rule ({@code height} until
 *       the judgement, then shrinking linearly to 0 at the end); never negative. Visual only:
 *       judging, {@link #endBeat()} and scoring never read it.</li>
 * </ul>
 *
 * <p><b>TIDE STREAM</b> (ARCHITECTURE §19): a {@link NoteType#UNIVERSAL universal} note with a
 * positive {@code duration} (beats; {@code height} is its band length in ref units, as for holds).
 * {@link #isTideStream()} tells it from the TIDE drop (a universal note without duration); it
 * pins to its line after its judgement like a hold and {@link #endBeat()} / {@link #endMs()}
 * include the duration.
 *
 * <p><b>LANE MINE</b> (轨道水雷, ARCHITECTURE §20): a {@link NoteType#MINE mine} with a positive
 * {@code duration}. It blocks its lane on its line from its judgement (when it reaches the line;
 * {@code Params.LANE_MINE_LEAD_MS} is 0) until {@link #endMs()} (half-open); {@link #isLaneMine()}
 * tells it from a plain mine. Like a hold it is {@link #isSustained() sustained}: its head stops on
 * the line and its column (length {@link #tailHeightAt}) follows it.
 *
 * <p>{@link #trails()} are the note's afterimage trail segments (ARCHITECTURE §17; several since
 * 2026-10-14, each with its own window and look) and {@link #glitches()} its glitch segments
 * (CHART_FORMAT §6.9): drawing only, never read by the engine; empty when the chart gives none.
 * {@link #trail()} is the first trail segment ({@link NoteTrail#NONE} when there is none).
 */
public final class Note {
    /** Movement value outside every movement event: far off screen. */
    public static final float HIDDEN_POSITION = 2000f;
    /**
     * Start distance of the default approach (ref units; where the shipped charts spawn notes; 972 when 1080 high).
     * Kept at 1080 when the lines moved to 1080 / 120 (Kelvin 2026-10-19 15:19: 「默认下落改为两拍1080的距离」).
     */
    public static final float DEFAULT_SPAWN_DISTANCE = 1080f;
    /** Length of the default approach in beats (1080 over 2 beats: the shipped charts' 540 units/beat). */
    public static final float DEFAULT_APPROACH_BEATS = 2f;
    /**
     * Movement ending at most this long before the judgement (1/32 beat, about 10 ms at 182 BPM)
     * still reaches it: shorter gaps are float rounding of the authoring tool.
     */
    public static final float APPROACH_PROBE_BEATS = 1f / 32f;

    private final NoteType type;
    private final Line line;
    private final int laneId;
    private final float appearance;
    private final float judgement;
    private final float duration;
    private final float height;
    private final List<Events> movementEvents;
    private final List<Events> rotationEvents;
    private final List<Events> disappearEvents;
    /** {@code HeightEvents} (tail length), kept only for sustained notes; empty otherwise. */
    private final List<Events> heightEvents;
    /** Movement used for positioning: the authored events, or the default approach. */
    private final List<Events> motion;
    private final boolean defaultApproach;
    /** Position at the judgement beat (the line for the default approach), for extrapolation. */
    private final float positionAtJudgement;
    private final float visibleFrom;
    private final float lastVelocity;
    /** Angle at the judgement beat, where a LANE MINE freezes (0 for other notes). */
    private final float pinnedAngle;
    private final float pivotX;
    private final float pivotY;
    private final NoteTrail trail;
    private final List<NoteTrail> trails;
    private final List<NoteGlitch> glitches;

    // Derived, set once by Chart.
    int index = -1;
    int laneIndex = -1;
    int slot = -1;
    int groupStart = -1;
    int groupSize = 1;
    int syncStart = -1;
    int syncSize = 1;
    boolean sharesBeat;
    double judgementMs;
    double endMs;

    /**
     * @param type            note type
     * @param line            judgement line
     * @param laneId          lane id (0 only for the inserted lane 0, CHART_FORMAT §3.6; ignored and
     *                        stored as 0 for universal)
     * @param appearance      authored appearance beat (NaN = derive from the events)
     * @param judgement       judgement beat (finite)
     * @param duration        length in beats of a hold, a TIDE STREAM (universal) or a LANE MINE
     *                        (mine); ignored for taps; negative, NaN or infinite → 0 (a universal
     *                        note with 0 is a TIDE drop, a mine with 0 a plain mine)
     * @param height          hold tail / TIDE STREAM band / LANE MINE column length in ref units (NaN → 0)
     * @param movementEvents  {@code MovementEvents} (copied and sorted; null = none)
     * @param rotationEvents  {@code RotationEvents}
     * @param disappearEvents {@code DisappearEvents} (note opacity)
     * @throws IllegalArgumentException for a null type / line or a non-finite judgement
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, NoteTrail.NONE);
    }

    /**
     * Same as the constructor without a trail, plus the note's afterimage trail.
     *
     * @param trail afterimage trail (null = {@link NoteTrail#NONE})
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, NoteTrail trail) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, null, trail);
    }

    /**
     * Same as the constructor with a trail, plus the note's {@code HeightEvents}.
     *
     * @param heightEvents visible tail length events (copied and sorted; null = none; kept only
     *                     for holds, TIDE STREAMs and LANE MINEs)
     * @param trail        afterimage trail (null = {@link NoteTrail#NONE})
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, NoteTrail trail) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trail == null ? null : Collections.singletonList(trail), null);
    }

    /**
     * Same as the constructor with {@code HeightEvents} and a trail, with any number of trail
     * segments and the note's glitch segments (CHART_FORMAT §6.8–§6.9).
     *
     * @param trails   afterimage trail segments (null = none; {@link NoteTrail#NONE} entries and
     *                 nulls are left out)
     * @param glitches glitch segments (null = none; nulls are left out; kept sorted by
     *                 {@code from}, stable)
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, 0f, 0f);
    }

    /**
     * Same as the constructor with trail segments and glitches, plus the rotation pivot (CHART_FORMAT
     * §6.1.1, Kelvin 2026-10-16).
     *
     * @param pivotX pivot x in reference units in the note's own frame, from the origin ({@link #pivotX()};
     *               NaN or infinite → 0)
     * @param pivotY pivot y, likewise (−20 = the edge of a DROP that faces its line)
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY) {
        if (type == null || line == null) throw new IllegalArgumentException("note type and line are required");
        if (Float.isNaN(judgement) || Float.isInfinite(judgement)) {
            throw new IllegalArgumentException("note judgement must be finite: " + judgement);
        }
        this.type = type;
        this.line = line;
        this.laneId = type.inLane() ? laneId : 0;
        this.judgement = judgement == 0f ? 0f : judgement; // folds −0 into +0 so == and compare agree
        this.duration = type.sustainable() && duration > 0f && duration < Float.POSITIVE_INFINITY ? duration : 0f;
        this.height = Float.isNaN(height) || Float.isInfinite(height) ? 0f : height;
        this.movementEvents = Events.sortedCopy(movementEvents);
        this.rotationEvents = Events.sortedCopy(rotationEvents);
        this.disappearEvents = Events.sortedCopy(disappearEvents);
        this.heightEvents = isSustained() ? Events.sortedCopy(heightEvents) : Collections.<Events>emptyList();
        this.defaultApproach = this.movementEvents.isEmpty()
            && !appearsAtJudgement(this.movementEvents, appearance, this.judgement);
        this.motion = defaultApproach ? Collections.singletonList(defaultApproachEvent(this.judgement))
            : this.movementEvents;
        // the slide past the line starts where the motion reaching the judgement leaves the note
        Events atJudgement = activeAt(this.motion, this.judgement, false);
        this.positionAtJudgement = atJudgement != null && atJudgement.endTime() >= this.judgement - APPROACH_PROBE_BEATS
            ? atJudgement.valueAt(this.judgement) : HIDDEN_POSITION;
        float from = this.judgement;
        if (!this.motion.isEmpty()) from = Math.min(from, this.motion.get(0).startTime());
        if (!this.rotationEvents.isEmpty()) from = Math.min(from, this.rotationEvents.get(0).startTime());
        if (!this.disappearEvents.isEmpty()) from = Math.min(from, this.disappearEvents.get(0).startTime());
        this.visibleFrom = from;
        this.appearance = Float.isNaN(appearance) || Float.isInfinite(appearance) ? from : appearance;
        float v = 0f;
        if (!this.motion.isEmpty()) {
            Events last = this.motion.get(this.motion.size() - 1);
            float dt = last.endTime() - last.startTime();
            if (dt > 0f) v = (last.finalValue() - last.initialValue()) / dt;
        }
        this.lastVelocity = v;
        this.pinnedAngle = isLaneMine() ? Events.calculateValue(this.rotationEvents, this.judgement, 0f) : 0f;
        this.pivotX = finiteOrZero(pivotX);
        this.pivotY = finiteOrZero(pivotY);
        this.trails = visibleTrails(trails);
        this.trail = this.trails.isEmpty() ? NoteTrail.NONE : this.trails.get(0);
        this.glitches = sortedGlitches(glitches);
    }

    private static float finiteOrZero(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) || v == 0f ? 0f : v; // also folds −0 into +0
    }

    private static List<NoteTrail> visibleTrails(List<NoteTrail> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<NoteTrail> out = new ArrayList<>(in.size());
        for (NoteTrail t : in) {
            if (t != null && !t.isNone()) out.add(t);
        }
        return out.isEmpty() ? Collections.<NoteTrail>emptyList() : Collections.unmodifiableList(out);
    }

    private static List<NoteGlitch> sortedGlitches(List<NoteGlitch> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<NoteGlitch> out = new ArrayList<>(in.size());
        for (NoteGlitch g : in) {
            if (g != null) out.add(g);
        }
        if (out.isEmpty()) return Collections.emptyList();
        out.sort((a, b) -> Float.compare(a.from(), b.from())); // List.sort is stable
        return Collections.unmodifiableList(out);
    }

    /**
     * @return true for a note with no movement events whose authored appearance is at or after its
     *         judgement: it has no approach, as in the original game (a tap is never drawn, a bar
     *         appears on its line at its judgement), so it does not get the default approach
     */
    private static boolean appearsAtJudgement(List<Events> movement, float appearance, float judgement) {
        return movement.isEmpty() && !Float.isNaN(appearance) && !Float.isInfinite(appearance)
            && appearance >= judgement;
    }

    /**
     * The approach rule of format v1 (and of v2 before 2026-10-13): true when the movement events
     * do not cover the moment {@link #APPROACH_PROBE_BEATS} before the judgement and the note is
     * not an appearance-at-judgement one. Such a note moved with the default approach, so the
     * converter gives it {@link #defaultApproachEvent} as its movement.
     */
    public static boolean v1NeedsApproach(List<Events> movement, float appearance, float judgement) {
        List<Events> m = Events.sortedCopy(movement);
        return !appearsAtJudgement(m, appearance, judgement)
            && activeAt(m, judgement - APPROACH_PROBE_BEATS, true) == null;
    }

    /** @return the default approach as a movement event: 1080 → 0, linear, over the 2 beats before the judgement. */
    public static Events defaultApproachEvent(float judgement) {
        return new Events(Events.TYPE.Movement, judgement - DEFAULT_APPROACH_BEATS, judgement, DEFAULT_SPAWN_DISTANCE,
            0f, 1f);
    }

    /**
     * @return the event {@link Events#calculateValue} uses at {@code beat} (the last one starting
     *         at or before it), or null; with {@code covering} only when it also contains the beat
     */
    private static Events activeAt(List<Events> events, float beat, boolean covering) {
        Events active = null;
        for (int i = 0; i < events.size(); i++) {
            Events e = events.get(i);
            if (e.startTime() <= beat) active = e;
            else break;
        }
        if (active != null && covering && !active.contains(beat)) return null;
        return active;
    }

    // ------------------------------------------------------------------ chart data

    /** @return note type. */
    public NoteType type() { return type; }
    /** @return judgement line. */
    public Line line() { return line; }
    /** @return true for a sky-line note. */
    public boolean isUpper() { return line == Line.UPPER; }
    /** @return lane id (0 for universal notes). */
    public int laneId() { return laneId; }
    /** @return authored appearance beat (informational; drawing uses {@link #visibleFrom()}). */
    public float appearance() { return appearance; }
    /** @return judgement beat. */
    public float judgement() { return judgement; }
    /** @return length in beats of a hold, a TIDE STREAM or a LANE MINE (0 for every other note). */
    public float duration() { return duration; }

    /**
     * @return true for a TIDE STREAM (潮汐水流): a universal note with a positive duration, held by
     *         any finger on its line (ARCHITECTURE §19). A universal note without one is a TIDE drop.
     */
    public boolean isTideStream() { return type == NoteType.UNIVERSAL && duration > 0f; }

    /**
     * @return true for a LANE MINE (轨道水雷): a mine with a positive duration that blocks its lane
     *         on its line (ARCHITECTURE §20). A mine without one is a plain MINE.
     */
    public boolean isLaneMine() { return type == NoteType.MINE && duration > 0f; }

    /**
     * @return true when the head sticks to the line after the judgement and a tail (hold ribbon,
     *         TIDE STREAM band, LANE MINE column) follows it: holds, TIDE STREAMs and LANE MINEs
     */
    public boolean isSustained() { return type == NoteType.HOLD || isTideStream() || isLaneMine(); }

    /**
     * @return the note's English display name (DROP, STREAM, MINE, LANE MINE, TIDE or TIDE STREAM;
     *         from {@link NoteType})
     */
    public String displayName() { return type.display(duration > 0f); }

    /** @return the note's Chinese display name (水滴, 水流, 水雷, 轨道水雷, 潮汐 or 潮汐水流). */
    public String displayNameZh() { return type.displayZh(duration > 0f); }
    /** @return {@code judgement + duration} in beats. */
    public float endBeat() { return judgement + duration; }
    /** @return hold tail (TIDE STREAM band, LANE MINE column) length in ref units as authored. */
    public float height() { return height; }
    /**
     * @return authored movement events, sorted by start (unmodifiable); a note without any moves
     *         with the default approach (see {@link #usesDefaultApproach()})
     */
    public List<Events> movementEvents() { return movementEvents; }
    /** @return rotation events, sorted by start (unmodifiable). */
    public List<Events> rotationEvents() { return rotationEvents; }
    /** @return opacity events ({@code DisappearEvents}), sorted by start (unmodifiable). */
    public List<Events> disappearEvents() { return disappearEvents; }

    /**
     * @return tail length events ({@code HeightEvents}), sorted by start (unmodifiable); always
     *         empty for notes that are not {@link #isSustained() sustained}
     */
    public List<Events> heightEvents() { return heightEvents; }

    /**
     * @return the first afterimage trail segment (ARCHITECTURE §17), never null:
     *         {@link NoteTrail#NONE} when the note has none. Visual only; the engine never reads it.
     *         {@link #trails()} has every segment.
     */
    public NoteTrail trail() { return trail; }

    /**
     * @return every afterimage trail segment in chart order, each with copies (unmodifiable; empty
     *         when the note has none). Visual only.
     */
    public List<NoteTrail> trails() { return trails; }

    /**
     * @return the glitch segments sorted by {@code from} (unmodifiable; empty when the note has
     *         none; {@link NoteGlitch#activeAt} picks the one shown). Visual only.
     */
    public List<NoteGlitch> glitches() { return glitches; }

    // ------------------------------------------------------------------ derived (set by Chart)

    /** @return position in {@link Chart#notes()}. */
    public int index() { return index; }

    /**
     * @return index of the note's lane in {@link Chart#lanes()} / {@link LaneLayout}, or −1 for
     *         universal notes
     */
    public int laneIndex() { return laneIndex; }

    /**
     * @return position in the note's stream array: {@link Chart#laneNotes} for tap / hold,
     *         {@link Chart#laneMines} for mines, {@link Chart#universals} for universal notes
     */
    public int slot() { return slot; }

    /**
     * @return for a universal note, the slot (in {@link Chart#universals}) of the first member of
     *         its group: universal notes on the same line with exactly the same judgement beat
     *         (ARCHITECTURE §13.2); for other types, {@link #slot()}
     */
    public int groupStart() { return groupStart; }

    /** @return size of the universal group (1 for other types and lone universal notes). */
    public int groupSize() { return groupSize; }

    /**
     * @return index in {@link Chart#notes()} of the first note of this note's sync group: the
     *         judged notes (not mines) on the same line with exactly the same judgement beat,
     *         stored contiguously (universal first, then lanes left to right)
     */
    public int syncStart() { return syncStart; }

    /** @return size of the sync group (1 = not a sync note). Mines are always 1. */
    public int syncSize() { return syncSize; }

    /** @return true when the note shares its judgement beat with another judged note on the same line. */
    public boolean isSync() { return syncSize > 1; }

    /**
     * @return true when any other note of the chart has exactly the same judgement beat: any type
     *         on either line (DROPs, STREAMs, MINEs, LANE MINEs, TIDEs and TIDE STREAMs alike), so
     *         a DROP with a same-line TIDE, stacked TIDEs and a row of mines all share their beat.
     *         Such a note carries the sync pearl while it is live (Kelvin 2026-10-12 12:39
     *         「同一时刻都带」). Drawing only: joint judging reads the sync and universal groups.
     */
    public boolean sharesBeat() { return sharesBeat; }

    /** @return judgement time in song ms. */
    public double judgementMs() { return judgementMs; }

    /** @return end time ({@link #endBeat()}) in song ms. */
    public double endMs() { return endMs; }

    // ------------------------------------------------------------------ motion

    /**
     * @return true when the note has no movement events (and no appearance at or after its
     *         judgement), so it moves with the default approach (see the class comment)
     */
    public boolean usesDefaultApproach() { return defaultApproach; }

    /**
     * @return earliest beat at which any of the note's events (the default approach included)
     *         starts, or its judgement beat
     */
    public float visibleFrom() { return visibleFrom; }

    /**
     * @return velocity of the last movement event in ref units per beat (0 if none or zero
     *         length); an unjudged tap past its time keeps sliding at this speed while it fades
     */
    public float lastVelocity() { return lastVelocity; }

    /**
     * @return distance from the judgement line at {@code beat} (see the class comment); exactly 0
     *         for a LANE MINE at or after its judgement beat
     */
    public float positionAt(float beat) {
        if (beat >= judgement && isLaneMine()) return 0f;
        float base = isSustained() && beat > judgement ? 0f : HIDDEN_POSITION;
        return Events.calculateValue(motion, beat, base);
    }

    /**
     * @return the position extrapolated past the judgement beat: the position at the judgement
     *         (from the last movement event started by then, its final value when it already
     *         ended) {@code + lastVelocity·(beat − judgement)}: how a missed tap keeps sliding
     *         through the line; {@link #HIDDEN_POSITION} when no movement reaches the judgement
     */
    public float positionPastJudgement(float beat) {
        if (positionAtJudgement == HIDDEN_POSITION) return HIDDEN_POSITION; // judged unseen: stays off screen
        return positionAtJudgement + lastVelocity * (beat - judgement);
    }

    /**
     * @return rotation in degrees at {@code beat}; a LANE MINE keeps {@code angleAt(judgement)}
     *         from its judgement beat on
     */
    public float angleAt(float beat) {
        if (beat >= judgement && isLaneMine()) return pinnedAngle;
        return Events.calculateValue(rotationEvents, beat, 0f);
    }

    /**
     * @return x of the rotation pivot (reference units, in the note's own frame: + is right on the
     *         ground line and left on the sky line, whose frame is turned 180°) from the origin, the
     *         centre of the head (a hold, TIDE STREAM or LANE MINE: of its base; a TIDE: the middle of
     *         the on-screen part of its bar); 0 by default
     */
    public float pivotX() { return pivotX; }

    /**
     * @return y of the rotation pivot from the origin, + away from the line (reference units; −20 is a
     *         DROP's edge facing its line, where notes turned before 2026-10-16); 0 by default
     */
    public float pivotY() { return pivotY; }

    /** @return true when the chart gives the note a pivot other than its origin. */
    public boolean hasPivot() { return pivotX != 0f || pivotY != 0f; }

    /** @return note opacity at {@code beat} (authored {@code DisappearEvents}, base 1). */
    public float opacityAt(float beat) {
        return Events.calculateValue(disappearEvents, beat, 1f);
    }

    /** @return the opacity the note is drawn with at {@code beat}: {@link #shownOpacity} of {@link #opacityAt}. */
    public float shownOpacityAt(float beat) {
        return shownOpacity(opacityAt(beat));
    }

    /**
     * Opacity as drawn: 0 for {@code o ≤ 0} or NaN, {@code o} up to 1, and above 1 the low byte of
     * {@code 255·o} over 255, as the old game's colour packing did, so an opacity rising past 1 blinks:
     * 0 → 12 in one beat brightens and drops out twelve times (Kelvin 2026-10-13: 「之前是比如说我设置opacity在1拍内
     * 从0到12，那么它就会先变亮再变暗，这种交替进行，就能创造闪烁的效果」).
     */
    public static float shownOpacity(float o) {
        if (!(o > 0f)) return 0f;
        if (o <= 1f) return o;
        return ((int) (255f * o) & 0xFF) / 255f;
    }

    /**
     * @return visible tail length at {@code beat} of a hold, TIDE STREAM band or LANE MINE column:
     *         the value of the {@code HeightEvents} event containing {@code beat}, else the default
     *         rule ({@link #height()} until the judgement beat, then shrinking linearly to 0 at the
     *         end); clamped to ≥ 0. 0 for every other note. Drawing only.
     */
    public float tailHeightAt(float beat) {
        if (!isSustained()) return 0f;
        float base;
        if (duration <= 0f || beat <= judgement) {
            base = height;
        } else {
            float progress = (beat - judgement) / duration;
            base = Math.max(0f, height * (1f - progress));
        }
        if (heightEvents.isEmpty()) return base;
        float v = Events.calculateValue(heightEvents, beat, base);
        return v > 0f ? v : 0f;
    }

    @Override
    public String toString() {
        return type.json + "@" + judgement + " " + line.json + (type.inLane() ? " lane " + laneId : "")
            + (duration > 0f ? " dur " + duration : "")
            + (heightEvents.isEmpty() ? "" : " heights " + heightEvents.size())
            + (trails.isEmpty() ? "" : " " + (trails.size() == 1 ? trail.toString() : trails.size() + " trails"))
            + (glitches.isEmpty() ? "" : " " + glitches.size() + " glitch(es)")
            + (hasPivot() ? " pivot (" + pivotX + ", " + pivotY + ")" : "");
    }
}
