package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 洞穴地图四周的 HUD：左上潜水员徽章，右上设置按钮，左下难度切换；“所有曲目”层右下有 SORT 按钮（打开排序和筛选面板）；
 * 主界面右下有“所有曲目”按钮，主线章节全部解锁后设置左边多一个故事模式 / 章节模式切换按钮；捏合到全图时顶部的提示亮 1 秒。
 *
 * <p>The HUD around a cave level (DESIGN §16, preview v17): profile badge (top left), Settings
 * (top right), lens control (bottom left), on ALL SONGS the SORT button (bottom right), which
 * opens the {@link SortPanel} (sort and filter), and the overview chip (lit 1 s by a pinch at the
 * overview since Kelvin 2026-10-17 19:39, which also dropped the over-pinch rise ring).
 * Kelvin 2026-10-16 trimmed it (no rise plate, recentre, match pill or DEMO chip) and then reshaped
 * the hub: no lens and no rise hint there, an ALL SONGS button at the bottom right (its only
 * entrance), and once MAIN's chapters are all open a story / chapter mode toggle left of
 * Settings. Everything is laid out against the uncovered map area every frame, so it follows the
 * card spring. No allocation per frame.
 */
final class MapHud {
    /** Actions the HUD triggers (implemented by the screen). */
    interface Actions {
        void openProfile();

        void openSettings();

        void lensChanged(Difficulty d);

        /** SORT (ALL SONGS): toggles the sort panel. */
        void openSort();

        /** Hub: the ALL SONGS button. */
        void openAllSongs();

        /** Hub: the story / chapter mode toggle. */
        void toggleMainMode();
    }

    private final Draw draw;
    private final Fonts fonts;
    private final Avatar avatars;
    private final MapCamera cam;
    private final MapRenderer r;
    private final Actions actions;

    final Badge badge;
    final Button settings;
    /** Hub: the ALL SONGS button (bottom right) and the main-line mode toggle (left of Settings). */
    final AllSongsButton allSongs;
    final ModeToggle mode;
    final LensSegment[] lens = new LensSegment[4];
    /** ALL SONGS: opens the sort panel. */
    final SortButton sort;
    private final Pressable[] controls;

    private Difficulty lensValue = Difficulty.SURFACE;
    private boolean hub, grid, gridFlat, sortOpen, modeShown, chapterMode;
    private int gridSort;
    /** Overview chip amount 0..1. */
    private float overview;

    // geometry (per frame) ------------------------------------------------------------
    private float mapRight, mapBottom, lensAlpha = 1f, hudAlpha = 1f, sortAlpha = 1f;
    /** HUD scale of this frame ({@link MapUi#k()}). */
    private float k = 1f;

    // profile cache ---------------------------------------------------------------------
    private final Avatar.Spec spec = new Avatar.Spec();
    private String name = "";
    private final StringBuilder shellText = new StringBuilder(16), depthText = new StringBuilder(16);
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private boolean hasProfile;
    private float time;
    private float unitU, sortU;

    // descent exit and the filter dot ----------------------------------------------------------
    private float exit;
    private boolean filterActive;
    /** The overview chip: on the hub without the rise hint (no rise from the hub, Kelvin 2026-10-16). */
    private static final String OVERVIEW_TEXT = "全图 OVERVIEW · 再捏合 ↑ 上浮", HUB_OVERVIEW_TEXT = "全图 OVERVIEW",
        SORT_TEXT = "SORT";
    private static final String ALL_TEXT = "ALL SONGS", ALL_ZH = "所有曲目", STORY_TEXT = "STORY MODE",
        STORY_ZH = "故事模式 · 点一下切换", CHAPTER_TEXT = "CHAPTER MODE", CHAPTER_ZH = "章节模式 · 点一下切换";

    /** Descent exit 0..1: elements slide 200 off their nearest edges and fade (§3.12). */
    void setExit(float e) {
        exit = MathUtils.clamp(e, 0f, 1f);
    }

