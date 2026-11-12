package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ImmediateModeRenderer20;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import org.kelvinizer.elements.Channels;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.Sea;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.ui.Theme;

/**
 * 3D 视角：谱面顶层 {@code view} 的四个通道（偏航、俯仰、视场角、距离）按拍算出相机。平面画面画在 z = 0 上（柱体和键的
 * 正面），后面加上轨道柱体、每条轨道的母面和键的厚度。定轨谱默认就正对屏幕带一点透视；无限轨谱默认还是平面画面。
 * 正对时按下的点就是平面画面上的点，转过去以后沿视线找最前面的柱体。
 *
 * <p>The view (Kelvin 2026-11-07 06:35 「写成3D定轨形式」, 06:45 the design thread's 3D page, its default ⇄ 3D move).
 * The flat picture is the front of a 3D world: every lane is a column {@link #DEPTH} deep behind its column on the
 * screen ({@link LaneLayout#columnLeft}), every line a mother plane per column, every lane key a box as deep as the
 * column. The renderers keep drawing the flat picture, now on the plane z = 0 through {@link #combined()}; this class
 * draws what lies behind it first ({@link #drawBack}, {@link #drawBoxes}): nothing ever covers the front plane, so the
 * flat picture is drawn last over it. Finite lanes are always 3D (Kelvin 2026-11-07 15:34: 「默认的2D视角做成这个样子，
 * 两边的间距小一点就可以了，一点点3D感」): by default the camera faces the screen centre from the distance at which the
 * front plane is exactly the flat picture, so the columns' sides, the mother planes and the key boxes show a little.
 * Unlimited lanes keep the flat picture by default; their depth grows with the angle the camera has turned through
 * (whole at {@link #FULL_DEPTH_DEG}). Facing straight, a press lands where its ray crosses the front plane (the flat
 * picture's own judging); turned, it goes to the column its ray meets first (the front one wins), or in a gap or beside
 * the columns to the point it crosses the middle of the columns' depth (the nearer lane). The hit effects are spheres
 * round the middle of the keys' depth ({@link #project}). A turned lane or sea (Kelvin 2026-11-08 23:10 – 11-09 00:13,
 * {@link LaneLayout#turn}) is drawn as if it fell straight down, then turned about its origin: its column, boxes and
 * sheets here through its frame ({@link #laneFrame}, {@link #seaFrame}), its flat picture through
 * {@link #frameCombined}.
 */
public final class View3D implements HitFx.Projection {
    /**
     * Field of view of the default camera, degrees: from where the front plane fills the screen, the 240 depth shows as
     * much as on the 3D page's default view (its back faces 0.96 the size of the front; Kelvin 2026-11-07 15:34).
     */
    static final float DEFAULT_FOV = 11f;
    /** Depth of a column, a mother plane and a key box, reference units (the 3D page: 240). */
    static final float DEPTH = 240f;
    /**
     * How far the camera sees past the middle of the depth, in its distances to the front plane: a 240-wide key that
     * far is a quarter of a pixel, so a key far down a lane turned towards the player shrinks away instead of
     * vanishing (Kelvin 2026-11-09 16:06 「在测试一个位置1000000的键看看正不正常」; 20000 reference units before, where
     * a key still showed 57 wide at the default view). The near plane is 1/20 of that distance: the depth's
     * resolution where things are is as before.
     */
    static final float SEEN = 1000f;
    /** The camera angle (from straight on) at which the depth is whole, degrees. */
    static final float FULL_DEPTH_DEG = 15f;
    /** How much of a column's reach past a line fades out (the 3D page: 700), and in how many pieces. */
    private static final float COLUMN_FADE = 700f;
    private static final int FADE_SEGMENTS = 4;
    private static final Color FACE = new Color(0x2a3a7aff), EDGE = new Color(0x9fb0ffff);
    private static final Color PLANE = new Color(0xc9d8ffff);
    /**
     * What a mine lane's column turns to, as red as it is ({@link LaneLayout#mine}; Kelvin 2026-11-10 23:01 「这些轨道会
     * 变红」): its faces, edges, face planes and their rims.
     */
    private static final Color MINE_FACE = new Color(0x8c1c2cff), MINE_PLANE = new Color(0xff7f8aff),
        MINE_LIGHT = new Color(0xff9aa3ff);
    /** This lane's colours ({@link #tint}). */
    private final Color face = new Color(FACE), edge = new Color(EDGE), plane = new Color(PLANE),
        rim = new Color(Color.WHITE);
    private static final float FACE_A = 0.18f, EDGE_A = 0.55f, PLANE_A = 0.55f, KEY_A = 0.85f;
    /** A veil's edges ({@link #drawVolumes}): the veil renderer's edge colour, a box's and an ellipsoid's rings. */
    private static final Color VOLUME_EDGE = new Color(128 / 255f, 194 / 255f, 250 / 255f, 1f);
    private static final float VOLUME_EDGE_A = 0.8f, VOLUME_RING_A = 0.5f;
    private static final int RING_STEPS = 48;
    private static final int MAX_VERTICES = 6000;

    private final PerspectiveCamera cam = new PerspectiveCamera();
    private final Matrix4 inverse = new Matrix4();
    private final Vector3 near = new Vector3();
    private final Vector3 far = new Vector3();
    private ImmediateModeRenderer20 shapes;
    private int primitive;
    /** (y, opacity) stops up a column ({@link #columnStops}). */
    private final float[] stops = new float[4 * FADE_SEGMENTS + 4];
    /** A moved column's own stops ({@link #column}), the stops the column being drawn uses and its front. */
    private final float[] laneStops = new float[4 * FADE_SEGMENTS + 4];
    private float[] cur = stops;
    private float front;
    /** The depth of the back of the column {@link #column} read last, view units (as {@link #front}). */
    private float back;
    private boolean active;
    /** True while the camera faces the screen straight (yaw and pitch 0): a press lands on the front plane. */
    private boolean straight;
    private float depth;
    private float shift;
    private float viewW;
    private float viewH;
    private float cx;
    private float cy;
    private float[] boxes = new float[0];
    private int boxCount;
    /** Boxes recorded this frame with a sync frame ({@link #box(float, float, float, float, float, Color, float,
     *  Color, float, float)}). */
    private int framed;
    /**
     * Floats a box takes in {@link #boxes}: x, y, w, len, rot, colour r g b, α, frame r g b, band α, halo α, its pose
     * ({@link #turn}): the front's depth (world z), the turns about the across and the along axis, the pivot, its
     * frame and depth; and its style ({@link #style}).
     */
    private static final int BOX_FLOATS = 21;
    /**
     * Box styles ({@link #style}): a key's box (its sides and back in its colour, the flat picture its front); a MINE's
     * (Kelvin 2026-11-09 17:24 「水雷要六面都有纹理」): all six faces as a mine's front reads, its front too; a LANE MINE
     * column's (2026-11-10 01:13 「四面都是那种红色斜线那种」): its long sides and back as the column's flat front reads
     * ({@link #columnFace}), its ends as a mine's front.
     */
    static final int BOX_PLAIN = 0, BOX_MINE = 1, BOX_COLUMN = 2;
    /** The style of the next box recorded ({@link #style}). */
    private int nextStyle = BOX_PLAIN;
    /** A mine face's rim and stud half side at most, world units (as the flat mine face's). */
    private static final float MINE_RIM = 3f;
    /** The pose the next boxes are recorded with ({@link #turn}). */
    private float turnZ, turnX, turnY, turnPivot, turnLen;
    /** The 3D corners of the box being drawn ({@link #corners}): the front's four, then the back's. */
    private final float[] p3 = new float[24];
    /**
     * A sync frame round a box's back and along its depth (Kelvin 2026-11-07 19:37 「双押的时候按键的3维外框都要高亮」):
     * half the width of its solid line and how far its soft edge reaches beyond, reference units, and its opacity in
     * the front frame's band (the flat frame round the front: an 11 wide band in a 40 wide halo).
     */
    static final float FRAME_LINE = 3f, FRAME_SOFT = 8f, FRAME_A = 0.8f;
    private final Vector3 edgeDir = new Vector3();
    private final Vector3 edgeSide = new Vector3();
    private final Vector3 edgeEye = new Vector3();
    private float[] sheets = new float[0];
    private int sheetCount;
    private static final int SHEET_FLOATS = 15;
    /** A sheet's lane ({@link #sheet}): every column it crosses; or a piece on a sea (its depths and frame given). */
    static final int SHEET_ACROSS = -1, SHEET_SEA = -2;
    private static final float SHEET_A = 0.35f, SEA_A = 0.5f, SEA_EDGE_A = 0.9f;
    /**
     * The heave of a sea and of a TIDE's sheet (Kelvin 2026-11-07 16:14 「我想做成2维的波状起伏」, 16:16 「海浪也这么处理」;
     * the design thread's 3D page): amplitude and wavelengths across and in depth (reference units), and the step of
     * the grid it is drawn on.
     */
    static final float HEAVE = 7f, HEAVE_X = 280f, HEAVE_Z = 260f, HEAVE_STEP = 40f;
    /** The sheet {@link #heaving} draws: its left end, middle depth, surface, turn, depth scale and time. */
    private float hl, hdm, hy, hslope, hmid, hpx, hpy, hcos = 1f, hsin, hzk, ht;
    /** True while that sheet is a sea's surface ({@link #seaHeave}). */
    private boolean hsea;
    /** Sheet points: x, y, z and, on a sea, how high the heave is there (0 trough – 1 crest). */
    private final float[] pa = new float[4], pb = new float[4], pc = new float[4], pd = new float[4];
    /**
     * A sea's heave against a TIDE's sheet (Kelvin 2026-11-08 19:34「海面再生动一点」, 20:17 picked A 起伏明暗): this many
     * times as high and as quick, with a third wave slanting across at {@link #SLANT_K} of the height so the crests
     * do not stand in rows.
     */
    static final float SEA_HEAVE_K = 1.7f, SEA_SPEED = 1.3f, SLANT_K = 0.6f, SLANT_L = 410f;
    /** A sea's troughs and crests: its fill goes from one to the other with the heave, more opaque on the crests. */
    private static final Color SEA_TROUGH = new Color(0x081A66ff), SEA_CREST = new Color(0x3C86FFff);
    private final Color crestC = new Color();
    /**
     * Where keys on a sea will land (Kelvin 2026-11-08 19:49「在逐渐接近判定面的时候在判定面上逐渐出现这个按键的投影」,
     * 「玩家好知道这个按键到底会落在哪里」; 20:17 picked 键色投影): recorded by {@link #landing}, drawn with the next
     * {@link #drawBoxes} beneath the keys, a fill and an edge in the key's colour, at their strongest these opacities.
     */
    private static final float LAND_FILL_A = 0.4f, LAND_EDGE_A = 1f;
    /** How far above its sea (reference units) a key's landing mark starts to show: a default fall. */
    static final float LAND_REACH = 1080f;
    private static final int LAND_FLOATS = 8;
    private float[] lands = new float[0];
    private Sea[] landSeas = new Sea[0];
    private int[] landIndex = new int[0];
    private int landCount;

    /**
     * @return the heave (reference units) at {@code x} from a sheet's left end and {@code d} from the middle of its
     *         depth at {@code t} seconds: a wave across and one in depth, as on the 3D page
     */
    static float heave(float x, float d, float t) {
        return HEAVE * (MathUtils.sin(MathUtils.PI2 * x / HEAVE_X - 2f * t)
            + MathUtils.sin(MathUtils.PI2 * d / HEAVE_Z - 1.4f * t + 0.004f * x));
    }

    /** @return {@link #heave} as a sea heaves ({@link #SEA_HEAVE_K}), also in the flat picture */
    static float seaHeave(float x, float d, float t) {
        return SEA_HEAVE_K * (heave(x, d, SEA_SPEED * t)
            + HEAVE * SLANT_K * MathUtils.sin(MathUtils.PI2 * (0.55f * x + 0.8f * d) / SLANT_L - 2.6f * t));
    }

    /** @return the distance (reference units) at which a lens of {@code fov} degrees shows the frame whole */
    public static float autoDist(float fov) {
        return Field.REF_H * 0.5f / (float) Math.tan(Math.toRadians(fov) * 0.5);
    }

    /** The chart editor's own turn on top of the chart's view (Kelvin 2026-11-08 01:00), degrees; 0, 0 in the game. */
    private float orbitYaw, orbitPitch;

    /**
     * Turns the camera by {@code yaw} and {@code pitch} degrees on top of the chart's view at every {@link #update}:
     * the chart editor's free look while editing (Kelvin 2026-11-08 01:00), back to the game's own view at 0, 0 (its
     * 「归位」). The game never calls it.
     */
    public void orbit(float yaw, float pitch) {
        orbitYaw = yaw;
        orbitPitch = pitch;
    }

    /** The chart editor's own eye in place of a chart format v7 chart's P ({@link #eye}); x NaN: P. */
    private float ownX = Float.NaN, ownY, ownZ;

