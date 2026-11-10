package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Slider;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 设置行：滑块加右侧的数值（如 {@code 80%}、{@code +12 ms}；上下排的行数值在标题那行右边，滑块占满下面一行）。
 * 可加吸附点（松手时离得近就吸过去）和第二行微调按钮（如谱面延时的 –10 / –1 / +1 / +10），
 * 第二行左边还能放一个按钮（谱面延时下面的「校准…」）；拖动时实时写入，松手时再提交一次。
 *
 * <p>A setting row with a {@link Slider} (DESIGN §9.1–§9.2) and a fixed-cell value readout to its
 * right ({@code 80%}, {@code +12 ms}). Options:
 * <ul>
 *   <li><b>detents</b>: values the knob snaps to when released within 3 % of the range (glitch
 *       intensity 0 / 35 / 60 / 100 %), drawn as ticks under the track, with a light haptic;</li>
 *   <li><b>nudges</b>: a second line of step buttons ({@code [–10][–1][+1][+10]}, audio offset);</li>
 *   <li><b>lead</b>: one more button at the left of that line, under the label (CALIBRATE… under
 *       CHART OFFSET, Kelvin 2026-10-18 02:27);</li>
 *   <li>values are written through a {@link Value} binding: live while dragging
 *       ({@code finished = false}) and once more on release or cancel ({@code finished = true}),
 *       which is where expensive work (save marks, {@code applySettings}) belongs.</li>
 * </ul>
 */
public class SliderRow extends SettingRow {
    /** Binding between the slider and the stored setting. */
    public interface Value {
        /** @return the stored value (slider units). */
        float get();

        /** Stores {@code v}; {@code finished} is true on release or after a nudge. */
        void set(float v, boolean finished);
    }

    private static final float VALUE_W = 112f;
    /** Widest lead button at uiScale 1. */
    private static final float LEAD_W = 320f;

    /** The slider. */
    protected final Slider slider;
    private final Value value;
    private final float min, max;
    private final float displayScale;
    private final boolean signed, percent;
    private final CharSequence unit;
    private float[] detents;
    private Button[] nudges;
    private float[] steps;
    private Pressable lead;
    private final StringBuilder valueText = new StringBuilder(16);
    private final StringBuilder scratch = new StringBuilder(16);
    private float textFor = Float.NaN;
    private float reserve, reserveK = Float.NaN;
    private int nudgeDown = -1;

    /**
     * @param min          slider minimum (setting units)
     * @param max          slider maximum
     * @param step         snap step (0 = continuous)
     * @param displayScale value × scale is shown (100 for percentages)
     * @param percent      append {@code %}
     * @param signed       show a {@code +} on positive values
     * @param unit         unit drawn after the number (e.g. {@code ms}), or null
     */
    public SliderRow(Main game, CharSequence label, CharSequence desc, boolean device, float min, float max,
                     float step, float displayScale, boolean percent, boolean signed, CharSequence unit,
                     Value value) {
        super(game, label, desc, device);
        this.min = min;
        this.max = max;
        this.displayScale = displayScale;
        this.percent = percent;
        this.signed = signed;
        this.unit = unit;
        this.value = value;
        slider = new Slider(draw, fonts, min, max, step);
        slider.setDisplay(displayScale, 0, percent ? "%" : "");
        slider.setListener(new Slider.Listener() {
            @Override
            public void changed(Slider s, float v, boolean finished) {
                onSlider(v, finished);
            }
        });
    }

    /** Adds detents (setting units) the knob snaps to on release when within 3 % of the range. */
    public SliderRow setDetents(float... values) {
        detents = values;
        return this;
    }

