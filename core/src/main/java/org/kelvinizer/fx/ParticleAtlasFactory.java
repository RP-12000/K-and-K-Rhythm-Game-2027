package org.kelvinizer.fx;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.kelvinizer.ui.Draw;

/**
 * 把 {@code assets/ParticleEffects/} 里的粒子文件加载到 {@link Draw} 的共享图集上，让粒子和场景共用一张贴图。
 * 这样加法混合的那一段仍只需一批绘制；还提供天空线用的上下翻转副本和染色方法，只在加载时调用。
 *
 * <p>Loads particle effects against the shared generated atlas of {@link Draw} (ARCHITECTURE §1,
 * DESIGN §2.1 and §5.9), so every particle comes from the same texture as the rest of the
 * gameplay scene and the additive block stays one batch.
 *
 * <p>The {@code .p} files in {@code assets/ParticleEffects/} name atlas regions directly as their
 * image paths ({@code ring_soft}, {@code ring_thin}, {@code soft_dot}, {@code streak}). They are
 * standard libGDX particle-editor files and can still be opened there (point the editor's image
 * directory at PNG exports of those regions).
 *
 * <p>Holds no state: the atlas is owned by {@link Draw} and the loaded effects by their caller.
 * Load-time only (it allocates).
 */
final class ParticleAtlasFactory {

    private ParticleAtlasFactory() {
    }

    /**
     * Loads {@code file} with {@link ParticleEffect#load(FileHandle, com.badlogic.gdx.graphics.g2d.TextureAtlas)},
     * its images resolved in the shared atlas. Emitters are set not to restore the blend function
     * after drawing, so consecutive additive emitters never flush.
     *
     * @throws GdxRuntimeException when the file is missing or malformed, or names a region the
     *                             atlas does not have
     */
    static ParticleEffect load(FileHandle file, Draw draw) {
        ParticleEffect effect = new ParticleEffect();
        try {
            effect.load(file, draw.atlas());
        } catch (RuntimeException e) {
            throw new GdxRuntimeException("Cannot load particle effect " + file.path(), e);
        }
        effect.setEmittersCleanUpBlendFunction(false);
        return effect;
    }

    /**
     * Returns a copy of {@code src} mirrored about the horizontal axis: emission angles,
     * rotations, gravity and Y offsets are negated (the sky line's bursts open downward).
     */
    static ParticleEffect mirroredY(ParticleEffect src) {
        ParticleEffect effect = new ParticleEffect(src);
        Array<ParticleEmitter> emitters = effect.getEmitters();
        for (int i = 0; i < emitters.size; i++) {
            ParticleEmitter e = emitters.get(i);
            negate(e.getAngle());
            negate(e.getRotation());
            negate(e.getGravity());
            negate(e.getYOffsetValue());
        }
        effect.setEmittersCleanUpBlendFunction(false);
        return effect;
    }

    /** Sets a single-colour tint on {@code emitter} (no-op when it is null). */
    static void tint(ParticleEmitter emitter, Color c) {
        if (emitter == null) return;
        emitter.getTint().setColors(new float[] {c.r, c.g, c.b});
        emitter.getTint().setTimeline(new float[] {0f});
    }

    /** Sets a two-colour tint gradient over the particle life on {@code emitter} (no-op when null). */
    static void tint(ParticleEmitter emitter, Color from, Color to) {
        if (emitter == null) return;
        emitter.getTint().setColors(gradient(from, to));
        emitter.getTint().setTimeline(new float[] {0f, 1f});
    }

    /** Tint colours {from.rgb, to.rgb} for a two-key timeline {0, 1}. */
    static float[] gradient(Color from, Color to) {
        return new float[] {from.r, from.g, from.b, to.r, to.g, to.b};
    }

    private static void negate(ParticleEmitter.RangedNumericValue v) {
        if (!v.isActive()) return;
        v.setLow(-v.getLowMin(), -v.getLowMax());
        if (v instanceof ParticleEmitter.ScaledNumericValue) {
            ParticleEmitter.ScaledNumericValue s = (ParticleEmitter.ScaledNumericValue) v;
            s.setHigh(-s.getHighMin(), -s.getHighMax());
        }
    }
}