    /**
     * Puts the camera of a chart format v7 chart at (x, y, z) (reference units, as its P) at every {@link #update}
     * in place of its P, still looking at the origin through its field of view (Kelvin 2026-11-09 18:44, item 7): the
     * chart editor's view while editing; x NaN gives P back. {@link #orbit} still turns it. The game never calls it.
     */
    public void eye(float x, float y, float z) {
        ownX = x;
        ownY = y;
        ownZ = z;
    }

    /** The chart editor's own move of the view ({@link #pan}), reference units along the chart's axes; 0 in game. */
    private float panX, panY, panZ;

    /**
     * Moves the camera and the point it looks at together by (x, y, z) (reference units along the chart's axes: x
     * right, y up, z inward, as P) at every {@link #update}, so the view keeps its direction: the chart editor's
     * Ctrl+Alt+arrows while editing (Kelvin 2026-11-10 20:26 「ctrl+alt+箭头可以前后左右移动camera位置」); 0, 0, 0
     * gives the chart's own view back. The game never calls it.
     */
    public void pan(float x, float y, float z) {
        panX = x;
        panY = y;
        panZ = z;
    }

    /**
     * Evaluates the chart's view at {@code beat} over the flat camera of the screen.
     *
     * @return true when the field is drawn in 3D, through {@link #combined()}: always for finite lanes, else off the
     *         default view
     */
    public boolean update(Chart chart, float beat, OrthographicCamera flat) {
        active = false;
        if (chart == null) return false;
        boolean solid = !chart.unlimitedLanes(); // finite lanes: 3D at the default too
        boolean orbiting = orbitYaw != 0f || orbitPitch != 0f;
        if (!solid && !chart.hasView() && !orbiting) return false;
        // motion events like any other (Kelvin 2026-11-07 16:04 「3Dcamera这些也都是运动类事件」): the default between
        float yaw = Events.calculateValue(chart.viewYaw(), beat, 0f);
        float pitch = Events.calculateValue(chart.viewPitch(), beat, 0f);
        float fov = Events.calculateValue(chart.viewFov(), beat, DEFAULT_FOV);
        float dist = Float.NaN;
        Channels at = chart.camera7();
        if (at != null) { // chart format v7: the camera at P looks at the origin (Kelvin 2026-11-09 18:44)
            boolean own = !Float.isNaN(ownX); // the chart editor's eye
            float px = own ? ownX : at.at(0, beat), py = own ? ownY : at.at(1, beat), pz = own ? ownZ : at.at(2, beat);
            float h = (float) Math.sqrt(px * px + pz * pz);
            yaw = h > 1e-3f ? (float) Math.toDegrees(Math.atan2(-px, -pz)) : 0f; // straight above or below: +z up
            pitch = (float) Math.toDegrees(Math.atan2(py, h));
            fov = chart.fov7() != null ? chart.fov7().at(beat) : DEFAULT_FOV; // 11° unless it says (22:28 「加视角」)
            if (!Float.isFinite(fov)) fov = DEFAULT_FOV;
            dist = (float) Math.sqrt(h * h + py * py) - DEPTH * 0.5f; // the origin is halfway into the columns
        }
        if (orbiting) { // the chart editor's free look on top (orbit)
            yaw += orbitYaw;
            pitch += orbitPitch;
        }
        pitch = MathUtils.clamp(pitch, -89f, 89f);
        fov = MathUtils.clamp(fov, 1f, 150f);
        float auto = autoDist(fov);
        if (at == null) dist = Events.calculateValue(chart.viewDist(), beat, auto);
        float cos = MathUtils.cosDeg(yaw) * MathUtils.cosDeg(pitch);
        float turned = (float) Math.toDegrees(Math.acos(MathUtils.clamp(cos, -1f, 1f)));
        float s = solid ? 1f : MathUtils.clamp(turned / FULL_DEPTH_DEG, 0f, 1f);
        if ((s <= 0f && Math.abs(dist - auto) < 0.5f) || !(dist > 1f)) return false;
        straight = yaw == 0f && pitch == 0f;
        float ky = Field.ky();
        depth = DEPTH * ky * s;
        shift = depth * 0.5f;
        viewW = flat.viewportWidth * flat.zoom;
        viewH = flat.viewportHeight * flat.zoom;
        cx = flat.position.x;
        cy = flat.position.y;
        float d = dist * ky;
        // dist is to the front plane when facing straight: the camera circles the middle of the depth at d + shift
        float r = d + shift;
        float cp = MathUtils.cosDeg(pitch);
        cam.viewportWidth = viewW;
        cam.viewportHeight = viewH;
        cam.fieldOfView = fov;
        // as on the 3D page: a negative yaw swings the camera to the right, a positive pitch lifts it; it looks at the
        // screen centre halfway into the columns' depth
        float mx = panX * ky, my = panY * ky, mz = -panZ * depth / DEPTH; // the chart editor's move ({@link #pan})
        cam.position.set(cx + mx - r * cp * MathUtils.sinDeg(yaw), cy + my + r * MathUtils.sinDeg(pitch),
            -shift + mz + r * cp * MathUtils.cosDeg(yaw));
        cam.direction.set(0f, 0f, -1f);
        cam.up.set(0f, 1f, 0f);
        cam.lookAt(cx + mx, cy + my, -shift + mz);
        cam.near = Math.max(1f, d * 0.05f);
        cam.far = r + SEEN * d;
        cam.update();
        inverse.set(cam.combined).inv();
        active = true;
        return true;
    }

    /** @return true while the last {@link #update} drew the field in 3D */
    public boolean active() {
        return active;
    }

    /** @return true while the boxes behind the keys are drawn ({@link #drawBoxes}): in 3D, with depth */
    boolean boxed() {
        return active && depth > 0f;
    }

    /**
     * Where a face {@code z} deeper than the front plane shows (Kelvin 2026-11-07 22:54: a key's or a veil's
     * {@code z}, reference units, positive inward): the factor by which its offset from the eye ({@link #eyeX},
     * {@link #eyeY}) and its size shrink when it is drawn on the front plane where it shows, as the flat picture is;
     * with the eye behind that plane (turned past the side) too, where the ray from the eye through it meets the plane.
     *
     * @return the factor: 1 while flat or for z 0, 0 when the eye is between it and the plane
     */
    public float sink(float z) {
        return sinkTo(z, 0f);
    }

    /** {@link #sink} onto the plane through the middle of the keys' depth, where the hit effects are placed. */
    public float sinkMiddle(float z) {
        return sinkTo(z, shift);
    }

    private float sinkTo(float z, float plane) {
        float cz = cam.position.z + plane;
        if (!active || z == 0f || !(Math.abs(cz) > 1f)) return 1f;
        float d = cz + z * depth / DEPTH;
        return cz > 0f ? (d > 1f ? cz / d : 0f) : (d < -1f ? cz / d : 0f);
    }

    /** @return the x of the eye ({@link #sink}), world units */
    public float eyeX() {
        return cam.position.x;
    }

    /** @return the y of the eye ({@link #sink}), world units */
    public float eyeY() {
        return cam.position.y;
    }

    /** @return the z of the eye, world units ({@link Volumes7}) */
    public float eyeZ() {
        return cam.position.z;
    }

    /** @return how far in front of the eye the camera starts to see, world units */
    public float nearPlane() {
        return cam.near;
    }

    /**
     * The projection to the screen in world units of the flat camera (where presses land), for {@link Volumes7}:
     * into {@code out} the rows r0, r1, r2 (four numbers each), so that world point X (w 1) shows at
     * (r0·X / r2·X, r1·X / r2·X), r2·X being how far in front of the eye it is.
     */
    public void screenRows(float[] out) {
        float[] m = cam.combined.val;
        float hw = 0.5f * viewW, hh = 0.5f * viewH;
        out[0] = hw * m[Matrix4.M00] + cx * m[Matrix4.M30];
        out[1] = hw * m[Matrix4.M01] + cx * m[Matrix4.M31];
        out[2] = hw * m[Matrix4.M02] + cx * m[Matrix4.M32];
        out[3] = hw * m[Matrix4.M03] + cx * m[Matrix4.M33];
        out[4] = hh * m[Matrix4.M10] + cy * m[Matrix4.M30];
        out[5] = hh * m[Matrix4.M11] + cy * m[Matrix4.M31];
        out[6] = hh * m[Matrix4.M12] + cy * m[Matrix4.M32];
        out[7] = hh * m[Matrix4.M13] + cy * m[Matrix4.M33];
        out[8] = m[Matrix4.M30];
        out[9] = m[Matrix4.M31];
        out[10] = m[Matrix4.M32];
        out[11] = m[Matrix4.M33];
    }

    /** Into {@code out} the screen in world units of the flat camera: left, bottom, right, top. */
    public void screenBox(float[] out) {
        out[0] = cx - 0.5f * viewW;
        out[1] = cy - 0.5f * viewH;
        out[2] = cx + 0.5f * viewW;
        out[3] = cy + 0.5f * viewH;
    }

    /** @return the projection the field is drawn through while {@link #active()} */
    public Matrix4 combined() {
        return cam.combined;
    }

    /**
     * Where a hit effect's point shows (Kelvin 2026-11-07 17:04 「和原来的同心圆一样的东西只不过3维化」): the effects are
     * spheres round the middle of the keys' depth, so the point (x, y) of the flat picture, {@code dz} nearer than that
     * middle (scaled with the depth), is projected to the screen, with the size one unit shows at there.
     */
    @Override
    public boolean project(float x, float y, float dz, float[] out) {
        float z = -shift + dz * depth / (DEPTH * Field.ky());
        float[] m = cam.combined.val;
        float w = m[Matrix4.M30] * x + m[Matrix4.M31] * y + m[Matrix4.M32] * z + m[Matrix4.M33];
        if (!(w > cam.near)) return false;
        float px = m[Matrix4.M00] * x + m[Matrix4.M01] * y + m[Matrix4.M02] * z + m[Matrix4.M03];
        float py = m[Matrix4.M10] * x + m[Matrix4.M11] * y + m[Matrix4.M12] * z + m[Matrix4.M13];
        out[0] = cx + 0.5f * viewW * px / w;
        out[1] = cy + 0.5f * viewH * py / w;
        out[2] = 0.5f * viewH * cam.projection.val[Matrix4.M11] / w;
        return true;
    }

    /** The direction (x, y, z) of the field as the camera sees it (x right, y up, z towards the eye). */
    @Override
    public void direction(float x, float y, float z, float[] out) {
        float[] v = cam.view.val;
        out[0] = v[Matrix4.M00] * x + v[Matrix4.M01] * y + v[Matrix4.M02] * z;
        out[1] = v[Matrix4.M10] * x + v[Matrix4.M11] * y + v[Matrix4.M12] * z;
        out[2] = v[Matrix4.M20] * x + v[Matrix4.M21] * y + v[Matrix4.M22] * z;
    }

    /** Which lanes a press goes to first ({@link #toField}): those with something to judge now. */
    public interface Awaits {
        /** @return true when a press on lane {@code i} would judge something now (a key, a running hold) */
        boolean awaits(int i);
    }

    /** {@link #toField(LaneLayout, float, float, Vector2, Awaits)} with no lane awaiting a press. */
    public void toField(LaneLayout lay, float x, float y, Vector2 out) {
        toField(lay, x, y, out, null);
    }

