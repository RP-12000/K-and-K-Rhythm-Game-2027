package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.NumberUtils;

/**
 * 音符故障效果的录制批次：先把一个音符画出的全部四边形录下来，再按这一拍的故障图样（横向错位的条带、红蓝色散、闪断）画进真正的批次。
 * 只是画面，不影响判定；稳定运行时不分配内存（缓冲只在第一次超出时增长）。
 *
 * <p>Recording {@link Batch} for a glitching note (CHART_FORMAT §6.9, Kelvin 2026-10-14). The
 * {@link NoteRenderer} points its batch here while it draws a note whose glitch segment is active,
 * then {@link #emit} replays the recorded quads into the real batch with the approved look:
 * <ul>
 *   <li><b>slice</b>: the note's height is cut into horizontal strips 2.. units tall; each strip
 *       moves sideways by ±3..(4 + 40·intensity·slice) units with chance
 *       {@code (0.25 + 0.6·intensity)·slice} (quads crossing a strip edge are clipped there);</li>
 *   <li><b>split</b>: a red copy {@code (2 + 10·intensity)·split} units to the left and a blue
 *       copy as far to the right, under the note (they follow the strips);</li>
 *   <li><b>flicker</b>: with chance {@code 0.3·intensity·flicker} the whole note dims to 20 %
 *       (no colour split then).</li>
 * </ul>
 * The pattern is a pure function of the seed (note, segment and pattern step), so it holds still
 * while paused and repeats exactly on a replay. A note's glows (drawn later, additive) replay
 * through {@link #emit(int, float, float, float, float, float, float)} with the note's own strips,
 * so its sync frame tears with it.
 * Recorded: every {@code draw} variant (rotated ones are turned into world quads as
 * {@code SpriteBatch} does); colour state is tracked; blend, shader and matrix calls go straight to
 * the real batch.
 */
final class GlitchCapture implements Batch {
    /** Most strips a note is cut into. */
    static final int MAX_STRIPS = 24;
    /** Chance factor of a flicker step: {@code 0.3·intensity·flicker}. */
    static final float FLICKER_CHANCE = 0.3f;
    /** Opacity of a flickering note. */
    static final float FLICKER_DIM = 0.2f;
    private static final int INITIAL_QUADS = 512;
    private static final float RED_R = 1f, RED_G = 0.18f, RED_B = 0.32f;
    private static final float BLUE_R = 0.25f, BLUE_G = 0.55f, BLUE_B = 1f;

    private Batch target;
    private Texture[] tex = new Texture[INITIAL_QUADS];
    private float[] verts = new float[INITIAL_QUADS * 20];
    private int quads;
    private final Color color = new Color(1f, 1f, 1f, 1f);
    private float packed = Color.WHITE_FLOAT_BITS;
    private final TextureRegion scratch = new TextureRegion();

    // two more for the still strips above and below a glow's note
    private final float[] stripLo = new float[MAX_STRIPS + 2];
    private final float[] stripHi = new float[MAX_STRIPS + 2];
    private final float[] stripDx = new float[MAX_STRIPS + 2];
    private int strips;
    private float boxLo, boxHi;
    private final float[] polyA = new float[8 * 5];
    private final float[] polyB = new float[8 * 5];
    private final float[] out = new float[20];
    private int rng;

    /** Starts recording for {@code real} (which must be drawing); the colour starts as the real batch's. */
    void begin(Batch real) {
        target = real;
        quads = 0;
        packed = real.getPackedColor();
        Color.abgr8888ToColor(color, packed);
    }

    /** @return quads recorded since {@link #begin(Batch)}. */
    int recorded() {
        return quads;
    }

    /** @return lowest y of the quads the last {@link #emit} replayed (where its strips start). */
    float boxLo() {
        return boxLo;
    }

    /** @return highest y of the quads the last {@link #emit} replayed (where its strips end). */
    float boxHi() {
        return boxHi;
    }

