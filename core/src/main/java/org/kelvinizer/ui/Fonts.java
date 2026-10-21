package org.kelvinizer.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.ObjectMap;

import java.nio.IntBuffer;

/**
 * 字体服务：按角色（DISPLAY、NUMBER、UI、UI_BOLD 等）和字高（世界单位）取字体，
 * 按屏幕实际像素栅格化并缓存，任何分辨率下都清晰。
 * 角色字体里没有的字（如中文）自动换用中文字体 Noto Sans SC；小字号共用一张贴图页，减少绘制调用。
 *
 * <p>Font service (replaces FontSystem; DESIGN §1.3, ARCHITECTURE §3, Appendix A.2).
 *
 * <p><b>Units.</b> Every size is a <em>cap height in world units</em> ({@code refPx}). Fonts are
 * rasterised at the real pixel size ({@code refPx × Ui.pxPerWorld()}, quantised into buckets)
 * and drawn in world units by setting the font data scale, so text is crisp at any resolution.
 *
 * <p><b>Vertical anchor.</b> {@code y} is the <em>baseline</em> by default (or with
 * {@link Align#bottom}). Pass {@link Align#top} to anchor the cap top instead, or
 * {@link #VCENTER} to centre the cap height on {@code y}. Horizontal anchoring follows
 * {@link Align#left}/{@link Align#right} (default: centred).
 *
 * <p><b>Colour.</b> Glyphs are baked white and tinted per draw; shared fonts are never left
 * mutated. Strings containing any char above U+024F (other than the shared UI symbols
 * {@code ·•—…–→←↑↓★☆♪×÷±%°μ} that the role font has) or a Latin char missing from the role font
 * are routed automatically to the CJK font at the same cap height. The CJK font (Noto Sans SC,
 * GB2312 subset) also carries Latin-1 and those symbols, so a mixed name such as
 * {@code Élodie 潜水员} draws completely. The Mokoto faces have no en dash; they draw {@code –}
 * with their hyphen.
 *
 * <p><b>Caching.</b> One font per (role, glyph set, pixel bucket); the cache is cleared when
 * {@link #onResize()} sees the pixel scale change by more than 10 %. Never keep a
 * {@link BitmapFont} returned by {@link #get} across frames: rasters on pages of their own are
 * freed again (below).
 *
 * <p><b>Texture pages (DESIGN §14).</b> Every small raster (estimated glyph area at most
 * {@value #SHARED_MAX_AREA} px²: the menu and HUD sizes, i.e. Chakra up to a cap of about 50 px
 * and the Mokoto digit, word and grade sets up to about 190 px) is packed into one shared 2048²
 * page, so text of different roles and sizes batches into a single draw call. Larger rasters keep
 * a page of their own, sized from the same estimate (the smallest power-of-two square, or half
 * of one, that holds it at {@value #OWN_FILL} fill; FreeType's default would pad most of them to
 * 1024²), and the incremental CJK font has its own shared packer. The shared page
 * stops accepting rasters once their estimated area reaches 3/4 of it, so it never keeps a second
 * page: a raster that spills over anyway is moved to a page of its own and closes the shared
 * page. Generating a raster into the shared page re-uploads that page: prewarm rasters while
 * loading ({@link #prewarm}), never in the middle of a song.
 *
 * <p><b>Trimming.</b> Main opens a new screen epoch ({@link #beginScreen}) before the next screen
 * prepares or shows, and calls {@link #trim} after that screen's first frame. Whenever the font
 * textures exceed {@value #PAGE_BUDGET} pages of 1024² (DESIGN §14), {@link #trim} frees rasters
 * with a page of their own that the current screen has not used, least recently used first (also
 * before a new own page is kept). The shared and CJK pages are never trimmed, and nothing used in
 * the current frame is ever freed. A trimmed raster is simply generated again on its next use.
 *
 * <p><b>Raster selection.</b> To draw cap {@code c} from the raster generated for cap {@code r}
 * (several sizes from one raster), use {@link #drawScaled}, the raster overload of
 * {@link #drawDigits}, or wrap any call in {@link #pushPixelScale}{@code (Ui.pxPerWorld() × r / c)}
 * and {@link #popPixelScale()}.
 *
 * <p>Owned by Main (never static); dispose once. Not thread-safe (GL thread only).
 */
public final class Fonts implements Disposable {

    /** Extra vertical-align bit: {@code y} is the vertical centre of the cap height. */
    public static final int VCENTER = 1 << 8;

    /**
     * Font roles (DESIGN §1.3). Each role's file is named only here, so a swap is one line. Since
     * 2026-10-13 only OFL fonts ship (DESIGN Appendix C): the Mokoto faces were replaced by Rubik
     * Glitch for the display roles and Chakra Petch Bold for numbers.
     */
    public enum Role {
        /** Rubik Glitch: titles, logo, grade stamp, DESCEND (was Mokoto Glitch Mark 2). */
        DISPLAY("RubikGlitch-Regular.ttf", 0.700f, Kind.OTHER, 0f),
        /** Rubik Glitch: judgement words, screen titles (was Mokoto Glitch Mark). */
        DISPLAY_SOFT("RubikGlitch-Regular.ttf", 0.700f, Kind.OTHER, 0f),
        /** Rubik Glitch: result-page watermarks (was the hollow Mokoto Glitch Mark outline). */
        DISPLAY_OUTLINE("RubikGlitch-Regular.ttf", 0.700f, Kind.OTHER, 0f),
        /** Rubik Glitch (alerts, chapter names). */
        RUBIK("RubikGlitch-Regular.ttf", 0.700f, Kind.OTHER, 0f),
        /** Chakra Petch Bold: numbers and short caps (was Mokoto Glitch). */
        NUMBER("ChakraPetch-Bold.ttf", 0.700f, Kind.CHAKRA, 0f),
        /** Chakra Petch SemiBold. */
        UI("ChakraPetch-SemiBold.ttf", 0.700f, Kind.CHAKRA, 0f),
        /** Chakra Petch Bold. */
        UI_BOLD("ChakraPetch-Bold.ttf", 0.700f, Kind.CHAKRA, 0f),
        /** Chakra Petch Regular. */
        UI_REGULAR("ChakraPetch-Regular.ttf", 0.700f, Kind.CHAKRA, 0f),
        /** Chakra Petch SemiBold with tracking {@code spaceX = round(0.10 × px)} (caps labels). */
        UI_LABEL("ChakraPetch-SemiBold.ttf", 0.700f, Kind.CHAKRA, 0.10f),
        /** Noto Sans SC Medium, incremental (CJK fallback). */
        CJK("NotoSansSC-Medium.ttf", 0.737f, Kind.CJK, 0f);

