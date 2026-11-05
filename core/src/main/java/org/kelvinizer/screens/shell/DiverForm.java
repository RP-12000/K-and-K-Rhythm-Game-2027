package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.AvatarCatalog;
import org.kelvinizer.profile.AvatarSpec;
import org.kelvinizer.profile.NameWidth;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;

/**
 * 新建玩家卡片的内容：默认头像和名字输入框（2026-10-30 起头像固定为默认的一个，不再随机重抽、不再选配色）；名字留空就用
 * 输入框下方显示的默认名 {@code DIVER-XXXX}。首次设置以前的第 1 步也用它，2026-10-21 起首次设置没有这一步了。
 *
 * <p>The create-player card content (DESIGN §8.3; also the first run's step 1 until Kelvin 2026-10-21 dropped it):
 * the default avatar (Kelvin 2026-10-30 16:37: one avatar for everyone, so no REROLL dice and no palette swatches)
 * and the name field {@code TAP TO NAME YOUR PLAYER}, labelled {@code PLAYER NAME} (Kelvin 2026-10-20 22:30). An
 * empty name keeps the default {@code DIVER-XXXX} shown under the field; the hint hides while the field holds exactly
 * that default.
 *
 * <p>The owner passes its {@link WidgetSet}; the form adds its widgets there once, lays them out
 * in {@link #layout(float, float, float)} and draws the rest in {@link #draw(Batch, float)}.
 * No allocation per frame.
 */
public final class DiverForm {
    /** Width of the name field. */
    private static final float FIELD_W = 560f;

    private final Main game;
    private final Fonts fonts;
    private final AvatarView view;
    private final TextField field;
    private final AvatarSpec avatar = AvatarCatalog.specOf(AvatarCatalog.DEFAULT);
    private final StringBuilder hint = new StringBuilder(48);
    private String defaultName = "DIVER";
    private float avatarSize = 280f;
    private float ax, ay, rightX, bottom, right;

    /**
     * Creates the form and adds its widgets to {@code widgets} (call once); the host registers
     * the name field for keyboard routing.
     */
    public DiverForm(Main game, ShellScreen host, WidgetSet widgets) {
        this.game = game;
        this.fonts = game.fonts();
        view = new AvatarView(game);
        field = widgets.add(new TextField(game.draw(), fonts, Lang.of("TAP TO NAME YOUR PLAYER", "点这里给玩家起名")));
        field.setLabel(Lang.of("PLAYER NAME", "玩家名字")).setMaxLength(ProfileStore.MAX_NAME_CODEPOINTS)
            .setWidthLimit(NameWidth.MAX_UNITS).setAllowEmpty(true);
        field.setListener(new TextField.Listener() {
            @Override
            public void committed(TextField f, String text) {
                view.replace(avatar, name(), false);
            }
        });
        host.registerField(field);
    }

    /**
     * Resets the form: {@code preset} pre-fills the field (null = empty, placeholder shown) and
     * {@code fallback} is the name kept when the field stays empty.
     */
    public void reset(String preset, String fallback) {
        defaultName = fallback != null ? fallback : game.profiles().defaultName();
        field.cancelEdit();
        field.setText(preset != null ? preset : "");
        view.set(avatar, name());
        hint.setLength(0);
        hint.append(Lang.t("EMPTY KEEPS ", "留空就用 ")).append(defaultName);
    }

    /** Commits an inline edit in progress; false when the text was rejected. */
    public boolean commitName() {
        return !field.isEditing() || field.commit();
    }

    /** The chosen name (the default when the field is empty). */
    public String name() {
        String t = field.text();
        return t == null || t.trim().isEmpty() ? defaultName : t;
    }

    /** The name field. */
    public TextField field() {
        return field;
    }

    /** The avatar preview. */
    public AvatarView view() {
        return view;
    }

    /** Natural width at scale 1. */
    public static float naturalWidth() {
        return 280f + 72f + FIELD_W;
    }

    /** Natural height at scale 1. */
    public static float naturalHeight() {
        return 340f;
    }

    /**
     * Places the form with its top-left corner at ({@code x}, {@code topY}) scaled by {@code k}
     * (≤ 1 shrinks the avatar and the gaps; touch targets keep their size).
     */
    public void layout(float x, float topY, float k) {
        avatarSize = 280f * k;
        ax = x + avatarSize * 0.5f;
        ay = topY - 30f * k - avatarSize * 0.5f;
        rightX = x + avatarSize + 72f * k;
        float fh = Button.standardHeight();
        float fieldTop = topY - 36f;
        field.setBounds(rightX, fieldTop - fh, FIELD_W, fh);
        bottom = Math.min(ay - avatarSize * 0.5f, fieldTop - fh - 60f);
        right = rightX + FIELD_W;
    }

    /** Shows or hides (and disables input of) every widget of the form. */
    public void setVisible(boolean on) {
        field.setVisible(on);
        if (!on) field.cancelEdit();
    }

    /** Bottom edge of the laid-out form. */
    public float bottom() {
        return bottom;
    }

    /** Right edge of the laid-out form. */
    public float right() {
        return right;
    }

    /** Advances the avatar animation. */
    public void update(float dt) {
        view.update(dt);
    }

    /** Draws the avatar and the hint (the widgets are drawn by their set). */
    public void draw(Batch b, float alpha) {
        game.draw().glowDot(ax, ay, avatarSize * 1.5f, Avatar.paletteA(avatar.palette), 0.14f * alpha);
        view.draw(b, ax, ay, avatarSize, alpha);
        if (!defaultName.equals(field.text())) {
            fonts.draw(b, Fonts.Role.UI_REGULAR, 18f, hint, rightX + 4f, field.y() - 30f, Align.left, Theme.INK2,
                alpha);
        }
    }
}
