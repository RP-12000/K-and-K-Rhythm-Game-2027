package org.kelvinizer.elements;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.kelvinizer.elements.ChartMapper.*;

/**
 * 旧谱面格式（v1–v4）的读取：解析文件里的键，并把 v1–v3 的坐标换成 v4；从 2026-10-27 起只读不写，写出一律是 v5
 * （{@link ChartFormatV5}）。
 *
 * <p>Reads the chart formats before v5 (v1–v4) into {@link ChartMapper}'s raw model, which
 * {@link ChartMapper} turns into a {@link Chart}: format v4 parsing, the exact v1 → v2 conversion of
 * ARCHITECTURE §13.3, the v2 → v3 change of frame and the v3 → v4 move of the lines. Read only: charts are written
 * as v5 since 2026-10-27 (CHART_FORMAT.md; the old keys are in CHART_FORMAT_v4.md).
 *
 * <p><b>Format v3</b> ({@code "formatVersion": 3}, Kelvin 2026-10-19 03:51: 「坐标系我们现在修改为1920*1200」) is
 * format v2 with every position in the 1920 × 1200 reference frame instead of 1920 × 1080. Format v1 and v2 charts
 * are read in the old frame and their y values (line heights and their events, note distances and tail lengths
 * with their events, pivot y) are multiplied by 1200 / 1080 on load ({@link ChartMapper#fromFrame1080}), so they look
 * and play exactly as before; x values (lane widths, camera frame, pivot x) keep their numbers.
 *
 * <p><b>Format v4</b> ({@code "formatVersion": 4}, Kelvin 2026-10-19 14:55: 「我觉得换成1080/120」) is format v3 with
 * the default judgement lines at 1080 / 120 instead of 1066.67 / 133.33. Older charts are moved on load
 * ({@link #toLines1080}): line heights and their events are stretched about the frame's centre y 600 by 36 / 35, the
 * gap between the lines (960 over 933.33), and rounded to 0.001 ({@link ChartMapper#toV4Line}: 1066.67 → 1080,
 * 133.33 → 120).
 * Every other value keeps its number: note distances, tail lengths and pivots are measured from the line, so notes
 * still reach the lines on their beats, and the default fall stays 1080 over 2 beats (Kelvin 15:19:
 * 「默认下落改为两拍1080的距离」). The judgement zone stays ±480 about each line, so the two zones meet at the
 * centre.
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
 * lane's optional {@code insert} object (Kelvin 2026-10-15, CHART_FORMAT_v4 §3.6) inserts it during the
 * song ({@link Lane#inserted()}), its optional {@code delete} object (Kelvin 2026-10-17, §3.7) deletes
 * it ({@link Lane#deleted()}). Optional {@code offsetMs}:
 * song time in ms of beat 0 (absent → 0; non-finite → 0 with a report message), so the beat grid
 * can start where the music's first beat is; v1 charts ignore it. Optional {@code laneCount}
 * (Kelvin 2026-10-13, {@link Chart#laneLimit()}): N ≥ 1 makes the lanes finite (the lanes as
 * declared share a 1920 × lane scale wide frame about the axis; the editor declares
 * −⌊N/2⌋..−1, 1..⌈N/2⌉); without it the lanes are unlimited (each keeps its width from the
 * axis) and the loader adds the gap lanes and the lanes the screen can reach
 * ({@link Lane#implicit()}, not written back). Optional {@code LineEvents.laneScale} /
 * {@code laneScaleEvents} (Kelvin 2026-10-13): global lane scaling, a factor on every lane width
 * ({@link JudgementLine#laneScaleAt}; absent → 1, negative or non-finite base → 1 with a report
 * message); scaling some lanes is their {@code widthEvents}. Optional {@code LineEvents.cameraFrame}
 * / {@code cameraFrameEvents} (Kelvin 2026-10-13 05:29): the lanes' axis offset from x 960
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
 *   <li>unlimited default lanes (Kelvin 2026-10-13): the ten 240-wide lanes filled the window
 *       (absent bounds → −240 / 2160), so its width over 2400 becomes the global lane scale and
 *       its centre's offset from x 960 the camera frame (Kelvin 2026-10-13 05:29), events
 *       included, so the look is unchanged (the shipped songs: scale 1, 0.8 for beats 526–562,
 *       camera frame 0). A left and right event pair with the same times and exponent converts
 *       exactly; unpaired window events are dropped (a report message);</li>
 *   <li>line y values and events are copied unchanged;</li>
 *   <li>a note whose motion misses its judgement ({@link Note#v1NeedsApproach}) gets the
 *       default approach as its movement event ({@link Note#defaultApproachEvent}), the look it
 *       had before format v2 used motion as written (Kelvin 2026-10-13).</li>
 * </ol>
 * Nothing is de-duplicated: notes stacked on one line, lane and judgement beat all stay (taps /
 * holds since Kelvin 2026-11-02, mines since 2026-10-22, universal notes always).
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
 * <p><b>Glitches</b> (CHART_FORMAT_v4 §6.9): a v2 note may carry {@code "glitch"}, one object or an
 * array of objects with {@code from}, {@code to} (or {@code duration}), {@code intensity},
 * {@code intensityEnd}, {@code exponent}, {@code rate}, {@code split}, {@code slice} and
 * {@code flicker} (read case-insensitively; sanitised by {@link NoteGlitch#of}, counted in
 * {@link Chart.LoadReport#glitchesFixed()}); v1 charts get none
 * ({@link Chart.LoadReport#glitchesIgnored()}).
 *
 * <p><b>Distortions</b> (CHART_FORMAT_v4 §6.11, Kelvin 2026-10-23 16:57): notes and veils may carry
 * {@code "distortion"}, written like {@code "glitch"} with {@code from}, {@code to} (or {@code duration}),
 * {@code intensity}, {@code intensityEnd} and {@code exponent} (sanitised by {@link NoteDistortion#of}, counted in
 * {@link Chart.LoadReport#distortionsFixed()}); v1 charts get none ({@link Chart.LoadReport#distortionsIgnored()}).
 *
 * <p><b>Pivot</b> (CHART_FORMAT_v4 §6.1.1, Kelvin 2026-10-16): a note may carry {@code "pivot": [x, y]}
 * (or an object with {@code x} and {@code y}; the key read case-insensitively): the point it turns
 * about, in reference units in its own frame from its origin ({@link Note#pivotX()}). Missing = the
 * origin. A value that is not two finite numbers is read as far as it goes (a missing or bad
 * coordinate is 0) and counted in {@link Chart.LoadReport#pivotsFixed()}. Written only when not the
 * origin.
 *
 * <p><b>Veils</b> (CHART_FORMAT_v4 §6.10, Kelvin 2026-10-23): an optional top-level {@code "veils"} array of
 * {@link Veil} objects ({@code shape}, {@code radius} or {@code points}, {@code x}, {@code y}, {@code rotation},
 * {@code opacity}, {@code startTime}, {@code endTime}, {@code xEvents}, {@code yEvents}, {@code rotationEvents},
 * {@code opacityEvents}, {@code glitch}, {@code distortion}). Bad values are sanitised by {@link Veil#of} and
 * counted in {@link Chart.LoadReport#veilsFixed()}; a polygon with fewer than 3 usable points, or an entry that is
 * not an object, is dropped ({@link Chart.LoadReport#veilsDropped()}). v1 charts get none. Written only when there
 * are some, so charts without veils are written exactly as before.
 */
