package org.kelvinizer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;
import org.kelvinizer.Main;
import org.kelvinizer.audio.AudioService;
import org.kelvinizer.audio.ClickTrack;
import org.kelvinizer.audio.SongClock;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.screens.shell.ShellFormat;
import org.kelvinizer.screens.shell.TapStats;
import org.kelvinizer.screens.shell.TapStrip;
import org.kelvinizer.screens.shell.WidgetSet;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Haptics;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Segmented;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.Arrays;

/**
 * 音频延迟校准（音押）的内容：跟着 120 BPM 的节拍点击，取误差中位数作为当前音频输出的延迟，并可在天空线和地面线上试打验证。
 * 设置里的延迟校准用整个屏幕；首次设置第 3 步把它放进卡片里（快速模式，没有验证，应用后进入目押校准）。
 *
 * <p>The audio offset calibration (DESIGN §9.4 [P0]) as a panel: {@link CalibrationScreen} lays it out over the whole
 * screen ({@link #layoutScreen()}), the first run's step 3 inside its card ({@link #layoutCard}, Kelvin 2026-10-20
 * 22:30: 「校准那里直接把延迟校准的设置界面搬过来，放在现在那个框里」). The owner passes its {@link WidgetSet} (the
 * panel adds its buttons there once) and forwards its lifecycle, frame and input; the panel reports runs and
 * applied offsets through its {@link Host}.
 *
 * <p><b>AUDIO OFFSET</b>: the synthesised 120 BPM click track ({@link ClickTrack}: 4 lead-in
 * clicks plus 24 measured, or 12 in quick mode) plays through {@link AudioService#playFile} on the
 * same {@code Music} path and {@link SongClock} as songs. The lead-in shows a visual 4-3-2-1;
 * during the measured beats there is <i>no</i> visual beat cue. Every touch down (and key press)
 * anywhere is a tap, timestamped with {@code clock.timeAtNanos(Gdx.input.getCurrentEventTime())}
 * and matched to the nearest measured beat (first tap per beat; more than ±250 ms away is
 * dropped). A ripple marks each tap, a dot drops onto the −300..+300 ms scatter strip and a caret
 * follows the running median; the progress dots fill per valid tap. The result is
 * {@code offset = round(median(tap − beat))} with spread = MAD: MAD &gt; 35 ms reads
 * {@code INCONSISTENT · TRY AGAIN}; fewer than 16 valid taps (8 in quick mode) reads
 * {@code NOT ENOUGH TAPS}; otherwise {@code +42 ms · YOUR TAPS LAND 42 ms AFTER THE CLICK} with
 * APPLY, RETRY and VERIFY (APPLY was APPLY TO &lt;active output&gt; before 22:37: 「应用到扬声器改为应用」).
 * Convention: {@code judgeMs = clock.timeAtNanos(event) − audioOffsetMs}, so APPLY stores the median directly.
 * The lines USE THE SPEAKER OR HEADPHONES YOU PLAY WITH, THE SCREEN STAYS STILL… and LISTEN · THE SCREEN STAYS
 * STILL… are gone (Kelvin 2026-10-20 22:37); the foot line only reports an unavailable click track or an
 * interrupted run.
 *
 * <p><b>VERIFY</b> [P1] (whole screen only) loops 16 gameplay-style notes alternating between the ground (y 120)
 * and sky (y 960) lines on the looping click track, judged with the candidate offset (tap the lower half for ground,
 * the upper half for sky), with live judgements, the average error and ±5 ms nudges.
 *
 * <p>The first run's panel ({@code firstRun}) is in quick mode, has no VERIFY, and APPLY tells the host at once
 * (the first run moves on to the VISUAL HIT POINT). Every text follows the interface language ({@link Lang}).
 * Menu music is silenced while the panel is entered. BACK aborts a run (back to the intro) or a verify pass (back to
 * the result) and is otherwise the owner's. When the app is paused (Main pauses the click clock), a measuring run is
 * abandoned because the gap breaks its beat grid: with enough valid taps the result is computed from them, otherwise
 * the intro returns with {@code RUN INTERRUPTED}; a verify pass returns to the result. Nothing is allocated per frame.
 */
final class CalibrationPanel {
    /** What the panel tells the screen around it. */
    interface Host {
        /** A run started or ended: every touch is a tap during a run, so the owner hides what else it shows. */
        void runChanged(boolean running);

        /** An offset (or a hit point) was applied. */
        void applied();
    }

    private static final int INTRO = 0, LEADIN = 1, MEASURE = 2, RESULT = 3, VERIFY = 4;
    private static final int RES_OK = 0, RES_INCONSISTENT = 1, RES_TOO_FEW = 2;
    private static final float GROUND_Y = 120f, SKY_Y = 960f;
    private static final float GROUND_SPAWN = 1092f, SKY_SPAWN = -12f;
    private static final float VERIFY_TRAVEL_MS = 1100f;
    private static final int VERIFY_NOTES = 16;
    private static final float[] VERIFY_LANES = {660f, 860f, 1060f, 1260f};
    private static final int RIPPLES = 10;
    private static final float RIPPLE_LIFE = 0.5f;
    /** Height of a card that fits the panel at full size; a shorter card squeezes the gaps (and the big numbers). */
    static final float CARD_H = 700f;
    private static final String[] DIGITS = {"1", "2", "3", "4"};

