package org.kelvinizer.map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongMeta;
import org.kelvinizer.screens.result.GlyphQuads;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.SonarField;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.IdentityHashMap;

/**
 * 画洞穴地图的一层：地面光斑、区域名、路（通关为实线，未通关为虚线，主线支线带箭头）、上锁歌曲到前置歌曲的金色虚线，
 * 以及各种洞：歌曲洞、剧情入口、章节竖井、航线洞口、上浮洞、未知信号和曲包占位；标签、路牌和提示在屏幕空间画。
 *
 * <p>Draws one cave level (DESIGN §16, preview v17). Draws are ordered by texture:
 * <ol>
 *   <li>{@link #drawWorld} with the map projection: floor pools, paths, gold dependency lines,
 *   every hole's atlas parts, the sonar and bubbles, then the thumbs (thumb pages), then locks and
 *   grates over them, then the floor text (area names, the title plate, text slots).</li>
 *   <li>The screen-space pass, split by texture: {@link #drawTextShapes} (chips, signposts, the
 *   lock tooltip), {@link #drawTextUi}, {@link #drawTextNumbers} and {@link #drawTextRubik}.</li>
 * </ol>
 * Per-level strings are built once in {@link #setWorld} / {@link #refreshLabels}; no allocation
 * per frame.
 */
final class MapRenderer {
    /** The single raster each text role is drawn from on the map (scaled to the wanted cap). */
    static final float R_UI = 22f, R_NUM = 24f, R_RUBIK = 72f;
    /** Rasters of the song card's own roles: UI_BOLD (titles, chip names) and DISPLAY (DESCEND, grades). */
    static final float R_BOLD = 38f, R_DISPLAY = 40f;
    /**
     * How far past the screen edges the card, sheet and list occluders reach, so a label that
     * straddles a screen edge under them counts as fully covered.
     */
    static final float OFFSCREEN = 4000f;
    /** Song-hole radius the song visuals (thumb, rim, arcs) are drawn for at scale 1. */
    static final float SONG_R = 108f;

    /** Score rings start at the top and fill clockwise. */
    private static final float RING_TOP = MathUtils.PI * 0.5f;
    /** Rim radius: just outside the thumb's visible circle. */
    private static final float RIM_R = MapStyle.THUMB_D * 0.5f + 2f;
    private static final int PATH_SAMPLES = 26, MAX_SIGNS = 12;
    /** Grade superscript "+" as the result stamp draws it: size and gap after the letters, fractions of their cap. */
    private static final float PLUS_SCALE = 0.55f, PLUS_GAP = 0.04f;
    /** White band through R and R+ as on the result stamp: slope (x per y), half width (× cap) and peak alpha. */
    private static final float BAND_SLOPE = 0.45f, BAND_HALF = 0.32f, BAND_A = 0.85f;
    /** Grade letters without the trailing "+" that {@link #grade} draws as a superscript, by grade index. */
    private static final String[] GRADE_LETTERS = new String[Theme.GRADES.length];

    static {
        for (int i = 0; i < GRADE_LETTERS.length; i++) {
            String g = Theme.GRADES[i];
            GRADE_LETTERS[i] = g.endsWith("+") ? g.substring(0, g.length() - 1) : g;
        }
    }

    private final Draw draw;
    private final Fonts fonts;
    private final GlitchText glitch;
    private final ThumbAtlas thumbs;
    private final SonarField sonar;
    private final MapBubbles bubbles;
    private final CaveData data;
    /** Thumb slot of every library song. */
    private final IdentityHashMap<SongMeta, Integer> slots;
    private final int slotCount;
    private MapWorld world;
    private final StringBuilder sb = new StringBuilder(64);
    private final Color c1 = new Color(), c2 = new Color(), c3 = new Color();
    /** Glyph quads of the band sweeping through R and R+ ({@link #grade}). */
    private final GlyphQuads quads = new GlyphQuads();
    /** Noise colours of a garbled secret song's disc. */
    private static final Color NOISE_DARK = new Color(0.08f, 0.12f, 0.27f, 1f), NOISE_MID = new Color(0.14f, 0.2f, 0.37f, 1f);
    private static final Color SHELL_RIB = Theme.mix(Theme.GOLD, Theme.ABYSS_BLACK, 0.55f, new Color());

    // per-frame inputs (set by the screen before drawing) ---------------------------------
    MapCamera cam;
    float time;
    Difficulty lens = Difficulty.SURFACE;
    MapNode selected;
    boolean reduceMotion;
    /** Language = 中文 (lock texts in Chinese). */
    boolean zh;
    /** Heartbeat phase 0..1 (contacts pulse on it). */
    float beat;
    /** Overall alpha of the world (arrival / exit fades). */
    float alpha = 1f;
    /** Extra fade of the map labels (the Descent zooms past them). */
    float textFade = 1f;
    /** HUD edges of the uncovered map area (signposts). */
    float mapLeft, mapRight;
    /** Shaft beacon speed (X3 speeds them up). */
    float beaconBoost = 1f;

    /** Return floater (§3.12, X5): node (null = none), elapsed time (negative = delay), text and colour. */
    MapNode floaterNode;
    float floaterT = -1f;
    String floaterText = "NEW BEST";
    /** Long-press peek ring (§3.8): node and fill 0..1. */
    MapNode peekNode;
    float peekP;
    /** Lock / seal tooltip: node, elapsed time (−1 = none) and text. */
    MapNode tipNode;
    float tipT = -1f;
    String tipText = "";
    /** X5 unlock flow: the nodes being unlocked and the gold flow progress 0..1 (−1 = none). */
    MapNode[] flowNodes = new MapNode[0];
    float flowT = -1f;

    // derived per frame -------------------------------------------------------------------
    private float zoom, vl, vr, vb, vt, ov, labelCap, labelA;
    /** Per node: label block visibility after HUD occlusion, 0..1. */
    private float[] labelK = new float[0];
    /** Per node label lines (built per level and on refresh). */
    private String[] l1 = new String[0], l2 = new String[0], l3 = new String[0];
    /** Title width at the UI raster per node (lazy, −1 = unknown). */
    private float[] titleW = new float[0];
    /** Signposts of this frame: HUD position, direction, alpha and the sign index. */
    private final float[] signX = new float[MAX_SIGNS], signY = new float[MAX_SIGNS], signW = new float[MAX_SIGNS];
    private final int[] signDir = new int[MAX_SIGNS], signIdx = new int[MAX_SIGNS];
    private int signN;
    private String[] signText = new String[0];
    private float tipX, tipY, tipW, tipA;

    // HUD occluders (screen rects; map labels fade where they would slide under the HUD) ---
    private final float[] occ = new float[4 * 16];
    private int occN;

    MapRenderer(Draw draw, Fonts fonts, GlitchText glitch, ThumbAtlas thumbs, IdentityHashMap<SongMeta, Integer> slots,
                int slotCount, SonarField sonar, MapBubbles bubbles, CaveData data) {
        this.draw = draw;
        this.fonts = fonts;
        this.glitch = glitch;
        this.thumbs = thumbs;
        this.slots = slots;
        this.slotCount = slotCount;
        this.sonar = sonar;
        this.bubbles = bubbles;
        this.data = data;
    }

    /** The shared fonts service (card widgets measure with it). */
    Fonts fonts() {
        return fonts;
    }

    /** Switches to a new level (allocation is fine: level changes only). */
    void setWorld(MapWorld w) {
        world = w;
        int n = w.all.length;
        labelK = new float[n];
        titleW = new float[n];
        java.util.Arrays.fill(titleW, -1f);
        l1 = new String[n];
        l2 = new String[n];
        l3 = new String[n];
        signText = new String[w.signs.size()];
        for (int i = 0; i < signText.length; i++) {
            CaveData.Sign g = w.signs.get(i);
            StringBuilder t = new StringBuilder(g.label);
            if (!g.labelZh.isEmpty()) t.append(' ').append(g.labelZh);
            if (!g.sub.isEmpty()) t.append(" · ").append(g.sub);
            signText[i] = t.toString();
        }
        tipNode = null;
        tipT = -1f;
        floaterNode = null;
        peekNode = null;
        flowNodes = new MapNode[0];
        flowT = -1f;
        refreshLabels();
    }

