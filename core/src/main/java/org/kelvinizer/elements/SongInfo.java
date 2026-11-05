package org.kelvinizer.elements;

import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一首歌共同的信息（{@code song.json}）：歌名、曲师、画师、BPM、变速和 offset；v5 谱面只在这里写这些，每个难度都用它。
 *
 * <p>The song-wide values of format v5 (CHART_FORMAT §1): {@code title}, {@code artist}, {@code illustrator},
 * {@code bpm}, {@code bpmList} and {@code offsetMs} of a song folder's {@code song.json}, which every difficulty of
 * the song shares. The file's other keys (preview, jacket framing, map, editor keys) are read elsewhere and kept by
 * {@link #mergeInto}.
 */
public final class SongInfo {
    /** The song file's name in a song folder. */
    public static final String FILE_NAME = "song.json";

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "title", "artist", "illustrator", "bpm", "bpmList", "offsetMs"));

    /** Song title; null = the folder name. */
    public String title;
    public String artist;
    public String illustrator;
    /** BPM (the first segment's with a {@link #bpmList}); NaN = missing. */
    public float bpm = Float.NaN;
    /** Tempo changes as read: {beat, bpm} entries, unsorted and unchecked (null = none). */
    public List<float[]> bpmList;
    public boolean hasOffset;
    /** Milliseconds into the audio of beat 0. */
    public float offsetMs;

    /**
     * @return the values of the {@code song.json} beside {@code chartFile} (the title defaulting to the folder name),
     *         or null when there is none or it cannot be read
     */
    public static SongInfo besides(FileHandle chartFile) {
        return in(chartFile.parent());
    }

    /**
     * @return the values of a song folder's {@code song.json} (the title defaulting to the folder name), or null
     *         when there is none or it cannot be read
     */
    public static SongInfo in(FileHandle folder) {
        FileHandle f = folder.child(FILE_NAME);
        if (!f.exists()) return null;
        try (InputStream in = f.read()) {
            SongInfo s = read(in);
            if (s.title == null) s.title = folder.name();
            return s;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads the song values of a {@code song.json} (the stream is not closed); other keys are skipped.
     *
     * @throws IOException when it is not a JSON object
     */
    public static SongInfo read(InputStream in) throws IOException {
        try (JsonParser p = FACTORY.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("song.json is not a JSON object");
            return read(p);
        }
    }

    /** Reads the song values of the object the parser is on (its START_OBJECT) up to its end. */
    static SongInfo read(JsonParser p) throws IOException {
        SongInfo s = new SongInfo();
        JsonToken t;
        while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
            String name = p.currentName();
            JsonToken v = p.nextToken();
            switch (name) {
                case "title": s.title = ChartMapper.text(p, v); break;
                case "artist": s.artist = ChartMapper.text(p, v); break;
                case "illustrator": s.illustrator = ChartMapper.text(p, v); break;
                case "bpm": s.bpm = ChartMapper.floatValue(p, v, Float.NaN); break;
                case "bpmList": s.bpmList = ChartMapper.parseBpmList(p, v); break;
                case "offsetMs": s.hasOffset = true; s.offsetMs = ChartMapper.floatValue(p, v, Float.NaN); break;
                default: p.skipChildren(); break;
            }
        }
        if (t != JsonToken.END_OBJECT) throw new IOException("truncated song.json");
        return s;
    }

    /** @return the lowest and highest positive BPM of {@link #bpm} and {@link #bpmList} ({0, 0} for none) */
    public float[] bpmRange() {
        float lo = 0f, hi = 0f;
        int n = bpmList != null ? bpmList.size() : 0;
        for (int i = -1; i < n; i++) {
            float b = i < 0 ? bpm : bpmList.get(i)[1];
            if (!(b > 0f) || b == Float.POSITIVE_INFINITY) continue;
            lo = lo > 0f ? Math.min(lo, b) : b;
            hi = Math.max(hi, b);
        }
        return new float[]{lo, hi};
    }

    /** @return the song values of a loaded chart (as a v5 chart reads them back) */
    public static SongInfo of(Chart chart) {
        SongInfo s = new SongInfo();
        s.title = chart.title();
        s.artist = chart.artist();
        s.illustrator = chart.illustrator();
        s.bpm = chart.bpm();
        float[] beats = chart.bpmListBeats(), bpms = chart.bpmListValues();
        if (beats.length > 1) {
            s.bpmList = new java.util.ArrayList<>(beats.length);
            for (int i = 0; i < beats.length; i++) s.bpmList.add(new float[]{beats[i], bpms[i]});
        }
        s.hasOffset = chart.offsetMs() != 0f;
        s.offsetMs = chart.offsetMs();
        return s;
    }

    /** Writes these values as a JSON object (empty ones left out). */
    void writeObject(JsonGenerator g) throws IOException {
        g.writeStartObject();
        writeFields(g);
        g.writeEndObject();
    }

    private void writeFields(JsonGenerator g) throws IOException {
        if (title != null) g.writeStringField("title", title);
        if (artist != null && !artist.isEmpty()) g.writeStringField("artist", artist);
        if (illustrator != null && !illustrator.isEmpty()) g.writeStringField("illustrator", illustrator);
        g.writeFieldName("bpm");
        ChartFormatV5.num(g, bpm);
        if (bpmList != null) {
            g.writeArrayFieldStart("bpmList");
            for (float[] e : bpmList) {
                g.writeStartObject();
                g.writeFieldName("beat");
                ChartFormatV5.num(g, e[0]);
                g.writeFieldName("bpm");
                ChartFormatV5.num(g, e[1]);
                g.writeEndObject();
            }
            g.writeEndArray();
        }
        if (hasOffset && offsetMs != 0f) {
            g.writeFieldName("offsetMs");
            ChartFormatV5.num(g, offsetMs);
        }
    }

    /**
     * @param existing the current {@code song.json} text (null or blank = none)
     * @return a {@code song.json} with these values first (empty ones left out) and every other key of
     *         {@code existing} after them, unchanged
     * @throws IOException when {@code existing} is not a JSON object
     */
    public String mergeInto(String existing) throws IOException {
        StringWriter sw = new StringWriter(512);
        try (JsonGenerator g = FACTORY.createGenerator(sw)) {
            g.setPrettyPrinter(new ChartFormatV5.Layout());
            g.writeStartObject();
            writeFields(g);
            if (existing != null && !existing.trim().isEmpty()) {
                try (JsonParser p = FACTORY.createParser(existing)) {
                    if (p.nextToken() != JsonToken.START_OBJECT) {
                        throw new IOException("song.json is not a JSON object");
                    }
                    while (p.nextToken() == JsonToken.FIELD_NAME) {
                        String name = p.currentName();
                        p.nextToken();
                        if (KEYS.contains(name)) {
                            p.skipChildren();
                            continue;
                        }
                        g.writeFieldName(name);
                        g.copyCurrentStructure(p);
                    }
                }
            }
            g.writeEndObject();
        }
        return sw.toString() + "\n";
    }
}
