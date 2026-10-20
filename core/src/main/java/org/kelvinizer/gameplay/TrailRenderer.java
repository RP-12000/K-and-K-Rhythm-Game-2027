package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.ui.Theme;

/**
 * 拖拽残影：在活音符之前画出每个带残影音符的副本，最远的先画，用和活音符相同的画法；水雷会旋转，轨道水雷像长条一样复制，TIDE 用 T4 外形。
 *
 * <p>Afterimage trails "拖拽残影" (ARCHITECTURE §17, DESIGN §17): draws the copies of every trailed
 * note in the normal blend of block B, <b>before</b> the live notes, farthest copy first, with
 * the {@link NoteRenderer} paint methods (same sprites and colours as the live note, no glow, no
 * glitch, no sync pearl, no tide sweep, no FX). Mine copies turn with the angle of their moment
 * (owner 2026-10-12 03:03); a LANE MINE is copied like a STREAM (DESIGN §20.2): its column (fill,
 * rims, tail cap; the stripes and studs are live-only) and its head cap; a TIDE drop copy has
 * the T4 shape (owner 2026-10-12 04:36), its wave at the moment's song time.
 *
 * <p>Geometry of copy k at moment {@code s = beat − k/density}:
 * <ul>
 *   <li>{@code path}: everything of moment s: lane span and window (a scratch
 *       {@link LaneLayout}, never the engine's), line y, distance, angle, size, hold length and
 *       the note's opacity;</li>
 *   <li>{@code level}: lane span and angle of moment s, the current distance, line y and hold
 *       length (copies stay level with the note and fall straight down with it).</li>
 * </ul>
 * ({@link TrailGeometry} computes both.)
 * A note with several trail segments ({@link Note#trails()}, 2026-10-14) gets one slot per segment:
 * each is planned, budgeted and drawn like a trail of its own.
 * Lifetime and brightness come from {@link TrailPlan}. A copy's opacity is capped at
 * {@link NoteTrail#READABILITY_CAP} × the live note's (DisappearEvents × fade-in), so the live
 * note is always the brightest. A copy that would sit exactly on the note it trails
 * ({@link TrailGeometry#sameAs}) is not drawn, so copies never change the live note's look.
 *
 * <p>A note that slides in from the screen edge ({@link EdgeEntry}, DESIGN §5.4) has copies of
 * its slide too: they stream in from the edge behind it, with the pose of its first movement,
 * and none exists before its entry beat.
 *
 * <p>Budget: at most {@link TrailPlan#frameBudget} quads per frame (the quality budget, lowered
 * when the live notes leave less room in the shared batch). {@link #prepare} narrows each
 * trail's copy range to the copies that can be seen (a bounded number of probes per frame),
 * counts what the frame would need, picks one global stride for every trail (every 2nd, 3rd …
 * copy, opacity × 1.25) and, in the extreme case where one copy per trail is still too much,
 * keeps the trails of the notes nearest in time. Quality Low / Off draws at most 16 copies per
 * note. Copies use the notes' batch and atlas, so they never add a draw call. No allocation after
 * {@link #begin}.
 */
final class TrailRenderer {
    /** Miss fade of a lane note / TIDE (ms), as the live note (DESIGN §5.8). */
    private static final float MISS_FADE_MS = 200f;
    /** Grey-out time of a missed DROP or TIDE drop (ms). */
    private static final float MISS_GREY_MS = 60f;
    /** Fade of a mine that passed untouched (ms, DESIGN §5.15). */
    private static final float MINE_PASS_FADE_MS = 120f;
    /** Fade of a touched mine (ms). */
    private static final float MINE_TOUCH_FADE_MS = 160f;
    /** TIDE bar inset from the window edges (as the live bar). */
    private static final float TIDE_INSET = 12f;
    /** Hysteresis: a lower global stride is taken only when it needs less than this share of the budget. */
    private static final float STRIDE_RELAX = 0.85f;
    /**
     * Copy placements {@link #prepare} may spend per frame narrowing the copy ranges to what can
     * be seen (two per visible trail; a trail that is entirely off screen or coincident costs one
     * per copy). Past it the remaining ranges stay as planned: the count only over-estimates.
     */
    private static final int NARROW_PROBES = 512;

    private final NoteRenderer painter;
    private final EdgeEntry entry;
    private final TrailGeometry geo = new TrailGeometry();
    private final Color col = new Color();

    private Chart chart;
    private Note[] notes = new Note[0];

