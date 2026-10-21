package org.kelvinizer.screens.result;

import org.kelvinizer.ui.Ui;

/**
 * 结算画面每个元素在当前视口下的位置（世界坐标），每次窗口大小改变时重新计算，不分配内存。
 * 2026-10-19 起按设计稿排版：16:10 是蓝本（1920 × 1200，第七版 06:28，第八版 15:2x）。左上玩家框、右上贝壳胶囊；
 * 下面一条全宽标题带（曲名和曲师居中）；再下面左边是 16:9 的大曲绘，难度块在它右下角里面，评级章、新纪录和分数压在曲绘左下；
 * 曲绘下面是共鸣连击和最大连击、早晚和调整建议，右边是判定数；右下角重开和继续，右边留给人物立绘。
 * 其他比例：矮的屏幕（16:9）整体按高度缩小，高的屏幕（4:3）多出的高度一半放在曲绘上面、一半放在曲绘下面；
 * 比 16:10 宽的屏幕像 Arcaea 一样把多出的宽度大部分留在两边、一点留在中间，人物区保持 16:10 的形状。
 * 比 16:8.5 宽的手机分三栏（第八版）：左边完整的曲绘、下面分数，中间统计，右边人物（和 16:10 一样的比例），角落、标题带和按钮照旧。
 *
 * <p>Positions of every result-screen element for the current viewport, in world units. The 16:10 blueprint is
 * 1920 × 1200 with y measured from the top and the safe edges 24 in: design round 7 (Kelvin 2026-10-19
 * 06:28, its layout notes 结算和地图排版参数) changed by round 8 (15:2x, 结算第八版排版参数). The player frame (top
 * left) and the shell capsule (top right), a full-width title band (title and artist centred), the 16:9 jacket at
 * the left with the difficulty chip in its bottom-right corner and the grade stamp, the best line and the score at
 * its darkened bottom left, under it RESONANCE CHAIN / MAX COMBO on one line, the early / late line and the offset
 * hint, the counts right of them, RETRY / CONTINUE at the bottom right and the area right of the jacket left for a
 * character. No clear badge since round 8; AUTOPLAY takes the best line's place.
 *
 * <p>Other aspects follow the blueprint's rules (03:47 「所有剩下的都通过16：10进行拉伸/压缩高度」): a screen shorter
 * than 16:10 (16:9) scales everything by its safe height over 1,152; a taller one (4:3) keeps the sizes and puts half
 * the extra height over the jacket and half under it, so the top band stays at the top and the rows under the jacket
 * at the bottom. The player frame keeps to the safe left edge, the capsule to the safe right edge and the title to the
 * middle. A screen wider than 16:10 (16:9, 16:8.5) leaves 40 % of the width past the blueprint's on each side and the
 * other 20 % between the jacket and the character area (Kelvin 17:48, like her Arcaea screenshot: 「两边留出空间，
 * 中间适当留一点空间但不要多」), so the left column, the character area and the buttons keep the blueprint's shape.
 *
 * <p>A world wider than 16:8.5 (phones, Kelvin 14:2x–15:2x; the split 17:22) has three columns instead, from the
 * round-8 notes' 2400 × 1080 frame (scaled by safe height over 1,032): the jacket whole (no shade) with NEW BEST and
 * the score under it and the stamp at their right; the chain block, the early / late line, the hint, a divider and the
 * counts in a middle column down to the score's baseline; the character area right of it. The corners, the title
 * band and the buttons stay as on the blueprint (scaled by {@link #s}). The character area has the blueprint's shape
 * ({@link #CHAR_ASPECT}, 17:48 「20：9的角色区域比例和16：10的角色区域比例要一样」). Narrower than 20:9 (18:9) the
 * middle column, its gap and the character area narrow in 20:9's proportions (the area from its top down), and the
 * buttons with the character area; wider than 20:9 the extra width goes to the sides as on 16:9.
 *
 * <p>Plain mutable fields; {@link #compute()} refills them on every resize (no allocation).
 */
