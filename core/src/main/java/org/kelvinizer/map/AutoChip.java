package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;

/**
 * 歌曲卡片操作行里的 AUTO（自动播放）开关：指示圆点下写着 AUTO，打开后染上 AUTO 色；松手时切换。
 *
 * <p>The AUTO toggle chip of the action row (DESIGN §4.7): {@code AUTO} (UI_BOLD 20) under an
 * indicator dot; on = {@code auto} tint. Toggles on release; disabled (a locked song) it is
 * drawn at α .4 and only shakes. No allocation per frame.
 */
final class AutoChip extends Pressable {
    private final MapRenderer r;
    private boolean on;
    /** On amount 0..1 (eased). */
    private float onK;
    private final Color c1 = new Color();
    /** The card scale (DESIGN §0.3), set by the card every frame; bounds are already scaled. */
    float k = 1f;

    AutoChip(Draw draw, MapRenderer r) {
        super(draw);
        this.r = r;
    }

    boolean isOn() {
        return on;
    }

    void setOn(boolean value) {
        on = value;
    }

    @Override
    protected void onFire() {
        on = !on;
    }

    @Override
    protected void onUpdate(float dt) {
        onK = approach(onK, on ? 1f : 0f, dt / Anim.SHORT);
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        alpha *= alphaMul();
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float bw = w * s, bh = h * s, bx = cx - bw * 0.5f, by = cy - bh * 0.5f;
        float rad = 18f * k * s;
        draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL_HI, 0.9f * alpha);
        float t = onK;
        if (t > 0f) draw.roundRect(bx, by, bw, bh, rad, Theme.AUTO, 0.16f * t * alpha);
        Theme.mix(Theme.STROKE, Theme.AUTO, t, c1);
        draw.roundRectStroke(bx, by, bw, bh, rad, 2f, c1, (0.8f + 0.2f * t) * alpha);
        if (press > 0f || hover > 0f) {
            draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, (0.08f * press + 0.05f * hover) * alpha);
        }
        ripple.color(Theme.AUTO);
        drawRipple(alpha);
        float ks = k * s;
        float dy = cy + 22f * ks;
        if (t > 0f) draw.glowDot(cx, dy, 46f * ks, Theme.AUTO, 0.5f * t * alpha);
        draw.circle(cx, dy, 9f * ks, 2.5f, t > 0.5f ? Theme.AUTO : Theme.INK3, alpha);
        draw.disc(cx, dy, 6f * ks * t, Theme.AUTO, t * alpha);
    }

    /** UI_BOLD phase: the label. */
    void drawBold(Batch b, float alpha) {
        if (!visible || alpha <= 0.002f) return;
        alpha *= alphaMul();
        float s = visualScale();
        Theme.mix(Theme.INK2, Theme.AUTO, onK, c1);
        r.bold(b, "AUTO", MapUi.cap(20f, k) * s, centerX() + shakeX(), centerY() + liftY() - 22f * k * s,
            Align.center | Fonts.VCENTER, c1, alpha);
    }
}
