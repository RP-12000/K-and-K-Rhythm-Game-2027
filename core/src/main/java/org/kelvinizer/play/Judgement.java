package org.kelvinizer.play;

import com.badlogic.gdx.graphics.Color;

/**
 * 音符的三级判定：共鸣 RESONANCE（|Δt| ≤ 66.67 ms）、回响 ECHO（≤ 133.33 ms）和迷失 LOST。
 * |Δt| ≤ 33.33 ms 的共鸣另计为 PERFECT（每个加 1 分）；判定窗口、权重和计分公式的常量都在这里。
 *
 * <p>The three judgement grades of a note (DESIGN §1.2, ARCHITECTURE §5).
 *
 * <ul>
 *   <li>{@link #RESONANCE}: |Δt| ≤ {@link #TIER1_MS} (66.67 ms), weight 1.0; a RESONANCE with
 *       |Δt| ≤ {@link #PERFECT_MS} (33.33 ms) is also counted as PERFECT (a sub-count, not a
 *       fourth grade)</li>
 *   <li>{@link #ECHO}: |Δt| ≤ {@link #TIER2_MS} (133.33 ms), weight 0.55</li>
 *   <li>{@link #LOST}: missed (late beyond the window, broken hold), weight 0</li>
 * </ul>
 *
 * <p><b>Score (ARCHITECTURE §18, the owner's formula).</b> With {@code t} judged notes,
 * {@code r}/{@code e} RESONANCE/ECHO counts, {@code m_r} the longest run of consecutive
 * RESONANCE (the resonance chain) and {@code r_p} the PERFECT count:
 * {@code score = 100,000,000 · (r + 0.55·e)/t + 10,000 · m_r/t + r_p}, computed exactly by
 * {@link #baseScore(int, int, int, int)} as
 * {@code floor((100,000,000 · (100·r + 55·e) + 1,000,000 · m_r) / (100·t))} plus {@code r_p}.
 *
 * <p>The timing, weight and score constants live here so that this package stays free of UI
 * and GL dependencies; {@code Params} aliases them. Colours are kept as RGBA8888 ints plus
 * shared, read-only {@link Color} instances (never mutate them), so nothing here depends on
 * {@code ui.Theme}.
 */
public enum Judgement {
    /** Right on the beat. */
    RESONANCE("RESONANCE", "共鸣", Judgement.WEIGHT_TIER1, 0x66F0FFFF),
    /** Close to the beat. */
    ECHO("ECHO", "回响", Judgement.WEIGHT_TIER2, 0xB49CFFFF),
    /** Missed. */
    LOST("LOST", "迷失", 0f, 0xFF5C7AFF);

    /** Tier-1 window half width in ms: |Δt| at or below this is {@link #RESONANCE}. */
    public static final float TIER1_MS = 1000f / 15f;
    /** Tier-2 window half width in ms: |Δt| at or below this is {@link #ECHO}; later is LOST. */
    public static final float TIER2_MS = 2000f / 15f;
    /** Score weight of {@link #RESONANCE}. */
    public static final float WEIGHT_TIER1 = 1.0f;
    /** Score (and accuracy) weight of {@link #ECHO}. */
    public static final float WEIGHT_TIER2 = 0.55f;
    /** PERFECT window half width in ms (1000/30 ≈ 33.33): a RESONANCE at or below it adds 1 point. */
    public static final float PERFECT_MS = 1000f / 30f;
    /**
     * Full value of the accuracy term {@code 100,000,000 · (r + 0.55·e)/t} (every note RESONANCE);
     * also the cap of the grade, chart rating and EXP, so the chain and PERFECT terms above it
     * never lift those.
     */
    public static final int MAX_SCORE = 100_000_000;
    /** Full value of the chain term {@code 10,000 · m_r/t} (one unbroken resonance chain). */
    public static final int CHAIN_SCORE = 10_000;
    /**
     * Base score (without the PERFECT bonus) of a run where every note is RESONANCE:
     * {@code MAX_SCORE + CHAIN_SCORE} = 100,010,000; the real maximum is {@link #maxScore(int)}.
     */
    public static final int MAX_BASE_SCORE = MAX_SCORE + CHAIN_SCORE;
    /** Accuracy and score weights in hundredths (RESONANCE 100, ECHO 55); keep equal to the float weights. */
    public static final int ACC_RESONANCE = 100, ACC_ECHO = 55;

