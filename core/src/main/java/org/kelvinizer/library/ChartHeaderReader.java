package org.kelvinizer.library;

import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;

/**
 * 用 Jackson 流式 API 只读谱面 JSON 开头的头字段：标题、曲师、画师、谱师、BPM、定数和难度名。
 * 读到 {@code notes} 数组时，若标题和定数已知就停下，所以加载曲库很快。
 *
 * <p>Reads only the top-level header fields of a chart JSON with the Jackson streaming API
 * (ARCHITECTURE §8): {@code title, artist, illustrator, charter, bpm, chartConstant, level}.
 *
 * <p>Parsing stops at the {@code notes} array once the title and constant are known (the
 * shipped charts put every header field first), so a 900 KB chart costs one small read.
 * If a header field is missing before {@code notes}, the array is skipped and parsing
 * continues.
 */
public final class ChartHeaderReader {
    private static final JsonFactory FACTORY = new JsonFactory();

    private ChartHeaderReader() {
    }

    /** Header fields of one chart file. */
    public static final class Header {
        /** Chart title (may be null if absent). */
        public String title;
        /** Artist (may be null). */
        public String artist;
        /** Illustrator (may be null). */
        public String illustrator;
        /** Charter (may be null). */
        public String charter;
        /** Beats per minute (0 if absent). */
        public float bpm;
        /**
         * Lowest and highest tempo over {@code bpm} and the {@code bpmList} entries (Kelvin 2026-10-13: the song card
         * shows the range). Both are {@link #bpm} when the chart has no list; a list after {@code notes} is not read.
         */
        public float bpmMin, bpmMax;
        /** Chart constant as authored, e.g. "15.9" (may be null). */
        public String chartConstant;
        /** Difficulty label as authored, e.g. "Deep" (may be null). */
        public String level;
        /** True when the chart has a {@code notes} array. */
        public boolean hasNotes;
    }

    /**
     * Reads the header of a chart file.
     *
     * @throws IOException when the file cannot be read or is not a JSON object
     */
    public static Header read(FileHandle file) throws IOException {
        try (InputStream in = file.read()) {
            return read(in);
        } catch (RuntimeException e) {
            // FileHandle.read() throws GdxRuntimeException for missing files.
            throw new IOException("Cannot read chart header: " + file.path(), e);
        }
    }

    /**
     * Reads a chart header from a stream (the stream is not closed).
     *
     * @throws IOException when the stream is not a JSON object
     */
    public static Header read(InputStream in) throws IOException {
        Header h = new Header();
        try (JsonParser p = FACTORY.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Chart is not a JSON object");
            }
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken v = p.nextToken();
                if ("notes".equals(name)) {
                    h.hasNotes = v == JsonToken.START_ARRAY;
                    if (h.title != null && h.chartConstant != null) break;
                    p.skipChildren();
                    continue;
                }
                switch (name) {
                    case "title": h.title = text(p, v); break;
                    case "artist": h.artist = text(p, v); break;
                    case "illustrator": h.illustrator = text(p, v); break;
                    case "charter": h.charter = text(p, v); break;
                    case "chartConstant": h.chartConstant = text(p, v); break;
                    case "level": h.level = text(p, v); break;
                    case "bpm":
                        if (v == JsonToken.VALUE_NUMBER_FLOAT || v == JsonToken.VALUE_NUMBER_INT) {
                            h.bpm = p.getFloatValue();
                        } else if (v == JsonToken.VALUE_STRING) {
                            try {
                                h.bpm = Float.parseFloat(p.getText().trim());
                            } catch (NumberFormatException ignored) {
                                h.bpm = 0f;
                            }
                        }
                        break;
                    case "bpmList":
                        if (v == JsonToken.START_ARRAY) readTempoRange(p, h);
                        else p.skipChildren();
                        break;
                    default:
                        p.skipChildren();
                        break;
                }
                if (h.hasNotes && complete(h)) break;
            }
            if (t == null) throw new IOException("Truncated chart JSON");
        }
        if (h.bpm > 0f && Float.isFinite(h.bpm)) {
            h.bpmMin = h.bpmMin > 0f ? Math.min(h.bpmMin, h.bpm) : h.bpm;
            h.bpmMax = Math.max(h.bpmMax, h.bpm);
        }
        return h;
    }

    /** Folds the positive {@code bpm} values of a {@code bpmList} array into {@code h.bpmMin} / {@code h.bpmMax}. */
    private static void readTempoRange(JsonParser p, Header h) throws IOException {
        JsonToken e;
        while ((e = p.nextToken()) != JsonToken.END_ARRAY) {
            if (e == null) throw new IOException("Truncated chart JSON");
            if (e != JsonToken.START_OBJECT) {
                p.skipChildren();
                continue;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String k = p.currentName();
                JsonToken v = p.nextToken();
                if ("bpm".equals(k) && v != null && v.isNumeric()) {
                    float b = p.getFloatValue();
                    if (b > 0f && Float.isFinite(b)) {
                        h.bpmMin = h.bpmMin > 0f ? Math.min(h.bpmMin, b) : b;
                        h.bpmMax = Math.max(h.bpmMax, b);
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
    }

    private static boolean complete(Header h) {
        return h.title != null && h.chartConstant != null && h.artist != null && h.level != null;
    }

    private static String text(JsonParser p, JsonToken v) throws IOException {
        if (v == JsonToken.VALUE_NULL) return null;
        if (v.isScalarValue()) return p.getText();
        p.skipChildren();
        return null;
    }
}
