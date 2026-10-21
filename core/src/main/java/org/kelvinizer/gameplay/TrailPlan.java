package org.kelvinizer.gameplay;

import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;

import java.util.List;

/**
 * 拖拽残影的纯规则：哪些副本存在、各有多亮，以及超出每帧的四边形预算时如何按步长稀疏。
 * 没有 GL、没有状态、不分配内存；{@link TrailRenderer} 每帧调用它，测试也直接检查它。
 *
 * <p>Pure decisions for afterimage trails (ARCHITECTURE §17, DESIGN §17): which copies exist, how
 * bright they are, and how the frame budget thins them. No GL, no allocation, no state; the
 * {@link TrailRenderer} asks it every frame and the tests check it directly.
 *
 * <p>Vocabulary: copy {@code k} (1 = nearest … n = farthest) of a note at the frame beat
 * {@code beat} shows the note at its <em>moment</em> {@code s = beat − k/density}. The note's
 * {@linkplain #outcomeOf outcome} (live, hit or missed) decides how long copies outlive it:
 * <ul>
 *   <li>every copy needs {@code s ≥ note.visibleFrom()} and {@code s ∈ [from, to]}; a note that
 *       slides in from the screen edge ({@link EdgeEntry}) needs {@code s ≥} its entry beat
 *       instead (the overloads with an explicit earliest moment), as its slide replaces the
 *       parked moments before its first movement;</li>
 *   <li><b>hit</b> (any judgement but LOST; a completed STREAM): {@code path} copies need
 *       {@code s < min(hitBeat, judgement)}, so the trail drains into the line behind the vanished
 *       note; {@code level} copies vanish with the note;</li>
 *   <li><b>missed</b> (LOST, a mine that passed or was touched): copies follow the miss slide and
 *       the miss fade evaluated at {@code s} (the renderer applies the look);</li>
 *   <li><b>STREAM</b>: never a copy for {@code s ≥ judgement} (the head is pinned to the line after
 *       it; a copy would sit on the live hold);</li>
 *   <li><b>TIDE</b> in {@code level} mode: none (decision, see the requests file: a level copy keeps
 *       the current distance and the current window, so it would coincide with the live bar and
 *       only thicken it);</li>
 *   <li>the renderer also skips a copy whose note was hidden at {@code s} (and, in {@code level}
 *       mode, when the live note is hidden now): every moment before the note's
 *       {@linkplain #motionStart first movement} is hidden, so {@link #showFrom} and
 *       {@link #lastShownCopy} leave those out of the plan at once;</li>
 *   <li>the renderer skips a copy that would sit exactly on the note it trails
 *       ({@link TrailGeometry#sameAs}): a {@code level} copy without sideways or rotation motion
 *       since its moment, a {@code path} copy of a note that stood still.</li>
 * </ul>
 * A note may have several trail segments ({@link Note#trails()}); each segment is planned on its
 * own, so every rule has an overload taking the segment. The overloads without one use the first
 * segment ({@link Note#trail()}).
 */
public final class TrailPlan {
    /** Trail quad budget per frame at particle quality High (ARCHITECTURE §17). */
    public static final int MAX_TRAIL_QUADS_HIGH = 2400;
    /** Trail quad budget per frame at particle quality Low and Off. */
    public static final int MAX_TRAIL_QUADS_LOW = 1200;
    /** Most copies per note at quality Low / Off (DESIGN §17.1: every 2nd copy of a 32-copy trail). */
    public static final int LOW_QUALITY_MAX_COPIES = 16;
    /** Sprites the shared atlas batch holds before it must flush ({@code ui/Draw}: {@code new SpriteBatch(4000)}). */
    public static final int BATCH_QUADS = 4000;
    /**
     * Quads kept free in the batch for the rest of block B and whatever shares its flush (line
     * cores, lane ticks, the banner scrim; block A when no HUD-under text separates them).
     */
    public static final int BATCH_RESERVE = 400;
    /** Opacity boost of thinned copies (capped at 1, and by the readability cap). */
    public static final float THIN_BOOST = 1.25f;

    /** Outcome: pending (also sliding past the line before the LOST sweep) or a STREAM being held. */
    public static final int LIVE = 0;
    /** Outcome: judged and gone (a DROP / TIDE that was hit, a completed STREAM). */
    public static final int HIT = 1;
    /** Outcome: LOST, or a MINE that passed or was touched. */
    public static final int MISSED = 2;

