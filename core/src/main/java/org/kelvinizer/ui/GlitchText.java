package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pool;
import org.kelvinizer.ui.Fonts.Role;

/**
 * 故障风文字，游戏里所有故障效果都经过这里：
 * G0 干净、G1 静止微闪、G2 突发（换字形、错位、切片、撕裂条）、G2 红和 G3 全屏撕裂。
 * 强度和「减少闪烁」是全局设置；强度为 0 时不做动画，突发只是透明度轻微下降。
 *
 * <p>Glitch treatment (DESIGN §1.4, Appendix A.3). All glitch goes through this class:
 *
 * <ul>
 *   <li><b>G0</b> clean.</li>
 *   <li><b>G1</b> shimmer: cyan / magenta copies at ±2.5·I (α .35), jittering ±1 px for two
 *       frames every 2–4 s.</li>
 *   <li><b>G2</b> burst (60–140 ms): face swap NUMBER → DISPLAY → DISPLAY_SOFT → rest (only for
 *       the Mokoto roles), split ±6·I, 1–3 sub-UV slices shifted ±(4..24)·I, 1–3 tear bars.</li>
 *   <li><b>G2-red</b>: as G2 but the split is {@code #FF5C7A} only (±4·I) plus two frames of
 *       ±4 px jitter, no face swap.</li>
 *   <li><b>G3</b>: screen-level tear ({@link #drawScreenTears}); for text it behaves like G2.</li>
 * </ul>
 *
 * <p>Jitter is seeded with {@code hash(floor(time × 24))} and the element seed. The global
 * intensity {@code I} and "reduce flashing" are static settings ({@link #setIntensity},
 * {@link #setReduceFlashing}) pushed by whoever owns Settings. With I = 0 nothing animates:
 * bursts become a 1 → 0.6 → 1 alpha dip and G3 is skipped. Reduce flashing disables tear
 * bars and flicker.
 *
 * <p>Copies are drawn with normal blending underneath the clean glyphs (on the dark palette
 * this matches additive closely and costs no batch flush). Sub-UV slices are drawn per glyph
 * quad by narrowing the texture v-range — no scissor, no FBO, works on dynamic text.
 */
public final class GlitchText {

    /** Glitch levels. */
    public enum Level { G0, G1, G2, G2_RED, G3 }

    private static float intensity = 0.6f;
    private static boolean reduceFlashing;

    /** Sets the global glitch intensity I (0..1, default 0.6). */
    public static void setIntensity(float value) {
        intensity = MathUtils.clamp(value, 0f, 1f);
    }

    /** Global glitch intensity I. */
    public static float intensity() {
        return intensity;
    }

    /** Sets "reduce flashing" (caps flashes at α .10, disables tear bars and flicker). */
    public static void setReduceFlashing(boolean value) {
        reduceFlashing = value;
    }

    /** True when "reduce flashing" is on. */
    public static boolean reduceFlashing() {
        return reduceFlashing;
    }

    /** Clamps a full-screen flash alpha per the photosensitivity rules (≤ .15, ≤ .10 reduced). */
    public static float flashAlpha(float alpha) {
        return Math.min(alpha, reduceFlashing ? 0.10f : 0.15f);
    }

    private static final float FRAME = 1f / 60f;
    private static final Role[] SWAP = {Role.NUMBER, Role.DISPLAY, Role.DISPLAY_SOFT};

    private final Fonts fonts;
    private final Draw draw;
    private final GlyphLayout layout = new GlyphLayout();
    private final Pool<GlitchState> pool = new Pool<GlitchState>(8, 64) {
        @Override
        protected GlitchState newObject() {
            return new GlitchState();
        }
    };
    private final float[] bandLo = new float[3];
    private final float[] bandHi = new float[3];
    private final float[] bandDx = new float[3];
    private int bands;
    private final Color tmp = new Color();
    /** Rotation of the draw in progress (degrees, CCW) and its pivot; 0 outside a rotated draw. */
    private float rotation, rotCos = 1f, rotSin, pivotX, pivotY;
    /** One rotated glyph quad (SpriteBatch layout: x, y, colour, u, v × 4). */
    private final float[] verts = new float[20];

    /** Creates the glitch renderer over the shared fonts and draw services. */
    public GlitchText(Fonts fonts, Draw draw) {
        this.fonts = fonts;
        this.draw = draw;
    }

    /** Obtains a pooled state (resting G0). */
    public GlitchState obtainState() {
        return pool.obtain();
    }

    /** Returns a state to the pool. */
    public void freeState(GlitchState state) {
        if (state != null) pool.free(state);
    }

