package org.kelvinizer.screens.result;

/**
 * 结算画面（DIVE LOG）的时间线：固定的出场节拍，随成绩变化的深度、贝壳和解锁头像外观阶段，以及一次性事件。
 * 第一次点击会加速播放剩余动画（约 1.2 秒播完，关键停顿点保持原速），第二次点击直接跳到结尾。
 *
 * <p>The DIVE LOG timeline (DESIGN §6.3, §6.4): fixed beats, the variable depth / shells / unlock
 * phases, skip handling with hold points, and one-shot events.
 *
 * <p>{@link #t()} is choreography time in seconds; t = 0 is the moment the ASCENT open starts.
 * Most visuals are pure functions of t, so the screen can be stepped deterministically.
 *
 * <ul>
 *   <li><b>First skip</b> (a tap after the input guard): the time scale becomes
 *       {@code max(6, remaining / 0.75)}, except inside hold points, which keep real speed for
 *       their minimum length (score-land 100 ms, stamp impact 150 ms, the UNLOCKED alert 450 ms). The scale is computed from the timeline
 *       outside the holds, and when the holds left would take more than 0.9 s they shrink
 *       proportionally, so a tap always finishes in about 1.15 s (DESIGN App. D: ≈ 1.2 s).</li>
 *   <li><b>Second skip</b>: jumps to the end; events fire "silently" (state only, no particles,
 *       kicks or haptics).</li>
 *   <li>After the end, t keeps running at real speed (idle loops).</li>
 * </ul>
 *
 * Allocation-free after construction.
 */
public final class Choreo {
    // ------------------------------------------------------------------ fixed beats (§6.3)
    /** Input guard: touches ignored before this. */
    public static final float GUARD = 0.35f;
    /** Jacket slide-in start / duration. */
    public static final float JACKET_T = 0.10f, JACKET_D = 0.50f;
    /** Title decode start / duration and per-char stagger. */
    public static final float TITLE_T = 0.25f, TITLE_D = 0.45f, TITLE_STAGGER = 0.018f;
    /** Artist and chip. */
    public static final float ARTIST_T = 0.30f, ARTIST_D = 0.25f;
    /** Header band: the meta line, AUTO watermark and ghost numeral fade in from here (no type-on since 2026-10-18). */
    public static final float HEADER_T = 0.40f;
    /** Score decode start, lock interval (9 locks since scoring v2, the last at 1.725). */
    public static final float SCORE_T = 0.60f, SCORE_LOCK = 0.125f;
    /** Judgement rows: start, stagger, roll duration. */
    public static final float ROWS_T = 0.80f, ROWS_STAGGER = 0.12f, ROWS_D = 0.90f;
    /** Echo trace sweep. */
    public static final float TRACE_T = 1.10f, TRACE_D = 0.80f;
    /** Early / late line fade. */
    public static final float EL_T = 1.40f, EL_D = 0.20f;
    /** Score lands (hold point). */
    public static final float LAND_T = 1.80f;
    /** Stamp slam start and impact (hold point). */
    public static final float STAMP_T = 2.00f, IMPACT_T = 2.22f;
    /** Shimmer pass (R+ / R / SS+). */
    public static final float SHIMMER_T = 2.40f, SHIMMER_D = 0.60f;
    /** Tiles. */
    public static final float TILES_T = 2.30f, TILES_STAGGER = 0.08f, TILES_D = 0.60f;
    /** Clear badge. */
    public static final float BADGE_T = 2.50f, BADGE_D = 0.50f;
    /** Best line. */
    public static final float BEST_T = 2.70f;
    /** Strip rise. */
    public static final float STRIP_T = 3.00f, STRIP_D = 0.35f;
    /** Depth phase D. */
    public static final float DEPTH_T = 3.20f, DEPTH_D = 0.80f;
    /** Shells phase E: start and length of the old → new count. */
    public static final float SHELLS_T = 3.40f, SHELLS_D = 0.90f;
    /** UNLOCKED alert: gap after the depth and shells phases, and its hold before the buttons. */
    public static final float UNLOCK_GAP = 0.10f, UNLOCK_HOLD = 0.45f;
    /** Earliest buttons time; buttons rise duration and stagger. */
    public static final float BUTTONS_MIN = 4.40f, BUTTONS_D = 0.42f, BUTTONS_STAGGER = 0.08f;
    /** Autoplay strip and buttons. */
    public static final float AUTO_STRIP_T = 2.20f, AUTO_BUTTONS_T = 2.60f;
    /** Minimum real seconds of the hold points while skipping. */
    public static final float HOLD_LAND = 0.10f, HOLD_IMPACT = 0.15f, HOLD_UNLOCK = 0.45f;
    /** Skip budget: real seconds from the first skip to the end, the share outside holds and its floor. */
    public static final float SKIP_BUDGET = 1.15f, SKIP_REST = 0.75f, SKIP_REST_MIN = 0.25f;
    /** Buttons enable this long after a skip completes. */
    public static final float SKIP_BUTTON_DELAY = 0.25f;

