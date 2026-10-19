package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.Main;
import org.kelvinizer.gameplay.NoteGuide;
import org.kelvinizer.ui.Lang;

import java.util.function.BooleanSupplier;

/**
 * 设置 → GAMEPLAY 末尾的「音符图鉴」行：把 {@link NoteGuide} 面板放进设置列表。
 * 只显示，不接收输入；在面板上拖动会滚动整页。2026-10-18 05:14 起设置里不再放这一行（Kelvin：「把音符图鉴那一部分删了」），
 * 代码先留着，以后要放到别处时再用。
 *
 * <p>The NOTE GUIDE row at the end of Settings → GAMEPLAY (owner 2026-10-12, mock-up P13 v2): it
 * hosts the {@link NoteGuide} panel in the page's list. Its height is the panel's, which depends on
 * the list width (read from the page's {@link ScrollArea}, which {@link SettingsPage#layout} sets
 * before any row is measured, so it is right on the first frame after a resize), the uiScale and
 * the language (asked every time the page measures the row, so choosing EN or 中文 in the
 * LANGUAGE row above relays it out on the next frame). Display only: every touch method keeps
 * {@link SettingRow}'s {@code false}, so a drag that starts on the panel scrolls the page. It draws
 * no row hairline; the panel has its own outline. Nothing is allocated per frame.
 *
 * <p>No page adds this row since 2026-10-18 05:14 (Kelvin: 「把音符图鉴那一部分删了」); it is kept so
 * the guide can move somewhere else later.
 */
public final class NoteGuideRow extends SettingRow {
    private final NoteGuide guide;
    private final ScrollArea list;
    private final BooleanSupplier zh;

    /**
     * Creates the row for the page whose list is {@code list}; {@code zh} answers whether the
     * active diver reads 中文.
     */
    public NoteGuideRow(Main game, ScrollArea list, BooleanSupplier zh) {
        super(game, Lang.of("NOTE GUIDE", "音符图鉴"), null, false);
        this.list = list;
        this.zh = zh;
        guide = new NoteGuide(draw, fonts);
    }

    /** The panel. */
    public NoteGuide guide() {
        return guide;
    }

    @Override
    protected float minHeight() {
        return 0f;
    }

    @Override
    public float height(float k) {
        return guide.layout(list.width(), k, zh.getAsBoolean());
    }

    @Override
    public void drawShapes(Batch b, float alpha) {
        guide.drawShapes(x, y + h, alpha);
    }

    @Override
    public void drawLabels(Batch b, float alpha) {
        guide.drawText(b, x, y + h, alpha);
    }
}
