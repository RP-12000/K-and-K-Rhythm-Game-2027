package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
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
 * 「上次活动：」加日期和时间），当前的标 ACTIVE；放得下就一行三张（Kelvin 2026-10-21），否则两张或一张，
 * 卡片窄时贝壳数前面用贝壳图标代替「贝壳」两个字。
 * 点其他卡片只切换到那位潜水员，这里不能编辑（Kelvin 2026-10-18）；末尾是 {@code + NEW PLAYER} 卡片和 IMPORT 按钮，
 * 存满 12 位后这两个都不再显示（Kelvin 2026-10-21）。
 *
 * <p>PLAYER, formerly DIVERS (DESIGN §8.2, §8.3): cards up to 520×128, three a row where three fit (Kelvin
 * 2026-10-21: 「一行拍三个」), else two, else one; avatar 88 (72 on a card under 440 wide), name,
 * {@code shells · depth} (a shell glyph for the word on a card under 440 wide), {@code LAST ACTIVITY: OCT 18 · 02:49}
 * (the last dive's date and time, Kelvin 2026-10-18 02:53; it said {@code DIVED TODAY} before). The active card
 * carries an {@code ACTIVE} tag and an accent border. Tapping another card switches to that diver (flip + count,
 * §8.3); the active card takes no taps, and nothing on this tab edits a diver (Kelvin 2026-10-18 00:34: 「这里只允许
 * 切换 diver」; the name and avatar are edited from the header). A {@code + NEW PLAYER} card and an IMPORT button close
 * the grid until the device holds {@link ProfileStore#MAX_PROFILES} divers; then both are gone (Kelvin 2026-10-21
 * 01:44: 「到12以后就直接不要再显示“导入”和“添加玩家”了」). English says PLAYER where it said DIVER (02:04: 「12/12
 * players，不是divers」).
 */
public final class DiversTab extends ProfileTab {
    /** Card size and gap. */
    public static final float CARD_W = 520f, CARD_H = 128f, GAP = 24f;
    /**
     * Narrowest card of a three-column grid, and the width under which a card takes the narrow layout (avatar 72,
     * margins 18, the last line fitted), so the 16:10 grid of three keeps its text whole.
     */
    private static final float NARROW_MIN = 330f, NARROW_W = 440f;
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private static final CharSequence ACTIVE = Lang.of("ACTIVE", "当前");
    private static final CharSequence NEW_DIVER = Lang.of("NEW PLAYER", "新建玩家");
    private static final CharSequence HINT = Lang.of("UP TO " + ProfileStore.MAX_PROFILES + " ON THIS DEVICE",
        "这台设备最多存 " + ProfileStore.MAX_PROFILES + " 位玩家");

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
                    DiversTab.this.game.toasts().show(ShellFormat.diversMax(), Theme.LOST);
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
            .append(Lang.t(" PLAYERS", " 位玩家"));
    }

    @Override
    protected void onLayout() {
        // three a row where three fit (Kelvin 2026-10-21: 「一行拍三个」), else two, else one
        if (w >= 3f * NARROW_MIN + 2f * GAP) {
            cols = 3;
            cardW = Math.min(CARD_W, (w - 2f * GAP) / 3f);
        } else if (w >= 2f * 400f + GAP) {
            cols = 2;
            cardW = Math.min(CARD_W, (w - GAP) * 0.5f);
        } else {
            cols = 1;
            cardW = Math.min(w, 640f);
        }
    }

    /** False once the device is full: the NEW DIVER card and IMPORT are gone then. */
    private boolean canAdd() {
        return game.profiles().canCreate();
    }

    private int items() {
        return divers.size() + (canAdd() ? 1 : 0);
    }

    private int gridRows() {
        return (items() + cols - 1) / cols;
    }

    @Override
    protected float contentHeight() {
        return 48f + gridRows() * (CARD_H + GAP) + (canAdd() ? Button.standardHeight() + 40f : 16f);
    }

    @Override
    protected void onVisibility(boolean on) {
        for (int i = 0; i < cards.length; i++) cards[i].setVisible(on && i < divers.size() && !divers.get(i).active);
        newCard.setVisible(on && canAdd());
        importButton.setVisible(on && canAdd());
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
        if (!canAdd()) {
            newCard.setVisible(false);
            importButton.setVisible(false);
            return;
        }
        float nx = x + (n % cols) * (cardW + GAP);
        float ny = gridTop - (n / cols + 1) * CARD_H - (n / cols) * GAP;
        newCard.setBounds(nx, ny, cardW, CARD_H);
        newCard.setVisible(on && scroll.isVisible(ny, ny + CARD_H));
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
        if (canAdd() && scroll.isVisible(newCard.y(), newCard.y() + CARD_H)) drawNewCard(b, alpha * rowIn(n + 1));
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
            draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.INK1,
                (0.07f * t.pressAmount() + 0.04f * t.hoverAmount()) * a);
        }
        t.drawPress(Theme.INK1, a);
        boolean narrow = w0 < NARROW_W;
        float pad = narrow ? 18f : 24f, av = narrow ? 72f : 88f;
        game.avatars().draw(b, e.spec, x0 + pad + av * 0.5f * s, cy, av * s, a, time);
        float tx = x0 + (narrow ? 106f : 136f) * s;
        float right = x0 + cw - pad * s;
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
        // the meta line keeps its 18 tier and ellipsizes rather than shrinking below it (DESIGN §1.1); a narrow card
        // puts a shell glyph for the word, as the map's price chips do, so the depth stays whole
        if (narrow) {
            draw.shell(tx + 11f * s, cy - 6f, 22f * s, Theme.GOLD, SHELL_RIB, a);
            fonts.drawFit(b, Fonts.Role.UI, 18f, e.metaShort, tx + 30f * s, cy - 6f, Align.left | Fonts.VCENTER,
                right - tx - 30f * s, 1f, Theme.INK2, a);
        } else {
            fonts.drawFit(b, Fonts.Role.UI, 18f, e.meta, tx, cy - 6f, Align.left | Fonts.VCENTER, right - tx, 1f,
                Theme.INK2, a);
        }
        fonts.drawFit(b, Fonts.Role.UI_REGULAR, 16f, e.lastActivity, tx, cy - 38f, Align.left | Fonts.VCENTER,
            right - tx, 0.75f, Theme.INK3, a);
    }

    private void drawNewCard(Batch b, float a) {
        Tappable t = newCard;
        float s = t.visualScale();
        float cw = t.width() * s, ch = t.height() * s;
        float cx = t.centerX() + t.shakeX(), cy = t.centerY();
        float x0 = cx - cw * 0.5f, y0 = cy - ch * 0.5f;
        draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.PANEL, 0.35f * a);
        // dashed outline
        float dash = 18f, gap = 12f;
        for (float px = x0 + 16f; px < x0 + cw - 16f; px += dash + gap) {
            float len = Math.min(dash, x0 + cw - 16f - px);
            draw.rect(px, y0 + 1f, len, 2f, Theme.INK2, 0.55f * a);
            draw.rect(px, y0 + ch - 3f, len, 2f, Theme.INK2, 0.55f * a);
        }
        for (float py = y0 + 16f; py < y0 + ch - 16f; py += dash + gap) {
            float len = Math.min(dash, y0 + ch - 16f - py);
            draw.rect(x0 + 1f, py, 2f, len, Theme.INK2, 0.55f * a);
            draw.rect(x0 + cw - 3f, py, 2f, len, Theme.INK2, 0.55f * a);
        }
        if (t.pressAmount() > 0f || t.hoverAmount() > 0f) {
            draw.roundRect(x0, y0, cw, ch, Panel.RADIUS, Theme.INK1,
                (0.07f * t.pressAmount() + 0.04f * t.hoverAmount()) * a);
        }
        t.drawPress(Theme.INK1, a);
        boolean narrow = t.width() < NARROW_W;
        float tx = narrow ? 106f : 136f;
        Icons.draw(draw.shapes(), Icons.Icon.PLUS, x0 + (narrow ? 54f : 68f), cy, 40f, Theme.GROUND_SIG, a);
        fonts.draw(b, Fonts.Role.UI_BOLD, 24f, NEW_DIVER, x0 + tx, cy + 12f, Align.left | Fonts.VCENTER, Theme.INK1, a);
        fonts.drawFit(b, Fonts.Role.UI_REGULAR, 18f, HINT, x0 + tx, cy - 22f, Align.left | Fonts.VCENTER,
            cw - tx - (narrow ? 18f : 24f), narrow ? 0.7f : 0.85f, Theme.INK3, a);
    }

    private void tapCard(int i) {
        if (i >= divers.size()) return;
        DiverList.Entry e = divers.get(i);
        if (!e.active) host.switchDiver(e.id);
    }
}
