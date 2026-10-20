package org.kelvinizer.map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 所有曲目页：所有的歌（隐藏的秘密曲包除外），手机上和地图默认缩放时一样大（平板小两成），每行两首、相邻两行错开半格（像单曲地图那样），
 * 上下行之间用线连成网格，不能缩放。网格可以上下拖，顶上不裁（像地图一样从玩家框下面滑过去），在难度行上面渐隐。上面原来的曲包框已经去掉。
 *
 * <p>The ALL SONGS page (Kelvin 2026-10-19 13:14–14:01, the design thread's round 5 and its notes 全部歌曲排版参数; the
 * grid itself 21:16–21:18): every song (hidden secret packs left out), two to a row, each row half a step along from
 * the one above as on the singles map, every song linked to the songs half a step either side of it in the next row so
 * that the links make a net. The songs are as big as a map's at its default zoom on a phone and a fifth smaller on a
 * tablet (21:53), each with its best score and grade, and the grid does not zoom. The song card stays open beside it.
 * The pack box that checked packs over the grid went on 2026-10-20 (「把全部歌曲上面选曲包的那个功能去掉吧，现在在那里很突兀而且
 * 曲包很少」): the grid starts where the box's top was.
 *
 * <p>The page is laid out on the 16:10 blueprint (1920 × 1200, y down): x follows the room left of the card and sizes
 * shrink with a shorter screen as on the result screen (never grow). The songs keep one size on every phone and one on
 * every tablet: a row's half step is the singles map's at that size, or less where the room left of the card is
 * narrower, and the rows lie the singles map's rows apart, or further where a score row would reach the next row's
 * rings. The grid scrolls with the map camera (x locked, zoom 1): a song's world position is its place on screen at
 * scroll 0, so the camera's y offset is the scroll. A mask shader fades what the grid draws out above the lens row;
 * nothing clips it at the top since Kelvin 2026-10-20 16:37 (「这个顶上不要做成有边界的」): it scrolls on under the player
 * block and off the screen as a map does.
 */
final class GridPage {
    // the 16:10 blueprint (y down) ---------------------------------------------------------------
    /** The page: left and right edge, the grid's top (under the player frame), the lens row's top. */
    private static final float B_LEFT = 24f, B_RIGHT = 1194f, B_TOP = 146f, B_LENS = 1080f;
    /** The grid fades out from y 1006 to 1066; the scroll bar runs from 168 to 1062. */
    private static final float FADE_FROM = 1006f, FADE_TO = 1066f, BAR_TOP = 168f, BAR_BOTTOM = 1062f;

    // the songs (HUD units on a phone, TABLET times these on a tablet) ---------------------------------------
    /** A map's look at its default zoom: the grid draws its songs this much larger than a map does at zoom 1. */
    static final float LOOK = 1f / MapStyle.ZOOM_MIN;
    /** Song radius: a map's song at the default zoom (Kelvin 2026-10-19 21:16: 全部歌曲的图标也用默认大小). */
    static final float NODE_R = MapRenderer.SONG_R * LOOK;
    /** Outer dashed ring, where a map's sonar dashes lie; the selected song's ring lies 1 outside it. */
    static final float RING_R = MapStyle.DASH_R * LOOK;
    /** The singles map's half step along a row and its rows' distance (250 and 230 there), at the default zoom. */
    private static final float HALF_STEP = 250f * LOOK, ROW_GAP = 230f * LOOK;
    /**
     * Everything below drawn this much smaller on a tablet (16:8.5 and taller, the result screen's split): a phone keeps
     * the maps' size (Kelvin 2026-10-19 21:53: 「16：10的稍微小一点吧，平板会放大这个大小的，这里手机端观感可以但是平板太大了」).
     */
    static final float TABLET = 0.8f;
    /** Room over the first row (its ring and the selected song's glow), at the page's left and for the scroll bar. */
    private static final float TOP = 22f, EDGE = 10f, BAR_ROOM = 26f;
    /** Name cap (a map's names at the default zoom), its baseline just under the ring, the chip 15 under it. */
    static final float NAME_CAP = 26f, NAME_DY = RING_R + 10f + NAME_CAP, CHIP_DY = 15f;
    /**
     * Score row: its baseline 93 under the name's (the pill 12 under the chip); score and grade caps (27.5 / 25 px
     * type), pill height, its top over the baseline, padding, gap.
     */
    static final float SCORE_DY = 93f, SCORE_CAP = 19.25f, GRADE_CAP = 17.5f, PILL_H = 35f, PILL_UP = 30f,
        PILL_PAD = 16f, SCORE_GAP = 12f;
    /**
     * How far a song's labels reach under its centre, half the widest score row, and how near them the next row's
     * songs may come (their score rings, and 4).
     */
    private static final float LABEL_DOWN = NAME_DY + SCORE_DY + PILL_H - PILL_UP, ROW_HALF_W = 131f,
        CLEAR_R = MapStyle.ARC_R1 * LOOK + 4f;

