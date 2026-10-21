package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.utils.viewport.ExtendViewport;

/**
 * 所有画面共用的视口计算：世界坐标以 1920 × 1080 为最小可见区域，较长的一边向外延伸，相机居中于 (960, 540)。
 * 提供可见边缘、安全边（避开刘海并加上设置里的额外边距）、界面缩放和最小触摸目标等数值，窗口大小改变时重新计算。
 *
 * <p>Viewport math shared by every screen (DESIGN §1.1, ARCHITECTURE §3, Appendix A.1).
 *
 * <p>The world is an {@code ExtendViewport(1920, 1080)}: the minimum world is 1920×1080 and the
 * longer axis extends. Screens centre their camera at (960, 540), so the visible world edges are
 * symmetric around that point. All values are recomputed by {@link #onResize(int, int)}.
 *
 * <p><b>Resize order.</b> {@code Main.resize(w, h)} must call {@link UiKit#resize(int, int)}
 * <em>before</em> {@code screen.resize(w, h)}: UiKit runs {@link #onResize}, re-buckets the fonts
 * ({@link Fonts#onResize()}) and resizes the overlay viewports. As a safety net
 * {@link KkScreen#resize} calls {@link #onResizeIfChanged(int, int)}, so a screen resized
 * directly still gets correct edges and {@link #pxPerWorld()}, but fonts are only re-bucketed
 * through UiKit.
 *
 * <p>Static state here is plain numbers (no GL objects), safe across context loss.
 */
public final class Ui {
    private Ui() { }

    /** Reference (minimum) world width. */
    public static final float REF_W = 1920f;
    /** Reference (minimum) world height. */
    public static final float REF_H = 1080f;
    /** Height of the playfield's reference frame ({@code Params.REF_H}; the field stretches it onto the world). */
    private static final float FIELD_H = 1200f;
    /** Camera centre x used by every screen camera. */
    public static final float CX = 960f;
    /** Camera centre y used by every screen camera. */
    public static final float CY = 540f;
    /** Minimum margin between content and a visible / cutout edge, in world units. */
    public static final float MIN_MARGIN = 24f;
    /** Aspect ratio at which layouts switch from TALL (bottom sheet) to WIDE (side card). */
    public static final float WIDE_ASPECT = 1.6f;
    /** Gameplay switches to the band HUD when the free band is at least this tall. */
    public static final float BAND_HUD_MIN = 104f;
    /**
     * Top edge of the sky line's band, y in the playfield's 1920 × 1200 reference frame: the line 1080 + 120 since
     * 2026-10-19 14:55 (1186.67 before; 1068 at 1080 high).
     */
    public static final float SKY_ZONE_TOP = 1200f;
    /** Bottom edge of the ground line's band, y in the playfield's reference frame: 120 − 120 (13.33 before). */
    public static final float GROUND_ZONE_BOTTOM = 0f;

    /** Layout classes (DESIGN §1.1). */
    public enum Layout {
        /** Aspect ≥ 1.6: side card, gauge on the right. */
        WIDE,
        /** Aspect &lt; 1.6: bottom sheet. */
        TALL
    }

    private static int screenW = 1920, screenH = 1080;
    private static float worldW = REF_W, worldH = REF_H;
    private static float pxPerWorld = 1f;
    private static float insetL, insetR, insetT, insetB;
    private static float edgePadding;
    private static float uiScaleSetting;
    private static float density = 1f;
    private static int generation;

    // ------------------------------------------------------------------ settings hooks
    /** Extra edge padding from Settings (0..48 world units), folded into the safe edges. */
    public static void setEdgePadding(float padding) {
        edgePadding = Math.max(0f, Math.min(48f, padding));
    }

    /** The Settings edge padding (0..48 world units) folded into the safe edges. */
    public static float edgePadding() {
        return edgePadding;
    }

    /** UI scale setting: 0 = Auto, else 0.9..1.3. */
    public static void setUiScaleSetting(float scale) {
        uiScaleSetting = scale <= 0f ? 0f : Math.max(0.9f, Math.min(1.3f, scale));
    }

