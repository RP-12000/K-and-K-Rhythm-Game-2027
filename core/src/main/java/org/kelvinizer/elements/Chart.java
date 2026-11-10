package org.kelvinizer.elements;

import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 一张可游玩的谱面（v2 模型）：加载时一次性建好按轨道、按线的音符索引，游戏中不再扫描全部音符。
 *
 * <p>A playable chart (format v2 model, ARCHITECTURE §13), loaded by {@link ChartMapper}. Chart
 * data is immutable after construction and holds no runtime state, so one instance can be
 * replayed and read from any thread.
 *
 * <p>Built once at construction (O(n log n)), so the engine never scans all notes per frame or
 * per tap:
 * <ul>
 *   <li>{@link #notes()}: every note sorted by judgement beat, then line (lower first), then
 *       judged notes before mines, then lane (universal notes first, lanes left to right).</li>
 *   <li>{@link #laneNotes(Line, int)}: tap and hold notes of one lane on one line, ascending in
 *       time (notes stacked on one beat in file order, kept since Kelvin 2026-11-02) — ready for per-lane
 *       cursors.</li>
 *   <li>{@link #laneMines(Line, int)}: mines (LANE MINEs included) of one lane on one line,
 *       ascending.</li>
 *   <li>{@link #universals(Line)}: universal notes of one line, ascending, with their
 *       same-time groups ({@link Note#groupStart()} / {@link Note#groupSize()}).</li>
 *   <li>{@link #drags(Line)}: the DRAGs of one line, ascending (Kelvin 2026-11-05).</li>
 *   <li>{@link #freeNotes(Line)}: the taps and holds of one line placed by position, not by a lane
 *       ({@link Note#free()}: written with an x, or between two lanes; Kelvin 2026-11-07 04:02), ascending. Mines
 *       placed so are only in {@link #mines()}.</li>
 *   <li>Counts: {@link #totalNotes()} (tap + hold + universal + drag, the score denominator; a TIDE
 *       STREAM is one note like a hold), {@link #totalMines()} (a LANE MINE is one mine),
 *       {@link #count(NoteType)}, {@link #tideStreamCount()}, {@link #laneMineCount()}.</li>
 *   <li>Timing: beat 0 is song time {@link #offsetMs()} ms (format v2 {@code offsetMs}, default 0).
 *       The tempo is constant ({@code ms = offsetMs + beat * msPerBeat}) unless the chart has BPM
 *       changes ({@code bpmList}): then it is constant within each segment and the first segment
 *       also covers every beat before it. All beat / ms conversion goes through {@link #beatToMs},
 *       {@link #msToBeat} and {@link #msBetween} ({@link Note#judgementMs()}, {@link Note#endMs()},
 *       {@link #lastNoteEndBeat()} / {@link #lastNoteEndMs()}).</li>
 *   <li>{@link #veils()}: the veils (CHART_FORMAT_v4 §6.10), set once by the loader before the chart is
 *       shared; likewise the laneless time ({@link #lanelessRanges()}, {@link #lanesShown}).</li>
 * </ul>
 *
 * <p>The arrays returned by the accessors are the chart's own (no copies, so reading them never
 * allocates); callers must not modify them.
 */
public final class Chart {
    private static final Note[] NO_NOTES = new Note[0];
    private static final Veil[] NO_VEILS = new Veil[0];
    private static final float[] NO_RANGES = new float[0];
    /**
     * Beats over which the lanes fade out where laneless time begins and back in where it ends, centred on the edge
     * (±1/4 beat).
     */
    public static final float LANELESS_FADE_BEATS = 0.5f;
    /**
     * How far the lanes' columns reach past the sky line and past the ground line by default, reference units: the
     * last 700 fade out, so on the default lines (1080 / 120) they are solid to the screen's edges (3D page v3: 2600
     * long, 700 fading at each end).
     */
    public static final float DEFAULT_EXTEND = 820f;
    /** A new chart's {@link #laneWidth()}: a default lane as wide as one of the default 6 (Kelvin 2026-11-08 02:39). */
    public static final float DEFAULT_LANE_WIDTH = 320f;

    private final String title;
    private final String artist;
    private final String illustrator;
    private final String charter;
    private final String level;
    private final String chartConstant;
    private final float bpm;
    private final float offsetMs;
    private final double msPerBeat;
    /** Tempo map: segment start beats (ascending), their BPM, ms per beat and song time in ms. */
    private final float[] segBeat, segBpm;
    private final double[] segMsPerBeat, segMs;
    private final Lane[] lanes;
    private final int[] laneIds;
    private final int laneLimit;
    /** A default lane's own width ({@link #laneWidth()}), always positive. */
    private float laneWidth;
    private final JudgementLine lineEvents;
    private final Note[] notes;
    private final Note[][] laneNotes;
    private final Note[][] laneMines;
    private final Note[][] universals;
    /** Per line: its DRAGs (not decorations), ascending in time. */
    private final Note[][] drags = new Note[Line.COUNT][];
    /** Per line: its taps and holds placed by position ({@link Note#free()}, not decorations), ascending in time. */
    private final Note[][] freeNotes = new Note[Line.COUNT][];
    private final Note[] mines;
    private final int[] typeCounts = new int[NoteType.count()];
    private int tideStreams;
    private int laneMineTotal;
    private final float lastNoteEndBeat;
    private final float firstVisibleBeat;

    int sourceFormat = ChartMapper.FORMAT_VERSION;
    LoadReport report = new LoadReport();
    private FileHandle chartFile;
    private FileHandle musicFile;
    private FileHandle jacketFile;
    private Veil[] veils = NO_VEILS;
    private Veil[] curtains = NO_VEILS;
    private float[] laneless = NO_RANGES;
    private float[] fullScreen = NO_RANGES;
    private List<Events> viewYaw = Collections.emptyList();
    private List<Events> viewPitch = Collections.emptyList();
    private List<Events> viewFov = Collections.emptyList();
    private List<Events> viewDist = Collections.emptyList();
    private float upperExtend = DEFAULT_EXTEND;
    private float lowerExtend = DEFAULT_EXTEND;
    private List<Events> upperExtendEvents = Collections.emptyList();
    private List<Events> lowerExtendEvents = Collections.emptyList();
    private List<Sea> seas = Collections.emptyList();
    /** The camera position P of a chart format v7 chart ({@link Channels#CAMERA7}), or null. */
    private Channels camera7;
    /** The camera's field of view of a chart format v7 chart ({@link #setFov7}), or null. */
    private Fn fov7;
    /** The veils and curtains of a chart format v7 chart ({@link #setVolumes7}). */
    private List<Volume7> volumes7 = Collections.emptyList();

    /** Same as the full constructor with {@code offsetMs} 0 (beat 0 = song time 0 ms). */
    public Chart(String title, String artist, String illustrator, String charter, String level,
                 String chartConstant, float bpm, List<Lane> lanes, JudgementLine lineEvents, List<Note> notes) {
        this(title, artist, illustrator, charter, level, chartConstant, bpm, 0f, lanes, lineEvents, notes);
    }

    /**
     * Builds a chart and all its indices. Notes must not belong to another chart.
     *
     * @param title         title (null → "")
     * @param artist        artist (null → "")
     * @param illustrator   illustrator (null → "")
     * @param charter       charter (null → "")
     * @param level         difficulty label as authored, e.g. "Deep" (null → "")
     * @param chartConstant chart constant as authored, e.g. "15.9" (may be null)
     * @param bpm           constant tempo (positive and finite)
     * @param offsetMs      song time in ms of beat 0 (finite; may be negative)
     * @param lanes         the lane set, any order, distinct ids (null = none)
     * @param lineEvents    line geometry, lane scale and camera frame (null = {@link JudgementLine#defaults()})
     * @param notes         the notes, any order (null = none)
     * @throws IllegalArgumentException for a bad bpm or offset, duplicate lane ids, a lane note whose
     *                                  lane is not in {@code lanes}, or a note reused from another chart
     */
    public Chart(String title, String artist, String illustrator, String charter, String level,
                 String chartConstant, float bpm, float offsetMs, List<Lane> lanes, JudgementLine lineEvents,
                 List<Note> notes) {
        this(title, artist, illustrator, charter, level, chartConstant, bpm, offsetMs, lanes, lineEvents, notes,
            null, null);
    }

    /**
     * As the constructor above, with BPM changes ({@code bpmList}). Each change sets the tempo from its
     * beat on; the first one also covers every beat before it, and its BPM replaces {@code bpm}.
     *
     * @param bpmBeats beats of the changes, finite and strictly ascending (null or empty = constant {@code bpm})
     * @param bpms     tempo from each change on, positive and finite (same length as {@code bpmBeats})
     * @throws IllegalArgumentException also for unordered change beats or a bad change BPM
     */
    public Chart(String title, String artist, String illustrator, String charter, String level,
                 String chartConstant, float bpm, float offsetMs, List<Lane> lanes, JudgementLine lineEvents,
                 List<Note> notes, float[] bpmBeats, float[] bpms) {
        this(title, artist, illustrator, charter, level, chartConstant, bpm, offsetMs, lanes, lineEvents, notes,
            bpmBeats, bpms, -1);
    }

    /**
     * As the constructor above, with the lane mode (Kelvin 2026-10-13, see {@link #laneLimit()}).
     *
     * @param laneLimit the file's {@code laneCount} (≥ 1: finite, each lane its own width, {@link #laneWidth()}),
     *                  0 for unlimited lanes (each lane keeps its width), or −1 for finite with this chart's own
     *                  lanes (what the shorter constructors use)
     */
    public Chart(String title, String artist, String illustrator, String charter, String level,
                 String chartConstant, float bpm, float offsetMs, List<Lane> lanes, JudgementLine lineEvents,
                 List<Note> notes, float[] bpmBeats, float[] bpms, int laneLimit) {
        if (!(bpm > 0f) || bpm == Float.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("bpm must be > 0: " + bpm);
        }
        if (Float.isNaN(offsetMs) || Float.isInfinite(offsetMs)) {
            throw new IllegalArgumentException("offsetMs must be finite: " + offsetMs);
        }
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.illustrator = illustrator != null ? illustrator : "";
        this.charter = charter != null ? charter : "";
        this.level = level != null ? level : "";
        this.chartConstant = chartConstant;
        int segs = bpmBeats != null && bpms != null ? Math.min(bpmBeats.length, bpms.length) : 0;
        if (segs == 0) {
            segBeat = new float[] {0f};
            segBpm = new float[] {bpm};
        } else {
            segBeat = Arrays.copyOf(bpmBeats, segs);
            segBpm = Arrays.copyOf(bpms, segs);
        }
        segMsPerBeat = new double[segBeat.length];
        segMs = new double[segBeat.length];
        for (int i = 0; i < segBeat.length; i++) {
            if (!(segBpm[i] > 0f) || segBpm[i] == Float.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("bpm must be > 0: " + segBpm[i]);
            }
            if (Float.isNaN(segBeat[i]) || Float.isInfinite(segBeat[i]) || i > 0 && !(segBeat[i] > segBeat[i - 1])) {
                throw new IllegalArgumentException("BPM change beats must be finite and ascending: "
                    + Arrays.toString(segBeat));
            }
            segMsPerBeat[i] = 60000.0 / segBpm[i];
            if (i > 0) segMs[i] = segMs[i - 1] + (segBeat[i] - segBeat[i - 1]) * segMsPerBeat[i - 1];
        }
        // shift the segment times so that beat 0 lands on offsetMs
        int zero = segmentOfBeat(0.0);
        double shift = offsetMs - (segMs[zero] - segBeat[zero] * segMsPerBeat[zero]);
        for (int i = 0; i < segMs.length; i++) segMs[i] += shift;
        this.bpm = segBpm[0];
        this.offsetMs = offsetMs;
        this.msPerBeat = segMsPerBeat[0];
        this.lineEvents = lineEvents != null ? lineEvents : JudgementLine.defaults();

        // Lanes, ascending by id.
        this.lanes = lanes != null ? lanes.toArray(new Lane[0]) : new Lane[0];
        Arrays.sort(this.lanes, new Comparator<Lane>() {
            @Override
            public int compare(Lane a, Lane b) {
                return Integer.compare(a.id(), b.id());
            }
        });
        int laneCount = this.lanes.length;
        this.laneLimit = laneLimit >= 0 ? laneLimit : Math.max(1, laneCount);
        this.laneWidth = keptLaneWidth();
        laneIds = new int[laneCount];
        for (int i = 0; i < laneCount; i++) {
            laneIds[i] = this.lanes[i].id();
            if (i > 0 && laneIds[i] == laneIds[i - 1]) {
                throw new IllegalArgumentException("duplicate lane id " + laneIds[i]);
            }
        }

        // Notes: resolve lanes, then sort.
        this.notes = notes != null ? notes.toArray(new Note[0]) : NO_NOTES;
        for (Note n : this.notes) {
            if (n.index != -1) throw new IllegalArgumentException("note already belongs to a chart: " + n);
            if (n.type().inLane() && !n.laneless()) {
                // a decimal lane, or lane 0 when there is none (the centre line), lies between two lanes
                boolean whole = n.lanePos() == n.laneId();
                n.laneIndex = whole ? laneIndexOf(n.laneId()) : -1;
                n.between = n.laneIndex < 0 && (!whole || n.laneId() == 0);
                if (n.laneIndex < 0 && !n.between) throw new IllegalArgumentException("note on undeclared lane: " + n);
            } else {
                n.laneIndex = -1;
                n.between = false;
            }
        }
        Arrays.sort(this.notes, NOTE_ORDER);

        float lastEnd = 0f;
        float firstVisible = 0f;
        for (int i = 0; i < this.notes.length; i++) {
            Note n = this.notes[i];
            n.index = i;
            n.judgementMs = beatToMs(n.judgement());
            n.endMs = beatToMs((double) n.judgement() + n.duration());
            if (!n.fake()) { // decorations are drawn only (Kelvin 2026-11-02)
                typeCounts[n.type().ordinal()]++;
                if (n.isTideStream()) tideStreams++;
                if (n.isLaneMine()) laneMineTotal++;
            }
            lastEnd = i == 0 ? n.endBeat() : Math.max(lastEnd, n.endBeat());
            firstVisible = i == 0 ? n.visibleFrom() : Math.min(firstVisible, n.visibleFrom());
        }
        lastNoteEndBeat = lastEnd;
        firstVisibleBeat = firstVisible;

        laneNotes = new Note[Line.COUNT * laneCount][];
        laneMines = new Note[Line.COUNT * laneCount][];
        universals = new Note[Line.COUNT][];
        mines = buildStreams(laneCount);
        buildUniversalGroups();
        buildSyncGroups();
    }

    /** Global note order (on one beat decorations after the rest); ties keep the input order (stable sort). */
    private static final Comparator<Note> NOTE_ORDER = new Comparator<Note>() {
        @Override
        public int compare(Note a, Note b) {
            int c = Float.compare(a.judgement(), b.judgement());
            if (c != 0) return c;
            c = Boolean.compare(a.fake(), b.fake());
            if (c != 0) return c;
            c = a.line().ordinal() - b.line().ordinal();
            if (c != 0) return c;
            c = (a.type().judged() ? 0 : 1) - (b.type().judged() ? 0 : 1);
            if (c != 0) return c;
            c = Integer.compare(a.laneIndex, b.laneIndex);
            if (c != 0) return c;
            return a.type().ordinal() - b.type().ordinal();
        }
    };

    /** Fills the per-lane, per-line arrays and slots; returns the global mine array. */
    private Note[] buildStreams(int laneCount) {
        int[] laneCounts = new int[Line.COUNT * laneCount];
        int[] mineCounts = new int[Line.COUNT * laneCount];
        int[] universalCounts = new int[Line.COUNT];
        int[] dragCounts = new int[Line.COUNT];
        int[] freeCounts = new int[Line.COUNT];
        for (Note n : notes) {
            if (n.fake()) continue; // in no stream: nothing judges a decoration
            int line = n.line().ordinal();
            switch (n.type()) {
                case MINE:
                    if (lane(n) >= 0) mineCounts[line * laneCount + lane(n)]++;
                    break;
                case UNIVERSAL:
                    universalCounts[line]++;
                    break;
                case TIDE_MINE:
                    break;
                case DRAG:
                    dragCounts[line]++;
                    break;
                default:
                    if (lane(n) >= 0) laneCounts[line * laneCount + lane(n)]++;
                    else freeCounts[line]++;
                    break;
            }
        }
        for (int k = 0; k < laneNotes.length; k++) {
            laneNotes[k] = laneCounts[k] == 0 ? NO_NOTES : new Note[laneCounts[k]];
            laneMines[k] = mineCounts[k] == 0 ? NO_NOTES : new Note[mineCounts[k]];
        }
        for (int l = 0; l < Line.COUNT; l++) {
            universals[l] = universalCounts[l] == 0 ? NO_NOTES : new Note[universalCounts[l]];
            drags[l] = dragCounts[l] == 0 ? NO_NOTES : new Note[dragCounts[l]];
            freeNotes[l] = freeCounts[l] == 0 ? NO_NOTES : new Note[freeCounts[l]];
        }
        Arrays.fill(dragCounts, 0);
        Arrays.fill(freeCounts, 0);
        Note[] allMines = totalMines() == 0 ? NO_NOTES : new Note[totalMines()];
        Arrays.fill(laneCounts, 0);
        Arrays.fill(mineCounts, 0);
        Arrays.fill(universalCounts, 0);
        int m = 0;
        for (Note n : notes) {
            if (n.fake()) continue;
            int line = n.line().ordinal();
            switch (n.type()) {
                case MINE: {
                    if (lane(n) >= 0) {
                        int k = line * laneCount + lane(n);
                        n.slot = mineCounts[k]++;
                        laneMines[k][n.slot] = n;
                    }
                    allMines[m++] = n;
                    break;
                }
                case UNIVERSAL:
                    n.slot = universalCounts[line]++;
                    universals[line][n.slot] = n;
                    break;
                case TIDE_MINE:
                    allMines[m++] = n;
                    break;
                case DRAG:
                    n.slot = dragCounts[line]++;
                    drags[line][n.slot] = n;
                    break;
                default: {
                    if (lane(n) < 0) {
                        n.slot = freeCounts[line]++;
                        freeNotes[line][n.slot] = n;
                        break;
                    }
                    int k = line * laneCount + lane(n);
                    n.slot = laneCounts[k]++;
                    laneNotes[k][n.slot] = n;
                    break;
                }
            }
            n.groupStart = n.slot;
            n.groupSize = 1;
        }
        return allMines;
    }

    /** @return the lane note n is judged in: its lane, or −1 when placed by position (a space key's path too) */
    private static int lane(Note n) {
        return n.spacePath() ? -1 : n.laneIndex;
    }

    /** Universal notes on the same line with exactly the same judgement beat form one group. */
    private void buildUniversalGroups() {
        for (Note[] arr : universals) {
            int i = 0;
            while (i < arr.length) {
                int j = i + 1;
                while (j < arr.length && arr[j].judgement() == arr[i].judgement()) j++;
                for (int k = i; k < j; k++) {
                    arr[k].groupStart = i;
                    arr[k].groupSize = j - i;
                }
                i = j;
            }
        }
    }

    /**
     * Sync groups (same line, same beat, judged notes) and the shared-beat flag (any other note of
     * any type, either line, at exactly the same beat: {@link Note#sharesBeat()}). Decorations
     * ({@link Note#fake()}, after the rest on their beat) are in neither.
     */
    private void buildSyncGroups() {
        int i = 0;
        while (i < notes.length) {
            if (notes[i].fake()) {
                notes[i].sharesBeat = false;
                notes[i].syncStart = i;
                notes[i].syncSize = 1;
                i++;
                continue;
            }
            // Cluster of notes with exactly the same judgement beat (both lines).
            int end = i + 1;
            while (end < notes.length && !notes[end].fake() && notes[end].judgement() == notes[i].judgement()) end++;
            boolean shared = end - i > 1;
            for (int q = i; q < end; q++) notes[q].sharesBeat = shared;
            int k = i;
            while (k < end) {
                Note n = notes[k];
                if (!n.type().judged()) {
                    n.syncStart = k;
                    n.syncSize = 1;
                    k++;
                    continue;
                }
                int r = k + 1;
                while (r < end && notes[r].type().judged() && notes[r].line() == n.line()) r++;
                for (int q = k; q < r; q++) {
                    notes[q].syncStart = k;
                    notes[q].syncSize = r - k;
                }
                k = r;
            }
            i = end;
        }
    }

    // ------------------------------------------------------------------ metadata

    /** @return title ("" if absent). */
    public String title() { return title; }
    /** @return artist ("" if absent). */
    public String artist() { return artist; }
    /** @return illustrator ("" if absent). */
    public String illustrator() { return illustrator; }
    /** @return charter ("" if absent). */
    public String charter() { return charter; }
    /** @return difficulty label as authored, e.g. "Deep" ("" if absent). */
    public String level() { return level; }
    /** @return chart constant as authored, e.g. "15.9" (null if absent). */
    public String chartConstant() { return chartConstant; }

    /** @return the chart constant as a number, or {@code fallback} when absent or unparsable. */
    public float chartConstantValue(float fallback) {
        if (chartConstant == null) return fallback;
        try {
            float v = Float.parseFloat(chartConstant.trim());
            return Float.isNaN(v) || Float.isInfinite(v) ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** @return the format version of the source file (1 = converted from v1, 2 = native). */
    public int sourceFormat() { return sourceFormat; }

    /** @return what the loader fixed or dropped (never null). */
    public LoadReport loadReport() { return report; }

    // ------------------------------------------------------------------ timing

    /** @return the tempo in beats per minute (of the first segment when the BPM changes). */
    public float bpm() { return bpm; }
    /** @return song time in ms of beat 0 (format v2 {@code offsetMs}; 0 when absent). */
    public float offsetMs() { return offsetMs; }
    /**
     * @return milliseconds per beat of the first segment; use {@link #msBetween} for spans that may cross a BPM change.
     */
    public double msPerBeat() { return msPerBeat; }
    /** @return song time in ms of {@code beat}. */
    public double beatToMs(double beat) {
        int i = segmentOfBeat(beat);
        return segMs[i] + (beat - segBeat[i]) * segMsPerBeat[i];
    }
    /** @return beat at song time {@code ms} (inverse of {@link #beatToMs}). */
    public double msToBeat(double ms) {
        int i = segmentOfMs(ms);
        return segBeat[i] + (ms - segMs[i]) / segMsPerBeat[i];
    }
    /** @return milliseconds from beat {@code from} to beat {@code to}. */
    public double msBetween(double from, double to) { return beatToMs(to) - beatToMs(from); }
    /** @return start beats of the tempo segments (one entry, beat 0, without BPM changes); do not modify. */
    public float[] bpmListBeats() { return segBeat; }
    /** @return BPM of each tempo segment, parallel to {@link #bpmListBeats()}; do not modify. */
    public float[] bpmListValues() { return segBpm; }

    private int segmentOfBeat(double beat) {
        int lo = 0, hi = segBeat.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (segBeat[mid] <= beat) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private int segmentOfMs(double ms) {
        int lo = 0, hi = segMs.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (segMs[mid] <= ms) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** @return the latest note end in beats (hold ends included; 0 for an empty chart). */
    public float lastNoteEndBeat() { return lastNoteEndBeat; }
    /** @return {@link #lastNoteEndBeat()} in ms. */
    public double lastNoteEndMs() { return beatToMs(lastNoteEndBeat); }
    /** @return the earliest {@link Note#visibleFrom()} (0 for an empty chart; may be negative). */
    public float firstVisibleBeat() { return firstVisibleBeat; }

    // ------------------------------------------------------------------ geometry

    /** @return line geometry, global lane scale and camera frame. */
    public JudgementLine lineEvents() { return lineEvents; }
    /** @return the lanes in ascending id order (shared array; do not modify). */
    public Lane[] lanes() { return lanes; }
    /** @return number of lanes (including the ones the loader added, {@link Lane#implicit()}). */
    public int laneCount() { return lanes.length; }

    /**
     * Lane mode (Kelvin 2026-10-13, chart key {@code laneCount}):
     * <ul>
     *   <li><b>0, unlimited</b> (a v2 file without {@code laneCount}, and every v1 file): each
     *       lane keeps its width in reference units times the global lane scale, lanes 1, 2, …
     *       laid out rightwards and −1, −2, … leftwards from the axis, x 960
     *       ({@link LaneLayout#TILE_CENTRE}) plus the camera frame; the screen decides which lanes
     *       are drawn and judged. The loader adds the lanes the screen can reach and fills id
     *       gaps.</li>
     *   <li><b>N ≥ 1, finite</b>: the chart's own lanes, each as wide as its own width
     *       ({@link #laneWidth()}, Kelvin 2026-11-08 02:48), side by side about the axis. A chart built in code with
     *       a shorter constructor is finite with its own lanes.</li>
     * </ul>
     *
     * @return the lane count of a finite chart, or 0 for unlimited lanes
     */
    public int laneLimit() { return laneLimit; }

    /** @return true for unlimited lanes ({@link #laneLimit()} is 0). */
    public boolean unlimitedLanes() { return laneLimit == 0; }

    /**
     * Sets the file's {@code laneWidth} (Kelvin 2026-11-08 02:39 「默认轨道宽度不变，都是默认6轨的轨道宽度」, 02:48
     * 「就是把每个轨道当作单独的object处理」): the reference width of a default lane ({@link Lane#DEFAULT_WIDTH}) at
     * scale 1. Not positive and finite: the width a chart without it keeps ({@link #laneWidth()}).
     */
    public void setLaneWidth(float w) {
        laneWidth = w > 0f && w < Float.POSITIVE_INFINITY ? w : keptLaneWidth();
    }

    /**
     * @return the reference width of a default lane at scale 1 (Kelvin 2026-11-08): every finite lane is
     *         {@code width / 240} of it, whatever the other lanes do. A new chart writes {@link #DEFAULT_LANE_WIDTH};
     *         one without it keeps the look it had: its lanes at their own widths fill the 1920 frame, as when they
     *         shared it (8 default lanes 240, 6 lanes 320, 9 lanes 213.33)
     */
    public float laneWidth() { return laneWidth; }

    private float keptLaneWidth() {
        if (laneLimit <= 0) return DEFAULT_LANE_WIDTH; // unlimited lanes keep their widths
        float sum = 0f;
        for (Lane l : lanes) sum += l.baseWidth();
        float frame = LaneLayout.VIEW_RIGHT - LaneLayout.VIEW_LEFT;
        float w = sum > 0f ? frame * Lane.DEFAULT_WIDTH / sum : frame / laneLimit;
        return w > 0f && w < Float.POSITIVE_INFINITY ? w : DEFAULT_LANE_WIDTH;
    }
    /** @return lane at index {@code i}. */
    public Lane lane(int i) { return lanes[i]; }

    /** @return index of lane {@code laneId}, or −1 if the chart has no such lane. O(log lanes). */
    public int laneIndexOf(int laneId) {
        int i = Arrays.binarySearch(laneIds, laneId);
        return i >= 0 ? i : -1;
    }

    // ------------------------------------------------------------------ notes

    /** @return every note in chart order, decorations ({@link Note#fake()}) included (shared array; do not modify). */
    public Note[] notes() { return notes; }
    /** @return number of notes of every type, mines and decorations included (the length of {@link #notes()}). */
    public int noteCount() { return notes.length; }
    /** @return note {@code i} of {@link #notes()}. */
    public Note note(int i) { return notes[i]; }

    /** @return tap and hold notes of lane index {@code laneIndex} on {@code line}, ascending (shared). */
    public Note[] laneNotes(Line line, int laneIndex) {
        return laneNotes[line.ordinal() * lanes.length + laneIndex];
    }

    /** @return mines (LANE MINEs included) of lane index {@code laneIndex} on {@code line}, ascending (shared). */
    public Note[] laneMines(Line line, int laneIndex) {
        return laneMines[line.ordinal() * lanes.length + laneIndex];
    }

    /** @return universal notes of {@code line}, ascending, groups contiguous (shared). */
    public Note[] universals(Line line) {
        return universals[line.ordinal()];
    }

    /**
     * @return the taps and holds of {@code line} placed by position ({@link Note#free()}; decorations excluded),
     *         ascending in time, one beat's in chart order (shared)
     */
    public Note[] freeNotes(Line line) {
        return freeNotes[line.ordinal()];
    }

    /** @return the DRAGs of {@code line} (decorations excluded), ascending in time; one beat's in chart order. */
    public Note[] drags(Line line) {
        return drags[line.ordinal()];
    }

    /** @return every mine (LANE MINEs included) in chart order (shared). */
    public Note[] mines() { return mines; }

    /** @return notes counted for score and combo: tap + hold + universal + drag. */
    public int totalNotes() {
        return typeCounts[NoteType.TAP.ordinal()] + typeCounts[NoteType.HOLD.ordinal()]
            + typeCounts[NoteType.UNIVERSAL.ordinal()] + typeCounts[NoteType.DRAG.ordinal()];
    }

    /** @return number of mines, TIDE MINEs included, each LANE MINE / TIDE STREAM MINE counted once (MINES row). */
    public int totalMines() {
        return typeCounts[NoteType.MINE.ordinal()] + typeCounts[NoteType.TIDE_MINE.ordinal()];
    }

    /** @return number of notes of {@code type} (UNIVERSAL counts TIDE drops and TIDE STREAMs). */
    public int count(NoteType type) { return typeCounts[type.ordinal()]; }

    /** @return number of TIDE STREAMs (universal notes with a duration; part of {@code count(UNIVERSAL)}). */
    public int tideStreamCount() { return tideStreams; }

    /** @return number of LANE MINEs (mines with a duration; part of {@code count(MINE)}). */
    public int laneMineCount() { return laneMineTotal; }

    /**
     * Counts the notes a LANE MINE would block (ARCHITECTURE §20 loader rule): tap / hold heads
     * and plain mines of the same line and lane whose judgement time lies in the mine's block
     * window {@code [judgementMs − leadMs, endMs)}. A note inside two such windows counts twice.
     *
     * @param leadMs how long before its judgement a LANE MINE starts blocking ({@code Params.LANE_MINE_LEAD_MS}, 0)
     */
    public int laneMineConflicts(double leadMs) {
        if (laneMineTotal == 0) return 0;
        int count = 0;
        for (Note m : mines) {
            if (!m.isLaneMine() || m.laneIndex < 0) continue; // one placed by position blocks no lane
            int k = m.line().ordinal() * lanes.length + m.laneIndex;
            double from = m.judgementMs - leadMs;
            double to = m.endMs;
            for (Note o : laneNotes[k]) {
                if (o.judgementMs >= from && o.judgementMs < to) count++;
            }
            for (Note o : laneMines[k]) {
                if (o != m && !o.isLaneMine() && o.judgementMs >= from && o.judgementMs < to) count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ veils

    /** @return the veils (CHART_FORMAT_v4 §6.10) in file order; the chart's own array, callers must not modify it. */
    public Veil[] veils() { return veils; }

    /**
     * Sets the veils: the loader does, before the chart is shared, and so may an editor that builds a chart.
     * Null entries and veils without a usable shape ({@link Veil#isValid}) are left out.
     */
    public void setVeils(List<Veil> list) {
        if (list == null || list.isEmpty()) {
            veils = NO_VEILS;
            return;
        }
        ArrayList<Veil> keep = new ArrayList<>(list.size());
        for (Veil v : list) {
            if (v != null && v.isValid()) keep.add(v);
        }
        veils = keep.isEmpty() ? NO_VEILS : keep.toArray(new Veil[0]);
    }

    /** @return the curtains (水帘, CHART_FORMAT §6.1) in file order; the chart's own array, do not modify it. */
    public Veil[] curtains() { return curtains; }

    /** Sets the curtains, as {@link #setVeils} sets the veils; curtains only blur and never block a touch. */
    public void setCurtains(List<Veil> list) {
        Veil[] keep = veils;
        setVeils(list);
        curtains = veils;
        veils = keep;
    }

    // ------------------------------------------------------------------ laneless time

    /**
     * Sets the laneless time (无轨时间, Kelvin 2026-11-07 04:02: 「加一个无轨时间和有轨时间」): beat ranges in which the
     * lanes are not shown ({@link #lanesShown}); the loader calls this once before the chart is shared. Each range
     * is {@code {from, to}} (−∞ / +∞ for the start / end of the chart); empty, inverted or NaN ranges are ignored and
     * overlapping ones merged. The lanes still exist there (inserts, deletes and widths go on), and keys keep their
     * places: the chart editor writes keys with an x in laneless time.
     */
    public void setLaneless(List<float[]> ranges) {
        laneless = merged(ranges);
    }

    /**
     * Sets the full-screen judging time (全屏判定, Kelvin 2026-11-07 06:04: 「有段落是"垂直判定"，有段落是全屏判定」): beat
     * ranges, read and merged as {@link #setLaneless}, in which a key takes a press anywhere on the screen; the
     * rest of the chart judges by columns (垂直判定). A key belongs to the range its judgement beat is in.
     */
    public void setFullScreen(List<float[]> ranges) {
        fullScreen = merged(ranges);
    }

    /**
     * Sets the seas (海面, Kelvin 2026-11-07 16:04–16:16) and puts every note with a sea index on its sea; an index
     * with no sea is dropped (the note lands on its line), and so is a lane key's: a sea belongs to no lane, so only
     * keys placed by their own x ({@link Note#laneless()}) and TIDEs stand on one (Kelvin 17:07).
     */
    public void setSeas(List<Sea> list) {
        seas = list == null || list.isEmpty() ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(list));
        for (Note n : notes) {
            boolean free = !n.type().inLane() || n.laneless();
            n.sea = free && n.seaIndex >= 0 && n.seaIndex < seas.size() ? seas.get(n.seaIndex) : null;
            if (n.sea == null) n.seaIndex = -1;
        }
    }

    /** @return the seas, in file order (notes refer to them by index) */
    public List<Sea> seas() { return seas; }

    /**
     * Sets the view (视角, the 3D camera; Kelvin 2026-11-07 06:35–06:45): four event channels in beats, motion events
     * like any other ({@link Events#calculateValue}; 16:04 「3Dcamera这些也都是运动类事件」). Yaw and pitch in
     * degrees (0 = straight on; a negative yaw swings the camera to the right, a positive pitch lifts it), the
     * vertical field of view in degrees (11 by default) and the distance to the columns' front in reference units
     * (by default the one at which the front is the 1920 × 1200 frame). Null or empty lists: that channel stays at
     * its default.
     */
    public void setView(List<Events> yaw, List<Events> pitch, List<Events> fov, List<Events> dist) {
        viewYaw = Events.sortedCopy(yaw);
        viewPitch = Events.sortedCopy(pitch);
        viewFov = Events.sortedCopy(fov);
        viewDist = Events.sortedCopy(dist);
    }

    /**
     * Sets the camera of a chart format v7 chart (Kelvin 2026-11-09 18:44): its position P ({@link Channels#CAMERA7},
     * chart axes from the origin, the columns' middle at the frame's centre), looking at the origin with +y up; it then
     * takes the place of the view events.
     */
    public void setCamera7(Channels p) {
        camera7 = p != null && p.keys() == Channels.CAMERA7 ? p : null;
    }

    /** @return the camera position P of a chart format v7 chart ({@link #setCamera7}), or null */
    public Channels camera7() { return camera7; }

    /** Sets the camera's vertical field of view of a chart format v7 chart, degrees (Kelvin 2026-11-09 22:28). */
    public void setFov7(Fn fov) { fov7 = fov; }

    /** @return the camera's field of view of a chart format v7 chart ({@link #setFov7}), or null */
    public Fn fov7() { return fov7; }

    /** Sets the veils and curtains of a chart format v7 chart (copied; nulls are left out). */
    public void setVolumes7(List<Volume7> list) {
        List<Volume7> keep = new ArrayList<>();
        if (list != null) for (Volume7 v : list) if (v != null) keep.add(v);
        volumes7 = keep.isEmpty() ? Collections.<Volume7>emptyList() : Collections.unmodifiableList(keep);
    }

    /** @return the veils and curtains of a chart format v7 chart (never null; empty for other charts) */
    public List<Volume7> volumes7() { return volumes7; }

    /** @return true when the chart moves its view ({@link #setView}) */
    public boolean hasView() {
        return !viewYaw.isEmpty() || !viewPitch.isEmpty() || !viewFov.isEmpty() || !viewDist.isEmpty();
    }

    /** @return the view's yaw events (degrees) */
    public List<Events> viewYaw() { return viewYaw; }
    /** @return the view's pitch events (degrees) */
    public List<Events> viewPitch() { return viewPitch; }
    /** @return the view's field-of-view events (degrees) */
    public List<Events> viewFov() { return viewFov; }
    /** @return the view's distance events (reference units) */
    public List<Events> viewDist() { return viewDist; }

    /**
     * Sets how far the lanes' columns reach past the sky line ({@code upper}) and past the ground line ({@code lower}),
     * reference units, each a base value with events like any motion channel (Kelvin 2026-11-07 15:58:
     * 「可以选择轨道高度天线与地线之间的距离，天线外侧延申高度和地键外侧延伸高度」, 15:59: all motion events). The
     * distance between the lines is the lines' own heights ({@link JudgementLine}). Null lists: no events.
     */
    public void setColumnExtends(float upper, List<Events> upperEvents, float lower, List<Events> lowerEvents) {
        upperExtend = upper;
        lowerExtend = lower;
        upperExtendEvents = Events.sortedCopy(upperEvents);
        lowerExtendEvents = Events.sortedCopy(lowerEvents);
    }

    /** @return the base reach of the columns past the sky line (reference units) */
    public float upperExtendBase() { return upperExtend; }
    /** @return the base reach of the columns past the ground line (reference units) */
    public float lowerExtendBase() { return lowerExtend; }
    /** @return the events of the reach past the sky line */
    public List<Events> upperExtendEvents() { return upperExtendEvents; }
    /** @return the events of the reach past the ground line */
    public List<Events> lowerExtendEvents() { return lowerExtendEvents; }

    /** @return how far the columns reach past the sky line at {@code beat}, reference units, at least 0 */
    public float upperExtendAt(float beat) {
        return Math.max(0f, Events.calculateValue(upperExtendEvents, beat, upperExtend));
    }

    /** @return how far the columns reach past the ground line at {@code beat}, reference units, at least 0 */
    public float lowerExtendAt(float beat) {
        return Math.max(0f, Events.calculateValue(lowerExtendEvents, beat, lowerExtend));
    }

    /** @return the full-screen judging time as merged ascending pairs (as {@link #lanelessRanges}) */
    public float[] fullScreenRanges() { return fullScreen; }

    /** @return true when a key judged at {@code beat} takes a press anywhere ({@link #setFullScreen}) */
    public boolean fullScreenAt(float beat) {
        for (int i = 0; i < fullScreen.length; i += 2) {
            if (beat < fullScreen[i]) return false;
            if (beat < fullScreen[i + 1]) return true;
        }
        return false;
    }

    /** @return {@code ranges} without empty, inverted or NaN ones, sorted and merged into pairs from, to, … */
    private static float[] merged(List<float[]> ranges) {
        List<float[]> keep = new ArrayList<>();
        if (ranges != null) {
            for (float[] r : ranges) {
                if (r != null && r.length == 2 && r[0] < r[1]) keep.add(new float[] {r[0], r[1]});
            }
        }
        Collections.sort(keep, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(a[0], b[0]);
            }
        });
        float[] out = new float[keep.size() * 2];
        int n = 0;
        for (float[] r : keep) {
            if (n > 0 && r[0] <= out[n - 1]) {
                out[n - 1] = Math.max(out[n - 1], r[1]);
            } else {
                out[n++] = r[0];
                out[n++] = r[1];
            }
        }
        return n == 0 ? NO_RANGES : Arrays.copyOf(out, n);
    }

    /**
     * @return the laneless time as merged ascending pairs {@code from0, to0, from1, to1, …} in beats (−∞ / +∞ for an
     *         open start / end); the chart's own array, callers must not modify it
     */
    public float[] lanelessRanges() { return laneless; }

    /**
     * @return how much of the lanes' look is shown at {@code beat}: 1 in laned time, 0 in laneless time, fading
     *         linearly over {@link #LANELESS_FADE_BEATS} centred on each edge of a laneless range (Kelvin 2026-11-07)
     */
    public float lanesShown(float beat) {
        float shown = 1f;
        for (int i = 0; i < laneless.length; i += 2) {
            float from = laneless[i];
            if (from - LANELESS_FADE_BEATS * 0.5f >= beat) break; // ranges ascend: none later reaches this beat
            float out = (from + LANELESS_FADE_BEATS * 0.5f - beat) / LANELESS_FADE_BEATS;
            float in = (beat - laneless[i + 1] + LANELESS_FADE_BEATS * 0.5f) / LANELESS_FADE_BEATS;
            float v = Math.max(out, in);
            if (v < shown) shown = v < 0f ? 0f : v;
        }
        return shown;
    }

    // ------------------------------------------------------------------ files

    /** Attaches the files this chart was loaded with (any may be null). */
    public void setAssetFiles(FileHandle chartFile, FileHandle musicFile, FileHandle jacketFile) {
        this.chartFile = chartFile;
        this.musicFile = musicFile;
        this.jacketFile = jacketFile;
    }

    /** @return the chart JSON file, or null when loaded from a stream. */
    public FileHandle chartFile() { return chartFile; }
    /** @return the song music, or null. */
    public FileHandle musicFile() { return musicFile; }
    /** @return the jacket image, or null. */
    public FileHandle jacketFile() { return jacketFile; }

    @Override
    public String toString() {
        return "Chart(" + title + " " + level + ", v" + sourceFormat + ", " + bpm + " bpm, " + lanes.length
            + " lanes, " + count(NoteType.TAP) + " tap / " + count(NoteType.HOLD) + " hold / "
            + count(NoteType.MINE) + " mine" + (laneMineTotal > 0 ? " (" + laneMineTotal + " LANE MINE)" : "")
            + " / " + count(NoteType.UNIVERSAL) + " universal"
            + (count(NoteType.DRAG) > 0 ? " / " + count(NoteType.DRAG) + " drag" : "")
            + (tideStreams > 0 ? " (" + tideStreams + " TIDE STREAM)" : "") + ")";
    }

    /**
     * What {@link ChartMapper} fixed or dropped while loading (ARCHITECTURE §13.3). The loader
     * logs one summary line per chart when anything here is non-zero.
     */
    public static final class LoadReport {
        int sourceFormat = ChartMapper.FORMAT_VERSION;
        int remappedLanes;
        int duplicatesRemoved;
        int unknownTypes;
        int invalidLanes;
        int invalidTimes;
        int unknownLines;
        int autoAddedLanes;
        int rejectedLanes;
        int trailsFixed;
        int trailsIgnored;
        int glitchesFixed;
        int glitchesIgnored;
        int distortionsFixed;
        int distortionsIgnored;
        int heightEventsIgnored;
        int laneMineConflicts;
        int pivotsFixed;
        int veilsFixed;
        int veilsDropped;
        final List<String> messages = new ArrayList<>(2);

        /** @return the source format version (1 or 2; a newer version is read as 2). */
        public int sourceFormat() { return sourceFormat; }
        /** @return v1 notes whose lane was outside 0..19 and became {@code lane % 20}. */
        public int remappedLanes() { return remappedLanes; }
        /** @return 0: nothing is removed as a duplicate since Kelvin 2026-11-02 (kept for the chart editor). */
        public int duplicatesRemoved() { return duplicatesRemoved; }
        /** @return notes dropped for a missing or unknown type. */
        public int unknownTypes() { return unknownTypes; }
        /** @return v2 lane notes dropped for a missing lane or lane 0 (when the chart does not insert one). */
        public int invalidLanes() { return invalidLanes; }
        /** @return notes dropped for a missing or non-finite judgement beat. */
        public int invalidTimes() { return invalidTimes; }
        /** @return v2 notes with an unknown {@code line} string (read as lower). */
        public int unknownLines() { return unknownLines; }
        /** @return lanes added because notes referenced undeclared ids. */
        public int autoAddedLanes() { return autoAddedLanes; }
        /** @return declared lanes rejected (id 0 without {@code insert}, missing id or a repeated id). */
        public int rejectedLanes() { return rejectedLanes; }
        /**
         * @return v2 note trails whose values had to be sanitised (ARCHITECTURE §17: a non-object
         *         trail, bad or out-of-range numbers, an unknown mode, {@code from > to})
         */
        public int trailsFixed() { return trailsFixed; }
        /** @return trails found in a v1 chart and ignored (v1 charts get no trails). */
        public int trailsIgnored() { return trailsIgnored; }
        /**
         * @return v2 notes whose {@code glitch} had to be sanitised (CHART_FORMAT_v4 §6.9: a value that
         *         is not an object or an array of objects, bad or out-of-range numbers, a
         *         {@code duration} without {@code from}, {@code from > to})
         */
        public int glitchesFixed() { return glitchesFixed; }
        /** @return notes of a v1 chart whose {@code glitch} was ignored (v1 charts get no glitches). */
        public int glitchesIgnored() { return glitchesIgnored; }
        /** @return notes whose {@code distortion} had to be sanitised (CHART_FORMAT_v4 §6.11; veils: veilsFixed). */
        public int distortionsFixed() { return distortionsFixed; }
        /** @return notes of a v1 chart whose {@code distortion} was ignored. */
        public int distortionsIgnored() { return distortionsIgnored; }
        /**
         * @return notes of a v1 chart whose {@code HeightEvents} were ignored (v1 charts keep the
         *         default tail rule)
         */
        public int heightEventsIgnored() { return heightEventsIgnored; }
        /**
         * @return tap / hold heads and plain mines inside a LANE MINE's block window on the same
         *         line and lane ({@link Chart#laneMineConflicts}); the chart still loads, the
         *         tutorial checker forbids them
         */
        public int laneMineConflicts() { return laneMineConflicts; }
        /**
         * @return notes whose {@code pivot} was not two finite numbers (CHART_FORMAT_v4 §6.1.1; read as far
         *         as it went, a missing or bad coordinate 0)
         */
        public int pivotsFixed() { return pivotsFixed; }
        /** @return veils whose values had to be sanitised (CHART_FORMAT_v4 §6.10). */
        public int veilsFixed() { return veilsFixed; }
        /** @return veils dropped: not an object, or a polygon with fewer than 3 usable points. */
        public int veilsDropped() { return veilsDropped; }

        /** @return true when nothing was fixed or dropped. */
        public boolean clean() {
            return remappedLanes == 0 && duplicatesRemoved == 0 && unknownTypes == 0 && invalidLanes == 0
                && invalidTimes == 0 && unknownLines == 0 && autoAddedLanes == 0 && rejectedLanes == 0
                && trailsFixed == 0 && trailsIgnored == 0 && glitchesFixed == 0 && glitchesIgnored == 0
                && distortionsFixed == 0 && distortionsIgnored == 0
                && heightEventsIgnored == 0 && laneMineConflicts == 0 && pivotsFixed == 0
                && veilsFixed == 0 && veilsDropped == 0 && messages.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("v").append(sourceFormat);
            append(sb, "lane(s) remapped by % 20", remappedLanes);
            append(sb, "duplicate note(s) removed", duplicatesRemoved);
            append(sb, "note(s) with unknown type dropped", unknownTypes);
            append(sb, "note(s) on lane 0 / without lane dropped", invalidLanes);
            append(sb, "note(s) without a finite judgement dropped", invalidTimes);
            append(sb, "note(s) with unknown line read as lower", unknownLines);
            append(sb, "undeclared lane(s) added", autoAddedLanes);
            append(sb, "lane declaration(s) rejected", rejectedLanes);
            append(sb, "trail(s) sanitised", trailsFixed);
            append(sb, "trail(s) of a v1 chart ignored", trailsIgnored);
            append(sb, "glitch(es) sanitised", glitchesFixed);
            append(sb, "glitch(es) of a v1 chart ignored", glitchesIgnored);
            append(sb, "distortion(s) sanitised", distortionsFixed);
            append(sb, "distortion(s) of a v1 chart ignored", distortionsIgnored);
            append(sb, "HeightEvents of a v1 chart ignored", heightEventsIgnored);
            append(sb, "note(s) inside a LANE MINE's block window", laneMineConflicts);
            append(sb, "pivot(s) sanitised", pivotsFixed);
            append(sb, "veil(s) sanitised", veilsFixed);
            append(sb, "veil(s) dropped", veilsDropped);
            for (String s : messages) sb.append("; ").append(s);
            return sb.toString();
        }

        private static void append(StringBuilder sb, String what, int n) {
            if (n > 0) sb.append("; ").append(n).append(' ').append(what);
        }
    }
}
