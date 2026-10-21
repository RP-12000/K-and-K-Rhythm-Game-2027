package org.kelvinizer.gameplay;

import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.Params;
import org.kelvinizer.elements.Note;

/**
 * 音符外形的纯几何：天地长条和潮汐水流重叠时的挖空、TIDE 的 T4 外形（天键珍珠串、地键滚浪）、同步珍珠和轨道水雷柱。
 * 渲染器按这里的数字画，测试直接检查这些数字。
 *
 * <p>Pure note-shape geometry: no GL and no allocation (every output array belongs to the
 * caller). {@link NoteRenderer} draws with these numbers and the tests check them directly.
 * <ul>
 *   <li><b>Sky and ground bodies at the same time</b> (DESIGN §21.7; Kelvin kept the redraw on
 *       2026-10-12 04:44, 「长条改的也很自然，先保留吧我再看看」). Lane STREAM ribbons: sky 56 % of
 *       the lane, ground 82 %, sky edges 6 px, fill fading head → tail, a 3 px tail cap in the
 *       ribbon's own colour. Where a ground ribbon overlaps a sky ribbon of the same lane, the
 *       ground fill leaves a hole under the sky ribbon, {@link #SKY_HOLE_PAD} wider on each side;
 *       the ground edges stay. TIDE STREAM bands: the sky band is inset
 *       {@link #SKY_TIDE_STREAM_INSET} from each window end; in the rows where a ground band
 *       overlaps a sky band the ground band draws nothing under it (with an inset, only its side
 *       strips and, when they hold them, its rails). Tail caps always draw. The two constants
 *       switch between the redraw (0 / 0) and the first proposal (3 / 56); every function takes
 *       them as parameters, so the tests cover both.</li>
 *   <li><b>T4 TIDE drop</b> (Kelvin 2026-10-12 04:36, 「天键地键tide的改为T4」; numbers from the T4 style
 *       of the mock-ups): the sky TIDE is a
 *       string of pearls, the ground TIDE a rolling wave whose crest moves with song time.
 *       "Behind" is the side the note comes from; {@code u} is the note size setting.</li>
 *   <li><b>Sync pearls</b> (Kelvin 2026-10-12 12:33, 12:39 「同一时刻都带」, 12:42 gold): every pending note
 *       that shares its beat with another note ({@link Note#sharesBeat()}) carries pearls: three gold
 *       ones side by side across the middle of a DROP or STREAM head, three smaller red ones across a
 *       MINE or LANE MINE head (16:07, 「不是1个珍珠而是3个珍珠并排」); a sky TIDE's pearls turn gold; a ground TIDE wave carries a
 *       row of gold pearls, large and small in turn like the sky thread's, halfway up the wave, and (a proposal)
 *       a TIDE STREAM head the same row along the middle of its bar.</li>
 *   <li><b>LANE MINE column</b> (DESIGN §20.2): inset, hazard stripes drifting toward the line,
 *       studs, and the gap it leaves around the other judgement line (Kelvin 2026-10-12 02:58).</li>
 * </ul>
 */
public final class NoteShapes {
    // ================================================================== lane note anchor
    /**
     * Distance from its line (reference units) within which a lane note or mine eases from its centred place
     * back to touching the line ({@link #centreShiftY}): 333.33 (300 in the old 1080-high frame).
     */
    public static final float CENTRE_EASE = 1000f / 3f;
    /**
     * Height of the reference body in reference units per world unit of a size-1.0 body: it is measured on a
     * 16 : 9 screen (1080 high) and the frame is 1200 high, so 1200 / 1080 (it was 1 before 2026-10-19).
     */
    static final float BODY_REF_K = Params.REF_H / 1080f;

    /**
     * x part of {@link #centreShiftY}: the stretch across the travel is {@code kx}.
     *
     * @return world x shift of the leading-edge centre
     */
    public static float centreShiftX(float pos, float refH, float h, float rot) {
        float e = centreEase(pos);
        if (e == 0f) return 0f;
        return -MathUtils.sinDeg(rot) * (refH * 0.5f * Field.kx() - h * 0.5f) * e;
    }

