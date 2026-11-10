package org.kelvinizer.elements;

import java.util.ArrayList;
import java.util.Arrays;
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
 *       early and be judged later unseen. A missed tap goes on past the line only when a
 *       movement event reaches the judgement (within {@link #APPROACH_PROBE_BEATS}): it follows its
 *       events, and after the last one keeps that event's end speed, position and angle alike, like
 *       inertia (Kelvin 2026-10-27: 「按照最后的事件延续吧，保留速度的那个值」).</li>
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
 *       bar), the default. A lane note may turn about an absolute point of the screen instead, its
 *       {@link #orbit()} (公转, Kelvin 2026-10-29), which can move and carries the note with it. Drawing only.</li>
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
 * 2026-10-14, each with its own window and look), {@link #glitches()} its glitch segments
 * (CHART_FORMAT_v4 §6.9) and {@link #distortions()} its distortion segments (§6.11): drawing only, never read by
 * the engine; empty when the chart gives none.
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
    private final float lanePos;
    private final boolean byX;
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
    private final List<NoteDistortion> distortions;
    private final float waveSpeed;
    private final float offsetX;
    private final List<Events> offsetXEvents;
    private final float orbitFrom;
    private final float orbitTo;
    private final NoteOrbit orbit;
    private final boolean fake;
    /** The key's 3D motion ({@link Channels#SOLID}), or null. */
    private final Channels space;

    // Derived, set once by Chart.
    int index = -1;
    int laneIndex = -1;
    boolean between;
    int slot = -1;
    int groupStart = -1;
    int groupSize = 1;
    int syncStart = -1;
    int syncSize = 1;
    boolean sharesBeat;
    double judgementMs;
    double endMs;
    /**
     * The sea it lands on ({@code "on"}, Kelvin 2026-11-07 16:04): its index and, once the chart has it, itself; only
     * a key placed by its own x or a TIDE (17:07 「海浪是独立于轨道的一种无轨判定面」).
     */
    int seaIndex = -1;
    Sea sea;
    /** {@code "track": "space"} ({@link #spaceTrack()}), set by the loader. */
    boolean spaceTrack;
    /** {@code "spans"} ({@link #spanCount()}): pairs of a kind (0 a lane id, 1 a sea index) and its value; or null. */
    int[] spans;
    /** A chart format v7 key's opacity and its lanes' ({@link #setOpacity7}), or null. */
    private Fn opacity7;
    private Fn[] laneOpacity7;
    /**
     * A space key's lane at its judgement beat ({@link LaneLayout#freezeSpaceKeys}; null for other keys): reference
     * units, {@link LaneLayout#FROZEN_COUNT} floats.
     */
    float[] frozen;

    /**
     * @param type            note type
     * @param line            judgement line
     * @param laneId          lane id (0 only for the inserted lane 0, CHART_FORMAT_v4 §3.6; ignored and
     *                        stored as 0 for universal and DRAG notes, {@link #laneless()}, in these older forms)
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
     * segments and the note's glitch segments (CHART_FORMAT_v4 §6.8–§6.9).
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
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, null);
    }

    /**
     * Same as the constructor with the rotation pivot, plus the note's distortion segments (CHART_FORMAT_v4 §6.11,
     * Kelvin 2026-10-23 16:57).
     *
     * @param distortions distortion segments (null = none; nulls are left out; kept sorted by {@code from}, stable)
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, 0f);
    }

    /**
     * Same as the constructor with distortions, plus how fast a TIDE's wave flows sideways (Kelvin 2026-10-28 00:33
     * 「加一个参数吧，就是可以让这些波动起来的那种」).
     *
     * @param waveSpeed waves per beat, positive to the right ({@link #waveSpeed()}; kept only on a TIDE or TIDE
     *                  STREAM, NaN or infinite → 0)
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, waveSpeed, 0f);
    }

    /**
     * Same as the constructor with a wave speed, plus a sideways shift of a lane note off its lane's centre (Kelvin
     * 2026-10-28 01:47 「能否修改一下游戏的底层架构来让这种旋转得以实现」: with it and a pivot, any shape of notes turns
     * about one point).
     *
     * @param offsetX reference units to the right on screen ({@link #offsetX()}; lane notes only, NaN or infinite → 0;
     *                a laneless key's x, {@link #x()})
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed, float offsetX) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, waveSpeed, offsetX, null);
    }

    /**
     * Same as the constructor with a sideways shift, plus events that move the shift over time (Kelvin 2026-10-28
     * 02:19: the snowflake slides right and left while it turns).
     *
     * @param offsetXEvents sideways shift by beat ({@link #offsetXAt}; lane notes only, null for none; a laneless
     *                      key's {@link #xEvents()})
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed, float offsetX, List<Events> offsetXEvents) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, waveSpeed, offsetX,
            offsetXEvents, null);
    }

    /**
     * Same as the constructor with sideways shift events, plus an orbit centre (Kelvin 2026-10-29 19:04: the key turns
     * about an absolute point of the screen, 公转, and moves with it).
     *
     * @param orbit the absolute centre the note turns about instead of its pivot ({@link #orbit()}; lane notes only,
     *              null for none)
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed, float offsetX, List<Events> offsetXEvents, NoteOrbit orbit) {
        this(type, line, laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, waveSpeed, offsetX,
            offsetXEvents, orbit, false);
    }

    /**
     * Same as the constructor with an orbit centre, plus the decoration flag (Kelvin 2026-11-02).
     *
     * @param fake a decoration that is only drawn ({@link #fake()}; tap, hold, mine and TIDE only, else ignored)
     */
    public Note(NoteType type, Line line, int laneId, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed, float offsetX, List<Events> offsetXEvents, NoteOrbit orbit, boolean fake) {
        this(type, line, (float) laneId, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, waveSpeed, offsetX,
            offsetXEvents, orbit, fake, type == NoteType.DRAG); // a DRAG of these older forms keeps its own x
    }

    /**
     * Same as the constructor with the decoration flag, but the lane may lie between two lanes, and a tap, hold or
     * mine may have no lane at all (Kelvin 2026-11-07 04:02: 「定轨状态支持小数轨道，然后加一个无轨时间和有轨时间」).
     *
     * @param lane a lane id, or a decimal between two lanes' ids ({@link #lanePos()}; lane notes placed by a lane only)
     * @param byX  a tap, hold, mine or DRAG placed by its own x ({@link #laneless()}): {@code offsetX} and
     *             {@code offsetXEvents} are then its x and x events and {@code lane} is ignored; false puts a DRAG in
     *             {@code lane} like a tap (Kelvin 2026-11-08 02:51 「也上轨道」)
     */
    public Note(NoteType type, Line line, float lane, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed, float offsetX, List<Events> offsetXEvents, NoteOrbit orbit, boolean fake,
                boolean byX) {
        this(type, line, lane, appearance, judgement, duration, height, movementEvents, rotationEvents,
            disappearEvents, heightEvents, trails, glitches, pivotX, pivotY, distortions, waveSpeed, offsetX,
            offsetXEvents, orbit, fake, byX, null);
    }

    /**
     * Same as the constructor above, with the key's 3D motion (Kelvin 2026-11-07 22:54; {@link #space()}).
     *
     * @param space its {@link Channels#SOLID} channels (null: none; kept for taps, holds, mines and DRAGs only)
     */
    public Note(NoteType type, Line line, float lane, float appearance, float judgement, float duration,
                float height, List<Events> movementEvents, List<Events> rotationEvents,
                List<Events> disappearEvents, List<Events> heightEvents, List<NoteTrail> trails,
                List<NoteGlitch> glitches, float pivotX, float pivotY, List<NoteDistortion> distortions,
                float waveSpeed, float offsetX, List<Events> offsetXEvents, NoteOrbit orbit, boolean fake,
                boolean byX, Channels space) {
        if (type == null || line == null) throw new IllegalArgumentException("note type and line are required");
        if (Float.isNaN(judgement) || Float.isInfinite(judgement)) {
            throw new IllegalArgumentException("note judgement must be finite: " + judgement);
        }
        this.type = type;
        this.line = line;
        this.byX = type.inLane() && byX;
        float pos = type.inLane() && !this.byX && !Float.isNaN(lane) && !Float.isInfinite(lane) ? lane : 0f;
        this.lanePos = pos == 0f ? 0f : pos; // folds −0 into +0
        this.laneId = (int) Math.floor(this.lanePos);
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
        this.lastVelocity = atJudgement != null ? endSpeed(atJudgement) : 0f;
        this.pinnedAngle = isBlockMine() ? Events.calculateValue(this.rotationEvents, this.judgement, 0f) : 0f;
        this.pivotX = finiteOrZero(pivotX);
        this.pivotY = finiteOrZero(pivotY);
        this.trails = visibleTrails(trails);
        this.trail = this.trails.isEmpty() ? NoteTrail.NONE : this.trails.get(0);
        this.glitches = sortedGlitches(glitches);
        this.distortions = sortedDistortions(distortions);
        this.waveSpeed = type.inLane() ? 0f : finiteOrZero(waveSpeed);
        this.offsetX = type.inLane() ? finiteOrZero(offsetX) : 0f;
        this.offsetXEvents = type.inLane() ? Events.sortedCopy(offsetXEvents) : Collections.<Events>emptyList();
        this.orbit = type.inLane() ? orbit : null;
        this.fake = fake && (type.inLane() || type == NoteType.UNIVERSAL);
        this.space = type.inLane() && space != null && space.keys() == Channels.SOLID ? space : null;
        // a radius orbit is in effect from its first event (centre, radius or angle) to its last, or always without one
        float of = Float.POSITIVE_INFINITY;
        float ot = Float.NEGATIVE_INFINITY;
        if (this.orbit != null && this.orbit.polar()) {
            for (List<Events> l : Arrays.asList(this.orbit.xEvents(), this.orbit.yEvents(), this.orbit.radiusEvents(),
                this.orbit.angleEvents())) {
                for (Events e : l) {
                    of = Math.min(of, e.startTime());
                    ot = Math.max(ot, e.endTime());
                }
            }
            if (of > ot) {
                of = Float.NEGATIVE_INFINITY;
                ot = Float.POSITIVE_INFINITY;
            }
        }
        this.orbitFrom = of;
        this.orbitTo = ot;
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

    private static List<NoteDistortion> sortedDistortions(List<NoteDistortion> in) {
        if (in == null || in.isEmpty()) return Collections.emptyList();
        List<NoteDistortion> out = new ArrayList<>(in.size());
        for (NoteDistortion d : in) {
            if (d != null) out.add(d);
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
    /**
     * @return lane id (0 for universal notes and {@link #laneless()} keys; for a key {@link #between()} lanes the id
     *         at or below its {@link #lanePos()})
     */
    public int laneId() { return laneId; }

    /**
     * @return the lane as written: a lane id, or a decimal between two lanes' ids, placed by
     *         {@link LaneLayout#keyCentre} (Kelvin 2026-11-07 04:02); 0 for universal notes and laneless keys
     */
    public float lanePos() { return lanePos; }
    /** @return authored appearance beat (informational; drawing uses {@link #visibleFrom()}). */
    public float appearance() { return appearance; }
    /** @return judgement beat. */
    public float judgement() { return judgement; }
    /** @return length in beats of a hold, a TIDE STREAM or a LANE MINE (0 for every other note). */
    public float duration() { return duration; }

    /**
     * @return true for a TIDE STREAM (潮汐): a universal note with a positive duration, held by
     *         a finger of its own on its line (ARCHITECTURE §19). A universal note without one is a TIDE drop.
     */
    public boolean isTideStream() { return type == NoteType.UNIVERSAL && duration > 0f; }

    /**
     * @return true for a LANE MINE (轨道水雷): a mine with a positive duration that blocks its lane
     *         on its line (ARCHITECTURE §20). A mine without one is a plain MINE.
     */
    public boolean isLaneMine() { return type == NoteType.MINE && duration > 0f; }

    /**
     * @return true for a TIDE MINE (浪花水雷) or TIDE STREAM MINE (潮汐水雷): a mine over its line's whole zone
     *         (Kelvin 2026-10-31)
     */
    public boolean isTideMine() { return type == NoteType.TIDE_MINE; }

    /**
     * @return true for a mine that blocks over {@code [T, end)} once it reaches the line: a LANE MINE (its lane) or a
     *         TIDE STREAM MINE (its line's whole zone)
     */
    public boolean isBlockMine() { return !type.judged() && duration > 0f; }

    /** @return true for a TIDE STREAM or a TIDE STREAM MINE: a band across the window follows its head. */
    public boolean hasTideBand() { return !type.inLane() && duration > 0f; }

    /**
     * @return true when the head sticks to the line after the judgement and a tail (hold ribbon,
     *         TIDE STREAM band, LANE MINE column) follows it: holds, TIDE STREAMs, LANE MINEs and TIDE STREAM MINEs
     */
    public boolean isSustained() { return type == NoteType.HOLD || hasTideBand() || isBlockMine(); }

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

    /**
     * @return the distortion segments sorted by {@code from} (unmodifiable; empty when the note has none;
     *         {@link NoteDistortion#strengthAt} with {@link NoteDistortion#NOTE_BASE} gives the strength). Visual
     *         only.
     */
    public List<NoteDistortion> distortions() { return distortions; }

    /**
     * @return how fast this TIDE's look flows sideways, in waves per beat (positive = to the right, 0 = still): the
     * ground wave slides and the sky pearl string moves two pearls a wave (Kelvin 2026-10-28 00:33)
     */
    public float waveSpeed() { return waveSpeed; }

    /**
     * @return how far this lane note is drawn right of its lane's centre, in reference units (stretched by kx, the
     * same on both lines; 0 for a TIDE). It moves the body before it turns about its pivot, which stays measured from
     * the shifted body. Visual only: the note is still judged in its lane (Kelvin 2026-10-28 01:47). A key with its
     * own x has no lane to be shifted off: for it this is its {@link #x()}.
     */
    public float offsetX() { return offsetX; }

    /** @return the events that move {@link #offsetX} over time, sorted (empty for none; a laneless key's x events). */
    public List<Events> offsetXEvents() { return offsetXEvents; }

    /**
     * @return the sideways shift at {@code beat}. A note without {@code offsetXEvents} keeps {@link #offsetX} all the
     * time; with events it takes the value of the event it is in, and 0 (its lane's centre) outside them, so a key
     * that slides as part of a show falls in its own lane afterwards (Kelvin 2026-10-29 19:04); 0 for a laneless key,
     * which is placed by {@link #xAt}
     */
    public float offsetXAt(float beat) {
        if (laneless()) return 0f;
        return offsetXEvents.isEmpty() ? offsetX : Events.calculateValue(offsetXEvents, beat, 0f);
    }

    /**
     * @return true for a tap, hold, mine or DRAG written with an x instead of a lane (a key on a sea, Kelvin
     *         2026-11-08 02:48 「只有海面上可以无轨」; older charts' laneless time and DRAGs): judged and drawn at its
     *         own {@link #xAt x}, as in the laneless game; its {@link #laneId()} is 0 and its {@link #laneIndex()} −1.
     *         A DRAG with a lane (02:51 「也上轨道」) is in it like a tap.
     */
    public boolean laneless() { return byX; }

    /**
     * @return true for a key whose {@link #lanePos()} is no lane of the chart (a decimal such as 1.5, or lane 0 when
     *         the chart has none): placed and judged at that spot between the lanes, its {@link #laneIndex()} −1
     */
    public boolean between() { return between; }

    /**
     * @return true for a key placed and judged by position, not a lane ({@link #laneless()}, {@link #between()}, or a
     *         {@link #spacePath()} key: judged where it is at its beat)
     */
    public boolean free() { return byX || between || spacePath(); }

    /**
     * @return a laneless key's x: reference units right of the screen's centre line (the default lanes' centres are
     *         −840, −600, …, 840), scaled and moved with the lines' lane scale and camera frame as the lanes are, but
     *         by no lane ({@link LaneLayout#freeX}); 0 for other notes
     */
    public float x() { return laneless() ? offsetX : 0f; }

    /** @return the events that move a laneless key's {@link #x()}, sorted (empty for none, and for other notes). */
    public List<Events> xEvents() { return laneless() ? offsetXEvents : Collections.<Events>emptyList(); }

    /** @return a laneless key's x at {@code beat}: the value of the event it is in, else {@link #x()}; 0 for others */
    public float xAt(float beat) {
        if (!laneless()) return 0f;
        return offsetXEvents.isEmpty() ? offsetX : Events.calculateValue(offsetXEvents, beat, offsetX);
    }

    /**
     * @return the absolute centre this lane note turns about, 公转 (Kelvin 2026-10-29 19:04), or null: then it turns
     * about its pivot ({@link #pivotX()}, {@link #pivotY()}), 自转. With an orbit the pivot is not used.
     */
    public NoteOrbit orbit() { return orbit; }

    /**
     * @return true for a decoration (假键, Kelvin 2026-11-02: snowflakes made of keys, then TIDE shows): drawn like any
     *         tap, hold, mine, TIDE or TIDE STREAM until its end beat and then gone, but never judged, counted, scored,
     *         hit or heard, with no sync hint and no lane glow; real notes on its beat do not count it as sharing their
     *         beat, and a decorative TIDE joins no TIDE group (each is drawn on its own)
     */
    public boolean fake() { return fake; }

    /**
     * @return true when an orbit with a radius and angle places the note at {@code beat} (Kelvin 2026-10-29 20:55):
     *         from the start of the first of its centre, radius and angle events to the end of the last (always when
     *         it has none); outside that the note is in its lane and turns about its own centre
     */
    public boolean inOrbitSpan(float beat) {
        return orbit != null && orbit.polar() && beat >= orbitFrom && beat <= orbitTo;
    }

    /** @return the first beat a radius orbit is in effect ({@link #inOrbitSpan}); +∞ without one, −∞ for always. */
    public float orbitFrom() { return orbitFrom; }

    /** @return the last beat a radius orbit is in effect ({@link #inOrbitSpan}). */
    public float orbitTo() { return orbitTo; }

    // ------------------------------------------------------------------ derived (set by Chart)

    /** @return position in {@link Chart#notes()}. */
    public int index() { return index; }

    /**
     * @return index of the note's lane in {@link Chart#lanes()} / {@link LaneLayout}, or −1 for
     *         universal notes and keys placed by position ({@link #free()})
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
     * @return speed (ref units per beat) at its end of the movement event in effect at the judgement (0 if none or
     *         zero length): how fast a missed tap leaves the line once that event is over
     */
    public float lastVelocity() { return lastVelocity; }

    /**
     * @return distance from the judgement line at {@code beat} (see the class comment); exactly 0
     *         for a LANE MINE at or after its judgement beat
     */
    public float positionAt(float beat) {
        if (beat >= judgement && isBlockMine()) return 0f;
        float base = isSustained() && beat > judgement ? 0f : HIDDEN_POSITION;
        return Events.calculateValue(motion, beat, base);
    }

    /**
     * @return the position past the judgement beat (Kelvin 2026-10-27: a missed note keeps going, like inertia): the
     *         movement events while one still covers {@code beat}; after the last one ends, its final value moved on
     *         at the speed it ended with ({@link #continued}); {@link #HIDDEN_POSITION} when no movement reaches the
     *         judgement
     */
    public float positionPastJudgement(float beat) {
        if (positionAtJudgement == HIDDEN_POSITION) return HIDDEN_POSITION; // judged unseen: stays off screen
        return continued(motion, beat, HIDDEN_POSITION);
    }

    /**
     * A channel past the judgement: the last event started by {@code beat} while it covers it; once it is over, its
     * final value plus its end speed times the beats since its end, when it was still running at the judgement
     * (else {@code base}, as before the judgement).
     */
    private float continued(List<Events> events, float beat, float base) {
        Events e = lastStarted(events, beat);
        if (e == null) return base;
        if (beat <= e.endTime()) return e.valueAt(beat);
        if (e.endTime() < judgement - APPROACH_PROBE_BEATS) return base;
        // one ending a float rounding before the judgement moves on from the line at the judgement
        return e.finalValue() + endSpeed(e) * (beat - Math.max(e.endTime(), judgement));
    }

    /** @return the last event (sorted by start) starting at or before {@code beat}, or null; no allocation */
    private static Events lastStarted(List<Events> events, float beat) {
        int lo = 0;
        int hi = events.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (events.get(mid).startTime() <= beat) lo = mid + 1;
            else hi = mid;
        }
        return lo > 0 ? events.get(lo - 1) : null;
    }

    /** @return the eased event's rate of change at its end, per beat: (final − initial) · end slope / length */
    private static float endSpeed(Events e) {
        float dt = e.endTime() - e.startTime();
        if (!(dt > 0f)) return 0f;
        float v = (e.finalValue() - e.initialValue()) * e.endSlope() / dt;
        return Float.isFinite(v) ? v : 0f;
    }

    /**
     * @return rotation in degrees at {@code beat}; a LANE MINE keeps {@code angleAt(judgement)}
     *         from its judgement beat on; past the judgement a tap, mine or TIDE keeps turning ({@link #continued})
     */
    public float angleAt(float beat) {
        if (beat >= judgement && isBlockMine()) return pinnedAngle;
        if (beat > judgement && !isSustained()) return continued(rotationEvents, beat, 0f); // keeps turning
        return Events.calculateValue(rotationEvents, beat, 0f);
    }

    /** @return the index of the sea it lands on in {@link Chart#seas()} ({@code "on"}), or −1 for its line */
    public int seaIndex() { return seaIndex; }

    /**
     * @return true when its path follows space (Kelvin 2026-11-08 23:20 「按键的轨迹可以选择跟所在海面/判定线绑定或者跟着
     *         绝对空间坐标绑定」, {@code "track": "space"}): its path's points ({@code positionEvents}, its x and z) are
     *         where it is in space (as a lane's origin: from the centre line, the frame's middle, the columns' middle),
     *         moved by nothing but the view; without a path it comes in on its lane as the lane is at its beat. False
     *         (the default, every chart before): it moves with its lane or sea
     */
    public boolean spaceTrack() { return spaceTrack; }

    /**
     * @return true for a {@link #spaceTrack()} key with a path of its own ({@code positionEvents}): its position is y
     *         up from the frame's middle (both lines), its x ({@code offsetX}, or a laneless key's x) from the centre
     *         line, its {@link Channels#Z} from the columns' middle. Without a path it falls on its lane as the lane
     *         is at its judgement beat ({@link LaneLayout#freezeSpaceKeys})
     */
    public boolean spacePath() { return spaceTrack && !movementEvents.isEmpty(); }

    /**
     * @return how many lanes and seas a TIDE or TIDE STREAM lists to span ({@code "spans"}, Kelvin 2026-11-09 00:01
     *         「然后海浪可以选择跨过若干个轨道/海面，这些轨道/海面之间用虚线链接」): drawn as one piece over each, in this order,
     *         dashes between each two; judged as one key as before. 0: over the lanes its range crosses
     */
    public int spanCount() { return spans != null ? spans.length / 2 : 0; }

    /** @return true when span k ({@link #spanCount()}) is a sea, false when it is a lane */
    public boolean spanSea(int k) { return spans[2 * k] == 1; }

    /** @return span k's lane id or sea index ({@link Chart#seas()}) */
    public int spanValue(int k) { return spans[2 * k + 1]; }

    /**
     * @return the key's 3D motion (Kelvin 2026-11-07 22:54, CHART_FORMAT §5.1): its {@link Channels#SOLID} channels,
     *         or null for none (drawing only: it is judged as before)
     */
    public Channels space() { return space; }

    /**
     * @return the sea it lands on (Kelvin 2026-11-07 16:04 「浪花判定面上也可以落下普通浪花」): drawn landing on the sea's
     *         surface under it instead of its line and leaning with it ({@link #angleAt}); judged as before: a key
     *         by its own x like any laneless key (a band its width wide, any height), a TIDE as a TIDE
     */
    public Sea sea() { return sea; }

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

    /**
     * @return note opacity at {@code beat} (authored {@code DisappearEvents}, base 1); a chart format v7 key's is its
     *         {@code o} times its lane's (Kelvin 2026-11-09 21:37 「加回透明度」), each clamped to 0..1 (a wave or tide:
     *         the most of its lanes')
     */
    public float opacityAt(float beat) {
        if (opacity7 == null) return Events.calculateValue(disappearEvents, beat, 1f);
        float o = Lane.clamp01(opacity7.at(beat));
        if (o > 0f && laneOpacity7 != null) {
            float l = 0f;
            for (Fn f : laneOpacity7) l = Math.max(l, f != null ? Lane.clamp01(f.at(beat)) : 1f);
            o *= l;
        }
        return o;
    }

    /**
     * Gives a chart format v7 key its opacity {@code o} and its lanes' ({@link #opacityAt}; kept, not copied; no
     * lanes = the key's alone).
     */
    void setOpacity7(Fn key, Fn[] lanes) {
        opacity7 = key;
        laneOpacity7 = lanes != null && lanes.length > 0 ? lanes : null;
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
        return type.json + "@" + judgement + " " + line.json
            + (laneless() ? " x " + offsetX : type.inLane() ? " lane " + (between ? lanePos : laneId) : "")
            + (duration > 0f ? " dur " + duration : "")
            + (heightEvents.isEmpty() ? "" : " heights " + heightEvents.size())
            + (trails.isEmpty() ? "" : " " + (trails.size() == 1 ? trail.toString() : trails.size() + " trails"))
            + (glitches.isEmpty() ? "" : " " + glitches.size() + " glitch(es)")
            + (distortions.isEmpty() ? "" : " " + distortions.size() + " distortion(s)")
            + (hasPivot() ? " pivot (" + pivotX + ", " + pivotY + ")" : "")
            + (orbit != null ? " " + orbit : "") + (fake ? " fake" : "");
    }
}
