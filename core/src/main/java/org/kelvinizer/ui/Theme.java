package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.Grades;

/**
 * 全部颜色常量：文字墨色、面板、天空线和地面线的信号色、判定、音符、难度、评级和虹彩色等，以及取派生颜色的辅助方法。
 * 常量绝不能修改；需要改透明度或混色时，写进调用方自己的临时 {@link Color}。
 *
 * <p>Every colour token of DESIGN.md §1.2 and §12.
 *
 * <p>All fields are {@code static final} and must <b>never be mutated</b>. Code that needs a
 * derived colour (alpha change, blend, iridescent cycle) writes into its own scratch
 * {@link Color} through the helper methods below, which always take an {@code out} argument.
 *
 * <p><b>Enum-typed accessors.</b> ARCHITECTURE §3 calls {@code Theme.difficulty(Difficulty)} and
 * judgement colours by enum. Those overloads take {@code Enum<?>} so this package compiles without
 * {@code library} / {@code play}; call them with {@code library.Difficulty} and
 * {@code play.Judgement} exactly as the architecture writes them
 * ({@code Theme.difficulty(Difficulty.DEEP)}, {@code Theme.judgement(j)}). They map by constant
 * name (SURFACE / SHALLOW / DEEP / ABYSS; RESONANCE / ECHO / LOST), so they stay right even if an
 * enum is reordered. The {@code int} overloads take the difficulty ordinal (0 = SURFACE …
 * 3 = ABYSS).
 *
 * <p><b>One numeric source.</b> Judgement, timing-tag and grade colours are defined once as
 * RGBA8888 ints in the pure-Java data packages ({@link Judgement#rgba},
 * {@link Judgement#EARLY_RGBA}, {@link Grades#GRADE_RGBA}, {@link Grades#R_PLUS_CYCLE_RGBA}) and
 * the tokens here are built from them, so a colour change is one edit. Each token is still its
 * own {@link Color} instance (never shared with the data packages).
 */
public final class Theme {
    private Theme() { }

    // ------------------------------------------------------------------ global tokens (§1.2)
    /** Primary text. */
    public static final Color INK1 = rgb(0xEAF2FF, 1f);
    /** Secondary text, artist names. */
    public static final Color INK2 = rgb(0xA9B6D6, 1f);
    /** Tertiary text, disabled. */
    public static final Color INK3 = rgb(0x6C7899, 1f);
    /** Leading zeros of fixed-cell numbers ({@code ink3 @ .40}). */
    public static final Color LEADING_ZERO = rgb(0x6C7899, 0.40f);
    /** Cards, sheets, HUD plates. */
    public static final Color PANEL = rgb(0x0A1033, 0.78f);
    /** Pressed or selected panel, chip backgrounds. */
    public static final Color PANEL_HI = rgb(0x16205A, 0.88f);
    /** Hairlines and panel borders. */
    public static final Color STROKE = rgb(0xFFFFFF, 0.14f);
    /** One-pixel top highlight on panels. */
    public static final Color PANEL_HIGHLIGHT = rgb(0xFFFFFF, 0.07f);
    /** Modal dim. */
    public static final Color SCRIM = rgb(0x02040F, 0.65f);
    /** Modal dim used by the pause menu. */
    public static final Color SCRIM_PAUSE = rgb(0x02040F, 0.70f);
    /** Near-black used for fades from black and text shadows (opaque). */
    public static final Color ABYSS_BLACK = rgb(0x02040F, 1f);
    /** Map label shadow ({@code #02040F @ .6}). */
    public static final Color SHADOW = rgb(0x02040F, 0.6f);
    /** Ground signal: ground line, ground notes, K1, lower curtain panel. */
    public static final Color GROUND_SIG = rgb(0x4FD8FF, 1f);
    /** Sky signal: sky line, sky notes, K2, upper curtain panel. */
    public static final Color SKY_SIG = rgb(0xF2F7FF, 1f);
    /** Curtain seam line. */
    public static final Color SEAM = rgb(0x0A1033, 1f);
    /** Cyan RGB-split copy colour. */
    public static final Color GLITCH_C = rgb(0x00E5FF, 1f);
    /** Magenta RGB-split copy colour. */
    public static final Color GLITCH_M = rgb(0xFF2E88, 1f);
    /** Records, NEW BEST, level, the YOU marker. */
    public static final Color GOLD = rgb(0xFFD27A, 1f);
    /**
     * Shell capsule fill under its {@link #GOLD} wash (map and result corners): a deep gold, nearly opaque, so the
     * map's level title behind it does not show through (Kelvin 2026-10-19 13:59 「光晕加深一点盖过单曲」).
     */
    public static final Color SHELL_CAPSULE = rgb(0x443A2B, 0.88f);
    /** Autoplay tint. */
    public static final Color AUTO = rgb(0x9CF5B0, 1f);
    /** Primary button gradient, top. */
    public static final Color BTN_TOP = rgb(0x6FE3FF, 1f);
    /** Primary button gradient, bottom. */
    public static final Color BTN_BOT = rgb(0x36B9E8, 1f);
    /** Primary button label. */
    public static final Color BTN_LABEL = rgb(0x04142B, 1f);
    /** FULL COMBO badge / banner colour: purple (Kelvin 2026-10-17 16:02); also a full combo's grade (2026-10-19). */
    public static final Color FULL_COMBO = rgb(0xB49CFF, 1f);
    /** ALL RESONANCE badge / banner colour: the light blue of R and R+ (Kelvin 2026-10-17 16:02). */
    public static final Color ALL_RESONANCE = rgb(0xA6DDFF, 1f);
    /** Surface band (map, above 0 m): top. */
    public static final Color SURFACE_TOP = rgb(0x9FD8F0, 1f);
    /** Plain white (convenience, never mutate). */
    public static final Color WHITE = rgb(0xFFFFFF, 1f);
    /** CURTAIN upper panel. */
    public static final Color CURTAIN_TOP = rgb(0x0A1033, 1f);
    /** CURTAIN lower panel. */
    public static final Color CURTAIN_BOTTOM = rgb(0x070C27, 1f);