        /** File name inside {@code assets/fonts/}. */
        public final String file;
        /** Cap height / em. */
        public final float capRatio;
        final Kind kind;
        final float tracking;

        Role(String file, float capRatio, Kind kind, float tracking) {
            this.file = file;
            this.capRatio = capRatio;
            this.kind = kind;
            this.tracking = tracking;
        }

        /**
         * True for faces that share advances, so a glitch burst can swap between them without moving
         * the text. None do since the Mokoto faces were replaced (DESIGN Appendix C).
         */
        public boolean swaps() {
            return false;
        }

        /** True for unicase Mokoto faces (lowercase is aliased to uppercase). */
        public boolean isMokoto() {
            return kind == Kind.MOKOTO || kind == Kind.MOKOTO_WIDE;
        }
    }

    /** Glyph sets for display sizes (Appendix A.2c). Small sets may be generated up to 256 px. */
    public enum GlyphSet {
        /** Full set of the role (ASCII + symbols; Latin-1 for UI roles). */
        FULL(false),
        /** {@code 0123456789,.:%+-/} and space. */
        DIGITS(true),
        /** A–Z, 0–9, space, {@code ·-+%!}. */
        WORDS(false),
        /** {@code PURESABCD+}. */
        GRADE(true),
        /** {@code K& }: the {@code K & K} title (Welcome, the OTHER brand row, the tutorial's finale). */
        LOGO(true);

        final boolean small;

        GlyphSet(boolean small) {
            this.small = small;
        }
    }

    enum Kind { MOKOTO, MOKOTO_WIDE, CHAKRA, OTHER, CJK }

    private static final String SYMBOLS = "·•—…–→←↑↓★☆♪×÷±%°μ";
    private static final String DIGIT_CHARS = "0123456789,.:%+-/ ";
    private static final String WORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ·-+%!";
    private static final String GRADE_CHARS = "RSABCDF+";
    private static final String LOGO_CHARS = "K& ";
    private static final float MAX_PX = 160f;
    private static final float MAX_PX_SMALL = 256f;
    /** Side of the shared small-raster page (capped by {@code GL_MAX_TEXTURE_SIZE}). */
    private static final int SHARED_PAGE = 2048;
    /** Rasters whose estimated glyph area (px²) is at most this go to the shared page. */
    private static final int SHARED_MAX_AREA = 420_000;
    /** Fraction of the shared page that estimated raster areas may fill (packing slack). */
    private static final float SHARED_FILL = 0.75f;
    /** Fraction of an own page that a raster's estimated area may fill (an under-estimate only adds a page). */
    private static final float OWN_FILL = 0.7f;
    /** Packer padding around each glyph (px). */
    private static final int PADDING = 2;
    /** Nesting depth of {@link #pushPixelScale}. */
    private static final int SCALE_STACK = 8;
    /** Font texture budget in 1024² RGBA pages (DESIGN §14); {@link #trim} enforces it where it can. */
    private static final float PAGE_BUDGET = 6f;

    /** One generated font plus the metrics needed to draw it in world units. */
    static final class Entry {
        BitmapFont font;
        Role role;
        /** Unscaled cap height in pixels. */
        float cap0;
        /** Unscaled distance from the baseline up to BitmapFont's draw y. */
        float top0;
        /** Unscaled distance from the baseline to the bottom of glyph quads offset origin. */
        float glyphBase0;
        /** True when the glyphs live on the shared small-raster page. */
        boolean shared;
        /** The raster's own page(s) when neither shared nor CJK (disposed with the entry). */
        PixmapPacker own;
        /** Estimated glyph area in px² (what {@link #sharedEligible} decided on). */
        int estimatedArea;
        GlyphSet set;
        int bucket;
        String ellipsis;
        /** Screen epoch ({@link #beginScreen}) of the last lookup. */
        int epoch;
        /** Frame id of the last lookup (a raster drawn this frame is never freed). */
        long frame;
    }

    private final ObjectMap<String, FreeTypeFontGenerator> generators = new ObjectMap<>();
    private final IntMap<Entry> cache = new IntMap<>();
    private final GlyphLayout layout = new GlyphLayout();
    private final Color scratch = new Color();
    /** Sign + 19 digits + 6 group commas = 26 cells, plus a spare. */
    private static final int DIGIT_CELLS = 27;
    private final char[] digitBuf = new char[DIGIT_CELLS];
    private PixmapPacker cjkPacker;
    private PixmapPacker sharedPacker;
    /** Sum of the estimated areas packed into {@link #sharedPacker}. */
    private long sharedArea;
    /** True once a raster spilled off the shared page: later rasters get pages of their own. */
    private boolean sharedClosed;
    private int sharedPageSize;
    /** {@link #sharedSide()}, queried once (0 until the first own page). */
    private int maxSide;
    private float cachePpw = -1f;
    private float pixelScaleOverride = -1f;
    private final float[] scaleStack = new float[SCALE_STACK];
    private int scaleDepth;
    /** Current screen epoch; see {@link #beginScreen}. */
    private int epoch;

    /** Creates the service; generators and fonts are created lazily (or via {@link #prewarm}). */
    public Fonts() {
    }

    // ------------------------------------------------------------------ lifecycle
    /** Call after {@link Ui#onResize}: clears the caches when the pixel scale changed by >10 %. */
    public void onResize() {
        float ppw = Ui.pxPerWorld();
        if (cachePpw > 0f && Math.abs(ppw / cachePpw - 1f) > 0.10f) clearCache();
    }

