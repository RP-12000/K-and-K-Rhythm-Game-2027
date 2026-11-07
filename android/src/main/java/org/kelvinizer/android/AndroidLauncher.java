package org.kelvinizer.android;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import org.kelvinizer.Main;
import org.kelvinizer.ui.EdgeGuard;

import java.util.Collections;

/**
 * Android 启动入口：全屏沉浸、画面延伸到刘海区域、保持屏幕常亮、不开加速度计和指南针，
 * 安卓 8.0 起给打击音开自己的低延迟输出，安卓 10 起游玩时把屏幕边缘从系统的返回手势要过来，然后创建 {@link Main}。
 *
 * <p>Launches the Android application. On Android 10+ it also keeps the system's edge gestures (Back) off the game
 * view while gameplay asks for it ({@link EdgeGuard}): when the system takes a gesture it cancels every finger on
 * the screen.
 */
public class AndroidLauncher extends AndroidApplication implements EdgeGuard {
    /** The low-latency hit sound output (Android 8.0+), or null. */
    private AndroidHitOutput hits;
    /** The game's view (Android 10+), whose whole area is kept from the system's gestures while guarded. */
    private View gameView;
    /** True while gameplay asks for the screen's edges; written on the render thread, read on the UI thread. */
    private volatile boolean edgesGuarded;
    private final Runnable applyEdges = new Runnable() {
        @Override
        public void run() {
            applyEdgeGuard();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        configuration.useImmersiveMode = true;
        configuration.renderUnderCutout = true;   // backgrounds fill the cutout; content uses safe insets
        configuration.useAccelerometer = false;   // unused: saves battery and sensor wake-ups
        configuration.useCompass = false;
        configuration.useWakelock = true;         // keep the screen on (songs are played hands-busy)
        hits = AndroidHitOutput.create(this);
        initialize(new Main(hits, this), configuration);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && graphics != null) {
            gameView = graphics.getView();
            // the excluded area is in the view's own coordinates: follow every layout (rotation, insets, resize)
            gameView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                                           int oldRight, int oldBottom) {
                    applyEdgeGuard();
                }
            });
        }
    }

    /**
     * Called on the render thread. The system honours at most 200 dp of each edge's height, except while the
     * navigation bar is stickily hidden, which the immersive mode keeps it.
     */
    @Override
    public void guardEdges(boolean on) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || edgesGuarded == on) return;
        edgesGuarded = on;
        runOnUiThread(applyEdges);
    }

    /** Excludes the whole game view from the system's gestures while guarded, nothing otherwise (UI thread). */
    private void applyEdgeGuard() {
        View v = gameView;
        if (v == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (edgesGuarded) {
            v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, v.getWidth(), v.getHeight())));
        } else {
            v.setSystemGestureExclusionRects(Collections.<Rect>emptyList());
        }
    }

    @Override
    protected void onPause() {
        if (hits != null) hits.pauseOutput();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hits != null) hits.resumeOutput();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hits != null) hits.dispose();
        hits = null;
    }
}
