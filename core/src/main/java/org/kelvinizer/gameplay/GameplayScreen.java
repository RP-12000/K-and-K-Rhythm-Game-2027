package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.Main;
import org.kelvinizer.Params;
import org.kelvinizer.elements.Chart;
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
 * resume with a 3-2-1 countdown during which notes stay frozen and nothing is judged.
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
    /** Length of each resume countdown step (s). */
    public static final float COUNT_STEP_SEC = 0.6f;
    private static final float ABYSS_PULSE_SEC = 0.40f;
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
    /** SHOW TOUCHES: a ring under every finger (Kelvin 2026-10-18 19:52). */
    private final TouchMarks marks;
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
    /** The faint dot where a touch hits no zone: the tutorial's (players' SHOW TOUCHES draws {@link #marks}). */
    private boolean strayDots;
    private boolean showTouches;
    private float lastBeatFloor = Float.NaN;
    private float abyssT = -10f;

    // pause state (field names read by the screenshot tests)
    private boolean menuOpen;
    private float countdown = -1f;
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

        FileHandle music = song != null && song.music() != null ? song.music() : chart.musicFile();
        float dur = song != null ? song.durationMs() : 0f;
        if (dur > 0f) engine.clock().load(music, dur);
        else engine.clock().load(music);
        engine.clock().setVolume(game.audio() != null ? game.audio().musicVolume() : 0.8f);
        // decode and prime the optional hit / hold-end / mine sounds while covered (once per
        // session), so the first judgement never waits for a load
        if (game.audio() != null) game.audio().hitSound();
        applyOffsets();
        // a resume's music latency is learned in the clock across runs; it starts from the device's stored one
        DeviceSettings d = game.profiles() != null ? game.profiles().device() : null;
        if (d != null && engine.clock().resumeLatencyMs() <= 0f) {
            engine.clock().setResumeLatencyMs(d.musicResumeLatencyMs);
        }
        // the playfield fills the screen (Kelvin 16:17): fit the stretch before the engine and the edge entries use it
        Field.fit(Ui.worldW(), Ui.worldH());
        engine.load(chart, autoplay);
        game.hitFx().clear();

        String title = song != null ? song.title() : chart.title();
        constText = song != null ? song.constantText(diff) : chart.chartConstant();
        if (constText == null) constText = "";
        field.begin(chart);
        // the note size sets how far a turned body reaches, so which notes slide in (DESIGN §5.4)
        notes.setStyle(settings.noteSizeScale, game.hitFx().quality() == HitFx.QUALITY_HIGH);
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
     * edge (DESIGN §5.4, {@link EdgeEntry}, trails included) and the frame's offsets. The engine
     * knows nothing of the screen, so the screen moves its start, never later.
     */
    private void startBeforeFirstNote() {
        float beat = notes.entry().firstDrawBeat();
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

    /** @return seconds left in the resume countdown, or −1. */
    public float resumeCountdown() {
        return countdown;
    }

    private void applyOffsets() {
        DeviceSettings d = game.profiles() != null ? game.profiles().device() : null;
        // no visual offset since 2026-10-18 (Kelvin: it overlapped the audio offset)
        engine.setOffsets(d != null ? d.audioOffsetMs() : 0f, 0f);
        engine.clock().setStartLeadMs(d != null ? d.musicStartLatencyMs : 0f);
    }

    /**
     * Keeps the music start latency the clock measured (averaged with the stored one), so the next
     * song starts its music that much early and the first notes do not hitch, and the resume latency
     * it learned, so the next resume's music is primed that much early.
     */
    private void recordLatencies() {
        DeviceSettings d = game.profiles() != null ? game.profiles().device() : null;
        if (d == null) return;
        float r = engine.clock().resumeLatencyMs();
        if (r > 0f && Math.abs(r - d.musicResumeLatencyMs) >= 0.5f) {
            d.musicResumeLatencyMs = r;
            game.profiles().markDirty();
        }
        float m = engine.clock().measuredStartLatencyMs();
        if (Float.isNaN(m)) return;
        float v = d.musicStartLatencyMs > 0f ? 0.5f * (d.musicStartLatencyMs + m) : m;
        if (Math.abs(v - d.musicStartLatencyMs) < 0.5f) return;
        d.musicStartLatencyMs = v;
        game.profiles().markDirty();
    }

    @Override
    protected void onShow() {
        Backdrop b = game.backdrop();
        b.setMode(Backdrop.Mode.GAMEPLAY);
        b.clearFocus();
        b.setDepth(game.gameplayDepth(song, difficulty));
        b.setDim(settings.backgroundDim);
        b.setWash(wash, JACKET_WASH_ALPHA);
        Texture img = imageHook();
        b.setImage(img, img != null ? ART_ALPHA * MathUtils.clamp(settings.artOpacity, 0f, 1f) : 0f);
        b.setComboLife(0);
    }

    @Override
    protected void onHide() {
        engine.pause();
        recordLatencies();
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
        pauseControl.reset();
    }

    @Override
    public void pause() {
        openPause("app"); // app to background: pause instantly (Main already paused the audio)
    }

    @Override
    public void dispose() {
        engine.clock().dispose();
        disposeSongArt();
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
            countdown -= delta;
            if (countdown < 0f) {
                countdown = -1f;
                engine.resume();
            } else if (countdown * 1000f < engine.clock().resumeLeadMs()) {
                engine.primeResume(); // the sound is back as the count ends (Android start latency)
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
        float runDelta = started && !menuOpen && !engine.isPaused() ? delta : 0f;
        runTime += runDelta;
        if (started) introT += runDelta;
        consumeEvents();
        if (showTouches) marks.update(engine, time);
        HitFx fx = game.hitFx();
        fx.update(runDelta);
        moveHoldFx();
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

        batch.begin();
        game.backdrop().render(batch, viewport, runDelta);
        // culling + approach state first: a TIDE approach brightens the zone hint of block A
        field.clearApproach();
        notes.setStyle(settings.noteSizeScale, fx.quality() == HitFx.QUALITY_HIGH);
        notes.setHitPoint(settings.hitPointPx);
        notes.setSyncHint(settings.syncHint);
        notes.prepare(engine, field, settings.approachGlow);
        trails.setQuality(fx.quality());
        tideWash.setStyle(settings.reduceFlashing, fx.quality());
        trails.prepare(engine, tideWash.quadsBound()); // block A may share block B's flush
        // ---- normal block A
        drawAbyssPressure();
        field.drawUnder(engine, zoneStrength(), MathUtils.clamp(introT / 0.35f, 0f, 1f));
        tideWash.draw(engine);  // above the lane field, beneath notes, judgement word and HUD
        hud.drawUnder(batch, introT, runTime, !menuOpen);
        // ---- HUD-under text (recorded, then one draw call per font page)
        text.start(batch);
        hud.setCentreCover(centreCover());
        hud.drawUnderText(text, engine, runTime, !menuOpen && countdown < 0f);
        feedback.drawText(text, game.fonts(), runTime);
        text.replay();
        // ---- normal block B
        field.drawLines(engine, runTime, introT);
        notes.drawBands(engine, runTime); // TIDE STREAM bands beneath trails, lane notes and mines
        trails.draw(engine);   // afterimages beneath every live note (ARCHITECTURE §17)
        notes.draw(engine, runTime);
        overlay.drawBannerScrim(engine.judgeMs(), engine.endMs());
        // ---- additive block (one blend switch)
        int src = batch.getBlendSrcFunc();
        int dst = batch.getBlendDstFunc();
        int srcA = batch.getBlendSrcFuncAlpha();
        int dstA = batch.getBlendDstFuncAlpha();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        field.drawAdditive(engine, runTime, combo, beatPulse());
        notes.drawGlows();
        feedback.drawAdditive(runTime);
        overlay.drawBurst(batch, engine.judgeMs(), engine.endMs());
        fx.draw(batch);
        batch.setBlendFunctionSeparate(src, dst, srcA, dstA);
        if (showTouches) marks.draw(time); // over the notes and effects, under the HUD
        // ---- HUD-over
        drawExtras(delta);
        if (countdown >= 0f) overlay.drawResumeCountdown(batch, countdown, time);
        else if (!menuOpen) overlay.drawStartCountdown(batch, preroll, runTime);
        overlay.drawEndBanner(batch, engine.judgeMs(), engine.endMs(), engine.scores(), runTime,
            game.backdrop().sonar());
        // ---- top layer: pause scrim, corner HUD (drawn last), pause buttons; then all their text
        menu.drawScrim(batch);
        hud.drawCorners(batch, engine, introT, runTime, menuOpen, menuOpen ? 0f : pauseControl.holdProgress());
        menu.drawShapes(batch);
        text.start(batch);
        hud.drawCornerText(text, introT);
        menu.drawText(text);
        text.replay();
        batch.end();
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

    /** The zone hint's strength for {@link FieldRenderer#drawUnder}: 1 with ZONE HINT on, up to α .25 in a pulse. */
    private float zoneStrength() {
        float base = FieldRenderer.ZONE_HINT_A;
        float a = settings.zoneHint ? base : 0f;
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
            float ly = line != null ? engine.lineY(line) : ev.y;
            boolean sky = line == Line.UPPER; // explicit: lines may cross y = 540 (no guessing from y)
            boolean universal = ev.has(GameEvent.FLAG_UNIVERSAL);
            boolean auto = ev.has(GameEvent.FLAG_AUTO);
            switch (ev.type) {
                case GameEvent.HIT: {
                    float x = universal ? ev.touchX : ev.x;
                    if (!universal) fx.tap(x, ly, ev.judgement, 1f, sky); // TIDE: no point effect (DESIGN §19.2)
                    hitFeedback(line, ly, x, universal, high);
                    if (universal) tideWash.hit(line, ev.judgement == Judgement.RESONANCE, runTime);
                    else contact(line, ly, true, auto); // TIDE: no contact ring at the finger either (§19.2)
                    hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                    if (auto) game.audio().playHit(ev.judgement);
                    break;
                }
                case GameEvent.HOLD_START:
                    if (ev.note >= 0) {
                        if (holdFx[ev.note] >= 0) fx.holdEnd(holdFx[ev.note], false);
                        holdFx[ev.note] = fx.holdStart(ev.x, ly, ev.judgement, 1f, sky);
                    }
                    if (!ev.has(GameEvent.FLAG_REGRAB)) {
                        fx.ripple(ev.x, ly, ev.judgement, 1f); // STREAM head water rings (DESIGN §20.5)
                        hitFeedback(line, ly, ev.x, false, high);
                        hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                    }
                    contact(line, ly, true, auto);
                    if (auto) game.audio().playHit(ev.judgement);
                    break;
                case GameEvent.HOLD_END: {
                    boolean completed = ev.has(GameEvent.FLAG_COMPLETED);
                    int h = ev.note >= 0 ? holdFx[ev.note] : -1;
                    if (h >= 0) {
                        fx.holdEnd(h, completed); // a completed hold seals itself (head grade at 80 %)
                        holdFx[ev.note] = -1;
                    } else if (completed && !ev.has(GameEvent.FLAG_TIDE_STREAM)) {
                        fx.tap(ev.x, ly, ev.judgement, HitFx.SEAL_SCALE, sky); // no stream slot: seal directly
                    }
                    if (completed) {
                        game.audio().playHoldEnd(); // optional Sounds/hold_end.* (DESIGN §5.14)
                        if (ev.has(GameEvent.FLAG_TIDE_STREAM)) {
                            tideWash.complete(line, ev.judgement == Judgement.RESONANCE, runTime);
                        }
                    } else {
                        fx.lost(ev.x, line == Line.UPPER ? ly - 120f : ly + 120f, 1f);
                        field.flash(line, ev.lane, false, runTime); // a TIDE STREAM (lane −1) flashes the window
                        hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                        // a broken TIDE STREAM drains the tide level (unless another one keeps it up)
                        if (ev.has(GameEvent.FLAG_TIDE_STREAM) && !engine.tideRunning(line)) {
                            tideWash.drain(line, runTime);
                        }
                    }
                    break;
                }
                case GameEvent.LOST:
                    fx.lost(ev.x, line == Line.UPPER ? ly - 120f : ly + 120f, 1f);
                    if (universal) field.flashWindow(line, false, runTime);
                    else field.flash(line, ev.lane, false, runTime);
                    hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                    break;
                case GameEvent.MINE:
                    game.audio().playMine(); // optional Sounds/mine.* (DESIGN §5.15), silent when absent
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
        feedback.contact(x, ly, judged, runTime);
        LaneLayout lay = engine.layout();
        if (ev.lane >= 0 && ev.lane < lay.count() && lay.active(ev.lane)) {
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
        float w = notes.headWidth(laneWidth);
        float h = notes.noteHeight();
        Color c = upper ? Theme.SKY_SIG : Theme.GROUND_SIG;
        notes.paintLaneNote(x, y, w, h, upper ? 180f : 0f, c, alpha, 1f, 0f, 0f, 0f, 0f, 0f, false);
        notes.batch().setColor(Color.WHITE);
    }

    /** Tutorial demo: paints an upright mine body centred on (cx, cy), as wide as in a lane {@code laneWidth} wide. */
    protected void drawMineDemo(float cx, float cy, float laneWidth, float alpha) {
        float mw = laneWidth * GameEngine.MINE_BOX;
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

    private void moveHoldFx() {
        HitFx fx = game.hitFx();
        LaneLayout lay = engine.layout();
        for (int k = 0; k < engine.activeHoldCount(); k++) {
            int i = engine.activeHold(k);
            if (holdFx[i] < 0) continue; // TIDE STREAMs have no stream FX slot (no HOLD_START)
            Note n = chart.note(i);
            fx.holdMove(holdFx[i], lay.centre(n.laneIndex()), engine.lineY(n.line()));
        }
    }

    // ================================================================== input

    /** Event timestamp of the event being processed (nanoTime base), robust to backends without one. */
    private long eventNanos() {
        long now = engine.clock().nanoTime();
        long t = Gdx.input != null ? Gdx.input.getCurrentEventTime() : 0L;
        if (t <= 0L || t > now || now - t > 1_000_000_000L) return now;
        return t;
    }

    @Override
    protected boolean onTouchDown(float x, float y, int pointer, int button) {
        long ns = eventNanos();
        if (menuOpen) {
            engine.touchDown(pointer, x, y, ns); // tracked only (paused)
            menu.touchDown(x, y, pointer);
            return true;
        }
        int r = engine.touchDown(pointer, x, y, ns);
        if (r == GameEngine.INPUT_JUDGED) {
            game.audio().playHit(engine.lastInputJudgement());
            if (settings.haptics == Settings.HAPTICS_ALL) Haptics.light();
        } else if (r == GameEngine.INPUT_EMPTY && settings.hitSoundMode == Settings.HIT_ON_ANY_ZONE_TAP) {
            game.audio().playHit(null);
        }
        // a touch that did nothing in gameplay (outside both zones, an empty zone, or not running) may belong to
        // a subclass control first (the tutorial's SKIP pill); a touch the pause control would not count (it
        // judged, re-grabbed, touched a mine or covers a running TIDE STREAM) never reaches it
        if ((r == GameEngine.INPUT_STRAY || r == GameEngine.INPUT_IGNORED || r == GameEngine.INPUT_EMPTY)
            && !PauseControl.judges(engine, r, y)) {
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
        engine.touchDragged(pointer, x, y, eventNanos());
        if (menuOpen) menu.touchDragged(x, y, pointer);
        extraTouchDragged(x, y, pointer);
        pauseControl.touchDragged(pointer, x, y);
        return true;
    }

    @Override
    protected boolean onTouchUp(float x, float y, int pointer, int button) {
        engine.touchUp(pointer, x, y, eventNanos());
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
     * Hook: a touch that did nothing in gameplay ({@code INPUT_STRAY} outside both zones, {@code INPUT_EMPTY} in
     * a zone with nothing to judge, no mine touched and no running TIDE STREAM covered, {@code INPUT_IGNORED}
     * while the run is not running), before the pause control sees it; return true to consume it (the pause
     * control then neither counts nor cancels it).
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
        menuOpen = true;
        countdown = -1f;
        pausedBy = by;
        pauseControl.reset();
        hud.clearPauseHint();
        menu.open(settings != null && settings.zh());
    }

    private void startCountdown() {
        if (!menuOpen) return;
        menuOpen = false;
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