    /** Rebuilds the label lines (after {@code world.refresh}: progress, locks). */
    void refreshLabels() {
        for (MapNode n : world.all) {
            int i = n.idx;
            CaveData.Spot s = n.spot;
            String a = "", b = "", c = "";
            switch (n.kind) {
                case MapNode.SONG:
                    a = n.song != null ? n.glitch ? MapWorld.garble(n.song.title()) : n.song.title() : "";
                    if (n.locked && !world.isGrid()) c = zh ? "通关 " + n.needsText + " 后解锁" : "CLEAR " + n.needsText + " TO UNLOCK";
                    else if (n.forSale) c = Integer.toString(n.price);
                    break;
                case MapNode.STORY: {
                    CaveData.Story st = s != null && s.story != null ? data.stories.get(s.story) : null;
                    a = "STORY " + (st != null ? st.number : "") + (n.read ? " · READ" : "");
                    b = st != null ? (zh && !st.titleZh.isEmpty() ? st.titleZh : st.title + "  " + st.titleZh) : "";
                    if (n.locked) c = zh ? "通关 " + n.needsText + " 后解锁" : "CLEAR " + n.needsText + " TO UNLOCK";
                    break;
                }
                case MapNode.RISE:
                    a = "RISE · " + (s != null && s.label != null ? s.label : "");
                    b = "上浮" + (s != null && s.labelZh != null ? " · " + s.labelZh : "");
                    break;
                case MapNode.CONTACT:
                case MapNode.SLOT:
                    break;
                case MapNode.TUTORIAL:
                    a = "LAUNCH POINT";
                    b = n.anyPlayed ? "TUTORIAL · REPLAY" : "TUTORIAL";
                    break;
                default:
                    a = s != null && s.label != null ? s.label : "";
                    b = s != null && s.labelZh != null ? s.labelZh : "";
                    c = s != null ? world.routeSub(zh && s.subZh != null ? s.subZh : s.sub) : "";
                    if (n.sealed() && c.isEmpty()) c = "COMING SOON · 敬请期待";
                    break;
            }
            l1[i] = a;
            l2[i] = b;
            l3[i] = c;
            titleW[i] = -1f;
        }
    }

    // =================================================================== occluders
    /** Clears the HUD occluder rects (call before {@link #addOccluder} each frame). */
    void clearOccluders() {
        occN = 0;
    }

    /** Adds a HUD rect (screen units) that map labels must not show through. */
    void addOccluder(float x, float y, float w, float h) {
        if (w <= 0f || h <= 0f || occN + 4 > occ.length) return;
        occ[occN++] = x;
        occ[occN++] = y;
        occ[occN++] = x + w;
        occ[occN++] = y + h;
    }

    /**
     * Coverage visibility 0..1 of a screen rect for the world-pass text (layer headers): the
     * HUD and the card draw over the world pass anyway, so only a mostly covered header fades
     * (fully hidden at 60 % of its area under one occluder) instead of vanishing when a corner of
     * it touches a HUD rect.
     */
    float coverVisibility(float x0, float y0, float x1, float y1) {
        float area = Math.max(1f, (x1 - x0) * (y1 - y0));
        float k = 1f;
        for (int i = 0; i < occN; i += 4) {
            float dx = Math.min(x1, occ[i + 2]) - Math.max(x0, occ[i]);
            if (dx <= 0f) continue;
            float dy = Math.min(y1, occ[i + 3]) - Math.max(y0, occ[i + 1]);
            if (dy <= 0f) continue;
            k = Math.min(k, 1f - MathUtils.clamp(dx * dy / area / 0.6f, 0f, 1f));
            if (k <= 0f) return 0f;
        }
        return k;
    }

    /**
     * Visibility 0..1 of a screen rect against the HUD occluders: fades out over the first
     * 28 units of penetration so labels slide under the HUD instead of popping.
     */
    float visibility(float x0, float y0, float x1, float y1) {
        float k = 1f;
        for (int i = 0; i < occN; i += 4) {
            float dx = Math.min(x1, occ[i + 2]) - Math.max(x0, occ[i]);
            if (dx <= 0f) continue;
            float dy = Math.min(y1, occ[i + 3]) - Math.max(y0, occ[i + 1]);
            if (dy <= 0f) continue;
            k = Math.min(k, 1f - MathUtils.clamp(Math.min(dx, dy) / 28f, 0f, 1f));
            if (k <= 0f) return 0f;
        }
        return k;
    }
    /** Thumb slot of a node's song (−1 when it has none). */
    int slotOf(MapNode n) {
        Integer s = n != null && n.song != null ? slots.get(n.song) : null;
        return s != null ? s : -1;
    }

    /** Scale of a hole: press, pop and the filtered-out state, plus the overview lift. */
    private float sc(MapNode n) {
        return n.scale() * (1f + 0.25f * ov);
    }

    /** Scale of the song visuals (thumb, rim, arcs: drawn for {@link #SONG_R}). */
    private float nodeScale(MapNode n) {
        return sc(n) * n.r / SONG_R;
    }

    /** True for the holes whose labels fade on the overview (songs, stories, contacts, slots). */
    private static boolean small(MapNode n) {
        return n.kind == MapNode.SONG || n.kind == MapNode.STORY || n.kind == MapNode.CONTACT || n.kind == MapNode.SLOT;
    }

    /** Shown locked: locked, or unlocking until the X5 gold flow arrives. */
    private static boolean lockShown(MapNode n) {
        return n.locked || n.unlocking;
    }

    /** Shown for sale: a single not bought yet (shell in the hole, price under the name, no chip). */
    private static boolean saleShown(MapNode n) {
        return n.forSale && !lockShown(n) && !n.glitch;
    }

    /** Recomputes the view rectangle, the overview blend, label visibility and signposts (once per frame). */
    void frame() {
        zoom = cam.zoom;
        float hw = Ui.worldW() * zoom * 0.5f, hh = Ui.worldH() * zoom * 0.5f;
        vl = cam.renderX() - hw;
        vr = cam.renderX() + hw;
        vb = cam.renderY() - hh;
        vt = cam.renderY() + hh;
        float zm = cam.zoomMax();
        ov = MathUtils.clamp((zoom - 0.84f * zm) / (0.12f * zm), 0f, 1f);
        labelCap = MathUtils.clamp(21f / zoom, 18f, 26f);
        labelA = alpha * textFade;
        for (MapNode n : world.all) {
            int i = n.idx;
            float base = small(n) ? 1f - ov : 1f;
            float fk = MathUtils.clamp(n.filterK * 2f - 1f, 0f, 1f) * base * Math.min(1f, n.wake * 1.5f);
            if (labelA * fk <= 0.01f || !visible(n) || (l1[i].isEmpty() && l2[i].isEmpty())) {
                labelK[i] = 0f;
                continue;
            }
            if (titleW[i] < 0f) titleW[i] = Math.max(fonts.width(Fonts.Role.UI, R_UI, l1[i]), fonts.width(Fonts.Role.UI, R_UI, l2[i]));
            float half = 0.5f * Math.max(Math.min(titleW[i] * labelCap / R_UI, MapStyle.LABEL_MAX_W), MapStyle.CHIP_W);
            float x = cam.hudX(n.drawX()), top = labelTop(n);
            labelK[i] = visibility(x - half - 6f, top - labelCap * 3.6f, x + half + 6f, top + 4f) * fk;
        }
        frameSigns();
        frameTip();
    }

    private void frameSigns() {
        signN = 0;
        float x0 = mapLeft + 110f, x1 = mapRight - 110f, y0 = Ui.safeBottom() + 140f, y1 = Ui.safeTop() - 150f;
        if (x1 <= x0 || y1 <= y0 || labelA <= 0.01f) return;
        float cx = 0.5f * (x0 + x1), cy = 0.5f * (y0 + y1);
        for (int i = 0; i < world.signs.size() && signN < MAX_SIGNS; i++) {
            CaveData.Sign g = world.signs.get(i);
            float hx = cam.hudX(g.x), hy = cam.hudY(g.y);
            if (hx > x0 - 60f && hx < x1 + 60f && hy > y0 - 60f && hy < y1 + 60f) continue;
            float dx = hx - cx, dy = hy - cy;
            float t = Math.min(dx != 0f ? Math.abs((dx > 0 ? x1 - cx : cx - x0) / dx) : 1e9f,
                dy != 0f ? Math.abs((dy > 0 ? y1 - cy : cy - y0) / dy) : 1e9f);
            t = Math.min(t, 1f);
            signX[signN] = cx + dx * t;
            signY[signN] = cy + dy * t;
            signDir[signN] = Math.abs(dx) * (y1 - y0) >= Math.abs(dy) * (x1 - x0) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
            signW[signN] = uiWidth(signText[i], 18f) + 76f;
            signIdx[signN] = i;
            signN++;
        }
    }

    private void frameTip() {
        tipA = 0f;
        MapNode n = tipNode;
        if (n == null || tipT < 0f) return;
        tipA = Math.min(1f, tipT / 0.12f) * (1f - MathUtils.clamp((tipT - 2.4f) / 0.4f, 0f, 1f)) * alpha;
        tipW = uiWidth(tipText, 20f) + 48f;
        tipX = MathUtils.clamp(cam.hudX(n.drawX()), Ui.safeLeft() + tipW * 0.5f + 8f, Ui.safeRight() - tipW * 0.5f - 8f);
        tipY = MathUtils.clamp(cam.hudY(n.drawY(time, reduceMotion) + n.r * sc(n)) + 18f, Ui.safeBottom() + 150f, Ui.safeTop() - 220f);
    }

    private boolean visible(MapNode n) {
        float r = n.r * 2.2f + 120f;
        return n.x + r > vl && n.x - r < vr && n.y + r > vb && n.y - r < vt;
    }

    private float labelTop(MapNode n) {
        return cam.hudY(n.drawY(time, reduceMotion) - n.r * sc(n) * 1.06f) - 8f;
    }

    private float chipTop(MapNode n) {
        return labelTop(n) - labelCap - 12f;
    }

