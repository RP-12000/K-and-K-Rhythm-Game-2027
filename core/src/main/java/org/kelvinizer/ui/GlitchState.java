package org.kelvinizer.ui;

import com.badlogic.gdx.utils.Pool;

/**
 * 单个元素的故障状态：静止等级（G0 干净或 G1 微闪）、
 * 一次突发（G2、G2 红或 G3）的开始时间和时长、让各元素抖动错开的随机种子。
 * 还可设置定时自发的突发；从 {@link GlitchText#obtainState()} 取得或在构造时创建，不要每帧新建。
 *
 * <p>Per-element glitch state (Appendix A.3): the resting level (G0 clean or G1 shimmer), an
 * optional burst (G2, G2-red or G3) with its start time and duration, a seed that
 * desynchronises jitter between elements, and an optional ambient "hero" burst interval.
 *
 * <p>Obtain from {@link GlitchText#obtainState()} (pooled) or allocate once per element at
 * construction time; never per frame. Times are in seconds on the caller's clock (the same
 * {@code time} passed to {@link GlitchText#draw}).
 */
public final class GlitchState implements Pool.Poolable {
    private static int seedCounter = 0x51ED;

    GlitchText.Level ambient = GlitchText.Level.G0;
    GlitchText.Level burst;
    float burstStart = -100f;
    float burstDur;
    int seed;
    float idleInterval;
    float nextIdle = -1f;

    /** Creates a clean state with a fresh seed. */
    public GlitchState() {
        reseed();
    }

    /** Sets the resting level ({@link GlitchText.Level#G0} or {@link GlitchText.Level#G1}). */
    public GlitchState ambient(GlitchText.Level level) {
        this.ambient = level == null ? GlitchText.Level.G0 : level;
        return this;
    }

    /** Resting level. */
    public GlitchText.Level ambient() {
        return ambient;
    }

    /**
     * Enables ambient hero bursts: a G2 every {@code intervalSec × random(0.8, 1.2)} (use the
     * layer's idle-glitch interval); 0 disables them.
     */
    public GlitchState idleBursts(float intervalSec) {
        this.idleInterval = Math.max(0f, intervalSec);
        this.nextIdle = -1f;
        return this;
    }

    /**
     * Starts a burst at {@code now}. G2 / G2-red last 60–140 ms, G3 up to 200 ms
     * (seeded random); a G0/G1 level only changes the resting level.
     */
    public GlitchState trigger(GlitchText.Level level, float now) {
        if (level == GlitchText.Level.G0 || level == GlitchText.Level.G1) {
            ambient = level;
            return this;
        }
        burst = level;
        burstStart = now;
        float r = Anim.hash01(seed, (int) (now * 1000f));
        burstDur = level == GlitchText.Level.G3 ? 0.14f + 0.06f * r : 0.06f + 0.08f * r;
        return this;
    }

    /** Starts a burst with an explicit duration in seconds. */
    public GlitchState trigger(GlitchText.Level level, float now, float durationSec) {
        trigger(level, now);
        if (burst == level) burstDur = Math.max(0.02f, durationSec);
        return this;
    }

    /** True while a burst is running at {@code now}. */
    public boolean isBursting(float now) {
        return burst != null && now >= burstStart && now - burstStart < burstDur;
    }

    /** Level in effect at {@code now} (the burst level while bursting, else the resting level). */
    public GlitchText.Level levelAt(float now) {
        return isBursting(now) ? burst : ambient;
    }

    /** Burst progress 0..1 at {@code now} (−1 when not bursting). */
    public float burstProgress(float now) {
        if (!isBursting(now)) return -1f;
        return (now - burstStart) / burstDur;
    }

    /** Seconds since the burst started (large when none). */
    public float burstElapsed(float now) {
        return now - burstStart;
    }

    /** Draws a new seed. */
    public GlitchState reseed() {
        seedCounter += 0x9E3779B1;
        seed = (int) (Anim.hash01(seedCounter) * 0x7FFFFF) + 1;
        return this;
    }

    /** Seed used for jitter. */
    public int seed() {
        return seed;
    }

    @Override
    public void reset() {
        ambient = GlitchText.Level.G0;
        burst = null;
        burstStart = -100f;
        burstDur = 0f;
        idleInterval = 0f;
        nextIdle = -1f;
        reseed();
    }
}
