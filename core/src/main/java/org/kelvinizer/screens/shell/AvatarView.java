package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import org.kelvinizer.Main;
import org.kelvinizer.profile.AvatarSpec;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Spring;

/**
 * 会动的「声呐印记」头像：切换潜水员时左右翻转，换成新头像。
 *
 * <p>An animated "sonar sigil" (DESIGN §8.5) on top of the shared {@link Avatar} cache: {@link #flipTo} plays a flip,
 * scaleX 1 → 0 → 1 over 220 ms, the sigil swapping at the midpoint (§8.3 switch). The REROLL slot machine went with the
 * random avatars (Kelvin 2026-10-30: one default avatar). The cached texture holds premultiplied alpha and is
 * composited with {@code (ONE, ONE_MINUS_SRC_ALPHA)}. Nothing is allocated per frame.
 */
public final class AvatarView {
    /** Flip duration (s). */
    public static final float FLIP = 0.22f;

    private final Main game;
    private final Avatar.Spec spec = new Avatar.Spec();
    private final Avatar.Spec pending = new Avatar.Spec();
    private final Spring pop = new Spring(Spring.Preset.BOUNCY, 1f);
    private float flipT = -1f;
    private boolean swapped;
    private float time;

    /** Creates a view showing a default sigil. */
    public AvatarView(Main game) {
        this.game = game;
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
        pop.snap(1f);
        return this;
    }

    /** Shows {@code s} at once. */
    public AvatarView set(Avatar.Spec s) {
        spec.set(s);
        flipT = -1f;
        pop.snap(1f);
        return this;
    }

    /** Flips to {@code a} (scaleX 1 → 0 → 1, 220 ms). */
    public void flipTo(AvatarSpec a, String name) {
        toSpec(a, name, pending);
        flipT = 0f;
        swapped = false;
    }

    /** Replaces the shown sigil in place (no animation), e.g. a palette swatch tap with a pop. */
    public void replace(AvatarSpec a, String name, boolean popIt) {
        toSpec(a, name, spec);
        if (flipT >= 0f) toSpec(a, name, pending);
        if (popIt) pop.setValue(0.9f).setTarget(1f);
    }

    /** The sigil currently shown (the final one while animating). */
    public Avatar.Spec spec() {
        return flipT >= 0f ? pending : spec;
    }

    /** Advances the animations. */
    public void update(float dt) {
        time += dt;
        pop.update(dt);
        if (flipT >= 0f) {
            flipT += dt;
            if (!swapped && flipT >= FLIP * 0.5f) {
                spec.set(pending);
                swapped = true;
            }
            if (flipT >= FLIP) flipT = -1f;
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
        float k = pop.value();
        float s = size * k;
        TextureRegion r = game.avatars().region(b, spec);
        int srcC = b.getBlendSrcFunc(), dstC = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha(), dstA = b.getBlendDstFuncAlpha();
        float old = b.getPackedColor();
        b.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        b.setColor(alpha, alpha, alpha, alpha);
        b.draw(r, cx - s * 0.5f, cy - s * 0.5f, s * 0.5f, s * 0.5f, s, s, sx, 1f, 0f);
        b.setBlendFunctionSeparate(srcC, dstC, srcA, dstA);
        b.setPackedColor(old);
        game.avatars().drawFrame(spec, cx, cy, s * sx, alpha * sx, time);
    }
}
