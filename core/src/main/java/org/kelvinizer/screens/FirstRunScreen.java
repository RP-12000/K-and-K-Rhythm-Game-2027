package org.kelvinizer.screens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.DeviceSettings;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.shell.DiverForm;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.transition.TransitionLayer;
import org.kelvinizer.ui.Anim;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchState;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Panel;
import org.kelvinizer.ui.Segmented;
import org.kelvinizer.ui.Slider;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Toggle;
import org.kelvinizer.ui.Ui;

/**
 * 首次启动的三步设置：WHO'S DIVING?（头像和名字）、COMFORT（故障强度、减少动态和闪烁）、
 * TUNE YOUR SONAR（音频输出和校准）。
 * 完成最后一步后直接下潜，进入新手教程。
 *
 * <p>First-run onboarding (DESIGN §10.1), three steps with progress dots under the header; BACK
 * goes to the previous step (from step 1, back to Welcome).
 *
 * <ol>
 *   <li><b>WHO'S DIVING?</b> The {@link DiverForm}: avatar preview 280 with REROLL, ten palette
 *       swatches and the name field ({@code TAP TO NAME YOUR DIVER}; empty keeps the default
 *       {@code DIVER-XXXX}). CONFIRM renames the diver and keeps the avatar; LATER keeps the
 *       default name. If old records exist: {@code WE FOUND EARLIER RECORDS — THEY'LL BE
 *       IMPORTED INTO THIS DIVER.}</li>
 *   <li><b>COMFORT.</b> Glitch intensity slider with a live sample (default 60 %), Reduce motion,
 *       Reduce flashing, and the photosensitivity line. Changes apply live. CONTINUE / SKIP.</li>
 *   <li><b>TUNE YOUR SONAR.</b> {@code USING BLUETOOTH? IT ADDS DELAY.} Output selector
 *       [SPEAKER | WIRED | BT]; CALIBRATE (→ Calibration, which returns here) / SKIP (sets the
 *       {@code calibration_reminder} flag for the map's reminder toast).</li>
 * </ol>
 * The last step completes the first run and dives into the tutorial ({@code game.startTutorial()},
 * DIVE). The backdrop stays at 0 m (DESIGN §2.6); because the surface water is bright, each step's
 * content sits on a soft card and a dark gradient sits under the header, so secondary copy keeps
 * a readable contrast.
 */
public class FirstRunScreen extends ShellScreen {
    /** Profile flag set when calibration was skipped (the map shows a reminder after the 2nd play). */
    public static final String FLAG_CALIBRATION_REMINDER = "calibration_reminder";
    private static final CharSequence[] TITLES = Lang.list("WHO'S DIVING?", "谁来下潜？", "COMFORT", "舒适度",
        "TUNE YOUR SONAR", "校准你的声呐");
    private static final CharSequence[] OUTPUTS = Lang.list("SPEAKER", "扬声器", "WIRED", "有线耳机", "BT", "蓝牙");
    /** Inner top padding of the step cards (first text line below the card's top edge). */
    private static final float CARD_PAD = 34f;

    private final DiverForm form;
    /** Name field of step 1 (kept under this name for the flow harnesses). */
    private final TextField name;
    /** Primary button of the current step (CONFIRM / CONTINUE / CALIBRATE or DIVE). */
    private final Button confirm;
    private final Button secondary;
    private final Slider intensity;
    private final Toggle reduceMotion, reduceFlashing;
    private final Segmented output;
    private final GlitchState sample;
    private final StringBuilder dots = new StringBuilder(16);
    private int step;
    private float stepTime;
    private boolean calibrating, calibrated;
    private boolean legacy;
    /** The legacy-import line under the form (null when there is no old save). */
    private String legacyNote;
    private boolean legacyImported;
    private float formScale = 1f;
    private float sampleNext;
    /** Content cards of the three steps (x, y, w, h), computed in {@link #layout()}. */
    private final float[] cards = new float[12];
    private final Color shadeClear = new Color();

