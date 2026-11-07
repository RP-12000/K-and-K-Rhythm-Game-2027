package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Floating;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 悬浮键的画法：空泡泡（发光的彩色圈）在原地浮现，中间的玻璃球从中心慢慢长满，长满的那一刻点（水滴蓝、DRAG 紫带菱形、水流白色珍珠、外面一圈金环按拍分段倒计时；
 * 水雷红色、六个铆钉，长水雷外面一圈红环倒计时）；可以有故障和扭曲；打中时闪一下、散出一圈小水泡，没打到瘪下去变灰，碰到水雷红色爆开（长水雷碰一次爆一次），
 * 没碰到的水雷到时间直接消失；
 * 同一拍的悬浮键、悬浮键和同一拍判定线上的键用发光的双押线连起来，DRAG 链用紫线连到下一个。
 *
 * <p>The floating notes (悬浮键, CHART_FORMAT §11; the approved mock-up v3, Kelvin 2026-11-06 15:51; since 7.187 the
 * glass keys of mock-up v4, 18:46 「泡泡上的玻璃键」 and 19:03), drawn by
 * {@link NoteRenderer} after the notes on the lines, in the normal blend. A note shows from its {@link Floating#appear}
 * as an empty bubble where the chart puts it ({@link FloatSpace}), growing from .86 to its full size as it fades in
 * (over .3 of its approach, .3 s at most); colour fills it from the centre, a disc of diameter
 * {@link Floating#discDiameter} (light at the centre, solid at its edge), full at its beat and spilling past the edge
 * after it. A tap is light blue, a DRAG purple with a light diamond, a STREAM a pearl with a gold ring outside
 * it, one piece per beat, that counts down clockwise from the top while it is held (it stays full and breathes then).
 * A mine fills red over a dark red core with a red rim and six rivets round it; a LONG MINE has a red ring outside it,
 * in one piece, that counts down from its beat to its end while it stays full (Kelvin 2026-11-06 15:01).
 * <b>Since 7.187</b> the empty bubble is a shell, a ring in the key's colour that glows more over the last .2 of its
 * approach, and the colour is a glass ball growing from the centre ({@link #glass}: lit from its upper left, a white
 * rim, a crescent glint; no drop in it, a DRAG's diamond kept). Tints: tap light blue, STREAM pearl white in a gold
 * shell (since 7.189, Kelvin 22:23 「然后改一下悬空stream的颜色吧，稍微有点难看」, chosen 22:32: 「珍珠」; gold before), DRAG
 * lilac, mine red. A note's glitch and distortion segments tear and bend it as they do a key on a line ({@link Host}).
 * Floating notes that share a beat are joined rim to rim by the doubles line, {@link #PAIR_W} wide with a soft glow,
 * left to right, and with one more such line to the nearest tap, hold or DRAG on a line that shares their beat (sync,
 * 19:03 「如果泡泡的tap和判定线的是sync也加一条双压线」), ending at that key's box; a DRAG chain by a purple line to its next
 * DRAG, which starts
 * moving along it once the earlier one's time has passed.
 *
 * <p>Effects come from the engine's state every frame, not spawned: a hit flashes the note and sends small bubbles
 * out (17, ECHO 11 tinted purple), a completed STREAM does the same in gold, a held one lets two small bubbles rise
 * every quarter beat, and a miss or a broken STREAM deflates, grey; a touched mine bursts red, a LONG MINE once at
 * each touch, and a mine nobody touched just goes when its time is over (Kelvin 2026-11-06 18:06–18:07:
 * 「水雷没点到就直接消失，点到了就爆炸」「轨道水雷点一次就爆炸特效一次。」). They follow the note and freeze with the
 * song.
 * The earliest note is on top. Nothing allocates per frame (a new chart builds its chains and pairs once).
 */
final class FloatNotes {
    /** How long a floating note stays in the drawn window after its end and its last judgement (ms). */
    static final double KEEP_MS = 1000.0;
    /** The fade-in: its share of the approach, at most this long (s); the bubble grows from this share of its size. */
    static final float FADE_SHARE = 0.3f, FADE_MAX_SEC = 0.3f, GROW_FROM = 0.86f;
    /** The rim glow comes in over this share of the approach before the note's beat. */
    static final float NEAR_SHARE = 0.2f;
    /** A STREAM's outer ring: its gap outside the rim, width, most pieces, and the doubles line's gap past it. */
    static final float RING_GAP = 12f, RING_W = 7f, PAIR_HOLD_GAP = 19f, PAIR_GAP = 6f;
    /** The doubles line's width (was 2 before 7.187, Kelvin 18:44 「正常的双押链接线条粗一点」). */
    static final float PAIR_W = 5f;
    static final int RING_PIECES = 16;
    /** Burst: small bubbles for RESONANCE / ECHO, the flash's length (s), the burst's and the deflate's length. */
    static final int BURST_RES = 17, BURST_ECHO = 11;
    static final float FLASH_SEC = 0.13f, BURST_SEC = 0.85f, DEFLATE_SEC = 0.42f, RISE_SEC = 0.6f;

    private static final Color GOLD_TIP = new Color(0xFFF4D6FF);
    private static final Color MINE_TIP = new Color(0xFFD0D4FF);
    /** The shell's faint inside and a DRAG's diamond. */
    private static final Color SHELL = new Color(0x080E28FF), DIAMOND = new Color(0xF7F0FFFF);

    /** One kind of key's glass (mock-up v4): its ring, and its ball light, mid and deep. */
    private static final class Glass {
        final Color ring, light, mid, deep;

        Glass(int ring, int light, int mid, int deep) {
            this.ring = new Color(ring << 8 | 0xFF);
            this.light = new Color(light << 8 | 0xFF);
            this.mid = new Color(mid << 8 | 0xFF);
            this.deep = new Color(deep << 8 | 0xFF);
        }
    }

    private static final Glass TAP_GLASS = new Glass(0x4FE6FF, 0xD9F6FF, 0x5FB2D6, 0x22597D);
    /** A STREAM: a pearl in a gold shell (7.189; a gold ball, light #FFF8E2, mid #F2C66C, deep #B07A22, before). */
    private static final Glass HOLD_GLASS = new Glass(0xFFD27A, 0xFFFFFF, 0xE8EEFA, 0x8E9CC4);
    private static final Glass DRAG_GLASS = new Glass(0xBF95FF, 0xF3EAFF, 0xA182E2, 0x4B2E8C);
    private static final Glass MINE_GLASS = new Glass(0xFF3344, 0xFFD6DA, 0xE2414F, 0x6E0B18);

    /** What {@link NoteRenderer} lends the floating notes: a note's glitch and bend, and where a key on a line is. */
    interface Host {
        /** Starts recording note i's drawing when it glitches or bends at {@code beat}, about (x, y); true then. */
        boolean floatGlitchBegin(int i, Note n, float beat, float x, float y);

        /** Draws what {@link #floatGlitchBegin} recorded, torn or bent. */
        void floatGlitchEnd();

        /**
         * Where key k on a line is drawn now: {@code out} = its box's centre x, y, half width, half height, turn
         * (degrees), opacity; false when it is not drawn.
         */
        boolean lineKey(GameEngine e, int k, Note n, float beat, float[] out);
    }
    private static final float TWO_PI = MathUtils.PI2;

    private final Draw draw;
    private final Host host;
    private final SpriteBatch batch;
    private final Texture atlas;
    private final Draw.Skin puff;
    private final float coreU, coreV;
    private final float[] verts = new float[20];
    private final float[] quad = new float[8];

    /**
     * The chart the chains and pairs were built for, and per note the one before it in its DRAG chain / the next one
     * on its beat, or −1.
     */
    private Chart seen;
    private int[] chainPrev = new int[0];
    private int[] pairNext = new int[0];
    /**
     * For the first floating note of a beat that has keys on the lines: the notes of that beat
     * ({@code [mateLo, mateHi)}); −1 for every other note.
     */
    private int[] mateLo = new int[0];
    private int[] mateHi = new int[0];
    /** Most keys on the lines of one beat that the sync line looks at. */
    private static final int MATE_KEYS = 16;
    private final float[] key = new float[6];
    private final float[] keys = new float[6 * MATE_KEYS];

    // the note in hand (world units): centre, radius at full size, world units per reference unit
    private float cx, cy, br, sc;

    FloatNotes(Draw draw, Host host) {
        this.draw = draw;
        this.host = host;
        this.batch = draw.batch();
        this.atlas = draw.atlasTexture();
        this.puff = draw.skin(Draw.BUBBLE);
        TextureRegion core = new TextureRegion(draw.white(), 1, 1, 2, 2);
        coreU = 0.5f * (core.getU() + core.getU2());
        coreV = 0.5f * (core.getV() + core.getV2());
    }

    /**
     * @return true when floating note i can leave the drawn window at visual time {@code vis}: a decoration after its
     *         end, a judged note {@link #KEEP_MS} after its end and its last judgement (its effects)
     */
    static boolean done(GameEngine e, int i, Note n, double vis) {
        if (n.fake()) return vis > n.endMs() + 500.0;
        byte s = e.noteState(i);
        if (s == GameEngine.STATE_PENDING || s == GameEngine.STATE_HOLDING) return false;
        float at = e.noteResolvedMs(i);
        return !Float.isNaN(at) && vis > at + KEEP_MS && vis > n.endMs() + KEEP_MS;
    }

    // ================================================================== drawing

    /**
     * Draws the floating notes of {@code [start, end)}: the chain and pair lines, the notes (the earliest on top; mines
     * over the others), then their effects. The batch colour is left white.
     */
    void draw(GameEngine e, Note[] notes, int start, int end, float beat) {
        Chart chart = e.chart();
        if (chart == null) return;
        links(chart, notes);
        double vis = e.visualMs();
        float px = Ui.worldPerPx();
        for (int i = start; i < end; i++) {
            Note n = notes[i];
            if (!n.floats() || beat < n.visibleFrom()) continue;
            if (chainPrev[i] >= 0) chain(e, notes, chainPrev[i], i, vis, px);
            if (pairNext[i] >= 0) pair(e, notes, i, pairNext[i], vis, px);
            if (mateLo[i] >= 0) linePair(e, notes, mateLo[i], mateHi[i], beat, vis, px);
        }
        for (int pass = 0; pass < 2; pass++) { // the mines over the notes, so the part to keep away from shows
            for (int i = end - 1; i >= start; i--) {
                Note n = notes[i];
                if (!n.floats() || beat < n.visibleFrom() || n.type().judged() == (pass == 1)) continue;
                note(e, i, n, vis, px);
            }
        }
        double now = e.judgeMs();
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.floats() || n.fake()) continue;
            effects(e, chart, i, n, now, px);
        }
        batch.setColor(Color.WHITE);
    }

    /** Builds {@link #chainPrev} and {@link #pairNext} once for a chart. */
    private void links(Chart chart, Note[] notes) {
        if (chart == seen) return;
        seen = chart;
        int n = notes.length;
        if (chainPrev.length < n) {
            chainPrev = new int[n];
            pairNext = new int[n];
            mateLo = new int[n];
            mateHi = new int[n];
        }
        Arrays.fill(chainPrev, 0, n, -1);
        Arrays.fill(pairNext, 0, n, -1);
        Arrays.fill(mateLo, 0, n, -1);
        // notes are in time order: each DRAG of a chain points back at the one before it
        Map<String, Integer> last = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Floating f = notes[i].floating();
            if (f == null || f.chain() == null) continue;
            Integer p = last.put(f.chain(), i);
            if (p != null) chainPrev[i] = p;
        }
        // floating notes on one beat, left to right by their written x; each to a key on a line of that beat
        for (int i = 0; i < n; ) {
            int j = i;
            while (j < n && notes[j].judgement() == notes[i].judgement()) j++;
            boolean lineKeys = false;
            for (int q = i; q < j; q++) lineKeys |= lineMate(notes[q]);
            for (int q = i; q < j && lineKeys; q++) {
                Note m = notes[q];
                if (!m.floats() || !Chart.playable(m) || !m.type().judged()) continue;
                mateLo[q] = i; // the beat's first floating note draws its one sync line
                mateHi[q] = j;
                break;
            }
            int prev = -1;
            for (int k = i; k < j; k++) {
                int best = -1;
                for (int q = i; q < j; q++) {
                    Note m = notes[q];
                    if (!m.floats() || !Chart.playable(m) || !m.type().judged()) continue; // no mines
                    if (prev >= 0 && !after(notes, q, prev)) continue;
                    if (best < 0 || after(notes, best, q)) best = q;
                }
                if (best < 0) break;
                if (prev >= 0) pairNext[prev] = best;
                prev = best;
            }
            i = j;
        }
    }

    /** @return true when floating note a comes after b left to right (by written x, then index) */
    private static boolean after(Note[] notes, int a, int b) {
        float xa = notes[a].floating().x(), xb = notes[b].floating().x();
        return xa != xb ? xa > xb : a > b;
    }

    /** Sets {@link #cx}, {@link #cy}, {@link #br} and {@link #sc} for note n now; false when it has no place. */
    private boolean place(GameEngine e, Note n) {
        Floating f = n.floating();
        sc = FloatSpace.scale(e.layout());
        cx = FloatSpace.worldX(e.layout(), f);
        cy = FloatSpace.worldY(e.layout(), f);
        br = FloatSpace.radius(e.layout(), f);
        return br > 0.5f && !Float.isNaN(cx) && !Float.isNaN(cy);
    }

    /** @return the approach of note n, its beat minus where it appears (ms, at least 1) */
    private static double approachMs(GameEngine e, Note n) {
        return Math.max(1.0, n.judgementMs() - e.chart().beatToMs(n.floating().appear()));
    }

    /** @return 0 → 1 as note n fades in at visual time vis (0 before it appears) */
    private static float fadeIn(GameEngine e, Note n, double vis) {
        double a = approachMs(e, n);
        double t0 = n.judgementMs() - a;
        if (vis < t0) return 0f;
        double len = Math.min(FADE_MAX_SEC * 1000.0, FADE_SHARE * a);
        float u = MathUtils.clamp((float) ((vis - t0) / len), 0f, 1f);
        float v = 1f - u;
        return 1f - v * v * v;
    }

    /**
     * Opacity of note n now (0: not drawn): its fade-in times its opacity events; gone once judged, a mine once
     * touched or passed (a LONG MINE at its end).
     */
    private float alpha(GameEngine e, int i, Note n, double vis, float beat) {
        byte s = e.noteState(i);
        if (s == GameEngine.STATE_HIT || s == GameEngine.STATE_COMPLETED || s == GameEngine.STATE_LOST) return 0f;
        if (s == GameEngine.STATE_MINE_PASSED || (n.isBlockMine() ? vis >= n.endMs()
            : s == GameEngine.STATE_MINE_TOUCHED)) {
            return 0f;
        }
        if (n.fake() && beat >= n.endBeat()) return 0f;
        return fadeIn(e, n, vis) * n.shownOpacityAt(beat);
    }

    private void note(GameEngine e, int i, Note n, double vis, float px) {
        float beat = e.visualBeat();
        float a = alpha(e, i, n, vis, beat);
        if (a <= NoteRenderer.MIN_ALPHA || !place(e, n)) return;
        float app = fadeIn(e, n, vis);
        boolean hold = n.type() == NoteType.HOLD;
        boolean drag = n.type() == NoteType.DRAG;
        boolean mine = !n.type().judged();
        boolean holding = e.noteState(i) == GameEngine.STATE_HOLDING;
        boolean full = mine && vis >= n.judgementMs(); // a mine stays just full from its beat until it goes
        boolean caught = drag && !Float.isNaN(e.dragCaughtMs(i));
        float grow = GROW_FROM + (1f - GROW_FROM) * app;
        float r = br * grow;
        Floating f = n.floating();
        float fill = f.fillAt(beat, n.judgement()) * sc * grow;
        if (holding) {
            fill = 0f;
            if (e.isHoldHeld(i)) {
                double press = n.judgementMs() + e.noteDtMs(i);
                r = br * (1f + 0.02f * MathUtils.sin((float) ((vis - press) / 1000.0) * 16f));
            }
        }
        if (caught || full) fill = 0f;
        Glass gl = mine ? MINE_GLASS : drag ? DRAG_GLASS : hold ? HOLD_GLASS : TAP_GLASS;
        double approach = approachMs(e, n);
        float near = holding || caught || full ? 1f
            : MathUtils.clamp(1f - (float) ((n.judgementMs() - vis) / (NEAR_SHARE * approach)), 0f, 1f);
        boolean recorded = host.floatGlitchBegin(i, n, beat, cx, cy);
        shell(r, gl, near, a, px);
        glass(0.5f * Floating.discDiameter(2f * r, fill), gl, drag, a, px);
        if (mine) { // six rivets round it (the mock-up's, from the top)
            for (int k = 0; k < 6; k++) {
                float an = MathUtils.HALF_PI + k * MathUtils.PI / 3f;
                draw.disc(cx + MathUtils.cos(an) * (r + 4f * sc), cy + MathUtils.sin(an) * (r + 4f * sc),
                    Math.max(4f * sc, 0.08f * r), Theme.MINE_RIVET, a);
            }
        }
        if (hold || n.isBlockMine()) {
            float prog = holding || full
                ? MathUtils.clamp((float) ((vis - n.judgementMs()) / (n.endMs() - n.judgementMs())), 0f, 1f) : 0f;
            if (hold) outerRing(r, prog, a, Math.round(n.duration()), Theme.GOLD, GOLD_TIP, px);
            else outerRing(r, prog, a, 1, Theme.MINE, MINE_TIP, px);
        }
        if (recorded) host.floatGlitchEnd();
    }

    /**
     * The empty bubble of radius {@code R} (mock-up v4): a faint dark inside, a ring in the key's colour with a soft
     * glow that grows as its beat nears ({@code glow} 0 → 1), a thin white ring on it and a faint glint at its upper
     * left.
     */
    private void shell(float R, Glass gl, float glow, float a, float px) {
        draw.disc(cx, cy, R, SHELL, 0.35f * a);
        softRing(R, (7f + 8f * glow) * sc, gl.ring, (0.28f + 0.22f * glow) * a, px);
        ring(cx, cy, R, (5f + 2f * glow) * sc, gl.ring, 0.9f * a, px);
        ring(cx, cy, R, 1.5f * sc, Theme.WHITE, (0.2f + 0.35f * glow) * a, px);
        draw.disc(cx - 0.55f * R, cy + 0.55f * R, Math.max(2f * sc, 0.07f * R), Theme.WHITE, 0.1f * a);
    }

    /**
     * The glass ball of radius {@code rb} at the note's centre (mock-up v4, as the keys on the bubbles of the morning
     * demo): lit from its upper left (light there, mid at .45 of the way, deep at its edge), a faint inner ring, a
     * white rim, a crescent glint at its upper left and a small one at its lower right; a DRAG keeps its light diamond
     * (Kelvin 19:03 「泡泡中间的水滴不要了，drag的菱形保留」).
     */
    private void glass(float rb, Glass gl, boolean drag, float a, float px) {
        if (rb < 0.5f * sc) return;
        px = Math.max(px, 1e-3f);
        int n = pieces(rb, px);
        float fx = cx - 0.3f * rb, fy = cy + 0.38f * rb;
        float cl = bits(gl.light, 0.85f * a), cm = bits(gl.mid, 0.72f * a), cd = bits(gl.deep, 0.9f * a);
        float clear = bits(gl.deep, 0f);
        float r0 = Math.max(0f, rb - 0.5f * px), r1 = rb + 0.5f * px;
        float ca = 1f, sa = 0f;
        Batch b = draw.sink();
        for (int k = 1; k <= n; k++) {
            float t = TWO_PI * k / n;
            float cb = MathUtils.cos(t), sb = MathUtils.sin(t);
            float ex0 = cx + r0 * ca, ey0 = cy + r0 * sa, ex1 = cx + r0 * cb, ey1 = cy + r0 * sb;
            float mx0 = fx + 0.45f * (ex0 - fx), my0 = fy + 0.45f * (ey0 - fy);
            float mx1 = fx + 0.45f * (ex1 - fx), my1 = fy + 0.45f * (ey1 - fy);
            quadOf(b, fx, fy, cl, mx0, my0, cm, mx1, my1, cm, fx, fy, cl);
            quadOf(b, mx0, my0, cm, ex0, ey0, cd, ex1, ey1, cd, mx1, my1, cm);
            quadOf(b, ex0, ey0, cd, cx + r1 * ca, cy + r1 * sa, clear, cx + r1 * cb, cy + r1 * sb, clear, ex1, ey1, cd);
            ca = cb;
            sa = sb;
        }
        ring(cx, cy, 0.84f * rb, Math.max(sc, 0.025f * rb), Theme.WHITE, 0.16f * a, px);
        float rim = Math.max(1.4f * sc, 0.05f * rb);
        ring(cx, cy, Math.max(0.5f * sc, rb - 0.5f * rim), rim, Theme.WHITE, 0.95f * a, px);
        // the crescent at the upper left (the mock-up's canvas arc 1.06π → 1.42π, y down) and the small glint
        glint(0.7f * rb, -1.42f * MathUtils.PI, 0.36f * MathUtils.PI, Math.max(1.5f * sc, 0.09f * rb), 0.85f * a, px);
        glint(0.78f * rb, -0.32f * MathUtils.PI, 0.22f * MathUtils.PI, Math.max(sc, 0.045f * rb), 0.45f * a, px);
        if (drag && rb > 10f * sc) {
            float d = 0.3f * rb;
            quad[0] = cx;
            quad[1] = cy - d;
            quad[2] = cx + 0.72f * d;
            quad[3] = cy;
            quad[4] = cx;
            quad[5] = cy + d;
            quad[6] = cx - 0.72f * d;
            quad[7] = cy;
            draw.convexFill(quad, 4, DIAMOND, 0.95f * a, px);
        }
    }

    /** A white arc of radius gr about the note from {@code start} over {@code sweep} (radians), {@code w} wide. */
    private void glint(float gr, float start, float sweep, float w, float a, float px) {
        draw.ringArc(cx, cy, gr, start, sweep, w, px, Theme.WHITE, a);
        float end = start + sweep;
        draw.disc(cx + MathUtils.cos(start) * gr, cy + MathUtils.sin(start) * gr, 0.5f * w, Theme.WHITE, a);
        draw.disc(cx + MathUtils.cos(end) * gr, cy + MathUtils.sin(end) * gr, 0.5f * w, Theme.WHITE, a);
    }

    /** A soft ring about the note: colour c at {@code a} on radius R, fading to nothing {@code half} in and out. */
    private void softRing(float R, float half, Color c, float a, float px) {
        if (a <= 0f || half <= 0f) return;
        int n = pieces(R + half, px);
        float solid = bits(c, a), clear = bits(c, 0f);
        float r0 = Math.max(0f, R - half), r2 = R + half;
        float ca = 1f, sa = 0f;
        Batch b = draw.sink();
        for (int k = 1; k <= n; k++) {
            float t = TWO_PI * k / n;
            float cb = MathUtils.cos(t), sb = MathUtils.sin(t);
            quadOf(b, cx + r0 * ca, cy + r0 * sa, clear, cx + R * ca, cy + R * sa, solid, cx + R * cb, cy + R * sb,
                solid, cx + r0 * cb, cy + r0 * sb, clear);
            quadOf(b, cx + R * ca, cy + R * sa, solid, cx + r2 * ca, cy + r2 * sa, clear, cx + r2 * cb, cy + r2 * sb,
                clear, cx + R * cb, cy + R * sb, solid);
            ca = cb;
            sa = sb;
        }
    }

    /** @return colour c at opacity a (0..1) as packed bits */
    private static float bits(Color c, float a) {
        return Color.toFloatBits(c.r, c.g, c.b, MathUtils.clamp(a, 0f, 1f));
    }

    /** One quad of the white core into {@code b}, its corners in order with their packed colours. */
    private void quadOf(Batch b, float x0, float y0, float c0, float x1, float y1, float c1, float x2, float y2,
                        float c2, float x3, float y3, float c3) {
        vert(0, x0, y0, c0);
        vert(5, x1, y1, c1);
        vert(10, x2, y2, c2);
        vert(15, x3, y3, c3);
        b.draw(atlas, verts, 0, 20);
    }

    /**
     * A STREAM's gold ring (a LONG MINE's red one) {@link #RING_GAP} outside its rim, in {@code pieces}
     * (1–{@link #RING_PIECES}) pieces round from the top: whole until it is held (blocks), then counting down
     * clockwise ({@code prog} 0 → 1), its tip a light dot, a faint whole ring under it.
     */
    private void outerRing(float r, float prog, float a, int pieces, Color c, Color tip, float px) {
        float gr = r + RING_GAP * sc;
        int k = MathUtils.clamp(pieces, 1, RING_PIECES);
        float gap = k > 1 ? Math.min(0.16f, TWO_PI / k * 0.16f) : 0f;
        if (prog > 0f) ring(cx, cy, gr, 5f * sc, c, 0.16f * a, px);
        if (prog >= 1f) return;
        // the mock-up's canvas angles (y down, clockwise from the top at −π/2); world angle = −canvas angle
        float a0 = -MathUtils.HALF_PI;
        float s = a0 + prog * TWO_PI;
        float w = RING_W * sc;
        boolean caps = k > 1 || prog > 0f;
        for (int i = 0; i < k; i++) {
            float from = a0 + i * TWO_PI / k + 0.5f * gap;
            float to = a0 + (i + 1) * TWO_PI / k - 0.5f * gap;
            if (to <= s) continue;
            from = Math.max(from, s);
            draw.ringArc(cx, cy, gr, -to, to - from, w, px, c, 0.95f * a);
            if (caps) {
                draw.disc(cx + MathUtils.cos(-from) * gr, cy + MathUtils.sin(-from) * gr, 0.5f * w, c, 0.95f * a);
                draw.disc(cx + MathUtils.cos(-to) * gr, cy + MathUtils.sin(-to) * gr, 0.5f * w, c, 0.95f * a);
            }
        }
        if (prog > 0f) {
            float tx = cx + MathUtils.cos(-s) * gr, ty = cy + MathUtils.sin(-s) * gr;
            draw.disc(tx, ty, 15f * sc, c, 0.3f * a);
            draw.disc(tx, ty, 7f * sc, tip, a);
        }
    }

    /** The purple line from DRAG i (moving on toward m once i's time has passed) to the next DRAG m of its chain. */
    private void chain(GameEngine e, Note[] notes, int i, int m, double vis, float px) {
        Note b = notes[m];
        if (e.noteState(m) != GameEngine.STATE_PENDING) return;
        float am = alpha(e, m, b, vis, e.visualBeat());
        if (am <= 0f || !place(e, b)) return;
        float x2 = cx, y2 = cy;
        if (!place(e, notes[i])) return;
        float x1 = cx, y1 = cy;
        double ti = notes[i].judgementMs(), tm = b.judgementMs();
        if (vis > ti && tm > ti) {
            float u = MathUtils.clamp((float) ((vis - ti) / (tm - ti)), 0f, 1f);
            x1 += (x2 - x1) * u;
            y1 += (y2 - y1) * u;
        }
        segment(x1, y1, x2, y2, 6f * sc, Theme.DRAG, 0.42f * am, px, true);
    }

    /**
     * The doubles line joining floating notes i and m that share a beat, from rim to rim, while neither is gone (none
     * where they overlap); as see-through as the fainter of the two.
     */
    private void pair(GameEngine e, Note[] notes, int i, int m, double vis, float px) {
        if (!live(e.noteState(i)) || !live(e.noteState(m))) return;
        float beat = e.visualBeat();
        float aa = alpha(e, i, notes[i], vis, beat), am = alpha(e, m, notes[m], vis, beat);
        if (aa <= 0f || am <= 0f || !place(e, notes[i])) return;
        float x1 = cx, y1 = cy, r1 = br + gap(notes[i]) * sc;
        if (!place(e, notes[m])) return;
        float r2 = br + gap(notes[m]) * sc;
        float dx = cx - x1, dy = cy - y1;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d <= r1 + r2) return;
        float ux = dx / d, uy = dy / d;
        pairLine(x1 + ux * r1, y1 + uy * r1, cx - ux * r2, cy - uy * r2, Math.min(aa, am), px);
    }

    /**
     * The one doubles line of a beat ({@code [lo, hi)}) that has floating notes and keys on the lines (sync, Kelvin
     * 2026-11-06 19:03): between the floating note and the tap, hold or DRAG on a line that are nearest each other
     * (rim to centre), both to be judged; from that note's rim to {@link #PAIR_GAP} short of the key's box. The
     * floating notes of the beat are joined among themselves already ({@link #pair}).
     */
    private void linePair(GameEngine e, Note[] notes, int lo, int hi, float beat, double vis, float px) {
        int nk = 0;
        for (int k = lo; k < hi && nk < MATE_KEYS; k++) {
            Note m = notes[k];
            if (!lineMate(m) || !live(e.noteState(k)) || !host.lineKey(e, k, m, beat, key)) continue;
            System.arraycopy(key, 0, keys, 6 * nk, 6);
            nk++;
        }
        if (nk == 0) return;
        float best = Float.POSITIVE_INFINITY, fx = 0f, fy = 0f, fr = 0f, fa = 0f;
        int bk = -1;
        for (int f = lo; f < hi; f++) {
            Note n = notes[f];
            if (!n.floats() || !n.type().judged() || !Chart.playable(n) || !live(e.noteState(f))) continue;
            float af = alpha(e, f, n, vis, beat);
            if (af <= 0f || !place(e, n)) continue;
            for (int q = 0; q < nk; q++) {
                float dx = keys[6 * q] - cx, dy = keys[6 * q + 1] - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy) - br;
                if (d < best) {
                    best = d;
                    bk = q;
                    fx = cx;
                    fy = cy;
                    fr = br + gap(n) * sc;
                    fa = af;
                }
            }
        }
        if (bk < 0) return;
        int o = 6 * bk;
        float dx = keys[o] - fx, dy = keys[o + 1] - fy;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(d > 0.01f)) return;
        float ux = dx / d, uy = dy / d;
        // how far the box reaches from its centre along the line, in the box's own frame
        float c = MathUtils.cosDeg(keys[o + 4]), s = MathUtils.sinDeg(keys[o + 4]);
        float lx = Math.abs(ux * c + uy * s), ly = Math.abs(uy * c - ux * s);
        float reach = Math.min(lx > 1e-4f ? keys[o + 2] / lx : Float.MAX_VALUE,
            ly > 1e-4f ? keys[o + 3] / ly : Float.MAX_VALUE);
        float r2 = reach + PAIR_GAP * sc;
        if (d <= fr + r2) return;
        pairLine(fx + ux * fr, fy + uy * fr, keys[o] - ux * r2, keys[o + 1] - uy * r2, Math.min(fa, keys[o + 5]), px);
    }

    /** @return true for a key on a line that a floating note on its beat is joined to: a played tap, hold or DRAG */
    private static boolean lineMate(Note m) {
        return !m.floats() && m.type().inLane() && m.type().judged() && Chart.playable(m);
    }

    /** @return the doubles line's gap past note n's rim: past a STREAM's outer ring */
    private static float gap(Note n) {
        return n.type() == NoteType.HOLD ? PAIR_HOLD_GAP : PAIR_GAP;
    }

    /** The doubles line from (x1, y1) to (x2, y2): {@link #PAIR_W} wide, ink white, round ends, softly glowing. */
    private void pairLine(float x1, float y1, float x2, float y2, float a, float px) {
        glowBand(x1, y1, x2, y2, 8f * sc, Theme.INK1, 0.3f * a);
        segment(x1, y1, x2, y2, PAIR_W * sc, Theme.INK1, 0.9f * a, px, true);
    }

    /** A soft band along (x1, y1) → (x2, y2): colour c at {@code a} on the line, fading to nothing {@code h} away. */
    private void glowBand(float x1, float y1, float x2, float y2, float h, Color c, float a) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 0.01f) || a <= 0f) return;
        float nx = -dy / len * h, ny = dx / len * h;
        float solid = bits(c, a), clear = bits(c, 0f);
        Batch b = draw.sink();
        quadOf(b, x1, y1, solid, x2, y2, solid, x2 + nx, y2 + ny, clear, x1 + nx, y1 + ny, clear);
        quadOf(b, x1, y1, solid, x1 - nx, y1 - ny, clear, x2 - nx, y2 - ny, clear, x2, y2, solid);
    }

    /** @return true for a note still to be judged or being held */
    private static boolean live(byte s) {
        return s == GameEngine.STATE_PENDING || s == GameEngine.STATE_HOLDING;
    }

    /** A straight line {@code w} wide from (x1, y1) to (x2, y2), anti-aliased, with round ends when {@code round}. */
    private void segment(float x1, float y1, float x2, float y2, float w, Color c, float a, float px, boolean round) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 0.01f) || a <= 0f) return;
        float h = 0.5f * w;
        float nx = -dy / len * h, ny = dx / len * h;
        quad[0] = x1 - nx;
        quad[1] = y1 - ny;
        quad[2] = x2 - nx;
        quad[3] = y2 - ny;
        quad[4] = x2 + nx;
        quad[5] = y2 + ny;
        quad[6] = x1 + nx;
        quad[7] = y1 + ny;
        draw.convexFill(quad, 4, c, a, px);
        if (round) {
            draw.disc(x1, y1, h, c, a);
            draw.disc(x2, y2, h, c, a);
        }
    }

    /** A whole anti-aliased ring of radius r, {@code w} wide. */
    private void ring(float x, float y, float r, float w, Color c, float a, float px) {
        draw.ringArc(x, y, r, 0f, TWO_PI, w, px, c, a);
    }

    /** Pieces round a circle of radius r so each chord stays within .2 px of it ({@link Draw#ringArc}'s rule). */
    private static int pieces(float r, float px) {
        return MathUtils.clamp(MathUtils.ceil(TWO_PI * (float) Math.sqrt(r / (1.6f * Math.max(px, 1e-3f)))), 8, 160);
    }

    private void vert(int k, float x, float y, float c) {
        float[] q = verts;
        q[k] = x;
        q[k + 1] = y;
        q[k + 2] = c;
        q[k + 3] = coreU;
        q[k + 4] = coreV;
    }

    // ================================================================== effects

    private void effects(GameEngine e, Chart chart, int i, Note n, double now, float px) {
        byte s = e.noteState(i);
        if (s == GameEngine.STATE_PENDING) return;
        if (!place(e, n)) return;
        Judgement j = e.noteJudgement(i);
        boolean echo = j == Judgement.ECHO;
        float at = e.noteResolvedMs(i);
        if (!n.type().judged()) { // a touched mine bursts red, a LONG MINE once at each touch; a passed one just goes
            if (s != GameEngine.STATE_MINE_TOUCHED) return;
            boolean block = n.isBlockMine();
            burst(age(now, block ? e.mineLastContactMs(i) : at), i + 7919 * e.mineContacts(i), Theme.MINE, false);
            return;
        }
        if (s == GameEngine.STATE_LOST) {
            deflate(age(now, at), px);
            return;
        }
        boolean hold = n.type() == NoteType.HOLD;
        Color col = n.type() == NoteType.DRAG ? Theme.DRAG : hold ? Theme.GOLD : Theme.GROUND_NOTE;
        if (!hold) {
            if (s == GameEngine.STATE_HIT) burst(age(now, at), i, col, echo);
            return;
        }
        double head = n.judgementMs() + e.noteDtMs(i);
        boolean running = s == GameEngine.STATE_HOLDING;
        double stop = running ? now : at;
        rises(chart, n, i, head, stop, now, echo ? Theme.ECHO : Theme.GOLD);
        if (s == GameEngine.STATE_COMPLETED) burst(age(now, at), i + 7919, Theme.GOLD, echo);
    }

    private static float age(double now, double at) {
        return (float) ((now - at) / 1000.0);
    }

    /**
     * A hit {@code age} seconds ago: the note flashes (its colour, growing to 1.14×, {@link #FLASH_SEC}) and small
     * bubbles leave it, slowing as they go and floating up, a third of them filled, most tinted (ECHO purple).
     */
    private void burst(float age, int seed, Color col, boolean echo) {
        if (!(age >= 0f) || age > BURST_SEC) return;
        if (age < FLASH_SEC) {
            float cu = age / FLASH_SEC;
            float v = 1f - cu;
            draw.disc(cx, cy, br * (1f + 0.14f * (1f - v * v * v)), col, 0.7f * v);
        }
        Color tint = echo ? Theme.ECHO : col;
        int count = echo ? BURST_ECHO : BURST_RES;
        float k = br / (80f * sc);
        float ks = MathUtils.clamp(k, 0.6f, 1.6f);
        for (int p = 0; p < count; p++) {
            int h = seed * 31 + p;
            float an = Anim.hash01(h, 1) * TWO_PI;
            float start = br * (float) Math.sqrt(Anim.hash01(h, 2)) * 0.55f;
            float v = (330f + 420f * Anim.hash01(h, 3)) * k * sc;
            float size = (3f + 10f * Anim.hash01(h, 4)) * ks * sc;
            boolean filled = Anim.hash01(h, 5) < 0.3f;
            Color c = Anim.hash01(h, 6) < 0.55f ? tint : Theme.WHITE;
            float life = 0.5f + 0.3f * Anim.hash01(h, 7);
            particle(age, an, start, v, size, filled, c, life);
        }
    }

    /**
     * A held STREAM from {@code head} to {@code stop} (judge ms): two small bubbles rise off its top every quarter beat
     * from its beat, each for {@link #RISE_SEC}.
     */
    private void rises(Chart chart, Note n, int seed, double head, double stop, double now, Color c) {
        double b0 = n.judgement();
        double last = chart.msToBeat(Math.min(now, stop));
        for (int q = (int) Math.floor((last - b0) / 0.25); q >= 1; q--) {
            double at = chart.beatToMs(b0 + q * 0.25);
            if (at < head) break;
            float a = age(now, at);
            if (a > RISE_SEC) break;
            for (int p = 0; p < 2; p++) {
                int h = (seed * 131 + q) * 7 + p;
                float an = MathUtils.HALF_PI + (Anim.hash01(h, 1) - 0.5f) * 1.6f;
                float v = (70f + 70f * Anim.hash01(h, 2)) * sc;
                float size = (3f + 4f * Anim.hash01(h, 3)) * sc;
                particle(a, an, 1.02f * br, v, size, false, Anim.hash01(h, 4) < 0.5f ? c : Theme.WHITE, 0.55f);
            }
        }
    }

    /** One small bubble {@code age} seconds out: from {@code start} along {@code an}, slowing, floating up, fading. */
    private void particle(float age, float an, float start, float v, float size, boolean filled, Color c, float life) {
        if (age < 0f || age >= life) return;
        float lu = age / life;
        float d = start + v * (1f - (float) Math.exp(-3.2f * age)) / 3.2f;
        float x = cx + MathUtils.cos(an) * d;
        float y = cy + MathUtils.sin(an) * d + 55f * age * age * sc;
        float s = size * (1f - 0.35f * lu);
        float al = 0.95f * (1f - lu * lu);
        if (!(s > 0.3f) || al <= 0.01f) return;
        if (filled) {
            draw.disc(x, y, s, c, 0.85f * al);
            return;
        }
        float h = s / Draw.BUBBLE_R;
        batch.setColor(c.r, c.g, c.b, al);
        batch.draw(puff.region, x - h, y - h, 2f * h, 2f * h);
    }

    /** A miss or a broken STREAM {@code age} seconds ago: the bubble shrinks by .42, sinks a little and fades, grey. */
    private void deflate(float age, float px) {
        if (!(age >= 0f) || age > DEFLATE_SEC) return;
        float u = age / DEFLATE_SEC;
        float v = 1f - u;
        float k = 1f - v * v * v;
        float r = br * (1f - 0.42f * k);
        float y = cy - 18f * k * sc;
        draw.disc(cx, y, r, Theme.MISSED, 0.35f * (1f - k));
        ring(cx, y, r, 3f * sc, Theme.MISSED, 0.9f * (1f - k), px);
    }

    // ================================================================== budget

    /**
     * @return an upper bound of the quads {@link #draw} submits for note i at {@code beat} (0 when it draws nothing),
     *         for the trail budget
     */
    int quads(GameEngine e, int i, Note n, float beat) {
        if (e.chart() == null || beat < n.visibleFrom() || !place(e, n)) return 0;
        int p = pieces(br + (RING_GAP + RING_W) * sc, Ui.worldPerPx());
        // the shell (inside, glow, rings), the glass (3 per piece, rings, glints, diamond), rivets, a STREAM's ring
        // and caps, two doubles lines; effects
        return 12 * p + 3 * p + 2 * RING_PIECES + 2 * BURST_RES + 96;
    }
}