    private TrailPlan() {
    }

    /** @return the outcome ({@link #LIVE}, {@link #HIT}, {@link #MISSED}) of an engine note state. */
    public static int outcomeOf(byte engineState) {
        switch (engineState) {
            case GameEngine.STATE_HIT:
            case GameEngine.STATE_COMPLETED:
                return HIT;
            case GameEngine.STATE_LOST:
            case GameEngine.STATE_MINE_PASSED:
            case GameEngine.STATE_MINE_TOUCHED:
                return MISSED;
            default:
                return LIVE;
        }
    }

    /**
     * @return true when the note shows no copy at all in this outcome (level copies of a vanished note, level TIDE).
     */
    public static boolean noCopies(Note n, int outcome) {
        return noCopies(n, n.trail(), outcome);
    }

    /** {@link #noCopies(Note, int)} for trail segment {@code t} of {@code n}. */
    public static boolean noCopies(Note n, NoteTrail t, int outcome) {
        if (t.isNone() || !(t.alpha() > 0f)) return true;
        if (t.mode() != NoteTrail.Mode.LEVEL) return false;
        return outcome == HIT || n.type() == NoteType.UNIVERSAL;
    }

    /** @return the earliest moment a copy may have: {@code max(visibleFrom, from)}. */
    public static float lowMoment(Note n) {
        return lowMoment(n, n.trail());
    }

    /** {@link #lowMoment(Note)} for trail segment {@code t} of {@code n}. */
    public static float lowMoment(Note n, NoteTrail t) {
        return Math.max(n.visibleFrom(), t.from());
    }

    /**
     * @return the exclusive upper bound of a copy's moment from the lifetime rules: the
     *         judgement for a STREAM or a TIDE STREAM ({@link Note#isSustained}),
     *         {@code min(hitBeat, judgement)} for a hit {@code path} trail, else +∞ (the
     *         inclusive {@code to} is checked separately)
     */
    public static float upperExclusive(Note n, int outcome, float hitBeat) {
        float hi = Float.POSITIVE_INFINITY;
        if (n.isSustained()) hi = n.judgement();
        if (outcome == HIT) {
            float h = Float.isNaN(hitBeat) ? n.judgement() : Math.min(hitBeat, n.judgement());
            hi = Math.min(hi, h);
        }
        return hi;
    }

    /**
     * Moment test of one copy (everything except the renderer's on-screen and hidden checks).
     *
     * @param hitBeat the visual beat at which the note was hit (NaN when not hit)
     * @param s       the copy's moment ({@link NoteTrail#momentOf})
     */
    public static boolean showsMoment(Note n, int outcome, float hitBeat, float s) {
        return showsMoment(n, outcome, hitBeat, s, n.visibleFrom());
    }

    /**
     * {@link #showsMoment(Note, int, float, float)} with the earliest moment {@code low} instead of
     * {@code visibleFrom}: the entry beat of a note that slides in from the screen edge
     * ({@link EdgeEntry}), whose moments before its first movement lie on its slide.
     */
    public static boolean showsMoment(Note n, int outcome, float hitBeat, float s, float low) {
        return showsMoment(n, n.trail(), outcome, hitBeat, s, low);
    }

    /** {@link #showsMoment(Note, int, float, float, float)} for trail segment {@code t} of {@code n}. */
    public static boolean showsMoment(Note n, NoteTrail t, int outcome, float hitBeat, float s, float low) {
        if (noCopies(n, t, outcome) || Float.isNaN(s)) return false;
        return s >= low && t.inWindow(s) && s < upperExclusive(n, outcome, hitBeat);
    }

    /**
     * @return the smallest copy index whose moment can pass {@link #showsMoment} at {@code beat}
     *         (≥ 1; may exceed {@link #lastCopy} when none can). Within one copy of rounding: the
     *         renderer still checks every copy with {@link #showsMoment}.
     */
    public static int firstCopy(Note n, int outcome, float hitBeat, float beat) {
        return firstCopy(n, n.trail(), outcome, hitBeat, beat);
    }