    /**
     * Maps a press at (x, y) of the flat picture (world units) to the field. Facing straight: where its ray crosses the
     * front plane (at the default view the point itself). Turned: where it meets a column (which one: below), else
     * where it crosses the middle of the columns' depth, so a press in a gap goes to the nearer lane. Columns are
     * the lanes' columns ({@link LaneLayout#columnLeft}), any height, where they are moved in depth (Kelvin 2026-11-07
     * 22:54; while lanes move, also facing straight). Of the columns it meets, the front one of a lane that
     * {@code awaits} a press wins, else the front one of a lane that shows, else the front one (Kelvin 2026-11-10 02:10
     * 「这些按键根本点不到」: a chart's scenery, or a hidden lane, in front of a key took the press).
     */
    public void toField(LaneLayout lay, float x, float y, Vector2 out, Awaits awaits) {
        float nx = (x - cx) / (viewW * 0.5f);
        float ny = (y - cy) / (viewH * 0.5f);
        near.set(nx, ny, -1f).prj(inverse);
        far.set(nx, ny, 1f).prj(inverse).sub(near);
        float bestAny = Float.POSITIVE_INFINITY, bestSeen = Float.POSITIVE_INFINITY, bestWant = Float.POSITIVE_INFINITY;
        int any = -1, seen = -1, want = -1;
        metCount = 0;
        for (int i = 0; (!straight || lay.moving()) && i < lay.count(); i++) {
            float w = lay.columnWidth(i);
            if (!lay.active(i) || w <= 0f || !(lay.scale(i) > 0f)) continue; // or its groups size it to nothing
            // an unturned, unsized column's press as before 7.236
            int f = lay.turned(i) || lay.scale(i) != 1f ? laneFrame(i) : -1;
            float t;
            if (f < 0) {
                float cf = frontOf(lay, i);
                t = slab(lay.columnLeft(i), lay.columnLeft(i) + w, cf, cf - thick(lay.length(i)),
                    Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
                if (t < Float.POSITIVE_INFINITY) hitAt.set(near.x + far.x * t, near.y + far.y * t, 0f);
            } else { // a turned lane: the ray in its frame, against its column there (Kelvin 2026-11-09 00:13)
                wNear.set(near);
                wFar.set(far);
                unframe(f, near, false);
                unframe(f, far, true);
                // its column as far as its zones reach; the press is on the ground line where the ray crosses it
                // inside (a lane falling toward the player: its square there), else on the sky line, else where it
                // enters
                float ky = Field.ky();
                float g = lineL + lay.lift(i, Line.LOWER) * ky;
                float u = lineU + lay.lift(i, Line.UPPER) * ky;
                float pad = Field.zoneHalf();
                t = slab(lay.columnLeft(i), lay.columnLeft(i) + w, 0f, -thick(lay.length(i)), Math.min(g, u) - pad,
                    Math.max(g, u) + pad);
                if (t < Float.POSITIVE_INFINITY) {
                    float y0 = near.y + far.y * t;
                    float y1 = near.y + far.y * slabOut;
                    float lo = Math.min(y0, y1);
                    float hi = Math.max(y0, y1);
                    hitAt.set(near.x + far.x * t, g >= lo && g <= hi ? g : u >= lo && u <= hi ? u : y0, 0f);
                }
                near.set(wNear);
                far.set(wFar);
            }
            if (!(t < Float.POSITIVE_INFINITY)) continue;
            met(i, t);
            if (t < bestAny) {
                bestAny = t;
                any = i;
                atAny.set(hitAt);
            }
            if (t < bestSeen && lay.opacity(i) > SEEN_A) {
                bestSeen = t;
                seen = i;
                atSeen.set(hitAt);
            }
            if (t < bestWant && awaits != null && awaits.awaits(i)) {
                bestWant = t;
                want = i;
                atWant.set(hitAt);
            }
        }
        hit = want >= 0 ? want : seen >= 0 ? seen : any;
        float th = want >= 0 ? bestWant : seen >= 0 ? bestSeen : bestAny;
        for (int k = 0; k < metCount; k++) { // a side wall only where it is the hit column's own face too
            metJoint[k] = !metSide[k] || Math.abs(metT[k] - th) * far.len() <= 1f;
        }
        if (hit >= 0) {
            hitAt.set(want >= 0 ? atWant : seen >= 0 ? atSeen : atAny);
            out.set(hitAt.x, hitAt.y);
            return;
        }
        float plane = straight ? 0f : -shift;
        float best = Math.abs(far.z) > 1e-6f ? (plane - near.z) / far.z : 0f;
        out.set(near.x + far.x * best, near.y + far.y * best);
    }

    /** Notes lane i, whose column the ray entered at {@link #hitAt}, t along it ({@link #slabSide}: how). */
    private void met(int i, float t) {
        if (metCount == metLane.length) {
            metLane = java.util.Arrays.copyOf(metLane, metCount * 2);
            metX = java.util.Arrays.copyOf(metX, metCount * 2);
            metY = java.util.Arrays.copyOf(metY, metCount * 2);
            metT = java.util.Arrays.copyOf(metT, metCount * 2);
            metSide = java.util.Arrays.copyOf(metSide, metCount * 2);
            metJoint = java.util.Arrays.copyOf(metJoint, metCount * 2);
        }
        metLane[metCount] = i;
        metX[metCount] = hitAt.x;
        metY[metCount] = hitAt.y;
        metT[metCount] = t;
        metSide[metCount] = slabSide;
        metCount++;
    }

    /**
     * @return how many lanes' columns the last {@link #toField} met: the lanes whose areas on the screen hold the
     *         press (Kelvin 2026-11-10 02:19 「映射在二维的一个判定区域里面」), {@link #hitLane} among them
     */
    public int metCount() {
        return metCount;
    }

    /** @return the k-th lane the last {@link #toField} met ({@link #metCount}) */
    public int metLane(int k) {
        return metLane[k];
    }

    /** @return where the last {@link #toField} met the k-th lane's column, x, as drawn before the lane's turn */
    public float metX(int k) {
        return metX[k];
    }

    /** @return where the last {@link #toField} met the k-th lane's column, y, as drawn before the lane's turn */
    public float metY(int k) {
        return metY[k];
    }

    /**
     * @return true when the k-th lane the last {@link #toField} met is one whose area the press is in to judge its
     *         keys with the hit lane's: its column entered through its front, back or ends, not a side wall (in a row
     *         of columns side by side, a column's side wall shows through its neighbour's front in perspective),
     *         unless where the ray enters the hit lane's column too (columns on one another)
     */
    public boolean metJoint(int k) {
        return metJoint[k];
    }

    /** The lanes the last {@link #toField} met, where, how far along the ray and how ({@link #metCount}). */
    private int metCount;
    private int[] metLane = new int[16];
    private float[] metX = new float[16], metY = new float[16], metT = new float[16];
    private boolean[] metSide = new boolean[16], metJoint = new boolean[16];

    /** Lane opacity at or under which a column takes a press only when no shown one meets it ({@link #toField}). */
    private static final float SEEN_A = 0.002f;
    /** Where {@link #toField}'s ray meets the front column of any lane, of a shown one, of one awaiting a press. */
    private final Vector3 atAny = new Vector3(), atSeen = new Vector3(), atWant = new Vector3();

    /**
     * @return the lane whose column the last {@link #toField} met (its point is then on that column as drawn before
     *         the lane's turn), or −1 when it met none
     */
    public int hitLane() {
        return hit;
    }

    /**
     * The ray of a press at (x, y) of the flat picture for keys on a sea (Kelvin 2026-11-08 19:43 「我目前的这些出现都要能
     * 靠垂直判定判定到」): a key there is judged where the ray is at the key's own depth, at any height, so one nearer or
     * deeper on a turned sea is met where it lands. {@code out[0]}: the ray's x where {@link #toField} meets the field
     * with no column (facing straight the front plane, turned the middle of the keys' depth); {@code out[1]}: how far
     * that x moves per reference unit a key lies deeper ({@link #sink}'s z). A key z deep: out[0] + out[1]·z.
     */
    public void seaRay(float x, float y, float[] out) {
        float nx = (x - cx) / (viewW * 0.5f);
        float ny = (y - cy) / (viewH * 0.5f);
        near.set(nx, ny, -1f).prj(inverse);
        far.set(nx, ny, 1f).prj(inverse).sub(near);
        if (!(Math.abs(far.z) > 1e-6f)) {
            out[0] = near.x;
            out[1] = 0f;
            return;
        }
        float plane = straight ? 0f : -shift;
        out[0] = near.x + far.x * (plane - near.z) / far.z;
        out[1] = -far.x / far.z * depth / DEPTH;
    }

    /** The lane {@link #toField} met last, where, and the ray in world units while it looks in a lane's frame. */
    private int hit = -1;
    private final Vector3 hitAt = new Vector3(), wNear = new Vector3(), wFar = new Vector3();

    /**
     * @return where the ray {@link #near} + t·{@link #far} enters the column over [l, r] × [y0, y1] × [b, f], or +∞;
     *         where it leaves it in {@link #slabOut}, whether it enters through a side wall (x = l or r) in
     *         {@link #slabSide}
     */
    private float slab(float l, float r, float f, float b, float y0, float y1) {
        float lo = 0f;
        float hi = Float.POSITIVE_INFINITY;
        boolean side = false;
        if (y1 < Float.POSITIVE_INFINITY || y0 > Float.NEGATIVE_INFINITY) {
            if (Math.abs(far.y) < 1e-6f) {
                if (near.y < y0 || near.y > y1) return Float.POSITIVE_INFINITY;
            } else {
                float a = (y0 - near.y) / far.y;
                float c = (y1 - near.y) / far.y;
                lo = Math.max(lo, Math.min(a, c));
                hi = Math.min(hi, Math.max(a, c));
            }
        }
        if (Math.abs(far.x) < 1e-6f) {
            if (near.x < l || near.x > r) return Float.POSITIVE_INFINITY;
        } else {
            float a = (l - near.x) / far.x;
            float c = (r - near.x) / far.x;
            side = Math.min(a, c) > lo;
            lo = Math.max(lo, Math.min(a, c));
            hi = Math.min(hi, Math.max(a, c));
        }
        if (Math.abs(far.z) < 1e-6f) {
            if (near.z < b || near.z > f) return Float.POSITIVE_INFINITY;
        } else {
            float a = (b - near.z) / far.z;
            float c = (f - near.z) / far.z;
            if (Math.min(a, c) > lo) side = false;
            lo = Math.max(lo, Math.min(a, c));
            hi = Math.min(hi, Math.max(a, c));
        }
        slabOut = hi;
        slabSide = side;
        return lo <= hi + 1e-6f ? lo : Float.POSITIVE_INFINITY;
    }

    /** Where the ray leaves the column {@link #slab} met last (+∞: never). */
    private float slabOut;
    /** True when the ray enters the column {@link #slab} met last through a side wall. */
    private boolean slabSide;

    // ================================================================== frames

    /**
     * The turned lanes' and seas' frames this frame ({@link #poseFrames}; Kelvin 2026-11-08 23:10 – 11-09 00:13):
     * {@link #FRAME_FLOATS} each, the turn by rows and the shift a world point of the lane or sea as drawn unturned
     * moves by: p' = R·p + t. A lane's puts its column's front (drawn at depth 0) back to its depth and turns it about
     * its origin (the middle of its box); a sea's turns its level surface about its origin.
     */
    private float[] frames = new float[0];
    private int frameCount;
    private static final int FRAME_FLOATS = 12;
    /** Each frame's size factor ({@link #addFrame}): its turn is that times a turn's. */
    private float[] frameK = new float[0];
    /** Each lane's and each sea's frame this frame, −1 while it has none ({@link #ownFrame}, a sea not turned). */
    private int[] laneFrames = new int[0];
    private int[] seaFrames = new int[0];
    private int laneFrameCount, seaFrameCount;
    /** The frame the vertices, boxes and sheets go through next ({@link #frame(int)}; −1: none). */
    private int curFrame = -1;
    private final float[] axes = new float[9];
    /** The chart's lines this frame ({@link #poseFrames}), world y. */
    private float lineL, lineU;
    private final Matrix4 frameProj = new Matrix4();
    private final Matrix4 frameTmp = new Matrix4();

    /**
     * @return true when lane i is drawn through a frame of its own: turned ({@link LaneLayout#turned}), moved in
     *         depth ({@link LaneLayout#moveZ}) or sized by its groups ({@link LaneLayout#scale}). An unturned one moved
     *         in depth was drawn shrunk onto the front plane until 7.236, its lines' and glows' sizes not: huge with
     *         the eye near that plane (Kelvin 2026-11-10 20:56)
     */
    static boolean ownFrame(LaneLayout lay, int i) {
        return lay.turned(i) || lay.moveZ(i) != 0f || lay.scale(i) != 1f;
    }

    /** Sets this frame's frames: one for each lane with a frame of its own ({@link #ownFrame}) and each turned sea. */
    private void poseFrames(GameEngine e) {
        LaneLayout lay = e.layout();
        Chart chart = e.chart();
        laneFrameCount = lay.count();
        seaFrameCount = chart != null ? chart.seas().size() : 0;
        if (laneFrames.length < laneFrameCount) laneFrames = new int[laneFrameCount];
        if (seaFrames.length < seaFrameCount) seaFrames = new int[seaFrameCount];
        frameCount = 0;
        stamp++;
        float ky = Field.ky();
        float yl = e.lineY(Line.LOWER);
        float yu = e.lineY(Line.UPPER);
        lineL = yl;
        lineU = yu;
        for (int i = 0; i < laneFrameCount; i++) {
            laneFrames[i] = -1;
            if (!(depth > 0f) || !ownFrame(lay, i)) continue;
            lay.axes(i, axes);
            float f = -lay.moveZ(i) * depth / DEPTH;
            float py = 0.5f * (yl + lay.lift(i, Line.LOWER) * ky + yu + lay.lift(i, Line.UPPER) * ky);
            // it turns about its origin as charted, whatever the key size draws it at
            laneFrames[i] = addFrame(lay.centre(i), py, f - 0.5f * thick(lay.chartLength(i)), f, lay.scale(i));
        }
        float beat = lay.beat();
        for (int j = 0; j < seaFrameCount; j++) {
            Sea s = chart.seas().get(j);
            seaFrames[j] = -1;
            if (!(depth > 0f) || !s.turn(beat, axes)) continue;
            seaFrames[j] = addFrame(lay.freeX(s.at(Sea.ORIGIN_X, beat)), Field.y(s.yAt(beat)),
                -(Sea.MID_Z + s.at(Sea.ORIGIN_Z, beat)) * depth / DEPTH, 0f, 1f);
        }
    }

    /**
     * Adds the frame turning by {@link #axes} (chart axes, z inward) and sizing by k about (px, py, pz) after a shift
     * dz in depth.
     */
    private int addFrame(float px, float py, float pz, float dz, float k) {
        int o = frameCount * FRAME_FLOATS;
        if (frames.length < o + FRAME_FLOATS) frames = java.util.Arrays.copyOf(frames, 2 * (o + FRAME_FLOATS));
        if (frameK.length <= frameCount) frameK = java.util.Arrays.copyOf(frameK, 2 * frameCount + 2);
        frameK[frameCount] = k;
        float[] a = axes; // in the view's axes (z towards the eye): z's sign changed on both sides
        frames[o] = a[0];
        frames[o + 1] = a[3];
        frames[o + 2] = -a[6];
        frames[o + 3] = a[1];
        frames[o + 4] = a[4];
        frames[o + 5] = -a[7];
        frames[o + 6] = -a[2];
        frames[o + 7] = -a[5];
        frames[o + 8] = a[8];
        for (int j = 0; k != 1f && j < 9; j++) frames[o + j] *= k;
        float qx = -px; // p' = R·(p + (0, 0, dz) − P) + P
        float qy = -py;
        float qz = dz - pz;
        frames[o + 9] = px + frames[o] * qx + frames[o + 1] * qy + frames[o + 2] * qz;
        frames[o + 10] = py + frames[o + 3] * qx + frames[o + 4] * qy + frames[o + 5] * qz;
        frames[o + 11] = pz + frames[o + 6] * qx + frames[o + 7] * qy + frames[o + 8] * qz;
        return frameCount++;
    }

    /** @return lane i's frame this frame, or −1 while it has none ({@link #ownFrame}; or the field is flat) */
    public int laneFrame(int i) {
        return active && i >= 0 && i < laneFrameCount ? laneFrames[i] : -1;
    }

    /** @return sea j's frame this frame (index into {@link org.kelvinizer.elements.Chart#seas()}), as laneFrame */
    public int seaFrame(int j) {
        return active && j >= 0 && j < seaFrameCount ? seaFrames[j] : -1;
    }

    /**
     * @return the frame key n is drawn through this frame: its sea's ({@link #seaFrame}) or its lane's
     *         ({@link LaneLayout#keyLane}); a space key's (Kelvin 2026-11-08 23:20): none along its own path, else its
     *         lane's as frozen at its beat ({@link LaneLayout#freezeSpaceKeys}); −1 while that has none
     */
    public int keyFrame(LaneLayout lay, Note n) {
        if (n.spacePath()) return -1;
        if (n.spaceTrack() && n.sea() == null) return frozenFrame(lay, n);
        return n.sea() != null ? seaFrame(n.seaIndex()) : laneFrame(lay.keyLane(n));
    }

    /** Space keys' frozen lanes' frames by note index, and the {@link #stamp} each was made in. */
    private int[] spaceFrames = new int[0], spaceStamps = new int[0];
    /** Counts {@link #poseFrames}, so a frozen lane's frame is made once a frame. */
    private int stamp;

    /** @return the frame of space key n's lane frozen turned at its beat, made once this frame; else −1 */
    private int frozenFrame(LaneLayout lay, Note n) {
        int k = n.index();
        if (!active || !(depth > 0f) || k < 0 || !LaneLayout.frozenTurn(n, axes)) return -1;
        if (spaceFrames.length <= k) {
            spaceFrames = java.util.Arrays.copyOf(spaceFrames, 2 * k + 2);
            spaceStamps = java.util.Arrays.copyOf(spaceStamps, 2 * k + 2);
        }
        if (spaceStamps[k] == stamp) return spaceFrames[k];
        float ky = Field.ky();
        float py = 0.5f * (lineL + lay.keyLift(n, Line.LOWER) * ky + lineU + lay.keyLift(n, Line.UPPER) * ky);
        float f = -lay.keyMoveZ(n) * depth / DEPTH;
        spaceStamps[k] = stamp;
        float pz = f - 0.5f * thick(lay.keyChartLength(n));
        return spaceFrames[k] = addFrame(lay.keyCentre(n, lay.beat()), py, pz, f, 1f);
    }

    /**
     * @return the frame key n's lane had at an earlier moment, {@code past} (a layout at that moment) and the chart's
     *         lines then at world y {@code yl} and {@code yu} placing it as {@link #poseFrames} does: where an
     *         afterimage copy of a key moving with its lane shows (Kelvin 2026-11-10 02:17 「残影要加上」); made this
     *         frame, the last one again when it is the same; −1 while that lane had none ({@link #ownFrame})
     */
    public int pastFrame(LaneLayout past, Note n, float yl, float yu) {
        int i = past.keyLane(n);
        if (!active || !(depth > 0f) || i < 0 || !ownFrame(past, i)) return -1;
        past.axes(i, axes);
        float ky = Field.ky();
        float f = -past.moveZ(i) * depth / DEPTH;
        float py = 0.5f * (yl + past.lift(i, Line.LOWER) * ky + yu + past.lift(i, Line.UPPER) * ky);
        int k = addFrame(past.centre(i), py, f - 0.5f * thick(past.chartLength(i)), f, past.scale(i));
        if (k == 0 || !sameAsLast(k)) return k;
        frameCount--;
        return k - 1;
    }

    /** @return true when frame k turns and shifts as frame k − 1 does */
    private boolean sameAsLast(int k) {
        int o = k * FRAME_FLOATS;
        for (int j = 0; j < FRAME_FLOATS; j++) {
            if (frames[o + j] != frames[o - FRAME_FLOATS + j]) return false;
        }
        return true;
    }

    /**
     * Where the hit effect of a key at (x, y) of frame f, {@code z} reference units deep in it, shows: the middle of
     * its depth through the frame, on the effects' plane ({@link #sinkPoint}) into {@code out[0..1]}.
     *
     * @return the factor sizes shrink by there
     */
    public float frameFx(int f, float x, float y, float z, float[] out) {
        framePoint(f, x, y, worldZ(z) - shift, fxTmp);
        float t = sinkPoint(fxTmp[0], fxTmp[1], fxTmp[2], out);
        return frameK[f] != 1f ? t * frameK[f] : t; // a lane its groups size: its effects too
    }

    private final float[] fxTmp = new float[3];

    /**
     * How far round a column its field picture may reach past its edges and lines, world units, besides the zone and
     * the column glow ({@link #cull}): the separators' glow, the ticks, the line ripples and swells.
     */
    private static final float CULL_MARGIN = 64f;
    /** Lanes whose column or field picture may show this frame ({@link #cull}); none culled past the count. */
    private boolean[] laneSeen = new boolean[0];
    private int laneSeenCount;
    private final float[] cullTmp = new float[3];

    /**
     * Where point (x, y) of frame f's flat picture (on its front, f −1: the front plane) shows in the flat picture:
     * the front plane's point on the eye's ray through it, into {@code out[0..1]} (itself while the view is flat).
     */
    public void frontPoint(int f, float x, float y, float[] out) {
        framePoint(f, x, y, 0f, fxTmp);
        out[0] = fxTmp[0];
        out[1] = fxTmp[1];
        float ez = cam.position.z;
        float d = fxTmp[2] - ez;
        if (!active || Math.abs(d) < 1f) return;
        float t = -ez / d;
        out[0] = cam.position.x + (fxTmp[0] - cam.position.x) * t;
        out[1] = cam.position.y + (fxTmp[1] - cam.position.y) * t;
    }

    /** Sets the frame the boxes and sheets recorded next, and the vertices drawn next, go through (−1: none). */
    void frame(int f) {
        curFrame = f;
    }

    /**
     * @return the projection frame f's flat picture is drawn through ({@link #combined()} after the frame), the
     *         picture {@code z} reference units deeper than its plane (a key's own depth); valid until the next call
     */
    public Matrix4 frameCombined(int f, float z) {
        int o = f * FRAME_FLOATS;
        float dz = -z * depth / DEPTH;
        float[] m = frameTmp.val;
        m[Matrix4.M00] = frames[o];
        m[Matrix4.M01] = frames[o + 1];
        m[Matrix4.M02] = frames[o + 2];
        m[Matrix4.M03] = frames[o + 9] + frames[o + 2] * dz;
        m[Matrix4.M10] = frames[o + 3];
        m[Matrix4.M11] = frames[o + 4];
        m[Matrix4.M12] = frames[o + 5];
        m[Matrix4.M13] = frames[o + 10] + frames[o + 5] * dz;
        m[Matrix4.M20] = frames[o + 6];
        m[Matrix4.M21] = frames[o + 7];
        m[Matrix4.M22] = frames[o + 8];
        m[Matrix4.M23] = frames[o + 11] + frames[o + 8] * dz;
        m[Matrix4.M30] = m[Matrix4.M31] = m[Matrix4.M32] = 0f;
        m[Matrix4.M33] = 1f;
        return frameProj.set(cam.combined).mul(frameTmp);
    }

    /**
     * @return the projection the flat picture is drawn through {@code z} reference units deeper than the front plane
     *         (an unturned column's front moved in depth, {@link LaneLayout#moveZ}); valid until the next call
     */
    public Matrix4 combinedAt(float z) {
        return frameProj.set(cam.combined).translate(0f, 0f, -z * depth / DEPTH);
    }

    /** Puts into {@code out[0..2]} where world point (x, y, z) of frame f's lane or sea goes (f −1: itself). */
    public void framePoint(int f, float x, float y, float z, float[] out) {
        if (f < 0) {
            out[0] = x;
            out[1] = y;
            out[2] = z;
            return;
        }
        int o = f * FRAME_FLOATS;
        out[0] = frames[o] * x + frames[o + 1] * y + frames[o + 2] * z + frames[o + 9];
        out[1] = frames[o + 3] * x + frames[o + 4] * y + frames[o + 5] * z + frames[o + 10];
        out[2] = frames[o + 6] * x + frames[o + 7] * y + frames[o + 8] * z + frames[o + 11];
    }

    /** Takes world point or direction v back through frame f (the inverse of {@link #framePoint}). */
    private void unframe(int f, Vector3 v, boolean direction) {
        int o = f * FRAME_FLOATS;
        float x = v.x - (direction ? 0f : frames[o + 9]);
        float y = v.y - (direction ? 0f : frames[o + 10]);
        float z = v.z - (direction ? 0f : frames[o + 11]);
        v.set(frames[o] * x + frames[o + 3] * y + frames[o + 6] * z, frames[o + 1] * x + frames[o + 4] * y
            + frames[o + 7] * z, frames[o + 2] * x + frames[o + 5] * y + frames[o + 8] * z);
        float k = frameK[f];
        if (k != 1f) v.scl(1f / (k * k)); // its turn is k times a turn's
    }

    /** @return the world depth of reference depth z (a key's z: 0 the columns' front, positive inward) */
    public float worldZ(float z) {
        return -z * depth / DEPTH;
    }

    /**
     * Where world point (x, y, z) shows on the plane through the middle of the keys' depth, as {@link #sinkMiddle}
     * puts a deeper key: into {@code out[0..1]} the point of that plane on the eye's ray through it.
     *
     * @return the factor sizes shrink by there (1 on that plane; 0 when the eye is between)
     */
    public float sinkPoint(float x, float y, float z, float[] out) {
        out[0] = x;
        out[1] = y;
        if (!active) return 1f;
        float ez = cam.position.z;
        float d = z - ez;
        float t = Math.abs(d) > 1f ? (-shift - ez) / d : 0f;
        if (!(t > 0f)) return 0f;
        out[0] = cam.position.x + (x - cam.position.x) * t;
        out[1] = cam.position.y + (y - cam.position.y) * t;
        return t;
    }

    // ================================================================== drawing

    /**
     * Draws the columns (sides reaching past the lines as the chart sets, {@link Chart#upperExtendAt}, and fading out;
     * edges) and every column's mother planes behind the flat picture, times {@code alpha} and how much the chart
     * shows its lanes; a moved column (Kelvin 2026-11-07 22:54) up, in depth and with its reach scaled. Call outside
     * the batch.
     */
    void drawBack(GameEngine e, float alpha) {
        poseFrames(e);
        laneSeenCount = 0;
        if (depth <= 0f) {
            boxCount = 0;
            framed = 0;
            return;
        }
        LaneLayout lay = e.layout();
        Chart chart = e.chart();
        float beat = lay.beat();
        float a = alpha * (chart != null ? chart.lanesShown(beat) : 1f);
        begin(GL20.GL_TRIANGLES);
        float ky = Field.ky();
        float yu = e.lineY(Line.UPPER);
        float yl = e.lineY(Line.LOWER);
        float up = (chart != null ? chart.upperExtendAt(beat) : Chart.DEFAULT_EXTEND) * ky;
        float down = (chart != null ? chart.lowerExtendAt(beat) : Chart.DEFAULT_EXTEND) * ky;
        float lo = Math.min(yu, yl);
        float hi = Math.max(yu, yl);
        float fade = COLUMN_FADE * ky;
        int n = columnStops(lo, hi, down, up, fade, stops);
        cull(lay, n, yl, yu, down, up, fade);
        for (int i = 0; a > 0f && i < lay.count(); i++) {
            float w = lay.columnWidth(i);
            float op = lay.opacity(i) * a;
            if (!lay.active(i) || w <= 0f || op <= 0.002f || !laneSeen(i)) continue;
            float l = lay.columnLeft(i);
            float r = l + w;
            int m = column(lay, i, n, yl, yu, down, up, fade);
            frame(laneFrame(i));
            tint(lay.mine(i));
            for (int k = 0; k + 1 < m; k++) {
                float y0 = cur[2 * k];
                float y1 = cur[2 * k + 2];
                float a0 = FACE_A * op * cur[2 * k + 1];
                float a1 = FACE_A * op * cur[2 * k + 3];
                quadZ(l, y0, a0, l, y1, a1, face);  // left side
                quadZ(r, y0, a0, r, y1, a1, face);  // right side
                wall(l, r, y0, a0, y1, a1, back);   // back
                wall(l, r, y0, a0, y1, a1, front);  // front
            }
            for (int li = 0; li < Line.COUNT; li++) {
                float y = e.lineY(Line.of(li)) + lay.lift(i, Line.of(li)) * ky;
                float pa = PLANE_A * op;
                vertex(l, y, front, plane, pa);
                vertex(r, y, front, plane, pa);
                vertex(r, y, back, plane, pa);
                vertex(l, y, front, plane, pa);
                vertex(r, y, back, plane, pa);
                vertex(l, y, back, plane, pa);
            }
            frame(-1);
        }
        begin(GL20.GL_LINES);
        for (int i = 0; a > 0f && i < lay.count(); i++) {
            float w = lay.columnWidth(i);
            float op = lay.opacity(i) * a;
            if (!lay.active(i) || w <= 0f || op <= 0.002f || !laneSeen(i)) continue;
            float l = lay.columnLeft(i);
            float r = l + w;
            int m = column(lay, i, n, yl, yu, down, up, fade);
            frame(laneFrame(i));
            tint(lay.mine(i));
            for (int k = 0; k + 1 < m; k++) {
                for (int c = 0; c < 4; c++) {
                    float x = c % 2 == 0 ? l : r;
                    float z = c < 2 ? front : back;
                    vertex(x, cur[2 * k], z, edge, EDGE_A * op * cur[2 * k + 1]);
                    vertex(x, cur[2 * k + 2], z, edge, EDGE_A * op * cur[2 * k + 3]);
                }
            }
            for (int li = 0; li < Line.COUNT; li++) {
                float y = e.lineY(Line.of(li)) + lay.lift(i, Line.of(li)) * ky;
                vertex(l, y, back, rim, op);
                vertex(r, y, back, rim, op);
                vertex(l, y, front, rim, op);
                vertex(l, y, back, rim, op);
                vertex(r, y, front, rim, op);
                vertex(r, y, back, rim, op);
            }
            frame(-1);
        }
        tint(0f);
        front = 0f;
        end();
        boxCount = 0;
        framed = 0;
        sheetCount = 0;
    }

    /**
     * Lane i's column this frame (Kelvin 2026-11-07 22:54, 11-08 23:10): its stops ({@link #cur}: the shared {@code n}
     * of {@link #stops}, or its own when its lines lie apart from the chart's ({@link LaneLayout#lift}) or its reach is
     * scaled) and its front's depth ({@link #front}, view units: 0 the columns' front, negative inward; 0 for a lane
     * with a frame of its own, {@link #ownFrame}, which puts it back).
     *
     * @return the number of stops
     */
    private int column(LaneLayout lay, int i, int n, float yl, float yu, float down, float up, float fade) {
        float lg = lay.lift(i, Line.LOWER) * Field.ky();
        float ls = lay.lift(i, Line.UPPER) * Field.ky();
        front = laneFrame(i) >= 0 ? 0f : frontOf(lay, i);
        back = front - thick(lay.length(i));
        float k = lay.reach(i);
        float ks = lay.reachSky(i); // a v7 lane's own past its sky face (Kelvin 2026-11-09 18:44)
        if (lg == 0f && ls == 0f && k == 1f && ks == 1f) {
            cur = stops;
            return n;
        }
        cur = laneStops;
        float a = yl + lg;
        float b = yu + ls;
        return columnStops(Math.min(a, b), Math.max(a, b), down * k, up * ks, fade, laneStops);
    }

    /**
     * Works out {@link #laneSeen} for this frame (Kelvin 2026-11-10 01:34 「想尽一切办法让弱一点的手机/平板也能正常游玩」;
     * My Soul Deep's graveyard has 1079 lanes): a lane is left out when its column, grown by its field picture's reach
     * (the zone round each of its lines, the column glow, {@link #CULL_MARGIN}), lies wholly outside the camera's view.
     */
    private void cull(LaneLayout lay, int n, float yl, float yu, float down, float up, float fade) {
        laneSeenCount = lay.count();
        if (laneSeen.length < laneSeenCount) laneSeen = new boolean[laneSeenCount];
        float ky = Field.ky();
        float reach = Math.max(Field.zoneHalf(), FieldRenderer.COLUMN_H) + CULL_MARGIN;
        for (int i = 0; i < laneSeenCount; i++) {
            float w = lay.columnWidth(i);
            int f = laneFrame(i);
            if (!(w > 0f)) {
                laneSeen[i] = true;
                continue;
            }
            int m = column(lay, i, n, yl, yu, down, up, fade);
            float gl = yl + lay.lift(i, Line.LOWER) * ky;
            float gu = yu + lay.lift(i, Line.UPPER) * ky;
            float y0 = Math.min(gl, gu) - reach;
            float y1 = Math.max(gl, gu) + reach;
            if (m > 0) {
                y0 = Math.min(y0, cur[0]);
                y1 = Math.max(y1, cur[2 * m - 2]);
            }
            float l = lay.columnLeft(i);
            laneSeen[i] = boxSeen(f, l - CULL_MARGIN, l + w + CULL_MARGIN, y0 - CULL_MARGIN, y1 + CULL_MARGIN,
                Math.min(back, front), Math.max(back, front));
        }
        front = 0f;
    }

    /**
     * @return false when lane i's column and its field picture lie wholly outside the camera's view this frame
     *         ({@link #cull}, worked out in {@link #drawBack}): nothing of them would reach the screen
     */
    public boolean laneSeen(int i) {
        return i < 0 || i >= laneSeenCount || laneSeen[i];
    }

    /**
     * @return false when the box [x0, x1] × [y0, y1] × [z0, z1] of frame f (−1: none) lies wholly past one side of the
     *         camera's view (all its corners outside the same clip plane), true when it may show
     */
    private boolean boxSeen(int f, float x0, float x1, float y0, float y1, float z0, float z1) {
        float[] m = cam.combined.val;
        int all = 0x3f;
        for (int c = 0; c < 8 && all != 0; c++) {
            framePoint(f, (c & 1) == 0 ? x0 : x1, (c & 2) == 0 ? y0 : y1, (c & 4) == 0 ? z0 : z1, cullTmp);
            float px = cullTmp[0], py = cullTmp[1], pz = cullTmp[2];
            float cx = m[Matrix4.M00] * px + m[Matrix4.M01] * py + m[Matrix4.M02] * pz + m[Matrix4.M03];
            float cy = m[Matrix4.M10] * px + m[Matrix4.M11] * py + m[Matrix4.M12] * pz + m[Matrix4.M13];
            float cz = m[Matrix4.M20] * px + m[Matrix4.M21] * py + m[Matrix4.M22] * pz + m[Matrix4.M23];
            float cw = m[Matrix4.M30] * px + m[Matrix4.M31] * py + m[Matrix4.M32] * pz + m[Matrix4.M33];
            all &= (cx < -cw ? 1 : 0) | (cx > cw ? 2 : 0) | (cy < -cw ? 4 : 0) | (cy > cw ? 8 : 0)
                | (cz < -cw ? 16 : 0) | (cz > cw ? 32 : 0);
        }
        return all == 0;
    }

    /**
     * @return how deep a column {@code len} reference units long is drawn this frame, view units (its base's length,
     *         Kelvin 2026-11-09 05:00; {@link #depth} for the default 240)
     */
    private float thick(float len) {
        return len == LaneLayout.DEFAULT_LENGTH ? depth : len * depth / DEPTH;
    }

    /**
     * @return the depth of lane i's column's front this frame, view units (0 unless its origin is moved in depth or
     *         its base is not 240 long)
     */
    private float frontOf(LaneLayout lay, int i) {
        float z = lay.moveZ(i);
        return z != 0f ? -z * depth / DEPTH : 0f;
    }

    /**
     * Fills {@code out} with (y, opacity) stops up a column, bottom first: from {@code below} under the ground line
     * {@code lo} to {@code above} over the sky line {@code hi}, solid between and fading out (smoothstep) over the
     * last {@code fade} of each reach; a reach of 0 ends the column solid at its line.
     *
     * @return the number of stops (pairs) written
     */
    static int columnStops(float lo, float hi, float below, float above, float fade, float[] out) {
        int n = 0;
        float fb = Math.min(fade, below);
        float fa = Math.min(fade, above);
        float bottom = lo - below;
        float top = hi + above;
        for (int k = 0; fb > 0f && k < FADE_SEGMENTS; k++) {
            float t = k / (float) FADE_SEGMENTS;
            out[n++] = bottom + fb * t;
            out[n++] = t * t * (3f - 2f * t);
        }
        out[n++] = bottom + fb;
        out[n++] = 1f;
        out[n++] = top - fa;
        out[n++] = 1f;
        for (int k = 1; fa > 0f && k <= FADE_SEGMENTS; k++) {
            float t = k / (float) FADE_SEGMENTS;
            out[n++] = top - fa + fa * t;
            out[n++] = 1f - t * t * (3f - 2f * t);
        }
        return n / 2;
    }

    /**
     * Records the box behind a key's front ({@link NoteRenderer#collectBoxes}): the rectangle {@code w} wide and
     * {@code len} long from the leading-edge centre (x, y) turned by {@code rot} degrees, in colour c at {@code a}.
     */
    void box(float x, float y, float w, float len, float rot, Color c, float a) {
        box(x, y, w, len, rot, c, a, null, 0f, 0f);
    }

    /**
     * {@link #box(float, float, float, float, float, Color, float)} with a sync frame (Kelvin 2026-11-07 19:37): the
     * edges of its back and along its depth glow in {@code frame} (null: none) like the flat picture's frame round its
     * front, a solid band at {@code bandA} in a soft halo at {@code haloA}: a thinner line ({@link #FRAME_LINE}).
     */
    void box(float x, float y, float w, float len, float rot, Color c, float a, Color frame, float bandA,
             float haloA) {
        int style = nextStyle;
        nextStyle = BOX_PLAIN;
        if (!(a > 0.002f) || !(w > 0f) || !(len > 0f)) return;
        int k = boxCount * BOX_FLOATS;
        if (boxes.length < k + BOX_FLOATS) {
            boxes = java.util.Arrays.copyOf(boxes, Math.max(10 * BOX_FLOATS, 2 * (k + BOX_FLOATS)));
        }
        boxes[k] = x;
        boxes[k + 1] = y;
        boxes[k + 2] = w;
        boxes[k + 3] = len;
        boxes[k + 4] = rot;
        boxes[k + 5] = c.r;
        boxes[k + 6] = c.g;
        boxes[k + 7] = c.b;
        boxes[k + 8] = a;
        boolean lit = frame != null && (bandA > 0.002f || haloA > 0.002f);
        boxes[k + 9] = lit ? frame.r : 0f;
        boxes[k + 10] = lit ? frame.g : 0f;
        boxes[k + 11] = lit ? frame.b : 0f;
        boxes[k + 12] = lit ? bandA : 0f;
        boxes[k + 13] = lit ? haloA : 0f;
        boxes[k + 14] = turnZ;
        boxes[k + 15] = turnX;
        boxes[k + 16] = turnY;
        boxes[k + 17] = turnPivot;
        boxes[k + 18] = curFrame;
        boxes[k + 19] = turnLen;
        boxes[k + 20] = style;
        if (lit) framed++;
        boxCount++;
    }

    /** Sets the style of the next box recorded ({@link #BOX_PLAIN}, {@link #BOX_MINE}); after it, plain again. */
    void style(int s) {
        nextStyle = s;
    }

    /**
     * Sets the pose of the boxes recorded next (a key's 3D motion, Kelvin 2026-11-07 22:54): its front {@code z} deeper
     * than the front plane (reference units), turned {@code rx} degrees about the key's across axis and {@code ry}
     * about its along axis, both through the point {@code pivot} (world units) along it from the leading edge on its
     * front, before its turn {@code rot}, {@code len} reference units deep (its column's base's length, Kelvin
     * 2026-11-09 05:00). All 0 (and 240) is the box as before.
     */
    void turn(float z, float rx, float ry, float pivot, float len) {
        turnLen = thick(len);
        turnZ = -z * depth / DEPTH;
        turnX = rx;
        turnY = ry;
        turnPivot = pivot;
    }

    /** Fills {@link #p3} with box k's corners in 3D: the front's four (counter-clockwise when unturned), the back's. */
    private void corners(int k) {
        float hw = boxes[k + 2] * 0.5f;
        float len = boxes[k + 3];
        float cos = MathUtils.cosDeg(boxes[k + 4]);
        float sin = MathUtils.sinDeg(boxes[k + 4]);
        float cx = MathUtils.cosDeg(boxes[k + 15]);
        float sx = MathUtils.sinDeg(boxes[k + 15]);
        float cy = MathUtils.cosDeg(boxes[k + 16]);
        float sy = MathUtils.sinDeg(boxes[k + 16]);
        float pivot = boxes[k + 17];
        for (int c = 0; c < 8; c++) {
            int f = c & 3;
            float lx = f == 0 || f == 3 ? -hw : hw;
            float ly = (f < 2 ? 0f : len) - pivot;
            float lz = c < 4 ? 0f : -boxes[k + 19];
            // about the along axis, then the across axis through the pivot; then the key's own turn
            float ax = lx * cy + lz * sy;
            float az = lz * cy - lx * sy;
            float ay = ly * cx - az * sx + pivot;
            p3[3 * c] = boxes[k] + ax * cos - ay * sin;
            p3[3 * c + 1] = boxes[k + 1] + ax * sin + ay * cos;
            p3[3 * c + 2] = boxes[k + 14] + ly * sx + az * cx;
        }
        int f = (int) boxes[k + 18];
        for (int c = 0; f >= 0 && c < 8; c++) { // on a turned lane or sea: through its frame
            int o = f * FRAME_FLOATS;
            float x = p3[3 * c], y = p3[3 * c + 1], z = p3[3 * c + 2];
            p3[3 * c] = frames[o] * x + frames[o + 1] * y + frames[o + 2] * z + frames[o + 9];
            p3[3 * c + 1] = frames[o + 3] * x + frames[o + 4] * y + frames[o + 5] * z + frames[o + 10];
            p3[3 * c + 2] = frames[o + 6] * x + frames[o + 7] * y + frames[o + 8] * z + frames[o + 11];
        }
    }

    /**
     * Records a TIDE head ({@link NoteRenderer#collectBoxes}): over {@code [x0, x1]} at y on {@code line} it lies on
     * that line's mother planes as a sheet as deep as the columns (Kelvin 2026-11-07 06:35: one piece over each column
     * it spans, at that column's line and depth, through a turned lane's frame), in colour c at {@code a}; its wave or
     * pearl thread is the sheet's front edge in the flat picture. A turned TIDE is turned by {@code deg} about (px, py)
     * as it is drawn. {@code lane}: {@link #SHEET_ACROSS}, one lane's whole column (Kelvin 2026-11-09 00:01), or
     * {@link #SHEET_SEA}: one piece over [x0, x1] and depths [d0, d1] through {@code frame} (on a sea).
     */
    void sheet(float x0, float x1, float y, Color c, float a, float deg, float px, float py, Line line, int lane,
               float d0, float d1, int frame) {
        if (!(a > 0.002f) || !(x1 > x0)) return;
        int k = sheetCount * SHEET_FLOATS;
        if (sheets.length < k + SHEET_FLOATS) sheets = java.util.Arrays.copyOf(sheets, 2 * (k + SHEET_FLOATS));
        sheets[k] = x0;
        sheets[k + 1] = x1;
        sheets[k + 2] = y;
        sheets[k + 3] = c.r;
        sheets[k + 4] = c.g;
        sheets[k + 5] = c.b;
        sheets[k + 6] = a;
        sheets[k + 7] = deg;
        sheets[k + 8] = px;
        sheets[k + 9] = py;
        sheets[k + 10] = line.ordinal();
        sheets[k + 11] = d0;
        sheets[k + 12] = d1;
        sheets[k + 13] = frame;
        sheets[k + 14] = lane;
        sheetCount++;
    }

    /**
     * Draws the recorded TIDE sheets (one piece a column on gapped lanes), then the key boxes' sides and backs (only
     * the faces turned to the camera), times {@code alpha}.
     */
    void drawBoxes(LaneLayout lay, float alpha, float seconds) {
        if (depth > 0f) drawLandings(lay, alpha, seconds);
        landCount = 0;
        if (sheetCount > 0 && depth > 0f) {
            begin(GL20.GL_TRIANGLES);
            boolean gapped = lay.separate();
            // a base of its own length (Kelvin 2026-11-09 05:00): a piece over each column, as deep as it
            for (int i = 0; !gapped && lay.moving() && i < lay.count(); i++) {
                gapped = lay.length(i) != LaneLayout.DEFAULT_LENGTH;
            }
            float ky = Field.ky();
            for (int s = 0; s < sheetCount; s++) {
                int k = s * SHEET_FLOATS;
                tmp.set(sheets[k + 3], sheets[k + 4], sheets[k + 5], 1f);
                float a = SHEET_A * sheets[k + 6] * alpha;
                Line line = Line.of((int) sheets[k + 10]);
                int lane = (int) sheets[k + 14];
                boolean each = gapped || lane >= 0;
                for (int i = 0; i < (lane == SHEET_SEA ? 1 : each ? lay.count() : 1); i++) {
                    float l = sheets[k];
                    float r = sheets[k + 1];
                    float y = sheets[k + 2];
                    float d0 = 0f;
                    float d1 = DEPTH;
                    if (lane == SHEET_SEA) { // on a sea: its depths, through its frame
                        d0 = sheets[k + 11];
                        d1 = sheets[k + 12];
                        frame((int) sheets[k + 13]);
                    } else if (each) {
                        if (lane >= 0 && i != lane || !lay.active(i) || lay.columnWidth(i) <= 0f) continue;
                        float cl = lay.columnLeft(i);
                        float cr = cl + lay.columnWidth(i);
                        l = lane >= 0 ? cl : Math.max(l, cl);
                        r = lane >= 0 ? cr : Math.min(r, cr);
                        if (r <= l) continue;
                        y += lay.lift(i, line) * ky;   // its column's line
                        int f = laneFrame(i);
                        d1 = lay.length(i);            // its base's length
                        if (f >= 0) {
                            frame(f);
                        } else {
                            d0 += lay.moveZ(i);         // at its column's depth
                            d1 += lay.moveZ(i);
                        }
                    }
                    // each piece heaves on its own x and depth (16:16 「海浪也这么处理」)
                    heaveSheet(l, 0.5f * (d0 + d1), y, 0f, 0f, sheets[k + 7], sheets[k + 8], sheets[k + 9],
                        seconds, false);
                    if (line == Line.UPPER) pearlSheet(l, r, d0, d1, tmp, a);
                    else heaving(l, r, d0, d1, tmp, a, false, false);
                    frame(-1);
                }
            }
            end();
        }
        sheetCount = 0;
        if (boxCount == 0 || depth <= 0f) return;
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);
        begin(GL20.GL_TRIANGLES);
        float off = NoteShapes.stripeOffset(seconds); // a LANE MINE column's stripes, as its front's
        for (int b = 0; b < boxCount; b++) {
            int k = b * BOX_FLOATS;
            float a = KEY_A * boxes[k + 8] * alpha;
            boolean mine = boxes[k + 20] == BOX_MINE;
            boolean column = boxes[k + 20] == BOX_COLUMN;
            float hw = 0.5f * boxes[k + 2];
            float len = boxes[k + 3];
            float d = boxes[k + 19];
            corners(k);
            for (int c = 0; c < 4; c++) {
                int n = (c + 1) % 4;
                // outward normal (back − front) × (next − this); lit from above, a little from the left
                float ex = p3[3 * n] - p3[3 * c];
                float ey = p3[3 * n + 1] - p3[3 * c + 1];
                float ez = p3[3 * n + 2] - p3[3 * c + 2];
                float dx = p3[3 * c + 12] - p3[3 * c];
                float dy = p3[3 * c + 13] - p3[3 * c + 1];
                float dz = p3[3 * c + 14] - p3[3 * c + 2];
                float nx = dy * ez - dz * ey;
                float ny = dz * ex - dx * ez;
                float nl = (float) Math.sqrt(nx * nx + ny * ny + (dx * ey - dy * ex) * (dx * ey - dy * ex));
                float lit = nl > 0f ? (nx * -0.3f + ny * 0.95f) / nl : 0f;
                float shade = 0.55f + 0.45f * Math.max(0f, lit);
                if (column && c == 1) { // round the column from the front's right edge
                    columnFace(1, 5, 2, hw, hw + d, 0f, len, shade, boxes[k + 8] * alpha, off);
                } else if (column && c == 3) { // and from its left edge the other way
                    columnFace(3, 7, 0, -hw, -hw - d, len, 0f, shade, boxes[k + 8] * alpha, off);
                } else if (mine || column) {
                    mineFace(c, c + 4, n, shade, a);
                }
                if (mine || column) continue;
                tint(k, shade);
                corner(c, a);
                corner(c + 4, a);
                corner(n + 4, a);
                corner(c, a);
                corner(n + 4, a);
                corner(n, a);
            }
            if (column) {
                columnFace(7, 6, 4, 3f * hw + d, hw + d, len, 0f, 0.45f, boxes[k + 8] * alpha, off);
                continue;
            }
            if (mine) {
                mineFace(7, 6, 4, 0.45f, a);
                mineFace(0, 1, 3, 1f, boxes[k + 8] * alpha); // its front, as the flat picture's
                continue;
            }
            tint(k, 0.45f);
            corner(7, a);
            corner(6, a);
            corner(5, a);
            corner(7, a);
            corner(5, a);
            corner(4, a);
        }
        end();
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        if (framed > 0) drawFrames(alpha);
        boxCount = 0;
        framed = 0;
    }

