package org.kelvinizer.screens;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.profile.Settings;
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
import org.kelvinizer.ui.Slider;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Toggle;
import org.kelvinizer.ui.Ui;

/**
 * 首次启动的三步设置：COMFORT（故障强度、减少动态和闪烁）、延迟校准、目押校准（后两步就是设置里的两个校准界面，
 * 放在加宽的卡片里）。新玩家直接用默认名和随机头像，以后在个人页改。没有标题栏和返回键，每一步的卡片都放在
 * 屏幕正中。目押校准应用或跳过后直接下潜，进入新手教程。
 *
 * <p>First-run onboarding (DESIGN §10.1), three steps with progress dots at the top right. The player keeps the
 * default {@code DIVER-XXXX} and the random avatar the store created them with, both changed on the profile
 * (Kelvin 2026-10-21: 「第一部直接不要了，默认随机一个头像就可以了」; the WHO'S DIVING? step went, after its name
 * field on 2026-10-20 22:37). Records imported from an old save are reported by a toast at start-up ({@code Main}).
 * There is no header: no step title and no BACK icon (Kelvin 2026-10-20 on the gallery's pp.2, 4 and 5: 「初次进入
 * 游戏这部分不要了」, 「删了，返回键也删了」); the system BACK still goes to the previous step (from step 1, back to
 * Welcome). Each step's card sits in the middle of the screen (「整一个区域放在屏幕居中处」).
 *
 * <ol>
 *   <li><b>COMFORT.</b> Glitch intensity slider with a live sample, the English word {@code SIGNAL} in
 *       both languages (「这里直接显示英语的Signal」; default 60 %), Reduce motion and Reduce flashing; the gaps above
 *       and below the sample are tighter and it has no LIVE SAMPLE label (22:30). Changes apply live.
 *       CONTINUE / SKIP.</li>
 *   <li><b>CALIBRATION.</b> The audio calibration of Settings in a wider card ({@link CalibrationPanel}: quick mode,
 *       no VERIFY; 22:30: 「校准那里直接把延迟校准的设置界面搬过来，放在现在那个框里（那个框横向拉长一点）」).
 *       APPLY moves on to step 3; SKIP sets the {@code calibration_reminder} flag for the map's reminder toast (unless
 *       the diver is calibrated) and moves on too.</li>
 *   <li><b>VISUAL HIT POINT.</b> The 目押 calibration in the same card ({@link HitPointPanel}; 22:37: 「音押校准点
 *       “应用”以后进入目押校准界面，目押校准完了进introduction」). APPLY or SKIP completes the first run.</li>
 * </ol>
 * The last step completes the first run and dives into the tutorial ({@code game.startTutorial()},
 * DIVE). During a calibration run every touch is a tap, so SKIP hides. The backdrop stays at 0 m (DESIGN §2.6);
 * because the surface water is bright, each step's content sits on a soft card and a dark gradient sits under the
 * step label, so secondary copy keeps a readable contrast. Where a card reaches the step label (the calibration
 * card on phones), the label hides and the dots stay.
 */
public class FirstRunScreen extends ShellScreen {
    /** Profile flag set when calibration was skipped (the map shows a reminder after the 2nd play). */
    public static final String FLAG_CALIBRATION_REMINDER = "calibration_reminder";
    private static final CharSequence[] TITLES = Lang.list("COMFORT", "舒适度", "CALIBRATION", "延迟校准",
        "VISUAL HIT POINT", "目押校准");
    /** The steps, in order. */
    private static final int COMFORT = 0, AUDIO = 1, HIT = 2, STEPS = 3;
    /** Inner top padding of the step cards (first text line below the card's top edge). */
    private static final float CARD_PAD = 34f;
    /**
     * COMFORT card, top down: card top to the sample, sample to the first row, first row to the card bottom
     * (the gaps shrink to their minimums on short screens; both narrowed 2026-10-20 22:30).
     */
    private static final float SAMPLE_DROP = 236f, SAMPLE_DROP_MIN = 150f, ROWS_DROP = 170f, ROWS_DROP_MIN = 130f,
        ROWS_TAIL = 284f;
    /** Width of the calibration card of steps 2 and 3 (wider than the COMFORT card, 22:30). */
    private static final float CAL_W = 1400f;

