package org.kelvinizer.screens.result;

import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Ui;

/**
 * 结算画面每个元素在当前视口下的位置（世界坐标），每次窗口大小改变时重新计算，不分配内存。
 * 一般屏幕用三栏布局（左曲绘、中分数和判定、右评级印章和数据卡），可见高度达到 1360 的 4:3 平板改用上下堆叠布局。
 *
 * <p>Positions of every DIVE LOG element for the current viewport (DESIGN §6.2), in world units.
 *
 * <ul>
 *   <li><b>Three-column</b> (visible height &lt; 1,360): the middle block is designed as a
 *       1,080-tall block centred on y = 540, the header anchors to the safe top and the strip to
 *       the safe bottom, so 16:10 and 3:2 get breathing room. Left column at SL + 72, right
 *       column (384 wide) at SR − 72. Below aspect 2.0 the centre column fills the gap between
 *       them (600–1,380 at 16:9); at aspect ≥ 2.0 it is 880 wide centred on x 960. The score
 *       (9 digits since scoring v2, DESIGN §18.2) is NUMBER 80 / 88 shrunk to fit the column
 *       (72 at 16:9, 81 at 20:9).</li>
 *   <li><b>Stacked</b> (visible height ≥ 1,360, 4:3 tablets): header band, jacket + title +
 *       stamp band, centred score, judgement rows and trace on the left, badge and tiles on the
 *       right, the strip, and the buttons on their own row at the safe bottom.</li>
 * </ul>
 *
 * <p>Plain mutable fields; {@link #compute(int)} refills them on every resize (no allocation).
 */
public final class ResultLayout {
    /** Visible height from which the stacked layout is used. */
    public static final float STACKED_MIN_HEIGHT = 1360f;
    /** Bar thickness of the judgement rows. */
    public static final float BAR_H = 10f;

    /** True for the stacked (tablet) layout. */
    public boolean stacked;
    /** True for three-column at aspect ≥ 2.0. */
    public boolean wide20;
    /** Ui generation the layout was computed for. */
    public int generation = -1;
    /** Count rows shown (3, or 4 with MINES). */
    public int rowsShown = 3;

    // header ---------------------------------------------------------------------------------
    /** Header band baseline (the meta line's; the jacket hangs under it) and the meta text's right edge. */
    public float headerBase, metaRightX;
    /** AUTO watermark anchor (right edge, baseline). */
    public float watermarkX, watermarkBase;
    /** Ghost layer numeral baseline (P2); drawn centred in the widest gutter when it fits. */
    public float ghostBase;
    /** Widest empty gutter between columns at aspect ≥ 2 (x0 &gt;= x1 when there is none). */
    public float gutterX0, gutterX1;

    // left column ----------------------------------------------------------------------------
    /** Jacket square. */
    public float jacketX, jacketY, jacketSize;
    /** Title left x, cap top, max width; artist cap top. */
    public float titleX, titleCapTop, titleMaxW, artistCapTop;
    /** Difficulty chip. */
    public float chipX, chipY, chipW = 220f, chipH = 52f;

    // centre column --------------------------------------------------------------------------
    /** Score anchor x (left edge, or centre when {@link #scoreCentred}), baseline and cap. */
    public float scoreX, scoreBase, scoreCap;
    /** True when the score (and the best line) are centred on {@link #scoreX}. */
    public boolean scoreCentred;
    /** Best line anchor x and baseline. */
    public float bestX, bestBase;
    /** Judgement rows: label x, count right edge, bar span. */
    public float rowLabelX, rowCountRight, barX0, barX1;
    /** Row baselines (3 rows, or 4 with the MINES row). */
    public final float[] rowBase = new float[4];
    /** Baseline of the PERFECT sub-count line under the RESONANCE row (DESIGN §18.2). */
    public float perfectBase;
    /** RESONANCE CHAIN row (DESIGN §18.2): label / value baseline and its 3 px bar's y. */
    public float chainBase, chainBarY;
    /** Early / late line x and baseline. */
    public float earlyLateX, earlyLateBase;
    /** Echo trace box. */
    public float traceX0, traceX1, traceY0, traceY1;
    /** Offset hint link baseline (under the trace; under the tiles when stacked). */
    public float hintBase;
    /** Offset hint link left edge and maximum width. */
    public float hintX, hintMaxW;

