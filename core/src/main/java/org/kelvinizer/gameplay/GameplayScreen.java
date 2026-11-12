package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import org.kelvinizer.Main;
import org.kelvinizer.Params;
import org.kelvinizer.audio.SongClock;
import org.kelvinizer.elements.Channels;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.play.PlayResult;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.KkScreen;
import org.kelvinizer.ui.Pressable;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;
import space.earlygrey.shapedrawer.JoinType;
import space.earlygrey.shapedrawer.ShapeDrawer;

import java.util.Arrays;


/**
 * 游玩画面：每次触摸先交给 {@link GameEngine} 判定，再交给暂停控件；画出场地、潮水、残影、音符、打击特效和 HUD。
 * 也负责开场、倒计时、暂停菜单和结束后转到结算；新手教程 {@link IntroductionScreen} 继承它。
 *
 * <p>Gameplay screen (ARCHITECTURE §10, DESIGN §5): {@link #begin} is called by
 * {@code Main.startGameplay} while the DIVE covers the (hidden) screen; {@link #show()} follows.
 * The run is driven by a {@link GameEngine}; 1.5 s after both the last note and the music have
 * ended ({@link GameEngine#finishAtMs()}) the result goes to {@code game.showResult(result)}.
 *
 * <p><b>Drawing</b> follows the blend blocks of DESIGN §5.1 in one batch pass:
 * backdrop (shader, art, jacket wash) → normal block A (zone hint, full-height lane separators, tide wash,
 * meter frame, glint) → HUD-under text (combo, judgement word) → normal block B (line cores, lane ticks,
 * TIDE STREAM bands, afterimage copies, notes) → one additive block (line glows, approach glows,
 * flashes, note glows, lane columns, touch feedback, hit FX) → HUD-over (tutorial captions,
 * countdowns, end banner) → the top layer: pause scrim, the corner HUD (Kelvin 2026-10-12:
 * drawn last, so lane glows and held bodies never cover it), the pause menu's buttons, and all of
 * their text in one recorded pass. Playfield effects run on "run time", which stops while paused.
 *
 * <p><b>Input</b> (every touch acts on touchDown, ARCHITECTURE §0.8 exception): the engine sees
 * every touch first with its event timestamp, so a touch inside a judgement zone always judges,
 * also under the HUD corners. Then the {@link PauseControl} sees it: by default two touch-downs on
 * the top-right control within 300 ms pause (the first plays a hint); a touch that judged a note,
 * re-grabbed a hold or covers a running TIDE STREAM does not count and does not cancel a pending
 * first tap. The hit sound plays inside the input handler. BACK / Esc, the keyboard's Pause key and
 * the app going to the background pause instantly; BACK / Esc (or RESUME, or Enter) while paused
 * resume with a 3-2-1 countdown, a second a digit as at the start (Kelvin 2026-10-21 16:45), during which notes stay
 * frozen and nothing is judged. On Android the music runs through it unheard (Kelvin 2026-10-21 15:09): it plays
 * muted from the countdown's length before the pause and sounds at full volume as the count ends at the paused time,
 * already in step with the notes. The chart fades back in through the count, from a twentieth of its opacity as it
 * starts to its own as it ends (Kelvin 2026-10-21 15:40, 16:10).
 *
 * <p><b>Start</b> (DESIGN §5.11, Kelvin 2026-10-12 16:06): when the DIVE cover opens (run time
 * t = 0) a 3.3 s pre-roll runs on wall time while the song clock waits: the 3-2-1 countdown at
 * one second per digit, then 0.3 s with nothing but the stage. At t = 3.3 the engine starts its
 * clock at the start time {@link GameEngine#leadInMs()} (0, or just before the first note can be
 * drawn); the music starts when the clock crosses 0. Nothing is judged before that. A pause
 * during the pre-roll restarts it from the 3.
 *
 * <p><b>Stretch</b> (Kelvin 2026-10-12 16:09 / 16:17): the playfield fills the screen at every aspect.
 * {@link #begin} and every frame fit {@link Field} to the visible world; a change (a desktop resize, also
 * during the pre-roll or a pause) makes the engine re-evaluate its frame geometry, and the edge entries
 * follow on the next frame. The corner HUD stays pinned to the corners, unstretched.
 */
public class GameplayScreen extends KkScreen<Main> {
    /** Length of each resume countdown step (s): one second, as at the start (Kelvin 2026-10-21 16:45). */
    public static final float COUNT_STEP_SEC = 1f;
    /** On Android the music runs muted through the resume countdown from this long before the paused time (ms). */
    private static final float COUNT_IN_MS = 3f * COUNT_STEP_SEC * 1000f;
    /** Autoplay's hit sounds are handed to a timed output this far ahead, so each is heard on its note. */
    private static final double AUTO_HIT_AHEAD_MS = 150.0;
    /** The chart's opacity factor as the resume countdown starts; it rises evenly to 1 as the count ends. */
    public static final float COUNT_FADE_FROM = 0.05f;
    private static final float ABYSS_PULSE_SEC = 0.40f;
    /** Judging hint ({@link #drawJudgeHint}): chevron arm, stroke, gap from the centre and slide (world units). */
    private static final float HINT_ARM = 56f, HINT_STROKE = 12f, HINT_GAP = 120f, HINT_SLIDE = 80f;
    private static final float JACKET_WASH_ALPHA = 0.12f;
    private static final float ART_ALPHA = 0.15f;

    /** The chart in play (set by {@link #begin}). */
    protected Chart chart;
    /** The song in play. */
    protected SongMeta song;
    /** The difficulty in play. */
    protected Difficulty difficulty;
    /** True for an autoplay run (never recorded). */
    protected boolean autoplay;
    /** Song times (ms, ascending) autoplay sounds a hit at, when the output can time them; else null. */
    private double[] autoHitMs;
    /** The next of {@link #autoHitMs} to hand to the output. */
    private int autoHitNext;
    /** Per note: a DRAG caught early whose hit sound is already handed to the output for its time. */
    private boolean[] dragSound = new boolean[0];
    /** Seconds since {@link #begin} (screen time, also while paused). */
    protected float time;
    /** Seconds the run has been unpaused since it started (playfield effects; frozen while paused). */
    protected float runTime;
    /** Run seconds since the DIVE cover opened (DESIGN §5.11 start sequence clock t; frozen while paused). */
    protected float introT;
    /** The gameplay model: clock, input, judging, scoring. */
    protected final GameEngine engine = new GameEngine();
    /** Active settings of the run (read at {@link #begin}). */
    protected Settings settings;

    private final GameEvent ev = new GameEvent();
    private final Settings defaults = new Settings();
    private final FieldRenderer field;
    private final NoteRenderer notes;
    private final TrailRenderer trails;
    private final FeedbackLayer feedback;
    /** The TIDE hit effect (DESIGN §19.2), block A. */
    private final TideWash tideWash;
    /** The chart's view (3D camera, 2026-11-07): the field through it while it is not the default one. */
    private final View3D view = new View3D();
    /** The press being handled's ray for keys on a sea ({@link #seaRay}): its x at depth 0, x per unit deeper. */
    private final float[] seaPt = new float[2];
    /** Where the press being handled lands on the field ({@link #toField}). */
    private final Vector2 fieldPt = new Vector2();
    /** SHOW TOUCHES: a ring under every finger (Kelvin 2026-10-18 19:52); a veiled finger's glitch mark. */
    private final TouchMarks marks;
    /** The veils over the field and notes, and the glitch mark of a veiled finger (CHART_FORMAT_v4 §6.10). */
    private final VeilRenderer veilFx;
    /** The curtains (水帘): blur everything beneath the pause menu, never judged (CHART_FORMAT §6.1). */
    private final VeilRenderer curtainFx;
    /** A chart format v7 chart's veils and curtains as they show this frame: drawn and judged on the screen. */
    private final Volumes7 volumes = new Volumes7();
    private final GameplayHud hud;
    /** Records HUD-under text (and other text passes) so each font page is one draw call. */
    private final DeferredBatch text = new DeferredBatch();
    private final RunOverlay overlay;
    private final PauseMenu menu;
    /** The top-right pause control (owned by the HUD, which draws it). */
    private final PauseControl pauseControl;

    private boolean started;
    /**
     * Seconds into the start pre-roll (DESIGN §5.11), or −1 outside it (before the cover opens, and once the clock
     * runs).
     */
    private float preroll = -1f;
    private boolean resultSent;
    private int[] holdFx = new int[0];
    /** The base point of the STREAM last asked ({@link #holdBase}). */
    private final float[] basePt = new float[2];
    /** Where {@link #sinkFx} puts a hit effect; where a contact ring on a turned lane shows ({@link #contact}). */
    private final float[] fxPt = new float[2], ringPt = new float[3];
    private final float[] hintPath = new float[6];
    /** The faint dot where a touch hits no zone: the tutorial's (players' SHOW TOUCHES draws {@link #marks}). */
    private boolean strayDots;
    private boolean showTouches;
    private float lastBeatFloor = Float.NaN;
    /** Frame of the last mine sound: stacked mines touched together play it once. */
    private long mineSoundFrame = -1L;
    private float abyssT = -10f;

