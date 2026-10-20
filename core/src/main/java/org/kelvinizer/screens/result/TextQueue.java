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
 * 结算画面的文字队列：同一字体角色的各种字号共用一张栅格缩放绘制，
 * 文字先记录下来，等本帧的图形画完后再按字体页分组一起绘制。
 * 曲绘、头像和分数格子等也能加入对应的组，以减少换贴图和绘制调用（目标：结算画面不超过 12 次）。
 *
 * <p>Page-grouped text for the DIVE LOG (DESIGN §14: result ≤ 12 draw calls, 2–3 font pages).
 *
 * <p>Two ideas keep the font pages and batch flushes down:
 * <ul>
 *   <li><b>Shared rasters.</b> Every size of a role is drawn from one raster of that role,
 *       scaled ({@link #raster}): Chakra SemiBold at 22 serves both {@code UI} and the tracked
 *       {@code UI_LABEL} (the 0.10 em tracking is added per glyph here, so both share a page),
 *       Chakra Bold at 34, NUMBER (Chakra Bold) at 30 (full set, with fixed digit cells drawn here),
 *       Rubik at 30 / 48. Sizes above a raster's range use their own bucket.</li>
 *   <li><b>Deferred drawing.</b> While {@link #begin() deferring}, text calls only measure and
 *       record; {@link #flush()} then draws every record grouped by texture page, in order of
 *       first use, after all the shapes of the frame. Custom glyph work (score cells, the
 *       rotated stamp letter) and images (jacket, avatar) join the groups through
 *       {@link Deferred}. While a batch transform is pushed (pops, flips) or outside
 *       begin/flush, calls draw at once with the same renderer, so nothing changes visually.</li>
 * </ul>
 *
 * <p>Anchoring and units follow {@link Fonts}: sizes are cap heights in world units, {@code y}
 * is the baseline unless {@link Align#top} or {@link Fonts#VCENTER} is set. Text that needs a
 * glyph the raster lacks is routed to the CJK font through {@link Fonts#drawScaled}.
 *
 * <p>No allocation after construction: text is copied into a fixed char pool (records beyond
 * the capacity draw at once). GL thread only.
 */
public final class TextQueue {
    /** A custom draw that belongs to a texture page (called during {@link #flush()}). */
    public interface Deferred {
        /** Draws the deferred work identified by {@code arg}. */
        void drawDeferred(int arg);
    }

    /** Chakra SemiBold raster (UI and UI_LABEL up to 25). */
    public static final float UI_RASTER = 22f;
    /** Chakra Bold raster (UI_BOLD up to 36). */
    public static final float BOLD_RASTER = 34f;
    /** NUMBER (Chakra Petch Bold) raster (full set, up to 32). */
    public static final float NUMBER_RASTER = 30f;
    /** Rubik rasters: small (≤ 32) and alert (≤ 50). */
    public static final float RUBIK_SMALL = 30f, RUBIK_ALERT = 48f;
    /** UI_LABEL tracking in cap heights (FreeType {@code spaceX = 0.10 × size}, size = cap / 0.70). */
    public static final float LABEL_TRACKING = 0.10f / 0.70f;
    /** Fixed digit cell of the NUMBER face in cap heights (Chakra Petch, as {@code Fonts.cellWidth}). */
    private static final float DIGIT_CELL = 0.92f;

    private static final int MAX = 224, POOL = 8192;
    private static final byte K_RUN = 0, K_FIXED = 1, K_FONTS = 2, K_DEFERRED = 3;

    private final Fonts fonts;
    private final Batch batch;
    private final GlyphLayout layout = new GlyphLayout();
    private final CharView view = new CharView();

    private final Texture[] page = new Texture[MAX];
    private final byte[] kind = new byte[MAX];
    private final Fonts.Role[] role = new Fonts.Role[MAX];
    private final Fonts.GlyphSet[] set = new Fonts.GlyphSet[MAX];
    private final float[] rasterOf = new float[MAX], capOf = new float[MAX], xOf = new float[MAX],
        yOf = new float[MAX], track = new float[MAX], colOf = new float[MAX], maxWOf = new float[MAX],
        minSOf = new float[MAX];
    private final int[] alignOf = new int[MAX], off = new int[MAX], len = new int[MAX], argOf = new int[MAX];
    private final Deferred[] cb = new Deferred[MAX];
    private final boolean[] done = new boolean[MAX];
    private final char[] pool = new char[POOL];
    private int n, used;
    private boolean deferring;
    private int direct;
    private final Color tmp = new Color();

    /** Creates the queue over the shared services. */
    public TextQueue(Fonts fonts, Batch batch) {
        this.fonts = fonts;
        this.batch = batch;
    }

    // ------------------------------------------------------------------ frame
    /** Starts deferring: text calls record until {@link #flush()}. */
    public void begin() {
        n = 0;
        used = 0;
        deferring = true;
        direct = 0;
    }

    /** Forces immediate drawing while {@code on} (nestable; the kit sets it around transforms). */
    public void direct(boolean on) {
        direct += on ? 1 : -1;
        if (direct < 0) direct = 0;
    }

    private boolean recording() {
        return deferring && direct == 0;
    }

    /**
     * Draws every record grouped by page (first-use order, record order within a page) and stops
     * deferring for the rest of the frame.
     */
    public void flush() {
        deferring = false;
        for (int i = 0; i < n; i++) done[i] = false;
        for (int i = 0; i < n; i++) {
            if (done[i]) continue;
            Texture p = page[i];
            for (int j = i; j < n; j++) {
                if (done[j] || page[j] != p) continue;
                done[j] = true;
                drawRecord(j);
            }
        }
        for (int i = 0; i < n; i++) {
            page[i] = null;
            cb[i] = null;
        }
        n = 0;
        used = 0;
    }

    // ------------------------------------------------------------------ rasters
    /** Raster cap used to draw {@code role} at {@code cap} (the role's shared raster when in range). */
    public static float raster(Fonts.Role role, float cap) {
        switch (role) {
            case UI:
            case UI_LABEL:
                return cap <= 25f ? UI_RASTER : cap;
            case UI_BOLD:
                return cap <= 36f ? BOLD_RASTER : cap;
            case NUMBER:
                return cap <= 32f ? NUMBER_RASTER : cap;
            case RUBIK:
                return cap <= 32f ? RUBIK_SMALL : (cap <= 50f ? RUBIK_ALERT : cap);
            default:
                return cap;
        }
    }

    /** Font role actually rasterised for {@code role} (UI_LABEL shares the UI raster). */
    private static Fonts.Role face(Fonts.Role role) {
        return role == Fonts.Role.UI_LABEL ? Fonts.Role.UI : role;
    }

    /** Pre-generates the rasters every result text uses (call while covered). */
    public void prewarm() {
        fonts.prewarm(Fonts.Role.UI, UI_RASTER, Fonts.GlyphSet.FULL);
        fonts.prewarm(Fonts.Role.UI_BOLD, BOLD_RASTER, Fonts.GlyphSet.FULL);
        fonts.prewarm(Fonts.Role.NUMBER, NUMBER_RASTER, Fonts.GlyphSet.FULL);
        fonts.prewarm(Fonts.Role.RUBIK, RUBIK_SMALL, Fonts.GlyphSet.FULL);
        fonts.prewarm(Fonts.Role.RUBIK, RUBIK_ALERT, Fonts.GlyphSet.FULL);
    }

    /**
     * Measures {@code text} as it will be drawn (generating any CJK glyphs it needs now, while
     * the screen is covered, rather than mid-animation).
     */
    public void warm(Fonts.Role role, float cap, CharSequence text) {
        width(role, cap, text);
    }

    // ------------------------------------------------------------------ measuring
    /** Width of {@code text} at {@code cap} as {@link #text} draws it. */
    public float width(Fonts.Role role, float cap, CharSequence text) {
        return width(role, cap, Fonts.GlyphSet.FULL, text);
    }

    /** Width with a glyph set (non-FULL sets are drawn from their own bucket). */
    public float width(Fonts.Role role, float cap, Fonts.GlyphSet gs, CharSequence text) {
        if (text == null || text.length() == 0 || cap <= 0f) return 0f;
        if (gs != Fonts.GlyphSet.FULL) return fonts.width(role, cap, gs, text);
        float r = raster(role, cap);
        Fonts.Role f = face(role);
        BitmapFont font = fonts.get(f, r);
        if (needsCjk(font, text)) return fonts.width(role, r, text) * cap / r;
        scaleTo(font, r, cap);
        layout.setText(font, text);
        int glyphs = glyphCount();
        return layout.width + (role == Fonts.Role.UI_LABEL ? LABEL_TRACKING * cap * Math.max(0, glyphs - 1) : 0f);
    }

    /** Width {@link #fixed} uses for {@code text} at {@code cap} (NUMBER cells). */
    public float fixedWidth(float cap, CharSequence text) {
        if (text == null) return 0f;
        float r = raster(Fonts.Role.NUMBER, cap);
        BitmapFont font = fonts.get(Fonts.Role.NUMBER, r);
        scaleTo(font, r, cap);
        float w = 0f;
        for (int i = 0, c = text.length(); i < c; i++) w += cellW(font, cap, text.charAt(i));
        return w;
    }

    /** Width of {@code digits} fixed digit cells (with group commas when {@code grouping}). */
    public float digitsWidth(float cap, int digits, boolean grouping) {
        float w = digits * cap * DIGIT_CELL;
        if (grouping && digits > 3) {
            float r = raster(Fonts.Role.NUMBER, cap);
            BitmapFont font = fonts.get(Fonts.Role.NUMBER, r);
            scaleTo(font, r, cap);
            w += ((digits - 1) / 3) * cellW(font, cap, ',');
        }
        return w;
    }

    // ------------------------------------------------------------------ drawing
    /** Draws (or records) {@code text}; returns its width. */
    public float text(Fonts.Role role, float cap, CharSequence text, float x, float y, int align, Color color,
                      float alpha) {
        return text(role, cap, Fonts.GlyphSet.FULL, text, x, y, align, color, alpha);
    }

    /** As {@link #text(Fonts.Role, float, CharSequence, float, float, int, Color, float)} with a glyph set. */
    public float text(Fonts.Role role, float cap, Fonts.GlyphSet gs, CharSequence text, float x, float y, int align,
                      Color color, float alpha) {
        if (text == null || text.length() == 0 || cap <= 0f || alpha <= 0.002f) return width(role, cap, gs, text);
        if (gs != Fonts.GlyphSet.FULL) {
            float w = fonts.width(role, cap, gs, text);
            BitmapFont font = fonts.get(role, cap, gs);
            return add(K_FONTS, font, role, gs, cap, cap, text, x, y, align, 0f, color, alpha, -1f, 1f, w);
        }
        float r = raster(role, cap);
        Fonts.Role f = face(role);
        BitmapFont font = fonts.get(f, r);
        if (needsCjk(font, text)) {
            float w = fonts.width(role, r, text) * cap / r;
            BitmapFont cjk = fonts.get(Fonts.Role.CJK, r);
            return add(K_FONTS, cjk, role, gs, r, cap, text, x, y, align, 0f, color, alpha, -1f, 1f, w);
        }
        scaleTo(font, r, cap);
        layout.setText(font, text);
        float tr = role == Fonts.Role.UI_LABEL ? LABEL_TRACKING * cap : 0f;
        float w = layout.width + tr * Math.max(0, glyphCount() - 1);
        return add(K_RUN, font, f, gs, r, cap, text, alignX(x, w, align), alignBaseline(y, cap, align), Align.left,
            tr, color, alpha, -1f, 1f, w);
    }

    /**
     * Draws {@code text} shrunk to fit {@code maxWidth} (down to {@code minScale}), ellipsis-
     * truncated beyond that; returns the drawn width.
     */
    public float textFit(Fonts.Role role, float cap, CharSequence text, float x, float y, int align, float maxWidth,
                         float minScale, Color color, float alpha) {
        float w = width(role, cap, text);
        if (w <= maxWidth || w <= 0f) return text(role, cap, text, x, y, align, color, alpha);
        float k = Math.max(minScale, maxWidth / w);
        if (w * k <= maxWidth + 0.5f) return text(role, cap * k, text, x, y, align, color, alpha);
        // still too wide at the minimum scale: let Fonts truncate (rare; own page for labels)
        float r = raster(role, cap);
        BitmapFont font = fonts.get(role, r);
        if (alpha <= 0.002f) return maxWidth;
        return add(K_FONTS, font, role, Fonts.GlyphSet.FULL, r, cap, text, x, y, align, 0f, color, alpha, maxWidth,
            minScale, maxWidth);
    }

    /**
     * Draws a short numeric string in fixed NUMBER cells (each digit 0.92 × cap, as {@code Fonts.cellWidth} for
     * Chakra Petch; other chars their advance); returns the width.
     */
    public float fixed(float cap, CharSequence text, float x, float y, int align, Color color, float alpha) {
        if (text == null || text.length() == 0) return 0f;
        float w = fixedWidth(cap, text);
        if (alpha <= 0.002f) return w;
        float r = raster(Fonts.Role.NUMBER, cap);
        BitmapFont font = fonts.get(Fonts.Role.NUMBER, r);
        return add(K_FIXED, font, Fonts.Role.NUMBER, Fonts.GlyphSet.FULL, r, cap, text, alignX(x, w, align),
            alignBaseline(y, cap, align), Align.left, 0f, color, alpha, -1f, 1f, w);
    }

    /**
     * One fixed NUMBER cell of {@code ch} (left edge {@code x}) drawn at once, not queued, and only between
     * {@code y0} and {@code y1}: an odometer window. For direct drawing (inside a {@code ResultKit.pushIf}).
     */
    public void cellClipped(float cap, char ch, float x, float base, float y0, float y1, Color color, float alpha) {
        if (alpha <= 0.002f) return;
        float r = raster(Fonts.Role.NUMBER, cap);
        BitmapFont font = fonts.get(Fonts.Role.NUMBER, r);
        scaleTo(font, r, cap);
        float old = batch.getPackedColor();
        batch.setColor(color.r, color.g, color.b, clamp01(color.a * alpha));
        quads.clipped(batch, font, ch, x, cellW(font, cap, ch), base, y0, y1);
        batch.setPackedColor(old);
    }

    private final StringBuilder digitSb = new StringBuilder(32);

    /** {@link #fixed} for an integer (optionally grouped with commas). */
    public float digits(float cap, long value, boolean grouping, float x, float y, int align, Color color,
                        float alpha) {
        digitSb.setLength(0);
        if (grouping) Fonts.appendGrouped(digitSb, value);
        else Fonts.appendInt(digitSb, value);
        return fixed(cap, digitSb, x, y, align, color, alpha);
    }

    /**
     * Joins {@code d.drawDeferred(arg)} to the group of {@code pageTex} (or calls it at once when
     * not deferring).
     */
    public void deferred(Texture pageTex, Deferred d, int arg) {
        if (!recording() || n >= MAX) {
            d.drawDeferred(arg);
            return;
        }
        int i = n++;
        kind[i] = K_DEFERRED;
        page[i] = pageTex;
        cb[i] = d;
        argOf[i] = arg;
    }

    /** True while records are being deferred (custom drawers use it to pick their path). */
    public boolean isDeferring() {
        return recording();
    }

    // ------------------------------------------------------------------ records
    private float add(byte k, BitmapFont font, Fonts.Role r, Fonts.GlyphSet gs, float raster, float cap,
                      CharSequence text, float x, float y, int align, float tracking, Color color, float alpha,
                      float maxW, float minScale, float width) {
        int len0 = text.length();
        if (!recording() || n >= MAX || used + len0 > POOL) {
            drawNow(k, font, r, gs, raster, cap, text, x, y, align, tracking,
                Color.toFloatBits(color.r, color.g, color.b, clamp01(color.a * alpha)), maxW, minScale, color, alpha);
            return width;
        }
        int i = n++;
        kind[i] = k;
        page[i] = font.getRegion().getTexture();
        role[i] = r;
        set[i] = gs;
        rasterOf[i] = raster;
        capOf[i] = cap;
        xOf[i] = x;
        yOf[i] = y;
        alignOf[i] = align;
        track[i] = tracking;
        colOf[i] = Color.toFloatBits(color.r, color.g, color.b, clamp01(color.a * alpha));
        maxWOf[i] = maxW;
        minSOf[i] = minScale;
        off[i] = used;
        len[i] = len0;
        for (int c = 0; c < len0; c++) pool[used + c] = text.charAt(c);
        used += len0;
        return width;
    }

    private void drawRecord(int i) {
        if (kind[i] == K_DEFERRED) {
            cb[i].drawDeferred(argOf[i]);
            return;
        }
        view.set(pool, off[i], len[i]);
        BitmapFont font;
        if (kind[i] == K_FONTS) {
            font = null;
        } else {
            font = fonts.get(role[i], rasterOf[i]);
        }
        Color.abgr8888ToColor(tmp, colOf[i]);
        drawNow(kind[i], font, role[i], set[i], rasterOf[i], capOf[i], view, xOf[i], yOf[i], alignOf[i], track[i],
            colOf[i], maxWOf[i], minSOf[i], tmp, 1f);
    }

    private void drawNow(byte k, BitmapFont font, Fonts.Role r, Fonts.GlyphSet gs, float raster, float cap,
                         CharSequence text, float x, float y, int align, float tracking, float packed, float maxW,
                         float minScale, Color color, float alpha) {
        switch (k) {
            case K_RUN:
                scaleTo(font, raster, cap);
                run(font, text, x, y, tracking, packed);
                break;
            case K_FIXED:
                scaleTo(font, raster, cap);
                cells(font, text, x, y, cap, packed);
                break;
            case K_FONTS:
            default:
                if (gs != Fonts.GlyphSet.FULL) {
                    fonts.draw(batch, r, cap, gs, text, x, y, align, color, alpha);
                } else {
                    fonts.drawScaled(batch, r, raster, cap / raster, text, x, y, align, maxW, minScale, color, alpha);
                }
                break;
        }
    }

    /** Draws laid-out glyph quads from {@code left}/{@code base} with extra {@code tracking} per glyph. */
    private void run(BitmapFont font, CharSequence text, float left, float base, float tracking, float packed) {
        BitmapFont.BitmapFontData d = font.getData();
        layout.setText(font, text);
        float sx = d.scaleX, sy = d.scaleY;
        float yOrigin = base - GlyphQuads.refYOffset(d) * sy;
        float old = batch.getPackedColor();
        batch.setPackedColor(packed);
        Array<GlyphLayout.GlyphRun> runs = layout.runs;
        int gi = 0;
        for (int r = 0; r < runs.size; r++) {
            GlyphLayout.GlyphRun run = runs.get(r);
            Array<BitmapFont.Glyph> glyphs = run.glyphs;
            FloatArray adv = run.xAdvances;
            float gx = left + run.x;
            float gy = yOrigin + run.y;
            for (int i = 0; i < glyphs.size; i++, gi++) {
                gx += adv.get(i);
                if (gi > 0) gx += tracking;
                BitmapFont.Glyph g = glyphs.get(i);
                if (g.width == 0 || g.height == 0) continue;
                Texture tex = font.getRegion(g.page).getTexture();
                batch.draw(tex, gx + g.xoffset * sx, gy + g.yoffset * sy, g.width * sx, g.height * sy, g.u, g.v,
                    g.u2, g.v2);
            }
        }
        batch.setPackedColor(old);
    }

    private void cells(BitmapFont font, CharSequence text, float left, float base, float cap, float packed) {
        float old = batch.getPackedColor();
        batch.setPackedColor(packed);
        float x = left;
        for (int i = 0, c = text.length(); i < c; i++) {
            char ch = text.charAt(i);
            float w = cellW(font, cap, ch);
            quads.cell(batch, font, ch, x, w, base, cap, 1f, null, null, null, 0);
            x += w;
        }
        batch.setPackedColor(old);
    }

    private final GlyphQuads quads = new GlyphQuads();

    // ------------------------------------------------------------------ helpers
    private int glyphCount() {
        int c = 0;
        for (int r = 0; r < layout.runs.size; r++) c += layout.runs.get(r).glyphs.size;
        return c;
    }

    /**
     * Sets the data scale of a raster font so it draws {@code cap}-tall caps (absolute: the
     * unscaled cap height is {@code capHeight / scaleY}, as BitmapFontData scales its metrics).
     */
    static void scaleTo(BitmapFont font, float raster, float cap) {
        BitmapFont.BitmapFontData d = font.getData();
        float cap0 = d.capHeight / d.scaleY;
        if (cap0 <= 0f) return;
        float target = cap / cap0;
        if (Math.abs(d.scaleX - target) > 1e-7f || Math.abs(d.scaleY - target) > 1e-7f) d.setScale(target);
    }

    /** True when {@code font} lacks a char of {@code text} (Fonts routes it to CJK). */
    private static boolean needsCjk(BitmapFont font, CharSequence text) {
        BitmapFont.BitmapFontData d = null;
        for (int i = 0, c = text.length(); i < c; i++) {
            char ch = text.charAt(i);
            if (ch < 0x80) continue;
            if (d == null) d = font.getData();
            if (d.getGlyph(ch) == null) return true;
        }
        return false;
    }

    private static float cellW(BitmapFont font, float cap, char c) {
        if (c >= '0' && c <= '9') return cap * DIGIT_CELL;
        BitmapFont.BitmapFontData d = font.getData();
        if (c == ' ') return d.spaceXadvance * d.scaleX;
        BitmapFont.Glyph g = d.getGlyph(c);
        if (g == null) return cap * 0.5f;
        return g.xadvance * d.scaleX;
    }

    private static float alignX(float x, float w, int align) {
        if ((align & Align.left) != 0) return x;
        if ((align & Align.right) != 0) return x - w;
        return x - w * 0.5f;
    }

    private static float alignBaseline(float y, float cap, int align) {
        if ((align & Align.top) != 0) return y - cap;
        if ((align & Fonts.VCENTER) != 0) return y - cap * 0.5f;
        return y;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** A CharSequence over a slice of the char pool (no allocation). */
    private static final class CharView implements CharSequence {
        private char[] a;
        private int o, l;

        void set(char[] a, int o, int l) {
            this.a = a;
            this.o = o;
            this.l = l;
        }

        @Override
        public int length() {
            return l;
        }

        @Override
        public char charAt(int index) {
            return a[o + index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new String(a, o + start, end - start);
        }

        @Override
        public String toString() {
            return new String(a, o, l);
        }
    }
}