    // right column ---------------------------------------------------------------------------
    /** Grade stamp centre, ring radius and letter cap. */
    public float stampX, stampY, stampR, stampCap;
    /** Stamp column (flash rect at impact). */
    public float stampColX0, stampColX1;
    /** Clear badge centre and size. */
    public float badgeX, badgeY, badgeW = 320f, badgeH = 56f;
    /** Tiles (accuracy, max combo). */
    public final float[] tileX = new float[2], tileY = new float[2];
    /** Tile size. */
    public float tileW, tileH;

    // strip ----------------------------------------------------------------------------------
    /** Strip panel. */
    public float stripX, stripY, stripW, stripH;
    /** Avatar centre and size. */
    public float avatarX, avatarY, avatarSize = 96f;
    /** Profile block: text x, name baseline, EXP bar, EXP text baseline. */
    public float profX, nameBase, shellX, shellY, shellW, chipBase;
    /** Depth block: left x, right limit, label baseline, numbers baseline, delta baseline. */
    public float depthX, depthRight, depthLabelBase, depthNumBase, depthDeltaBase;
    /** Mini gauge (vertical) left of the depth block. */
    public float gaugeX, gaugeY0, gaugeY1;
    /** Alert banner centre (RANK UP / LEVEL UP). */
    public float alertX, alertY;

    // buttons --------------------------------------------------------------------------------
    /** RETRY and CONTINUE rectangles (x, y, w, h). */
    public float retryX, retryY, retryW, retryH, contX, contY, contW, contH;

    /**
     * Recomputes every position for the current {@link Ui} metrics.
     *
     * @param rows judgement rows shown (3, or 4 with the MINES row)
     */
    public void compute(int rows) {
        generation = Ui.generation();
        rowsShown = rows >= 4 ? 4 : 3;
        stacked = Ui.worldH() >= STACKED_MIN_HEIGHT - 0.5f;
        wide20 = !stacked && Ui.aspect() >= 2.0f - 0.002f;
        if (stacked) stacked(rows);
        else threeColumn(rows);
        alertX = (stripX + stripX + stripW) * 0.5f;
        alertY = stripY + stripH + 86f;
    }

