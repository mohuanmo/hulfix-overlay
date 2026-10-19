package com.example.hulfix.ui;

import android.widget.LinearLayout;

import com.example.hulfix.overlay.LensEngine;

/**
 * AGSL 真折射引擎 + 动态光效引擎的参数区（设置页与可视化编辑器共用）。
 *
 * <p>这些参数是<b>全局</b>的（作用于所有液态玻璃样式），与逐样式调整层
 * （{@link StyleAdjustPanel}）正交：引擎决定"玻璃怎么折射和发光"，
 * 调整层决定"这个样式长什么样"。</p>
 */
public final class LensEngineSection {

    private LensEngineSection() {
    }

    public static void add(LinearLayout container, SettingsStore store,
                           Runnable onPreviewRefresh) {
        if (!LensEngine.isSupported()) {
            SettingsViews.addNote(container,
                    "当前设备不支持 AGSL 真折射（需要 Android 13 / API 33 及以上）。"
                            + "液态玻璃样式已自动使用兼容模拟效果（窗口模糊 + 双描边 + 高光），观感接近且零风险。");
            return;
        }

        SettingsViews.addSectionHeader(container, "真折射引擎（AGSL）");
        SettingsViews.addSwitchItem(container, "启用真折射引擎",
                "液态玻璃启用物理折射：Snell 定律光线偏折 + RGB 色散 + 菲涅尔高光",
                store.prefs().getBoolean("hulfix_lens_enabled", true),
                v -> {
                    store.putBoolean("hulfix_lens_enabled", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "折射强度",
                0f, 48f, 1f, store.prefs().getFloat("hulfix_lens_thickness", 14f), "px",
                v -> {
                    store.putFloat("hulfix_lens_thickness", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "边缘透镜",
                0f, 1f, 0.05f, store.prefs().getFloat("hulfix_lens_edge", 0.75f), "%",
                v -> {
                    store.putFloat("hulfix_lens_edge", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "色散",
                0f, 1f, 0.05f, store.prefs().getFloat("hulfix_lens_dispersion", 0.35f), "%",
                v -> {
                    store.putFloat("hulfix_lens_dispersion", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "镜面高光",
                0f, 2f, 0.1f, store.prefs().getFloat("hulfix_lens_specular", 1f), "",
                v -> {
                    store.putFloat("hulfix_lens_specular", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "折射率 IOR",
                1.01f, 2f, 0.01f, store.prefs().getFloat("hulfix_lens_ior", 1.5f), "",
                v -> {
                    store.putFloat("hulfix_lens_ior", v);
                    onPreviewRefresh.run();
                });

        // ---- 动态光效引擎（实时渲染） ----
        SettingsViews.addSectionHeader(container, "动态光效引擎（实时渲染）");
        SettingsViews.addSwitchItem(container, "启用动态光效",
                "逐帧重算镜面高光、自适应阴影与流动光泽，持续适应玻璃后方内容与交互",
                store.prefs().getBoolean("hulfix_lens_dynamic", true),
                v -> {
                    store.putBoolean("hulfix_lens_dynamic", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "动态强度",
                0f, 1f, 0.05f, store.prefs().getFloat("hulfix_lens_dynamic_strength", 1f), "%",
                v -> {
                    store.putFloat("hulfix_lens_dynamic_strength", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSliderItem(container, "自适应阴影",
                0f, 1f, 0.05f, store.prefs().getFloat("hulfix_lens_adaptive_shadow", 0.35f), "%",
                v -> {
                    store.putFloat("hulfix_lens_adaptive_shadow", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addSwitchItem(container, "高光跟随系统强调色",
                "镜面高光与光泽颜色自动跟随系统强调色（Material You 动态取色）",
                store.prefs().getBoolean("hulfix_lens_accent_follow", false),
                v -> {
                    store.putBoolean("hulfix_lens_accent_follow", v);
                    onPreviewRefresh.run();
                });
        SettingsViews.addNote(container,
                "动态光效在悬浮窗停留期间实时演算：手指按在卡片上时高光跟随触点，"
                        + "消息弹出触发一次光能爆发，拖动时光泽加速流动；环境光以玻璃后方"
                        + "内容的亮度为基准自动适应。关闭后零常驻开销，回到纯静态折射。");

        SettingsViews.addNote(container,
                "引擎参数对所有液态玻璃（liquid）类样式生效；修改即时反映在预览中，"
                        + "真实悬浮窗在下次通知时生效。折射强度越大边缘拉伸越明显，"
                        + "色散控制边缘彩虹强度。");
    }
}
