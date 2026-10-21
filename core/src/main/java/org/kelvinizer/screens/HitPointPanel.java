package org.kelvinizer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import org.kelvinizer.Main;
import org.kelvinizer.audio.AudioService;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.shell.TapStats;
import org.kelvinizer.screens.shell.TapStrip;
import org.kelvinizer.screens.shell.WidgetSet;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.Arrays;

/**
 * 目押校准的内容：没有声音，按键一个个落到判定线上，玩家按平时目押的习惯，看到按键「到线」就点屏幕；
 * 每次点击时按键中线离判定线多少 px，取中位数就是新的目押校准值。全程只用 px，不出现毫秒。
 * 设置里的目押校准用整个屏幕；首次设置第 4 步把它放进卡片里，应用后进入新手教程。
 *
 * <p>The VISUAL HIT POINT calibration (Kelvin 2026-10-20 03:20) as a panel: {@link HitPointScreen} lays it out over
 * the whole screen ({@link #layoutScreen()}), the first run's step 4 in its card ({@link #layoutCard}, Kelvin 22:37:
 * 「延迟校准以后加入目押校准，类似的idea」). The owner passes its {@link WidgetSet} and forwards its lifecycle, frame and
 * input; runs and APPLY reach it through a {@link CalibrationPanel.Host}. Everything it shows is in px
 * (「目押校准不要再有“毫秒”这个标准了啊，下面的散点图指标应该是px」).
 * <ul>
 *   <li><b>Intro</b>: what to do, a diagram of a key at the judgement time with the current value (its midline, the
 *       line and the distance between them), START, and what 0 and 20 mean.</li>
 *   <li><b>Run</b>: silent on purpose (sound would turn it into the audio calibration).
 *       {@link HitPointScreen#WARM_UP} keys, then {@link HitPointScreen#MEASURED}, fall onto a ground line every
 *       {@link HitPointScreen#BEAT_MS} at {@link HitPointScreen#SPEED} (1080 a second: the default two beats at
 *       120 BPM), drawn as the game draws them with the current value and note size. Any touch (or key) is a tap,
 *       matched to the nearest key within {@link HitPointScreen#MAX_TAP_ERROR_MS}; a ripple marks it and nothing says
 *       early or late, which would bias the taps. The clock is the touch events' own monotonic clock, as the game
 *       draws and judges notes on one clock. In a card the lanes sit right of the instructions and the keys fade in
 *       under its top edge and out above its bottom edge.</li>
 *   <li><b>Result</b>: each measured tap {@code late} ms after its key's judgement time saw the key's midline at
 *       {@code current − SPEED·late / noteSize} px from the line (a late tapper sees the hit when the key is further
 *       down); the median, rounded and clamped, is the new value, so the key is drawn there at the judgement time.
 *       The px strip shows every tap (below the line on the left, above it on the right) with the median caret, and
 *       the spread (MAD) reads in px. Fewer than {@link HitPointScreen#REQUIRED} valid taps, or a spread over
 *       {@link HitPointScreen#MAX_MAD_MS} of travel, asks for a retry. APPLY stores the value for the active player
 *       and tells the host; RETRY runs again. The whole screen's result shows the diagram with the new value too.</li>
 * </ul>
 * BACK during a run returns to the intro; backgrounding the app abandons the run. Nothing is allocated per frame.
 */
final class HitPointPanel {
    private static final int INTRO = 0, RUN = 1, RESULT = 2;
    private static final int RES_OK = 0, RES_INCONSISTENT = 1, RES_TOO_FEW = 2;
    private static final int WARM_UP = HitPointScreen.WARM_UP, MEASURED = HitPointScreen.MEASURED,
        KEYS = WARM_UP + MEASURED;
    private static final float SPEED = HitPointScreen.SPEED, BEAT_MS = HitPointScreen.BEAT_MS,
        LEAD_MS = HitPointScreen.LEAD_MS;
    private static final float LANE_GAP = 240f, KEY_W = 200f, KEY_H = 40f, DOTS_GAP = 30f;
    /** From the lanes' centre to the outer edge of an outer key. */
    private static final float LANES_HALF = 1.5f * LANE_GAP + KEY_W * 0.5f;
    /** Lane of each key in turn (four lanes about the centre). */
    private static final int[] LANE_ORDER = {0, 2, 1, 3, 2, 0, 3, 1};
    /**
     * The diagram: a panel of {@code DIAGRAM_W × DIAGRAM_H} with a key at its real size, the range of values drawn to
     * scale (others at its ends, labelled with their own value) and the line's height under the panel's centre.
     */
    private static final float DIAGRAM_W = 600f, DIAGRAM_H = 220f, DIAGRAM_LO = -60f, DIAGRAM_HI = 80f,
        DIAGRAM_LINE = -10f;
    private static final int RIPPLES = 10;
    private static final float RIPPLE_LIFE = 0.5f;