    // trail slots in chart order (one per segment of a trailed note, one note per TIDE group):
    // the note, the segment, the culling suffix and the spawn distance
    private int[] trailed = new int[0];
    private NoteTrail[] seg = new NoteTrail[0];
    private float[] showFrom = new float[0];
    /** Earliest moment a copy may show: visibleFrom, or the entry beat of a note that slides in from the edge. */
    private float[] low = new float[0];
    /** The note slides in from the screen edge at the current extent. */
    private boolean[] slides = new boolean[0];
    private float[] suffixVis = new float[0];
    private float[] spawn = new float[0];
    private int[] tQuads = new int[0];
    private boolean[] stillLevel = new boolean[0];
    private int trailedCount;
    private int ts;
    private int te;
    private float lastBeat = Float.NaN;
    /** {@link EdgeEntry#generation} the lifetime tables were built for. */
    private int entryGen;
    /** The renderer's edge entries were prepared for this chart (else every note keeps its authored path). */
    private boolean following;

    // per-frame candidates (filled by prepare, chart order)
    private int cands;
    private int[] cNote = new int[0];
    private int[] cOutcome = new int[0];
    private float[] cHit = new float[0];
    private int[] cFirst = new int[0];
    private int[] cLast = new int[0];
    private int[] cCopies = new int[0];
    private int[] cQuality = new int[0];
    private int[] cQuads = new int[0];
    private float[] cLive = new float[0];
    // per candidate: the placement a copy must not coincide with (the live note, or as it was hit)
    private boolean[] cRefOk = new boolean[0];
    private float[] cRefX = new float[0];
    private float[] cRefY = new float[0];
    private float[] cRefW = new float[0];
    private float[] cRefRot = new float[0];
    private float[] cRefTail = new float[0];
    private int cutoff;

    private boolean lowQuality;
    private int budget = TrailPlan.MAX_TRAIL_QUADS_HIGH;
    private int liveQuads;
    private int globalStride = 1;

    // diagnostics of the last draw
    private int quadsNeeded;
    private int quadsDrawn;
    private int copiesDrawn;

    /** Creates the renderer; copies are painted by {@code painter}. */
    TrailRenderer(NoteRenderer painter) {
        this.painter = painter;
        this.entry = painter.entry();
    }

    /** Prepares a chart (load time, may allocate). A chart without trails costs nothing per frame. */
    void begin(Chart chart) {
        this.chart = chart;
        notes = chart.notes();
        geo.begin(chart);
        int count = 0;
        for (int i = 0; i < notes.length; i++) {
            if (eligible(i)) count += notes[i].trails().size();
        }
        if (trailed.length < count) {
            trailed = new int[count];
            seg = new NoteTrail[count];
            showFrom = new float[count];
            low = new float[count];
            slides = new boolean[count];
            suffixVis = new float[count];
            spawn = new float[count];
            tQuads = new int[count];
            stillLevel = new boolean[count];
            cNote = new int[count];
            cOutcome = new int[count];
            cHit = new float[count];
            cFirst = new int[count];
            cLast = new int[count];
            cCopies = new int[count];
            cQuality = new int[count];
            cQuads = new int[count];
            cLive = new float[count];
            cRefOk = new boolean[count];
            cRefX = new float[count];
            cRefY = new float[count];
            cRefW = new float[count];
            cRefRot = new float[count];
            cRefTail = new float[count];
        }
        int c = 0;
        for (int i = 0; i < notes.length; i++) {
            if (!eligible(i)) continue;
            Note n = notes[i];
            for (NoteTrail t : n.trails()) {
                if (!(t.alpha() > 0f)) continue;
                trailed[c] = i;
                seg[c] = t;
                spawn[c] = n.positionAt(n.visibleFrom());
                tQuads[c] = quadsPerCopy(n);   // also initialises the enum switch here, not in the first frame
                // level copies of a note that never moves sideways or turns would all sit on the note
                stillLevel[c] = t.mode() == NoteTrail.Mode.LEVEL && geo.staticLayout() && n.rotationEvents().isEmpty();
                c++;
            }
        }
        for (int k = c; k < seg.length; k++) seg[k] = null;
        trailedCount = c;
        ts = 0;
        entryTable();
        cands = 0;
        cutoff = 0;
        globalStride = 1;
        lastBeat = Float.NaN;
        quadsNeeded = quadsDrawn = copiesDrawn = 0;
    }