    /**
     * Draws {@code text} with the glitch level in effect for {@code state} at {@code time}.
     * Anchoring and units follow {@link Fonts#draw}. A null state draws clean text.
     *
     * @return the text width in world units
     */
    public float draw(Batch b, Role restRole, float cap, CharSequence text, float x, float y, int align,
                      Color color, float alpha, GlitchState state, float time) {
        return draw(b, restRole, cap, Fonts.GlyphSet.FULL, text, x, y, align, color, alpha, state, time);
    }

    /** As {@link #draw(Batch, Role, float, CharSequence, float, float, int, Color, float, GlitchState, float)} with a glyph set. */
    public float draw(Batch b, Role restRole, float cap, Fonts.GlyphSet set, CharSequence text, float x, float y,
                      int align, Color color, float alpha, GlitchState state, float time) {
        return draw(b, restRole, cap, set, text, x, y, align, color, alpha, state, time, 1f, 0f);
    }

    /**
     * As {@link #draw(Batch, Role, float, Fonts.GlyphSet, CharSequence, float, float, int, Color, float,
     * GlitchState, float)}, transformed without a batch flush:
     * <ul>
     *   <li>{@code scale}: the text is rasterised for cap {@code cap} (the font bucket stays fixed)
     *       and drawn at {@code cap × scale}, scaled about the anchor ({@code x}, {@code y}, per
     *       {@code align}); split, slices and tear bars scale with it. For pops and punches.</li>
     *   <li>{@code degrees}: every glyph quad is rotated on the CPU (counter-clockwise) about the
     *       centre of the text box; tear bars stay level. For leans of a few degrees.</li>
     * </ul>
     *
     * @return the drawn text width in world units (before rotation)
     */
    public float draw(Batch b, Role restRole, float cap, Fonts.GlyphSet set, CharSequence text, float x, float y,
                      int align, Color color, float alpha, GlitchState state, float time, float scale,
                      float degrees) {
        if (text == null || text.length() == 0 || cap <= 0f || scale <= 0f) return 0f;
        boolean transformed = scale != 1f || degrees != 0f;
        if (!transformed && state == null) return fonts.draw(b, restRole, cap, set, text, x, y, align, color, alpha);
        Level level = Level.G0;
        float bt = -1f;
        if (state != null) {
            scheduleIdle(state, time);
            level = state.levelAt(time);
            bt = state.burstProgress(time);
        }
        float drawCap = cap * scale;
        float I = intensity;
        rotation = degrees;
        rotCos = MathUtils.cosDeg(degrees);
        rotSin = MathUtils.sinDeg(degrees);
        try {
            if (I <= 0.001f || level == Level.G0) {
                float a = alpha;
                if (bt >= 0f && level != Level.G0 && level != Level.G1) {
                    a *= 1f - 0.4f * MathUtils.sin(bt * MathUtils.PI);
                }
                if (!transformed) return fonts.draw(b, restRole, cap, set, text, x, y, align, color, a);
                return drawClean(b, restRole, cap, drawCap, set, text, x, y, align, color, a);
            }
            if (level == Level.G1) {
                return drawShimmer(b, restRole, cap, drawCap, set, text, x, y, align, color, alpha, state, time, I);
            }
            return drawBurst(b, restRole, cap, drawCap, set, text, x, y, align, color, alpha, state, time, I,
                level == Level.G2_RED, bt);
        } finally {
            rotation = 0f;
        }
    }

    // ------------------------------------------------------------------ G1 resting shimmer
    /**
     * Split of the G1 resting shimmer for text of cap {@code cap}: the cyan copy sits at
     * {@code −offset + jitterX}, the magenta one at {@code +offset − jitterX}. 0 when
     * {@code state} is not resting in G1 at {@code time} or the global intensity is 0. For code
     * that draws a resting glyph itself (e.g. hand-rotated quads) and wants GlitchText's shimmer.
     */
    public static float restingOffset(GlitchState state, float cap, float time) {
        if (state == null || intensity <= 0.001f || state.levelAt(time) != Level.G1) return 0f;
        return 2.5f * intensity * Math.max(1f, cap / 60f);
    }

    /**
     * Horizontal jitter of the G1 copies: ±1 world unit for two frames once per shimmer period
     * (2–4 s, per state), else 0; always 0 with reduce flashing.
     */
    public static float restingJitterX(GlitchState state, float time) {
        return restingJitter(state, time, 0);
    }

    /** Vertical jitter of the G1 copies (cyan at +jy, magenta at −jy); see {@link #restingJitterX}. */
    public static float restingJitterY(GlitchState state, float time) {
        return restingJitter(state, time, 31);
    }

