package org.kelvinizer.elements;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.PrettyPrinter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 谱面格式 v7（简单函数）的读写：每个函数写成一个数（常数）或一串简单函数段，默认值不写；一个轨道、一个键一行。
 *
 * <p>Reads and writes chart format v7 (Kelvin 2026-11-09 18:44–18:45; model {@link Chart7}):
 * <pre>
 * {"format": "2.1", "charter": "…", "constant": 12.5,
 *  "groups":  [{"id": 1, "name": "…", "p": 2, "P": {"x","y","z"}, "R": {"x","y","z"}, "K", "o"}],
 *  "lanes":   [{"id": 1, "g": 1, "u": {"x","y","z"}, "n": {"x","y","z"}, "v": {"x","y","z"}, "a": {"w","h"},
 *               "S", "s", "F", "f", "o", "l", "m"}],
 *  "keys":    [{"type": "tap", "lane": 1, "face": "sky", "fake": true, "h": […], "o": …,
 *               "trails": […], "glitches": […], "distortions": […]},
 *              {"type": "tide", "lanes": [1, 2], "h": […], "H": […]}],
 *  "volumes": [{"kind": "veil" | "curtain", "shape": "box" | "ellipsoid", "V": {"w","h","d"}, "p": {"x","y","z"},
 *               "o": {"x","y","z"}, "b": …, "distortions": […], "glitches": […]}],
 *  "camera":  {"P": {"x","y","z"}, "fov": …},
 *  "editor":  {…}}
 * </pre>
 * A function is a number (a constant) or a list of segments {@code {"ti", "tf", "xi", "xf", "e"}}; a key's
 * {@code trails}, {@code glitches} and {@code distortions} and a volume's {@code distortions} and {@code glitches} are
 * read and written as in format v5 ({@link ChartFormatV5}), left out when there are none. The writer leaves
 * out a function at its default constant with no segments ({@code e} when it is 1, {@code face} for the ground,
 * {@code fake} when false, {@code H} for the kinds without a height, {@code volumes} and {@code camera} when there are
 * none or it is at its default; {@code groups}, a group's {@code name} and {@code p} and a lane's {@code g} when there
 * are none), but always writes a lane's {@code v.x} and a key's {@code h}. The reader keeps file
 * order, sorts each function's segments by {@code ti} and drops a key with no {@code h} segments, of an unknown type,
 * or in a lane id the chart does not have (a wave or tide keeps the lanes that exist), and a wave or tide mine is never
 * fake; a lane without an id, or with one used before, gets a new id, and so does a group (never 0); a group in a
 * group the chart does not have, or in a ring of groups, and a lane in a group the chart does not have are in none
 * ({@link Group7}). Keys and objects not listed here are skipped, so they are lost on the next write.
 */
public final class ChartFormatV7 {
    /** The first format this reads ({@code "format"} 7 or more, chart version 2.0). */
    public static final int FORMAT = 7;
    /**
     * The format this writes: 8, chart version 2.1 (Kelvin 2026-11-10 06:45 「谱面版本为2.0，以后谱面版本更改都是0.1 0.1的改」;
     * 2.0 is format 7), which adds a lane's {@code n} (7.234), {@code m} (Kelvin 2026-11-10 23:01) and {@code l}
     * (2026-11-11 02:12), and groups ({@link Group7}, 7.242). It is written as its chart version, {@code "format":
     * "2.1"} ({@link #version}; Kelvin 2026-11-11 04:14–04:19, 7.239), the editor's version too, the two always the
     * same (Kelvin 2026-11-12 06:03 「制谱器版本号，format都放在2.1」「这两个要始终同步的」). Files of 7.234–7.238 say 8 or 9
     * and files of 7.242 "2.2", all read as 2.1.
     */
    public static final int WRITE_FORMAT = 8;
    /** The chart version this writes, {@link #version} of {@link #WRITE_FORMAT}: "2.1" (the editor shows it). */
    public static final String VERSION = version(WRITE_FORMAT);

