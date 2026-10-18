package com.example.hulfix.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.example.hulfix.overlay.OverlayController;

/**
 * 接收悬浮窗控制器（SystemUI 进程）回报的事件广播：
 * shown / suppressed / dismissed。用于设置页的统计展示、台账镜像与
 * 用户忽略列表的持久化（hook 进程无法写本模块的 SharedPreferences，
 * 因此"划掉即忽略"经本接收器回落库）。</p>
 */
public class StatsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!OverlayController.ACTION_STAT_EVENT.equals(intent.getAction())) return;
        String event = intent.getStringExtra(OverlayController.EXTRA_EVENT);
        if (event == null) return;
        String pkg = intent.getStringExtra(OverlayController.EXTRA_PKG);
        String key = intent.getStringExtra(OverlayController.EXTRA_KEY);
        String title = intent.getStringExtra(OverlayController.EXTRA_TITLE);
        int reason = intent.getIntExtra(OverlayController.EXTRA_REASON, 0);
        SettingsStore.get(context).onStatEvent(event, pkg, key, title, reason);
    }
}
