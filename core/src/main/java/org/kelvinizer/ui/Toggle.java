package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;

/**
 * 开关控件：96 × 52 的胶囊形轨道，旋钮随弹簧滑动，打开时轨道变成青色。
 * 点一下在抬起时切换，带 {@link Pressable} 的按下效果；触摸区域至少有最小触摸目标那么大。
 *
 * <p>On/off switch (DESIGN §1.6): a 96×52 pill with a 44 knob that travels on a snappy spring;
 * ON fill {@code groundSig}. Taps toggle on release (with the §1.5 press primitives). The hit
 * rect is at least {@code P}.
 */
public class Toggle extends Pressable {
    /** Visual size. */
    public static final float W = 96f, H = 52f, KNOB = 44f;

    /** Change listener. */
    public interface Listener {
        /** Called after the value changed through a tap. */
        void changed(Toggle toggle, boolean on);
    }

    private boolean on;
    private final Spring knob = new Spring(Spring.Preset.SNAPPY, 0f);
    private Listener listener;
    private final Color c1 = new Color(), c2 = new Color();

    /** Creates a toggle at (0, 0); position it with {@link #setCenter}. */
    public Toggle(Draw draw, boolean on) {
        super(draw);
        this.on = on;
        knob.snap(on ? 1f : 0f);
        w = W;
        h = H;
    }

    /** Sets the listener. */
    public Toggle setListener(Listener l) {
        listener = l;
        return this;
    }

    /** Current value. */
    public boolean isOn() {
        return on;
    }

    /** Sets the value; {@code animate = false} snaps the knob. Does not call the listener. */
    public Toggle setOn(boolean value, boolean animate) {
        on = value;
        if (animate) knob.setTarget(on ? 1f : 0f);
        else knob.snap(on ? 1f : 0f);
        return this;
    }

    @Override
    protected void onFire() {
        on = !on;
        knob.setTarget(on ? 1f : 0f);
        if (listener != null) listener.changed(this, on);
    }

    @Override
    protected void onUpdate(float dt) {
        knob.update(dt);
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float tw = w * s, th = h * s;
        float tx = cx - tw * 0.5f, ty = cy - th * 0.5f;
        float k = Math.max(0f, Math.min(1f, knob.value()));
        // track: off = ink3 @ .35 over panel, on = groundSig
        draw.pill(tx, ty, tw, th, Theme.PANEL, a);
        Theme.mix(Theme.INK3, Theme.GROUND_SIG, k, c1);
        bright(c1, c2);
        draw.pill(tx, ty, tw, th, c2, (0.35f + 0.65f * k) * a);
        draw.roundRectStroke(tx, ty, tw, th, th * 0.5f, 1.5f, Theme.INK1, (0.25f - 0.15f * k) * a);
        drawRipple(a * 0.8f);
        // knob (travel uses the raw spring value for a little overshoot)
        float kr = KNOB * 0.5f * s;
        float pad = (th - KNOB * s) * 0.5f;
        float x0 = tx + pad + kr, x1 = tx + tw - pad - kr;
        float kx = x0 + (x1 - x0) * knob.value();
        draw.disc(kx, cy - 2f, kr + 1f, Theme.SHADOW, 0.5f * a);
        draw.disc(kx, cy, kr, Theme.INK1, a);
    }
}
