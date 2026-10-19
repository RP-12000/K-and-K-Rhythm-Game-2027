package org.kelvinizer.ui;

/**
 * 一维阻尼弹簧：按下缩放、开关旋钮、弹出和回弹等动画都用它，有 SNAPPY、BOUNCY、SOFT 等几种预设手感。
 * 每帧按不超过 1/120 秒的小步积分，不分配内存；每个动画值各用一个。
 *
 * <p>Scalar damped spring (DESIGN §1.5): semi-implicit Euler with sub-steps ≤ 1/120 s,
 * {@code a = −ω²(x − target) − 2ζω·v}. Allocation-free; keep one per animated value.
 *
 * <pre>
 * Spring scale = new Spring(Spring.Preset.SNAPPY, 1f);
 * scale.setTarget(0.94f);           // press
 * scale.update(delta);  float s = scale.value();
 * </pre>
 */
public final class Spring {

    /** Named presets: angular frequency ω (rad/s) and damping ratio ζ. */
    public enum Preset {
        /** Press scale, toggle knob. */
        SNAPPY(30f, 0.75f),
        /** Node pop, stamp settle, release overshoot. */
        BOUNCY(18f, 0.42f),
        /** Parallax, gauge follow. */
        SOFT(9f, 0.90f),
        /** Node lag-wobble, Welcome K lean. */
        JELLY(12f, 0.30f),
        /** Overscroll and zoom spring-back. */
        CAMERA(12f, 1.00f),
        /** Sheet detents, card overshoot. */
        SHEET(16f, 0.85f);

        /** Angular frequency in rad/s. */
        public final float omega;
        /** Damping ratio. */
        public final float zeta;

        Preset(float omega, float zeta) {
            this.omega = omega;
            this.zeta = zeta;
        }
    }

    private static final float MAX_STEP = 1f / 120f;

    private float value, velocity, target;
    private float omega, zeta;

    /** Creates a spring at rest at {@code value} with the given preset. */
    public Spring(Preset preset, float value) {
        setPreset(preset);
        this.value = value;
        this.target = value;
    }

    /** Creates a spring at rest at {@code value} with explicit ω and ζ. */
    public Spring(float omega, float zeta, float value) {
        this.omega = omega;
        this.zeta = zeta;
        this.value = value;
        this.target = value;
    }

    /** Switches the preset (keeps value and velocity). */
    public Spring setPreset(Preset preset) {
        this.omega = preset.omega;
        this.zeta = preset.zeta;
        return this;
    }

    /** Sets explicit parameters (keeps value and velocity). */
    public Spring setParams(float omega, float zeta) {
        this.omega = omega;
        this.zeta = zeta;
        return this;
    }

    /** Sets the rest target. */
    public Spring setTarget(float target) {
        this.target = target;
        return this;
    }

    /** Snaps value and target to {@code v} and zeroes the velocity. */
    public Spring snap(float v) {
        value = target = v;
        velocity = 0f;
        return this;
    }

    /** Sets the value without touching target or velocity. */
    public Spring setValue(float v) {
        value = v;
        return this;
    }

    /** Adds an instantaneous velocity kick. */
    public Spring impulse(float dv) {
        velocity += dv;
        return this;
    }

    /** Sets the velocity. */
    public Spring setVelocity(float v) {
        velocity = v;
        return this;
    }

    /** Integrates the spring by {@code delta} seconds using sub-steps of at most 1/120 s. */
    public float update(float delta) {
        if (delta <= 0f) return value;
        int steps = (int) Math.ceil(delta / MAX_STEP);
        if (steps > 32) steps = 32;
        float h = delta / steps;
        float w2 = omega * omega;
        float c = 2f * zeta * omega;
        for (int i = 0; i < steps; i++) {
            float a = -w2 * (value - target) - c * velocity;
            velocity += a * h;
            value += velocity * h;
        }
        return value;
    }

    /** Current value. */
    public float value() {
        return value;
    }

    /** Current velocity. */
    public float velocity() {
        return velocity;
    }

    /** Current target. */
    public float target() {
        return target;
    }

    /** True when both displacement and velocity are below {@code eps}. */
    public boolean isSettled(float eps) {
        return Math.abs(value - target) < eps && Math.abs(velocity) < eps;
    }
}
