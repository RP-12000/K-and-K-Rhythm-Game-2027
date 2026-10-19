package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;

/**
 * 会缩放的数字（连击数、倒计时）的故障闪烁：按固定格宽排版，数字变化时宽度不抖动。
 * 有 G2（换字体、青 / 品红错位、撕裂条）和 G2 红色两种爆发，强度随故障强度设置缩放；不分配内存。
 *
 * <p>Scaled numbers with a G2 / G2-red glitch for gameplay punches (combo, countdown). These are
 * fixed-cell digits (DESIGN A.2d: the width must not jitter while the count changes), which
 * {@link GlitchText}'s scale overload cannot provide (it lays text out proportionally; the
 * judgement word uses it).
 *
 * <p>Only digit strings ({@code 0-9 , . : % + - /} and space, the {@link Fonts} digit glyph
 * set) are supported. Text is laid out in fixed {@link Fonts} cells and every cell is drawn with
 * {@link Fonts#drawCell} scaled about the text centre, so the font bucket never changes while
 * the size animates. A burst follows DESIGN §1.4: face swap
 * NUMBER → DISPLAY → DISPLAY_SOFT (only for faces that share advances), cyan / magenta split
 * copies (red copies plus a 2-frame jitter for G2-red) and 1–3 tear bars, all scaled by
 * {@link GlitchText#intensity()} and suppressed per {@link GlitchText#reduceFlashing()}.
 * No allocation.
 */
final class ScaledGlitch {
    /** Plain text, no glitch. */
    static final int NONE = 0;
    /** G2 burst (face swap + chromatic split + tear bars). */
    static final int G2 = 1;
    /** G2-red burst (red split, 2-frame jitter, red tear bars, no face swap). */
    static final int G2_RED = 2;

    private static final float FRAME = 1f / 60f;
    private static final Fonts.Role[] SWAP = {Fonts.Role.NUMBER, Fonts.Role.DISPLAY, Fonts.Role.DISPLAY_SOFT};
    private static final int MAX_CHARS = 32;

    private final Fonts fonts;
    private final Draw draw;
    private final float[] cellW = new float[MAX_CHARS];

    /** Creates the helper over the shared services. */
    ScaledGlitch(Fonts fonts, Draw draw) {
        this.fonts = fonts;
        this.draw = draw;
    }

    /** @return the unscaled width {@link #draw} lays {@code text} out at. */
    float width(Fonts.Role role, float cap, CharSequence text) {
        float w = 0f;
        for (int i = 0, n = Math.min(MAX_CHARS, text.length()); i < n; i++) w += fonts.cellWidth(role, cap, text.charAt(i));
        return w;
    }

    /**
     * Draws {@code text} centred on {@code cx} at baseline {@code baseline}, scaled by
     * {@code scale} about its centre.
     *
     * @param mode   {@link #NONE}, {@link #G2} or {@link #G2_RED}
     * @param burstT 0..1 progress of the burst (face swap order), ignored for NONE
     * @param seed   per-element seed for the hashed slices
     * @param time   seconds (24 Hz slice ticks)
     * @return the scaled width
     */
    float draw(Batch b, Fonts.Role rest, float cap, CharSequence text, float cx, float baseline, float scale,
               Color color, float alpha, int mode, float burstT, float burstElapsed, int seed, float time) {
        int n = Math.min(MAX_CHARS, text.length());
        if (n == 0 || alpha <= 0.003f) return 0f;
        float total = 0f;
        for (int i = 0; i < n; i++) {
            cellW[i] = fonts.cellWidth(rest, cap, text.charAt(i));
            total += cellW[i];
        }
        float I = GlitchText.intensity();
        boolean reduce = GlitchText.reduceFlashing();
        if (I <= 0.001f) mode = NONE;
        Fonts.Role role = rest;
        if (mode == G2 && rest.swaps()) {
            int idx = Math.min(3, (int) (burstT * 4f));
            role = idx < 3 ? SWAP[idx] : rest;
        }
        float sizeK = Math.max(1f, cap * scale / 60f);
        int tick = (int) Math.floor(time * 24f);
        int h = seed * 31 + tick;
        float old = b.getPackedColor();
        if (mode == G2) {
            float a = 6f * I * sizeK;
            float ca = 0.35f * alpha * color.a;
            b.setColor(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, ca);
            cells(b, role, cap, text, n, total, cx - a, baseline, scale);
            b.setColor(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, ca);
            cells(b, role, cap, text, n, total, cx + a, baseline, scale);
        } else if (mode == G2_RED) {
            float d = 4f * I * sizeK;
            float jx = 0f;
            float jy = 0f;
            if (burstElapsed < 2f * FRAME && !reduce) {
                jx = (Anim.hash01(h, 60) * 2f - 1f) * 4f;
                jy = (Anim.hash01(h, 61) * 2f - 1f) * 4f;
            }
            float ca = 0.35f * alpha * color.a;
            b.setColor(Theme.LOST.r, Theme.LOST.g, Theme.LOST.b, ca);
            cells(b, role, cap, text, n, total, cx - d + jx, baseline + jy, scale);
            cells(b, role, cap, text, n, total, cx + d + jx, baseline + jy, scale);
            cx += jx;
            baseline += jy;
        }
        b.setColor(color.r, color.g, color.b, color.a * alpha);
        cells(b, role, cap, text, n, total, cx, baseline, scale);
        if (mode != NONE && !reduce) {
            int bars = 1 + (int) (Anim.hash01(h, 70) * 2.999f);
            float w = total * scale;
            float c = cap * scale;
            float left = cx - w * 0.5f;
            for (int k = 0; k < bars; k++) {
                float bh = (4f + 10f * Anim.hash01(h, 80 + k)) * sizeK * 0.8f;
                float by = baseline - c * 0.1f + (c * 1.2f - bh) * Anim.hash01(h, 90 + k);
                float bw = (w + 48f) * (0.35f + 0.65f * Anim.hash01(h, 110 + k));
                float bx = left - 24f + (w + 48f - bw) * Anim.hash01(h, 120 + k);
                Color tc = mode == G2_RED ? Theme.LOST : ((k & 1) == 0 ? Theme.GLITCH_C : Theme.GLITCH_M);
                draw.rect(bx, by, bw, bh, tc, 0.25f * alpha);
            }
        }
        b.setPackedColor(old);
        return total * scale;
    }

    private void cells(Batch b, Fonts.Role role, float cap, CharSequence text, int n, float total, float cx,
                       float baseline, float scale) {
        float x = cx - total * 0.5f;
        for (int i = 0; i < n; i++) {
            float w = cellW[i];
            float centre = x + w * 0.5f;
            float sc = cx + (centre - cx) * scale;
            char ch = text.charAt(i);
            if (ch != ' ') fonts.drawCell(b, role, cap, ch, sc - w * 0.5f, baseline, scale);
            x += w;
        }
    }
}
