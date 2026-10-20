package org.kelvinizer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import org.kelvinizer.Main;
import org.kelvinizer.audio.AudioService;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.screens.shell.TapStats;
import org.kelvinizer.screens.shell.TapStrip;
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
 * 目押校准：没有声音，按键一个个落到判定线上，玩家按平时目押的习惯，看到按键「到线」就点屏幕；
 * 取点击误差的中位数换算成像素，得出新的目押校准值（判定时按键中线到判定线的距离），可以应用或重测。
 *
 * <p>VISUAL HIT POINT calibration (Kelvin 2026-10-20 03:20: 「下面的校准会单独弹出来一个界面」), opened by CALIBRATE…
 * under the VISUAL HIT POINT row of Settings → VISUAL ({@link Settings#hitPointPx}).
 * <ul>
 *   <li><b>Intro</b>: what to do, a diagram of a key at the judgement time with the current value (its midline, the
 *       line and the distance between them), START.</li>
 *   <li><b>Run</b>: silent on purpose (sound would turn it into the audio calibration). {@link #WARM_UP} keys, then
 *       {@link #MEASURED}, fall onto a ground line every {@link #BEAT_MS} at {@link #SPEED} (1080 a second: the
 *       default two beats at 120 BPM), drawn as the game draws them with the current value and note size. Any touch
 *       (or key) is a tap, matched to the nearest key within {@link #MAX_TAP_ERROR_MS}; a ripple marks it and nothing
 *       says early or late, which would bias the taps. The clock is the touch events' own monotonic clock, as the
 *       game draws and judges notes on one clock.</li>
 *   <li><b>Result</b>: the median lateness {@code t} (ms) of the measured taps moves the hit point by the distance a
 *       key travels in that time, {@code new = current − SPEED·t / noteSize} (a late tapper sees the hit when the key
 *       is further down, so the key is drawn further down at the judgement time), rounded and clamped. Fewer than
 *       {@link #REQUIRED} valid taps or a spread (MAD) over {@link #MAX_MAD_MS} asks for a retry. APPLY stores it for
 *       the active player; RETRY runs again. The −150..+150 ms strip shows the taps.</li>
 * </ul>
 * BACK during a run returns to the intro; backgrounding the app abandons the run. Nothing is allocated per frame.
 */
public class HitPointScreen extends ShellScreen {
    /** Title, also the label of the Settings row and the CURTAIN label of its CALIBRATE…. */
    public static final CharSequence TITLE = Lang.of("VISUAL HIT POINT", "目押校准");
    /** Warm-up keys (not measured), measured keys, valid taps required. */
    public static final int WARM_UP = 4, MEASURED = 16, REQUIRED = 12;
    /** Spread (median absolute deviation) above which the result is rejected; taps further off are dropped. */
    public static final float MAX_MAD_MS = 35f, MAX_TAP_ERROR_MS = 250f;
    /** Fall speed (world units per ms), time between keys, time before the first key reaches the line. */
    public static final float SPEED = 1.08f, BEAT_MS = 600f, LEAD_MS = 1500f;

    private static final int INTRO = 0, RUN = 1, RESULT = 2;
    private static final int RES_OK = 0, RES_INCONSISTENT = 1, RES_TOO_FEW = 2;
    private static final int KEYS = WARM_UP + MEASURED;
    private static final float LINE_Y = 120f, LANE_GAP = 240f, KEY_W = 200f, KEY_H = 40f, DOTS_GAP = 30f;
    /** Lane of each key in turn (four lanes about the centre). */
    private static final int[] LANE_ORDER = {0, 2, 1, 3, 2, 0, 3, 1};
    /**
     * The diagram: a panel of {@code DIAGRAM_W × DIAGRAM_H} with a key at its real size, the range of values drawn to
     * scale (others at its ends, labelled with their own value) and the line's height under the panel's centre.
     */
    private static final float DIAGRAM_W = 600f, DIAGRAM_H = 220f, DIAGRAM_LO = -60f, DIAGRAM_HI = 80f,
        DIAGRAM_LINE = -10f;
    /** Vertical places (from the centre): intro diagram, CURRENT, START / the result's buttons, foot; result diagram. */
    private static final float INTRO_DIAGRAM = 66f, CURRENT_Y = -74f, BUTTONS_Y = -160f, FOOT_Y = -330f,
        RESULT_DIAGRAM = 46f;
    private static final int RIPPLES = 10;
    private static final float RIPPLE_LIFE = 0.5f;

    private static final CharSequence PX = "px";
    private static final Settings FALLBACK = new Settings();

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
    private int resultKind, resultPx, resultCount, countFor = -1, rippleNext;
    private float resultMad, resultLate;
    private boolean applied, interrupted;

    /** Creates the screen once (Main caches it). */
    public HitPointScreen(final Main game) {
        super(game, TITLE, null, 0f, 0.6f, false);
        Draw d = game.draw();
        Fonts f = game.fonts();
        strip = new TapStrip(game, MEASURED);
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

    private static float laneX(int i) {
        return Ui.CX + (LANE_ORDER[i % LANE_ORDER.length] - 1.5f) * LANE_GAP;
    }

    private void refreshCurrent() {
        currentText.setLength(0);
        currentText.append(Lang.t("CURRENT · ", "当前 · "));
        Fonts.appendInt(currentText, Math.round(s().hitPointPx)).append(" px");
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected boolean playsMenuMusic() {
        return false;
    }

    @Override
    protected void enterScreen() {
        AudioService a = game.audio();
        a.stopSlot(AudioService.Slot.MENU, 0.3f);
        a.stopSlot(AudioService.Slot.PREVIEW, 0.2f);
        interrupted = false;
        stats.clear();
        strip.clear();
        refreshCurrent();
        setState(INTRO);
    }

    @Override
    protected void leaveScreen() {
        game.profiles().save();
    }

    @Override
    protected void back() {
        if (state == RUN) {
            setState(INTRO);
            return;
        }
        goBack();
    }

    /** App backgrounded: the gap would break the run, so it is abandoned. */
    @Override
    public void pause() {
        if (state != RUN) return;
        interrupted = true;
        setState(INTRO);
    }

    // ------------------------------------------------------------------ state
    private void setState(int st) {
        state = st;
        stateT = 0f;
        start.setVisible(st == INTRO);
        boolean ok = st == RESULT && resultKind == RES_OK;
        apply.setVisible(ok);
        apply.setEnabled(!applied);
        retry.setVisible(st == RESULT);
        retry.setVariant(ok ? Button.Variant.SECONDARY : Button.Variant.PRIMARY);
        backButton.setVisible(st != RUN);
        placeResultButtons();
    }

    private void startRun() {
        stats.clear();
        strip.clear();
        Arrays.fill(taken, false);
        countFor = -1;
        applied = false;
        interrupted = false;
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
        if (taken[i] || Math.abs(delta) > MAX_TAP_ERROR_MS) return;
        taken[i] = true;
        if (i < WARM_UP) return;
        stats.add(delta);
        strip.add(delta);
        strip.setMedian(stats.median());
    }

    private void finishRun() {
        resultCount = stats.count();
        resultMad = stats.mad();
        if (resultCount < REQUIRED) {
            resultKind = RES_TOO_FEW;
        } else {
            resultLate = stats.median();
            float px = s().hitPointPx - SPEED * resultLate / s().noteSizeScale;
            resultPx = Math.round(MathUtils.clamp(px, Settings.HIT_POINT_MIN, Settings.HIT_POINT_MAX));
            resultKind = resultMad > MAX_MAD_MS ? RES_INCONSISTENT : RES_OK;
        }
        buildResultText();
        setState(RESULT);
        if (resultKind == RES_OK) Haptics.medium();
    }

    private void buildResultText() {
        resultValue.setLength(0);
        resultLine.setLength(0);
        resultSub.setLength(0);
        int mad = Float.isNaN(resultMad) ? 0 : Math.round(resultMad);
        boolean zh = Lang.zh();
        if (resultKind == RES_TOO_FEW) {
            resultValue.append(zh ? "有效点击不够" : "NOT ENOUGH TAPS");
            if (zh) {
                resultLine.append("有效点击 ");
                Fonts.appendInt(resultLine, resultCount).append(" 次 · 至少需要 ");
                Fonts.appendInt(resultLine, REQUIRED).append(" 次");
            } else {
                Fonts.appendInt(resultLine, resultCount).append(" VALID TAPS · AT LEAST ");
                Fonts.appendInt(resultLine, REQUIRED).append(" NEEDED");
            }
            resultSub.append(zh ? "每个按键到线时都点一下屏幕" : "TAP ANYWHERE AS EACH KEY REACHES THE LINE");
            return;
        }
        if (resultKind == RES_INCONSISTENT) {
            resultValue.append(zh ? "不稳定 · 再试一次" : "INCONSISTENT · TRY AGAIN");
            resultLine.append(zh ? "点击偏差 ±" : "YOUR TAPS SPREAD ±");
            Fonts.appendInt(resultLine, mad).append(" ms");
            resultSub.append(zh ? "盯住按键，每次都在同一个位置点" : "WATCH THE KEYS AND TAP AT THE SAME POINT EACH TIME");
            return;
        }
        Fonts.appendInt(resultValue, resultPx);
        int late = Math.round(resultLate);
        if (late == 0) {
            resultLine.append(zh ? "你的点击正好落在判定上" : "YOUR TAPS LAND RIGHT ON THE HIT");
        } else if (zh) {
            resultLine.append("你的点击比判定").append(late > 0 ? "晚 " : "早 ");
            Fonts.appendInt(resultLine, Math.abs(late)).append(" ms");
        } else {
            resultLine.append("YOUR TAPS LAND ");
            Fonts.appendInt(resultLine, Math.abs(late)).append(late > 0 ? " ms AFTER THE HIT" : " ms BEFORE THE HIT");
        }
        resultSub.append(zh ? "偏差 ±" : "SPREAD ±");
        Fonts.appendInt(resultSub, mad).append(" ms · ");
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
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void layout() {
        float bh = Button.standardHeight();
        start.setBounds(Ui.CX - 220f, Ui.CY + BUTTONS_Y - bh * 0.5f, 440f, bh);
        strip.setBounds(Ui.CX, 200f, Math.min(1200f, Ui.safeRight() - Ui.safeLeft() - 160f));
        placeResultButtons();
    }

    private void placeResultButtons() {
        float bh = Button.standardHeight();
        float y = Ui.CY + BUTTONS_Y - bh * 0.5f;
        if (resultKind == RES_OK) {
            float aw = 400f, ow = 260f, gap = 24f;
            float x = Ui.CX - (aw + gap + ow) * 0.5f;
            apply.setBounds(x, y, aw, bh);
            retry.setBounds(x + aw + gap, y, ow, bh);
        } else {
            retry.setBounds(Ui.CX - 200f, y, 400f, bh);
        }
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void update(float delta) {
        stateT += delta;
        for (int i = 0; i < RIPPLES; i++) rippleT[i] += delta;
        strip.update(delta);
        if (state == RUN && msSince(TimeUtils.nanoTime()) > keyMs(KEYS - 1) + 600f) finishRun();
    }

    @Override
    protected void drawScreen(float delta) {
        float e = enter(0.1f);
        switch (state) {
            case INTRO: drawIntro(e); break;
            case RUN: drawRun(e); break;
            case RESULT: drawResult(e); break;
            default: break;
        }
        drawRipples(e);
    }

    private void drawIntro(float a) {
        Fonts f = game.fonts();
        float cx = Ui.CX, cy = Ui.CY, maxW = Ui.safeRight() - Ui.safeLeft() - 80f;
        f.draw(batch, Fonts.Role.UI_BOLD, 44f, Lang.t("TAP WHEN A KEY REACHES THE LINE", "看到按键到线就点"), cx,
            cy + 330f, Align.center | Fonts.VCENTER, Theme.INK1, a);
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 24f, Lang.t("NO SOUND · TAP ANYWHERE THE WAY YOU PLAY BY EYE",
            "全程没有声音 · 按你平时目押的习惯点屏幕任意位置"), cx, cy + 268f, Align.center | Fonts.VCENTER, maxW, 0.7f,
            Theme.INK2, a);
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 24f, Lang.t("4 WARM-UP KEYS, THEN 16 MEASURED",
            "先落 4 个练习，再测 16 个"), cx, cy + 228f, Align.center | Fonts.VCENTER, maxW, 0.7f, Theme.INK2, a);
        drawDiagram(s().hitPointPx, cx, cy + INTRO_DIAGRAM, a);
        f.draw(batch, Fonts.Role.UI_LABEL, 18f, currentText, cx, cy + CURRENT_Y, Align.center | Fonts.VCENTER, Theme.INK2,
            a);
        CharSequence foot = interrupted ? Lang.t("RUN INTERRUPTED · TAP START TO TRY AGAIN", "校准被打断 · 点「开始」再试一次")
            : Lang.t("20 = THE KEY'S BOTTOM EDGE ON THE LINE (DEFAULT) · 0 = THE LINE THROUGH ITS MIDDLE",
            "20 = 按键下边缘碰线（默认）· 0 = 判定线横穿按键中间");
        f.drawFit(batch, interrupted ? Fonts.Role.UI : Fonts.Role.UI_REGULAR, 18f, foot, cx, cy + FOOT_Y,
            Align.center | Fonts.VCENTER, maxW, 0.7f, interrupted ? Theme.GOLD : Theme.INK3, a);
    }

    /**
     * A key at the judgement time, {@code px} from its midline to the line, at its real size on a panel centred on
     * ({@code cx}, {@code cy}): the line (labelled at its left end, on the side away from the key), the key with its
     * dashed midline (labelled left of the key, kept clear of the line) and the gold distance between them with its
     * value, beside them or, when they nearly meet, above.
     */
    private void drawDiagram(float px, float cx, float cy, float a) {
        Draw d = game.draw();
        Fonts f = game.fonts();
        float kw = KEY_W, kh = KEY_H, lineY = cy + DIAGRAM_LINE;
        d.panel(cx - DIAGRAM_W * 0.5f, cy - DIAGRAM_H * 0.5f, DIAGRAM_W, DIAGRAM_H, 18f, a);
        float x0 = cx - DIAGRAM_W * 0.5f + 24f, x1 = cx + DIAGRAM_W * 0.5f - 24f;
        d.rect(x0, lineY - 2f, x1 - x0, 4f, Theme.GROUND_SIG, 0.95f * a);
        // the two labels on opposite sides of the line, so they never meet
        boolean above = px >= 0f;
        f.draw(batch, Fonts.Role.UI_LABEL, 15f, Lang.t("LINE", "判定线"), x0, above ? lineY - 20f : lineY + 20f,
            Align.left | Fonts.VCENTER, Theme.GROUND_SIG, a);
        float mid = lineY + MathUtils.clamp(px, DIAGRAM_LO, DIAGRAM_HI);
        float kx = cx - 10f - kw * 0.5f;
        NinePatch note = d.note9();
        float old = batch.getPackedColor();
        batch.setColor(Theme.GROUND_SIG.r, Theme.GROUND_SIG.g, Theme.GROUND_SIG.b, 0.9f * a);
        note.draw(batch, kx, mid - kh * 0.5f, kw, kh);
        batch.setPackedColor(old);
        // the midline, dashed, out to the distance marker
        float bx = kx + kw + 50f;
        for (float x = kx - 16f; x < bx; x += 18f) d.rect(x, mid - 1f, 10f, 2f, Theme.INK1, 0.9f * a);
        float my = above ? Math.max(mid, lineY + 22f) : Math.min(mid, lineY - 22f);
        f.draw(batch, Fonts.Role.UI_LABEL, 15f, Lang.t("MIDDLE", "按键中线"), kx - 24f, my,
            Align.right | Fonts.VCENTER, Theme.INK1, a);
        float y0 = Math.min(mid, lineY), y1 = Math.max(mid, lineY);
        if (y1 - y0 > 2f) d.rect(bx - 1.5f, y0, 3f, y1 - y0, Theme.GOLD, a);
        d.rect(bx - 10f, mid - 1.5f, 20f, 3f, Theme.GOLD, a);
        d.rect(bx - 10f, lineY - 1.5f, 20f, 3f, Theme.GOLD, a);
        diagramText.setLength(0);
        Fonts.appendInt(diagramText, Math.round(px)).append(" px");
        float ty = y1 - y0 >= 40f ? (y0 + y1) * 0.5f : y1 + 24f;
        f.draw(batch, Fonts.Role.NUMBER, 24f, diagramText, bx + 18f, ty, Align.left | Fonts.VCENTER, Theme.GOLD, a);
    }

    private void drawRun(float a) {
        Draw d = game.draw();
        Fonts f = game.fonts();
        float left = Ui.left(), w = Ui.right() - left;
        d.rectGradientV(left, LINE_Y, w, 108f, Theme.GROUND_SIG, Theme.withAlpha(Theme.GROUND_SIG, 0f, tmp),
            0.06f * a);
        d.sprite(d.glowV(), Ui.CX, LINE_Y, w, 48f, Theme.GROUND_SIG, 0.18f * a);
        d.rect(left, LINE_Y - 2f, w, 4f, Theme.GROUND_SIG, 0.95f * a);
        // the keys, as the game draws them: midline hitPointPx × size from the line at the judgement time
        Settings st = s();
        float size = st.noteSizeScale, kh = KEY_H * size, kw = KEY_W * Math.min(size, 1.15f);
        float shift = (KEY_H * 0.5f - st.hitPointPx) * size;
        double now = msSince(TimeUtils.nanoTime());
        NinePatch note = d.note9();
        float old = batch.getPackedColor();
        for (int i = 0; i < KEYS; i++) {
            float ahead = (float) (keyMs(i) - now);
            float y = LINE_Y + SPEED * ahead - shift;
            if (y > Ui.top()) continue;
            float na = ahead < -100f ? 1f + (ahead + 100f) / 150f : 1f;
            if (na <= 0f) continue;
            batch.setColor(Theme.GROUND_SIG.r, Theme.GROUND_SIG.g, Theme.GROUND_SIG.b, na * a);
            note.draw(batch, laneX(i) - kw * 0.5f, y, kw, kh);
        }
        batch.setPackedColor(old);
        // what to do, at the left, clear of the lanes
        float px = Ui.safeLeft() + 40f, maxW = Ui.CX - 1.5f * LANE_GAP - KEY_W * 0.5f - 40f - px;
        boolean warm = now < keyMs(WARM_UP) - BEAT_MS * 0.5f;
        f.drawFit(batch, Fonts.Role.UI_BOLD, 34f, warm ? Lang.t("WARM-UP", "练习") : Lang.t("KEEP TAPPING", "继续点"), px,
            900f, Align.left | Fonts.VCENTER, maxW, 0.6f, Theme.INK1, a);
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 20f, Lang.t("TAP WHEN A KEY REACHES THE LINE", "看到按键到线就点"), px,
            850f, Align.left | Fonts.VCENTER, maxW, 0.6f, Theme.INK2, a);
        drawProgress(px, 780f, a);
    }

    private void drawProgress(float x0, float y, float a) {
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
        game.fonts().draw(batch, Fonts.Role.UI_LABEL, 16f, countText, x0, y - 2f * DOTS_GAP - 4f,
            Align.left | Fonts.VCENTER, Theme.INK3, a);
    }

    private void drawResult(float a) {
        Fonts f = game.fonts();
        float cx = Ui.CX, cy = Ui.CY;
        float in = Anim.POW3_OUT.apply(Math.min(1f, stateT / 0.32f));
        float lift = 24f * (1f - in), ra = a * in;
        if (resultKind == RES_OK) {
            f.draw(batch, Fonts.Role.NUMBER, 96f, resultValue, cx - 10f, cy + 330f - lift, Align.right | Fonts.VCENTER,
                Theme.INK1, ra);
            f.draw(batch, Fonts.Role.UI_BOLD, 36f, PX, cx + 4f, cy + 312f - lift, Align.left | Fonts.VCENTER,
                Theme.INK2, ra);
            if (applied) {
                f.draw(batch, Fonts.Role.UI_LABEL, 18f, Lang.t("APPLIED", "已应用"), cx + 4f, cy + 362f - lift,
                    Align.left | Fonts.VCENTER, Theme.RESONANCE, ra);
            }
            f.draw(batch, Fonts.Role.UI, 26f, resultLine, cx, cy + 250f - lift, Align.center | Fonts.VCENTER,
                Theme.INK1, ra);
            f.draw(batch, Fonts.Role.UI_REGULAR, 20f, resultSub, cx, cy + 210f - lift, Align.center | Fonts.VCENTER,
                Theme.INK3, ra);
            drawDiagram(resultPx, cx, cy + RESULT_DIAGRAM, ra);
        } else {
            f.draw(batch, Fonts.Role.UI_BOLD, 52f, resultValue, cx, cy + 200f - lift, Align.center | Fonts.VCENTER,
                resultKind == RES_INCONSISTENT ? Theme.LOST : Theme.GOLD, ra);
            f.draw(batch, Fonts.Role.UI, 26f, resultLine, cx, cy + 110f - lift, Align.center | Fonts.VCENTER,
                Theme.INK1, ra);
            f.draw(batch, Fonts.Role.UI_REGULAR, 20f, resultSub, cx, cy + 62f - lift, Align.center | Fonts.VCENTER,
                Theme.INK3, ra);
        }
        strip.draw(batch, a);
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

    // ------------------------------------------------------------------ input
    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        if (state != RUN) return false;
        tap(x, y, eventNanos());
        return true;
    }

    @Override
    protected boolean contentKeyDown(int keycode) {
        if (state == RUN) {
            tap(Ui.CX, LINE_Y, eventNanos());
            return true;
        }
        if (state == INTRO && (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER)) {
            startRun();
            return true;
        }
        return false;
    }
}
