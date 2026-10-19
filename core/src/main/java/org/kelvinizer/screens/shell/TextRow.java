package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 设置列表里的一大段纯文字（许可证全文）：按原文换行逐行显示，只画滚动到视野里的行，不接收输入。
 *
 * <p>A block of plain text in the Settings list (a licence text, Kelvin 2026-10-18 00:24): one line
 * per source line (the licence files are already wrapped near 80 columns; a longer line is fitted
 * to the width), UI_REGULAR 18 {@code ink2} on a 28-unit pitch, × uiScale. Only the lines inside
 * the list's visible rect are drawn. Never takes input; nothing is allocated per frame.
 */
public final class TextRow extends SettingRow {
    private static final float LINE_H = 28f, PAD = 16f;

    private final String[] lines;
    private final ScrollArea scroll;

    /** Creates the block; {@code scroll} is the list it sits in (for culling). */
    public TextRow(Main game, ScrollArea scroll, String[] lines) {
        super(game, "", null, false);
        this.scroll = scroll;
        this.lines = lines;
    }

    @Override
    public float naturalHeight() {
        return lines.length * LINE_H + 2f * PAD;
    }

    @Override
    protected float minHeight() {
        return 0f;
    }

    @Override
    public void drawShapes(Batch b, float alpha) {
    }

    @Override
    public void drawLabels(Batch b, float alpha) {
        float lh = LINE_H * k;
        float top = y + h - PAD * k;
        // first and last line inside the visible rect
        int first = Math.max(0, (int) ((top - scroll.top()) / lh) - 1);
        for (int i = first; i < lines.length; i++) {
            float cy = top - (i + 0.5f) * lh;
            if (cy + lh < scroll.y()) break;
            if (cy - lh > scroll.top() || lines[i].isEmpty()) continue;
            fonts.drawFit(b, Fonts.Role.UI_REGULAR, 18f * k, lines[i], x, cy, Align.left | Fonts.VCENTER, w, 0.6f,
                Theme.INK2, alpha);
        }
    }
}
