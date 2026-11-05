package org.kelvinizer.screens.result;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.DepthCrest;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;
import space.earlygrey.shapedrawer.ShapeDrawer;

/**
 * 结算画面左上的深度徽章和右上的贝壳、珍珠胶囊：徽章和地图左上角的一样（缩到 0.72），名字默认展开，点头像收起（只在这一页）；
 * 珍珠在最右，贝壳在它左边。这次加深了的话，整个徽章以六边形中心放大发亮，六边形右边浮出一个小六边形框写着这次下降了多少米，
 * 然后数字像里程表一样滚到新值，落定时换成新深度那一档的样子，前后约 2.5 秒；贝壳在深度动画之后（没加深就照原来的时间）：左边
 * 浮出一个 +N 的贝壳泡，像两滴水一样和贝壳胶囊融成一个，再显示新的数。超过 9,999 显示 9,999+ 加一圈高光。点六边形看深度明细、
 * 点贝壳看贝壳明细（超过 9,999 时第一行是具体总数），由画面处理。
 *
 * <p>The depth crest (top left) and the shell and pearl capsules (top right) of the result screen, split like the map's
 * corners (Kelvin 2026-10-19 06:04, 06:28). Since Kelvin 2026-10-30 (16:37–20:58, picture topleft8) the crest is the
 * map's ({@link DepthCrest}, here at {@link #CREST_K}): the avatar on the hexagon of its depth tier, the depth in the
 * hexagon. The name shows on its band by default here, and a tap on the avatar hides it for this screen only
 * (「结算页左上角（默认展开）」). The pearls take the right-most slot and the shells sit left of them (20:34
 * 「结算界面里面贝壳在珍珠左侧」); a count over 9,999 reads {@code 9,999+} inside a highlight ring (20:58). Both
 * corners drop in from the top at the strip time; the capsules are sized for their final counts. Taps (depth and shell
 * breakdowns, the name) are the screen's.
 *
 * <p>Kelvin 2026-10-20 18:45 「如果说深度值下降了，就显示旧的深度值，右下角浮现这次下降了多少，然后滚动显示到新的深度值。
 * 整个过程大概持续2.5秒，要引起玩家的注意。贝壳就普通一点，如果深度值更新了就等深度值完成再开始。就只是在左侧浮现一个加多少的
 * 贝壳泡，然后和贝壳那个东西合并，像两个水滴合并一样，再显示新的值。」:
 * <ul>
 *   <li><b>Depth</b> (phase D, {@link Choreo#DEPTH_D}, only for a run that went deeper): the crest with the old depth
 *       grows to {@link #DEPTH_K}× about its hexagon's centre with a glow and a ring, {@code ▼ 37} floats up in a
 *       smaller pointed frame right of the crest (as Arcaea's change frame beside its potential, Kelvin 2026-10-20
 *       19:45), the digits roll like an odometer to the new depth, which lands with a flash and a second ring as the
 *       crest takes the new depth's tier, and the crest shrinks back. The frame stays.</li>
 *   <li><b>Shells</b> (phase E, {@link Choreo#SHELLS_D}): a {@code +151} bubble rises at the capsule's left, is
 *       drawn in and merges with it (a neck grows between their ends like two drops), the capsule wobbles and the
 *       count rolls on to the new total.</li>
 * </ul>
 *
 * <p>All motion is a function of the timeline (the name band's slide, of real time); no allocation per frame. Shapes
 * and the crest's back layer go down at once; text, the avatar image and the crest's top layer join their page groups
 * in the {@link TextQueue} (drawn at once while the crest is scaled).
 */