    private static final CharSequence PX = "px";
    private static final Settings FALLBACK = new Settings();

    private final Main game;
    private final CalibrationPanel.Host host;
    private final Button start, apply, retry;
    private final TapStats stats = new TapStats(MEASURED);
    private final TapStrip strip;
    private final boolean[] taken = new boolean[KEYS];
    private final float[] rippleX = new float[RIPPLES], rippleY = new float[RIPPLES], rippleT = new float[RIPPLES];
    private final Color tmp = new Color();

    private final StringBuilder currentText = new StringBuilder(24);
    private final StringBuilder resultValue = new StringBuilder(16);
    private final StringBuilder resultLine = new StringBuilder(64);
    private final StringBuilder resultSub = new StringBuilder(64);
    private final StringBuilder countText = new StringBuilder(24);
    private final StringBuilder diagramText = new StringBuilder(16);

    private int state = INTRO;
    private float stateT;
    private long t0;
    /** The hit point and note size the run's keys were drawn with. */
    private float runHit, runSize = 1f;
    private int resultKind, resultPx, resultCount, countFor = -1, rippleNext;
    private float resultMad, resultLate;
    private boolean applied, interrupted;
    private boolean visible = true;

    // laid out by layoutScreen() or layoutCard()
    private boolean inCard;
    private float cx, textW, k = 1f;
    private float titleY, line1Y, line2Y, diagramY, currentY, startY, footY;
    private float okValueY, valueSize = 96f, okLineY, okSubY, failValueY, failLineY, failSubY, buttonsY;
    private float resultDiagramY;
    private boolean resultDiagram;
    private float lanesX, runLineY, runX0, runX1, fadeTop, fadeBottom;
    private float colX, colW, statusY, hintY, dotsY;

    /** Creates the panel and adds its buttons to {@code widgets} (call once). */
    HitPointPanel(final Main game, WidgetSet widgets, CalibrationPanel.Host host) {
        this.game = game;
        this.host = host;
        Draw d = game.draw();
        Fonts f = game.fonts();
        strip = new TapStrip(game, MEASURED).inPixels();
        start = widgets.add(new Button(d, f, Button.Variant.PRIMARY, Lang.of("START", "开始")));
        start.setOnTap(new Runnable() {
            @Override
            public void run() {
                startRun();
            }
        });
        apply = widgets.add(new Button(d, f, Button.Variant.PRIMARY, Lang.of("APPLY", "应用")));
        apply.setOnTap(new Runnable() {
            @Override
            public void run() {
                applyResult();
            }
        });
        retry = widgets.add(new Button(d, f, Button.Variant.SECONDARY, Lang.of("RETRY", "重试")));
        retry.setOnTap(new Runnable() {
            @Override
            public void run() {
                startRun();
            }
        });
    }

    // ------------------------------------------------------------------ helpers
    private Settings s() {
        Profile p = game.profiles().active();
        return p != null ? p.settings : FALLBACK;
    }

    private static long eventNanos() {
        long t = Gdx.input != null ? Gdx.input.getCurrentEventTime() : 0L;
        return t != 0L ? t : TimeUtils.nanoTime();
    }

    private double msSince(long nanos) {
        return (nanos - t0) / 1e6;
    }

    private static float keyMs(int i) {
        return LEAD_MS + i * BEAT_MS;
    }

    private float laneX(int i) {
        return lanesX + (LANE_ORDER[i % LANE_ORDER.length] - 1.5f) * LANE_GAP;
    }

    /** Where a tap {@code lateMs} after its key's judgement time saw the key's midline, in px from the line. */
    private float seenPx(float lateMs) {
        return runHit - SPEED * lateMs / runSize;
    }

