package org.kelvinizer.gameplay;

import org.kelvinizer.Params;
import org.kelvinizer.audio.SongClock;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Floating;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.play.PlayResult;
import org.kelvinizer.play.ScoreKeeper;

import java.util.Arrays;

/**
 * 一局游戏的模型：歌曲时间、输入、判定和计分；纯 Java、不碰 GL，加载后不再分配内存。水雷、轨道水雷和浪花的联合判定也在这里。
 *
 * <p>The gameplay model (ARCHITECTURE §5, §6, §10, §13, §20): song time, input, judging and scoring for
 * one run of a {@link Chart}. Pure Java with no GL, so it runs headless in tests with a fake
 * {@link SongClock.TimeSource}. Touch only, and laneless (Kelvin 2026-11-05: 「八轨改无轨」): every tap, hold and
 * mine is judged where it is across the screen, its own x and width ({@link LaneLayout#noteX},
 * {@link LaneLayout#noteWidth}; older charts' lanes are turned into those at load). Two notes are "in the same
 * place" (the old "same lane") when their ranges {@code x ± width / 2} overlap at the later one's beat.
 *
 * <h2>Time</h2>
 * One {@link SongClock} (audio-anchored) drives everything. With the user offsets
 * {@code A = audioOffsetMs} and {@code V = visualOffsetMs}:
 * <ul>
 *   <li>judge time of an input event = {@code clock.timeAtNanos(eventNanos) − A} (the event's own
 *       timestamp, never the frame time);</li>
 *   <li>frame judge time = {@code clock.nowMs() − A} (LOST sweep, holds, mines, autoplay);</li>
 *   <li>visual time = judge time + V: positive V draws notes earlier (compensates a display that
 *       shows frames late). Line y and note x for drawing and for hit-testing are evaluated
 *       at the visual time of the frame or event, i.e. where the player saw them, and stretched to
 *       the screen by {@link Field} (line y with {@code ky}, x with {@code kx}).</li>
 * </ul>
 *
 * <h2>Judging (one tap judges one target, a TIDE group counts as one; plus its sync partner where the zones
 * overlap)</h2>
 * <ul>
 *   <li>A touch is in a line's zone when {@code |y − lineY| ≤ 480·ky} ({@link Field#zoneHalf}: half the screen per
 *       line, 半垂判, Kelvin 2026-11-06 22:23 「改回半垂判」; ±160 from 7.184 to 7.188), or as near
 *       to a pending TIDE of that line that lands away from it, in its window: a TIDE can be the line (Kelvin
 *       2026-10-16; the tutorial's lesson L12 until 2026-11-06; {@link #zoneDist}). Outside both zones it judges
 *       nothing. Where the zones overlap, a touch that judges a note also judges the other line's pending note in the
 *       same place with exactly the same judgement beat, and that line's TIDE group at that beat
 *       ({@code judgeOtherLine}).</li>
 *   <li>Candidates, per zone the touch is in: the earliest unjudged tap/hold head with
 *       {@code |dt| ≤ TIER2} whose range {@code x ± width / 2} holds the touch (the judging width,
 *       {@link LaneLayout#judgeWidth}: the key's own, or one lane where the chart has fixed lanes, 定轨, and the lane
 *       is wider); if there is none, the earliest
 *       one whose range reaches within {@link #NEIGHBOUR_TOLERANCE} of its width of the touch (neighbour); plus
 *       the earliest unjudged TIDE note of that line. The earliest judgement time wins; on a tie the
 *       smaller |dt|, then lane notes before TIDE, under the finger before neighbour, the closer line, and
 *       finally the lower line (only when both lines coincide and everything else is equal).</li>
 *   <li>Taps / holds stacked on one line, place and beat (allowed since Kelvin 2026-11-02: 「现在我们要放开这个限制」)
 *       are separate notes: a press judges one (the earliest pending, file order on a tie), so a stack of n
 *       takes n fingers; autoplay judges each.</li>
 *   <li><b>Joint judging</b> (ARCHITECTURE §20, replacing the old "exact tie → the lane note, a
 *       second finger takes the TIDE" rule): when a tap judges a lane note (DROP or STREAM head, under the
 *       finger or neighbour) on line L at beat b, every unjudged TIDE note of L
 *       whose beat is exactly b is judged with it, with the same grade and Δt (each counts as its
 *       own note; its TIDE STREAMs then run on coverage). A tap that judges no lane note falls back
 *       to the TIDE notes alone, and the lane notes of that beat stay pending.</li>
 *   <li>A TIDE group (same line, identical beat) is judged entirely by the tap that takes it, TIDE
 *       drops and TIDE STREAMs alike; each TIDE STREAM of the group then runs on its own.</li>
 *   <li>Taps earlier than −TIER2 consume nothing; notes later than +TIER2 are swept LOST once per
 *       frame (with {@link #SWEEP_SLACK_MS} so events stamped just before the frame still count).</li>
 *   <li><b>Lane STREAMs belong to one finger (no switching hands, ARCHITECTURE §19).</b> The head
 *       is judged like a tap; the pointer that hit it owns the hold for its whole length
 *       and is then tracked. No other pointer ever takes it over: a second finger touching
 *       it only judges or misses other notes, and when the owner lifts
 *       before {@code end − HOLD_RELEASE_GRACE_MS}, or stays off its zone and reach ({@link #HOLD_REACH} widths
 *       from its centre, ±1 lane of the old game) for more than {@link #HOLD_LEAVE_MS}, the hold breaks (LOST)
 *       even if another finger rests on it. Otherwise it is counted with the head grade when it completes. Leaving at or after
 *       {@code end − HOLD_RELEASE_GRACE_MS} is treated like a release there (completes). A finger
 *       that left and is lifted before it comes back counts as released when it left, so lifting
 *       and staying down are judged the same. A pointer id reused after a lost touch-up releases
 *       the old hold first, so it never inherits it. The only exception is the pause: a pause never
 *       breaks a hold; a pointer lifted while paused detaches it, and any free pointer on it
 *       within 100 ms after the resume re-grabs it (resuming needs a lifted finger).</li>
 *   <li><b>A gesture the system takes</b> ({@link #gestureCancelled}, Kelvin 2026-11-06: players reported dropped
 *       touches): Android cancels every finger on the screen and never says when they lift, so all of them are let
 *       go at once without judging anything, and every hold running then (STREAM, floating or not, TIDE STREAM) is held
 *       by the engine to its end. New fingers play as usual.</li>
 *   <li><b>TIDE STREAMs are held by coverage, one finger each, not by ownership.</b> The head is
 *       judged like a TIDE drop (any tap in the line's zone at any x). A line's fingers are its down pointers
 *       inside its zone ({@code |y − lineY| ≤ 480·ky}, {@link Field#zoneHalf}; any x, pointers holding lane
 *       STREAMs included). Each running TIDE STREAM needs one of
 *       them (Kelvin 2026-10-23: two on one line need two fingers the whole time), and which finger
 *       is which does not matter, so swapping hands is free. When a line has fewer fingers than
 *       running TIDE STREAMs, the ones in their last {@code HOLD_RELEASE_GRACE_MS} go without first
 *       (that completes them), then the latest heads (then the highest index). Coverage is
 *       evaluated at every input event with its timestamp (down, drag, up, cancel)
 *       and once per frame (resting pointers, moving lines). A lapse longer than
 *       {@link #HOLD_LEAVE_MS} that starts before
 *       {@code end − HOLD_RELEASE_GRACE_MS} breaks it (LOST at the lapse start + 100 ms, also when
 *       the next observation finds it covered again); a lapse that starts at or after that point
 *       completes it at once; reaching the end completes it with the head grade. A pause never
 *       breaks it; after the resume it has {@link #HOLD_LEAVE_MS} to be covered again. It is one note
 *       for score, combo and totals, like a hold. Autoplay completes it.</li>
 *   <li>Mines: a MINE is touched only by a press (Kelvin 2026-10-31: 「点击才检查」): a touch-down in the mine
 *       line's zone inside the central {@link #MINE_BOX} (70 %) of the mine's judging width (its own, or one lane
 *       in 定轨, {@link LaneLayout#judgeWidth}), within
 *       ±{@link #MINE_WINDOW_MS} of the mine (the ECHO window, ±133.33 ms; was ±66.67 ms) and outside the windows
 *       cut from it (below). A finger already down, a drag into the mine and a release never touch it, so a
 *       finger resting through a field of mines touches none of them. Each mine triggers at most once
 *       ({@link ScoreKeeper#mineTouched()}: combo 0, 10,000 off the score) and emits one
 *       {@link GameEvent#MINE}. Autoplay never touches mines.</li>
 *   <li><b>LANE MINEs</b> (ARCHITECTURE §20) block their lane on their line over
 *       {@code [T − LANE_MINE_LEAD_MS, end)} = {@code [T, end)}: only once they reach the line
 *       (Kelvin 2026-10-13; was {@code [T − 100 ms, end)}). Contact = a down pointer in that line's zone inside
 *       the central {@link #LANE_MINE_BOX} (80 %) of the mine's current judging width.
 *       Each contact triggers once: a touch-down in the box, a drag entering it, or a pointer already
 *       resting in it when the block starts; a pointer that stays in the box triggers again only after leaving it and coming back. The
 *       first contact is {@link ScoreKeeper#mineTouched()}, later ones
 *       {@link ScoreKeeper#laneMineContact()} (combo 0 again; the MINES row counts the lane mine
 *       once). Every contact emits one {@link GameEvent#MINE} with {@link GameEvent#FLAG_LANE_MINE}.
 *       A LANE MINE never judges or consumes a tap; autoplay never touches it.</li>
 *   <li><b>TIDE MINEs</b> (Kelvin 2026-10-31: the mine forms of the TIDE): a TIDE MINE is a MINE whose touch area is
 *       its line's whole zone (any x) and whose window every tap, STREAM head
 *       and TIDE of its line cuts; a TIDE STREAM MINE is a LANE MINE over that whole zone
 *       that every note of its line meets gives way to, with one RESONANCE guard per line.</li>
 *   <li><b>Mines and taps</b> (Kelvin 2026-10-31): where a MINE's window {@code M = [T_m − MINE_WINDOW_MS,
 *       T_m + MINE_WINDOW_MS]} meets the ±TIER2 window of a tap or STREAM head in its place and line, the
 *       two are cut apart: the mine's window loses the note's RESONANCE window {@code R = [T − TIER1, T + TIER1]},
 *       R stays whole, and the note's ECHO window loses M. One press there either touches the mine or judges the
 *       note, never both; the note is LOST at the end of what is left of its window. TIDE notes are not affected
 *       (they can be hit anywhere on the line).</li>
 *   <li><b>LANE MINE priority</b> (Kelvin 2026-10-14): a tap or STREAM head whose RESONANCE window meets the
 *       block {@code [T, end)} of a LANE MINE in its place and line is judged first: the LANE MINE sleeps over
 *       that window (no contact of any finger there; one still in place when it wakes at
 *       {@code T + TIER1} is a contact) and the note has no ECHO (only RESONANCE inside ±TIER1, LOST at
 *       {@code T + TIER1}). A RESONANCE on such a note at {@code t} also keeps every LANE MINE in its place and
 *       line asleep until {@code t + RESONANCE_GUARD_MS} (150 ms, Kelvin 2026-10-30); a finger still in
 *       place when that ends is a contact. A LOST gives no guard.</li>
 *   <li><b>DRAGs</b> (Kelvin 2026-11-05: 「加 Drag，手指在对应判定线就判」, 「手指提前放着或者滑过去都算，到点时手指在那儿就是共鸣，
 *       不在就是迷失，没有回响」): never tapped and never taking a tap. A finger catches a DRAG when it is down (not
 *       pressed inside a veil) in its line's zone within {@link #DRAG_REACH} of its widths of its centre (as far as a
 *       tap reaches a neighbour) at any moment from {@code T − TIER2} to the end of its window (+TIER2, +TIER1 when a
 *       LANE MINE gives way to it): every input event and every frame looks. Caught before T (a finger resting there
 *       early, or passing through) it is a RESONANCE at T; caught at or after T it is a RESONANCE at once; never
 *       caught, it is LOST when its window ends. It has no timing of its own: Δt 0, counted PERFECT, outside the
 *       early / late statistics ({@link ScoreKeeper#recordDrag()}). Any finger catches it, also one holding a STREAM
 *       or tapping another note, and catching consumes nothing. A catch before T emits {@link GameEvent#CATCH} (for
 *       a timed hit sound); the judgement emits a {@link GameEvent#HIT} with {@link GameEvent#FLAG_DRAG}. A LANE MINE in
 *       its place gives way to it as to a tap; plain MINEs and DRAGs do not cut each other (a DRAG needs no press).</li>
 *   <li><b>Floating notes</b> (悬浮键, CHART_FORMAT §11; Kelvin 2026-11-06 14:44–15:51, the approved mock-up v3):
 *       taps, STREAMs and DRAGs that float where the chart puts them are judged only inside their circle as drawn at
 *       the press ({@link FloatSpace}), in a zone or not, with the windows of the notes on the lines. A press judges
 *       one note: the earliest of its line candidates and of the pending floating taps / STREAM heads whose circle and
 *       window hold it; on a tie of time the note on a line, then the floating note whose centre is nearest (in its
 *       radii). A floating note is never judged together with another note (no TIDE group, no other line). A floating
 *       STREAM belongs to the finger that hit its head, which may drift to {@link #FLOAT_HOLD_REACH} of its radius; a
 *       floating DRAG is caught by any finger inside its circle (not one pressed inside a veil).</li>
 *   <li><b>Floating mines</b> (Kelvin 2026-11-06 15:01: 「也可以带上红色的水雷和轨道水雷，如果两个东西判定的时候重合了就按照之前一样的规矩。
 *       如果说一个tap和水雷不完全重合，则击打tap不在水雷范围内的区域依旧保留echo判定」): a floating MINE or LONG MINE is
 *       touched inside its circle, otherwise as the mines on the lines. A floating mine and a note, or a mine and a
 *       floating note, whose windows meet are cut apart as above only where the press or finger is over both: the
 *       mine sleeps there in the note's RESONANCE window, the note has no ECHO there (inside a MINE's window; under a
 *       LONG MINE none) and a RESONANCE there guards the LONG MINE. Elsewhere the note keeps its ECHO, so its window
 *       is never cut short.</li>
 *   <li>Autoplay judges every note RESONANCE with dt 0 at its exact time (deterministic).</li>
 * </ul>
 *
 * <h2>Per-frame use</h2>
 * {@link #load} (while covered) → {@link #start} (cover open) → every frame {@link #update} then
 * {@link #pollEvent} until it returns false; input handlers call {@link #touchDown} etc. with
 * world coordinates and {@code Gdx.input.getCurrentEventTime()}. {@link #isFinished()} becomes
 * true {@code FINISH_DELAY_MS} (or {@link #setFinishDelayMs}) after both the last note and the music have
 * ended (the music plays to its end, Kelvin 2026-10-16; {@link #endMs()}, {@link #finishAtMs()}); then build the
 * result with
 * {@link #fillResult}.
 *
 * <p>Per-line cursors keep every input and frame O(notes near now); nothing allocates
 * after {@link #load}.
 */