    /** Advances the floater, the tooltip and the unlock flow. */
    void updateFx(float dt) {
        if (floaterNode != null) {
            floaterT += dt;
            if (floaterT > MapStyle.FLOATER_T + 0.6f) {
                floaterT = -1f;
                floaterNode = null;
            }
        }
        if (tipT >= 0f) {
            tipT += dt;
            if (tipT > 2.8f) {
                tipT = -1f;
                tipNode = null;
            }
        }
    }

    /** Shows the lock / seal tooltip over {@code n}. */
    void showTip(MapNode n, String text) {
        tipNode = n;
        tipText = text;
        tipT = 0f;
    }

    // =================================================================== world pass
    /** World pass (map projection set on the batch, which is drawing). */
    void drawWorld(Batch b) {
        for (float[] p : world.level.pools) {
            if (p[0] + p[2] < vl || p[0] - p[2] > vr || p[1] + p[2] < vb || p[1] - p[2] > vt) continue;
            draw.glowDot(p[0], p[1], p[2] * 2f, Theme.GROUND_SIG, p[3] * alpha);
        }
        for (CaveData.Area a : world.areas) draw.glowDot(a.x, a.y, 1100f, a.color, (0.05f + 0.05f * ov) * alpha);
        drawPaths();
        drawNeeds();
        for (MapNode n : world.all) if (visible(n)) drawSpot(n);
        drawPeekRing();
        sonar.draw(b);
        bubbles.draw(draw, Theme.INK1, vl, vr, vb, vt);
        for (MapNode n : world.all) if (visible(n)) drawThumbs(b, n);
        for (MapNode n : world.all) if (visible(n)) drawOver(n);
        drawFloorText(b);
    }

    private static float bez(float a, float m, float b, float t) {
        float u = 1f - t;
        return u * u * a + 2f * u * t * m + t * t * b;
    }

