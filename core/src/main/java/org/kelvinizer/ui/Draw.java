package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import space.earlygrey.shapedrawer.ShapeDrawer;

import java.nio.ByteBuffer;

/**
 * 共用绘图服务：全游戏唯一的 {@link SpriteBatch}、
 * 程序生成的 1024² 图集（界面、背景和粒子的全部小图）、{@link ShapeDrawer} 和着色器用的噪声贴图。
 * 还提供矩形、圆、圆角矩形和面板等不分配内存的绘制方法；由 Main 持有，只释放一次。
 *
 * <p>Shared drawing service (DESIGN §1.6, §2.1, ARCHITECTURE §3): the one {@link SpriteBatch},
 * a procedurally generated, mipmapped 1024² atlas holding every UI / backdrop / particle sprite, the
 * {@link ShapeDrawer} bound to the atlas' {@code white} region, a separate 128² tiling
 * {@code noise} texture for shaders, and allocation-free rounded-rectangle helpers.
 *
 * <p>Context loss: the atlas and noise textures are uploaded from retained pixmaps as
 * <em>managed</em> textures, so libGDX re-uploads them automatically after an Android context
 * loss; every {@link TextureRegion} stays valid. {@link #resume()} is a hook Main calls from
 * {@code resume()} for completeness.
 *
 * <p>Owned by Main (never static); dispose once.
 */
public final class Draw implements Disposable {

    /** Atlas region names (DESIGN §2.1 plus a few helpers). */
    public static final String WHITE = "white", SOFT_DOT = "soft_dot", RING_SOFT = "ring_soft",
        RING_THIN = "ring_thin", STREAK = "streak", GLOW_V = "glow_v", SHAFT = "shaft",
        NOTE_9P = "note_9p", NOTE_GLOW = "note_glow", HOLD_EDGE = "hold_edge",
        PANEL_9P = "panel_9p", PANEL_STROKE_9P = "panel_stroke_9p", CHIP_9P = "chip_9p",
        PILL_9P = "pill_9p", VIGNETTE = "vignette", DISC = "disc",
        PANEL_STROKE2_9P = "panel_stroke2_9p", PANEL_STROKE3_9P = "panel_stroke3_9p",
        CHIP_STROKE2_9P = "chip_stroke2_9p", ROUND_LG_9P = "round_lg_9p",
        ROUND_LG_STROKE2_9P = "round_lg_stroke2_9p", ROUND_LG_STROKE3_9P = "round_lg_stroke3_9p";
    /**
     * Name prefix of the water-ring regions {@code ring_water0} … {@code ring_water10} (DESIGN §20.5):
     * the same soft ring, {@value #RING_WATER_FWHM} texels thick, at the radii of
     * {@link #ringWaterRadius}. A growing ring picks the level whose radius suits it, so its
     * stroke stays about the same width instead of growing with the sprite.
     */
    public static final String RING_WATER = "ring_water";
    /** Number of {@link #RING_WATER} levels. */
    public static final int RING_WATER_LODS = 11;
    /** Stroke of every {@link #RING_WATER} level (gaussian full width at half maximum, texels). */
    public static final float RING_WATER_FWHM = 3f;

    /** Radius of the base panel nine-slice. */
    public static final float PANEL_RADIUS = 18f;
    private static final int ATLAS_SIZE = 1024;
    /**
     * Gap between atlas regions: the atlas is mipmapped (thin rings stay continuous when minified
     * on ≤ 0.7 px/unit screens) and 8 px keeps neighbours apart down to mip level 3.
     */
    private static final int ATLAS_PADDING = 8;
    private static final int NOISE_SIZE = 128;
    private static final float RING_THIN_R = 0.48f;

    private final SpriteBatch batch;
    private final PixmapPacker packer;
    private final Texture atlasTexture;
    private final TextureAtlas atlas;
    private final Pixmap noisePixmap;
    private final Texture noise;
    private final ShapeDrawer shapes;
    private final ObjectMap<String, TextureRegion> regions = new ObjectMap<>();

    private final TextureRegion white, softDot, ringSoft, ringThin, streak, glowV, shaft, noteGlow,
        holdEdge, vignette, disc, whiteCore;
    private final TextureRegion[] ringWater = new TextureRegion[RING_WATER_LODS];
    private final NinePatch panel9, panelStroke9, chip9, pill9, note9;
    private final Nine ninePanel, nineChip, nineLarge;
    private final Nine[] panelStrokes = new Nine[3];
    private final Nine chipStroke2;
    private final Nine[] largeStrokes = new Nine[2];

    /** Reused vertex buffer for nine-slice quads (9 quads × 20 floats). */
    private final float[] verts = new float[9 * 20];
    private final Color tmp = new Color();
    private final Color tmp2 = new Color();