    /**
     * Lifetime tables for the current edge entries (a new chart or a resize): a note that slides
     * in from the screen edge shows copies from its entry beat on ({@link TrailPlan#showFrom(Note,
     * float)}), the others from {@link TrailPlan#showFrom(Note)}; then the culling suffix.
     */
    private void entryTable() {
        entryGen = entry.generation();
        following = entry.covers(chart);
        geo.setEntry(following ? entry : null);
        for (int c = 0; c < trailedCount; c++) {
            int i = trailed[c];
            Note n = notes[i];
            slides[c] = following && entry.entering(i);
            if (slides[c]) {
                float f = entry.entryBeat(i);
                showFrom[c] = TrailPlan.showFrom(seg[c], f);
                low[c] = f;
            } else {
                showFrom[c] = TrailPlan.showFrom(n, seg[c]);
                low[c] = n.visibleFrom();
            }
        }
        float min = Float.POSITIVE_INFINITY;
        for (int k = trailedCount - 1; k >= 0; k--) {
            min = Math.min(min, showFrom[k]);
            suffixVis[k] = min;
        }
        te = ts;
    }

    /**
     * A note draws its trails when one of them has visible copies; of the TIDE drops of a group
     * (same line and beat) only the first member with such a trail does, as only one bar is drawn
     * for the group. A TIDE STREAM has its own band, so it always draws its own trails (band, tail
     * and bar).
     */
    private boolean eligible(int i) {
        Note n = notes[i];
        if (!showsTrail(n)) return false;
        if (n.type() != NoteType.UNIVERSAL || n.isTideStream()) return true;
        for (int j = i - 1; j >= 0; j--) {
            Note p = notes[j];
            if (p.type() != NoteType.UNIVERSAL || p.line() != n.line() || p.judgement() != n.judgement()) break;
            if (!p.isTideStream() && showsTrail(p)) return false;
        }
        return true;
    }

    /** @return true when one of the note's trail segments has copies with some opacity. */
    private static boolean showsTrail(Note n) {
        for (NoteTrail t : n.trails()) {
            if (!t.isNone() && t.alpha() > 0f) return true;
        }
        return false;
    }

    /** Sets the particle quality of this frame ({@code HitFx.QUALITY_*}): budget and the Low / Off copy limit. */
    void setQuality(int quality) {
        lowQuality = quality != HitFx.QUALITY_HIGH;
    }

    /** @return number of trail slots in the chart (one per visible segment of a trailed note). */
    int trailedNotes() {
        return trailedCount;
    }

    // ================================================================== per-frame plan

    /**
     * Culls the trailed notes, works out each candidate's copy range (narrowed to the copies that
     * can be seen) and picks the frame's global stride for the budget. Call once per frame after
     * the engine update, before {@link #draw}.
     */
    void prepare(GameEngine e) {
        prepare(e, 0);
    }

