package com.example.hulfix.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.hulfix.core.Settings;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.Locale;

/**
 * 引擎页：冷却引擎总览与全部规则、手动暂停、消息台账管理、场景检测。
 */
public class EngineFragment extends BaseSettingsFragment {

    private TextView mStatusLine;
    private TextView mStatLine;
    private MaterialButtonToggleGroup mPauseGroup;
    private boolean mSyncingPauseGroup;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void buildUI(LinearLayout container) {
        // ==================== 状态 ====================
        SettingsViews.addSectionHeader(container, "引擎状态");
        mStatusLine = new TextView(requireContext());
        mStatLine = new TextView(requireContext());
        com.google.android.material.card.MaterialCardView statusCard =
                new com.google.android.material.card.MaterialCardView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, SettingsViews.dp(requireContext(), 8));
        statusCard.setLayoutParams(lp);
        statusCard.setCardElevation(SettingsViews.dp(requireContext(), 1));
        statusCard.setRadius(SettingsViews.dp(requireContext(), 20));
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsViews.dp(requireContext(), 16);
        box.setPadding(pad, pad, pad, pad);
        mStatusLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        mStatusLine.setTypeface(mStatusLine.getTypeface(), android.graphics.Typeface.BOLD);
        mStatLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        mStatLine.setAlpha(0.75f);
        mStatLine.setPadding(0, SettingsViews.dp(requireContext(), 4), 0, 0);
        box.addView(mStatusLine);
        box.addView(mStatLine);
        statusCard.addView(box);
        container.addView(statusCard);

        SettingsViews.addSwitchItem(container, "启用浮窗模块",
                "总开关：关闭后所有通知都不再弹出悬浮窗（其余设置保留）",
                prefBoolean("hulfix_enabled", true),
                v -> store.putBoolean("hulfix_enabled", v));
        SettingsViews.addSwitchItem(container, "启用冷却引擎",
                "关闭后仅保留场景与名单等硬规则",
                prefBoolean("hulfix_engine_enabled", true),
                v -> store.putBoolean("hulfix_engine_enabled", v));

        // ==================== 暂停 ====================
        SettingsViews.addSectionHeader(container, "暂停弹窗发送");
        mPauseGroup = SettingsViews.addSegmentedItem(container, "立即暂停一段时间",
                java.util.Arrays.asList("恢复", "5 分钟", "15 分钟", "30 分钟", "60 分钟"),
                0, index -> {
                    if (mSyncingPauseGroup) return;
                    switch (index) {
                        case 0:
                            store.clearPause();
                            break;
                        case 1:
                            store.setPause(5 * 60_000L);
                            break;
                        case 2:
                            store.setPause(15 * 60_000L);
                            break;
                        case 3:
                            store.setPause(30 * 60_000L);
                            break;
                        default:
                            store.setPause(60 * 60_000L);
                            break;
                    }
                    refreshStatus();
                });

        // ==================== 冷却规则 ====================
        SettingsViews.addSectionHeader(container, "冷却规则");
        SettingsViews.addSliderItem(container, "全局冷却",
                0, 5000, 100, prefInt("hulfix_global_cooldown_ms", 1000),
                "ms", v -> store.putInt("hulfix_global_cooldown_ms", Math.round(v)));
        SettingsViews.addNote(container, "任意两次弹窗之间的最小间隔。");

        SettingsViews.addSliderItem(container, "同一条消息冷却",
                0, 60000, 500, prefInt("hulfix_key_cooldown_ms", 3000),
                "ms", v -> store.putInt("hulfix_key_cooldown_ms", Math.round(v)));
        SettingsViews.addNote(container, "同一条通知（同 key）两次弹出的最小间隔。");

        SettingsViews.addSliderItem(container, "同一个应用冷却",
                0, 60000, 500, prefInt("hulfix_app_cooldown_ms", 0),
                "ms", v -> store.putInt("hulfix_app_cooldown_ms", Math.round(v)));
        SettingsViews.addNote(container, "同一应用连续来消息时的最小间隔，0 = 不限。");

        SettingsViews.addSliderItem(container, "突发限流（窗口内次数）",
                0, 10, 1, prefInt("hulfix_burst_limit", 3),
                "次", v -> store.putInt("hulfix_burst_limit", Math.round(v)));
        SettingsViews.addSliderItem(container, "突发窗口时长",
                3000, 60000, 1000, prefInt("hulfix_burst_window_ms", 10000),
                "ms", v -> store.putInt("hulfix_burst_window_ms", Math.round(v)));
        SettingsViews.addNote(container,
                "防止消息轰炸：突发窗口内最多弹出 N 次，超出部分进入抑制。");

        SettingsViews.addSliderItem(container, "每分钟上限",
                0, 30, 1, prefInt("hulfix_minute_limit", 0),
                "次/分", v -> store.putInt("hulfix_minute_limit", Math.round(v)));

