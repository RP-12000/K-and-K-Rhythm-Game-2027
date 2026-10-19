package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;

/**
 * 结算画面的顶部信息行和左栏：右上角的信息行、AUTO 水印、宽屏空白处的大号层号，
 * 以及曲绘、曲名、艺术家和难度标签。
 * 曲绘滑入后声呐边框顺时针描出，静止时轻轻呼吸、点击会倾斜；曲名先解码再以一次 G2 故障收尾，难度标签弹出。
 *
 * <p>The header band and the left column (DESIGN §6.2, §6.3): the meta line (the DIVE LOG line typed
 * on at its left went away on 2026-10-18 17:22, Kelvin 「黑框框起来的删了」), the AUTO
 * watermark, the ghost layer numeral (P2, aspect ≥ 2), the jacket (slide + swing-in, RGB split,
 * sonar frame tracing clockwise, idle breathing, tap tilt), the title decode with its G2 end
 * burst, the artist line and the difficulty chip pop.
 *
 * <p>At rest (no rotation) the jacket is drawn without a batch transform: its shapes go down
 * with the other shapes and the image joins its own page group in the {@link TextQueue}, so the
 * block costs one draw call for the image.
 */
public final class JacketBlock implements TextQueue.Deferred {
    private static final float RADIUS = 16f;
    private static final float FRAME_GAP = 12f;
    private static final float FRAME_D = 0.40f;

    private final ResultKit k;
    private Texture jacket;
    private float u0, v0, u1, v1;
    private String titleShown = "";
    private float titleCap = 34f;
    private boolean titleCjk;
    private float chipW, chipTextW, chipMarkW;
    private static final float GHOST_CAP = 460f;
    private float ghostCap, ghostX;
    private final Color tmp = new Color();
    private float jx, jy, js;
    // deferred image (rest path)
    private float imgX, imgY, imgSize, imgRadius, imgAlpha;

    /** Creates the block. */
    public JacketBlock(ResultKit kit) {
        this.k = kit;
    }

    /** Sets the jacket texture (library-owned; may be null) and its centre-square crop. */
    public void setJacket(Texture tex) {
        jacket = tex;
        if (tex == null) return;
        float w = tex.getWidth(), h = tex.getHeight();
        float side = Math.min(w, h);
        u0 = (w - side) * 0.5f / w;
        u1 = 1f - u0;
        v0 = (h - side) * 0.5f / h;
        v1 = 1f - v0;
    }

    /** Fits the title and the chip for the current layout (allocates only when truncating). */
    public void measure(ResultModel m, ResultLayout l) {
        TextQueue q = k.text;
        titleCjk = k.fonts.needsCjk(Fonts.Role.UI_BOLD, m.title);
        float w = q.width(Fonts.Role.UI_BOLD, 34f, m.title);
        titleCap = w > l.titleMaxW && w > 0f ? 34f * Math.max(0.6f, l.titleMaxW / w) : 34f;
        titleShown = m.title;
        if (q.width(Fonts.Role.UI_BOLD, titleCap, titleShown) > l.titleMaxW + 0.5f) {
            String s = m.title;
            int n = s.length();
            while (n > 1 && q.width(Fonts.Role.UI_BOLD, titleCap, s.substring(0, n) + "…") > l.titleMaxW) n--;
            titleShown = s.substring(0, n).trim() + "…";
        }
        chipTextW = q.width(Fonts.Role.UI_BOLD, 22f, m.chipText);
        chipMarkW = m.chipMark.isEmpty() ? 0f
            : 22f * Theme.CONST_MARK_GAP + q.width(Fonts.Role.UI_BOLD, 22f * Theme.CONST_MARK_SCALE, m.chipMark);
        chipW = Math.max(l.chipW, chipTextW + chipMarkW + 48f);
        // ghost layer numeral (P2): only inside a gutter that is wider than the numeral
        ghostCap = 0f;
        if (l.gutterX1 > l.gutterX0 && !m.layerNumeral.isEmpty()) {
            float gw = k.fonts.width(Fonts.Role.DISPLAY_OUTLINE, GHOST_CAP, Fonts.GlyphSet.DIGITS, m.layerNumeral);
            if (gw > 0f && gw + 32f <= l.gutterX1 - l.gutterX0) {
                ghostCap = GHOST_CAP;
                ghostX = (l.gutterX0 + l.gutterX1) * 0.5f;
            }
        }
    }

