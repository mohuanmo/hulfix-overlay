package com.example.hulfix.overlay;

/**
 * 进程内唯一的 {@link OverlayController} 持有者。
 *
 * <p>Xposed 可能多次实例化模块入口类（不同进程加载、重复 handleLoadPackage），
 * 用 holder 保证控制器单例，所有 hook 汇流到同一个状态机。</p>
 *
 * <p><b>惰性单例</b>：模块入口类在 zygote fork 阶段（主 Looper 就绪前）就会被实例化，
 * 若在此处静态初始化控制器，其构造链上的 {@code new Handler(Looper.getMainLooper())}
 * 会抛 NPE 并以 {@code ExceptionInInitializerError} 杀死整个模块类加载。
 * 因此控制器推迟到第一次被访问时才创建——那一刻必然已在通知 / UI 流程中。</p>
 */
public final class OverlayControllerHolder {

    private static volatile OverlayController sInstance;

    private OverlayControllerHolder() {
    }

    public static OverlayController get() {
        OverlayController instance = sInstance;
        if (instance == null) {
            synchronized (OverlayControllerHolder.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new OverlayController();
                    sInstance = instance;
                }
            }
        }
        return instance;
    }
}