    // ------------------------------------------------------------------ three-column
    private void threeColumn(int rows) {
        float sl = Ui.safeLeft(), sr = Ui.safeRight(), st = Ui.safeTop(), sb = Ui.safeBottom();
        float leftX = sl + 72f;
        float rightR = sr - 72f, rightL = rightR - 384f;
        jacketSize = 440f;
        float cx0, cx1;
        if (wide20) {
            cx0 = 520f;
            cx1 = 1400f;
            // cutouts may push the side columns inward: keep 48 between columns
            cx0 = Math.max(cx0, leftX + jacketSize + 48f);
            cx1 = Math.min(cx1, rightL - 48f);
        } else {
            float minCentre = 640f;
            float avail = rightL - 60f - (leftX + 64f);
            if (avail - jacketSize < minCentre) jacketSize = Math.max(300f, avail - minCentre);
            cx0 = leftX + jacketSize + 64f;
            cx1 = rightL - 60f;
        }
        float centreW = cx1 - cx0;

        headerBase = st - 24f - 20f;
        metaRightX = rightR;
        watermarkX = sr - 16f;
        watermarkBase = 560f;
        ghostBase = 420f;
        gutterX0 = 0f;
        gutterX1 = 0f;
        if (wide20) {
            float l0 = leftX + jacketSize + 24f, l1 = cx0 - 24f;
            float r0 = cx1 + 24f, r1 = rightL - 24f;
            if (l1 - l0 >= r1 - r0) {
                gutterX0 = l0;
                gutterX1 = l1;
            } else {
                gutterX0 = r0;
                gutterX1 = r1;
            }
        }

        jacketX = leftX;
        jacketY = 940f - jacketSize;
        titleX = leftX;
        titleMaxW = jacketSize;
        titleCapTop = jacketY - 44f;
        artistCapTop = titleCapTop - 52f;
        chipX = leftX;
        chipY = artistCapTop - 32f - chipH - 20f;

        scoreCentred = false;
        scoreX = cx0;
        float design = wide20 ? 88f : 80f;
        // 9 digits + 2 commas = 10.8 × cap wide (Mokoto cells: digit 1.10, comma 0.45)
        scoreCap = Math.min(design, centreW / SCORE_WIDTH_CAPS);
        scoreBase = 920f - scoreCap;
        bestX = cx0;
        bestBase = bestBaseUnder(scoreBase, scoreCap);

        float barW = Math.min(390f, centreW * 0.48f);
        barX1 = cx1;
        barX0 = cx1 - barW;
        rowCountRight = barX0 - 30f;
        rowLabelX = cx0;
        // RESONANCE (+ its PERFECT line), ECHO, LOST, [MINES], RESONANCE CHAIN, early / late, trace
        if (rows >= 4) {
            rows(724f, 450f);
            traceY0 = 346f;
            traceY1 = 440f;
        } else {
            rows(716f, 500f);
            traceY0 = 380f;
            traceY1 = 480f;
        }
        earlyLateX = cx0;
        traceX0 = cx0;
        traceX1 = cx1;
        hintBase = traceY0 - 78f;
        hintX = traceX0;
        hintMaxW = traceX1 - traceX0;

        stampX = rightL + 192f;
        stampY = 760f;
        stampR = 170f;
        stampCap = 200f;
        stampColX0 = rightL - 24f;
        stampColX1 = rightR + 24f;
        badgeX = stampX;
        badgeY = 548f;
        tileW = 184f;
        tileH = 110f;
        tileX[0] = rightL;
        tileX[1] = rightR - tileW;
        tileY[0] = tileY[1] = 380f;

        stripX = sl;
        stripW = sr - sl;
        stripY = sb + 8f;
        stripH = 188f;
        float bh = Math.max(Button.standardHeight(), 112f);
        contW = 300f;
        contH = bh;
        contX = sr - 24f - contW;
        contY = stripY + (stripH - bh) * 0.5f;
        retryW = 260f;
        retryH = bh;
        retryX = contX - 32f - retryW;
        retryY = contY;

        avatarX = sl + 24f + avatarSize * 0.5f;
        avatarY = stripY + stripH * 0.5f;
        profX = sl + 24f + avatarSize + 28f;
        nameBase = stripY + stripH - 52f;
        shellW = 420f;
        shellX = profX;
        shellY = stripY + stripH * 0.5f - 7f;
        chipBase = stripY + 34f;

        depthX = Math.max(profX + shellW + 110f, sl + 0.34f * stripW);
        depthRight = retryX - 40f;
        depthLabelBase = stripY + stripH - 46f;
        depthNumBase = stripY + stripH * 0.5f - 16f;
        depthDeltaBase = stripY + 32f;
        gaugeX = depthX - 34f;
        gaugeY0 = stripY + 26f;
        gaugeY1 = stripY + stripH - 26f;
    }

    /** Width of the 9-digit grouped score in caps (9 × 1.10 + 2 × 0.45 = 10.8) plus a margin. */
    static final float SCORE_WIDTH_CAPS = 10.85f;

    /**
     * Stacks the stats rows from the RESONANCE baseline {@code top} down to the early / late
     * baseline {@code elBase}: the PERFECT line 26 under RESONANCE, ECHO 64 under it, the next
     * rows 52 apart, then the RESONANCE CHAIN row (label and value, its 3 px bar 14 under the
     * baseline) half way between the last count row and the early / late line.
     */
    private void rows(float top, float elBase) {
        rows(top, elBase, 64f, 52f);
    }

    /** {@link #rows(float, float)} with the RESONANCE → ECHO gap and the row step given. */
    private void rows(float top, float elBase, float firstGap, float step) {
        rowBase[0] = top;
        perfectBase = top - Math.min(26f, firstGap * 0.42f);
        float next = top - firstGap;
        for (int i = 1; i < 4; i++) rowBase[i] = next - (i - 1) * step;
        float lastCount = rowBase[rowsShown - 1];
        earlyLateBase = elBase;
        chainBase = lastCount - Math.max(48f, Math.min(56f, (lastCount - elBase) * 0.55f));
        chainBarY = chainBase - 14f;
    }

