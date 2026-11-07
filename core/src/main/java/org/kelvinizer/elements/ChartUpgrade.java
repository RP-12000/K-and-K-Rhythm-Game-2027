package org.kelvinizer.elements;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把旧谱面（v1–v4）一次性转换成 v5 的工具：歌曲信息写进 {@code song.json}，谱面编辑器的键保留；转换前后逐项比对，一致才写入。
 *
 * <p>Converts the old charts (v1–v4) of song folders to format v5 (CHART_FORMAT §7): each chart is read as before,
 * written as v5 and read back, and the two models are compared value by value (every note, lane, line and veil); a
 * folder is written only when every chart in it matches and its charts agree on the song values, which go into
 * {@code song.json} (its other keys kept). The Charter's own keys are kept: {@code iterations}, {@code flowBpmList}
 * and {@code fallBy} move into {@code "editor"}, and {@link #EVENT_KEYS} ({@code rel}, {@code flow}, {@code link},
 * {@code grp}) stay on their events: a note's events and layers, and the judgement lines' events and layers. A desktop
 * tool: {@code ChartUpgrade [--check] <song folder>...} ({@code --check} only reports).
 *
 * <p>The chart editor's laneless working text ({@code "laneless": true}) is written as format 6. A v5 chart becomes
 * format 6 with {@link #toLaneless} (every tap, hold and mine at its x from its lane, Kelvin 2026-11-05: 「把"轨道"这个
 * 概念去掉」): {@code ChartUpgrade --laneless [--check] <song folder>...}.
 */
public final class ChartUpgrade {
    private static final String[] CHART_NAMES = {"Surface.json", "Shallow.json", "Deep.json", "Abyss.json"};
    /** Editor-only top-level keys of the old charts, moved into {@code "editor"}. */
    private static final String[] EDITOR_KEYS = {"iterations", "flowBpmList", "fallBy"};
    /**
     * The chart editor's own keys on events, kept on the written events: {@code rel} and {@code flow}, and since the
     * editor's motion redesign (2026-11-05) {@code link} (sync group) and {@code grp} (motion group).
     */
    private static final String[] EVENT_KEYS = {"rel", "flow", "link", "grp"};
    private static final String[][] NOTE_CHANNELS = {
        {"MovementEvents"}, {"RotationEvents"}, {"DisappearEvents", "OpacityEvents"}, {"HeightEvents"},
        {"OffsetXEvents", "offsetXEvents"}, {"OrbitXEvents", "orbitXEvents"}, {"OrbitYEvents", "orbitYEvents"},
        {"OrbitRadiusEvents", "orbitRadiusEvents"}, {"OrbitAngleEvents", "orbitAngleEvents"}, {"xEvents"},
        {"xLayers"}, {"positionLayers"}, {"rotationLayers"}, {"yEvents"}, {"sizeEvents"}, {"fillEvents"}};
    /** The same channels in format v5 (and 6) files. */
    private static final String[] V5_NAMES = {"positionEvents", "rotationEvents", "opacityEvents", "heightEvents",
        "offsetXEvents", "orbitXEvents", "orbitYEvents", "orbitRadiusEvents", "orbitAngleEvents", "xEvents",
        "xLayers", "positionLayers", "rotationLayers", "yEvents", "sizeEvents", "fillEvents"};
    /**
     * The line channels whose events may carry editor keys, in v2–v4 text ({@code "LineEvents"} or
     * {@code "judgementLine"}) and in v5 / v6 ({@code "lines"}), in {@link #lineChannels} order.
     */
    private static final String[] LINE_V4 = {"upperEvents", "lowerEvents", "laneScaleEvents", "cameraFrameEvents",
        "upperLayers", "lowerLayers", "cameraLayers"};
    private static final String[] LINE_V5 = {"upperEvents", "lowerEvents", "scaleEvents", "cameraEvents",
        "upperLayers", "lowerLayers", "cameraLayers"};
    private static final String[][] V5_CHANNELS = new String[V5_NAMES.length][];

