package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.library.ConstantLabel;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.play.ScoreKeeper;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 游玩界面的 HUD：分数和准确率在左上角，暂停键在右上角，难度标签和曲名在左下角，剩余时间在右下角；
 * 连击数和判定字仍在屏幕中央。四角的 HUD 最后绘制，光柱和按住的长条都盖不住它。
 *
 * <p>Persistent gameplay HUD in the screen corners (owner decisions 2026-10-12; DESIGN §21.5, which
 * they override where they differ, §5.3, §5.5, §5.6): score and accuracy top-left, the pause
 * control top-right ({@link PauseControl}), difficulty chip and song title bottom-left, time
 * remaining bottom-right, each anchored to the {@code Ui.safe*()} edges at every aspect (16:9, 20:9,
 * 16:10, 3:2, 4:3); the progress hairline runs along the top edge. Score and accuracy are drawn at
 * {@link #CORNER_SCALE} of their §5.3 size (the accuracy never below the 20-unit playfield minimum
 * of DESIGN §1.1). Combo, judgement word and the hit error meter stay in the middle band. The band
 * layout (live counters centred in a free bottom band, formerly 3:2 and 4:3) no longer switches on:
 * the playfield is stretched to fill the screen ({@link Field}, owner 2026-10-12 16:17), so there is
 * no free band ({@link Ui#useBandHud}). The corner HUD itself is not stretched. Score, accuracy, chip,
 * title and time are display-only: every touch on them goes to gameplay.
 *
 * <p><b>Guard (§5.1).</b> At 16:9 the corners sit inside the outer judgement zones by design, so a
 * corner element fades to α .15 (120 ms) only while a judgement line itself, moved by chart events,
 * comes within {@link #LINE_GUARD} of it inside the lane window, or while an at-lane word overlaps
 * it. Combo, word, meter and counters keep the zone-overlap rule.
 *
 * <p><b>Behaviour (§5.5, §18.1).</b> The score ({@link #SCORE_DIGITS} digits, grouped, leading
 * zeros dim: {@code 000,000,000}; it only ever rises) eases to each new value in 120 ms and its
 * changed digit cells pop; with FC / AR INDICATOR on (Kelvin 2026-10-18 16:17) it is light blue while every
 * judgement so far is RESONANCE, purple while the combo holds through ECHOs and white once it broke (a LOST or
 * a touched mine), as {@link ScoreKeeper#isFullComboSoFar()} says; a PERFECT RESONANCE
 * (|Δt| ≤ {@link Judgement#PERFECT_MS}) draws a 2 px
 * glint under the central word (P1) and the hit error meter shades the ±33 ms band one step
 * brighter than the ±66 ms band (P1); the combo punches on every increment, bursts every 100
 * and breaks with a G2-red flicker. The first counted tap on the pause control plays a 300 ms
 * hint ({@link #pauseHint}): the icon brightens, a cyan arc drains with the double-tap window and
 * a thin ring pulses out; while paused the ring turns cyan. Digits are cached glyph cells
 * ({@link Fonts#drawDigits}); strings are rebuilt only when their value changes. No allocation per
 * frame.
 *
 * <p><b>Draw passes.</b> {@link #drawUnder} (normal block A: meter frame, glint) and
 * {@link #drawUnderText} (HUD-under text: combo, word) sit under the notes; {@link #drawCorners}
 * (shapes: hairline, chip plate, pause icon, hint, hold ring) and {@link #drawCornerText} (score,
 * accuracy, chip text, title, time, counters) run last, after the additive block and above the
 * pause scrim, so lane glows and held bodies never cover the corners.
 *
 * <p><b>Font pages (DESIGN §14).</b> Every HUD text comes from one of five rasters, whatever
 * its drawn size: NUMBER digits at the score cap (score, accuracy, time, chip constant), NUMBER
 * digits at the combo cap, UI at 20 (title, and the COMBO / AUTOPLAY labels drawn as UI with
 * the UI_LABEL tracking), UI_BOLD at 22 (chip name, EARLY/LATE, live counters) and the current
 * judgement word's face. Smaller sizes are drawn from the shared raster through
 * {@link Fonts#pushPixelScale}; the screen records the text with a {@link DeferredBatch} so each
 * page is one draw call per pass.
 */
final class GameplayHud {
    // ------------------------------------------------------------------ corner layout (HUD scale 1)
    /** Size of the score and the accuracy against their §5.3 size (owner: 80 %). */
    static final float CORNER_SCALE = 0.80f;
    /** §5.3 score cap (NUMBER 40), drawn at {@link #CORNER_SCALE}. */
    static final float SCORE_CAP = 40f;
    /** §5.3 accuracy cap (NUMBER 24), drawn at {@link #CORNER_SCALE}. */
    static final float ACC_CAP = 24f;
    /** Smallest cap of text over the moving playfield (DESIGN §1.1); floors the accuracy. */
    static final float PLAYFIELD_MIN_CAP = 20f;
    /** Gap between the score's baseline and the accuracy's cap top. */
    static final float ACC_GAP = 12f;
    /** AUTOPLAY label cap (UI_LABEL 20, §5.3), in place of the accuracy. */
    static final float AUTO_CAP = 20f;
    /** Difficulty chip height (§5.3). */
    static final float CHIP_H = 40f;
    /** Chip text cap (UI_BOLD name and NUMBER constant, §5.3). */
    static final float CHIP_CAP = 18f;
    /** Chip side padding, name-to-constant gap and corner radius. */
    static final float CHIP_PAD = 14f;
    static final float CHIP_GAP = 10f;
    static final float CHIP_RADIUS = 10f;
    /** Title cap (UI 20), gap after the chip and widest title (§5.3). */
    static final float TITLE_CAP = 20f;
    static final float TITLE_GAP = 16f;
    static final float TITLE_MAX = 560f;
    /** Time cap (NUMBER 20, §5.3) and the least gap kept between the title and the time. */
    static final float TIME_CAP = 20f;
    static final float TIME_GAP = 32f;
    /** Progress hairline (§5.3): this far below the top edge, this thick (16:9: y 1074–1077). */
    static final float PROGRESS_DROP = 6f;
    static final float PROGRESS_H = 3f;
    /**
     * A judgement line closer than this to a corner element fades it (guard, §5.1): about half a
     * DROP's height, so notes landing on a moved line would touch the text. Below the 11.45 left
     * between the 16:9 sky line and the pause ring at UI scale 1.3, so the default lines never fade
     * a corner at any UI scale.
     */
    static final float LINE_GUARD = 8f;
    /** Hit error meter centre y (§5.3; the meter stays in the middle band at every aspect). */
    static final float METER_Y = 380f;
    /** The double-tap demo's second tap after its first (s). */
    static final float DEMO_SECOND_TAP = 0.18f;

    // ------------------------------------------------------------------ element rects (getters)
    /** Score + accuracy (or AUTOPLAY) cluster, top-left. */
    static final int EL_SCORE = 0;
    /** Pause icon ring, top-right. */
    static final int EL_PAUSE = 1;
    /** Difficulty chip + title, bottom-left. */
    static final int EL_CHIP = 2;
    /** Time remaining, bottom-right. */
    static final int EL_TIME = 3;
    /** Progress hairline along the top edge. */
    static final int EL_PROGRESS = 4;
    /** Live counters (band layout only; empty otherwise). */
    static final int EL_COUNTERS = 5;
    /** Pause control hit box. */
    static final int EL_PAUSE_BOX = 6;
    /** Number of element rects. */
    static final int EL_COUNT = 7;

    private static final float SCORE_EASE = 0.12f;
    private static final float POP_SEC = 0.09f;
    private static final float INC_SEC = 0.16f;
    private static final float BIG_SEC = 0.40f;
    private static final float BIG_GLITCH_SEC = 0.30f;
    private static final float BREAK_FLICKER = 2f / 60f;
    private static final float BREAK_FADE = 0.30f;
    private static final float WORD_SEC = 0.42f;
    private static final float GUARD_A = 0.15f;
    private static final float GUARD_SEC = 0.12f;
    private static final int METER_N = 12;
    private static final float METER_MS = Judgement.TIER2_MS;
    /** Digits of the HUD score (DESIGN §18.1: 9, grouped; up to 100,010,000 + the PERFECT bonus). */
    static final int SCORE_DIGITS = 9;
    /** Cells of the HUD score: the digits plus their group commas. */
    private static final int SCORE_CELLS = SCORE_DIGITS + (SCORE_DIGITS - 1) / 3;
    /** PERFECT glint sweep (DESIGN §18.1). */
    private static final float GLINT_SEC = 0.09f;
    /** Glint trail colour: the gradient starts at the settled trail's alpha (.35 of the head's .95), no seam. */
    private static final Color TRAIL_WHITE = new Color(1f, 1f, 1f, 0.35f / 0.95f);
    /** The widest accuracy text, for the score cluster's width. */
    private static final String ACC_WIDEST = "100.00%";

    private final Fonts fonts;
    private final Draw draw;
    private final GlitchText glitch;
    private final ScaledGlitch digits;
    private final FeedbackLayer feedback;
    private final GlitchState wordState;
    private final BitmapFontAdvance advance = new BitmapFontAdvance();
    private final PauseControl pause = new PauseControl();

    // run info
    private boolean autoplay;
    /** Corners show only the score and the accuracy (the GAMEPLAY VISUALS preview, {@link PreviewRun}). */
    private boolean scoreOnly;
    private Settings settings = new Settings();
    private String diffName = "";
    private String constText = "";
    private String title = "";
    private Color diffFill = Theme.INK3;
    private Color diffText = Theme.INK1;

    // layout
    private boolean band;
    private float k = 1f;
    private float sl;
    private float sr;
    private float scoreCap;
    private float scoreW;
    private float scoreTop;
    private float accCap;
    private float accTop;
    private float accW;
    private boolean constDigits;
    /** The constant's superscript mark ({@code ConstantLabel}; {@link #constText} is its whole number) and the number's width. */
    private String constMark = "";
    private float constW;
    private float chipX;
    private float chipY;
    private float chipH;
    private float chipW;
    private float chipNameW;
    private float titleX;
    private float titleMax;
    private float titleW;
    private float timeW;
    private float timeMaxW;
    private float progY;
    private float countersY;
    private float bandBottomY;
    /** Viewport generation and HUD scale the text widths were measured for. */
    private int measuredGen = -1;
    private float measuredK = -1f;
    /** Pause control hit box (field names read by the screen harnesses). */
    private float pauseX;
    private float pauseY;
    private float pauseW;
    private float pauseH;
    /** x0, y0, x1, y1 of each {@code EL_*} element. */
    private final float[] rects = new float[EL_COUNT * 4];

    // score
    private int scoreTarget;
    /** Ease start (long: a float steps by 4–8 above 2^24, the 100 M scale, DESIGN §18.1). */
    private long scoreFrom;
    private float scoreT = 1f;
    private long scoreShown;
    /** FC / AR INDICATOR state of the score colour: none (white), FULL COMBO with ECHOs (purple), all RESONANCE. */
    private static final int FC_NONE = 0, FC_ECHO = 1, FC_AR = 2;
    private int fcState = FC_NONE;
    private final float[] cellPop = new float[SCORE_CELLS];
    private final float[] cellScale = new float[SCORE_CELLS];

    // text caches
    private final StringBuilder accSb = new StringBuilder(16);
    private int accJudged = -1;
    private final StringBuilder timeSb = new StringBuilder(8);
    private int timeSec = -1;
    private final StringBuilder comboSb = new StringBuilder(16);
    private final StringBuilder[] cntSb = {new StringBuilder(16), new StringBuilder(16), new StringBuilder(16)};
    private final float[] cntW = new float[3];
    private final int[] cntVal = {-1, -1, -1};
    private float sepW;

    // combo
    private int lastCombo;
    private float incT = -10f;
    private float bigT = -10f;
    private int bigSeed;
    private int breakValue;
    private float breakT = -10f;
    private boolean mineBreak;

    // word
    private Judgement word;
    /** The central word is a mine's red LOST ({@link #mineWord}). */
    private boolean wordMine;
    private float wordT = -10f;
    private boolean wordRedFired;
    private float wordW;
    /** Time of the last PERFECT shown as the central word (−10 = none) and whether the word drew last frame. */
    private float glintT = -10f;
    private boolean centreDrawn;
    private final StringBuilder tagSb = new StringBuilder(24);
    private boolean tagShown;
    private Color tagColor = Theme.EARLY;

    // hit error meter
    private final float[] meterDt = new float[METER_N];
    private final float[] meterT = new float[METER_N];
    private final Judgement[] meterJ = new Judgement[METER_N];
    private final float[] sortBuf = new float[METER_N];
    private int meterNext;

    // pause hint (run seconds; −10 = none)
    private float hintT = -10f;
    private float demoT = -10f;
    private float lastNow;

    // guard alphas
    private float gPause = 1f;
    private float gScore = 1f;
    private float gChip = 1f;
    private float gTime = 1f;
    private float gCombo = 1f;
    /** 0..1 share of the centre taken by the end banner; the combo fades out under it. */
    private float centreCover;
    private float gWord = 1f;
    private float gMeter = 1f;
    private float gCounters = 1f;

    /** Creates the HUD over the shared services. */
    GameplayHud(Fonts fonts, Draw draw, GlitchText glitch, FeedbackLayer feedback) {
        this.fonts = fonts;
        this.draw = draw;
        this.glitch = glitch;
        this.digits = new ScaledGlitch(fonts, draw);
        this.feedback = feedback;
        this.wordState = glitch.obtainState();
        java.util.Arrays.fill(cellScale, 1f);
    }

    /** Resets the HUD for a new run (load time). */
    void begin(String title, Difficulty diff, String constantText, boolean autoplay, Settings settings) {
        this.title = title != null ? title : "";
        this.autoplay = autoplay;
        this.settings = settings;
        diffName = diff != null ? Theme.difficultyName(diff) : "";
        constText = constantText != null ? ConstantLabel.base(constantText) : "";
        constMark = ConstantLabel.mark(constantText);
        constDigits = digitsOnly(constText);
        diffFill = diff != null ? Theme.difficultyFill(diff) : Theme.INK3;
        diffText = diff != null ? Theme.difficultyText(diff) : Theme.INK1;
        scoreTarget = 0;
        scoreFrom = 0L;
        scoreT = 1f;
        scoreShown = 0;
        java.util.Arrays.fill(cellPop, -10f);
        java.util.Arrays.fill(cellScale, 1f);
        accJudged = -1;
        accSb.setLength(0);
        timeSec = -1;
        timeSb.setLength(0);
        java.util.Arrays.fill(cntVal, -1);
        lastCombo = 0;
        incT = -10f;
        bigT = -10f;
        breakT = -10f;
        mineBreak = false;
        word = null;
        wordT = -10f;
        glintT = -10f;
        centreDrawn = false;
        java.util.Arrays.fill(meterT, -100f);
        java.util.Arrays.fill(meterJ, null);
        meterNext = 0;
        hintT = -10f;
        demoT = -10f;
        lastNow = 0f;
        pause.reset();
        gPause = gScore = gChip = gTime = gCombo = gWord = gMeter = gCounters = 1f;
        wordState.reset();
        measuredGen = -1;
        prewarm();
    }

    /**
     * Generates every font page the running HUD may draw (load time, may allocate), so the first
     * combo, judgement word, EARLY/LATE tag or glitch face swap never builds a FreeType page
     * mid-song (§14: no allocation and no hitch while a song plays).
     */
    private void prewarm() {
        layout();
        Fonts.GlyphSet dig = Fonts.GlyphSet.DIGITS;
        Fonts.GlyphSet full = Fonts.GlyphSet.FULL;
        Fonts.GlyphSet words = Fonts.GlyphSet.WORDS;
        // the five HUD rasters (see the class comment)
        fonts.prewarm(Fonts.Role.NUMBER, numRaster(), dig);
        float combo = 96f * k;
        fonts.prewarm(Fonts.Role.NUMBER, combo, dig);
        fonts.prewarm(Fonts.Role.UI, uiRaster(), full);
        fonts.prewarm(Fonts.Role.UI_BOLD, boldRaster(), full);
        // judgement words, centre (cap 32) and at lane (cap 22); both sets are small, and warming
        // both keeps a mid-run change of the display mode hitch-free too
        float word = 32f * k;
        fonts.prewarm(Fonts.Role.DISPLAY_SOFT, word, full);
        fonts.prewarm(Fonts.Role.DISPLAY_SOFT, 22f, words);
    }

    // ------------------------------------------------------------------ shared rasters

    /** Raster cap of the small NUMBER page (the score's own cap). */
    private float numRaster() {
        return scoreCap;
    }

    /** Raster cap of the UI page. */
    private float uiRaster() {
        return 20f * k;
    }

    /** Raster cap of the UI_BOLD page. */
    private float boldRaster() {
        return 22f * k;
    }

    /** Makes the next font calls at cap {@code cap} use the font generated for cap {@code raster}. */
    private void raster(float cap, float raster) {
        fonts.pushPixelScale(Ui.pxPerWorld() * raster / cap);
    }

    private void unraster() {
        fonts.popPixelScale();
    }

    /** Fixed-cell digits from the small NUMBER page. */
    private void numFixed(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, float a) {
        if (text.length() == 0 || a <= 0.003f) return;
        raster(cap, numRaster());
        fonts.drawFixed(b, Fonts.Role.NUMBER, cap, text, x, y, align, c, a);
        unraster();
    }

    private float numFixedWidth(CharSequence text, float cap) {
        raster(cap, numRaster());
        float w = fonts.fixedWidth(Fonts.Role.NUMBER, cap, text);
        unraster();
        return w;
    }

    /** UI_BOLD text from the shared UI_BOLD page; returns its width. */
    private float bold(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, float a) {
        if (text.length() == 0) return 0f;
        raster(cap, boldRaster());
        float w = fonts.draw(b, Fonts.Role.UI_BOLD, cap, text, x, y, align, c, a);
        unraster();
        return w;
    }

    private float boldWidth(CharSequence text, float cap) {
        raster(cap, boldRaster());
        float w = fonts.width(Fonts.Role.UI_BOLD, cap, text);
        unraster();
        return w;
    }

    /**
     * Caps label in the UI_LABEL look (Chakra SemiBold, tracking {@code 0.10 × font size}) drawn
     * letter by letter from the UI page, so it costs no page of its own.
     */
    void label(Batch b, String[] letters, float cap, float x, float y, int align, Color c, float a) {
        label(b, letters, cap, uiRaster(), x, y, align, c, a);
    }

    /**
     * {@link #label(Batch, String[], float, float, float, int, Color, float)} from the UI page of cap
     * {@code raster}.
     */
    void label(Batch b, String[] letters, float cap, float raster, float x, float y, int align, Color c, float a) {
        if (a <= 0.003f) return;
        raster(cap, raster);
        float w = advance.measure(fonts, letters, cap);
        float left = (align & Align.left) != 0 ? x : (align & Align.right) != 0 ? x - w : x - w * 0.5f;
        int v = align & (Align.top | Fonts.VCENTER);
        for (int i = 0; i < letters.length; i++) {
            fonts.draw(b, Fonts.Role.UI, cap, letters[i], left, y, Align.left | v, c, a);
            left += advance.step(i);
        }
        unraster();
    }

    /** Width of a {@link #label} at {@code cap} from the UI page. */
    private float labelWidth(String[] letters, float cap) {
        raster(cap, uiRaster());
        float w = advance.measure(fonts, letters, cap);
        unraster();
        return w;
    }

    /** "COMBO" split into cached one-letter strings for {@link #label}. */
    static final String[] COMBO = letters("COMBO");
    /** "AUTOPLAY" split for {@link #label}. */
    static final String[] AUTOPLAY = letters("AUTOPLAY");
    // the dive stays English in 中文 too, like the judgement words (Kelvin 2026-10-18 16:41: 「early late就不要改成早，晚了，
    // 然后combo就不要改成连击了，autoplay也不要改成自动演示了」); from 03:1x until then these labels were Chinese

    /** Splits a label into one-letter strings (load time). */
    static String[] letters(String s) {
        String[] out = new String[s.length()];
        for (int i = 0; i < out.length; i++) out[i] = s.substring(i, i + 1);
        return out;
    }

    /**
     * Per-letter advances of a tracked label (no allocation after the first label of a given
     * length).
     */
    private static final class BitmapFontAdvance {
        private float[] steps = new float[16];

        float measure(Fonts fonts, String[] letters, float cap) {
            if (steps.length < letters.length) steps = new float[letters.length];
            com.badlogic.gdx.graphics.g2d.BitmapFont f = fonts.get(Fonts.Role.UI, cap);
            com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData d = f.getData();
            float track = 0.10f * cap / Fonts.Role.UI.capRatio;
            float w = 0f;
            for (int i = 0; i < letters.length; i++) {
                com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph g = d.getGlyph(letters[i].charAt(0));
                float adv = g != null ? g.xadvance * d.scaleX : cap * 0.6f;
                steps[i] = adv + track;
                w += i < letters.length - 1 ? adv + track : adv;
            }
            return w;
        }

        float step(int i) {
            return steps[i];
        }
    }

    // ================================================================== layout

    /** Recomputes the corner anchors for the current viewport (cheap; every frame). */
    private void layout() {
        band = Ui.useBandHud();
        k = MathUtils.clamp(Ui.uiScale(), 0.9f, 1.3f);
        sl = Ui.safeLeft();
        sr = Ui.safeRight();
        float st = Ui.safeTop();
        float sb = Ui.safeBottom();
        pause.layout();
        pauseX = pause.boxX();
        pauseY = pause.boxY();
        pauseW = pause.boxW();
        pauseH = pause.boxH();
        scoreCap = SCORE_CAP * CORNER_SCALE * k;
        accCap = Math.max(ACC_CAP * CORNER_SCALE * k, PLAYFIELD_MIN_CAP);
        chipH = CHIP_H * k;
        if (measuredGen != Ui.generation() || measuredK != k) measure();
        // top-left: score, accuracy (or AUTOPLAY) under it
        scoreTop = st;
        accTop = st - scoreCap - ACC_GAP * k;
        // bottom-left: chip on the safe bottom, title after it; bottom-right: time on the chip's row
        chipX = sl;
        chipY = sb;
        titleX = chipX + chipW + TITLE_GAP * k;
        titleMax = Math.max(0f, Math.min(TITLE_MAX * k, sr - timeMaxW - TIME_GAP * k - titleX));
        // the hairline along the top edge (visible edge less cutout and edge padding)
        progY = pause.edgeTop() - PROGRESS_DROP;
        float bottom = Math.max(Ui.bottom(), sb - 24f);
        bandBottomY = (bottom + 12f) * 0.5f;
        countersY = bandBottomY;
        updateRects();
    }

    /** Widths that depend on the fonts (once per viewport generation and HUD scale, or run). */
    private void measure() {
        measuredGen = Ui.generation();
        measuredK = k;
        scoreW = fonts.digitsWidth(Fonts.Role.NUMBER, scoreCap, SCORE_DIGITS, true);
        accW = autoplay ? labelWidth(AUTOPLAY, AUTO_CAP * k) : numFixedWidth(ACC_WIDEST, accCap);
        chipNameW = boldWidth(diffName, CHIP_CAP * k);
        constW = constText.length() == 0 ? 0f : constDigits
            ? numFixedWidth(constText, CHIP_CAP * k)
            : fonts.width(Fonts.Role.NUMBER, CHIP_CAP * k, constText);
        float cw = constW;
        if (cw > 0f && constMark.length() > 0) {
            float mc = CHIP_CAP * k * Theme.CONST_MARK_SCALE;
            raster(mc, numRaster());
            cw += CHIP_CAP * k * Theme.CONST_MARK_GAP + fonts.width(Fonts.Role.NUMBER, mc, constMark);
            unraster();
        }
        chipW = diffName.length() == 0 ? 0f : CHIP_PAD * k + chipNameW + (cw > 0f ? CHIP_GAP * k + cw : 0f) + CHIP_PAD * k;
        titleW = title.length() == 0 ? 0f : fonts.width(Fonts.Role.UI, TITLE_CAP * k, title);
        timeMaxW = numFixedWidth("00:00", TIME_CAP * k);
        timeW = timeSb.length() == 0 ? 0f : numFixedWidth(timeSb, TIME_CAP * k);
    }

    private void updateRects() {
        float accH = autoplay ? AUTO_CAP * k : accCap;
        setRect(EL_SCORE, sl, accTop - accH, sl + Math.max(scoreW, accW), scoreTop);
        float rr = pause.radius() + pause.stroke() * 0.5f;
        setRect(EL_PAUSE, pause.cx() - rr, pause.cy() - rr, pause.cx() + rr, pause.cy() + rr);
        setRect(EL_CHIP, chipX, chipY, titleRight(), chipY + chipH);
        float cy = chipY + chipH * 0.5f;
        float th = TIME_CAP * k * 0.5f;
        setRect(EL_TIME, sr - timeW, cy - th, sr, cy + th);
        setRect(EL_PROGRESS, Ui.left(), progY, Ui.right(), progY + PROGRESS_H);
        if (band && settings.liveCounters && cntVal[0] >= 0) {
            float total = cntW[0] + cntW[1] + cntW[2] + 2f * sepW;
            float ch = 22f * k * 0.5f;
            setRect(EL_COUNTERS, Ui.CX - total * 0.5f, countersY - ch, Ui.CX + total * 0.5f, countersY + ch);
        } else {
            setRect(EL_COUNTERS, 0f, 0f, 0f, 0f);
        }
        setRect(EL_PAUSE_BOX, pauseX, pauseY, pauseX + pauseW, pauseY + pauseH);
    }

    private void setRect(int el, float x0, float y0, float x1, float y1) {
        int i = el * 4;
        rects[i] = x0;
        rects[i + 1] = y0;
        rects[i + 2] = x1;
        rects[i + 3] = y1;
    }

    /** Left edge of element {@code el} ({@code EL_*}) as laid out this frame. */
    float left(int el) {
        return rects[el * 4];
    }

    /** Bottom edge of element {@code el}. */
    float bottom(int el) {
        return rects[el * 4 + 1];
    }

    /** Right edge of element {@code el}. */
    float right(int el) {
        return rects[el * 4 + 2];
    }

    /** Top edge of element {@code el}. */
    float top(int el) {
        return rects[el * 4 + 3];
    }

    /** Element of a tutorial tour anchor (DESIGN §10.2), or −1 for a non-HUD anchor. */
    static int element(GuidingText.Anchor a) {
        switch (a) {
            case HUD_SCORE:
                return EL_SCORE;
            case HUD_PAUSE:
                return EL_PAUSE;
            case HUD_CHIP:
                return EL_CHIP;
            case HUD_TIME:
                return EL_TIME;
            default:
                return -1;
        }
    }

    /** Tour target: centre x of the corner element a HUD anchor names (NaN for other anchors). */
    float targetX(GuidingText.Anchor a) {
        int el = element(a);
        return el < 0 ? Float.NaN : (left(el) + right(el)) * 0.5f;
    }

    /** Tour target: centre y of the corner element a HUD anchor names (NaN for other anchors). */
    float targetY(GuidingText.Anchor a) {
        int el = element(a);
        return el < 0 ? Float.NaN : (bottom(el) + top(el)) * 0.5f;
    }

    /** True when the band layout is in use (live counters in a free bottom band; off while the field is stretched). */
    boolean isBand() {
        return band;
    }

    /** The pause control (geometry and input rule). */
    PauseControl pause() {
        return pause;
    }

    /** Pause control hit box. */
    float pauseX() {
        return pauseX;
    }

    float pauseY() {
        return pauseY;
    }

    float pauseW() {
        return pauseW;
    }

    float pauseH() {
        return pauseH;
    }

    /** Centre of the pause icon. */
    float pauseCx() {
        return pause.cx();
    }

    float pauseCy() {
        return pause.cy();
    }

    /** True when (x, y) is on the pause control's hit box. */
    boolean inPause(float x, float y) {
        return pause.contains(x, y);
    }

    /** Bottom-left of the difficulty chip. */
    float chipX() {
        return chipX;
    }

    /** Bottom of the difficulty chip. */
    float chipY() {
        return chipY;
    }

    /** Right edge of the chip plus its title as drawn (the title is fitted to its max width). */
    float titleRight() {
        if (titleW <= 0f || titleMax <= 0f) return chipX + chipW;
        return titleX + Math.min(titleW, titleMax);
    }

    // ================================================================== events

    /**
     * A judgement for the word / meter (DESIGN §5.6). Centre mode shows the central word,
     * "At lane" a small word at the lane, "Off" nothing.
     */
    void judgement(Judgement j, float dtMs, float x, Line line, float lineY, float now) {
        if (j == null) return;
        if (j != Judgement.LOST) {
            int m = meterNext;
            meterNext = (m + 1) % METER_N;
            meterDt[m] = MathUtils.clamp(dtMs, -METER_MS, METER_MS);
            meterT[m] = now;
            meterJ[m] = j;
        }
        int mode = settings.judgementDisplay;
        if (mode == Settings.JUDGEMENT_OFF) return;
        if (mode == Settings.JUDGEMENT_AT_LANE) {
            if (line != null) feedback.laneWord(j, x, line, lineY, now);
            return;
        }
        // The same touch touched a mine first (the engine emits MINE before HIT): keep the red LOST
        // that explains the combo reset; the meter tick above still counts the hit.
        if (wordMine && wordT == now) return;
        word = j;
        wordMine = false;
        wordT = now;
        glintT = j == Judgement.RESONANCE && Judgement.isPerfect(dtMs) ? now : -10f;
        wordRedFired = false;
        wordState.reset();
        if (j == Judgement.RESONANCE) wordState.ambient(GlitchText.Level.G1);
        wordW = fonts.width(wordRole(j), 32f * k, j.display);
        tagShown = false;
        if (j != Judgement.LOST) {
            if (settings.showsEarlyLate(j, dtMs)) {
                tagShown = true;
                boolean late = dtMs > 0f;
                tagColor = late ? Theme.LATE : Theme.EARLY;
                tagSb.setLength(0);
                tagSb.append(late ? "LATE" : "EARLY");
                if (settings.showTimingMs) {
                    tagSb.append("  ").append(late ? '+' : '-');
                    Fonts.appendInt(tagSb, Math.round(Math.abs(dtMs)));
                    tagSb.append(" ms");
                }
            }
        }
    }

    /**
     * Keeps only the score and the accuracy of the corners (no progress hairline, pause control, chip, title or time):
     * the live preview of GAMEPLAY VISUALS, whose caption takes the bottom-right (Kelvin 2026-10-18 19:52).
     */
    void setScoreOnly(boolean on) {
        scoreOnly = on;
    }

    /** A touched mine: the next combo drop plays the break at any combo (§5.15). */
    void mineBreak() {
        mineBreak = true;
    }

    /** First counted tap on the pause control at run time {@code now}: plays the 300 ms hint. */
    void pauseHint(float now) {
        hintT = now;
    }

    /** Clears the pause hint (the run paused, resumed or restarted). */
    void clearPauseHint() {
        hintT = -10f;
        demoT = -10f;
    }

    /**
     * Tutorial hook: plays the double-tap hint twice, as two taps {@link #DEMO_SECOND_TAP} apart, at
     * run time {@code now} without pausing (the second tap flashes the ring cyan, as a real second
     * tap would before the menu opens). Call again to loop it.
     */
    void demoPauseDoubleTap(float now) {
        demoT = now;
    }

    /** {@link #demoPauseDoubleTap(float)} at the HUD's current run time. */
    void demoPauseDoubleTap() {
        demoPauseDoubleTap(lastNow);
    }

    /**
     * A touched MINE or a LANE MINE contact (owner 2026-10-12 00:42): the LOST word like a missed
     * note's, in {@code Theme.MINE_LOST}, at the centre or at the mine x per the judgement display
     * setting; no meter tick, no EARLY/LATE.
     */
    void mineWord(float x, Line line, float lineY, float now) {
        int mode = settings.judgementDisplay;
        if (mode == Settings.JUDGEMENT_OFF) return;
        if (mode == Settings.JUDGEMENT_AT_LANE) {
            if (line != null) feedback.mineWord(x, line, lineY, now);
            return;
        }
        judgement(Judgement.LOST, 0f, x, line, lineY, now);
        wordMine = true;
    }

    /**
     * Tutorial: the demo mine's red LOST, always in the at-lane form above the mine (nothing when
     * judgements are Off), so it never shows in the centre while real notes are being played.
     */
    void demoMineWord(float x, Line line, float lineY, float now) {
        if (settings.judgementDisplay != Settings.JUDGEMENT_OFF) feedback.mineWord(x, line, lineY, now);
    }

    /**
     * Tutorial: shows the centre word {@code j} with its EARLY / LATE tag for {@code dtMs} (DESIGN §5.6)
     * whatever the judgement-display and EARLY/LATE settings say; no hit error meter tick.
     */
    void demoJudgement(Judgement j, float dtMs, float now) {
        word = j;
        wordMine = false;
        wordT = now;
        glintT = -10f;
        wordRedFired = false;
        wordState.reset();
        if (j == Judgement.RESONANCE) wordState.ambient(GlitchText.Level.G1);
        wordW = fonts.width(wordRole(j), 32f * k, j.display);
        tagShown = j != Judgement.LOST && Math.abs(dtMs) >= 1f;
        if (tagShown) {
            boolean late = dtMs > 0f;
            tagColor = late ? Theme.LATE : Theme.EARLY;
            tagSb.setLength(0);
            tagSb.append(late ? "LATE" : "EARLY");
        }
    }

    /** Raster cap of the centre judgement-word pages (32 × the HUD scale), shared with the tutorial's grade lines. */
    static float wordRaster() {
        return 32f * MathUtils.clamp(Ui.uiScale(), 0.9f, 1.3f);
    }

    /** @return the face of a judgement word: Rubik Glitch for all three since 2026-10-13 (DESIGN Appendix C). */
    static Fonts.Role wordRole(Judgement j) {
        return Fonts.Role.DISPLAY_SOFT;
    }

    // ================================================================== update

    /**
     * Per-frame update: layout, score easing and cell pops, combo punches / breaks, cached strings
     * and the guard.
     *
     * @param dt  run delta (0 while paused)
     * @param now run seconds
     */
    void update(float dt, GameEngine e, float now) {
        lastNow = now;
        ScoreKeeper s = e.scores();
        // score
        int target = s.score();
        if (target != scoreTarget) {
            markChangedCells(scoreTarget, target, now);
            scoreFrom = scoreShown;
            scoreTarget = target;
            scoreT = 0f;
        }
        if (scoreT < SCORE_EASE) {
            scoreT += dt;
            float p = Anim.POW2_OUT.apply(Math.min(1f, scoreT / SCORE_EASE));
            // exact at both ends and never past the target, so the shown score only ever rises
            scoreShown = Anim.count(scoreFrom, scoreTarget, p);
        } else {
            scoreShown = scoreTarget;
        }
        for (int i = 0; i < cellScale.length; i++) {
            float t = (now - cellPop[i]) / POP_SEC;
            cellScale[i] = t >= 0f && t < 1f ? 1.08f - 0.08f * Anim.POW2_OUT.apply(t) : 1f;
        }
        fcState = !settings.fcArIndicator || !s.isFullComboSoFar() ? FC_NONE
            : s.count(Judgement.ECHO) > 0 ? FC_ECHO : FC_AR;
        // accuracy
        int judged = s.judgedCount();
        if (judged != accJudged) {
            accJudged = judged;
            accSb.setLength(0);
            Fonts.appendTruncated(accSb, s.accuracy() * 100.0, 2).append('%');
        }
        // time remaining
        double remain = Math.max(0.0, e.lastNoteEndMs() - Math.max(0.0, e.judgeMs()));
        int sec = (int) Math.ceil(remain / 1000.0);
        if (sec != timeSec) {
            timeSec = sec;
            timeSb.setLength(0);
            Fonts.appendInt(timeSb, sec / 60).append(':');
            if (sec % 60 < 10) timeSb.append('0');
            Fonts.appendInt(timeSb, sec % 60);
            timeW = numFixedWidth(timeSb, TIME_CAP * k);
        }
        // combo
        int c = s.combo();
        if (c > lastCombo) {
            incT = now;
            if (c >= 100 && c / 100 > lastCombo / 100) {
                bigT = now;
                bigSeed++;
                feedback.centreRing(Ui.CX, 540f + 48f * k, now);
            }
        } else if (c < lastCombo) {
            if (lastCombo >= 20 || mineBreak && lastCombo >= 1) {
                breakValue = lastCombo;
                breakT = now;
            }
        }
        mineBreak = false;
        lastCombo = c;
        // live counters
        if (band && settings.liveCounters) {
            updateCounter(0, 'R', s.count(Judgement.RESONANCE));
            updateCounter(1, 'E', s.count(Judgement.ECHO));
            updateCounter(2, 'L', s.count(Judgement.LOST));
            sepW = boldWidth(SEP, 22f * k);
        }
        layout();
        guard(e, dt, now);
    }

    private void updateCounter(int i, char letter, int v) {
        if (cntVal[i] == v) return;
        cntVal[i] = v;
        StringBuilder sb = cntSb[i];
        sb.setLength(0);
        sb.append(letter).append(' ');
        Fonts.appendGrouped(sb, v);
        cntW[i] = boldWidth(sb, 22f * k);
    }

    /** Pops the digit cells of the {@link #SCORE_DIGITS}-digit grouped score that differ between a and b. */
    private void markChangedCells(int a, int b, float now) {
        for (int p = 0; p < SCORE_DIGITS; p++) {
            int e = SCORE_DIGITS - 1 - p;
            int div = pow10(e);
            if ((a / div) % 10 != (b / div) % 10) {
                // commas precede every digit whose exponent is a multiple of 3 (except the last)
                int cell = p + (SCORE_DIGITS - 1) / 3 - e / 3;
                cellPop[cell] = now;
            }
        }
    }

    private static int pow10(int e) {
        int v = 1;
        for (int i = 0; i < e; i++) v *= 10;
        return v;
    }

    // ------------------------------------------------------------------ guard

    private void guard(GameEngine e, float dt, float now) {
        float ly0 = e.lineY(Line.LOWER);
        float ly1 = e.lineY(Line.UPPER);
        float lo0 = ly0 - FieldRenderer.zone();
        float hi0 = ly0 + FieldRenderer.zone();
        float lo1 = ly1 - FieldRenderer.zone();
        float hi1 = ly1 + FieldRenderer.zone();
        LaneLayout lay = e.layout();
        float wl = lay.windowLeft();
        float wr = lay.windowRight();
        float step = dt * (1f - GUARD_A) / GUARD_SEC;
        // corners: a judgement line near the element, or an at-lane word on it
        gScore = approach(gScore, cornerHit(EL_SCORE, wl, wr, ly0, ly1, now), step);
        gPause = approach(gPause, cornerHit(EL_PAUSE, wl, wr, ly0, ly1, now), step);
        gChip = approach(gChip, cornerHit(EL_CHIP, wl, wr, ly0, ly1, now), step);
        gTime = approach(gTime, cornerHit(EL_TIME, wl, wr, ly0, ly1, now), step);
        // centre: the zone-overlap rule
        gCombo = approach(gCombo, hit(Ui.CX - 300f, Ui.CX + 300f, 500f, 540f + 96f * k, wl, wr, lo0, hi0, lo1, hi1), step);
        gWord = approach(gWord, hit(Ui.CX - 300f, Ui.CX + 300f, 440f, 444f + 34f * k, wl, wr, lo0, hi0, lo1, hi1), step);
        gMeter = approach(gMeter, hit(Ui.CX - 170f, Ui.CX + 170f, METER_Y - 14f, METER_Y + 14f, wl, wr, lo0, hi0, lo1, hi1),
            step);
        gCounters = approach(gCounters, hit(Ui.CX - 300f, Ui.CX + 300f, countersY - 14f, countersY + 14f, wl, wr, lo0, hi0,
            lo1, hi1), step);
    }

    /** True while a judgement line runs within {@link #LINE_GUARD} of element {@code el} or an at-lane word covers it. */
    private boolean cornerHit(int el, float wl, float wr, float ly0, float ly1, float now) {
        float x0 = left(el);
        float x1 = right(el);
        float y0 = bottom(el);
        float y1 = top(el);
        if (x1 <= x0) return false;
        if (x1 >= wl && x0 <= wr) {
            if (ly0 > y0 - LINE_GUARD && ly0 < y1 + LINE_GUARD) return true;
            if (ly1 > y0 - LINE_GUARD && ly1 < y1 + LINE_GUARD) return true;
        }
        return feedback.wordOverlaps(x0, x1, y0, y1, now);
    }

    private static boolean hit(float x0, float x1, float y0, float y1, float wl, float wr, float lo0, float hi0,
                               float lo1, float hi1) {
        if (x1 < wl || x0 > wr) return false;
        return y1 > lo0 && y0 < hi0 || y1 > lo1 && y0 < hi1;
    }

    private static float approach(float v, boolean overlap, float step) {
        float target = overlap ? GUARD_A : 1f;
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }

    // ================================================================== drawing

    /** Slide-in factor of the HUD clusters (0.10–0.40 s, {@code pow3Out}). */
    private static float slide(float introT) {
        float t = MathUtils.clamp((introT - 0.10f) / 0.30f, 0f, 1f);
        return Anim.POW3_OUT.apply(t);
    }

    /**
     * Normal block A (under the notes): the hit error meter frame and the PERFECT glint.
     *
     * @param centreFree false while the pause menu or a countdown owns the centre
     */
    void drawUnder(Batch b, float introT, float now, boolean centreFree) {
        if (settings.showTimingMeter) {
            float a = slide(introT) * gMeter;
            float mx = Ui.CX;
            float my = METER_Y;
            draw.roundRect(mx - 160f, my - 12f, 320f, 24f, 8f, Theme.PANEL, 0.6f * a);
            float inner = 160f * Judgement.TIER1_MS / METER_MS;
            draw.rect(mx - inner, my - 12f, inner * 2f, 24f, Theme.RESONANCE, 0.10f * a);
            // §18.1 [P1]: the PERFECT band (±33 ms) one step brighter on top
            float perfect = 160f * Judgement.PERFECT_MS / METER_MS;
            draw.rect(mx - perfect, my - 12f, perfect * 2f, 24f, Theme.RESONANCE, 0.10f * a);
            draw.rect(mx - 1f, my - 12f, 2f, 24f, Theme.INK1, 0.45f * a);
            int n = 0;
            for (int i = 0; i < METER_N; i++) {
                float age = now - meterT[i];
                if (meterJ[i] == null || age < 0f || age > 2f) continue;
                float x = mx + 160f * meterDt[i] / METER_MS;
                draw.rect(x - 1f, my - 7f, 2f, 14f, meterJ[i].color(), a * (1f - age / 2f));
                sortBuf[n++] = meterDt[i];
            }
            if (n > 0) {
                insertionSort(sortBuf, n);
                float med = n % 2 == 1 ? sortBuf[n / 2] : 0.5f * (sortBuf[n / 2 - 1] + sortBuf[n / 2]);
                float x = mx + 160f * med / METER_MS;
                draw.line(x - 6f, my + 22f, x, my + 14f, 2f, Theme.INK1, 0.8f * a);
                draw.line(x, my + 14f, x + 6f, my + 22f, 2f, Theme.INK1, 0.8f * a);
            }
        }
        if (centreFree) drawGlint(now);
    }

    /**
     * §18.1 [P1] PERFECT glint: a 2 px white line under the central word whose bright head sweeps
     * left → right in 90 ms, then rests under the word and fades with it. Shapes only (block A).
     */
    private void drawGlint(float now) {
        if (word != Judgement.RESONANCE || glintT < 0f || !centreDrawn) return;
        float el = now - glintT;
        if (el < 0f || el >= WORD_SEC) return;
        float a = (el < 0.30f ? 1f : 1f - (el - 0.30f) / 0.12f) * gWord;
        if (a <= 0.003f) return;
        float w = wordW + 16f * k;
        float x0 = Ui.CX - w * 0.5f;
        float y = 444f - 12f * k;
        float p = Math.min(1f, el / GLINT_SEC);
        float head = x0 + w * Anim.POW2_OUT.apply(p);
        if (p < 1f) {
            float tail = Math.max(x0, head - w * 0.45f);
            draw.rectGradientH(tail, y, head - tail, 2f, TRAIL_WHITE, Theme.WHITE, 0.95f * a);
            if (tail > x0) draw.rect(x0, y, tail - x0, 2f, Theme.WHITE, 0.35f * a);
        } else {
            draw.rect(x0, y, w, 2f, Theme.WHITE, 0.55f * a);
        }
    }

    private static void insertionSort(float[] a, int n) {
        for (int i = 1; i < n; i++) {
            float v = a[i];
            int j = i - 1;
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j];
                j--;
            }
            a[j + 1] = v;
        }
    }

    /**
     * HUD-under text (under the notes): combo and the judgement word.
     *
     * @param centreFree false while the pause menu or a countdown owns the centre
     */
    void drawUnderText(Batch b, GameEngine e, float now, boolean centreFree) {
        centreDrawn = centreFree;
        if (!centreFree) return;
        drawCombo(b, e, now);
        drawWord(b, now);
    }

    /**
     * Last pass, shapes (atlas): progress hairline, difficulty chip plate, the pause icon with its
     * first-tap hint, demo taps and hold ring.
     *
     * @param paused       the pause menu is open (the ring turns cyan)
     * @param holdProgress 0..1 of the hold mode's ring
     */
    void drawCorners(Batch b, GameEngine e, float introT, float now, boolean paused, float holdProgress) {
        if (scoreOnly) return;
        float s = slide(introT);
        float off = (1f - s) * 90f;
        // progress hairline along the top edge
        float vl = Ui.left();
        float vw = Ui.worldW();
        draw.rect(vl, progY, vw, PROGRESS_H, Theme.INK1, 0.12f * s);
        draw.rect(vl, progY, vw * e.progress(), PROGRESS_H, Theme.GROUND_SIG, 0.60f * s);
        // difficulty chip plate
        if (chipW > 0f) draw.roundRect(chipX - off, chipY, chipW, chipH, CHIP_RADIUS * k, diffFill, 0.70f * s * gChip);
        drawPauseIcon(now, s * gPause, off, paused, holdProgress);
    }

    /** The pause icon (ring and bars) with the first-tap hint, the demo taps and the hold ring. */
    private void drawPauseIcon(float now, float a, float off, boolean paused, float holdProgress) {
        if (a <= 0.003f) return;
        PauseControl pc = pause;
        float pk = pc.scale();
        float cx = pc.cx() + off;
        float cy = pc.cy();
        float r = pc.radius();
        float hint = Math.max(hintLevel(now - hintT), Math.max(hintLevel(now - demoT),
            hintLevel(now - demoT - DEMO_SECOND_TAP)));
        float second = now - demoT - DEMO_SECOND_TAP;
        boolean cyan = paused || second >= 0f && second < PauseControl.HINT_SEC;
        float ink = 0.55f + 0.45f * hint;
        draw.circle(cx, cy, r, pc.stroke(), cyan ? Theme.BTN_TOP : Theme.INK1, (cyan ? 0.95f : ink) * a);
        float bw = PauseControl.BAR_W * pk;
        float bh = PauseControl.BAR_H * pk;
        float dx = PauseControl.BAR_DX * pk;
        draw.rect(cx - dx - bw * 0.5f, cy - bh * 0.5f, bw, bh, Theme.INK1, ink * a);
        draw.rect(cx + dx - bw * 0.5f, cy - bh * 0.5f, bw, bh, Theme.INK1, ink * a);
        if (!paused) {
            hintRings(cx, cy, r, pk, now - hintT, a);
            hintRings(cx, cy, r, pk, now - demoT, a);
            hintRings(cx, cy, r, pk, second, a);
        }
        if (holdProgress > 0.001f) {
            // hold mode: a 4-unit ink1 ring fills clockwise from the top (§5.3)
            draw.arc(cx, cy, r + 6f * pk, MathUtils.PI * 0.5f, -MathUtils.PI2 * Math.min(1f, holdProgress), 4f * pk,
                Theme.INK1, a);
        }
    }

    /** 0..1 brightness of the icon {@code el} seconds after a counted tap (1 through the window, then off). */
    private static float hintLevel(float el) {
        if (el < 0f || el >= PauseControl.HINT_SEC) return 0f;
        float p = el / PauseControl.HINT_SEC;
        return p < 0.75f ? 1f : (1f - p) / 0.25f;
    }

    /**
     * The first-tap hint {@code el} seconds after the tap: a cyan arc 6 outside the ring that drains
     * clockwise with the double-tap window, and a thin ring pulsing out from the icon.
     */
    private void hintRings(float cx, float cy, float r, float pk, float el, float a) {
        if (el < 0f || el >= PauseControl.HINT_SEC) return;
        float p = el / PauseControl.HINT_SEC;
        float ra = r + 6f * pk;
        draw.circle(cx, cy, ra, 2f * pk, Theme.INK1, 0.18f * a);
        draw.arc(cx, cy, ra, MathUtils.PI * 0.5f, -MathUtils.PI2 * (1f - p), 3f * pk, Theme.BTN_TOP, 0.95f * a);
        float rp = r + 24f * pk * Anim.POW2_OUT.apply(p);
        draw.circle(cx, cy, rp, 2f * pk, Theme.BTN_TOP, 0.6f * (1f - p) * a);
    }

    /**
     * Last pass, text: score, accuracy (or AUTOPLAY), chip text and title, time, live counters.
     */
    void drawCornerText(Batch b, float introT) {
        float s = slide(introT);
        float off = (1f - s) * 90f;
        // score, accuracy, time and the chip constant: the small NUMBER page; with FC / AR INDICATOR the score is light
        // blue while all RESONANCE, purple while a FULL COMBO with ECHOs, white after a break (Kelvin 2026-10-18 16:17)
        Color sc = autoplay ? Theme.AUTO : fcState == FC_AR ? Theme.ALL_RESONANCE : fcState == FC_ECHO
            ? Theme.FULL_COMBO : Theme.INK1;
        fonts.drawDigits(b, Fonts.Role.NUMBER, scoreCap, scoreShown, SCORE_DIGITS, true, sl - off, scoreTop,
            Align.left | Align.top, sc, Theme.LEADING_ZERO, 0.80f * s * gScore, cellScale);
        if (autoplay) {
            label(b, AUTOPLAY, AUTO_CAP * k, sl - off, accTop, Align.left | Align.top, Theme.AUTO, 0.85f * s * gScore);
        } else {
            numFixed(b, accSb, accCap, sl - off, accTop, Align.left | Align.top, Theme.INK1, 0.60f * s * gScore);
        }
        if (scoreOnly) return;
        float chipCy = chipY + chipH * 0.5f;
        numFixed(b, timeSb, TIME_CAP * k, sr + off, chipCy, Align.right | Fonts.VCENTER, Theme.INK1, 0.50f * s * gTime);
        float chipA = s * gChip;
        float chipTextX = chipX - off + CHIP_PAD * k;
        if (diffName.length() > 0 && constText.length() > 0) {
            float cx = chipTextX + chipNameW + CHIP_GAP * k;
            if (constDigits) {
                numFixed(b, constText, CHIP_CAP * k, cx, chipCy, Align.left | Fonts.VCENTER, diffText, chipA);
            } else {
                fonts.draw(b, Fonts.Role.NUMBER, CHIP_CAP * k, constText, cx, chipCy, Align.left | Fonts.VCENTER,
                    diffText, chipA);
            }
            if (constMark.length() > 0 && chipA > 0.003f) {
                float mc = CHIP_CAP * k * Theme.CONST_MARK_SCALE;
                raster(mc, numRaster());
                fonts.draw(b, Fonts.Role.NUMBER, mc, constMark, cx + constW + CHIP_CAP * k * Theme.CONST_MARK_GAP,
                    chipCy + CHIP_CAP * k * Theme.CONST_MARK_RISE, Align.left | Fonts.VCENTER, diffText, chipA);
                unraster();
            }
        }
        // chip name (UI_BOLD page) + title (UI page)
        if (diffName.length() > 0) {
            bold(b, diffName, CHIP_CAP * k, chipTextX, chipCy, Align.left | Fonts.VCENTER, diffText, chipA);
        }
        if (title.length() > 0 && titleMax > 0f) {
            float base = chipCy - TITLE_CAP * k * 0.5f;
            fonts.drawFit(b, Fonts.Role.UI, TITLE_CAP * k, title, titleX - off, base, Align.left, titleMax, 1f,
                Theme.INK1, 0.60f * chipA);
        }
        // live counters (band layout; UI_BOLD page)
        if (band && settings.liveCounters && cntVal[0] >= 0) {
            float total = cntW[0] + cntW[1] + cntW[2] + 2f * sepW;
            float x = Ui.CX - total * 0.5f;
            float a = s * gCounters;
            int al = Align.left | Fonts.VCENTER;
            float c = 22f * k;
            x += bold(b, cntSb[0], c, x, countersY, al, Theme.RESONANCE, a);
            x += bold(b, SEP, c, x, countersY, al, Theme.INK3, a);
            x += bold(b, cntSb[1], c, x, countersY, al, Theme.ECHO, a);
            x += bold(b, SEP, c, x, countersY, al, Theme.INK3, a);
            bold(b, cntSb[2], c, x, countersY, al, Theme.LOST, a);
        }
    }

    private static final String SEP = " · ";

    /** True when every char is in the digit glyph set ({@code 0-9 , . : % + - /} and space). */
    private static boolean digitsOnly(CharSequence t) {
        for (int i = 0; i < t.length(); i++) {
            if ("0123456789,.:%+-/ ".indexOf(t.charAt(i)) < 0) return false;
        }
        return t.length() > 0;
    }

    /** The end banner's hold on the centre (0..1, see {@code RunOverlay.bannerCover}); set every frame. */
    void setCentreCover(float cover) {
        centreCover = MathUtils.clamp(cover, 0f, 1f);
    }

    private void drawCombo(Batch b, GameEngine e, float now) {
        int c = e.scores().combo();
        float keep = 1f - centreCover;
        if (keep <= 0.003f) return;
        float cap = 96f * k;
        float bt = now - breakT;
        if (c < 3 && bt >= 0f && bt < BREAK_FLICKER + BREAK_FADE) {
            float a = bt < BREAK_FLICKER ? 0.30f : 0.30f * (1f - (bt - BREAK_FLICKER) / BREAK_FADE);
            comboSb.setLength(0);
            Fonts.appendGrouped(comboSb, breakValue);
            int mode = bt < BREAK_FLICKER ? ScaledGlitch.G2_RED : ScaledGlitch.NONE;
            digits.draw(b, Fonts.Role.NUMBER, cap, comboSb, Ui.CX, 540f, 1f, Theme.INK1, a * gCombo * keep, mode, 0f,
                bt, breakValue, now);
            drawComboLabel(b, a / 0.30f * 0.40f * keep);
            return;
        }
        if (c < 3) return;
        float inc = now - incT;
        float a = 0.30f + (inc >= 0f && inc < INC_SEC ? 0.20f * (1f - inc / INC_SEC) : 0f);
        float scale = 1f;
        int mode = ScaledGlitch.NONE;
        float burst = 0f;
        float big = now - bigT;
        if (big >= 0f && big < BIG_SEC) {
            scale = 1.25f - 0.25f * Anim.SWING_OUT_16.apply(big / BIG_SEC);
            if (big < BIG_GLITCH_SEC) {
                mode = ScaledGlitch.G2;
                burst = big / BIG_GLITCH_SEC;
            }
        } else if (inc >= 0f && inc < INC_SEC) {
            scale = 1.06f - 0.06f * Anim.POW2_OUT.apply(inc / INC_SEC);
        }
        comboSb.setLength(0);
        Fonts.appendGrouped(comboSb, c);
        digits.draw(b, Fonts.Role.NUMBER, cap, comboSb, Ui.CX, 540f, scale, Theme.INK1, a * gCombo * keep, mode, burst,
            big, bigSeed, now);
        drawComboLabel(b, 0.40f * keep);
    }

    private void drawComboLabel(Batch b, float a) {
        label(b, COMBO, 18f * k, Ui.CX, 504f, Align.center, Theme.INK1, a * gCombo);
    }

    private void drawWord(Batch b, float now) {
        if (word == null) return;
        float el = now - wordT;
        if (el < 0f || el >= WORD_SEC) return;
        float a = el < 0.30f ? 1f : 1f - (el - 0.30f) / 0.12f;
        float alpha = a * gWord * (word == Judgement.LOST ? 0.85f : 1f);
        float cap = 32f * k;
        Fonts.Role role = wordRole(word);
        Color c = wordMine ? Theme.MINE_LOST : word.color();
        float scale = el < 0.06f ? 1.2f - 0.2f * (el / 0.06f) : 1f;
        if (word == Judgement.LOST && !wordRedFired) {
            wordRedFired = true;
            wordState.trigger(GlitchText.Level.G2_RED, now);
        }
        if (word == Judgement.RESONANCE || word == Judgement.LOST) {
            // glitched through the pop too: the scale keeps the cap-32 raster (no page per size)
            glitch.draw(b, role, cap, Fonts.GlyphSet.FULL, word.display, Ui.CX, 444f, Align.center, c, alpha,
                wordState, now, scale, 0f);
        } else if (scale > 1.0001f) {
            fonts.drawScaled(b, role, cap, scale, word.display, Ui.CX, 444f, Align.center, 0f, 1f, c, alpha);
        } else {
            fonts.draw(b, role, cap, word.display, Ui.CX, 444f, Align.center, c, alpha);
        }
        if (tagShown) {
            float x = Ui.CX + wordW * scale * 0.5f + 16f * k;
            bold(b, tagSb, 20f * k, x, 444f, Align.left, tagColor, a * gWord);
        }
    }
}
