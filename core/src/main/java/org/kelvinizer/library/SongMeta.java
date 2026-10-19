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
    float previewStartMs = -1f;
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
    /** @return the display chart's charter (SHALLOW, else SURFACE); prefer {@link #charter(Difficulty)} for one chart. */
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

    /** @return the whole number shown for the constant, e.g. "15" for "15.9" ({@link ConstantLabel}; null if absent). */
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

    /** @return the hardest difficulty present (SURFACE if none). */
    public Difficulty hardest() {
        for (int i = present.length - 1; i >= 0; i--) if (present[i]) return Difficulty.byOrdinal(i);
        return Difficulty.SURFACE;
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

    /**
     * @return where the preview starts in ms: the manifest's {@code previewStartMs} when set,
     *         else 30 % of the duration, else 0 when the duration is unknown
     */
    public float previewStartMs() {
        if (previewStartMs >= 0f) return previewStartMs;
        return durationMs > 0f ? durationMs * 0.3f : 0f;
    }

    @Override
    public String toString() {
        return "SongMeta{" + id + " '" + title + "' " + chapterId + "#" + indexInChapter + " charts=" + chartCount()
            + " music=" + (music != null ? music.name() : "none") + " jacket=" + (jacket != null ? jacket.name() : "none")
            + " durMs=" + (int) durationMs + "}";
    }
}