public final class GameEngine {
    /** Half height of a judgement zone in reference units; in the world it is {@link Field#zoneHalf} (480·ky). */
    public static final float ZONE_HALF = Params.JUDGE_ZONE_HALF;
    /** RESONANCE window half width (ms). */
    public static final float TIER1_MS = Params.TIER1_MS;
    /** ECHO window half width (ms); beyond it a note is not judgeable. */
    public static final float TIER2_MS = Params.TIER2_MS;
    /** Neighbour tolerance: how far past a note's edges a touch still takes it, as a fraction of its width. */
    public static final float NEIGHBOUR_TOLERANCE = 0.30f;
    /** How long before its time a TIDE's landing place is read ({@link #landing}, beats). */
    static final float LAND_PROBE_BEATS = 1f / 1024f;
    /** Width of a mine's touch box as a fraction of its width (centred). */
    public static final float MINE_BOX = 0.70f;
    /**
     * Mine touch window half width (ms; {@link Params#MINE_WINDOW_MS}, ≈ 133.33): a press touches a MINE over
     * {@code [T − MINE_WINDOW_MS, T + MINE_WINDOW_MS]}, less the RESONANCE windows of the notes in its lane.
     */
    public static final float MINE_WINDOW_MS = Params.MINE_WINDOW_MS;
    /**
     * How long before its judgement a LANE MINE starts blocking (ms; {@link Params#LANE_MINE_LEAD_MS},
     * 0): it blocks over {@code [T − LANE_MINE_LEAD_MS, end)}, i.e. once it reaches the line.
     */
    public static final float LANE_MINE_LEAD_MS = Params.LANE_MINE_LEAD_MS;
    /**
     * RESONANCE guard (ms; {@link Params#RESONANCE_GUARD_MS}, 150): after a RESONANCE at {@code t} on a note a
     * LANE MINE gives way to, that lane's LANE MINEs sleep over {@code [t, t + RESONANCE_GUARD_MS]}.
     */
    public static final float RESONANCE_GUARD_MS = Params.RESONANCE_GUARD_MS;
    /**
     * Width of a LANE MINE's contact box as a fraction of its current width (centred; the whole width
     * is drawn red, the 10 % margins keep brushes from a note beside it fair).
     */
    public static final float LANE_MINE_BOX = 0.80f;
    /** How far a hold's finger may be from its centre, in its widths (±1 lane of the old game). */
    public static final float HOLD_REACH = 1.5f;
    /** How far a finger catching a DRAG may be from its centre, in its widths: as far as a tap reaches a neighbour. */
    public static final float DRAG_REACH = 0.5f + NEIGHBOUR_TOLERANCE;
    /** How far a floating STREAM's finger may drift from its centre, in its radii (the mock-up's 1.3). */
    public static final float FLOAT_HOLD_REACH = 1.3f;
    /** A hold breaks when its pointer stays off its reach / zone longer than this (ms). */
    public static final float HOLD_LEAVE_MS = 100f;
    /** Releasing a hold this close to its end (ms) still completes it. */
    public static final float HOLD_RELEASE_GRACE_MS = Params.HOLD_RELEASE_GRACE_MS;
    /**
     * The LOST sweep and the mine pass run this many ms behind the frame time, so an input event
     * stamped just before the frame (processed in the next frame) is still judged.
     */
    public static final float SWEEP_SLACK_MS = 20f;
    /**
     * The clock starts this many ms before the first visible note (DESIGN §5.11), so the first frame
     * of the game shows nothing of it. The countdown and the quiet buffer before it run on wall time
     * in the screen ({@code GameplayScreen}), not on the song clock.
     */
    public static final float START_EPS_MS = 1f;
    /** Pointers tracked (libGDX reports up to 20). */
    public static final int MAX_POINTERS = 20;
    /** Event ring capacity (the oldest events are dropped when the consumer falls behind). */
    public static final int EVENT_CAPACITY = 256;

    /** Engine phase. */
    public enum Phase {
        /** No chart loaded. */
        IDLE,
        /** Loaded, clock prepared at its start time, not running. */
        READY,
        /** Time runs; input is judged. */
        RUNNING,
        /** Time frozen; input only tracked. */
        PAUSED,
        /** Past {@link #finishAtMs()}: every note resolved and the music over. */
        FINISHED
    }

    /** Note state: not judged yet. */
    public static final byte STATE_PENDING = 0;
    /** Tap or TIDE note hit. */
    public static final byte STATE_HIT = 1;
    /** Hold (or TIDE STREAM) head hit, running. */
    public static final byte STATE_HOLDING = 2;
    /** Hold (or TIDE STREAM) completed. */
    public static final byte STATE_COMPLETED = 3;
    /** Missed, or hold broken. */
    public static final byte STATE_LOST = 4;
    /** Mine touched. */
    public static final byte STATE_MINE_TOUCHED = 5;
    /** Mine passed untouched. */
    public static final byte STATE_MINE_PASSED = 6;

    /** Input result: not judged (not running, autoplay, bad pointer). */
    public static final int INPUT_IGNORED = 0;
    /** Input result: touch outside both zones that did nothing (free for HUD controls). */
    public static final int INPUT_STRAY = 1;
    /**
     * Input result: inside a zone but nothing to judge (a press that only caught or judged a DRAG is one too:
     * {@link #lastInputCaught()}; the DRAG's own events carry its hit), or outside both zones a press that only caught
     * a floating DRAG.
     */
    public static final int INPUT_EMPTY = 2;
    /** Input result: a note (or TIDE group) was judged; see {@link #lastInputJudgement()}. */
    public static final int INPUT_JUDGED = 3;
    /** Input result: a hold detached by a pause was re-grabbed. */
    public static final int INPUT_REGRAB = 4;
    /**
     * Input result: the touch went down inside a veil (ARCHITECTURE §22); the pointer judges nothing until it
     * lifts ({@link #pointerVeiled}).
     */
    public static final int INPUT_VEILED = 5;

    private static final int OWNER_NONE = -1;
    private static final int OWNER_AUTO = -2;
    private static final int OWNER_DETACHED = -3;
    /** A running TIDE STREAM: held by coverage (one finger in its line's zone each). */
    private static final int OWNER_COVER = -4;
    /** A hold running when the system took the gesture ({@link #gestureCancelled}): held to its end by the engine. */
    private static final int OWNER_KEPT = -5;

    private static final int KIND_OWN = 0;
    private static final int KIND_NEIGHBOUR = 1;
    private static final int KIND_UNIVERSAL = 2;
    /** A floating note: after every line candidate of the same time, then the nearest centre. */
    private static final int KIND_FLOAT = 3;

    private static final int PTR_ENGINE = -2;

    private final SongClock clock = new SongClock();
    private final ScoreKeeper keeper = new ScoreKeeper();
    private final LaneLayout frameLayout = new LaneLayout();
    private final LaneLayout inputLayout = new LaneLayout();

    private Chart chart;
    private Note[] notes = new Note[0];
    private boolean autoplay;
    private Phase phase = Phase.IDLE;
    private float audioOffsetMs;
    private float visualOffsetMs;
    private float leadInMs;
    private float finishDelayMs = Params.FINISH_DELAY_MS;
    private double lastEndMs;

    // Frame time and geometry (visual time of the frame).
    private double frameJudgeMs;
    private double frameVisualMs;
    private float frameBeat;
    private float frameLowerY = JudgementLine.DEFAULT_LOWER;
    private float frameUpperY = JudgementLine.DEFAULT_UPPER;
    // Geometry at the current input event's visual time.
    private float evLowerY;
    private float evUpperY;

    // Per-note runtime state (indexed by Note.index()).
    private byte[] state = new byte[0];
    private byte[] grade = new byte[0];
    private float[] dt = new float[0];
    private float[] resolvedMs = new float[0];
    private int[] holdOwner = new int[0];
    private float[] holdOffSince = new float[0];

    // Streams and cursors: per line its taps / holds ({@link Chart#lineNotes}) and its TIDE notes.
    private final Note[][] lineStream = new Note[Line.COUNT][];
    private final int[] lineCursor = new int[Line.COUNT];
    private final Note[][] uniStream = new Note[Line.COUNT][];
    private final int[] uniCursor = new int[Line.COUNT];
    private final Note[][] dragStream = new Note[Line.COUNT][];
    private final int[] dragCursor = new int[Line.COUNT];
    /** Per note: when a finger caught this DRAG before its time (judge ms), NaN while it is not caught. */
    private float[] caughtMs = new float[0];
    // Floating notes, judged inside their circles: taps / holds ({@link Chart#floatNotes}) and DRAGs.
    private Note[] floatStream = new Note[0];
    private int floatCursor;
    private Note[] floatDragStream = new Note[0];
    private int floatDragCursor;
    /** Plain mines (no LANE MINEs) in chart order; valid up to {@link #mineCount}. */
    private Note[] mines = new Note[0];
    private int mineCount;
    private int mineLo;
    /** LANE MINEs in chart order; valid up to {@link #laneMineCount}. */
    private Note[] laneMineArr = new Note[0];
    private int laneMineCount;
    private int laneMineLo;
    // Per LANE MINE (indexed by Note.index()): pointers counted inside its box (bit mask), the number of
    // contacts and the time of the last one.
    private int[] minePtrMask = new int[0];
    private int[] mineContacts = new int[0];
    private float[] mineContactMs = new float[0];
    // Mines and taps, LANE MINE priority (see the class doc). Per note: how long after its time it can still be
    // judged (TIER2, less where MINE windows cut its late ECHO, TIER1 when a LANE MINE gives way to it), whether it
    // has no ECHO (that), and the MINEs that cut its ECHO,
    // cutMine[cutFrom .. + cutLen). Per mine: the notes whose RESONANCE windows it loses or gives way to,
    // shadowNote[shadowFrom .. + shadowLen).
    private float[] lateWin = new float[0];
    private boolean[] noEcho = new boolean[0];
    /**
     * Per TIDE (indexed by Note.index()): where it lands, its distance from its line just before its time (reference
     * units, 0 = on the line). A TIDE that lands away from its line is a judgement line too ({@link #zoneDist}).
     */
    private float[] tideLand = new float[0];
    private int[] cutFrom = new int[0];
    private int[] cutLen = new int[0];
    private int[] cutMine = new int[0];
    private int[] shadowFrom = new int[0];
    private int[] shadowLen = new int[0];
    private int[] shadowNote = new int[0];
    // RESONANCE guard: per LANE MINE (Note.index()), then per line for its TIDE STREAM MINEs (notes.length + line):
    // they sleep over [guardFrom, guardTo]; NaN = none.
    private double[] guardFrom = new double[0];
    private double[] guardTo = new double[0];

    // Running holds.
    private int[] active = new int[4];
    private int activeCount;
    /** One line's running TIDE STREAMs in the order they get fingers ({@link #sortCover}). */
    private int[] coverOrder = new int[4];

    // Pointers.
    private final boolean[] pDown = new boolean[MAX_POINTERS];
    private final float[] pX = new float[MAX_POINTERS];
    private final float[] pY = new float[MAX_POINTERS];
    private final int[] pHold = new int[MAX_POINTERS];
    private final int[] pLine = new int[MAX_POINTERS];
    /** Pointers that went down inside a veil: invalid until they lift (ARCHITECTURE §22). */
    private final boolean[] pVeiled = new boolean[MAX_POINTERS];
    /** The chart's veils (never null). */
    private Veil[] veils = new Veil[0];
    private Veil[] curtains = new Veil[0];
    /** Visual beat of the last {@link #eventGeometry} (what the player saw at the input event). */
    private float evBeat;

    // Candidate selection scratch.
    private int bestNote;
    private double bestT;
    private double bestAbs;
    private int bestKind;
    private float bestLineDist;
    private Judgement lastJudgement;
    private Line lastInputLine;
    private boolean lastInputMine;
    private boolean lastInputCaught;
    /** MINE events emitted so far (touches and LANE MINE contacts); lets touchDown see its own. */
    private int mineEvents;

    // Event ring (struct of arrays).
    private final int[] evType = new int[EVENT_CAPACITY];
    private final byte[] evGrade = new byte[EVENT_CAPACITY];
    private final float[] evDt = new float[EVENT_CAPACITY];
    private final float[] evX = new float[EVENT_CAPACITY];
    private final float[] evY = new float[EVENT_CAPACITY];
    private final float[] evTouchX = new float[EVENT_CAPACITY];
    private final float[] evTouchY = new float[EVENT_CAPACITY];
    private final int[] evLane = new int[EVENT_CAPACITY];
    private final float[] evWidth = new float[EVENT_CAPACITY];
    private final byte[] evLine = new byte[EVENT_CAPACITY];
    private final int[] evNote = new int[EVENT_CAPACITY];
    private final int[] evCount = new int[EVENT_CAPACITY];
    private final int[] evPointer = new int[EVENT_CAPACITY];
    private final double[] evTime = new double[EVENT_CAPACITY];
    private final int[] evFlags = new int[EVENT_CAPACITY];
    private int evHead;
    private int evSize;
    private int evDropped;

    /** Creates an idle engine; {@link #load} a chart before use. */
    public GameEngine() {
        Arrays.fill(pHold, -1);
    }

    // ================================================================== setup

    /**
     * @return the song clock. Load the music into it ({@link SongClock#load}, or
     *         {@link SongClock#attach} with null for a silent run) before {@link #load}.
     */
    public SongClock clock() {
        return clock;
    }

