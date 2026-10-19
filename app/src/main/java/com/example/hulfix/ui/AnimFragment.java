package com.example.hulfix.ui;

import android.widget.LinearLayout;

import com.example.hulfix.core.Settings;
import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;

/**
 * 动画与手势页：入场/退场动画类型、方向、时长、弹性；手势开关与阈值；
 * 底部带实时预览（应用当前所有设置）。
 */
public class AnimFragment extends BaseSettingsFragment {

    private StylePreviewView mPreview;

    @Override
    protected void buildUI(LinearLayout container) {
        // ==================== 入场 ====================
        SettingsViews.addSectionHeader(container, "弹出动画");
        SettingsViews.addSegmentedItem(container, "入场方式",
                java.util.Arrays.asList("跟随样式", "滑入", "缩放", "弹性", "淡入"),
                prefInt("hulfix_enter_anim", Settings.ANIM_FOLLOW_STYLE),
                index -> store.putInt("hulfix_enter_anim", index));
        SettingsViews.addSegmentedItem(container, "弹入方向",
                java.util.Arrays.asList("跟随位置", "上", "下", "左", "右"),
                prefInt("hulfix_enter_dir", Settings.DIR_AUTO),
                index -> store.putInt("hulfix_enter_dir", index));
        SettingsViews.addSliderItem(container, "入场时长",
                100, 800, 20, prefInt("hulfix_enter_duration", 260),
                "ms", v -> store.putInt("hulfix_enter_duration", Math.round(v)));
        SettingsViews.addSliderItem(container, "弹性强度",
                0.3f, 2.0f, 0.1f, prefFloat("hulfix_enter_overshoot", 1.0f),
                "x", v -> store.putFloat("hulfix_enter_overshoot", v));
        SettingsViews.addSwitchItem(container, "内容错落入场",
                "图标 → 标题 → 正文依次浮现", prefBoolean("hulfix_stagger", true),
                v -> store.putBoolean("hulfix_stagger", v));

        // ==================== 退场 ====================
        SettingsViews.addSectionHeader(container, "收回动画");
        SettingsViews.addSegmentedItem(container, "退场方式",
                java.util.Arrays.asList("跟随样式", "滑动", "缩放", "淡出"),
                prefInt("hulfix_exit_anim", Settings.ANIM_FOLLOW_STYLE),
                index -> store.putInt("hulfix_exit_anim", index));
        SettingsViews.addSliderItem(container, "退场时长",
                100, 800, 20, prefInt("hulfix_exit_duration", 240),
                "ms", v -> store.putInt("hulfix_exit_duration", Math.round(v)));
        SettingsViews.addNote(container,
                "退场方式与时长同时作用于超时消失与滑动消失。");

        // ==================== 手势 ====================
        SettingsViews.addSectionHeader(container, "手势");
        SettingsViews.addSwitchItem(container, "左右滑动消失",
                "向左或向右划掉通知", prefBoolean("hulfix_gesture_h", true),
                v -> store.putBoolean("hulfix_gesture_h", v));
        SettingsViews.addSwitchItem(container, "上下滑动",
                "向上划掉 / 向下展开通知栏", prefBoolean("hulfix_gesture_v", true),
                v -> store.putBoolean("hulfix_gesture_v", v));
        SettingsViews.addSwitchItem(container, "下拉展开通知栏",
                "在悬浮窗上向下滑动", prefBoolean("hulfix_gesture_pulldown", true),
                v -> store.putBoolean("hulfix_gesture_pulldown", v));
        SettingsViews.addSwitchItem(container, "点击打开",
                "点按悬浮窗打开对应通知", prefBoolean("hulfix_gesture_tap", true),
                v -> store.putBoolean("hulfix_gesture_tap", v));
        SettingsViews.addSwitchItem(container, "长按忽略",
                "长按 0.5 秒直接划掉并记入忽略", prefBoolean("hulfix_gesture_longpress", false),
                v -> store.putBoolean("hulfix_gesture_longpress", v));
        SettingsViews.addSliderItem(container, "滑动消失距离",
                60, 300, 10, prefFloat("hulfix_swipe_threshold", 140f),
                "px", v -> store.putFloat("hulfix_swipe_threshold", v));
        SettingsViews.addSliderItem(container, "快移速度阈值",
                100, 1000, 50, prefFloat("hulfix_fling_velocity", 200f),
                "px/s", v -> store.putFloat("hulfix_fling_velocity", v));

        // ==================== 预览 ====================
        SettingsViews.addSectionHeader(container, "预览");
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        card.setCardElevation(SettingsViews.dp(requireContext(), 1));
        card.setRadius(SettingsViews.dp(requireContext(), 20));
        int pad = SettingsViews.dp(requireContext(), 12);
        card.setPadding(pad, pad, pad, pad);
        mPreview = new StylePreviewView(requireContext());
        mPreview.setMinimumHeight(SettingsViews.dp(requireContext(), 150));
        card.addView(mPreview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(card);
    }

    @Override
    protected void refresh() {
        super.refresh();
        if (mPreview == null) return;
        Settings settings = currentSettings();
        StyleSpec spec = StyleRegistry.byId(settings.styleId, settings.customStylesJson, settings.styleOverridesJson);
        mPreview.setSettings(settings);
        mPreview.setStyle(spec, isDarkNow());
        mPreview.playEnter();
    }
}
