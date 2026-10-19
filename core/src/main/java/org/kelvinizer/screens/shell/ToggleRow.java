package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Toggle;

/**
 * 设置行：右侧一个开关；整行都是点击区域，点行上任何位置也能切换。
 *
 * <p>A setting row with an on/off {@link Toggle} at the right edge (DESIGN §9.1). Tapping anywhere
 * on the row flips it too (the whole row is one generous target with its own press ripple).
 */
public class ToggleRow extends SettingRow {
    /** Binding between the toggle and the stored setting. */
    public interface Value {
        /** @return the stored state. */
        boolean get();

        /** Stores the new state (called once per flip). */
        void set(boolean on);
    }

    private final Toggle toggle;
    private final Tappable row;
    private final Value value;

    /** Creates the row. */
    public ToggleRow(Main game, CharSequence label, CharSequence desc, boolean device, final Value value) {
        super(game, label, desc, device);
        this.value = value;
        toggle = new Toggle(draw, false);
        toggle.setListener(new Toggle.Listener() {
            @Override
            public void changed(Toggle t, boolean on) {
                value.set(on);
            }
        });
        row = new Tappable(draw);
        row.setHaptic(false);
        row.setOnTap(new Runnable() {
            @Override
            public void run() {
                toggle.fire();
            }
        });
    }

    @Override
    public void sync() {
        toggle.setOn(value.get(), false);
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        toggle.setEnabled(on);
        row.setEnabled(on);
    }

    /** A stacked row's toggle sits at the right of its label line, tall enough to keep it off the description. */
    @Override
    protected float stackedReserve() {
        return Toggle.W + 16f * k;
    }

    @Override
    protected float stackedLabelHeight() {
        return Toggle.H + 12f;
    }

    @Override
    protected void onPlace() {
        toggle.setCenter(controlX + controlW - Toggle.W * 0.5f, lineCy());
        row.setBounds(x, y, w, h);
    }

    @Override
    public void update(float dt) {
        toggle.update(dt);
        row.update(dt);
    }

    @Override
    public void drawShapes(Batch b, float alpha) {
        if (row.pressAmount() > 0f || row.hoverAmount() > 0f) {
            draw.roundRect(x - 16f, y + 4f, w + 32f, h - 8f, 16f, Theme.INK1,
                (0.06f * row.pressAmount() + 0.035f * row.hoverAmount()) * alpha);
        }
        row.drawPress(Theme.INK1, alpha * 0.6f);
        super.drawShapes(b, alpha);
    }

    @Override
    protected void drawControl(Batch b, float alpha) {
        toggle.draw(b, alpha);
    }

    @Override
    public boolean touchDown(float px, float py, int pointer) {
        if (!shownInList) return false;
        if (toggle.touchDown(px, py, pointer)) return true;
        return inside(px, py) && row.touchDown(px, py, pointer);
    }

    @Override
    public boolean touchDragged(float px, float py, int pointer) {
        return toggle.touchDragged(px, py, pointer) | row.touchDragged(px, py, pointer);
    }

    @Override
    public boolean touchUp(float px, float py, int pointer) {
        return toggle.touchUp(px, py, pointer) | row.touchUp(px, py, pointer);
    }

    @Override
    public void cancel() {
        toggle.cancel();
        row.cancel();
    }

    @Override
    public void mouseMoved(float px, float py) {
        toggle.mouseMoved(px, py);
        row.mouseMoved(px, py);
    }

    /** Current toggle state. */
    public boolean isOn() {
        return toggle.isOn();
    }
}
