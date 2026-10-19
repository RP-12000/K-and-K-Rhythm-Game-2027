package org.kelvinizer.screens;

import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.screens.shell.AvatarEditorSheet;
import org.kelvinizer.screens.shell.CreateDiverSheet;
import org.kelvinizer.screens.shell.DiverStats;
import org.kelvinizer.screens.shell.DiversTab;
import org.kelvinizer.screens.shell.ImportDialog;
import org.kelvinizer.screens.shell.LogTab;
import org.kelvinizer.screens.shell.ProfileHeader;
import org.kelvinizer.screens.shell.ProfileTab;
import org.kelvinizer.screens.shell.ScrollArea;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.screens.shell.StatsTab;
import org.kelvinizer.screens.shell.ThumbCache;
import org.kelvinizer.screens.shell.TopTab;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Segmented;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Ui;

/**
 * 玩家档案：左栏是潜水员信息（头像、名字、贝壳、深度、游玩次数和时长），右栏是 STATS / DST 10 / RECENT / PLAYER 四个标签页。
 * 也在这里新建、切换潜水员，点名字改名，用代码导入潜水员（PLAYER「玩家」页只切换，不编辑）。
 *
 * <p>Profiles and local accounts (DESIGN §8).
 *
 * <p><b>Layout.</b> 16:9 / 20:9 / 16:10: columns anchored to SL + 72 and SR − 72; the left
 * column is the {@link ProfileHeader} (avatar → editor, name → rename, level and EXP, depth and R,
 * rank emblem, next tier); the right column holds the tabs {@code STATS · DST 10 · RECENT · PLAYER}
 * (UI_BOLD 22) over a clipped, scrolling content area. 4:3: a header block on top, the tabs and
 * content below. Backdrop at the diver's depth (shafts × 0.6, {@code Blue.png} α .15) with the
 * avatar as heartbeat source; touches ping the water.
 *
 * <p><b>Accounts</b> (§8.3–§8.4): create (sheet), rename (tap the name), switch (PLAYER card: the
 * avatar flips, numbers count to the new diver's values in 400 ms, the backdrop moves to the new
 * depth, the header glitches), import codes. The PLAYER tab (DIVERS until Kelvin 2026-10-18 02:27)
 * only switches (00:34), so there is no delete; the active diver's code is exported from
 * Settings › OTHER.
 * Everything is cached per store revision; nothing is allocated per frame.
 */
public class ProfileScreen extends ShellScreen {
    /** Tab indices. */
    public static final int TAB_STATS = 0, TAB_TOP = 1, TAB_LOG = 2, TAB_DIVERS = 3;
    // LOG → RECENT (Kelvin 2026-10-17); TOP 10 → DST 10 and DIVERS → PLAYER (2026-10-18); all four in English in
    // both languages since 2026-10-18 14:29 (「档案界面保留原来的英语翻译（不受语言影响）」)
    private static final CharSequence[] TAB_NAMES = {"STATS", "DST 10", "RECENT", "PLAYER"};
    private static final float COUNT_TIME = 0.4f;

    private final ProfileHeader header;
    private final Segmented tabs;
    private final ProfileTab[] tabViews = new ProfileTab[4];
    private final ThumbCache thumbs = new ThumbCache();
    private final DiverStats from = new DiverStats(), to = new DiverStats(), live = new DiverStats();
    private final AvatarEditorSheet editor;
    private int tab = TAB_STATS;
    private int revision = Integer.MIN_VALUE;
    private String activeId;
    private float countT = 1f;

    /** Creates the screen once (Main caches it). */
    public ProfileScreen(final Main game) {
        super(game, Lang.of("PROFILE", "账号与统计"), "Blue.png", 0.15f, 0.6f, false);
        header = new ProfileHeader(game, widgets);
        editor = new AvatarEditorSheet(game, this);
        CreateDiverSheet create = new CreateDiverSheet(game, this);
        ImportDialog importDialog = new ImportDialog(game, this);
        header.avatarTap.setOnTap(new Runnable() {
            @Override
            public void run() {
                openModal(editor);
            }
        });
        header.nameTap.setOnTap(new Runnable() {
            @Override
            public void run() {
                header.beginRename(); // keyboard only, no dialog (Kelvin 2026-10-17)
            }
        });
        header.nameField.setListener(new TextField.Listener() {
            @Override
            public void committed(TextField field, String text) {
                ProfileStore store = game.profiles();
                if (!store.rename(store.activeId(), text)) field.reject();
            }
        });
        registerField(header.nameField);
        tabs = new Segmented(game.draw(), game.fonts(), TAB_NAMES);
        tabs.setLabelCap(22f);
        tabs.setListener(new Segmented.Listener() {
            @Override
            public void selected(Segmented control, int index) {
                setTab(index);
            }
        });
        tabViews[TAB_STATS] = new StatsTab(game, this, widgets);
        tabViews[TAB_TOP] = new TopTab(game, this, thumbs);
        tabViews[TAB_LOG] = new LogTab(game, this, thumbs);
        tabViews[TAB_DIVERS] = new DiversTab(game, this, widgets, create, importDialog);
    }