    /** @return a seed for note {@code note}, pattern step {@code step} and segment key {@code segment}. */
    static int seed(int note, long step, int segment) {
        int h = note * 0x9E3779B1 ^ (int) step * 0x85EBCA6B ^ (int) (step >>> 32) * 0xC2B2AE35 ^ segment * 0x27D4EB2F;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    /**
     * Draws the recorded quads into the real batch with the glitch look of {@code seed} and ends
     * the recording; the real batch's colour becomes the last colour set while recording.
     *
     * @param intensity 0..1 (after the segment's ramp)
     * @param split     colour split weight
     * @param slice     sideways slice weight
     * @param flicker   flicker weight (0 with "reduce flashing")
     * @return quads submitted
     */
    int emit(int seed, float intensity, float split, float slice, float flicker) {
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int q = 0, end = quads * 20; q < end; q += 5) {
            float y = verts[q + 1];
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        return replay(seed, intensity, split, slice, flicker, minY, maxY, false);
    }

    /**
     * {@link #emit(int, float, float, float, float)} for the glows of a note: the strips are those
     * of the note itself (its {@link #boxLo()}..{@link #boxHi()} and seed), and whatever lies above
     * or below them stays in place.
     */
    int emit(int seed, float intensity, float split, float slice, float flicker, float lo, float hi) {
        return replay(seed, intensity, split, slice, flicker, lo, hi, true);
    }

    private int replay(int seed, float intensity, float split, float slice, float flicker, float minY, float maxY,
                       boolean open) {
        Batch b = target;
        int n = 0;
        if (quads > 0) {
            rng = seed != 0 ? seed : 0x6D2B79F5;
            boxLo = minY;
            boxHi = maxY;
            float alpha = 1f;
            if (flicker > 0f && next() < FLICKER_CHANCE * intensity * flicker) alpha = FLICKER_DIM;
            strips(minY, maxY, intensity, slice, open);
            if (split > 0f && alpha == 1f) {
                float s = (2f + 10f * intensity) * split;
                float a = Math.min(1f, (0.35f + 0.45f * intensity) * Math.min(1f, split));
                n += pass(b, -s, true, RED_R, RED_G, RED_B, a);
                n += pass(b, s, true, BLUE_R, BLUE_G, BLUE_B, a);
            }
            n += pass(b, 0f, false, 0f, 0f, 0f, alpha);
        }
        b.setPackedColor(packed);
        target = null;
        quads = 0;
        return n;
    }

    // ------------------------------------------------------------------ pattern

    /** @return a pseudo-random float in [0, 1) (xorshift). */
    private float next() {
        int x = rng;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rng = x;
        return (x >>> 8) * (1f / (1 << 24));
    }

    /**
     * Cuts [minY, maxY] into strips with their sideways offsets (neighbouring still strips merged);
     * {@code open} adds still strips reaching from there to ±∞.
     */
    private void strips(float minY, float maxY, float intensity, float slice, boolean open) {
        strips = 0;
        if (open) addStrip(Float.NEGATIVE_INFINITY, minY, 0f);
        float h = maxY - minY;
        if (h > 0f && slice > 0f) {
            float maxBand = Math.max(3f, Math.min(h / 3f, Math.max(24f, h / 10f)));
            float chance = Math.min(1f, (0.25f + 0.6f * intensity) * slice);
            float reach = 1f + 40f * intensity * slice;
            float y = minY;
            while (y < maxY && strips < MAX_STRIPS - 1) {
                float band = 2f + next() * (maxBand - 2f);
                float dx = 0f;
                if (next() < chance) dx = (next() < 0.5f ? -1f : 1f) * (3f + next() * reach);
                addStrip(y, y + band, dx);
                y += band;
            }
            if (y < maxY) addStrip(y, maxY, 0f);
        } else {
            addStrip(minY, maxY, 0f);
        }
        if (open) {
            // the note's strips end at its top; what lies above stays in place
            stripHi[strips - 1] = Math.min(stripHi[strips - 1], maxY);
            addStrip(maxY, Float.POSITIVE_INFINITY, 0f);
            return;
        }
        // vertices exactly on the outer edges stay inside
        stripLo[0] = minY - 1f;
        stripHi[strips - 1] = Math.max(stripHi[strips - 1], maxY) + 1f;
    }

    private void addStrip(float lo, float hi, float dx) {
        if (strips > 0 && dx == 0f && stripDx[strips - 1] == 0f) {
            stripHi[strips - 1] = hi;
            return;
        }
        stripLo[strips] = lo;
        stripHi[strips] = hi;
        stripDx[strips] = dx;
        strips++;
    }

    /** Draws every recorded quad once, cut into the strips and moved by {@code shift}; returns quads submitted. */
    private int pass(Batch b, float shift, boolean tint, float tr, float tg, float tb, float alpha) {
        int n = 0;
        for (int q = 0; q < quads; q++) {
            int o = q * 20;
            float y0 = Math.min(Math.min(verts[o + 1], verts[o + 6]), Math.min(verts[o + 11], verts[o + 16]));
            float y1 = Math.max(Math.max(verts[o + 1], verts[o + 6]), Math.max(verts[o + 11], verts[o + 16]));
            for (int k = 0; k < strips; k++) {
                float lo = stripLo[k], hi = stripHi[k];
                if (hi <= y0 || lo >= y1) {
                    if (lo >= y1) break;
                    continue;
                }
                float dx = stripDx[k] + shift;
                if (y0 >= lo && y1 <= hi) {
                    System.arraycopy(verts, o, polyA, 0, 20);
                    n += polygon(b, tex[q], polyA, 4, dx, tint, tr, tg, tb, alpha);
                } else {
                    System.arraycopy(verts, o, polyA, 0, 20);
                    int m = clip(polyA, 4, polyB, lo, true);
                    m = clip(polyB, m, polyA, hi, false);
                    if (m >= 3) n += polygon(b, tex[q], polyA, m, dx, tint, tr, tg, tb, alpha);
                }
            }
        }
        return n;
    }

    /**
     * Sutherland–Hodgman against one horizontal line: keeps {@code y ≥ bound} ({@code below} =
     * false keeps {@code y ≤ bound}). Vertices are {@code x, y, colour, u, v}.
     *
     * @return vertices written to {@code dst}
     */
    private static int clip(float[] src, int n, float[] dst, float bound, boolean keepAbove) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int c = i * 5, p = ((i + n - 1) % n) * 5;
            float yc = src[c + 1], yp = src[p + 1];
            boolean inC = keepAbove ? yc >= bound : yc <= bound;
            boolean inP = keepAbove ? yp >= bound : yp <= bound;
            if (inC != inP) {
                float t = (bound - yp) / (yc - yp);
                int d = m * 5;
                dst[d] = src[p] + (src[c] - src[p]) * t;
                dst[d + 1] = bound;
                dst[d + 2] = lerpColor(src[p + 2], src[c + 2], t);
                dst[d + 3] = src[p + 3] + (src[c + 3] - src[p + 3]) * t;
                dst[d + 4] = src[p + 4] + (src[c + 4] - src[p + 4]) * t;
                m++;
            }
            if (inC) {
                System.arraycopy(src, c, dst, m * 5, 5);
                m++;
            }
        }
        return m;
    }

    private static float lerpColor(float a, float b, float t) {
        if (a == b) return a;
        int ca = NumberUtils.floatToIntColor(a), cb = NumberUtils.floatToIntColor(b);
        int r = lerp8(ca, cb, 0, t), g = lerp8(ca, cb, 8, t), bl = lerp8(ca, cb, 16, t), al = lerp8(ca, cb, 24, t);
        return Color.toFloatBits(r, g, bl, al);
    }

    private static int lerp8(int a, int b, int shift, float t) {
        int x = (a >>> shift) & 0xff, y = (b >>> shift) & 0xff;
        return MathUtils.clamp(Math.round(x + (y - x) * t), 0, 255);
    }

    /** Submits a convex polygon of m (3..6) vertices as a fan of quads, moved by dx and recoloured. */
    private int polygon(Batch b, Texture t, float[] p, int m, float dx, boolean tint, float tr, float tg, float tb,
                        float alpha) {
        int n = 0;
        for (int start = 1; start + 1 < m; start += 2) {
            int i1 = start, i2 = start + 1, i3 = Math.min(start + 2, m - 1);
            vertex(0, p, 0, dx, tint, tr, tg, tb, alpha);
            vertex(1, p, i1, dx, tint, tr, tg, tb, alpha);
            vertex(2, p, i2, dx, tint, tr, tg, tb, alpha);
            vertex(3, p, i3, dx, tint, tr, tg, tb, alpha);
            b.draw(t, out, 0, 20);
            n++;
        }
        return n;
    }

    private void vertex(int slot, float[] p, int i, float dx, boolean tint, float tr, float tg, float tb, float alpha) {
        int s = slot * 5, v = i * 5;
        out[s] = p[v] + dx;
        out[s + 1] = p[v + 1];
        float c = p[v + 2];
        if (tint || alpha != 1f) {
            int abgr = NumberUtils.floatToIntColor(c);
            float a = ((abgr >>> 24) & 0xff) / 255f * alpha;
            if (tint) {
                c = Color.toFloatBits(tr, tg, tb, a);
            } else {
                c = Color.toFloatBits((abgr & 0xff) / 255f, ((abgr >>> 8) & 0xff) / 255f,
                    ((abgr >>> 16) & 0xff) / 255f, a);
            }
        }
        out[s + 2] = c;
        out[s + 3] = p[v + 3];
        out[s + 4] = p[v + 4];
    }

    // ------------------------------------------------------------------ recording

    private int room() {
        if (quads == tex.length) {
            Texture[] t = new Texture[tex.length * 2];
            System.arraycopy(tex, 0, t, 0, quads);
            tex = t;
            float[] v = new float[verts.length * 2];
            System.arraycopy(verts, 0, v, 0, quads * 20);
            verts = v;
        }
        return quads * 20;
    }

    private void quad(Texture t, float x1, float y1, float u1, float v1, float x2, float y2, float u2, float v2,
                      float x3, float y3, float u3, float v3, float x4, float y4, float u4, float v4) {
        int o = room();
        float c = packed;
        float[] q = verts;
        q[o] = x1; q[o + 1] = y1; q[o + 2] = c; q[o + 3] = u1; q[o + 4] = v1;
        q[o + 5] = x2; q[o + 6] = y2; q[o + 7] = c; q[o + 8] = u2; q[o + 9] = v2;
        q[o + 10] = x3; q[o + 11] = y3; q[o + 12] = c; q[o + 13] = u3; q[o + 14] = v3;
        q[o + 15] = x4; q[o + 16] = y4; q[o + 17] = c; q[o + 18] = u4; q[o + 19] = v4;
        tex[quads++] = t;
    }

    /** An axis-aligned quad: u/v at (x, y), u2/v2 at the opposite corner, as {@code SpriteBatch}. */
    private void rect(Texture t, float x, float y, float w, float h, float u, float v, float u2, float v2) {
        float fx2 = x + w, fy2 = y + h;
        quad(t, x, y, u, v, x, fy2, u, v2, fx2, fy2, u2, v2, fx2, y, u2, v);
    }

    /** A scaled and rotated quad as {@code SpriteBatch}; corner k gets (uk, vk). */
    private void turned(Texture t, float x, float y, float originX, float originY, float w, float h, float scaleX,
                        float scaleY, float rotation, float u1, float v1, float u2, float v2, float u3, float v3,
                        float u4, float v4) {
        float wx = x + originX, wy = y + originY;
        float fx = -originX * scaleX, fy = -originY * scaleY;
        float fx2 = (w - originX) * scaleX, fy2 = (h - originY) * scaleY;
        float x1, y1, x2, y2, x3, y3, x4, y4;
        if (rotation != 0f) {
            float cos = MathUtils.cosDeg(rotation), sin = MathUtils.sinDeg(rotation);
            x1 = cos * fx - sin * fy;
            y1 = sin * fx + cos * fy;
            x2 = cos * fx - sin * fy2;
            y2 = sin * fx + cos * fy2;
            x3 = cos * fx2 - sin * fy2;
            y3 = sin * fx2 + cos * fy2;
            x4 = x1 + (x3 - x2);
            y4 = y3 - (y2 - y1);
        } else {
            x1 = fx;
            y1 = fy;
            x2 = fx;
            y2 = fy2;
            x3 = fx2;
            y3 = fy2;
            x4 = fx2;
            y4 = fy;
        }
        quad(t, x1 + wx, y1 + wy, u1, v1, x2 + wx, y2 + wy, u2, v2, x3 + wx, y3 + wy, u3, v3, x4 + wx, y4 + wy, u4, v4);
    }

    private TextureRegion region(Texture t, int srcX, int srcY, int srcW, int srcH, boolean flipX, boolean flipY) {
        scratch.setTexture(t);
        scratch.setRegion(srcX, srcY, srcW, srcH);
        scratch.flip(flipX, !flipY); // SpriteBatch's texture variants put v at the bottom of the source rect
        return scratch;
    }

    @Override
    public void draw(Texture texture, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation, int srcX, int srcY, int srcWidth, int srcHeight,
                     boolean flipX, boolean flipY) {
        TextureRegion r = region(texture, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
        turned(texture, x, y, originX, originY, width, height, scaleX, scaleY, rotation,
            r.getU(), r.getV(), r.getU(), r.getV2(), r.getU2(), r.getV2(), r.getU2(), r.getV());
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, int srcX, int srcY, int srcWidth,
                     int srcHeight, boolean flipX, boolean flipY) {
        TextureRegion r = region(texture, srcX, srcY, srcWidth, srcHeight, flipX, flipY);
        rect(texture, x, y, width, height, r.getU(), r.getV(), r.getU2(), r.getV2());
    }

    @Override
    public void draw(Texture texture, float x, float y, int srcX, int srcY, int srcWidth, int srcHeight) {
        draw(texture, x, y, srcWidth, srcHeight, srcX, srcY, srcWidth, srcHeight, false, false);
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height, float u, float v,
                     float u2, float v2) {
        rect(texture, x, y, width, height, u, v, u2, v2);
    }

    @Override
    public void draw(Texture texture, float x, float y) {
        draw(texture, x, y, texture.getWidth(), texture.getHeight());
    }

    @Override
    public void draw(Texture texture, float x, float y, float width, float height) {
        rect(texture, x, y, width, height, 0f, 1f, 1f, 0f);
    }

    @Override
    public void draw(Texture texture, float[] spriteVertices, int offset, int count) {
        for (int i = offset, end = offset + count - 19; i < end; i += 20) {
            int o = room();
            System.arraycopy(spriteVertices, i, verts, o, 20);
            tex[quads++] = texture;
        }
    }

    @Override
    public void draw(TextureRegion region, float x, float y) {
        draw(region, x, y, region.getRegionWidth(), region.getRegionHeight());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float width, float height) {
        rect(region.getTexture(), x, y, width, height, region.getU(), region.getV2(), region.getU2(), region.getV());
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation) {
        float u = region.getU(), v = region.getV2(), u2 = region.getU2(), v2 = region.getV();
        turned(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
            u, v, u, v2, u2, v2, u2, v);
    }

    @Override
    public void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height,
                     float scaleX, float scaleY, float rotation, boolean clockwise) {
        float u = region.getU(), v = region.getV(), u2 = region.getU2(), v2 = region.getV2();
        if (clockwise) {
            turned(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
                u2, v2, u, v2, u, v, u2, v);
        } else {
            turned(region.getTexture(), x, y, originX, originY, width, height, scaleX, scaleY, rotation,
                u, v, u2, v, u2, v2, u, v2);
        }
    }

    @Override
    public void draw(TextureRegion region, float width, float height, Affine2 t) {
        float x1 = t.m02, y1 = t.m12;
        float x2 = t.m01 * height + t.m02, y2 = t.m11 * height + t.m12;
        float x3 = t.m00 * width + t.m01 * height + t.m02, y3 = t.m10 * width + t.m11 * height + t.m12;
        float x4 = t.m00 * width + t.m02, y4 = t.m10 * width + t.m12;
        float u = region.getU(), v = region.getV2(), u2 = region.getU2(), v2 = region.getV();
        quad(region.getTexture(), x1, y1, u, v, x2, y2, u, v2, x3, y3, u2, v2, x4, y4, u2, v);
    }

    // ------------------------------------------------------------------ state

    @Override
    public void setColor(Color tint) {
        color.set(tint);
        packed = tint.toFloatBits();
    }

    @Override
    public void setColor(float r, float g, float b, float a) {
        color.set(r, g, b, a);
        packed = color.toFloatBits();
    }

    @Override
    public Color getColor() {
        return color;
    }

    @Override
    public void setPackedColor(float packedColor) {
        Color.abgr8888ToColor(color, packedColor);
        packed = packedColor;
    }

    @Override
    public float getPackedColor() {
        return packed;
    }

    @Override
    public void begin() {
    }

    @Override
    public void end() {
    }

    @Override
    public void flush() {
    }

    @Override
    public void disableBlending() {
        target.disableBlending();
    }

    @Override
    public void enableBlending() {
        target.enableBlending();
    }

    @Override
    public void setBlendFunction(int srcFunc, int dstFunc) {
        target.setBlendFunction(srcFunc, dstFunc);
    }

    @Override
    public void setBlendFunctionSeparate(int srcFuncColor, int dstFuncColor, int srcFuncAlpha, int dstFuncAlpha) {
        target.setBlendFunctionSeparate(srcFuncColor, dstFuncColor, srcFuncAlpha, dstFuncAlpha);
    }

    @Override
    public int getBlendSrcFunc() {
        return target.getBlendSrcFunc();
    }

    @Override
    public int getBlendDstFunc() {
        return target.getBlendDstFunc();
    }

    @Override
    public int getBlendSrcFuncAlpha() {
        return target.getBlendSrcFuncAlpha();
    }

    @Override
    public int getBlendDstFuncAlpha() {
        return target.getBlendDstFuncAlpha();
    }

    @Override
    public Matrix4 getProjectionMatrix() {
        return target.getProjectionMatrix();
    }

    @Override
    public Matrix4 getTransformMatrix() {
        return target.getTransformMatrix();
    }

    @Override
    public void setProjectionMatrix(Matrix4 projection) {
        target.setProjectionMatrix(projection);
    }

    @Override
    public void setTransformMatrix(Matrix4 transform) {
        target.setTransformMatrix(transform);
    }

    @Override
    public void setShader(ShaderProgram shader) {
        target.setShader(shader);
    }

    @Override
    public ShaderProgram getShader() {
        return target.getShader();
    }

    @Override
    public boolean isBlendingEnabled() {
        return target.isBlendingEnabled();
    }

    @Override
    public boolean isDrawing() {
        return target != null && target.isDrawing();
    }

    @Override
    public void dispose() {
    }
}
