package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 音符图鉴：设置 → GAMEPLAY 最下方的说明面板，两列各三项（水滴、水流、水雷 | 潮汐、潮汐水流、轨道水雷）。
 * 每项左边是用对局里同一套画法画的静止小图，右边是名字和一句玩法；中文模式下加中文名，玩法也换成中文。
 * 2026-10-18 05:14 起设置里不再显示（{@code NoteGuideRow} 没有页面使用），代码先留着。
 *
 * <p>The NOTE GUIDE panel at the end of Settings → GAMEPLAY (owner 2026-10-12, mock-up P13 v2): a
 * dashed outline titled {@code NOTE GUIDE} with the subtitle {@code EVERY NOTE TYPE AND HOW TO PLAY IT}
 * on the right ({@code 音符图鉴} and {@code 每种音符和它的玩法} in 中文), then two columns of three items, DROP,
 * STREAM, MINE | TIDE, TIDE STREAM, LANE MINE. There is no growing-bar (伸长) and no afterimage
 * (残影) entry (owner 2026-10-12). Each item shows a static miniature about 64 wide, then its name
 * and a one-line rule in the current language (中文 shows only Chinese since Kelvin 2026-10-18).
 *
 * <p>The miniatures are painted by {@link NoteRenderer}'s own {@code paint*} methods with the
 * gameplay numbers ({@link NoteShapes}, the head and mine width rules) in a {@value #MINI_LANE}-wide
 * mini lane, ground side, rotation 0: a DROP head, a short STREAM (head and ribbon), a plain MINE,
 * the T4 rolling wave over one lane at a fixed phase, a TIDE STREAM head bar with its band, and a
 * short LANE MINE column with its stripes and head cap. Only the lengths are the guide's own (head
 * and mine height 16, ribbon, band and column 40, all × uiScale).
 *
 * <p>{@link #layout} sizes the panel for a width, a uiScale and the language, and is cached; a frame
 * only reads the result. Names use the essential cap ({@code max(20 × uiScale, dp(7.5))}), rules the
 * secondary cap 18 × uiScale; when a rule does not fit its column at 18, all rules of that language
 * use the smaller raster (16), and only when one does not fit at 16 either do they wrap at 18: each
 * rule too long for its column breaks between words (中文 between characters, never before ， ； ：)
 * into two lines where two lines hold it, after a punctuation mark when that fits, else where the
 * lines are most even. The two columns share their row heights, so they stay aligned; with
 * less than {@link #MIN_TEXT_W} of text width per column the six items stack in one column. The
 * subtitle moves under the title when the row is too narrow for both, and is left out when even
 * that line cannot hold it at 3/4 size. No text runs past its room ({@link #overflow}). Drawing is
 * two passes like every settings row: {@link #drawShapes} (outline, hairlines, miniatures; the
 * atlas) and {@link #drawText} (all Latin text, then all CJK text, so the panel switches font pages
 * at most once). Display only, no input. Nothing is allocated per frame.
 *
 * <p>Not shown anywhere since 2026-10-18 05:14, when Kelvin took the NOTE GUIDE off the Settings
 * OTHER page; the panel is kept so it can return elsewhere.
 */
public final class NoteGuide {
    /** Number of items. */
    public static final int ITEMS = 6;
    private static final int DROP = 0;
    private static final int STREAM = 1;
    private static final int MINE = 2;
    private static final int TIDE = 3;
    private static final int TIDE_STREAM = 4;
    /** Items 0–2 form the left column, 3–5 the right one. */
    private static final int PER_COLUMN = 3;

    private static final String TITLE = "NOTE GUIDE";
    private static final String TITLE_ZH = "音符图鉴";
    private static final String SUBTITLE = "EVERY NOTE TYPE AND HOW TO PLAY IT";
    private static final String SUBTITLE_ZH = "每种音符和它的玩法";
    /** Item names: DROP, STREAM, MINE, TIDE, TIDE STREAM, LANE MINE (from {@link NoteType}). */
    private static final String[] NAME = {
        NoteType.TAP.display, NoteType.HOLD.display, NoteType.MINE.display(false),
        NoteType.UNIVERSAL.display(false), NoteType.UNIVERSAL.display(true), NoteType.MINE.display(true)};
    private static final String[] NAME_ZH = {
        NoteType.TAP.displayZh, NoteType.HOLD.displayZh, NoteType.MINE.displayZh(false),
        NoteType.UNIVERSAL.displayZh(false), NoteType.UNIVERSAL.displayZh(true), NoteType.MINE.displayZh(true)};
    /** Rules (owner 2026-10-12). */
    private static final String[] RULE = {
        "TAP WHEN IT REACHES THE LINE",
        "HOLD WITH THE SAME FINGER TO THE END",
        "DON'T TOUCH: IT BREAKS YOUR COMBO",
        "TAP ANYWHERE ON ITS LINE",
        "KEEP ANY FINGER ON THE LINE",
        "STAY OUT OF ITS LANE WHILE IT'S RED"};
    /** 中文 rules (owner 2026-10-12; full-width ， ； ：). */
    private static final String[] RULE_ZH = {
        "落到线上时点一下",
        "按住，同一根手指按到结尾",
        "别碰；碰到会断连击",
        "整条线：线上任意位置点一下",
        "任意手指按在线上就算",
        "变红的时候别碰这条轨道"};

    // geometry in world units at uiScale 1 (everything below is × uiScale)
    /** Gap from the top of the panel's box to the outline (the row above ends with its hairline). */
    private static final float OUT_TOP = 16f;
    /** Gap from the outline to the bottom of the box. */
    private static final float OUT_BOTTOM = 8f;
    /** Inner padding left and right of the outline. */
    private static final float PAD_X = 16f;
    /** Title row: outline → the hairline over the items. */
    private static final float TITLE_H = 48f;
    /** Extra title-row height when the subtitle needs a line of its own. */
    private static final float SUB_H = 26f;
    /** Gap between the title and the subtitle on one line. */
    private static final float TITLE_GAP = 32f;
    /** Last item → outline. */
    private static final float END_PAD = 6f;
    /** Gap between the two columns (the separator runs through its middle). */
    private static final float COL_GAP = 24f;
    /** Miniature slot at the left of an item. */
    private static final float MINI_BOX = 76f;
    /** Miniature slot → text. */
    private static final float TEXT_GAP = 18f;
    /** Item content (name and rule lines) → item edge, at least. */
    private static final float ITEM_PAD = 8f;
    /** Text width per column below which the items stack in one column. */
    private static final float MIN_TEXT_W = 300f;
    private static final float TITLE_CAP = 16f;
    private static final float NAME_CAP = 20f;
    private static final float RULE_CAP = 18f;
    private static final float RULE_SMALL = 16f;
    /** Line heights over the cap: name, rule. */
    private static final float NAME_LEAD = 10f;
    private static final float RULE_LEAD = 8f;
    /** Most lines a wrapped rule takes (the rest stays on the last line). */
    private static final int MAX_LINES = 4;
    /** A two-line wrap prefers to break right after one of these (a clause stays on one line). */
    private static final String PUNCT = "，；：。、,;:.";
    private static final float SUB_A = 0.75f;
    private static final float HAIRLINE_A = 0.8f;
    /** Dashed outline: dash, gap, thickness, colour and opacity. */
    private static final float DASH = 10f;
    private static final float DASH_GAP = 6f;
    private static final float DASH_T = 2f;
    private static final Color OUTLINE = Theme.INK3;
    private static final float OUTLINE_A = 0.7f;

    // miniatures (world units at uiScale 1)
    /** Lane width of the miniatures: a DROP head (5/6 of the lane) is 64 wide. */
    static final float MINI_LANE = 76.8f;
    /** Head height of a lane note and height of a MINE (gameplay: 40 and 32 × note size). */
    private static final float MINI_HEAD = 16f;
    /** Length of the STREAM ribbon, the TIDE STREAM band and the LANE MINE column. */
    private static final float MINI_TAIL = 40f;
    /** Width of the TIDE wave and of the TIDE STREAM bar. */
    private static final float MINI_TIDE_W = 64f;
    /** TIDE STREAM head bar thickness (gameplay: 18 × note size). */
    private static final float MINI_TIDE_BAR = 12f;
    /** Note size of the T4 wave (the smallest setting). */
    private static final float WAVE_U = 0.8f;
    /** Song second of the fixed T4 phase: one crest a little left of the middle. */
    private static final float WAVE_SECONDS = 0.88f;
    /** Song second of the fixed LANE MINE stripe phase. */
    private static final float STRIPE_SECONDS = 0f;
    /** Lanes under the TIDE STREAM miniature: its diamonds mark the three inner boundaries. */
    private static final int BAR_LANES = 4;

    private final Draw draw;
    private final Fonts fonts;
    private final NoteRenderer notes;
    private final LaneLayout waveLane;
    private final LaneLayout barLanes;

    // layout cache key
    private float keyWidth = -1f;
    private float keyK = -1f;
    private boolean keyZh;
    private int keyGen = -1;

    // layout (offsets from the left and top of the panel's box; y negative downwards)
    private float k = 1f;
    private boolean zh;
    private float width;
    private float height;
    private int columns = 2;
    private float colW;
    private float overflow;
    private float titleCap;
    private float nameCap;
    private float ruleCap;
    private float nameLh;
    private float ruleLh;
    private float titleCy;
    private boolean subOwnLine;
    private boolean subShown = true;
    private float subCy;
    private float subScale = 1f;
    private float itemsTop;
    private float itemsBottom;
    private float sepX;
    private final float[] itemX = new float[ITEMS];
    private final float[] itemTop = new float[ITEMS];
    private final float[] itemH = new float[ITEMS];
    private final float[] nameScale = new float[ITEMS];
    private final int[] lines = new int[ITEMS];
    private final StringBuilder[][] ruleLine = new StringBuilder[ITEMS][MAX_LINES];
    private final StringBuilder scratch = new StringBuilder(64);

    /** Creates the panel over the shared drawing services. */
    public NoteGuide(Draw draw, Fonts fonts) {
        this.draw = draw;
        this.fonts = fonts;
        notes = new NoteRenderer(draw);
        waveLane = new LaneLayout(new Lane[]{new Lane(1)});
        Lane[] bar = new Lane[BAR_LANES];
        for (int i = 0; i < BAR_LANES; i++) bar[i] = new Lane(i + 1);
        barLanes = new LaneLayout(bar);
        for (int i = 0; i < ITEMS; i++) {
            for (int j = 0; j < MAX_LINES; j++) ruleLine[i][j] = new StringBuilder(48);
        }
    }

    // ------------------------------------------------------------------ layout
    /**
     * Lays the panel out for a box {@code width} wide at uiScale {@code k} in 中文 when {@code zh}
     * (cached: an unchanged width, scale, language and {@link Ui#generation()} returns at once).
     *
     * @return the box height: outline, the gaps above and below it, and everything inside
     */
    public float layout(float width, float k, boolean zh) {
        int gen = Ui.generation();
        if (width == keyWidth && k == keyK && zh == keyZh && gen == keyGen) return height;
        keyWidth = width;
        keyK = k;
        keyZh = zh;
        keyGen = gen;
        this.width = width;
        this.k = k;
        this.zh = zh;
        overflow = 0f;
        notes.setStyle(WAVE_U * k, false);
        float inner = Math.max(0f, width - 2f * PAD_X * k);

        // title row: NOTE GUIDE on the left, the subtitle on the right or under it
        titleCap = TITLE_CAP * k;
        float titleW = fonts.width(Fonts.Role.UI_LABEL, titleCap, zh ? TITLE_ZH : TITLE);
        float subW = fonts.width(Fonts.Role.UI_LABEL, titleCap, zh ? SUBTITLE_ZH : SUBTITLE);
        titleCy = -(OUT_TOP + TITLE_H * 0.5f) * k;
        subOwnLine = titleW + TITLE_GAP * k + subW > inner;
        // on a line of its own it may shrink to 3/4; a list too narrow even for that leaves it out
        subShown = !subOwnLine || subW * 0.75f <= inner;
        subScale = subOwnLine && subW > inner ? inner / subW : 1f;
        float titleBlock = TITLE_H * k;
        subCy = titleCy;
        if (subOwnLine && subShown) {
            titleCy = -(OUT_TOP + TITLE_H * 0.5f - 4f) * k;
            subCy = titleCy - SUB_H * k;
            titleBlock += SUB_H * k;
        }
        overflow = Math.max(overflow, titleW - inner);

        // columns
        float textOff = (MINI_BOX + TEXT_GAP) * k;
        float half = (inner - COL_GAP * k) * 0.5f;
        // the text runs to the column's edge: the column gap keeps it off the separator
        columns = half - textOff >= MIN_TEXT_W * k ? 2 : 1;
        colW = columns == 2 ? half : inner;
        float textW = colW - textOff;

        // names, shrunk to fit only in extreme layouts
        nameCap = Math.max(NAME_CAP * k, Ui.dp(7.5f));
        nameLh = nameCap + NAME_LEAD * k;
        for (int i = 0; i < ITEMS; i++) {
            float full = zh ? fonts.width(Fonts.Role.UI, nameCap, NAME_ZH[i])
                : fonts.width(Fonts.Role.UI_LABEL, nameCap, NAME[i]);
            float s = full > textW && full > 0f ? Math.max(0.75f, textW / full) : 1f;
            nameScale[i] = s;
            overflow = Math.max(overflow, full * s - textW);
        }

        // rules: 18 when every rule of the language fits, else 16, else wrapped at 18
        String[] rule = zh ? RULE_ZH : RULE;
        float cap = RULE_CAP * k;
        boolean single = allFit(rule, cap, textW);
        if (!single && allFit(rule, RULE_SMALL * k, textW)) {
            cap = RULE_SMALL * k;
            single = true;
        }
        ruleCap = cap;
        ruleLh = cap + RULE_LEAD * k;
        for (int i = 0; i < ITEMS; i++) {
            if (single) {
                ruleLine[i][0].setLength(0);
                ruleLine[i][0].append(rule[i]);
                lines[i] = 1;
            } else {
                lines[i] = wrap(i, rule[i], textW);
            }
        }

        // items: rows shared by the two columns
        float pad = 2f * ITEM_PAD * k;
        for (int i = 0; i < ITEMS; i++) itemH[i] = pad + nameLh + lines[i] * ruleLh;
        itemsTop = -OUT_TOP * k - titleBlock;
        float y = itemsTop;
        float left = PAD_X * k;
        if (columns == 2) {
            for (int r = 0; r < PER_COLUMN; r++) {
                float rh = Math.max(itemH[r], itemH[r + PER_COLUMN]);
                itemH[r] = rh;
                itemH[r + PER_COLUMN] = rh;
                itemTop[r] = y;
                itemTop[r + PER_COLUMN] = y;
                itemX[r] = left;
                itemX[r + PER_COLUMN] = left + colW + COL_GAP * k;
                y -= rh;
            }
            sepX = left + colW + COL_GAP * k * 0.5f;
        } else {
            for (int i = 0; i < ITEMS; i++) {
                itemTop[i] = y;
                itemX[i] = left;
                y -= itemH[i];
            }
        }
        itemsBottom = y;
        height = -itemsBottom + (END_PAD + OUT_BOTTOM) * k;
        return height;
    }

    /** True when every rule fits {@code maxW} on one line at cap {@code cap}. */
    private boolean allFit(String[] rule, float cap, float maxW) {
        for (String r : rule) {
            if (fonts.width(Fonts.Role.UI_REGULAR, cap, r) > maxW) return false;
        }
        return true;
    }

    /**
     * Wraps rule {@code s} of item {@code i} into {@link #ruleLine} at {@link #ruleCap}: one line
     * when it fits {@code maxW}, else breaking between words (中文: between characters) into two
     * lines when two lines hold it (right after a punctuation mark when such a break fits, so a
     * clause stays whole; else where the two lines are most even), else greedily (as many words per
     * line as fit; a word longer than a line stays whole).
     *
     * @return the line count
     */
    private int wrap(int i, String s, float maxW) {
        int len = s.length();
        if (measure(s, 0, len) <= maxW) {
            ruleLine[i][0].setLength(0);
            ruleLine[i][0].append(s);
            return 1;
        }
        int best = -1;
        float bestW = Float.POSITIVE_INFINITY;
        boolean bestPunct = false;
        for (int e = 1; e < len; e++) {
            if (!breakBefore(s, e)) continue;
            int end1 = trimEnd(s, 0, e);
            float w1 = measure(s, 0, end1);
            if (w1 > maxW) break;
            int b = e;
            while (b < len && s.charAt(b) == ' ') b++;
            float w2 = measure(s, b, len);
            if (w2 > maxW) continue;
            boolean punct = end1 > 0 && PUNCT.indexOf(s.charAt(end1 - 1)) >= 0;
            float w = Math.max(w1, w2);
            if (punct && !bestPunct || punct == bestPunct && w < bestW) {
                bestW = w;
                best = e;
                bestPunct = punct;
            }
        }
        if (best > 0) {
            ruleLine[i][0].setLength(0);
            ruleLine[i][0].append(s, 0, trimEnd(s, 0, best));
            while (best < len && s.charAt(best) == ' ') best++;
            ruleLine[i][1].setLength(0);
            ruleLine[i][1].append(s, best, len);
            return 2;
        }
        int n = 0;
        int start = 0;
        while (start < len && n < MAX_LINES) {
            while (start < len && s.charAt(start) == ' ') start++;
            if (start >= len) break;
            int end = len;
            if (n < MAX_LINES - 1 && measure(s, start, len) > maxW) {
                int fit = -1;
                int first = -1;
                for (int e = start + 1; e < len; e++) {
                    if (!breakBefore(s, e)) continue;
                    if (first < 0) first = e;
                    if (measure(s, start, trimEnd(s, start, e)) > maxW) break;
                    fit = e;
                }
                end = fit > 0 ? fit : first > 0 ? first : len;
            }
            StringBuilder line = ruleLine[i][n];
            line.setLength(0);
            line.append(s, start, trimEnd(s, start, end));
            overflow = Math.max(overflow, fonts.width(Fonts.Role.UI_REGULAR, ruleCap, line) - maxW);
            n++;
            start = end;
        }
        return Math.max(1, n);
    }

    /** True when a line may break before char {@code e} of {@code s} (0 < e < length). */
    private static boolean breakBefore(String s, int e) {
        char c = s.charAt(e);
        char p = s.charAt(e - 1);
        if (c == ' ') return true;
        if (c == '，' || c == '；' || c == '：' || c == '。' || c == '、') return false;
        return cjk(c) || cjk(p);
    }

    private static boolean cjk(char c) {
        return c >= 0x2E80;
    }

    /** End of {@code s[start, end)} without trailing spaces. */
    private static int trimEnd(String s, int start, int end) {
        while (end > start && s.charAt(end - 1) == ' ') end--;
        return end;
    }

    /** Width of {@code s[a, b)} at {@link #ruleCap} (layout only). */
    private float measure(String s, int a, int b) {
        scratch.setLength(0);
        scratch.append(s, a, b);
        return fonts.width(Fonts.Role.UI_REGULAR, ruleCap, scratch);
    }

    /** Height of the box at the last {@link #layout}. */
    public float height() {
        return height;
    }

    /** Columns at the last {@link #layout}: 2, or 1 when the list is too narrow. */
    public int columns() {
        return columns;
    }

    /** How far the widest text line of the last {@link #layout} runs past its room (≤ 0: it all fits). */
    public float overflow() {
        return overflow;
    }

    /** Rule cap of the last {@link #layout} (18 or 16 × uiScale). */
    public float ruleCap() {
        return ruleCap;
    }

    /** Lines of item {@code i}'s rule at the last {@link #layout}. */
    public int ruleLines(int i) {
        return lines[i];
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Shape pass (the atlas): the dashed outline, the hairlines and the six miniatures of the box
     * whose top-left corner is {@code (x, top)}. Leaves the batch colour white.
     */
    public void drawShapes(float x, float top, float alpha) {
        if (alpha <= 0.002f) return;
        float oTop = top - OUT_TOP * k;
        float oBottom = top - height + OUT_BOTTOM * k;
        dashedRect(x, oBottom, width, oTop - oBottom, alpha);
        float ix0 = x + PAD_X * k;
        float ix1 = x + width - PAD_X * k;
        draw.rect(ix0, top + itemsTop, ix1 - ix0, 1f, Theme.STROKE, HAIRLINE_A * alpha);
        if (columns == 2) {
            float inset = 10f * k;
            draw.rect(x + sepX, top + itemsBottom + inset, 1f, itemsTop - itemsBottom - 2f * inset, Theme.STROKE,
                HAIRLINE_A * alpha);
        }
        for (int i = 0; i < ITEMS; i++) {
            boolean last = columns == 2 ? i % PER_COLUMN == PER_COLUMN - 1 : i == ITEMS - 1;
            if (!last) {
                draw.rect(x + itemX[i], top + itemTop[i] - itemH[i], colW, 1f, Theme.STROKE, 0.6f * alpha);
            }
        }
        float mini = MINI_BOX * k * 0.5f;
        for (int i = 0; i < ITEMS; i++) {
            paintMiniature(i, x + itemX[i] + mini, top + itemTop[i] - itemH[i] * 0.5f, alpha);
        }
        draw.batch().setColor(Color.WHITE);
    }

    /** Dashed rect outline: each side starts and ends with a dash, so the corners read as solid L's. */
    private void dashedRect(float x, float y, float w, float h, float a) {
        float t = DASH_T;
        dashes(x, y, w, t, true, a);
        dashes(x, y + h - t, w, t, true, a);
        dashes(x, y + t, h - 2f * t, t, false, a);
        dashes(x + w - t, y + t, h - 2f * t, t, false, a);
    }

    /** One dashed side from {@code (x, y)}, {@code len} long, horizontal or vertical. */
    private void dashes(float x, float y, float len, float t, boolean horizontal, float a) {
        if (len <= 0f) return;
        float gap = DASH_GAP * k;
        int n = Math.max(1, Math.round((len + gap) / (DASH * k + gap)));
        float d = (len - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) {
            float o = i * (d + gap);
            if (horizontal) draw.rect(x + o, y, d, t, OUTLINE, OUTLINE_A * a);
            else draw.rect(x, y + o, t, d, OUTLINE, OUTLINE_A * a);
        }
    }

    /**
     * Paints miniature {@code i} centred on {@code (cx, cy)} with the gameplay painters, ground side,
     * rotation 0, in a {@link #MINI_LANE} × uiScale lane (leading edge at the bottom).
     */
    private void paintMiniature(int i, float cx, float cy, float a) {
        float lane = MINI_LANE * k;
        float head = MINI_HEAD * k;
        float tail = MINI_TAIL * k;
        switch (i) {
            case DROP:
                notes.paintLaneNote(cx, cy - head * 0.5f, headWidth(lane), head, 0f, Theme.GROUND_SIG, a, 1f,
                    0f, 0f, 0f, 0f, 0f, false);
                break;
            case STREAM: {
                float rw = NoteShapes.ribbonWidth(lane, false);
                notes.paintLaneNote(cx, cy - tail * 0.5f, headWidth(lane), head, 0f, Theme.GROUND_SIG, a, 1f,
                    NoteShapes.ribbonHeadAlpha(false), NoteShapes.ribbonTailAlpha(false), tail, rw,
                    NoteShapes.ribbonEdge(rw, false), false);
                break;
            }
            case MINE:
                notes.paintMine(cx, cy - head * 0.5f, lane * GameEngine.MINE_BOX, head, 0f, a);
                break;
            case TIDE: {
                float hw = MINI_TIDE_W * k * 0.5f;
                waveLane.update(0f, cx - hw - NoteRenderer.TIDE_INSET, cx + hw + NoteRenderer.TIDE_INSET);
                notes.paintTideT4(cx - hw, cx + hw, cy - notes.tideReach(false) * 0.5f, false, 0f, 0f, a, waveLane,
                    WAVE_SECONDS, false);
                break;
            }
            case TIDE_STREAM: {
                float hw = MINI_TIDE_W * k * 0.5f;
                float bar = MINI_TIDE_BAR * k;
                float y = cy - tail * 0.5f;
                barLanes.update(0f, cx - hw - NoteRenderer.TIDE_INSET, cx + hw + NoteRenderer.TIDE_INSET);
                // band layer first (band, then its tail cap), then the head bar, as in gameplay
                notes.paintTideBand(cx - hw, cx + hw, y, tail, false, 1f, 0f, Float.NaN, bar, a);
                notes.paintTideCap(cx - hw, cx + hw, y, tail, false, 0f, a);
                notes.paintTide(cx - hw, cx + hw, y, bar, false, false, 0f, a, barLanes);
                break;
            }
            default: {
                // LANE MINE: the column (band layer), then its head cap (the MINE body)
                float y = cy - tail * 0.5f;
                notes.paintLaneMineColumn(cx, y, 0f, lane, tail, a, STRIPE_SECONDS, Float.NaN, true, false);
                notes.paintMine(cx, y, lane * GameEngine.MINE_BOX, head, 0f, a);
                break;
            }
        }
    }

    /** Head width of a lane note in a lane {@code lane} wide at note size 1 (NoteRenderer's 5/6 rule). */
    private static float headWidth(float lane) {
        return lane * 5f / 6f;
    }

    /**
     * Text pass for the box whose top-left corner is {@code (x, top)}, all in the current language
     * (one font page: the shared Latin page, or the CJK page in 中文).
     */
    public void drawText(Batch b, float x, float top, float alpha) {
        if (alpha <= 0.002f) return;
        float ix0 = x + PAD_X * k;
        float ix1 = x + width - PAD_X * k;
        int vc = Align.left | Fonts.VCENTER;
        String sub = zh ? SUBTITLE_ZH : SUBTITLE;
        fonts.draw(b, Fonts.Role.UI_LABEL, titleCap, zh ? TITLE_ZH : TITLE, ix0, top + titleCy, vc, Theme.INK3, alpha);
        if (subShown && subOwnLine) {
            fonts.drawScaled(b, Fonts.Role.UI_LABEL, titleCap, subScale, sub, ix0, top + subCy, vc, 0f, 0f,
                Theme.INK3, SUB_A * alpha);
        } else if (subShown) {
            fonts.draw(b, Fonts.Role.UI_LABEL, titleCap, sub, ix1, top + subCy, Align.right | Fonts.VCENTER,
                Theme.INK3, SUB_A * alpha);
        }
        float textOff = (MINI_BOX + TEXT_GAP) * k;
        for (int i = 0; i < ITEMS; i++) {
            float tx = x + itemX[i] + textOff;
            if (zh) {
                fonts.drawScaled(b, Fonts.Role.UI, nameCap, nameScale[i], NAME_ZH[i], tx, top + nameCy(i), vc, 0f, 0f,
                    Theme.INK1, alpha);
            } else {
                fonts.drawScaled(b, Fonts.Role.UI_LABEL, nameCap, nameScale[i], NAME[i], tx, top + nameCy(i), vc, 0f,
                    0f, Theme.INK1, alpha);
            }
            drawRule(b, i, tx, top, alpha);
        }
    }

    private void drawRule(Batch b, int i, float tx, float top, float alpha) {
        float y = top + contentTop(i) - nameLh - ruleLh * 0.5f;
        for (int j = 0; j < lines[i]; j++) {
            fonts.draw(b, Fonts.Role.UI_REGULAR, ruleCap, ruleLine[i][j], tx, y, Align.left | Fonts.VCENTER,
                Theme.INK2, alpha);
            y -= ruleLh;
        }
    }

    /** Top of item {@code i}'s text block (centred in its row), from the top of the box. */
    private float contentTop(int i) {
        float content = nameLh + lines[i] * ruleLh;
        return itemTop[i] - (itemH[i] - content) * 0.5f;
    }

    private float nameCy(int i) {
        return contentTop(i) - nameLh * 0.5f;
    }
}
