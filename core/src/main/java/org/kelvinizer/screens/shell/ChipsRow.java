package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;

/**
 * 设置行：右侧是几个可以多选的开关按钮，样子和个人页 STATS 的难度按钮一样（亮着的有底色、边框和小灯），全都不选也可以。
 * 游玩页的 EARLY / LATE ON（RESONANCE、ECHO）用它（Kelvin 2026-10-18 06:03）。
 *
 * <p>A setting row with a few toggle chips side by side in the widened control column (Kelvin 2026-10-18 06:03:
 * EARLY / LATE ON with RESONANCE and ECHO), drawn like the difficulty toggles of the Profile STATS tab: a lit
 * chip has a tint, a border and a lit lamp in its colour and its name in that colour; a dark one is a plain
 * card with a ring lamp and a grey name. Any combination can be lit, none included; each tap flips one chip.
 */
public class ChipsRow extends SettingRow {
    /** Binding between the chips and the stored setting. */
    public interface Value {
        /** @return whether chip {@code i} is lit. */
        boolean get(int i);

        /** Stores chip {@code i} as {@code on} (called once per tap). */
        void set(int i, boolean on);
    }

    private final CharSequence[] names;
    private final Color[] colors;
    private final Tappable[] chips;
    private final boolean[] lit;
    private final Value value;
    private float chipW, chipH;

    /** Creates the row over the chips {@code names} in {@code colors} (both arrays are kept). */
    public ChipsRow(Main game, CharSequence label, CharSequence desc, boolean device, CharSequence[] names,
                    Color[] colors, final Value value) {
        super(game, label, desc, device);
        this.names = names;
        this.colors = colors;
        this.value = value;
        chips = new Tappable[names.length];
        lit = new boolean[names.length];
        for (int i = 0; i < chips.length; i++) {
            final int chip = i;
            chips[i] = new Tappable(draw);
            chips[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    lit[chip] = !lit[chip];
                    value.set(chip, lit[chip]);
                }
            });
        }
    }

    @Override
    public void sync() {
        for (int i = 0; i < lit.length; i++) lit[i] = value.get(i);
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        for (Tappable c : chips) c.setEnabled(on);
    }

    @Override
    protected void onPlace() {
        widenControl();
        float gap = 16f * k;
        chipH = 64f * k;
        chipW = (controlW - gap * (chips.length - 1)) / chips.length;
        for (int i = 0; i < chips.length; i++) {
            chips[i].setBounds(controlX + i * (chipW + gap), lineCy() - chipH * 0.5f, chipW, chipH);
        }
    }

    @Override
    public void update(float dt) {
        for (Tappable c : chips) c.update(dt);
    }

    @Override
    protected void drawControl(Batch b, float alpha) {
        float a = alpha * (enabled ? 1f : 0.4f);
        for (int i = 0; i < chips.length; i++) {
            Tappable t = chips[i];
            Color c = colors[i];
            float s = t.visualScale(), sw = chipW * s, sh = chipH * s;
            float sx = t.centerX() - sw * 0.5f + t.shakeX(), sy = t.centerY() - sh * 0.5f;
            Panel.card(draw, sx, sy, sw, sh, a);
            float hover = t.hoverAmount();
            if (lit[i]) {
                draw.roundRect(sx, sy, sw, sh, Panel.RADIUS, c, (0.10f + 0.06f * hover) * a);
                draw.roundRectStroke(sx, sy, sw, sh, Panel.RADIUS, 2f, c, 0.85f * a);
            } else if (hover > 0f) {
                draw.roundRectStroke(sx, sy, sw, sh, Panel.RADIUS, 2f, c, 0.35f * hover * a);
            }
            t.drawPress(c, a);
            float lx = sx + sw - 26f * k, ly = sy + sh * 0.5f;
            if (lit[i]) {
                draw.glowDot(lx, ly, 30f * k, c, 0.5f * a);
                draw.disc(lx, ly, 7f * k, c, a);
            } else {
                draw.circle(lx, ly, 7f * k, 2f, c, 0.5f * a);
            }
        }
    }

    @Override
    public void drawLabels(Batch b, float alpha) {
        super.drawLabels(b, alpha);
        float a = alpha * (enabled ? 1f : 0.4f);
        for (int i = 0; i < chips.length; i++) {
            Tappable t = chips[i];
            float s = t.visualScale(), sw = chipW * s;
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f * k * s, names[i], t.centerX() - sw * 0.5f + t.shakeX() + 20f * k,
                t.centerY(), Align.left | Fonts.VCENTER, sw - 64f * k, 0.6f, lit[i] ? colors[i] : Theme.INK3, a);
        }
    }

    @Override
    public boolean touchDown(float px, float py, int pointer) {
        if (!shownInList) return false;
        for (Tappable c : chips) {
            if (c.touchDown(px, py, pointer)) return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(float px, float py, int pointer) {
        boolean any = false;
        for (Tappable c : chips) any |= c.touchDragged(px, py, pointer);
        return any;
    }

    @Override
    public boolean touchUp(float px, float py, int pointer) {
        boolean any = false;
        for (Tappable c : chips) any |= c.touchUp(px, py, pointer);
        return any;
    }

    @Override
    public void cancel() {
        for (Tappable c : chips) c.cancel();
    }

    @Override
    public void mouseMoved(float px, float py) {
        for (Tappable c : chips) c.mouseMoved(px, py);
    }

    /** Whether chip {@code i} is lit. */
    public boolean isLit(int i) {
        return lit[i];
    }
}