    // ------------------------------------------------------------------ judgements (§1.2)
    /** RESONANCE (tier 1), from {@link Judgement#RESONANCE}. */
    public static final Color RESONANCE = new Color(Judgement.RESONANCE.rgba);
    /** ECHO (tier 2), from {@link Judgement#ECHO}. */
    public static final Color ECHO = new Color(Judgement.ECHO.rgba);
    /** LOST (miss), from {@link Judgement#LOST}; the word is drawn at α .85. */
    public static final Color LOST = new Color(Judgement.LOST.rgba);
    /** EARLY tag, from {@link Judgement#EARLY_RGBA}. */
    public static final Color EARLY = new Color(Judgement.EARLY_RGBA);
    /** LATE tag, from {@link Judgement#LATE_RGBA}. */
    public static final Color LATE = new Color(Judgement.LATE_RGBA);

    // ------------------------------------------------------------------ note types (§5.9, §5.15)
    /** Mine rim, studs, pulse and shock ring; the result's MINES row (DESIGN §5.15 {@code mine}). */
    public static final Color MINE = rgb(0xFF3344, 1f);
    /** Mine body fill, drawn at α .92 (DESIGN §5.15 {@code mineCore}). */
    public static final Color MINE_CORE = rgb(0x2A0610, 1f);
    /** Universal (TIDE 潮汐) notes, their zone hint and hit sweep (DESIGN §5.15 {@code tide}). */
    public static final Color TIDE = rgb(0x8CFFD9, 1f);
    /**
     * Sky (upper-line) TIDE colour {@code skyTide} = 0.7·{@link #SKY_SIG} + 0.3·{@link #TIDE}
     * (≈ #D3F9F4, DESIGN §21.6): sky TIDE STREAM bands and rails, and the sky RESONANCE TIDE
     * effects (rise/recede, line swell, stream light bands). Ground TIDE keeps {@link #TIDE}.
     */
    public static final Color SKY_TIDE = blend(SKY_SIG, TIDE, 0.3f);
    /** Ground T4 TIDE wave fill, drawn at α .85 (Kelvin 2026-10-12 04:36, 「天键地键tide的改为T4」). */
    public static final Color TIDE_WAVE = rgb(0x1FBF98, 1f);
    /** Ground T4 TIDE wave crest line. */
    public static final Color TIDE_WAVE_CREST = rgb(0xC8FFF0, 1f);
    /** Ground T4 TIDE leading-edge base line. */
    public static final Color TIDE_WAVE_BASE = rgb(0xEAFFF8, 1f);
    /**
     * The LOST word drawn at a touched MINE or LANE MINE contact (Kelvin 2026-10-12: a touched mine
     * shows a red judgement). Redder than the {@link #LOST} pink, so a missed note and a touched
     * mine read differently.
     */
    public static final Color MINE_LOST = rgb(0xFF2A3D, 1f);
    /**
     * Sync pearl body (Kelvin 2026-10-12 12:33 / 12:42: the sync mark is a gold pearl): the three pearls
     * across the middle of a DROP or STREAM head that shares its beat (16:07), the gold sky TIDE pearls and the
     * rows of gold pearls on the ground TIDE wave and the TIDE STREAM head. A deeper gold
     * than {@link #GOLD}, so it reads as gold, not yellow, on cyan and pearl-white heads.
     */
    public static final Color SYNC_PEARL = rgb(0xF0B43C, 1f);
    /**
     * Sync highlight (Kelvin 2026-10-13): the border and halo round a ground DROP, STREAM or TIDE that shares its
     * beat. White; only sky notes keep gold (14:41 「除了天键的用金色以外其他的都用白色高亮吧」).
     */
    public static final Color SYNC_HIGHLIGHT = rgb(0xFFFFFF, 1f);
    /** Sync highlight round sky (white) notes: a deep gold, so the additive border reads gold beside a white body. */
    public static final Color SYNC_HIGHLIGHT_SKY = rgb(0xFF8000, 1f);
    /** Sync highlight of a MINE or LANE MINE: light red (Kelvin 2026-10-13: 「地雷的高亮自然是浅红色的」). */
    public static final Color MINE_HIGHLIGHT = rgb(0xFF8A80, 1f);
    /** Darker-gold rim of a sync pearl, so the pearl keeps its edge on cyan, pearl white and the water. */
    public static final Color SYNC_PEARL_RIM = rgb(0x8A5A12, 1f);
    /** Soft light-gold lustre toward the upper left of a sync pearl. */
    public static final Color SYNC_PEARL_LUSTRE = rgb(0xFFE6A6, 1f);
    /**
     * Red sync pearls, three across the middle of a MINE or LANE MINE head that shares its beat (Kelvin 12:33, 16:07).
     */
    public static final Color MINE_PEARL = rgb(0xFF5A4E, 1f);
    /** Dark-red rim of the red sync pearl. */
    public static final Color MINE_PEARL_RIM = rgb(0x5C0712, 1f);
    /** Soft lustre toward the upper left of the red sync pearl. */
    public static final Color MINE_PEARL_LUSTRE = rgb(0xFFB8AC, 1f);
    /** Missed notes and broken holds desaturate toward it (DESIGN §5.8; same value as {@link #INK3}). */
    public static final Color MISSED = rgb(0x6C7899, 1f);
    /** Head colour of RESONANCE hit sparks, which fade to the line colour (DESIGN §5.9). */
    public static final Color SPARK_HEAD = rgb(0xF2FFFF, 1f);