    /** Disposes every cached font and both shared packers (fonts regenerate lazily). */
    public void clearCache() {
        for (IntMap.Entry<Entry> e : cache.entries()) disposeEntry(e.value);
        cache.clear();
        disposePacker(cjkPacker);
        cjkPacker = null;
        disposePacker(sharedPacker);
        sharedPacker = null;
        sharedArea = 0L;
        sharedClosed = false;
        cachePpw = -1f;
    }

    /**
     * Frees a caller-owned packer (shared, or a raster's own) and its pages. Fonts generated into
     * a caller-owned packer have {@code ownsTexture = false}, so {@code BitmapFont.dispose()} leaves
     * the page textures alone, and {@code PixmapPacker.dispose()} in turn skips every page that has
     * a texture. The page textures are therefore disposed here explicitly; their dispose override
     * also frees the backing pixmap. Null-safe.
     */
    private static void disposePacker(PixmapPacker packer) {
        if (packer == null) return;
        Array<PixmapPacker.Page> pages = packer.getPages();
        for (int i = 0; i < pages.size; i++) {
            Texture t = pages.get(i).getTexture();
            if (t != null) t.dispose();
        }
        packer.dispose();
    }

    /** A packer for fonts that share pages; transparent pixels are white so edges never darken. */
    private static PixmapPacker newPacker(int size, PixmapPacker.PackStrategy strategy) {
        return newPacker(size, size, strategy);
    }

    private static PixmapPacker newPacker(int w, int h, PixmapPacker.PackStrategy strategy) {
        PixmapPacker p = new PixmapPacker(w, h, Pixmap.Format.RGBA8888, PADDING, false, strategy);
        p.setTransparentColor(new Color(1f, 1f, 1f, 0f));
        return p;
    }

    /**
     * The page of a raster that does not go to the shared page: the smallest power-of-two square
     * (or the half of it, w × w/2) that holds {@code area} at {@link #OWN_FILL} fill, at least
     * 128 px and 2 em (the tallest glyph) per side, at most {@link #sharedSide()}. When the
     * estimate is short, {@link #generate} doubles the page until the raster fits on one.
     */
    private PixmapPacker newOwnPacker(int area, int sizePx) {
        if (maxSide == 0) maxSide = sharedSide();
        float need = area / OWN_FILL;
        int minSide = Math.max(128, MathUtils.nextPowerOfTwo(2 * sizePx + 2 * PADDING));
        int w = Math.min(maxSide, Math.max(minSide, MathUtils.nextPowerOfTwo((int) Math.ceil(Math.sqrt(need)))));
        int h = w / 2 >= minSide && (float) w * (w / 2) >= need ? w / 2 : w;
        return newPacker(w, h, new PixmapPacker.SkylineStrategy());
    }

    /** Number of pages in the shared CJK packer (0 when none); for diagnostics and tests. */
    public int cjkPageCount() {
        return cjkPacker == null ? 0 : cjkPacker.getPages().size;
    }

    /** Side of the shared small-raster page in pixels (0 before the first shared raster). */
    public int sharedPageSize() {
        return sharedPacker == null ? 0 : sharedPageSize;
    }

    /**
     * Font texture memory in units of a 1024² RGBA page (DESIGN §14 budget: 6): every page of the
     * shared packers plus each own-page raster. Diagnostics only (iterates the cache).
     */
    public float pageBudgetUsed() {
        float units = 0f;
        float sharedUnits = (sharedPageSize / 1024f) * (sharedPageSize / 1024f);
        if (sharedPacker != null) units += sharedPacker.getPages().size * sharedUnits;
        units += cjkPageCount();
        for (IntMap.Entry<Entry> e : cache.entries()) {
            if (e.value.shared || e.value.role == Role.CJK) continue;
            units += ownUnits(e.value);
        }
        return units;
    }

    /** Page units (1024² RGBA) of the textures of an own-page raster. */
    private static float ownUnits(Entry e) {
        float units = 0f;
        Array<TextureRegion> regions = e.font.getRegions();
        for (int i = 0; i < regions.size; i++) {
            Texture t = regions.get(i).getTexture();
            units += (t.getWidth() / 1024f) * (t.getHeight() / 1024f);
        }
        return units;
    }

    /**
     * Selects rasters for an explicit pixel-per-unit scale until {@link #popPixelScale()}: the
     * font bucket becomes {@code refPx × pxPerUnit} instead of {@code refPx × Ui.pxPerWorld()},
     * while sizes stay in world units. Two uses:
     * <ul>
     *   <li>drawing into a FrameBuffer whose units are pixels (pass the FBO's pixels per unit);</li>
     *   <li>drawing cap {@code c} from the raster of cap {@code r}: pass
     *       {@code Ui.pxPerWorld() × r / c}, so several sizes share one raster (one font page).</li>
     * </ul>
     * Calls nest (up to 8 deep); every push needs exactly one pop.
     */
    public void pushPixelScale(float pxPerUnit) {
        if (scaleDepth < SCALE_STACK) scaleStack[scaleDepth] = pixelScaleOverride;
        scaleDepth++;
        pixelScaleOverride = pxPerUnit;
    }

    /** Restores the pixel scale in effect before the matching {@link #pushPixelScale(float)}. */
    public void popPixelScale() {
        if (scaleDepth <= 0) {
            pixelScaleOverride = -1f;
            return;
        }
        scaleDepth--;
        pixelScaleOverride = scaleDepth < SCALE_STACK ? scaleStack[scaleDepth] : -1f;
    }

    /** Generates the font for (role, refPx, set) now, e.g. during loading. */
    public void prewarm(Role role, float refPx, GlyphSet set) {
        entry(role, refPx, set);
    }

    /**
     * Opens a new screen epoch: rasters looked up from now on belong to the screen that is being
     * prepared or shown, and {@link #trim} may free own-page rasters that it never uses. Main calls
     * this once per screen switch, before the next screen prewarms.
     */
    public void beginScreen() {
        epoch++;
    }