    private void refreshCurrent() {
        currentText.setLength(0);
        currentText.append(Lang.t("CURRENT · ", "当前 · "));
        Fonts.appendInt(currentText, Math.round(s().hitPointPx)).append(" px");
    }

    /** True during a run (every touch is a tap). */
    boolean running() {
        return state == RUN;
    }

    // ------------------------------------------------------------------ lifecycle
    /** Starts afresh at the intro and silences the menu music. */
    void enter() {
        AudioService a = game.audio();
        a.stopSlot(AudioService.Slot.MENU, 0.3f);
        a.stopSlot(AudioService.Slot.PREVIEW, 0.2f);
        interrupted = false;
        applied = false;
        stats.clear();
        strip.clear();
        refreshCurrent();
        setState(INTRO);
    }

    /** Saves (the owner was hidden, or the step left). */
    void leave() {
        game.profiles().save();
    }

    /** BACK / ESC: a run returns to the intro; false when BACK is the owner's. */
    boolean back() {
        if (state != RUN) return false;
        setState(INTRO);
        return true;
    }

    /** App backgrounded: the gap would break the run, so it is abandoned. */
    void pause() {
        if (state != RUN) return;
        interrupted = true;
        setState(INTRO);
    }

    /** Shows or hides the panel's buttons (hidden: the owner shows something else). */
    void setVisible(boolean on) {
        visible = on;
        updateWidgets();
    }

    // ------------------------------------------------------------------ state
    private void setState(int st) {
        state = st;
        stateT = 0f;
        updateWidgets();
    }

    private void updateWidgets() {
        start.setVisible(visible && state == INTRO);
        boolean ok = visible && state == RESULT && resultKind == RES_OK;
        apply.setVisible(ok);
        apply.setEnabled(!applied);
        retry.setVisible(visible && state == RESULT);
        retry.setVariant(ok ? Button.Variant.SECONDARY : Button.Variant.PRIMARY);
        placeResultButtons();
        if (visible) host.runChanged(state == RUN);
    }

    private void startRun() {
        stats.clear();
        strip.clear();
        Arrays.fill(taken, false);
        countFor = -1;
        applied = false;
        interrupted = false;
        runHit = s().hitPointPx;
        runSize = s().noteSizeScale;
        t0 = TimeUtils.nanoTime();
        setState(RUN);
        Haptics.light();
    }

    private void tap(float x, float y, long nanos) {
        ripple(x, y);
        if (state != RUN) return;
        double t = msSince(nanos);
        int i = MathUtils.clamp(Math.round((float) ((t - LEAD_MS) / BEAT_MS)), 0, KEYS - 1);
        float delta = (float) (t - keyMs(i));
        if (taken[i] || Math.abs(delta) > HitPointScreen.MAX_TAP_ERROR_MS) return;
        taken[i] = true;
        if (i < WARM_UP) return;
        stats.add(delta);
        strip.add(seenPx(delta));
        strip.setMedian(seenPx(stats.median()));
    }

    private void finishRun() {
        resultCount = stats.count();
        resultMad = stats.mad();
        if (resultCount < HitPointScreen.REQUIRED) {
            resultKind = RES_TOO_FEW;
        } else {
            resultLate = stats.median();
            resultPx = Math.round(MathUtils.clamp(seenPx(resultLate), Settings.HIT_POINT_MIN, Settings.HIT_POINT_MAX));
            resultKind = resultMad > HitPointScreen.MAX_MAD_MS ? RES_INCONSISTENT : RES_OK;
        }
        buildResultText();
        setState(RESULT);
        if (resultKind == RES_OK) Haptics.medium();
    }

