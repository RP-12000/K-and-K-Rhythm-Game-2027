package org.kelvinizer.screens.shell;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.Main;
import org.kelvinizer.profile.AvatarSpec;
import org.kelvinizer.profile.Depth;
import org.kelvinizer.profile.Profile;
import org.kelvinizer.ui.Avatar;
import org.kelvinizer.ui.Button;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.Icons;
import org.kelvinizer.ui.Lang;
import org.kelvinizer.ui.Theme;
import org.kelvinizer.ui.Ui;

import java.util.Random;

/**
 * 头像（声呐印记）编辑器：大预览加随机重抽，形状、花纹、配色、符号、边框五项各用 ‹ › 切换，改的是当前潜水员的头像。
 * 未解锁的选项也能预览，并显示锁和需要潜到多深；只要选中了未解锁的选项，SAVE 就会抖动、不能保存。
 *
 * <p>Avatar editor (DESIGN §8.5 full, §8.6 unlocks; P1): a large preview with REROLL and ‹ ›
 * steppers for shape, pattern, palette, glyph and frame. Every option can be previewed; locked
 * ones show a lock and the depth needed (metres, since 2026-10-17), and SAVE refuses (shake) while one is selected.
 * Edits the active diver.
 */
public final class AvatarEditorSheet extends Modal {
    private static final int PARAMS = 5;
    private static final CharSequence[] LABELS = Lang.list("SHAPE", "形状", "PATTERN", "图案", "SIGNAL COLOURS", "信号颜色",
        "GLYPH", "字符", "FRAME", "边框");
    // Chinese names of the AvatarSpec options, in the same order
    private static final String[] SHAPES_ZH = {"圆形", "六边形", "圆角方形", "菱形", "盾形"};
    private static final String[] PATTERNS_ZH = {"圆环", "辐条", "波纹", "圆点", "螺旋", "网格", "分裂 K", "星座"};
    private static final String[] GLYPHS_ZH = {"无", "首字母", "K"};
    private static final String[] FRAMES_ZH = {"无", "虚线声呐", "双层", "虹彩"};
    private static final CharSequence REROLL = Lang.of("REROLL", "随机");
    private static final float ROW_H = 112f;

    private final AvatarView preview;
    private final Button[] prev = new Button[PARAMS], next = new Button[PARAMS];
    private final Button dice, cancel, save;
    private final AvatarSpec edit = new AvatarSpec();
    private final Random random = new Random();
    private final StringBuilder lockText = new StringBuilder(16);
    private String name = "";
    /** The diver's depth in whole metres (gates the options). */
    private int depthM;
    private float previewX, previewY, previewSize, rowsX, rowsTop;