    /** Cap of the ghost numeral, or 0 when it is not drawn (no gutter wide enough). */
    public float ghostCap() {
        return ghostCap;
    }

    /** Cap of the title as drawn (its G2 burst bucket is prewarmed by the screen). */
    public float titleCap() {
        return titleCap;
    }

    /** The title as drawn (possibly truncated). */
    public String titleShown() {
        return titleShown;
    }

    /** True when (x, y) is over the jacket. */
    public boolean hitJacket(float x, float y) {
        return x >= jx - js * 0.5f && x <= jx + js * 0.5f && y >= jy - js * 0.5f && y <= jy + js * 0.5f;
    }

    /** Jacket centre x (for the tilt direction). */
    public float jacketX() {
        return jx;
    }

    /**
     * Draws the meta line, watermark and ghost numeral.
     */
    public void drawHeader(ResultModel m, ResultLayout l, float t, Color accent, float alpha) {
        TextQueue q = k.text;
        if (m.autoplay) {
            float wa = Anim.progress(t, Choreo.HEADER_T, 0.4f) * alpha;
            q.text(Fonts.Role.DISPLAY_OUTLINE, 200f, Fonts.GlyphSet.WORDS, "AUTO", l.watermarkX, l.watermarkBase,
                Align.right, Theme.AUTO, 0.06f * wa);
        }
        if (ghostCap > 0f) {
            float ga = Anim.progress(t, Choreo.HEADER_T, 0.8f) * alpha;
            q.text(Fonts.Role.DISPLAY_OUTLINE, ghostCap, Fonts.GlyphSet.DIGITS, m.layerNumeral, ghostX, l.ghostBase,
                Align.center, Theme.INK1, 0.05f * ga);
        }
        if (t < Choreo.HEADER_T) return;
        if (!m.meta.isEmpty()) {
            float ma = Anim.progress(t, Choreo.HEADER_T, 0.25f) * alpha;
            q.text(Fonts.Role.UI_LABEL, 18f, m.meta, l.metaRightX, l.headerBase, Align.right,
                m.autoplay ? Theme.AUTO : Theme.INK3, ma);
        }
    }

