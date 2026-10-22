package org.kelvinizer.screens;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.audio.AudioService;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.play.PlayResult;
import org.kelvinizer.play.RecordOutcome;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.result.Choreo;
import org.kelvinizer.screens.result.CornerHud;
import org.kelvinizer.screens.result.FxPool;
import org.kelvinizer.screens.result.GradeStamp;
import org.kelvinizer.screens.result.JacketBlock;
import org.kelvinizer.screens.result.ResultKit;
import org.kelvinizer.screens.result.ResultLayout;
import org.kelvinizer.screens.result.ResultModel;
import org.kelvinizer.screens.result.ScorePanel;
import org.kelvinizer.screens.result.StatsBlock;
import org.kelvinizer.screens.result.TextQueue;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.KkScreen;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Spring;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 结算画面 DIVE LOG：ASCENT 转场盖住画面时一次算好全部数据，
 * 然后按时间线播放曲绘、分数、判定数量、评级印章、深度和贝壳变化等动画，点击可快进。
 * 2026-10-19 起按第七、八版设计稿排版：左上玩家框（深度在里面数）、右上贝壳胶囊、标题带、16:9 大曲绘（难度块在右下角）
 * 左下压着评级和分数，曲绘下面是连击线、早晚和判定数，右下角重开和继续，右边留给人物；比 16:8.5 宽的手机分三栏。
 * 深度明细、贝壳明细、早晚共鸣和分数明细都是点开看（早晚偏差的柱状图 10-19 去掉了）。
 * RETRY 重开同一张谱面，CONTINUE 用 IRIS 转场回到地图。
 *
 * <p>Result screen, "DIVE LOG" (DESIGN §6, §11, §12), reached through {@code Main.showResult},
 * which calls {@link #present} while the ASCENT covers the screen.
 *
 * <p>Everything shown is derived once in {@link #present} ({@link ResultModel}); the screen then
 * plays the {@link Choreo} timeline from the moment the ASCENT starts opening (t = 0): the title band and the
 * jacket, the title decode, the score decode and its landing over the jacket, the counts, the chain line and the
 * early / late line, the grade stamp slam and impact, the best line (or the AUTOPLAY pill), the player frame and
 * the shell capsule in the top corners with the depth (D) and shells (E) phases, the UNLOCKED beat, and the
 * buttons. The layout is design rounds 7 and 8 (Kelvin 2026-10-19 06:28 and 15:2x, {@link
 * ResultLayout}): no clear badge since round 8, and three columns on screens wider than 16:8.5. Taps fast-forward
 * then jump to the end (§6.4); idle taps (§6.5) re-stamp and open the popovers: the depth breakdown (the player
 * frame), the shell breakdown (the capsule), the score breakdown (the score) and EARLY / LATE RESONANCE (PERFECT),
 * and the offset hint opens its sheet. The early / late line opens nothing since the echo trace card went (Kelvin
 * 2026-10-19 17:48 「早晚的那个柱状图不要了」).
 *
 * <p>Buttons: RETRY (REPLAY for the tutorial) restarts the same chart, difficulty and autoplay
 * (Main stops the result music); CONTINUE closes an IRIS back to the map and fades the music
 * out over 450 ms. No allocation per frame after {@link #present}.
 */
public class ResultScreen extends KkScreen<Main> {
    private static final float STAMP_IDLE_INTERVAL = 3.25f;
    private static final int MAX_KICKS = 4;
    /** UNLOCKED alert display length (s). */
    private static final float ALERT_UNLOCK_D = 2.0f;
    // the screen is English in both languages (Kelvin 2026-10-18 16:45 「结算界面的英文也全部保留」) except the
    // RETRY / CONTINUE buttons, the chart offset sheet and the shell breakdown, which follow the language
    // (17:22 「绿框框起来的语言还是分开吧，然后点击贝壳查看的明细也分开」), the score breakdown (20:34 「点一下分数
    // 弹出的细则框还是分中英文」), and the depth breakdown like the shell one
    private static final CharSequence RETRY = Lang.of("RETRY", "重开"), REPLAY = Lang.of("REPLAY", "再玩一次");
    /** Label cap of RETRY / CONTINUE in 中文 (20:34 「下面的重开/继续字体可以做大一点」); English keeps the default. */
    private static final float ZH_BUTTON_CAP = 34f;
    private static final String UNLOCKED = "UNLOCKED";
    private static final String EARLY = "EARLY", LATE = "LATE";
    private static final CharSequence SHELLS_TOTAL = Lang.of("TOTAL", "合计");
    private static final CharSequence OFFSET_TITLE = Lang.of("CHART OFFSET", "谱面延时");

    private final ResultModel model = new ResultModel();
    private final ResultLayout layout = new ResultLayout();
    private final Choreo choreo = new Choreo();
    private final FxPool fx = new FxPool();
    private final ResultKit kit;
    private final ScorePanel score;
    private final GradeStamp stamp;
    private final CornerHud hud;
    private final JacketBlock jacket;
    private final StatsBlock stats;
    private final Button retry, cont, apply, calibrate, cancel;
    private final GlitchState titleState, stampState, alertState, g3State;
    private final Spring sheet = new Spring(Spring.Preset.SHEET, 0f);
    private final Spring popover = new Spring(Spring.Preset.SNAPPY, 0f);
    private final Color accent = new Color(), stampColor = new Color(), tmp = new Color(), tmp2 = new Color();
    private final float[] kickT = new float[MAX_KICKS], kickAmp = new float[MAX_KICKS], kickDur = new float[MAX_KICKS];
    private int kickNext;

    private PlayResult result;
    private SongMeta song;
    /** Image hook (DESIGN §2.8), held from the kit's ArtCache from the first present until dispose. */
    private static final String RESULT_PAGE = "ResultPage.png";
    private Texture resultPage;
    private boolean resultPageTried;
    /** The jacket wash of the song (owned by the library). */
    private Texture aura;
    private boolean presented, reduceMotion, idleArmed, sheetOpen, popOpen, hintUsed, musicStopping;
    /**
     * What {@link #popOpen} shows: the shell breakdown, the score breakdown (DESIGN §18.2 [P1]), EARLY / LATE
     * RESONANCE beside PERFECT or the depth breakdown (round 7).
     */
    private static final int POP_SHELLS = 0, POP_SCORE = 1, POP_PERFECT = 2, POP_DEPTH = 3;
    /** Which popover {@link #popOpen} shows (one of the {@code POP_} kinds). */
    private int popKind;
    /** Where the score popover points (x of the tap, clamped to the score row). */
    private float popScoreX;
    /** Width of the depth breakdown (its longest row). */
    private float depthPopW;
    private float nextStampBurst = -1f;
    private float restampT = -1f, duckRestoreReal = -1f, lastBubbleT = -1f;
    /** UNLOCKED alert lines: the option (or how many), and the list when there are several. */
    private String unlockLine2 = "", unlockLine3 = "";
    private int tapPointer = -1;
    private float tapX, tapY, rippleX, rippleY;
    private boolean tapMoved, tapOnButton;
    private float hintX0, hintY0, hintX1, hintY1;
    private boolean hintVisible;

    /** Creates the screen once (Main caches it). */
    public ResultScreen(final Main game) {
        super(game);
        kit = new ResultKit(game.draw(), game.fonts(), game.glitch());
        score = new ScorePanel(kit);
        stamp = new GradeStamp(kit);
        hud = new CornerHud(kit, game.avatars());
        jacket = new JacketBlock(kit);
        stats = new StatsBlock(kit);
        // RETRY / CONTINUE: bodies via drawBody, labels by the screen (shared UI_BOLD raster)
        retry = new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, RETRY);
        retry.setOnTap(new Runnable() {
            @Override
            public void run() {
                retryNow();
            }
        });
        cont = new Button(game.draw(), game.fonts(), Button.Variant.PRIMARY, Lang.of("CONTINUE", "继续"));
        cont.setOnTap(new Runnable() {
            @Override
            public void run() {
                continueToMap();
            }
        });
        apply = new Button(game.draw(), game.fonts(), Button.Variant.PRIMARY, Lang.of("APPLY", "应用"));
        apply.setOnTap(new Runnable() {
            @Override
            public void run() {
                applyOffset();
            }
        });
        calibrate = new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, Lang.of("CALIBRATE", "校准"));
        calibrate.setOnTap(new Runnable() {
            @Override
            public void run() {
                openCalibration();
            }
        });
        cancel = new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, Lang.of("CANCEL", "取消"));
        cancel.setOnTap(new Runnable() {
            @Override
            public void run() {
                closeSheet();
            }
        });
        titleState = game.glitch().obtainState();
        stampState = game.glitch().obtainState();
        alertState = game.glitch().obtainState();
        g3State = game.glitch().obtainState();
    }

    // ------------------------------------------------------------------ present
    /**
     * Shows {@code r} with its record {@code o} (called while covered). Builds every string, the
     * timeline, the jacket aura and the layout once. Main never passes a null outcome: autoplay
     * and unrecordable runs have {@code o.recorded() == false}; the tutorial has
     * {@code o.tutorial() == true} (DESIGN §6.6).
     */
    public void present(PlayResult r, RecordOutcome o) {
        if (o == null) o = RecordOutcome.builder().recorded(false).tutorial(r.tutorial()).build();
        result = r;
        song = r.tutorial() ? game.library().tutorial() : game.library().song(r.songId());
        Profile p = game.profiles().active();
        DeviceSettings device = game.profiles().device();
        model.set(r, o, song, p, device);
        Settings s = p != null ? p.settings : null;
        reduceMotion = s != null && s.reduceMotion;
        choreo.plan(model);
        int nu = model.unlocks.size();
        if (nu <= 1) {
            unlockLine2 = nu == 1 ? model.unlocks.get(0) : "";
            unlockLine3 = "";
        } else {
            unlockLine2 = nu + " NEW AVATAR OPTIONS";
            StringBuilder ub = new StringBuilder(64);
            for (int i = 0; i < nu; i++) {
                String u = model.unlocks.get(i);
                // "NEW PATTERN · WAVES" -> "WAVES"
                ub.append(i == 0 ? "" : "  ·  ").append(u.substring(u.lastIndexOf('·') + 1).trim());
            }
            unlockLine3 = ub.toString();
        }
        int quality = device != null ? device.particleQuality : DeviceSettings.PARTICLES_HIGH;
        fx.setDensity(quality == DeviceSettings.PARTICLES_OFF ? 0f
            : (quality == DeviceSettings.PARTICLES_LOW ? 0.5f : 1f));
        fx.clear();
        titleState.reset();
        stampState.reset();
        alertState.reset();
        g3State.reset();
        sheet.snap(0f);
        popover.snap(0f);
        sheetOpen = false;
        popOpen = false;
        hintUsed = false;
        idleArmed = false;
        nextStampBurst = -1f;
        restampT = -1f;
        duckRestoreReal = -1f;
        lastBubbleT = -1f;
        kickNext = 0;
        for (int i = 0; i < MAX_KICKS; i++) kickT[i] = -100f;
        tapPointer = -1;
        retry.setLabel(model.tutorial ? REPLAY : RETRY);
        retry.cancel();
        cont.cancel();
        aura = game.library().wash(song); // the library's 16×16 jacket wash (shared with gameplay)
        jacket.setJacket(song != null ? game.library().jacket(song) : null, song != null ? song.jacket16x9() : null,
            aura);
        if (!resultPageTried && s != null && s.backgroundArt) {
            resultPageTried = true;
            resultPage = game.art().acquire(RESULT_PAGE);
        }
        relayout();
        warmText();
        presented = true;
    }

    /** True once the whole choreography has played (idle state); for tests and tooling. */
    public boolean isChoreographyDone() {
        return choreo.done();
    }

    private void relayout() {
        layout.compute();
        jacket.measure(model, layout);
        stamp.measure(model, layout);
        stats.measure(model, layout);
        hud.measure(model, layout);
        // the labels shrink with the buttons (96 high on the blueprint, the standard 112 before round 7)
        float lk = layout.contH / Button.standardHeight();
        retry.setLabelCap((model.zh ? ZH_BUTTON_CAP : Button.LABEL_CAP) * lk);
        cont.setLabelCap((model.zh ? ZH_BUTTON_CAP : Button.LABEL_CAP) * lk);
        depthPopW = rowsWidth(model.depthRows, model.depthRowValues);
        prewarm();
        if (choreo.started() && t() >= Choreo.IMPACT_T && !model.tutorial) {
            game.backdrop().setFocus(layout.stampX, layout.stampY);
        }
        if (isShown()) anchorToasts();
    }

    /** Toasts (unlocks skipped past, the applied offset) hang under the title band, clear of the score. */
    private void anchorToasts() {
        game.toasts().setAnchorTop(layout.bandY0 - 24f * layout.s);
    }

    /** Width of a breakdown popover for {@code labels} and {@code values} (UI 18 labels, NUMBER 20 values). */
    private float rowsWidth(java.util.List<String> labels, java.util.List<String> values) {
        float lw = 0f, vw = 0f;
        for (int i = 0; i < labels.size(); i++) {
            lw = Math.max(lw, kit.text.width(Fonts.Role.UI, 18f, labels.get(i)));
            vw = Math.max(vw, kit.text.fixedWidth(20f, values.get(i)));
        }
        return Math.max(360f, 22f + lw + 40f + vw + 22f);
    }

    private float t() {
        return choreo.t();
    }

    @Override
    public void dispose() {
        aura = null; // the library's
        if (resultPage != null) game.art().release(RESULT_PAGE);
        resultPage = null;
        game.glitch().freeState(titleState);
        game.glitch().freeState(stampState);
        game.glitch().freeState(alertState);
        game.glitch().freeState(g3State);
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onShow() {
        Backdrop b = game.backdrop();
        b.setMode(Backdrop.Mode.MENU);
        b.setCaustics(false);
        b.setIntensity(1f);
        b.setShaftScale(1f);
        b.setDepthImmediate(game.gameplayDepth(song, model.difficulty));
        Profile p = game.profiles().active();
        Settings s = p != null ? p.settings : null;
        float art = s != null && s.backgroundArt ? 0.20f * s.artOpacity : 0f;
        b.setImage(art > 0f ? resultPage : null, art);
        b.setImageParallax(0f);
        b.setWash(aura, aura != null ? 0.35f : 0f, song != null ? song.jacket16x9() : null);
        b.clearFocus();
        game.audio().playResult(song);
        game.audio().duckResult(0f, 0f);
        musicStopping = false;
        if (presented) {
            prewarm();
            game.avatars().region(batch, model.avatar);
            anchorToasts();
        }
    }

    /**
     * Generates every font page the choreography can touch, at the sizes actually drawn (after
     * the measures above), so nothing is rasterised mid-animation (DESIGN §1.3). Most text is
     * drawn from the {@link org.kelvinizer.screens.result.TextQueue} rasters; the rest are the
     * glitch faces of the score land, the stamp bursts and the title burst, the alert words, the
     * autoplay watermark and the sheet buttons. Idempotent and allocation-free when warm.
     */
    private void prewarm() {
        Fonts f = game.fonts();
        kit.text.prewarm();
        float sc = layout.scoreCap;
        f.prewarm(Fonts.Role.NUMBER, sc, Fonts.GlyphSet.DIGITS);
        f.prewarm(Fonts.Role.DISPLAY, sc, Fonts.GlyphSet.DIGITS);
        f.prewarm(Fonts.Role.DISPLAY_SOFT, sc, Fonts.GlyphSet.DIGITS);
        if (model.grade != null && !model.tutorial) {
            float lc = stamp.letterCap();
            f.prewarm(Fonts.Role.DISPLAY, lc, Fonts.GlyphSet.GRADE);
            f.prewarm(Fonts.Role.NUMBER, lc, Fonts.GlyphSet.GRADE);
            f.prewarm(Fonts.Role.DISPLAY_SOFT, lc, Fonts.GlyphSet.GRADE);
        }
        f.prewarm(Fonts.Role.UI_BOLD, jacket.titleCap(), Fonts.GlyphSet.FULL);
        f.prewarm(Fonts.Role.RUBIK, 40f, Fonts.GlyphSet.FULL);
        f.prewarm(Fonts.Role.RUBIK, 48f, Fonts.GlyphSet.FULL);
        f.prewarm(Fonts.Role.UI_BOLD, Button.LABEL_CAP, Fonts.GlyphSet.FULL);
        if (model.autoplay) f.prewarm(Fonts.Role.DISPLAY_OUTLINE, jacket.watermarkCap(), Fonts.GlyphSet.WORDS);
        if (song == null || song.jacket() == null) {
            f.prewarm(Fonts.Role.DISPLAY_OUTLINE, JacketBlock.fallbackCap(layout), Fonts.GlyphSet.LOGO);
        }
    }

    /**
     * Lays out every model string once through the shared rasters, so glyphs that route to the
     * incremental CJK font (titles, names, the 中文 buttons, sheet and shell breakdown) are
     * rasterised while the ASCENT covers the screen.
     */
    private void warmText() {
        TextQueue q = kit.text;
        q.warm(Fonts.Role.UI_BOLD, jacket.titleCap(), model.title);
        q.warm(Fonts.Role.UI, layout.artistCap, model.artist);
        q.warm(Fonts.Role.UI_BOLD, layout.chipCap, model.chipText);
        q.warm(Fonts.Role.UI, 18f, model.hintText);
        q.warm(Fonts.Role.UI, 18f, model.stripNote);
        q.warm(Fonts.Role.UI, 24f, model.hintQuestion);
        if (model.zh) {
            // the Chinese labels of the buttons, the offset sheet and the shell and score breakdowns
            q.warm(Fonts.Role.UI_BOLD, retry.labelCap(), retry.label());
            q.warm(Fonts.Role.UI_BOLD, cont.labelCap(), cont.label());
            q.warm(Fonts.Role.UI_BOLD, Button.LABEL_CAP, apply.label());
            q.warm(Fonts.Role.UI_BOLD, Button.LABEL_CAP, calibrate.label());
            q.warm(Fonts.Role.UI_BOLD, Button.LABEL_CAP, cancel.label());
            q.warm(Fonts.Role.UI_LABEL, 18f, OFFSET_TITLE);
            for (int i = 0; i < model.shellRows.size(); i++) q.warm(Fonts.Role.UI, 18f, model.shellRows.get(i));
            for (int i = 0; i < model.depthRows.size(); i++) q.warm(Fonts.Role.UI, 18f, model.depthRows.get(i));
            q.warm(Fonts.Role.UI_BOLD, 18f, SHELLS_TOTAL);
            for (CharSequence label : BREAKDOWN_LABELS) q.warm(Fonts.Role.UI, 18f, label);
            q.warm(Fonts.Role.UI_BOLD, 18f, BREAKDOWN_TOTAL);
        }
        hud.warm(model);
        for (int i = 0; i < model.unlocks.size(); i++) q.warm(Fonts.Role.UI_LABEL, 18f, model.unlocks.get(i));
        if (model.title != null && game.fonts().needsCjk(Fonts.Role.UI_BOLD, model.title)) {
            // the title's G2 burst draws through GlitchText at its own cap
            game.fonts().width(Fonts.Role.UI_BOLD, jacket.titleCap(), model.title);
        }
    }

    @Override
    protected void onHide() {
        // CONTINUE / CALIBRATE / RETRY already started the fade with their transition; a second
        // stop would restart it (DESIGN §6.7: 450 ms with the IRIS, 300 ms on RETRY)
        if (!musicStopping) game.audio().stopSlot(AudioService.Slot.RESULT, 0.45f);
        musicStopping = true;
        Backdrop b = game.backdrop();
        b.clearFocus();
        b.setImage(null, 0f);
        b.setWash(null, 0f);
        retry.cancel();
        cont.cancel();
        tapPointer = -1;
        game.toasts().resetAnchor();
    }

    @Override
    protected void onResize(int width, int height) {
        if (presented) relayout();
    }

    // ------------------------------------------------------------------ update
    @Override
    protected void draw(float delta) {
        update(delta);
        paint(delta);
    }

    private void update(float dt) {
        if (!presented) return;
        if (layout.generation != Ui.generation()) relayout();
        if (!choreo.started()) tryStart();
        choreo.advance(dt);
        float t = choreo.t();
        stamp.color(model, t, stampColor);
        accent.set(game.backdrop().accent());
        handleEvents(t);
        // bubbles off the depth while it rolls
        if (CornerHud.rolling(choreo, t) && !choreo.skipping() && t - lastBubbleT > 0.07f) {
            lastBubbleT = t;
            fx.bubbles(hud.depthCenterX(), hud.depthCenterY(), 8f, 1, accent);
        }
        if (duckRestoreReal >= 0f && choreo.real() >= duckRestoreReal) {
            duckRestoreReal = -1f;
            game.audio().duckResult(1f, 0.3f);
        }
        fx.update(dt);
        sheet.update(dt);
        popover.update(dt);
        layoutButtons(t);
        retry.update(dt);
        cont.update(dt);
        apply.update(dt);
        calibrate.update(dt);
        cancel.update(dt);
        choreo.endFrame();
    }

    /** Starts the timeline when the ASCENT starts opening (or at once without a transition). */
    private void tryStart() {
        TransitionLayer tl = game.transitions();
        if (tl.phase() == TransitionLayer.Phase.OPENING) {
            choreo.start(tl.phaseProgress() * tl.openDuration());
        } else if (!tl.isBusy()) {
            choreo.start(0f);
        }
        if (choreo.started()) game.audio().duckResult(1f, AudioService.RESULT_FADE_IN);
    }

    private void handleEvents(float t) {
        boolean silent = choreo.silent();
        boolean d = "F".equals(model.grade);
        if (choreo.due(Choreo.EV_TITLE) && !silent) titleState.trigger(GlitchText.Level.G2, t);
        if (choreo.due(Choreo.EV_LAND) && !silent) kick(t, 4f, 0.14f);
        if (choreo.due(Choreo.EV_IMPACT)) {
            stampState.ambient(model.autoplay ? GlitchText.Level.G0 : GlitchText.Level.G1);
            if (!silent) {
                kick(t, model.autoplay ? 4f : 8f, 0.16f);
                stampState.trigger(d ? GlitchText.Level.G2_RED : GlitchText.Level.G2, t);
                if (!model.autoplay && !d) {
                    fx.burstSparks(layout.stampX, layout.stampY, layout.stampR * 0.95f, 12, 1100f, stampColor);
                }
                Haptics.medium();
            }
            if (!model.autoplay) game.backdrop().setFocus(layout.stampX, layout.stampY);
        }
        if (choreo.due(Choreo.EV_SHIMMER) && model.rPlus && !silent) {
            float r = layout.stampR;
            fx.motes(layout.stampX - r * 1.1f, layout.stampY - r, r * 2.2f, r * 1.4f, 40, Theme.IRIDESCENT_A,
                Theme.IRIDESCENT_B, Theme.IRIDESCENT_C);
        }
        choreo.due(Choreo.EV_STRIP);
        choreo.due(Choreo.EV_DEPTH);
        choreo.due(Choreo.EV_SHELLS);
        if (choreo.due(Choreo.EV_UNLOCK)) {
            if (!silent) {
                float m = 40f * layout.s;
                fx.burstDots(layout.charX0 + m, layout.charX1 - m, (layout.charY0 + layout.charY1) * 0.5f, 24,
                    Theme.GOLD);
                Haptics.heavy();
                game.audio().duckResult(0.6f, 0.08f);
                duckRestoreReal = choreo.real() + 0.6f;
                if (!reduceMotion) g3State.trigger(GlitchText.Level.G3, t);
                alertState.trigger(GlitchText.Level.G3, t);
            }
            if (silent || choreo.skipping()) {
                // the UNLOCKED alert is skipped past: keep the news as toasts
                for (int u = 0; u < model.unlocks.size(); u++) {
                    game.toasts().show(UNLOCKED + " · " + model.unlocks.get(u), Theme.GOLD);
                }
            }
        }
        choreo.due(Choreo.EV_BUTTONS);
        if (choreo.done() && !idleArmed) {
            idleArmed = true;
            nextStampBurst = t + STAMP_IDLE_INTERVAL;
        }
        // idle G2 on the stamp every 2.6–3.9 s (§6.3 idle); scheduled here because the resting
        // letter is drawn from the page-grouped glyph path, not by GlitchText
        if (idleArmed && !model.autoplay && !model.tutorial && model.grade != null && nextStampBurst >= 0f
            && t >= nextStampBurst && !reduceMotion) {
            stampState.trigger("F".equals(model.grade) ? GlitchText.Level.G2_RED : GlitchText.Level.G2, t);
            nextStampBurst = t + STAMP_IDLE_INTERVAL * (0.8f + 0.4f * hash01(t));
        }
    }

    /** Deterministic 0..1 jitter for idle intervals (no Random, so screenshots stay stable). */
    private static float hash01(float x) {
        float v = (float) Math.sin(x * 12.9898f) * 43758.547f;
        return v - (float) Math.floor(v);
    }

    private void kick(float t, float amp, float dur) {
        if (reduceMotion) return;
        int i = kickNext;
        kickNext = (kickNext + 1) % MAX_KICKS;
        kickT[i] = t;
        kickAmp[i] = amp;
        kickDur[i] = dur;
    }

    /** Content offset (negative = down) from the running camera kicks (damped sine). */
    private float kickOffset(float t) {
        float y = 0f;
        for (int i = 0; i < MAX_KICKS; i++) {
            float u = (t - kickT[i]) / kickDur[i];
            if (u < 0f || u >= 1f) continue;
            y -= kickAmp[i] * (float) Math.sin(u * Math.PI * 3f) * (1f - u);
        }
        return y;
    }

    private void layoutButtons(float t) {
        float bt = choreo.buttonsT();
        float p0 = Anim.progress(t, bt, Choreo.BUTTONS_D);
        float p1 = Anim.progress(t, bt + Choreo.BUTTONS_STAGGER, Choreo.BUTTONS_D);
        float o0 = reduceMotion ? 0f : -40f * (1f - Anim.SWING_OUT_14.apply(p0));
        float o1 = reduceMotion ? 0f : -40f * (1f - Anim.SWING_OUT_14.apply(p1));
        retry.setBounds(layout.retryX, layout.retryY + o0, layout.retryW, layout.retryH);
        cont.setBounds(layout.contX, layout.contY + o1, layout.contW, layout.contH);
        retry.setVisible(choreo.started() && t >= bt);
        cont.setVisible(choreo.started() && t >= bt + Choreo.BUTTONS_STAGGER * 0.5f);
        // the confirm sheet
        float sw = Math.min(1040f, Ui.safeRight() - Ui.safeLeft() - 48f);
        float bw = Math.min(280f, (sw - 96f - 48f) / 3f), bh = Button.standardHeight();
        float sy = sheetY();
        float cx = (Ui.safeLeft() + Ui.safeRight()) * 0.5f;
        float by = sy + 36f;
        apply.setBounds(cx + bw * 0.5f + 24f, by, bw, bh);
        calibrate.setBounds(cx - bw * 0.5f, by, bw, bh);
        cancel.setBounds(cx - bw * 1.5f - 24f, by, bw, bh);
        boolean sv = sheet.value() > 0.02f;
        apply.setVisible(sv);
        calibrate.setVisible(sv);
        cancel.setVisible(sv);
    }

    private float sheetH() {
        return 340f;
    }

    private float sheetY() {
        float v = Math.max(0f, Math.min(1.05f, sheet.value()));
        return Ui.safeBottom() + 24f - (1f - v) * (sheetH() + 120f);
    }

    // ------------------------------------------------------------------ render
    private void paint(float delta) {
        batch.begin();
        game.backdrop().render(batch, viewport, delta);
        if (!presented || !choreo.started()) {
            batch.end();
            return;
        }
        float t = choreo.t();
        float time = choreo.real();
        float kx = reduceMotion ? 0f : game.glitch().screenJitterX(g3State, t);
        float ky = kickOffset(t);
        boolean shifted = kx != 0f || ky != 0f;
        if (shifted) {
            camera.position.x -= kx;
            camera.position.y -= ky;
            camera.update();
            batch.setProjectionMatrix(camera.combined);
        }

        // shapes draw at once; text, glyph cells and images are recorded and drawn grouped by
        // texture page in flush() (DESIGN §14 draw-call budget)
        kit.text.begin();
        jacket.drawBand(layout, t, 1f);
        jacket.drawJacket(model, layout, t, accent, reduceMotion, 1f);
        jacket.drawWatermark(model, layout, t, 1f);
        jacket.drawTitle(model, layout, t, titleState, reduceMotion, 1f);
        jacket.drawChip(model, layout, t, reduceMotion, 1f);
        score.drawScore(model, layout, t, accent, 1f);
        score.drawBest(model, layout, t, accent, 1f);
        stats.drawRows(model, layout, t, 1f);
        drawHint(t);
        stats.drawAutoplay(model, layout, t, 1f);
        stamp.drawFlash(model, layout, t, 1f);
        stamp.draw(model, layout, t, stampState, restampT, 1f);
        hud.draw(model, layout, choreo, t, time, accent, reduceMotion, 1f);
        drawButtons(t);
        kit.text.flush();
        // a title past 50 characters scrolls inside the band
        jacket.drawMarquee(model, layout, t, 1f);

        kit.additive();
        fx.draw(batch, game.draw(), 1f);
        stamp.drawRings(model, layout, t, restampT, 1f);
        kit.normal();

        drawAlerts(t);
        if (!reduceMotion) {
            game.glitch().drawScreenTears(batch, g3State, t, Ui.left(), Ui.bottom(), Ui.worldW(), Ui.worldH());
        }

        if (shifted) {
            camera.position.x += kx;
            camera.position.y += ky;
            camera.update();
            batch.setProjectionMatrix(camera.combined);
        }
        drawPopover();
        drawSheet();
        batch.end();
    }

    private void drawButtons(float t) {
        if (!retry.isVisible() && !cont.isVisible()) return;
        float bt = choreo.buttonsT();
        float a0 = Math.min(1f, Anim.progress(t, bt, Choreo.BUTTONS_D) * 2.5f);
        float a1 = Math.min(1f, Anim.progress(t, bt + Choreo.BUTTONS_STAGGER, Choreo.BUTTONS_D) * 2.5f);
        if (choreo.done()) {
            // CONTINUE glow α .3 ↔ .6 at 0.8 Hz, in normal blend (no blend switch at rest)
            float g = 0.3f + 0.3f * (0.5f + 0.5f * (float) Math.sin((t - choreo.end()) * Math.PI * 2f * 0.8f));
            game.draw().sprite(game.draw().softDot(), cont.centerX(), cont.centerY(), cont.width() + 200f,
                cont.height() + 170f, Theme.BTN_TOP, g * 0.38f);
        }
        retry.drawBody(batch, a0);
        cont.drawBody(batch, a1);
        buttonLabel(retry, a0);
        buttonLabel(cont, a1);
    }

    /** A button's label through the shared UI_BOLD raster, where {@link Button} lays it out. */
    private void buttonLabel(Button b, float alpha) {
        if (!b.isVisible()) return;
        float a = alpha * b.alphaMul();
        if (a <= 0.002f) return;
        kit.text.textFit(Fonts.Role.UI_BOLD, b.labelCap() * b.labelScale(), b.label(), b.labelX(), b.labelY(),
            Align.center | Fonts.VCENTER, b.labelMaxWidth(), 0.72f, b.labelColor(), a);
    }

    /**
     * The offset hint link (§6.5) under the early / late line, left-aligned at x 96 (Kelvin 2026-10-19 06:04
     * 「如果说average太高了就在early late avg下面显示建议调整」; the same text and style as before). Autoplay and the
     * tutorial show their note there instead when there is no hint (the profile strip's until round 7).
     */
    private void drawHint(float t) {
        hintVisible = false;
        float a = Anim.progress(t, Choreo.EL_T + Choreo.EL_D + 0.3f, 0.3f);
        if (a <= 0.01f) return;
        float x = layout.hintX, y = layout.hintBase, cap = layout.hintCap;
        if (model.hasHint && !hintUsed) {
            float w = kit.text.textFit(Fonts.Role.UI, cap, model.hintText, x, y, Align.left, layout.hintMaxW, 0.7f,
                accent, a);
            game.draw().rect(x, y - layout.hintLine, w, 1.5f, accent, 0.6f * a);
            float pad = Math.max(0f, (Ui.touchTarget() - 24f) * 0.5f);
            hintX0 = x - 8f;
            hintX1 = x + w + 8f;
            hintY0 = y - 8f - pad;
            hintY1 = y + cap + pad;
            hintVisible = a > 0.5f;
        } else if (!model.stripNote.isEmpty()) {
            kit.text.textFit(Fonts.Role.UI, cap, model.stripNote, x, y, Align.left, layout.hintMaxW, 0.7f,
                model.autoplay ? Theme.AUTO : Theme.INK2, a);
        }
    }

    /**
     * UNLOCKED (DESIGN §6.3, §11): an opaque alert in the middle of the character area, where it covers nothing
     * (round 7), naming the avatar option the new depth unlocked (or how many, with their names under it).
     */
    private void drawAlerts(float t) {
        float ut = choreo.unlockT();
        if (ut < 0f || !model.unlockUp) return;
        float w = Math.min(600f * layout.s, layout.charX1 - layout.charX0);
        float cx = (layout.charX0 + layout.charX1) * 0.5f, cy = (layout.charY0 + layout.charY1) * 0.5f;
        drawAlert(UNLOCKED, 48f, unlockLine2, unlockLine3, Theme.GOLD, ut, ut + ALERT_UNLOCK_D, cx, cy, w,
            180f * layout.s, t);
    }

    /** One alert box {@code w} × {@code h}, centred on (cx, cy); text centred in it. */
    private void drawAlert(String line1, float cap, String line2, String line3, Color col, float start, float end,
                           float cx, float cy, float w, float h, float t) {
        if (t < start || t >= end) return;
        float p = Anim.progress(t, start, Anim.POP);
        float s = Math.max(0.001f, Anim.POP_EASE.apply(p));
        float a = Math.min(1f, p * 4f) * (1f - Anim.progress(t, end - 0.25f, 0.25f));
        TextQueue q = kit.text;
        boolean third = line3 != null && !line3.isEmpty();
        // the text block (headline, value, optional unlock line), centred vertically in the box
        float th = Math.min(h, cap + 26f + 56f + (third ? 34f : 0f));
        kit.push(cx, cy, 0f, s, s);
        Panel.shadow(game.draw(), -w * 0.5f, -h * 0.5f, w, h, a);
        tmp2.set(Theme.PANEL_HI);
        tmp2.a = 1f;
        game.draw().roundRect(-w * 0.5f, -h * 0.5f, w, h, 20f, tmp2, a);
        game.draw().roundRect(-w * 0.5f, -h * 0.5f, w, h, 20f, col, 0.10f * a);
        game.draw().roundRectStroke(-w * 0.5f, -h * 0.5f, w, h, 20f, 2f, col, 0.9f * a);
        float top = th * 0.5f - 14f;
        if (alertState.isBursting(t)) {
            game.glitch().draw(batch, Fonts.Role.RUBIK, cap, line1, 0f, top, Align.center | Align.top, col, a,
                alertState, t);
        } else {
            q.text(Fonts.Role.RUBIK, cap, line1, 0f, top, Align.center | Align.top, col, a);
        }
        float l2 = -th * 0.5f + 20f + (third ? 34f : 0f);
        q.textFit(Fonts.Role.UI_BOLD, 26f, line2, 0f, l2, Align.center, w - 40f, 0.7f, Theme.INK1, a);
        if (third) {
            q.textFit(Fonts.Role.UI_LABEL, 18f, line3, 0f, -th * 0.5f + 18f, Align.center, w - 40f, 0.6f, col, a);
        }
        kit.pop();
    }

    private void drawPopover() {
        if (popKind == POP_SCORE) {
            drawScorePopover();
        } else if (popKind == POP_PERFECT) {
            drawPerfectTiming();
        } else if (popKind == POP_DEPTH) {
            drawRowsPopover(model.depthRows, model.depthRowValues, null, null, depthPopW, hud.depthCenterX(),
                hud.frameBottom(), accent, Theme.INK1);
        } else {
            drawRowsPopover(model.shellRows, model.shellRowValues, SHELLS_TOTAL, model.shellsText, 360f,
                hud.capsuleCenterX(), hud.capsuleBottom(), Theme.GOLD, Theme.GOLD);
        }
    }

    /**
     * A breakdown card hanging under ({@code pointX}, {@code under}) with its pointer up at it: one row per label
     * (UI 18, ink2) and value (NUMBER 20), then an optional total line. The shell breakdown hangs from the capsule
     * (no title since 17:22 「把"这次下潜获得的贝壳"这个小字删掉」), the depth breakdown from the player frame.
     */
    private void drawRowsPopover(java.util.List<String> labels, java.util.List<String> values, CharSequence total,
                                 CharSequence totalValue, float w, float pointX, float under, Color edge,
                                 Color valueColor) {
        float v = popover.value();
        int n = labels.size();
        if (v <= 0.01f || n == 0) return;
        float a = Math.min(1f, v);
        TextQueue q = kit.text;
        boolean sum = total != null;
        float rowH = 36f, h = 34f + (n - 1) * rowH + (sum ? rowH + 44f : 30f);
        float cx = Math.max(Ui.safeLeft() + w * 0.5f + 16f, Math.min(Ui.safeRight() - w * 0.5f - 16f, pointX));
        float yTop = under - 14f;
        float s = 0.9f + 0.1f * a;
        kit.push(cx, yTop, 0f, s, s);
        float lx = -w * 0.5f, by = -h;
        // opaque card so the title band under it does not show through
        Panel.shadow(game.draw(), lx, by, w, h, a);
        Panel.cardSolid(game.draw(), lx, by, w, h, 18f, a);
        game.draw().roundRectStroke(lx, by, w, h, 18f, 1.5f, edge, 0.5f * a);
        float px = Math.max(lx + 30f, Math.min(lx + w - 30f, pointX - cx));
        kit.triangle(px - 11f, -1f, px + 11f, -1f, px, 10f, edge, 0.5f * a);
        float y = -34f;
        for (int i = 0; i < n; i++) {
            q.textFit(Fonts.Role.UI, 18f, labels.get(i), lx + 22f, y - i * rowH, Align.left | Fonts.VCENTER,
                w * 0.5f, 0.8f, Theme.INK2, a);
            q.fixed(20f, values.get(i), lx + w - 22f, y - i * rowH, Align.right | Fonts.VCENTER, valueColor, a);
        }
        if (sum) {
            float ty = by + 30f;
            game.draw().rect(lx + 22f, ty + 22f, w - 44f, 1f, Theme.STROKE, a);
            q.text(Fonts.Role.UI_BOLD, 18f, total, lx + 22f, ty, Align.left | Fonts.VCENTER, Theme.INK1, a);
            q.text(Fonts.Role.NUMBER, 22f, totalValue, lx + w - 22f, ty, Align.right | Fonts.VCENTER, valueColor, a);
        }
        kit.pop();
    }

    // English names (Kelvin 2026-10-18 17:26); 中文 ones since 20:34 「中文分别是"准度分"，"共鸣链"，"完美共鸣"，"总计"」
    private static final CharSequence[] BREAKDOWN_LABELS = {Lang.of("ACCURACY SCORE", "准度分"),
        Lang.of("RESONANCE CHAIN", "共鸣链"), Lang.of("PERFECT RESONANCE", "完美共鸣")};
    private static final CharSequence BREAKDOWN_TOTAL = Lang.of("TOTAL", "总计");

    /**
     * Score breakdown popover (DESIGN §18.2 [P1]): {@code ACCURACY SCORE · 88,123,456},
     * {@code RESONANCE CHAIN · 9,812}, {@code PERFECT RESONANCE +812} and the TOTAL (准度分 / 共鸣链 / 完美共鸣 /
     * 总计 in 中文), hanging under the score with a pointer at the tapped digit (standing over it when there is no
     * room under it, as with three columns); no title since 17:26 (「把"分数"这个小字去掉」).
     */
    private void drawScorePopover() {
        float v = popover.value();
        if (v <= 0.01f) return;
        float a = Math.min(1f, v);
        TextQueue q = kit.text;
        float w = 500f, rowH = 38f, h = 21f + 3 * rowH + 50f;
        float left = score.scoreLeft(model, layout);
        float right = left + score.scoreWidth(model, layout.scoreCap);
        float px0 = Math.max(left, Math.min(right, popScoreX));
        float cx = Math.max(Ui.safeLeft() + w * 0.5f + 16f, Math.min(Ui.safeRight() - w * 0.5f - 16f,
            Math.max(px0, left + w * 0.5f)));
        float yTop = layout.scoreBase - 0.113f * layout.scoreCap - 14f;
        boolean up = yTop - h < Ui.safeBottom();
        float s = 0.9f + 0.1f * a;
        kit.push(cx, up ? layout.scoreBase + layout.scoreCap + 14f : yTop, 0f, s, s);
        float lx = -w * 0.5f, by = up ? 0f : -h;
        Panel.shadow(game.draw(), lx, by, w, h, a);
        Panel.cardSolid(game.draw(), lx, by, w, h, 18f, a);
        game.draw().roundRectStroke(lx, by, w, h, 18f, 1.5f, accent, 0.5f * a);
        float px = Math.max(lx + 30f, Math.min(lx + w - 30f, px0 - cx));
        if (up) kit.triangle(px - 11f, 1f, px + 11f, 1f, px, -10f, accent, 0.5f * a);
        else kit.triangle(px - 11f, -1f, px + 11f, -1f, px, 10f, accent, 0.5f * a);
        float y = by + h - 21f - rowH * 0.5f + 6f;
        for (int i = 0; i < 3; i++) {
            float ry = y - i * rowH;
            String value = i == 0 ? model.breakdownAccuracy : i == 1 ? model.breakdownChain : model.breakdownPerfect;
            q.text(Fonts.Role.UI, 18f, BREAKDOWN_LABELS[i], lx + 22f, ry, Align.left | Fonts.VCENTER,
                i == 2 ? Theme.RESONANCE : Theme.INK2, a);
            q.fixed(20f, value, lx + w - 22f, ry, Align.right | Fonts.VCENTER,
                i == 2 ? Theme.RESONANCE : Theme.INK1, a);
        }
        float ty = by + 30f;
        game.draw().rect(lx + 22f, ty + 22f, w - 44f, 1f, Theme.STROKE, a);
        q.text(Fonts.Role.UI_BOLD, 18f, BREAKDOWN_TOTAL, lx + 22f, ty, Align.left | Fonts.VCENTER, Theme.INK1, a);
        q.digits(22f, model.score, true, lx + w - 22f, ty, Align.right | Fonts.VCENTER, Theme.INK1, a);
        kit.pop();
    }

    /**
     * EARLY / LATE RESONANCE (Kelvin, 2026-10-14): tapping {@code PERFECT n} under RESONANCE slides
     * {@code EARLY n · LATE n} out to its right in the same font. They count the RESONANCE hits that are not PERFECT
     * (33–67 ms off) by sign, so PERFECT + EARLY + LATE = RESONANCE. Any tap hides them again.
     */
    private void drawPerfectTiming() {
        float v = popover.value();
        if (v <= 0.01f) return;
        float a = Math.min(1f, v);
        TextQueue q = kit.text;
        float cap = layout.perfectCap, sc = layout.rowK;
        float x = layout.rowLabelX + stats.perfectWidth(model, layout) + 14f * sc - 16f * sc * (1f - a);
        float y = layout.perfectBase;
        x += q.text(Fonts.Role.UI_LABEL, cap, EARLY, x, y, Align.left, Theme.EARLY, a) + 8f * sc;
        x += q.text(Fonts.Role.UI_LABEL, cap, model.earlyResonanceText, x, y, Align.left, Theme.INK2, a) + 12f * sc;
        x += q.text(Fonts.Role.UI_LABEL, cap, "·", x, y, Align.left, Theme.INK3, a) + 12f * sc;
        x += q.text(Fonts.Role.UI_LABEL, cap, LATE, x, y, Align.left, Theme.LATE, a) + 8f * sc;
        q.text(Fonts.Role.UI_LABEL, cap, model.lateResonanceText, x, y, Align.left, Theme.INK2, a);
    }

    /** True when (x, y) is on {@code PERFECT n} under RESONANCE (grown to a touch target). */
    private boolean hitPerfect(float x, float y) {
        if (model.totalNotes <= 0) return false;
        float cap = layout.perfectCap;
        float left = layout.rowLabelX, right = left + stats.perfectWidth(model, layout);
        float grow = Math.max(0f, (Ui.touchTarget() - cap) * 0.5f);
        float y0 = layout.perfectBase - grow, y1 = layout.perfectBase + cap + grow;
        return x >= left - 12f && x <= right + 12f && y >= y0 && y <= y1;
    }

    /** Height halfway between the hint's top and the early / late line's baseline (the hint's tap area ends there). */
    private float hintSplit() {
        return (layout.earlyLateBase + layout.hintBase + layout.hintCap) * 0.5f;
    }

    /** True when (x, y) is on the score row (grown to a touch target). */
    private boolean hitScore(float x, float y) {
        if (model.totalNotes <= 0) return false;
        float left = score.scoreLeft(model, layout);
        float right = left + score.scoreWidth(model, layout.scoreCap);
        float grow = Math.max(0f, (Ui.touchTarget() - layout.scoreCap) * 0.5f);
        float y0 = layout.scoreBase - 0.12f * layout.scoreCap - grow, y1 = layout.scoreBase + layout.scoreCap + grow;
        return x >= left - 12f && x <= right + 12f && y >= y0 && y <= y1;
    }

    private void drawSheet() {
        float v = sheet.value();
        if (v <= 0.01f) return;
        float a = Math.min(1f, Math.max(0f, v));
        Panel.scrim(game.draw(), Theme.SCRIM, a);
        float sw = Math.min(1040f, Ui.safeRight() - Ui.safeLeft() - 48f), sh = sheetH();
        float cx = (Ui.safeLeft() + Ui.safeRight()) * 0.5f;
        float y = sheetY();
        Panel.shadow(game.draw(), cx - sw * 0.5f, y, sw, sh, a);
        Panel.cardHi(game.draw(), cx - sw * 0.5f, y, sw, sh, 24f, a);
        TextQueue q = kit.text;
        q.text(Fonts.Role.UI_LABEL, 18f, OFFSET_TITLE, cx, y + sh - 34f, Align.center | Align.top, accent, a);
        q.textFit(Fonts.Role.UI, 24f, model.hintQuestion, cx, y + sh - 92f, Align.center | Align.top, sw - 96f, 0.6f,
            Theme.INK1, a);
        cancel.draw(batch, a);
        calibrate.draw(batch, a);
        apply.draw(batch, a);
    }

    // ------------------------------------------------------------------ actions
    private void retryNow() {
        if (result == null || game.transitions().isBusy()) return;
        // Main stops the result music (300 ms) when it starts the new run
        boolean started = model.tutorial ? game.startTutorial()
            : game.startGameplay(song, model.difficulty, model.autoplay, retry.centerX(), retry.centerY());
        if (started) musicStopping = true;
    }

    private void continueToMap() {
        if (musicStopping) return;
        if (game.transitionTo(game.map(), TransitionLayer.Style.IRIS, cont.centerX(), cont.centerY())) {
            // one 450 ms fade alongside the IRIS; onHide must not restart it
            musicStopping = true;
            game.audio().stopSlot(AudioService.Slot.RESULT, 0.45f);
        }
    }

    private void openSheet() {
        if (!model.hasHint || hintUsed) return;
        sheetOpen = true;
        sheet.setTarget(1f);
        popOpen = false;
        popover.setTarget(0f);
        Haptics.light();
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.setTarget(0f);
        apply.cancel();
        calibrate.cancel();
        cancel.cancel();
    }

    private void applyOffset() {
        DeviceSettings d = game.profiles().device();
        if (d != null) {
            d.setAudioOffsetMs(model.offsetNew);
            game.profiles().markDeviceDirty();
            game.toasts().show(model.offsetNew == 0 ? OFFSET_TITLE + " 0 ms"
                : OFFSET_TITLE + " " + (model.offsetNew > 0 ? "+" : "–") + Math.abs(model.offsetNew) + " ms", accent);
        }
        hintUsed = true;
        closeSheet();
    }

    private void openCalibration() {
        closeSheet();
        if (musicStopping) return;
        // DONE / BACK land on the map: a result cannot be resumed
        CalibrationScreen calibration = game.calibration();
        calibration.returnTo(game.map(), null);
        if (game.transitionTo(calibration, TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, CalibrationScreen.TITLE)) {
            musicStopping = true;
            game.audio().stopSlot(AudioService.Slot.RESULT, 0.45f);
        } else {
            calibration.setReturnTarget(null, null);
        }
    }

    private void restamp() {
        if (model.tutorial || model.grade == null) return;
        float t = choreo.t();
        restampT = t;
        kick(t + GradeStamp.RESTAMP_D, 4f, 0.14f);
        if (!model.autoplay) {
            stampState.trigger("F".equals(model.grade) ? GlitchText.Level.G2_RED : GlitchText.Level.G2,
                t + GradeStamp.RESTAMP_D);
            game.backdrop().sonar().ping(layout.stampX, layout.stampY, layout.stampR * 2.4f, 1.0f,
                stampColor, 0.35f, 1);
        }
        Haptics.light();
    }

    // ------------------------------------------------------------------ input
    private boolean buttonsLive() {
        return choreo.buttonsEnabled() && !sheetOpen;
    }

    @Override
    protected boolean onTouchDown(float x, float y, int pointer, int button) {
        if (!presented) return false;
        if (sheetOpen) {
            if (pointer == 0 || tapPointer < 0) {
                apply.touchDown(x, y, pointer);
                calibrate.touchDown(x, y, pointer);
                cancel.touchDown(x, y, pointer);
            }
            return true;
        }
        if (tapPointer >= 0) return true;
        tapPointer = pointer;
        tapX = x;
        tapY = y;
        rippleX = x;
        rippleY = y;
        tapMoved = false;
        tapOnButton = false;
        if (buttonsLive() && (retry.touchDown(x, y, pointer) || cont.touchDown(x, y, pointer))) tapOnButton = true;
        return true;
    }

    @Override
    protected boolean onTouchDragged(float x, float y, int pointer) {
        if (sheetOpen) {
            apply.touchDragged(x, y, pointer);
            calibrate.touchDragged(x, y, pointer);
            cancel.touchDragged(x, y, pointer);
            return true;
        }
        if (pointer != tapPointer) return true;
        if (tapOnButton) {
            retry.touchDragged(x, y, pointer);
            cont.touchDragged(x, y, pointer);
            return true;
        }
        float dx = x - tapX, dy = y - tapY;
        if (!tapMoved && dx * dx + dy * dy > Ui.slop() * Ui.slop()) {
            tapMoved = true;
            game.backdrop().touch(x, y);
        }
        if (tapMoved) {
            float rx = x - rippleX, ry = y - rippleY;
            if (rx * rx + ry * ry > 70f * 70f) {
                rippleX = x;
                rippleY = y;
                game.backdrop().touch(x, y);
                fx.bubbles(x, y, 18f, 2, accent);
            }
        }
        return true;
    }

    @Override
    protected boolean onTouchUp(float x, float y, int pointer, int button) {
        if (sheetOpen) {
            apply.touchUp(x, y, pointer);
            calibrate.touchUp(x, y, pointer);
            cancel.touchUp(x, y, pointer);
            if (pointer == tapPointer) tapPointer = -1;
            return true;
        }
        if (pointer != tapPointer) return true;
        tapPointer = -1;
        if (tapOnButton) {
            retry.touchUp(x, y, pointer);
            cont.touchUp(x, y, pointer);
            return true;
        }
        if (tapMoved) return true;
        if (popOpen) {
            popOpen = false;
            popover.setTarget(0f);
            return true;
        }
        if (!choreo.done()) {
            choreo.skip();
            return true;
        }
        idleTap(x, y);
        return true;
    }

    private void idleTap(float x, float y) {
        float dx = x - layout.stampX, dy = y - layout.stampY;
        float capGrow = Math.max(0f, (Ui.touchTarget() - layout.capsuleH) * 0.5f);
        if (model.grade != null && !model.tutorial
            && dx * dx + dy * dy <= (layout.stampR + 12f) * (layout.stampR + 12f)) {
            restamp();
        } else if (hitScore(x, y)) {
            popScoreX = x;
            openPopover(POP_SCORE);
        } else if (!model.depthRows.isEmpty() && hud.hitFrame(x, y, 0f)) {
            openPopover(POP_DEPTH);
        } else if (!model.shellRows.isEmpty() && hud.hitCapsule(x, y, capGrow)) {
            openPopover(POP_SHELLS);
        } else if (hitPerfect(x, y)) {
            openPopover(POP_PERFECT);
        } else if (hintVisible && x >= hintX0 && x <= hintX1 && y >= hintY0 && y <= Math.min(hintY1, hintSplit())) {
            openSheet();
        } else {
            game.backdrop().touch(x, y);
        }
    }

    private void openPopover(int kind) {
        popOpen = true;
        popKind = kind;
        popover.snap(0f);
        popover.setTarget(1f);
        Haptics.light();
    }

    @Override
    protected void onTouchCancel(int pointer) {
        retry.cancel();
        cont.cancel();
        apply.cancel();
        calibrate.cancel();
        cancel.cancel();
        if (pointer == tapPointer) tapPointer = -1;
    }

    @Override
    protected boolean onMouseMoved(float x, float y) {
        if (sheetOpen) return apply.mouseMoved(x, y) | calibrate.mouseMoved(x, y) | cancel.mouseMoved(x, y);
        if (!buttonsLive()) return false;
        return retry.mouseMoved(x, y) | cont.mouseMoved(x, y);
    }

    @Override
    protected boolean onKeyDown(int keycode) {
        if (!presented) return false;
        switch (keycode) {
            case Input.Keys.SPACE:
            case Input.Keys.ENTER:
            case Input.Keys.NUMPAD_ENTER:
                if (sheetOpen) return true;
                if (!choreo.done()) choreo.skip();
                else if (buttonsLive()) continueToMap();
                return true;
            case Input.Keys.R:
                if (sheetOpen) return true;
                if (buttonsLive()) retryNow();
                else if (!choreo.done()) choreo.skip();
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onBack() {
        if (!presented) return;
        if (sheetOpen) {
            closeSheet();
            return;
        }
        if (popOpen) {
            popOpen = false;
            popover.setTarget(0f);
            return;
        }
        if (!choreo.done()) {
            choreo.jumpToEnd();
            return;
        }
        if (buttonsLive()) continueToMap();
    }
}
