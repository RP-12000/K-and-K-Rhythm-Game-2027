package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 新建潜水员（{@code + NEW PLAYER}）的弹出卡片，内容与首次设置的「WHO'S DIVING?」相同：头像、配色和名字。
 * CREATE 保存新潜水员（最多 12 个）并立即切换过去；CANCEL 或点卡片外面关闭。
 *
 * <p>{@code + NEW PLAYER} (DESIGN §8.3): the §10.1 "who's diving?" card as a sheet. CREATE stores
 * the diver (at most {@link ProfileStore#MAX_PROFILES}) and makes it active through
 * {@link ShellScreen#switchDiver}; CANCEL or a tap outside closes it.
 */
public final class CreateDiverSheet extends Modal {
    private static final float PAD = 56f;

    private final DiverForm form;
    private final Button cancel, create;
    private float formScale = 1f;

    /** Creates the sheet for {@code host}. */
    public CreateDiverSheet(Main game, ShellScreen host) {
        super(game, host);
        form = new DiverForm(game, host, widgets);
        cancel = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("CANCEL", "取消")));
        cancel.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
        create = widgets.add(new Button(draw, fonts, Button.Variant.PRIMARY, Lang.of("CREATE", "创建")));
        create.setOnTap(new Runnable() {
            @Override
            public void run() {
                create();
            }
        });
    }

    @Override
    protected void onOpened() {
        ProfileStore store = game.profiles();
        form.reset(null, store.defaultName(), null, 0);
        create.setEnabled(store.canCreate());
    }

    @Override
    protected float slideDistance() {
        return 120f;
    }

    @Override
    protected void layout(float dy) {
        float availW = Ui.safeRight() - Ui.safeLeft() - 48f;
        float availH = Ui.safeTop() - Ui.safeBottom() - 48f;
        float natW = DiverForm.naturalWidth() + 2f * PAD;
        formScale = Math.min(1f, Math.min(availW / natW, (availH - 110f - Button.standardHeight() - 40f)
            / DiverForm.naturalHeight()));
        formScale = Math.max(0.6f, formScale);
        float fw = 280f * formScale + 72f * formScale + 560f;
        w = Math.min(availW, Math.max(fw + 2f * PAD, 900f));
        h = Math.min(availH, 110f + DiverForm.naturalHeight() * formScale + Button.standardHeight() + 40f);
        x = Ui.CX - w * 0.5f;
        y = Ui.CY - h * 0.5f + dy;
        float fx = x + (w - fw) * 0.5f;
        form.layout(fx, y + h - 100f, formScale);
        float bw = Math.min(360f, (w - 2f * PAD - 32f) * 0.5f);
        float by = y + 28f;
        cancel.setBounds(x + w * 0.5f - 16f - bw, by, bw, Button.standardHeight());
        create.setBounds(x + w * 0.5f + 16f, by, bw, Button.standardHeight());
    }

    @Override
    protected void onUpdate(float dt) {
        form.update(dt);
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        drawTitle(b, Lang.t("NEW PLAYER", "新建玩家"), alpha);
        ProfileStore store = game.profiles();
        String full = store.canCreate() ? "" : ShellFormat.deviceFull();
        fonts.draw(b, Fonts.Role.UI_LABEL, 18f, full, x + w - 40f, y + h - 40f, Align.right | Align.top, Theme.LOST,
            alpha);
        form.draw(b, alpha);
    }

    private void create() {
        if (!form.commitName()) return;
        ProfileStore store = game.profiles();
        Profile p = store.create(form.name(), form.avatar().copy());
        if (p == null) {
            create.shake();
            game.toasts().show(ShellFormat.diversMax(), Theme.LOST);
            return;
        }
        close();
        host.switchDiver(p.id);
        game.toasts().show(Lang.t("NEW PLAYER · ", "新玩家 · ") + p.name, Theme.GROUND_SIG);
    }
}