    /**
     * Draws the jacket with its frame.
     *
     * @param t     timeline seconds (also drive the idle breathing)
     * @param tilt  extra rotation from the tap spring (degrees)
     * @param press scale from the tap spring
     */
    public void drawJacket(ResultModel m, ResultLayout l, float t, float tilt, float press, Color accent,
                           boolean reduceMotion, float alpha) {
        float p = Anim.progress(t, Choreo.JACKET_T, Choreo.JACKET_D);
        if (p <= 0f) return;
        float size = l.jacketSize;
        float ox, rot, a;
        if (reduceMotion) {
            ox = 0f;
            rot = 0f;
            a = Anim.progress(t, Choreo.JACKET_T, Anim.SHORT);
        } else {
            ox = -220f * (1f - Anim.POW3_OUT.apply(p));
            rot = -4f * (1f - Anim.SWING_OUT_16.apply(p));
            a = ResultKit.clamp01(p / 0.4f);
        }
        a *= alpha;
        float settled = t - (Choreo.JACKET_T + Choreo.JACKET_D);
        float breathe = settled > 0f && !reduceMotion ? 1f + 0.01f * (float) Math.sin(settled * Math.PI * 2f / 4f) : 1f;
        float s = breathe * press;
        jx = l.jacketX + size * 0.5f + ox;
        jy = l.jacketY + size * 0.5f;
        js = size;
        float split = reduceMotion ? 0f : 6f * (1f - ResultKit.clamp01((t - Choreo.JACKET_T) / 0.15f));
        float deg = rot + tilt;
        boolean rest = Math.abs(deg) < 0.01f && split <= 0.05f && Anim.progress(t, Choreo.JACKET_T, FRAME_D) >= 1f;
        // at rest: world coordinates scaled by hand (no transform, image deferred to its page)
        float cx = rest ? jx : 0f, cy = rest ? jy : 0f, sc = rest ? s : 1f;
        if (!rest) k.push(jx, jy, deg, s, s);
        float h = size * 0.5f * sc;
        Panel.shadow(k.draw, cx - h, cy - h, 2f * h, 2f * h, a);
        if (jacket != null) {
            if (split > 0.05f) {
                k.image.draw(k.batch, jacket, u0, v0, u1, v1, cx - h - split, cy - h, 2f * h, 2f * h, RADIUS * sc,
                    Color.toFloatBits(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, 0.45f * a));
                k.image.draw(k.batch, jacket, u0, v0, u1, v1, cx - h + split, cy - h, 2f * h, 2f * h, RADIUS * sc,
                    Color.toFloatBits(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, 0.45f * a));
            }
            imgX = cx - h;
            imgY = cy - h;
            imgSize = 2f * h;
            imgRadius = RADIUS * sc;
            imgAlpha = a;
            k.text.deferred(jacket, this, 0);
            // the 1-unit border sits just outside the image so the deferred image never covers it
            k.draw.roundRectStroke(cx - h - 0.5f, cy - h - 0.5f, 2f * h + 1f, 2f * h + 1f, RADIUS * sc + 0.5f, 1f,
                Theme.STROKE, a);
        } else {
            Theme.mix(accent, Theme.PANEL_HI, 0.55f, tmp);
            k.draw.roundRectGradient(cx - h, cy - h, 2f * h, 2f * h, RADIUS * sc, Theme.ABYSS_BLACK, tmp, a);
            k.draw.glowDot(cx, cy + h * 0.35f, 2f * h * 1.1f, accent, 0.18f * a);
            k.draw.roundRectStroke(cx - h, cy - h, 2f * h, 2f * h, RADIUS * sc, 1f, Theme.STROKE, a);
            k.text.text(Fonts.Role.DISPLAY_OUTLINE, size * 0.22f * sc, Fonts.GlyphSet.LOGO, "K&K", cx, cy,
                Align.center | Fonts.VCENTER, Theme.INK1, 0.30f * a);
        }
        drawFrame(t, cx, cy, h + FRAME_GAP * sc, sc, accent, a);
        if (!rest) k.pop();
    }

    /** Deferred jacket image (rest path), drawn with the jacket texture's page group. */
    @Override
    public void drawDeferred(int arg) {
        k.image.draw(k.batch, jacket, u0, v0, u1, v1, imgX, imgY, imgSize, imgSize, imgRadius,
            Color.toFloatBits(1f, 1f, 1f, imgAlpha));
    }

    /** Sonar frame around (cx, cy): traces clockwise from the top-left corner over 400 ms, then rests. */
    private void drawFrame(float t, float cx, float cy, float h, float sc, Color accent, float a) {
        float q = Anim.progress(t, Choreo.JACKET_T, FRAME_D);
        if (q <= 0f) return;
        float fa = 0.55f * a;
        if (q >= 1f) {
            k.draw.roundRectStroke(cx - h, cy - h, 2f * h, 2f * h, (RADIUS + FRAME_GAP) * sc, 2f, accent, 0.35f * a);
            float c = 34f * sc;
            corner(cx - h, cy + h, 1f, -1f, c, accent, fa * 1.4f);
            corner(cx + h, cy + h, -1f, -1f, c, accent, fa * 1.4f);
            corner(cx + h, cy - h, -1f, 1f, c, accent, fa * 1.4f);
            corner(cx - h, cy - h, 1f, 1f, c, accent, fa * 1.4f);
            return;
        }
        float side = 2f * h;
        float len = 4f * side * Anim.POW2_OUT.apply(q);
        // clockwise from top-left: top (→), right (↓), bottom (←), left (↑)
        float hx = cx - h, hy = cy + h;
        for (int i = 0; i < 4 && len > 0f; i++) {
            float seg = Math.min(side, len);
            float ax = cx + SEG_X[i] * h, ay = cy + SEG_Y[i] * h;
            hx = ax + DIR_X[i] * seg;
            hy = ay + DIR_Y[i] * seg;
            k.draw.line(ax, ay, hx, hy, 2f, accent, fa);
            len -= seg;
        }
        k.draw.glowDot(hx, hy, 34f, accent, 0.9f * a);
        k.draw.disc(hx, hy, 3.5f, Theme.INK1, a);
    }