    /**
     * {@link #prepare(GameEngine)} with {@code extraQuads} more quads that may share the trails'
     * flush this frame (the tide wash of block A, see {@link TideWash#quadsBound}).
     */
    void prepare(GameEngine e, int extraQuads) {
        cands = 0;
        cutoff = 0;
        quadsNeeded = 0;
        if (trailedCount == 0) return;
        float beat = e.visualBeat();
        if (Float.isNaN(beat)) return;
        geo.setShift(painter.hitShift()); // copies follow the live notes' VISUAL HIT POINT
        // the quality budget, lowered when the live notes leave less room in the shared batch
        liveQuads = painter.quadsBound(e) + Math.max(0, extraQuads);
        budget = TrailPlan.frameBudget(!lowQuality, liveQuads);
        if (!(beat >= lastBeat)) ts = 0; // first frame or a seek backwards
        lastBeat = beat;
        if (entry.generation() != entryGen) entryTable(); // a resize moved the edge entries
        while (ts < trailedCount && finished(e, ts, beat)) ts++;
        if (te < ts) te = ts;
        while (te < trailedCount && suffixVis[te] <= beat) te++;
        LaneLayout frame = e.layout();
        int probes = NARROW_PROBES;
        for (int c = ts; c < te; c++) {
            if (showFrom[c] > beat || stillLevel[c]) continue;
            int i = trailed[c];
            Note n = notes[i];
            int outcome = TrailPlan.outcomeOf(e.noteState(i));
            NoteTrail t = seg[c];
            if (TrailPlan.noCopies(n, t, outcome)) continue;
            boolean up = n.isUpper();
            float lineY = e.lineY(n.line());
            float livePos = following ? entry.position(i, n, beat) : NoteRenderer.position(n, beat);
            boolean liveHidden = hidden(livePos);
            if (liveHidden && t.mode() == NoteTrail.Mode.LEVEL) continue; // level copies keep the hidden distance
            float fade = liveHidden ? 1f : fadeIn(c, beat, lineY, up);
            float live = n.shownOpacityAt(following ? entry.look(i, beat) : beat) * fade;
            if (NoteTrail.READABILITY_CAP * live <= NoteRenderer.MIN_ALPHA) continue; // every copy is capped away
            float hit = outcome == TrailPlan.HIT ? hitBeat(e, i) : Float.NaN;
            // one copy of slack on each side for float rounding; the narrowing and the draw test every copy again
            int first = Math.max(1, TrailPlan.firstCopy(n, t, outcome, hit, beat) - 1);
            int last = Math.min(t.copies(), TrailPlan.lastShownCopy(t, showFrom[c], beat) + 1);
            if (last < first) continue;
            int k = cands;
            cNote[k] = c;
            cOutcome[k] = outcome;
            cHit[k] = hit;
            cLive[k] = live;
            // what a copy must not sit on: the note now, or a hit note as it was when it was hit
            float ref = outcome == TrailPlan.HIT ? Math.min(beat, TrailPlan.upperExclusive(n, outcome, hit)) : beat;
            boolean refOk = place(n, t.mode(), ref, beat, frame, lineY);
            cRefOk[k] = refOk;
            if (refOk) {
                cRefX[k] = geo.x;
                cRefY[k] = geo.y;
                cRefW[k] = geo.laneWidth;
                cRefRot[k] = geo.rot;
                cRefTail[k] = geo.tail;
            }
            // narrow from both ends to the copies that can be seen (off screen, coincident and
            // invisible ones leave the count); a probe budget bounds the work per frame
            while (last >= first && probes > 0) {
                probes--;
                if (visible(e, k, n, last, beat)) break;
                last--;
            }
            while (first < last && probes > 0) {
                probes--;
                if (visible(e, k, n, first, beat)) break;
                first++;
            }
            if (last < first) continue;
            cands++;
            cFirst[k] = first;
            cLast[k] = last;
            cCopies[k] = t.copies();
            cQuality[k] = TrailPlan.qualityStride(t.copies(), lowQuality);
            cQuads[k] = tQuads[c];
        }
        if (cands == 0) return;
        int g = TrailPlan.budgetStride(cands, cFirst, cLast, cCopies, cQuality, cQuads, budget);
        if (g < globalStride) {
            // hysteresis: step back down only with some room, so a trail does not flicker at the edge
            int prevQ = TrailPlan.quadsAt(cands, cFirst, cLast, cCopies, cQuality, cQuads, globalStride);
            int newQ = TrailPlan.quadsAt(cands, cFirst, cLast, cCopies, cQuality, cQuads, g);
            if (prevQ <= budget && newQ > STRIDE_RELAX * budget) g = globalStride;
        }
        globalStride = g;
        // nearest notes first: in the extreme case the farthest trails are the ones left out
        int used = 0;
        cutoff = cands;
        for (int k = 0; k < cands; k++) {
            int s = TrailPlan.stride(cCopies[k], cQuality[k], g);
            int q = TrailPlan.countMultiples(cFirst[k], cLast[k], s) * cQuads[k];
            if (used + q > budget) {
                cutoff = k;
                break;
            }
            used += q;
        }
        quadsNeeded = used;
    }

    /**
     * @return true when copy j of candidate k can be seen at some stride: its moment passes the
     *         lifetime rules, it is placed on screen and not on its note, and its brightest
     *         possible opacity (thinning boost included) is above the draw threshold
     */
    private boolean visible(GameEngine e, int k, Note n, int j, float beat) {
        NoteTrail t = seg[cNote[k]];
        float s = t.momentOf(beat, j);
        if (!TrailPlan.showsMoment(n, t, cOutcome[k], cHit[k], s, low[cNote[k]])) return false;
        float opacity = placeCopy(e, k, n, s, beat);
        return Math.min(1f, t.alphaOf(j) * TrailPlan.THIN_BOOST) * opacity > NoteRenderer.MIN_ALPHA;
    }