    /** Selects the tab shown next (e.g. DIVERS when opened from Welcome's MANAGE). */
    public void selectTab(int index) {
        tab = Math.max(0, Math.min(3, index));
        tabs.select(tab, false);
    }

    private void setTab(int index) {
        tabViews[tab].cancel(-1);
        tab = index;
        for (int i = 0; i < tabViews.length; i++) tabViews[i].setVisible(i == tab);
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void enterScreen() {
        tabs.select(tab, false);
        setTab(tab);
        refresh(false);
        game.backdrop().setFocus(header.avatarX(), header.avatarY());
    }

    @Override
    protected void leaveScreen() {
        tabs.cancel();
        header.cancelRename();
    }

    @Override
    protected void disposeScreen() {
        thumbs.dispose();
    }

    @Override
    public void onActiveDiverChanged() {
        refresh(true);
    }

    @Override
    public void onDiversEdited() {
        refresh(false);
    }

    /** Rebuilds every cached number and row; {@code switched} plays the flip and the count. */
    private void refresh(boolean switched) {
        ProfileStore store = game.profiles();
        Profile p = store.active();
        if (p == null) return;
        revision = store.revision();
        activeId = p.id;
        to.compute(p, game.library());
        if (switched) {
            from.set(live);
            countT = 0f;
        } else {
            from.set(to);
            live.set(to);
            countT = 1f;
        }
        header.refresh(p, live, switched);
        for (ProfileTab t : tabViews) t.refresh(p, live);
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void layout() {
        float top = Ui.safeTop() - HEADER_H;
        float bottom = Ui.safeBottom() + 24f;
        float left = Ui.safeLeft() + 72f, right = Ui.safeRight() - 72f;
        float tabsH = 72f;
        if (Ui.isTablet()) {
            float hb = header.layoutTall(left, top, right - left);
            float ty = hb - 32f - tabsH;
            tabs.setBounds(left, ty, Math.min(760f, right - left), tabsH);
            for (ProfileTab t : tabViews) t.layout(left, bottom, right - left, ty - 28f - bottom);
        } else {
            header.layoutWide(left, top, bottom);
            float rx = left + 560f + 72f;
            float rw = right - rx;
            float ty = top - tabsH;
            tabs.setBounds(rx, ty, Math.min(760f, rw), tabsH);
            for (ProfileTab t : tabViews) t.layout(rx, bottom, rw, ty - 28f - bottom);
        }
        game.backdrop().setFocus(header.avatarX(), header.avatarY());
    }

    @Override
    protected ScrollArea activeScroll() {
        return tabViews[tab].scroll();
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void update(float delta) {
        ProfileStore store = game.profiles();
        String id = store.activeId();
        if (store.revision() != revision || (id != null && !id.equals(activeId))) {
            refresh(activeId != null && id != null && !id.equals(activeId));
        }
        if (countT < 1f) {
            countT = Math.min(1f, countT + delta / COUNT_TIME);
            live.lerp(from, to, Anim.POW3_OUT.apply(countT));
        }
        thumbs.update();
        header.update(delta);
        tabs.update(delta);
        for (ProfileTab t : tabViews) {
            if (t.isVisible()) t.update(delta);
        }
    }

    @Override
    protected void drawScreen(float delta) {
        float e = enter(0.05f);
        header.draw(batch, 1f, e);
        float te = enter(0.12f);
        tabs.draw(batch, te);
        tabViews[tab].draw(batch, te);
    }

    // ------------------------------------------------------------------ input
    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        if (tabs.touchDown(x, y, pointer)) return true;
        if (tabViews[tab].touchDown(x, y, pointer)) return true;
        game.backdrop().touch(x, y);
        return false;
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        boolean t = tabs.touchDragged(x, y, pointer);
        return tabViews[tab].touchDragged(x, y, pointer) || t;
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        boolean t = tabs.touchUp(x, y, pointer);
        return tabViews[tab].touchUp(x, y, pointer) || t;
    }

    @Override
    protected void contentCancel(int pointer) {
        tabs.cancel();
        tabViews[tab].cancel(pointer);
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        tabs.mouseMoved(x, y);
        tabViews[tab].mouseMoved(x, y);
    }

    @Override
    protected boolean contentScrolled(float amountY) {
        return tabViews[tab].scrolled(amountY);
    }
}
