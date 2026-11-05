package org.kelvinizer.android;

import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import org.kelvinizer.Main;

/**
 * Android 启动入口：全屏沉浸、画面延伸到刘海区域、保持屏幕常亮、不开加速度计和指南针，
 * 安卓 8.0 起给打击音开自己的低延迟输出，然后创建 {@link Main}。
 *
 * <p>Launches the Android application.
 */
public class AndroidLauncher extends AndroidApplication {
    /** The low-latency hit sound output (Android 8.0+), or null. */
    private AndroidHitOutput hits;

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
        initialize(new Main(hits), configuration);
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