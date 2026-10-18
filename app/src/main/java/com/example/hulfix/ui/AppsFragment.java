package com.example.hulfix.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.hulfix.core.Settings;

import java.util.Set;

/**
 * 应用页：弹窗过滤模式（黑名单 / 白名单）、前台抑制列表、
 * 通知类型过滤（常驻 / 前台服务 / 媒体 / 低优先级频道）。
 */
public class AppsFragment extends BaseSettingsFragment {

    private TextView mBlackSummary;
    private TextView mWhiteSummary;
    private TextView mFgSummary;

    @Override
    protected void buildUI(LinearLayout container) {
        // ==================== 过滤模式 ====================
        SettingsViews.addSectionHeader(container, "过滤模式");
        SettingsViews.addSegmentedItem(container, null,
                java.util.Arrays.asList("黑名单", "白名单"),
                prefInt("hulfix_filter_mode", Settings.FILTER_BLACKLIST),
                index -> store.putInt("hulfix_filter_mode", index));
        SettingsViews.addNote(container,
                "黑名单模式：默认全部允许，命中名单不弹；白名单模式：只有名单内应用才弹。");

        // ==================== 名单管理 ====================
        SettingsViews.addSectionHeader(container, "名单管理");

        mBlackSummary = new TextView(requireContext());
        mWhiteSummary = new TextView(requireContext());
        mFgSummary = new TextView(requireContext());

        View blackEntry = SettingsViews.addEntryItem(container, "黑名单",
                "这些应用的通知不弹", () -> pick("黑名单", "hulfix_blocked_apps"));
        View whiteEntry = SettingsViews.addEntryItem(container, "白名单",
                "仅这些应用的通知允许弹出", () -> pick("白名单", "hulfix_allowed_apps"));
        View fgEntry = SettingsViews.addEntryItem(container, "前台抑制",
                "这些应用处于前台时不弹（游戏、视频、会议…）",
                () -> pick("前台抑制", "hulfix_fg_suppress_apps"));

        // 把“已选 N 个应用”挂进卡片的纵向文本区（标题下方的第三行），
        // 而不是横向行——否则会被挤到箭头右侧的卡片边缘
        attachSummary(blackEntry, mBlackSummary);
        attachSummary(whiteEntry, mWhiteSummary);
        attachSummary(fgEntry, mFgSummary);

        // ==================== 通知类型过滤 ====================
        SettingsViews.addSectionHeader(container, "通知类型过滤");
        SettingsViews.addSwitchItem(container, "跳过常驻通知",
                "FLAG_ONGOING（如音乐播放条、系统状态）",
                prefBoolean("hulfix_skip_ongoing", true),
                v -> store.putBoolean("hulfix_skip_ongoing", v));
        SettingsViews.addSwitchItem(container, "跳过前台服务通知",
                "FLAG_FOREGROUND_SERVICE", prefBoolean("hulfix_skip_fgs", true),
                v -> store.putBoolean("hulfix_skip_fgs", v));
        SettingsViews.addSwitchItem(container, "跳过媒体播放通知",
                "带媒体会话的通知（歌词、播放控制）",
                prefBoolean("hulfix_skip_media", false),
                v -> store.putBoolean("hulfix_skip_media", v));
        SettingsViews.addSwitchItem(container, "仅弹重要通知",
                "频道重要度低于“默认”的不弹",
                prefBoolean("hulfix_skip_low_importance", false),
                v -> store.putBoolean("hulfix_skip_low_importance", v));
    }

    /** 结构：card → row(横向) → [textBox(纵向: 标题/说明), 箭头]；summary 挂进 textBox。 */
    private void attachSummary(View entry, TextView summary) {
        try {
            com.google.android.material.card.MaterialCardView card =
                    (com.google.android.material.card.MaterialCardView) entry;
            LinearLayout row = (LinearLayout) card.getChildAt(0);
            LinearLayout textBox = (LinearLayout) row.getChildAt(0);
            summary.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setAlpha(0.7f);
            summary.setPadding(0, SettingsViews.dp(requireContext(), 2), 0, 0);
            textBox.addView(summary);
        } catch (Throwable ignored) {
            // 结构变化时静默降级：不影响入口项本身的可点击性
        }
    }

    private void pick(String title, String key) {
        Set<String> current = Settings.decodeSet(prefString(key, ""));
        AppPickerDialog.newInstance(title, current, true, selected ->
                store.putStringSet(key, selected))
                .show(getParentFragmentManager(), "picker_" + key);
    }

    @Override
    protected void refresh() {
        super.refresh();
        if (mBlackSummary != null) {
            mBlackSummary.setText(summaryText("hulfix_blocked_apps", "未添加"));
        }
        if (mWhiteSummary != null) {
            mWhiteSummary.setText(summaryText("hulfix_allowed_apps", "未添加"));
        }
        if (mFgSummary != null) {
            mFgSummary.setText(summaryText("hulfix_fg_suppress_apps", "未添加"));
        }
    }

    private String summaryText(String key, String empty) {
        Set<String> set = Settings.decodeSet(prefString(key, ""));
        if (set.isEmpty()) return empty;
        return "已选 " + set.size() + " 个应用";
    }
}
