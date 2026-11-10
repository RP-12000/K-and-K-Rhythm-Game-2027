package org.kelvinizer.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import org.kelvinizer.Main;

import java.io.File;

/**
 * 桌面启动入口：1280 × 720 的可调窗口（最小 960 × 540），开垂直同步，找得到图标时设置窗口图标，然后创建 {@link Main}。
 * 启动前先调用 {@link StartupHelper}，macOS 等情况下它会另起一个 JVM 来运行游戏。
 *
 * <p>Launches the desktop (LWJGL3) application.
 */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return; // This handles macOS support and helps on Windows.
        createApplication();
    }

    private static Lwjgl3Application createApplication() {
        return new Lwjgl3Application(new Main(), getDefaultConfiguration());
    }

    private static final String ICON = "ic_launcher.png";

    /** @return true when the icon can be loaded as an internal file (working directory or classpath). */
    private static boolean iconAvailable(String path) {
        if (new File(path).isFile()) return true;
        ClassLoader loader = Lwjgl3Launcher.class.getClassLoader();
        return loader != null && loader.getResource(path) != null;
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("K & K");
        //// Vsync limits the frames per second to what your hardware can display, and helps eliminate
        //// screen tearing. This setting doesn't always work on Linux, so the line after is a safeguard.
        configuration.useVsync(true);
        //// Limits FPS to the refresh rate of the currently active monitor, plus 1 to try to match fractional
        //// refresh rates. The Vsync setting above should limit the actual FPS to match the monitor.
        configuration.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1);
        //// If you remove the above line and set Vsync to false, you can get unlimited FPS, which can be
        //// useful for testing performance, but can also be very stressful to some hardware.
        //// You may also need to configure GPU drivers to fully disable Vsync; this can cause screen tearing.

        configuration.setWindowedMode(1280, 720);
        configuration.setWindowSizeLimits(960, 540, -1, -1);
        //// The icon is lwjgl3/src/main/resources/ic_launcher.png (a copy of icons/logo.png). libGDX
        //// throws during window creation on Windows/Linux when the file is missing, so only set it
        //// when it can actually be found (classpath resource or a file in the working directory).
        if (iconAvailable(ICON)) {
            configuration.setWindowIcon(ICON);
        }

        //// This could improve compatibility with Windows machines with buggy OpenGL drivers, Macs
        //// with Apple Silicon that have to emulate compatibility with OpenGL anyway, and more.
        //// This uses the dependency `com.badlogicgames.gdx:gdx-lwjgl3-angle` to function.
        //// You would need to add this line to lwjgl3/build.gradle , below the dependency on `gdx-backend-lwjgl3`:
        ////     implementation "com.badlogicgames.gdx:gdx-lwjgl3-angle:$gdxVersion"
        //// You can choose to add the following line and the mentioned dependency if you want; they
        //// are not intended for games that use GL30 (which is compatibility with OpenGL ES 3.0).
        //// Know that it might not work well in some cases.
//        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 0, 0);

        return configuration;
    }
}
