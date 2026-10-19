package org.kelvinizer.play;

/**
 * 一局的分数、连击、共鸣链和时机统计（PERFECT、EARLY / LATE、误差分布、水雷）；纯 Java，构造后不再分配内存。
 * 分数只增不减；碰到水雷只打断连击、不改分数，但会失去 FULL COMBO 和 ALL RESONANCE。
 *
 * <p>Pure-Java score, combo and timing statistics for one run (ARCHITECTURE §5).
 *
 * <p>No libGDX dependencies and no allocation after construction, so it can be called from
 * input handlers and unit-tested on a plain JVM.
 *
 * <ul>
 *   <li>{@link #score()} = {@link #baseScore()} + {@link #perfectCount()} (ARCHITECTURE §18):
 *       the base is {@code 100,000,000 · (r + 0.55·e)/t + 10,000 · m_r/t} rounded down once,
 *       with {@code m_r} = {@link #maxChain()}, so only an all-RESONANCE run reaches a base of
 *       100,010,000 and every PERFECT (RESONANCE with |Δt| ≤ {@link Judgement#PERFECT_MS}) adds
 *       one point on top ({@link #maxScore()} = 100,010,000 + t). Every term only grows, so the
 *       score never decreases during a run.</li>
 *   <li>Two streaks: the <b>combo</b> (RESONANCE or ECHO; reset by LOST and by a touched mine;
 *       shown in the HUD and used for FULL COMBO) and the <b>resonance chain</b> (RESONANCE only;
 *       reset by ECHO and LOST, <i>not</i> by a mine, so a mine never changes the score).</li>
 *   <li>{@link #accuracy()} = (r + 0.55·e) / judgedCount (1.0 before anything is judged).</li>
 *   <li>Early / late count non-LOST hits with Δt &lt; −TIER1 / Δt &gt; +TIER1 (the ECHO hits);
 *       {@link #earlyResonance()} / {@link #lateResonance()} split the RESONANCE hits that are not
 *       PERFECT by sign, so PERFECT + early + late RESONANCE = RESONANCE.</li>
 *   <li>The delta histogram has {@link #HISTOGRAM_BINS} bins of 10 ms over −135..+135 ms and
 *       only counts non-LOST hits (DESIGN Appendix A.9).</li>
 *   <li>Mines (ARCHITECTURE §13.2) are outside {@code totalNotes}: {@link #mineTouched()} only
 *       breaks the combo and counts; FULL COMBO / ALL RESONANCE require
 *       {@code maxCombo == totalNotes}, so a touched mine loses them without changing the score.
 *       A LANE MINE counts once; its further contacts only break the combo
 *       ({@link #laneMineContact()}).</li>
 * </ul>
 */
public final class ScoreKeeper {
    /** Number of histogram bins (10 ms each over −135..+135 ms). */
    public static final int HISTOGRAM_BINS = 27;
    /** Lower edge of the histogram in ms. */
    public static final float HISTOGRAM_MIN_MS = -135f;
    /** Width of one histogram bin in ms. */
    public static final float HISTOGRAM_BIN_MS = 10f;

    private final int[] counts = new int[Judgement.count()];
    private final short[] histogram = new short[HISTOGRAM_BINS];
    private int totalNotes;
    private int totalMines;
    private int minesTouched;
    private int combo;
    private int maxCombo;
    private int chain;
    private int maxChain;
    private int perfect;
    private int early;
    private int late;
    private int earlyResonance;
    private int lateResonance;
    private double offsetSum;
    private int offsetCount;

    /** Creates a keeper for a run of zero notes; call {@link #reset(int, int)} before use. */
    public ScoreKeeper() {
    }

    /** Creates a keeper for a run of {@code totalNotes} notes. */
    public ScoreKeeper(int totalNotes) {
        reset(totalNotes, 0);
    }

    /** Creates a keeper for a run of {@code totalNotes} notes and {@code totalMines} mines. */
    public ScoreKeeper(int totalNotes, int totalMines) {
        reset(totalNotes, totalMines);
    }

    /** Clears every statistic and sets the number of notes in the run (no mines). */
    public void reset(int totalNotes) {
        reset(totalNotes, 0);
    }

