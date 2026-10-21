package org.kelvinizer.gameplay;

import org.kelvinizer.Params;
import org.kelvinizer.ui.Ui;

/**
 * 游玩场地的拉伸：谱面坐标按 1920 × 1200 的参考画面写成（2026-10-19 起；以前是 1920 × 1080），在 a × b 的屏幕上以屏幕中心为原点，
 * 横向乘 a/1920、纵向乘 b/1200，判定线、轨道、音符下落的距离和判定区都跟着拉伸；音符厚度、文字、波纹和四角的 HUD 不拉伸。
 *
 * <p>The playfield stretch (Kelvin 2026-10-12 16:09 / 16:17: 「横向上所有坐标拉伸（a/1920）……纵向上所有坐标拉伸
 * （b/1080）……拉伸是相对于中轴线（屏幕中心）」; 「实际音符下落的时候也是按照这种比例拉伸」). Chart geometry is
 * authored in the reference 1920 × 1200 frame since 2026-10-19 (Kelvin 03:51: 「坐标系我们现在修改为1920*1200，核心思路不变」;
 * before that 1920 × 1080, read through {@code ChartMapper}'s conversion), so a 16 : 10 screen shows it unstretched.
 * The world is the UI's {@code ExtendViewport} (at least 1920 × 1080, centred at (960, 540)), so on a screen of
 * aspect a : b the field fills it with {@code kx = worldW / 1920} and {@code ky = worldH / 1200}, which in pixels is
 * exactly a/1920 and b/1200. After {@link #fit} a reference point maps to {@code X = 960 + (x − 960)·kx},
 * {@code Y = 540 + (y − 600)·ky}: the reference centre (960, 600) lands on the camera centre.
 *
 * <p>Applied at the source, so renderers and input stay in world coordinates: the engine stretches the
 * line y and window bounds it evaluates for a frame or an input event (lanes widen with {@code kx}, and the
 * note width follows the lane), every distance from a line (note travel, spawn, tail length) is multiplied
 * by {@code ky} after the {@code HIDDEN_POSITION} test on the raw value (a note still reaches its line at
 * its judgement time), and a judgement zone is {@code ±480·ky}. Not stretched: note thickness, pearls,
 * ripples, glows, particles, text, the corner HUD and the pause control; rotation angles stay as authored
 * (bodies are rigid, only positions stretch). A lane note's or mine's rigid body is centred where its size-1.0
 * reference body's centre lands, away from its line ({@link NoteShapes#centreShiftY}).
 *
 * <p>Plain static numbers, set by the gameplay screen from the viewport ({@link #fit}) on load and every
 * frame (a desktop resize mid-run is picked up on the next frame). Until the first fit (and after
 * {@link #reset}) the mapping is the identity, {@code kx = ky = 1} about the reference centre, so headless tests
 * and editor previews that never fit see the authored coordinates unchanged.
 */
public final class Field {
    private Field() { }

    /** Reference frame width. */
    public static final float REF_W = Params.REF_W;
    /** Reference frame height. */
    public static final float REF_H = Params.REF_H;
    /** Stretch centre x in the reference frame (the screen centre). */
    public static final float CX = REF_W * 0.5f;
    /** Stretch centre y in the reference frame (the screen centre). */
    public static final float CY = REF_H * 0.5f;
    /** World y of the screen centre after {@link #fit}: the camera centre of every screen. */
    public static final float WORLD_CY = Ui.CY;

    private static float kx = 1f;
    private static float ky = 1f;
    /** World y of the reference centre {@link #CY}: {@link #WORLD_CY} after {@link #fit}, else {@link #CY}. */
    private static float cy = CY;

    /**
     * Fits the field to a world {@code worldW × worldH} centred at (960, 540) (the viewport's visible world):
     * {@code kx = worldW / 1920}, {@code ky = worldH / 1200}. Non-positive or NaN sizes are ignored.
     *
     * @return true when the stretch changed
     */
    public static boolean fit(float worldW, float worldH) {
        if (!(worldW > 0f) || !(worldH > 0f)) return false;
        boolean moved = cy != WORLD_CY;
        cy = WORLD_CY;
        return set(worldW / REF_W, worldH / REF_H) || moved;
    }

    /**
     * Sets the stretch directly (tests and previews; positive factors only, otherwise ignored).
     *
     * @return true when the stretch changed
     */
    public static boolean set(float sx, float sy) {
        if (!(sx > 0f) || !(sy > 0f) || sx == Float.POSITIVE_INFINITY || sy == Float.POSITIVE_INFINITY) return false;
        if (sx == kx && sy == ky) return false;
        kx = sx;
        ky = sy;
        return true;
    }

    /** Back to the reference frame: the identity ({@code kx = ky = 1} about the reference centre). */
    public static void reset() {
        kx = 1f;
        ky = 1f;
        cy = CY;
    }

    /** @return horizontal stretch ({@code worldW / 1920}). */
    public static float kx() {
        return kx;
    }

    /** @return vertical stretch ({@code worldH / 1200}). */
    public static float ky() {
        return ky;
    }

    /** @return world x of reference x. */
    public static float x(float refX) {
        return CX + (refX - CX) * kx;
    }

    /** @return world y of reference y. */
    public static float y(float refY) {
        return cy + (refY - CY) * ky;
    }

    /** @return reference x of world x. */
    public static float refX(float worldX) {
        return CX + (worldX - CX) / kx;
    }

    /** @return reference y of world y. */
    public static float refY(float worldY) {
        return CY + (worldY - cy) / ky;
    }

    /**
     * @return a distance along the travel direction (a note's distance from its line, a tail length) in world
     *         units: {@code d·ky}. Test {@code HIDDEN_POSITION} on the raw value first.
     */
    public static float dist(float d) {
        return d * ky;
    }

    /** @return half height of a judgement zone in world units: {@code 480·ky}. */
    public static float zoneHalf() {
        return Params.JUDGE_ZONE_HALF * ky;
    }

    /** @return half height of the visual band around a line in world units: {@code 120·ky}. */
    public static float bandHalf() {
        return Params.JUDGE_BAND_HALF * ky;
    }
}
