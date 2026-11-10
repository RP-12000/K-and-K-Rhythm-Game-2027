package org.kelvinizer.gameplay;

import org.kelvinizer.Params;
import org.kelvinizer.audio.SongClock;
import org.kelvinizer.elements.Channels;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Sea;
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
 * {@link SongClock.TimeSource}.
 *
 * <h2>Time</h2>
 * One {@link SongClock} (audio-anchored) drives everything. With the user offsets
 * {@code A = audioOffsetMs} and {@code V = visualOffsetMs}:
 * <ul>
 *   <li>judge time of an input event = {@code clock.timeAtNanos(eventNanos) − A} (the event's own
 *       timestamp, never the frame time);</li>
 *   <li>frame judge time = {@code clock.nowMs() − A} (LOST sweep, holds, mines, autoplay);</li>
 *   <li>visual time = judge time + V: positive V draws notes earlier (compensates a display that
 *       shows frames late). Line y and lane geometry for drawing and for hit-testing are evaluated
 *       at the visual time of the frame or event, i.e. where the player saw them, and stretched to
 *       the screen by {@link Field} (line y with {@code ky}, window bounds with {@code kx}).</li>
 * </ul>
 *
 * <h2>Judging (one tap judges one target, a TIDE group counts as one; plus its sync partner where the lines are
 * close)</h2>
 * <ul>
 *   <li><b>Whole columns</b> (Kelvin 2026-11-07 04:14 「改回垂直判定」, 04:18 「整列」): a note's column counts at any
 *       height and the two lines no longer split the screen (was half the screen each, {@code |y − lineY| ≤ 480·ky}),
 *       so every touch looks at the notes of both lines, wherever it is. Only the mines still touch only inside their
 *       own line's zone {@code |y − lineY| ≤ 480·ky} ({@link Field#zoneHalf}, 0.4 of the screen height). A touch
 *       inside both lines' zones (lines less than 960·ky apart) that judges a note also judges the other line's
 *       pending note in the same lane with exactly the same judgement beat, and that line's TIDE group at that beat
 *       ({@code judgeOtherLine}); anywhere else a sky + ground pair in one column takes two touches, as before.</li>
 *   <li>Candidates, on each line: the earliest unjudged tap/hold head with
 *       {@code |dt| ≤ TIER2} in the lane under x ({@link LaneLayout#indexAtX}); if that lane has
 *       none and x is within 30 % of the lane width of a boundary, the neighbouring lane; plus the
 *       earliest unjudged TIDE note of that line. The earliest judgement time wins; on a tie the
 *       smaller |dt|, then lane notes before TIDE, own lane before neighbour, the closer line, and
 *       finally the lower line (only when both lines coincide and everything else is equal).</li>
 *   <li><b>Keys placed by position</b> (Kelvin 2026-11-07 04:02: 「定轨状态支持小数轨道，然后加一个无轨时间和有轨时间」): a
 *       tap, STREAM or mine between two lanes (a decimal lane such as 1.5, or lane 0 without a lane 0) or written
 *       with its own x instead of a lane ({@link Note#free()}) is where {@link LaneLayout#keyCentre} puts it, as wide
 *       as {@link LaneLayout#keyWidth} (the two lanes' widths mixed; a default lane for an x). A touch within
 *       {@link #FREE_REACH} of that width of it is a candidate like one in its own lane (the nearest on a tie), one
 *       within {@link #DRAG_REACH} only when nothing in reach of the own lane has one, as a tap reaches into a
 *       neighbour lane; a lane key takes it when its lane's centre is within {@link #DRAG_REACH}. Its STREAM is kept
 *       within {@link #FREE_HOLD_REACH} widths (±1 lane), its MINE is touched inside {@link #MINE_BOX} of its width,
 *       its LANE MINE inside {@link #LANE_MINE_BOX}; where mines meet taps (below) it counts as in the lane under it
 *       at its time ({@code homeLane}).</li>
 *   <li>Taps / holds stacked on one line, lane and beat (allowed since Kelvin 2026-11-02: 「现在我们要放开这个限制」)
 *       are separate notes: a press judges one (the earliest pending, file order on a tie), so a stack of n
 *       takes n fingers; a key press judges one; autoplay judges each.</li>
 *   <li><b>Joint judging</b> (ARCHITECTURE §20, replacing the old "exact tie → the lane note, a
 *       second finger takes the TIDE" rule): when a tap or a lane key judges a lane note (DROP or
 *       STREAM head, own lane or neighbour) on line L at beat b, every unjudged TIDE note of L
 *       whose beat is exactly b is judged with it, with the same grade and Δt (each counts as its
 *       own note; its TIDE STREAMs then run on coverage). A tap that judges no lane note falls back
 *       to the TIDE notes alone, and the lane notes of that beat stay pending. Space never judges
 *       lane notes.</li>
 *   <li>A TIDE group (same line, identical beat) is judged entirely by the tap that takes it, TIDE
 *       drops and TIDE STREAMs alike; each TIDE STREAM of the group then runs on its own.</li>
 *   <li>Taps earlier than −TIER2 consume nothing; notes later than +TIER2 are swept LOST once per
 *       frame (with {@link #SWEEP_SLACK_MS} so events stamped just before the frame still count).</li>
 *   <li><b>Lane STREAMs belong to one finger (no switching hands, ARCHITECTURE §19).</b> The head
 *       is judged like a tap; the pointer (or key) that hit it owns the hold for its whole length
 *       and is then tracked. No other pointer or key ever takes it over: a second finger touching
 *       the lane only judges or misses other notes, and when the owner lifts (or its key goes up)
 *       before {@code end − HOLD_RELEASE_GRACE_MS}, or stays off its lane (±1 lane, at any height) for
 *       more than {@link #HOLD_LEAVE_MS}, the hold breaks (LOST) even if another finger rests on
 *       the lane. Otherwise it is counted with the head grade when it completes. Leaving at or after
 *       {@code end − HOLD_RELEASE_GRACE_MS} is treated like a release there (completes). A finger
 *       that left and is lifted before it comes back counts as released when it left, so lifting
 *       and staying down are judged the same. A pointer id reused after a lost touch-up releases
 *       the old hold first, so it never inherits it. The only exception is the pause: a pause never
 *       breaks a hold; a pointer or key lifted while paused detaches it, and any free pointer on it,
 *       or that lane's key down, within 100 ms after the resume re-grabs it (resuming needs a
 *       lifted finger).</li>
 *   <li><b>A gesture the system takes</b> ({@link #gestureCancelled}, Kelvin 2026-11-06: players reported dropped
 *       touches): Android cancels every finger on the screen and never says when they lift, so all of them are let
 *       go at once without judging anything, and every hold a finger held then (STREAM, TIDE STREAM) is held by the
 *       engine to its end. New fingers play as usual.</li>
 *   <li><b>TIDE STREAMs are held by coverage, one finger each, not by ownership.</b> The head is
 *       judged like a TIDE drop (any tap at any x and height; keyboard any key of the line's
 *       row, Space for the lower line). A line's fingers are all down pointers (any x and height, so one
 *       counts for both lines; pointers holding lane STREAMs included) plus the keys of its row that are
 *       down. Each running TIDE STREAM needs one of
 *       them (Kelvin 2026-10-23: two on one line need two fingers the whole time), and which finger
 *       is which does not matter, so swapping hands is free. When a line has fewer fingers than
 *       running TIDE STREAMs, the ones in their last {@code HOLD_RELEASE_GRACE_MS} go without first
 *       (that completes them), then the latest heads (then the highest index). Coverage is
 *       evaluated at every input event with its timestamp (down, drag, up, cancel, key down / up)
 *       and once per frame (resting pointers, moving lines). A lapse longer than
 *       {@link #HOLD_LEAVE_MS} that starts before
 *       {@code end − HOLD_RELEASE_GRACE_MS} breaks it (LOST at the lapse start + 100 ms, also when
 *       the next observation finds it covered again); a lapse that starts at or after that point
 *       completes it at once; reaching the end completes it with the head grade. A pause never
 *       breaks it; after the resume it has {@link #HOLD_LEAVE_MS} to be covered again. It is one note
 *       for score, combo and totals, like a hold. Autoplay completes it.</li>
 *   <li>Mines: a MINE is touched only by a press (Kelvin 2026-10-31: 「点击才检查」): a touch-down in the mine
 *       line's zone inside the central 70 % of the mine lane, or a key-down of the lane's key, within
 *       ±{@link #MINE_WINDOW_MS} of the mine (the ECHO window, ±133.33 ms; was ±66.67 ms) and outside the windows
 *       cut from it (below). A finger or key already down, a drag into the mine and a release never touch it, so a
 *       finger resting through a field of mines touches none of them. Each mine triggers at most once
 *       ({@link ScoreKeeper#mineTouched()}: combo 0, 10,000 off the score) and emits one
 *       {@link GameEvent#MINE}. Autoplay never touches mines.</li>
 *   <li><b>LANE MINEs</b> (ARCHITECTURE §20) block their lane on their line over
 *       {@code [T − LANE_MINE_LEAD_MS, end)} = {@code [T, end)}: only once they reach the line
 *       (Kelvin 2026-10-13; was {@code [T − 100 ms, end)}). Contact = a down pointer in that line's zone inside
 *       the central {@link #LANE_MINE_BOX} (80 %) of the lane's current span, or the lane's key down.
 *       Each contact triggers once: a touch-down in the box, a drag entering it, a pointer already
 *       resting in it when the block starts, a key press, or a key already held when it starts; a
 *       pointer that stays in the box triggers again only after leaving it and coming back. The
 *       first contact is {@link ScoreKeeper#mineTouched()}, later ones
 *       {@link ScoreKeeper#laneMineContact()} (combo 0 again; the MINES row counts the lane mine
 *       once). Every contact emits one {@link GameEvent#MINE} with {@link GameEvent#FLAG_LANE_MINE}.
 *       A LANE MINE never judges or consumes a tap; autoplay never touches it.</li>
 *   <li><b>TIDE MINEs</b> (Kelvin 2026-10-31: the mine forms of the TIDE): a TIDE MINE is a MINE whose touch area is
 *       its line's whole zone (any x; any key of its line, Space included) and whose window every tap, STREAM head
 *       and TIDE of its line cuts; a TIDE STREAM MINE is a LANE MINE over that whole zone (any key of its line held
 *       counts) that every note of its line meets gives way to, with one RESONANCE guard per line.</li>
 *   <li><b>Mines and taps</b> (Kelvin 2026-10-31): where a MINE's window {@code M = [T_m − MINE_WINDOW_MS,
 *       T_m + MINE_WINDOW_MS]} meets the ±TIER2 window of a tap or STREAM head in its own lane and line, the
 *       two are cut apart: the mine's window loses the note's RESONANCE window {@code R = [T − TIER1, T + TIER1]},
 *       R stays whole, and the note's ECHO window loses M. One press there either touches the mine or judges the
 *       note, never both; the note is LOST at the end of what is left of its window. TIDE notes are not affected
 *       (they can be hit anywhere on the line).</li>
 *   <li><b>LANE MINE priority</b> (Kelvin 2026-10-14): a tap or STREAM head whose RESONANCE window meets the
 *       block {@code [T, end)} of a LANE MINE in its own lane and line is judged first: the LANE MINE sleeps over
 *       that window (no contact of any finger or key there; one still in place when it wakes at
 *       {@code T + TIER1} is a contact) and the note has no ECHO (only RESONANCE inside ±TIER1, LOST at
 *       {@code T + TIER1}). A RESONANCE on such a note at {@code t} also keeps every LANE MINE in its lane and
 *       line asleep until {@code t + RESONANCE_GUARD_MS} (150 ms, Kelvin 2026-10-30); a finger or key still in
 *       place when that ends is a contact. A LOST gives no guard.</li>
 *   <li><b>DRAGs</b> (Kelvin 2026-11-05: 「加 Drag，手指在对应判定线就判」, 「手指提前放着或者滑过去都算，到点时手指在那儿就是共鸣，
 *       不在就是迷失，没有回响」; in a lane like a tap since 2026-11-08 02:51 「也上轨道」): never tapped and never
 *       taking a tap. A DRAG is at its lane's centre ({@link LaneLayout#keyCentre}, following the lane), its lane wide;
 *       one with its own x (on a sea, or written so) is where that x puts it ({@link Note#xAt}, moved and scaled with
 *       the lanes' camera frame and scale, {@link LaneLayout#freeX}), a default lane wide. A finger catches it
 *       when it is down (not pressed inside a veil) at any height within {@link #DRAG_REACH} of that width of its
 *       x (as far as a tap reaches into a neighbour lane), and so does a key of its line held down whose lane's centre
 *       is that near (a finger there), at any moment from {@code T − TIER2} to the end of its window (+TIER2, +TIER1
 *       when a LANE MINE gives way to it): every input event and every frame looks. Caught before T (a finger resting
 *       there early, or sliding through) it is a RESONANCE at T; caught at or after T it is a RESONANCE at once; never
 *       caught, it is LOST when its window ends. It has no timing of its own: Δt 0, counted PERFECT, outside the
 *       early / late statistics ({@link ScoreKeeper#recordDrag()}). Any finger catches it, also one holding a STREAM
 *       or tapping another note, and catching consumes nothing. A catch before T emits {@link GameEvent#CATCH} (for
 *       a timed hit sound); the judgement emits a {@link GameEvent#HIT} with {@link GameEvent#FLAG_DRAG}; its events
 *       carry the lane under its x (−1 off the lanes). A LANE MINE gives way to the DRAGs in its lane (one with its own
 *       x: where it is at its time) as to a tap (a TIDE STREAM MINE to every DRAG of its line); plain MINEs and
 *       DRAGs do not cut each other (a DRAG needs no press).</li>
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
 * <p>Per-lane cursors keep every input and frame O(lanes + notes near now); nothing allocates
 * after {@link #load}.
 */
public final class GameEngine {
    /** RESONANCE window half width (ms). */
    public static final float TIER1_MS = Params.TIER1_MS;
    /** ECHO window half width (ms); beyond it a note is not judgeable. */
    public static final float TIER2_MS = Params.TIER2_MS;
    /** Neighbour-lane tolerance as a fraction of the touched lane's current width. */
    public static final float NEIGHBOUR_TOLERANCE = 0.30f;
    /**
     * How far from its x a finger catches a DRAG, in its widths (its lane's; a default lane's at its own x): as far as
     * a tap reaches; also
     * how far a key placed by position is taken when nothing nearer is ({@link Note#free()}).
     */
    public static final float DRAG_REACH = 0.5f + NEIGHBOUR_TOLERANCE;
    /** How far from it a touch takes a key placed by position like one in its own lane, in its widths. */
    public static final float FREE_REACH = 0.5f;
    /** How far from it a finger keeps a STREAM placed by position, in its widths (±1 lane). */
    public static final float FREE_HOLD_REACH = 1.5f;
    /**
     * A key on a sea is taken this many times as far as {@link #FREE_REACH}, {@link #DRAG_REACH} and
     * {@link #FREE_HOLD_REACH} say; its mines are not (Kelvin 2026-11-08 19:37「斜着的平面的垂直判定效果不怎么样，极大放宽判定
     * 范围试一下」): a turned view meets a press with the field at one depth, so a key nearer or deeper on its sea is
     * off by up to a key width or more.
     */
    public static final float SEA_REACH = 3f;
    /** Width of a mine's touch box as a fraction of its lane (centred). */
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
     * Width of a LANE MINE's contact box as a fraction of its lane's current span (centred; the
     * whole lane is drawn red, the 10 % margins keep brushes from a neighbouring lane fair).
     */
    public static final float LANE_MINE_BOX = 0.80f;
    /** A hold breaks when its pointer stays off the lane/zone longer than this (ms). */
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

    /** Input result: not judged (not running, autoplay, not a gameplay key, bad pointer). */
    public static final int INPUT_IGNORED = 0;
    /**
     * Input result: a touch on neither line (free for HUD controls). Not returned since whole columns (Kelvin
     * 2026-11-07): every touch is on both lines.
     */
    public static final int INPUT_STRAY = 1;
    /**
     * Input result: a touch (or a gameplay key) with nothing to judge (a press that only caught or judged a
     * DRAG is one too: {@link #lastInputCaught()}; the DRAG's own events carry its hit).
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
    /** A running TIDE STREAM: held by coverage (one finger in its line's zone or key of its row each). */
    private static final int OWNER_COVER = -4;
    /** A hold running when the system took the gesture ({@link #gestureCancelled}): held to its end by the engine. */
    private static final int OWNER_KEPT = -5;
    private static final int OWNER_KEY_BASE = -100;

    private static final int KIND_OWN = 0;
    private static final int KIND_NEIGHBOUR = 1;
    private static final int KIND_UNIVERSAL = 2;
    /** A key of the full-screen time taken by a press away from it (Kelvin 2026-11-07 06:04, {@link #screenNote}). */
    private static final int KIND_SCREEN = 3;

    private static final int PTR_KEY = -1;
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
    /** True while the field is drawn in 3D ({@link #setSolid}). */
    private boolean solid;
    private final float[] seaAxes = new float[9];
    private static final float[] SEA_Y_AXES = new float[9]; // seaY's scratch (drawing: one thread)
    private float evLowerY;
    private float evUpperY;

    // Per-note runtime state (indexed by Note.index()).
    private byte[] state = new byte[0];
    private byte[] grade = new byte[0];
    private float[] dt = new float[0];
    private float[] resolvedMs = new float[0];
    private int[] holdOwner = new int[0];
    private float[] holdOffSince = new float[0];

    // Streams and cursors.
    private int laneCount;
    private Note[][] laneStream = new Note[0][];
    private int[] laneCursor = new int[0];
    private final Note[][] uniStream = new Note[Line.COUNT][];
    private final int[] uniCursor = new int[Line.COUNT];
    private final Note[][] dragStream = new Note[Line.COUNT][];
    private final int[] dragCursor = new int[Line.COUNT];
    /** Per line: its taps and STREAMs placed by position ({@link Chart#freeNotes}). */
    private final Note[][] freeStream = new Note[Line.COUNT][];
    private final int[] freeCursor = new int[Line.COUNT];
    /** Per note: when a finger or key caught this DRAG before its time (judge ms), NaN while it is not caught. */
    private float[] caughtMs = new float[0];
    /**
     * Per note: the lane under a key placed by position (a DRAG too) at its time (reference geometry; −1 off the lanes
     * and for keys in a lane), {@link #placeLane}.
     */
    private int[] homeLane = new int[0];
    /**
     * Per note: in the chart's full-screen time ({@link Chart#fullScreenAt} of its beat): a press anywhere takes it,
     * a started hold follows its finger anywhere and any finger catches a DRAG; mines and keys stay as they are.
     */
    private boolean[] screenNote = new boolean[0];
    /** True when some note is a {@link #screenNote}. */
    private boolean anyScreen;
    /** Plain mines (no LANE MINEs) in chart order; valid up to {@link #mineCount}. */
    private Note[] mines = new Note[0];
    private int mineCount;
    private int mineLo;
    /** LANE MINEs in chart order; valid up to {@link #laneMineCount}. */
    private Note[] laneMineArr = new Note[0];
    private int laneMineCount;
    private int laneMineLo;
    // Per LANE MINE (indexed by Note.index()): pointers / key slots counted inside its box (bit
    // masks), the number of contacts and the time of the last one.
    private int[] minePtrMask = new int[0];
    private int[] mineKeyMask = new int[0];
    private int[] mineContacts = new int[0];
    private float[] mineContactMs = new float[0];
    // Mines and taps, LANE MINE priority (see the class doc). Per note: how long after its time it can still be
    // judged (TIER2, less where MINE windows cut its late ECHO, TIER1 when a LANE MINE gives way to it), whether a
    // LANE MINE gives way to it, and the MINEs that cut its ECHO, cutMine[cutFrom .. + cutLen). Per mine: the notes
    // whose RESONANCE windows it loses or gives way to, shadowNote[shadowFrom .. + shadowLen).
    private float[] lateWin = new float[0];
    private boolean[] noEcho = new boolean[0];
    private int[] cutFrom = new int[0];
    private int[] cutLen = new int[0];
    private int[] cutMine = new int[0];
    private int[] shadowFrom = new int[0];
    private int[] shadowLen = new int[0];
    private int[] shadowNote = new int[0];
    // RESONANCE guard per line × lane (like laneStream): LANE MINEs there sleep over [guardFrom, guardTo]; NaN = none.
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
    /**
     * Per pointer, for keys on a sea (Kelvin 2026-11-08 19:43): its ray's x at a key's depth 0 and how far that x moves
     * per reference unit a key lies deeper ({@link #seaX}); its x and 0 while the view is not turned.
     */
    private final float[] pSeaX = new float[MAX_POINTERS];
    private final float[] pSeaG = new float[MAX_POINTERS];
    private final int[] pHold = new int[MAX_POINTERS];
    private final int[] pLane = new int[MAX_POINTERS];
    /** The lane each pointer's press met in 3D ({@link View3D#hitLane}), −1 for none: its lane over the x's. */
    private final int[] pHint = new int[MAX_POINTERS];
    private final int[] pLine = new int[MAX_POINTERS];
    /** Pointers that went down inside a veil: invalid until they lift (ARCHITECTURE §22). */
    private final boolean[] pVeiled = new boolean[MAX_POINTERS];
    /** The chart's veils (never null). */
    private Veil[] veils = new Veil[0];
    private Veil[] curtains = new Veil[0];
    /** Visual beat of the last {@link #eventGeometry} (what the player saw at the input event). */
    private float evBeat;

    // Keys.
    private final boolean[] kDown = new boolean[GameKeys.SLOTS];
    private final int[] kHold = new int[GameKeys.SLOTS];
    private final int[] kLane = new int[GameKeys.SLOTS];

    // Candidate selection scratch.
    private int bestNote;
    private double bestT;
    private double bestAbs;
    private int bestKind;
    private int bestLane;
    private float bestLineDist;
    private Judgement lastJudgement;
    private int lastInputLane = -1;
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
    private final byte[] evLine = new byte[EVENT_CAPACITY];
    private final int[] evNote = new int[EVENT_CAPACITY];
    private final int[] evCount = new int[EVENT_CAPACITY];
    private final int[] evPointer = new int[EVENT_CAPACITY];
    private final double[] evTime = new double[EVENT_CAPACITY];
    private final int[] evFlags = new int[EVENT_CAPACITY];
    private int evHead;
    private int evSize;

    /** Creates an idle engine; {@link #load} a chart before use. */
    public GameEngine() {
        Arrays.fill(pHold, -1);
        Arrays.fill(kHold, -1);
        Arrays.fill(kLane, -1);
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
            mineKeyMask = new int[n];
            mineContacts = new int[n];
            mineContactMs = new float[n];
            caughtMs = new float[n];
            homeLane = new int[n];
            screenNote = new boolean[n];
            lateWin = new float[n];
            noEcho = new boolean[n];
            cutFrom = new int[n];
            cutLen = new int[n];
            shadowFrom = new int[n];
            shadowLen = new int[n];
        }
        Arrays.fill(state, 0, n, STATE_PENDING);
        Arrays.fill(grade, 0, n, (byte) -1);
        Arrays.fill(dt, 0, n, 0f);
        Arrays.fill(resolvedMs, 0, n, Float.NaN);
        Arrays.fill(holdOwner, 0, n, OWNER_NONE);
        Arrays.fill(holdOffSince, 0, n, Float.NaN);
        Arrays.fill(minePtrMask, 0, n, 0);
        Arrays.fill(mineKeyMask, 0, n, 0);
        Arrays.fill(mineContacts, 0, n, 0);
        Arrays.fill(mineContactMs, 0, n, Float.NaN);
        Arrays.fill(caughtMs, 0, n, Float.NaN);
        anyScreen = false;
        for (int i = 0; i < n; i++) {
            screenNote[i] = chart.fullScreenAt(notes[i].judgement());
            anyScreen |= screenNote[i];
        }

        laneCount = chart.laneCount();
        int streams = Line.COUNT * laneCount;
        // the guards hold Line.COUNT more than the streams: a chart without lanes (0 streams) still needs them
        if (laneStream.length < streams || guardFrom.length < streams + Line.COUNT) {
            laneStream = new Note[streams][];
            laneCursor = new int[streams];
            guardFrom = new double[streams + Line.COUNT];
            guardTo = new double[streams + Line.COUNT];
        }
        Arrays.fill(guardFrom, 0, streams + Line.COUNT, Double.NaN);
        Arrays.fill(guardTo, 0, streams + Line.COUNT, Double.NaN);
        for (int l = 0; l < Line.COUNT; l++) {
            Line line = Line.of(l);
            for (int i = 0; i < laneCount; i++) {
                laneStream[l * laneCount + i] = chart.laneNotes(line, i);
                laneCursor[l * laneCount + i] = 0;
            }
            uniStream[l] = chart.universals(line);
            uniCursor[l] = 0;
            dragStream[l] = chart.drags(line);
            dragCursor[l] = 0;
            freeStream[l] = chart.freeNotes(line);
            freeCursor[l] = 0;
        }
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
        LaneLayout.freezeSpaceKeys(chart);
        inputLayout.setLanes(chart);
        findHomeLanes(n);
        findShadows(n);
        int holds = Math.max(4, chart.count(NoteType.HOLD) + chart.tideStreamCount());
        if (active.length < holds) {
            active = new int[holds];
            coverOrder = new int[holds];
        }
        activeCount = 0;

        frameLayout.setLanes(chart);
        for (int s = 0; s < GameKeys.SLOTS; s++) {
            kLane[s] = s == GameKeys.SPACE ? -1 : chart.laneIndexOf(GameKeys.laneId(s));
            kDown[s] = false;
            kHold[s] = -1;
        }
        for (int p = 0; p < MAX_POINTERS; p++) {
            pDown[p] = false;
            pVeiled[p] = false;
            pHold[p] = -1;
            pLane[p] = -1;
            pLine[p] = -1;
        }
        veils = chart.veils();
        curtains = chart.curtains();
        keeper.reset(chart.totalNotes(), chart.totalMines());
        clearEvents();
        lastJudgement = null;
        lastInputLane = -1;
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
            int free = fingers(line);
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

    /** Line y and lanes of the frame, in the world ({@link Field}: the screen and lanes with kx, lines with ky). */
    private void frameGeometry(float beat) {
        JudgementLine je = chart.lineEvents();
        frameLayout.update(beat, Field.x(LaneLayout.VIEW_LEFT), Field.x(LaneLayout.VIEW_RIGHT), Field.kx());
        frameLowerY = Field.y(je.lowerAt(beat));
        frameUpperY = Field.y(je.upperAt(beat));
    }

    /** Line y and lanes at an input event's visual time, in the world (as {@link #frameGeometry}). */
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
        for (int k = 0; k < Line.COUNT * laneCount; k++) laneCursor[k] = autoStream(laneStream[k], laneCursor[k], t);
        for (int l = 0; l < Line.COUNT; l++) freeCursor[l] = autoStream(freeStream[l], freeCursor[l], t);
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
                    float x = dragX(n, frameLayout, frameBeat);
                    float y = lineY(n.line(), frameLowerY, frameUpperY);
                    judgeDrag(i, n.judgementMs(), PTR_ENGINE, frameLayout, x, y, x, y, GameEvent.FLAG_AUTO);
                }
                c++;
            }
            dragCursor[l] = c;
        }
    }

    /** Autoplay over one stream of taps / STREAM heads up to t (each RESONANCE at its time). @return its cursor */
    private int autoStream(Note[] arr, int c, double t) {
        while (c < arr.length) {
            Note n = arr[c];
            int i = n.index();
            if (state[i] == STATE_PENDING) {
                if (n.judgementMs() > t) break;
                float x = spot(n, frameLayout);
                float y = lineY(n.line(), frameLowerY, frameUpperY);
                judgeLaneNote(i, Judgement.RESONANCE, 0f, n.judgementMs(), OWNER_AUTO, PTR_ENGINE, x, y, x, y,
                    GameEvent.FLAG_AUTO);
            }
            c++;
        }
        return c;
    }

    private void resolvePointers() {
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (!pDown[p] || pVeiled[p]) {
                pLane[p] = -1;
                pLine[p] = -1;
                continue;
            }
            pLine[p] = nearerLine(pY[p], frameLowerY, frameUpperY);
            int h = pHint[p];
            pLane[p] = h >= 0 && h < frameLayout.count() ? h : frameLayout.indexAtX(pX[p]);
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
                on = pDown[o] && !pVeiled[o] && onHold(n, pX[o], o, frameLayout);
            } else if (o <= OWNER_KEY_BASE) {
                on = kDown[OWNER_KEY_BASE - o];
            } else {
                on = regrabByPointers(i, n) || regrabByKeys(i, n);
            }
            stepHold(k, i, on, t);
        }
        stepCoverage(t);
    }

    /**
     * A lane key that is down and free (pressed again while paused, or held through the resume)
     * takes over a detached hold of its lane and line.
     */
    private boolean regrabByKeys(int i, Note n) {
        for (int s = 0; s < GameKeys.SPACE; s++) {
            if (kDown[s] && kHold[s] < 0 && keyOn(s, n, frameLayout)) {
                attachHold(i, OWNER_KEY_BASE - s, true);
                return true;
            }
        }
        return false;
    }

    /** A resting pointer that is on a detached hold takes it over. */
    private boolean regrabByPointers(int i, Note n) {
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p] && !pVeiled[p] && pHold[p] < 0 && onHold(n, pX[p], p, frameLayout)) {
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
     * Re-evaluates the coverage of every running TIDE STREAM at time t (after a pointer or key
     * changed, and once per frame). Per line the
     * fingers go to the streams in {@link #sortCover} order, one each; a stream that finishes in
     * this pass (its end reached, or an earlier lapse ran out) passes its finger on to the next.
     */
    private void stepCoverage(double t) {
        for (int l = 0; l < Line.COUNT; l++) {
            Line line = Line.of(l);
            int m = sortCover(line, t);
            if (m == 0) continue;
            int free = fingers(line);
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
     * @return how many fingers {@code line} has (ARCHITECTURE §19): down pointers at any x and height
     *         (whole columns; whatever else they hold, but not one that went down inside a veil) plus keys
     *         of its row down (Space counts for the lower line)
     */
    private int fingers(Line line) {
        int count = 0;
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p] && !pVeiled[p]) count++;
        }
        for (int s = 0; s < GameKeys.SLOTS; s++) {
            if (kDown[s] && GameKeys.line(s) == line) count++;
        }
        return count;
    }

    private void sweep(double sweepT) {
        for (int k = 0; k < Line.COUNT * laneCount; k++) {
            laneCursor[k] = sweepStream(laneStream[k], laneCursor[k], sweepT);
        }
        for (int l = 0; l < Line.COUNT; l++) {
            freeCursor[l] = sweepStream(freeStream[l], freeCursor[l], sweepT);
            dragCursor[l] = sweepStream(dragStream[l], dragCursor[l], sweepT);
        }
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
    }

    /** The LOST sweep over one stream of taps / STREAM heads or DRAGs. @return its cursor */
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
        // a note whose window a mine cuts short can run out before an earlier one
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
     * that is showing judges nothing until it lifts (ARCHITECTURE §22): {@link #INPUT_VEILED}.
     *
     * @param pointer pointer id (0..{@link #MAX_POINTERS}−1)
     * @param x       world x (unprojected with the gameplay viewport)
     * @param y       world y
     * @param nanos   event timestamp ({@code Gdx.input.getCurrentEventTime()}, nanoTime base)
     * @return one of the {@code INPUT_*} codes
     */
    public int touchDown(int pointer, float x, float y, long nanos) {
        return touchDown(pointer, x, y, x, 0f, nanos);
    }

    /**
     * {@link #touchDown(int, float, float, long)} with the press's ray for keys on a sea (Kelvin 2026-11-08 19:43
     * 「我目前的这些出现都要能靠垂直判定判定到」): such a key {@code z} deep is judged at {@code seaX + seaG·z}, at any height
     * ({@link View3D#seaRay}); every other key at x.
     */
    public int touchDown(int pointer, float x, float y, float seaX, float seaG, long nanos) {
        return touchDown(pointer, x, y, seaX, seaG, -1, nanos);
    }

    /**
     * {@link #touchDown(int, float, float, float, float, long)} with the lane the press met in 3D (Kelvin 2026-11-08
     * 23:10: lanes turned or apart from the row may overlap; {@link View3D#hitLane}, −1 for none): it is the press's
     * lane, (x, y) on its column as drawn before its turn, its lines at the chart's.
     */
    public int touchDown(int pointer, float x, float y, float seaX, float seaG, int lane, long nanos) {
        return touchDown(pointer, x, y, seaX, seaG, lane, false, nanos);
    }

    /**
     * {@link #touchDown(int, float, float, float, float, int, long)} with {@code veiled} true when the press is inside
     * a chart format v7 veil's outline on the screen ({@link Volumes7#veiled}): it then judges nothing until it lifts.
     */
    public int touchDown(int pointer, float x, float y, float seaX, float seaG, int lane, boolean veiled,
                         long nanos) {
        int ov = overlapCount; // the other lanes it is in, set just before (pressOverlap)
        overlapCount = 0;
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS) return INPUT_IGNORED;
        if (pDown[pointer]) release(pointer, nanos, false); // a lost touchUp: release the old press first
        pDown[pointer] = true;
        pX[pointer] = x;
        pY[pointer] = y;
        pSeaX[pointer] = seaX;
        pSeaG[pointer] = seaG;
        pHint[pointer] = lane;
        pHold[pointer] = -1;
        lastJudgement = null;
        lastInputLane = -1;
        lastInputLine = null;
        lastInputMine = false;
        lastInputCaught = false;
        if (phase != Phase.RUNNING) {
            // held through a pause or the start: the veils as they show now decide
            pVeiled[pointer] = veiled || Veils.containsAny(veils, x, y, frameBeat);
            return INPUT_IGNORED;
        }
        double t = eventJudgeMs(nanos);
        eventGeometry(t);
        if (!autoplay) stepCoverage(t);
        pVeiled[pointer] = veiled || Veils.containsAny(veils, x, y, evBeat);
        if (pVeiled[pointer]) return INPUT_VEILED;
        // whole columns: both lines take the touch at any height; inside both zones a sync pair takes one tap
        boolean both = Math.abs(y - evLowerY) <= Field.zoneHalf() && Math.abs(y - evUpperY) <= Field.zoneHalf();
        int own = lane >= 0 && lane < inputLayout.count() ? lane : inputLayout.indexAtX(x);
        int zone = nearerLine(y, evLowerY, evUpperY);
        lastInputLane = own;
        lastInputLine = Line.of(zone);
        if (autoplay) {
            emitEmpty(x, zone == Line.UPPER.ordinal() ? evUpperY : evLowerY, own, zone, pointer, t,
                GameEvent.FLAG_AUTO);
            return INPUT_EMPTY;
        }
        int minesBefore = mineEvents;
        checkMinesAt(t, x, y, pointer, true);
        lastInputMine = mineEvents != minesBefore;
        int caught = catchDragsAt(t, pointer, -1, x, y);
        lastInputCaught = caught != CATCH_NONE;
        if (regrabAt(pointer, x, y, t)) return INPUT_REGRAB;

        int nb = neighbourLane(own, x);
        clearBest();
        for (int l = 0; l < Line.COUNT; l++) {
            float ly = l == 0 ? evLowerY : evUpperY;
            float dist = Math.abs(y - ly);
            int c = own >= 0 ? laneCandidate(l, own, t) : -1;
            int f = freeCandidate(l, x, pointer, t, FREE_REACH);
            if (c >= 0 || f >= 0) {
                consider(c, KIND_OWN, own, dist, t);
                consider(f, KIND_OWN, own, dist, t);
            } else {
                if (nb >= 0) {
                    c = laneCandidate(l, nb, t);
                    consider(c, KIND_NEIGHBOUR, nb, dist, t);
                }
                f = freeCandidate(l, x, pointer, t, DRAG_REACH); // as far as a tap reaches into a neighbour lane
                consider(f, KIND_NEIGHBOUR, own, dist, t);
            }
            boolean laneNote = c >= 0 || f >= 0;
            for (int k = 0; k < ov; k++) { // the other lanes whose areas it is in, as its own (Kelvin 2026-11-10 02:19)
                int o = overlapLane[k] == own ? -1 : laneCandidate(l, overlapLane[k], t);
                if (o < 0) continue;
                consider(o, KIND_OWN, overlapLane[k], Math.abs(overlapY[k] - ly), t);
                laneNote = true;
            }
            if (anyScreen && considerScreen(l, x, y, ly, t)) laneNote = true;
            consider(universalCandidate(l, t, laneNote), KIND_UNIVERSAL, -1, dist, t);
        }
        if (bestNote < 0) {
            // a press that only judged a DRAG has its HIT already; one that caught a DRAG early is still a bare touch
            if (caught != CATCH_JUDGED) {
                emitEmpty(x, zone == Line.UPPER.ordinal() ? evUpperY : evLowerY, own, zone, pointer, t, 0);
            }
            return INPUT_EMPTY;
        }
        for (int k = 0; k < ov; k++) { // judged in another lane it is in: the pointer is on that one's column
            if (overlapLane[k] != bestLane || bestLane == own) continue;
            pX[pointer] = overlapX[k];
            pY[pointer] = overlapY[k];
            pHint[pointer] = bestLane;
        }
        judgeBest(pointer, pointer, x, y, t, bestKind == KIND_NEIGHBOUR ? GameEvent.FLAG_NEIGHBOUR : 0);
        Judgement j = lastJudgement;
        if (both) judgeOtherLine(pointer, x, y, t, own);
        for (int k = 0; k < ov; k++) judgeJoint(pointer, x, y, t, j, overlapLane[k], both);
        if (own >= 0 && own != bestLane) judgeJoint(pointer, x, y, t, j, own, both);
        return INPUT_JUDGED;
    }

    /**
     * The keys of lane {@code lane}, another lane whose area the press that judged {@link #bestNote} is in, at exactly
     * its beat (on its line, else on the other line, or on both lines when the press is in both zones): judged with it,
     * the same grade and Δt (Kelvin 2026-11-10 02:19 「之前“同时点击重合判定区域能够判定两个轨道的键”也还可以保留」, as
     * {@link #judgeOtherLine}). The other line's key counts as the press is anywhere on the lane's column (Kelvin
     * 2026-11-10 04:22 「同时点击垂直轨道和水平轨道的交界处好像不能同时判定两个按键」: a lane lying toward the player met a
     * standing column with its sky key at the beat of the column's ground key).
     */
    private void judgeJoint(int pointer, float x, float y, double t, Judgement j, int lane, boolean both) {
        Note n = notes[bestNote];
        if (lane < 0 || lane == bestLane || n.type() == NoteType.UNIVERSAL || j == null) return;
        float d = (float) (t - n.judgementMs());
        int line = n.line().ordinal();
        boolean same = pendingLaneAt(line, lane, n.judgement()) >= 0;
        for (int l = 0; l < Line.COUNT; l++) {
            if (l != line && same && !both) continue;
            int c = pendingLaneAt(l, lane, n.judgement());
            if (c < 0) continue;
            int keep = pHold[pointer];
            float ly = l == Line.UPPER.ordinal() ? evUpperY : evLowerY;
            judgeLaneNote(c, j, d, t, pointer, pointer, spot(notes[c], inputLayout), ly, x, y, GameEvent.FLAG_JOINT);
            if (keep >= 0) pHold[pointer] = keep;
        }
    }

    /** The other lanes the next press is in ({@link #pressOverlap}), and where on each one's column. */
    private int overlapCount;
    private int[] overlapLane = new int[8];
    private float[] overlapX = new float[8], overlapY = new float[8];

    /**
     * Sets the other lanes whose areas the next touch event's press is in, besides the one it is given (Kelvin
     * 2026-11-10 02:19 「映射在二维的一个判定区域里面，点击这个区域就能够判定」: each lane's column as the screen shows
     * it): lane {@code lanes[k]} at ({@code xs[k]}, {@code ys[k]}) on its column, as the event takes its own. A press
     * going down judges the earliest key of all of them and the others' keys at its beat with it; a pointer holding a
     * key stays on that key's lane while it is in its area ({@link #followHold}). The next touch event uses and clears
     * it.
     */
    public void pressOverlap(int n, int[] lanes, float[] xs, float[] ys) {
        if (overlapLane.length < n) {
            overlapLane = new int[n];
            overlapX = new float[n];
            overlapY = new float[n];
        }
        System.arraycopy(lanes, 0, overlapLane, 0, n);
        System.arraycopy(xs, 0, overlapX, 0, n);
        System.arraycopy(ys, 0, overlapY, 0, n);
        overlapCount = n;
    }

    /**
     * Overlapping zones (Kelvin 10-12): a touch inside both zones also judges the other line's note in
     * the same lane, and that line's TIDE group, when they share the judged note's beat exactly, with
     * the same grade and Δt. A sky + ground sync pair then takes one tap. A second hold follows the
     * same pointer through its owner; the pointer keeps the first one.
     */
    private void judgeOtherLine(int pointer, float x, float y, double t, int own) {
        Note n = notes[bestNote];
        int other = 1 - n.line().ordinal();
        float d = (float) (t - n.judgementMs());
        Judgement j = lastJudgement;
        float ly = other == Line.UPPER.ordinal() ? evUpperY : evLowerY;
        int lane = n.type() == NoteType.UNIVERSAL ? own : bestLane;
        int c = pendingLaneAt(other, lane, n.judgement());
        if (c < 0) c = pendingFreeAt(other, n.judgement(), x, pointer);
        if (c >= 0) {
            int keep = pHold[pointer];
            judgeLaneNote(c, j, d, t, pointer, pointer, spot(notes[c], inputLayout), ly, x, y, GameEvent.FLAG_JOINT);
            if (keep >= 0) pHold[pointer] = keep;
        }
        int g = pendingTideAt(other, n.judgement());
        if (g >= 0) judgeGroup(notes[g], j, d, t, pointer, x, ly, x, y, GameEvent.FLAG_JOINT);
    }

    /**
     * @return true when some lane of {@code line} has a pending tap / hold at exactly {@code beat}, or a key placed by
     *         position there has one
     */
    private boolean pendingLaneAt(int line, float beat) {
        for (int lane = 0; lane < laneCount; lane++) if (pendingLaneAt(line, lane, beat) >= 0) return true;
        return pendingFreeAt(line, beat, Float.NaN, -1) >= 0;
    }

    /**
     * @return a pending tap / hold of {@code line} placed by position whose judgement beat is exactly {@code beat} and
     *         that x of pointer p (input geometry, {@link #seaX}) takes like its own lane ({@link #FREE_REACH}; any
     *         when x is NaN), or −1
     */
    private int pendingFreeAt(int line, float beat, float x, int p) {
        Note[] arr = freeStream[line];
        for (int c = freeCursor[line]; c < arr.length; c++) {
            Note u = arr[c];
            float b = u.judgement();
            if (b > beat) break;
            if (b == beat && state[u.index()] == STATE_PENDING
                && (Float.isNaN(x) || reaches(u, inputLayout, seaX(u, x, p, inputLayout), FREE_REACH))) {
                return u.index();
            }
        }
        return -1;
    }

    /**
     * @return a pending tap / hold of {@code lane} on {@code line} whose judgement beat is exactly {@code beat}, or −1
     */
    private int pendingLaneAt(int line, int lane, float beat) {
        if (lane < 0) return -1;
        int k = line * laneCount + lane;
        Note[] arr = laneStream[k];
        for (int c = laneCursor[k]; c < arr.length; c++) {
            Note u = arr[c];
            float b = u.judgement();
            if (b > beat) break;
            if (b == beat && state[u.index()] == STATE_PENDING) return u.index();
        }
        return -1;
    }

    /**
     * A pointer moved while down: hold tracking, mines and re-grabbing a detached hold.
     *
     * @param nanos event timestamp ({@code Gdx.input.getCurrentEventTime()})
     */
    public void touchDragged(int pointer, float x, float y, long nanos) {
        touchDragged(pointer, x, y, x, 0f, nanos);
    }

    /** {@link #touchDragged(int, float, float, long)} with the press's ray for keys on a sea ({@link #touchDown}). */
    public void touchDragged(int pointer, float x, float y, float seaX, float seaG, long nanos) {
        touchDragged(pointer, x, y, seaX, seaG, -1, nanos);
    }

    /** {@link #touchDragged(int, float, float, float, float, long)} with the lane it met ({@link #touchDown}). */
    public void touchDragged(int pointer, float x, float y, float seaX, float seaG, int lane, long nanos) {
        int ov = overlapCount;
        overlapCount = 0;
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        pX[pointer] = x;
        pY[pointer] = y;
        pSeaX[pointer] = seaX;
        pSeaG[pointer] = seaG;
        pHint[pointer] = lane;
        followHold(pointer, ov);
        x = pX[pointer];
        y = pY[pointer];
        if (phase != Phase.RUNNING || autoplay || pVeiled[pointer]) return;
        double t = eventJudgeMs(nanos);
        eventGeometry(t);
        checkMinesAt(t, x, y, pointer, false);
        catchDragsAt(t, pointer, -1, x, y);
        int h = pHold[pointer];
        if (h >= 0) {
            int k = activeSlot(h);
            if (k >= 0) stepHold(k, h, onHold(notes[h], x, pointer, inputLayout), t);
        } else {
            regrabAt(pointer, x, y, t);
        }
        stepCoverage(t);
    }

    /**
     * A pointer went up: releases its hold (completed within the grace, else LOST).
     *
     * @param nanos event timestamp ({@code Gdx.input.getCurrentEventTime()})
     */
    public void touchUp(int pointer, float x, float y, long nanos) {
        touchUp(pointer, x, y, x, 0f, nanos);
    }

    /** {@link #touchUp(int, float, float, long)} with the press's ray for keys on a sea ({@link #touchDown}). */
    public void touchUp(int pointer, float x, float y, float seaX, float seaG, long nanos) {
        touchUp(pointer, x, y, seaX, seaG, -1, nanos);
    }

    /** {@link #touchUp(int, float, float, float, float, long)} with the lane it met ({@link #touchDown}). */
    public void touchUp(int pointer, float x, float y, float seaX, float seaG, int lane, long nanos) {
        int ov = overlapCount;
        overlapCount = 0;
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        pX[pointer] = x;
        pY[pointer] = y;
        pSeaX[pointer] = seaX;
        pSeaG[pointer] = seaG;
        pHint[pointer] = lane;
        followHold(pointer, ov);
        release(pointer, nanos, true);
    }

    /**
     * Keeps a pointer holding a lane's key on that lane's column while the press is still in its area
     * ({@link #pressOverlap}: the first of {@code ov}), when another lane's column took the press first.
     */
    private void followHold(int pointer, int ov) {
        int h = pHold[pointer];
        if (h < 0 || notes[h].free() || pHint[pointer] == notes[h].laneIndex()) return;
        for (int k = 0; k < ov; k++) {
            if (overlapLane[k] != notes[h].laneIndex()) continue;
            pX[pointer] = overlapX[k];
            pY[pointer] = overlapY[k];
            pHint[pointer] = overlapLane[k];
            return;
        }
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
     * every running hold held by a finger (STREAM, TIDE STREAM) is held by the engine to its end, completing with its
     * head grade unless a lapse it already had runs out ({@link #HOLD_LEAVE_MS}); keys keep theirs. Paused or not
     * running, the fingers count as lifted (a pause detaches their holds). New fingers play as usual.
     */
    public void gestureCancelled() {
        if (chart == null) return;
        if (phase == Phase.RUNNING && !autoplay) {
            for (int k = 0; k < activeCount; k++) {
                int i = active[k];
                int o = holdOwner[i];
                if (o != OWNER_AUTO && o > OWNER_KEY_BASE) holdOwner[i] = OWNER_KEPT;
            }
            for (int p = 0; p < MAX_POINTERS; p++) {
                if (!pDown[p]) continue;
                pDown[p] = false;
                pVeiled[p] = false;
                pHold[p] = -1;
                pLane[p] = -1;
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
                if (k >= 0 && !stepHold(k, h, onHold(notes[h], pX[pointer], pointer, inputLayout), t)) {
                    // A finger that already left the lane and lifts without coming back let
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
        if (judging) stepCoverage(t);
    }

    /**
     * A key went down. Gameplay keys are judged like a touch on their lane (no neighbour
     * tolerance) and on their line's TIDE notes; Space takes lower-line TIDE notes only.
     *
     * @return one of the {@code INPUT_*} codes ({@link #INPUT_IGNORED} for non-gameplay keys)
     */
    public int keyDown(int keycode, long nanos) {
        int s = GameKeys.slotOf(keycode);
        if (chart == null || s < 0) return INPUT_IGNORED;
        if (kDown[s]) return INPUT_IGNORED;
        kDown[s] = true;
        kHold[s] = -1;
        lastJudgement = null;
        Line line = GameKeys.line(s);
        int lane = kLane[s];
        lastInputLane = lane;
        lastInputLine = line;
        if (phase != Phase.RUNNING) return INPUT_IGNORED;
        double t = eventJudgeMs(nanos);
        eventGeometry(t);
        float ly = line == Line.UPPER ? evUpperY : evLowerY;
        float x = lane >= 0 ? inputLayout.centre(lane)
            : (inputLayout.windowLeft() + inputLayout.windowRight()) * 0.5f;
        if (autoplay) {
            emitEmpty(x, ly, lane, line.ordinal(), PTR_KEY, t, GameEvent.FLAG_KEYBOARD | GameEvent.FLAG_AUTO);
            return INPUT_EMPTY;
        }
        stepCoverage(t);
        checkKeyMines(s, t, true);
        int caught = catchDragsAt(t, PTR_KEY, s, x, ly);
        // Re-grab a hold of this key's lane detached by a pause.
        if (lane >= 0) {
            for (int k = 0; k < activeCount; k++) {
                int i = active[k];
                Note n = notes[i];
                if (holdOwner[i] == OWNER_DETACHED && keyOn(s, n, inputLayout)) {
                    attachHold(i, OWNER_KEY_BASE - s, false);
                    emitHold(GameEvent.HOLD_START, i, x, ly, PTR_KEY, t,
                        GameEvent.FLAG_KEYBOARD | GameEvent.FLAG_REGRAB);
                    return INPUT_REGRAB;
                }
            }
        }
        clearBest();
        int l = line.ordinal();
        boolean laneKey = lane >= 0 && s != GameKeys.SPACE;
        int c = laneKey ? laneCandidate(l, lane, t) : -1;
        consider(c, KIND_OWN, lane, 0f, t);
        // a key placed by position: as a finger at this key's lane centre reaches it
        int f = laneKey && inputLayout.active(lane) ? freeCandidate(l, x, -1, t, DRAG_REACH) : -1;
        consider(f, KIND_OWN, lane, 0f, t);
        consider(universalCandidate(l, t, c >= 0 || f >= 0), KIND_UNIVERSAL, -1, 0f, t);
        if (bestNote < 0) {
            if (caught != CATCH_JUDGED) emitEmpty(x, ly, lane, l, PTR_KEY, t, GameEvent.FLAG_KEYBOARD);
            return INPUT_EMPTY;
        }
        judgeBest(OWNER_KEY_BASE - s, PTR_KEY, x, ly, t, GameEvent.FLAG_KEYBOARD);
        return INPUT_JUDGED;
    }

    /** A key went up: releases the hold it owns (and may uncover a TIDE STREAM of its row). */
    public void keyUp(int keycode, long nanos) {
        int s = GameKeys.slotOf(keycode);
        if (chart == null || s < 0 || !kDown[s]) return;
        int h = kHold[s];
        boolean judging = phase == Phase.RUNNING && !autoplay;
        double t = 0.0;
        if (judging) {
            t = eventJudgeMs(nanos);
            eventGeometry(t);
            checkKeyMines(s, t, false);
            if (h >= 0) {
                int k = activeSlot(h);
                if (k >= 0 && !stepHold(k, h, true, t)) releaseHold(k, h, t);
            }
        } else if (phase == Phase.PAUSED && h >= 0) {
            detachHold(h);
        }
        kDown[s] = false;
        kHold[s] = -1;
        forgetLaneMineKey(s);
        if (judging) stepCoverage(t);
    }

    // ------------------------------------------------------------------ candidates

    /**
     * Earliest pending tap/hold head of (line, lane) whose judge window ({@link #inWindow}) holds t, or −1.
     */
    private int laneCandidate(int line, int lane, double t) {
        if (lane < 0) return -1;
        int k = line * laneCount + lane;
        Note[] arr = laneStream[k];
        for (int c = laneCursor[k]; c < arr.length; c++) {
            Note n = arr[c];
            double T = n.judgementMs();
            if (T - TIER2_MS > t) break;
            int i = n.index();
            if (state[i] != STATE_PENDING || !inWindow(i, t)) continue;
            return i;
        }
        return -1;
    }

    /**
     * Earliest pending tap / hold head of {@code line} placed by position whose judge window holds t and that x of
     * pointer p (input geometry; on a sea at the key's depth, {@link #seaX}; −1: none) reaches within {@code reach} of
     * its width ({@link #reaches}); on a tie the nearest. −1 for none.
     */
    private int freeCandidate(int line, float x, int p, double t, float reach) {
        Note[] arr = freeStream[line];
        int best = -1;
        double bestTime = 0.0;
        float bestGap = 0f;
        for (int c = freeCursor[line]; c < arr.length; c++) {
            Note n = arr[c];
            double T = n.judgementMs();
            if (T - TIER2_MS > t || best >= 0 && T > bestTime) break;
            int i = n.index();
            float px = seaX(n, x, p, inputLayout);
            if (state[i] != STATE_PENDING || !inWindow(i, t) || !reaches(n, inputLayout, px, reach)) continue;
            float gap = Math.abs(px - spot(n, inputLayout));
            if (best < 0 || gap < bestGap) {
                best = i;
                bestTime = T;
                bestGap = gap;
            }
        }
        return best;
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
     * Full-screen time (Kelvin 2026-11-07 06:04): offers a press at (x, y) the earliest pending key of the time in
     * every active lane of {@code line} and the earliest one placed by position, as {@link #KIND_SCREEN} (the same
     * time: the nearest x, then the nearer line).
     *
     * @return true when it offered one
     */
    private boolean considerScreen(int line, float x, float y, float ly, double t) {
        boolean any = false;
        for (int lane = 0; lane < laneCount; lane++) {
            if (!inputLayout.active(lane)) continue;
            int k = line * laneCount + lane;
            Note[] arr = laneStream[k];
            for (int c = laneCursor[k]; c < arr.length; c++) {
                Note n = arr[c];
                if (n.judgementMs() - TIER2_MS > t) break;
                int i = n.index();
                if (state[i] != STATE_PENDING || !screenNote[i] || !inWindow(i, t)) continue;
                consider(i, KIND_SCREEN, lane, screenGap(n, x, y, ly), t);
                any = true;
                break;
            }
        }
        Note[] arr = freeStream[line];
        for (int c = freeCursor[line]; c < arr.length; c++) {
            Note n = arr[c];
            if (n.judgementMs() - TIER2_MS > t) break;
            int i = n.index();
            if (state[i] != STATE_PENDING || !screenNote[i] || !inWindow(i, t)) continue;
            if (inputLayout.keyWidth(n) < LaneLayout.MIN_ACTIVE_WIDTH) continue;
            consider(i, KIND_SCREEN, inputLayout.indexAtX(spot(n, inputLayout)), screenGap(n, x, y, ly), t);
            any = true;
        }
        return any;
    }

    /** @return how far a press at (x, y) is from key n of a line at ly: x first, the line's distance on a tie */
    private float screenGap(Note n, float x, float y, float ly) {
        return Math.abs(x - spot(n, inputLayout)) + 0.001f * Math.abs(y - ly);
    }

    private void clearBest() {
        bestNote = -1;
        bestT = 0;
        bestAbs = 0;
        bestKind = 0;
        bestLane = -1;
        bestLineDist = 0f;
    }

    private void consider(int i, int kind, int lane, float lineDist, double t) {
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
        bestLane = lane;
        bestLineDist = lineDist;
    }

    /** Judges the selected candidate for an input at (x, y) and judge time t. */
    private void judgeBest(int owner, int pointer, float x, float y, double t, int flags) {
        Note n = notes[bestNote];
        float d = (float) (t - n.judgementMs());
        Judgement j = Judgement.ofDelta(Math.abs(d));
        if (j == null) j = Judgement.ECHO; // |dt| ≤ TIER2 by construction; float rounding guard
        lastJudgement = j;
        float ly = n.isUpper() ? evUpperY : evLowerY;
        if (n.type() == NoteType.UNIVERSAL) {
            lastInputLane = -1;
            lastInputLine = n.line();
            judgeGroup(n, j, d, t, pointer, x, ly, x, y, flags);
        } else {
            lastInputLane = bestLane;
            lastInputLine = n.line();
            float cx = spot(n, inputLayout);
            judgeLaneNote(n.index(), j, d, t, owner, pointer, cx, ly, x, y, flags);
            // Joint judging (ARCHITECTURE §20): the TIDE group of this line at exactly this beat
            // is judged by the same tap or key, with the same grade and Δt.
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
        if (n.type() == NoteType.HOLD) {
            state[i] = STATE_HOLDING;
            active[activeCount++] = i;
            holdOwner[i] = OWNER_NONE;
            if (owner >= 0) {
                attachHold(i, owner, false);
            } else {
                holdOwner[i] = owner;
                if (owner <= OWNER_KEY_BASE) kHold[OWNER_KEY_BASE - owner] = i;
            }
            holdOffSince[i] = Float.NaN;
            emit(GameEvent.HOLD_START, j, d, x, y, touchX, touchY, placeLane(n), n.line(), i, 1, pointer, t, flags);
            if (t >= n.endMs()) completeHold(activeCount - 1, i, t);
        } else {
            state[i] = STATE_HIT;
            keeper.record(j, d);
            emit(GameEvent.HIT, j, d, x, y, touchX, touchY, placeLane(n), n.line(), i, 1, pointer, t, flags);
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

    private int neighbourLane(int own, float x) {
        LaneLayout lay = inputLayout;
        if (own >= 0) {
            float tol = NEIGHBOUR_TOLERANCE * lay.width(own);
            int k = x - lay.left(own) <= tol ? prevActive(lay, own) : lay.right(own) - x <= tol ? nextActive(lay, own)
                : -1;
            // moved columns (Kelvin 2026-11-07 22:54) need not be side by side: a neighbour only where it is near
            if (k >= 0 && lay.moving() && Math.max(lay.left(k) - x, x - lay.right(k)) > tol) return -1;
            return k;
        }
        // Outside the field or on a collapsed lane: the nearest active lane within its own 30 %.
        int best = -1;
        float bestGap = Float.MAX_VALUE;
        for (int i = 0; i < lay.count(); i++) {
            if (!lay.active(i)) continue;
            float gap;
            if (x < lay.left(i)) gap = lay.left(i) - x;
            else if (x > lay.right(i)) gap = x - lay.right(i);
            else continue;
            if (gap <= NEIGHBOUR_TOLERANCE * lay.width(i) && gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        return best;
    }

    private static int prevActive(LaneLayout lay, int i) {
        for (int k = i - 1; k >= 0; k--) if (lay.active(k)) return k;
        return -1;
    }

    private static int nextActive(LaneLayout lay, int i) {
        for (int k = i + 1; k < lay.count(); k++) if (lay.active(k)) return k;
        return -1;
    }

    // ------------------------------------------------------------------ DRAGs

    /** {@link #catchDragsAt} result: no DRAG caught. */
    private static final int CATCH_NONE = 0;
    /** {@link #catchDragsAt} result: a DRAG caught before its time (judged when its time comes). */
    private static final int CATCH_EARLY = 1;
    /** {@link #catchDragsAt} result: a DRAG judged at once (caught at or after its time). */
    private static final int CATCH_JUDGED = 2;

    /**
     * One input event at judge time t (the event's geometry), of a valid pointer at (x, y) or, with {@code slot}
     * ≥ 0, of that key going down: catches every pending DRAG there whose window holds t, and judges the caught
     * ones whose time has come.
     *
     * @return {@link #CATCH_JUDGED} when it judged one, else {@link #CATCH_EARLY} when it caught one, else
     *         {@link #CATCH_NONE}
     */
    private int catchDragsAt(double t, int pointer, int slot, float x, float y) {
        int result = CATCH_NONE;
        for (int l = 0; l < Line.COUNT; l++) {
            Note[] arr = dragStream[l];
            for (int c = dragCursor[l]; c < arr.length; c++) {
                Note n = arr[c];
                double T = n.judgementMs();
                if (T - TIER2_MS > t) break;
                int i = n.index();
                if (state[i] != STATE_PENDING) continue;
                float nx = dragX(n, inputLayout, evBeat);
                float ny = lineY(n.line(), evLowerY, evUpperY);
                if (!Float.isNaN(caughtMs[i])) { // caught already: judged at its time
                    if (t >= T) {
                        judgeDrag(i, T, pointer, inputLayout, nx, ny, x, y, 0);
                        result = CATCH_JUDGED;
                    }
                    continue;
                }
                boolean on = slot >= 0 ? keyOnDrag(slot, n, nx, inputLayout)
                    : onDrag(n, inputLayout, nx, seaX(n, x, pointer, inputLayout));
                if (t > T + lateWin[i] || !on) continue;
                if (t < T) {
                    catchEarly(i, t, pointer, inputLayout, nx, ny, x, y);
                    if (result == CATCH_NONE) result = CATCH_EARLY;
                } else {
                    judgeDrag(i, t, pointer, inputLayout, nx, ny, x, y, 0);
                    result = CATCH_JUDGED;
                }
            }
        }
        return result;
    }

    /**
     * Frame pass over the DRAGs (frame time t, the frame's geometry): judges the caught ones whose time has come at
     * their time, and lets every valid resting pointer or held key catch the others, so a finger already in place
     * (or a DRAG moving under a resting finger) catches it.
     */
    private void updateDrags(double t) {
        for (int l = 0; l < Line.COUNT; l++) {
            Note[] arr = dragStream[l];
            for (int c = dragCursor[l]; c < arr.length; c++) {
                Note n = arr[c];
                double T = n.judgementMs();
                if (T - TIER2_MS > t) break;
                int i = n.index();
                if (state[i] != STATE_PENDING) continue;
                float nx = dragX(n, frameLayout, frameBeat);
                float ny = lineY(n.line(), frameLowerY, frameUpperY);
                if (!Float.isNaN(caughtMs[i])) {
                    if (t >= T) judgeDrag(i, T, PTR_ENGINE, frameLayout, nx, ny, nx, ny, 0);
                    continue;
                }
                if (t > T + lateWin[i]) continue; // the sweep loses it
                int p = dragFinger(n, nx);
                if (p < 0 && dragKey(n, nx) < 0) continue;
                int ptr = p >= 0 ? p : PTR_KEY;
                float tx = p >= 0 ? pX[p] : nx;
                float ty = p >= 0 ? pY[p] : ny;
                if (t < T) catchEarly(i, t, ptr, frameLayout, nx, ny, tx, ty);
                else judgeDrag(i, t, ptr, frameLayout, nx, ny, tx, ty, 0);
            }
        }
    }

    /** @return the world x of DRAG n at {@code beat} in {@code lay}: its lane's centre, or its own x on a sea */
    private static float dragX(Note n, LaneLayout lay, float beat) {
        return lay.keyCentre(n, beat);
    }

    /** @return a valid down pointer on DRAG n (at {@code dragX}) with the frame's geometry, or −1 */
    private int dragFinger(Note n, float dragX) {
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p] && !pVeiled[p] && onDrag(n, frameLayout, dragX, seaX(n, pX[p], p, frameLayout))) return p;
        }
        return -1;
    }

    /** @return a down key slot on DRAG n (at {@code dragX}) with the frame's geometry, or −1 */
    private int dragKey(Note n, float dragX) {
        for (int s = 0; s < GameKeys.SLOTS; s++) if (kDown[s] && keyOnDrag(s, n, dragX, frameLayout)) return s;
        return -1;
    }

    /**
     * @return true when key slot s catches DRAG n at {@code dragX}: a key of its line whose lane's centre is as near
     *         as a finger catching it would be ({@link #onDrag}); Space and unmapped keys catch none
     */
    private boolean keyOnDrag(int s, Note n, float dragX, LaneLayout lay) {
        int lane = kLane[s];
        return lane >= 0 && lane < lay.count() && GameKeys.line(s) == n.line() && lay.active(lane)
            && reachesDrag(lay, n, dragX, lay.centre(lane));
    }

    /** True when a finger at x catches DRAG n at {@code dragX} ({@link #reachesDrag}); in full-screen time, any x. */
    private boolean onDrag(Note n, LaneLayout lay, float dragX, float x) {
        return reachesDrag(lay, n, dragX, screenNote[n.index()] ? dragX : x);
    }

    /**
     * True when x is within {@link #DRAG_REACH} of DRAG n's width ({@link LaneLayout#keyWidth}: its lane's, a default
     * lane's at its own x) of its x; one narrower than {@link LaneLayout#MIN_ACTIVE_WIDTH} cannot be caught.
     */
    private static boolean reachesDrag(LaneLayout lay, Note n, float dragX, float x) {
        float w = lay.keyWidth(n);
        return w >= LaneLayout.MIN_ACTIVE_WIDTH && Math.abs(x - dragX) <= DRAG_REACH * seaReach(n) * w;
    }

    /**
     * DRAG i caught at t before its time: judged when its time comes; emits {@link GameEvent#CATCH} (time = T), with
     * the lane of {@code lay} under its x.
     */
    private void catchEarly(int i, double t, int pointer, LaneLayout lay, float x, float y, float touchX,
                            float touchY) {
        Note n = notes[i];
        caughtMs[i] = (float) t;
        emit(GameEvent.CATCH, Judgement.RESONANCE, 0f, x, y, touchX, touchY, lay.indexAtX(x), n.line(), i, 1,
            pointer, n.judgementMs(), dragFlags(pointer));
    }

    /**
     * Judges DRAG i RESONANCE at {@code at}: Δt 0, PERFECT, no timing statistics; one {@link GameEvent#HIT}, with
     * the lane of {@code lay} under its x.
     */
    private void judgeDrag(int i, double at, int pointer, LaneLayout lay, float x, float y, float touchX,
                           float touchY, int flags) {
        Note n = notes[i];
        state[i] = STATE_HIT;
        grade[i] = (byte) Judgement.RESONANCE.ordinal();
        dt[i] = 0f;
        resolvedMs[i] = (float) at;
        if (noEcho[i]) guardLane(n, at);
        keeper.recordDrag();
        emit(GameEvent.HIT, Judgement.RESONANCE, 0f, x, y, touchX, touchY, lay.indexAtX(x), n.line(), i, 1, pointer,
            at, flags | dragFlags(pointer));
    }

    private static int dragFlags(int pointer) {
        return GameEvent.FLAG_DRAG | (pointer == PTR_KEY ? GameEvent.FLAG_KEYBOARD : 0);
    }

    // ------------------------------------------------------------------ holds

    /**
     * Gives running hold i to {@code owner}: a pointer (≥ 0) or a key slot
     * ({@code OWNER_KEY_BASE − slot}). With {@code regrab} a HOLD_START/REGRAB event is emitted at
     * the frame time.
     */
    private void attachHold(int i, int owner, boolean regrab) {
        holdOwner[i] = owner;
        if (owner >= 0) pHold[owner] = i;
        else if (owner <= OWNER_KEY_BASE) kHold[OWNER_KEY_BASE - owner] = i;
        holdOffSince[i] = Float.NaN;
        if (regrab) {
            Note n = notes[i];
            float x = spot(n, frameLayout);
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
            if (!onHold(n, x, pointer, inputLayout)) continue;
            holdOwner[i] = pointer;
            pHold[pointer] = i;
            holdOffSince[i] = Float.NaN;
            emitHold(GameEvent.HOLD_START, i, spot(n, inputLayout), lineY(n.line(), evLowerY, evUpperY),
                pointer, t, GameEvent.FLAG_REGRAB);
            return true;
        }
        return false;
    }

    private void detachHold(int i) {
        int o = holdOwner[i];
        if (o >= 0) pHold[o] = -1;
        else if (o <= OWNER_KEY_BASE) kHold[OWNER_KEY_BASE - o] = -1;
        holdOwner[i] = OWNER_DETACHED;
    }

    private void releaseOwner(int i) {
        int o = holdOwner[i];
        if (o >= 0) {
            if (pHold[o] == i) pHold[o] = -1;
        } else if (o <= OWNER_KEY_BASE) {
            int s = OWNER_KEY_BASE - o;
            if (kHold[s] == i) kHold[s] = -1;
        }
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
            ownerX(o), ownerY(o), placeLane(n), n.line(), i, 1, ownerPointer(o), at,
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
            lineY(n.line(), frameLowerY, frameUpperY), ownerX(o), ownerY(o), placeLane(n), n.line(), i, 1,
            ownerPointer(o), at, ownerFlags(o) | holdFlags(n));
    }

    /** Feedback x of a hold's events: where it is ({@link #spot}), or the window centre for a TIDE STREAM. */
    private float holdX(Note n) {
        if (n.isTideStream()) return (frameLayout.windowLeft() + frameLayout.windowRight()) * 0.5f;
        return spot(n, frameLayout);
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
        if (o >= 0) return o;
        return o <= OWNER_KEY_BASE ? PTR_KEY : PTR_ENGINE;
    }

    private static int ownerFlags(int o) {
        if (o == OWNER_AUTO) return GameEvent.FLAG_AUTO;
        return o <= OWNER_KEY_BASE ? GameEvent.FLAG_KEYBOARD : 0;
    }

    /**
     * True when x of pointer p keeps hold n (at any height, whole columns): within ±1 (active) lane of it; one placed
     * by position within {@link #FREE_HOLD_REACH} of its width of where it is (on a sea at its depth, {@link #seaX});
     * one of the full-screen time anywhere.
     */
    private boolean onHold(Note n, float x, int p, LaneLayout lay) {
        if (screenNote[n.index()]) return true;
        if (n.free()) return near(n, lay, seaX(n, x, p, lay), FREE_HOLD_REACH * seaReach(n));
        int lane = n.laneIndex();
        float w = lay.width(lane);
        // moved columns (Kelvin 2026-11-07 22:54) need not be side by side: a lane's width either side of its own
        if (lay.moving()) return x >= lay.left(lane) - w && x <= lay.right(lane) + w;
        int a = prevActive(lay, lane);
        int b = nextActive(lay, lane);
        float lo = a >= 0 ? lay.left(a) : lay.left(lane) - w;
        float hi = b >= 0 ? lay.right(b) : lay.right(lane) + w;
        return x >= lo && x <= hi;
    }

    // ------------------------------------------------------------------ mines and taps

    /**
     * The lane under each key placed by position (a DRAG too) at its time, in reference geometry ({@link #homeLane}):
     * the lane it counts as in where mines meet taps, and whose guard its RESONANCE starts. Called by {@link #load}
     * once the input layout has the lanes.
     */
    private void findHomeLanes(int n) {
        Arrays.fill(homeLane, 0, n, -1);
        for (int i = 0; i < n; i++) {
            Note u = notes[i];
            if (!u.free()) continue;
            float b = u.judgement();
            inputLayout.update(b, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
            homeLane[i] = inputLayout.indexAtX(spot(u, inputLayout));
        }
    }

    /** @return the lane note n counts as in: its own, or for one placed by position the lane under it at its time */
    private int placeLane(Note n) {
        return n.free() ? homeLane[n.index()] : n.laneIndex();
    }

    /**
     * @return where note n is in {@code lay} at its last update: {@link LaneLayout#keyCentre}, a space key's path
     *         ({@link Note#spacePath()}) with its x shift as drawn
     */
    private static float spot(Note n, LaneLayout lay) {
        float b = lay.beat();
        return lay.keyCentre(n, b) + (n.spacePath() ? n.offsetXAt(b) * lay.unitX() : 0f);
    }

    /** True when x is within {@code reach} of key n's width ({@link LaneLayout#keyWidth}) of where it is. */
    private static boolean near(Note n, LaneLayout lay, float x, float reach) {
        return Math.abs(x - spot(n, lay)) <= reach * lay.keyWidth(n);
    }

    /** {@link #near}, for a key at least {@link LaneLayout#MIN_ACTIVE_WIDTH} wide (a narrower one takes no input). */
    private static boolean reaches(Note n, LaneLayout lay, float x, float reach) {
        return lay.keyWidth(n) >= LaneLayout.MIN_ACTIVE_WIDTH && near(n, lay, x, reach * seaReach(n));
    }

    /** @return {@link #SEA_REACH} for a key on a sea, else 1 */
    private static float seaReach(Note n) {
        return n.sea() != null ? SEA_REACH : 1f;
    }

    /**
     * @return the x a press of pointer p at x is judged at for key n: for a key on a sea where the press's ray is at
     *         the key's own depth (Kelvin 2026-11-08 19:43; {@link #pSeaX}, {@link #keyDepth}), so a key nearer or
     *         deeper on its sea is met where it lands, at any height (a space key's path, {@link Note#spacePath()}:
     *         at its depth too); else x (also with no pointer, p &lt; 0)
     */
    private float seaX(Note n, float x, int p, LaneLayout lay) {
        boolean path = n.spacePath(); // a space key's path: met at its own depth too
        if (p < 0 || p >= MAX_POINTERS || n.sea() == null && !path) return x;
        Sea s = n.sea();
        float z = keyDepth(n, lay);
        float beat = lay.beat();
        if (!solid || path || !s.turn(beat, seaAxes)) return pSeaX[p] + pSeaG[p] * z;
        // a turned sea (Kelvin 2026-11-09 00:13): the key lands where its spot on the level sea turns to; the press
        // is judged there, as far from the key's own x as it is from that spot
        float c = lay.keyCentre(n, beat);
        float dx = c - lay.freeX(s.at(Sea.ORIGIN_X, beat));
        float dz = (z - Sea.MID_Z - s.at(Sea.ORIGIN_Z, beat)) * Field.ky();
        float landX = c - dx + seaAxes[0] * dx + seaAxes[6] * dz;
        float landZ = Sea.MID_Z + s.at(Sea.ORIGIN_Z, beat) + (seaAxes[2] * dx + seaAxes[8] * dz) / Field.ky();
        return pSeaX[p] + pSeaG[p] * landZ + c - landX;
    }

    /** @return how deep key n is at {@code lay}'s beat, reference units: its column's and its own {@link Note#space} */
    private static float keyDepth(Note n, LaneLayout lay) {
        Channels c = n.space();
        return lay.keyMoveZ(n) + (c != null ? c.at(Channels.Z, lay.beat()) : 0f);
    }

    /**
     * True when lane key slot s plays note n of its line: n is in the key's lane, or is placed by position and a
     * finger at the key's lane centre reaches it ({@link #DRAG_REACH}); Space and unmapped keys play none.
     */
    private boolean keyOn(int s, Note n, LaneLayout lay) {
        int lane = kLane[s];
        if (lane < 0 || lane >= lay.count() || s >= GameKeys.SPACE || GameKeys.line(s) != n.line()) return false;
        if (!n.free()) return n.laneIndex() == lane;
        return lay.active(lane) && reaches(n, lay, lay.centre(lane), DRAG_REACH);
    }

    /**
     * Mines and taps (Kelvin 2026-10-31) and LANE MINE priority (Kelvin 2026-10-14), see the class doc: pairs every
     * MINE with the taps / STREAM heads of its lane and line whose ±TIER2 window meets its window, and every LANE
     * MINE with those whose RESONANCE window meets its block, then sets how long each note can be judged. Called by
     * {@link #load}; allocates only for a chart with more such pairs than any before.
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
                int lane = tide ? -1 : placeLane(m);
                if (!tide && (lane < 0 || lane >= laneCount)) continue;
                shadowFrom[m.index()] = at;
                int l = m.line().ordinal();
                // a TIDE MINE covers its whole line: the taps / STREAM heads of every lane and those placed by
                // position, then its TIDEs; a LANE MINE or TIDE STREAM MINE then also gives way to the DRAGs in its
                // place (a plain MINE needs a press, a DRAG none: the two do not cut each other)
                int c = pass == 0 ? pairs : at;
                for (int r = tide ? 0 : lane, end = tide ? laneCount - 1 : lane; r <= end; r++) {
                    c = pairUp(pass, c, m, laneStream[l * laneCount + r], -1, lo, hi, w, laneMine);
                }
                c = pairUp(pass, c, m, freeStream[l], lane, lo, hi, w, laneMine);
                if (tide) c = pairUp(pass, c, m, uniStream[l], -1, lo, hi, w, laneMine);
                if (laneMine) c = pairUp(pass, c, m, dragStream[l], lane, lo, hi, w, laneMine);
                if (pass == 0) pairs = c;
                else at = c;
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
     * One {@link #findShadows} scan of mine m over the notes {@code arr} (only those in lane {@code lane} by
     * {@link #homeLane} unless it is −1) whose window meets [lo, hi] by {@code w}: pass 0 counts the pairs and the
     * cuts, pass 1 records them. @return the pair count (pass 0) or the next free {@link #shadowNote} slot
     */
    private int pairUp(int pass, int c, Note m, Note[] arr, int lane, double lo, double hi, double w,
                       boolean laneMine) {
        int mi = m.index();
        for (Note u : arr) {
            double T = u.judgementMs();
            if (T + w < lo) continue;
            if (T - w > hi) break;
            if (laneMine && T - w >= hi) continue; // a LANE MINE's span is [T, end)
            int ui = u.index();
            if (lane >= 0 && placeLane(u) != lane) continue;
            c++;
            if (pass == 0) {
                if (!laneMine) cutLen[ui]++;
                continue;
            }
            shadowNote[c - 1] = ui;
            shadowLen[mi]++;
            if (laneMine) noEcho[ui] = true;
            else cutMine[cutFrom[ui] + cutLen[ui]++] = mi;
        }
        return c;
    }

    /** How long after its time note i can still be judged: TIER2, less where MINE windows cover its late ECHO. */
    private float windowEnd(int i) {
        if (noEcho[i]) return TIER1_MS;
        double T = notes[i].judgementMs();
        double e = T + TIER2_MS;
        for (boolean moved = true; moved && e > T + TIER1_MS; ) {
            moved = false;
            for (int k = cutFrom[i], end = k + cutLen[i]; k < end; k++) {
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
     * the windows of the MINEs that cut it (none when a LANE MINE gives way to the note).
     */
    private boolean inWindow(int i, double t) {
        double d = t - notes[i].judgementMs();
        if (d >= -TIER1_MS && d <= TIER1_MS) return true;
        if (noEcho[i] || d < -TIER2_MS || d > TIER2_MS) return false;
        for (int k = cutFrom[i], e = k + cutLen[i]; k < e; k++) {
            if (Math.abs(t - notes[cutMine[k]].judgementMs()) <= MINE_WINDOW_MS) return false;
        }
        return true;
    }

    /**
     * True while mine i is off at t: inside the RESONANCE window of a note it is paired with (cut from a MINE's
     * window; a LANE MINE sleeps there), or, for a LANE MINE, inside the RESONANCE guard of its lane and line.
     */
    private boolean asleep(int i, double t) {
        for (int k = shadowFrom[i], e = k + shadowLen[i]; k < e; k++) {
            double T = notes[shadowNote[k]].judgementMs();
            if (t >= T - TIER1_MS && t <= T + TIER1_MS) return true;
        }
        Note m = notes[i];
        if (!m.isBlockMine()) return false;
        int s = Line.COUNT * laneCount + m.line().ordinal(); // a TIDE STREAM MINE: its line's guard
        if (!m.isTideMine()) {
            int lane = placeLane(m);
            if (lane < 0 || lane >= laneCount) return false;
            s = m.line().ordinal() * laneCount + lane;
        }
        return t >= guardFrom[s] && t <= guardTo[s];
    }

    /**
     * RESONANCE guard (Kelvin 2026-10-30): a RESONANCE at t on a note a LANE MINE gives way to keeps its lane's
     * LANE MINEs asleep until t + RESONANCE_GUARD_MS; a guard still running is extended.
     */
    private void guardLane(Note n, double t) {
        int lane = placeLane(n);
        if (lane >= 0 && lane < laneCount) guard(n.line().ordinal() * laneCount + lane, t);
        guard(Line.COUNT * laneCount + n.line().ordinal(), t); // and its line's TIDE STREAM MINEs
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
        float ly = lineY(m.line(), lowerY, upperY); // a press on a lane with lines of its own comes at the chart's
        if (Math.abs(y - ly) > Field.zoneHalf()) return false;
        if (m.isTideMine()) return true; // anywhere in its line's zone, as a TIDE
        return near(m, lay, x, MINE_BOX * 0.5f);
    }

    /** Mines under a pointer event at t: MINEs only on a press (touch-down), LANE MINEs on every event. */
    private void checkMinesAt(double t, float x, float y, int pointer, boolean press) {
        for (int k = mineLo; press && k < mineCount; k++) {
            Note m = mines[k];
            double T = m.judgementMs();
            if (T - MINE_WINDOW_MS > t) break;
            int i = m.index();
            if (state[i] != STATE_PENDING || t > T + MINE_WINDOW_MS) continue;
            if (onMine(m, seaX(m, x, pointer, inputLayout), y, inputLayout, evLowerY, evUpperY) && !asleep(i, t)) {
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

    /** Mines of key slot s's lane at t: MINEs only on a press (key-down), LANE MINEs on every event. */
    private void checkKeyMines(int s, double t, boolean press) {
        int lane = kLane[s];
        Line line = GameKeys.line(s);
        for (int k = mineLo; press && k < mineCount; k++) {
            Note m = mines[k];
            double T = m.judgementMs();
            if (T - MINE_WINDOW_MS > t) break;
            int i = m.index();
            if (state[i] != STATE_PENDING || t > T + MINE_WINDOW_MS) continue;
            if (keyOnMine(s, m, inputLayout, MINE_BOX) && m.line() == line && !asleep(i, t)) {
                touchMine(i, keyX(s, inputLayout), lineY(line, evLowerY, evUpperY), PTR_KEY, t, inputLayout,
                    evLowerY, evUpperY, GameEvent.FLAG_KEYBOARD);
            }
        }
        for (int k = laneMineLo; k < laneMineCount; k++) {
            Note m = laneMineArr[k];
            if (m.judgementMs() - LANE_MINE_LEAD_MS > t) break;
            if (t >= m.endMs() || !keyOnMine(s, m, inputLayout, LANE_MINE_BOX) || m.line() != line) continue;
            observeKey(m.index(), s, t, inputLayout, evLowerY, evUpperY);
        }
    }

    /**
     * True when key slot s is on mine m of its line, given that line: always for a TIDE (STREAM) MINE, else when m
     * is in the key's lane, or is placed by position and the key's lane centre is inside {@code box} of its width.
     */
    private boolean keyOnMine(int s, Note m, LaneLayout lay, float box) {
        if (m.isTideMine()) return true;
        int lane = kLane[s];
        if (lane < 0 || s >= GameKeys.SPACE) return false;
        if (!m.free()) return m.laneIndex() == lane;
        return lane < lay.count() && lay.active(lane) && near(m, lay, lay.centre(lane), box * 0.5f);
    }

    /** Where key slot s plays: its lane's centre, or the window's middle for Space and unmapped keys. */
    private float keyX(int s, LaneLayout lay) {
        int lane = kLane[s];
        return lane >= 0 ? lay.centre(lane) : (lay.windowLeft() + lay.windowRight()) * 0.5f;
    }

    // ------------------------------------------------------------------ LANE MINEs

    /**
     * True when (x, y) is in a LANE MINE's contact box: inside its line's zone and inside the
     * central {@link #LANE_MINE_BOX} of its lane's current span (edges included).
     */
    private static boolean onLaneMine(Note m, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        float ly = lineY(m.line(), lowerY, upperY); // as onMine
        if (Math.abs(y - ly) > Field.zoneHalf()) return false;
        if (m.isTideMine()) return true; // a TIDE STREAM MINE: its line's whole zone
        return near(m, lay, x, LANE_MINE_BOX * 0.5f);
    }

    /**
     * Frame pass over the LANE MINEs (frame time t): ends the ones whose block is over (with the
     * sweep slack, so an input event stamped just before the end still counts), then observes the
     * resting pointers and held keys of the blocking ones, so a finger or key already in place
     * when a block starts (or a lane moving under a resting finger) triggers once.
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
            for (int s = 0; s < GameKeys.SLOTS; s++) { // a TIDE STREAM MINE takes every key of its line
                if (kDown[s] && GameKeys.line(s) == m.line() && keyOnMine(s, m, frameLayout, LANE_MINE_BOX)) {
                    observeKey(i, s, t, frameLayout, frameLowerY, frameUpperY);
                }
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
        if (asleep(i, t)) return; // giving way to a note: a finger still inside counts when it wakes
        minePtrMask[i] |= bit;
        laneMineContact(i, x, y, p, t, lay, lowerY, upperY, 0);
    }

    /** One observation of key slot s (down, on LANE MINE i's lane and line) at time t. */
    private void observeKey(int i, int s, double t, LaneLayout lay, float lowerY, float upperY) {
        int bit = 1 << s;
        if ((mineKeyMask[i] & bit) != 0) return;
        if (asleep(i, t)) return;
        mineKeyMask[i] |= bit;
        Note m = notes[i];
        float x = m.isTideMine() ? keyX(s, lay) : spot(m, lay);
        laneMineContact(i, x, lineY(m.line(), lowerY, upperY), PTR_KEY, t, lay, lowerY, upperY,
            GameEvent.FLAG_KEYBOARD);
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
            placeLane(m), m.line(), i, c, pointer, t, flags | GameEvent.FLAG_LANE_MINE);
    }

    /** Ends LANE MINE i's block: untouched → passed (at its end); the in-box masks are cleared. */
    private void endLaneMine(int i) {
        if (state[i] == STATE_PENDING) {
            state[i] = STATE_MINE_PASSED;
            resolvedMs[i] = (float) notes[i].endMs();
        }
        minePtrMask[i] = 0;
        mineKeyMask[i] = 0;
    }

    /** Pointer p went up (or was replaced): it no longer counts as inside any LANE MINE box. */
    private void forgetLaneMinePointer(int p) {
        int keep = ~(1 << p);
        for (int k = laneMineLo; k < laneMineCount; k++) minePtrMask[laneMineArr[k].index()] &= keep;
    }

    /** Key slot s went up: it no longer counts as held on any LANE MINE. */
    private void forgetLaneMineKey(int s) {
        int keep = ~(1 << s);
        for (int k = laneMineLo; k < laneMineCount; k++) mineKeyMask[laneMineArr[k].index()] &= keep;
    }

    private void touchMine(int i, float x, float y, int pointer, double t, LaneLayout lay, float lowerY,
                           float upperY, int flags) {
        Note m = notes[i];
        state[i] = STATE_MINE_TOUCHED;
        resolvedMs[i] = (float) t;
        mineEvents++;
        keeper.mineTouched();
        emit(GameEvent.MINE, null, (float) (t - m.judgementMs()), x, lineY(m.line(), lowerY, upperY), x, y,
            placeLane(m), m.line(), i, 1, pointer, t, flags);
    }

    // ------------------------------------------------------------------ LOST

    private void loseNote(int i) {
        Note n = notes[i];
        state[i] = STATE_LOST;
        grade[i] = (byte) Judgement.LOST.ordinal();
        double at = n.judgementMs() + lateWin[i];
        resolvedMs[i] = (float) at;
        keeper.record(Judgement.LOST, 0f);
        float x = spot(n, frameLayout);
        float y = lineY(n.line(), frameLowerY, frameUpperY);
        emit(GameEvent.LOST, Judgement.LOST, 0f, x, y, x, y, n.free() ? frameLayout.indexAtX(x) : n.laneIndex(),
            n.line(), i, 1, PTR_ENGINE, at, n.type() == NoteType.DRAG ? GameEvent.FLAG_DRAG : 0);
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
    }

    private void emitEmpty(float x, float y, int lane, int line, int pointer, double t, int flags) {
        emit(GameEvent.EMPTY, null, 0f, x, y, x, y, lane, line >= 0 ? Line.of(line) : null, -1, 0, pointer, t,
            flags | (pointer == PTR_KEY ? GameEvent.FLAG_KEYBOARD : 0));
    }

    private void emitHold(int type, int i, float x, float y, int pointer, double t, int flags) {
        Note n = notes[i];
        emit(type, Judgement.byOrdinal(grade[i]), dt[i], x, y, x, y, placeLane(n), n.line(), i, 1,
            pointer >= 0 ? pointer : PTR_KEY, t, flags);
    }

    private void emit(int type, Judgement j, float d, float x, float y, float touchX, float touchY, int lane,
                      Line line, int note, int count, int pointer, double t, int flags) {
        int slot;
        if (evSize == EVENT_CAPACITY) {
            slot = evHead;
            evHead = (evHead + 1) & (EVENT_CAPACITY - 1);
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
        evLine[slot] = (byte) (line != null ? line.ordinal() : -1);
        evNote[slot] = note;
        evCount[slot] = count;
        evPointer[slot] = pointer;
        evTime[slot] = t;
        evFlags[slot] = flags;
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
        out.line = evLine[s] >= 0 ? Line.of(evLine[s]) : null;
        out.note = evNote[s];
        out.count = evCount[s];
        out.pointer = evPointer[s];
        out.timeMs = evTime[s];
        out.flags = evFlags[s];
        return true;
    }

    // ================================================================== helpers

    private static float lineY(Line line, float lowerY, float upperY) {
        return line == Line.UPPER ? upperY : lowerY;
    }

    /** @return the ordinal of the line closer to y, the lower one on a tie (whole columns: a touch is on both). */
    private static int nearerLine(float y, float lowerY, float upperY) {
        return Math.abs(y - upperY) < Math.abs(y - lowerY) ? Line.UPPER.ordinal() : Line.LOWER.ordinal();
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

    /** @return the lane layout at the last frame's visual time (shared; do not update it). */
    public LaneLayout layout() {
        return frameLayout;
    }

    /** @return y of {@code line} at the last frame's visual time. */
    public float lineY(Line line) {
        return line == Line.UPPER ? frameUpperY : frameLowerY;
    }

    /**
     * @return the world y note n lands on this frame: its line's (where its lane's column is moved, Kelvin 2026-11-07
     *         22:54), or on a sea ({@link Note#sea()}, 16:04) the sea's surface under its key (a TIDE: under the middle
     *         of the sea's part of the window)
     */
    public float landY(Note n) {
        Sea s = n.sea();
        if (s == null || n.spacePath()) return lineY(n.line()) + frameLayout.keyLift(n) * Field.ky();
        float x;
        if (n.type().inLane()) {
            x = frameLayout.keyCentre(n, frameBeat);
        } else {
            float l = Math.max(frameLayout.windowLeft(), frameLayout.freeX(s.xMin(frameBeat)));
            float r = Math.min(frameLayout.windowRight(), frameLayout.freeX(s.xMax(frameBeat)));
            x = 0.5f * (l + r);
        }
        return solid ? Field.y(s.yAt(frameBeat)) : seaY(s, frameLayout, frameBeat, x);
    }

    /**
     * Sets whether the field is drawn in 3D this frame ({@link View3D#active()}): a turned sea is then drawn level and
     * turned in 3D ({@link View3D#seaFrame}), so its keys land on it level ({@link #landY}); flat, on its slope.
     */
    public void setSolid(boolean solid) {
        this.solid = solid;
    }

    /**
     * @return the world y of sea s's surface over world x at {@code beat} on layout {@code lay} in the flat picture:
     *         through its origin's height along its across axis as turned (Kelvin 2026-11-09 00:13)
     */
    public static float seaY(Sea s, LaneLayout lay, float beat, float x) {
        float y = Field.y(s.yAt(beat));
        float[] a = SEA_Y_AXES;
        if (!s.turn(beat, a)) return y;
        float cos = Math.max(0.05f, Math.abs(a[0]));
        return y + (x - lay.freeX(s.at(Sea.ORIGIN_X, beat))) * a[1] / (a[0] < 0f ? -cos : cos);
    }

    /** @return the state of note i ({@code STATE_*}). */
    public byte noteState(int i) {
        return state[i];
    }

    /** @return the grade of note i (hold: head grade while running), or null when not judged. */
    public Judgement noteJudgement(int i) {
        return grade[i] >= 0 ? Judgement.byOrdinal(grade[i]) : null;
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
     * @return true while hold i runs and its finger (or key, or autoplay) is on it; for a TIDE
     *         STREAM, while it runs and has a finger of its own on its line
     */
    public boolean isHoldHeld(int i) {
        return state[i] == STATE_HOLDING && holdOwner[i] != OWNER_DETACHED && Float.isNaN(holdOffSince[i]);
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

    /**
     * True when a press on lane {@code lane} would have a key to judge now (Kelvin 2026-11-10 02:10 「这些按键根本点不到」):
     * a pending tap, hold head or DRAG of it within {@link #AWAIT_MS} of the last frame's judge time, or a hold of it
     * running. {@link View3D#toField} gives a press whose ray meets several columns to the front one of such a lane,
     * not to scenery or a hidden lane in front of it.
     */
    public boolean awaitsPress(int lane) {
        if (chart == null || lane < 0 || lane >= laneCount) return false;
        double t = frameJudgeMs;
        for (int l = 0; l < Line.COUNT; l++) {
            int k = l * laneCount + lane;
            Note[] arr = laneStream[k];
            for (int c = laneCursor[k]; c < arr.length && arr[c].judgementMs() - AWAIT_MS <= t; c++) {
                if (state[arr[c].index()] == STATE_PENDING && t <= arr[c].judgementMs() + AWAIT_MS) return true;
            }
            Note[] drags = dragStream[l];
            for (int c = dragCursor[l]; drags != null && c < drags.length; c++) {
                Note n = drags[c];
                if (n.judgementMs() - AWAIT_MS > t) break;
                if (n.laneIndex() == lane && state[n.index()] == STATE_PENDING && t <= n.judgementMs() + AWAIT_MS) {
                    return true;
                }
            }
        }
        for (int k = 0; k < activeCount; k++) {
            if (notes[active[k]].laneIndex() == lane) return true;
        }
        return false;
    }

    /** How far before and after a key's time its lane takes a press first ({@link #awaitsPress}), ms. */
    private static final double AWAIT_MS = TIER2_MS + 50.0;

    /** @return lane index under pointer p at the last frame, −1 off the lanes (or up, or pressed in a veil). */
    public int pointerLane(int p) {
        return pLane[p];
    }

    /** @return the line closer to pointer p at the last frame, or null (up, or pressed in a veil). */
    public Line pointerLine(int p) {
        return pLine[p] >= 0 ? Line.of(pLine[p]) : null;
    }

    /** @return the hold note index pointer p is holding, or −1. */
    public int pointerHold(int p) {
        return pHold[p];
    }

    /** @return true while key slot s ({@link GameKeys}) is down. */
    public boolean keyDown(int slot) {
        return kDown[slot];
    }

    /** @return lane index of key slot s in this chart, or −1 (Space, or the chart lacks the lane). */
    public int keyLane(int slot) {
        return kLane[slot];
    }

    /** @return the grade judged by the last touchDown / keyDown, or null. */
    public Judgement lastInputJudgement() {
        return lastJudgement;
    }

    /** @return lane resolved by the last touchDown / keyDown (judged lane incl. neighbour), or −1. */
    public int lastInputLane() {
        return lastInputLane;
    }

    /** @return zone line of the last touchDown / keyDown, or null (stray). */
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
