package org.kelvinizer;

import org.kelvinizer.play.Judgement;

/**
 * 全局常量：版本号、游玩场地和谱面的参考画面 1920 × 1200、判定窗口和计分权重、水雷窗口、判定区半高、结束延迟等。
 * 判定窗口和计分常量在 {@link Judgement} 里定义，这里只是别名；不放 GL 对象，也没有可变状态。
 *
 * <p>Game-wide constants (ARCHITECTURE §5). Constants only: no GL objects and no mutable state
 * live here (Android can lose the GL context at any time; textures belong to Main's services or
 * to a screen).
 *
 * <p>The judgement timing and scoring constants are defined once in {@link Judgement} (so the
 * {@code play} package stays free of UI dependencies) and aliased here, so a tuning change is a
 * single edit.
 */
public final class Params {
    /**
     * Build version shown on Welcome and in Settings → ABOUT. Keep it equal to
     * {@code projectVersion} in gradle.properties and {@code versionName} in android/build.gradle.
     */
    public static final String VERSION = "1.0.0";

    /** Width of the reference frame of the playfield and the charts. */
    public static final float REF_W = 1920f;
    /**
     * Height of the reference frame of the playfield and the charts: 1200 since 2026-10-19 (Kelvin 03:51:
     * 「坐标系我们现在修改为1920*1200」; it was 1080, and format v1 / v2 charts are converted on load). The UI
     * viewports are still at least 1920 × 1080 ({@code Ui.REF_H}).
     */
    public static final float REF_H = 1200f;

    /** Tier-1 window half width (66.67 ms): RESONANCE. */
    public static final float TIER1_MS = Judgement.TIER1_MS;
    /** Tier-2 window half width (133.33 ms): ECHO; later than this is LOST. */
    public static final float TIER2_MS = Judgement.TIER2_MS;
    /** PERFECT window half width (1000/30 ≈ 33.33 ms): a RESONANCE inside it adds 1 bonus point. */
    public static final float PERFECT_MS = Judgement.PERFECT_MS;
    /**
     * Mine touch window half width: the RESONANCE window, 1000/15 ≈ 66.67 ms (owner 2026-10-13
     * 21:55: 「普通水雷生效范围为+-66ms」; was 100 ms from 2026-10-12). A MINE is dangerous over
     * [T − 66.67, T + 66.67].
     */
    public static final float MINE_WINDOW_MS = TIER1_MS;
    /**
     * How long before its judgement a LANE MINE starts blocking: 0 ms (owner 2026-10-13 21:55:
     * 「轨道水雷只在碰到判定线后才生效」; was {@link #MINE_WINDOW_MS}). A LANE MINE blocks over
     * [T, end).
     */
    public static final float LANE_MINE_LEAD_MS = 0f;
    /** Score weight of a tier-1 hit. */
    public static final float WEIGHT_TIER1 = Judgement.WEIGHT_TIER1;
    /** Score and accuracy weight of a tier-2 hit (0.55). */
    public static final float WEIGHT_TIER2 = Judgement.WEIGHT_TIER2;
    /** Full value of the chain term {@code 10,000 · m_r/t} of the score (ARCHITECTURE §18). */
    public static final int CHAIN_SCORE = Judgement.CHAIN_SCORE;
    /**
     * Full value of the accuracy term of the score (100,000,000); the chain term and the PERFECT
     * bonus come on top, up to {@code Judgement.maxScore(totalNotes)} = 100,010,000 + totalNotes.
     */
    public static final int MAX_SCORE = Judgement.MAX_SCORE;

    /**
     * Half height of each judgement zone, in ref units above and below the line: 0.4 of the screen
     * height (Kelvin 10-12), so the sky line takes the upper half and the ground line the lower half.
     */
    public static final float JUDGE_ZONE_HALF = 480f;
    /**
     * Half height of the band drawn and kept clear around a line (the at-lane words sit just outside it): 0.1 of
     * the screen height.
     */
    public static final float JUDGE_BAND_HALF = 120f;
    /** Releasing a STREAM or TIDE STREAM within this many ms of its end still counts as completed: there is no
     * tail judgement (Kelvin 10-12 21:09: 0.25 s). */
    public static final float HOLD_RELEASE_GRACE_MS = 250f;
    /** Delay between the end of the run (the last note and the music both over) and the result transition (ms). */
    public static final float FINISH_DELAY_MS = 1500f;
    /** Wait between the end of the run and the end banner (CLEAR, FULL COMBO, …) (ms; Kelvin 2026-10-17: 1 s). */
    public static final float END_BANNER_DELAY_MS = 1000f;
    /** How long the end banner stays (ms). */
    public static final float END_BANNER_MS = 2000f;
    /** {@link #FINISH_DELAY_MS} of a run that shows the end banner: its wait and its time on screen (ms). */
    public static final float BANNER_FINISH_DELAY_MS = END_BANNER_DELAY_MS + END_BANNER_MS;

    private Params() {
    }

    /** True when world point (wx, wy) lies inside the rect (x, y, w, h), edges included. */
    public static boolean hit(float wx, float wy, float x, float y, float w, float h) {
        return wx >= x && wx <= x + w && wy >= y && wy <= y + h;
    }
}
