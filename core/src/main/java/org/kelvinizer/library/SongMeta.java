package org.kelvinizer.library;

import com.badlogic.gdx.files.FileHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * 一首歌的头信息（只读谱面头，不含音符）：标题、曲师、各难度的定数和谱面文件、音乐、曲绘、试听起点等。
 * id 就是歌曲文件夹名，也是所有存档记录的键。
 *
 * <p>Header-only description of one song folder (ARCHITECTURE §8). Built by
 * {@link SongLibrary#load()} from the chart headers; notes are only parsed by
 * {@link SongLibrary#loadChart(SongMeta, Difficulty)}.
 *
 * <p>The song id is the chart folder name (e.g. "Song 1", "marsh mally") and is the key of
 * every saved record. Per-difficulty data is stored in arrays, so the accessors never allocate.
 */
public final class SongMeta {
    /** Preview length when no end is set (ms). */
    public static final float DEFAULT_PREVIEW_MS = 20_000f;

    private final String id;
    private final FileHandle folder;
    String title = "";
    String artist = "";
    String illustrator = "";
    String charter = "";
    float bpm;
    /** Lowest and highest tempo of the display chart (0 = only {@link #bpm} is known). */
    float bpmMin, bpmMax;
    String chapterId = "";
    int chapterIndex;
    int indexInChapter;
    int globalIndex;
    final boolean[] present = new boolean[Difficulty.count()];
    final float[] constants = new float[Difficulty.count()];
    final String[] constantText = new String[Difficulty.count()];
    /** The constant as shown ({@link ConstantLabel}): its whole number and superscript mark. */
    final String[] constantBase = new String[Difficulty.count()], constantMark = new String[Difficulty.count()];
    final String[] chartTitles = new String[Difficulty.count()];
    /** Charter written in each difficulty's chart file (null or "" when absent). */
    final String[] charters = new String[Difficulty.count()];
    final FileHandle[] chartFiles = new FileHandle[Difficulty.count()];
    FileHandle jacket;
    FileHandle music;
    FileHandle resultMusic;
    FileHandle background;
    /** Preview start and end from {@code song.json} or the manifest (ms; NaN = not set). */
    float previewStartMs = Float.NaN, previewEndMs = Float.NaN;
    /** Jacket framing from {@code song.json} or the manifest: {x, y, w, h} fractions of the image, y from the top. */
    float[] jacket16x9, jacketCircle;
    float resultMusicStartMs;
    float durationMs;
    float mapNudgeX;
    float mapNudgeY;

    SongMeta(String id, FileHandle folder) {
        this.id = id;
        this.folder = folder;
    }

    /** @return song id = folder name. */
    public String id() { return id; }
    /** @return the song folder. */
    public FileHandle folder() { return folder; }
    /** @return display title (from the SHALLOW chart, else SURFACE). */
    public String title() { return title; }
    /** @return artist. */
    public String artist() { return artist; }
    /** @return illustrator. */
    public String illustrator() { return illustrator; }
    /**
     * @return the display chart's charter (SHALLOW, else SURFACE); prefer {@link #charter(Difficulty)} for one chart.
     */
    public String charter() { return charter; }
    /** @return beats per minute. */
    public float bpm() { return bpm; }
    /** @return the lowest tempo of the song's charts ({@link #bpm} when they have no tempo changes). */
    public float bpmMin() { return bpmMin > 0f ? bpmMin : bpm; }
    /** @return the highest tempo of the song's charts ({@link #bpm} when they have no tempo changes). */
    public float bpmMax() { return bpmMax > 0f ? bpmMax : bpm; }
    /** @return manifest chapter id, e.g. "c1". */
    public String chapterId() { return chapterId; }
    /** @return index of the chapter in {@link SongLibrary#chapters()}. */
    public int chapterIndex() { return chapterIndex; }
    /** @return position inside its chapter (0-based). */
    public int indexInChapter() { return indexInChapter; }
    /** @return position in {@link SongLibrary#songs()}. */
    public int globalIndex() { return globalIndex; }

    /** @return true when the song has a chart for {@code d}. */
    public boolean has(Difficulty d) {
        return present[d.ordinal()];
    }

    /** @return the chart constant for {@code d} (1.0 if unparsable, 0 if the chart is absent). */
    public float constant(Difficulty d) {
        return constants[d.ordinal()];
    }

    /** @return the chart constant as authored, e.g. "15.9" (null if absent). */
    public String constantText(Difficulty d) {
        return constantText[d.ordinal()];
    }

    /**
     * @return the whole number shown for the constant, e.g. "15" for "15.9" ({@link ConstantLabel}; null if absent).
     */
    public String constantBase(Difficulty d) {
        return constantBase[d.ordinal()];
    }

    /** @return the superscript mark shown after {@link #constantBase}: "-", "" or "+" ("" if absent). */
    public String constantMark(Difficulty d) {
        String m = constantMark[d.ordinal()];
        return m != null ? m : ConstantLabel.NONE;
    }

    /** @return the chart file for {@code d} (null if absent). */
    public FileHandle chartFile(Difficulty d) {
        return chartFiles[d.ordinal()];
    }

    /** @return the title stored in that difficulty's chart file (null if absent). */
    public String chartTitle(Difficulty d) {
        return chartTitles[d.ordinal()];
    }

    /**
     * @return the charter written in that difficulty's chart file; the song's {@link #charter()}
     *         when that file names none (never null)
     */
    public String charter(Difficulty d) {
        String c = charters[d.ordinal()];
        return c != null && !c.trim().isEmpty() ? c : charter;
    }

    /**
     * @return every chart's charter once, in difficulty order, joined by {@code " / "}
     *         (e.g. {@code Integers / Real Numbers}); "" when no chart names one
     */
    public String chartersText() {
        List<String> names = new ArrayList<>(Difficulty.count());
        for (Difficulty d : Difficulty.values()) {
            if (!present[d.ordinal()]) continue;
            String c = charter(d);
            if (!c.isEmpty() && !names.contains(c)) names.add(c);
        }
        return String.join(" / ", names);
    }

    /** @return number of difficulty charts. */
    public int chartCount() {
        int n = 0;
        for (boolean p : present) if (p) n++;
        return n;
    }

    /** @return the easiest difficulty present (SURFACE if none). */
    public Difficulty easiest() {
        for (int i = 0; i < present.length; i++) if (present[i]) return Difficulty.byOrdinal(i);
        return Difficulty.SURFACE;
    }

    /** @return jacket image ({@code jacket.png} preferred, else the first image), or null. */
    public FileHandle jacket() { return jacket; }
    /** @return song music ({@code <id>.(ogg|mp3|wav)} preferred, else the first audio file), or null. */
    public FileHandle music() { return music; }
    /** @return optional per-song result music ({@code result.*}), or null. */
    public FileHandle resultMusic() { return resultMusic; }
    /** @return optional background image hook ({@code bg.png}), or null. */
    public FileHandle background() { return background; }
    /** @return music length in ms from the WAV header, or 0 when unknown. */
    public float durationMs() { return durationMs; }
    /** @return where the result music starts in ms (manifest {@code resultMusicStartMs}, default 0). */
    public float resultMusicStartMs() { return resultMusicStartMs; }
    /** @return map node x nudge from the manifest ({@code map.nudgeX}). */
    public float mapNudgeX() { return mapNudgeX; }
    /** @return map node y nudge from the manifest ({@code map.nudgeY}). */
    public float mapNudgeY() { return mapNudgeY; }
    /** @return the 16:9 framing of the jacket {x, y, w, h} (fractions of the image, y from the top), or null */
    public float[] jacket16x9() { return jacket16x9; }
    /** @return the square round the jacket's circle {x, y, w, h} (fractions, y from the top), or null */
    public float[] jacketCircle() { return jacketCircle; }

    /**
     * @return where the preview starts in ms: {@code previewStartMs} when set (before 0 the preview is silent until
     *         the song's start), else {@link #defaultPreviewStartMs}
     */
    public float previewStartMs() {
        return Float.isNaN(previewStartMs) ? defaultPreviewStartMs(durationMs) : previewStartMs;
    }

    /**
     * @return where the preview ends and loops in ms: {@code previewEndMs} when after the start (past the music's end
     *         the preview is silent until it), else {@link #DEFAULT_PREVIEW_MS} after the start
     */
    public float previewEndMs() {
        float start = previewStartMs();
        return previewEndMs > start ? previewEndMs : start + DEFAULT_PREVIEW_MS;
    }

    /** @return the default preview start: 30 % of {@code durationMs}, or 0 when it is unknown (Kelvin 2026-10-21). */
    public static float defaultPreviewStartMs(float durationMs) {
        return durationMs > 0f ? durationMs * 0.3f : 0f;
    }

    /**
     * Crops an image of {@code imageW} × {@code imageH} for a slot of aspect {@code aspect} (width / height): the
     * framing {@code box} ({x, y, w, h} fractions, y from the top; null = the whole image), centre-cropped to the
     * slot's shape (Kelvin 2026-10-21 18:32). Writes {u0, v0, u1, v1} (fractions, v from the top) into {@code out}.
     */
    public static void crop(float[] box, float imageW, float imageH, float aspect, float[] out) {
        float x = 0f, y = 0f, w = 1f, h = 1f;
        if (box != null) {
            x = box[0];
            y = box[1];
            w = box[2];
            h = box[3];
        }
        if (imageW > 0f && imageH > 0f && aspect > 0f) {
            float pw = w * imageW, ph = h * imageH;
            if (pw > ph * aspect) {
                float cut = (w - ph * aspect / imageW) * 0.5f;
                x += cut;
                w -= 2f * cut;
            } else {
                float cut = (h - pw / aspect / imageH) * 0.5f;
                y += cut;
                h -= 2f * cut;
            }
        }
        out[0] = x;
        out[1] = y;
        out[2] = x + w;
        out[3] = y + h;
    }

    /**
     * The square of an {@code imageW} × {@code imageH} jacket shown in a round or square slot (pixels, y from the
     * top): the square round the song's circle framing, else the centred one. Writes {x, y, side} into {@code out}.
     */
    public void circleSquare(int imageW, int imageH, int[] out) {
        float[] uv = new float[4];
        crop(jacketCircle, imageW, imageH, 1f, uv);
        int side = Math.min(Math.round((uv[2] - uv[0]) * imageW), Math.round((uv[3] - uv[1]) * imageH));
        out[2] = Math.max(1, side);
        out[0] = Math.max(0, Math.min(Math.round(uv[0] * imageW), imageW - out[2]));
        out[1] = Math.max(0, Math.min(Math.round(uv[1] * imageH), imageH - out[2]));
    }

    @Override
    public String toString() {
        return "SongMeta{" + id + " '" + title + "' " + chapterId + "#" + indexInChapter + " charts=" + chartCount()
            + " music=" + (music != null ? music.name() : "none")
            + " jacket=" + (jacket != null ? jacket.name() : "none") + " durMs=" + (int) durationMs + "}";
    }
}
