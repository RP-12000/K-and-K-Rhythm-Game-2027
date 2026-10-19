package org.kelvinizer.map;

import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ChartRecord;
import org.kelvinizer.profile.Profile;

/**
 * “所有曲目”的筛选：NEW（这个难度没玩过）、FULL COMBO、RESONATED（全共鸣，2026-10-18 14:29 前叫 ALL RESONANCE）三选一，
 * 或者不筛选；都按当前镜头难度判断，没有这个难度谱面的歌一律不符合。不符合的歌不放进网格。
 *
 * <p>The ALL SONGS filter (DESIGN §3.10; Kelvin 2026-10-16): none, or one of NEW (the lens chart
 * never played), FULL COMBO and RESONATED (ALL RESONANCE until 2026-10-18 14:29; on the lens chart).
 * A song without a chart for the lens never matches. {@link MapWorld} leaves the songs it rejects out
 * of the grid.
 */
final class MapFilter {
    /** Filter values (index into {@link #LABELS}). */
    static final int NONE = 0, NEW = 1, FULL_COMBO = 2, ALL_RESONANCE = 3;
    /** Labels of the values (the panel's chips use 1–3). */
    static final String[] LABELS = {"ALL", "NEW", "FULL COMBO", "RESONATED"};

    private int status = NONE;

    /** Current value (one of the constants). */
    int status() {
        return status;
    }

    /** Selects {@code s}; selecting the current value again clears the filter. */
    void toggle(int s) {
        if (s <= NONE || s > ALL_RESONANCE) return;
        status = s == status ? NONE : s;
    }

    /** True when a value is selected. */
    boolean active() {
        return status != NONE;
    }

    /** True when {@code song} matches for diver {@code p} on difficulty {@code lens}. */
    boolean accepts(SongMeta song, Profile p, Difficulty lens) {
        if (status == NONE) return true;
        if (song == null || !song.has(lens)) return false;
        ChartRecord r = p != null ? p.record(song.id(), lens) : null;
        boolean played = r != null && r.plays > 0;
        switch (status) {
            case NEW:
                return !played;
            case FULL_COMBO:
                return played && r.fullCombo;
            default:
                return played && r.allResonance;
        }
    }
}