    static {
        for (int i = 0; i < V5_NAMES.length; i++) V5_CHANNELS[i] = new String[] {V5_NAMES[i]};
    }
    /** The one event channel of a custom line (CHART_FORMAT §13), in every format. */
    private static final String[] STROKE_EVENTS = {"opacityEvents"};
    /** Indices of the sideways-shift and x channels (a lane chart has the first, a laneless chart the second). */
    private static final int OFFSET_X = 4, X = 9;

    private ChartUpgrade() {
    }

    /** One converted chart. */
    public static final class Result {
        public final String file;
        public final int sourceFormat;
        public final int oldBytes;
        public final String json;
        public final List<String> problems = new ArrayList<>();
        public int eventKeysKept;
        public int eventKeysDropped;
        /** {@link #toLaneless}: editor keys moved from sideways-shift events to the x events made from them. */
        public int keysMovedToX;
        SongInfo song;

        Result(String file, int sourceFormat, int oldBytes, String json) {
            this.file = file;
            this.sourceFormat = sourceFormat;
            this.oldBytes = oldBytes;
            this.json = json;
        }
    }

    public static void main(String[] args) throws IOException {
        boolean check = false;
        boolean laneless = false;
        int failed = 0;
        for (String a : args) {
            if ("--check".equals(a)) {
                check = true;
                continue;
            }
            if ("--laneless".equals(a)) {
                laneless = true;
                continue;
            }
            List<String> report = new ArrayList<>();
            boolean ok = laneless ? lanelessFolder(new File(a), !check, report) : upgradeFolder(new File(a), !check, report);
            for (String line : report) System.out.println(line);
            if (!ok) failed++;
        }
        if (failed > 0) {
            System.out.println(failed + " folder(s) not converted");
            System.exit(1);
        }
    }