    // ------------------------------------------------------------------ difficulties (§1.2)
    private static final Color[] DIFF_FILL = {
        rgb(0xF2F7FF, 1f), rgb(0x38B6FF, 1f), rgb(0x2F6BFF, 1f), rgb(0x000000, 1f)};
    private static final Color[] DIFF_TEXT = {
        rgb(0x0B1030, 1f), rgb(0x04142B, 1f), rgb(0xFFFFFF, 1f), rgb(0xFFFFFF, 1f)};
    private static final Color[] DIFF_ACCENT = {
        rgb(0xF2F7FF, 1f), rgb(0x38B6FF, 1f), rgb(0x2F6BFF, 1f), rgb(0xFF2E88, 1f)};
    private static final Color[] DIFF_BORDER = {
        null, null, rgb(0x004AAD, 1f), rgb(0xFF2E88, 1f)};
    private static final float[] DIFF_BORDER_W = {0f, 0f, 2f, 3f};
    /** ABYSS chip: the extra 2 px cyan border copy, offset (+2, 0). */
    public static final Color ABYSS_BORDER_COPY = rgb(0x00E5FF, 1f);
    /** Difficulty display names, indexed by ordinal. */
    public static final String[] DIFFICULTY_NAMES = {"SURFACE", "SHALLOW", "DEEP", "ABYSS"};
    /**
     * Chart constant superscript ("16⁺", {@code library.ConstantLabel}): its size, the lift of its
     * centre above the number's centre and its gap after the number, as fractions of the number's cap height.
     */
    public static final float CONST_MARK_SCALE = 0.6f, CONST_MARK_RISE = 0.3f, CONST_MARK_GAP = 0.06f;

