package org.kelvinizer.ui;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.input.NativeInputConfiguration;
import com.badlogic.gdx.input.TextInputWrapper;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.profile.NameWidth;

/**
 * 文字输入框（新建或改名玩家、重置时输入玩家名等）：点一下弹出手机的系统输入对话框，或者只弹出软键盘。
 * 提交的文字会被清理并限制长度（默认最多 16 个字）；为空或不合规时摇一摇、闪红框并振动，不会提交。
 *
 * <p>Text entry (DESIGN §1.6, used by §8.3 create / rename and §10.1 "name your player"): a styled field that
 * edits through the phone's own text input. Built on {@link Pressable}, so it has the §1.5 press and "no" shake.
 *
 * <p><b>Look.</b> {@code panel} fill, radius 18, 2-unit {@code ink1 @ .35} outline ({@code #FF5C7A} flash on
 * reject), optional caps label above (UI_LABEL 18 {@code ink2}), value UI 26 {@code ink1} or the placeholder in
 * {@code ink3}, and a pencil icon on the right. CJK routes automatically. Height defaults to
 * {@link Button#standardHeight()}.
 *
 * <p><b>Validation.</b> Every submitted string goes through {@link #sanitize(CharSequence, int)}
 * (control characters removed, line breaks and tabs become spaces, trimmed, at most
 * {@link #setMaxLength(int)} code points; default 16 as for player names). An empty result (unless
 * {@link #setAllowEmpty(boolean)}) is rejected with the shake, a red outline flash and a medium haptic.
 *
 * <p><b>Dialog ({@link Mode#DIALOG}, the default).</b> The platform text dialog
 * ({@code Gdx.input.getTextInput}); the Android backend calls the result back on the GL thread. While the
 * dialog is open {@link #isAwaitingDialog()} is true and further taps are ignored.
 *
 * <p><b>Keyboard ({@link Mode#KEYBOARD}).</b> No dialog: {@code Gdx.input.openTextInputField}
 * shows the soft keyboard with the platform's one-line field above it (IME input such as pinyin
 * works there). DONE commits through {@link #submit}, BACK keeps the old value; while it is open
 * {@link #isAwaitingDialog()} is true (Kelvin 2026-10-17: renaming should only bring up a keyboard).
 *
 * <p>Off a phone (the desktop test runs) a tap opens nothing. No allocation per frame; a String is created
 * only when a value is committed.
 */
public class TextField extends Pressable {
    /** How the field edits. */
    public enum Mode {
        /** The platform text dialog ({@code Gdx.input.getTextInput}). */
        DIALOG,
        /** The soft keyboard and its one-line field (no dialog). */
        KEYBOARD
    }

    /** Receives committed values. */
    public interface Listener {
        /** Called after a sanitised, valid value was committed (it may equal the old one). */
        void committed(TextField field, String text);
    }

    /** Corner radius. */
    public static final float RADIUS = 18f;
    /** Value / placeholder cap height. */
    public static final float TEXT_CAP = 26f;
    /** Label cap height. */
    public static final float LABEL_CAP = 18f;
    /** Default maximum length in code points (player names, DESIGN §8.3). */
    public static final int DEFAULT_MAX_LENGTH = 16;
    private static final float PAD_L = 28f, PAD_R = 28f, ICON = 34f;
    private static final float REJECT_FLASH = 0.40f;
    private static final int BUFFER = 128;

    private final Fonts fonts;
    private final StringBuilder value = new StringBuilder(BUFFER);
    private String committed = "";
    private CharSequence label, placeholder;
    private Mode mode = Mode.DIALOG;
    private int maxLength = DEFAULT_MAX_LENGTH;
    private float widthLimit = Float.MAX_VALUE;
    private boolean allowEmpty;
    private Listener listener;
    private Fonts.Role role = Fonts.Role.UI;
    private float textCap = TEXT_CAP;

    private boolean awaitingDialog;
    private float rejectT = REJECT_FLASH;

    private final Color c1 = new Color();

    /** Keyboard mode: the configuration (made on first use) and the text it handed back. */
    private NativeInputConfiguration keyboard;
    private String keyboardText = "";

    private final Input.TextInputListener dialogListener = new Input.TextInputListener() {
        @Override
        public void input(String text) {
            awaitingDialog = false;
            submit(text);
        }

        @Override
        public void canceled() {
            awaitingDialog = false;
        }
    };

    /** Creates a field ({@code 640 × standardHeight}); position it with {@link #setBounds}. */
    public TextField(Draw draw, Fonts fonts, CharSequence placeholder) {
        super(draw);
        this.fonts = fonts;
        this.placeholder = placeholder;
        w = 640f;
        h = Button.standardHeight();
    }

