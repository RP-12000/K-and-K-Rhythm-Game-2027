package org.kelvinizer.fx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 打击特效的入口：DROP 和长条的水花（打中、按住、放开，{@link Splash}）和迷失的红色 ×。
 * 画质分高、低、关三档；高画质下游玩帧率持续偏低时自动降为低画质。
 *
 * <p>Hit-effect facade (ARCHITECTURE §9/§15, DESIGN §5.9). One instance is owned by {@code Main}
 * ({@code game.hitFx()}); gameplay calls it at judgement time, advances it with the game delta
 * (0 while paused, which freezes every effect) and draws it inside its additive block.
 *
 * <p><b>Effects.</b>
 * <ul>
 *   <li>DROP and lane STREAM: the round splash of {@link Splash} (Kelvin 2026-10-26): {@link #tap} for a DROP,
 *       {@link #head} for a STREAM's head (whose ripples are the STREAM's own), {@link #holdStart} /
 *       {@link #holdMove} / {@link #holdEnd} for the held stream that follows the note's base and its release
 *       splash. The judgement of the DROP or of the STREAM's head picks the form
 *       ({@link SplashForm}, RESONANCE or ECHO); a STREAM keeps its head's form to the end. The same on both lines,
 *       except that the drops fly down from the sky line.</li>
 *   <li>LOST: {@code assets/ParticleEffects/lost.p}, a red × mark of four short streaks, no motion (4 quads), loaded
 *       once with {@code ParticleAtlasFactory} against the shared {@link Draw} atlas into a ground pool and a mirrored
 *       sky pool.</li>
 * </ul>
 * The mine shock ring (DESIGN §5.15) is drawn by gameplay's own feedback layer, in the same additive block; TIDE hits
 * have no point effect at all: their effect is gameplay's tide wash (DESIGN §19.2, normal block A), which never calls
 * this class.
 *
 * <p><b>Quality</b> ({@link #setQuality}): High = everything; Low = half the splash drops and fountain rate, no
 * second ripple, no drop trails; Off = no hit effects. {@link #holdRate} (22 / 12 / 0 per s) is the rate of the TIDE
 * STREAM light bands (DESIGN §20.3). While the quality is High, {@link #update} watches the frame rate of actual
 * play and, when it averages under 55 fps over 3 s, drops to Low for the rest of the session and runs the
 * {@link #setOnAutoDrop auto-drop callback} once.
 *
 * <p><b>Allocation.</b> Fixed arrays and warmed LOST instances are created in the constructor; spawning, updating,
 * drawing and clearing allocate nothing. Everything is drawn additively from one texture, so it adds no draw call
 * beyond the caller's additive block.
 *
 * <p><b>3D</b> ({@link #setView}, Kelvin 2026-11-07 17:04 「和原来的同心圆一样的东西只不过3维化」): while the field is drawn
 * in 3D the effects are spheres round the keys: every ring is a shell, every flash, light and drop a ball, drawn facing
 * the screen where its point shows and at the size it shows at, the drops also flying in depth. A shell is a clear
 * sphere with a thick bright rim and a faint glow inside, shaded by a light from the upper left in front
 * ({@link #LIGHT_X}) with a highlight where it faces the light; a ball gets a white glint towards the light (17:46
 * 「球感不够」, 17:52 「可以，你试一下」, the design thread's 3D page v17).
 *
 * <p>Coordinates are world units. GL thread only.
 */
public final class HitFx implements Disposable {
    /**
     * 3D 视角下打击特效的位置：画面上的点在屏幕上的位置和那里的大小比例。
     *
     * <p>Where the hit effects show while the field is drawn in 3D (gameplay's {@code View3D}).
     */
    public interface Projection {
        /**
         * Puts into {@code out[0]}, {@code out[1]} where the point (x, y) of the flat picture shows on the screen
         * (world units of the flat camera), {@code dz} nearer than the middle of the keys' depth, and into
         * {@code out[2]} how many of those units one unit across the line of sight spans there.
         *
         * @return false when the point is behind the camera (it is not drawn)
         */
        boolean project(float x, float y, float dz, float[] out);

        /**
         * Puts into {@code out[0..2]} the direction (x, y, z) of the field (x right, y up, z towards the screen's
         * viewer) as the camera sees it: x right, y up, z towards the eye.
         */
        void direction(float x, float y, float z, float[] out);
    }

    /**
     * The hit effects' light in 3D, a unit vector fixed in the field: (−0.45, 0.75, 0.5) normalised, from the upper
     * left in front (the design thread's SPEC, 17:43).
     */
    public static final float LIGHT_X = -0.45f / 1.007472f, LIGHT_Y = 0.75f / 1.007472f, LIGHT_Z = 0.5f / 1.007472f;

    /** Quality levels (DESIGN §5.9); equal to {@code DeviceSettings.PARTICLES_*}. */
    public static final int QUALITY_OFF = 0, QUALITY_LOW = 1, QUALITY_HIGH = 2;

    /** Folder of the effect files (internal assets). */
    public static final String EFFECTS_DIR = "ParticleEffects/";
    /** World y above which a hit without an explicit line counts as a sky-line hit. */
    public static final float SKY_SPLIT_Y = 540f;
    /** Frame rate under which High quality drops to Low. */
    public static final float AUTO_DROP_FPS = 55f;
    /** Seconds of play averaged per auto-drop check. */
    public static final float AUTO_DROP_WINDOW_SEC = 3f;
    /** Seconds of play ignored after {@link #clear()} (song start hitches). */
    public static final float AUTO_DROP_WARMUP_SEC = 1f;
    /** TIDE STREAM light bands at High quality, per second (DESIGN §20.3). */
    public static final float HOLD_RATE_HIGH = 22f;
    /** TIDE STREAM light bands at Low quality, per second (DESIGN §20.3). */
    public static final float HOLD_RATE_LOW = 12f;

    private static final String LOST_FILE = "lost.p";
    private static final int MAX_HOLDS = Splash.HOLDS;

    private int quality = QUALITY_HIGH;
    /** The 3D view the effects are drawn through, or null (flat). */
    private Projection view;
    private final float[] pt = new float[3];

    private final Splash splash = new Splash();
    /** The LOST mark's ground and sky pools (null when lost.p failed to load). */
    private FxPool lostGround, lostSky;
    /** Prototypes, kept for the pools' lifetime and disposed with them. */
    private final Array<ParticleEffect> prototypes = new Array<>(false, 2);

    // hold slots -------------------------------------------------------------------------------------
    private final boolean[] holdActive = new boolean[MAX_HOLDS];
    private final int[] holdGen = new int[MAX_HOLDS];

    /** Splashes (taps, releases), LOST marks and held streams spawned so far (diagnostics). */
    private long burstsSpawned, streamsSpawned;

    // auto-drop ------------------------------------------------------------------------------------
    private Runnable onAutoDrop;
    private boolean autoDropped;
    private float probeWarmup = AUTO_DROP_WARMUP_SEC, probeTime;
    private int probeFrames;

    /**
     * Binds the splash to the shared atlas of {@code draw} and loads the LOST mark into its warmed pools. A missing
     * or malformed {@code lost.p} is logged and the mark is skipped; the splash works. The atlas stays owned by
     * {@link Draw}. GL thread only.
     */
    public HitFx(Draw draw) {
        splash.setRegions(draw);
        ParticleEffect ground;
        try {
            ground = ParticleAtlasFactory.load(Gdx.files.internal(EFFECTS_DIR + LOST_FILE), draw);
        } catch (RuntimeException e) {
            Gdx.app.error("HitFx", "Effect disabled: " + LOST_FILE, e);
            return;
        }
        Array<ParticleEmitter> em = ground.getEmitters();
        for (int i = 0; i < em.size; i++) ParticleAtlasFactory.tint(em.get(i), Theme.LOST);
        ParticleEffect sky = ParticleAtlasFactory.mirroredY(ground);
        prototypes.add(ground);
        prototypes.add(sky);
        lostGround = new FxPool(ground, true, FxPool.INITIAL);
        lostSky = new FxPool(sky, true, FxPool.INITIAL);
    }

    // ------------------------------------------------------------------ settings
    /** Sets the quality: 0 off (no hit effects), 1 low, 2 high. Off also clears live effects. */
    public void setQuality(int q) {
        quality = MathUtils.clamp(q, QUALITY_OFF, QUALITY_HIGH);
        splash.setLow(quality == QUALITY_LOW);
        if (quality == QUALITY_OFF) clear();
    }

    /** Current quality level. */
    public int quality() {
        return quality;
    }

    /**
     * Rate of the TIDE STREAM light bands at quality {@code q}, per second: 22 High, 12 Low, 0 Off (DESIGN §19.2,
     * §20.3).
     */
    public static float holdRate(int q) {
        return q >= QUALITY_HIGH ? HOLD_RATE_HIGH : q == QUALITY_LOW ? HOLD_RATE_LOW : 0f;
    }

    /**
     * Callback run once, on the GL thread inside {@link #update}, when High quality drops to Low
     * because play averaged under {@value #AUTO_DROP_FPS} fps over {@value #AUTO_DROP_WINDOW_SEC}
     * s. The owner persists it (device {@code particleQuality = LOW},
     * {@code particlesAutoDropped = true}); it must not re-layout or switch screens mid-song.
     */
    public void setOnAutoDrop(Runnable callback) {
        onAutoDrop = callback;
    }

    /**
     * Draws the effects through the 3D view {@code p}, or flat (null; {@link #clear} also goes back to flat). With a
     * view {@link #draw} takes the batch in the flat camera's world units: each ring, flash, light and drop is drawn
     * where its point shows, at the size it shows at, and a LOST mark starts where its point shows.
     */
    public void setView(Projection p) {
        view = p;
        splash.setView(p);
    }

    /** True once the quality was dropped automatically this session (it is never re-armed). */
    public boolean autoDropped() {
        return autoDropped;
    }

    // ------------------------------------------------------------------ spawning
    /**
     * The splash of a judged DROP at world point (x, y); the line is taken from y (above {@value #SKY_SPLIT_Y} =
     * sky). LOST delegates to {@link #lost}.
     */
    public void tap(float x, float y, Judgement j, float scale) {
        tap(x, y, j, scale, y > SKY_SPLIT_Y);
    }

    /**
     * The splash of a judged DROP or STREAM head at world point (x, y) on the given line, in the judgement's form
     * (the drops fly down from the sky line); LOST delegates to {@link #lost}. TIDE hits must not call it (they have
     * no point effect, DESIGN §19.2).
     *
     * @param scale size multiplier (1 = design size)
     * @param sky   true for the sky (upper) line
     */
    public void tap(float x, float y, Judgement j, float scale, boolean sky) {
        if (quality == QUALITY_OFF || j == null) return;
        if (j == Judgement.LOST) {
            lost(x, y, scale);
            return;
        }
        splash.splash(x, y, form(j), sky, clampScale(scale), Splash.DROP);
        burstsSpawned++;
    }

    /**
     * The splash of a judged STREAM head at its base (x, y) on the given line: the STREAM's own ripples, which a
     * RESONANCE head has as well (Kelvin 2026-10-26 14:12: only a DROP's ripples differ by form).
     */
    public void head(float x, float y, Judgement j, float scale, boolean sky) {
        if (quality == QUALITY_OFF || (j != Judgement.RESONANCE && j != Judgement.ECHO)) return;
        splash.splash(x, y, form(j), sky, clampScale(scale), Splash.HEAD);
        burstsSpawned++;
    }

    /**
     * The release splash of a completed STREAM at (x, y) that has no held stream (effects were off at its head, or
     * every hold slot was busy): the splash at 1.2× with the release drops, in the head's form.
     */
    public void release(float x, float y, Judgement j, float scale, boolean sky) {
        if (quality == QUALITY_OFF || (j != Judgement.RESONANCE && j != Judgement.ECHO)) return;
        splash.splash(x, y, form(j), sky, Splash.RELEASE_BIG * clampScale(scale), Splash.RELEASE);
        burstsSpawned++;
    }

    /**
     * Starts the held stream of a STREAM at its base (x, y); the line is taken from y (above
     * {@value #SKY_SPLIT_Y} = sky). See {@link #holdStart(float, float, Judgement, float, boolean)}.
     *
     * @return a handle for {@link #holdMove} / {@link #holdEnd}, or −1 when effects are off, the
     *         head is LOST or null, or every hold slot is busy (both methods ignore −1)
     */
    public int holdStart(float x, float y, Judgement j, float scale) {
        return holdStart(x, y, j, scale, y > SKY_SPLIT_Y);
    }

    /**
     * Starts the held stream of a STREAM at its base (x, y) on the given line, in the form of its head's judgement:
     * the flickering light, the fountain and the ripples (the head's own splash is {@link #tap}). A LOST (or null)
     * head starts nothing and returns −1: a missed head has no stream and no release splash.
     *
     * @return a handle for {@link #holdMove} / {@link #holdEnd}, or −1 when effects are off, the
     *         head is LOST or null, or every hold slot is busy (both methods ignore −1)
     */
    public int holdStart(float x, float y, Judgement j, float scale, boolean sky) {
        if (quality == QUALITY_OFF || (j != Judgement.RESONANCE && j != Judgement.ECHO)) return -1;
        for (int i = 0; i < MAX_HOLDS; i++) {
            if (holdActive[i]) continue;
            holdActive[i] = true;
            holdGen[i] = (holdGen[i] + 1) & 0x7FFFFF;
            splash.hold(i, x, y, form(j), sky, clampScale(scale));
            streamsSpawned++;
            return (holdGen[i] << 8) | i;
        }
        return -1;
    }

    /**
     * Sets the length of one beat (s) at the song's current tempo: the ripples of a held STREAM come every
     * {@link SplashForm#HELD_RING_BEATS} beats of it and follow tempo changes (Kelvin 2026-10-31 15:03). Call every
     * frame before {@link #update}; never set (or 0) keeps the fixed {@link SplashForm#HELD_RING_PERIOD}.
     */
    public void setBeatLength(float seconds) {
        splash.setBeatLength(seconds);
    }

    /**
     * Moves a running held stream to the note's base (stale or −1 handles are ignored): its light follows, later
     * drops and ripples start there.
     */
    public void holdMove(int handle, float x, float y) {
        int i = slot(handle);
        if (i >= 0) splash.moveHold(i, x, y);
    }

    /**
     * Ends a held stream: the fountain and ripples stop at once, live drops and ripples finish. A completed STREAM
     * adds its release splash at the base.
     */
    public void holdEnd(int handle, boolean completed) {
        int i = slot(handle);
        if (i < 0) return;
        holdActive[i] = false;
        boolean seal = completed && quality != QUALITY_OFF;
        splash.endHold(i, seal);
        if (seal) burstsSpawned++;
    }

    /**
     * Subtle miss cue: a red × (no motion) at (x, y). The caller places it just outside the
     * zone (ground y 240, sky y 840, DESIGN §5.6).
     */
    public void lost(float x, float y, float scale) {
        if (quality == QUALITY_OFF) return;
        FxPool pool = y > SKY_SPLIT_Y ? lostSky : lostGround;
        if (pool == null) return;
        float s = clampScale(scale);
        if (view != null) { // a short mark: placed where it shows as it starts
            if (!view.project(x, y, 0f, pt)) return;
            x = pt[0];
            y = pt[1];
            s *= pt[2];
        }
        pool.spawn(x, y, s, null);
        burstsSpawned++;
    }

    // ------------------------------------------------------------------ frame
    /**
     * Advances every effect by the game delta. Call once per frame; pass 0 while paused (all
     * effects freeze). Also runs the High → Low auto-drop check over frames with delta > 0.
     */
    public void update(float delta) {
        if (delta <= 0f) return;
        probe(delta);
        splash.update(delta);
        if (lostGround != null) {
            lostGround.update(delta);
            lostSky.update(delta);
        }
    }

    /**
     * Draws every live effect into {@code batch} with additive blending. Any begun batch works
     * (the shared {@code game.draw().batch()} or a screen's own); everything comes from the shared
     * {@link Draw} atlas texture. The batch's previous blend function and colour are restored.
     */
    public void draw(Batch batch) {
        if (liveEffects() == 0) return;
        int src = batch.getBlendSrcFunc(), dst = batch.getBlendDstFunc();
        int srcA = batch.getBlendSrcFuncAlpha(), dstA = batch.getBlendDstFuncAlpha();
        float oldColor = batch.getPackedColor();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        splash.draw(batch);
        if (lostGround != null) {
            lostGround.draw(batch);
            lostSky.draw(batch);
        }
        batch.setPackedColor(oldColor);
        batch.setBlendFunctionSeparate(src, dst, srcA, dstA);
    }

    /**
     * Removes every effect and stops every hold (begin, retry, quit, hide) and goes back to flat drawing
     * ({@link #setView}). Also restarts the auto-drop warm-up, so a song start hitch is never counted.
     */
    public void clear() {
        setView(null);
        splash.clear();
        if (lostGround != null) {
            lostGround.releaseAll();
            lostSky.releaseAll();
        }
        for (int i = 0; i < MAX_HOLDS; i++) holdActive[i] = false;
        probeWarmup = AUTO_DROP_WARMUP_SEC;
        probeTime = 0f;
        probeFrames = 0;
    }

    // ------------------------------------------------------------------ diagnostics
    /** Live effects: splash drops, flashes and ripples, held streams and LOST marks. */
    public int liveEffects() {
        int n = splash.liveDrops() + splash.liveFlashes() + splash.liveRings();
        for (int i = 0; i < MAX_HOLDS; i++) {
            if (holdActive[i]) n++;
        }
        if (lostGround != null) n += lostGround.liveCount() + lostSky.liveCount();
        return n;
    }

    /** Quads the next {@link #draw} submits at most. */
    public int liveQuads() {
        int n = splash.quads();
        if (lostGround != null) n += lostGround.quads() + lostSky.quads();
        return n;
    }

    /** Splashes (taps, releases) and LOST marks spawned so far, never reset (diagnostics and tests). */
    public long burstsSpawned() {
        return burstsSpawned;
    }

    /** Held streams started so far, never reset (diagnostics and tests). */
    public long streamsSpawned() {
        return streamsSpawned;
    }

    /** Releases the pools and prototypes (the atlas belongs to {@link Draw}). */
    @Override
    public void dispose() {
        clear();
        if (lostGround != null) {
            lostGround.clear(); // drops the free instances
            lostSky.clear();
        }
        lostGround = null;
        lostSky = null;
        for (int i = 0; i < prototypes.size; i++) prototypes.get(i).dispose();
        prototypes.clear();
    }

    // ------------------------------------------------------------------ internals
    private static int form(Judgement j) {
        return j == Judgement.ECHO ? SplashForm.ECHO : SplashForm.RESONANCE;
    }

    private static float clampScale(float scale) {
        return scale > 0.05f ? scale : 0.05f;
    }

    private int slot(int handle) {
        if (handle < 0) return -1;
        int i = handle & 0xFF;
        if (i >= MAX_HOLDS || !holdActive[i] || holdGen[i] != (handle >>> 8)) return -1;
        return i;
    }

    /** Average-fps watch for the High → Low drop (frames with delta > 0 only). */
    private void probe(float delta) {
        if (quality != QUALITY_HIGH || autoDropped) return;
        if (probeWarmup > 0f) {
            probeWarmup -= delta;
            return;
        }
        probeTime += delta;
        probeFrames++;
        if (probeTime < AUTO_DROP_WINDOW_SEC) return;
        float fps = probeFrames / probeTime;
        probeTime = 0f;
        probeFrames = 0;
        if (fps >= AUTO_DROP_FPS) return;
        autoDropped = true;
        quality = QUALITY_LOW;
        splash.setLow(true);
        if (onAutoDrop != null) onAutoDrop.run();
    }
}
