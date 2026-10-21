package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.AvatarSpec;
import org.kelvinizer.profile.ProfileStore;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.TextField;
import org.kelvinizer.ui.Theme;

import java.util.Random;

/**
 * 新建玩家卡片的内容：头像预览（点头像或骰子随机重抽）、十种配色和名字输入框。后三种配色 10 级解锁；名字留空就用
 * 输入框下方显示的默认名 {@code DIVER-XXXX}。首次设置以前的第 1 步也用它，2026-10-21 起首次设置没有这一步了。
 *
 * <p>The create-player card content (DESIGN §8.3; also the first run's step 1 until Kelvin 2026-10-21 dropped it):
 * an avatar preview with a REROLL dice (the avatar itself also rerolls on tap), ten palette swatches (the
 * last three lock below Lv 10, §8.6) and the name field {@code TAP TO NAME YOUR PLAYER}, labelled {@code PLAYER NAME}
 * (Kelvin 2026-10-20 22:30). An empty name keeps the default {@code DIVER-XXXX} shown under the field; the hint
 * hides while the field holds exactly that default.
 *
 * <p>The owner passes its {@link WidgetSet}; the form adds its widgets there once, lays them out
 * in {@link #layout(float, float, float)} and draws the rest in {@link #draw(Batch, float)}.
 * No allocation per frame.
 */
public final class DiverForm {
    /** Swatch spacing (one touch target). */
    private static final float SWATCH_STEP = 112f;
    private static final int SWATCH_COLS = 5;

    private final Main game;
    private final Draw draw;
    private final Fonts fonts;
    private final AvatarView view;
    private final TextField field;
    private final Button dice;
    private final Tappable avatarTap;
    private final Tappable[] swatches = new Tappable[Avatar.PALETTES];
    private final AvatarSpec avatar = new AvatarSpec();
    private final Random random = new Random();
    private final StringBuilder hint = new StringBuilder(48);
    private final Color tmp = new Color();
    private static final CharSequence REROLL = Lang.of("REROLL", "随机");
    private static final CharSequence COLOURS = Lang.of("SIGNAL COLOURS", "信号颜色");
    private String defaultName = "DIVER";
    /** The diver's depth in whole metres (gates the palettes). */
    private int depthM;
    private float scale = 1f;
    private float avatarSize = 280f;
    private float ax, ay, rightX, rightW, swatchTop, top, bottom, left, right;

