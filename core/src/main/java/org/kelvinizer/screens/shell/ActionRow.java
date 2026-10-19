package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Pressable;

/**
 * 设置行：右侧放一到两个按钮，例如 TIMING 页的 CALIBRATE…，以及 ACCOUNT 页的导出 / 导入备份码和按住重置记录。
 *
 * <p>A setting row with one or two action buttons right-aligned in the control column (DESIGN §9.2
 * TIMING → CALIBRATE…, ACCOUNT). Buttons are 72 tall visually (hit ≥ {@code P}); a single button
 * is at most 380 wide, two share the column.
 */
public class ActionRow extends SettingRow {
    private final Pressable[] actions = new Pressable[2];
    private int count;
    private float buttonW = 380f;

    /** Creates a row without buttons; add them with {@link #add}. */
    public ActionRow(Main game, CharSequence label, CharSequence desc, boolean device) {
        super(game, label, desc, device);
    }

    /** Adds a button (at most two); returns it. */
    public <T extends Pressable> T add(T action) {
        if (count < actions.length) actions[count++] = action;
        if (action instanceof Button) ((Button) action).setLabelCap(22f);
        return action;
    }

    /** Maximum width of a single button (default 380 × uiScale). */
    public ActionRow setButtonWidth(float width) {
        buttonW = width;
        return this;
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        for (int i = 0; i < count; i++) actions[i].setEnabled(on);
    }

    @Override
    protected void onPlace() {
        if (count == 0) return;
        if (count == 2) widenControl();
        float bh = 72f * k;
        float cy = lineCy();
        if (count == 1) {
            float bw = Math.min(controlW, buttonW * k);
            actions[0].setBounds(controlX + controlW - bw, cy - bh * 0.5f, bw, bh);
        } else {
            float gap = 16f * k;
            float bw = (controlW - gap) * 0.5f;
            actions[0].setBounds(controlX, cy - bh * 0.5f, bw, bh);
            actions[1].setBounds(controlX + bw + gap, cy - bh * 0.5f, bw, bh);
        }
    }

    @Override
    public void update(float dt) {
        for (int i = 0; i < count; i++) actions[i].update(dt);
    }

    @Override
    protected void drawControl(Batch b, float alpha) {
        for (int i = 0; i < count; i++) actions[i].draw(b, alpha);
    }

    @Override
    public boolean touchDown(float px, float py, int pointer) {
        if (!shownInList) return false;
        for (int i = 0; i < count; i++) if (actions[i].touchDown(px, py, pointer)) return true;
        return false;
    }

    @Override
    public boolean touchDragged(float px, float py, int pointer) {
        boolean any = false;
        for (int i = 0; i < count; i++) any |= actions[i].touchDragged(px, py, pointer);
        return any;
    }

    @Override
    public boolean touchUp(float px, float py, int pointer) {
        boolean any = false;
        for (int i = 0; i < count; i++) any |= actions[i].touchUp(px, py, pointer);
        return any;
    }

    @Override
    public void cancel() {
        for (int i = 0; i < count; i++) actions[i].cancel();
    }

    @Override
    public void mouseMoved(float px, float py) {
        for (int i = 0; i < count; i++) actions[i].mouseMoved(px, py);
    }

    /** True while a hold-to-confirm button of this row is held (the list must not scroll that pointer). */
    @Override
    public boolean isGrabbing() {
        for (int i = 0; i < count; i++) {
            Pressable a = actions[i];
            if (a.isPressed() && a instanceof Button && ((Button) a).holdsToConfirm()) return true;
        }
        return false;
    }
}
