package org.kelvinizer.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
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
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.screens.shell.TapStats;
import org.kelvinizer.screens.shell.TapStrip;
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
 * 音频延迟校准：跟着 120 BPM 的节拍点击，取误差中位数作为当前音频输出的延迟，
 * 并可在天空线和地面线上试打验证。画面延迟 2026-10-18 起取消了，这里只剩这一页。
 *
 * <p>Audio offset calibration (DESIGN §9.4 [P0]).
 *
 * <p><b>AUDIO OFFSET</b>: the synthesised 120 BPM click track ({@link ClickTrack}: 4 lead-in
 * clicks plus 24 measured, or 12 in quick mode) plays through {@link AudioService#playFile} on the
 * same {@code Music} path and {@link SongClock} as songs. The lead-in shows a visual 4-3-2-1;
 * during the measured beats there is <i>no</i> visual beat cue. Every touch down (and key press)
 * anywhere is a tap, timestamped with {@code clock.timeAtNanos(Gdx.input.getCurrentEventTime())}
 * and matched to the nearest measured beat (first tap per beat; more than ±250 ms away is
 * dropped). A ripple marks each tap, a dot drops onto the −150..+150 ms scatter strip and a caret
 * follows the running median; 24 progress dots fill per valid tap. The result is
 * {@code offset = round(median(tap − beat))} with spread = MAD: MAD &gt; 35 ms reads
 * {@code INCONSISTENT · TRY AGAIN}; fewer than 16 valid taps (8 in quick mode) reads
 * {@code NOT ENOUGH TAPS}; otherwise {@code +42 ms · YOUR TAPS LAND 42 ms AFTER THE CLICK} with
 * APPLY TO &lt;active output&gt;, RETRY and VERIFY. Convention: {@code judgeMs =
 * clock.timeAtNanos(event) − audioOffsetMs}, so APPLY stores the median directly.
 *
 * <p><b>VERIFY</b> [P1] loops 16 gameplay-style notes alternating between the ground (y 120) and
 * sky (y 960) lines on the looping click track, judged with the candidate offset (tap the lower
 * half for ground, the upper half for sky), with live judgements, the average error and ±5 ms
 * nudges.
 *
 * <p>The VISUAL OFFSET tab (§9.5) is gone with the visual offset itself (Kelvin 2026-10-18 00:24:
 * it overlapped the audio offset); notes are drawn at judge time. Every text follows the
 * interface language ({@link Lang}).
 *
 * <p>Menu music is silenced while this screen is shown. BACK / ESC aborts a run (back to the
 * intro) or a verify pass (back to the result), else returns to the opener. When the app is
 * paused (Main pauses the click clock), a measuring run is abandoned because the gap breaks its
 * beat grid: with enough valid taps the result is computed from them, otherwise the intro returns
 * with {@code RUN INTERRUPTED}; a verify pass returns to the result. Nothing is allocated per
 * frame.
 */
public class CalibrationScreen extends ShellScreen {
    /** Valid taps required (full run / quick mode). */
    public static final int REQUIRED = 16, QUICK_REQUIRED = 8;
    /** Spread (median absolute deviation) above which the result is rejected. */
    public static final float MAX_MAD_MS = 35f;
    /** Taps further than this from the nearest beat are dropped. */
    public static final float MAX_TAP_ERROR_MS = 250f;
    /** Profile flag MAP sets once its calibration reminder was shown. */
    public static final String FLAG_REMINDER_SHOWN = "calibration_reminder_shown";

    /** Title, also the CURTAIN label of the screens that open this one. */
    public static final CharSequence TITLE = Lang.of("CALIBRATION", "延迟校准");

    private static final int INTRO = 0, LEADIN = 1, MEASURE = 2, RESULT = 3, VERIFY = 4;
    private static final int RES_OK = 0, RES_INCONSISTENT = 1, RES_TOO_FEW = 2;
    private static final float STRIP_Y = 200f, DOTS_Y = 372f;
    private static final float GROUND_Y = 120f, SKY_Y = 960f;
    private static final float GROUND_SPAWN = 1092f, SKY_SPAWN = -12f;
    private static final float VERIFY_TRAVEL_MS = 1100f;
    private static final int VERIFY_NOTES = 16;
    private static final float[] VERIFY_LANES = {660f, 860f, 1060f, 1260f};
    private static final int RIPPLES = 10;
    private static final float RIPPLE_LIFE = 0.5f;

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

    private final StringBuilder applyText = new StringBuilder(32);
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
    private float stateT;
    private int resultKind, resultOffset, resultCount, candidate;
    private float resultMad;
    private boolean quick, applied, appliedResult;
    private boolean interrupted;
    private int rippleNext;
    private Judgement word;
    private float wordT = 10f;
    private int verifyHits;
    private float verifySum;
    private double lastVerifyMs;
    private int countFor = -1;

    /** Creates the screen once (Main caches it). */
    public CalibrationScreen(final Main game) {
        super(game, TITLE, null, 0f, 0.6f, false);
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
        apply = widgets.add(new Button(d, f, Button.Variant.PRIMARY, applyText));
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
        vApply = widgets.add(new Button(d, f, Button.Variant.PRIMARY, applyText));
        vApply.setOnTap(new Runnable() {
            @Override
            public void run() {
                applyOffset(candidate);
                if (!quick) endVerify();
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

    // ------------------------------------------------------------------ public API
    /**
     * Quick mode (DESIGN §9.4, first run): 12 measured beats, at least 8 valid taps, and APPLY
     * returns to the opener at once. Set before opening the screen; cleared when it hides.
     */
    public void setQuickMode(boolean on) {
        quick = on;
    }

    /** True in quick mode. */
    public boolean isQuickMode() {
        return quick;
    }

    /**
     * True once if an offset was applied during the last visit (then cleared). FirstRun reads it on
     * return to know whether the player calibrated or backed out.
     */
    public boolean consumeApplied() {
        boolean a = applied;
        applied = false;
        return a;
    }

    /**
     * Sets where BACK leads after calibrating (e.g. RESULT's offset suggestion returns to the map);
     * consumed when the screen hides. A null {@code label} uses the destination's usual name.
     */
    public void returnTo(Screen target, CharSequence label) {
        if (label == null && (target == null || target == game.map())) label = Lang.t("DIVE CHART", "航线");
        setReturnTarget(target, label);
    }

    // ------------------------------------------------------------------ helpers
    private DeviceSettings device() {
        return game.profiles().device();
    }

    private int measured() {
        return quick ? ClickTrack.QUICK_MEASURED : ClickTrack.MEASURED;
    }

    private int required() {
        return quick ? QUICK_REQUIRED : REQUIRED;
    }

    private boolean running() {
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
        applyText.setLength(0);
        if (zh) applyText.append("应用到").append(out);
        else applyText.append("APPLY TO ").append(out);
        apply.setLabel(applyText);
        vApply.setLabel(applyText);
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
    @Override
    protected boolean playsMenuMusic() {
        return false;
    }

    @Override
    protected void enterScreen() {
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

    @Override
    protected void leaveScreen() {
        stopClicks();
        game.hitFx().clear();
        output.cancel();
        quick = false;
        game.profiles().save();
    }

    @Override
    protected void disposeScreen() {
        game.glitch().freeState(resultGlitch);
        game.glitch().freeState(wordGlitch);
    }

    @Override
    protected void back() {
        if (running()) {
            stopClicks();
            setState(INTRO);
            return;
        }
        if (state == VERIFY) {
            endVerify();
            return;
        }
        goBack();
    }

    /**
     * App backgrounded (a call, a notification, the screen locking). {@code Main.pause()} has
     * already paused the click clock and {@code AudioService.resume()} deliberately leaves it
     * paused, so a run cannot simply continue: the gap would break the beat grid. A measuring run
     * ends here (a result from the taps so far when there are enough, else back to the intro with
     * {@code RUN INTERRUPTED}); a verify pass returns to the result.
     */
    @Override
    public void pause() {
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

    // ------------------------------------------------------------------ state
    private void setState(int s) {
        state = s;
        stateT = 0f;
        updateWidgets();
    }

    private void updateWidgets() {
        boolean run = running();
        start.setVisible(state == INTRO);
        start.setEnabled(clickFile != null || !clickTried);
        boolean res = state == RESULT;
        boolean ok = res && resultKind == RES_OK;
        apply.setVisible(ok);
        apply.setEnabled(!appliedResult);
        verify.setVisible(ok);
        retry.setVisible(res);
        retry.setVariant(ok ? Button.Variant.SECONDARY : Button.Variant.PRIMARY);
        boolean ver = state == VERIFY;
        vMinus.setVisible(ver);
        vPlus.setVisible(ver);
        vApply.setVisible(ver);
        vDone.setVisible(ver);
        backButton.setVisible(!run);
        placeResultButtons();
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
        int k = beat - ClickTrack.LEAD_IN;
        if (k < 0 || k >= measured() || beatTaken[k]) return;
        float delta = (float) (t - ClickTrack.beatTimeMs(beat));
        if (Math.abs(delta) > MAX_TAP_ERROR_MS) return;
        beatTaken[k] = true;
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
            resultKind = resultMad > MAX_MAD_MS ? RES_INCONSISTENT : RES_OK;
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
                Fonts.appendInt(resultLine, a).append(resultOffset > 0 ? " ms AFTER THE CLICK" : " ms BEFORE THE CLICK");
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
            p.setFlag(FLAG_REMINDER_SHOWN);
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
        if (quick) {
            stopClicks();
            goBack();
            return;
        }
        updateWidgets();
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
        candidate = Math.max(DeviceSettings.AUDIO_OFFSET_MIN, Math.min(DeviceSettings.AUDIO_OFFSET_MAX, candidate + ms));
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
            for (int k = 0; k < VERIFY_NOTES; k++) {
                if (noteDone[k] || judge - noteTime(k) <= Judgement.TIER2_MS) continue;
                noteDone[k] = true;
                showWord(Judgement.LOST, 0);
                game.hitFx().lost(laneX(k), isSky(k) ? SKY_Y - 120f : GROUND_Y + 120f, 1f);
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
        for (int k = 0; k < VERIFY_NOTES; k++) {
            if (noteDone[k] || isSky(k) != sky) continue;
            double a = Math.abs(judge - noteTime(k));
            if (a < bestAbs) {
                bestAbs = a;
                best = k;
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
    @Override
    protected void layout() {
        float sl = Ui.safeLeft(), sr = Ui.safeRight();
        output.setBounds(Ui.CX - 330f, Ui.CY + 10f, 660f, 72f);
        float bh = Button.standardHeight();
        start.setBounds(Ui.CX - 220f, Ui.CY - 210f - bh * 0.5f, 440f, bh);
        // verify panels
        float px = sl + 40f;
        vMinus.setBounds(px, 470f, 150f, bh);
        vPlus.setBounds(px + 170f, 470f, 150f, bh);
        float rw = Math.min(440f, sr - sl - 1500f + 440f);
        rw = Math.max(320f, rw);
        vApply.setBounds(sr - rw, 600f, rw, bh);
        vDone.setBounds(sr - rw, 600f - bh - 20f, rw, bh);
        strip.setBounds(Ui.CX, STRIP_Y, Math.min(1200f, sr - sl - 160f));
        placeResultButtons();
    }

    private void placeResultButtons() {
        float bh = Button.standardHeight();
        float y = Ui.CY - 60f - bh * 0.5f;
        if (resultKind == RES_OK) {
            float aw = 520f, ow = 260f, gap = 24f;
            float total = aw + 2f * (ow + gap);
            float x = Ui.CX - total * 0.5f;
            apply.setBounds(x, y, aw, bh);
            retry.setBounds(x + aw + gap, y, ow, bh);
            verify.setBounds(x + aw + 2f * gap + ow, y, ow, bh);
        } else {
            retry.setBounds(Ui.CX - 200f, y, 400f, bh);
        }
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void update(float delta) {
        stateT += delta;
        wordT += delta;
        for (int i = 0; i < RIPPLES; i++) rippleT[i] += delta;
        strip.update(delta);
        output.update(delta);
        if (running()) updateRun();
        else if (state == VERIFY) updateVerify(delta);
    }

    @Override
    protected void drawScreen(float delta) {
        float e = enter(0.1f);
        switch (state) {
            case INTRO: drawIntro(e); break;
            case LEADIN:
            case MEASURE: drawRun(e); break;
            case RESULT: drawResult(e); break;
            case VERIFY: drawVerify(e); break;
            default: break;
        }
        drawRipples(e);
        if (noAudio && track != null) {
            game.fonts().draw(batch, Fonts.Role.UI_LABEL, 16f, Lang.t("NO AUDIO OUTPUT · THE CLICKS ARE SILENT",
                "没有音频输出 · 听不到节拍声"),
                Ui.safeRight() - 8f, Ui.safeBottom() + 30f, Align.right, Theme.GOLD, e);
        }
    }

    private void drawIntro(float a) {
        Fonts f = game.fonts();
        float cx = Ui.CX, cy = Ui.CY;
        f.draw(batch, Fonts.Role.UI_BOLD, 44f, Lang.t("TAP ALONG TO THE CLICK", "跟着节拍声点击"), cx, cy + 270f,
            Align.center | Fonts.VCENTER, Theme.INK1, a);
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 24f, introLine, cx, cy + 205f, Align.center | Fonts.VCENTER,
            Ui.safeRight() - Ui.safeLeft() - 80f, 0.7f, Theme.INK2, a);
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 24f, Lang.t("USE THE SPEAKER OR HEADPHONES YOU PLAY WITH",
            "用你平时玩的时候用的扬声器或耳机"), cx, cy + 165f,
            Align.center | Fonts.VCENTER, Ui.safeRight() - Ui.safeLeft() - 80f, 0.7f, Theme.INK2, a);
        f.draw(batch, Fonts.Role.UI_LABEL, 18f, Lang.t("AUDIO PRESET", "音频预设"), cx, cy + 112f,
            Align.center | Fonts.VCENTER, Theme.INK3, a);
        output.draw(batch, a);
        f.draw(batch, Fonts.Role.UI_LABEL, 18f, currentLabel, cx, cy - 36f, Align.center | Fonts.VCENTER, Theme.INK3,
            a);
        f.drawFixed(batch, Fonts.Role.NUMBER, 44f, currentText, cx - 6f, cy - 92f, Align.right | Fonts.VCENTER,
            Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI, 22f, "ms", cx + 6f, cy - 100f, Align.left | Fonts.VCENTER, Theme.INK2, a);
        boolean unavailable = clickTried && clickFile == null;
        CharSequence foot = unavailable ? Lang.t("CLICK TRACK UNAVAILABLE · CHECK FREE STORAGE",
            "节拍音轨不可用 · 检查一下剩余存储空间")
            : (interrupted ? Lang.t("RUN INTERRUPTED · TAP START TO TRY AGAIN", "校准被打断 · 点「开始」再试一次")
            : Lang.t("THE SCREEN STAYS STILL WHILE YOU TAP · VISUAL CUES WOULD BIAS THE RESULT",
            "点击时画面保持不动 · 画面提示会让结果不准"));
        Color footColor = unavailable ? Theme.LOST : (interrupted ? Theme.GOLD : Theme.INK3);
        f.drawFit(batch, interrupted && !unavailable ? Fonts.Role.UI : Fonts.Role.UI_REGULAR, 18f, foot, cx,
            cy - 300f, Align.center | Fonts.VCENTER, Ui.safeRight() - Ui.safeLeft() - 80f, 0.7f, footColor, a);
    }

    private void drawRun(float a) {
        Fonts f = game.fonts();
        float cx = Ui.CX, cy = Ui.CY;
        if (state == LEADIN) {
            double t = track != null ? track.nowMs() - device().audioOffsetMs() : 0.0;
            int i = (int) Math.floor((t - ClickTrack.FIRST_BEAT_MS) / ClickTrack.BEAT_MS);
            if (i >= 0 && i < ClickTrack.LEAD_IN) {
                float since = (float) ((t - ClickTrack.beatTimeMs(i)) / 1000.0);
                float pop = 1f + 0.25f * (1f - Anim.POW3_OUT.apply(Math.min(1f, since / 0.18f)));
                float fa = since < 0.35f ? 1f : Math.max(0.35f, 1f - (since - 0.35f) / 0.3f);
                f.drawScaled(batch, Fonts.Role.NUMBER, 200f, pop, DIGITS[ClickTrack.LEAD_IN - 1 - i], cx, cy + 130f,
                    Align.center | Fonts.VCENTER, 1000f, 1f, i == 0 ? Theme.GROUND_SIG : Theme.INK1, fa * a);
            } else {
                f.draw(batch, Fonts.Role.UI_BOLD, 34f, Lang.t("LISTEN", "听"), cx, cy + 130f,
                    Align.center | Fonts.VCENTER, Theme.INK2, a);
            }
            f.draw(batch, Fonts.Role.UI_BOLD, 32f, Lang.t("TAP ON EVERY CLICK", "每一下节拍声都点一下"), cx, cy - 40f,
                Align.center | Fonts.VCENTER, Theme.INK1, a);
        } else {
            float in = Anim.POW3_OUT.apply(Math.min(1f, stateT / 0.32f));
            f.draw(batch, Fonts.Role.UI_BOLD, 44f, Lang.t("KEEP TAPPING", "继续点"), cx, cy + 130f,
                Align.center | Fonts.VCENTER, Theme.INK1, a * in);
            f.draw(batch, Fonts.Role.UI_REGULAR, 22f, Lang.t("LISTEN · THE SCREEN STAYS STILL ON PURPOSE",
                "用耳朵听 · 画面故意保持不动"), cx, cy + 70f, Align.center | Fonts.VCENTER, Theme.INK3, a * in);
        }
        drawProgress(a);
        strip.draw(batch, a);
    }

    private static final String[] DIGITS = {"1", "2", "3", "4"};

    private void drawProgress(float a) {
        int n = measured();
        float gap = 30f;
        float x0 = Ui.CX - (n - 1) * gap * 0.5f;
        int filled = stats.count();
        Draw d = game.draw();
        for (int i = 0; i < n; i++) {
            float x = x0 + i * gap;
            if (i < filled) d.disc(x, DOTS_Y, 7f, Theme.GROUND_SIG, 0.95f * a);
            else d.circle(x, DOTS_Y, 6f, 2f, Theme.INK3, 0.8f * a);
        }
        if (filled != countFor) {
            countFor = filled;
            countText.setLength(0);
            countText.append(Lang.t("VALID TAPS · ", "有效点击 · "));
            Fonts.appendInt(countText, filled).append(" / ");
            Fonts.appendInt(countText, n);
        }
        game.fonts().draw(batch, Fonts.Role.UI_LABEL, 16f, countText, Ui.CX, DOTS_Y + 34f,
            Align.center | Fonts.VCENTER, Theme.INK3, a);
    }

    private void drawResult(float a) {
        Fonts f = game.fonts();
        float cx = Ui.CX, cy = Ui.CY;
        float in = Anim.POW3_OUT.apply(Math.min(1f, stateT / 0.32f));
        float lift = 24f * (1f - in);
        float ra = a * in;
        if (resultKind == RES_OK) {
            // proportional: a static value, and fixed cells spread "-10" into "- 1 0" at this size
            f.draw(batch, Fonts.Role.NUMBER, 110f, resultValue, cx - 10f, cy + 210f - lift,
                Align.right | Fonts.VCENTER, Theme.INK1, ra);
            f.draw(batch, Fonts.Role.UI_BOLD, 40f, "ms", cx + 4f, cy + 190f - lift, Align.left | Fonts.VCENTER,
                Theme.INK2, ra);
            if (appliedResult) {
                f.draw(batch, Fonts.Role.UI_LABEL, 18f, Lang.t("APPLIED", "已应用"), cx + 4f, cy + 248f - lift,
                    Align.left | Fonts.VCENTER, Theme.RESONANCE, ra);
            }
            f.draw(batch, Fonts.Role.UI, 28f, resultLine, cx, cy + 100f - lift, Align.center | Fonts.VCENTER,
                Theme.INK1, ra);
        } else {
            Color c = resultKind == RES_INCONSISTENT ? Theme.LOST : Theme.GOLD;
            game.glitch().draw(batch, Fonts.Role.RUBIK, 60f, resultValue, cx, cy + 200f - lift,
                Align.center | Fonts.VCENTER, c, ra, resultGlitch, clock);
            f.draw(batch, Fonts.Role.UI, 28f, resultLine, cx, cy + 100f - lift, Align.center | Fonts.VCENTER,
                Theme.INK1, ra);
        }
        f.draw(batch, Fonts.Role.UI_REGULAR, 20f, resultSub, cx, cy + 52f - lift, Align.center | Fonts.VCENTER,
            Theme.INK3, ra);
        strip.draw(batch, a);
    }

    private void drawVerify(float a) {
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
            float old = batch.getPackedColor();
            for (int k = 0; k < VERIFY_NOTES; k++) {
                if (noteDone[k]) continue;
                double ahead = noteTime(k) - vt;
                if (ahead > VERIFY_TRAVEL_MS || ahead < -Judgement.TIER2_MS) continue;
                boolean sky = isSky(k);
                float p = (float) (ahead / VERIFY_TRAVEL_MS);
                float ny = sky ? SKY_Y - p * (SKY_Y - SKY_SPAWN) : GROUND_Y + p * (GROUND_SPAWN - GROUND_Y);
                Color c = sky ? Theme.SKY_SIG : Theme.GROUND_SIG;
                float na = ahead < 0 ? a * (1f + (float) ahead / Judgement.TIER2_MS) : a;
                batch.setColor(c.r, c.g, c.b, na);
                note.draw(batch, laneX(k) - nw * 0.5f, ny - nh * 0.5f, nw, nh);
            }
            batch.setPackedColor(old);
        }
        game.hitFx().draw(batch);
        // judgement word
        if (word != null && wordT < 0.6f) {
            float wa = wordT < 0.45f ? 1f : 1f - (wordT - 0.45f) / 0.15f;
            Fonts.Role role = Fonts.Role.DISPLAY_SOFT; // every judgement word is Rubik Glitch
            float ww = game.glitch().draw(batch, role, 36f, Lang.zh() ? word.displayZh : word.display, Ui.CX, 470f,
                Align.center | Fonts.VCENTER, Theme.judgement(word), wa * a, wordGlitch, clock);
            if (wordMs.length() > 0) {
                float nx = Ui.CX + ww * 0.5f + 20f;
                float nw = f.drawFixed(batch, Fonts.Role.NUMBER, 24f, wordMs, nx, 470f, Align.left | Fonts.VCENTER,
                    Theme.INK2, wa * a);
                f.draw(batch, Fonts.Role.UI, 18f, "ms", nx + nw + 6f, 466f, Align.left | Fonts.VCENTER, Theme.INK3,
                    wa * a);
            }
        }
        // candidate panel; the running average sits under its ±5 buttons, clear of the note lanes
        float px = Ui.safeLeft() + 40f;
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 20f, avgText, px, 430f, Align.left | Fonts.VCENTER,
            VERIFY_LANES[0] - 140f - px, 0.7f, Theme.INK2, a);
        f.draw(batch, Fonts.Role.UI_LABEL, 16f, candidateLabel, px, 700f, Align.left | Fonts.VCENTER, Theme.INK3, a);
        float cw = f.drawFixed(batch, Fonts.Role.NUMBER, 48f, candidateText, px, 630f, Align.left | Fonts.VCENTER,
            Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI, 22f, "ms", px + cw + 10f, 622f, Align.left | Fonts.VCENTER, Theme.INK2, a);
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

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        return output.touchDragged(x, y, pointer);
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        return output.touchUp(x, y, pointer);
    }

    @Override
    protected void contentCancel(int pointer) {
        output.cancel();
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        output.mouseMoved(x, y);
    }

    @Override
    protected boolean contentKeyDown(int keycode) {
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
