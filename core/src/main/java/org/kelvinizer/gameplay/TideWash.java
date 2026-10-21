package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

/**
 * 潮汐命中效果：一整条柔和光带从判定线浮起、散开，被抬起的水退回淡出，并短暂变暗；潮汐水流按住时，细光带按
 * 固定节奏不断浮起。The TIDE hit effect, v3 (DESIGN §19.2, §20.3, §21.1, §21.6): a soft
 * window-wide band of light lifts off the hit line and floats away as a whole, dissolves, and the
 * water it lifted slides back and fades under a brief darkening; a running TIDE STREAM keeps thin
 * bands peeling off its line on a fixed clock. There is no point effect at the touch x: TIDE hits
 * never reach {@link HitFx}.
 *
 * <ul>
 *   <li><b>Hit</b> ({@link #hit}): across the window (inset {@value #INSET} at each end, soft
 *       ends), from the hit line toward the other line (up from the lower line, down from the
 *       upper line). <i>Rise</i> 0–380 ms: a Gaussian band (exp(−(d/σ)²), σ {@value #BAND_SIGMA},
 *       no core, no line), peak α {@value #BAND_A}, its centre floating {@code pow2Out} to
 *       {@link #REACH_RESONANCE} of the gap (ECHO: lower, {@link #REACH_ECHO}, and fainter,
 *       α × {@value #ECHO_K}, as in the approved mock-up); beneath it the lifted
 *       water, α .15 × (0.35 at the line → 1 at the band) with a soft 55-unit top edge.
 *       <i>Dissolve</i> 300–480 ms: σ × 2.2 while the band fades out. <i>Recede</i> 380–900 ms:
 *       no band; the water's top edge falls back to the line ({@code pow2In}) while it fades.
 *       <i>Veil</i> {@code #02040F}: peak α .16 at 480 ms, gone by 850 ms, over the hit half of
 *       the gap and half as strong over the far half. The line itself swells
 *       ({@link FieldRenderer#swell}) instead of the point ripple.</li>
 *   <li><b>Colours</b> (DESIGN §21.1, §21.6): RESONANCE in {@code tide} on the lower line and
 *       {@code skyTide} on the upper line, ECHO in {@code echo} on both.</li>
 *   <li><b>Several at once</b>: one effect per line per frame (a TIDE group, or TIDEs judged in
 *       the same frame, keep the larger reach); a new one on the same line restarts it; both lines
 *       play at once, floating toward each other. LOST plays nothing.</li>
 *   <li><b>TIDE STREAM</b>: while one runs, its line keeps the low tide level (12 % of the gap,
 *       α .12 breathing ± .03 at 1.2 Hz, the same whether or not a finger covers the line just
 *       then). One emitter per running TIDE STREAM peels thin bands (σ {@value #STREAM_SIGMA},
 *       α {@value #STREAM_A}) off the line at {@link HitFx#holdRate}, the lane STREAM hold particle
 *       rate (22/s High, 12/s Low, none Off); each floats 25 % of the gap in 600 ms
 *       ({@code pow2Out}) while it dims. Band k is born at {@code head + k / rate}, {@code head}
 *       being the stream's head judgement time from the engine, so finger or key swaps never
 *       pause, restart or shift the bands (DESIGN §20.3). {@link #complete}: one rise/recede at
 *       30 % without veil; {@link #drain} (a break): the level drains to the line over 150 ms
 *       under a veil of α .12. Either way emission stops and the bands in flight finish.</li>
 *   <li>{@code reduceFlashing}: α × .5, no veil, reach at most 30 %. Particle quality Off keeps
 *       the rise and recede, without the dissolve widening, the line swell or the stream bands.</li>
 * </ul>
 *
 * <p><b>Layering and blend.</b> Drawn in normal block A after the lane field
 * ({@link FieldRenderer#drawUnder}) and before the HUD shapes, so it lies above the backdrop and
 * lane field and beneath the notes, the judgement word and the HUD (DESIGN §19.2). The design draws
 * the bands, the water and the level additively; an additive pass beneath the notes would cost a
 * blend switch, i.e. two more draw calls. They are drawn with the normal blend instead, in an
 * additive-equivalent colour: colour c at α becomes {@link #FIELD} + c / {@link #ADD_GAIN} at
 * α × {@link #ADD_GAIN}, which over {@code FIELD} (the field colour measured in game beneath the
 * gap) adds exactly c × α, and over the field's other dark blues stays within a few percent of α
 * of it. Plain normal blending instead loses α × the background, mostly in blue, so the band would
 * read greener and dimmer than the approved mock-up clip of the TIDE drop (v4).
 * A band also lies on the lifted water or the level, which the normal blend would partly cover:
 * its α is divided by (1 − the α beneath its centre) ({@link #over}), so the stack adds up there
 * as the additive design does. The veil is a normal-blend darkening by design. Bands use the
 * {@code glow_v} gaussian of the shared atlas, the water and the veils vertex-coloured
 * {@code white}: per-vertex quads, no extra draw call, no allocation after construction. Hit
 * effects run on run seconds, stream bands on the engine's judge time; both freeze while paused.
 */