    private void buildResultText() {
        resultValue.setLength(0);
        resultLine.setLength(0);
        resultSub.setLength(0);
        // the spread as the distance a key travels in it
        int spread = Float.isNaN(resultMad) ? 0 : Math.round(SPEED * resultMad / runSize);
        boolean zh = Lang.zh();
        if (resultKind == RES_TOO_FEW) {
            resultValue.append(zh ? "有效点击不够" : "NOT ENOUGH TAPS");
            if (zh) {
                resultLine.append("有效点击 ");
                Fonts.appendInt(resultLine, resultCount).append(" 次 · 至少需要 ");
                Fonts.appendInt(resultLine, HitPointScreen.REQUIRED).append(" 次");
            } else {
                Fonts.appendInt(resultLine, resultCount).append(" VALID TAPS · AT LEAST ");
                Fonts.appendInt(resultLine, HitPointScreen.REQUIRED).append(" NEEDED");
            }
            resultSub.append(zh ? "每个按键到线时都点一下屏幕" : "TAP ANYWHERE AS EACH KEY REACHES THE LINE");
            return;
        }
        if (resultKind == RES_INCONSISTENT) {
            resultValue.append(zh ? "不稳定 · 再试一次" : "INCONSISTENT · TRY AGAIN");
            resultLine.append(zh ? "点击偏差 ±" : "YOUR TAPS SPREAD ±");
            Fonts.appendInt(resultLine, spread).append(" px");
            resultSub.append(zh ? "盯住按键，每次都在同一个位置点" : "WATCH THE KEYS AND TAP AT THE SAME POINT EACH TIME");
            return;
        }
        Fonts.appendInt(resultValue, resultPx);
        int seen = Math.round(seenPx(resultLate));
        if (seen == 0) {
            resultLine.append(zh ? "你点的时候，判定线正好穿过按键中间" : "WHEN YOU TAP, THE LINE RUNS THROUGH THE KEY'S MIDDLE");
        } else if (zh) {
            resultLine.append("你点的时候，按键中间在判定线").append(seen < 0 ? "下方 " : "上方 ");
            Fonts.appendInt(resultLine, Math.abs(seen)).append(" px");
        } else {
            resultLine.append("WHEN YOU TAP, THE KEY'S MIDDLE IS ");
            Fonts.appendInt(resultLine, Math.abs(seen)).append(seen < 0 ? " px BELOW THE LINE" : " px ABOVE THE LINE");
        }
        resultSub.append(zh ? "偏差 ±" : "SPREAD ±");
        Fonts.appendInt(resultSub, spread).append(" px · ");
        if (zh) {
            resultSub.append("有效 ");
            Fonts.appendInt(resultSub, resultCount).append(" / ");
            Fonts.appendInt(resultSub, MEASURED).append(" 次");
        } else {
            Fonts.appendInt(resultSub, resultCount).append(" OF ");
            Fonts.appendInt(resultSub, MEASURED).append(" TAPS");
        }
    }

    private void applyResult() {
        s().hitPointPx = resultPx;
        game.profiles().markDirty();
        game.applySettings();
        game.profiles().save();
        applied = true;
        apply.setEnabled(false);
        refreshCurrent();
        StringBuilder sb = new StringBuilder(32);
        sb.append(Lang.t("VISUAL HIT POINT · ", "目押校准 · "));
        Fonts.appendInt(sb, resultPx).append(" px");
        game.toasts().show(sb.toString(), Theme.GROUND_SIG);
        Haptics.medium();
        host.applied();
    }

    // ------------------------------------------------------------------ layout
    /** Lays the panel out over the whole screen (the VISUAL HIT POINT screen). */
    void layoutScreen() {
        float sl = Ui.safeLeft(), sr = Ui.safeRight(), cy = Ui.CY;
        inCard = false;
        cx = Ui.CX;
        k = 1f;
        textW = sr - sl - 80f;
        titleY = cy + 330f;
        line1Y = cy + 268f;
        line2Y = cy + 228f;
        diagramY = cy + 66f;
        currentY = cy - 74f;
        startY = cy - 160f;
        footY = cy - 330f;
        okValueY = cy + 330f;
        valueSize = 96f;
        okLineY = cy + 250f;
        okSubY = cy + 210f;
        resultDiagram = true;
        resultDiagramY = cy + 46f;
        failValueY = cy + 200f;
        failLineY = cy + 110f;
        failSubY = cy + 62f;
        buttonsY = cy - 160f;
        lanesX = Ui.CX;
        runLineY = 120f;
        runX0 = Ui.left();
        runX1 = Ui.right();
        colX = sl + 40f;
        colW = lanesX - LANES_HALF - 40f - colX;
        statusY = 900f;
        hintY = 850f;
        dotsY = 780f;
        placeButtons();
        strip.setBounds(Ui.CX, 200f, Math.min(1200f, sr - sl - 160f));
    }

