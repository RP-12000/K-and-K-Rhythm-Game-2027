package org.kelvinizer.profile;

import org.kelvinizer.play.Judgement;

import java.util.List;

/**
 * 深度（米），代替原来的评分 rating（Kelvin 2026-10-17）：每张谱的深度 = 40 米 × max(0, 定数 + (分数 − 9600 万) / 200 万)²，
 * 9600 万分时正好是 40 × 定数²，1 亿分时定数加 2，超过 1 亿的部分不算；低于 9600 万时定数往下减，减到 0 为止
 * （14:10 取消分数系数，15:22 更正为低分也扣）；
 * 潜水员的深度 = 最深的 10 张谱的平均（不够 10 张时空位算 0）。
 * 深度分档：0、2000、4000、6000、7000、8000、9000 米，之后每 500 米一档；头像外观按档解锁。
 * 这里的深度都是正数（海面以下多少米），界面上前面加负号显示（14:20），比如 −4,000 m。
 * 也负责地图卡片上的 NEXT 目标；纯 Java。
 *
 * <p>Depth in metres, which replaced the chart rating ρ and the player rating R (Kelvin
 * 2026-10-17 04:42; formula of 14:10 as corrected at 15:22): chart depth = 40 m × max(0, c + bonus)²,
 * where bonus = (min(score, 100,000,000) − 96,000,000) / 2,000,000: −3 at 90 M, 0 at 96 M, 2 at 100 M,
 * so a score below 96 M lowers the constant (down to a depth of 0).
 * Player depth = the mean of the {@link #TOP_N} deepest charts (empty slots count 0). Depth tiers
 * start at 0, 2,000, 4,000, 6,000, 7,000, 8,000 and 9,000 m, then every 500 m ({@link #tierMeters});
 * tiers 1 to 11 unlock the avatar options. Depths here are positive metres below the surface; every
 * screen shows them with a minus sign ({@code -4,000 m}, Kelvin 14:20). Also the card's NEXT goal.
 * Pure Java, no allocation in the numeric helpers.
 */
public final class Depth {
    /** Number of deepest charts averaged into the player depth. */
    public static final int TOP_N = 10;
    /** Metres per (constant + bonus)². */
    public static final float METERS_PER_C2 = 40f;
    /** Score cap of depth and shells: the chain and PERFECT points above 100,000,000 are ignored. */
    public static final int SCORE_CAP = Judgement.MAX_SCORE;
    /** Score where the bonus is 0 (the chart counts 40 × c²); below it the constant shrinks, above it grows. */
    public static final int BONUS_FROM = 96_000_000;
    /** Score per 1 of constant bonus away from {@link #BONUS_FROM}. */
    public static final int SCORE_PER_BONUS = 2_000_000;
    /** Step of the NEXT goal's score (it reads like 97,350,000). */
    public static final int GOAL_SCORE_STEP = 10_000;
    /** Round player depths (m) the NEXT goal tries, coarsest first (Kelvin 15:32). */
    private static final int[] GOAL_ROUND = {100, 50, 10};

    /** First depths (m) of the tiers before the 500 m steps (tier 0 starts at 0 m). */
    private static final int[] TIER_START = {0, 2000, 4000, 6000, 7000, 8000, 9000};
    /** Depth (m) between tiers after the last fixed one. */
    public static final int TIER_STEP = 500;

    /** Labels of the avatar options unlocked by tiers 1, 2, 3 … (the result alert); see {@link AvatarSpec}. */
    private static final String[] UNLOCK_LABELS = {
        "NEW PATTERN · WAVES",
        "NEW FRAME · DASHED SONAR",
        "NEW PATTERN · GRID",
        "+3 PALETTES",
        "NEW FRAME · DOUBLE",
        "NEW PATTERN · SPLIT-K",
        "NEW PATTERN · SPIRAL",
        "NEW PATTERN · CONSTELLATION",
        "NEW GLYPH · K",
        "ORBIT RING",
        "NEW FRAME · IRIDESCENT",
    };
    /** Depth (m) that unlocks the animated orbit ring (tier 10). */
    public static final int ORBIT_RING_METERS = 11000;

    private Depth() {
    }

    /**
     * @return the constant bonus: 1 per 2,000,000 away from 96,000,000, negative below it (−48 at 0,
     *         −3 at 90,000,000, 0 at 96,000,000, 2 at 100,000,000 and above)
     */
    public static float bonus(int score) {
        int s = Math.min(score, SCORE_CAP);
        return (s - BONUS_FROM) / (float) SCORE_PER_BONUS;
    }

    /** @return chart depth in metres: 40 × max(0, c + bonus)² (0 for a constant that is not positive). */
    public static float chartDepth(float c, int score) {
        if (!(c > 0f)) return 0f;
        float x = Math.max(0f, c + bonus(score));
        return METERS_PER_C2 * x * x;
    }

    /** @return the deepest this chart can count for (score 100,000,000 or more). */
    public static float maxChartDepth(float c) {
        return chartDepth(c, SCORE_CAP);
    }

