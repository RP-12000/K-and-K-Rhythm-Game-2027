package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Theme;

/**
 * 玩家档案的 PLAYER「玩家」标签页（原来叫 DIVERS，Kelvin 2026-10-18 02:27 改名）：每个潜水员一张卡片（头像、名字、「贝壳 · 深度」、
 * 「上次活动：」加日期和时间），当前的标 ACTIVE。
 * 点其他卡片只切换到那位潜水员，这里不能编辑（Kelvin 2026-10-18）；末尾是 {@code + NEW DIVER} 卡片和 IMPORT 按钮。
 *
 * <p>PLAYER, formerly DIVERS (DESIGN §8.2, §8.3): cards 520×128 in two columns (one on narrow content): avatar 88,
 * name, {@code shells · depth}, {@code LAST ACTIVITY: OCT 18 · 02:49} (the last dive's date and time, Kelvin
 * 2026-10-18 02:53; it said {@code DIVED TODAY} before). The active card carries an {@code ACTIVE} tag and an
 * accent border. Tapping another card switches to that diver (flip + count, §8.3); the active card
 * takes no taps, and nothing on this tab edits a diver (Kelvin 2026-10-18 00:34: 「这里只允许切换
 * diver」; the name and avatar are edited from the header). A {@code + NEW DIVER} card (disabled at
 * 8) and an IMPORT button close the grid.
 */
public final class DiversTab extends ProfileTab {
    /** Card size and gap. */
    public static final float CARD_W = 520f, CARD_H = 128f, GAP = 24f;
    private static final CharSequence ACTIVE = Lang.of("ACTIVE", "当前");
    private static final CharSequence NEW_DIVER = Lang.of("NEW DIVER", "新建玩家");
    private static final CharSequence FULL = Lang.of("8 / 8 DIVERS", "8 / 8 位玩家");
    private static final CharSequence HINT = Lang.of("UP TO 8 DIVERS ON THIS DEVICE", "这台设备最多存 8 位玩家");
    private static final CharSequence HINT_FULL = Lang.of("THIS DEVICE IS FULL", "这台设备已经存满了");

    private final DiverList divers = new DiverList();
    private final Tappable[] cards = new Tappable[ProfileStore.MAX_PROFILES];
    private final Tappable newCard;
    private final Button importButton;
    private final CreateDiverSheet createSheet;
    private final ImportDialog importDialog;
    private final StringBuilder count = new StringBuilder(16);
    private int cols = 2;
    private float cardW = CARD_W;