    /**
     * Lays the panel out in a card at ({@code x}, {@code y}) of {@code w} × {@code h} (the first run): the intro about
     * the card's centre, the result like the audio calibration's card (no diagram) and the run with the lanes at the
     * right and the instructions at the left. A card lower than {@link CalibrationPanel#CARD_H} squeezes the gaps by
     * the same factor; the diagram, the keys and the buttons keep their size.
     */
    void layoutCard(float x, float y, float w, float h) {
        float c = y + h * 0.5f;
        inCard = true;
        cx = x + w * 0.5f;
        k = Math.min(1f, h / CalibrationPanel.CARD_H);
        textW = w - 96f;
        // the title and START where the audio calibration had them; the diagram keeps its size, so the texts around it
        // keep their distances to it and the bottom ones give way on a short card
        diagramY = c + 30f * k;
        line1Y = diagramY + DIAGRAM_H * 0.5f + 35f;
        line2Y = line1Y;
        titleY = line1Y + 62f;
        currentY = diagramY - DIAGRAM_H * 0.5f - 30f;
        startY = Math.min(c - 203f * k, currentY - 85f);
        footY = Math.min(c - 293f * k, startY - 85f);
        okValueY = c + 215f * k;
        valueSize = 96f * k;
        okLineY = c + 105f * k;
        okSubY = c + 57f * k;
        resultDiagram = false;
        failValueY = c + 205f * k;
        failLineY = okLineY;
        failSubY = okSubY;
        buttonsY = c - 55f * k;
        lanesX = x + w - 72f - LANES_HALF;
        runLineY = y + 100f;
        runX0 = x + 24f;
        runX1 = x + w - 24f;
        fadeTop = y + h - 20f;
        fadeBottom = y + 16f;
        colX = x + 56f;
        colW = lanesX - LANES_HALF - 40f - colX;
        statusY = y + h - 96f * k;
        hintY = statusY - 50f * k;
        dotsY = hintY - 70f * k;
        placeButtons();
        strip.setBounds(cx, Math.max(c - 228f * k, y + 110f), Math.min(1200f, w - 160f));
    }

    private void placeButtons() {
        float bh = Button.standardHeight();
        start.setBounds(cx - 220f, startY - bh * 0.5f, 440f, bh);
        placeResultButtons();
    }

    private void placeResultButtons() {
        float bh = Button.standardHeight();
        float y = buttonsY - bh * 0.5f;
        if (resultKind == RES_OK) {
            float aw = 400f, ow = 260f, gap = 24f;
            float x = cx - (aw + gap + ow) * 0.5f;
            apply.setBounds(x, y, aw, bh);
            retry.setBounds(x + aw + gap, y, ow, bh);
        } else {
            retry.setBounds(cx - 200f, y, 400f, bh);
        }
    }

    // ------------------------------------------------------------------ frame
    /** Advances the run, the result's animation and the ripples. */
    void update(float delta) {
        stateT += delta;
        for (int i = 0; i < RIPPLES; i++) rippleT[i] += delta;
        strip.update(delta);
        if (state == RUN && msSince(TimeUtils.nanoTime()) > keyMs(KEYS - 1) + 600f) finishRun();
    }

    /** Draws the current state at {@code a} (the buttons are drawn by the owner's widget set). */
    void draw(Batch b, float a) {
        switch (state) {
            case INTRO: drawIntro(b, a); break;
            case RUN: drawRun(b, a); break;
            case RESULT: drawResult(b, a); break;
            default: break;
        }
        drawRipples(a);
    }