    private static float restingJitter(GlitchState st, float time, int salt) {
        if (st == null || reduceFlashing) return 0f;
        float period = 2f + 2f * Anim.hash01(st.seed);
        float phase = (time + Anim.hash01(st.seed, 7) * period) % period;
        if (phase >= 2f * FRAME) return 0f;
        int tick = (int) (time / period);
        return Anim.hash01(st.seed, tick + salt) < 0.5f ? -1f : 1f;
    }

    /** Fires the ambient hero burst when its idle interval elapses. */
    private static void scheduleIdle(GlitchState st, float time) {
        if (st.idleInterval <= 0f) return;
        if (st.nextIdle < 0f) {
            st.nextIdle = time + st.idleInterval * (0.8f + 0.4f * Anim.hash01(st.seed, 3));
        } else if (time >= st.nextIdle) {
            st.trigger(Level.G2, time);
            st.nextIdle = time + st.idleInterval * (0.8f + 0.4f * Anim.hash01(st.seed, (int) (time * 10f)));
        }
    }

    // ------------------------------------------------------------------ G0 (transformed)
    private float drawClean(Batch b, Role role, float rasterCap, float cap, Fonts.GlyphSet set, CharSequence text,
                            float x, float y, int align, Color color, float alpha) {
        BitmapFont font = fonts.prepare(role, rasterCap, cap, set, text);
        float top = fonts.lastTop();
        layout.setText(font, text);
        float w = layout.width;
        float left = Fonts.alignX(x, w, align);
        float base = Fonts.alignBaseline(y, cap, align);
        pivot(left, w, base, cap);
        float old = b.getPackedColor();
        b.setColor(color.r, color.g, color.b, color.a * alpha);
        walk(b, font, top, left, base, 0);
        b.setPackedColor(old);
        return w;
    }

    // ------------------------------------------------------------------ G1
    private float drawShimmer(Batch b, Role role, float rasterCap, float cap, Fonts.GlyphSet set, CharSequence text,
                              float x, float y, int align, Color color, float alpha, GlitchState st, float time,
                              float I) {
        BitmapFont font = fonts.prepare(role, rasterCap, cap, set, text);
        float top = fonts.lastTop();
        layout.setText(font, text);
        float w = layout.width;
        float left = Fonts.alignX(x, w, align);
        float base = Fonts.alignBaseline(y, cap, align);
        pivot(left, w, base, cap);
        float a = restingOffset(st, cap, time);
        float jx = restingJitterX(st, time), jy = restingJitterY(st, time);
        float old = b.getPackedColor();
        float ca = 0.35f * alpha * color.a;
        b.setColor(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, ca);
        walk(b, font, top, left - a + jx, base + jy, 0);
        b.setColor(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, ca);
        walk(b, font, top, left + a - jx, base - jy, 0);
        b.setColor(color.r, color.g, color.b, color.a * alpha);
        walk(b, font, top, left, base, 0);
        b.setPackedColor(old);
        return w;
    }

