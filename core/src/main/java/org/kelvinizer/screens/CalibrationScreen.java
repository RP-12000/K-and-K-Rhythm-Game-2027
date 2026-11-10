package org.kelvinizer.screens;

import com.badlogic.gdx.Screen;
import org.kelvinizer.Main;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.ui.Lang;

/**
 * 音频延迟校准（设置里的那一页）：内容都在 {@link CalibrationPanel} 里，这里把它铺满整个屏幕。
 * 首次设置第 3 步用的是同一个部件，放在卡片里。
 *
 * <p>Audio offset calibration (DESIGN §9.4 [P0]) over the whole screen: a thin owner of a
 * {@link CalibrationPanel} (run, result, VERIFY), which the first run's step 3 shows in its card instead (Kelvin
 * 2026-10-20 22:30). The back arrow hides during a run, where every touch is a tap; BACK / ESC aborts a run or a
 * verify pass first, else returns to the opener. The quick mode moved to the first run's panel with that step.
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

    private final CalibrationPanel panel;

    /** Creates the screen once (Main caches it). */
    public CalibrationScreen(final Main game) {
        super(game, TITLE, null, 0f, 0.6f, false);
        panel = new CalibrationPanel(game, widgets, new CalibrationPanel.Host() {
            @Override
            public void runChanged(boolean running) {
                backButton.setVisible(!running);
            }

            @Override
            public void applied() {
            }
        }, false);
    }

    /**
     * Sets where BACK leads after calibrating (e.g. RESULT's offset suggestion returns to the map);
     * consumed when the screen hides. A null {@code label} uses the destination's usual name.
     */
    public void returnTo(Screen target, CharSequence label) {
        if (label == null && (target == null || target == game.map())) label = Lang.t("DIVE CHART", "航线");
        setReturnTarget(target, label);
    }

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected boolean playsMenuMusic() {
        return false;
    }

    @Override
    protected void enterScreen() {
        panel.enter();
    }

    @Override
    protected void leaveScreen() {
        panel.leave();
    }

    @Override
    protected void disposeScreen() {
        panel.dispose();
    }

    @Override
    protected void back() {
        if (!panel.back()) goBack();
    }

    @Override
    public void pause() {
        panel.pause();
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void layout() {
        panel.layoutScreen();
    }

    @Override
    protected void update(float delta) {
        panel.update(delta);
    }

    @Override
    protected void drawScreen(float delta) {
        panel.draw(batch, enter(0.1f));
    }

    // ------------------------------------------------------------------ input
    @Override
    protected boolean contentTouchDown(float x, float y, int pointer) {
        return panel.touchDown(x, y, pointer);
    }

    @Override
    protected boolean contentTouchDragged(float x, float y, int pointer) {
        return panel.touchDragged(x, y, pointer);
    }

    @Override
    protected boolean contentTouchUp(float x, float y, int pointer) {
        return panel.touchUp(x, y, pointer);
    }

    @Override
    protected void contentCancel(int pointer) {
        panel.cancel();
    }

    @Override
    protected void contentMouseMoved(float x, float y) {
        panel.mouseMoved(x, y);
    }

    @Override
    protected boolean contentKeyDown(int keycode) {
        return panel.keyDown(keycode);
    }
}
