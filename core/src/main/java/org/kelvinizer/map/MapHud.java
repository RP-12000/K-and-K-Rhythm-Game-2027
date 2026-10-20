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
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 洞穴地图四周的 HUD：左上玩家块（白圈圆头像、名字、两头尖的深度标签，2026-10-20 14:10 起不画框，顶边和贝壳胶囊对齐），
 * 右上设置按钮和它左边的贝壳胶囊（只显示贝壳数，点了不跳转），左下难度切换；“所有曲目”层右下有 SORT 按钮（打开排序和筛选面板）；
 * 主界面右下有“所有曲目”按钮，主线章节全部解锁后设置左边多一个故事模式 / 章节模式切换按钮；双指静止按住时两指中间出现上浮进度圈。
 *
 * <p>The HUD around a cave level (DESIGN §16, preview v17): the player block (top left: a round avatar, the name and a
 * pointed depth tag) and the shell capsule (beside the depth tag since 2026-10-20 15:36), which split the profile
 * badge like the result screen's corners (Kelvin 2026-10-19 06:04 「就是地图界面上也这样分开到两个角落」, 06:28), Settings (top right),
 * lens control (bottom left),
 * on ALL SONGS the SORT button (bottom right), which
 * opens the {@link SortPanel} (sort and filter), and the rise ring between two fingers held still
 * (Kelvin 2026-10-19 18:46, which dropped the overview chip of 2026-10-17 19:39).
 * Kelvin 2026-10-16 trimmed it (no rise plate, recentre, match pill or DEMO chip) and then reshaped
 * the hub: no lens and no rise hint there, an ALL SONGS button at the bottom right (its only
 * entrance), and once MAIN's chapters are all open a story / chapter mode toggle left of
 * Settings. Kelvin 2026-10-20 03:26 enlarged the frame and the capsule to the green boxes of their
 * picture (「现在特别小的名字和低的depth就在角落缩成一团了」), and since 03:38 the capsule only shows the
 * count (「贝壳就不要再包括通往存档的跳转了」). At 14:10 the frame gave way to the design thread's plan 5 (Kelvin 13:22
 * 「个人profile的那个稍微高了一点，以及观感不佳试着重新设计一下」): no panel, the block 100 high from the top of Settings.
 * At 15:10 the capsule shrank and the depth grew (「我感觉贝壳有点太大了，喧宾夺主了，我觉得深度才应该有那么大」); at 15:36
 * the name got plan 6's band and the capsule moved beside the depth tag (「把贝壳移动到depth旁边」).
 * Everything is laid out against the uncovered map area every frame, so it follows the card spring. No allocation per
 * frame.
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

    /** The player frame (top left; tap → Profile). */
    final Badge badge;
    /** The shell capsule (left of Settings or the mode toggle; not a control since 2026-10-20 03:38). */
    final ShellCapsule shells;
    final Button settings;
    /** Hub: the ALL SONGS button (bottom right) and the main-line mode toggle (left of Settings). */
    final AllSongsButton allSongs;
    final ModeToggle mode;
    final LensSegment[] lens = new LensSegment[4];
    /** ALL SONGS: opens the sort panel. */
    final SortButton sort;
    private final Pressable[] controls;

    private Difficulty lensValue = Difficulty.SURFACE;
    private boolean hub, grid, sortOpen, modeShown, chapterMode;
    private int gridSort;
    /** ALL SONGS: right edge of the grid (SORT aligns to it). */
    private float gridRight;
    /** Rise ring: fill (≤ 0 hidden, 1 closed; it fades in over its first eighth) and centre, between the fingers. */
    private float riseFill, riseX, riseY;
    /** Radius of the rise ring (× k). */
    private static final float RISE_R = 70f;

    // geometry (per frame) ------------------------------------------------------------
    private float mapRight, mapBottom, lensAlpha = 1f, hudAlpha = 1f, sortAlpha = 1f;
    /** HUD scale of this frame ({@link MapUi#k()}). */
    private float k = 1f;

    // profile cache ---------------------------------------------------------------------
    private final Avatar.Spec spec = new Avatar.Spec();
    private String name = "";
    private final StringBuilder shellText = new StringBuilder(16), depthText = new StringBuilder(20);
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private static final String NO_DIVER = "NO DIVER";
    /** Longest name before it shrinks (× k). */
    private static final float NAME_MAX = 560f;
    /**
     * The player block (× k; Kelvin 2026-10-20 14:10, the design thread's plan 5, laid out on 16:10 from the block's
     * top left at (36, 36)): how far in from the screen's left margin it starts, the avatar disc's radius, its white ring's
     * radius and width and the avatar drawn in it; the name's left edge, baseline under the top and cap; the depth
     * tag's left tip, height (its bottom is the block's), tip depth and inner padding and the number's cap, on a
     * baseline {@link #DEPTH_BASE} under the top (a tracked DEPTH label stood before the number until 17:34). Since
     * Kelvin 2026-10-20 15:36 (「用最下面一版的深度大小」, after 15:10 「我觉得深度才应该有那么大」) the depth leads: the tag
     * 62 high with a number cap of 40 (42 and 24 before), the name's cap 24 (31) so the block stays 100 high. Since
     * Kelvin 2026-10-20 15:46 (「头像圈再放大一点，直径跟名字区域+深度高度加在一起相等」, the depth number 「稍微小一点」)
     * the white ring's outer edge is the disc's, so the circle is exactly as tall as the name's band and the tag
     * together (the block's 100; the ring 97 across before), the avatar 60 (58), and the number's cap 36 (40). Since
     * Kelvin 2026-10-20 16:05 (「把头像圈直径扩大1.5倍我看看」) the circle is 150 across and so is the block; the name and
     * the tag, a column {@link #COL_H} high, sit centred against it, {@link #COL_Y} under the top, and start 50 further
     * right; the name's, band's and tag's measures below are from the column's top. Since Kelvin 2026-10-20 16:37
     * (「玩家名称稍微往上挪一点，现在离depth太近了」) the name's baseline is 21 under the column's top (29), and since 16:39
     * (「名字左对齐depth六边形左端点」) its first stroke lines up with the tag's left tip: it starts at 165 (168), the glyphs'
     * own side bearing to the left of the tip at 166. All of it is drawn at {@link #BLOCK_K} since 17:34.
     */
    private static final float BLOCK_IN = 12f, AV_R = 75f, RING_R = 73.5f, RING_W = 3f, AV_ART = 90f, NAME_X = 165f,
        NAME_BASE = 21f, NAME_CAP = 24f, TAG_X = 166f, TAG_H = 62f, TAG_TIP = 22f, TAG_PAD = 14f, DEPTH_CAP = 36f,
        DEPTH_BASE = 87f, COL_Y = 25f, COL_H = 100f;
    /**
     * The player block and the shell capsule beside it are drawn at this much of their measures here (Kelvin
     * 2026-10-20 17:34 「depth那个字不要了，然后整体等比例缩小到原来的75%」): the avatar, the name and its band, the depth
     * tag, the capsule, the gaps and shadows between them. The block's top left corner ({@link #BLOCK_IN} in, at the
     * top of Settings), the name's room up to Settings and ALL SONGS' gap under the block stay as they were.
     */
    private static final float BLOCK_K = 0.75f;
    /** {@link #k} times {@link #BLOCK_K}: the scale of the player block and the shell capsule this frame. */
    private float bk = BLOCK_K;
    /** Plan 5's colours: the avatar disc, the shadows (under the disc and the tag, under the name), the tag's fill and edge. */
    private static final Color AV_DISC = Theme.rgb(0x05091C, 1f), SHADE = Theme.rgb(0x000012, 1f),
        NAME_SHADE = Theme.rgb(0x000014, 1f), TAG_FILL = Theme.rgb(0x080D26, 1f), TAG_EDGE = Theme.rgb(0x38B6FF, 1f);
    /** The name's soft shadow: four faint copies about 2 under it (× k). */
    private static final float[] SHADE_DX = {-1.5f, 1.5f, -1f, 1f}, SHADE_DY = {-1.5f, -1.5f, -3f, -3f};
    /**
     * The name's band (× k; Kelvin 2026-10-20 15:36 「之前的号方案的那种光带加在名字上吧」, the band of the design thread's
     * plans 3 and 6): its top and bottom under the column's top, how far it runs past the name, and its alpha; navy from
     * the avatar's centre, fading out to the right from 0.6 of the name. Since Kelvin 2026-10-20 16:37 it moves up 8 with
     * the name (0 to 36 before), and since 16:39 (「那个banner稍微深一点」) its alpha is 0.95 (0.84).
     */
    private static final float BAND_TOP = -8f, BAND_BOTTOM = 28f, BAND_RUN = 140f, BAND_A = 0.95f;
    private static final Color BAND_CLEAR = Theme.rgb(0x080D26, 0f);
    /** Gap between the depth tag's right tip and the shell capsule beside it (× k; Kelvin 2026-10-20 15:36). */
    private static final float SHELL_GAP = 16f;
    /** Room for the name this frame: from its left edge to 24 short of Settings or the mode toggle. */
    private float nameRoom;
    /** Right end of the name's band this frame (24 short of Settings or the mode toggle). */
    private float bandRight;
    /**
     * The shell capsule's insides (× k; 03:26, about their second green box; smaller since 15:10 and beside the depth
     * tag since 15:36): padding, icon, gap, count cap, padding.
     */
    private static final float CAP_PAD_L = 14f, CAP_ICON = 26f, CAP_GAP = 7f, COUNT_CAP = 22f, CAP_PAD_R = 16f;
    private boolean hasProfile;
    private float time;
    private float sortU;
    /** Width per unit of cap kept for the depth whatever its value ({@link #depthRoomU()}). */
    private float depthRoomU;

    // descent exit and the filter dot ----------------------------------------------------------
    private float exit;
    private boolean filterActive;
    private static final String SORT_TEXT = "SORT";
    private static final String ALL_TEXT = "ALL SONGS", ALL_ZH = "所有曲目", STORY_TEXT = "STORY MODE",
        STORY_ZH = "故事模式 · 点一下切换", CHAPTER_TEXT = "CHAPTER MODE", CHAPTER_ZH = "章节模式 · 点一下切换",
        SWITCH_TEXT = "TAP TO SWITCH";

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
        Runnable profile = new Runnable() {
            @Override
            public void run() {
                actions.openProfile();
            }
        };
        badge = new Badge(draw);
        badge.setOnTap(profile);
        shells = new ShellCapsule(draw);
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
        sortU = r.uiWidth(SORT_TEXT, 1f);
        depthRoomU = depthRoomU();
    }

    // ------------------------------------------------------------------ state
    /** Refreshes the frame and the capsule from {@code p} (on profile changes only; may allocate). */
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
        Fonts.appendGrouped(depthText, -m).append(" m"); // depths read negative (below the surface)
        shellU = r.numWidth(shellText, 1f);
        depthU = r.numWidth(depthText, 1f);
        nameU = r.boldWidth(name, 1f);
    }

    /** Text widths per unit of cap (shell count, depth, name). */
    private float shellU, depthU, nameU;

    /** Sets the lens shown by the control. */
    void setLens(Difficulty d) {
        lensValue = d;
    }

    /** The level shown: hub / grid variants (level changes only). */
    void setLevel(MapWorld w) {
        hub = w.isHub();
        grid = w.isGrid();
        gridSort = w.sort;
    }

    /** ALL SONGS: right edge of the grid, which SORT lines up with. */
    void setGridRight(float right) {
        gridRight = right;
    }

    /** Rise ring: fill (≤ 0 hidden, 1 closed) and centre (between the two fingers). */
    void setRiseHold(float fill, float x, float y) {
        riseFill = fill;
        riseX = x;
        riseY = y;
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
     * @param cardOpen 0..1 card open amount (hides the lens control and SORT, except on ALL SONGS, where the card stays
     *                 open beside them)
     * @param alpha    overall HUD alpha
     */
    void layout(float right, float bottom, float cardOpen, float alpha) {
        mapRight = right;
        mapBottom = bottom;
        k = MapUi.k();
        bk = k * BLOCK_K;
        float ex = Interpolation.pow2In.apply(exit);
        float off = 200f * ex;
        hudAlpha = alpha * (1f - ex);
        float cardK = grid ? 1f : MathUtils.clamp(1f - cardOpen * 4f, 0f, 1f);
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
        // the player block from the top of Settings down, BLOCK_IN in from the screen's left margin (24 and EXTRA EDGE
        // PADDING; a cutout no longer pushes it in, Kelvin 2026-10-20 14:39), the name and its band never reaching
        // Settings (or the mode toggle); the shell capsule right of the depth tag, centred on it (Kelvin 2026-10-20
        // 15:36 「把贝壳移动到depth旁边」)
        float clusterLeft = mode.isVisible() ? mode.x() : settings.x();
        float fh = MapStyle.FRAME_H * bk, fx = Ui.left() + Ui.MIN_MARGIN + Ui.edgePadding() + BLOCK_IN * k;
        bandRight = clusterLeft - 24f * k;
        nameRoom = bandRight - fx - NAME_X * bk;
        badge.setBounds(fx, cy + icon * 0.5f - fh, blockW(), fh);
        badge.setVisible(hudAlpha > 0.05f);
        float ch = MapStyle.CAPSULE_H * bk;
        shells.setBounds(fx + TAG_X * bk + tagW() + SHELL_GAP * bk, tagBottom() + (TAG_H * bk - ch) * 0.5f, capsuleW(),
            ch);
        shells.setVisible(hasProfile && hudAlpha > 0.05f);
        // lens control (BL)
        float lx = sl, segW = MapStyle.LENS_SEG_W * k, lensH = MapStyle.LENS_H * k;
        for (int i = 0; i < 4; i++) {
            lens[i].setBounds(lx, sb - off, segW, lensH);
            lens[i].setVisible(lensAlpha > 0.05f);
            lx += segW;
        }
        // SORT (ALL SONGS): the bottom right of the grid's side, flush with its right edge (Kelvin 2026-10-19); above
        // the lens row's top where the row has no room for both
        float ph = 80f * k, sw = 76f * k + sortU * MapUi.cap(20f, k) + 32f * k;
        float sx = (grid ? gridRight : right - 24f * k) - sw;
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
        if (shells.isVisible()) {
            r.addOccluder(shells.x() - m, shells.y() - m, shells.width() + 2f * m, shells.height() + 2f * m);
        }
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
    }

    /** Bottom of the icon cluster's touch row (the filter panel drops from below it). */
    float clusterBottom() {
        return settings.centerY() - Math.max(Ui.touchTarget(), settings.height()) * 0.5f;
    }

    /** Top edge for the shared (global) toast on the map: under the player frame. */
    float globalToastTop() {
        return badge.y() - 16f * k;
    }

    /** Left edge of the card's leader-line dim zone (no gauge: the card edge itself). */
    float gaugeLeft() {
        return mapRight;
    }

    /** Shell capsule width: padding, icon, gap, count, padding; at least {@link MapStyle#CAPSULE_MIN_W}. */
    private float capsuleW() {
        return Math.max(MapStyle.CAPSULE_MIN_W * bk, capsuleContentW());
    }

    private float capsuleContentW() {
        return (CAP_PAD_L + CAP_ICON + CAP_GAP + CAP_PAD_R) * bk + shellU * bcap(COUNT_CAP);
    }

    /** Top of Settings at rest, where the player block starts. */
    private static float rowTop(float k) {
        float icon = Button.ICON_DIAMETER * k;
        return Ui.safeTop() - Math.max(Ui.touchTarget(), icon) * 0.5f + icon * 0.5f;
    }

    /** Bottom of the depth tag (and of the name and tag column) this frame. */
    private float tagBottom() {
        return badge.y() + badge.height() - (COL_Y + COL_H) * bk;
    }

    /** Bottom of the player block at rest, as {@link #layout} places it; ALL SONGS starts under it. */
    static float frameBottom(float k) {
        return rowTop(k) - MapStyle.FRAME_H * k * BLOCK_K;
    }

    /** A cap in the player block or the capsule: {@code cap} at {@link #BLOCK_K}, never under its legible minimum. */
    private float bcap(float cap) {
        return MapUi.cap(cap * BLOCK_K, k);
    }

    /** Depth tag width: tips, paddings and room for a minus sign and five digits (more only for a longer number). */
    private float tagW() {
        return (2f * TAG_TIP + 2f * TAG_PAD) * bk + Math.max(depthU, depthRoomU) * bcap(DEPTH_CAP);
    }

    /**
     * Width per unit of cap of {@code -88,888 m} in the NUMBER font's widest digit: the depth tag keeps room for a
     * minus sign and five digits and centres a shorter number in it (Kelvin 2026-10-20 17:58
     * 「预留5位数的空间加一个负号的空间啊，然后预留了空间以后，如果没有填满数字就剧中」).
     */
    private float depthRoomU() {
        char wide = '0';
        float best = 0f;
        for (char c = '0'; c <= '9'; c++) {
            float w = r.numWidth(String.valueOf(c), 1f);
            if (w > best) {
                best = w;
                wide = c;
            }
        }
        return r.numWidth(new StringBuilder(9).append('-').append(wide).append(wide).append(',').append(wide)
            .append(wide).append(wide).append(" m"), 1f);
    }

    /** Widest the name is drawn: {@link #NAME_MAX}, or less where the capsule is near. */
    private float nameMaxW() {
        return Math.max(0f, Math.min(NAME_MAX * k, nameRoom));
    }

    /** Player block width (map labels fade under it): the avatar, and the name or the depth tag, whichever reaches further. */
    private float blockW() {
        if (!hasProfile) return NAME_X * bk + r.uiWidth(NO_DIVER, bcap(NAME_CAP));
        float name = Math.min(nameU * bcap(NAME_CAP), nameMaxW());
        return Math.max(2f * AV_R * bk, Math.max(NAME_X * bk + name, TAG_X * bk + tagW()));
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

    /** HUD atlas phase: the player block's disc and tag, the capsule, buttons, lens, SORT and the rise ring. */
    void drawShapes(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f) return;
        badge.drawBack(a);
        shells.drawBack(a);
        settings.draw(b, a);
        mode.draw(b, a);
        allSongs.draw(b, a);
        if (lensAlpha > 0.01f) for (LensSegment s : lens) s.draw(b, lensAlpha);
        if (sortAlpha > 0.01f) sort.draw(b, sortAlpha);
        if (riseFill > 0f) {
            float al = riseAlpha() * a, rr = RISE_R * k, px = Ui.worldPerPx();
            draw.disc(riseX, riseY, rr + 20f * k, Theme.PANEL, 0.88f * al);
            draw.ringArc(riseX, riseY, rr, 0f, MathUtils.PI2, 10f * k, px, Theme.INK1, 0.16f * al);
            // fills clockwise from the top
            draw.ringArc(riseX, riseY, rr, MathUtils.HALF_PI, -Math.min(riseFill, 1f) * MathUtils.PI2, 10f * k, px,
                Theme.GROUND_SIG, al);
            Icons.draw(draw.shapes(), Icons.Icon.CHEVRON_UP, riseX, riseY + 14f * k, 40f * k, Theme.INK1, al);
        }
    }

    private float riseAlpha() {
        return MathUtils.clamp(riseFill * 8f, 0f, 1f);
    }

    /** HUD avatar phase (its own texture): the avatar in the disc, 1 under its centre. */
    void drawAvatar(Batch b) {
        if (hasProfile && hudAlpha > 0.01f) {
            avatars.draw(b, spec, badge.x() + AV_R * bk, badge.y() + badge.height() - (AV_R + 1f) * bk,
                AV_ART * bk * badge.visualScale(), hudAlpha, time);
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
        if (riseFill > 0f) {
            // RISE or 上浮 by the language (Kelvin 2026-10-19 19:59)
            r.ui(b, Lang.t("RISE", "上浮"), MapUi.cap(18f, k), riseX, riseY - 26f * k, Align.center | Fonts.VCENTER,
                Theme.INK1, riseAlpha() * a);
        }
    }

    /** HUD NUMBER-raster phase. */
    void drawNumbers(Batch b) {
        if (hudAlpha <= 0.01f) return;
        badge.drawNumbers(b, hudAlpha);
        shells.drawNumbers(b, hudAlpha);
    }

    /** HUD UI_BOLD-raster phase (the player's name). */
    void drawBold(Batch b) {
        if (hudAlpha > 0.01f) badge.drawBold(b, hudAlpha);
    }

    // =================================================================== controls
    /**
     * The player block (§3.9; the design thread's plan 5, Kelvin 2026-10-20 14:10, after the frames of 2026-10-19 06:28
     * and 2026-10-20 03:26): no panel; the avatar in a disc ({@link #AV_R}) with a white ring, the name (UI_BOLD
     * {@link #NAME_CAP}) right of it and under the name a tag pointed at both ends holding the depth, {@code -2,698 m}
     * (NUMBER {@link #DEPTH_CAP}; a DEPTH label before it until Kelvin 2026-10-20 17:34), all at {@link #BLOCK_K} since
     * 17:34; the tag keeps room for a minus sign and five digits and centres the number in it since 17:58 (as wide as
     * its number before). {@link MapStyle#FRAME_H} high;
     * its bounds reach the end of the name or the tag. Only the avatar takes taps (Kelvin 2026-10-20 14:40
     * 「然后现在就只保留点击头像进入profile界面」): tap → Profile; a touch on the name or the tag reaches the map.
     */
    final class Badge extends Pressable {
        /** The depth tag's six corners, counter-clockwise from its left tip. */
        private final float[] tag = new float[12];

        Badge(Draw draw) {
            super(draw);
        }

        /** The avatar disc only, at least a touch target across. */
        @Override
        public boolean hit(float px, float py, float grow) {
            float r = Math.max(AV_R * bk, Ui.touchTarget() * 0.5f) + grow;
            float dx = px - (x + AV_R * bk), dy = py - (y + h - AV_R * bk);
            return dx * dx + dy * dy <= r * r;
        }

        void drawBack(float alpha) {
            if (!visible) return;
            float px = Ui.worldPerPx(), s = visualScale();
            float cx = x + AV_R * bk, cy = y + h - AV_R * bk, rr = AV_R * bk * s, sy = cy - 3f * bk;
            float lit = (0.06f * press + 0.04f * hover) * alpha;
            if (hasProfile) {
                // the name's band, under the disc: solid to 0.6 of the name, then fading out over BAND_RUN past it
                float nx = x + NAME_X * bk, nw = Math.min(nameU * bcap(NAME_CAP), nameMaxW());
                float bb = y + h - (COL_Y + BAND_BOTTOM) * bk, bh = (BAND_BOTTOM - BAND_TOP) * bk;
                float solid = nx + nw * 0.6f, end = Math.max(solid, Math.min(nx + nw + BAND_RUN * bk, bandRight));
                draw.rect(cx, bb, solid - cx, bh, TAG_FILL, BAND_A * alpha);
                draw.rectGradientH(solid, bb, end - solid, bh, TAG_FILL, BAND_CLEAR, BAND_A * alpha);
            }
            // a soft shadow 3 under the disc (wider, fainter discs for its blur), the disc and its white ring
            draw.disc(cx, sy, rr + 8f * bk, SHADE, 0.08f * alpha);
            draw.disc(cx, sy, rr + 5f * bk, SHADE, 0.12f * alpha);
            draw.disc(cx, sy, rr + 2.5f * bk, SHADE, 0.18f * alpha);
            draw.disc(cx, sy, rr, SHADE, 0.3f * alpha);
            draw.disc(cx, cy, rr, AV_DISC, alpha);
            if (lit > 0f) draw.disc(cx, cy, rr, Theme.INK1, lit);
            draw.ringArc(cx, cy, RING_R * bk * s, 0f, MathUtils.PI2, RING_W * bk, px, Color.WHITE, 0.92f * alpha);
            if (!hasProfile) return;
            // the depth tag over its shadow (two copies 3 and 4.5 under it), with a cyan edge
            tag(-3f * bk);
            draw.convexFill(tag, 6, SHADE, 0.3f * alpha, px);
            tag(-4.5f * bk);
            draw.convexFill(tag, 6, SHADE, 0.2f * alpha, px);
            tag(0f);
            draw.convexFill(tag, 6, TAG_FILL, 0.9f * alpha, px);
            draw.convexStroke(tag, 6, 1.6f * bk, TAG_EDGE, 0.9f * alpha, px);
        }

        /** Puts the depth tag's corners in {@link #tag}, {@code dy} above its place (its bottom is the column's). */
        private void tag(float dy) {
            float x0 = x + TAG_X * bk, x1 = x0 + tagW(), p = TAG_TIP * bk;
            float yb = tagBottom() + dy, yt = yb + TAG_H * bk, ym = yb + TAG_H * bk * 0.5f;
            float[] t = tag;
            t[0] = x0;
            t[1] = ym;
            t[2] = x0 + p;
            t[3] = yb;
            t[4] = x1 - p;
            t[5] = yb;
            t[6] = x1;
            t[7] = ym;
            t[8] = x1 - p;
            t[9] = yt;
            t[10] = x0 + p;
            t[11] = yt;
        }

        /** The name, its baseline {@link #NAME_BASE} under the column's top, over a soft shadow about 2 under it. */
        void drawBold(Batch b, float alpha) {
            if (!visible || !hasProfile) return;
            float nx = x + NAME_X * bk, base = y + h - (COL_Y + NAME_BASE) * bk, cap = bcap(NAME_CAP);
            float mw = nameMaxW();
            for (int i = 0; i < SHADE_DX.length; i++) {
                r.boldFit(b, name, cap, nx + SHADE_DX[i] * bk, base + SHADE_DY[i] * bk, Align.left, mw, 0.8f,
                    NAME_SHADE, 0.18f * alpha);
            }
            r.boldFit(b, name, cap, nx, base, Align.left, mw, 0.8f, Theme.INK1, alpha);
        }

        /** Without a profile, NO DIVER where the name goes. */
        void drawUi(Batch b, float alpha) {
            if (!visible || hasProfile) return;
            r.ui(b, NO_DIVER, bcap(NAME_CAP), x + NAME_X * bk, centerY(), Align.left | Fonts.VCENTER, Theme.INK2,
                alpha);
        }

        /** Centre of the depth tag, where its number is centred (left after the tip and padding until 17:58). */
        private float numberX() {
            return x + TAG_X * bk + tagW() * 0.5f;
        }

        /** Baseline of the tag's text, {@link #DEPTH_BASE} under the column's top. */
        private float tagBase() {
            return y + h - (COL_Y + DEPTH_BASE) * bk;
        }

        void drawNumbers(Batch b, float alpha) {
            if (!visible || !hasProfile) return;
            r.num(b, depthText, bcap(DEPTH_CAP), numberX(), tagBase(), Align.center, Theme.INK1, alpha);
        }

        @Override
        public void draw(Batch b, float alpha) {
            drawBack(alpha);
            drawUi(b, alpha);
            drawNumbers(b, alpha);
            drawBold(b, alpha);
        }
    }

    /**
     * The shell capsule (the gold pill that sat in the badge, Kelvin 2026-10-19 06:28 「把原来的那种贝壳和profile的小的
     * 边界加上」): the shell icon and the count, {@link MapStyle#CAPSULE_H} high and at least
     * {@link MapStyle#CAPSULE_MIN_W} wide (2026-10-20 03:26; smaller since 15:10, so the depth leads) with the two
     * centred in it; since Kelvin 2026-10-20 15:36 (「把贝壳移动到depth旁边」) it sits {@link #SHELL_GAP} right of the
     * player block's depth tag, centred on it, no longer left of Settings, and it is drawn at {@link #BLOCK_K} with the
     * block since 17:34. It only shows the count: no tap since 2026-10-20 03:38.
     * Its gold wash lies on a deep gold fill since 2026-10-19 13:59, so the level title it can sit on does not show
     * through.
     */
    final class ShellCapsule extends Pressable {
        ShellCapsule(Draw draw) {
            super(draw);
        }

        void drawBack(float alpha) {
            if (!visible) return;
            float s = visualScale();
            float bw = w * s, bh = h * s;
            float bx = centerX() - bw * 0.5f, by = centerY() - bh * 0.5f;
            draw.pill(bx, by, bw, bh, Theme.SHELL_CAPSULE, alpha);
            draw.pill(bx, by, bw, bh, Theme.GOLD, 0.16f * alpha);
            draw.shell(contentX() + (CAP_PAD_L + CAP_ICON * 0.5f) * bk, centerY(), CAP_ICON * bk, Theme.GOLD, SHELL_RIB,
                alpha);
        }

        /** Left of the icon and count, centred in a capsule wider than they need. */
        private float contentX() {
            return x + Math.max(0f, w - capsuleContentW()) * 0.5f;
        }

        void drawNumbers(Batch b, float alpha) {
            if (!visible) return;
            r.num(b, shellText, bcap(COUNT_CAP), contentX() + (CAP_PAD_L + CAP_ICON + CAP_GAP) * bk, centerY(),
                Align.left | Fonts.VCENTER, Theme.GOLD, alpha);
        }

        @Override
        public void draw(Batch b, float alpha) {
            drawBack(alpha);
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
     * while the order or filter is not the default. Tap → the sort panel.
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
            if (gridSort != MapWorld.SORT_DEFAULT || filterActive) {
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

    /**
     * ALL SONGS (hub, bottom right): {@code ALL SONGS} over {@code 所有曲目} (in English alone) and a chevron; no icon
     * (Kelvin 2026-10-16).
     */
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
            if (!r.zh) { // an English screen shows no Chinese (Kelvin 2026-10-20)
                r.uiFit(b, ALL_TEXT, MapUi.cap(22f, k), tx, centerY(), Align.left | Fonts.VCENTER, tw, Theme.INK1,
                    alpha);
                return;
            }
            r.uiFit(b, ALL_TEXT, MapUi.cap(22f, k), tx, centerY() + 14f * k, Align.left | Fonts.VCENTER, tw, Theme.INK1, alpha);
            r.uiFit(b, ALL_ZH, MapUi.cap(16f, k), tx, centerY() - 20f * k, Align.left | Fonts.VCENTER, tw, Theme.INK2, alpha);
        }
    }

    /**
     * The main-line mode toggle (hub, left of Settings, once MAIN's chapters and side stories are
     * all open): the current mode's icon (a path for story mode, four tiles for chapter mode), its
     * name and {@code 点一下切换} ({@code TAP TO SWITCH} in English). Tap → the other mode.
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
            r.uiFit(b, !r.zh ? SWITCH_TEXT : chapterMode ? CHAPTER_ZH : STORY_ZH, MapUi.cap(14f, k), tx,
                centerY() - 16f * k,
                Align.left | Fonts.VCENTER, tw, Theme.INK2, alpha);
        }
    }
}