    /**
     * Shift of a lane note's or mine's leading edge (Kelvin 2026-10-13: a row of sky keys and a row of ground keys at
     * the same distance from their lines must line up at any note size and screen ratio). A chart distance is
     * measured to the near edge of the reference body ({@code refH} tall, note size 1.0, 16 : 9). Away from the
     * line the drawn body ({@code h} tall, rigid) is centred where that reference body's centre lands after the
     * playfield stretch, so its edge moves by {@code refH/2·k − h/2} along its own axis ({@code rot} in degrees,
     * +180 on the sky line); within {@link #CENTRE_EASE} of the line it eases back (smoothstep) and the edge
     * touches the line at the judgement time as before. 0 at note size 1.0 on a 16 : 9 screen.
     *
     * @param pos the note's world distance from its line ({@link Field#dist} of its chart distance)
     * @return world y shift of the leading-edge centre
     */
    public static float centreShiftY(float pos, float refH, float h, float rot) {
        float e = centreEase(pos);
        if (e == 0f) return 0f;
        return MathUtils.cosDeg(rot) * (refH * 0.5f * BODY_REF_K * Field.ky() - h * 0.5f) * e;
    }

    /** @return 0 at or behind the line, rising (smoothstep) to 1 at {@link #CENTRE_EASE} from it. */
    static float centreEase(float pos) {
        if (!(pos > 0f)) return 0f;
        float t = Math.min(pos / Field.dist(CENTRE_EASE), 1f);
        return t * t * (3f - 2f * t);
    }

    // ================================================================== rotation pivot (Kelvin 2026-10-16)
    /**
     * x part of {@link #pivotShiftY}.
     *
     * @return world x shift of the leading-edge centre from {@code (lane centre, lineY ± pos)}
     */
    public static float pivotShiftX(float pos, float refH, float h, float rot, boolean up, float px, float py) {
        float vx = px * Field.kx();
        float vy = py * Field.ky() + h * 0.5f;
        return (up ? -vx : vx) - MathUtils.cosDeg(rot) * vx + MathUtils.sinDeg(rot) * vy;
    }

    /**
     * Shift of a lane note's or mine's leading-edge centre when it turns about its pivot (Kelvin 2026-10-16:
     * 「旋转一个note可以选择旋转中心的坐标，以这个note的几何中心为原点」). Unturned the body sits as
     * {@link #centreShiftY} puts it; its origin is the centre of its head ({@code h/2} from the leading edge;
     * a hold or LANE MINE: of its base, the tail left out). The pivot {@code (px, py)} is in reference units in
     * the note's own frame (stretched like positions, {@code kx} and {@code ky}; a sky note's frame is turned
     * 180° with it, so + y points away from the line on both lines) and stays where it is while the body
     * turns {@code rot} about it. At the authored angle 0 the pivot changes nothing; pivot (0, −refH/2) is the
     * edge facing the line on a 16 : 10 screen ({@code ky} 1), the turn of the notes before 2026-10-16 there
     * (a format v2 chart's (0, −refH/2) loads as (0, −refH·{@link #BODY_REF_K}/2): that turn on a 16 : 9 screen).
     *
     * @param pos the note's world distance from its line ({@link Field#dist} of its chart distance)
     * @param rot drawn angle in degrees (the authored angle, +180 on the sky line)
     * @param up  sky line
     * @return world y shift of the leading-edge centre from {@code lineY ± pos}
     */
    public static float pivotShiftY(float pos, float refH, float h, float rot, boolean up, float px, float py) {
        float d = (refH * 0.5f * BODY_REF_K * Field.ky() - h * 0.5f) * centreEase(pos) + h * 0.5f + py * Field.ky();
        float vx = px * Field.kx();
        float vy = py * Field.ky() + h * 0.5f;
        return (up ? -d : d) - MathUtils.sinDeg(rot) * vx - MathUtils.cosDeg(rot) * vy;
    }

    /**
     * Least {@code |cos|} a turned TIDE is drawn for: a steeper TIDE keeps the length it has here
     * ({@code 1 / 0.2} times its window), which crosses any screen.
     */
    public static final float TIDE_TURN_MIN_COS = 0.2f;

    /**
     * @return how far a TIDE turned {@code deg} degrees reaches up or down from where its bar crosses the
     *         middle of its window: drawn infinitely long (Kelvin 2026-10-16: 「无限长的东西（tide）」), it crosses
     *         the window ({@code halfSpan} either side) as a slanted bar, up to {@link #TIDE_TURN_MIN_COS}
     */
    public static float tideTurnReach(float halfSpan, float deg) {
        return halfSpan * Math.abs(MathUtils.sinDeg(deg))
            / Math.max(Math.abs(MathUtils.cosDeg(deg)), TIDE_TURN_MIN_COS);
    }