    /** Colour of the EARLY timing label ({@code #6FA8FF}), RGBA8888. */
    public static final int EARLY_RGBA = 0x6FA8FFFF;
    /** Colour of the LATE timing label ({@code #FFB067}), RGBA8888. */
    public static final int LATE_RGBA = 0xFFB067FF;
    /** Shared read-only EARLY colour. Never mutate. */
    public static final Color EARLY_COLOR = new Color(EARLY_RGBA);
    /** Shared read-only LATE colour. Never mutate. */
    public static final Color LATE_COLOR = new Color(LATE_RGBA);

    private static final Judgement[] VALUES = values();

    /** English display word (uppercase). */
    public final String display;
    /** Chinese display word, used as a subtitle when the language is zh. */
    public final String displayZh;
    /** Accuracy weight (1.0, 0.55 or 0). */
    public final float weight;
    /** Display colour as RGBA8888. */
    public final int rgba;
    private final Color color;

    Judgement(String display, String displayZh, float weight, int rgba) {
        this.display = display;
        this.displayZh = displayZh;
        this.weight = weight;
        this.rgba = rgba;
        this.color = new Color(rgba);
    }

    /** @return the shared, read-only display colour of this judgement. Never mutate it. */
    public Color color() {
        return color;
    }

    /** @return true for RESONANCE and ECHO (the note was hit). */
    public boolean isHit() {
        return this != LOST;
    }

    /**
     * Grades an absolute timing error.
     *
     * @param absDeltaMs |judge time − note time| in ms
     * @return RESONANCE, ECHO, or {@code null} when the error is outside the judgeable window
     */
    public static Judgement ofDelta(float absDeltaMs) {
        if (absDeltaMs < 0f) absDeltaMs = -absDeltaMs;
        if (absDeltaMs <= TIER1_MS) return RESONANCE;
        if (absDeltaMs <= TIER2_MS) return ECHO;
        return null;
    }

    /** @return true when a RESONANCE with this |Δt| is also PERFECT (|Δt| ≤ {@link #PERFECT_MS}). */
    public static boolean isPerfect(float absDeltaMs) {
        if (absDeltaMs < 0f) absDeltaMs = -absDeltaMs;
        return absDeltaMs <= PERFECT_MS;
    }

    /**
     * @return the highest score a chart of {@code totalNotes} judged notes can give: every note
     *         PERFECT, {@code MAX_BASE_SCORE + totalNotes}
     */
    public static int maxScore(int totalNotes) {
        return MAX_BASE_SCORE + Math.max(0, totalNotes);
    }

    /**
     * Exact base score (without the PERFECT bonus):
     * {@code floor((100,000,000 · (100·resonance + 55·echo) + 1,000,000 · chain) / (100·totalNotes))},
     * i.e. {@code 100,000,000 · (r + 0.55·e)/t + 10,000 · m_r/t} rounded down once; 0 when
     * {@code totalNotes ≤ 0}. Equals {@link #MAX_BASE_SCORE} exactly when every note is RESONANCE
     * ({@code chain == resonance == totalNotes}). Inputs are clamped to sane ranges.
     *
     * @param chain      longest run of consecutive RESONANCE ({@code m_r})
     * @param resonance  RESONANCE count ({@code r})
     * @param echo       ECHO count ({@code e})
     * @param totalNotes judged notes of the chart ({@code t}, mines excluded)
     */
    public static int baseScore(int chain, int resonance, int echo, int totalNotes) {
        if (totalNotes <= 0) return 0;
        long r = Math.max(0, Math.min(resonance, totalNotes));
        long m = Math.max(0, Math.min(chain, (int) r));
        long e = Math.max(0, Math.min(echo, totalNotes - (int) r));
        long units = (long) MAX_SCORE * (ACC_RESONANCE * r + ACC_ECHO * e) + 100L * CHAIN_SCORE * m;
        return (int) (units / (100L * totalNotes));
    }

    /**
     * @return the chain term alone, {@code floor(10,000 · chain / totalNotes)} (0 when
     *         {@code totalNotes ≤ 0}); the result screen's score breakdown shows it
     */
    public static int chainScore(int chain, int totalNotes) {
        if (totalNotes <= 0) return 0;
        long m = Math.max(0, Math.min(chain, totalNotes));
        return (int) ((long) CHAIN_SCORE * m / totalNotes);
    }

    /** @return the judgement with the given ordinal (no allocation, unlike {@code values()}). */
    public static Judgement byOrdinal(int ordinal) {
        return VALUES[ordinal];
    }

    /** @return number of judgement grades (3). */
    public static int count() {
        return VALUES.length;
    }
}