public final class CornerHud implements TextQueue.Deferred {
    /** Depth phase beats after its start (s): pop in, the gain floating in, the roll, shrinking back. */
    public static final float POP_IN = 0.35f, GAIN_T = 0.25f, GAIN_D = 0.45f, ROLL_T = 0.70f, ROLL_D = 1.30f,
        BACK_T = 2.05f;
    /**
     * Scale of the crest (about its hexagon's centre) at the height of phase D; the depth tag alone grew 1.3× about
     * its left tip before the crest, whose avatar would leave the screen's top at that.
     */
    public static final float DEPTH_K = 1.2f;
    /** Shells phase beats after its start (s): the bubble rising, the pull into the capsule, the count. */
    public static final float BUBBLE_IN = 0.30f, PULL_T = 0.45f, PULL_D = 0.40f, COUNT_D = 0.40f;
    /** Seconds the new depth's tier takes to replace the old one's look as the number lands. */
    private static final float TIER_FADE = 0.3f;
    /** One opaque fill for the capsule, the bubble and the neck between them, so the merge shows no seams. */
    private static final Color CAPSULE_FILL = opaque(Theme.mix(Theme.SHELL_CAPSULE, Theme.GOLD, 0.16f, new Color()));
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private static final String UNIT = " m", ZERO = "0", COMMA = ",", MINUS = "-";
    /** Slices of the neck between the bubble and the capsule. */
    private static final int NECK_N = 14;
    /**
     * The crest at {@link #CREST_K} of the map's (blueprint units before that): its art {@link #CREST_IN} in from the
     * frame's left (the avatar's top is {@link ResultLayout#crestTop}); the avatar's white ring (radius and width) and
     * the avatar drawn in it, as on the map.
     */
    private static final float CREST_K = 0.72f, CREST_IN = 12f, RING_R = 52.9f, RING_W = 2.2f, AV_ART = 64.8f;
    /**
     * The name band, as the map's (× the crest's scale): its height, its tail past the name's room ({@link #NAME_ROOM}
     * CJK glyphs), its alpha, the share of it that is solid, the name's cap and gap from the avatar; seconds to slide
     * out and back.
     */
    private static final float BAND_H = 48f, BAND_TAIL = 44f, BAND_A = 215f / 255f, BAND_SOLID = 0.74f,
        NAME_CAP = 24f, NAME_GAP = 14f, BAND_OPEN = 0.24f, BAND_CLOSE = 0.18f;
    private static final int NAME_ROOM = 10;
    /**
     * The gain's frame (× {@link #GAIN_K} of the blueprint; Kelvin 2026-10-20 19:45 「出现的下降也是有一个六边形外框」,
     * smaller than Arcaea's): its height, tip depth and padding, the triangle's width, the gap after it and the
     * number's cap; it sits {@link #GAIN_SPACE} right of the crest's art, centred on the hexagon (picture topleft8).
     */
    private static final float GAIN_H = 46f, GAIN_TIP = 16f, GAIN_PAD = 10f, GAIN_TRI = 17f, GAIN_GAP = 6f,
        GAIN_CAP = 24f, GAIN_K = 2f / 3f, GAIN_SPACE = 8f;
    /**
     * The pearl capsule (blueprint units; picture topleft8): height, left padding, the pearl's radius, the gap and
     * cap of the count, right padding; {@link #CAP_SPACE} between it and the shell capsule left of it.
     */
    private static final float PEARL_H = 42f, PEARL_PAD_L = 15f, PEARL_R = 11.3f, PEARL_GAP = 9f, PEARL_CAP = 18f,
        PEARL_PAD_R = 17.5f, CAP_SPACE = 12f;
    /** The crest's colours: the disc, the shadows (under the disc and the gain, under the name), the name band. */
    private static final Color AV_DISC = Theme.rgb(0x05091C, 1f), SHADE = Theme.rgb(0x000012, 1f),
        NAME_SHADE = Theme.rgb(0x000818, 1f), TAG_FILL = Theme.rgb(0x080D26, 1f), BAND = Theme.rgb(0x080E26, 1f),
        BAND_CLEAR = Theme.rgb(0x080E26, 0f), HAIR = Theme.rgb(0x78DCFF, 1f), HAIR_CLEAR = Theme.rgb(0x78DCFF, 0f);
    /** The pearl capsule's fill and count, and its pearl's rim, body and light (as the map's). */
    private static final Color PEARL_FILL = Theme.rgb(0x3A3260, 225f / 255f), PEARL_TEXT = Theme.rgb(0xEEE8FF, 1f),
        PEARL_RIM = Theme.rgb(0xFFE2F6, 1f), PEARL_BODY = Theme.rgb(0xC8BEEC, 1f),
        PEARL_LIGHT = Theme.rgb(0xF4F0FF, 1f);
    /** A capped count's highlight ring and glow: gold for shells, lilac for pearls (as the map's). */
    private static final Color SHELL_LINE = Theme.rgb(0xFFF0C8, 1f), SHELL_GLOW = Theme.rgb(0xFFD678, 1f),
        PEARL_LINE = Theme.rgb(0xF4ECFF, 1f), PEARL_GLOW = Theme.rgb(0xC8B4FF, 1f);
    /** The pearl breakdown's accent. */
    public static final Color PEARL_ACCENT = Theme.rgb(0xD9CCFF, 1f);
    /** The name's soft shadow: four faint copies about 2 under it and 1 right (× the crest's scale). */
    private static final float[] SHADE_DX = {0f, 2f, 0f, 2f}, SHADE_DY = {-1f, -1f, -3f, -3f};