    /** CONTINUE of COMFORT; the calibrations move on with their own APPLY. */
    private final Button confirm;
    /** SKIP of every step. */
    private final Button secondary;
    private final Slider intensity;
    private final Toggle reduceMotion, reduceFlashing;
    private final GlitchState sample;
    private final CalibrationPanel audio;
    private final HitPointPanel hit;
    private final StringBuilder dots = new StringBuilder(16);
    private int step;
    private float stepTime;
    private float sampleNext;
    /** Laid-out heights of the COMFORT card (top, sample, first row). */
    private float comfortTop, sampleY, rowsY;
    /** Content cards of the three steps (x, y, w, h), computed in {@link #layout()}. */
    private final float[] cards = new float[STEPS * 4];
    private final Color shadeClear = new Color();

    /** Creates the screen once (Main caches it). */
    public FirstRunScreen(final Main game) {
        // no title: no header and no BACK icon (TITLES still name the screen on the curtain)
        super(game, null, null, 0f, 1f, false);
        confirm = widgets.add(new Button(game.draw(), game.fonts(), Button.Variant.PRIMARY, Lang.t("CONTINUE", "继续")));
        confirm.setOnTap(new Runnable() {
            @Override
            public void run() {
                primary();
            }
        });
        secondary = widgets.add(new Button(game.draw(), game.fonts(), Button.Variant.SECONDARY, Lang.t("SKIP", "跳过")));
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
        sample = game.glitch().obtainState().ambient(GlitchText.Level.G1);
        // steps 2 and 3: APPLY moves on (「点“应用”以后进入目押校准界面，目押校准完了进introduction」)
        audio = new CalibrationPanel(game, widgets, new CalibrationPanel.Host() {
            @Override
            public void runChanged(boolean running) {
                secondary.setVisible(!running);
            }

            @Override
            public void applied() {
                go(HIT);
            }
        }, true);
        hit = new HitPointPanel(game, widgets, new CalibrationPanel.Host() {
            @Override
            public void runChanged(boolean running) {
                secondary.setVisible(!running);
            }

            @Override
            public void applied() {
                finish();
            }
        });
        audio.setVisible(false);
        hit.setVisible(false);
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
        step = COMFORT;
        Settings s = settings();
        if (s != null) {
            intensity.setValue(s.glitchIntensity);
            reduceMotion.setOn(s.reduceMotion, false);
            reduceFlashing.setOn(s.reduceFlashing, false);
        }
        go(COMFORT);
    }

    @Override
    protected void leaveScreen() {
        if (step == AUDIO) audio.leave();
        else if (step == HIT) hit.leave();
    }

    @Override
    protected void disposeScreen() {
        game.glitch().freeState(sample);
        audio.dispose();
    }

    private void go(int s) {
        int from = step;
        if (from == AUDIO && s != AUDIO) audio.leave();
        if (from == HIT && s != HIT) hit.leave();
        step = s;
        stepTime = 0f;
        boolean comfort = s == COMFORT;
        reduceMotion.setVisible(comfort);
        reduceFlashing.setVisible(comfort);
        intensity.setVisible(comfort);
        confirm.setVisible(comfort);
        secondary.setVisible(true);
        if (s == AUDIO) audio.enter();
        if (s == HIT) hit.enter();
        audio.setVisible(s == AUDIO);
        hit.setVisible(s == HIT);
        // the calibrations silence the menu music: back on COMFORT it plays again
        if (comfort && from != COMFORT) game.audio().playMenu();
        dots.setLength(0);
        if (Lang.zh()) dots.append("第 ").append(s + 1).append(" 步，共 ").append(STEPS).append(" 步");
        else dots.append("STEP ").append(s + 1).append(" OF ").append(STEPS);
        widgets.cancel();
        invalidateLayout();
    }