    /** @return true when trail slot c can never show a copy again (its window cursor may pass it). */
    private boolean finished(GameEngine e, int c, float beat) {
        int i = trailed[c];
        Note n = notes[i];
        NoteTrail t = seg[c];
        int outcome = TrailPlan.outcomeOf(e.noteState(i));
        if (outcome == TrailPlan.LIVE) return false;
        if (TrailPlan.noCopies(n, t, outcome)) return true;
        float hi = Math.min(t.to(), TrailPlan.upperExclusive(n, outcome, outcome == TrailPlan.HIT ? hitBeat(e, i) : Float.NaN));
        if (outcome == TrailPlan.MISSED) {
            float at = e.noteResolvedMs(i);
            if (!Float.isNaN(at)) {
                double fadeEnd = at + e.visualOffsetMs() + MISS_FADE_MS + 1.0;
                hi = Math.min(hi, (float) chart.msToBeat(fadeEnd));
            }
        }
        return hi != Float.POSITIVE_INFINITY && beat - t.spanBeats() > hi + 1e-3f;
    }

    /** @return the visual beat at which note i got its current state (hit time). */
    private float hitBeat(GameEngine e, int i) {
        float at = e.noteResolvedMs(i);
        if (Float.isNaN(at)) return Float.NaN;
        return (float) chart.msToBeat(at + e.visualOffsetMs());
    }

    private int quadsPerCopy(Note n) {
        switch (n.type()) {
            case HOLD: return NoteRenderer.HOLD_QUADS;
            case MINE: return n.isLaneMine() ? NoteRenderer.LANE_MINE_COPY_QUADS : NoteRenderer.MINE_QUADS;
            case UNIVERSAL:
                return n.isTideStream() ? NoteRenderer.tideQuads(geo.laneCount()) + NoteRenderer.BAND_QUADS
                    : NoteShapes.t4Quads(geo.patternLanes(), n.isUpper());
            default: return NoteRenderer.TAP_QUADS;
        }
    }

    private static boolean hidden(float pos) {
        return TrailGeometry.hidden(pos);
    }

    // ================================================================== drawing

    /**
     * Draws every copy planned by {@link #prepare} (normal blend, inside block B, before the live
     * notes). Farther notes first, and in each trail the farthest copy first. The batch colour is
     * restored to white.
     */
    void draw(GameEngine e) {
        quadsDrawn = 0;
        copiesDrawn = 0;
        if (cutoff == 0) return;
        float beat = e.visualBeat();
        for (int k = cutoff - 1; k >= 0; k--) {
            int c = cNote[k];
            int i = trailed[c];
            Note n = notes[i];
            NoteTrail t = seg[c];
            int stride = TrailPlan.stride(cCopies[k], cQuality[k], globalStride);
            for (int j = cLast[k]; j >= cFirst[k]; j--) {
                if (!TrailPlan.drawnAt(j, stride)) continue;
                float s = t.momentOf(beat, j);
                if (!TrailPlan.showsMoment(n, t, cOutcome[k], cHit[k], s, low[c])) continue;
                if (quadsDrawn + cQuads[k] > budget) break;
                int q;
                switch (n.type()) {
                    case MINE: q = copyMine(e, i, n, k, j, stride, s, beat); break;
                    case UNIVERSAL: q = copyTide(e, i, n, k, j, stride, s, beat); break;
                    default: q = copyLane(e, i, n, k, j, stride, s, beat); break;
                }
                if (q > 0) {
                    quadsDrawn += q;
                    copiesDrawn++;
                }
            }
        }
        painter.batch().setColor(Color.WHITE);
    }

    /** @return ms since the note's miss at moment s (negative before it), or NaN when it was not missed. */
    private float missAge(GameEngine e, int i, int outcome, float s) {
        if (outcome != TrailPlan.MISSED) return Float.NaN;
        float at = e.noteResolvedMs(i);
        if (Float.isNaN(at)) return Float.NaN;
        return (float) (chart.beatToMs(s) - e.visualOffsetMs() - at);
    }

