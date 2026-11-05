package org.kelvinizer.gameplay;

import org.kelvinizer.Params;
import org.kelvinizer.audio.SongClock;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.JudgementLine;
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
 * <h2>Judging (one tap judges one target, a TIDE group counts as one; plus its sync partner where the zones
 * overlap)</h2>
 * <ul>
 *   <li>A touch is in a line's zone when {@code |y − lineY| ≤ 480·ky} ({@link Field#zoneHalf}: 0.4 of the screen
 *       height, so roughly half the screen per line). Outside both zones it judges nothing. Where the zones overlap,
 *       a touch that judges a note also judges the other line's pending note in the same lane with exactly the same
 *       judgement beat, and that line's TIDE group at that beat ({@code judgeOtherLine}).</li>
 *   <li>Candidates, per zone the touch is in: the earliest unjudged tap/hold head with
 *       {@code |dt| ≤ TIER2} in the lane under x ({@link LaneLayout#indexAtX}); if that lane has
 *       none and x is within 30 % of the lane width of a boundary, the neighbouring lane; plus the
 *       earliest unjudged TIDE note of that line. The earliest judgement time wins; on a tie the
 *       smaller |dt|, then lane notes before TIDE, own lane before neighbour, the closer line, and
 *       finally the lower line (only when both lines coincide and everything else is equal).</li>
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
 *       before {@code end − HOLD_RELEASE_GRACE_MS}, or stays off its zone and lane (±1 lane) for
 *       more than {@link #HOLD_LEAVE_MS}, the hold breaks (LOST) even if another finger rests on
 *       the lane. Otherwise it is counted with the head grade when it completes. Leaving at or after
 *       {@code end − HOLD_RELEASE_GRACE_MS} is treated like a release there (completes). A finger
 *       that left and is lifted before it comes back counts as released when it left, so lifting
 *       and staying down are judged the same. A pointer id reused after a lost touch-up releases
 *       the old hold first, so it never inherits it. The only exception is the pause: a pause never
 *       breaks a hold; a pointer or key lifted while paused detaches it, and any free pointer on it,
 *       or that lane's key down, within 100 ms after the resume re-grabs it (resuming needs a
 *       lifted finger).</li>
 *   <li><b>TIDE STREAMs are held by coverage, one finger each, not by ownership.</b> The head is
 *       judged like a TIDE drop (any tap in the line's zone at any x; keyboard any key of the line's
 *       row, Space for the lower line). A line's fingers are its down pointers inside its zone
 *       ({@code |y − lineY| ≤ 480·ky}, {@link Field#zoneHalf}; any x, pointers holding lane STREAMs
 *       included) plus the keys of its row that are down. Each running TIDE STREAM needs one of
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
    /** Half height of a judgement zone in reference units; in the world it is {@link Field#zoneHalf} (480·ky). */
    public static final float ZONE_HALF = Params.JUDGE_ZONE_HALF;
    /** RESONANCE window half width (ms). */
    public static final float TIER1_MS = Params.TIER1_MS;
    /** ECHO window half width (ms); beyond it a note is not judgeable. */
    public static final float TIER2_MS = Params.TIER2_MS;
    /** Neighbour-lane tolerance as a fraction of the touched lane's current width. */
    public static final float NEIGHBOUR_TOLERANCE = 0.30f;
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
    /** Input result: touch outside both zones (free for HUD controls). */
    public static final int INPUT_STRAY = 1;
    /** Input result: inside a zone (or a gameplay key) but nothing to judge. */
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
    private static final int OWNER_KEY_BASE = -100;

    private static final int KIND_OWN = 0;
    private static final int KIND_NEIGHBOUR = 1;
    private static final int KIND_UNIVERSAL = 2;

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
    private final int[] pHold = new int[MAX_POINTERS];
    private final int[] pLane = new int[MAX_POINTERS];
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
    private int evDropped;

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

        laneCount = chart.laneCount();
        int streams = Line.COUNT * laneCount;
        if (laneStream.length < streams) {
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
        findShadows(n);
        int holds = Math.max(4, chart.count(NoteType.HOLD) + chart.tideStreamCount());
        if (active.length < holds) {
            active = new int[holds];
            coverOrder = new int[holds];
        }
        activeCount = 0;

        frameLayout.setLanes(chart);
        inputLayout.setLanes(chart);
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
        for (int k = 0; k < Line.COUNT * laneCount; k++) {
            Note[] arr = laneStream[k];
            int c = laneCursor[k];
            while (c < arr.length) {
                Note n = arr[c];
                int i = n.index();
                if (state[i] == STATE_PENDING) {
                    if (n.judgementMs() > t) break;
                    float x = frameLayout.centre(n.laneIndex());
                    float y = lineY(n.line(), frameLowerY, frameUpperY);
                    judgeLaneNote(i, Judgement.RESONANCE, 0f, n.judgementMs(), OWNER_AUTO, PTR_ENGINE, x, y, x, y,
                        GameEvent.FLAG_AUTO);
                }
                c++;
            }
            laneCursor[k] = c;
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
    }

    private void resolvePointers() {
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (!pDown[p] || pVeiled[p]) {
                pLane[p] = -1;
                pLine[p] = -1;
                continue;
            }
            int line = zoneLine(pY[p], frameLowerY, frameUpperY);
            pLine[p] = line;
            pLane[p] = line >= 0 ? frameLayout.indexAtX(pX[p]) : -1;
        }
    }

    private void updateHolds(double t) {
        for (int k = activeCount - 1; k >= 0; k--) {
            int i = active[k];
            Note n = notes[i];
            int o = holdOwner[i];
            boolean on;
            if (o == OWNER_AUTO) {
                on = true;
            } else if (o == OWNER_COVER) {
                continue;
            } else if (o >= 0) {
                on = pDown[o] && !pVeiled[o] && onHold(n, pX[o], pY[o], frameLayout, frameLowerY, frameUpperY);
            } else if (o <= OWNER_KEY_BASE) {
                on = kDown[OWNER_KEY_BASE - o];
            } else {
                on = regrabByPointers(i, n) || regrabByKeys(i, n);
            }
            stepHold(k, i, on, t);
        }
        stepCoverage(t, frameLowerY, frameUpperY);
    }

    /**
     * A lane key that is down and free (pressed again while paused, or held through the resume)
     * takes over a detached hold of its lane and line.
     */
    private boolean regrabByKeys(int i, Note n) {
        for (int s = 0; s < GameKeys.SPACE; s++) {
            if (kDown[s] && kHold[s] < 0 && kLane[s] == n.laneIndex() && GameKeys.line(s) == n.line()) {
                attachHold(i, OWNER_KEY_BASE - s, true);
                return true;
            }
        }
        return false;
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
     * (the event's after a pointer or key changed, the frame's once per frame). Per line the
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
     *         at any x (whatever else they hold, but not one that went down inside a veil) plus keys
     *         of its row down (Space counts for the lower line)
     */
    private int fingers(Line line, float lowerY, float upperY) {
        float ly = lineY(line, lowerY, upperY);
        int count = 0;
        for (int p = 0; p < MAX_POINTERS; p++) {
            if (pDown[p] && !pVeiled[p] && Math.abs(pY[p] - ly) <= Field.zoneHalf()) count++;
        }
        for (int s = 0; s < GameKeys.SLOTS; s++) {
            if (kDown[s] && GameKeys.line(s) == line) count++;
        }
        return count;
    }

    private void sweep(double sweepT) {
        for (int k = 0; k < Line.COUNT * laneCount; k++) {
            Note[] arr = laneStream[k];
            int c = laneCursor[k];
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
            laneCursor[k] = c;
            // a note whose window a mine cuts short can run out before an earlier one
            for (int c2 = c + 1; c2 < arr.length; c2++) {
                Note n = arr[c2];
                if (n.judgementMs() + TIER1_MS >= sweepT) break;
                int i = n.index();
                if (state[i] == STATE_PENDING && n.judgementMs() + lateWin[i] < sweepT) loseNote(i);
            }
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
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS) return INPUT_IGNORED;
        if (pDown[pointer]) release(pointer, nanos, false); // a lost touchUp: release the old press first
        pDown[pointer] = true;
        pX[pointer] = x;
        pY[pointer] = y;
        pHold[pointer] = -1;
        lastJudgement = null;
        lastInputLane = -1;
        lastInputLine = null;
        lastInputMine = false;
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
        boolean inLower = Math.abs(y - evLowerY) <= Field.zoneHalf();
        boolean inUpper = Math.abs(y - evUpperY) <= Field.zoneHalf();
        int own = inputLayout.indexAtX(x);
        int zone = zoneLine(y, evLowerY, evUpperY);
        lastInputLane = own;
        lastInputLine = zone >= 0 ? Line.of(zone) : null;
        if (!inLower && !inUpper) {
            emitEmpty(x, y, own, -1, pointer, t, 0);
            return INPUT_STRAY;
        }
        if (autoplay) {
            emitEmpty(x, zone == Line.UPPER.ordinal() ? evUpperY : evLowerY, own, zone, pointer, t,
                GameEvent.FLAG_AUTO);
            return INPUT_EMPTY;
        }
        int minesBefore = mineEvents;
        checkMinesAt(t, x, y, pointer, true);
        lastInputMine = mineEvents != minesBefore;
        if (regrabAt(pointer, x, y, t)) return INPUT_REGRAB;

        int nb = neighbourLane(own, x);
        clearBest();
        for (int l = 0; l < Line.COUNT; l++) {
            float ly = l == 0 ? evLowerY : evUpperY;
            float dist = Math.abs(y - ly);
            if (dist > Field.zoneHalf()) continue;
            int c = own >= 0 ? laneCandidate(l, own, t) : -1;
            if (c >= 0) {
                consider(c, KIND_OWN, own, dist, t);
            } else if (nb >= 0) {
                c = laneCandidate(l, nb, t);
                consider(c, KIND_NEIGHBOUR, nb, dist, t);
            }
            consider(universalCandidate(l, t, c >= 0), KIND_UNIVERSAL, -1, dist, t);
        }
        if (bestNote < 0) {
            emitEmpty(x, zone == Line.UPPER.ordinal() ? evUpperY : evLowerY, own, zone, pointer, t, 0);
            return INPUT_EMPTY;
        }
        judgeBest(pointer, pointer, x, y, t, bestKind == KIND_NEIGHBOUR ? GameEvent.FLAG_NEIGHBOUR : 0);
        if (inLower && inUpper) judgeOtherLine(pointer, x, y, t, own);
        return INPUT_JUDGED;
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
        if (c >= 0) {
            int keep = pHold[pointer];
            judgeLaneNote(c, j, d, t, pointer, pointer, inputLayout.centre(lane), ly, x, y, GameEvent.FLAG_JOINT);
            if (keep >= 0) pHold[pointer] = keep;
        }
        int g = pendingTideAt(other, n.judgement());
        if (g >= 0) judgeGroup(notes[g], j, d, t, pointer, x, ly, x, y, GameEvent.FLAG_JOINT);
    }

    /** @return true when some lane of {@code line} has a pending tap / hold at exactly {@code beat} */
    private boolean pendingLaneAt(int line, float beat) {
        for (int lane = 0; lane < laneCount; lane++) if (pendingLaneAt(line, lane, beat) >= 0) return true;
        return false;
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
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        pX[pointer] = x;
        pY[pointer] = y;
        if (phase != Phase.RUNNING || autoplay || pVeiled[pointer]) return;
        double t = eventJudgeMs(nanos);
        eventGeometry(t);
        checkMinesAt(t, x, y, pointer, false);
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
     * A pointer was cancelled (system gesture, transition lock): treated as a release at its last
     * position.
     */
    public void touchCancelled(int pointer, long nanos) {
        if (chart == null || pointer < 0 || pointer >= MAX_POINTERS || !pDown[pointer]) return;
        release(pointer, nanos, true);
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
        stepCoverage(t, evLowerY, evUpperY);
        checkKeyMines(s, t, true);
        // Re-grab a hold of this key's lane detached by a pause.
        if (lane >= 0) {
            for (int k = 0; k < activeCount; k++) {
                int i = active[k];
                Note n = notes[i];
                if (holdOwner[i] == OWNER_DETACHED && n.laneIndex() == lane && n.line() == line) {
                    attachHold(i, OWNER_KEY_BASE - s, false);
                    emitHold(GameEvent.HOLD_START, i, x, ly, PTR_KEY, t,
                        GameEvent.FLAG_KEYBOARD | GameEvent.FLAG_REGRAB);
                    return INPUT_REGRAB;
                }
            }
        }
        clearBest();
        int l = line.ordinal();
        int c = lane >= 0 && s != GameKeys.SPACE ? laneCandidate(l, lane, t) : -1;
        consider(c, KIND_OWN, lane, 0f, t);
        consider(universalCandidate(l, t, c >= 0), KIND_UNIVERSAL, -1, 0f, t);
        if (bestNote < 0) {
            emitEmpty(x, ly, lane, l, PTR_KEY, t, GameEvent.FLAG_KEYBOARD);
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
        if (judging) stepCoverage(t, evLowerY, evUpperY);
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
            float cx = inputLayout.centre(n.laneIndex());
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

    private int neighbourLane(int own, float x) {
        LaneLayout lay = inputLayout;
        if (own >= 0) {
            float tol = NEIGHBOUR_TOLERANCE * lay.width(own);
            if (x - lay.left(own) <= tol) return prevActive(lay, own);
            if (lay.right(own) - x <= tol) return nextActive(lay, own);
            return -1;
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
            float x = frameLayout.centre(n.laneIndex());
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
            emitHold(GameEvent.HOLD_START, i, inputLayout.centre(n.laneIndex()), lineY(n.line(), evLowerY, evUpperY),
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

    /** Feedback x of a hold's events: its lane centre, or the window centre for a TIDE STREAM. */
    private float holdX(Note n) {
        int lane = n.laneIndex();
        return lane >= 0 ? frameLayout.centre(lane) : (frameLayout.windowLeft() + frameLayout.windowRight()) * 0.5f;
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

    /** True when (x, y) keeps hold n: inside its line's zone and within ±1 (active) lane of it. */
    private static boolean onHold(Note n, float x, float y, LaneLayout lay, float lowerY, float upperY) {
        if (Math.abs(y - lineY(n.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        int lane = n.laneIndex();
        float w = lay.width(lane);
        int a = prevActive(lay, lane);
        int b = nextActive(lay, lane);
        float lo = a >= 0 ? lay.left(a) : lay.left(lane) - w;
        float hi = b >= 0 ? lay.right(b) : lay.right(lane) + w;
        return x >= lo && x <= hi;
    }

    // ------------------------------------------------------------------ mines and taps

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
                int lane = m.laneIndex();
                if (!tide && (lane < 0 || lane >= laneCount)) continue;
                int mi = m.index();
                shadowFrom[mi] = at;
                int l = m.line().ordinal();
                // a TIDE MINE covers its whole line: the taps / STREAM heads of every lane, then its TIDEs
                for (int r = tide ? 0 : lane, last = tide ? laneCount : lane; r <= last; r++) {
                    Note[] arr = r < laneCount ? laneStream[l * laneCount + r] : uniStream[l];
                    for (Note u : arr) {
                        double T = u.judgementMs();
                        if (T + w < lo) continue;
                        if (T - w > hi) break;
                        if (laneMine && T - w >= hi) continue; // a LANE MINE's span is [T, end)
                        int ui = u.index();
                        if (pass == 0) {
                            pairs++;
                            if (!laneMine) cutLen[ui]++;
                            continue;
                        }
                        shadowNote[at++] = ui;
                        shadowLen[mi]++;
                        if (laneMine) noEcho[ui] = true;
                        else cutMine[cutFrom[ui] + cutLen[ui]++] = mi;
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
            int lane = m.laneIndex();
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
        int lane = n.laneIndex();
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
        if (Math.abs(y - lineY(m.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        if (m.isTideMine()) return true; // anywhere in its line's zone, as a TIDE
        int lane = m.laneIndex();
        return Math.abs(x - lay.centre(lane)) <= MINE_BOX * 0.5f * lay.width(lane);
    }

    /** Mines under a pointer event at t: MINEs only on a press (touch-down), LANE MINEs on every event. */
    private void checkMinesAt(double t, float x, float y, int pointer, boolean press) {
        for (int k = mineLo; press && k < mineCount; k++) {
            Note m = mines[k];
            double T = m.judgementMs();
            if (T - MINE_WINDOW_MS > t) break;
            int i = m.index();
            if (state[i] != STATE_PENDING || t > T + MINE_WINDOW_MS) continue;
            if (onMine(m, x, y, inputLayout, evLowerY, evUpperY) && !asleep(i, t)) {
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
            if ((m.isTideMine() || m.laneIndex() == lane) && m.line() == line && !asleep(i, t)) {
                touchMine(i, keyX(s, inputLayout), lineY(line, evLowerY, evUpperY), PTR_KEY, t, inputLayout,
                    evLowerY, evUpperY, GameEvent.FLAG_KEYBOARD);
            }
        }
        for (int k = laneMineLo; k < laneMineCount; k++) {
            Note m = laneMineArr[k];
            if (m.judgementMs() - LANE_MINE_LEAD_MS > t) break;
            if (t >= m.endMs() || !m.isTideMine() && m.laneIndex() != lane || m.line() != line) continue;
            observeKey(m.index(), s, t, inputLayout, evLowerY, evUpperY);
        }
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
        if (Math.abs(y - lineY(m.line(), lowerY, upperY)) > Field.zoneHalf()) return false;
        if (m.isTideMine()) return true; // a TIDE STREAM MINE: its line's whole zone
        int lane = m.laneIndex();
        return Math.abs(x - lay.centre(lane)) <= LANE_MINE_BOX * 0.5f * lay.width(lane);
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
            boolean tide = m.isTideMine(); // a TIDE STREAM MINE takes every key of its line
            for (int s = 0; s < GameKeys.SLOTS; s++) {
                if (kDown[s] && GameKeys.line(s) == m.line()
                    && (tide || s < GameKeys.SPACE && kLane[s] == m.laneIndex())) {
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
        float x = m.isTideMine() ? keyX(s, lay) : lay.centre(m.laneIndex());
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
            m.laneIndex(), m.line(), i, c, pointer, t, flags | GameEvent.FLAG_LANE_MINE);
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
        float x = frameLayout.centre(n.laneIndex());
        float y = lineY(n.line(), frameLowerY, frameUpperY);
        emit(GameEvent.LOST, Judgement.LOST, 0f, x, y, x, y, n.laneIndex(), n.line(), i, 1, PTR_ENGINE, at, 0);
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

    private void emitEmpty(float x, float y, int lane, int line, int pointer, double t, int flags) {
        emit(GameEvent.EMPTY, null, 0f, x, y, x, y, lane, line >= 0 ? Line.of(line) : null, -1, 0, pointer, t,
            flags | (pointer == PTR_KEY ? GameEvent.FLAG_KEYBOARD : 0));
    }

    private void emitHold(int type, int i, float x, float y, int pointer, double t, int flags) {
        Note n = notes[i];
        emit(type, Judgement.byOrdinal(grade[i]), dt[i], x, y, x, y, n.laneIndex(), n.line(), i, 1,
            pointer >= 0 ? pointer : PTR_KEY, t, flags);
    }

    private void emit(int type, Judgement j, float d, float x, float y, float touchX, float touchY, int lane,
                      Line line, int note, int count, int pointer, double t, int flags) {
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

    /** @return the lane layout at the last frame's visual time (shared; do not update it). */
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
     * @return true while hold i runs and its finger (or key, or autoplay) is on it; for a TIDE
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

    /** @return lane index under pointer p at the last frame when it is in a zone, else −1. */
    public int pointerLane(int p) {
        return pLane[p];
    }

    /** @return the zone line of pointer p at the last frame, or null (outside both zones / up). */
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
