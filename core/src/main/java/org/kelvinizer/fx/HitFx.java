package org.kelvinizer.fx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEffectPool.PooledEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 打击特效的入口：水滴的共鸣 / 回响爆发、水滴和水流命中时的水波纹、按住水流时的光点流和完成时的收尾、迷失的红色 ×。
 * 画质分高、低、关三档，粒子不超过 100 个四边形的预算；高画质下游玩帧率持续偏低时自动降为低画质。
 *
 * <p>Hit-effect facade (ARCHITECTURE §9/§15, DESIGN §5.9). One instance is owned by {@code Main}
 * ({@code game.hitFx()}); gameplay calls it at judgement time, advances it with the game delta
 * (0 while paused, which freezes every effect) and draws it inside its additive block.
 *
 * <p><b>Effects.</b> The {@code assets/ParticleEffects/*.p} files are loaded once with
 * {@code ParticleAtlasFactory} against the shared {@link Draw} atlas (regions {@code ring_thin},
 * {@code ring_soft}, {@code soft_dot}, {@code streak}), recoloured from {@link Theme} and kept as
 * prototypes:
 * <ul>
 *   <li>{@code resonance.p}: crisp sonar ring with a soft halo, white core flash, 9 aligned
 *       sparks (12 quads);</li>
 *   <li>{@code echo.p}: ring, a delayed softer "echo" ring, 6 sparks (8 quads);</li>
 *   <li>{@code hold.p}: continuous rising stream of motes (bright head → line colour) while a
 *       hold is held; a completed hold ends with the seal, the head grade's Low-quality burst at
 *       80 % scale (RESONANCE 6 quads, ECHO 4);</li>
 *   <li>{@code lost.p}: a red × mark of four short streaks, no motion (4 quads).</li>
 * </ul>
 * On top of the bursts, DROP and STREAM heads get the water rings of DESIGN §20.5
 * ({@code WaterRings}, one quad each): {@link #tap} adds them to a DROP's burst and
 * {@link #ripple} plays them alone for a STREAM head; RESONANCE gives five thick cyan rings
 * staggered 65 ms (Low: rings 0 and 2), ECHO one thin violet ring; a running hold stream adds a
 * small ring at its hold point every 320 ms. Every effect is in its judgement colour on both
 * lines (DESIGN §21.1: {@code resonance} rings and halo, {@code echo} rings and sparks).
 * The mine shock ring (DESIGN §5.15) is drawn by gameplay's own feedback layer, in the same
 * additive block; TIDE hits have no point effect at all: their effect is gameplay's tide wash
 * (DESIGN §19.2, normal block A), which never calls this class.
 * Each effect has a ground pool and a sky pool (the sky prototype is mirrored at load, so its
 * bursts open downward toward the screen centre), and a reduced Low-quality variant where the
 * design asks for one. Pools hold at most 24 instances (see {@code FxPool}) and are pre-filled
 * with warmed instances for the most an effect can have live at once in play: 12 per burst pool
 * (Low and ring-only variants included), 16 per hold-stream pool (one per hold slot), 8 for the
 * LOST pools.
 *
 * <p><b>Quality</b> ({@link #setQuality}): High = everything; Low = half the sparks, no core,
 * no halo, no echo ring, hold stream 12/s, RESONANCE water rings 0 and 2 only (RESONANCE 6 + 2
 * quads, ECHO 4 + 1); Off = no hit effects. The hold stream rate is {@link #holdRate}, which the
 * TIDE STREAM light bands share (DESIGN §20.3).
 * While the quality is High, {@link #update} watches the
 * frame rate of actual play and, when it averages under 55 fps over 3 s, drops to Low for the
 * rest of the session and runs the {@link #setOnAutoDrop auto-drop callback} once.
 *
 * <p><b>Budget.</b> The particles keep their own budget: RESONANCE draws 12 quads, ECHO 8; 12
 * hits/s plus 4 held streams, including four holds sealing in the same frame, stay under
 * {@value #QUAD_BUDGET} live particle quads (DESIGN §5.9, §14). A guard covers denser moments: a
 * burst that would take the estimated live particle total past 90 steps down to its Low variant,
 * then to its ring alone (1 quad). The water rings come on top with their own budget (DESIGN
 * §20.5: 5 ring quads per RESONANCE hit at High, 1 per ECHO hit, about 30 live in the worst
 * case): when a RESONANCE set would take the ring estimate (waiting rings included) past
 * {@value #RING_GUARD}, it steps down to the Low pair, then to ring 0 alone. No judgement ever
 * loses its cue. Everything is drawn additively from one texture, so it adds no draw call beyond
 * the caller's additive block.
 *
 * <p><b>Allocation.</b> The instances are created and warmed in the constructor; spawning,
 * updating, drawing and clearing allocate nothing while the pre-filled pools suffice (see
 * {@link #instances()}).
 *
 * <p>Coordinates are world units. GL thread only.
 */
public final class HitFx implements Disposable {
    /** Quality levels (DESIGN §5.9); equal to {@code DeviceSettings.PARTICLES_*}. */
    public static final int QUALITY_OFF = 0, QUALITY_LOW = 1, QUALITY_HIGH = 2;

    /** Folder of the effect files (internal assets). */
    public static final String EFFECTS_DIR = "ParticleEffects/";
    /** Scale of the burst that seals a completed hold. */
    public static final float SEAL_SCALE = 0.8f;
    /** World y above which a hit without an explicit line counts as a sky-line hit. */
    public static final float SKY_SPLIT_Y = 540f;
    /** Frame rate under which High quality drops to Low. */
    public static final float AUTO_DROP_FPS = 55f;
    /** Seconds of play averaged per auto-drop check. */
    public static final float AUTO_DROP_WINDOW_SEC = 3f;
    /** Seconds of play ignored after {@link #clear()} (song start hitches). */
    public static final float AUTO_DROP_WARMUP_SEC = 1f;
    /** Live-quad budget of all hit effects in gameplay (DESIGN §5.9 "under 100", §14). */
    public static final int QUAD_BUDGET = 100;
    /** Hold-stream emission at High quality, particles per second (DESIGN §5.9). */
    public static final float HOLD_RATE_HIGH = 22f;
    /** Hold-stream emission at Low quality, particles per second (DESIGN §5.9). */
    public static final float HOLD_RATE_LOW = 12f;
    /** Period of the small water ring at a held STREAM's hold point (DESIGN §20.5). */
    public static final float HOLD_RING_PERIOD_SEC = WaterRings.HOLD_PERIOD_SEC;

    private static final int FX_RESONANCE = 0, FX_ECHO = 1, FX_HOLD = 2, FX_LOST = 3, FX_COUNT = 4;
    private static final String[] FILES = {"resonance.p", "echo.p", "hold.p", "lost.p"};
    private static final int GROUND = 0, SKY = 1;
    /** Variant tiers of an effect: Low, High, and Ring (the ring alone, budget guard only). */
    private static final int Q_LOW = 0, Q_HIGH = 1, Q_RING = 2, TIERS = 3;

    private static final int MAX_HOLDS = 16;
    /**
     * Budget guard threshold: a burst steps down a tier rather than take the estimated live total
     * past it. The 10-quad margin under {@link #QUAD_BUDGET} absorbs the hold-stream motes born
     * between two updates and the ring-only cues, which are never refused.
     */
    private static final int GUARD_QUADS = QUAD_BUDGET - 10;
    /** Ring estimate (started and waiting water rings) past which RESONANCE rings step down. */
    public static final int RING_GUARD = 60;
    /** Warmed instances per pool: bursts, hold streams (one per hold slot), LOST marks. */
    private static final int PREFILL_BURST = 12, PREFILL_HOLD = MAX_HOLDS, PREFILL_MARK = FxPool.INITIAL;
    /** Share of white mixed into a hold mote's colour at birth (its bright head). */
    private static final float STREAM_HEAD_WHITE = 0.6f;

    private int quality = QUALITY_HIGH;

    /**
     * pools[fx][line][tier]; Low and High share one pool where the effect is the same, and only
     * the RESONANCE and ECHO bursts have a Ring tier.
     */
    private final FxPool[][][] pools = new FxPool[FX_COUNT][2][TIERS];
    /** Every distinct pool once, for update / draw / clear. */
    private final Array<FxPool> allPools = new Array<>(false, 24);
    /** Prototypes, kept for the pools' lifetime and disposed with them. */
    private final Array<ParticleEffect> prototypes = new Array<>(false, 24);

    // hold streams -----------------------------------------------------------------------------
    private final boolean[] holdActive = new boolean[MAX_HOLDS];
    private final int[] holdGen = new int[MAX_HOLDS];
    private final PooledEffect[] holdEffect = new PooledEffect[MAX_HOLDS];
    private final float[] holdX = new float[MAX_HOLDS], holdY = new float[MAX_HOLDS];
    private final float[] holdScale = new float[MAX_HOLDS];
    private final boolean[] holdSky = new boolean[MAX_HOLDS];
    private final Judgement[] holdJudgement = new Judgement[MAX_HOLDS];
    /** Stream tints (bright head → colour, timeline {0, 1}) given to a hold emitter at its start. */
    private final float[] streamGround, streamSky, streamEcho;

    /** Hold-stream time since its last small water ring (s), per hold slot. */
    private final float[] holdRingT = new float[MAX_HOLDS];

    /** The DROP / STREAM water rings (DESIGN §20.5), drawn with the bursts. */
    private final WaterRings rings = new WaterRings();

    /** Live quads after the last update plus the most each burst spawned since can add. */
    private int quadEstimate;
    /** Water rings alive after the last update (started or waiting) plus the ones spawned since. */
    private int ringEstimate;
    /** Bursts (taps, seals, LOST marks) and hold streams spawned so far (diagnostics). */
    private long burstsSpawned, streamsSpawned;

    // auto-drop ------------------------------------------------------------------------------------
    private Runnable onAutoDrop;
    private boolean autoDropEnabled = true;
    private boolean autoDropped, autoDropNotice;
    private float probeWarmup = AUTO_DROP_WARMUP_SEC, probeTime;
    private int probeFrames;

    /**
     * Loads every effect against the shared atlas of {@code draw} and builds the warmed pools.
     * A missing or malformed effect file is logged and that effect is skipped; the others work.
     * The atlas stays owned by {@link Draw}. GL thread only.
     */
    public HitFx(Draw draw) {
        streamGround = streamTint(Theme.GROUND_SIG);
        streamSky = streamTint(Theme.SKY_SIG);
        streamEcho = streamTint(Theme.ECHO);
        rings.setRegions(draw);
        for (int fx = 0; fx < FX_COUNT; fx++) {
            ParticleEffect ground;
            try {
                ground = ParticleAtlasFactory.load(Gdx.files.internal(EFFECTS_DIR + FILES[fx]), draw);
            } catch (RuntimeException e) {
                Gdx.app.error("HitFx", "Effect disabled: " + FILES[fx], e);
                continue;
            }
            colour(fx, ground, Theme.GROUND_SIG);
            if (fx == FX_HOLD) holdEmission(ground, QUALITY_HIGH); // one source of the rate
            ParticleEffect sky = ParticleAtlasFactory.mirroredY(ground);
            colour(fx, sky, Theme.SKY_SIG);
            buildPools(fx, GROUND, ground);
            buildPools(fx, SKY, sky);
        }
    }

    // ------------------------------------------------------------------ settings
    /** Sets the quality: 0 off (no hit effects), 1 low, 2 high. Off also clears live effects. */
    public void setQuality(int q) {
        quality = MathUtils.clamp(q, QUALITY_OFF, QUALITY_HIGH);
        if (quality == QUALITY_OFF) clear();
    }

    /** Current quality level. */
    public int quality() {
        return quality;
    }

    /**
     * Emission rate of a hold stream at quality {@code q}, particles per second: 22 High, 12 Low,
     * 0 Off. The TIDE STREAM light bands peel off at the same rate (DESIGN §19.2, §20.3).
     */
    public static float holdRate(int q) {
        return q >= QUALITY_HIGH ? HOLD_RATE_HIGH : q == QUALITY_LOW ? HOLD_RATE_LOW : 0f;
    }

    /**
     * Sets the emission of a hold-stream effect ({@code hold.p} or its Low variant) to
     * {@link #holdRate holdRate(q)} particles per second (load time).
     */
    static void holdEmission(ParticleEffect hold, int q) {
        hold.getEmitters().first().getEmission().setHigh(holdRate(q));
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
     * Enables (default) or disables the automatic High → Low drop, e.g. for benchmarks and test
     * harnesses running on slow software GL (same role as {@code Backdrop.setAutoFallback}).
     */
    public void setAutoDrop(boolean enabled) {
        autoDropEnabled = enabled;
    }

    /** True once the quality was dropped automatically this session (it is never re-armed). */
    public boolean autoDropped() {
        return autoDropped;
    }

    /**
     * Returns true exactly once after an automatic drop, so the result screen can show
     * "EFFECTS SET TO LOW FOR SMOOTHER PLAY" a single time.
     */
    public boolean consumeAutoDropNotice() {
        boolean n = autoDropNotice;
        autoDropNotice = false;
        return n;
    }

    // ------------------------------------------------------------------ spawning
    /**
     * One-shot burst for a judged tap at world point (x, y); the line is taken from y
     * (above {@value #SKY_SPLIT_Y} = sky). LOST delegates to {@link #lost}.
     */
    public void tap(float x, float y, Judgement j, float scale) {
        tap(x, y, j, scale, y > SKY_SPLIT_Y);
    }

    /**
     * One-shot hit effect for a judged DROP at world point (x, y) on the given line (sky bursts
     * open downward): the RESONANCE or ECHO burst plus its water rings ({@link #ripple}); LOST
     * delegates to {@link #lost}. TIDE hits must not call it (they have no point effect,
     * DESIGN §19.2).
     *
     * @param scale size and motion multiplier (1 = design size)
     * @param sky   true for the sky (upper) line
     */
    public void tap(float x, float y, Judgement j, float scale, boolean sky) {
        if (quality == QUALITY_OFF || j == null) return;
        if (j == Judgement.LOST) {
            lost(x, y, scale);
            return;
        }
        burst(j == Judgement.RESONANCE ? FX_RESONANCE : FX_ECHO, sky, x, y, scale, false);
        rings(x, y, j, scale);
    }

    /**
     * The water rings of a DROP or STREAM head hit at (x, y) without a burst (DESIGN §20.5; a
     * STREAM head's burst is its hold stream): RESONANCE five {@code resonance} rings staggered
     * 65 ms (Low quality: rings 0 and 2), ECHO one thin {@code echo} ring, LOST and Off nothing.
     * The same on both lines. TIDE hits must not call it.
     *
     * @param scale size multiplier (1 = design size)
     */
    public void ripple(float x, float y, Judgement j, float scale) {
        if (quality == QUALITY_OFF || j == null || j == Judgement.LOST) return;
        rings(x, y, j, scale);
    }

    /**
     * Starts a hold stream at (x, y) coloured by the head judgement; the line is taken from y
     * (above {@value #SKY_SPLIT_Y} = sky). See {@link #holdStart(float, float, Judgement, float, boolean)}.
     *
     * @return a handle for {@link #holdMove} / {@link #holdEnd}, or −1 when effects are off, the
     *         head is LOST or null, or every hold slot is busy (both methods ignore −1)
     */
    public int holdStart(float x, float y, Judgement j, float scale) {
        return holdStart(x, y, j, scale, y > SKY_SPLIT_Y);
    }

    /**
     * Starts a hold stream at (x, y) on the given line, coloured by the head judgement: the line
     * colour after a RESONANCE head, {@code #B49CFF} after an ECHO head. A LOST (or null) head
     * starts nothing and returns −1: a missed head has no stream and never earns a seal.
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
            holdX[i] = x;
            holdY[i] = y;
            holdScale[i] = scale;
            holdSky[i] = sky;
            holdJudgement[i] = j;
            holdRingT[i] = 0f;
            streamsSpawned++;
            FxPool pool = pool(FX_HOLD, sky);
            float[] tint = j == Judgement.ECHO ? streamEcho : sky ? streamSky : streamGround;
            holdEffect[i] = pool != null ? pool.spawn(x, y, clampScale(scale), tint) : null;
            return (holdGen[i] << 8) | i;
        }
        return -1;
    }

    /** Moves a running hold stream (stale or −1 handles are ignored). */
    public void holdMove(int handle, float x, float y) {
        int i = slot(handle);
        if (i < 0) return;
        holdX[i] = x;
        holdY[i] = y;
        if (holdEffect[i] != null) holdEffect[i].setPosition(x, y);
    }

    /**
     * Ends a hold stream: emission stops at once and the live particles finish their life
     * (never popped). A completed hold adds the seal: the head grade's burst in its Low-quality
     * form (ring and half the sparks) at {@value #SEAL_SCALE} scale, light enough that a chord of
     * holds released together stays within the quad budget.
     */
    public void holdEnd(int handle, boolean completed) {
        int i = slot(handle);
        if (i < 0) return;
        holdActive[i] = false;
        PooledEffect e = holdEffect[i];
        holdEffect[i] = null;
        if (e != null) FxPool.stopEmission(e);
        if (completed && quality != QUALITY_OFF) {
            int fx = holdJudgement[i] == Judgement.ECHO ? FX_ECHO : FX_RESONANCE;
            burst(fx, holdSky[i], holdX[i], holdY[i], holdScale[i] * SEAL_SCALE, true);
        }
    }

    /**
     * Subtle miss cue: a red × (no motion) at (x, y). The caller places it just outside the
     * zone (ground y 240, sky y 840, DESIGN §5.6).
     */
    public void lost(float x, float y, float scale) {
        if (quality == QUALITY_OFF) return;
        burst(FX_LOST, y > SKY_SPLIT_Y, x, y, scale, false);
    }

    // ------------------------------------------------------------------ frame
    /**
     * Advances every effect by the game delta. Call once per frame; pass 0 while paused (all
     * effects freeze). Also runs the High → Low auto-drop check over frames with delta > 0.
     */
    public void update(float delta) {
        if (delta <= 0f) return;
        probe(delta);
        for (int i = 0; i < allPools.size; i++) allPools.get(i).update(delta);
        rings.update(delta);
        if (quality != QUALITY_OFF) holdRings(delta);
        quadEstimate = poolQuads();
        ringEstimate = rings.live(); // waiting (staggered) rings included
    }

    /** Every {@value #HOLD_RING_PERIOD_SEC} s of a running hold stream, a small ring at its hold point. */
    private void holdRings(float delta) {
        for (int i = 0; i < MAX_HOLDS; i++) {
            if (!holdActive[i]) continue;
            holdRingT[i] += delta;
            while (holdRingT[i] >= WaterRings.HOLD_PERIOD_SEC) {
                holdRingT[i] -= WaterRings.HOLD_PERIOD_SEC;
                rings.hold(holdX[i], holdY[i], holdScale[i], holdJudgement[i] == Judgement.ECHO);
            }
        }
    }

    /**
     * Draws every live effect into {@code batch} with additive blending. Any begun batch works
     * (the shared {@code game.draw().batch()} or a screen's own); particles come from the shared
     * {@link Draw} atlas texture. The batch's previous blend function and colour are restored.
     */
    public void draw(Batch batch) {
        if (liveEffects() == 0 && rings.live() == 0) return;
        int src = batch.getBlendSrcFunc(), dst = batch.getBlendDstFunc();
        int srcA = batch.getBlendSrcFuncAlpha(), dstA = batch.getBlendDstFuncAlpha();
        float oldColor = batch.getPackedColor();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        rings.draw(batch);
        for (int i = 0; i < allPools.size; i++) allPools.get(i).draw(batch);
        batch.setPackedColor(oldColor);
        batch.setBlendFunctionSeparate(src, dst, srcA, dstA);
    }

    /**
     * Removes every effect and stops every hold (begin, retry, quit, hide). Also restarts the
     * auto-drop warm-up, so a song start hitch is never counted.
     */
    public void clear() {
        for (int i = 0; i < allPools.size; i++) allPools.get(i).releaseAll();
        for (int i = 0; i < MAX_HOLDS; i++) {
            holdActive[i] = false;
            holdEffect[i] = null;
            holdRingT[i] = 0f;
        }
        rings.clear();
        quadEstimate = 0;
        ringEstimate = 0;
        probeWarmup = AUTO_DROP_WARMUP_SEC;
        probeTime = 0f;
        probeFrames = 0;
    }

    // ------------------------------------------------------------------ diagnostics
    /** Live effect instances (bursts, streams, marks). */
    public int liveEffects() {
        int n = 0;
        for (int i = 0; i < allPools.size; i++) n += allPools.get(i).liveCount();
        return n;
    }

    /**
     * Particle quads the next {@link #draw} will submit (bursts, hold streams, LOST marks): the
     * quantity of the {@value #QUAD_BUDGET} budget. The water rings add {@link #ringQuads()}.
     */
    public int liveQuads() {
        return poolQuads();
    }

    /** Water-ring quads the next {@link #draw} will submit (started, unfinished rings; DESIGN §20.5). */
    public int ringQuads() {
        return rings.quads();
    }

    private int poolQuads() {
        int n = 0;
        for (int i = 0; i < allPools.size; i++) n += allPools.get(i).quads();
        return n;
    }

    /** Water rings alive, started or still waiting for their 65 ms stagger (diagnostics). */
    public int liveRings() {
        return rings.live();
    }

    /** Water rings spawned so far, never reset (diagnostics and tests). */
    public long ringsSpawned() {
        return rings.spawned();
    }

    /** Bursts spawned so far (taps, seals, LOST marks), never reset (diagnostics and tests). */
    public long burstsSpawned() {
        return burstsSpawned;
    }

    /** Hold streams started so far, never reset (diagnostics and tests). */
    public long streamsSpawned() {
        return streamsSpawned;
    }

    /**
     * Effect instances created so far, live and free (diagnostics). It stays constant during play
     * while the pre-filled pools suffice; every increase was a mid-play allocation.
     */
    public int instances() {
        int n = 0;
        for (int i = 0; i < allPools.size; i++) n += allPools.get(i).created();
        return n;
    }

    /** Releases the pools and prototypes (the atlas belongs to {@link Draw}). */
    @Override
    public void dispose() {
        clear();
        for (int i = 0; i < allPools.size; i++) allPools.get(i).clear(); // drops the free instances
        allPools.clear();
        for (int i = 0; i < prototypes.size; i++) prototypes.get(i).dispose();
        prototypes.clear();
        for (int fx = 0; fx < FX_COUNT; fx++) {
            for (int line = 0; line < 2; line++) {
                for (int tier = 0; tier < TIERS; tier++) pools[fx][line][tier] = null;
            }
        }
    }

    // ------------------------------------------------------------------ internals
    /**
     * Spawns a one-shot effect in the tier of the current quality ({@code reduced}, the hold seal,
     * always starts at Low). Budget guard: while the burst would take the estimated live total
     * past {@link #GUARD_QUADS}, it steps down a tier (High → Low → the ring alone); the last tier
     * is always spawned, so a judgement never goes without its cue.
     */
    private void burst(int fx, boolean sky, float x, float y, float scale, boolean reduced) {
        FxPool[] tiers = pools[fx][sky ? SKY : GROUND];
        int tier = reduced || quality != QUALITY_HIGH ? Q_LOW : Q_HIGH;
        while (tier != Q_RING && tiers[tier] != null && quadEstimate + tiers[tier].cost() > GUARD_QUADS) {
            int next = tier == Q_HIGH ? Q_LOW : Q_RING;
            if (tiers[next] == null) break;
            tier = next;
        }
        FxPool pool = tiers[tier];
        if (pool == null) return;
        pool.spawn(x, y, clampScale(scale), null);
        quadEstimate += pool.cost();
        burstsSpawned++;
    }

    /**
     * The water rings of a head hit (DESIGN §20.5). Budget guard: RESONANCE steps down to the Low
     * pair, then to ring 0 alone, when its rings would take the ring estimate past {@link #RING_GUARD}.
     */
    private void rings(float x, float y, Judgement j, float scale) {
        float s = clampScale(scale);
        if (j == Judgement.RESONANCE) {
            boolean low = quality != QUALITY_HIGH || ringEstimate + WaterRings.RESONANCE_RINGS > RING_GUARD;
            int max = ringEstimate + WaterRings.LOW_RINGS.length > RING_GUARD ? 1 : WaterRings.RESONANCE_RINGS;
            ringEstimate += rings.resonance(x, y, s, low, max);
        } else if (j == Judgement.ECHO) {
            rings.echo(x, y, s);
            ringEstimate++;
        }
    }

    private FxPool pool(int fx, boolean sky) {
        return pools[fx][sky ? SKY : GROUND][quality == QUALITY_HIGH ? Q_HIGH : Q_LOW];
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
        if (quality != QUALITY_HIGH || autoDropped || !autoDropEnabled) return;
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
        autoDropNotice = true;
        quality = QUALITY_LOW;
        if (onAutoDrop != null) onAutoDrop.run();
    }

    /** Applies the Theme colours to a freshly loaded prototype of effect {@code fx}. */
    private static void colour(int fx, ParticleEffect e, Color line) {
        switch (fx) {
            case FX_RESONANCE:
                ParticleAtlasFactory.tint(e.findEmitter("halo"), Theme.RESONANCE);
                ParticleAtlasFactory.tint(e.findEmitter("ring"), Theme.RESONANCE);
                ParticleAtlasFactory.tint(e.findEmitter("core"), Theme.WHITE);
                ParticleAtlasFactory.tint(e.findEmitter("sparks"), Theme.SPARK_HEAD, line);
                break;
            case FX_ECHO:
                ParticleAtlasFactory.tint(e.findEmitter("ring"), Theme.ECHO);
                ParticleAtlasFactory.tint(e.findEmitter("echo ring"), Theme.ECHO);
                ParticleAtlasFactory.tint(e.findEmitter("sparks"), Theme.ECHO, line);
                break;
            case FX_HOLD:
                ParticleAtlasFactory.tint(e.getEmitters().first(), streamHead(line), line);
                break;
            case FX_LOST:
            default: {
                Array<ParticleEmitter> em = e.getEmitters();
                for (int i = 0; i < em.size; i++) ParticleAtlasFactory.tint(em.get(i), Theme.LOST);
                break;
            }
        }
    }

    /**
     * Builds the High pool and, where the design reduces the effect, a separate Low pool; the
     * RESONANCE and ECHO bursts also get a Ring pool for the budget guard.
     */
    private void buildPools(int fx, int line, ParticleEffect high) {
        prototypes.add(high);
        boolean burst = fx != FX_HOLD;
        int prefill = fx == FX_HOLD ? PREFILL_HOLD : fx == FX_LOST ? PREFILL_MARK : PREFILL_BURST;
        FxPool hi = new FxPool(high, burst, prefill);
        allPools.add(hi);
        pools[fx][line][Q_HIGH] = hi;
        if (fx == FX_LOST) {
            pools[fx][line][Q_LOW] = hi;
            return;
        }
        ParticleEffect low = lowVariant(fx, high);
        prototypes.add(low);
        FxPool lo = new FxPool(low, burst, prefill);
        allPools.add(lo);
        pools[fx][line][Q_LOW] = lo;
        if (fx == FX_RESONANCE || fx == FX_ECHO) {
            ParticleEffect ring = ringVariant(high);
            prototypes.add(ring);
            FxPool r = new FxPool(ring, true, prefill);
            allPools.add(r);
            pools[fx][line][Q_RING] = r;
        }
    }

    /** The budget guard's last tier: the burst's crisp ring alone (1 quad). */
    private static ParticleEffect ringVariant(ParticleEffect high) {
        ParticleEffect ring = new ParticleEffect(high);
        ring.setEmittersCleanUpBlendFunction(false);
        Array<ParticleEmitter> em = ring.getEmitters();
        for (int i = em.size - 1; i >= 0; i--) {
            if (!"ring".equals(em.get(i).getName())) em.removeIndex(i);
        }
        return ring;
    }

    /**
     * Low quality (DESIGN §5.9): sparks × 0.5, no core, no echo ring (and no RESONANCE halo, the
     * other soft layer), hold stream 12/s.
     */
    private static ParticleEffect lowVariant(int fx, ParticleEffect high) {
        ParticleEffect low = new ParticleEffect(high);
        low.setEmittersCleanUpBlendFunction(false);
        Array<ParticleEmitter> em = low.getEmitters();
        for (int i = em.size - 1; i >= 0; i--) {
            ParticleEmitter e = em.get(i);
            String name = e.getName();
            if ("core".equals(name) || "halo".equals(name) || "echo ring".equals(name)) {
                em.removeIndex(i);
            } else if ("sparks".equals(name)) {
                int n = Math.max(1, Math.round(e.getMaxParticleCount() * 0.5f));
                e.setMaxParticleCount(n);
                e.setMinParticleCount(n);
            }
        }
        if (fx == FX_HOLD) holdEmission(low, QUALITY_LOW);
        return low;
    }

    /** Bright head colour of a hold mote of colour {@code c} (load time only: allocates). */
    private static Color streamHead(Color c) {
        return new Color(c).lerp(Color.WHITE, STREAM_HEAD_WHITE);
    }

    private static float[] streamTint(Color c) {
        return ParticleAtlasFactory.gradient(streamHead(c), c);
    }
}