    /**
     * Clears every statistic.
     *
     * @param totalNotes judged notes in the run (taps, hold heads, universal notes; no mines)
     * @param totalMines mines in the run
     */
    public void reset(int totalNotes, int totalMines) {
        this.totalNotes = Math.max(0, totalNotes);
        this.totalMines = Math.max(0, totalMines);
        minesTouched = 0;
        for (int i = 0; i < counts.length; i++) counts[i] = 0;
        for (int i = 0; i < histogram.length; i++) histogram[i] = 0;
        combo = 0;
        maxCombo = 0;
        chain = 0;
        maxChain = 0;
        perfect = 0;
        early = 0;
        late = 0;
        earlyResonance = 0;
        lateResonance = 0;
        offsetSum = 0;
        offsetCount = 0;
    }

    /**
     * Records one judged note.
     *
     * @param j       the grade (LOST breaks the combo and the chain, ECHO breaks the chain)
     * @param deltaMs signed timing error (judge time − note time, negative = early, calibration
     *                included: the same Δt that chose the grade); a RESONANCE with
     *                |Δt| ≤ {@link Judgement#PERFECT_MS} also counts as PERFECT; ignored for LOST
     */
    public void record(Judgement j, float deltaMs) {
        counts[j.ordinal()]++;
        if (j == Judgement.LOST) {
            combo = 0;
            chain = 0;
            return;
        }
        if (j == Judgement.RESONANCE) {
            chain++;
            if (chain > maxChain) maxChain = chain;
            if (Judgement.isPerfect(deltaMs)) perfect++;
            else if (deltaMs < 0f) earlyResonance++;
            else lateResonance++;
        } else {
            chain = 0;
        }
        combo++;
        if (combo > maxCombo) maxCombo = combo;
        if (deltaMs < -Judgement.TIER1_MS) early++;
        else if (deltaMs > Judgement.TIER1_MS) late++;
        offsetSum += deltaMs;
        offsetCount++;
        int bin = (int) Math.floor((deltaMs - HISTOGRAM_MIN_MS) / HISTOGRAM_BIN_MS);
        if (bin < 0) bin = 0;
        if (bin >= HISTOGRAM_BINS) bin = HISTOGRAM_BINS - 1;
        if (histogram[bin] < Short.MAX_VALUE) histogram[bin]++;
    }

    /** Breaks the current combo and the resonance chain without recording a judgement. */
    public void breakCombo() {
        combo = 0;
        chain = 0;
    }

    /**
     * Records a touched mine: the combo drops to 0 and {@link #minesTouched()} grows; the
     * resonance chain, score, accuracy and judgement counts are unchanged (ARCHITECTURE §13.2,
     * §18: "碰到不扣分但是断连击").
     */
    public void mineTouched() {
        combo = 0;
        minesTouched++;
    }

    /**
     * Records a further contact with a LANE MINE that was already touched (ARCHITECTURE §20):
     * the combo drops to 0 again; {@link #minesTouched()} does not grow (a LANE MINE counts once
     * in the MINES row, as touched when it was triggered at least once); the chain, score,
     * accuracy and judgement counts are unchanged.
     */
    public void laneMineContact() {
        combo = 0;
    }

    /** @return mines touched so far (a LANE MINE counts once however often it was triggered). */
    public int minesTouched() {
        return minesTouched;
    }

    /** @return mines in the run. */
    public int totalMines() {
        return totalMines;
    }

    /**
     * @return the current score, {@link #baseScore()} + {@link #perfectCount()}, in
     *         0..{@link #maxScore()}; never decreases during a run
     */
    public int score() {
        return baseScore() + perfect;
    }

    /**
     * @return the score without the PERFECT bonus,
     *         {@code floor((100,000,000 · (100·r + 55·e) + 1,000,000 · maxChain) / (100·totalNotes))}
     *         (exact integer arithmetic; 100,010,000 only when every note is RESONANCE)
     */
    public int baseScore() {
        return Judgement.baseScore(maxChain, counts[Judgement.RESONANCE.ordinal()],
            counts[Judgement.ECHO.ordinal()], totalNotes);
    }

    /** @return the highest score this run could reach: 100,010,000 + totalNotes (all PERFECT). */
    public int maxScore() {
        return Judgement.maxScore(totalNotes);
    }