    /** {@link #firstCopy(Note, int, float, float)} for trail segment {@code t} of {@code n}. */
    public static int firstCopy(Note n, NoteTrail t, int outcome, float hitBeat, float beat) {
        double sp = t.spacingBeats();
        if (!(sp > 0.0)) return Integer.MAX_VALUE;
        double k = 1.0;
        double to = t.to();
        if (to != Double.POSITIVE_INFINITY) k = Math.max(k, Math.ceil((beat - to) / sp));
        double hi = upperExclusive(n, outcome, hitBeat);
        if (hi != Double.POSITIVE_INFINITY) k = Math.max(k, Math.floor((beat - hi) / sp) + 1.0);
        if (!(k <= t.copies())) return t.copies() + 1;
        return (int) k;
    }

    /**
     * @return the largest copy index whose moment can pass {@link #showsMoment} at {@code beat}
     *         (≤ n; 0 when none can)
     */
    public static int lastCopy(Note n, float beat) {
        return lastCopyFrom(n.trail(), lowMoment(n), beat);
    }

    /**
     * @return the beat of the note's first movement: the start of its default approach, or of
     *         its first {@code MovementEvent}. Before it the note is parked at
     *         {@link Note#HIDDEN_POSITION} (no event, no base distance), so no copy whose moment lies
     *         before it can be seen, whatever other events (opacity, rotation) start earlier.
     *         Never after the judgement beat.
     */
    public static float motionStart(Note n) {
        if (n.usesDefaultApproach()) return n.judgement() - Note.DEFAULT_APPROACH_BEATS;
        List<Events> m = n.movementEvents();
        return m.isEmpty() ? n.judgement() : Math.min(n.judgement(), m.get(0).startTime());
    }

    /**
     * @return the earliest frame beat at which the note can show a copy:
     *         {@code max(visibleFrom, from, motionStart)} (a copy's moment is never after the frame
     *         beat). The renderer's culling window opens a trail here.
     */
    public static float showFrom(Note n) {
        return showFrom(n, n.trail());
    }

    /** {@link #showFrom(Note)} for trail segment {@code t} of {@code n}. */
    public static float showFrom(Note n, NoteTrail t) {
        return Math.max(lowMoment(n, t), motionStart(n));
    }

    /**
     * @return the largest copy index whose moment is not before {@link #showFrom} (≤ n; 0 when
     *         none): {@link #lastCopy} without the copies that would show the note still parked
     *         before its first movement
     */
    public static int lastShownCopy(Note n, float beat) {
        return lastCopyFrom(n.trail(), showFrom(n), beat);
    }

    /**
     * @return the earliest frame beat a note that slides in from the screen edge from
     *         {@code entryBeat} can show a copy: {@code max(entryBeat, from)} (its slide replaces
     *         the parked moments before its first movement)
     */
    public static float showFrom(Note n, float entryBeat) {
        return showFrom(n.trail(), entryBeat);
    }

    /** {@link #showFrom(Note, float)} for trail segment {@code t}. */
    public static float showFrom(NoteTrail t, float entryBeat) {
        return Math.max(entryBeat, t.from());
    }

    /**
     * @return the largest copy index of trail {@code t} whose moment is not before
     *         {@code showFrom} at {@code beat} (≤ n; 0 when none): {@link #lastShownCopy(Note, float)}
     *         with an explicit first frame beat (see {@link #showFrom(Note, float)})
     */
    public static int lastShownCopy(NoteTrail t, float showFrom, float beat) {
        return lastCopyFrom(t, showFrom, beat);
    }

    private static int lastCopyFrom(NoteTrail t, float low, float beat) {
        double sp = t.spacingBeats();
        if (!(sp > 0.0)) return 0;
        double k = Math.floor((beat - (double) low) / sp);
        if (!(k >= 1.0)) return 0;
        return k >= t.copies() ? t.copies() : (int) k;
    }

    /** @return the quality stride: 1 at High; at Low / Off every 2nd copy of a trail longer than 16. */
    public static int qualityStride(int copies, boolean lowQuality) {
        if (!lowQuality || copies <= LOW_QUALITY_MAX_COPIES) return 1;
        return (copies + LOW_QUALITY_MAX_COPIES - 1) / LOW_QUALITY_MAX_COPIES;
    }

    /**
     * @return the stride a note draws with: the larger of its quality stride and the frame's
     *         global (budget) stride, but never more than its copy count, so a thinned trail always
     *         keeps its farthest-but-one-stride copy instead of disappearing (≥ 1)
     */
    public static int stride(int copies, int qualityStride, int globalStride) {
        int s = Math.max(1, Math.max(qualityStride, globalStride));
        return copies > 0 ? Math.min(s, copies) : s;
    }