    /**
     * Resets everything for a new run of {@code chart} and prepares the clock at its start time
     * ({@link #leadInMs()}: {@code min(0, floor(first visibleFrom) − START_EPS_MS)}), paused. Allocates only when the
     * chart is larger than any before it.
     *
     * @param chart    the chart to play
     * @param autoplay true for a perfect, deterministic autoplay run (input never judges)
     */
    public void load(Chart chart, boolean autoplay) {
        if (chart == null) throw new IllegalArgumentException("chart is null");
        this.chart = chart;
        this.autoplay = autoplay;
        notes = chart.notes();
        int n = notes.length;
        if (state.length < n) {
            state = new byte[n];
            grade = new byte[n];
            dt = new float[n];
            resolvedMs = new float[n];
            holdOwner = new int[n];
            holdOffSince = new float[n];
            minePtrMask = new int[n];
            mineContacts = new int[n];
            mineContactMs = new float[n];
            lateWin = new float[n];
            noEcho = new boolean[n];
            tideLand = new float[n];
            cutFrom = new int[n];
            cutLen = new int[n];
            shadowFrom = new int[n];
            shadowLen = new int[n];
            caughtMs = new float[n];
        }
        Arrays.fill(state, 0, n, STATE_PENDING);
        Arrays.fill(grade, 0, n, (byte) -1);
        Arrays.fill(dt, 0, n, 0f);
        Arrays.fill(resolvedMs, 0, n, Float.NaN);
        Arrays.fill(holdOwner, 0, n, OWNER_NONE);
        Arrays.fill(holdOffSince, 0, n, Float.NaN);
        Arrays.fill(minePtrMask, 0, n, 0);
        Arrays.fill(mineContacts, 0, n, 0);
        Arrays.fill(mineContactMs, 0, n, Float.NaN);
        Arrays.fill(caughtMs, 0, n, Float.NaN);

        if (guardFrom.length < n + Line.COUNT) {
            guardFrom = new double[n + Line.COUNT];
            guardTo = new double[n + Line.COUNT];
        }
        Arrays.fill(guardFrom, 0, n + Line.COUNT, Double.NaN);
        Arrays.fill(guardTo, 0, n + Line.COUNT, Double.NaN);
        for (int l = 0; l < Line.COUNT; l++) {
            Line line = Line.of(l);
            lineStream[l] = chart.lineNotes(line);
            lineCursor[l] = 0;
            uniStream[l] = chart.universals(line);
            uniCursor[l] = 0;
            dragStream[l] = chart.drags(line);
            dragCursor[l] = 0;
        }
        Arrays.fill(tideLand, 0, n, 0f);
        for (int l = 0; l < Line.COUNT; l++) for (Note u : uniStream[l]) tideLand[u.index()] = landing(u);
        floatStream = chart.floatNotes();
        floatCursor = 0;
        floatDragStream = chart.floatDrags();
        floatDragCursor = 0;
        Note[] allMines = chart.mines();
        int blockTotal = 0;
        for (Note m : allMines) if (m.isBlockMine()) blockTotal++;
        if (mines.length < allMines.length - blockTotal) mines = new Note[allMines.length - blockTotal];
        if (laneMineArr.length < blockTotal) laneMineArr = new Note[blockTotal];
        mineCount = 0;
        laneMineCount = 0;
        for (Note m : allMines) {
            if (m.isBlockMine()) laneMineArr[laneMineCount++] = m;
            else mines[mineCount++] = m;
        }
        mineLo = 0;
        laneMineLo = 0;
        findShadows(n);
        int holds = Math.max(4, chart.count(NoteType.HOLD) + chart.tideStreamCount());
        if (active.length < holds) {
            active = new int[holds];
            coverOrder = new int[holds];
        }
        activeCount = 0;

        frameLayout.setLanes(chart);
        inputLayout.setLanes(chart);
        for (int p = 0; p < MAX_POINTERS; p++) {
            pDown[p] = false;
            pVeiled[p] = false;
            pHold[p] = -1;
            pLine[p] = -1;
        }
        veils = chart.veils();
        curtains = chart.curtains();
        keeper.reset(chart.totalNotes(), chart.totalMines());
        clearEvents();
        lastJudgement = null;
        lastInputLine = null;
        lastInputMine = false;
        lastInputCaught = false;

        lastEndMs = chart.lastNoteEndMs();
        double firstVisibleMs = chart.beatToMs(chart.firstVisibleBeat());
        leadInMs = (float) Math.min(0.0, Math.floor(firstVisibleMs) - START_EPS_MS);
        clock.prepare(leadInMs);
        phase = Phase.READY;
        frameJudgeMs = leadInMs - audioOffsetMs;
        frameVisualMs = frameJudgeMs + visualOffsetMs;
        frameBeat = (float) chart.msToBeat(frameVisualMs);
        frameGeometry(frameBeat);
    }

    /**
     * Overrides the start time chosen by {@link #load} (song ms where the clock starts, ≤ 0)
     * and re-prepares the clock. Only valid before {@link #start()}.
     */
    public void setLeadInMs(float startMs) {
        if (phase != Phase.READY) return;
        leadInMs = startMs;
        clock.prepare(startMs);
        frameJudgeMs = startMs - audioOffsetMs;
        frameVisualMs = frameJudgeMs + visualOffsetMs;
        frameBeat = (float) chart.msToBeat(frameVisualMs);
        frameGeometry(frameBeat);
    }

    /**
     * Sets the user offsets (ms; may change live, e.g. from the pause menu).
     *
     * @param audioOffsetMs  positive = the player hears audio late (judge times shift later)
     * @param visualOffsetMs positive = notes are drawn earlier (the display shows frames late)
     */
    public void setOffsets(float audioOffsetMs, float visualOffsetMs) {
        this.audioOffsetMs = audioOffsetMs;
        this.visualOffsetMs = visualOffsetMs;
    }

    /** Starts the run: the clock runs from {@link #leadInMs()}. No-op unless {@link Phase#READY}. */
    public void start() {
        if (phase != Phase.READY) return;
        clock.start();
        phase = Phase.RUNNING;
    }

    /**
     * Freezes time and judgement. Pointers keep being tracked; a hold whose pointer lifts while
     * paused is detached, not broken.
     *
     * @return true when the engine was running
     */
    public boolean pause() {
        if (phase == Phase.PAUSED) clock.pause(); // stops a count-in (the menu opened again during the countdown)
        if (phase != Phase.RUNNING) return false;
        clock.pause();
        phase = Phase.PAUSED;
        return true;
    }

    /**
     * Runs the paused run's music in through the countdown ({@link SongClock#startCountIn}): it plays muted from
     * {@code ms} before the frozen time while the run stays paused; call {@link #resume()} once
     * {@code clock().countInLeftMs()} is up.
     *
     * @return false when there is nothing to run in (not paused; desktop, where a resume is exact): resume plainly
     */
    public boolean startCountIn(float ms) {
        return phase == Phase.PAUSED && clock.startCountIn(ms);
    }

    /**
     * Resumes after {@link #pause()}. Detached holds get {@link #HOLD_LEAVE_MS} from now to be
     * re-grabbed; a running TIDE STREAM that has no finger now gets {@link #HOLD_LEAVE_MS} from now
     * to get one again (whatever lapse it had before the pause is forgotten).
     */
    public void resume() {
        if (phase != Phase.PAUSED) return;
        clock.resume();
        phase = Phase.RUNNING;
        float t = (float) (clock.nowMs() - audioOffsetMs);
        for (int k = 0; k < activeCount; k++) {
            int i = active[k];
            if (holdOwner[i] == OWNER_DETACHED) holdOffSince[i] = t;
        }
        for (int l = 0; l < Line.COUNT; l++) {
            Line line = Line.of(l);
            int m = sortCover(line, t);
            int free = fingers(line, frameLowerY, frameUpperY);
            for (int r = 0; r < m; r++) holdOffSince[coverOrder[r]] = r < free ? Float.NaN : t;
        }
    }

    // ================================================================== frame

    /**
     * Advances one frame: clock, geometry at the frame's visual time, autoplay, holds, the LOST
     * sweep, mines and the finish. Call once per frame after input was processed.
     */
    public void update() {
        if (chart == null) return;
        clock.update();
        if (phase != Phase.RUNNING) return;
        frameJudgeMs = clock.nowMs() - audioOffsetMs;
        frameVisualMs = frameJudgeMs + visualOffsetMs;
        frameBeat = (float) chart.msToBeat(frameVisualMs);
        frameGeometry(frameBeat);
        double t = frameJudgeMs;
        if (autoplay) autoJudge(t);
        resolvePointers();
        updateHolds(t);
        if (!autoplay) updateDrags(t);
        if (!autoplay) sweep(t - SWEEP_SLACK_MS);
        updateMines(t);
        if (t >= finishAtMs()) finish(t);
    }

    /**
     * Recomputes the frame geometry at the current frame beat, for a stretch that changed while the clock does
     * not run (a resize during the pre-roll or a pause: {@link #update} only evaluates it while running).
     */
    public void refreshGeometry() {
        if (chart != null) frameGeometry(frameBeat);
    }

    /** Line y and the lines' scale and camera of the frame, in the world ({@link Field}: x with kx, lines with ky). */
    private void frameGeometry(float beat) {
        JudgementLine je = chart.lineEvents();
        frameLayout.update(beat, Field.x(LaneLayout.VIEW_LEFT), Field.x(LaneLayout.VIEW_RIGHT), Field.kx());
        frameLowerY = Field.y(je.lowerAt(beat));
        frameUpperY = Field.y(je.upperAt(beat));
    }

    /** Line y and the lines' scale and camera at an input event's visual time, in the world (as {@link #frameGeometry}). */
    private void eventGeometry(double judgeMs) {
        float beat = (float) chart.msToBeat(judgeMs + visualOffsetMs);
        evBeat = beat;
        JudgementLine je = chart.lineEvents();
        inputLayout.update(beat, Field.x(LaneLayout.VIEW_LEFT), Field.x(LaneLayout.VIEW_RIGHT), Field.kx());
        evLowerY = Field.y(je.lowerAt(beat));
        evUpperY = Field.y(je.upperAt(beat));
    }

    private double eventJudgeMs(long nanos) {
        return clock.timeAtNanos(nanos) - audioOffsetMs;
    }

    private void autoJudge(double t) {
        for (int l = 0; l < Line.COUNT; l++) {
            Note[] arr = lineStream[l];
            int c = lineCursor[l];
            while (c < arr.length) {
                Note n = arr[c];
                int i = n.index();
                if (state[i] == STATE_PENDING) {
                    if (n.judgementMs() > t) break;
                    float x = frameLayout.noteX(n);
                    float y = lineY(n.line(), frameLowerY, frameUpperY);
                    judgeLaneNote(i, Judgement.RESONANCE, 0f, n.judgementMs(), OWNER_AUTO, PTR_ENGINE, x, y, x, y,
                        GameEvent.FLAG_AUTO);
                }
                c++;
            }
            lineCursor[l] = c;
        }
        for (int l = 0; l < Line.COUNT; l++) {
            Note[] arr = uniStream[l];
            int c = uniCursor[l];
            while (c < arr.length) {
                Note n = arr[c];
                if (state[n.index()] == STATE_PENDING) {
                    if (n.judgementMs() > t) break;
                    float x = (frameLayout.windowLeft() + frameLayout.windowRight()) * 0.5f;
                    float y = lineY(n.line(), frameLowerY, frameUpperY);
                    judgeGroup(n, Judgement.RESONANCE, 0f, n.judgementMs(), PTR_ENGINE, x, y, x, y,
                        GameEvent.FLAG_AUTO);
                }
                c++;
            }
            uniCursor[l] = c;
        }
        for (int l = 0; l < Line.COUNT; l++) {
            Note[] arr = dragStream[l];
            int c = dragCursor[l];
            while (c < arr.length) {
                Note n = arr[c];
                int i = n.index();
                if (state[i] == STATE_PENDING) {
                    if (n.judgementMs() > t) break;
                    float x = frameLayout.noteX(n);
                    float y = lineY(n.line(), frameLowerY, frameUpperY);
                    judgeDrag(i, n.judgementMs(), PTR_ENGINE, x, y, x, y, GameEvent.FLAG_AUTO);
                }
                c++;
            }
            dragCursor[l] = c;
        }
        // floating notes: their events take their place ({@link #emit})
        int c = floatCursor;
        while (c < floatStream.length) {
            Note n = floatStream[c];
            int i = n.index();
            if (state[i] == STATE_PENDING) {
                if (n.judgementMs() > t) break;
                judgeLaneNote(i, Judgement.RESONANCE, 0f, n.judgementMs(), OWNER_AUTO, PTR_ENGINE, Float.NaN,
                    Float.NaN, Float.NaN, Float.NaN, GameEvent.FLAG_AUTO);
            }
            c++;
        }
        floatCursor = c;
        c = floatDragCursor;
        while (c < floatDragStream.length) {
            Note n = floatDragStream[c];
            int i = n.index();
            if (state[i] == STATE_PENDING) {
                if (n.judgementMs() > t) break;
                judgeDrag(i, n.judgementMs(), PTR_ENGINE, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    GameEvent.FLAG_AUTO);
            }
            c++;
        }
        floatDragCursor = c;
    }

    private void resolvePointers() {
        for (int p = 0; p < MAX_POINTERS; p++) {
            pLine[p] = pDown[p] && !pVeiled[p] ? zoneLine(pY[p], frameLowerY, frameUpperY) : -1;
        }
    }

    private void updateHolds(double t) {
        for (int k = activeCount - 1; k >= 0; k--) {
            int i = active[k];
            Note n = notes[i];
            int o = holdOwner[i];
            boolean on;
            if (o == OWNER_AUTO || o == OWNER_KEPT) {
                on = true;
            } else if (o == OWNER_COVER) {
                continue;
            } else if (o >= 0) {
                on = pDown[o] && !pVeiled[o] && onHold(n, pX[o], pY[o], frameLayout, frameLowerY, frameUpperY);
            } else {
                on = regrabByPointers(i, n);
            }
            stepHold(k, i, on, t);
        }
        stepCoverage(t, frameLowerY, frameUpperY);
    }