    @Override
    protected void back() {
        if (step == AUDIO && audio.back()) return;
        if (step == HIT && hit.back()) return;
        if (step > COMFORT) {
            go(step - 1);
            return;
        }
        game.transitionTo(game.welcome(), TransitionLayer.Style.CURTAIN, Ui.CX, Ui.CY, "K & K");
    }

    /** App backgrounded: a calibration run in progress is abandoned (see the panels). */
    @Override
    public void pause() {
        if (step == AUDIO) audio.pause();
        else if (step == HIT) hit.pause();
    }

    // ------------------------------------------------------------------ actions
    private void primary() {
        if (step != COMFORT) return;
        game.applySettings();
        go(AUDIO);
    }

    private void secondary() {
        if (step == COMFORT) {
            game.applySettings();
            go(AUDIO);
        } else if (step == AUDIO) {
            Profile p = diver();
            if (p != null && !p.calibrated && p.setFlag(FLAG_CALIBRATION_REMINDER)) game.profiles().markDirty();
            go(HIT);
        } else {
            finish();
        }
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
        // the calibrations have SKIP alone, centred
        secondary.setBounds(step != COMFORT ? Ui.CX - bw * 0.5f : Ui.CX - 20f - bw, by, bw, bh);
        confirm.setBounds(Ui.CX + 20f, by, bw, bh);
        // each step's card is centred on the screen between the safe top and the buttons
        float hi = Ui.safeTop();
        float lo = by + bh + 20f;
        float avail = hi - lo;
        // COMFORT: intro, live sample, slider and toggles
        float colW = Math.min(1040f, Ui.safeRight() - Ui.safeLeft() - 96f);
        float colX = Ui.CX - colW * 0.5f;
        float cut = Math.max(0f, SAMPLE_DROP + ROWS_DROP + ROWS_TAIL - avail);
        float sampleDrop = Math.max(SAMPLE_DROP_MIN, SAMPLE_DROP - cut);
        float rowsDrop = Math.max(ROWS_DROP_MIN, ROWS_DROP - (cut - (SAMPLE_DROP - sampleDrop)));
        float h1 = sampleDrop + rowsDrop + ROWS_TAIL;
        float y1 = cardY(h1, lo, hi);
        comfortTop = y1 + h1;
        sampleY = comfortTop - sampleDrop;
        rowsY = sampleY - rowsDrop;
        intensity.setBounds(colX + colW * 0.42f, rowsY, colW * 0.58f - 40f);
        reduceMotion.setCenter(colX + colW - Toggle.W * 0.5f, rowsY - 112f);
        reduceFlashing.setCenter(colX + colW - Toggle.W * 0.5f, rowsY - 224f);
        setCard(COMFORT, colX - 56f, y1, colW + 112f, h1);
        // steps 2 and 3: the two calibrations in one wider card (「那个框横向拉长一点」)
        float calW = Math.min(CAL_W, Ui.safeRight() - Ui.safeLeft() - 48f);
        float calH = Math.min(CalibrationPanel.CARD_H, avail);
        float calX = Ui.CX - calW * 0.5f, calY = cardY(calH, lo, hi);
        setCard(AUDIO, calX, calY, calW, calH);
        setCard(HIT, calX, calY, calW, calH);
        audio.layoutCard(calX, calY, calW, calH);
        hit.layoutCard(calX, calY, calW, calH);
    }

    /** Bottom of a card of height {@code h} centred on the screen, kept between {@code lo} and {@code hi}. */
    private static float cardY(float h, float lo, float hi) {
        return Math.max(lo, Math.min(Ui.CY - h * 0.5f, hi - h));
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
        intensity.update(delta);
        if (step == AUDIO) audio.update(delta);
        else if (step == HIT) hit.update(delta);
    }

