package org.kelvinizer.play;

import org.kelvinizer.library.Difficulty;

/**
 * 一局结束时的成绩快照（不可变）：分数、准确率、各判定数量、连击、水雷、EARLY / LATE 和误差分布。
 * 游玩结束时生成，交给 {@code ProfileStore.recordPlay} 记录，并由结算画面显示。
 *
 * <p>Immutable snapshot of one finished run, built by gameplay when the run ends and passed to
 * {@code ProfileStore.recordPlay} and the result screen (ARCHITECTURE §5, DESIGN Appendix A.9).
 *
 * <p>Create it with {@link #builder()}; {@link Builder#scores(ScoreKeeper)} copies every
 * statistic from a {@link ScoreKeeper}.
 */
public final class PlayResult {
    private final String songId;
    private final Difficulty difficulty;
    private final String title;
    private final String artist;
    private final float chartConstant;
    private final String constantText;
    private final int score;
    private final int baseScore;
    private final int perfect;
    private final int maxChain;
    private final float accuracy;
    private final int resonance;
    private final int echo;
    private final int lost;
    private final int early;
    private final int late;
    private final int earlyResonance;
    private final int lateResonance;
    private final int maxCombo;
    private final int totalNotes;
    private final int minesTotal;
    private final int minesTouched;
    private final boolean fullCombo;
    private final boolean allResonance;
    private final boolean autoplay;
    private final boolean tutorial;
    private final float meanOffsetMs;
    private final long timestampMillis;
    private final long playTimeMs;
    private final short[] deltaHistogram;

    private PlayResult(Builder b) {
        songId = b.songId;
        difficulty = b.difficulty;
        title = b.title != null ? b.title : (b.songId != null ? b.songId : "");
        artist = b.artist != null ? b.artist : "";
        chartConstant = b.chartConstant;
        constantText = b.constantText != null ? b.constantText : formatConstant(b.chartConstant);
        resonance = Math.max(0, b.resonance);
        echo = Math.max(0, b.echo);
        lost = Math.max(0, b.lost);
        totalNotes = Math.max(0, b.totalNotes);
        // ARCHITECTURE §18: the chain term and the PERFECT bonus lift the score above 100,000,000
        // (up to 100,010,000 + t)
        score = Math.max(0, Math.min(Judgement.maxScore(totalNotes), b.score));
        perfect = Math.max(0, Math.min(resonance, b.perfect));
        maxChain = Math.max(0, Math.min(resonance, b.maxChain));
        int base = b.baseScore >= 0 ? b.baseScore : score - perfect;
        baseScore = Math.max(0, Math.min(Math.min(Judgement.MAX_BASE_SCORE, score), base));
        accuracy = b.accuracy;
        early = b.early;
        late = b.late;
        earlyResonance = Math.max(0, Math.min(resonance - perfect, b.earlyResonance));
        lateResonance = Math.max(0, Math.min(resonance - perfect - earlyResonance, b.lateResonance));
        maxCombo = b.maxCombo;
        minesTotal = Math.max(0, b.minesTotal);
        minesTouched = Math.max(0, b.minesTouched);
        fullCombo = b.fullCombo;
        allResonance = b.allResonance;
        autoplay = b.autoplay;
        tutorial = b.tutorial;
        meanOffsetMs = b.meanOffsetMs;
        timestampMillis = b.timestampMillis != 0 ? b.timestampMillis : System.currentTimeMillis();
        playTimeMs = b.playTimeMs;
        deltaHistogram = new short[ScoreKeeper.HISTOGRAM_BINS];
        if (b.deltaHistogram != null) {
            System.arraycopy(b.deltaHistogram, 0, deltaHistogram, 0,
                Math.min(b.deltaHistogram.length, deltaHistogram.length));
        }
    }