    /** Filter state: the SORT button stays lit with its dot while a filter is on. */
    void setFilter(boolean active) {
        filterActive = active;
    }

    /** Hub mode toggle: shown (MAIN's chapters all open) and the current mode. */
    void setMode(boolean shown, boolean chapter) {
        modeShown = shown;
        chapterMode = chapter;
    }

    MapHud(Draw draw, Fonts fonts, Avatar avatars, MapCamera cam, MapRenderer r, Actions actions) {
        this.draw = draw;
        this.fonts = fonts;
        this.avatars = avatars;
        this.cam = cam;
        this.r = r;
        this.actions = actions;
        badge = new Badge(draw);
        badge.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.openProfile();
            }
        });
        settings = new Button(draw, fonts, Icons.Icon.GEAR);
        settings.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.openSettings();
            }
        });
        for (int i = 0; i < 4; i++) {
            final Difficulty d = Difficulty.byOrdinal(i);
            lens[i] = new LensSegment(draw, d);
            lens[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    actions.lensChanged(d);
                }
            });
        }
        sort = new SortButton(draw);
        sort.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.openSort();
            }
        });
        allSongs = new AllSongsButton(draw);
        allSongs.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.openAllSongs();
            }
        });
        mode = new ModeToggle(draw);
        mode.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.toggleMainMode();
            }
        });
        controls = new Pressable[]{badge, settings, mode, lens[0], lens[1], lens[2], lens[3], sort, allSongs};
        unitU = r.uiWidth("m", 1f);
        sortU = r.uiWidth(SORT_TEXT, 1f);
    }

    // ------------------------------------------------------------------ state
    /** Refreshes the badge from {@code p} (on profile changes only; may allocate). */
    void refresh(Profile p) {
        hasProfile = p != null;
        if (p == null) return;
        name = p.name != null ? p.name : "";
        char initial = name.isEmpty() ? 'K' : Character.toUpperCase(name.charAt(0));
        spec.set(p.avatar.seed, p.avatar.shape, p.avatar.pattern, p.avatar.palette, p.avatar.glyph, p.avatar.frame,
            initial);
        shellText.setLength(0);
        Fonts.appendGrouped(shellText, p.shells);
        int m = p.depthMeters();
        depthText.setLength(0);
        Fonts.appendGrouped(depthText, -m); // depths read negative (below the surface)
        shellU = r.numWidth(shellText, 1f);
        depthU = r.numWidth(depthText, 1f);
    }

    /** Badge number widths per unit of cap. */
    private float shellU, depthU;

    /** Sets the lens shown by the control. */
    void setLens(Difficulty d) {
        lensValue = d;
    }

    /** The level shown: hub / grid variants (level changes only). */
    void setLevel(MapWorld w) {
        hub = w.isHub();
        grid = w.isGrid();
        gridFlat = w.flat;
        gridSort = w.sort;
    }

    /** Overview chip amount 0..1. */
    void setOverview(float amount) {
        overview = amount;
    }

    /** Lights the SORT button while its panel is open. */
    void setSortOpen(boolean open) {
        sortOpen = open;
    }

    // ------------------------------------------------------------------ layout
    /**
     * Lays the HUD out against the uncovered map area (every metric × {@link MapUi#k()}).
     *
     * @param right    right edge of the map area (VR, or the card's left edge)
     * @param bottom   bottom of the map area (SB, or the sheet top)
     * @param cardOpen 0..1 card open amount (hides the lens control and SORT)
     * @param alpha    overall HUD alpha
     */
    void layout(float right, float bottom, float cardOpen, float alpha) {
        mapRight = right;
        mapBottom = bottom;
        k = MapUi.k();
        float ex = Interpolation.pow2In.apply(exit);
        float off = 200f * ex;
        hudAlpha = alpha * (1f - ex);
        float cardK = MathUtils.clamp(1f - cardOpen * 4f, 0f, 1f);
        lensAlpha = hub ? 0f : cardK * hudAlpha;
        sortAlpha = grid ? cardK * hudAlpha : 0f;
        float P = Ui.touchTarget();
        float sl = Ui.safeLeft(), st = Ui.safeTop(), sb = Ui.safeBottom();
        float icon = Button.ICON_DIAMETER * k, slot = Math.max(P, icon), gap = 16f * k;
        float cy = st - slot * 0.5f + off;
        float x = right - 24f * k - slot * 0.5f;
        settings.setBounds(x - icon * 0.5f, cy - icon * 0.5f, icon, icon);
        // the mode toggle (hub, once MAIN's chapters are all open) left of Settings
        float mw = 252f * k, mh = 64f * k;
        mode.setBounds(x - slot * 0.5f - gap - mw, cy - mh * 0.5f, mw, mh);
        mode.setMinHit(mw, Math.max(mh, P));
        mode.setVisible(hub && modeShown && hudAlpha > 0.05f);
        float clusterLeft = mode.isVisible() ? mode.x() : x - slot * 0.5f;
        float bh = MapStyle.BADGE_H * k;
        float bw = MathUtils.clamp(clusterLeft - 24f * k - sl, MapStyle.BADGE_MIN_W * k, MapStyle.BADGE_W * k);
        badge.setBounds(sl, st - bh + off, bw, bh);
        badge.setVisible(hudAlpha > 0.05f);
        // lens control (BL)
        float lx = sl, segW = MapStyle.LENS_SEG_W * k, lensH = MapStyle.LENS_H * k;
        for (int i = 0; i < 4; i++) {
            lens[i].setBounds(lx, sb - off, segW, lensH);
            lens[i].setVisible(lensAlpha > 0.05f);
            lx += segW;
        }
        // SORT (ALL SONGS): the bottom-right corner (Kelvin 2026-10-16); above the lens row's top
        // where the row has no room for both
        float ph = 80f * k, sw = 76f * k + sortU * MapUi.cap(20f, k) + 32f * k;
        float sx = right - 24f * k - sw;
        float sy = sx - 24f * k >= lx ? sb : sb + lensH + 16f * k;
        sort.setBounds(sx, sy - off, sw, ph);
        sort.setMinHit(sw, Math.max(ph, P));
        sort.setVisible(sortAlpha > 0.05f);
        // ALL SONGS (hub): the bottom-right corner, its only entrance (Kelvin 2026-10-16)
        float aw = 256f * k, ah = 88f * k;
        allSongs.setBounds(right - 24f * k - aw, sb - off, aw, ah);
        allSongs.setMinHit(aw, Math.max(ah, P));
        allSongs.setVisible(hub && hudAlpha > 0.05f);
    }

    /** Adds the HUD's screen rects to the renderer so map labels fade instead of showing through. */
    void occluders(MapRenderer r) {
        if (hudAlpha <= 0.01f) return;
        float m = 8f * k;
        r.addOccluder(badge.x() - m, badge.y() - m, badge.width() + 2f * m, badge.height() + 2f * m);
        float fl = mode.isVisible() ? mode.x() : settings.x();
        r.addOccluder(fl - m, settings.y() - m, settings.x() + settings.width() - fl + 2f * m, settings.height() + 2f * m);
        if (lensAlpha > 0.05f) {
            float lx = lens[0].x() - m;
            r.addOccluder(lx, lens[0].y() - m, lens[3].x() + lens[3].width() - lx + m, lens[0].height() + 2f * m);
        }
        if (sort.isVisible()) r.addOccluder(sort.x() - m, sort.y() - m, sort.width() + 2f * m, sort.height() + 2f * m);
        if (allSongs.isVisible()) {
            r.addOccluder(allSongs.x() - m, allSongs.y() - m, allSongs.width() + 2f * m, allSongs.height() + 2f * m);
        }
        if (overview > 0.05f) r.addOccluder(Ui.CX - 330f * k, chipTop() - 70f * k, 660f * k, 70f * k);
    }

    /** Bottom of the icon cluster's touch row (the filter panel drops from below it). */
    float clusterBottom() {
        return settings.centerY() - Math.max(Ui.touchTarget(), settings.height()) * 0.5f;
    }

    /** Top edge for the shared (global) toast on the map: under the badge. */
    float globalToastTop() {
        return badge.y() - 16f * k;
    }

    /** Left edge of the card's leader-line dim zone (no gauge: the card edge itself). */
    float gaugeLeft() {
        return mapRight;
    }

    private float chipTop() {
        return Ui.safeTop() - MapStyle.BADGE_H * k - 16f * k;
    }

    // ------------------------------------------------------------------ input
    /** Touch down; true when a HUD control claims it. */
    boolean touchDown(float x, float y, int ptr) {
        if (hudAlpha < 0.5f) return false;
        for (Pressable p : controls) {
            if (isLensPart(p) && lensAlpha < 0.5f) continue;
            if (p == sort && sortAlpha < 0.5f) continue;
            if (p.touchDown(x, y, ptr)) return true;
        }
        return false;
    }

    private boolean isLensPart(Pressable p) {
        return p == lens[0] || p == lens[1] || p == lens[2] || p == lens[3];
    }

    /** Touch dragged; true when owned by the HUD. */
    boolean touchDragged(float x, float y, int ptr) {
        boolean own = false;
        for (Pressable p : controls) own |= p.touchDragged(x, y, ptr);
        return own;
    }

    /** Touch up; true when owned by the HUD. */
    boolean touchUp(float x, float y, int ptr) {
        boolean own = false;
        for (Pressable p : controls) own |= p.touchUp(x, y, ptr);
        return own;
    }

    /** Cancels every press. */
    void cancel() {
        for (Pressable p : controls) p.cancel();
    }

    /** Desktop hover. */
    void mouseMoved(float x, float y) {
        for (Pressable p : controls) p.mouseMoved(x, y);
    }

    // ------------------------------------------------------------------ frame
    void update(float dt, float now) {
        time = now;
        for (Pressable p : controls) p.update(dt);
    }

    /** HUD atlas phase: badge panel, buttons, lens, SORT and the overview chip. */
    void drawShapes(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f) return;
        badge.drawBack(a);
        settings.draw(b, a);
        mode.draw(b, a);
        allSongs.draw(b, a);
        if (lensAlpha > 0.01f) for (LensSegment s : lens) s.draw(b, lensAlpha);
        if (sortAlpha > 0.01f) sort.draw(b, sortAlpha);
        if (overview > 0.01f) {
            float y = chipTop();
            float w = r.uiWidth(hub ? HUB_OVERVIEW_TEXT : OVERVIEW_TEXT, MapUi.cap(20f, k)) + 48f * k;
            draw.pill(Ui.CX - w * 0.5f, y - 56f * k, w, 56f * k, Theme.PANEL, 0.92f * overview * a);
            draw.roundRectStroke(Ui.CX - w * 0.5f, y - 56f * k, w, 56f * k, 28f * k, 2f, Theme.SURFACE_TOP,
                0.6f * overview * a);
        }
    }

    /** HUD avatar phase (its own texture). */
    void drawAvatar(Batch b) {
        if (hasProfile && hudAlpha > 0.01f) {
            avatars.draw(b, spec, badge.x() + 60f * k, badge.centerY(), 88f * k * badge.visualScale(), hudAlpha, time);
        }
    }

    /** HUD UI-raster phase. */
    void drawUi(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f) return;
        badge.drawUi(b, a);
        if (lensAlpha > 0.01f) for (LensSegment s : lens) s.drawText(b, lensAlpha);
        if (sortAlpha > 0.01f) sort.drawText(b, sortAlpha);
        mode.drawText(b, a);
        allSongs.drawText(b, a);
        if (overview > 0.01f) {
            float y = chipTop() - 28f * k;
            r.ui(b, hub ? HUB_OVERVIEW_TEXT : OVERVIEW_TEXT, MapUi.cap(20f, k), Ui.CX, y, Align.center | Fonts.VCENTER,
                Theme.INK1, overview * a);
        }
    }

    /** HUD NUMBER-raster phase. */
    void drawNumbers(Batch b) {
        if (hudAlpha > 0.01f) badge.drawNumbers(b, hudAlpha);
    }

    // =================================================================== controls
    /** Profile badge (§3.9): avatar, name and shells, the depth (no bar since 2026-10-17 14:10). Tap → Profile. */
    final class Badge extends Pressable {
        Badge(Draw draw) {
            super(draw);
        }

        /** Shell pill width at the current scale (icon, gap, count). */
        private float shellPillW() {
            return 34f * k + shellU * MapUi.cap(20f, k) + 18f * k;
        }

        /** Text column x (right of the avatar). */
        private float textX(float bx) {
            return bx + 120f * k;
        }

        void drawBack(float alpha) {
            if (!visible) return;
            float s = visualScale();
            float cx = centerX(), cy = centerY();
            float bw = w * s, bh = h * s;
            float bx = cx - bw * 0.5f, by = cy - bh * 0.5f;
            float rad = 18f * k;
            draw.panel(bx, by, bw, bh, rad, alpha * 0.95f);
            if (press > 0f || hover > 0f) draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, (0.06f * press + 0.04f * hover) * alpha);
            if (!hasProfile) return;
            // shell pill: icon and count
            float lw = shellPillW(), ph = 36f * k;
            float px = bx + bw - 20f * k - lw, py = by + bh - 20f * k - ph;
            draw.pill(px, py, lw, ph, Theme.GOLD, 0.16f * alpha);
            draw.shell(px + 22f * k, py + ph * 0.5f, 24f * k, Theme.GOLD, SHELL_RIB, alpha);
        }

        void drawUi(Batch b, float alpha) {
            if (!visible) return;
            float bx = x, by = y;
            if (!hasProfile) {
                r.ui(b, "NO DIVER", MapUi.cap(22f, k), textX(bx), by + h * 0.5f, Align.left | Fonts.VCENTER, Theme.INK2,
                    alpha);
                return;
            }
            float lw = shellPillW();
            r.uiFit(b, name, MapUi.cap(22f, k), textX(bx), by + h - 38f * k, Align.left | Fonts.VCENTER,
                Math.min(260f * k, w - 140f * k - lw - 20f * k), Theme.INK1, alpha);
            float ux = textX(bx) + depthU * MapUi.cap(24f, k) + 6f * k;
            r.ui(b, "m", MapUi.cap(20f, k), ux, depthY(by), Align.left | Fonts.VCENTER, Theme.INK2, alpha);
        }

        /** Centre y of the depth row: as far below the middle as the name row is above it. */
        private float depthY(float by) {
            return by + h * 0.5f - 22f * k;
        }

        void drawNumbers(Batch b, float alpha) {
            if (!visible || !hasProfile) return;
            float lw = shellPillW();
            r.num(b, shellText, MapUi.cap(20f, k), x + w - 20f * k - lw + 34f * k, y + h - 38f * k,
                Align.left | Fonts.VCENTER, Theme.GOLD, alpha);
            r.num(b, depthText, MapUi.cap(24f, k), textX(x), depthY(y), Align.left | Fonts.VCENTER, Theme.INK1, alpha);
        }

        @Override
        public void draw(Batch b, float alpha) {
            drawBack(alpha);
            drawUi(b, alpha);
            drawNumbers(b, alpha);
        }
    }

    /** One segment of the lens control (§3.9): full difficulty name, selected = filled. */
    final class LensSegment extends Pressable {
        final Difficulty d;

        LensSegment(Draw draw, Difficulty d) {
            super(draw);
            this.d = d;
        }

        @Override
        public void draw(Batch b, float alpha) {
            if (!visible) return;
            int o = d.ordinal();
            boolean sel = lensValue == d;
            float s = visualScale();
            float bw = w * s, bh = h * s, bx = centerX() - bw * 0.5f, by = centerY() - bh * 0.5f;
            float i2 = 2f * k, i6 = 6f * k, r18 = 18f * k, r14 = 14f * k;
            draw.roundRect(bx + i2, by + i2, bw - 2f * i2, bh - 2f * i2, r18, Theme.PANEL, 0.85f * alpha);
            float ix = bx + i6, iy = by + i6, iw = bw - 2f * i6, ih = bh - 2f * i6;
            if (sel) {
                draw.roundRect(ix, iy, iw, ih, r14, Theme.difficultyFill(o), alpha);
                Color border = Theme.difficultyBorder(o);
                if (border != null) {
                    draw.roundRectStroke(ix, iy, iw, ih, r14, Theme.difficultyBorderWidth(o), border, alpha);
                }
            } else {
                draw.roundRectStroke(ix, iy, iw, ih, r14, 2f, Theme.difficultyFill(o), (o == 3 ? 0.9f : 0.45f) * alpha);
                if (o == 3) draw.roundRectStroke(ix, iy, iw, ih, r14, 2f, Theme.GLITCH_M, 0.6f * alpha);
            }
            if (press > 0f) draw.roundRect(ix, iy, iw, ih, r14, Theme.INK1, 0.1f * press * alpha);
        }

        void drawText(Batch b, float alpha) {
            if (!visible) return;
            int o = d.ordinal();
            boolean sel = lensValue == d;
            Color c = sel ? Theme.difficultyText(o) : Theme.INK1;
            r.uiFit(b, Theme.DIFFICULTY_NAMES[o], MapUi.cap(20f, k), centerX(), centerY(), Align.center | Fonts.VCENTER,
                w - 24f * k, c, (sel ? 1f : 0.8f) * alpha);
        }
    }

    /**
     * SORT (ALL SONGS): a plate with the sort icon and {@code SORT}; lit in
     * {@code groundSig} while the panel is open or a filter is on, and a dot at its top right
     * while the layout, order or filter is not the default. Tap → the sort panel.
     */
    final class SortButton extends Pressable {
        SortButton(Draw draw) {
            super(draw);
        }

        @Override
        public void draw(Batch b, float alpha) {
            if (!visible) return;
            float s = visualScale();
            float bw = w * s, bh = h * s, bx = centerX() - bw * 0.5f, by = centerY() - bh * 0.5f, rad = 22f * k;
            boolean lit = sortOpen || filterActive;
            draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL, 0.9f * alpha);
            if (lit) draw.roundRect(bx, by, bw, bh, rad, Theme.GROUND_SIG, 0.16f * alpha);
            draw.roundRectStroke(bx, by, bw, bh, rad, 2f, lit ? Theme.GROUND_SIG : Theme.SURFACE_TOP,
                (lit ? 0.9f : 0.45f) * alpha);
            if (press > 0f || hover > 0f) draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, (0.08f * press + 0.05f * hover) * alpha);
            Icons.draw(draw.shapes(), Icons.Icon.SORT, bx + 40f * k, centerY(), 40f * k,
                lit ? Theme.GROUND_SIG : Theme.SURFACE_TOP, alpha);
            if (gridFlat || gridSort != MapWorld.SORT_DEFAULT || filterActive) {
                draw.disc(bx + bw - 16f * k, by + bh - 16f * k, 6f * k, Theme.GROUND_SIG, alpha);
            }
        }

        void drawText(Batch b, float alpha) {
            if (!visible) return;
            float c20 = MapUi.cap(20f, k);
            Color c = sortOpen || filterActive ? Theme.GROUND_SIG : Theme.INK1;
            r.uiFit(b, SORT_TEXT, c20, x + 76f * k, centerY(), Align.left | Fonts.VCENTER, w - 108f * k, c, alpha);
        }
    }

    /** A HUD plate: panel fill, a faint rim, the press / hover tint. */
    private void plate(Pressable p, float rad, float alpha) {
        float s = p.visualScale();
        float bw = p.width() * s, bh = p.height() * s, bx = p.centerX() - bw * 0.5f, by = p.centerY() - bh * 0.5f;
        draw.roundRect(bx, by, bw, bh, rad, Theme.PANEL, 0.9f * alpha);
        draw.roundRectStroke(bx, by, bw, bh, rad, 2f, Theme.SURFACE_TOP, 0.45f * alpha);
    }

    /** ALL SONGS (hub, bottom right): {@code ALL SONGS} over {@code 所有曲目} and a chevron; no icon (Kelvin 2026-10-16). */
    final class AllSongsButton extends Pressable {
        AllSongsButton(Draw draw) {
            super(draw);
        }

        @Override
        public void draw(Batch b, float alpha) {
            if (!visible || alpha <= 0.01f) return;
            plate(this, 22f * k, alpha);
            if (press > 0f || hover > 0f) draw.roundRect(x, y, w, h, 22f * k, Theme.INK1, (0.08f * press + 0.05f * hover) * alpha);
            Icons.draw(draw.shapes(), Icons.Icon.CHEVRON_RIGHT, x + w - 34f * k, centerY(), 30f * k, Theme.INK1, 0.8f * alpha);
        }

        void drawText(Batch b, float alpha) {
            if (!visible || alpha <= 0.01f) return;
            float tx = x + 28f * k, tw = w - 80f * k;
            r.uiFit(b, ALL_TEXT, MapUi.cap(22f, k), tx, centerY() + 14f * k, Align.left | Fonts.VCENTER, tw, Theme.INK1, alpha);
            r.uiFit(b, ALL_ZH, MapUi.cap(16f, k), tx, centerY() - 20f * k, Align.left | Fonts.VCENTER, tw, Theme.INK2, alpha);
        }
    }

    /**
     * The main-line mode toggle (hub, left of Settings, once MAIN's chapters and side stories are
     * all open): the current mode's icon (a path for story mode, four tiles for chapter mode), its
     * name and {@code 点一下切换}. Tap → the other mode.
     */
    final class ModeToggle extends Pressable {
        ModeToggle(Draw draw) {
            super(draw);
        }

        @Override
        public void draw(Batch b, float alpha) {
            if (!visible || alpha <= 0.01f) return;
            float rad = h * 0.5f;
            plate(this, rad, alpha);
            if (press > 0f || hover > 0f) draw.roundRect(x, y, w, h, rad, Theme.INK1, (0.08f * press + 0.05f * hover) * alpha);
            float cx = x + 36f * k, cy = centerY();
            Color c = Theme.GROUND_SIG;
            if (chapterMode) {
                float t = 11f * k, g = 4f * k;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        draw.roundRectStroke(cx - t - g * 0.5f + i * (t + g), cy - t - g * 0.5f + j * (t + g), t, t, 2f * k, 2f, c,
                            alpha);
                    }
                }
            } else {
                float d = 16f * k, e = 8f * k;
                draw.line(cx - d, cy - e, cx, cy + e, 3f * k, c, alpha);
                draw.line(cx, cy + e, cx + d, cy - e, 3f * k, c, alpha);
                draw.disc(cx - d, cy - e, 5f * k, c, alpha);
                draw.disc(cx, cy + e, 5f * k, c, alpha);
                draw.disc(cx + d, cy - e, 5f * k, c, alpha);
            }
        }

        void drawText(Batch b, float alpha) {
            if (!visible || alpha <= 0.01f) return;
            float tx = x + 66f * k, tw = w - 84f * k;
            r.uiFit(b, chapterMode ? CHAPTER_TEXT : STORY_TEXT, MapUi.cap(20f, k), tx, centerY() + 12f * k,
                Align.left | Fonts.VCENTER, tw, Theme.INK1, alpha);
            r.uiFit(b, chapterMode ? CHAPTER_ZH : STORY_ZH, MapUi.cap(14f, k), tx, centerY() - 16f * k,
                Align.left | Fonts.VCENTER, tw, Theme.INK2, alpha);
        }
    }
}
