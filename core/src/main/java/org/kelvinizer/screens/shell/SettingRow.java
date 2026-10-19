package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 设置列表中一行的基类：左边是标题和说明（说明最多三行，有第三行时这一行高出一行），存在本机、所有玩家共用的设置
 * 还带「公用」小标签（2026-10-18 04:48 前叫「本机」）；右边一列放控件，由子类摆放和绘制。「游戏界面」画面的两列用
 * 上下排的样式：标题一行、说明一行、滑块或分段选择器再占一行（数值和开关在标题那行右边）。
 *
 * <p>One row of the Settings list (DESIGN §9.1): 104 tall (× uiScale, never below the touch target
 * {@code P}; a line taller with a third description line, CHART OFFSET since Kelvin 2026-10-18 19:52); on the left
 * the label (UI 24), a description of one to three lines (UI_REGULAR 18 {@code ink3}) and a
 * small {@code SHARED} / {@code 公用} tag (UI_LABEL 16) on device-scoped rows, the settings every
 * diver on the device shares ({@code DEVICE} / {@code 本机} until 2026-10-18 04:48); on the right
 * the control in a column of width {@code controlW}. Subclasses own their control, place it in
 * {@link #onPlace()} (called every frame, so it follows scrolling and the entrance slide) and draw
 * it in {@link #drawControl(Batch, float)}.
 *
 * <p>{@link #setStacked Stacked} rows (the GAMEPLAY VISUALS columns, Kelvin 2026-10-18 15:53) put the label line on
 * top (a slider's value or a toggle at its right), the description under it and a slider or segmented control
 * across the whole row below; the block sits in the middle of the row when the list gives it extra height.
 *
 * <p>{@link #sync()} re-reads the stored value into the control (on show, after a diver switch or
 * when another setting changed what this row shows). Labels and descriptions are
 * {@link CharSequence}s so a row can show a reused {@link StringBuilder}. Nothing is allocated
 * per frame.
 */
public abstract class SettingRow {
    /** Row height at uiScale 1 (DESIGN §9.1). */
    public static final float ROW_H = 104f;
    /** Gap between the text column and the control column. */
    public static final float GAP = 32f;
    /** Tag of device-scoped rows (Kelvin 2026-10-18 04:48: 「那可以标注一下公用」). */
    private static final CharSequence SHARED = Lang.of("SHARED", "公用");
    /** Stacked rows at uiScale 1: padding above and below the block, label line, description line. */
    private static final float STACK_PAD = 16f, STACK_LABEL = 40f, STACK_DESC = 28f;
    /** Space between two description lines at uiScale 1. */
    private static final float DESC_STEP = 27f;

    /** Service hub. */
    protected final Main game;
    /** Shared drawing. */
    protected final Draw draw;
    /** Shared fonts. */
    protected final Fonts fonts;
    /** Label (UI 24). */
    protected CharSequence label;
    /** Description (UI_REGULAR 18), or null. */
    protected CharSequence desc;
    /** Second description line under {@link #desc}, or null (the CHART OFFSET hints, Kelvin 2026-10-18 14:29). */
    protected CharSequence desc2;
    /** Third description line under {@link #desc2}, or null (CHART OFFSET since Kelvin 2026-10-18 19:52). */
    protected CharSequence desc3;
    /** Description colour (default {@code ink3}). */
    protected Color descColor = Theme.INK3;
    /** Device-scoped row (draws the SHARED tag). */
    protected final boolean device;
    /** Whether the control accepts input (disabled rows draw at α .40). */
    protected boolean enabled = true;
    /** Current rect, uiScale and control column (set by {@link #place}). */
    protected float x, y, w, h, k, controlX, controlW;
    /** Whether the row is at least partly visible in its list (hidden rows ignore input). */
    protected boolean shownInList = true;
    /** Stacked layout: label line, description, then the control across the row (see the class comment). */
    protected boolean stacked;

    /** Creates a row; {@code device} marks it device-scoped. */
    protected SettingRow(Main game, CharSequence label, CharSequence desc, boolean device) {
        this.game = game;
        this.draw = game.draw();
        this.fonts = game.fonts();
        this.label = label;
        this.desc = desc;
        this.device = device;
    }

    // ------------------------------------------------------------------ geometry
    /**
     * Height at uiScale 1: {@link #ROW_H} (a line more with a third description line), or the stacked block and its
     * padding.
     */
    public float naturalHeight() {
        return stacked ? stackedBlock() + 2f * STACK_PAD : ROW_H + textExtra();
    }

    /** Height the first line gains at uiScale 1 for a third description line (four lines of text). */
    protected float textExtra() {
        return !stacked && desc2 != null && desc2.length() > 0 && desc3 != null && desc3.length() > 0 ? DESC_STEP : 0f;
    }

    /** Lays the row out stacked (see the class comment); returns this row. */
    public SettingRow setStacked(boolean on) {
        stacked = on;
        return this;
    }

    /** Height of a stacked row's control line at uiScale 1 (0 = the control sits on the label line). */
    protected float stackedControlHeight() {
        return 0f;
    }

    /** Width a stacked row keeps free at the right of its label line (a slider's value, a toggle). */
    protected float stackedReserve() {
        return 0f;
    }

    /** Height of a stacked row's label line at uiScale 1 (a toggle needs more than the label). */
    protected float stackedLabelHeight() {
        return STACK_LABEL;
    }

    private float stackedBlock() {
        return stackedLabelHeight() + (desc != null && desc.length() > 0 ? STACK_DESC : 0f) + stackedControlHeight();
    }

    /** Top of a stacked row's block, centred in the row. */
    private float stackedTop() {
        return y + (h + stackedBlock() * k) * 0.5f;
    }

    /** Smallest allowed height (default one touch target, so neighbouring hit rects never overlap). */
    protected float minHeight() {
        return Ui.touchTarget();
    }

    /** Height at uiScale {@code k}. */
    public float height(float k) {
        return Math.max(naturalHeight() * k, minHeight());
    }

    /**
     * True for a row that takes the height its list has left over (at most one per page; the K &amp; K row that
     * ends OTHER, Kelvin 2026-10-18 05:58), so the page fits without scrolling; see {@link #fillHeight}.
     */
    public boolean fills() {
        return false;
    }

    /** Height of a {@link #fills() filling} row at uiScale {@code k}, given the list's {@code spare} height. */
    public float fillHeight(float spare, float k) {
        return height(k);
    }

    /** Height of the first line (label + main control) inside the row. */
    protected float lineHeight() {
        return Math.min(h, Math.max((ROW_H + textExtra()) * k, Ui.touchTarget()));
    }

    /** Centre y of the first line (a stacked row's label line). */
    protected float lineCy() {
        if (stacked) return stackedTop() - stackedLabelHeight() * 0.5f * k;
        return y + h - lineHeight() * 0.5f;
    }

    /** Centre y of the control: the first line, or a stacked row's control line. */
    protected float controlCy() {
        if (!stacked || stackedControlHeight() <= 0f) return lineCy();
        return stackedTop() - (stackedBlock() - stackedControlHeight() * 0.5f) * k;
    }

    /**
     * Places the row (bottom-left {@code x, y}, size {@code w × h}) with the control column of width
     * {@code controlW} at its right edge (a stacked row's control spans the row); called every frame by the list.
     */
    public void place(float x, float y, float w, float h, float controlW, float k, boolean visible) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.k = k;
        this.controlW = stacked ? w : controlW;
        this.controlX = x + w - this.controlW;
        this.shownInList = visible;
        onPlace();
    }

    /** Positions the control for the current rect. */
    protected void onPlace() {
    }

    /**
     * Widens the control column to {@code min(620 × k, half the row)} when that is wider than the
     * list's column (segmented controls and button pairs on wide pages); the text column shrinks
     * accordingly. Call at the start of {@link #onPlace()}.
     */
    protected void widenControl() {
        float wide = Math.min(620f * k, w * 0.5f);
        if (wide > controlW) {
            controlW = wide;
            controlX = x + w - wide;
        }
    }

    // ------------------------------------------------------------------ state
    /** Re-reads the stored value into the control (no callbacks). */
    public void sync() {
    }

    /** Enables or disables the row's control. */
    public void setEnabled(boolean on) {
        enabled = on;
    }

    /** True when the control is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Replaces the description (and its colour). */
    public void setDescription(CharSequence text, Color color) {
        desc = text;
        descColor = color != null ? color : Theme.INK3;
    }

    /** Sets a second description line, drawn under the first in the same colour (null for none). */
    public void setDescription2(CharSequence text) {
        desc2 = text;
    }

    /** Sets a third description line under the second (null for none); the row grows by a line for it. */
    public void setDescription3(CharSequence text) {
        desc3 = text;
    }

    /** Advances the control. */
    public void update(float dt) {
    }

    // ------------------------------------------------------------------ drawing
    /*
     * Drawing runs in two passes over the whole page (SettingsPage.draw): every row's control and
     * hairline, then every row's text, so the batch switches between the shape atlas and the font
     * pages once per pass instead of once per row. The batch is drawing, clipped to the list.
     */

    /** Shape pass: the control and the bottom hairline. */
    public void drawShapes(Batch b, float alpha) {
        drawControl(b, alpha);
        Panel.divider(draw, x, x + w, y, 0.8f * alpha);
    }

    /** Text pass: label, SHARED tag and description. */
    public void drawLabels(Batch b, float alpha) {
        drawText(b, alpha);
    }

    /**
     * Draws label, SHARED tag and description (one to three lines) in the text column of the first line; a stacked
     * row's description runs under its label line across the whole row (the further lines are not shown).
     */
    protected void drawText(Batch b, float alpha) {
        float a = alpha * (enabled ? 1f : 0.55f);
        float cy = lineCy();
        float maxW = stacked ? w : controlX - GAP - x;
        float labelW = stacked ? w - stackedReserve() : maxW;
        float tagW = device ? fonts.width(Fonts.Role.UI_LABEL, 16f * k, SHARED) + 20f * k : 0f;
        boolean two = desc != null && desc.length() > 0;
        boolean three = !stacked && two && desc2 != null && desc2.length() > 0;
        boolean four = three && textExtra() > 0f;
        float ly = stacked ? cy : four ? cy + 40f * k : three ? cy + 27f * k : two ? cy + 15f * k : cy;
        float lw = fonts.drawFit(b, Fonts.Role.UI, 24f * k, label, x, ly, Align.left | Fonts.VCENTER,
            Math.max(40f, labelW - (device ? tagW + 14f * k : 0f)), 0.75f, Theme.INK1, a);
        if (device) {
            float tx = x + lw + 14f * k, th = 26f * k;
            draw.roundRectStroke(tx, ly - th * 0.5f, tagW, th, th * 0.5f, 1.5f, Theme.INK3, 0.8f * a);
            fonts.draw(b, Fonts.Role.UI_LABEL, 16f * k, SHARED, tx + tagW * 0.5f, ly, Align.center | Fonts.VCENTER,
                Theme.INK2, a);
        }
        float dy = stacked ? cy - (stackedLabelHeight() + STACK_DESC) * 0.5f * k
            : four ? cy + 9f * k : three ? cy - 4f * k : cy - 20f * k;
        if (two) descLine(b, desc, dy, maxW, alpha);
        if (three) descLine(b, desc2, dy - DESC_STEP * k, maxW, alpha);
        if (four) descLine(b, desc3, dy - 2f * DESC_STEP * k, maxW, alpha);
    }

    private void descLine(Batch b, CharSequence text, float cy, float maxW, float alpha) {
        fonts.drawFit(b, Fonts.Role.UI_REGULAR, 18f * k, text, x, cy, Align.left | Fonts.VCENTER, Math.max(40f, maxW),
            0.8f, descColor, alpha * (enabled ? 1f : 0.8f));
    }

    /** Draws the control. */
    protected void drawControl(Batch b, float alpha) {
    }

    // ------------------------------------------------------------------ input
    /** Touch down inside the list; true when the row's control took it. */
    public boolean touchDown(float px, float py, int pointer) {
        return false;
    }

    /** Drag of a pointer this row may own. */
    public boolean touchDragged(float px, float py, int pointer) {
        return false;
    }

    /** Touch up; true when the row owned the pointer. */
    public boolean touchUp(float px, float py, int pointer) {
        return false;
    }

    /** Cancels any press without acting. */
    public void cancel() {
    }

    /** Desktop hover. */
    public void mouseMoved(float px, float py) {
    }

    /** True while a slider of this row is grabbed (the list must not scroll that pointer). */
    public boolean isGrabbing() {
        return false;
    }

    /** True when (px, py) lies inside the row rect. */
    protected boolean inside(float px, float py) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