    /** @return true when a note turned {@code deg} degrees is drawn turned (not within about 0.6° of upright). */
    public static boolean turned(float deg) {
        return Math.abs(MathUtils.sinDeg(deg)) > UPRIGHT_SIN || MathUtils.cosDeg(deg) < 0f;
    }

    // ================================================================== §21.7 lane STREAM ribbons
    /** Ground STREAM ribbon width, share of the lane. */
    public static final float GROUND_RIBBON = 0.82f;
    /** Sky STREAM ribbon width, share of the lane (DESIGN §21.7). */
    public static final float SKY_RIBBON = 0.56f;
    /** Sky ribbon edge width (DESIGN §21.7). */
    public static final float SKY_RIBBON_EDGE = 6f;
    /** Largest ground ribbon edge width (a quarter of the ribbon when narrower). */
    public static final float GROUND_RIBBON_EDGE = 8f;
    /** Ribbon fill opacity at the head (DESIGN §21.7: .42 → .16). */
    public static final float RIBBON_HEAD_A = 0.42f;
    /** Ribbon fill opacity at the tail end. */
    public static final float RIBBON_TAIL_A = 0.16f;
    /** Ribbon fill opacity at the head while held (.62 → .30). */
    public static final float RIBBON_HELD_HEAD_A = 0.62f;
    /** Ribbon fill opacity at the tail end while held. */
    public static final float RIBBON_HELD_TAIL_A = 0.30f;
    /** Head opacity a broken or missed STREAM's ribbon fades to (DESIGN §5.8: grey at α .30). */
    public static final float RIBBON_BROKEN_HEAD_A = 0.30f;
    /** Tail opacity a broken or missed STREAM's ribbon fades to. */
    public static final float RIBBON_BROKEN_TAIL_A = 0.12f;
    /** Ribbon tail cap thickness, in the ribbon's own colour. */
    public static final float RIBBON_CAP = 3f;
    /** Ribbon tail cap opacity. */
    public static final float RIBBON_CAP_A = 0.8f;
    /**
     * Extra knock-out beside an overlapping sky ribbon, on each side of it. 0 = the redraw Kelvin
     * kept on 2026-10-12 04:44 (the hole is exactly the sky ribbon); the first proposal had
     * {@link #SKY_HOLE_PAD_FIRST}.
     */
    public static final float SKY_HOLE_PAD = 0f;
    /** {@link #SKY_HOLE_PAD} of the first proposal (DESIGN §21.7 as first written). */
    public static final float SKY_HOLE_PAD_FIRST = 3f;
    /** Most sky bodies a ground body knocks out at once (more overlaps draw without a hole). */
    public static final int MAX_HOLES = 4;
    /** |sin| of a body's angle under which it counts as upright for the knock-out (about 0.6°). */
    public static final float UPRIGHT_SIN = 0.01f;

    // ================================================================== §21.7 TIDE STREAM bands
    /**
     * Inset of a sky TIDE STREAM band from each window end. 0 = the redraw (full width; the ground
     * band draws nothing in the overlap rows); the first proposal had
     * {@link #SKY_TIDE_STREAM_INSET_FIRST}.
     */
    public static final float SKY_TIDE_STREAM_INSET = 0f;
    /** {@link #SKY_TIDE_STREAM_INSET} of the first proposal. */
    public static final float SKY_TIDE_STREAM_INSET_FIRST = 56f;
    /** Band fill opacity at the head (DESIGN §19.1). */
    public static final float BAND_HEAD_A = 0.14f;
    /** Band fill opacity at the tail end. */
    public static final float BAND_TAIL_A = 0.08f;
    /** Band rail width (one rail at each end of the band). */
    public static final float BAND_RAIL = 2f;
    /** Band rail opacity (DESIGN §21.7). */
    public static final float BAND_RAIL_A = 0.6f;
    /** Band tail cap thickness, in the band's own colour (DESIGN §21.7). */
    public static final float BAND_CAP = 4f;
    /** Band tail cap opacity. */
    public static final float BAND_CAP_A = 0.75f;

