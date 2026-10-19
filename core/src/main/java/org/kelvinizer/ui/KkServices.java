package org.kelvinizer.ui;

import org.kelvinizer.transition.TransitionLayer;

/**
 * 接口：{@link KkScreen} 依赖的共用服务（字体、绘图、背景、转场、故障字、提示条、头像和背景图缓存），
 * 让画面基类不依赖 {@code Main}。
 * {@code Main} 通过 {@link UiKit} 实现它；这些服务都由 Main 持有，整个应用运行期间只有一份。
 *
 * <p>The small service surface {@link KkScreen} depends on (ARCHITECTURE §2), so the screen base
 * compiles independently of {@code Main}. {@code Main} implements it by delegating to its
 * {@link UiKit}; every instance is owned by Main (never static) and lives for the whole app.
 */
public interface KkServices {
    /** Shared font service. */
    Fonts fonts();

    /** Shared batch, atlas and shape drawer. */
    Draw draw();

    /** The one shared animated background. */
    Backdrop backdrop();

    /** The global transition layer. */
    TransitionLayer transitions();

    /** Glitch text renderer (pooled states). */
    GlitchText glitch();

    /** Global toast queue. */
    Toast toasts();

    /** Avatar ("sonar sigil") cache. */
    Avatar avatars();

    /** Ref-counted image-hook textures (DESIGN §2.8). */
    ArtCache art();
}
