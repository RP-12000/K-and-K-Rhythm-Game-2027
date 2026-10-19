package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;

/**
 * 按下控件时的波纹：从触点扩散的圆环加一层淡淡的实心圆，380 ms 内放大并淡出。
 * 不做裁剪，可能超出控件边缘最多 48 单位；可重复使用，不分配内存。
 *
 * <p>Press ripple of the interaction primitives (DESIGN §1.5): a ring from the touch point,
 * radius 0 → {@code min(1.2·max(w,h), max(w,h)/2 + 48)} over 380 ms {@code pow2Out}, stroke
 * 3 → 1, α .30 → 0, plus a fill disc α .08 → 0. No scissor: it may spill up to 48 units
 * beyond the control. Reusable, allocation-free.
 */
public final class Ripple {
    private static final float DURATION = 0.38f;
    private float x, y, maxR, t = DURATION;
    private final Color color = new Color(Theme.INK1);

    /** Starts a ripple at (px, py) for a control of size w×h. */
    public void start(float px, float py, float w, float h) {
        x = px;
        y = py;
        float m = Math.max(w, h);
        maxR = Math.min(1.2f * m, m * 0.5f + 48f);
        t = 0f;
    }

    /** Sets the ripple colour (default ink1). */
    public Ripple color(Color c) {
        color.set(c);
        return this;
    }

    /** True while visible. */
    public boolean isActive() {
        return t < DURATION;
    }

    /** Advances the ripple. */
    public void update(float dt) {
        if (t < DURATION) t += dt;
    }

    /** Draws the ripple (normal blending). */
    public void draw(Draw d, float alpha) {
        if (t >= DURATION) return;
        float p = t / DURATION;
        float r = maxR * Anim.POW2_OUT.apply(p);
        float fade = 1f - p;
        d.disc(x, y, r, color, 0.08f * fade * alpha);
        if (r > 2f) d.circle(x, y, r, 3f - 2f * p, color, 0.30f * fade * alpha);
    }
}