    private final ResultKit k;
    private final Avatar avatars;
    private final DepthCrest crests;
    private final Color numCol = new Color(), cntCol = new Color();
    /** Corners of the pointed frame being drawn, counter-clockwise from its left tip. */
    private final float[] hex = new float[12];
    private final StringBuilder pearlText = new StringBuilder(8);
    // sizes for the current layout (measure)
    private float s = 1f, ks = CREST_K, gk = GAIN_K, nameCap = NAME_CAP * CREST_K, depthCap, gainCap;
    private float nameRoom, numW, unitW, gainNumW, gainW, capsuleW, pearlsW, bubbleW, capH;
    /** The crest's tiers before and after the run, and the crest's left, its hexagon's left and its art's right. */
    private int tierBefore = 1, tierAfter = 1;
    private float crestLeft, hexL, crestRight, frameW;
    private boolean pearlsCapped;
    /** The name band: hidden for this screen by a tap on the avatar; its slide (0 in … 1 out). */
    private boolean nameHidden;
    private float nameT = 1f, lastTime = -1f;
    // deferred avatar image and crest top layer
    private ResultModel avM;
    private float avDrawX, avDrawY, avSize, avAlpha, topX, topY, topBefore, topAfter;
    // last drawn geometry (input, particles, popovers)
    private float avX, avY, hexB, capX, capY, pearlsX, depthK = 1f;

    private static Color opaque(Color c) {
        c.a = 1f;
        return c;
    }

    /** Creates the corner HUD renderer. */
    public CornerHud(ResultKit kit, Avatar avatars, DepthCrest crests) {
        this.k = kit;
        this.avatars = avatars;
        this.crests = crests;
    }

    /**
     * Measures every string the HUD can draw (CJK glyphs are generated now, while covered); a new result shows the
     * name again.
     */
    public void warm(ResultModel m) {
        TextQueue q = k.text;
        q.warm(Fonts.Role.UI_BOLD, nameCap, m.name);
        nameHidden = false;
        nameT = 1f;
        lastTime = -1f;
    }

