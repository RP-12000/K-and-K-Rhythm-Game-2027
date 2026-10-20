package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import org.kelvinizer.ui.Fonts;

/**
 * 结算画面的逐字绘制，做 {@link Fonts} 和 {@code GlitchText} 不包办的效果：
 * 分数格子 G2 故障时的横向错位切片，以及评级印章上斜扫过字形的光带。
 * 也能不借助批处理变换直接画出旋转、缩放后的字形，让静止的印章字母和同一字体页的文字一起绘制；
 * 还能把文字只画在一个窗口里、两端渐隐（滚动的歌名）。
 *
 * <p>Glyph-level drawing for the effects {@link Fonts} and {@code GlitchText} do not cover: fixed
 * cells cut into horizontally shifted sub-UV slices (the score's G2 burst on fixed cells) and a
 * diagonal shimmer band painted through the glyphs with per-vertex colours (the grade stamp),
 * plain glyph quads under an optional rotation / scale (the stamp letter at rest, drawn
 * without a batch transform so it batches with the rest of its page), and text cut to a window
 * with faded ends (scrolling titles, {@link #faded}).
 *
 * <p>Glyph placement matches {@link Fonts}: a font returned by {@code fonts.get(role, cap, set)}
 * is already scaled to world units, and a glyph's quad bottom sits at
 * {@code baseline + (g.yoffset − refY) · scale}, where refY is the y-offset of the font's
 * reference capital ({@code H0KPMEBN1}, first present). No allocation after construction;
 * the caller owns blending and the batch must be drawing.
 */
public final class GlyphQuads {
    private static final String REF_CHARS = "H0KPMEBN1";
    private static final int GRID = 6;

    private final float[] q = new float[20];
    private final GlyphLayout layout = new GlyphLayout();
    /** Optional affine (world = origin + R·scale·local) applied by {@link #text} and {@link #shimmer}. */
    private boolean affine;
    private float ox, oy, m00, m01, m10, m11;

