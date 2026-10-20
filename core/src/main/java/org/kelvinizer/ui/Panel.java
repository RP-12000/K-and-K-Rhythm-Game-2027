package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;

/**
 * 面板绘制辅助（静态方法）：卡片、加亮卡片、阴影、全屏暗幕、分隔线和分节标题。
 * 还有可选的边框流光（panel life）：一段高光每 6 秒沿卡片边框绕行一圈；全部不分配内存。
 *
 * <p>Panel helpers (DESIGN §1.6): cards, scrims, dividers, section headers and the optional
 * "panel life" highlight that travels around a card's border once per 6 s (P2). Static,
 * allocation-free; every method needs a drawing batch.
 */
public final class Panel {
    /** Standard card radius. */
    public static final float RADIUS = Draw.PANEL_RADIUS;
    /** Period of the panel-life highlight (s). */
    public static final float LIFE_PERIOD = 6f;
    private static final float LIFE_LENGTH = 120f;
    private static final int LIFE_PIECES = 16;

    private Panel() { }

    /** Standard card: {@code panel} fill, 1-unit stroke, top highlight (radius 18). */
    public static void card(Draw d, float x, float y, float w, float h, float alpha) {
        d.panel(x, y, w, h, RADIUS, alpha);
    }

    /** Raised card ({@code panelHi} fill) for sheets, dialogs and selected rows. */
    public static void cardHi(Draw d, float x, float y, float w, float h, float radius, float alpha) {
        d.roundRect(x, y, w, h, radius, Theme.PANEL_HI, alpha);
        d.roundRectStroke(x, y, w, h, radius, 1f, Theme.STROKE, alpha);
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        d.rect(x + r, y + h - 2f, w - 2f * r, 1f, Theme.PANEL_HIGHLIGHT, alpha * 1.4f);
    }

    private static final Color SOLID_HI = new Color(Theme.PANEL_HI.r, Theme.PANEL_HI.g, Theme.PANEL_HI.b, 1f);

    /** {@link #cardHi} with an opaque fill, for popovers over text (nothing under them shows through). */
    public static void cardSolid(Draw d, float x, float y, float w, float h, float radius, float alpha) {
        d.roundRect(x, y, w, h, radius, SOLID_HI, alpha);
        d.roundRectStroke(x, y, w, h, radius, 1f, Theme.STROKE, alpha);
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        d.rect(x + r, y + h - 2f, w - 2f * r, 1f, Theme.PANEL_HIGHLIGHT, alpha * 1.4f);
    }

    /** Soft drop shadow under a card (a stretched glow, cheap). */
    public static void shadow(Draw d, float x, float y, float w, float h, float alpha) {
        d.sprite(d.softDot(), x + w * 0.5f, y + h * 0.5f - 14f, w + 140f, h + 140f, Theme.SHADOW, 0.55f * alpha);
    }

    /** Full-screen scrim over the visible world (dialogs, pause). */
    public static void scrim(Draw d, Color c, float alpha) {
        d.rect(Ui.left(), Ui.bottom(), Ui.worldW(), Ui.worldH(), c, alpha);
    }

    /** 1-unit horizontal divider in {@code stroke}. */
    public static void divider(Draw d, float x1, float x2, float y, float alpha) {
        d.rect(x1, y, x2 - x1, 1f, Theme.STROKE, alpha);
    }

    /**
     * Section header: UI_LABEL caps in {@code ink3} with its baseline at {@code y}, left-aligned at
     * {@code x}, followed by a hairline to {@code lineEndX} (pass ≤ x for none).
     */
    public static void header(Batch b, Draw d, Fonts f, CharSequence text, float x, float y, float lineEndX,
                              float alpha) {
        float w = f.draw(b, Fonts.Role.UI_LABEL, 16f, text, x, y, com.badlogic.gdx.utils.Align.left, Theme.INK3, alpha);
        if (lineEndX > x + w + 24f) divider(d, x + w + 16f, lineEndX, y + 7f, alpha);
    }

    /**
     * Panel life [P2]: a 120-unit highlight segment travelling around the rounded border once
     * per {@link #LIFE_PERIOD} at α .25 ({@code phaseOffset} de-syncs neighbouring cards).
     */
    public static void life(Draw d, float x, float y, float w, float h, float radius, float time,
                            float phaseOffset, float alpha) {
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        float straightW = w - 2f * r, straightH = h - 2f * r;
        float arc = MathUtils.HALF_PI * r;
        float perim = 2f * (straightW + straightH) + 4f * arc;
        if (perim <= 0f) return;
        float head = ((time / LIFE_PERIOD + phaseOffset) % 1f + 1f) % 1f * perim;
        float step = LIFE_LENGTH / LIFE_PIECES;
        float px = 0f, py = 0f;
        for (int i = 0; i <= LIFE_PIECES; i++) {
            float s = head - i * step;
            if (s < 0f) s += perim;
            float qx = pointX(s, x, y, w, h, r, straightW, straightH, arc);
            float qy = pointY(s, x, y, w, h, r, straightW, straightH, arc);
            if (i > 0) {
                float k = 1f - (i - 0.5f) / LIFE_PIECES;
                d.line(px, py, qx, qy, 2f, Theme.INK1, 0.25f * k * alpha);
            }
            px = qx;
            py = qy;
        }
    }

    // Perimeter walk, clockwise from the top-left end of the top edge.
    private static float pointX(float s, float x, float y, float w, float h, float r, float sw, float sh, float arc) {
        if (s < sw) return x + r + s;
        s -= sw;
        if (s < arc) return x + w - r + r * MathUtils.sin(s / r);
        s -= arc;
        if (s < sh) return x + w;
        s -= sh;
        if (s < arc) return x + w - r + r * MathUtils.cos(s / r);
        s -= arc;
        if (s < sw) return x + w - r - s;
        s -= sw;
        if (s < arc) return x + r - r * MathUtils.sin(s / r);
        s -= arc;
        if (s < sh) return x;
        s -= sh;
        return x + r - r * MathUtils.cos(Math.min(s, arc) / r);
    }

    private static float pointY(float s, float x, float y, float w, float h, float r, float sw, float sh, float arc) {
        if (s < sw) return y + h;
        s -= sw;
        if (s < arc) return y + h - r + r * MathUtils.cos(s / r);
        s -= arc;
        if (s < sh) return y + h - r - s;
        s -= sh;
        if (s < arc) return y + r - r * MathUtils.sin(s / r);
        s -= arc;
        if (s < sw) return y;
        s -= sw;
        if (s < arc) return y + r - r * MathUtils.cos(s / r);
        s -= arc;
        if (s < sh) return y + r + s;
        s -= sh;
        return y + h - r + r * MathUtils.sin(Math.min(s, arc) / r);
    }
}
