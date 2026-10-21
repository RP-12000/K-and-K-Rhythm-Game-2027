package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;

/**
 * 改名对话框：一打开就开始编辑名字（桌面在框内直接输入，手机用系统输入框）。
 * 名字 1–16 个字符，空名字会抖动拒绝，允许重名；SAVE 后写入存档。
 *
 * <p>RENAME (DESIGN §8.3): a dialog with the diver's name in a {@link TextField} that starts editing
 * at once (inline on desktop, the platform text dialog on mobile). Names are 1–16 code points,
 * trimmed, without control characters; an empty name is rejected with a shake. Duplicate names
 * are allowed. SAVE stores through {@link ProfileStore#rename}.
 */
public final class RenameDialog extends Modal {
    private final TextField field;
    private final Button cancel, save;
    private final AvatarView avatar;
    private String id;

    /** Creates the dialog for {@code host}. */
    public RenameDialog(Main game, ShellScreen host) {
        super(game, host);
        avatar = new AvatarView(game);
        field = widgets.add(new TextField(draw, fonts, "TAP TO NAME YOUR PLAYER"));
        field.setLabel("PLAYER NAME").setMaxLength(ProfileStore.MAX_NAME_CODEPOINTS);
        field.setListener(new TextField.Listener() {
            @Override
            public void committed(TextField f, String text) {
                Profile p = game().profiles().profile(id);
                if (p != null) avatar.replace(p.avatar, text, false);
            }
        });
        host.registerField(field);
        cancel = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, "CANCEL"));
        cancel.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
        save = widgets.add(new Button(draw, fonts, Button.Variant.PRIMARY, "SAVE"));
        save.setOnTap(new Runnable() {
            @Override
            public void run() {
                save();
            }
        });
    }

    private Main game() {
        return game;
    }

    /** Targets diver {@code profileId}; call before {@link ShellScreen#openModal}. */
    public RenameDialog target(String profileId) {
        id = profileId;
        return this;
    }

    @Override
    protected void onOpened() {
        Profile p = game.profiles().profile(id);
        field.setText(p != null && p.name != null ? p.name : "");
        if (p != null) avatar.set(p.avatar, p.name);
        field.beginEdit();
    }

    @Override
    protected void onClosed() {
        field.cancelEdit();
    }

    @Override
    protected void layout(float dy) {
        centerCard(820f, 450f, dy);
        float fh = Button.standardHeight();
        field.setBounds(x + 176f, y + h - 140f - fh, w - 176f - 48f, fh);
        float bw = Math.min(320f, (w - 48f * 2f - 32f) * 0.5f);
        cancel.setBounds(x + w - 48f - 2f * bw - 32f, y + 32f, bw, fh);
        save.setBounds(x + w - 48f - bw, y + 32f, bw, fh);
    }

    @Override
    protected void onUpdate(float dt) {
        avatar.update(dt);
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        drawTitle(b, "RENAME PLAYER", alpha);
        avatar.draw(b, x + 96f, field.centerY(), 104f, alpha);
        fonts.draw(b, Fonts.Role.UI_REGULAR, 18f, "1–16 CHARACTERS · DUPLICATES ARE FINE", field.x() + 4f,
            field.y() - 28f, Align.left, Theme.INK3, alpha);
    }

    private void save() {
        if (field.isEditing() && !field.commit()) return;
        String name = field.text();
        if (ProfileStore.sanitizeName(name) == null) {
            field.reject();
            return;
        }
        if (!game.profiles().rename(id, name)) {
            field.reject();
            return;
        }
        close();
        host.onDiversEdited();
    }
}