    /**
     * Player depth = (sum of the {@link #TOP_N} deepest charts) / TOP_N; missing slots count 0.
     *
     * @param topSortedDesc chart depths sorted descending (any length; only the first TOP_N count)
     */
    public static float playerDepth(float[] topSortedDesc) {
        double sum = 0;
        int n = Math.min(TOP_N, topSortedDesc.length);
        for (int i = 0; i < n; i++) sum += Math.max(0f, topSortedDesc[i]);
        return (float) (sum / TOP_N);
    }

    /** @return a depth as displayed: whole metres, rounded down. */
    public static int meters(float depth) {
        return depth > 0f ? (int) Math.floor(depth + 1e-3) : 0;
    }

    /**
     * The player depth in displayed metres if this chart's best score became {@code score} (a score
     * that does not beat the chart's current depth, or the 10th deepest, changes nothing).
     *
     * @param c        chart constant
     * @param curDepth the chart's current best depth, or −1 when it has no record
     * @param top10    best chart depths sorted descending, length ≥ {@link #TOP_N}, zeros for empty slots
     */
    public static int metersAfter(float c, int score, float curDepth, float[] top10) {
        double sum = 0;
        for (int i = 0; i < TOP_N; i++) sum += Math.max(0f, top10[i]);
        boolean inTop = curDepth >= 0f && curDepth >= top10[TOP_N - 1];
        float replaced = inTop ? curDepth : top10[TOP_N - 1];
        float d = chartDepth(c, score);
        if (d > replaced) sum += (double) d - replaced;
        return meters((float) (sum / TOP_N));
    }

    /**
     * The card's NEXT goal (Kelvin 2026-10-17 15:32): the next whole 100 m of the player depth; when
     * this chart alone cannot get there, the next 50 m, then the next 10 m; failing those, the
     * deepest this chart can take the player ({@link Goal#max}). The score is the lowest multiple
     * of {@link #GOAL_SCORE_STEP} above the chart's best that gets there.
     *
     * @param c         chart constant
     * @param bestScore the chart's best score, or −1 when it has no record
     * @param curDepth  the chart's current best depth, or −1 when it has no record
     * @param top10     best chart depths sorted descending, length ≥ {@link #TOP_N}
     * @param out       filled when the method returns true
     * @return false when no score on this chart makes the player a metre deeper (MAXED)
     */
    public static boolean nextGoal(float c, int bestScore, float curDepth, float[] top10, Goal out) {
        int lo = Math.max(0, bestScore) / GOAL_SCORE_STEP + 1;
        int hi = SCORE_CAP / GOAL_SCORE_STEP;
        if (lo > hi) return false;
        int now = meters(playerDepth(top10));
        int deepest = metersAfter(c, hi * GOAL_SCORE_STEP, curDepth, top10);
        if (deepest <= now) return false;
        int target = deepest;
        out.max = true;
        for (int step : GOAL_ROUND) {
            int t = (now / step + 1) * step;
            if (t <= deepest) {
                target = t;
                out.max = false;
                break;
            }
        }
        // the lowest step that gets there (the depth never falls as the score rises)
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (metersAfter(c, mid * GOAL_SCORE_STEP, curDepth, top10) >= target) hi = mid;
            else lo = mid + 1;
        }
        out.meters = target;
        out.score = lo * GOAL_SCORE_STEP;
        return true;
    }

    /** The NEXT goal of one chart ({@link #nextGoal}); the card keeps one and refills it. */
    public static final class Goal {
        /** Target player depth in whole metres (positive; shown as −2,500 m). */
        public int meters;
        /** The lowest score that reaches {@link #meters}, a multiple of {@link #GOAL_SCORE_STEP}. */
        public int score;
        /** True when {@link #meters} is the deepest this chart can take the player, not a round depth. */
        public boolean max;
    }

    /** @return the depth (m) where tier {@code i} starts: 0, 2,000, 4,000, 6,000, 7,000, 8,000, 9,000, 9,500 … */
    public static int tierMeters(int i) {
        if (i <= 0) return 0;
        if (i < TIER_START.length) return TIER_START[i];
        return TIER_START[TIER_START.length - 1] + (i - TIER_START.length + 1) * TIER_STEP;
    }

    /** @return the tier of depth {@code m} (0 below 2,000 m). */
    public static int tierOf(int m) {
        int i = 0;
        while (tierMeters(i + 1) <= m) i++;
        return i;
    }

    /** @return progress 0..1 through the tier of depth {@code m} (the result screen's mini gauge). */
    public static float tierProgress(int m) {
        int v = Math.max(0, m);
        int t = tierOf(v);
        int a = tierMeters(t);
        return (v - a) / (float) (tierMeters(t + 1) - a);
    }

    /**
     * Appends the labels of every avatar option unlocked in ({@code fromM}, {@code toM}] metres
     * (option {@code i} unlocks at the start of tier {@code i + 1}).
     *
     * @return number of labels appended
     */
    public static int unlocksBetween(int fromM, int toM, List<String> out) {
        int n = 0;
        for (int i = 0; i < UNLOCK_LABELS.length; i++) {
            int m = tierMeters(i + 1);
            if (m > fromM && m <= toM) {
                out.add(UNLOCK_LABELS[i]);
                n++;
            }
        }
        return n;
    }

    /** @return true when the animated orbit ring is unlocked at depth {@code m}. */
    public static boolean orbitRingUnlocked(int m) {
        return m >= ORBIT_RING_METERS;
    }
}
