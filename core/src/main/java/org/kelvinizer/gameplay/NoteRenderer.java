package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 音符渲染器：画 DROP、STREAM、MINE、轨道水雷、TIDE（T4：天键珍珠串、地键滚浪）和潮汐水流，并收集它们的发光。
 * 与别的音符同一拍的音符带同步珍珠（DROP、STREAM 头上三颗金色并排，水雷三颗红色，TIDE 金色珍珠）；轨道水雷的柱子画在条带层，不会盖住另一条判定线。
 *
 * <p>Note renderer (DESIGN §5.8 notes, §5.15 mines and TIDE notes, §19.1 TIDE STREAM, §20.2
 * LANE MINE, §21.6–21.7, and the owner decisions of 2026-10-12).
 *
 * <p>Every note lives in a local frame whose origin is the centre of its leading edge (the edge
 * facing its line) and whose +y points away from the line: rotation is the note's
 * {@code angleAt(beat)}, +180° for sky notes. Lane notes are {@code note_9p} tinted with the line
 * colour; size follows the lane's <em>current</em> width ({@code laneWidth × 5/6 ×
 * min(noteSize, 1.15)} × {@code 40 × noteSize}). STREAM ribbons follow DESIGN §21.7 (see
 * {@link NoteShapes}): sky 56 % of the lane, ground 82 %, fill fading head → tail, a tail cap in
 * the ribbon's colour, and where a ground ribbon overlaps a sky ribbon of the same lane the ground
 * fill leaves a hole under it. Missed notes turn grey, jitter for two frames and fade; broken holds
 * fade to grey. Notes glitch only where the chart asks for it.
 *
 * <p><b>Chart glitches</b> (CHART_FORMAT §6.9, Kelvin 2026-10-14): while a note's glitch segment is
 * active ({@link NoteGlitch#activeAt}), its sprites go to a {@link GlitchCapture} instead of the
 * atlas batch and are replayed cut into sideways-moving strips, with a red and a blue copy and an
 * occasional dim flicker; "reduce flashing" turns the flicker off and halves the pattern rate.
 * Its glows (sync frame, glow) tear along the same strips in the additive pass, without the colour
 * split. A TIDE group shows the glitch of the note that draws its head.
 *
 * <p><b>Sync pearls</b> (owner 2026-10-12 12:33, 12:39 「同一时刻都带」, 12:42 gold; they replace the gold
 * square): a note that shares its beat with any other note ({@link Note#sharesBeat()})
 * carries pearls while it is pending or held ({@link NoteShapes#showsPearl}): three gold pearls
 * {@link NoteShapes#PEARL_D} × noteSize across side by side across the middle of a DROP or STREAM
 * head (16:07, 「不是1个珍珠而是3个珍珠并排」), half a pearl apart, turning with the note at the head's
 * alpha (each a {@code SYNC_PEARL_RIM} rim, the {@code SYNC_PEARL} body, a soft
 * {@code SYNC_PEARL_LUSTRE} lustre and a white highlight toward the upper left of the screen); three
 * red pearls 0.7× as big the same way across a MINE or LANE MINE head; gold pearls instead of white on a sky
 * TIDE's thread, with gold glows; a row of gold pearls, large and small in turn like the sky thread's,
 * halfway up a ground TIDE's wave, riding the crest; and, as a proposal, the same row along the middle
 * of a TIDE STREAM head's bar. A note's own glows leave its pearls out (an octagonal hole each), and so do
 * the TIDE wave and bar glows beneath a note drawn over them (a DROP, STREAM or MINE on a TIDE's beat),
 * so the pearls stay true gold and red.
 *
 * <p>Mines turn with {@code angleAt} like lane notes (owner 2026-10-12 03:03). A LANE MINE (DESIGN §20.2) is
 * drawn in two layers: its body in {@link #drawBands} (beneath trails, lane notes and mines): an
 * inset column of {@code mineCore}, hazard stripes drifting toward the line, rims with studs and
 * a tail cap. The column leaves a gap around the <em>other</em> judgement line, so that line, its
 * ripples and ticks stay as bright as elsewhere (owner 2026-10-12 02:58). While it blocks, a {@code mine}
 * veil covers its lane's part of its own zone (band layer) and its line segment glows (additive).
 * Its head cap is the MINE body, drawn with the notes with the MINE pulse; it jitters on every
 * contact and fades over 120 ms after its end.
 *
 * <p>TIDE drops use the T4 look (owner 2026-10-12 04:36): a string of pearls on the sky line, a rolling wave on
 * the ground line whose crest moves with song time (frozen on pause). TIDE heads draw first in the
 * note pass, so lane notes and mines read on top of them (a joint DROP stays visible on its TIDE's
 * wave or pearls). A TIDE STREAM keeps the TIDE bar of §5.15 as its head (sky heads in the
 * {@code skySig} pearl gradient, {@code tide} diamonds) with a band behind it (§19.1): window-wide
 * (sky inset by {@link NoteShapes#SKY_TIDE_STREAM_INSET}), fill α .14 at the head → .08 at the
 * tail, 2 px rails at α .6 and a 4 px tail cap at α .75 in the band's colour ({@code skyTide} on the
 * sky line, {@code tide} on the ground line), two faint ripple lines scrolling toward the line; in the
 * rows where a ground band overlaps a sky band it draws nothing under the sky band. While held the
 * head pins to the line and the band is α × 1.6; a broken one fades to the missed grey over 200 ms
 * like a hold. {@link #drawBands} draws, in this order, the TIDE STREAM bands, the LANE MINE veils
 * and columns, then every band's tail cap (a cap inside an overlap still shows).
 *
 * <p>{@link #prepare} culls (a suffix minimum of each note's first drawable beat replaces any
 * fixed look-ahead, so notes with long approach events never pop in late) and feeds the approach
 * glows; {@link #draw} draws the normal pass and collects the additive glows that
 * {@link #drawGlows} draws in the additive block. Everything shares the atlas batch: no extra
 * draw call, no allocation after {@link #begin}.
 *
 * <p>A note that spawns beyond the 16:9 frame but whose body would show inside the screen there
 * (with the field stretched to the screen, a turned note's corner on any screen) slides
 * in from the screen edge instead ({@link EdgeEntry}, DESIGN §5.4): its position, pose, culling
 * and window follow the slide, and it does not fade in.
 *
 * <p>Positions are stretched with the field ({@link Field}): lane spans and line y come stretched from the
 * engine, a note's distance from its line and its tail length are multiplied by {@code ky} after the
 * {@link Note#HIDDEN_POSITION} test on the raw value ({@code drawPos}, {@link #tailDraw}); body thickness,
 * pearls, glows and rotation are not stretched.
 *
 * <p>The sprite work of each note type lives in the {@code paint*} methods, which
 * {@link TrailRenderer} also uses for afterimage copies (ARCHITECTURE §17: "same sprites as the
 * live note"); the live-only extras (sync pearls, glows, jitter, the LANE MINE's stripes, studs,
 * veil and line glow, band knock-outs) stay in the {@code draw*} methods.
 */
final class NoteRenderer {
    /** Note body height at note size 1. */
    static final float NOTE_H = 40f;
    /** MINE body height at note size 1.0. */
    static final float MINE_H = 32f;
    private static final int GLOW_MAX = 1024;
    /** Most octagonal holes the glows of one frame leave around sync pearls. */
    private static final int GLOW_HOLE_MAX = 1024;
    /** Most TIDE glows of one frame that leave out the pearls of the notes drawn over them. */
    private static final int COVER_MAX = 64;
    /** An octagon of apothem a cuts a × (2 − √2) off each corner of its a-box. */
    private static final float OCT_CUT = 0.58578644f;
    private static final byte G_NOTE = 0;
    private static final byte G_V = 1;
    private static final byte G_DOT = 2;
    /** Sync highlight halo round a box ({@link #frameGlow}): gw × gh is the box, {@link #gpad} the halo's width. */
    private static final byte G_FRAME = 3;
    /** Solid band round a box ({@link #drawFrame} with the disc's fall-off): the sync highlight's border. */
    private static final byte G_BAND = 4;
    /** Solid disc ({@code disc}): the border round a highlighted sky TIDE pearl. */
    private static final byte G_DISC = 5;
    /** Most ground TIDE crests highlighted in one frame, and most points along them. */
    private static final int CREST_MAX = 32, CREST_PTS = 4096;
    /**
     * Sync highlight (Kelvin 2026-10-13; 12:02 「高亮还可以再明显一点」; 12:05 the border about half as thick as the old
     * high-opacity glow's, which reached 16 beyond the body): the solid border's width beyond the body, the soft
     * halo's width, how far both start inside the body's edge, and their strengths.
     */
    private static final float HL_BAND = 8f, HL_PAD = 40f, HL_INSET = 3f, HL_BAND_A = 0.9f, HL_HALO_A = 0.6f;
    /** A mine's light-red highlight is kept very dim (Kelvin 13:57 「红区的sync弄得很暗就行了」): scales its border and halo. */
    private static final float HL_MINE_K = 0.3f;
    /**
     * Sky notes are white, so their border is a deeper gold, drawn full strength over a weaker halo: added over
     * the dark field it stays gold instead of washing out to pale yellow next to the white body (Kelvin 13:57).
     */
    private static final float HL_SKY_BAND_A = 1f, HL_SKY_HALO_A = 0.3f;
    /** Highlighted sky TIDE pearl: its solid border's and soft halo's width beyond the pearl. */
    private static final float HL_PEARL_BAND = 6f, HL_DOT_PAD = 28f;
    private static final float APPROACH_MS = 250f;
    /** Fade-in time (ms) of a note that appears on screen, counted from its first event. */
    private static final float FADE_IN_MS = 150f;
    /** Longest hold tail drawn (world units): more than any visible world height plus the line offset. */
    static final float MAX_TAIL_DRAW = 4000f;
    /** TIDE bar and band inset from the window ends. */
    static final float TIDE_INSET = 12f;
    /** Fade of a missed note (ms, DESIGN §5.8). */
    private static final float MISS_FADE_MS = 200f;
    /** Grey-out of a missed DROP or TIDE drop (ms). */
    private static final float MISS_GREY_MS = 60f;
    /** Jitter time of a missed note, a touched mine and a LANE MINE contact (two frames). */
    private static final float JITTER_MS = 34f;
    /** Jitter offset. */
    private static final float JITTER = 3f;
    /** Corner radius of a MINE body. */
    private static final float MINE_RADIUS = 8f;
    /** Corner size of the {@code chip_9p} nine-slice in texels (its corner radius). */
    private static final float CHIP_SPLIT = 12f;
    /** Radius of the {@code disc} sprite as a share of its size ({@code Draw.disc}). */
    static final float DISC_R = 0.484f;
    private static final Color TIDE_LEAD = new Color(0x8CFFD9B3);  // tide @ .70
    private static final Color TIDE_TRAIL = new Color(0x8CFFD94D); // tide @ .30
    /** Sky TIDE STREAM head at its leading edge: pearl bright (DESIGN §21.6). */
    private static final Color SKY_TIDE_LEAD = alpha(Theme.SKY_TIDE_HEAD_BRIGHT, 0.85f);
    /** Sky TIDE STREAM head at its trailing edge: pearl dim. */
    private static final Color SKY_TIDE_TRAIL = alpha(Theme.SKY_TIDE_HEAD_DIM, 0.60f);
    /** Most sky STREAM ribbons and sky TIDE STREAM bands collected per frame for the knock-outs. */
    private static final int MAX_SKY = 64;

    private final Draw draw;
    /** The shared atlas batch. */
    private final Batch atlas;
    /** Where note sprites go: {@link #atlas}, or {@link #glitch} while a glitching note draws. */
    private Batch batch;
    private final GlitchCapture glitch = new GlitchCapture();
    private int glitchSeed;
    private float glitchIntensity;
    private float glitchSplit;
    private float glitchSlice;
    private float glitchFlicker;
    private int glitchQuads;
    private int glitchedNotes;
    /** First glow the glitching note added (its glows replay with its glitch in {@link #drawGlows}). */
    private int glowMark;
    private final NinePatch nine;
    private final TextureRegion core;
    private final TextureRegion edgeL;
    private final TextureRegion edgeR;
    private final TextureRegion chip;
    private final TextureRegion disc;
    private final TextureRegion dot;
    private final Color col = new Color();
    private final Color col2 = new Color();
    private final float[] gradientQuad = new float[20];
    private final Color col3 = new Color();
    private final float[] bandQuad = new float[20];
    /** Rotated nine-slice and polygon quads. */
    private final float[] verts = new float[9 * 20];
    private final float coreU;
    private final float coreV;

    private Note[] notes = new Note[0];
    private int count;
    private float[] suffixVis = new float[0];
    private float[] spawnPos = new float[0];
    /** The chart being drawn, for beat spans in ms (its tempo may change). */
    private Chart timing;
    /** Visual beat of the frame being prepared / drawn (for the fade-in). */
    private float fadeBeat;
    /** Notes that slide in from the screen edge (DESIGN §5.4). */
    private final EdgeEntry entry = new EdgeEntry();
    private int start;
    private int end;
    private float noteSize = 1f;
    private boolean high;
    /** How notes that share their beat are marked ({@link Settings#syncHint}). */
    private int syncHint = Settings.SYNC_HINT_HIGHLIGHT;
    /**
     * soft_dot's and disc's fall-off from the centre up (one texel wide) and their upper-right quarters: the soft
     * halos and the solid borders of the sync highlight.
     */
    private TextureRegion dotSlice, dotQuarter, discSlice, discQuarter;

    private final float[] gx = new float[GLOW_MAX];
    private final float[] gy = new float[GLOW_MAX];
    private final float[] gw = new float[GLOW_MAX];
    private final float[] gh = new float[GLOW_MAX];
    private final float[] grot = new float[GLOW_MAX];
    private final float[] gcol = new float[GLOW_MAX];
    /** Halo width of a {@link #G_FRAME} glow. */
    private final float[] gpad = new float[GLOW_MAX];
    /** Highlighted crests ({@link #crestGlow}): first point, point count, packed colour, width and kind of each. */
    private final boolean[] crestBand = new boolean[CREST_MAX];
    private final int[] crestAt = new int[CREST_MAX];
    private final int[] crestN = new int[CREST_MAX];
    private final float[] crestCol = new float[CREST_MAX];
    private final float[] crestPad = new float[CREST_MAX];
    private final float[] crestNx = new float[CREST_MAX];
    private final float[] crestNy = new float[CREST_MAX];
    private final float[] cpx = new float[CREST_PTS];
    private final float[] cpy = new float[CREST_PTS];

    // a TIDE drawn turned about its pivot, between tideTurn and tideTurnEnd (owner 2026-10-16)
    private boolean tideTurned;
    private float ttDeg;
    private float ttCos;
    private float ttSin;
    private float ttPx;
    private float ttPy;
    /** Span map of the turned TIDE: window x is drawn at local {@code tmA + x·tmK} ({@link #tideX}). */
    private float tmA;
    private float tmK = 1f;
    private final Matrix4 ttSaved = new Matrix4();
    private final Matrix4 ttTurn = new Matrix4();
    private int crests;
    private int crestPts;
    /** First hole of each glow in {@link #hlx} / {@link #hly} / {@link #hla}. */
    private final int[] gholeAt = new int[GLOW_MAX];
    /** Holes of each glow (the sync pearls it leaves out), 0 for a plain one-quad glow. */
    private final int[] gholes = new int[GLOW_MAX];
    /** Glow holes: centre in the glow's own frame (from its centre) and apothem. */
    private final float[] hlx = new float[GLOW_HOLE_MAX];
    private final float[] hly = new float[GLOW_HOLE_MAX];
    private final float[] hla = new float[GLOW_HOLE_MAX];
    private int glowHoles;
    /** Spans beside a knock-out ({@link NoteShapes#spansBeside}). */
    private final float[] span = new float[4];
    /** Scratch quad of a glow with a hole. */
    private final float[] glowQuad = new float[20];
    private final byte[] greg = new byte[GLOW_MAX];
    // the glitch of the note that added each glow (gglitch false: none); see glitchEnd()
    private final boolean[] gglitch = new boolean[GLOW_MAX];
    private final int[] gseed = new int[GLOW_MAX];
    private final float[] gfx = new float[GLOW_MAX * 6];
    private int glows;
    /** Glows (indices) that leave out the pearls drawn over them later in the frame ({@link #coverHoles}). */
    private final int[] cover = new int[COVER_MAX];
    private int covers;

    // scratch geometry of the last computed note (see geometry())
    private float sx;
    private float sy;
    private float sw;
    private float sh;
    private float srot;
    private float spos;
    // the local frame of the last painted note (see frame())
    private float fcos = 1f;
    private float fsin;

    // knock-outs of the body being painted (0 holes for afterimage copies)
    private int holes;
    private final float[] holeLo = new float[NoteShapes.MAX_HOLES];
    private final float[] holeHi = new float[NoteShapes.MAX_HOLES];
    private float holeX0;
    private float holeX1;
    private final float[] segLo = new float[2 * NoteShapes.MAX_HOLES + 1];
    private final float[] segHi = new float[2 * NoteShapes.MAX_HOLES + 1];
    private final boolean[] segHole = new boolean[2 * NoteShapes.MAX_HOLES + 1];
    private int segs;
    private final float[] ov = new float[2];

    // sky bodies of this frame (world rows), for the ground knock-outs
    private int skyRibs;
    private final int[] skyRibLane = new int[MAX_SKY];
    private final float[] skyRibLo = new float[MAX_SKY];
    private final float[] skyRibHi = new float[MAX_SKY];
    private final float[] skyRibCx = new float[MAX_SKY];
    private final float[] skyRibW = new float[MAX_SKY];
    private int skyBands;
    private final float[] skyBandLo = new float[MAX_SKY];
    private final float[] skyBandHi = new float[MAX_SKY];

    // rotated nine-slice grid and the LANE MINE polygons (local and world stages)
    private final float[] gxs = new float[4];
    private final float[] gys = new float[4];
    private final float[] gus = new float[4];
    private final float[] gvs = new float[4];
    private final float[] pa = new float[16];
    private final float[] pb = new float[16];
    private final float[] pc = new float[16];
    private final float[] pd = new float[16];
    private float gapLo = Float.NaN;
    private float gapHi = Float.NaN;
    private float visLo;
    private float visHi;

    /** Creates the renderer over the shared atlas. */
    NoteRenderer(Draw draw) {
        this.draw = draw;
        this.atlas = draw.batch();
        this.batch = atlas;
        this.nine = draw.note9();
        core = new TextureRegion(draw.white(), 1, 1, 2, 2);
        edgeL = new TextureRegion(draw.holdEdge());
        edgeR = new TextureRegion(draw.holdEdge());
        edgeR.flip(true, false);
        chip = draw.region(Draw.CHIP_9P);
        disc = draw.disc();
        dot = draw.softDot();
        coreU = 0.5f * (core.getU() + core.getU2());
        coreV = 0.5f * (core.getV() + core.getV2());
    }

    private static Color alpha(Color c, float a) {
        return new Color(c.r, c.g, c.b, a);
    }

    /**
     * Prepares a chart: culling tables, spawn positions and the edge entries for the current
     * visible extent (load time, may allocate).
     */
    void begin(Chart chart) {
        notes = chart.notes();
        count = notes.length;
        timing = chart;
        if (suffixVis.length < count) {
            suffixVis = new float[count];
            spawnPos = new float[count];
        }
        for (int i = count - 1; i >= 0; i--) {
            Note n = notes[i];
            spawnPos[i] = n.positionAt(n.visibleFrom());
        }
        entry.begin(chart);
        entry.update(Ui.bottom(), Ui.top(), Ui.worldW(), noteSize);
        windowTable();
        start = 0;
        end = 0;
        glows = 0;
        crests = 0;
        crestPts = 0;
    }

    /** Culling table: suffix minimum of each note's first drawable beat ({@link EdgeEntry#drawFrom}). */
    private void windowTable() {
        float min = Float.POSITIVE_INFINITY;
        for (int i = count - 1; i >= 0; i--) {
            min = Math.min(min, entry.drawFrom(i));
            suffixVis[i] = min;
        }
    }

    /** @return the edge entries of the chart in play (trails follow them; the screen starts the run before them). */
    EdgeEntry entry() {
        return entry;
    }

    /** Sets the note size setting (0.8–1.3) and the effect quality for this frame. */
    void setStyle(float noteSizeScale, boolean highQuality) {
        noteSize = MathUtils.clamp(noteSizeScale, 0.8f, 1.3f);
        high = highQuality;
    }

    /**
     * Sets how notes that share their beat are marked (Kelvin 2026-10-13): {@link Settings#SYNC_HINT_HIGHLIGHT} a
     * border and halo (white; deep gold on sky notes; light red on mines; round each pearl of a sky TIDE, along the
     * crest of a ground TIDE),
     * {@link Settings#SYNC_HINT_PEARLS} the halo and the sync pearls, {@link Settings#SYNC_HINT_OFF} neither.
     */
    void setSyncHint(int mode) {
        syncHint = mode;
    }

    /** @return true when a note that shares its beat gets the halo. */
    private boolean syncHalo() {
        return syncHint != Settings.SYNC_HINT_OFF;
    }

    /** @return true when a note that shares its beat gets its sync pearls. */
    private boolean syncPearls() {
        return syncHint == Settings.SYNC_HINT_PEARLS;
    }

    /** @return note body height in world units for the current size setting. */
    float noteHeight() {
        return NOTE_H * noteSize;
    }

    /** @return the note size setting of this frame (0.8–1.3). */
    float noteSize() {
        return noteSize;
    }

    /** @return head width of a lane note in a lane {@code laneWidth} wide (DESIGN §5.8 size rule). */
    float headWidth(float laneWidth) {
        return laneWidth * 5f / 6f * Math.min(noteSize, 1.15f);
    }

    /** @return the batch the notes draw with (the shared atlas batch). */
    Batch batch() {
        return atlas;
    }

    /**
     * Starts recording note i's sprites when one of its glitch segments is active at
     * {@code beat} with some intensity (CHART_FORMAT §6.9).
     *
     * @return true when the note glitches; then {@link #glitchEnd()} must follow its drawing
     */
    private boolean glitchBegin(int i, Note n, float beat) {
        if (n.glitches().isEmpty()) return false;
        NoteGlitch g = NoteGlitch.activeAt(n.glitches(), beat);
        if (g == null) return false;
        float intensity = g.intensityAt(beat);
        if (!(intensity > 0.001f)) return false;
        boolean reduce = GlitchText.reduceFlashing();
        glitchSeed = GlitchCapture.seed(i, g.step(beat, reduce ? 0.5f : 1f), Float.floatToIntBits(g.from()));
        glitchIntensity = intensity;
        glitchSplit = g.split();
        glitchSlice = g.slice();
        glitchFlicker = reduce ? 0f : g.flicker();
        glitch.begin(atlas);
        batch = glitch;
        glowMark = glows;
        return true;
    }

    /** Replays the recorded note with its glitch into the atlas batch. */
    private void glitchEnd() {
        batch = atlas;
        boolean drew = glitch.recorded() > 0;
        if (drew) glitchedNotes++;
        glitchQuads += glitch.emit(glitchSeed, glitchIntensity, glitchSplit, glitchSlice, glitchFlicker);
        if (!drew) return;
        // its glows (sync frame, glow) tear along the same strips when the additive pass draws them
        for (int k = glowMark; k < glows; k++) {
            gglitch[k] = true;
            gseed[k] = glitchSeed;
            int o = k * 6;
            gfx[o] = glitchIntensity;
            gfx[o + 1] = glitchSplit;
            gfx[o + 2] = glitchSlice;
            gfx[o + 3] = glitchFlicker;
            gfx[o + 4] = glitch.boxLo();
            gfx[o + 5] = glitch.boxHi();
        }
    }

    /** @return notes drawn glitched since the last {@link #drawBands} (diagnostics). */
    int glitchedNotes() {
        return glitchedNotes;
    }

    /** @return quads the glitched notes submitted since the last {@link #drawBands} (diagnostics). */
    int glitchQuads() {
        return glitchQuads;
    }

    /** {@code Draw.rect} through the note batch (so a glitching note records it). */
    private void rect(float x, float y, float w, float h, Color c, float alpha) {
        float old = batch.getPackedColor();
        batch.setColor(c.r, c.g, c.b, c.a * alpha);
        batch.draw(core, x, y, w, h);
        batch.setPackedColor(old);
    }

    /** {@code Draw.rectGradientV} through the note batch: a vertical gradient from {@code bottom} to {@code top}. */
    private void rectGradientV(float x, float y, float w, float h, Color bottom, Color top, float alpha) {
        float cb = Color.toFloatBits(bottom.r, bottom.g, bottom.b, bottom.a * alpha);
        float ct = Color.toFloatBits(top.r, top.g, top.b, top.a * alpha);
        float[] q = gradientQuad;
        q[0] = x; q[1] = y; q[2] = cb; q[3] = coreU; q[4] = coreV;
        q[5] = x; q[6] = y + h; q[7] = ct; q[8] = coreU; q[9] = coreV;
        q[10] = x + w; q[11] = y + h; q[12] = ct; q[13] = coreU; q[14] = coreV;
        q[15] = x + w; q[16] = y; q[17] = cb; q[18] = coreU; q[19] = coreV;
        batch.draw(core.getTexture(), q, 0, 20);
    }

    /**
     * Advances the culling window and feeds the approach glows of the next 250 ms (DESIGN §5.7
     * approach glow, §5.15 mine warning and TIDE approach) when {@code approach} is on.
     */
    void prepare(GameEngine e, FieldRenderer field, boolean approach) {
        double vis = e.visualMs();
        float beat = e.visualBeat();
        fadeBeat = beat;
        if (entry.update(Ui.bottom(), Ui.top(), Ui.worldW(), noteSize)) windowTable(); // a resize, a new note size
        if (start > count) start = 0;
        while (start < count && done(e, start, vis)) start++;
        end = start;
        while (end < count && suffixVis[end] <= beat) end++;
        if (!approach) return;
        for (int i = start; i < end; i++) {
            if (e.noteState(i) != GameEngine.STATE_PENDING) continue;
            Note n = notes[i];
            double dt = n.judgementMs() - vis;
            if (dt <= 0.0 || dt > APPROACH_MS) continue;
            float k = 1f - (float) dt / APPROACH_MS;
            if (n.type() == NoteType.UNIVERSAL) field.tideApproach(n.line(), k);
            else if (n.type() == NoteType.MINE) field.approach(n.line(), n.laneIndex(), 0.20f * k, true);
            else field.approach(n.line(), n.laneIndex(), 0.25f * k, false);
        }
    }

    /**
     * Quads {@link #draw} adds beyond its paint to a pending DROP, STREAM, MINE or LANE MINE head
     * that shares its beat: its {@link NoteShapes#HEAD_PEARLS} sync pearls (each a rim, body, lustre and highlight).
     */
    static final int SYNC_QUADS = NoteShapes.HEAD_PEARLS * NoteShapes.PEARL_QUADS;

    /** Below this opacity a note (or an afterimage copy) is not drawn. */
    static final float MIN_ALPHA = 0.004f;
    /** Cull margin of a lane note: beyond its far end (rotation) and toward its line. */
    static final float LANE_CULL = 60f;
    /** Cull margin of a MINE or TIDE bar toward its line. */
    static final float FLAT_CULL = 20f;

    /**
     * Upper bound of the quads {@link #drawBands} and {@link #draw} submit this frame (call after
     * {@link #prepare} and {@link #setStyle}): every note of the prepared window that they do not
     * cull (gone, hidden at {@link Note#HIDDEN_POSITION}, in an inactive lane, off screen with the
     * draw margins, or transparent by its events and fade-in), at its type's quad bound; a LANE
     * MINE's body is counted exactly (a dry run of its column). A note that is in the window only
     * because an early event made it "visible" is therefore not counted while it is parked off
     * screen. The trail budget keeps the copies within the room the shared batch has left, so
     * trails never force an extra flush (ARCHITECTURE §17). No allocation.
     */
    int quadsBound(GameEngine e) {
        float beat = e.visualBeat();
        LaneLayout lay = e.layout();
        int lanes = lay.count();
        int pattern = lay.patternLanes();
        int q = 0;
        int groundHolds = 0;
        int skyHolds = 0;
        int groundBands = 0;
        int skyBandCount = 0;
        for (int i = start; i < end; i++) {
            Note n = notes[i];
            if (entry.drawFrom(i) > beat) continue;
            float lb = entry.look(i, beat);
            byte st = e.noteState(i);
            boolean up = n.isUpper();
            int nq;
            float far;
            float near;
            boolean holdBody = false;
            boolean bandBody = false;
            switch (n.type()) {
                case MINE: {
                    int lane = n.laneIndex();
                    if (lane < 0 || lane >= lanes || !lay.active(lane)) continue;
                    if (n.isLaneMine()) q += laneMineBody(e, i, n, beat, 0, true);
                    nq = MINE_QUADS + (NoteShapes.showsPearl(n, st) ? SYNC_QUADS : 0);
                    float pad = 0.5f * lay.width(lane) * GameEngine.MINE_BOX; // any rotation
                    far = mineHeight() + pad;
                    // as drawMine: a body turned toward its line also reaches its height
                    near = FLAT_CULL + pad + turnReach(up, n.angleAt(lb) + (up ? 180f : 0f), 0f, mineHeight());
                    break;
                }
                case UNIVERSAL: {
                    if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
                    boolean stream = n.isTideStream();
                    boolean bar = tideBar(i, n, st);
                    if (!bar && !stream) continue;
                    if (lay.windowRight() - lay.windowLeft() <= 2f * TIDE_INSET) continue;
                    float tail = stream ? tailDraw(n, lb) : 0f;
                    if (stream && st != GameEngine.STATE_PENDING && tail <= 0f && beat > n.endBeat()) continue;
                    bandBody = stream && tail > 0f;
                    nq = (bar ? (stream ? tideQuads(lanes) : NoteShapes.t4Quads(pattern, up)) : 0)
                        + (bar && NoteShapes.showsPearl(n, st)
                            ? (stream ? NoteShapes.barPearlQuads(pattern) : NoteShapes.t4PearlQuads(pattern, up)) : 0)
                        + (bandBody ? BAND_QUADS : 0);
                    if (nq == 0) continue;
                    far = Math.max(tideReach(stream), tail);
                    near = FLAT_CULL;
                    break;
                }
                default: {
                    if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
                    int lane = n.laneIndex();
                    if (lane < 0 || lane >= lanes || lay.width(lane) < LaneLayout.MIN_ACTIVE_WIDTH) continue;
                    boolean hold = n.type() == NoteType.HOLD;
                    float tail = hold ? tailDraw(n, lb) : 0f;
                    if (hold && st != GameEngine.STATE_PENDING && tail <= 0f && beat > n.endBeat()) continue;
                    holdBody = hold && tail > 0f;
                    nq = (hold ? HOLD_QUADS : TAP_QUADS) + (NoteShapes.showsPearl(n, st) ? SYNC_QUADS : 0);
                    far = noteHeight() + tail + LANE_CULL;
                    near = LANE_CULL;
                    if (entry.entering(i)) { // as drawLaneNote: a slide keeps what its turned body reaches
                        float rot = n.angleAt(lb) + (up ? 180f : 0f);
                        float w = headWidth(lay.width(lane));
                        far += rotPad(rot, w);
                        near += turnReach(up, rot, w, noteHeight() + tail);
                    }
                    break;
                }
            }
            float pos = drawPos(i, n, beat);
            if (Float.isNaN(pos)) continue;
            float lineY = e.lineY(n.line());
            float y = up ? lineY - pos : lineY + pos;
            if (n.type() != NoteType.UNIVERSAL) { // where drawLaneNote and drawMine put it
                boolean mine = n.type() == NoteType.MINE;
                y += NoteShapes.pivotShiftY(pos, mine ? MINE_H : NOTE_H, mine ? mineHeight() : noteHeight(),
                    n.angleAt(lb) + (up ? 180f : 0f), up, n.pivotX(), n.pivotY());
            }
            // as drawTide and drawBand: a turned TIDE is not culled
            boolean turnedTide = n.type() == NoteType.UNIVERSAL && NoteShapes.turned(n.angleAt(lb));
            if (!turnedTide && offScreen(up, y, far, near)) continue;
            if (n.shownOpacityAt(lb) * fadeIn(i, lineY, up) <= MIN_ALPHA) continue;
            q += nq;
            if (holdBody) {
                if (up) skyHolds++;
                else groundHolds++;
            }
            if (bandBody) {
                if (up) skyBandCount++;
                else groundBands++;
            }
        }
        // knock-outs: every ground body may be split around up to MAX_HOLES sky bodies
        q += groundHolds * RIBBON_HOLE_QUADS * Math.min(skyHolds, NoteShapes.MAX_HOLES);
        q += groundBands * BAND_HOLE_QUADS * Math.min(skyBandCount, NoteShapes.MAX_HOLES);
        return q;
    }

    /**
     * @return true when a note whose leading edge is at {@code y} lies entirely off screen: it
     *         reaches {@code far} away from its line and {@code near} toward it (the draw cull)
     */
    static boolean offScreen(boolean up, float y, float far, float near) {
        return up ? (y - far > Ui.top() || y + near < Ui.bottom()) : (y + far < Ui.bottom() || y - near > Ui.top());
    }

    /** @return extra cull margin of a body {@code w} wide turned by {@code rot} degrees. */
    static float rotPad(float rot, float w) {
        return Math.abs(MathUtils.sinDeg(rot)) * w * 0.5f;
    }

    /**
     * @return how far a lane-note body {@code w} wide and {@code len} long (head and tail), drawn
     *         turned by {@code rot} (a sky note's 180° included), reaches toward its line beyond
     *         its leading edge: its corners when turned sideways, its length when turned toward
     *         the line
     */
    static float turnReach(boolean up, float rot, float w, float len) {
        float back = up ? MathUtils.cosDeg(rot) : -MathUtils.cosDeg(rot);
        return rotPad(rot, w) + Math.max(0f, back) * len;
    }

    /** @return MINE body height for the current size setting. */
    float mineHeight() {
        return MINE_H * noteSize;
    }

    /** @return TIDE bar thickness for the current size setting. */
    float tideHeight() {
        return 18f * noteSize;
    }

    /** @return how far a TIDE head reaches behind its leading edge: the bar, or the T4 shape of a drop. */
    float tideReach(boolean stream) {
        return stream ? tideHeight() : NoteShapes.T4_REACH * noteSize;
    }

    /**
     * @return true when TIDE note i draws a bar in state {@code st}: a pending group (same line
     *         and beat) draws one bar (its first member); a TIDE STREAM that runs or broke draws
     *         its own (pinned) bar; a missed drop group keeps its one bar
     */
    private boolean tideBar(int i, Note n, byte st) {
        if (st == GameEngine.STATE_PENDING) return tideHead(i, n);
        return n.isTideStream() || tideHead(i, n);
    }

    /** @return true when TIDE note i heads its group (same line and beat). */
    private boolean tideHead(int i, Note n) {
        if (i == 0) return true;
        Note p = notes[i - 1];
        return !(p.type() == NoteType.UNIVERSAL && p.line() == n.line() && p.judgement() == n.judgement());
    }

    private boolean done(GameEngine e, int i, double vis) {
        byte st = e.noteState(i);
        if (st == GameEngine.STATE_PENDING || st == GameEngine.STATE_HOLDING) return false;
        float at = e.noteResolvedMs(i);
        Note n = notes[i];
        return !Float.isNaN(at) && vis > at + 500.0 && vis > n.endMs() + 500.0;
    }

    // ================================================================== normal pass

    /**
     * Draws the notes in the normal blend (block B): the TIDE heads, then the lane notes and mines,
     * each nearest note on top, and collects the additive glows. The batch colour is restored to
     * white.
     */
    void draw(GameEngine e, float now) {
        glows = 0;
        glowHoles = 0;
        covers = 0;
        crests = 0;
        crestPts = 0;
        float beat = e.visualBeat();
        fadeBeat = beat;
        int frame = (int) (now * 60f);
        collectSkyRibbons(e, beat);
        // TIDE heads first (T4 drops, TIDE STREAM bars): lane notes and mines read on top of them
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (n.type() != NoteType.UNIVERSAL || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(i, n, beat);
            drawTide(e, i, n, beat, frame);
            if (g) glitchEnd();
            tideTurnEnd();
        }
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (entry.drawFrom(i) > beat || n.type() == NoteType.UNIVERSAL) continue;
            boolean g = glitchBegin(i, n, beat);
            if (n.type() == NoteType.MINE) drawMine(e, i, n, beat, now, frame);
            else drawLaneNote(e, i, n, beat, now, frame);
            if (g) glitchEnd();
        }
        batch.setColor(Color.WHITE);
    }

    /**
     * Raw position of a note at {@code beat}: taps, mines and TIDE drops slide on past the line
     * after their judgement ({@link Note#positionPastJudgement}); STREAMs, TIDE STREAMs and LANE
     * MINEs stop at it ({@link Note#isSustained}).
     */
    static float position(Note n, float beat) {
        if (n.isSustained()) {
            float p = n.positionAt(beat);
            return beat > n.judgement() ? Math.max(0f, p) : p;
        }
        return beat > n.judgement() ? n.positionPastJudgement(beat) : n.positionAt(beat);
    }

    /**
     * World distance of note i from its line at {@code beat} ({@link EdgeEntry#position} stretched by
     * {@link Field#dist}), or NaN while it is parked at {@link Note#HIDDEN_POSITION} (tested on the raw value).
     */
    private float drawPos(int i, Note n, float beat) {
        float p = entry.position(i, n, beat);
        return Math.abs(p) >= Note.HIDDEN_POSITION - 1f ? Float.NaN : Field.dist(p);
    }

    /** {@link #drawPos(int, Note, float)} by the note's chart index. */
    private float drawPos(Note n, float beat) {
        float p = entry.position(n, beat);
        return Math.abs(p) >= Note.HIDDEN_POSITION - 1f ? Float.NaN : Field.dist(p);
    }

    /**
     * @return the drawn length (world) of a note's hold ribbon, TIDE STREAM band or LANE MINE column at the look
     *         beat {@code lb}: its tail stretched by {@link Field#dist}, at most {@link #MAX_TAIL_DRAW}
     */
    static float tailDraw(Note n, float lb) {
        return Math.min(Field.dist(n.tailHeightAt(lb)), MAX_TAIL_DRAW);
    }

    /**
     * Fade-in factor over the first 150 ms after the note's first event when its spawn point is on
     * screen; 1 for a note that slides in from the screen edge.
     */
    private float fadeIn(int i, float lineY, boolean up) {
        if (entry.entering(i)) return 1f;
        return fadeInFrom(spawnPos[i], (float) timing.msBetween(notes[i].visibleFrom(), fadeBeat), lineY, up);
    }

    /**
     * Fade-in factor (DESIGN §5.4) for a note spawning at raw distance {@code sp} (reference units)
     * from a line at {@code lineY}, {@code sinceMs} after its first event: 1 unless the spawn point is
     * on screen; else over 150 ms. Time, not travel, so a note that hovers where it appears, or moves
     * away from its line, still shows.
     */
    static float fadeInFrom(float sp, float sinceMs, float lineY, boolean up) {
        if (!(sp > 0f) || sp >= Note.HIDDEN_POSITION - 1f) return 1f;
        float spw = Field.dist(sp);
        float spawnY = up ? lineY - spw : lineY + spw;
        if (spawnY <= Ui.bottom() || spawnY >= Ui.top()) return 1f;
        return MathUtils.clamp(sinceMs / FADE_IN_MS, 0f, 1f);
    }

    /**
     * Computes the geometry of lane note i into the scratch fields: {@code sx, sy} leading-edge
     * centre, {@code sw × sh} body, {@code srot} rotation, {@code spos} position.
     *
     * @return false when it is not drawable (hidden, inactive lane)
     */
    private boolean geometry(GameEngine e, Note n, float beat) {
        LaneLayout lay = e.layout();
        int lane = n.laneIndex();
        if (lane < 0 || lane >= lay.count()) return false;
        float lw = lay.width(lane);
        if (lw < LaneLayout.MIN_ACTIVE_WIDTH) return false;
        float pos = drawPos(n, beat);
        if (Float.isNaN(pos)) return false;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        spos = pos;
        sx = lay.centre(lane);
        sy = up ? lineY - pos : lineY + pos;
        sw = lw * 5f / 6f * Math.min(noteSize, 1.15f);
        sh = NOTE_H * noteSize;
        srot = n.angleAt(entry.look(n, beat)) + (up ? 180f : 0f);
        sx += NoteShapes.pivotShiftX(pos, NOTE_H, sh, srot, up, n.pivotX(), n.pivotY());
        sy += NoteShapes.pivotShiftY(pos, NOTE_H, sh, srot, up, n.pivotX(), n.pivotY());
        return true;
    }

    /**
     * Collects the sky STREAM ribbons this frame draws upright (world rows, lane, centre, width),
     * for the ground ribbons' knock-out (DESIGN §21.7). A turned ribbon knocks nothing out.
     */
    private void collectSkyRibbons(GameEngine e, float beat) {
        skyRibs = 0;
        LaneLayout lay = e.layout();
        for (int i = start; i < end && skyRibs < MAX_SKY; i++) {
            Note n = notes[i];
            if (n.type() != NoteType.HOLD || !n.isUpper() || entry.drawFrom(i) > beat) continue;
            byte st = e.noteState(i);
            if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
            int lane = n.laneIndex();
            if (lane < 0 || lane >= lay.count() || lay.width(lane) < LaneLayout.MIN_ACTIVE_WIDTH) continue;
            float lb = entry.look(i, beat);
            float tail = tailDraw(n, lb);
            if (!(tail > 0f)) continue;
            float pos = drawPos(i, n, beat);
            if (Float.isNaN(pos)) continue;
            float rot = n.angleAt(lb) + 180f;
            if (Math.abs(MathUtils.sinDeg(rot)) > NoteShapes.UPRIGHT_SIN) continue;
            float lineY = e.lineY(n.line());
            if (n.shownOpacityAt(lb) * fadeIn(i, lineY, true) <= MIN_ALPHA) continue;
            float y = lineY - pos + NoteShapes.pivotShiftY(pos, NOTE_H, noteHeight(), rot, true, n.pivotX(), n.pivotY());
            float y2 = y + MathUtils.cosDeg(rot) * tail;
            int k = skyRibs++;
            skyRibLane[k] = lane;
            skyRibLo[k] = Math.min(y, y2);
            skyRibHi[k] = Math.max(y, y2);
            skyRibCx[k] = lay.centre(lane) + NoteShapes.pivotShiftX(pos, NOTE_H, noteHeight(), rot, true, n.pivotX(),
                n.pivotY());
            skyRibW[k] = NoteShapes.ribbonWidth(lay.width(lane), true);
        }
    }

    /**
     * Sets the knock-outs of a ground ribbon (leading edge {@code (x, y)}, turned {@code rot},
     * {@code tail} long): the rows where a sky ribbon of its lane overlaps it, and the hole's
     * local x span (the sky ribbon plus {@link NoteShapes#SKY_HOLE_PAD} on each side).
     */
    private void ribbonHoles(Note n, float x, float y, float rot, float tail) {
        holes = 0;
        if (skyRibs == 0 || !(tail > 0f)) return;
        if (Math.abs(MathUtils.sinDeg(rot)) > NoteShapes.UPRIGHT_SIN) return;
        float dir = MathUtils.cosDeg(rot) > 0f ? 1f : -1f;
        int lane = n.laneIndex();
        for (int k = 0; k < skyRibs && holes < NoteShapes.MAX_HOLES; k++) {
            if (skyRibLane[k] != lane) continue;
            if (!NoteShapes.overlapRows(y, dir, tail, skyRibLo[k], skyRibHi[k], ov)) continue;
            holeLo[holes] = ov[0];
            holeHi[holes] = ov[1];
            holes++;
            float half = NoteShapes.holeHalf(skyRibW[k], NoteShapes.SKY_HOLE_PAD);
            float cx = (skyRibCx[k] - x) * dir; // local x of the sky ribbon's centre
            holeX0 = cx - half;
            holeX1 = cx + half;
        }
    }

    private void drawLaneNote(GameEngine e, int i, Note n, float beat, float now, int frame) {
        byte st = e.noteState(i);
        if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) return;
        if (!geometry(e, n, beat)) return;
        boolean up = n.isUpper();
        boolean hold = n.type() == NoteType.HOLD;
        float lineY = e.lineY(n.line());
        float lb = entry.look(i, beat);
        // Tails longer than any screen (Abyss has 100000-unit holds) are drawn screen-sized.
        float tail = hold ? tailDraw(n, lb) : 0f;
        if (hold && st != GameEngine.STATE_PENDING && tail <= 0f && beat > n.endBeat()) return;
        // cull (generous margin for rotation); a note sliding in from the screen edge also keeps
        // what its turned body reaches, as its slide starts beyond that
        float far = sh + tail + LANE_CULL;
        float near = LANE_CULL;
        if (entry.entering(i)) {
            far += rotPad(srot, sw);
            near += turnReach(up, srot, sw, sh + tail);
        }
        if (offScreen(up, sy, far, near)) return;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up);
        if (alpha <= MIN_ALPHA) return;
        Color base = up ? Theme.SKY_SIG : Theme.GROUND_SIG;
        float x = sx;
        float headMul = 1f;
        boolean heldNow = hold && e.isHoldHeld(i);
        float ribHead = NoteShapes.ribbonHeadAlpha(heldNow);
        float ribTail = NoteShapes.ribbonTailAlpha(heldNow);
        boolean lost = st == GameEngine.STATE_LOST;
        if (lost) {
            float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
            if (Float.isNaN(age)) age = 0f;
            if (hold) {
                float k = MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
                Theme.mix(base, Theme.MISSED, k, col);
                headMul = 1f - 0.70f * k;
                ribHead += (NoteShapes.RIBBON_BROKEN_HEAD_A - ribHead) * k;
                ribTail += (NoteShapes.RIBBON_BROKEN_TAIL_A - ribTail) * k;
            } else {
                Theme.mix(base, Theme.MISSED, MathUtils.clamp(age / MISS_GREY_MS, 0f, 1f), col);
                alpha *= 1f - MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
                if (alpha <= MIN_ALPHA) return;
            }
            if (age < JITTER_MS) x += Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
        } else {
            col.set(base);
        }
        float rot = srot;
        float y = sy;
        float w = sw;
        float h = sh;
        float rw = hold ? NoteShapes.ribbonWidth(e.layout().width(n.laneIndex()), up) : 0f;
        if (hold && !up) ribbonHoles(n, x, y, rot, tail);
        paintLaneNote(x, y, w, h, rot, col, alpha, headMul, ribHead, ribTail, tail, rw, NoteShapes.ribbonEdge(rw, up),
            lost);
        holes = 0;
        float cos = fcos;
        float sin = fsin;
        float a = alpha * headMul;
        boolean sync = NoteShapes.showsPearl(n, st);
        boolean pearl = sync && syncPearls();
        if (pearl) syncPearl(x, y, w, h, a);
        // glows (additive block)
        float gcx = x - sin * h * 0.5f;
        float gcy = y + cos * h * 0.5f;
        if (sync && syncHalo()) syncFrame(gcx, gcy, w, h, rot, n.isUpper(), a);
        float pr = NoteShapes.headPearlRadius(NoteShapes.pearlRadius(noteSize), w);
        // the glow of a TIDE beneath (a joint DROP or STREAM sits on its wave or bar) leaves the pearls out
        if (pearl) rowCoverHoles(gcx, gcy, pr);
        if (heldNow) {
            int k = glows;
            addGlow(G_NOTE, gcx, gcy, w + 56f, h + 40f, rot, col, 0.40f * alpha);
            if (pearl && glows > k) rowGlowHoles(pr);
        } else if (high && !lost) {
            int k = glows;
            addGlow(G_NOTE, gcx, gcy, w + 40f, h + 32f, rot, col, 0.22f * alpha);
            // the pearls sit across the glow's centre line: leave them out, so the glow cannot pale their gold
            if (pearl && glows > k) rowGlowHoles(pr);
        }
    }

    /** Quads of {@link #paintLaneNote} a knock-out adds to a ground ribbon (a plain piece and two side strips). */
    static final int RIBBON_HOLE_QUADS = 3;

    /**
     * Paints a lane note (DROP / STREAM; DESIGN §5.8, §21.7) in its local frame: the hold ribbon
     * when {@code tail > 0} (fill fading head → tail, split around the knock-outs set by
     * {@link #ribbonHoles}, {@code hold_edge} sides, a tail cap in the ribbon colour), then the
     * {@code note_9p} head and the 3 px white leading edge. Normal blend; leaves the batch colour
     * changed and the local frame set to {@code rot}.
     *
     * @param x       leading-edge centre x
     * @param y       leading-edge centre y
     * @param w       head width
     * @param h       head height
     * @param rot     rotation in degrees (+180 for sky notes)
     * @param c       body colour (line colour, or mixed toward the missed grey)
     * @param alpha   note opacity
     * @param headMul head opacity factor (a broken hold fades its head)
     * @param ribHead ribbon opacity at the head ({@link NoteShapes#ribbonHeadAlpha})
     * @param ribTail ribbon opacity at the tail end
     * @param tail    ribbon length (0 = no ribbon)
     * @param rw      ribbon width ({@link NoteShapes#ribbonWidth})
     * @param ew      ribbon edge width ({@link NoteShapes#ribbonEdge})
     * @param lost    missed look (dimmer edges and cap)
     * @return quads submitted
     */
    int paintLaneNote(float x, float y, float w, float h, float rot, Color c, float alpha, float headMul,
                      float ribHead, float ribTail, float tail, float rw, float ew, boolean lost) {
        frame(rot);
        int quads = 0;
        if (tail > 0f && rw > 0f) {
            float hr = rw * 0.5f;
            float aH = ribHead * alpha;
            float aT = ribTail * alpha;
            segs = holes > 0 ? NoteShapes.rowSegments(tail, holes, holeLo, holeHi, segLo, segHi, segHole) : 0;
            float hx0 = MathUtils.clamp(holeX0, -hr, hr);
            float hx1 = MathUtils.clamp(holeX1, -hr, hr);
            if (segs == 0 || hx1 <= hx0) {
                localRect(x, y, -hr, 0f, hr, tail, c, aH, aT);
                quads++;
            } else {
                for (int s = 0; s < segs; s++) {
                    float r0 = segLo[s];
                    float r1 = segHi[s];
                    float a0 = aH + (aT - aH) * (r0 / tail);
                    float a1 = aH + (aT - aH) * (r1 / tail);
                    if (!segHole[s]) {
                        localRect(x, y, -hr, r0, hr, r1, c, a0, a1);
                        quads++;
                    } else {
                        // the ground fill leaves a hole under the sky ribbon; the edges stay
                        int n = NoteShapes.spansBeside(-hr, hr, hx0, hx1, span);
                        for (int k = 0; k < n; k++) localRect(x, y, span[2 * k], r0, span[2 * k + 1], r1, c, a0, a1);
                        quads += n;
                    }
                }
            }
            float dim = lost ? 0.5f : 1f;
            batch.setColor(c.r, c.g, c.b, 0.9f * alpha * dim);
            local(edgeL, x, y, -hr, 0f, ew, tail);
            local(edgeR, x, y, hr - ew, 0f, ew, tail);
            float capH = Math.min(NoteShapes.RIBBON_CAP, tail);
            batch.setColor(c.r, c.g, c.b, NoteShapes.RIBBON_CAP_A * alpha * dim);
            local(core, x, y, -hr, tail - capH, rw, capH);
            quads += 3;
        }
        float a = alpha * headMul;
        batch.setColor(c.r, c.g, c.b, a);
        if (w >= 24f) {
            nine.draw(batch, x - w * 0.5f, y, w * 0.5f, 0f, w, h, 1f, 1f, rot);
            quads += 9;
        } else {
            batch.setColor(c.r, c.g, c.b, 0.8f * a);
            local(core, x, y, -w * 0.5f, 0f, w, h);
            quads++;
        }
        if (w > 16f) {
            batch.setColor(1f, 1f, 1f, a * (lost ? 0.4f : 1f));
            local(core, x, y, -w * 0.5f + 6f, 0f, w - 12f, 3f);
            quads++;
        }
        return quads;
    }

    /** Sets the local frame (rotation in degrees) of the next {@code local*} calls. */
    private void frame(float rot) {
        srot = rot;
        fcos = MathUtils.cosDeg(rot);
        fsin = MathUtils.sinDeg(rot);
    }

    /** Draws region r in the note's local frame: rect (lx, ly, w, h) rotated about the origin. */
    private void local(TextureRegion r, float ox, float oy, float lx, float ly, float w, float h) {
        batch.draw(r, ox + lx, oy + ly, -lx, -ly, w, h, 1f, 1f, srot);
    }

    /**
     * Draws the local rect {@code [lx0, lx1] × [ly0, ly1]} in colour c with opacity {@code a0}
     * along {@code ly0} and {@code a1} along {@code ly1} (one vertex quad in the local frame).
     */
    private void localRect(float ox, float oy, float lx0, float ly0, float lx1, float ly1, Color c, float a0,
                           float a1) {
        float c0 = Color.toFloatBits(c.r, c.g, c.b, a0);
        float c1 = Color.toFloatBits(c.r, c.g, c.b, a1);
        int k = vert(0, ox, oy, lx0, ly0, c0, coreU, coreV);
        k = vert(k, ox, oy, lx0, ly1, c1, coreU, coreV);
        k = vert(k, ox, oy, lx1, ly1, c1, coreU, coreV);
        vert(k, ox, oy, lx1, ly0, c0, coreU, coreV);
        batch.draw(core.getTexture(), verts, 0, 20);
    }

    /** Writes one vertex of local point (lx, ly) into {@link #verts} at k; @return k + 5. */
    private int vert(int k, float ox, float oy, float lx, float ly, float c, float u, float v) {
        float[] q = verts;
        q[k] = ox + lx * fcos - ly * fsin;
        q[k + 1] = oy + lx * fsin + ly * fcos;
        q[k + 2] = c;
        q[k + 3] = u;
        q[k + 4] = v;
        return k + 5;
    }

    /**
     * The sync pearls of a DROP or STREAM head (owner 2026-10-12 12:33, 「把中间的"黄色方块"换成黄色圆形，像珍珠一样」;
     * gold, 12:42; three side by side, 16:07): {@link NoteShapes#HEAD_PEARLS} gold pearls
     * {@link NoteShapes#PEARL_D} × noteSize across in a row across the middle of the head, centred,
     * {@link NoteShapes#PEARL_STEP} diameters apart (smaller on a head too narrow for them,
     * {@link NoteShapes#headPearlRadius}), turning with it at the head's alpha; call right after
     * {@link #paintLaneNote} (its local frame). The tutorial's sync demo calls it too.
     *
     * @param w head width
     * @param h head height
     * @return quads submitted ({@link #SYNC_QUADS})
     */
    int syncPearl(float x, float y, float w, float h, float a) {
        return pearlRow(x, y, h * 0.5f, NoteShapes.headPearlRadius(NoteShapes.pearlRadius(noteSize), w),
            Theme.SYNC_PEARL, Theme.SYNC_PEARL_RIM, Theme.SYNC_PEARL_LUSTRE, a);
    }

    /**
     * Paints the row of {@link NoteShapes#HEAD_PEARLS} pearls of radius {@code r} across a head whose
     * leading-edge centre is {@code (x, y)}, at {@code d} behind it, in the current local frame.
     *
     * @return quads submitted ({@link #SYNC_QUADS})
     */
    private int pearlRow(float x, float y, float d, float r, Color body, Color rimColor, Color lustre, float a) {
        int q = 0;
        for (int i = 0; i < NoteShapes.HEAD_PEARLS; i++) {
            float lx = NoteShapes.headPearlOffset(i, r);
            q += pearl(x + lx * fcos - d * fsin, y + lx * fsin + d * fcos, r, NoteShapes.PEARL_RIM, body, rimColor,
                lustre, a);
        }
        return q;
    }

    /** Leaves the row of head pearls of radius {@code r} out of the glow added last (centred on the row's middle). */
    private void rowGlowHoles(float r) {
        for (int i = 0; i < NoteShapes.HEAD_PEARLS; i++) glowHole(NoteShapes.headPearlOffset(i, r), 0f, r);
    }

    /**
     * Leaves the row of head pearls of radius {@code r} centred at {@code (cx, cy)} (in the current local frame)
     * out of the TIDE glows beneath ({@link #coverHoles}).
     */
    private void rowCoverHoles(float cx, float cy, float r) {
        for (int i = 0; i < NoteShapes.HEAD_PEARLS; i++) {
            float lx = NoteShapes.headPearlOffset(i, r);
            coverHoles(cx + lx * fcos, cy + lx * fsin, r);
        }
    }

    /**
     * Paints a pearl of radius {@code r} centred at {@code (cx, cy)} from the {@code disc} and
     * {@code soft_dot} sprites (same atlas page as the notes, so no extra draw call): the rim disc
     * in {@code rimColor}, the {@code body} disc inside it ({@code rim} = rim width as a share of
     * r), a soft {@code lustre} (null for none) and a small white highlight, both toward the upper
     * left of the screen, so sky and ground pearls are lit alike whatever their turn. Normal blend;
     * leaves the batch colour changed.
     *
     * @return quads submitted ({@link NoteShapes#PEARL_QUADS} with a lustre, else
     *         {@link NoteShapes#ROW_PEARL_QUADS})
     */
    private int pearl(float cx, float cy, float r, float rim, Color body, Color rimColor, Color lustre, float a) {
        float s = r / DISC_R;
        batch.setColor(rimColor.r, rimColor.g, rimColor.b, a);
        batch.draw(disc, cx - s * 0.5f, cy - s * 0.5f, s, s);
        float b = r * (1f - rim);
        s = b / DISC_R;
        batch.setColor(body.r, body.g, body.b, a);
        batch.draw(disc, cx - s * 0.5f, cy - s * 0.5f, s, s);
        int q = 2;
        if (lustre != null) {
            // soft_dot is α (1 − d)²: a sheen fading out before the rim
            float ls = 2f * NoteShapes.LUSTRE_R * b;
            float lo = NoteShapes.LUSTRE_OFF * b;
            batch.setColor(lustre.r, lustre.g, lustre.b, NoteShapes.LUSTRE_A * a);
            batch.draw(dot, cx - lo - ls * 0.5f, cy + lo - ls * 0.5f, ls, ls);
            q++;
        }
        s = NoteShapes.SHINE_R * b / DISC_R;
        float so = NoteShapes.SHINE_OFF * b;
        batch.setColor(1f, 1f, 1f, NoteShapes.SHINE_A * a);
        batch.draw(disc, cx - so - s * 0.5f, cy + so - s * 0.5f, s, s);
        return q + 1;
    }

    // ================================================================== mines

    /** @return ±3 for two frames after LANE MINE i's latest contact, else 0. */
    private float contactJitter(GameEngine e, int i, int frame) {
        float at = e.mineLastContactMs(i);
        if (Float.isNaN(at)) return 0f;
        double age = e.judgeMs() - at;
        if (age < 0.0 || age >= JITTER_MS) return 0f;
        return Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
    }

    private void drawMine(GameEngine e, int i, Note n, float beat, float now, int frame) {
        byte st = e.noteState(i);
        LaneLayout lay = e.layout();
        int lane = n.laneIndex();
        if (lane < 0 || lane >= lay.count() || !lay.active(lane)) return;
        boolean up = n.isUpper();
        boolean laneMine = n.isLaneMine();
        float lineY = e.lineY(n.line());
        float lw = lay.width(lane);
        if (laneMine) {
            // the closed lane reads at a glance: its line segment glows while it blocks
            float b = NoteShapes.blockLook(e.judgeMs(), n.judgementMs(), n.endMs(), GameEngine.LANE_MINE_LEAD_MS);
            if (b > 0f) {
                addGlow(G_V, lay.centre(lane), lineY, lw, NoteShapes.LANE_MINE_LINE_GLOW_H, 0f, Theme.MINE,
                    NoteShapes.LANE_MINE_LINE_GLOW_A * b);
            }
        }
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) return;
        float lb = entry.look(i, beat);
        float mw = lw * GameEngine.MINE_BOX;
        float mh = mineHeight();
        float rot = n.angleAt(lb) + (up ? 180f : 0f);
        float y = (up ? lineY - pos : lineY + pos) + NoteShapes.pivotShiftY(pos, MINE_H, mh, rot, up, n.pivotX(), n.pivotY());
        float pad = rotPad(rot, mw);
        // toward the line it reaches its turned corners, and its height when turned toward the line
        if (offScreen(up, y, mh + pad, FLAT_CULL + turnReach(up, rot, mw, mh))) return;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up);
        float x = lay.centre(lane) + NoteShapes.pivotShiftX(pos, MINE_H, mh, rot, up, n.pivotX(), n.pivotY());
        boolean live;
        if (laneMine) {
            alpha *= NoteShapes.endFade(e.judgeMs(), n.endMs());
            x += contactJitter(e, i, frame);
            live = e.judgeMs() < n.endMs();
        } else {
            live = st == GameEngine.STATE_PENDING;
            if (st == GameEngine.STATE_MINE_PASSED || st == GameEngine.STATE_MINE_TOUCHED) {
                float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
                if (Float.isNaN(age)) age = 0f;
                boolean touched = st == GameEngine.STATE_MINE_TOUCHED;
                alpha *= 1f - MathUtils.clamp(age / (touched ? 160f : 120f), 0f, 1f);
                if (touched && age < JITTER_MS) x += Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
            }
        }
        if (alpha <= MIN_ALPHA) return;
        paintMine(x, y, mw, mh, rot, alpha);
        // a MINE or untouched LANE MINE that shares its beat: a light-red halo, three red pearls across its bar
        boolean sync = NoteShapes.showsPearl(n, st);
        boolean pearl = sync && syncPearls();
        float pr = NoteShapes.headPearlRadius(NoteShapes.minePearlRadius(noteSize), mw);
        if (sync && syncHalo()) {
            frameGlow(x - fsin * mh * 0.5f, y + fcos * mh * 0.5f, mw, mh, rot, Theme.MINE_HIGHLIGHT,
                HL_MINE_K * HL_BAND_A * alpha, HL_MINE_K * HL_HALO_A * alpha);
        }
        if (pearl) {
            pearlRow(x, y, mh * 0.5f, pr, Theme.MINE_PEARL, Theme.MINE_PEARL_RIM, Theme.MINE_PEARL_LUSTRE, alpha);
            rowCoverHoles(x - fsin * mh * 0.5f, y + fcos * mh * 0.5f, pr);
        }
        if (live) {
            float pulse = 0.26f + 0.08f * MathUtils.sin(now * MathUtils.PI2 * 2f);
            int k = glows;
            addGlow(G_NOTE, x - fsin * mh * 0.5f, y + fcos * mh * 0.5f, mw + 48f, mh + 40f, rot, Theme.MINE,
                pulse * alpha);
            // the red pulse leaves the pearls out, so they keep their coral bodies
            if (pearl && glows > k) rowGlowHoles(pr);
        }
    }

    /**
     * Paints a MINE body (DESIGN §5.15) in its local frame: rounded {@code mine} rim,
     * {@code mineCore} fill and three studs on each long edge, turned by {@code rot} about the
     * leading-edge centre {@code (x, y)} (owner 2026-10-12 03:03: mines turn like DROP and STREAM). Also the
     * head cap of a LANE MINE. Leaves the local frame set to {@code rot}.
     *
     * @return quads submitted (at most {@link #MINE_QUADS})
     */
    int paintMine(float x, float y, float mw, float mh, float rot, float alpha) {
        frame(rot);
        float r = Math.min(MINE_RADIUS, Math.min(mw, mh) * 0.5f);
        float lx = -mw * 0.5f;
        rotChip(x, y, lx, 0f, mw, mh, r, Theme.MINE, alpha);
        if (mw > 8f && mh > 8f) {
            rotChip(x, y, lx + 3f, 3f, mw - 6f, mh - 6f, Math.max(1f, r - 3f), Theme.MINE_CORE, 0.92f * alpha);
        }
        // studs on both long edges at 1/4, 1/2, 3/4
        Color m = Theme.MINE;
        batch.setColor(m.r, m.g, m.b, m.a * alpha);
        for (int k = 1; k <= 3; k++) {
            float cx = lx + mw * k * 0.25f;
            local(core, x, y, cx - 3f, -3f, 6f, 6f);
            local(core, x, y, cx - 3f, mh - 3f, 6f, 6f);
        }
        return MINE_QUADS;
    }

    /**
     * A {@code chip_9p} rounded rect {@code (lx, ly, w, h)} with corner radius {@code radius} in
     * the current local frame (as {@code Draw.roundRect}, turned): one vertex draw of ≤ 9 quads.
     */
    private void rotChip(float ox, float oy, float lx, float ly, float w, float h, float radius, Color c, float alpha) {
        if (w <= 0f || h <= 0f) return;
        float r = Math.min(radius, Math.min(w, h) * 0.5f);
        if (r < 0.5f) r = Math.min(0.5f, Math.min(w, h) * 0.5f);
        TextureRegion reg = chip;
        float u0 = reg.getU();
        float u3 = reg.getU2();
        float vTop = reg.getV();
        float vBot = reg.getV2();
        float us = (u3 - u0) * (CHIP_SPLIT / reg.getRegionWidth());
        float vs = (vBot - vTop) * (CHIP_SPLIT / reg.getRegionHeight());
        gxs[0] = lx;
        gxs[1] = lx + r;
        gxs[2] = lx + w - r;
        gxs[3] = lx + w;
        gys[0] = ly;
        gys[1] = ly + r;
        gys[2] = ly + h - r;
        gys[3] = ly + h;
        gus[0] = u0;
        gus[1] = u0 + us;
        gus[2] = u3 - us;
        gus[3] = u3;
        gvs[0] = vBot;
        gvs[1] = vBot - vs;
        gvs[2] = vTop + vs;
        gvs[3] = vTop;
        float cc = Color.toFloatBits(c.r, c.g, c.b, c.a * alpha);
        int k = 0;
        for (int j = 0; j < 3; j++) {
            if (gys[j + 1] <= gys[j]) continue;
            for (int i = 0; i < 3; i++) {
                if (gxs[i + 1] <= gxs[i]) continue;
                k = vert(k, ox, oy, gxs[i], gys[j], cc, gus[i], gvs[j]);
                k = vert(k, ox, oy, gxs[i], gys[j + 1], cc, gus[i], gvs[j + 1]);
                k = vert(k, ox, oy, gxs[i + 1], gys[j + 1], cc, gus[i + 1], gvs[j + 1]);
                k = vert(k, ox, oy, gxs[i + 1], gys[j], cc, gus[i + 1], gvs[j]);
            }
        }
        if (k > 0) batch.draw(reg.getTexture(), verts, 0, k);
    }

    /** Upper bound of the quads of one {@link #paintMine} (two nine-slices and six studs). */
    static final int MINE_QUADS = 24;
    /** Upper bound of the quads of one {@link #paintLaneNote} without a ribbon. */
    static final int TAP_QUADS = 10;
    /** Upper bound of the quads of one {@link #paintLaneNote} with a ribbon and no knock-out. */
    static final int HOLD_QUADS = 14;
    /**
     * Upper bound of the quads of a LANE MINE afterimage copy: its column without stripes and
     * studs (fill, two rims, tail cap; each split at most in two around the other line) and the
     * head cap.
     */
    static final int LANE_MINE_COPY_QUADS = 4 * 4 + MINE_QUADS;

    /**
     * LANE MINE body in the band layer (DESIGN §20.2): the blocking veil over its lane's part of
     * its zone, then the column. With {@code dry} it only counts (the quad bound).
     *
     * @return quads submitted (or that would be)
     */
    private int laneMineBody(GameEngine e, int i, Note n, float beat, int frame, boolean dry) {
        LaneLayout lay = e.layout();
        int lane = n.laneIndex();
        if (lane < 0 || lane >= lay.count() || !lay.active(lane)) return 0;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        float lw = lay.width(lane);
        int q = 0;
        float b = NoteShapes.blockLook(e.judgeMs(), n.judgementMs(), n.endMs(), GameEngine.LANE_MINE_LEAD_MS);
        if (b > 0f) {
            if (!dry) {
                Color m = Theme.MINE;
                batch.setColor(m.r, m.g, m.b, NoteShapes.LANE_MINE_VEIL_A * b);
                batch.draw(core, lay.left(lane), lineY - Field.zoneHalf(), lw, 2f * Field.zoneHalf());
            }
            q++;
        }
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) return q;
        float lb = entry.look(i, beat);
        float tail = tailDraw(n, lb);
        if (!(tail > 0f)) return q;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up) * NoteShapes.endFade(e.judgeMs(), n.endMs());
        if (alpha <= MIN_ALPHA) return q;
        float rot = n.angleAt(lb) + (up ? 180f : 0f);
        float mh = mineHeight();
        float y = (up ? lineY - pos : lineY + pos) + NoteShapes.pivotShiftY(pos, MINE_H, mh, rot, up, n.pivotX(), n.pivotY());
        float x = lay.centre(lane) + NoteShapes.pivotShiftX(pos, MINE_H, mh, rot, up, n.pivotX(), n.pivotY())
            + (dry ? 0f : contactJitter(e, i, frame));
        return q + paintLaneMineColumn(x, y, rot, lw, tail, alpha, NoteShapes.songSeconds(e),
            e.lineY(n.line().other()), true, dry);
    }

    /**
     * Paints a LANE MINE column (DESIGN §20.2) in its local frame from the leading edge
     * {@code (ox, oy)} for {@code tail} away from its line, turned by {@code rot}: {@code mineCore}
     * fill (α .85) over the lane inset 6 each side, 45° {@code mine} hazard stripes (α .35, period
     * 24) drifting toward the line at 40 /s of song time, 3 px {@code mine} rims with 6×6 studs
     * every 64, and a 3 px tail cap. Only the rows on screen are drawn, and nothing within
     * {@link NoteShapes#OTHER_LINE_GAP} of {@code otherY}, the other judgement line, which stays as
     * bright across the column as elsewhere (owner 2026-10-12 02:58). Afterimage copies pass
     * {@code detail = false} (no stripes, no studs). Leaves the local frame set to {@code rot}.
     *
     * @param seconds song seconds of the stripe phase
     * @param dry     count only, draw nothing
     * @return quads submitted (or that would be)
     */
    int paintLaneMineColumn(float ox, float oy, float rot, float lw, float tail, float alpha, float seconds,
                            float otherY, boolean detail, boolean dry) {
        float hw = NoteShapes.laneMineHalfWidth(lw);
        if (hw <= NoteShapes.LANE_MINE_RIM || !(tail > 0f)) return 0;
        frame(rot);
        if (!visibleRows(ox, oy, hw + NoteShapes.STUD, tail)) return 0;
        float lo = visLo;
        float hi = visHi;
        gapLo = otherY - NoteShapes.OTHER_LINE_GAP;
        gapHi = otherY + NoteShapes.OTHER_LINE_GAP;
        Color mc = Theme.MINE_CORE;
        Color m = Theme.MINE;
        float cFill = Color.toFloatBits(mc.r, mc.g, mc.b, NoteShapes.LANE_MINE_FILL_A * alpha);
        float cMine = Color.toFloatBits(m.r, m.g, m.b, alpha);
        int q = rectPoly(ox, oy, -hw, lo, hw, hi, cFill, dry);
        if (detail) {
            float cStripe = Color.toFloatBits(m.r, m.g, m.b, NoteShapes.STRIPE_A * alpha);
            float p = NoteShapes.stripeAxisPeriod();
            float sw = NoteShapes.stripeAxisWidth();
            float off = NoteShapes.stripeOffset(seconds);
            // stripe k lies between the 45° lines y = x + c and y = x + c + sw, c = off + k·p
            int k0 = (int) Math.ceil((lo - hw - sw - off) / p);
            int k1 = (int) Math.floor((hi + hw - off) / p);
            for (int k = k0; k <= k1; k++) {
                float c = off + k * p;
                pa[0] = -hw;
                pa[1] = -hw + c;
                pa[2] = hw;
                pa[3] = hw + c;
                pa[4] = hw;
                pa[5] = hw + c + sw;
                pa[6] = -hw;
                pa[7] = -hw + c + sw;
                int nv = clip(pa, 4, pb, 0f, -1f, -lo); // keep y ≥ lo
                nv = clip(pb, nv, pa, 0f, 1f, hi);      // keep y ≤ hi
                q += emitLocal(pa, nv, ox, oy, cStripe, dry);
            }
        }
        float rim = NoteShapes.LANE_MINE_RIM;
        q += rectPoly(ox, oy, -hw, lo, -hw + rim, hi, cMine, dry);
        q += rectPoly(ox, oy, hw - rim, lo, hw, hi, cMine, dry);
        if (detail) {
            float half = NoteShapes.STUD * 0.5f;
            float step = NoteShapes.STUD_STEP;
            int j0 = Math.max(0, (int) Math.ceil((lo - half) / step - 0.5f));
            for (int j = j0; ; j++) {
                float yc = step * (j + 0.5f);
                if (yc + half > tail || yc - half > hi) break;
                float l = -hw + rim * 0.5f;
                float r = hw - rim * 0.5f;
                q += rectPoly(ox, oy, l - half, yc - half, l + half, yc + half, cMine, dry);
                q += rectPoly(ox, oy, r - half, yc - half, r + half, yc + half, cMine, dry);
            }
        }
        float cap = Math.min(NoteShapes.LANE_MINE_CAP, tail);
        if (tail - cap <= hi) q += rectPoly(ox, oy, -hw, tail - cap, hw, tail, cMine, dry);
        return q;
    }

    /**
     * Sets {@code [visLo, visHi]}: the rows of {@code [0, tail]} of a body in the current local
     * frame from {@code (ox, oy)} whose centre line passes within {@code m} of the screen.
     *
     * @return false when none does
     */
    private boolean visibleRows(float ox, float oy, float m, float tail) {
        float lo = 0f;
        float hi = tail;
        float b = Ui.bottom() - m;
        float t = Ui.top() + m;
        if (Math.abs(fcos) > 1e-4f) {
            float a1 = (b - oy) / fcos;
            float a2 = (t - oy) / fcos;
            lo = Math.max(lo, Math.min(a1, a2));
            hi = Math.min(hi, Math.max(a1, a2));
        } else if (oy < b || oy > t) {
            return false;
        }
        float l = Ui.left() - m;
        float r = Ui.right() + m;
        if (Math.abs(fsin) > 1e-4f) {
            float a1 = (ox - l) / fsin;
            float a2 = (ox - r) / fsin;
            lo = Math.max(lo, Math.min(a1, a2));
            hi = Math.min(hi, Math.max(a1, a2));
        } else if (ox < l || ox > r) {
            return false;
        }
        visLo = lo;
        visHi = hi;
        return hi > lo;
    }

    /** Emits the local rect {@code [lx0, lx1] × [ly0, ly1]} (see {@link #emitLocal}). */
    private int rectPoly(float ox, float oy, float lx0, float ly0, float lx1, float ly1, float c, boolean dry) {
        if (lx1 <= lx0 || ly1 <= ly0) return 0;
        pa[0] = lx0;
        pa[1] = ly0;
        pa[2] = lx1;
        pa[3] = ly0;
        pa[4] = lx1;
        pa[5] = ly1;
        pa[6] = lx0;
        pa[7] = ly1;
        return emitLocal(pa, 4, ox, oy, c, dry);
    }

    /**
     * Emits a convex local polygon of {@code n} vertices: turned into world space, split around
     * the other line's gap {@code [gapLo, gapHi]} when it crosses it, and drawn as quads.
     *
     * @return quads submitted (or that would be)
     */
    private int emitLocal(float[] p, int n, float ox, float oy, float c, boolean dry) {
        if (n < 3) return 0;
        float ymin = Float.POSITIVE_INFINITY;
        float ymax = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < n; v++) {
            float lx = p[2 * v];
            float ly = p[2 * v + 1];
            float wy = oy + lx * fsin + ly * fcos;
            pc[2 * v] = ox + lx * fcos - ly * fsin;
            pc[2 * v + 1] = wy;
            if (wy < ymin) ymin = wy;
            if (wy > ymax) ymax = wy;
        }
        if (!(ymax > gapLo && ymin < gapHi)) return emitWorld(pc, n, c, dry);
        int m = clip(pc, n, pd, 0f, 1f, gapLo);    // the part below the gap
        int q = emitWorld(pd, m, c, dry);
        m = clip(pc, n, pd, 0f, -1f, -gapHi);      // the part above it
        return q + emitWorld(pd, m, c, dry);
    }

    /** Draws a convex world polygon as a fan of quads (a triangle as a quad with a repeated vertex). */
    private int emitWorld(float[] p, int n, float c, boolean dry) {
        if (n < 3) return 0;
        int quads = 0;
        for (int i = 1; i + 1 < n; i += 2) {
            int i3 = Math.min(i + 2, n - 1);
            if (!dry) {
                worldVert(0, p, 0, c);
                worldVert(5, p, i, c);
                worldVert(10, p, i + 1, c);
                worldVert(15, p, i3, c);
                batch.draw(core.getTexture(), verts, 0, 20);
            }
            quads++;
        }
        return quads;
    }

    private void worldVert(int k, float[] p, int v, float c) {
        float[] q = verts;
        q[k] = p[2 * v];
        q[k + 1] = p[2 * v + 1];
        q[k + 2] = c;
        q[k + 3] = coreU;
        q[k + 4] = coreV;
    }

    /**
     * Clips a convex polygon to the half plane {@code a·x + b·y ≤ c} (Sutherland–Hodgman).
     *
     * @return the vertex count written to {@code dst} (≤ n + 1)
     */
    private static int clip(float[] src, int n, float[] dst, float a, float b, float c) {
        if (n < 3) return 0;
        int m = 0;
        float px = src[2 * (n - 1)];
        float py = src[2 * (n - 1) + 1];
        float pdist = a * px + b * py - c;
        for (int i = 0; i < n; i++) {
            float qx = src[2 * i];
            float qy = src[2 * i + 1];
            float qdist = a * qx + b * qy - c;
            if (qdist <= 0f) {
                if (pdist > 0f) {
                    float t = pdist / (pdist - qdist);
                    dst[2 * m] = px + (qx - px) * t;
                    dst[2 * m + 1] = py + (qy - py) * t;
                    m++;
                }
                dst[2 * m] = qx;
                dst[2 * m + 1] = qy;
                m++;
            } else if (pdist <= 0f) {
                float t = pdist / (pdist - qdist);
                dst[2 * m] = px + (qx - px) * t;
                dst[2 * m + 1] = py + (qy - py) * t;
                m++;
            }
            px = qx;
            py = qy;
            pdist = qdist;
        }
        return m;
    }

    // ================================================================== TIDE

    /** @return upper bound of the quads of one {@link #paintTide} over {@code lanes} lanes. */
    static int tideQuads(int lanes) {
        return 3 + lanes;
    }

    /**
     * Paints a TIDE STREAM head bar (DESIGN §5.15, §21.6) across {@code [x0, x1]} with its leading
     * edge at {@code y}: gradient fill (ground {@code tide}, sky the {@code skySig} pearl
     * gradient) and white leading edge, or the missed grey; ping diamonds in {@code tide} at the
     * lane boundaries of {@code lay} (not when missed). No glow.
     *
     * @param jx horizontal jitter of the missed look (0 for none)
     * @return quads submitted
     */
    int paintTide(float x0, float x1, float y, float th, boolean up, boolean lost, float jx, float alpha, LaneLayout lay) {
        float y0 = up ? y - th : y;
        if (lost) {
            rect(x0 + jx, y0, x1 - x0, th, Theme.MISSED, 0.5f * alpha);
        } else if (up) {
            rectGradientV(x0, y0, x1 - x0, th, SKY_TIDE_TRAIL, SKY_TIDE_LEAD, alpha);
        } else {
            rectGradientV(x0, y0, x1 - x0, th, TIDE_LEAD, TIDE_TRAIL, alpha);
        }
        rect(x0 + jx, up ? y - 3f : y, x1 - x0, 3f, Theme.WHITE, (lost ? 0.4f : 1f) * alpha);
        int quads = 2;
        if (!lost) {
            float cy = y0 + th * 0.5f;
            Color tc = Theme.TIDE;
            for (int k = 0; k < lay.count(); k++) {
                if (!lay.active(k)) continue;
                quads += diamond(tideX(lay.left(k)), cy, x0, x1, tc, 0.9f * alpha * Math.max(lay.opacity(k), k > 0 ? lay.opacity(k - 1) : 0f));
                if (k == lay.count() - 1 || !lay.active(k + 1)) quads += diamond(tideX(lay.right(k)), cy, x0, x1, tc, 0.9f * alpha * lay.opacity(k));
            }
        }
        return quads;
    }

    /**
     * Paints a TIDE drop in the T4 look (owner 2026-10-12 04:36; {@link NoteShapes}) across {@code [x0, x1]}
     * with its leading edge at {@code y}. Sky: a 2u {@code skySig} thread at α .45 7u behind the
     * leading edge and {@code 3 × lanes} pearls on it, radius 11u / 7u alternating. Ground: a
     * {@code TIDE_WAVE} band (α .85) from the leading edge back to a crest at
     * {@code 20u + 9u·sin(phase)}, a 3u {@code TIDE_WAVE_CREST} crest line and a 3u
     * {@code TIDE_WAVE_BASE} line on the leading edge, from {@code 8 × lanes} vertical strips.
     * {@code u} is the note size; the phase moves with {@code seconds} (song time). With
     * {@code glow} it also collects the soft glows (live notes only). Normal blend; leaves the
     * batch colour changed. No sync pearls (afterimage copies and the guide).
     *
     * @param grey 0..1 toward the missed grey
     * @param jx   horizontal jitter of the missed look (0 for none)
     * @return quads submitted (at most {@link NoteShapes#t4Quads})
     */
    int paintTideT4(float x0, float x1, float y, boolean up, float grey, float jx, float alpha, LaneLayout lay,
                    float seconds, boolean glow) {
        return paintTideT4(x0, x1, y, up, grey, jx, alpha, lay, seconds, glow, false);
    }

    /**
     * {@link #paintTideT4(float, float, float, boolean, float, float, float, LaneLayout, float, boolean)}
     * with the sync pearls of a live TIDE that shares its beat when {@code gold}: on the sky line
     * the pearls on the thread turn gold (rim, body, lustre, highlight) and so do their glows,
     * which leave the pearls out; on the ground line a row of {@link NoteShapes#beadCount} gold
     * pearls, large and small in turn ({@link NoteShapes#rowPearlRadius}), rides halfway between the
     * base line and the crest, and the wave's glow leaves them out. The thread and the wave are
     * unchanged.
     *
     * @return quads submitted (at most {@link NoteShapes#t4Quads} plus, with {@code gold},
     *         {@link NoteShapes#t4PearlQuads})
     */
    int paintTideT4(float x0, float x1, float y, boolean up, float grey, float jx, float alpha, LaneLayout lay,
                    float seconds, boolean glow, boolean gold) {
        return paintTideT4(x0, x1, y, up, grey, jx, alpha, lay, seconds, glow, gold, false);
    }

    /**
     * {@link #paintTideT4(float, float, float, boolean, float, float, float, LaneLayout, float, boolean, boolean)}
     * with the sync highlight when {@code halo} (Kelvin 2026-10-13: 「珍珠的surface tide每一个珍珠高亮而不是整一个大矩形框起来，
     * bottom tide沿着轮廓高亮」): on the sky line a deep gold halo and outline round each pearl, on the ground line a
     * white border and halo along the wave's crest.
     */
    int paintTideT4(float x0, float x1, float y, boolean up, float grey, float jx, float alpha, LaneLayout lay,
                    float seconds, boolean glow, boolean gold, boolean halo) {
        if (x1 <= x0) return 0;
        float u = noteSize;
        int lanes = lay.patternLanes();
        float dir = up ? -1f : 1f;
        float ax0 = x0 + jx;
        float ax1 = x1 + jx;
        if (up) {
            Color pc = grey > 0f ? Theme.mix(Theme.SKY_SIG, Theme.MISSED, grey, col2) : Theme.SKY_SIG;
            float ty = y + dir * NoteShapes.T4_THREAD_BEHIND * u;
            float th = NoteShapes.T4_THREAD * u;
            batch.setColor(pc.r, pc.g, pc.b, NoteShapes.T4_THREAD_A * alpha);
            batch.draw(core, ax0, ty - th * 0.5f, ax1 - ax0, th);
            int n = NoteShapes.beadCount(lanes);
            float bs = beadStep(lay, x0, x1);
            beadRange(lay, x0, x1, bs, n);
            batch.setColor(pc.r, pc.g, pc.b, alpha);
            int q = 1;
            for (int i = beadFirst; i <= beadLast; i++) {
                float cx = beadX(lay, i, n, x0, x1, bs) + jx;
                float r = NoteShapes.beadRadius(i, u);
                if (gold) {
                    q += pearl(cx, ty, r, NoteShapes.PEARL_RIM, Theme.SYNC_PEARL, Theme.SYNC_PEARL_RIM,
                        Theme.SYNC_PEARL_LUSTRE, alpha);
                } else {
                    float s = r / DISC_R;
                    batch.draw(disc, cx - s * 0.5f, ty - s * 0.5f, s, s);
                    q++;
                }
                if (glow) {
                    float gs = NoteShapes.T4_BEAD_GLOW * r;
                    int k = glows;
                    addGlow(G_DOT, cx, ty, gs, gs, 0f, gold ? Theme.SYNC_PEARL : Theme.SKY_SIG,
                        NoteShapes.T4_BEAD_GLOW_A * alpha);
                    if (gold && glows > k) glowHole(0f, 0f, r);
                }
                if (halo) {
                    // a soft halo and a solid border round the pearl; a gold pearl is left out of both
                    float hs = 2f * (r + HL_DOT_PAD);
                    int k = glows;
                    addGlow(G_DOT, cx, ty, hs, hs, 0f, Theme.SYNC_HIGHLIGHT_SKY, HL_SKY_HALO_A * alpha);
                    if (gold && glows > k) glowHole(0f, 0f, r);
                    float ds = 2f * (r + HL_PEARL_BAND) / (2f * DISC_R);
                    k = glows;
                    addGlow(G_DISC, cx, ty, ds, ds, 0f, Theme.SYNC_HIGHLIGHT_SKY, HL_SKY_BAND_A * alpha);
                    if (gold && glows > k) glowHole(0f, 0f, r);
                }
            }
            return q;
        }
        Color fc = Theme.TIDE_WAVE;
        Color cc = Theme.TIDE_WAVE_CREST;
        Color bc = Theme.TIDE_WAVE_BASE;
        if (grey > 0f) {
            fc = Theme.mix(fc, Theme.MISSED, grey, col);
            cc = Theme.mix(cc, Theme.MISSED, grey, col2);
            bc = Theme.mix(bc, Theme.MISSED, grey, col3);
        }
        float cFill = Color.toFloatBits(fc.r, fc.g, fc.b, NoteShapes.WAVE_FILL_A * alpha);
        float cCrest = Color.toFloatBits(cc.r, cc.g, cc.b, alpha);
        float cBase = Color.toFloatBits(bc.r, bc.g, bc.b, alpha);
        float half = NoteShapes.WAVE_LINE * u * 0.5f;
        float wo = lay.patternOrigin();
        float mlw = lay.patternWidth();
        // base line on the leading edge
        flatQuad(ax0, y - half, ax0, y + half, ax1, y + half, ax1, y - half, cBase);
        int strips = NoteShapes.waveStrips(lanes);
        float xa = ax0;
        float ca = NoteShapes.waveCrest(tideWin(x0) - wo, mlw, seconds, u);
        for (int k = 1; k <= strips; k++) {
            float xr = x0 + (x1 - x0) * k / strips;
            float xb = xr + jx;
            float cb = NoteShapes.waveCrest(tideWin(xr) - wo, mlw, seconds, u);
            // fill from the base line to the crest line, then the crest line
            flatQuad(xa, y + dir * half, xa, y + dir * (ca - half), xb, y + dir * (cb - half), xb, y + dir * half,
                cFill);
            flatQuad(xa, y + dir * (ca - half), xa, y + dir * (ca + half), xb, y + dir * (cb + half),
                xb, y + dir * (cb - half), cCrest);
            xa = xb;
            ca = cb;
        }
        float gcx = (ax0 + ax1) * 0.5f;
        float gcy = y + dir * NoteShapes.WAVE_BASE * u * 0.5f;
        int k = glows;
        if (glow) {
            addGlow(G_V, gcx, gcy, ax1 - ax0, NoteShapes.WAVE_GLOW_H * u, 0f, Theme.TIDE_WAVE,
                NoteShapes.WAVE_GLOW_A * alpha);
        }
        if (halo) crestGlow(x0, x1, y, jx, lay, seconds, alpha, strips);
        int q = 1 + 2 * strips;
        if (gold) {
            // the gold row halfway up the wave, riding the crest; the wave's glow leaves it out
            boolean holed = glows > k;
            int n = NoteShapes.beadCount(lanes);
            float bs = beadStep(lay, x0, x1);
            beadRange(lay, x0, x1, bs, n);
            for (int i = beadFirst; i <= beadLast; i++) {
                float px = beadX(lay, i, n, x0, x1, bs);
                float py = y + dir * NoteShapes.wavePearlDistance(tideWin(px) - wo, mlw, seconds, u);
                float r = NoteShapes.rowPearlRadius(i, u);
                q += pearl(px + jx, py, r, NoteShapes.ROW_PEARL_RIM, Theme.SYNC_PEARL, Theme.SYNC_PEARL_RIM, null,
                    alpha);
                if (holed) glowHole(px + jx - gcx, py - gcy, r);
            }
        }
        return q;
    }

    /**
     * Collects the sync highlight along a ground TIDE wave's crest ({@link #paintTideT4}): the crest's points, for
     * {@link #drawGlows} to draw over them a solid border {@link #HL_BAND} × note size high and a soft halo
     * {@link #HL_PAD} × note size high, both from the crest up.
     */
    private void crestGlow(float x0, float x1, float y, float jx, LaneLayout lay, float seconds, float alpha,
                           int strips) {
        if (crests + 2 > CREST_MAX || crestPts + strips + 1 > CREST_PTS || alpha <= 0.003f) return;
        float u = noteSize;
        float wo = lay.patternOrigin();
        float mlw = lay.patternWidth();
        Color hc = Theme.SYNC_HIGHLIGHT;
        for (int i = 0; i < 2; i++) {
            int c = crests++;
            boolean band = i == 1;
            crestBand[c] = band;
            crestAt[c] = crestPts;
            crestN[c] = strips + 1;
            crestPad[c] = (band ? HL_BAND : HL_PAD) * u;
            crestCol[c] = Color.toFloatBits(hc.r, hc.g, hc.b, Math.min(1f, (band ? HL_BAND_A : HL_HALO_A) * alpha));
            crestNx[c] = tideTurned ? -ttSin : 0f;
            crestNy[c] = tideTurned ? ttCos : 1f;
        }
        for (int k = 0; k <= strips; k++) {
            float xr = x0 + (x1 - x0) * k / strips;
            float px = xr + jx;
            float py = y + NoteShapes.waveCrest(tideWin(xr) - wo, mlw, seconds, u);
            if (tideTurned) { // drawn later, without the turned batch
                float dx = px - ttPx;
                float dy = py - ttPy;
                px = ttPx + ttCos * dx - ttSin * dy;
                py = ttPy + ttSin * dx + ttCos * dy;
            }
            cpx[crestPts] = px;
            cpy[crestPts] = py;
            crestPts++;
        }
    }

    /**
     * Adds the sync highlight round a {@code w × h} body centred at {@code (cx, cy)}, turned {@code rot}: a soft
     * {@link #G_FRAME} halo {@link #HL_PAD} wide and a solid {@link #G_BAND} border reaching {@link #HL_BAND}
     * beyond the body, both starting {@link #HL_INSET} inside its edge, so the edge lights up while the body keeps
     * its colour.
     */
    private void frameGlow(float cx, float cy, float w, float h, float rot, Color c, float bandA, float haloA) {
        float iw = Math.max(0f, w - 2f * HL_INSET);
        float ih = Math.max(0f, h - 2f * HL_INSET);
        int k = glows;
        addGlow(G_FRAME, cx, cy, iw, ih, rot, c, haloA);
        if (glows > k) gpad[k] = HL_PAD;
        k = glows;
        addGlow(G_BAND, cx, cy, iw, ih, rot, c, bandA);
        if (glows > k) gpad[k] = HL_INSET + HL_BAND;
    }

    /** The sync frame of a key or TIDE STREAM head: white on the ground line, deep gold on the sky line. */
    private void syncFrame(float cx, float cy, float w, float h, float rot, boolean sky, float a) {
        if (sky) frameGlow(cx, cy, w, h, rot, Theme.SYNC_HIGHLIGHT_SKY, HL_SKY_BAND_A * a, HL_SKY_HALO_A * a);
        else frameGlow(cx, cy, w, h, rot, Theme.SYNC_HIGHLIGHT, HL_BAND_A * a, HL_HALO_A * a);
    }

    /** One world quad (four corners in order) in packed colour c. */
    private void flatQuad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, float c) {
        float[] q = verts;
        q[0] = x1;
        q[1] = y1;
        q[2] = c;
        q[3] = coreU;
        q[4] = coreV;
        q[5] = x2;
        q[6] = y2;
        q[7] = c;
        q[8] = coreU;
        q[9] = coreV;
        q[10] = x3;
        q[11] = y3;
        q[12] = c;
        q[13] = coreU;
        q[14] = coreV;
        q[15] = x4;
        q[16] = y4;
        q[17] = c;
        q[18] = coreU;
        q[19] = coreV;
        batch.draw(core.getTexture(), q, 0, 20);
    }

    /**
     * Turns the atlas batch {@code deg} degrees about a TIDE's pivot (owner 2026-10-16: a TIDE or TIDE STREAM turns
     * like the other notes; 「对于无限长的东西（tide），默认原点就是屏幕内的按键的中点」): the origin is the middle of the
     * on-screen part of its bar {@code [x0, x1]} (world), half the bar ({@link #tideHeight}) behind its leading
     * edge {@code y}; the pivot lies {@code (px, py)} reference units from it in the note's frame (stretched by
     * kx, ky; turned 180° on the sky line, so + y points away from the line). The TIDE is drawn as if infinitely
     * long: the span map ({@link #tideX}) stretches its bar so that, turned, it still crosses {@code [x0, x1]}
     * (up to {@link NoteShapes#TIDE_TURN_MIN_COS}) with each lane's pearls, wave and diamonds above that lane.
     * Its glows turn as they are collected ({@link #addGlow}). Upright ({@link NoteShapes#turned}) nothing happens.
     *
     * @return true when turned: {@link #tideTurnEnd} must follow (after the note's glitch replay)
     */
    boolean tideTurn(float deg, float px, float py, float y, boolean up, float x0, float x1) {
        if (tideTurned || !NoteShapes.turned(deg)) return false;
        float l = Math.max(x0, Ui.left());
        float r = Math.min(x1, Ui.right());
        float ox = l < r ? 0.5f * (l + r) : 0.5f * (x0 + x1);
        float oy = up ? y - tideHeight() * 0.5f : y + tideHeight() * 0.5f;
        float vx = px * Field.kx();
        float vy = py * Field.ky();
        ttPx = up ? ox - vx : ox + vx;
        ttPy = up ? oy - vy : oy + vy;
        ttDeg = deg;
        ttCos = MathUtils.cosDeg(deg);
        ttSin = MathUtils.sinDeg(deg);
        // local x where the turned bar's middle line crosses window x: Px + (x − Px + sin·(oy − Py)) / cos
        float c = Math.abs(ttCos) >= NoteShapes.TIDE_TURN_MIN_COS ? ttCos
            : ttCos < 0f ? -NoteShapes.TIDE_TURN_MIN_COS : NoteShapes.TIDE_TURN_MIN_COS;
        tmK = 1f / c;
        tmA = ttPx + (ttSin * (oy - ttPy) - ttPx) * tmK;
        tideTurned = true;
        ttSaved.set(atlas.getTransformMatrix());
        ttTurn.set(ttSaved).translate(ttPx, ttPy, 0f).rotate(0f, 0f, 1f, deg).translate(-ttPx, -ttPy, 0f);
        atlas.setTransformMatrix(ttTurn);
        return true;
    }

    /** Undoes {@link #tideTurn} (nothing when the TIDE was upright). */
    void tideTurnEnd() {
        if (!tideTurned) return;
        tideTurned = false;
        tmA = 0f;
        tmK = 1f;
        atlas.setTransformMatrix(ttSaved);
    }

    /** @return where window x is drawn along a turned TIDE's bar ({@link #tideTurn}); x itself when upright. */
    float tideX(float x) {
        return tmA + x * tmK;
    }

    /** @return the window x a turned TIDE's local x stands for (inverse of {@link #tideX}). */
    float tideWin(float lx) {
        return (lx - tmA) / tmK;
    }

    /** @return the left end of window span {@code [x0, x1]} as drawn ({@link #tideX}). */
    float tideLo(float x0, float x1) {
        return Math.min(tideX(x0), tideX(x1));
    }

    /** @return the right end of window span {@code [x0, x1]} as drawn ({@link #tideX}). */
    float tideHi(float x0, float x1) {
        return Math.max(tideX(x0), tideX(x1));
    }

    private void drawTide(GameEngine e, int i, Note n, float beat, int frame) {
        byte st = e.noteState(i);
        if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) return;
        // a pending TIDE group draws one head (its first member); a running TIDE STREAM its own
        if (!tideBar(i, n, st)) return;
        boolean stream = n.isTideStream();
        if (stream && st != GameEngine.STATE_PENDING && beat > n.endBeat()
            && n.tailHeightAt(beat) <= 0f) return;
        LaneLayout lay = e.layout();
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) return;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        float y = up ? lineY - pos : lineY + pos;
        float th = tideHeight();
        float lb = entry.look(i, beat);
        float deg = n.angleAt(lb);
        // a turned TIDE crosses the screen slanted: no cull (it is rare, and drawn only while on its way)
        if (!NoteShapes.turned(deg) && offScreen(up, y, tideReach(stream), FLAT_CULL)) return;
        float x0 = lay.windowLeft() + TIDE_INSET;
        float x1 = lay.windowRight() - TIDE_INSET;
        if (x1 <= x0) return;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up);
        boolean lost = st == GameEngine.STATE_LOST;
        float jx = 0f;
        float grey = 0f;
        if (lost) {
            float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
            if (Float.isNaN(age)) age = 0f;
            // a drop fades out; a TIDE STREAM's head fades to .30 like a broken hold's
            float k = MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
            alpha *= stream ? 1f - 0.70f * k : 1f - k;
            grey = MathUtils.clamp(age / MISS_GREY_MS, 0f, 1f);
            if (age < JITTER_MS) jx = Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
        }
        if (alpha <= MIN_ALPHA) return;
        boolean sync = NoteShapes.showsPearl(n, st);
        boolean pearls = sync && syncPearls();
        if (tideTurn(deg, n.pivotX(), n.pivotY(), y, up, x0, x1)) { // undone by the caller (tideTurnEnd)
            float a = tideLo(x0, x1);
            x1 = tideHi(x0, x1);
            x0 = a;
        }
        if (!stream) {
            int g = glows;
            paintTideT4(x0, x1, y, up, grey, jx, alpha, lay, NoteShapes.songSeconds(e), !lost && high, pearls,
                sync && syncHalo());
            // the ground wave's glow leaves out the pearls of the notes drawn over it (upright only)
            if (!up && glows > g && greg[g] == G_V && !tideTurned) coverGlow(g);
            return;
        }
        paintTide(x0, x1, y, th, up, lost, jx, alpha, lay);
        float cy = (up ? y - th : y) + th * 0.5f;
        int k = glows;
        if (!lost && high) {
            boolean held = st == GameEngine.STATE_HOLDING && e.isHoldHeld(i);
            addGlow(G_V, (x0 + x1) * 0.5f, cy, x1 - x0, held ? 64f : 48f, 0f, up ? Theme.SKY_TIDE : Theme.TIDE,
                (held ? 0.30f : 0.20f) * alpha);
        }
        if (glows > k && !tideTurned) coverGlow(k);
        if (pearls) barPearls(lay, cy, alpha, glows > k ? (x0 + x1) * 0.5f : Float.NaN);
        if (sync && syncHalo()) syncFrame((x0 + x1) * 0.5f, cy, x1 - x0, th, 0f, up, alpha);
    }

    /**
     * PROPOSAL (the owner's words do not cover the TIDE STREAM head): a pending TIDE STREAM that
     * shares its beat carries the ground wave's row of gold pearls, large and small in turn, along
     * the middle ({@code cy}) of its head bar, {@link NoteShapes#beadCount} of them over the lane
     * window, so the lane-boundary diamonds fall halfway between two pearls. With a glow centred at
     * {@code (glowX, cy)} added last (not NaN) the glow leaves them out.
     *
     * @return quads submitted ({@link NoteShapes#barPearlQuads})
     */
    private int barPearls(LaneLayout lay, float cy, float alpha, float glowX) {
        int n = NoteShapes.beadCount(lay.patternLanes());
        float wl = tideLo(lay.windowLeft(), lay.windowRight());
        float wr = tideHi(lay.windowLeft(), lay.windowRight());
        float bs = beadStep(lay, wl, wr);
        beadRange(lay, wl, wr, bs, n);
        int q = 0;
        for (int i = beadFirst; i <= beadLast; i++) {
            float px = beadX(lay, i, n, wl, wr, bs);
            float r = NoteShapes.rowPearlRadius(i, noteSize);
            q += pearl(px, cy, r, NoteShapes.ROW_PEARL_RIM, Theme.SYNC_PEARL, Theme.SYNC_PEARL_RIM, null, alpha);
            if (!Float.isNaN(glowX)) glowHole(px - glowX, 0f, r);
        }
        return q;
    }

    /** First and last pearl index of the row {@link #beadRange} set last. */
    private int beadFirst, beadLast;

    /**
     * @return spacing of a pearl row over {@code [a, b]}: {@code (b − a) / (beadCount)} at
     *         {@link LaneLayout#patternWidth} lanes' worth across the window, so on tiled lanes it
     *         follows the global lane scale (owner 2026-10-13) and equals the shared-window spacing
     *         at the default layout
     */
    private static float beadStep(LaneLayout lay, float a, float b) {
        float span = lay.windowRight() - lay.windowLeft();
        return (b - a) * lay.patternWidth() / (span * NoteShapes.T4_BEADS_PER_LANE);
    }

    /**
     * Sets {@link #beadFirst}..{@link #beadLast}: all {@code n} pearls when the lanes share the
     * window; on tiled lanes the pearls {@code s} apart about {@link LaneLayout#patternOrigin}
     * that fall inside {@code [a, b]}, at most {@code n}, so the row slides with the scale and
     * pearls come and go at its ends.
     */
    private void beadRange(LaneLayout lay, float a, float b, float s, int n) {
        if (!lay.tiled()) {
            beadFirst = 0;
            beadLast = n - 1;
        } else if (!(s > 0f) || !(b > a)) {
            beadFirst = 0;
            beadLast = -1;
        } else {
            float o = tideX(lay.patternOrigin());
            beadFirst = (int) Math.ceil((a - o) / s - 0.5f);
            beadLast = Math.min((int) Math.floor((b - o) / s - 0.5f), beadFirst + n - 1);
        }
    }

    /** @return centre x of pearl i of a row over {@code [a, b]} ({@link #beadRange}). */
    private float beadX(LaneLayout lay, int i, int n, float a, float b, float s) {
        return lay.tiled() ? tideX(lay.patternOrigin()) + (i + 0.5f) * s : NoteShapes.beadX(i, n, a, b);
    }

    // ================================================================== band layer

    /**
     * Quads of one plain {@link #paintTideBand} and {@link #paintTideCap} (fill, two rails, two ripple lines,
     * tail cap).
     */
    static final int BAND_QUADS = 6;
    /** Quads a knock-out adds to a ground band (a plain piece, two side strips, two rails). */
    static final int BAND_HOLE_QUADS = 5;
    /** Band opacity factor while a TIDE STREAM is held (DESIGN §19.1). */
    static final float BAND_HELD = 1.6f;
    /** Longest stretch of band (from the head) the two ripple lines travel through. */
    private static final float RIPPLE_SPAN = 720f;
    /** Seconds a ripple line takes over its span. */
    private static final float RIPPLE_SEC = 2.4f;

    /**
     * Normal block B, before the trails and the notes: the band layer, beneath lane notes, mines
     * and afterimages (DESIGN §19.1 layering): the TIDE STREAM bands, the LANE MINE veils and
     * columns, then every band's tail cap (on top, also inside an overlap). No allocation.
     */
    void drawBands(GameEngine e, float now) {
        float beat = e.visualBeat();
        fadeBeat = beat;
        glitchQuads = 0;
        glitchedNotes = 0;
        collectSkyBands(e, beat);
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.isTideStream() || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(i, n, beat);
            drawBand(e, i, n, beat, now, false);
            if (g) glitchEnd();
            tideTurnEnd();
        }
        int frame = (int) (now * 60f);
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.isLaneMine() || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(i, n, beat);
            laneMineBody(e, i, n, beat, frame, false);
            if (g) glitchEnd();
        }
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.isTideStream() || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(i, n, beat);
            drawBand(e, i, n, beat, now, true);
            if (g) glitchEnd();
            tideTurnEnd();
        }
        batch.setColor(Color.WHITE);
    }

    /** Collects the world rows of this frame's sky TIDE STREAM bands, for the ground bands' knock-out. */
    private void collectSkyBands(GameEngine e, float beat) {
        skyBands = 0;
        for (int i = start; i < end && skyBands < MAX_SKY; i++) {
            Note n = notes[i];
            if (!n.isTideStream() || !n.isUpper() || entry.drawFrom(i) > beat) continue;
            byte st = e.noteState(i);
            if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
            float pos = drawPos(i, n, beat);
            if (Float.isNaN(pos)) continue;
            float lb = entry.look(i, beat);
            float tail = tailDraw(n, lb);
            if (!(tail > 0f) || NoteShapes.turned(n.angleAt(lb))) continue; // a turned band knocks nothing out
            float lineY = e.lineY(n.line());
            if (n.shownOpacityAt(lb) * fadeIn(i, lineY, true) <= MIN_ALPHA) continue;
            float y = lineY - pos;
            skyBandLo[skyBands] = y - tail;
            skyBandHi[skyBands] = y;
            skyBands++;
        }
    }

    private void drawBand(GameEngine e, int i, Note n, float beat, float now, boolean cap) {
        byte st = e.noteState(i);
        if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) return;
        LaneLayout lay = e.layout();
        float x0 = lay.windowLeft() + TIDE_INSET;
        float x1 = lay.windowRight() - TIDE_INSET;
        if (x1 <= x0) return;
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) return;
        float lb = entry.look(i, beat);
        float tail = tailDraw(n, lb);
        if (!(tail > 0f)) return;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        float y = up ? lineY - pos : lineY + pos;
        float deg = n.angleAt(lb);
        boolean turned = NoteShapes.turned(deg);
        if (!turned && offScreen(up, y, tail, FLAT_CULL)) return;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up);
        if (alpha <= MIN_ALPHA) return;
        float grey = 0f;
        if (st == GameEngine.STATE_LOST) {
            float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
            grey = Float.isNaN(age) ? 1f : MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
        }
        float sx0 = NoteShapes.skyBandLeft(x0, x1, NoteShapes.SKY_TIDE_STREAM_INSET);
        float sx1 = NoteShapes.skyBandRight(x0, x1, NoteShapes.SKY_TIDE_STREAM_INSET);
        // turned with its head about the same pivot (undone by the caller, tideTurnEnd)
        tideTurn(deg, n.pivotX(), n.pivotY(), y, up, x0, x1);
        if (up) {
            x0 = sx0;
            x1 = sx1;
        }
        float a = tideLo(x0, x1);
        x1 = tideHi(x0, x1);
        x0 = a;
        if (cap) {
            paintTideCap(x0, x1, y, tail, up, grey, alpha);
            return;
        }
        holes = 0;
        if (!up && !turned) {
            for (int k = 0; k < skyBands && holes < NoteShapes.MAX_HOLES; k++) {
                if (!NoteShapes.overlapRows(y, 1f, tail, skyBandLo[k], skyBandHi[k], ov)) continue;
                holeLo[holes] = ov[0];
                holeHi[holes] = ov[1];
                holes++;
            }
            holeX0 = sx0;
            holeX1 = sx1;
        }
        boolean held = st == GameEngine.STATE_HOLDING && e.isHoldHeld(i);
        paintTideBand(x0, x1, y, tail, up, held ? BAND_HELD : 1f, grey, now, tideHeight(), alpha);
        holes = 0;
    }

    /**
     * Paints a TIDE STREAM band (DESIGN §19.1, §21.6–21.7) across {@code [x0, x1]} from the
     * head's leading edge at {@code y} for {@code len} away from the line, in the band colour
     * ({@code skyTide} on the sky line, {@code tide} on the ground line): fill α .14 → .08, 2 px
     * rails at α .6 at both ends and, when {@code ripple} is not NaN, two 1 px ripple lines
     * ({@code @ .18}) scrolling toward the line. Where the knock-outs set by {@link #drawBand}
     * overlap (a sky band over this ground band) it draws nothing under the sky band
     * {@code [holeX0, holeX1]}: no fill, no ripple, and rails only when the side strips beside the
     * sky band hold them. The tail cap is {@link #paintTideCap}. Normal blend, axis-aligned;
     * leaves the batch colour changed.
     *
     * @param mul    band opacity factor (1, {@link #BAND_HELD} while held)
     * @param grey   0..1 toward the missed grey (a broken or missed TIDE STREAM; no ripples)
     * @param ripple run seconds for the ripple phase, NaN for none (afterimage copies)
     * @param head   bar thickness (the ripples start behind it)
     * @return quads submitted (at most {@link #BAND_QUADS} − 1 without a knock-out)
     */
    int paintTideBand(float x0, float x1, float y, float len, boolean up, float mul, float grey, float ripple,
                      float head, float alpha) {
        if (!(len > 0f) || x1 <= x0) return 0;
        Color c = col;
        Color base = up ? Theme.SKY_TIDE : Theme.TIDE;
        if (grey > 0f) Theme.mix(base, Theme.MISSED, grey, c);
        else c.set(base);
        float w = x1 - x0;
        float dir = up ? -1f : 1f;
        float aHead = Math.min(1f, NoteShapes.BAND_HEAD_A * mul) * alpha;
        float aTail = Math.min(1f, NoteShapes.BAND_TAIL_A * mul) * alpha;
        float rail = NoteShapes.BAND_RAIL;
        float ra = Math.min(1f, NoteShapes.BAND_RAIL_A * mul) * alpha * (1f - 0.4f * grey);
        segs = holes > 0 ? NoteShapes.rowSegments(len, holes, holeLo, holeHi, segLo, segHi, segHole) : 0;
        int quads = 0;
        if (segs == 0) {
            quads += bandRows(x0, w, y, dir, 0f, len, len, c, aHead, aTail);
            float b0 = up ? y - len : y;
            batch.setColor(c.r, c.g, c.b, ra);
            batch.draw(core, x0, b0, rail, len);
            batch.draw(core, x1 - rail, b0, rail, len);
            quads += 2;
        } else {
            float hx0 = MathUtils.clamp(holeX0, x0, x1);
            float hx1 = MathUtils.clamp(holeX1, x0, x1);
            boolean through = NoteShapes.railsThroughOverlap(hx0 - x0) && NoteShapes.railsThroughOverlap(x1 - hx1);
            for (int s = 0; s < segs; s++) {
                float r0 = segLo[s];
                float r1 = segHi[s];
                if (!segHole[s]) {
                    quads += bandRows(x0, w, y, dir, r0, r1, len, c, aHead, aTail);
                    if (!through) {
                        float b0 = up ? y - r1 : y + r0;
                        batch.setColor(c.r, c.g, c.b, ra);
                        batch.draw(core, x0, b0, rail, r1 - r0);
                        batch.draw(core, x1 - rail, b0, rail, r1 - r0);
                        quads += 2;
                    }
                } else {
                    // nothing under the sky band; with an inset only the side strips beside it
                    int n = NoteShapes.spansBeside(x0, x1, hx0, hx1, span);
                    for (int k = 0; k < n; k++) {
                        quads += bandRows(span[2 * k], span[2 * k + 1] - span[2 * k], y, dir, r0, r1, len, c, aHead,
                            aTail);
                    }
                }
            }
            if (through) {
                float b0 = up ? y - len : y;
                batch.setColor(c.r, c.g, c.b, ra);
                batch.draw(core, x0, b0, rail, len);
                batch.draw(core, x1 - rail, b0, rail, len);
                quads += 2;
            }
        }
        if (Float.isNaN(ripple) || grey > 0f) return quads;
        // two ripple lines travelling from the band's far stretch toward the head
        float span = Math.min(len, RIPPLE_SPAN) - head;
        if (span <= 8f) return quads;
        for (int k = 0; k < 2; k++) {
            float ph = ripple / RIPPLE_SEC + 0.5f * k;
            ph -= (float) Math.floor(ph);
            float d = head + span * (1f - ph);
            if (inHoleRows(d)) continue;
            float a = 0.18f * Math.min(1f, mul) * alpha * MathUtils.sin(MathUtils.PI * ph);
            if (a <= MIN_ALPHA) continue;
            batch.setColor(c.r, c.g, c.b, a);
            batch.draw(core, x0 + rail, up ? y - d - 1f : y + d, w - 2f * rail, 1f);
            quads++;
        }
        return quads;
    }

    /** @return true when row d of the band being painted lies in one of its knock-outs. */
    private boolean inHoleRows(float d) {
        for (int s = 0; s < segs; s++) {
            if (segHole[s] && d >= segLo[s] && d <= segHi[s]) return true;
        }
        return false;
    }

    /** Band rows {@code [r0, r1]} over {@code [x, x + w]} with the head → tail fill gradient; @return 1. */
    private int bandRows(float x, float w, float y, float dir, float r0, float r1, float len, Color c, float aHead,
                         float aTail) {
        float a0 = aHead + (aTail - aHead) * (r0 / len);
        float a1 = aHead + (aTail - aHead) * (r1 / len);
        float c0 = Color.toFloatBits(c.r, c.g, c.b, a0);
        float c1 = Color.toFloatBits(c.r, c.g, c.b, a1);
        if (dir > 0f) bandFill(x, y + r0, w, r1 - r0, c0, c1);
        else bandFill(x, y - r1, w, r1 - r0, c1, c0);
        return 1;
    }

    /**
     * Paints a TIDE STREAM band's tail cap (DESIGN §21.7): 4 px in the band colour at α .75 at the
     * band's far end. Drawn after every band, so a cap inside an overlap still shows.
     *
     * @return quads submitted
     */
    int paintTideCap(float x0, float x1, float y, float len, boolean up, float grey, float alpha) {
        if (!(len > 0f) || x1 <= x0) return 0;
        Color c = col;
        Color base = up ? Theme.SKY_TIDE : Theme.TIDE;
        if (grey > 0f) Theme.mix(base, Theme.MISSED, grey, c);
        else c.set(base);
        float h = Math.min(NoteShapes.BAND_CAP, len);
        batch.setColor(c.r, c.g, c.b, NoteShapes.BAND_CAP_A * alpha * (1f - 0.6f * grey));
        batch.draw(core, x0, up ? y - len : y + len - h, x1 - x0, h);
        return 1;
    }

    /** Fill quad of a band with a vertical gradient (packed colours at the bottom and the top). */
    private void bandFill(float x, float y, float w, float h, float bottom, float top) {
        float u = coreU;
        float v = coreV;
        float[] q = bandQuad;
        q[0] = x; q[1] = y; q[2] = bottom; q[3] = u; q[4] = v;
        q[5] = x; q[6] = y + h; q[7] = top; q[8] = u; q[9] = v;
        q[10] = x + w; q[11] = y + h; q[12] = top; q[13] = u; q[14] = v;
        q[15] = x + w; q[16] = y; q[17] = bottom; q[18] = u; q[19] = v;
        batch.draw(core.getTexture(), q, 0, 20);
    }

    private int diamond(float x, float cy, float x0, float x1, Color c, float a) {
        if (x < x0 || x > x1 || a <= 0.01f) return 0;
        batch.setColor(c.r, c.g, c.b, c.a * a);
        batch.draw(core, x - 5f, cy - 5f, 5f, 5f, 10f, 10f, 0.7071f, 0.7071f, 45f);
        batch.setColor(Color.WHITE);
        return 1;
    }

    private void addGlow(byte region, float x, float y, float w, float h, float rot, Color c, float a) {
        if (glows >= GLOW_MAX || a <= 0.003f) return;
        if (tideTurned) { // drawn later, without the turned batch: turn it here
            float dx = x - ttPx;
            float dy = y - ttPy;
            x = ttPx + ttCos * dx - ttSin * dy;
            y = ttPy + ttSin * dx + ttCos * dy;
            rot += ttDeg;
        }
        int k = glows++;
        greg[k] = region;
        gglitch[k] = false;
        gholes[k] = 0;
        gx[k] = x;
        gy[k] = y;
        gw[k] = w;
        gh[k] = h;
        grot[k] = rot;
        gcol[k] = Color.toFloatBits(c.r, c.g, c.b, Math.min(1f, c.a * a));
    }

    /**
     * Leaves an octagon of apothem {@code a} centred at {@code (lx, ly)} (in the frame of the glow
     * added last, from its centre) out of that glow, so the sync pearl under it keeps its colour.
     * One glow's holes come in ascending {@code lx}; a hole that overlaps the previous one along x,
     * reaches outside the glow, or finds the store full is skipped (the glow then covers that pearl).
     */
    private void glowHole(float lx, float ly, float a) {
        int k = glows - 1;
        if (k < 0 || glowHoles >= GLOW_HOLE_MAX || !(a > 0f)) return;
        float hw = gw[k] * 0.5f;
        float hh = gh[k] * 0.5f;
        if (!(lx - a >= -hw && lx + a <= hw && ly - a >= -hh && ly + a <= hh)) return;
        int m = gholes[k];
        if (m == 0) {
            gholeAt[k] = glowHoles;
        } else {
            int p = gholeAt[k] + m - 1;
            if (lx - a < hlx[p] + hla[p]) return;
        }
        hlx[glowHoles] = lx;
        hly[glowHoles] = ly;
        hla[glowHoles] = a;
        glowHoles++;
        gholes[k] = m + 1;
    }

    /** Marks upright glow k as one that leaves out the pearls drawn over it later in this frame. */
    private void coverGlow(int k) {
        if (covers < COVER_MAX) cover[covers++] = k;
    }

    /**
     * Leaves the pearl of radius {@code r} at {@code (cx, cy)} out of every glow marked by {@link #coverGlow} (a
     * TIDE's wave or bar, all drawn before the lane notes and mines) that holds it whole, so a DROP, STREAM or MINE
     * drawn over a TIDE of its beat keeps its colour.
     */
    private void coverHoles(float cx, float cy, float r) {
        for (int t = 0; t < covers; t++) {
            int k = cover[t];
            float lx = cx - gx[k];
            float ly = cy - gy[k];
            float hw = gw[k] * 0.5f;
            float hh = gh[k] * 0.5f;
            if (lx - r < -hw || lx + r > hw || ly - r < -hh || ly + r > hh) continue;
            insertHole(k, lx, ly, r);
        }
    }

    /**
     * Adds the hole {@code (lx, ly)} of apothem {@code a} to glow k's holes in x order, dropping the glow's own
     * holes it overlaps along x (pearls of the glow's TIDE under the note's head). The glow's holes move to the
     * end of the store first unless they end it already; a full store skips the hole.
     */
    private void insertHole(int k, float lx, float ly, float a) {
        int m = gholes[k];
        int h0 = gholeAt[k];
        if (m == 0 || h0 + m != glowHoles) {
            if (glowHoles + m + 1 > GLOW_HOLE_MAX) return;
            if (m > 0) {
                System.arraycopy(hlx, h0, hlx, glowHoles, m);
                System.arraycopy(hly, h0, hly, glowHoles, m);
                System.arraycopy(hla, h0, hla, glowHoles, m);
            }
            h0 = glowHoles;
            gholeAt[k] = h0;
            glowHoles += m;
        } else if (glowHoles + 1 > GLOW_HOLE_MAX) {
            return;
        }
        int w = h0;
        int at = -1;
        for (int j = h0; j < h0 + m; j++) {
            if (hlx[j] + hla[j] > lx - a && hlx[j] - hla[j] < lx + a) continue;
            if (at < 0 && hlx[j] > lx) at = w;
            hlx[w] = hlx[j];
            hly[w] = hly[j];
            hla[w] = hla[j];
            w++;
        }
        if (at < 0) at = w;
        for (int j = w; j > at; j--) {
            hlx[j] = hlx[j - 1];
            hly[j] = hly[j - 1];
            hla[j] = hla[j - 1];
        }
        hlx[at] = lx;
        hly[at] = ly;
        hla[at] = a;
        gholes[k] = w - h0 + 1;
        glowHoles = h0 + gholes[k];
    }

    // ================================================================== additive pass

    /** Draws the glows collected by the last {@link #draw} (call inside the additive block). */
    void drawGlows() {
        TextureRegion ng = draw.noteGlow();
        TextureRegion gv = draw.glowV();
        TextureRegion gd = draw.softDot();
        if (dotSlice == null) {
            int sw2 = gd.getRegionWidth() / 2;
            int sh2 = gd.getRegionHeight() / 2;
            dotSlice = new TextureRegion(gd, sw2, 0, 1, sh2);
            dotQuarter = new TextureRegion(gd, sw2, 0, sw2, sh2);
            sw2 = disc.getRegionWidth() / 2;
            sh2 = disc.getRegionHeight() / 2;
            discSlice = new TextureRegion(disc, sw2, 0, 1, sh2);
            discQuarter = new TextureRegion(disc, sw2, 0, sw2, sh2);
        }
        float old = batch.getPackedColor();
        for (int k = 0; k < glows; k++) {
            if (!gglitch[k]) {
                drawGlow(k, ng, gv, gd);
                continue;
            }
            glitch.begin(atlas);
            batch = glitch;
            drawGlow(k, ng, gv, gd);
            batch = atlas;
            int o = k * 6;
            // no colour split here: three additive copies would wash the frame out to white
            glitchQuads += glitch.emit(gseed[k], gfx[o], 0f, gfx[o + 2], gfx[o + 3], gfx[o + 4], gfx[o + 5]);
        }
        drawCrests();
        batch.setPackedColor(old);
    }

    /** Draws glow k of {@link #drawGlows} into {@link #batch}. */
    private void drawGlow(int k, TextureRegion ng, TextureRegion gv, TextureRegion gd) {
        byte g = greg[k];
        float w = gw[k];
        float h = gh[k];
        if (g == G_FRAME || g == G_BAND) {
            boolean band = g == G_BAND;
            drawFrame(band ? discSlice : dotSlice, band ? discQuarter : dotQuarter, gx[k], gy[k], w * 0.5f,
                h * 0.5f, gpad[k], grot[k], gcol[k]);
            return;
        }
        TextureRegion r = g == G_NOTE ? ng : g == G_V ? gv : g == G_DISC ? disc : gd;
        if (gholes[k] > 0) {
            holedGlow(r, gx[k], gy[k], w, h, grot[k], gholeAt[k], gholes[k], gcol[k]);
            return;
        }
        batch.setPackedColor(gcol[k]);
        batch.draw(r, gx[k] - w * 0.5f, gy[k] - h * 0.5f, w * 0.5f, h * 0.5f, w, h, 1f, 1f, grot[k]);
    }

    /** Draws the crest highlights collected by {@link #crestGlow}: one strip of quads sharing their corners. */
    private void drawCrests() {
        float[] q = glowQuad;
        for (int c = 0; c < crests; c++) {
            TextureRegion r = crestBand[c] ? discSlice : dotSlice;
            float u = (r.getU() + r.getU2()) * 0.5f;
            float vb = r.getV2();
            float vt = r.getV();
            float col = crestCol[c];
            float p = crestPad[c];
            float nx = crestNx[c] * p;
            float ny = crestNy[c] * p;
            int last = crestAt[c] + crestN[c] - 1;
            for (int i = crestAt[c]; i < last; i++) {
                q[0] = cpx[i];
                q[1] = cpy[i];
                q[2] = col;
                q[3] = u;
                q[4] = vb;
                q[5] = cpx[i] + nx;
                q[6] = cpy[i] + ny;
                q[7] = col;
                q[8] = u;
                q[9] = vt;
                q[10] = cpx[i + 1] + nx;
                q[11] = cpy[i + 1] + ny;
                q[12] = col;
                q[13] = u;
                q[14] = vt;
                q[15] = cpx[i + 1];
                q[16] = cpy[i + 1];
                q[17] = col;
                q[18] = u;
                q[19] = vb;
                batch.draw(r.getTexture(), q, 0, 20);
            }
        }
    }

    /**
     * Draws a {@link #G_FRAME} halo or a {@link #G_BAND} border: {@code slice}'s fall-off (soft_dot's or the
     * disc's) from the box's edge to {@code p} out, along its four sides (box half sizes {@code hw × hh}, centred
     * at {@code (cx, cy)}, turned {@code rot}), and {@code quarter} round its four corners (8 quads).
     */
    private void drawFrame(TextureRegion slice, TextureRegion quarter, float cx, float cy, float hw, float hh, float p,
                           float rot, float col) {
        batch.setPackedColor(col);
        for (int s = 0; s < 4; s++) {
            boolean odd = (s & 1) == 1;
            float d = odd ? hw : hh;    // centre to this side
            float l = odd ? hh : hw;    // half the side, and the next corner's offset along it
            float th = rot + 90f * s;
            if (l > 0f) batch.draw(slice, cx - l, cy + d, l, -d, 2f * l, p, 1f, 1f, th);
            batch.draw(quarter, cx + l, cy + d, -l, -d, p, p, 1f, 1f, th);
        }
    }

    /**
     * Quads of a glow drawn around one hole: the slabs left and right of it, the rows below and
     * above it and its four corner triangles. Each further hole adds 7.
     */
    static final int HOLED_GLOW_QUADS = 8;

    // the glow being cut around its holes (see holedGlow)
    private float hgx;
    private float hgy;
    private float hgcos;
    private float hgsin;
    private float hgw;
    private float hgh;
    private float hgu;
    private float hgdu;
    private float hgv;
    private float hgdv;
    private float hgcol;
    private TextureRegion hgr;

    /**
     * Draws glow region {@code r} ({@code w × h} centred at {@code (cx, cy)}, turned {@code rot})
     * except its {@code n} holes from {@code h0} (octagons around sync pearls, ascending in x, see
     * {@link #glowHole}): the vertical slabs between and beside the holes, and for each hole the
     * rows below and above it and the four corners of its box outside the octagon. The pieces'
     * UVs follow their position, so the rest looks exactly like the one-quad glow
     * ({@code 1 + 7n} quads).
     */
    private void holedGlow(TextureRegion r, float cx, float cy, float w, float h, float rot, int h0, int n,
                           float col) {
        hgr = r;
        hgx = cx;
        hgy = cy;
        hgcos = MathUtils.cosDeg(rot);
        hgsin = MathUtils.sinDeg(rot);
        hgw = w;
        hgh = h;
        hgu = r.getU();
        hgdu = r.getU2() - hgu;
        hgv = r.getV2();
        hgdv = r.getV() - hgv;
        hgcol = col;
        float hh = h * 0.5f;
        float left = -w * 0.5f;
        for (int j = h0; j < h0 + n; j++) {
            float a = hla[j];
            float x0 = hlx[j] - a;
            float x1 = hlx[j] + a;
            float y0 = hly[j] - a;
            float y1 = hly[j] + a;
            float c = a * OCT_CUT;
            glowPiece(left, -hh, left, hh, x0, hh, x0, -hh);
            glowPiece(x0, -hh, x0, y0, x1, y0, x1, -hh);
            glowPiece(x0, y1, x0, hh, x1, hh, x1, y1);
            // the corners of the hole's box outside the octagon (triangles: one corner repeated)
            glowPiece(x0, y0, x0, y0 + c, x0 + c, y0, x0 + c, y0);
            glowPiece(x0, y1 - c, x0, y1, x0 + c, y1, x0 + c, y1);
            glowPiece(x1 - c, y1, x1, y1, x1, y1 - c, x1, y1 - c);
            glowPiece(x1 - c, y0, x1, y0 + c, x1, y0, x1, y0);
            left = x1;
        }
        glowPiece(left, -hh, left, hh, w * 0.5f, hh, w * 0.5f, -hh);
        hgr = null;
    }

    /** One piece of the glow being cut (four local corners); UVs follow the position. */
    private void glowPiece(float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy) {
        float[] q = glowQuad;
        glowVert(q, 0, hgx, hgy, hgcos, hgsin, ax, ay, hgw, hgh, hgu, hgdu, hgv, hgdv, hgcol);
        glowVert(q, 5, hgx, hgy, hgcos, hgsin, bx, by, hgw, hgh, hgu, hgdu, hgv, hgdv, hgcol);
        glowVert(q, 10, hgx, hgy, hgcos, hgsin, cx, cy, hgw, hgh, hgu, hgdu, hgv, hgdv, hgcol);
        glowVert(q, 15, hgx, hgy, hgcos, hgsin, dx, dy, hgw, hgh, hgu, hgdu, hgv, hgdv, hgcol);
        batch.draw(hgr.getTexture(), q, 0, 20);
    }

    private static void glowVert(float[] q, int k, float cx, float cy, float cos, float sin, float lx, float ly,
                                 float w, float h, float u, float du, float v, float dv, float col) {
        q[k] = cx + lx * cos - ly * sin;
        q[k + 1] = cy + lx * sin + ly * cos;
        q[k + 2] = col;
        q[k + 3] = u + (lx / w + 0.5f) * du;
        q[k + 4] = v + (ly / h + 0.5f) * dv;
    }
}
