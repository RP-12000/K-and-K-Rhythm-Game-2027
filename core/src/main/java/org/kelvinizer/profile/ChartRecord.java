package org.kelvinizer.profile;

import org.kelvinizer.play.Judgement;

import java.util.ArrayList;
import java.util.List;

/**
 * 一位潜水员在一张谱面（歌曲 + 难度）上的记录：最高分、准确率、最大连击、评级、游玩次数和最近的分数。
 * 也记录是否达成过 FULL COMBO / ALL RESONANCE，并负责把旧版 10,000,000 分制的记录换算到 100,000,000 分制。
 *
 * <p>A diver's record on one chart (song + difficulty), stored in {@link Profile#records}
 * (ARCHITECTURE §7). Plain public fields, bound by {@link PlainJson}.
 */
public class ChartRecord {
    /** Number of recent scores kept. */
    public static final int RECENT_MAX = 6;
    /**
     * Highest score accepted from a saved record: the 100,000,000 base plus a PERFECT bonus of at
     * most one point per note (ARCHITECTURE §18), bounded generously.
     */
    public static final int MAX_STORED_SCORE = 2 * Judgement.MAX_SCORE - 1;
    /** Old (scale 1) score cap: the 10,000,000 scale of earlier builds and the original game. */
    public static final int OLD_SCALE_MAX = 10_000_000;
    /** Factor from the old 10,000,000 scale to the 100,000,000 scale. */
    public static final int SCALE_FACTOR = 10;
    /** Grade strings of the old scale and of schema 1 profiles, best first (only for the migrations). */
    private static final String[] OLD_GRADES = {"PURE", "S+", "S", "A+", "A", "B", "C", "D"};

    /** Best score (base + PERFECT bonus, 100,000,000 scale; may exceed 100,000,000). */
    public int bestScore;
    /** Best accuracy 0..1 (independent of the best score's run). */
    public float bestAccuracy;
    /** Best max combo. */
    public int maxCombo;
    /** A FULL COMBO was achieved at least once. */
    public boolean fullCombo;
    /** An ALL RESONANCE (PURE) was achieved at least once. */
    public boolean allResonance;
    /** Best grade string ("R+".."F", see {@link Grades}). */
    public String bestGrade;
    /** Recorded plays. */
    public int plays;
    /** Wall-clock time of the first recorded play. */
    public long firstPlayedAt;
    /** Wall-clock time of the last recorded play. */
    public long lastPlayedAt;
    /**
     * Recent scores, newest first, at most {@link #RECENT_MAX}. A real score of 0 is kept (there
     * is no fail state); the legacy zero padding of {@code recentAttempts} is dropped by
     * {@link LegacyUserDataImporter}, not here.
     */
    public List<Integer> recent = new ArrayList<>();
    /** Depth of the best score in metres ({@link Depth#chartDepth}); recomputed on load if a constant changes. */
    public float bestDepth;

    /** Creates an empty record. */
    public ChartRecord() {
    }

    /** Adds a score to {@link #recent} (newest first, capped). */
    public void pushRecent(int score) {
        if (recent == null) recent = new ArrayList<>();
        recent.add(0, score);
        while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
    }

    /** Repairs nulls and ranges after loading untrusted JSON. */
    public void sanitize() {
        if (recent == null) recent = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Integer v = recent.get(i);
            if (v == null || v < 0) recent.remove(i);
            else if (v > MAX_STORED_SCORE) recent.set(i, MAX_STORED_SCORE);
        }
        while (recent.size() > RECENT_MAX) recent.remove(recent.size() - 1);
        bestScore = Math.max(0, Math.min(MAX_STORED_SCORE, bestScore));
        bestAccuracy = Settings.clamp(bestAccuracy, 0f, 1f);
        plays = Math.max(plays, bestScore > 0 ? 1 : 0);
        if (bestGrade == null && plays > 0) bestGrade = Grades.gradeOf(bestScore);
        if (!(bestDepth >= 0f) || Float.isInfinite(bestDepth)) bestDepth = 0f;
    }

    /**
     * Converts an old-scale (10,000,000) score to the 100,000,000 scale: × 10, clamped to
     * 0..100,000,000 (old scores carry no PERFECT bonus).
     */
    public static int toNewScale(int oldScore) {
        long v = (long) Math.max(0, Math.min(OLD_SCALE_MAX, oldScore)) * SCALE_FACTOR;
        return (int) Math.min(Judgement.MAX_SCORE, v);
    }

    /**
     * True when an old-scale score was a PURE (every note RESONANCE) run: a stored grade is
     * authoritative ("PURE" or not), the ALL RESONANCE flag implies a PURE run, and only a record
     * without a known grade falls back to the old maximum (10,000,000 was reachable only by PURE).
     */
    static boolean wasPureOnOldScale(String grade, boolean allResonance, int oldScore) {
        if (allResonance || "PURE".equals(grade)) return true;
        for (String g : OLD_GRADES) {
            if (g.equals(grade)) return false;   // a known non-PURE grade
        }
        return oldScore >= OLD_SCALE_MAX;
    }

    /**
     * Converts one old-scale score: {@link #toNewScale} (× 10, clamped 0..100,000,000), and a
     * non-PURE score stays below 100,000,000 (an honest one is at most 99,999,990; more means a
     * corrupt value, which must not read as PURE on the new scale).
     */
    static int toNewScale(int oldScore, boolean pure) {
        int v = toNewScale(oldScore);
        return pure ? v : Math.min(v, Judgement.MAX_SCORE - 1);
    }

    /**
     * Converts this record from the old 10,000,000 scale (ARCHITECTURE §18): best and recent
     * scores × 10 (capped at 100,000,000; a non-PURE best below it, see
     * {@link #wasPureOnOldScale}) and the best grade recomputed from the new best score; the
     * best depth is left to {@code ProfileStore}, which recomputes every depth from the library
     * at load. Null and negative recent entries are dropped here, as
     * {@link #sanitize()} would have dropped them on the old scale (they must not become real
     * zeros). Call it exactly once per record; {@link Profile#migrateScoreScale()} guards that.
     */
    void migrateFromOldScale() {
        int oldBest = bestScore;
        boolean wasPure = wasPureOnOldScale(bestGrade, allResonance, oldBest);
        bestScore = toNewScale(oldBest, wasPure);
        if (recent != null) {
            for (int i = recent.size() - 1; i >= 0; i--) {
                Integer v = recent.get(i);
                if (v == null || v < 0) recent.remove(i);
                else recent.set(i, toNewScale(v));
            }
        }
        if (bestGrade != null || plays > 0) bestGrade = Grades.gradeOf(bestScore);
    }

    /** @return a deep copy. */
    public ChartRecord copy() {
        ChartRecord r = new ChartRecord();
        r.bestScore = bestScore;
        r.bestAccuracy = bestAccuracy;
        r.maxCombo = maxCombo;
        r.fullCombo = fullCombo;
        r.allResonance = allResonance;
        r.bestGrade = bestGrade;
        r.plays = plays;
        r.firstPlayedAt = firstPlayedAt;
        r.lastPlayedAt = lastPlayedAt;
        r.recent = new ArrayList<>(recent != null ? recent : new ArrayList<Integer>());
        r.bestDepth = bestDepth;
        return r;
    }
}
