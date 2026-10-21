package org.kelvinizer.gameplay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.Batch;
import org.kelvinizer.Main;
import org.kelvinizer.audio.SongClock;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.ChartMapper;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 「游戏界面」画面里实时预览的那一局：用真正的游戏引擎、判定线、音符、特效和 HUD 自己打一张生成的谱，不接受点击。
 * 每局 12 组按键（单、单、双，循环四次），每组随机落在上下两条线的随机轨道上；第 5 组打成回响、第 9 组整组迷失、其余都是共鸣，
 * 所以 FC/AR 指示每局都按 淡蓝 → 紫 → 白 换一遍；打完马上换一张新谱。
 *
 * <p>The run behind the live preview of GAMEPLAY VISUALS (Kelvin 2026-10-18 19:52: the preview plays itself and takes
 * no taps; the zone hint of the real ±0.4 zone; COMBO as in a game; 2026-10-20 03:20: 「每12组键一个循环，每三组单单双循环，
 * 每次都是第5组爆echo，第9组爆miss」, so the FC / AR INDICATOR changes every four groups instead of once a 40-drop round).
 * It owns a {@link GameEngine} whose clock runs on a simulated time source (frame steps, at most 0.1 s each) and plays
 * a chart of {@link #GROUPS} groups, one a beat at 120 BPM: single, single, double, four times; each group on a random
 * line, a single in a random lane, a double's lanes at least two apart. Each round plans its own input: a press per
 * note at its judgement ± up to 50 ms (a RESONANCE), released 70 ms later; group {@link #ECHO_GROUP} is pressed 85–120
 * ms early or late (an ECHO) and group {@link #LOST_GROUP}, a double, is left alone (two LOSTs). The score is light
 * blue through group 4, purple from the ECHO and white from the LOSTs, and the next round starts light blue again. The
 * presses go to the engine as touches at the lane centre on the line, before each update, so the engine judges them as
 * a player's. The screen's renderers draw the run with the active diver's settings, read every frame:
 * {@link FieldRenderer} (lines, the zone hint over {@link Field#zoneHalf}), {@link NoteRenderer} (note size,
 * MULTI-PRESS ASSIST, approach glow), {@link FeedbackLayer}, the shared {@link HitFx} and a {@link GameplayHud} that
 * keeps only the score of its corners ({@link GameplayHud#setScoreOnly}). {@link #draw} works in world units
 * of a full game screen; the caller maps it into the panel with the batch transform and clips it. When a round ends a
 * new chart is generated (allocation per round, none per frame).
 */
public final class PreviewRun {
    /** Groups per round, one a beat from {@link #FIRST_BEAT} at 120 BPM (a round is about 7 s). */
    static final int GROUPS = 12;
    /** The group (1-based) pressed as an ECHO and the double left for two LOSTs, every round. */
    static final int ECHO_GROUP = 5, LOST_GROUP = 9;
    private static final int FIRST_BEAT = 2;
    private static final float MS_PER_BEAT = 500f;
    private static final int LANES = 8;
    /** Press-to-release time of a planned tap (ms). */
    private static final float PRESS_MS = 70f;
    /** The wait after a round's last note before the next round (ms). */
    private static final float ROUND_GAP_MS = 700f;
    /** The HUD and the lines skip their entrance: the run is always past it. */
    private static final float SETTLED = 10f;

    private final Main game;
    private final GameEngine engine = new GameEngine();
    private final GameEvent ev = new GameEvent();
    private final FieldRenderer field;
    private final NoteRenderer notes;
    private final FeedbackLayer feedback;
    private final GameplayHud hud;
    private final DeferredBatch text = new DeferredBatch();
    private final Settings defaults = new Settings();
    private final Random random = new Random();
    private final StringBuilder json = new StringBuilder(4096);

    /** Simulated time (ns) and its value when the round started. */
    private long nanos, startNanos;
    private float leadMs, runTime;
    private boolean ready;

    /** Planned presses (song ms), sorted by time: down or up, pointer, lane index, line. */
    private final float[] inAt = new float[GROUPS * 4];
    private final boolean[] inDown = new boolean[GROUPS * 4];
    private final int[] inPointer = new int[GROUPS * 4];
    private final int[] inLane = new int[GROUPS * 4];
    private final Line[] inLine = new Line[GROUPS * 4];
    private int inCount, inNext;

    /** Creates the run (no chart until {@link #restart()}). */
    public PreviewRun(Main game) {
        this.game = game;
        field = new FieldRenderer(game.draw());
        notes = new NoteRenderer(game.draw());
        feedback = new FeedbackLayer(game.draw());
        hud = new GameplayHud(game.fonts(), game.draw(), game.glitch(), feedback);
        hud.setScoreOnly(true);
        engine.clock().setTimeSource(new SongClock.TimeSource() {
            @Override
            public long nanoTime() {
                return nanos;
            }
        });
        engine.setOffsets(0f, 0f);
        engine.setFinishDelayMs(ROUND_GAP_MS);
    }

    private Settings settings() {
        Profile p = game.profiles().active();
        return p != null && p.settings != null ? p.settings : defaults;
    }

    /** Starts a new round with a new random chart. */
    public void restart() {
        Chart chart = randomChart();
        if (chart == null) return;
        Settings st = settings();
        Field.fit(Ui.worldW(), Ui.worldH());
        engine.load(chart, false);
        field.begin(chart);
        notes.setStyle(st.noteSizeScale, game.hitFx().quality() == HitFx.QUALITY_HIGH);
        notes.setHitPoint(st.hitPointPx);
        notes.setSyncHint(st.syncHint);
        notes.begin(chart);
        // as GameplayScreen: the clock starts before the first note can slide in from the screen edge
        float beat = notes.entry().firstDrawBeat();
        if (beat != Float.POSITIVE_INFINITY) {
            double ms = chart.beatToMs(beat);
            if (ms <= engine.leadInMs()) engine.setLeadInMs((float) Math.floor(ms) - GameEngine.START_EPS_MS);
        }
        feedback.clear();
        hud.begin("", null, "", false, st);
        leadMs = engine.leadInMs();
        startNanos = nanos;
        engine.start();
        ready = true;
    }

    /**
     * Writes this round's chart and plans its presses: single, single, double, four times, one group a beat; group
     * {@link #ECHO_GROUP} an ECHO, group {@link #LOST_GROUP} not pressed, the rest RESONANCE.
     */
    private Chart randomChart() {
        inCount = 0;
        inNext = 0;
        json.setLength(0);
        json.append("{\"formatVersion\":4,\"title\":\"preview\",\"bpm\":120,\"notes\":[");
        for (int g = 1; g <= GROUPS; g++) {
            int beat = FIRST_BEAT + g - 1;
            float at = beat * MS_PER_BEAT;
            Line line = random.nextBoolean() ? Line.UPPER : Line.LOWER;
            int a = random.nextInt(LANES);
            note(line, a, beat);
            if (g != LOST_GROUP) press(at, 0, a, line, g == ECHO_GROUP);
            if (g % 3 != 0) continue;
            int b;
            do {
                b = random.nextInt(LANES);
            } while (Math.abs(b - a) < 2);
            note(line, b, beat);
            if (g != LOST_GROUP) press(at, 1, b, line, false);
        }
        json.setLength(json.length() - 1);
        json.append("]}");
        sortPresses();
        try {
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            return ChartMapper.read(new ByteArrayInputStream(bytes), "preview");
        } catch (IOException | RuntimeException e) {
            if (Gdx.app != null) Gdx.app.error("PreviewRun", "cannot build the preview chart", e);
            return null;
        }
    }

    private void note(Line line, int lane, int beat) {
        json.append("{\"type\":\"tap\",\"line\":\"").append(line == Line.UPPER ? "upper" : "lower")
            .append("\",\"lane\":").append(lane < 4 ? lane - 4 : lane - 3).append(",\"judgement\":").append(beat)
            .append("},");
    }

    /** Plans a press of {@code lane} on {@code line} for a note at {@code at} ms: an ECHO or a RESONANCE. */
    private void press(float at, int pointer, int lane, Line line, boolean echo) {
        float dt = echo ? (random.nextBoolean() ? 1f : -1f) * (85f + 35f * random.nextFloat())
            : (random.nextFloat() * 2f - 1f) * 50f;
        input(at + dt, true, pointer, lane, line);
        input(at + dt + PRESS_MS, false, pointer, lane, line);
    }

    private void input(float at, boolean down, int pointer, int lane, Line line) {
        int k = inCount++;
        inAt[k] = at;
        inDown[k] = down;
        inPointer[k] = pointer;
        inLane[k] = lane;
        inLine[k] = line;
    }

    /** Insertion sort by time (a double's presses and releases can interleave). */
    private void sortPresses() {
        for (int i = 1; i < inCount; i++) {
            float at = inAt[i];
            boolean down = inDown[i];
            int p = inPointer[i], lane = inLane[i];
            Line line = inLine[i];
            int j = i - 1;
            while (j >= 0 && inAt[j] > at) {
                inAt[j + 1] = inAt[j];
                inDown[j + 1] = inDown[j];
                inPointer[j + 1] = inPointer[j];
                inLane[j + 1] = inLane[j];
                inLine[j + 1] = inLine[j];
                j--;
            }
            inAt[j + 1] = at;
            inDown[j + 1] = down;
            inPointer[j + 1] = p;
            inLane[j + 1] = lane;
            inLine[j + 1] = line;
        }
    }

    // ------------------------------------------------------------------ frame
    /** Advances the run by {@code dt} seconds (at most 0.1): planned presses, the engine, effects and HUD. */
    public void update(float dt) {
        if (!ready) return;
        float step = Math.max(0f, Math.min(0.1f, dt));
        nanos += (long) (step * 1e9);
        runTime += step;
        if (Field.fit(Ui.worldW(), Ui.worldH())) engine.refreshGeometry();
        float songMs = leadMs + (nanos - startNanos) / 1e6f;
        LaneLayout lay = engine.layout();
        while (inNext < inCount && inAt[inNext] <= songMs) {
            int k = inNext++;
            long when = startNanos + (long) ((inAt[k] - leadMs) * 1e6);
            int p = inPointer[k];
            float x = lay.centre(inLane[k]), y = engine.lineY(inLine[k]);
            if (inDown[k]) engine.touchDown(p, x, y, when);
            else engine.touchUp(p, x, y, when);
        }
        engine.update();
        consumeEvents();
        game.hitFx().update(step);
        field.update(step, engine);
        hud.update(step, engine, runTime);
        if (engine.isFinished()) restart();
    }

    /** The screen's event mapping without sounds, holds, mines or TIDEs (the chart has only TAPs). */
    private void consumeEvents() {
        HitFx fx = game.hitFx();
        boolean high = fx.quality() == HitFx.QUALITY_HIGH;
        Settings st = settings();
        while (engine.pollEvent(ev)) {
            Line line = ev.line;
            if (line == null) continue;
            float ly = engine.lineY(line);
            boolean sky = line == Line.UPPER;
            if (ev.type == GameEvent.HIT) {
                fx.tap(ev.x, ly, ev.judgement, 1f, sky);
                if (high) field.ripple(line, ev.x, 4f, runTime);
                else field.bead(line, ev.x, runTime);
                if (st.showsEarlyLate(ev.judgement, ev.dtMs)) feedback.chevron(ev.x, line, ly, ev.dtMs > 0f, runTime);
                feedback.contact(ev.touchX, ly, true, runTime);
                LaneLayout lay = engine.layout();
                if (ev.lane >= 0 && ev.lane < lay.count() && lay.active(ev.lane)) {
                    feedback.brackets(lay.left(ev.lane), lay.right(ev.lane), ly, runTime);
                }
                hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
            } else if (ev.type == GameEvent.LOST) {
                fx.lost(ev.x, sky ? ly - 120f : ly + 120f, 1f);
                field.flash(line, ev.lane, false, runTime);
                hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
            }
        }
    }

    /**
     * Draws the run in world units of a full game screen, in GameplayScreen's pass order (the caller set the batch's
     * transform and scissor; the batch is drawing).
     */
    public void draw(Batch b) {
        if (!ready) return;
        Settings st = settings();
        HitFx fx = game.hitFx();
        field.clearApproach();
        notes.setStyle(st.noteSizeScale, fx.quality() == HitFx.QUALITY_HIGH);
        notes.setHitPoint(st.hitPointPx);
        notes.setSyncHint(st.syncHint);
        notes.prepare(engine, field, st.approachGlow);
        field.drawUnder(engine, st.zoneHint ? 1f : 0f, 1f);
        hud.drawUnder(b, SETTLED, runTime, true);
        text.start(b);
        hud.setCentreCover(0f);
        hud.drawUnderText(text, engine, runTime, true);
        feedback.drawText(text, game.fonts(), runTime);
        text.replay();
        field.drawLines(engine, runTime, SETTLED);
        notes.drawBands(engine, runTime);
        notes.draw(engine, runTime);
        int src = b.getBlendSrcFunc();
        int dst = b.getBlendDstFunc();
        int srcA = b.getBlendSrcFuncAlpha();
        int dstA = b.getBlendDstFuncAlpha();
        b.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        field.drawAdditive(engine, runTime, engine.scores().combo(), beatPulse());
        notes.drawGlows();
        feedback.drawAdditive(runTime);
        fx.draw(b);
        b.setBlendFunctionSeparate(src, dst, srcA, dstA);
        text.start(b);
        hud.drawCornerText(text, SETTLED);
        text.replay();
    }

    /** Line-glow beat pulse 1 → 0 over 180 ms after each beat (as the screen's). */
    private float beatPulse() {
        Chart chart = engine.chart();
        float beat = engine.visualBeat();
        if (beat < 0f || chart == null) return 0f;
        float ms = (float) chart.msBetween(Math.floor(beat), beat);
        return ms < 180f ? 1f - ms / 180f : 0f;
    }
}