    /**
     * The sync frames of the boxes recorded with one (Kelvin 2026-11-07 19:37 「双押的时候按键的3维外框都要高亮」): the
     * four edges of the back and the four along the depth, added over the faces; the front's four are the flat
     * picture's own frame.
     */
    private void drawFrames(float alpha) {
        begin(GL20.GL_TRIANGLES);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        for (int b = 0; b < boxCount; b++) {
            int k = b * BOX_FLOATS;
            float band = FRAME_A * boxes[k + 12] * alpha;
            float soft = Math.max(0.5f * band, boxes[k + 13] * alpha);
            if (!(band > 0.002f)) continue;
            corners(k);
            tmp.set(boxes[k + 9], boxes[k + 10], boxes[k + 11], 1f);
            for (int c = 0; c < 4; c++) {
                int b4 = 3 * (c + 4);
                int n4 = 3 * ((c + 1) % 4 + 4);
                frameEdge(p3[b4], p3[b4 + 1], p3[b4 + 2], p3[n4], p3[n4 + 1], p3[n4 + 2], band, soft);
                frameEdge(p3[3 * c], p3[3 * c + 1], p3[3 * c + 2], p3[b4], p3[b4 + 1], p3[b4 + 2], band, soft);
            }
        }
        end();
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * One edge of a sync frame from a to b in colour {@link #tmp}: a strip turned to the camera, a solid line
     * {@link #FRAME_LINE} each side (as far past both ends, so the corners close) at {@code band}, its edges fading
     * from {@code soft} to nothing {@link #FRAME_SOFT} further out. An edge seen end on is a point and is left out.
     */
    private void frameEdge(float ax, float ay, float az, float bx, float by, float bz, float band, float soft) {
        edgeDir.set(bx - ax, by - ay, bz - az);
        float len = edgeDir.len();
        if (!(len > 1e-3f)) return;
        edgeDir.scl(1f / len);
        edgeEye.set(cam.position).sub(0.5f * (ax + bx), 0.5f * (ay + by), 0.5f * (az + bz));
        edgeSide.set(edgeDir).crs(edgeEye);
        float sl = edgeSide.len();
        if (!(sl > 1e-3f * edgeEye.len())) return;
        edgeSide.scl(1f / sl);
        float ex = edgeDir.x * FRAME_LINE;
        float ey = edgeDir.y * FRAME_LINE;
        float ez = edgeDir.z * FRAME_LINE;
        ax -= ex;
        ay -= ey;
        az -= ez;
        bx += ex;
        by += ey;
        bz += ez;
        strip(ax, ay, az, bx, by, bz, -FRAME_LINE, FRAME_LINE, band, band);
        strip(ax, ay, az, bx, by, bz, FRAME_LINE, FRAME_LINE + FRAME_SOFT, soft, 0f);
        strip(ax, ay, az, bx, by, bz, -FRAME_LINE, -FRAME_LINE - FRAME_SOFT, soft, 0f);
    }

    /** A strip along a → b from {@code s0} to {@code s1} along {@link #edgeSide}, α {@code a0} at s0, a1 at s1. */
    private void strip(float ax, float ay, float az, float bx, float by, float bz, float s0, float s1, float a0,
                       float a1) {
        float x0 = edgeSide.x * s0;
        float y0 = edgeSide.y * s0;
        float z0 = edgeSide.z * s0;
        float x1 = edgeSide.x * s1;
        float y1 = edgeSide.y * s1;
        float z1 = edgeSide.z * s1;
        vertex(ax + x0, ay + y0, az + z0, tmp, a0);
        vertex(bx + x0, by + y0, bz + z0, tmp, a0);
        vertex(bx + x1, by + y1, bz + z1, tmp, a1);
        vertex(ax + x0, ay + y0, az + z0, tmp, a0);
        vertex(bx + x1, by + y1, bz + z1, tmp, a1);
        vertex(ax + x1, ay + y1, az + z1, tmp, a1);
    }

    private final Color tmp = new Color();

    /**
     * Draws the seas (海面, Kelvin 2026-11-07 16:04–16:16; CHART_FORMAT §5.7) shown at the frame's beat: each a deep-blue
     * sheet over its x and depth, its surface through its height at its middle at its tilt, heaving ({@link #heave}),
     * one piece across the gaps (15:38), its front edge a light line; times {@code alpha}. Behind the flat picture.
     */
    void drawSeas(GameEngine e, float alpha, float seconds) {
        Chart chart = e.chart();
        if (chart == null || chart.seas().isEmpty()) return;
        LaneLayout lay = e.layout();
        float beat = lay.beat();
        for (int edge = 0; edge < 2; edge++) {
            begin(edge == 0 ? GL20.GL_TRIANGLES : GL20.GL_LINES);
            for (int j = 0; j < chart.seas().size(); j++) {
                Sea s = chart.seas().get(j);
                float a = alpha * s.shownAt(beat);
                float l = lay.freeX(s.xMin(beat));
                float r = lay.freeX(s.xMax(beat));
                if (a <= 0.002f || !(r > l)) continue;
                float d0 = s.zMin(beat);
                float d1 = s.zMax(beat);
                frame(seaFrame(j)); // level, then turned about its origin (Kelvin 2026-11-09 00:13)
                seaSheet(s, l, r, d0, d1, beat, seconds);
                if (edge == 0) heaving(l, r, d0, d1, Theme.SEA, SEA_A * a, false, true);
                else heaving(l, r, d0, d1, Theme.SEA_EDGE, SEA_EDGE_A * a, true, false);
                frame(-1);
            }
        }
        end();
    }

    /** Sets sea s over x in [l, r] and depth [d0, d1] at the beat as the sheet {@link #heaving} draws next. */
    private void seaSheet(Sea s, float l, float r, float d0, float d1, float beat, float seconds) {
        heaveSheet(l, 0.5f * (d0 + d1), Field.y(s.yAt(beat)), 0f, 0.5f * (l + r), 0f, 0f, 0f, seconds, true);
    }

    /**
     * Records where a key on sea s will land ({@link #LAND_FILL_A}): its footprint over world x in [x0, x1] and depth
     * [d0, d1] (reference units) on the sea's surface, in its colour c at strength k (0 – 1).
     */
    void landing(Sea s, int index, float x0, float x1, float d0, float d1, Color c, float k) {
        if (s == null || !(k > 0.002f) || !(x1 > x0) || !(d1 > d0)) return;
        if (landSeas.length <= landCount) {
            landSeas = java.util.Arrays.copyOf(landSeas, 2 * landCount + 4);
            lands = java.util.Arrays.copyOf(lands, LAND_FLOATS * (2 * landCount + 4));
            landIndex = java.util.Arrays.copyOf(landIndex, 2 * landCount + 4);
        }
        landIndex[landCount] = index;
        int o = LAND_FLOATS * landCount;
        lands[o] = x0;
        lands[o + 1] = x1;
        lands[o + 2] = d0;
        lands[o + 3] = d1;
        lands[o + 4] = c.r;
        lands[o + 5] = c.g;
        lands[o + 6] = c.b;
        lands[o + 7] = k;
        landSeas[landCount++] = s;
    }

    /** Draws the recorded landing marks on their seas' heaving surfaces, within the seas, times {@code alpha}. */
    private void drawLandings(LaneLayout lay, float alpha, float seconds) {
        if (landCount == 0) return;
        float beat = lay.beat();
        for (int edge = 0; edge < 2; edge++) {
            begin(edge == 0 ? GL20.GL_TRIANGLES : GL20.GL_LINES);
            for (int i = 0; i < landCount; i++) {
                int o = LAND_FLOATS * i;
                Sea s = landSeas[i];
                float l = lay.freeX(s.xMin(beat));
                float r = lay.freeX(s.xMax(beat));
                float d0 = s.zMin(beat);
                float d1 = s.zMax(beat);
                float x0 = Math.max(lands[o], l);
                float x1 = Math.min(lands[o + 1], r);
                float z0 = Math.max(lands[o + 2], d0);
                float z1 = Math.min(lands[o + 3], d1);
                if (!(x1 > x0) || !(z1 > z0)) continue;
                frame(seaFrame(landIndex[i]));
                seaSheet(s, l, r, d0, d1, beat, seconds);
                tmp.set(lands[o + 4], lands[o + 5], lands[o + 6], 1f);
                float a = lands[o + 7] * alpha;
                if (edge == 0) heaving(x0, x1, z0, z1, tmp, LAND_FILL_A * a, false, false);
                else outline(x0, x1, z0, z1, tmp, LAND_EDGE_A * a);
                frame(-1);
            }
        }
        end();
    }

    /** The edge of [x0, x1] × [d0, d1] on the current sheet ({@link #heaveSheet}) as lines along its heave. */
    private void outline(float x0, float x1, float d0, float d1, Color c, float a) {
        int nx = MathUtils.clamp((int) Math.ceil((x1 - x0) / (HEAVE_STEP * Field.kx())), 1, 96);
        int nz = MathUtils.clamp((int) Math.ceil((d1 - d0) / HEAVE_STEP), 1, 32);
        for (int side = 0; side < 4; side++) {
            boolean across = side < 2;
            int n = across ? nx : nz;
            for (int i = 0; i < n; i++) {
                float t0 = (float) i / n;
                float t1 = (float) (i + 1) / n;
                float ax = across ? x0 + (x1 - x0) * t0 : side == 2 ? x0 : x1;
                float bx = across ? x0 + (x1 - x0) * t1 : ax;
                float ad = across ? (side == 0 ? d0 : d1) : d0 + (d1 - d0) * t0;
                float bd = across ? ad : d0 + (d1 - d0) * t1;
                heavePoint(pa, ax, ad);
                heavePoint(pb, bx, bd);
                vertex(pa[0], pa[1], pa[2], c, a);
                vertex(pb[0], pb[1], pb[2], c, a);
            }
        }
    }

    /**
     * Sets the sheet {@link #heaving} draws next (left end, middle depth, surface, turn about (px, py), time; a sea's
     * surface heaves as {@link #seaHeave}).
     */
    private void heaveSheet(float l, float dm, float y, float slope, float mid, float deg, float px, float py,
                            float t, boolean sea) {
        hsea = sea;
        hl = l;
        hdm = dm;
        hy = y;
        hslope = slope;
        hmid = mid;
        hpx = px;
        hpy = py;
        hcos = MathUtils.cosDeg(deg);
        hsin = MathUtils.sinDeg(deg);
        hzk = depth / DEPTH;
        ht = t;
    }

    /** The point of the current sheet over world x at depth d (reference units, 0 = the front plane). */
    private void heavePoint(float[] out, float x, float d) {
        float h = hsea ? seaHeave((x - hl) / Field.kx(), d - hdm, ht) : heave((x - hl) / Field.kx(), d - hdm, ht);
        // trough 0 – crest 1: the largest heave is 2.6 HEAVE times SEA_HEAVE_K
        out[3] = MathUtils.clamp(0.5f + h / (5.2f * SEA_HEAVE_K * HEAVE), 0f, 1f);
        float y = hy + hslope * (x - hmid) + h * Field.ky();
        float dx = x - hpx;
        float dy = y - hpy;
        out[0] = hpx + dx * hcos - dy * hsin;
        out[1] = hpy + dx * hsin + dy * hcos;
        out[2] = -d * hzk;
    }

    /**
     * Draws the current sheet ({@link #heaveSheet}) over x in [l, r] and depth [d0, d1] (reference units) on a grid of
     * {@link #HEAVE_STEP}; with {@code edge} only its front edge (the smaller depth) as lines; with {@code crest} its
     * fill shaded from {@link #SEA_TROUGH} to {@link #SEA_CREST} by the heave instead of c.
     */
    private void heaving(float l, float r, float d0, float d1, Color c, float a, boolean edge, boolean crest) {
        int nx = MathUtils.clamp((int) Math.ceil((r - l) / (HEAVE_STEP * Field.kx())), 1, 96);
        int nz = edge ? 1 : MathUtils.clamp((int) Math.ceil(Math.abs(d1 - d0) / HEAVE_STEP), 1, 32);
        float front = Math.min(d0, d1);
        for (int j = 0; j < nz; j++) {
            float da = edge ? front : d0 + (d1 - d0) * j / nz;
            float db = d0 + (d1 - d0) * (j + 1) / nz;
            for (int i = 0; i < nx; i++) {
                float xa = l + (r - l) * i / nx;
                float xb = l + (r - l) * (i + 1) / nx;
                heavePoint(pa, xa, da);
                heavePoint(pb, xb, da);
                if (edge) {
                    vertex(pa[0], pa[1], pa[2], c, a);
                    vertex(pb[0], pb[1], pb[2], c, a);
                    continue;
                }
                heavePoint(pc, xb, db);
                heavePoint(pd, xa, db);
                sheetVertex(pa, c, a, crest);
                sheetVertex(pb, c, a, crest);
                sheetVertex(pc, c, a, crest);
                sheetVertex(pa, c, a, crest);
                sheetVertex(pc, c, a, crest);
                sheetVertex(pd, c, a, crest);
            }
        }
    }

    /** Pearl spacing and radius of a sky TIDE's sheet, reference units, and how much more opaque than a sheet. */
    static final float PEARL_STEP = 60f, PEARL_R = 9f, PEARL_A = 2.4f;

    /**
     * A sky TIDE's sheet as pearls (Kelvin 2026-11-09 00:05 「浪花跟海面类似，原来的珍珠线也拓展为珍珠面」): one every
     * {@link #PEARL_STEP} across x in [l, r] and depth [d0, d1] on the current sheet ({@link #heaveSheet}), each two
     * crossed diamonds, upright and lying, in c.
     */
    private void pearlSheet(float l, float r, float d0, float d1, Color c, float a) {
        int nx = MathUtils.clamp(Math.round((r - l) / (PEARL_STEP * Field.kx())), 1, 96);
        int nz = MathUtils.clamp(Math.round(Math.abs(d1 - d0) / PEARL_STEP), 1, 32);
        float pr = PEARL_R * Field.ky();
        float zr = PEARL_R * depth / DEPTH;
        float pa2 = Math.min(1f, a * PEARL_A);
        for (int j = 0; j < nz; j++) {
            float d = d0 + (d1 - d0) * (j + 0.5f) / nz;
            for (int i = 0; i < nx; i++) {
                heavePoint(pa, l + (r - l) * (i + 0.5f) / nx, d);
                float x = pa[0], y = pa[1], z = pa[2];
                vertex(x - pr, y, z, c, pa2);
                vertex(x, y + pr, z, c, pa2);
                vertex(x + pr, y, z, c, pa2);
                vertex(x - pr, y, z, c, pa2);
                vertex(x + pr, y, z, c, pa2);
                vertex(x, y - pr, z, c, pa2);
                vertex(x - pr, y, z, c, pa2);
                vertex(x, y, z - zr, c, pa2);
                vertex(x + pr, y, z, c, pa2);
                vertex(x - pr, y, z, c, pa2);
                vertex(x + pr, y, z, c, pa2);
                vertex(x, y, z + zr, c, pa2);
            }
        }
    }

    /** A sheet point p as a vertex in c at a; with {@code crest} shaded by its heave ({@link #heaving}). */
    private void sheetVertex(float[] p, Color c, float a, boolean crest) {
        if (!crest) {
            vertex(p[0], p[1], p[2], c, a);
            return;
        }
        crestC.set(SEA_TROUGH).lerp(SEA_CREST, p[3]);
        vertex(p[0], p[1], p[2], crestC, a * (0.85f + 0.4f * p[3]));
    }

    /**
     * A MINE box's face (Kelvin 2026-11-09 17:24 「水雷要六面都有纹理」), the rectangle from corner A of {@link #p3}
     * along to B and to D, wound as the box's faces are: as the flat mine's front reads, its dark core, its red rims
     * and three studs on each long edge, all within the face (17:25: nothing stands out of it), shaded by
     * {@code shade}.
     */
    private void mineFace(int A, int B, int D, float shade, float a) {
        float ux = p3[3 * B] - p3[3 * A];
        float uy = p3[3 * B + 1] - p3[3 * A + 1];
        float uz = p3[3 * B + 2] - p3[3 * A + 2];
        float vx = p3[3 * D] - p3[3 * A];
        float vy = p3[3 * D + 1] - p3[3 * A + 1];
        float vz = p3[3 * D + 2] - p3[3 * A + 2];
        float lu = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        float lv = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(lu > 0f) || !(lv > 0f)) return;
        float r = Math.min(MINE_RIM, 0.25f * Math.min(lu, lv));
        float ru = r / lu;
        float rv = r / lv;
        Color c = Theme.MINE_CORE;
        tmp.set(c.r * shade, c.g * shade, c.b * shade, 1f);
        facePart(A, B, D, ru, rv, 1f - ru, 1f - rv, c.a * 0.92f * a);
        Color m = Theme.MINE;
        tmp.set(m.r * shade, m.g * shade, m.b * shade, 1f);
        float ma = m.a * a;
        facePart(A, B, D, 0f, 0f, 1f, rv, ma);
        facePart(A, B, D, 0f, 1f - rv, 1f, 1f, ma);
        facePart(A, B, D, 0f, rv, ru, 1f - rv, ma);
        facePart(A, B, D, 1f - ru, rv, 1f, 1f - rv, ma);
        for (int k = 1; k <= 3; k++) { // studs at 1/4, 1/2, 3/4 of the long edges
            float t = k * 0.25f;
            if (lu >= lv) {
                facePart(A, B, D, t - ru, 0f, t + ru, 2f * rv, ma);
                facePart(A, B, D, t - ru, 1f - 2f * rv, t + ru, 1f, ma);
            } else {
                facePart(A, B, D, 0f, t - rv, 2f * ru, t + rv, ma);
                facePart(A, B, D, 1f - 2f * ru, t - rv, 1f, t + rv, ma);
            }
        }
    }

    /** Scratch polygons of {@link #columnFace}: (across, along) pairs. */
    private final float[] qa = new float[16], qb = new float[16];

    /**
     * A LANE MINE column's long face (Kelvin 2026-11-10 01:13 「四面都是那种红色斜线那种」), the rectangle from corner A of
     * {@link #p3} across to B and along to D, wound as the box's faces are, shaded by {@code shade}: as the column's
     * flat front reads ({@link NoteRenderer#paintLaneMineColumn}), its {@code mineCore} fill, the 45° {@code mine}
     * stripes drifting toward its line ({@code off}, {@link NoteShapes#stripeOffset}), its rims along both long edges
     * and its tail cap, all the way up (01:36 「这个纹理还是没有到顶」). Across it runs from {@code sA} to {@code sB} round
     * the column (the front's x carried on round its corners, so the stripes run on round them); along, from
     * {@code yA} to {@code yD} (0 at the column's leading edge).
     */
    private void columnFace(int A, int B, int D, float sA, float sB, float yA, float yD, float shade, float a,
                            float off) {
        float du = sB - sA;
        float dv = yD - yA;
        float len = Math.abs(dv);
        if (du == 0f || dv == 0f) return;
        Color c = Theme.MINE_CORE;
        tmp.set(c.r * shade, c.g * shade, c.b * shade, 1f);
        facePart(A, B, D, 0f, 0f, 1f, 1f, c.a * NoteShapes.LANE_MINE_FILL_A * a);
        Color m = Theme.MINE;
        tmp.set(m.r * shade, m.g * shade, m.b * shade, 1f);
        float lo = Math.min(sA, sB);
        float hi = Math.max(sA, sB);
        float p = NoteShapes.stripeAxisPeriod();
        float sw = NoteShapes.stripeAxisWidth();
        float sa = m.a * NoteShapes.STRIPE_A * a;
        boolean flip = du < 0f != dv < 0f; // across or along runs backwards: the winding turns over
        // stripe k lies between the 45° lines y = s + c and y = s + c + sw, c = off + k·p, as on the front
        int k1 = (int) Math.floor((len - lo - off) / p);
        for (int k = (int) Math.ceil((-hi - sw - off) / p); k <= k1; k++) {
            float cc = off + k * p;
            qa[0] = lo;
            qa[1] = lo + cc;
            qa[2] = hi;
            qa[3] = hi + cc;
            qa[4] = hi;
            qa[5] = hi + cc + sw;
            qa[6] = lo;
            qa[7] = lo + cc + sw;
            int nv = clipAlong(qa, 4, qb, 0f, false);
            nv = clipAlong(qb, nv, qa, len, true);
            for (int j = 1; j + 1 < nv; j++) { // a fan
                stripePoint(A, B, D, 0, sA, du, yA, dv, sa);
                stripePoint(A, B, D, flip ? j + 1 : j, sA, du, yA, dv, sa);
                stripePoint(A, B, D, flip ? j : j + 1, sA, du, yA, dv, sa);
            }
        }
        float ma = m.a * a;
        float ru = Math.min(0.25f, NoteShapes.LANE_MINE_RIM / Math.abs(du));
        facePart(A, B, D, 0f, 0f, ru, 1f, ma);
        facePart(A, B, D, 1f - ru, 0f, 1f, 1f, ma);
        float cap = Math.min(NoteShapes.LANE_MINE_CAP, len) / len;
        if (dv > 0f) facePart(A, B, D, 0f, 1f - cap, 1f, 1f, ma);
        else facePart(A, B, D, 0f, 0f, 1f, cap, ma);
    }

    /** Clips polygon {@code in} ({@code n} (across, along) pairs) to along ≥ b, or ≤ b when {@code below}. */
    private static int clipAlong(float[] in, int n, float[] out, float b, boolean below) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float yi = in[2 * i + 1];
            float yj = in[2 * j + 1];
            boolean ini = below ? yi <= b : yi >= b;
            if (ini) {
                out[2 * m] = in[2 * i];
                out[2 * m++ + 1] = yi;
            }
            if (ini != (below ? yj <= b : yj >= b)) {
                float t = (b - yi) / (yj - yi);
                out[2 * m] = in[2 * i] + t * (in[2 * j] - in[2 * i]);
                out[2 * m++ + 1] = b;
            }
        }
        return m;
    }

    /** {@link #columnFace}'s point j of {@link #qa} on its face. */
    private void stripePoint(int A, int B, int D, int j, float sA, float du, float yA, float dv, float a) {
        facePoint(A, B, D, (qa[2 * j] - sA) / du, (qa[2 * j + 1] - yA) / dv, a);
    }

    /** The part [u0, u1] × [v0, v1] of {@link #mineFace}'s face, u along A→B, v along A→D, in {@link #tmp}. */
    private void facePart(int A, int B, int D, float u0, float v0, float u1, float v1, float a) {
        facePoint(A, B, D, u0, v0, a);
        facePoint(A, B, D, u1, v0, a);
        facePoint(A, B, D, u1, v1, a);
        facePoint(A, B, D, u0, v0, a);
        facePoint(A, B, D, u1, v1, a);
        facePoint(A, B, D, u0, v1, a);
    }

    private void facePoint(int A, int B, int D, float u, float v, float a) {
        int o = 3 * A;
        vertex(p3[o] + u * (p3[3 * B] - p3[o]) + v * (p3[3 * D] - p3[o]),
            p3[o + 1] + u * (p3[3 * B + 1] - p3[o + 1]) + v * (p3[3 * D + 1] - p3[o + 1]),
            p3[o + 2] + u * (p3[3 * B + 2] - p3[o + 2]) + v * (p3[3 * D + 2] - p3[o + 2]), tmp, a);
    }

    /** A vertex at corner c of {@link #p3} in colour {@link #tmp}. */
    private void corner(int c, float a) {
        vertex(p3[3 * c], p3[3 * c + 1], p3[3 * c + 2], tmp, a);
    }

    private void tint(int k, float shade) {
        tmp.set(boxes[k + 5] * shade, boxes[k + 6] * shade, boxes[k + 7] * shade, 1f);
    }

    /** A side wall of a column at x from (y0, a0) to (y1, a1), from its front ({@link #front}) to its back. */
    private void quadZ(float x, float y0, float a0, float x1, float y1, float a1, Color c) {
        vertex(x, y0, front, c, a0);
        vertex(x, y0, back, c, a0);
        vertex(x1, y1, back, c, a1);
        vertex(x, y0, front, c, a0);
        vertex(x1, y1, back, c, a1);
        vertex(x1, y1, front, c, a1);
    }

    /** Sets the column colours ({@link #face} …) for a lane {@code k} red as a mine lane (0: the usual blue). */
    private void tint(float k) {
        face.set(FACE).lerp(MINE_FACE, k);
        edge.set(EDGE).lerp(Theme.MINE, k);
        plane.set(PLANE).lerp(MINE_PLANE, k);
        rim.set(Color.WHITE).lerp(MINE_LIGHT, k);
    }

    /** A face across a column at depth z from (y0, a0) to (y1, a1). */
    private void wall(float l, float r, float y0, float a0, float y1, float a1, float z) {
        vertex(l, y0, z, face, a0);
        vertex(r, y0, z, face, a0);
        vertex(r, y1, z, face, a1);
        vertex(l, y0, z, face, a0);
        vertex(r, y1, z, face, a1);
        vertex(l, y1, z, face, a1);
    }

    private void begin(int type) {
        if (shapes == null) shapes = new ImmediateModeRenderer20(MAX_VERTICES, false, true, 0);
        if (primitive != 0) shapes.end();
        else {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        }
        primitive = type;
        shapes.begin(cam.combined, type);
    }

    private void end() {
        if (primitive != 0) shapes.end();
        primitive = 0;
    }

    private void vertex(float x, float y, float z, Color c, float a) {
        if (curFrame >= 0) { // a turned lane's or sea's: through its frame
            int o = curFrame * FRAME_FLOATS;
            float fx = frames[o] * x + frames[o + 1] * y + frames[o + 2] * z + frames[o + 9];
            float fy = frames[o + 3] * x + frames[o + 4] * y + frames[o + 5] * z + frames[o + 10];
            z = frames[o + 6] * x + frames[o + 7] * y + frames[o + 8] * z + frames[o + 11];
            x = fx;
            y = fy;
        }
        int per = primitive == GL20.GL_LINES ? 2 : 6;
        if (shapes.getNumVertices() % per == 0 && shapes.getNumVertices() + per > shapes.getMaxVertices()) {
            shapes.end();
            shapes.begin(cam.combined, primitive);
        }
        shapes.color(c.r, c.g, c.b, MathUtils.clamp(a, 0f, 1f));
        shapes.vertex(x, y, z);
    }

    /**
     * The edges of the veils showing in 3D, over the water the veil renderer drew (Kelvin 2026-11-09 21:21
     * 「椭球和长方体可以」, the preview Kelvin saw): a box's twelve, an ellipsoid's rings across its own z axis; none for a
     * curtain or round an eye inside one. Call outside the batch.
     */
    void drawVolumes(Volumes7 v, float alpha) {
        if (!active || v == null || v.count() == 0 || !(alpha > 0.003f)) return;
        frame(-1);
        begin(GL20.GL_LINES);
        for (int k = 0; k < v.count(); k++) {
            if (v.curtain(k) || v.kind(k) == Volumes7.EVERYWHERE) continue;
            if (v.ellipsoid(k)) {
                for (int j = -2; j <= 2; j++) volumeRing(v, k, j / 3f, VOLUME_RING_A * alpha);
                continue;
            }
            for (int i = 0; i < 8; i++) {
                for (int bit = 1; bit < 8; bit <<= 1) { // each edge once: from the corner without the bit
                    if ((i & bit) != 0) continue;
                    volumeCorner(v, k, i, VOLUME_EDGE_A * alpha);
                    volumeCorner(v, k, i | bit, VOLUME_EDGE_A * alpha);
                }
            }
        }
        end();
    }

    /** Corner i (bits: +x, +y, +z) of box k as a line vertex. */
    private void volumeCorner(Volumes7 v, int k, int i, float a) {
        float sx = (i & 1) == 0 ? -1f : 1f, sy = (i & 2) == 0 ? -1f : 1f, sz = (i & 4) == 0 ? -1f : 1f;
        vertex(v.centre(k, 0) + sx * v.axis(k, 0, 0) + sy * v.axis(k, 1, 0) + sz * v.axis(k, 2, 0),
            v.centre(k, 1) + sx * v.axis(k, 0, 1) + sy * v.axis(k, 1, 1) + sz * v.axis(k, 2, 1),
            v.centre(k, 2) + sx * v.axis(k, 0, 2) + sy * v.axis(k, 1, 2) + sz * v.axis(k, 2, 2), VOLUME_EDGE, a);
    }

    /** The ring of ellipsoid k at {@code t} (−1..1) along its own z axis, as line pieces. */
    private void volumeRing(Volumes7 v, int k, float t, float a) {
        float f = (float) Math.sqrt(1f - t * t);
        float px = 0f, py = 0f, pz = 0f;
        for (int s = 0; s <= RING_STEPS; s++) {
            float c = f * MathUtils.cos(MathUtils.PI2 * s / RING_STEPS);
            float n = f * MathUtils.sin(MathUtils.PI2 * s / RING_STEPS);
            float x = v.centre(k, 0) + t * v.axis(k, 2, 0) + c * v.axis(k, 0, 0) + n * v.axis(k, 1, 0);
            float y = v.centre(k, 1) + t * v.axis(k, 2, 1) + c * v.axis(k, 0, 1) + n * v.axis(k, 1, 1);
            float z = v.centre(k, 2) + t * v.axis(k, 2, 2) + c * v.axis(k, 0, 2) + n * v.axis(k, 1, 2);
            if (s > 0) {
                vertex(px, py, pz, VOLUME_EDGE, a);
                vertex(x, y, z, VOLUME_EDGE, a);
            }
            px = x;
            py = y;
            pz = z;
        }
    }

    /** Frees the GL resources. */
    void dispose() {
        if (shapes != null) shapes.dispose();
        shapes = null;
    }
}
