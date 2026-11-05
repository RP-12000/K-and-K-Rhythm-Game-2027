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
import java.util.ArrayDeque;
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
 * and {@code fallBy} move into {@code "editor"}, and {@code rel} / {@code flow} stay on their note events. A desktop
 * tool: {@code ChartUpgrade [--check] <song folder>...} ({@code --check} only reports).
 */
public final class ChartUpgrade {
    private static final String[] CHART_NAMES = {"Surface.json", "Shallow.json", "Deep.json", "Abyss.json"};
    /** Editor-only top-level keys of the old charts, moved into {@code "editor"}. */
    private static final String[] EDITOR_KEYS = {"iterations", "flowBpmList", "fallBy"};
    /** Editor-only keys of old note events, kept on the v5 events. */
    private static final String[] EVENT_KEYS = {"rel", "flow"};
    private static final String[][] NOTE_CHANNELS = {
        {"MovementEvents"}, {"RotationEvents"}, {"DisappearEvents", "OpacityEvents"}, {"HeightEvents"},
        {"OffsetXEvents", "offsetXEvents"}, {"OrbitXEvents", "orbitXEvents"}, {"OrbitYEvents", "orbitYEvents"},
        {"OrbitRadiusEvents", "orbitRadiusEvents"}, {"OrbitAngleEvents", "orbitAngleEvents"}};

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
        int failed = 0;
        for (String a : args) {
            if ("--check".equals(a)) {
                check = true;
                continue;
            }
            List<String> report = new ArrayList<>();
            boolean ok = upgradeFolder(new File(a), !check, report);
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
        int[] kept = new int[1];
        List<String> problems = new ArrayList<>();
        int sourceFormat = a.sourceFormat;
        if (sourceFormat >= 2) {
            mapEventKeys(root.get("notes"), a, extra, kept, problems);
        } else if (hasEventKeys(root.get("notes"))) {
            problems.add("editor keys on the events of a v1 chart");
        }
        String json = ChartFormatV5.toJson(a, extra, false);
        Result r = new Result(file, sourceFormat, oldJson.length, json);
        r.problems.addAll(problems);
        r.song = SongInfo.of(a);
        int written = countEventKeys(new JsonReader().parse(json).get("notes"), "positionEvents", "rotationEvents",
            "opacityEvents", "heightEvents", "offsetXEvents", "orbitXEvents", "orbitYEvents", "orbitRadiusEvents",
            "orbitAngleEvents");
        r.eventKeysKept = written;
        r.eventKeysDropped = kept[0] - written;
        Chart b = ChartMapper.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), file, r.song);
        compare(a, b, r.problems);
        String ra = reportOf(a), rb = reportOf(b);
        // the old file's fixes are written into the v5 file, so it reads back clean or with the same notes
        if (!rb.isEmpty() && !ra.equals(rb)) r.problems.add("load report changed: " + ra + "  →  " + rb);
        return r;
    }

    /** @return the chart's load report without its source format */
    private static String reportOf(Chart c) {
        return c.loadReport() == null ? "" : c.loadReport().toString().replaceFirst("^v\\d+;?\\s*", "");
    }

    // ------------------------------------------------------------------ editor keys

    /**
     * Puts the {@code rel} / {@code flow} keys of the old note events on the model's events: notes matched by type,
     * line, lane and judgement in file order (as the Charter does), events by position in their sorted list.
     */
    private static void mapEventKeys(JsonValue notes, Chart chart, ChartFormatV5.EditorData extra, int[] kept,
                                     List<String> problems) {
        if (notes == null) return;
        Map<String, ArrayDeque<Note>> byKey = new HashMap<>();
        for (Note n : chart.notes()) {
            byKey.computeIfAbsent(noteKey(n.type().json, n.line().json, n.type().inLane() ? n.laneId() : 0,
                n.judgement()), k -> new ArrayDeque<>()).add(n);
        }
        for (JsonValue v = notes.child; v != null; v = v.next) {
            List<List<Map<String, Object>>> keys = new ArrayList<>();
            boolean any = false;
            for (String[] channel : NOTE_CHANNELS) {
                List<Map<String, Object>> list = eventKeys(v, channel);
                keys.add(list);
                for (Map<String, Object> m : list) any |= m != null;
            }
            String type = v.getString("type", "").trim().toLowerCase(Locale.ROOT);
            String line = v.getString("line", "lower").trim().toLowerCase(Locale.ROOT);
            ArrayDeque<Note> q = byKey.get(
                    noteKey(type, line, v.getInt("lane", 0), v.getFloat("judgement", Float.NaN)));
            Note n = q != null ? q.poll() : null;
            if (!any) continue;
            if (n == null) {
                problems.add("editor keys of a note not in the chart (" + type + " at "
                        + v.getFloat("judgement", 0f) + ")");
                continue;
            }
            NoteOrbit orbit = n.orbit();
            List<Events> none = Collections.emptyList();
            List<List<Events>> lists = Arrays.asList(n.movementEvents(), n.rotationEvents(), n.disappearEvents(),
                n.heightEvents(), n.offsetXEvents(), orbit != null ? orbit.xEvents() : none,
                orbit != null ? orbit.yEvents() : none, orbit != null ? orbit.radiusEvents() : none,
                orbit != null ? orbit.angleEvents() : none);
            for (int c = 0; c < lists.size(); c++) {
                List<Map<String, Object>> src = keys.get(c);
                List<Events> dst = lists.get(c);
                if (src.size() != dst.size()) {
                    problems.add("event count of " + NOTE_CHANNELS[c][0] + " at " + n.judgement() + " changed");
                    continue;
                }
                for (int i = 0; i < src.size(); i++) {
                    if (src.get(i) == null) continue;
                    extra.eventKeys.put(dst.get(i), src.get(i));
                    kept[0] += src.get(i).size();
                }
            }
        }
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
            starts.add(new float[]{e.getFloat("startTime", 0f), keys.size()});
            keys.add(m);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> starts.get(i)[0])); // stable, like the model's sort
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
        for (int i : order) out.add(keys.get(i));
        return out;
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

    private static String noteKey(String type, String line, int lane, float judgement) {
        return type + '|' + line + '|' + lane + '|' + Math.round(judgement * 10000.0);
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
        List<String> da = new ArrayList<>(), db = new ArrayList<>();
        dump("chart", a, da, 0);
        dump("chart", b, db, 0);
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
        "usesDefaultApproach", "type", "typeName", "trail"));

    private static void dump(String path, Object o, List<String> out, int depth) {
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
            for (int i = 0; i < l.size(); i++) dump(path + "[" + i + "]", l.get(i), out, depth + 1);
        } else if (o.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(o);
            out.add(path + ".length = " + len);
            for (int i = 0; i < len; i++) dump(path + "[" + i + "]", java.lang.reflect.Array.get(o, i), out, depth + 1);
        } else if (o.getClass().getPackage() == ChartUpgrade.class.getPackage()) {
            List<Method> ms = new ArrayList<>();
            for (Method m : o.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() != void.class && !SKIP.contains(m.getName())) {
                    ms.add(m);
                }
            }
            ms.sort(Comparator.comparing(Method::getName));
            for (Method m : ms) {
                try {
                    dump(path + "." + m.getName(), m.invoke(o), out, depth + 1);
                } catch (ReflectiveOperationException e) {
                    out.add(path + "." + m.getName() + " threw " + e.getCause());
                }
            }
            if (o instanceof Note) {
                Note n = (Note) o;
                List<Events> motion = n.usesDefaultApproach()
                    ? Collections.singletonList(Note.defaultApproachEvent(n.judgement())) : n.movementEvents();
                dump(path + ".motion", motion, out, depth + 1);
                if (n.movementEvents().isEmpty() && !n.usesDefaultApproach()) {
                    out.add(path + ".appearance = " + n.appearance());
                }
            }
        } else {
            out.add(path + " = " + o);
        }
    }
}
