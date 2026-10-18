package com.example.hulfix.ui;

import android.widget.LinearLayout;

import com.example.hulfix.core.Settings;
import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;

/**
 * 位置页：9 宫格锚点、XY 偏移、宽度/圆角/背景不透明度微调、显示时长与文本行数。
 */
public class PositionFragment extends BaseSettingsFragment {

    private PositionPadView mPad;
    private StylePreviewView mPreview;

    @Override
    protected void buildUI(LinearLayout container) {
        // ==================== 停靠位置 ====================
        SettingsViews.addSectionHeader(container, "停靠位置");
        mPad = new PositionPadView(requireContext());
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        padLp.setMargins(0, 0, 0, SettingsViews.dp(requireContext(), 8));
        container.addView(mPad, padLp);
        mPad.setAnchor(prefInt("hulfix_anchor", Settings.ANCHOR_TOP_RIGHT));
        mPad.setOnAnchorChange(anchor -> store.putInt("hulfix_anchor", anchor));

        SettingsViews.addSliderItem(container, "水平偏移",
                -100, 100, 5, prefInt("hulfix_offset_x", 0),
                "dp", v -> store.putInt("hulfix_offset_x", Math.round(v)));
        SettingsViews.addSliderItem(container, "垂直偏移",
                -100, 100, 5, prefInt("hulfix_offset_y", 0),
                "dp", v -> store.putInt("hulfix_offset_y", Math.round(v)));

        // ==================== 尺寸微调 ====================
        SettingsViews.addSectionHeader(container, "尺寸微调");
        SettingsViews.addSliderItem(container, "宽度",
                0.5f, 1.4f, 0.05f, prefFloat("hulfix_width_scale", 1.0f),
                "x", v -> store.putFloat("hulfix_width_scale", v));
        SettingsViews.addSliderItem(container, "圆角",
                0.3f, 2.0f, 0.05f, prefFloat("hulfix_corner_scale", 1.0f),
                "x", v -> store.putFloat("hulfix_corner_scale", v));
        SettingsViews.addSliderItem(container, "背景不透明度",
                0.3f, 1.0f, 0.05f, prefFloat("hulfix_bg_alpha", 1.0f),
                "x", v -> store.putFloat("hulfix_bg_alpha", v));

        // ==================== 显示 ====================
        SettingsViews.addSectionHeader(container, "显示");
        SettingsViews.addSliderItem(container, "停留时长",
                2000, 15000, 500, prefInt("hulfix_auto_dismiss_ms", 6000),
                "ms", v -> store.putInt("hulfix_auto_dismiss_ms", Math.round(v)));
        SettingsViews.addSliderItem(container, "正文最多行数",
                1, 4, 1, prefInt("hulfix_max_text_lines", 2),
                "行", v -> store.putInt("hulfix_max_text_lines", Math.round(v)));
        SettingsViews.addSwitchItem(container, "显示应用名",
                "在标题上方显示来源应用", prefBoolean("hulfix_show_app_name", true),
                v -> store.putBoolean("hulfix_show_app_name", v));

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
        if (mPad != null) {
            mPad.setAnchor(prefInt("hulfix_anchor", Settings.ANCHOR_TOP_RIGHT));
        }
        if (mPreview != null) {
            Settings settings = currentSettings();
            StyleSpec spec = StyleRegistry.byId(settings.styleId, settings.customStylesJson);
            mPreview.setSettings(settings);
            mPreview.setStyle(spec, isDarkNow());
            mPreview.playEnter();
        }
    }
}
