package com.example.hulfix.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.example.hulfix.core.SettingsRepository;

/**
 * 响应 hook 进程（SystemUI）的设置同步请求。
 *
 * <p><b>为什么必须是静态（manifest）注册</b>：SystemUI 重启后，模块 App 进程
 * 很可能不在运行；动态注册的 receiver 随进程死亡而消失。静态 receiver 在收到
 * 显式包名广播时会被 AMS <b>拉起进程</b>后投递——这是"重启后无需打开 App 也能
 * 同步设置"的关键。</p>
 *
 * <p>收到 {@link SettingsRepository#ACTION_REQUEST_SETTINGS} 后立即回送一次
 * 全量设置广播（复用 {@link SettingsStore} 的双广播通道）。</p>
 */
public class SettingsRequestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!SettingsRepository.ACTION_REQUEST_SETTINGS.equals(intent.getAction())) return;
        try {
            // init 幂等：进程刚被拉起时 sContext 尚未就绪，这里负责补上
            SettingsStore.init(context);
            // 立即回送（不等 150ms 合并）：请求方正在等待
            SettingsStore.get(context).notifySystemUiNow();
        } catch (Throwable ignored) {
        }
    }
}