    // pause state (field names read by the screenshot tests)
    private boolean menuOpen;
    private float countdown = -1f;
    /** True while the music runs in through the countdown ({@link GameEngine#startCountIn}): it times the digits. */
    private boolean countIn;
    /** Fades the chart through the resume countdown. */
    private final FadeShader fade = new FadeShader();
    /** The picture drawn smaller and stretched while the frame rate stays low (Kelvin 2026-11-10 01:34). */
    private final RenderScale renderScale = new RenderScale();
    /** How the last pause opened: "tap" (pause control), "key" (BACK / Esc / Pause) or "app". */
    @SuppressWarnings("unused")
    private String pausedBy = "";
    @SuppressWarnings("unused")
    private final Pressable resume;

    // backdrop hooks: the wash is the library's (shared with the result), the song bg is ours,
    // ChartVisualizer.png is held from the kit's ArtCache until dispose
    private static final String DEFAULT_ART = "ChartVisualizer.png";
    private Texture wash;
    private Texture art;
    private Texture defaultArt;
    private boolean defaultArtTried;
    private String constText = "";

    /** Creates the screen once (Main caches it). */
    public GameplayScreen(Main game) {
        super(game);
        settings = defaults;
        field = new FieldRenderer(game.draw());
        notes = new NoteRenderer(game.draw());
        trails = new TrailRenderer(notes);
        feedback = new FeedbackLayer(game.draw());
        tideWash = new TideWash(game.draw(), field);
        marks = new TouchMarks(game.draw());
        veilFx = new VeilRenderer(game.draw());
        curtainFx = new VeilRenderer(game.draw(), true);
        hud = new GameplayHud(game.fonts(), game.draw(), game.glitch(), feedback);
        pauseControl = hud.pause();
        overlay = new RunOverlay(game.fonts(), game.draw(), game.glitch());
        menu = new PauseMenu(game, new Runnable() {
            @Override
            public void run() {
                startCountdown();
            }
        }, new Runnable() {
            @Override
            public void run() {
                retryRun();
            }
        }, new Runnable() {
            @Override
            public void run() {
                quitRun();
            }
        });
        resume = menu.resumeButton();
    }

    /**
     * Resets the screen for a new run without reallocating GL objects other than the per-song
     * jacket wash and art. Runs on the GL thread while the screen is covered and hidden (Main's
     * contract); loads the music and prepares the clock at the lead-in. The run starts when the
     * cover has opened.
     */
    public void begin(Chart chart, SongMeta song, Difficulty diff, boolean autoplay) {
        this.chart = chart;
        this.song = song;
        this.difficulty = diff;
        this.autoplay = autoplay;
        time = 0f;
        runTime = 0f;
        introT = 0f;
        started = false;
        preroll = -1f;
        resultSent = false;
        menuOpen = false;
        menu.close();
        countdown = -1f;
        countIn = false;
        pausedBy = "";
        lastBeatFloor = Float.NaN;
        abyssT = -10f;
        Profile p = game.profiles() != null ? game.profiles().active() : null;
        settings = p != null && p.settings != null ? p.settings : defaults;
        // SHOW TOUCHES is a plain switch since 2026-10-18 04:06 (the old AUTO reads as off) and a ring under every
        // finger since 19:52; the tutorial keeps its stray-touch dots
        showTouches = settings.strayTouches == Settings.STRAY_ON;
        strayDots = tutorial();
        marks.clear();
        if (holdFx.length < chart.noteCount()) holdFx = new int[chart.noteCount()];
        java.util.Arrays.fill(holdFx, 0, chart.noteCount(), -1);
        if (dragSound.length < chart.noteCount()) dragSound = new boolean[chart.noteCount()];
        java.util.Arrays.fill(dragSound, 0, chart.noteCount(), false);

        FileHandle music = song != null && song.music() != null ? song.music() : chart.musicFile();
        float dur = song != null ? song.durationMs() : 0f;
        engine.clock().setMusicOutput(game.audio() != null ? game.audio().musicOutput() : null);
        if (dur > 0f) engine.clock().load(music, dur);
        else engine.clock().load(music);
        engine.clock().setVolume(game.audio() != null ? game.audio().musicVolume() : 0.5f);
        // decode and prime the optional hit / hold-end / mine sounds while covered (once per
        // session), so the first judgement never waits for a load
        if (game.audio() != null) game.audio().hitSound();
        applyOffsets();
        // the playfield fills the screen (Kelvin 16:17): fit the stretch before the engine and the edge entries use it
        Field.fit(Ui.worldW(), Ui.worldH());
        engine.load(chart, autoplay);
        autoHitMs = autoplay && game.audio() != null && game.audio().timesHits() ? hitTimes(chart) : null;
        autoHitNext = 0;
        game.hitFx().clear();

        String title = song != null ? song.title() : chart.title();
        constText = song != null ? song.constantText(diff) : chart.chartConstant();
        if (constText == null) constText = "";
        field.begin(chart);
        // compile the veil / curtain shaders and make the capture texture now, while the cover is still closed:
        // doing it in the first drawn frame stalls long enough to let the song clock run past the first note
        veilFx.prewarm();
        curtainFx.prewarm();
        // the note size sets how far a turned body reaches, so which notes slide in (DESIGN §5.4)
        notes.setStyle(settings.noteSizeScale, game.hitFx().quality() == HitFx.QUALITY_HIGH);
        engine.layout().setKeyScale(notes.noteSize()); // the lanes' bases at the key size
        notes.setHitPoint(settings.hitPointPx);
        notes.setSyncHint(settings.syncHint);
        notes.begin(chart);
        startBeforeFirstNote();
        trails.begin(chart);
        feedback.clear();
        tideWash.clear();
        hud.begin(title, diff, constText, autoplay, settings);
        menu.prewarm();
        overlay.begin(autoplay, !tutorial());
        // the end banner waits 1 s after the music and stays 2 s, then the result (Kelvin 2026-10-17)
        engine.setFinishDelayMs(tutorial() ? Params.FINISH_DELAY_MS : Params.BANNER_FINISH_DELAY_MS);
        loadBackdropArt();
    }

    /**
     * The song clock starts before the first note can be drawn (DESIGN §5.11), so the first frame
     * of the game shows nothing of any note: the engine starts at 0 or just before the first
     * visibleFrom, and the screen moves that start before the earliest slide in from the screen
     * edge (DESIGN §5.4, {@link EdgeEntry}, trails included) and the frame's offsets, and before the first judging hint
     * ({@link #firstHintBeat}). The engine knows nothing of the screen, so the screen moves its start, never later.
     */
    private void startBeforeFirstNote() {
        float beat = Math.min(notes.entry().firstDrawBeat(), firstHintBeat());
        if (beat == Float.POSITIVE_INFINITY) return;
        // the first frame draws at visual time lead-in − audio offset + visual offset
        double ms = chart.beatToMs(beat) + Math.min(0f, engine.audioOffsetMs() - engine.visualOffsetMs());
        if (ms <= engine.leadInMs()) engine.setLeadInMs((float) Math.floor(ms) - GameEngine.START_EPS_MS);
    }

    /**
     * Jacket wash (16×16, linear) and the image hook (song bg, else ChartVisualizer.png), DESIGN
     * §2.7–§2.8. The wash comes from {@link org.kelvinizer.library.SongLibrary#wash(FileHandle)}:
     * Main's chart loader already computed it off the render thread, the result screen reuses it.
     */
    private void loadBackdropArt() {
        disposeSongArt();
        FileHandle jacket = song != null && song.jacket() != null ? song.jacket() : chart.jacketFile();
        wash = jacket != null && game.library() != null ? game.library().wash(jacket) : null;
        if (!settings.backgroundArt) return;
        FileHandle bg = song != null ? song.background() : null;
        if (bg != null && bg.exists()) {
            try {
                art = new Texture(bg);
                art.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            } catch (Exception e) {
                art = null;
            }
        }
    }

    private Texture imageHook() {
        if (!settings.backgroundArt) return null;
        if (art != null) return art;
        if (!defaultArtTried) {
            defaultArtTried = true;
            defaultArt = game.art().acquire(DEFAULT_ART);
        }
        return defaultArt;
    }

    private void disposeSongArt() {
        wash = null; // the library's
        if (art != null) art.dispose();
        art = null;
    }

