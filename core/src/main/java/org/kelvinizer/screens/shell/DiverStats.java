package org.kelvinizer.screens.shell;

import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ChartRecord;
import org.kelvinizer.profile.Grades;
import org.kelvinizer.profile.Profile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 玩家档案上一位潜水员的全部数字：贝壳、深度、游玩次数和时长、每个难度玩过 / 通过 / FULL COMBO / ALL RESONANCE
 * 的谱面数和最高分的分布（STATS 的分数直方图，每 100 万分一格），以及下潜曲线（在 TOP 10 页）。
 * 刷新时算一次；切换潜水员时在新旧两份数字之间插值，让数字在 400 ms 内滚动到新值。
 *
 * <p>Numbers of the Profile screen for one diver (DESIGN §8.2): shells and depth (Kelvin
 * 2026-10-17: they replace level, EXP and the rating), plays and play time, and per difficulty
 * (Kelvin 14:24) the charts played, cleared (D or better), FULL COMBO and ALL RESONANCE and how the
 * best scores of the played charts spread (the STATS score trace, 1,000,000 per bin), plus the dive
 * profile (TOP 10). Computed once per
 * refresh ({@link #compute}); {@link #lerp} blends two snapshots for the 400 ms count after a
 * switch (§8.3). Drawing reads the fields directly.
 */
public final class DiverStats {
    /** Number of difficulties. */
    public static final int DIFFS = 4;
    /** Lowest best score of a cleared chart: grade D or better (F is not a clear). */
    public static final int CLEAR_MIN_SCORE = Grades.GRADE_MIN_SCORE[Grades.F - 1];
    /**
     * Score trace bins: 1,000,000 wide from 60,000,000; the first also holds every lower score, the
     * last ({@code 100,000,000} and up) holds R and R+.
     */
    public static final int SCORE_BINS = 41, BIN_FLOOR = 60_000_000, BIN_WIDTH = 1_000_000;

    /** Shells and player depth in metres. */
    public float shells, depth;
    /** Plays and play time (all difficulties). */
    public float plays, playMs;
    /** Charts played / available per difficulty. */
    public final int[] diffPlayed = new int[DIFFS], diffTotal = new int[DIFFS];
    /** Charts cleared (D or better), with a FULL COMBO, with an ALL RESONANCE, per difficulty. */
    public final float[] cleared = new float[DIFFS], fullCombos = new float[DIFFS], allResonance = new float[DIFFS];
    /** Played charts per score bin ({@link #scoreBin}), per difficulty (the STATS trace). */
    public final int[][] scoreBins = new int[DIFFS][SCORE_BINS];
    /** Fullest bin and mean best score of the played charts, per difficulty. */
    public final int[] binPeak = new int[DIFFS];
    public final float[] meanScore = new float[DIFFS];
    /** Depth history in metres (oldest first). */
    public final float[] history = new float[Profile.DEPTH_HISTORY_MAX];
    /** Play time of each {@link #history} point (ms since epoch). */
    public final long[] historyT = new long[Profile.DEPTH_HISTORY_MAX];
    /** Valid entries of {@link #history}. */
    public int historyCount;

    /** Recomputes everything for {@code p} (allocates iterators; call on refresh only). */
    public DiverStats compute(Profile p, SongLibrary library) {
        shells = p.shells;
        depth = p.depthMeters();
        plays = p.playCount;
        playMs = p.stats.totalPlayMs;
        double[] sum = new double[DIFFS];
        for (int i = 0; i < DIFFS; i++) {
            diffPlayed[i] = 0;
            diffTotal[i] = 0;
            cleared[i] = 0f;
            fullCombos[i] = 0f;
            allResonance[i] = 0f;
            Arrays.fill(scoreBins[i], 0);
        }
        for (Map.Entry<String, Map<String, ChartRecord>> song : p.records.entrySet()) {
            SongMeta meta = library != null ? library.song(song.getKey()) : null;
            for (Map.Entry<String, ChartRecord> e : song.getValue().entrySet()) {
                ChartRecord r = e.getValue();
                if (r == null || r.plays <= 0) continue;
                Difficulty d = Difficulty.parse(e.getKey());
                if (d == null || (meta != null && !meta.has(d))) continue;
                int i = d.ordinal();
                diffPlayed[i]++;
                if (r.bestScore >= CLEAR_MIN_SCORE) cleared[i]++;
                if (r.fullCombo) fullCombos[i]++;
                if (r.allResonance) allResonance[i]++;
                scoreBins[i][scoreBin(r.bestScore)]++;
                sum[i] += r.bestScore;
            }
        }
        for (int i = 0; i < DIFFS; i++) {
            int peak = 0;
            for (int c : scoreBins[i]) peak = Math.max(peak, c);
            binPeak[i] = peak;
            meanScore[i] = diffPlayed[i] > 0 ? (float) (sum[i] / diffPlayed[i]) : 0f;
        }
        if (library != null) {
            List<SongMeta> songs = library.songs();
            for (int i = 0; i < songs.size(); i++) {
                SongMeta s = songs.get(i);
                if (s == library.tutorial()) continue;
                for (int d = 0; d < DIFFS; d++) {
                    Difficulty diff = Difficulty.byOrdinal(d);
                    if (diff != null && s.has(diff)) diffTotal[d]++;
                }
            }
        }
        historyCount = Math.min(history.length, p.depthHistory.size());
        int from = p.depthHistory.size() - historyCount;
        for (int i = 0; i < historyCount; i++) {
            Profile.DepthPoint pt = p.depthHistory.get(from + i);
            history[i] = pt != null ? pt.m : 0f;
            historyT[i] = pt != null ? pt.t : 0L;
        }
        return this;
    }

    /** Trace bin of {@code score}: below 61,000,000 → 0, 100,000,000 and up → the last. */
    public static int scoreBin(int score) {
        return Math.max(0, Math.min(SCORE_BINS - 1, (score - BIN_FLOOR) / BIN_WIDTH));
    }

    /** Copies every field of {@code o}. */
    public DiverStats set(DiverStats o) {
        shells = o.shells;
        depth = o.depth;
        plays = o.plays;
        playMs = o.playMs;
        System.arraycopy(o.diffPlayed, 0, diffPlayed, 0, DIFFS);
        System.arraycopy(o.diffTotal, 0, diffTotal, 0, DIFFS);
        System.arraycopy(o.cleared, 0, cleared, 0, DIFFS);
        System.arraycopy(o.fullCombos, 0, fullCombos, 0, DIFFS);
        System.arraycopy(o.allResonance, 0, allResonance, 0, DIFFS);
        for (int i = 0; i < DIFFS; i++) System.arraycopy(o.scoreBins[i], 0, scoreBins[i], 0, SCORE_BINS);
        System.arraycopy(o.binPeak, 0, binPeak, 0, DIFFS);
        System.arraycopy(o.meanScore, 0, meanScore, 0, DIFFS);
        System.arraycopy(o.history, 0, history, 0, history.length);
        System.arraycopy(o.historyT, 0, historyT, 0, historyT.length);
        historyCount = o.historyCount;
        return this;
    }

    /** Sets the counted numbers to {@code a + (b − a) × t}; lists and the trace take {@code b}. */
    public DiverStats lerp(DiverStats a, DiverStats b, float t) {
        set(b);
        shells = a.shells + (b.shells - a.shells) * t;
        depth = a.depth + (b.depth - a.depth) * t;
        plays = a.plays + (b.plays - a.plays) * t;
        playMs = a.playMs + (b.playMs - a.playMs) * t;
        for (int i = 0; i < DIFFS; i++) {
            cleared[i] = a.cleared[i] + (b.cleared[i] - a.cleared[i]) * t;
            fullCombos[i] = a.fullCombos[i] + (b.fullCombos[i] - a.fullCombos[i]) * t;
            allResonance[i] = a.allResonance[i] + (b.allResonance[i] - a.allResonance[i]) * t;
        }
        return this;
    }
}