    // ------------------------------------------------------------------ configuration
    /** Caps label drawn above the field; also the title of the platform dialog. Null = none. */
    public TextField setLabel(CharSequence text) {
        label = text;
        return this;
    }

    /** Placeholder shown while empty; also the dialog hint. */
    public TextField setPlaceholder(CharSequence text) {
        placeholder = text;
        return this;
    }

    /** Editing mode (default {@link Mode#DIALOG}). */
    public TextField setMode(Mode m) {
        mode = m == null ? Mode.DIALOG : m;
        return this;
    }

    /** Maximum length in code points (1..{@value #BUFFER} / 2). */
    public TextField setMaxLength(int codePoints) {
        maxLength = MathUtils.clamp(codePoints, 1, BUFFER / 2);
        return this;
    }

    /**
     * Widest value in {@link NameWidth} units (player names: {@link NameWidth#MAX_UNITS}); typing past it shakes,
     * longer text is cut. Unlimited by default.
     */
    public TextField setWidthLimit(float units) {
        widthLimit = units;
        return this;
    }

    /** Allows committing an empty value (default false: empty is rejected with a shake). */
    public TextField setAllowEmpty(boolean allow) {
        allowEmpty = allow;
        return this;
    }

    /** Commit listener (created once; never per frame). */
    public TextField setListener(Listener l) {
        listener = l;
        return this;
    }

    /** Font role and cap of the value (default UI 26). */
    public TextField setTextStyle(Fonts.Role r, float cap) {
        role = r == null ? Fonts.Role.UI : r;
        textCap = cap;
        return this;
    }

    /** Sets the committed value (sanitised to the max length; no listener call). */
    public TextField setText(CharSequence text) {
        committed = fit(sanitize(text, maxLength));
        value.setLength(0);
        value.append(committed);
        return this;
    }

    /** The committed value (never null). */
    public String text() {
        return committed;
    }

    /** True while the platform dialog or the keyboard is open. */
    public boolean isAwaitingDialog() {
        return awaitingDialog;
    }

    // ------------------------------------------------------------------ editing control
    /** Starts editing as a tap would (the dialog or the keyboard per {@link Mode}). */
    public void beginEdit() {
        if (!visible || !enabled || awaitingDialog || !mobile()) return;
        awaitingDialog = true;
        if (mode == Mode.KEYBOARD) {
            Gdx.input.openTextInputField(keyboardConfig());
            return;
        }
        String title = label != null ? label.toString() : (placeholder != null ? placeholder.toString() : "");
        String hint = placeholder != null ? placeholder.toString() : "";
        Gdx.input.getTextInput(dialogListener, title, committed, hint);
    }

    /**
     * Sanitises and validates {@code raw}; on success stores and reports it. Returns false (with
     * the reject feedback) when it is empty.
     */
    public boolean submit(CharSequence raw) {
        String s = fit(sanitize(raw, maxLength));
        boolean ok = allowEmpty || !s.isEmpty();
        if (!ok) {
            reject();
            return false;
        }
        committed = s;
        value.setLength(0);
        value.append(s);
        if (listener != null) listener.committed(this, s);
        return true;
    }

    /** Plays the reject feedback (shake, red outline flash, medium haptic). */
    public void reject() {
        shake();
        rejectT = 0f;
        Haptics.medium();
    }

    private static boolean mobile() {
        if (Gdx.app == null) return false;
        Application.ApplicationType t = Gdx.app.getType();
        return t == Application.ApplicationType.Android || t == Application.ApplicationType.iOS;
    }

    /** The keyboard-mode configuration: starts from the committed value; DONE submits, BACK keeps it. */
    private NativeInputConfiguration keyboardConfig() {
        if (keyboard == null) {
            keyboard = new NativeInputConfiguration();
            keyboard.setType(Input.OnscreenKeyboardType.Default);
            keyboard.setPreventCorrection(true);
            keyboard.setTextInputWrapper(new TextInputWrapper() {
                @Override
                public String getText() {
                    return committed;
                }

                @Override
                public int getSelectionStart() {
                    return committed.length();
                }

                @Override
                public int getSelectionEnd() {
                    return committed.length();
                }

                @Override
                public void writeResults(String text, int selectionStart, int selectionEnd) {
                    keyboardText = text != null ? text : "";
                }
            });
            // called on the GL thread after writeResults
            keyboard.setCloseCallback(new NativeInputConfiguration.NativeInputCloseCallback() {
                @Override
                public boolean onClose(boolean confirmed) {
                    awaitingDialog = false;
                    if (confirmed) submit(keyboardText);
                    return false;
                }
            });
        }
        keyboard.setMaxLength(maxLength);
        keyboard.setPlaceholder(placeholder != null ? placeholder.toString() : "");
        return keyboard;
    }