    /** Creates the screen once (Main caches it). */
    public FirstRunScreen(final Main game) {
        super(game, TITLES[0], null, 0f, 1f, false);
        form = new DiverForm(game, this, widgets);
        name = form.field();
        confirm = widgets.add(new Button(game.draw(), game.fonts(), Button.Variant.PRIMARY, Lang.t("CONFIRM", "确认")));
        confirm.setOnTap(new Runnable() {
            @Override
            public void run() {
                primary();
            }
        });
        secondary = widgets.add(new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, Lang.t("LATER", "稍后")));
        secondary.setOnTap(new Runnable() {
            @Override
            public void run() {
                secondary();
            }
        });
        intensity = new Slider(game.draw(), game.fonts(), 0f, 1f, 0.05f);
        intensity.setDisplay(100f, 0, "%");
        intensity.setListener(new Slider.Listener() {
            @Override
            public void changed(Slider s, float value, boolean finished) {
                Settings st = settings();
                if (st == null) return;
                st.glitchIntensity = value;
                GlitchText.setIntensity(value);
                if (finished) game.profiles().markDirty();
            }
        });
        reduceMotion = widgets.add(new Toggle(game.draw(), false));
        reduceMotion.setListener(new Toggle.Listener() {
            @Override
            public void changed(Toggle t, boolean on) {
                Settings st = settings();
                if (st == null) return;
                st.reduceMotion = on;
                game.backdrop().setReduceMotion(on);
                game.profiles().markDirty();
            }
        });
        reduceFlashing = widgets.add(new Toggle(game.draw(), false));
        reduceFlashing.setListener(new Toggle.Listener() {
            @Override
            public void changed(Toggle t, boolean on) {
                Settings st = settings();
                if (st == null) return;
                st.reduceFlashing = on;
                GlitchText.setReduceFlashing(on);
                game.profiles().markDirty();
            }
        });
        output = new Segmented(game.draw(), game.fonts(), OUTPUTS);
        output.setLabelCap(22f);
        output.setListener(new Segmented.Listener() {
            @Override
            public void selected(Segmented control, int index) {
                DeviceSettings d = game.profiles().device();
                d.activeOutput = index;
                game.profiles().markDeviceDirty();
            }
        });
        sample = game.glitch().obtainState().ambient(GlitchText.Level.G1);
    }

    private Settings settings() {
        Profile p = diver();
        return p != null ? p.settings : null;
    }

    @Override
    protected float screenDepth() {
        return 0f;
    }

    @Override
    protected CharSequence screenLabel() {
        return TITLES[step];
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void enterScreen() {
        if (calibrating) {
            calibrating = false;
            calibrated = game.calibration().consumeApplied();
            go(2);
            return;
        }
        calibrated = false;
        ProfileStore store = game.profiles();
        Profile p = diver();
        String current = p != null ? p.name : null;
        boolean isDefault = current == null || current.matches("DIVER-[0-9A-F]{4}");
        int depthM = p != null ? p.depthMeters() : 0;
        form.reset(isDefault ? null : current, isDefault && current != null ? current : store.defaultName(),
            p != null ? p.avatar : null, depthM);
        // Only report what happened: records are imported at load (fresh install) or never.
        int imported = store.legacyImportedCount();
        legacyImported = imported > 0;
        if (legacyImported) {
            legacyNote = Lang.zh() ? "已把 " + imported + " 条以前的记录导入这位玩家。"
                : imported + (imported == 1 ? " EARLIER RECORD WAS" : " EARLIER RECORDS WERE")
                + " IMPORTED INTO THIS DIVER.";
        } else if (store.hasLegacyData()) {
            legacyNote = Lang.t("EARLIER RECORDS WERE FOUND BUT COULD NOT BE READ.", "找到了以前的记录，但读不出来。");
        } else {
            legacyNote = null;
        }
        legacy = legacyNote != null;
        Settings s = settings();
        if (s != null) {
            intensity.setValue(s.glitchIntensity);
            reduceMotion.setOn(s.reduceMotion, false);
            reduceFlashing.setOn(s.reduceFlashing, false);
        }
        output.select(MathUtils.clamp(store.device().activeOutput, 0, 2), false);
        go(0);
    }

    @Override
    protected void disposeScreen() {
        game.glitch().freeState(sample);
    }

    private void go(int s) {
        step = s;
        stepTime = 0f;
        setTitle(TITLES[s]);
        boolean one = s == 0, two = s == 1, three = s == 2;
        form.setVisible(one);
        reduceMotion.setVisible(two);
        reduceFlashing.setVisible(two);
        intensity.setVisible(two);
        output.setVisible(three);
        if (one) {
            confirm.setLabel(Lang.t("CONFIRM", "确认"));
            secondary.setLabel(Lang.t("LATER", "稍后"));
        } else if (two) {
            confirm.setLabel(Lang.t("CONTINUE", "继续"));
            secondary.setLabel(Lang.t("SKIP", "跳过"));
        } else {
            confirm.setLabel(calibrated ? Lang.t("DIVE", "下潜") : Lang.t("CALIBRATE", "校准"));
            secondary.setLabel(calibrated ? Lang.t("RECALIBRATE", "重新校准") : Lang.t("SKIP", "跳过"));
        }
        dots.setLength(0);
        if (Lang.zh()) dots.append("第 ").append(s + 1).append(" 步，共 3 步");
        else dots.append("STEP ").append(s + 1).append(" OF 3");
        widgets.cancel();
        invalidateLayout();
    }

    @Override
    protected void back() {
        if (step > 0) {
            go(step - 1);
            return;
        }
        game.transitionTo(game.welcome(), TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, "K & K");
    }

    // ------------------------------------------------------------------ actions
    private void primary() {
        if (step == 0) {
            if (!form.commitName()) return;
            applyDiver(true);
            go(1);
        } else if (step == 1) {
            game.applySettings();
            go(2);
        } else if (calibrated) {
            finish();
        } else {
            calibrate();
        }
    }

    private void secondary() {
        if (step == 0) {
            form.field().cancelEdit();
            applyDiver(false);
            go(1);
        } else if (step == 1) {
            game.applySettings();
            go(2);
        } else if (calibrated) {
            calibrate();
        } else {
            Profile p = diver();
            if (p != null && p.setFlag(FLAG_CALIBRATION_REMINDER)) game.profiles().markDirty();
            finish();
        }
    }

    /** Stores the chosen avatar and, when {@code rename}, the name on the active diver. */
    private void applyDiver(boolean rename) {
        ProfileStore store = game.profiles();
        Profile p = diver();
        if (p == null) return;
        p.avatar = form.avatar().copy();
        store.markDirty();
        if (rename) store.rename(p.id, form.name());
        else store.save();
    }

    private void calibrate() {
        CalibrationScreen c = game.calibration();
        c.setQuickMode(true);
        c.consumeApplied();
        calibrating = openScreen(c, CalibrationScreen.TITLE);
        if (!calibrating) c.setQuickMode(false);
    }

    private void finish() {
        ProfileStore store = game.profiles();
        store.completeFirstRun();
        game.applySettings();
        game.startTutorial();
    }

    // ------------------------------------------------------------------ layout
    @Override
    protected void layout() {
        float bh = Button.standardHeight();
        float bw = Math.min(400f, (Ui.safeRight() - Ui.safeLeft() - 120f) * 0.5f);
        float by = Ui.safeBottom() + 40f;
        secondary.setBounds(Ui.CX - 20f - bw, by, bw, bh);
        confirm.setBounds(Ui.CX + 20f, by, bw, bh);
        float top = Ui.safeTop() - HEADER_H - 40f;
        float bottom = by + bh + 40f;
        // step 1: the form, centred
        float avail = top - bottom - (legacy ? 50f : 0f);
        formScale = Math.max(0.6f, Math.min(1f, avail / DiverForm.naturalHeight()));
        float fw = (280f + 72f) * formScale + 560f;
        float formTop = Math.min(top, Ui.CY + 70f + DiverForm.naturalHeight() * formScale * 0.5f);
        form.layout(Ui.CX - fw * 0.5f, formTop, formScale);
        // step 2: slider and toggles
        float colW = Math.min(1040f, Ui.safeRight() - Ui.safeLeft() - 96f);
        float colX = Ui.CX - colW * 0.5f;
        float rowsTop = Ui.CY + 10f;
        intensity.setBounds(colX + colW * 0.42f, rowsTop - 56f, colW * 0.58f - 40f);
        reduceMotion.setCenter(colX + colW - Toggle.W * 0.5f, rowsTop - 56f - 112f);
        reduceFlashing.setCenter(colX + colW - Toggle.W * 0.5f, rowsTop - 56f - 224f);
        // step 3: output selector
        float segW = Math.min(780f, colW);
        output.setBounds(Ui.CX - segW * 0.5f, Ui.CY - 60f, segW, 88f);
        // content cards (never under the header, never over the buttons)
        float cardTop = Ui.safeTop() - HEADER_H + 6f;
        float cardMin = by + bh + 20f;
        float fl = Ui.CX - fw * 0.5f - 56f, fr = form.right() + 56f;
        float fb = Math.max(cardMin, form.bottom() - (legacy ? 92f : 40f));
        setCard(0, fl, fb, fr - fl, Math.min(cardTop, formTop + 34f) - fb);
        float cx = colX - 56f, cw = colW + 112f;
        float b1 = Math.max(cardMin, rowsTop - 56f - 224f - 60f);
        setCard(1, cx, b1, cw, cardTop - b1);
        float b2 = Math.max(cardMin, Ui.CY - 170f);
        setCard(2, cx, b2, cw, cardTop - b2);
    }

    private void setCard(int i, float x, float y, float w, float h) {
        cards[i * 4] = x;
        cards[i * 4 + 1] = y;
        cards[i * 4 + 2] = w;
        cards[i * 4 + 3] = h;
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void update(float delta) {
        stepTime += delta;
        form.update(delta);
        intensity.update(delta);
        output.update(delta);
    }

    @Override
    protected void drawScreen(float delta) {
        Draw d = game.draw();
        // the 0 m water is bright: darken behind the header (title, step label) ...
        float hb = Ui.safeTop() - HEADER_H - 40f;
        drawTopShade(d, hb);
        drawDots();
        float e = Anim.ease(Anim.POW3_OUT, stepTime, 0.02f, 0.30f);
        float a = e;
        // ... and give the step's content a soft card (secondary copy stays readable)
        int c = step * 4;
        float lift = 16f * (1f - e);
        Panel.shadow(d, cards[c], cards[c + 1] - lift, cards[c + 2], cards[c + 3], 0.6f * a);
        Panel.card(d, cards[c], cards[c + 1] - lift, cards[c + 2], cards[c + 3], 0.85f * a);
        if (step == 0) drawWho(a);
        else if (step == 1) drawComfort(a);
        else drawSonar(a);
    }

    @Override
    protected float widgetAlpha() {
        return Anim.ease(Anim.POW3_OUT, stepTime, 0.06f, 0.30f);
    }

    /** Dark gradient from the top edge (α .45) fading out at {@code bottom}. */
    private void drawTopShade(Draw d, float bottom) {
        Theme.withAlpha(Theme.ABYSS_BLACK, 0f, shadeClear);
        d.rectGradientV(Ui.left(), bottom, Ui.worldW(), Ui.top() - bottom, shadeClear, Theme.ABYSS_BLACK, 0.45f);
    }

    private void drawDots() {
        Draw d = game.draw();
        float cy = Ui.safeTop() - 44f;
        float cx = Ui.safeRight() - 48f - 2f * 36f;
        for (int i = 0; i < 3; i++) {
            float x = cx + i * 36f;
            if (i == step) {
                d.pill(x - 16f, cy - 7f, 32f, 14f, Theme.GROUND_SIG, 1f);
            } else {
                d.disc(x, cy, 6f, i < step ? Theme.GROUND_SIG : Theme.INK3, i < step ? 0.8f : 0.6f);
            }
        }
        game.fonts().draw(batch, Fonts.Role.UI_LABEL, 16f, dots, cx - 40f, cy, Align.right | Fonts.VCENTER,
            Theme.INK1, 0.85f);
    }

    private void drawWho(float a) {
        form.draw(batch, a);
        if (legacy) {
            game.fonts().drawFit(batch, Fonts.Role.UI, 20f, legacyNote, Ui.CX, form.bottom() - 36f,
                Align.center, cards[2] - 64f, 0.7f, legacyImported ? Theme.GOLD : Theme.INK2, a);
        }
    }

    private void drawComfort(float a) {
        Fonts f = game.fonts();
        Draw d = game.draw();
        float colW = Math.min(1040f, Ui.safeRight() - Ui.safeLeft() - 96f);
        float colX = Ui.CX - colW * 0.5f;
        float rowsTop = Ui.CY + 10f;
        float sampleY = rowsTop + 170f;
        f.draw(batch, Fonts.Role.UI, 22f, Lang.t("THIS GAME USES FLICKERING GLITCH EFFECTS.", "这个游戏有闪烁的故障特效。"),
            Ui.CX,
            Ui.safeTop() - HEADER_H - CARD_PAD, Align.center | Align.top, Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI, 22f, Lang.t("YOU CAN TURN THEM DOWN HERE.", "可以在这里调低。"), Ui.CX,
            Ui.safeTop() - HEADER_H - CARD_PAD - 36f, Align.center | Align.top, Theme.INK2, a);
        // live sample: bursts every ~1.1 s at the chosen intensity
        if (clock >= sampleNext && !sample.isBursting(clock)) {
            sample.trigger(GlitchText.Level.G2, clock);
            sampleNext = clock + 0.8f + MathUtils.random(0.6f);
        }
        game.glitch().draw(batch, Fonts.Role.DISPLAY, 72f, Lang.t("SIGNAL", "信号"), Ui.CX, sampleY,
            Align.center | Fonts.VCENTER,
            Theme.GROUND_SIG, a, sample, clock);
        f.draw(batch, Fonts.Role.UI_LABEL, 16f, Lang.t("LIVE SAMPLE", "实时示例"), Ui.CX, sampleY - 84f, Align.center,
            Theme.INK2, a);
        float y0 = rowsTop - 56f;
        f.draw(batch, Fonts.Role.UI_BOLD, 24f, Lang.t("GLITCH INTENSITY", "故障效果强度"), colX, y0,
            Align.left | Fonts.VCENTER, Theme.INK1, a);
        intensity.draw(batch, a);
        d.rect(colX, y0 - 56f, colW, 1f, Theme.STROKE, a);
        f.draw(batch, Fonts.Role.UI_BOLD, 24f, Lang.t("REDUCE MOTION", "减少动态"), colX, y0 - 112f + 14f,
            Align.left | Fonts.VCENTER,
            Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI_REGULAR, 18f, Lang.t("CALMER WATER, NO SCREEN SHAKE", "水面更平静，屏幕不晃动"), colX,
            y0 - 112f - 20f,
            Align.left | Fonts.VCENTER, Theme.INK2, a);
        d.rect(colX, y0 - 168f, colW, 1f, Theme.STROKE, a);
        f.draw(batch, Fonts.Role.UI_BOLD, 24f, Lang.t("REDUCE FLASHING", "减少闪烁"), colX, y0 - 224f + 14f,
            Align.left | Fonts.VCENTER,
            Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI_REGULAR, 18f, Lang.t("NO TEAR BARS OR BRIGHT FLASHES", "没有撕裂条，也没有强烈闪光"),
            colX, y0 - 224f - 20f,
            Align.left | Fonts.VCENTER, Theme.INK2, a);
    }

    private void drawSonar(float a) {
        Fonts f = game.fonts();
        Draw d = game.draw();
        float top = Ui.safeTop() - HEADER_H - CARD_PAD;
        f.draw(batch, Fonts.Role.UI_BOLD, 34f, Lang.t("USING BLUETOOTH? IT ADDS DELAY.", "用蓝牙？蓝牙会增加延迟。"), Ui.CX,
            top, Align.center | Align.top,
            Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI, 20f, Lang.t("PICK YOUR AUDIO PRESET. EACH ONE REMEMBERS ITS OWN CHART OFFSET.",
            "选好音频预设，每种预设会各自记住自己的谱面延时。"), Ui.CX, top - 60f,
            Align.center | Align.top, Theme.INK2, a);
        // a small sonar motif above the selector
        float cy = Ui.CY + 120f;
        float ph = (clock / Anim.HEARTBEAT) % 1f;
        for (int i = 0; i < 3; i++) {
            float p = (ph + i / 3f) % 1f;
            d.circle(Ui.CX, cy, 20f + 110f * p, 2f, Theme.GROUND_SIG, (1f - p) * 0.6f * a);
        }
        d.glowDot(Ui.CX, cy, 90f, Theme.GROUND_SIG, 0.6f * a);
        output.draw(batch, a);
        CharSequence note = calibrated ? Lang.t("SONAR TUNED · THE TUTORIAL IS NEXT", "声呐已校准 · 接下来是新手教程")
            : Lang.t("CALIBRATE TAKES ABOUT 12 SECONDS · YOU CAN DO IT LATER IN SETTINGS",
            "校准大约需要 12 秒 · 也可以以后在设置里做");
        f.drawFit(batch, Fonts.Role.UI_REGULAR, 18f, note, Ui.CX, Ui.CY - 110f, Align.center,
            Ui.safeRight() - Ui.safeLeft() - 96f, 0.7f, calibrated ? Theme.AUTO : Theme.INK2, a);
    }

    // ------------------------------------------------------------------ input (slider / segmented)
    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        if (step == 1) return intensity.touchDown(x, y, pointer);
        if (step == 2) return output.touchDown(x, y, pointer);
        return false;
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        if (step == 1) return intensity.touchDragged(x, y, pointer);
        if (step == 2) return output.touchDragged(x, y, pointer);
        return false;
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        if (step == 1) return intensity.touchUp(x, y, pointer);
        if (step == 2) return output.touchUp(x, y, pointer);
        return false;
    }

    @Override
    protected void contentCancel(int pointer) {
        // a cancelled drag already wrote its value live: keep it and mark it for saving
        if (intensity.isGrabbed()) game.profiles().markDirty();
        intensity.cancel();
        output.cancel();
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        if (step == 2) output.mouseMoved(x, y);
    }
}