    /** True for the tutorial run ({@link IntroductionScreen}). */
    protected boolean tutorial() {
        return false;
    }

    /** @return the engine (tests, tutorial). */
    public GameEngine engine() {
        return engine;
    }

    /** Text recorder for HUD-over passes of subclasses (start / replay within one pass). */
    DeferredBatch textBatch() {
        return text;
    }

    /** @return the HUD (tutorial tour anchors). */
    GameplayHud hud() {
        return hud;
    }

    /** @return true while the pause menu is open. */
    public boolean isMenuOpen() {
        return menuOpen;
    }

    /** @return seconds into the start pre-roll (DESIGN §5.11), or −1 outside it. */
    public float preroll() {
        return preroll;
    }

    private void applyOffsets() {
        DeviceSettings d = game.profiles() != null ? game.profiles().device() : null;
        // no visual offset since 2026-10-18 (Kelvin: it overlapped the audio offset)
        engine.setOffsets(d != null ? d.audioOffsetMs() : 0f, 0f);
        engine.clock().setStartLeadMs(d != null ? d.musicStartLatencyMs : 0f);
    }

    /**
     * Keeps the music start latency the clock measured (averaged with the stored one), so the next
     * song starts its music that much early and the first notes do not hitch.
     */
    private void recordStartLatency() {
        float m = engine.clock().measuredStartLatencyMs();
        DeviceSettings d = game.profiles() != null ? game.profiles().device() : null;
        if (d == null || Float.isNaN(m)) return;
        float v = d.musicStartLatencyMs > 0f ? 0.5f * (d.musicStartLatencyMs + m) : m;
        if (Math.abs(v - d.musicStartLatencyMs) < 0.5f) return;
        d.musicStartLatencyMs = v;
        game.profiles().markDirty();
    }

    @Override
    protected void onShow() {
        game.guardEdges(true); // a system edge swipe would cancel every finger on the screen (Kelvin 2026-11-06)
        Backdrop b = game.backdrop();
        b.setMode(Backdrop.Mode.GAMEPLAY);
        b.clearFocus();
        b.setDepth(game.gameplayDepth(song, difficulty));
        b.setDim(settings.backgroundDim);
        b.setWash(wash, JACKET_WASH_ALPHA, song != null ? song.jacket16x9() : null);
        Texture img = imageHook();
        b.setImage(img, img != null ? ART_ALPHA * MathUtils.clamp(settings.artOpacity, 0f, 1f) : 0f);
        b.setComboLife(0);
    }

    @Override
    protected void onHide() {
        game.guardEdges(false);
        engine.pause();
        dropQueuedHits();
        recordStartLatency();
        engine.clock().dispose(); // release the music; begin() loads it again
        Backdrop b = game.backdrop();
        b.setMode(Backdrop.Mode.MENU);
        b.setWash(null, 0f);
        b.setImage(null, 0f);
        b.setComboLife(0);
        game.hitFx().clear();
        menuOpen = false;
        menu.close();
        countdown = -1f;
        countIn = false;
        pauseControl.reset();
    }

    @Override
    public void pause() {
        openPause("app"); // app to background: pause instantly (Main already paused the audio)
    }

    @Override
    public void dispose() {
        engine.clock().dispose();
        fade.dispose();
        renderScale.dispose();
        veilFx.dispose();
        curtainFx.dispose();
        disposeSongArt();
        view.dispose();
        if (defaultArt != null) game.art().release(DEFAULT_ART);
        defaultArt = null;
    }

    // ================================================================== frame

    @Override
    protected void draw(float delta) {
        time += delta;
        if (chart == null) return;
        // a resize stretches the field anew; the engine re-evaluates its frame geometry even while it waits
        if (Field.fit(Ui.worldW(), Ui.worldH())) engine.refreshGeometry();
        if (!started && !inputLocked() && !menuOpen) {
            started = true; // t = 0: the pre-roll starts, the song clock waits (DESIGN §5.11)
            preroll = 0f;
        }
        if (pauseControl.update(delta, engine, settings.pauseMode) == PauseControl.PAUSE && pauseEnabled()) {
            openPause("tap");
        }
        if (countdown >= 0f) {
            if (!countIn) {
                countdown -= delta;
            } else {
                // the digits follow the music running in: the count ends as it reaches the paused time
                double left = engine.clock().countInLeftMs();
                countdown = left > 0.0 ? Math.min(countdown, (float) (left / 1000.0)) : -1f;
            }
            if (countdown < 0f) {
                countdown = -1f;
                countIn = false;
                engine.resume();
            }
        }
        if (preroll >= 0f && !menuOpen) {
            preroll += delta;
            // the float sum of frame steps can land a hair short of 3.3: count it as there
            if (preroll >= RunOverlay.PREROLL_SEC - 1e-4f) {
                preroll = -1f;
                engine.start();
            }
        }
        engine.update();
        queueAutoHits();
        float runDelta = started && !menuOpen && !engine.isPaused() ? delta : 0f;
        runTime += runDelta;
        if (started) introT += runDelta;
        consumeEvents();
        marks.update(engine, time);
        HitFx fx = game.hitFx();
        moveHoldFx(); // the held streams' births this update start at their base as drawn now
        float vb = engine.visualBeat(); // held ripples every 1/4 beat at the tempo now (Kelvin 2026-10-31 15:03)
        fx.setBeatLength((float) chart.msBetween(vb, vb + 0.25) * 0.004f);
        fx.update(runDelta);
        tideWash.update(runDelta, engine, runTime);
        field.update(runDelta, engine);
        hud.update(runDelta, engine, runTime);
        updateBeat();
        if (engine.isFinished() && !resultSent) {
            resultSent = true;
            game.showResult(buildResult());
        }
        menu.update(delta);
        updateExtras(delta);

        int combo = engine.scores().combo();
        game.backdrop().setComboLife(combo);

        // drawn smaller and stretched while the frame rate stays low (Kelvin 2026-11-10 01:34, weaker phones)
        renderScale.update(runDelta > 0f);
        boolean small = renderScale.begin();
        batch.begin();
        game.backdrop().render(batch, viewport, runDelta);
        // culling + approach state first: a TIDE approach brightens the zone hint of block A
        field.clearApproach();
        notes.setStyle(settings.noteSizeScale, fx.quality() == HitFx.QUALITY_HIGH);
        engine.layout().setKeyScale(notes.noteSize()); // the lanes' bases at the key size
        notes.setHitPoint(settings.hitPointPx);
        notes.setSyncHint(settings.syncHint);
        notes.prepare(engine, field, settings.approachGlow);
        trails.setQuality(fx.quality());
        tideWash.setStyle(settings.reduceFlashing, fx.quality());
        trails.prepare(engine, tideWash.quadsBound()); // block A may share block B's flush
        // ---- normal block A
        drawAbyssPressure();
        // the chart fades back in through the resume countdown (Kelvin 2026-10-21 15:40)
        float fadeA = countdown >= 0f ? countFade(countdown) : 1f;
        // the view (2026-11-07): in 3D (finite lanes always, else off the default) what lies behind the flat picture
        // first, then the field through it
        boolean v3 = view.update(chart, engine.visualBeat(), camera);
        engine.setSolid(v3); // a turned sea is then drawn level and turned in 3D (Kelvin 2026-11-09 00:13)
        tideWash.setSpace(v3 ? view : null);
        if (v3) {
            batch.end();
            view.drawBack(engine, fadeA * MathUtils.clamp(introT / 0.35f, 0f, 1f)); // with the lanes' intro
            view.drawSeas(engine, fadeA, NoteShapes.songSeconds(engine));
            notes.collectBoxes(engine, runTime, view);
            view.drawBoxes(engine.layout(), fadeA, NoteShapes.songSeconds(engine));
            batch.setProjectionMatrix(view.combined());
            batch.begin();
            game.draw().batch().beginSpace(); // each lane's frame without a draw call (Kelvin 2026-11-10 01:19)
        }
        boolean faded = fadeA < 1f && fade.begin(batch, fadeA);
        field.setSpace(v3 ? view : null); // moved columns' depth (Kelvin 2026-11-07 22:54)
        field.drawUnder(engine, zoneStrength(), MathUtils.clamp(introT / 0.35f, 0f, 1f));
        if (!v3) field.drawSeas(engine, NoteShapes.songSeconds(engine)); // in 3D drawn behind, with the columns
        tideWash.draw(engine);  // above the lane field, beneath notes, judgement word and HUD
        drawJudgeHint();
        if (faded) fade.end(batch);
        // the veils cover the field and the notes (block B); the HUD beneath the notes stays above the most
        // prominent one: drawn outside it here, inside it again after the veils (CHART_FORMAT_v4 §6.10)
        veilFx.setSpace(v3 ? view : null); // their depth through the view (Kelvin 2026-11-07 22:54)
        // chart format v7: boxes and ellipsoids, worked out through the view and drawn on the screen
        volumes.update(chart, engine.visualBeat(), v3 ? view : null);
        boolean seven = chart != null && !chart.volumes7().isEmpty();
        boolean veiled = seven ? veilFx.prepare7(volumes, runTime, fadeA)
            : veilFx.prepare(engine.veils(), engine.visualBeat(), runTime, fadeA);
        boolean curtained = seven ? curtainFx.prepare7(volumes, runTime, fadeA)
            : curtainFx.prepare(engine.curtains(), engine.visualBeat(), runTime, fadeA);
        // in 3D the veils lie on the field, the HUD stays on the screen; v7 volumes are on the screen already
        veilFx.setView(v3 && !seven ? view.combined() : null);
        if (v3) flat();
        boolean clipped = veiled && veilFx.clipBegin(batch, false);
        hud.drawUnder(batch, introT, runTime, !menuOpen);
        // ---- HUD-under text (recorded, then one draw call per font page)
        text.start(batch);
        hud.setCentreCover(centreCover());
        hud.drawUnderText(text, engine, runTime, !menuOpen && countdown < 0f);
        feedback.drawText(text, game.fonts(), runTime);
        if (clipped) {
            text.replayKeep();
            veilFx.clipEnd(batch);
        } else {
            text.replay();
        }
        // ---- normal block B
        if (v3) deep();
        faded = fadeA < 1f && fade.begin(batch, fadeA);
        field.drawLines(engine, runTime, introT);
        notes.drawBands(engine, runTime); // TIDE STREAM bands beneath trails, lane notes and mines
        trails.setSpace(v3 ? view : null);
        trails.draw(engine);   // afterimages beneath every live note (ARCHITECTURE §17)
        notes.draw(engine, runTime);
        if (faded) fade.end(batch);
        if (veiled) drawVeils(clipped, v3, seven, fadeA);
        if (v3) flat();
        overlay.drawBannerScrim(engine.judgeMs(), engine.endMs());
        // ---- additive block (one blend switch)
        int src = batch.getBlendSrcFunc();
        int dst = batch.getBlendDstFunc();
        int srcA = batch.getBlendSrcFuncAlpha();
        int dstA = batch.getBlendDstFuncAlpha();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        if (v3) deep();
        faded = fadeA < 1f && fade.begin(batch, fadeA);
        field.drawAdditive(engine, runTime, combo, beatPulse());
        notes.drawGlows();
        if (faded) fade.end(batch);
        if (v3) flat();
        feedback.drawAdditive(runTime);
        overlay.drawBurst(batch, engine.judgeMs(), engine.endMs());
        // in 3D the hit effects are spheres round the keys, drawn facing the screen (Kelvin 2026-11-07 17:04)
        fx.setView(v3 ? view : null);
        faded = fadeA < 1f && fade.begin(batch, fadeA); // the hit effects the pause froze fade with the chart
        fx.draw(batch);
        if (faded) fade.end(batch);
        batch.setBlendFunctionSeparate(src, dst, srcA, dstA);
        if (small) { // the smaller picture stretched over the screen; the touch marks and the HUD over it as before
            batch.end();
            renderScale.end(batch);
            batch.begin();
        }
        marks.draw(batch, veilFx, time, showTouches, glitchMarkRadius()); // over the notes and effects, under the HUD
        // ---- HUD-over
        drawExtras(delta);
        if (countdown >= 0f) overlay.drawResumeCountdown(batch, countdown, time);
        else if (!menuOpen) overlay.drawStartCountdown(batch, preroll, runTime);
        overlay.drawEndBanner(batch, engine.judgeMs(), engine.endMs(), engine.scores(), runTime,
            game.backdrop().sonar());
        // ---- top layer: pause scrim, corner HUD (drawn last), pause buttons; then all their text
        menu.drawScrim(batch);
        hud.drawCorners(batch, engine, introT, runTime, menuOpen, menuOpen ? 0f : pauseControl.holdProgress());
        if (curtained) { // the curtains blur everything under them but the pause menu (Kelvin 2026-10-31)
            text.start(batch);
            hud.drawCornerText(text, introT);
            text.replay();
            curtainFx.draw(batch);
        }
        menu.drawShapes(batch);
        text.start(batch);
        if (!curtained) hud.drawCornerText(text, introT);
        menu.drawText(text);
        text.replay();
        batch.end();
    }