    static final Color CYAN = Theme.rgb(0x66F0FF, 1f);

    private static final String VERT = "attribute vec4 a_position;\n"
        + "attribute vec4 a_color;\n"
        + "attribute vec2 a_texCoord0;\n"
        + "uniform mat4 u_projTrans;\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "void main() {\n"
        + "  v_color = a_color;\n"
        + "  v_color.a = v_color.a * (255.0 / 254.0);\n"
        + "  v_texCoords = a_texCoord0;\n"
        + "  gl_Position = u_projTrans * a_position;\n"
        + "}\n";
    /** u_mask (pixels from the bottom): x nothing above, y opaque up from, z clear at and below. */
    private static final String FRAG = "#ifdef GL_ES\n"
        + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
        + "precision highp float;\n"
        + "#else\n"
        + "precision mediump float;\n"
        + "#endif\n"
        + "#endif\n"
        + "varying vec4 v_color;\n"
        + "varying vec2 v_texCoords;\n"
        + "uniform sampler2D u_texture;\n"
        + "uniform vec3 u_mask;\n"
        + "void main() {\n"
        + "  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
        + "  float y = gl_FragCoord.y;\n"
        + "  float k = clamp((y - u_mask.z) / max(u_mask.y - u_mask.z, 0.001), 0.0, 1.0);\n"
        + "  if (y > u_mask.x) k = 0.0;\n"
        + "  gl_FragColor = vec4(c.rgb, c.a * k);\n"
        + "}\n";

    private final Draw draw;
    private final MapRenderer r;
    private final MapCamera cam;
    private ShaderProgram shader;
    private boolean shaderFailed;

    private MapWorld world;
    /** False while the Descent zooms into a song (nothing is faded then). */
    boolean masking = true;

    // this frame's geometry (HUD units, y up) ---------------------------------------------------------
    /** Left and right edge of the page (the card is right of it) and the grid's top. */
    float areaL, areaR, gridTop;
    /** x stretch, the page's size factor (≤ 1) and the grid band's y stretch. */
    private float sx = 1f, s = 1f, v = 1f;
    /** The songs: x of half step 0, the half step, y of the first row and the rows' distance. */
    private float col0, step, row0, rowGap;
    /** The songs' and their labels' size: 1 on a phone, {@link #TABLET} on a tablet. */
    float size = 1f;

    GridPage(Draw draw, MapRenderer r, MapCamera cam) {
        this.draw = draw;
        this.r = r;
        this.cam = cam;
    }

    /** Takes a new ALL SONGS world. */
    void setWorld(MapWorld w) {
        world = w;
    }

    // =================================================================== geometry
    /** Recomputes the page for this frame: the room left of the card, the band between the frame and the lens row. */
    void layout() {
        float k = MapUi.k();
        areaL = Ui.safeLeft();
        areaR = Ui.right() - SongCard.sideWidth() - 26f * k;
        sx = Math.max(0.1f, (areaR - areaL) / (B_RIGHT - B_LEFT));
        // the player frame's bottom and the lens row's top at rest, as MapHud places them
        float frameBottom = MapHud.frameBottom(k);
        float lensTop = Ui.safeBottom() + MapStyle.LENS_H * k;
        gridTop = frameBottom - 24f * k;
        float u = (gridTop - lensTop) / (B_LENS - B_TOP);
        s = Math.max(0.1f, Math.min(1f, Math.min(u, sx)));
        v = Math.max(0.1f, u);
        // the songs, a fifth smaller on a tablet, centred left of the scroll bar: the singles map's half step where
        // the room allows
        size = Ui.worldW() > 16f / 8.5f * Ui.worldH() + 0.5f ? 1f : TABLET;
        float left = areaL + EDGE, right = areaR - BAR_ROOM * s;
        step = Math.max(0f, Math.min(HALF_STEP * size, (right - left - 2f * RING_R * size) / 3f));
        col0 = 0.5f * (left + right) - 1.5f * step;
        row0 = gridTop - TOP - RING_R * size;
        // the singles map's rows apart, or as far as keeps a score row clear of the next row's score rings
        float side = Math.max(0f, step - ROW_HALF_W * size), clear = CLEAR_R * size;
        rowGap = Math.max(ROW_GAP * size, LABEL_DOWN * size + (float) Math.sqrt(Math.max(0f, clear * clear - side * side)));
    }

    /** HUD x of blueprint x {@code bx}. */
    private float hx(float bx) {
        return areaL + (bx - B_LEFT) * sx;
    }

    /** HUD y of blueprint y {@code by} in the grid band. */
    private float hy(float by) {
        return gridTop - (by - B_TOP) * v;
    }

    /**
     * Song {@code i}'s place along its row in half steps (0–3): two to a row, the even rows on 1 and 3 and the odd ones
     * on 0 and 2, as the singles map's two rows lie (Kelvin 2026-10-19 21:18: 一排两个错开).
     */
    static int column(int i) {
        return 2 * (i % 2) + 1 - (i / 2) % 2;
    }

    private float nodeX(int i) {
        return col0 + column(i) * step;
    }

    private float nodeY(int i) {
        return row0 - (i / 2) * rowGap;
    }

    /** How far the grid scrolls: until the last row's labels clear the fade. */
    private float maxScroll() {
        int rows = (world.nodes.length + 1) / 2;
        if (rows == 0) return 0f;
        return Math.max(0f, hy(FADE_FROM) - (row0 - (rows - 1) * rowGap - LABEL_DOWN * size - TOP));
    }

    /** The grid's scroll: how far the camera has moved down from the first row. */
    float scroll() {
        return Ui.CY - cam.renderY();
    }

    /** Outer ring radius of song {@code n} (its dashes, or the selected ring just outside). */
    static float ringR(MapNode n) {
        return RING_R * n.r / NODE_R;
    }

    /**
     * True when HUD y {@code hy} is above the grid's faded bottom. The grid has no top (2026-10-20 16:37): a song showing
     * under the player block takes a tap there too, after the HUD's own controls.
     */
    boolean inBand(float y) {
        return y >= hy(FADE_TO);
    }

    // =================================================================== frame
    /**
     * Sets the camera's bounds (x locked at the centre, y from the first row to the last) and puts every song at its
     * place on screen for scroll 0.
     */
    void place() {
        MapNode[] ns = world.nodes;
        float h = Ui.worldH();
        cam.setLevel(Ui.CX, Ui.CX, Ui.CY - maxScroll() - h * 0.5f, Ui.CY + h * 0.5f, 1f);
        for (int i = 0; i < ns.length; i++) {
            ns[i].x = nodeX(i);
            ns[i].y = nodeY(i);
            ns[i].r = NODE_R * size;
        }
    }

    /** Scrolls the grid so that song {@code n} and its labels show between its top and the fade (glides or snaps). */
    void reveal(MapNode n, boolean animate) {
        if (n == null || world == null || n.order < 0) return;
        float s0 = scroll(), y = nodeY(n.order) + s0, target = s0;
        float over = y + RING_R * size + TOP - gridTop, under = hy(FADE_FROM) - (y - LABEL_DOWN * size - TOP);
        if (over > 0f) target = s0 - over;
        else if (under > 0f) target = s0 + under;
        target = MathUtils.clamp(target, 0f, maxScroll());
        if (Math.abs(target - s0) < 0.5f) return;
        if (animate) cam.flyTo(Ui.CX, Ui.CY - target, 1f, 0.32f, Interpolation.exp5Out, 0f);
        else cam.snap(Ui.CX, Ui.CY - target, 1f);
    }

    /** Puts the camera at scroll {@code scroll} (clamped) at once: entering, or restoring the saved place. */
    void scrollTo(float scroll) {
        cam.snap(Ui.CX, Ui.CY - MathUtils.clamp(scroll, 0f, maxScroll()), 1f);
    }

    /** The song whose ring or labels (name, chip and score row) hold HUD point (hx, hy), the nearest of two, or null. */
    MapNode hit(float hx, float hy) {
        if (world == null || !inBand(hy)) return null;
        MapNode best = null;
        float bestD = Float.MAX_VALUE, rr = (RING_R * size + 10f) * (RING_R * size + 10f);
        for (MapNode n : world.nodes) {
            if (n.wake < 0.5f) continue;
            float dx = hx - cam.hudX(n.drawX()), dy = hy - cam.hudY(n.drawY(0f, true)), d = dx * dx + dy * dy;
            boolean in = d <= rr || (Math.abs(dx) <= ROW_HALF_W * size + 20f && dy <= 0f && dy >= -LABEL_DOWN * size - 8f);
            if (in && d < bestD) {
                best = n;
                bestD = d;
            }
        }
        return best;
    }

    // =================================================================== the mask
    /** Fades what follows out above the lens row; nothing is clipped at the top (it runs on under the player block). */
    void maskGrid(Batch b) {
        if (masking) mask(b, Ui.top() + Ui.worldH(), hy(FADE_FROM), hy(FADE_TO));
    }

    /** Back to the batch's own shader. */
    void unmask(Batch b) {
        if (shader != null && b.getShader() == shader) b.setShader(null);
    }

    /** Sets the mask shader (HUD y: nothing above {@code clip}, opaque down to {@code from}, clear from {@code to}). */
    private void mask(Batch b, float clip, float from, float to) {
        if (shader == null && !shaderFailed) {
            shader = new ShaderProgram(VERT, FRAG);
            if (!shader.isCompiled()) {
                Gdx.app.error("GridPage", "mask shader: " + shader.getLog());
                shader.dispose();
                shader = null;
                shaderFailed = true;
            }
        }
        if (shader == null) return;
        if (b.getShader() == shader) b.flush();
        else b.setShader(shader);
        float px = Ui.pxPerWorld(), bot = Ui.bottom();
        shader.setUniformf("u_mask", (clip - bot) * px, (from - bot) * px, (to - bot) * px);
    }

    void dispose() {
        if (shader != null) shader.dispose();
        shader = null;
    }

    // =================================================================== drawing
    /** Atlas phase: the grid's scroll bar. */
    void drawShapes(float a) {
        if (world == null || a <= 0.01f) return;
        drawBar(a);
    }

    /** The thin scroll bar right of the grid when it scrolls. */
    private void drawBar(float a) {
        float max = maxScroll();
        if (max <= 0f) return;
        float x = areaR - 17f * s, w = 6f * s, t0 = hy(BAR_TOP), t1 = hy(BAR_BOTTOM);
        float view = gridTop - hy(FADE_FROM), len = (t0 - t1) * view / (view + max);
        float f = MathUtils.clamp(scroll() / max, 0f, 1f), top = t0 - f * (t0 - t1 - len);
        draw.roundRect(x, t1, w, t0 - t1, 3f * s, Theme.WHITE, 26f / 255f * a);
        draw.roundRect(x, top - len, w, len, 3f * s, Theme.INK2, 170f / 255f * a);
    }

    /** UI phase: when nothing is left to show, the filter's no-match line. */
    void drawUi(Batch b, boolean zh, MapFilter filter, float a) {
        if (world == null || a <= 0.01f) return;
        if (world.gridEmpty && filter != null && filter.active()) {
            String label = MapFilter.LABELS[filter.status()];
            r.ui(b, (zh ? "没有符合的歌 · " : "NO MATCHES · ") + label, 22f * s, hx(B_LEFT + 32f), row0,
                Align.left | Fonts.VCENTER, Theme.INK1, a);
        }
    }
}