    /** Creates the editor for {@code host}. */
    public AvatarEditorSheet(Main game, ShellScreen host) {
        super(game, host);
        preview = new AvatarView(game);
        for (int i = 0; i < PARAMS; i++) {
            final int index = i;
            prev[i] = widgets.add(new Button(draw, fonts, Icons.Icon.CHEVRON_LEFT));
            prev[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    step(index, -1);
                }
            });
            next[i] = widgets.add(new Button(draw, fonts, Icons.Icon.CHEVRON_RIGHT));
            next[i].setOnTap(new Runnable() {
                @Override
                public void run() {
                    step(index, 1);
                }
            });
        }
        dice = widgets.add(new Button(draw, fonts, Icons.Icon.DICE));
        dice.setOnTap(new Runnable() {
            @Override
            public void run() {
                AvatarSpec r = AvatarSpec.random(random, depthM);
                copy(r, edit);
                preview.reroll(edit, name, depthM, random);
            }
        });
        cancel = widgets.add(new Button(draw, fonts, Button.Variant.SECONDARY, Lang.of("CANCEL", "取消")));
        cancel.setOnTap(new Runnable() {
            @Override
            public void run() {
                close();
            }
        });
        save = widgets.add(new Button(draw, fonts, Button.Variant.PRIMARY, Lang.of("SAVE", "保存")));
        save.setOnTap(new Runnable() {
            @Override
            public void run() {
                save();
            }
        });
    }

    private static void copy(AvatarSpec from, AvatarSpec to) {
        to.seed = from.seed;
        to.shape = from.shape;
        to.pattern = from.pattern;
        to.palette = from.palette;
        to.glyph = from.glyph;
        to.frame = from.frame;
    }

    @Override
    protected void onOpened() {
        Profile p = game.profiles().active();
        if (p == null) {
            close();
            return;
        }
        name = p.name != null ? p.name : "";
        depthM = p.depthMeters();
        copy(p.avatar, edit);
        edit.sanitize();
        preview.set(edit, name);
        preview.setOrbit(Depth.orbitRingUnlocked(depthM));
    }

    private int value(int param) {
        switch (param) {
            case 0: return edit.shape;
            case 1: return edit.pattern;
            case 2: return edit.palette;
            case 3: return edit.glyph;
            default: return edit.frame;
        }
    }

    private static int count(int param) {
        switch (param) {
            case 0: return Avatar.SHAPES;
            case 1: return Avatar.PATTERNS;
            case 2: return Avatar.PALETTES;
            case 3: return Avatar.GLYPHS;
            default: return Avatar.FRAMES;
        }
    }

    private static int unlockMeters(int param, int option) {
        switch (param) {
            case 1: return AvatarSpec.PATTERN_UNLOCK_METERS[option];
            case 2: return AvatarSpec.PALETTE_UNLOCK_METERS[option];
            case 3: return AvatarSpec.GLYPH_UNLOCK_METERS[option];
            case 4: return AvatarSpec.FRAME_UNLOCK_METERS[option];
            default: return 0;
        }
    }

    private static final String[] PAIR_NAMES = pairNames(false), PAIR_NAMES_ZH = pairNames(true);

    private static String[] pairNames(boolean zh) {
        String[] names = new String[Math.max(1, Avatar.PALETTES)];
        for (int i = 0; i < names.length; i++) names[i] = zh ? "第 " + (i + 1) + " 组" : "PAIR " + (i + 1);
        return names;
    }

    private static String optionName(int param, int option) {
        boolean zh = Lang.zh();
        switch (param) {
            case 0: return (zh ? SHAPES_ZH : AvatarSpec.SHAPES)[option];
            case 1: return (zh ? PATTERNS_ZH : AvatarSpec.PATTERNS)[option];
            case 2: return (zh ? PAIR_NAMES_ZH : PAIR_NAMES)[Math.max(0, Math.min(PAIR_NAMES.length - 1, option))];
            case 3: return (zh ? GLYPHS_ZH : AvatarSpec.GLYPHS)[option];
            default: return (zh ? FRAMES_ZH : AvatarSpec.FRAMES)[option];
        }
    }

    private void step(int param, int dir) {
        int n = count(param);
        int v = (value(param) + dir + n) % n;
        switch (param) {
            case 0: edit.shape = v; break;
            case 1: edit.pattern = v; break;
            case 2: edit.palette = v; break;
            case 3: edit.glyph = v; break;
            default: edit.frame = v; break;
        }
        preview.replace(edit, name, true);
    }

    private boolean anyLocked() {
        for (int i = 0; i < PARAMS; i++) if (unlockMeters(i, value(i)) > depthM) return true;
        return false;
    }

    private void save() {
        if (anyLocked()) {
            save.shake();
            game.toasts().show(Lang.t("LOCKED OPTIONS · DIVE DEEPER TO UNLOCK", "有未解锁的选项 · 潜得更深才能解锁"), Theme.GOLD);
            return;
        }
        Profile p = game.profiles().active();
        if (p != null) game.profiles().setAvatar(p.id, edit);
        close();
        host.onDiversEdited();
    }

    @Override
    protected float slideDistance() {
        return 120f;
    }

    @Override
    protected void layout(float dy) {
        float bh = Button.standardHeight();
        float availH = Ui.safeTop() - Ui.safeBottom() - 32f;
        float rowH = Math.min(ROW_H, (availH - 110f - bh - 70f) / PARAMS);
        centerCard(1240f, Math.min(availH, 110f + PARAMS * rowH + bh + 70f), dy);
        previewSize = Math.min(320f, w * 0.30f);
        previewX = x + 48f + previewSize * 0.5f;
        previewY = y + h - 110f - previewSize * 0.5f - 10f;
        dice.setCenter(previewX + previewSize * 0.40f, previewY - previewSize * 0.42f);
        rowsX = x + 48f + previewSize + 64f;
        rowsTop = y + h - 100f;
        float rowsW = x + w - 48f - rowsX;
        for (int i = 0; i < PARAMS; i++) {
            float cy = rowsTop - rowH * (i + 0.5f);
            prev[i].setCenter(rowsX + rowsW * 0.42f, cy);
            next[i].setCenter(rowsX + rowsW - 44f, cy);
        }
        float bw = Math.min(340f, (w - 96f - 32f) * 0.5f);
        cancel.setBounds(x + w - 48f - 2f * bw - 32f, y + 32f, bw, bh);
        save.setBounds(x + w - 48f - bw, y + 32f, bw, bh);
    }

    @Override
    protected void onUpdate(float dt) {
        preview.update(dt);
    }

    @Override
    protected void drawContent(Batch b, float alpha) {
        drawTitle(b, Lang.t("SONAR SIGIL", "声呐徽记"), alpha);
        boolean locked = anyLocked();
        draw.glowDot(previewX, previewY, previewSize * 1.5f, Avatar.paletteA(edit.palette), 0.14f * alpha);
        preview.draw(b, previewX, previewY, previewSize, alpha * (locked ? 0.55f : 1f));
        fonts.draw(b, Fonts.Role.UI_LABEL, 16f, REROLL, dice.centerX(), dice.y() - 22f, Align.center, Theme.INK2,
            alpha);
        for (int i = 0; i < PARAMS; i++) {
            float cy = prev[i].centerY();
            fonts.draw(b, Fonts.Role.UI_LABEL, 18f, LABELS[i], rowsX, cy, Align.left | Fonts.VCENTER,
                Theme.INK2, alpha);
            int v = value(i);
            int need = unlockMeters(i, v);
            float mid = (prev[i].centerX() + next[i].centerX()) * 0.5f;
            float span = next[i].centerX() - prev[i].centerX() - 96f;
            boolean lock = need > depthM;
            if (lock) {
                lockText.setLength(0);
                ShellFormat.appendDepth(lockText, need);
                fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f, optionName(i, v), mid, cy + 14f, Align.center | Fonts.VCENTER,
                    span, 0.6f, Theme.INK3, alpha);
                Icons.draw(draw.shapes(), Icons.Icon.LOCK, mid - 30f, cy - 22f, 22f, Theme.GOLD, alpha);
                fonts.draw(b, Fonts.Role.NUMBER, 18f, lockText, mid - 12f, cy - 22f, Align.left | Fonts.VCENTER,
                    Theme.GOLD, alpha);
            } else if (i == 2) {
                draw.disc(mid - 70f, cy, 18f, Avatar.paletteA(v), alpha);
                draw.disc(mid - 70f, cy, 8f, Avatar.paletteB(v), alpha);
                fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f, optionName(i, v), mid + 16f, cy, Align.center | Fonts.VCENTER,
                    span - 70f, 0.6f, Theme.INK1, alpha);
            } else {
                fonts.drawFit(b, Fonts.Role.UI_BOLD, 22f, optionName(i, v), mid, cy, Align.center | Fonts.VCENTER,
                    span, 0.6f, Theme.INK1, alpha);
            }
            if (i < PARAMS - 1) {
                float ly = cy - (prev[i].centerY() - prev[i + 1].centerY()) * 0.5f;
                draw.rect(rowsX, ly, x + w - 48f - rowsX, 1f, Theme.STROKE, alpha);
            }
        }
        if (locked) {
            fonts.drawFit(b, Fonts.Role.UI_REGULAR, 18f, Lang.t("PREVIEW · LOCKED OPTIONS CANNOT BE SAVED YET",
                "预览 · 未解锁的选项暂时不能保存"), x + 48f,
                y + 32f + Button.standardHeight() * 0.5f, Align.left | Fonts.VCENTER, cancel.x() - x - 72f, 0.7f,
                Theme.GOLD, alpha);
        }
    }
}
