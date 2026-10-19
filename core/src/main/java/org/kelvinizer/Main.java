package org.kelvinizer;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import org.kelvinizer.audio.AudioService;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.gameplay.GameplayScreen;
import org.kelvinizer.gameplay.IntroductionScreen;
import org.kelvinizer.library.ChapterMeta;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.map.MapScreen;
import org.kelvinizer.play.PlayResult;
import org.kelvinizer.play.RecordOutcome;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.CalibrationScreen;
import org.kelvinizer.screens.FirstRunScreen;
import org.kelvinizer.screens.ProfileScreen;
import org.kelvinizer.screens.ResultScreen;
import org.kelvinizer.screens.SettingsScreen;
import org.kelvinizer.screens.VisualsScreen;
import org.kelvinizer.screens.WelcomeScreen;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.ArtCache;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.KkServices;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Toast;
import org.kelvinizer.ui.Ui;
import org.kelvinizer.ui.UiKit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 游戏的服务中心：创建并持有字体、绘图、背景、转场、曲库、存档、音频等共用服务，缓存所有画面，所有画面切换都经过这里。
 * 开始一局时，DIVE 转场盖住画面期间在后台线程解析谱面；一局结束后记录成绩，再以 ASCENT 转场进入结算画面。
 *
 * <p>The service hub (ARCHITECTURE §2 and §14): owns every shared service, the cached screens
 * and all screen switching.
 *
 * <ul>
 *   <li><b>Services</b> (created in {@link #create()}, disposed once in {@link #dispose()}):
 *       the visual kit ({@link UiKit}: fonts, draw, backdrop, transitions, glitch, toasts,
 *       avatars, exposed through {@link KkServices}), the song {@link #library()}, the
 *       {@link #profiles()} store, {@link #audio()} and the {@link #hitFx()} facade.</li>
 *   <li><b>Screens</b> are created lazily once and cached ({@link #welcome()}, {@link #map()},
 *       ...).</li>
 *   <li><b>Switching</b> only through {@link #transitionTo} / {@link #switchNow}; the actual
 *       {@code setScreen} always happens at the start of the next frame, never inside another
 *       screen's render. Switching to the screen that is already shown runs its {@code hide()}
 *       then {@code show()} again ({@link Game#setScreen} semantics).</li>
 *   <li><b>Covers</b> are never left dangling: a cover cancelled before it closed
 *       ({@link TransitionLayer#cancel()}, which only Main may call) is detected at the start of
 *       the next frame; a pending chart load is abandoned, a pending result is presented without
 *       animation and a pending {@link #transitionTo} switch is dropped.</li>
 *   <li><b>Flow</b> (§2): Welcome → (first run ? FirstRun → tutorial → map : map);
 *       {@link #startGameplay} / {@link #startTutorial} parse the chart on a worker thread while a
 *       DIVE covers the screen; {@link #showResult} records the run and ascends to the result.</li>
 * </ul>
 * All public methods are GL-thread only.
 */
public class Main extends Game implements KkServices {
    private static final String TAG = "Main";
    /** Gameplay depth fraction of the chapter's depth band per difficulty (DESIGN §2.6). */
    private static final float[] DIFFICULTY_DEPTH_F = {0.10f, 0.35f, 0.60f, 0.85f};
    /** How long a {@link #prefetchChart} parse stays usable by {@link #startGameplay}. */
    private static final long PREFETCH_TTL_NANOS = 5_000_000_000L;

    private UiKit ui;
    private SongLibrary library;
    private ProfileStore profiles;
    private AudioService audio;
    private HitFx hitFx;

    private WelcomeScreen welcome;
    private MapScreen map;
    private GameplayScreen gameplay;
    private IntroductionScreen introduction;
    private ResultScreen result;
    private ProfileScreen profile;
    private SettingsScreen settings;
    private CalibrationScreen calibration;
    private VisualsScreen visuals;
    private FirstRunScreen firstRun;

    /** Screen to show at the start of the next frame (deferred switch). */
    private Screen pendingScreen;
    /** Screen that already opened its font epoch while it was prepared (see {@link #prepareFonts}). */
    private Screen fontsPreparedFor;
    /** Trim font pages after the next frame (the first frame of a new screen). */
    private boolean trimFonts;
    /** Destination of the running {@link #transitionTo} cover. */
    private Screen coverTarget;
    private final Runnable switchToCoverTarget = new Runnable() {
        @Override
        public void run() {
            if (coverTarget != null) pendingScreen = coverTarget;
            coverTarget = null;
        }
    };
    /** Result handed to {@link #showResult}, presented while the ASCENT covers (null when idle). */
    private PlayResult pendingResult;
    private RecordOutcome pendingOutcome;
    private boolean resultPending;
    private final Runnable presentPendingResult = new Runnable() {
        @Override
        public void run() {
            presentResultNow();
        }
    };
    /**
     * HitFx's High → Low auto-drop (DESIGN §5.9), run once mid-song inside {@code hitFx.update}:
     * persists LOW for this device and matches the backdrop particles to it. It deliberately
     * neither calls {@link #applySettings()} (which re-lays out the screen) nor toasts (the drop is
     * silent during play; the result screen shows the notice).
     */
    private final Runnable particlesAutoDropped = new Runnable() {
        @Override
        public void run() {
            DeviceSettings d = profiles.device();
            d.particleQuality = DeviceSettings.PARTICLES_LOW;
            d.particlesAutoDropped = true;
            profiles.markDeviceDirty();
            ui.backdrop().setParticleScale(0.5f);
        }
    };
    /** The chart load in flight (null when idle). */
    private ChartLoad load;
    /** A parse started ahead of {@link #startGameplay} by {@link #prefetchChart} (null when none). */
    private Prefetch prefetch;
    /** Profile whose diver settings {@link #applySettings()} last pushed (detects profile switches). */
    private Profile appliedProfile;
    private ExecutorService loader;
    private boolean disposed;

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void create() {
        Gdx.input.setCatchKey(Input.Keys.BACK, true);
        ui = new UiKit();
        ui.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        library = new SongLibrary();
        library.load();
        profiles = new ProfileStore();
        profiles.load(library);
        audio = new AudioService(profiles);
        hitFx = new HitFx(ui.draw());
        hitFx.setOnAutoDrop(particlesAutoDropped);
        ui.backdrop().setOnAutoLow(new Runnable() {
            @Override
            public void run() {
                DeviceSettings d = profiles.device();
                d.backgroundDetail = DeviceSettings.DETAIL_LOW;
                d.backdropAutoDropped = true;
                profiles.markDeviceDirty();
                ui.toasts().show(Lang.t("BACKGROUND DETAIL SET TO LOW", "背景细节已自动调低"), Theme.AUTO);
            }
        });
        applySettings();
        int imported = profiles.legacyImportedCount();
        if (imported > 0) {
            ui.toasts().show(Lang.zh() ? "导入了 " + imported + " 条以前的成绩"
                : "IMPORTED " + imported + (imported == 1 ? " EARLIER RECORD" : " EARLIER RECORDS"), Theme.GOLD);
        }
        switchNow(welcome());
    }

    /**
     * Recovers a cancelled cover, applies a pending switch, re-applies settings after a profile
     * switch, updates audio and autosave, renders the screen, then overlays.
     */
    @Override
    public void render() {
        recoverCancelledCover();
        applyPendingSwitch();
        if (prefetch != null && prefetch.expired()) prefetch = null; // never claimed: drop the chart
        if (profiles.active() != appliedProfile) applySettings();
        float delta = Gdx.graphics.getDeltaTime();
        audio.update(delta);
        // no autosave serialisation or disk I/O during a song (explicit saves still happen)
        profiles.setAutosaveSuspended(screen != null && (screen == gameplay || screen == introduction));
        profiles.update(delta);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        super.render();
        ui.renderOverlays(delta);
        if (trimFonts) {
            // after the new screen's first frame: free own font pages it does not use (DESIGN §14)
            trimFonts = false;
            ui.fonts().trim();
        }
    }

    /** UI metrics, fonts and overlays first, then the current screen. */
    @Override
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) return; // minimised
        ui.resize(width, height);
        if (screen != null) screen.resize(width, height);
    }

    /** Saves profiles, pauses audio, then forwards to the screen (gameplay auto-pauses). */
    @Override
    public void pause() {
        profiles.saveNow();
        audio.pause();
        super.pause();
    }

    /** Resets draw state after a possible context loss and fades menu audio back in. */
    @Override
    public void resume() {
        ui.resume();
        audio.resume();
        super.resume();
    }

    /** Disposes every cached screen and service exactly once. */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (screen != null) screen.hide();
        screen = null;
        pendingScreen = null;
        if (loader != null) loader.shutdownNow();
        prefetch = null;
        disposeScreen(welcome);
        disposeScreen(map);
        disposeScreen(gameplay);
        disposeScreen(introduction);
        disposeScreen(result);
        disposeScreen(profile);
        disposeScreen(settings);
        disposeScreen(calibration);
        disposeScreen(visuals);
        disposeScreen(firstRun);
        if (profiles != null) profiles.dispose();
        if (audio != null) audio.dispose();
        if (library != null) library.dispose();
        if (hitFx != null) hitFx.dispose();
        if (ui != null) ui.dispose();
    }

    // ------------------------------------------------------------------ settings
    /**
     * Pushes the active diver's settings and the device settings into the static UI switches
     * and shared services: the interface language ({@link Lang}), GlitchText intensity / reduce
     * flashing, Ui edge padding and UI scale (then re-lays out fonts, overlays and the current
     * screen), Backdrop detail / particle scale / reduce motion, Haptics and the HitFx quality.
     * Music and hit volumes are read live by {@link AudioService#update(float)}. Called at startup
     * and automatically at the start of the frame after the active profile changed
     * ({@code profiles().switchTo}, deleting the active diver); SettingsScreen calls it after every
     * change.
     */
    public void applySettings() {
        DeviceSettings d = profiles.device();
        Profile p = profiles.active();
        appliedProfile = p;
        Settings s = p != null ? p.settings : null;
        Lang.set(s != null && s.zh());
        if (s != null) {
            GlitchText.setIntensity(s.glitchIntensity);
            GlitchText.setReduceFlashing(s.reduceFlashing);
            ui.backdrop().setReduceMotion(s.reduceMotion);
            Haptics.setEnabled(s.haptics != Settings.HAPTICS_OFF);
        }
        Backdrop.Detail detail = d.backgroundDetail == DeviceSettings.DETAIL_LOW ? Backdrop.Detail.LOW
            : Backdrop.Detail.HIGH;
        if (ui.backdrop().detail() != detail) ui.backdrop().setDetail(detail);
        ui.backdrop().setParticleScale(d.particleQuality == DeviceSettings.PARTICLES_HIGH ? 1f
            : (d.particleQuality == DeviceSettings.PARTICLES_LOW ? 0.5f : 0f));
        hitFx.setQuality(d.particleQuality);
        ui.setShowFps(d.showFps);
        Ui.setEdgePadding(d.edgePadding);
        Ui.setUiScaleSetting(d.uiScale);
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    // ------------------------------------------------------------------ switching
    /** CURTAIN transition to {@code next}. See {@link #transitionTo(Screen, TransitionLayer.Style, float, float, CharSequence)}. */
    public boolean transitionTo(Screen next) {
        return transitionTo(next, TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, null);
    }

    /** Transition with {@code style}, centred. */
    public boolean transitionTo(Screen next, TransitionLayer.Style style) {
        return transitionTo(next, style, Ui.CX, Ui.CY, null);
    }

    /** Transition with {@code style} focused on world point (focusX, focusY) (IRIS origin). */
    public boolean transitionTo(Screen next, TransitionLayer.Style style, float focusX, float focusY) {
        return transitionTo(next, style, focusX, focusY, null);
    }

    /**
     * Covers the screen with {@code style}, switches to {@code next} at the start of the frame
     * after the cover closed, then opens.
     *
     * @param coveredLabel text shown while covered (CURTAIN destination name, DIVE status line);
     *                     null for none
     * @return false (and nothing happens) when {@code next} is null, a transition is running or a
     *         chart load is pending
     */
    public boolean transitionTo(Screen next, TransitionLayer.Style style, float focusX, float focusY,
                                CharSequence coveredLabel) {
        TransitionLayer t = ui.transitions();
        if (next == null || t.isBusy() || load != null) return false;
        t.setCoveredLabel(coveredLabel);
        coverTarget = next;
        if (!t.start(style, focusX, focusY, switchToCoverTarget)) {
            coverTarget = null;
            return false;
        }
        return true;
    }

    /**
     * Switches to {@code next} at the start of the next frame, without animation. Switching to the
     * current screen runs its {@code hide()} then {@code show()}.
     */
    public void switchNow(Screen next) {
        if (next != null) pendingScreen = next;
    }

    private void applyPendingSwitch() {
        Screen next = pendingScreen;
        if (next == null) return;
        pendingScreen = null;
        if (next != fontsPreparedFor) ui.fonts().beginScreen();
        fontsPreparedFor = null;
        setScreen(next);
        trimFonts = true;
    }

    /**
     * Opens the font epoch of {@code target} before it prepares itself while the current screen is
     * still shown (gameplay {@code begin}, result {@code present}), so the rasters it prewarms
     * belong to it and the switch does not open another epoch.
     */
    private void prepareFonts(Screen target) {
        ui.fonts().beginScreen();
        fontsPreparedFor = target;
    }

    /**
     * A cover cancelled before it closed never runs its onCovered callback. Once the layer is idle
     * again, finish or drop what that callback would have done, so a cancel can never leave
     * {@link #startGameplay} or {@link #showResult} stuck. Field checks only (runs every frame).
     */
    private void recoverCancelledCover() {
        if (ui.transitions().isBusy()) return;
        coverTarget = null;
        if (load != null && !load.covered) load.abandon();
        if (resultPending) {
            Gdx.app.error(TAG, "result cover cancelled; presenting without animation");
            presentResultNow();
        }
    }

    // ------------------------------------------------------------------ flow
    /** {@link #startGameplay(SongMeta, Difficulty, boolean, float, float)} focused on the centre. */
    public boolean startGameplay(SongMeta song, Difficulty diff, boolean autoplay) {
        return startGameplay(song, diff, autoplay, Ui.CX, Ui.CY);
    }

    /**
     * Dives into a chart: stops menu, preview and result music, closes a DIVE (focused on
     * (focusX, focusY)) that stays covered while the chart is parsed on a worker thread, then on
     * the GL thread calls {@code gameplay().begin(chart, song, diff, autoplay)}, switches and
     * opens. If the chart cannot be loaded the cover opens on a freshly shown map with a toast.
     *
     * <p>{@code begin} always runs while the target screen is hidden: when gameplay restarts
     * itself (pause-menu Retry calls this while the same screen is shown) Main first calls its
     * {@code hide()}, then {@code begin(...)}, then {@code show()} on the next switch.
     *
     * <p>The focus point is accepted for API symmetry; per DESIGN §3.12 the DIVE closes as two
     * panels meeting at y = 540 and does not use it (the button's sonar ping is the caller's).
     *
     * @return false when nothing started (missing chart, a load or transition already running)
     */
    public boolean startGameplay(SongMeta song, Difficulty diff, boolean autoplay, float focusX, float focusY) {
        if (song == null || diff == null || !song.has(diff)) {
            ui.toasts().show(Lang.t("THIS CHART IS NOT AVAILABLE", "这张谱面现在不能玩"), Theme.LOST);
            return false;
        }
        return beginLoad(song, diff, autoplay, false, focusX, focusY);
    }

    /**
     * Dives into the tutorial (the manifest's tutorial folder, its easiest chart) exactly like
     * {@link #startGameplay} but routed to {@link #introduction()}.
     *
     * @return false when nothing started
     */
    public boolean startTutorial() {
        SongMeta tutorial = library.tutorial();
        if (tutorial == null) {
            ui.toasts().show(Lang.t("TUTORIAL NOT FOUND", "找不到新手教程"), Theme.LOST);
            transitionTo(map());
            return false;
        }
        return beginLoad(tutorial, tutorial.easiest(), false, true, Ui.CX, Ui.CY);
    }

    /**
     * Records a finished run ({@link ProfileStore#recordPlay}, which skips autoplay and handles the
     * tutorial itself), then ascends to the result screen, handing it the result and the non-null
     * outcome while covered. If a transition is already running it presents and switches at once.
     */
    public void showResult(PlayResult r) {
        pendingResult = r;
        pendingOutcome = profiles.recordPlay(r);
        resultPending = true;
        TransitionLayer t = ui.transitions();
        t.setCoveredLabel(null);
        if (t.isBusy() || !t.start(TransitionLayer.Style.ASCENT, presentPendingResult)) presentResultNow();
    }

    /** Hands the pending result to the result screen and switches to it (once per showResult). */
    private void presentResultNow() {
        if (!resultPending) return;
        resultPending = false;
        PlayResult r = pendingResult;
        RecordOutcome o = pendingOutcome;
        pendingResult = null;
        pendingOutcome = null;
        ResultScreen target = result();
        prepareFonts(target);
        target.present(r, o);
        pendingScreen = target;
    }

    /**
     * Backdrop depth of a song's gameplay (DESIGN §2.6): the chapter's depth band at
     * SURFACE .10, SHALLOW .35, DEEP .60, ABYSS .85.
     */
    public float gameplayDepth(SongMeta song, Difficulty diff) {
        if (song == null || library.chapters().isEmpty()) return 0f;
        int ci = song.chapterIndex();
        if (ci < 0 || ci >= library.chapters().size()) return 0f;
        ChapterMeta ch = library.chapters().get(ci);
        float f = DIFFICULTY_DEPTH_F[diff == null ? 0 : diff.ordinal()];
        return ch.depthTop() + (ch.depthBottom() - ch.depthTop()) * f;
    }

    /** The diver's depth in whole metres (best-10 average, {@code profile.Depth}); 0 for new divers. */
    public float playerDepth() {
        return Depth.meters(profiles.depth());
    }

    /**
     * Starts parsing (song, diff) on the loader thread ahead of {@link #startGameplay} (MAP's
     * DESCEND at t = 0, DESIGN §3.12, while the DIVE has not started yet). A
     * {@code startGameplay} of the same chart within 5 s takes that parse instead of parsing again
     * (once; if the parse is still running, the load simply queues behind it on the same thread).
     * Anything else drops it. No-op while a chart load is pending, for an unknown chart, or when
     * the same chart is already prefetched.
     */
    public void prefetchChart(SongMeta song, Difficulty diff) {
        if (load != null || song == null || diff == null || !song.has(diff)) return;
        if (prefetch != null && prefetch.matches(song, diff) && !prefetch.expired()) return;
        prefetch = new Prefetch(song, diff);
        loaderThread().execute(prefetch);
    }

    /** Hands out the prefetched parse of (song, diff) once, or null; any other prefetch is dropped. */
    private Prefetch claimPrefetch(SongMeta song, Difficulty diff, boolean tutorial) {
        Prefetch p = prefetch;
        prefetch = null;
        return p != null && !tutorial && p.matches(song, diff) && !p.expired() ? p : null;
    }

    private boolean beginLoad(SongMeta song, Difficulty diff, boolean autoplay, boolean tutorial,
                              float focusX, float focusY) {
        TransitionLayer t = ui.transitions();
        if (load != null || t.isBusy()) return false;
        ChartLoad job = new ChartLoad(song, diff, autoplay, tutorial);
        job.token = t.holdOpen();                       // pre-armed: applies to the DIVE below
        t.setCoveredLabel(null);
        if (!t.start(TransitionLayer.Style.DIVE, focusX, focusY, job)) {
            t.releaseOpen(job.token);
            return false;
        }
        load = job;
        job.source = claimPrefetch(song, diff, tutorial);
        audio.stopSlot(AudioService.Slot.MENU, 0.4f);
        audio.stopPreview(0.4f);
        audio.stopSlot(AudioService.Slot.RESULT, 0.3f);
        loaderThread().execute(job.parse);
        return true;
    }

    /**
     * Loader thread, after a parse: computes the song's 16×16 jacket wash (decode + downscale) so
     * gameplay and the result only upload it (DESIGN §2.7, §6.2).
     */
    private void prepareWash(SongMeta song, Chart chart) {
        FileHandle jacket = song != null && song.jacket() != null ? song.jacket() : chart.jacketFile();
        try {
            library.prepareWash(jacket);
        } catch (RuntimeException e) {
            Gdx.app.error(TAG, "jacket wash: " + e.getMessage()); // never fails the load; begin() retries
        }
    }

    private ExecutorService loaderThread() {
        if (loader == null) {
            loader = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread th = new Thread(r, "kk-chart-loader");
                    th.setDaemon(true);
                    return th;
                }
            });
        }
        return loader;
    }

    /**
     * A chart parsed ahead of time on the loader thread ({@link #prefetchChart}). It posts nothing
     * back: the load that claims it runs on the same single loader thread after it, so it simply
     * reads the result there.
     */
    private final class Prefetch implements Runnable {
        final SongMeta song;
        final Difficulty diff;
        final long startedNanos = System.nanoTime();
        volatile Chart chart;
        volatile Throwable error;

        Prefetch(SongMeta song, Difficulty diff) {
            this.song = song;
            this.diff = diff;
        }

        @Override
        public void run() {
            try {
                chart = library.loadChart(song, diff);
                prepareWash(song, chart);
            } catch (Throwable e) {
                error = e;
            }
        }

        boolean matches(SongMeta s, Difficulty d) {
            return s != null && d == diff && s.id().equals(song.id());
        }

        boolean expired() {
            return System.nanoTime() - startedNanos > PREFETCH_TTL_NANOS;
        }
    }

    /**
     * One chart load: parsed on the loader thread, finished on the GL thread once both the
     * parse has completed and the DIVE has covered the screen (in either order).
     */
    private final class ChartLoad implements Runnable {
        final SongMeta song;
        final Difficulty diff;
        final boolean autoplay, tutorial;
        int token;
        boolean covered, parsed, finished;
        /**
         * The claimed prefetch of this chart, or null. Set before {@link #parse} is queued; the
         * loader is one thread, so the prefetch has finished by the time {@link #parse} runs.
         */
        Prefetch source;
        volatile Chart chart;
        volatile Throwable error;

        /** Worker part: parse (or take the prefetched parse), then hop back to the GL thread. */
        final Runnable parse = new Runnable() {
            @Override
            public void run() {
                try {
                    Prefetch p = source;
                    if (p != null) {
                        chart = p.chart;
                        error = p.error;
                    } else {
                        chart = tutorial ? library.loadTutorialChart() : library.loadChart(song, diff);
                        prepareWash(song, chart);
                    }
                } catch (Throwable e) {
                    error = e;
                }
                Gdx.app.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        parsed = true;
                        tryFinish();
                    }
                });
            }
        };

        ChartLoad(SongMeta song, Difficulty diff, boolean autoplay, boolean tutorial) {
            this.song = song;
            this.diff = diff;
            this.autoplay = autoplay;
            this.tutorial = tutorial;
        }

        /** The DIVE's onCovered callback (GL thread). */
        @Override
        public void run() {
            covered = true;
            tryFinish();
        }

        /**
         * The DIVE was cancelled before it covered (GL thread): drops the parse result whenever it
         * arrives and re-shows the current screen, so it restarts the music {@link #beginLoad}
         * faded out. The cancelled transition already discarded the hold token.
         */
        void abandon() {
            if (finished) return;
            finished = true;
            if (load == this) load = null;
            Gdx.app.error(TAG, "DIVE cancelled before it covered; load of " + song.id() + " " + diff + " dropped");
            pendingScreen = screen != null ? screen : map();
        }

        private void tryFinish() {
            if (finished || !covered || !parsed || disposed) return;
            finished = true;
            if (load == this) load = null;
            Chart c = chart;
            if (c != null && error == null) {
                try {
                    GameplayScreen target = tutorial ? introduction() : gameplay();
                    if (screen == target) {
                        // Self-restart (pause-menu Retry): hide first so begin() never runs on a
                        // shown screen; the switch below shows it again. Covered, so no blank frame.
                        screen = null;
                        target.hide();
                    }
                    prepareFonts(target);
                    target.begin(c, song, diff, autoplay);
                    pendingScreen = target;
                } catch (RuntimeException e) {
                    error = e;
                }
            }
            if (error != null || c == null) {
                if (error != null) Gdx.app.error(TAG, "cannot start " + song.id() + " " + diff, error);
                else Gdx.app.error(TAG, "cannot start " + song.id() + " " + diff + ": no chart");
                ui.toasts().show(Lang.t("COULDN'T LOAD THIS CHART", "这张谱面加载失败"), Theme.LOST);
                // Always (re)show the map, even when it is the current screen: its show() restarts
                // the menu music that beginLoad faded out.
                pendingScreen = map();
            }
            ui.transitions().releaseOpen(token);
        }
    }

    // ------------------------------------------------------------------ services
    /** The song catalogue (headers loaded at startup). */
    public SongLibrary library() {
        return library;
    }

    /** Local profiles, device settings and records. */
    public ProfileStore profiles() {
        return profiles;
    }

    /** Menu / preview / result music slots and the reserved hit sound. */
    public AudioService audio() {
        return audio;
    }

    /** Shared hit-effect facade (gameplay updates and draws it). */
    public HitFx hitFx() {
        return hitFx;
    }

    @Override
    public Fonts fonts() {
        return ui.fonts();
    }

    @Override
    public Draw draw() {
        return ui.draw();
    }

    @Override
    public Backdrop backdrop() {
        return ui.backdrop();
    }

    @Override
    public TransitionLayer transitions() {
        return ui.transitions();
    }

    @Override
    public GlitchText glitch() {
        return ui.glitch();
    }

    @Override
    public Toast toasts() {
        return ui.toasts();
    }

    @Override
    public Avatar avatars() {
        return ui.avatars();
    }

    @Override
    public ArtCache art() {
        return ui.art();
    }

    // ------------------------------------------------------------------ screens (lazy, cached)
    /** Title screen. */
    public WelcomeScreen welcome() {
        if (welcome == null) welcome = new WelcomeScreen(this);
        return welcome;
    }

    /** Song select. */
    public MapScreen map() {
        if (map == null) map = new MapScreen(this);
        return map;
    }

    /** Gameplay (call {@code begin} via {@link #startGameplay}). */
    public GameplayScreen gameplay() {
        if (gameplay == null) gameplay = new GameplayScreen(this);
        return gameplay;
    }

    /** Tutorial gameplay (via {@link #startTutorial}). */
    public IntroductionScreen introduction() {
        if (introduction == null) introduction = new IntroductionScreen(this);
        return introduction;
    }

    /** Result ("dive log"; via {@link #showResult}). */
    public ResultScreen result() {
        if (result == null) result = new ResultScreen(this);
        return result;
    }

    /** Profiles. */
    public ProfileScreen profile() {
        if (profile == null) profile = new ProfileScreen(this);
        return profile;
    }

    /** Settings. */
    public SettingsScreen settings() {
        if (settings == null) settings = new SettingsScreen(this);
        return settings;
    }

    /** Audio offset calibration. */
    public CalibrationScreen calibration() {
        if (calibration == null) calibration = new CalibrationScreen(this);
        return calibration;
    }

    /** GAMEPLAY VISUALS (Settings → VISUAL → ADJUST…). */
    public VisualsScreen visuals() {
        if (visuals == null) visuals = new VisualsScreen(this);
        return visuals;
    }

    /** First-run onboarding. */
    public FirstRunScreen firstRun() {
        if (firstRun == null) firstRun = new FirstRunScreen(this);
        return firstRun;
    }

    private static void disposeScreen(Screen s) {
        if (s == null) return;
        try {
            s.dispose();
        } catch (RuntimeException e) {
            Gdx.app.error(TAG, "dispose failed: " + s.getClass().getSimpleName(), e);
        }
    }
}
