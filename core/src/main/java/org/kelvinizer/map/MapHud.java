package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.NameWidth;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.DepthCrest;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 洞穴地图四周的 HUD：左上角深度徽章（头像坐在按深度分 16 档的六边形上，六边形里写深度；点头像展开 / 收起名字条，状态会保存，
 * 每次开游戏第一次进地图时名字收着就自动探出 2 秒；六边形下面左边贝壳、右边珍珠，超过 9,999 显示 9,999+ 加一圈高光，点一下弹出
 * 具体数目），右上设置和它左边的玩家按钮（进个人页），左下难度切换；“所有曲目”层右下有 SORT 按钮（打开排序和筛选面板）；主界面右下
 * 有“所有曲目”按钮，主线章节全部解锁后玩家按钮左边多一个故事模式 / 章节模式切换按钮；双指静止按住时两指中间出现上浮进度圈。
 *
 * <p>The HUD around a cave level (DESIGN §16). Top left, the depth crest (Kelvin 2026-10-30 16:37–20:58; the player
 * block of 2026-10-20 before it): the avatar sits on a hexagon holding the depth, in one of 16 looks by depth
 * ({@link DepthCrest}). A tap on the avatar slides the name out on a dark band or takes it back (「点一下头像弹出来一个和之前
 * 一样的那种用户名显示，再点一下收起来」), remembered in {@link Profile#mapNameShown}; the first map of a launch shows a hidden
 * name for {@link #PEEK} seconds. Under the hexagon sit the shells (left) and the pearls (right, a count only for now);
 * a count over 9,999 reads {@code 9,999+} inside a highlight ring, and a tap on it shows the whole count under it
 * (「贝壳数超过9999就在贝壳外面加一圈高光，点一下贝壳就会弹出一个说已经攒了多少的具体值」). Top right, Settings and left of it
 * the player button, which opens the profile (the avatar did until 20:54 「设置旁边放一个玩家的按钮，从那里进去」). Bottom
 * left the lens control; on ALL SONGS the SORT button (bottom right), which opens the {@link SortPanel} (sort and
 * filter); the rise ring between two fingers held still (Kelvin 2026-10-19 18:46). The hub (Kelvin 2026-10-16) has no
 * lens and no rise hint, an ALL SONGS button at the bottom right (its only entrance), and once MAIN's chapters are all
 * open a story / chapter mode toggle left of the player button. Everything is laid out against the uncovered map area
 * every frame, so it follows the card spring. No allocation per frame.
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

        /** A tap on the avatar showed or hid the name: remember it. */
        void nameShown(boolean shown);
    }

    private final Draw draw;
    private final Fonts fonts;
    private final Avatar avatars;
    private final DepthCrest crests;
    private final MapCamera cam;
    private final MapRenderer r;
    private final Actions actions;

    /** The depth crest's avatar (top left; tap → the name band). */
    final Crest crest;
    /** The shell and pearl capsules under the crest (a tap shows the whole count when it reads 9,999+). */
    final Capsule shells, pearls;
    /** Top right: Settings and the player button left of it (tap → Profile). */
    final Button settings, player;
    /** Hub: the ALL SONGS button (bottom right) and the main-line mode toggle (left of the player button). */
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
    /**
     * The crest this frame: the hexagon's left and bottom, the avatar's centre, the capsules' bottom, how far the
     * crest's art reaches right and the name band's longest reach.
     */
    private float hexL, hexB, avX, avY, capB, crestR, bandMax;

    // profile cache ---------------------------------------------------------------------
    private final Avatar.Spec spec = new Avatar.Spec();
    private String name = "";
    private final StringBuilder depthText = new StringBuilder(20);
    /** The crest's tier (1–16, {@link Depth#crestTier}). */
    private int tier = 1;
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());
    private static final String NO_DIVER = "NO PLAYER";
    /**
     * The crest (× k; Kelvin 2026-10-30 20:13–20:34, picture topleft8): its art's left edge {@link #CREST_IN} in from
     * the screen's left margin and the avatar's top at the top of Settings, the avatar ({@link DepthCrest#AV_R})
     * centred over the hexagon and overlapping it by {@link DepthCrest#LIFT}; the avatar's white ring (radius and
     * width) and the avatar drawn in it, 0.72 of the 2026-10-20 block's; the capsules {@link #CAP_DROP} under the
     * hexagon.
     */
    private static final float CREST_IN = 12f, RING_R = 52.9f, RING_W = 2.2f, AV_ART = 64.8f, CAP_DROP = 6f;
    /** The avatar disc and the shadows (under the disc, under the name). */
    private static final Color AV_DISC = Theme.rgb(0x05091C, 1f), SHADE = Theme.rgb(0x000012, 1f),
        NAME_SHADE = Theme.rgb(0x000818, 1f);
    /** The name's soft shadow: four faint copies about 2 under it and 1 right (× k). */
    private static final float[] SHADE_DX = {0f, 2f, 0f, 2f}, SHADE_DY = {-1f, -1f, -3f, -3f};
    /**
     * The name band (× k; picture topleft7, Kelvin 2026-10-30 20:30 「那就把名字大小再提升一点？」): {@link #BAND_H} high
     * and centred on the avatar, from its centre past the name's room ({@link #NAME_ROOM} CJK glyphs, 「最多只能用15个英文
     * 字符或者10个中文字符的宽度」) by {@link #BAND_TAIL}; solid ({@link #BAND_A}) to {@link #BAND_SOLID} of its length,
     * then fading out, with faint cyan hairlines along its edges. The name's cap and its gap from the avatar.
     */
    private static final float BAND_H = 48f, BAND_TAIL = 44f, BAND_A = 215f / 255f, BAND_SOLID = 0.74f,
        NAME_CAP = 24f, NAME_GAP = 14f;
    private static final int NAME_ROOM = 10;
    private static final Color BAND = Theme.rgb(0x080E26, 1f), BAND_CLEAR = Theme.rgb(0x080E26, 0f),
        HAIR = Theme.rgb(0x78DCFF, 1f), HAIR_CLEAR = Theme.rgb(0x78DCFF, 0f);
    /** Seconds the band takes to slide out and back, and how long the first map of a launch shows a hidden name. */
    private static final float BAND_OPEN = 0.24f, BAND_CLOSE = 0.18f, PEEK = 2f;
    /** The name band's target, its slide (0 in … 1 out) and what is left of the first map's look. */
    private boolean nameShown;
    private float nameT, peekLeft;
    /** The first map of this launch has had its look at the name. */
    private static boolean peeked;
    /** Width per unit of cap of {@link #NAME_ROOM} CJK glyphs, and of the name. */
    private final float nameRoomU;
    /**
     * The capsules' insides (× k; the shell capsule's of 2026-10-20 at its 0.75 of 17:34, the pearl capsule of
     * picture topleft8 alike): padding, icon, gap, count cap, padding. {@link MapStyle#CAPSULE_H} high.
     */
    private static final float CAP_PAD_L = 10.5f, CAP_ICON = 19.5f, CAP_GAP = 5.25f, COUNT_CAP = 16.5f,
        CAP_PAD_R = 12f;
    /** The pearl capsule (picture topleft8): fill, count, and the pearl's rim, body and light. */
    private static final Color PEARL_FILL = Theme.rgb(0x3A3260, 225f / 255f), PEARL_TEXT = Theme.rgb(0xEEE8FF, 1f),
        PEARL_RIM = Theme.rgb(0xFFE2F6, 1f), PEARL_BODY = Theme.rgb(0xC8BEEC, 1f),
        PEARL_LIGHT = Theme.rgb(0xF4F0FF, 1f);
    /** A capped count's highlight ring and glow (Kelvin 2026-10-30 20:58): gold for shells, lilac for pearls. */
    private static final Color SHELL_LINE = Theme.rgb(0xFFF0C8, 1f), SHELL_GLOW = Theme.rgb(0xFFD678, 1f),
        PEARL_LINE = Theme.rgb(0xF4ECFF, 1f), PEARL_GLOW = Theme.rgb(0xC8B4FF, 1f), PEARL_NUM = Theme.rgb(0xD9CCFF, 1f);
    /** Gap between the player button and Settings (× k; their centres 102 apart on 16:10). */
    private static final float PLAYER_GAP = 14f;
    private boolean hasProfile;
    private float time;
    private float sortU;

    // the whole count (a tap on a capped capsule) ---------------------------------------------
    /** The capsule whose whole count shows, or null; seconds left and its fade (0..1). */
    private Capsule pop;
    private float popLeft, popT;
    /** The count shown and the words around it, their widths at {@link #popCap} and that cap. */
    private String popPre = "", popPost = "";
    private float popPreW, popNumW, popPostW, popCap;
    /**
     * Seconds the whole count stays (a tap elsewhere closes it sooner); the bubble's padding, height and arrow (× k).
     */
    private static final float POP_TIME = 3f, POP_PAD = 18f, POP_H = 44f, POP_ARROW = 9f;
    private final float[] popArrow = new float[6];

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

    MapHud(Draw draw, Fonts fonts, Avatar avatars, DepthCrest crests, MapCamera cam, MapRenderer r,
           Actions actions) {
        this.draw = draw;
        this.fonts = fonts;
        this.avatars = avatars;
        this.crests = crests;
        this.cam = cam;
        this.r = r;
        this.actions = actions;
        crest = new Crest(draw);
        crest.setOnTap(new Runnable() {
            @Override
            public void run() {
                toggleName();
            }
        });
        shells = new Capsule(draw, false);
        shells.setOnTap(new Runnable() {
            @Override
            public void run() {
                togglePop(shells);
            }
        });
        pearls = new Capsule(draw, true);
        pearls.setOnTap(new Runnable() {
            @Override
            public void run() {
                togglePop(pearls);
            }
        });
        settings = new Button(draw, fonts, Icons.Icon.GEAR);
        settings.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.openSettings();
            }
        });
        player = new Button(draw, fonts, Icons.Icon.PERSON);
        player.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.openProfile();
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
        controls = new Pressable[]{crest, shells, pearls, settings, player, mode, lens[0], lens[1], lens[2], lens[3],
            sort, allSongs};
        sortU = r.uiWidth(SORT_TEXT, 1f);
        StringBuilder room = new StringBuilder(NAME_ROOM);
        for (int i = 0; i < NAME_ROOM; i++) room.append('深');
        nameRoomU = r.boldWidth(room, 1f);
    }

    // ------------------------------------------------------------------ state
    /** Refreshes the crest and the capsules from {@code p} (on profile changes only; may allocate). */
    void refresh(Profile p) {
        hasProfile = p != null;
        if (p == null) {
            closePop();
            return;
        }
        name = NameWidth.clip(p.name != null ? p.name : "");
        char initial = name.isEmpty() ? 'K' : Character.toUpperCase(name.charAt(0));
        spec.set(p.avatar.seed, p.avatar.shape, p.avatar.pattern, p.avatar.palette, p.avatar.glyph, p.avatar.frame,
            initial);
        int m = p.depthMeters();
        tier = Depth.crestTier(m);
        depthText.setLength(0);
        Fonts.appendGrouped(depthText, -m).append(" m"); // depths read negative (below the surface)
        nameShown = p.mapNameShown;
        shells.set(p.shells);
        pearls.set(p.pearls);
        if (pop != null && !pop.capped) closePop();
    }

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

    /** A tap on the avatar: the name band out or back, remembered; it ends the first map's look. */
    private void toggleName() {
        if (!hasProfile) return;
        nameShown = !(nameShown || peekLeft > 0f);
        peekLeft = 0f;
        actions.nameShown(nameShown);
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
        // the player button left of Settings (Kelvin 2026-10-30 20:54), the mode toggle (hub, once MAIN's chapters are
        // all open) left of it
        float px = x - icon - PLAYER_GAP * k;
        player.setBounds(px - icon * 0.5f, cy - icon * 0.5f, icon, icon);
        player.setVisible(hudAlpha > 0.05f);
        float mw = 252f * k, mh = 64f * k;
        mode.setBounds(px - slot * 0.5f - gap - mw, cy - mh * 0.5f, mw, mh);
        mode.setMinHit(mw, Math.max(mh, P));
        mode.setVisible(hub && modeShown && hudAlpha > 0.05f);
        // the crest from the top of Settings down, its art CREST_IN in from the screen's left margin (24 and EXTRA EDGE
        // PADDING; a cutout does not push it in, Kelvin 2026-10-20 14:39); the name band never reaching the buttons
        float fx = Ui.left() + Ui.MIN_MARGIN + Ui.edgePadding() + CREST_IN * k, top = cy + icon * 0.5f;
        float R = DepthCrest.AV_R * k;
        hexL = fx + DepthCrest.reachLeft(tier) * k;
        avX = hexL + DepthCrest.HEX_W * k * 0.5f;
        avY = top - R;
        hexB = top - (2f * DepthCrest.AV_R - DepthCrest.LIFT + DepthCrest.HEX_H) * k;
        crestR = hexL + (DepthCrest.HEX_W + DepthCrest.reachRight(tier)) * k;
        crest.setBounds(avX - R, avY - R, 2f * R, 2f * R);
        crest.setVisible(hudAlpha > 0.05f);
        float clusterLeft = mode.isVisible() ? mode.x() : player.x();
        bandMax = Math.max(avX, Math.min(avX + bandLength(), clusterLeft - 24f * k));
        // the shells under the pedestal's left, the pearls under its right (picture topleft8)
        float ch = MapStyle.CAPSULE_H * k;
        capB = hexB - CAP_DROP * k - ch;
        float sw = shells.naturalW(), pw = pearls.naturalW();
        shells.setBounds(fx, capB, sw, ch);
        pearls.setBounds(Math.max(crestR - pw, fx + sw + 8f * k), capB, pw, ch);
        shells.setVisible(hasProfile && hudAlpha > 0.05f);
        pearls.setVisible(hasProfile && hudAlpha > 0.05f);
        // lens control (BL)
        float lx = sl, segW = MapStyle.LENS_SEG_W * k, lensH = MapStyle.LENS_H * k;
        for (int i = 0; i < 4; i++) {
            lens[i].setBounds(lx, sb - off, segW, lensH);
            lens[i].setVisible(lensAlpha > 0.05f);
            lx += segW;
        }
        // SORT (ALL SONGS): the bottom right of the grid's side, flush with its right edge (Kelvin 2026-10-19); above
        // the lens row's top where the row has no room for both
        float ph = 80f * k, sortW = 76f * k + sortU * MapUi.cap(20f, k) + 32f * k;
        float sx = (grid ? gridRight : right - 24f * k) - sortW;
        float sy = sx - 24f * k >= lx ? sb : sb + lensH + 16f * k;
        sort.setBounds(sx, sy - off, sortW, ph);
        sort.setMinHit(sortW, Math.max(ph, P));
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
        float m = 8f * k, left = crestLeft(), top = avY + (DepthCrest.AV_R + 12f) * k, bottom = crestBottom();
        float right = Math.max(crestR, avX + DepthCrest.AV_R * k);
        if (!hasProfile) right = nameX() + r.uiWidth(NO_DIVER, MapUi.cap(NAME_CAP, k));
        else if (nameT > 0.01f) right = Math.max(right, bandEnd());
        r.addOccluder(left - m, bottom - m, right - left + 2f * m, top - bottom + 2f * m);
        float fl = mode.isVisible() ? mode.x() : player.x();
        r.addOccluder(fl - m, settings.y() - m, settings.x() + settings.width() - fl + 2f * m,
            settings.height() + 2f * m);
        if (pop != null && popT > 0.01f) {
            float pw = popW(), pl = popLeft(pw);
            r.addOccluder(pl - m, popBottom() - m, pw + 2f * m, popTop() - popBottom() + 2f * m);
        }
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

    /** Top edge for the shared (global) toast on the map: under the crest. */
    float globalToastTop() {
        return crestBottom() - 16f * k;
    }

    /** Left edge of the card's leader-line dim zone (no gauge: the card edge itself). */
    float gaugeLeft() {
        return mapRight;
    }

    /** Left of the crest's art (its wings or the hexagon's tip), where the shell capsule starts too. */
    private float crestLeft() {
        return hexL - DepthCrest.reachLeft(tier) * k;
    }

    /** Bottom of the crest this frame: the capsules', or the avatar's without a profile. */
    private float crestBottom() {
        return hasProfile ? capB : avY - DepthCrest.AV_R * k;
    }

    /** Top of Settings at rest, where the crest starts. */
    private static float rowTop(float k) {
        float icon = Button.ICON_DIAMETER * k;
        return Ui.safeTop() - Math.max(Ui.touchTarget(), icon) * 0.5f + icon * 0.5f;
    }

    /** Bottom of the crest and its capsules at rest, as {@link #layout} places them; ALL SONGS starts under it. */
    static float frameBottom(float k) {
        return rowTop(k) - (2f * DepthCrest.AV_R - DepthCrest.LIFT + DepthCrest.HEX_H + CAP_DROP
            + MapStyle.CAPSULE_H) * k;
    }

    /** Full length of the name band: the avatar's radius, the gap, the name's room and the tail. */
    private float bandLength() {
        return (DepthCrest.AV_R + NAME_GAP + BAND_TAIL) * k + nameRoomU * MapUi.cap(NAME_CAP, k);
    }

    /** Right end of the name band as it slides this frame. */
    private float bandEnd() {
        return avX + (bandMax - avX) * Interpolation.pow2Out.apply(nameT);
    }

    /** Left edge of the name (and of NO PLAYER). */
    private float nameX() {
        return avX + (DepthCrest.AV_R + NAME_GAP) * k;
    }

    /** A capsule count's cap. */
    private float countCap() {
        return MapUi.cap(COUNT_CAP, k);
    }

    // ------------------------------------------------------------------ the whole count
    /** Shows {@code c}'s whole count under it, or hides it if it shows (a tap on a capped capsule). */
    private void togglePop(Capsule c) {
        if (pop == c) {
            closePop();
            return;
        }
        pop = c;
        popLeft = POP_TIME;
        popT = 0f;
        boolean zh = r.zh;
        popPre = zh ? "已经攒了 " : "";
        popPost = zh ? (c.pearl ? " 颗珍珠" : " 个贝壳") : (c.pearl ? " pearls saved" : " shells saved");
        popCap = MapUi.cap(18f, k);
        popPreW = r.uiWidth(popPre, popCap);
        popNumW = r.uiWidth(c.full, popCap);
        popPostW = r.uiWidth(popPost, popCap);
    }

    private void closePop() {
        pop = null;
        popLeft = 0f;
    }

    /** The bubble's width, left edge (centred under its capsule, kept on screen), top and bottom. */
    private float popW() {
        return popPreW + popNumW + popPostW + 2f * POP_PAD * k;
    }

    private float popLeft(float w) {
        float min = Ui.left() + Ui.MIN_MARGIN, max = Ui.right() - Ui.MIN_MARGIN - w;
        return MathUtils.clamp(pop.centerX() - w * 0.5f, min, Math.max(min, max));
    }

    private float popTop() {
        return pop.y() - (4f + POP_ARROW) * k + 8f * k * (1f - popT);
    }

    private float popBottom() {
        return popTop() - POP_H * k;
    }

    // ------------------------------------------------------------------ input
    /** Touch down; true when a HUD control claims it. A touch anywhere but its capsule hides the whole count. */
    boolean touchDown(float x, float y, int ptr) {
        if (pop != null && !pop.hit(x, y)) closePop();
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

    // ------------------------------------------------------------------ frame
    void update(float dt, float now) {
        time = now;
        for (Pressable p : controls) p.update(dt);
        // the first map of a launch shows a hidden name for PEEK seconds (Kelvin 2026-10-30 20:50 mock-up)
        if (!peeked && hasProfile && hudAlpha > 0.5f) {
            peeked = true;
            if (!nameShown) peekLeft = PEEK;
        }
        if (peekLeft > 0f) peekLeft = Math.max(0f, peekLeft - dt);
        boolean out = hasProfile && (nameShown || peekLeft > 0f);
        nameT = out ? Math.min(1f, nameT + dt / BAND_OPEN) : Math.max(0f, nameT - dt / BAND_CLOSE);
        if (pop != null) {
            popLeft -= dt;
            popT = Math.min(1f, popT + dt / 0.15f);
            if (popLeft <= 0f || !pop.isVisible()) closePop();
        }
    }

    /** HUD atlas phase: the name band, buttons, lens, SORT and the rise ring. */
    void drawShapes(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f) return;
        if (hasProfile && nameT > 0.001f) drawBand(a);
        settings.draw(b, a);
        player.draw(b, a);
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

    /**
     * The name band, behind the avatar from its centre: solid to {@link #BAND_SOLID} of its length, then fading out,
     * with hairlines along both edges.
     */
    private void drawBand(float alpha) {
        float end = bandEnd(), len = end - avX, hh = BAND_H * k, by = avY - hh * 0.5f;
        if (len <= 0.5f) return;
        float solid = len * BAND_SOLID, fade = len - solid, line = Ui.worldPerPx(), a = BAND_A * alpha;
        draw.rect(avX, by, solid, hh, BAND, a);
        draw.rectGradientH(avX + solid, by, fade, hh, BAND, BAND_CLEAR, a);
        for (int i = 0; i < 2; i++) {
            float ly = i == 0 ? by : by + hh - line;
            draw.rect(avX, ly, solid, line, HAIR, a / 3f);
            draw.rectGradientH(avX + solid, ly, fade, line, HAIR, HAIR_CLEAR, a / 3f);
        }
    }

    private float riseAlpha() {
        return MathUtils.clamp(riseFill * 8f, 0f, 1f);
    }

    /**
     * HUD avatar phase: the crest's back layer, the avatar disc and its ring, the capsules and the whole count's
     * bubble, the avatar, then the crest's top layer (the pearls or the diamond on the ring).
     */
    void drawAvatar(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f) return;
        float s = crest.visualScale(), R = DepthCrest.AV_R * k * s;
        if (hasProfile) crests.drawBack(b, tier, hexL, hexB, k, a);
        // a soft shadow 3 under the disc (wider, fainter discs for its blur), the disc and its white ring
        float sy = avY - 3f * k, px = Ui.worldPerPx();
        draw.disc(avX, sy, R + 6f * k, SHADE, 0.08f * a);
        draw.disc(avX, sy, R + 3.5f * k, SHADE, 0.12f * a);
        draw.disc(avX, sy, R + 1.5f * k, SHADE, 0.2f * a);
        draw.disc(avX, avY, R, AV_DISC, a);
        float lit = crest.lit() * a;
        if (lit > 0f) draw.disc(avX, avY, R, Theme.INK1, lit);
        draw.ringArc(avX, avY, RING_R * k * s, 0f, MathUtils.PI2, RING_W * k, px, Color.WHITE, 0.92f * a);
        if (hasProfile) {
            shells.drawBack(a);
            pearls.drawBack(a);
            if (pop != null && popT > 0.01f) drawPopBack(a * popT);
            // the avatar, 1 under the disc's centre
            avatars.draw(b, spec, avX, avY - k, AV_ART * k * s, a, time);
            // the pearls or the diamond on the ring, pressed with the avatar
            crests.drawTop(b, tier, avX + (hexL - avX) * s, avY + (hexB - avY) * s, k * s, a);
        }
    }

    /**
     * The whole count's card, as the result screen's breakdowns: opaque, its edge and the pointer up to its capsule
     * in the capsule's colour.
     */
    private void drawPopBack(float alpha) {
        float w = popW(), x = popLeft(w), t = popTop(), h = POP_H * k, rad = 12f * k;
        float ax = MathUtils.clamp(pop.centerX(), x + rad + POP_ARROW * k, x + w - rad - POP_ARROW * k);
        float[] p = popArrow;
        p[0] = ax - POP_ARROW * k;
        p[1] = t - 1f;
        p[2] = ax + POP_ARROW * k;
        p[3] = t - 1f;
        p[4] = ax;
        p[5] = t + POP_ARROW * k;
        Color rim = pop.pearl ? PEARL_BODY : Theme.GOLD;
        Panel.shadow(draw, x, t - h, w, h, alpha);
        Panel.cardSolid(draw, x, t - h, w, h, rad, alpha);
        draw.roundRectStroke(x, t - h, w, h, rad, 1.5f * k, rim, 0.5f * alpha);
        draw.convexFill(p, 3, rim, 0.5f * alpha, Ui.worldPerPx());
    }

    /** HUD UI-raster phase. */
    void drawUi(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f) return;
        if (!hasProfile && crest.isVisible()) {
            r.ui(b, NO_DIVER, MapUi.cap(NAME_CAP, k), nameX(), avY, Align.left | Fonts.VCENTER, Theme.INK2, a);
        }
        if (pop != null && popT > 0.01f) {
            float pa = a * popT, w = popW(), x = popLeft(w) + POP_PAD * k, y = popTop() - POP_H * k * 0.5f;
            Color num = pop.pearl ? PEARL_NUM : Theme.GOLD;
            r.ui(b, popPre, popCap, x, y, Align.left | Fonts.VCENTER, Theme.INK1, pa);
            r.ui(b, pop.full, popCap, x + popPreW, y, Align.left | Fonts.VCENTER, num, pa);
            r.ui(b, popPost, popCap, x + popPreW + popNumW, y, Align.left | Fonts.VCENTER, Theme.INK1, pa);
        }
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

    /** HUD NUMBER-raster phase: the depth on the hexagon over its shadow, and the counts. */
    void drawNumbers(Batch b) {
        float a = hudAlpha;
        if (a <= 0.01f || !hasProfile) return;
        float cap = MapUi.cap(DepthCrest.NUM_CAP, k), cx = hexL + DepthCrest.HEX_W * k * 0.5f;
        float cy = hexB + DepthCrest.HEX_H * k * 0.5f, d = DepthCrest.SHADOW * k;
        r.num(b, depthText, cap, cx + d, cy - d, Align.center | Fonts.VCENTER, Color.BLACK, 0.67f * a);
        r.num(b, depthText, cap, cx, cy, Align.center | Fonts.VCENTER, Theme.INK1, a);
        shells.drawNumbers(b, a);
        pearls.drawNumbers(b, a);
    }

    /** HUD UI_BOLD-raster phase: the name on its band, sliding in after it, over a soft shadow. */
    void drawBold(Batch b) {
        if (hudAlpha <= 0.01f || !hasProfile || nameT <= 0.3f) return;
        float e = MathUtils.clamp((nameT - 0.3f) / 0.7f, 0f, 1f), a = hudAlpha * e;
        float x = nameX() - 16f * k * (1f - e), cap = MapUi.cap(NAME_CAP, k);
        float mw = Math.max(0f, Math.min(nameRoomU * cap, bandMax - 12f * k - x));
        for (int i = 0; i < SHADE_DX.length; i++) {
            r.boldFit(b, name, cap, x + SHADE_DX[i] * k, avY + SHADE_DY[i] * k, Align.left | Fonts.VCENTER, mw, 0.8f,
                NAME_SHADE, 0.2f * a);
        }
        r.boldFit(b, name, cap, x, avY, Align.left | Fonts.VCENTER, mw, 0.8f, Theme.INK1, a);
    }

    // =================================================================== controls
    /**
     * The crest's avatar: a tap shows or hides the name band (Kelvin 2026-10-30 20:20; it opened the profile before
     * 20:54, which the player button does now). It takes touches on the avatar disc, at least a touch target across;
     * the hexagon and the band take none. {@link MapHud} draws it, scaled while pressed.
     */
    final class Crest extends Pressable {
        Crest(Draw draw) {
            super(draw);
        }

        @Override
        public boolean hit(float px, float py, float grow) {
            float rr = Math.max(w * 0.5f, Ui.touchTarget() * 0.5f) + grow;
            float dx = px - centerX(), dy = py - centerY();
            return dx * dx + dy * dy <= rr * rr;
        }

        /** The press tint over the disc. */
        float lit() {
            return 0.06f * press;
        }

        @Override
        public void draw(Batch b, float alpha) {
        }
    }

    /**
     * A capsule under the crest (picture topleft8): the shells' (gold, the capsule of 2026-10-19 06:28) under the
     * hexagon's left, the pearls' (lavender, a count only for now, Kelvin 2026-10-30 20:20 「底座右下角留一个珍珠数，暂时
     * 不用写功能」) under its right reach; {@link MapStyle#CAPSULE_H} high and at least {@link MapStyle#CAPSULE_MIN_W}
     * wide, the icon and count centred. A count over 9,999 reads {@code 9,999+} with a ring 2 outside the capsule and a
     * soft glow, and only then does it take taps: the whole count shows under it (Kelvin 2026-10-30 20:58).
     */
    final class Capsule extends Pressable {
        final boolean pearl;
        /** The count as shown, and in full. */
        final StringBuilder text = new StringBuilder(12), full = new StringBuilder(16);
        boolean capped;
        private float textU;

        Capsule(Draw draw, boolean pearl) {
            super(draw);
            this.pearl = pearl;
        }

        void set(long count) {
            full.setLength(0);
            Fonts.appendGrouped(full, Math.max(0L, count));
            capped = count > Fonts.SHOWN_MAX;
            text.setLength(0);
            Fonts.appendCapped(text, count);
            textU = r.numWidth(text, 1f);
        }

        /** Padding, icon, gap, count, padding; at least {@link MapStyle#CAPSULE_MIN_W}. */
        float naturalW() {
            return Math.max(MapStyle.CAPSULE_MIN_W * k, contentW());
        }

        private float contentW() {
            return (CAP_PAD_L + CAP_ICON + CAP_GAP + CAP_PAD_R) * k + textU * countCap();
        }

        /** Left of the icon and count, centred in a capsule wider than they need. */
        private float contentX() {
            return x + Math.max(0f, w - contentW()) * 0.5f;
        }

        @Override
        public boolean hit(float px, float py, float grow) {
            return capped && super.hit(px, py, grow);
        }

        void drawBack(float alpha) {
            if (!visible) return;
            float s = visualScale();
            float bw = w * s, bh = h * s, bx = centerX() - bw * 0.5f, by = centerY() - bh * 0.5f;
            if (capped) {
                // the glow, then the ring: 2 wide, 2 outside the capsule
                Color glow = pearl ? PEARL_GLOW : SHELL_GLOW;
                for (int i = 3; i >= 1; i--) {
                    float e = (2f + 2f * i) * k;
                    draw.pill(bx - e, by - e, bw + 2f * e, bh + 2f * e, glow, (0.2f - 0.05f * i) * alpha);
                }
                float o = 4f * k;
                draw.roundRectStroke(bx - o, by - o, bw + 2f * o, bh + 2f * o, bh * 0.5f + o, 2f * k,
                    pearl ? PEARL_LINE : SHELL_LINE, 0.95f * alpha);
            }
            float ix = contentX() + (CAP_PAD_L + CAP_ICON * 0.5f) * k, iy = centerY();
            if (pearl) {
                draw.pill(bx, by, bw, bh, PEARL_FILL, alpha);
                float pr = CAP_ICON * 0.5f * k;
                draw.disc(ix, iy, pr, PEARL_RIM, alpha);
                draw.disc(ix, iy, pr - Math.max(1f, 0.8f * k), PEARL_BODY, alpha);
                draw.disc(ix - 0.125f * pr, iy + 0.175f * pr, 0.72f * pr, PEARL_LIGHT, alpha);
                draw.disc(ix - 0.325f * pr, iy + 0.4f * pr, 0.21f * pr, Color.WHITE, alpha);
            } else {
                draw.pill(bx, by, bw, bh, Theme.SHELL_CAPSULE, alpha);
                draw.pill(bx, by, bw, bh, Theme.GOLD, 0.16f * alpha);
                draw.shell(ix, iy, CAP_ICON * k, Theme.GOLD, SHELL_RIB, alpha);
            }
            if (press > 0f) draw.pill(bx, by, bw, bh, Theme.INK1, 0.1f * press * alpha);
        }

        void drawNumbers(Batch b, float alpha) {
            if (!visible) return;
            r.num(b, text, countCap(), contentX() + (CAP_PAD_L + CAP_ICON + CAP_GAP) * k, centerY(),
                Align.left | Fonts.VCENTER, pearl ? PEARL_TEXT : Theme.GOLD, alpha);
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
            if (press > 0f) {
                draw.roundRect(bx, by, bw, bh, rad, Theme.INK1, 0.08f * press * alpha);
            }
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

    /** A HUD plate: panel fill, a faint rim, the press tint. */
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
            if (press > 0f) {
                draw.roundRect(x, y, w, h, 22f * k, Theme.INK1, 0.08f * press * alpha);
            }
            Icons.draw(draw.shapes(), Icons.Icon.CHEVRON_RIGHT, x + w - 34f * k, centerY(), 30f * k,
                Theme.INK1, 0.8f * alpha);
        }

        void drawText(Batch b, float alpha) {
            if (!visible || alpha <= 0.01f) return;
            float tx = x + 28f * k, tw = w - 80f * k;
            if (!r.zh) { // an English screen shows no Chinese (Kelvin 2026-10-20)
                r.uiFit(b, ALL_TEXT, MapUi.cap(22f, k), tx, centerY(), Align.left | Fonts.VCENTER, tw, Theme.INK1,
                    alpha);
                return;
            }
            r.uiFit(b, ALL_TEXT, MapUi.cap(22f, k), tx, centerY() + 14f * k, Align.left | Fonts.VCENTER, tw,
                Theme.INK1, alpha);
            r.uiFit(b, ALL_ZH, MapUi.cap(16f, k), tx, centerY() - 20f * k, Align.left | Fonts.VCENTER, tw,
                Theme.INK2, alpha);
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
            if (press > 0f) {
                draw.roundRect(x, y, w, h, rad, Theme.INK1, 0.08f * press * alpha);
            }
            float cx = x + 36f * k, cy = centerY();
            Color c = Theme.GROUND_SIG;
            if (chapterMode) {
                float t = 11f * k, g = 4f * k;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        draw.roundRectStroke(cx - t - g * 0.5f + i * (t + g), cy - t - g * 0.5f + j * (t + g), t, t,
                            2f * k, 2f, c, alpha);
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