    /**
     * Creates the form and adds its widgets to {@code widgets} (call once); the host registers
     * the name field for keyboard routing.
     */
    public DiverForm(Main game, ShellScreen host, WidgetSet widgets) {
        this.game = game;
        this.draw = game.draw();
        this.fonts = game.fonts();
        view = new AvatarView(game);
        avatarTap = widgets.add(new Tappable(draw));
        avatarTap.setOnTap(new Runnable() {
            @Override
            public void run() {
                reroll();
            }
        });
        for (int i = 0; i < swatches.length; i++) {
            final int index = i;
            swatches[i] = widgets.add(new Tappable(draw));
            swatches[i].tag = i;
            swatches[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    pickPalette(index);
                }
            });
        }
        field = widgets.add(new TextField(draw, fonts, Lang.of("TAP TO NAME YOUR PLAYER", "点这里给玩家起名")));
        field.setLabel(Lang.of("PLAYER NAME", "玩家名字")).setMaxLength(ProfileStore.MAX_NAME_CODEPOINTS)
            .setAllowEmpty(true);
        field.setListener(new TextField.Listener() {
            @Override
            public void committed(TextField f, String text) {
                view.replace(avatar, name(), false);
            }
        });
        host.registerField(field);
        dice = widgets.add(new Button(draw, fonts, Icons.Icon.DICE));
        dice.setOnTap(new Runnable() {
            @Override
            public void run() {
                reroll();
            }
        });
    }

    /**
     * Resets the form: {@code preset} pre-fills the field (null = empty, placeholder shown),
     * {@code fallback} is the name kept when the field stays empty, {@code start} the starting
     * avatar (null = random) and {@code depthM} (metres) gates the palettes.
     */
    public void reset(String preset, String fallback, AvatarSpec start, int depthM) {
        this.depthM = Math.max(0, depthM);
        defaultName = fallback != null ? fallback : game.profiles().defaultName();
        field.cancelEdit();
        field.setText(preset != null ? preset : "");
        AvatarSpec a = start != null ? start : AvatarSpec.random(random, this.depthM);
        copy(a, avatar);
        view.set(avatar, name());
        hint.setLength(0);
        hint.append(Lang.t("EMPTY KEEPS ", "留空就用 ")).append(defaultName);
    }

    private static void copy(AvatarSpec from, AvatarSpec to) {
        to.seed = from.seed;
        to.shape = from.shape;
        to.pattern = from.pattern;
        to.palette = from.palette;
        to.glyph = from.glyph;
        to.frame = from.frame;
    }

    /** Rolls a new random avatar (options unlocked at the diver's depth) with the slot machine. */
    public void reroll() {
        AvatarSpec next = AvatarSpec.random(random, depthM);
        copy(next, avatar);
        view.reroll(avatar, name(), depthM, random);
    }

    private void pickPalette(int i) {
        if (AvatarSpec.PALETTE_UNLOCK_METERS[i] > depthM) {
            swatches[i].shake();
            game.toasts().show(ShellFormat.appendDepth(new StringBuilder(Lang.t("UNLOCKS AT ", "解锁深度 ")),
                AvatarSpec.PALETTE_UNLOCK_METERS[i]).toString(), Theme.GOLD);
            return;
        }
        if (avatar.palette == i) return;
        avatar.palette = i;
        view.replace(avatar, name(), true);
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

    /** The chosen avatar (live object; copy it before storing). */
    public AvatarSpec avatar() {
        return avatar;
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
        return 280f + 72f + SWATCH_STEP * SWATCH_COLS;
    }

    /** Natural height at scale 1. */
    public static float naturalHeight() {
        return 492f;
    }

    /**
     * Places the form with its top-left corner at ({@code x}, {@code topY}) scaled by {@code k}
     * (≤ 1 shrinks the avatar and the gaps; touch targets keep their size).
     */
    public void layout(float x, float topY, float k) {
        scale = k;
        left = x;
        top = topY;
        avatarSize = 280f * k;
        ax = x + avatarSize * 0.5f;
        ay = topY - 30f * k - avatarSize * 0.5f;
        avatarTap.setBounds(ax - avatarSize * 0.5f, ay - avatarSize * 0.5f, avatarSize, avatarSize);
        dice.setCenter(ax + avatarSize * 0.40f, ay - avatarSize * 0.40f);
        rightX = x + avatarSize + 72f * k;
        rightW = SWATCH_STEP * SWATCH_COLS;
        float fh = Button.standardHeight();
        float fieldTop = topY - 36f;
        field.setBounds(rightX, fieldTop - fh, rightW, fh);
        float hintY = fieldTop - fh - 30f;
        swatchTop = hintY - 56f;
        for (int i = 0; i < swatches.length; i++) {
            int col = i % SWATCH_COLS, row = i / SWATCH_COLS;
            float cx = rightX + SWATCH_STEP * (col + 0.5f);
            float cy = swatchTop - SWATCH_STEP * (row + 0.5f);
            swatches[i].setBounds(cx - 36f, cy - 36f, 72f, 72f);
        }
        bottom = Math.min(ay - avatarSize * 0.5f, swatchTop - 2f * SWATCH_STEP);
        right = rightX + rightW;
    }

    /** Shows or hides (and disables input of) every widget of the form. */
    public void setVisible(boolean on) {
        avatarTap.setVisible(on);
        dice.setVisible(on);
        field.setVisible(on);
        for (Tappable t : swatches) t.setVisible(on);
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

    /** Draws the avatar, the hint and the swatches (the widgets are drawn by their set). */
    public void draw(Batch b, float alpha) {
        float s = avatarTap.visualScale();
        game.draw().glowDot(ax, ay, avatarSize * 1.5f, Avatar.paletteA(avatar.palette), 0.14f * alpha);
        view.draw(b, ax + avatarTap.shakeX(), ay, avatarSize * s, alpha);
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, REROLL, dice.centerX(), dice.y() - 22f, Align.center, Theme.INK2,
            alpha);
        if (!defaultName.equals(field.text())) {
            fonts.draw(b, Fonts.Role.UI_REGULAR, 18f, hint, rightX + 4f, field.y() - 30f, Align.left, Theme.INK2,
                alpha);
        }
        fonts.draw(b, Fonts.Role.UI_LABEL, 18f, COLOURS, rightX + 4f, swatchTop + 6f, Align.left, Theme.INK2,
            alpha);
        for (int i = 0; i < swatches.length; i++) drawSwatch(swatches[i], i, alpha);
    }

    private void drawSwatch(Tappable t, int i, float alpha) {
        float cx = t.centerX() + t.shakeX(), cy = t.centerY();
        float k = t.visualScale();
        boolean locked = AvatarSpec.PALETTE_UNLOCK_METERS[i] > depthM;
        float a = alpha * (locked ? 0.35f : 1f);
        Color ca = Avatar.paletteA(i), cb = Avatar.paletteB(i);
        if (avatar.palette == i) {
            draw.circle(cx, cy, 46f * k, 3f, Theme.INK1, alpha);
            draw.glowDot(cx, cy, 140f, ca, 0.22f * alpha);
        }
        Theme.brighten(ca, t.brightness(), tmp);
        draw.disc(cx, cy, 34f * k, tmp, a);
        draw.disc(cx, cy, 15f * k, cb, a);
        if (locked) {
            Icons.draw(draw.shapes(), Icons.Icon.LOCK, cx, cy, 30f, Theme.INK1, alpha * 0.9f);
        }
    }
}