    // ------------------------------------------------------------------ resize
    /**
     * Recomputes every metric for a back-buffer of {@code width}×{@code height} logical pixels.
     * Reads safe insets and density from {@code Gdx.graphics} when available.
     */
    public static void onResize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        screenW = width;
        screenH = height;
        float aspect = width / (float) height;
        if (aspect >= REF_W / REF_H) {
            worldH = REF_H;
            worldW = REF_H * aspect;
        } else {
            worldW = REF_W;
            worldH = REF_W / aspect;
        }
        float backH = height;
        float l = 0, r = 0, t = 0, b = 0;
        if (Gdx.graphics != null) {
            int bh = Gdx.graphics.getBackBufferHeight();
            if (bh > 0) backH = bh;
            l = Gdx.graphics.getSafeInsetLeft();
            r = Gdx.graphics.getSafeInsetRight();
            t = Gdx.graphics.getSafeInsetTop();
            b = Gdx.graphics.getSafeInsetBottom();
            float d = Gdx.graphics.getDensity();
            density = (d > 0f && !Float.isNaN(d)) ? Math.max(0.6f, Math.min(4f, d)) : 1f;
        }
        pxPerWorld = backH / worldH;
        float worldPerScreenPx = worldW / width;
        insetL = l * worldPerScreenPx;
        insetR = r * worldPerScreenPx;
        insetT = t * worldPerScreenPx;
        insetB = b * worldPerScreenPx;
        generation++;
    }

    /**
     * Runs {@link #onResize(int, int)} only when {@code width}×{@code height} differs from the
     * last resize (no generation bump otherwise). Used by {@link KkScreen#resize}.
     */
    public static void onResizeIfChanged(int width, int height) {
        if (width != screenW || height != screenH || generation == 0) onResize(width, height);
    }

    /** Overrides the safe insets (screen px) — for tests / simulated cutouts. */
    public static void simulateInsets(float leftPx, float rightPx, float topPx, float bottomPx) {
        float k = worldW / screenW;
        insetL = leftPx * k;
        insetR = rightPx * k;
        insetT = topPx * k;
        insetB = bottomPx * k;
        generation++;
    }

    /** Incremented on every resize; layouts can cache against it. */
    public static int generation() {
        return generation;
    }

    /**
     * Updates an {@code ExtendViewport} for a resize and centres its camera at (960, 540).
     * Does not call {@link #onResize}.
     */
    public static void applyViewport(ExtendViewport viewport, int width, int height) {
        viewport.update(width, height, false);
        Camera cam = viewport.getCamera();
        cam.position.set(CX, CY, 0f);
        cam.update();
    }

    // ------------------------------------------------------------------ world metrics
    /** Visible world width (≥ 1920). */
    public static float worldW() {
        return worldW;
    }

    /** Visible world height (≥ 1080). */
    public static float worldH() {
        return worldH;
    }

    /** Visible world left edge (VL) for a camera centred at (960, 540). */
    public static float left() {
        return CX - worldW * 0.5f;
    }

    /** Visible world right edge (VR). */
    public static float right() {
        return CX + worldW * 0.5f;
    }

    /** Visible world bottom edge (VB). */
    public static float bottom() {
        return CY - worldH * 0.5f;
    }

    /** Visible world top edge (VT). */
    public static float top() {
        return CY + worldH * 0.5f;
    }

    /** Safe left edge (SL): VL + cutout inset + 24 + extra edge padding. */
    public static float safeLeft() {
        return left() + insetL + MIN_MARGIN + edgePadding;
    }

    /** Safe right edge (SR). */
    public static float safeRight() {
        return right() - insetR - MIN_MARGIN - edgePadding;
    }

    /** Safe top edge (ST). */
    public static float safeTop() {
        return top() - insetT - MIN_MARGIN - edgePadding;
    }

    /** Safe bottom edge (SB). */
    public static float safeBottom() {
        return bottom() + insetB + MIN_MARGIN + edgePadding;
    }

    /** Uniform back-buffer pixels per world unit. */
    public static float pxPerWorld() {
        return pxPerWorld;
    }

    /** World units per back-buffer pixel. */
    public static float worldPerPx() {
        return 1f / pxPerWorld;
    }

    /** Screen aspect ratio (width / height). */
    public static float aspect() {
        return screenW / (float) screenH;
    }

    /** Layout class: WIDE at aspect ≥ 1.6, else TALL. */
    public static Layout layout() {
        return aspect() >= WIDE_ASPECT - 0.002f ? Layout.WIDE : Layout.TALL;
    }

    /** True for the TALL layout class (aspect &lt; 1.6, tablets). */
    public static boolean isTablet() {
        return layout() == Layout.TALL;
    }

    // ------------------------------------------------------------------ density / scale
    /** Converts density-independent pixels to world units (density clamped to 0.6..4). */
    public static float dp(float dp) {
        return dp * density / pxPerWorld;
    }

    /** Minimum touch target {@code P = max(112, dp(48))} in world units. */
    public static float touchTarget() {
        return Math.max(112f, dp(48f));
    }

    /** Touch slop {@code dp(12)} in world units (at least 12). */
    public static float slop() {
        return Math.max(12f, dp(12f));
    }

    /**
     * UI scale: the Settings value (0.9..1.3) or Auto = 1.15 when the landscape height is below
     * 480 dp, else 1.0. Multiply HUD / menu dimensions by it; never the playfield or map world.
     */
    public static float uiScale() {
        if (uiScaleSetting > 0f) return uiScaleSetting;
        return compactScreen() ? 1.15f : 1f;
    }

    /** True on a phone-sized screen: the short side is under 480 dp (Auto UI scale is 1.15 there). */
    public static boolean compactScreen() {
        return Math.min(screenW, screenH) / density < 480f;
    }

    /** Minimum essential text cap: {@code max(20 · uiScale, dp(7.5))}. */
    public static float minEssentialCap() {
        return Math.max(20f * uiScale(), dp(7.5f));
    }

    // ------------------------------------------------------------------ gameplay bands
    /**
     * Free band above the sky zone. The gameplay field is stretched to fill the visible world (owner
     * 2026-10-12 16:17, {@code gameplay.Field}: the reference centre y 600 on 540, y by {@code worldH / 1200}),
     * so the zone top 1200 lands at {@code 540 + 600·worldH / 1200}, the top of the world: with the lines at
     * 1080 / 120 (2026-10-19 14:55) there is no free band (13.33·worldH / 1200 before).
     */
    public static float freeBandTop() {
        return top() - (CY + (SKY_ZONE_TOP - FIELD_H * 0.5f) * worldH / FIELD_H);
    }

    /** Free band below the ground zone (stretched like {@link #freeBandTop}): 0 with the ground line at 120. */
    public static float freeBandBottom() {
        return (CY + (GROUND_ZONE_BOTTOM - FIELD_H * 0.5f) * worldH / FIELD_H) - bottom();
    }

    /**
     * True when gameplay should use the band HUD layout (free band ≥ 104). With the stretched field the
     * band is 0 on every screen, so gameplay keeps the corner HUD at every aspect.
     */
    public static boolean useBandHud() {
        return freeBandTop() >= BAND_HUD_MIN;
    }
}