    /** Creates the batch, atlas, noise texture and shape drawer. Must run on the GL thread. */
    public Draw() {
        batch = new SpriteBatch(4000);
        packer = new PixmapPacker(ATLAS_SIZE, ATLAS_SIZE, Pixmap.Format.RGBA8888, ATLAS_PADDING, true,
            new PixmapPacker.GuillotineStrategy());
        // every sprite is white × alpha; white padding keeps the mip levels from darkening edges
        packer.setTransparentColor(new Color(1f, 1f, 1f, 0f));
        packAll();
        if (packer.getPages().size != 1) {
            throw new GdxRuntimeException("Atlas overflows one " + ATLAS_SIZE + "² page: " + packer.getPages().size);
        }
        Pixmap page = packer.getPages().first().getPixmap();
        atlasTexture = new Texture(new PixmapTextureData(page, Pixmap.Format.RGBA8888, true, false, true));
        atlasTexture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        atlas = new TextureAtlas();
        for (String name : NAMES) {
            PixmapPacker.PixmapPackerRectangle r = packer.getRect(name);
            if (r == null) throw new GdxRuntimeException("Atlas region not packed: " + name);
            TextureRegion region = atlas.addRegion(name, atlasTexture, r.getX(), r.getY(), r.getWidth(), r.getHeight());
            regions.put(name, region);
        }
        for (int i = 0; i < RING_WATER_LODS; i++) {
            PixmapPacker.PixmapPackerRectangle r = packer.getRect(RING_WATER + i);
            if (r == null) throw new GdxRuntimeException("Atlas region not packed: " + RING_WATER + i);
            ringWater[i] = atlas.addRegion(RING_WATER + i, atlasTexture, r.getX(), r.getY(),
                r.getWidth(), r.getHeight());
            regions.put(RING_WATER + i, ringWater[i]);
        }
        white = regions.get(WHITE);
        whiteCore = new TextureRegion(white, 1, 1, 2, 2);
        softDot = regions.get(SOFT_DOT);
        ringSoft = regions.get(RING_SOFT);
        ringThin = regions.get(RING_THIN);
        streak = regions.get(STREAK);
        glowV = regions.get(GLOW_V);
        shaft = regions.get(SHAFT);
        noteGlow = regions.get(NOTE_GLOW);
        holdEdge = regions.get(HOLD_EDGE);
        vignette = regions.get(VIGNETTE);
        disc = regions.get(DISC);

        panel9 = new NinePatch(regions.get(PANEL_9P), 18, 18, 18, 18);
        panelStroke9 = new NinePatch(regions.get(PANEL_STROKE_9P), 18, 18, 18, 18);
        chip9 = new NinePatch(regions.get(CHIP_9P), 12, 12, 12, 12);
        pill9 = new NinePatch(regions.get(PILL_9P), 16, 16, 15, 15);
        note9 = new NinePatch(regions.get(NOTE_9P), 12, 12, 8, 8);

        ninePanel = new Nine(regions.get(PANEL_9P), 18f);
        nineChip = new Nine(regions.get(CHIP_9P), 12f);
        nineLarge = new Nine(regions.get(ROUND_LG_9P), 48f);
        panelStrokes[0] = new Nine(regions.get(PANEL_STROKE_9P), 18f);
        panelStrokes[1] = new Nine(regions.get(PANEL_STROKE2_9P), 18f);
        panelStrokes[2] = new Nine(regions.get(PANEL_STROKE3_9P), 18f);
        chipStroke2 = new Nine(regions.get(CHIP_STROKE2_9P), 12f);
        largeStrokes[0] = new Nine(regions.get(ROUND_LG_STROKE2_9P), 48f);
        largeStrokes[1] = new Nine(regions.get(ROUND_LG_STROKE3_9P), 48f);

        noisePixmap = buildNoise();
        noise = new Texture(new PixmapTextureData(noisePixmap, Pixmap.Format.RGBA8888, false, false, true));
        noise.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        noise.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);