    /** Sizes the crest and the capsules for the final numbers of {@code m} in layout {@code l}. */
    public void measure(ResultModel m, ResultLayout l) {
        TextQueue q = k.text;
        s = l.s;
        ks = CREST_K * s;
        gk = GAIN_K * s;
        tierBefore = Depth.crestTier(m.depthBefore);
        tierAfter = Depth.crestTier(m.depthAfter);
        // room for both tiers' art, so the crest stays put as its look changes
        float reachL = Math.max(DepthCrest.reachLeft(tierBefore), DepthCrest.reachLeft(tierAfter));
        float reachR = Math.max(DepthCrest.reachRight(tierBefore), DepthCrest.reachRight(tierAfter));
        crestLeft = l.frameX + CREST_IN * s;
        hexL = crestLeft + reachL * ks;
        avX = hexL + DepthCrest.HEX_W * ks * 0.5f;
        crestRight = hexL + (DepthCrest.HEX_W + reachR) * ks;
        nameCap = NAME_CAP * ks;
        k.sb.setLength(0);
        for (int i = 0; i < NAME_ROOM; i++) k.sb.append('深');
        nameRoom = q.width(Fonts.Role.UI_BOLD, nameCap, k.sb);
        depthCap = DepthCrest.NUM_CAP * ks;
        numW = depthWidth(m.depthAfter, depthCap);
        unitW = q.width(Fonts.Role.NUMBER, depthCap, UNIT);
        gainCap = GAIN_CAP * gk;
        gainNumW = gainShown(m) ? q.fixedWidth(gainCap, m.depthGainText) : 0f;
        gainW = gainShown(m) ? (2f * GAIN_TIP + 2f * GAIN_PAD + GAIN_TRI + GAIN_GAP) * gk + gainNumW : 0f;
        frameW = crestRight + (gainW > 0f ? GAIN_SPACE * s + gainW : 0f) - crestLeft;
        k.sb.setLength(0);
        Fonts.appendCapped(k.sb, Math.max(m.shellsBefore, m.shellsAfter));
        capsuleW = 18f * s + 28f * s + 8f * s + q.fixedWidth(22f * s, k.sb) + 20f * s;
        pearlText.setLength(0);
        Fonts.appendCapped(pearlText, m.pearls);
        pearlsCapped = m.pearls > Fonts.SHOWN_MAX;
        pearlsW = (PEARL_PAD_L + 2f * PEARL_R + PEARL_GAP + PEARL_PAD_R) * s + q.fixedWidth(PEARL_CAP * s, pearlText);
        bubbleW = m.hasShells ? q.fixedWidth(17f * s, m.shellsText) + 30f * s : 0f;
        capH = l.capsuleH;
    }

    /** Width of {@code -metres} in fixed NUMBER cells at {@code cap}. */
    private float depthWidth(long metres, float cap) {
        k.sb.setLength(0);
        Fonts.appendGrouped(k.sb, -metres);
        return k.text.fixedWidth(cap, k.sb);
    }

    /** True when the crest shows {@code ▼ n} (a recorded run that went deeper: phase D runs). */
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
     * Draws the crest and the capsules.
     *
     * @param accent layer accent (the depth glow and rings)
     * @param time   real time for the avatar frame animation and the name band's slide
     */
    public void draw(ResultModel m, ResultLayout l, Choreo c, float t, float time, Color accent, boolean reduceMotion,
                     float alpha) {
        if (t < c.stripT()) return;
        float oy = drop(c, t, reduceMotion);
        float a = alpha * Anim.progress(t, c.stripT(), reduceMotion ? Anim.SHORT : 0.2f);
        float dt = lastTime < 0f ? 0f : MathUtils.clamp(time - lastTime, 0f, 0.1f);
        lastTime = time;
        nameT = nameHidden ? Math.max(0f, nameT - dt / BAND_CLOSE) : Math.min(1f, nameT + dt / BAND_OPEN);
        float top = l.crestTop + oy;
        avY = top - DepthCrest.AV_R * ks;
        hexB = top - (2f * DepthCrest.AV_R - DepthCrest.LIFT + DepthCrest.HEX_H) * ks;
        depthK = depthScale(c, t, reduceMotion);
        if (nameT > 0.001f) drawBand(a);
        drawCrest(m, c, t, time, accent, reduceMotion, a);
        if (nameT > 0.3f) drawName(m, a);
        drawCapsule(m, l, c, t, oy, reduceMotion, a);
        drawPearls(l, a);
    }

