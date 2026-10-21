package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.Main;
import org.kelvinizer.profile.AvatarSpec;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;

import java.util.Random;

/**
 * 会动的「声呐印记」头像：切换潜水员时左右翻转，换成新头像；随机重抽时像老虎机一样滚过六个样式，再弹跳着停在结果上。
 * 40 级起，头像外多一圈轨道环，上面有两个绕行的光点。
 *
 * <p>An animated "sonar sigil" (DESIGN §8.5) on top of the shared {@link Avatar} cache:
 *
 * <ul>
 *   <li><b>Flip</b> ({@link #flipTo}): scaleX 1 → 0 → 1 over 220 ms; the sigil swaps at the
 *       midpoint (§8.3 switch).</li>
 *   <li><b>Reroll</b> ({@link #reroll}): a slot machine that cycles six random variants in 400 ms
 *       (each sliding in from above) while the sigil swings on a spring to ±18°, then settles on
 *       the result with a bouncy pop.</li>
 *   <li><b>Orbit ring</b> (§8.6, Lv 40): a thin ring with two orbiting blips.</li>
 * </ul>
 * The cached texture holds premultiplied alpha and is composited with
 * {@code (ONE, ONE_MINUS_SRC_ALPHA)}. Nothing is allocated per frame (a reroll allocates its six
 * variants once, when it starts).
 */
public final class AvatarView {
    /** Flip duration (s). */
    public static final float FLIP = 0.22f;
    /** Reroll reel duration (s) and variant count. */
    public static final float REEL = 0.40f;
    private static final int REEL_N = 6;

    private final Main game;
    private final Draw draw;
    private final Avatar.Spec spec = new Avatar.Spec();
    private final Avatar.Spec pending = new Avatar.Spec();
    private final Avatar.Spec[] reel = new Avatar.Spec[REEL_N];
    private final Spring rotation = new Spring(Spring.Preset.BOUNCY, 0f);
    private final Spring pop = new Spring(Spring.Preset.BOUNCY, 1f);
    private final Color tmp = new Color();
    private float flipT = -1f, reelT = -1f;
    private boolean swapped;
    private boolean orbit;
    private float time;

    /** Creates a view showing a default sigil. */
    public AvatarView(Main game) {
        this.game = game;
        this.draw = game.draw();
        for (int i = 0; i < REEL_N; i++) reel[i] = new Avatar.Spec();
    }

    /** Maps a profile avatar plus the diver name (initial glyph) into {@code out}. */
    public static Avatar.Spec toSpec(AvatarSpec a, String name, Avatar.Spec out) {
        if (a == null) return out.set(0L, 0, 0, 0, 1, 0, ShellFormat.initialOf(name));
        return out.set(a.seed, a.shape, a.pattern, a.palette, a.glyph, a.frame, ShellFormat.initialOf(name));
    }

    /** Shows {@code a} at once (cancels any animation). */
    public AvatarView set(AvatarSpec a, String name) {
        toSpec(a, name, spec);
        flipT = -1f;
        reelT = -1f;
        rotation.snap(0f);
        pop.snap(1f);
        return this;
    }

    /** Shows {@code s} at once. */
    public AvatarView set(Avatar.Spec s) {
        spec.set(s);
        flipT = -1f;
        reelT = -1f;
        rotation.snap(0f);
        pop.snap(1f);
        return this;
    }

    /** Flips to {@code a} (scaleX 1 → 0 → 1, 220 ms). */
    public void flipTo(AvatarSpec a, String name) {
        toSpec(a, name, pending);
        reelT = -1f;
        flipT = 0f;
        swapped = false;
    }

    /**
     * Plays the REROLL slot machine and lands on {@code result}. The six reel variants are drawn
     * from options unlocked at {@code depthM} metres.
     */
    public void reroll(AvatarSpec result, String name, int depthM, Random random) {
        char initial = ShellFormat.initialOf(name);
        for (int i = 0; i < REEL_N; i++) {
            AvatarSpec v = AvatarSpec.random(random, depthM);
            reel[i].set(v.seed, v.shape, v.pattern, v.palette, v.glyph, v.frame, initial);
        }
        toSpec(result, name, pending);
        flipT = -1f;
        reelT = 0f;
        float sign = random.nextBoolean() ? 1f : -1f;
        rotation.setPreset(Spring.Preset.BOUNCY).setTarget(18f * sign);
        pop.setPreset(Spring.Preset.BOUNCY).setTarget(0.92f);
    }