    // ------------------------------------------------------------------ G2 / G2-red / G3
    private float drawBurst(Batch b, Role rest, float rasterCap, float cap, Fonts.GlyphSet set, CharSequence text,
                            float x, float y, int align, Color color, float alpha, GlitchState st, float time,
                            float I, boolean red, float bt) {
        Role role = rest;
        if (!red && rest.swaps()) {
            int idx = Math.min(3, (int) (bt * 4f));
            role = idx < 3 ? SWAP[idx] : rest;
        }
        BitmapFont font = fonts.prepare(role, rasterCap, cap, set, text);
        float top = fonts.lastTop();
        layout.setText(font, text);
        float w = layout.width;
        float left = Fonts.alignX(x, w, align);
        float base = Fonts.alignBaseline(y, cap, align);
        pivot(left, w, base, cap);
        int tick = (int) Math.floor(time * 24f);
        int h = st.seed * 31 + tick;
        float sizeK = Math.max(1f, cap / 60f);

        // sub-UV slices: one band per third of the cap height, 1..3 of them
        int n = 1 + (int) (Anim.hash01(h, 1) * 2.999f);
        bands = 0;
        for (int k = 0; k < 3 && bands < n; k++) {
            if (n < 3 && Anim.hash01(h, 10 + k) < 0.35f && 3 - k > n - bands) continue;
            float bh = cap * (0.12f + 0.18f * Anim.hash01(h, 20 + k));
            float third = cap / 3f;
            float lo = base + third * k + (third - Math.min(bh, third)) * Anim.hash01(h, 30 + k);
            bandLo[bands] = lo;
            bandHi[bands] = lo + Math.min(bh, third);
            float mag = (4f + 20f * Anim.hash01(h, 40 + k)) * I * sizeK * 0.6f;
            bandDx[bands] = Anim.hash01(h, 50 + k) < 0.5f ? -mag : mag;
            bands++;
        }

        float jx = 0f, jy = 0f;
        float elapsed = st.burstElapsed(time);
        if (red && elapsed < 2f * FRAME && !reduceFlashing) {
            jx = (Anim.hash01(h, 60) * 2f - 1f) * 4f;
            jy = (Anim.hash01(h, 61) * 2f - 1f) * 4f;
        }

        float old = b.getPackedColor();
        float ca = 0.35f * alpha * color.a;
        if (red) {
            float d = 4f * I * sizeK;
            b.setColor(Theme.LOST.r, Theme.LOST.g, Theme.LOST.b, ca);
            walk(b, font, top, left - d + jx, base + jy, bands);
            walk(b, font, top, left + d + jx, base + jy, bands);
        } else {
            float a = 6f * I * sizeK;
            b.setColor(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, ca);
            walk(b, font, top, left - a, base, bands);
            b.setColor(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, ca);
            walk(b, font, top, left + a, base, bands);
        }
        b.setColor(color.r, color.g, color.b, color.a * alpha);
        walk(b, font, top, left + jx, base + jy, bands);

        // tear bars
        if (!reduceFlashing) {
            int bars = 1 + (int) (Anim.hash01(h, 70) * 2.999f);
            TextureRegion white = draw.white();
            for (int k = 0; k < bars; k++) {
                float bh = (4f + 10f * Anim.hash01(h, 80 + k)) * sizeK * 0.8f;
                float by = base - cap * 0.1f + (cap * 1.2f - bh) * Anim.hash01(h, 90 + k);
                float bx = left - 24f + (Anim.hash01(h, 100 + k) * 2f - 1f) * 24f;
                float bw = (w + 48f) * (0.35f + 0.65f * Anim.hash01(h, 110 + k));
                bx += (w + 48f - bw) * Anim.hash01(h, 120 + k);
                Color c = red ? Theme.LOST : ((k & 1) == 0 ? Theme.GLITCH_C : Theme.GLITCH_M);
                b.setColor(c.r, c.g, c.b, 0.25f * alpha);
                b.draw(white, bx, by, bw, bh);
            }
        }
        b.setPackedColor(old);
        return w;
    }

    /**
     * Screen-level G3 tear: 2–4 full-width bars 6–30 tall at α .08–.15 over the given rect,
     * while {@code state} has a G3 burst running. Skipped at I = 0 and with reduce flashing.
     */
    public void drawScreenTears(Batch b, GlitchState state, float time, float x, float y, float w, float h) {
        if (state == null || reduceFlashing || intensity <= 0.001f) return;
        if (state.levelAt(time) != Level.G3) return;
        int tick = (int) Math.floor(time * 24f);
        int hs = state.seed * 17 + tick;
        int bars = 2 + (int) (Anim.hash01(hs, 1) * 2.999f);
        float old = b.getPackedColor();
        TextureRegion white = draw.white();
        for (int k = 0; k < bars; k++) {
            float bh = 6f + 24f * Anim.hash01(hs, 10 + k);
            float by = y + (h - bh) * Anim.hash01(hs, 20 + k);
            float a = (0.08f + 0.07f * Anim.hash01(hs, 30 + k)) * intensity;
            int pick = (int) (Anim.hash01(hs, 40 + k) * 3f);
            Color c = pick == 0 ? Theme.GLITCH_C : (pick == 1 ? Theme.GLITCH_M : Theme.INK1);
            b.setColor(c.r, c.g, c.b, a);
            float dx = (Anim.hash01(hs, 50 + k) * 2f - 1f) * 24f;
            b.draw(white, x + dx, by, w, bh);
        }
        b.setPackedColor(old);
    }

    /** HUD-layer jitter (±4 px) while a G3 burst runs; 0 otherwise. */
    public float screenJitterX(GlitchState state, float time) {
        if (state == null || reduceFlashing || intensity <= 0.001f || state.levelAt(time) != Level.G3) return 0f;
        int tick = (int) Math.floor(time * 24f);
        return (Anim.hash01(state.seed, tick) * 2f - 1f) * 4f * intensity;
    }

