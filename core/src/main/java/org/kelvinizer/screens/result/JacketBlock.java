package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Marquee;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 结算画面的标题带和曲绘：全宽的标题带（曲名和曲师居中），16:9 的大曲绘（背后是曲绘自己颜色的柔光，难度块在它右下角里面；
 * 平板等比例下部渐暗好让评级、分数和新纪录压在上面，比 16:8.5 宽的手机完整显示），以及角色区里的 AUTO 水印。
 * 曲绘滑入时声呐线沿边框描一圈；曲名先解码再以一次 G2 故障收尾，50 个字符以内缩小到放得下，更长的就在标题带里滚动（和选歌卡片同样的节奏）。
 *
 * <p>The title band and the jacket (Kelvin 2026-10-19 06:28, design round 7, and round 8 at 15:2x):
 * a full-width band with the title and the artist centred; the 16:9 jacket (the song's framing) with a soft glow of its
 * own colours behind it (the library's 16 × 16 wash, kept under the band), the difficulty chip in its bottom-right
 * corner over a soft shadow (round 8, 14:59 「把shallow10-这类等级信息移动到曲绘的右下角」), its lower 58 % darkened
 * towards (6, 10, 32) so the stamp, score and best line read over it (not with three columns, where they are under
 * it), and a 1-unit stroke; the AUTO watermark in the character area. The jacket slides in with an RGB split while
 * a sonar line traces its edge, and the chip pops once it has landed; the title decodes and ends in a G2 burst.
 * A title of up to 50 characters shrinks to fit the band (04:55
 * 「50个字符以内的歌名都能够完整显示出来，超过才滚动显示」, 05:12 「歌名一开始就居中」); a longer one keeps its size and
 * scrolls like the song card's ({@link Marquee}), cut to the band's text width with faded ends, after the text
 * queue's flush.
 *
 * <p>The jacket is drawn at once, not deferred into the page groups: its shade, its stroke and the overlays drawn
 * after it must cover it.
 */
public final class JacketBlock {
    /** Titles longer than this many characters scroll instead of shrinking (Kelvin 04:55). */
    public static final int MARQUEE_CHARS = 50;
    private static final String AUTO = "AUTO";
    private static final float FRAME_D = 0.40f, FRAME_FADE = 0.25f;
    /** Glow: size over the jacket's, its fade from the edges (fraction of its size) and its peak alpha. */
    private static final float GLOW_W = 1.24f, GLOW_H = 1.30f, GLOW_FADE_X = 0.24f, GLOW_FADE_Y = 0.30f,
        GLOW_A = 0.59f;
    /** Shade: starts 42 % down, reaches 86 % at the bottom as t^1.3. */
    private static final float SHADE_FROM = 0.42f, SHADE_AMOUNT = 0.86f, SHADE_POWER = 1.3f;
    private static final Color BAND_FILL = new Color(8 / 255f, 13 / 255f, 38 / 255f, 0.43f);
    private static final Color BAND_LINE = new Color(120 / 255f, 200 / 255f, 255 / 255f, 0.27f);
    private static final Color JACKET_STROKE = new Color(140 / 255f, 200 / 255f, 255 / 255f, 0.31f);
    private static final Color SHADE = new Color(6 / 255f, 10 / 255f, 32 / 255f, 1f);
    /** Chip shadow (round 8): (0, 0, 12) at α .47, blurred by 6 and 3 down, as stacked round rects. */
    private static final Color CHIP_SHADOW = new Color(0f, 0f, 12 / 255f, 1f);
    /** Each shadow layer's growth over the chip (outermost first) and alpha: a Gaussian edge of σ 6. */
    private static final float[] SHADOW_GROW = {13.5f, 10.5f, 7.5f, 4.5f, 1.5f, -1.5f};
    private static final float[] SHADOW_A = {0.011f, 0.020f, 0.045f, 0.076f, 0.105f, 0.118f};

    private final ResultKit k;
    private Texture jacket, wash;
    private float u0, v0, u1, v1;
    private boolean titleCjk, marquee;
    private float titleCap = 48f, titleW, watermarkCap;
    private float chipW, chipTextW, chipMarkW;
    private final Color tmp = new Color();
    private final float[] cols = new float[4], rows = new float[6];
    private final float[] quad = new float[20], crop = new float[4];

    /** Creates the block. */
    public JacketBlock(ResultKit kit) {
        this.k = kit;
    }

    /**
     * Sets the jacket texture (library-owned; may be null), its 16:9 crop ({@code box}, the song's framing; null =
     * the centre), and the library's wash of it for the glow (may be null).
     */
    public void setJacket(Texture tex, float[] box, Texture wash) {
        jacket = tex;
        this.wash = wash;
        u0 = 0f;
        u1 = 1f;
        v0 = 0f;
        v1 = 1f;
        if (tex == null) return;
        SongMeta.crop(box, tex.getWidth(), tex.getHeight(), 16f / 9f, crop);
        u0 = crop[0];
        v0 = crop[1];
        u1 = crop[2];
        v1 = crop[3];
    }

    /** Fits the title and the chip for the current layout (no allocation). */
    public void measure(ResultModel m, ResultLayout l) {
        TextQueue q = k.text;
        titleCjk = k.fonts.needsCjk(Fonts.Role.UI_BOLD, m.title);
        marquee = m.title.codePointCount(0, m.title.length()) > MARQUEE_CHARS;
        titleCap = l.titleCap;
        titleW = q.width(Fonts.Role.UI_BOLD, titleCap, m.title);
        if (!marquee && titleW > l.titleMaxW && titleW > 0f) {
            // up to 50 characters the whole title shows, smaller when it must (no ellipsis)
            titleCap *= l.titleMaxW / titleW;
            titleW = q.width(Fonts.Role.UI_BOLD, titleCap, m.title);
        }
        float cc = l.chipCap;
        chipTextW = q.width(Fonts.Role.UI_BOLD, cc, m.chipText);
        chipMarkW = m.chipMark.isEmpty() ? 0f
            : cc * Theme.CONST_MARK_GAP + q.width(Fonts.Role.UI_BOLD, cc * Theme.CONST_MARK_SCALE, m.chipMark);
        chipW = chipTextW + chipMarkW + 2f * l.chipPad;
        watermarkCap = l.watermarkCap;
        if (m.autoplay) {
            float w = k.fonts.width(Fonts.Role.DISPLAY_OUTLINE, watermarkCap, Fonts.GlyphSet.WORDS, AUTO);
            float room = l.charX1 - l.charX0 - 32f * l.s;
            if (w > room && w > 0f) watermarkCap *= room / w;
        }
    }

    /** Cap of the title as drawn (its G2 burst bucket is prewarmed by the screen). */
    public float titleCap() {
        return titleCap;
    }

    /** Cap of the K&K mark drawn when the song has no jacket. */
    public static float fallbackCap(ResultLayout l) {
        return l.jacketH * 0.30f;
    }

    /** Draws the full-width title band (under everything else of the screen). */
    public void drawBand(ResultLayout l, float t, float alpha) {
        float a = alpha * Anim.progress(t, Choreo.JACKET_T, 0.3f);
        if (a <= 0.002f) return;
        float x0 = Ui.left(), w = Ui.worldW(), lw = 1.2f;
        k.draw.rect(x0, l.bandY0, w, l.bandY1 - l.bandY0, BAND_FILL, a);
        k.draw.rect(x0, l.bandY0 - lw * 0.5f, w, lw, BAND_LINE, a);
        k.draw.rect(x0, l.bandY1 - lw * 0.5f, w, lw, BAND_LINE, a);
    }

    /** Cap of the AUTO watermark: the layout's, shrunk to fit the character area. */
    public float watermarkCap() {
        return watermarkCap;
    }

    /** Draws the AUTO watermark in the middle of the character area (autoplay only). */
    public void drawWatermark(ResultModel m, ResultLayout l, float t, float alpha) {
        if (!m.autoplay) return;
        float wa = Anim.progress(t, Choreo.HEADER_T, 0.4f) * alpha;
        float base = (l.charY0 + l.charY1 - watermarkCap) * 0.5f;
        k.text.text(Fonts.Role.DISPLAY_OUTLINE, watermarkCap, Fonts.GlyphSet.WORDS, AUTO, l.watermarkX, base,
            Align.right, Theme.AUTO, 0.06f * wa);
    }

    /**
     * Draws the glow, the jacket, its shade and stroke at once (slide in with a slight swing and an RGB split; the
     * sonar line traces the edge, then fades).
     */
    public void drawJacket(ResultModel m, ResultLayout l, float t, Color accent, boolean reduceMotion, float alpha) {
        float p = Anim.progress(t, Choreo.JACKET_T, Choreo.JACKET_D);
        if (p <= 0f) return;
        float ox, rot, a;
        if (reduceMotion) {
            ox = 0f;
            rot = 0f;
            a = Anim.progress(t, Choreo.JACKET_T, Anim.SHORT);
        } else {
            ox = -220f * l.s * (1f - Anim.POW3_OUT.apply(p));
            rot = -4f * (1f - Anim.SWING_OUT_16.apply(p));
            a = ResultKit.clamp01(p / 0.4f);
        }
        a *= alpha;
        float w = l.jacketW, h = l.jacketH, r = l.jacketR;
        float jx = l.jacketX + w * 0.5f + ox, jy = l.jacketY + h * 0.5f;
        if (wash != null) glow(l, jx, jy, a);
        float split = reduceMotion ? 0f : 6f * l.s * (1f - ResultKit.clamp01((t - Choreo.JACKET_T) / 0.15f));
        boolean turned = Math.abs(rot) >= 0.01f;
        float cx = jx, cy = jy;
        if (turned) {
            k.push(jx, jy, rot, 1f, 1f);
            cx = 0f;
            cy = 0f;
        }
        float x0 = cx - w * 0.5f, y0 = cy - h * 0.5f;
        if (jacket != null) {
            if (split > 0.05f) {
                k.image.draw(k.batch, jacket, u0, v0, u1, v1, x0 - split, y0, w, h, r,
                    Color.toFloatBits(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, 0.45f * a));
                k.image.draw(k.batch, jacket, u0, v0, u1, v1, x0 + split, y0, w, h, r,
                    Color.toFloatBits(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, 0.45f * a));
            }
            k.image.draw(k.batch, jacket, u0, v0, u1, v1, x0, y0, w, h, r, Color.toFloatBits(1f, 1f, 1f, a));
            if (l.shade) {
                k.image.shade(k.batch, k.draw.white(), x0, y0, w, h, r, SHADE, a, SHADE_FROM, SHADE_AMOUNT,
                    SHADE_POWER);
            }
        } else {
            Theme.mix(accent, Theme.PANEL_HI, 0.55f, tmp);
            k.draw.roundRectGradient(x0, y0, w, h, r, Theme.ABYSS_BLACK, tmp, a);
            k.draw.glowDot(cx, cy + h * 0.2f, w * 0.8f, accent, 0.18f * a);
            k.text.text(Fonts.Role.DISPLAY_OUTLINE, fallbackCap(l), Fonts.GlyphSet.LOGO, "K&K", cx, cy + h * 0.12f,
                Align.center | Fonts.VCENTER, Theme.INK1, 0.30f * a);
        }
        k.draw.roundRectStroke(x0, y0, w, h, r, 1.2f, JACKET_STROKE, a);
        drawTrace(t, cx, cy, w * 0.5f + 6f * l.s, h * 0.5f + 6f * l.s, accent, a);
        if (turned) k.pop();
    }

    /**
     * The jacket's own colours glowing behind it: the wash (the same 16:9 crop) stretched over 1.24 × 1.30 of the
     * jacket, its alpha fading to the edges, and nothing over the title band (a 30-unit ramp under its bottom edge).
     * A grid of vertex-coloured quads in one texture.
     */
    private void glow(ResultLayout l, float cx, float cy, float a) {
        float gw = l.jacketW * GLOW_W, gh = l.jacketH * GLOW_H;
        float gx0 = cx - gw * 0.5f, gy0 = cy - gh * 0.5f, gx1 = gx0 + gw;
        float fx = gw * GLOW_FADE_X, fy = gh * GLOW_FADE_Y;
        float clipTop = l.bandY0 - 4f * l.s, clipRamp = 30f * l.s;
        float top = Math.min(gy0 + gh, clipTop);
        if (top <= gy0) return;
        cols[0] = gx0;
        cols[1] = gx0 + fx;
        cols[2] = gx1 - fx;
        cols[3] = gx1;
        // rows: the glow's own fade, the band's ramp, its top; sorted, inside [gy0, top]
        int n = 0;
        rows[n++] = gy0;
        n = addRow(n, gy0 + fy, gy0, top);
        n = addRow(n, gy0 + gh - fy, gy0, top);
        n = addRow(n, clipTop - clipRamp, gy0, top);
        n = addRow(n, top, gy0, top);
        float peak = GLOW_A * a;
        for (int j = 0; j + 1 < n; j++) {
            float ya = rows[j], yb = rows[j + 1];
            float aa = glowY(ya, gy0, gh, fy, clipTop, clipRamp), ab = glowY(yb, gy0, gh, fy, clipTop, clipRamp);
            if (aa <= 0f && ab <= 0f) continue;
            for (int i = 0; i < 3; i++) {
                float xa = cols[i], xb = cols[i + 1];
                float ka = glowX(xa, gx0, gw, fx) * peak, kb = glowX(xb, gx0, gw, fx) * peak;
                if (ka <= 0f && kb <= 0f) continue;
                glowVert(0, xa, ya, ka * aa, gx0, gy0, gw, gh);
                glowVert(5, xa, yb, ka * ab, gx0, gy0, gw, gh);
                glowVert(10, xb, yb, kb * ab, gx0, gy0, gw, gh);
                glowVert(15, xb, ya, kb * aa, gx0, gy0, gw, gh);
                k.batch.draw(wash, quad, 0, 20);
            }
        }
    }

    /** Inserts {@code y} (clamped to [lo, hi]) into the sorted rows unless it is already there. */
    private int addRow(int n, float y, float lo, float hi) {
        y = Math.max(lo, Math.min(hi, y));
        int i = n;
        while (i > 0 && rows[i - 1] > y) i--;
        if (i > 0 && Math.abs(rows[i - 1] - y) < 0.01f) return n;
        if (i < n && Math.abs(rows[i] - y) < 0.01f) return n;
        System.arraycopy(rows, i, rows, i + 1, n - i);
        rows[i] = y;
        return n + 1;
    }

    private static float glowX(float x, float gx0, float gw, float fx) {
        return ResultKit.clamp01(Math.min(x - gx0, gx0 + gw - x) / fx);
    }

    private static float glowY(float y, float gy0, float gh, float fy, float clipTop, float clipRamp) {
        return ResultKit.clamp01(Math.min(y - gy0, gy0 + gh - y) / fy) * ResultKit.clamp01((clipTop - y) / clipRamp);
    }

    private void glowVert(int i, float px, float py, float alpha, float gx0, float gy0, float gw, float gh) {
        quad[i] = px;
        quad[i + 1] = py;
        quad[i + 2] = Color.toFloatBits(1f, 1f, 1f, alpha);
        quad[i + 3] = u0 + (px - gx0) / gw * (u1 - u0);
        quad[i + 4] = v1 - (py - gy0) / gh * (v1 - v0);
    }

    /**
     * Sonar line around the box of half-size (hw, hh) centred on (cx, cy): traces clockwise from the top-left corner
     * over 400 ms with a glowing head, then fades.
     */
    private void drawTrace(float t, float cx, float cy, float hw, float hh, Color accent, float a) {
        float q = Anim.progress(t, Choreo.JACKET_T, FRAME_D);
        if (q <= 0f) return;
        float fade = 1f - Anim.progress(t, Choreo.JACKET_T + FRAME_D, FRAME_FADE);
        if (fade <= 0f) return;
        float fa = 0.55f * a * fade;
        float len = (4f * hw + 4f * hh) * Anim.POW2_OUT.apply(q);
        // clockwise from top-left: top (→), right (↓), bottom (←), left (↑)
        float hx = cx - hw, hy = cy + hh;
        for (int i = 0; i < 4 && len > 0f; i++) {
            float side = (i & 1) == 0 ? 2f * hw : 2f * hh;
            float seg = Math.min(side, len);
            float ax = cx + SEG_X[i] * hw, ay = cy + SEG_Y[i] * hh;
            hx = ax + DIR_X[i] * seg;
            hy = ay + DIR_Y[i] * seg;
            k.draw.line(ax, ay, hx, hy, 2f, accent, fa);
            len -= seg;
        }
        if (q < 1f) {
            k.draw.glowDot(hx, hy, 34f, accent, 0.9f * a);
            k.draw.disc(hx, hy, 3.5f, Theme.INK1, a);
        }
    }

    private static final float[] SEG_X = {-1f, 1f, 1f, -1f};
    private static final float[] SEG_Y = {1f, 1f, -1f, -1f};
    private static final float[] DIR_X = {1f, 0f, -1f, 0f};
    private static final float[] DIR_Y = {0f, -1f, 0f, 1f};

    /**
     * Draws the title (decode → G2 burst; a scrolling title is drawn by {@link #drawMarquee}) and the artist.
     *
     * @param titleState glitch state of the title (G2 burst at the end of the decode)
     */
    public void drawTitle(ResultModel m, ResultLayout l, float t, GlitchState titleState, boolean reduceMotion,
                          float alpha) {
        TextQueue q = k.text;
        float el = t - Choreo.TITLE_T;
        if (el >= 0f && !marquee) {
            CharSequence text = m.title;
            float a = alpha;
            if (titleCjk || reduceMotion) {
                a *= Anim.progress(t, Choreo.TITLE_T, Choreo.TITLE_D);
            } else {
                float doneEl = (m.title.length() - 1) * Choreo.TITLE_STAGGER + 0.04f;
                if (el < doneEl) text = GlitchText.decodeInto(k.sb2, m.title, el, Choreo.TITLE_STAGGER, 0x7171);
            }
            // centred on its final width, so the decoding glyphs never shift it
            float left = l.titleX - titleW * 0.5f;
            if (titleState.isBursting(t)) {
                // the G2 burst needs GlitchText (its own bucket, prewarmed); otherwise the shared raster
                k.glitch.draw(k.batch, Fonts.Role.UI_BOLD, titleCap, text, left, l.titleBase, Align.left, Theme.INK1, a,
                    titleState, t);
            } else {
                q.text(Fonts.Role.UI_BOLD, titleCap, text, left, l.titleBase, Align.left, Theme.INK1, a);
            }
        }
        // artist, centred
        float ap = Anim.progress(t, Choreo.ARTIST_T, Choreo.ARTIST_D);
        if (ap > 0f) {
            float rise = reduceMotion ? 0f : -8f * l.s * (1f - Anim.POW3_OUT.apply(ap));
            q.textFit(Fonts.Role.UI, l.artistCap, m.artist, l.titleX, l.artistBase + rise, Align.center, l.titleMaxW,
                0.82f, Theme.INK2, ap * alpha);
        }
    }

    /**
     * Draws the difficulty chip in the jacket's bottom-right corner (round 8), over its soft shadow; it pops once the
     * jacket has landed. Call after {@link #drawJacket}.
     */
    public void drawChip(ResultModel m, ResultLayout l, float t, boolean reduceMotion, float alpha) {
        if (t < Choreo.CHIP_T) return;
        TextQueue q = k.text;
        float pp = Anim.progress(t, Choreo.CHIP_T, Anim.POP);
        float s = reduceMotion ? 1f : Math.max(0.001f, Anim.POP_EASE.apply(pp));
        float ca = alpha * Math.min(1f, pp * 4f);
        float cw = chipW, ch = l.chipH, cc = l.chipCap;
        float cx = l.chipRight - cw * 0.5f, cy = l.chipY + ch * 0.5f;
        boolean px = k.pushIf(cx, cy, 0f, s, s);
        float x0 = k.ox - cw * 0.5f, y0 = k.oy - ch * 0.5f, drop = 3f * l.s;
        for (int i = 0; i < SHADOW_GROW.length; i++) {
            float g = SHADOW_GROW[i] * l.s;
            k.draw.roundRect(x0 - g, y0 - drop - g, cw + 2f * g, ch + 2f * g, l.chipR + g, CHIP_SHADOW,
                SHADOW_A[i] * ca);
        }
        k.draw.roundRect(x0, y0, cw, ch, l.chipR, Theme.difficultyFill(m.difficulty), ca);
        float bw = Theme.difficultyBorderWidth(m.difficulty);
        if (bw > 0f) {
            k.draw.roundRectStroke(x0, y0, cw, ch, l.chipR, bw, Theme.difficultyBorder(m.difficulty), ca);
        }
        float tx = x0 + l.chipPad;
        q.text(Fonts.Role.UI_BOLD, cc, m.chipText, tx, k.oy, Align.left | Fonts.VCENTER,
            Theme.difficultyText(m.difficulty), ca);
        if (chipMarkW > 0f) {
            q.text(Fonts.Role.UI_BOLD, cc * Theme.CONST_MARK_SCALE, m.chipMark,
                tx + chipTextW + cc * Theme.CONST_MARK_GAP, k.oy + cc * Theme.CONST_MARK_RISE,
                Align.left | Fonts.VCENTER, Theme.difficultyText(m.difficulty), ca);
        }
        k.popIf(px);
    }

    /**
     * A title longer than {@link #MARQUEE_CHARS} characters, at full size, scrolling inside the band with the song
     * card's timing ({@link Marquee}: rest 1 s, scroll to the end, rest 2 s, a 160 gap, the next copy, loop; Kelvin
     * 2026-10-19 07:01), its glyphs cut to the window and faded at its ends. Call after the text queue's flush (it
     * draws at once).
     */
    public void drawMarquee(ResultModel m, ResultLayout l, float t, float alpha) {
        float el = t - Choreo.TITLE_T;
        if (!marquee || el < 0f) return;
        float a = alpha * Anim.progress(t, Choreo.TITLE_T, Choreo.TITLE_D);
        if (a <= 0.002f) return;
        float x0 = l.titleX - l.titleMaxW * 0.5f, x1 = x0 + l.titleMaxW, span = titleW + Marquee.GAP * l.s;
        float d = Marquee.distance(el, titleW - l.titleMaxW, span, l.s);
        float fl = Marquee.fadeLeft(d, span, l.s), fr = Marquee.fadeRight(d, titleW - l.titleMaxW, span, l.s);
        float r = TextQueue.raster(Fonts.Role.UI_BOLD, titleCap);
        BitmapFont font = k.fonts.get(titleCjk ? Fonts.Role.CJK : Fonts.Role.UI_BOLD, r);
        BitmapFont.BitmapFontData fd = font.getData();
        float s0 = fd.scaleX;
        fd.setScale(s0 * titleCap / r);
        for (float x = x0 - d % span; x < x1; x += span) {
            k.quads.faded(k.batch, font, m.title, x, l.titleBase, Align.left, titleCap, Theme.INK1, a, x0, x1, fl, fr);
        }
        fd.setScale(s0);
    }
}