    // ================================================================== T4 TIDE drop
    /** Sky: pearl thread thickness (×u). */
    public static final float T4_THREAD = 2f;
    /** Sky: pearl thread opacity. */
    public static final float T4_THREAD_A = 0.45f;
    /** Sky: distance of the thread (and the bead centres) behind the leading edge (×u). */
    public static final float T4_THREAD_BEHIND = 7f;
    /** Sky: beads per lane. */
    public static final int T4_BEADS_PER_LANE = 3;
    /** Sky: radius of the even beads (×u). */
    public static final float T4_BEAD_BIG = 11f;
    /** Sky: radius of the odd beads (×u). */
    public static final float T4_BEAD_SMALL = 7f;
    /** Sky: glow diameter under a bead, in bead radii (a glow about twice the bead's radius). */
    public static final float T4_BEAD_GLOW = 4f;
    /** Sky: glow opacity under a bead. */
    public static final float T4_BEAD_GLOW_A = 0.35f;
    /** Ground: mean crest distance behind the leading edge (×u). */
    public static final float WAVE_BASE = 20f;
    /** Ground: crest amplitude (×u). */
    public static final float WAVE_AMP = 9f;
    /** Ground: crest phase speed, radians per song second. */
    public static final float WAVE_SPEED = 6f;
    /** Ground: wave fill opacity. */
    public static final float WAVE_FILL_A = 0.85f;
    /** Ground: crest line and leading-edge base line thickness (×u). */
    public static final float WAVE_LINE = 3f;
    /** Ground: vertical strips per lane width (DESIGN brief: 8–12). */
    public static final int WAVE_STRIPS_PER_LANE = 8;
    /** Ground: glow height under the wave (×u). */
    public static final float WAVE_GLOW_H = 64f;
    /** Ground: glow opacity under the wave. */
    public static final float WAVE_GLOW_A = 0.30f;
    /** Farthest reach of a T4 shape behind its leading edge (×u): the wave crest plus half its line. */
    public static final float T4_REACH = WAVE_BASE + WAVE_AMP + WAVE_LINE * 0.5f;

    // ================================================================== sync pearls
    /** Diameter of each gold sync pearl on a DROP or STREAM head at note size 1 (half the 40-unit head). */
    public static final float PEARL_D = 20f;
    /**
     * Pearls side by side across the middle of a DROP, STREAM, MINE or LANE MINE head that shares its
     * beat (Kelvin 2026-10-12 16:07: three, not one, so the mark reads more clearly).
     */
    public static final int HEAD_PEARLS = 3;
    /** Centre-to-centre step of a head's pearls, in pearl diameters (a gap of half a pearl between them). */
    public static final float PEARL_STEP = 1.5f;
    /**
     * Largest share of a head's width its row of pearls may span; a narrower head shrinks the pearls
     * and their step together. The narrowest heads of a 12-lane window (133 units for a DROP, 112 for
     * a MINE) keep the full size.
     */
    public static final float PEARL_ROW_SHARE = 0.7f;
    /** Rim width of a head, MINE or sky TIDE pearl, as a share of its radius (2 units on a head pearl). */
    public static final float PEARL_RIM = 0.2f;
    /** Diameter of the red MINE pearl as a share of the gold head pearl's. */
    public static final float MINE_PEARL_SHARE = 0.7f;
    /**
     * Radius of the even (large) pearls of a row (ground TIDE wave, TIDE STREAM head) at note size 1:
     * 14 units across, the largest that stays within the outer edges of the wave's base and crest
     * lines where the band is thinnest and fits the 18-unit TIDE STREAM head bar.
     */
    public static final float ROW_PEARL_BIG = 7f;
    /** Radius of the odd (small) pearls of a row at note size 1 (9 units across: the sky thread's 22 : 14 ratio). */
    public static final float ROW_PEARL_SMALL = 4.5f;
    /** Rim width of a row pearl, as a share of its radius (thicker, so a small pearl keeps its rim). */
    public static final float ROW_PEARL_RIM = 0.3f;
    /** Lustre: soft light disc, radius as a share of the body's, toward the upper left. */
    public static final float LUSTRE_R = 0.6f;
    /** Lustre centre offset left and up, as a share of the body's radius. */
    public static final float LUSTRE_OFF = 0.25f;
    /** Lustre opacity. */
    public static final float LUSTRE_A = 0.65f;
    /** White highlight radius, as a share of the body's radius. */
    public static final float SHINE_R = 0.32f;
    /** White highlight centre offset left and up, as a share of the body's radius. */
    public static final float SHINE_OFF = 0.38f;
    /** White highlight opacity. */
    public static final float SHINE_A = 0.9f;
    /** Quads of a pearl with lustre (each head, MINE and sky TIDE pearl): rim, body, lustre, highlight. */
    public static final int PEARL_QUADS = 4;
    /** Quads of a row pearl: rim, body, highlight. */
    public static final int ROW_PEARL_QUADS = 3;