final class TideWash {
    // ---- hit (rise / dissolve / recede / veil), seconds
    static final float RISE_SEC = 0.38f;
    static final float DISSOLVE_FROM_SEC = 0.30f;
    static final float DISSOLVE_TO_SEC = 0.48f;
    static final float RECEDE_FROM_SEC = 0.38f;
    static final float END_SEC = 0.90f;
    static final float VEIL_FROM_SEC = 0.38f;
    static final float VEIL_PEAK_SEC = 0.48f;
    static final float VEIL_END_SEC = 0.85f;
    /** Fade-in of the band and water at the line (not in the design; keeps the band from popping on). */
    static final float BAND_IN_SEC = 0.04f;
    /** Band profile exp(−(d/σ)²) (world units). */
    static final float BAND_SIGMA = 30f;
    static final float DISSOLVE_WIDEN = 2.2f;
    static final float BAND_A = 0.30f;
    static final float WATER_A = 0.15f;
    /** Water α at the line, as a share of {@link #WATER_A} (1 at the band). */
    static final float WATER_LINE_K = 0.35f;
    /** Soft top edge of the lifted water (world units). */
    static final float WATER_EDGE = 55f;
    static final float VEIL_A = 0.16f;
    /** Veil strength on the far half of the gap, as a share of the hit half. */
    static final float VEIL_FAR_K = 0.5f;
    /** Half height of the soft step between the veil's hit half and far half (world units). */
    static final float VEIL_STEP = 40f;
    /** Reach of a RESONANCE hit (fraction of the gap between the lines). */
    static final float REACH_RESONANCE = 0.45f;
    /** Reach of an ECHO hit. */
    static final float REACH_ECHO = 0.30f;
    /**
     * α factor of an ECHO rise/recede and its line swell: the approved mock-up draws ECHO "lower and
     * fainter" (its caption: 浮得低一些、淡一些); measured on {@code v4_tide_drop.mp4}, its ECHO
     * band peaks at .72 of the RESONANCE band on both lines.
     */
    static final float ECHO_K = 0.72f;
    /** Reach of the rise/recede of a completed TIDE STREAM. */
    static final float REACH_COMPLETE = 0.30f;
    /** Reach cap with {@code reduceFlashing}. */
    static final float REACH_REDUCED = 0.30f;
    // ---- TIDE STREAM level, drain and light bands
    /** Tide level of a running TIDE STREAM (fraction of the gap). */
    static final float LEVEL_REACH = 0.12f;
    static final float LEVEL_A = 0.12f;
    static final float LEVEL_BREATH = 0.03f;
    static final float LEVEL_HZ = 1.2f;
    static final float LEVEL_RISE_SEC = 0.18f;
    static final float LEVEL_FALL_SEC = 0.25f;
    static final float DRAIN_SEC = 0.15f;
    static final float DRAIN_VEIL_A = 0.12f;
    /** Stream band profile exp(−(d/σ)²) (world units). */
    static final float STREAM_SIGMA = 9f;
    static final float STREAM_A = 0.13f;
    /** Travel of a stream band (fraction of the gap) over its life. */
    static final float STREAM_TRAVEL = 0.25f;
    static final float STREAM_LIFE_MS = 600f;
    /**
     * A stream whose emitter starts later than this after its head (judge ms) was running while
     * the bands were off (quality Off): its bands start now instead of replaying the past.
     */
    static final float LATE_START_MS = 250f;
    /** Emitters (band segments) at once: one per running TIDE STREAM, plus ending ones. */
    static final int MAX_EMITTERS = 12;
    // ---- additive-equivalent drawing (see the class notes)
    /** Field colour beneath the gap that the normal-blend draw is matched to (measured in game). */
    static final Color FIELD = new Color(0x0B1A35FF);
    /** α factor of the additive-equivalent draw; at least 1.26 keeps every wash colour within 1. */
    static final float ADD_GAIN = 1.3f;
    // ---- geometry
    /** Inset of every band, water and veil from each window end (world units). */
    static final float INSET = 12f;
    /** Soft end of the bands, water and veils inside the inset (world units). */
    static final float END_FEATHER = 24f;
    /**
     * Smallest gap the effect measures (lines closer than two judgement zones still get one), in reference
     * units; the world gap is stretched with the zones ({@link Field#dist}).
     */
    static final float MIN_GAP = 2f * org.kelvinizer.Params.JUDGE_BAND_HALF;
    /** {@code glow_v} height per unit of σ: its profile is exp(−4.5 t²) over t ∈ [−1, 1]. */
    static final float GLOW_V_PER_SIGMA = 2f * (float) Math.sqrt(4.5);
    /** Quads per horizontal strip (two soft ends and the middle). */
    static final int STRIP_QUADS = 3;
    static final int LEVEL_QUADS = 2 * STRIP_QUADS;
    static final int WATER_QUADS = 2 * STRIP_QUADS;
    static final int BAND_QUADS = STRIP_QUADS;
    static final int VEIL_QUADS = 3 * STRIP_QUADS;

