package org.kelvinizer.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;

/**
 * 分段选择控件（如 OFF | LOW | HIGH）：胶囊形容器里，选中块随弹簧在等宽的分段之间滑动。
 * 在某一段上抬起手指才选中它，按下时有波纹和变亮；选中块为青色，上面的文字为深色。
 *
 * <p>Segmented control (DESIGN §1.6): a pill container whose selection pill slides between equal
 * segments on a snappy spring. A segment is selected on release inside it (with slop); the
 * pressed segment shows the §1.5 ripple and brightening. The hit height is at least {@code P}.
 * Labels are UI_LABEL; the selected label is drawn in {@code #04142B} on {@code groundSig}.
 */
public class Segmented {
    /** Selection listener. */
    public interface Listener {
        /** Called after a tap changed the selection. */
        void selected(Segmented control, int index);
    }

    private final Draw draw;
    private final Fonts fonts;
    private final CharSequence[] options;
    private float x, y, w = 480f, h = 64f;
    private float labelCap = 18f;
    private int selected, pressedIndex = -1, pointer = -1, hoverIndex = -1;
    private boolean enabled = true, visible = true;
    private float press;
    private Listener listener;
    private final Spring pos = new Spring(Spring.Preset.SNAPPY, 0f);
    private final Spring squash = new Spring(Spring.Preset.SNAPPY, 1f);
    private final Ripple ripple = new Ripple();
    private final Color tmp = new Color(), tmp2 = new Color();

    /** Creates a control over {@code options} (the array is kept; do not mutate it). */
    public Segmented(Draw draw, Fonts fonts, CharSequence[] options) {
        this.draw = draw;
        this.fonts = fonts;
        this.options = options;
    }

    /** Sets the container rect (bottom-left origin). */
    public Segmented setBounds(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        return this;
    }

    /** Label cap height (default 18). */
    public Segmented setLabelCap(float cap) {
        labelCap = cap;
        return this;
    }

    /** Sets the listener. */
    public Segmented setListener(Listener l) {
        listener = l;
        return this;
    }

    /** Selects {@code index}; {@code animate = false} snaps the pill. No callback. */
    public Segmented select(int index, boolean animate) {
        selected = MathUtils.clamp(index, 0, options.length - 1);
        if (animate) pos.setTarget(selected);
        else pos.snap(selected);
        return this;
    }

    /** Selected index. */
    public int selected() {
        return selected;
    }

    /** Enables or disables input. */
    public Segmented setEnabled(boolean on) {
        enabled = on;
        if (!on) cancel();
        return this;
    }

    /** Shows or hides the control. */
    public Segmented setVisible(boolean on) {
        visible = on;
        if (!on) cancel();
        return this;
    }

    private float segW() {
        return w / Math.max(1, options.length);
    }

    private int indexAt(float px, float py, float grow) {
        float hh = Math.max(h, Ui.touchTarget()) * 0.5f + grow;
        if (Math.abs(py - (y + h * 0.5f)) > hh || px < x - grow || px > x + w + grow) return -1;
        return MathUtils.clamp((int) ((px - x) / segW()), 0, options.length - 1);
    }

    /** Touch down. */
    public boolean touchDown(float px, float py, int ptr) {
        if (!visible || !enabled || pointer >= 0) return false;
        int i = indexAt(px, py, 0f);
        if (i < 0) return false;
        pointer = ptr;
        pressedIndex = i;
        squash.setPreset(Spring.Preset.SNAPPY).setTarget(Pressable.PRESS_SCALE);
        ripple.color(i == selected ? Theme.WHITE : Theme.INK1).start(px, py, segW(), h);
        Haptics.light();
        return true;
    }

    /** Drag; cancels beyond slop of the pressed segment. */
    public boolean touchDragged(float px, float py, int ptr) {
        if (pointer != ptr) return false;
        if (indexAt(px, py, Ui.slop()) < 0) cancel();
        return true;
    }

