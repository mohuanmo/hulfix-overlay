package com.example.hulfix.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/**
 * 平台版本兼容小工具。
 *
 * <p>目前只封装 {@link #registerReceiver}：Android 14（API 34）起，targetSdk 34 的进程
 * 调用 {@code registerReceiver} 注册<strong>非系统广播</strong>的接收器时，必须显式传入
 * {@code RECEIVER_EXPORTED} 或 {@code RECEIVER_NOT_EXPORTED}，否则抛
 * {@code SecurityException}。模块代码运行在被 hook 进程（SystemUI，target 34）内，
 * 必须自己带上这个标志；而带标志的五参重载只有 API 33+ 才存在，低版本系统上调用会
 * {@code NoSuchMethodError}，因此需要版本分流。</p>
 */
public final class PlatformCompat {

    private PlatformCompat() {
    }

    /**
     * 注册广播接收器，自动适配 API 33+ 的 exported 标志要求。
     *
     * @param exported true = 需要接收来自其他应用/进程的广播（对应 RECEIVER_EXPORTED）；
     *                 false = 只接收本进程/系统广播（RECEIVER_NOT_EXPORTED，更安全）
     */
    public static void registerReceiver(Context context, BroadcastReceiver receiver,
                                        IntentFilter filter, Handler scheduler,
                                        boolean exported) {
        if (Build.VERSION.SDK_INT >= 33) {
            int flag = exported
                    ? Context.RECEIVER_EXPORTED
                    : Context.RECEIVER_NOT_EXPORTED;
            context.registerReceiver(receiver, filter, null, scheduler, flag);
        } else {
            context.registerReceiver(receiver, filter, null, scheduler);
        }
    }
}