    // ================================================================== LANE MINE column
    /** Column inset from each lane edge (DESIGN §20.2). */
    public static final float LANE_MINE_INSET = 6f;
    /** Column fill ({@code mineCore}) opacity. */
    public static final float LANE_MINE_FILL_A = 0.85f;
    /** Hazard stripe period, measured across the stripes (45°). */
    public static final float STRIPE_PERIOD = 24f;
    /** Hazard stripe width, measured across the stripe. */
    public static final float STRIPE_WIDTH = 10f;
    /** Hazard stripe ({@code mine}) opacity. */
    public static final float STRIPE_A = 0.35f;
    /** Stripe drift toward the line, units per song second along the column. */
    public static final float STRIPE_SPEED = 40f;
    /** Rim width on both long sides. */
    public static final float LANE_MINE_RIM = 3f;
    /** Stud side. */
    public static final float STUD = 6f;
    /** Stud spacing along each rim. */
    public static final float STUD_STEP = 64f;
    /** Tail cap thickness. */
    public static final float LANE_MINE_CAP = 3f;
    /** The column's fade after its end (ms, DESIGN §20.2). */
    public static final float LANE_MINE_END_FADE_MS = 120f;
    /** Ramp-in of the blocking veil and line glow at the start of the block (ms). */
    public static final float LANE_MINE_BLOCK_RAMP_MS = 60f;
    /** Blocking veil ({@code mine}) opacity over the lane's part of its zone. */
    public static final float LANE_MINE_VEIL_A = 0.10f;
    /** Blocking glow ({@code mine}) opacity on the lane's line segment. */
    public static final float LANE_MINE_LINE_GLOW_A = 0.30f;
    /** Height of the blocking glow on the line segment. */
    public static final float LANE_MINE_LINE_GLOW_H = 40f;
    /**
     * Half height of the gap a column leaves around the OTHER judgement line, so that line, its
     * ripples and ticks stay as bright as elsewhere (Kelvin 2026-10-12 02:58; mock-up fix N8).
     */
    public static final float OTHER_LINE_GAP = 10f;
    /** √2: a 45° stripe is √2 times longer along the column than across it. */
    private static final float SQRT2 = 1.4142135f;

    private NoteShapes() {
    }

    // ================================================================== ribbons and bands

    /** @return the ribbon width of a STREAM in a lane {@code laneWidth} wide (sky 56 %, ground 82 %). */
    public static float ribbonWidth(float laneWidth, boolean sky) {
        return laneWidth * (sky ? SKY_RIBBON : GROUND_RIBBON);
    }

    /** @return the edge width of a ribbon {@code rw} wide (sky 6, ground 8; never over a quarter of it). */
    public static float ribbonEdge(float rw, boolean sky) {
        return Math.min(sky ? SKY_RIBBON_EDGE : GROUND_RIBBON_EDGE, rw * 0.25f);
    }

    /** @return the ribbon fill opacity at the head (held or not). */
    public static float ribbonHeadAlpha(boolean held) {
        return held ? RIBBON_HELD_HEAD_A : RIBBON_HEAD_A;
    }

    /** @return the ribbon fill opacity at the tail end (held or not). */
    public static float ribbonTailAlpha(boolean held) {
        return held ? RIBBON_HELD_TAIL_A : RIBBON_TAIL_A;
    }

    /** @return half width of the ground knock-out under a sky ribbon {@code skyRw} wide. */
    public static float holeHalf(float skyRw, float pad) {
        return skyRw * 0.5f + pad;
    }

    /** @return left end of a sky TIDE STREAM band over the band span {@code [x0, x1]}. */
    public static float skyBandLeft(float x0, float x1, float inset) {
        return Math.min(x0 + inset, (x0 + x1) * 0.5f);
    }

    /** @return right end of a sky TIDE STREAM band over the band span {@code [x0, x1]}. */
    public static float skyBandRight(float x0, float x1, float inset) {
        return Math.max(x1 - inset, (x0 + x1) * 0.5f);
    }

    /**
     * @return true when a ground band's rails continue through its overlap rows: the side strips
     *         the inset leaves beside the sky band are at least a rail wide
     */
    public static boolean railsThroughOverlap(float inset) {
        return inset >= BAND_RAIL;
    }