    private static final float[] SEG_X = {-1f, 1f, 1f, -1f};
    private static final float[] SEG_Y = {1f, 1f, -1f, -1f};
    private static final float[] DIR_X = {1f, 0f, -1f, 0f};
    private static final float[] DIR_Y = {0f, -1f, 0f, 1f};

    private void corner(float x, float y, float sx, float sy, float len, Color c, float a) {
        k.draw.line(x, y, x + sx * len, y, 3f, c, a);
        k.draw.line(x, y, x, y + sy * len, 3f, c, a);
    }

    /**
     * Draws the title (decode → G2 burst), the artist and the difficulty chip.
     *
     * @param titleState glitch state of the title (G2 burst at the end of the decode)
     */
    public void drawTitle(ResultModel m, ResultLayout l, float t, GlitchState titleState, boolean reduceMotion,
                          float alpha) {
        TextQueue q = k.text;
        float el = t - Choreo.TITLE_T;
        if (el >= 0f) {
            CharSequence text = titleShown;
            float a = alpha;
            if (titleCjk || reduceMotion) {
                a *= Anim.progress(t, Choreo.TITLE_T, Choreo.TITLE_D);
            } else {
                float doneEl = (titleShown.length() - 1) * Choreo.TITLE_STAGGER + 0.04f;
                if (el < doneEl) {
                    text = GlitchText.decodeInto(k.sb2, titleShown, el, Choreo.TITLE_STAGGER, 0x7171);
                }
            }
            if (titleState.isBursting(t)) {
                // the G2 burst needs GlitchText (its own bucket, prewarmed); otherwise the shared raster
                k.glitch.draw(k.batch, Fonts.Role.UI_BOLD, titleCap, text, l.titleX, l.titleCapTop,
                    Align.left | Align.top, Theme.INK1, a, titleState, t);
            } else {
                q.text(Fonts.Role.UI_BOLD, titleCap, text, l.titleX, l.titleCapTop, Align.left | Align.top, Theme.INK1, a);
            }
        }
        // artist
        float ap = Anim.progress(t, Choreo.ARTIST_T, Choreo.ARTIST_D);
        if (ap > 0f) {
            float rise = reduceMotion ? 0f : -8f * (1f - Anim.POW3_OUT.apply(ap));
            q.textFit(Fonts.Role.UI, 22f, m.artist, l.titleX, l.artistCapTop + rise, Align.left | Align.top,
                l.titleMaxW, 0.82f, Theme.INK2, ap * alpha);
        }
        // difficulty chip (pop)
        if (t >= Choreo.ARTIST_T) {
            float pp = Anim.progress(t, Choreo.ARTIST_T, Anim.POP);
            float s = reduceMotion ? 1f : Math.max(0.001f, Anim.POP_EASE.apply(pp));
            float ca = alpha * Math.min(1f, pp * 4f);
            float cw = chipW, ch = l.chipH;
            float cx = l.chipX + cw * 0.5f, cy = l.chipY + ch * 0.5f;
            boolean px = k.pushIf(cx, cy, 0f, s, s);
            float x0 = k.ox - cw * 0.5f, y0 = k.oy - ch * 0.5f;
            k.draw.roundRect(x0, y0, cw, ch, 12f, Theme.difficultyFill(m.difficulty), ca);
            float bw = Theme.difficultyBorderWidth(m.difficulty);
            if (bw > 0f) {
                k.draw.roundRectStroke(x0, y0, cw, ch, 12f, bw, Theme.difficultyBorder(m.difficulty), ca);
            }
            float tx = k.ox - (chipTextW + chipMarkW) * 0.5f;
            q.text(Fonts.Role.UI_BOLD, 22f, m.chipText, tx, k.oy, Align.left | Fonts.VCENTER,
                Theme.difficultyText(m.difficulty), ca);
            if (chipMarkW > 0f) {
                q.text(Fonts.Role.UI_BOLD, 22f * Theme.CONST_MARK_SCALE, m.chipMark,
                    tx + chipTextW + 22f * Theme.CONST_MARK_GAP, k.oy + 22f * Theme.CONST_MARK_RISE,
                    Align.left | Fonts.VCENTER, Theme.difficultyText(m.difficulty), ca);
            }
            k.popIf(px);
        }
    }
}