    /**
     * Draws the veils over block B, leaving the judgement line strokes crisp, then the HUD beneath the notes
     * inside the veil it was kept out of ({@code clipped}); in 3D ({@code v3}) the veils through the view, the HUD
     * on the screen as before them. A chart format v7 chart's ({@code seven}) are drawn on the screen, then their
     * edges in 3D ({@link View3D#drawVolumes}).
     */
    private void drawVeils(boolean clipped, boolean v3, boolean seven, float fadeA) {
        if (v3) game.draw().batch().endSpace(); // the veils' shaders as before, through the view's projection
        if (seven) {
            if (v3) flat();
            veilFx.draw(batch);
            if (v3) {
                batch.end();
                view.drawVolumes(volumes, fadeA);
                batch.begin();
            }
        } else {
            LaneLayout lay = engine.layout();
            float ly = engine.lineY(Line.LOWER), lh = field.strokeHalf(Line.LOWER, runTime);
            float uy = engine.lineY(Line.UPPER), uh = field.strokeHalf(Line.UPPER, runTime);
            veilFx.draw(batch, ly, lh, uy, uh, lay.windowLeft(), lay.windowRight());
        }
        if (!clipped) return;
        if (v3) flat();
        if (!veilFx.clipBegin(batch, true)) return;
        hud.drawUnder(batch, introT, runTime, !menuOpen);
        text.replay();
        veilFx.clipEnd(batch);
    }

    /**
     * Into the 3D field: the view's projection, and the batch's space mode, where each lane's and key's own frame costs
     * no draw call (Kelvin 2026-11-10 01:19 「我的手机根本带不动这么大的3D建模」).
     */
    private void deep() {
        batch.setProjectionMatrix(view.combined());
        game.draw().batch().beginSpace();
    }

    /** Out of the 3D field onto the screen: what space mode holds drawn, then the flat camera's projection. */
    private void flat() {
        game.draw().batch().endSpace();
        batch.setProjectionMatrix(camera.combined);
    }

    /** @return radius of a veiled finger's glitch mark: half a note's width in the average lane (Kelvin 16:55). */
    private float glitchMarkRadius() {
        LaneLayout lay = engine.layout();
        float sum = 0f;
        int n = 0;
        for (int i = 0; i < lay.count(); i++) {
            if (!lay.active(i)) continue;
            sum += lay.width(i);
            n++;
        }
        return n > 0 ? 0.5f * notes.headWidth(sum / n) : 64f;
    }

    /** Hook for subclasses: per-frame update before drawing (screen delta). */
    protected void updateExtras(float delta) {
    }

    /** Hook for subclasses to draw in the HUD-over pass (batch drawing, normal blend). */
    protected void drawExtras(float delta) {
    }

    /** Extra zone-hint pulse 0..1 (the tutorial pulses it to α .25, DESIGN §10.2). */
    protected float zoneHintPulse() {
        return 0f;
    }

    /**
     * How much of the centre something else owns, 0..1: the HUD combo there gives way to it. The end banner here;
     * the tutorial adds its closing title.
     */
    protected float centreCover() {
        return overlay.bannerCover(engine.judgeMs(), engine.endMs());
    }

    /** True when stray-touch dots are forced on (tutorial). */
    protected boolean forceStrayDots() {
        return false;
    }

    /** The zone hint's strength for {@link FieldRenderer#drawUnder}: 1 at ZONE HINT 50 %, up to α .25 in a pulse. */
    private float zoneStrength() {
        float base = FieldRenderer.ZONE_HINT_A;
        float a = base * settings.zoneHintStrength();
        float pulse = zoneHintPulse();
        if (pulse > 0f) a = Math.max(a, base + (0.25f - base) * pulse);
        return a / base;
    }

    /** Beat-phase feed for the backdrop (pulse each beat, ping each bar) and the Abyss corners. */
    private void updateBeat() {
        float beat = engine.visualBeat();
        if (!engine.isRunning() || beat < 0f || Float.isNaN(beat)) return;
        float fl = (float) Math.floor(beat);
        boolean bar = ((int) fl & 3) == 0;
        game.backdrop().setBeat(beat - fl, bar);
        if (fl != lastBeatFloor) {
            if (bar && difficulty == Difficulty.ABYSS && !Float.isNaN(lastBeatFloor)) abyssT = runTime;
            lastBeatFloor = fl;
        }
    }