    /**
     * The spans of {@code [a, b]} beside a knock-out {@code [h0, h1]} (clamped to it): what a ground
     * ribbon or band still fills across its overlap rows. With {@code SKY_TIDE_STREAM_INSET = 0} the
     * sky band covers the whole ground band, so nothing is left; with an inset the two side strips
     * are; a knock-out that misses {@code [a, b]} leaves all of it.
     *
     * @param out receives the spans as (lo, hi) pairs (length ≥ 4)
     * @return the number of spans: 0, 1 or 2
     */
    public static int spansBeside(float a, float b, float h0, float h1, float[] out) {
        if (!(b > a)) return 0;
        float c0 = Math.max(a, Math.min(b, h0));
        float c1 = Math.max(a, Math.min(b, h1));
        if (!(c1 > c0)) {
            out[0] = a;
            out[1] = b;
            return 1;
        }
        int n = 0;
        if (c0 > a) {
            out[0] = a;
            out[1] = c0;
            n++;
        }
        if (c1 < b) {
            out[2 * n] = c1;
            out[2 * n + 1] = b;
            n++;
        }
        return n;
    }

    /**
     * Overlap of a body with another body's world rows, as rows of the first body.
     *
     * @param y   world y of the body's leading edge
     * @param dir +1 when the body runs toward +y from its leading edge, −1 toward −y
     * @param len body length (rows {@code [0, len]})
     * @param lo  low world y of the other body
     * @param hi  high world y of the other body
     * @param out receives the overlap rows {@code [out[0], out[1]]} of the body
     * @return false when they do not overlap (or only touch)
     */
    public static boolean overlapRows(float y, float dir, float len, float lo, float hi, float[] out) {
        float a = y;
        float b = y + dir * len;
        float bl = Math.min(a, b);
        float bh = Math.max(a, b);
        float ol = Math.max(bl, lo);
        float oh = Math.min(bh, hi);
        if (!(oh > ol)) return false;
        float r0 = (ol - y) * dir;
        float r1 = (oh - y) * dir;
        out[0] = Math.max(0f, Math.min(r0, r1));
        out[1] = Math.min(len, Math.max(r0, r1));
        return out[1] > out[0];
    }

    /**
     * Splits the rows {@code [0, len]} of a body around holes. Holes are row intervals
     * {@code [lo[k], hi[k]]}, k &lt; {@code holes} (any order; they may overlap or reach past the
     * body): they are clamped to the body, sorted and merged in place. The segments cover
     * {@code [0, len]} exactly, in order, and alternate plain rows and hole rows.
     *
     * @return the number of segments written to {@code segLo / segHi / segHole} (at most
     *         {@code 2·holes + 1}; 0 when {@code len ≤ 0})
     */
    public static int rowSegments(float len, int holes, float[] lo, float[] hi, float[] segLo, float[] segHi,
                                  boolean[] segHole) {
        if (!(len > 0f)) return 0;
        // clamp and drop the empty ones
        int h = 0;
        for (int k = 0; k < holes; k++) {
            float a = Math.max(0f, lo[k]);
            float b = Math.min(len, hi[k]);
            if (b > a) {
                lo[h] = a;
                hi[h] = b;
                h++;
            }
        }
        // insertion sort by start
        for (int k = 1; k < h; k++) {
            float a = lo[k];
            float b = hi[k];
            int j = k - 1;
            while (j >= 0 && lo[j] > a) {
                lo[j + 1] = lo[j];
                hi[j + 1] = hi[j];
                j--;
            }
            lo[j + 1] = a;
            hi[j + 1] = b;
        }
        // merge
        int m = 0;
        for (int k = 0; k < h; k++) {
            if (m > 0 && lo[k] <= hi[m - 1]) {
                hi[m - 1] = Math.max(hi[m - 1], hi[k]);
            } else {
                lo[m] = lo[k];
                hi[m] = hi[k];
                m++;
            }
        }
        int s = 0;
        float cur = 0f;
        for (int k = 0; k < m; k++) {
            if (lo[k] > cur) {
                segLo[s] = cur;
                segHi[s] = lo[k];
                segHole[s] = false;
                s++;
            }
            segLo[s] = lo[k];
            segHi[s] = hi[k];
            segHole[s] = true;
            s++;
            cur = hi[k];
        }
        if (cur < len) {
            segLo[s] = cur;
            segHi[s] = len;
            segHole[s] = false;
            s++;
        }
        return s;
    }