    private final Main game;
    private final Host host;
    private final boolean firstRun;
    private final Segmented output;
    private final Button start, apply, retry, verify;
    private final Button vMinus, vPlus, vApply, vDone;
    private final TapStats stats = new TapStats(ClickTrack.MEASURED);
    private final TapStrip strip;
    private final boolean[] beatTaken = new boolean[ClickTrack.MEASURED];
    private final GlitchState resultGlitch, wordGlitch;
    private final float[] rippleX = new float[RIPPLES], rippleY = new float[RIPPLES], rippleT = new float[RIPPLES];
    private final boolean[] noteDone = new boolean[VERIFY_NOTES];
    private final Color tmp = new Color();

    private final StringBuilder currentText = new StringBuilder(16);
    private final StringBuilder currentLabel = new StringBuilder(32);
    private final StringBuilder resultValue = new StringBuilder(16);
    private final StringBuilder resultLine = new StringBuilder(64);
    private final StringBuilder resultSub = new StringBuilder(64);
    private final StringBuilder introLine = new StringBuilder(80);
    private final StringBuilder countText = new StringBuilder(24);
    private final StringBuilder candidateText = new StringBuilder(16);
    private final StringBuilder candidateLabel = new StringBuilder(32);
    private final StringBuilder avgText = new StringBuilder(64);
    private final StringBuilder wordMs = new StringBuilder(16);

    private FileHandle clickFile;
    private boolean clickTried;
    private SongClock track;
    private boolean noAudio;
    private int state = INTRO;
    private float stateT, clock;
    private int resultKind, resultOffset, resultCount, candidate;
    private float resultMad;
    private boolean applied, appliedResult;
    private boolean interrupted;
    private boolean visible = true;
    private int rippleNext;
    private Judgement word;
    private float wordT = 10f;
    private int verifyHits;
    private float verifySum;
    private double lastVerifyMs;
    private int countFor = -1;

    // laid out by layoutScreen() or layoutCard(): centre x, widest text, then the rows of each state
    private float cx, textW, k = 1f;
    private float titleY, introY, presetY, currentY, startY, footY;
    private float digitY, digitSize = 200f, tapY, dotsY, stripY;
    private float valueY, valueSize = 110f, lineY, subY, buttonsY;
    private float noteX, noteY;

    /**
     * Creates the panel and adds its buttons to {@code widgets} (call once). {@code firstRun}: quick mode, no VERIFY,
     * the first run's card.
     */
    CalibrationPanel(final Main game, WidgetSet widgets, Host host, boolean firstRun) {
        this.game = game;
        this.host = host;
        this.firstRun = firstRun;
        Draw d = game.draw();
        Fonts f = game.fonts();
        strip = new TapStrip(game, ClickTrack.MEASURED);
        resultGlitch = game.glitch().obtainState();
        wordGlitch = game.glitch().obtainState();
        output = new Segmented(d, f, Lang.list("SPEAKER", "扬声器", "WIRED", "有线耳机", "BT", "蓝牙"));
        output.setLabelCap(20f);
        output.setListener(new Segmented.Listener() {
            @Override
            public void selected(Segmented control, int index) {
                game.profiles().device().activeOutput = index;
                game.profiles().markDeviceDirty();
                refreshLabels();
            }
        });
        start = widgets.add(new Button(d, f, Button.Variant.PRIMARY, Lang.of("START", "开始")));
        start.setOnTap(new Runnable() {
            @Override
            public void run() {
                startRun();
            }
        });
        CharSequence applyLabel = Lang.of("APPLY", "应用");
        apply = widgets.add(new Button(d, f, Button.Variant.PRIMARY, applyLabel));
        apply.setOnTap(new Runnable() {
            @Override
            public void run() {
                applyOffset(resultOffset);
            }
        });
        retry = widgets.add(new Button(d, f, Button.Variant.SECONDARY, Lang.of("RETRY", "重试")));
        retry.setOnTap(new Runnable() {
            @Override
            public void run() {
                startRun();
            }
        });
        verify = widgets.add(new Button(d, f, Button.Variant.SECONDARY, Lang.of("VERIFY", "验证")));
        verify.setOnTap(new Runnable() {
            @Override
            public void run() {
                startVerify();
            }
        });
        vMinus = widgets.add(new Button(d, f, Button.Variant.SECONDARY, "–5"));
        vMinus.setOnTap(new Runnable() {
            @Override
            public void run() {
                nudgeCandidate(-5);
            }
        });
        vPlus = widgets.add(new Button(d, f, Button.Variant.SECONDARY, "+5"));
        vPlus.setOnTap(new Runnable() {
            @Override
            public void run() {
                nudgeCandidate(5);
            }
        });
        vApply = widgets.add(new Button(d, f, Button.Variant.PRIMARY, applyLabel));
        vApply.setOnTap(new Runnable() {
            @Override
            public void run() {
                applyOffset(candidate);
                endVerify();
            }
        });
        vDone = widgets.add(new Button(d, f, Button.Variant.SECONDARY, Lang.of("DONE", "完成")));
        vDone.setOnTap(new Runnable() {
            @Override
            public void run() {
                endVerify();
            }
        });
    }

    // ------------------------------------------------------------------ helpers
    private DeviceSettings device() {
        return game.profiles().device();
    }

    private int measured() {
        return firstRun ? ClickTrack.QUICK_MEASURED : ClickTrack.MEASURED;
    }

    private int required() {
        return firstRun ? CalibrationScreen.QUICK_REQUIRED : CalibrationScreen.REQUIRED;
    }

    /** True while the click track runs a measuring pass (every touch is a tap). */
    boolean running() {
        return state == LEADIN || state == MEASURE;
    }

    private FileHandle clickFile() {
        if (!clickTried) {
            clickTried = true;
            clickFile = ClickTrack.ensure();
        }
        return clickFile;
    }