    /** Line-glow beat pulse 1 → 0 over 180 ms after each beat. */
    private float beatPulse() {
        float beat = engine.visualBeat();
        if (beat < 0f || chart == null) return 0f;
        float ms = (float) chart.msBetween(Math.floor(beat), beat);
        return ms < 180f ? 1f - ms / 180f : 0f;
    }

    /**
     * @return where the first judging hint ({@link #drawJudgeHint}) starts: a beat before the chart's first full-screen
     *         edge, or +∞ without one; the run starts by then (Kelvin 2026-11-08 19:35)
     */
    private float firstHintBeat() {
        float[] r = chart.fullScreenRanges();
        if (r != null) for (float e : r) if (Float.isFinite(e)) return e - 1f;
        return Float.POSITIVE_INFINITY;
    }

    /**
     * Judging hint (Kelvin 2026-11-07 06:04 「两个箭头向外就是全屏判定，两个箭头向内就是垂直判定」): in the beat before each
     * edge of the chart's full-screen time, two chevrons between the lines slide apart (‹ ›, a press anywhere from the
     * edge) or together (› ‹, columns again), fading in over a quarter beat and out over the last fifth, gone as the
     * edge comes (19:35「-3~-2拍就播放全屏判定的动画，然后-2拍正式进入全屏判定」).
     */
    private void drawJudgeHint() {
        float[] r = chart != null ? chart.fullScreenRanges() : null;
        if (r == null || r.length == 0) return;
        float beat = engine.visualBeat();
        LaneLayout lay = engine.layout();
        float cx = 0.5f * (lay.windowLeft() + lay.windowRight());
        float cy = 0.5f * (engine.lineY(Line.LOWER) + engine.lineY(Line.UPPER));
        ShapeDrawer sd = game.draw().shapes();
        float old = sd.getPackedColor();
        for (int i = 0; i < r.length; i++) {
            float d = beat - r[i];
            if (!(d >= -1f && d <= 0f)) continue; // also an open end (±∞)
            float a = Math.min(1f, 4f * (d + 1f)) * Math.min(1f, -5f * d);
            boolean apart = i % 2 == 0; // a range starts: full screen
            float p = d + 1f;
            float gap = HINT_GAP + HINT_SLIDE * (apart ? p : 1f - p);
            for (int s = -1; s <= 1; s += 2) {
                float tip = cx + s * gap;
                float back = tip - (apart ? s : -s) * HINT_ARM;
                game.draw().glowDot(tip, cy, 4f * HINT_ARM, Theme.WHITE, 0.16f * a);
                hintPath[0] = back;
                hintPath[1] = cy + HINT_ARM;
                hintPath[2] = tip;
                hintPath[3] = cy;
                hintPath[4] = back;
                hintPath[5] = cy - HINT_ARM;
                sd.setColor(1f, 1f, 1f, 0.9f * a);
                sd.path(hintPath, HINT_STROKE, JoinType.POINTY, true);
            }
        }
        sd.setColor(old);
    }

    /** Abyss pressure (DESIGN §2.7, P1): the corners pulse {@code #FF2E88 @ .06} on bar downbeats. */
    private void drawAbyssPressure() {
        float t = runTime - abyssT;
        if (t < 0f || t > ABYSS_PULSE_SEC) return;
        float a = 0.06f * (1f - t / ABYSS_PULSE_SEC);
        float s = 1000f;
        game.draw().glowDot(Ui.left(), Ui.bottom(), s, Theme.GLITCH_M, a);
        game.draw().glowDot(Ui.right(), Ui.bottom(), s, Theme.GLITCH_M, a);
        game.draw().glowDot(Ui.left(), Ui.top(), s, Theme.GLITCH_M, a);
        game.draw().glowDot(Ui.right(), Ui.top(), s, Theme.GLITCH_M, a);
    }

    // ================================================================== events