    // ------------------------------------------------------------------ grades (§12, fifteen since 2026-10-17)
    /** Grade strings, best first, as stored in records (the {@link Grades#GRADES} array; never modify). */
    public static final String[] GRADES = Grades.GRADES;
    /** Iridescent cycle stops (the R+ motes, iridescent frame). */
    public static final Color IRIDESCENT_A = new Color(Grades.R_PLUS_CYCLE_RGBA[0]);
    /** Iridescent cycle stop 2. */
    public static final Color IRIDESCENT_B = new Color(Grades.R_PLUS_CYCLE_RGBA[1]);
    /** Iridescent cycle stop 3. */
    public static final Color IRIDESCENT_C = new Color(Grades.R_PLUS_CYCLE_RGBA[2]);
    /** Length of one iridescent cycle in seconds. */
    public static final float IRIDESCENT_PERIOD = 3f;
    /**
     * Seconds from one white sweep through R, and through R+, to the next (Kelvin 2026-10-17 16:02:
     * the light keeps sweeping, on the result stamp and the select screen); R+ runs them back to back.
     */
    public static final float R_SWEEP_PERIOD = 1.4f, R_PLUS_SWEEP_PERIOD = 1f;
    /** Seconds one sweep takes to cross the grade. */
    public static final float SWEEP_D = 1f;

    private static final Color[] GRADE_COLORS = new Color[Grades.GRADES.length];
    private static final int[] GRADE_RINGS = Grades.GRADE_RINGS;

    static {
        for (int i = 0; i < GRADE_COLORS.length; i++) GRADE_COLORS[i] = new Color(Grades.GRADE_RGBA[i]);
    }

    // ------------------------------------------------------------------ factories
    /**
     * Creates a colour from a 24-bit RGB value and an alpha. Only for static token setup or
     * one-off initialisation; never call it per frame.
     */
    public static Color rgb(int rgb, float alpha) {
        return new Color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
    }

    /** A new opaque colour {@code (1 − t)·a + t·b}. Only for static token setup. */
    private static Color blend(Color a, Color b, float t) {
        return new Color(a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t, 1f);
    }

    // ------------------------------------------------------------------ difficulty helpers
    /** Chip fill colour of a difficulty ordinal (0..3). */
    public static Color difficultyFill(int ordinal) {
        return DIFF_FILL[clampDiff(ordinal)];
    }

    /** Chip text colour (on the fill) of a difficulty ordinal. */
    public static Color difficultyText(int ordinal) {
        return DIFF_TEXT[clampDiff(ordinal)];
    }

    /** Accent colour used when the difficulty is selected (card accent, glows). */
    public static Color difficultyAccent(int ordinal) {
        return DIFF_ACCENT[clampDiff(ordinal)];
    }

    /** Chip border colour or {@code null} when the difficulty has no extra border. */
    public static Color difficultyBorder(int ordinal) {
        return DIFF_BORDER[clampDiff(ordinal)];
    }

    /** Chip border width in world units (0 when none). */
    public static float difficultyBorderWidth(int ordinal) {
        return DIFF_BORDER_W[clampDiff(ordinal)];
    }

