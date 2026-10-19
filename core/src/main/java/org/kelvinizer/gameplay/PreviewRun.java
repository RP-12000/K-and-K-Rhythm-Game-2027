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
 * 「游戏界面」画面里实时预览的那一局：用真正的游戏引擎、判定线、音符、特效和 HUD 自己打一张随机生成的谱，
 * 不接受点击。按键随机落在上下两条线上，双押之后隔 1–6 次下落才再出双押，每 5–12 连击故意漏掉一个；
 * 一局 40 次下落，打完马上换一张新谱。
 *
 * <p>The run behind the live preview of GAMEPLAY VISUALS (Kelvin 2026-10-18 19:52: the preview plays itself and takes no
 * taps; random notes; the combo breaks every 5–12; after a double, 1–6 drops before the next one; the zone hint of the
 * real ±0.4 zone; COMBO as in a game). It owns a {@link GameEngine} whose clock runs on a simulated time source
 * (frame steps, at most 0.1 s each) and plays a random chart of {@link #DROPS} drops, one a beat at 120 BPM: TAPs on a
 * random line and lane, a double on one line with its lanes at least two apart. Each round plans its own input: a
 * press per note at its judgement ± up to 50 ms (one in six at 85–120 ms early or late, an ECHO), released 70 ms
 * later, and a single left alone whenever the combo reaches this round's target of 5–12 (a LOST). The presses go to
 * the engine as touches at the lane centre on the line, before each update, so the engine judges them as a player's.
 * The screen's renderers draw the run with the active diver's settings, read every frame: {@link FieldRenderer}
 * (lines, the zone hint over {@link Field#zoneHalf}), {@link NoteRenderer} (note size, MULTI-PRESS ASSIST, approach
 * glow), {@link FeedbackLayer}, the shared {@link HitFx} and a {@link GameplayHud} that keeps only the score and the
 * accuracy of its corners ({@link GameplayHud#setScoreOnly}). {@link #draw} works in world units of a full game
 * screen; the caller maps it into the panel with the batch transform and clips it. When a round ends a new chart is
 * generated (allocation per round, none per frame).
 */
public final class PreviewRun {
    /** Drops per round, one a beat from {@link #FIRST_BEAT} at 120 BPM (a round is about 21 s). */
    static final int DROPS = 40;
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
    private final float[] inAt = new float[DROPS * 4];
    private final boolean[] inDown = new boolean[DROPS * 4];
    private final int[] inPointer = new int[DROPS * 4];
    private final int[] inLane = new int[DROPS * 4];
    private final Line[] inLine = new Line[DROPS * 4];
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
     * Writes this round's chart and plans its presses: one drop a beat, a double once 1–6 drops passed since the last
     * one (unless it would carry the combo past the target), and a single missed once the combo reached the target.
     */
    private Chart randomChart() {
        inCount = 0;
        inNext = 0;
        json.setLength(0);
        json.append("{\"formatVersion\":2,\"title\":\"preview\",\"bpm\":120,\"notes\":[");
        int combo = 0, target = nextTarget(), since = 0, wait = nextWait();
        for (int i = 0; i < DROPS; i++) {
            int beat = FIRST_BEAT + i;
            float at = beat * MS_PER_BEAT;
            Line line = random.nextBoolean() ? Line.UPPER : Line.LOWER;
            int a = random.nextInt(LANES);
            if (combo >= target) {
                note(line, a, beat);
                combo = 0;
                target = nextTarget();
                since++;
                continue;
            }
            note(line, a, beat);
            press(at, 0, a, line);
            if (since >= wait && combo + 2 <= target) {
                int b;
                do {
                    b = random.nextInt(LANES);
                } while (Math.abs(b - a) < 2);
                note(line, b, beat);
                press(at, 1, b, line);
                combo += 2;
                since = 0;
                wait = nextWait();
            } else {
                combo++;
                since++;
            }
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

    private int nextTarget() {
        return 5 + random.nextInt(8);
    }

    private int nextWait() {
        return 1 + random.nextInt(6);
    }

    private void note(Line line, int lane, int beat) {
        json.append("{\"type\":\"tap\",\"line\":\"").append(line == Line.UPPER ? "upper" : "lower")
            .append("\",\"lane\":").append(lane < 4 ? lane - 4 : lane - 3).append(",\"judgement\":").append(beat)
            .append("},");
    }

    /** Plans a press of {@code lane} on {@code line} for a note at {@code at} ms: one in six an ECHO. */
    private void press(float at, int pointer, int lane, Line line) {
        float dt = random.nextInt(6) == 0 ? (random.nextBoolean() ? 1f : -1f) * (85f + 35f * random.nextFloat())
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
