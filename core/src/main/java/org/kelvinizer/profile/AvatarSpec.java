package org.kelvinizer.profile;

import java.util.Random;

/**
 * 程序生成头像「声呐印记」的参数：形状、花纹、配色、符号和边框，都是整数下标，并列出各选项要潜到多深（米）才解锁。
 * 画头像的代码在界面层的 {@code ui.Avatar}。
 *
 * <p>Parameters of the procedural "sonar sigil" avatar (DESIGN §8.5). Plain ints so it
 * serialises compactly; rendering lives in the UI ({@code ui.Avatar}).
 *
 * <p>The depth in metres that unlocks each option (DESIGN §8.6, by depth since 2026-10-17, at the
 * depth tiers since 14:10) is exposed so editors can show locks; {@link Depth} lists the same
 * tiers with their labels.
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

    /** Depth (m) needed for each pattern (§8.6). */
    public static final int[] PATTERN_UNLOCK_METERS = {0, 0, 2000, 0, 9500, 6000, 9000, 10000};
    /** Depth (m) needed for each frame (§8.6). */
    public static final int[] FRAME_UNLOCK_METERS = {0, 4000, 8000, 11500};
    /** Depth (m) needed for each glyph (§8.6). */
    public static final int[] GLYPH_UNLOCK_METERS = {0, 0, 10500};
    /** Depth (m) needed for each palette (§8.6: the last three at 7,000 m). */
    public static final int[] PALETTE_UNLOCK_METERS = {0, 0, 0, 0, 0, 0, 0, 7000, 7000, 7000};

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

    /**
     * @return a random avatar using only options unlocked at 0 m (deterministic for a seed)
     */
    public static AvatarSpec fromSeed(int seed) {
        Random r = new Random(seed);
        AvatarSpec a = new AvatarSpec();
        a.seed = seed;
        a.shape = r.nextInt(SHAPES.length);
        a.pattern = pickUnlocked(r, PATTERN_UNLOCK_METERS, 0);
        a.palette = pickUnlocked(r, PALETTE_UNLOCK_METERS, 0);
        a.glyph = r.nextBoolean() ? 1 : 0;
        a.frame = 0;
        return a;
    }

    /** @return a random avatar from the given random source, limited to options unlocked at {@code depthM} metres. */
    public static AvatarSpec random(Random r, int depthM) {
        AvatarSpec a = new AvatarSpec();
        a.seed = r.nextInt();
        a.shape = r.nextInt(SHAPES.length);
        a.pattern = pickUnlocked(r, PATTERN_UNLOCK_METERS, depthM);
        a.palette = pickUnlocked(r, PALETTE_UNLOCK_METERS, depthM);
        a.glyph = pickUnlocked(r, GLYPH_UNLOCK_METERS, depthM);
        a.frame = pickUnlocked(r, FRAME_UNLOCK_METERS, depthM);
        return a;
    }

    private static int pickUnlocked(Random r, int[] unlock, int depthM) {
        int count = 0;
        for (int m : unlock) if (m <= depthM) count++;
        int k = r.nextInt(Math.max(1, count));
        for (int i = 0; i < unlock.length; i++) {
            if (unlock[i] <= depthM && k-- == 0) return i;
        }
        return 0;
    }

    /** @return primary palette colour (RGBA8888). */
    public int primaryRgba() {
        return PALETTES[Settings.clamp(palette, 0, PALETTES.length - 1)][0];
    }

    /** @return secondary palette colour (RGBA8888). */
    public int secondaryRgba() {
        return PALETTES[Settings.clamp(palette, 0, PALETTES.length - 1)][1];
    }

    /** Clamps every index into range. */
    public void sanitize() {
        shape = Settings.clamp(shape, 0, SHAPES.length - 1);
        pattern = Settings.clamp(pattern, 0, PATTERNS.length - 1);
        palette = Settings.clamp(palette, 0, PALETTES.length - 1);
        glyph = Settings.clamp(glyph, 0, GLYPHS.length - 1);
        frame = Settings.clamp(frame, 0, FRAMES.length - 1);
    }

    /** Swaps every option not unlocked at {@code depthM} metres for a default one (RESET RECORDS). */
    public void relock(int depthM) {
        sanitize();
        if (PATTERN_UNLOCK_METERS[pattern] > depthM) pattern = 0;
        if (PALETTE_UNLOCK_METERS[palette] > depthM) palette = 0;
        if (GLYPH_UNLOCK_METERS[glyph] > depthM) glyph = 1;
        if (FRAME_UNLOCK_METERS[frame] > depthM) frame = 0;
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
