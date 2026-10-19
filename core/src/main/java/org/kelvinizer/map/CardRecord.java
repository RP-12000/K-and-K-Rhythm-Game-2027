package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ChartRecord;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Grades;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 歌曲卡片上一张谱面的成绩区：最高分、评级、通关标记、准确率、这张谱的深度（当前/全共鸣时）、本机排名、游玩次数、最近走势和小目标。
 * 只在谱面、潜水员或存档修订号变化时重新计算，绘制时直接读缓存好的字符串。
 *
 * <p>The record block of the song card for one chart (DESIGN §4.6): best score with its leading
 * zeros, grade, clear badge, accuracy, the chart depth as current / max (Kelvin 2026-10-17: metres
 * replace the rating), local rank, plays, the recent sparkline and the micro-goal (§11.5). Computed only when the chart, the diver or the
 * profile revision changes ({@link #update}); drawing reads the cached strings. No allocation
 * after construction.
 */
final class CardRecord {
    /** Micro-goal kinds (GOAL_PURE is unused; GOAL_NEXT also carries the MAX prefix). */
    static final int GOAL_NONE = 0, GOAL_NEXT = 1, GOAL_PURE = 2, GOAL_MAXED = 3;

    /** True when the chart has at least one recorded play. */
    boolean played;
    /** Best score and grade index (0 = R+ … 14 = F, {@link Grades#GRADES}; −1 unplayed). */
    int best, grade = -1;
    /** Clear flags. */
    boolean fullCombo, allResonance;
    /** Recorded plays. */
    int plays;
    /** Best score as {@code 098,123,456} and the count of its dim leading chars. */
    final StringBuilder bestText = new StringBuilder(14);
    int bestLead;
    /** {@code 99.12%} (value only; the {@code ACC} label is drawn separately). */
    final StringBuilder accText = new StringBuilder(10);
    /**
     * Played: the chart depth over its maximum (score 100,000,000), e.g. {@code 640 / 1,000 m},
     * drawn after the accuracy. Unplayed: the first-dive line {@code FIRST DIVE · +40 SHELLS}.
     */
    final StringBuilder depthText = new StringBuilder(40);
    /** Depth line colour (accent for a first dive). */
    boolean firstDive;
    /** {@code PLAYS 12}, then {@code  · LOCAL #1 / 3} when 2 or more local profiles have a record. */
    final StringBuilder playsText = new StringBuilder(32);
    /** Recent scores, oldest first (the sparkline draws newest at the right). */
    final int[] recent = new int[ChartRecord.RECENT_MAX];
    int recentN;
    /** Micro-goal: kind, the prefix ({@code NEXT}, {@code MAX}, {@code MAXED}) and the rest ({@code -2,500 m AT 97,350,000}). */
    int goal = GOAL_NONE;
    String goalPrefix = "";
    final StringBuilder goalRest = new StringBuilder(32);

    // cache keys -------------------------------------------------------------------------
    private SongMeta keySong;
    private Difficulty keyDiff;
    private Profile keyProfile;
    private int keyRev = Integer.MIN_VALUE;
    private final float[] top10 = new float[Depth.TOP_N];
    private final Depth.Goal next = new Depth.Goal();
    private final ProfileStore.LocalRank local = new ProfileStore.LocalRank();

    /**
     * Recomputes the block for (song, d) when it, the active profile or the store revision
     * changed. Returns true when it was recomputed.
     */
    boolean update(ProfileStore store, SongMeta song, Difficulty d) {
        Profile p = store.active();
        int rev = store.revision();
        if (song == keySong && d == keyDiff && p == keyProfile && rev == keyRev) return false;
        keySong = song;
        keyDiff = d;
        keyProfile = p;
        keyRev = rev;
        compute(store, p, song, d);
        return true;
    }

    /** Forces the next {@link #update} to recompute. */
    void invalidate() {
        keySong = null;
    }

    private void compute(ProfileStore store, Profile p, SongMeta song, Difficulty d) {
        ChartRecord r = p != null && song != null ? p.record(song.id(), d) : null;
        played = r != null && r.plays > 0;
        best = played ? r.bestScore : 0;
        grade = played ? Grades.gradeIndex(r.bestGrade) : -1;
        if (grade >= Theme.GRADES.length) grade = played ? Grades.gradeIndex(Grades.gradeOf(best)) : -1;
        fullCombo = played && r.fullCombo;
        allResonance = played && r.allResonance;
        plays = played ? r.plays : 0;

        bestText.setLength(0);
        bestLead = 0;
        appendScore(bestText, best);
        for (int i = 0; i < bestText.length(); i++) {
            char c = bestText.charAt(i);
            if (c != '0' && c != ',') break;
            bestLead = i + 1;
        }
        if (best == 0) bestLead = bestText.length();

        accText.setLength(0);
        if (played) Fonts.appendTruncated(accText, Math.max(0f, Math.min(1f, r.bestAccuracy)) * 100.0, 2).append('%');

        depthText.setLength(0);
        firstDive = !played;
        if (played) {
            float max = song != null ? Depth.maxChartDepth(song.constant(d)) : 0f;
            // depths read negative (below the surface): -4,000 / -5,760 m
            Fonts.appendGrouped(depthText, -Depth.meters(Math.max(0f, r.bestDepth))).append(" / ");
            Fonts.appendGrouped(depthText, -Depth.meters(max)).append(" m");
        } else {
            depthText.append("FIRST DIVE · +40 SHELLS");
        }

        playsText.setLength(0);
        if (played) {
            playsText.append("PLAYS ").append(plays);
            store.localRank(p, song.id(), d, local);
            if (local.count >= 2 && local.rank > 0) {
                playsText.append(" · LOCAL #").append(local.rank).append(" / ").append(local.count);
            }
        }

        recentN = 0;
        if (played && r.recent != null) {
            int n = Math.min(recent.length, r.recent.size());
            // r.recent is newest first: store oldest first
            for (int i = n - 1; i >= 0; i--) {
                Integer v = r.recent.get(i);
                recent[recentN++] = v != null ? v : 0;
            }
        }

        goal = GOAL_NONE;
        goalPrefix = "";
        goalRest.setLength(0);
        if (p != null && song != null && song.has(d)) {
            store.topDepths(p, top10);
            float c = song.constant(d);
            float cur = played ? r.bestDepth : -1f;
            if (Depth.nextGoal(c, played ? best : -1, cur, top10, next)) {
                // the player depth this chart can reach: a round 100 / 50 / 10 m, else its deepest (MAX)
                goal = GOAL_NEXT;
                goalPrefix = next.max ? "MAX" : "NEXT";
                Fonts.appendGrouped(goalRest, -next.meters).append(" m AT ");
                Fonts.appendGrouped(goalRest, next.score);
            } else {
                goal = GOAL_MAXED;
                goalPrefix = "MAXED";
            }
        }
    }

    /**
     * Appends a score as 9 digits with grouping ({@code 098,123,456}; an all-PERFECT run reads
     * {@code 100,010,xxx}, DESIGN §18.1). Stored bests are clamped to
     * {@link ChartRecord#MAX_STORED_SCORE} so nine digits always suffice.
     */
    static StringBuilder appendScore(StringBuilder sb, int score) {
        int v = Math.max(0, Math.min(ChartRecord.MAX_STORED_SCORE, score));
        int div = 100_000_000;
        for (int i = 8; i >= 0; i--) {
            sb.append((char) ('0' + (v / div) % 10));
            if (i == 6 || i == 3) sb.append(',');
            div /= 10;
        }
        return sb;
    }

    /** Grade colour into {@code out} (white when unplayed). */
    Color gradeColor(float time, Color out) {
        if (grade >= 0 && grade < Theme.GRADES.length) return Theme.gradeColor(Theme.GRADES[grade], time, out);
        return out.set(Theme.INK1);
    }
}