public final class ResultLayout {
    /** Safe height of the 16:10 blueprint (1,200 less the two 24 margins). */
    static final float BLUEPRINT_SAFE_H = 1152f;
    /** Safe height of the three-column frame (2400 × 1080 less the two 24 margins). */
    static final float WIDE_SAFE_H = 1032f;
    /** World aspect past which the three columns are used (Kelvin 17:22 「划分点是16：8.5」; 16:8.5 itself is a tablet). */
    static final float WIDE_ASPECT = 16f / 8.5f;
    /** Safe widths of the blueprint (1,920) and of the three-column frame (2,400), less the two 24 margins. */
    static final float BLUEPRINT_SAFE_W = 1872f, WIDE_SAFE_W = 2352f;
    /** Share of the width past the frame's that goes to each side (the rest between the jacket and the character). */
    static final float SIDE_SHARE = 0.4f;
    /** Width over height of the character area: the blueprint's 720 × 716, on phones too (Kelvin 17:48). */
    static final float CHAR_ASPECT = 720f / 716f;

    /** Ui generation the layout was computed for. */
    public int generation = -1;
    /** Size factor: 1 at 16:10 and taller, safe height / 1,152 on shorter screens. */
    public float s = 1f;
    /** Three columns (world aspect wider than 16:8.5, round 8). */
    public boolean wide;

    // corners ------------------------------------------------------------------------------
    /**
     * Player block: left x, bottom y, height (its width follows the text, see {@link CornerHud}). Since Kelvin
     * 2026-10-20 19:45 it is the map's block, 100 high from 32 down: centred on the capsule, 8 clear of the title
     * band (a panel 84 high from 40 before).
     */
    public float frameX, frameY, frameH;
    /** Shell capsule: right x, centre y, height. */
    public float capsuleRight, capsuleY, capsuleH;

    // title band ---------------------------------------------------------------------------
    /** Full-width band: bottom and top y. */
    public float bandY0, bandY1;
    /** Title centre x, baseline, cap and the width past which it shrinks. */
    public float titleX, titleBase, titleCap, titleMaxW;
    /** Artist baseline and cap (centred on {@link #titleX}). */
    public float artistBase, artistCap;

    // jacket -------------------------------------------------------------------------------
    /** The 16:9 jacket: left x, bottom y, width, height, corner radius. */
    public float jacketX, jacketY, jacketW, jacketH, jacketR;
    /** Darken the jacket's lower part under the score (not with three columns, where the score is under it). */
    public boolean shade;
    /** Difficulty chip in the jacket's bottom-right corner: right x, bottom y, height, radius, text cap, padding. */
    public float chipRight, chipY, chipH, chipR, chipCap, chipPad;
    /** AUTO watermark right edge and largest cap (it is centred on the character area, see {@link JacketBlock}). */
    public float watermarkX, watermarkCap;

    // stamp, best line and score -----------------------------------------------------------
    /** Grade stamp centre, ring radius and letter cap. */
    public float stampX, stampY, stampR, stampCap;
    /** Stamp column (flash rect at impact). */
    public float stampColX0, stampColX1;
    /** Score left x, baseline and cap. */
    public float scoreX, scoreBase, scoreCap;
    /** Best line: left x, pill top and pill height. */
    public float bestX, bestTop, bestH;
    /** AUTOPLAY pill in the best line's place: left x, centre y, least width, height, text cap. */
    public float autoX, autoY, autoW, autoH, autoCap;

    // stats --------------------------------------------------------------------------------
    /** RESONANCE CHAIN / MAX COMBO: line span and y, bar height, label baselines and caps, and their size factor. */
    public float chainX0, chainX1, chainLineY, chainBarH, chainBase, comboBase, chainLabelCap, chainValueCap, chainK;
    /** Early / late line: left x, baseline, label and number caps, the size factor of its gaps, and the width it
     *  shrinks to fit (0: none). */
    public float earlyLateX, earlyLateBase, earlyLateLabelCap, earlyLateCap, earlyLateK, earlyLateMaxW;
    /** Offset hint: left x, baseline, maximum width, cap and how far under the baseline its underline runs. */
    public float hintX, hintBase, hintMaxW, hintCap, hintLine;
    /** Divider over the counts (three columns only): span and y; none when {@code dividerX1 <= dividerX0}. */
    public float dividerX0, dividerX1, dividerY;
    /** Counts: label x, value right edge, label and value caps, and their size factor. */
    public float rowLabelX, rowCountRight, rowLabelCap, rowValueCap, rowK;
    /** Count baselines: RESONANCE, ECHO, LOST and MINES (MINES shows 0 / 0 too, Kelvin 2026-10-19 02:40). */
    public final float[] rowBase = new float[4];
    /** PERFECT line under RESONANCE: baseline and cap. */
    public float perfectBase, perfectCap;

    // right --------------------------------------------------------------------------------
    /** RETRY and CONTINUE rectangles (x, y, w, h). */
    public float retryX, retryY, retryW, retryH, contX, contY, contW, contH;
    /** Character area (left empty for a character to come). */
    public float charX0, charY0, charX1, charY1;