    /** Curved paths: solid when both ends are cleared, dashed otherwise; arrows on main / side paths. */
    private void drawPaths() {
        float wk = Math.max(1f, zoom * 0.9f);
        for (int i = 0; i < world.la.length; i++) {
            MapNode a = world.all[world.la[i]], c = world.all[world.lb[i]];
            float ax = a.drawX(), ay = a.drawY(time, reduceMotion), bx = c.drawX(), by = c.drawY(time, reduceMotion);
            if (Math.max(ax, bx) + 300f < vl || Math.min(ax, bx) - 300f > vr || Math.max(ay, by) + 300f < vb
                || Math.min(ay, by) - 300f > vt) {
                continue;
            }
            float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1f) continue;
            float bend = (Anim.hash01(i * 7 + world.la[i], 13) - 0.5f) * 0.3f * len;
            float mx = 0.5f * (ax + bx) - dy / len * bend, my = 0.5f * (ay + by) + dx / len * bend;
            float t0 = Math.min(0.45f, a.r * sc(a) * 0.95f / len), t1 = Math.max(0.55f, 1f - c.r * sc(c) * 0.95f / len);
            boolean solid = world.lcleared[i];
            Color col = world.lcolor[i];
            float al = alpha * (solid ? 0.85f : 0.55f) * Math.min(a.wake, c.wake) * Math.min(a.vis(), c.vis()) * 1.2f;
            if (al <= 0.01f) continue;
            float w = (solid ? 6f : 4.5f) * wk;
            float px = bez(ax, mx, bx, t0), py = bez(ay, my, by, t0);
            for (int j = 1; j <= PATH_SAMPLES; j++) {
                float t = t0 + (t1 - t0) * j / PATH_SAMPLES;
                float qx = bez(ax, mx, bx, t), qy = bez(ay, my, by, t);
                if (solid || (j & 1) == 0) draw.line(px, py, qx, qy, w, col, al);
                px = qx;
                py = qy;
            }
            if (world.larrow[i]) {
                float t = 0.5f * (t0 + t1);
                float cx = bez(ax, mx, bx, t), cy = bez(ay, my, by, t);
                float tx = (1f - t) * (mx - ax) + t * (bx - mx), ty = (1f - t) * (my - ay) + t * (by - my);
                float tl = (float) Math.sqrt(tx * tx + ty * ty);
                if (tl > 0.001f) {
                    tx /= tl;
                    ty /= tl;
                    float s = 18f * wk;
                    draw.line(cx - tx * s - ty * s * 0.8f, cy - ty * s + tx * s * 0.8f, cx + tx * s * 0.3f, cy + ty * s * 0.3f,
                        w, col, Math.min(1f, al * 1.3f));
                    draw.line(cx - tx * s + ty * s * 0.8f, cy - ty * s - tx * s * 0.8f, cx + tx * s * 0.3f, cy + ty * s * 0.3f,
                        w, col, Math.min(1f, al * 1.3f));
                }
            }
        }
    }

    /** Gold dotted lines from locked holes to what they need (faint; clear on the overview and on a tap). */
    private void drawNeeds() {
        for (MapNode n : world.all) {
            if (!lockShown(n) || n.needs.length == 0) continue;
            float tip = n == tipNode && tipT >= 0f ? 1f - MathUtils.clamp((tipT - 2.2f) / 0.5f, 0f, 1f) : 0f;
            float a = alpha * Math.max(0.2f + 0.45f * ov, 0.95f * tip) * Math.min(1f, n.wake * 1.5f);
            if (a <= 0.01f) continue;
            for (MapNode m : n.needs) dotted(m.drawX(), m.drawY(time, reduceMotion), n.drawX(), n.drawY(time, reduceMotion),
                m.r * sc(m), n.r * sc(n), a, tip > 0f && !reduceMotion ? time : 0f);
        }
        if (flowT < 0f) return;
        float k = Anim.POW2_OUT.apply(Math.min(1f, flowT));
        for (MapNode n : flowNodes) {
            for (MapNode m : n.needs) {
                float ax = m.drawX(), ay = m.drawY(time, reduceMotion), bx = n.drawX(), by = n.drawY(time, reduceMotion);
                dotted(ax, ay, bx, by, m.r * sc(m), n.r * sc(n), alpha * 0.6f, time);
                float x = ax + (bx - ax) * k, y = ay + (by - ay) * k;
                draw.glowDot(x, y, 120f, Theme.GOLD, 0.8f * alpha);
                draw.disc(x, y, 9f, Theme.GOLD, alpha);
            }
        }
    }

    private void dotted(float ax, float ay, float bx, float by, float ra, float rb, float a, float phase) {
        float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float ux = dx / len, uy = dy / len, wk = Math.max(1f, zoom * 0.8f);
        float step = 22f * wk, r = 3.4f * wk;
        float d = ra + 6f + (phase * 60f) % step;
        for (; d < len - rb - 6f; d += step) draw.disc(ax + ux * d, ay + uy * d, r, Theme.GOLD, a);
    }

    private void drawSpot(MapNode n) {
        switch (n.kind) {
            case MapNode.SONG:
                drawSong(n);
                break;
            case MapNode.STORY:
                drawStory(n);
                break;
            case MapNode.SHAFT:
                drawShaft(n);
                break;
            case MapNode.ROUTE:
                if (n.spot != null && "shaft".equals(n.spot.art)) drawShaft(n);
                else drawRouteArt(n);
                break;
            case MapNode.RISE:
                drawRise(n);
                break;
            case MapNode.CONTACT:
                drawContact(n);
                break;
            case MapNode.TUTORIAL:
                drawLaunch(n);
                break;
            default:
                drawSlot(n);
                break;
        }
    }

    private Color accent(MapNode n, Color fallback) {
        return n.spot != null && n.spot.color != null ? n.spot.color : fallback;
    }

    private Color lensColor(MapNode n, Color out) {
        if (n.missing(lens)) return out.set(Theme.INK3);
        return out.set(Theme.difficultyAccent(lens.ordinal()));
    }

    /** A song hole's atlas parts (the thumb comes later): glow, rim, difficulty arcs, sonar dashes. */
    private void drawSong(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), s = nodeScale(n);
        float a = alpha * n.vis();
        if (n.glitch) {
            drawNoise(n, cx, cy, s, a);
            return;
        }
        boolean locked = lockShown(n) || saleShown(n);
        if (locked) c1.set(Theme.GOLD);
        else lensColor(n, c1);
        float breathe = reduceMotion ? 0f : 0.04f * MathUtils.sin(time * MathUtils.PI2 / 3f + n.bobPhase);
        float halo = n.filterOn && n.filterMatch && !reduceMotion
            ? 0.10f * (0.5f + 0.5f * MathUtils.sin(time * MathUtils.PI2 / 1.4f + n.bobPhase)) : 0f;
        float glowA = ((locked ? 0.06f : 0.12f) + breathe + 0.10f * n.selected + 0.08f * n.flash + halo) * a;
        draw.glowDot(cx, cy, MapStyle.GLOW_D * s, c1, glowA);
        draw.glowDot(cx, cy - 10f * s, MapStyle.THUMB_D * 1.25f * s, Theme.SHADOW, 0.5f * a);
        draw.disc(cx, cy, (RIM_R + 8f) * s, Theme.ABYSS_BLACK, 0.85f * a);
        if (thumbs.region(slotOf(n)) == null) {
            thumbs.average(slotOf(n), c2);
            Theme.mix(MapStyle.THUMB_PLACEHOLDER, c2, 0.35f, c2);
            draw.disc(cx, cy, MapStyle.THUMB_D * 0.5f * s, c2, (locked ? 0.4f : 1f) * a);
        }
        float r = RIM_R * s;
        float rimA = (n.pressed ? 1f : 0.9f) * a;
        if (locked || n.isNew() || n.missing(lens)) {
            int dashes = 20;
            float step = MathUtils.PI2 / dashes;
            float rot = reduceMotion ? 0f : time * 0.15f;
            for (int i = 0; i < dashes; i++) draw.arc(cx, cy, r, rot + i * step, step * 0.55f, MapStyle.RIM_W * s, c1, rimA);
        } else {
            draw.ringArc(cx, cy, r, 0f, MathUtils.PI2, MapStyle.RIM_W * s, zoom * Ui.worldPerPx(), c1, rimA);
        }
        if (locked) return;
        drawArcs(n, cx, cy, s, a);
        if (ov >= 1f) return;
        float da = (0.15f + 0.20f * n.selected + (n.pressed ? 0.2f : 0f)) * (1f - ov) * a;
        float dr = MapStyle.DASH_R * s, step = 15f * MathUtils.degreesToRadians;
        float base = n.dashAngle * MathUtils.degreesToRadians;
        for (int i = 0; i < 24; i++) draw.arc(cx, cy, dr, base + i * step, 8f * MathUtils.degreesToRadians, 3f, Theme.INK1, da);
        if (n.isNew()) {
            float pulse = reduceMotion ? 1f : 0.7f + 0.3f * MathUtils.sin(time * MathUtils.PI2 / 1.2f);
            float nx = cx + MapStyle.NEW_R * 0.7071f * s, ny = cy + MapStyle.NEW_R * 0.7071f * s;
            draw.glowDot(nx, ny, MapStyle.NEW_D * 3f, Theme.GROUND_SIG, 0.35f * pulse * a * (1f - ov));
            draw.disc(nx, ny, MapStyle.NEW_D * 0.5f, Theme.GROUND_SIG, pulse * a * (1f - ov));
        }
    }

    /**
     * A garbled secret song: a disc of 8-unit noise cells (dark, now and then cyan or magenta)
     * that re-rolls 12 times a second with torn rows, and a dashed grey rim.
     */
    private void drawNoise(MapNode n, float cx, float cy, float s, float a) {
        float R = MapStyle.THUMB_D * 0.5f * s, cell = 8f * s;
        int cells = (int) Math.ceil(2f * R / cell), seed = n.order * 7919 + 13;
        int frame = reduceMotion ? 0 : (int) (time * 12f);
        draw.disc(cx, cy, (RIM_R + 8f) * s, Theme.ABYSS_BLACK, 0.85f * a);
        for (int j = 0; j < cells; j++) {
            float y = cy - R + (j + 0.5f) * cell;
            int tear = Anim.hash01(seed + j, frame / 3) < 0.15f ? (int) (Anim.hash01(seed - j, frame) * 5f) - 2 : 0;
            for (int i = 0; i < cells; i++) {
                float x = cx - R + (i + 0.5f) * cell, dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > R * R) continue;
                float v = Anim.hash01(seed + (i + tear) * 131 + j * 977, frame);
                Color c = v < 0.55f ? NOISE_DARK : v < 0.62f ? Theme.GLITCH_C : v < 0.66f ? Theme.GLITCH_M : NOISE_MID;
                draw.rect(x - cell * 0.5f, y - cell * 0.5f, cell * 0.8f, cell * 0.8f, c, a * (v < 0.66f && v >= 0.55f ? 0.8f : 1f));
            }
        }
        int dashes = 20;
        float step = MathUtils.PI2 / dashes, r = RIM_R * s;
        for (int i = 0; i < dashes; i++) draw.arc(cx, cy, r, i * step, step * 0.55f, MapStyle.RIM_W * s, Theme.INK3, 0.9f * a);
    }

    /**
     * Score rings (Kelvin 2026-10-17 16:40): four full rings, SURFACE innermost to ABYSS outermost
     * (ABYSS black), each filled clockwise from the top by {@link #ringFill}; an unplayed chart
     * shows the bare track, a missing one no ring. A first play sweeps its fill in. Drawn round and
     * anti-aliased at any length (17:43: a short ABYSS arc showed as two straight pieces).
     */
    private void drawArcs(MapNode n, float cx, float cy, float s, float alpha) {
        float lift = 4f * n.flash;
        float pitch = (MapStyle.ARC_R1 - MapStyle.ARC_R0) * 0.25f, w = (pitch - MapStyle.ARC_GAP) * s;
        float bright = 1f + 0.25f * n.flash, px = zoom * Ui.worldPerPx();
        for (int d = 0; d < 4; d++) {
            if (!n.has[d]) continue;
            float rr = (MapStyle.ARC_R0 + (d + 0.5f) * pitch + lift) * s;
            float sw = !n.played[d] ? 0f : n.sweep[d] >= 1f ? 1f : Anim.POW2_OUT.apply(n.sweep[d]);
            float fill = MathUtils.PI2 * (n.played[d] ? ringFill(n.best[d]) * sw : 0f);
            if (fill < MathUtils.PI2) draw.ringArc(cx, cy, rr, RING_TOP - fill, -(MathUtils.PI2 - fill), w, px, MapStyle.ARC_UNPLAYED, alpha);
            if (fill <= 0.01f) continue;
            c2.set(d == 3 ? Theme.difficultyFill(d) : Theme.difficultyAccent(d));
            c2.r = Math.min(1f, c2.r * bright);
            c2.g = Math.min(1f, c2.g * bright);
            c2.b = Math.min(1f, c2.b * bright);
            draw.ringArc(cx, cy, rr, RING_TOP, -fill, w, px, c2, alpha);
            if (sw < 1f) {
                float end = RING_TOP - fill;
                draw.glowDot(cx + rr * MathUtils.cos(end), cy + rr * MathUtils.sin(end), 40f * s,
                    d == 3 ? Theme.difficultyAccent(d) : c2, 0.6f * alpha);
            }
        }
    }

    /** Part of a score ring a best score fills: max(0, (min(best, 100M) − 60M) / 40M) (Kelvin 2026-10-17 16:40). */
    static float ringFill(int best) {
        return Math.max(0f, (Math.min(best, 100_000_000) - 60_000_000) / 40_000_000f);
    }

    /** Story entrance: about 70 % of a song hole, an open book; read stories are dimmer. */
    private void drawStory(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis() * (n.read ? 0.6f : 1f);
        Color acc = accent(n, Theme.GROUND_SIG);
        draw.glowDot(cx, cy, R * 3.4f, acc, (0.14f + 0.1f * n.selected + 0.08f * n.flash) * a);
        draw.disc(cx, cy, R * 0.9f, Theme.SEAM, 0.95f * a);
        draw.circle(cx, cy, R * 0.9f, 3f * Math.max(1f, zoom * 0.7f), lockShown(n) ? Theme.GOLD : acc, 0.9f * a);
        if (!lockShown(n)) book(cx, cy, R * 0.95f, acc, a);
    }

    private void book(float cx, float cy, float w, Color c, float a) {
        float h = w * 0.62f, lw = Math.max(2.5f, w * 0.05f), hw = w * 0.5f;
        draw.line(cx, cy - h * 0.5f, cx, cy + h * 0.38f, lw, c, a);
        for (int side = -1; side <= 1; side += 2) {
            float ex = cx + side * hw;
            draw.line(cx, cy + h * 0.38f, ex, cy + h * 0.5f, lw, c, a);
            draw.line(ex, cy + h * 0.5f, ex, cy - h * 0.38f, lw, c, a);
            draw.line(ex, cy - h * 0.38f, cx, cy - h * 0.5f, lw, c, a);
            for (int k = 0; k < 3; k++) {
                float y = cy + h * (0.22f - k * 0.22f);
                draw.line(cx + side * hw * 0.22f, y, cx + side * hw * 0.78f, y + h * 0.04f, lw * 0.6f, c, 0.6f * a);
            }
        }
    }

    /** Chapter shaft (and the hub's MAIN / SIDE): a black shaft with beacons; sealed = grate and slow red light. */
    private void drawShaft(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis();
        boolean sealed = n.sealed();
        Color acc = accent(n, Theme.GROUND_SIG);
        float slow = reduceMotion ? 0.5f : 0.5f + 0.5f * MathUtils.sin(time * MathUtils.PI2 / 3.2f);
        draw.glowDot(cx, cy, R * 2.9f, sealed ? Theme.MINE : acc, (sealed ? 0.06f + 0.08f * slow : 0.16f + 0.1f * n.selected) * a);
        draw.disc(cx, cy, R * 0.95f, Theme.SEAM, a);
        draw.disc(cx, cy, R * 0.82f, Theme.ABYSS_BLACK, a);
        draw.glowDot(cx, cy, R * 1.1f, Theme.ABYSS_BLACK, a);
        draw.circle(cx, cy, R * 0.95f, 4f * Math.max(1f, zoom * 0.7f), Theme.INK3, 0.8f * a);
        draw.circle(cx, cy, R * 0.82f, 2f * Math.max(1f, zoom * 0.7f), sealed ? Theme.INK3 : acc, 0.5f * a);
        int beacons = 6;
        for (int i = 0; i < beacons; i++) {
            float ang = i * MathUtils.PI2 / beacons + (reduceMotion ? 0f : time * 0.12f);
            float bx = cx + R * 0.885f * MathUtils.cos(ang), by = cy + R * 0.885f * MathUtils.sin(ang);
            float ph = reduceMotion ? 0.5f : ((time * 0.8f * beaconBoost - (float) i / beacons) % 1f + 1f) % 1f;
            float on = sealed ? 0f : Math.max(0f, 1f - ph * 3f);
            draw.disc(bx, by, 5f * Math.max(1f, zoom * 0.7f), sealed ? Theme.INK3 : acc, (sealed ? 0.35f : 0.45f + 0.55f * on) * a);
            if (on > 0.01f) draw.glowDot(bx, by, 60f, acc, 0.6f * on * a);
        }
        if (sealed) {
            draw.glowDot(cx, cy, R * 1.5f, Theme.MINE, (0.10f + 0.14f * slow) * a);
            grate(cx, cy, R * 0.8f, a);
        }
    }

    private void grate(float cx, float cy, float r, float a) {
        float w = Math.max(4f, r * 0.05f);
        for (int k = -2; k <= 2; k++) {
            float o = k * r * 0.36f, h = (float) Math.sqrt(Math.max(0f, r * r - o * o));
            draw.line(cx + o, cy - h, cx + o, cy + h, w, Theme.INK3, 0.85f * a);
            draw.line(cx - h, cy + o, cx + h, cy + o, w, Theme.INK3, 0.85f * a);
        }
    }

    /** Hub route hole with full art (SINGLES, ALL SONGS, COLLAB): dark rim, thumbs drawn later. */
    private void drawRouteArt(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis();
        Color acc = accent(n, Theme.GROUND_SIG);
        boolean sealed = n.sealed();
        float slow = reduceMotion ? 0.5f : 0.5f + 0.5f * MathUtils.sin(time * MathUtils.PI2 / 3.2f);
        draw.glowDot(cx, cy, R * 2.9f, sealed ? Theme.MINE : acc, (sealed ? 0.06f + 0.08f * slow : 0.16f + 0.1f * n.selected) * a);
        draw.disc(cx, cy, R * 0.98f, Theme.ABYSS_BLACK, 0.95f * a);
        draw.disc(cx, cy, R * 0.86f, Theme.SEAM, a);
        draw.circle(cx, cy, R * 0.98f, 3f * Math.max(1f, zoom * 0.7f), sealed ? Theme.INK3 : acc, 0.7f * a);
    }

    /** Rise hole: light from above and a climbing chevron. */
    private void drawRise(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis();
        float k = reduceMotion ? 0.3f : (time * 0.8f) % 1f;
        draw.glowDot(cx, cy + R * 0.8f, R * 4.4f, Theme.SURFACE_TOP, (0.16f + 0.1f * n.selected) * a);
        draw.disc(cx, cy, R * 0.92f, Theme.PANEL_HI, 0.92f * a);
        draw.circle(cx, cy, R * 0.92f, 4f * Math.max(1f, zoom * 0.7f), Theme.SURFACE_TOP, 0.9f * a);
        draw.circle(cx, cy, R * (0.92f + 0.5f * k), 2f, Theme.SURFACE_TOP, 0.5f * (1f - k) * a);
        Icons.draw(draw.shapes(), Icons.Icon.CHEVRON_UP, cx, cy - R * 0.12f + k * R * 0.3f, R * 1.0f, Theme.SKY_SIG,
            a * (1f - 0.6f * k));
    }

    private void drawContact(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis() * (1f - 0.5f * ov);
        float pulse = reduceMotion ? 0.5f : 1f - beat;
        draw.circle(cx, cy, R, 3f, Theme.INK3, 0.8f * a);
        draw.circle(cx, cy, R * (1f + 0.6f * (1f - pulse)), 2f, Theme.INK3, 0.4f * pulse * a);
    }

    private void drawSlot(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis();
        draw.disc(cx, cy, R * 0.86f, Theme.SEAM, 0.6f * a);
        int dashes = 18;
        float step = MathUtils.PI2 / dashes;
        for (int i = 0; i < dashes; i++) draw.arc(cx, cy, R * 0.86f, i * step, step * 0.5f, 3f, Theme.INK3, 0.8f * a);
    }

    /** The tutorial hole: a gold-rimmed float with a diving chevron. */
    private void drawLaunch(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), s = nodeScale(n);
        float a = alpha * n.vis();
        float r = MapStyle.THUMB_D * 0.5f * s;
        draw.glowDot(cx, cy, MapStyle.GLOW_D * s, Theme.GOLD, (0.14f + 0.1f * n.selected) * a);
        draw.disc(cx, cy, r, Theme.PANEL_HI, a);
        draw.disc(cx, cy, r * 0.82f, Theme.SEAM, 0.85f * a);
        draw.circle(cx, cy, RIM_R * s, MapStyle.RIM_W * s, Theme.GOLD, (n.pressed ? 1f : 0.9f) * a);
        float sink = reduceMotion ? 0f : 8f * MathUtils.sin(time * MathUtils.PI2 / 0.9f);
        Icons.draw(draw.shapes(), Icons.Icon.CHEVRONS_DOWN, cx, cy - 4f * s + sink * s, 92f * s, Theme.GOLD, a);
    }

    /** The long-press ring: fills clockwise from the top around the pressed node. */
    private void drawPeekRing() {
        MapNode n = peekNode;
        if (n == null || peekP <= 0.01f) return;
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion);
        float rr = Math.max((MapStyle.DASH_R + 18f) * nodeScale(n), n.r * sc(n) + 18f);
        float p = Math.min(1f, peekP);
        draw.arc(cx, cy, rr, MathUtils.HALF_PI, -MathUtils.PI2, 3f, Theme.INK1, 0.15f * alpha);
        draw.arc(cx, cy, rr, MathUtils.HALF_PI, -MathUtils.PI2 * p, 6f, Theme.INK1, 0.9f * alpha);
        if (p >= 1f) draw.glowDot(cx, cy, rr * 2.6f, Theme.INK1, 0.10f * alpha);
    }

    /** Thumbs: song art, and the hub's art holes (a centre thumb ringed by six). */
    private void drawThumbs(Batch b, MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion);
        float a = alpha * n.vis();
        if (n.kind == MapNode.SONG) {
            if (n.glitch) return;
            float k = Math.min(1f, 1f + 0.25f * n.flash);
            float dim = lockShown(n) ? 0.38f : n.missing(lens) ? 0.55f : 1f;
            thumb(b, slotOf(n), cx, cy, MapStyle.THUMB_D * nodeScale(n), k * dim, a);
            return;
        }
        if (n.kind != MapNode.ROUTE || slotCount == 0 || n.spot == null || "shaft".equals(n.spot.art)) return;
        float R = n.r * sc(n);
        float dim = n.sealed() ? 0.4f : 1f;
        boolean songs = "songs".equals(n.spot.art);
        int base = n.sealed() ? 3 : 0;
        float cd = songs ? R * 0.74f : R * 0.54f, rd = songs ? R * 0.42f : R * 0.54f, ring = songs ? R * 0.6f : R * 0.56f;
        thumb(b, base % slotCount, cx, cy, cd, dim, a);
        for (int i = 0; i < 6; i++) {
            float ang = MathUtils.HALF_PI + i * MathUtils.PI2 / 6f;
            thumb(b, (base + 1 + i) % slotCount, cx + ring * MathUtils.cos(ang), cy + ring * MathUtils.sin(ang), rd, dim, a);
        }
    }

    private void thumb(Batch b, int slot, float cx, float cy, float d, float k, float a) {
        TextureRegion r = thumbs.region(slot);
        if (r == null) {
            thumbs.average(slot, c2);
            draw.disc(cx, cy, d * 0.5f, c2, k * a);
            return;
        }
        float cell = ThumbAtlas.cellSizeFor(d);
        float old = b.getPackedColor();
        b.setColor(k, k, k, a);
        b.draw(r, cx - cell * 0.5f, cy - cell * 0.5f, cell, cell);
        b.setPackedColor(old);
    }

    /** Over the thumbs: padlocks, the sealed art holes' grate and red light, and the X5 lock shatter. */
    private void drawOver(MapNode n) {
        float cx = n.drawX(), cy = n.drawY(time, reduceMotion), R = n.r * sc(n);
        float a = alpha * n.vis();
        if (lockShown(n) && (n.kind == MapNode.SONG || n.kind == MapNode.STORY)) {
            draw.disc(cx, cy, R * 0.34f, Theme.SEAM, 0.75f * a);
            Icons.draw(draw.shapes(), Icons.Icon.LOCK, cx, cy, R * 0.5f, Theme.GOLD, a);
        } else if (saleShown(n)) {
            draw.disc(cx, cy, R * 0.38f, Theme.SEAM, 0.75f * a);
            draw.shell(cx, cy, R * 0.56f, Theme.GOLD, SHELL_RIB, a);
        }
        if (n.kind == MapNode.ROUTE && n.sealed() && n.spot != null && !"shaft".equals(n.spot.art)) {
            float slow = reduceMotion ? 0.5f : 0.5f + 0.5f * MathUtils.sin(time * MathUtils.PI2 / 3.2f);
            draw.glowDot(cx, cy, R * 1.5f, Theme.MINE, (0.10f + 0.14f * slow) * a);
            grate(cx, cy, R * 0.84f, a);
        }
        if (n.unlockFx > 0f) {
            float k = 1f - n.unlockFx;
            draw.circle(cx, cy, R * (0.9f + 0.9f * k), 5f, Theme.GOLD, n.unlockFx * alpha);
            for (int i = 0; i < 8; i++) {
                float ang = i * MathUtils.PI2 / 8f + 0.3f;
                float d0 = R * (0.2f + 1.1f * k), d1 = d0 + R * 0.18f;
                draw.line(cx + d0 * MathUtils.cos(ang), cy + d0 * MathUtils.sin(ang), cx + d1 * MathUtils.cos(ang + 0.2f),
                    cy + d1 * MathUtils.sin(ang + 0.2f), 5f, Theme.GOLD, n.unlockFx * alpha);
            }
        }
    }

    /** Floor text in the world: area names (faint at play zoom, clear on the overview), the title plate, text slots. */
    private void drawFloorText(Batch b) {
        float a = alpha * textFade;
        if (a <= 0.01f) return;
        for (CaveData.Area ar : world.areas) {
            if (ar.x + 900f < vl || ar.x - 900f > vr || ar.y + 200f < vb || ar.y - 200f > vt) continue;
            float k = (0.2f + 0.6f * ov) * a;
            fonts.drawScaled(b, Fonts.Role.UI_BOLD, R_BOLD, 64f / R_BOLD, ar.name, ar.x, ar.y + 40f, Align.center, 0f, 1f,
                ar.color, k);
            fonts.drawScaled(b, Fonts.Role.UI, R_UI, 34f / R_UI, ar.nameZh, ar.x, ar.y - 34f, Align.center, 0f, 1f, ar.color,
                k);
            if (!ar.sub.isEmpty()) {
                fonts.drawScaled(b, Fonts.Role.UI, R_UI, 22f / R_UI, ar.sub, ar.x, ar.y - 84f, Align.center, 0f, 1f, Theme.INK2,
                    0.8f * k);
            }
        }
        CaveData.Level l = world.level;
        if (!Float.isNaN(l.titleX)) {
            sb.setLength(0);
            if (!l.numeral.isEmpty()) sb.append(l.numeral).append("  ");
            sb.append(l.title);
            fonts.drawScaled(b, Fonts.Role.UI_BOLD, R_BOLD, 54f / R_BOLD, sb, l.titleX, l.titleY + 40f, Align.center, 0f, 1f,
                Theme.INK1, 0.85f * a);
            fonts.drawScaled(b, Fonts.Role.UI, R_UI, 30f / R_UI, l.titleZh, l.titleX, l.titleY - 26f, Align.center, 0f, 1f,
                Theme.INK2, 0.85f * a);
            // no stats line under the title (Kelvin 2026-10-16)
        }
        for (CaveData.Text t : world.texts) {
            if (t.x + 1200f < vl || t.x - 1200f > vr || t.y + 100f < vb || t.y - 100f > vt) continue;
            String s = zh ? t.textZh : t.text;
            fonts.drawScaled(b, Fonts.Role.UI, R_UI, t.size / R_UI, s, t.x, t.y, Align.left, 0f, 1f, t.color, 0.9f * a);
        }
    }

    // =================================================================== text helpers
    /** Chakra text from the single UI raster. */
    float ui(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, float a) {
        return fonts.drawScaled(b, Fonts.Role.UI, R_UI, cap / R_UI, text, x, y, align, 0f, 1f, c, a);
    }

    /** Chakra text from the single UI raster, shrunk then ellipsised to {@code maxW}. */
    float uiFit(Batch b, CharSequence text, float cap, float x, float y, int align, float maxW, Color c, float a) {
        return fonts.drawScaled(b, Fonts.Role.UI, R_UI, cap / R_UI, text, x, y, align, maxW, 0.8f, c, a);
    }

    /** Number text (Chakra Petch Bold) from the single NUMBER raster. */
    float num(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, float a) {
        return fonts.drawScaled(b, Fonts.Role.NUMBER, R_NUM, cap / R_NUM, text, x, y, align, 0f, 1f, c, a);
    }

    /**
     * A chart constant centred on (x, y): its whole number {@code base} at {@code cap} and the
     * superscript {@code mark} after it ({@code ConstantLabel}, {@code Theme.CONST_MARK_*}). NUMBER raster.
     */
    void constant(Batch b, CharSequence base, CharSequence mark, float cap, float x, float y, Color c, float a) {
        float bw = numWidth(base, cap), mc = cap * Theme.CONST_MARK_SCALE;
        float mw = mark.length() == 0 ? 0f : cap * Theme.CONST_MARK_GAP + numWidth(mark, mc);
        float x0 = x - (bw + mw) * 0.5f;
        num(b, base, cap, x0, y, Align.left | Fonts.VCENTER, c, a);
        if (mw > 0f) {
            num(b, mark, mc, x0 + bw + cap * Theme.CONST_MARK_GAP, y + cap * Theme.CONST_MARK_RISE,
                Align.left | Fonts.VCENTER, c, a);
        }
    }

    /** Width of UI text at {@code cap}. */
    float uiWidth(CharSequence text, float cap) {
        return fonts.width(Fonts.Role.UI, R_UI, text) * cap / R_UI;
    }

    /** Width of NUMBER text at {@code cap}. */
    float numWidth(CharSequence text, float cap) {
        return fonts.width(Fonts.Role.NUMBER, R_NUM, text) * cap / R_NUM;
    }

    /**
     * Draws a score left-aligned in 9 fixed cells with dim leading zeros ({@code 093,000,000};
     * DESIGN §18) from the NUMBER digit raster of cap {@link #R_NUM} (whatever {@code cap}); returns
     * the width.
     */
    float score(Batch b, int value, float cap, float x, float y, int valign, Color c, float a) {
        return fonts.drawDigits(b, Fonts.Role.NUMBER, cap, R_NUM, Math.max(0, value), 9, true, x, y,
            Align.left | valign, c, Theme.LEADING_ZERO, a, null);
    }

    /** Chakra Bold text from the single UI_BOLD raster. */
    float bold(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, float a) {
        return fonts.drawScaled(b, Fonts.Role.UI_BOLD, R_BOLD, cap / R_BOLD, text, x, y, align, 0f, 1f, c, a);
    }

    /** Chakra Bold text from the UI_BOLD raster, shrunk to {@code minScale}, then ellipsised to {@code maxW}. */
    float boldFit(Batch b, CharSequence text, float cap, float x, float y, int align, float maxW, float minScale, Color c,
                  float a) {
        return fonts.drawScaled(b, Fonts.Role.UI_BOLD, R_BOLD, cap / R_BOLD, text, x, y, align, maxW, minScale, c, a);
    }

    /** Width of UI_BOLD text at {@code cap}. */
    float boldWidth(CharSequence text, float cap) {
        return fonts.width(Fonts.Role.UI_BOLD, R_BOLD, text) * cap / R_BOLD;
    }

    /** Mokoto display text from the single DISPLAY raster. */
    float display(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, float a) {
        return fonts.drawScaled(b, Fonts.Role.DISPLAY, R_DISPLAY, cap / R_DISPLAY, text, x, y, align, 0f, 1f, c, a);
    }

    /**
     * Grade index {@code g} centred on (x, y) from the raster {@code raster} of {@code role}: its
     * letters at {@code cap} and a trailing "+" as a superscript, as on the result stamp (Kelvin
     * 2026-10-17 16:02), all shrunk to {@code maxW} when wider (0 = no limit). R and R+ carry the
     * white band that keeps sweeping through them ({@link Theme#sweep}), drawn in the same pass.
     */
    void grade(Batch b, Fonts.Role role, float raster, int g, float cap, float maxW, float x, float y, Color c,
               float a) {
        if (g < 0 || g >= GRADE_LETTERS.length || a <= 0.002f) return;
        String letters = GRADE_LETTERS[g];
        boolean plus = letters.length() < Theme.GRADES[g].length();
        float pc = cap * PLUS_SCALE, gap = plus ? cap * PLUS_GAP : 0f;
        float lw = fonts.width(role, raster, letters) * cap / raster;
        float pw = plus ? fonts.width(role, raster, "+") * pc / raster : 0f;
        float w = lw + gap + pw;
        if (maxW > 0f && w > maxW) {
            float f = maxW / w;
            cap *= f;
            pc *= f;
            lw *= f;
            gap *= f;
            w = maxW;
        }
        float x0 = x - w * 0.5f, px = x0 + lw + gap, top = y + cap * 0.5f;
        fonts.drawScaled(b, role, raster, cap / raster, letters, x0, y, Align.left | Fonts.VCENTER, 0f, 1f, c, a);
        if (plus) fonts.drawScaled(b, role, raster, pc / raster, "+", px, top, Align.left | Align.top, 0f, 1f, c, a);
        float sw = Theme.sweep(g, time);
        if (sw < 0f) return;
        // the band crosses letters and superscript as one diagonal; each takes it at its own left / baseline
        BitmapFont font = fonts.get(role, raster);
        BitmapFont.BitmapFontData d = font.getData();
        float s0 = d.scaleX, half = cap * BAND_HALF;
        float from = -BAND_SLOPE * cap - half, pos = from + (w + half - from) * sw;
        d.setScale(s0 * cap / raster);
        quads.shimmer(b, font, letters, x0, y, Align.left | Fonts.VCENTER, cap, pos, half, BAND_SLOPE, Theme.WHITE,
            BAND_A * a);
        if (plus) {
            d.setScale(s0 * pc / raster);
            quads.shimmer(b, font, "+", px, top, Align.left | Align.top, pc, pos - (px - x0) + BAND_SLOPE * (cap - pc),
                half, BAND_SLOPE, Theme.WHITE, BAND_A * a);
        }
        d.setScale(s0);
    }

    /**
     * Glitch text drawn from the raster {@code raster} of {@code role} at cap {@code cap}: the
     * pixel scale is pushed so the glyph bucket stays the raster's (no extra font page per size).
     */
    float glitch(Batch b, Fonts.Role role, float raster, CharSequence text, float cap, float x, float y, int align,
                 Color c, float a, GlitchState st) {
        fonts.pushPixelScale(Ui.pxPerWorld() * raster / cap);
        try {
            return glitch.draw(b, role, cap, text, x, y, align, c, a, st, time);
        } finally {
            fonts.popPixelScale();
        }
    }

    /** Width of {@link #fixed} text at {@code cap}. */
    float fixedWidth(CharSequence text, float cap) {
        BitmapFont font = fonts.get(Fonts.Role.NUMBER, R_NUM);
        BitmapFont.BitmapFontData d = font.getData();
        float s = cap * d.scaleY / Math.max(1e-3f, d.capHeight);
        float w = 0f;
        for (int i = 0, n = text.length(); i < n; i++) w += cellW(d, s, cap, text.charAt(i));
        return w;
    }

    private static float cellW(BitmapFont.BitmapFontData d, float s, float cap, char c) {
        if (c >= '0' && c <= '9') return cap * 1.10f;
        if (c == ',' || c == '.') return cap * 0.45f;
        if (c == '%') return cap * 1.16f;
        if (c == ' ') return cap * 0.42f;
        BitmapFont.Glyph g = d.getGlyph(c);
        return g == null ? cap * 0.5f : g.xadvance * s;
    }

    /**
     * Fixed-cell numbers (Appendix A.2d cells: digit 1.10 × cap, {@code ,} {@code .} 0.45,
     * {@code %} 1.16), each glyph centred by its ink in its cell, drawn from the shared NUMBER
     * raster (so it costs no extra font page). The first {@code leadChars} chars use
     * {@code lead} (dim leading zeros). {@code roll} &lt; 1 slot-rolls the chars in from below
     * with a left-to-right stagger. Returns the width.
     */
    float fixed(Batch b, CharSequence text, float cap, float x, float y, int align, Color c, Color lead, int leadChars,
                float a, float roll) {
        int n = text != null ? text.length() : 0;
        if (n == 0 || a <= 0.002f) return 0f;
        BitmapFont font = fonts.get(Fonts.Role.NUMBER, R_NUM);
        BitmapFont.BitmapFontData d = font.getData();
        float s = cap * d.scaleY / Math.max(1e-3f, d.capHeight);
        BitmapFont.Glyph ref = d.getGlyph('H');
        if (ref == null) ref = d.getGlyph('0');
        float refY = ref != null ? ref.yoffset : 0f;
        float total = 0f;
        for (int i = 0; i < n; i++) total += cellW(d, s, cap, text.charAt(i));
        float left = (align & Align.left) != 0 ? x : (align & Align.right) != 0 ? x - total : x - total * 0.5f;
        float base = (align & Align.top) != 0 ? y - cap : (align & Fonts.VCENTER) != 0 ? y - cap * 0.5f : y;
        float old = b.getPackedColor();
        float cx = left;
        float span = 1f + 0.35f * (n - 1);
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            float w = cellW(d, s, cap, ch);
            BitmapFont.Glyph g = ch == ' ' ? null : d.getGlyph(ch);
            if (g != null && g.width > 0 && g.height > 0) {
                float k = 1f;
                if (roll < 1f) k = Anim.POW3_OUT.apply(MathUtils.clamp(roll * span - 0.35f * i, 0f, 1f));
                Color col = i < leadChars && lead != null ? lead : c;
                b.setColor(col.r, col.g, col.b, col.a * a * k);
                float gw = g.width * s, gh = g.height * s;
                float gy = base + (g.yoffset - refY) * s - (1f - k) * cap * 0.8f;
                b.draw(font.getRegion(g.page).getTexture(), cx + (w - gw) * 0.5f, gy, gw, gh, g.u, g.v, g.u2, g.v2);
            }
            cx += w;
        }
        b.setPackedColor(old);
        return total;
    }
    /**
     * Lock-on leader line (§3.8, P2): a 1.5 px accent line from the selected node's rim to the
     * side card's edge, with a diamond at the node end and a short notch on the card edge. Where
     * it passes the depth gauge column ({@code gaugeLeft} .. {@code cardLeft}) it dims to a hint
     * so it never competes with the gauge's ticks and readout. Screen atlas phase.
     */
    void drawLeader(MapNode n, float cardLeft, float gaugeLeft, Color acc, float a) {
        if (n == null || a <= 0.01f) return;
        float s = nodeScale(n);
        float hx = cam.hudX(n.drawX()), hy = cam.hudY(n.drawY(time, reduceMotion));
        float x0 = hx + (RIM_R + 10f) * s / zoom;
        if (x0 >= cardLeft - 48f || hy < Ui.bottom() || hy > Ui.top()) return;
        float k = visibility(x0, hy - 2f, x0 + 2f, hy + 2f);
        float g = Math.max(x0, gaugeLeft - 12f);
        if (g > x0 && k > 0.01f) draw.line(x0, hy, g, hy, 1.5f, acc, 0.55f * a * k);
        draw.line(g, hy, cardLeft, hy, 1.5f, acc, 0.22f * a);
        draw.rect(cardLeft - 3f, hy - 9f, 3f, 18f, acc, 0.85f * a);
        if (k > 0.01f) draw.sprite(draw.white(), x0, hy, 8f, 8f, 45f, acc, a * k);
    }
    // =================================================================== screen pass
    /** Text pass, atlas: lens chips, detail grade and NEW pills, signposts and the tooltip panel. */
    void drawTextShapes() {
        int d = lens.ordinal();
        for (MapNode n : world.nodes) {
            float la = labelA * labelK[n.idx];
            if (la <= 0.01f || lockShown(n)) continue;
            if (saleShown(n)) {
                float px = cam.hudX(n.drawX()), py = labelTop(n) - labelCap - 12f - 9f;
                float tw = uiWidth(l3[n.idx], 18f);
                draw.shell(px - (tw + 30f) * 0.5f + 11f, py, 22f, Theme.GOLD, SHELL_RIB, la);
                continue;
            }
            float x = cam.hudX(n.drawX()), y = chipTop(n);
            float h = MapStyle.CHIP_H, w = MapStyle.CHIP_W;
            if (n.glitch) {
                draw.roundRect(x - w * 0.5f, y - h, w, h, 10f, Theme.INK1, 0.9f * la);
                continue;
            }
            if (n.missing(lens)) {
                draw.roundRectStroke(x - w * 0.5f, y - h, w, h, 10f, 2f, Theme.INK3, 0.8f * la);
            } else {
                draw.roundRect(x - w * 0.5f, y - h, w, h, 10f, Theme.difficultyFill(d), la);
                Color border = Theme.difficultyBorder(d);
                if (border != null) draw.roundRectStroke(x - w * 0.5f, y - h, w, h, 10f, 2f, border, la);
            }
            if (zoom <= MapStyle.LOD_DETAIL && n.played[d]) {
                gradeColor(n.grade[d], c3);
                float py = y - h - 10f;
                draw.pill(x + 70f, py - 34f, 64f, 34f, c3, 0.22f * la);
                draw.roundRectStroke(x + 70f, py - 34f, 64f, 34f, 14f, 2f, c3, 0.8f * la);
            } else if (zoom <= MapStyle.LOD_DETAIL && !n.missing(lens)) { // NEW pill on the score row (Kelvin 2026-10-17 16:40)
                float py = y - h - 10f;
                draw.pill(x - 38f, py - 34f, 76f, 34f, Theme.GROUND_SIG, 0.22f * la);
                draw.roundRectStroke(x - 38f, py - 34f, 76f, 34f, 14f, 2f, Theme.GROUND_SIG, 0.8f * la);
            }
        }
        for (int i = 0; i < signN; i++) {
            CaveData.Sign g = world.signs.get(signIdx[i]);
            float w = signW[i], h = 52f, x = signX[i] - w * 0.5f, y = signY[i] - h * 0.5f;
            float k = visibility(x, y, x + w, y + h) * labelA;
            if (k <= 0.01f) continue;
            draw.pill(x, y, w, h, Theme.PANEL, 0.9f * k);
            draw.roundRectStroke(x, y, w, h, h * 0.5f, 2f, g.color, 0.8f * k);
            Icons.Icon ic = signDir[i] == 0 ? Icons.Icon.CHEVRON_RIGHT : signDir[i] == 1 ? Icons.Icon.CHEVRON_UP
                : signDir[i] == 2 ? Icons.Icon.CHEVRON_LEFT : Icons.Icon.CHEVRON_DOWN;
            Icons.draw(draw.shapes(), ic, x + 28f, signY[i], 30f, g.color, k);
        }
        if (tipA > 0.01f) {
            draw.panel(tipX - tipW * 0.5f, tipY, tipW, 52f, 16f, 0.95f * tipA);
            draw.roundRectStroke(tipX - tipW * 0.5f, tipY, tipW, 52f, 16f, 2f, Theme.GOLD, 0.8f * tipA);
        }
    }

    /** Grade colour of grade index {@code g} into {@code out}; ink1 when unknown. */
    void gradeColorOf(int g, Color out) {
        gradeColor(g, out);
    }

    private void gradeColor(int g, Color out) {
        if (g >= 0 && g < Theme.GRADES.length) Theme.gradeColor(Theme.GRADES[g], time, out);
        else out.set(Theme.INK1);
    }

    /** Text pass, UI raster: labels of every hole, contact / slot marks, signposts and the tooltip. */
    void drawTextUi(Batch b) {
        for (MapNode n : world.all) {
            float la = labelA * labelK[n.idx];
            int i = n.idx;
            if (n.kind == MapNode.CONTACT || n.kind == MapNode.SLOT) {
                if (!visible(n)) continue;
                float hx = cam.hudX(n.drawX()), hy = cam.hudY(n.drawY(time, reduceMotion));
                float k = visibility(hx - 20f, hy - 20f, hx + 20f, hy + 20f) * alpha * n.vis() * (1f - 0.5f * ov);
                if (k > 0.01f) ui(b, "?", 28f, hx, hy, Align.center | Fonts.VCENTER, Theme.INK3, 0.9f * k);
                continue;
            }
            if (la <= 0.01f) continue;
            float x = cam.hudX(n.drawX()), y = labelTop(n);
            if (n.kind == MapNode.SONG) {
                GlitchState gs = n.labelGlitch;
                if (n.glitch) {
                    glitch.draw(b, Fonts.Role.UI, R_UI, l1[i], x, y, Align.center | Align.top, Theme.INK2, la, gs, time);
                    ui(b, "??", 18f, x, chipTop(n) - MapStyle.CHIP_H * 0.5f, Align.center | Fonts.VCENTER, Theme.ABYSS_BLACK, la);
                    continue;
                }
                if (gs.isBursting(time)) {
                    glitch.draw(b, Fonts.Role.UI, R_UI, l1[i], x, y, Align.center | Align.top, Theme.INK1, la, gs, time);
                } else {
                    uiFit(b, l1[i], labelCap, x + 2f, y - 2f, Align.center | Align.top, MapStyle.LABEL_MAX_W, Theme.SHADOW,
                        0.9f * la);
                    uiFit(b, l1[i], labelCap, x, y, Align.center | Align.top, MapStyle.LABEL_MAX_W, Theme.INK1, la);
                }
                if (lockShown(n)) {
                    uiFit(b, l3[i], 18f, x, y - labelCap - 12f, Align.center | Align.top, MapStyle.LABEL_MAX_W, Theme.GOLD, la);
                } else if (saleShown(n)) {
                    // the price after a small shell (drawn in the atlas pass)
                    ui(b, l3[i], 18f, x + 15f, y - labelCap - 12f, Align.center | Align.top, Theme.GOLD, la);
                } else if (n.missing(lens)) {
                    ui(b, "—", 18f, x, chipTop(n) - MapStyle.CHIP_H * 0.5f, Align.center | Fonts.VCENTER, Theme.INK3, la);
                }
                continue;
            }
            boolean big = n.kind == MapNode.SHAFT || n.kind == MapNode.ROUTE;
            float cap = big ? MathUtils.clamp(26f / zoom, 20f, 30f) : labelCap * 0.9f;
            Color main = n.kind == MapNode.STORY ? Theme.INK2 : n.kind == MapNode.RISE ? Theme.SURFACE_TOP
                : n.sealed() ? Theme.INK2 : Theme.INK1;
            uiFit(b, l1[i], cap, x, y, Align.center | Align.top, 420f, main, la);
            float yy = y - cap - 10f;
            if (!l2[i].isEmpty()) {
                uiFit(b, l2[i], cap * 0.8f, x, yy, Align.center | Align.top, 420f, n.kind == MapNode.STORY ? Theme.INK1 : Theme.INK2,
                    la);
                yy -= cap * 0.8f + 10f;
            }
            if (!l3[i].isEmpty()) {
                Color c = lockShown(n) ? Theme.GOLD : n.sealed() ? Theme.GLITCH_M : Theme.INK3;
                uiFit(b, l3[i], 17f, x, yy, Align.center | Align.top, 420f, c, la);
            }
        }
        for (int i = 0; i < signN; i++) {
            float w = signW[i], x = signX[i] - w * 0.5f, y = signY[i] - 26f;
            float k = visibility(x, y, x + w, y + 52f) * labelA;
            if (k > 0.01f) ui(b, signText[signIdx[i]], 18f, x + 52f, signY[i], Align.left | Fonts.VCENTER, Theme.INK1, k);
        }
        if (tipA > 0.01f) ui(b, tipText, 20f, tipX, tipY + 26f, Align.center | Fonts.VCENTER, Theme.GOLD, tipA);
    }

    /** Text pass, NUMBER raster: lens chip constants and the close-zoom scores and grades (NEW when unplayed). */
    void drawTextNumbers(Batch b) {
        int d = lens.ordinal();
        for (MapNode n : world.nodes) {
            float la = labelA * labelK[n.idx];
            if (la <= 0.01f || n.missing(lens) || lockShown(n) || saleShown(n) || n.glitch) continue;
            float x = cam.hudX(n.drawX()), y = chipTop(n) - MapStyle.CHIP_H * 0.5f;
            String ct = n.song.constantBase(lens);
            if (ct != null) {
                constant(b, ct, n.song.constantMark(lens), MapStyle.CHIP_CAP, x, y, Theme.difficultyText(d), la);
            } else {
                num(b, "--", MapStyle.CHIP_CAP, x, y, Align.center | Fonts.VCENTER, Theme.difficultyText(d), la);
            }
            if (zoom <= MapStyle.LOD_DETAIL) {
                float py = y - MapStyle.CHIP_H * 0.5f - 10f - 17f;
                if (!n.played[d]) {
                    num(b, "NEW", 18f, x, py, Align.center | Fonts.VCENTER, Theme.GROUND_SIG, la);
                    continue;
                }
                score(b, n.best[d], 18f, x - 130f, py, Fonts.VCENTER, Theme.INK1, la);
                int g = n.grade[d];
                if (g >= 0 && g < Theme.GRADES.length) {
                    gradeColor(g, c3);
                    grade(b, Fonts.Role.NUMBER, R_NUM, g, 18f, 0f, x + 100f, py, c3, la);
                }
            }
        }
    }

    /** Text pass, bold raster: the return floater (NEW BEST, FIRST CLEAR). */
    void drawTextRubik(Batch b) {
        MapNode n = floaterNode;
        if (n == null || floaterT < 0f) return;
        float t = floaterT / (MapStyle.FLOATER_T + 0.6f);
        float rise = 80f * Anim.POW3_OUT.apply(Math.min(1f, floaterT / 0.9f));
        float a = Math.min(1f, floaterT / 0.12f) * (t < 0.6f ? 1f : 1f - (t - 0.6f) / 0.4f) * alpha;
        float x = cam.hudX(n.drawX()), y = cam.hudY(n.drawY(time, reduceMotion) + n.r * sc(n)) + 40f + rise;
        // near the top it would run into the HUD row and the toasts: float down below the hole instead
        if (y > Ui.safeTop() - 230f) y = cam.hudY(n.drawY(time, reduceMotion) - n.r * sc(n)) - 90f - rise;
        bold(b, floaterText, 30f, x, y, Align.center | Fonts.VCENTER, Theme.GOLD, a);
    }

    // =================================================================== hit tests
    /** The tappable spot under HUD (hx, hy) (closest within its hit radius), or null. */
    MapNode nodeAt(float hx, float hy) {
        float wx = cam.worldX(hx), wy = cam.worldY(hy);
        MapNode best = null;
        float bestD = Float.MAX_VALUE;
        float minR = Ui.touchTarget() * 0.5f * cam.zoom;
        for (MapNode n : world.all) {
            if (!n.tappable() || n.wake < 0.5f) continue;
            float r = Math.max(n.r * (1f + 0.25f * ov), minR);
            float dx = wx - n.x, dy = wy - n.y;
            float d = dx * dx + dy * dy;
            if (d <= r * r && d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }
}