final class LegacyChartFormat {
    private LegacyChartFormat() {
    }

    /**
     * Reads a v1–v4 chart (the parser is before its first token) and moves v1–v3 values into the v4 frame and lines.
     */
    static RawChart parse(JsonParser p) throws IOException {
        RawChart raw = parseTop(p);
        raw.readerFormat = 4;
        if (raw.formatVersion < 3) toFrame1200(raw);
        if (raw.formatVersion < 4) toLines1080(raw);
        return raw;
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
     * Moves a format v1–v3 chart (already in the 1200-high frame) onto the v4 lines 1080 / 120: the line heights and
     * their events by {@link ChartMapper#toV4Line}. Absent values (NaN) stay absent, so they take the v4 defaults; the
     * notes keep their numbers.
     */
    private static void toLines1080(RawChart raw) {
        RawLine l = raw.line;
        if (l == null) return;
        l.upper = toV4Line(l.upper);
        l.lower = toV4Line(l.lower);
        l.upperEvents = toV4(l.upperEvents);
        l.lowerEvents = toV4(l.lowerEvents);
    }

    /** @return the line events with both values moved by {@link ChartMapper#toV4Line} (null stays null). */
    private static List<Events> toV4(List<Events> events) {
        if (events == null) return null;
        List<Events> out = new ArrayList<>(events.size());
        for (Events e : events) {
            out.add(e == null ? null : new Events(e.type(), e.startTime(), e.endTime(), toV4Line(e.initialValue()),
                toV4Line(e.finalValue()), e.exponent()));
        }
        return out;
    }

    private static RawChart parseTop(JsonParser p) throws IOException {
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
                case "veils": c.veils = parseVeils(p, v); break;
                case "curtains": c.curtains = parseVeils(p, v); break;
                default: p.skipChildren(); break;
            }
        }
        if (t != JsonToken.END_OBJECT) throw new IOException("truncated chart JSON");
        return c;
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
                case "startTime":
                    r.startTime = floatValue(p, fv, Float.NaN);
                    r.fixed |= bad(r.startTime) && !isNull;
                    break;
                case "endTime":
                    r.endTime = floatValue(p, fv, Float.NaN);
                    r.fixed |= bad(r.endTime) && !isNull;
                    break;
                case "xEvents": r.xEvents = parseEvents(p, fv); break;
                case "yEvents": r.yEvents = parseEvents(p, fv); break;
                case "rotationEvents":
                case "RotationEvents": r.rotationEvents = parseEvents(p, fv); break;
                case "opacityEvents":
                case "OpacityEvents":
                case "DisappearEvents": r.opacityEvents = parseEvents(p, fv); break;
                case "blur":
                    r.blur = floatValue(p, fv, Float.NaN);
                    r.fixed |= !(r.blur >= 0f && r.blur <= Veil.MAX_BLUR);
                    break;
                case "blurEvents": r.blurEvents = parseEvents(p, fv); break;
                default:
                    if (GLITCH_KEY.equalsIgnoreCase(name.trim())) {
                        RawNote g = new RawNote();
                        parseGlitch(p, fv, g);
                        r.glitches = g.glitches;
                        r.fixed |= g.glitchFixed;
                    } else if (DISTORTION_KEY.equalsIgnoreCase(name.trim())) {
                        RawNote d = new RawNote();
                        parseDistortion(p, fv, d);
                        r.distortions = d.distortions;
                        r.fixed |= d.distortionFixed;
                    } else {
                        p.skipChildren();
                    }
                    break;
            }
        }
        r.fixed |= r.startTime > r.endTime;
        return r;
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
                if (DISTORTION_KEY.equalsIgnoreCase(name)) {
                    parseDistortion(p, fv, n);
                    continue;
                }
                if (PIVOT_KEY.equalsIgnoreCase(name)) {
                    parsePivot(p, fv, n);
                    continue;
                }
                if (ORBIT_KEY.equalsIgnoreCase(name)) {
                    parseOrbit(p, fv, n);
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
                    // the chart editor's v4 working text
                    case "waveSpeed": n.waveSpeed = floatValue(p, fv, 0f); break;
                    case "offsetX": n.offsetX = floatValue(p, fv, 0f); break;
                    case "offsetXEvents": n.offsetXEvents = parseEvents(p, fv); break;
                    case "orbitXEvents": n.orbitXEvents = parseEvents(p, fv); break;
                    case "orbitYEvents": n.orbitYEvents = parseEvents(p, fv); break;
                    case "orbitRadius": n.orbitRadius = floatValue(p, fv, Float.NaN); break;
                    case "orbitRadiusEvents": n.orbitRadiusEvents = parseEvents(p, fv); break;
                    case "orbitAngle": n.orbitAngle = floatValue(p, fv, Float.NaN); break;
                    case "orbitAngleEvents": n.orbitAngleEvents = parseEvents(p, fv); break;
                    case "orbitConflict": n.orbitConflict = orbitConflictOf(text(p, fv)); break;
                    case "orbitRigid": n.orbitRigid = flag(p, fv); break;
                    case "fake": n.fake = flag(p, fv); break;
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

    /** A lane's {@code insert} object (Kelvin 2026-10-15): the keys of an event; {@code final} may be absent. */
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

    /** A lane's {@code delete} object (Kelvin 2026-10-17): the keys of an event, every one optional. */
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
}