    /** Replaces the shown sigil in place (no animation), e.g. a palette swatch tap with a pop. */
    public void replace(AvatarSpec a, String name, boolean popIt) {
        toSpec(a, name, spec);
        if (flipT >= 0f || reelT >= 0f) toSpec(a, name, pending);
        if (popIt) pop.setValue(0.9f).setTarget(1f);
    }

    /** Enables the Lv 40 orbit ring. */
    public void setOrbit(boolean on) {
        orbit = on;
    }

    /** The sigil currently shown (the final one while animating). */
    public Avatar.Spec spec() {
        return flipT >= 0f || reelT >= 0f ? pending : spec;
    }

    /** Advances the animations. */
    public void update(float dt) {
        time += dt;
        rotation.update(dt);
        pop.update(dt);
        if (flipT >= 0f) {
            flipT += dt;
            if (!swapped && flipT >= FLIP * 0.5f) {
                spec.set(pending);
                swapped = true;
            }
            if (flipT >= FLIP) flipT = -1f;
        }
        if (reelT >= 0f) {
            reelT += dt;
            if (reelT >= REEL) {
                reelT = -1f;
                spec.set(pending);
                rotation.setTarget(0f);
                pop.setValue(0.82f).setTarget(1f).impulse(1.2f);
            }
        }
    }

    /** Draws the sigil centred at (cx, cy), {@code size} across. */
    public void draw(Batch b, float cx, float cy, float size, float alpha) {
        if (alpha <= 0.002f || size <= 0f) return;
        float sx = 1f;
        if (flipT >= 0f) {
            float p = Anim.clamp01(flipT / FLIP);
            sx = p < 0.5f ? 1f - Anim.POW2_IN.apply(p * 2f) : Anim.POW2_OUT.apply((p - 0.5f) * 2f);
            sx = Math.max(0.02f, sx);
        }
        Avatar.Spec shown = spec;
        float dy = 0f;
        if (reelT >= 0f) {
            float slot = REEL / REEL_N;
            int i = Math.min(REEL_N - 1, (int) (reelT / slot));
            shown = reel[i];
            float local = Anim.clamp01((reelT - i * slot) / slot);
            dy = (1f - Anim.POW2_OUT.apply(local)) * size * 0.16f;
        }
        float k = pop.value();
        float s = size * k;
        if (orbit) drawOrbit(cx, cy, s, alpha);
        TextureRegion r = game.avatars().region(b, shown);
        int srcC = b.getBlendSrcFunc(), dstC = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha(), dstA = b.getBlendDstFuncAlpha();
        float old = b.getPackedColor();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.setColor(alpha, alpha, alpha, alpha);
        float y = cy + dy;
        b.draw(r, cx - s * 0.5f, y - s * 0.5f, s * 0.5f, s * 0.5f, s, s, sx, 1f, rotation.value());
        b.setBlendFunctionSeparate(srcC, dstC, srcA, dstA);
        b.setPackedColor(old);
        if (reelT < 0f && Math.abs(rotation.value()) < 2f) {
            game.avatars().drawFrame(shown, cx, y, s * sx, alpha * sx, time);
        }
    }

    private void drawOrbit(float cx, float cy, float size, float alpha) {
        float r = size * 0.5f + Math.max(14f, size * 0.09f);
        Color a = Avatar.paletteA(spec().palette);
        draw.circle(cx, cy, r, Math.max(1f, size * 0.006f), Theme.INK1, 0.16f * alpha);
        float ang = time * 1.4f;
        draw.glowDot(cx + MathUtils.cos(ang) * r, cy + MathUtils.sin(ang) * r, Math.max(18f, size * 0.10f), a,
            0.9f * alpha);
        float ang2 = -time * 0.9f + 2.1f;
        Theme.mix(a, Theme.INK1, 0.5f, tmp);
        draw.glowDot(cx + MathUtils.cos(ang2) * r, cy + MathUtils.sin(ang2) * r, Math.max(12f, size * 0.06f), tmp,
            0.7f * alpha);
    }
}