    private static long eventNanos() {
        long t = Gdx.input != null ? Gdx.input.getCurrentEventTime() : 0L;
        return t != 0L ? t : TimeUtils.nanoTime();
    }

    private boolean playClicks(boolean loop) {
        FileHandle f = clickFile();
        if (f == null) {
            game.toasts().show(Lang.t("CLICK TRACK UNAVAILABLE", "节拍音轨不可用"), Theme.LOST);
            return false;
        }
        track = game.audio().playFile(f, loop);
        noAudio = track == null || !track.hasMusic();
        return track != null;
    }

    private void stopClicks() {
        if (track != null) game.audio().stopFile();
        track = null;
    }

    private static void appendSigned(StringBuilder sb, int v) {
        if (v > 0) sb.append('+');
        Fonts.appendInt(sb, v);
    }

    /** Re-derives the output-dependent labels and the current offsets (in the interface language). */
    private void refreshLabels() {
        DeviceSettings d = device();
        String out = ShellFormat.outputName(d.activeOutput);
        boolean zh = Lang.zh();
        currentLabel.setLength(0);
        currentLabel.append(zh ? "当前延迟 · " : "CURRENT OFFSET · ").append(out);
        currentText.setLength(0);
        appendSigned(currentText, d.audioOffsetMs());
        candidateLabel.setLength(0);
        candidateLabel.append(zh ? "候选值 · " : "CANDIDATE · ").append(out);
        introLine.setLength(0);
        if (zh) introLine.append("先响 4 下预备拍，再响 ").append(measured()).append(" 下 · 每一下都点屏幕任意位置");
        else introLine.append("4 COUNT-IN CLICKS, THEN ").append(measured()).append(" MORE · TAP ANYWHERE ON EACH ONE");
    }

    // ------------------------------------------------------------------ lifecycle
    /** Starts afresh at the intro and silences the menu music (the owner was shown, or the step entered). */
    void enter() {
        AudioService a = game.audio();
        a.stopSlot(AudioService.Slot.MENU, 0.3f);
        a.stopSlot(AudioService.Slot.PREVIEW, 0.2f);
        a.stopSlot(AudioService.Slot.RESULT, 0.3f);
        applied = false;
        appliedResult = false;
        interrupted = false;
        output.select(device().activeOutput, false);
        setState(INTRO);
        stats.clear();
        strip.clear();
        refreshLabels();
        clickFile();
    }

    /** Stops the clicks and saves (the owner was hidden, or the step left). */
    void leave() {
        stopClicks();
        game.hitFx().clear();
        output.cancel();
        game.profiles().save();
    }

    /** Frees the glitch states. */
    void dispose() {
        game.glitch().freeState(resultGlitch);
        game.glitch().freeState(wordGlitch);
    }

    /** BACK / ESC: aborts a run (to the intro) or a verify pass (to the result); false when it is the owner's. */
    boolean back() {
        if (running()) {
            stopClicks();
            setState(INTRO);
            return true;
        }
        if (state == VERIFY) {
            endVerify();
            return true;
        }
        return false;
    }

    /**
     * App backgrounded (a call, a notification, the screen locking). {@code Main.pause()} has
     * already paused the click clock and {@code AudioService.resume()} deliberately leaves it
     * paused, so a run cannot simply continue: the gap would break the beat grid. A measuring run
     * ends here (a result from the taps so far when there are enough, else back to the intro with
     * {@code RUN INTERRUPTED}); a verify pass returns to the result.
     */
    void pause() {
        if (running()) {
            if (state == MEASURE && stats.count() >= required()) {
                finishRun(false);
            } else {
                stopClicks();
                interrupted = true;
                setState(INTRO);
            }
        } else if (state == VERIFY) {
            endVerify();
        }
    }

    /** Shows or hides the panel and its buttons (hidden: the owner shows something else). */
    void setVisible(boolean on) {
        visible = on;
        updateWidgets();
    }

    // ------------------------------------------------------------------ state
    private void setState(int s) {
        state = s;
        stateT = 0f;
        updateWidgets();
    }

    private void updateWidgets() {
        boolean run = running();
        start.setVisible(visible && state == INTRO);
        start.setEnabled(clickFile != null || !clickTried);
        boolean res = visible && state == RESULT;
        boolean ok = res && resultKind == RES_OK;
        apply.setVisible(ok);
        apply.setEnabled(!appliedResult);
        verify.setVisible(ok && !firstRun);
        retry.setVisible(res);
        retry.setVariant(ok ? Button.Variant.SECONDARY : Button.Variant.PRIMARY);
        boolean ver = visible && state == VERIFY;
        vMinus.setVisible(ver);
        vPlus.setVisible(ver);
        vApply.setVisible(ver);
        vDone.setVisible(ver);
        placeResultButtons();
        if (visible) host.runChanged(run);
    }

    // ------------------------------------------------------------------ AUDIO run
    private void startRun() {
        stats.clear();
        strip.clear();
        Arrays.fill(beatTaken, false);
        countFor = -1;
        appliedResult = false;
        interrupted = false;
        if (!playClicks(false)) return;
        refreshLabels();
        setState(LEADIN);
        Haptics.light();
    }

    private void tap(float x, float y, long nanos) {
        ripple(x, y);
        if (track == null) return;
        double t = track.timeAtNanos(nanos);
        int beat = ClickTrack.nearestBeat(t);
        int i = beat - ClickTrack.LEAD_IN;
        if (i < 0 || i >= measured() || beatTaken[i]) return;
        float delta = (float) (t - ClickTrack.beatTimeMs(beat));
        if (Math.abs(delta) > CalibrationScreen.MAX_TAP_ERROR_MS) return;
        beatTaken[i] = true;
        stats.add(delta);
        strip.add(delta);
        strip.setMedian(stats.median());
    }

