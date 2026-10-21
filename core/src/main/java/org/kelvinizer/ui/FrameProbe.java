package org.kelvinizer.ui;

/**
 * 帧时间探测器：背景在某个画面开始渲染后统计帧时间，太慢时让背景自动降到低画质（LOW）。
 * 要连续两个 120 帧的窗口都慢（平均值和中位数都超过 18 ms）才算慢，偶发卡顿不会触发；纯 Java，不分配内存。
 *
 * <p>Frame-time probe behind the backdrop's automatic LOW fallback (DESIGN §2.4: "the average frame
 * time exceeds 18 ms over the first 120 frames the backdrop renders on a screen").
 *
 * <p>Made robust against one-off hitches, because the fallback is persisted:
 * <ul>
 *   <li>the first {@link #SKIP} frames after arming are ignored (screen-change hitches);</li>
 *   <li>each sample is clamped to {@link #SAMPLE_CAP}, so one 300 ms GC pause or asset load adds
 *       at most 50 ms to the window;</li>
 *   <li>a window is slow only when the clamped average is above {@link #BUDGET} <em>and</em> more
 *       than half of its frames are over budget (median above 18 ms);</li>
 *   <li>{@link Result#SLOW} needs {@link #CONFIRM} consecutive slow windows: after the first one
 *       a confirming window starts immediately ({@link Result#RETRY}).</li>
 * </ul>
 * Pure Java, no allocation; feed it {@code Gdx.graphics.getDeltaTime()} once per frame.
 */
public final class FrameProbe {
    /** Frames ignored after arming. */
    public static final int SKIP = 10;
    /** Frames per measuring window. */
    public static final int FRAMES = 120;
    /** Consecutive slow windows needed to report {@link Result#SLOW}. */
    public static final int CONFIRM = 2;
    /** Frame budget in seconds (18 ms). */
    public static final float BUDGET = 0.018f;
    /** Per-sample clamp in seconds. */
    public static final float SAMPLE_CAP = 0.05f;

    /** Outcome of one {@link #sample(float)}. */
    public enum Result {
        /** Not armed, or already finished. */
        IDLE,
        /** Collecting samples. */
        RUNNING,
        /** The window was fast enough; the probe is finished. */
        PASSED,
        /** The window was slow; a confirming window has started. */
        RETRY,
        /** Confirmed slow; the probe is finished (fall back to LOW). */
        SLOW
    }

    private int frames, over, failures;
    private float sum;
    private boolean done = true;

    /** Starts a fresh window (keeps the count of earlier slow windows). */
    public void rearm() {
        frames = 0;
        over = 0;
        sum = 0f;
        done = false;
    }

    /** Stops the probe without a result (e.g. detail is already LOW). */
    public void finish() {
        done = true;
    }

    /** Forgets earlier slow windows (e.g. the player set the detail by hand). */
    public void clearFailures() {
        failures = 0;
    }

    /** Consecutive slow windows so far (0 or 1 while running). */
    public int failures() {
        return failures;
    }

    /** Adds one frame time (seconds) and returns the probe state after it. */
    public Result sample(float dt) {
        if (done) return Result.IDLE;
        frames++;
        if (frames <= SKIP) return Result.RUNNING;
        sum += Math.min(Math.max(dt, 0f), SAMPLE_CAP);
        if (dt > BUDGET) over++;
        if (frames < SKIP + FRAMES) return Result.RUNNING;
        boolean slow = sum / FRAMES > BUDGET && over * 2 > FRAMES;
        if (!slow) {
            failures = 0;
            done = true;
            return Result.PASSED;
        }
        failures++;
        if (failures < CONFIRM) {
            frames = SKIP;
            over = 0;
            sum = 0f;
            return Result.RETRY;
        }
        failures = 0;
        done = true;
        return Result.SLOW;
    }
}