    private void drawIntro(Batch b, float a) {
        Fonts f = game.fonts();
        f.drawFit(b, Fonts.Role.UI_BOLD, 44f, Lang.t("TAP WHEN A KEY REACHES THE LINE", "看到按键到线就点"), cx, titleY,
            Align.center | Fonts.VCENTER, textW, 0.7f, Theme.INK1, a);
        if (inCard) {
            // one line in the card, like the audio calibration's
            f.drawFit(b, Fonts.Role.UI_REGULAR, 24f, Lang.t(
                "NO SOUND · TAP ANYWHERE THE WAY YOU PLAY BY EYE · 4 WARM-UP KEYS, THEN 16 MEASURED",
                "全程没有声音 · 按你平时目押的习惯点屏幕任意位置 · 先落 4 个练习，再测 16 个"), cx, line1Y,
                Align.center | Fonts.VCENTER, textW, 0.7f, Theme.INK2, a);
        } else {
            f.drawFit(b, Fonts.Role.UI_REGULAR, 24f, Lang.t("NO SOUND · TAP ANYWHERE THE WAY YOU PLAY BY EYE",
                "全程没有声音 · 按你平时目押的习惯点屏幕任意位置"), cx, line1Y, Align.center | Fonts.VCENTER, textW, 0.7f,
                Theme.INK2, a);
            f.drawFit(b, Fonts.Role.UI_REGULAR, 24f, Lang.t("4 WARM-UP KEYS, THEN 16 MEASURED",
                "先落 4 个练习，再测 16 个"), cx, line2Y, Align.center | Fonts.VCENTER, textW, 0.7f, Theme.INK2, a);
        }
        drawDiagram(b, s().hitPointPx, cx, diagramY, a);
        f.draw(b, Fonts.Role.UI_LABEL, 18f, currentText, cx, currentY, Align.center | Fonts.VCENTER, Theme.INK2, a);
        // 0 is the default since 2026-10-20 22:37 (it was 20, the key's bottom edge on the line)
        CharSequence foot = interrupted ? Lang.t("RUN INTERRUPTED · TAP START TO TRY AGAIN", "校准被打断 · 点「开始」再试一次")
            : Lang.t("0 = THE LINE THROUGH THE KEY'S MIDDLE (DEFAULT) · 20 = THE KEY'S BOTTOM EDGE ON THE LINE",
            "0 = 判定线横穿按键中间（默认）· 20 = 按键下边缘碰线");
        f.drawFit(b, interrupted ? Fonts.Role.UI : Fonts.Role.UI_REGULAR, 18f, foot, cx, footY,
            Align.center | Fonts.VCENTER, textW, 0.7f, interrupted ? Theme.GOLD : Theme.INK3, a);
    }

    /**
     * A key at the judgement time, {@code px} from its midline to the line, at its real size on a panel centred on
     * ({@code x}, {@code y}): the line (labelled at its left end, on the side away from the key), the key with its
     * dashed midline (labelled left of the key, kept clear of the line) and the gold distance between them with its
     * value, beside them or, when they nearly meet, above.
     */
    private void drawDiagram(Batch b, float px, float x, float y, float a) {
        Draw d = game.draw();
        Fonts f = game.fonts();
        float kw = KEY_W, kh = KEY_H, lineY = y + DIAGRAM_LINE;
        d.panel(x - DIAGRAM_W * 0.5f, y - DIAGRAM_H * 0.5f, DIAGRAM_W, DIAGRAM_H, 18f, a);
        float x0 = x - DIAGRAM_W * 0.5f + 24f, x1 = x + DIAGRAM_W * 0.5f - 24f;
        d.rect(x0, lineY - 2f, x1 - x0, 4f, Theme.GROUND_SIG, 0.95f * a);
        // the two labels on opposite sides of the line, so they never meet
        boolean above = px >= 0f;
        f.draw(b, Fonts.Role.UI_LABEL, 15f, Lang.t("LINE", "判定线"), x0, above ? lineY - 20f : lineY + 20f,
            Align.left | Fonts.VCENTER, Theme.GROUND_SIG, a);
        float mid = lineY + MathUtils.clamp(px, DIAGRAM_LO, DIAGRAM_HI);
        float kx = x - 10f - kw * 0.5f;
        NinePatch note = d.note9();
        float old = b.getPackedColor();
        b.setColor(Theme.GROUND_SIG.r, Theme.GROUND_SIG.g, Theme.GROUND_SIG.b, 0.9f * a);
        note.draw(b, kx, mid - kh * 0.5f, kw, kh);
        b.setPackedColor(old);
        // the midline, dashed, out to the distance marker
        float bx = kx + kw + 50f;
        for (float dx = kx - 16f; dx < bx; dx += 18f) d.rect(dx, mid - 1f, 10f, 2f, Theme.INK1, 0.9f * a);
        float my = above ? Math.max(mid, lineY + 22f) : Math.min(mid, lineY - 22f);
        f.draw(b, Fonts.Role.UI_LABEL, 15f, Lang.t("MIDDLE", "按键中线"), kx - 24f, my, Align.right | Fonts.VCENTER,
            Theme.INK1, a);
        float y0 = Math.min(mid, lineY), y1 = Math.max(mid, lineY);
        if (y1 - y0 > 2f) d.rect(bx - 1.5f, y0, 3f, y1 - y0, Theme.GOLD, a);
        d.rect(bx - 10f, mid - 1.5f, 20f, 3f, Theme.GOLD, a);
        d.rect(bx - 10f, lineY - 1.5f, 20f, 3f, Theme.GOLD, a);
        diagramText.setLength(0);
        Fonts.appendInt(diagramText, Math.round(px)).append(" px");
        float ty = y1 - y0 >= 40f ? (y0 + y1) * 0.5f : y1 + 24f;
        f.draw(b, Fonts.Role.NUMBER, 24f, diagramText, bx + 18f, ty, Align.left | Fonts.VCENTER, Theme.GOLD, a);
    }