    /**
     * Makes {@link #text} and {@link #shimmer} place their quads at
     * {@code (cx, cy) + rotate(deg) · scale · local}, so rotated or scaled glyphs can be drawn
     * without a batch transform (and the batch flush it costs). Clear with {@link #clearAffine()}.
     */
    public void setAffine(float cx, float cy, float deg, float scale) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r) * scale, s = (float) Math.sin(r) * scale;
        ox = cx;
        oy = cy;
        m00 = c;
        m01 = -s;
        m10 = s;
        m11 = c;
        affine = true;
    }

    /** Back to plain (batch-transform) coordinates. */
    public void clearAffine() {
        affine = false;
    }

    /**
     * Draws {@code text} laid out like {@code fonts.draw(..., x, y, align, ...)} (pass the font
     * from {@code fonts.get(role, cap, set)}, scaled for {@code cap}) as individual quads in the
     * packed colour, honouring the affine set by {@link #setAffine}. Returns the width.
     */
    public float text(Batch b, BitmapFont font, CharSequence text, float x, float y, int align, float cap,
                      float packedColor) {
        if (text == null || text.length() == 0) return 0f;
        BitmapFont.BitmapFontData d = font.getData();
        layout.setText(font, text);
        float w = layout.width;
        float left = (align & Align.left) != 0 ? x : ((align & Align.right) != 0 ? x - w : x - w * 0.5f);
        float base = (align & Align.top) != 0 ? y - cap : ((align & Fonts.VCENTER) != 0 ? y - cap * 0.5f : y);
        float sx = d.scaleX, sy = d.scaleY;
        float yOrigin = base - refYOffset(d) * sy;
        Array<GlyphLayout.GlyphRun> runs = layout.runs;
        for (int r = 0; r < runs.size; r++) {
            GlyphLayout.GlyphRun run = runs.get(r);
            Array<BitmapFont.Glyph> glyphs = run.glyphs;
            FloatArray adv = run.xAdvances;
            float gx = left + run.x;
            float gy = yOrigin + run.y;
            for (int i = 0; i < glyphs.size; i++) {
                gx += adv.get(i);
                BitmapFont.Glyph g = glyphs.get(i);
                if (g.width == 0 || g.height == 0) continue;
                float qx = gx + g.xoffset * sx, qy = gy + g.yoffset * sy;
                float qw = g.width * sx, qh = g.height * sy;
                Texture tex = font.getRegion(g.page).getTexture();
                put(0, qx, qy, packedColor, g.u, g.v);
                put(5, qx, qy + qh, packedColor, g.u, g.v2);
                put(10, qx + qw, qy + qh, packedColor, g.u2, g.v2);
                put(15, qx + qw, qy, packedColor, g.u2, g.v);
                b.draw(tex, q, 0, 20);
            }
        }
        return w;
    }

    /**
     * Draws {@code text} like {@link #text} but only inside {@code [clipX0, clipX1]}, its alpha rising over
     * {@code fadeL} from clipX0 and falling over {@code fadeR} to clipX1 (a scrolling title, {@code Marquee}). Each
     * glyph quad is cut at those four lines, so the ramps are exact and nothing outside is drawn (no scissor, no
     * flush). Ignores the affine.
     */
    public void faded(Batch b, BitmapFont font, CharSequence text, float x, float y, int align, float cap, Color c,
                      float alpha, float clipX0, float clipX1, float fadeL, float fadeR) {
        if (alpha <= 0.002f || text == null || text.length() == 0 || clipX1 <= clipX0) return;
        boolean was = affine;
        affine = false;
        BitmapFont.BitmapFontData d = font.getData();
        layout.setText(font, text);
        float w = layout.width;
        float left = (align & Align.left) != 0 ? x : ((align & Align.right) != 0 ? x - w : x - w * 0.5f);
        float base = (align & Align.top) != 0 ? y - cap : ((align & Fonts.VCENTER) != 0 ? y - cap * 0.5f : y);
        float sx = d.scaleX, sy = d.scaleY;
        float yOrigin = base - refYOffset(d) * sy;
        float in0 = Math.min(clipX0 + fadeL, clipX1 - fadeR), in1 = Math.max(clipX0 + fadeL, clipX1 - fadeR);
        Array<GlyphLayout.GlyphRun> runs = layout.runs;
        for (int r = 0; r < runs.size; r++) {
            GlyphLayout.GlyphRun run = runs.get(r);
            Array<BitmapFont.Glyph> glyphs = run.glyphs;
            FloatArray adv = run.xAdvances;
            float gx = left + run.x;
            float gy = yOrigin + run.y;
            for (int i = 0; i < glyphs.size; i++) {
                gx += adv.get(i);
                BitmapFont.Glyph g = glyphs.get(i);
                if (g.width == 0 || g.height == 0) continue;
                float qx = gx + g.xoffset * sx, qy = gy + g.yoffset * sy;
                float qw = g.width * sx, qh = g.height * sy;
                float xa = Math.max(qx, clipX0), x1 = Math.min(qx + qw, clipX1);
                if (x1 <= xa) continue;
                Texture tex = font.getRegion(g.page).getTexture();
                // pieces between the clip edges and the two ramp ends, each linear in x
                if (in0 > xa && in0 < x1) {
                    fadedPiece(b, tex, g, qx, qw, qy, qh, xa, in0, c, alpha, clipX0, clipX1, fadeL, fadeR);
                    xa = in0;
                }
                if (in1 > xa && in1 < x1) {
                    fadedPiece(b, tex, g, qx, qw, qy, qh, xa, in1, c, alpha, clipX0, clipX1, fadeL, fadeR);
                    xa = in1;
                }
                fadedPiece(b, tex, g, qx, qw, qy, qh, xa, x1, c, alpha, clipX0, clipX1, fadeL, fadeR);
            }
        }
        affine = was;
    }

    private void fadedPiece(Batch b, Texture tex, BitmapFont.Glyph g, float qx, float qw, float qy, float qh, float xa,
                            float xb, Color c, float alpha, float clipX0, float clipX1, float fadeL, float fadeR) {
        float fa = edgeRamp(xa, clipX0, clipX1, fadeL, fadeR), fb = edgeRamp(xb, clipX0, clipX1, fadeL, fadeR);
        if (fa <= 0f && fb <= 0f) return;
        float ua = g.u + (xa - qx) / qw * (g.u2 - g.u), ub = g.u + (xb - qx) / qw * (g.u2 - g.u);
        float ca = pack(c, alpha * fa), cb = pack(c, alpha * fb);
        put(0, xa, qy, ca, ua, g.v);
        put(5, xa, qy + qh, ca, ua, g.v2);
        put(10, xb, qy + qh, cb, ub, g.v2);
        put(15, xb, qy, cb, ub, g.v);
        b.draw(tex, q, 0, 20);
    }

    private static float edgeRamp(float x, float x0, float x1, float fadeL, float fadeR) {
        float l = fadeL > 0f ? Math.max(0f, Math.min(1f, (x - x0) / fadeL)) : 1f;
        float r = fadeR > 0f ? Math.max(0f, Math.min(1f, (x1 - x) / fadeR)) : 1f;
        return l * r;
    }

    /** Reference y-offset (unscaled) of a font, as Fonts uses for its baseline. */
    public static float refYOffset(BitmapFont.BitmapFontData d) {
        for (int i = 0; i < REF_CHARS.length(); i++) {
            BitmapFont.Glyph g = d.getGlyph(REF_CHARS.charAt(i));
            if (g != null && g.height > 0) return g.yoffset;
        }
        return 0f;
    }

    /**
     * Draws {@code c} centred by its ink in the cell {@code [cellX, cellX + cellW]} like
     * {@link Fonts#drawCell}, scaled by {@code k} about the cell centre, with the batch colour.
     * Each of the {@code bands} slices {@code [lo, hi]} (world y) is shifted by {@code dx}.
     */
    public void cell(Batch b, BitmapFont font, char c, float cellX, float cellW, float baseline, float cap,
                     float k, float[] lo, float[] hi, float[] dx, int bands) {
        if (c == ' ') return;
        BitmapFont.BitmapFontData d = font.getData();
        BitmapFont.Glyph g = d.getGlyph(c);
        if (g == null || g.width == 0 || g.height == 0) return;
        float s = d.scaleX;
        float gw = g.width * s, gh = g.height * s;
        float gx = cellX + (cellW - gw) * 0.5f;
        float gy = baseline + (g.yoffset - refYOffset(d)) * s;
        if (k != 1f) {
            float cxm = gx + gw * 0.5f, cym = baseline + cap * 0.5f;
            gx = cxm + (gx - cxm) * k;
            gy = cym + (gy - cym) * k;
            gw *= k;
            gh *= k;
        }
        Texture tex = font.getRegion(g.page).getTexture();
        if (bands <= 0) {
            b.draw(tex, gx, gy, gw, gh, g.u, g.v, g.u2, g.v2);
            return;
        }
        float cursor = gy, end = gy + gh;
        for (int i = 0; i < bands; i++) {
            float a = Math.max(lo[i], gy), z = Math.min(hi[i], end);
            if (z <= a || a < cursor) continue;
            piece(b, tex, g, gx, gy, gw, gh, cursor, a, 0f);
            piece(b, tex, g, gx, gy, gw, gh, a, z, dx[i]);
            cursor = z;
        }
        piece(b, tex, g, gx, gy, gw, gh, cursor, end, 0f);
    }

    /**
     * {@link #cell} at scale 1 without bands, drawing only the part of the glyph between {@code y0} and {@code y1}
     * (a slot-roll window: no scissor, no flush). Uses the batch colour.
     */
    public void clipped(Batch b, BitmapFont font, char c, float cellX, float cellW, float baseline, float y0,
                        float y1) {
        if (c == ' ') return;
        BitmapFont.BitmapFontData d = font.getData();
        BitmapFont.Glyph g = d.getGlyph(c);
        if (g == null || g.width == 0 || g.height == 0) return;
        float s = d.scaleX, gw = g.width * s, gh = g.height * s;
        float gx = cellX + (cellW - gw) * 0.5f, gy = baseline + (g.yoffset - refYOffset(d)) * s;
        piece(b, font.getRegion(g.page).getTexture(), g, gx, gy, gw, gh, Math.max(y0, gy), Math.min(y1, gy + gh), 0f);
    }

    private static void piece(Batch b, Texture tex, BitmapFont.Glyph g, float qx, float qy, float qw, float qh,
                              float y0, float y1, float dx) {
        if (y1 - y0 <= 0.01f) return;
        float t0 = (y0 - qy) / qh, t1 = (y1 - qy) / qh;
        float v0 = g.v + (g.v2 - g.v) * t0;
        float v1 = g.v + (g.v2 - g.v) * t1;
        b.draw(tex, qx + dx, y0, qw, y1 - y0, g.u, v0, g.u2, v1);
    }

    /**
     * Paints a diagonal light band through {@code text} laid out exactly like
     * {@code fonts.draw(b, role, cap, set, text, x, y, align, ...)} (pass the font from
     * {@code fonts.get(role, cap, set)}). The band is centred where
     * {@code x' − slope · y' = bandPos} (local coordinates relative to the text's left/baseline),
     * {@code halfWidth} wide, and fades linearly; draw with additive blending.
     */
    public void shimmer(Batch b, BitmapFont font, CharSequence text, float x, float y, int align, float cap,
                        float bandPos, float halfWidth, float slope, Color color, float alpha) {
        if (alpha <= 0.002f || text == null || text.length() == 0) return;
        BitmapFont.BitmapFontData d = font.getData();
        layout.setText(font, text);
        float w = layout.width;
        float left = (align & Align.left) != 0 ? x : ((align & Align.right) != 0 ? x - w : x - w * 0.5f);
        float base = (align & Align.top) != 0 ? y - cap : ((align & Fonts.VCENTER) != 0 ? y - cap * 0.5f : y);
        float sx = d.scaleX, sy = d.scaleY;
        float yOrigin = base - refYOffset(d) * sy;
        Array<GlyphLayout.GlyphRun> runs = layout.runs;
        for (int r = 0; r < runs.size; r++) {
            GlyphLayout.GlyphRun run = runs.get(r);
            Array<BitmapFont.Glyph> glyphs = run.glyphs;
            FloatArray adv = run.xAdvances;
            float gx = left + run.x;
            float gy = yOrigin + run.y;
            for (int i = 0; i < glyphs.size; i++) {
                gx += adv.get(i);
                BitmapFont.Glyph g = glyphs.get(i);
                if (g.width == 0 || g.height == 0) continue;
                float qx = gx + g.xoffset * sx, qy = gy + g.yoffset * sy;
                float qw = g.width * sx, qh = g.height * sy;
                Texture tex = font.getRegion(g.page).getTexture();
                grid(b, tex, g, qx, qy, qw, qh, left, base, bandPos, halfWidth, slope, color, alpha);
            }
        }
    }

    private void grid(Batch b, Texture tex, BitmapFont.Glyph g, float qx, float qy, float qw, float qh,
                      float ox, float oy, float bandPos, float halfWidth, float slope, Color c, float alpha) {
        float du = (g.u2 - g.u) / GRID, dv = (g.v2 - g.v) / GRID;
        float dw = qw / GRID, dh = qh / GRID;
        for (int j = 0; j < GRID; j++) {
            float y0 = qy + dh * j, y1 = y0 + dh;
            float v0 = g.v + dv * j, v1 = v0 + dv;
            for (int i = 0; i < GRID; i++) {
                float x0 = qx + dw * i, x1 = x0 + dw;
                float a00 = band(x0 - ox, y0 - oy, bandPos, halfWidth, slope);
                float a01 = band(x0 - ox, y1 - oy, bandPos, halfWidth, slope);
                float a11 = band(x1 - ox, y1 - oy, bandPos, halfWidth, slope);
                float a10 = band(x1 - ox, y0 - oy, bandPos, halfWidth, slope);
                if (a00 + a01 + a11 + a10 <= 0.001f) continue;
                float u0 = g.u + du * i, u1 = u0 + du;
                put(0, x0, y0, pack(c, alpha * a00), u0, v0);
                put(5, x0, y1, pack(c, alpha * a01), u0, v1);
                put(10, x1, y1, pack(c, alpha * a11), u1, v1);
                put(15, x1, y0, pack(c, alpha * a10), u1, v0);
                b.draw(tex, q, 0, 20);
            }
        }
    }

    private static float band(float lx, float ly, float pos, float half, float slope) {
        float dist = Math.abs(lx - slope * ly - pos);
        float f = 1f - dist / half;
        return f <= 0f ? 0f : f * f * (3f - 2f * f);
    }

    private static float pack(Color c, float a) {
        return Color.toFloatBits(c.r, c.g, c.b, Math.max(0f, Math.min(1f, c.a * a)));
    }

    private void put(int i, float x, float y, float col, float u, float v) {
        if (affine) {
            float wx = ox + m00 * x + m01 * y;
            y = oy + m10 * x + m11 * y;
            x = wx;
        }
        q[i] = x;
        q[i + 1] = y;
        q[i + 2] = col;
        q[i + 3] = u;
        q[i + 4] = v;
    }
}
