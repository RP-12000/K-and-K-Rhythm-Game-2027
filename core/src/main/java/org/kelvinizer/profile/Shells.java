package org.kelvinizer.profile;

import org.kelvinizer.play.ShellBreakdown;

/**
 * 贝壳：每局得到的贝壳（代替原来的经验，算法不变）和买单曲的默认价格；纯 Java。
 * 基础贝壳由定数、分数和谱面长度决定，分数只算到 100,000,000，超出的连击分和 PERFECT 加分不计。
 *
 * <p>Shells per play (Kelvin 2026-10-17: the former EXP, same formula and bonuses, DESIGN §11.6
 * with the §18.5 base on the 100 M scale) and the default price of a single. Pure Java.
 */
public final class Shells {
    /** Shells for the first play of a chart + difficulty. */
    public static final int BONUS_FIRST_PLAY = 40;
    /** Shells for a new best (not on a first play). */
    public static final int BONUS_NEW_BEST = 20;
    /** Shells for a new best that also improved the grade. */
    public static final int BONUS_NEW_BEST_GRADE = 40;
    /** Shells for the first FULL COMBO on a chart. */
    public static final int BONUS_FIRST_FC = 50;
    /** Shells for the first ALL RESONANCE on a chart. */
    public static final int BONUS_FIRST_AR = 100;
    /** Shells for the first completed play of the local day. */
    public static final int BONUS_DAILY = 50;
    /** Flat shells for the first tutorial completion. */
    public static final int TUTORIAL_FIRST = 150;
    /** Price of a single when its map gives none ({@code "price"} in the map data). */
    public static final int DEFAULT_PRICE = 300;

    private Shells() {
    }

    /** @return length factor L = clamp(totalNotes / 1700, 0.5, 1.2). */
    public static float lengthFactor(int totalNotes) {
        float l = totalNotes / 1700f;
        return Math.max(0.5f, Math.min(1.2f, l));
    }

    /**
     * @return base shells = round((30 + 6c) × (0.4 + 0.6 × (min(score, 100M) / 100M)²) × L)
     *         (the chain and PERFECT points above 100,000,000 are ignored)
     */
    public static int baseShells(float c, int score, int totalNotes) {
        double s = Math.max(0, Math.min(Depth.SCORE_CAP, score)) / (double) Depth.SCORE_CAP;
        double base = (30.0 + 6.0 * c) * (0.4 + 0.6 * s * s) * lengthFactor(totalNotes);
        return (int) Math.round(Math.max(0, base));
    }

    /**
     * Shells for one recorded play.
     *
     * @param c             chart constant
     * @param score         score of this play
     * @param totalNotes    notes in the chart
     * @param firstPlay     first play of this chart + difficulty
     * @param newBest       score beats the previous best (ignored on a first play)
     * @param gradeImproved the new best also improved the grade
     * @param firstFc       first FULL COMBO on this chart
     * @param firstAr       first ALL RESONANCE on this chart
     * @param firstOfDay    first completed play of the local day
     */
    public static ShellBreakdown shellsForPlay(float c, int score, int totalNotes, boolean firstPlay,
                                               boolean newBest, boolean gradeImproved, boolean firstFc,
                                               boolean firstAr, boolean firstOfDay) {
        int base = baseShells(c, score, totalNotes);
        int first = firstPlay ? BONUS_FIRST_PLAY : 0;
        int best = (!firstPlay && newBest) ? (gradeImproved ? BONUS_NEW_BEST_GRADE : BONUS_NEW_BEST) : 0;
        int fc = firstFc ? BONUS_FIRST_FC : 0;
        int ar = firstAr ? BONUS_FIRST_AR : 0;
        int daily = firstOfDay ? BONUS_DAILY : 0;
        return new ShellBreakdown(base, first, best, fc, ar, daily, 0);
    }

    /** @return the tutorial award: flat 150 on the first completion, nothing afterwards. */
    public static ShellBreakdown shellsForTutorial(boolean firstCompletion) {
        return firstCompletion ? new ShellBreakdown(0, 0, 0, 0, 0, 0, TUTORIAL_FIRST) : ShellBreakdown.NONE;
    }
}