    /**
     * Best-line baseline under a score row: the NEW BEST pill (its top 36 above the baseline)
     * keeps 16 clear of the score's comma descenders (Mokoto ',' reaches 0.113 cap below).
     */
    static float bestBaseUnder(float scoreBase, float scoreCap) {
        return scoreBase - 0.113f * scoreCap - 16f - 36f;
    }

    // ------------------------------------------------------------------ stacked
    private void stacked(int rows) {
        float sl = Ui.safeLeft(), sr = Ui.safeRight(), st = Ui.safeTop(), sb = Ui.safeBottom();
        float leftX = sl + 72f;
        float rightR = sr - 72f;

        headerBase = st - 24f - 20f;
        metaRightX = rightR;
        watermarkX = sr - 16f;
        watermarkBase = 1020f;
        ghostBase = 420f;
        gutterX0 = 0f;
        gutterX1 = 0f;

        // jacket top sits 48 under the header baseline so the sonar frame (12 out) clears the header
        jacketSize = 300f;
        jacketX = leftX;
        jacketY = Math.min(860f, headerBase - 48f - jacketSize);
        titleX = jacketX + jacketSize + 40f;
        titleMaxW = Math.min(784f, (rightR - 380f) - titleX);
        titleCapTop = jacketY + jacketSize - 4f;
        artistCapTop = titleCapTop - 58f;
        chipX = titleX;
        chipY = titleCapTop - 166f;

        stampX = rightR - 224f;
        stampY = jacketY + jacketSize * 0.5f;
        stampR = 150f;
        stampCap = 176f;
        stampColX0 = stampX - 200f;
        stampColX1 = stampX + 200f;

        scoreCentred = true;
        scoreCap = 104f;
        scoreX = 960f;
        scoreBase = 720f;
        bestX = 960f;
        bestBase = bestBaseUnder(scoreBase, scoreCap);

        float leftR = 1000f;
        rowLabelX = leftX;
        barX1 = leftR;
        barX0 = leftR - 370f;
        rowCountRight = barX0 - 30f;
        if (rows >= 4) {
            // the tightest case (MINES row + PERFECT line + RESONANCE CHAIN): rows 48 apart and a
            // 64-tall trace whose μ label clears the strip (lowered to y 16 on 4:3)
            rows(604f, 358f, 58f, 48f);
            traceY0 = 270f;
            traceY1 = 334f;
        } else {
            rows(600f, 384f);
            traceY0 = 280f;
            traceY1 = 372f;
        }
        earlyLateX = leftX;
        traceX0 = leftX;
        traceX1 = leftR;
        // no room between the trace and the strip: the hint sits under the tiles (below)

        float rightL = 1080f;
        badgeX = (rightL + rightR) * 0.5f;
        badgeY = 580f;
        badgeW = 360f;
        tileW = (rightR - rightL - 24f) * 0.5f;
        tileH = 120f;
        tileX[0] = rightL;
        tileX[1] = rightR - tileW;
        tileY[0] = tileY[1] = 380f;
        hintX = rightL;
        hintBase = tileY[0] - 56f;
        hintMaxW = rightR - rightL;

        float bh = Math.max(Button.standardHeight(), 112f);
        retryW = 400f;
        retryH = bh;
        contW = 440f;
        contH = bh;
        retryX = 520f;
        contX = 960f;
        retryY = contY = sb;

        stripX = sl;
        stripW = sr - sl;
        // 16 (was 40) since scoring v2 added the PERFECT line and the RESONANCE CHAIN row above
        stripY = Math.max(16f, sb + bh + 36f);
        stripH = 200f;

        avatarX = sl + 24f + avatarSize * 0.5f;
        avatarY = stripY + stripH * 0.5f;
        profX = sl + 24f + avatarSize + 28f;
        nameBase = stripY + stripH - 56f;
        shellW = 460f;
        shellX = profX;
        shellY = stripY + stripH * 0.5f - 7f;
        chipBase = stripY + 36f;

        depthX = 1040f;
        depthRight = sr - 24f;
        depthLabelBase = stripY + stripH - 50f;
        depthNumBase = stripY + stripH * 0.5f - 16f;
        depthDeltaBase = stripY + 34f;
        gaugeX = depthX - 34f;
        gaugeY0 = stripY + 28f;
        gaugeY1 = stripY + stripH - 28f;
    }
}
