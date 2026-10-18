package com.example.hulfix.core;

import android.os.Handler;
import android.os.Looper;

/**
 * 主线程 {@link Handler} 的懒加载封装。
 *
 * <p><b>为什么需要这个类</b>：Xposed/LSPosed 的模块入口类是在 zygote fork 阶段
 * （{@code nativeForkSystemServer} / {@code nativeSpecializeAppProcess}）内通过
 * {@code Class.newInstance()} 实例化的——此刻进程的主 Looper 还没有创建，
 * {@code Looper.getMainLooper()} 返回 null。任何在构造期执行
 * {@code new Handler(Looper.getMainLooper())} 的字段都会抛 NPE，进而以
 * {@code ExceptionInInitializerError} 炸掉整个模块类的加载，hook 全部无法挂载
 * （表现为"模块完全没反应"）。</p>
 *
 * <p>本类把 Handler 的创建推迟到首次使用：届时调用必然发生在通知 / UI 流程中，
 * 主 Looper 必然已就绪。双检锁保证并发下也只创建一个实例。</p>
 */
public final class MainHandler {

    private volatile Handler mHandler;

    /** 返回主线程 Handler；首次调用时创建（主 Looper 未就绪会抛出明确异常）。 */
    public Handler get() {
        Handler h = mHandler;
        if (h == null) {
            synchronized (this) {
                h = mHandler;
                if (h == null) {
                    Looper looper = Looper.getMainLooper();
                    if (looper == null) {
                        throw new IllegalStateException(
                                "main Looper is not ready (called before Looper.prepareMainLooper?)");
                    }
                    h = new Handler(looper);
                    mHandler = h;
                }
            }
        }
        return h;
    }

    public boolean post(Runnable r) {
        return get().post(r);
    }

    public boolean postDelayed(Runnable r, long delayMs) {
        return get().postDelayed(r, delayMs);
    }

    public void removeCallbacks(Runnable r) {
        get().removeCallbacks(r);
    }
}