    private static final JsonFactory FACTORY = new JsonFactory();
    /** A chart version as a file writes it ({@link #version}). */
    private static final Pattern VERSION_TEXT = Pattern.compile("([0-9]{1,3})\\.([0-9])");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    /** Marks a missing or unreadable id. */
    private static final int NO_ID = Integer.MIN_VALUE;
    private static final String[] XYZ = {"x", "y", "z"};
    private static final String[] WH = {"w", "h"};
    private static final String[] WHD = {"w", "h", "d"};

    private ChartFormatV7() {
    }

    /** @return true when the JSON object has a top-level {@code "format"} of 7 or more (false for unreadable JSON) */
    public static boolean isV7(byte[] json) {
        return formatOf(json) >= FORMAT;
    }

    /**
     * @return the top-level {@code "format"} of a chart file as {@link #format} reads it (the editor compares it with
     *         {@link #WRITE_FORMAT}); NaN for none or unreadable JSON
     */
    public static float formatOf(byte[] json) {
        if (json == null) return Float.NaN;
        try (JsonParser p = FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return Float.NaN;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken v = p.nextToken();
                if ("format".equals(name)) return format(p, v);
                p.skipChildren();
            }
        } catch (IOException e) {
            return Float.NaN;
        }
        return Float.NaN;
    }

    /**
     * @return the chart version format {@code f} is written as (Kelvin 2026-11-11 04:14 「文件里的format也改成2.0」):
     *         format {@code 7 + 10·(a − 2) + b} is version {@code "a.b"}, so 7 is "2.0", 8 "2.1" and 17 "3.0"; null
     *         below format 7
     */
    public static String version(int f) {
        return f < FORMAT ? null : (2 + (f - FORMAT) / 10) + "." + (f - FORMAT) % 10;
    }

    /**
     * @return the format a {@code "format"} value names: a chart version {@code "a.b"} with a at least 2 as its format
     *         ({@link #version}; "2.2", which 7.242 wrote for groups, is 2.1, Kelvin 2026-11-12 06:03); otherwise as a
     *         number (files before 7.239 wrote 1–9; 9 is 8, chart version 2.1, Kelvin 2026-11-11 04:19), NaN for
     *         anything else
     */
    public static float format(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_STRING) {
            Matcher m = VERSION_TEXT.matcher(p.getText().trim());
            int a = m.matches() ? Integer.parseInt(m.group(1)) : 0;
            if (a == 2 && Integer.parseInt(m.group(2)) == 2) return 8f;
            if (a >= 2) return FORMAT + 10 * (a - 2) + Integer.parseInt(m.group(2));
        }
        float f = number(p, v);
        return f == 9f ? 8f : f;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads a v7 chart. The stream is not closed.
     *
     * @throws IOException for JSON that cannot be read or is not a chart of format 7 or more
     */
    public static Chart7 read(InputStream in) throws IOException {
        try (JsonParser p = FACTORY.createParser(in)) {
            p.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("chart is not a JSON object");
            Chart7 c = new Chart7();
            List<Key7> keys = new ArrayList<>();
            float format = Float.NaN;
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken v = p.nextToken();
                switch (name) {
                    case "format": format = format(p, v); break;
                    case "charter": c.charter = text(p, v); break;
                    case "constant": c.constant = text(p, v); break;
                    case "groups":
                        if (v != JsonToken.START_ARRAY) p.skipChildren();
                        else while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                            if (t == JsonToken.START_OBJECT) c.groups.add(readGroup7(p));
                            else p.skipChildren();
                        }
                        break;
                    case "lanes":
                        if (v != JsonToken.START_ARRAY) p.skipChildren();
                        else while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                            if (t == JsonToken.START_OBJECT) c.lanes.add(readLane(p));
                            else p.skipChildren();
                        }
                        break;
                    case "keys":
                        if (v != JsonToken.START_ARRAY) p.skipChildren();
                        else while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                            if (t == JsonToken.START_OBJECT) keys.add(readKey(p));
                            else p.skipChildren();
                        }
                        break;
                    case "volumes":
                        if (v != JsonToken.START_ARRAY) p.skipChildren();
                        else while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                            if (t == JsonToken.START_OBJECT) c.volumes.add(readVolume(p));
                            else p.skipChildren();
                        }
                        break;
                    case "camera":
                        if (v != JsonToken.START_OBJECT) p.skipChildren();
                        else while (p.nextToken() == JsonToken.FIELD_NAME) {
                            String field = p.currentName();
                            JsonToken fv = p.nextToken();
                            if ("P".equals(field)) readGroup(p, fv, XYZ, c.camera.x, c.camera.y, c.camera.z);
                            else if ("fov".equals(field)) readFn(p, fv, c.fov);
                            else p.skipChildren();
                        }
                        break;
                    case "editor": {
                        Object o = readValue(p, v);
                        if (o instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) o;
                            c.editor = m;
                        }
                        break;
                    }
                    default: p.skipChildren(); break;
                }
            }
            if (t != JsonToken.END_OBJECT) throw new IOException("truncated chart JSON");
            if (!(format >= FORMAT)) throw new IOException("not a chart of format " + FORMAT);
            fixLaneIds(c.lanes);
            fixGroups(c);
            Set<Integer> ids = new HashSet<>();
            for (Lane7 l : c.lanes) ids.add(l.id);
            for (Key7 k : keys) {
                if (keep(k, ids)) c.keys.add(k);
            }
            return c;
        }
    }

    private static Lane7 readLane(JsonParser p) throws IOException {
        Lane7 l = new Lane7(NO_ID, 0f);
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "id": l.id = intValue(p, v); break;
                case "g": l.g = intValue(p, v); break;
                case "u": readGroup(p, v, XYZ, l.u.x, l.u.y, l.u.z); break;
                case "n": readGroup(p, v, XYZ, l.n.x, l.n.y, l.n.z); break;
                case "v": readGroup(p, v, XYZ, l.v.x, l.v.y, l.v.z); break;
                case "a": readGroup(p, v, WH, l.w, l.h); break;
                case "S": readFn(p, v, l.S); break;
                case "s": readFn(p, v, l.s); break;
                case "F": readFn(p, v, l.F); break;
                case "f": readFn(p, v, l.f); break;
                case "o": readFn(p, v, l.o); break;
                case "l": readFn(p, v, l.l); break;
                case "m": readFn(p, v, l.m); break;
                default: p.skipChildren(); break;
            }
        }
        return l;
    }

    private static Group7 readGroup7(JsonParser p) throws IOException {
        Group7 g = new Group7(NO_ID);
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "id": g.id = intValue(p, v); break;
                case "name": g.name = text(p, v); break;
                case "p": g.p = intValue(p, v); break;
                case "P": readGroup(p, v, XYZ, g.P.x, g.P.y, g.P.z); break;
                case "R": readGroup(p, v, XYZ, g.R.x, g.R.y, g.R.z); break;
                case "K": readFn(p, v, g.K); break;
                case "o": readFn(p, v, g.o); break;
                default: p.skipChildren(); break;
            }
        }
        return g;
    }

    /**
     * Gives a group without an id, with id 0 or with an id an earlier group has the next unused id (lanes keep the
     * first); puts a group in a group the chart does not have, or in a ring of groups, and a lane in a group the chart
     * does not have, in none.
     */
    private static void fixGroups(Chart7 c) {
        Map<Integer, Group7> byId = new HashMap<>();
        int next = 1;
        for (Group7 g : c.groups) {
            if (g.id != NO_ID) next = Math.max(next, g.id + 1);
        }
        for (Group7 g : c.groups) {
            if (g.id == NO_ID || g.id == 0 || byId.containsKey(g.id)) {
                while (byId.containsKey(next) || next == 0) next++;
                g.id = next;
            }
            byId.put(g.id, g);
        }
        for (Group7 g : c.groups) {
            if (!byId.containsKey(g.p)) g.p = 0;
        }
        for (Group7 g : c.groups) { // up from each group: one met again is in none
            Set<Integer> seen = new HashSet<>();
            seen.add(g.id);
            for (Group7 a = g; a.p != 0; a = byId.get(a.p)) {
                if (!seen.add(a.p)) {
                    a.p = 0;
                    break;
                }
            }
        }
        for (Lane7 l : c.lanes) {
            if (!byId.containsKey(l.g)) l.g = 0;
        }
    }

    /** Gives a lane without an id, or with an id an earlier lane has, the next unused id (keys keep the first). */
    private static void fixLaneIds(List<Lane7> lanes) {
        Set<Integer> used = new HashSet<>();
        int next = 1;
        for (Lane7 l : lanes) {
            if (l.id != NO_ID) next = Math.max(next, l.id + 1); // ids are below 2^24 (intValue)
        }
        for (Lane7 l : lanes) {
            if (l.id == NO_ID || !used.add(l.id)) {
                while (used.contains(next)) next++;
                l.id = next;
                used.add(next);
            }
        }
    }

    private static Key7 readKey(JsonParser p) throws IOException {
        Key7 k = new Key7();
        k.lane = NO_ID;
        String type = null;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "type": type = text(p, v); break;
                case "lane": k.lane = intValue(p, v); break;
                case "lanes": k.lanes = readIds(p, v); break;
                case "face": k.sky = "sky".equals(text(p, v)); break;
                case "fake": k.fake = v == JsonToken.VALUE_TRUE; break;
                case "h": readFn(p, v, k.h); break;
                case "H": readFn(p, v, k.H); break;
                case "o": readFn(p, v, k.o); break;
                case "trails": k.trails = orNone(effects(p, v, name).trails); break;
                case "glitches": k.glitches = orNone(effects(p, v, name).glitches); break;
                case "distortions": k.distortions = orNone(effects(p, v, name).distortions); break;
                default: p.skipChildren(); break;
            }
        }
        k.kind = KeyKind.of(type);
        return k;
    }

    /**
     * @return a scratch note with {@code name} ({@code trails}, {@code glitches} or {@code distortions}) read into it
     *         as format v5 reads it ({@link ChartMapper#parseTrail}, {@link ChartMapper#parseGlitch},
     *         {@link ChartMapper#parseDistortion})
     */
    private static ChartMapper.RawNote effects(JsonParser p, JsonToken v, String name) throws IOException {
        ChartMapper.RawNote n = new ChartMapper.RawNote();
        if (name.equals("trails")) ChartMapper.parseTrail(p, v, n);
        else if (name.equals("glitches")) ChartMapper.parseGlitch(p, v, n);
        else ChartMapper.parseDistortion(p, v, n);
        return n;
    }

    /** @return {@code l}, or an empty list for null (none) */
    private static <T> List<T> orNone(List<T> l) {
        return l != null ? l : new ArrayList<>();
    }

    /** @return true when {@code k} is kept; sets its lane or lanes to ones in {@code ids} */
    private static boolean keep(Key7 k, Set<Integer> ids) {
        if (k.kind == null || k.h.isEmpty()) return false;
        if (k.kind == KeyKind.WAVE_MINE || k.kind == KeyKind.TIDE_MINE) k.fake = false; // as the old tideMine
        if (k.kind.spans()) {
            int[] src = k.lanes.length > 0 ? k.lanes : k.lane != NO_ID ? new int[] {k.lane} : new int[0];
            LinkedHashSet<Integer> ok = new LinkedHashSet<>();
            for (int id : src) {
                if (ids.contains(id)) ok.add(id);
            }
            if (ok.isEmpty()) return false;
            k.lanes = new int[ok.size()];
            int i = 0;
            for (int id : ok) k.lanes[i++] = id;
            k.lane = k.lanes[0];
        } else {
            int lane = k.lane != NO_ID ? k.lane : k.lanes.length > 0 ? k.lanes[0] : NO_ID;
            if (lane == NO_ID || !ids.contains(lane)) return false;
            k.lane = lane;
            k.lanes = new int[0];
        }
        if (!k.kind.hasHeight()) {
            k.H.segs.clear();
            k.H.constant = k.H.def;
        }
        return true;
    }

    private static Volume7 readVolume(JsonParser p) throws IOException {
        Volume7 o = new Volume7();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "kind": o.curtain = "curtain".equals(text(p, v)); break;
                case "shape": o.ellipsoid = "ellipsoid".equals(text(p, v)); break;
                case "V": readGroup(p, v, WHD, o.V.x, o.V.y, o.V.z); break;
                case "p": readGroup(p, v, XYZ, o.p.x, o.p.y, o.p.z); break;
                case "o": readGroup(p, v, XYZ, o.o.x, o.o.y, o.o.z); break;
                case "b": readFn(p, v, o.b); break;
                case "distortions": o.distortions = orNone(effects(p, v, name).distortions); break;
                case "glitches": o.glitches = orNone(effects(p, v, name).glitches); break;
                default: p.skipChildren(); break;
            }
        }
        return o;
    }

    /** Reads an object of functions {@code {names[i]: fns[i], …}}; other keys are skipped. */
    private static void readGroup(JsonParser p, JsonToken v, String[] names, Fn... fns) throws IOException {
        if (v != JsonToken.START_OBJECT) {
            p.skipChildren();
            return;
        }
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            int i = Arrays.asList(names).indexOf(name);
            if (i >= 0) readFn(p, fv, fns[i]);
            else p.skipChildren();
        }
    }

    /**
     * Reads a function: a number (or numeric string) is its constant, a list its segments (sorted by {@code ti});
     * null or anything else leaves its default. A segment missing {@code ti}, {@code tf}, {@code xi} or
     * {@code xf}, or with a value that is not a finite number, is skipped; {@code tf < ti} becomes a jump at
     * {@code ti}, and an {@code e} that is not above 0 becomes 1.
     */
    private static void readFn(JsonParser p, JsonToken v, Fn fn) throws IOException {
        fn.segs.clear();
        fn.constant = fn.def;
        if (v == JsonToken.START_ARRAY) {
            JsonToken t;
            while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
                Seg s = t == JsonToken.START_OBJECT ? readSeg(p) : null;
                if (t != JsonToken.START_OBJECT) p.skipChildren();
                if (s != null) fn.segs.add(s);
            }
            fn.sort();
            return;
        }
        float x = number(p, v);
        if (Float.isFinite(x)) fn.constant = x;
    }

    private static Seg readSeg(JsonParser p) throws IOException {
        float ti = Float.NaN, tf = Float.NaN, xi = Float.NaN, xf = Float.NaN, e = 1f;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "ti": ti = number(p, v); break;
                case "tf": tf = number(p, v); break;
                case "xi": xi = number(p, v); break;
                case "xf": xf = number(p, v); break;
                case "e": e = number(p, v); break;
                default: p.skipChildren(); break;
            }
        }
        if (!Float.isFinite(ti) || !Float.isFinite(tf) || !Float.isFinite(xi) || !Float.isFinite(xf)) return null;
        if (!(e > 0f) || !Float.isFinite(e)) e = 1f;
        return new Seg(ti, Math.max(ti, tf), xi, xf, e);
    }

    /** @return a number or numeric string's value, else NaN (structures are skipped) */
    private static float number(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT) return p.getFloatValue();
        if (v == JsonToken.VALUE_STRING) {
            try {
                return Float.parseFloat(p.getText().trim());
            } catch (NumberFormatException e) {
                return Float.NaN;
            }
        }
        p.skipChildren();
        return Float.NaN;
    }

    /** @return a whole number's value as an int, else {@link #NO_ID} (structures are skipped) */
    private static int intValue(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.START_OBJECT || v == JsonToken.START_ARRAY) {
            p.skipChildren();
            return NO_ID;
        }
        float f = number(p, v);
        if (!(Math.abs(f) < (1 << 24)) || f != Math.rint(f)) return NO_ID;
        return (int) f;
    }

    /** @return the whole numbers of an array (or a single number) */
    private static int[] readIds(JsonParser p, JsonToken v) throws IOException {
        if (v != JsonToken.START_ARRAY) {
            int id = intValue(p, v);
            return id == NO_ID ? new int[0] : new int[] {id};
        }
        int[] out = new int[8];
        int n = 0;
        JsonToken t;
        while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) {
            int id = intValue(p, t);
            if (id == NO_ID) continue;
            if (n == out.length) out = Arrays.copyOf(out, n * 2);
            out[n++] = id;
        }
        return Arrays.copyOf(out, n);
    }

    private static String text(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NULL) return null;
        if (v != null && v.isScalarValue()) return p.getText();
        p.skipChildren();
        return null;
    }

    /** @return the value as maps, lists, strings, numbers (Long or Double), booleans and nulls */
    private static Object readValue(JsonParser p, JsonToken v) throws IOException {
        if (v == null) return null;
        switch (v) {
            case START_OBJECT: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    String name = p.currentName();
                    m.put(name, readValue(p, p.nextToken()));
                }
                return m;
            }
            case START_ARRAY: {
                List<Object> list = new ArrayList<>();
                JsonToken t;
                while ((t = p.nextToken()) != null && t != JsonToken.END_ARRAY) list.add(readValue(p, t));
                return list;
            }
            case VALUE_STRING: return p.getText();
            case VALUE_NUMBER_INT:
                return p.getNumberType() == JsonParser.NumberType.BIG_INTEGER ? (Object) p.getDoubleValue()
                    : (Object) p.getLongValue();
            case VALUE_NUMBER_FLOAT: return p.getDoubleValue();
            case VALUE_TRUE: return Boolean.TRUE;
            case VALUE_FALSE: return Boolean.FALSE;
            default: return null;
        }
    }

    // ------------------------------------------------------------------ writing

    /** Writes {@code c} as v7 JSON (UTF-8), one group, lane, key or volume per line. Flushed, not closed. */
    public static void write(Chart7 c, OutputStream out) throws IOException {
        try (JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            g.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            g.setPrettyPrinter(new Layout());
            g.writeStartObject();
            g.writeStringField("format", VERSION);
            if (c.charter != null && !c.charter.isEmpty()) g.writeStringField("charter", c.charter);
            if (c.constant != null && !c.constant.trim().isEmpty()) {
                g.writeFieldName("constant");
                String k = c.constant.trim();
                if (JSON_NUMBER.matcher(k).matches()) g.writeNumber(k);
                else g.writeString(c.constant);
            }
            if (!c.groups.isEmpty()) {
                g.writeArrayFieldStart("groups");
                for (Group7 o : c.groups) writeGroup7(g, o);
                g.writeEndArray();
            }
            g.writeArrayFieldStart("lanes");
            for (Lane7 l : c.lanes) writeLane(g, l);
            g.writeEndArray();
            g.writeArrayFieldStart("keys");
            for (Key7 k : c.keys) {
                if (k.kind != null) writeKey(g, k);
            }
            g.writeEndArray();
            if (!c.volumes.isEmpty()) {
                g.writeArrayFieldStart("volumes");
                for (Volume7 o : c.volumes) writeVolume(g, o);
                g.writeEndArray();
            }
            if (!isDefault(c.camera.x) || !isDefault(c.camera.y) || !isDefault(c.camera.z) || !isDefault(c.fov)) {
                g.writeObjectFieldStart("camera");
                writeGroup(g, "P", XYZ, null, c.camera.x, c.camera.y, c.camera.z);
                writeFn(g, "fov", c.fov, false);
                g.writeEndObject();
            }
            if (c.editor != null && !c.editor.isEmpty()) {
                g.writeFieldName("editor");
                writeValue(g, c.editor);
            }
            g.writeEndObject();
            g.writeRaw('\n');
        }
    }

    private static void writeGroup7(JsonGenerator g, Group7 o) throws IOException {
        g.writeStartObject();
        g.writeNumberField("id", o.id);
        if (o.name != null && !o.name.isEmpty()) g.writeStringField("name", o.name);
        if (o.p != 0) g.writeNumberField("p", o.p);
        writeGroup(g, "P", XYZ, null, o.P.x, o.P.y, o.P.z);
        writeGroup(g, "R", XYZ, null, o.R.x, o.R.y, o.R.z);
        writeFn(g, "K", o.K, false);
        writeFn(g, "o", o.o, false);
        g.writeEndObject();
    }

    private static void writeLane(JsonGenerator g, Lane7 l) throws IOException {
        g.writeStartObject();
        g.writeNumberField("id", l.id);
        if (l.g != 0) g.writeNumberField("g", l.g);
        writeGroup(g, "u", XYZ, null, l.u.x, l.u.y, l.u.z);
        writeGroup(g, "n", XYZ, null, l.n.x, l.n.y, l.n.z);
        writeGroup(g, "v", XYZ, l.v.x, l.v.x, l.v.y, l.v.z);
        writeGroup(g, "a", WH, null, l.w, l.h);
        writeFn(g, "S", l.S, false);
        writeFn(g, "s", l.s, false);
        writeFn(g, "F", l.F, false);
        writeFn(g, "f", l.f, false);
        writeFn(g, "o", l.o, false);
        writeFn(g, "l", l.l, false);
        writeFn(g, "m", l.m, false);
        g.writeEndObject();
    }

    private static void writeKey(JsonGenerator g, Key7 k) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", k.kind.json);
        if (k.kind.spans()) {
            g.writeArrayFieldStart("lanes");
            if (k.lanes != null) for (int id : k.lanes) g.writeNumber(id);
            g.writeEndArray();
        } else {
            g.writeNumberField("lane", k.lane);
        }
        if (k.sky) g.writeStringField("face", "sky");
        if (k.fake) g.writeBooleanField("fake", true);
        writeFn(g, "h", k.h, true);
        if (k.kind.hasHeight()) writeFn(g, "H", k.H, false);
        writeFn(g, "o", k.o, false);
        if (k.trails != null && !k.trails.isEmpty()) {
            g.writeArrayFieldStart("trails");
            for (NoteTrail t : k.trails) ChartFormatV5.writeTrail(g, t);
            g.writeEndArray();
        }
        if (k.glitches != null) ChartFormatV5.writeGlitches(g, k.glitches);
        if (k.distortions != null) ChartFormatV5.writeDistortions(g, k.distortions);
        g.writeEndObject();
    }

    private static void writeVolume(JsonGenerator g, Volume7 o) throws IOException {
        g.writeStartObject();
        g.writeStringField("kind", o.curtain ? "curtain" : "veil");
        g.writeStringField("shape", o.ellipsoid ? "ellipsoid" : "box");
        writeGroup(g, "V", WHD, null, o.V.x, o.V.y, o.V.z);
        writeGroup(g, "p", XYZ, null, o.p.x, o.p.y, o.p.z);
        writeGroup(g, "o", XYZ, null, o.o.x, o.o.y, o.o.z);
        writeFn(g, "b", o.b, false);
        if (o.distortions != null) ChartFormatV5.writeDistortions(g, o.distortions);
        if (o.glitches != null) ChartFormatV5.writeGlitches(g, o.glitches);
        g.writeEndObject();
    }

    /** @return true for a function at its default constant with no segments (left out of the file) */
    private static boolean isDefault(Fn f) {
        return f.segs.isEmpty() && f.constant == f.def;
    }

    /**
     * Writes {@code field: {names[i]: fns[i], …}} with the functions not at their default, and {@code always} even
     * then; nothing when none is written.
     */
    private static void writeGroup(JsonGenerator g, String field, String[] names, Fn always, Fn... fns)
            throws IOException {
        boolean any = false;
        for (Fn f : fns) any |= f == always || !isDefault(f);
        if (!any) return;
        g.writeObjectFieldStart(field);
        for (int i = 0; i < fns.length; i++) writeFn(g, names[i], fns[i], fns[i] == always);
        g.writeEndObject();
    }

    /** Writes {@code field: constant} or {@code field: [segments]}; nothing at the default unless {@code always}. */
    private static void writeFn(JsonGenerator g, String field, Fn f, boolean always) throws IOException {
        if (!always && isDefault(f)) return;
        g.writeFieldName(field);
        if (f.segs.isEmpty()) {
            num(g, f.constant);
            return;
        }
        g.writeStartArray();
        for (Seg s : f.segs) {
            g.writeStartObject();
            g.writeFieldName("ti");
            num(g, s.ti);
            g.writeFieldName("tf");
            num(g, s.tf);
            g.writeFieldName("xi");
            num(g, s.xi);
            g.writeFieldName("xf");
            num(g, s.xf);
            if (s.e != 1f) {
                g.writeFieldName("e");
                num(g, s.e);
            }
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    /** Writes a float as the shortest JSON number that reads back to it (whole numbers without {@code .0}; NaN 0). */
    private static void num(JsonGenerator g, float v) throws IOException {
        if (!Float.isFinite(v)) v = 0f;
        if (v == Math.rint(v) && Math.abs(v) < 1e9f) g.writeNumber((long) v);
        else g.writeNumber(v);
    }

    /** Writes an editor value: a map, list, string, number, boolean or null. */
    private static void writeValue(JsonGenerator g, Object v) throws IOException {
        if (v instanceof Map) {
            g.writeStartObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                g.writeFieldName(String.valueOf(e.getKey()));
                writeValue(g, e.getValue());
            }
            g.writeEndObject();
        } else if (v instanceof List) {
            g.writeStartArray();
            for (Object o : (List<?>) v) writeValue(g, o);
            g.writeEndArray();
        } else if (v instanceof Float) {
            num(g, (Float) v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) g.writeNumber(0);
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) g.writeNumber((long) d);
            else g.writeNumber(d);
        } else if (v instanceof Boolean) {
            g.writeBoolean((Boolean) v);
        } else if (v == null) {
            g.writeNull();
        } else {
            g.writeString(v.toString());
        }
    }

    /**
     * Layout of a written chart: one top-level key per line, one entry per line in the top-level arrays (a group, a
     * lane, a key or a volume per line), everything deeper on that line.
     */
    private static final class Layout implements PrettyPrinter {
        private int depth;

        @Override
        public void writeRootValueSeparator(JsonGenerator g) {
        }

        @Override
        public void writeStartObject(JsonGenerator g) throws IOException {
            g.writeRaw('{');
            depth++;
        }

        @Override
        public void beforeObjectEntries(JsonGenerator g) throws IOException {
            if (depth == 1) g.writeRaw("\n  ");
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }

        @Override
        public void writeObjectEntrySeparator(JsonGenerator g) throws IOException {
            g.writeRaw(depth == 1 ? ",\n  " : ", ");
        }

        @Override
        public void writeEndObject(JsonGenerator g, int entries) throws IOException {
            if (depth == 1 && entries > 0) g.writeRaw('\n');
            g.writeRaw('}');
            depth--;
        }

        @Override
        public void writeStartArray(JsonGenerator g) throws IOException {
            g.writeRaw('[');
            depth++;
        }

        @Override
        public void beforeArrayValues(JsonGenerator g) throws IOException {
            if (depth == 2) g.writeRaw("\n    ");
        }

        @Override
        public void writeArrayValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(depth == 2 ? ",\n    " : ", ");
        }

        @Override
        public void writeEndArray(JsonGenerator g, int values) throws IOException {
            if (depth == 2 && values > 0) g.writeRaw("\n  ");
            g.writeRaw(']');
            depth--;
        }
    }
}