        shapes = new ShapeDrawer(batch, whiteCore);
    }

    // ------------------------------------------------------------------ accessors
    /** The shared SpriteBatch (do not dispose). */
    public SpriteBatch batch() {
        return batch;
    }

    /** ShapeDrawer bound to the shared batch and the atlas' white region. */
    public ShapeDrawer shapes() {
        return shapes;
    }

    /** The generated atlas (also used by particle effects). */
    public TextureAtlas atlas() {
        return atlas;
    }

    /** The atlas page texture. */
    public Texture atlasTexture() {
        return atlasTexture;
    }

    /** Region by name; throws for unknown names. */
    public TextureRegion region(String name) {
        TextureRegion r = regions.get(name);
        if (r == null) throw new GdxRuntimeException("Unknown atlas region: " + name);
        return r;
    }

    /** Solid white 4² region. */
    public TextureRegion white() {
        return white;
    }

    /** {@code soft_dot}: α = (1 − r)². */
    public TextureRegion softDot() {
        return softDot;
    }

    /** Alias of {@link #softDot()} (ARCHITECTURE "glow"). */
    public TextureRegion glow() {
        return softDot;
    }

    /** {@code ring_soft}: gaussian ring at r = 0.42. */
    public TextureRegion ringSoft() {
        return ringSoft;
    }

    /** Alias of {@link #ringSoft()} (ARCHITECTURE "ring"). */
    public TextureRegion ring() {
        return ringSoft;
    }

    /** {@code ring_thin}: 2 px anti-aliased ring of radius 0.48 × region size. */
    public TextureRegion ringThin() {
        return ringThin;
    }

    /**
     * {@code ring_water<lod>}: a soft ring {@value #RING_WATER_FWHM} texels thick, centred in the
     * region, of radius {@link #ringWaterRadius ringWaterRadius(lod)} texels (DESIGN §20.5).
     */
    public TextureRegion ringWater(int lod) {
        return ringWater[MathUtils.clamp(lod, 0, RING_WATER_LODS - 1)];
    }

    /** Ring radius of water-ring level {@code lod} in texels: 6 × 1.35^lod (6 … 121). */
    public static float ringWaterRadius(int lod) {
        return 6f * (float) Math.pow(1.35, MathUtils.clamp(lod, 0, RING_WATER_LODS - 1));
    }

    /** {@code streak}: capsule with a bright head on the right (+x). */
    public TextureRegion streak() {
        return streak;
    }

    /** {@code glow_v}: vertical gaussian, for line glows and lane columns. */
    public TextureRegion glowV() {
        return glowV;
    }

    /** {@code shaft}: horizontal gaussian × downward fade (LOW backdrop path). */
    public TextureRegion shaft() {
        return shaft;
    }

    /** {@code note_glow}: soft capsule glow. */
    public TextureRegion noteGlow() {
        return noteGlow;
    }

    /** {@code hold_edge}: horizontal edge gradient, bright on the left. */
    public TextureRegion holdEdge() {
        return holdEdge;
    }

    /** {@code vignette}: α = smoothstep(0.45, 1, r). */
    public TextureRegion vignette() {
        return vignette;
    }

    /** Anti-aliased filled disc (radius 0.484 × size). */
    public TextureRegion disc() {
        return disc;
    }

    /** The 128² RGBA tiling noise texture (REPEAT, linear). */
    public Texture noise() {
        return noise;
    }

    /** {@code panel_9p} (radius 18) as a NinePatch. */
    public NinePatch panel9() {
        return panel9;
    }

    /** {@code panel_stroke_9p} (radius 18, 1 px) as a NinePatch. */
    public NinePatch panelStroke9() {
        return panelStroke9;
    }

    /** {@code chip_9p} (radius 12) as a NinePatch. */
    public NinePatch chip9() {
        return chip9;
    }

    /** {@code pill_9p} (64×32, radius 16) as a NinePatch. */
    public NinePatch pill9() {
        return pill9;
    }

    /** {@code note_9p} (96×32, splits 12/12/8/8) as a NinePatch. */
    public NinePatch note9() {
        return note9;
    }

    /**
     * Context-restore hook (call from Main.resume). Textures here are managed and are
     * re-uploaded by libGDX itself; this only resets the batch colour and shape-drawer state.
     */
    public void resume() {
        batch.setColor(Color.WHITE);
        shapes.update();
    }

    // ------------------------------------------------------------------ drawing helpers
    /** Solid axis-aligned rectangle. */
    public void rect(float x, float y, float w, float h, Color c, float alpha) {
        float old = batch.getPackedColor();
        batch.setColor(c.r, c.g, c.b, c.a * alpha);
        batch.draw(whiteCore, x, y, w, h);
        batch.setPackedColor(old);
    }

    /** Rectangle with a vertical gradient from {@code bottom} to {@code top}. */
    public void rectGradientV(float x, float y, float w, float h, Color bottom, Color top, float alpha) {
        float cb = Color.toFloatBits(bottom.r, bottom.g, bottom.b, bottom.a * alpha);
        float ct = Color.toFloatBits(top.r, top.g, top.b, top.a * alpha);
        float u = 0.5f * (whiteCore.getU() + whiteCore.getU2());
        float v = 0.5f * (whiteCore.getV() + whiteCore.getV2());
        float[] q = verts;
        put(q, 0, x, y, cb, u, v);
        put(q, 5, x, y + h, ct, u, v);
        put(q, 10, x + w, y + h, ct, u, v);
        put(q, 15, x + w, y, cb, u, v);
        batch.draw(atlasTexture, q, 0, 20);
    }

    /**
     * Quad with vertical sides at {@code x0} and {@code x1}: bottom and top at {@code yb0} / {@code yt0} on the left
     * and {@code yb1} / {@code yt1} on the right, a vertical gradient from {@code bottom} to {@code top}. Strips of
     * these draw wavy edges (the DIVE water walls).
     */
    public void quadV(float x0, float yb0, float yt0, float x1, float yb1, float yt1, Color bottom, Color top,
                      float alpha) {
        float cb = Color.toFloatBits(bottom.r, bottom.g, bottom.b, bottom.a * alpha);
        float ct = Color.toFloatBits(top.r, top.g, top.b, top.a * alpha);
        float u = 0.5f * (whiteCore.getU() + whiteCore.getU2());
        float v = 0.5f * (whiteCore.getV() + whiteCore.getV2());
        float[] q = verts;
        put(q, 0, x0, yb0, cb, u, v);
        put(q, 5, x0, yt0, ct, u, v);
        put(q, 10, x1, yt1, ct, u, v);
        put(q, 15, x1, yb1, cb, u, v);
        batch.draw(atlasTexture, q, 0, 20);
    }

    /** Rectangle with a horizontal gradient from {@code left} to {@code right}. */
    public void rectGradientH(float x, float y, float w, float h, Color left, Color right, float alpha) {
        float cl = Color.toFloatBits(left.r, left.g, left.b, left.a * alpha);
        float cr = Color.toFloatBits(right.r, right.g, right.b, right.a * alpha);
        float u = 0.5f * (whiteCore.getU() + whiteCore.getU2());
        float v = 0.5f * (whiteCore.getV() + whiteCore.getV2());
        float[] q = verts;
        put(q, 0, x, y, cl, u, v);
        put(q, 5, x, y + h, cl, u, v);
        put(q, 10, x + w, y + h, cr, u, v);
        put(q, 15, x + w, y, cr, u, v);
        batch.draw(atlasTexture, q, 0, 20);
    }

    /** Sprite centred at (cx, cy) with size w×h, tinted. */
    public void sprite(TextureRegion r, float cx, float cy, float w, float h, Color c, float alpha) {
        float old = batch.getPackedColor();
        batch.setColor(c.r, c.g, c.b, c.a * alpha);
        batch.draw(r, cx - w * 0.5f, cy - h * 0.5f, w, h);
        batch.setPackedColor(old);
    }

    /** Sprite centred at (cx, cy), rotated by {@code degrees} about its centre, tinted. */
    public void sprite(TextureRegion r, float cx, float cy, float w, float h, float degrees, Color c, float alpha) {
        float old = batch.getPackedColor();
        batch.setColor(c.r, c.g, c.b, c.a * alpha);
        batch.draw(r, cx - w * 0.5f, cy - h * 0.5f, w * 0.5f, h * 0.5f, w, h, 1f, 1f, degrees);
        batch.setPackedColor(old);
    }

    /** Anti-aliased filled disc of radius {@code radius}. */
    public void disc(float cx, float cy, float radius, Color c, float alpha) {
        float s = radius / 0.484f;
        sprite(disc, cx, cy, s, s, c, alpha);
    }

    /** {@code ring_thin} sprite sized so the ring has radius {@code radius}. */
    public void ringSprite(float cx, float cy, float radius, Color c, float alpha) {
        float s = radius / RING_THIN_R;
        sprite(ringThin, cx, cy, s, s, c, alpha);
    }

    /** Soft glow dot ({@code soft_dot}) of diameter {@code size}. */
    public void glowDot(float cx, float cy, float size, Color c, float alpha) {
        sprite(softDot, cx, cy, size, size, c, alpha);
    }

    /** Stroked circle drawn with the ShapeDrawer. */
    public void circle(float cx, float cy, float radius, float width, Color c, float alpha) {
        float old = shapes.getPackedColor();
        shapes.setColor(c.r, c.g, c.b, c.a * alpha);
        shapes.arc(cx, cy, radius, 0f, MathUtils.PI2, width, false, sides(radius));
        shapes.setColor(old);
    }

    /**
     * Stroked arc drawn with the ShapeDrawer: {@code radians} counter-clockwise from
     * {@code start} (a negative sweep runs clockwise).
     */
    public void arc(float cx, float cy, float radius, float start, float radians, float width, Color c, float alpha) {
        if (radians < 0f) {
            start += radians;
            radians = -radians;
        }
        if (radians <= 0f) return;
        float old = shapes.getPackedColor();
        shapes.setColor(c.r, c.g, c.b, c.a * alpha);
        shapes.arc(cx, cy, radius, start, radians, width, false, sides(radius));
        shapes.setColor(old);
    }

    /**
     * ShapeDrawer sides for a circle of {@code radius}: one every 6 units, 24–128. The ShapeDrawer counts
     * sides per whole circle even for an arc, so a short arc gets its share of them, not all of them.
     */
    private static int sides(float radius) {
        return MathUtils.clamp((int) (radius * MathUtils.PI2 / 6f), 24, 128);
    }

    /**
     * Anti-aliased stroked arc for the map's rings: {@code radians} counter-clockwise from {@code start}
     * (a negative sweep runs clockwise), cut finely enough to stay round on screen, its pieces joined
     * edge to edge and both rims feathered over one screen pixel ({@code px}, world units per pixel).
     */
    public void ringArc(float cx, float cy, float radius, float start, float radians, float width, float px,
                        Color c, float alpha) {
        if (radians < 0f) {
            start += radians;
            radians = -radians;
        }
        radians = Math.min(radians, MathUtils.PI2);
        if (radians <= 0f || width <= 0f || alpha <= 0f) return;
        px = Math.max(px, 1e-3f);
        // a ring thinner than a pixel keeps a pixel's width and fades instead of breaking up
        float solid = Color.toFloatBits(c.r, c.g, c.b, c.a * alpha * Math.min(1f, width / px));
        float clear = Color.toFloatBits(c.r, c.g, c.b, 0f);
        float half = 0.5f * Math.max(width, px), f = 0.5f * px;
        float r0 = radius - half - f, r1 = radius - half + f, r2 = radius + half - f, r3 = radius + half + f;
        // each chord stays within 0.2 px of the circle
        int n = MathUtils.clamp(MathUtils.ceil(radians * (float) Math.sqrt(radius / (1.6f * px))), 1,
            MathUtils.ceil(radians * 160f / MathUtils.PI2));
        float u = 0.5f * (whiteCore.getU() + whiteCore.getU2());
        float v = 0.5f * (whiteCore.getV() + whiteCore.getV2());
        float[] q = verts;
        float ca = (float) Math.cos(start), sa = (float) Math.sin(start);
        for (int i = 1; i <= n; i++) {
            float t = start + radians * i / n;
            float cb = (float) Math.cos(t), sb = (float) Math.sin(t);
            put(q, 0, cx + r0 * ca, cy + r0 * sa, clear, u, v);
            put(q, 5, cx + r1 * ca, cy + r1 * sa, solid, u, v);
            put(q, 10, cx + r1 * cb, cy + r1 * sb, solid, u, v);
            put(q, 15, cx + r0 * cb, cy + r0 * sb, clear, u, v);
            put(q, 20, cx + r1 * ca, cy + r1 * sa, solid, u, v);
            put(q, 25, cx + r2 * ca, cy + r2 * sa, solid, u, v);
            put(q, 30, cx + r2 * cb, cy + r2 * sb, solid, u, v);
            put(q, 35, cx + r1 * cb, cy + r1 * sb, solid, u, v);
            put(q, 40, cx + r2 * ca, cy + r2 * sa, solid, u, v);
            put(q, 45, cx + r3 * ca, cy + r3 * sa, clear, u, v);
            put(q, 50, cx + r3 * cb, cy + r3 * sb, clear, u, v);
            put(q, 55, cx + r2 * cb, cy + r2 * sb, solid, u, v);
            batch.draw(atlasTexture, q, 0, 60);
            ca = cb;
            sa = sb;
        }
    }

    /** Scratch for {@link #convexFill} and {@link #convexStroke}: four offset copies of up to 8 corners. */
    private final float[] rings = new float[4 * 16];

    /**
     * Anti-aliased filled convex polygon of {@code n} (3–8) corners, counter-clockwise in {@code xy}: its edges
     * feathered over one screen pixel ({@code px}, world units per pixel) as {@link #ringArc} feathers its rims.
     */
    public void convexFill(float[] xy, int n, Color c, float alpha, float px) {
        if (alpha <= 0f || n < 3) return;
        float f = 0.5f * Math.max(px, 1e-3f);
        offset(xy, n, -f, rings, 0);
        offset(xy, n, f, rings, 16);
        float solid = Color.toFloatBits(c.r, c.g, c.b, c.a * alpha);
        float clear = Color.toFloatBits(c.r, c.g, c.b, 0f);
        float u = 0.5f * (whiteCore.getU() + whiteCore.getU2());
        float v = 0.5f * (whiteCore.getV() + whiteCore.getV2());
        float[] q = verts;
        // the inner polygon as a fan of quads from corner 0 (the last folds into a triangle when n is odd)
        for (int i = 1; i + 1 < n; i += 2) {
            int j = Math.min(i + 2, n - 1);
            put(q, 0, rings[0], rings[1], solid, u, v);
            put(q, 5, rings[2 * i], rings[2 * i + 1], solid, u, v);
            put(q, 10, rings[2 * i + 2], rings[2 * i + 3], solid, u, v);
            put(q, 15, rings[2 * j], rings[2 * j + 1], solid, u, v);
            batch.draw(atlasTexture, q, 0, 20);
        }
        for (int i = 0; i < n; i++) band(2 * i, 2 * ((i + 1) % n), 0, 16, solid, clear, u, v);
    }

    /**
     * Anti-aliased outline of a convex polygon ({@code n} ≤ 8 corners, counter-clockwise in {@code xy}): a band
     * {@code width} wide centred on its edges, mitred at the corners, both rims feathered over one screen pixel.
     */
    public void convexStroke(float[] xy, int n, float width, Color c, float alpha, float px) {
        if (alpha <= 0f || width <= 0f || n < 3) return;
        px = Math.max(px, 1e-3f);
        // a stroke thinner than a pixel keeps a pixel's width and fades instead (as in ringArc)
        float half = 0.5f * Math.max(width, px), f = 0.5f * px;
        offset(xy, n, -half - f, rings, 0);
        offset(xy, n, -half + f, rings, 16);
        offset(xy, n, half - f, rings, 32);
        offset(xy, n, half + f, rings, 48);
        float solid = Color.toFloatBits(c.r, c.g, c.b, c.a * alpha * Math.min(1f, width / px));
        float clear = Color.toFloatBits(c.r, c.g, c.b, 0f);
        float u = 0.5f * (whiteCore.getU() + whiteCore.getU2());
        float v = 0.5f * (whiteCore.getV() + whiteCore.getV2());
        for (int i = 0; i < n; i++) {
            int a = 2 * i, b = 2 * ((i + 1) % n);
            band(a, b, 0, 16, clear, solid, u, v);
            band(a, b, 16, 32, solid, solid, u, v);
            band(a, b, 32, 48, solid, clear, u, v);
        }
    }

    /** One quad of {@link #rings} between the offset copies at {@code in} and {@code out}, along the edge a → b. */
    private void band(int a, int b, int in, int out, float cin, float cout, float u, float v) {
        float[] q = verts, r = rings;
        put(q, 0, r[in + a], r[in + a + 1], cin, u, v);
        put(q, 5, r[out + a], r[out + a + 1], cout, u, v);
        put(q, 10, r[out + b], r[out + b + 1], cout, u, v);
        put(q, 15, r[in + b], r[in + b + 1], cin, u, v);
        batch.draw(atlasTexture, q, 0, 20);
    }

    /**
     * The corners of the convex counter-clockwise polygon {@code xy} moved {@code d} outward (inward when negative)
     * along both neighbouring edges' normals, a mitred offset, into {@code out} from {@code at}.
     */
    private static void offset(float[] xy, int n, float d, float[] out, int at) {
        for (int i = 0; i < n; i++) {
            int p = (i + n - 1) % n, nx = (i + 1) % n;
            float ax = xy[2 * i] - xy[2 * p], ay = xy[2 * i + 1] - xy[2 * p + 1];
            float bx = xy[2 * nx] - xy[2 * i], by = xy[2 * nx + 1] - xy[2 * i + 1];
            float la = (float) Math.sqrt(ax * ax + ay * ay), lb = (float) Math.sqrt(bx * bx + by * by);
            // outward normals of the edges into and out of corner i (counter-clockwise: (dy, -dx))
            float n1x = ay / la, n1y = -ax / la, n2x = by / lb, n2y = -bx / lb;
            float s = d / (1f + n1x * n2x + n1y * n2y);
            out[at + 2 * i] = xy[2 * i] + (n1x + n2x) * s;
            out[at + 2 * i + 1] = xy[2 * i + 1] + (n1y + n2y) * s;
        }
    }

    /** Straight line drawn with the ShapeDrawer. */
    public void line(float x1, float y1, float x2, float y2, float width, Color c, float alpha) {
        float old = shapes.getPackedColor();
        shapes.setColor(c.r, c.g, c.b, c.a * alpha);
        shapes.line(x1, y1, x2, y2, width);
        shapes.setColor(old);
    }

    /** Filled rounded rectangle (anti-aliased corners), radius in world units. */
    public void roundRect(float x, float y, float w, float h, float radius, Color c, float alpha) {
        float cc = Color.toFloatBits(c.r, c.g, c.b, c.a * alpha);
        nine(pickFill(radius), x, y, w, h, radius, cc, cc);
    }

    /** Filled rounded rectangle with a vertical gradient. */
    public void roundRectGradient(float x, float y, float w, float h, float radius, Color bottom,
                                  Color top, float alpha) {
        float cb = Color.toFloatBits(bottom.r, bottom.g, bottom.b, bottom.a * alpha);
        float ct = Color.toFloatBits(top.r, top.g, top.b, top.a * alpha);
        nine(pickFill(radius), x, y, w, h, radius, cb, ct);
    }

    /** Stroked rounded rectangle; the stroke lies inside the rectangle. */
    public void roundRectStroke(float x, float y, float w, float h, float radius, float width, Color c, float alpha) {
        float cc = Color.toFloatBits(c.r, c.g, c.b, c.a * alpha);
        nine(pickStroke(radius, width), x, y, w, h, radius, cc, cc);
    }

    /**
     * Standard panel (DESIGN §1.6): {@code panel} fill, 1 px {@code stroke} border and a 1 px top
     * highlight, all multiplied by {@code alpha}.
     */
    public void panel(float x, float y, float w, float h, float radius, float alpha) {
        roundRect(x, y, w, h, radius, Theme.PANEL, alpha);
        roundRectStroke(x, y, w, h, radius, 1f, Theme.STROKE, alpha);
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        rect(x + r, y + h - 2f, w - 2f * r, 1f, Theme.PANEL_HIGHLIGHT, alpha);
    }

    /** Pill (fully rounded rectangle) fill. */
    public void pill(float x, float y, float w, float h, Color c, float alpha) {
        roundRect(x, y, w, h, Math.min(w, h) * 0.5f, c, alpha);
    }

    /**
     * The shell (贝壳) icon, a scallop {@code size} tall centred on (cx, cy): a fan of seven
     * scalloped lobes over a small hinge, its ribs cut in {@code rib} (usually the colour behind it).
     */
    public void shell(float cx, float cy, float size, Color fill, Color rib, float alpha) {
        float r = size * 0.46f, hy = cy - size * 0.30f;
        float a0 = MathUtils.PI * 0.08f, sweep = MathUtils.PI * 0.84f;
        float old = shapes.getPackedColor();
        shapes.setColor(fill.r, fill.g, fill.b, fill.a * alpha);
        shapes.sector(cx, hy, r * 0.93f, a0, sweep, 24);
        // the hinge ears: a trapezoid under the fan
        float e0 = size * 0.20f, e1 = size * 0.08f, eb = hy - size * 0.10f, et = hy + size * 0.04f;
        shapes.filledTriangle(cx - e0, eb, cx + e0, eb, cx + e1, et);
        shapes.filledTriangle(cx - e0, eb, cx + e1, et, cx - e1, et);
        shapes.setColor(old);
        // the scalloped rim: one lobe between each pair of ribs
        for (int i = 0; i < 7; i++) {
            float a = a0 + sweep * (i + 0.5f) / 7f;
            disc(cx + MathUtils.cos(a) * r * 0.91f, hy + MathUtils.sin(a) * r * 0.91f, r * 0.16f, fill, alpha);
        }
        float w = Math.max(1f, size * 0.05f);
        for (int i = 1; i < 7; i++) {
            float a = a0 + sweep * i / 7f;
            line(cx, hy, cx + MathUtils.cos(a) * r * 0.98f, hy + MathUtils.sin(a) * r * 0.98f, w, rib, alpha);
        }
        line(cx - e0 * 0.9f, hy - size * 0.03f, cx + e0 * 0.9f, hy - size * 0.03f, w, rib, alpha);
    }

    private Nine pickFill(float radius) {
        if (radius <= 14f) return nineChip;
        if (radius <= 30f) return ninePanel;
        return nineLarge;
    }

    private Nine pickStroke(float radius, float width) {
        if (radius > 30f) {
            float k = radius / 48f;
            return width / k >= 2.5f ? largeStrokes[1] : largeStrokes[0];
        }
        if (radius <= 14f) {
            float k = radius / 12f;
            if (Math.abs(width / k - 2f) < 0.75f) return chipStroke2;
        }
        float k = radius / 18f;
        float base = width / Math.max(0.01f, k);
        if (base < 1.5f) return panelStrokes[0];
        if (base < 2.5f) return panelStrokes[1];
        return panelStrokes[2];
    }

    /** Draws a nine-slice with corner radius {@code radius} and a vertical colour gradient. */
    private void nine(Nine n, float x, float y, float w, float h, float radius, float cBottom, float cTop) {
        if (w <= 0f || h <= 0f) return;
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        if (r < 0.5f) r = Math.min(0.5f, Math.min(w, h) * 0.5f);
        TextureRegion reg = n.region;
        float u0 = reg.getU(), u3 = reg.getU2();
        float vTop = reg.getV(), vBot = reg.getV2();
        float uSplit = (u3 - u0) * (n.split / reg.getRegionWidth());
        float vSplit = (vBot - vTop) * (n.split / reg.getRegionHeight());
        float u1 = u0 + uSplit, u2 = u3 - uSplit;
        float v1 = vBot - vSplit, v2 = vTop + vSplit; // v1: bottom split, v2: top split
        float x0 = x, x1 = x + r, x2 = x + w - r, x3 = x + w;
        float y0 = y, y1 = y + r, y2 = y + h - r, y3 = y + h;
        float c0 = cBottom, c3 = cTop;
        float c1 = cBottom == cTop ? cBottom : lerpPacked(cBottom, cTop, r / h);
        float c2 = cBottom == cTop ? cBottom : lerpPacked(cBottom, cTop, (h - r) / h);
        int i = 0;
        // bottom row
        i = quad(i, x0, y0, x1, y1, u0, vBot, u1, v1, c0, c1);
        i = quad(i, x1, y0, x2, y1, u1, vBot, u2, v1, c0, c1);
        i = quad(i, x2, y0, x3, y1, u2, vBot, u3, v1, c0, c1);
        // middle row
        if (y2 > y1) {
            i = quad(i, x0, y1, x1, y2, u0, v1, u1, v2, c1, c2);
            i = quad(i, x1, y1, x2, y2, u1, v1, u2, v2, c1, c2);
            i = quad(i, x2, y1, x3, y2, u2, v1, u3, v2, c1, c2);
        }
        // top row
        i = quad(i, x0, y2, x1, y3, u0, v2, u1, vTop, c2, c3);
        i = quad(i, x1, y2, x2, y3, u1, v2, u2, vTop, c2, c3);
        i = quad(i, x2, y2, x3, y3, u2, v2, u3, vTop, c2, c3);
        batch.draw(atlasTexture, verts, 0, i);
    }

    private int quad(int i, float xa, float ya, float xb, float yb, float ua, float vb, float ub, float vt,
                     float cBottom, float cTop) {
        if (xb <= xa) return i;
        float[] q = verts;
        put(q, i, xa, ya, cBottom, ua, vb);
        put(q, i + 5, xa, yb, cTop, ua, vt);
        put(q, i + 10, xb, yb, cTop, ub, vt);
        put(q, i + 15, xb, ya, cBottom, ub, vb);
        return i + 20;
    }

    private static void put(float[] q, int i, float x, float y, float c, float u, float v) {
        q[i] = x;
        q[i + 1] = y;
        q[i + 2] = c;
        q[i + 3] = u;
        q[i + 4] = v;
    }

    private float lerpPacked(float a, float b, float t) {
        Color.abgr8888ToColor(tmp, a);
        Color.abgr8888ToColor(tmp2, b);
        return Color.toFloatBits(tmp.r + (tmp2.r - tmp.r) * t, tmp.g + (tmp2.g - tmp.g) * t,
            tmp.b + (tmp2.b - tmp.b) * t, tmp.a + (tmp2.a - tmp.a) * t);
    }

    @Override
    public void dispose() {
        batch.dispose();
        atlas.dispose(); // disposes atlasTexture
        packer.dispose(); // disposes the retained page pixmap
        noise.dispose();
        noisePixmap.dispose();
    }

    /** Nine-slice source description: a region whose corner radius equals its split size. */
    private static final class Nine {
        final TextureRegion region;
        final float split;

        Nine(TextureRegion region, float radius) {
            this.region = region;
            this.split = radius;
        }
    }

    // ================================================================== generation
    private static final String[] NAMES = {
        WHITE, SOFT_DOT, RING_SOFT, RING_THIN, STREAK, GLOW_V, SHAFT, NOTE_9P, NOTE_GLOW, HOLD_EDGE,
        PANEL_9P, PANEL_STROKE_9P, CHIP_9P, PILL_9P, VIGNETTE, DISC, PANEL_STROKE2_9P, PANEL_STROKE3_9P,
        CHIP_STROKE2_9P, ROUND_LG_9P, ROUND_LG_STROKE2_9P, ROUND_LG_STROKE3_9P};

    /** Per-pixel generator returning RGBA8888 for pixel centre (px, py), py measured downward. */
    private interface Gen {
        int rgba(float px, float py, int w, int h);
    }

    private void packAll() {
        pack(WHITE, 4, 4, (px, py, w, h) -> 0xFFFFFFFF);
        pack(SOFT_DOT, 64, 64, (px, py, w, h) -> {
            float r = radial(px, py, w, h);
            float a = r >= 1f ? 0f : (1f - r) * (1f - r);
            return white(a);
        });
        pack(RING_SOFT, 128, 128, (px, py, w, h) -> {
            float r = radial(px, py, w, h);
            float d = (r - 0.42f) / 0.07f;
            float a = (float) Math.exp(-d * d);
            if (r > 0.95f) a *= Math.max(0f, (1f - r) / 0.05f);
            return white(a);
        });
        pack(RING_THIN, 256, 256, (px, py, w, h) -> {
            float dx = px - w * 0.5f, dy = py - h * 0.5f;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float rr = RING_THIN_R * w;
            float a = MathUtils.clamp(1.5f - Math.abs(d - rr), 0f, 1f); // 2 px wide, 1 px AA each side
            return white(a);
        });
        pack(STREAK, 64, 16, (px, py, w, h) -> {
            float hr = h * 0.5f;
            float cx = MathUtils.clamp(px, hr, w - hr);
            float dx = px - cx, dy = py - hr;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            float edge = MathUtils.clamp((hr - d) / hr, 0f, 1f);
            float ramp = px / w;
            float a = (float) Math.pow(edge, 1.2) * ramp * ramp;
            return white(a);
        });
        pack(GLOW_V, 4, 128, (px, py, w, h) -> {
            float t = (py - h * 0.5f) / (h * 0.5f);
            float a = (float) Math.exp(-t * t * 4.5f);
            if (Math.abs(t) > 0.92f) a *= Math.max(0f, (1f - Math.abs(t)) / 0.08f);
            return white(a);
        });
        pack(SHAFT, 64, 256, (px, py, w, h) -> {
            float t = (px - w * 0.5f) / (w * 0.5f);
            float g = (float) Math.exp(-t * t * 3.5f);
            if (Math.abs(t) > 0.9f) g *= Math.max(0f, (1f - Math.abs(t)) / 0.1f);
            float v = py / h; // 0 at top
            float fade = (float) Math.pow(1f - v, 1.6);
            float top = MathUtils.clamp(v / 0.06f, 0f, 1f);
            return white(g * fade * top);
        });
        pack(NOTE_9P, 96, 32, (px, py, w, h) -> {
            float d = sdRoundRect(px, py, w, h, 8f);
            float cov = MathUtils.clamp(0.5f - d, 0f, 1f);
            if (cov <= 0f) return 0;
            float yUp = h - py; // distance from the bottom (leading edge)
            float a = 0.92f + (0.55f - 0.92f) * MathUtils.clamp((yUp - 3f) / (h - 6f), 0f, 1f);
            int rgb = 0xFFFFFF;
            if (yUp <= 3f) a = 1f;
            if (d > -2f) { // 2 px dark rim
                float rim = MathUtils.clamp(d + 2f, 0f, 1f);
                float r = lerpC(1f, 0x0B / 255f, rim), g = lerpC(1f, 0x10 / 255f, rim), b = lerpC(1f, 0x30 / 255f, rim);
                a = lerpC(a, 0.6f, rim);
                return rgba(r, g, b, a * cov);
            }
            return (rgb << 8) | (int) (MathUtils.clamp(a * cov, 0f, 1f) * 255f + 0.5f);
        });
        pack(NOTE_GLOW, 128, 48, (px, py, w, h) -> {
            float d = sdRoundRect(px, py, w, h, h * 0.5f);
            float inner = -h * 0.5f;
            float t = MathUtils.clamp((d - inner * 0.35f) / (-inner * 1.35f), 0f, 1f);
            float a = (1f - t);
            a = a * a;
            return white(a);
        });
        pack(HOLD_EDGE, 16, 64, (px, py, w, h) -> {
            float t = px / w;
            float a = (1f - t) * (1f - t);
            return white(a);
        });
        pack(PANEL_9P, 48, 48, (px, py, w, h) -> white(MathUtils.clamp(0.5f - sdRoundRect(px, py, w, h, 18f), 0f, 1f)));
        pack(PANEL_STROKE_9P, 48, 48, (px, py, w, h) -> white(stroke(sdRoundRect(px, py, w, h, 18f), 1f)));
        pack(PANEL_STROKE2_9P, 48, 48, (px, py, w, h) -> white(stroke(sdRoundRect(px, py, w, h, 18f), 2f)));
        pack(PANEL_STROKE3_9P, 48, 48, (px, py, w, h) -> white(stroke(sdRoundRect(px, py, w, h, 18f), 3f)));
        pack(CHIP_9P, 40, 40, (px, py, w, h) -> white(MathUtils.clamp(0.5f - sdRoundRect(px, py, w, h, 12f), 0f, 1f)));
        pack(CHIP_STROKE2_9P, 40, 40, (px, py, w, h) -> white(stroke(sdRoundRect(px, py, w, h, 12f), 2f)));
        pack(PILL_9P, 64, 32, (px, py, w, h) -> white(MathUtils.clamp(0.5f - sdRoundRect(px, py, w, h, 16f), 0f, 1f)));
        pack(ROUND_LG_9P, 112, 112,
            (px, py, w, h) -> white(MathUtils.clamp(0.5f - sdRoundRect(px, py, w, h, 48f), 0f, 1f)));
        pack(ROUND_LG_STROKE2_9P, 112, 112, (px, py, w, h) -> white(stroke(sdRoundRect(px, py, w, h, 48f), 2f)));
        pack(ROUND_LG_STROKE3_9P, 112, 112, (px, py, w, h) -> white(stroke(sdRoundRect(px, py, w, h, 48f), 3f)));
        pack(VIGNETTE, 256, 256, (px, py, w, h) -> {
            float dx = (px - w * 0.5f) / (w * 0.5f), dy = (py - h * 0.5f) / (h * 0.5f);
            float r = (float) Math.sqrt(dx * dx + dy * dy);
            float t = MathUtils.clamp((r - 0.45f) / 0.55f, 0f, 1f);
            return white(t * t * (3f - 2f * t));
        });
        pack(DISC, 128, 128, (px, py, w, h) -> {
            float dx = px - w * 0.5f, dy = py - h * 0.5f;
            float d = (float) Math.sqrt(dx * dx + dy * dy) - 0.484f * w;
            return white(MathUtils.clamp(0.5f - d, 0f, 1f));
        });
        // water-ring levels: gaussian stroke, 6 texels of margin (> 4σ), sizes kept to multiples of 4
        float sigma = RING_WATER_FWHM / 2.3548f;
        for (int i = 0; i < RING_WATER_LODS; i++) {
            final float rr = ringWaterRadius(i);
            int size = 4 * (int) Math.ceil((2f * rr + 12f) / 4f);
            pack(RING_WATER + i, size, size, (px, py, w, h) -> {
                float dx = px - w * 0.5f, dy = py - h * 0.5f;
                float d = ((float) Math.sqrt(dx * dx + dy * dy) - rr) / sigma;
                return white((float) Math.exp(-0.5f * d * d));
            });
        }
    }

    private void pack(String name, int w, int h, Gen gen) {
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        ByteBuffer buf = p.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = gen.rgba(x + 0.5f, y + 0.5f, w, h);
                int i = (y * w + x) * 4;
                buf.put(i, (byte) (c >>> 24));
                buf.put(i + 1, (byte) (c >>> 16));
                buf.put(i + 2, (byte) (c >>> 8));
                buf.put(i + 3, (byte) c);
            }
        }
        packer.pack(name, p);
        p.dispose();
    }

    /** Normalised radial distance (0 centre, 1 at the half-size). */
    private static float radial(float px, float py, int w, int h) {
        float dx = (px - w * 0.5f) / (w * 0.5f), dy = (py - h * 0.5f) / (h * 0.5f);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Signed distance to a rounded rectangle filling [0,w]×[0,h] with corner radius r. */
    private static float sdRoundRect(float px, float py, float w, float h, float r) {
        float qx = Math.abs(px - w * 0.5f) - (w * 0.5f - r);
        float qy = Math.abs(py - h * 0.5f) - (h * 0.5f - r);
        float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f);
        return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0f) - r;
    }

    /** Coverage of an inside stroke of width {@code s} for signed distance {@code d}. */
    private static float stroke(float d, float s) {
        float outer = MathUtils.clamp(0.5f - d, 0f, 1f);
        float inner = MathUtils.clamp(0.5f - (d + s), 0f, 1f);
        return outer - inner;
    }

    private static int white(float a) {
        return 0xFFFFFF00 | (int) (MathUtils.clamp(a, 0f, 1f) * 255f + 0.5f);
    }

    private static int rgba(float r, float g, float b, float a) {
        return ((int) (MathUtils.clamp(r, 0f, 1f) * 255f + 0.5f) << 24)
            | ((int) (MathUtils.clamp(g, 0f, 1f) * 255f + 0.5f) << 16)
            | ((int) (MathUtils.clamp(b, 0f, 1f) * 255f + 0.5f) << 8)
            | (int) (MathUtils.clamp(a, 0f, 1f) * 255f + 0.5f);
    }

    private static float lerpC(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ------------------------------------------------------------------ noise texture
    /**
     * 128² RGBA tiling noise: R/G = value noise (8 and 16 cells), B = smooth caustic noise,
     * A = high-passed (blue-noise-like) grain.
     */
    private static Pixmap buildNoise() {
        int n = NOISE_SIZE;
        Pixmap p = new Pixmap(n, n, Pixmap.Format.RGBA8888);
        p.setBlending(Pixmap.Blending.None);
        float[] white = new float[n * n];
        for (int i = 0; i < white.length; i++) white[i] = Anim.hash01(i, 977);
        ByteBuffer buf = p.getPixels();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float r = valueNoise(x, y, n, 8, 11) * 0.7f + valueNoise(x, y, n, 32, 12) * 0.3f;
                float g = valueNoise(x, y, n, 16, 21) * 0.7f + valueNoise(x, y, n, 64, 22) * 0.3f;
                float b = valueNoise(x, y, n, 4, 31) * 0.65f + valueNoise(x, y, n, 8, 32) * 0.35f;
                b = 0.5f + (b - 0.5f) * 2.2f; // stretch contrast: thinner caustic filaments
                // high-pass the white noise against its 3×3 neighbourhood (wrapped)
                float sum = 0f;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        sum += white[((y + oy + n) % n) * n + (x + ox + n) % n];
                    }
                }
                float a = MathUtils.clamp(0.5f + (white[y * n + x] - sum / 9f) * 1.6f, 0f, 1f);
                int i = (y * n + x) * 4;
                buf.put(i, (byte) (int) (MathUtils.clamp(r, 0f, 1f) * 255f));
                buf.put(i + 1, (byte) (int) (MathUtils.clamp(g, 0f, 1f) * 255f));
                buf.put(i + 2, (byte) (int) (MathUtils.clamp(b, 0f, 1f) * 255f));
                buf.put(i + 3, (byte) (int) (a * 255f));
            }
        }
        return p;
    }

    /** Tileable value noise with {@code cells} lattice cells across {@code size} pixels. */
    private static float valueNoise(int x, int y, int size, int cells, int seed) {
        float cs = size / (float) cells;
        float fx = (x + 0.5f) / cs, fy = (y + 0.5f) / cs;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        tx = tx * tx * (3f - 2f * tx);
        ty = ty * ty * (3f - 2f * ty);
        int xa = ((x0 % cells) + cells) % cells, xb = (xa + 1) % cells;
        int ya = ((y0 % cells) + cells) % cells, yb = (ya + 1) % cells;
        float v00 = Anim.hash01(xa * 131 + ya * 7919, seed);
        float v10 = Anim.hash01(xb * 131 + ya * 7919, seed);
        float v01 = Anim.hash01(xa * 131 + yb * 7919, seed);
        float v11 = Anim.hash01(xb * 131 + yb * 7919, seed);
        float a = v00 + (v10 - v00) * tx;
        float b = v01 + (v11 - v01) * tx;
        return a + (b - a) * ty;
    }
}