    // ------------------------------------------------------------------ events
    /** Event ids. */
    public static final int EV_TITLE = 0, EV_LAND = 1, EV_IMPACT = 2, EV_SHIMMER = 3, EV_STRIP = 4,
        EV_DEPTH = 5, EV_SHELLS = 6, EV_UNLOCK = 7, EV_BUTTONS = 8;
    private static final int EVENTS = EV_BUTTONS + 1;

    private final float[] evT = new float[EVENTS];
    private final boolean[] fired = new boolean[EVENTS];
    private final float[] holdT = new float[3];
    private final float[] holdLen = new float[3];
    private int holds;
    /** Hold length multiplier for the running skip (1, or less when the holds would overrun the budget). */
    private float holdK = 1f;

    private float t, real;
    private boolean started;
    private int skips;
    private float skipScale = 1f;
    private boolean silent;
    private float skipDoneReal = -1f;

    // planned phase times ------------------------------------------------------------------
    private float stripT, depthT = -1f, shellsT = -1f, shellsEnd = -1f, unlockT = -1f, buttonsT, end;
    private boolean hasStamp;

    /**
     * Plans the variable phases for a model and resets the clock (not started).
     */
    public void plan(ResultModel m) {
        t = 0f;
        real = 0f;
        started = false;
        skips = 0;
        skipScale = 1f;
        holdK = 1f;
        silent = false;
        skipDoneReal = -1f;
        for (int i = 0; i < EVENTS; i++) {
            evT[i] = -1f;
            fired[i] = false;
        }
        hasStamp = !m.tutorial;
        stripT = m.autoplay ? AUTO_STRIP_T : STRIP_T;
        float lastEnd = stripT + STRIP_D;
        depthT = -1f;
        if (m.hasDepth) {
            depthT = DEPTH_T;
            lastEnd = depthT + DEPTH_D;
        }
        shellsT = -1f;
        shellsEnd = -1f;
        if (m.hasShells) {
            shellsT = SHELLS_T;
            shellsEnd = shellsT + SHELLS_D;
            lastEnd = Math.max(lastEnd, shellsEnd);
        }
        unlockT = -1f;
        if (m.unlockUp) {
            unlockT = lastEnd + UNLOCK_GAP;
            lastEnd = unlockT + UNLOCK_HOLD;
        }
        if (m.autoplay) buttonsT = AUTO_BUTTONS_T;
        else if (m.tutorial && !m.hasShells) buttonsT = Math.max(3.60f, lastEnd + 0.10f);
        else buttonsT = Math.max(BUTTONS_MIN, lastEnd + 0.10f);
        end = buttonsT + BUTTONS_STAGGER + BUTTONS_D;

        evT[EV_TITLE] = TITLE_T + TITLE_D;
        evT[EV_LAND] = LAND_T;
        evT[EV_IMPACT] = hasStamp ? IMPACT_T : -1f;
        evT[EV_SHIMMER] = hasStamp ? SHIMMER_T : -1f;
        evT[EV_STRIP] = stripT;
        evT[EV_DEPTH] = depthT;
        evT[EV_SHELLS] = shellsT;
        evT[EV_UNLOCK] = unlockT;
        evT[EV_BUTTONS] = buttonsT;

        holds = 0;
        addHold(LAND_T, HOLD_LAND);
        if (hasStamp) addHold(IMPACT_T, HOLD_IMPACT);
        if (unlockT >= 0f) addHold(unlockT, HOLD_UNLOCK);
    }

    private void addHold(float at, float len) {
        holdT[holds] = at;
        holdLen[holds] = len;
        holds++;
    }

    /** Starts the clock at {@code t0} (seconds already elapsed since the open started). */
    public void start(float t0) {
        started = true;
        t = Math.max(0f, t0);
    }

    /** True once {@link #start(float)} ran. */
    public boolean started() {
        return started;
    }