    /** @return a new, empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return song id (chart folder name), e.g. "Song 1"; "Introduction" for the tutorial. */
    public String songId() { return songId; }
    /** @return difficulty played (SURFACE for the tutorial). */
    public Difficulty difficulty() { return difficulty; }
    /** @return display title. */
    public String title() { return title; }
    /** @return display artist. */
    public String artist() { return artist; }
    /** @return chart constant used for depth and shells. */
    public float chartConstant() { return chartConstant; }
    /** @return chart constant as authored, e.g. "15.9". */
    public String constantText() { return constantText; }
    /**
     * @return final score, base + PERFECT bonus, 0..{@code 100,010,000 + totalNotes}
     *         (ARCHITECTURE §18); depth and shells only look at {@code min(score, 100,000,000)}
     */
    public int score() { return score; }
    /** @return score without the PERFECT bonus, 0..100,010,000 (100,010,000 = every note RESONANCE). */
    public int baseScore() { return baseScore; }
    /** @return PERFECT count: RESONANCE judgements with |Δt| ≤ {@link Judgement#PERFECT_MS} (the bonus points). */
    public int perfect() { return perfect; }
    /** @return longest run of consecutive RESONANCE judgements (the resonance chain, {@code m_r}). */
    public int maxChain() { return maxChain; }
    /** @return highest possible score of this chart: 100,010,000 + totalNotes. */
    public int maxScore() { return Judgement.maxScore(totalNotes); }
    /** @return accuracy 0..1. */
    public float accuracy() { return accuracy; }
    /** @return number of notes with the given grade. */
    public int count(Judgement j) {
        switch (j) {
            case RESONANCE: return resonance;
            case ECHO: return echo;
            default: return lost;
        }
    }
    /** @return non-LOST hits earlier than −TIER1. */
    public int early() { return early; }
    /** @return non-LOST hits later than +TIER1. */
    public int late() { return late; }
    /** @return RESONANCE hits earlier than −PERFECT_MS (RESONANCE but not PERFECT). */
    public int earlyResonance() { return earlyResonance; }
    /** @return RESONANCE hits later than +PERFECT_MS (RESONANCE but not PERFECT). */
    public int lateResonance() { return lateResonance; }
    /** @return best combo. */
    public int maxCombo() { return maxCombo; }
    /** @return judged notes in the chart (mines excluded). */
    public int totalNotes() { return totalNotes; }
    /** @return mines in the chart (the result shows the MINES row only when &gt; 0). */
    public int minesTotal() { return minesTotal; }
    /** @return mines touched during the run. */
    public int minesTouched() { return minesTouched; }
    /** @return true when the whole run was one combo (maxCombo == totalNotes). */
    public boolean fullCombo() { return fullCombo; }
    /** @return true for the ALL RESONANCE badge: every note RESONANCE and a FULL COMBO. */
    public boolean allResonance() { return allResonance; }
    /**
     * @return true when every judged note was RESONANCE; unlike
     *         {@link #allResonance()} a touched mine does not clear it (mines never lower the grade)
     */
    public boolean pure() {
        return allResonance || (totalNotes > 0 && resonance >= totalNotes);
    }
    /** @return true for autoplay runs (never recorded). */
    public boolean autoplay() { return autoplay; }
    /** @return true for the tutorial (no chart record). */
    public boolean tutorial() { return tutorial; }
    /** @return mean signed offset of non-LOST hits in ms (+ = late). */
    public float meanOffsetMs() { return meanOffsetMs; }
    /** @return wall-clock time the run finished. */
    public long timestampMillis() { return timestampMillis; }
    /** @return length of the run in ms (for play-time stats); 0 if unknown. */
    public long playTimeMs() { return playTimeMs; }
    /** @return number of non-LOST hits. */
    public int hits() { return resonance + echo; }

    /** @return histogram bin {@code i} (0..26, 10 ms bins over −135..+135 ms). */
    public int histogramBin(int i) {
        return deltaHistogram[i];
    }

    /**
     * Copies the 27-bin delta histogram into {@code out}.
     *
     * @return {@code out}
     */
    public short[] deltaHistogram(short[] out) {
        System.arraycopy(deltaHistogram, 0, out, 0, deltaHistogram.length);
        return out;
    }

    /** @return a fresh copy of the 27-bin delta histogram. */
    public short[] deltaHistogram() {
        return deltaHistogram.clone();
    }

    /** @return largest histogram bin value (for normalising bars). */
    public int histogramPeak() {
        int max = 0;
        for (short v : deltaHistogram) if (v > max) max = v;
        return max;
    }