    /** The name band behind the avatar from its centre, as the map's: solid, then fading out, with hairlines. */
    private void drawBand(float a) {
        float len = ((DepthCrest.AV_R * depthK + NAME_GAP + BAND_TAIL) * ks + nameRoom)
            * Interpolation.pow2Out.apply(nameT);
        if (len <= 0.5f) return;
        float hh = BAND_H * ks, by = avY - hh * 0.5f, solid = len * BAND_SOLID, fade = len - solid;
        float line = Ui.worldPerPx(), ba = BAND_A * a;
        k.draw.rect(avX, by, solid, hh, BAND, ba);
        k.draw.rectGradientH(avX + solid, by, fade, hh, BAND, BAND_CLEAR, ba);
        for (int i = 0; i < 2; i++) {
            float ly = i == 0 ? by : by + hh - line;
            k.draw.rect(avX, ly, solid, line, HAIR, ba / 3f);
            k.draw.rectGradientH(avX + solid, ly, fade, line, HAIR, HAIR_CLEAR, ba / 3f);
        }
    }

    /** The name on its band, sliding in after it, over a soft shadow; NAME_GAP right of the ring as the crest grows. */
    private void drawName(ResultModel m, float a) {
        TextQueue q = k.text;
        float e = MathUtils.clamp((nameT - 0.3f) / 0.7f, 0f, 1f), na = a * e;
        float x = avX + (DepthCrest.AV_R * depthK + NAME_GAP) * ks - 16f * ks * (1f - e), base = avY - nameCap * 0.5f;
        for (int i = 0; i < SHADE_DX.length; i++) {
            q.textFit(Fonts.Role.UI_BOLD, nameCap, m.name, x + SHADE_DX[i] * ks, base + SHADE_DY[i] * ks, Align.left,
                nameRoom, 0.8f, NAME_SHADE, 0.2f * na);
        }
        q.textFit(Fonts.Role.UI_BOLD, nameCap, m.name, x, base, Align.left, nameRoom, 0.8f, Theme.INK1, na);
    }

    /** Scale of the crest at {@code t}: up to {@link #DEPTH_K} in phase D, with a bump as the number lands. */
    private float depthScale(Choreo c, float t, boolean reduceMotion) {
        float u = c.depthT() >= 0f ? t - c.depthT() : -1f;
        if (reduceMotion || u < 0f) return 1f;
        float land = ROLL_T + ROLL_D;
        float bump = u < land ? 0f : 0.08f * MathUtils.sin(MathUtils.PI * Anim.progress(u, land, 0.25f));
        return 1f + (DEPTH_K - 1f + bump) * emphasis(u);
    }