        // ==================== 用户划掉 ====================
        SettingsViews.addSectionHeader(container, "划掉之后的冷却");
        SettingsViews.addSliderItem(container, "忽略冷却时长",
                1000, 300000, 1000, prefInt("hulfix_user_dismiss_cooldown_ms", 60000),
                "ms", v -> store.putInt("hulfix_user_dismiss_cooldown_ms", Math.round(v)));
        SettingsViews.addSegmentedItem(container, "忽略范围",
                java.util.Arrays.asList("同一条消息", "同一个应用", "全局"),
                prefInt("hulfix_user_dismiss_scope", Settings.SCOPE_KEY),
                index -> store.putInt("hulfix_user_dismiss_scope", index));

        // ==================== 消息更新（同 key 复用） ====================
        SettingsViews.addSectionHeader(container, "消息更新");
        SettingsViews.addNote(container,
                "同一条通知（同 key）更新内容时：若该消息正在划掉冷却期内，沿用既有冷却逻辑"
                        + "（不再重复弹出）；微信 / QQ 等会话通知常用同一 key 更新。"
                        + "通知被从通知栏划掉或应用主动取消时，正在显示的浮窗会同步收起。");

        // ==================== 消息管理 ====================
        SettingsViews.addSectionHeader(container, "消息管理");
        SettingsViews.addSwitchItem(container, "启用消息台账",
                "记录每条消息的弹出/抑制历史（LRU 256 条）",
                prefBoolean("hulfix_ledger_enabled", true),
                v -> store.putBoolean("hulfix_ledger_enabled", v));
        SettingsViews.addSwitchItem(container, "同内容去重",
                "同一条消息被系统重复读出时只弹一次",
                prefBoolean("hulfix_dedup_same_content", true),
                v -> store.putBoolean("hulfix_dedup_same_content", v));
        SettingsViews.addEntryItem(container, "查看消息台账",
                "最近的弹出 / 抑制记录", () ->
                        LedgerDialog.newInstance()
                                .show(getParentFragmentManager(), "ledger"));
        SettingsViews.addEntryItem(container, "已忽略的通知",
                "被划掉且在冷却期内的消息", () ->
                        IgnoredDialog.newInstance()
                                .show(getParentFragmentManager(), "ignored"));
        SettingsViews.addEntryItem(container, "重置今日统计",
                "清零展示 / 抑制 / 划掉计数", () -> {
                    store.resetStats();
                    refreshStatus();
                });

        // ==================== 场景检测 ====================
        SettingsViews.addSectionHeader(container, "场景检测");
        SettingsViews.addSwitchItem(container, "免打扰时不弹",
                "处于免打扰 / 仅闹钟模式", prefBoolean("hulfix_block_dnd", true),
                v -> store.putBoolean("hulfix_block_dnd", v));
        SettingsViews.addSwitchItem(container, "锁屏时不弹",
                "锁屏界面直接抑制", prefBoolean("hulfix_block_keyguard", true),
                v -> store.putBoolean("hulfix_block_keyguard", v));
        SettingsViews.addSwitchItem(container, "通知栏展开时不弹",
                "面板下拉期间抑制", prefBoolean("hulfix_block_panel", true),
                v -> store.putBoolean("hulfix_block_panel", v));
        SettingsViews.addSwitchItem(container, "熄屏时不弹",
                "屏幕关闭后移除并抑制", prefBoolean("hulfix_block_screen_off", true),
                v -> store.putBoolean("hulfix_block_screen_off", v));
        SettingsViews.addSwitchItem(container, "通话中不弹",
                "电话 / 会议语音进行中", prefBoolean("hulfix_block_incall", true),
                v -> store.putBoolean("hulfix_block_incall", v));

        // ==================== 调试 ====================
        SettingsViews.addSectionHeader(container, "调试");
        SettingsViews.addSwitchItem(container, "输出调试日志",
                "在 LSPosed 日志中输出运行信息（排查完毕后建议关闭）",
                prefBoolean("hulfix_debug_log", false),
                v -> store.putBoolean("hulfix_debug_log", v));
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mTicker);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTicker);
    }

    private void refreshStatus() {
        if (mStatusLine == null) return;
        long remain = store.pauseRemainingMs();
        if (remain > 0) {
            mStatusLine.setText("⏸ 已暂停 " + formatDuration(remain));
        } else {
            // 暂停已结束/未设置：把分段按钮同步回“恢复”，避免残留旧选中态造成误解
            if (mPauseGroup != null && mPauseGroup.getChildCount() > 0) {
                View first = mPauseGroup.getChildAt(0);
                if (first != null && mPauseGroup.getCheckedButtonId() != first.getId()) {
                    mSyncingPauseGroup = true;
                    mPauseGroup.check(first.getId());
                    mSyncingPauseGroup = false;
                }
            }
            if (!prefBoolean("hulfix_engine_enabled", true)) {
                mStatusLine.setText("引擎已关闭（仅硬规则生效）");
            } else {
                mStatusLine.setText("● 运行中");
            }
        }
        int[] stats = store.stats();
        int ignored = store.ignoredEntries().size();
        mStatLine.setText(String.format(Locale.US,
                "今日弹出 %d · 抑制 %d · 划掉 %d · 忽略列表 %d 条",
                stats[0], stats[1], stats[2], ignored));
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        if (s >= 60) return (s / 60) + " 分 " + (s % 60) + " 秒";
        return s + " 秒";
    }
}
