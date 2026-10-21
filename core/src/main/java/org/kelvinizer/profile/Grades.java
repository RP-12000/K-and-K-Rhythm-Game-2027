package org.kelvinizer.profile;

import org.kelvinizer.play.Judgement;

/**
 * 评级：按分数分成 15 档，从好到差是 R+、R、SS+、SS、S+、S、A+、A、B+、B、C+、C、D+、D、F（Kelvin 2026-10-17）。
 * R 要 1 亿分（象征共鸣 resonance），R+ 是这张谱的理论满分（每个 note 都是 PERFECT），6000 万及以下是 F；纯 Java。
 *
 * <p>Grades of a run (Kelvin 2026-10-17 05:44), fifteen bands on the score, best first: R needs
 * 100,000,000, R+ is the chart's theoretical maximum ({@link Judgement#maxScore(int)}, every note
 * a PERFECT RESONANCE), and 60,000,000 or less is F. Pure Java, no allocation.
 */
public final class Grades {
    /** Grade strings, best first. */
    public static final String[] GRADES = {
        "R+", "R", "SS+", "SS", "S+", "S", "A+", "A", "B+", "B", "C+", "C", "D+", "D", "F"};
    /** Index of R+ (the theoretical maximum) in {@link #GRADES}. */
    public static final int R_PLUS = 0;
    /** Index of R (100,000,000) in {@link #GRADES}. */
    public static final int R = 1;
    /** Index of F, the last grade. */
    public static final int F = GRADES.length - 1;
    /**
     * Minimum score of each grade in {@link #GRADES}. R+ is not a threshold (it needs the chart's
     * theoretical maximum, see {@link #gradeIndexOf(int, int)}); its entry repeats R's. D starts
     * above 60,000,000: "60M 及以下 F".
     */
    public static final int[] GRADE_MIN_SCORE = {
        100_000_000, 100_000_000, 99_000_000, 98_000_000, 96_000_000, 94_000_000, 92_000_000, 90_000_000,
        87_000_000, 80_000_000, 77_000_000, 70_000_000, 67_000_000, 60_000_001, 0};
    /**
     * Grade colours (RGBA8888) matching {@link #GRADES}: R+ and R light blue with a white band
     * sweeping through them ({@code ui.Theme#sweep}, Kelvin 2026-10-17 15:34 / 16:02); SS and S gold
     * (Kelvin 18:10: 「S和SS都用金色（+号默认和不带加号的一样）」); below them a neutral grey that lightens
     * one letter at a time up to white (Kelvin 19:34: 「F能不能用灰色，然后逐渐向A+的纯白色过渡？」):
     * F #808080, D #A0A0A0, C #C0C0C0, B #E0E0E0, A #FFFFFF. A "+" grade is the colour of its letter.
     */
    public static final int[] GRADE_RGBA = {
        0xA6DDFFFF, 0xA6DDFFFF, 0xFFD27AFF, 0xFFD27AFF, 0xFFD27AFF, 0xFFD27AFF, 0xFFFFFFFF, 0xFFFFFFFF,
        0xE0E0E0FF, 0xE0E0E0FF, 0xC0C0C0FF, 0xC0C0C0FF, 0xA0A0A0FF, 0xA0A0A0FF, 0x808080FF};
    /** Stamp ring count of each grade in {@link #GRADES}. */
    public static final int[] GRADE_RINGS = {3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 1, 1, 1, 1, 1};
    /** Iridescent cycle (RGBA8888), 3 s loop: the R+ motes and the iridescent frame. */
    public static final int[] R_PLUS_CYCLE_RGBA = {0x66F0FFFF, 0xB49CFFFF, 0xFFD27AFF};

    private Grades() {
    }

    /**
     * Grade of a run.
     *
     * @param score      the run's score (base + chain + PERFECT bonus)
     * @param totalNotes notes in the chart; R+ needs {@link Judgement#maxScore(int)} of it
     *                   (0 = unknown, then the best grade is R)
     */
    public static String gradeOf(int score, int totalNotes) {
        return GRADES[gradeIndexOf(score, totalNotes)];
    }

    /** @return the index into {@link #GRADES} for {@link #gradeOf(int, int)}. */
    public static int gradeIndexOf(int score, int totalNotes) {
        if (totalNotes > 0 && score >= Judgement.maxScore(totalNotes)) return R_PLUS;
        for (int i = R; i < GRADE_MIN_SCORE.length; i++) {
            if (score >= GRADE_MIN_SCORE[i]) return i;
        }
        return F;
    }

    /** Grade for a stored score whose chart is unknown (migration, fallbacks): R at best, never R+. */
    public static String gradeOf(int score) {
        return GRADES[gradeIndexOf(score, 0)];
    }

    /**
     * @return the index of a grade string in {@link #GRADES} (0 = R+, best), or
     *         {@code GRADES.length} for null/unknown (worse than F)
     */
    public static int gradeIndex(String grade) {
        if (grade == null) return GRADES.length;
        for (int i = 0; i < GRADES.length; i++) {
            if (GRADES[i].equals(grade)) return i;
        }
        return GRADES.length;
    }

    /** @return true when {@code a} is a strictly better grade than {@code b} (null = none). */
    public static boolean isBetterGrade(String a, String b) {
        return gradeIndex(a) < gradeIndex(b);
    }
}