    private final Batch batch;
    private final Texture tex;
    private final FieldRenderer field;
    private final float wu;
    private final float wv;
    private final float gu;
    private final float gv0;
    private final float gv1;
    private final float[] q = new float[20];
    /** Column edges of the current window: outer left, inner left, inner right, outer right. */
    private float xa, xb, xc, xd;

    // hit effect per line
    private final float[] effT = new float[Line.COUNT];
    private final float[] effReach = new float[Line.COUNT];
    private final boolean[] effVeil = new boolean[Line.COUNT];
    private final boolean[] effEcho = new boolean[Line.COUNT];
    // tide level and drain per line
    private final float[] level = new float[Line.COUNT];
    private final float[] drainT = new float[Line.COUNT];
    private final float[] drainH = new float[Line.COUNT];
    private final float[] drainA = new float[Line.COUNT];
    // band emitters (struct of arrays); a rate change splits an emitter into segments
    private final boolean[] emActive = new boolean[MAX_EMITTERS];
    private final boolean[] emSeen = new boolean[MAX_EMITTERS];
    private final int[] emNote = new int[MAX_EMITTERS];
    private final int[] emLine = new int[MAX_EMITTERS];
    private final boolean[] emEcho = new boolean[MAX_EMITTERS];
    /** Head judgement time of the stream (judge ms): band k is born at origin + k·period. */
    private final double[] emOrigin = new double[MAX_EMITTERS];
    private final double[] emPeriod = new double[MAX_EMITTERS];
    /** The segment's bands are born after this time (exclusive; −∞ from the head on). */
    private final double[] emFrom = new double[MAX_EMITTERS];
    /** ... and at or before this one (+∞ while the segment emits). */
    private final double[] emEnd = new double[MAX_EMITTERS];
    // rate change scratch (no allocation)
    private final int[] reNote = new int[MAX_EMITTERS];
    private final int[] reLine = new int[MAX_EMITTERS];
    private final boolean[] reEcho = new boolean[MAX_EMITTERS];
    private final double[] reOrigin = new double[MAX_EMITTERS];

    private boolean reduce;
    private int quality = HitFx.QUALITY_HIGH;
    private float rate = HitFx.holdRate(HitFx.QUALITY_HIGH);
    private float now;
    private double songMs;
    private long triggers;
    private long swells;

    /** Creates the effect over the shared atlas; the line swell of a hit goes to {@code field}. */
    TideWash(Draw draw, FieldRenderer field) {
        this(draw.batch(), draw.atlasTexture(), draw.white(), draw.glowV(), field);
    }

    /**
     * Creates the effect over the regions {@code white} and {@code glowV} of {@code tex}, drawn
     * into {@code batch}; {@code field} may be null (no line swell). Tests use it with a
     * recording batch, without GL.
     */
    TideWash(Batch batch, Texture tex, TextureRegion white, TextureRegion glowV, FieldRenderer field) {
        this.field = field;
        this.batch = batch;
        this.tex = tex;
        wu = 0.5f * (white.getU() + white.getU2());
        wv = 0.5f * (white.getV() + white.getV2());
        gu = 0.5f * (glowV.getU() + glowV.getU2());
        gv0 = glowV.getV();
        gv1 = glowV.getV2();
        clear();
    }

    /** Clears every running effect, level, drain and stream band. */
    void clear() {
        for (int l = 0; l < Line.COUNT; l++) {
            effT[l] = -100f;
            effReach[l] = 0f;
            effVeil[l] = false;
            effEcho[l] = false;
            level[l] = 0f;
            drainT[l] = -100f;
            drainH[l] = 0f;
            drainA[l] = 0f;
        }
        for (int s = 0; s < MAX_EMITTERS; s++) emActive[s] = false;
    }

