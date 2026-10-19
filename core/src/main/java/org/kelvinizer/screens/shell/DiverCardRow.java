package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;

/**
 * 设置 → ACCOUNT 的第一行：当前潜水员的头像、名字和「等级 · 深度 · 段位」，右侧是 SWITCH 和 MANAGE DIVERS 按钮。
 *
 * <p>Settings → ACCOUNT's first row (DESIGN §9.2 "active diver"): the active diver's sigil, name
 * (UI_BOLD 30) and {@code LV · depth · rank} line, with up to two action buttons at the right
 * (SWITCH, MANAGE DIVERS).
 */
public final class DiverCardRow extends ActionRow {
    private static final float CARD_H = 152f;

    private final AvatarView avatar;
    private final StringBuilder name = new StringBuilder(32);
    private final StringBuilder meta = new StringBuilder(64);

    /** Creates the card; bind buttons with {@link #add}. */
    public DiverCardRow(Main game) {
        super(game, "", null, false);
        avatar = new AvatarView(game);
        label = name;
    }

    @Override
    public float naturalHeight() {
        return CARD_H;
    }

    @Override
    protected float lineHeight() {
        return h;
    }

    /** Re-reads the active diver (name, shells, depth, sigil). */
    @Override
    public void sync() {
        Profile p = game.profiles().active();
        name.setLength(0);
        meta.setLength(0);
        if (p == null) return;
        name.append(p.name != null ? p.name : "");
        ShellFormat.appendDiverMeta(meta, p);
        avatar.set(p.avatar, p.name);
    }

    @Override
    public void update(float dt) {
        super.update(dt);
        avatar.update(dt);
    }


    @Override
    protected void drawText(Batch b, float alpha) {
        float cy = lineCy();
        float size = Math.min(h - 24f * k, 112f * k);
        avatar.draw(b, x + size * 0.5f, cy, size, alpha);
        float tx = x + size + 28f * k;
        float maxW = controlX - GAP - tx;
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 30f * k, name, tx, cy + 18f * k, Align.left | Fonts.VCENTER, maxW,
            0.6f, Theme.INK1, alpha);
        fonts.drawFit(b, Fonts.Role.UI, 20f * k, meta, tx, cy - 22f * k, Align.left | Fonts.VCENTER, maxW, 0.7f,
            Theme.INK2, alpha);
    }
}