    /** @return number of copy indices {@code k ∈ {S, 2S, …}} with {@code first ≤ k ≤ last}. */
    public static int countMultiples(int first, int last, int stride) {
        if (stride < 1 || last < first || last < 1) return 0;
        int lo = Math.max(1, first);
        int c = last / stride - (lo - 1) / stride;
        return c > 0 ? c : 0;
    }

    /** @return true when copy k is drawn at {@code stride} (k is a multiple of it). */
    public static boolean drawnAt(int k, int stride) {
        return stride <= 1 || k % stride == 0;
    }

    /**
     * Opacity of copy k (DESIGN §17.1): {@code min(1, alphaOf(k) · boost) · opacityAtMoment},
     * capped at {@link NoteTrail#READABILITY_CAP} × {@code liveOpacity}; {@code boost} is
     * {@link #THIN_BOOST} for a thinned trail (stride &gt; 1), else 1.
     *
     * @param opacityAtMoment the note's own opacity at the copy's moment (DisappearEvents × fade-in)
     * @param liveOpacity     the live note's opacity now (DisappearEvents × fade-in, no judgement fades)
     */
    public static float copyAlpha(NoteTrail t, int k, int stride, float opacityAtMoment, float liveOpacity) {
        float a = t.alphaOf(k);
        if (stride > 1) a = Math.min(1f, a * THIN_BOOST);
        a *= opacityAtMoment;
        float cap = NoteTrail.READABILITY_CAP * liveOpacity;
        if (a > cap) a = cap;
        return a > 0f ? a : 0f;
    }

    /** @return the trail quad budget of a frame at this particle quality. */
    public static int budget(boolean highQuality) {
        return highQuality ? MAX_TRAIL_QUADS_HIGH : MAX_TRAIL_QUADS_LOW;
    }

    /**
     * @param liveQuads upper bound of the quads the live notes submit this frame
     * @return the frame's trail budget: the quality budget, lowered so copies, live notes and
     *         {@link #BATCH_RESERVE} fit one batch of {@link #BATCH_QUADS} (copies never force an
     *         extra flush, i.e. never add a draw call); ≥ 0
     */
    public static int frameBudget(boolean highQuality, int liveQuads) {
        int room = BATCH_QUADS - BATCH_RESERVE - Math.max(0, liveQuads);
        return Math.max(0, Math.min(budget(highQuality), room));
    }

    /**
     * Quads a frame's trails need at global stride {@code g}: Σ over notes of
     * {@code countMultiples(first, last, stride(copies, qualityStride, g)) × quadsPerCopy}.
     * Arrays are indexed 0..count−1 (one entry per candidate note).
     */
    public static int quadsAt(int count, int[] first, int[] last, int[] copies, int[] qualityStride,
                              int[] quadsPerCopy, int g) {
        long q = 0;
        for (int i = 0; i < count; i++) {
            int s = stride(copies[i], qualityStride[i], g);
            q += (long) countMultiples(first[i], last[i], s) * quadsPerCopy[i];
        }
        return q > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) q;
    }

    /**
     * @return the smallest global stride {@code g} in {@code 1..}{@link NoteTrail#MAX_TRAIL_COPIES}
     *         whose {@link #quadsAt} fits {@code budget}, or {@code MAX_TRAIL_COPIES} when even that
     *         does not fit (each trail then keeps one copy and the renderer stops at the budget)
     */
    public static int budgetStride(int count, int[] first, int[] last, int[] copies, int[] qualityStride,
                                   int[] quadsPerCopy, int budget) {
        int total = quadsAt(count, first, last, copies, qualityStride, quadsPerCopy, 1);
        if (total <= budget) return 1;
        // a first guess from the ratio, then walk to the smallest stride that fits
        int g = Math.max(2, Math.min(NoteTrail.MAX_TRAIL_COPIES, (int) Math.ceil(total / (double) budget)));
        while (g > 2 && quadsAt(count, first, last, copies, qualityStride, quadsPerCopy, g - 1) <= budget) g--;
        while (g < NoteTrail.MAX_TRAIL_COPIES
            && quadsAt(count, first, last, copies, qualityStride, quadsPerCopy, g) > budget) {
            g++;
        }
        return g;
    }
}