    /** A resting pointer that is on a detached hold takes it over. */
    private boolean regrabByPointers(int i, Note n) {
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p] && !pVeiled[p] && pHold[p] < 0
                && onHold(n, pX[p], pY[p], frameLayout, frameLowerY, frameUpperY)) {
                attachHold(i, p, true);
                return true;
            }
        }
        return false;
    }

    /**
     * Applies one on/off observation at time t to running hold i (slot k in {@link #active}) and
     * completes or breaks it when due. A lapse that already lasted longer than
     * {@link #HOLD_LEAVE_MS} by t breaks the hold even when this observation finds it on again
     * (an input event stamped after a long lapse, before a frame noticed it). A TIDE STREAM whose
     * coverage lapses at or after {@code end − HOLD_RELEASE_GRACE_MS} completes at once.
     *
     * @return true when the hold finished (it was removed from the active list)
     */
    private boolean stepHold(int k, int i, boolean on, double t) {
        double end = notes[i].endMs();
        float off = holdOffSince[i];
        // Leaving inside the release grace is treated like a release there: it completes.
        if (!Float.isNaN(off) && off < end - HOLD_RELEASE_GRACE_MS && off + HOLD_LEAVE_MS < end
            && t > off + HOLD_LEAVE_MS) {
            breakHold(k, i, off + HOLD_LEAVE_MS);
            return true;
        }
        if (on) {
            holdOffSince[i] = Float.NaN;
        } else if (Float.isNaN(off)) {
            holdOffSince[i] = (float) t;
        }
        if (t >= end) {
            completeHold(k, i, end);
            return true;
        }
        if (!on && holdOwner[i] == OWNER_COVER && holdOffSince[i] >= end - HOLD_RELEASE_GRACE_MS) {
            completeHold(k, i, t);
            return true;
        }
        return false;
    }

    /**
     * Re-evaluates the coverage of every running TIDE STREAM at time t with the given line geometry
     * (the event's after a pointer changed, the frame's once per frame). Per line the
     * fingers go to the streams in {@link #sortCover} order, one each; a stream that finishes in
     * this pass (its end reached, or an earlier lapse ran out) passes its finger on to the next.
     */
    private void stepCoverage(double t, float lowerY, float upperY) {
        for (int l = 0; l < Line.COUNT; l++) {
            Line line = Line.of(l);
            int m = sortCover(line, t);
            if (m == 0) continue;
            int free = fingers(line, lowerY, upperY);
            for (int r = 0; r < m; r++) {
                int i = coverOrder[r];
                boolean on = free > 0;
                if (!stepHold(activeSlot(i), i, on, t) && on) free--;
            }
        }
    }

    /**
     * Puts the running TIDE STREAMs of {@code line} into {@link #coverOrder} in the order they get
     * fingers at time t: those before their last {@code HOLD_RELEASE_GRACE_MS} first (letting go
     * of one inside it completes it), earlier heads first, then lower indices.
     *
     * @return how many there are
     */
    private int sortCover(Line line, double t) {
        int m = 0;
        for (int k = 0; k < activeCount; k++) {
            int i = active[k];
            if (holdOwner[i] != OWNER_COVER || notes[i].line() != line) continue;
            int r = m++;
            while (r > 0 && coverFirst(i, coverOrder[r - 1], t)) {
                coverOrder[r] = coverOrder[r - 1];
                r--;
            }
            coverOrder[r] = i;
        }
        return m;
    }

    /** @return true when TIDE STREAM a gets a finger before b at time t (see {@link #sortCover}) */
    private boolean coverFirst(int a, int b, double t) {
        // Same float rounding as the lapse start that stepHold compares with the grace.
        double ft = (float) t;
        boolean graceA = ft >= notes[a].endMs() - HOLD_RELEASE_GRACE_MS;
        boolean graceB = ft >= notes[b].endMs() - HOLD_RELEASE_GRACE_MS;
        if (graceA != graceB) return graceB;
        double headA = notes[a].judgementMs();
        double headB = notes[b].judgementMs();
        if (headA != headB) return headA < headB;
        return a < b;
    }

    /**
     * @return how many fingers {@code line} has (ARCHITECTURE §19): down pointers inside its zone
     *         at any x (whatever else they hold, but not one that went down inside a veil)
     */
    private int fingers(Line line, float lowerY, float upperY) {
        float ly = lineY(line, lowerY, upperY);
        int count = 0;
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p] && !pVeiled[p] && Math.abs(pY[p] - ly) <= Field.zoneHalf()) count++;
        }
        return count;
    }

    private void sweep(double sweepT) {
        for (int l = 0; l < Line.COUNT; l++) lineCursor[l] = sweepStream(lineStream[l], lineCursor[l], sweepT);
        for (int l = 0; l < Line.COUNT; l++) {
            Note[] arr = uniStream[l];
            int c = uniCursor[l];
            while (c < arr.length) {
                Note n = arr[c];
                if (state[n.index()] != STATE_PENDING) {
                    c++;
                    continue;
                }
                if (n.judgementMs() + lateWin[n.index()] < sweepT) {
                    loseGroup(n);
                    c = n.groupStart() + n.groupSize();
                    continue;
                }
                break;
            }
            uniCursor[l] = c;
        }
        for (int l = 0; l < Line.COUNT; l++) dragCursor[l] = sweepStream(dragStream[l], dragCursor[l], sweepT);
        floatCursor = sweepStream(floatStream, floatCursor, sweepT);
        floatDragCursor = sweepStream(floatDragStream, floatDragCursor, sweepT);
    }

    /**
     * Sweeps one stream of single notes (taps / holds, DRAGs; no TIDE groups) from cursor {@code c}: every pending
     * note whose window ended before {@code sweepT} is LOST.
     *
     * @return the new cursor
     */
    private int sweepStream(Note[] arr, int c, double sweepT) {
        while (c < arr.length) {
            Note n = arr[c];
            int i = n.index();
            if (state[i] != STATE_PENDING) {
                c++;
                continue;
            }
            if (n.judgementMs() + lateWin[i] < sweepT) {
                loseNote(i);
                c++;
                continue;
            }
            break;
        }
        // a note whose window is cut short (a mine, a LANE MINE) can run out before an earlier one
        for (int c2 = c + 1; c2 < arr.length; c2++) {
            Note n = arr[c2];
            if (n.judgementMs() + TIER1_MS >= sweepT) break;
            int i = n.index();
            if (state[i] == STATE_PENDING && n.judgementMs() + lateWin[i] < sweepT) loseNote(i);
        }
        return c;
    }

    private void updateMines(double t) {
        double sweepT = t - SWEEP_SLACK_MS;
        updateLaneMines(t, sweepT);
        while (mineLo < mineCount) {
            Note m = mines[mineLo];
            int i = m.index();
            if (state[i] != STATE_PENDING) {
                mineLo++;
                continue;
            }
            if (m.judgementMs() + MINE_WINDOW_MS < sweepT) {
                state[i] = STATE_MINE_PASSED;
                resolvedMs[i] = (float) (m.judgementMs() + MINE_WINDOW_MS);
                mineLo++;
                continue;
            }
            break;
        }
    }

    private void finish(double t) {
        // Everything is resolved by now (the last end is at least 1.5 s ago); this is only a safety net.
        sweep(Double.POSITIVE_INFINITY);
        for (int k = activeCount - 1; k >= 0; k--) {
            int i = active[k];
            if (t >= notes[i].endMs()) completeHold(k, i, notes[i].endMs());
            else breakHold(k, i, t);
        }
        for (int k = mineLo; k < mineCount; k++) {
            int i = mines[k].index();
            if (state[i] == STATE_PENDING) {
                state[i] = STATE_MINE_PASSED;
                resolvedMs[i] = (float) (mines[k].judgementMs() + MINE_WINDOW_MS);
            }
        }
        mineLo = mineCount;
        for (int k = laneMineLo; k < laneMineCount; k++) endLaneMine(laneMineArr[k].index());
        laneMineLo = laneMineCount;
        clock.pause();
        phase = Phase.FINISHED;
    }

    // ================================================================== input

    /**
     * A pointer went down (ARCHITECTURE §10: gameplay notes act on touchDown). One that lands inside a veil
     * that is showing judges nothing until it lifts (ARCHITECTURE §22): {@link #INPUT_VEILED}. Floating notes are
     * judged inside their circles, in a zone or not; outside both zones a down that judged nothing and caught no DRAG
     * is {@link #INPUT_STRAY}.
     *
     * @param pointer pointer id (0..{@link #MAX_POINTERS}−1)
     * @param x       world x (unprojected with the gameplay viewport)
     * @param y       world y
     * @param nanos   event timestamp ({@code Gdx.input.getCurrentEventTime()}, nanoTime base)
     * @return one of the {@code INPUT_*} codes
     */
    public int touchDown(int pointer, float x, float y, long nanos) {
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS) return INPUT_IGNORED;
        if (pDown[pointer]) release(pointer, nanos, false); // a lost touchUp: release the old press first
        pDown[pointer] = true;
        pX[pointer] = x;
        pY[pointer] = y;
        pHold[pointer] = -1;
        lastJudgement = null;
        lastInputLine = null;
        lastInputMine = false;
        lastInputCaught = false;
        if (phase != Phase.RUNNING) {
            // held through a pause or the start: the veils as they show now decide
            pVeiled[pointer] = Veils.containsAny(veils, x, y, frameBeat);
            return INPUT_IGNORED;
        }
        double t = eventJudgeMs(nanos);
        eventGeometry(t);
        if (!autoplay) stepCoverage(t, evLowerY, evUpperY);
        pVeiled[pointer] = Veils.containsAny(veils, x, y, evBeat);
        if (pVeiled[pointer]) return INPUT_VEILED;
        float dLower = zoneDist(Line.LOWER.ordinal(), y, evLowerY, t);
        float dUpper = zoneDist(Line.UPPER.ordinal(), y, evUpperY, t);
        boolean inLower = dLower <= Field.zoneHalf();
        boolean inUpper = dUpper <= Field.zoneHalf();
        boolean inZone = inLower || inUpper;
        int zone = inLower && inUpper ? (dUpper < dLower ? Line.UPPER.ordinal() : Line.LOWER.ordinal())
            : inLower ? Line.LOWER.ordinal() : inUpper ? Line.UPPER.ordinal() : -1;
        lastInputLine = zone >= 0 ? Line.of(zone) : null;
        float zoneY = zone == Line.UPPER.ordinal() ? evUpperY : evLowerY;
        if (Math.abs(y - zoneY) > Field.zoneHalf()) zoneY = y; // in a TIDE's zone only: the feedback where it is
        if (autoplay) {
            emitEmpty(x, inZone ? zoneY : y, zone, pointer, t, inZone ? GameEvent.FLAG_AUTO : 0);
            return inZone ? INPUT_EMPTY : INPUT_STRAY;
        }
        // outside both zones only the floating notes can take it
        int minesBefore = mineEvents;
        checkMinesAt(t, x, y, pointer, true);
        lastInputMine = mineEvents != minesBefore;
        int caught = catchDragsAt(t, pointer, x, y);
        lastInputCaught = caught != CATCH_NONE;
        if (regrabAt(pointer, x, y, t)) return INPUT_REGRAB;

        clearBest();
        for (int l = 0; l < Line.COUNT; l++) {
            float dist = l == 0 ? dLower : dUpper;
            if (dist > Field.zoneHalf()) continue;
            int c = placeCandidate(l, x, y, t, 0.5f);
            if (c >= 0) {
                consider(c, KIND_OWN, dist, t);
            } else {
                c = placeCandidate(l, x, y, t, 0.5f + NEIGHBOUR_TOLERANCE);
                consider(c, KIND_NEIGHBOUR, dist, t);
            }
            consider(universalCandidate(l, t, c >= 0), KIND_UNIVERSAL, dist, t);
        }
        considerFloats(t, x, y);
        if (bestNote < 0) {
            if (!inZone && !lastInputMine && !lastInputCaught) {
                emitEmpty(x, y, -1, pointer, t, 0);
                return INPUT_STRAY;
            }
            // a press that only judged a DRAG has its HIT already; one that caught a DRAG early is still a bare touch
            if (caught != CATCH_JUDGED) emitEmpty(x, inZone ? zoneY : y, zone, pointer, t, 0);
            return INPUT_EMPTY;
        }
        boolean floating = notes[bestNote].floats();
        judgeBest(pointer, x, y, t, bestKind == KIND_NEIGHBOUR ? GameEvent.FLAG_NEIGHBOUR : 0);
        // one press, one floating note: no sync partner on a line with it
        if (inLower && inUpper && !floating) judgeOtherLine(pointer, x, y, t);
        return INPUT_JUDGED;
    }

    /**
     * Overlapping zones (Kelvin 10-12): a touch inside both zones also judges the other line's note in
     * the same place (its range overlaps the judged note's, or holds the touch when a TIDE was judged), and that
     * line's TIDE group, when they share the judged note's beat exactly, with
     * the same grade and Δt. A sky + ground sync pair then takes one tap. A second hold follows the
     * same pointer through its owner; the pointer keeps the first one.
     */
    private void judgeOtherLine(int pointer, float x, float y, double t) {
        Note n = notes[bestNote];
        int other = 1 - n.line().ordinal();
        float d = (float) (t - n.judgementMs());
        Judgement j = lastJudgement;
        float ly = other == Line.UPPER.ordinal() ? evUpperY : evLowerY;
        boolean tide = n.type() == NoteType.UNIVERSAL;
        float at = tide ? x : inputLayout.noteX(n);
        float half = tide ? 0f : 0.5f * inputLayout.noteWidth(n);
        int c = pendingPlaceAt(other, n.judgement(), at, half);
        if (c >= 0) {
            int keep = pHold[pointer];
            judgeLaneNote(c, j, d, t, pointer, pointer, inputLayout.noteX(notes[c]), ly, x, y, GameEvent.FLAG_JOINT);
            if (keep >= 0) pHold[pointer] = keep;
        }
        int g = pendingTideAt(other, n.judgement());
        if (g >= 0) judgeGroup(notes[g], j, d, t, pointer, x, ly, x, y, GameEvent.FLAG_JOINT);
    }

    /** @return true when {@code line} has a pending tap / hold at exactly {@code beat} */
    private boolean pendingLaneAt(int line, float beat) {
        Note[] arr = lineStream[line];
        for (int c = lineCursor[line]; c < arr.length; c++) {
            Note u = arr[c];
            float b = u.judgement();
            if (b > beat) break;
            if (b == beat && state[u.index()] == STATE_PENDING) return true;
        }
        return false;
    }

    /**
     * @return a pending tap / hold of {@code line} at exactly {@code beat} in the same place as the range
     *         {@code x ± half} (world, at the event's visual time; {@code half} 0: the range holds x), or −1
     */
    private int pendingPlaceAt(int line, float beat, float x, float half) {
        Note[] arr = lineStream[line];
        for (int c = lineCursor[line]; c < arr.length; c++) {
            Note u = arr[c];
            float b = u.judgement();
            if (b > beat) break;
            if (b != beat || state[u.index()] != STATE_PENDING) continue;
            float w = inputLayout.noteWidth(u);
            float dx = Math.abs(inputLayout.noteX(u) - x);
            if (half > 0f ? samePlace(dx, 2f * half, w) : dx <= 0.5f * w) return u.index();
        }
        return -1;
    }

    /**
     * @return true when two ranges {@code dx} apart and {@code w1}, {@code w2} wide are in the same place (the old
     *         "same lane"): they overlap by more than 1 % of their mean width, so keys side by side are not
     */
    static boolean samePlace(float dx, float w1, float w2) {
        return dx < 0.495f * (w1 + w2);
    }

    /**
     * A pointer moved while down: hold tracking, mines and re-grabbing a detached hold.
     *
     * @param nanos event timestamp ({@code Gdx.input.getCurrentEventTime()})
     */
    public void touchDragged(int pointer, float x, float y, long nanos) {
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        pX[pointer] = x;
        pY[pointer] = y;
        if (phase != Phase.RUNNING || autoplay || pVeiled[pointer]) return;
        double t = eventJudgeMs(nanos);
        eventGeometry(t);
        checkMinesAt(t, x, y, pointer, false);
        catchDragsAt(t, pointer, x, y);
        int h = pHold[pointer];
        if (h >= 0) {
            int k = activeSlot(h);
            if (k >= 0) stepHold(k, h, onHold(notes[h], x, y, inputLayout, evLowerY, evUpperY), t);
        } else {
            regrabAt(pointer, x, y, t);
        }
        stepCoverage(t, evLowerY, evUpperY);
    }

    /**
     * A pointer went up: releases its hold (completed within the grace, else LOST).
     *
     * @param nanos event timestamp ({@code Gdx.input.getCurrentEventTime()})
     */
    public void touchUp(int pointer, float x, float y, long nanos) {
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        pX[pointer] = x;
        pY[pointer] = y;
        release(pointer, nanos, true);
    }

    /**
     * One pointer was called off (a transition lock): treated as a release at its last position. A gesture the system
     * took is {@link #gestureCancelled()} instead.
     */
    public void touchCancelled(int pointer, long nanos) {
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        release(pointer, nanos, true);
    }

    /**
     * The system took the whole gesture (ARCHITECTURE §10; Kelvin 2026-11-06: players reported dropped touches), for
     * example an edge swipe or a phone maker's three-finger gesture. Android then cancels every finger on the screen
     * and reports nothing more about them, not even their lifting (libGDX passes on one cancel and forgets the rest),
     * so every pointer is let go here without judging anything. While the run is running, what they held is kept:
     * every running hold (STREAM, floating or not, TIDE STREAM) is held by the engine to its end, completing with its
     * head grade unless a lapse it already had runs out ({@link #HOLD_LEAVE_MS}). Paused or not running, the fingers
     * count as lifted (a pause detaches their holds). New fingers play as usual.
     */
    public void gestureCancelled() {
        if (chart == null) return;
        if (phase == Phase.RUNNING && !autoplay) {
            for (int k = 0; k < activeCount; k++) {
                int i = active[k];
                if (holdOwner[i] != OWNER_AUTO) holdOwner[i] = OWNER_KEPT;
            }
            for (int p = 0; p < MAX_POINTERS; p++) {
                if (!pDown[p]) continue;
                pDown[p] = false;
                pVeiled[p] = false;
                pHold[p] = -1;
                pLine[p] = -1;
                forgetLaneMinePointer(p);
            }
            return;
        }
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p]) release(p, 0L, false);
        }
    }

    private void release(int pointer, long nanos, boolean checkMines) {
        int h = pHold[pointer];
        boolean judging = phase == Phase.RUNNING && !autoplay && !pVeiled[pointer];
        double t = 0.0;
        if (judging) {
            t = eventJudgeMs(nanos);
            eventGeometry(t);
            if (checkMines) checkMinesAt(t, pX[pointer], pY[pointer], pointer, false);
            if (h >= 0) {
                int k = activeSlot(h);
                if (k >= 0 && !stepHold(k, h, onHold(notes[h], pX[pointer], pY[pointer], inputLayout,
                    evLowerY, evUpperY), t)) {
                    // A finger that already left the zone or lane and lifts without coming back let
                    // go when it left, so lifting is judged exactly like keeping it down (ARCH §10).
                    float off = holdOffSince[h];
                    releaseHold(k, h, Float.isNaN(off) ? t : Math.min(t, off));
                }
            }
        } else if (phase == Phase.PAUSED && h >= 0) {
            detachHold(h);
        }
        pDown[pointer] = false;
        pVeiled[pointer] = false;
        pHold[pointer] = -1;
        forgetLaneMinePointer(pointer);
        if (judging) stepCoverage(t, evLowerY, evUpperY);
    }

    /**
     * Keys no longer play (the game is touch only, Kelvin 2026-11-05: 「删掉所有电脑相关内容」); kept so the chart
     * editor, which builds this class, still compiles until it moves to chart format v6.
     *
     * @return {@link #INPUT_IGNORED}
     */
    public int keyDown(int keycode, long nanos) {
        return INPUT_IGNORED;
    }

    /** Keys no longer play; see {@link #keyDown(int, long)}. */
    public void keyUp(int keycode, long nanos) {
    }

    // ------------------------------------------------------------------ candidates

    /**
     * Earliest pending tap/hold head of {@code line} whose judge window ({@link #inWindow}) holds t and whose centre
     * is within {@code reach} of its widths of x (world, at the event's visual time; 0.5: under the finger), or −1.
     * A key narrower than {@link LaneLayout#MIN_ACTIVE_WIDTH} cannot be touched.
     */
    private int placeCandidate(int line, float x, float y, double t, float reach) {
        Note[] arr = lineStream[line];
        for (int c = lineCursor[line]; c < arr.length; c++) {
            Note n = arr[c];
            double T = n.judgementMs();
            if (T - TIER2_MS > t) break;
            int i = n.index();
            if (state[i] != STATE_PENDING || !inWindowAt(i, t, x, y)) continue;
            float w = inputLayout.judgeWidth(n);
            if (w >= LaneLayout.MIN_ACTIVE_WIDTH && Math.abs(x - inputLayout.noteX(n)) <= reach * w) return i;
        }
        return -1;
    }

    /**
     * Earliest pending TIDE note of {@code line} with |dt| ≤ TIER2, or −1. With {@code laneNote} (the input also
     * has a tap / STREAM head of this line to judge), a TIDE whose exact beat still has a pending tap / STREAM head
     * on this line is skipped: it waits to be judged together with that note (ARCHITECTURE §20), so it never takes
     * an input meant for another note (Kelvin 2026-10-24: a dense TIDE + DROP run lost its DROPs).
     */
    private int universalCandidate(int line, double t, boolean laneNote) {
        Note[] arr = uniStream[line];
        for (int c = uniCursor[line]; c < arr.length; c++) {
            Note n = arr[c];
            double T = n.judgementMs();
            if (T - TIER2_MS > t) break;
            if (state[n.index()] != STATE_PENDING || !inWindow(n.index(), t)) continue;
            if (laneNote && pendingLaneAt(line, n.judgement())) continue;
            return n.index();
        }
        return -1;
    }

    /**
     * Considers every pending floating tap / STREAM head whose judge window ({@link #inWindow}) holds t and whose
     * circle at the press (the event's geometry, {@link FloatSpace}) holds (x, y): {@link #KIND_FLOAT}, nearest centre
     * first on a tie (in its radii).
     */
    private void considerFloats(double t, float x, float y) {
        Note[] arr = floatStream;
        for (int c = floatCursor; c < arr.length; c++) {
            Note n = arr[c];
            if (n.judgementMs() - TIER2_MS > t) break;
            int i = n.index();
            if (state[i] != STATE_PENDING || !inWindowAt(i, t, x, y)) continue;
            float d = FloatSpace.reach(inputLayout, n.floating(), x, y);
            if (d <= 1f) consider(i, KIND_FLOAT, d, t);
        }
    }

    private void clearBest() {
        bestNote = -1;
        bestT = 0;
        bestAbs = 0;
        bestKind = 0;
        bestLineDist = 0f;
    }

    private void consider(int i, int kind, float lineDist, double t) {
        if (i < 0) return;
        double T = notes[i].judgementMs();
        double abs = Math.abs(t - T);
        boolean better;
        if (bestNote < 0 || T < bestT) {
            better = true;
        } else if (T > bestT) {
            better = false;
        } else if (abs != bestAbs) {
            better = abs < bestAbs;
        } else if (kind != bestKind) {
            better = kind < bestKind;
        } else {
            better = lineDist < bestLineDist;
        }
        if (!better) return;
        bestNote = i;
        bestT = T;
        bestAbs = abs;
        bestKind = kind;
        bestLineDist = lineDist;
    }

    /** Judges the selected candidate for pointer's input at (x, y) and judge time t. */
    private void judgeBest(int pointer, float x, float y, double t, int flags) {
        Note n = notes[bestNote];
        float d = (float) (t - n.judgementMs());
        Judgement j = Judgement.ofDelta(Math.abs(d));
        if (j == null) j = Judgement.ECHO; // |dt| ≤ TIER2 by construction; float rounding guard
        lastJudgement = j;
        if (n.floats()) { // alone: its events take its place ({@link #emit})
            judgeLaneNote(n.index(), j, d, t, pointer, pointer, Float.NaN, Float.NaN, x, y, flags);
            return;
        }
        float ly = n.isUpper() ? evUpperY : evLowerY;
        lastInputLine = n.line();
        if (n.type() == NoteType.UNIVERSAL) {
            judgeGroup(n, j, d, t, pointer, x, ly, x, y, flags);
        } else {
            judgeLaneNote(n.index(), j, d, t, pointer, pointer, inputLayout.noteX(n), ly, x, y, flags);
            // Joint judging (ARCHITECTURE §20): the TIDE group of this line at exactly this beat
            // is judged by the same tap, with the same grade and Δt.
            int g = pendingTideAt(n.line().ordinal(), n.judgement());
            if (g >= 0) judgeGroup(notes[g], j, d, t, pointer, x, ly, x, y, flags | GameEvent.FLAG_JOINT);
        }
    }

    /**
     * @return a pending member of the TIDE group of {@code line} whose judgement beat is exactly
     *         {@code beat}, or −1
     */
    private int pendingTideAt(int line, float beat) {
        Note[] arr = uniStream[line];
        for (int c = uniCursor[line]; c < arr.length; c++) {
            Note u = arr[c];
            float b = u.judgement();
            if (b > beat) break;
            if (b == beat && state[u.index()] == STATE_PENDING) return u.index();
        }
        return -1;
    }

    private void judgeLaneNote(int i, Judgement j, float d, double t, int owner, int pointer, float x, float y,
                               float touchX, float touchY, int flags) {
        Note n = notes[i];
        grade[i] = (byte) j.ordinal();
        dt[i] = d;
        resolvedMs[i] = (float) t;
        if (j == Judgement.RESONANCE && noEcho[i]) guardLane(n, t);
        if (j == Judgement.RESONANCE) guardAt(i, touchX, touchY, t, inputLayout, evLowerY, evUpperY);
        if (n.type() == NoteType.HOLD) {
            state[i] = STATE_HOLDING;
            active[activeCount++] = i;
            holdOwner[i] = OWNER_NONE;
            if (owner >= 0) attachHold(i, owner, false);
            else holdOwner[i] = owner;
            holdOffSince[i] = Float.NaN;
            emit(GameEvent.HOLD_START, j, d, x, y, touchX, touchY, n.laneIndex(), n.line(), i, 1, pointer, t, flags);
            if (t >= n.endMs()) completeHold(activeCount - 1, i, t);
        } else {
            state[i] = STATE_HIT;
            keeper.record(j, d);
            emit(GameEvent.HIT, j, d, x, y, touchX, touchY, n.laneIndex(), n.line(), i, 1, pointer, t, flags);
        }
    }

    /**
     * Judges every pending member of {@code n}'s TIDE group with one grade: TIDE drops are hit and
     * scored now; each TIDE STREAM starts running (held by coverage, or by autoplay) and is scored
     * when it ends. One HIT event covers the group ({@link GameEvent#FLAG_TIDE_STREAM} when a
     * stream started).
     */
    private void judgeGroup(Note n, Judgement j, float d, double t, int pointer, float x, float y, float touchX,
                            float touchY, int flags) {
        Note[] arr = uniStream[n.line().ordinal()];
        int from = n.groupStart();
        int to = from + n.groupSize();
        int count = 0;
        int streams = 0;
        boolean auto = (flags & GameEvent.FLAG_AUTO) != 0;
        for (int c = from; c < to; c++) {
            int i = arr[c].index();
            if (state[i] != STATE_PENDING) continue;
            if (j == Judgement.RESONANCE && noEcho[i]) guardLane(arr[c], t);
            grade[i] = (byte) j.ordinal();
            dt[i] = d;
            resolvedMs[i] = (float) t;
            if (arr[c].isTideStream()) {
                state[i] = STATE_HOLDING;
                active[activeCount++] = i;
                holdOwner[i] = auto ? OWNER_AUTO : OWNER_COVER;
                holdOffSince[i] = Float.NaN;
                streams++;
            } else {
                state[i] = STATE_HIT;
                keeper.record(j, d);
            }
            count++;
        }
        emit(GameEvent.HIT, j, d, x, y, touchX, touchY, -1, n.line(), arr[from].index(), count, pointer, t,
            flags | GameEvent.FLAG_UNIVERSAL | (streams > 0 ? GameEvent.FLAG_TIDE_STREAM : 0));
        if (streams == 0) return;
        // a stream whose end has already passed (very short, tapped late) completes at once
        for (int k = activeCount - 1; k >= 0; k--) {
            int i = active[k];
            Note m = notes[i];
            if (m.isTideStream() && m.line() == n.line() && m.groupStart() == from && t >= m.endMs()) {
                completeHold(k, i, t);
            }
        }
    }

    // ------------------------------------------------------------------ DRAGs

    /** {@link #catchDragsAt} result: no DRAG caught. */
    private static final int CATCH_NONE = 0;
    /** {@link #catchDragsAt} result: a DRAG caught before its time (judged when its time comes). */
    private static final int CATCH_EARLY = 1;
    /** {@link #catchDragsAt} result: a DRAG judged at once (caught at or after its time). */
    private static final int CATCH_JUDGED = 2;

    /**
     * One input event of a valid pointer at (x, y), judge time t (the event's geometry): catches every pending DRAG
     * there whose window holds t, and judges the caught ones whose time has come.
     *
     * @return {@link #CATCH_JUDGED} when it judged one, else {@link #CATCH_EARLY} when it caught one, else
     *         {@link #CATCH_NONE}
     */
    private int catchDragsAt(double t, int pointer, float x, float y) {
        int result = CATCH_NONE;
        for (int l = 0; l < Line.COUNT; l++) {
            result = catchDragsIn(dragStream[l], dragCursor[l], t, pointer, x, y, result);
        }
        return catchDragsIn(floatDragStream, floatDragCursor, t, pointer, x, y, result);
    }

    /**
     * {@link #catchDragsAt} over one stream from {@code from}; a floating DRAG is caught inside its circle, its events
     * take its place.
     *
     * @return {@code result}, raised to what this stream did
     */
    private int catchDragsIn(Note[] arr, int from, double t, int pointer, float x, float y, int result) {
        for (int c = from; c < arr.length; c++) {
            Note n = arr[c];
            double T = n.judgementMs();
            if (T - TIER2_MS > t) break;
            int i = n.index();
            if (state[i] != STATE_PENDING) continue;
            boolean floating = n.floats();
            if (!Float.isNaN(caughtMs[i])) { // caught already: judged at its time
                if (t >= T) {
                    judgeDrag(i, T, pointer, floating ? Float.NaN : inputLayout.noteX(n),
                        floating ? Float.NaN : lineY(n.line(), evLowerY, evUpperY), x, y, 0);
                    result = CATCH_JUDGED;
                }
                continue;
            }
            if (t > T + lateWin[i] || !onNote(n, x, y, inputLayout, evLowerY, evUpperY)) continue;
            if (t > T + TIER1_MS && echoCut(i, x, y, t, inputLayout, evLowerY, evUpperY)) continue;
            float nx = floating ? Float.NaN : inputLayout.noteX(n);
            float ny = floating ? Float.NaN : lineY(n.line(), evLowerY, evUpperY);
            if (t < T) {
                catchEarly(i, t, pointer, nx, ny, x, y);
                if (result == CATCH_NONE) result = CATCH_EARLY;
            } else {
                judgeDrag(i, t, pointer, nx, ny, x, y, 0);
                result = CATCH_JUDGED;
            }
        }
        return result;
    }

    /**
     * Frame pass over the DRAGs (frame time t, the frame's geometry): judges the caught ones whose time has come at
     * their time, and lets every valid resting pointer catch the others, so a finger already in place (or a DRAG
     * moving under a resting finger) catches it.
     */
    private void updateDrags(double t) {
        for (int l = 0; l < Line.COUNT; l++) updateDragsIn(dragStream[l], dragCursor[l], t);
        updateDragsIn(floatDragStream, floatDragCursor, t);
    }

    /** {@link #updateDrags} over one stream from {@code from}. */
    private void updateDragsIn(Note[] arr, int from, double t) {
        for (int c = from; c < arr.length; c++) {
            Note n = arr[c];
            double T = n.judgementMs();
            if (T - TIER2_MS > t) break;
            int i = n.index();
            if (state[i] != STATE_PENDING) continue;
            boolean floating = n.floats();
            float nx = floating ? Float.NaN : frameLayout.noteX(n);
            float ny = floating ? Float.NaN : lineY(n.line(), frameLowerY, frameUpperY);
            if (!Float.isNaN(caughtMs[i])) {
                if (t >= T) judgeDrag(i, T, PTR_ENGINE, nx, ny, nx, ny, 0);
                continue;
            }
            if (t > T + lateWin[i]) continue; // the sweep loses it
            int p = dragFinger(n, t);
            if (p < 0) continue;
            if (t < T) catchEarly(i, t, p, nx, ny, pX[p], pY[p]);
            else judgeDrag(i, t, p, nx, ny, pX[p], pY[p], 0);
        }
    }

    /**
     * @return a valid down pointer on DRAG n at frame time t with the frame's geometry (after its RESONANCE window, not
     *         one over a LONG MINE that takes it there, {@link #echoCut}), or −1
     */
    private int dragFinger(Note n, double t) {
        boolean late = t > n.judgementMs() + TIER1_MS;
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (!pDown[p] || pVeiled[p] || !onNote(n, pX[p], pY[p], frameLayout, frameLowerY, frameUpperY)) continue;
            if (!late || !echoCut(n.index(), pX[p], pY[p], t, frameLayout, frameLowerY, frameUpperY)) return p;
        }
        return -1;
    }

    /**
     * True when (x, y) catches DRAG n, or could judge tap / STREAM head n: inside its line's zone and within
     * {@link #DRAG_REACH} of its widths of its centre (as far as a tap reaches a neighbour; a key narrower than
     * {@link LaneLayout#MIN_ACTIVE_WIDTH} cannot be caught); a floating note inside its circle at the layout's beat.
     */
    private static boolean onNote(Note n, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        if (n.floats()) return FloatSpace.reach(lay, n.floating(), x, y) <= 1f;
        if (Math.abs(y - lineY(n.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        float w = lay.judgeWidth(n);
        return w >= LaneLayout.MIN_ACTIVE_WIDTH && Math.abs(x - lay.noteX(n)) <= DRAG_REACH * w;
    }

    /** DRAG i caught at t before its time: it is judged at its time; emits {@link GameEvent#CATCH} (time = its time). */
    private void catchEarly(int i, double t, int pointer, float x, float y, float touchX, float touchY) {
        Note n = notes[i];
        caughtMs[i] = (float) t;
        emit(GameEvent.CATCH, Judgement.RESONANCE, 0f, x, y, touchX, touchY, n.laneIndex(), n.line(), i, 1, pointer,
            n.judgementMs(), GameEvent.FLAG_DRAG);
    }

    /** Judges DRAG i RESONANCE at {@code at}: Δt 0, PERFECT, no timing statistics; one {@link GameEvent#HIT}. */
    private void judgeDrag(int i, double at, int pointer, float x, float y, float touchX, float touchY, int flags) {
        Note n = notes[i];
        state[i] = STATE_HIT;
        grade[i] = (byte) Judgement.RESONANCE.ordinal();
        dt[i] = 0f;
        resolvedMs[i] = (float) at;
        if (noEcho[i]) guardLane(n, at);
        if (Float.isNaN(touchX) && n.floats()) { // judged at its time after an early catch: where it is
            touchX = FloatSpace.worldX(frameLayout, n.floating());
            touchY = FloatSpace.worldY(frameLayout, n.floating());
        }
        guardAt(i, touchX, touchY, at, frameLayout, frameLowerY, frameUpperY);
        keeper.recordDrag();
        emit(GameEvent.HIT, Judgement.RESONANCE, 0f, x, y, touchX, touchY, n.laneIndex(), n.line(), i, 1, pointer, at,
            flags | GameEvent.FLAG_DRAG);
    }

    // ------------------------------------------------------------------ holds

    /**
     * Gives running hold i to pointer {@code owner}. With {@code regrab} a HOLD_START/REGRAB event is emitted at
     * the frame time.
     */
    private void attachHold(int i, int owner, boolean regrab) {
        holdOwner[i] = owner;
        pHold[owner] = i;
        holdOffSince[i] = Float.NaN;
        if (regrab) {
            Note n = notes[i];
            float x = frameLayout.noteX(n);
            float y = lineY(n.line(), frameLowerY, frameUpperY);
            emitHold(GameEvent.HOLD_START, i, x, y, ownerPointer(owner), frameJudgeMs,
                GameEvent.FLAG_REGRAB | ownerFlags(owner));
        }
    }

    private boolean regrabAt(int pointer, float x, float y, double t) {
        for (int k = 0; k < activeCount; k++) {
            int i = active[k];
            if (holdOwner[i] != OWNER_DETACHED) continue;
            Note n = notes[i];
            if (!onHold(n, x, y, inputLayout, evLowerY, evUpperY)) continue;
            holdOwner[i] = pointer;
            pHold[pointer] = i;
            holdOffSince[i] = Float.NaN;
            emitHold(GameEvent.HOLD_START, i, inputLayout.noteX(n), lineY(n.line(), evLowerY, evUpperY),
                pointer, t, GameEvent.FLAG_REGRAB);
            return true;
        }
        return false;
    }

    private void detachHold(int i) {
        int o = holdOwner[i];
        if (o >= 0) pHold[o] = -1;
        holdOwner[i] = OWNER_DETACHED;
    }

    private void releaseOwner(int i) {
        int o = holdOwner[i];
        if (o >= 0 && pHold[o] == i) pHold[o] = -1;
        holdOwner[i] = OWNER_NONE;
    }

    private void releaseHold(int k, int i, double t) {
        if (t >= notes[i].endMs() - HOLD_RELEASE_GRACE_MS) completeHold(k, i, t);
        else breakHold(k, i, t);
    }

    private void completeHold(int k, int i, double at) {
        Note n = notes[i];
        state[i] = STATE_COMPLETED;
        resolvedMs[i] = (float) at;
        Judgement j = Judgement.byOrdinal(grade[i]);
        int o = holdOwner[i];
        releaseOwner(i);
        removeActive(k);
        keeper.record(j, dt[i]);
        emit(GameEvent.HOLD_END, j, dt[i], holdX(n), lineY(n.line(), frameLowerY, frameUpperY),
            ownerX(o), ownerY(o), n.laneIndex(), n.line(), i, 1, ownerPointer(o), at,
            GameEvent.FLAG_COMPLETED | ownerFlags(o) | holdFlags(n));
    }

    private void breakHold(int k, int i, double at) {
        Note n = notes[i];
        state[i] = STATE_LOST;
        resolvedMs[i] = (float) at;
        int o = holdOwner[i];
        releaseOwner(i);
        removeActive(k);
        keeper.record(Judgement.LOST, 0f);
        emit(GameEvent.HOLD_END, Judgement.LOST, dt[i], holdX(n),
            lineY(n.line(), frameLowerY, frameUpperY), ownerX(o), ownerY(o), n.laneIndex(), n.line(), i, 1,
            ownerPointer(o), at, ownerFlags(o) | holdFlags(n));
    }

    /** Feedback x of a hold's events: where it is, or the window centre for a TIDE STREAM. */
    private float holdX(Note n) {
        return n.hasX() ? frameLayout.noteX(n) : (frameLayout.windowLeft() + frameLayout.windowRight()) * 0.5f;
    }

    /** Event flags that mark a TIDE STREAM's events. */
    private static int holdFlags(Note n) {
        return n.isTideStream() ? GameEvent.FLAG_UNIVERSAL | GameEvent.FLAG_TIDE_STREAM : 0;
    }

    private void removeActive(int k) {
        active[k] = active[--activeCount];
    }

    private int activeSlot(int i) {
        for (int k = 0; k < activeCount; k++) if (active[k] == i) return k;
        return -1;
    }

    private float ownerX(int o) {
        return o >= 0 ? pX[o] : Float.NaN;
    }

    private float ownerY(int o) {
        return o >= 0 ? pY[o] : Float.NaN;
    }

    private static int ownerPointer(int o) {
        return o >= 0 ? o : PTR_ENGINE;
    }

    private static int ownerFlags(int o) {
        return o == OWNER_AUTO ? GameEvent.FLAG_AUTO : 0;
    }

    /**
     * True when (x, y) keeps hold n: inside its line's zone and within {@link #HOLD_REACH} of its widths of it; a
     * floating STREAM within {@link #FLOAT_HOLD_REACH} of its radius at the layout's beat.
     */
    private static boolean onHold(Note n, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        if (n.floats()) return FloatSpace.reach(lay, n.floating(), x, y) <= FLOAT_HOLD_REACH;
        if (Math.abs(y - lineY(n.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        return Math.abs(x - lay.noteX(n)) <= HOLD_REACH * lay.judgeWidth(n);
    }

    // ------------------------------------------------------------------ mines and taps

    /**
     * Mines and taps (Kelvin 2026-10-31) and LANE MINE priority (Kelvin 2026-10-14), see the class doc: pairs every
     * MINE with the taps / STREAM heads of its line in its place (at the note's beat) whose ±TIER2 window meets its
     * window, and every LANE MINE with those whose RESONANCE window meets its block, then sets how long each note can
     * be judged. A pair with a floating mine or note ({@link #spatial}) meets in time only: where it is cut apart is
     * up to the press ({@link #echoCut}, {@link #asleep}). Called by {@link #load}; allocates only for a chart with
     * more such pairs than any before.
     */
    private void findShadows(int n) {
        Arrays.fill(noEcho, 0, n, false);
        Arrays.fill(cutLen, 0, n, 0);
        Arrays.fill(shadowLen, 0, n, 0);
        int total = mineCount + laneMineCount;
        for (int pass = 0, pairs = 0; pass < 2; pass++) {
            int at = 0;
            for (int k = 0; k < total; k++) {
                Note m = k < mineCount ? mines[k] : laneMineArr[k - mineCount];
                boolean laneMine = m.isBlockMine();
                double lo = laneMine ? m.judgementMs() - LANE_MINE_LEAD_MS : m.judgementMs() - MINE_WINDOW_MS;
                double hi = laneMine ? m.endMs() : m.judgementMs() + MINE_WINDOW_MS;
                double w = laneMine ? TIER1_MS : TIER2_MS;
                boolean tide = m.isTideMine();
                int mi = m.index();
                shadowFrom[mi] = at;
                for (int r = 0; r < 6; r++) {
                    Note[] arr = shadowSource(m, r);
                    if (arr == null) continue;
                    for (Note u : arr) {
                        double T = u.judgementMs();
                        if (T + w < lo) continue;
                        if (T - w > hi) break;
                        if (laneMine && T - w >= hi) continue; // a LANE MINE's span is [T, end)
                        boolean spatial = spatial(m, u);
                        if (!spatial && !tide && !samePlace(m, u)) continue;
                        boolean cut = !laneMine || spatial; // a floating pair keeps its ECHO away from the mine
                        int ui = u.index();
                        if (pass == 0) {
                            pairs++;
                            if (cut) cutLen[ui]++;
                            continue;
                        }
                        shadowNote[at++] = ui;
                        shadowLen[mi]++;
                        if (cut) cutMine[cutFrom[ui] + cutLen[ui]++] = mi;
                        else noEcho[ui] = true;
                    }
                }
            }
            if (pass == 0) {
                if (shadowNote.length < pairs) shadowNote = new int[pairs];
                if (cutMine.length < pairs) cutMine = new int[pairs];
                for (int i = 0, from = 0; i < n; i++) {
                    cutFrom[i] = from;
                    from += cutLen[i];
                    cutLen[i] = 0;
                }
            }
        }
        for (int i = 0; i < n; i++) lateWin[i] = windowEnd(i);
    }

    /**
     * The r-th stream of notes mine m pairs with ({@link #findShadows}), or null. A mine on a line: its line's taps /
     * STREAM heads, its TIDEs for a TIDE MINE (it covers the whole line), its DRAGs for a LANE MINE or TIDE STREAM MINE
     * (a plain MINE needs a press, a DRAG none), then the floating taps / heads (and DRAGs). A floating mine: those of
     * both lines and the floating ones, never a TIDE.
     */
    private Note[] shadowSource(Note m, int r) {
        boolean block = m.isBlockMine();
        if (m.floats()) {
            if (r < 2) return lineStream[r];
            if (r == 2) return floatStream;
            if (!block) return null;
            return r < 5 ? dragStream[r - 3] : floatDragStream;
        }
        int l = m.line().ordinal();
        switch (r) {
            case 0: return lineStream[l];
            case 1: return m.isTideMine() ? uniStream[l] : null;
            case 2: return block ? dragStream[l] : null;
            case 3: return floatStream;
            case 4: return block ? floatDragStream : null;
            default: return null;
        }
    }

    /** @return true when mine m or note u floats: where they are cut apart is up to the press (Kelvin 15:01). */
    private static boolean spatial(Note m, Note u) {
        return m.floats() || u.floats();
    }

    /** @return true when mine (or note) {@code m} is in the same place as tap / hold {@code u} at u's beat. */
    private static boolean samePlace(Note m, Note u) {
        float b = u.judgement();
        return samePlace(Math.abs(m.xAt(b) - u.xAt(b)), Lane.DEFAULT_WIDTH, Lane.DEFAULT_WIDTH);
    }

    /** How long after its time note i can still be judged: TIER2, less where MINE windows cover its late ECHO. */
    private float windowEnd(int i) {
        if (noEcho[i]) return TIER1_MS;
        double T = notes[i].judgementMs();
        double e = T + TIER2_MS;
        for (boolean moved = true; moved && e > T + TIER1_MS; ) {
            moved = false;
            for (int k = cutFrom[i], end = k + cutLen[i]; k < end; k++) {
                if (spatial(notes[cutMine[k]], notes[i])) continue; // the ECHO away from it stays
                double tm = notes[cutMine[k]].judgementMs();
                if (e > tm - MINE_WINDOW_MS && e <= tm + MINE_WINDOW_MS) {
                    e = tm - MINE_WINDOW_MS;
                    moved = true;
                }
            }
        }
        return (float) Math.max(TIER1_MS, e - T);
    }

    /**
     * True when a press at t is inside note i's judge window: its RESONANCE window, or the rest of ±TIER2 outside
     * the windows of the MINEs that cut it (none when a LANE MINE gives way to the note); floating pairs aside
     * ({@link #inWindowAt}).
     */
    private boolean inWindow(int i, double t) {
        double d = t - notes[i].judgementMs();
        if (d >= -TIER1_MS && d <= TIER1_MS) return true;
        if (noEcho[i] || d < -TIER2_MS || d > TIER2_MS) return false;
        for (int k = cutFrom[i], e = k + cutLen[i]; k < e; k++) {
            Note m = notes[cutMine[k]];
            if (!spatial(m, notes[i]) && Math.abs(t - m.judgementMs()) <= MINE_WINDOW_MS) return false;
        }
        return true;
    }

    /** {@link #inWindow} for a press at (x, y) with the event's geometry, less the ECHO {@link #echoCut} takes. */
    private boolean inWindowAt(int i, double t, float x, float y) {
        if (!inWindow(i, t)) return false;
        double d = t - notes[i].judgementMs();
        return d >= -TIER1_MS && d <= TIER1_MS || !echoCut(i, x, y, t, inputLayout, evLowerY, evUpperY);
    }

    /**
     * Floating pairs (Kelvin 2026-11-06 15:01): true when (x, y) at t is over a mine that takes note i's ECHO there, a
     * mine paired with it ({@link #spatial}) that is a MINE whose window holds t or a LANE MINE (it gives way to the
     * note); elsewhere the note keeps its ECHO.
     */
    private boolean echoCut(int i, float x, float y, double t, LaneLayout lay, float lowerY, float upperY) {
        for (int k = cutFrom[i], e = k + cutLen[i]; k < e; k++) {
            Note m = notes[cutMine[k]];
            if (!spatial(m, notes[i])) continue;
            if (m.isBlockMine() ? onLaneMine(m, x, y, lay, lowerY, upperY)
                : Math.abs(t - m.judgementMs()) <= MINE_WINDOW_MS && onMine(m, x, y, lay, lowerY, upperY)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True while mine i is off at t for a press or finger at (x, y): inside the RESONANCE window of a note it is
     * paired with (cut from a MINE's window; a LANE MINE sleeps there; for a floating pair only where (x, y) is over
     * the note), or, for a LANE MINE, inside its RESONANCE guard (its line's, for a TIDE STREAM MINE).
     */
    private boolean asleep(int i, double t, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        Note m = notes[i];
        for (int k = shadowFrom[i], e = k + shadowLen[i]; k < e; k++) {
            Note u = notes[shadowNote[k]];
            double T = u.judgementMs();
            if (t < T - TIER1_MS || t > T + TIER1_MS) continue;
            if (!spatial(m, u) || onNote(u, x, y, lay, lowerY, upperY)) return true;
        }
        if (!m.isBlockMine()) return false;
        int s = m.isTideMine() ? notes.length + m.line().ordinal() : i; // a TIDE STREAM MINE: its line's guard
        return t >= guardFrom[s] && t <= guardTo[s];
    }

    /**
     * RESONANCE guard (Kelvin 2026-10-30): a RESONANCE at t on a note a LANE MINE gives way to keeps the LANE MINEs
     * of its line in its place asleep until t + RESONANCE_GUARD_MS, and its line's TIDE STREAM MINEs; a guard still
     * running is extended.
     */
    private void guardLane(Note n, double t) {
        if (n.floats()) return; // no line: no LANE MINE to guard
        if (n.hasX()) {
            double until = t + RESONANCE_GUARD_MS;
            for (int k = laneMineLo; k < laneMineCount; k++) {
                Note m = laneMineArr[k];
                if (m.judgementMs() - LANE_MINE_LEAD_MS > until) break;
                if (m.line() == n.line() && !m.isTideMine() && t < m.endMs() && samePlace(m, n)) guard(m.index(), t);
            }
        }
        guard(notes.length + n.line().ordinal(), t); // and its line's TIDE STREAM MINEs
    }

    /**
     * The RESONANCE guard of the floating pairs: a RESONANCE on note i at t whose press (or catching finger) is at
     * (x, y) guards the LANE MINEs paired with it ({@link #spatial}) that (x, y) is over.
     */
    private void guardAt(int i, float x, float y, double t, LaneLayout lay, float lowerY, float upperY) {
        for (int k = cutFrom[i], e = k + cutLen[i]; k < e; k++) {
            Note m = notes[cutMine[k]];
            if (!m.isBlockMine() || !spatial(m, notes[i]) || !onLaneMine(m, x, y, lay, lowerY, upperY)) continue;
            guard(m.isTideMine() ? notes.length + m.line().ordinal() : m.index(), t);
        }
    }

    private void guard(int s, double t) {
        if (!(t <= guardTo[s])) guardFrom[s] = t;
        double end = t + RESONANCE_GUARD_MS;
        if (!(end <= guardTo[s])) guardTo[s] = end;
    }

    /**
     * @return how long after its time note i can still be judged (ms): TIER2, less where MINE windows cut its late
     *         ECHO, TIER1 when a LANE MINE gives way to it
     */
    public float judgeWindowMs(int i) {
        return lateWin[i];
    }

    // ------------------------------------------------------------------ mines

    private static boolean onMine(Note m, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        if (m.floats()) return FloatSpace.reach(lay, m.floating(), x, y) <= 1f; // inside its circle
        if (Math.abs(y - lineY(m.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        if (m.isTideMine()) return true; // anywhere in its line's zone, as a TIDE
        return Math.abs(x - lay.noteX(m)) <= MINE_BOX * 0.5f * lay.judgeWidth(m);
    }

    /** Mines under a pointer event at t: MINEs only on a press (touch-down), LANE MINEs on every event. */
    private void checkMinesAt(double t, float x, float y, int pointer, boolean press) {
        for (int k = mineLo; press && k < mineCount; k++) {
            Note m = mines[k];
            double T = m.judgementMs();
            if (T - MINE_WINDOW_MS > t) break;
            int i = m.index();
            if (state[i] != STATE_PENDING || t > T + MINE_WINDOW_MS) continue;
            if (onMine(m, x, y, inputLayout, evLowerY, evUpperY)
                && !asleep(i, t, x, y, inputLayout, evLowerY, evUpperY)) {
                touchMine(i, x, y, pointer, t, inputLayout, evLowerY, evUpperY, 0);
            }
        }
        for (int k = laneMineLo; k < laneMineCount; k++) {
            Note m = laneMineArr[k];
            if (m.judgementMs() - LANE_MINE_LEAD_MS > t) break;
            if (t >= m.endMs()) continue;
            observePointer(m.index(), pointer, onLaneMine(m, x, y, inputLayout, evLowerY, evUpperY), x, y, t,
                inputLayout, evLowerY, evUpperY);
        }
    }

    // ------------------------------------------------------------------ LANE MINEs

    /**
     * True when (x, y) is in a LANE MINE's contact box: inside its line's zone and inside the
     * central {@link #LANE_MINE_BOX} of its current judging width (edges included; one lane in 定轨); a floating one's
     * circle.
     */
    private static boolean onLaneMine(Note m, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        if (m.floats()) return FloatSpace.reach(lay, m.floating(), x, y) <= 1f;
        if (Math.abs(y - lineY(m.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        if (m.isTideMine()) return true; // a TIDE STREAM MINE: its line's whole zone
        return Math.abs(x - lay.noteX(m)) <= LANE_MINE_BOX * 0.5f * lay.judgeWidth(m);
    }

    /**
     * Frame pass over the LANE MINEs (frame time t): ends the ones whose block is over (with the
     * sweep slack, so an input event stamped just before the end still counts), then observes the
     * resting pointers of the blocking ones, so a finger already in place
     * when a block starts (or a mine moving under a resting finger) triggers once.
     */
    private void updateLaneMines(double t, double sweepT) {
        while (laneMineLo < laneMineCount && laneMineArr[laneMineLo].endMs() <= sweepT) {
            endLaneMine(laneMineArr[laneMineLo].index());
            laneMineLo++;
        }
        for (int k = laneMineLo; k < laneMineCount; k++) {
            Note m = laneMineArr[k];
            if (m.judgementMs() - LANE_MINE_LEAD_MS > t) break;
            int i = m.index();
            if (m.endMs() <= sweepT) {
                endLaneMine(i);
                continue;
            }
            if (autoplay || t >= m.endMs()) continue;
            for (int p = 0; p < MAX_POINTERS; p++) {
                if (!pDown[p] || pVeiled[p]) continue;
                observePointer(i, p, onLaneMine(m, pX[p], pY[p], frameLayout, frameLowerY, frameUpperY), pX[p], pY[p],
                    t, frameLayout, frameLowerY, frameUpperY);
            }
        }
    }

    /** One observation of pointer p for LANE MINE i at time t: entering its box is a contact. */
    private void observePointer(int i, int p, boolean inside, float x, float y, double t, LaneLayout lay,
                                float lowerY, float upperY) {
        int bit = 1 << p;
        if (!inside) {
            minePtrMask[i] &= ~bit;
            return;
        }
        if ((minePtrMask[i] & bit) != 0) return;
        if (asleep(i, t, x, y, lay, lowerY, upperY)) return; // giving way: a finger still inside counts when it wakes
        minePtrMask[i] |= bit;
        laneMineContact(i, x, y, p, t, lay, lowerY, upperY, 0);
    }

    /**
     * A LANE MINE contact: the first one counts the mine as touched ({@link ScoreKeeper#mineTouched()}),
     * later ones only break the combo again ({@link ScoreKeeper#laneMineContact()}); each emits one
     * {@link GameEvent#MINE} with {@link GameEvent#FLAG_LANE_MINE} and the contact number as count.
     */
    private void laneMineContact(int i, float x, float y, int pointer, double t, LaneLayout lay, float lowerY,
                                 float upperY, int flags) {
        Note m = notes[i];
        int c = ++mineContacts[i];
        mineContactMs[i] = (float) t;
        mineEvents++;
        if (state[i] == STATE_PENDING) {
            state[i] = STATE_MINE_TOUCHED;
            resolvedMs[i] = (float) t;
            keeper.mineTouched();
        } else {
            keeper.laneMineContact();
        }
        emit(GameEvent.MINE, null, (float) (t - m.judgementMs()), x, lineY(m.line(), lowerY, upperY), x, y,
            m.laneIndex(), m.line(), i, c, pointer, t, flags | GameEvent.FLAG_LANE_MINE);
    }

    /** Ends LANE MINE i's block: untouched → passed (at its end); the in-box masks are cleared. */
    private void endLaneMine(int i) {
        if (state[i] == STATE_PENDING) {
            state[i] = STATE_MINE_PASSED;
            resolvedMs[i] = (float) notes[i].endMs();
        }
        minePtrMask[i] = 0;
    }

    /** Pointer p went up (or was replaced): it no longer counts as inside any LANE MINE box. */
    private void forgetLaneMinePointer(int p) {
        int keep = ~(1 << p);
        for (int k = laneMineLo; k < laneMineCount; k++) minePtrMask[laneMineArr[k].index()] &= keep;
    }

    private void touchMine(int i, float x, float y, int pointer, double t, LaneLayout lay, float lowerY,
                           float upperY, int flags) {
        Note m = notes[i];
        state[i] = STATE_MINE_TOUCHED;
        resolvedMs[i] = (float) t;
        mineEvents++;
        keeper.mineTouched();
        emit(GameEvent.MINE, null, (float) (t - m.judgementMs()), x, lineY(m.line(), lowerY, upperY), x, y,
            m.laneIndex(), m.line(), i, 1, pointer, t, flags);
    }

    // ------------------------------------------------------------------ LOST

    private void loseNote(int i) {
        Note n = notes[i];
        state[i] = STATE_LOST;
        grade[i] = (byte) Judgement.LOST.ordinal();
        double at = n.judgementMs() + lateWin[i];
        resolvedMs[i] = (float) at;
        keeper.record(Judgement.LOST, 0f);
        float x = frameLayout.noteX(n);
        float y = lineY(n.line(), frameLowerY, frameUpperY);
        emit(GameEvent.LOST, Judgement.LOST, 0f, x, y, x, y, n.laneIndex(), n.line(), i, 1, PTR_ENGINE, at,
            n.type() == NoteType.DRAG ? GameEvent.FLAG_DRAG : 0);
    }

    private void loseGroup(Note n) {
        Note[] arr = uniStream[n.line().ordinal()];
        int from = n.groupStart();
        int to = from + n.groupSize();
        int count = 0;
        int streams = 0;
        double at = n.judgementMs() + TIER2_MS;
        for (int c = from; c < to; c++) {
            int i = arr[c].index();
            if (state[i] != STATE_PENDING) continue;
            state[i] = STATE_LOST;
            grade[i] = (byte) Judgement.LOST.ordinal();
            resolvedMs[i] = (float) at;
            keeper.record(Judgement.LOST, 0f);
            if (arr[c].isTideStream()) streams++;
            count++;
        }
        float x = (frameLayout.windowLeft() + frameLayout.windowRight()) * 0.5f;
        float y = lineY(n.line(), frameLowerY, frameUpperY);
        emit(GameEvent.LOST, Judgement.LOST, 0f, x, y, x, y, -1, n.line(), arr[from].index(), count, PTR_ENGINE, at,
            GameEvent.FLAG_UNIVERSAL | (streams > 0 ? GameEvent.FLAG_TIDE_STREAM : 0));
    }

    // ================================================================== events

    private void clearEvents() {
        evHead = 0;
        evSize = 0;
        evDropped = 0;
    }

    private void emitEmpty(float x, float y, int line, int pointer, double t, int flags) {
        emit(GameEvent.EMPTY, null, 0f, x, y, x, y, -1, line >= 0 ? Line.of(line) : null, -1, 0, pointer, t, flags);
    }

    private void emitHold(int type, int i, float x, float y, int pointer, double t, int flags) {
        Note n = notes[i];
        emit(type, Judgement.byOrdinal(grade[i]), dt[i], x, y, x, y, n.laneIndex(), n.line(), i, 1, pointer, t, flags);
    }

    /**
     * Adds one event to the ring (the oldest is dropped when it is full). A floating note gets its place instead of
     * {@code x}, {@code y} and {@code line} (centre and diameter with the frame's geometry), and
     * {@link GameEvent#FLAG_FLOAT}.
     *
     * @return the event's slot
     */
    private int emit(int type, Judgement j, float d, float x, float y, float touchX, float touchY, int lane,
                     Line line, int note, int count, int pointer, double t, int flags) {
        float width;
        if (note >= 0 && notes[note].floats()) {
            Floating f = notes[note].floating();
            x = FloatSpace.worldX(frameLayout, f);
            y = FloatSpace.worldY(frameLayout, f);
            width = 2f * FloatSpace.radius(frameLayout, f);
            line = null;
            flags |= GameEvent.FLAG_FLOAT;
        } else {
            // world width of the key (a bare touch: one key's column; TIDE notes: none)
            width = note >= 0 ? notes[note].hasX() ? frameLayout.noteWidth(notes[note]) : 0f
                : type == GameEvent.EMPTY ? frameLayout.defaultWidth() : 0f;
        }
        int slot;
        if (evSize == EVENT_CAPACITY) {
            slot = evHead;
            evHead = (evHead + 1) & (EVENT_CAPACITY - 1);
            evDropped++;
        } else {
            slot = (evHead + evSize) & (EVENT_CAPACITY - 1);
            evSize++;
        }
        evType[slot] = type;
        evGrade[slot] = (byte) (j != null ? j.ordinal() : -1);
        evDt[slot] = d;
        evX[slot] = x;
        evY[slot] = y;
        evTouchX[slot] = Float.isNaN(touchX) ? x : touchX;
        evTouchY[slot] = Float.isNaN(touchY) ? y : touchY;
        evLane[slot] = lane;
        evWidth[slot] = width;
        evLine[slot] = (byte) (line != null ? line.ordinal() : -1);
        evNote[slot] = note;
        evCount[slot] = count;
        evPointer[slot] = pointer;
        evTime[slot] = t;
        evFlags[slot] = flags;
        return slot;
    }

    /**
     * Moves the oldest pending event into {@code out}.
     *
     * @return false when there are no more events
     */
    public boolean pollEvent(GameEvent out) {
        if (evSize == 0) return false;
        int s = evHead;
        evHead = (evHead + 1) & (EVENT_CAPACITY - 1);
        evSize--;
        out.type = evType[s];
        out.judgement = evGrade[s] >= 0 ? Judgement.byOrdinal(evGrade[s]) : null;
        out.dtMs = evDt[s];
        out.x = evX[s];
        out.y = evY[s];
        out.touchX = evTouchX[s];
        out.touchY = evTouchY[s];
        out.lane = evLane[s];
        out.width = evWidth[s];
        out.line = evLine[s] >= 0 ? Line.of(evLine[s]) : null;
        out.note = evNote[s];
        out.count = evCount[s];
        out.pointer = evPointer[s];
        out.timeMs = evTime[s];
        out.flags = evFlags[s];
        return true;
    }

    /** @return events waiting in the ring. */
    public int pendingEvents() {
        return evSize;
    }

    /** @return events dropped because the ring was full (the consumer fell behind). */
    public int droppedEvents() {
        return evDropped;
    }

    // ================================================================== helpers

    private static float lineY(Line line, float lowerY, float upperY) {
        return line == Line.UPPER ? upperY : lowerY;
    }

    /**
     * @return how far y is from line l's zone at judge time t (world): from the line at {@code ly}, or from a pending
     *         TIDE of that line whose window holds t, where it lands ({@link #tideLand}) when that is nearer. A TIDE
     *         that lands away from its line is a judgement line too (Kelvin 2026-10-16: 「有时候tide也能充当判定线」;
     *         tap where the notes meet it; the tutorial taught it as L12 until 2026-11-06), so a press there judges
     *         them even where it is outside the line's own zone.
     */
    private float zoneDist(int l, float y, float ly, double t) {
        float d = Math.abs(y - ly);
        Note[] arr = uniStream[l];
        for (int c = uniCursor[l]; c < arr.length; c++) {
            Note n = arr[c];
            if (n.judgementMs() - TIER2_MS > t) break;
            int i = n.index();
            float land = tideLand[i];
            if (land == 0f || state[i] != STATE_PENDING || !inWindow(i, t)) continue;
            float d2 = Math.abs(y - (l == Line.UPPER.ordinal() ? ly - Field.dist(land) : ly + Field.dist(land)));
            if (d2 < d) d = d2;
        }
        return d;
    }

    /**
     * @return where note n lands: its distance from its line just before its time ({@link #LAND_PROBE_BEATS}), in
     *         reference units; 0 when that is within 1 of the line or the note is out of sight then
     */
    static float landing(Note n) {
        float p = n.positionAt(n.judgement() - LAND_PROBE_BEATS);
        return Math.abs(p) >= Note.HIDDEN_POSITION - 1f || Math.abs(p) < 1f ? 0f : p;
    }

    /** @return the ordinal of the line whose zone contains y (the closer one if both), or −1. */
    private static int zoneLine(float y, float lowerY, float upperY) {
        float dl = Math.abs(y - lowerY);
        float du = Math.abs(y - upperY);
        boolean l = dl <= Field.zoneHalf();
        boolean u = du <= Field.zoneHalf();
        if (l && u) return du < dl ? Line.UPPER.ordinal() : Line.LOWER.ordinal();
        if (l) return Line.LOWER.ordinal();
        if (u) return Line.UPPER.ordinal();
        return -1;
    }

    // ================================================================== accessors

    /** @return the chart in play, or null. */
    public Chart chart() {
        return chart;
    }

    /** @return true for an autoplay run. */
    public boolean autoplay() {
        return autoplay;
    }

    /** @return the current phase. */
    public Phase phase() {
        return phase;
    }

    /** @return true while time runs and input is judged. */
    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** @return true while paused. */
    public boolean isPaused() {
        return phase == Phase.PAUSED;
    }

    /** @return true once the run is over (build the result). */
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    /** @return the score keeper of the run (read it; do not mutate). */
    public ScoreKeeper scores() {
        return keeper;
    }

    /** @return song ms where the clock starts (0, or negative when a note is drawn before song time 0). */
    public float leadInMs() {
        return leadInMs;
    }

    /** @return the audio offset in ms. */
    public float audioOffsetMs() {
        return audioOffsetMs;
    }

    /** @return the visual offset in ms. */
    public float visualOffsetMs() {
        return visualOffsetMs;
    }

    /** @return the judge time of the last frame (song ms, audio offset applied). */
    public double judgeMs() {
        return frameJudgeMs;
    }

    /** @return the visual time of the last frame (song ms): draw notes at this time. */
    public double visualMs() {
        return frameVisualMs;
    }

    /** @return {@link #visualMs()} in beats. */
    public float visualBeat() {
        return frameBeat;
    }

    /** @return end of the last note (hold ends and mines included), song ms. */
    public double lastNoteEndMs() {
        return lastEndMs;
    }

    /**
     * @return judge time (song ms, audio offset applied) at which the run is over: the later of the last
     *         note end and the music end (Kelvin 2026-10-16); without a known music length, the last note end
     */
    public double endMs() {
        double end = lastEndMs;
        float dur = clock.durationMs();
        if (dur > 0f) end = Math.max(end, dur - audioOffsetMs);
        return end;
    }

    /** @return judge time at which the run finishes: the finish delay after {@link #endMs()}. */
    public double finishAtMs() {
        return endMs() + finishDelayMs;
    }

    /**
     * Sets the wait between {@link #endMs()} and the finish (default {@code Params.FINISH_DELAY_MS}); the
     * screen lengthens it for the end banner ({@code Params.BANNER_FINISH_DELAY_MS}). Kept across loads.
     */
    public void setFinishDelayMs(float ms) {
        finishDelayMs = ms >= 0f && ms < Float.POSITIVE_INFINITY ? ms : Params.FINISH_DELAY_MS;
    }

    /** @return 0..1 progress from song time 0 to the last note end (for the progress hairline). */
    public float progress() {
        if (lastEndMs <= 0) return frameJudgeMs >= 0 ? 1f : 0f;
        double p = frameJudgeMs / lastEndMs;
        return p < 0 ? 0f : p > 1 ? 1f : (float) p;
    }

    /**
     * @return the layout at the last frame's visual time (shared; do not update it): where the keys are
     *         ({@link LaneLayout#noteX}, {@link LaneLayout#noteWidth}) and the TIDE pattern
     */
    public LaneLayout layout() {
        return frameLayout;
    }

    /** @return y of {@code line} at the last frame's visual time. */
    public float lineY(Line line) {
        return line == Line.UPPER ? frameUpperY : frameLowerY;
    }

    /** @return the state of note i ({@code STATE_*}). */
    public byte noteState(int i) {
        return state[i];
    }

    /** @return the grade of note i (hold: head grade while running), or null when not judged. */
    public Judgement noteJudgement(int i) {
        return grade[i] >= 0 ? Judgement.byOrdinal(grade[i]) : null;
    }

    /** @return the signed timing error of note i (head for holds), ms. */
    public float noteDtMs(int i) {
        return dt[i];
    }

    /**
     * @return judge time (song ms) at which note i got its current state: hit, hold start then
     *         hold end, LOST (time + TIER2 for misses), mine touched / passed (T + MINE_WINDOW_MS);
     *         a LANE MINE's first contact, or its end when it passed untouched; NaN while pending
     */
    public float noteResolvedMs(int i) {
        return resolvedMs[i];
    }

    /** @return number of contacts so far with LANE MINE i (0 for every other note). */
    public int mineContacts(int i) {
        return mineContacts[i];
    }

    /** @return judge time (song ms) of the last contact with LANE MINE i, or NaN when none. */
    public float mineLastContactMs(int i) {
        return mineContactMs[i];
    }

    /**
     * @return true while hold i runs and its finger (or autoplay) is on it; for a TIDE
     *         STREAM, while it runs and has a finger of its own on its line
     */
    public boolean isHoldHeld(int i) {
        return state[i] == STATE_HOLDING && holdOwner[i] != OWNER_DETACHED && Float.isNaN(holdOffSince[i]);
    }

    /**
     * @return true while a TIDE STREAM of {@code line} runs and is held (it has a finger, or
     *         autoplay): the tide level of DESIGN §19.2 stays up
     */
    public boolean tideHeld(Line line) {
        for (int k = 0; k < activeCount; k++) {
            int i = active[k];
            if (notes[i].line() == line && notes[i].isTideStream() && isHoldHeld(i)) return true;
        }
        return false;
    }

    /** @return true while a TIDE STREAM of {@code line} runs (held or in a lapse). */
    public boolean tideRunning(Line line) {
        for (int k = 0; k < activeCount; k++) {
            int i = active[k];
            if (notes[i].line() == line && notes[i].isTideStream()) return true;
        }
        return false;
    }

    /** @return number of holds currently running. */
    public int activeHoldCount() {
        return activeCount;
    }

    /** @return note index of running hold k (0..{@link #activeHoldCount()}−1). */
    public int activeHold(int k) {
        return active[k];
    }

    /** @return true while pointer p is down. */
    public boolean pointerDown(int p) {
        return pDown[p];
    }

    /** @return true while pointer p is down and went down inside a veil (it judges nothing until it lifts). */
    public boolean pointerVeiled(int p) {
        return pDown[p] && pVeiled[p];
    }

    /** @return the chart's veils, in chart order (never null; do not modify). */
    public Veil[] veils() {
        return veils;
    }

    /** @return the chart's curtains (水帘: blur only, never judged), in chart order (never null; do not modify). */
    public Veil[] curtains() {
        return curtains;
    }

    /** @return last world x of pointer p. */
    public float pointerX(int p) {
        return pX[p];
    }

    /** @return last world y of pointer p. */
    public float pointerY(int p) {
        return pY[p];
    }

    /** @return the zone line of pointer p at the last frame, or null (outside both zones / up). */
    public Line pointerLine(int p) {
        return pLine[p] >= 0 ? Line.of(pLine[p]) : null;
    }

    /** @return the hold note index pointer p is holding, or −1. */
    public int pointerHold(int p) {
        return pHold[p];
    }

    /** @return the grade judged by the last touchDown, or null. */
    public Judgement lastInputJudgement() {
        return lastJudgement;
    }

    /** @return zone line of the last touchDown, or null (stray). */
    public Line lastInputLine() {
        return lastInputLine;
    }

    /**
     * @return true when the last touchDown touched a MINE or made a LANE MINE contact (the pause
     *         control does not count such a down, even when it judged nothing)
     */
    public boolean lastInputMine() {
        return lastInputMine;
    }

    /**
     * @return true when the last touchDown caught a DRAG (early, or judged at once): the pause control does not count
     *         such a down either
     */
    public boolean lastInputCaught() {
        return lastInputCaught;
    }

    /** @return when DRAG i was caught before its time (judge ms), or NaN (not caught, judged, or not a DRAG) */
    public float dragCaughtMs(int i) {
        return state[i] == STATE_PENDING ? caughtMs[i] : Float.NaN;
    }

    /**
     * Copies the run's statistics into a result builder: score, accuracy, counts, early/late,
     * mean offset, histogram, max combo, FC/AR flags, mines, autoplay and the play time.
     *
     * @return {@code b}
     */
    public PlayResult.Builder fillResult(PlayResult.Builder b) {
        b.scores(keeper).mines(chart != null ? chart.totalMines() : 0, keeper.minesTouched()).autoplay(autoplay);
        b.playTimeMs(Math.max(0L, (long) (frameJudgeMs - leadInMs)));
        return b;
    }
}
