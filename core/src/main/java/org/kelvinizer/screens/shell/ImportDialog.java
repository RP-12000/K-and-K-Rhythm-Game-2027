package org.kelvinizer.screens.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;

import java.util.Arrays;
import java.util.Comparator;

/**
 * 导入潜水员的对话框：PASTE CODE 从剪贴板读取 {@code KK1:} 备份码，桌面版还列出导出文件夹里最新的三个文件供选择。
 * 导入的潜水员重名时改名为 {@code NAME (2)}；已有 12 个时按钮不可用并说明原因，无效的码直接在框内报错。
 *
 * <p>IMPORT (DESIGN §8.4, P1): PASTE CODE reads a {@code KK1:} code from the clipboard; on desktop
 * the newest files of the export folder are offered too. The imported diver gets a new id, a
 * name collision becomes {@code NAME (2)}, and the {@link ProfileStore#MAX_PROFILES} cap is enforced (the buttons are
 * disabled and the reason is shown). An invalid code shows the error inline.
 */
public final class ImportDialog extends Modal {
    private static final int MAX_FILES = 3;

    private final Button paste, cancel;
    private final Button[] files = new Button[MAX_FILES];
    private final FileHandle[] fileHandles = new FileHandle[MAX_FILES];
    private int fileCount;
    private String status = "";
    private Color statusColor = Theme.INK2;

    /** Creates the dialog for {@code host}. */
    public ImportDialog(Main game, ShellScreen host) {
        super(game, host);
        paste = widgets.add(new Button(draw, fonts, Button.Variant.PRIMARY, Lang.of("PASTE CODE", "粘贴备份码")));
        paste.setOnTap(new Runnable() {
            @Override
            public void run() {
                pasteCode();
            }
        });
        for (int i = 0; i < MAX_FILES; i++) {
            final int index = i;
            files[i] = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, ""));
            files[i].setLabelCap(20f);
            files[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    importFile(index);
                }
            });
        }
        cancel = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("CLOSE", "关闭")));
        cancel.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
    }

    @Override
    protected void onOpened() {
        ProfileStore store = game.profiles();
        boolean can = store.canCreate();
        status = can ? Lang.t("COPY A PLAYER CODE (KK1:…) ON ANY DEVICE, THEN PASTE IT HERE.",
            "在任意设备上复制备份码（KK1:…），再粘贴到这里。") : ShellFormat.deviceFull();
        statusColor = can ? Theme.INK2 : Theme.LOST;
        paste.setEnabled(can);
        FileHandle[] list = store.listExportFiles();
        Arrays.sort(list, new Comparator<FileHandle>() {
            @Override
            public int compare(FileHandle a, FileHandle b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        fileCount = Math.min(MAX_FILES, list.length);
        for (int i = 0; i < MAX_FILES; i++) {
            fileHandles[i] = i < fileCount ? list[i] : null;
            files[i].setVisible(i < fileCount);
            files[i].setEnabled(can);
            if (i < fileCount) files[i].setLabel(list[i].name().toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Override
    protected void layout(float dy) {
        float bh = Button.standardHeight();
        float filesH = fileCount > 0 ? 50f + fileCount * (bh + 16f) : 0f;
        centerCard(820f, 220f + bh + filesH + bh + 40f, dy);
        float bw = w - 96f;
        float top = y + h - 170f;
        paste.setBounds(x + 48f, top - bh, bw, bh);
        float fy = top - bh - 50f;
        for (int i = 0; i < fileCount; i++) {
            files[i].setBounds(x + 48f, fy - bh, bw, bh);
            fy -= bh + 16f;
        }
        cancel.setBounds(x + 48f, y + 32f, bw, bh);
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        drawTitle(b, Lang.t("IMPORT PLAYER", "导入玩家"), alpha);
        fonts.drawFit(b, Fonts.Role.UI, 20f, status, x + 40f, y + h - 112f, Align.left, w - 80f, 0.6f, statusColor,
            alpha);
        if (fileCount > 0) {
            fonts.draw(b, Fonts.Role.UI_LABEL, 16f, Lang.t("OR PICK AN EXPORTED FILE", "或者选一个导出的文件"), x + 48f,
                paste.y() - 22f, Align.left, Theme.INK3, alpha);
        }
    }

    private void pasteCode() {
        String code = null;
        try {
            code = Gdx.app.getClipboard().getContents();
        } catch (RuntimeException e) {
            Gdx.app.error("Shell", "clipboard unavailable", e);
        }
        if (code == null || code.trim().isEmpty()) {
            fail(Lang.t("THE CLIPBOARD IS EMPTY", "剪贴板是空的"));
            return;
        }
        try {
            done(game.profiles().importCode(code));
        } catch (ProfileStore.ImportException e) {
            fail(e.reason == ProfileStore.ImportException.Reason.LIMIT_REACHED
                ? ShellFormat.deviceFull()
                : Lang.t("THAT IS NOT A VALID PLAYER CODE", "这不是有效的备份码"));
        }
    }

    private void importFile(int i) {
        FileHandle f = fileHandles[i];
        if (f == null) return;
        try {
            done(game.profiles().importFile(f));
        } catch (ProfileStore.ImportException e) {
            fail(e.reason == ProfileStore.ImportException.Reason.LIMIT_REACHED
                ? ShellFormat.deviceFull()
                : Lang.t("THAT FILE COULD NOT BE READ", "这个文件读不出来"));
        }
    }

    private void fail(String why) {
        status = why;
        statusColor = Theme.LOST;
        paste.shake();
    }

    private void done(Profile p) {
        close();
        host.onDiversEdited();
        game.toasts().show(Lang.t("IMPORTED · ", "已导入 · ") + p.name, Theme.GROUND_SIG);
    }
}
