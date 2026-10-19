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

/**
 * 文字输入框（新建或改名潜水员、地图上的搜索等）：手机上弹出系统输入对话框，桌面上直接在框内编辑。
 * 提交的文字会被清理并限制长度（默认最多 16 个字）；为空或不合规时摇一摇、闪红框并振动，不会提交。
 *
 * <p>Text entry (DESIGN §1.6, used by §8.3 create / rename, §10.1 "name your diver" and the §3.10
 * search field): a styled field that edits through the platform text dialog on mobile
 * ({@code Gdx.input.getTextInput}) and inline on desktop (the LWJGL3 backend has no text
 * dialog). Built on {@link Pressable}, so it has the §1.5 press, hover, focus and "no" shake.
 *
 * <p><b>Look.</b> {@code panel} fill, radius 18, 2-unit {@code ink1 @ .35} outline (2.5-unit
 * {@code groundSig} while editing, {@code #FF5C7A} flash on reject), optional caps label above
 * (UI_LABEL 18 {@code ink2}), value UI 26 {@code ink1} or the placeholder in {@code ink3}, a
 * pencil icon on the right while idle, and a blinking {@code groundSig} caret while editing
 * inline. CJK routes automatically. Height defaults to {@link Button#standardHeight()}.
 *
 * <p><b>Validation.</b> Every submitted string goes through {@link #sanitize(CharSequence, int)}
 * (control characters removed, line breaks and tabs become spaces, trimmed, at most
 * {@link #setMaxLength(int)} code points; default 16 as for diver names). An empty result (unless
 * {@link #setAllowEmpty(boolean)}) or one refused by the {@link Validator} is rejected with the
 * shake, a red outline flash and a medium haptic; inline editing then stays open.
 *
 * <p><b>Inline editing (desktop).</b> The owning screen must route keyboard input while
 * {@link #isEditing()}: {@link KkScreen} does this for fields passed to
 * {@code registerTextField} (ENTER commits, ESC cancels instead of going back, a touch outside
 * commits or reverts). Supported keys: printable characters, BACKSPACE / DELETE (with key repeat),
 * LEFT / RIGHT / HOME / END, Ctrl+V or Shift+INSERT to paste. A tap inside moves the caret.
 *
 * <p><b>Dialog (mobile).</b> The Android backend calls the result back on the GL thread. While the
 * dialog is open {@link #isAwaitingDialog()} is true and further taps are ignored.
 *
 * <p><b>Keyboard (mobile, {@link Mode#KEYBOARD}).</b> No dialog: {@code Gdx.input.openTextInputField}
 * shows the soft keyboard with the platform's one-line field above it (IME input such as pinyin
 * works there). DONE commits through {@link #submit}, BACK keeps the old value; while it is open
 * {@link #isAwaitingDialog()} is true (Kelvin 2026-10-17: renaming should only bring up a keyboard).
 *
 * <p>No allocation per frame: the edit buffer and the measuring buffers are reused; a String is
 * created only when a value is committed.
 */
public class TextField extends Pressable {
    /** How the field edits. */
    public enum Mode {
        /** Platform dialog on Android / iOS, inline elsewhere. */
        AUTO,
        /** Always the platform dialog ({@code Gdx.input.getTextInput}). */
        DIALOG,
        /** Always inline (needs keyboard events routed to the field). */
        INLINE,
        /** The soft keyboard and its one-line field on Android / iOS (no dialog), inline elsewhere. */
        KEYBOARD
    }

    /** Receives committed values. */
    public interface Listener {
        /** Called after a sanitised, valid value was committed (it may equal the old one). */
        void committed(TextField field, String text);
    }

    /** Extra acceptance rule applied to sanitised text (after the empty check). */
    public interface Validator {
        /** True when {@code sanitized} may be committed. */
        boolean isValid(String sanitized);
    }