    private void drawRun(Batch b, float a) {
        Draw d = game.draw();
        Fonts f = game.fonts();
        float w = runX1 - runX0;
        d.rectGradientV(runX0, runLineY, w, 108f, Theme.GROUND_SIG, Theme.withAlpha(Theme.GROUND_SIG, 0f, tmp),
            0.06f * a);
        d.sprite(d.glowV(), (runX0 + runX1) * 0.5f, runLineY, w, 48f, Theme.GROUND_SIG, 0.18f * a);
        d.rect(runX0, runLineY - 2f, w, 4f, Theme.GROUND_SIG, 0.95f * a);
        // the keys, as the game draws them: midline hitPointPx × size from the line at the judgement time
        float size = runSize, kh = KEY_H * size, kw = KEY_W * Math.min(size, 1.15f);
        float shift = (KEY_H * 0.5f - runHit) * size;
        double now = msSince(TimeUtils.nanoTime());
        float top = inCard ? fadeTop : Ui.top();
        NinePatch note = d.note9();
        float old = b.getPackedColor();
        for (int i = 0; i < KEYS; i++) {
            float ahead = (float) (keyMs(i) - now);
            float y = runLineY + SPEED * ahead - shift;
            if (y > top) continue;
            float na = ahead < -100f ? 1f + (ahead + 100f) / 150f : 1f;
            // in a card the keys fade in under its top edge and out above its bottom edge
            if (inCard) na *= MathUtils.clamp((top - y - kh) / 70f, 0f, 1f) * MathUtils.clamp((y - fadeBottom) / 40f,
                0f, 1f);
            if (na <= 0f) continue;
            b.setColor(Theme.GROUND_SIG.r, Theme.GROUND_SIG.g, Theme.GROUND_SIG.b, na * a);
            note.draw(b, laneX(i) - kw * 0.5f, y, kw, kh);
        }
        b.setPackedColor(old);
        // what to do, at the left, clear of the lanes
        boolean warm = now < keyMs(WARM_UP) - BEAT_MS * 0.5f;
        f.drawFit(b, Fonts.Role.UI_BOLD, 34f, warm ? Lang.t("WARM-UP", "练习") : Lang.t("KEEP TAPPING", "继续点"), colX,
            statusY, Align.left | Fonts.VCENTER, colW, 0.6f, Theme.INK1, a);
        f.drawFit(b, Fonts.Role.UI_REGULAR, 20f, Lang.t("TAP WHEN A KEY REACHES THE LINE", "看到按键到线就点"), colX,
            hintY, Align.left | Fonts.VCENTER, colW, 0.6f, Theme.INK2, a);
        drawProgress(b, colX, dotsY, a);
    }

    private void drawProgress(Batch b, float x0, float y, float a) {
        int filled = stats.count();
        Draw d = game.draw();
        for (int i = 0; i < MEASURED; i++) {
            float x = x0 + 7f + (i % 8) * DOTS_GAP, dy = y - (i / 8) * DOTS_GAP;
            if (i < filled) d.disc(x, dy, 7f, Theme.GROUND_SIG, 0.95f * a);
            else d.circle(x, dy, 6f, 2f, Theme.INK3, 0.8f * a);
        }
        if (filled != countFor) {
            countFor = filled;
            countText.setLength(0);
            countText.append(Lang.t("VALID TAPS · ", "有效点击 · "));
            Fonts.appendInt(countText, filled).append(" / ");
            Fonts.appendInt(countText, MEASURED);
        }
        game.fonts().draw(b, Fonts.Role.UI_LABEL, 16f, countText, x0, y - 2f * DOTS_GAP - 4f,
            Align.left | Fonts.VCENTER, Theme.INK3, a);
    }