    private void consumeEvents() {
        HitFx fx = game.hitFx();
        boolean high = fx.quality() == HitFx.QUALITY_HIGH;
        while (engine.pollEvent(ev)) {
            Line line = ev.line;
            // where the note lands: its line, or its sea (Kelvin 2026-11-07 16:04)
            float ly = line == null ? ev.y : ev.note >= 0 ? engine.landY(chart.note(ev.note)) : engine.lineY(line);
            boolean sky = line == Line.UPPER; // explicit: lines may cross y = 540 (no guessing from y)
            boolean universal = ev.has(GameEvent.FLAG_UNIVERSAL);
            boolean auto = ev.has(GameEvent.FLAG_AUTO);
            switch (ev.type) {
                case GameEvent.HIT: {
                    float x = universal ? ev.touchX : ev.x;
                    if (!universal) { // TIDE: no point effect (DESIGN §19.2)
                        float k = sinkFx(ev.note, x, ly) * keyFx(ev.note);
                        fx.tap(fxPt[0], fxPt[1], ev.judgement, k, sky);
                    }
                    hitFeedback(line, ly, x, universal, high);
                    boolean res = ev.judgement == Judgement.RESONANCE;
                    Note on = universal && ev.note >= 0 ? chart.note(ev.note) : null;
                    if (on != null && on.sea() != null) tideWash.hitSea(on.sea(), line, res, runTime); // along its sea
                    else if (universal) tideWash.hit(engine, ev.note, line, res, runTime); // on the lanes it spans
                    else contact(line, ly, true, auto); // TIDE: no contact ring at the finger either (§19.2)
                    hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                    if (auto && autoHitMs == null) game.audio().playHit(ev.judgement);
                    // a DRAG sounds as it is judged, unless its sound was timed when it was caught early
                    else if (!auto && ev.has(GameEvent.FLAG_DRAG) && !dragSound[ev.note]) playHitAt(ev.timeMs);
                    break;
                }
                case GameEvent.CATCH:
                    // a DRAG caught early is a RESONANCE at its time: time its hit sound now, as autoplay's are
                    if (game.audio() != null && game.audio().timesHits()) {
                        playHitAt(ev.timeMs);
                        dragSound[ev.note] = true;
                    }
                    break;
                case GameEvent.HOLD_START: {
                    boolean based = ev.note >= 0 && holdBase(ev.note);
                    float k = sinkFx(ev.note, ev.x, ly) * keyFx(ev.note);
                    float bx = based ? basePt[0] : fxPt[0], by = based ? basePt[1] : fxPt[1];
                    if (ev.note >= 0) {
                        if (holdFx[ev.note] >= 0) fx.holdEnd(holdFx[ev.note], false);
                        holdFx[ev.note] = fx.holdStart(bx, by, ev.judgement, k, sky);
                    }
                    if (!ev.has(GameEvent.FLAG_REGRAB)) {
                        fx.head(bx, by, ev.judgement, k, sky); // the head's own splash (LOST heads: nothing)
                        hitFeedback(line, ly, ev.x, false, high);
                        hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                    }
                    contact(line, ly, true, auto);
                    if (auto && autoHitMs == null) game.audio().playHit(ev.judgement);
                    break;
                }
                case GameEvent.HOLD_END: {
                    boolean completed = ev.has(GameEvent.FLAG_COMPLETED);
                    int h = ev.note >= 0 ? holdFx[ev.note] : -1;
                    if (h >= 0) {
                        if (completed && holdEndBase(ev.note)) fx.holdMove(h, basePt[0], basePt[1]);
                        fx.holdEnd(h, completed); // a completed STREAM ends with its release splash at the base
                        holdFx[ev.note] = -1;
                    } else if (completed && !ev.has(GameEvent.FLAG_TIDE_STREAM)) {
                        boolean based = ev.note >= 0 && holdEndBase(ev.note);
                        float k = sinkFx(ev.note, ev.x, ly) * keyFx(ev.note);
                        // no held stream (effects were off at its head, or every slot was busy): the splash alone
                        fx.release(based ? basePt[0] : fxPt[0], based ? basePt[1] : fxPt[1], ev.judgement, k, sky);
                    }
                    if (completed) {
                        game.audio().playHoldEnd(); // optional Sounds/hold_end.* (DESIGN §5.14)
                        if (ev.has(GameEvent.FLAG_TIDE_STREAM)) {
                            tideWash.complete(engine, ev.note, line, ev.judgement == Judgement.RESONANCE, runTime);
                        }
                    } else {
                        fx.lost(ev.x, line == Line.UPPER ? ly - 120f : ly + 120f, 1f);
                        // a TIDE STREAM (lane −1) flashes the window, or over separate lanes each column it spans
                        if (!spanFlash(ev.note, line)) field.flash(line, ev.lane, false, runTime);
                        hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                        // a broken TIDE STREAM drains the tide level (unless another one keeps it up)
                        if (ev.has(GameEvent.FLAG_TIDE_STREAM)) tideWash.drain(engine, ev.note, line, runTime);
                    }
                    break;
                }
                case GameEvent.LOST:
                    fx.lost(ev.x, line == Line.UPPER ? ly - 120f : ly + 120f, 1f);
                    if (universal && !spanFlash(ev.note, line)) field.flashWindow(line, false, runTime);
                    else if (ev.lane >= 0) field.flash(line, ev.lane, false, runTime); // a DRAG off the lanes: none
                    hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                    break;
                case GameEvent.MINE:
                    if (Gdx.graphics.getFrameId() != mineSoundFrame) {
                        mineSoundFrame = Gdx.graphics.getFrameId();
                        game.audio().playMine(); // optional Sounds/mine.* (DESIGN §5.15), silent when absent
                    }
                    feedback.mineShock(ev.touchX, ly, runTime);
                    field.flash(line, ev.lane, true, runTime);
                    hud.mineBreak();
                    hud.mineWord(ev.x, line, ly, runTime); // red LOST word (Kelvin 2026-10-12 00:42)
                    break;
                case GameEvent.EMPTY:
                    if (line != null) {
                        contact(line, ly, false, false);
                    } else if ((strayDots || forceStrayDots()) && ev.pointer >= 0 && !controlAt(ev.touchX, ev.touchY)) {
                        feedback.stray(ev.touchX, ev.touchY, runTime);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Hit ripple (High) or bead and EARLY/LATE chevron for a judged lane hit (TIDE: the tide wash, in consumeEvents).
     */
    private void hitFeedback(Line line, float ly, float x, boolean universal, boolean high) {
        if (line == null || universal) return; // TIDE: the tide wash swells the whole line instead (DESIGN §19.2)
        if (high) field.ripple(line, x, 4f, runTime);
        else field.bead(line, x, runTime);
        if (settings.showsEarlyLate(ev.judgement, ev.dtMs)) {
            feedback.chevron(ev.x, line, ly, ev.dtMs > 0f, runTime);
        }
    }

    /** Contact ring and brackets of a touch (DESIGN §5.10); autoplay pulses the lane column instead. */
    private void contact(Line line, float ly, boolean judged, boolean auto) {
        if (line == null) return;
        if (auto) {
            field.pulseColumn(line, ev.lane);
            return;
        }
        float x = ev.pointer >= 0 ? ev.touchX : ev.x;
        LaneLayout lay = engine.layout();
        if (ev.note >= 0 && view.active() && view.keyFrame(lay, chart.note(ev.note)) >= 0) {
            // on a turned lane or sea (Kelvin 2026-11-09 00:13): where its hit shows on the screen
            sinkFx(ev.note, ev.x, ly);
            if (view.project(fxPt[0], fxPt[1], 0f, ringPt)) feedback.contact(ringPt[0], ringPt[1], judged, runTime);
            return;
        }
        feedback.contact(x, ly, judged, runTime);
        if (ev.lane >= 0 && ev.lane < lay.count() && lay.active(ev.lane)
            && engine.chart().lanesShown(lay.beat()) > 0.5f) { // no lane brackets in laneless time
            feedback.brackets(lay.left(ev.lane), lay.right(ev.lane), ly, runTime);
        }
    }

    /** Height of the demo mine's red LOST word above the mine centre (judgement display "At lane"). */
    private static final float MINE_WORD_RISE = 56f;

    /**
     * Tutorial demo: paints a lane-note head with its leading edge centred on (x, y) (a sky head hangs
     * below it, a ground head stands on it) at the current note size, using the chart's own painters. Call
     * from {@link #drawExtras} (normal blend, atlas batch).
     */
    protected void drawNoteDemo(float x, float y, float laneWidth, boolean upper, float alpha) {
        float w = notes.headWidth(laneWidth * notes.noteSize()); // the lane at the key size
        float h = notes.noteHeight();
        Color c = upper ? Theme.SKY_SIG : Theme.GROUND_NOTE;
        notes.paintLaneNote(x, y, w, h, upper ? 180f : 0f, c, alpha, 1f, 0f, 0f, 0f, 0f, 0f, false);
        notes.batch().setColor(Color.WHITE);
    }

    /** Tutorial demo: paints an upright mine body centred on (cx, cy), as wide as in a lane {@code laneWidth} wide. */
    protected void drawMineDemo(float cx, float cy, float laneWidth, float alpha) {
        float mw = laneWidth * notes.noteSize() * GameEngine.MINE_BOX;
        float mh = notes.mineHeight();
        notes.paintMine(cx, cy - mh * 0.5f, mw, mh, 0f, alpha); // (x, y) is the leading-edge centre
        notes.batch().setColor(Color.WHITE);
    }

    /**
     * Tutorial demo: what a touched mine at (x, y) shows (DESIGN §5.15 and Kelvin's red LOST): the
     * contact ring, the shock rings and the red LOST word. No sound, and the combo and score never change.
     */
    protected void demoMineTouch(float x, float y) {
        feedback.contact(x, y, false, runTime);
        feedback.mineShock(x, y, runTime);
        // the red LOST word MINE_WORD_RISE above the mine (an at-lane word sits ZONE + 24 above its ground
        // line) in every judgement mode but Off: Kelvin's chart plays real notes during this demo
        hud.demoMineWord(x, Line.LOWER, y + MINE_WORD_RISE - FieldRenderer.zone() - 24f, runTime);
    }

    /** True when (x, y) is on a HUD control (no stray dot there). Subclasses add their controls. */
    protected boolean controlAt(float x, float y) {
        return pauseControl.contains(x, y);
    }

    /** Moves the effects of every held STREAM to its base as drawn this frame (DESIGN §5.9). */
    private void moveHoldFx() {
        HitFx fx = game.hitFx();
        for (int k = 0; k < engine.activeHoldCount(); k++) {
            int i = engine.activeHold(k);
            if (holdFx[i] < 0) continue; // TIDE STREAMs have no stream FX slot (no HOLD_START)
            if (holdBase(i)) fx.holdMove(holdFx[i], basePt[0], basePt[1]);
        }
    }

    /**
     * A missed TIDE or TIDE STREAM {@code note} over separate lanes (Kelvin 2026-11-11 05:30 「只在那些跨过的轨道」): each
     * column it spans flashes, as a missed key's lane does, instead of the window.
     *
     * @return false when it is not one (no note, one on a sea, or the lanes are not separate)
     */
    private boolean spanFlash(int note, Line line) {
        if (note < 0 || line == null || !engine.layout().separate()) return false;
        Note n = chart.note(note);
        if (n.type().inLane() || n.sea() != null) return false;
        int m = tideWash.columns(engine, note);
        for (int k = 0; k < m; k++) field.flash(line, tideWash.column(k), false, runTime);
        return true;
    }

    /**
     * How much bigger key {@code note}'s hit effects show than a default key's (Kelvin 2026-11-10 04:35 「76的那个按键的
     * 打击特效要大一点」「把打击特效的大小和按键大小绑定」): its width over {@link Lane#DEFAULT_WIDTH}, at least 1 (a
     * narrower key's as before) and at most {@link #FX_KEY_MAX}.
     */
    private float keyFx(int note) {
        if (note < 0) return 1f;
        LaneLayout lay = engine.layout();
        return MathUtils.clamp(lay.keyWidth(chart.note(note)) / (Lane.DEFAULT_WIDTH * lay.unitX()), 1f, FX_KEY_MAX);
    }

    /** The most {@link #keyFx} makes a key's hit effects grow: a key four default keys wide or wider. */
    private static final float FX_KEY_MAX = 4f;

    /**
     * Puts into {@link #fxPt} where the hit effect of note {@code note} at (x, y) shows (Kelvin 2026-11-07 22:54): at
     * its column's and its own depth ({@link LaneLayout#keyMoveZ}, {@link Note#space()}), drawn on the effects' plane
     * towards the eye ({@link View3D#sinkMiddle}).
     *
     * @return the effect's size factor: 1 at the columns' depth, without a note or while the view is flat
     */
    private float sinkFx(int note, float x, float y) {
        fxPt[0] = x;
        fxPt[1] = y;
        if (note < 0 || !view.active()) return 1f;
        Note n = chart.note(note);
        // at its column's middle, its origin (Kelvin 2026-11-09 05:00: a base shorter than 240 brings its front in)
        float own = Channels.at(n.space(), Channels.SOLID, Channels.Z, engine.visualBeat())
            - 0.5f * (LaneLayout.DEFAULT_LENGTH - engine.layout().keyLength(n));
        int f = view.keyFrame(engine.layout(), n);
        if (f >= 0) return view.frameFx(f, x, y, own, fxPt); // on its turned lane or sea (Kelvin 2026-11-09 00:13)
        float z = engine.layout().keyMoveZ(n) + own;
        if (z == 0f) return 1f;
        float t = view.sinkMiddle(z);
        fxPt[0] = view.eyeX() + (x - view.eyeX()) * t;
        fxPt[1] = view.eyeY() + (y - view.eyeY()) * t;
        return t;
    }

    /**
     * Puts into {@link #basePt} the point the effects of STREAM {@code note} start from (Kelvin 2026-10-26 13:25:
     * always at the note's base): its key's hit point as drawn now ({@link NoteRenderer#effectPoint}), or before
     * its own beat (an early head) where the key lands on the line, as a DROP's splash does.
     *
     * @return false when the note is not drawn now (hidden, inactive lane)
     */
    private boolean holdBase(int note) {
        Note n = chart.note(note);
        return notes.effectPoint(engine, n, Math.max(engine.visualBeat(), n.judgement()), basePt);
    }

    /**
     * Like {@link #holdBase}, for the release splash of a completed STREAM: where its base is drawn when the STREAM
     * ends, never later (Kelvin 2026-10-31 15:03: the end comes after the note's movement events, which would put the
     * base back on the line in its lane).
     */
    private boolean holdEndBase(int note) {
        Note n = chart.note(note);
        float beat = Math.min(Math.max(engine.visualBeat(), n.judgement()), n.endBeat());
        return notes.effectPoint(engine, n, beat, basePt);
    }

    // ================================================================== input

    /** Event timestamp of the event being processed (nanoTime base), robust to backends without one. */
    private long eventNanos() {
        long now = engine.clock().nanoTime();
        long t = Gdx.input != null ? Gdx.input.getCurrentEventTime() : 0L;
        if (t <= 0L || t > now || now - t > 1_000_000_000L) return now;
        return t;
    }

    /** The lanes a press goes to first ({@link View3D#toField}): those with something to judge now. */
    private final View3D.Awaits awaits = i -> engine.awaitsPress(i);

    /**
     * Sets {@link #fieldPt} to where a press at (x, y) of the screen lands on the field: the point itself, or while the
     * view is turned ({@link View3D#toField}) along its ray. The engine judges there; the menus, the pause button and
     * the touch rings stay on the screen. The other lanes whose areas on the screen hold the press go to the engine
     * with it ({@link GameEngine#pressOverlap}; Kelvin 2026-11-10 02:19).
     */
    private Vector2 toField(float x, float y) {
        LaneLayout lay = engine.layout();
        fieldLane = -1;
        int n = 0;
        if (view.active()) {
            view.toField(lay, x, y, fieldPt, awaits); // a lane with a key waiting first (Kelvin 2026-11-10 02:10)
            fieldLane = view.hitLane();
            for (int k = 0; k < view.metCount(); k++) {
                int m = view.metLane(k);
                if (m == fieldLane || !view.metJoint(k)) continue;
                if (n == overLane.length) {
                    overLane = Arrays.copyOf(overLane, 2 * n);
                    overX = Arrays.copyOf(overX, 2 * n);
                    overY = Arrays.copyOf(overY, 2 * n);
                }
                overLane[n] = m;
                overX[n] = view.metX(k);
                overY[n] = onLines(lay, m, view.metY(k));
                n++;
            }
        } else {
            fieldPt.set(x, y);
        }
        engine.pressOverlap(n, overLane, overX, overY);
        int i = fieldLane >= 0 ? fieldLane : lay.moving() ? lay.indexAtX(fieldPt.x) : -1;
        if (i >= 0) fieldPt.y = onLines(lay, i, fieldPt.y);
        return fieldPt;
    }

    /** @return y on lane i's own lines (Kelvin 2026-11-08 23:10) moved to the chart's, where it is judged */
    private float onLines(LaneLayout lay, int i, float y) {
        if (!lay.moving()) return y;
        float ky = Field.ky();
        float yl = engine.lineY(Line.LOWER);
        float yu = engine.lineY(Line.UPPER);
        float ol = yl + lay.lift(i, Line.LOWER) * ky;
        float ou = yu + lay.lift(i, Line.UPPER) * ky;
        return Math.abs(ou - ol) > 1f ? yl + (y - ol) * (yu - yl) / (ou - ol) : y + yl - ol;
    }

    /** The other lanes the press {@link #toField} mapped last is in, and where ({@link GameEngine#pressOverlap}). */
    private int[] overLane = new int[16];
    private float[] overX = new float[16], overY = new float[16];

    /** The lane the press {@link #toField} mapped last met in 3D ({@link View3D#hitLane}), or −1. */
    private int fieldLane = -1;

    /**
     * Sets {@link #seaPt} for a press at (x, y) of the screen (Kelvin 2026-11-08 19:43): while the view is turned its
     * ray, so a key on a sea is judged at its own depth ({@link View3D#seaRay}); else the point's x, the same at any
     * depth.
     */
    private float[] seaRay(float x, float y) {
        if (view.active()) {
            view.seaRay(x, y, seaPt);
        } else {
            seaPt[0] = x;
            seaPt[1] = 0f;
        }
        return seaPt;
    }

    @Override
    protected boolean onTouchDown(float x, float y, int pointer, int button) {
        long ns = eventNanos();
        Vector2 f = toField(x, y);
        float[] s = seaRay(x, y);
        boolean veiled = volumes.veiled(x, y); // a v7 veil's outline as drawn
        if (menuOpen) {
            engine.touchDown(pointer, f.x, f.y, s[0], s[1], fieldLane, veiled, ns); // tracked only (paused)
            menu.touchDown(x, y, pointer);
            return true;
        }
        int r = engine.touchDown(pointer, f.x, f.y, s[0], s[1], fieldLane, veiled, ns);
        marks.down(pointer, x, y, r == GameEngine.INPUT_VEILED, time);
        if (r == GameEngine.INPUT_JUDGED) {
            game.audio().playHit(engine.lastInputJudgement(), ns);
            if (settings.haptics == Settings.HAPTICS_ALL) Haptics.light();
        } else if (r == GameEngine.INPUT_EMPTY && settings.hitSoundMode == Settings.HIT_ON_ANY_ZONE_TAP) {
            game.audio().playHit(null);
        }
        // a touch that did nothing in gameplay (outside both zones, an empty zone, inside a veil, or not running)
        // may belong to a subclass control first (the tutorial's SKIP pill); a touch the pause control would not
        // count (it judged, re-grabbed, touched a mine or covers a running TIDE STREAM) never reaches it
        if ((r == GameEngine.INPUT_STRAY || r == GameEngine.INPUT_IGNORED || r == GameEngine.INPUT_EMPTY
            || r == GameEngine.INPUT_VEILED) && !PauseControl.judges(engine, r, f.y)) {
            if (extraTouchDown(x, y, pointer)) return true;
        }
        // the touch already reached gameplay; now the pause control may count it
        int p = pauseControl.touchDown(engine, r, pointer, x, y, ns, settings.pauseMode, pauseEnabled());
        if (p == PauseControl.PAUSE) openPause("tap");
        else if (p == PauseControl.HINT) hud.pauseHint(runTime);
        return true;
    }

    @Override
    protected boolean onTouchDragged(float x, float y, int pointer) {
        Vector2 f = toField(x, y);
        float[] s = seaRay(x, y);
        engine.touchDragged(pointer, f.x, f.y, s[0], s[1], fieldLane, eventNanos());
        if (menuOpen) menu.touchDragged(x, y, pointer);
        extraTouchDragged(x, y, pointer);
        pauseControl.touchDragged(pointer, x, y);
        return true;
    }

    @Override
    protected boolean onTouchUp(float x, float y, int pointer, int button) {
        Vector2 f = toField(x, y);
        float[] s = seaRay(x, y);
        engine.touchUp(pointer, f.x, f.y, s[0], s[1], fieldLane, eventNanos());
        if (menuOpen) menu.touchUp(x, y, pointer);
        extraTouchUp(x, y, pointer);
        if (pauseControl.touchUp(pointer, x, y, settings.pauseMode) == PauseControl.PAUSE && pauseEnabled()) {
            openPause("tap");
        }
        return true;
    }

    @Override
    protected void onTouchCancel(int pointer) {
        engine.touchCancelled(pointer, eventNanos());
        menu.cancel();
        extraTouchCancel(pointer);
        pauseControl.touchCancelled(pointer);
    }

    /**
     * The system took the whole gesture (Kelvin 2026-11-06: players reported dropped touches): the engine lets every
     * finger go and keeps the holds they held ({@link GameEngine#gestureCancelled}), and every press on the menus and
     * controls is called off. Breaking the holds here, one pointer at a time, is what dropped them.
     */
    @Override
    protected void onGestureCancel(int pointer) {
        engine.gestureCancelled();
        menu.cancel();
        for (int p = 0; p < GameEngine.MAX_POINTERS; p++) {
            extraTouchCancel(p);
            pauseControl.touchCancelled(p);
        }
    }

    /**
     * Hook: a touch that did nothing in gameplay ({@code INPUT_STRAY} outside both zones, {@code INPUT_EMPTY} in
     * a zone with nothing to judge, no mine touched and no running TIDE STREAM covered, {@code INPUT_VEILED}
     * inside a veil, {@code INPUT_IGNORED} while the run is not running), before the pause control sees it; return
     * true to consume it (the pause control then neither counts nor cancels it).
     */
    protected boolean extraTouchDown(float x, float y, int pointer) {
        return false;
    }

    /** Hook: every drag (after the engine). */
    protected void extraTouchDragged(float x, float y, int pointer) {
    }

    /** Hook: every release (after the engine). */
    protected void extraTouchUp(float x, float y, int pointer) {
    }

    /** Hook: a cancelled pointer. */
    protected void extraTouchCancel(int pointer) {
    }

    @Override
    protected boolean onKeyDown(int keycode) {
        if (keycode == com.badlogic.gdx.Input.Keys.PAUSE) {
            onBack(); // the keyboard's Pause key works like Esc: pause at once, or resume
            return true;
        }
        if (menuOpen && (keycode == com.badlogic.gdx.Input.Keys.ENTER
            || keycode == com.badlogic.gdx.Input.Keys.NUMPAD_ENTER)) {
            startCountdown();
            return true;
        }
        int r = engine.keyDown(keycode, eventNanos());
        if (r == GameEngine.INPUT_JUDGED) game.audio().playHit(engine.lastInputJudgement());
        else if (r == GameEngine.INPUT_EMPTY && settings.hitSoundMode == Settings.HIT_ON_ANY_ZONE_TAP) {
            game.audio().playHit(null);
        }
        return GameKeys.slotOf(keycode) >= 0;
    }

    @Override
    protected boolean onKeyUp(int keycode) {
        engine.keyUp(keycode, eventNanos());
        return GameKeys.slotOf(keycode) >= 0;
    }

    @Override
    protected void onBack() {
        if (resultSent) return;
        if (menuOpen) startCountdown();
        else openPause("key");
    }

    // ------------------------------------------------------------------ pause

    /** True while the pause control may pause: the run or its pre-roll is going, no menu, no countdown. */
    private boolean pauseEnabled() {
        return chart != null && !resultSent && !menuOpen && countdown < 0f && (engine.isRunning() || preroll >= 0f);
    }

    /** Opens the pause menu (freezes the run). No-op once the run is over (the music and the notes have ended). */
    protected void openPause() {
        openPause("key");
    }

    private void openPause(String by) {
        // nothing is left to play after the end, and quitting from the pause would lose the record (Kelvin 2026-10-17)
        if (chart == null || resultSent || engine.isFinished() || engine.judgeMs() >= engine.endMs()) return;
        engine.pause();
        dropQueuedHits();
        menuOpen = true;
        game.guardEdges(false); // the menu gives the edges back: the system's back gesture resumes (onBack)
        countdown = -1f;
        countIn = false;
        pausedBy = by;
        pauseControl.reset();
        hud.clearPauseHint();
        menu.open(settings != null && settings.zh());
    }

    private void startCountdown() {
        if (!menuOpen) return;
        menuOpen = false;
        game.guardEdges(true);
        menu.close();
        pauseControl.reset();
        applyOffsets();
        engine.clock().setVolume(game.audio().musicVolume());
        if (!started) return; // paused before the cover opened: the start logic runs the pre-roll
        if (preroll >= 0f) {
            preroll = 0f; // paused during the pre-roll: it starts again from the 3 (DESIGN §5.11)
            return;
        }
        countdown = 3f * COUNT_STEP_SEC;
        // on Android the music runs in through the count, unheard, and is in step as it ends (Kelvin 2026-10-21 15:09)
        countIn = engine.startCountIn(COUNT_IN_MS);
    }

    /** @return the song times (ms, ascending, one per instant) of every note but mines: autoplay's hit sounds */
    private static double[] hitTimes(Chart chart) {
        Note[] notes = chart.notes();
        double[] t = new double[notes.length];
        int n = 0;
        for (Note note : notes) if (note.type().judged() && !note.fake()) t[n++] = note.judgementMs();
        Arrays.sort(t, 0, n);
        int u = 0;
        for (int i = 0; i < n; i++) if (u == 0 || t[i] - t[u - 1] >= 1.0) t[u++] = t[i];
        return Arrays.copyOf(t, u);
    }

    /**
     * Autoplay with a timed output: hands each hit sound over {@link #AUTO_HIT_AHEAD_MS} before its note, to be
     * heard as the music reaches it (the judging, a frame later, plays none). Only while the run's time runs.
     * Placed on chart time (the clock less the audio offset), the time the notes are judged and drawn on.
     */
    private void queueAutoHits() {
        if (autoHitMs == null || !engine.isRunning()) return;
        SongClock clock = engine.clock();
        long now = clock.nanoTime();
        double t = clock.timeAtNanos(now) - engine.audioOffsetMs();
        while (autoHitNext < autoHitMs.length && autoHitMs[autoHitNext] <= t + AUTO_HIT_AHEAD_MS) {
            game.audio().playHit(Judgement.RESONANCE, now + (long) ((autoHitMs[autoHitNext++] - t) * 1e6));
        }
    }

    /**
     * Plays the hit sound so it is heard at chart time {@code ms} (the clock less the audio offset, as notes are judged
     * on): at once when that has passed, else timed by the output.
     */
    private void playHitAt(double ms) {
        if (game.audio() == null) return;
        SongClock clock = engine.clock();
        long now = clock.nanoTime();
        double t = clock.timeAtNanos(now) - engine.audioOffsetMs();
        game.audio().playHit(Judgement.RESONANCE, ms <= t ? now : now + (long) ((ms - t) * 1e6));
    }

    /**
     * The run paused: hit sounds not yet heard are dropped; autoplay's are handed over again after the resume, and a
     * caught DRAG's sound when it is judged.
     */
    private void dropQueuedHits() {
        if (game.audio() != null) game.audio().cancelTimedHits();
        double now = engine.clock().nowMs() - engine.audioOffsetMs();
        Note[] notes = chart != null ? chart.notes() : null;
        for (int i = 0; notes != null && i < Math.min(notes.length, dragSound.length); i++) {
            if (dragSound[i] && engine.noteState(i) == GameEngine.STATE_PENDING && notes[i].judgementMs() > now) {
                dragSound[i] = false;
            }
        }
        if (autoHitMs == null) return;
        double t = engine.clock().nowMs() - engine.audioOffsetMs();
        autoHitNext = 0;
        while (autoHitNext < autoHitMs.length && autoHitMs[autoHitNext] <= t) autoHitNext++;
    }

    /**
     * The chart's opacity factor at {@code left} seconds of the resume countdown: {@link #COUNT_FADE_FROM} as it
     * starts, rising evenly to 1 as it ends (Kelvin 2026-10-21 15:40).
     */
    static float countFade(float left) {
        float done = 1f - left / (3f * COUNT_STEP_SEC);
        return COUNT_FADE_FROM + (1f - COUNT_FADE_FROM) * MathUtils.clamp(done, 0f, 1f);
    }

    private void retryRun() {
        if (tutorial()) game.startTutorial();
        else game.startGameplay(song, difficulty, autoplay);
    }

    private void quitRun() {
        game.transitionTo(game.map(), TransitionLayer.Style.IRIS);
    }

    /** Ends the run now and shows its result (the tutorial's SKIP). */
    protected void finishNow() {
        if (resultSent || chart == null) return;
        resultSent = true;
        countdown = -1f; // a resume countdown in progress (tutorial SKIP) must not resume the run behind the result
        countIn = false;
        engine.pause();
        game.showResult(buildResult());
    }

    // ================================================================== result

    /** Builds the result of the finished run. */
    protected PlayResult buildResult() {
        PlayResult.Builder b = PlayResult.builder();
        if (song != null) {
            b.chart(song.id(), difficulty).info(song.title(), song.artist())
                .constant(song.constant(difficulty), song.constantText(difficulty));
        } else {
            b.chart(chart.title(), difficulty).info(chart.title(), chart.artist())
                .constant(chart.chartConstantValue(0f), chart.chartConstant());
        }
        engine.fillResult(b);
        return b.tutorial(tutorial()).timestamp(System.currentTimeMillis()).build();
    }
}