    private void updateRun() {
        if (track == null) return;
        double t = track.nowMs();
        // The 4-3-2-1 is drawn on the heard timeline (raw clock minus the output's offset), so the
        // switch to KEEP TAPPING uses the same time: every digit shows for a full beat whatever
        // the current offset. Taps are judged on the raw clock either way.
        double heard = t - device().audioOffsetMs();
        if (state == LEADIN && heard >= ClickTrack.beatTimeMs(ClickTrack.LEAD_IN) - ClickTrack.BEAT_MS * 0.5f) {
            state = MEASURE;
            stateT = 0f;
        }
        if (t > ClickTrack.beatTimeMs(ClickTrack.LEAD_IN + measured() - 1) + 450f) finishRun(true);
    }

    /** Ends the run and shows the result; {@code feedback} adds the haptic (not while backgrounded). */
    private void finishRun(boolean feedback) {
        stopClicks();
        resultCount = stats.count();
        resultMad = stats.mad();
        if (resultCount < required()) {
            resultKind = RES_TOO_FEW;
        } else {
            resultOffset = Math.max(DeviceSettings.AUDIO_OFFSET_MIN,
                Math.min(DeviceSettings.AUDIO_OFFSET_MAX, Math.round(stats.median())));
            resultKind = resultMad > CalibrationScreen.MAX_MAD_MS ? RES_INCONSISTENT : RES_OK;
        }
        buildResultText();
        resultGlitch.ambient(resultKind == RES_OK ? GlitchText.Level.G1 : GlitchText.Level.G0);
        resultGlitch.trigger(resultKind == RES_OK ? GlitchText.Level.G2 : GlitchText.Level.G2_RED, clock);
        setState(RESULT);
        if (feedback && resultKind == RES_OK) Haptics.medium();
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
                Fonts.appendInt(resultLine, required()).append(" 次");
            } else {
                Fonts.appendInt(resultLine, resultCount).append(" VALID TAPS · AT LEAST ");
                Fonts.appendInt(resultLine, required()).append(" NEEDED");
            }
            resultSub.append(zh ? "预备拍之后，每一下节拍声都点一下屏幕" : "TAP ANYWHERE ON EVERY CLICK AFTER THE COUNT-IN");
        } else if (resultKind == RES_INCONSISTENT) {
            resultValue.append(zh ? "不稳定 · 再试一次" : "INCONSISTENT · TRY AGAIN");
            resultLine.append(zh ? "点击偏差 ±" : "YOUR TAPS SPREAD ±");
            Fonts.appendInt(resultLine, mad).append(" ms");
            resultSub.append(zh ? "仔细听，每一下都正好点在节拍声上" : "LISTEN AND TAP EXACTLY ON EACH CLICK");
        } else {
            appendSigned(resultValue, resultOffset);
            int a = Math.abs(resultOffset);
            if (resultOffset == 0) {
                resultLine.append(zh ? "你的点击正好落在节拍声上" : "YOUR TAPS LAND RIGHT ON THE CLICK");
            } else if (zh) {
                resultLine.append("你的点击比节拍声").append(resultOffset > 0 ? "晚 " : "早 ");
                Fonts.appendInt(resultLine, a).append(" ms");
            } else {
                resultLine.append("YOUR TAPS LAND ");
                Fonts.appendInt(resultLine, a)
                    .append(resultOffset > 0 ? " ms AFTER THE CLICK" : " ms BEFORE THE CLICK");
            }
            resultSub.append(zh ? "偏差 ±" : "SPREAD ±");
            Fonts.appendInt(resultSub, mad).append(" ms · ");
            if (zh) {
                resultSub.append("有效 ");
                Fonts.appendInt(resultSub, resultCount).append(" / ");
                Fonts.appendInt(resultSub, measured()).append(" 次");
            } else {
                Fonts.appendInt(resultSub, resultCount).append(" OF ");
                Fonts.appendInt(resultSub, measured()).append(" TAPS");
            }
        }
    }

    private void applyOffset(int offset) {
        ProfileStore store = game.profiles();
        DeviceSettings d = store.device();
        d.setAudioOffsetMs(offset);
        store.markDeviceDirty();
        Profile p = store.active();
        if (p != null) {
            p.calibrated = true;
            p.setFlag(CalibrationScreen.FLAG_REMINDER_SHOWN);
            if (p.flags != null) p.flags.remove(FirstRunScreen.FLAG_CALIBRATION_REMINDER);
            store.markDirty();
        }
        store.save();
        applied = true;
        appliedResult = true;
        resultOffset = offset;
        StringBuilder sb = new StringBuilder(40);
        sb.append(Lang.t("CHART OFFSET · ", "谱面延时 · ")).append(ShellFormat.outputName(d.activeOutput)).append(' ');
        appendSigned(sb, d.audioOffsetMs());
        sb.append(" ms");
        game.toasts().show(sb.toString(), Theme.GROUND_SIG);
        Haptics.medium();
        refreshLabels();
        if (firstRun) stopClicks();
        updateWidgets();
        host.applied();
    }

    // ------------------------------------------------------------------ VERIFY
    private void startVerify() {
        candidate = resultOffset;
        if (!playClicks(true)) return;
        Arrays.fill(noteDone, false);
        lastVerifyMs = 0;
        verifyHits = 0;
        verifySum = 0f;
        word = null;
        wordT = 10f;
        game.hitFx().clear();
        refreshCandidate();
        setState(VERIFY);
    }

    private void endVerify() {
        stopClicks();
        game.hitFx().clear();
        if (candidate != resultOffset) {
            resultOffset = candidate;
            appliedResult = false;
            buildResultText();
        }
        setState(RESULT);
    }

    private void nudgeCandidate(int ms) {
        candidate = Math.max(DeviceSettings.AUDIO_OFFSET_MIN,
            Math.min(DeviceSettings.AUDIO_OFFSET_MAX, candidate + ms));
        refreshCandidate();
    }

    private void refreshCandidate() {
        candidateText.setLength(0);
        appendSigned(candidateText, candidate);
        refreshAverage();
    }

    private void refreshAverage() {
        avgText.setLength(0);
        boolean zh = Lang.zh();
        if (verifyHits == 0) {
            avgText.append(zh ? "地面音符点下半屏 · 天空音符点上半屏"
                : "TAP THE LOWER HALF FOR GROUND NOTES · THE UPPER HALF FOR SKY NOTES");
            return;
        }
        int avg = Math.round(verifySum / verifyHits);
        if (zh) {
            avgText.append(avg > 0 ? "平均晚 " : (avg < 0 ? "平均早 " : "平均 "));
            Fonts.appendInt(avgText, Math.abs(avg)).append(" ms · 击中 ");
            Fonts.appendInt(avgText, verifyHits).append(" 次");
            return;
        }
        avgText.append("AVERAGE ");
        Fonts.appendInt(avgText, Math.abs(avg));
        avgText.append(avg > 0 ? " ms LATE · " : (avg < 0 ? " ms EARLY · " : " ms · "));
        Fonts.appendInt(avgText, verifyHits).append(verifyHits == 1 ? " HIT" : " HITS");
    }

    private static float noteTime(int k) {
        return ClickTrack.measuredBeatTimeMs(k);
    }

    private static boolean isSky(int k) {
        return (k & 1) == 1;
    }

    private static float laneX(int k) {
        return VERIFY_LANES[(k >> 1) % VERIFY_LANES.length] + (isSky(k) ? 100f : 0f);
    }

    private void updateVerify(float delta) {
        if (track != null) {
            double t = track.nowMs();
            if (t + 2000.0 < lastVerifyMs) Arrays.fill(noteDone, false);
            lastVerifyMs = t;
            double judge = t - candidate;
            for (int i = 0; i < VERIFY_NOTES; i++) {
                if (noteDone[i] || judge - noteTime(i) <= Judgement.TIER2_MS) continue;
                noteDone[i] = true;
                showWord(Judgement.LOST, 0);
                game.hitFx().lost(laneX(i), isSky(i) ? SKY_Y - 120f : GROUND_Y + 120f, 1f);
            }
        }
        game.hitFx().update(delta);
    }

    private void verifyTap(float x, float y, long nanos) {
        if (track == null) return;
        boolean sky = y > Ui.CY;
        double judge = track.timeAtNanos(nanos) - candidate;
        int best = -1;
        double bestAbs = Double.MAX_VALUE;
        for (int i = 0; i < VERIFY_NOTES; i++) {
            if (noteDone[i] || isSky(i) != sky) continue;
            double a = Math.abs(judge - noteTime(i));
            if (a < bestAbs) {
                bestAbs = a;
                best = i;
            }
        }
        Judgement j = best >= 0 ? Judgement.ofDelta((float) bestAbs) : null;
        if (j == null) {
            ripple(x, y);
            return;
        }
        noteDone[best] = true;
        float d = (float) (judge - noteTime(best));
        showWord(j, Math.round(d));
        game.hitFx().tap(laneX(best), sky ? SKY_Y : GROUND_Y, j, 1f, sky);
        verifyHits++;
        verifySum += d;
        refreshAverage();
    }

    private void showWord(Judgement j, int ms) {
        word = j;
        wordT = 0f;
        wordMs.setLength(0);
        if (j != Judgement.LOST) appendSigned(wordMs, ms);
        wordGlitch.ambient(j == Judgement.RESONANCE ? GlitchText.Level.G1 : GlitchText.Level.G0);
        if (j == Judgement.LOST) wordGlitch.trigger(GlitchText.Level.G2_RED, clock);
    }

    // ------------------------------------------------------------------ layout
    /** Lays the panel out over the whole screen (the calibration screen; VERIFY uses the whole screen too). */
    void layoutScreen() {
        float sl = Ui.safeLeft(), sr = Ui.safeRight(), cy = Ui.CY;
        cx = Ui.CX;
        k = 1f;
        textW = sr - sl - 80f;
        // the title and its line moved down into the place of USE THE SPEAKER OR HEADPHONES (gone 2026-10-20 22:37)
        titleY = cy + 230f;
        introY = cy + 165f;
        presetY = cy + 112f;
        output.setBounds(cx - 330f, cy + 10f, 660f, 72f);
        currentY = cy - 36f;
        startY = cy - 210f;
        footY = cy - 300f;
        digitY = cy + 130f;
        digitSize = 200f;
        tapY = cy - 40f;
        // the progress dots and the strip stay at the world heights the screen always gave them
        dotsY = 372f;
        stripY = 200f;
        valueY = cy + 210f;
        valueSize = 110f;
        lineY = cy + 100f;
        subY = cy + 52f;
        buttonsY = cy - 60f;
        noteX = sr - 8f;
        noteY = Ui.safeBottom() + 30f;
        float bh = Button.standardHeight();
        start.setBounds(cx - 220f, startY - bh * 0.5f, 440f, bh);
        // verify panels
        float px = sl + 40f;
        vMinus.setBounds(px, 470f, 150f, bh);
        vPlus.setBounds(px + 170f, 470f, 150f, bh);
        float rw = Math.min(440f, sr - sl - 1500f + 440f);
        rw = Math.max(320f, rw);
        vApply.setBounds(sr - rw, 600f, rw, bh);
        vDone.setBounds(sr - rw, 600f - bh - 20f, rw, bh);
        strip.setBounds(cx, stripY, Math.min(1200f, sr - sl - 160f));
        placeResultButtons();
    }

    /**
     * Lays the panel out in a card at ({@code x}, {@code y}) of {@code w} × {@code h} (the first run): the intro as on
     * the screen about the card's centre, the run and the result with the strip near the card's foot. A card lower than
     * {@link #CARD_H} squeezes the gaps and the big numbers by the same factor; buttons keep their height.
     */
    void layoutCard(float x, float y, float w, float h) {
        float c = y + h * 0.5f;
        cx = x + w * 0.5f;
        k = Math.min(1f, h / CARD_H);
        textW = w - 96f;
        titleY = c + 237f * k;
        introY = c + 172f * k;
        presetY = c + 119f * k;
        output.setBounds(cx - 330f, c + 89f * k - 72f, 660f, 72f);
        currentY = c - 29f * k;
        startY = c - 203f * k;
        footY = c - 293f * k;
        digitY = c + 200f * k;
        digitSize = 200f * k;
        tapY = c + 30f * k;
        dotsY = c - 86f * k;
        stripY = Math.max(c - 228f * k, y + 110f);
        valueY = c + 215f * k;
        valueSize = 110f * k;
        lineY = c + 105f * k;
        subY = c + 57f * k;
        buttonsY = c - 55f * k;
        noteX = x + w - 28f;
        noteY = y + 28f;
        float bh = Button.standardHeight();
        start.setBounds(cx - 220f, startY - bh * 0.5f, 440f, bh);
        strip.setBounds(cx, stripY, Math.min(1200f, w - 160f));
        placeResultButtons();
    }

    private void placeResultButtons() {
        float bh = Button.standardHeight();
        float y = buttonsY - bh * 0.5f;
        if (resultKind == RES_OK) {
            float aw = 400f, ow = 260f, gap = 24f;
            float total = aw + (firstRun ? 1f : 2f) * (ow + gap);
            float x = cx - total * 0.5f;
            apply.setBounds(x, y, aw, bh);
            retry.setBounds(x + aw + gap, y, ow, bh);
            verify.setBounds(x + aw + 2f * gap + ow, y, ow, bh);
        } else {
            retry.setBounds(cx - 200f, y, 400f, bh);
        }
    }

    // ------------------------------------------------------------------ frame
    /** Advances the run, the result's animations and the ripples. */
    void update(float delta) {
        clock += delta;
        stateT += delta;
        wordT += delta;
        for (int i = 0; i < RIPPLES; i++) rippleT[i] += delta;
        strip.update(delta);
        output.update(delta);
        if (running()) updateRun();
        else if (state == VERIFY) updateVerify(delta);
    }

    /** Draws the current state at {@code a} (the buttons are drawn by the owner's widget set). */
    void draw(Batch b, float a) {
        switch (state) {
            case INTRO: drawIntro(b, a); break;
            case LEADIN:
            case MEASURE: drawRun(b, a); break;
            case RESULT: drawResult(b, a); break;
            case VERIFY: drawVerify(b, a); break;
            default: break;
        }
        drawRipples(a);
        if (noAudio && track != null) {
            game.fonts().draw(b, Fonts.Role.UI_LABEL, 16f, Lang.t("NO AUDIO OUTPUT · THE CLICKS ARE SILENT",
                "没有音频输出 · 听不到节拍声"), noteX, noteY, Align.right, Theme.GOLD, a);
        }
    }

    private void drawIntro(Batch b, float a) {
        Fonts f = game.fonts();
        f.draw(b, Fonts.Role.UI_BOLD, 44f, Lang.t("TAP ALONG TO THE CLICK", "跟着节拍声点击"), cx, titleY,
            Align.center | Fonts.VCENTER, Theme.INK1, a);
        f.drawFit(b, Fonts.Role.UI_REGULAR, 24f, introLine, cx, introY, Align.center | Fonts.VCENTER, textW, 0.7f,
            Theme.INK2, a);
        f.draw(b, Fonts.Role.UI_LABEL, 18f, Lang.t("AUDIO PRESET", "音频预设"), cx, presetY,
            Align.center | Fonts.VCENTER, Theme.INK3, a);
        output.draw(b, a);
        f.draw(b, Fonts.Role.UI_LABEL, 18f, currentLabel, cx, currentY, Align.center | Fonts.VCENTER, Theme.INK3, a);
        // the value and its unit centred together, like the result's
        float right = cx + (f.fixedWidth(Fonts.Role.NUMBER, 44f, currentText) - 12f
            - f.width(Fonts.Role.UI, 22f, "ms")) * 0.5f;
        f.drawFixed(b, Fonts.Role.NUMBER, 44f, currentText, right, currentY - 56f * k, Align.right | Fonts.VCENTER,
            Theme.INK1, a);
        f.draw(b, Fonts.Role.UI, 22f, "ms", right + 12f, currentY - 64f * k, Align.left | Fonts.VCENTER, Theme.INK2, a);
        // the foot only reports trouble (THE SCREEN STAYS STILL… went 2026-10-20 22:37)
        boolean unavailable = clickTried && clickFile == null;
        if (!unavailable && !interrupted) return;
        CharSequence foot = unavailable ? Lang.t("CLICK TRACK UNAVAILABLE · CHECK FREE STORAGE",
            "节拍音轨不可用 · 检查一下剩余存储空间")
            : Lang.t("RUN INTERRUPTED · TAP START TO TRY AGAIN", "校准被打断 · 点「开始」再试一次");
        f.drawFit(b, unavailable ? Fonts.Role.UI_REGULAR : Fonts.Role.UI, 18f, foot, cx, footY,
            Align.center | Fonts.VCENTER, textW, 0.7f, unavailable ? Theme.LOST : Theme.GOLD, a);
    }

    private void drawRun(Batch b, float a) {
        Fonts f = game.fonts();
        if (state == LEADIN) {
            double t = track != null ? track.nowMs() - device().audioOffsetMs() : 0.0;
            int i = (int) Math.floor((t - ClickTrack.FIRST_BEAT_MS) / ClickTrack.BEAT_MS);
            if (i >= 0 && i < ClickTrack.LEAD_IN) {
                float since = (float) ((t - ClickTrack.beatTimeMs(i)) / 1000.0);
                float pop = 1f + 0.25f * (1f - Anim.POW3_OUT.apply(Math.min(1f, since / 0.18f)));
                float fa = since < 0.35f ? 1f : Math.max(0.35f, 1f - (since - 0.35f) / 0.3f);
                f.drawScaled(b, Fonts.Role.NUMBER, digitSize, pop, DIGITS[ClickTrack.LEAD_IN - 1 - i], cx, digitY,
                    Align.center | Fonts.VCENTER, 1000f, 1f, i == 0 ? Theme.GROUND_SIG : Theme.INK1, fa * a);
            } else {
                f.draw(b, Fonts.Role.UI_BOLD, 34f, Lang.t("LISTEN", "听"), cx, digitY, Align.center | Fonts.VCENTER,
                    Theme.INK2, a);
            }
            f.draw(b, Fonts.Role.UI_BOLD, 32f, Lang.t("TAP ON EVERY CLICK", "每一下节拍声都点一下"), cx, tapY,
                Align.center | Fonts.VCENTER, Theme.INK1, a);
        } else {
            // KEEP TAPPING alone: its LISTEN · THE SCREEN STAYS STILL… line went 2026-10-20 22:37
            float in = Anim.POW3_OUT.apply(Math.min(1f, stateT / 0.32f));
            f.draw(b, Fonts.Role.UI_BOLD, 44f, Lang.t("KEEP TAPPING", "继续点"), cx, digitY,
                Align.center | Fonts.VCENTER, Theme.INK1, a * in);
        }
        drawProgress(b, a);
        strip.draw(b, a);
    }

    private void drawProgress(Batch b, float a) {
        int n = measured();
        float gap = 30f;
        float x0 = cx - (n - 1) * gap * 0.5f;
        int filled = stats.count();
        Draw d = game.draw();
        for (int i = 0; i < n; i++) {
            float x = x0 + i * gap;
            if (i < filled) d.disc(x, dotsY, 7f, Theme.GROUND_SIG, 0.95f * a);
            else d.circle(x, dotsY, 6f, 2f, Theme.INK3, 0.8f * a);
        }
        if (filled != countFor) {
            countFor = filled;
            countText.setLength(0);
            countText.append(Lang.t("VALID TAPS · ", "有效点击 · "));
            Fonts.appendInt(countText, filled).append(" / ");
            Fonts.appendInt(countText, n);
        }
        game.fonts().draw(b, Fonts.Role.UI_LABEL, 16f, countText, cx, dotsY + 34f, Align.center | Fonts.VCENTER,
            Theme.INK3, a);
    }

    private void drawResult(Batch b, float a) {
        Fonts f = game.fonts();
        float in = Anim.POW3_OUT.apply(Math.min(1f, stateT / 0.32f));
        float lift = 24f * (1f - in);
        float ra = a * in;
        if (resultKind == RES_OK) {
            // proportional: a static value, and fixed cells spread "-10" into "- 1 0" at this size; the value and
            // its unit are centred together (Kelvin 2026-10-21: 「那个+40ms和-32px不居中」)
            float unit = 40f * valueSize / 110f;
            float right = cx + (f.width(Fonts.Role.NUMBER, valueSize, resultValue) - 14f
                - f.width(Fonts.Role.UI_BOLD, unit, "ms")) * 0.5f;
            f.draw(b, Fonts.Role.NUMBER, valueSize, resultValue, right, valueY - lift, Align.right | Fonts.VCENTER,
                Theme.INK1, ra);
            f.draw(b, Fonts.Role.UI_BOLD, unit, "ms", right + 14f, valueY - 20f * k - lift,
                Align.left | Fonts.VCENTER, Theme.INK2, ra);
            if (appliedResult) {
                f.draw(b, Fonts.Role.UI_LABEL, 18f, Lang.t("APPLIED", "已应用"), right + 14f, valueY + 38f * k - lift,
                    Align.left | Fonts.VCENTER, Theme.RESONANCE, ra);
            }
        } else {
            Color c = resultKind == RES_INCONSISTENT ? Theme.LOST : Theme.GOLD;
            game.glitch().draw(b, Fonts.Role.RUBIK, 60f, resultValue, cx, valueY - 10f * k - lift,
                Align.center | Fonts.VCENTER, c, ra, resultGlitch, clock);
        }
        f.drawFit(b, Fonts.Role.UI, 28f, resultLine, cx, lineY - lift, Align.center | Fonts.VCENTER, textW, 0.7f,
            Theme.INK1, ra);
        f.drawFit(b, Fonts.Role.UI_REGULAR, 20f, resultSub, cx, subY - lift, Align.center | Fonts.VCENTER, textW, 0.7f,
            Theme.INK3, ra);
        strip.draw(b, a);
    }

    private void drawVerify(Batch b, float a) {
        Fonts f = game.fonts();
        Draw d = game.draw();
        float left = Ui.left(), right = Ui.right(), w = right - left;
        // zones and lines
        d.rectGradientV(left, GROUND_Y, w, 108f, Theme.GROUND_SIG, Theme.withAlpha(Theme.GROUND_SIG, 0f, tmp),
            0.06f * a);
        d.rectGradientV(left, SKY_Y - 108f, w, 108f, Theme.withAlpha(Theme.SKY_SIG, 0f, tmp), Theme.SKY_SIG,
            0.06f * a);
        d.sprite(d.glowV(), Ui.CX, GROUND_Y, w, 48f, Theme.GROUND_SIG, 0.18f * a);
        d.sprite(d.glowV(), Ui.CX, SKY_Y, w, 48f, Theme.SKY_SIG, 0.18f * a);
        d.rect(left, GROUND_Y - 2f, w, 4f, Theme.GROUND_SIG, 0.95f * a);
        d.rect(left, SKY_Y - 2f, w, 4f, Theme.SKY_SIG, 0.95f * a);
        // notes
        if (track != null) {
            double vt = track.nowMs() - candidate;
            NinePatch note = d.note9();
            float nw = 200f, nh = 40f;
            float old = b.getPackedColor();
            for (int i = 0; i < VERIFY_NOTES; i++) {
                if (noteDone[i]) continue;
                double ahead = noteTime(i) - vt;
                if (ahead > VERIFY_TRAVEL_MS || ahead < -Judgement.TIER2_MS) continue;
                boolean sky = isSky(i);
                float p = (float) (ahead / VERIFY_TRAVEL_MS);
                float ny = sky ? SKY_Y - p * (SKY_Y - SKY_SPAWN) : GROUND_Y + p * (GROUND_SPAWN - GROUND_Y);
                Color c = sky ? Theme.SKY_SIG : Theme.GROUND_SIG;
                float na = ahead < 0 ? a * (1f + (float) ahead / Judgement.TIER2_MS) : a;
                b.setColor(c.r, c.g, c.b, na);
                note.draw(b, laneX(i) - nw * 0.5f, ny - nh * 0.5f, nw, nh);
            }
            b.setPackedColor(old);
        }
        game.hitFx().draw(b);
        // judgement word
        if (word != null && wordT < 0.6f) {
            float wa = wordT < 0.45f ? 1f : 1f - (wordT - 0.45f) / 0.15f;
            Fonts.Role role = Fonts.Role.DISPLAY_SOFT; // every judgement word is Rubik Glitch
            // English in both languages, as in a game (Kelvin 2026-10-21: 「这里为什么还是中文，都用lost」)
            float ww = game.glitch().draw(b, role, 36f, word.display, Ui.CX, 470f,
                Align.center | Fonts.VCENTER, Theme.judgement(word), wa * a, wordGlitch, clock);
            if (wordMs.length() > 0) {
                float nx = Ui.CX + ww * 0.5f + 20f;
                float nw = f.drawFixed(b, Fonts.Role.NUMBER, 24f, wordMs, nx, 470f, Align.left | Fonts.VCENTER,
                    Theme.INK2, wa * a);
                f.draw(b, Fonts.Role.UI, 18f, "ms", nx + nw + 6f, 466f, Align.left | Fonts.VCENTER, Theme.INK3,
                    wa * a);
            }
        }
        // candidate panel; the running average sits under its ±5 buttons, clear of the note lanes
        float px = Ui.safeLeft() + 40f;
        f.drawFit(b, Fonts.Role.UI_REGULAR, 20f, avgText, px, 430f, Align.left | Fonts.VCENTER,
            VERIFY_LANES[0] - 140f - px, 0.7f, Theme.INK2, a);
        f.draw(b, Fonts.Role.UI_LABEL, 16f, candidateLabel, px, 700f, Align.left | Fonts.VCENTER, Theme.INK3, a);
        float cw = f.drawFixed(b, Fonts.Role.NUMBER, 48f, candidateText, px, 630f, Align.left | Fonts.VCENTER,
            Theme.INK1, a);
        f.draw(b, Fonts.Role.UI, 22f, "ms", px + cw + 10f, 622f, Align.left | Fonts.VCENTER, Theme.INK2, a);
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
        if (running()) {
            tap(x, y, eventNanos());
            return true;
        }
        if (state == VERIFY) {
            verifyTap(x, y, eventNanos());
            return true;
        }
        return state == INTRO && output.touchDown(x, y, pointer);
    }

    boolean touchDragged(float x, float y, int pointer) {
        return output.touchDragged(x, y, pointer);
    }

    boolean touchUp(float x, float y, int pointer) {
        return output.touchUp(x, y, pointer);
    }

    void cancel() {
        output.cancel();
    }

    void mouseMoved(float x, float y) {
        output.mouseMoved(x, y);
    }

    /** A key: a tap during a run or a verify pass, SPACE / ENTER starts from the intro. */
    boolean keyDown(int keycode) {
        if (running()) {
            tap(Ui.CX, Ui.CY, eventNanos());
            return true;
        }
        if (state == VERIFY) {
            boolean sky = keycode == Input.Keys.UP || keycode == Input.Keys.W || keycode == Input.Keys.J
                || keycode == Input.Keys.K;
            verifyTap(Ui.CX, sky ? SKY_Y : GROUND_Y, eventNanos());
            return true;
        }
        if (state == INTRO
            && (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER) && start.isEnabled()) {
            startRun();
            return true;
        }
        return false;
    }
}