    private void drawResult(Batch b, float a) {
        Fonts f = game.fonts();
        float in = Anim.POW3_OUT.apply(Math.min(1f, stateT / 0.32f));
        float lift = 24f * (1f - in), ra = a * in;
        if (resultKind == RES_OK) {
            float u = valueSize / 96f;
            // the value and its unit centred together (Kelvin 2026-10-21: 「那个+40ms和-32px不居中」)
            float right = cx + (f.width(Fonts.Role.NUMBER, valueSize, resultValue) - 14f
                - f.width(Fonts.Role.UI_BOLD, 36f * u, PX)) * 0.5f;
            f.draw(b, Fonts.Role.NUMBER, valueSize, resultValue, right, okValueY - lift,
                Align.right | Fonts.VCENTER, Theme.INK1, ra);
            f.draw(b, Fonts.Role.UI_BOLD, 36f * u, PX, right + 14f, okValueY - 18f * u - lift,
                Align.left | Fonts.VCENTER, Theme.INK2, ra);
            if (applied) {
                f.draw(b, Fonts.Role.UI_LABEL, 18f, Lang.t("APPLIED", "已应用"), right + 14f, okValueY + 32f * u - lift,
                    Align.left | Fonts.VCENTER, Theme.RESONANCE, ra);
            }
            f.drawFit(b, Fonts.Role.UI, 26f, resultLine, cx, okLineY - lift, Align.center | Fonts.VCENTER, textW, 0.7f,
                Theme.INK1, ra);
            f.drawFit(b, Fonts.Role.UI_REGULAR, 20f, resultSub, cx, okSubY - lift, Align.center | Fonts.VCENTER, textW,
                0.7f, Theme.INK3, ra);
            if (resultDiagram) drawDiagram(b, resultPx, cx, resultDiagramY, ra);
        } else {
            f.drawFit(b, Fonts.Role.UI_BOLD, 52f * k, resultValue, cx, failValueY - lift, Align.center | Fonts.VCENTER,
                textW, 0.7f, resultKind == RES_INCONSISTENT ? Theme.LOST : Theme.GOLD, ra);
            f.drawFit(b, Fonts.Role.UI, 26f, resultLine, cx, failLineY - lift, Align.center | Fonts.VCENTER, textW,
                0.7f, Theme.INK1, ra);
            f.drawFit(b, Fonts.Role.UI_REGULAR, 20f, resultSub, cx, failSubY - lift, Align.center | Fonts.VCENTER,
                textW, 0.7f, Theme.INK3, ra);
        }
        strip.draw(b, a);
    }

    private void ripple(float x, float y) {
        rippleX[rippleNext] = x;
        rippleY[rippleNext] = y;
        rippleT[rippleNext] = 0f;
        rippleNext = (rippleNext + 1) % RIPPLES;
        game.backdrop().touch(x, y);
    }

    private void drawRipples(float a) {
        Draw d = game.draw();
        for (int i = 0; i < RIPPLES; i++) {
            float t = rippleT[i];
            if (t >= RIPPLE_LIFE) continue;
            float p = t / RIPPLE_LIFE;
            float r = 28f + 130f * Anim.POW2_OUT.apply(p);
            d.circle(rippleX[i], rippleY[i], r, 4f * (1f - p) + 1f, Theme.INK1, 0.45f * (1f - p) * a);
        }
    }

    // ------------------------------------------------------------------ input (after the owner's widgets)
    boolean touchDown(float x, float y, int pointer) {
        if (state != RUN) return false;
        tap(x, y, eventNanos());
        return true;
    }

    /** A key: a tap during a run, SPACE / ENTER starts from the intro. */
    boolean keyDown(int keycode) {
        if (state == RUN) {
            tap(lanesX, runLineY, eventNanos());
            return true;
        }
        if (state == INTRO && (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER)) {
            startRun();
            return true;
        }
        return false;
    }
}