    /**
     * Places copy moment {@code s} of candidate k (into {@link #geo}) and culls it with the live
     * note's margins: off screen, an empty TIDE window, or sitting exactly on its note
     * ({@link TrailGeometry#sameAs} the candidate's reference).
     *
     * @return the note's own opacity at {@code s} (DisappearEvents × fade-in), 0 when the copy
     *         cannot be seen
     */
    private float placeCopy(GameEngine e, int k, Note n, float s, float beat) {
        TrailGeometry g = geo;
        if (!place(n, seg[cNote[k]].mode(), s, beat, e.layout(), e.lineY(n.line()))) return 0f;
        boolean up = n.isUpper();
        switch (n.type()) {
            case MINE: {
                float pad = NoteRenderer.rotPad(g.rot, g.laneWidth);
                float tail = n.isLaneMine() ? Math.min(g.tail, NoteRenderer.MAX_TAIL_DRAW) : 0f;
                // a turned column may point anywhere: cull it by its whole length on both sides; a
                // body or column turned toward the line reaches its length beyond the leading edge
                float toward = NoteRenderer.turnReach(up, g.rot, 0f, Math.max(painter.mineHeight(), tail));
                float near = NoteRenderer.FLAT_CULL + pad + Math.max(pad > 0.5f ? tail : 0f, toward);
                if (NoteRenderer.offScreen(up, g.y, Math.max(painter.mineHeight(), tail) + pad, near)) return 0f;
                break;
            }
            case UNIVERSAL: {
                float far = Math.max(painter.tideReach(n.isTideStream()), Math.min(g.tail, NoteRenderer.MAX_TAIL_DRAW));
                // as the live TIDE: a turned one crosses the screen slanted and is not culled
                boolean turned = NoteShapes.turned(g.rot - (up ? 180f : 0f));
                if (!turned && NoteRenderer.offScreen(up, g.y, far, NoteRenderer.FLAT_CULL)) return 0f;
                if (g.windowRight - g.windowLeft <= 2f * TIDE_INSET) return 0f;
                break;
            }
            default: {
                float tail = n.type() == NoteType.HOLD ? Math.min(g.tail, NoteRenderer.MAX_TAIL_DRAW) : 0f;
                float far = painter.noteHeight() + tail + NoteRenderer.LANE_CULL;
                float near = NoteRenderer.LANE_CULL;
                if (slides[cNote[k]]) { // as the live note: a slide keeps what its turned body reaches
                    float w = painter.headWidth(g.laneWidth);
                    far += NoteRenderer.rotPad(g.rot, w);
                    near += NoteRenderer.turnReach(up, g.rot, w, painter.noteHeight() + tail);
                }
                if (NoteRenderer.offScreen(up, g.y, far, near)) return 0f;
                break;
            }
        }
        if (cRefOk[k] && g.sameAs(n.type(), cRefX[k], cRefY[k], cRefW[k], cRefRot[k], cRefTail[k])) return 0f;
        int c = cNote[k];
        return n.shownOpacityAt(following ? entry.look(trailed[c], s) : s) * fadeIn(c, s, g.lineY, up);
    }

    /**
     * {@link TrailGeometry#place} with a lane note or mine moved as the live note is
     * ({@link NoteShapes#centreShiftY}), so a copy and its note line up at any note size.
     */
    private boolean place(Note n, NoteTrail.Mode mode, float s, float beat, LaneLayout frame, float lineY) {
        TrailGeometry g = geo;
        if (!g.place(n, mode, s, beat, frame, lineY)) return false;
        if (n.type() == NoteType.UNIVERSAL) return true;
        boolean mine = n.type() == NoteType.MINE;
        float ref = mine ? NoteRenderer.MINE_H : NoteRenderer.NOTE_H;
        float h = mine ? painter.mineHeight() : painter.noteHeight();
        g.x += NoteShapes.pivotShiftX(g.pos, ref, h, g.rot, n.isUpper(), n.pivotX(), n.pivotY());
        g.y += NoteShapes.pivotShiftY(g.pos, ref, h, g.rot, n.isUpper(), n.pivotX(), n.pivotY());
        return true;
    }

    /** Fade-in of trailed note c at beat {@code at} (as the live note: none on a slide from the screen edge). */
    private float fadeIn(int c, float at, float lineY, boolean up) {
        if (slides[c]) return 1f;
        float sinceMs = (float) chart.msBetween(notes[trailed[c]].visibleFrom(), at);
        return NoteRenderer.fadeInFrom(spawn[c], sinceMs, lineY, up);
    }