    /** Adds a second line of step buttons (setting units, e.g. −10, −1, +1, +10) with their labels. */
    public SliderRow setNudges(float[] stepValues, CharSequence[] labels) {
        steps = stepValues;
        nudges = new Button[stepValues.length];
        for (int i = 0; i < nudges.length; i++) {
            final int index = i;
            nudges[i] = new Button(draw, fonts, Button.Variant.SECONDARY, labels[i]);
            nudges[i].setLabelCap(22f);
            nudges[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    nudge(index);
                }
            });
        }
        return this;
    }

    /** Puts {@code action} at the left of the nudge line (needs {@link #setNudges}); returns it. */
    public <T extends Pressable> T setLead(T action) {
        lead = action;
        if (action instanceof Button) ((Button) action).setLabelCap(22f);
        return action;
    }

    @Override
    public float naturalHeight() {
        return nudges != null ? ROW_H + textExtra() + 96f : super.naturalHeight();
    }

    @Override
    protected float stackedControlHeight() {
        return 56f;
    }

    /** A stacked row shows its value at the right of the label line: room for the widest value it can show. */
    @Override
    protected float stackedReserve() {
        if (reserveK != k) {
            reserveK = k;
            format(scratch, min);
            float w = fonts.fixedWidth(Fonts.Role.NUMBER, 24f * k, scratch);
            format(scratch, max);
            w = Math.max(w, fonts.fixedWidth(Fonts.Role.NUMBER, 24f * k, scratch));
            if (unit != null) w += fonts.width(Fonts.Role.UI_REGULAR, 18f * k, unit) + 8f * k;
            reserve = Math.min(VALUE_W * k, w) + 20f * k;
        }
        return reserve;
    }

    /** Track length: the control column less the value cell, or the whole row when stacked. */
    private float trackW() {
        float rest = stacked ? controlW : controlW - VALUE_W * k - 12f * k;
        return Math.max(40f, rest - Slider.KNOB);
    }

    @Override
    protected float minHeight() {
        return nudges != null ? 2f * Ui.touchTarget() : Ui.touchTarget();
    }

    @Override
    public void setEnabled(boolean on) {
        super.setEnabled(on);
        slider.setEnabled(on);
        if (nudges != null) for (Button n : nudges) n.setEnabled(on);
        if (lead != null) lead.setEnabled(on);
    }

    @Override
    public void sync() {
        slider.setValue(value.get());
        refreshText(slider.value());
    }

    private void onSlider(float v, boolean finished) {
        if (finished && detents != null) {
            float range = max - min;
            for (float d : detents) {
                if (Math.abs(v - d) <= 0.03f * range) {
                    if (v != d) Haptics.light();
                    v = d;
                    slider.setValue(d);
                    break;
                }
            }
        }
        value.set(v, finished);
        refreshText(v);
    }

    private void nudge(int i) {
        float v = Math.max(min, Math.min(max, value.get() + steps[i]));
        slider.setValue(v);
        value.set(slider.value(), true);
        refreshText(slider.value());
    }

    private void refreshText(float v) {
        if (v == textFor) return;
        textFor = v;
        format(valueText, v);
    }

    private void format(StringBuilder sb, float v) {
        sb.setLength(0);
        int n = Math.round(v * displayScale);
        if (signed && n > 0) sb.append('+');
        if (n < 0) {
            sb.append('-');
            n = -n;
        }
        Fonts.appendInt(sb, n);
        if (percent) sb.append('%');
    }

    // ------------------------------------------------------------------ layout / frame
    @Override
    protected void onPlace() {
        slider.setBounds(controlX + Slider.KNOB * 0.5f, controlCy(), trackW());
        if (nudges != null) {
            float gap = 12f * k;
            int n = nudges.length;
            float bw = (controlW - (n - 1) * gap) / n;
            float bh = 64f * k;
            float ny = y + (h - lineHeight()) * 0.5f - bh * 0.5f;
            for (int i = 0; i < n; i++) nudges[i].setBounds(controlX + i * (bw + gap), ny, bw, bh);
            if (lead != null) lead.setBounds(x, ny, Math.min(LEAD_W * k, controlX - GAP - x), bh);
        }
    }

    @Override
    public void update(float dt) {
        slider.update(dt);
        if (nudges != null) for (Button n : nudges) n.update(dt);
        if (lead != null) lead.update(dt);
        if (!slider.isGrabbed()) refreshText(slider.value());
    }

    @Override
    protected void drawControl(Batch b, float alpha) {
        float a = alpha * (enabled ? 1f : 0.4f);
        if (detents != null) {
            float x0 = controlX + Slider.KNOB * 0.5f;
            float tw = trackW(), ty = controlCy() - 20f;
            for (float d : detents) {
                float tx = x0 + tw * (d - min) / (max - min);
                draw.rect(tx - 1f, ty, 2f, 9f, Theme.INK2, 0.6f * a);
            }
        }
        if (nudges != null) for (Button n : nudges) n.draw(b, alpha);
        if (lead != null) lead.draw(b, alpha);
        drawValue(b, lineCy(), a);
        slider.draw(b, alpha);
    }

    private void drawValue(Batch b, float cy, float a) {
        float right = controlX + controlW;
        Color c = enabled ? Theme.INK1 : Theme.INK3;
        if (unit != null) {
            float uw = fonts.draw(b, Fonts.Role.UI_REGULAR, 18f * k, unit, right, cy, Align.right | Fonts.VCENTER,
                Theme.INK2, a);
            right -= uw + 8f * k;
        }
        fonts.drawFixed(b, Fonts.Role.NUMBER, 24f * k, valueText, right, cy, Align.right | Fonts.VCENTER, c, a);
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean touchDown(float px, float py, int pointer) {
        if (!shownInList) return false;
        if (nudges != null) {
            for (int i = 0; i < nudges.length; i++) {
                if (nudges[i].touchDown(px, py, pointer)) {
                    nudgeDown = pointer;
                    return true;
                }
            }
        }
        if (lead != null && lead.touchDown(px, py, pointer)) return true;
        return slider.touchDown(px, py, pointer);
    }

    @Override
    public boolean touchDragged(float px, float py, int pointer) {
        boolean any = slider.touchDragged(px, py, pointer);
        if (nudges != null) for (Button n : nudges) any |= n.touchDragged(px, py, pointer);
        if (lead != null) any |= lead.touchDragged(px, py, pointer);
        return any;
    }

    @Override
    public boolean touchUp(float px, float py, int pointer) {
        boolean any = slider.touchUp(px, py, pointer);
        if (nudges != null) for (Button n : nudges) any |= n.touchUp(px, py, pointer);
        if (lead != null) any |= lead.touchUp(px, py, pointer);
        if (pointer == nudgeDown) nudgeDown = -1;
        return any;
    }

    /**
     * Cancels the row's pointers. A drag that ends in a cancel (the OS took the touch, a modal
     * opened, BACK during the drag) has already written its value live, so it is committed like a
     * release ({@code finished = true}: detents, save marks, {@code applySettings}); the stored
     * value then always matches the knob.
     */
    @Override
    public void cancel() {
        boolean grabbed = slider.isGrabbed();
        slider.cancel();
        if (grabbed) onSlider(slider.value(), true);
        if (nudges != null) for (Button n : nudges) n.cancel();
        if (lead != null) lead.cancel();
        nudgeDown = -1;
    }

    @Override
    public void mouseMoved(float px, float py) {
        if (nudges != null) for (Button n : nudges) n.mouseMoved(px, py);
        if (lead != null) lead.mouseMoved(px, py);
    }

    @Override
    public boolean isGrabbing() {
        return slider.isGrabbed();
    }

    /** The slider's current (possibly mid-drag) value. */
    public float sliderValue() {
        return slider.value();
    }
}