    /**
     * Sets this frame's style: {@code reduceFlashing} (α × .5, no veil, reach ≤ 30 %) and the
     * particle quality ({@code HitFx.QUALITY_*}: the stream band rate; Off also drops the dissolve
     * widening and the line swell). A rate change ends the emitting segments now and continues
     * them at the new rate on the same head clock.
     */
    void setStyle(boolean reduceFlashing, int particleQuality) {
        reduce = reduceFlashing;
        quality = particleQuality;
        float r = HitFx.holdRate(particleQuality);
        if (r == rate) return;
        rate = r;
        int n = 0;
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (!emActive[s] || emEnd[s] != Double.POSITIVE_INFINITY) continue;
            emEnd[s] = songMs;
            reNote[n] = emNote[s];
            reLine[n] = emLine[s];
            reEcho[n] = emEcho[s];
            reOrigin[n] = emOrigin[s];
            n++;
        }
        if (rate <= 0f) return;
        for (int j = 0; j < n; j++) segment(reNote[j], reLine[j], reEcho[j], reOrigin[j], songMs);
    }

    // ------------------------------------------------------------------ triggers

    /**
     * A TIDE (drop or TIDE STREAM head, or a whole group) judged on {@code line} at run time
     * {@code now}: the rise, dissolve, recede and veil (RESONANCE reaches 45 % of the gap, ECHO
     * 30 %) and, unless the particle quality is Off, the line swell.
     */
    void hit(Line line, boolean resonance, float now) {
        if (start(line, resonance ? REACH_RESONANCE : REACH_ECHO, true, !resonance, now)
            && quality != HitFx.QUALITY_OFF) {
            swells++;
            if (field != null) {
                field.swell(line, colour(line, !resonance), (reduce ? 0.5f : 1f) * (resonance ? 1f : ECHO_K), now);
            }
        }
    }

    /** A TIDE STREAM of {@code line} completed: one rise/recede at 30 % in its head's colour, no veil. */
    void complete(Line line, boolean resonance, float now) {
        start(line, REACH_COMPLETE, false, !resonance, now);
    }

    /**
     * Starts (or, in the same frame, merges into) the effect of a line.
     *
     * @return true when the line's effect starts or takes this trigger's look
     */
    private boolean start(Line line, float reach, boolean veil, boolean echo, float t) {
        if (line == null) return false;
        int l = line.ordinal();
        triggers++;
        if (effT[l] == t) { // one effect per line per frame: keep the larger reach
            effVeil[l] |= veil;
            if (reach <= effReach[l]) return false;
            effReach[l] = reach;
            effEcho[l] = echo;
            return true;
        }
        effT[l] = t;
        effReach[l] = reach;
        effVeil[l] = veil;
        effEcho[l] = echo;
        return true;
    }

    /** A TIDE STREAM of {@code line} broke: its level drains back to the line over 150 ms. */
    void drain(Line line, float now) {
        if (line == null) return;
        int l = line.ordinal();
        if (level[l] <= 0.001f) return;
        drainT[l] = now;
        drainH[l] = level[l] * LEVEL_REACH;
        drainA[l] = levelAlpha(l, now);
        level[l] = 0f;
    }

    // ------------------------------------------------------------------ update

    /**
     * Per frame, after the events of the frame: eases each line's tide level toward "a TIDE
     * STREAM of this line runs" and keeps one band emitter per running TIDE STREAM.
     *
     * @param dt  run delta (0 while paused)
     * @param now run seconds
     */
    void update(float dt, GameEngine e, float now) {
        this.now = now;
        songMs = e.judgeMs();
        for (int l = 0; l < Line.COUNT; l++) {
            if (e.tideRunning(Line.of(l))) {
                level[l] = Math.min(1f, level[l] + dt / LEVEL_RISE_SEC);
                drainT[l] = -100f;
            } else if (level[l] > 0f) {
                level[l] = Math.max(0f, level[l] - dt / LEVEL_FALL_SEC);
            }
        }
        updateEmitters(e);
    }

    private void updateEmitters(GameEngine e) {
        for (int s = 0; s < MAX_EMITTERS; s++) emSeen[s] = false;
        Chart chart = e.chart();
        if (chart != null) {
            for (int k = 0; k < e.activeHoldCount(); k++) {
                int i = e.activeHold(k);
                Note n = chart.note(i);
                if (!n.isTideStream()) continue;
                int s = emitting(i);
                if (s < 0 && rate > 0f) {
                    float head = e.noteResolvedMs(i);
                    double origin = Float.isNaN(head) ? songMs : head;
                    double from = songMs - origin > LATE_START_MS ? songMs : Double.NEGATIVE_INFINITY;
                    s = segment(i, n.line().ordinal(), e.noteJudgement(i) == Judgement.ECHO, origin, from);
                }
                if (s >= 0) emSeen[s] = true;
            }
        }
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (!emActive[s]) continue;
            if (emEnd[s] == Double.POSITIVE_INFINITY && !emSeen[s]) {
                // the stream ended (completed or broke): emission stops at its end time
                float end = chart != null ? e.noteResolvedMs(emNote[s]) : Float.NaN;
                emEnd[s] = Float.isNaN(end) ? songMs : Math.min(songMs, end);
            }
            if (emEnd[s] != Double.POSITIVE_INFINITY && songMs - emEnd[s] >= STREAM_LIFE_MS) emActive[s] = false;
        }
    }

    /** Slot of the emitting segment of note {@code i}, or −1. */
    private int emitting(int i) {
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (emActive[s] && emNote[s] == i && emEnd[s] == Double.POSITIVE_INFINITY) return s;
        }
        return -1;
    }

    /**
     * Starts an emitting segment in a free slot, else in the ended segment that ended first
     * (its last bands vanish early); −1 when every slot emits.
     */
    private int segment(int note, int line, boolean echo, double origin, double from) {
        int slot = -1;
        double first = Double.POSITIVE_INFINITY;
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (!emActive[s]) {
                slot = s;
                break;
            }
            if (emEnd[s] < first) {
                first = emEnd[s];
                slot = s;
            }
        }
        if (slot < 0) return -1;
        emActive[slot] = true;
        emNote[slot] = note;
        emLine[slot] = line;
        emEcho[slot] = echo;
        emOrigin[slot] = origin;
        emPeriod[slot] = 1000.0 / rate;
        emFrom[slot] = from;
        emEnd[slot] = Double.POSITIVE_INFINITY;
        return slot;
    }

    // ------------------------------------------------------------------ queries

    /** @return true when nothing is drawn this frame. */
    boolean idle() {
        for (int l = 0; l < Line.COUNT; l++) {
            if (effActive(l) || level[l] > 0f || drainActive(l)) return false;
        }
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (emActive[s]) return false;
        }
        return true;
    }

    private boolean effActive(int l) {
        float t = now - effT[l];
        return t >= 0f && t < END_SEC;
    }

    private boolean drainActive(int l) {
        float t = now - drainT[l];
        return t >= 0f && t < DRAIN_SEC;
    }

    /**
     * @return upper bound of the quads {@link #draw} submits this frame (call after
     *         {@link #update} and {@link #setStyle}); feeds the trail budget, since block A can
     *         share a flush with block B
     */
    int quadsBound() {
        int n = 0;
        for (int l = 0; l < Line.COUNT; l++) {
            if (effActive(l)) n += WATER_QUADS + BAND_QUADS + VEIL_QUADS;
            if (drainActive(l)) n += LEVEL_QUADS + VEIL_QUADS;
            else if (level[l] > 0f) n += LEVEL_QUADS;
        }
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (emActive[s] && !duplicate(s)) n += BAND_QUADS * (int) Math.max(0L, bandLast(s) - bandFirst(s) + 1);
        }
        return n;
    }

    private float levelAlpha(int l, float t) {
        return (LEVEL_A + LEVEL_BREATH * MathUtils.sin(MathUtils.PI2 * LEVEL_HZ * t)) * level[l];
    }

    /** Index of the oldest band of segment {@code s} still in flight (greater than {@link #bandLast} when none). */
    private long bandFirst(int s) {
        double lo = Math.max(songMs - STREAM_LIFE_MS, emFrom[s]);
        return Math.max(0L, (long) Math.floor((lo - emOrigin[s]) / emPeriod[s]) + 1);
    }

    /** Index of the newest band of segment {@code s} born so far (−1 when none). */
    private long bandLast(int s) {
        double hi = Math.min(songMs, emEnd[s]);
        if (hi < emOrigin[s]) return -1;
        return (long) Math.floor((hi - emOrigin[s]) / emPeriod[s]);
    }

    /** True for the second emitter of one TIDE group on one line: its bands coincide, so it draws nothing. */
    private boolean duplicate(int s) {
        for (int o = 0; o < s; o++) {
            if (emActive[o] && emLine[o] == emLine[s] && emOrigin[o] == emOrigin[s] && emPeriod[o] == emPeriod[s]
                && emFrom[o] == emFrom[s] && emEnd[o] == emEnd[s] && emEcho[o] == emEcho[s]) {
                return true;
            }
        }
        return false;
    }

    /** RESONANCE: {@code tide} (lower line) or {@code skyTide} (upper line); ECHO: {@code echo}. */
    static Color colour(Line line, boolean echo) {
        if (echo) return Theme.ECHO;
        return line == Line.UPPER ? Theme.SKY_TIDE : Theme.TIDE;
    }

    private static final Color DRAW_TIDE = additiveEquivalent(Theme.TIDE);
    private static final Color DRAW_SKY_TIDE = additiveEquivalent(Theme.SKY_TIDE);
    private static final Color DRAW_ECHO = additiveEquivalent(Theme.ECHO);

    /** The colour the bands, water and level of {@link #colour} are drawn in (at α × {@link #ADD_GAIN}). */
    static Color drawColour(Line line, boolean echo) {
        if (echo) return DRAW_ECHO;
        return line == Line.UPPER ? DRAW_SKY_TIDE : DRAW_TIDE;
    }

    /**
     * {@link #FIELD} + c / {@link #ADD_GAIN} per channel, opaque: drawn with the normal blend at
     * α × {@code ADD_GAIN} over {@code FIELD} it gives {@code FIELD} + c × α, the additive result.
     */
    static Color additiveEquivalent(Color c) {
        return new Color(Math.min(1f, FIELD.r + c.r / ADD_GAIN), Math.min(1f, FIELD.g + c.g / ADD_GAIN),
            Math.min(1f, FIELD.b + c.b / ADD_GAIN), 1f);
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the levels, drains, stream bands and hit effects of both lines, then their veils
     * (normal block A, after the lane field, before the HUD shapes). The batch colour is left
     * unchanged.
     */
    void draw(GameEngine e) {
        if (idle()) return;
        LaneLayout lay = e.layout();
        float x0 = lay.windowLeft() + INSET;
        float x1 = lay.windowRight() - INSET;
        float w = x1 - x0;
        if (!(w > 8f)) return;
        float f = Math.min(END_FEATHER, w * 0.25f);
        xa = x0;
        xb = x0 + f;
        xc = x1 - f;
        xd = x1;
        // α factor of every additive part: reduceFlashing halves it, the normal-blend draw scales it
        float am = (reduce ? 0.5f : 1f) * ADD_GAIN;
        boolean widen = quality != HitFx.QUALITY_OFF;
        for (int l = 0; l < Line.COUNT; l++) {
            Line line = Line.of(l);
            float ly = e.lineY(line);
            float gap = gap(e, line);
            float dir = line == Line.UPPER ? -1f : 1f;
            Color tide = drawColour(line, false);
            // tide level of a running TIDE STREAM, or its drain after a break
            float lh = 0f, la = 0f;
            if (drainActive(l)) {
                float p = (now - drainT[l]) / DRAIN_SEC;
                lh = drainH[l] * gap * (1f - Anim.POW2_IN.apply(p));
                la = drainA[l] * (1f - p) * am;
            } else if (level[l] > 0f) {
                lh = level[l] * LEVEL_REACH * gap;
                la = levelAlpha(l, now) * am;
            }
            levelWater(ly, dir, lh, la, tide);
            // light bands of the running (and just ended) TIDE STREAMs, over the level
            for (int s = 0; s < MAX_EMITTERS; s++) {
                if (emActive[s] && emLine[s] == l && !duplicate(s)) streamBands(s, ly, dir, gap, am, lh, la);
            }
            // the hit: lifted water, then the band
            if (!effActive(l)) continue;
            float t = now - effT[l];
            float reach = reduce ? Math.min(effReach[l], REACH_REDUCED) : effReach[l];
            float peak = reach * gap;
            Color c = drawColour(line, effEcho[l]);
            float in = Math.min(1f, t / BAND_IN_SEC);
            float top;
            float wa;
            if (t < RECEDE_FROM_SEC) {
                top = peak * Anim.POW2_OUT.apply(t / RISE_SEC);
                wa = WATER_A * in;
            } else {
                float p = (t - RECEDE_FROM_SEC) / (END_SEC - RECEDE_FROM_SEC);
                top = peak * (1f - Anim.POW2_IN.apply(p));
                wa = WATER_A * (1f - p);
            }
            float ek = effEcho[l] ? ECHO_K : 1f;
            hitWater(ly, dir, top, wa * am * ek, c);
            if (t < DISSOLVE_TO_SEC) {
                float centre = peak * Anim.POW2_OUT.apply(Math.min(1f, t / RISE_SEC));
                float sigma = BAND_SIGMA;
                float a = BAND_A * in;
                if (t > DISSOLVE_FROM_SEC) {
                    float p = (t - DISSOLVE_FROM_SEC) / (DISSOLVE_TO_SEC - DISSOLVE_FROM_SEC);
                    if (widen) sigma *= 1f + (DISSOLVE_WIDEN - 1f) * p;
                    a *= 1f - p;
                }
                // over the lifted water: see over()
                band(ly + dir * centre, sigma, over(a * am * ek, waterAt(centre, top, wa * am * ek)), c);
            }
        }
        if (reduce) return;
        // veils last, so a veil also dims the other line's band beneath it
        for (int l = 0; l < Line.COUNT; l++) {
            float v = veilAlpha(l);
            if (v <= 0.002f) continue;
            Line line = Line.of(l);
            veil(e.lineY(line), line == Line.UPPER ? -1f : 1f, gap(e, line), v);
        }
    }

    /** Veil α of line {@code l} now: the hit's (not a completion's) and a drain's, the larger. */
    private float veilAlpha(int l) {
        float v = 0f;
        if (effActive(l) && effVeil[l]) {
            float t = now - effT[l];
            if (t > VEIL_FROM_SEC && t < VEIL_END_SEC) {
                v = t < VEIL_PEAK_SEC
                    ? VEIL_A * (t - VEIL_FROM_SEC) / (VEIL_PEAK_SEC - VEIL_FROM_SEC)
                    : VEIL_A * (VEIL_END_SEC - t) / (VEIL_END_SEC - VEIL_PEAK_SEC);
            }
        }
        if (drainActive(l)) {
            float p = (now - drainT[l]) / DRAIN_SEC;
            v = Math.max(v, DRAIN_VEIL_A * (p < 0.3f ? p / 0.3f : (1f - p) / 0.7f));
        }
        return v;
    }

    private static float gap(GameEngine e, Line line) {
        float ly = e.lineY(line);
        return Math.max(Field.dist(MIN_GAP), Math.abs(e.lineY(line == Line.UPPER ? Line.LOWER : Line.UPPER) - ly));
    }

    /**
     * Drawn α of a band that lies on a wash part of drawn α {@code under} at its centre. The
     * normal blend covers {@code under} by the band's own α, which the design's additive band
     * does not; dividing by (1 − under) restores the sum at the band's centre, so the stack adds
     * up as the additive design does there.
     */
    static float over(float a, float under) {
        return a / (1f - MathUtils.clamp(under, 0f, 0.8f));
    }

    /**
     * Drawn α of the lifted water at height {@code h} above the line (the profile of
     * {@link #hitWater}): {@code a} × (.35 → 1) from the line to {@code top}, then the soft edge.
     */
    static float waterAt(float h, float top, float a) {
        if (a <= 0.002f || h < 0f) return 0f;
        if (top > 0.5f && h <= top) return a * (WATER_LINE_K + (1f - WATER_LINE_K) * h / top);
        float base = Math.max(0f, top);
        float edge = top > 0.5f ? a : WATER_LINE_K * a;
        float e = (h - base) / WATER_EDGE;
        return e >= 1f ? 0f : edge * (1f - e);
    }

    /** Drawn α of the tide level at height {@code h} (the profile of {@link #levelWater}). */
    static float levelAt(float h, float lh, float a) {
        if (!(lh > 0.5f) || a <= 0.002f || h < 0f || h >= lh) return 0f;
        float hm = 0.5f * lh;
        return h <= hm ? a * (1f - 0.3f * h / hm) : 0.7f * a * (lh - h) / hm;
    }

    /** The low tide level: α {@code a} at the line easing to 0 at height {@code h}. */
    private void levelWater(float ly, float dir, float h, float a, Color c) {
        if (!(h > 0.5f) || a <= 0.002f) return;
        float hm = 0.5f * h;
        strip(ly, ly + dir * hm, c, a, 0.7f * a, wu, wv, wv);
        strip(ly + dir * hm, ly + dir * h, c, 0.7f * a, 0f, wu, wv, wv);
    }

    /** Lifted water: .35 → 1 of {@code a} from the line up to {@code top}, then the soft edge above it. */
    private void hitWater(float ly, float dir, float top, float a, Color c) {
        if (a <= 0.002f) return;
        float edge = WATER_LINE_K * a;
        if (top > 0.5f) {
            strip(ly, ly + dir * top, c, WATER_LINE_K * a, a, wu, wv, wv);
            edge = a;
        }
        float base = Math.max(0f, top);
        strip(ly + dir * base, ly + dir * (base + WATER_EDGE), c, edge, 0f, wu, wv, wv);
    }

    /** A soft band of profile exp(−(d/σ)²) centred at y with peak α {@code a} ({@code glow_v}). */
    private void band(float y, float sigma, float a, Color c) {
        if (a <= 0.002f) return;
        float hh = 0.5f * GLOW_V_PER_SIGMA * sigma;
        strip(y - hh, y + hh, c, a, a, gu, gv1, gv0);
    }

    /**
     * The bands in flight of segment {@code s}: born at origin + k·period, 25 % of the gap in 600 ms,
     * over the line's level (height {@code lh}, drawn α {@code la} at the line).
     */
    private void streamBands(int s, float ly, float dir, float gap, float am, float lh, float la) {
        long k1 = bandLast(s);
        Color c = drawColour(Line.of(emLine[s]), emEcho[s]);
        for (long k = bandFirst(s); k <= k1; k++) {
            double born = emOrigin[s] + k * emPeriod[s];
            float p = (float) ((songMs - born) / STREAM_LIFE_MS);
            if (p < 0f || p >= 1f) continue;
            float d = STREAM_TRAVEL * gap * Anim.POW2_OUT.apply(p);
            band(ly + dir * d, STREAM_SIGMA, over(STREAM_A * (1f - p) * am, levelAt(d, lh, la)), c);
        }
    }

    /** The veil over the gap: {@code v} on the hit half, {@link #VEIL_FAR_K} of it on the far half. */
    private void veil(float ly, float dir, float gap, float v) {
        Color d = Theme.ABYSS_BLACK;
        float m = 0.5f * gap;
        float s = Math.min(VEIL_STEP, 0.25f * gap);
        strip(ly, ly + dir * (m - s), d, v, v, wu, wv, wv);
        strip(ly + dir * (m - s), ly + dir * (m + s), d, v, VEIL_FAR_K * v, wu, wv, wv);
        strip(ly + dir * (m + s), ly + dir * gap, d, VEIL_FAR_K * v, VEIL_FAR_K * v, wu, wv, wv);
    }

    /**
     * One horizontal strip across the window from {@code yNear} to {@code yFar}: α {@code aNear}
     * → {@code aFar} across it, soft over {@link #END_FEATHER} at both window ends (three quads);
     * texture column u, v {@code vNear} → {@code vFar}.
     */
    private void strip(float yNear, float yFar, Color c, float aNear, float aFar, float u, float vNear, float vFar) {
        float cn = Color.toFloatBits(c.r, c.g, c.b, MathUtils.clamp(aNear, 0f, 1f));
        float cf = Color.toFloatBits(c.r, c.g, c.b, MathUtils.clamp(aFar, 0f, 1f));
        float z = Color.toFloatBits(c.r, c.g, c.b, 0f);
        quad(xa, yNear, z, xa, yFar, z, xb, yFar, cf, xb, yNear, cn, u, vNear, vFar);
        quad(xb, yNear, cn, xb, yFar, cf, xc, yFar, cf, xc, yNear, cn, u, vNear, vFar);
        quad(xc, yNear, cn, xc, yFar, cf, xd, yFar, z, xd, yNear, z, u, vNear, vFar);
    }

    /**
     * One quad: corners 1–4 in order (left near, left far, right far, right near), with texture
     * coordinate u and v {@code vNear} at corners 1 and 4, {@code vFar} at corners 2 and 3.
     */
    private void quad(float x1, float y1, float c1, float x2, float y2, float c2, float x3, float y3, float c3,
                      float x4, float y4, float c4, float u, float vNear, float vFar) {
        float[] v = q;
        v[0] = x1; v[1] = y1; v[2] = c1; v[3] = u; v[4] = vNear;
        v[5] = x2; v[6] = y2; v[7] = c2; v[8] = u; v[9] = vFar;
        v[10] = x3; v[11] = y3; v[12] = c3; v[13] = u; v[14] = vFar;
        v[15] = x4; v[16] = y4; v[17] = c4; v[18] = u; v[19] = vNear;
        batch.draw(tex, v, 0, 20);
    }

    // ------------------------------------------------------------------ diagnostics (tests)

    /** @return hit and completion triggers so far, merged ones included (tests). */
    long triggers() {
        return triggers;
    }

    /** @return line swells requested so far (tests). */
    long swells() {
        return swells;
    }

    /** @return the tide level of {@code line} (0..1). */
    float level(Line line) {
        return level[line.ordinal()];
    }

    /** @return band emitters (segments) alive, emitting or finishing. */
    int emitters() {
        int n = 0;
        for (int s = 0; s < MAX_EMITTERS; s++) {
            if (emActive[s]) n++;
        }
        return n;
    }

    /** @return the stream band rate of this frame (bands per second). */
    float rate() {
        return rate;
    }
}
