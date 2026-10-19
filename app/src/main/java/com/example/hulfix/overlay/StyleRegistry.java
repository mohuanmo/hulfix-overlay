package com.example.hulfix.overlay;

import com.example.hulfix.core.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 样式注册表：4 个内置样式模板 + 用户导入的自定义样式 + 逐样式调整层。
 *
 * <ul>
 *   <li><b>standard</b> 标准安卓：AOSP Heads-Up 观感，纯色卡片 + 轻投影，快进快出；</li>
 *   <li><b>m3e_glass</b> Material 3 Expressive 磨砂玻璃：28dp 超大圆角、半透明 tonal 表面、
 *       顶部高光 + 底部接触阴影 + 系统级 BlurBehind；</li>
 *   <li><b>ios_liquid</b> iOS 26 液态玻璃：双层折射描边、强 BlurBehind、白色罩层、
 *       大圆角，"凝华"式入场；</li>
 *   <li><b>neon_pulse</b> 霓虹脉冲（自研）：暗色玻璃 + 青色渐变描边 + 倒计时进度环 +
 *       呼吸脉冲点，一眼可见剩余停留时间。</li>
 * </ul>
 *
 * <p>4 个内置样式是<b>模板</b>：用户对某个样式的微调不写回模板，而是以补丁形式存放在
 * 设置的 {@code hulfix_style_overrides}（{@code {styleId: {partial JSON}}}），
 * 由 {@link #byId(String, String, String)} 在解析时叠加——内置模板因此保持出厂定义，
 * 随时可一键重置调整。</p>
 */
public final class StyleRegistry {

    public static final class StyleIds {
        public static final String STANDARD = "standard";
        public static final String M3E_GLASS = "m3e_glass";
        public static final String IOS_LIQUID = "ios_liquid";
        public static final String NEON_PULSE = "neon_pulse";
    }

    private StyleRegistry() {
    }

    // ==================== 内置样式 ====================

    public static List<StyleSpec> builtins() {
        List<StyleSpec> list = new ArrayList<>();
        list.add(buildStandard());
        list.add(buildM3eGlass());
        list.add(buildIosLiquid());
        list.add(buildNeonPulse());
        return list;
    }

    public static StyleSpec byId(String id, String customStylesJson) {
        return byId(id, customStylesJson, null);
    }

    /**
     * 解析样式：内置模板 / 导入样式 + 逐样式调整层（overrides）。
     *
     * <p><b>模板 + 调整层模型</b>：4 个内置样式是不可变的共享模板（谁都不许改
     * {@link #builtins()} 返回的实例）；用户的微调存放在 {@code overridesJson}
     * （形如 {@code {"ios_liquid": {"cornerRadiusDp": 24}}}），只对<b>对应 id</b>
     * 的样式叠加。叠加顺序：模板 → 导入定义 → 调整补丁 → clamp。</p>
     *
     * <p>补丁是<b>局部</b>的：JSON 里没出现的键保持模板原值，因此用户改一个圆角
     * 不会顺带把内置模板后续演进的其余参数冻结成旧值。</p>
     */
    public static StyleSpec byId(String id, String customStylesJson, String overridesJson) {
        StyleSpec base = byIdRaw(id, customStylesJson);
        if (base == null || overridesJson == null || overridesJson.isEmpty() || id == null) {
            return base;
        }
        JSONObject patch = overridePatch(overridesJson, id);
        if (patch == null || patch.length() == 0) return base;
        try {
            StyleSpec merged = base.copy().applyJson(patch);
            StyleSpec.clamp(merged);
            return merged;
        } catch (JSONException ignored) {
            return base;
        }
    }

    /** 从 {@code {styleId: {partial spec}}} 覆盖表中取出指定样式的补丁；无则 null。 */
    public static JSONObject overridePatch(String overridesJson, String styleId) {
        if (overridesJson == null || overridesJson.isEmpty() || styleId == null) return null;
        try {
            return new JSONObject(overridesJson).optJSONObject(styleId);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static StyleSpec byIdRaw(String id, String customStylesJson) {
        if (id != null && id.startsWith("custom_")) {
            for (StyleSpec s : parseCustom(customStylesJson)) {
                if (id.equals(s.id)) return s;
            }
        }
        for (StyleSpec s : builtins()) {
            if (s.id.equals(id)) return s;
        }
        return buildM3eGlass();
    }

    public static List<StyleSpec> parseCustom(String json) {
        List<StyleSpec> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            for (StyleSpec s : StyleSpec.listFromJson(json)) {
                StyleSpec.clamp(s);
                out.add(s);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static String displayName(String id, String customStylesJson) {
        return byId(id, customStylesJson).name;
    }

    // ---------- 1. 标准安卓 ----------

    private static StyleSpec buildStandard() {
        StyleSpec s = base(StyleIds.STANDARD, "标准安卓", StyleSpec.VARIANT_STANDARD);
        s.author = "HULFix";
        s.cornerRadiusDp = 16f;
        s.strokeWidthDp = 0f;
        s.blurRadiusDp = 0f;
        s.elevationDp = 4f;
        s.paddingHDp = 20f;
        s.paddingVDp = 14f;
        s.iconSizeDp = 26f;
        s.iconPaddingDp = 0f;
        s.titleSizeSp = 15f;
        s.textSizeSp = 13f;
        s.appNameSizeSp = 11f;
        s.iconCircleBg = false;
        s.topHighlight = false;
        s.bottomShadow = false;
        s.doubleStroke = false;
        s.showIcon = true;
        s.showAppName = true;
        // AOSP 浅色
        s.light.bgColors = new int[]{0xFFFFFFFF, 0xFFFFFFFF};
        s.light.textPrimary = 0xDE000000;
        s.light.textSecondary = 0x8A000000;
        s.light.appNameColor = 0x8A000000;
        s.light.accent = 0xFF1A73E8;
        s.light.strokeColor = 0x00000000;
        s.light.iconBgColor = 0x00000000;
        s.light.iconStrokeColor = 0x00000000;
        s.light.ringColor = 0xFF1A73E8;
        s.light.ringTrackColor = 0x1F000000;
        s.light.pulseColor = 0xFF1A73E8;
        // AOSP 深色
        s.dark.bgColors = new int[]{0xFF202124, 0xFF202124};
        s.dark.textPrimary = 0xFFFFFFFF;
        s.dark.textSecondary = 0xB3FFFFFF;
        s.dark.appNameColor = 0xB3FFFFFF;
        s.dark.accent = 0xFF8AB4F8;
        s.dark.strokeColor = 0x00000000;
        s.dark.iconBgColor = 0x00000000;
        s.dark.iconStrokeColor = 0x00000000;
        s.dark.ringColor = 0xFF8AB4F8;
        s.dark.ringTrackColor = 0x33FFFFFF;
        s.dark.pulseColor = 0xFF8AB4F8;
        // 动画：快进快出
        s.enterAnim = Settings.ANIM_SLIDE;
        s.exitAnim = Settings.ANIM_SLIDE;
        s.enterDurationMs = 220;
        s.exitDurationMs = 200;
        s.enterOvershoot = 0.6f;
        return s;
    }

    // ---------- 2. M3 Expressive 磨砂玻璃 ----------

    private static StyleSpec buildM3eGlass() {
        StyleSpec s = base(StyleIds.M3E_GLASS, "M3 磨砂玻璃", StyleSpec.VARIANT_GLASS);
        s.author = "HULFix";
        s.cornerRadiusDp = 28f;   // M3E extra-large
        s.strokeWidthDp = 1f;
        s.blurRadiusDp = 22f;
        s.elevationDp = 6f;
        s.paddingHDp = 22f;
        s.paddingVDp = 16f;
        s.iconSizeDp = 40f;
        s.iconPaddingDp = 6f;
        s.iconCircleBg = true;
        s.topHighlight = true;
        s.bottomShadow = true;
        s.titleSizeSp = 15f;
        s.textSizeSp = 13f;
        s.appNameSizeSp = 11f;
        // 浅色 tonal 表面
        s.light.bgColors = new int[]{0xCCFFFFFF, 0xB8FFFFFF};
        s.light.textPrimary = 0xFF1C1B1F;
        s.light.textSecondary = 0xCC49454F;
        s.light.appNameColor = 0x9979747E;
        s.light.accent = 0xFF6750A4;
        s.light.strokeColor = 0x33FFFFFF;
        s.light.highlightColor = 0x59FFFFFF;
        s.light.shadowColor = 0x1A000000;
        s.light.iconBgColor = 0x1F6750A4;
        s.light.iconStrokeColor = 0x4D6750A4;
        s.light.ringColor = 0xFF6750A4;
        s.light.ringTrackColor = 0x1F6750A4;
        s.light.pulseColor = 0xFF6750A4;
        // 深色 tonal 表面
        s.dark.bgColors = new int[]{0xD924242C, 0xBF24242C};
        s.dark.textPrimary = 0xFFE6E1E9;
        s.dark.textSecondary = 0xBFCAC4D0;
        s.dark.appNameColor = 0x99948F99;
        s.dark.accent = 0xFFD0BCFF;
        s.dark.strokeColor = 0x1FFFFFFF;
        s.dark.highlightColor = 0x40FFFFFF;
        s.dark.shadowColor = 0x66000000;
        s.dark.iconBgColor = 0x33D0BCFF;
        s.dark.iconStrokeColor = 0x66D0BCFF;
        s.dark.ringColor = 0xFFD0BCFF;
        s.dark.ringTrackColor = 0x33D0BCFF;
        s.dark.pulseColor = 0xFFD0BCFF;
        // 动画：M3E 弹性
        s.enterAnim = Settings.ANIM_SPRING;
        s.exitAnim = Settings.ANIM_SLIDE;
        s.enterDurationMs = 320;
        s.exitDurationMs = 240;
        s.enterOvershoot = 1.1f;
        return s;
    }

    // ---------- 3. iOS 26 液态玻璃 ----------

    private static StyleSpec buildIosLiquid() {
        StyleSpec s = base(StyleIds.IOS_LIQUID, "iOS 液态玻璃", StyleSpec.VARIANT_LIQUID);
        s.author = "HULFix";
        s.cornerRadiusDp = 30f;   // liquid 大圆角（近似 squircle）
        s.strokeWidthDp = 1.5f;
        s.blurRadiusDp = 36f;     // 强 BlurBehind
        s.elevationDp = 8f;
        s.paddingHDp = 24f;
        s.paddingVDp = 16f;
        s.iconSizeDp = 38f;
        s.iconPaddingDp = 6f;
        s.iconCircleBg = true;
        s.topHighlight = true;
        s.bottomShadow = true;
        s.doubleStroke = true;    // 双描边折射感
        s.titleSizeSp = 15f;
        s.textSizeSp = 13f;
        s.appNameSizeSp = 11f;
        // 浅色：高透玻璃 + 白罩
        s.light.bgColors = new int[]{0x66FFFFFF, 0x47FFFFFF};
        s.scrimColor = 0x1AFFFFFF;
        s.light.textPrimary = 0xFF000000;
        s.light.textSecondary = 0xCC3C3C43;
        s.light.appNameColor = 0x993C3C43;
        s.light.accent = 0xFF007AFF;
        s.light.strokeColor = 0x80FFFFFF;
        s.light.highlightColor = 0x99FFFFFF;
        s.light.shadowColor = 0x33000000;
        s.light.iconBgColor = 0x1F007AFF;
        s.light.iconStrokeColor = 0x33007AFF;
        s.light.ringColor = 0xFF007AFF;
        s.light.ringTrackColor = 0x33000000;
        s.light.pulseColor = 0xFF007AFF;
        // 深色：暗玻璃 + 微弱白罩
        s.dark.bgColors = new int[]{0x591C1C1E, 0x401C1C1E};
        s.scrimColor = 0x0DFFFFFF;
        s.dark.textPrimary = 0xFFFFFFFF;
        s.dark.textSecondary = 0xCCEBEBF5;
        s.dark.appNameColor = 0x99EBEBF5;
        s.dark.accent = 0xFF0A84FF;
        s.dark.strokeColor = 0x40FFFFFF;
        s.dark.highlightColor = 0x66FFFFFF;
        s.dark.shadowColor = 0x80000000;
        s.dark.iconBgColor = 0x330A84FF;
        s.dark.iconStrokeColor = 0x590A84FF;
        s.dark.ringColor = 0xFF0A84FF;
        s.dark.ringTrackColor = 0x40FFFFFF;
        s.dark.pulseColor = 0xFF0A84FF;
        // 动画：凝华（scale + blur 收敛 + fade）
        s.enterAnim = Settings.ANIM_SCALE;
        s.exitAnim = Settings.ANIM_SCALE;
        s.enterDurationMs = 340;
        s.exitDurationMs = 260;
        s.enterOvershoot = 0.4f;
        return s;
    }

    // ---------- 4. 霓虹脉冲（自研） ----------

    private static StyleSpec buildNeonPulse() {
        StyleSpec s = base(StyleIds.NEON_PULSE, "霓虹脉冲", StyleSpec.VARIANT_NEON);
        s.author = "HULFix";
        s.cornerRadiusDp = 24f;
        s.strokeWidthDp = 2f;      // 渐变描边（渲染器用 SweepGradient 绘制）
        s.blurRadiusDp = 18f;
        s.elevationDp = 10f;
        s.paddingHDp = 20f;
        s.paddingVDp = 14f;
        s.iconSizeDp = 42f;
        s.iconPaddingDp = 8f;
        s.iconCircleBg = true;
        s.topHighlight = true;
        s.bottomShadow = true;
        s.progressRing = true;     // 倒计时进度环
        s.pulseDot = true;         // 呼吸脉冲点
        s.titleSizeSp = 15f;
        s.textSizeSp = 13f;
        s.appNameSizeSp = 11f;
        // 亮暗双模式均使用暗色玻璃底（霓虹=暗场美学），保证两种场景观感一致
        s.light.bgColors = new int[]{0xE6101524, 0xCC101524};
        s.light.textPrimary = 0xFFFFFFFF;
        s.light.textSecondary = 0xB3FFFFFF;
        s.light.appNameColor = 0x80FFFFFF;
        s.light.accent = 0xFF00E5FF;
        s.light.strokeColor = 0x6600E5FF;
        s.light.highlightColor = 0x3300E5FF;
        s.light.shadowColor = 0x4D000000;
        s.light.iconBgColor = 0x3300E5FF;
        s.light.iconStrokeColor = 0xCC00E5FF;
        s.light.ringColor = 0xFF00E5FF;
        s.light.ringTrackColor = 0x33FFFFFF;
        s.light.pulseColor = 0xFF00E5FF;
        s.dark.bgColors = new int[]{0xE60A0A12, 0xCC0A0A12};
        s.dark.textPrimary = 0xFFFFFFFF;
        s.dark.textSecondary = 0xB3FFFFFF;
        s.dark.appNameColor = 0x80FFFFFF;
        s.dark.accent = 0xFF00E5FF;
        s.dark.strokeColor = 0x6600E5FF;
        s.dark.highlightColor = 0x3300E5FF;
        s.dark.shadowColor = 0x4D000000;
        s.dark.iconBgColor = 0x3300E5FF;
        s.dark.iconStrokeColor = 0xCC00E5FF;
        s.dark.ringColor = 0xFF00E5FF;
        s.dark.ringTrackColor = 0x33FFFFFF;
        s.dark.pulseColor = 0xFF00E5FF;
        // 动画：滑入 + 轻微回弹
        s.enterAnim = Settings.ANIM_SLIDE;
        s.exitAnim = Settings.ANIM_SLIDE;
        s.enterDurationMs = 300;
        s.exitDurationMs = 220;
        s.enterOvershoot = 0.8f;
        return s;
    }

    private static StyleSpec base(String id, String name, String variant) {
        StyleSpec s = new StyleSpec();
        s.id = id;
        s.name = name;
        s.variant = variant;
        s.builtin = true;
        s.version = 1;
        return s;
    }
}
