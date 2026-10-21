package org.kelvinizer.screens;

import org.kelvinizer.Main;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.screens.shell.ShellScreen;
import org.kelvinizer.ui.Lang;

/**
 * 目押校准（设置里的那一页）：内容都在 {@link HitPointPanel} 里，这里把它铺满整个屏幕。
 * 首次设置第 4 步用的是同一个部件，放在卡片里。
 *
 * <p>VISUAL HIT POINT calibration over the whole screen (Kelvin 2026-10-20 03:20: 「下面的校准会单独弹出来一个界面」),
 * opened by CALIBRATE… under the VISUAL HIT POINT row of Settings → VISUAL ({@link Settings#hitPointPx}): a thin owner
 * of a {@link HitPointPanel}, which the first run's step 4 shows in its card instead (Kelvin 22:37). The back arrow
 * hides during a run; BACK / ESC ends a run first, else returns to the opener.
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

    private final HitPointPanel panel;

    /** Creates the screen once (Main caches it). */
    public HitPointScreen(final Main game) {
        super(game, TITLE, null, 0f, 0.6f, false);
        panel = new HitPointPanel(game, widgets, new CalibrationPanel.Host() {
            @Override
            public void runChanged(boolean running) {
                backButton.setVisible(!running);
            }

            @Override
            public void applied() {
            }
        });
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
    protected boolean contentKeyDown(int keycode) {
        return panel.keyDown(keycode);
    }
}
