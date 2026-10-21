package org.kelvinizer.ui;

import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;

/**
 * 全游戏共用的动画用语：时长常量、设计稿里的缓动函数（共享的 {@link Interpolation} 实例）、
 * 计数和抖动等不分配内存的小工具，以及可复用的 {@link Tween}。
 * 弹簧动画在 {@link Spring} 里。
 *
 * <p>Motion vocabulary of DESIGN §1.5: duration tokens, the easing names used throughout the
 * design (all libGDX {@link Interpolation}s, shared static instances), small allocation-free
 * helpers and a reusable {@link Tween}. Springs live in {@link Spring}.
 */
public final class Anim {
    private Anim() { }

    // ------------------------------------------------------------------ duration tokens (s)
    /** Press-in, colour flash. */
    public static final float MICRO = 0.09f;
    /** Toggles, chip morph, tooltips. */
    public static final float SHORT = 0.16f;
    /** Cards, sheets, panels entering. */
    public static final float MEDIUM = 0.28f;
    /** Anything leaving (0.7 × enter). */
    public static final float EXIT = 0.20f;
    /** Camera glides, screen-level moves. */
    public static final float LONG = 0.48f;
    /** Badges, stamps, node select. */
    public static final float POP = 0.32f;
    /** Numbers counting. */
    public static final float COUNT = 0.75f;
    /** Stagger between list items. */
    public static final float STAGGER_LIST = 0.04f;
    /** Shared heartbeat period (s). */
    public static final float HEARTBEAT = 2.4f;

    // ------------------------------------------------------------------ easings (DESIGN names)
    /** {@code pow2Out}. */
    public static final Interpolation POW2_OUT = Interpolation.pow2Out;
    /** {@code pow2In}. */
    public static final Interpolation POW2_IN = Interpolation.pow2In;
    /** {@code pow3Out}. */
    public static final Interpolation POW3_OUT = Interpolation.pow3Out;
    /** {@code pow3In}. */
    public static final Interpolation POW3_IN = Interpolation.pow3In;
    /** {@code pow3} (in-out). */
    public static final Interpolation POW3 = Interpolation.pow3;
    /** {@code pow4In}. */
    public static final Interpolation POW4_IN = Interpolation.pow4In;
    /** {@code exp5Out}. */
    public static final Interpolation EXP5_OUT = Interpolation.exp5Out;
    /** {@code sine} (in-out). */
    public static final Interpolation SINE = Interpolation.sine;
    /** {@code linear}. */
    public static final Interpolation LINEAR = Interpolation.linear;
    /** {@code new SwingOut(1.4f)}: buttons rising. */
    public static final Interpolation SWING_OUT_14 = new Interpolation.SwingOut(1.4f);
    /** {@code new SwingOut(1.6f)}: combo milestones, trace bins, jacket settle. */
    public static final Interpolation SWING_OUT_16 = new Interpolation.SwingOut(1.6f);
    /** {@code new SwingOut(1.8f)}: the "pop" token. */
    public static final Interpolation SWING_OUT_18 = new Interpolation.SwingOut(1.8f);
    /** Alias of {@link #SWING_OUT_18} for the {@code pop} token. */
    public static final Interpolation POP_EASE = SWING_OUT_18;

    // ------------------------------------------------------------------ helpers
    /** Ping alpha curve: {@code (1 - t)²}. */
    public static float pingAlpha(float t) {
        float u = 1f - clamp01(t);
        return u * u;
    }

    /** Clamps to 0..1. */
    public static float clamp01(float t) {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    /** Normalised progress of {@code time} inside [start, start + duration], clamped to 0..1. */
    public static float progress(float time, float start, float duration) {
        if (duration <= 0f) return time >= start ? 1f : 0f;
        return clamp01((time - start) / duration);
    }

    /** Eased progress of {@code time} inside [start, start + duration]. */
    public static float ease(Interpolation interp, float time, float start, float duration) {
        return interp.apply(progress(time, start, duration));
    }

    /**
     * An integer counting from {@code from} to {@code to} at progress {@code p} (0..1), for
     * count-ups of large numbers: computed in double (a float steps by 4 or more above 2^24,
     * which 100,000,000-scale scores pass), exactly {@code to} once {@code p >= 1}, never outside
     * the two ends and non-decreasing in {@code p} toward a larger {@code to}.
     */
    public static long count(long from, long to, float p) {
        if (p >= 1f) return to;
        if (!(p > 0f)) return from;
        long v = from + Math.round((to - from) * (double) p);
        return from <= to ? Math.min(to, v) : Math.max(to, v);
    }

    /** {@code a + (b - a) * t}. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Frame-rate independent exponential approach of {@code current} toward {@code target}
     * with time constant {@code tau} seconds.
     */
    public static float damp(float current, float target, float tau, float delta) {
        if (tau <= 0f) return target;
        return target + (current - target) * (float) Math.exp(-delta / tau);
    }

    /** A 0..1 "breathing" value synced to a period, {@code 0.5 - 0.5 cos}. */
    public static float breathe(float time, float period) {
        return 0.5f - 0.5f * MathUtils.cos(time / period * MathUtils.PI2);
    }

    /**
     * Shake offset for the disabled "no" gesture: ±{@code amp} px, {@code cycles} cycles over
     * {@code duration}, decaying; returns 0 outside [0, duration].
     */
    public static float shake(float t, float duration, float amp, int cycles) {
        if (t <= 0f || t >= duration) return 0f;
        float p = t / duration;
        return MathUtils.sin(p * cycles * MathUtils.PI2) * amp * (1f - p);
    }

    /** Cheap deterministic hash of an int to 0..1 (for seeded jitter without Random). */
    public static float hash01(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return (x >>> 8) / 16777216f;
    }

    /** Hash of two ints to 0..1. */
    public static float hash01(int a, int b) {
        return hash01(a * 0x27d4eb2d + b * 0x165667b1 + 0x3c6ef372);
    }

    /**
     * A tiny allocation-free tween of one float. Reuse an instance per animated value:
     * {@code start(from, to, duration, interp)}, then {@code update(delta)} each frame.
     */
    public static final class Tween {
        private float from, to, duration, elapsed;
        private Interpolation interp = LINEAR;
        private boolean running;

        /** Starts a tween; a duration of 0 jumps to {@code to}. */
        public Tween start(float from, float to, float duration, Interpolation interp) {
            this.from = from;
            this.to = to;
            this.duration = duration;
            this.interp = interp == null ? LINEAR : interp;
            this.elapsed = 0f;
            this.running = duration > 0f;
            return this;
        }

        /** Jumps to a value and stops. */
        public Tween set(float value) {
            from = to = value;
            elapsed = duration = 0f;
            running = false;
            return this;
        }

        /** Advances the tween. */
        public void update(float delta) {
            if (!running) return;
            elapsed += delta;
            if (elapsed >= duration) {
                elapsed = duration;
                running = false;
            }
        }

        /** Current value. */
        public float value() {
            if (duration <= 0f) return to;
            return from + (to - from) * interp.apply(clamp01(elapsed / duration));
        }

        /** Linear progress 0..1. */
        public float progress() {
            return duration <= 0f ? 1f : clamp01(elapsed / duration);
        }

        /** True while animating. */
        public boolean isRunning() {
            return running;
        }

        /** Target value. */
        public float target() {
            return to;
        }
    }
}