    /** Touch up; selects the pressed segment when released over it. */
    public boolean touchUp(float px, float py, int ptr) {
        if (pointer != ptr) return false;
        pointer = -1;
        squash.setPreset(Spring.Preset.BOUNCY).setTarget(1f);
        int i = indexAt(px, py, Ui.slop());
        float sw = segW();
        boolean overPressed = i >= 0 && px >= x + pressedIndex * sw - Ui.slop()
            && px <= x + (pressedIndex + 1) * sw + Ui.slop();
        if (overPressed && pressedIndex != selected) {
            selected = pressedIndex;
            pos.setTarget(selected);
            if (listener != null) listener.selected(this, selected);
        }
        pressedIndex = -1;
        return true;
    }

    /** Mouse hover. */
    public boolean mouseMoved(float px, float py) {
        hoverIndex = visible && enabled ? indexAt(px, py, 0f) : -1;
        if (hoverIndex >= 0 && (py < y || py > y + h)) hoverIndex = -1;
        return hoverIndex >= 0;
    }

    /** Cancels a press. */
    public void cancel() {
        if (pointer >= 0) {
            pointer = -1;
            pressedIndex = -1;
            squash.setPreset(Spring.Preset.BOUNCY).setTarget(1f);
        }
    }

    /** Advances the spring and ripple. */
    public void update(float dt) {
        pos.update(dt);
        squash.update(dt);
        ripple.update(dt);
        press = pointer >= 0 ? Math.min(1f, press + dt / Anim.MICRO) : Math.max(0f, press - dt / Anim.MICRO);
    }

    /** Draws the control; the batch must be drawing. */
    public void draw(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * (enabled ? 1f : 0.4f);
        float r = h * 0.5f;
        draw.roundRect(x, y, w, h, r, Theme.PANEL, a);
        draw.roundRectStroke(x, y, w, h, r, 1f, Theme.STROKE, a);
        float sw = segW();
        float inset = 5f;
        if (hoverIndex >= 0 && hoverIndex != selected && pointer < 0) {
            draw.roundRect(x + hoverIndex * sw + inset, y + inset, sw - 2f * inset, h - 2f * inset, r - inset,
                Theme.INK1, 0.05f * a);
        }
        // selection pill (slides; squashes slightly while its own segment is held)
        float p = pos.value();
        float sq = pressedIndex == selected ? squash.value() : 1f;
        float pw = (sw - 2f * inset) * sq, ph = (h - 2f * inset) * (0.5f + 0.5f * sq);
        float pcx = x + (p + 0.5f) * sw, pcy = y + h * 0.5f;
        Theme.brighten(Theme.GROUND_SIG, 1f + 0.12f * (pressedIndex == selected ? press : 0f), tmp);
        draw.glowDot(pcx, pcy, pw + 60f, Theme.GROUND_SIG, 0.12f * a);
        draw.roundRect(pcx - pw * 0.5f, pcy - ph * 0.5f, pw, ph, ph * 0.5f, tmp, a);
        if (pressedIndex >= 0 && pressedIndex != selected) {
            draw.roundRect(x + pressedIndex * sw + inset, y + inset, sw - 2f * inset, h - 2f * inset, r - inset,
                Theme.INK1, 0.08f * press * a);
        }
        ripple.draw(draw, a);
        for (int i = 0; i < options.length; i++) {
            float lx = x + (i + 0.5f) * sw;
            // label colour follows the pill's proximity so the swap is smooth
            float near = MathUtils.clamp(1f - Math.abs(p - i), 0f, 1f);
            Theme.mix(Theme.INK2, Theme.BTN_LABEL, near, tmp2);
            float s = (i == pressedIndex) ? squash.value() : 1f;
            // inside the pill with about 2/3 of a cap of air per side; shrink to 60 % before an ellipsis
            fonts.drawScaled(b, Fonts.Role.UI_LABEL, labelCap, s, options[i], lx, y + h * 0.5f, Fonts.VCENTER,
                sw - 2f * inset - 1.3f * labelCap, 0.6f, tmp2, a);
        }
    }
}
