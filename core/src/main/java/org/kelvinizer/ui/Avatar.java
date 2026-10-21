package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import space.earlygrey.shapedrawer.JoinType;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 程序生成的「声呐印记」头像：形状、花纹、配色和符号组合后超采样渲染一次，
 * 存进 256² 的帧缓冲缓存；边框每帧实时绘制（声呐和虹彩边框会动）。
 * 由 Main 持有；GL 上下文丢失后调用 {@link #invalidate()}，所有头像会在下次使用时重新渲染。
 *
 * <p>"Sonar sigil" avatars (DESIGN §8.5): a shape, a pattern, a palette pair and a glyph rendered
 * once into a cached 256² {@link FrameBuffer} (with mipmaps), plus a frame drawn live on top
 * (the iridescent and sonar frames animate).
 *
 * <p>Rendering is supersampled: each sigil is drawn into a shared 512² scratch buffer and
 * box-filtered down into its cache entry, so every edge is anti-aliased. Cached textures hold
 * premultiplied alpha and are composited with {@code (ONE, ONE_MINUS_SRC_ALPHA)}.
 *
 * <p>Owned by Main ({@code AvatarCache} in DESIGN): never static. Call {@link #invalidate()} from
 * {@code Main.resume()}: the generation counter makes every entry re-render lazily after a GL
 * context loss. {@link #draw} may be called while the batch is drawing; a cache miss ends and
 * restarts the batch around the off-screen pass. At most {@link #MAX_ENTRIES} distinct sigils
 * should be visible in one frame (see {@link #region}).
 */
public final class Avatar implements Disposable {
    /** Option counts. */
    public static final int SHAPES = 5, PATTERNS = 8, PALETTES = 10, GLYPHS = 3, FRAMES = 4;
    /** Cache texture size. */
    public static final int SIZE = 256;
    /**
     * Number of cached sigils (256² RGBA + mips ≈ 340 KB each, allocated lazily). This is also the
     * most distinct sigils one frame can show without re-rendering: the largest planned screen
     * (PLAYER grid of 12 profiles plus the avatar editor's option previews) needs about 24.
     */
    public static final int MAX_ENTRIES = 32;

    private static final Color[] PAL_A = {
        Theme.rgb(0x4FD8FF, 1f), Theme.rgb(0x7FE7FF, 1f), Theme.rgb(0x6FA8FF, 1f), Theme.rgb(0x8F7BFF, 1f),
        Theme.rgb(0xC77DFF, 1f), Theme.rgb(0xFF5CA8, 1f), Theme.rgb(0xFFD27A, 1f), Theme.rgb(0x66F0FF, 1f),
        Theme.rgb(0x9CF5B0, 1f), Theme.rgb(0xFF2E88, 1f)};
    private static final Color[] PAL_B = {
        Theme.rgb(0xF2F7FF, 1f), Theme.rgb(0x134079, 1f), Theme.rgb(0x0F275E, 1f), Theme.rgb(0x0A1338, 1f),
        Theme.rgb(0x080B24, 1f), Theme.rgb(0x050613, 1f), Theme.rgb(0x0A1033, 1f), Theme.rgb(0xB49CFF, 1f),
        Theme.rgb(0x0D1C4C, 1f), Theme.rgb(0x00E5FF, 1f)};
    private static final Color DEFAULT_BG = Theme.rgb(0x0B1236, 1f);

    /** Avatar parameters (a plain mutable holder; map the profile's avatar fields into it). */
    public static final class Spec {
        /** Seed for pattern variation (rotation, constellation, grid fill). */
        public long seed;
        /** Option indices. */
        public int shape, pattern, palette, glyph, frame;
        /** Initial shown by the INITIAL glyph (any char; CJK routes automatically). */
        public char initial = 'K';

        /** Sets every field; returns this. */
        public Spec set(long seed, int shape, int pattern, int palette, int glyph, int frame, char initial) {
            this.seed = seed;
            this.shape = shape;
            this.pattern = pattern;
            this.palette = palette;
            this.glyph = glyph;
            this.frame = frame;
            this.initial = initial;
            return this;
        }

        /** Copies another spec. */
        public Spec set(Spec o) {
            return set(o.seed, o.shape, o.pattern, o.palette, o.glyph, o.frame, o.initial);
        }
    }

    private static final class Entry {
        FrameBuffer fbo;
        final TextureRegion region = new TextureRegion();
        long seed;
        int shape, pattern, palette, glyph, gen = -1;
        char initial;
        /** Monotonic use tick (LRU order). */
        long lastUse;
        /** Graphics frame id of the last use; entries used this frame are not evicted. */
        long lastFrame = -1;
    }

    private final Draw draw;
    private final Fonts fonts;
    private final Entry[] entries = new Entry[MAX_ENTRIES];
    private FrameBuffer scratch;
    private final TextureRegion scratchRegion = new TextureRegion();
    private int generation;
    private long useTick;
    private boolean warnedFull;
    private final Matrix4 proj = new Matrix4(), savedProj = new Matrix4();
    /** The caller's batch transform, set aside for the off-screen pass (see {@link #render}). */
    private final Matrix4 savedTransform = new Matrix4(), identity = new Matrix4();
    private final Color bg = new Color(), ca = new Color(), cb = new Color(), tmp = new Color();
    private final FloatArray pathBuf = new FloatArray(512);
    private final float[] quad = new float[8], clip = new float[24];
    private final float[] shield;
    private final float[] px = new float[7], py = new float[7];
    private final StringBuilder glyphText = new StringBuilder(2);
    private final int[] savedBlend = new int[4];

    /** Creates the cache (no GL work until the first draw). */
    public Avatar(Draw draw, Fonts fonts) {
        this.draw = draw;
        this.fonts = fonts;
        proj.setToOrtho2D(0f, 0f, SIZE, SIZE);
        shield = buildShield();
    }

    // ------------------------------------------------------------------ palette
    /** First colour of palette pair {@code i}. */
    public static Color paletteA(int i) {
        return PAL_A[Math.floorMod(i, PALETTES)];
    }

    /** Second colour of palette pair {@code i}. */
    public static Color paletteB(int i) {
        return PAL_B[Math.floorMod(i, PALETTES)];
    }

    private static float luminance(Color c) {
        return 0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b;
    }

    // ------------------------------------------------------------------ lifecycle
    /** Marks every cached sigil stale (call from Main.resume after a possible context loss). */
    public void invalidate() {
        generation++;
    }

    /** Current generation. */
    public int generation() {
        return generation;
    }

    @Override
    public void dispose() {
        for (int i = 0; i < MAX_ENTRIES; i++) {
            if (entries[i] != null && entries[i].fbo != null) entries[i].fbo.dispose();
            entries[i] = null;
        }
        if (scratch != null) scratch.dispose();
        scratch = null;
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Draws the avatar centred at (cx, cy), {@code size} world units across, with its frame.
     * {@code time} drives the animated frames.
     */
    public void draw(Batch b, Spec s, float cx, float cy, float size, float alpha, float time) {
        if (alpha <= 0.002f || size <= 0f) return;
        TextureRegion r = region(b, s);
        int srcC = b.getBlendSrcFunc(), dstC = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha(), dstA = b.getBlendDstFuncAlpha();
        float old = b.getPackedColor();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.setColor(alpha, alpha, alpha, alpha);
        b.draw(r, cx - size * 0.5f, cy - size * 0.5f, size, size);
        b.setBlendFunctionSeparate(srcC, dstC, srcA, dstA);
        b.setPackedColor(old);
        drawFrame(s, cx, cy, size, alpha, time);
    }

    /** Draws only the frame of {@code s} (live; animated). */
    public void drawFrame(Spec s, float cx, float cy, float size, float alpha, float time) {
        float k = size / SIZE;
        float r = size * 0.5f;
        Color a = paletteA(s.palette);
        switch (s.frame) {
            case 1: {
                int dashes = 28;
                float rr = r + 7f * k;
                float rot = time * 0.25f;
                float seg = MathUtils.PI2 / dashes;
                for (int i = 0; i < dashes; i++) {
                    float st = rot + i * seg;
                    draw.arc(cx, cy, rr, st, seg * 0.55f, Math.max(1.5f, 4f * k), a, 0.85f * alpha);
                }
                float blip = time * 1.3f;
                draw.glowDot(cx + MathUtils.cos(blip) * rr, cy + MathUtils.sin(blip) * rr,
                    26f * k + 8f, a, 0.8f * alpha);
                break;
            }
            case 2:
                draw.circle(cx, cy, r + 4f * k, Math.max(1.5f, 3f * k), Theme.INK1, 0.75f * alpha);
                draw.circle(cx, cy, r + 13f * k, Math.max(1f, 2f * k), Theme.INK1, 0.4f * alpha);
                break;
            case 3:
                Theme.iridescent(time, tmp);
                draw.glowDot(cx, cy, size * 1.25f, tmp, 0.18f * alpha);
                draw.circle(cx, cy, r + 6f * k, Math.max(2f, 6f * k), tmp, alpha);
                break;
            default:
                break;
        }
    }

    /**
     * Returns the cached texture region for {@code s}, rendering it first when missing or stale.
     * If the batch is drawing it is ended and restarted around the off-screen pass.
     *
     * <p>At most {@link #MAX_ENTRIES} distinct sigils can be shown in one frame. Eviction takes
     * the least recently used entry <em>not</em> used in the current frame; beyond the limit
     * entries are re-rendered every frame (logged once), so keep visible sigils under it.
     */
    public TextureRegion region(Batch b, Spec s) {
        long frame = Gdx.graphics != null ? Gdx.graphics.getFrameId() : 0L;
        Entry e = find(s);
        if (e == null || e.gen != generation) {
            if (e == null) e = claim(s, frame);
            boolean wasDrawing = b.isDrawing();
            if (wasDrawing) b.end();
            render(e, s);
            if (wasDrawing) b.begin();
        }
        e.lastUse = ++useTick;
        e.lastFrame = frame;
        return e.region;
    }

    private Entry find(Spec s) {
        for (int i = 0; i < MAX_ENTRIES; i++) {
            Entry e = entries[i];
            if (e != null && e.seed == s.seed && e.shape == s.shape && e.pattern == s.pattern
                && e.palette == s.palette && e.glyph == s.glyph && (s.glyph != 1 || e.initial == s.initial)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Picks a slot for a new sigil: a free slot; else the least recently used entry not drawn in
     * the last two frames; else (the visible set fills the cache) the most recently used entry not
     * drawn this frame; else the most recently drawn entry of this frame (more than
     * {@link #MAX_ENTRIES} distinct sigils in one frame; logged once). Preferring the most recent
     * entry once the visible set exceeds the cache keeps cyclic draws close to the minimum: 40
     * sigils per frame miss about 9 per frame instead of all 40. Re-rendering an entry already
     * drawn this frame is safe: {@link #region} flushes the batch before the off-screen pass.
     */
    private Entry claim(Spec s, long frame) {
        int free = -1, lru = -1, mru = -1, busy = -1;
        for (int i = 0; i < MAX_ENTRIES; i++) {
            Entry c = entries[i];
            if (c == null) {
                free = i;
                break;
            }
            if (c.lastFrame == frame) {
                if (busy < 0 || c.lastUse > entries[busy].lastUse) busy = i;
                continue;
            }
            if (lru < 0 || c.lastUse < entries[lru].lastUse) lru = i;
            if (mru < 0 || c.lastUse > entries[mru].lastUse) mru = i;
        }
        int slot;
        if (free >= 0) {
            slot = free;
        } else if (lru >= 0 && entries[lru].lastFrame < frame - 1) {
            slot = lru;
        } else if (mru >= 0) {
            slot = mru;
        } else {
            slot = busy;
            if (!warnedFull && Gdx.app != null) {
                warnedFull = true;
                Gdx.app.error("Avatar", "more than " + MAX_ENTRIES + " distinct sigils in one frame; re-rendering");
            }
        }
        Entry e = entries[slot];
        if (e == null) {
            e = new Entry();
            entries[slot] = e;
        }
        e.seed = s.seed;
        e.shape = s.shape;
        e.pattern = s.pattern;
        e.palette = s.palette;
        e.glyph = s.glyph;
        e.initial = s.initial;
        e.gen = -1;
        return e;
    }

    // ------------------------------------------------------------------ off-screen pass
    private void render(Entry e, Spec s) {
        SpriteBatch b = draw.batch();
        if (scratch == null) {
            scratch = new FrameBuffer(Pixmap.Format.RGBA8888, SIZE * 2, SIZE * 2, false);
        }
        if (e.fbo == null) e.fbo = new FrameBuffer(Pixmap.Format.RGBA8888, SIZE, SIZE, false);
        savedProj.set(b.getProjectionMatrix());
        // A caller drawing under a transform (a modal or the pause menu scaled for the UI scale)
        // would carry it into the off-screen pass and cache a misplaced sigil: draw it untransformed.
        savedTransform.set(b.getTransformMatrix());
        b.setTransformMatrix(identity);
        savedBlend[0] = b.getBlendSrcFunc();
        savedBlend[1] = b.getBlendDstFunc();
        savedBlend[2] = b.getBlendSrcFuncAlpha();
        savedBlend[3] = b.getBlendDstFuncAlpha();
        float oldColor = b.getPackedColor();
        float oldShape = draw.shapes().getPackedColor();

        palette(s.palette);
        // The caller may be inside a ScissorStack clip (scrolling lists): its rectangle would cut
        // the off-screen pass and cache a partial sigil, so scissoring is off for this pass.
        boolean scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        if (scissor) Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        // 1) supersampled sigil
        scratch.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.setProjectionMatrix(proj);
        b.begin();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ZERO, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        drawShape(s.shape);
        b.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ZERO, GL20.GL_ONE);
        drawPattern(b, s);
        drawGlyph(b, s);
        // premultiply: rgb *= dst alpha (also zeroes everything outside the shape)
        b.setBlendFunctionSeparate(GL20.GL_ZERO, GL20.GL_DST_ALPHA, GL20.GL_ZERO, GL20.GL_ONE);
        draw.rect(0f, 0f, SIZE, SIZE, Color.WHITE, 1f);
        b.end();
        scratch.end();

        // 2) 2×2 box-filter copy into the cache entry
        Texture st = scratch.getColorBufferTexture();
        st.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        scratchRegion.setRegion(st);
        scratchRegion.flip(false, true);
        e.fbo.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        b.setProjectionMatrix(proj);
        b.disableBlending();
        b.begin();
        b.setColor(Color.WHITE);
        b.draw(scratchRegion, 0f, 0f, SIZE, SIZE);
        b.end();
        b.enableBlending();
        e.fbo.end();

        Texture t = e.fbo.getColorBufferTexture();
        t.bind();
        Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
        t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        e.region.setRegion(t);
        e.region.flip(false, true);
        e.gen = generation;

        if (scissor) Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        b.setBlendFunctionSeparate(savedBlend[0], savedBlend[1], savedBlend[2], savedBlend[3]);
        b.setProjectionMatrix(savedProj);
        b.setTransformMatrix(savedTransform);
        b.setPackedColor(oldColor);
        draw.shapes().setColor(oldShape);
    }

    private void palette(int p) {
        Color a = paletteA(p), pb = paletteB(p);
        ca.set(a);
        if (luminance(pb) < 0.3f) {
            bg.set(pb);
            cb.set(a).lerp(Theme.INK1, 0.55f);
        } else {
            bg.set(DEFAULT_BG);
            cb.set(pb);
        }
    }

    private void drawShape(int shape) {
        ShapeDrawer sd = draw.shapes();
        float c = SIZE * 0.5f;
        switch (Math.floorMod(shape, SHAPES)) {
            case 1:
                sd.setColor(bg);
                sd.filledPolygon(c, c, 6, 124f, MathUtils.PI / 6f);
                break;
            case 2:
                draw.roundRect(10f, 10f, SIZE - 20f, SIZE - 20f, 72f, bg, 1f);
                break;
            case 3:
                sd.setColor(bg);
                sd.filledPolygon(c, c, 4, 126f, 0f);
                break;
            case 4:
                sd.setColor(bg);
                sd.filledPolygon(shield, 0, shield.length);
                break;
            case 0:
            default:
                draw.disc(c, c, 122f, bg, 1f);
                break;
        }
        sd.setColor(Color.WHITE);
    }

    // ------------------------------------------------------------------ patterns
    private void drawPattern(Batch b, Spec s) {
        int h = (int) (s.seed ^ (s.seed >>> 32));
        float c = SIZE * 0.5f;
        float rot = Anim.hash01(h, 101) * MathUtils.PI2;
        ShapeDrawer sd = draw.shapes();
        switch (Math.floorMod(s.pattern, PATTERNS)) {
            case 0: { // rings
                float ox = (Anim.hash01(h, 3) - 0.5f) * 24f, oy = (Anim.hash01(h, 4) - 0.5f) * 24f;
                for (int i = 3; i >= 0; i--) {
                    float r = 30f + i * 27f;
                    Color col = (i & 1) == 0 ? ca : cb;
                    draw.circle(c + ox, c + oy, r, 10f - i, col, 0.95f - i * 0.17f);
                }
                draw.disc(c + ox, c + oy, 11f, ca, 1f);
                break;
            }
            case 1: { // spokes
                for (int i = 0; i < 12; i++) {
                    float a = rot + i * MathUtils.PI2 / 12f;
                    float cs = MathUtils.cos(a), sn = MathUtils.sin(a);
                    float r0 = (i & 1) == 0 ? 26f : 44f;
                    draw.line(c + cs * r0, c + sn * r0, c + cs * 150f, c + sn * 150f, (i & 1) == 0 ? 9f : 6f,
                        (i & 1) == 0 ? ca : cb, (i & 1) == 0 ? 0.9f : 0.6f);
                }
                draw.disc(c, c, 15f, ca, 1f);
                draw.circle(c, c, 26f, 3f, cb, 0.7f);
                break;
            }
            case 2: { // waves
                float phase = rot;
                for (int k = 0; k < 5; k++) {
                    float y0 = c + (k - 2) * 36f;
                    pathBuf.clear();
                    for (float x = -12f; x <= SIZE + 12f; x += 6f) {
                        pathBuf.add(x);
                        pathBuf.add(y0 + 13f * MathUtils.sin(x / 96f * MathUtils.PI2 + phase + k * 0.9f));
                    }
                    sd.setColor(k == 2 ? ca : cb);
                    tmp.set(k == 2 ? ca : cb);
                    tmp.a = k == 2 ? 1f : 0.55f + 0.1f * Math.abs(k - 2);
                    sd.setColor(tmp);
                    sd.path(pathBuf, k == 2 ? 10f : 7f, JoinType.SMOOTH, true);
                }
                sd.setColor(Color.WHITE);
                break;
            }
            case 3: { // dots (hex lattice)
                float fx = c + (Anim.hash01(h, 5) - 0.5f) * 120f, fy = c + (Anim.hash01(h, 6) - 0.5f) * 120f;
                float dx = 24f, dy = 20.8f;
                int row = 0;
                for (float y = 6f; y < SIZE; y += dy, row++) {
                    float off = (row & 1) == 0 ? 0f : dx * 0.5f;
                    int col = 0;
                    for (float x = 6f + off; x < SIZE; x += dx, col++) {
                        float d = (float) Math.sqrt((x - fx) * (x - fx) + (y - fy) * (y - fy));
                        float a = MathUtils.clamp(1.05f - d / 190f, 0.12f, 1f);
                        boolean alt = Anim.hash01(h + row * 31, col) < 0.14f;
                        draw.disc(x, y, alt ? 7f : 6f, alt ? cb : ca, a);
                    }
                }
                break;
            }
            case 4: { // spiral (Archimedean, 3 turns)
                pathBuf.clear();
                float turns = 3f, r0 = 6f, r1 = 114f;
                float total = turns * MathUtils.PI2;
                for (float t = 0f; t <= total; t += 0.09f) {
                    float r = r0 + (r1 - r0) * t / total;
                    pathBuf.add(c + MathUtils.cos(t + rot) * r);
                    pathBuf.add(c + MathUtils.sin(t + rot) * r);
                }
                sd.setColor(ca);
                sd.path(pathBuf, 9f, JoinType.SMOOTH, true);
                sd.setColor(Color.WHITE);
                float ea = total + rot;
                draw.disc(c + MathUtils.cos(ea) * r1, c + MathUtils.sin(ea) * r1, 9f, cb, 1f);
                draw.disc(c, c, 8f, cb, 1f);
                break;
            }
            case 5: { // grid of rounded squares
                float cell = 44f, gap = 12f, o = (SIZE - 4f * cell - 3f * gap) * 0.5f;
                for (int gy = 0; gy < 4; gy++) {
                    for (int gx = 0; gx < 4; gx++) {
                        float x = o + gx * (cell + gap), y = o + gy * (cell + gap);
                        float v = Anim.hash01(h + gy * 7, gx);
                        if (v < 0.38f) {
                            draw.roundRect(x, y, cell, cell, 11f, ca, 0.95f);
                        } else if (v < 0.7f) {
                            draw.roundRectStroke(x, y, cell, cell, 11f, 3f, cb, 0.9f);
                        } else {
                            draw.roundRect(x, y, cell, cell, 11f, ca, 0.22f);
                        }
                    }
                }
                break;
            }
            case 6: { // split-K: a big K cut along the diagonal in two colours
                tmp.set(cb);
                sd.setColor(tmp.r, tmp.g, tmp.b, 0.12f);
                sd.filledTriangle(0f, 0f, SIZE, 0f, SIZE, SIZE);
                boolean flip = Anim.hash01(h, 9) < 0.5f;
                kStroke(sd, 80f, 34f, 80f, 222f, 34f, flip);
                kStroke(sd, 92f, 124f, 196f, 222f, 34f, flip);
                kStroke(sd, 112f, 146f, 200f, 34f, 34f, flip);
                draw.line(0f, 0f, SIZE, SIZE, 2f, Theme.INK1, 0.35f);
                sd.setColor(Color.WHITE);
                break;
            }
            case 7: // constellation
            default: {
                for (int i = 0; i < 7; i++) {
                    float a = Anim.hash01(h, 200 + i) * MathUtils.PI2;
                    float r = 18f + 84f * (float) Math.sqrt(Anim.hash01(h, 300 + i));
                    px[i] = c + MathUtils.cos(a) * r;
                    py[i] = c + MathUtils.sin(a) * r;
                }
                for (int i = 1; i < 7; i++) {
                    int best = 0;
                    float bd = Float.MAX_VALUE;
                    for (int j = 0; j < i; j++) {
                        float d = (px[i] - px[j]) * (px[i] - px[j]) + (py[i] - py[j]) * (py[i] - py[j]);
                        if (d < bd) {
                            bd = d;
                            best = j;
                        }
                    }
                    draw.line(px[i], py[i], px[best], py[best], 3f, cb, 0.7f);
                }
                draw.line(px[6], py[6], px[0], py[0], 2f, cb, 0.35f);
                for (int i = 0; i < 7; i++) {
                    boolean star = i == 0;
                    draw.glowDot(px[i], py[i], star ? 64f : 36f, ca, star ? 0.6f : 0.4f);
                    draw.disc(px[i], py[i], star ? 10f : 6f, star ? Theme.INK1 : ca, 1f);
                }
                break;
            }
        }
    }

    /** One K stroke as a quad, clipped by the diagonal y = x into two coloured halves. */
    private void kStroke(ShapeDrawer sd, float x0, float y0, float x1, float y1, float width, boolean flip) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float nx = -dy / len * width * 0.5f, ny = dx / len * width * 0.5f;
        quad[0] = x0 + nx;
        quad[1] = y0 + ny;
        quad[2] = x1 + nx;
        quad[3] = y1 + ny;
        quad[4] = x1 - nx;
        quad[5] = y1 - ny;
        quad[6] = x0 - nx;
        quad[7] = y0 - ny;
        for (int side = 0; side < 2; side++) {
            int n = clipHalf(quad, 4, side == 0);
            if (n < 3) continue;
            Color col = (side == 0) != flip ? ca : cb;
            sd.setColor(col);
            sd.filledPolygon(clip, 0, n * 2);
        }
    }

    /** Sutherland–Hodgman against the half-plane y ≥ x (above) or y ≤ x; returns vertex count. */
    private int clipHalf(float[] in, int count, boolean above) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            float ax = in[i * 2], ay = in[i * 2 + 1];
            int j = (i + 1) % count;
            float bx = in[j * 2], by = in[j * 2 + 1];
            float fa = above ? ay - ax : ax - ay;
            float fb = above ? by - bx : bx - by;
            if (fa >= 0f) {
                clip[n * 2] = ax;
                clip[n * 2 + 1] = ay;
                n++;
            }
            if ((fa >= 0f) != (fb >= 0f)) {
                float t = fa / (fa - fb);
                clip[n * 2] = ax + (bx - ax) * t;
                clip[n * 2 + 1] = ay + (by - ay) * t;
                n++;
            }
        }
        return n;
    }

    private void drawGlyph(Batch b, Spec s) {
        if (s.glyph <= 0) return;
        float c = SIZE * 0.5f;
        glyphText.setLength(0);
        Fonts.Role role;
        Fonts.GlyphSet set;
        if (s.glyph == 2) {
            glyphText.append('K');
            role = Fonts.Role.DISPLAY;
            set = Fonts.GlyphSet.LOGO;
        } else {
            char ch = Character.toUpperCase(s.initial);
            glyphText.append(ch);
            boolean simple = (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
            role = simple ? Fonts.Role.NUMBER : Fonts.Role.UI_BOLD;
            set = simple ? Fonts.GlyphSet.WORDS : Fonts.GlyphSet.FULL;
        }
        // dark backing so the glyph reads over any pattern
        draw.sprite(draw.softDot(), c, c, 230f, 230f, bg, 0.9f);
        draw.disc(c, c, 74f, bg, 0.55f);
        fonts.pushPixelScale(2f);
        float cap = 112f;
        fonts.draw(b, role, cap, set, glyphText, c + 2f, c - 5f, Fonts.VCENTER, bg, 0.7f);
        fonts.draw(b, role, cap, set, glyphText, c, c, Fonts.VCENTER, Theme.INK1, 1f);
        fonts.popPixelScale();
    }

    private static float[] buildShield() {
        FloatArray f = new FloatArray(64);
        float top = 238f, left = 26f, right = 230f, r = 18f;
        // top-left rounded corner → top-right rounded corner
        for (int i = 0; i <= 4; i++) {
            float a = MathUtils.PI - i * MathUtils.HALF_PI / 4f;
            f.add(left + r + MathUtils.cos(a) * r);
            f.add(top - r + MathUtils.sin(a) * r);
        }
        for (int i = 0; i <= 4; i++) {
            float a = MathUtils.HALF_PI - i * MathUtils.HALF_PI / 4f;
            f.add(right - r + MathUtils.cos(a) * r);
            f.add(top - r + MathUtils.sin(a) * r);
        }
        // right side: straight down to y 150, then a quadratic curve to the bottom point
        float sx = right, sy = 150f, cx = right, cy = 58f, ex = 128f, ey = 10f;
        for (int i = 0; i <= 10; i++) {
            float t = i / 10f, u = 1f - t;
            f.add(u * u * sx + 2f * u * t * cx + t * t * ex);
            f.add(u * u * sy + 2f * u * t * cy + t * t * ey);
        }
        // left side mirrored, from the bottom point back up
        for (int i = 1; i <= 10; i++) {
            float t = 1f - i / 10f, u = 1f - t;
            float x = u * u * sx + 2f * u * t * cx + t * t * ex;
            f.add(SIZE - x);
            f.add(u * u * sy + 2f * u * t * cy + t * t * ey);
        }
        return f.toArray();
    }
}
