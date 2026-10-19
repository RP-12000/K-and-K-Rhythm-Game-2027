package org.kelvinizer.fx;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEffectPool;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.utils.Array;

/**
 * 一种打击特效的对象池：实例创建时就预热，同时存活的不超过 {@link #MAX} 个，游戏中生成、绘制和回收都不分配内存。
 * 池满时，一次性的爆发回收最旧的一个，持续的光点流（按住水流时）拒绝新的。
 *
 * <p>One pool of pooled copies of a prototype effect plus the list of its live instances
 * (DESIGN §5.9: "initial 8, max 24").
 *
 * <p>The libGDX {@link ParticleEffectPool} only caps the number of <em>free</em> instances; this
 * class also caps the <em>live</em> ones at {@link #MAX}, so a pool never holds more than
 * {@code MAX} instances in total. Every instance is warmed when it is created (particles, their
 * tint arrays and the lazily built size/motion tables are allocated up front), so spawning,
 * updating, drawing and freeing never allocate during play. The pool is pre-filled with the
 * number of instances its effect can have live at once in play; only a pool that needs more than
 * that creates (once) another warmed instance, up to {@link #MAX}.
 *
 * <p>Live instances are kept oldest first. A full burst pool recycles its oldest instance; a full
 * continuous pool (hold streams) refuses the spawn.
 */
final class FxPool extends ParticleEffectPool {
    /** Instances created when a pool is built, unless the owner asks for more (DESIGN §5.9). */
    static final int INITIAL = 8;
    /** Maximum instances (live and free each). */
    static final int MAX = 24;
    /** Length of the priming step that makes a new burst visible in the frame it was spawned. */
    private static final float PRIME_SEC = 0.001f;

    private final ParticleEffect prototype;
    private final boolean burst;
    private final int cost;
    private final PooledEffect[] live = new PooledEffect[MAX];
    private final float[] liveScale = new float[MAX];
    private int liveCount;
    private int created;

    /**
     * @param prototype the loaded effect every instance copies (kept, never drawn)
     * @param burst     true for one-shot bursts (the oldest is recycled when full), false for
     *                  continuous effects (a spawn is refused when full)
     * @param initial   warmed instances created now (clamped to 1..{@link #MAX})
     */
    FxPool(ParticleEffect prototype, boolean burst, int initial) {
        super(prototype, MAX, MAX);
        this.prototype = prototype;
        this.burst = burst;
        int n = 0;
        Array<ParticleEmitter> em = prototype.getEmitters();
        for (int i = 0; i < em.size; i++) n += em.get(i).getMaxParticleCount();
        cost = n;
        fill(Math.max(1, Math.min(MAX, initial)));
    }

    @Override
    protected PooledEffect newObject() {
        created++;
        PooledEffect e = super.newObject();
        e.setEmittersCleanUpBlendFunction(false);
        Array<ParticleEmitter> em = e.getEmitters();
        Array<ParticleEmitter> proto = prototype.getEmitters();
        for (int i = 0; i < em.size; i++) {
            ParticleEmitter m = em.get(i);
            m.preAllocateParticles();
            m.addParticles(m.getMaxParticleCount()); // first activation allocates each particle's tint array
            m.matchSize(proto.get(i));                // builds the lazy size / motion tables now
            m.matchMotion(proto.get(i));
        }
        e.scaleEffect(1f); // creates the emitter array's reusable iterator (scaleEffect iterates with for-each)
        e.reset(false);
        return e;
    }

    /**
     * Starts an instance at (x, y), scaled by {@code scale} (size and motion), and primes it with
     * a 1 ms step so it is visible this frame even when the caller runs after {@code update}.
     *
     * @param tint tint colours for the first emitter ({r, g, b} per key of its timeline), or null
     *             to keep the prototype's (the array is referenced, never copied: pass a constant)
     * @return the live instance, or null when a continuous pool is full
     */
    PooledEffect spawn(float x, float y, float scale, float[] tint) {
        if (liveCount >= MAX) {
            if (!burst) return null;
            release(0);
        }
        PooledEffect e = obtain();
        if (tint != null) e.getEmitters().first().getTint().setColors(tint);
        if (scale != 1f) {
            e.scaleEffect(scale);
            e.reset(false); // re-derive the cached spawn sizes from the scaled values
        }
        e.setPosition(x, y);
        e.update(PRIME_SEC);
        live[liveCount] = e;
        liveScale[liveCount] = scale;
        liveCount++;
        return e;
    }

    /** Advances every live instance and frees the finished ones. */
    void update(float delta) {
        int i = 0;
        while (i < liveCount) {
            PooledEffect e = live[i];
            e.update(delta);
            if (e.isComplete()) {
                release(i);
            } else {
                i++;
            }
        }
    }

    /** Draws every live instance (the caller has set the blend function). */
    void draw(Batch batch) {
        for (int i = 0; i < liveCount; i++) live[i].draw(batch);
    }

    /** Frees every live instance (the free list is kept). */
    void releaseAll() {
        while (liveCount > 0) release(liveCount - 1);
    }

    /** Live instances. */
    int liveCount() {
        return liveCount;
    }

    /** Live particles, i.e. quads drawn by this pool. */
    int quads() {
        int n = 0;
        for (int i = 0; i < liveCount; i++) {
            Array<ParticleEmitter> em = live[i].getEmitters();
            for (int k = 0; k < em.size; k++) n += em.get(k).getActiveCount();
        }
        return n;
    }

    /** Most quads one instance can draw (sum of its emitters' maximum particle counts). */
    int cost() {
        return cost;
    }

    /** Instances created so far (live and free); constant while the pool is big enough. */
    int created() {
        return created;
    }

    private void release(int index) {
        PooledEffect e = live[index];
        float scale = liveScale[index];
        int tail = liveCount - index - 1;
        if (tail > 0) {
            System.arraycopy(live, index + 1, live, index, tail);
            System.arraycopy(liveScale, index + 1, liveScale, index, tail);
        }
        liveCount--;
        live[liveCount] = null;
        free(e); // resets the instance and restores the prototype's size / motion
        if (scale != 1f) e.reset(false); // the reset inside free() ran before the sizes were restored
    }

    /**
     * Stops a continuous instance's emission now; its live particles finish their life.
     * ({@link ParticleEmitter#allowCompletion()} also ends the current duration cycle, so the
     * emitter is done on its next update.)
     */
    static void stopEmission(ParticleEffect e) {
        Array<ParticleEmitter> em = e.getEmitters();
        for (int i = 0; i < em.size; i++) em.get(i).allowCompletion();
    }
}