    /**
     * Converts the old charts of one song folder (charts already in v5 are left alone).
     *
     * @param write  false = only check and report
     * @param report receives one line per chart and per problem
     * @return true when every chart converted exactly (and, with {@code write}, the files were written)
     */
    public static boolean upgradeFolder(File folder, boolean write, List<String> report) throws IOException {
        if (!folder.isDirectory()) {
            report.add(folder.getPath() + ": not a folder");
            return false;
        }
        List<Result> results = new ArrayList<>();
        boolean ok = true;
        for (String name : CHART_NAMES) {
            File f = new File(folder, name);
            if (!f.isFile()) continue;
            byte[] bytes = Files.readAllBytes(f.toPath());
            if (ChartFormatV5.isV5(ChartMapper.FACTORY, bytes)) {
                report.add(folder.getName() + "/" + name + ": already v5");
                continue;
            }
            Result r = upgrade(name, bytes);
            results.add(r);
            report.add(String.format(Locale.ROOT, "%s/%s: v%d %d → %d bytes (%.0f%%), editor keys on events kept %d%s",
                folder.getName(), name, r.sourceFormat, r.oldBytes, r.json.getBytes(StandardCharsets.UTF_8).length,
                100.0 * r.json.getBytes(StandardCharsets.UTF_8).length / r.oldBytes, r.eventKeysKept,
                r.eventKeysDropped > 0 ? ", left out " + r.eventKeysDropped + " on default falls" : ""));
            for (String p : r.problems) report.add("  ✗ " + p);
            ok &= r.problems.isEmpty();
        }
        if (results.isEmpty()) return ok;
        SongInfo song = results.get(0).song;
        for (Result r : results) {
            String d = differs(song, r.song);
            if (d != null) {
                report.add("  ✗ " + r.file + ": song values differ from " + results.get(0).file + " (" + d + ")");
                ok = false;
            }
        }
        if (!ok || !write) return ok;
        File songFile = new File(folder, SongInfo.FILE_NAME);
        String existing = songFile.isFile() ? new String(Files.readAllBytes(songFile.toPath()), StandardCharsets.UTF_8)
            : null;
        Files.write(songFile.toPath(), song.mergeInto(existing).getBytes(StandardCharsets.UTF_8));
        for (Result r : results) {
            Files.write(new File(folder, r.file).toPath(), r.json.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    /** Converts one old chart: reads it, writes it as v5 with its editor keys, reads that back and compares. */
    public static Result upgrade(String file, byte[] oldJson) throws IOException {
        Chart a = ChartMapper.read(new ByteArrayInputStream(oldJson), file);
        ChartFormatV5.EditorData extra = new ChartFormatV5.EditorData();
        JsonValue root = new JsonReader().parse(new String(oldJson, StandardCharsets.UTF_8));
        for (String k : EDITOR_KEYS) {
            JsonValue v = root.get(k);
            if (v != null) extra.editor.put(k, plain(v));
        }
        int[] kept = new int[3];
        List<String> problems = new ArrayList<>();
        int sourceFormat = a.sourceFormat;
        if (sourceFormat >= 2) {
            mapEventKeys(root.get("notes"), a, extra, NOTE_CHANNELS, "judgement", false, kept, problems);
            JsonValue line = root.get("LineEvents");
            mapLineKeys(line != null ? line : root.get("judgementLine"), a, extra, LINE_V4, kept, problems);
            mapStrokeKeys(root.get("strokes"), a, extra, kept, problems);
        } else if (hasEventKeys(root.get("notes"))) {
            problems.add("editor keys on the events of a v1 chart");
        }
        String json = ChartFormatV5.toJson(a, extra, false);
        Result r = new Result(file, sourceFormat, oldJson.length, json);
        r.problems.addAll(problems);
        r.song = SongInfo.of(a);
        int written = writtenKeys(json);
        r.eventKeysKept = written;
        r.eventKeysDropped = kept[0] - written;
        Chart b = ChartMapper.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), file, r.song);
        compare(a, b, r.problems);
        String ra = reportOf(a), rb = reportOf(b);
        // the old file's fixes are written into the v5 file, so it reads back clean or with the same notes
        if (!rb.isEmpty() && !ra.equals(rb)) r.problems.add("load report changed: " + ra + "  →  " + rb);
        return r;
    }

    // ------------------------------------------------------------------ lanes → laneless (format 6)

    /**
     * Converts the v5 charts of one song folder to format 6 ({@link #toLaneless}); charts already in format 6 are
     * left alone, older ones must be converted to v5 first ({@link #upgradeFolder}).
     *
     * @param write  false = only check and report
     * @param report receives one line per chart and per problem
     * @return true when every chart converted exactly (and, with {@code write}, the files were written)
     */
    public static boolean lanelessFolder(File folder, boolean write, List<String> report) throws IOException {
        if (!folder.isDirectory()) {
            report.add(folder.getPath() + ": not a folder");
            return false;
        }
        File songFile = new File(folder, SongInfo.FILE_NAME);
        SongInfo song = null;
        if (songFile.isFile()) {
            try (java.io.InputStream in = Files.newInputStream(songFile.toPath())) {
                song = SongInfo.read(in);
            }
        }
        List<Result> results = new ArrayList<>();
        boolean ok = true;
        for (String name : CHART_NAMES) {
            File f = new File(folder, name);
            if (!f.isFile()) continue;
            byte[] bytes = Files.readAllBytes(f.toPath());
            double format = ChartFormatV5.formatOf(ChartMapper.FACTORY, bytes);
            if (format >= ChartFormatV5.LANELESS) {
                report.add(folder.getName() + "/" + name + ": already laneless (format 6)");
                continue;
            }
            if (format < ChartFormatV5.FORMAT) {
                report.add(folder.getName() + "/" + name + ": format " + (format > 0 ? format : "1–4")
                    + ", convert it to v5 first");
                ok = false;
                continue;
            }
            Result r;
            try {
                r = toLaneless(name, bytes, song);
            } catch (IOException e) {
                report.add(folder.getName() + "/" + name + ": not read: " + e.getMessage());
                ok = false;
                continue;
            }
            results.add(r);
            report.add(String.format(Locale.ROOT, "%s/%s: v5 %d → v6 %d bytes, editor keys on events kept %d%s%s",
                folder.getName(), name, r.oldBytes, r.json.getBytes(StandardCharsets.UTF_8).length, r.eventKeysKept,
                r.keysMovedToX > 0 ? " (" + r.keysMovedToX + " moved from shift to x events)" : "",
                r.eventKeysDropped > 0 ? ", left out " + r.eventKeysDropped
                    + " (default falls, or shifts while lanes moved)" : ""));
            for (String p : r.problems) report.add("  ✗ " + p);
            ok &= r.problems.isEmpty();
        }
        if (!ok || !write) return ok;
        for (Result r : results) {
            Files.write(new File(folder, r.file).toPath(), r.json.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    /**
     * Converts one v5 chart to format 6 (Kelvin 2026-11-05: 「把"轨道"这个概念去掉」): each tap, hold and mine at
     * the x its lane gives it ({@link LanePlacement}: exact where the lanes stand still, 1/8-beat pieces where they
     * move), every x from the screen's centre line, no lanes. The {@code "editor"} object and the {@link #EVENT_KEYS}
     * of note and line events and layers are kept (a shift event's keys go to its x event where the lane stood still;
     * notes on one beat are matched by the nearest x).
     * The v6 text is read back and compared with the v5 chart value by value, except the lane model (lanes, lane ids,
     * sideways shifts, lane slots), which format 6 does not have.
     *
     * @param song the song's values ({@code song.json}); null = none, and a v5 chart then fails to load
     */
    public static Result toLaneless(String file, byte[] v5Json, SongInfo song) throws IOException {
        Chart a = ChartMapper.read(new ByteArrayInputStream(v5Json), file, song);
        ChartFormatV5.EditorData extra = new ChartFormatV5.EditorData();
        JsonValue root = new JsonReader().parse(new String(v5Json, StandardCharsets.UTF_8));
        JsonValue editor = root.get("editor");
        if (editor != null && editor.isObject()) {
            for (JsonValue v = editor.child; v != null; v = v.next) extra.editor.put(v.name, plain(v));
        }
        int[] kept = new int[3];
        List<String> problems = new ArrayList<>();
        if (a.laneless()) problems.add("already laneless");
        mapEventKeys(root.get("notes"), a, extra, V5_CHANNELS, "beat", true, kept, problems);
        mapLineKeys(root.get("lines"), a, extra, LINE_V5, kept, problems);
        String json = ChartFormatV5.toJson(a, extra, false, true);
        Result r = new Result(file, a.sourceFormat, v5Json.length, json);
        r.problems.addAll(problems);
        r.song = SongInfo.of(a);
        int written = writtenKeys(json);
        r.eventKeysKept = written;
        r.keysMovedToX = kept[1];
        r.eventKeysDropped = kept[0] - written + kept[2];
        Chart b = ChartMapper.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), file, song);
        if (!b.laneless()) r.problems.add("not read back as laneless");
        compare(a, b, r.problems, true);
        String ra = reportOf(a), rb = reportOf(b);
        if (!rb.isEmpty() && !ra.equals(rb)) r.problems.add("load report changed: " + ra + "  →  " + rb);
        return r;
    }

    /** @return the chart's load report without its source format */
    private static String reportOf(Chart c) {
        return c.loadReport() == null ? "" : c.loadReport().toString().replaceFirst("^v\\d+;?\\s*", "");
    }

    // ------------------------------------------------------------------ editor keys

    /**
     * Puts the editor keys ({@link #EVENT_KEYS}) of a file's note events on the model's events: notes matched by type,
     * line, lane and beat in file order (as the Charter does; in a laneless chart by type, line and beat, then the
     * nearest x, since keys on one beat are kept left to right), events by position in their sorted list.
     *
     * @param names   the channels' JSON names ({@link #NOTE_CHANNELS} for v1–v4, {@link #V5_CHANNELS})
     * @param beatKey the notes' beat key ({@code judgement} in v1–v4, {@code beat} in v5)
     * @param toX     a lane chart written laneless ({@link #toLaneless}): the keys of a sideways-shift event go to
     *                the x event made from it (where the lane stood still, one for one), others are left out
     * @param kept    [0] keys put on events, [1] of those moved from shift to x events, [2] keys left out
     */
    private static void mapEventKeys(JsonValue notes, Chart chart, ChartFormatV5.EditorData extra, String[][] names,
                                     String beatKey, boolean toX, int[] kept, List<String> problems) {
        if (notes == null) return;
        boolean laneless = chart.laneless();
        Map<String, List<Note>> byKey = new HashMap<>();
        for (Note n : chart.notes()) {
            int lane = n.type().inLane() && !laneless && !n.floats() ? n.laneId() : 0;
            byKey.computeIfAbsent(noteKey(n.type().json, n.line().json, lane, n.judgement(), n.floats()),
                k -> new ArrayList<>()).add(n);
        }
        for (JsonValue v = notes.child; v != null; v = v.next) {
            List<List<Map<String, Object>>> keys = new ArrayList<>();
            boolean any = false;
            for (String[] channel : names) {
                List<Map<String, Object>> list = eventKeys(v, channel);
                keys.add(list);
                for (Map<String, Object> m : list) any |= m != null;
            }
            NoteType nt = NoteType.fromJson(v.getString("type", ""));
            Line nl = Line.fromJson(v.getString("line", "lower"));
            String type = nt != null ? nt.json : v.getString("type", "").trim();
            String line = nl != null ? nl.json : v.getString("line", "").trim();
            // a floating note (CHART_FORMAT §11) is on the lower line with no lane in the model
            boolean floating = nt != null && nt.floats() && v.getBoolean("float", false);
            if (floating) line = Line.LOWER.json;
            List<Note> q = byKey.get(noteKey(type, line, laneless || floating ? 0 : v.getInt("lane", 0),
                v.getFloat(beatKey, Float.NaN), floating));
            Note n = take(q, laneless || floating ? v.getFloat("x", 0f) : Float.NaN);
            if (!any) continue;
            if (n == null) {
                problems.add("editor keys of a note not in the chart (" + type + " at "
                        + v.getFloat(beatKey, 0f) + ")");
                continue;
            }
            List<List<Events>> lists = channels(n);
            for (int c = 0; c < lists.size(); c++) {
                // not read for this kind of chart (a floating note's x is its own in every format)
                if (c == (chart.laneless() ? OFFSET_X : X) && !n.floats()) continue;
                List<Map<String, Object>> src = keys.get(c);
                List<Events> dst = lists.get(c);
                if (!hasKeys(src)) continue;
                if (src.size() != dst.size()) {
                    problems.add("event count of " + names[c][0] + " at " + n.judgement() + " changed");
                    continue;
                }
                List<Events> onto = c == OFFSET_X && toX ? xEventsOf(n, dst) : dst;
                for (int i = 0; i < src.size(); i++) {
                    if (src.get(i) == null) continue;
                    if (onto == null) {
                        kept[2] += src.get(i).size();
                        continue;
                    }
                    extra.eventKeys.put(onto.get(i), src.get(i));
                    kept[0] += src.get(i).size();
                    if (onto != dst) kept[1] += src.get(i).size();
                }
            }
        }
    }

    /**
     * Puts the editor keys of a file's line events on the model's line events, by position in their sorted lists.
     *
     * @param line  the file's line object (null: nothing to do)
     * @param names its channels' JSON names ({@link #LINE_V4} or {@link #LINE_V5})
     */
    private static void mapLineKeys(JsonValue line, Chart chart, ChartFormatV5.EditorData extra, String[] names,
                                    int[] kept, List<String> problems) {
        if (line == null || !line.isObject()) return;
        List<List<Events>> lists = lineChannels(chart.lineEvents());
        for (int c = 0; c < names.length; c++) {
            List<Map<String, Object>> src = eventKeys(line, new String[] {names[c]});
            if (!hasKeys(src)) continue;
            List<Events> dst = lists.get(c);
            if (src.size() != dst.size()) {
                problems.add("event count of the lines' " + names[c] + " changed");
                continue;
            }
            for (int i = 0; i < src.size(); i++) {
                if (src.get(i) == null) continue;
                extra.eventKeys.put(dst.get(i), src.get(i));
                kept[0] += src.get(i).size();
            }
        }
    }

    /**
     * Puts the editor keys of a file's custom lines' opacity events on the model's (CHART_FORMAT §13): lines in file
     * order, events by position in their sorted list; none when the chart left a line out (reported as dropped).
     */
    private static void mapStrokeKeys(JsonValue strokes, Chart chart, ChartFormatV5.EditorData extra, int[] kept,
                                      List<String> problems) {
        Stroke[] model = chart.strokes();
        if (strokes == null || !strokes.isArray() || strokes.size != model.length) return;
        int i = 0;
        for (JsonValue v = strokes.child; v != null; v = v.next, i++) {
            List<Map<String, Object>> src = eventKeys(v, STROKE_EVENTS);
            if (!hasKeys(src)) continue;
            List<Events> dst = model[i].opacityEvents();
            if (src.size() != dst.size()) {
                problems.add("event count of custom line " + (i + 1) + " changed");
                continue;
            }
            for (int k = 0; k < src.size(); k++) {
                if (src.get(k) == null) continue;
                extra.eventKeys.put(dst.get(k), src.get(k));
                kept[0] += src.get(k).size();
            }
        }
    }

    /** @return the line event lists in {@link #LINE_V5} order */
    private static List<List<Events>> lineChannels(JudgementLine l) {
        return Arrays.asList(l.upperEvents(), l.lowerEvents(), l.laneScaleEvents(), l.cameraFrameEvents(),
            l.upperLayers(), l.lowerLayers(), l.cameraLayers());
    }

    /** @return the editor keys on the events of a written v5 / v6 text, notes and lines */
    private static int writtenKeys(String json) {
        JsonValue root = new JsonReader().parse(json);
        int n = countEventKeys(root.get("notes"), V5_NAMES) + countEventKeys(root.get("strokes"), STROKE_EVENTS);
        JsonValue lines = root.get("lines");
        if (lines != null && lines.isObject()) {
            for (String c : LINE_V5) {
                JsonValue arr = lines.get(c);
                if (arr == null) continue;
                for (JsonValue e = arr.child; e != null; e = e.next) {
                    for (String k : EVENT_KEYS) {
                        if (e.get(k) != null) n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * @return the next note of {@code q} (removed): the first, or with an {@code x} the one nearest it (the first of
     *         equals); null for none
     */
    private static Note take(List<Note> q, float x) {
        if (q == null || q.isEmpty()) return null;
        int best = 0;
        if (!Float.isNaN(x)) {
            for (int i = 1; i < q.size(); i++) {
                if (Math.abs(xOf(q.get(i)) - x) < Math.abs(xOf(q.get(best)) - x)) best = i;
            }
        }
        return q.remove(best);
    }

    /** @return the x a file gives a note: a floating note's own (CHART_FORMAT §11), else the note's */
    private static float xOf(Note n) {
        return n.floats() ? n.floating().x() : n.x();
    }

    private static boolean hasKeys(List<Map<String, Object>> keys) {
        for (Map<String, Object> m : keys) if (m != null) return true;
        return false;
    }

    /**
     * @return the x events {@link LanePlacement} made one for one from a lane note's sideways-shift events (its lane
     *         stood still: same beats and ease), or null when they are not one for one
     */
    private static List<Events> xEventsOf(Note n, List<Events> shift) {
        List<Events> xs = n.xEvents();
        if (xs.size() != shift.size()) return null;
        for (int i = 0; i < xs.size(); i++) {
            Events a = shift.get(i), b = xs.get(i);
            if (a.startTime() != b.startTime() || a.endTime() != b.endTime() || a.exponent() != b.exponent()) return null;
        }
        return xs;
    }

    /** @return a note's event lists in {@link #NOTE_CHANNELS} order */
    private static List<List<Events>> channels(Note n) {
        NoteOrbit orbit = n.orbit();
        Floating f = n.floating();
        List<Events> none = Collections.emptyList();
        return Arrays.asList(n.movementEvents(), n.rotationEvents(), n.disappearEvents(), n.heightEvents(),
            n.offsetXEvents(), orbit != null ? orbit.xEvents() : none, orbit != null ? orbit.yEvents() : none,
            orbit != null ? orbit.radiusEvents() : none, orbit != null ? orbit.angleEvents() : none,
            f != null ? f.xEvents() : n.xEvents(), n.xLayers(), n.positionLayers(), n.rotationLayers(),
            f != null ? f.yEvents() : none, f != null ? f.sizeEvents() : none, f != null ? f.fillEvents() : none);
    }

    /** @return the editor keys of a note's events in one channel, in start order like {@code Events.sortedCopy} */
    private static List<Map<String, Object>> eventKeys(JsonValue note, String[] channel) {
        JsonValue arr = null;
        for (String c : channel) {
            if (arr == null) arr = note.get(c);
        }
        if (arr == null || !arr.isArray()) return Collections.emptyList();
        List<float[]> starts = new ArrayList<>();
        List<Map<String, Object>> keys = new ArrayList<>();
        for (JsonValue e = arr.child; e != null; e = e.next) {
            if (!e.isObject()) continue;
            Map<String, Object> m = null;
            for (String k : EVENT_KEYS) {
                JsonValue x = e.get(k);
                if (x == null) continue;
                if (m == null) m = new LinkedHashMap<>();
                m.put(k, plain(x));
            }
            starts.add(new float[]{startOf(e), keys.size()});
            keys.add(m);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> starts.get(i)[0])); // stable, like the model's sort
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
        for (int i : order) out.add(keys.get(i));
        return out;
    }

    /** @return an event's start beat: v1–v4 {@code startTime}, v5 {@code beats[0]} (0 when left out) */
    private static float startOf(JsonValue e) {
        JsonValue beats = e.get("beats");
        if (beats != null) return beats.isArray() && beats.size > 0 && beats.child.isNumber() ? beats.child.asFloat() : 0f;
        return e.getFloat("startTime", 0f);
    }

    private static boolean hasEventKeys(JsonValue notes) {
        return notes != null && countEventKeys(notes, "MovementEvents", "RotationEvents", "DisappearEvents",
            "OpacityEvents", "HeightEvents", "OffsetXEvents", "offsetXEvents", "OrbitXEvents", "orbitXEvents",
            "OrbitYEvents", "orbitYEvents", "OrbitRadiusEvents", "orbitRadiusEvents", "OrbitAngleEvents",
            "orbitAngleEvents") > 0;
    }

    private static int countEventKeys(JsonValue notes, String... channels) {
        int n = 0;
        if (notes == null) return 0;
        for (JsonValue v = notes.child; v != null; v = v.next) {
            for (String c : channels) {
                JsonValue arr = v.get(c);
                if (arr == null) continue;
                for (JsonValue e = arr.child; e != null; e = e.next) {
                    for (String k : EVENT_KEYS) {
                        if (e.get(k) != null) n++;
                    }
                }
            }
        }
        return n;
    }

    private static String noteKey(String type, String line, int lane, float judgement, boolean floating) {
        return type + '|' + line + '|' + lane + '|' + Math.round(judgement * 10000.0) + (floating ? "|float" : "");
    }

    /** @return a JSON value as maps, lists, strings, numbers and booleans */
    private static Object plain(JsonValue v) {
        switch (v.type()) {
            case object: {
                Map<String, Object> m = new LinkedHashMap<>();
                for (JsonValue c = v.child; c != null; c = c.next) m.put(c.name, plain(c));
                return m;
            }
            case array: {
                List<Object> l = new ArrayList<>();
                for (JsonValue c = v.child; c != null; c = c.next) l.add(plain(c));
                return l;
            }
            case stringValue: return v.asString();
            case doubleValue: return v.asDouble();
            case longValue: return v.asLong();
            case booleanValue: return v.asBoolean();
            default: return null;
        }
    }

    // ------------------------------------------------------------------ comparison

    /** @return what differs between two charts' song values, or null */
    private static String differs(SongInfo a, SongInfo b) {
        if (!eq(a.title, b.title)) return "title";
        if (!eq(a.artist, b.artist)) return "artist";
        if (!eq(a.illustrator, b.illustrator)) return "illustrator";
        if (a.bpm != b.bpm) return "bpm";
        if (a.offsetMs != b.offsetMs) return "offsetMs";
        if ((a.bpmList == null) != (b.bpmList == null)) return "bpmList";
        if (a.bpmList != null) {
            if (a.bpmList.size() != b.bpmList.size()) return "bpmList";
            for (int i = 0; i < a.bpmList.size(); i++) {
                if (!Arrays.equals(a.bpmList.get(i), b.bpmList.get(i))) return "bpmList";
            }
        }
        return null;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Compares every value of two charts (as the game sees them), adding one line per difference to {@code out}. */
    static void compare(Chart a, Chart b, List<String> out) {
        compare(a, b, out, false);
    }

    /** @param laneless leave out the lane model ({@link #LANE_MODEL}), for a lane chart against its format 6 copy */
    static void compare(Chart a, Chart b, List<String> out, boolean laneless) {
        List<String> da = new ArrayList<>(), db = new ArrayList<>();
        dump("chart", a, da, 0, laneless);
        dump("chart", b, db, 0, laneless);
        int shown = 0;
        int n = Math.max(da.size(), db.size());
        for (int i = 0; i < n && shown < 20; i++) {
            String x = i < da.size() ? da.get(i) : "(none)", y = i < db.size() ? db.get(i) : "(none)";
            if (!x.equals(y)) {
                out.add("differs: " + x + "  ≠  " + y);
                shown++;
            }
        }
        if (da.size() != db.size()) out.add("value count " + da.size() + " ≠ " + db.size());
    }

    /** Methods left out of the comparison: file handles, load bookkeeping, and movement (compared as motion). */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("getClass", "hashCode", "toString",
        "chartFile", "musicFile", "jacketFile", "loadReport", "sourceFormat", "level", "appearance", "movementEvents",
        "usesDefaultApproach", "type", "typeName", "trail", "issues"));

    /**
     * The lane model, which format 6 does not have: the chart's lanes, a note's lane, sideways shift and slot in its
     * lane (the game judges and draws by x; a TIDE's slot is its group's and is compared).
     */
    private static final Set<String> LANE_MODEL = new HashSet<>(Arrays.asList("lanes", "laneCount", "laneLimit",
        "unlimitedLanes", "laneless", "laneId", "laneIndex", "offsetX", "offsetXEvents"));
    private static final Set<String> LANE_SLOTS = new HashSet<>(Arrays.asList("slot", "groupStart"));

    private static void dump(String path, Object o, List<String> out, int depth, boolean laneless) {
        if (depth > 8) throw new IllegalStateException("too deep: " + path);
        if (o == null || o instanceof String || o instanceof Boolean || o instanceof Integer || o instanceof Long
            || o instanceof Enum) {
            out.add(path + " = " + o);
        } else if (o instanceof Float || o instanceof Double) {
            double d = ((Number) o).doubleValue();
            out.add(path + " = "
                    + (d == 0 ? "0" : o instanceof Float ? Float.toString((Float) o) : Double.toString(d)));
        } else if (o instanceof List) {
            List<?> l = (List<?>) o;
            out.add(path + ".size = " + l.size());
            for (int i = 0; i < l.size(); i++) dump(path + "[" + i + "]", l.get(i), out, depth + 1, laneless);
        } else if (o.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(o);
            out.add(path + ".length = " + len);
            for (int i = 0; i < len; i++) {
                dump(path + "[" + i + "]", java.lang.reflect.Array.get(o, i), out, depth + 1, laneless);
            }
        } else if (o.getClass().getPackage() == ChartUpgrade.class.getPackage()) {
            List<Method> ms = new ArrayList<>();
            boolean laneNote = laneless && o instanceof Note && ((Note) o).type().inLane();
            for (Method m : o.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() != void.class && !SKIP.contains(m.getName())
                    && !(laneless && LANE_MODEL.contains(m.getName()))
                    && !(laneNote && LANE_SLOTS.contains(m.getName()))) {
                    ms.add(m);
                }
            }
            ms.sort(Comparator.comparing(Method::getName));
            for (Method m : ms) {
                try {
                    dump(path + "." + m.getName(), m.invoke(o), out, depth + 1, laneless);
                } catch (ReflectiveOperationException e) {
                    out.add(path + "." + m.getName() + " threw " + e.getCause());
                }
            }
            if (o instanceof Note) {
                Note n = (Note) o;
                List<Events> motion = n.usesDefaultApproach()
                    ? Collections.singletonList(Note.defaultApproachEvent(n.judgement())) : n.movementEvents();
                dump(path + ".motion", motion, out, depth + 1, laneless);
                if (n.movementEvents().isEmpty() && !n.usesDefaultApproach()) {
                    out.add(path + ".appearance = " + n.appearance());
                }
            }
        } else {
            out.add(path + " = " + o);
        }
    }
}
