package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 教学关卡的一条定时字幕：按歌曲拍数淡入淡出，位置可以挂在安全边、判定线、HUD 元素或潮汐叠音上。
 * 文字为空的字幕是占位：拍点和位置都留着，但什么都不画。
 * One timed tutorial caption (DESIGN §10.2), ported from the pre-redesign
 * {@code screens/GuidingText}: visible from {@code startBeat} to {@code endBeat} with linear fades
 * measured in song beats (so captions freeze with a pause). A caption with no text is a placeholder
 * (Kelvin 2026-10-21): it keeps its beats and place but draws nothing, no plate either.
 *
 * <p>Changes from the original: world coordinates through the ExtendViewport with an
 * {@link Anchor} (safe edges, judgement lines, the corner HUD elements or the tutorial's TIDE stack,
 * resolved by the screen), pre-split lines whose widths are cached per viewport generation, a
 * {@link Style} per role (instructions on a {@code panel @ .6} plate, headings, story beats in RUBIK,
 * grade lines with the judgement word in its own face and colour, the EARLY / LATE line with both tags
 * in their colours, and the {@code K & K} finale in DISPLAY_SOFT with the LOGO glyph set), and alpha
 * through {@link Fonts#draw} (shared fonts are never mutated).
 *
 * <p><b>Font pages (DESIGN §14).</b> Captions draw from a few shared rasters through
 * {@link Fonts#pushPixelScale}: every UI caption (BODY, QUOTE, NARRATIVE, the GRADE and TIMING
 * descriptions) from the {@link #uiRaster()} page, every UI_BOLD caption (HEADING, LABEL, the TIMING
 * tags) from the {@link #BOLD_RASTER} page, and the GRADE words from the HUD's centre-word page. The
 * tutorial's SKIP and ghost COMBO labels use the same UI page, so a caption frame adds two or three
 * pages, not one per size. {@link #prewarm} builds them (and any CJK glyphs) at load time.
 */
public final class GuidingText {
    /** Visual role of a caption. */
    public enum Style {
        /** Story beats: RUBIK at the given cap (48–72), no plate. */
        STORY,
        /** Narrative sentences: UI at the given cap, no plate. */
        NARRATIVE,
        /** Headings: UI_BOLD 40. */
        HEADING,
        /** Instructions: UI 30 on a {@code panel @ .6} plate. */
        BODY,
        /** Dialogue quotes: UI 30 on a plate. */
        QUOTE,
        /** Small labels beside HUD elements, lines and the TIDE stack: UI_BOLD 26. */
        LABEL,
        /** A grade line: the judgement word in its face and colour, then the description. */
        GRADE,
        /**
         * The EARLY / LATE line: {@code EARLY} in {@link Theme#EARLY}, {@code " / "} in
         * {@link Theme#INK2} and {@code LATE} in {@link Theme#LATE} (UI_BOLD 28, the HUD tag's face),
         * then the description like a grade line.
         */
        TIMING,
        /** The finale: DISPLAY_SOFT with the LOGO glyph set. */
        FINALE
    }

    /** What (x, y) is relative to. The screen resolves non-absolute anchors each frame. */
    public enum Anchor {
        /** Point of the reference 1920×1200 frame, stretched with the playfield ({@link Field}). */
        WORLD,
        /** x relative to the safe left edge. */
        SAFE_LEFT,
        /** x relative to the safe right edge. */
        SAFE_RIGHT,
        /** y relative to the ground line (the offset stretched by ky like any distance from a line). */
        GROUND,
        /** y relative to the sky line (the offset stretched by ky). */
        SKY,
        /** Beside the score, top left (resolved by the screen). */
        HUD_SCORE,
        /** Beside the pause control, top right (resolved by the screen). */
        HUD_PAUSE,
        /** Beside the difficulty chip and title, bottom left (resolved by the screen). */
        HUD_CHIP,
        /** Beside the time readout, bottom right (resolved by the screen). */
        HUD_TIME
    }

    /** Raster cap of the UI_BOLD caption page (HEADING 40, LABEL 26, TIMING tags 28). */
    static final float BOLD_RASTER = 40f;

    private static final float PLATE_PAD_X = 40f;
    private static final float PLATE_PAD_Y = 26f;
    /** Gap between a GRADE word (or the TIMING tags) and the description. */
    private static final float WORD_GAP = 24f;
    /** Cap of the TIMING tags (UI_BOLD, from the {@link #BOLD_RASTER} page). */
    private static final float TAG_CAP = 28f;
    private static final String EARLY_TAG = "EARLY";
    private static final String TAG_SLASH = " / ";
    private static final String LATE_TAG = "LATE";

    private final String[] lines;
    private final float startBeat;
    private final float endBeat;
    private final float fadeInBeats;
    private final float fadeOutBeats;
    private final float x;
    private final float y;
    private final float cap;
    private final Style style;
    private final Anchor anchor;
    private final Judgement grade;
    private final float[] widths;
    /** No text: nothing is drawn or prewarmed. */
    private final boolean placeholder;
    private float gradeWordW;
    private float earlyW;
    private float slashW;
    private float lateW;
    private int generation = -1;

    /** Creates a world-anchored caption; (x, y) is its centre, {@code cap} its cap height. */
    public GuidingText(String content, float startBeat, float endBeat, float fadeInBeats, float fadeOutBeats,
                       float x, float y, float cap) {
        this(content, startBeat, endBeat, fadeInBeats, fadeOutBeats, x, y, cap, Style.BODY, Anchor.WORLD, null);
    }

    /**
     * Creates a caption.
     *
     * @param content lines separated by {@code \n} (split once here)
     * @param x       centre x (absolute, or an offset for SAFE_LEFT / SAFE_RIGHT / HUD anchors)
     * @param y       centre y (absolute, or an offset for GROUND / SKY / HUD anchors)
     * @param cap     cap height (STORY, NARRATIVE, FINALE; other styles use their fixed size)
     * @param grade   the judgement of a {@link Style#GRADE} line, else null
     */
    public GuidingText(String content, float startBeat, float endBeat, float fadeInBeats, float fadeOutBeats,
                       float x, float y, float cap, Style style, Anchor anchor, Judgement grade) {
        this.lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) lines[i] = lines[i].trim();
        this.placeholder = content.trim().isEmpty();
        this.startBeat = startBeat;
        this.endBeat = endBeat;
        this.fadeInBeats = Math.max(1e-3f, fadeInBeats);
        this.fadeOutBeats = Math.max(1e-3f, fadeOutBeats);
        this.x = x;
        this.y = y;
        this.cap = cap;
        this.style = style;
        this.anchor = anchor;
        this.grade = grade;
        this.widths = new float[lines.length];
    }

    /** Opacity 0..1 at {@code beat}. */
    public float alphaAt(float beat) {
        if (beat < startBeat || beat > endBeat + fadeOutBeats) return 0f;
        if (beat < startBeat + fadeInBeats) return (beat - startBeat) / fadeInBeats;
        if (beat <= endBeat) return 1f;
        return 1f - (beat - endBeat) / fadeOutBeats;
    }

    /** @return the anchor (the screen resolves the HUD anchors). */
    public Anchor anchor() {
        return anchor;
    }

    /** @return the style. */
    public Style style() {
        return style;
    }

    /** @return the x value as given (absolute or offset). */
    public float x() {
        return x;
    }

    /** @return the y value as given (absolute or offset). */
    public float y() {
        return y;
    }

    /** @return the first beat of the caption. */
    public float startBeat() {
        return startBeat;
    }

    /** @return the last fully visible beat. */
    public float endBeat() {
        return endBeat;
    }

    /** @return the grade of a GRADE line, or null. */
    public Judgement grade() {
        return grade;
    }

    /** @return the caption text (lines joined with {@code \n}; tests and probes). */
    public String text() {
        return String.join("\n", lines);
    }

    /** @return the fade-in length in beats. */
    public float fadeInBeats() {
        return fadeInBeats;
    }

    /** @return the fade-out length in beats. */
    public float fadeOutBeats() {
        return fadeOutBeats;
    }

    private Fonts.Role role() {
        switch (style) {
            case STORY:
                return Fonts.Role.RUBIK;
            case HEADING:
            case LABEL:
                return Fonts.Role.UI_BOLD;
            case FINALE:
                return Fonts.Role.DISPLAY_SOFT;
            default:
                return Fonts.Role.UI;
        }
    }

    /** Cap height actually used (fixed per style, at least {@link Ui#minEssentialCap()} for text to read). */
    private float capFor() {
        switch (style) {
            case HEADING:
                return 40f;
            case BODY:
            case QUOTE:
                return Math.max(30f, Ui.minEssentialCap());
            case LABEL:
                return 26f;
            case GRADE:
            case TIMING:
                return 28f;
            default:
                return cap;
        }
    }

    private Fonts.GlyphSet set() {
        return style == Style.FINALE ? Fonts.GlyphSet.LOGO : Fonts.GlyphSet.FULL;
    }

    /**
     * Raster cap of the tutorial's UI page: BODY / QUOTE / NARRATIVE captions, the GRADE and TIMING
     * descriptions and the SKIP / ghost COMBO labels (all at or below it, so they only downscale).
     */
    static float uiRaster() {
        return Math.max(34f, Ui.minEssentialCap());
    }

    /** The raster cap a caption of {@code role} at {@code c} draws from ({@code c} itself: its own page). */
    private static float rasterFor(Fonts.Role role, float c) {
        if (role == Fonts.Role.UI && c <= uiRaster()) return uiRaster();
        if (role == Fonts.Role.UI_BOLD && c <= BOLD_RASTER) return BOLD_RASTER;
        return c;
    }

    /** Points the next font calls at cap {@code c} to the page of {@code raster} (no-op when equal). */
    private static void raster(Fonts fonts, float c, float raster) {
        if (raster != c) fonts.pushPixelScale(Ui.pxPerWorld() * raster / c);
    }

    private static void unraster(Fonts fonts, float c, float raster) {
        if (raster != c) fonts.popPixelScale();
    }

    /** Width of the word part (GRADE word or TIMING tags) before the description, 0 for other styles. */
    private float wordPartW() {
        if (grade != null) return gradeWordW;
        if (style == Style.TIMING) return earlyW + slashW + lateW;
        return 0f;
    }

    private void measure(Fonts fonts) {
        if (generation == Ui.generation()) return;
        generation = Ui.generation();
        float c = capFor();
        float r = rasterFor(role(), c);
        raster(fonts, c, r);
        for (int i = 0; i < lines.length; i++) widths[i] = fonts.width(role(), c, set(), lines[i]);
        unraster(fonts, c, r);
        if (grade != null) {
            float wr = GameplayHud.wordRaster();
            raster(fonts, 34f, wr);
            gradeWordW = fonts.width(GameplayHud.wordRole(grade), 34f, grade.display);
            unraster(fonts, 34f, wr);
        }
        if (style == Style.TIMING) {
            float br = rasterFor(Fonts.Role.UI_BOLD, TAG_CAP);
            raster(fonts, TAG_CAP, br);
            earlyW = fonts.width(Fonts.Role.UI_BOLD, TAG_CAP, EARLY_TAG);
            slashW = fonts.width(Fonts.Role.UI_BOLD, TAG_CAP, TAG_SLASH);
            lateW = fonts.width(Fonts.Role.UI_BOLD, TAG_CAP, LATE_TAG);
            unraster(fonts, TAG_CAP, br);
        }
    }

    /**
     * Builds this caption's font pages and glyphs now (load time), so the first frame that shows
     * it never generates a FreeType page or CJK glyph mid-song.
     */
    void prewarm(Fonts fonts) {
        if (placeholder) return;
        generation = -1;
        measure(fonts);
        if (grade != null || style == Style.TIMING) {
            float r = rasterFor(Fonts.Role.UI, capFor());
            raster(fonts, capFor(), r);
            fonts.width(Fonts.Role.UI, capFor(), lines[0]);
            unraster(fonts, capFor(), r);
        }
    }

    /** @return the widest line in world units (after the first draw or measure). */
    public float width(Fonts fonts) {
        measure(fonts);
        float w = 0f;
        for (float v : widths) w = Math.max(w, v);
        float word = wordPartW();
        if (word > 0f) w += word + WORD_GAP;
        return w;
    }

    /** @return the height of the caption's text block in world units (cap of one line, plus line gaps). */
    public float height() {
        float c = capFor();
        return (lines.length - 1) * c * (style == Style.FINALE ? 1.2f : 1.6f) + c;
    }

    /**
     * Draws the caption centred on the resolved point (cx, cy) at {@code beat} (the batch must be
     * drawing, normal blend).
     *
     * @return the caption alpha (0 when hidden)
     */
    public float draw(Batch batch, Fonts fonts, Draw draw, float beat, float cx, float cy) {
        float a = alphaAt(beat);
        if (a <= 0.003f || placeholder) return 0f;
        measure(fonts);
        float c = capFor();
        float lh = c * (style == Style.FINALE ? 1.2f : 1.6f);
        float total = (lines.length - 1) * lh;
        if (style == Style.BODY || style == Style.QUOTE) {
            float w = 0f;
            for (float v : widths) w = Math.max(w, v);
            float pw = w + 2f * PLATE_PAD_X;
            float ph = total + c + 2f * PLATE_PAD_Y;
            draw.panel(cx - pw * 0.5f, cy - ph * 0.5f, pw, ph, 18f, 0.77f * a);
        }
        Color color = style == Style.QUOTE ? Theme.INK1 : style == Style.NARRATIVE ? Theme.INK2 : Theme.INK1;
        if ((style == Style.GRADE && grade != null) || style == Style.TIMING) {
            float word = wordPartW();
            float left = cx - (word + WORD_GAP + widths[0]) * 0.5f;
            if (grade != null) {
                float wr = GameplayHud.wordRaster();
                raster(fonts, 34f, wr);
                fonts.draw(batch, GameplayHud.wordRole(grade), 34f, grade.display, left, cy,
                    Align.left | Fonts.VCENTER, grade.color(), a * (grade == Judgement.LOST ? 0.85f : 1f));
                unraster(fonts, 34f, wr);
            } else {
                float br = rasterFor(Fonts.Role.UI_BOLD, TAG_CAP);
                raster(fonts, TAG_CAP, br);
                int al = Align.left | Fonts.VCENTER;
                fonts.draw(batch, Fonts.Role.UI_BOLD, TAG_CAP, EARLY_TAG, left, cy, al, Theme.EARLY, a);
                fonts.draw(batch, Fonts.Role.UI_BOLD, TAG_CAP, TAG_SLASH, left + earlyW, cy, al, Theme.INK2, a);
                fonts.draw(batch, Fonts.Role.UI_BOLD, TAG_CAP, LATE_TAG, left + earlyW + slashW, cy, al, Theme.LATE, a);
                unraster(fonts, TAG_CAP, br);
            }
            float r = rasterFor(Fonts.Role.UI, c);
            raster(fonts, c, r);
            fonts.draw(batch, Fonts.Role.UI, c, lines[0], left + word + WORD_GAP, cy, Align.left | Fonts.VCENTER,
                Theme.INK1, a);
            unraster(fonts, c, r);
            return a;
        }
        float top = cy + total * 0.5f;
        float r = rasterFor(role(), c);
        raster(fonts, c, r);
        for (int i = 0; i < lines.length; i++) {
            fonts.draw(batch, role(), c, set(), lines[i], cx, top - i * lh, Align.center | Fonts.VCENTER, color, a);
        }
        unraster(fonts, c, r);
        return a;
    }

    /** Draws a world-anchored caption at its own point (compatibility with the stub API). */
    public void draw(Batch batch, Fonts fonts, float beat) {
        float a = alphaAt(beat);
        if (a <= 0f || placeholder) return;
        float c = capFor();
        float lh = c * 1.6f;
        float top = y + (lines.length - 1) * lh * 0.5f;
        for (int i = 0; i < lines.length; i++) {
            fonts.draw(batch, role(), c, set(), lines[i], x, top - i * lh, Align.center | Fonts.VCENTER, Theme.INK1, a);
        }
    }

    /** Left x of the word (GRADE) or tags (TIMING) of a line drawn centred on {@code cx} (demo effect anchor). */
    public float gradeLeft(Fonts fonts, float cx) {
        return cx - width(fonts) * 0.5f;
    }
}