    /** Corner radius. */
    public static final float RADIUS = 18f;
    /** Value / placeholder cap height. */
    public static final float TEXT_CAP = 26f;
    /** Label cap height. */
    public static final float LABEL_CAP = 18f;
    /** Default maximum length in code points (diver names, DESIGN §8.3). */
    public static final int DEFAULT_MAX_LENGTH = 16;
    private static final float PAD_L = 28f, PAD_R = 28f, ICON = 34f;
    private static final float BLINK = 0.53f;
    private static final float REJECT_FLASH = 0.40f;
    /** Inline editing shrinks long text to this fraction of the cap (≈ 20.8 for 26) before windowing. */
    private static final float MIN_SCALE = 0.8f;
    private static final int BUFFER = 128;

    private final Fonts fonts;
    private final StringBuilder value = new StringBuilder(BUFFER);
    private final StringBuilder edit = new StringBuilder(BUFFER);
    private final StringBuilder view = new StringBuilder(BUFFER);
    private final StringBuilder measure = new StringBuilder(BUFFER);
    private String committed = "";
    private CharSequence label, placeholder;
    private Mode mode = Mode.AUTO;
    private int maxLength = DEFAULT_MAX_LENGTH;
    private boolean allowEmpty;
    private Validator validator;
    private Listener listener;
    private Runnable onRejected;
    private Fonts.Role role = Fonts.Role.UI;
    private float textCap = TEXT_CAP;

    private boolean editing, awaitingDialog;
    private int caret;
    private float caretT, rejectT = REJECT_FLASH;

    // layout cache (inline editing)
    private boolean layoutDirty = true;
    private int layoutGen = -1;
    private float layoutW = -1f;
    private Fonts.Role drawRole = Fonts.Role.UI;
    private float drawCap = TEXT_CAP;
    private int viewStart, viewEnd;
    private float caretOffset;

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

    /** Editing mode (default {@link Mode#AUTO}). */
    public TextField setMode(Mode m) {
        mode = m == null ? Mode.AUTO : m;
        return this;
    }

    /** Maximum length in code points (1..{@value #BUFFER} / 2). */
    public TextField setMaxLength(int codePoints) {
        maxLength = MathUtils.clamp(codePoints, 1, BUFFER / 2);
        return this;
    }

    /** Allows committing an empty value (default false: empty is rejected with a shake). */
    public TextField setAllowEmpty(boolean allow) {
        allowEmpty = allow;
        return this;
    }

    /** Extra validation rule (null = none). */
    public TextField setValidator(Validator v) {
        validator = v;
        return this;
    }

    /** Commit listener (created once; never per frame). */
    public TextField setListener(Listener l) {
        listener = l;
        return this;
    }

    /** Runs after a rejected submit (the shake and flash play regardless). */
    public TextField setOnRejected(Runnable r) {
        onRejected = r;
        return this;
    }

    /** Font role and cap of the value (default UI 26). */
    public TextField setTextStyle(Fonts.Role r, float cap) {
        role = r == null ? Fonts.Role.UI : r;
        textCap = cap;
        layoutDirty = true;
        return this;
    }

    /** Sets the committed value (sanitised to the max length; no listener call; ends editing). */
    public TextField setText(CharSequence text) {
        committed = sanitize(text, maxLength);
        value.setLength(0);
        value.append(committed);
        editing = false;
        layoutDirty = true;
        return this;
    }

    /** The committed value (never null). */
    public String text() {
        return committed;
    }

    /** True while editing inline. */
    public boolean isEditing() {
        return editing;
    }

    /** True while the platform dialog is open. */
    public boolean isAwaitingDialog() {
        return awaitingDialog;
    }

    /** The live edit buffer while editing inline (read only), else the committed value. */
    public CharSequence displayText() {
        return editing ? edit : value;
    }

