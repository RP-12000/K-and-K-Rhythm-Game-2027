package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Segmented;

/**
 * 设置行：右侧是分段选择器（如 {@code OFF | LOW | HIGH}），选中的高亮块会弹性滑到所选的一段；上下排的行选择器在说明下面占满一行。
 *
 * <p>A setting row with a {@link Segmented} control filling the control column (DESIGN §9.1), for
 * options such as {@code OFF | LOW | HIGH}. The selection pill slides on a snappy spring.
 */
public class ChoiceRow extends SettingRow {
    /** Binding between the control and the stored setting. */
    public interface Value {
        /** @return the stored option index. */
        int get();

        /** Stores option {@code index} (called once per change). */
        void set(int index);
    }

    private final Segmented control;
    private final Value value;
    private final int options;

    /** Creates the row over {@code options} (the array is kept). */
    public ChoiceRow(Main game, CharSequence label, CharSequence desc, boolean device, CharSequence[] options,
                     final Value value) {
        super(game, label, desc, device);
        this.value = value;
        this.options = options.length;
        control = new Segmented(draw, fonts, options);
        control.setListener(new Segmented.Listener() {
            @Override
            public void selected(Segmented c, int index) {
                value.set(index);
            }
        });
    }

    @Override
    public void sync() {
        control.select(value.get(), false);
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        control.setEnabled(on);
    }

    @Override
    protected float stackedControlHeight() {
        return 72f;
    }

    @Override
    protected void onPlace() {
        widenControl();
        float ch = 60f * k;
        control.setBounds(controlX, controlCy() - ch * 0.5f, controlW, ch);
        control.setLabelCap((options > 4 ? 17f : 19f) * k);
    }

    @Override
    public void update(float dt) {
        control.update(dt);
    }

    @Override
    protected void drawControl(Batch b, float alpha) {
        control.draw(b, alpha);
    }

    @Override
    public boolean touchDown(float px, float py, int pointer) {
        return shownInList && control.touchDown(px, py, pointer);
    }

    @Override
    public boolean touchDragged(float px, float py, int pointer) {
        return control.touchDragged(px, py, pointer);
    }

    @Override
    public boolean touchUp(float px, float py, int pointer) {
        return control.touchUp(px, py, pointer);
    }

    @Override
    public void cancel() {
        control.cancel();
    }

    @Override
    public void mouseMoved(float px, float py) {
        control.mouseMoved(px, py);
    }

    /** Selected option index. */
    public int selected() {
        return control.selected();
    }
}