    /** Closes the keyboard this field opened ({@code confirm}: submit what was typed; else keep the value). */
    public void closeKeyboard(boolean confirm) {
        if (awaitingDialog && mode == Mode.KEYBOARD && Gdx.input != null && Gdx.input.isTextInputFieldOpened()) {
            Gdx.input.closeTextInputField(confirm);
        }
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void onFire() {
        beginEdit();
    }

    @Override
    public boolean touchDown(float px, float py, int ptr) {
        if (awaitingDialog) return visible && hit(px, py);
        return super.touchDown(px, py, ptr);
    }

    /** {@code s} cut to {@link #widthLimit}. */
    private String fit(String s) {
        return widthLimit < Float.MAX_VALUE ? NameWidth.cut(s, widthLimit).trim() : s;
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void onUpdate(float dt) {
        if (rejectT < REJECT_FLASH) rejectT += dt;
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * alphaMul();
        if (a <= 0.002f) return;
        float s = visualScale();
        float cx = centerX() + shakeX(), cy = centerY();
        float bw = w * s, bh = h * s;
        float bx = cx - bw * 0.5f, by = cy - bh * 0.5f;

        if (label != null && label.length() > 0) {
            fonts.drawFit(b, Fonts.Role.UI_LABEL, LABEL_CAP, label, x + shakeX(), y + h + 14f, Align.left,
                w, 0.75f, Theme.INK2, a);
        }
        draw.roundRect(bx, by, bw, bh, RADIUS * s, Theme.PANEL, 0.9f * a);
        if (press > 0f) {
            draw.roundRect(bx, by, bw, bh, RADIUS * s, Theme.INK1, 0.06f * press * a);
        }
        float flash = rejectT < REJECT_FLASH ? 1f - rejectT / REJECT_FLASH : 0f;
        Color stroke = Theme.INK1;
        float strokeA = Math.min(1f, 0.35f * brightness());
        if (flash > 0f) {
            Theme.mix(stroke, Theme.LOST, flash, c1);
            stroke = c1;
            strokeA = Math.max(strokeA, flash);
        }
        draw.roundRectStroke(bx, by, bw, bh, RADIUS * s, 2f, stroke, strokeA * a);
        ripple.color(Theme.INK1);
        drawRipple(a);

        float left = bx + PAD_L * s;
        float avail = bw - (PAD_L + PAD_R + ICON + 16f) * s;
        boolean empty = value.length() == 0;
        CharSequence t = empty ? placeholder : value;
        if (t != null && t.length() > 0) {
            fonts.drawScaled(b, role, textCap, s, t, left, cy, Align.left | Fonts.VCENTER, avail, 0.7f,
                empty ? Theme.INK3 : Theme.INK1, a);
        }
        float ix = bx + bw - (PAD_R + ICON * 0.5f) * s;
        Icons.draw(draw.shapes(), Icons.Icon.PENCIL, ix, cy, ICON * s, Theme.INK2, 0.8f * a);
    }

    // ------------------------------------------------------------------ text helpers
    /**
     * Cleans user text (DESIGN §8.3): line breaks and tabs become spaces, other control and
     * format characters are removed, leading / trailing whitespace (including the ideographic
     * space) is trimmed, and the result is cut to {@code maxCodePoints} code points without
     * splitting a surrogate pair. Null gives "".
     */
    public static String sanitize(CharSequence in, int maxCodePoints) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(in.length(), BUFFER));
        int n = in.length();
        for (int i = 0; i < n; ) {
            int cp = Character.codePointAt(in, i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t' || cp == 0x2028 || cp == 0x2029) cp = ' ';
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) continue;
            if (Character.getType(cp) == Character.SURROGATE) continue;   // lone surrogate
            sb.appendCodePoint(cp);
        }
        int start = 0, end = sb.length();
        while (start < end && isSpace(sb.charAt(start))) start++;
        while (end > start && isSpace(sb.charAt(end - 1))) end--;
        int count = 0, cut = start;
        while (cut < end && count < maxCodePoints) {
            cut += Character.charCount(Character.codePointAt(sb, cut));
            count++;
        }
        end = cut;
        while (end > start && isSpace(sb.charAt(end - 1))) end--;
        return sb.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Number of code points in {@code s} (surrogate pairs count once). */
    public static int codePointLength(CharSequence s) {
        int n = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            if (!(Character.isLowSurrogate(s.charAt(i)) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1)))) n++;
        }
        return n;
    }
}