    private int copyLane(GameEngine e, int i, Note n, int k, int j, int stride, float s, float beat) {
        float opacity = placeCopy(e, k, n, s, beat);
        if (opacity <= 0f) return 0;
        TrailGeometry g = geo;
        boolean up = n.isUpper();
        boolean hold = n.type() == NoteType.HOLD;
        float tail = hold ? Math.min(g.tail, NoteRenderer.MAX_TAIL_DRAW) : 0f;
        float w = painter.headWidth(g.laneWidth);
        float h = painter.noteHeight();
        float a = TrailPlan.copyAlpha(seg[cNote[k]], j, stride, opacity, cLive[k]);
        Color base = up ? Theme.SKY_SIG : Theme.GROUND_SIG;
        float headMul = 1f;
        float ribHead = NoteShapes.ribbonHeadAlpha(false);
        float ribTail = NoteShapes.ribbonTailAlpha(false);
        boolean lost = false;
        float age = missAge(e, i, cOutcome[k], s);
        if (age >= 0f) {
            lost = true;
            if (hold) {
                float m = MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
                Theme.mix(base, Theme.MISSED, m, col);
                headMul = 1f - 0.70f * m;
                ribHead += (NoteShapes.RIBBON_BROKEN_HEAD_A - ribHead) * m;
                ribTail += (NoteShapes.RIBBON_BROKEN_TAIL_A - ribTail) * m;
            } else {
                Theme.mix(base, Theme.MISSED, MathUtils.clamp(age / MISS_GREY_MS, 0f, 1f), col);
                a *= 1f - MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
            }
        } else {
            col.set(base);
        }
        if (a <= NoteRenderer.MIN_ALPHA) return 0;
        float rw = hold ? NoteShapes.ribbonWidth(g.laneWidth, up) : 0f;
        return painter.paintLaneNote(g.x, g.y, w, h, g.rot, col, a, headMul, ribHead, ribTail, tail, rw,
            NoteShapes.ribbonEdge(rw, up), lost);
    }

    private int copyMine(GameEngine e, int i, Note n, int k, int j, int stride, float s, float beat) {
        float opacity = placeCopy(e, k, n, s, beat);
        if (opacity <= 0f) return 0;
        TrailGeometry g = geo;
        float mw = g.laneWidth * GameEngine.MINE_BOX;
        float mh = painter.mineHeight();
        float a = TrailPlan.copyAlpha(seg[cNote[k]], j, stride, opacity, cLive[k]);
        boolean laneMine = n.isLaneMine();
        float age = missAge(e, i, cOutcome[k], s);
        if (age >= 0f && !laneMine) {
            // a touched LANE MINE keeps blocking: its copies (all before its judgement) do not fade
            boolean touched = e.noteState(i) == GameEngine.STATE_MINE_TOUCHED;
            a *= 1f - MathUtils.clamp(age / (touched ? MINE_TOUCH_FADE_MS : MINE_PASS_FADE_MS), 0f, 1f);
        }
        if (a <= NoteRenderer.MIN_ALPHA) return 0;
        int q = 0;
        if (laneMine) {
            float tail = Math.min(g.tail, NoteRenderer.MAX_TAIL_DRAW);
            q += painter.paintLaneMineColumn(g.x, g.y, g.rot, g.laneWidth, tail, a, 0f,
                e.lineY(n.line().other()), false, false);
        }
        return q + painter.paintMine(g.x, g.y, mw, mh, g.rot, a);
    }