    /**
     * Decode helper (title decode, header type-on): writes {@code text} into {@code out} where
     * characters not yet revealed (char i reveals at {@code i × stagger} seconds after start)
     * are replaced by a random glyph from {@code #%&0-9A-F}, changing every frame tick.
     * Spaces are kept. No allocation.
     */
    public static StringBuilder decodeInto(StringBuilder out, CharSequence text, float elapsed, float stagger,
                                           int seed) {
        out.setLength(0);
        if (text == null) return out;
        int tick = (int) Math.floor(elapsed * 60f);
        String pool = "#%&0123456789ABCDEF";
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            float reveal = i * stagger + 2f * FRAME;
            if (c == ' ' || elapsed >= reveal) {
                out.append(c);
            } else if (elapsed >= i * stagger) {
                out.append(pool.charAt((int) (Anim.hash01(seed + i, tick) * pool.length()) % pool.length()));
            } else {
                out.append(' ');
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ glyph walker
    /**
     * Draws the current {@link #layout} glyph by glyph with the batch colour, baseline at
     * {@code base}; when {@code nBands > 0} each glyph is split at the slice bands and the band
     * pieces are shifted horizontally.
     */
    private void walk(Batch b, BitmapFont font, float top, float left, float base, int nBands) {
        BitmapFont.BitmapFontData data = font.getData();
        float sx = data.scaleX, sy = data.scaleY;
        float yOrigin = base + top + data.ascent;
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
                float qx = gx + g.xoffset * sx;
                float qy = gy + g.yoffset * sy;
                float qw = g.width * sx, qh = g.height * sy;
                Texture tex = font.getRegion(g.page).getTexture();
                if (nBands == 0) {
                    quad(b, tex, qx, qy, qw, qh, g.u, g.v, g.u2, g.v2);
                    continue;
                }
                float cursor = qy, end = qy + qh;
                for (int k = 0; k < nBands; k++) {
                    float lo = Math.max(bandLo[k], qy), hi = Math.min(bandHi[k], end);
                    if (hi <= lo || lo < cursor) continue;
                    piece(b, tex, g, qx, qy, qw, qh, cursor, lo, 0f);
                    piece(b, tex, g, qx, qy, qw, qh, lo, hi, bandDx[k]);
                    cursor = hi;
                }
                piece(b, tex, g, qx, qy, qw, qh, cursor, end, 0f);
            }
        }
    }

    private void piece(Batch b, Texture tex, BitmapFont.Glyph g, float qx, float qy, float qw, float qh,
                       float y0, float y1, float dx) {
        if (y1 - y0 <= 0.01f) return;
        float t0 = (y0 - qy) / qh, t1 = (y1 - qy) / qh;
        float v0 = g.v + (g.v2 - g.v) * t0;
        float v1 = g.v + (g.v2 - g.v) * t1;
        quad(b, tex, qx + dx, y0, qw, y1 - y0, g.u, v0, g.u2, v1);
    }

    /** Sets the rotation pivot to the centre of the text box (left, base, w × cap). */
    private void pivot(float left, float w, float base, float cap) {
        pivotX = left + w * 0.5f;
        pivotY = base + cap * 0.5f;
    }

    /**
     * Draws one glyph quad with the batch colour: {@code (u, vBottom)} at the bottom-left,
     * {@code (u2, vTop)} at the top-right, rotated by {@link #rotation} about the pivot when set.
     */
    private void quad(Batch b, Texture tex, float x, float y, float w, float h, float u, float vBottom, float u2,
                      float vTop) {
        if (rotation == 0f) {
            b.draw(tex, x, y, w, h, u, vBottom, u2, vTop);
            return;
        }
        float c = rotCos, s = rotSin, col = b.getPackedColor();
        float x1 = x - pivotX, y1 = y - pivotY, x2 = x1 + w, y2 = y1 + h;
        float[] v = verts;
        v[0] = pivotX + c * x1 - s * y1;
        v[1] = pivotY + s * x1 + c * y1;
        v[2] = col;
        v[3] = u;
        v[4] = vBottom;
        v[5] = pivotX + c * x1 - s * y2;
        v[6] = pivotY + s * x1 + c * y2;
        v[7] = col;
        v[8] = u;
        v[9] = vTop;
        v[10] = pivotX + c * x2 - s * y2;
        v[11] = pivotY + s * x2 + c * y2;
        v[12] = col;
        v[13] = u2;
        v[14] = vTop;
        v[15] = pivotX + c * x2 - s * y1;
        v[16] = pivotY + s * x2 + c * y1;
        v[17] = col;
        v[18] = u2;
        v[19] = vBottom;
        b.draw(tex, v, 0, 20);
    }
}
