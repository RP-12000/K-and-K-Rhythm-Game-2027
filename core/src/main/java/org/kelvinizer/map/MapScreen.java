package org.kelvinizer.map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import org.kelvinizer.Main;
import org.kelvinizer.audio.AudioService;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.screens.CalibrationScreen;
import org.kelvinizer.screens.FirstRunScreen;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.KkScreen;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.SonarField;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选歌：水下洞穴地图。主界面（洞口）有单曲、主线、联动三个入口，右下角是所有曲目；每个入口是一层可以四处拖动的地图，
 * 歌曲是洞、剧情是书本入口、章节之间是竖井；每层的布局都来自 assets/maps 下的数据文件。主线有故事模式（点 MAIN 直接进第一章）
 * 和章节模式（所有章节入口在一层），章节全部解锁后主界面才出现切换按钮。
 * 这里处理换层的转场、选歌和试听、上锁、剧情卡、排序筛选、按 DESCEND 下潜进游戏，以及从游戏返回时的通关和解锁动画。
 *
 * <p>Song select: the underwater cave map (DESIGN §16, preview v17). The hub (SINGLES, MAIN and
 * COLLAB, ALL SONGS behind the HUD's corner button; no zoom there) and every route level are
 * freely draggable 2D maps built from the data files under {@code assets/maps} ({@link CaveData},
 * {@link MapWorld}); song holes open the {@link SongCard}, story entrances open the
 * {@link StoryCard}, shafts fall to the next chapter.
 *
 * <p><b>Main-line modes</b> (Kelvin 2026-10-16): in story mode MAIN leads straight into chapter 1
 * (a spot's {@code storyTo}) and each chapter rises to the one before it ({@code storyParent});
 * in chapter mode MAIN leads to the layer with every chapter entrance and every chapter rises
 * there. The toggle shows on the hub once that layer's chapters and side stories are all open.
 *
 * <p><b>Levels.</b> A route hole whirls into its level (X1), a shaft falls into the next chapter
 * (X3), and two pinches at the overview within a second (or BACK / Esc) rise one level (X4;
 * {@link CaveFx}). There is no rise button, and nothing rises from the hub (Kelvin 2026-10-16) or
 * while a song card is open (2026-10-17). Arriving lights the level
 * up from the arrival point. Locked holes shake and show what to clear first; sealed ones say
 * they are coming. A song without a chart for the lens difficulty only shakes (Kelvin 2026-10-16).
 *
 * <p><b>Input priority:</b> story card → sort panel → card → HUD → map; each pointer is
 * owned by the layer that claimed its touchDown. On the map a hole takes the press state at once, but the
 * action waits for the tap / pan decision ({@link MapGestures}).
 *
 * <p><b>The Descent</b> (§3.12, X2c): DESCEND closes the card, slides the HUD off and zooms the
 * camera into the hole until its art is a disc of {@link TransitionLayer#ART_R}; the DIVE then
 * closes its walls behind the art and title chip. On the IRIS return played arcs sweep in, a first
 * clear or new best gets its floater, and newly unlocked holes get the X5 gold flow and shatter.
 *
 * <p><b>Persistence:</b> level ({@code mapLevel}), camera, lens and selection are saved to the
 * profile on hide and on {@link #pause()}. Allocation-free per frame after a level is built.
 */
public class MapScreen extends KkScreen<Main>
    implements MapGestures.Listener, MapHud.Actions, SongCard.Actions, SortPanel.Listener {

    private static final int OWN_NONE = 0, OWN_CARD = 1, OWN_HUD = 2, OWN_MAP = 3, OWN_STORY = 4, OWN_SWALLOW = 6,
        OWN_SORT = 7;
    /** Peek ring: visible after this hold time, full at {@link MapStyle#PEEK_T}. */
    private static final float PEEK_DELAY = 0.1f;
    /** Height the camera glides down from on the Welcome arrival. */
    private static final float WELCOME_RISE = 900f;
    /** Height of the plain arrival (shown with no transition running). */
    private static final float ARRIVAL_RISE = 600f;
    static final String HUB = "hub", ALL = "all";

    private final CaveData data;
    private MapWorld world;
    private final MapCamera cam;
    private final OrthographicCamera mapCamera = new OrthographicCamera();
    private final MapGestures gestures;
    private final ThumbAtlas thumbs;
    private final SonarField sonar;
    private final MapBubbles bubbles;
    private final MapRenderer renderer;
    private final MapHud hud;
    private final SongCard card;
    private final MapFilter filter;
    /** ALL SONGS order and filter, opened by the HUD's SORT button. */
    private final SortPanel sortPanel;
    /** ALL SONGS: the grid's layout, mask and scroll. */
    private final GridPage grid;
    private final PeekTip peek;
    private final StoryCard story;
    private final CaveFx fx;

    private final int[] owner = new int[20];
    private float time;
    private Difficulty lens = Difficulty.SURFACE;
    private MapNode selected;
    private MapNode pressedNode;
    private boolean longWater;
    private MapNode resumeCard, storyNode;
    /** MAIN's chapter mode can be chosen (its chapters and side stories are all open). */
    private boolean modeAvailable;
    /** Per level id: the spot last taken down from it (a rise lands there). */
    private final Map<String, String> downVia = new HashMap<>();
    private int gridSort;
    /** ALL SONGS: the page's alpha. */
    private float gridA = 1f;

    private Profile profile;
    private int profileRev = Integer.MIN_VALUE;
    private boolean reduceMotion, zh;

    private float dwell = -1f;
    private boolean arriving, fromWelcome, shownOnce;
    private float mouseX = Ui.CX, mouseY = Ui.CY;

    private float peekT = -1f;
    private boolean peekShown;

    // the Descent and the return (§3.12, X2c, X5) -------------------------------------------------
    private MapNode diveNode;
    private Difficulty diveDiff = Difficulty.SURFACE;
    private boolean diveAuto, diveZooming, diveStarted;
    private float diveT = -1f, diveFx, diveFy, diveDepth0, diveDepth1;
    /** Camera before the zoom (what is saved and restored). */
    private float preX, preY, preZ;
    private float exitK;
    private boolean dived;
    private MapNode returnNode;
    private Difficulty returnDiff;
    private boolean returnHad, returnCleared;
    private int returnBest;
    /** Lock state of every spot when the Descent started (X5 finds the newly unlocked ones). */
    private boolean[] lockedBefore = new boolean[0];
    private MapWorld lockedWorld;

    /**
     * Test hook: when &gt; 0 every frame advances by this many seconds instead of the real delta
     * (deterministic screenshots of the animations).
     */
    float fixedDelta;

    /** Loads the map data, builds the hub and starts the thumbnail worker. */
    public MapScreen(final Main game) {
        super(game);
        data = CaveData.load();
        List<SongMeta> lib = game.library().songs();
        IdentityHashMap<SongMeta, Integer> slots = new IdentityHashMap<>();
        List<SongMeta> thumbSongs = new ArrayList<>(lib);
        for (int i = 0; i < lib.size(); i++) slots.put(lib.get(i), i);
        SongMeta tut = game.library().tutorial();
        if (tut != null) {
            slots.put(tut, thumbSongs.size());
            thumbSongs.add(tut);
        }
        thumbs = new ThumbAtlas(thumbSongs);
        thumbs.start();
        cam = new MapCamera();
        gestures = new MapGestures(cam, this);
        sonar = new SonarField(game.draw());
        bubbles = new MapBubbles();
        renderer = new MapRenderer(game.draw(), game.fonts(), game.glitch(), thumbs, slots, lib.size(), sonar, bubbles, data);
        renderer.cam = cam;
        hud = new MapHud(game.draw(), game.fonts(), game.avatars(), cam, renderer, this);
        grid = new GridPage(game.draw(), renderer, cam);
        renderer.grid = grid;
        filter = new MapFilter();
        world = build(HUB);
        card = new SongCard(game.draw(), game.fonts(), world, thumbs, renderer, game.library(), game.profiles(), this);
        sortPanel = new SortPanel(game.draw(), renderer, filter, this);
        peek = new PeekTip(game.draw(), renderer);
        story = new StoryCard(game.draw(), game.fonts(), renderer, new Runnable() {
            @Override
            public void run() {
                storyClosed();
            }
        });
        fx = new CaveFx(game.draw(), renderer);
        game.fonts().prewarm(Fonts.Role.UI_BOLD, MapRenderer.R_BOLD, Fonts.GlyphSet.FULL);
        game.fonts().prewarm(Fonts.Role.DISPLAY, MapRenderer.R_DISPLAY, Fonts.GlyphSet.FULL);
        game.fonts().prewarm(Fonts.Role.DISPLAY_SOFT, MapRenderer.R_DISPLAY, Fonts.GlyphSet.FULL);
        game.fonts().prewarm(Fonts.Role.NUMBER, MapRenderer.R_DISPLAY, Fonts.GlyphSet.FULL);
        cam.setViewport(Ui.worldW(), Ui.worldH());
        applyLevel(world);
        startCamera(world.start);
    }

    // =================================================================== levels
    /** Builds level {@code id} (the hub, then a plain ALL SONGS grid when the data is missing). */
    private MapWorld build(String id) {
        CaveData.Level l = data.levels.get(id);
        if (l == null) l = data.levels.get(HUB);
        if (l == null) {
            l = new CaveData.Level();
            l.id = ALL;
            l.kind = CaveData.GRID;
            l.title = "ALL SONGS";
            l.titleZh = "所有曲目";
        }
        return new MapWorld(data, l, game.library(), profile, gridSort, lens, filter);
    }

    /** Makes {@code w} the current level (renderer, card, HUD, camera bounds). */
    private void applyLevel(MapWorld w) {
        world = w;
        renderer.zh = zh;
        renderer.setWorld(w);
        card.world = w;
        boolean g = w.isGrid();
        if (g) grid.setWorld(w);
        cam.lockX = g;
        card.pinned = g;
        applyLevelBounds();
        hud.setLevel(w);
        selected = null;
        pressedNode = null;
    }

    private static int layer(CaveData.Level l) {
        try {
            return Integer.parseInt(l.numeral.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Snaps the camera onto {@code at} (or the level centre) at the closest zoom (the hub, which does not zoom, at 1);
     * ALL SONGS opens at its first row.
     */
    private void startCamera(MapNode at) {
        if (world.isGrid()) {
            grid.scrollTo(0f);
            return;
        }
        float x = at != null ? at.x : 0.5f * (world.minX + world.maxX);
        float y = at != null ? at.y : 0.5f * (world.minY + world.maxY);
        if (at != null && at == world.start && !Float.isNaN(world.startX)) { // where the dropped rise spot was
            x = world.startX;
            y = world.startY;
        }
        if (world.isHub() && !Float.isNaN(world.level.titleX)) y = 0.5f * (y + world.level.titleY) - 60f;
        float z = world.isHub() ? 1f : MapStyle.ZOOM_MIN;
        cam.snap(MapCamera.centreFor(x, Ui.CX, z), MapCamera.centreForY(y, Ui.CY, z), z);
    }

    /** Lights the level up from ({@code ox}, {@code oy}) (the wake light spreads along the paths). */
    private void wake(float ox, float oy) {
        for (MapNode n : world.all) {
            float dx = n.x - ox, dy = n.y - oy;
            n.wake = reduceMotion ? 1f : 0f;
            n.wakeDelay = (float) Math.sqrt(dx * dx + dy * dy) / 1800f;
        }
    }

    /**
     * Leaves for level {@code id} with transition {@code how} from {@code from} (null: the screen
     * centre); a spot with {@code at} starts the camera on that spot of the new level.
     */
    private void goTo(final String id, final int how, MapNode from) {
        if (fx.busy() || diving() || !data.levels.containsKey(id)) return;
        // rising from ALL SONGS its card stays where it is and leaves with the page at the swap (Kelvin 2026-10-20
        // 18:45 「全部歌曲上浮的过程中侧板会收起来。我要上浮的时候侧板也保持不动。」)
        if (how == CaveFx.RISE && world.isGrid() && card.isOpen()) {
            dwell = -1f;
            game.audio().duckMenu(1f, 0.8f);
        } else {
            closeCard();
        }
        story.close();
        sortPanel.close();
        peekCancel();
        gestures.cancel();
        hud.cancel();
        final String prev = world.level.id;
        final String atSpot = from != null && from.spot != null && how != CaveFx.RISE ? from.spot.at : null;
        if (from != null && from.spot != null && how != CaveFx.RISE) downVia.put(prev, from.spot.id);
        float hx = from != null ? cam.hudX(from.drawX()) : Ui.CX, hy = from != null ? cam.hudY(from.drawY(time, reduceMotion)) : Ui.CY;
        if (from != null && how != CaveFx.RISE && !reduceMotion) {
            float z = 0.45f;
            cam.diveTo(MapCamera.centreFor(from.x, Ui.CX, z), MapCamera.centreForY(from.y, Ui.CY, z), z, CaveFx.outDuration(how),
                Interpolation.exp5In);
        }
        CaveData.Level next = data.levels.get(id);
        // an English screen shows no Chinese (Kelvin 2026-10-20: 如果语言选择是英语就不要出现中文)
        String name = (next.numeral.isEmpty() ? "" : next.numeral + " ") + next.title + (zh ? "  " + next.titleZh : "");
        String line = (zh ? "第 " + layer(next) + " 层 · " : "") + "LEVEL " + layer(next);
        Haptics.medium();
        game.audio().stopPreview(0.3f);
        fx.start(how, hx, hy, name, line, new Runnable() {
            @Override
            public void run() {
                enter(id, prev, how, atSpot);
            }
        });
    }

    private void enter(String id, String prev, int how, String atSpot) {
        if (card.isOpen()) { // kept through a rise from ALL SONGS (see goTo): gone with the old page
            card.snapClosed();
            card.releaseJacket();
        }
        applyLevel(build(id));
        MapNode at = world.start;
        if (how == CaveFx.RISE) { // land on the spot taken down, else one leading to where we rose from
            MapNode via = world.spotNode(downVia.get(id));
            if (via != null && prev.equals(target(via.spot))) {
                at = via;
            } else {
                MapNode any = null;
                for (MapNode n : world.all) {
                    if (n.spot == null || !prev.equals(target(n.spot))) continue;
                    if (any == null || (any.spot.at != null && n.spot.at == null)) any = n;
                }
                if (any != null) at = any;
            }
        } else if (atSpot != null && world.spotNode(atSpot) != null) {
            at = world.spotNode(atSpot);
        }
        startCamera(at);
        // the arrival lights up from at, or from the dropped rise spot the level starts on when the
        // camera opens nearer to it: SINGLES opens centred on its start (Kelvin 2026-10-17 20:20: the
        // ring flashed on the top-left song, the hole nearest the start); the chapters start on their
        // title by the left edge, which the bounds hold off the middle, so they keep their first hole
        float lx = at != null ? at.x : cam.x, ly = at != null ? at.y : cam.y;
        if (at != null && at == world.start && !Float.isNaN(world.startX)
            && Vector2.dst2(world.startX, world.startY, cam.x, cam.y) < Vector2.dst2(lx, ly, cam.x, cam.y)) {
            lx = world.startX;
            ly = world.startY;
        }
        if (!reduceMotion && how != CaveFx.RISE && !world.isGrid()) { // the grid does not zoom
            cam.glideFrom(cam.x, cam.y, cam.zoom * 0.7f, 0.7f, Interpolation.exp5Out);
        }
        wake(lx, ly);
        if (at != null) {
            fx.arrive(cam.hudX(lx), cam.hudY(ly));
            sonar.ping(lx, ly, 900f, 1.2f, Theme.GROUND_SIG, 0.3f, 2);
        }
        game.backdrop().setDepth(world.level.depth);
        if (world.isGrid()) openGridCard(profile != null ? world.node(profile.lastSongId) : null);
        saveState();
    }

    /**
     * Two fingers held still ({@link MapStyle#RISE_HOLD} s, Kelvin 2026-10-19 18:46), BACK, Esc or
     * PAGE UP: up one level. Nothing rises from the hub (Kelvin 2026-10-16: no way back to the title
     * once on the routes) or while a song card is open (2026-10-17 19:42: 「这个界面下就不允许上浮」).
     */
    public void rise() {
        String parent = riseTarget();
        if (parent != null) goTo(parent, CaveFx.RISE, null);
    }

    /** The level a rise goes up to now, or null when nothing rises (see {@link #rise()}). */
    private String riseTarget() {
        if (fx.busy() || diving() || world.isHub() || (card.isOpen() && !world.isGrid())) return null;
        String parent = !chapterMode() && world.level.storyParent != null ? world.level.storyParent : world.level.parent;
        return parent != null && data.levels.containsKey(parent) ? parent : null;
    }

    /** MAIN in chapter mode: chosen by the diver and offered (every chapter open). */
    private boolean chapterMode() {
        return modeAvailable && profile != null && profile.mainChapterMode;
    }

    /** Where spot {@code s} leads in the current mode ({@code storyTo} in story mode, when set). */
    private String target(CaveData.Spot s) {
        return s.storyTo != null && !chapterMode() ? s.storyTo : s.to;
    }

    /**
     * True when every chapter and side-story shaft on MAIN's chapter layer (the level the hub's
     * MAIN leads to in chapter mode) is open; sealed placeholders do not count.
     */
    private boolean computeModeAvailable() {
        CaveData.Level hub = data.levels.get(HUB);
        if (hub == null) return false;
        for (CaveData.Spot s : hub.spots) {
            if (s.storyTo == null || s.to == null) continue;
            CaveData.Level layer = data.levels.get(s.to);
            if (layer == null) return false;
            boolean any = false;
            for (CaveData.Spot c : layer.spots) {
                if (MapWorld.kindOf(c.type) != MapNode.SHAFT || c.sealed || c.to == null) continue;
                any = true;
                if (!MapWorld.unmet(layer, c, profile, game.library()).isEmpty()) return false;
            }
            return any;
        }
        return false;
    }

    @Override
    public void gridSort(int sort) {
        if (sort == gridSort) return;
        gridSort = sort;
        relayoutGrid();
        Haptics.light();
    }

    /**
     * Lists ALL SONGS again (its packs, order, filter or the lens behind them changed): the grid starts at its first
     * row, the card keeps its song while the grid still has it, and every title's scroll starts over.
     */
    private void relayoutGrid() {
        MapNode keep = selected;
        applyLevel(build(world.level.id));
        grid.scrollTo(0f);
        openGridCard(keep != null && keep.song != null ? world.node(keep.song.id()) : null);
        renderer.resetTitles();
    }

    /**
     * ALL SONGS keeps its card open (Kelvin 2026-10-19 13:14): on {@code n} when it opens, else on the first song that
     * does, its row scrolled into view; closed only while no song is left (a filter with no match).
     */
    private void openGridCard(MapNode n) {
        if (n == null || !opens(n)) {
            n = null;
            for (MapNode m : world.nodes) {
                if (opens(m)) {
                    n = m;
                    break;
                }
            }
        }
        if (n == null) {
            closeCard();
            return;
        }
        if (n != selected || !card.isOpen()) selectNode(n, false);
        grid.reveal(n, false);
    }

    // =================================================================== public API
    /**
     * Selects {@code song} as if its hole were tapped (on the current level) and optionally opens
     * its card. Unknown songs are ignored. Intended for deep links and tests.
     */
    public void select(SongMeta song, boolean openCard) {
        if (song == null || diving()) return;
        MapNode n = world.node(song.id());
        if (n == null && !fx.busy()) { // not on this level: jump to ALL SONGS, which has every song
            applyLevel(build(ALL));
            startCamera(world.start);
            n = world.node(song.id());
        }
        if (n == null || (n.locked && !world.isGrid()) || n.glitch || n.missing(lens)) return;
        selectNode(n, true);
        if (!openCard) card.close();
    }

    /** The selected song, or null. */
    public SongMeta selectedSong() {
        return selected != null ? selected.song : null;
    }

    /** The lens difficulty (map node rims, arcs and chips). */
    public Difficulty lens() {
        return lens;
    }

    /** True while the Descent (DESCEND → DIVE) runs on this screen. */
    public boolean isDescending() {
        return diving();
    }

    /**
     * Writes the current screen-viewport (HUD) position of a song's hole on this level into {@code out}.
     *
     * @return false when the song has no hole here
     */
    public boolean songHudPosition(String songId, Vector2 out) {
        MapNode n = world.node(songId);
        if (n == null) return false;
        out.set(cam.hudX(n.drawX()), cam.hudY(n.drawY(time, reduceMotion)));
        return true;
    }

    /** Arrives on the hub from Welcome (camera settles from above) on the next show. */
    public void arriveFromWelcome() {
        fromWelcome = true;
    }

    /** Ocean depth (m) of the hub, where the Welcome descent lands (Welcome ramps the backdrop toward it). */
    public float arrivalDepth() {
        CaveData.Level h = data.levels.get(HUB);
        return h != null ? h.depth : world.level.depth;
    }

    // =================================================================== lifecycle
    @Override
    protected void onShow() {
        Backdrop b = game.backdrop();
        b.setMode(Backdrop.Mode.MENU);
        b.setCaustics(true);
        cam.setViewport(Ui.worldW(), Ui.worldH());
        for (int i = 0; i < owner.length; i++) owner[i] = OWN_NONE;
        pressedNode = null;
        longWater = false;
        peekCancel();
        refreshProfile(true);
        boolean welcome = fromWelcome;
        fromWelcome = false;
        if (welcome || !shownOnce) {
            String id = !welcome && profile != null && profile.mapLevel != null && data.levels.containsKey(profile.mapLevel)
                ? profile.mapLevel : HUB;
            if (!id.equals(world.level.id)) applyLevel(build(id));
            refreshProfile(true);
        }
        shownOnce = true;
        restoreCamera(welcome);
        game.audio().playMenu();
        card.revalidate();

        TransitionLayer t = game.transitions();
        boolean busy = t != null && t.isBusy();
        boolean iris = busy && t.style() == TransitionLayer.Style.IRIS;
        boolean failedDive = dived && !iris && (!busy || t.style() == TransitionLayer.Style.DIVE);
        MapNode back = returnNode;
        boolean returning = dived && iris && back != null && back.idx < world.all.length && world.all[back.idx] == back;
        dived = false;
        returnNode = null;
        if (failedDive && busy) b.setDepthImmediate(world.level.depth);
        else b.setDepth(world.level.depth);

        MapNode resume = resumeCard;
        resumeCard = null;
        if (resume != null && card.node() == null && resume.idx < world.all.length && world.all[resume.idx] == resume) {
            selectNode(resume, false);
            focusNow(resume); // back from a song: centred beside the card at once (Kelvin 2026-10-13)
        }
        if (world.isGrid() && !card.isOpen()) openGridCard(profile != null ? world.node(profile.lastSongId) : null);

        arriving = false;
        exitK = 0f;
        if (failedDive) {
            exitK = 1f;
        } else if (welcome || !busy) {
            if (welcome && world.start != null) wake(world.start.x, world.start.y);
            if (!reduceMotion && !world.isGrid()) {
                cam.glideFrom(cam.x, cam.y + (welcome ? WELCOME_RISE : ARRIVAL_RISE), cam.zoom, welcome ? 1f : 0.8f,
                    Interpolation.exp5Out);
                arriving = true;
            }
        } else if (iris) {
            exitK = 1f;
            if (returning && !reduceMotion && !world.isGrid()) {
                float z = MapStyle.RETURN_ZOOM;
                cam.glideFrom(MapCamera.centreFor(back.x, focusX(), z), MapCamera.centreForY(back.y, focusY(), z), z,
                    MapStyle.RETURN_GLIDE, Interpolation.exp5Out);
                arriving = true;
            }
            if (returning) returnFx(back);
        }
        calibrationReminder();
    }

    /** The return (§3.12, X5): arcs sweep in, a ripple, the first clear or new best, then the unlocks. */
    private void returnFx(MapNode n) {
        Difficulty d = returnDiff;
        if (d == null || n.launch) return;
        int o = d.ordinal();
        if (n.played[o] && !returnHad) {
            n.sweep[o] = 0f;
            n.sweepDelay = 0.3f;
        }
        bubbles.burst(n.x, n.y, 14, 30f);
        sonar.ping(n.x, n.y, 700f, 1f, Theme.GROUND_SIG, 0.35f, 2);
        int g = n.grade[o];
        if (!returnCleared && n.anyPlayed) {
            renderer.floaterNode = n;
            renderer.floaterT = -0.35f;
            renderer.floaterText = (zh ? "首次通关 FIRST CLEAR" : "FIRST CLEAR")
                + (g >= 0 && g < Theme.GRADES.length ? " · " + Theme.GRADES[g] : "");
        } else if (n.played[o] && n.best[o] > returnBest) {
            renderer.floaterNode = n;
            renderer.floaterT = -0.35f;
            renderer.floaterText = "NEW BEST";
        }
        if (lockedWorld != world || lockedBefore.length != world.all.length) return;
        List<MapNode> un = new ArrayList<>();
        for (MapNode m : world.all) {
            if (lockedBefore[m.idx] && !m.locked) {
                m.unlocking = true;
                un.add(m);
            }
        }
        if (!un.isEmpty()) {
            renderer.flowNodes = un.toArray(new MapNode[0]);
            renderer.flowT = -1.2f;
        }
    }

    /** X5: the gold runs from the cleared songs to the unlocked holes, then the locks shatter. */
    private void updateFlow(float dt) {
        MapNode[] f = renderer.flowNodes;
        if (f.length == 0) return;
        renderer.flowT += dt / 1.1f;
        if (renderer.flowT < 1f) return;
        for (MapNode m : f) {
            m.unlocking = false;
            m.unlockFx = 1f;
        }
        renderer.flowNodes = new MapNode[0];
        renderer.flowT = -1f;
        renderer.refreshLabels();
        game.toasts().show((zh ? "新解锁 " + f.length + " 首 · " : "") + f.length + " UNLOCKED", Theme.GOLD);
        Haptics.medium();
        MapNode first = f[0];
        flyTo(MapCamera.centreFor(first.x, Ui.CX, cam.zoom), MapCamera.centreForY(first.y, Ui.CY, cam.zoom), cam.zoom);
    }

    /** DESIGN §10.1: calibration skipped in FirstRun → one reminder toast after the 2nd play. */
    private void calibrationReminder() {
        Profile p = profile;
        if (p != null && p.playCount >= 2 && !p.calibrated && p.hasFlag(FirstRunScreen.FLAG_CALIBRATION_REMINDER)
            && p.setFlag(CalibrationScreen.FLAG_REMINDER_SHOWN)) {
            game.profiles().markDirty();
            game.toasts().setAnchorTop(hud.globalToastTop());
            game.toasts().show("TUNE YOUR SONAR IN SETTINGS · CHART OFFSET", Theme.GROUND_SIG);
        }
    }

    @Override
    protected void onHide() {
        boolean wasDiving = diving();
        game.toasts().resetAnchor();
        gestures.cancel();
        hud.cancel();
        card.cancel();
        sortPanel.cancel();
        story.close();
        peekCancel();
        bubbles.stopStream();
        saveState();
        if (wasDiving) {
            dived = diveStarted;
            returnNode = diveNode;
            returnDiff = diveDiff;
            resumeCard = diveNode;
        } else {
            resumeCard = card.isOpen() ? selected : null;
        }
        diveT = -1f;
        diveNode = null;
        diveZooming = false;
        diveStarted = false;
        renderer.textFade = 1f;
        card.snapClosed();
        sortPanel.close();
        selected = null;
        dwell = -1f;
        game.audio().stopPreview(0.3f);
        if (!wasDiving) game.audio().duckMenu(1f, 0.6f);
        game.backdrop().setCameraMotion(0f, 0f, cam.renderX(), cam.renderY());
    }

    /** App backgrounded (Android may kill the process): saves now instead of on the next hide. */
    @Override
    public void pause() {
        saveState();
        game.profiles().saveNow();
    }

    @Override
    protected void onResize(int width, int height) {
        cam.setViewport(Ui.worldW(), Ui.worldH());
        applyLevelBounds();
    }

    private void applyLevelBounds() {
        MapWorld w = world;
        if (w.isGrid()) { // the page sets the camera's bounds (x locked, zoom 1) as it places the songs
            grid.layout();
            grid.place();
            cam.zoomLocked = true;
            return;
        }
        float zx = (w.maxX - w.minX) / Math.max(1f, Ui.worldW()), zy = (w.maxY - w.minY) / Math.max(1f, Ui.worldH());
        float zMax = w.level.overviewZoom > 0f ? w.level.overviewZoom : MathUtils.clamp(Math.max(zx, zy), w.isGrid() ? 1.6f : 1.15f, 3.2f);
        cam.setLevel(w.minX, w.maxX, w.minY, w.maxY, zMax);
        cam.zoomLocked = w.isHub(); // the hub takes no pinch or wheel zoom (Kelvin 2026-10-16)
    }

    @Override
    public void dispose() {
        card.releaseJacket();
        thumbs.dispose();
        grid.dispose();
    }

    /** Saves level, camera, lens and selection; during a descent the camera from before the zoom. */
    private void saveState() {
        Profile p = game.profiles().active();
        if (p == null) return;
        boolean d = diving();
        p.mapLevel = world.level.id;
        p.mapCamSaved = true;
        p.mapCamX = d ? preX : cam.x;
        p.mapCamY = d ? preY : cam.y;
        p.mapZoom = d ? preZ : cam.zoom;
        p.lastDifficulty = lens.name();
        if (selected != null && selected.song != null && !selected.launch) p.lastSongId = selected.song.id();
        game.profiles().markDirty();
    }

    private void restoreCamera(boolean fresh) {
        Profile p = profile;
        if (!fresh && !world.isHub() && p != null && p.mapCamSaved && world.level.id.equals(p.mapLevel) && !Float.isNaN(p.mapCamX)
            && !Float.isNaN(p.mapCamY)) {
            if (world.isGrid()) grid.scrollTo(Ui.CY - p.mapCamY);
            else cam.snap(p.mapCamX, p.mapCamY, p.mapZoom > 0f ? p.mapZoom : 1f);
            return;
        }
        startCamera(world.start);
    }

    /** Re-reads the active profile when it or its revision changed (allocation allowed here). */
    private void refreshProfile(boolean force) {
        Profile p = game.profiles().active();
        int rev = game.profiles().revision();
        if (!force && p == profile && rev == profileRev) return;
        boolean switched = p != profile;
        profile = p;
        profileRev = rev;
        reduceMotion = p != null && p.settings != null && p.settings.reduceMotion;
        zh = p != null && p.settings != null && p.settings.zh();
        cam.setReduceMotion(reduceMotion);
        renderer.reduceMotion = reduceMotion;
        renderer.zh = zh;
        world.refresh(p, 0f);
        renderer.refreshLabels();
        hud.refresh(p);
        hud.setLevel(world);
        modeAvailable = computeModeAvailable();
        hud.setMode(modeAvailable, chapterMode());
        if (switched || force) {
            Difficulty d = p != null ? Difficulty.parse(p.lastDifficulty) : null;
            lens = d != null ? d : Difficulty.SURFACE;
            hud.setLens(lens);
            if (switched && !force) {
                card.close();
                selected = null;
            }
        }
    }

    // =================================================================== focus point
    /** HUD x of the uncovered map area's centre once the card is open (§3.6). */
    private float focusX() {
        if (SongCard.sheet()) return 0.5f * (Ui.left() + Ui.right());
        return 0.5f * (Ui.left() + Ui.right() - SongCard.sideWidth());
    }

    /** HUD y of the uncovered map area's centre once the card is open. */
    private float focusY() {
        if (SongCard.sheet()) return 0.5f * (card.targetTop() + Ui.top());
        return 0.5f * (Ui.bottom() + Ui.top());
    }

    private void glideToFocus(MapNode n, float dur) {
        if (world.isGrid()) { // the grid only scrolls the song's row into view
            grid.reveal(n, true);
            return;
        }
        float z = cam.zoom > 1.2f ? 1f : cam.zoom;
        float cx = MapCamera.centreFor(n.x, focusX(), z), cy = MapCamera.centreForY(n.y, focusY(), z);
        cam.keepReachable(cx, cy); // also near the level's edge (Kelvin 2026-10-13)
        float dx = cx - cam.x, dy = cy - cam.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > 2400f) flyTo(cx, cy, z);
        else cam.flyTo(cx, cy, z, dur, Interpolation.exp5Out, 0f);
    }

    /**
     * Puts {@code n} at the centre of the area the open card leaves free without a glide (the
     * saved camera was clamped once the dive closed the card).
     */
    private void focusNow(MapNode n) {
        if (world.isGrid()) {
            grid.reveal(n, false);
            return;
        }
        float z = cam.zoom > 1.2f ? 1f : cam.zoom;
        float cx = MapCamera.centreFor(n.x, focusX(), z), cy = MapCamera.centreForY(n.y, focusY(), z);
        cam.keepReachable(cx, cy);
        cam.snap(cx, cy, z);
    }

    private Color accentOf(MapNode n) {
        return n != null && n.isSong() ? Theme.difficultyAccent(lens.ordinal()) : Theme.GROUND_SIG;
    }

    // =================================================================== selection
    private void selectNode(MapNode n, boolean glide) {
        boolean changed = n != selected;
        selected = n;
        if (!world.isGrid()) n.popSelect(); // the grid's ring marks it, at its own size
        n.labelGlitch.trigger(GlitchText.Level.G2, time);
        sonar.ping(n.x, n.y, 420f, 0.6f, accentOf(n), 0.35f, 2);
        sonar.setHeartbeat(n.x, n.y, Anim.HEARTBEAT, 900f, 0.18f, accentOf(n));
        card.open(n, lens);
        if (glide) glideToFocus(n, 0.42f);
        if (changed) {
            SongMeta playing = game.audio().previewSong();
            if (playing != null && playing != n.song) game.audio().stopPreview(0.3f);
            game.audio().duckMenu(0.25f, 0.3f);
            dwell = n.launch ? -1f : 0f;
        }
    }

    /** Closes the card, except on ALL SONGS, where it stays open beside the grid (Kelvin 2026-10-19). */
    private void deselect() {
        if (!world.isGrid()) closeCard();
    }

    private void closeCard() {
        if (selected == null && !card.isOpen()) return;
        card.close();
        selected = null;
        dwell = -1f;
        game.audio().stopPreview(0.4f);
        game.audio().duckMenu(1f, 0.8f);
    }

    /** A tap on a spot: songs open the card, stories the story card, shafts and routes change level. */
    private void tapSpot(MapNode n) {
        switch (n.kind) {
            case MapNode.SONG:
            case MapNode.TUTORIAL:
                if (n.glitch) glitchTap(n);
                else if (n.locked && !world.isGrid()) lockTap(n); // ALL SONGS: the card shows how to unlock it
                else if (n.missing(lens)) missingTap(n);
                else selectNode(n, true);
                return;
            case MapNode.STORY:
                if (n.locked) lockTap(n);
                else openStory(n);
                return;
            case MapNode.SHAFT:
            case MapNode.ROUTE: {
                String to = n.spot != null ? target(n.spot) : null;
                if (n.sealed() || to == null || !data.levels.containsKey(to)) sealTap(n);
                else if (n.locked) lockTap(n);
                else goTo(to, n.kind == MapNode.SHAFT ? CaveFx.FALL : CaveFx.WHIRL, n);
                return;
            }
            case MapNode.CONTACT:
                sonar.ping(n.x, n.y, 320f, 0.8f, Theme.INK2, 0.3f, 2);
                game.toasts().show("UNCHARTED · MORE SIGNALS COMING", Theme.INK2);
                return;
            default:
                sealTap(n);
        }
    }

    private void lockTap(MapNode n) {
        n.shake = 0.45f;
        Haptics.light();
        deselect();
        renderer.showTip(n, zh ? "未解锁 LOCKED · 先通关 " + n.needsText + " 才能打开"
            : "LOCKED · CLEAR " + n.needsText + " FIRST");
    }

    /** A garbled secret song: it only flickers, no card (Kelvin 2026-10-16). */
    private void glitchTap(MapNode n) {
        n.shake = 0.3f;
        n.flash = 1f;
        n.labelGlitch.trigger(GlitchText.Level.G3, time);
        Haptics.light();
        deselect();
    }

    /** No chart for the lens difficulty: the hole only shakes (Kelvin 2026-10-16). */
    private void missingTap(MapNode n) {
        n.shake = 0.45f;
        Haptics.light();
        deselect();
    }

    private void sealTap(MapNode n) {
        n.shake = 0.45f;
        Haptics.light();
        boolean slot = n.kind == MapNode.SLOT;
        if (zh) renderer.showTip(n, slot ? "敬请期待 · COMING SOON" : "未开放 SEALED · 敬请期待 COMING SOON");
        else renderer.showTip(n, slot ? "COMING SOON" : "SEALED · COMING SOON");
    }

    private void openStory(MapNode n) {
        CaveData.Story st = n.spot != null && n.spot.story != null ? data.stories.get(n.spot.story) : null;
        if (st == null) {
            renderer.showTip(n, zh ? "剧情待定 · STORY COMING SOON" : "STORY COMING SOON");
            return;
        }
        deselect();
        peekCancel();
        n.popSelect();
        sonar.ping(n.x, n.y, 420f, 0.6f, Theme.GROUND_SIG, 0.35f, 2);
        storyNode = n;
        story.open(st, cam.hudX(n.drawX()), cam.hudY(n.drawY(time, reduceMotion)), zh);
        Haptics.light();
    }

    /** Story card closed: the story counts as read (dimmer hole, solid paths). */
    private void storyClosed() {
        MapNode n = storyNode;
        storyNode = null;
        Profile p = profile;
        if (p != null && n != null && n.spot != null && n.spot.story != null && p.setFlag(MapWorld.storyFlag(n.spot.story))) {
            game.profiles().markDirty();
        }
    }

    // =================================================================== card actions
    @Override
    public void cardClosed() {
        deselect();
    }

    @Override
    public void cardLens(Difficulty d) {
        setLens(d);
    }

    /** HOLD TO BUY completed: spends the shells and opens the single (Kelvin 2026-10-17, no dialog). */
    @Override
    public void buy(MapNode node) {
        if (diving() || node == null || node.song == null || !node.forSale) return;
        if (!game.profiles().buySong(node.song.id(), node.price)) {
            game.toasts().show(zh ? "贝壳不够" : "NOT ENOUGH SHELLS", Theme.INK2);
            return;
        }
        refreshProfile(false);
        node.unlockFx = 1f;
        game.toasts().show((zh ? "已购买 · " : "BOUGHT · ") + node.song.title(), Theme.GOLD);
        Haptics.heavy();
    }

    /** DESCEND released: starts the Descent (§3.12, X2c). */
    @Override
    public void descend(MapNode node, Difficulty d, boolean auto, float fx, float fy) {
        if (diving() || node == null || this.fx.busy() || (node.locked && !node.launch) || node.glitch || node.forSale) {
            return;
        }
        if (!node.launch && (node.song == null || !node.song.has(d))) {
            game.startGameplay(node.song, d, auto, fx, fy);   // refuses with its own toast
            return;
        }
        if (!node.launch) game.prefetchChart(node.song, d);
        Color acc = node.launch ? Theme.GOLD : Theme.difficultyAccent(d.ordinal());
        game.backdrop().sonar().ping(fx, fy, 600f, 0.6f, acc, 0.4f, 3);
        Haptics.medium();
        diveNode = node;
        diveDiff = d;
        diveAuto = auto;
        diveFx = fx;
        diveFy = fy;
        diveT = 0f;
        diveZooming = false;
        diveStarted = false;
        preX = cam.x;
        preY = cam.y;
        preZ = cam.zoom;
        int o = d.ordinal();
        returnHad = node.played[o];
        returnBest = node.best[o];
        returnCleared = node.anyPlayed;
        lockedWorld = world;
        lockedBefore = new boolean[world.all.length];
        for (MapNode m : world.all) lockedBefore[m.idx] = m.locked;
        diveDepth0 = game.backdrop().depth();
        diveDepth1 = game.gameplayDepth(node.song, node.launch && node.song != null ? node.song.easiest() : d);
        selected = node;
        dwell = -1f;
        peekCancel();
        sortPanel.close();
        card.close(true);
        gestures.cancel();
        hud.cancel();
        game.audio().stopPreview(0.4f);
        game.audio().stopSlot(AudioService.Slot.MENU, 0.4f);
    }

    private boolean diving() {
        return diveT >= 0f;
    }

    /** The Descent timeline (§3.12): the zoom stops when the hole's art is the X2c disc. */
    private void updateDive(float dt) {
        diveT += dt;
        float t = diveT;
        MapNode n = diveNode;
        if (!diveZooming && t >= MapStyle.DIVE_ZOOM_START) {
            diveZooming = true;
            float artR = MapStyle.THUMB_D * 0.5f * n.r / MapRenderer.SONG_R;
            float z = n.launch ? MapStyle.DIVE_ZOOM : MathUtils.clamp(artR / TransitionLayer.ART_R, 0.12f, 1f);
            cam.diveTo(MapCamera.centreFor(n.x, Ui.CX, z), MapCamera.centreForY(n.y, Ui.CY, z), z,
                MapStyle.DIVE_ZOOM_END - MapStyle.DIVE_ZOOM_START, Interpolation.exp5In);
        }
        float k = MathUtils.clamp((t - MapStyle.DIVE_ZOOM_START) / (MapStyle.DIVE_ZOOM_END - MapStyle.DIVE_ZOOM_START),
            0f, 1f);
        Backdrop b = game.backdrop();
        b.setDepthImmediate(MathUtils.lerp(diveDepth0, diveDepth1, Interpolation.pow2.apply(k)));
        b.setVirtualVelocity(0f, -MapStyle.DIVE_STREAK_V * Interpolation.pow2In.apply(k));
        renderer.textFade = 1f - MathUtils.clamp(k * 3f, 0f, 1f);
        if (!diveStarted && t >= MapStyle.DIVE_START_GAMEPLAY) {
            diveStarted = true;
            TransitionLayer tl = game.transitions();
            if (tl != null && !n.launch && n.song != null) {
                String ct = n.song.constantBase(diveDiff);
                tl.setCoverArt(thumbs.region(renderer.slotOf(n)), ThumbAtlas.cellSizeFor(2f * TransitionLayer.ART_R),
                    n.song.title(), n.song.artist(), Theme.DIFFICULTY_NAMES[diveDiff.ordinal()] + (ct != null ? " " + ct : ""),
                    Theme.difficultyAccent(diveDiff.ordinal()), n.song.charter(diveDiff), n.song.illustrator());
                if (ct != null) tl.setCoverDiffMark(n.song.constantMark(diveDiff));
            }
            boolean ok = n.launch ? game.startTutorial() : game.startGameplay(n.song, diveDiff, diveAuto, diveFx, diveFy);
            if (!ok) {
                if (tl != null) tl.setCoverArt(null, 0f, null, null, null, null);
                abortDive();
            }
        }
    }

    /** A refused start: the camera, HUD, card and music spring back. */
    private void abortDive() {
        MapNode n = diveNode;
        diveT = -1f;
        diveNode = null;
        diveZooming = false;
        diveStarted = false;
        exitK = 1f;
        renderer.textFade = 1f;
        Backdrop b = game.backdrop();
        b.setVirtualVelocity(0f, 0f);
        b.setDepth(world.level.depth);
        cam.flyTo(preX, preY, preZ, 0.5f, Interpolation.exp5Out, 0f);
        game.audio().playMenu();
        if (n != null) {
            selected = null;
            selectNode(n, false);
        }
    }

    @Override
    public void cardStep(int dir) {
        MapNode cur = selected;
        if (cur == null || !cur.isSong() || world.nodes.length == 0) return;
        int count = world.nodes.length, j = cur.order;
        for (int step = 0; step < count; step++) {
            j = (j + dir + count) % count; // wraps round: › on the last song opens the first (Kelvin 2026-10-17)
            if (opens(world.nodes[j])) break;
        }
        if (j == cur.order || !opens(world.nodes[j])) return;
        selectNode(world.nodes[j], true);
    }

    /** True when a tap on song hole {@code n} opens its card (unlocked or on ALL SONGS, not garbled, with a chart for the lens). */
    private boolean opens(MapNode n) {
        return (!n.locked || world.isGrid()) && !n.glitch && !n.missing(lens);
    }

    @Override
    public void cardDetent() {
        if (selected != null) glideToFocus(selected, 0.32f);
    }

    // =================================================================== HUD actions
    @Override
    public void openProfile() {
        // the curtain names the destination, which follows the language (the map itself stays English)
        CharSequence title = Lang.t("PROFILE", "账号与统计");
        if (game.transitionTo(game.profile(), TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, title)) {
            leaving();
        }
    }

    @Override
    public void openSettings() {
        if (game.transitionTo(game.settings(), TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, Lang.t("SETTINGS", "设置"))) {
            leaving();
        }
    }

    private void leaving() {
        dwell = -1f;
        game.audio().stopPreview(0.3f);
    }

    @Override
    public void lensChanged(Difficulty d) {
        setLens(d);
        if (selected != null) card.setDifficulty(d);
    }

    /**
     * Sets the lens. An open card whose song has no chart for it goes away (Kelvin 2026-10-16), and
     * ALL SONGS is laid out again when its order or filter depends on the lens.
     */
    private void setLens(Difficulty d) {
        if (d == null || d == lens) return;
        lens = d;
        hud.setLens(d);
        Haptics.light();
        MapNode n = selected;
        if (world.isGrid()) {
            renderer.resetTitles();
            if (gridSort == MapWorld.SORT_LEVEL || gridSort == MapWorld.SORT_BEST || filter.active()) relayoutGrid();
            // a song without this difficulty hands the card to the first one with it (Kelvin 2026-10-19 14:01)
            else if (n != null && n.missing(d)) openGridCard(null);
            return;
        }
        if (n != null && n.missing(d)) deselect();
        else if (n != null && !n.launch) sonar.ping(n.x, n.y, 320f, 0.5f, Theme.difficultyAccent(d.ordinal()), 0.3f, 1);
    }

    private void flyTo(float cx, float cy, float z) {
        float dx = cx - cam.x, dy = cy - cam.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float hop = 0.4f * MathUtils.clamp((dist - 1200f) / 2400f, 0f, 1f);
        cam.flyTo(cx, cy, z, MapCamera.flyDuration(dist), Interpolation.pow3, hop);
    }

    /** SORT or F: toggles the sort panel, which also holds the filter (ALL SONGS only, Kelvin 2026-10-16). */
    @Override
    public void openSort() {
        if (diving() || !world.isGrid()) return;
        if (sortPanel.isOpen()) {
            sortPanel.close();
            return;
        }
        peekCancel();
        sortPanel.open();
    }

    /** The hub's ALL SONGS button. */
    @Override
    public void openAllSongs() {
        if (world.isHub()) goTo(ALL, CaveFx.WHIRL, null);
    }

    /** The hub's story / chapter mode toggle: switches MAIN's mode (saved with the diver). */
    @Override
    public void toggleMainMode() {
        if (profile == null || !modeAvailable) return;
        profile.mainChapterMode = !profile.mainChapterMode;
        game.profiles().markDirty();
        hud.setMode(true, chapterMode());
        Haptics.light();
    }

    /** The filter changed: ALL SONGS shows only the songs it keeps. */
    @Override
    public void filterChanged() {
        if (world.isGrid()) relayoutGrid();
        Haptics.light();
    }

    // =================================================================== peek (long-press)
    private void peekCancel() {
        peekT = -1f;
        if (peekShown) peek.hide();
        peekShown = false;
    }

    // =================================================================== map gestures
    @Override
    public void mapDown(float hx, float hy, boolean caught) {
        if (caught) return;
        MapNode n = renderer.nodeAt(hx, hy);
        if (n != null) {
            n.pressed = true;
            pressedNode = n;
            peekT = n.isSong() && !n.locked && !n.glitch ? 0f : -1f;
            renderer.peekNode = peekT >= 0f ? n : null;
            Haptics.light();
        }
    }

    @Override
    public void mapPanStart() {
        if (pressedNode != null) {
            pressedNode.pressed = false;
            pressedNode = null;
        }
        peekCancel();
        if (longWater) {
            bubbles.stopStream();
            longWater = false;
        }
    }

    @Override
    public void mapTap(float hx, float hy) {
        if (peekShown) {
            peekCancel();
            return;
        }
        if (longWater) return;
        MapNode n = pressedNode != null ? pressedNode : renderer.nodeAt(hx, hy);
        if (n != null) {
            tapSpot(n);
            return;
        }
        deselect();
        game.backdrop().touch(hx, hy);
        bubbles.burst(cam.worldX(hx), cam.worldY(hy), 8, 18f * cam.zoom);
    }

    @Override
    public void mapDoubleTap(float hx, float hy) {
        if (renderer.nodeAt(hx, hy) != null) {
            mapTap(hx, hy);
            return;
        }
        if (world.isHub() || world.isGrid()) return; // no zoom on the hub or the grid
        float far = cam.zoomMax();
        float tz = cam.zoom < 0.5f * (1f + far) ? far : 1f;
        float wx = cam.worldX(hx), wy = cam.worldY(hy);
        cam.flyTo(wx - (hx - Ui.CX) * tz, wy - (hy - Ui.CY) * tz, tz, 0.38f, Interpolation.exp5Out, 0f);
    }

    @Override
    public void mapLongPress(float hx, float hy) {
        if (pressedNode != null) {
            if (pressedNode.isSong() && !pressedNode.locked && !pressedNode.glitch) {
                peekShown = true;
                peek.show(pressedNode);
                Haptics.light();
            }
            return;
        }
        longWater = true;
        bubbles.stream(cam.worldX(hx), cam.worldY(hy));
    }

    @Override
    public void mapUp() {
        if (pressedNode != null) {
            pressedNode.pressed = false;
            pressedNode = null;
        }
        peekCancel();
        if (longWater) {
            bubbles.stopStream();
            longWater = false;
        }
    }

    @Override
    public void mapDragged(float hx, float hy, float dist) {
        bubbles.wake(cam.worldX(hx), cam.worldY(hy), dist * cam.zoom);
    }

    @Override
    public void mapRiseHold() {
        rise();
    }

    // =================================================================== input routing
    @Override
    protected boolean inputLocked() {
        return super.inputLocked() || diving() || fx.busy();
    }

    @Override
    protected boolean onTouchDown(float x, float y, int pointer, int button) {
        if (pointer < 0 || pointer >= owner.length) return false;
        if (story.isOpen()) {
            story.touchDown(x, y, pointer);
            owner[pointer] = OWN_STORY;
            return true;
        }
        if (sortPanel.isOpen()) {
            if (sortPanel.touchDown(x, y, pointer)) {
                owner[pointer] = OWN_SORT;
                return true;
            }
            if (!hud.sort.hit(x, y)) {
                sortPanel.close();
                owner[pointer] = OWN_SWALLOW;
                return true;
            }
        }
        if (card.touchDown(x, y, pointer)) {
            owner[pointer] = OWN_CARD;
            return true;
        }
        if (hud.touchDown(x, y, pointer)) {
            owner[pointer] = OWN_HUD;
            return true;
        }
        owner[pointer] = OWN_MAP;
        return gestures.touchDown(x, y, pointer);
    }

    @Override
    protected boolean onTouchDragged(float x, float y, int pointer) {
        if (pointer < 0 || pointer >= owner.length) return false;
        switch (owner[pointer]) {
            case OWN_STORY:
                return story.touchDragged(x, y, pointer);
            case OWN_SORT:
                return sortPanel.touchDragged(x, y, pointer);
            case OWN_CARD:
                return card.touchDragged(x, y, pointer);
            case OWN_HUD:
                return hud.touchDragged(x, y, pointer);
            case OWN_MAP:
                return gestures.touchDragged(x, y, pointer);
            case OWN_SWALLOW:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected boolean onTouchUp(float x, float y, int pointer, int button) {
        if (pointer < 0 || pointer >= owner.length) return false;
        int o = owner[pointer];
        owner[pointer] = OWN_NONE;
        switch (o) {
            case OWN_STORY:
                return story.touchUp(x, y, pointer);
            case OWN_SORT:
                return sortPanel.touchUp(x, y, pointer);
            case OWN_CARD:
                return card.touchUp(x, y, pointer);
            case OWN_HUD:
                return hud.touchUp(x, y, pointer);
            case OWN_MAP:
                return gestures.touchUp(x, y, pointer);
            case OWN_SWALLOW:
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onTouchCancel(int pointer) {
        if (pointer >= 0 && pointer < owner.length) owner[pointer] = OWN_NONE;
        story.cancel();
        sortPanel.cancel();
        card.cancel();
        hud.cancel();
        gestures.cancel();
    }

    @Override
    protected boolean onMouseMoved(float x, float y) {
        mouseX = x;
        mouseY = y;
        sortPanel.mouseMoved(x, y);
        card.mouseMoved(x, y);
        hud.mouseMoved(x, y);
        return false;
    }

    @Override
    protected boolean onScrolled(float amountX, float amountY) {
        if (amountY == 0f) return false;
        Vector3 v = toWorld(Gdx.input.getX(), Gdx.input.getY());
        mouseX = v.x;
        mouseY = v.y;
        if (story.isOpen()) return true;
        if (sortPanel.isOpen() && sortPanel.contains(mouseX, mouseY)) return true;
        if (card.isOpen() && mouseX >= card.left() && !SongCard.sheet()) return true;
        if (card.isOpen() && SongCard.sheet() && mouseY <= card.top()) return true;
        if (world.isHub()) return true; // no zoom and nothing to rise to on the hub
        if (world.isGrid()) { // the grid does not zoom (Kelvin 2026-10-19 21:16): the wheel scrolls it
            grid.scrollTo(grid.scroll() + amountY * 120f);
            return true;
        }
        if (amountY > 0f && cam.zoom >= cam.zoomMax() * 0.99f && !cam.isGliding()) {
            cam.bump(0.06f); // at the overview the map gives a little and springs back
            return true;
        }
        cam.zoomAbout(mouseX, mouseY, (float) Math.pow(1.1, amountY));
        cam.poke();
        return true;
    }

    /** BACK / Esc: overlays first, then the card, then up one level (nothing on the hub). */
    @Override
    protected void onBack() {
        if (peekShown) {
            peekCancel();
            return;
        }
        if (story.isOpen()) {
            story.close();
            return;
        }
        if (sortPanel.isOpen()) {
            sortPanel.close();
            return;
        }
        if (card.isOpen() && !world.isGrid()) {
            deselect();
            return;
        }
        rise();
    }

    @Override
    protected boolean onKeyDown(int keycode) {
        switch (keycode) {
            case Input.Keys.PLUS:
            case Input.Keys.EQUALS:
            case Input.Keys.NUMPAD_ADD:
                if (!world.isGrid()) cam.zoomAbout(Ui.CX, Ui.CY, 1f / 1.25f); // the grid does not zoom
                return true;
            case Input.Keys.MINUS:
            case Input.Keys.NUMPAD_SUBTRACT:
                if (!world.isGrid()) cam.zoomAbout(Ui.CX, Ui.CY, 1.25f);
                return true;
            case Input.Keys.PAGE_UP:
                rise();
                return true;
            case Input.Keys.TAB: {
                if (world.nodes.length == 0) return true;
                boolean back = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                int i = selected != null && selected.isSong() ? selected.order : (back ? 0 : -1);
                for (int step = 0; step < world.nodes.length; step++) {
                    i = (i + (back ? -1 : 1) + world.nodes.length) % world.nodes.length;
                    if (opens(world.nodes[i])) break;
                }
                if (opens(world.nodes[i])) selectNode(world.nodes[i], true);
                return true;
            }
            case Input.Keys.Q:
                card.keyStep(-1);
                return true;
            case Input.Keys.E:
                card.keyStep(1);
                return true;
            case Input.Keys.NUM_1:
            case Input.Keys.NUM_2:
            case Input.Keys.NUM_3:
            case Input.Keys.NUM_4:
                lensChanged(Difficulty.byOrdinal(keycode - Input.Keys.NUM_1));
                return true;
            case Input.Keys.F:
                openSort();
                return true;
            case Input.Keys.ENTER:
            case Input.Keys.NUMPAD_ENTER:
                if (card.isOpen()) card.fireDescend();
                return true;
            default:
                return false;
        }
    }

    private void keyboardPan(float dt) {
        if (Gdx.input == null) return;
        float dx = 0f, dy = 0f;
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) dx -= 1f;
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) dx += 1f;
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) dy += 1f;
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) dy -= 1f;
        if (world.isGrid()) dx = 0f;
        if (dx == 0f && dy == 0f) return;
        float k = MapStyle.KEY_PAN * cam.zoom * dt;
        cam.nudge(dx * k, dy * k);
    }

    // =================================================================== frame
    /**
     * The rise ring (Kelvin 2026-10-19 18:46, which dropped the overview chip and the double-pinch
     * rise): two fingers held still fill it over {@link MapStyle#RISE_HOLD} s, shown from
     * {@link MapStyle#RISE_RING} s on, and rise one level when it closes; none where nothing rises.
     */
    private void updateRise() {
        float held = riseTarget() != null ? gestures.holdTime() : 0f;
        hud.setRiseHold((held - MapStyle.RISE_RING) / (MapStyle.RISE_HOLD - MapStyle.RISE_RING),
            gestures.holdCx(), gestures.holdCy());
    }

    private void update(float dt) {
        time += dt;
        gestures.update(dt);
        refreshProfile(false);
        boolean typing = editingTextField() != null;
        if (!inputLocked() && !typing && !gestures.active() && !story.isOpen()) keyboardPan(dt);
        if (diving()) updateDive(dt);
        else if (exitK > 0f) exitK = Math.max(0f, exitK - dt / 0.2f);
        fx.update(dt);
        if (!card.isOpen()) cam.clearReachable();
        if (world.isGrid()) {
            grid.masking = !diving();
            grid.layout();
            grid.place();
            hud.setGridRight(grid.areaR);
        }
        cam.update(dt);
        if (arriving && !cam.isGliding()) arriving = false;
        card.update(dt, time, sonar.heartbeatPhase());
        story.update(dt);
        boolean sheet = SongCard.sheet();
        float mapRight = sheet ? Ui.right() : card.left();
        float mapBottom = sheet ? Math.max(card.top(), Ui.safeBottom()) : Ui.safeBottom();

        hud.setFilter(filter.active());
        float k = MapUi.k();
        updatePeek(dt);
        updateRise();
        float exit = diving() ? MathUtils.clamp(diveT / 0.2f, 0f, 1f) : exitK;
        hud.setExit(exit);
        hud.layout(mapRight, mapBottom, card.amount(), 1f - 0.7f * fx.cover());
        gridA = (1f - 0.7f * fx.cover()) * (1f - exit);
        game.toasts().setAnchorTop(hud.globalToastTop());
        // the sort panel rises from the SORT button and closes when the button goes away
        if (sortPanel.isOpen() && !hud.sort.isVisible()) sortPanel.close();
        sortPanel.layout(hud.sort.x() + hud.sort.width(), hud.sort.y() + hud.sort.height() + 16f * k,
            hud.clusterBottom() - 20f * k);
        sortPanel.update(dt, gridSort);
        hud.setSortOpen(sortPanel.isOpen());
        hud.update(dt, time);
        renderer.clearOccluders();
        hud.occluders(renderer);
        float far = MapRenderer.OFFSCREEN;
        if (card.amount() > 0.01f) {
            if (sheet) {
                renderer.addOccluder(Ui.left() - far, Ui.bottom() - far, Ui.worldW() + 2f * far, card.top() - Ui.bottom() + far);
            } else {
                renderer.addOccluder(card.left(), Ui.bottom() - far, Ui.right() - card.left() + far, Ui.worldH() + 2f * far);
            }
        }
        if (sortPanel.amount() > 0.01f) {
            renderer.addOccluder(sortPanel.left(), sortPanel.top() - sortPanel.height(), sortPanel.width(),
                sortPanel.height());
        }

        sonar.update(dt);
        for (MapNode n : world.all) {
            n.update(dt, cam.dispVx, cam.dispVy, sonar.waveAt(n.x, n.y), n == selected, reduceMotion);
        }
        bubbles.update(dt);
        thumbs.update();
        renderer.updateFx(dt);
        updateFlow(dt);

        Backdrop b = game.backdrop();
        b.setCameraMotion(cam.dispVx / cam.zoom, cam.dispVy / cam.zoom, cam.renderX(), cam.renderY());

        if (dwell >= 0f && selected != null && !inputLocked()) {
            dwell += dt;
            if (dwell >= MapStyle.PREVIEW_DWELL) {
                dwell = -1f;
                boolean on = profile == null || profile.settings == null || profile.settings.previewOn;
                if (on && selected.song != null && !selected.launch) game.audio().playPreview(selected.song);
            }
        }

        mapCamera.viewportWidth = Ui.worldW();
        mapCamera.viewportHeight = Ui.worldH();
        mapCamera.position.set(cam.renderX(), cam.renderY(), 0f);
        mapCamera.zoom = cam.zoom;
        mapCamera.update();

        renderer.time = time;
        renderer.lens = lens;
        renderer.selected = selected;
        renderer.beat = sonar.heartbeatPhase();
        renderer.mapLeft = Ui.safeLeft();
        renderer.mapRight = mapRight;
    }

    /** Long-press ring (fills over 450 ms from the press) and the peek tooltip. */
    private void updatePeek(float dt) {
        if (peekT >= 0f && pressedNode != null) {
            peekT += dt;
            renderer.peekNode = pressedNode;
            renderer.peekP = MathUtils.clamp((peekT - PEEK_DELAY) / (MapStyle.PEEK_T - PEEK_DELAY), 0f, 1f);
        } else if (renderer.peekP > 0f) {
            renderer.peekP = Math.max(0f, renderer.peekP - dt / 0.12f);
            if (renderer.peekP <= 0f) renderer.peekNode = null;
        }
        peek.update(dt);
    }

    /**
     * Draw order is grouped by texture: backdrop, world pass, then the screen pass by phase
     * (atlas shapes, card jacket, avatar, UI raster, NUMBER raster, bold, DISPLAY), then the
     * overlays (filter panel, peek, story card) and the level transition on top. On ALL SONGS the
     * grid's UI and NUMBER text comes before the HUD's atlas phase, so the player block covers it.
     */
    @Override
    protected void draw(float delta) {
        update(fixedDelta > 0f ? fixedDelta : delta);
        renderer.frame();
        batch.begin();
        game.backdrop().render(batch, viewport, delta);
        phaseCalls[0] = batch.renderCalls;
        batch.setProjectionMatrix(mapCamera.combined);
        renderer.drawWorld(batch);
        phaseCalls[1] = batch.renderCalls;
        batch.setProjectionMatrix(camera.combined);
        renderer.drawTextShapes();
        boolean gridOn = world.isGrid();
        if (gridOn) {
            grid.drawShapes(gridA);
            // ALL SONGS' titles, scores and constants before the HUD and the card, so the player block stays on top
            // of songs scrolled under it (Kelvin 2026-10-20 18:45 「我要profile的图层最大」); two more texture
            // switches than the maps, whose labels fade out under the block instead
            renderer.drawTextUi(batch);
            grid.drawUi(batch, zh, filter, gridA);
            renderer.drawTextNumbers(batch);
        } else if (!SongCard.sheet() && card.isOpen() && selected != null) {
            renderer.drawLeader(selected, card.left(), hud.gaugeLeft(), card.accent(), card.amount());
        }
        hud.drawShapes(batch);
        card.drawShapes(batch);
        phaseCalls[2] = batch.renderCalls;
        card.drawJacket(batch);
        hud.drawAvatar(batch);
        phaseCalls[3] = batch.renderCalls;
        if (!gridOn) renderer.drawTextUi(batch);
        hud.drawUi(batch);
        card.drawUi(batch);
        if (!gridOn) renderer.drawTextNumbers(batch);
        hud.drawNumbers(batch);
        card.drawNumbers(batch);
        renderer.drawTextRubik(batch);
        phaseCalls[4] = batch.renderCalls;
        hud.drawBold(batch);
        card.drawBold(batch);
        card.drawDisplay(batch);
        phaseCalls[5] = batch.renderCalls;
        sortPanel.draw(batch);
        peek.draw(batch, cam, renderer.mapLeft, renderer.mapRight, SongCard.sheet() ? card.top() : Ui.safeBottom(),
            Ui.safeTop());
        story.draw(batch);
        fx.draw(batch);
        batch.end();
        phaseCalls[6] = batch.renderCalls;
    }

    /**
     * Debug: cumulative batch flushes after backdrop, world, screen atlas (map, HUD, card),
     * jacket + avatar, text phases and the whole frame including overlays (read by test
     * harnesses; written every frame).
     */
    final int[] phaseCalls = new int[7];
}
