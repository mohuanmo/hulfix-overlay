package com.example.hulfix.overlay;

import android.os.Build;

/**
 * AGSL 真折射引擎门面。
 *
 * <p><b>API 隔离设计</b>：{@link RuntimeShader} / {@code RenderEffect} 仅存在于 API 33+。
 * 本类自身不引用任何 API 31+ 类型，所有高危调用都隔离在 {@link LensEngineImpl} 中；
 * API &lt; 33 时 {@link #tryCreate()} 在引用 Impl 类之前就返回 null，
 * 因此旧设备上 Impl 类永远不会被类加载器加载（也就不会触发校验失败）。</p>
 */
public final class LensEngine {

    private LensEngine() {
    }

    /** 设备是否支持 AGSL 真折射（Android 13 / API 33+）。 */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * 尝试创建引擎实例。
     *
     * @return 可用时返回实现；API 不足或 RuntimeShader 编译失败时返回 null（调用方走降级路径）
     */
    public static LensEngineImpl tryCreate() {
        if (!isSupported()) return null;
        try {
            return LensEngineImpl.create();
        } catch (Throwable t) {
            // 驱动 / Skia 版本问题导致 AGSL 不可用时静默降级，绝不让悬浮窗崩掉
            return null;
        }
    }
}
