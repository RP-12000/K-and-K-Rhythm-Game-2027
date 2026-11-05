package org.kelvinizer.profile;

/**
 * 程序生成头像「声呐印记」的参数：形状、花纹、配色、符号和边框，都是整数下标。画头像的代码在界面层的 {@code ui.Avatar}；
 * 玩家用哪个头像由 {@link AvatarCatalog} 决定，深度不再解锁头像选项（2026-10-30）。
 *
 * <p>Parameters of the procedural "sonar sigil" avatar (DESIGN §8.5). Plain ints so it serialises compactly; rendering
 * lives in the UI ({@code ui.Avatar}). Which sigil a player shows comes from {@link AvatarCatalog}; depth no longer
 * unlocks options (Kelvin 2026-10-30).
 */
public class AvatarSpec {
    /** Shapes: circle, hexagon, squircle, diamond, shield. */
    public static final String[] SHAPES = {"CIRCLE", "HEXAGON", "SQUIRCLE", "DIAMOND", "SHIELD"};
    /** Patterns: rings, spokes, waves, dots, spiral, grid, split-K, constellation. */
    public static final String[] PATTERNS = {"RINGS", "SPOKES", "WAVES", "DOTS", "SPIRAL", "GRID", "SPLIT-K",
        "CONSTELLATION"};
    /** Glyphs: none, initial, K. */
    public static final String[] GLYPHS = {"NONE", "INITIAL", "K"};
    /** Frames: none, dashed sonar, double, iridescent. */
    public static final String[] FRAMES = {"NONE", "DASHED SONAR", "DOUBLE", "IRIDESCENT"};
    /** Palette pairs (primary, secondary) as RGBA8888. */
    public static final int[][] PALETTES = {
        {0x4FD8FFFF, 0xF2F7FFFF}, {0x7FE7FFFF, 0x134079FF}, {0x6FA8FFFF, 0x0F275EFF}, {0x8F7BFFFF, 0x0A1338FF},
        {0xC77DFFFF, 0x080B24FF}, {0xFF5CA8FF, 0x050613FF}, {0xFFD27AFF, 0x0A1033FF}, {0x66F0FFFF, 0xB49CFFFF},
        {0x9CF5B0FF, 0x0D1C4CFF}, {0xFF2E88FF, 0x00E5FFFF}};

    /** Random seed (pattern jitter, constellation points). */
    public int seed;
    /** Index into {@link #SHAPES}. */
    public int shape;
    /** Index into {@link #PATTERNS}. */
    public int pattern;
    /** Index into {@link #PALETTES}. */
    public int palette;
    /** Index into {@link #GLYPHS}. */
    public int glyph = 1;
    /** Index into {@link #FRAMES}. */
    public int frame;

    /** Creates a default avatar (seed 0, circle, rings, first palette, initial glyph). */
    public AvatarSpec() {
    }

    /** Clamps every index into range. */
    public void sanitize() {
        shape = Settings.clamp(shape, 0, SHAPES.length - 1);
        pattern = Settings.clamp(pattern, 0, PATTERNS.length - 1);
        palette = Settings.clamp(palette, 0, PALETTES.length - 1);
        glyph = Settings.clamp(glyph, 0, GLYPHS.length - 1);
        frame = Settings.clamp(frame, 0, FRAMES.length - 1);
    }

    /** @return a copy. */
    public AvatarSpec copy() {
        AvatarSpec a = new AvatarSpec();
        a.seed = seed;
        a.shape = shape;
        a.pattern = pattern;
        a.palette = palette;
        a.glyph = glyph;
        a.frame = frame;
        return a;
    }

    /** @return true when every parameter equals {@code o}'s (for avatar cache invalidation). */
    public boolean sameAs(AvatarSpec o) {
        return o != null && seed == o.seed && shape == o.shape && pattern == o.pattern && palette == o.palette
            && glyph == o.glyph && frame == o.frame;
    }
}
