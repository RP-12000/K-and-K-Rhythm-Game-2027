package org.kelvinizer.elements;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.kelvinizer.Params;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 谱面的读取和写出：解析 v2 格式，并把 v1 谱面精确转换成 v2；修正或丢弃的内容记入加载报告。
 *
 * <p>Loads (and writes) charts: format v4 parsing, the exact v1 → v2 conversion of
 * ARCHITECTURE §13.3, the v2 → v3 change of frame and the v3 → v4 move of the lines. Thread-safe; {@code Main}
 * calls it on the chart loader thread.
 *
 * <p><b>Format v3</b> ({@code "formatVersion": 3}, Kelvin 2026-10-19 03:51: 「坐标系我们现在修改为1920*1200」) is
 * format v2 with every position in the 1920 × 1200 reference frame instead of 1920 × 1080. Format v1 and v2 charts
 * are read in the old frame and their y values (line heights and their events, note distances and tail lengths
 * with their events, pivot y) are multiplied by 1200 / 1080 on load ({@link #fromFrame1080}), so they look and
 * play exactly as before; x values (lane widths, camera frame, pivot x) keep their numbers.
 *
 * <p><b>Format v4</b> ({@code "formatVersion": 4}, Kelvin 2026-10-19 14:55: 「我觉得换成1080/120」) is format v3 with
 * the default judgement lines at 1080 / 120 instead of 1066.67 / 133.33. Older charts are moved on load
 * ({@link #toLines1080}): line heights and their events are stretched about the frame's centre y 600 by 36 / 35, the
 * gap between the lines (960 over 933.33), and rounded to 0.001 ({@link #toV4Line}: 1066.67 → 1080, 133.33 → 120).
 * Every other value keeps its number: note distances, tail lengths and pivots are measured from the line, so notes
 * still reach the lines on their beats, and the default fall stays 1080 over 2 beats (Kelvin 15:19:
 * 「默认下落改为两拍1080的距离」). The judgement zone stays ±480 about each line, so the two zones meet at the
 * centre. The writer always writes v4.
 *
 * <p>Parsing uses the Jackson streaming API (no reflection, so it is ProGuard-safe and fast).
 * Unknown keys are skipped, so stale keys in the shipped files ({@code combo}, {@code score},
 * {@code finalScore}, {@code endTime}, {@code typeName}, …) are ignored.
 *
 * <p><b>Format v2</b> ({@code "formatVersion": 2}): {@code lanes} (absent → ids −4..−1, 1..4,
 * width 240, opacity 1), notes with {@code type} tap / hold / mine / universal
 * (case-insensitive), {@code line} lower (default) / upper, {@code lane} for lane notes, and
 * {@code LineEvents} (absent lines → 1080 / 120 in v4, 1066.67 / 133.33 in v3, 960 / 120 in v2). A note on an
 * undeclared lane adds that lane with defaults; lane 0 is rejected unless the chart inserts it. A
 * lane's optional {@code insert} object (owner 2026-10-15, CHART_FORMAT §3.6) inserts it during the
 * song ({@link Lane#inserted()}), its optional {@code delete} object (owner 2026-10-17, §3.7) deletes
 * it ({@link Lane#deleted()}). Optional {@code offsetMs}:
 * song time in ms of beat 0 (absent → 0; non-finite → 0 with a report message), so the beat grid
 * can start where the music's first beat is; v1 charts ignore it. Optional {@code laneCount}
 * (owner 2026-10-13, {@link Chart#laneLimit()}): N ≥ 1 makes the lanes finite (the lanes as
 * declared share a 1920 × lane scale wide frame about the axis; the editor declares
 * −⌊N/2⌋..−1, 1..⌈N/2⌉); without it the lanes are unlimited (each keeps its width from the
 * axis) and the loader adds the gap lanes and the lanes the screen can reach
 * ({@link Lane#implicit()}, not written back). Optional {@code LineEvents.laneScale} /
 * {@code laneScaleEvents} (owner 2026-10-13): global lane scaling, a factor on every lane width
 * ({@link JudgementLine#laneScaleAt}; absent → 1, negative or non-finite base → 1 with a report
 * message); scaling some lanes is their {@code widthEvents}. Optional {@code LineEvents.cameraFrame}
 * / {@code cameraFrameEvents} (owner 2026-10-13 05:29): the lanes' axis offset from x 960
 * ({@link JudgementLine#cameraFrameAt}; absent or non-finite base → 0). The old window keys
 * ({@code leftBound}, {@code rightBound}, {@code leftEvents}, {@code rightEvents}) are no longer
 * written; read in v2 they become the camera frame and lane scale for finite lanes and are
 * dropped for unlimited lanes (a report message unless they are the old defaults).
 *
 * <p><b>Format v1</b> (no {@code formatVersion}; every shipped song chart) converts exactly:
 * <ol>
 *   <li>a lane outside 0..19 becomes {@code lane % 20} (the shipped lane-115 note → 15);</li>
 *   <li>{@code line = lane ≥ 10 ? upper : lower}; with {@code c = lane % 10} the id is
 *       {@code c < 5 ? c − 5 : c − 4} (0 → −5, 4 → −1, 5 → 1, 9 → 5);</li>
 *   <li>unlimited default lanes (owner 2026-10-13): the ten 240-wide lanes filled the window
 *       (absent bounds → −240 / 2160), so its width over 2400 becomes the global lane scale and
 *       its centre's offset from x 960 the camera frame (owner 2026-10-13 05:29), events
 *       included, so the look is unchanged (the shipped songs: scale 1, 0.8 for beats 526–562,
 *       camera frame 0). A left and right event pair with the same times and exponent converts
 *       exactly; unpaired window events are dropped (a report message);</li>
 *   <li>line y values and events are copied unchanged;</li>
 *   <li>a note whose motion misses its judgement ({@link Note#v1NeedsApproach}) gets the
 *       default approach as its movement event ({@link Note#defaultApproachEvent}), the look it
 *       had before format v2 used motion as written (owner 2026-10-13).</li>
 * </ol>
 * In both formats tap / hold / mine notes with the same (line, lane, judgement beat) are
 * de-duplicated (a hold beats a tap beats a mine; the longer hold, or the longer mine, wins;
 * otherwise the first is kept). Universal notes are never de-duplicated: stacked ones are
 * intentional.
 *
 * <p><b>TIDE STREAM</b> (ARCHITECTURE §19): a {@code universal} note with a positive
 * {@code duration} (beats) is a TIDE STREAM; {@code height} is its band length, as for holds.
 * A missing, zero, negative or non-finite duration leaves a TIDE drop (the same sanitising as a
 * hold's duration, not counted in the load report). There is no new {@code type} string.
 *
 * <p><b>LANE MINE</b> (ARCHITECTURE §20): likewise a {@code mine} with a positive {@code duration}
 * is a LANE MINE (its {@code height} is the column length). A lane note (tap / hold head) or a
 * plain mine inside a LANE MINE's block window on the same line and lane is counted in
 * {@link Chart.LoadReport#laneMineConflicts()} (the chart still loads).
 *
 * <p><b>HeightEvents</b> (ARCHITECTURE §20 "Tail motion"): optional {@code "HeightEvents"} on a
 * hold, TIDE STREAM or LANE MINE (event type {@code Height}, the same fields as every event list;
 * value = visible tail length in ref units). Ignored on other notes and on v1 charts
 * ({@link Chart.LoadReport#heightEventsIgnored()}).
 *
 * <p>Notes with an unknown type or a non-finite judgement beat are dropped. Everything fixed or
 * dropped is counted in {@link Chart#loadReport()} and logged once per chart. A chart without a
 * positive {@code bpm} cannot be timed and fails to load.
 *
 * <p><b>Afterimage trails</b> (ARCHITECTURE §17): a v2 note may carry
 * {@code "trail": {"length", "density", "alpha", "fade", "mode", "from", "to"}} (the key and its
 * fields are read case-insensitively). Missing {@code length} / {@code density} take the readable
 * defaults 0.5 / 16 of DESIGN §17.2, missing {@code alpha} / {@code fade} / {@code mode} 0.6 / 1 /
 * path, missing {@code from} / {@code to} are unbounded. Bad values are sanitised by
 * {@link NoteTrail#of} and counted in {@link Chart.LoadReport#trailsFixed()}; v1 charts get no
 * trails ({@link Chart.LoadReport#trailsIgnored()}). Since 2026-10-14 {@code "trail"} may also be
 * an array of such objects: segments, each with its own window and look.
 *
 * <p><b>Glitches</b> (CHART_FORMAT §6.9): a v2 note may carry {@code "glitch"}, one object or an
 * array of objects with {@code from}, {@code to} (or {@code duration}), {@code intensity},
 * {@code intensityEnd}, {@code exponent}, {@code rate}, {@code split}, {@code slice} and
 * {@code flicker} (read case-insensitively; sanitised by {@link NoteGlitch#of}, counted in
 * {@link Chart.LoadReport#glitchesFixed()}); v1 charts get none
 * ({@link Chart.LoadReport#glitchesIgnored()}).
 *
 * <p><b>Pivot</b> (CHART_FORMAT §6.1.1, owner 2026-10-16): a note may carry {@code "pivot": [x, y]}
 * (or an object with {@code x} and {@code y}; the key read case-insensitively): the point it turns
 * about, in reference units in its own frame from its origin ({@link Note#pivotX()}). Missing = the
 * origin. A value that is not two finite numbers is read as far as it goes (a missing or bad
 * coordinate is 0) and counted in {@link Chart.LoadReport#pivotsFixed()}. Written only when not the
 * origin.
 */
public final class ChartMapper {
    /** Raw lane count of format v1 (0..9 lower line, 10..19 upper line); used by the converter only. */
    public static final int LANES = 20;
    /** Chart format this loader writes and reads natively: v4, the 1920 × 1200 frame with the lines at 1080 / 120. */
    public static final int FORMAT_VERSION = 4;
    /** Format v4's stretch of the line heights about y 600: the gap between the lines, 960 / 933.33. */
    private static final double V4_STRETCH = 36.0 / 35.0;
    /** Default v2 lane ids when a chart declares no {@code lanes}. */
    private static final int[] DEFAULT_LANE_IDS = {-4, -3, -2, -1, 1, 2, 3, 4};
    /** Lanes of a format v1 chart (±1..±5). */
    private static final int V1_LANE_COUNT = 10;
    /** Largest |lane id| the loader adds for unlimited lanes (64 lanes of 240 reach far past any window). */
    private static final int MAX_LANE_ID = 64;
    /** Window bounds of v1 charts that omit them (ten 240-wide lanes, ±5 off screen). */
    private static final float V1_DEFAULT_LEFT = -240f;
    private static final float V1_DEFAULT_RIGHT = 2160f;
    /** Width of a v1 window at lane scale 1: its ten 240-wide lanes. */
    private static final float V1_SPAN = V1_LANE_COUNT * Lane.DEFAULT_WIDTH;

    private static final String TAG = "ChartMapper";
    private static final JsonFactory FACTORY = new JsonFactory();
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final String[] AUDIO_EXT = {"ogg", "mp3", "wav"};
    private static final String[] IMAGE_EXT = {"png", "jpg", "jpeg"};

    private ChartMapper() {
    }

    // ------------------------------------------------------------------ reading

    /** Parses a chart from a stream (the stream is closed). */
    public static Chart read(InputStream inputStream) throws IOException {
        return read(inputStream, null);
    }

    /**
     * Parses a chart from a stream (the stream is closed).
     *
     * @param source name used in the load log line (may be null)
     * @throws IOException for malformed JSON, a missing / non-positive bpm, or an I/O error
     */
    public static Chart read(InputStream inputStream, String source) throws IOException {
        try (InputStream in = inputStream) {
            return parse(in, source != null ? source : "chart");
        }
    }

    /**
     * Parses a chart file, or the first {@code .json} of a folder, and attaches the folder's
     * music ({@code <folder>.(ogg|mp3|wav)} preferred) and jacket ({@code jacket.png} preferred).
     */
    public static Chart read(FileHandle fileHandle) throws IOException {
        FileHandle chartFile = fileHandle.isDirectory() ? findRequired(fileHandle) : fileHandle;
        FileHandle folder = chartFile.parent();
        return read(chartFile, findMusic(folder), findJacket(folder));
    }

    /**
     * Parses a chart file and attaches the given music and jacket handles (either may be null).
     * Used by {@code SongLibrary.loadChart}.
     */
    public static Chart read(FileHandle chartFile, FileHandle musicFile, FileHandle jacketFile) throws IOException {
        Chart chart;
        try (InputStream in = chartFile.read()) {
            chart = parse(in, chartFile.path());
        } catch (RuntimeException e) {
            throw new IOException("Cannot read chart " + chartFile.path(), e);
        }
        chart.setAssetFiles(chartFile, musicFile, jacketFile);
        return chart;
    }

    /**
     * Maps a raw v1 lane to its v2 lane id: {@code lane % 20} (normalised to 0..19), then
     * {@code c = lane % 10}, id {@code c < 5 ? c − 5 : c − 4}.
     */
    public static int v1LaneId(int rawLane) {
        int c = v1Lane(rawLane) % 10;
        return c < 5 ? c - 5 : c - 4;
    }

    /** @return the line of a raw v1 lane: upper when {@code lane % 20 ≥ 10}. */
    public static Line v1Line(int rawLane) {
        return v1Lane(rawLane) >= 10 ? Line.UPPER : Line.LOWER;
    }

    private static int v1Lane(int rawLane) {
        return ((rawLane % LANES) + LANES) % LANES;
    }

    private static Chart parse(InputStream in, String source) throws IOException {
        RawChart raw;
        try (JsonParser p = FACTORY.createParser(in)) {
            raw = parseChart(p);
        }
        try {
            return build(raw, source);
        } catch (IllegalArgumentException e) {
            throw new IOException(source + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ v1/v2 → model

    private static Chart build(RawChart raw, String source) throws IOException {
        Chart.LoadReport report = new Chart.LoadReport();
        boolean v1 = raw.formatVersion < 2;
        report.sourceFormat = v1 ? 1 : Math.min(raw.formatVersion, FORMAT_VERSION);
        if (raw.formatVersion > FORMAT_VERSION) {
            report.messages.add("format " + raw.formatVersion + " read as " + FORMAT_VERSION);
        }
        if (raw.formatVersion < 3) toFrame1200(raw);
        if (raw.formatVersion < 4) toLines1080(raw);
        float[][] tempo = tempoMap(raw.bpmList, report);
        float bpm = raw.bpm;
        if (!(bpm > 0f) || bpm == Float.POSITIVE_INFINITY) {
            if (tempo == null) throw new IOException(source + ": missing or invalid bpm");
            bpm = tempo[1][0];
        }
        float offsetMs = 0f;
        if (raw.hasOffset) {
            if (v1) report.messages.add("offsetMs of a v1 chart ignored");
            else if (Float.isNaN(raw.offsetMs) || Float.isInfinite(raw.offsetMs)) report.messages.add("invalid offsetMs read as 0");
            else offsetMs = raw.offsetMs;
        }

        // Lanes, keyed by id (the Chart sorts them).
        Map<Integer, Lane> lanes = new LinkedHashMap<>();
        if (v1) {
            for (int id = -5; id <= 5; id++) {
                if (id != 0) lanes.put(id, new Lane(id));
            }
            if (raw.lanes != null) report.messages.add("lanes of a v1 chart ignored");
        } else if (raw.lanes == null) {
            for (int id : DEFAULT_LANE_IDS) lanes.put(id, new Lane(id));
        } else {
            for (RawLane r : raw.lanes) {
                if (r == null || !r.hasId || (r.id == 0 && !r.hasInsert) || lanes.containsKey(r.id)) {
                    report.rejectedLanes++;
                    continue;
                }
                lanes.put(r.id, r.hasInsert ? null : deletedLane(r, new Lane(r.id, r.width, r.opacity, r.widthEvents, r.opacityEvents)));
            }
            // Inserted lanes (owner 2026-10-15) last: the final width defaults to their neighbours'.
            for (RawLane r : raw.lanes) {
                if (r != null && r.hasInsert && lanes.containsKey(r.id) && lanes.get(r.id) == null) {
                    lanes.put(r.id, deletedLane(r, insertedLane(r, lanes)));
                }
            }
        }

        // Notes.
        List<Note> notes = new ArrayList<>(raw.notes.size());
        for (RawNote r : raw.notes) {
            NoteType type = NoteType.fromJson(r.type);
            if (type == null) {
                report.unknownTypes++;
                continue;
            }
            if (Float.isNaN(r.judgement) || Float.isInfinite(r.judgement)) {
                report.invalidTimes++;
                continue;
            }
            Line line;
            int laneId = 0;
            if (v1 && type.inLane()) {
                if (r.laneInvalid) {
                    report.invalidLanes++;
                    continue;
                }
                int lane = r.lane; // a missing v1 lane read as 0, as the old loader did
                if (lane < 0 || lane >= LANES) report.remappedLanes++;
                line = v1Line(lane);
                laneId = v1LaneId(lane);
            } else {
                line = r.line == null ? Line.LOWER : Line.fromJson(r.line);
                if (line == null) {
                    report.unknownLines++;
                    line = Line.LOWER;
                }
                if (type.inLane()) {
                    if (!r.hasLane || r.laneInvalid || (r.lane == 0 && !lanes.containsKey(0))) {
                        report.invalidLanes++;
                        continue;
                    }
                    laneId = r.lane;
                    if (!lanes.containsKey(laneId)) {
                        lanes.put(laneId, new Lane(laneId));
                        report.autoAddedLanes++;
                    }
                }
            }
            List<NoteTrail> trails = null;
            if (r.hasTrail) {
                if (v1) {
                    report.trailsIgnored++;
                } else {
                    trails = r.trails;
                    if (r.trailFixed) report.trailsFixed++;
                }
            }
            List<NoteGlitch> glitches = null;
            if (r.hasGlitch) {
                if (v1) {
                    report.glitchesIgnored++;
                } else {
                    glitches = r.glitches;
                    if (r.glitchFixed) report.glitchesFixed++;
                }
            }
            List<Events> heights = r.heights;
            if (v1 && heights != null && !heights.isEmpty()) {
                report.heightEventsIgnored++;
                heights = null;
            }
            List<Events> movement = r.movement;
            // v1 notes whose motion misses the judgement moved with the default approach: keep that look
            if (v1 && Note.v1NeedsApproach(movement, r.appearance, r.judgement)) {
                movement = Collections.singletonList(Note.defaultApproachEvent(r.judgement));
            }
            if (r.pivotFixed) report.pivotsFixed++;
            notes.add(new Note(type, line, laneId, r.appearance, r.judgement, r.duration, r.height,
                movement, r.rotation, r.disappear, heights, trails, glitches, r.pivotX, r.pivotY));
        }
        notes = dedupe(notes, report);

        RawLine rl = raw.line != null ? raw.line : new RawLine();
        JudgementLine geometry = v1 ? v1Line(rl, report) : v2Line(rl, report, raw.hasLaneCount && raw.laneCount >= 1);

        // Lane mode (owner 2026-10-13): unlimited unless a v2 laneCount says finite.
        int laneLimit = 0;
        if (v1) {
            if (raw.hasLaneCount) report.messages.add("laneCount of a v1 chart ignored");
        } else if (raw.hasLaneCount) {
            if (raw.laneCount >= 1) {
                laneLimit = (int) Math.min(raw.laneCount, 2L * MAX_LANE_ID);
                if (laneLimit != raw.laneCount) report.messages.add("laneCount " + raw.laneCount + " read as " + laneLimit);
            } else {
                report.messages.add("invalid laneCount read as unlimited lanes");
            }
        }
        if (laneLimit == 0) fillUnlimited(lanes, geometry); // finite: the lanes as declared share the frame

        Chart chart = new Chart(raw.title, raw.artist, raw.illustrator, raw.charter, raw.level,
            raw.chartConstant, bpm, offsetMs, new ArrayList<>(lanes.values()), geometry, notes,
            tempo != null ? tempo[0] : null, tempo != null ? tempo[1] : null, laneLimit);
        chart.sourceFormat = report.sourceFormat;
        report.laneMineConflicts = chart.laneMineConflicts(Params.LANE_MINE_LEAD_MS);
        chart.report = report;
        if (!report.clean() && Gdx.app != null) {
            Gdx.app.log(TAG, source + ": " + report);
        }
        return chart;
    }

    /**
     * Unlimited lanes: adds default lanes ({@link Lane#implicit()}) for the id gaps, since lanes are
     * laid out by id from the axis, and for the screen as seen from the axis at its farthest (the
     * camera frame's base and event end values) at the smallest lane scale, so the lanes cover it.
     * At most {@link #MAX_LANE_ID} lanes each side.
     */
    private static void fillUnlimited(Map<Integer, Lane> lanes, JudgementLine g) {
        int lo = 0, hi = 0;
        for (int id : lanes.keySet()) {
            lo = Math.min(lo, id);
            hi = Math.max(hi, id);
        }
        float k = g.laneScaleMin();
        // inserted lanes squeeze the others, so the screen can show up to their widths more (and
        // narrowest() counts them as 0 wide)
        float more = 0f;
        for (Lane l : lanes.values()) {
            if (l.inserted()) more += Math.max(finiteWidth(l.insert().initialValue()), finiteWidth(l.baseWidth())) * k;
        }
        float reach = LaneLayout.VIEW_RIGHT - LaneLayout.TILE_CENTRE - g.cameraFrameMin() + more;
        float x = 0f;
        for (int id = 1; id <= MAX_LANE_ID && (id <= hi || x < reach); id++) {
            x += narrowest(laneOrImplicit(lanes, id)) * k;
        }
        reach = LaneLayout.TILE_CENTRE + g.cameraFrameMax() - LaneLayout.VIEW_LEFT + more;
        x = 0f;
        for (int id = -1; id >= -MAX_LANE_ID && (id >= lo || x < reach); id--) {
            x += narrowest(laneOrImplicit(lanes, id)) * k;
        }
    }

    /**
     * @return the v2 line geometry: lane scale and camera frame checked. The old window keys
     *         (before 2026-10-13 05:29): finite lanes divided the window, so it becomes the camera
     *         frame and lane scale as in v1 ({@link #windowLine}, 1920 wide at scale 1) unless the
     *         chart sets those too; unlimited lanes never followed it, so it is dropped. Either
     *         way a report message.
     */
    private static JudgementLine v2Line(RawLine rl, Chart.LoadReport report, boolean finite) {
        boolean window = (rl.left == rl.left && rl.left != JudgementLine.DEFAULT_LEFT)
            || (rl.right == rl.right && rl.right != JudgementLine.DEFAULT_RIGHT)
            || (rl.leftEvents != null && !rl.leftEvents.isEmpty()) || (rl.rightEvents != null && !rl.rightEvents.isEmpty());
        boolean framed = rl.hasLaneScale || rl.laneScaleEvents != null || rl.hasCamera || rl.cameraEvents != null;
        if (window && finite && !framed) {
            report.messages.add("window (leftBound / rightBound) read as cameraFrame / laneScale");
            return windowLine(rl, JudgementLine.DEFAULT_LEFT, JudgementLine.DEFAULT_RIGHT,
                JudgementLine.DEFAULT_RIGHT - JudgementLine.DEFAULT_LEFT, report);
        }
        if (window) report.messages.add("window (leftBound / rightBound) ignored: lanes show across the screen; use cameraFrame / laneScale");
        float laneScale = 1f;
        if (rl.hasLaneScale) {
            if (rl.laneScale >= 0f && rl.laneScale < Float.POSITIVE_INFINITY) laneScale = rl.laneScale;
            else report.messages.add("invalid laneScale read as 1");
        }
        float camera = 0f;
        if (rl.hasCamera) {
            if (rl.camera > Float.NEGATIVE_INFINITY && rl.camera < Float.POSITIVE_INFINITY) camera = rl.camera;
            else report.messages.add("invalid cameraFrame read as 0");
        }
        return new JudgementLine(orDefault(rl.upper, JudgementLine.DEFAULT_UPPER), orDefault(rl.lower, JudgementLine.DEFAULT_LOWER),
            rl.upperEvents, rl.lowerEvents, laneScale, rl.laneScaleEvents, camera, rl.cameraEvents);
    }

    /** @return the v1 line geometry: its window as the lane scale and camera frame ({@link #windowLine}, 2400 wide). */
    private static JudgementLine v1Line(RawLine rl, Chart.LoadReport report) {
        if (rl.hasLaneScale || rl.laneScaleEvents != null) report.messages.add("laneScale of a v1 chart ignored");
        if (rl.hasCamera || rl.cameraEvents != null) report.messages.add("cameraFrame of a v1 chart ignored");
        return windowLine(rl, V1_DEFAULT_LEFT, V1_DEFAULT_RIGHT, V1_SPAN, report);
    }

    /**
     * An old window as the global lane scale and the camera frame (owner 2026-10-13 05:29): the
     * lanes filled a window {@code span} wide at scale 1 (v1: ten 240-wide lanes, {@link #V1_SPAN}),
     * so its width over {@code span} is the scale and its centre's offset from x 960 the camera
     * frame. Each left / right event pair with the same times and exponent (every shipped song)
     * becomes one event of each, exactly; otherwise the window events are dropped with a report
     * message. A channel that never leaves its default (scale 1, camera frame 0) gets no events.
     */
    private static JudgementLine windowLine(RawLine rl, float defLeft, float defRight, float span, Chart.LoadReport report) {
        float l0 = orDefault(rl.left, defLeft);
        float r0 = orDefault(rl.right, defRight);
        float scale = (r0 - l0) / span;
        float camera = (l0 + r0) * 0.5f - LaneLayout.TILE_CENTRE;
        List<Events> left = Events.sortedCopy(rl.leftEvents);
        List<Events> right = Events.sortedCopy(rl.rightEvents);
        List<Events> scales = new ArrayList<>(left.size());
        List<Events> cameras = new ArrayList<>(left.size());
        boolean scaled = scale != 1f, moved = camera != 0f;
        boolean paired = left.size() == right.size();
        for (int i = 0; i < left.size() && paired; i++) {
            Events a = left.get(i);
            Events b = right.get(i);
            if (a.startTime() != b.startTime() || a.endTime() != b.endTime() || a.exponent() != b.exponent()) {
                paired = false;
                break;
            }
            Events s = new Events(Events.TYPE.LaneScale, a.startTime(), a.endTime(),
                (b.initialValue() - a.initialValue()) / span, (b.finalValue() - a.finalValue()) / span, a.exponent());
            Events c = new Events(Events.TYPE.CameraFrame, a.startTime(), a.endTime(),
                (a.initialValue() + b.initialValue()) * 0.5f - LaneLayout.TILE_CENTRE,
                (a.finalValue() + b.finalValue()) * 0.5f - LaneLayout.TILE_CENTRE, a.exponent());
            scaled |= s.initialValue() != 1f || s.finalValue() != 1f;
            moved |= c.initialValue() != 0f || c.finalValue() != 0f;
            scales.add(s);
            cameras.add(c);
        }
        if (!paired) {
            scales.clear();
            cameras.clear();
            report.messages.add("window events not in left / right pairs: ignored");
        }
        return new JudgementLine(orDefault(rl.upper, JudgementLine.DEFAULT_UPPER), orDefault(rl.lower, JudgementLine.DEFAULT_LOWER),
            rl.upperEvents, rl.lowerEvents, scale, scaled ? scales : null, camera, moved ? cameras : null);
    }

    /**
     * An inserted lane (owner 2026-10-15, {@code insert}): the final width is {@code insert.final},
     * else the lane's {@code width}, else the mean base width of the lanes next to it by id (a
     * missing one counts as a default lane), so it ends as wide as its neighbours. Non-finite or
     * negative values: initial 0, final the default, exponent 1; an end before the start is the start.
     */
    private static Lane insertedLane(RawLane r, Map<Integer, Lane> lanes) {
        float fin = r.insFinal >= 0f && r.insFinal < Float.POSITIVE_INFINITY ? r.insFinal : Float.NaN;
        if (fin != fin && r.hasWidth) fin = finiteWidth(r.width);
        if (fin != fin) {
            Lane below = null, above = null;
            for (Map.Entry<Integer, Lane> e : lanes.entrySet()) {
                int id = e.getKey();
                Lane l = e.getValue();
                if (l == null || l.inserted()) continue;
                if (id < r.id && (below == null || id > below.id())) below = l;
                if (id > r.id && (above == null || id < above.id())) above = l;
            }
            fin = 0.5f * ((below != null ? finiteWidth(below.baseWidth()) : Lane.DEFAULT_WIDTH)
                + (above != null ? finiteWidth(above.baseWidth()) : Lane.DEFAULT_WIDTH));
        }
        float start = r.insStart > Float.NEGATIVE_INFINITY && r.insStart < Float.POSITIVE_INFINITY ? r.insStart : 0f;
        float end = r.insEnd >= start && r.insEnd < Float.POSITIVE_INFINITY ? r.insEnd : start;
        float initial = finiteWidth(r.insInitial);
        float exponent = r.insExponent > 0f && r.insExponent < Float.POSITIVE_INFINITY ? r.insExponent : 1f;
        return new Lane(r.id, fin, r.opacity, r.widthEvents, r.opacityEvents,
            new Events(Events.TYPE.Width, start, end, initial, fin, exponent));
    }

    /**
     * A deleted lane (owner 2026-10-17, {@code delete}): {@code lane} with the deletion of {@code r}, or
     * {@code lane} itself without one. From {@code delete.startTime} the lane eases from
     * {@code delete.initial} (default: its width at the start without the deletion, so it does not jump)
     * to {@code delete.final} (default 0) by {@code delete.endTime} (default the start) and is 0 wide
     * after. Non-finite or negative values: start 0, initial the default, final 0, exponent 1; an end
     * before the start is the start.
     */
    private static Lane deletedLane(RawLane r, Lane lane) {
        if (!r.hasDelete) return lane;
        float start = r.delStart > Float.NEGATIVE_INFINITY && r.delStart < Float.POSITIVE_INFINITY ? r.delStart : 0f;
        float end = r.delEnd >= start && r.delEnd < Float.POSITIVE_INFINITY ? r.delEnd : start;
        float initial = r.delInitial >= 0f && r.delInitial < Float.POSITIVE_INFINITY ? r.delInitial : lane.widthAt(start);
        float exponent = r.delExponent > 0f && r.delExponent < Float.POSITIVE_INFINITY ? r.delExponent : 1f;
        return new Lane(lane.id(), lane.baseWidth(), lane.baseOpacity(), lane.widthEvents(), lane.opacityEvents(),
            lane.insert(), new Events(Events.TYPE.Width, start, end, initial, finiteWidth(r.delFinal), exponent));
    }

    private static Lane laneOrImplicit(Map<Integer, Lane> lanes, int id) {
        Lane l = lanes.get(id);
        if (l == null) {
            l = Lane.implicitLane(id);
            lanes.put(id, l);
        }
        return l;
    }

    /** @return the narrowest a lane gets: its base or an event's end width (eased widths stay between them). */
    private static float narrowest(Lane lane) {
        float w = finiteWidth(lane.baseWidth());
        if (lane.inserted()) w = 0f; // not there before its insertion
        for (Events e : lane.widthEvents()) {
            w = Math.min(w, Math.min(finiteWidth(e.initialValue()), finiteWidth(e.finalValue())));
        }
        return w;
    }

    private static float finiteWidth(float w) {
        return w > 0f && w < Float.POSITIVE_INFINITY ? w : 0f;
    }

    private static float orDefault(float v, float def) {
        return Float.isNaN(v) || Float.isInfinite(v) ? def : v;
    }

    /**
     * Removes tap / hold / mine notes that share (line, lane id, judgement beat) with another
     * one. Priority: hold (longer wins) over tap over mine; equal ones keep the first.
     */
    private static List<Note> dedupe(List<Note> notes, Chart.LoadReport report) {
        Map<String, Integer> winner = new HashMap<>();
        boolean[] drop = new boolean[notes.size()];
        int removed = 0;
        StringBuilder key = new StringBuilder(32);
        for (int i = 0; i < notes.size(); i++) {
            Note a = notes.get(i);
            if (!a.type().inLane()) continue;
            key.setLength(0);
            key.append(a.line().ordinal()).append(':').append(a.laneId()).append(':')
                .append(Float.floatToIntBits(a.judgement()));
            String k = key.toString();
            Integer w = winner.get(k);
            if (w == null) {
                winner.put(k, i);
                continue;
            }
            removed++;
            if (beats(a, notes.get(w))) {
                drop[w] = true;
                winner.put(k, i);
            } else {
                drop[i] = true;
            }
        }
        if (removed == 0) return notes;
        report.duplicatesRemoved += removed;
        List<Note> kept = new ArrayList<>(notes.size() - removed);
        for (int i = 0; i < notes.size(); i++) {
            if (!drop[i]) kept.add(notes.get(i));
        }
        return kept;
    }

    /** @return true when duplicate {@code a} should replace the earlier {@code b}. */
    private static boolean beats(Note a, Note b) {
        int ra = rank(a.type());
        int rb = rank(b.type());
        if (ra != rb) return ra > rb;
        return (a.type() == NoteType.HOLD || a.type() == NoteType.MINE) && a.duration() > b.duration();
    }

    private static int rank(NoteType t) {
        switch (t) {
            case HOLD: return 2;
            case TAP: return 1;
            default: return 0;
        }
    }

    /**
     * Scales a format v1 / v2 chart's y values from the 1920 × 1080 frame to the 1920 × 1200 frame: the line
     * heights and their events, every note's distances ({@code MovementEvents}), tail length and
     * {@code HeightEvents}, and pivot y. Absent values (NaN) stay absent, so they take the v3 defaults.
     */
    private static void toFrame1200(RawChart raw) {
        RawLine l = raw.line;
        if (l != null) {
            l.upper = fromFrame1080(l.upper);
            l.lower = fromFrame1080(l.lower);
            l.upperEvents = fromFrame1080(l.upperEvents);
            l.lowerEvents = fromFrame1080(l.lowerEvents);
        }
        for (RawNote r : raw.notes) {
            r.height = fromFrame1080(r.height);
            r.pivotY = fromFrame1080(r.pivotY);
            r.movement = fromFrame1080(r.movement);
            r.heights = fromFrame1080(r.heights);
        }
    }

    /**
     * @return a y value (position or distance) of the old 1920 × 1080 frame in the 1920 × 1200 frame:
     *         {@code y · 1200 / 1080} (so 120 → 133.33, 960 → 1066.67, 972 → 1080)
     */
    public static float fromFrame1080(float y) {
        return (float) (y * 10.0 / 9.0);
    }

    /** @return the events with both values scaled by {@link #fromFrame1080(float)} (null stays null). */
    public static List<Events> fromFrame1080(List<Events> events) {
        if (events == null) return null;
        List<Events> out = new ArrayList<>(events.size());
        for (Events e : events) {
            out.add(e == null ? null : new Events(e.type(), e.startTime(), e.endTime(), fromFrame1080(e.initialValue()),
                fromFrame1080(e.finalValue()), e.exponent()));
        }
        return out;
    }

    /**
     * Moves a format v1–v3 chart (already in the 1200-high frame) onto the v4 lines 1080 / 120: the line heights and
     * their events by {@link #toV4Line}. Absent values (NaN) stay absent, so they take the v4 defaults; the notes keep
     * their numbers.
     */
    private static void toLines1080(RawChart raw) {
        RawLine l = raw.line;
        if (l == null) return;
        l.upper = toV4Line(l.upper);
        l.lower = toV4Line(l.lower);
        l.upperEvents = toV4(l.upperEvents);
        l.lowerEvents = toV4(l.lowerEvents);
    }

    /**
     * @return a format v3 line height in format v4: {@code 600 + (y − 600) · 36/35} to 0.001, so the old lines land
     *         exactly on 1080 and 120 (1066.67 → 1080, 133.33 → 120)
     */
    public static float toV4Line(float y) {
        double cy = Params.REF_H * 0.5;
        return (float) (Math.rint((cy + (y - cy) * V4_STRETCH) * 1e3) / 1e3);
    }

    /** @return the line events with both values moved by {@link #toV4Line} (null stays null). */
    private static List<Events> toV4(List<Events> events) {
        if (events == null) return null;
        List<Events> out = new ArrayList<>(events.size());
        for (Events e : events) {
            out.add(e == null ? null : new Events(e.type(), e.startTime(), e.endTime(), toV4Line(e.initialValue()),
                toV4Line(e.finalValue()), e.exponent()));
        }
        return out;
    }

    // ------------------------------------------------------------------ streaming parse

    private static final class RawChart {
        int formatVersion = 1;
        String title;
        String artist;
        String illustrator;
        String charter;
        String level;
        String chartConstant;
        float bpm = Float.NaN;
        /** {@code bpmList} entries as read: {beat, bpm}, unsorted and unchecked (null = absent). */
        List<float[]> bpmList;
        boolean hasOffset;
        float offsetMs = Float.NaN;
        boolean hasLaneCount;
        long laneCount = NO_INT;
        List<RawLane> lanes;
        final List<RawNote> notes = new ArrayList<>();
        RawLine line;
    }

    private static final class RawLane {
        boolean hasId;
        int id;
        boolean hasWidth;
        float width = Lane.DEFAULT_WIDTH;
        float opacity = Lane.DEFAULT_OPACITY;
        List<Events> widthEvents;
        List<Events> opacityEvents;
        // insert (owner 2026-10-15)
        boolean hasInsert;
        float insStart;
        float insEnd;
        float insInitial;
        float insFinal = Float.NaN;
        float insExponent = 1f;
        // delete (owner 2026-10-17)
        boolean hasDelete;
        float delStart;
        float delEnd = Float.NaN;
        float delInitial = Float.NaN;
        float delFinal;
        float delExponent = 1f;
    }

    private static final class RawNote {
        String type;
        String line;
        boolean hasLane;
        boolean laneInvalid;
        int lane;
        float appearance = Float.NaN;
        float judgement = Float.NaN;
        float duration;
        float height;
        List<Events> movement;
        List<Events> rotation;
        List<Events> disappear;
        List<Events> heights;
        boolean hasTrail;
        boolean trailFixed;
        List<NoteTrail> trails;
        boolean hasGlitch;
        boolean glitchFixed;
        List<NoteGlitch> glitches;
        float pivotX;
        float pivotY;
        boolean pivotFixed;
    }

    private static final class RawLine {
        float upper = Float.NaN;
        float lower = Float.NaN;
        float left = Float.NaN;
        float right = Float.NaN;
        List<Events> upperEvents;
        List<Events> lowerEvents;
        List<Events> leftEvents;
        List<Events> rightEvents;
        boolean hasLaneScale;
        float laneScale = Float.NaN;
        List<Events> laneScaleEvents;
        boolean hasCamera;
        float camera = Float.NaN;
        List<Events> cameraEvents;
    }

    private static RawChart parseChart(JsonParser p) throws IOException {
        if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("chart is not a JSON object");
        RawChart c = new RawChart();
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "formatVersion": c.formatVersion = (int) floatValue(p, v, 1f); break;
                case "title": c.title = text(p, v); break;
                case "artist": c.artist = text(p, v); break;
                case "illustrator": c.illustrator = text(p, v); break;
                case "charter": c.charter = text(p, v); break;
                case "level": c.level = text(p, v); break;
                case "chartConstant": c.chartConstant = text(p, v); break;
                case "bpm": c.bpm = floatValue(p, v, Float.NaN); break;
                case "bpmList": c.bpmList = parseBpmList(p, v); break;
                case "offsetMs": c.hasOffset = true; c.offsetMs = floatValue(p, v, Float.NaN); break;
                case "lanes": c.lanes = parseLanes(p, v); break;
                case "laneCount": c.hasLaneCount = true; c.laneCount = intValue(p, v); break;
                case "notes": parseNotes(p, v, c.notes); break;
                case "LineEvents":
                case "judgementLine": c.line = parseLine(p, v); break;
                default: p.skipChildren(); break;
            }
        }
        if (t != JsonToken.END_OBJECT) throw new IOException("truncated chart JSON");
        return c;
    }

    /** Reads {@code bpmList}: an array of {@code {"beat", "bpm"}} objects; anything else in it is skipped. */
    private static List<float[]> parseBpmList(JsonParser p, JsonToken v) throws IOException {
        List<float[]> out = new ArrayList<>();
        if (v != JsonToken.START_ARRAY) {
            p.skipChildren();
            return out;
        }
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren();
                continue;
            }
            float beat = Float.NaN, bpm = Float.NaN;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken fv = p.nextToken();
                if ("beat".equals(name)) beat = floatValue(p, fv, Float.NaN);
                else if ("bpm".equals(name)) bpm = floatValue(p, fv, Float.NaN);
                else p.skipChildren();
            }
            out.add(new float[] {beat, bpm});
        }
        return out;
    }

    /**
     * Turns the raw {@code bpmList} into {beats, bpms} for {@link Chart}: entries without a finite beat
     * and a positive finite bpm are dropped (reported), the rest sorted by beat; of two entries on the
     * same beat the later one in the file wins.
     *
     * @return {beats, bpms}, or null when there is no usable entry
     */
    static float[][] tempoMap(List<float[]> raw, Chart.LoadReport report) {
        if (raw == null || raw.isEmpty()) return null;
        List<float[]> ok = new ArrayList<>();
        for (float[] e : raw) {
            if (Float.isNaN(e[0]) || Float.isInfinite(e[0]) || !(e[1] > 0f) || e[1] == Float.POSITIVE_INFINITY) continue;
            ok.add(e);
        }
        if (ok.size() < raw.size() && report != null) {
            report.messages.add("bpmList: " + (raw.size() - ok.size()) + " invalid entries dropped");
        }
        if (ok.isEmpty()) return null;
        // stable sort, so the later of two entries on the same beat stays last and wins below
        ok.sort((a, b) -> Float.compare(a[0], b[0]));
        List<float[]> merged = new ArrayList<>();
        for (float[] e : ok) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[0] == e[0]) merged.set(merged.size() - 1, e);
            else merged.add(e);
        }
        float[][] out = {new float[merged.size()], new float[merged.size()]};
        for (int i = 0; i < merged.size(); i++) {
            out[0][i] = merged.get(i)[0];
            out[1][i] = merged.get(i)[1];
        }
        return out;
    }

    private static List<RawLane> parseLanes(JsonParser p, JsonToken v) throws IOException {
        if (v != JsonToken.START_ARRAY) {
            p.skipChildren();
            return null;
        }
        List<RawLane> out = new ArrayList<>();
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren();
                out.add(null);
                continue;
            }
            RawLane l = new RawLane();
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken fv = p.nextToken();
                switch (name) {
                    case "id": {
                        long id = intValue(p, fv);
                        l.hasId = id != NO_INT;
                        l.id = l.hasId ? (int) id : 0;
                        break;
                    }
                    case "width": l.width = floatValue(p, fv, Lane.DEFAULT_WIDTH); l.hasWidth = true; break;
                    case "opacity": l.opacity = floatValue(p, fv, Lane.DEFAULT_OPACITY); break;
                    case "widthEvents": l.widthEvents = parseEvents(p, fv); break;
                    case "opacityEvents": l.opacityEvents = parseEvents(p, fv); break;
                    case "insert": parseInsert(p, fv, l); break;
                    case "delete": parseDelete(p, fv, l); break;
                    default: p.skipChildren(); break;
                }
            }
            out.add(l);
        }
        return out;
    }

    private static void parseNotes(JsonParser p, JsonToken v, List<RawNote> out) throws IOException {
        if (v != JsonToken.START_ARRAY) {
            p.skipChildren();
            return;
        }
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            RawNote n = new RawNote();
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren();
                out.add(n); // no type: counted as unknown
                continue;
            }
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken fv = p.nextToken();
                if (TRAIL_KEY.equalsIgnoreCase(name)) {
                    parseTrail(p, fv, n);
                    continue;
                }
                if (GLITCH_KEY.equalsIgnoreCase(name)) {
                    parseGlitch(p, fv, n);
                    continue;
                }
                if (PIVOT_KEY.equalsIgnoreCase(name)) {
                    parsePivot(p, fv, n);
                    continue;
                }
                switch (name) {
                    case "type": n.type = text(p, fv); break;
                    case "line": n.line = text(p, fv); break;
                    case "lane": {
                        long lane = intValue(p, fv);
                        n.hasLane = fv != JsonToken.VALUE_NULL;
                        n.laneInvalid = n.hasLane && lane == NO_INT;
                        n.lane = lane == NO_INT ? 0 : (int) lane;
                        break;
                    }
                    case "appearance": n.appearance = floatValue(p, fv, Float.NaN); break;
                    case "judgement": n.judgement = floatValue(p, fv, Float.NaN); break;
                    case "duration": n.duration = floatValue(p, fv, 0f); break;
                    case "height":
                    case "length": n.height = floatValue(p, fv, 0f); break;
                    case "MovementEvents": n.movement = parseEvents(p, fv); break;
                    case "RotationEvents": n.rotation = parseEvents(p, fv); break;
                    case "DisappearEvents":
                    case "OpacityEvents": n.disappear = parseEvents(p, fv); break;
                    case "HeightEvents": n.heights = parseEvents(p, fv); break;
                    default: p.skipChildren(); break;
                }
            }
            out.add(n);
        }
    }

    /** JSON key of a note's rotation pivot (read case-insensitively). */
    private static final String PIVOT_KEY = "pivot";

    /**
     * Reads a note's {@code pivot} into {@code n} (a later key replaces an earlier one): {@code [x, y]}
     * or {@code {"x": .., "y": ..}}; {@code null} = the origin. Anything else, a missing, extra,
     * non-numeric or non-finite coordinate: what can be read is kept (the rest 0) and
     * {@code n.pivotFixed} is set.
     */
    private static void parsePivot(JsonParser p, JsonToken v, RawNote n) throws IOException {
        n.pivotX = 0f;
        n.pivotY = 0f;
        n.pivotFixed = false;
        if (v == JsonToken.VALUE_NULL) return;
        float x = Float.NaN;
        float y = Float.NaN;
        int extra = 0;
        if (v == JsonToken.START_ARRAY) {
            int k = 0;
            JsonToken t;
            while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
                float f = floatValue(p, t, Float.NaN);
                if (k == 0) x = f;
                else if (k == 1) y = f;
                else extra++;
                k++;
            }
        } else if (v == JsonToken.START_OBJECT) {
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken fv = p.nextToken();
                if ("x".equalsIgnoreCase(name)) x = floatValue(p, fv, Float.NaN);
                else if ("y".equalsIgnoreCase(name)) y = floatValue(p, fv, Float.NaN);
                else {
                    p.skipChildren();
                    extra++;
                }
            }
        } else {
            p.skipChildren();
        }
        boolean okX = !Float.isNaN(x) && !Float.isInfinite(x);
        boolean okY = !Float.isNaN(y) && !Float.isInfinite(y);
        n.pivotX = okX ? x : 0f;
        n.pivotY = okY ? y : 0f;
        n.pivotFixed = !okX || !okY || extra > 0;
    }

    /** JSON key of a note's afterimage trail (read case-insensitively). */
    private static final String TRAIL_KEY = "trail";

    /**
     * Reads a note's {@code trail} into {@code n} (a later {@code trail} key replaces an earlier
     * one): one object, or an array of objects (segments, 2026-10-14). A value that is neither, or
     * an array element that is not an object, is ignored and counted as fixed; {@code null} means
     * none.
     */
    private static void parseTrail(JsonParser p, JsonToken v, RawNote n) throws IOException {
        n.trails = null;
        n.trailFixed = false;
        n.hasTrail = v != JsonToken.VALUE_NULL;
        if (!n.hasTrail) return;
        List<NoteTrail> out = new ArrayList<>(2);
        if (v == JsonToken.START_OBJECT) {
            out.add(parseTrailObject(p, n));
        } else if (v == JsonToken.START_ARRAY) {
            JsonToken t;
            while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
                if (t == JsonToken.START_OBJECT) {
                    out.add(parseTrailObject(p, n));
                } else {
                    p.skipChildren();
                    n.trailFixed = true;
                }
            }
        } else {
            p.skipChildren();
            n.trailFixed = true;
        }
        n.trails = out;
    }

    /** Reads one trail object (the parser is on its START_OBJECT); sets {@code n.trailFixed} when a value had to change. */
    private static NoteTrail parseTrailObject(JsonParser p, RawNote n) throws IOException {
        float length = NoteTrail.SUGGESTED_LENGTH;
        float density = NoteTrail.SUGGESTED_DENSITY;
        float alpha = NoteTrail.DEFAULT_ALPHA;
        float fade = NoteTrail.DEFAULT_FADE;
        NoteTrail.Mode mode = NoteTrail.Mode.PATH;
        float from = Float.NaN;
        float to = Float.NaN;
        boolean fixed = false;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName().trim().toLowerCase(Locale.ROOT);
            JsonToken fv = p.nextToken();
            switch (name) {
                case "length": length = floatValue(p, fv, Float.NaN); break;
                case "density": density = floatValue(p, fv, Float.NaN); break;
                case "alpha": alpha = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(alpha); break;
                case "fade": fade = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(fade); break;
                case "mode": {
                    String m = text(p, fv);
                    mode = NoteTrail.Mode.fromJson(m);
                    if (mode == null) {
                        mode = NoteTrail.Mode.PATH;
                        fixed = true;
                    }
                    break;
                }
                case "from": from = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(from) && fv != JsonToken.VALUE_NULL; break;
                case "to": to = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(to) && fv != JsonToken.VALUE_NULL; break;
                default: p.skipChildren(); break;
            }
        }
        // a bound that is not a finite number ("Infinity", 1e39 overflowing float) leaves that end open
        if (Float.isInfinite(from)) {
            from = Float.NaN;
            fixed = true;
        }
        if (Float.isInfinite(to)) {
            to = Float.NaN;
            fixed = true;
        }
        // values the sanitiser has to change (length 0 is a valid "no trail")
        if (!(length >= 0f) || length == Float.POSITIVE_INFINITY) fixed = true;
        if (!(density > 0f) || density == Float.POSITIVE_INFINITY) fixed = true;
        if (alpha < 0f || alpha > 1f) fixed = true;
        if (fade < NoteTrail.MIN_FADE || fade > NoteTrail.MAX_FADE) fixed = true;
        if (from > to) fixed = true;
        if (fixed) n.trailFixed = true;
        return NoteTrail.of(length, density, alpha, fade, mode, from, to);
    }

    /** JSON key of a note's glitch segments (read case-insensitively). */
    private static final String GLITCH_KEY = "glitch";

    /**
     * Reads a note's {@code glitch} into {@code n} (a later {@code glitch} key replaces an earlier
     * one): one object or an array of objects (segments). A value that is neither, or an array
     * element that is not an object, is ignored and counted as fixed; {@code null} means none.
     */
    private static void parseGlitch(JsonParser p, JsonToken v, RawNote n) throws IOException {
        n.glitches = null;
        n.glitchFixed = false;
        n.hasGlitch = v != JsonToken.VALUE_NULL;
        if (!n.hasGlitch) return;
        List<NoteGlitch> out = new ArrayList<>(2);
        if (v == JsonToken.START_OBJECT) {
            out.add(parseGlitchObject(p, n));
        } else if (v == JsonToken.START_ARRAY) {
            JsonToken t;
            while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
                if (t == JsonToken.START_OBJECT) {
                    out.add(parseGlitchObject(p, n));
                } else {
                    p.skipChildren();
                    n.glitchFixed = true;
                }
            }
        } else {
            p.skipChildren();
            n.glitchFixed = true;
        }
        n.glitches = out;
    }

    /**
     * Reads one glitch object (the parser is on its START_OBJECT). {@code duration} stands for
     * {@code to = from + duration} when {@code to} is absent; sets {@code n.glitchFixed} when a
     * value had to change.
     */
    private static NoteGlitch parseGlitchObject(JsonParser p, RawNote n) throws IOException {
        float from = Float.NaN;
        float to = Float.NaN;
        float duration = Float.NaN;
        float intensity = NoteGlitch.DEFAULT_INTENSITY;
        float intensityEnd = Float.NaN;
        float exponent = 1f;
        float rate = NoteGlitch.DEFAULT_RATE;
        float split = NoteGlitch.DEFAULT_SPLIT;
        float slice = NoteGlitch.DEFAULT_SLICE;
        float flicker = NoteGlitch.DEFAULT_FLICKER;
        boolean fixed = false;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName().trim().toLowerCase(Locale.ROOT);
            JsonToken fv = p.nextToken();
            boolean isNull = fv == JsonToken.VALUE_NULL;
            switch (name) {
                case "from": from = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(from) && !isNull; break;
                case "to": to = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(to) && !isNull; break;
                case "duration": duration = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(duration) && !isNull; break;
                case "intensity": intensity = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(intensity); break;
                case "intensityend": intensityEnd = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(intensityEnd) && !isNull; break;
                case "exponent": exponent = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(exponent); break;
                case "rate": rate = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(rate); break;
                case "split": split = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(split); break;
                case "slice": slice = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(slice); break;
                case "flicker": flicker = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(flicker); break;
                default: p.skipChildren(); break;
            }
        }
        if (Float.isInfinite(from)) {
            from = Float.NaN;
            fixed = true;
        }
        if (Float.isInfinite(to)) {
            to = Float.NaN;
            fixed = true;
        }
        if (Float.isNaN(to) && !Float.isNaN(duration)) {
            if (Float.isNaN(from) || Float.isInfinite(duration)) fixed = true; // a duration needs a start
            else to = from + Math.max(0f, duration);
            if (duration < 0f) fixed = true;
        }
        // values the sanitiser has to change
        if (intensity < 0f || intensity > 1f) fixed = true;
        if (intensityEnd < 0f || intensityEnd > 1f) fixed = true;
        if (exponent < NoteGlitch.MIN_EXPONENT || exponent > NoteGlitch.MAX_EXPONENT) fixed = true;
        if (rate < 0f || rate > NoteGlitch.MAX_RATE) fixed = true;
        if (split < 0f || split > NoteGlitch.MAX_WEIGHT) fixed = true;
        if (slice < 0f || slice > NoteGlitch.MAX_WEIGHT) fixed = true;
        if (flicker < 0f || flicker > NoteGlitch.MAX_WEIGHT) fixed = true;
        if (from > to) fixed = true;
        if (fixed) n.glitchFixed = true;
        return NoteGlitch.of(from, to, intensity, intensityEnd, exponent, rate, split, slice, flicker);
    }

    private static RawLine parseLine(JsonParser p, JsonToken v) throws IOException {
        if (v != JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        RawLine l = new RawLine();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            switch (name) {
                case "upperJudgementLine":
                case "upperInitial": l.upper = floatValue(p, fv, Float.NaN); break;
                case "lowerJudgementLine":
                case "lowerInitial": l.lower = floatValue(p, fv, Float.NaN); break;
                case "leftBound":
                case "initialLeftBound": l.left = floatValue(p, fv, Float.NaN); break;
                case "rightBound":
                case "initialRightBound": l.right = floatValue(p, fv, Float.NaN); break;
                case "upperEvents": l.upperEvents = parseEvents(p, fv); break;
                case "lowerEvents": l.lowerEvents = parseEvents(p, fv); break;
                case "leftEvents": l.leftEvents = parseEvents(p, fv); break;
                case "rightEvents": l.rightEvents = parseEvents(p, fv); break;
                case "laneScale": l.hasLaneScale = true; l.laneScale = floatValue(p, fv, Float.NaN); break;
                case "laneScaleEvents": l.laneScaleEvents = parseEvents(p, fv); break;
                case "cameraFrame": l.hasCamera = true; l.camera = floatValue(p, fv, Float.NaN); break;
                case "cameraFrameEvents": l.cameraEvents = parseEvents(p, fv); break;
                default: p.skipChildren(); break;
            }
        }
        return l;
    }

    /** A lane's {@code insert} object (owner 2026-10-15): the keys of an event; {@code final} may be absent. */
    private static void parseInsert(JsonParser p, JsonToken v, RawLane l) throws IOException {
        if (v != JsonToken.START_OBJECT) {
            p.skipChildren();
            return;
        }
        l.hasInsert = true;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            switch (name) {
                case "startTime": l.insStart = floatValue(p, fv, 0f); break;
                case "endTime": l.insEnd = floatValue(p, fv, Float.NaN); break;
                case "initialVal":
                case "initial": l.insInitial = floatValue(p, fv, 0f); break;
                case "finalVal":
                case "final": l.insFinal = floatValue(p, fv, Float.NaN); break;
                case "exponent": l.insExponent = floatValue(p, fv, 1f); break;
                default: p.skipChildren(); break;
            }
        }
    }

    /** A lane's {@code delete} object (owner 2026-10-17): the keys of an event, every one optional. */
    private static void parseDelete(JsonParser p, JsonToken v, RawLane l) throws IOException {
        if (v != JsonToken.START_OBJECT) {
            p.skipChildren();
            return;
        }
        l.hasDelete = true;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            switch (name) {
                case "startTime": l.delStart = floatValue(p, fv, 0f); break;
                case "endTime": l.delEnd = floatValue(p, fv, Float.NaN); break;
                case "initialVal":
                case "initial": l.delInitial = floatValue(p, fv, Float.NaN); break;
                case "finalVal":
                case "final": l.delFinal = floatValue(p, fv, 0f); break;
                case "exponent": l.delExponent = floatValue(p, fv, 1f); break;
                default: p.skipChildren(); break;
            }
        }
    }

    private static List<Events> parseEvents(JsonParser p, JsonToken v) throws IOException {
        if (v != JsonToken.START_ARRAY) {
            p.skipChildren();
            return null;
        }
        List<Events> out = new ArrayList<>();
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t != JsonToken.START_OBJECT) {
                p.skipChildren();
                continue;
            }
            Events.TYPE type = null;
            float start = 0f;
            float end = 0f;
            float initial = 0f;
            float fin = 0f;
            float exponent = 1f;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken fv = p.nextToken();
                switch (name) {
                    case "type": type = Events.TYPE.parse(text(p, fv)); break;
                    case "startTime": start = floatValue(p, fv, 0f); break;
                    case "endTime": end = floatValue(p, fv, 0f); break;
                    case "initialVal":
                    case "initial": initial = floatValue(p, fv, 0f); break;
                    case "finalVal":
                    case "final": fin = floatValue(p, fv, 0f); break;
                    case "exponent": exponent = floatValue(p, fv, 1f); break;
                    default: p.skipChildren(); break;
                }
            }
            out.add(new Events(type, start, end, initial, fin, exponent));
        }
        return out;
    }

    /**
     * @return a numeric value as float (numbers exactly as Jackson data binding reads a float
     *         field; numeric strings parsed), else {@code def} (structures are skipped)
     */
    private static float floatValue(JsonParser p, JsonToken v, float def) throws IOException {
        if (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getFloatValue();
        }
        if (v == JsonToken.VALUE_STRING) {
            try {
                return Float.parseFloat(p.getText().trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        p.skipChildren();
        return def;
    }

    /** Marks "not an int" for {@link #intValue}. */
    private static final long NO_INT = Long.MIN_VALUE;

    /**
     * @return an int value (a fractional number is truncated, as Jackson data binding does for
     *         an int field; numeric strings parsed), or {@link #NO_INT} for null, non-numeric or
     *         out-of-range values (structures are skipped)
     */
    private static long intValue(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NUMBER_INT) {
            return p.getNumberType() == JsonParser.NumberType.INT ? p.getIntValue() : NO_INT;
        }
        double d;
        if (v == JsonToken.VALUE_NUMBER_FLOAT) {
            d = p.getDoubleValue();
        } else if (v == JsonToken.VALUE_STRING) {
            try {
                d = Double.parseDouble(p.getText().trim());
            } catch (NumberFormatException e) {
                return NO_INT;
            }
        } else {
            p.skipChildren();
            return NO_INT;
        }
        return d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (long) (int) d : NO_INT;
    }

    private static String text(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NULL) return null;
        if (v != null && v.isScalarValue()) return p.getText();
        p.skipChildren();
        return null;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes {@code chart} as format v2 JSON (UTF-8, pretty-printed). The stream is flushed but
     * not closed. Reading the output back yields an equal model (every value is written
     * explicitly; floats round-trip exactly). The game never writes charts; this is for the
     * future editor and tools.
     */
    public static void write(Chart chart, OutputStream out) throws IOException {
        JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8);
        g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        write(chart, g);
    }

    /** @return {@code chart} as format v2 JSON text (see {@link #write(Chart, OutputStream)}). */
    public static String toJson(Chart chart) {
        StringWriter sw = new StringWriter(1 << 16);
        try {
            write(chart, FACTORY.createGenerator(sw));
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringWriter does not throw
        }
        return sw.toString();
    }

    private static void write(Chart chart, JsonGenerator g) throws IOException {
        g.useDefaultPrettyPrinter();
        g.writeStartObject();
        g.writeNumberField("formatVersion", FORMAT_VERSION);
        g.writeStringField("title", chart.title());
        g.writeStringField("artist", chart.artist());
        g.writeStringField("illustrator", chart.illustrator());
        g.writeStringField("charter", chart.charter());
        g.writeNumberField("bpm", chart.bpm());
        float[] changeBeats = chart.bpmListBeats(), changeBpms = chart.bpmListValues();
        if (changeBeats.length > 1) {
            g.writeArrayFieldStart("bpmList");
            for (int i = 0; i < changeBeats.length; i++) {
                g.writeStartObject();
                g.writeNumberField("beat", changeBeats[i]);
                g.writeNumberField("bpm", changeBpms[i]);
                g.writeEndObject();
            }
            g.writeEndArray();
        }
        if (chart.offsetMs() != 0f) g.writeNumberField("offsetMs", chart.offsetMs());
        String constant = chart.chartConstant();
        if (constant != null) {
            g.writeFieldName("chartConstant");
            if (JSON_NUMBER.matcher(constant).matches()) g.writeNumber(constant);
            else g.writeString(constant);
        }
        g.writeStringField("level", chart.level());
        if (!chart.unlimitedLanes()) g.writeNumberField("laneCount", chart.laneLimit());

        g.writeArrayFieldStart("lanes");
        for (Lane lane : chart.lanes()) {
            if (lane.implicit()) continue; // added by the loader for the lane mode, not authored
            g.writeStartObject();
            g.writeNumberField("id", lane.id());
            g.writeNumberField("width", lane.baseWidth());
            g.writeNumberField("opacity", lane.baseOpacity());
            writeEvents(g, "widthEvents", lane.widthEvents());
            writeEvents(g, "opacityEvents", lane.opacityEvents());
            Events ins = lane.insert();
            if (ins != null) {
                g.writeObjectFieldStart("insert");
                g.writeNumberField("startTime", ins.startTime());
                g.writeNumberField("endTime", ins.endTime());
                g.writeNumberField("initial", ins.initialValue());
                g.writeNumberField("final", ins.finalValue());
                g.writeNumberField("exponent", ins.exponent());
                g.writeEndObject();
            }
            Events del = lane.deletion();
            if (del != null) {
                g.writeObjectFieldStart("delete");
                g.writeNumberField("startTime", del.startTime());
                g.writeNumberField("endTime", del.endTime());
                g.writeNumberField("initial", del.initialValue());
                g.writeNumberField("final", del.finalValue());
                g.writeNumberField("exponent", del.exponent());
                g.writeEndObject();
            }
            g.writeEndObject();
        }
        g.writeEndArray();

        g.writeArrayFieldStart("notes");
        for (Note n : chart.notes()) {
            g.writeStartObject();
            g.writeStringField("type", n.type().json);
            g.writeStringField("line", n.line().json);
            if (n.type().inLane()) g.writeNumberField("lane", n.laneId());
            g.writeNumberField("appearance", n.appearance());
            g.writeNumberField("judgement", n.judgement());
            if (n.type() == NoteType.HOLD || n.duration() > 0f) g.writeNumberField("duration", n.duration());
            g.writeNumberField("height", n.height());
            writeEvents(g, "MovementEvents", n.movementEvents());
            writeEvents(g, "RotationEvents", n.rotationEvents());
            writeEvents(g, "DisappearEvents", n.disappearEvents());
            if (!n.heightEvents().isEmpty()) writeEvents(g, "HeightEvents", n.heightEvents());
            writeTrails(g, n.trails());
            writeGlitches(g, n.glitches());
            if (n.hasPivot()) {
                g.writeArrayFieldStart("pivot");
                g.writeNumber(n.pivotX());
                g.writeNumber(n.pivotY());
                g.writeEndArray();
            }
            g.writeEndObject();
        }
        g.writeEndArray();

        JudgementLine l = chart.lineEvents();
        g.writeObjectFieldStart("LineEvents");
        g.writeNumberField("upperJudgementLine", l.upperBase());
        g.writeNumberField("lowerJudgementLine", l.lowerBase());
        writeEvents(g, "upperEvents", l.upperEvents());
        writeEvents(g, "lowerEvents", l.lowerEvents());
        if (l.laneScaleBase() != 1f) g.writeNumberField("laneScale", l.laneScaleBase());
        if (!l.laneScaleEvents().isEmpty()) writeEvents(g, "laneScaleEvents", l.laneScaleEvents());
        if (l.cameraFrameBase() != 0f) g.writeNumberField("cameraFrame", l.cameraFrameBase());
        if (!l.cameraFrameEvents().isEmpty()) writeEvents(g, "cameraFrameEvents", l.cameraFrameEvents());
        g.writeEndObject();

        g.writeEndObject();
        g.flush();
    }

    /**
     * Writes a note's trail segments: nothing for none, one object for one segment (the form
     * before 2026-10-14), an array of objects for more.
     */
    private static void writeTrails(JsonGenerator g, List<NoteTrail> trails) throws IOException {
        if (trails.isEmpty()) return;
        g.writeFieldName(TRAIL_KEY);
        if (trails.size() == 1) {
            writeTrail(g, trails.get(0));
            return;
        }
        g.writeStartArray();
        for (NoteTrail t : trails) writeTrail(g, t);
        g.writeEndArray();
    }

    /** Writes one trail object (sanitised values; from / to only when bounded). */
    private static void writeTrail(JsonGenerator g, NoteTrail t) throws IOException {
        g.writeStartObject();
        g.writeNumberField("length", t.length());
        g.writeNumberField("density", t.density());
        g.writeNumberField("alpha", t.alpha());
        g.writeNumberField("fade", t.fade());
        g.writeStringField("mode", t.mode().json);
        if (t.hasFrom()) g.writeNumberField("from", t.from());
        if (t.hasTo()) g.writeNumberField("to", t.to());
        g.writeEndObject();
    }

    /** Writes a note's glitch segments like its trails: nothing, one object, or an array. */
    private static void writeGlitches(JsonGenerator g, List<NoteGlitch> glitches) throws IOException {
        if (glitches.isEmpty()) return;
        g.writeFieldName(GLITCH_KEY);
        if (glitches.size() == 1) {
            writeGlitch(g, glitches.get(0));
            return;
        }
        g.writeStartArray();
        for (NoteGlitch x : glitches) writeGlitch(g, x);
        g.writeEndArray();
    }

    /** Writes one glitch object (sanitised values; from / to only when bounded). */
    private static void writeGlitch(JsonGenerator g, NoteGlitch x) throws IOException {
        g.writeStartObject();
        if (x.hasFrom()) g.writeNumberField("from", x.from());
        if (x.hasTo()) g.writeNumberField("to", x.to());
        g.writeNumberField("intensity", x.intensity());
        if (x.intensityEnd() != x.intensity()) g.writeNumberField("intensityEnd", x.intensityEnd());
        if (x.exponent() != 1f) g.writeNumberField("exponent", x.exponent());
        g.writeNumberField("rate", x.rate());
        g.writeNumberField("split", x.split());
        g.writeNumberField("slice", x.slice());
        g.writeNumberField("flicker", x.flicker());
        g.writeEndObject();
    }

    private static void writeEvents(JsonGenerator g, String field, List<Events> events) throws IOException {
        g.writeArrayFieldStart(field);
        for (int i = 0; i < events.size(); i++) {
            Events e = events.get(i);
            g.writeStartObject();
            if (e.type() != null) g.writeStringField("type", e.type().name());
            g.writeNumberField("startTime", e.startTime());
            g.writeNumberField("endTime", e.endTime());
            g.writeNumberField("initial", e.initialValue());
            g.writeNumberField("final", e.finalValue());
            g.writeNumberField("exponent", e.exponent());
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    // ------------------------------------------------------------------ song folder files

    /**
     * @return the folder's music: {@code <folderName>.(ogg|mp3|wav)} if present, else the first
     *         audio file by name that is not a result or hit sound; null if none
     */
    public static FileHandle findMusic(FileHandle folder) {
        if (folder == null) return null;
        for (String ext : AUDIO_EXT) {
            FileHandle f = folder.child(folder.name() + "." + ext);
            if (f.exists()) return f;
        }
        for (FileHandle f : sortedFiles(folder)) {
            String base = f.nameWithoutExtension().toLowerCase(Locale.ROOT);
            if (hasExtension(f, AUDIO_EXT) && !base.equals("result") && !base.startsWith("hit")
                && !base.startsWith("hold_end") && !base.startsWith("mine")) {
                return f;
            }
        }
        return null;
    }

    /** @return the folder's jacket: {@code jacket.png} if present, else the first image that is not {@code bg.*}. */
    public static FileHandle findJacket(FileHandle folder) {
        if (folder == null) return null;
        for (String ext : IMAGE_EXT) {
            FileHandle f = folder.child("jacket." + ext);
            if (f.exists()) return f;
        }
        for (FileHandle f : sortedFiles(folder)) {
            if (hasExtension(f, IMAGE_EXT) && !f.nameWithoutExtension().equalsIgnoreCase("bg")) return f;
        }
        return null;
    }

    private static List<FileHandle> sortedFiles(FileHandle folder) {
        FileHandle[] list = folder.list();
        Arrays.sort(list, new Comparator<FileHandle>() {
            @Override
            public int compare(FileHandle a, FileHandle b) {
                return a.name().compareTo(b.name());
            }
        });
        List<FileHandle> out = new ArrayList<>(list.length);
        for (FileHandle f : list) {
            if (!f.isDirectory()) out.add(f);
        }
        return out;
    }

    private static FileHandle findRequired(FileHandle folder) throws IOException {
        for (FileHandle f : sortedFiles(folder)) {
            if (hasExtension(f, "json")) return f;
        }
        throw new IOException("No .json file found in " + folder.path());
    }

    private static boolean hasExtension(FileHandle file, String... extensions) {
        String actual = file.extension().toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (actual.equals(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