    /**
     * The crest, all scaled by depthK about its hexagon's centre: the old and new tiers' back layers (the new one
     * fading in as the number lands), the avatar disc and ring, the avatar and the top layer (deferred), the gain's
     * frame right of the crest and the number with its unit centred in the hexagon over a dark shadow (rolling in
     * phase D).
     */
    private void drawCrest(ResultModel m, Choreo c, float t, float time, Color accent, boolean reduceMotion, float a) {
        float u = c.depthT() >= 0f ? t - c.depthT() : -1f;
        float e = reduceMotion ? 0f : emphasis(u);
        float land = ROLL_T + ROLL_D;
        float flash = u >= land ? 1f - Anim.progress(u, land, 0.35f) : 0f;
        float fresh = tierAfter == tierBefore ? 1f : (u < 0f ? 0f : Anim.progress(u, land, TIER_FADE));
        float hexW = DepthCrest.HEX_W * ks, pivotY = hexB + DepthCrest.HEX_H * ks * 0.5f;
        float sc = depthK, px = Ui.worldPerPx() / sc;
        if (e > 0.01f) { // a glow under the crest while it is up
            k.additive();
            k.draw.glowDot(avX, pivotY, hexW * 1.1f * sc, accent, 0.42f * e * a);
            k.normal();
        }
        if (!reduceMotion && u >= 0f) { // a ring as it pops, another as it lands
            ring(avX, pivotY, u, 0f, hexW * 0.2f, hexW * 0.6f, accent, a);
            ring(avX, pivotY, u, land, hexW * 0.3f * sc, hexW * 0.7f, Theme.WHITE, a);
        }
        boolean pushed = k.pushIf(avX, pivotY, 0f, sc, sc);
        float dx = k.ox - avX, dy = k.oy - pivotY;
        float bx = hexL + dx, by = hexB + dy;
        if (fresh < 1f) crests.drawBack(k.batch, tierBefore, bx, by, ks, a * (1f - fresh));
        if (fresh > 0f) crests.drawBack(k.batch, tierAfter, bx, by, ks, a * fresh);
        // the avatar disc over a soft shadow 3 under it (wider, fainter discs for its blur) and its white ring
        float cx = avX + dx, cy = avY + dy, rr = DepthCrest.AV_R * ks, sy = cy - 3f * ks;
        k.draw.disc(cx, sy, rr + 6f * ks, SHADE, 0.08f * a);
        k.draw.disc(cx, sy, rr + 3.5f * ks, SHADE, 0.12f * a);
        k.draw.disc(cx, sy, rr + 1.5f * ks, SHADE, 0.2f * a);
        k.draw.disc(cx, cy, rr, AV_DISC, a);
        k.draw.ringArc(cx, cy, RING_R * ks, 0f, MathUtils.PI2, RING_W * ks, px, Color.WHITE, 0.92f * a);
        // the avatar 1 under the disc's centre: its frame now, its image with the text, then the pearls on the ring
        float avs = AV_ART * ks, avy = cy - ks;
        avatars.drawFrame(m.avatar, cx, avy, avs, a, time);
        if (a > 0.002f) {
            avM = m;
            avDrawX = cx;
            avDrawY = avy;
            avSize = avs;
            avAlpha = a;
            k.text.deferred(avatars.region(k.batch, m.avatar).getTexture(), this, 0);
            topX = bx;
            topY = by;
            topBefore = a * (1f - fresh);
            topAfter = a * fresh;
            if (topBefore > 0.002f) k.text.deferred(crests.page(tierBefore), this, 1);
            if (topAfter > 0.002f) k.text.deferred(crests.page(tierAfter), this, 2);
        }
        if (gainShown(m) && u >= GAIN_T) drawGain(m, u, crestRight + GAIN_SPACE * s + dx, pivotY + dy, px, a);
        Theme.mix(Theme.INK1, accent, 0.55f * e, numCol);
        if (flash > 0f) Theme.mix(numCol, Theme.WHITE, flash, numCol);
        float base = pivotY + dy - depthCap * 0.5f, x = avX + dx - (numW + unitW) * 0.5f, d = DepthCrest.SHADOW * ks;
        float shown = depthShown(m, c, t);
        float w = rollNumber(shown, m.depthAfter, depthCap, x + d, base - d, Color.BLACK, 0.67f * a);
        k.text.text(Fonts.Role.NUMBER, depthCap, UNIT, x + d + w, base - d, Align.left, Color.BLACK, 0.67f * a);
        w = rollNumber(shown, m.depthAfter, depthCap, x, base, numCol, a);
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
     * The gain's frame: {@code ▼ 37} in a smaller pointed tag from its left tip ({@code x0}, centre {@code my}), right
     * of the crest, floating up into place from {@link #GAIN_T}.
     */
    private void drawGain(ResultModel m, float u, float x0, float my, float px, float a) {
        float p = Anim.progress(u, GAIN_T, GAIN_D);
        float ga = a * Anim.POW2_OUT.apply(Math.min(1f, p * 1.6f));
        float gh = GAIN_H * gk, gy = my - 10f * gk * (1f - Anim.POW3_OUT.apply(p)), tip = GAIN_TIP * gk;
        hex(x0, gy - 3f * gk, gainW, gh, tip);
        k.draw.convexFill(hex, 6, SHADE, 0.3f * ga, px);
        hex(x0, gy, gainW, gh, tip);
        k.draw.convexFill(hex, 6, TAG_FILL, 0.9f * ga, px);
        k.draw.convexStroke(hex, 6, 1.6f * gk, Theme.RESONANCE, 0.9f * ga, px);
        float tri = GAIN_TRI * gk, gap = GAIN_GAP * gk, gc = gainCap;
        float tx = x0 + (gainW - tri - gap - gainNumW) * 0.5f, gb = gy - gc * 0.5f;
        k.triangle(tx, gb + gc * 0.82f, tx + tri, gb + gc * 0.82f, tx + tri * 0.5f, gb + gc * 0.05f, Theme.RESONANCE,
            ga);
        k.text.fixed(gc, m.depthGainText, tx + tri + gap, gb, Align.left, Theme.RESONANCE, ga);
    }

    /**
     * The shell capsule, left of the pearls; in phase E the {@code +N} bubble rises at its left and merges into it.
     * Over 9,999 it reads {@code 9,999+} inside its highlight ring.
     */
    private void drawCapsule(ResultModel m, ResultLayout l, Choreo c, float t, float oy, boolean reduceMotion,
                             float a) {
        TextQueue q = k.text;
        capY = l.capsuleY + oy;
        capX = l.capsuleRight - pearlsW - CAP_SPACE * s - capsuleW;
        long count = shellsShown(m, c, t);
        if (count > Fonts.SHOWN_MAX) capRing(capX, capY - capH * 0.5f, capsuleW, capH, SHELL_GLOW, SHELL_LINE, a);
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
        if (count > Fonts.SHOWN_MAX) {
            q.fixed(22f * s, Fonts.SHOWN_CAPPED, nx, capY - 11f * s, Align.left, cntCol, a);
        } else {
            q.digits(22f * s, count, true, nx, capY - 11f * s, Align.left, cntCol, a);
        }
        if (bubble) {
            q.fixed(17f * s, m.shellsText, bx, by, Align.center | Fonts.VCENTER, Theme.GOLD,
                ba * Anim.clamp01(1f - inside * 2.5f));
        }
    }

    /** A capped count's highlight: a soft glow, then a ring 2 wide, 2 outside the capsule at (x, y, w, h). */
    private void capRing(float x, float y, float w, float h, Color glow, Color line, float a) {
        for (int i = 3; i >= 1; i--) {
            float e = (2f + 2f * i) * s;
            k.draw.pill(x - e, y - e, w + 2f * e, h + 2f * e, glow, (0.2f - 0.05f * i) * a);
        }
        float o = 4f * s;
        k.draw.roundRectStroke(x - o, y - o, w + 2f * o, h + 2f * o, h * 0.5f + o, 2f * s, line, 0.95f * a);
    }

    /** The pearl capsule at the right (a count only for now), centred on the shell capsule. */
    private void drawPearls(ResultLayout l, float a) {
        float ph = PEARL_H * s, pr = PEARL_R * s;
        pearlsX = l.capsuleRight - pearlsW;
        if (pearlsCapped) capRing(pearlsX, capY - ph * 0.5f, pearlsW, ph, PEARL_GLOW, PEARL_LINE, a);
        k.draw.pill(pearlsX, capY - ph * 0.5f, pearlsW, ph, PEARL_FILL, a);
        float ix = pearlsX + PEARL_PAD_L * s + pr;
        k.draw.disc(ix, capY, pr, PEARL_RIM, a);
        k.draw.disc(ix, capY, pr - Math.max(1f, 0.8f * s), PEARL_BODY, a);
        k.draw.disc(ix - 0.125f * pr, capY + 0.175f * pr, 0.72f * pr, PEARL_LIGHT, a);
        k.draw.disc(ix - 0.325f * pr, capY + 0.4f * pr, 0.21f * pr, Theme.WHITE, a);
        k.text.fixed(PEARL_CAP * s, pearlText, ix + pr + PEARL_GAP * s, capY - PEARL_CAP * s * 0.5f, Align.left,
            PEARL_TEXT, a);
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

    /**
     * Deferred images (premultiplied, like {@code Avatar.draw}): the avatar ({@code arg} 0), then the crest's top
     * layer of the old tier (2) and of the new one (1).
     */
    @Override
    public void drawDeferred(int arg) {
        Batch b = k.batch;
        if (arg == 1 || arg == 2) {
            crests.drawTop(b, arg == 1 ? tierBefore : tierAfter, topX, topY, ks, arg == 1 ? topBefore : topAfter);
            return;
        }
        TextureRegion r = avatars.region(b, avM.avatar);
        int srcC = b.getBlendSrcFunc(), dstC = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha(), dstA = b.getBlendDstFuncAlpha();
        float old = b.getPackedColor();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.setColor(avAlpha, avAlpha, avAlpha, avAlpha);
        b.draw(r, avDrawX - avSize * 0.5f, avDrawY - avSize * 0.5f, avSize, avSize);
        b.setBlendFunctionSeparate(srcC, dstC, srcA, dstA);
        b.setPackedColor(old);
    }

    // ------------------------------------------------------------------ geometry for the screen
    /** True when (x, y) hits the crest's avatar, at least a touch target across (a tap shows or hides the name). */
    public boolean hitAvatar(float x, float y) {
        float r = Math.max(DepthCrest.AV_R * ks, Ui.touchTarget() * 0.5f), dx = x - avX, dy = y - avY;
        return frameW > 0f && dx * dx + dy * dy <= r * r;
    }

    /** Shows or hides the name for this screen (not remembered: the next result shows it again). */
    public void toggleName() {
        nameHidden = !nameHidden;
    }

    /** True when (x, y) hits the crest's hexagon or the gain right of it (grown by {@code grow}). */
    public boolean hitFrame(float x, float y, float grow) {
        return frameW > 0f && x >= crestLeft - grow && x <= crestLeft + frameW + grow && y >= hexB - grow
            && y <= hexB + DepthCrest.HEX_H * ks + grow;
    }

    /** True when (x, y) hits the shell capsule (grown by {@code grow}). */
    public boolean hitCapsule(float x, float y, float grow) {
        return capsuleW > 0f && x >= capX - grow && x <= capX + capsuleW + grow && y >= capY - capH * 0.5f - grow
            && y <= capY + capH * 0.5f + grow;
    }

    /** True when (x, y) hits the pearl capsule (grown by {@code grow}). */
    public boolean hitPearls(float x, float y, float grow) {
        float h = PEARL_H * s * 0.5f;
        return pearlsW > 0f && x >= pearlsX - grow && x <= pearlsX + pearlsW + grow && y >= capY - h - grow
            && y <= capY + h + grow;
    }

    /** Bottom of the crest's hexagon (the depth popover hangs from it). */
    public float frameBottom() {
        return hexB;
    }

    /** Centre x of the hexagon, where the crest scales about (the depth popover's pointer, the rolling bubbles). */
    public float depthCenterX() {
        return avX;
    }

    /** Centre y of the hexagon. */
    public float depthCenterY() {
        return hexB + DepthCrest.HEX_H * ks * 0.5f;
    }

    /** Shell capsule centre x (the shell popover's pointer). */
    public float capsuleCenterX() {
        return capX + capsuleW * 0.5f;
    }

    /** Pearl capsule centre x (the pearl popover's pointer). */
    public float pearlsCenterX() {
        return pearlsX + pearlsW * 0.5f;
    }

    /** Capsule centre y. */
    public float capsuleCenterY() {
        return capY;
    }

    /** Capsule bottom (the shell and pearl popovers hang from it). */
    public float capsuleBottom() {
        return capY - capH * 0.5f;
    }
}