    /**
     * Frees own-page rasters that the current screen has not used, least recently used first, while
     * the font textures exceed {@value #PAGE_BUDGET} pages of 1024² ({@link #pageBudgetUsed}).
     * Shared and CJK pages stay; a raster looked up in the current frame is never freed. Runs at
     * screen switches and generation time only (it iterates the cache).
     */
    public void trim() {
        float used = pageBudgetUsed();
        long frame = frameId();
        while (used > PAGE_BUDGET) {
            Entry victim = null;
            int victimKey = 0;
            for (IntMap.Entry<Entry> me : cache.entries()) {
                Entry e = me.value;
                if (e.own == null || e.epoch == epoch || e.frame == frame) continue;
                if (victim == null || e.epoch < victim.epoch || (e.epoch == victim.epoch && e.frame < victim.frame)) {
                    victim = e;
                    victimKey = me.key;
                }
            }
            if (victim == null) return;
            cache.remove(victimKey);
            used -= ownUnits(victim);
            disposeEntry(victim);
        }
    }

    private static long frameId() {
        return Gdx.graphics != null ? Gdx.graphics.getFrameId() : 0L;
    }

    @Override
    public void dispose() {
        clearCache();
        for (ObjectMap.Entry<String, FreeTypeFontGenerator> g : generators.entries()) g.value.dispose();
        generators.clear();
    }

    // ------------------------------------------------------------------ public API
    /**
     * Returns the font for {@code role} at cap height {@code refPx} (world units) with its data
     * scale set so that drawing in world units yields {@code refPx}-tall caps. The instance is
     * shared: use it immediately and do not keep it.
     */
    public BitmapFont get(Role role, float refPx) {
        return get(role, refPx, GlyphSet.FULL);
    }

    /** As {@link #get(Role, float)} for a specific glyph set. */
    public BitmapFont get(Role role, float refPx, GlyphSet set) {
        Entry e = entry(role, refPx, set);
        apply(e, refPx);
        return e.font;
    }

    /** True when {@code text} has a char that the role font cannot draw (routes to CJK). */
    public boolean needsCjk(Role role, CharSequence text) {
        if (role == Role.CJK || text == null) return false;
        return needsCjk(null, role, text);
    }

