package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Marquee;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 歌曲卡片：贴在屏幕右侧（平板比例也一样，只是纵向拉开；从底部拉起的旧版本不再使用）。
 * 内容自上而下：顶行、16:9 曲绘和它自己颜色的柔光、标题带（曲名只占一行，超过 30 个字符就滚动）、
 * 分数和右边的评级圈、难度按钮、NEXT、AUTO 和 DESCEND。
 * 卡片跟手拖动，快速甩开或拖过 35% 就关闭；换歌、换难度时内容交叉淡入淡出。
 *
 * <p>The song card (DESIGN §4): a side card attached to VR on WIDE layouts (760 wide at aspect
 * ≥ 2, else 700) or a bottom sheet with peek / full detents on TALL ones.
 *
 * <p><b>Container</b> (§4.1). One position value drives everything: the visible width of the
 * side card, or the sheet height above VB. It opens on a spring tuned for a ≈ 12 unit overshoot,
 * follows the finger 1:1 while dragged (side: horizontally; sheet: vertically), and on release
 * either springs to its detent ({@code pos + v·0.15} picks the sheet detent) or is dismissed
 * (velocity &gt; 900 /s or past 35 % of its size). The exit is a 220 ms {@code pow2In} tween.
 *
 * <p><b>Content</b> (§4.2–§4.7; the side card is design round 5, Kelvin 2026-10-19 07:00 / 07:01):
 * header (‹ at its left end and › at its right step through songs, the level's name between them, all a little
 * larger since Kelvin 2026-10-19 13:37, who also dropped the ×: tapping the map, swiping the card away or Back closes
 * it); the 16:9 jacket (centre crop, scanline reveal, thin stroke) over a glow of its own colours (the library's
 * wash); a full-width title band with the title on one line (34, shrinking to 29; past 30 characters, or still too
 * wide at 29, it scrolls at 29 with {@link Marquee} timing and faded ends)
 * over the artist and the BPM; the record ({@link CardRecord}): the best score with dim leading zeros, DEPTH and
 * PLAYS under it and the grade ring at the right (purple once the chart has a full combo, Kelvin 2026-10-19 13:09,
 * which replaced the FULL COMBO / RESONATED ribbon); difficulty chips ({@link CardChip}; a horizontal swipe on the
 * row steps the difficulty); the micro-goal, centred on its row since Kelvin 2026-10-19 16:36 (AUTOPLAY · NOT
 * RECORDED in its place while AUTO is on; the recent sparkline left of it went at 13:09); AUTO ({@link AutoChip}) and
 * DESCEND
 * ({@link DescendButton}) on SB. The sheet keeps its square jacket beside the record. Switching songs crossfades the
 * content (old rises 16 and fades in 120 ms, new rises in over 200 ms) with a 2-frame jacket G2; switching difficulty
 * sweeps the chip, rolls its constant, recolours the card accent and crossfades the record block (160 ms).
 *
 * <p><b>Jacket.</b> Requested once the card rested 280 ms on a song ({@code library().requestJacket}
 * decodes it on a worker, {@code jacketIfReady} uploads it when done, so browsing never waits on
 * a decode; an average-colour placeholder shows meanwhile) and released when the card moves to
 * another song or finishes closing. It is kept when the screen hides (the song may be the one in
 * play).
 *
 * <p><b>Scale</b> (DESIGN §0.3). Every metric is the DESIGN value at uiScale 1 times the card
 * scale {@link #scale()}: {@link MapUi#k()}, reduced only as far as the content needs to fit the
 * screen (the side card's 16:10 blueprint as a whole, like the result screen; the sheet's action row width).
 * Taller screens share their extra height between the side card's four gaps (Kelvin 2026-10-19 03:47
 * 「所有剩下的都通过16：10进行拉伸/压缩高度」). Text caps never drop below the
 * §1.1 minimums of the current uiScale ({@link MapUi#cap(float, float)}).
 *
 * <p>Drawn in the screen's texture phases (atlas, jacket, UI, NUMBER, UI_BOLD, DISPLAY). Every
 * touch inside its rect is swallowed. No allocation per frame.
 */
final class SongCard {
    /** Card actions (implemented by the screen). */
    interface Actions {
        /** The user dismissed the card (a swipe or a fling; the × went 2026-10-19). */
        void cardClosed();

        /** A chip (or a chip-row swipe) chose a difficulty: it becomes the lens. */
        void cardLens(Difficulty d);

        /** DESCEND released. */
        void descend(MapNode node, Difficulty d, boolean auto, float fx, float fy);

        /** HOLD TO BUY completed on a single not bought yet. */
        void buy(MapNode node);

        /** ‹ (−1) or › (+1): select the previous / next song in path order. */
        void cardStep(int dir);

        /** The sheet changed detent (the focus point moved). */
        void cardDetent();
    }

    private static final int DRAG_NONE = 0, DRAG_CARD = 1, DRAG_CHIPS = 2, DRAG_IGNORE = 3;
    private static final int SWAP_NONE = 0, SWAP_OUT = 1, SWAP_IN = 2;
    private static final float CHIP_STEP = 150f;
    /**
     * Side card blueprint at scale 1, from the 16:10 round 5 (y down from ST): header row centre and half height (the
     * 76 arrows since 2026-10-19 13:37, the first gap 6 shorter so the jacket stays put);
     * title band 20 under the jacket and 114 tall, the title and artist baselines 56 and 96 below its top; grade ring
     * radius, the score and DEPTH baselines 68 and 116 below the ring's top; chip row; the NEXT row, 40 tall and 24
     * over the action row, which stands on SB.
     */
    private static final float B_HEAD = 76f, B_HEAD_HALF = 38f, B_BAND_GAP = 20f, B_BAND_H = 114f, B_TITLE = 56f,
        B_ARTIST = 96f, B_RING_R = 74f, B_SCORE = 68f, B_DEPTH = 116f, B_CHIP_H = 116f, B_NEXT_H = 40f,
        B_NEXT_GAP = 24f;
    /** All of it but the jacket and the four gaps (666). */
    private static final float B_FIXED = B_HEAD + B_HEAD_HALF + B_BAND_GAP + B_BAND_H + B_DEPTH + B_CHIP_H + B_NEXT_H
        + B_NEXT_GAP + MapStyle.ACTION_H;
    /**
     * The four gaps at 16:10 (header → jacket, band → ring, DEPTH line → chips, chips → NEXT; the third keeps the
     * ring clear of the fourth chip) and the least each closes to when a screen is too short even at the 0.8 floor.
     */
    private static final float[] B_GAP = {39f, 14f, 48f, 30f}, B_GAP_MIN = {16f, 6f, 44f, 12f};
    private static final float B_GAP_SUM = 39f + 14f + 48f + 30f, B_GAP_MIN_SUM = 16f + 6f + 44f + 12f;
    /** Title caps: 34, shrinking to 29 to fit the band; past 30 characters, or too wide at 29, it scrolls at 29. */
    private static final float TITLE_CAP = 34f, TITLE_MIN = 29f;
    private static final int TITLE_CHARS = 30;
    /** Header: the level's name (26; 20 until Kelvin 2026-10-19 13:37 「稍微大一点」). */
    private static final float HEAD_CAP = 26f;
    /** DEPTH / PLAYS: letter spacing of the labels (fraction of their cap). */
    private static final float LABEL_TRACK = 0.12f;
    private static final float JACKET_R = 14f;
    /** Glow: the wash over 1.16 × 1.24 of the jacket, its alpha rising from the edges over these fractions of its
     *  size to the peak (design round 5, after the result screen's). */
    private static final float GLOW_W = 1.16f, GLOW_H = 1.24f, GLOW_FX = 0.16f, GLOW_FY = 0.2f, GLOW_A = 0.47f;
    private static final Color BAND_FILL = new Color(8 / 255f, 13 / 255f, 38 / 255f, 150 / 255f);
    private static final Color BAND_LINE = new Color(120 / 255f, 200 / 255f, 1f, 70 / 255f);
    private static final Color JACKET_STROKE = new Color(140 / 255f, 200 / 255f, 1f, 80 / 255f);
    private static final String ACC_LABEL = "ACC ", DEPTH_LABEL = " · DEPTH ", DEPTH_WORD = "DEPTH",
        PLAYS_WORD = "PLAYS";
    /** Sheet action row width at scale 1: padding, chips 4 × 184 + 3 × 16, gap, AUTO, gap, DESCEND. */
    private static final float SHEET_ROW_W = 2f * MapStyle.CARD_PAD + 4f * MapStyle.SHEET_CHIP_W
        + 3f * MapStyle.CHIP_GAP + 40f + MapStyle.AUTO_W_WIDE + MapStyle.ACTION_GAP + MapStyle.SHEET_DESCEND_W;

    private final Draw draw;
    /** The current level (its card header); swapped with the level. */
    MapWorld world;
    private final ThumbAtlas thumbs;
    private final MapRenderer r;
    private final SongLibrary library;
    private final ProfileStore profiles;
    private final Actions actions;

    // ------------------------------------------------------------------ container
    private boolean shown, closing, sheetLayout, full;
    /** ALL SONGS: the card stays open beside the grid, so a swipe does not move or dismiss it (Kelvin 2026-10-19). */
    boolean pinned;
    private float pos, closeT, closeFrom;
    private final Spring spring = new Spring(16f, 0.79f, 0f);
    private float time;

    // ------------------------------------------------------------------ input
    private int ptr = -1, dragMode = DRAG_NONE, chipSteps;
    private float downX, downY, dragStart;
    private boolean downOnChips;
    private Pressable pressed;
    private final VelocityTracker vt = new VelocityTracker();

    // ------------------------------------------------------------------ content
    private MapNode node, content;
    private Difficulty diff = Difficulty.SURFACE, shownDiff = Difficulty.SURFACE;
    private int swap = SWAP_NONE;
    private float swapT, recordT = 1f, recordOutT = -1f;
    private final CardRecord rec = new CardRecord();
    private final StringBuilder layerText = new StringBuilder(48), metaText = new StringBuilder(96);
    /** A locked song (ALL SONGS): its unlock conditions, shown one at a time on DESCEND. */
    private String[] lockTexts = new String[0];
    private int lockIdx;
    private float lockT;
    /** Each condition holds this long, then fades out and the next fades in (Kelvin 2026-10-16 22:39: 1 s in all). */
    private static final float LOCK_HOLD = 3f, LOCK_FADE = 0.5f;
    private final Color accent = new Color(Theme.GROUND_SIG), accentTarget = new Color(Theme.GROUND_SIG);
    private final Color c1 = new Color(), c2 = new Color();
    /** Text widths per unit of cap (micro-goal parts at UI, best score in fixed cells). */
    private float goalPrefixU, goalRestU, bestU, sheetBestLeft;
    /** Card scale of this frame ({@link #scale()}) and its cap of a 20 text. */
    private float k = 1f, c20 = 20f;
    /**
     * Side card title: seconds since its song came in (the marquee clock; a difficulty change keeps it), its cap,
     * its width at that cap and whether it scrolls, measured for {@link #titleFor} at {@link #titleK}.
     */
    private float titleT, titleCap = TITLE_CAP, titleW, titleK;
    private boolean titleScroll;
    private MapNode titleFor;
    /** DEPTH / PLAYS row of this frame: label and value caps, and the x of label, value, label, value. */
    private float rowLabelCap, rowValueCap;
    private final float[] rowX = new float[4];
    private final StringBuilder one = new StringBuilder(1);

    // ------------------------------------------------------------------ jacket
    private SongMeta jacketSong;
    private Texture jacket, wash;
    /** The jacket's crop to the box (16:9 centre on the side card, all of it in the sheet's square). */
    private float cu0, cv0, cu1 = 1f, cv1 = 1f;
    private boolean jacketTried, holdJacket;
    private float jacketWait, reveal, jacketGlitch;
    private final float[] jv = new float[20 * 12];
    /** Glow grid lines (outer edge, end of the fade, start of the fade, outer edge). */
    private final float[] gx = new float[4], gy = new float[4];

    // ------------------------------------------------------------------ controls
    final Button prev, next;
    final CardChip[] chips = new CardChip[4];
    final AutoChip auto;
    final DescendButton descend;
    private final Pressable[] controls;

    // ------------------------------------------------------------------ layout (per frame)
    private float x0, cw, cx0, cx1, jx, jTop, jSize, jW, jH, chipTop, goalTop, actionY;
    /**
     * Side card: header row centre, title band, title and artist baselines, grade ring, score and DEPTH baselines,
     * and this frame's four gaps (header → jacket, band → ring, DEPTH → chips, chips → NEXT).
     */
    private float headY, bandTop, bandBot, titleBase, artistBase, ringX, ringY, ringR, scoreBase, depthBase;
    private final float[] gaps = new float[4];
    private float sheetTop, base, fullK;

    SongCard(Draw draw, Fonts fonts, MapWorld world, ThumbAtlas thumbs, MapRenderer renderer, SongLibrary library,
             ProfileStore profiles, final Actions actions) {
        this.draw = draw;
        this.world = world;
        this.thumbs = thumbs;
        this.r = renderer;
        this.library = library;
        this.profiles = profiles;
        this.actions = actions;
        prev = new Button(draw, fonts, Icons.Icon.CHEVRON_LEFT);
        prev.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.cardStep(-1);
            }
        });
        next = new Button(draw, fonts, Icons.Icon.CHEVRON_RIGHT);
        next.setOnTap(new Runnable() {
            @Override
            public void run() {
                actions.cardStep(1);
            }
        });
        for (int i = 0; i < 4; i++) {
            final Difficulty d = Difficulty.byOrdinal(i);
            chips[i] = new CardChip(draw, renderer, d);
            chips[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    chooseDifficulty(d);
                }
            });
        }
        auto = new AutoChip(draw, renderer);
        descend = new DescendButton(draw, renderer);
        descend.setOnTap(new Runnable() {
            @Override
            public void run() {
                if (content != null && swap != SWAP_OUT && !descend.isLocked() && !descend.isBuying()) {
                    actions.descend(content, diff, auto.isOn() && !content.launch, descend.centerX(),
                        descend.centerY());
                }
            }
        });
        descend.setOnBuy(new Runnable() {
            @Override
            public void run() {
                if (content != null && content.forSale && swap != SWAP_OUT) actions.buy(content);
            }
        });
        controls = new Pressable[]{prev, next, chips[0], chips[1], chips[2], chips[3], auto, descend};
    }

    // =================================================================== state
    /**
     * Opens the card on {@code n} (or switches to it with the content crossfade). The difficulty is
     * the lens, falling back to the nearest lower chart (§4.5).
     */
    void open(MapNode n, Difficulty lens) {
        node = n;
        holdJacket = false;
        Difficulty d = n.launch ? Difficulty.SURFACE : fallback(n, lens);
        if (!shown) {
            boolean reentering = closing;
            shown = true;
            closing = false;
            if (!reentering) {
                full = false;
                spring.snap(0f);
                pos = 0f;
            } else {
                spring.snap(pos);
            }
            spring.setTarget(targetPos());
            swap = SWAP_NONE;
            setDiff(d, false);
            setContent(n, false);
            return;
        }
        if (content != n) {
            if (swap == SWAP_NONE || swap == SWAP_IN) {
                swap = SWAP_OUT;
                swapT = 0f;
            }
            setDiff(d, false);
        } else {
            setDiff(d, true);
        }
    }

    /** The difficulty the card opens on: the lens, else the nearest lower, else the nearest higher. */
    static Difficulty fallback(MapNode n, Difficulty lens) {
        int o = lens.ordinal();
        for (int i = o; i >= 0; i--) if (n.has[i]) return Difficulty.byOrdinal(i);
        for (int i = o + 1; i < 4; i++) if (n.has[i]) return Difficulty.byOrdinal(i);
        return lens;
    }

    /** Sets the shown difficulty after a lens change made elsewhere (keyboard, HUD). */
    void setDifficulty(Difficulty lens) {
        if (node != null && !node.launch) setDiff(fallback(node, lens), true);
    }

    private void chooseDifficulty(Difficulty d) {
        if (content == null || content.launch || d == diff) return;
        actions.cardLens(d);
        setDiff(d, true);
        Haptics.light();
    }

    private void stepDifficulty(int dir) {
        if (content == null || content.launch) return;
        int o = diff.ordinal() + dir;
        while (o >= 0 && o < 4 && !content.has[o]) o += dir;
        if (o < 0 || o > 3) {
            chips[diff.ordinal()].shake(); // the end of the row: missing charts have no chip to shake
            return;
        }
        chooseDifficulty(Difficulty.byOrdinal(o));
    }

    private void setDiff(Difficulty d, boolean animate) {
        boolean changed = d != diff;
        diff = d;
        for (int i = 0; i < 4; i++) chips[i].setSelected(i == d.ordinal(), animate && changed, time);
        if (!changed) return;
        if (animate && content != null && swap == SWAP_NONE) {
            recordOutT = 0f;
        } else {
            shownDiff = d;
            recordOutT = -1f;
            recordT = 1f;
        }
    }

    private void setContent(MapNode n, boolean animate) {
        content = n;
        shownDiff = diff;
        recordOutT = -1f;
        recordT = 1f;
        for (int i = 0; i < 4; i++) {
            CardChip c = chips[i];
            Difficulty d = c.d;
            boolean has = !n.launch && n.has[i];
            c.setChart(n.song != null && has ? n.song.constantBase(d) : null,
                n.song != null ? n.song.constantMark(d) : null, !has);
            c.setVisible(has); // no chip at all for a chart the song lacks (Kelvin 2026-10-17)
            c.setSelected(i == diff.ordinal(), false, time);
            if (animate) c.rollIn();
        }
        auto.setVisible(!n.launch);
        auto.setEnabled(!n.locked && !n.forSale);
        descend.setText(n.launch ? "DIVE IN" : auto.isOn() ? "WATCH" : "DESCEND");
        lockTexts = new String[n.locked ? Math.max(1, n.lockConds.length) : 0];
        for (int i = 0; i < lockTexts.length; i++) {
            String c = i < n.lockConds.length ? n.lockConds[i] : "?";
            lockTexts[i] = r.zh ? "先通关 " + c : "CLEAR " + c + " FIRST";
        }
        lockIdx = 0;
        lockT = LOCK_FADE;
        layerText.setLength(0);
        if (n.launch) {
            layerText.append("00 · SURFACE");
        } else {
            layerText.append(r.zh ? world.cardHeader : world.cardHeaderEn);
        }
        metaText.setLength(0);
        if (n.launch) {
            metaText.append("THE TWO SIGNAL LINES · THE BASICS");
        } else if (n.song != null) {
            SongMeta s = n.song;
            // the tempo range when the song changes tempo, e.g. BPM 140-400 (Kelvin 2026-10-13 「显示范围」)
            metaText.append("BPM ");
            int lo = Math.round(s.bpmMin());
            int hi = Math.round(s.bpmMax());
            Fonts.appendInt(metaText, lo);
            if (hi != lo) {
                metaText.append('-');
                Fonts.appendInt(metaText, hi);
            }
            // the charter and illustrator credits show on the DIVE instead (Kelvin 2026-10-13 16:18)
        }
        rec.invalidate();
        titleT = 0f; // a new song's title starts its marquee over (Kelvin 07:01; a difficulty change keeps it)
        if (animate) jacketGlitch = 2f / 60f;
    }

    /** Starts the exit (220 ms {@code pow2In}); the jacket is released when it ends. */
    void close() {
        close(false);
    }

    /**
     * Starts the exit (220 ms {@code pow2In}). With {@code keepJacket} the jacket stays loaded
     * after the exit (the Descent: the screens that follow may draw the same jacket) until the card
     * opens on another song or {@link #releaseJacket()}.
     */
    void close(boolean keepJacket) {
        holdJacket = keepJacket;
        if (!shown) return;
        shown = false;
        closing = true;
        closeT = 0f;
        closeFrom = pos;
        cancel();
    }

    /** Hides immediately (screen hidden). The jacket is kept (it may be the song in play). */
    void snapClosed() {
        shown = false;
        closing = false;
        pos = 0f;
        spring.snap(0f);
        node = null;
        content = null;
        swap = SWAP_NONE;
        cancel();
    }

    /** Re-validates the kept jacket after the screen shows again (another screen may have released it). */
    void revalidate() {
        if (jacketSong == null) return;
        // a loaded jacket may have been released; a pending request may have been dropped
        if (jacket != null ? !library.isJacketLoaded(jacketSong) : jacketTried) {
            jacket = null;
            jacketTried = false;
            jacketWait = 0f;
        }
    }

    /** Releases the jacket the card holds (when the map leaves for good, e.g. dispose). */
    void releaseJacket() {
        holdJacket = false;
        if (jacketSong != null && (jacket != null || jacketTried)) library.releaseJacket(jacketSong);
        jacket = null;
        wash = null;
        jacketSong = null;
        jacketTried = false;
    }

    boolean isOpen() {
        return shown;
    }

    /** True while the card is visible at all (open, opening or closing). */
    boolean isVisible() {
        return pos > 0.5f && (shown || closing);
    }

    MapNode node() {
        return shown ? node : null;
    }

    Difficulty difficulty() {
        return diff;
    }

    /** Open amount: 0 closed, 1 at the (peek) detent. */
    float amount() {
        if (sheet()) return Math.max(0f, pos) / Math.max(1f, peekH());
        return Math.max(0f, pos) / sideWidth();
    }

    /** True for the bottom-sheet layout: never since Kelvin 2026-10-19 03:47 (4:3 shows the side card stretched). */
    static boolean sheet() {
        return false;
    }

    /**
     * The card scale: {@link MapUi#k()}, reduced only as far as the content must to fit (the
     * side card's 16:10 blueprint between ST and SB, as a whole like the result screen; the
     * sheet's action row between SL and SR), never below 0.8.
     */
    static float scale() {
        float k = MapUi.k();
        float fit;
        if (sheet()) {
            fit = (Ui.safeRight() - Ui.safeLeft()) / SHEET_ROW_W;
        } else {
            float w = (Ui.aspect() >= 2f ? MapStyle.CARD_W_WIDE : MapStyle.CARD_W) - 2f * MapStyle.CARD_PAD;
            fit = (Ui.safeTop() - Ui.safeBottom()) / (B_FIXED + w * 9f / 16f + B_GAP_SUM);
        }
        return Math.max(0.8f, Math.min(k, fit));
    }

    /** Full card width for the side layout (760 at aspect ≥ 2, else 700, × {@link #scale()}). */
    static float sideWidth() {
        return (Ui.aspect() >= 2f ? MapStyle.CARD_W_WIDE : MapStyle.CARD_W) * scale();
    }

    private static float peekH() {
        return Ui.safeBottom() - Ui.bottom() + MapStyle.SHEET_PEEK * scale();
    }

    private static float fullH() {
        return Ui.safeBottom() - Ui.bottom() + MapStyle.SHEET_FULL * scale();
    }

    private float targetPos() {
        if (sheet()) return full ? fullH() : peekH();
        return sideWidth();
    }

    /** Left edge of the side card in HUD units (VR when closed). */
    float left() {
        return sheet() ? Ui.right() : Ui.right() - Math.max(0f, pos);
    }

    /** Top of the sheet in HUD units (VB when closed). */
    float top() {
        return sheet() ? Ui.bottom() + Math.max(0f, pos) : Ui.bottom();
    }

    /** Sheet top at its current detent (the focus point uses the destination, not the spring). */
    float targetTop() {
        return Ui.bottom() + (full ? fullH() : peekH());
    }

    /** True while the user drags the card or the sheet. */
    boolean dragging() {
        return dragMode == DRAG_CARD;
    }

    // =================================================================== layout
    private void layout() {
        k = scale();
        c20 = MapUi.cap(20f, k);
        float P = Ui.touchTarget();
        float ic = MapStyle.HEADER_ICON * k;
        float pitch = Math.max(P, ic);
        float pad = MapStyle.CARD_PAD * k, gap = MapStyle.CHIP_GAP * k;
        float rowH = MapStyle.CHIP_ROW_H * k, actH = MapStyle.ACTION_H * k, actGap = MapStyle.ACTION_GAP * k;
        for (int i = 0; i < 4; i++) chips[i].k = k;
        auto.k = k;
        descend.k = k;
        if (sheetLayout) {
            sheetTop = Ui.bottom() + Math.max(0f, pos);
            float peekTop = Ui.bottom() + peekH();
            base = Ui.safeBottom() + Math.min(0f, sheetTop - peekTop);
            fullK = MathUtils.clamp((pos - peekH()) / (fullH() - peekH()), 0f, 1f);
            float sl = Ui.safeLeft() + pad, sr = Ui.safeRight() - pad;
            cx0 = sl;
            cx1 = sr;
            cw = sr - sl;
            float chipW = MapStyle.SHEET_CHIP_W * k;
            for (int i = 0; i < 4; i++) {
                chips[i].setBounds(sl + chipSlot(i) * (chipW + gap), base + 12f * k, chipW, rowH);
            }
            float dw = MapStyle.SHEET_DESCEND_W * k, aw = MapStyle.AUTO_W_WIDE * k;
            descend.setBounds(sr - dw, base, dw, actH);
            auto.setBounds(sr - dw - actGap - aw, base, aw, actH);
            if (content != null && content.launch) {
                descend.setBounds(sr - dw - actGap - aw, base, dw + actGap + aw, actH);
            }
            float hy = sheetTop - 44f * k;
            float hx = Ui.safeRight() - 8f * k - ic * 0.5f;
            next.setBounds(hx - ic * 0.5f, hy - ic * 0.5f, ic, ic);
            prev.setBounds(hx - pitch - ic * 0.5f, hy - ic * 0.5f, ic, ic);
            jSize = MapStyle.JACKET * k;
            jW = jSize;
            jH = jSize;
            jx = sl;
            jTop = base + 592f * k;
            sheetBestLeft = sr - 96f * k - bestU * MapUi.cap(28f, k);
            crop();
            return;
        }
        float W = sideWidth();
        boolean wide = Ui.aspect() >= 2f;
        x0 = Ui.right() - Math.max(0f, pos);
        cx0 = x0 + pad;
        cx1 = x0 + W - pad;
        cw = cx1 - cx0;
        float st = Ui.safeTop(), sb = Ui.safeBottom();
        jx = cx0;
        jW = cw;
        jH = cw * 9f / 16f;
        // the round 5 blueprint from the top (Kelvin 2026-10-19 07:00 「depth/plays稍微往下挪一点，选歌难度往下挪，然后
        // next那一行也往下挪」): taller screens share their extra height between the four gaps, one too short even at
        // the 0.8 floor closes them towards their minimums
        float free = (st - sb) / k - B_FIXED - jH / k - B_GAP_SUM;
        float shut = free < 0f ? Math.min(1f, -free / (B_GAP_SUM - B_GAP_MIN_SUM)) : 0f;
        for (int i = 0; i < 4; i++) {
            float g = free > 0f ? B_GAP[i] + free * 0.25f : B_GAP[i] - shut * (B_GAP[i] - B_GAP_MIN[i]);
            gaps[i] = g * k;
        }
        headY = st - B_HEAD * k;
        // ‹ flush with the jacket's left edge, › with its right one (Kelvin 2026-10-19 13:37 「放在两边」)
        prev.setBounds(cx0, headY - ic * 0.5f, ic, ic);
        next.setBounds(cx1 - ic, headY - ic * 0.5f, ic, ic);
        jTop = headY - B_HEAD_HALF * k - gaps[0];
        bandTop = jTop - jH - B_BAND_GAP * k;
        bandBot = bandTop - B_BAND_H * k;
        titleBase = bandTop - B_TITLE * k;
        artistBase = bandTop - B_ARTIST * k;
        ringR = B_RING_R * k;
        float ringTop = bandBot - gaps[1];
        ringX = cx1 - ringR;
        ringY = ringTop - ringR;
        scoreBase = ringTop - B_SCORE * k;
        depthBase = ringTop - B_DEPTH * k;
        chipTop = depthBase - gaps[2];
        rowH = B_CHIP_H * k;
        float chipW = (cw - 3f * gap) / 4f;
        for (int i = 0; i < 4; i++) {
            chips[i].setBounds(cx0 + chipSlot(i) * (chipW + gap), chipTop - rowH, chipW, rowH);
        }
        // AUTO and DESCEND on SB (24 above the screen's bottom, like the result screen's buttons), NEXT 24 over them
        actionY = sb;
        goalTop = actionY + actH + (B_NEXT_GAP + B_NEXT_H) * k;
        float autoW = (wide ? MapStyle.AUTO_W_WIDE : MapStyle.AUTO_W) * k;
        auto.setBounds(cx0, actionY, autoW, actH);
        if (content != null && content.launch) {
            descend.setBounds(cx0, actionY, cw, actH);
        } else {
            descend.setBounds(cx0 + autoW + actGap, actionY, cw - autoW - actGap, actH);
        }
        crop();
    }

    /** The jacket's centre crop to the box's shape (16:9 on the side card). */
    private void crop() {
        cu0 = 0f;
        cv0 = 0f;
        cu1 = 1f;
        cv1 = 1f;
        if (jacket == null || jW <= 0f || jH <= 0f) return;
        float tw = jacket.getWidth(), th = jacket.getHeight(), box = jW / jH;
        if (tw > th * box) {
            cu0 = (tw - th * box) * 0.5f / tw;
            cu1 = 1f - cu0;
        } else {
            cv0 = (th - tw / box) * 0.5f / th;
            cv1 = 1f - cv0;
        }
    }

    /** Place of chip {@code i} in the row: charts the song lacks have no chip, so the rest close up from the left. */
    private int chipSlot(int i) {
        if (content == null || !content.has[i]) return i;
        int s = 0;
        for (int j = 0; j < i; j++) if (content.has[j]) s++;
        return s;
    }

    // =================================================================== input
    private boolean inside(float px, float py) {
        if (!shown || pos < 8f) return false;
        if (sheetLayout) return py <= sheetTop;
        return px >= x0;
    }

    private boolean onChipRow(float px, float py) {
        if (content == null || content.launch) return false;
        CardChip a = chips[0], b = chips[3];
        return px >= a.x() - 8f * k && px <= b.x() + b.width() + 8f * k && py >= a.y() - 16f * k
            && py <= a.y() + a.height() + 16f * k;
    }

    /** Touch down (HUD units); true when the card claims it. */
    boolean touchDown(float px, float py, int p) {
        if (!inside(px, py)) return false;
        if (ptr >= 0) return true;
        ptr = p;
        downX = px;
        downY = py;
        dragMode = DRAG_NONE;
        chipSteps = 0;
        vt.reset();
        downOnChips = onChipRow(px, py);
        pressed = null;
        if (spring.velocity() != 0f || closing) spring.snap(pos);
        for (Pressable c : controls) {
            if (c.touchDown(px, py, p)) {
                pressed = c;
                break;
            }
        }
        return true;
    }

    /** Touch dragged; true when the pointer belongs to the card. */
    boolean touchDragged(float px, float py, int p) {
        if (p != ptr) return false;
        float dx = px - downX, dy = py - downY;
        float slop = Ui.slop();
        if (dragMode == DRAG_NONE && dx * dx + dy * dy > slop * slop) {
            boolean horiz = Math.abs(dx) > Math.abs(dy);
            int mode = DRAG_IGNORE;
            if (downOnChips && horiz) mode = DRAG_CHIPS;
            else if (!sheetLayout && horiz) mode = pinned ? DRAG_IGNORE : DRAG_CARD;
            else if (sheetLayout && !horiz) mode = DRAG_CARD;
            if (mode != DRAG_IGNORE || pressed == null) {
                if (pressed != null) {
                    pressed.cancel();
                    pressed = null;
                }
                dragMode = mode;
                if (mode == DRAG_CARD) {
                    dragStart = pos;
                    downX = px;
                    downY = py;
                    dx = 0f;
                    dy = 0f;
                    vt.add(pos);
                }
            }
        }
        if (pressed != null) {
            pressed.touchDragged(px, py, p);
            if (!pressed.isPressed()) pressed = null;
            return true;
        }
        if (dragMode == DRAG_CARD) {
            if (sheetLayout) {
                float v = dragStart + dy;
                float hi = fullH();
                if (v > hi) v = hi + rubber(v - hi, 200f);
                pos = Math.max(0f, v);
            } else {
                float W = sideWidth();
                float v = dragStart - dx;
                if (v > W) v = W + rubber(v - W, 120f);
                pos = Math.max(0f, v);
            }
            spring.snap(pos);
            vt.add(pos);
        } else if (dragMode == DRAG_CHIPS) {
            int steps = (int) (-dx / (CHIP_STEP * k));
            while (chipSteps < steps) {
                chipSteps++;
                stepDifficulty(1);
            }
            while (chipSteps > steps) {
                chipSteps--;
                stepDifficulty(-1);
            }
        }
        return true;
    }

    private static float rubber(float over, float d) {
        float k = MapStyle.RUBBER * over;
        return k * d / (d + k);
    }

    /** Touch up; true when the pointer belonged to the card. */
    boolean touchUp(float px, float py, int p) {
        if (p != ptr) return false;
        ptr = -1;
        if (pressed != null) {
            Pressable c = pressed;
            pressed = null;
            c.touchUp(px, py, p);
            dragMode = DRAG_NONE;
            return true;
        }
        int mode = dragMode;
        dragMode = DRAG_NONE;
        if (mode == DRAG_CARD) {
            release();
        } else if (mode == DRAG_NONE && sheetLayout && py >= sheetTop - 72f * k && shown) {
            // a tap on the handle region toggles the detent
            full = !full;
            spring.setTarget(targetPos());
            actions.cardDetent();
            Haptics.light();
        } else if (mode == DRAG_CHIPS && chipSteps == 0) {
            float dx = px - downX;
            if (Math.abs(dx) > CHIP_STEP * k * 0.4f) stepDifficulty(dx < 0f ? 1 : -1);
        }
        return true;
    }

    private void release() {
        float v = vt.velocity();
        if (sheetLayout) {
            float peek = peekH(), fh = fullH();
            float proj = pos + v * 0.15f;
            boolean fromPeek = !full;
            boolean dismiss = proj < peek * 0.6f
                || (fromPeek && (v < -MapStyle.DISMISS_V || pos < peek * (1f - MapStyle.DISMISS_FRACTION)));
            if (dismiss) {
                actions.cardClosed();
                return;
            }
            boolean nf = Math.abs(proj - fh) < Math.abs(proj - peek);
            if (nf != full) {
                full = nf;
                actions.cardDetent();
            }
        } else {
            float W = sideWidth();
            if (v < -MapStyle.DISMISS_V || pos < W * (1f - MapStyle.DISMISS_FRACTION)) {
                actions.cardClosed();
                return;
            }
        }
        spring.setValue(pos).setVelocity(v).setTarget(targetPos());
    }

    /** Cancels every press and drag. */
    void cancel() {
        for (Pressable c : controls) c.cancel();
        pressed = null;
        if (dragMode == DRAG_CARD && shown) spring.setValue(pos).setTarget(targetPos());
        dragMode = DRAG_NONE;
        ptr = -1;
    }

    void mouseMoved(float px, float py) {
        for (Pressable c : controls) c.mouseMoved(px, py);
    }

    /** Keyboard Enter on the open card: DESCEND. */
    void fireDescend() {
        if (shown && content != null && swap != SWAP_OUT) descend.fire();
    }

    /** Keyboard: step the difficulty. */
    void keyStep(int dir) {
        if (shown) stepDifficulty(dir);
    }

    // =================================================================== frame
    /**
     * Advances the container, content swap, record fade, jacket and controls.
     *
     * @param beat heartbeat phase 0..1 (DESCEND glow)
     */
    void update(float dt, float now, float beat) {
        time = now;
        boolean sh = sheet();
        if (sh != sheetLayout) {
            sheetLayout = sh;
            if (shown) {
                pos = targetPos();
                spring.snap(pos);
            }
        }
        if (closing) {
            closeT += dt;
            float p = Math.min(1f, closeT / MapStyle.CARD_EXIT);
            pos = closeFrom * (1f - Interpolation.pow2In.apply(p));
            if (p >= 1f) {
                closing = false;
                pos = 0f;
                spring.snap(0f);
                content = null;
                node = null;
                swap = SWAP_NONE;
                if (!holdJacket) releaseJacket();
            }
        } else if (shown && dragMode != DRAG_CARD) {
            spring.setTarget(targetPos());
            pos = spring.update(dt);
        }
        // content swap
        if (swap == SWAP_OUT) {
            swapT += dt;
            if (swapT >= MapStyle.SWAP_OUT) {
                if (node != null) setContent(node, true);
                swap = SWAP_IN;
                swapT = 0f;
            }
        } else if (swap == SWAP_IN) {
            swapT += dt;
            if (swapT >= MapStyle.SWAP_IN) swap = SWAP_NONE;
        }
        // record crossfade (two halves of 80 ms)
        if (recordOutT >= 0f) {
            recordOutT += dt;
            if (recordOutT >= MapStyle.RECORD_FADE * 0.5f) {
                recordOutT = -1f;
                shownDiff = diff;
                recordT = 0f;
            }
        } else if (recordT < 1f) {
            recordT = Math.min(1f, recordT + dt / (MapStyle.RECORD_FADE * 0.5f));
        }
        if (content != null && !content.launch && content.song != null) {
            if (rec.update(profiles, content.song, shownDiff)) {
                goalPrefixU = r.uiWidth(rec.goalPrefix, 1f);
                goalRestU = r.uiWidth(rec.goalRest, 1f);
                bestU = r.fixedWidth(rec.bestText, 28f) / 28f;
            }
        }
        // accent recolours toward the difficulty (160 ms)
        if (content != null) accentTarget.set(content.launch ? Theme.GOLD : Theme.difficultyAccent(diff.ordinal()));
        float k = Math.min(1f, dt / 0.08f);
        accent.lerp(accentTarget, k);
        updateJacket(dt);
        if (content != null) descend.setText(content.launch ? "DIVE IN" : auto.isOn() ? "WATCH" : "DESCEND");
        updateLock(dt);
        // a single not bought yet: HOLD TO BUY in place of DESCEND, AUTO off (Kelvin 2026-10-17)
        boolean sale = content != null && content.forSale && !content.locked && !content.launch;
        descend.setBuy(sale, sale ? content.price : 0, profiles.shells(), r.zh);
        if (content != null && !content.launch) auto.setEnabled(!content.locked && !content.forSale);
        descend.setAuto(auto.isOn() && content != null && !content.launch);
        descend.setAccent(accent);
        descend.beat = beat;
        layout();
        if (content != null && !sheetLayout) {
            titleT += dt;
            if (content != titleFor || k != titleK) measureTitle();
            if (!content.launch && rec.played) measureRecordRow();
        }
        for (Pressable c : controls) c.update(dt);
        if (jacketGlitch > 0f) jacketGlitch -= dt;
    }

    private String title() {
        return content.launch ? "LAUNCH POINT" : content.song != null ? content.song.title() : "";
    }

    /**
     * The side card title's cap and whether it scrolls (Kelvin 2026-10-19 07:01 「这里最多显示30个字符，太长了就滚动显示」):
     * 34 when it fits the band, shrunk as far as 29 when it must; past 30 characters, or too wide even at 29 (CJK
     * titles can be with fewer), it scrolls at 29.
     */
    private void measureTitle() {
        titleFor = content;
        titleK = k;
        String t = title();
        float hi = MapUi.cap(TITLE_CAP, k), lo = MapUi.cap(TITLE_MIN, k);
        float w = r.boldWidth(t, hi);
        boolean scroll = t.codePointCount(0, t.length()) > TITLE_CHARS;
        float cap = hi;
        if (scroll) {
            cap = lo;
        } else if (w > cw && w > 0f) {
            cap = Math.max(lo, hi * cw / w);
            scroll = r.boldWidth(t, cap) > cw + 0.5f;
            if (scroll) cap = lo;
        }
        titleCap = cap;
        titleW = r.boldWidth(t, cap);
        titleScroll = scroll;
    }

    /**
     * Places the DEPTH / PLAYS row under the score (round 5): labels at 13 tracked 0.12, values bold 17, 10 from
     * label to value and 32 between the pairs, shrunk as one when it would come within 16 of the grade ring.
     */
    private void measureRecordRow() {
        float lc = MapUi.cap(13f, k), vc = MapUi.cap(17f, k);
        float l1 = trackedWidth(DEPTH_WORD, lc), v1 = r.boldWidth(rec.depthText, vc);
        float l2 = trackedWidth(PLAYS_WORD, lc), v2 = r.boldWidth(rec.playsValue, vc);
        float g1 = 10f * k, g2 = 32f * k;
        float total = l1 + g1 + v1 + g2 + l2 + g1 + v2, room = ringX - ringR - 16f * k - cx0;
        float f = total > room && total > 0f ? room / total : 1f;
        rowLabelCap = lc * f;
        rowValueCap = vc * f;
        rowX[0] = cx0;
        rowX[1] = rowX[0] + (l1 + g1) * f;
        rowX[2] = rowX[1] + (v1 + g2) * f;
        rowX[3] = rowX[2] + (l2 + g1) * f;
    }

    /** Width of a label drawn by {@link #tracked}. */
    private float trackedWidth(String text, float cap) {
        float w = 0f;
        for (int i = 0; i < text.length(); i++) {
            one.setLength(0);
            one.append(text.charAt(i));
            w += r.uiWidth(one, cap);
        }
        return w + LABEL_TRACK * cap * Math.max(0, text.length() - 1);
    }

    /** A label from the UI raster with {@link #LABEL_TRACK} between its letters, one letter at a time. */
    private void tracked(Batch b, String text, float cap, float x, float y, Color c, float a) {
        for (int i = 0; i < text.length(); i++) {
            one.setLength(0);
            one.append(text.charAt(i));
            x += r.ui(b, one, cap, x, y, Align.left, c, a) + LABEL_TRACK * cap;
        }
    }

    /** Cycles a locked song's conditions on DESCEND: hold 3 s, fade out .5 s, next fades in .5 s. */
    private void updateLock(float dt) {
        if (content == null || lockTexts.length == 0) {
            descend.setLocked(false, "", 1f);
            return;
        }
        float a = 1f;
        if (lockTexts.length > 1) {
            // one period per condition: fade in, hold, fade out (the card opens on a shown one)
            lockT += dt;
            float period = LOCK_FADE + LOCK_HOLD + LOCK_FADE;
            if (lockT >= period) {
                lockT -= period;
                lockIdx = (lockIdx + 1) % lockTexts.length;
            }
            if (lockT < LOCK_FADE) a = lockT / LOCK_FADE;
            else if (lockT > LOCK_FADE + LOCK_HOLD) a = (period - lockT) / LOCK_FADE;
        }
        descend.setLocked(true, lockTexts[lockIdx], MathUtils.clamp(a, 0f, 1f));
    }

    private void updateJacket(float dt) {
        SongMeta js = content != null && !content.launch ? content.song : null;
        if (js == null && holdJacket) return;
        if (js != jacketSong) {
            if (jacketSong != null && (jacket != null || jacketTried)) library.releaseJacket(jacketSong);
            jacketSong = js;
            jacket = null;
            wash = null;
            jacketTried = false;
            jacketWait = 0f;
            reveal = 0f;
        }
        if (js == null) return;
        boolean settled = shown && swap != SWAP_OUT && Math.abs(pos - targetPos()) < 6f && dragMode != DRAG_CARD;
        if (!jacketTried && settled) {
            jacketWait += dt;
            if (jacketWait >= MapStyle.JACKET_DELAY) {
                jacketTried = true;
                library.requestJacket(js);
            }
        }
        if (jacketTried && jacket == null) {
            jacket = library.jacketIfReady(js);
            if (jacket != null) {
                reveal = 0f;
                jacketGlitch = 2f / 60f;
            }
        }
        if (jacket != null && reveal < 1f) reveal = Math.min(1f, reveal + dt / MapStyle.JACKET_REVEAL);
        // the glow's wash: prepared from the jacket's own decode, so this only uploads it
        if (jacket != null && wash == null && !sheetLayout) wash = library.washIfReady(js);
    }

    // =================================================================== drawing helpers
    private float cardAlpha() {
        return MathUtils.clamp(pos / 60f, 0f, 1f);
    }

    /** Content alpha of the song swap. */
    private float swapAlpha() {
        if (swap == SWAP_OUT) return 1f - swapT / MapStyle.SWAP_OUT;
        if (swap == SWAP_IN) return Anim.POW2_OUT.apply(swapT / MapStyle.SWAP_IN);
        return 1f;
    }

    /** Vertical offset of the content during the swap (old rises, new rises in from below). */
    private float swapDy() {
        if (swap == SWAP_OUT) return 16f * k * swapT / MapStyle.SWAP_OUT;
        if (swap == SWAP_IN) return -16f * k * (1f - Anim.POW2_OUT.apply(swapT / MapStyle.SWAP_IN));
        return 0f;
    }

    private float recordAlpha() {
        if (recordOutT >= 0f) return 1f - recordOutT / (MapStyle.RECORD_FADE * 0.5f);
        return recordT;
    }

    /** Alpha of a full-only sheet row whose top is at {@code y} (revealed as the sheet rises). */
    private float rowK(float y) {
        if (!sheetLayout) return 1f;
        return MathUtils.clamp((sheetTop - 20f * k - y) / (48f * k), 0f, 1f);
    }

    private float jacketFloat() {
        return r.reduceMotion ? 0f : 6f * k * MathUtils.sin(time * MathUtils.PI2 / 7f);
    }

    private boolean visibleNow() {
        return content != null && (shown || closing) && pos > 0.5f;
    }

    /** A sheet row's y: {@code v} (scale-1 units above the content base) scaled. */
    private float row(float v) {
        return base + v * k;
    }

    // =================================================================== drawing: atlas
    /** Atlas phase: panel, header, jacket frame, record shapes, chips, AUTO and DESCEND. */
    void drawShapes(Batch b) {
        if (!visibleNow()) return;
        float a = cardAlpha();
        float ca = a * swapAlpha(), dy = swapDy();
        float rad = MapStyle.CARD_RADIUS * k;
        if (sheetLayout) {
            float w = Ui.worldW();
            c1.set(Theme.SHADOW);
            c2.set(Theme.SHADOW);
            c2.a = 0f;
            draw.rectGradientV(Ui.left(), sheetTop, w, 36f * k, c1, c2, 0.45f * a);
            draw.roundRect(Ui.left(), Ui.bottom() - 40f, w, sheetTop - Ui.bottom() + 40f, rad, Theme.ABYSS_BLACK,
                MapStyle.UNDERLAY * a);
            draw.roundRect(Ui.left(), Ui.bottom() - 40f, w, sheetTop - Ui.bottom() + 40f, rad, Theme.PANEL, a);
            draw.rect(Ui.left() + rad, sheetTop - 2f, w - 2f * rad, 2f, accent, 0.45f * a);
            float hw = MapStyle.HANDLE_W * k, hh = MapStyle.HANDLE_H * k;
            draw.pill(Ui.CX - hw * 0.5f, sheetTop - MapStyle.HANDLE_DY * k - hh, hw, hh, Theme.INK2, 0.8f * a);
        } else {
            float h = Ui.worldH();
            c1.set(Theme.SHADOW);
            c2.set(Theme.SHADOW);
            c1.a = 0f;
            draw.rectGradientH(x0 - 40f * k, Ui.bottom(), 40f * k, h, c1, c2, 0.45f * a);
            draw.roundRect(x0, Ui.bottom() - 30f, sideWidth() + 60f, h + 60f, rad, Theme.ABYSS_BLACK,
                MapStyle.UNDERLAY * a);
            draw.roundRect(x0, Ui.bottom() - 30f, sideWidth() + 60f, h + 60f, rad, Theme.PANEL, a);
            // the title band across the whole card, fixed height whatever the title's size (round 5)
            float bw = sideWidth() + 60f, lw = 1.2f;
            draw.rect(x0, bandBot, bw, bandTop - bandBot, BAND_FILL, a);
            draw.rect(x0, bandBot - lw * 0.5f, bw, lw, BAND_LINE, a);
            draw.rect(x0, bandTop - lw * 0.5f, bw, lw, BAND_LINE, a);
            draw.rect(x0, Ui.bottom() + 18f, 2f, h - 36f, accent, 0.45f * a);
        }
        prev.draw(b, a);
        next.draw(b, a);
        drawJacketFrame(ca, dy);
        if (!content.launch) drawRecordShapes(ca * recordAlpha(), dy);
        for (CardChip c : chips) c.draw(b, a);
        auto.draw(b, a);
        descend.draw(b, a);
    }

    /** Bottom of the jacket box (the sheet's square floats slowly; the side card's 16:9 jacket stays put). */
    private float jacketY(float dy) {
        return jTop - jH + (sheetLayout ? jacketFloat() : 0f) + dy;
    }

    /** Corner radius of the jacket box. */
    private float jacketR() {
        return (sheetLayout ? MapStyle.JACKET_RADIUS : JACKET_R) * k;
    }

    private void drawJacketFrame(float ca, float dy) {
        float rk = rowK(jTop);
        float a = ca * rk;
        if (a <= 0.01f) return;
        float jy = jacketY(dy), w = jW, h = jH, rad = jacketR();
        float cx = jx + w * 0.5f, cy = jy + h * 0.5f, s = Math.min(w, h);
        if (sheetLayout || content.launch) {
            draw.glowDot(cx, cy - 8f * k, s * 1.6f, accent, 0.20f * a);
            draw.glowDot(cx, cy - 18f * k, s * 1.1f, Theme.SHADOW, 0.6f * a);
        }
        if (content.launch) {
            draw.roundRect(jx, jy, w, h, rad, Theme.SEAM, a);
            float o = 3f * k;
            draw.roundRectStroke(jx - o, jy - o, w + 2f * o, h + 2f * o, rad + o, 2f, Theme.GOLD, 0.9f * a);
            float sink = r.reduceMotion ? 0f : 10f * k * MathUtils.sin(time * MathUtils.PI2 / 0.9f);
            draw.circle(cx, cy, s * 0.36f, 3f * k, Theme.GOLD, 0.5f * a);
            Icons.draw(draw.shapes(), Icons.Icon.CHEVRONS_DOWN, cx, cy + sink, s * 0.42f, Theme.GOLD, a);
            return;
        }
        boolean full = jacket != null && reveal >= 1f;
        if (!full) {
            thumbs.average(r.slotOf(content), c1);
            Theme.mix(Theme.SEAM, c1, 0.55f, c1);
            draw.roundRect(jx, jy, w, h, rad, c1, a);
            // sonar scan over the placeholder while it waits for the jacket
            if (jacket == null) {
                float p = r.reduceMotion ? 0.5f : (time % 1.6f) / 1.6f;
                draw.rect(jx + 8f * k, jy + h * (1f - p) - 1f, w - 16f * k, 2f, Theme.INK1, 0.18f * a);
            }
        }
        if (jacket != null && reveal < 1f) {
            float ry = jy + h * (1f - Anim.POW2_OUT.apply(reveal));
            draw.rect(jx, ry - 4f * k, w, 4f * k, Theme.INK1, 0.85f * a);
            draw.glowDot(jx + w * 0.5f, ry - 2f * k, w * 0.9f, accent, 0.25f * a);
        }
        if (sheetLayout) {
            float o = 4f * k;
            draw.roundRectStroke(jx - o, jy - o, w + 2f * o, h + 2f * o, rad + o, 2.5f, accent, 0.9f * a);
        }
    }

    private void drawRecordShapes(float a, float dy) {
        if (a <= 0.01f) return;
        if (!sheetLayout) {
            if (rec.played) {
                // the grade ring: 148 across, its right edge on the content edge, no fill (round 5)
                float ry = ringY + dy;
                rec.gradeColor(c1);
                draw.circle(ringX, ry, ringR - 2f * k, 4f * k, c1, a);
            }
            if (!autoRow()) drawGoalIcon(goalRight(), goalTop - MapStyle.GOAL_ROW_H * k * 0.5f + dy, a);
        } else {
            if (rec.played) {
                float py = row(190f) + dy;
                rec.gradeColor(c1);
                float pw = 80f * k, ph = 44f * k;
                draw.pill(cx1 - pw, py - 4f * k, pw, ph, c1, 0.2f * a);
                draw.roundRectStroke(cx1 - pw, py - 4f * k, pw, ph, ph * 0.5f, 2f, c1, 0.9f * a);
            }
            float rk = rowK(row(520f));
            if (rk > 0f) drawGoalIcon(sheetColB() + goalTotalW(), row(500f) + dy, a * rk);
        }
    }

    private float sheetColB() {
        return cx0 + jSize + 32f * k + 680f * k;
    }

    /** True while AUTO is on: AUTOPLAY · NOT RECORDED takes NEXT's row. */
    private boolean autoRow() {
        return auto.isOn() && content != null && !content.launch && !content.locked;
    }

    /** Goal copy gaps: text, 10, ▼ (18), 10, text. */
    private float goalGap() {
        return (10f + 18f + 10f) * k;
    }

    private float goalTotalW() {
        if (rec.goal == CardRecord.GOAL_NONE) return 0f;
        if (rec.goal == CardRecord.GOAL_MAXED) return goalPrefixU * c20;
        return goalPrefixU * c20 + goalGap() + goalRestU * c20;
    }

    /** Right edge of the side card's micro-goal: centred on the content (Kelvin 2026-10-19 16:36). */
    private float goalRight() {
        return (cx0 + cx1 + goalTotalW()) * 0.5f;
    }

    /** The procedural ▼ of the micro-goal (right edge of the copy at {@code right}). */
    private void drawGoalIcon(float right, float cy, float a) {
        if (rec.goal != CardRecord.GOAL_NEXT && rec.goal != CardRecord.GOAL_PURE) return;
        float x = right - goalRestU * c20 - 19f * k;
        float bob = r.reduceMotion ? 0f : 2f * k * MathUtils.sin(time * MathUtils.PI2 / 1.2f);
        Icons.draw(draw.shapes(), Icons.Icon.TRIANGLE_DOWN, x, cy + bob, 30f * k, accent, a);
    }

    // =================================================================== drawing: jacket
    /**
     * Jacket-texture phase: the glow (side card), the jacket with rounded corners, the scanline reveal and the swap
     * G2, then the side card's thin stroke over it (or over the placeholder).
     */
    void drawJacket(Batch b) {
        if (!visibleNow() || content.launch) return;
        float a = cardAlpha() * swapAlpha() * rowK(jTop);
        if (a <= 0.01f) return;
        float jy = jacketY(swapDy());
        if (jacket != null) {
            float rv = Anim.POW2_OUT.apply(reveal);
            float lo = jy + jH * (1f - rv);
            if (wash != null && !sheetLayout) glow(b, jy, a * rv);
            if (jacketGlitch > 0f && !r.reduceMotion) {
                float g = 6f * k;
                jacketQuads(b, jx - g, jy, lo,
                    Color.toFloatBits(Theme.GLITCH_C.r, Theme.GLITCH_C.g, Theme.GLITCH_C.b, 0.45f * a));
                jacketQuads(b, jx + g, jy, lo,
                    Color.toFloatBits(Theme.GLITCH_M.r, Theme.GLITCH_M.g, Theme.GLITCH_M.b, 0.45f * a));
            }
            jacketQuads(b, jx, jy, lo, Color.toFloatBits(1f, 1f, 1f, a));
        }
        if (!sheetLayout) draw.roundRectStroke(jx, jy, jW, jH, jacketR(), 1.2f, JACKET_STROKE, a);
    }

    /**
     * The jacket's own colours glowing round it: its wash (same crop) stretched over {@link #GLOW_W} ×
     * {@link #GLOW_H} of the jacket, centred, the alpha rising from 0 at the edges to {@link #GLOW_A} over
     * {@link #GLOW_FX} / {@link #GLOW_FY} of its size. A 3 × 3 grid of vertex-coloured quads less the middle one,
     * which the jacket covers.
     */
    private void glow(Batch b, float jy, float a) {
        float gw = jW * GLOW_W, gh = jH * GLOW_H;
        gx[0] = jx + (jW - gw) * 0.5f;
        gx[1] = gx[0] + gw * GLOW_FX;
        gx[3] = gx[0] + gw;
        gx[2] = gx[3] - gw * GLOW_FX;
        gy[0] = jy + (jH - gh) * 0.5f;
        gy[1] = gy[0] + gh * GLOW_FY;
        gy[3] = gy[0] + gh;
        gy[2] = gy[3] - gh * GLOW_FY;
        float peak = GLOW_A * a;
        int n = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                if (col == 1 && row == 1) continue;
                n = glowVert(n, col, row, peak);
                n = glowVert(n, col, row + 1, peak);
                n = glowVert(n, col + 1, row + 1, peak);
                n = glowVert(n, col + 1, row, peak);
            }
        }
        b.draw(wash, jv, 0, n);
    }

    /** Glow grid vertex: alpha 0 on the outer lines, {@code peak} on the inner ones; the crop stretched over it. */
    private int glowVert(int n, int col, int row, float peak) {
        float px = gx[col], py = gy[row];
        float alpha = col == 0 || col == 3 || row == 0 || row == 3 ? 0f : peak;
        float u = cu0 + (px - gx[0]) / (gx[3] - gx[0]) * (cu1 - cu0);
        float v = cv1 - (py - gy[0]) / (gy[3] - gy[0]) * (cv1 - cv0);
        put(jv, n, px, py, Color.toFloatBits(1f, 1f, 1f, alpha), u, v);
        return n + 5;
    }

    /**
     * Draws the jacket's crop into the jacket box as horizontal slabs whose corner insets follow a circle of the
     * box's radius (rounded corners without a mask or scissor), clipped below {@code clipLo}.
     */
    private void jacketQuads(Batch b, float x, float y, float clipLo, float color) {
        float rad = jacketR(), s = jH;
        int n = 0;
        int slabs = 4;
        float top = y + s;
        // bottom corner zone, middle, top corner zone
        for (int i = 0; i < slabs * 2 + 1; i++) {
            float ya, yb;
            if (i < slabs) {
                ya = y + rad * i / slabs;
                yb = y + rad * (i + 1) / slabs;
            } else if (i == slabs) {
                ya = y + rad;
                yb = top - rad;
            } else {
                int j = i - slabs - 1;
                ya = top - rad + rad * j / slabs;
                yb = top - rad + rad * (j + 1) / slabs;
            }
            if (yb <= clipLo) continue;
            if (ya < clipLo) ya = clipLo;
            float ia = inset(ya, y, top, rad), ib = inset(yb, y, top, rad);
            n = putQuad(n, x, y, ya, yb, ia, ib, color);
        }
        if (n > 0) b.draw(jacket, jv, 0, n);
    }

    private static float inset(float yy, float y, float top, float rad) {
        float d = Math.min(yy - y, top - yy);
        if (d >= rad) return 0f;
        float t = rad - d;
        return rad - (float) Math.sqrt(Math.max(0f, rad * rad - t * t));
    }

    private int putQuad(int i, float x, float y, float ya, float yb, float ia, float ib, float color) {
        float[] q = jv;
        float w = jW, h = jH, du = cu1 - cu0, dv = cv1 - cv0;
        float va = cv1 - (ya - y) / h * dv, vb = cv1 - (yb - y) / h * dv;
        put(q, i, x + ia, ya, color, cu0 + ia / w * du, va);
        put(q, i + 5, x + ib, yb, color, cu0 + ib / w * du, vb);
        put(q, i + 10, x + w - ib, yb, color, cu1 - ib / w * du, vb);
        put(q, i + 15, x + w - ia, ya, color, cu1 - ia / w * du, va);
        return i + 20;
    }

    private static void put(float[] q, int i, float x, float y, float c, float u, float v) {
        q[i] = x;
        q[i + 1] = y;
        q[i + 2] = c;
        q[i + 3] = u;
        q[i + 4] = v;
    }

    // =================================================================== drawing: UI raster
    /**
     * UI-raster phase: layer, artist and BPM, the DEPTH / PLAYS labels (side) or record lines (sheet), the first-dive
     * line, micro-goal or AUTOPLAY line.
     */
    void drawUi(Batch b) {
        if (!visibleNow()) return;
        float a = cardAlpha();
        float ca = a * swapAlpha(), dy = swapDy(), ra = ca * recordAlpha();
        for (CardChip c : chips) c.drawUi(b, a);
        String artist = content.launch ? "TUTORIAL · INTRODUCTION" : content.song != null ? content.song.artist() : "";
        float c18 = MapUi.cap(18f, k);
        if (sheetLayout) {
            r.uiFit(b, artist, c20, cx0, row(156f) + dy, Align.left, 900f * k, Theme.INK2, ca);
            float rk = rowK(row(592f));
            float ax = cx0 + jSize + 32f * k, aw = 640f * k;
            if (rk > 0f) r.uiFit(b, layerText, c20, ax, row(592f) + dy, Align.left | Align.top, aw, accent, ca * rk);
            rk = rowK(row(556f));
            if (rk > 0f) r.uiFit(b, metaText, c20, ax, row(556f) + dy, Align.left | Align.top, aw, Theme.INK3, ca * rk);
            if (content.launch) {
                rk = rowK(row(508f));
                if (rk > 0f) drawLaunchLines(b, ax, row(508f) + dy, aw, ca * rk);
            } else {
                if (!rec.played) {
                    r.ui(b, "NOT YET DIVED", c20, cx1, row(190f) + dy, Align.right, Theme.INK2, ra);
                } else {
                    r.ui(b, "BEST", c20, sheetBestLeft - 16f * k, row(190f) + dy, Align.right, Theme.INK2, ra);
                }
                drawRecordLines(b, ax, row(508f) + dy, aw, ra, true);
                rk = rowK(row(520f));
                if (rk > 0f) drawGoalText(b, sheetColB() + goalTotalW(), row(500f) + dy, ra * rk);
            }
            if (auto.isOn() && !content.launch && !content.locked) {
                r.ui(b, "AUTOPLAY · NOT RECORDED", c18, auto.x(), base + (MapStyle.ACTION_H + 18f) * k,
                    Align.left | Fonts.VCENTER, Theme.AUTO, a);
            }
            return;
        }
        // the level's name centred between the arrows
        float lx0 = prev.x() + prev.width() + 16f * k, lx1 = next.x() - 16f * k;
        r.uiFit(b, layerText, MapUi.cap(HEAD_CAP, k), (lx0 + lx1) * 0.5f, headY, Align.center | Fonts.VCENTER,
            lx1 - lx0, accent, a);
        // the artist at the left and the BPM at the right, on one line under the title
        float metaW = content.launch ? 0f : r.uiWidth(metaText, c20);
        float artistW = metaW > 0f ? cw - metaW - 24f * k : cw;
        r.uiFit(b, artist, MapUi.cap(22f, k), cx0, artistBase + dy, Align.left, artistW, Theme.INK2, ca);
        if (metaW > 0f) r.ui(b, metaText, c20, cx1, artistBase + dy, Align.right, Theme.INK3, ca);
        float rowY = goalTop - MapStyle.GOAL_ROW_H * k * 0.5f + dy;
        if (content.launch) {
            r.uiFit(b, "LEARN THE TWO LINES", MapUi.cap(22f, k), cx0, scoreBase + dy, Align.left, cw, Theme.INK2, ca);
            boolean done = content.anyPlayed;
            r.uiFit(b, done ? "COMPLETED · REPLAY ANYTIME" : "START HERE", c20, cx0, depthBase + dy, Align.left, cw,
                done ? Theme.AUTO : Theme.GOLD, ca);
        } else {
            if (rec.played) {
                tracked(b, DEPTH_WORD, rowLabelCap, rowX[0], depthBase + dy, Theme.INK3, ra);
                tracked(b, PLAYS_WORD, rowLabelCap, rowX[2], depthBase + dy, Theme.INK3, ra);
            } else {
                // FIRST DIVE · +40 SHELLS under NOT YET DIVED
                r.uiFit(b, rec.depthText, MapUi.cap(17f, k), cx0, depthBase + dy, Align.left, cw, accent, ra);
            }
            if (!autoRow()) drawGoalText(b, goalRight(), rowY, ra);
        }
        if (autoRow()) r.ui(b, "AUTOPLAY · NOT RECORDED", c18, cx0, rowY, Align.left | Fonts.VCENTER, Theme.AUTO, a);
    }

    private void drawLaunchLines(Batch b, float x, float top, float w, float a) {
        r.uiFit(b, "LEARN THE TWO LINES", c20, x, top, Align.left | Align.top, w, Theme.INK2, a);
        boolean done = content.anyPlayed;
        r.uiFit(b, done ? "COMPLETED · REPLAY ANYTIME" : "START HERE", c20, x, top - 40f * k, Align.left | Align.top, w,
            done ? Theme.AUTO : Theme.GOLD, a);
    }

    /**
     * Two record lines from {@code top} (side: under the grade, beside the jacket; sheet: the
     * record column): {@code ACC 99.12% · DEPTH 640 / 1,000 m} (Kelvin 2026-10-17: the chart depth as
     * current / max after the accuracy, no top-10 position), then {@code PLAYS 12} with the local
     * rank. Unplayed charts show only the FIRST DIVE line.
     */
    private void drawRecordLines(Batch b, float x, float top, float w, float a, boolean rows) {
        if (a <= 0.01f) return;
        if (!rec.played) {
            float y = rows ? top : top + 100f * k;
            float rk = rows ? rowK(y) : 1f;
            if (rk > 0f) r.uiFit(b, rec.depthText, c20, x, y, Align.left | Align.top, w, accent, a * rk);
            return;
        }
        float pitch = recordPitch();
        float yy = top;
        float rk = rows ? rowK(yy) : 1f;
        if (rk > 0f) {
            // label grey, values white; shrunk as one line to the column
            float full = r.uiWidth(ACC_LABEL, c20) + r.uiWidth(rec.accText, c20) + r.uiWidth(DEPTH_LABEL, c20)
                + r.uiWidth(rec.depthText, c20);
            float cap = full > w ? c20 * w / full : c20, xx = x;
            xx += r.ui(b, ACC_LABEL, cap, xx, yy, Align.left | Align.top, Theme.INK2, a * rk);
            xx += r.ui(b, rec.accText, cap, xx, yy, Align.left | Align.top, Theme.INK1, a * rk);
            xx += r.ui(b, DEPTH_LABEL, cap, xx, yy, Align.left | Align.top, Theme.INK2, a * rk);
            r.ui(b, rec.depthText, cap, xx, yy, Align.left | Align.top, Theme.INK1, a * rk);
        }
        yy -= pitch;
        rk = rows ? rowK(yy) : 1f;
        if (rk > 0f) r.uiFit(b, rec.playsText, c20, x, yy, Align.left | Align.top, w, Theme.INK2, a * rk);
    }

    /** Line pitch of the record lines. */
    private float recordPitch() {
        return Math.max(36f * k, c20 * 1.6f);
    }

    private void drawGoalText(Batch b, float right, float cy, float a) {
        if (rec.goal == CardRecord.GOAL_NONE || a <= 0.01f) return;
        if (rec.goal == CardRecord.GOAL_MAXED) {
            r.ui(b, rec.goalPrefix, c20, right, cy, Align.right | Fonts.VCENTER, accent, a);
            return;
        }
        r.ui(b, rec.goalRest, c20, right, cy, Align.right | Fonts.VCENTER, accent, a);
        r.ui(b, rec.goalPrefix, c20, right - goalRestU * c20 - goalGap(), cy, Align.right | Fonts.VCENTER, accent, a);
    }

    // =================================================================== drawing: NUMBER raster
    /** NUMBER-raster phase: the chip constants and the sheet's best score (fixed cells). */
    void drawNumbers(Batch b) {
        if (!visibleNow()) return;
        float a = cardAlpha();
        for (CardChip c : chips) c.drawNumbers(b, a);
        // the side card's score is drawn in the DISPLAY phase (its own DIGITS raster)
        if (!sheetLayout || content.launch || !rec.played) return;
        float ra = a * swapAlpha() * recordAlpha(), dy = swapDy();
        r.fixed(b, rec.bestText, MapUi.cap(28f, k), cx1 - 96f * k, row(190f) + dy, Align.right, Theme.INK1,
            Theme.LEADING_ZERO, rec.bestLead, ra, 1f);
    }

    // =================================================================== drawing: UI_BOLD raster
    /** UI_BOLD phase: title, chip names, AUTO label, the DEPTH / PLAYS values and NOT YET DIVED. */
    void drawBold(Batch b) {
        if (!visibleNow()) return;
        float a = cardAlpha();
        float ca = a * swapAlpha(), dy = swapDy(), ra = ca * recordAlpha();
        String title = content.launch ? "LAUNCH POINT" : content.song != null ? content.song.title() : "";
        for (CardChip c : chips) c.drawBold(b, a);
        auto.drawBold(b, a);
        descend.drawLock(b, a);
        if (sheetLayout) {
            float right = rec.played && !content.launch ? sheetBestLeft - 16f * k - r.uiWidth("BEST", c20) - 32f * k
                : content.launch ? cx1 : cx1 - r.uiWidth("NOT YET DIVED", c20) - 32f * k;
            r.boldFit(b, title, MapUi.cap(34f, k), cx0, row(190f) + dy, Align.left, Math.min(900f * k, right - cx0),
                0.7f, Theme.INK1, ca);
            return;
        }
        drawTitle(b, title, titleBase + dy, ca);
        if (content.launch) return;
        if (!rec.played) {
            r.bold(b, "NOT YET DIVED", MapUi.cap(26f, k), cx0, scoreBase + dy, Align.left, Theme.INK2, ra);
            return;
        }
        r.bold(b, rec.depthText, rowValueCap, rowX[1], depthBase + dy, Align.left, Theme.INK2, ra);
        r.bold(b, rec.playsValue, rowValueCap, rowX[3], depthBase + dy, Align.left, Theme.INK2, ra);
    }

    /**
     * The side card's title on its baseline: still (34 shrinking to 29), or scrolling at 29 between the content
     * edges with {@link Marquee} timing (drawn again one span on, its ends faded).
     */
    private void drawTitle(Batch b, String title, float y, float a) {
        if (!titleScroll) {
            r.bold(b, title, titleCap, cx0, y, Align.left, Theme.INK1, a);
            return;
        }
        float span = titleW + Marquee.GAP * k;
        float d = Marquee.distance(titleT, titleW - cw, span, k);
        float fl = Marquee.fadeLeft(d, span, k), fr = Marquee.fadeRight(d, titleW - cw, span, k);
        for (float x = cx0 - d % span; x < cx1; x += span) {
            r.boldFaded(b, title, titleCap, x, y, Align.left, Theme.INK1, a, cx0, cx1, fl, fr);
        }
    }

    // =================================================================== drawing: DISPLAY raster
    /**
     * DISPLAY phase: the DESCEND label, the grade letter ("+" as a superscript, R and R+ sweeping) and the side
     * card's score.
     */
    void drawDisplay(Batch b) {
        if (!visibleNow()) return;
        float a = cardAlpha();
        descend.drawLabel(b, a);
        if (content.launch || !rec.played) return;
        float ra = a * swapAlpha() * recordAlpha(), dy = swapDy();
        rec.gradeColor(c1);
        if (sheetLayout) {
            r.grade(b, Fonts.Role.DISPLAY, MapRenderer.R_DISPLAY, rec.grade, 28f * k, 60f * k, cx1 - 40f * k,
                row(208f) + dy, c1, ra);
            return;
        }
        // the letter as big as the ring allows (Kelvin 07:00 「图标放圆圈这么大」), then the score with dim leading zeros;
        // both from small rasters at their own size, which share a page
        if (rec.grade >= 0) {
            r.grade(b, Fonts.Role.DISPLAY, MapRenderer.R_DISPLAY, Fonts.GlyphSet.GRADE, rec.grade, 78f * k,
                2f * ringR - 28f * k, ringX, ringY + dy, c1, ra);
        }
        r.boldScore(b, rec.bestGhost, rec.bestRest, MapUi.cap(42f, k), cx0, scoreBase + dy, Theme.INK1,
            Theme.LEADING_ZERO, ra);
    }

    /** Current accent (difficulty accent, animated). */
    Color accent() {
        return accent;
    }

    /** The card scale of this frame (for the overlays and tests). */
    float k() {
        return k;
    }
}
