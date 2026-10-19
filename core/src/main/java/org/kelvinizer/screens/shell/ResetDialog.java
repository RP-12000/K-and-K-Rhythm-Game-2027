package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;

/**
 * 重置记录的确认框（设置 → 其他 → 重置记录，按住 2 秒后弹出）：先点「确认」，再把玩家名完整输入一遍，名字对上了「重置」才能点。
 * 重置清空这位玩家的全部进度，包括贝壳和买下的曲目。
 *
 * <p>RESET RECORDS confirmation (Kelvin 2026-10-18 20:16: 「先要玩家长按，然后再点确认，然后再把用户名完整打一遍才能删除」).
 * The settings row's 2 s hold opens it for the active diver. Step one shows the diver, says what goes and asks
 * for CONFIRM; step two asks for the diver's name typed in full, and RESET is enabled only while the text (the
 * inline edit buffer on desktop, the keyboard's committed text on a phone) is the name exactly, spaces at the ends
 * aside. RESET runs the host's action, which calls {@link ProfileStore#resetRecords}. CANCEL, BACK or a tap
 * outside closes it without changes. No allocation per frame.
 */
public final class ResetDialog extends Modal {
    private static final CharSequence TITLE = Lang.of("RESET RECORDS?", "重置记录？");
    private static final CharSequence ALL = Lang.of("ALL PROGRESS WILL BE ERASED:", "这位玩家的全部进度都会清空：");
    private static final CharSequence WHAT = Lang.of("SCORES, DEPTH, GAME LOG, SHELLS AND BOUGHT SONGS.",
        "成绩、深度、下潜记录、贝壳和买下的曲目。");
    private static final CharSequence UNDO = Lang.of("THIS CANNOT BE UNDONE.", "清空后无法恢复。");
    private static final CharSequence TYPE = Lang.of("TYPE THE DIVER NAME IN FULL TO RESET",
        "把玩家名完整输入一遍才能重置");

    private final Button cancel, confirm, reset;
    private final TextField field;
    private final AvatarView avatar;
    private final StringBuilder meta = new StringBuilder(48);
    private final Runnable action;
    private String name = "";
    /** Step two: the name field and RESET instead of the warning and CONFIRM. */
    private boolean typing;

    /** Creates the dialog for {@code host}; {@code action} resets the active diver. */
    public ResetDialog(Main game, ShellScreen host, Runnable action) {
        super(game, host);
        this.action = action;
        avatar = new AvatarView(game);
        field = widgets.add(new TextField(draw, fonts, Lang.of("TAP TO TYPE THE NAME", "点这里输入玩家名")));
        field.setMode(TextField.Mode.KEYBOARD).setMaxLength(ProfileStore.MAX_NAME_CODEPOINTS);
        host.registerField(field);
        cancel = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("CANCEL", "取消")));
        cancel.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
        confirm = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("CONFIRM", "确认")));
        confirm.setAccent(Theme.LOST);
        confirm.setOnTap(new Runnable() {
            @Override
            public void run() {
                showStep(true);
            }
        });
        reset = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("RESET", "重置")));
        reset.setAccent(Theme.LOST);
        reset.setOnTap(new Runnable() {
            @Override
            public void run() {
                tryReset();
            }
        });
    }

    @Override
    protected void onOpened() {
        Profile p = game.profiles().active();
        if (p == null) {
            close();
            return;
        }
        name = p.name != null ? p.name : "";
        avatar.set(p.avatar, name);
        meta.setLength(0);
        ShellFormat.appendDiverMeta(meta, p);
        showStep(false);
    }

    @Override
    protected void onClosed() {
        field.closeKeyboard(false);
        field.cancelEdit();
    }

    private void showStep(boolean type) {
        field.closeKeyboard(false);
        field.cancelEdit();
        typing = type;
        field.setText("");
        field.setVisible(type);
        confirm.setVisible(!type);
        reset.setVisible(type);
        reset.setEnabled(false);
        if (type) field.beginEdit();
    }

    @Override
    protected void layout(float dy) {
        float bh = Button.standardHeight();
        centerCard(860f, 406f + bh, dy);
        float bw = Math.min(380f, (w - 96f - 32f) * 0.5f);
        cancel.setBounds(x + 48f, y + 40f, w - 96f - bw - 32f, bh);
        confirm.setBounds(x + w - 48f - bw, y + 40f, bw, bh);
        reset.setBounds(x + w - 48f - bw, y + 40f, bw, bh);
        float tx = x + 188f, top = y + h - 110f;
        field.setBounds(tx, top - 100f - bh, x + w - 48f - tx, bh);
    }

    @Override
    protected void onUpdate(float dt) {
        avatar.update(dt);
        if (typing) reset.setEnabled(matches(field.displayText()));
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        drawTitle(b, TITLE, alpha);
        float top = y + h - 110f, tx = x + 188f, tw = w - 236f;
        avatar.draw(b, x + 104f, top - 60f, 112f, alpha);
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 32f, name, tx, top - 36f, Align.left, tw, 0.6f, Theme.INK1, alpha);
        if (typing) {
            fonts.drawFit(b, Fonts.Role.UI, 20f, TYPE, tx, top - 76f, Align.left, tw, 0.8f, Theme.INK2, alpha);
        } else {
            fonts.drawFit(b, Fonts.Role.UI, 20f, meta, tx, top - 72f, Align.left, tw, 0.6f, Theme.INK2, alpha);
            fonts.drawFit(b, Fonts.Role.UI, 20f, ALL, tx, top - 122f, Align.left, tw, 0.9f, Theme.INK1, alpha);
            fonts.drawFit(b, Fonts.Role.UI, 20f, WHAT, tx, top - 156f, Align.left, tw, 0.9f, Theme.INK1, alpha);
            fonts.drawFit(b, Fonts.Role.UI, 20f, UNDO, tx, top - 190f, Align.left, tw, 0.9f, Theme.LOST, alpha);
        }
        draw.roundRectStroke(x, y, w, h, 18f, 2f, Theme.LOST, 0.45f * alpha);
    }

    private void tryReset() {
        if (field.isEditing() && !field.commit()) return;
        if (!matches(field.text())) {
            field.reject();
            return;
        }
        close();
        action.run();
    }

    /** True when {@code s}, without spaces at either end, is the diver's name (no allocation). */
    private boolean matches(CharSequence s) {
        int a = 0, e = s.length();
        while (a < e && s.charAt(a) <= ' ') a++;
        while (e > a && s.charAt(e - 1) <= ' ') e--;
        if (name.isEmpty() || e - a != name.length()) return false;
        for (int i = 0; i < name.length(); i++) if (s.charAt(a + i) != name.charAt(i)) return false;
        return true;
    }
}