    // ------------------------------------------------------------------ editing control
    /** Starts editing as a tap would (dialog or inline per {@link Mode}). */
    public void beginEdit() {
        if (!visible || !enabled || editing || awaitingDialog) return;
        if (mode == Mode.KEYBOARD && mobile()) {
            awaitingDialog = true;
            Gdx.input.openTextInputField(keyboardConfig());
            return;
        }
        if (useDialog()) {
            awaitingDialog = true;
            String title = label != null ? label.toString() : (placeholder != null ? placeholder.toString() : "");
            String hint = placeholder != null ? placeholder.toString() : "";
            Gdx.input.getTextInput(dialogListener, title, committed, hint);
            return;
        }
        editing = true;
        edit.setLength(0);
        edit.append(value);
        caret = edit.length();
        caretT = 0f;
        layoutDirty = true;
    }

    /**
     * Submits the inline edit (ENTER). Returns true when committed; a rejected value keeps the
     * field editing.
     */
    public boolean commit() {
        if (!editing) return false;
        if (!submit(edit)) return false;
        editing = false;
        return true;
    }

    /** Ends inline editing, restoring the committed value (ESC). */
    public void cancelEdit() {
        if (!editing) return;
        editing = false;
        layoutDirty = true;
    }

    /**
     * Focus loss (touch outside, screen hidden): commits when valid, otherwise reverts with the
     * reject feedback. Returns true when a value was committed.
     */
    public boolean commitOrRevert() {
        if (!editing) return false;
        if (submit(edit)) {
            editing = false;
            return true;
        }
        editing = false;
        layoutDirty = true;
        return false;
    }

    /**
     * Sanitises and validates {@code raw}; on success stores and reports it. Returns false (with
     * the reject feedback) when it is empty or refused.
     */
    public boolean submit(CharSequence raw) {
        String s = sanitize(raw, maxLength);
        boolean ok = (allowEmpty || !s.isEmpty()) && (validator == null || validator.isValid(s));
        if (!ok) {
            reject();
            return false;
        }
        committed = s;
        value.setLength(0);
        value.append(s);
        layoutDirty = true;
        if (listener != null) listener.committed(this, s);
        return true;
    }

    /** Plays the reject feedback (shake, red outline flash, medium haptic). */
    public void reject() {
        shake();
        rejectT = 0f;
        Haptics.medium();
        if (onRejected != null) onRejected.run();
    }

    private boolean useDialog() {
        if (mode == Mode.DIALOG) return true;
        return mode == Mode.AUTO && mobile();
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
        if (editing && visible && hit(px, py)) {
            placeCaret(px);
            return true;
        }
        return super.touchDown(px, py, ptr);
    }

    /**
     * Key down while editing inline: arrows / HOME / END move the caret, ENTER commits, ESC and
     * BACK cancel, Ctrl+V / Shift+INSERT paste. Returns true when consumed.
     */
    public boolean keyDown(int keycode) {
        if (!editing) return false;
        switch (keycode) {
            case Input.Keys.ENTER:
            case Input.Keys.NUMPAD_ENTER:
                commit();
                return true;
            case Input.Keys.ESCAPE:
            case Input.Keys.BACK:
                cancelEdit();
                return true;
            case Input.Keys.LEFT:
                moveCaret(-1);
                return true;
            case Input.Keys.RIGHT:
                moveCaret(1);
                return true;
            case Input.Keys.HOME:
                setCaret(0);
                return true;
            case Input.Keys.END:
                setCaret(edit.length());
                return true;
            case Input.Keys.V:
                if (ctrl()) paste();
                return true;
            case Input.Keys.INSERT:
                if (shift()) paste();
                return true;
            default:
                return true;
        }
    }

    /** Character typed while editing inline (BACKSPACE = '\b', DELETE = 127). */
    public boolean keyTyped(char c) {
        if (!editing) return false;
        if (c == '\b') {
            deleteBefore();
        } else if (c == 127) {
            deleteAfter();
        } else if (c == '\n' || c == '\r' || c == '\t' || c == 27) {
            // handled in keyDown (ENTER / ESC); tabs are not part of names
        } else if (!Character.isISOControl(c) && !Character.isSurrogate(c) && !ctrl()) {
            insert(c);
        }
        return true;
    }