    /** Creates the tab; its widgets go into the screen's {@code widgets}. */
    public DiversTab(Main game, ShellScreen host, WidgetSet widgets, CreateDiverSheet createSheet,
                     ImportDialog importDialog) {
        super(game, host);
        this.createSheet = createSheet;
        this.importDialog = importDialog;
        for (int i = 0; i < cards.length; i++) {
            final int index = i;
            cards[i] = widgets.add(new Tappable(draw));
            cards[i].setClip(scroll);
            cards[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    tapCard(index);
                }
            });
        }
        newCard = widgets.add(new Tappable(draw));
        newCard.setClip(scroll);
        newCard.setOnTap(new Runnable() {
            @Override
            public void run() {
                if (!DiversTab.this.game.profiles().canCreate()) {
                    newCard.shake();
                    DiversTab.this.game.toasts().show(Lang.t("8 DIVERS MAX", "最多 8 位玩家"), Theme.LOST);
                    return;
                }
                DiversTab.this.host.openModal(DiversTab.this.createSheet);
            }
        });
        importButton = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("IMPORT", "导入")));
        importButton.setOnTap(new Runnable() {
            @Override
            public void run() {
                DiversTab.this.host.openModal(DiversTab.this.importDialog);
            }
        });
        onVisibility(false);
    }

    @Override
    public void refresh(Profile p, DiverStats stats) {
        divers.invalidate();
        divers.refresh(game.profiles());
        updateCount();
    }

    private void updateCount() {
        count.setLength(0);
        count.append(divers.size()).append(" / ").append(ProfileStore.MAX_PROFILES)
            .append(Lang.t(" DIVERS", " 位玩家"));
    }

    @Override
    protected void onLayout() {
        if (w >= 2f * 400f + GAP) {
            cols = 2;
            cardW = Math.min(CARD_W, (w - GAP) * 0.5f);
        } else {
            cols = 1;
            cardW = Math.min(w, 640f);
        }
    }

    private int items() {
        return divers.size() + 1;
    }

    private int gridRows() {
        return (items() + cols - 1) / cols;
    }

    @Override
    protected float contentHeight() {
        return 48f + gridRows() * (CARD_H + GAP) + Button.standardHeight() + 40f;
    }

    @Override
    protected void onVisibility(boolean on) {
        for (int i = 0; i < cards.length; i++) cards[i].setVisible(on && i < divers.size() && !divers.get(i).active);
        newCard.setVisible(on);
        importButton.setVisible(on);
    }

    @Override
    protected void place(float top) {
        if (divers.refresh(game.profiles())) updateCount();
        boolean on = isVisible();
        int n = divers.size();
        float gridTop = top - 48f;
        for (int i = 0; i < cards.length; i++) {
            if (i >= n) {
                cards[i].setVisible(false);
                continue;
            }
            float cx = x + (i % cols) * (cardW + GAP);
            float cy = gridTop - (i / cols + 1) * CARD_H - (i / cols) * GAP;
            cards[i].setBounds(cx, cy, cardW, CARD_H);
            // the active card takes no taps (and so shows no press)
            cards[i].setVisible(on && scroll.isVisible(cy, cy + CARD_H) && !divers.get(i).active);
        }
        float nx = x + (n % cols) * (cardW + GAP);
        float ny = gridTop - (n / cols + 1) * CARD_H - (n / cols) * GAP;
        newCard.setBounds(nx, ny, cardW, CARD_H);
        newCard.setVisible(on && scroll.isVisible(ny, ny + CARD_H));
        newCard.setEnabled(game.profiles().canCreate());
        float iy = gridTop - gridRows() * (CARD_H + GAP) - Button.standardHeight() - 8f;
        float iw = Math.min(420f, w);
        importButton.setBounds(x, iy, iw, Button.standardHeight());
        importButton.setVisible(on && iy >= scroll.y() - 1f && iy + Button.standardHeight() <= scroll.top() + 1f);
    }

    @Override
    protected void drawContent(Batch b, float top, float alpha) {
        fonts.draw(b, Fonts.Role.UI_LABEL, 18f, count, x, top - 20f, Align.left | Fonts.VCENTER, Theme.INK2,
            alpha * rowIn(0));
        int n = divers.size();
        for (int i = 0; i < n; i++) {
            Tappable t = cards[i];
            if (!scroll.isVisible(t.y(), t.y() + CARD_H)) continue;
            drawCard(b, divers.get(i), t, alpha * rowIn(i + 1));
        }
        if (scroll.isVisible(newCard.y(), newCard.y() + CARD_H)) drawNewCard(b, alpha * rowIn(n + 1));
    }

    private void drawCard(Batch b, DiverList.Entry e, Tappable t, float a) {
        float s = t.visualScale();
        float w0 = t.width(), h0 = t.height();
        float cw = w0 * s, ch = h0 * s;
        float cx = t.centerX() + t.shakeX(), cy = t.centerY();
        float x0 = cx - cw * 0.5f, y0 = cy - ch * 0.5f;
        Panel.card(draw, x0, y0, cw, ch, a);
        if (e.active) {
            draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.GROUND_SIG, 0.08f * a);
            draw.roundRectStroke(x0, y0, cw, ch, Panel.RADIUS, 2.5f, Theme.GROUND_SIG, 0.9f * a);
        }
        if (t.pressAmount() > 0f || t.hoverAmount() > 0f) {
            draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.INK1, (0.07f * t.pressAmount() + 0.04f * t.hoverAmount()) * a);
        }
        t.drawPress(Theme.INK1, a);
        game.avatars().draw(b, e.spec, x0 + 24f + 44f * s, cy, 88f * s, a, time);
        float tx = x0 + 136f * s;
        float right = x0 + cw - 24f * s;
        float nameMax = right - tx;
        if (e.active) {
            float pw = fonts.width(Fonts.Role.UI_LABEL, 16f, ACTIVE) + 24f;
            float px = right - pw;
            draw.pill(px, cy + 30f - 15f, pw, 30f, Theme.GROUND_SIG, 0.18f * a);
            fonts.draw(b, Fonts.Role.UI_LABEL, 16f, ACTIVE, px + pw * 0.5f, cy + 30f, Align.center | Fonts.VCENTER,
                Theme.GROUND_SIG, a);
            nameMax = px - 12f - tx;
        }
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 26f, e.name, tx, cy + 30f, Align.left | Fonts.VCENTER, nameMax, 0.65f,
            Theme.INK1, a);
        // the meta line keeps its 18 tier and ellipsizes rather than shrinking below it (DESIGN §1.1)
        fonts.drawFit(b, Fonts.Role.UI, 18f, e.meta, tx, cy - 6f, Align.left | Fonts.VCENTER, right - tx, 1f,
            Theme.INK2, a);
        fonts.draw(b, Fonts.Role.UI_REGULAR, 16f, e.lastActivity, tx, cy - 38f, Align.left | Fonts.VCENTER, Theme.INK3,
            a);
    }

    private void drawNewCard(Batch b, float a) {
        Tappable t = newCard;
        float s = t.visualScale();
        float cw = t.width() * s, ch = t.height() * s;
        float cx = t.centerX() + t.shakeX(), cy = t.centerY();
        float x0 = cx - cw * 0.5f, y0 = cy - ch * 0.5f;
        float k = t.isEnabled() ? 1f : 0.4f;
        draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.PANEL, 0.35f * a);
        // dashed outline
        float dash = 18f, gap = 12f;
        for (float px = x0 + 16f; px < x0 + cw - 16f; px += dash + gap) {
            float len = Math.min(dash, x0 + cw - 16f - px);
            draw.rect(px, y0 + 1f, len, 2f, Theme.INK2, 0.55f * a * k);
            draw.rect(px, y0 + ch - 3f, len, 2f, Theme.INK2, 0.55f * a * k);
        }
        for (float py = y0 + 16f; py < y0 + ch - 16f; py += dash + gap) {
            float len = Math.min(dash, y0 + ch - 16f - py);
            draw.rect(x0 + 1f, py, 2f, len, Theme.INK2, 0.55f * a * k);
            draw.rect(x0 + cw - 3f, py, 2f, len, Theme.INK2, 0.55f * a * k);
        }
        if (t.pressAmount() > 0f || t.hoverAmount() > 0f) {
            draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.INK1, (0.07f * t.pressAmount() + 0.04f * t.hoverAmount()) * a);
        }
        t.drawPress(Theme.INK1, a);
        Icons.draw(draw.shapes(), Icons.Icon.PLUS, x0 + 68f, cy, 40f, Theme.GROUND_SIG, a * k);
        fonts.draw(b, Fonts.Role.UI_BOLD, 24f, t.isEnabled() ? NEW_DIVER : FULL, x0 + 136f, cy + 12f,
            Align.left | Fonts.VCENTER, Theme.INK1, a * k);
        CharSequence hint = t.isEnabled() ? HINT : HINT_FULL;
        fonts.drawFit(b, Fonts.Role.UI_REGULAR, 18f, hint, x0 + 136f, cy - 22f, Align.left | Fonts.VCENTER,
            cw - 136f - 24f, 0.85f, Theme.INK3, a * k);
    }

    private void tapCard(int i) {
        if (i >= divers.size()) return;
        DiverList.Entry e = divers.get(i);
        if (!e.active) host.switchDiver(e.id);
    }
}
