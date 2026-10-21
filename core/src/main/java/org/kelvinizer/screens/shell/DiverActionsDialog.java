package org.kelvinizer.screens.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Clipboard;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;

/**
 * 潜水员卡片的 ⋯ 菜单：RENAME 改名，EXPORT CODE 复制 {@code KK1:} 备份码（桌面版还写入导出文件夹），DELETE 删除。
 * 只剩一个潜水员时不能删除；删除前要在确认框里按住 1.5 秒。
 *
 * <p>The {@code ⋯} actions of a DIVERS card (DESIGN §8.2): RENAME, EXPORT (P1, §8.4: copies the
 * {@code KK1:} code to the clipboard; desktop also writes {@code KandKGame/export/}) and DELETE.
 * DELETE is disabled for the last diver (it shakes on tap) and otherwise opens the
 * hold-to-confirm {@link DeleteDialog}.
 */
public final class DiverActionsDialog extends Modal {
    private final Button rename, export, delete;
    private final AvatarView avatar;
    private final StringBuilder meta = new StringBuilder(64);
    private final RenameDialog renameDialog;
    private final DeleteDialog deleteDialog;
    private String id;
    private String name = "";

    /** Creates the dialog (and its rename / delete follow-ups) for {@code host}. */
    public DiverActionsDialog(Main game, ShellScreen host, RenameDialog renameDialog) {
        super(game, host);
        this.renameDialog = renameDialog;
        this.deleteDialog = new DeleteDialog(game, host);
        avatar = new AvatarView(game);
        rename = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, "RENAME"));
        rename.setOnTap(new Runnable() {
            @Override
            public void run() {
                DiverActionsDialog.this.host.openModal(DiverActionsDialog.this.renameDialog.target(id));
            }
        });
        export = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, "EXPORT CODE"));
        export.setOnTap(new Runnable() {
            @Override
            public void run() {
                export();
            }
        });
        delete = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, "DELETE"));
        delete.setAccent(Theme.LOST);
        delete.setOnTap(new Runnable() {
            @Override
            public void run() {
                DiverActionsDialog.this.host.openModal(deleteDialog.target(id));
            }
        });
    }

    /** Targets diver {@code profileId}; call before {@link ShellScreen#openModal}. */
    public DiverActionsDialog target(String profileId) {
        id = profileId;
        return this;
    }

    @Override
    protected void onOpened() {
        Profile p = game.profiles().profile(id);
        if (p == null) {
            close();
            return;
        }
        name = p.name != null ? p.name : "";
        avatar.set(p.avatar, name);
        meta.setLength(0);
        ShellFormat.appendDiverMeta(meta, p);
        delete.setEnabled(game.profiles().count() > 1);
    }

    @Override
    protected void layout(float dy) {
        float bh = Button.standardHeight();
        centerCard(760f, 210f + 3f * bh + 2f * 20f + 40f, dy);
        float bw = w - 96f;
        float by = y + 40f;
        delete.setBounds(x + 48f, by, bw, bh);
        export.setBounds(x + 48f, by + bh + 20f, bw, bh);
        rename.setBounds(x + 48f, by + 2f * (bh + 20f), bw, bh);
    }

    @Override
    protected void onUpdate(float dt) {
        avatar.update(dt);
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        float top = y + h - 40f;
        avatar.draw(b, x + 48f + 60f, top - 62f, 120f, alpha);
        float tx = x + 48f + 150f;
        fonts.drawFit(b, Fonts.Role.UI_BOLD, 32f, name, tx, top - 44f, Align.left, w - (tx - x) - 48f, 0.6f,
            Theme.INK1, alpha);
        fonts.draw(b, Fonts.Role.UI, 20f, meta, tx, top - 96f, Align.left, Theme.INK2, alpha);
        if (!delete.isEnabled()) {
            fonts.draw(b, Fonts.Role.UI_REGULAR, 16f, "THE LAST PLAYER CANNOT BE DELETED", x + w * 0.5f,
                delete.y() - 6f, Align.center | Align.top, Theme.INK3, alpha);
        }
    }

    private void export() {
        if (!exportCode(game, id)) {
            export.shake();
            return;
        }
        close();
    }

    /**
     * Exports diver {@code id} (DESIGN §8.4, P1): copies its {@code KK1:} code to the clipboard and,
     * where the platform allows, also writes it to {@code KandKGame/export/}; a toast reports what
     * actually happened (copied and saved, copied only, saved only, or nothing). Shared by this
     * dialog and Settings → ACCOUNT. Returns false when the code reached neither the clipboard nor
     * a file (unknown diver, serialisation failure, no clipboard and no writable storage).
     */
    public static boolean exportCode(Main game, String id) {
        ProfileStore store = game.profiles();
        String code = store.exportCode(id);
        if (code == null) {
            game.toasts().show(Lang.t("EXPORT FAILED", "导出失败"), Theme.LOST);
            return false;
        }
        boolean copied = false;
        try {
            Clipboard clip = Gdx.app.getClipboard();
            if (clip != null) {
                clip.setContents(code);
                copied = true;
            }
        } catch (RuntimeException e) {
            Gdx.app.error("Shell", "clipboard unavailable", e);
        }
        FileHandle f = store.exportToFile(id);
        String file = f != null ? "EXPORT/" + f.name().toUpperCase(java.util.Locale.ROOT) : null;
        if (copied && file != null) {
            game.toasts().show(Lang.t("CODE COPIED · SAVED TO ", "备份码已复制 · 已存到 ") + file, Theme.GROUND_SIG);
        } else if (copied) {
            game.toasts().show(Lang.t("CODE COPIED TO CLIPBOARD", "备份码已复制到剪贴板"), Theme.GROUND_SIG);
        } else if (file != null) {
            game.toasts().show(Lang.t("COULD NOT COPY · CODE SAVED TO ", "没能复制 · 备份码已存到 ") + file, Theme.GOLD);
        } else {
            game.toasts().show(Lang.t("COULD NOT COPY THE CODE", "没能复制备份码"), Theme.LOST);
            return false;
        }
        return true;
    }

    /**
     * Destructive confirmation (DESIGN §8.3): hold DELETE for 1.5 s; the card shakes harder as the
     * ring fills. Deleting the active diver switches to the most recently played remaining one.
     */
    public static final class DeleteDialog extends Modal {
        private final Button hold, cancel;
        private final AvatarView avatar;
        private final StringBuilder warning = new StringBuilder(96);
        private String id;
        private String name = "";

        /** Creates the dialog for {@code host}. */
        public DeleteDialog(Main game, ShellScreen host) {
            super(game, host);
            avatar = new AvatarView(game);
            hold = widgets.add(new Button(draw, fonts, Button.Variant.DESTRUCTIVE, "HOLD TO DELETE"));
            hold.setOnTap(new Runnable() {
                @Override
                public void run() {
                    confirm();
                }
            });
            cancel = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, "KEEP"));
            cancel.setOnTap(new Runnable() {
                @Override
                public void run() {
                    close();
                }
            });
        }

        /** Targets diver {@code profileId}. */
        public DeleteDialog target(String profileId) {
            id = profileId;
            return this;
        }

        @Override
        protected void onOpened() {
            Profile p = game.profiles().profile(id);
            if (p == null) {
                close();
                return;
            }
            name = p.name != null ? p.name : "";
            avatar.set(p.avatar, name);
            warning.setLength(0);
            ShellFormat.appendShells(warning, p.shells).append(" · ");
            Fonts.appendInt(warning, p.playCount).append(p.playCount == 1 ? " DIVE" : " DIVES");
            hold.setEnabled(game.profiles().count() > 1);
        }

        @Override
        protected void layout(float dy) {
            float bh = Button.standardHeight();
            centerCard(860f, 366f + bh, dy);
            float bw = Math.min(380f, (w - 96f - 32f) * 0.5f);
            cancel.setBounds(x + 48f, y + 40f, w - 96f - bw - 32f, bh);
            hold.setBounds(x + w - 48f - bw, y + 40f, bw, bh);
        }

        @Override
        protected void onUpdate(float dt) {
            avatar.update(dt);
            float p = hold.holdProgress();
            cardShakeX = p <= 0f ? 0f : (float) Math.sin(time * 70f) * (2f + 14f * p * p);
        }

        @Override
        protected void drawContent(Batch b, float alpha) {
            drawTitle(b, "DELETE PLAYER?", alpha);
            float cx = x + cardShakeX;
            float top = y + h - 110f;
            avatar.draw(b, cx + 48f + 56f, top - 60f, 112f, alpha);
            float tx = cx + 48f + 140f;
            fonts.drawFit(b, Fonts.Role.UI_BOLD, 32f, name, tx, top - 36f, Align.left, w - 240f, 0.6f, Theme.INK1,
                alpha);
            fonts.drawFit(b, Fonts.Role.UI, 20f, warning, tx, top - 76f, Align.left, w - 240f, 0.6f, Theme.INK2,
                alpha);
            // two lines so the irreversible warning keeps its full 20 cap (DESIGN §1.1 essential text)
            fonts.drawFit(b, Fonts.Role.UI, 20f, "EVERY RECORD GOES WITH THEM.", tx, top - 112f, Align.left,
                w - 240f, 0.9f, Theme.LOST, alpha);
            fonts.drawFit(b, Fonts.Role.UI, 20f, "THIS CANNOT BE UNDONE.", tx, top - 146f, Align.left, w - 240f, 0.9f,
                Theme.LOST, alpha);
            draw.roundRectStroke(cx, y, w, h, 18f, 2f, Theme.LOST, (0.25f + 0.6f * hold.holdProgress()) * alpha);
        }

        private void confirm() {
            String doomed = id;
            close();
            if (host.deleteDiver(doomed)) {
                game.toasts().show("PLAYER DELETED", Theme.LOST);
            } else {
                game.toasts().show("THE LAST PLAYER CANNOT BE DELETED", Theme.LOST);
            }
        }
    }
}
