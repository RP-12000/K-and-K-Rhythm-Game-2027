package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 结算画面左上的玩家块和右上的贝壳胶囊：玩家块和地图左上角的一样（白圈圆头像、带光带的名字、两头尖的六边形深度标签，不写 DEPTH），
 * 贝壳胶囊里是贝壳数。这次加深了的话，深度标签先显示旧值并放大发亮，右下角浮出一个小六边形框写着这次下降了多少米，然后数字像
 * 里程表一样滚到新值，前后约 2.5 秒；贝壳在深度动画之后（没加深就照原来的时间）：左边浮出一个 +N 的贝壳泡，像两滴水一样和
 * 贝壳胶囊融成一个，再显示新的数。点玩家块看深度明细、点贝壳看贝壳明细，由画面处理。
 *
 * <p>The player block (top left) and the shell capsule (top right) of the result screen, split like the map's corners
 * (Kelvin 2026-10-19 06:04 「主界面的depth，用户名和贝壳也这样子分开到屏幕左上角和右上角」, 06:28 「把原来的那种贝壳和
 * profile的小的边界加上」; they replace the profile strip along the bottom). Since Kelvin 2026-10-20 19:45
 * (「depth不要出现了，就和之前那样的六边形架构啊，然后出现的下降也是有一个六边形外框。你看arcaea是怎么做的，原来的是菱形，后面
 * 变动的外框也是菱形。我们不要做的像arcaea那么大但是思路是一样的」) the block is the map's (the design thread's plan 5 as
 * {@code MapHud} draws it): no panel; the avatar in a disc with a white ring, the name on its fading band and under it
 * a tag pointed at both ends holding the depth, {@code -2,698 m}, with room for a minus sign and five digits and the
 * number centred; no DEPTH label (a panel with {@code DEPTH -2,698 m} before). The capsule is the gold pill (on a deep
 * gold fill, as on the map) with the shell icon and the count. Both drop in from the top at the strip time; the
 * capsule is sized for its final count. Taps (depth and shell breakdowns) are the screen's.
 *
 * <p>Kelvin 2026-10-20 18:45 「如果说深度值下降了，就显示旧的深度值，右下角浮现这次下降了多少，然后滚动显示到新的深度值。
 * 整个过程大概持续2.5秒，要引起玩家的注意。贝壳就普通一点，如果深度值更新了就等深度值完成再开始。就只是在左侧浮现一个加多少的
 * 贝壳泡，然后和贝壳那个东西合并，像两个水滴合并一样，再显示新的值。」:
 * <ul>
 *   <li><b>Depth</b> (phase D, {@link Choreo#DEPTH_D}, only for a run that went deeper): the tag with the old depth
 *       grows to {@link #DEPTH_K}× about its left tip with a glow and a ring, {@code ▼ 37} floats up at its lower
 *       right in a smaller pointed frame tucked under it (since 19:45, as Arcaea's change frame beside its potential;
 *       bare text before), the digits roll like an odometer to the new depth, which lands with a flash and a second
 *       ring, and the tag shrinks back. The frame stays.</li>
 *   <li><b>Shells</b> (phase E, {@link Choreo#SHELLS_D}): a {@code +151} bubble rises at the capsule's left, is
 *       drawn in and merges with it (a neck grows between their ends like two drops), the capsule wobbles and the
 *       count rolls on to the new total.</li>
 * </ul>
 *
 * <p>All motion is a function of the timeline; no allocation per frame. Shapes go down at once; text and the avatar
 * image join their page groups in the {@link TextQueue} (text draws at once while the depth tag is scaled).
 */
public final class CornerHud implements TextQueue.Deferred {
    /** Depth phase beats after its start (s): pop in, the gain floating in, the roll, shrinking back. */
    public static final float POP_IN = 0.35f, GAIN_T = 0.25f, GAIN_D = 0.45f, ROLL_T = 0.70f, ROLL_D = 1.30f,
        BACK_T = 2.05f;
    /** Scale of the depth tag (about its left tip) at the height of phase D. */
    public static final float DEPTH_K = 1.3f;
    /** Shells phase beats after its start (s): the bubble rising, the pull into the capsule, the count. */
    public static final float BUBBLE_IN = 0.30f, PULL_T = 0.45f, PULL_D = 0.40f, COUNT_D = 0.40f;
    /** One opaque fill for the capsule, the bubble and the neck between them, so the merge shows no seams. */
    private static final Color CAPSULE_FILL = opaque(Theme.mix(Theme.SHELL_CAPSULE, Theme.GOLD, 0.16f, new Color()));
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private static final String UNIT = " m", ZERO = "0", COMMA = ",", MINUS = "-", ROOM = "-00,000";
    /** Longest name before it shrinks (blueprint units). */
    private static final float NAME_MAX = 420f;
    /** Slices of the neck between the bubble and the capsule. */
    private static final int NECK_N = 14;
    /**
     * The map's player block at full size ({@code MapHud}'s measures, Kelvin 2026-10-20 14:10–16:39; the map draws it
     * at 3/4): its height and how far in from the margin it starts; the avatar disc's radius, its white ring's radius
     * and width and the avatar drawn in it; the name's left edge, baseline under the column's top and cap; the depth
     * tag's left tip, height, tip depth, padding and the number's cap and baseline under the column's top; the name
     * and tag column's top and height; the name's band, its top and bottom under the column's top, its run past the
     * name and its alpha. Drawn here at the layout's frame height over {@link #BLOCK_H} (on 16:10 100 of 150, the room
     * between the top margin and the title band).
     */
    private static final float BLOCK_H = 150f, BLOCK_IN = 12f, AV_R = 75f, RING_R = 73.5f, RING_W = 3f, AV_ART = 90f,
        NAME_X = 165f, NAME_BASE = 21f, NAME_CAP = 24f, TAG_X = 166f, TAG_H = 62f, TAG_TIP = 22f, TAG_PAD = 14f,
        DEPTH_CAP = 36f, DEPTH_BASE = 87f, COL_Y = 25f, COL_H = 100f, BAND_TOP = -8f, BAND_BOTTOM = 28f,
        BAND_RUN = 140f, BAND_A = 0.95f;
    /**
     * The gain's frame in the block's measures (Kelvin 2026-10-20 19:45 「出现的下降也是有一个六边形外框」, smaller than
     * Arcaea's): its height, tip depth and padding, the triangle's width, the gap after it and the number's cap; its
     * left tip lies this far under the tag's right end and its centre this far under the tag's.
     */
    private static final float GAIN_H = 46f, GAIN_TIP = 16f, GAIN_PAD = 10f, GAIN_TRI = 17f, GAIN_GAP = 6f,
        GAIN_CAP = 24f, GAIN_TUCK = 10f, GAIN_DROP = 18f;
    /** The map block's colours: the disc, the shadows (under the disc and the tags, under the name), the tag. */
    private static final Color AV_DISC = Theme.rgb(0x05091C, 1f), SHADE = Theme.rgb(0x000012, 1f),
        NAME_SHADE = Theme.rgb(0x000014, 1f), TAG_FILL = Theme.rgb(0x080D26, 1f), TAG_EDGE = Theme.rgb(0x38B6FF, 1f),
        BAND_CLEAR = Theme.rgb(0x080D26, 0f);
    /** The name's soft shadow: four faint copies about 2 under it (block measures). */
    private static final float[] SHADE_DX = {-1.5f, 1.5f, -1f, 1f}, SHADE_DY = {-1.5f, -1.5f, -3f, -3f};

    private final ResultKit k;
    private final Avatar avatars;
    private final Color numCol = new Color(), cntCol = new Color(), edgeCol = new Color();
    /** Corners of the pointed tag being drawn, counter-clockwise from its left tip. */
    private final float[] hex = new float[12];
    // sizes for the current layout (measure)
    private float s = 1f, bk = 2f / 3f, nameCap = NAME_CAP * 2f / 3f, depthCap, gainCap;
    private float frameW, capsuleW, nameW, numW, unitW, tagW, gainNumW, gainW, bubbleW;
    // deferred avatar image
    private ResultModel avM;
    private float avX, avY, avSize, avAlpha;
    // last drawn geometry (input, particles, popovers)
    private float frameX, frameY, frameH, capX, capY, capH, tagX0, tagMid, lastOy, depthK = 1f;

    private static Color opaque(Color c) {
        c.a = 1f;
        return c;
    }

    /** Creates the corner HUD renderer. */
    public CornerHud(ResultKit kit, Avatar avatars) {
        this.k = kit;
        this.avatars = avatars;
    }

    /** Measures every string the HUD can draw (CJK glyphs are generated now, while covered). */
    public void warm(ResultModel m) {
        TextQueue q = k.text;
        q.warm(Fonts.Role.UI_BOLD, nameCap, m.name);
    }

    /** Sizes the block and the capsule for the final numbers of {@code m} in layout {@code l}. */
    public void measure(ResultModel m, ResultLayout l) {
        TextQueue q = k.text;
        s = l.s;
        frameX = l.frameX + BLOCK_IN * s;
        frameH = l.frameH;
        bk = frameH / BLOCK_H;
        nameCap = NAME_CAP * bk;
        nameW = Math.min(q.width(Fonts.Role.UI_BOLD, nameCap, m.name), NAME_MAX * s);
        depthCap = DEPTH_CAP * bk;
        numW = depthWidth(m.depthAfter, depthCap);
        unitW = q.width(Fonts.Role.NUMBER, depthCap, UNIT);
        tagW = (2f * TAG_TIP + 2f * TAG_PAD) * bk + Math.max(q.fixedWidth(depthCap, ROOM), numW) + unitW;
        gainCap = GAIN_CAP * bk;
        gainNumW = gainShown(m) ? q.fixedWidth(gainCap, m.depthGainText) : 0f;
        gainW = gainShown(m) ? (2f * GAIN_TIP + 2f * GAIN_PAD + GAIN_TRI + GAIN_GAP + GAIN_TUCK) * bk + gainNumW : 0f;
        float tagEnd = TAG_X * bk + tagW + (gainW > 0f ? gainW - GAIN_TUCK * bk : 0f);
        frameW = Math.max(2f * AV_R * bk, Math.max(NAME_X * bk + nameW, tagEnd));
        k.sb.setLength(0);
        Fonts.appendGrouped(k.sb, Math.max(m.shellsBefore, m.shellsAfter));
        capsuleW = 18f * s + 28f * s + 8f * s + q.fixedWidth(22f * s, k.sb) + 20f * s;
        bubbleW = m.hasShells ? q.fixedWidth(17f * s, m.shellsText) + 30f * s : 0f;
        capH = l.capsuleH;
    }

    /** Width of {@code -metres} in fixed NUMBER cells at {@code cap}. */
    private float depthWidth(long metres, float cap) {
        k.sb.setLength(0);
        Fonts.appendGrouped(k.sb, -metres);
        return k.text.fixedWidth(cap, k.sb);
    }

    /** True when the block shows {@code ▼ n} (a recorded run that went deeper: phase D runs). */
    private static boolean gainShown(ResultModel m) {
        return m.hasDepth && m.depthGain > 0;
    }

    /** Shell count shown at {@code t}: the old one until the bubble is in, then counting on ({@code pow3Out}). */
    public long shellsShown(ResultModel m, Choreo c, float t) {
        float st = c.shellsT();
        if (!m.hasShells || st < 0f) return m.shellsBefore;
        float p = Anim.POW3_OUT.apply(Anim.progress(t, st + PULL_T + PULL_D, COUNT_D));
        return m.shellsBefore + Math.round((m.shellsAfter - m.shellsBefore) * (double) p);
    }

    /** Depth shown at {@code t}: the old one until the roll, then rolling to the new one ({@code pow3} in-out). */
    public float depthShown(ResultModel m, Choreo c, float t) {
        float dt = c.depthT();
        if (dt < 0f) return m.depthBefore;
        float p = Anim.POW3.apply(Anim.progress(t, dt + ROLL_T, ROLL_D));
        return m.depthBefore + (m.depthAfter - m.depthBefore) * p;
    }

    /** True while the depth digits roll (the screen sends bubbles up from them). */
    public static boolean rolling(Choreo c, float t) {
        float dt = c.depthT();
        return dt >= 0f && t >= dt + ROLL_T && t < dt + ROLL_T + ROLL_D;
    }

    /** Vertical offset (drop from 40 above) at {@code t}. */
    private float drop(Choreo c, float t, boolean reduceMotion) {
        if (reduceMotion) return 0f;
        return 40f * s * (1f - Anim.POW3_OUT.apply(Anim.progress(t, c.stripT(), Choreo.STRIP_D)));
    }

    /** 0 → 1 → 0 over phase D ({@code u}: seconds since its start): up over {@link #POP_IN}, down from BACK_T. */
    private static float emphasis(float u) {
        if (u < 0f || u >= Choreo.DEPTH_D) return 0f;
        if (u < BACK_T) return Anim.POW3_OUT.apply(Anim.clamp01(u / POP_IN));
        return 1f - Anim.SINE.apply(Anim.clamp01((u - BACK_T) / (Choreo.DEPTH_D - BACK_T)));
    }

    /**
     * Draws the block and the capsule.
     *
     * @param accent layer accent (the depth glow and rings)
     * @param time   real time for the avatar frame animation
     */
    public void draw(ResultModel m, ResultLayout l, Choreo c, float t, float time, Color accent, boolean reduceMotion,
                     float alpha) {
        if (t < c.stripT()) return;
        float oy = drop(c, t, reduceMotion);
        lastOy = oy;
        float a = alpha * Anim.progress(t, c.stripT(), reduceMotion ? Anim.SHORT : 0.2f);
        TextQueue q = k.text;
        frameY = l.frameY + oy;
        float top = frameY + frameH, px = Ui.worldPerPx();
        float acx = frameX + AV_R * bk, acy = top - AV_R * bk, rr = AV_R * bk, sy = acy - 3f * bk;
        // the name's band, under the disc: solid to 0.6 of the name, then fading out over BAND_RUN past it
        float nx = frameX + NAME_X * bk;
        float bb = top - (COL_Y + BAND_BOTTOM) * bk, bh = (BAND_BOTTOM - BAND_TOP) * bk;
        float solid = nx + nameW * 0.6f, end = Math.max(solid, nx + nameW + BAND_RUN * bk);
        k.draw.rect(acx, bb, solid - acx, bh, TAG_FILL, BAND_A * a);
        k.draw.rectGradientH(solid, bb, end - solid, bh, TAG_FILL, BAND_CLEAR, BAND_A * a);
        // a soft shadow 3 under the disc (wider, fainter discs for its blur), the disc and its white ring
        k.draw.disc(acx, sy, rr + 8f * bk, SHADE, 0.08f * a);
        k.draw.disc(acx, sy, rr + 5f * bk, SHADE, 0.12f * a);
        k.draw.disc(acx, sy, rr + 2.5f * bk, SHADE, 0.18f * a);
        k.draw.disc(acx, sy, rr, SHADE, 0.3f * a);
        k.draw.disc(acx, acy, rr, AV_DISC, a);
        k.draw.ringArc(acx, acy, RING_R * bk, 0f, MathUtils.PI2, RING_W * bk, px, Color.WHITE, 0.92f * a);
        // the avatar 1 under the disc's centre: its frame now, its image with the text
        float avs = AV_ART * bk, avy = acy - bk;
        avatars.drawFrame(m.avatar, acx, avy, avs, a, time);
        if (a > 0.002f) {
            avM = m;
            avX = acx;
            avY = avy;
            avSize = avs;
            avAlpha = a;
            k.text.deferred(avatars.region(k.batch, m.avatar).getTexture(), this, 0);
        }
        // the name over a soft shadow about 2 under it
        float base = top - (COL_Y + NAME_BASE) * bk, mw = NAME_MAX * s;
        for (int i = 0; i < SHADE_DX.length; i++) {
            q.textFit(Fonts.Role.UI_BOLD, nameCap, m.name, nx + SHADE_DX[i] * bk, base + SHADE_DY[i] * bk, Align.left,
                mw, 0.8f, NAME_SHADE, 0.18f * a);
        }
        q.textFit(Fonts.Role.UI_BOLD, nameCap, m.name, nx, base, Align.left, mw, 0.8f, Theme.INK1, a);
        tagX0 = frameX + TAG_X * bk;
        tagMid = top - (COL_Y + COL_H - TAG_H * 0.5f) * bk;
        depthK = depthScale(c, t, reduceMotion);
        drawDepth(m, c, t, accent, reduceMotion, a);
        drawCapsule(m, l, c, t, oy, reduceMotion, a);
    }

    /** Scale of the depth tag at {@code t}: up to {@link #DEPTH_K} in phase D, with a bump as the number lands. */
    private float depthScale(Choreo c, float t, boolean reduceMotion) {
        float u = c.depthT() >= 0f ? t - c.depthT() : -1f;
        if (reduceMotion || u < 0f) return 1f;
        float land = ROLL_T + ROLL_D;
        float bump = u < land ? 0f : 0.08f * MathUtils.sin(MathUtils.PI * Anim.progress(u, land, 0.25f));
        return 1f + (DEPTH_K - 1f + bump) * emphasis(u);
    }

    /**
     * The depth tag from its left tip: the gain's frame tucked under its lower right, the tag with its edge (brighter
     * while it is up) and the number with its unit centred in it (rolling in phase D), all scaled by depthK.
     */
    private void drawDepth(ResultModel m, Choreo c, float t, Color accent, boolean reduceMotion, float a) {
        float u = c.depthT() >= 0f ? t - c.depthT() : -1f;
        float e = reduceMotion ? 0f : emphasis(u);
        float land = ROLL_T + ROLL_D;
        float flash = u >= land ? 1f - Anim.progress(u, land, 0.35f) : 0f;
        float sc = depthK, th = TAG_H * bk, tip = TAG_TIP * bk, px = Ui.worldPerPx() / sc;
        float ncx = depthCenterX(), ncy = depthCenterY();
        if (e > 0.01f) { // a glow under the tag while it is up
            k.additive();
            k.draw.glowDot(ncx, ncy, tagW * 1.1f * sc, accent, 0.42f * e * a);
            k.normal();
        }
        if (!reduceMotion && u >= 0f) { // a ring as it pops, another as it lands
            ring(ncx, ncy, u, 0f, tagW * 0.2f, tagW * 0.6f, accent, a);
            ring(ncx, ncy, u, land, tagW * 0.3f * sc, tagW * 0.7f, Theme.WHITE, a);
        }
        boolean pushed = k.pushIf(tagX0, tagMid, 0f, sc, sc);
        float lx = k.ox, my = k.oy;
        if (gainShown(m) && u >= GAIN_T) drawGain(m, u, lx + tagW - GAIN_TUCK * bk, my - GAIN_DROP * bk, px, a);
        // the tag over its shadow (two copies 3 and 4.5 under it), with a cyan edge
        hex(lx, my - 3f * bk, tagW, th, tip);
        k.draw.convexFill(hex, 6, SHADE, 0.3f * a, px);
        hex(lx, my - 4.5f * bk, tagW, th, tip);
        k.draw.convexFill(hex, 6, SHADE, 0.2f * a, px);
        hex(lx, my, tagW, th, tip);
        k.draw.convexFill(hex, 6, TAG_FILL, 0.9f * a, px);
        Theme.mix(TAG_EDGE, Theme.WHITE, Math.max(0.35f * e, 0.7f * flash), edgeCol);
        k.draw.convexStroke(hex, 6, (1.6f + 1.2f * e) * bk, edgeCol, 0.9f * a, px);
        Theme.mix(Theme.INK1, accent, 0.55f * e, numCol);
        if (flash > 0f) Theme.mix(numCol, Theme.WHITE, flash, numCol);
        float base = my - (TAG_H * 0.5f - (COL_H - DEPTH_BASE)) * bk, x = lx + (tagW - numW - unitW) * 0.5f;
        float w = rollNumber(depthShown(m, c, t), m.depthAfter, depthCap, x, base, numCol, a);
        k.text.text(Fonts.Role.NUMBER, depthCap, UNIT, x + w, base, Align.left, numCol, a);
        k.popIf(pushed);
    }

    /** Puts a tag pointed at both ends, {@code w} by {@code h} from its left tip ({@code x0}, {@code ym}), in hex. */
    private void hex(float x0, float ym, float w, float h, float tip) {
        float[] p = hex;
        float yb = ym - h * 0.5f, yt = ym + h * 0.5f;
        p[0] = x0;
        p[1] = ym;
        p[2] = x0 + tip;
        p[3] = yb;
        p[4] = x0 + w - tip;
        p[5] = yb;
        p[6] = x0 + w;
        p[7] = ym;
        p[8] = x0 + w - tip;
        p[9] = yt;
        p[10] = x0 + tip;
        p[11] = yt;
    }

    /** An expanding ring from {@code r0} to {@code r1} over 0.6 s from {@code t0} (phase time). */
    private void ring(float cx, float cy, float u, float t0, float r0, float r1, Color c, float a) {
        float p = (u - t0) / 0.6f;
        if (p < 0f || p >= 1f) return;
        float r = r0 + (r1 - r0) * Anim.POW3_OUT.apply(p);
        k.draw.circle(cx, cy, r, 2.5f * s, c, a * 0.8f * (1f - p) * (1f - p));
    }

    /**
     * Draws {@code -v} in the fixed cells of {@code -last}, right-aligned so every digit keeps its place, as an
     * odometer: the units roll with the fraction of {@code v}, and each column rolls on to its next digit while the
     * column to its right turns from 9 to 0, each cell cut to the line like a window. Leading zeros stay blank (a new
     * leading digit rolls in); the minus sign sits before the leading digit. Not drawing at once (reduced motion: no
     * scale), it counts instead. Returns the width of {@code -last}.
     */
    private float rollNumber(float v, long last, float cap, float x, float base, Color col, float a) {
        TextQueue q = k.text;
        k.sb.setLength(0);
        Fonts.appendGrouped(k.sb, -last);
        float w = q.fixedWidth(cap, k.sb);
        long iv = (long) Math.floor(v);
        float r = v - iv;
        if (iv >= last || q.isDeferring()) {
            if (iv < last) {
                k.sb.setLength(0);
                Fonts.appendGrouped(k.sb, -iv);
            }
            q.fixed(cap, k.sb, x + w, base, Align.right, col, a);
            return w;
        }
        float cell = q.fixedWidth(cap, ZERO), comma = q.fixedWidth(cap, COMMA), rollH = cap * 1.25f;
        int digits = 1;
        for (long n = last; n >= 10; n /= 10) digits++;
        float xr = x + w, lead = xr;
        long pow = 1;
        for (int p = 0; p < digits; p++) {
            boolean shown = p == 0 || iv >= pow, next = p == 0 || iv + 1 >= pow;
            if (p > 0 && p % 3 == 0) {
                xr -= comma;
                float ca = shown ? a : (next ? a * r : 0f);
                if (ca > 0.002f) q.fixed(cap, COMMA, xr, base, Align.left, col, ca);
            }
            xr -= cell;
            int d = (int) ((iv / pow) % 10);
            rollCell(shown ? (char) ('0' + d) : 0, next ? (char) ('0' + (d + 1) % 10) : 0, r, xr, base, cap, rollH,
                col, a);
            if (shown || (next && r > 0f)) lead = xr;
            if (d != 9) r = 0f; // the next column only turns while this one goes from 9 to 0
            pow *= 10;
        }
        q.fixed(cap, MINUS, lead - q.fixedWidth(cap, MINUS), base, Align.left, col, a);
        return w;
    }

    /** One odometer cell, cut to the line: {@code out} leaving upwards, {@code in} arriving from below. */
    private void rollCell(char out, char in, float r, float x, float base, float cap, float rollH, Color col,
                          float a) {
        TextQueue q = k.text;
        float y0 = base - cap * 0.2f, y1 = base + cap * 1.2f;
        if (out != 0 && r < 0.999f) q.cellClipped(cap, out, x, base + r * rollH, y0, y1, col, a * (1f - r));
        if (in != 0 && r > 0.001f) q.cellClipped(cap, in, x, base - (1f - r) * rollH, y0, y1, col, a * r);
    }

    /**
     * The gain's frame: {@code ▼ 37} in a smaller pointed tag from its left tip ({@code x0}, centre {@code my}), under
     * the depth tag's lower right, floating up into place from {@link #GAIN_T}; its content centred in the part that
     * shows.
     */
    private void drawGain(ResultModel m, float u, float x0, float my, float px, float a) {
        float p = Anim.progress(u, GAIN_T, GAIN_D);
        float ga = a * Anim.POW2_OUT.apply(Math.min(1f, p * 1.6f));
        float gh = GAIN_H * bk, gy = my - 10f * bk * (1f - Anim.POW3_OUT.apply(p)), tip = GAIN_TIP * bk;
        hex(x0, gy - 3f * bk, gainW, gh, tip);
        k.draw.convexFill(hex, 6, SHADE, 0.3f * ga, px);
        hex(x0, gy, gainW, gh, tip);
        k.draw.convexFill(hex, 6, TAG_FILL, 0.9f * ga, px);
        k.draw.convexStroke(hex, 6, 1.6f * bk, Theme.RESONANCE, 0.9f * ga, px);
        float tri = GAIN_TRI * bk, gap = GAIN_GAP * bk, tuck = GAIN_TUCK * bk, gc = gainCap;
        float tx = x0 + tuck + (gainW - tuck - tri - gap - gainNumW) * 0.5f, gb = gy - gc * 0.5f;
        k.triangle(tx, gb + gc * 0.82f, tx + tri, gb + gc * 0.82f, tx + tri * 0.5f, gb + gc * 0.05f, Theme.RESONANCE,
            ga);
        k.text.fixed(gc, m.depthGainText, tx + tri + gap, gb, Align.left, Theme.RESONANCE, ga);
    }

    /** The shell capsule; in phase E the {@code +N} bubble rises at its left and merges into it. */
    private void drawCapsule(ResultModel m, ResultLayout l, Choreo c, float t, float oy, boolean reduceMotion,
                             float a) {
        TextQueue q = k.text;
        capY = l.capsuleY + oy;
        capX = l.capsuleRight - capsuleW;
        float u = c.shellsT() >= 0f ? t - c.shellsT() : -1f;
        float in = PULL_T + PULL_D;
        // the capsule squashes as the bubble lands in it, then settles
        float wob = reduceMotion || u < in ? 0f
            : 0.10f * (float) Math.exp(-6f * (u - in)) * MathUtils.sin(20f * Math.min(u - in, 0.6f));
        float cw = capsuleW * (1f + wob), ch = capH * (1f - 0.6f * wob), cx0 = capX + (capsuleW - cw) * 0.5f;
        k.draw.pill(cx0, capY - ch * 0.5f, cw, ch, CAPSULE_FILL, a);
        float bx = 0f, by = capY, ba = 0f, inside = 0f;
        boolean bubble = m.hasShells && u >= 0f && u < in + 0.05f;
        if (bubble) {
            float pin = Anim.progress(u, 0f, BUBBLE_IN);
            ba = a * Anim.POW2_OUT.apply(Math.min(1f, pin * 1.5f));
            float pop = reduceMotion ? 1f : Math.max(0.05f, Anim.POP_EASE.apply(pin));
            by = capY - 10f * s * (1f - Anim.POW3_OUT.apply(pin));
            float rest = capX - 22f * s - bubbleW * 0.5f, goal = capX + capH * 0.5f;
            bx = rest + (goal - rest) * Anim.POW2_IN.apply(Anim.progress(u, PULL_T, PULL_D));
            inside = Anim.clamp01((bx + bubbleW * 0.5f - cx0) / bubbleW);
            float bh = capH * 0.8f * pop * (1f - 0.5f * inside), bw = Math.max(bh, bubbleW * pop * (1f - inside));
            // the neck between the bubble's right end and the capsule's left end thickens as they close in
            float gap = cx0 - (bx + bw * 0.5f);
            neck(bx + bw * 0.5f - bh * 0.5f, bh * 0.5f, cx0 + ch * 0.5f, ch * 0.5f, by,
                Anim.clamp01(1f - gap / (12f * s)), ba);
            k.draw.pill(bx - bw * 0.5f, by - bh * 0.5f, bw, bh, CAPSULE_FILL, ba);
            // a drop's gloss
            k.draw.glowDot(bx - bw * 0.22f, by + bh * 0.2f, bh * 0.55f, Theme.WHITE, 0.22f * ba * (1f - inside));
        }
        k.draw.shell(capX + 18f * s + 14f * s, capY, 28f * s, Theme.GOLD, SHELL_RIB, a);
        float flash = m.hasShells && u >= in ? 1f - Anim.progress(u, in, 0.5f) : 0f;
        if (flash > 0f && !reduceMotion) { // the splash as they become one
            k.additive();
            k.draw.glowDot(cx0 + ch * 0.5f, capY, ch * 2.4f, Theme.GOLD, 0.5f * flash * a);
            k.normal();
        }
        Theme.mix(Theme.GOLD, Theme.WHITE, 0.6f * flash, cntCol);
        float nx = capX + 18f * s + 28f * s + 8f * s;
        q.digits(22f * s, shellsShown(m, c, t), true, nx, capY - 11f * s, Align.left, cntCol, a);
        if (bubble) {
            q.fixed(17f * s, m.shellsText, bx, by, Align.center | Fonts.VCENTER, Theme.GOLD,
                ba * Anim.clamp01(1f - inside * 2.5f));
        }
    }

    /**
     * The neck between two drops on one line at {@code cy}: from the centre of the left one's right end ({@code x1},
     * radius {@code r1}) to the centre of the right one's left end ({@code x2}, {@code r2}). Its waist grows with
     * {@code m} (0 apart, 1 touching) and its sides curve in less, so the two read as one as they meet.
     */
    private void neck(float x1, float r1, float x2, float r2, float cy, float m, float a) {
        if (m <= 0.02f || x2 <= x1 || a <= 0.002f) return;
        float waist = Math.min(r1, r2) * 0.92f * m * (float) Math.sqrt(m), pw = 6f - 4f * m;
        ShapeDrawer sd = k.shapes;
        float old = sd.getPackedColor();
        sd.setColor(CAPSULE_FILL.r, CAPSULE_FILL.g, CAPSULE_FILL.b, a);
        float px = x1, ph = r1;
        for (int i = 1; i <= NECK_N; i++) {
            float f = i / (float) NECK_N, nx = x1 + (x2 - x1) * f, end = r1 + (r2 - r1) * f;
            float h = waist + (end - waist) * (float) Math.pow(Math.abs(2f * f - 1f), pw);
            sd.filledTriangle(px, cy - ph, nx, cy - h, nx, cy + h);
            sd.filledTriangle(px, cy - ph, nx, cy + h, px, cy + ph);
            px = nx;
            ph = h;
        }
        sd.setColor(old);
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

    // ------------------------------------------------------------------ geometry for the screen
    /** True when (x, y) hits the player block (grown by {@code grow}). */
    public boolean hitFrame(float x, float y, float grow) {
        return frameW > 0f && x >= frameX - grow && x <= frameX + frameW + grow && y >= frameY - grow
            && y <= frameY + frameH + grow;
    }

    /** True when (x, y) hits the shell capsule (grown by {@code grow}). */
    public boolean hitCapsule(float x, float y, float grow) {
        return capsuleW > 0f && x >= capX - grow && x <= capX + capsuleW + grow && y >= capY - capH * 0.5f - grow
            && y <= capY + capH * 0.5f + grow;
    }

    /** Bottom of the player block (the depth popover hangs from it). */
    public float frameBottom() {
        return frameY;
    }

    /** Centre x of the depth tag as drawn (the depth popover's pointer, the bubbles while it rolls). */
    public float depthCenterX() {
        return tagX0 + tagW * 0.5f * depthK;
    }

    /** Centre y of the depth tag as drawn. */
    public float depthCenterY() {
        return tagMid;
    }

    /** Capsule centre x (the shell popover's pointer). */
    public float capsuleCenterX() {
        return capX + capsuleW * 0.5f;
    }

    /** Capsule centre y. */
    public float capsuleCenterY() {
        return capY;
    }

    /** Capsule bottom (the shell popover hangs from it). */
    public float capsuleBottom() {
        return capY - capH * 0.5f;
    }

    /** Vertical offset of the HUD at the last draw. */
    public float lastOffset() {
        return lastOy;
    }
}