    static String formatConstant(float c) {
        int tenths = Math.round(c * 10f);
        return (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    @Override
    public String toString() {
        return "PlayResult{" + songId + " " + difficulty + " score=" + score + " (base " + baseScore
            + " +" + perfect + " perfect) chain=" + maxChain + " acc=" + accuracy
            + " R/E/L=" + resonance + "/" + echo + "/" + lost + " maxCombo=" + maxCombo + "/" + totalNotes
            + (minesTotal > 0 ? " mines=" + minesTouched + "/" + minesTotal : "")
            + (autoplay ? " AUTO" : "") + (tutorial ? " TUTORIAL" : "") + "}";
    }

    /** Mutable builder for {@link PlayResult}. Not thread-safe; reusable after {@link #build()}. */
    public static final class Builder {
        private String songId;
        private Difficulty difficulty = Difficulty.SURFACE;
        private String title;
        private String artist;
        private float chartConstant = 1f;
        private String constantText;
        private int score;
        private int baseScore = -1;
        private int perfect;
        private int maxChain;
        private float accuracy = 1f;
        private int resonance;
        private int echo;
        private int lost;
        private int early;
        private int late;
        private int earlyResonance;
        private int lateResonance;
        private int maxCombo;
        private int totalNotes;
        private int minesTotal;
        private int minesTouched;
        private boolean fullCombo;
        private boolean allResonance;
        private boolean autoplay;
        private boolean tutorial;
        private float meanOffsetMs;
        private long timestampMillis;
        private long playTimeMs;
        private short[] deltaHistogram;

        private Builder() {
        }

        /**
         * Sets the song id (folder name) and difficulty. Required for every non-tutorial run:
         * {@code ProfileStore.recordPlay} does not record a result without a song id.
         */
        public Builder chart(String songId, Difficulty difficulty) {
            this.songId = songId;
            this.difficulty = difficulty != null ? difficulty : Difficulty.SURFACE;
            return this;
        }

        /** Sets display title and artist. */
        public Builder info(String title, String artist) {
            this.title = title;
            this.artist = artist;
            return this;
        }

        /** Sets the chart constant used for depth and shells and its authored text (may be null). */
        public Builder constant(float chartConstant, String constantText) {
            this.chartConstant = chartConstant;
            this.constantText = constantText;
            return this;
        }

        /**
         * Copies score (base, PERFECT, chain), accuracy, counts, combos, early/late, offsets,
         * flags and histogram.
         */
        public Builder scores(ScoreKeeper k) {
            score = k.score();
            baseScore = k.baseScore();
            perfect = k.perfectCount();
            maxChain = k.maxChain();
            accuracy = k.accuracy();
            resonance = k.count(Judgement.RESONANCE);
            echo = k.count(Judgement.ECHO);
            lost = k.count(Judgement.LOST);
            early = k.early();
            late = k.late();
            earlyResonance = k.earlyResonance();
            lateResonance = k.lateResonance();
            maxCombo = k.maxCombo();
            totalNotes = k.totalNotes();
            minesTotal = k.totalMines();
            minesTouched = k.minesTouched();
            fullCombo = k.isFullCombo();
            allResonance = k.isAllResonance();
            meanOffsetMs = k.meanOffsetMs();
            if (deltaHistogram == null) deltaHistogram = new short[ScoreKeeper.HISTOGRAM_BINS];
            k.histogram(deltaHistogram);
            return this;
        }

        /**
         * Sets the score explicitly (base + PERFECT bonus). Without {@link #baseScore(int)} the
         * base is taken as {@code score − perfect}.
         */
        public Builder score(int score) { this.score = score; return this; }
        /** Sets the base score explicitly (score without the PERFECT bonus). */
        public Builder baseScore(int baseScore) { this.baseScore = baseScore; return this; }
        /** Sets the PERFECT count explicitly (clamped to the RESONANCE count at build). */
        public Builder perfect(int perfect) { this.perfect = perfect; return this; }
        /** Sets the longest resonance chain explicitly (clamped to the RESONANCE count at build). */
        public Builder maxChain(int maxChain) { this.maxChain = maxChain; return this; }
        /** Sets the accuracy explicitly (0..1). */
        public Builder accuracy(float accuracy) { this.accuracy = accuracy; return this; }
        /** Sets the judgement counts explicitly. */
        public Builder counts(int resonance, int echo, int lost) {
            this.resonance = resonance;
            this.echo = echo;
            this.lost = lost;
            return this;
        }
        /** Sets early/late counts explicitly. */
        public Builder earlyLate(int early, int late) { this.early = early; this.late = late; return this; }

        /** Sets the early/late RESONANCE counts (RESONANCE but not PERFECT) explicitly. */
        public Builder resonanceEarlyLate(int early, int late) {
            this.earlyResonance = early;
            this.lateResonance = late;
            return this;
        }
        /** Sets max combo and total notes explicitly. */
        public Builder combo(int maxCombo, int totalNotes) {
            this.maxCombo = maxCombo;
            this.totalNotes = totalNotes;
            return this;
        }
        /** Sets the mine counts explicitly. */
        public Builder mines(int minesTotal, int minesTouched) {
            this.minesTotal = minesTotal;
            this.minesTouched = minesTouched;
            return this;
        }
        /** Sets the FULL COMBO / ALL RESONANCE flags explicitly. */
        public Builder flags(boolean fullCombo, boolean allResonance) {
            this.fullCombo = fullCombo;
            this.allResonance = allResonance;
            return this;
        }
        /** Marks an autoplay run (never recorded). */
        public Builder autoplay(boolean autoplay) { this.autoplay = autoplay; return this; }
        /** Marks the tutorial run. */
        public Builder tutorial(boolean tutorial) { this.tutorial = tutorial; return this; }
        /** Sets the mean offset explicitly. */
        public Builder meanOffsetMs(float meanOffsetMs) { this.meanOffsetMs = meanOffsetMs; return this; }
        /** Sets the finish time; defaults to now. */
        public Builder timestamp(long timestampMillis) { this.timestampMillis = timestampMillis; return this; }
        /** Sets the run length in ms. */
        public Builder playTimeMs(long playTimeMs) { this.playTimeMs = playTimeMs; return this; }
        /** Sets the histogram explicitly (copied). */
        public Builder histogram(short[] bins) {
            if (bins == null) {
                deltaHistogram = null;
            } else {
                deltaHistogram = new short[ScoreKeeper.HISTOGRAM_BINS];
                System.arraycopy(bins, 0, deltaHistogram, 0, Math.min(bins.length, deltaHistogram.length));
            }
            return this;
        }

        /** @return a new immutable result. */
        public PlayResult build() {
            return new PlayResult(this);
        }
    }
}