    /** Safe edges, the left and right edges of the jacket, stats, character and buttons (inside the side space). */
    private float sl, sr, st, bl, br, extra, k;

    /** Recomputes every position for the current {@link Ui} metrics. */
    public void compute() {
        generation = Ui.generation();
        sl = Ui.safeLeft();
        sr = Ui.safeRight();
        st = Ui.safeTop();
        float sb = Ui.safeBottom();
        float safeH = st - sb;
        s = Math.min(1f, safeH / BLUEPRINT_SAFE_H);
        extra = Math.max(0f, safeH - BLUEPRINT_SAFE_H);
        wide = Ui.worldW() > WIDE_ASPECT * Ui.worldH() + 0.5f;
        k = safeH / WIDE_SAFE_H;
        // the width past the frame's (16:10, or 20:9 with three columns) goes to the sides, a fifth to the middle
        float side = SIDE_SHARE * Math.max(0f, sr - sl - (wide ? WIDE_SAFE_W * k : BLUEPRINT_SAFE_W * s));
        bl = sl + side;
        br = sr - side;

        // corners
        frameX = sl;
        frameH = 100f * s;
        frameY = ty(132f);
        capsuleRight = sr;
        capsuleY = ty(82f);
        capsuleH = 44f * s;

        // title band
        bandY0 = ty(300f);
        bandY1 = ty(140f);
        titleX = (sl + sr) * 0.5f;
        titleBase = ty(212f);
        titleCap = 48f * s;
        titleMaxW = sr - sl - 144f * s;
        artistBase = ty(268f);
        artistCap = 24f * s;

        // RETRY and CONTINUE at the bottom right (Kelvin 06:04: a little smaller), the character area over them
        contH = retryH = 96f * s;
        contY = retryY = sb;
        charX1 = br;
        charY0 = contY + contH + 28f * s;

        if (wide) columns();
        else blueprint();

        // the chip keeps its round-7 size, 16 in from the jacket's right and bottom edges (round 8)
        jacketR = 18f * s;
        chipH = 48f * s;
        chipR = 10f * s;
        chipCap = 22f * s;
        chipPad = 22f * s;
        chipRight = jacketX + jacketW - 16f * s;
        chipY = jacketY + 16f * s;
        stampCap = 176f * stampR / 150f;
        stampColX0 = stampX - stampR - 50f * s;
        stampColX1 = stampX + stampR + 50f * s;
        // the AUTOPLAY pill's bottom on the best line's
        autoY = bestTop - bestH + autoH * 0.5f;

        // the stats' sizes follow their factors (round 7's at 1)
        chainBarH = 16f * chainK;
        chainBase = chainLineY + 26f * chainK;
        comboBase = chainLineY - 50f * chainK;
        chainLabelCap = 17f * chainK;
        chainValueCap = 24f * chainK;
        earlyLateLabelCap = 15f * earlyLateK;
        earlyLateCap = 17f * earlyLateK;
        rowLabelCap = 17f * rowK;
        rowValueCap = 26f * rowK;
        perfectCap = 14f * rowK;

        // the buttons no wider than the character area over them
        float bk = Math.min(1f, (charX1 - charX0) / (714f * s));
        contW = 360f * s * bk;
        retryW = 326f * s * bk;
        contX = br - contW;
        retryX = contX - 28f * s * bk - retryW;
        watermarkCap = 200f * s;
        watermarkX = br - 16f * s;
    }

    /** The 16:10 blueprint (rounds 7 and 8). */
    private void blueprint() {
        // jacket, 16:9, with half of a tall screen's extra height over it; its bottom darkened under the score
        jacketX = lx(96f);
        jacketW = 1040f * s;
        jacketH = 585f * s;
        jacketY = jy(921f);
        shade = true;
        // stamp, NEW BEST and score at the jacket's bottom left, the score's baseline on the chip's text (round 8)
        stampR = 66f * s;
        stampX = lx(190f);
        stampY = jy(830f);
        scoreX = lx(296f);
        scoreBase = jy(892f);
        scoreCap = 60f * s;
        bestX = scoreX;
        bestTop = jy(789f);
        bestH = 27f * s;
        autoX = scoreX;
        autoW = 168f * s;
        autoH = 40f * s;
        autoCap = 18f * s;

        // under the jacket, anchored to the bottom
        chainK = earlyLateK = rowK = s;
        chainX0 = lx(96f);
        chainX1 = lx(600f);
        chainLineY = by(1007f);
        earlyLateX = lx(96f);
        earlyLateBase = by(1103f);
        earlyLateMaxW = 0f;
        hintX = lx(96f);
        hintBase = by(1147f);
        hintMaxW = lx(640f) - hintX;
        hintCap = 18f * s;
        hintLine = 6f * s;
        dividerX0 = dividerX1 = dividerY = 0f;
        rowLabelX = lx(676f);
        rowCountRight = lx(1036f);
        rowBase[0] = by(981f);
        rowBase[1] = by(1047f);
        rowBase[2] = by(1089f);
        rowBase[3] = by(1131f);
        perfectBase = by(1009f);
        charX0 = rx(1176f);
        charY1 = jy(336f);
    }