    @Override
    protected void drawScreen(float delta) {
        Draw d = game.draw();
        // the 0 m water is bright: darken behind the step label ...
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
        if (step == COMFORT) drawComfort(a);
        else if (step == AUDIO) audio.draw(batch, a);
        else hit.draw(batch, a);
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
        float cx = Ui.safeRight() - 48f - (STEPS - 1) * 36f;
        for (int i = 0; i < STEPS; i++) {
            float x = cx + i * 36f;
            if (i == step) {
                d.pill(x - 16f, cy - 7f, 32f, 14f, Theme.GROUND_SIG, 1f);
            } else {
                d.disc(x, cy, 6f, i < step ? Theme.GROUND_SIG : Theme.INK3, i < step ? 0.8f : 0.6f);
            }
        }
        // the label gives way to a card that reaches it (the calibration card on phones); the dots stay
        Fonts f = game.fonts();
        float lx = cx - 40f;
        int c = step * 4;
        boolean clash = cards[c + 1] + cards[c + 3] > cy - 24f
            && cards[c] + cards[c + 2] > lx - f.width(Fonts.Role.UI_LABEL, 16f, dots) - 16f;
        if (!clash) f.draw(batch, Fonts.Role.UI_LABEL, 16f, dots, lx, cy, Align.right | Fonts.VCENTER, Theme.INK1,
            0.85f);
    }

    private void drawComfort(float a) {
        Fonts f = game.fonts();
        Draw d = game.draw();
        float colW = Math.min(1040f, Ui.safeRight() - Ui.safeLeft() - 96f);
        float colX = Ui.CX - colW * 0.5f;
        float top = comfortTop - CARD_PAD - 6f;
        f.draw(batch, Fonts.Role.UI, 22f, Lang.t("THIS GAME USES FLICKERING GLITCH EFFECTS.", "这个游戏有闪烁的故障特效。"),
            Ui.CX, top, Align.center | Align.top, Theme.INK1, a);
        f.draw(batch, Fonts.Role.UI, 22f, Lang.t("YOU CAN TURN THEM DOWN HERE.", "可以在这里调低。"), Ui.CX,
            top - 36f, Align.center | Align.top, Theme.INK2, a);
        // live sample: bursts every ~1.1 s at the chosen intensity
        if (clock >= sampleNext && !sample.isBursting(clock)) {
            sample.trigger(GlitchText.Level.G2, clock);
            sampleNext = clock + 0.8f + MathUtils.random(0.6f);
        }
        // the sample is the English word in both languages (Kelvin 2026-10-20: 「这里直接显示英语的Signal」), with no
        // LIVE SAMPLE label under it since 22:30 (「"实时示例"删了」)
        game.glitch().draw(batch, Fonts.Role.DISPLAY, 72f, "SIGNAL", Ui.CX, sampleY,
            Align.center | Fonts.VCENTER,
            Theme.GROUND_SIG, a, sample, clock);
        float y0 = rowsY;
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

    // ------------------------------------------------------------------ input (slider / calibrations)
    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        if (step == COMFORT) return intensity.touchDown(x, y, pointer);
        if (step == AUDIO) return audio.touchDown(x, y, pointer);
        if (step == HIT) return hit.touchDown(x, y, pointer);
        return false;
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        if (step == COMFORT) return intensity.touchDragged(x, y, pointer);
        if (step == AUDIO) return audio.touchDragged(x, y, pointer);
        return false;
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        if (step == COMFORT) return intensity.touchUp(x, y, pointer);
        if (step == AUDIO) return audio.touchUp(x, y, pointer);
        return false;
    }

    @Override
    protected void contentCancel(int pointer) {
        // a cancelled drag already wrote its value live: keep it and mark it for saving
        if (intensity.isGrabbed()) game.profiles().markDirty();
        intensity.cancel();
        audio.cancel();
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        if (step == AUDIO) audio.mouseMoved(x, y);
    }

    @Override
    protected boolean contentKeyDown(int keycode) {
        if (step == AUDIO) return audio.keyDown(keycode);
        if (step == HIT) return hit.keyDown(keycode);
        return false;
    }
}