    /**
     * A TIDE copy (path only: a level copy would coincide with the live bar, see {@link TrailPlan}).
     * A TIDE drop copy is its T4 shape (no glow), its wave at the moment's song time; a missed one
     * greys and fades like a DROP copy. A TIDE STREAM copy is its band (no ripples, no knock-out),
     * tail cap and bar of the moment; it greys like a hold when the stream was missed (its moments
     * end at the judgement, so a copy is never of a held or broken stream).
     */
    private int copyTide(GameEngine e, int i, Note n, int k, int j, int stride, float s, float beat) {
        float opacity = placeCopy(e, k, n, s, beat);
        if (opacity <= 0f) return 0;
        TrailGeometry g = geo;
        boolean up = n.isUpper();
        boolean stream = n.isTideStream();
        float a = TrailPlan.copyAlpha(seg[cNote[k]], j, stride, opacity, cLive[k]);
        float age = missAge(e, i, cOutcome[k], s);
        boolean lost = age >= 0f;
        float grey = 0f;
        if (lost) {
            float m = MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
            grey = stream ? m : MathUtils.clamp(age / MISS_GREY_MS, 0f, 1f);
            a *= stream ? 1f - 0.70f * m : 1f - m;
        }
        if (a <= NoteRenderer.MIN_ALPHA) return 0;
        float x0 = g.windowLeft + TIDE_INSET;
        float x1 = g.windowRight - TIDE_INSET;
        float bx0 = up ? NoteShapes.skyBandLeft(x0, x1, NoteShapes.SKY_TIDE_STREAM_INSET) : x0;
        float bx1 = up ? NoteShapes.skyBandRight(x0, x1, NoteShapes.SKY_TIDE_STREAM_INSET) : x1;
        // turned like the live TIDE, about the pivot of the moment's window (one flush each way)
        boolean turned = painter.tideTurn(g.rot - (up ? 180f : 0f), n.pivotX(), n.pivotY(), g.y, up, x0, x1);
        float t0 = painter.tideLo(x0, x1);
        x1 = painter.tideHi(x0, x1);
        x0 = t0;
        t0 = painter.tideLo(bx0, bx1);
        bx1 = painter.tideHi(bx0, bx1);
        bx0 = t0;
        int q;
        if (!stream) {
            float seconds = (float) (chart.beatToMs(s) / 1000.0);
            q = painter.paintTideT4(x0, x1, g.y, up, grey, 0f, a, g.layout, seconds, false);
        } else {
            float tail = Math.min(g.tail, NoteRenderer.MAX_TAIL_DRAW);
            q = painter.paintTideBand(bx0, bx1, g.y, tail, up, 1f, grey, Float.NaN, painter.tideHeight(), a);
            q += painter.paintTideCap(bx0, bx1, g.y, tail, up, grey, a);
            q += painter.paintTide(x0, x1, g.y, painter.tideHeight(), up, lost, 0f, a, g.layout);
        }
        if (turned) painter.tideTurnEnd();
        return q;
    }

    // ================================================================== static demo (tutorial captions)

    /**
     * Draws a static afterimage illustration (for a caption or a legend; not tied to the chart):
     * {@code t}'s copies of a DROP of a lane {@code laneWidth} wide, copy k offset by
     * {@code (k·dx, k·dy)} from the note, farthest first, then the live note on top. Uses the
     * notes' sprites and the readability cap; call inside a normal-blend atlas pass.
     *
     * @param x         leading-edge centre x of the live note
     * @param y         leading-edge centre y of the live note
     * @param laneWidth lane width the note is sized for (240 = a default lane)
     * @param upper     sky colour and orientation instead of ground
     * @param t         the trail to illustrate (for example {@code length 0.75, density 12})
     * @param dx        x offset per copy (0 for a straight fall, ±20 for a level smear)
     * @param dy        y offset per copy (away from the line: + for ground, − for sky)
     * @param alpha     overall opacity (caption fade)
     * @return quads submitted
     */
    int drawDemo(float x, float y, float laneWidth, boolean upper, NoteTrail t, float dx, float dy, float alpha) {
        Color base = upper ? Theme.SKY_SIG : Theme.GROUND_SIG;
        float w = painter.headWidth(laneWidth);
        float h = painter.noteHeight();
        float rot = upper ? 180f : 0f;
        int q = 0;
        for (int k = t.copies(); k >= 1; k--) {
            float a = TrailPlan.copyAlpha(t, k, 1, alpha, alpha);
            if (a <= NoteRenderer.MIN_ALPHA) continue;
            q += painter.paintLaneNote(x + k * dx, y + k * dy, w, h, rot, base, a, 1f, 0f, 0f, 0f, 0f, 0f, false);
        }
        q += painter.paintLaneNote(x, y, w, h, rot, base, alpha, 1f, 0f, 0f, 0f, 0f, 0f, false);
        painter.batch().setColor(Color.WHITE);
        return q;
    }

    // ================================================================== diagnostics

    /** @return quads the last {@link #prepare} planned (after thinning). */
    int quadsNeeded() {
        return quadsNeeded;
    }

    /** @return quads the last {@link #draw} submitted. */
    int quadsDrawn() {
        return quadsDrawn;
    }

    /** @return copies the last {@link #draw} submitted. */
    int copiesDrawn() {
        return copiesDrawn;
    }

    /** @return the global stride of the last frame (1 = no thinning by the budget). */
    int globalStride() {
        return globalStride;
    }

    /** @return the trail budget of the last frame (quality budget, lowered by the batch room). */
    int budget() {
        return budget;
    }

    /** @return the live notes' quad bound the last frame's budget was computed with. */
    int liveQuads() {
        return liveQuads;
    }

    /** @return trailed notes considered in the last frame. */
    int candidates() {
        return cands;
    }

    /** @return candidates whose trails fit the budget in the last frame. */
    int drawnTrails() {
        return cutoff;
    }
}
