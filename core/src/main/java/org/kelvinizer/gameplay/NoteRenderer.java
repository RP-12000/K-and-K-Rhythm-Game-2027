package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 音符渲染器：画 DROP、STREAM、MINE、轨道水雷、TIDE（T4：天键珍珠串、地键滚浪）和潮汐，并收集它们的发光。
 * 与别的音符同一拍的音符带同步珍珠（DROP、STREAM 头上三颗金色并排，水雷三颗红色，TIDE 金色珍珠）；轨道水雷的柱子画在条带层，不会盖住另一条判定线。
 * 悬浮键交给 FloatNotes 画，自定义线条交给 StrokeRenderer 画（在键下面，写了 front 的在悬浮键上面）。
 *
 * <p>Note renderer (DESIGN §5.8 notes, §5.15 mines and TIDE notes, §19.1 TIDE STREAM, §20.2
 * LANE MINE, §21.6–21.7, and Kelvin's decisions of 2026-10-12).
 *
 * <p>Every note lives in a local frame whose origin is the centre of its leading edge (the edge
 * facing its line) and whose +y points away from the line: rotation is the note's
 * {@code angleAt(beat)}, +180° for sky notes. Lane notes are {@code note_9p} tinted with the line
 * colour; size follows the lane's <em>current</em> width ({@code laneWidth × 5/6 ×
 * min(noteSize, 1.15)} × {@code 40 × noteSize}). STREAM ribbons follow DESIGN §21.7 (see
 * {@link NoteShapes}): sky 56 % of the lane, ground 82 %, fill fading head → tail, a tail cap in
 * the ribbon's colour, and where a ground ribbon overlaps a sky ribbon of the same lane the ground
 * fill leaves a hole under it. Missed notes turn grey, jitter for two frames and fade; broken holds
 * fade to grey. Notes glitch only where the chart asks for it. The floating notes (CHART_FORMAT §11) are drawn by
 * {@link FloatNotes}, after all the others, from the same culling window, glitching and bending through this class's
 * capture ({@link FloatNotes.Host}). The custom lines (CHART_FORMAT §13) are drawn by {@link StrokeRenderer}: under
 * everything that is judged, or over the floating notes when {@code front}.
 *
 * <p><b>Chart glitches</b> (CHART_FORMAT_v4 §6.9, Kelvin 2026-10-14): while a note's glitch segment is
 * active ({@link NoteGlitch#activeAt}), its sprites go to a {@link GlitchCapture} instead of the
 * atlas batch and are replayed cut into sideways-moving strips, with a red and a blue copy and an
 * occasional dim flicker; "reduce flashing" turns the flicker off and halves the pattern rate.
 * Its glows (sync frame, glow) tear along the same strips in the additive pass, without the colour
 * split. A TIDE group shows the glitch of the note that draws its head.
 *
 * <p><b>Distortion</b> (CHART_FORMAT_v4 §6.11, Kelvin 2026-10-23 16:57 / 17:01): while a note's distortion strength is
 * above 0 ({@link NoteDistortion#strengthAt}, 0 outside its segments) its sprites are recorded the same way and
 * {@link GlitchCapture#bend} moves them with the veils' water wobble about the note's head, so the waves ride with
 * the note (a turned TIDE bends in its own turned frame); then they tear as above or go to the batch as they are.
 * Its glows and a ground TIDE's line glow bend with it. Trails do not bend. Only a look: judgement never sees it.
 *
 * <p><b>Sync pearls</b> (Kelvin 2026-10-12 12:33, 12:39 「同一时刻都带」, 12:42 gold; they replace the gold
 * square): a note that shares its beat with any other note ({@link Note#sharesBeat()})
 * carries pearls while it is pending or held ({@link NoteShapes#showsPearl}): three gold pearls
 * {@link NoteShapes#PEARL_D} × noteSize across side by side across the middle of a DROP or STREAM
 * head (16:07, 「不是1个珍珠而是3个珍珠并排」), half a pearl apart, turning with the note at the head's
 * alpha (each a {@code SYNC_PEARL_RIM} rim, the {@code SYNC_PEARL} body, a soft
 * {@code SYNC_PEARL_LUSTRE} lustre and a white highlight toward the upper left of the screen); three
 * red pearls 0.7× as big the same way across a MINE or LANE MINE head; gold pearls instead of white on a sky
 * TIDE's thread, with gold glows; a row of gold pearls, large and small in turn like the sky thread's,
 * strung on a ground TIDE's wave line (a TIDE STREAM head alike). A note's own glows leave its
 * pearls out (an octagonal hole each), so the pearls stay true gold and red. A DRAG gets no pearls and no
 * frame: it only lights up a little ({@link #DRAG_LIGHT_A}, Kelvin 2026-11-06 06:19).
 *
 * <p>Mines turn with {@code angleAt} like lane notes (Kelvin 2026-10-12 03:03). A LANE MINE (DESIGN §20.2) is
 * drawn in two layers: its body in {@link #drawBands} (beneath trails, lane notes and mines): an
 * inset column of {@code mineCore}, hazard stripes drifting toward the line, rims with studs and
 * a tail cap. The column leaves a gap around the <em>other</em> judgement line, so that line, its
 * ripples and ticks stay as bright as elsewhere (Kelvin 2026-10-12 02:58). While it blocks, a {@code mine}
 * veil covers its lane's part of its own zone (band layer) and its line segment glows (additive).
 * Its head cap is the MINE body, drawn with the notes with the MINE pulse; it jitters on every
 * contact and fades over 120 ms after its end.
 *
 * <p>TIDE drops use the T4 look (Kelvin 2026-10-12 04:36): a string of pearls on the sky line, a rolling wave on
 * the ground line (again since 2026-10-29) whose crest slides by the note's {@code waveSpeed} (still at 0), the
 * pearls moving with it. TIDE heads draw first in the note pass, so lane notes and mines read on top of them (a
 * joint DROP stays visible on its TIDE's wave or pearls). A TIDE STREAM's head is its line's TIDE drop (Kelvin
 * 2026-10-22: the pearl string on the sky line, the wave on the ground line) with a band behind it (§19.1): window-wide
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
 * pearls, glows and rotation are not stretched. The player's VISUAL HIT POINT ({@link #setHitPoint}) then moves every
 * note the same distance along its path ({@link #hitShift()}), so a key's midline crosses its line where the player
 * sees the hit (Kelvin 2026-10-20 03:20).
 *
 * <p>The sprite work of each note type lives in the {@code paint*} methods, which
 * {@link TrailRenderer} also uses for afterimage copies (ARCHITECTURE §17: "same sprites as the
 * live note"); the live-only extras (sync pearls, glows, jitter, the LANE MINE's stripes, studs,
 * veil and line glow, band knock-outs) stay in the {@code draw*} methods.
 */
final class NoteRenderer implements FloatNotes.Host {
    /** Note body height at note size 1. */
    static final float NOTE_H = 40f;
    /** MINE body height at note size 1.0. */
    static final float MINE_H = 32f;
    private static final int GLOW_MAX = 1024;
    /** Most octagonal holes the glows of one frame leave around sync pearls. */
    private static final int GLOW_HOLE_MAX = 1024;
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
    /**
     * A DRAG's sync light ({@link #dragLight}): its bar and diamond drawn again in the additive block, gx, gy its
     * leading-edge centre, gw × gh its box.
     */
    private static final byte G_DRAG = 6;
    /** Most ground TIDE line glows in one frame (two sides each), and most points along them. */
    private static final int CREST_MAX = 128, CREST_PTS = 8192;
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
    /**
     * A DRAG that shares its beat lights up a little instead (Kelvin 2026-11-06 06:19 「drag同时的时候轻微高亮就行了不要外面一个
     * 大框」): its own bar and diamond are added over it at this strength, no frame round its box and no pearls.
     */
    static final float DRAG_LIGHT_A = 0.45f;
    /** Highlighted sky TIDE pearl: its solid border's and soft halo's width beyond the pearl. */
    private static final float HL_PEARL_BAND = 6f, HL_DOT_PAD = 28f;
    private static final float APPROACH_MS = 250f;
    /** Fade-in time (ms) of a note that appears on screen, counted from its first event. */
    private static final float FADE_IN_MS = 150f;
    /** Longest hold tail drawn (world units): more than any visible world height plus the line offset. */
    static final float MAX_TAIL_DRAW = 4000f;
    /** TIDE head and band inset from the window ends. */
    static final float TIDE_INSET = 12f;
    /** Fade of a missed note (ms, DESIGN §5.8). */
    private static final float MISS_FADE_MS = 200f;
    /** Grey-out of a missed DROP or TIDE drop (ms). */
    private static final float MISS_GREY_MS = 60f;
    /** Jitter time of a missed note, a touched mine and a LANE MINE contact (two frames). */
    private static final float JITTER_MS = 34f;
    /** Jitter offset. */
    private static final float JITTER = 3f;
    /** Corner size of the {@code chip_9p} nine-slice in texels (its corner radius). */
    private static final float CHIP_SPLIT = 12f;
    /** Radius of the {@code disc} sprite as a share of its size ({@code Draw.disc}). */
    static final float DISC_R = 0.484f;
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
    /** The note being recorded tears (an active glitch) and / or bends (an active distortion). */
    private boolean tearing, bending;
    /** Distortion strength of the note being recorded, and its head: where its waves are anchored (world). */
    private float bendStrength, bendX, bendY;
    /** Second of the water wobble's loop in this frame ({@link VeilRenderer#loopTime}). */
    private float bendT;
    private final TextureRegion core;
    private final TextureRegion chip;
    /** Note skins of the 2026-11-05 look ({@link Draw#skin}). */
    private final Draw.Skin skGround, skSky, skNeutral, skDragBar, skDragDiamond, skMine, skTear, skEndFill,
        skEndLine, skEndGlow, skSideGlow, skRim, skBar;
    /** Song seconds of this frame: the flow of the light bars inside STREAM bodies ({@link #paintBars}). */
    private float ribbonClock;
    private final TextureRegion disc;
    private final TextureRegion dot;
    private final Color col = new Color();
    private final Color col2 = new Color();
    private final float[] bandQuad = new float[20];
    /** Rotated nine-slice and polygon quads. */
    private final float[] verts = new float[9 * 20];
    private final float coreU;
    private final float coreV;
    /** The floating notes (CHART_FORMAT §11), drawn after the notes on the lines. */
    private final FloatNotes floats;
    /** The custom lines (自定义线条, CHART_FORMAT §13): under the notes, or over them when they ask. */
    private final StrokeRenderer strokes;

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
    /** {@link Settings#hitPointPx} of this frame (0 = its line through a key's middle at the judgement time). */
    private float hitPoint = Settings.HIT_POINT_DEFAULT;
    private boolean high;
    /** How notes that share their beat are marked ({@link Settings#syncHint}). */
    private int syncHint = Settings.SYNC_HINT_HIGHLIGHT;
    /**
     * soft_dot's and disc's fall-off from the centre up (one texel wide) and their upper-right quarters: the soft
     * halos and the solid borders of the sync highlight.
     */
    private TextureRegion dotSlice, dotQuarter, discSlice, discQuarter, gaussSlice;

    private final float[] gx = new float[GLOW_MAX];
    private final float[] gy = new float[GLOW_MAX];
    private final float[] gw = new float[GLOW_MAX];
    private final float[] gh = new float[GLOW_MAX];
    private final float[] grot = new float[GLOW_MAX];
    private final float[] gcol = new float[GLOW_MAX];
    /** Halo width of a {@link #G_FRAME} glow. */
    private final float[] gpad = new float[GLOW_MAX];
    /**
     * Sides of the ground TIDE line glows ({@link #lineGlow}): first point, point count, packed colour, reach,
     * normal.
     */
    private final int[] crestAt = new int[CREST_MAX];
    private final int[] crestN = new int[CREST_MAX];
    private final float[] crestCol = new float[CREST_MAX];
    private final float[] crestPad = new float[CREST_MAX];
    private final float[] crestNx = new float[CREST_MAX];
    private final float[] crestNy = new float[CREST_MAX];
    private final float[] cpx = new float[CREST_PTS];
    private final float[] cpy = new float[CREST_PTS];
    /**
     * How far the TIDE being painted has flowed, in waves ({@link Note#waveSpeed()} × beat; 0 outside
     * {@link #drawTide} and trail copies); {@link #beadShift} is the same for the pearls, in pearl steps.
     */
    float wavePhase;
    /**
     * Paints the TIDE shapes ({@link #paintTideT4}, {@link #paintTideBand}, {@link #paintTideCap}) in the mine red:
     * set only around the paint calls of a TIDE MINE or TIDE STREAM MINE and their afterimage copies.
     */
    boolean mineTint;
    private float beadShift;

    // a TIDE drawn turned about its pivot, between tideTurn and tideTurnEnd (Kelvin 2026-10-16)
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
    // the distortion of the note that added each glow: strength (0: none) and anchor; see glitchEnd()
    private final float[] gbend = new float[GLOW_MAX * 3];
    private int glows;

    // scratch geometry of the last computed note (see geometry())
    private float sx;
    private float sy;
    private float sw;
    private float sh;
    private float srot;
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
    // a STREAM body's knock-outs (rows in holeLo / holeHi): each one's x span, and the round far end of the sky
    // body making it (local row of its tip, ±1 toward the rows it widens into, its length, 0 for a square hole;
    // the sky body's half width), so that the hole follows its curve (see fillAround)
    private final float[] holeX0s = new float[NoteShapes.MAX_HOLES];
    private final float[] holeX1s = new float[NoteShapes.MAX_HOLES];
    private final float[] holeTip = new float[NoteShapes.MAX_HOLES];
    private final float[] holeTipDir = new float[NoteShapes.MAX_HOLES];
    private final float[] holeCap = new float[NoteShapes.MAX_HOLES];
    private final float[] holeSkyHr = new float[NoteShapes.MAX_HOLES];
    /** Rows where {@link #fillAround} cuts a body: its ends, and each hole's ends and the rows of its round end. */
    private final float[] cuts = new float[2 + (NoteShapes.HOLE_CURVE_ROWS + 3) * NoteShapes.MAX_HOLES];
    // fillAround's affine map of the piece being filled: u and v across and along, colour and opacity along
    private float fTexU;
    private float fSu;
    private float fXa;
    private float fTexV;
    private float fSv;
    private float fYa;
    private float fR;
    private float fG;
    private float fB;
    private float fA;
    private float fSa;

    // sky bodies of this frame (world rows), for the ground knock-outs
    private int skyRibs;
    private final float[] skyRibKeyW = new float[MAX_SKY];
    private final float[] skyRibLo = new float[MAX_SKY];
    private final float[] skyRibHi = new float[MAX_SKY];
    private final float[] skyRibCx = new float[MAX_SKY];
    private final float[] skyRibW = new float[MAX_SKY];
    private final boolean[] skyRibTipLow = new boolean[MAX_SKY];
    private final float[] skyRibCap = new float[MAX_SKY];
    private int skyBands;
    private final float[] skyBandLo = new float[MAX_SKY];
    private final float[] skyBandHi = new float[MAX_SKY];

    // rotated nine-slice grid and the LANE MINE polygons (local and world stages; a clip adds a vertex)
    private final float[] gxs = new float[4];
    private final float[] gys = new float[4];
    private final float[] gus = new float[4];
    private final float[] gvs = new float[4];
    private final float[] pa = new float[40];
    private final float[] pb = new float[40];
    private final float[] pc = new float[40];
    private final float[] pd = new float[40];
    /** A LANE MINE column's round far end ({@link #endPolygon}). */
    private final float[] pe = new float[12];
    private float gapLo = Float.NaN;
    private float gapHi = Float.NaN;
    private float visLo;
    private float visHi;

    /** Creates the renderer over the shared atlas. */
    NoteRenderer(Draw draw) {
        this.draw = draw;
        this.atlas = draw.batch();
        this.batch = atlas;
        core = new TextureRegion(draw.white(), 1, 1, 2, 2);
        chip = draw.region(Draw.CHIP_9P);
        skGround = draw.skin(Draw.SKIN_DROP_GROUND);
        skSky = draw.skin(Draw.SKIN_DROP_SKY);
        skNeutral = draw.skin(Draw.SKIN_DROP_NEUTRAL);
        skDragBar = draw.skin(Draw.SKIN_DRAG_BAR);
        skDragDiamond = draw.skin(Draw.SKIN_DRAG_DIAMOND);
        skMine = draw.skin(Draw.SKIN_MINE);
        skTear = draw.skin(Draw.TEAR);
        skEndFill = draw.skin(Draw.HOLD_END_FILL);
        skEndLine = draw.skin(Draw.HOLD_END_LINE);
        skEndGlow = draw.skin(Draw.HOLD_END_GLOW);
        skSideGlow = draw.skin(Draw.HOLD_SIDE_GLOW);
        skRim = draw.skin(Draw.HOLD_RIM);
        skBar = draw.skin(Draw.HOLD_BAR);
        disc = draw.disc();
        dot = draw.softDot();
        coreU = 0.5f * (core.getU() + core.getU2());
        coreV = 0.5f * (core.getV() + core.getV2());
        floats = new FloatNotes(draw, this);
        strokes = new StrokeRenderer(draw);
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

    /** Sets the VISUAL HIT POINT for this frame ({@link Settings#hitPointPx}; not set: the default, 0). */
    void setHitPoint(float px) {
        hitPoint = MathUtils.clamp(px, Settings.HIT_POINT_MIN, Settings.HIT_POINT_MAX);
    }

    /**
     * @return how far every note is drawn along its path toward its line (negative: back from it) for the VISUAL HIT
     *         POINT: positions are measured to a key's edge, so half a size-1.0 key less the setting, scaled with the
     *         key, puts a TAP or HOLD key's midline {@code hitPointPx × size} from its line at the judgement time (the
     *         midline travels the distance itself, 1080 to 0 at the default 0); 0 at a setting of 20 (the edge on
     *         the line)
     */
    float hitShift() {
        return (NOTE_H * 0.5f - hitPoint) * noteSize;
    }

    /**
     * Sets how notes that share their beat are marked (Kelvin 2026-10-13): {@link Settings#SYNC_HINT_HIGHLIGHT} a
     * border and halo (white; deep gold on sky notes; light red on mines; round each pearl of a sky TIDE; a ground
     * TIDE's wave line turns white instead),
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
     * Starts recording note i's sprites when one of its glitch segments is active at {@code beat} with some intensity
     * (CHART_FORMAT_v4 §6.9) or it is distorted there (§6.11).
     *
     * @return true when the note glitches or bends; then {@link #glitchEnd()} must follow its drawing
     */
    private boolean glitchBegin(GameEngine e, int i, Note n, float beat) {
        if (!glitchStart(i, n, beat)) return false;
        if (bending) bendAnchor(e, i, n, beat);
        return true;
    }

    /**
     * Sets up note i's glitch and bend at {@code beat} and starts recording its sprites when it does either.
     *
     * @return false (nothing started) when it neither glitches nor bends
     */
    private boolean glitchStart(int i, Note n, float beat) {
        tearing = tearAt(i, n, beat);
        bendStrength = n.distortions().isEmpty() ? 0f
            : NoteDistortion.strengthAt(n.distortions(), beat, NoteDistortion.NOTE_BASE);
        bending = bendStrength > 0.001f;
        if (!tearing && !bending) return false;
        glitch.begin(atlas);
        batch = glitch;
        glowMark = glows;
        return true;
    }

    /**
     * Floating note i ({@link FloatNotes}): starts recording what {@link Draw} draws when one of its glitch segments
     * or its distortion is on at {@code beat}, its waves anchored at its centre (x, y).
     *
     * @return true when it records; then {@link #floatGlitchEnd()} must follow its drawing
     */
    @Override
    public boolean floatGlitchBegin(int i, Note n, float beat, float x, float y) {
        if (!glitchStart(i, n, beat)) return false;
        bendX = x;
        bendY = y;
        draw.target(glitch);
        return true;
    }

    /** Replays a floating note recorded since {@link #floatGlitchBegin}, torn or bent, into the batch. */
    @Override
    public void floatGlitchEnd() {
        draw.target(null);
        glitchEnd();
    }

    /**
     * The key on a line that a floating note on its beat joins (sync, Kelvin 2026-11-06 19:03): where note k is drawn
     * now. {@code out} gets the centre of its box (x, y), half its width and height, its turn (degrees) and its
     * opacity.
     *
     * @return false when it is not drawn now ({@code out} unchanged)
     */
    @Override
    public boolean lineKey(GameEngine e, int k, Note n, float beat, float[] out) {
        if (entry.drawFrom(k) > beat || !geometry(e, n, beat)) return false;
        float a = n.shownOpacityAt(entry.look(k, beat)) * fadeIn(k, e.lineY(n.line()), n.isUpper());
        if (a <= MIN_ALPHA) return false;
        // the box is above its leading edge (sx, sy) in its own frame
        out[0] = sx - MathUtils.sinDeg(srot) * 0.5f * sh;
        out[1] = sy + MathUtils.cosDeg(srot) * 0.5f * sh;
        out[2] = 0.5f * sw;
        out[3] = 0.5f * sh;
        out[4] = srot;
        out[5] = a;
        return true;
    }

    /** @return true when a glitch segment of note i is active at {@code beat} with some intensity (then set up). */
    private boolean tearAt(int i, Note n, float beat) {
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
        return true;
    }

    /**
     * Anchors the waves of a bending note at its head: where the key is (the window's centre for a TIDE) where its
     * leading edge is, upright. A turned TIDE records in its own frame (the batch is turned), so this is its
     * head in that frame too; its glows turn the anchor with them ({@link #glitchEnd}).
     */
    private void bendAnchor(GameEngine e, int i, Note n, float beat) {
        LaneLayout lay = e.layout();
        bendX = !n.hasX() ? 0.5f * (lay.windowLeft() + lay.windowRight()) : lay.noteX(n, entry.look(i, beat));
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) pos = 0f;
        float lineY = e.lineY(n.line());
        bendY = n.isUpper() ? lineY - pos : lineY + pos;
        if (n.orbit() != null) { // a key on its orbit bends where it is drawn
            float lb = entry.look(i, beat);
            boolean mine = n.type() == NoteType.MINE;
            float rot = n.angleAt(lb) + (n.isUpper() ? 180f : 0f);
            float h = mine ? mineHeight() : noteHeight();
            float x = NoteShapes.anchorX(n, lb, bendX, bendY, pos, mine ? MINE_H : NOTE_H, h, rot);
            bendY = NoteShapes.anchorY(n, lb, bendX, bendY, pos, mine ? MINE_H : NOTE_H, h, rot);
            bendX = x;
        }
    }

    /** Replays the recorded note, bent and with its glitch, into the atlas batch. */
    private void glitchEnd() {
        batch = atlas;
        boolean drew = glitch.recorded() > 0;
        if (bending) glitch.bend(bendX, bendY, bendStrength, bendT, Field.shapeScale());
        if (tearing) {
            if (drew) glitchedNotes++;
            glitchQuads += glitch.emit(glitchSeed, glitchIntensity, glitchSplit, glitchSlice, glitchFlicker);
        } else {
            glitch.emitPlain();
        }
        if (!drew) return;
        // its glows (sync frame, glow) bend with the same waves and tear along the same strips in the additive pass
        float ax = bendX;
        float ay = bendY;
        if (tideTurned) { // they are collected turned (addGlow)
            float dx = ax - ttPx;
            float dy = ay - ttPy;
            ax = ttPx + ttCos * dx - ttSin * dy;
            ay = ttPy + ttSin * dx + ttCos * dy;
        }
        for (int k = glowMark; k < glows; k++) {
            int b = k * 3;
            gbend[b] = bending ? bendStrength : 0f;
            gbend[b + 1] = ax;
            gbend[b + 2] = ay;
            gglitch[k] = tearing;
            if (!tearing) continue;
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
        LaneLayout lay = e.layout();
        for (int i = start; i < end; i++) {
            if (e.noteState(i) != GameEngine.STATE_PENDING) continue;
            Note n = notes[i];
            if (n.fake() || n.floats()) continue; // a decoration lights nothing; a floating note has no line
            double dt = n.judgementMs() - vis;
            if (dt <= 0.0 || dt > APPROACH_MS) continue;
            float k = 1f - (float) dt / APPROACH_MS;
            if (n.isTideMine()) continue;
            if (n.type() == NoteType.UNIVERSAL) {
                field.tideApproach(n.line(), k);
                continue;
            }
            // the line lights where the key will be judged
            float ax = lay.noteX(n, n.judgement());
            if (n.type() == NoteType.MINE) field.approach(n.line(), ax, lay.noteWidth(n), 0.20f * k, true);
            else field.approach(n.line(), ax, lay.noteWidth(n), 0.25f * k, false);
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
        int pattern = lay.patternLanes();
        int q = 0;
        int groundHolds = 0;
        int skyHolds = 0;
        int groundBands = 0;
        int skyBandCount = 0;
        q += strokes.quads(e);
        for (int i = start; i < end; i++) {
            Note n = notes[i];
            if (entry.drawFrom(i) > beat) continue;
            if (n.floats()) {
                q += floats.quads(e, i, n, beat);
                continue;
            }
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
                    if (lay.noteWidth(n) < LaneLayout.MIN_ACTIVE_WIDTH) continue;
                    if (n.isLaneMine()) q += laneMineBody(e, i, n, beat, 0, true);
                    nq = (n.isLaneMine() ? LANE_MINE_HEAD_QUADS : MINE_QUADS)
                        + (NoteShapes.showsPearl(n, st) ? SYNC_QUADS : 0);
                    // any rotation; a LANE MINE's head bar is wider than the body
                    float pad = 0.5f * lay.noteWidth(n) * (n.isLaneMine() ? 1f : GameEngine.MINE_BOX);
                    far = mineHeight() + pad;
                    // as drawMine: a body turned toward its line also reaches its height
                    near = FLAT_CULL + pad + turnReach(up, n.angleAt(lb) + (up ? 180f : 0f), 0f, mineHeight());
                    break;
                }
                case UNIVERSAL:
                case TIDE_MINE: {
                    if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
                    boolean stream = n.hasTideBand();
                    boolean bar = tideBar(i, n, st, beat);
                    if (!bar && !stream) continue;
                    if (lay.windowRight() - lay.windowLeft() <= 2f * TIDE_INSET) continue;
                    float tail = stream ? tailDraw(n, lb) : 0f;
                    if (stream && st != GameEngine.STATE_PENDING && tail <= 0f && beat > n.endBeat()) continue;
                    bandBody = stream && tail > 0f;
                    nq = (bar ? NoteShapes.t4Quads(pattern, up) : 0)
                        + (bar && NoteShapes.showsPearl(n, st) ? NoteShapes.t4PearlQuads(pattern, up) : 0)
                        + (bandBody ? BAND_QUADS : 0);
                    if (nq == 0) continue;
                    far = Math.max(tideReach(), tail);
                    near = FLAT_CULL;
                    break;
                }
                default: {
                    if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
                    if (lay.noteWidth(n) < LaneLayout.MIN_ACTIVE_WIDTH) continue;
                    boolean hold = n.type() == NoteType.HOLD;
                    float tail = hold ? tailDraw(n, lb) : 0f;
                    if (hold && st != GameEngine.STATE_PENDING && tail <= 0f && beat > n.endBeat()) continue;
                    holdBody = hold && tail > 0f;
                    boolean drag = n.type() == NoteType.DRAG;
                    nq = (hold ? HOLD_QUADS + barBound(tail) : drag ? DRAG_QUADS : TAP_QUADS)
                        + (!drag && NoteShapes.showsPearl(n, st) ? SYNC_QUADS : 0);
                    far = noteHeight() + tail + LANE_CULL;
                    near = LANE_CULL;
                    if (entry.entering(i)) { // as drawLaneNote: a slide keeps what its turned body reaches
                        float rot = n.angleAt(lb) + (up ? 180f : 0f);
                        float w = headWidth(lay.noteWidth(n));
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
            if (n.type().inLane()) { // where drawLaneNote and drawMine put it
                boolean mine = n.type() == NoteType.MINE;
                y = NoteShapes.anchorY(n, lb, lay.noteX(n, lb), y, pos,
                    mine ? MINE_H : NOTE_H, mine ? mineHeight() : noteHeight(), n.angleAt(lb) + (up ? 180f : 0f));
            }
            // as drawTide and drawBand: a turned TIDE is not culled
            boolean turnedTide = !n.type().inLane() && NoteShapes.turned(n.angleAt(lb));
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

    /** @return thickness of a TIDE's notional bar (its pivot's origin sits halfway up it) at the size setting. */
    float tideHeight() {
        return 18f * noteSize;
    }

    /** @return how far a TIDE head (the T4 shape of a drop or TIDE STREAM) reaches behind its leading edge. */
    float tideReach() {
        return NoteShapes.T4_REACH * noteSize;
    }

    /**
     * @return true when TIDE note i draws a head in state {@code st} at {@code beat}: a pending group
     *         (same line and beat) draws one head per look ({@link #tideHead}); a TIDE STREAM that runs
     *         or broke draws its own (pinned) head; a missed drop group keeps its heads; a decoration
     *         ({@link Note#fake()}) always draws its own
     */
    private boolean tideBar(int i, Note n, byte st, float beat) {
        if (n.isTideMine()) return true; // a TIDE MINE is never part of a TIDE group
        if (st == GameEngine.STATE_PENDING) return tideHead(i, n, beat);
        return n.isTideStream() || tideHead(i, n, beat);
    }

    /**
     * @return true when TIDE note i draws its own head at {@code beat}: it heads its group (same line
     *         and beat), is a decoration, or the member before it is not drawn yet (its window opens
     *         later) or looks different now (position, angle, pivot, opacity or wave; Kelvin 2026-11-02:
     *         lightning lines); members that look alike draw one head
     */
    private boolean tideHead(int i, Note n, float beat) {
        if (i == 0 || n.fake()) return true; // a decorative TIDE is drawn on its own (Kelvin 2026-11-02)
        Note p = notes[i - 1];
        if (!(p.type() == NoteType.UNIVERSAL && p.line() == n.line() && p.judgement() == n.judgement())) return true;
        if (entry.drawFrom(i - 1) > beat) return true;
        float lp = entry.look(i - 1, beat);
        float ln = entry.look(i, beat);
        return entry.position(i - 1, p, beat) != entry.position(i, n, beat) || p.angleAt(lp) != n.angleAt(ln)
            || p.pivotX() != n.pivotX() || p.pivotY() != n.pivotY() || p.waveSpeed() != n.waveSpeed()
            || p.shownOpacityAt(lp) != n.shownOpacityAt(ln);
    }

    private boolean done(GameEngine e, int i, double vis) {
        if (notes[i].floats()) return FloatNotes.done(e, i, notes[i], vis); // its hit effects last longer
        if (notes[i].fake()) return vis > notes[i].endMs() + 500.0; // never judged: gone after its end
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
        crests = 0;
        crestPts = 0;
        float beat = e.visualBeat();
        fadeBeat = beat;
        bendT = VeilRenderer.loopTime(now);
        int frame = (int) (now * 60f);
        ribbonClock = NoteShapes.songSeconds(e);
        collectSkyRibbons(e, beat);
        // TIDE heads first (T4 drops and TIDE STREAM heads): lane notes and mines read on top of them
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (n.type().inLane() || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(e, i, n, beat);
            drawTide(e, i, n, beat, frame);
            if (g) glitchEnd();
            tideTurnEnd();
        }
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (entry.drawFrom(i) > beat || !n.type().inLane() || n.floats()) continue;
            boolean g = glitchBegin(e, i, n, beat);
            if (n.type() == NoteType.MINE) drawMine(e, i, n, beat, now, frame);
            else drawLaneNote(e, i, n, beat, now, frame);
            if (g) glitchEnd();
        }
        // the floating notes over everything on the lines, then the custom lines that go over the notes
        floats.draw(e, notes, start, end, beat);
        strokes.draw(e, bendT, true);
        batch.setColor(Color.WHITE);
    }

    /**
     * Opacity factor of an unjudged DROP or TIDE past its time (Kelvin 2026-10-27): 1 at its judgement, falling
     * linearly to 0 at {@link GameEngine#TIER2_MS} late, the moment it is judged LOST.
     */
    static float lateFade(GameEngine e, Note n) {
        float late = (float) (e.judgeMs() - n.judgementMs());
        return late <= 0f ? 1f : 1f - MathUtils.clamp(late / GameEngine.TIER2_MS, 0f, 1f);
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
     * {@link Field#dist}, less {@link #hitShift()}), or NaN while it is parked at {@link Note#HIDDEN_POSITION} (tested
     * on the raw value).
     */
    private float drawPos(int i, Note n, float beat) {
        float p = entry.position(i, n, beat);
        return Math.abs(p) >= Note.HIDDEN_POSITION - 1f ? Float.NaN : Field.dist(p) - hitShift();
    }

    /** {@link #drawPos(int, Note, float)} by the note's chart index. */
    private float drawPos(Note n, float beat) {
        float p = entry.position(n, beat);
        return Math.abs(p) >= Note.HIDDEN_POSITION - 1f ? Float.NaN : Field.dist(p) - hitShift();
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
     * centre, {@code sw × sh} body, {@code srot} rotation.
     *
     * @return false when it is not drawable (hidden, narrower than {@link LaneLayout#MIN_ACTIVE_WIDTH})
     */
    private boolean geometry(GameEngine e, Note n, float beat) {
        LaneLayout lay = e.layout();
        float lw = lay.noteWidth(n);
        if (lw < LaneLayout.MIN_ACTIVE_WIDTH) return false;
        float pos = drawPos(n, beat);
        if (Float.isNaN(pos)) return false;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        float lb = entry.look(n, beat);
        sx = lay.noteX(n, lb);
        sy = up ? lineY - pos : lineY + pos;
        sw = lw * 5f / 6f * Math.min(noteSize, 1.15f);
        sh = NOTE_H * noteSize;
        srot = n.angleAt(lb) + (up ? 180f : 0f);
        float x = NoteShapes.anchorX(n, lb, sx, sy, pos, NOTE_H, sh, srot);
        sy = NoteShapes.anchorY(n, lb, sx, sy, pos, NOTE_H, sh, srot);
        sx = x;
        return true;
    }

    /**
     * The point a STREAM's hit effects start from at {@code beat} (DESIGN §5.9, Kelvin 2026-10-26 13:25): the point of
     * its key that crosses the line at the judgement ({@link #hitShift()} in from the leading edge, along the key),
     * wherever the base is drawn: its lane, its line, its distance from the line after the head and its turn.
     *
     * @param out receives x, y
     * @return false when the note is not drawn at {@code beat} (hidden, inactive lane); {@code out} is unchanged
     */
    boolean effectPoint(GameEngine e, Note n, float beat, float[] out) {
        if (!geometry(e, n, beat)) return false;
        float shift = hitShift();
        out[0] = sx - MathUtils.sinDeg(srot) * shift;
        out[1] = sy + MathUtils.cosDeg(srot) * shift;
        return true;
    }

    /**
     * Collects the sky STREAM bodies this frame draws upright (world rows from the head's middle, lane, centre,
     * width), for the ground bodies' knock-out (DESIGN §21.7). A turned body knocks nothing out.
     */
    private void collectSkyRibbons(GameEngine e, float beat) {
        skyRibs = 0;
        LaneLayout lay = e.layout();
        for (int i = start; i < end && skyRibs < MAX_SKY; i++) {
            Note n = notes[i];
            if (n.type() != NoteType.HOLD || !n.isUpper() || entry.drawFrom(i) > beat) continue;
            if (n.fake() && beat >= n.endBeat()) continue; // as drawLaneNote
            byte st = e.noteState(i);
            if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) continue;
            float kw = lay.noteWidth(n);
            if (kw < LaneLayout.MIN_ACTIVE_WIDTH) continue;
            float lb = entry.look(i, beat);
            float tail = tailDraw(n, lb);
            if (!(tail > 0f)) continue;
            float pos = drawPos(i, n, beat);
            if (Float.isNaN(pos)) continue;
            float rot = n.angleAt(lb) + 180f;
            if (Math.abs(MathUtils.sinDeg(rot)) > NoteShapes.UPRIGHT_SIN) continue;
            float lineY = e.lineY(n.line());
            if (n.shownOpacityAt(lb) * fadeIn(i, lineY, true) <= MIN_ALPHA) continue;
            float bx = lay.noteX(n, lb);
            // the body starts at the head's middle
            float y = NoteShapes.anchorY(n, lb, bx, lineY - pos, pos, NOTE_H, noteHeight(), rot)
                + MathUtils.cosDeg(rot) * 0.5f * noteHeight();
            float y2 = y + MathUtils.cosDeg(rot) * tail;
            int k = skyRibs++;
            skyRibKeyW[k] = kw;
            skyRibLo[k] = Math.min(y, y2);
            skyRibHi[k] = Math.max(y, y2);
            skyRibCx[k] = NoteShapes.anchorX(n, lb, bx, lineY - pos, pos, NOTE_H, noteHeight(), rot);
            skyRibW[k] = NoteShapes.ribbonWidth(kw, true);
            skyRibTipLow[k] = y2 < y; // its round far end, at the low world end or the high one
            skyRibCap[k] = Math.min(NoteShapes.RIBBON_END_LEN, tail);
        }
    }

    /**
     * Sets the knock-outs of a ground STREAM body (starting at {@code (x, y)}, the head's middle, turned
     * {@code rot}, {@code tail} long): the rows where a sky body in its place overlaps it, each hole's local x span
     * (the sky body plus {@link NoteShapes#SKY_HOLE_PAD} on each side) and the sky body's round far end, which the
     * hole follows. Holes that overlap each other are merged into one square hole over both.
     */
    private void ribbonHoles(Note n, float x, float y, float rot, float tail) {
        holes = 0;
        if (skyRibs == 0 || !(tail > 0f)) return;
        if (Math.abs(MathUtils.sinDeg(rot)) > NoteShapes.UPRIGHT_SIN) return;
        float dir = MathUtils.cosDeg(rot) > 0f ? 1f : -1f;
        // the round end's line reaches a little past the tip (its outer half and the sprite's margin)
        float len = tail + skEndLine.y1 - Draw.HOLD_END_LEN;
        for (int k = 0; k < skyRibs && holes < NoteShapes.MAX_HOLES; k++) {
            if (!GameEngine.samePlace(Math.abs(skyRibCx[k] - x), skyRibKeyW[k], skyRibKeyW[k])) continue;
            if (!NoteShapes.overlapRows(y, dir, len, skyRibLo[k], skyRibHi[k], ov)) continue;
            int h = holes++;
            holeLo[h] = ov[0];
            holeHi[h] = ov[1];
            float half = NoteShapes.holeHalf(skyRibW[k], NoteShapes.SKY_HOLE_PAD);
            float cx = (skyRibCx[k] - x) * dir; // local x of the sky body's centre
            holeX0s[h] = cx - half;
            holeX1s[h] = cx + half;
            holeTip[h] = ((skyRibTipLow[k] ? skyRibLo[k] : skyRibHi[k]) - y) * dir;
            holeTipDir[h] = skyRibTipLow[k] ? dir : -dir;
            holeCap[h] = skyRibCap[k];
            holeSkyHr[h] = 0.5f * skyRibW[k];
        }
        // in row order; overlapping holes become one square hole
        for (int k = 1; k < holes; k++) {
            for (int j = k; j > 0 && holeLo[j - 1] > holeLo[j]; j--) swapHoles(j - 1, j);
        }
        int m = 0;
        for (int k = 0; k < holes; k++) {
            if (m > 0 && holeLo[k] < holeHi[m - 1]) {
                holeHi[m - 1] = Math.max(holeHi[m - 1], holeHi[k]);
                holeX0s[m - 1] = Math.min(holeX0s[m - 1], holeX0s[k]);
                holeX1s[m - 1] = Math.max(holeX1s[m - 1], holeX1s[k]);
                holeCap[m - 1] = 0f;
            } else {
                if (m != k) swapHoles(m, k);
                m++;
            }
        }
        holes = m;
    }

    /** Swaps knock-outs {@code a} and {@code b} of the body being painted. */
    private void swapHoles(int a, int b) {
        swap(holeLo, a, b);
        swap(holeHi, a, b);
        swap(holeX0s, a, b);
        swap(holeX1s, a, b);
        swap(holeTip, a, b);
        swap(holeTipDir, a, b);
        swap(holeCap, a, b);
        swap(holeSkyHr, a, b);
    }

    private static void swap(float[] v, int a, int b) {
        float t = v[a];
        v[a] = v[b];
        v[b] = t;
    }

    private void drawLaneNote(GameEngine e, int i, Note n, float beat, float now, int frame) {
        byte st = e.noteState(i);
        if (st == GameEngine.STATE_HIT || st == GameEngine.STATE_COMPLETED) return;
        if (n.fake() && beat >= n.endBeat()) return; // a decoration is gone at its end (Kelvin 2026-11-02)
        if (!geometry(e, n, beat)) return;
        boolean up = n.isUpper();
        boolean hold = n.type() == NoteType.HOLD;
        boolean drag = n.type() == NoteType.DRAG;
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
        Color base = drag ? Theme.DRAG : up ? (hold ? Theme.SKY_HOLD : Theme.SKY_SIG) : Theme.GROUND_NOTE;
        float x = sx;
        float headMul = 1f;
        float grey = 0f;
        boolean heldNow = hold && e.isHoldHeld(i);
        float ribHead = NoteShapes.ribbonHeadAlpha(heldNow);
        float ribTail = NoteShapes.ribbonTailAlpha(heldNow);
        boolean lost = st == GameEngine.STATE_LOST;
        if (!hold) {
            if (lost) return; // a missed DROP has faded out by its LOST
            alpha *= lateFade(e, n);
            if (alpha <= MIN_ALPHA) return;
        }
        if (lost) {
            float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
            if (Float.isNaN(age)) age = 0f;
            float k = MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
            Theme.mix(base, Theme.MISSED, k, col);
            grey = k;
            headMul = 1f - 0.70f * k;
            ribHead += (NoteShapes.RIBBON_BROKEN_HEAD_A - ribHead) * k;
            ribTail += (NoteShapes.RIBBON_BROKEN_TAIL_A - ribTail) * k;
            if (age < JITTER_MS) x += Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
        } else {
            col.set(base);
        }
        float rot = srot;
        float y = sy;
        float w = sw;
        float h = sh;
        float rw = hold ? NoteShapes.ribbonWidth(e.layout().noteWidth(n), up) : 0f;
        if (hold && !up) { // the body starts at the head's middle
            ribbonHoles(n, x - MathUtils.sinDeg(rot) * 0.5f * h, y + MathUtils.cosDeg(rot) * 0.5f * h, rot, tail);
        }
        if (drag) paintDrag(x, y, w, h, rot, grey, alpha);
        else paintLaneNote(x, y, w, h, rot, up, grey, col, alpha, headMul, ribHead, ribTail, tail, rw, heldNow, true);
        holes = 0;
        float cos = fcos;
        float sin = fsin;
        float a = alpha * headMul;
        boolean sync = NoteShapes.showsPearl(n, st);
        boolean pearl = sync && syncPearls() && !drag;
        if (pearl) syncPearl(x, y, w, h, a);
        // glows (additive block); a pending key's own glow is in its skin
        float gcx = x - sin * h * 0.5f;
        float gcy = y + cos * h * 0.5f;
        if (sync && syncHalo()) {
            if (drag) dragLight(x, y, w, h, rot, a);
            else syncFrame(gcx, gcy, w, h, rot, n.isUpper(), a);
        }
        if (heldNow) {
            float pr = NoteShapes.headPearlRadius(NoteShapes.pearlRadius(noteSize), w);
            int k = glows;
            addGlow(G_NOTE, gcx, gcy, w + 56f, h + 40f, rot, col, 0.40f * alpha);
            // the pearls sit across the glow's centre line: leave them out, so the glow cannot pale their gold
            if (pearl && glows > k) rowGlowHoles(pr);
        }
    }

    /**
     * Upper bound of the quads a knock-out adds to a ground STREAM body ({@link #fillAround} over its fill, its round
     * end's fill and its round end's line): a plain piece after it in each of the three, and the pieces it is cut into
     * (at the {@link NoteShapes#HOLE_CURVE_ROWS} + 1 rows of the sky body's round end and where the ground body's
     * round end starts), each with two pieces beside it in the fill or in both of the round end's sprites.
     */
    static final int RIBBON_HOLE_QUADS = 3 + (NoteShapes.HOLE_CURVE_ROWS + 3) * 4;

    /**
     * Paints a DROP or STREAM (Kelvin 2026-11-05, 「按这页把水滴、水流…画进游戏，大小和现在一样」) in its local frame:
     * a STREAM's body ({@link #paintBody}) when {@code tail > 0}, from the head's middle; then the head, the baked
     * glass skin ({@link Draw#SKIN_DROP_GROUND} cyan, {@link Draw#SKIN_DROP_SKY} pearl white) with its small tear
     * pointing at the line. A missed look crosses the skin over to the grey ramp ({@link Draw#SKIN_DROP_NEUTRAL} in
     * {@link Theme#MISSED}) and the tear to that grey. Normal blend; leaves the local frame set to {@code rot}.
     *
     * @param x       leading-edge centre x
     * @param y       leading-edge centre y
     * @param w       head width
     * @param h       head height
     * @param rot     rotation in degrees (+180 for sky notes)
     * @param up      a sky note (the pearl skin)
     * @param grey    0..1 toward the missed grey
     * @param c       body colour (line colour, or mixed toward the missed grey)
     * @param alpha   note opacity
     * @param headMul head opacity factor (a broken hold fades its head)
     * @param ribHead body opacity at the head ({@link NoteShapes#ribbonHeadAlpha})
     * @param ribTail body opacity at the far end
     * @param tail    body length (0 = no body)
     * @param rw      body width ({@link NoteShapes#ribbonWidth})
     * @param held    held now (a brighter rim and brighter light bars)
     * @param bars    draw the light bars flowing toward the head (live notes; not afterimage copies)
     * @return quads submitted
     */
    int paintLaneNote(float x, float y, float w, float h, float rot, boolean up, float grey, Color c, float alpha,
                      float headMul, float ribHead, float ribTail, float tail, float rw, boolean held, boolean bars) {
        frame(rot);
        int quads = 0;
        if (tail > 0f && rw > 0f) {
            quads += paintBody(x - fsin * 0.5f * h, y + fcos * 0.5f * h, c, alpha, ribHead, ribTail, tail, rw, grey,
                held, bars);
        }
        float a = alpha * headMul;
        float s = h / NOTE_H;
        if (grey < 1f) {
            quads += skin(up ? skSky : skGround, x, y, w, s, 0f, Color.toFloatBits(1f, 1f, 1f, a * (1f - grey)));
        }
        if (grey > 0f) {
            Color m = Theme.MISSED;
            quads += skin(skNeutral, x, y, w, s, 0f, Color.toFloatBits(m.r, m.g, m.b, a * grey));
        }
        if (w >= TEAR_MIN_W) {
            Theme.mix(up ? Theme.SKY_TEAR : Theme.WHITE, Theme.MISSED, grey, col2);
            quads += plain(skTear, x, y, s, Color.toFloatBits(col2.r, col2.g, col2.b, a));
        }
        return quads;
    }

    /** Narrowest head that shows its tear. */
    private static final float TEAR_MIN_W = 24f;

    /**
     * A STREAM's body (Kelvin 2026-11-05, the mock-up's hold) in the current local frame from its start
     * {@code (ox, oy)} (the head's middle) for {@code tail} away from the line, {@code rw} wide: a fill fading from
     * {@code aHead} to {@code aTail} (split around the knock-outs set by {@link #ribbonHoles}) with its round far end
     * ({@link NoteShapes#RIBBON_END_LEN} long, {@link NoteShapes#ribbonEndHalfWidth}), a soft glow outside it in the
     * same colour, light bars flowing toward the head inside it and a white rim round it; afterimage copies leave
     * the bars out. The fill and the rim of the round end are cut around a knock-out too ({@link #endAround}).
     *
     * @return quads submitted
     */
    private int paintBody(float ox, float oy, Color c, float alpha, float aHead, float aTail, float tail, float rw,
                          float grey, boolean held, boolean bars) {
        float hr = 0.5f * rw;
        float cap = Math.min(NoteShapes.RIBBON_END_LEN, tail);
        float straight = tail - cap;
        float aH = aHead * alpha;
        float dA = (aTail - aHead) * alpha / tail;
        float aS = aH + dA * straight;
        float ky = cap / Draw.HOLD_END_LEN;
        float aTop = Math.max(0f, aS + dA * skEndFill.y1 * ky);
        int quads = 0;
        // the glow outside it
        float g = NoteShapes.RIBBON_GLOW_K;
        if (straight > 0f) {
            float g0 = Color.toFloatBits(c.r, c.g, c.b, g * aH);
            float g1 = Color.toFloatBits(c.r, c.g, c.b, g * aS);
            quads += strip(skSideGlow, ox, oy, hr, 1f, 0f, straight, g0, g1);
            quads += strip(skSideGlow, ox, oy, -hr, -1f, 0f, straight, g0, g1);
        }
        quads += endSprite(skEndGlow, ox, oy, hr, straight, ky, Color.toFloatBits(c.r, c.g, c.b, g * aS),
            Color.toFloatBits(c.r, c.g, c.b, g * aTop));
        // the fill: the straight part and the round end, leaving out the knock-outs under sky bodies
        if (straight > 0f) {
            quads += fillAround(core.getTexture(), ox, oy, -hr, hr, 0f, straight, coreU, coreU, coreV, coreV, c.r, c.g,
                c.b, aH, aS);
        }
        quads += endAround(skEndFill, ox, oy, hr, straight, ky, c.r, c.g, c.b, aS, aTop);
        if (bars) {
            float ba = (held ? NoteShapes.RIBBON_BAR_HELD_A : NoteShapes.RIBBON_BAR_A) * alpha * (1f - grey);
            if (ba > MIN_ALPHA) quads += paintBars(ox, oy, hr, straight, cap, tail, Color.toFloatBits(1f, 1f, 1f, ba));
        }
        // the white rim
        float rimA = (held ? NoteShapes.RIBBON_RIM_HELD_A : NoteShapes.RIBBON_RIM_A) * alpha * (1f - 0.5f * grey);
        float rc = Color.toFloatBits(1f, 1f, 1f, rimA);
        if (straight > 0f) {
            quads += strip(skRim, ox, oy, hr, 1f, 0f, straight, rc, rc);
            quads += strip(skRim, ox, oy, -hr, -1f, 0f, straight, rc, rc);
        }
        return quads + endAround(skEndLine, ox, oy, hr, straight, ky, 1f, 1f, 1f, rimA, rimA);
    }

    /**
     * {@link #endSprite} of a STREAM's round end fill or line ({@code r g b}, opacity {@code a0} where the end starts
     * and {@code a1} at the sprite's top), around the knock-outs ({@link #fillAround}).
     *
     * @return quads submitted
     */
    private int endAround(Draw.Skin k, float ox, float oy, float hr, float y0, float ky, float r, float g, float b,
                          float a0, float a1) {
        float kx = hr / Draw.HOLD_END_HALF_W;
        float v0 = k.vB + (k.vT - k.vB) * (-k.y0 / (k.y1 - k.y0));
        return fillAround(k.region.getTexture(), ox, oy, k.x0 * kx, k.x1 * kx, y0, y0 + k.y1 * ky, k.uA, k.uS, v0,
            k.vT, r, g, b, a0, a1);
    }

    /**
     * Fills the local box {@code [xa, xb] × [ya, yb]} of the body being painted with {@code tex} mapped onto it
     * ({@code u} from {@code ua} to {@code ub} across, {@code v} from {@code va} to {@code vb} along) in {@code r g b}
     * at opacity {@code a0} at {@code ya} to {@code a1} at {@code yb}, leaving out its knock-outs (DESIGN §21.7): the
     * rows are cut at each hole's ends and at five rows of the round far end of the sky body making it (spaced as the
     * square of the distance from its tip, so straight pieces follow the curve closely), and the pieces beside a
     * hole are drawn as trapezoids. Without knock-outs it is one quad.
     *
     * @return quads submitted (with knock-outs at most {@link #RIBBON_HOLE_QUADS} / 3 more for each)
     */
    private int fillAround(Texture tex, float ox, float oy, float xa, float xb, float ya, float yb, float ua, float ub,
                           float va, float vb, float r, float g, float b, float a0, float a1) {
        if (!(yb > ya) || !(xb > xa)) return 0;
        fTexU = ua;
        fSu = (ub - ua) / (xb - xa);
        fXa = xa;
        fTexV = va;
        fSv = (vb - va) / (yb - ya);
        fYa = ya;
        fR = r;
        fG = g;
        fB = b;
        fA = a0;
        fSa = (a1 - a0) / (yb - ya);
        int n = 0;
        cuts[n++] = ya;
        cuts[n++] = yb;
        for (int h = 0; h < holes; h++) {
            float lo = holeLo[h];
            float hi = holeHi[h];
            if (!(hi > ya && lo < yb)) continue;
            n = cut(n, lo, ya, yb);
            n = cut(n, hi, ya, yb);
            float cap = holeCap[h];
            for (int s = 0; s <= NoteShapes.HOLE_CURVE_ROWS && cap > 0f; s++) {
                float row = holeTip[h] + holeTipDir[h] * cap * NoteShapes.holeCurveRow(s);
                if (row > lo && row < hi) n = cut(n, row, ya, yb);
            }
        }
        for (int k = 1; k < n; k++) { // insertion sort: a few rows
            float v = cuts[k];
            int j = k - 1;
            while (j >= 0 && cuts[j] > v) {
                cuts[j + 1] = cuts[j];
                j--;
            }
            cuts[j + 1] = v;
        }
        int quads = 0;
        for (int i = 0; i + 1 < n; i++) {
            float r0 = cuts[i];
            float r1 = cuts[i + 1];
            if (!(r1 > r0)) continue;
            int h = holeAt(0.5f * (r0 + r1));
            if (h < 0) {
                fillQuad(tex, ox, oy, xa, r0, xa, r1, xb, r1, xb, r0);
                quads++;
                continue;
            }
            float cx = 0.5f * (holeX0s[h] + holeX1s[h]);
            float w0 = holeHalfAt(h, r0);
            float w1 = holeHalfAt(h, r1);
            float l0 = MathUtils.clamp(cx - w0, xa, xb);
            float l1 = MathUtils.clamp(cx - w1, xa, xb);
            float q0 = MathUtils.clamp(cx + w0, xa, xb);
            float q1 = MathUtils.clamp(cx + w1, xa, xb);
            if (l0 > xa || l1 > xa) {
                fillQuad(tex, ox, oy, xa, r0, xa, r1, l1, r1, l0, r0);
                quads++;
            }
            if (q0 < xb || q1 < xb) {
                fillQuad(tex, ox, oy, q0, r0, q1, r1, xb, r1, xb, r0);
                quads++;
            }
        }
        return quads;
    }

    /** Adds {@code row} to {@link #cuts} when it lies strictly inside {@code (ya, yb)}; @return the new count. */
    private int cut(int n, float row, float ya, float yb) {
        if (row > ya && row < yb && n < cuts.length) cuts[n++] = row;
        return n;
    }

    /** @return the knock-out of the body being painted over local row {@code row}, or −1 */
    private int holeAt(float row) {
        for (int h = 0; h < holes; h++) {
            if (row >= holeLo[h] && row <= holeHi[h]) return h;
        }
        return -1;
    }

    /** @return half width of knock-out {@code h} at local row {@code row}: narrower over its sky body's round end. */
    private float holeHalfAt(int h, float row) {
        return NoteShapes.holeHalfAt(row, 0.5f * (holeX1s[h] - holeX0s[h]), holeSkyHr[h], holeTip[h], holeTipDir[h],
            holeCap[h]);
    }

    /** Draws the local quad (x0, y0) … (x3, y3) with {@link #fillAround}'s map. */
    private void fillQuad(Texture tex, float ox, float oy, float x0, float y0, float x1, float y1, float x2, float y2,
                          float x3, float y3) {
        int k = fillVert(0, ox, oy, x0, y0);
        k = fillVert(k, ox, oy, x1, y1);
        k = fillVert(k, ox, oy, x2, y2);
        fillVert(k, ox, oy, x3, y3);
        batch.draw(tex, verts, 0, 20);
    }

    private int fillVert(int k, float ox, float oy, float lx, float ly) {
        float a = MathUtils.clamp(fA + fSa * (ly - fYa), 0f, 1f);
        return vert(k, ox, oy, lx, ly, Color.toFloatBits(fR, fG, fB, a), fTexU + fSu * (lx - fXa),
            fTexV + fSv * (ly - fYa));
    }

    /**
     * The light bars inside a STREAM body (the mock-up's: {@link NoteShapes#RIBBON_BAR_W} of the body wide, 7 tall,
     * {@link NoteShapes#RIBBON_BAR_GAP} apart, counted from the far end and flowing toward the head at
     * {@link NoteShapes#RIBBON_BAR_SPEED} of song time, so they stand still while the song is paused); only the rows on
     * screen, narrowed inside the round end, none across a knock-out.
     *
     * @return quads submitted (at most {@link #barBound})
     */
    private int paintBars(float ox, float oy, float hr, float straight, float cap, float tail, float c) {
        float half = 0.5f * NoteShapes.BAR_H;
        if (!visibleRows(ox, oy, hr, tail)) return 0;
        float lo = visLo - half;
        float hi = visHi + half;
        float gap = NoteShapes.RIBBON_BAR_GAP;
        float off = NoteShapes.barOffset(ribbonClock);
        float bw = NoteShapes.RIBBON_BAR_W * hr;
        Draw.Skin k = skBar;
        int quads = 0;
        for (int b = Math.max(0, (int) Math.ceil((tail - off - hi) / gap)); ; b++) {
            float yy = tail - off - b * gap;
            if (yy - half < 0f || yy < lo) break;
            float bh = bw;
            float top = yy + half;
            if (top > straight) bh = Math.min(bh, NoteShapes.ribbonEndHalfWidth(top - straight, hr, cap) - BAR_INSET);
            if (bh < half || barInHole(yy - half, yy + half)) continue;
            // the sprite's bar spans [0, 100] × [0, 7] of its units
            float kx = 2f * bh / NoteShapes.BAR_SPRITE_W;
            int q = vert(0, ox, oy, -bh + k.x0 * kx, yy - half + k.y0, c, k.uA, k.vB);
            q = vert(q, ox, oy, -bh + k.x0 * kx, yy - half + k.y1, c, k.uA, k.vT);
            q = vert(q, ox, oy, -bh + k.x1 * kx, yy - half + k.y1, c, k.uS, k.vT);
            vert(q, ox, oy, -bh + k.x1 * kx, yy - half + k.y0, c, k.uS, k.vB);
            batch.draw(k.region.getTexture(), verts, 0, 20);
            quads++;
        }
        return quads;
    }

    /** How far a light bar stays inside a STREAM's round end. */
    private static final float BAR_INSET = 1.5f;

    /** @return true when the rows {@code [r0, r1]} of the body being painted cross one of its knock-outs. */
    private boolean barInHole(float r0, float r1) {
        for (int k = 0; k < holes; k++) {
            if (holeHi[k] > r0 && holeLo[k] < r1) return true;
        }
        return false;
    }

    /**
     * @return upper bound of the light bars of a STREAM body {@code tail} long ({@link #paintBars}): no more than fit
     *         along it, nor along the screen's diagonal
     */
    static int barBound(float tail) {
        float diag = (float) Math.sqrt(Ui.worldW() * Ui.worldW() + (Ui.top() - Ui.bottom()) * (Ui.top() - Ui.bottom()));
        return (int) (Math.min(tail, diag + 2f * NoteShapes.BAR_H) / NoteShapes.RIBBON_BAR_GAP) + 2;
    }

    /**
     * A strip of sprite {@code k} beside an edge at local x {@code e}: its x span ({@code x0 … x1} of the sprite)
     * laid outward ({@code sign} 1: to the right, −1: to the left), along the rows {@code [y0, y1]} with colour
     * {@code c0} at {@code y0} and {@code c1} at {@code y1}; the sprite's rows are all alike, so it is sampled
     * between them.
     *
     * @return quads submitted (1)
     */
    private int strip(Draw.Skin k, float ox, float oy, float e, float sign, float y0, float y1, float c0, float c1) {
        float xa = e + sign * k.x0;
        float xb = e + sign * k.x1;
        float v = 0.5f * (k.vB + k.vT);
        int q = vert(0, ox, oy, xa, y0, c0, k.uA, v);
        q = vert(q, ox, oy, xa, y1, c1, k.uA, v);
        q = vert(q, ox, oy, xb, y1, c1, k.uS, v);
        vert(q, ox, oy, xb, y0, c0, k.uS, v);
        batch.draw(k.region.getTexture(), verts, 0, 20);
        return 1;
    }

    /**
     * A STREAM far-end sprite ({@link Draw#HOLD_END_FILL} …) over the round end that starts {@code y0} along the body:
     * stretched to the body's half width {@code hr} and by {@code ky} along it, drawn from the end's start (the
     * sprite's rows below it only feed the filtering), colour {@code c0} there and {@code c1} at its top.
     *
     * @return quads submitted (1)
     */
    private int endSprite(Draw.Skin k, float ox, float oy, float hr, float y0, float ky, float c0, float c1) {
        float kx = hr / Draw.HOLD_END_HALF_W;
        float v0 = k.vB + (k.vT - k.vB) * (-k.y0 / (k.y1 - k.y0));
        float yt = y0 + k.y1 * ky;
        int q = vert(0, ox, oy, k.x0 * kx, y0, c0, k.uA, v0);
        q = vert(q, ox, oy, k.x0 * kx, yt, c1, k.uA, k.vT);
        q = vert(q, ox, oy, k.x1 * kx, yt, c1, k.uS, k.vT);
        vert(q, ox, oy, k.x1 * kx, y0, c0, k.uS, v0);
        batch.draw(k.region.getTexture(), verts, 0, 20);
        return 1;
    }

    /**
     * Paints a DRAG (Kelvin 2026-11-05, the mock-up's shape B) in its local frame, the box of a DROP: the baked thin
     * light-purple bar ({@link Draw#SKIN_DRAG_BAR}) across the box's width and its diamond
     * ({@link Draw#SKIN_DRAG_DIAMOND}, {@link #DRAG_DIAMOND} of the width at most) in the middle, both light at the
     * far edge and deep at the edge facing the line. A missed afterimage copy darkens toward {@link Theme#MISSED}.
     * Normal blend; leaves the local frame set to {@code rot}.
     *
     * @param x     leading-edge centre x
     * @param y     leading-edge centre y
     * @param w     box width (a DROP's head width)
     * @param h     box height
     * @param rot   rotation in degrees (+180 for sky notes)
     * @param grey  0..1 toward the missed grey
     * @param alpha note opacity
     * @return quads submitted ({@link #DRAG_QUADS} at most)
     */
    int paintDrag(float x, float y, float w, float h, float rot, float grey, float alpha) {
        Theme.mix(Theme.WHITE, Theme.MISSED, grey, col2);
        return dragSkins(x, y, w, h, rot, Color.toFloatBits(col2.r, col2.g, col2.b, alpha));
    }

    /** The bar and diamond of {@link #paintDrag} in packed colour {@code c}; @return quads submitted. */
    private int dragSkins(float x, float y, float w, float h, float rot, float c) {
        frame(rot);
        float s = h / NOTE_H;
        int quads = skin(skDragBar, x, y, w, s, 0f, c);
        // a narrow box keeps the diamond within its share of the width, centred where it was
        float ds = Math.min(s, DRAG_DIAMOND * w / Draw.DRAG_DIAMOND_W);
        return quads + skin(skDragDiamond, x, y, 0f, ds, 0.5f * NOTE_H * (s - ds), c);
    }

    /**
     * Adds the sync light of a DRAG at leading-edge centre {@code (x, y)}, box {@code w × h} turned {@code rot}:
     * its bar and diamond again in the additive block at {@link #DRAG_LIGHT_A} of {@code a}, so it is a little
     * brighter and its own glow a little stronger, with no frame round the box.
     */
    private void dragLight(float x, float y, float w, float h, float rot, float a) {
        addGlow(G_DRAG, x, y, w, h, rot, Theme.WHITE, DRAG_LIGHT_A * a);
    }

    /** Width of a DRAG's diamond as a share of the box width at most (72 of 200). */
    static final float DRAG_DIAMOND = 0.36f;

    /**
     * Writes and draws the quads of skin {@code k} for a body {@code w} wide at note size {@code s}, {@code dy} along
     * the current local frame from {@code (ox, oy)}: a stretchable skin as its cap, its column stretched and the cap
     * mirrored, or as the cap cut at the middle and mirrored when the body is too narrow for the column ({@code w}
     * 0: a centred shape baked as its left half); in packed colour {@code c}.
     *
     * @return quads submitted (2 or 3)
     */
    private int skin(Draw.Skin k, float ox, float oy, float w, float s, float dy, float c) {
        float hw = 0.5f * w;
        float l = -hw + k.x0 * s;
        float m = -hw + k.xs * s;
        float yb = dy + k.y0 * s;
        float yt = dy + k.y1 * s;
        int q;
        if (m < 0f) {
            q = skinQuad(0, ox, oy, l, m, yb, yt, k.uA, k.uS, k, c);
            q = skinQuad(q, ox, oy, m, -m, yb, yt, k.uC, k.uC, k, c);
            q = skinQuad(q, ox, oy, -m, -l, yb, yt, k.uS, k.uA, k, c);
        } else {
            float um = k.uA + (k.uS - k.uA) * (-l) / (m - l);
            q = skinQuad(0, ox, oy, l, 0f, yb, yt, k.uA, um, k, c);
            q = skinQuad(q, ox, oy, 0f, -l, yb, yt, um, k.uA, k, c);
        }
        batch.draw(k.region.getTexture(), verts, 0, q);
        return q / 20;
    }

    /** Writes plain sprite {@code k} at its place at note size {@code s} in the current local frame and draws it. */
    private int plain(Draw.Skin k, float ox, float oy, float s, float c) {
        skinQuad(0, ox, oy, k.x0 * s, k.x1 * s, k.y0 * s, k.y1 * s, k.uA, k.uS, k, c);
        batch.draw(k.region.getTexture(), verts, 0, 20);
        return 1;
    }

    /** Writes the local quad {@code [xa, xb] × [yb, yt]} of skin {@code k}, u {@code ua → ub}, at q; @return q + 20. */
    private int skinQuad(int q, float ox, float oy, float xa, float xb, float yb, float yt, float ua, float ub,
                         Draw.Skin k, float c) {
        q = vert(q, ox, oy, xa, yb, c, ua, k.vB);
        q = vert(q, ox, oy, xa, yt, c, ua, k.vT);
        q = vert(q, ox, oy, xb, yt, c, ub, k.vT);
        return vert(q, ox, oy, xb, yb, c, ub, k.vB);
    }

    /** Sets the local frame (rotation in degrees) of the next {@code local*} calls. */
    private void frame(float rot) {
        srot = rot;
        fcos = MathUtils.cosDeg(rot);
        fsin = MathUtils.sinDeg(rot);
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
     * The sync pearls of a DROP or STREAM head (Kelvin 2026-10-12 12:33, 「把中间的"黄色方块"换成黄色圆形，像珍珠一样」;
     * gold, 12:42; three side by side, 16:07): {@link NoteShapes#HEAD_PEARLS} gold pearls
     * {@link NoteShapes#PEARL_D} × noteSize across in a row across the middle of the head, centred,
     * {@link NoteShapes#PEARL_STEP} diameters apart (smaller on a head too narrow for them,
     * {@link NoteShapes#headPearlRadius}), turning with it at the head's alpha; call right after
     * {@link #paintLaneNote} (its local frame).
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
        if (n.fake() && beat >= n.endBeat()) return; // a decoration is gone at its end (Kelvin 2026-11-02)
        LaneLayout lay = e.layout();
        float lw = lay.noteWidth(n);
        if (lw < LaneLayout.MIN_ACTIVE_WIDTH) return;
        boolean up = n.isUpper();
        boolean laneMine = n.isLaneMine();
        float lineY = e.lineY(n.line());
        if (laneMine && !n.fake()) {
            // the closed place reads at a glance: its line segment glows while it blocks
            float b = NoteShapes.blockLook(e.judgeMs(), n.judgementMs(), n.endMs(), GameEngine.LANE_MINE_LEAD_MS);
            if (b > 0f) {
                addGlow(G_V, lay.noteX(n), lineY, lw, NoteShapes.LANE_MINE_LINE_GLOW_H, 0f, Theme.MINE,
                    NoteShapes.LANE_MINE_LINE_GLOW_A * b);
            }
        }
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) return;
        float lb = entry.look(i, beat);
        float mw = lw * GameEngine.MINE_BOX;
        float mh = mineHeight();
        float rot = n.angleAt(lb) + (up ? 180f : 0f);
        float bx = lay.noteX(n, lb);
        float by = up ? lineY - pos : lineY + pos;
        float y = NoteShapes.anchorY(n, lb, bx, by, pos, MINE_H, mh, rot);
        float cw = laneMine ? Math.max(mw, 2f * NoteShapes.laneMineHalfWidth(lw)) : mw; // a LANE MINE's bar is wider
        float pad = rotPad(rot, cw);
        // toward the line it reaches its turned corners, and its height when turned toward the line
        if (offScreen(up, y, mh + pad, FLAT_CULL + turnReach(up, rot, cw, mh))) return;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up);
        float x = NoteShapes.anchorX(n, lb, bx, by, pos, MINE_H, mh, rot);
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
        // a MINE or untouched LANE MINE that shares its beat: a light-red halo, three red pearls across its body
        boolean sync = NoteShapes.showsPearl(n, st);
        boolean pearl = sync && syncPearls();
        // the box the pearls, the halo and the pulse sit on: a MINE's body, a LANE MINE's head bar
        float bw = mw;
        float bh = mh;
        float bc = 0.5f * mh;
        if (laneMine) {
            paintLaneMineHead(x, y, lw, rot, alpha);
            bw = 2f * NoteShapes.laneMineHalfWidth(lw);
            bh = NoteShapes.LANE_MINE_HEAD_H * noteSize;
            bc = laneMineStart();
        } else {
            paintMine(x, y, mw, mh, rot, alpha, !pearl); // the pearls sit where the rivets are
        }
        float pr = NoteShapes.headPearlRadius(NoteShapes.minePearlRadius(noteSize), bw);
        float gx = x - fsin * bc;
        float gy = y + fcos * bc;
        if (sync && syncHalo()) {
            frameGlow(gx, gy, bw, bh, rot, Theme.MINE_HIGHLIGHT, HL_MINE_K * HL_BAND_A * alpha,
                HL_MINE_K * HL_HALO_A * alpha);
        }
        if (pearl) {
            pearlRow(x, y, bc, pr, Theme.MINE_PEARL, Theme.MINE_PEARL_RIM, Theme.MINE_PEARL_LUSTRE, alpha);
        }
        if (live) {
            float pulse = 0.26f + 0.08f * MathUtils.sin(now * MathUtils.PI2 * 2f);
            int k = glows;
            addGlow(G_NOTE, gx, gy, bw + 48f, bh + 40f, rot, Theme.MINE, pulse * alpha);
            // the red pulse leaves the pearls out, so they keep their coral bodies
            if (pearl && glows > k) rowGlowHoles(pr);
        }
    }

    /** {@link #paintMine(float, float, float, float, float, float, boolean)} with its rivets. */
    int paintMine(float x, float y, float mw, float mh, float rot, float alpha) {
        return paintMine(x, y, mw, mh, rot, alpha, true);
    }

    /**
     * Paints a MINE (Kelvin 2026-11-05, the mock-up's: 「暗红芯、亮红边，两头带尖，中间三颗铆钉」) in its local frame: the
     * baked body ({@link Draw#SKIN_MINE}: red spikes past both ends, a dark-red core with its red glow and rim) and,
     * with {@code rivets}, three {@link Theme#MINE_RIVET} rivets across its middle; turned by {@code rot} about the
     * leading-edge centre {@code (x, y)} (Kelvin 2026-10-12 03:03: mines turn like DROP and STREAM). Leaves the local
     * frame set to {@code rot}.
     *
     * @return quads submitted (at most {@link #MINE_QUADS})
     */
    int paintMine(float x, float y, float mw, float mh, float rot, float alpha, boolean rivets) {
        frame(rot);
        float s = mh / MINE_H;
        int q = skin(skMine, x, y, mw, s, 0f, Color.toFloatBits(1f, 1f, 1f, alpha));
        if (!rivets || mw < MINE_RIVET_MIN_W) return q;
        Color r = Theme.MINE_RIVET;
        batch.setColor(r.r, r.g, r.b, alpha);
        float size = MINE_RIVET_R * s / DISC_R;
        float ly = 0.5f * mh;
        for (int k = -1; k <= 1; k++) {
            float lx = k * MINE_RIVET_STEP * mw;
            batch.draw(disc, x + lx * fcos - ly * fsin - 0.5f * size, y + lx * fsin + ly * fcos - 0.5f * size, size,
                size);
        }
        return q + 3;
    }

    /** MINE rivets: radius at note size 1, spacing as a share of the body's width (48 of 200), narrowest with them. */
    private static final float MINE_RIVET_R = 4.2f, MINE_RIVET_STEP = 0.24f, MINE_RIVET_MIN_W = 40f;

    /** @return how far along a LANE MINE from its leading edge its head bar's middle and its column's start are. */
    float laneMineStart() {
        return 0.5f * NOTE_H * noteSize;
    }

    /**
     * Paints a LANE MINE's head (Kelvin 2026-11-05, the mock-up's long mine): a solid {@link Theme#MINE} bar across
     * its column, {@link NoteShapes#LANE_MINE_HEAD_H} tall with round ends, its middle {@link #laneMineStart()} from
     * the leading edge {@code (x, y)}, so it lies on the line while the place is closed. Leaves the local frame set to
     * {@code rot}.
     *
     * @return quads submitted (at most {@link #LANE_MINE_HEAD_QUADS})
     */
    int paintLaneMineHead(float x, float y, float lw, float rot, float alpha) {
        frame(rot);
        float hw = NoteShapes.laneMineHalfWidth(lw);
        if (!(hw > 0f)) return 0;
        float bh = NoteShapes.LANE_MINE_HEAD_H * noteSize;
        rotChip(x, y, -hw, laneMineStart() - 0.5f * bh, 2f * hw, bh, 0.5f * bh, Theme.MINE, alpha);
        return LANE_MINE_HEAD_QUADS;
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

    /** Upper bound of the quads of one {@link #paintMine}: its skin and three rivets. */
    static final int MINE_QUADS = 6;
    /** Upper bound of the quads of one {@link #paintLaneNote} without a body: the skin, the grey skin, the tear. */
    static final int TAP_QUADS = 7;
    /**
     * Upper bound of the quads of one {@link #paintLaneNote} with a body, no knock-out and no light bars
     * ({@link #barBound}): the head's, the body's glow (three), fill (two), rim (three).
     */
    static final int HOLD_QUADS = TAP_QUADS + 8;
    /** Upper bound of the quads of one {@link #paintDrag}: the bar's skin and the diamond's. */
    static final int DRAG_QUADS = 5;
    /** Upper bound of the quads of one {@link #paintLaneMineHead} (a rounded bar). */
    static final int LANE_MINE_HEAD_QUADS = 9;
    /**
     * Upper bound of the quads of a LANE MINE afterimage copy: its column without stripes and glow (the fill of the
     * straight part and of the round end, two side rims, five outline segments; each cut to the screen and split
     * at most in two around the other line) and the head bar.
     */
    static final int LANE_MINE_COPY_QUADS = 56 + LANE_MINE_HEAD_QUADS;

    /**
     * LANE MINE body in the band layer (DESIGN §20.2): the blocking veil over its lane's part of
     * its zone, then the column. With {@code dry} it only counts (the quad bound).
     *
     * @return quads submitted (or that would be)
     */
    private int laneMineBody(GameEngine e, int i, Note n, float beat, int frame, boolean dry) {
        LaneLayout lay = e.layout();
        float lw = lay.noteWidth(n);
        if (lw < LaneLayout.MIN_ACTIVE_WIDTH) return 0;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        int q = 0;
        float b = NoteShapes.blockLook(e.judgeMs(), n.judgementMs(), n.endMs(), GameEngine.LANE_MINE_LEAD_MS);
        if (b > 0f) {
            if (!dry) {
                Color m = Theme.MINE;
                batch.setColor(m.r, m.g, m.b, NoteShapes.LANE_MINE_VEIL_A * b);
                batch.draw(core, lay.noteX(n) - 0.5f * lw, lineY - Field.zoneHalf(), lw, 2f * Field.zoneHalf());
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
        float bx = lay.noteX(n, lb);
        float by = up ? lineY - pos : lineY + pos;
        float y = NoteShapes.anchorY(n, lb, bx, by, pos, MINE_H, mh, rot);
        float x = NoteShapes.anchorX(n, lb, bx, by, pos, MINE_H, mh, rot) + (dry ? 0f : contactJitter(e, i, frame));
        return q + paintLaneMineColumn(x, y, rot, lw, tail, alpha, NoteShapes.songSeconds(e),
            e.lineY(n.line().other()), true, dry);
    }

    /**
     * Paints a LANE MINE column (Kelvin 2026-11-05, the mock-up's long mine: 「一段和原来一条轨道差不多宽的红色斜纹带」)
     * in its local frame, turned by {@code rot}: from {@link #laneMineStart()} past the leading edge {@code (lx, ly)}
     * (its head bar's middle) for {@code tail} away from its line, {@link NoteShapes#laneMineHalfWidth} each side of
     * its centre: a deep-red fill ({@link Theme#LONG_MINE_FILL} at {@link NoteShapes#LANE_MINE_FILL_A}) with a soft red
     * glow beside it, 45° {@link Theme#MINE} hazard stripes drifting toward the line at
     * {@link NoteShapes#STRIPE_SPEED} of song time and a 3-unit red outline, the far end rounded
     * ({@link NoteShapes#LANE_MINE_END_R}, two segments a corner). Only the rows on screen are drawn, and nothing
     * within {@link NoteShapes#OTHER_LINE_GAP} of {@code otherY}, the other judgement line, which stays as bright
     * across the column as elsewhere (Kelvin 2026-10-12 02:58). Afterimage copies pass {@code detail = false} (no
     * stripes, no glow). Leaves the local frame set to {@code rot}.
     *
     * @param seconds song seconds of the stripe phase
     * @param dry     count only, draw nothing
     * @return quads submitted (or that would be)
     */
    int paintLaneMineColumn(float lx, float ly, float rot, float lw, float tail, float alpha, float seconds,
                            float otherY, boolean detail, boolean dry) {
        float hw = NoteShapes.laneMineHalfWidth(lw);
        if (hw <= NoteShapes.LANE_MINE_RIM || !(tail > 0f)) return 0;
        frame(rot);
        float c0 = laneMineStart();
        float ox = lx - fsin * c0;
        float oy = ly + fcos * c0;
        float ro = 0.5f * NoteShapes.LANE_MINE_RIM;
        float reach = hw + (detail ? NoteShapes.LANE_MINE_GLOW_OUT : ro);
        if (!visibleRows(ox, oy, reach, tail + ro)) return 0;
        float lo = visLo;
        float hi = visHi;
        gapLo = otherY - NoteShapes.OTHER_LINE_GAP;
        gapHi = otherY + NoteShapes.OTHER_LINE_GAP;
        float r = Math.min(NoteShapes.LANE_MINE_END_R, Math.min(tail, hw));
        float ls = tail - r; // where the round far end starts
        float sy0 = lo;
        float sy1 = Math.min(hi, ls);
        Color f = Theme.LONG_MINE_FILL;
        Color m = Theme.MINE;
        float cFill = Color.toFloatBits(f.r, f.g, f.b, NoteShapes.LANE_MINE_FILL_A * alpha);
        float cMine = Color.toFloatBits(m.r, m.g, m.b, alpha);
        int q = 0;
        if (detail && sy1 > sy0) { // the glow beside the sides, outside the outline
            float g1 = Color.toFloatBits(m.r, m.g, m.b, NoteShapes.LANE_MINE_GLOW_A1 * alpha);
            float g2 = Color.toFloatBits(m.r, m.g, m.b, NoteShapes.LANE_MINE_GLOW_A2 * alpha);
            float e1 = hw + ro;
            float e2 = hw + NoteShapes.LANE_MINE_GLOW_MID;
            float e3 = hw + NoteShapes.LANE_MINE_GLOW_OUT;
            q += rectPoly(ox, oy, e1, sy0, e2, sy1, g1, dry) + rectPoly(ox, oy, -e2, sy0, -e1, sy1, g1, dry);
            q += rectPoly(ox, oy, e2, sy0, e3, sy1, g2, dry) + rectPoly(ox, oy, -e3, sy0, -e2, sy1, g2, dry);
        }
        // the fill: the straight part, then the round end
        q += rectPoly(ox, oy, -hw, sy0, hw, sy1, cFill, dry);
        boolean end = hi > ls && r > 0f;
        if (end) {
            int n = endPolygon(hw, ls, r, pe);
            n = clip(pe, n, pb, 0f, -1f, -lo); // keep y ≥ lo
            n = clip(pb, n, pa, 0f, 1f, hi);   // keep y ≤ hi
            q += emitLocal(pa, n, ox, oy, cFill, dry);
        }
        if (detail) {
            float cStripe = Color.toFloatBits(m.r, m.g, m.b, NoteShapes.STRIPE_A * alpha);
            float p = NoteShapes.stripeAxisPeriod();
            float sw = NoteShapes.stripeAxisWidth();
            float off = NoteShapes.stripeOffset(seconds);
            int ne = end ? endPolygon(hw, ls, r, pe) : 0;
            float top = Math.min(hi, tail);
            // stripe k lies between the 45° lines y = x + c and y = x + c + sw, c = off + k·p
            int k0 = (int) Math.ceil((lo - hw - sw - off) / p);
            int k1 = (int) Math.floor((top + hw - off) / p);
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
                nv = clip(pb, nv, pa, 0f, 1f, top);     // keep y ≤ top
                if (ne > 0 && hw + c + sw > ls) nv = clipToEnd(nv, ne);
                q += emitLocal(pa, nv, ox, oy, cStripe, dry);
            }
        }
        // the outline: both sides, then round the far end
        if (sy1 > sy0) {
            q += rectPoly(ox, oy, -hw - ro, sy0, -hw + ro, sy1, cMine, dry);
            q += rectPoly(ox, oy, hw - ro, sy0, hw + ro, sy1, cMine, dry);
        }
        if (hi > ls - ro && r > 0f) {
            int n = endPolygon(hw, ls, r, pe);
            // the polygon's path from (hw, ls) round to (−hw, ls): its vertices 1 … n − 1, then vertex 0
            for (int v = 1; v < n; v++) {
                int w = v + 1 < n ? v + 1 : 0;
                q += segmentPoly(ox, oy, pe[2 * v], pe[2 * v + 1], pe[2 * w], pe[2 * w + 1], ro, lo, hi, cMine, dry);
            }
        }
        return q;
    }

    /**
     * Writes the round far end of a LANE MINE column into {@code p} (counter-clockwise, y up): the row {@code ls} it
     * starts at across the full width, then each corner of radius {@code r} as two segments, to the far end
     * {@code ls + r}.
     *
     * @return the vertex count (6)
     */
    private static int endPolygon(float hw, float ls, float r, float[] p) {
        float d = r * (1f - 0.70710677f);
        p[0] = -hw;
        p[1] = ls;
        p[2] = hw;
        p[3] = ls;
        p[4] = hw - d;
        p[5] = ls + r - d;
        p[6] = hw - r;
        p[7] = ls + r;
        p[8] = -hw + r;
        p[9] = ls + r;
        p[10] = -hw + d;
        p[11] = ls + r - d;
        return 6;
    }

    /**
     * Clips the polygon in {@link #pa} ({@code n} vertices) to the four corner edges of the round far end in
     * {@link #pe} ({@code ne} vertices, from {@link #endPolygon}; the half planes inside its edges 1 → 2, 2 → 3,
     * 4 → 5 and 5 → 0), so a stripe stops at the rounded corners. Leaves the result in {@link #pa}.
     *
     * @return the vertex count
     */
    private int clipToEnd(int n, int ne) {
        for (int e = 1; e < ne && n >= 3; e++) {
            if (e == 3) continue; // the far end's straight edge: the stripes stop at y ≤ tail already
            int f = e + 1 < ne ? e + 1 : 0;
            float x0 = pe[2 * e];
            float y0 = pe[2 * e + 1];
            float dx = pe[2 * f] - x0;
            float dy = pe[2 * f + 1] - y0;
            // inside a counter-clockwise edge: dy·x − dx·y ≤ dy·x0 − dx·y0
            n = clip(pa, n, pb, dy, -dx, dy * x0 - dx * y0);
            System.arraycopy(pb, 0, pa, 0, 2 * n);
        }
        return n;
    }

    /**
     * Emits a stroke {@code 2·half} wide along the local segment {@code (x0, y0) → (x1, y1)}, a little longer at both
     * ends so that segments meet without a notch, cut to the rows {@code [lo, hi]} (see {@link #emitLocal}).
     *
     * @return quads submitted (or that would be)
     */
    private int segmentPoly(float ox, float oy, float x0, float y0, float x1, float y1, float half, float lo, float hi,
                            float c, boolean dry) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (!(len > 0f)) return 0;
        dx /= len;
        dy /= len;
        float ex = dx * half * 0.5f;
        float ey = dy * half * 0.5f;
        float nx = -dy * half;
        float ny = dx * half;
        pb[0] = x0 - ex + nx;
        pb[1] = y0 - ey + ny;
        pb[2] = x0 - ex - nx;
        pb[3] = y0 - ey - ny;
        pb[4] = x1 + ex - nx;
        pb[5] = y1 + ey - ny;
        pb[6] = x1 + ex + nx;
        pb[7] = y1 + ey + ny;
        int n = clip(pb, 4, pa, 0f, -1f, -lo);
        n = clip(pa, n, pb, 0f, 1f, hi);
        return emitLocal(pb, n, ox, oy, c, dry);
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

    /**
     * Paints a TIDE drop or TIDE STREAM head in the T4 look (Kelvin 2026-10-12 04:36; {@link NoteShapes}) across
     * {@code [x0, x1]}
     * with its leading edge at {@code y}. Sky: a 2u {@code skySig} thread at α .45 7u behind the
     * leading edge and {@code 3 × lanes} pearls on it, radius 11u / 7u alternating. Ground: one
     * {@link NoteShapes#WAVE_LINE} {@code tide} wave line (Kelvin 2026-11-01: no band, no base line) at
     * {@code 20u + 9u·sin(phase)} behind the leading edge, from {@code 8 × lanes} vertical strips.
     * {@code u} is the note size; the phase moves with {@link #wavePhase} ({@code seconds} is unused). With
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
     * pearls, large and small in turn ({@link NoteShapes#rowPearlRadius}), strung on the wave line.
     * The thread and the wave are unchanged.
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
     * bottom tide沿着轮廓高亮」): on the sky line a deep gold halo and outline round each pearl; on the ground line the
     * wave line turns white, {@link NoteShapes#WAVE_LINE_MULTI} thick, with a stronger glow (2026-11-01).
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
            Color sig = mineTint ? Theme.MINE : Theme.SKY_SIG;
            Color pc = grey > 0f ? Theme.mix(sig, Theme.MISSED, grey, col2) : sig;
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
                    addGlow(G_DOT, cx, ty, gs, gs, 0f, gold ? Theme.SYNC_PEARL : sig,
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
        // one wave line (Kelvin 2026-11-01): white and thicker on a multi-press, red on a mine
        Color lc = mineTint ? Theme.MINE : halo ? Theme.SYNC_HIGHLIGHT : Theme.TIDE;
        if (grey > 0f) lc = Theme.mix(lc, Theme.MISSED, grey, col);
        float lw = (halo && !mineTint ? NoteShapes.WAVE_LINE_MULTI : NoteShapes.WAVE_LINE) * u;
        float cLine = Color.toFloatBits(lc.r, lc.g, lc.b, alpha);
        float half = lw * 0.5f;
        float wo = lay.patternOrigin();
        float mlw = lay.patternWidth();
        int strips = NoteShapes.waveStrips(lanes);
        float xa = ax0;
        float ca = NoteShapes.waveCrest(tideWin(x0) - wo, mlw, wavePhase, u);
        for (int k = 1; k <= strips; k++) {
            float xr = x0 + (x1 - x0) * k / strips;
            float xb = xr + jx;
            float cb = NoteShapes.waveCrest(tideWin(xr) - wo, mlw, wavePhase, u);
            flatQuad(xa, y + dir * (ca - half), xa, y + dir * (ca + half), xb, y + dir * (cb + half),
                xb, y + dir * (cb - half), cLine);
            xa = xb;
            ca = cb;
        }
        if (glow || halo) {
            lineGlow(x0, x1, y, jx, lay, half, lw * NoteShapes.WAVE_GLOW, lc,
                (halo ? NoteShapes.WAVE_GLOW_MULTI_A : NoteShapes.WAVE_GLOW_A) * alpha, strips);
        }
        int q = strips;
        if (gold) {
            // the gold row strung on the wave line
            int n = NoteShapes.beadCount(lanes);
            float bs = beadStep(lay, x0, x1);
            beadRange(lay, x0, x1, bs, n);
            for (int i = beadFirst; i <= beadLast; i++) {
                float px = beadX(lay, i, n, x0, x1, bs);
                float py = y + dir * NoteShapes.wavePearlDistance(tideWin(px) - wo, mlw, wavePhase, u);
                float r = NoteShapes.rowPearlRadius(i, u);
                q += pearl(px + jx, py, r, NoteShapes.ROW_PEARL_RIM, Theme.SYNC_PEARL, Theme.SYNC_PEARL_RIM, null,
                    alpha);
            }
        }
        return q;
    }

    /**
     * Collects the soft glow along a ground TIDE's wave line ({@link #paintTideT4}): the line's points, for
     * {@link #drawGlows} to draw on each side of it a gaussian fall-off from its edge ({@code half} from its middle)
     * at {@code a} out to {@code reach}, in the line's colour.
     */
    private void lineGlow(float x0, float x1, float y, float jx, LaneLayout lay, float half, float reach, Color c,
                          float a, int strips) {
        if (crests + 2 > CREST_MAX || crestPts + 2 * (strips + 1) > CREST_PTS || a <= 0.003f) return;
        float u = noteSize;
        float wo = lay.patternOrigin();
        float mlw = lay.patternWidth();
        float packed = Color.toFloatBits(c.r, c.g, c.b, Math.min(1f, a));
        for (int i = 0; i < 2; i++) {
            float s = i == 0 ? 1f : -1f;
            int c0 = crests++;
            crestAt[c0] = crestPts;
            crestN[c0] = strips + 1;
            crestPad[c0] = reach;
            crestCol[c0] = packed;
            crestNx[c0] = s * (tideTurned ? -ttSin : 0f);
            crestNy[c0] = s * (tideTurned ? ttCos : 1f);
            for (int k = 0; k <= strips; k++) {
                float xr = x0 + (x1 - x0) * k / strips;
                float px = xr + jx;
                float py = y + NoteShapes.waveCrest(tideWin(xr) - wo, mlw, wavePhase, u) + s * half;
                if (bending) { // the glow bends with its wave (glitchEnd), in the wave's own frame
                    float sc = Field.shapeScale();
                    float lx = (px - bendX) / sc;
                    float ly = (py - bendY) / sc;
                    px -= bendStrength * sc * VeilRenderer.wobbleX(lx, ly, bendT);
                    py -= bendStrength * sc * VeilRenderer.wobbleY(lx, ly, bendT);
                }
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

    /**
     * The sync frame of a DROP, STREAM or TIDE STREAM head: white on the ground line, deep gold on the sky line (a
     * DRAG has {@link #dragLight} instead).
     */
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
     * Turns the atlas batch {@code deg} degrees about a TIDE's pivot (Kelvin 2026-10-16: a TIDE or TIDE STREAM turns
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
        if (n.fake() && beat >= n.endBeat()) return; // a decoration is gone at its end (Kelvin 2026-11-02)
        // a pending TIDE group draws one head per look (tideHead); a running TIDE STREAM its own
        if (!tideBar(i, n, st, beat)) return;
        boolean mine = n.isTideMine();
        boolean block = n.isBlockMine();
        LaneLayout lay = e.layout();
        if (block) {
            // as a LANE MINE's lane: the closed line glows across the window while it blocks
            float b = NoteShapes.blockLook(e.judgeMs(), n.judgementMs(), n.endMs(), GameEngine.LANE_MINE_LEAD_MS);
            if (b > 0f) {
                addGlow(G_V, (lay.windowLeft() + lay.windowRight()) * 0.5f, e.lineY(n.line()),
                    lay.windowRight() - lay.windowLeft(), NoteShapes.LANE_MINE_LINE_GLOW_H, 0f, Theme.MINE,
                    NoteShapes.LANE_MINE_LINE_GLOW_A * b);
            }
        }
        boolean stream = n.isTideStream();
        if (stream && st != GameEngine.STATE_PENDING && beat > n.endBeat()
            && n.tailHeightAt(beat) <= 0f) return;
        float pos = drawPos(i, n, beat);
        if (Float.isNaN(pos)) return;
        boolean up = n.isUpper();
        float lineY = e.lineY(n.line());
        float y = up ? lineY - pos : lineY + pos;
        float lb = entry.look(i, beat);
        float deg = n.angleAt(lb);
        // a turned TIDE crosses the screen slanted: no cull (it is rare, and drawn only while on its way)
        if (!NoteShapes.turned(deg) && offScreen(up, y, tideReach(), FLAT_CULL)) return;
        float x0 = lay.windowLeft() + TIDE_INSET;
        float x1 = lay.windowRight() - TIDE_INSET;
        if (x1 <= x0) return;
        float alpha = n.shownOpacityAt(lb) * fadeIn(i, lineY, up);
        boolean lost = st == GameEngine.STATE_LOST;
        float jx = 0f;
        if (block) {
            // as a LANE MINE: fades out at its end, shakes at each contact
            alpha *= NoteShapes.endFade(e.judgeMs(), n.endMs());
            jx = contactJitter(e, i, frame);
        } else if (mine) {
            // as a MINE: passes the line, fades once passed (120 ms) or touched (160 ms, with a shake)
            if (st == GameEngine.STATE_MINE_PASSED || st == GameEngine.STATE_MINE_TOUCHED) {
                float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
                if (Float.isNaN(age)) age = 0f;
                boolean touched = st == GameEngine.STATE_MINE_TOUCHED;
                alpha *= 1f - MathUtils.clamp(age / (touched ? 160f : 120f), 0f, 1f);
                if (touched && age < JITTER_MS) jx = Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
            }
        } else if (!stream) {
            if (lost) return; // a missed TIDE has faded out by its LOST
            alpha *= lateFade(e, n);
            if (alpha <= MIN_ALPHA) return;
        }
        float grey = 0f;
        if (lost) {
            float age = (float) (e.judgeMs() - e.noteResolvedMs(i));
            if (Float.isNaN(age)) age = 0f;
            // a TIDE STREAM's head fades to .30 like a broken hold's
            float k = MathUtils.clamp(age / MISS_FADE_MS, 0f, 1f);
            alpha *= 1f - 0.70f * k;
            grey = MathUtils.clamp(age / MISS_GREY_MS, 0f, 1f);
            if (age < JITTER_MS) jx = Anim.hash01(i, frame) < 0.5f ? -JITTER : JITTER;
        }
        if (alpha <= MIN_ALPHA) return;
        boolean sync = !mine && NoteShapes.showsPearl(n, st);
        boolean pearls = sync && syncPearls();
        if (tideTurn(deg, n.pivotX(), n.pivotY(), y, up, x0, x1)) { // undone by the caller (tideTurnEnd)
            float a = tideLo(x0, x1);
            x1 = tideHi(x0, x1);
            x0 = a;
        }
        // a TIDE STREAM's head is its line's TIDE drop (Kelvin 2026-10-22), the band behind it drawn in drawBands
        wavePhase = n.waveSpeed() * beat;
        mineTint = mine;
        paintTideT4(x0, x1, y, up, grey, jx, alpha, lay, NoteShapes.songSeconds(e), !lost && high, pearls,
            sync && syncHalo());
        mineTint = false;
        wavePhase = 0f;
    }

    /** First and last pearl index of the row {@link #beadRange} set last. */
    private int beadFirst, beadLast;

    /**
     * @return spacing of a pearl row over {@code [a, b]}: {@code (b − a) / (beadCount)} at
     *         {@link LaneLayout#patternWidth} lanes' worth across the window, so on tiled lanes it
     *         follows the global lane scale (Kelvin 2026-10-13) and equals the shared-window spacing
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
        beadShift = 2f * (wavePhase - MathUtils.floor(wavePhase)); // two pearls a wave, in pearl steps [0, 2)
        if (!lay.tiled()) {
            beadFirst = 0;
            beadLast = n - 1;
        } else if (!(s > 0f) || !(b > a)) {
            beadFirst = 0;
            beadLast = -1;
        } else {
            float o = tideX(lay.patternOrigin()) + (beadShift - 2f) * s; // one pair to the left covers the shift
            beadFirst = (int) Math.ceil((a - o) / s - 0.5f);
            beadLast = Math.min((int) Math.floor((b - o) / s - 0.5f), beadFirst + n - 1);
        }
    }

    /** @return centre x of pearl i of a row over {@code [a, b]} ({@link #beadRange}). */
    private float beadX(LaneLayout lay, int i, int n, float a, float b, float s) {
        if (lay.tiled()) return tideX(lay.patternOrigin()) + (i + beadShift - 2f + 0.5f) * s;
        if (beadShift == 0f) return NoteShapes.beadX(i, n, a, b);
        float j = (i + beadShift) % n; // a row in a finite window wraps around
        return a + (b - a) * (j + 0.5f) / n;
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
        bendT = VeilRenderer.loopTime(now);
        glitchQuads = 0;
        glitchedNotes = 0;
        strokes.draw(e, bendT, false); // the custom lines under every note
        collectSkyBands(e, beat);
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.hasTideBand() || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(e, i, n, beat);
            drawBand(e, i, n, beat, now, false);
            if (g) glitchEnd();
            tideTurnEnd();
        }
        int frame = (int) (now * 60f);
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.isLaneMine() || n.floats() || entry.drawFrom(i) > beat) continue; // floating: FloatNotes
            boolean g = glitchBegin(e, i, n, beat);
            laneMineBody(e, i, n, beat, frame, false);
            if (g) glitchEnd();
        }
        for (int i = end - 1; i >= start; i--) {
            Note n = notes[i];
            if (!n.hasTideBand() || entry.drawFrom(i) > beat) continue;
            boolean g = glitchBegin(e, i, n, beat);
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
            if (!n.hasTideBand() || !n.isUpper() || entry.drawFrom(i) > beat) continue;
            if (n.fake() && beat >= n.endBeat()) continue; // as drawBand
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
        if (n.fake() && beat >= n.endBeat()) return; // a decoration is gone at its end
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
        if (n.isBlockMine()) alpha *= NoteShapes.endFade(e.judgeMs(), n.endMs());
        mineTint = n.isTideMine();
        if (cap) {
            paintTideCap(x0, x1, y, tail, up, grey, alpha);
            mineTint = false;
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
        paintTideBand(x0, x1, y, tail, up, held ? BAND_HELD : 1f, grey, now, tideReach(), alpha);
        mineTint = false;
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
     * @param head   how far the head reaches ({@link #tideReach}; the ripples start behind it)
     * @return quads submitted (at most {@link #BAND_QUADS} − 1 without a knock-out)
     */
    int paintTideBand(float x0, float x1, float y, float len, boolean up, float mul, float grey, float ripple,
                      float head, float alpha) {
        if (!(len > 0f) || x1 <= x0) return 0;
        Color c = col;
        Color base = mineTint ? Theme.MINE : up ? Theme.SKY_TIDE : Theme.TIDE;
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
        Color base = mineTint ? Theme.MINE : up ? Theme.SKY_TIDE : Theme.TIDE;
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
        gbend[k * 3] = 0f;
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
            TextureRegion gg = draw.gaussDot();
            gaussSlice = new TextureRegion(gg, gg.getRegionWidth() / 2, 0, 1, gg.getRegionHeight() / 2);
        }
        float old = batch.getPackedColor();
        for (int k = 0; k < glows; k++) {
            float bs = gbend[k * 3];
            if (!gglitch[k] && !(bs > 0f)) {
                drawGlow(k, ng, gv, gd);
                continue;
            }
            glitch.begin(atlas);
            batch = glitch;
            drawGlow(k, ng, gv, gd);
            batch = atlas;
            if (bs > 0f) glitch.bend(gbend[k * 3 + 1], gbend[k * 3 + 2], bs, bendT, Field.shapeScale());
            if (!gglitch[k]) {
                glitch.emitPlain();
                continue;
            }
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
        if (g == G_DRAG) {
            dragSkins(gx[k], gy[k], w, h, grot[k], gcol[k]);
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

    /** Draws the line glows collected by {@link #lineGlow}: one strip of quads per side, sharing their corners. */
    private void drawCrests() {
        float[] q = glowQuad;
        for (int c = 0; c < crests; c++) {
            TextureRegion r = gaussSlice;
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
