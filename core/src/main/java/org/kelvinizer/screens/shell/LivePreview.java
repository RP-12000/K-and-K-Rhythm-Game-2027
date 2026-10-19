package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.Main;
import org.kelvinizer.gameplay.PreviewRun;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

/**
 * 「游戏界面」画面中间的实时预览：把一整局游戏画面按屏幕的比例缩小放进来，自己打一张随机谱（{@link PreviewRun}），
 * 只看不点；按当前的设置绘制，分数在左上角，「实时预览」四个字在右下角。
 *
 * <p>Live preview of GAMEPLAY VISUALS (DESIGN §9.3; rewritten on Kelvin 2026-10-18 19:52: 「现在的实时预览界面失真很严重，
 * combo没有出现，然后判定区提示还在之前的那种0.1倍屏幕高度的旧版判定区。重写那一段的逻辑」). A whole game screen, at the
 * screen's own shape and scaled to fit the bounds it is given (centred, so nothing is stretched), plays itself:
 * {@link PreviewRun} drives the real engine and renderers with the diver's current settings, so lines, notes,
 * MULTI-PRESS ASSIST, the zone hint (±0.4 of the height), hit effects, COMBO, the judgement word and the score look as
 * in a game. The panel draws the depth gradient under it with the BACKGROUND DIM scrim, maps the run's world into
 * itself with the batch transform and one scissor, and frames it; the score sits top-left, as in a game, and the
 * caption LIVE PREVIEW bottom-right (19:52). It takes no taps (19:52: 「live preview就不要支持点击了，只看就行了」).
 * The entrance fade applies to the panel, its frame and caption; the run itself shows at once. No allocation per
 * frame (the run allocates a chart per round).
 */
public final class LivePreview {
    private static final CharSequence CAPTION = Lang.of("LIVE PREVIEW", "实时预览");

    private final Main game;
    private final Draw draw;
    private final Fonts fonts;
    private final PreviewRun run;
    private final Matrix4 saved = new Matrix4(), local = new Matrix4();
    private final Rectangle clip = new Rectangle(), scissor = new Rectangle();
    private final Color c1 = new Color(), c2 = new Color();
    private float x, y, w = 600f, h = 340f;

    /** Creates the preview. */
    public LivePreview(Main game) {
        this.game = game;
        this.draw = game.draw();
        this.fonts = game.fonts();
        run = new PreviewRun(game);
    }

    /** Fits the panel, at the screen's shape, into the rect {@code (ax, ay, aw, ah)} (bottom-left origin), centred. */
    public void setBounds(float ax, float ay, float aw, float ah) {
        float shape = Ui.worldW() / Ui.worldH();
        if (aw > ah * shape) {
            h = ah;
            w = ah * shape;
        } else {
            w = aw;
            h = aw / shape;
        }
        x = ax + (aw - w) * 0.5f;
        y = ay + (ah - h) * 0.5f;
    }

    /** Starts a new round and clears the shared particles. */
    public void reset() {
        game.hitFx().clear();
        run.restart();
    }

    /** Advances the run. */
    public void update(float dt) {
        run.update(dt);
    }

    /** Draws the preview (the batch is drawing in world units). */
    public void draw(Batch b, Viewport viewport, float alpha) {
        if (alpha <= 0.002f) return;
        Profile p = game.profiles().active();
        float dim = p != null && p.settings != null ? p.settings.backgroundDim : 0.35f;
        float depth = game.playerDepth();
        Theme.depthBottom(depth, c1);
        Theme.depthTop(depth, c2);
        Theme.mix(c1, Theme.ABYSS_BLACK, 0.25f, c1);
        draw.rectGradientV(x, y, w, h, c1, c2, alpha);
        draw.rect(x, y, w, h, Theme.ABYSS_BLACK, dim * alpha);

        saved.set(b.getTransformMatrix());
        clip.set(x, y, w, h);
        viewport.calculateScissors(saved, clip, scissor);
        b.flush();
        boolean pushed = ScissorStack.pushScissors(scissor);
        float s = w / Ui.worldW();
        local.set(saved).translate(x, y, 0f).scale(s, s, 1f).translate(-Ui.left(), -Ui.bottom(), 0f);
        b.setTransformMatrix(local);
        run.draw(b);
        b.setTransformMatrix(saved);
        b.flush();
        if (pushed) ScissorStack.popScissors();

        draw.roundRectStroke(x, y, w, h, 6f, 1.5f, Theme.STROKE, alpha);
        float k = Ui.uiScale();
        fonts.draw(b, Fonts.Role.UI_LABEL, 18f * k, CAPTION, x + w - 18f * k, y + 18f * k, Align.right | Align.bottom,
            Theme.INK2, 0.9f * alpha);
    }
}
