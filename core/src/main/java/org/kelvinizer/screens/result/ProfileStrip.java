package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 结算画面底部的潜水员条：头像、名字、贝壳数从旧值数到新值和 +贝壳 标签（点击可看贝壳明细）。
 * 深度区显示深度从旧值数到新值、小深度计的标记在当前深度分档里下沉；自动播放和新手教程改为一行说明。
 *
 * <p>The profile strip (DESIGN §6.2, §6.3 phases D and E, §11): avatar, name, the shell count
 * {@code 1,356 → 1,478} counting up in the shells phase, the {@code +122} shell chip (tap → itemised
 * popover), and the depth block: {@code DEPTH}, {@code -2,536 m → -2,560 m} counting {@code pow4Out}
 * (depths read negative since 14:20), the mini gauge whose marker sinks through the current depth
 * tier, and the {@code ▼ -24 m} pop.
 * Autoplay and the tutorial show a note instead of the depth block.
 *
 * <p>All motion is a function of the timeline; no allocation per frame. Shapes go down at once;
 * text and the avatar image join their page groups in the {@link TextQueue}.
 */
public final class ProfileStrip implements TextQueue.Deferred {
    /** ▼ delta pop offset after the depth start. */
    public static final float DELTA_OFFSET = 0.55f;
    /** Cap of the shell count digits. */
    private static final float SHELL_CAP = 24f;
    /** Size of the shell icon before the count. */
    private static final float SHELL_ICON = 28f;
    /** Size of the shell icon in the +N chip. */
    private static final float CHIP_ICON = 22f;

    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());

    private final ResultKit k;
    private final Avatar avatars;
    // deferred avatar image
    private ResultModel avM;
    private float avX, avY, avSize, avAlpha;
    private final Color tmp = new Color();

    // last drawn geometry (for input and particles)
    private float chipX, chipY, chipW, chipH;
    private float markerX, markerY;
    private float lastOy;

    /** Creates the strip renderer. */
    public ProfileStrip(ResultKit kit, Avatar avatars) {
        this.k = kit;
        this.avatars = avatars;
    }

    /** Measures every string the strip can draw (CJK glyphs are generated now, while covered). */
    public void warm(ResultModel m) {
        TextQueue q = k.text;
        q.warm(Fonts.Role.UI_BOLD, 22f, m.name);
        q.warm(Fonts.Role.UI_LABEL, 20f, m.stripNote);
    }

    /** Shell count shown at {@code t} (counts before → after over the shells phase, {@code pow3Out}). */
    public long shellsShown(ResultModel m, Choreo c, float t) {
        float st = c.shellsT();
        if (!m.hasShells || st < 0f || t < st) return m.shellsBefore;
        float p = Anim.POW3_OUT.apply(Anim.progress(t, st, Choreo.SHELLS_D));
        return m.shellsBefore + Math.round((m.shellsAfter - m.shellsBefore) * (double) p);
    }

    /** Depth shown at {@code t} (counts old → new over 800 ms {@code pow4Out}). */
    public float depthShown(ResultModel m, Choreo c, float t) {
        float dt = c.depthT();
        if (dt < 0f || t < dt) return m.depthBefore;
        float p = Anim.POW4_OUT.apply(Anim.progress(t, dt, Choreo.DEPTH_D));
        return m.depthBefore + (m.depthAfter - m.depthBefore) * p;
    }

    /** Vertical offset of the strip (rise y −160 → 0) at {@code t}. */
    public static float rise(Choreo c, float t, boolean reduceMotion) {
        if (reduceMotion) return 0f;
        return -160f * (1f - Anim.POW3_OUT.apply(Anim.progress(t, c.stripT(), Choreo.STRIP_D)));
    }

    /** Alpha of the strip at {@code t}. */
    public static float fade(Choreo c, float t, boolean reduceMotion) {
        return Anim.progress(t, c.stripT(), reduceMotion ? Anim.SHORT : 0.2f);
    }

    /** Parts of a strip element: shapes, text, or the whole element while it animates. */
    private static final int P_SHAPES = 1, P_TEXT = 2, P_ANIM = 4;

    // per-frame depth line geometry (filled by layoutDepth)
    private float dNum0X, dM0X, dArrowX, dArrowW, dNum1X, dM1X;
    private float dShown;
    private int dDelta;
    // per-frame shell line geometry (filled by layoutShells)
    private float sNum0X, sArrowX, sNum1X;
    private long sShown;

    /**
     * Draws the strip. Shapes are drawn first, then the avatar, then the text grouped by font so
     * the batch switches texture as few times as possible; elements that are popping are drawn
     * whole afterwards, each inside its own transform.
     *
     * @param accent layer accent (FIRST DIVE, deltas, gauge marker)
     * @param time   real time for the avatar frame animation
     */
    public void draw(ResultModel m, ResultLayout l, Choreo c, float t, float time, Color accent, boolean reduceMotion,
                     float alpha) {
        if (t < c.stripT()) return;
        float oy = rise(c, t, reduceMotion);
        lastOy = oy;
        float a = alpha * fade(c, t, reduceMotion);
        layoutShells(m, l, c, t);
        if (m.hasDepth) layoutDepth(m, l, c, t);

        drawShapes(m, l, c, t, oy, accent, a);
        // the avatar frame is a shape; the cached sigil image joins its own page group
        avatars.drawFrame(m.avatar, l.avatarX, l.avatarY + oy, l.avatarSize, a, time);
        if (a > 0.002f) {
            avM = m;
            avX = l.avatarX;
            avY = l.avatarY + oy;
            avSize = l.avatarSize;
            avAlpha = a;
            k.text.deferred(avatars.region(k.batch, m.avatar).getTexture(), this, 0);
        }
        drawTexts(m, l, c, t, oy, accent, a);

        drawShellChip(m, l, c, t, oy, a, P_ANIM);
        if (m.hasDepth) drawDelta(m, l, c, t, oy, accent, a, P_ANIM);
    }

    /** Deferred avatar image (premultiplied, like {@code Avatar.draw}). */
    @Override
    public void drawDeferred(int arg) {
        Batch b = k.batch;
        TextureRegion r = avatars.region(b, avM.avatar);
        int srcC = b.getBlendSrcFunc(), dstC = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha(), dstA = b.getBlendDstFuncAlpha();
        float old = b.getPackedColor();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.setColor(avAlpha, avAlpha, avAlpha, avAlpha);
        b.draw(r, avX - avSize * 0.5f, avY - avSize * 0.5f, avSize, avSize);
        b.setBlendFunctionSeparate(srcC, dstC, srcA, dstA);
        b.setPackedColor(old);
    }

    private void drawShapes(ResultModel m, ResultLayout l, Choreo c, float t, float oy, Color accent, float a) {
        float sy = l.stripY + oy;
        // panel
        k.draw.roundRect(l.stripX, sy, l.stripW, l.stripH, 22f, Theme.PANEL, 0.92f * a);
        k.draw.roundRectStroke(l.stripX, sy, l.stripW, l.stripH, 22f, 1f, Theme.STROKE, a);
        k.draw.rectGradientH(l.stripX + 22f, sy + l.stripH - 2f, l.stripW - 44f, 1.5f, Theme.STROKE, accent, 0.5f * a);

        // shell line: icon, and the arrow between the counts when this run grants shells
        float cy = shellLineY(l, oy);
        k.draw.shell(l.shellX + SHELL_ICON * 0.5f, cy, SHELL_ICON, Theme.GOLD, SHELL_RIB, a);
        if (m.hasShells) {
            float ax0 = sArrowX + 2f, ax1 = sArrowX + 22f;
            k.draw.line(ax0, cy, ax1 - 6f, cy, 2f, Theme.INK3, a);
            k.triangle(ax1 - 9f, cy + 6f, ax1 - 9f, cy - 6f, ax1, cy, Theme.INK3, a);
        }
        drawShellChip(m, l, c, t, oy, a, P_SHAPES);
        if (!m.hasDepth) return;

        // depth arrow (a shape, so it batches with the panel)
        float ay = l.depthNumBase + oy + 15f;
        float ax0 = dArrowX + 2f, ax1 = dArrowX + dArrowW - 2f;
        k.draw.line(ax0, ay, ax1 - 6f, ay, 2f, Theme.INK3, a);
        k.triangle(ax1 - 9f, ay + 6f, ax1 - 9f, ay - 6f, ax1, ay, Theme.INK3, a);
        drawGauge(l, dShown, oy, accent, a);
        drawDelta(m, l, c, t, oy, accent, a, P_SHAPES);
    }

    private void drawTexts(ResultModel m, ResultLayout l, Choreo c, float t, float oy, Color accent, float a) {
        TextQueue q = k.text;
        q.textFit(Fonts.Role.UI_BOLD, 22f, m.name, l.profX, l.nameBase + oy, Align.left, l.shellW, 0.82f,
            Theme.INK1, a);
        if (m.hasDepth) {
            q.text(Fonts.Role.UI_LABEL, 18f, "DEPTH", l.depthX, l.depthLabelBase + oy, Align.left, Theme.INK3, a);
        }
        // shell counts: before → after (gold while counting); just the total when nothing was granted
        float sb = shellLineY(l, oy) - SHELL_CAP * 0.5f;
        if (m.hasShells) {
            boolean counting = t >= c.shellsT() && t < c.shellsT() + Choreo.SHELLS_D;
            q.digits(SHELL_CAP, m.shellsBefore, true, sNum0X, sb, Align.left, Theme.INK2, a);
            q.digits(SHELL_CAP, sShown, true, sNum1X, sb, Align.left, counting ? Theme.GOLD : Theme.INK1, a);
        } else {
            q.digits(SHELL_CAP, m.shellsBefore, true, sNum0X, sb, Align.left, Theme.INK1, 0.8f * a);
        }
        drawShellChip(m, l, c, t, oy, a, P_TEXT);
        if (!m.hasDepth) {
            drawNote(m, l, oy, a);
            return;
        }
        float numBase = l.depthNumBase + oy;
        boolean counting = c.depthT() >= 0f && t >= c.depthT() && t < c.depthT() + Choreo.DEPTH_D;
        // depths read negative (below the surface)
        q.digits(30f, -m.depthBefore, true, dNum0X, numBase, Align.left, Theme.INK2, a);
        q.digits(30f, -(long) Math.floor(dShown), true, dNum1X, numBase, Align.left, counting ? accent : Theme.INK1, a);
        q.text(Fonts.Role.UI, 20f, "m", dM0X, numBase, Align.left, Theme.INK3, a);
        q.text(Fonts.Role.UI, 20f, "m", dM1X, numBase, Align.left, Theme.INK3, a);
        drawDelta(m, l, c, t, oy, accent, a, P_TEXT);
    }

    /** Centre y of the shell line (where the EXP bar was). */
    private static float shellLineY(ResultLayout l, float oy) {
        return l.shellY + 5f + oy;
    }

    /** Positions of the {@code before → after} shell counts for this frame. */
    private void layoutShells(ResultModel m, ResultLayout l, Choreo c, float t) {
        TextQueue q = k.text;
        sShown = shellsShown(m, c, t);
        sNum0X = l.shellX + SHELL_ICON + 10f;
        sArrowX = sNum0X + q.digitsWidth(SHELL_CAP, digitCount(m.shellsBefore), true) + 12f;
        sNum1X = sArrowX + 22f + 12f;
    }

    /** Positions of the {@code before m → after m} line for this frame. */
    private void layoutDepth(ResultModel m, ResultLayout l, Choreo c, float t) {
        TextQueue q = k.text;
        dShown = depthShown(m, c, t);
        dDelta = m.depthAfter - m.depthBefore;
        float mw = q.width(Fonts.Role.UI, 20f, "m");
        dNum0X = l.depthX;
        float minus = q.fixedWidth(30f, "-");
        float w0 = q.digitsWidth(30f, digitCount(m.depthBefore), true) + (m.depthBefore > 0 ? minus : 0f);
        dM0X = dNum0X + w0 + 6f;
        dArrowX = dM0X + mw + 16f;
        dArrowW = 26f;
        dNum1X = dArrowX + dArrowW + 16f;
        long shownM = (long) Math.floor(dShown);
        float w1 = q.digitsWidth(30f, digitCount(shownM), true) + (shownM > 0 ? minus : 0f);
        dM1X = dNum1X + w1 + 6f;
    }

    private static int digitCount(long v) {
        if (v < 0) v = -v;
        int n = 1;
        while (v >= 10) {
            v /= 10;
            n++;
        }
        return n;
    }

    /** {@code +122} chip with a shell icon; pops 0 → 1 at the shells start. */
    private void drawShellChip(ResultModel m, ResultLayout l, Choreo c, float t, float oy, float a, int parts) {
        if (parts == P_SHAPES) chipW = 0f;
        if (!m.hasShells || t < c.shellsT()) return;
        float p = Anim.progress(t, c.shellsT(), Anim.POP);
        float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
        boolean rest = p >= 1f && Math.abs(s - 1f) < 1e-3f;
        if (rest == (parts == P_ANIM)) return;
        float ca = a * Math.min(1f, p * 4f);
        float tw = k.text.width(Fonts.Role.NUMBER, 24f, m.shellsText);
        float tb = l.chipBase + oy;
        chipX = l.profX - 10f;
        chipY = tb - 12f;
        chipW = tw + 8f + CHIP_ICON + 24f;
        chipH = 24f + 24f;
        float cx = chipX + chipW * 0.5f, cy = chipY + chipH * 0.5f;
        boolean px = k.pushIf(cx, cy, 0f, s, s);
        float x0 = k.ox - chipW * 0.5f, y0 = k.oy - chipH * 0.5f;
        float tx = x0 + 12f;
        if ((parts & (P_SHAPES | P_ANIM)) != 0) {
            k.draw.roundRect(x0, y0, chipW, chipH, 12f, Theme.GOLD, 0.12f * ca);
            k.draw.roundRectStroke(x0, y0, chipW, chipH, 12f, 1.5f, Theme.GOLD, 0.45f * ca);
            k.draw.shell(tx + tw + 8f + CHIP_ICON * 0.5f, k.oy, CHIP_ICON, Theme.GOLD, SHELL_RIB, ca);
        }
        if ((parts & (P_TEXT | P_ANIM)) != 0) {
            k.text.text(Fonts.Role.NUMBER, 24f, m.shellsText, tx, k.oy, Align.left | Fonts.VCENTER, Theme.GOLD, ca);
        }
        k.popIf(px);
    }

    private void drawNote(ResultModel m, ResultLayout l, float oy, float a) {
        if (m.stripNote.isEmpty()) return;
        float w = l.depthRight - l.depthX;
        Color c = m.autoplay ? Theme.AUTO : Theme.INK2;
        k.text.textFit(Fonts.Role.UI_LABEL, 20f, m.stripNote, l.depthX, l.stripY + l.stripH * 0.5f + oy,
            Align.left | Fonts.VCENTER, w, 0.9f, c, a);
    }

    /** {@code ▼ -24 m}, popping at depth start + 0.55 s. */
    private void drawDelta(ResultModel m, ResultLayout l, Choreo c, float t, float oy, Color accent, float a,
                           int parts) {
        float dt0 = c.depthT() + DELTA_OFFSET;
        if (c.depthT() < 0f || t < dt0) return;
        TextQueue q = k.text;
        float x = l.depthX;
        float db = l.depthDeltaBase + oy;
        float p = Anim.progress(t, dt0, Anim.POP);
        float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
        boolean rest = p >= 1f && Math.abs(s - 1f) < 1e-3f;
        if (rest == (parts == P_ANIM)) return;
        float tw = q.width(Fonts.Role.UI, 20f, m.depthDeltaText);
        float triW = dDelta != 0 ? 22f : 0f;
        float gw = triW + tw;
        float da = a * Math.min(1f, p * 4f);
        Color dc = dDelta > 0 ? accent : Theme.INK3;
        boolean px = k.pushIf(x + gw * 0.5f, db + 10f, 0f, s, s);
        float lx = k.ox - gw * 0.5f, ly = k.oy;
        if ((parts & (P_SHAPES | P_ANIM)) != 0) {
            if (dDelta > 0) k.triangle(lx, ly + 7f, lx + 15f, ly + 7f, lx + 7.5f, ly - 7f, dc, da);
            else if (dDelta < 0) k.triangle(lx, ly - 7f, lx + 15f, ly - 7f, lx + 7.5f, ly + 7f, dc, da);
        }
        if ((parts & (P_TEXT | P_ANIM)) != 0) {
            q.text(Fonts.Role.UI, 20f, m.depthDeltaText, lx + triW, ly - 10f, Align.left, dc, da);
        }
        k.popIf(px);
    }

    /** Mini gauge: the marker sinks from the start of the depth tier (top) to the next tier (bottom). */
    private void drawGauge(ResultLayout l, float shown, float oy, Color accent, float a) {
        float gx = l.gaugeX, y0 = l.gaugeY0 + oy, y1 = l.gaugeY1 + oy;
        k.draw.rect(gx - 1f, y0, 2f, y1 - y0, Theme.INK3, 0.5f * a);
        for (int i = 0; i <= 4; i++) {
            float ty = y0 + (y1 - y0) * i / 4f;
            k.draw.rect(gx - (i % 2 == 0 ? 7f : 4f), ty - 0.75f, i % 2 == 0 ? 14f : 8f, 1.5f, Theme.INK3, 0.5f * a);
        }
        float prog = Depth.tierProgress((int) Math.floor(shown));
        markerX = gx;
        markerY = y1 - prog * (y1 - y0);
        tmp.set(accent);
        k.draw.glowDot(markerX, markerY, 30f, tmp, 0.5f * a);
        k.triangle(markerX + 4f, markerY, markerX + 16f, markerY + 7f, markerX + 16f, markerY - 7f, tmp, a);
        k.draw.disc(markerX, markerY, 4.5f, Theme.INK1, a);
    }

    // ------------------------------------------------------------------ geometry for the screen
    /** True when (x, y) hits the +shells chip (grown by {@code grow}). */
    public boolean hitShellChip(float x, float y, float grow) {
        return chipW > 0f && x >= chipX - grow && x <= chipX + chipW + grow && y >= chipY - grow
            && y <= chipY + chipH + grow;
    }

    /** +shells chip centre x. */
    public float chipCenterX() {
        return chipX + chipW * 0.5f;
    }

    /** +shells chip top. */
    public float chipTop() {
        return chipY + chipH;
    }

    /** Gauge marker position. */
    public float markerX() {
        return markerX;
    }

    /** Gauge marker position. */
    public float markerY() {
        return markerY;
    }

    /** Vertical offset of the strip at the last draw. */
    public float lastOffset() {
        return lastOy;
    }
}