    // ================================================================== T4 TIDE

    /**
     * @return the song time (visual clock, seconds) the T4 wave and the LANE MINE stripes move
     *         with: it stops while the run is paused, so they freeze with the song
     */
    public static float songSeconds(GameEngine e) {
        return (float) (e.visualMs() / 1000.0);
    }

    /** @return number of pearls of a sky T4 TIDE over {@code lanes} lanes. */
    public static int beadCount(int lanes) {
        return Math.max(1, lanes) * T4_BEADS_PER_LANE;
    }

    /** @return centre x of pearl i of n over {@code [x0, x1]}. */
    public static float beadX(int i, int n, float x0, float x1) {
        return x0 + (x1 - x0) * (i + 0.5f) / n;
    }

    /** @return radius of pearl i at note size {@code u} (11u for even i, 7u for odd i). */
    public static float beadRadius(int i, float u) {
        return ((i & 1) == 0 ? T4_BEAD_BIG : T4_BEAD_SMALL) * u;
    }

    /**
     * @param x             distance from {@link org.kelvinizer.elements.LaneLayout#patternOrigin}
     *                      (the window's left edge, or x 960 on tiled lanes)
     * @param meanLaneWidth {@link org.kelvinizer.elements.LaneLayout#patternWidth}: window width /
     *                      lane count, or on tiled lanes a default lane times the global lane scale
     *                      capped at 1
     * @param seconds       song (visual) seconds: the wave freezes with the song on pause
     * @return the crest phase {@code π·x/(meanLaneWidth/2) + 6·seconds}
     */
    public static float wavePhase(float x, float meanLaneWidth, float seconds) {
        return MathUtils.PI * x / (meanLaneWidth * 0.5f) + WAVE_SPEED * seconds;
    }

    /** @return the crest's distance behind the leading edge: {@code 20u + 9u·sin(phase)}. */
    public static float waveCrest(float x, float meanLaneWidth, float seconds, float u) {
        return (WAVE_BASE + WAVE_AMP * (float) Math.sin(wavePhase(x, meanLaneWidth, seconds))) * u;
    }

    /** @return number of vertical strips of a ground T4 TIDE over {@code lanes} lanes. */
    public static int waveStrips(int lanes) {
        return Math.max(1, lanes) * WAVE_STRIPS_PER_LANE;
    }

    /**
     * @return quads of one T4 TIDE (normal pass): sky = thread + pearls; ground = base line + a
     *         fill and a crest quad per strip
     */
    public static int t4Quads(int lanes, boolean sky) {
        return sky ? 1 + beadCount(lanes) : 1 + 2 * waveStrips(lanes);
    }

    // ================================================================== sync pearls

    /**
     * @return true when note n in engine state {@code state} carries its sync pearl: it shares its
     *         beat with another note ({@link Note#sharesBeat()}: any type, either line) and is
     *         still pending or being held, so live and in its normal look: a DROP head before it
     *         is hit, a STREAM or TIDE STREAM head before and while it is held (Kelvin 2026-10-13:
     *         a held sync hold keeps its pearls), a MINE or LANE MINE before it is touched or
     *         passed, a TIDE before it is taken. Never on a LOST, missed, greyed or broken note,
     *         and never on afterimage copies (copies never ask).
     */
    public static boolean showsPearl(Note n, byte state) {
        return (state == GameEngine.STATE_PENDING || state == GameEngine.STATE_HOLDING) && n.sharesBeat();
    }

    /** @return radius of the gold pearl on a DROP or STREAM head at note size {@code u}. */
    public static float pearlRadius(float u) {
        return PEARL_D * 0.5f * u;
    }

    /** @return radius of the red pearl on a MINE or LANE MINE head at note size {@code u}. */
    public static float minePearlRadius(float u) {
        return pearlRadius(u) * MINE_PEARL_SHARE;
    }

    /**
     * @return radius of each pearl in the row of {@link #HEAD_PEARLS} across a head {@code w} wide
     *         whose pearls are {@code r} in radius at full size: {@code r} while the row (centre step
     *         {@link #PEARL_STEP} diameters) spans at most {@link #PEARL_ROW_SHARE} of {@code w}, else
     *         shrunk until it does
     */
    public static float headPearlRadius(float r, float w) {
        float span = 2f * r * (PEARL_STEP * (HEAD_PEARLS - 1) + 1f);
        float max = PEARL_ROW_SHARE * w;
        return span > max ? r * max / span : r;
    }

