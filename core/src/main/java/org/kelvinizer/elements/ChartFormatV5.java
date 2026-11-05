package org.kelvinizer.elements;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.PrettyPrinter;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.kelvinizer.elements.ChartMapper.*;

/**
 * 谱面格式 v5 的读写：默认值不写、键名统一、事件写成 beats / values / ease、歌曲信息在 song.json；写出时一个音符一行。
 *
 * <p>Reads and writes chart format v5 (docs/CHART_FORMAT.md; Kelvin 2026-10-27 04:12 「我需要你把整个谱面文件全部重构」,
 * 04:40 the file format). A key left out has its default, so a note without {@code positionEvents} falls with the
 * default approach and a chart with the eight default lanes and lines writes neither. Events are
 * {@code {"beats": [start, end], "values": [initial, final], "ease": exponent}}; trails, glitches and distortions are
 * arrays. The song-wide values are not in the file but in {@code song.json} ({@link SongInfo}). Reading fills
 * {@link ChartMapper}'s raw model, so values are sanitised by the same rules as the old formats; the meaning of every
 * value is that of format v4.
 */
public final class ChartFormatV5 {
    static final int FORMAT = 5;

    private ChartFormatV5() {
    }

    /** @return true when the JSON object has a top-level {@code "format"} of 5 or more (false for unreadable JSON) */
    static boolean isV5(JsonFactory factory, byte[] json) {
        try (JsonParser p = factory.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken v = p.nextToken();
                if ("format".equals(name)) return v != null && v.isNumeric() && p.getDoubleValue() >= FORMAT;
                p.skipChildren();
            }
        } catch (IOException e) {
            return false; // the old-format reader reports it
        }
        return false;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads a v5 chart (the parser is before its first token).
     *
     * @param song  the song's values (null = none: the chart then has no BPM and fails to load)
     * @param level the difficulty (the file name), or null
     */
    static RawChart parse(JsonParser p, SongInfo song, String level) throws IOException {
        if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("chart is not a JSON object");
        RawChart c = new RawChart();
        c.formatVersion = FORMAT;
        c.level = level;
        if (song != null) applySong(c, song);
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "format": c.formatVersion = (int) floatValue(p, v, FORMAT); break;
                case "charter": c.charter = text(p, v); break;
                case "constant": c.chartConstant = text(p, v); break;
                case "laneCount": c.hasLaneCount = true; c.laneCount = intValue(p, v); break;
                case "lanes": c.lanes = parseLanes(p, v); break;
                case "lines": c.line = parseLines(p, v); break;
                case "veils": c.veils = parseVeils(p, v); break;
                case "curtains": c.curtains = parseVeils(p, v); break;
                case "notes": parseNotes(p, v, c.notes); break;
                case "song": {
                    // the song values of a chart away from its folder (backups, tests); song.json wins
                    SongInfo own = v == JsonToken.START_OBJECT ? SongInfo.read(p) : null;
                    if (own == null) p.skipChildren();
                    else if (song == null) applySong(c, own);
                    break;
                }
                default: p.skipChildren(); break;
            }
        }
        if (t != JsonToken.END_OBJECT) throw new IOException("truncated chart JSON");
        return c;
    }

    private static void applySong(RawChart c, SongInfo song) {
        c.title = song.title;
        c.artist = song.artist;
        c.illustrator = song.illustrator;
        c.bpm = song.bpm;
        c.bpmList = song.bpmList;
        c.hasOffset = song.hasOffset;
        c.offsetMs = song.offsetMs;
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
            while (p.nextToken() == JsonToken.FIELD_NAME) {
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
                    case "widthEvents": l.widthEvents = parseEvents(p, fv, Events.TYPE.Width); break;
                    case "opacityEvents": l.opacityEvents = parseEvents(p, fv, Events.TYPE.Opacity); break;
                    case "insert": {
                        if (fv != JsonToken.START_OBJECT) {
                            p.skipChildren();
                            break;
                        }
                        // the end and final width may be left out (CHART_FORMAT_v4 §3.6)
                        float[] beats = {0f, Float.NaN}, values = {0f, Float.NaN};
                        l.hasInsert = true;
                        l.insExponent = parseEvent(p, beats, values);
                        l.insStart = beats[0];
                        l.insEnd = beats[1];
                        l.insInitial = values[0];
                        l.insFinal = values[1];
                        break;
                    }
                    case "delete": {
                        if (fv != JsonToken.START_OBJECT) {
                            p.skipChildren();
                            break;
                        }
                        float[] beats = {0f, Float.NaN}, values = {Float.NaN, 0f};
                        l.hasDelete = true;
                        l.delExponent = parseEvent(p, beats, values);
                        l.delStart = beats[0];
                        l.delEnd = beats[1];
                        l.delInitial = values[0];
                        l.delFinal = values[1];
                        break;
                    }
                    default: p.skipChildren(); break;
                }
            }
            out.add(l);
        }
        return out;
    }

    private static RawLine parseLines(JsonParser p, JsonToken v) throws IOException {
        if (v != JsonToken.START_OBJECT) {
            p.skipChildren();
            return null;
        }
        RawLine l = new RawLine();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            switch (name) {
                case "upper": l.upper = floatValue(p, fv, Float.NaN); break;
                case "lower": l.lower = floatValue(p, fv, Float.NaN); break;
                case "upperEvents": l.upperEvents = parseEvents(p, fv, Events.TYPE.JudgementUp); break;
                case "lowerEvents": l.lowerEvents = parseEvents(p, fv, Events.TYPE.JudgementDown); break;
                case "scale": l.hasLaneScale = true; l.laneScale = floatValue(p, fv, Float.NaN); break;
                case "scaleEvents": l.laneScaleEvents = parseEvents(p, fv, Events.TYPE.LaneScale); break;
                case "camera": l.hasCamera = true; l.camera = floatValue(p, fv, Float.NaN); break;
                case "cameraEvents": l.cameraEvents = parseEvents(p, fv, Events.TYPE.CameraFrame); break;
                default: p.skipChildren(); break;
            }
        }
        return l;
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
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken fv = p.nextToken();
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
                    case "beat": n.judgement = floatValue(p, fv, Float.NaN); break;
                    case "appear": n.appearance = floatValue(p, fv, Float.NaN); break;
                    case "duration": n.duration = floatValue(p, fv, 0f); break;
                    case "height": n.height = floatValue(p, fv, 0f); break;
                    case "waveSpeed": n.waveSpeed = floatValue(p, fv, 0f); break;
                    case "offsetX": n.offsetX = floatValue(p, fv, 0f); break;
                    case "offsetXEvents": n.offsetXEvents = parseEvents(p, fv, null); break;
                    case "positionEvents": n.movement = parseEvents(p, fv, Events.TYPE.Movement); break;
                    case "rotationEvents": n.rotation = parseEvents(p, fv, Events.TYPE.Rotation); break;
                    case "opacityEvents": n.disappear = parseEvents(p, fv, Events.TYPE.Opacity); break;
                    case "heightEvents": n.heights = parseEvents(p, fv, Events.TYPE.Height); break;
                    case "pivot": parsePivot(p, fv, n); break;
                    case "orbit": parseOrbit(p, fv, n); break;
                    case "orbitXEvents": n.orbitXEvents = parseEvents(p, fv, null); break;
                    case "orbitYEvents": n.orbitYEvents = parseEvents(p, fv, null); break;
                    case "orbitRadius": n.orbitRadius = floatValue(p, fv, Float.NaN); break;
                    case "orbitRadiusEvents": n.orbitRadiusEvents = parseEvents(p, fv, null); break;
                    case "orbitAngle": n.orbitAngle = floatValue(p, fv, Float.NaN); break;
                    case "orbitAngleEvents": n.orbitAngleEvents = parseEvents(p, fv, null); break;
                    case "orbitConflict": n.orbitConflict = orbitConflictOf(text(p, fv)); break;
                    case "orbitRigid": n.orbitRigid = flag(p, fv); break;
                    case "fake": n.fake = flag(p, fv); break;
                    case "trails": parseTrail(p, fv, n); break;
                    case "glitches": parseGlitch(p, fv, n); break;
                    case "distortions": parseDistortion(p, fv, n); break;
                    default: p.skipChildren(); break;
                }
            }
            out.add(n);
        }
    }

    /** Reads {@code veils}: an array of objects; anything else in it becomes a dropped entry. */
    private static List<RawVeil> parseVeils(JsonParser p, JsonToken v) throws IOException {
        List<RawVeil> out = new ArrayList<>();
        if (v != JsonToken.START_ARRAY) {
            if (v != JsonToken.VALUE_NULL) {
                p.skipChildren();
                RawVeil bad = new RawVeil();
                bad.dropped = true;
                out.add(bad);
            }
            return out;
        }
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            if (t == JsonToken.START_OBJECT) {
                out.add(parseVeil(p));
            } else {
                p.skipChildren();
                RawVeil bad = new RawVeil();
                bad.dropped = true;
                out.add(bad);
            }
        }
        return out;
    }

    /** Reads one veil object (the parser is on its START_OBJECT); marks values that need sanitising. */
    private static RawVeil parseVeil(JsonParser p) throws IOException {
        RawVeil r = new RawVeil();
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            boolean isNull = fv == JsonToken.VALUE_NULL;
            switch (name) {
                case "shape": {
                    String s = text(p, fv);
                    r.shape = Veil.Shape.parse(s);
                    r.fixed |= r.shape == null && s != null;
                    break;
                }
                case "radius":
                    r.radius = floatValue(p, fv, Float.NaN);
                    r.fixed |= !(r.radius >= Veil.MIN_SIZE && r.radius <= Veil.MAX_SIZE);
                    break;
                case "points": r.points = parseVeilPoints(p, fv, r); break;
                case "x": r.x = floatValue(p, fv, Float.NaN); r.fixed |= bad(r.x); break;
                case "y": r.y = floatValue(p, fv, Float.NaN); r.fixed |= bad(r.y); break;
                case "rotation": r.rotation = floatValue(p, fv, Float.NaN); r.fixed |= bad(r.rotation); break;
                case "opacity":
                    r.opacity = floatValue(p, fv, Float.NaN);
                    r.fixed |= !(r.opacity >= 0f && r.opacity <= 1f);
                    break;
                case "from":
                    r.startTime = floatValue(p, fv, Float.NaN);
                    r.fixed |= bad(r.startTime) && !isNull;
                    break;
                case "to":
                    r.endTime = floatValue(p, fv, Float.NaN);
                    r.fixed |= bad(r.endTime) && !isNull;
                    break;
                case "xEvents": r.xEvents = parseEvents(p, fv, null); break;
                case "yEvents": r.yEvents = parseEvents(p, fv, null); break;
                case "rotationEvents": r.rotationEvents = parseEvents(p, fv, Events.TYPE.Rotation); break;
                case "opacityEvents": r.opacityEvents = parseEvents(p, fv, Events.TYPE.Opacity); break;
                case "blur":
                    r.blur = floatValue(p, fv, Float.NaN);
                    r.fixed |= !(r.blur >= 0f && r.blur <= Veil.MAX_BLUR);
                    break;
                case "blurEvents": r.blurEvents = parseEvents(p, fv, null); break;
                case "glitches": {
                    RawNote g = new RawNote();
                    parseGlitch(p, fv, g);
                    r.glitches = g.glitches;
                    r.fixed |= g.glitchFixed;
                    break;
                }
                case "distortions": {
                    RawNote d = new RawNote();
                    parseDistortion(p, fv, d);
                    r.distortions = d.distortions;
                    r.fixed |= d.distortionFixed;
                    break;
                }
                default: p.skipChildren(); break;
            }
        }
        r.fixed |= r.startTime > r.endTime;
        return r;
    }

    /** Reads an event list; entries that are not objects are skipped. */
    private static List<Events> parseEvents(JsonParser p, JsonToken v, Events.TYPE type) throws IOException {
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
            float[] beats = {0f, 0f}, values = {0f, 0f};
            float ease = parseEvent(p, beats, values);
            out.add(new Events(type, beats[0], beats[1], values[0], values[1], ease));
        }
        return out;
    }

    /**
     * Reads one event object (the parser is on its START_OBJECT) into {@code beats} and {@code values}, whose
     * entries keep their value where the file has none.
     *
     * @return its {@code ease} (1 when left out)
     */
    private static float parseEvent(JsonParser p, float[] beats, float[] values) throws IOException {
        float ease = 1f;
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken fv = p.nextToken();
            switch (name) {
                case "beats": pair(p, fv, beats); break;
                case "values": pair(p, fv, values); break;
                case "ease": ease = floatValue(p, fv, 1f); break;
                default: p.skipChildren(); break;
            }
        }
        return ease;
    }

    /** Reads up to two numbers of an array into {@code out}; anything else leaves {@code out} as it was. */
    private static void pair(JsonParser p, JsonToken v, float[] out) throws IOException {
        if (v != JsonToken.START_ARRAY) {
            p.skipChildren();
            return;
        }
        int k = 0;
        JsonToken t;
        while ((t = p.nextToken()) != JsonToken.END_ARRAY && t != null) {
            float f = floatValue(p, t, Float.NaN);
            if (k < 2 && !Float.isNaN(f)) out[k] = f;
            k++;
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Editor-only values the writer passes through (the chart converter keeps the Charter's keys): the top-level
     * {@code "editor"} object and extra keys of single events. Values are maps, lists, strings, numbers and booleans.
     */
    public static final class EditorData {
        /** Entries of the top-level {@code "editor"} object. */
        public final Map<String, Object> editor = new LinkedHashMap<>();
        /** Extra keys written into an event object, by event identity. */
        public final Map<Events, Map<String, Object>> eventKeys = new IdentityHashMap<>();
    }

    /**
     * @param withSong also write the song values as a {@code "song"} object, for a chart kept away from its folder
     * @return {@code chart} as v5 JSON text
     */
    public static String toJson(Chart chart, EditorData extra, boolean withSong) {
        StringWriter sw = new StringWriter(1 << 16);
        try {
            write(chart, ChartMapper.FACTORY.createGenerator(sw), extra, withSong);
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringWriter does not throw
        }
        return sw.toString();
    }

    /**
     * Writes {@code chart} as v5 JSON and flushes {@code g}.
     *
     * @param withSong also write the song values as a {@code "song"} object (in a song folder they belong in
     *                 {@code song.json}: {@link SongInfo#mergeInto})
     */
    static void write(Chart chart, JsonGenerator g, EditorData extra, boolean withSong) throws IOException {
        g.setPrettyPrinter(new Layout());
        g.writeStartObject();
        g.writeNumberField("format", FORMAT);
        if (chart.charter() != null && !chart.charter().isEmpty()) g.writeStringField("charter", chart.charter());
        String constant = chart.chartConstant();
        if (constant != null) {
            g.writeFieldName("constant");
            if (JSON_NUMBER.matcher(constant).matches()) g.writeNumber(constant);
            else g.writeString(constant);
        }
        if (!chart.unlimitedLanes()) g.writeNumberField("laneCount", chart.laneLimit());
        List<Lane> lanes = new ArrayList<>();
        for (Lane lane : chart.lanes()) {
            if (!lane.implicit()) lanes.add(lane); // implicit lanes are added by the loader, not authored
        }
        if (!defaultLanes(lanes)) {
            g.writeArrayFieldStart("lanes");
            for (Lane lane : lanes) writeLane(g, lane, extra);
            g.writeEndArray();
        }
        writeLines(g, chart.lineEvents(), extra);
        if (chart.veils().length > 0) {
            g.writeArrayFieldStart("veils");
            for (Veil v : chart.veils()) writeVeil(g, v, false, extra);
            g.writeEndArray();
        }
        if (chart.curtains().length > 0) {
            g.writeArrayFieldStart("curtains");
            for (Veil v : chart.curtains()) writeVeil(g, v, true, extra);
            g.writeEndArray();
        }
        g.writeArrayFieldStart("notes");
        for (Note n : chart.notes()) writeNote(g, n, extra);
        g.writeEndArray();
        if (withSong) {
            g.writeFieldName("song");
            SongInfo.of(chart).writeObject(g);
        }
        if (extra != null && !extra.editor.isEmpty()) {
            g.writeFieldName("editor");
            writeValue(g, extra.editor);
        }
        g.writeEndObject();
        g.writeRaw('\n');
        g.flush();
    }

    /** @return true for the eight default lanes −4..−1, 1..4 with no changes (left out of the file) */
    private static boolean defaultLanes(List<Lane> lanes) {
        if (lanes.size() != DEFAULT_LANE_IDS.length) return false;
        for (int i = 0; i < lanes.size(); i++) {
            Lane l = lanes.get(i);
            if (l.id() != DEFAULT_LANE_IDS[i] || l.baseWidth() != Lane.DEFAULT_WIDTH
                || l.baseOpacity() != Lane.DEFAULT_OPACITY || !l.widthEvents().isEmpty()
                || !l.opacityEvents().isEmpty() || l.insert() != null || l.deletion() != null) {
                return false;
            }
        }
        return true;
    }

    private static void writeLane(JsonGenerator g, Lane lane, EditorData extra) throws IOException {
        g.writeStartObject();
        g.writeNumberField("id", lane.id());
        if (lane.baseWidth() != Lane.DEFAULT_WIDTH) numField(g, "width", lane.baseWidth());
        if (lane.baseOpacity() != Lane.DEFAULT_OPACITY) numField(g, "opacity", lane.baseOpacity());
        writeEvents(g, "widthEvents", lane.widthEvents(), extra);
        writeEvents(g, "opacityEvents", lane.opacityEvents(), extra);
        if (lane.insert() != null) {
            g.writeFieldName("insert");
            writeEvent(g, lane.insert(), extra);
        }
        if (lane.deletion() != null) {
            g.writeFieldName("delete");
            writeEvent(g, lane.deletion(), extra);
        }
        g.writeEndObject();
    }

    /** Writes {@code lines} unless every value is its default. */
    private static void writeLines(JsonGenerator g, JudgementLine l, EditorData extra) throws IOException {
        if (l.upperBase() == JudgementLine.DEFAULT_UPPER && l.lowerBase() == JudgementLine.DEFAULT_LOWER
            && l.upperEvents().isEmpty() && l.lowerEvents().isEmpty() && l.laneScaleBase() == 1f
            && l.laneScaleEvents().isEmpty() && l.cameraFrameBase() == 0f && l.cameraFrameEvents().isEmpty()) {
            return;
        }
        g.writeObjectFieldStart("lines");
        if (l.upperBase() != JudgementLine.DEFAULT_UPPER) numField(g, "upper", l.upperBase());
        if (l.lowerBase() != JudgementLine.DEFAULT_LOWER) numField(g, "lower", l.lowerBase());
        writeEvents(g, "upperEvents", l.upperEvents(), extra);
        writeEvents(g, "lowerEvents", l.lowerEvents(), extra);
        if (l.laneScaleBase() != 1f) numField(g, "scale", l.laneScaleBase());
        writeEvents(g, "scaleEvents", l.laneScaleEvents(), extra);
        if (l.cameraFrameBase() != 0f) numField(g, "camera", l.cameraFrameBase());
        writeEvents(g, "cameraEvents", l.cameraFrameEvents(), extra);
        g.writeEndObject();
    }

    private static void writeNote(JsonGenerator g, Note n, EditorData extra) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", n.type().json);
        if (n.fake()) g.writeBooleanField("fake", true);
        if (n.line() != Line.LOWER) g.writeStringField("line", n.line().json);
        if (n.type().inLane()) g.writeNumberField("lane", n.laneId());
        numField(g, "beat", n.judgement());
        if (n.duration() != 0f) numField(g, "duration", n.duration());
        if (n.height() != 0f) numField(g, "height", n.height());
        if (n.waveSpeed() != 0f) numField(g, "waveSpeed", n.waveSpeed());
        if (n.offsetX() != 0f) numField(g, "offsetX", n.offsetX());
        List<Events> move = n.movementEvents();
        if (move.isEmpty()) {
            if (!n.usesDefaultApproach()) numField(g, "appear", n.appearance()); // no approach (CHART_FORMAT_v4 §9)
        } else if (!isDefaultFall(n, move, extra)) {
            writeEvents(g, "positionEvents", move, extra);
        }
        writeEvents(g, "rotationEvents", n.rotationEvents(), extra);
        writeEvents(g, "offsetXEvents", n.offsetXEvents(), extra);
        writeEvents(g, "opacityEvents", n.disappearEvents(), extra);
        writeEvents(g, "heightEvents", n.heightEvents(), extra);
        if (n.hasPivot()) {
            g.writeArrayFieldStart("pivot");
            num(g, n.pivotX());
            num(g, n.pivotY());
            g.writeEndArray();
        }
        NoteOrbit orbit = n.orbit();
        if (orbit != null) {
            g.writeArrayFieldStart("orbit");
            num(g, orbit.x());
            num(g, orbit.y());
            g.writeEndArray();
            writeEvents(g, "orbitXEvents", orbit.xEvents(), extra);
            writeEvents(g, "orbitYEvents", orbit.yEvents(), extra);
            if (!Float.isNaN(orbit.radius())) numField(g, "orbitRadius", orbit.radius());
            writeEvents(g, "orbitRadiusEvents", orbit.radiusEvents(), extra);
            if (!Float.isNaN(orbit.angle())) numField(g, "orbitAngle", orbit.angle());
            writeEvents(g, "orbitAngleEvents", orbit.angleEvents(), extra);
            String conflict = ORBIT_CONFLICT_NAMES[orbit.conflict()];
            if (conflict != null) g.writeStringField("orbitConflict", conflict);
            if (orbit.rigid()) g.writeBooleanField("orbitRigid", true);
        }
        if (!n.trails().isEmpty()) {
            g.writeArrayFieldStart("trails");
            for (NoteTrail t : n.trails()) writeTrail(g, t);
            g.writeEndArray();
        }
        writeGlitches(g, n.glitches());
        writeDistortions(g, n.distortions());
        g.writeEndObject();
    }

    /**
     * @return true when the movement is just the default approach, which a note without {@code positionEvents} gets
     *         (and no editor key on it but the Charter's {@code "rel": true}, its default for that event)
     */
    private static boolean isDefaultFall(Note n, List<Events> move, EditorData extra) {
        if (move.size() != 1) return false;
        Events e = move.get(0), d = Note.defaultApproachEvent(n.judgement());
        if (e.startTime() != d.startTime() || e.endTime() != d.endTime() || e.initialValue() != d.initialValue()
            || e.finalValue() != d.finalValue() || e.exponent() != d.exponent()) {
            return false;
        }
        Map<String, Object> keys = extra != null ? extra.eventKeys.get(e) : null;
        return keys == null || keys.isEmpty() || keys.size() == 1 && Boolean.TRUE.equals(keys.get("rel"));
    }

    /** Writes a veil or a curtain (times only when bounded, event lists only when not empty). */
    private static void writeVeil(JsonGenerator g, Veil v, boolean curtain, EditorData extra) throws IOException {
        g.writeStartObject();
        g.writeStringField("shape", v.shape().json);
        if (v.shape() == Veil.Shape.CIRCLE) {
            numField(g, "radius", v.radius());
        } else {
            g.writeArrayFieldStart("points");
            for (int i = 0; i < v.pointCount(); i++) {
                g.writeStartArray();
                num(g, v.pointX(i));
                num(g, v.pointY(i));
                g.writeEndArray();
            }
            g.writeEndArray();
        }
        if (v.baseX() != 960f) numField(g, "x", v.baseX());
        if (v.baseY() != 600f) numField(g, "y", v.baseY());
        if (v.baseRotation() != 0f) numField(g, "rotation", v.baseRotation());
        if (v.baseOpacity() != 1f) numField(g, "opacity", v.baseOpacity());
        if (v.hasStart()) numField(g, "from", v.startTime());
        if (v.hasEnd()) numField(g, "to", v.endTime());
        writeEvents(g, "xEvents", v.xEvents(), extra);
        writeEvents(g, "yEvents", v.yEvents(), extra);
        writeEvents(g, "rotationEvents", v.rotationEvents(), extra);
        writeEvents(g, "opacityEvents", v.opacityEvents(), extra);
        if (curtain && v.blur() != Veil.DEFAULT_BLUR) numField(g, "blur", v.blur());
        if (curtain) writeEvents(g, "blurEvents", v.blurEvents(), extra);
        writeGlitches(g, v.glitches());
        writeDistortions(g, v.distortions());
        g.writeEndObject();
    }

    /** Writes one trail segment, its default values left out. */
    private static void writeTrail(JsonGenerator g, NoteTrail t) throws IOException {
        g.writeStartObject();
        if (t.length() != NoteTrail.SUGGESTED_LENGTH) numField(g, "length", t.length());
        if (t.density() != NoteTrail.SUGGESTED_DENSITY) numField(g, "density", t.density());
        if (t.alpha() != NoteTrail.DEFAULT_ALPHA) numField(g, "alpha", t.alpha());
        if (t.fade() != NoteTrail.DEFAULT_FADE) numField(g, "fade", t.fade());
        if (t.mode() != NoteTrail.Mode.PATH) g.writeStringField("mode", t.mode().json);
        if (t.hasFrom()) numField(g, "from", t.from());
        if (t.hasTo()) numField(g, "to", t.to());
        g.writeEndObject();
    }

    /** Writes {@code glitches} (nothing for none), default values left out. */
    private static void writeGlitches(JsonGenerator g, List<NoteGlitch> glitches) throws IOException {
        if (glitches.isEmpty()) return;
        g.writeArrayFieldStart("glitches");
        for (NoteGlitch x : glitches) {
            g.writeStartObject();
            if (x.hasFrom()) numField(g, "from", x.from());
            if (x.hasTo()) numField(g, "to", x.to());
            if (x.intensity() != NoteGlitch.DEFAULT_INTENSITY) numField(g, "intensity", x.intensity());
            if (x.intensityEnd() != x.intensity()) numField(g, "intensityEnd", x.intensityEnd());
            if (x.exponent() != 1f) numField(g, "ease", x.exponent());
            if (x.rate() != NoteGlitch.DEFAULT_RATE) numField(g, "rate", x.rate());
            if (x.split() != NoteGlitch.DEFAULT_SPLIT) numField(g, "split", x.split());
            if (x.slice() != NoteGlitch.DEFAULT_SLICE) numField(g, "slice", x.slice());
            if (x.flicker() != NoteGlitch.DEFAULT_FLICKER) numField(g, "flicker", x.flicker());
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    /** Writes {@code distortions} (nothing for none), default values left out. */
    private static void writeDistortions(JsonGenerator g, List<NoteDistortion> list) throws IOException {
        if (list.isEmpty()) return;
        g.writeArrayFieldStart("distortions");
        for (NoteDistortion d : list) {
            g.writeStartObject();
            if (d.hasFrom()) numField(g, "from", d.from());
            if (d.hasTo()) numField(g, "to", d.to());
            if (d.intensity() != NoteDistortion.DEFAULT_INTENSITY) numField(g, "intensity", d.intensity());
            if (d.intensityEnd() != d.intensity()) numField(g, "intensityEnd", d.intensityEnd());
            if (d.exponent() != 1f) numField(g, "ease", d.exponent());
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    /** Writes an event list (nothing when it is empty). */
    private static void writeEvents(JsonGenerator g, String field, List<Events> events, EditorData extra)
        throws IOException {
        if (events.isEmpty()) return;
        g.writeArrayFieldStart(field);
        for (int i = 0; i < events.size(); i++) writeEvent(g, events.get(i), extra);
        g.writeEndArray();
    }

    private static void writeEvent(JsonGenerator g, Events e, EditorData extra) throws IOException {
        g.writeStartObject();
        g.writeArrayFieldStart("beats");
        num(g, e.startTime());
        num(g, e.endTime());
        g.writeEndArray();
        g.writeArrayFieldStart("values");
        num(g, e.initialValue());
        num(g, e.finalValue());
        g.writeEndArray();
        if (e.exponent() != 1f) numField(g, "ease", e.exponent());
        Map<String, Object> keys = extra != null ? extra.eventKeys.get(e) : null;
        if (keys != null) {
            for (Map.Entry<String, Object> k : keys.entrySet()) {
                g.writeFieldName(k.getKey());
                writeValue(g, k.getValue());
            }
        }
        g.writeEndObject();
    }

    private static void numField(JsonGenerator g, String field, float v) throws IOException {
        g.writeFieldName(field);
        num(g, v);
    }

    /** Writes a float as the shortest JSON number that reads back to it (whole numbers without {@code .0}). */
    static void num(JsonGenerator g, float v) throws IOException {
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
            if (d == Math.rint(d) && Math.abs(d) < 1e15) g.writeNumber((long) d);
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
     * Layout of a written chart: one top-level key per line, one entry per line in the top-level arrays (a note, a
     * lane or a veil per line), everything deeper on that line.
     */
    static final class Layout implements PrettyPrinter {
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