    /** Three columns past 16:8.5 (round 8; the notes' 2400 × 1080 frame, y measured down). */
    private void columns() {
        // left: the whole jacket (no shade), NEW BEST 30 under it, the score, the stamp at their right
        jacketX = px(88f);
        jacketW = 930f * k;
        jacketH = 522f * k;
        jacketY = py(826f);
        shade = false;
        bestX = px(88f);
        bestTop = py(856f);
        bestH = 36f * k;
        autoX = bestX;
        autoW = 168f * k;
        autoH = 40f * k;
        autoCap = 18f * k;
        scoreX = px(88f);
        scoreBase = py(980f);
        scoreCap = 70f * k;
        stampR = 82f * k;
        stampX = px(923f);
        stampY = py(945f);

        // middle: the chain block × 1.15, EARLY / LATE × 1.1, the hint, a divider, the counts × 1.25
        chainK = 1.15f * k;
        earlyLateK = 1.1f * k;
        rowK = 1.25f * k;
        // the middle column, its gap and the character area share the width right of the jacket's column as on the
        // notes' 20:9 frame: 560, the gap, and the character area as wide as the blueprint's shape allows from the
        // jacket's top down to the buttons (17:48); narrower screens narrow all three (Kelvin 17:21 「18：9中间那一栏
        // 宽了，窄一点」)
        float x0 = px(1090f);
        float f = Math.min(1f, (br - x0) / (1286f * k));
        float x1 = x0 + 560f * k * f;
        chainX0 = x0;
        chainX1 = x1;
        chainLineY = py(418f);
        earlyLateX = x0;
        earlyLateBase = py(539.5f);
        earlyLateMaxW = x1 - x0;
        hintX = x0;
        hintBase = py(585.5f);
        hintMaxW = x1 - x0;
        hintCap = 18.7f * k;
        hintLine = 9f * k;
        dividerX0 = x0;
        dividerX1 = x1;
        dividerY = py(641.5f);
        rowLabelX = x0;
        rowCountRight = x1;
        rowBase[0] = py(723.5f);
        perfectBase = py(758.5f);
        // ECHO, LOST and MINES share the rest down to the score's baseline, MINES on it
        float pitch = (980f - 758.5f) / 3f;
        rowBase[1] = py(758.5f + pitch);
        rowBase[2] = py(758.5f + 2f * pitch);
        rowBase[3] = py(980f);

        // right: the character area from the jacket's top, as tall as the room over the buttons times f
        charY1 = py(304f);
        float charH = (charY1 - charY0) * f;
        charX0 = br - charH * CHAR_ASPECT;
        charY0 = charY1 - charH;
    }

    /** World x of a blueprint x in the left column (kept to the left edge inside the side space). */
    private float lx(float x) {
        return bl + (x - 24f) * s;
    }

    /** World x of a blueprint x at the right (kept to the right edge inside the side space). */
    private float rx(float x) {
        return br - (1896f - x) * s;
    }

    /** World y of a blueprint y (from the top) above the jacket: kept to the safe top. */
    private float ty(float y) {
        return st - (y - 24f) * s;
    }

    /** World y in the jacket's band: half of a tall screen's extra height above it. */
    private float jy(float y) {
        return ty(y) - extra * 0.5f;
    }

    /** World y under the jacket: kept to the safe bottom. */
    private float by(float y) {
        return ty(y) - extra;
    }

    /** World x of an x in the three-column frame (from the left edge inside the side space). */
    private float px(float x) {
        return bl + (x - 24f) * k;
    }

    /** World y of a y (from the top) in the three-column frame. */
    private float py(float y) {
        return st - (y - 24f) * k;
    }
}