    /** @return RESONANCE judgements with |Δt| ≤ {@link Judgement#PERFECT_MS} so far. */
    public int perfectCount() {
        return perfect;
    }

    /** @return current run of consecutive RESONANCE judgements (the resonance chain). */
    public int chain() {
        return chain;
    }

    /** @return longest resonance chain of the run ({@code m_r} of the score formula). */
    public int maxChain() {
        return maxChain;
    }

    /** @return Σweight over every judged note (RESONANCE 1, ECHO 0.55). */
    public double sumWeights() {
        return (Judgement.ACC_RESONANCE * (double) counts[Judgement.RESONANCE.ordinal()]
            + Judgement.ACC_ECHO * (double) counts[Judgement.ECHO.ordinal()]) / 100.0;
    }

    /** @return (r + 0.55·e) / judgedCount in 0..1, or 1.0 when nothing has been judged yet. */
    public float accuracy() {
        int judged = judgedCount();
        if (judged == 0) return 1f;
        return (float) (sumWeights() / judged);
    }

    /** @return number of notes recorded with the given grade. */
    public int count(Judgement j) {
        return counts[j.ordinal()];
    }

    /** @return number of notes judged so far (any grade). */
    public int judgedCount() {
        int n = 0;
        for (int i = 0; i < counts.length; i++) n += counts[i];
        return n;
    }

    /** @return notes in the run. */
    public int totalNotes() {
        return totalNotes;
    }

    /** @return current combo. */
    public int combo() {
        return combo;
    }

    /** @return best combo of the run. */
    public int maxCombo() {
        return maxCombo;
    }

    /** @return non-LOST hits earlier than −TIER1. */
    public int early() {
        return early;
    }

    /** @return non-LOST hits later than +TIER1. */
    public int late() {
        return late;
    }

    /** @return RESONANCE hits earlier than −{@link Judgement#PERFECT_MS} (RESONANCE but not PERFECT). */
    public int earlyResonance() {
        return earlyResonance;
    }

    /** @return RESONANCE hits later than +{@link Judgement#PERFECT_MS} (RESONANCE but not PERFECT). */
    public int lateResonance() {
        return lateResonance;
    }

    /** @return mean signed offset of non-LOST hits in ms (0 when there are none). */
    public float meanOffsetMs() {
        return offsetCount == 0 ? 0f : (float) (offsetSum / offsetCount);
    }

    /** @return number of non-LOST hits that contributed to {@link #meanOffsetMs()}. */
    public int offsetSamples() {
        return offsetCount;
    }

    /**
     * @return true when the combo was never broken so far (nothing LOST, no mine touched, no
     *         {@link #breakCombo()}); the run may be unfinished
     */
    public boolean isFullComboSoFar() {
        return counts[Judgement.LOST.ordinal()] == 0 && minesTouched == 0 && combo == judgedCount();
    }

    /**
     * @return true when the whole run is one combo ({@code maxCombo == totalNotes}) and no mine was
     *         touched: a touched MINE or LANE MINE removes FULL COMBO and ALL RESONANCE even when it
     *         falls before the first note or after the last one (ARCHITECTURE §14)
     */
    public boolean isFullCombo() {
        return totalNotes > 0 && maxCombo >= totalNotes && minesTouched == 0;
    }

    /** @return true when every note is RESONANCE and the run is a FULL COMBO (the ALL RESONANCE badge). */
    public boolean isAllResonance() {
        return isFullCombo() && isPure();
    }

    /**
     * @return true when every judged note of the run is RESONANCE (grade PURE, base score
     *         100,010,000); a touched mine does not change it (it only breaks the combo)
     */
    public boolean isPure() {
        return totalNotes > 0 && counts[Judgement.RESONANCE.ordinal()] >= totalNotes;
    }

    /**
     * Copies the delta histogram into {@code out} (length ≥ {@link #HISTOGRAM_BINS}).
     *
     * @return {@code out}
     */
    public short[] histogram(short[] out) {
        System.arraycopy(histogram, 0, out, 0, HISTOGRAM_BINS);
        return out;
    }

    /** @return the live histogram bin value (no copy). */
    public int histogramBin(int bin) {
        return histogram[bin];
    }
}