    /**
     * @return offset along a head of pearl i ({@code 0 … HEAD_PEARLS − 1}, left to right in the head's
     *         frame) from the head's centre, for pearls of radius {@code r}: {@code −step, 0, +step}
     */
    public static float headPearlOffset(int i, float r) {
        return (i - (HEAD_PEARLS - 1) * 0.5f) * 2f * r * PEARL_STEP;
    }

    /**
     * @return radius of pearl i of a row (ground TIDE wave, TIDE STREAM head) at note size
     *         {@code u}: {@link #ROW_PEARL_BIG} for even i, {@link #ROW_PEARL_SMALL} for odd i, large
     *         and small in turn like the sky thread's pearls ({@link #beadRadius})
     */
    public static float rowPearlRadius(int i, float u) {
        return ((i & 1) == 0 ? ROW_PEARL_BIG : ROW_PEARL_SMALL) * u;
    }

    /**
     * @return distance behind the leading edge of a ground TIDE wave's pearl at {@code x}: halfway
     *         between the base line and the crest ({@code waveCrest / 2}, 5.5u to 14.5u), so the
     *         row rides the crest's motion. Where the band is thinnest (crest near 11u) the pearls
     *         overlap the base and crest lines a little, never past their outer edges.
     */
    public static float wavePearlDistance(float x, float meanLaneWidth, float seconds, float u) {
        return 0.5f * waveCrest(x, meanLaneWidth, seconds, u);
    }

    /**
     * @return quads the sync pearls add to a T4 TIDE over {@code lanes} lanes: sky, each pearl
     *         gains a rim, a lustre and a highlight; ground, the row of {@link #beadCount} pearls
     */
    public static int t4PearlQuads(int lanes, boolean sky) {
        return beadCount(lanes) * (sky ? PEARL_QUADS - 1 : ROW_PEARL_QUADS);
    }

    /** @return quads of the row of pearls on a TIDE STREAM head over {@code lanes} lanes (the proposal). */
    public static int barPearlQuads(int lanes) {
        return beadCount(lanes) * ROW_PEARL_QUADS;
    }

    // ================================================================== LANE MINE column

    /** @return the stripe period along the column (45° stripes: period × √2). */
    public static float stripeAxisPeriod() {
        return STRIPE_PERIOD * SQRT2;
    }

    /** @return a stripe's thickness along the column (width × √2). */
    public static float stripeAxisWidth() {
        return STRIPE_WIDTH * SQRT2;
    }

    /**
     * @return the stripe offset along the column at song second {@code seconds}, in
     *         {@code [0, stripeAxisPeriod())}: it falls at {@link #STRIPE_SPEED}, so the stripes
     *         drift toward the line, and freezes with the song on pause
     */
    public static float stripeOffset(float seconds) {
        float p = stripeAxisPeriod();
        float o = (-STRIPE_SPEED * seconds) % p;
        return o < 0f ? o + p : o;
    }

    /** @return half width of a LANE MINE column in a lane {@code laneWidth} wide (inset 6 each side). */
    public static float laneMineHalfWidth(float laneWidth) {
        return laneWidth * 0.5f - LANE_MINE_INSET;
    }

    /**
     * @return the block look factor of a LANE MINE at judge time {@code ms}: 0 before its block
     *         starts ({@code T − windowMs}; the renderer passes {@code GameEngine.LANE_MINE_LEAD_MS},
     *         0, so the block starts at T), ramping to 1 over {@link #LANE_MINE_BLOCK_RAMP_MS}, then
     *         fading over {@link #LANE_MINE_END_FADE_MS} after its end
     */
    public static float blockLook(double ms, double judgementMs, double endMs, float windowMs) {
        double s = judgementMs - windowMs;
        if (ms < s) return 0f;
        float k = (float) Math.min(1.0, (ms - s) / LANE_MINE_BLOCK_RAMP_MS);
        if (ms >= endMs) k *= endFade(ms, endMs);
        return k;
    }

    /** @return the end fade of a LANE MINE at judge time {@code ms}: 1 until its end, then to 0 over 120 ms. */
    public static float endFade(double ms, double endMs) {
        if (ms <= endMs) return 1f;
        return (float) Math.max(0.0, 1.0 - (ms - endMs) / LANE_MINE_END_FADE_MS);
    }
}