    /** Difficulty ordinal from its upper-case name; -1 when unknown. */
    public static int difficultyIndex(String name) {
        for (int i = 0; i < DIFFICULTY_NAMES.length; i++) {
            if (DIFFICULTY_NAMES[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static int clampDiff(int i) {
        return i < 0 ? 0 : (i > 3 ? 3 : i);
    }

    // ------------------------------------------------------------------ enum-typed accessors (ARCH §3)
    /**
     * Index (0..3) of a {@code library.Difficulty} constant, matched by name; falls back to the
     * ordinal for unknown names, SURFACE for null. No allocation.
     */
    public static int difficultyIndex(Enum<?> difficulty) {
        if (difficulty == null) return 0;
        String n = difficulty.name();
        for (int i = 0; i < DIFFICULTY_NAMES.length; i++) {
            if (DIFFICULTY_NAMES[i].equals(n)) return i;
        }
        return clampDiff(difficulty.ordinal());
    }

    /**
     * Difficulty colour (the chip fill) of a {@code library.Difficulty}: ARCHITECTURE §3's
     * {@code Theme.difficulty(Difficulty)}.
     */
    public static Color difficulty(Enum<?> difficulty) {
        return DIFF_FILL[difficultyIndex(difficulty)];
    }

    /** Chip fill colour of a {@code library.Difficulty}. */
    public static Color difficultyFill(Enum<?> difficulty) {
        return DIFF_FILL[difficultyIndex(difficulty)];
    }

    /** Chip text colour of a {@code library.Difficulty}. */
    public static Color difficultyText(Enum<?> difficulty) {
        return DIFF_TEXT[difficultyIndex(difficulty)];
    }

    /** Selected accent colour of a {@code library.Difficulty}. */
    public static Color difficultyAccent(Enum<?> difficulty) {
        return DIFF_ACCENT[difficultyIndex(difficulty)];
    }

    /** Chip border colour of a {@code library.Difficulty}, or {@code null} when none. */
    public static Color difficultyBorder(Enum<?> difficulty) {
        return DIFF_BORDER[difficultyIndex(difficulty)];
    }

    /** Chip border width of a {@code library.Difficulty} (0 when none). */
    public static float difficultyBorderWidth(Enum<?> difficulty) {
        return DIFF_BORDER_W[difficultyIndex(difficulty)];
    }

    /** Display name (SURFACE … ABYSS) of a {@code library.Difficulty}. */
    public static String difficultyName(Enum<?> difficulty) {
        return DIFFICULTY_NAMES[difficultyIndex(difficulty)];
    }

    /**
     * Judgement colour of a {@code play.Judgement} constant by name: RESONANCE, ECHO, LOST
     * (unknown names and null give {@link #INK1}).
     */
    public static Color judgement(Enum<?> judgement) {
        if (judgement == null) return INK1;
        String n = judgement.name();
        if ("RESONANCE".equals(n)) return RESONANCE;
        if ("ECHO".equals(n)) return ECHO;
        if ("LOST".equals(n)) return LOST;
        return INK1;
    }

    // ------------------------------------------------------------------ grade helpers
    /** Index of a grade string in {@link #GRADES}; the last (F) when unknown. */
    public static int gradeIndex(String grade) {
        if (grade != null) {
            for (int i = 0; i < GRADES.length; i++) {
                if (GRADES[i].equals(grade)) return i;
            }
        }
        return GRADES.length - 1;
    }

    /** Static grade colour (R and R+ light blue; their gloss is the sweep, {@link #sweep}). */
    public static Color grade(String grade) {
        return GRADE_COLORS[gradeIndex(grade)];
    }

    /**
     * Grade colour written into {@code out}. Steady since 2026-10-17 ({@code time} is unused): R and
     * R+ are light blue and their gloss is a white band drawn over the letters ({@link #sweep}).
     */
    public static Color gradeColor(String grade, float time, Color out) {
        return out.set(GRADE_COLORS[gradeIndex(grade)]);
    }

    /**
     * Colour of grade index {@code g} written into {@code out}; ink1 when unknown. A full combo paints it purple
     * ({@link #FULL_COMBO}, Kelvin 2026-10-19 13:09): the select screen passes whether the chart was ever
     * full-comboed, the result screen whether this play was. R and R+ already mean ALL RESONANCE and stay light blue.
     */
    public static Color gradeColor(int g, boolean fullCombo, Color out) {
        if (g < 0 || g >= GRADE_COLORS.length) return out.set(INK1);
        return out.set(fullCombo && g != Grades.R && g != Grades.R_PLUS ? FULL_COMBO : GRADE_COLORS[g]);
    }

    /**
     * Progress 0..1 of the white band sweeping through grade index {@code g} at {@code time}: R+
     * every {@link #R_PLUS_SWEEP_PERIOD} s, R every {@link #R_SWEEP_PERIOD} s, each crossing in
     * {@link #SWEEP_D} s. −1 between sweeps and for the other grades.
     */
    public static float sweep(int g, float time) {
        if (g != Grades.R_PLUS && g != Grades.R) return -1f;
        float period = g == Grades.R_PLUS ? R_PLUS_SWEEP_PERIOD : R_SWEEP_PERIOD;
        float p = time - MathUtils.floor(time / period) * period;
        return p < SWEEP_D ? p / SWEEP_D : -1f;
    }

    /** Number of stamp rings for a grade (§12). */
    public static int gradeRings(String grade) {
        return GRADE_RINGS[gradeIndex(grade)];
    }

    /**
     * The iridescent cycle {@code #66F0FF → #B49CFF → #FFD27A → #66F0FF} over
     * {@link #IRIDESCENT_PERIOD} seconds, written into {@code out} (alpha 1).
     */
    public static Color iridescent(float time, Color out) {
        float p = time / IRIDESCENT_PERIOD;
        p -= MathUtils.floor(p);
        float seg = p * 3f;
        int i = (int) seg;
        float f = seg - i;
        f = f * f * (3f - 2f * f);
        Color a = i == 0 ? IRIDESCENT_A : (i == 1 ? IRIDESCENT_B : IRIDESCENT_C);
        Color b = i == 0 ? IRIDESCENT_B : (i == 1 ? IRIDESCENT_C : IRIDESCENT_A);
        out.r = a.r + (b.r - a.r) * f;
        out.g = a.g + (b.g - a.g) * f;
        out.b = a.b + (b.b - a.b) * f;
        out.a = 1f;
        return out;
    }

    // ------------------------------------------------------------------ misc helpers
    /** Writes {@code c} with its alpha multiplied by {@code alpha} into {@code out}. */
    public static Color withAlpha(Color c, float alpha, Color out) {
        return out.set(c.r, c.g, c.b, c.a * alpha);
    }

    /** Linear (sRGB-space) blend of two colours into {@code out}. */
    public static Color mix(Color a, Color b, float t, Color out) {
        out.r = a.r + (b.r - a.r) * t;
        out.g = a.g + (b.g - a.g) * t;
        out.b = a.b + (b.b - a.b) * t;
        out.a = a.a + (b.a - a.a) * t;
        return out;
    }

    /** Multiplies the RGB of {@code c} by {@code k} (brightness), clamped, into {@code out}. */
    public static Color brighten(Color c, float k, Color out) {
        out.r = Math.min(1f, c.r * k);
        out.g = Math.min(1f, c.g * k);
        out.b = Math.min(1f, c.b * k);
        out.a = c.a;
        return out;
    }

    /** Background top colour of the depth palette at {@code meters}. */
    public static Color depthTop(float meters, Color out) {
        return DepthPalette.colorAt(meters, DepthPalette.CH_TOP, out);
    }

    /** Background bottom colour of the depth palette at {@code meters}. */
    public static Color depthBottom(float meters, Color out) {
        return DepthPalette.colorAt(meters, DepthPalette.CH_BOTTOM, out);
    }

    /** Layer accent colour at {@code meters}. */
    public static Color depthAccent(float meters, Color out) {
        return DepthPalette.colorAt(meters, DepthPalette.CH_ACCENT, out);
    }
}