    private static boolean ctrl() {
        return Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
            || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT) || Gdx.input.isKeyPressed(Input.Keys.SYM));
    }

    private static boolean shift() {
        return Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT));
    }

    private void insert(char c) {
        if (codePointLength(edit) >= maxLength || edit.length() >= BUFFER - 2) {
            shake();
            return;
        }
        edit.insert(caret, c);
        caret++;
        edited();
    }

    private void paste() {
        if (Gdx.app == null || Gdx.app.getClipboard() == null) return;
        String clip = Gdx.app.getClipboard().getContents();
        if (clip == null || clip.isEmpty()) return;
        int room = maxLength - codePointLength(edit);
        for (int i = 0; i < clip.length() && room > 0 && edit.length() < BUFFER - 2; ) {
            int cp = clip.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') cp = ' ';
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT) continue;
            int n = Character.charCount(cp);
            if (n == 1) {
                edit.insert(caret, (char) cp);
            } else {
                edit.insert(caret, Character.highSurrogate(cp));
                edit.insert(caret + 1, Character.lowSurrogate(cp));
            }
            caret += n;
            room--;
        }
        edited();
    }

    private void deleteBefore() {
        if (caret <= 0) return;
        int n = (caret >= 2 && Character.isLowSurrogate(edit.charAt(caret - 1))
            && Character.isHighSurrogate(edit.charAt(caret - 2))) ? 2 : 1;
        edit.delete(caret - n, caret);
        caret -= n;
        edited();
    }

    private void deleteAfter() {
        if (caret >= edit.length()) return;
        int n = (caret + 1 < edit.length() && Character.isHighSurrogate(edit.charAt(caret))
            && Character.isLowSurrogate(edit.charAt(caret + 1))) ? 2 : 1;
        edit.delete(caret, caret + n);
        edited();
    }

    private void moveCaret(int dir) {
        int c = caret;
        if (dir < 0 && c > 0) {
            c--;
            if (c > 0 && Character.isLowSurrogate(edit.charAt(c)) && Character.isHighSurrogate(edit.charAt(c - 1))) c--;
        } else if (dir > 0 && c < edit.length()) {
            c++;
            if (c < edit.length() && Character.isLowSurrogate(edit.charAt(c))) c++;
        }
        setCaret(c);
    }

    private void setCaret(int c) {
        caret = MathUtils.clamp(c, 0, edit.length());
        caretT = 0f;
        layoutDirty = true;
    }

    private void edited() {
        caretT = 0f;
        layoutDirty = true;
    }

    /** Moves the caret to the char boundary nearest to world x (tap inside while editing). */
    private void placeCaret(float px) {
        ensureLayout();
        float left = x + PAD_L;
        int best = viewStart;
        float bestD = Float.MAX_VALUE;
        for (int i = viewStart; i <= viewEnd; i++) {
            if (i > viewStart && i < viewEnd && Character.isLowSurrogate(edit.charAt(i))) continue;
            float cx = left + widthOf(viewStart, i);
            float d = Math.abs(cx - px);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        setCaret(best);
    }

    // ------------------------------------------------------------------ frame
    @Override
    protected void onUpdate(float dt) {
        caretT += dt;
        if (rejectT < REJECT_FLASH) rejectT += dt;
    }

    @Override
    public void draw(Batch b, float alpha) {
        if (!visible) return;
        float a = alpha * alphaMul();
        if (a <= 0.002f) return;
        float s = editing ? 1f : visualScale();
        float cx = centerX() + shakeX(), cy = centerY() + liftY();
        float bw = w * s, bh = h * s;
        float bx = cx - bw * 0.5f, by = cy - bh * 0.5f;

        if (label != null && label.length() > 0) {
            fonts.drawFit(b, Fonts.Role.UI_LABEL, LABEL_CAP, label, x + shakeX(), y + h + 14f, Align.left,
                w, 0.75f, Theme.INK2, a);
        }
        draw.roundRect(bx, by, bw, bh, RADIUS * s, Theme.PANEL, 0.9f * a);
        if (editing) draw.roundRect(bx, by, bw, bh, RADIUS * s, Theme.GROUND_SIG, 0.06f * a);
        if (press > 0f || hover > 0f) {
            draw.roundRect(bx, by, bw, bh, RADIUS * s, Theme.INK1, (0.06f * press + 0.04f * hover) * a);
        }
        float flash = rejectT < REJECT_FLASH ? 1f - rejectT / REJECT_FLASH : 0f;
        Color stroke;
        float strokeA;
        if (editing) {
            stroke = Theme.GROUND_SIG;
            strokeA = 1f;
        } else {
            stroke = Theme.INK1;
            strokeA = Math.min(1f, 0.35f * brightness());
        }
        if (flash > 0f) {
            Theme.mix(stroke, Theme.LOST, flash, c1);
            stroke = c1;
            strokeA = Math.max(strokeA, flash);
        }
        draw.roundRectStroke(bx, by, bw, bh, RADIUS * s, editing ? 2.5f : 2f, stroke, strokeA * a);
        ripple.color(Theme.INK1);
        drawRipple(a);

        float left = bx + PAD_L * s;
        if (editing) {
            drawEditing(b, a, left, cy);
        } else {
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
        drawFocus(bx, by, bw, bh, RADIUS, a);
    }

    private void drawEditing(Batch b, float a, float left, float cy) {
        ensureLayout();
        if (edit.length() == 0) {
            if (placeholder != null && placeholder.length() > 0) {
                float avail = w - PAD_L - PAD_R;
                fonts.drawFit(b, role, textCap, placeholder, left, cy, Align.left | Fonts.VCENTER, avail, 0.7f,
                    Theme.INK3, 0.5f * a);
            }
        } else {
            view.setLength(0);
            view.append(edit, viewStart, viewEnd);
            fonts.draw(b, drawRole, drawCap, view, left, cy, Align.left | Fonts.VCENTER, Theme.INK1, a);
        }
        boolean on = caretT < 0.5f || ((int) ((caretT - 0.5f) / BLINK) & 1) == 0;
        if (on) {
            float ch = drawCap * 1.5f;
            draw.rect(left + caretOffset + (caret > viewStart ? 1f : -1.5f), cy - ch * 0.5f, 3f, ch, Theme.GROUND_SIG, a);
        }
    }

    /** Fits the edit buffer into the field: shrink to {@link #MIN_SCALE}, then window around the caret. */
    private void ensureLayout() {
        if (!layoutDirty && layoutGen == Ui.generation() && layoutW == w) return;
        layoutDirty = false;
        layoutGen = Ui.generation();
        layoutW = w;
        int n = edit.length();
        drawRole = fonts.needsCjk(role, edit) ? Fonts.Role.CJK : role;
        drawCap = textCap;
        viewStart = 0;
        viewEnd = n;
        float avail = w - PAD_L - PAD_R;
        float full = widthOf(0, n);
        if (full > avail && full > 0f) {
            float k = Math.max(MIN_SCALE, avail / full);
            drawCap = textCap * k;
            full *= k;
            if (full > avail) {
                // window: the tail that fits, moved left when the caret is before it
                while (viewStart < n && widthOf(viewStart, viewEnd) > avail) viewStart = nextBoundary(viewStart);
                if (caret < viewStart) {
                    viewStart = caret;
                    viewEnd = viewStart;
                    while (viewEnd < n && widthOf(viewStart, nextBoundary(viewEnd)) <= avail) {
                        viewEnd = nextBoundary(viewEnd);
                    }
                }
            }
        }
        caretOffset = widthOf(viewStart, Math.max(viewStart, Math.min(caret, viewEnd)));
    }

    private int nextBoundary(int i) {
        int j = i + 1;
        if (j < edit.length() && Character.isLowSurrogate(edit.charAt(j))) j++;
        return Math.min(j, edit.length());
    }

    /** Width of {@code edit[from, to)} at the current draw role and cap. */
    private float widthOf(int from, int to) {
        if (to <= from) return 0f;
        measure.setLength(0);
        measure.append(edit, from, to);
        return fonts.width(drawRole, drawCap, measure);
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