    /**
     * Advances by {@code realDt} real seconds, honouring the skip scale and hold points.
     * Real time always advances (for idle loops and the button delay).
     */
    public void advance(float realDt) {
        real += realDt;
        if (!started) return;
        float rem = realDt;
        for (int guard = 0; rem > 1e-6f && guard < 12; guard++) {
            if (skips == 0 || t >= end) {
                t += rem;
                break;
            }
            float holdEnd = holdEndAt(t);
            if (holdEnd > t) {
                float step = Math.min(rem, holdEnd - t);
                t += step;
                rem -= step;
                continue;
            }
            float limit = Math.min(nextHoldAfter(t), end);
            float need = (limit - t) / skipScale;
            if (rem <= need) {
                t += rem * skipScale;
                rem = 0f;
            } else {
                t = limit;
                rem -= need;
            }
        }
        if (skips > 0 && t >= end && skipDoneReal < 0f) skipDoneReal = real;
    }

    private float holdEndAt(float time) {
        for (int i = 0; i < holds; i++) {
            float e = holdT[i] + holdLen[i] * holdK;
            if (time >= holdT[i] - 1e-5f && time < e) return e;
        }
        return -1f;
    }

    /** Real seconds the holds still ahead of (or around) {@code time} take at full length. */
    private float holdsLeft(float time) {
        float h = 0f;
        for (int i = 0; i < holds; i++) {
            float a = Math.max(time, holdT[i]), b = Math.min(end, holdT[i] + holdLen[i]);
            if (b > a) h += b - a;
        }
        return h;
    }

    private float nextHoldAfter(float time) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < holds; i++) {
            if (holdT[i] > time && holdT[i] < best) best = holdT[i];
        }
        return best;
    }

    /**
     * A skip request (tap or key). The first speeds the rest up, the second jumps to the end.
     *
     * @return true when the request was used (false after the end or before the guard)
     */
    public boolean skip() {
        if (!started || t < GUARD || t >= end) return false;
        skips++;
        if (skips == 1) {
            float h = holdsLeft(t);
            float maxHolds = SKIP_BUDGET - SKIP_REST_MIN;
            holdK = h > maxHolds ? maxHolds / h : 1f;
            h *= holdK;
            float rest = Math.max(SKIP_REST_MIN, Math.min(SKIP_REST, SKIP_BUDGET - h));
            skipScale = Math.max(6f, Math.max(0f, end - t - h) / rest);
        } else {
            jumpToEnd();
        }
        return true;
    }

    /** Jumps straight to the final state (events fire silently). */
    public void jumpToEnd() {
        if (!started) start(0f);
        if (t >= end) return;
        if (skips == 0) skips = 1;
        t = end;
        silent = true;
        if (skipDoneReal < 0f) skipDoneReal = real;
    }

    /**
     * Returns true exactly once when event {@code id} is due. While true, {@link #silent()} says
     * whether the event was passed by a jump (apply state, skip particles and kicks).
     */
    public boolean due(int id) {
        if (fired[id] || evT[id] < 0f || !started || t < evT[id]) return false;
        fired[id] = true;
        return true;
    }

    /** True when due events are being fired after a jump to the end. */
    public boolean silent() {
        return silent;
    }

    /** Clears the silent flag (call after polling the events each frame). */
    public void endFrame() {
        silent = false;
    }

    // ------------------------------------------------------------------ queries
    /** Choreography time (s). */
    public float t() {
        return t;
    }

    /** Real seconds since {@link #plan}. */
    public float real() {
        return real;
    }

    /** True once the whole choreography has played. */
    public boolean done() {
        return started && t >= end;
    }

    /** True while a skip (fast-forward) is running. */
    public boolean skipping() {
        return skips > 0 && t < end;
    }

    /** Number of skip requests so far. */
    public int skips() {
        return skips;
    }

    /** End of the choreography. */
    public float end() {
        return end;
    }

    /** Strip rise start. */
    public float stripT() {
        return stripT;
    }

    /** Depth phase start (−1 when none). */
    public float depthT() {
        return depthT;
    }

    /** Shells phase start (−1 when none). */
    public float shellsT() {
        return shellsT;
    }

    /** Shells phase end (−1 when none). */
    public float shellsEnd() {
        return shellsEnd;
    }

    /** UNLOCKED alert start (−1 when nothing was unlocked). */
    public float unlockT() {
        return unlockT;
    }

    /** Buttons rise start. */
    public float buttonsT() {
        return buttonsT;
    }

    /**
     * True when the buttons accept taps: visible, and at least 250 ms after a skip completed
     * (prevents an accidental RETRY).
     */
    public boolean buttonsEnabled() {
        if (!started || t < buttonsT) return false;
        if (skips == 0) return true;
        return skipDoneReal >= 0f && real >= skipDoneReal + SKIP_BUTTON_DELAY;
    }

    /** True when the stamp is part of this result (not the tutorial). */
    public boolean hasStamp() {
        return hasStamp;
    }
}
