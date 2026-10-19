package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 潜水员切换卡片：在欢迎画面从左下角的潜水员标签上方升起，设置 → ACCOUNT 的 SWITCH 也打开它；
 * 每个潜水员一行，当前的标 ACTIVE。
 * 点一行就地切换并关闭卡片；底部有 {@code + NEW DIVER} 和 {@code MANAGE}（去玩家档案）。
 *
 * <p>The diver switcher (DESIGN §7 corners): on Welcome a sheet rising above the profile chip;
 * Settings → ACCOUNT → SWITCH opens it too. It has one
 * row per diver (avatar 72, name, {@code LV · depth}, the active one tagged), {@code + NEW DIVER}
 * and {@code MANAGE} (→ Profile). Selecting a row switches in place through
 * {@link ShellScreen#switchDiver} and closes the sheet. Rows scroll when eight divers do not
 * fit. No allocation per frame.
 */
public final class SwitcherSheet extends Modal {
    private static final CharSequence ACTIVE = Lang.of("ACTIVE", "当前");
    /** Row height (one touch target). */
    public static final float ROW_H = 112f;
    private static final float WIDTH = 700f, HEADER = 92f, FOOTER = 150f;

    private final DiverList divers = new DiverList();
    private final Tappable[] rows = new Tappable[ProfileStore.MAX_PROFILES];
    private final Button add, manage;
    private final ScrollArea list = new ScrollArea();
    private final StringBuilder count = new StringBuilder(16);
    private final Color tmp = new Color();
    private final Runnable onManage;
    private float anchorX, anchorY;

    /**
     * Creates the sheet. {@code onCreate} opens the create sheet, {@code onManage} opens Profile.
     */
    public SwitcherSheet(Main game, ShellScreen host, final Runnable onCreate, Runnable onManage) {
        super(game, host);
        this.onManage = onManage;
        for (int i = 0; i < rows.length; i++) {
            final int index = i;
            rows[i] = widgets.add(new Tappable(draw));
            rows[i].setClip(list);
            rows[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    pick(index);
                }
            });
        }
        add = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("+ NEW DIVER", "+ 新建玩家")));
        add.setLabelCap(22f);
        add.setOnTap(onCreate);
        manage = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("MANAGE", "管理")));
        manage.setLabelCap(22f);
        manage.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
                SwitcherSheet.this.onManage.run();
            }
        });
    }

    /** Sets the bottom-left corner the sheet rises from (the chip's top-left). */
    public void setAnchor(float ax, float ay) {
        anchorX = ax;
        anchorY = ay;
    }

    @Override
    protected void onOpened() {
        divers.invalidate();
        refresh();
        list.reset();
        int a = divers.activeIndex();
        if (a > 0) list.reveal(a * ROW_H, (a + 1) * ROW_H);
    }

    private void refresh() {
        if (!divers.refresh(game.profiles())) return;
        ProfileStore store = game.profiles();
        count.setLength(0);
        count.append(divers.size()).append(" / ").append(ProfileStore.MAX_PROFILES);
        add.setEnabled(store.canCreate());
        for (int i = 0; i < rows.length; i++) rows[i].setVisible(i < divers.size());
    }

    @Override
    protected ScrollArea scroll() {
        return list;
    }

    @Override
    protected float slideDistance() {
        return 90f;
    }

    /** Grows from its anchor (the chip's corner) under the UI scale, so it stays attached to the chip. */
    @Override
    protected float scalePivotX() {
        return anchorX;
    }

    @Override
    protected float scalePivotY(float restY) {
        return anchorY;
    }

    @Override
    protected void layout(float dy) {
        refresh();
        int n = divers.size();
        w = Math.min(WIDTH, Ui.safeRight() - Ui.safeLeft() - 48f);
        float maxH = Ui.safeTop() - 24f - anchorY;
        float rowsH = Math.min(n * ROW_H, Math.max(ROW_H, maxH - HEADER - FOOTER));
        h = HEADER + rowsH + FOOTER;
        x = anchorX;
        y = anchorY + dy;
        list.setBounds(x + 16f, y + FOOTER, w - 32f, rowsH);
        list.setContentHeight(n * ROW_H);
        float top = list.top() + list.offset();
        for (int i = 0; i < n; i++) {
            float ry = top - (i + 1) * ROW_H;
            rows[i].setBounds(list.x(), ry, list.width(), ROW_H);
            rows[i].setVisible(list.isVisible(ry, ry + ROW_H));
        }
        float bw = (w - 3f * 24f) * 0.5f;
        float bh = Button.standardHeight();
        add.setBounds(x + 24f, y + (FOOTER - bh) * 0.5f, bw, bh);
        manage.setBounds(x + 48f + bw, y + (FOOTER - bh) * 0.5f, bw, bh);
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        fonts.draw(b, Fonts.Role.UI_LABEL, 20f, Lang.t("DIVERS", "玩家"), x + 40f, y + h - 34f, Align.left | Align.top,
            Theme.INK1, alpha);
        fonts.draw(b, Fonts.Role.NUMBER, 20f, count, x + w - 40f, y + h - 34f, Align.right | Align.top, Theme.INK2,
            alpha);
        list.beginClip(b, host.screenViewport(), 0f);
        for (int i = 0; i < divers.size(); i++) {
            Tappable t = rows[i];
            if (!t.isVisible()) continue;
            drawRow(b, divers.get(i), t, alpha);
        }
        list.endClip(b);
        list.drawIndicator(draw, alpha);
        draw.rect(x + 24f, y + FOOTER - 1f, w - 48f, 1f, Theme.STROKE, alpha);
    }

    private void drawRow(Batch b, DiverList.Entry e, Tappable t, float alpha) {
        float rx = t.x(), ry = t.y(), rw = t.width(), rh = t.height();
        float cy = ry + rh * 0.5f;
        if (e.active) {
            draw.roundRect(rx, ry + 6f, rw, rh - 12f, 16f, Theme.GROUND_SIG, 0.10f * alpha);
            draw.rect(rx + 4f, ry + 22f, 4f, rh - 44f, Theme.GROUND_SIG, alpha);
        }
        if (t.pressAmount() > 0f || t.hoverAmount() > 0f) {
            draw.roundRect(rx, ry + 6f, rw, rh - 12f, 16f, Theme.INK1,
                (0.08f * t.pressAmount() + 0.05f * t.hoverAmount()) * alpha);
        }
        t.drawPress(Theme.INK1, alpha);
        float k = t.visualScale();
        game.avatars().draw(b, e.spec, rx + 64f, cy, 72f * k, alpha, time);
        float tx = rx + 124f;
        float maxW = rw - 124f - (e.active ? 150f : 40f);
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 26f, e.name, tx, cy + 8f, Align.left, maxW, 0.7f, Theme.INK1, alpha);
        fonts.draw(b, Fonts.Role.UI, 20f, e.meta, tx, cy - 32f, Align.left, Theme.INK2, alpha);
        if (e.active) {
            float pw = fonts.width(Fonts.Role.UI_LABEL, 16f, ACTIVE) + 28f;
            float px = rx + rw - 24f - pw;
            draw.pill(px, cy - 18f, pw, 36f, Theme.GROUND_SIG, 0.18f * alpha);
            fonts.draw(b, Fonts.Role.UI_LABEL, 16f, ACTIVE, px + pw * 0.5f, cy, Align.center | Fonts.VCENTER,
                Theme.GROUND_SIG, alpha);
        } else {
            Theme.withAlpha(Theme.INK2, 1f, tmp);
            Icons.draw(draw.shapes(), Icons.Icon.CHEVRON_RIGHT, rx + rw - 40f, cy, 28f, tmp, 0.6f * alpha);
        }
    }

    private void pick(int i) {
        if (i >= divers.size()) return;
        DiverList.Entry e = divers.get(i);
        close();
        if (!e.active) host.switchDiver(e.id);
    }
}
