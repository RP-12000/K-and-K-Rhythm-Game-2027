package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 玩家档案画面的潜水员信息块：大头像（点开头像编辑器）、名字（点开改名）、
 * 贝壳数和深度（Kelvin 2026-10-17：取消等级、评分和称号；14:10 起不再显示下一个解锁和进度条，只留深度数字；
 * 14:47 起去掉 SHELLS 字样，贝壳数居中放在名字和深度之间），以及游玩次数和游玩时长两条（从 STATS 挪过来）。
 * 宽屏时是左栏，两条在卡片下面一直排到底；4:3 时是顶部横条，两条在横条右边。切换潜水员时头像翻转，数字滚动到新值。
 * 点名字直接改名（14:55）：手机上只弹出键盘（上面一行系统输入条），桌面上名字原地变成输入框；不再打开改名对话框。
 *
 * <p>The diver block of the Profile screen (DESIGN §8.2, §23.8). WIDE: a 560-wide column with the
 * avatar 300 (tap → avatar editor), the name UI_BOLD 36 with a pencil (tap → rename), the shell and
 * count (NUMBER 26, gold, centred; no {@code SHELLS} label since 14:47) and the depth (NUMBER 56);
 * under the card, two bars down to the bottom: {@code PLAYS} and {@code PLAY TIME} (label left,
 * NUMBER 36 right; moved from STATS, Kelvin 14:47). TALL (4:3): a header block with a 240 avatar
 * beside the text and the two bars at its right. Numbers follow the live, counting
 * {@link DiverStats}; the avatar flips on a switch. Tapping the name renames in place (Kelvin 14:55):
 * {@link #nameField} in {@link TextField.Mode#KEYBOARD} brings up only the soft keyboard on a phone
 * (the name is underlined meanwhile) and becomes an inline field over the name on desktop; the
 * rename dialog is no longer opened from here. No allocation per frame.
 */
public final class ProfileHeader {
    private final Main game;
    private final Draw draw;
    private final Fonts fonts;
    private final AvatarView avatar;
    /** Avatar hot-spot (opens the editor). */
    public final Tappable avatarTap;
    /** Name hot-spot (starts {@link #beginRename}). */
    public final Tappable nameTap;
    /** In-place rename: keyboard only on a phone, an inline field over the name on desktop. */
    public final TextField nameField;
    private final StringBuilder shells = new StringBuilder(16), depth = new StringBuilder(16);
    private final StringBuilder plays = new StringBuilder(16), playTime = new StringBuilder(16);
    private static final CharSequence[] BAR_LABELS = Lang.list("PLAYS", "游玩次数", "PLAY TIME", "游玩时长");
    /** PLAYS / PLAY TIME bars: tallest bar and the gap between them. */
    private static final float BAR_MAX_H = 140f, BAR_GAP = 16f;
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private DiverStats stats;
    private String name = "";
    private boolean tall;
    private float k = 1f;
    private float colX, colW, top, avatarX, avatarY, avatarSize, bottom;
    private float panelX, panelY, panelW, panelH;
    /** PLAYS / PLAY TIME bars: left, width, top edge and height of each. */
    private float barsX, barsW, barsTop, barH;
    private float time;

    /** Creates the block; its hot-spots go into {@code widgets}. */
    public ProfileHeader(Main game, WidgetSet widgets) {
        this.game = game;
        this.draw = game.draw();
        this.fonts = game.fonts();
        avatar = new AvatarView(game);
        avatarTap = widgets.add(new Tappable(draw));
        nameTap = widgets.add(new Tappable(draw));
        nameField = widgets.add(new TextField(draw, fonts, Lang.of("TAP TO NAME YOUR PLAYER", "点这里给玩家起名")));
        nameField.setMode(TextField.Mode.KEYBOARD).setMaxLength(ProfileStore.MAX_NAME_CODEPOINTS);
        nameField.setTextStyle(Fonts.Role.UI_BOLD, 30f);
        nameField.setVisible(false);
    }

    /** Starts renaming the shown diver in place. */
    public void beginRename() {
        if (nameField.isEditing() || nameField.isAwaitingDialog()) return;
        nameField.setText(name);
        nameField.setVisible(true);
        nameField.beginEdit();
        // on a phone the keyboard's own field shows the text: the name stays, underlined
        if (nameField.isAwaitingDialog()) nameField.setVisible(false);
    }

    /** Ends renaming without saving (screen left). */
    public void cancelRename() {
        nameField.closeKeyboard(false);
        nameField.cancelEdit();
        nameField.setVisible(false);
    }

    /** Binds diver {@code p}; {@code flip} plays the §8.3 avatar flip. */
    public void refresh(Profile p, DiverStats live, boolean flip) {
        stats = live;
        name = p.name != null ? p.name : "";
        if (flip) avatar.flipTo(p.avatar, name);
        else avatar.set(p.avatar, name);
        avatar.setOrbit(Depth.orbitRingUnlocked(p.depthMeters()));
    }

    /** Lays out the WIDE column: left edge {@code x}, top {@code topY}, down to {@code bottomY}. */
    public void layoutWide(float x, float topY, float bottomY) {
        tall = false;
        colX = x;
        colW = 560f;
        top = topY;
        float card = 600f;
        // the card, then the PLAYS / PLAY TIME bars (at least 80 each) down to the bottom
        float natural = card + 24f + 2f * 80f + BAR_GAP;
        k = Math.max(0.6f, Math.min(Ui.uiScale(), (topY - bottomY) / natural));
        avatarSize = 300f * k;
        avatarX = colX + colW * 0.5f;
        avatarY = top - 24f * k - avatarSize * 0.5f;
        bottom = top - card * k;
        panelX = colX - 24f;
        panelW = colW + 48f;
        panelY = bottom;
        panelH = top - bottom + 8f;
        barsX = panelX;
        barsW = panelW;
        barsTop = panelY - 24f * k;
        barH = Math.min(BAR_MAX_H * k, (barsTop - bottomY - BAR_GAP * k) * 0.5f);
        place();
    }

    /** Lays out the TALL header block across {@code [x, x + width]} from {@code topY}; returns its bottom. */
    public float layoutTall(float x, float topY, float width) {
        tall = true;
        colX = x;
        colW = width;
        top = topY;
        k = Math.min(1f, Ui.uiScale());
        avatarSize = 240f;
        avatarX = colX + 24f + avatarSize * 0.5f;
        avatarY = top - 16f - avatarSize * 0.5f;
        bottom = top - 16f - avatarSize - 24f;
        panelX = colX - 24f;
        panelW = colW + 48f;
        panelY = bottom;
        panelH = top - bottom + 8f;
        // the bars sit at the right of the block, beside the text column
        barH = 96f;
        float textRight = textX() + Math.min(560f, (colX + colW - textX()) * 0.5f - 20f);
        barsW = Math.min(600f, colX + colW - textRight - 40f);
        barsX = colX + colW - barsW;
        barsTop = (top + bottom + 8f) * 0.5f + barH + BAR_GAP * 0.5f;
        place();
        return bottom;
    }

    private void place() {
        avatarTap.setBounds(avatarX - avatarSize * 0.5f, avatarY - avatarSize * 0.5f, avatarSize, avatarSize);
        float ny = nameY();
        if (tall) {
            nameTap.setBounds(textX(), ny - 30f, Math.min(560f, colW * 0.5f), 60f);
            nameField.setBounds(textX() - 16f, ny - 36f, Math.min(560f, colW * 0.5f), 72f);
        } else {
            nameTap.setBounds(colX + 20f, ny - 30f * k, colW - 40f, 60f * k);
            nameField.setBounds(colX, ny - 36f * k, colW, 72f * k);
        }
    }

    private float textX() {
        return avatarX + avatarSize * 0.5f + 40f;
    }

    private float nameY() {
        return tall ? top - 52f : avatarY - avatarSize * 0.5f - 42f * k;
    }

    /** Avatar centre x (heartbeat source). */
    public float avatarX() {
        return avatarX;
    }

    /** Avatar centre y. */
    public float avatarY() {
        return avatarY;
    }

    /** Advances the avatar. */
    public void update(float dt) {
        time += dt;
        avatar.update(dt);
        // the inline field (desktop) goes away once the edit is committed or cancelled
        if (nameField.isVisible() && !nameField.isEditing()) nameField.setVisible(false);
        // while the field is up, taps on it move the caret instead of restarting the rename
        nameTap.setVisible(!nameField.isVisible());
    }

    private void buildStrings() {
        DiverStats s = stats;
        shells.setLength(0);
        Fonts.appendGrouped(shells, Math.round(s.shells));
        depth.setLength(0);
        Fonts.appendGrouped(depth, -Math.max(0L, (long) s.depth)); // below the surface: -2,560
        plays.setLength(0);
        Fonts.appendGrouped(plays, Math.round(s.plays));
        playTime.setLength(0);
        ShellFormat.appendHoursMinutes(playTime, (long) s.playMs);
    }

    /** Draws the block; {@code alpha} fades it in. */
    public void draw(Batch b, float alpha, float enter) {
        if (stats == null) return;
        buildStrings();
        float a = alpha * enter;
        Panel.card(draw, panelX, panelY, panelW, panelH, 0.85f * a);
        float s = avatarTap.visualScale();
        draw.glowDot(avatarX, avatarY, avatarSize * 1.4f, Theme.GROUND_SIG, 0.08f * a);
        avatar.draw(b, avatarX + avatarTap.shakeX(), avatarY, avatarSize * s, a);
        if (avatarTap.hoverAmount() > 0f) {
            Icons.draw(draw.shapes(), Icons.Icon.PENCIL, avatarX + avatarSize * 0.36f, avatarY - avatarSize * 0.36f,
                34f, Theme.INK1, avatarTap.hoverAmount() * a);
        }
        if (tall) drawTall(b, a);
        else drawWide(b, a);
        drawBars(b, a);
    }

    /** PLAYS and PLAY TIME: two bars, label left and number right (wide: cards; tall: inside the block). */
    private void drawBars(Batch b, float a) {
        float kk = tall ? 1f : k;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 2; i++) {
                float by = barsTop - (i + 1) * barH - i * BAR_GAP * kk;
                if (pass == 0) {
                    if (tall) draw.roundRect(barsX, by, barsW, barH, 12f, Theme.PANEL, 0.6f * a);
                    else Panel.card(draw, barsX, by, barsW, barH, a);
                    continue;
                }
                float cy = by + barH * 0.5f;
                fonts.draw(b, Fonts.Role.UI_LABEL, 20f * kk, BAR_LABELS[i], barsX + 32f * kk, cy,
                    Align.left | Fonts.VCENTER, Theme.INK2, a);
                fonts.drawFixed(b, Fonts.Role.NUMBER, 36f * kk, i == 0 ? plays : playTime, barsX + barsW - 32f * kk, cy,
                    Align.right | Fonts.VCENTER, Theme.INK1, a);
            }
        }
    }

    /** While the phone keyboard is up: a pulsing underline under the name from {@code x}, {@code w} wide. */
    private void drawRenaming(float x, float y, float w, float a) {
        if (!nameField.isAwaitingDialog()) return;
        float pulse = 0.55f + 0.45f * (float) Math.sin(time * 5f);
        draw.rect(x, y, w, 3f, Theme.GROUND_SIG, pulse * a);
    }

    private void drawWide(Batch b, float a) {
        float cx = colX + colW * 0.5f;
        float y = nameY();
        float nameMax = colW - 120f;
        float nw = Math.min(nameMax, fonts.width(Fonts.Role.UI_BOLD, 36f * k, name));
        float nx = cx - (nw + 44f) * 0.5f + nameTap.shakeX();
        float bright = 1f + 0.1f * nameTap.hoverAmount();
        if (!nameField.isVisible()) {
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 36f * k, name, nx, y, Align.left | Fonts.VCENTER, nameMax, 0.6f,
                Theme.INK1, a);
            Icons.draw(draw.shapes(), Icons.Icon.PENCIL, nx + nw + 28f, y, 30f, Theme.INK2,
                Math.min(1f, 0.7f * bright) * a);
            drawRenaming(nx, y - 28f * k, nw, a);
        }
        nameTap.drawPress(Theme.INK1, a);
        // shells, centred (no label since 14:47)
        y -= 58f * k;
        drawShells(b, cx - shellsWidth(k) * 0.5f, y, k, a);
        // depth
        y -= 94f * k;
        float dw = fonts.fixedWidth(Fonts.Role.NUMBER, 56f * k, depth);
        float uw = fonts.width(Fonts.Role.UI, 28f * k, "m");
        drawDepth(b, cx - (dw + 12f * k + uw) * 0.5f, y, k, a);
    }

    /**
     * The depth number (NUMBER 56) from {@code left} with its unit {@code m} (UI 28, the {@code m} of the NUMBER face
     * is missing).
     */
    private void drawDepth(Batch b, float left, float y, float k, float a) {
        float dw = fonts.drawFixed(b, Fonts.Role.NUMBER, 56f * k, depth, left, y, Align.left | Fonts.VCENTER,
            Theme.INK1, a);
        fonts.draw(b, Fonts.Role.UI, 28f * k, "m", left + dw + 12f * k, y - 8f * k, Align.left | Fonts.VCENTER,
            Theme.INK2, a);
    }

    /** Width of the shell and its count. */
    private float shellsWidth(float k) {
        return 30f * k + fonts.fixedWidth(Fonts.Role.NUMBER, 26f * k, shells);
    }

    /** The shell and the count (gold) from {@code left}. */
    private void drawShells(Batch b, float left, float y, float k, float a) {
        draw.shell(left + 13f * k, y, 26f * k, Theme.GOLD, SHELL_RIB, a);
        fonts.drawFixed(b, Fonts.Role.NUMBER, 26f * k, shells, left + 30f * k, y, Align.left | Fonts.VCENTER,
            Theme.GOLD, a);
    }

    private void drawTall(Batch b, float a) {
        float tx = textX();
        float colA = Math.min(560f, (colX + colW - tx) * 0.5f - 20f);
        float y = nameY();
        float nw = Math.min(colA - 50f, fonts.width(Fonts.Role.UI_BOLD, 36f, name));
        if (!nameField.isVisible()) {
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 36f, name, tx + nameTap.shakeX(), y, Align.left | Fonts.VCENTER,
                colA - 50f, 0.6f, Theme.INK1, a);
            Icons.draw(draw.shapes(), Icons.Icon.PENCIL, tx + nw + 28f, y, 30f, Theme.INK2, 0.7f * a);
            drawRenaming(tx, y - 28f, nw, a);
        }
        nameTap.drawPress(Theme.INK1, a);
        y -= 60f;
        drawShells(b, tx, y, 1f, a);
        y -= 94f;
        drawDepth(b, tx, y, 1f, a);
    }
}
