package org.kelvinizer.elements;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.kelvinizer.Params;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 谱面的读取和写出：读 v5 和旧格式（v1–v4，见 {@link LegacyChartFormat}），写出一律是 v5（{@link ChartFormatV5}）；
 * 歌曲信息从同一文件夹的 {@code song.json} 读（{@link SongInfo}），修正或丢弃的内容记入加载报告。
 *
 * <p>Loads and writes charts (docs/CHART_FORMAT.md). A file with {@code "format": 5} is read by {@link ChartFormatV5},
 * with the song-wide values (title, artist, illustrator, BPM and tempo changes, offset) from the folder's
 * {@code song.json}; any other file is an old chart (v1–v4) read by {@link LegacyChartFormat}, which moves it into the
 * current frame and lines. Both fill the same raw model, which {@link #build} turns into a {@link Chart}: lanes
 * (default, declared, inserted, deleted, filled for unlimited lanes), notes (sanitised; notes sharing a line, lane and
 * beat all stay since Kelvin 2026-11-02), the judgement lines and veils. The writer always writes v5. Thread-safe;
 * {@code Main} calls it on the chart loader thread.
 *
 * <p>Parsing uses the Jackson streaming API (no reflection, so it is ProGuard-safe and fast). Unknown keys are skipped.
 */
public final class ChartMapper {
    /** Raw lane count of format v1 (0..9 lower line, 10..19 upper line); used by the converter only. */
    public static final int LANES = 20;
    /** Chart format this loader writes and reads natively: v4, the 1920 × 1200 frame with the lines at 1080 / 120. */
    public static final int FORMAT_VERSION = 5;
    /** Format v4's stretch of the line heights about y 600: the gap between the lines, 960 / 933.33. */
    private static final double V4_STRETCH = 36.0 / 35.0;
    /** Default v2 lane ids when a chart declares no {@code lanes}. */
    static final int[] DEFAULT_LANE_IDS = {-4, -3, -2, -1, 1, 2, 3, 4};
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
    static final JsonFactory FACTORY = new JsonFactory();
    static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
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
     * Parses a chart from a stream (the stream is closed). A v5 chart needs its song's values: see
     * {@link #read(InputStream, String, SongInfo)}.
     *
     * @param source name used in the load log line (may be null)
     * @throws IOException for malformed JSON, a missing / non-positive bpm, or an I/O error
     */
    public static Chart read(InputStream inputStream, String source) throws IOException {
        return read(inputStream, source, null);
    }

    /**
     * Parses a chart from a stream (the stream is closed).
     *
     * @param source name used in the load log line (may be null)
     * @param song   the song's {@code song.json} values for a v5 chart (null = none; old charts carry their own)
     * @throws IOException for malformed JSON, a missing / non-positive bpm, or an I/O error
     */
    public static Chart read(InputStream inputStream, String source, SongInfo song) throws IOException {
        try (InputStream in = inputStream) {
            return parse(in, source != null ? source : "chart", song, null);
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
     * A v5 chart takes its song's values from the {@code song.json} beside it and its level from its file name.
     * Used by {@code SongLibrary.loadChart}.
     */
    public static Chart read(FileHandle chartFile, FileHandle musicFile, FileHandle jacketFile) throws IOException {
        Chart chart;
        try (InputStream in = chartFile.read()) {
            chart = parse(in, chartFile.path(), SongInfo.besides(chartFile), chartFile.nameWithoutExtension());
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

    /**
     * @param song  the song's values for a v5 chart (null = none)
     * @param level the difficulty for a v5 chart (its file name; null = none)
     */
    private static Chart parse(InputStream in, String source, SongInfo song, String level) throws IOException {
        byte[] bytes = readAll(in);
        RawChart raw;
        if (ChartFormatV5.isV5(FACTORY, bytes)) {
            try (JsonParser p = FACTORY.createParser(bytes)) {
                raw = ChartFormatV5.parse(p, song, level);
            }
        } else {
            try (JsonParser p = FACTORY.createParser(bytes)) {
                raw = LegacyChartFormat.parse(p);
            }
        }
        try {
            return build(raw, source);
        } catch (IllegalArgumentException e) {
            throw new IOException(source + ": " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 15];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ raw model → Chart

    private static Chart build(RawChart raw, String source) throws IOException {
        Chart.LoadReport report = new Chart.LoadReport();
        boolean v1 = raw.formatVersion < 2;
        report.sourceFormat = v1 ? 1 : Math.min(raw.formatVersion, raw.readerFormat);
        if (raw.formatVersion > raw.readerFormat) {
            report.messages.add("format " + raw.formatVersion + " read as " + raw.readerFormat);
        }
        float[][] tempo = tempoMap(raw.bpmList, report);
        float bpm = raw.bpm;
        if (!(bpm > 0f) || bpm == Float.POSITIVE_INFINITY) {
            if (tempo == null) {
                throw new IOException(source + (raw.readerFormat >= 5 ? ": missing or invalid bpm in song.json"
                    : ": missing or invalid bpm"));
            }
            bpm = tempo[1][0];
        }
        float offsetMs = 0f;
        if (raw.hasOffset) {
            if (v1) {
                report.messages.add("offsetMs of a v1 chart ignored");
            } else if (Float.isNaN(raw.offsetMs) || Float.isInfinite(raw.offsetMs)) {
                report.messages.add("invalid offsetMs read as 0");
            } else {
                offsetMs = raw.offsetMs;
            }
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
                lanes.put(r.id, r.hasInsert ? null
                    : deletedLane(r, new Lane(r.id, r.width, r.opacity, r.widthEvents, r.opacityEvents)));
            }
            // Inserted lanes (Kelvin 2026-10-15) last: the final width defaults to their neighbours'.
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
            List<NoteDistortion> distortions = null;
            if (r.hasDistortion) {
                if (v1) {
                    report.distortionsIgnored++;
                } else {
                    distortions = r.distortions;
                    if (r.distortionFixed) report.distortionsFixed++;
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
            if (r.pivotFixed || r.orbitFixed) report.pivotsFixed++;
            NoteOrbit orbit = r.hasOrbit ? new NoteOrbit(r.orbitX, r.orbitY, r.orbitXEvents, r.orbitYEvents,
                r.orbitRadius, r.orbitRadiusEvents, r.orbitAngle, r.orbitAngleEvents, r.orbitConflict, r.orbitRigid)
                : null;
            notes.add(new Note(type, line, laneId, r.appearance, r.judgement, r.duration, r.height,
                movement, r.rotation, r.disappear, heights, trails, glitches, r.pivotX, r.pivotY, distortions,
                r.waveSpeed, r.offsetX, r.offsetXEvents, orbit, r.fake));
        }
        // Taps / holds sharing (line, lane id, beat) all stay since Kelvin 2026-11-02 (「现在我们要放开这个限制」): each
        // takes its own finger. Mines and TIDEs were never removed.

        RawLine rl = raw.line != null ? raw.line : new RawLine();
        JudgementLine geometry = v1 ? v1Line(rl, report) : v2Line(rl, report, raw.hasLaneCount && raw.laneCount >= 1);

        // Lane mode (Kelvin 2026-10-13): unlimited unless a v2 laneCount says finite.
        int laneLimit = 0;
        if (v1) {
            if (raw.hasLaneCount) report.messages.add("laneCount of a v1 chart ignored");
        } else if (raw.hasLaneCount) {
            if (raw.laneCount >= 1) {
                laneLimit = (int) Math.min(raw.laneCount, 2L * MAX_LANE_ID);
                if (laneLimit != raw.laneCount) {
                    report.messages.add("laneCount " + raw.laneCount + " read as " + laneLimit);
                }
            } else {
                report.messages.add("invalid laneCount read as unlimited lanes");
            }
        }
        if (laneLimit == 0) fillUnlimited(lanes, geometry); // finite: the lanes as declared share the frame

        Chart chart = new Chart(raw.title, raw.artist, raw.illustrator, raw.charter, raw.level,
            raw.chartConstant, bpm, offsetMs, new ArrayList<>(lanes.values()), geometry, notes,
            tempo != null ? tempo[0] : null, tempo != null ? tempo[1] : null, laneLimit);
        chart.sourceFormat = report.sourceFormat;
        if (raw.veils != null) chart.setVeils(veils(raw.veils, v1, false, report));
        if (raw.curtains != null) chart.setCurtains(veils(raw.curtains, v1, true, report));
        report.laneMineConflicts = chart.laneMineConflicts(Params.LANE_MINE_LEAD_MS);
        chart.report = report;
        if (!report.clean() && Gdx.app != null) {
            Gdx.app.log(TAG, source + ": " + report);
        }
        return chart;
    }

    /** @return the usable veils (or curtains) of a v2+ chart (counted with the veils in the report); none for v1. */
    private static List<Veil> veils(List<RawVeil> raw, boolean v1, boolean curtains, Chart.LoadReport report) {
        List<Veil> out = new ArrayList<>(raw.size());
        if (v1) {
            if (!raw.isEmpty()) report.messages.add(raw.size() + " veil(s) of a v1 chart ignored");
            return out;
        }
        for (RawVeil r : raw) {
            Veil v = r.dropped ? null : Veil.of(r.shape, r.radius, r.points, r.x, r.y, r.rotation, r.opacity,
                r.startTime, r.endTime, r.xEvents, r.yEvents, r.rotationEvents, r.opacityEvents, r.glitches,
                r.distortions);
            if (v != null && curtains) v = v.withBlur(r.blur, r.blurEvents);
            if (v == null || !v.isValid()) {
                report.veilsDropped++;
                continue;
            }
            if (r.fixed || r.shape == Veil.Shape.CIRCLE && r.points != null && r.points.length > 0) {
                report.veilsFixed++;
            }
            out.add(v);
        }
        return out;
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
            || (rl.leftEvents != null && !rl.leftEvents.isEmpty())
            || (rl.rightEvents != null && !rl.rightEvents.isEmpty());
        boolean framed = rl.hasLaneScale || rl.laneScaleEvents != null || rl.hasCamera || rl.cameraEvents != null;
        if (window && finite && !framed) {
            report.messages.add("window (leftBound / rightBound) read as cameraFrame / laneScale");
            return windowLine(rl, JudgementLine.DEFAULT_LEFT, JudgementLine.DEFAULT_RIGHT,
                JudgementLine.DEFAULT_RIGHT - JudgementLine.DEFAULT_LEFT, report);
        }
        if (window) {
            report.messages.add("window (leftBound / rightBound) ignored: lanes show across the screen; "
                + "use cameraFrame / laneScale");
        }
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
        return new JudgementLine(orDefault(rl.upper, JudgementLine.DEFAULT_UPPER),
            orDefault(rl.lower, JudgementLine.DEFAULT_LOWER), rl.upperEvents, rl.lowerEvents, laneScale,
            rl.laneScaleEvents, camera, rl.cameraEvents);
    }

    /** @return the v1 line geometry: its window as the lane scale and camera frame ({@link #windowLine}, 2400 wide). */
    private static JudgementLine v1Line(RawLine rl, Chart.LoadReport report) {
        if (rl.hasLaneScale || rl.laneScaleEvents != null) report.messages.add("laneScale of a v1 chart ignored");
        if (rl.hasCamera || rl.cameraEvents != null) report.messages.add("cameraFrame of a v1 chart ignored");
        return windowLine(rl, V1_DEFAULT_LEFT, V1_DEFAULT_RIGHT, V1_SPAN, report);
    }

    /**
     * An old window as the global lane scale and the camera frame (Kelvin 2026-10-13 05:29): the
     * lanes filled a window {@code span} wide at scale 1 (v1: ten 240-wide lanes, {@link #V1_SPAN}),
     * so its width over {@code span} is the scale and its centre's offset from x 960 the camera
     * frame. Each left / right event pair with the same times and exponent (every shipped song)
     * becomes one event of each, exactly; otherwise the window events are dropped with a report
     * message. A channel that never leaves its default (scale 1, camera frame 0) gets no events.
     */
    private static JudgementLine windowLine(RawLine rl, float defLeft, float defRight, float span,
                                            Chart.LoadReport report) {
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
        return new JudgementLine(orDefault(rl.upper, JudgementLine.DEFAULT_UPPER),
            orDefault(rl.lower, JudgementLine.DEFAULT_LOWER), rl.upperEvents, rl.lowerEvents, scale,
            scaled ? scales : null, camera, moved ? cameras : null);
    }

    /**
     * An inserted lane (Kelvin 2026-10-15, {@code insert}): the final width is {@code insert.final},
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
     * A deleted lane (Kelvin 2026-10-17, {@code delete}): {@code lane} with the deletion of {@code r}, or
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
        float initial = r.delInitial >= 0f && r.delInitial < Float.POSITIVE_INFINITY ? r.delInitial
            : lane.widthAt(start);
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
     * @return a format v3 line height in format v4: {@code 600 + (y − 600) · 36/35} to 0.001, so the old lines land
     *         exactly on 1080 and 120 (1066.67 → 1080, 133.33 → 120)
     */
    public static float toV4Line(float y) {
        double cy = Params.REF_H * 0.5;
        return (float) (Math.rint((cy + (y - cy) * V4_STRETCH) * 1e3) / 1e3);
    }

    // ------------------------------------------------------------------ streaming parse

    static final class RawChart {
        int formatVersion = 1;
        /** The newest format this reader knows (a legacy file claiming a later one is read by v4 rules). */
        int readerFormat = FORMAT_VERSION;
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
        List<RawVeil> veils;
        List<RawVeil> curtains;
    }

    static final class RawVeil {
        boolean dropped;
        boolean fixed;
        Veil.Shape shape;
        float radius = Float.NaN;
        float[] points;
        float x = 960f;
        float y = 600f;
        float rotation;
        float opacity = 1f;
        float startTime = Float.NaN;
        float endTime = Float.NaN;
        List<Events> xEvents;
        List<Events> yEvents;
        List<Events> rotationEvents;
        List<Events> opacityEvents;
        List<NoteGlitch> glitches;
        List<NoteDistortion> distortions;
        float blur = Float.NaN;
        List<Events> blurEvents;
    }

    static final class RawLane {
        boolean hasId;
        int id;
        boolean hasWidth;
        float width = Lane.DEFAULT_WIDTH;
        float opacity = Lane.DEFAULT_OPACITY;
        List<Events> widthEvents;
        List<Events> opacityEvents;
        // insert (Kelvin 2026-10-15)
        boolean hasInsert;
        float insStart;
        float insEnd;
        float insInitial;
        float insFinal = Float.NaN;
        float insExponent = 1f;
        // delete (Kelvin 2026-10-17)
        boolean hasDelete;
        float delStart;
        float delEnd = Float.NaN;
        float delInitial = Float.NaN;
        float delFinal;
        float delExponent = 1f;
    }

    static final class RawNote {
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
        boolean hasDistortion;
        boolean distortionFixed;
        List<NoteDistortion> distortions;
        float pivotX;
        float pivotY;
        float waveSpeed;
        float offsetX;
        List<Events> offsetXEvents;
        boolean pivotFixed;
        boolean hasOrbit;
        boolean orbitFixed;
        float orbitX;
        float orbitY;
        List<Events> orbitXEvents;
        List<Events> orbitYEvents;
        float orbitRadius = Float.NaN;
        List<Events> orbitRadiusEvents;
        float orbitAngle = Float.NaN;
        List<Events> orbitAngleEvents;
        int orbitConflict;
        boolean orbitRigid;
        boolean fake;
    }

    static final class RawLine {
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

    /** @return true for NaN or ±∞. */
    static boolean bad(float v) {
        return Float.isNaN(v) || Float.isInfinite(v);
    }

    /**
     * Reads a polygon's {@code points}: an array of {@code [x, y]} pairs or {@code {"x", "y"}} objects. Entries
     * that are not two finite numbers are left out and mark the veil as fixed.
     */
    static float[] parseVeilPoints(JsonParser p, JsonToken v, RawVeil r) throws IOException {
        if (v != JsonToken.START_ARRAY) {
            if (v != JsonToken.VALUE_NULL) r.fixed = true;
            p.skipChildren();
            return null;
        }
        float[] out = new float[8];
        int n = 0;
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            RawNote pt = new RawNote();
            parsePivot(p, t, pt);
            if (pt.pivotFixed || t == JsonToken.VALUE_NULL) {
                r.fixed = true;
                continue;
            }
            if (n + 2 > out.length) out = Arrays.copyOf(out, out.length * 2);
            out[n++] = pt.pivotX;
            out[n++] = pt.pivotY;
            r.fixed |= Math.abs(pt.pivotX) > Veil.MAX_SIZE || Math.abs(pt.pivotY) > Veil.MAX_SIZE;
        }
        if (n / 2 > Veil.MAX_POINTS) r.fixed = true;
        return Arrays.copyOf(out, n);
    }

    /** Reads {@code bpmList}: an array of {@code {"beat", "bpm"}} objects; anything else in it is skipped. */
    static List<float[]> parseBpmList(JsonParser p, JsonToken v) throws IOException {
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
            if (Float.isNaN(e[0]) || Float.isInfinite(e[0]) || !(e[1] > 0f) || e[1] == Float.POSITIVE_INFINITY) {
                continue;
            }
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

    /** JSON key of a note's rotation pivot (read case-insensitively). */
    static final String PIVOT_KEY = "pivot";

    /**
     * Reads a note's {@code pivot} into {@code n} (a later key replaces an earlier one): {@code [x, y]}
     * or {@code {"x": .., "y": ..}}; {@code null} = the origin. Anything else, a missing, extra,
     * non-numeric or non-finite coordinate: what can be read is kept (the rest 0) and
     * {@code n.pivotFixed} is set.
     */
    static void parsePivot(JsonParser p, JsonToken v, RawNote n) throws IOException {
        n.pivotX = 0f;
        n.pivotY = 0f;
        n.pivotFixed = false;
        if (v == JsonToken.VALUE_NULL) return;
        float[] xy = new float[2];
        int extra = readPoint(p, v, xy);
        boolean okX = !Float.isNaN(xy[0]) && !Float.isInfinite(xy[0]);
        boolean okY = !Float.isNaN(xy[1]) && !Float.isInfinite(xy[1]);
        n.pivotX = okX ? xy[0] : 0f;
        n.pivotY = okY ? xy[1] : 0f;
        n.pivotFixed = !okX || !okY || extra != 0;
    }

    /** JSON key of a lane note's orbit centre, 公转 (read case-insensitively). */
    static final String ORBIT_KEY = "orbit";

    /**
     * Reads a note's {@code orbit} centre into {@code n} (a later key replaces an earlier one), written like
     * {@link #parsePivot}: {@code [x, y]} or {@code {"x": .., "y": ..}} in reference units, y up from the bottom;
     * {@code null} = none. A missing, extra, non-numeric or non-finite coordinate sets {@code n.orbitFixed} (a bad
     * coordinate is the middle of the screen, {@link NoteOrbit}); a value that is neither is no orbit.
     */
    static void parseOrbit(JsonParser p, JsonToken v, RawNote n) throws IOException {
        n.hasOrbit = false;
        n.orbitFixed = false;
        if (v == JsonToken.VALUE_NULL) return;
        float[] xy = new float[2];
        int extra = readPoint(p, v, xy);
        n.hasOrbit = extra >= 0;
        n.orbitX = xy[0];
        n.orbitY = xy[1];
        n.orbitFixed = extra != 0 || Float.isNaN(xy[0]) || Float.isInfinite(xy[0]) || Float.isNaN(xy[1])
            || Float.isInfinite(xy[1]);
    }

    /** JSON values of a note's {@code orbitConflict}, by {@link NoteOrbit#conflict()} (none for unset). */
    static final String[] ORBIT_CONFLICT_NAMES = {null, "orbit", "move"};

    /** @return the {@link NoteOrbit#conflict()} an {@code orbitConflict} value names (case-insensitive; else unset) */
    static int orbitConflictOf(String s) {
        if (s == null) return NoteOrbit.CONFLICT_UNSET;
        String t = s.trim();
        if (t.equalsIgnoreCase("orbit")) return NoteOrbit.CONFLICT_ORBIT;
        if (t.equalsIgnoreCase("move")) return NoteOrbit.CONFLICT_MOVE;
        return NoteOrbit.CONFLICT_UNSET;
    }

    /**
     * Reads a point written {@code [x, y]} or {@code {"x": .., "y": ..}} into {@code xy} (NaN for a missing or
     * non-numeric coordinate).
     *
     * @return how many entries there were beyond x and y, or −1 for a value that is neither (skipped)
     */
    private static int readPoint(JsonParser p, JsonToken v, float[] xy) throws IOException {
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
            extra = -1;
        }
        xy[0] = x;
        xy[1] = y;
        return extra;
    }

    /** JSON key of a note's afterimage trail (read case-insensitively). */
    static final String TRAIL_KEY = "trail";

    /**
     * Reads a note's {@code trail} into {@code n} (a later {@code trail} key replaces an earlier
     * one): one object, or an array of objects (segments, 2026-10-14). A value that is neither, or
     * an array element that is not an object, is ignored and counted as fixed; {@code null} means
     * none.
     */
    static void parseTrail(JsonParser p, JsonToken v, RawNote n) throws IOException {
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

    /**
     * Reads one trail object (the parser is on its START_OBJECT); sets {@code n.trailFixed} when a value had to change.
     */
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
                case "from":
                    from = floatValue(p, fv, Float.NaN);
                    fixed |= Float.isNaN(from) && fv != JsonToken.VALUE_NULL;
                    break;
                case "to":
                    to = floatValue(p, fv, Float.NaN);
                    fixed |= Float.isNaN(to) && fv != JsonToken.VALUE_NULL;
                    break;
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
    static final String GLITCH_KEY = "glitch";

    /**
     * Reads a note's {@code glitch} into {@code n} (a later {@code glitch} key replaces an earlier
     * one): one object or an array of objects (segments). A value that is neither, or an array
     * element that is not an object, is ignored and counted as fixed; {@code null} means none.
     */
    static void parseGlitch(JsonParser p, JsonToken v, RawNote n) throws IOException {
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
                case "duration":
                    duration = floatValue(p, fv, Float.NaN);
                    fixed |= Float.isNaN(duration) && !isNull;
                    break;
                case "intensity": intensity = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(intensity); break;
                case "intensityend":
                    intensityEnd = floatValue(p, fv, Float.NaN);
                    fixed |= Float.isNaN(intensityEnd) && !isNull;
                    break;
                case "exponent":
                case "ease": exponent = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(exponent); break;
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

    /** JSON key of distortion segments (read case-insensitively). */
    static final String DISTORTION_KEY = "distortion";

    /**
     * Reads {@code distortion} into {@code n} like {@link #parseGlitch}: one object or an array of objects; anything
     * else is ignored and counted as fixed; {@code null} means none.
     */
    static void parseDistortion(JsonParser p, JsonToken v, RawNote n) throws IOException {
        n.distortions = null;
        n.distortionFixed = false;
        n.hasDistortion = v != JsonToken.VALUE_NULL;
        if (!n.hasDistortion) return;
        List<NoteDistortion> out = new ArrayList<>(2);
        if (v == JsonToken.START_OBJECT) {
            out.add(parseDistortionObject(p, n));
        } else if (v == JsonToken.START_ARRAY) {
            JsonToken t;
            while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
                if (t == JsonToken.START_OBJECT) {
                    out.add(parseDistortionObject(p, n));
                } else {
                    p.skipChildren();
                    n.distortionFixed = true;
                }
            }
        } else {
            p.skipChildren();
            n.distortionFixed = true;
        }
        n.distortions = out;
    }

    /** Reads one distortion object (the parser is on its START_OBJECT), as {@link #parseGlitchObject}. */
    private static NoteDistortion parseDistortionObject(JsonParser p, RawNote n) throws IOException {
        float from = Float.NaN;
        float to = Float.NaN;
        float duration = Float.NaN;
        float intensity = NoteDistortion.DEFAULT_INTENSITY;
        float intensityEnd = Float.NaN;
        float exponent = 1f;
        boolean fixed = false;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName().trim().toLowerCase(Locale.ROOT);
            JsonToken fv = p.nextToken();
            boolean isNull = fv == JsonToken.VALUE_NULL;
            switch (name) {
                case "from": from = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(from) && !isNull; break;
                case "to": to = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(to) && !isNull; break;
                case "duration":
                    duration = floatValue(p, fv, Float.NaN);
                    fixed |= Float.isNaN(duration) && !isNull;
                    break;
                case "intensity": intensity = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(intensity); break;
                case "intensityend":
                    intensityEnd = floatValue(p, fv, Float.NaN);
                    fixed |= Float.isNaN(intensityEnd) && !isNull;
                    break;
                case "exponent":
                case "ease": exponent = floatValue(p, fv, Float.NaN); fixed |= Float.isNaN(exponent); break;
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
        float max = NoteDistortion.MAX_INTENSITY;
        if (intensity < 0f || intensity > max || intensityEnd < 0f || intensityEnd > max) fixed = true;
        if (exponent < NoteGlitch.MIN_EXPONENT || exponent > NoteGlitch.MAX_EXPONENT) fixed = true;
        if (from > to) fixed = true;
        if (fixed) n.distortionFixed = true;
        return NoteDistortion.of(from, to, intensity, intensityEnd, exponent);
    }

    /**
     * @return a numeric value as float (numbers exactly as Jackson data binding reads a float
     *         field; numeric strings parsed), else {@code def} (structures are skipped)
     */
    static float floatValue(JsonParser p, JsonToken v, float def) throws IOException {
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
    static final long NO_INT = Long.MIN_VALUE;

    /**
     * @return an int value (a fractional number is truncated, as Jackson data binding does for
     *         an int field; numeric strings parsed), or {@link #NO_INT} for null, non-numeric or
     *         out-of-range values (structures are skipped)
     */
    static long intValue(JsonParser p, JsonToken v) throws IOException {
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

    /** @return a JSON flag: {@code true} or a non-zero number; anything else is false (and skipped). */
    static boolean flag(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_TRUE) return true;
        if (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) return p.getDoubleValue() != 0.0;
        if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) p.skipChildren();
        return false;
    }

    static String text(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NULL) return null;
        if (v != null && v.isScalarValue()) return p.getText();
        p.skipChildren();
        return null;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes {@code chart} as format v5 JSON (UTF-8, {@link ChartFormatV5}) with its song values in a {@code "song"}
     * object, so reading it back yields an equal model. The stream is flushed but not closed.
     */
    public static void write(Chart chart, OutputStream out) throws IOException {
        write(chart, out, true);
    }

    /**
     * Writes {@code chart} as format v5 JSON (UTF-8). The stream is flushed but not closed.
     *
     * @param withSong also write the song values; false for a chart file in its song folder, whose song values
     *                 belong in {@code song.json} ({@link SongInfo#mergeInto})
     */
    public static void write(Chart chart, OutputStream out, boolean withSong) throws IOException {
        JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8);
        g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        ChartFormatV5.write(chart, g, null, withSong);
    }

    /** @return {@code chart} as format v5 JSON text with its song values (see {@link #write(Chart, OutputStream)}) */
    public static String toJson(Chart chart) {
        return toJson(chart, true);
    }

    /** @return {@code chart} as format v5 JSON text (see {@link #write(Chart, OutputStream, boolean)}) */
    public static String toJson(Chart chart, boolean withSong) {
        return ChartFormatV5.toJson(chart, null, withSong);
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
