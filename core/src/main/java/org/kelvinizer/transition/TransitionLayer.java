package org.kelvinizer.transition;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Backdrop;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 全局转场，由 Main 持有、画在画面之上：
 * 菜单间的 CURTAIN 幕布、进游戏的 DIVE 下潜、到结算的 ASCENT 水帘、回地图的 IRIS 光圈。
 * 画面完全盖住时执行一次回调；后台加载可以用令牌让转场保持盖住，直到加载完成。
 *
 * <p>Global screen transition, owned by Main and drawn after the screen (ARCHITECTURE §4,
 * DESIGN §3.12 and Appendix A.8).
 *
 * <p><b>Flow.</b> {@code start(style, fx, fy, onCovered)} closes; the moment the screen is
 * fully covered {@code onCovered} runs once (Main loads and schedules its deferred
 * {@code setScreen} there; this class never switches screens itself). The open starts only when
 * all of these hold: at least {@link #MIN_COVER} seconds covered, at least two covered frames
 * (so a deferred {@code setScreen} has been applied and drawn once), and no outstanding
 * {@link #holdOpen()} for this transition.
 *
 * <p><b>Holding the open.</b> Async work keeps the cover up with a token:
 * <pre>
 *   final int token = transitions().holdOpen();      // before start() or inside onCovered
 *   worker: parse ...; Gdx.app.postRunnable(() -&gt; { apply(result); transitions().releaseOpen(token); });
 * </pre>
 * A hold taken while idle (or while the previous transition is already opening) applies to the
 * <em>next</em> transition that starts, so a loader can be kicked off before the close begins
 * (DESIGN §3.12, early chart parse). Each token belongs to exactly one transition: a late release
 * from an earlier transition's worker is ignored (and logged) instead of cancelling a hold of
 * the current one. Holds and releases are GL-thread only.
 *
 * <p><b>Styles.</b>
 * <ul>
 *   <li>{@link Style#CURTAIN} (menus): navy panels close 360 ms {@code pow3In}; a 2-unit
 *       {@code groundSig} seam with a 250 ms scan beam, the destination label
 *       ({@link #setCoveredLabel}) on the seam and a G3 tear at cover; open 400 ms
 *       {@code exp5Out}.</li>
 *   <li>{@link Style#DIVE} (X2c, Kelvin 2026-10-12 21:05; water look restored 2026-10-13): two
 *       water walls, pearl from the top and cyan from the bottom, with wavy foaming edges close
 *       350 ms {@code pow2In}; where they meet, spray flies off the seam and two ripple rings
 *       sweep out (it stays covered at least {@link #DIVE_MIN_COVER}, {@link #DIVE_ART_COVER} with
 *       cover art). With cover art the song's
 *       disc sits on the walls at the centre, then glides left once they close while the info
 *       rows pop in on its right, 0.1 s apart: MUSIC (artist), the title with the difficulty,
 *       CHART and ILLUSTRATION (Kelvin 2026-10-13 22:16); without art (RETRY, the tutorial)
 *       the closed walls carry nothing (Kelvin 2026-10-17 05:55: no status line or depth
 *       counter). The open: the walls part to y = 960
 *       and y = 120 (420 ms {@code exp5Out}), their edges glowing as the judgement lines, then
 *       fade out over 350 ms.</li>
 *   <li>{@link Style#ASCENT} (Kelvin 2026-10-13 06:57: the same as the DIVE; 06:22: nothing but
 *       water): the DIVE's water walls, spray, ripple rings and parting, without the art, the
 *       text and the judgement-line glow; it stays covered at least {@link #ASCENT_MIN_COVER}.</li>
 *   <li>{@link Style#IRIS}: a disc in the current layer's bgBottom with a 4-unit {@code ink1}
 *       ring expands from the focus (380 ms); on open the disc fades over 300 ms while a ring
 *       keeps expanding.</li>
 * </ul>
 *
 * <p>Uses its own centred {@link ExtendViewport}; deltas are clamped to 1/30 s. With
 * {@link #setBackdrop} it also drives the backdrop's virtual camera velocity (DIVE: snow streaks
 * upward). No allocation per frame.
 */
public final class TransitionLayer implements Disposable {
    /** Transition styles. */
    public enum Style { CURTAIN, DIVE, ASCENT, IRIS }

    /** Phases. */
    public enum Phase { IDLE, CLOSING, COVERED, OPENING }

    /** Minimum time fully covered (s). */
    public static final float MIN_COVER = 0.25f;
    /** Minimum DIVE cover (s) without cover art (tutorial, retry): the closed walls stay 1.5 s (Kelvin 2026-10-13). */
    public static final float DIVE_MIN_COVER = 1.5f;
    /** Minimum DIVE cover (s) with cover art: the art and the credits stay 4.5 s (Kelvin 2026-10-16). */
    public static final float DIVE_ART_COVER = 4.5f;
    /** Minimum ASCENT cover (s): the spray settles before the walls part (no art to show). */
    public static final float ASCENT_MIN_COVER = 0.6f;
    /** Gameplay judgement line heights the DIVE split lands on. */
    public static final float SKY_LINE_Y = 960f, GROUND_LINE_Y = 120f;
    /** Seam height. */
    public static final float SEAM_Y = 540f;
    private static final float MAX_DT = 1f / 30f;
    private static final float DIVE_STREAK_VY = -3000f;
    private static final CharSequence CREDIT_MUSIC = Lang.of("MUSIC", "音乐"), CREDIT_CHART = Lang.of("CHART", "谱师"),
        CREDIT_ILLUSTRATION = Lang.of("ILLUSTRATION", "曲绘");

    private final Draw draw;
    private final Fonts fonts;
    private final GlitchText glitch;
    private final GlitchState tear;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final ExtendViewport viewport = new ExtendViewport(Ui.REF_W, Ui.REF_H, camera);
    private Backdrop backdrop;

    private Style style = Style.CURTAIN;
    private Phase phase = Phase.IDLE;
    private float t, time;
    private int coveredFrames;
    /** Id of the current (or last started) transition. */
    private int serial;
    /** Transition the outstanding {@link #holds} belong to (may be {@code serial + 1}). */
    private int holdSerial;
    private int holds;
    private float focusX = Ui.CX, focusY = Ui.CY, irisR;
    private Runnable onCovered;
    private final StringBuilder label = new StringBuilder(48);
    /** {@link #time} when the current transition became fully covered (the DIVE spray and ripples). */
    private float closedAt;
    /** X2c art disc radius on the covered DIVE (the map's dive zoom stops at this size). */
    public static final float ART_R = 250f;
    private TextureRegion art;
    private float artCell;
    private final StringBuilder artTitle = new StringBuilder(48), artArtist = new StringBuilder(48),
        artDiff = new StringBuilder(16), artDiffMark = new StringBuilder(2), artCharter = new StringBuilder(48),
        artIllustrator = new StringBuilder(48);
    private final Color artAccent = new Color(Theme.GROUND_SIG);
    private final Color irisColor = new Color(Theme.CURTAIN_BOTTOM);
    private final Color c1 = new Color(), c2 = new Color(), clear = new Color();

    /** Creates the layer; {@code glitch} may be null (no tear at cover). */
    public TransitionLayer(Draw draw, Fonts fonts, GlitchText glitch) {
        this.draw = draw;
        this.fonts = fonts;
        this.glitch = glitch;
        this.tear = glitch != null ? glitch.obtainState() : null;
        camera.position.set(Ui.CX, Ui.CY, 0f);
    }

    // ------------------------------------------------------------------ configuration
    /** Optional shared backdrop: iris colour source and streak driver (null to detach). */
    public void setBackdrop(Backdrop b) {
        backdrop = b;
    }

    /**
     * Label shown while covered: the destination name on the CURTAIN seam or a centred caption
     * for IRIS (the DIVE shows none). Copied; cleared when the transition finishes. Null or
     * empty = none.
     */
    public void setCoveredLabel(CharSequence text) {
        label.setLength(0);
        if (text != null) label.append(text);
    }

    /**
     * X2c cover art for the next DIVE (cleared when it ends; null clears it now): the song's art
     * disc ({@code region} drawn {@code cell} wide, its visible circle {@link #ART_R} in radius)
     * sits on the closing walls; once they meet it glides left and the info rows pop in on its right
     * one by one: MUSIC (the artist), the title with {@code diff} (e.g. "DEEP 15.5") in
     * {@code accent}. On the open the art fades, leaving its rim, which widens. No credit rows; see
     * the overload.
     */
    public void setCoverArt(TextureRegion region, float cell, CharSequence title, CharSequence artist, CharSequence diff,
                            Color accent) {
        setCoverArt(region, cell, title, artist, diff, accent, null, null);
    }

    /**
     * As above, plus the CHART ({@code charter}) and ILLUSTRATION ({@code illustrator}) rows under the
     * title, each only when non-empty (Kelvin 2026-10-13 16:18: the credits show on the way into the
     * song, not on the song card; 22:16: the art moves left and the info pops in on its right).
     */
    public void setCoverArt(TextureRegion region, float cell, CharSequence title, CharSequence artist, CharSequence diff,
                            Color accent, CharSequence charter, CharSequence illustrator) {
        art = region;
        artCell = cell;
        artTitle.setLength(0);
        artArtist.setLength(0);
        artDiff.setLength(0);
        artDiffMark.setLength(0);
        if (title != null) artTitle.append(title);
        if (artist != null) artArtist.append(artist);
        if (diff != null) artDiff.append(diff);
        artCharter.setLength(0);
        artIllustrator.setLength(0);
        if (charter != null) artCharter.append(charter);
        if (illustrator != null) artIllustrator.append(illustrator);
        artAccent.set(accent != null ? accent : Theme.GROUND_SIG);
    }

    /**
     * The superscript after the cover art's difficulty, e.g. "+" for "DEEP 15⁺" (the chart constant's
     * mark, {@code ConstantLabel}); call after {@link #setCoverArt}, which clears it. Null or empty = none.
     */
    public void setCoverDiffMark(CharSequence mark) {
        artDiffMark.setLength(0);
        if (mark != null) artDiffMark.append(mark);
    }

    /**
     * Delays the open until the matching {@link #releaseOpen(int)}. While a transition is closing
     * or covered the hold applies to it; while idle or opening it applies to the next transition
     * that starts (pre-armed hold for loads kicked off before the close). A pre-armed hold must
     * be released even if its transition is never started, or the next transition stays covered.
     * GL thread only.
     *
     * @return the token to pass to {@link #releaseOpen(int)}
     */
    public int holdOpen() {
        int target = (phase == Phase.IDLE || phase == Phase.OPENING) ? serial + 1 : serial;
        if (holdSerial != target) {
            holdSerial = target;
            holds = 0;
        }
        holds++;
        return target;
    }

    /**
     * Releases one hold taken with {@link #holdOpen()}. A token from an earlier transition (its
     * transition already opened or was cancelled) is ignored, so stale workers cannot open a
     * newer cover early. GL thread only.
     */
    public void releaseOpen(int token) {
        if (token != holdSerial || holds <= 0) {
            if (Gdx.app != null) Gdx.app.error("TransitionLayer", "ignored stale releaseOpen(" + token + ")");
            return;
        }
        holds--;
    }

    /** Number of outstanding holds on the current (or, while idle, the next) transition. */
    public int pendingHolds() {
        int target = (phase == Phase.IDLE || phase == Phase.OPENING) ? serial + 1 : serial;
        return holdSerial == target ? holds : 0;
    }

    // ------------------------------------------------------------------ control
    /** Starts a transition centred on the screen. See {@link #start(Style, float, float, Runnable)}. */
    public boolean start(Style s, Runnable covered) {
        return start(s, Ui.CX, Ui.CY, covered);
    }

    /**
     * Starts closing with {@code style}; (fx, fy) is the IRIS focus in world units. Returns false
     * (and does nothing) when a transition is already running.
     */
    public boolean start(Style s, float fx, float fy, Runnable covered) {
        if (phase != Phase.IDLE) return false;
        style = s == null ? Style.CURTAIN : s;
        focusX = fx;
        focusY = fy;
        onCovered = covered;
        beginSerial();
        t = 0f;
        coveredFrames = 0;
        phase = Phase.CLOSING;
        prepareIris();
        return true;
    }

    /** Aborts immediately without callbacks (e.g. on a load failure handled elsewhere). */
    public void cancel() {
        finish();
    }

    /** True while a transition runs (screens lock input). */
    public boolean isBusy() {
        return phase != Phase.IDLE;
    }

    /** True while fully covered. */
    public boolean isCovered() {
        return phase == Phase.COVERED;
    }

    /** Current phase. */
    public Phase phase() {
        return phase;
    }

    /** Style of the current (or last) transition. */
    public Style style() {
        return style;
    }

    /** Progress 0..1 of the current closing / opening phase (1 while covered, 0 when idle). */
    public float phaseProgress() {
        switch (phase) {
            case CLOSING:
                return Anim.clamp01(t / closeDuration());
            case OPENING:
                return Anim.clamp01(t / openDuration());
            case COVERED:
                return 1f;
            default:
                return 0f;
        }
    }

    /** Minimum time fully covered for the current style (s). */
    public float minCover() {
        if (style == Style.DIVE) return art != null ? DIVE_ART_COVER : DIVE_MIN_COVER;
        return style == Style.ASCENT ? ASCENT_MIN_COVER : MIN_COVER;
    }

    /** Close duration of the current style (s). */
    public float closeDuration() {
        switch (style) {
            case DIVE:
            case ASCENT:
                return 0.35f;
            case IRIS:
                return 0.38f;
            case CURTAIN:
            default:
                return 0.36f;
        }
    }

    /** Open duration of the current style (s). */
    public float openDuration() {
        switch (style) {
            case DIVE:
            case ASCENT:
                return DIVE_OPEN_PART + DIVE_OPEN_FADE;
            case IRIS:
                return 0.30f;
            case CURTAIN:
            default:
                return 0.40f;
        }
    }

    /** Updates the private viewport (call from Main.resize after {@code Ui.onResize}). */
    public void resize(int w, int h) {
        Ui.applyViewport(viewport, w, h);
    }

    @Override
    public void dispose() {
        if (glitch != null && tear != null) glitch.freeState(tear);
    }

    // ------------------------------------------------------------------ frame
    /** Advances the state machine (clamped delta). {@link #render(float)} calls it. */
    public void update(float delta) {
        if (phase == Phase.IDLE) return;
        float dt = MathUtils.clamp(delta, 0f, MAX_DT);
        time += dt;
        t += dt;
        switch (phase) {
            case CLOSING:
                if (t >= closeDuration()) {
                    phase = Phase.COVERED;
                    t = 0f;
                    coveredFrames = 0;
                    closedAt = time;
                    if (style == Style.CURTAIN && tear != null) tear.reseed().trigger(GlitchText.Level.G3, time, 0.17f);
                    Runnable r = onCovered;
                    onCovered = null;
                    if (r != null) r.run();
                }
                break;
            case COVERED:
                coveredFrames++;
                if (t >= minCover() && coveredFrames >= 2 && !held()) {
                    phase = Phase.OPENING;
                    t = 0f;
                }
                break;
            case OPENING:
                if (t >= openDuration()) finish();
                break;
            default:
                break;
        }
        driveBackdrop();
    }

    /** Updates, then draws over the whole visible world. Begins and ends the shared batch. */
    public void render(float delta) {
        update(delta);
        if (phase == Phase.IDLE) return;
        viewport.apply();
        SpriteBatch b = draw.batch();
        b.setProjectionMatrix(camera.combined);
        b.begin();
        switch (style) {
            case DIVE:
            case ASCENT:
                drawDive(b);
                break;
            case IRIS:
                drawIris(b);
                break;
            case CURTAIN:
            default:
                drawCurtain(b);
                break;
        }
        b.end();
    }

    private void finish() {
        boolean wasBusy = phase != Phase.IDLE;
        phase = Phase.IDLE;
        t = 0f;
        if (holdSerial == serial) holds = 0;   // holds pre-armed for the next transition survive
        onCovered = null;
        art = null;
        label.setLength(0);
        if (backdrop != null && wasBusy && style == Style.DIVE) {
            backdrop.setVirtualVelocity(0f, 0f);
        }
    }

    /** Starts a new transition id; pre-armed holds (taken for exactly this id) are kept. */
    private void beginSerial() {
        serial++;
        if (holdSerial != serial) {
            holdSerial = serial;
            holds = 0;
        }
    }

    private boolean held() {
        return holdSerial == serial && holds > 0;
    }

    private void prepareIris() {
        if (backdrop != null) irisColor.set(backdrop.params().bottom);
        else irisColor.set(Theme.CURTAIN_BOTTOM);
        irisColor.a = 1f;
        float l = Ui.left(), r = Ui.right(), bo = Ui.bottom(), to = Ui.top();
        float dx = Math.max(Math.abs(focusX - l), Math.abs(r - focusX));
        float dy = Math.max(Math.abs(focusY - bo), Math.abs(to - focusY));
        irisR = (float) Math.sqrt(dx * dx + dy * dy) + 16f;
    }

    private void driveBackdrop() {
        if (backdrop == null || style != Style.DIVE) return;
        float v = DIVE_STREAK_VY;
        float k;
        switch (phase) {
            case CLOSING:
                k = Anim.clamp01(t / closeDuration());
                break;
            case COVERED:
                k = 1f;
                break;
            case OPENING:
                k = 1f - Anim.POW2_OUT.apply(Anim.clamp01(t / openDuration()));
                break;
            default:
                k = 0f;
                break;
        }
        backdrop.setVirtualVelocity(0f, v * k);
    }

    // ------------------------------------------------------------------ CURTAIN
    private void drawCurtain(SpriteBatch b) {
        float l = Ui.left(), w = Ui.worldW(), bo = Ui.bottom() - 8f, to = Ui.top() + 8f;
        float yU, yL, edgeA, seamA = 0f;
        if (phase == Phase.CLOSING) {
            float p = Anim.POW3_IN.apply(Anim.clamp01(t / closeDuration()));
            yU = to - (to - SEAM_Y) * p;
            yL = bo + (SEAM_Y - bo) * p;
            edgeA = 0.25f + 0.5f * p;
        } else if (phase == Phase.COVERED) {
            yU = SEAM_Y;
            yL = SEAM_Y;
            edgeA = 0f;
            seamA = 1f;
        } else {
            float p = Anim.EXP5_OUT.apply(Anim.clamp01(t / openDuration()));
            yU = SEAM_Y + (to - SEAM_Y) * p;
            yL = SEAM_Y - (SEAM_Y - bo) * p;
            edgeA = 0.75f * (1f - p);
            seamA = 1f - Anim.clamp01(t / 0.12f);
        }
        if (phase != Phase.COVERED) {
            // soft shadows cast onto the uncovered band give the panels weight
            float sh = 0.7f * Math.min(1f, (yU - yL) / 120f);
            if (sh > 0.01f) {
                clear.set(Theme.SHADOW);
                clear.a = 0f;
                draw.rectGradientV(l, yU - 110f, w, 110f, clear, Theme.SHADOW, sh);
                draw.rectGradientV(l, yL, w, 110f, Theme.SHADOW, clear, sh);
            }
        }
        Theme.mix(Theme.CURTAIN_TOP, Theme.ABYSS_BLACK, 0.35f, c1);
        draw.rectGradientV(l, yU, w, to - yU, Theme.CURTAIN_TOP, c1, 1f);
        Theme.mix(Theme.CURTAIN_BOTTOM, Theme.ABYSS_BLACK, 0.35f, c2);
        draw.rectGradientV(l, bo, w, yL - bo, c2, Theme.CURTAIN_BOTTOM, 1f);
        if (edgeA > 0.01f) {
            draw.sprite(draw.glowV(), l + w * 0.5f, yU, w, 70f, Theme.GROUND_SIG, 0.18f * edgeA);
            draw.sprite(draw.glowV(), l + w * 0.5f, yL, w, 70f, Theme.GROUND_SIG, 0.18f * edgeA);
            draw.rect(l, yU, w, 2f, Theme.GROUND_SIG, 0.6f * edgeA);
            draw.rect(l, yL - 2f, w, 2f, Theme.GROUND_SIG, 0.6f * edgeA);
        }
        if (seamA > 0.01f) drawCurtainSeam(b, l, w, seamA);
        if (phase == Phase.COVERED && glitch != null) glitch.drawScreenTears(b, tear, time, l, Ui.bottom(), w, Ui.worldH());
    }

    private void drawCurtainSeam(SpriteBatch b, float l, float w, float a) {
        float scanT = phase == Phase.COVERED ? Anim.clamp01(t / 0.25f) : 1f;
        float beamX = l + w * Anim.POW2_OUT.apply(scanT);
        // seam: dim ahead of the beam, lit behind it
        draw.sprite(draw.glowV(), l + w * 0.5f, SEAM_Y, w, 90f, Theme.GROUND_SIG, 0.20f * a);
        draw.rect(l, SEAM_Y - 1f, w, 2f, Theme.GROUND_SIG, 0.35f * a);
        draw.rect(l, SEAM_Y - 1f, beamX - l, 2f, Theme.GROUND_SIG, a);
        if (scanT < 1f) {
            draw.sprite(draw.streak(), beamX - 210f, SEAM_Y, 420f, 22f, Theme.GROUND_SIG, 0.9f * a);
            draw.glowDot(beamX, SEAM_Y, 120f, Theme.GROUND_SIG, 0.7f * a);
            draw.glowDot(beamX, SEAM_Y, 34f, Theme.WHITE, 0.9f * a);
        }
        if (label.length() > 0) {
            float la = a * Anim.clamp01((beamX - Ui.CX + 200f) / 300f);
            if (phase == Phase.COVERED) la *= Anim.POW2_OUT.apply(Anim.clamp01((t - 0.08f) / Anim.SHORT));
            if (la > 0.01f) {
                float cap = 20f;
                float tw = fonts.width(Fonts.Role.UI_LABEL, cap, label);
                float pw = tw + 64f, ph = cap + 34f;
                float px = Ui.CX - pw * 0.5f, py = SEAM_Y - ph * 0.5f;
                draw.roundRect(px, py, pw, ph, ph * 0.5f, Theme.CURTAIN_TOP, la);
                draw.roundRectStroke(px, py, pw, ph, ph * 0.5f, 1.5f, Theme.GROUND_SIG, 0.8f * la);
                fonts.draw(b, Fonts.Role.UI_LABEL, cap, label, Ui.CX, SEAM_Y, Fonts.VCENTER, Theme.INK1, la);
            }
        }
    }

    // ------------------------------------------------------------------ DIVE (X2c water walls)
    /** DIVE open: the walls part to the judgement lines, then fade (s). */
    private static final float DIVE_OPEN_PART = 0.42f, DIVE_OPEN_FADE = 0.35f;
    /** Strips per wall edge, the wave band above / below each wall's body, spray droplets and their flight (s). */
    private static final int WALL_COLS = 96, SPRAY_N = 180, FOAM_DOTS = 90;
    private static final float WAVE_BAND = 34f, SPRAY_DUR = 0.6f;
    /** Ripple rings after the walls meet: start delays, run time and travel (s, s, world units). */
    private static final float RING_1 = 0.05f, RING_2 = 0.25f, RING_DUR = 0.7f, RING_TRAVEL = 820f;
    /** Mock X2b / X2c water colours: pearl wall (top of the screen → near the edge), cyan wall (edge → bottom). */
    private static final Color PEARL_FAR = Theme.rgb(0xC9D6EA, 1f), PEARL_MID = Theme.rgb(0xE8F0FB, 1f),
        CYAN_EDGE = Theme.rgb(0x7BE6FF, 1f), CYAN_FAR = Theme.rgb(0x2A9CC8, 1f), FOAM_CYAN = Theme.rgb(0xE6FBFF, 1f),
        SPRAY_CYAN = Theme.rgb(0xCFF6FF, 1f), RIPPLE = Theme.rgb(0x7FE7FF, 1f), CHIP_BG = Theme.rgb(0x141024, 1f);

    /** DIVE, and ASCENT (the water only: no art, text or judgement-line glow). */
    private void drawDive(SpriteBatch b) {
        float bo = Ui.bottom() - 8f, to = Ui.top() + 8f;
        boolean dive = style == Style.DIVE;
        if (phase == Phase.CLOSING) {
            float p = Anim.POW2_IN.apply(Anim.clamp01(t / closeDuration()));
            float u0 = to + 60f, l0 = bo - 60f;
            drawWalls(u0 + (SEAM_Y - u0) * p, l0 + (SEAM_Y - l0) * p, 1f, 1f, 1f);
            if (dive && art != null) drawArt(b, Ui.CX, 1f, 0f);
            return;
        }
        float since = time - closedAt;
        float eU = SEAM_Y, eL = SEAM_Y, wob, foam = 1f, wallA = 1f, open = 0f;
        if (phase == Phase.COVERED) {
            wob = 1f - 0.7f * Anim.clamp01(since / 0.5f);
        } else {
            open = Anim.clamp01(t / openDuration());
            float p = Anim.EXP5_OUT.apply(Anim.clamp01(t / DIVE_OPEN_PART));
            eU = SEAM_Y + (SKY_LINE_Y - SEAM_Y) * p;
            eL = SEAM_Y - (SEAM_Y - GROUND_LINE_Y) * p;
            wob = 0.3f + 0.7f * MathUtils.sin(MathUtils.PI * Anim.clamp01(t / 0.5f));
            float f = Anim.clamp01((t - DIVE_OPEN_PART) / DIVE_OPEN_FADE);
            foam = 1f - f;
            wallA = 1f - Anim.POW2_OUT.apply(f);
        }
        if (wallA > 0.003f) drawWalls(eU, eL, wob, foam, wallA);
        drawSpray(since / SPRAY_DUR);
        float r0 = dive && art != null ? ART_R + 10f : 60f;
        drawRipple((since - RING_1) / RING_DUR, r0);
        drawRipple((since - RING_2) / RING_DUR, r0);
        if (!dive) return;
        if (phase == Phase.OPENING) {
            // the parting edges glow as the judgement lines, fading as the gameplay lines take over
            float la = 1f - Anim.clamp01((t - (openDuration() - 0.15f)) / 0.15f);
            float l = Ui.left(), w = Ui.worldW();
            if (la > 0.003f) {
                draw.sprite(draw.glowV(), l + w * 0.5f, eU, w, 56f, Theme.SKY_SIG, 0.45f * la);
                draw.sprite(draw.glowV(), l + w * 0.5f, eL, w, 56f, Theme.GROUND_SIG, 0.45f * la);
                draw.rect(l, eU - 2f, w, 4f, Theme.SKY_SIG, la);
                draw.rect(l, eL - 2f, w, 4f, Theme.GROUND_SIG, la);
            }
        }
        if (art != null) {
            // the art glides left once the walls meet, the info rows pop in on its right (Kelvin 2026-10-13 22:16)
            float glide = Anim.POW3_OUT.apply(Anim.clamp01((since - GLIDE_AT) / GLIDE_DUR));
            float ax = Ui.CX + (artRestX() - Ui.CX) * glide;
            drawArt(b, ax, phase == Phase.COVERED ? 1f : 1f - Anim.clamp01(t / 0.46f), Anim.POW2_OUT.apply(open));
            drawInfo(b, ax, since, phase == Phase.COVERED ? 1f : 1f - Anim.clamp01(t / 0.15f));
        }
    }

    /** Wave offset of a wall edge at world x (the mock's three travelling sines, up to ±30). */
    private float wave(float x, float ph) {
        return 16f * MathUtils.sin(x * 0.011f + time * 5f + ph) + 9f * MathUtils.sin(x * 0.027f - time * 7f + ph * 2f)
            + 5f * MathUtils.sin(x * 0.061f + time * 11f);
    }

    /**
     * The water walls (mock X2b / X2c): pearl water from the top down to {@code eU}, cyan water from the
     * bottom up to {@code eL}, each edge a wave ({@code wob} scales it) with a foam line and foam dots
     * ({@code foam}), all at {@code alpha}.
     */
    private void drawWalls(float eU, float eL, float wob, float foam, float alpha) {
        float l = Ui.left() - 4f, r = Ui.right() + 4f, bo = Ui.bottom() - 8f, to = Ui.top() + 8f;
        float ub = eU + WAVE_BAND, lb = eL - WAVE_BAND;
        // where the walls meet, water under both edges so no gap shows between their waves
        if (eU - eL < 2f * WAVE_BAND) {
            float lo = Math.max(bo, Math.min(eL, eU) - WAVE_BAND), hi = Math.min(to, Math.max(eL, eU) + WAVE_BAND);
            if (hi > lo) draw.rect(l, lo, r - l, hi - lo, CYAN_EDGE, alpha);
        }
        if (to > ub) draw.rectGradientV(l, ub, r - l, to - ub, PEARL_MID, PEARL_FAR, alpha);
        if (lb > bo) draw.rectGradientV(l, bo, r - l, lb - bo, CYAN_FAR, Theme.GROUND_SIG, alpha);
        float step = (r - l) / WALL_COLS, fa = 0.55f * foam * alpha;
        float px = l, pu = eU + wob * wave(l, 0f), pl = eL - wob * wave(l, 1.7f);
        for (int i = 1; i <= WALL_COLS; i++) {
            float x = l + i * step;
            float yu = eU + wob * wave(x, 0f), yl = eL - wob * wave(x, 1.7f);
            if (pu < to || yu < to) draw.quadV(px, pu, ub, x, yu, ub, Theme.SKY_SIG, PEARL_MID, alpha);
            if (pl > bo || yl > bo) draw.quadV(px, lb, pl, x, lb, yl, Theme.GROUND_SIG, CYAN_EDGE, alpha);
            if (fa > 0.003f) {
                draw.quadV(px, pu - 3f, pu + 3f, x, yu - 3f, yu + 3f, Color.WHITE, Color.WHITE, fa);
                draw.quadV(px, pl - 3f, pl + 3f, x, yl - 3f, yl + 3f, FOAM_CYAN, FOAM_CYAN, fa);
            }
            px = x;
            pu = yu;
            pl = yl;
        }
        if (fa <= 0.003f) return;
        // foam flecks thrown just off each edge (fixed per slot, riding the wave)
        for (int i = 0; i < FOAM_DOTS; i++) {
            float x = l + (r - l) * Anim.hash01(i, 41) + (Anim.hash01(i, 42) - 0.5f) * 40f;
            float rad = 1.5f + 6f * Anim.hash01(i, 43), off = 14f * Anim.hash01(i, 44);
            float da = 0.35f / 0.55f * Anim.hash01(i, 45) * fa;
            draw.disc(x, eU + wob * wave(x, 0f) - off, rad, Color.WHITE, da);
            draw.disc(x, eL - wob * wave(x, 1.7f) + off, rad, FOAM_CYAN, da);
        }
    }

    /** Spray off the seam where the walls meet ({@code k} 0..1 over {@link #SPRAY_DUR}). */
    private void drawSpray(float k) {
        if (k <= 0f || k >= 1f) return;
        float l = Ui.left(), w = Ui.worldW(), tt = k * 0.9f, a = 0.75f * (1f - k);
        for (int i = 0; i < SPRAY_N; i++) {
            boolean up = Anim.hash01(i, 21) < 0.5f;
            float v = 300f + 900f * Anim.hash01(i, 22), h = Anim.hash01(i, 23);
            float x = l + w * Anim.hash01(i, 24) + (Anim.hash01(i, 25) - 0.5f) * 300f * tt;
            float d = v * tt - 1100f * tt * tt;
            draw.disc(x, up ? SEAM_Y + d : SEAM_Y - d, 2f + 7f * h * h, up ? Color.WHITE : SPRAY_CYAN, a);
        }
    }

    /** One ripple ring sweeping out from the seam's centre ({@code k} 0..1), starting at radius {@code r0}. */
    private void drawRipple(float k, float r0) {
        if (k <= 0f || k >= 1f) return;
        float r = r0 + Anim.POW3_OUT.apply(k) * RING_TRAVEL;
        draw.sprite(draw.ringSoft(), Ui.CX, SEAM_Y, r / 0.42f, r / 0.42f, RIPPLE, 0.10f * (1f - k));
        draw.circle(Ui.CX, SEAM_Y, r, 3f, RIPPLE, 0.85f * (1f - k));
    }

    /** Covered DIVE timing (s after the walls meet): the art's glide left, then one info row every {@link #INFO_STEP}. */
    private static final float GLIDE_AT = 0.05f, GLIDE_DUR = 0.40f, INFO_AT = 0.20f, INFO_STEP = 0.10f, INFO_DUR = 0.25f;
    /** Info column metrics: gap after the disc, row gap, row and title heights, padding, screen margin. */
    private static final float INFO_GAP = 72f, ROW_GAP = 16f, ROW_H = 60f, TITLE_H = 104f, ROW_PAD = 30f, MARGIN = 80f;
    /** Info caps (labels, names, title, difficulty) and the gaps after a label and before the difficulty. */
    private static final float CAP_LABEL = 15f, CAP_NAME = 22f, CAP_TITLE = 44f, CAP_DIFF = 22f, LABEL_GAP = 14f,
        DIFF_GAP = 24f;

    /**
     * X2c: the art disc at {@code cx} on the seam; {@code open} 0..1 widens the rim from
     * {@link #ART_R} toward 600 as the art ({@code artA}) fades.
     */
    private void drawArt(SpriteBatch b, float cx, float artA, float open) {
        float cy = SEAM_Y;
        float rimR = ART_R + (600f - ART_R) * open;
        float rimA = open > 0f ? 1f - Anim.clamp01((open - 0.6f) / 0.4f) : 1f;
        draw.glowDot(cx, cy, ART_R * 3.2f, artAccent, 0.35f * artA);
        if (artA > 0.003f) {
            draw.disc(cx, cy, ART_R, Theme.SEAM, artA);
            float old = b.getPackedColor();
            b.setColor(1f, 1f, 1f, artA);
            b.draw(art, cx - artCell * 0.5f, cy - artCell * 0.5f, artCell, artCell);
            b.setPackedColor(old);
        }
        if (rimA > 0.003f) draw.circle(cx, cy, rimR, 6f, artAccent, rimA);
    }

    /** Content width of a credit row (label, gap, name); 0 when the name is empty. */
    private float creditW(CharSequence label, CharSequence name) {
        if (name.length() == 0) return 0f;
        return fonts.width(Fonts.Role.UI_LABEL, CAP_LABEL, label) + LABEL_GAP + fonts.width(Fonts.Role.UI, CAP_NAME, name);
    }

    /** Content width of the title row (title, gap, difficulty). */
    private float titleW() {
        float w = fonts.width(Fonts.Role.UI_BOLD, CAP_TITLE, artTitle);
        if (artDiff.length() > 0) w += DIFF_GAP + diffW();
        return w;
    }

    /** Width of the difficulty with its superscript mark. */
    private float diffW() {
        float w = fonts.width(Fonts.Role.UI_BOLD, CAP_DIFF, artDiff);
        if (artDiffMark.length() > 0) {
            w += CAP_DIFF * Theme.CONST_MARK_GAP
                + fonts.width(Fonts.Role.UI_BOLD, CAP_DIFF, artDiffMark) * Theme.CONST_MARK_SCALE;
        }
        return w;
    }

    /** Widest the info column may be: what is left right of the disc inside the margins. */
    private float infoMaxW() {
        return Ui.worldW() - 2f * MARGIN - 2f * ART_R - INFO_GAP;
    }

    /** Disc centre x after its glide: the disc and the info column centred as one group. */
    private float artRestX() {
        float inner = Math.max(titleW(), Math.max(creditW(CREDIT_MUSIC, artArtist),
            Math.max(creditW(CREDIT_CHART, artCharter), creditW(CREDIT_ILLUSTRATION, artIllustrator))));
        float col = Math.min(inner + 2f * ROW_PAD, infoMaxW());
        return Ui.CX - (INFO_GAP + col) * 0.5f;
    }

    /**
     * The info column right of the disc at {@code discX} (Kelvin 2026-10-13 22:16): MUSIC (the
     * artist), the title with the difficulty, CHART and ILLUSTRATION, each a dark pill that slides
     * out from the disc side {@link #INFO_STEP} after the one above it ({@code since}: seconds since
     * the walls met). Rows with an empty name are left out; the column is centred on the seam and
     * faded by {@code a}.
     */
    private void drawInfo(SpriteBatch b, float discX, float since, float a) {
        if (a <= 0.003f) return;
        boolean hm = artArtist.length() > 0, hc = artCharter.length() > 0, hi = artIllustrator.length() > 0;
        float total = TITLE_H + (hm ? ROW_H + ROW_GAP : 0f) + (hc ? ROW_H + ROW_GAP : 0f) + (hi ? ROW_H + ROW_GAP : 0f);
        float x = discX + ART_R + INFO_GAP, maxW = infoMaxW(), top = SEAM_Y + total * 0.5f;
        int i = 0;
        if (hm) {
            drawCreditRow(b, x, top, maxW, CREDIT_MUSIC, artArtist, popIn(since, i++), a);
            top -= ROW_H + ROW_GAP;
        }
        drawTitleRow(b, x, top, maxW, popIn(since, i++), a);
        top -= TITLE_H + ROW_GAP;
        if (hc) {
            drawCreditRow(b, x, top, maxW, CREDIT_CHART, artCharter, popIn(since, i++), a);
            top -= ROW_H + ROW_GAP;
        }
        if (hi) drawCreditRow(b, x, top, maxW, CREDIT_ILLUSTRATION, artIllustrator, popIn(since, i), a);
    }

    /** Pop-in progress 0..1 of info row {@code i}. */
    private static float popIn(float since, int i) {
        return Anim.clamp01((since - INFO_AT - i * INFO_STEP) / INFO_DUR);
    }

    /** A credit row at (x, top): "LABEL name" on a dark pill with a ground-cyan rim; {@code p} its pop-in. */
    private void drawCreditRow(SpriteBatch b, float x, float top, float maxW, CharSequence label, CharSequence name,
                               float p, float alpha) {
        if (p <= 0f) return;
        float a = alpha * Anim.POW2_OUT.apply(p);
        float wl = fonts.width(Fonts.Role.UI_LABEL, CAP_LABEL, label), wn = fonts.width(Fonts.Role.UI, CAP_NAME, name);
        float inner = wl + LABEL_GAP + wn;
        float s = Math.min(1f, (maxW - 2f * ROW_PAD) / Math.max(1f, inner));
        float bx = x - 40f * (1f - Anim.POW3_OUT.apply(p)), bottom = top - ROW_H, cy = bottom + ROW_H * 0.5f;
        pill(bx, bottom, inner * s + 2f * ROW_PAD, ROW_H, Theme.GROUND_SIG, a);
        float tx = bx + ROW_PAD;
        fonts.draw(b, Fonts.Role.UI_LABEL, CAP_LABEL * s, label, tx, cy, Align.left | Fonts.VCENTER, Theme.INK2, a);
        fonts.draw(b, Fonts.Role.UI, CAP_NAME * s, name, tx + (wl + LABEL_GAP) * s, cy, Align.left | Fonts.VCENTER,
            Theme.INK1, a);
    }

    /** The title row at (x, top): the title (bold) and the difficulty in its accent, on a sky-rimmed pill. */
    private void drawTitleRow(SpriteBatch b, float x, float top, float maxW, float p, float alpha) {
        if (p <= 0f) return;
        float a = alpha * Anim.POW2_OUT.apply(p);
        float wt = fonts.width(Fonts.Role.UI_BOLD, CAP_TITLE, artTitle);
        float wd = artDiff.length() > 0 ? diffW() : 0f;
        float inner = wt + (wd > 0f ? DIFF_GAP + wd : 0f);
        float s = Math.min(1f, (maxW - 2f * ROW_PAD) / Math.max(1f, inner));
        float bx = x - 40f * (1f - Anim.POW3_OUT.apply(p)), bottom = top - TITLE_H, cy = bottom + TITLE_H * 0.5f;
        pill(bx, bottom, inner * s + 2f * ROW_PAD, TITLE_H, Theme.SKY_SIG, a);
        float tx = bx + ROW_PAD;
        fonts.draw(b, Fonts.Role.UI_BOLD, CAP_TITLE * s, artTitle, tx, cy, Align.left | Fonts.VCENTER, Theme.INK1, a);
        if (wd > 0f) {
            float dx = tx + (wt + DIFF_GAP) * s;
            fonts.draw(b, Fonts.Role.UI_BOLD, CAP_DIFF * s, artDiff, dx, cy, Align.left | Fonts.VCENTER, artAccent, a);
            if (artDiffMark.length() > 0) {
                float mx = dx + (fonts.width(Fonts.Role.UI_BOLD, CAP_DIFF, artDiff) + CAP_DIFF * Theme.CONST_MARK_GAP) * s;
                // scaled from the difficulty's raster: no extra font bucket
                fonts.drawScaled(b, Fonts.Role.UI_BOLD, CAP_DIFF, Theme.CONST_MARK_SCALE * s, artDiffMark, mx,
                    cy + CAP_DIFF * Theme.CONST_MARK_RISE * s, Align.left | Fonts.VCENTER, 0f, 1f, artAccent, a);
            }
        }
    }

    /** A dark info pill with a 2-unit {@code rim}. */
    private void pill(float x, float y, float w, float h, Color rim, float a) {
        draw.roundRect(x, y, w, h, h * 0.5f, CHIP_BG, 0.92f * a);
        draw.roundRectStroke(x + 1f, y + 1f, w - 2f, h - 2f, h * 0.5f - 1f, 2f, rim, 0.9f * a);
    }

    // ------------------------------------------------------------------ IRIS
    private void drawIris(SpriteBatch b) {
        float l = Ui.left(), w = Ui.worldW(), bo = Ui.bottom(), h = Ui.worldH();
        if (phase == Phase.CLOSING) {
            float p = Anim.POW3_IN.apply(Anim.clamp01(t / closeDuration()));
            float r = irisR * p;
            if (r > 1f) {
                draw.sprite(draw.ringSoft(), focusX, focusY, r / 0.42f, r / 0.42f, Theme.GROUND_SIG, 0.22f);
                draw.shapes().setColor(irisColor);
                draw.shapes().filledCircle(focusX, focusY, r);
                draw.shapes().setColor(Color.WHITE);
                draw.circle(focusX, focusY, r, 4f, Theme.INK1, 1f);
            }
        } else if (phase == Phase.COVERED) {
            draw.rect(l, bo, w, h, irisColor, 1f);
            drawIrisLabel(b, Anim.POW2_OUT.apply(Anim.clamp01((t - 0.06f) / Anim.SHORT)));
        } else {
            float raw = Anim.clamp01(t / openDuration());
            float da = 1f - Anim.POW2_OUT.apply(raw);
            draw.rect(l, bo, w, h, irisColor, da);
            drawIrisLabel(b, 1f - Anim.clamp01(t / 0.08f));
            // a fresh ring keeps travelling outward from the focus
            float rr = irisR * (0.18f + 0.82f * Anim.POW3_OUT.apply(raw));
            float ra = (1f - raw) * (1f - raw);
            draw.circle(focusX, focusY, rr, 4f, Theme.INK1, ra);
            draw.sprite(draw.ringSoft(), focusX, focusY, rr / 0.42f, rr / 0.42f, Theme.GROUND_SIG, 0.10f * ra);
        }
    }

    private void drawIrisLabel(SpriteBatch b, float a) {
        if (a <= 0.01f || label.length() == 0) return;
        fonts.draw(b, Fonts.Role.UI_LABEL, 20f, label, Ui.CX, SEAM_Y, Fonts.VCENTER, Theme.INK1, a);
    }
}