    private boolean needsCjk(Entry e, Role role, CharSequence text) {
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i);
            // above Latin Extended: CJK, except the shared UI symbols when the role font has them
            if (c > 0x24F && SYMBOLS.indexOf(c) < 0) return true;
            if (c >= 0x80) {
                if (e == null) e = entry(role, 20f, GlyphSet.FULL);
                if (e.font.getData().getGlyph(c) == null) return true;
            }
        }
        return false;
    }

    /** Draws {@code text}; returns its width in world units. See class doc for anchoring. */
    public float draw(Batch b, Role role, float refPx, CharSequence text, float x, float y, int align,
                      Color color, float alpha) {
        return draw(b, role, refPx, GlyphSet.FULL, text, x, y, align, color, alpha);
    }

    /** Draws {@code text} using a glyph set; returns its width in world units. */
    public float draw(Batch b, Role role, float refPx, GlyphSet set, CharSequence text, float x, float y,
                      int align, Color color, float alpha) {
        if (text == null || text.length() == 0 || refPx <= 0f) return 0f;
        Entry e = route(role, refPx, set, text);
        return drawEntry(b, e, refPx, refPx, text, x, y, align, color, alpha, -1f);
    }

    /** Width of {@code text} at cap {@code refPx} in world units. */
    public float width(Role role, float refPx, CharSequence text) {
        return width(role, refPx, GlyphSet.FULL, text);
    }

    /** Width of {@code text} using a glyph set. */
    public float width(Role role, float refPx, GlyphSet set, CharSequence text) {
        if (text == null || text.length() == 0 || refPx <= 0f) return 0f;
        Entry e = route(role, refPx, set, text);
        apply(e, refPx);
        layout.setText(e.font, text);
        return layout.width;
    }

    /**
     * Draws {@code text} shrunk to fit {@code maxWidth}: measures once, scales the cap down to at
     * most {@code minScale} (no regeneration), then truncates with an ellipsis if still too wide.
     * Returns the drawn width.
     */
    public float drawFit(Batch b, Role role, float refPx, CharSequence text, float x, float y, int align,
                         float maxWidth, float minScale, Color color, float alpha) {
        if (text == null || text.length() == 0 || refPx <= 0f) return 0f;
        Entry e = route(role, refPx, GlyphSet.FULL, text);
        apply(e, refPx);
        layout.setText(e.font, text);
        float w = layout.width;
        float draw = refPx;
        float truncate = -1f;
        if (w > maxWidth && w > 0f) {
            float k = Math.max(minScale, maxWidth / w);
            draw = refPx * k;
            if (w * k > maxWidth + 0.5f) truncate = maxWidth;
        }
        return drawEntry(b, e, refPx, draw, text, x, y, align, color, alpha, truncate);
    }

    /**
     * Draws {@code text} rasterised for cap {@code refPx} but drawn at {@code refPx × scale} (for
     * press springs and pops: the font bucket stays fixed while the size animates). When
     * {@code maxWidth > 0} the text is shrunk (down to {@code minScale} of the scaled size) and
     * then ellipsis-truncated to fit, like {@link #drawFit}. Returns the drawn width.
     */
    public float drawScaled(Batch b, Role role, float refPx, float scale, CharSequence text, float x, float y,
                            int align, float maxWidth, float minScale, Color color, float alpha) {
        if (text == null || text.length() == 0 || refPx <= 0f || scale <= 0f) return 0f;
        Entry e = route(role, refPx, GlyphSet.FULL, text);
        float k = scale;
        float truncate = -1f;
        if (maxWidth > 0f) {
            apply(e, refPx);
            layout.setText(e.font, text);
            float w = layout.width * scale;
            if (w > maxWidth && w > 0f) {
                float f = Math.max(minScale, maxWidth / w);
                k = scale * f;
                if (layout.width * k > maxWidth + 0.5f) truncate = maxWidth;
            }
        }
        return drawEntry(b, e, refPx, refPx * k, text, x, y, align, color, alpha, truncate);
    }

    // ------------------------------------------------------------------ fixed-cell numbers
    /**
     * Fixed-cell integer renderer (Appendix A.2d): each glyph is centred by its ink in a cell of
     * fixed width (Mokoto digit 1.10 × cap, {@code ,}/{@code .} 0.45, {@code %} 1.16; Chakra digit
     * 0.92). Draws at least {@code minDigits} digits; digits that are leading zeros (and the
     * group commas between them) use {@code leadingZero} (pass null to hide them). Accepts the
     * whole {@code long} range including {@link Long#MIN_VALUE}; {@code minDigits} is capped at 19.
     * No allocation.
     *
     * @return the total width in world units
     */
    public float drawDigits(Batch b, Role role, float cap, long value, int minDigits, boolean grouping,
                            float x, float y, int align, Color color, Color leadingZero, float alpha) {
        return drawDigits(b, role, cap, value, minDigits, grouping, x, y, align, color, leadingZero, alpha, null);
    }

    /**
     * As {@link #drawDigits(Batch, Role, float, long, int, boolean, float, float, int, Color, Color,
     * float)} with an optional per-cell scale (index 0 = leftmost cell, may be shorter than the
     * cell count) for "changed cells pop" effects.
     */
    public float drawDigits(Batch b, Role role, float cap, long value, int minDigits, boolean grouping,
                            float x, float y, int align, Color color, Color leadingZero, float alpha,
                            float[] cellScale) {
        return drawDigits(b, role, cap, cap, value, minDigits, grouping, x, y, align, color, leadingZero, alpha,
            cellScale);
    }

    /**
     * As {@link #drawDigits(Batch, Role, float, long, int, boolean, float, float, int, Color, Color,
     * float, float[])}, drawn at cap {@code cap} from the digit raster generated for cap
     * {@code rasterCap}, so several sizes share one raster (fixed cells are always laid out for
     * {@code cap}).
     */
    public float drawDigits(Batch b, Role role, float cap, float rasterCap, long value, int minDigits,
                            boolean grouping, float x, float y, int align, Color color, Color leadingZero,
                            float alpha, float[] cellScale) {
        int n = buildDigits(value, minDigits, grouping);
        Entry e = entry(role, rasterCap > 0f ? rasterCap : cap, digitSet(role));
        float s = apply(e, cap);
        float total = 0f;
        for (int i = 0; i < n; i++) total += cellW(e, s, cap, digitBuf[i]);
        float left = alignX(x, total, align);
        float baseline = alignBaseline(y, cap, align);
        float packed = Color.toFloatBits(color.r, color.g, color.b, color.a * alpha);
        float packedLead = leadingZero == null ? 0f
            : Color.toFloatBits(leadingZero.r, leadingZero.g, leadingZero.b, leadingZero.a * alpha);
        float old = b.getPackedColor();
        float cx = left;
        // digitBuf holds chars left to right; digitLead marks leading cells
        for (int i = 0; i < n; i++) {
            char c = digitBuf[i];
            float w = cellW(e, s, cap, c);
            boolean lead = digitLead[i];
            if (!lead || leadingZero != null) {
                b.setPackedColor(lead ? packedLead : packed);
                float k = (cellScale != null && i < cellScale.length) ? cellScale[i] : 1f;
                drawCell(b, e, s, c, cx, w, baseline, k, cap);
            }
            cx += w;
        }
        b.setPackedColor(old);
        return total;
    }

    /** Width {@link #drawDigits} would use for {@code digits} digits (with or without grouping). */
    public float digitsWidth(Role role, float cap, int digits, boolean grouping) {
        Entry e = entry(role, cap, digitSet(role));
        float s = apply(e, cap);
        float w = digits * cellW(e, s, cap, '0');
        if (grouping && digits > 3) w += ((digits - 1) / 3) * cellW(e, s, cap, ',');
        return w;
    }

    /**
     * Fixed-cell renderer for a short numeric string such as {@code 99.12%}, {@code 1:52} or
     * {@code +38}: digits use fixed cells, other chars their advance. No allocation for
     * StringBuilder input.
     */
    public float drawFixed(Batch b, Role role, float cap, CharSequence text, float x, float y, int align,
                           Color color, float alpha) {
        if (text == null || text.length() == 0) return 0f;
        Entry e = entry(role, cap, digitSet(role));
        float s = apply(e, cap);
        int n = text.length();
        float total = 0f;
        for (int i = 0; i < n; i++) total += cellW(e, s, cap, text.charAt(i));
        float cx = alignX(x, total, align);
        float baseline = alignBaseline(y, cap, align);
        float old = b.getPackedColor();
        b.setColor(color.r, color.g, color.b, color.a * alpha);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            float w = cellW(e, s, cap, c);
            drawCell(b, e, s, c, cx, w, baseline, 1f, cap);
            cx += w;
        }
        b.setPackedColor(old);
        return total;
    }

    /** Width {@link #drawFixed} would use. */
    public float fixedWidth(Role role, float cap, CharSequence text) {
        Entry e = entry(role, cap, digitSet(role));
        float s = apply(e, cap);
        float total = 0f;
        for (int i = 0, n = text.length(); i < n; i++) total += cellW(e, s, cap, text.charAt(i));
        return total;
    }

    /** Fixed cell width of one char for (role, cap). */
    public float cellWidth(Role role, float cap, char c) {
        Entry e = entry(role, cap, digitSet(role));
        return cellW(e, apply(e, cap), cap, c);
    }

    /**
     * Draws one char centred by its ink in a cell starting at {@code cellX} (baseline
     * {@code baselineY}), scaled by {@code scale} about the cell centre. Uses the batch colour
     * as set by the caller. Building block for decode / slot-roll effects.
     */
    public void drawCell(Batch b, Role role, float cap, char c, float cellX, float baselineY, float scale) {
        Entry e = entry(role, cap, digitSet(role));
        float s = apply(e, cap);
        drawCell(b, e, s, c, cellX, cellW(e, s, cap, c), baselineY, scale, cap);
    }

    // ------------------------------------------------------------------ string helpers
    /** Appends {@code v} with thousands grouping ({@code 2,560}) without allocating. */
    public static StringBuilder appendGrouped(StringBuilder sb, long v) {
        if (v == Long.MIN_VALUE) return sb.append("-9,223,372,036,854,775,808");
        if (v < 0) {
            sb.append('-');
            v = -v;
        }
        long div = 1;
        int digits = 1;
        while (v / div >= 10) {
            div *= 10;
            digits++;
        }
        for (int i = digits - 1; i >= 0; i--) {
            int d = (int) (v / div % 10);
            sb.append((char) ('0' + d));
            if (i > 0 && i % 3 == 0) sb.append(',');
            div /= 10;
        }
        return sb;
    }

    /** Appends {@code v} without grouping and without allocating. */
    public static StringBuilder appendInt(StringBuilder sb, long v) {
        if (v == Long.MIN_VALUE) return sb.append("-9223372036854775808");
        if (v < 0) {
            sb.append('-');
            v = -v;
        }
        long div = 1;
        while (v / div >= 10) div *= 10;
        while (div > 0) {
            sb.append((char) ('0' + (int) (v / div % 10)));
            div /= 10;
        }
        return sb;
    }

    /**
     * Appends {@code value} with exactly {@code decimals} decimals, truncated (not rounded)
     * toward zero — e.g. accuracy {@code 99.129 → "99.12"}. No allocation.
     */
    public static StringBuilder appendTruncated(StringBuilder sb, double value, int decimals) {
        if (value < 0) {
            sb.append('-');
            value = -value;
        }
        long pow = 1;
        for (int i = 0; i < decimals; i++) pow *= 10;
        long scaled = (long) Math.floor(value * pow + 1e-7);
        appendInt(sb, scaled / pow);
        if (decimals > 0) {
            sb.append('.');
            long frac = scaled % pow;
            for (long p = pow / 10; p > 0; p /= 10) {
                sb.append((char) ('0' + (int) (frac / p % 10)));
            }
        }
        return sb;
    }

    // ------------------------------------------------------------------ package API (glitch, avatar)
    /**
     * Routes and scales the font for drawing {@code text}; the returned font is ready to lay
     * out. {@link #lastTop()} then gives the distance from the baseline to BitmapFont's y.
     */
    BitmapFont prepare(Role role, float refPx, GlyphSet set, CharSequence text) {
        return prepare(role, refPx, refPx, set, text);
    }

    /**
     * As {@link #prepare(Role, float, GlyphSet, CharSequence)}, but picks the raster for cap
     * {@code rasterPx} and scales it to draw at cap {@code drawPx} — a transient scale (pop,
     * punch) then reuses one bucket instead of generating a font per frame.
     */
    BitmapFont prepare(Role role, float rasterPx, float drawPx, GlyphSet set, CharSequence text) {
        Entry e = route(role, rasterPx, set, text);
        apply(e, drawPx);
        lastTop = e.top0 * drawPx / e.cap0;
        return e.font;
    }

    private float lastTop;

    /** Baseline → BitmapFont draw-y distance of the last {@link #prepare} call (world units). */
    float lastTop() {
        return lastTop;
    }

    /** Horizontal start x for a run of width {@code w} anchored at {@code x}. */
    static float alignX(float x, float w, int align) {
        if ((align & Align.left) != 0) return x;
        if ((align & Align.right) != 0) return x - w;
        return x - w * 0.5f;
    }

    /** Baseline y for a cap height and vertical anchor bits. */
    static float alignBaseline(float y, float cap, int align) {
        if ((align & Align.top) != 0) return y - cap;
        if ((align & VCENTER) != 0) return y - cap * 0.5f;
        return y;
    }

    // ------------------------------------------------------------------ internals
    private float drawEntry(Batch b, Entry e, float genPx, float drawPx, CharSequence text, float x, float y,
                            int align, Color color, float alpha, float truncateWidth) {
        float s = apply(e, drawPx);
        scratch.set(color.r, color.g, color.b, color.a * alpha);
        if (truncateWidth > 0f) {
            layout.setText(e.font, text, 0, text.length(), scratch, truncateWidth, Align.left, false, e.ellipsis);
        } else {
            layout.setText(e.font, text, 0, text.length(), scratch, 0f, Align.left, false, null);
        }
        float w = layout.width;
        float left = alignX(x, w, align);
        float baseline = alignBaseline(y, drawPx, align);
        e.font.draw(b, layout, left, baseline + e.top0 * s);
        return w;
    }

    private Entry route(Role role, float refPx, GlyphSet set, CharSequence text) {
        Entry e = entry(role, refPx, set);
        if (role != Role.CJK && needsCjk(e, role, text)) return entry(Role.CJK, refPx, GlyphSet.FULL);
        return e;
    }

    /** Applies the data scale for drawing at cap {@code refPx}; returns the scale. */
    private static float apply(Entry e, float refPx) {
        float s = refPx / e.cap0;
        BitmapFont.BitmapFontData d = e.font.getData();
        if (d.scaleX != s || d.scaleY != s) d.setScale(s);
        return s;
    }

    private float ppw() {
        return pixelScaleOverride > 0f ? pixelScaleOverride : Ui.pxPerWorld();
    }

    private Entry entry(Role role, float refPx, GlyphSet set) {
        if (role == Role.CJK) set = GlyphSet.FULL;
        float px = refPx * ppw();
        int bucket = quantize(px, set.small ? MAX_PX_SMALL : MAX_PX);
        int key = (role.ordinal() << 16) | (set.ordinal() << 10) | bucket;
        Entry e = cache.get(key);
        boolean fresh = e == null;
        if (fresh) {
            e = generate(role, set, bucket);
            cache.put(key, e);
            if (cachePpw < 0f && pixelScaleOverride <= 0f) cachePpw = Ui.pxPerWorld();
        }
        e.epoch = epoch;
        e.frame = frameId();
        if (fresh && e.own != null) trim();
        return e;
    }

    /** Pixel bucket: ×2 below 48, ×4 below 96, ×8 above; clamped to [6, max]. */
    static int quantize(float px, float max) {
        float p = Math.min(Math.max(px, 6f), max);
        int q;
        if (p < 48f) q = 2;
        else if (p < 96f) q = 4;
        else q = 8;
        int b = Math.round(p / q) * q;
        return Math.max(6, Math.min(b, (int) max));
    }

    private FreeTypeFontGenerator generator(String file) {
        FreeTypeFontGenerator g = generators.get(file);
        if (g == null) {
            FileHandle fh = Gdx.files.internal("fonts/" + file);
            g = new FreeTypeFontGenerator(fh);
            generators.put(file, g);
        }
        return g;
    }

    private Entry generate(Role role, GlyphSet set, int capPx) {
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = Math.max(4, Math.round(capPx / role.capRatio));
        p.characters = charsFor(role, set);
        p.minFilter = Texture.TextureFilter.Linear;
        p.magFilter = Texture.TextureFilter.Linear;
        p.kerning = true;
        p.spaceX = role.tracking > 0f ? Math.round(role.tracking * p.size) : 0;
        int area = estimateArea(role, set, p.size, p.characters.length());
        boolean shared = false;
        PixmapPacker own = null;
        if (role == Role.CJK) {
            if (cjkPacker == null) cjkPacker = newPacker(1024, new PixmapPacker.SkylineStrategy());
            p.incremental = true;
            p.packer = cjkPacker;
        } else if (sharedEligible(area)) {
            if (sharedPacker == null) {
                sharedPageSize = sharedSide();
                sharedPacker = newPacker(sharedPageSize, new PixmapPacker.SkylineStrategy());
            }
            p.packer = sharedPacker;
            sharedArea += area;
            shared = true;
        } else {
            own = newOwnPacker(area, p.size);
            p.packer = own;
        }
        // the first shared raster creates the page; any later page is a spill
        int sharedPages = shared ? Math.max(1, sharedPacker.getPages().size) : 0;
        BitmapFont font = generateInto(role, p, own);
        if (shared && sharedPacker.getPages().size > sharedPages) {
            // the estimates fell short and the raster spilled onto a second shared page: drop that
            // page, close the shared page to further rasters and give this one a page of its own
            font.dispose();
            dropPagesAfter(sharedPacker, sharedPages);
            sharedArea -= area;
            sharedClosed = true;
            shared = false;
            own = newOwnPacker(area, p.size);
            p.packer = own;
            font = generateInto(role, p, own);
        }
        // an own page is sized from the estimate: grow it until the raster fits on one page
        while (own != null && own.getPages().size > 1
            && (own.getPageWidth() < maxSide || own.getPageHeight() < maxSide)) {
            int w = own.getPageWidth(), h = own.getPageHeight();
            font.dispose();
            disposePacker(own);
            own = h < w ? newPacker(w, w, new PixmapPacker.SkylineStrategy())
                : newPacker(Math.min(maxSide, 2 * w), h, new PixmapPacker.SkylineStrategy());
            p.packer = own;
            font = generateInto(role, p, own);
        }
        font.setUseIntegerPositions(false);
        BitmapFont.BitmapFontData data = font.getData();
        if (role.isMokoto()) {
            // unicase faces: lowercase draws as uppercase; no en dash, so it draws as the hyphen
            for (char c = 'a'; c <= 'z'; c++) alias(data, c, Character.toUpperCase(c));
            alias(data, '–', '-');
        }
        Entry e = new Entry();
        e.font = font;
        e.role = role;
        e.set = set;
        e.bucket = capPx;
        e.shared = shared;
        e.own = own;
        e.estimatedArea = area;
        e.cap0 = data.capHeight > 0f ? data.capHeight : capPx;
        BitmapFont.Glyph ref = refGlyph(data);
        if (ref != null) {
            // glyph bottom = drawY + ascent + yoffset ⇒ drawY = baseline − (ascent + yoffset)
            e.top0 = -(data.ascent + ref.yoffset);
        } else {
            e.top0 = e.cap0;
        }
        e.glyphBase0 = e.top0 + data.ascent;
        e.ellipsis = data.getGlyph('…') != null ? "…" : "...";
        return e;
    }

    /** Generates the raster into {@code p.packer}; frees {@code own} (may be null) when that fails. */
    private BitmapFont generateInto(Role role, FreeTypeFontGenerator.FreeTypeFontParameter p, PixmapPacker own) {
        try {
            return generator(role.file).generateFont(p);
        } catch (RuntimeException ex) {
            disposePacker(own);
            throw ex;
        }
    }

    /** Removes and frees the pages of {@code packer} beyond the first {@code keep}. */
    private static void dropPagesAfter(PixmapPacker packer, int keep) {
        Array<PixmapPacker.Page> pages = packer.getPages();
        while (pages.size > keep) {
            PixmapPacker.Page page = pages.pop();
            if (page.getTexture() != null) page.getTexture().dispose();
            else page.getPixmap().dispose();
        }
    }

    /** Makes {@code c} draw with {@code target}'s glyph when the font has no glyph of its own. */
    private static void alias(BitmapFont.BitmapFontData data, char c, char target) {
        if (data.getGlyph(c) != null) return;
        BitmapFont.Glyph g = data.getGlyph(target);
        if (g != null) data.setGlyph(c, g);
    }

    /**
     * Estimated packed glyph area in px² of a raster with {@code glyphs} requested chars at em size
     * {@code sizePx}: mean glyph box per em² (measured with fontTools: Chakra 0.31, Mokoto and
     * Rubik 0.43–0.45, Mokoto outline 0.53; capitals-only sets about 1.25× that) plus the padding.
     */
    static int estimateArea(Role role, GlyphSet set, int sizePx, int glyphs) {
        float perGlyph = role.kind == Kind.CHAKRA ? 0.32f : (role.kind == Kind.MOKOTO_WIDE ? 0.53f : 0.45f);
        if (set == GlyphSet.WORDS || set == GlyphSet.GRADE || set == GlyphSet.LOGO) perGlyph *= 1.25f;
        float pad = 2f * PADDING * 1.4f * sizePx;
        return (int) Math.min(Integer.MAX_VALUE, glyphs * (perGlyph * sizePx * sizePx + pad));
    }

    /** True when a raster of estimated {@code area} goes to the shared page (and still fits it). */
    private boolean sharedEligible(int area) {
        if (area > SHARED_MAX_AREA || sharedClosed) return false;
        int side = sharedPacker != null ? sharedPageSize : sharedSide();
        return sharedArea + area <= (long) (SHARED_FILL * side * side);
    }

    /** {@value #SHARED_PAGE}, or the GPU's maximum texture size when that is smaller. */
    private static int sharedSide() {
        int max = SHARED_PAGE;
        if (Gdx.gl != null) {
            IntBuffer buf = BufferUtils.newIntBuffer(16);
            Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, buf);
            int gpu = buf.get(0);
            if (gpu >= 512) max = Math.min(max, gpu);
        }
        return max;
    }

    private static BitmapFont.Glyph refGlyph(BitmapFont.BitmapFontData data) {
        String candidates = "H0KPMEBN1";
        for (int i = 0; i < candidates.length(); i++) {
            BitmapFont.Glyph g = data.getGlyph(candidates.charAt(i));
            if (g != null && g.height > 0) return g;
        }
        return null;
    }

    private static void disposeEntry(Entry e) {
        e.font.dispose();
        disposePacker(e.own);
    }

    private static GlyphSet digitSet(Role role) {
        return role == Role.CJK ? GlyphSet.FULL : GlyphSet.DIGITS;
    }

    private static String charsFor(Role role, GlyphSet set) {
        switch (set) {
            case DIGITS:
                return DIGIT_CHARS;
            case WORDS:
                return WORD_CHARS;
            case GRADE:
                return GRADE_CHARS;
            case LOGO:
                return LOGO_CHARS;
            default:
                break;
        }
        StringBuilder sb = new StringBuilder(256);
        if (role == Role.CJK) {
            for (char c = 32; c < 127; c++) sb.append(c);
            return sb.toString();
        }
        boolean mokoto = role.isMokoto();
        for (char c = 32; c < 127; c++) {
            if (mokoto && c >= 'a' && c <= 'z') continue;
            sb.append(c);
        }
        if (role.kind == Kind.CHAKRA) {
            for (char c = 0xA1; c <= 0xFF; c++) sb.append(c);
        }
        sb.append(SYMBOLS);
        return sb.toString();
    }

    private static float digitCellFactor(Role role) {
        switch (role.kind) {
            case MOKOTO:
                return 1.10f;
            case MOKOTO_WIDE:
                return 1.36f;
            case CHAKRA:
                return 0.92f;
            default:
                return 0.95f;
        }
    }

    private static float cellW(Entry e, float s, float cap, char c) {
        Role role = e.role;
        if (c >= '0' && c <= '9') return cap * digitCellFactor(role);
        boolean mok = role.kind == Kind.MOKOTO;
        if (mok && (c == ',' || c == '.')) return cap * 0.45f;
        if (mok && c == '%') return cap * 1.16f;
        BitmapFont.BitmapFontData d = e.font.getData();
        if (c == ' ') return d.spaceXadvance;
        BitmapFont.Glyph g = d.getGlyph(c);
        if (g == null) return cap * 0.5f;
        return g.xadvance * s;
    }

    private void drawCell(Batch b, Entry e, float s, char c, float cellX, float cellW, float baseline,
                          float k, float cap) {
        if (c == ' ') return;
        BitmapFont.Glyph g = e.font.getData().getGlyph(c);
        if (g == null || g.width == 0 || g.height == 0) return;
        float gw = g.width * s, gh = g.height * s;
        float gx = cellX + (cellW - gw) * 0.5f;
        float gy = baseline + (e.glyphBase0 + g.yoffset) * s;
        Texture tex = e.font.getRegion(g.page).getTexture();
        if (k != 1f) {
            float cxm = gx + gw * 0.5f, cym = baseline + cap * 0.5f;
            gx = cxm + (gx - cxm) * k;
            gy = cym + (gy - cym) * k;
            gw *= k;
            gh *= k;
        }
        b.draw(tex, gx, gy, gw, gh, g.u, g.v, g.u2, g.v2);
    }

    // digit building -------------------------------------------------------------------------
    private final boolean[] digitLead = new boolean[DIGIT_CELLS];

    /**
     * Fills digitBuf/digitLead left to right; returns the cell count. Handles the whole long range
     * (including {@link Long#MIN_VALUE}) by extracting digits from the non-positive value, and
     * pads to at most 19 digits ({@link Long#MAX_VALUE} has 19), so the buffers never overflow.
     */
    private int buildDigits(long value, int minDigits, boolean grouping) {
        boolean neg = value < 0;
        long v = neg ? value : -value;           // v ≤ 0: no overflow for Long.MIN_VALUE
        int nd = 0;
        char[] rev = revBuf;
        do {
            rev[nd++] = (char) ('0' - (int) (v % 10));
            v /= 10;
        } while (v != 0 && nd < rev.length);
        int sig = nd;
        int total = Math.max(Math.min(minDigits, MAX_DIGITS), nd);
        int n = 0;
        if (neg) {
            digitBuf[n] = '-';
            digitLead[n++] = false;
        }
        for (int i = total - 1; i >= 0; i--) {
            boolean lead = i >= sig;
            digitBuf[n] = lead ? '0' : rev[i];
            digitLead[n++] = lead;
            if (grouping && i > 0 && i % 3 == 0) {
                digitBuf[n] = ',';
                digitLead[n++] = lead && i >= sig;
            }
        }
        return n;
    }

    /** Longest decimal long (19 digits). */
    private static final int MAX_DIGITS = 19;
    private final char[] revBuf = new char[MAX_DIGITS];
}
