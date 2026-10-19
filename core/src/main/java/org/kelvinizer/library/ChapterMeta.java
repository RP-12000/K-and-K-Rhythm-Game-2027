package org.kelvinizer.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌曲地图上的一个章节（深度层）：中英文层名、副标题、深度范围（米）、是否未探明和歌曲列表。
 * 来自 {@code Charts/index.json}，曲库加载后不再改变。
 *
 * <p>One chapter (depth layer) of the song map, from {@code Charts/index.json}
 * (ARCHITECTURE §8, DESIGN §3.1 and Appendix A.16). Immutable after
 * {@link SongLibrary#load()}.
 */
public final class ChapterMeta {
    private final String id;
    private final int index;
    private final String title;
    private final String titleZh;
    private final String subtitle;
    private final int depthTop;
    private final int depthBottom;
    private final boolean uncharted;
    private final String numeral;
    private final List<SongMeta> songs = new ArrayList<>();
    private final List<SongMeta> songsView = Collections.unmodifiableList(songs);

    ChapterMeta(String id, int index, String title, String titleZh, String subtitle, int depthTop,
                int depthBottom, boolean uncharted) {
        this.id = id;
        this.index = index;
        this.title = title != null ? title : id;
        this.titleZh = titleZh != null ? titleZh : this.title;
        this.subtitle = subtitle != null ? subtitle : "";
        this.depthTop = depthTop;
        this.depthBottom = Math.max(depthTop, depthBottom);
        this.uncharted = uncharted;
        this.numeral = (index + 1 < 10 ? "0" : "") + (index + 1);
    }

    void addSong(SongMeta song) {
        songs.add(song);
    }

    /** @return manifest id, e.g. "c2". */
    public String id() { return id; }
    /** @return position in the manifest (0-based). */
    public int index() { return index; }
    /** @return English layer name, e.g. "TWILIGHT". */
    public String title() { return title; }
    /** @return Chinese layer name, e.g. "暮光层". */
    public String titleZh() { return titleZh; }
    /** @return subtitle, e.g. "Chapter II" (may be empty). */
    public String subtitle() { return subtitle; }
    /** @return depth of the top of the layer in metres. */
    public int depthTop() { return depthTop; }
    /** @return depth of the bottom of the layer in metres. */
    public int depthBottom() { return depthBottom; }
    /** @return true for uncharted layers (no songs yet, DESIGN §3.11). */
    public boolean uncharted() { return uncharted; }
    /** @return two-digit numeral for the layer header, e.g. "02". */
    public String numeral() { return numeral; }
    /** @return songs in map order (unmodifiable). */
    public List<SongMeta> songs() { return songsView; }
    /** @return number of songs. */
    public int songCount() { return songs.size(); }

    /** @return total number of difficulty charts in this chapter. */
    public int chartCount() {
        int n = 0;
        for (int i = 0; i < songs.size(); i++) n += songs.get(i).chartCount();
        return n;
    }

    @Override
    public String toString() {
        return id + "(" + title + ", " + depthTop + "-" + depthBottom + " m, " + songs.size() + " songs"
            + (uncharted ? ", uncharted" : "") + ")";
    }
}
