package com.example.hulfix.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.example.hulfix.ui.StyleImportDoc;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

/**
 * 自定义样式导入功能的单元测试（数据层端到端）。
 *
 * <p>覆盖 StyleFragment"从文件 / 粘贴导入"背后的全部纯 Java 逻辑：</p>
 * <ul>
 *   <li>三种文件形态（顶层数组 / 单个对象 / {"styles":[...]} 包装）；</li>
 *   <li>缺字段回落默认值、非法 variant 回落 glass；</li>
 *   <li>越界数值夹紧（clamp）、颜色解析（#RRGGBB / #AARRGGBB / 非法）；</li>
 *   <li>内置规范文档里的示例 JSON 真实可导入（防止文档与实现漂移）；</li>
 *   <li>StyleRegistry.parseCustom + byId 端到端（导入后立即可选）。</li>
 * </ul>
 */
public class StyleImportTest {

    // ==================== 文件形态 ====================

    @Test
    public void listFromJson_singleObject() throws JSONException {
        List<StyleSpec> list = StyleSpec.listFromJson(
                "{\"name\":\"单样式\",\"variant\":\"neon\"}");
        assertEquals(1, list.size());
        assertEquals("单样式", list.get(0).name);
        assertEquals(StyleSpec.VARIANT_NEON, list.get(0).variant);
    }

    @Test
    public void listFromJson_topLevelArray() throws JSONException {
        List<StyleSpec> list = StyleSpec.listFromJson(
                "[{\"name\":\"甲\"},{\"name\":\"乙\"},{\"name\":\"丙\"}]");
        assertEquals(3, list.size());
        assertEquals("甲", list.get(0).name);
        assertEquals("丙", list.get(2).name);
    }

    @Test
    public void listFromJson_wrappedStyles() throws JSONException {
        List<StyleSpec> list = StyleSpec.listFromJson(
                "{\"styles\":[{\"name\":\"包一\"},{\"name\":\"包二\"}]}");
        assertEquals(2, list.size());
        assertEquals("包一", list.get(0).name);
        assertEquals("包二", list.get(1).name);
    }

    @Test
    public void listFromJson_emptyArrayAndNull() throws JSONException {
        assertTrue(StyleSpec.listFromJson("[]").isEmpty());
        assertTrue(StyleSpec.listFromJson((String) null).isEmpty());
    }

    @Test
    public void listFromJson_garbage_throws() {
        assertThrows(JSONException.class, () -> StyleSpec.listFromJson("这不是 JSON"));
    }

    // ==================== 默认值 / 非法值回落 ====================

    @Test
    public void fromJson_missingFields_fallBackToDefaults() throws JSONException {
        StyleSpec s = StyleSpec.fromJson("{}");
        assertEquals("custom", s.id);
        assertEquals("自定义样式", s.name);
        assertEquals("", s.author);
        assertEquals(1, s.version);
        assertEquals(StyleSpec.VARIANT_GLASS, s.variant);
        assertTrue(!s.builtin);
        // 几何默认值
        assertEquals(28f, s.cornerRadiusDp, 0.001f);
        assertEquals(1f, s.strokeWidthDp, 0.001f);
        assertEquals(0f, s.blurRadiusDp, 0.001f);
        assertEquals(20f, s.paddingHDp, 0.001f);
        assertEquals(14f, s.paddingVDp, 0.001f);
        assertEquals(40f, s.iconSizeDp, 0.001f);
        assertEquals(15f, s.titleSizeSp, 0.001f);
        assertEquals(13f, s.textSizeSp, 0.001f);
        assertEquals(64f, s.minHeightDp, 0.001f);
        // 元素开关
        assertTrue(s.showIcon);
        assertTrue(s.showAppName);
        assertTrue(!s.iconCircleBg);
        assertTrue(!s.progressRing);
        assertTrue(!s.doubleStroke);
        assertEquals(2, s.maxTextLines);
        // 动画
        assertEquals(1, s.enterAnim);   // ANIM_SLIDE
        assertEquals(1, s.exitAnim);
        assertEquals(260, s.enterDurationMs);
        assertEquals(240, s.exitDurationMs);
        // 配色默认（深色玻璃底）
        assertEquals(0xF51C1C1E, s.dark.bgColors[0]);
        assertEquals(0xFFFFFFFF, s.dark.textPrimary);
        assertEquals(0xFFA8C7FA, s.dark.accent);
    }

    @Test
    public void fromJson_invalidVariant_fallsBackToGlass() throws JSONException {
        assertEquals(StyleSpec.VARIANT_GLASS,
                StyleSpec.fromJson("{\"variant\":\"bogus\"}").variant);
        assertEquals(StyleSpec.VARIANT_GLASS,
                StyleSpec.fromJson("{\"variant\":\"\"}").variant);
        assertEquals(StyleSpec.VARIANT_STANDARD,
                StyleSpec.fromJson("{\"variant\":\"standard\"}").variant);
        assertEquals(StyleSpec.VARIANT_LIQUID,
                StyleSpec.fromJson("{\"variant\":\"liquid\"}").variant);
        assertEquals(StyleSpec.VARIANT_NEON,
                StyleSpec.fromJson("{\"variant\":\"neon\"}").variant);
    }

    // ==================== clamp（越界夹紧） ====================

    @Test
    public void fromJson_outOfRangeValues_clamped() throws JSONException {
        StyleSpec s = StyleSpec.fromJson("{"
                + "\"cornerRadiusDp\":999,"
                + "\"strokeWidthDp\":-5,"
                + "\"blurRadiusDp\":120,"
                + "\"iconSizeDp\":5,"
                + "\"paddingHDp\":-1,"
                + "\"titleSizeSp\":99,"
                + "\"minHeightDp\":500,"
                + "\"maxTextLines\":99,"
                + "\"enterDurationMs\":5,"
                + "\"exitDurationMs\":99999,"
                + "\"enterOvershoot\":-3"
                + "}");
        assertEquals(48f, s.cornerRadiusDp, 0.001f);
        assertEquals(0f, s.strokeWidthDp, 0.001f);
        assertEquals(60f, s.blurRadiusDp, 0.001f);
        assertEquals(16f, s.iconSizeDp, 0.001f);
        assertEquals(0f, s.paddingHDp, 0.001f);
        assertEquals(32f, s.titleSizeSp, 0.001f);
        assertEquals(200f, s.minHeightDp, 0.001f);
        assertEquals(5, s.maxTextLines);
        assertEquals(60, s.enterDurationMs);
        assertEquals(1500, s.exitDurationMs);
        assertEquals(0f, s.enterOvershoot, 0.001f);
    }

    @Test
    public void fromJson_minValues_clamped() throws JSONException {
        StyleSpec s = StyleSpec.fromJson("{\"maxTextLines\":0}");
        assertEquals(1, s.maxTextLines);
    }

    // ==================== 颜色解析 ====================

    @Test
    public void parseColor_variants() {
        assertEquals(0xFFFF0000, StyleSpec.parseColor("#FF0000", 0));
        assertEquals(0x80FF0000, StyleSpec.parseColor("#80FF0000", 0));
        assertEquals(0x80123456, StyleSpec.parseColor("80123456", 0));
        assertEquals(0xFFFFFFFF, StyleSpec.parseColor("#FFFFFF", 0));
        // 非法 / 空 → 回落
        assertEquals(0x12345678, StyleSpec.parseColor("garbage", 0x12345678));
        assertEquals(0x12345678, StyleSpec.parseColor("", 0x12345678));
        assertEquals(0x12345678, StyleSpec.parseColor(null, 0x12345678));
        assertEquals(0x12345678, StyleSpec.parseColor("#FFF", 0x12345678));
        assertEquals(0x12345678, StyleSpec.parseColor("#GGGGGG", 0x12345678));
    }

    @Test
    public void paletteFromJson_colorsAndFallbacks() throws JSONException {
        StyleSpec s = StyleSpec.fromJson("{"
                + "\"dark\":{\"accent\":\"#FF112233\",\"textPrimary\":\"bad\","
                + "\"bgColors\":[\"#E6000000\",\"#80000000\"]},"
                + "\"light\":{}"
                + "}");
        assertEquals(0xFF112233, s.dark.accent);
        // 非法色回落该字段默认值
        assertEquals(0xFFFFFFFF, s.dark.textPrimary);
        // 未提供的 light 整体回落默认调色板
        assertEquals(0xFFFFFFFF, s.light.textPrimary);
        assertEquals(0xFFA8C7FA, s.light.accent);
        assertEquals(2, s.dark.bgColors.length);
        assertEquals(0xE6000000, s.dark.bgColors[0]);
    }

    @Test
    public void paletteFromJson_bgColorsTooFew_fallBackToDefault() throws JSONException {
        StyleSpec s = StyleSpec.fromJson("{\"dark\":{\"bgColors\":[\"#FF000000\"]}}");
        // 少于 2 个停止色 → 回落默认
        assertEquals(0xF51C1C1E, s.dark.bgColors[0]);
    }

    // ==================== 内置规范文档示例真实可导入 ====================

    @Test
    public void builtinSpecExample_importsEndToEnd() throws JSONException {
        List<StyleSpec> list = StyleSpec.listFromJson(StyleImportDoc.EXAMPLE_JSON);
        assertEquals(1, list.size());
        StyleSpec s = list.get(0);
        assertEquals("暮光紫", s.name);
        assertEquals("HULFix", s.author);
        assertEquals(StyleSpec.VARIANT_GLASS, s.variant);
        assertEquals(26f, s.cornerRadiusDp, 0.001f);
        assertEquals(20f, s.blurRadiusDp, 0.001f);
        assertTrue(s.iconCircleBg);
        assertTrue(s.topHighlight);
        assertTrue(s.bottomShadow);
        assertEquals(0xE61F1B2E, s.dark.bgColors[0]);
        assertEquals(0xB81F1B2E, s.dark.bgColors[1]);
        assertEquals(0xFFCFBCFF, s.dark.accent);
        assertEquals(0xFF1C1B1F, s.light.textPrimary);
        assertEquals(0xFF6750A4, s.light.accent);
        // 序列化 → 再解析：内容守恒（导入落盘用的就是这条链路）
        StyleSpec again = StyleSpec.fromJson(s.toJson());
        assertEquals(s.name, again.name);
        assertEquals(s.contentHash(), again.contentHash());
    }

    @Test
    public void builtinSpecExample_batchImport() throws JSONException {
        // 示例再包一层数组 = 批量导入形态，同样必须可解析
        String batch = "[" + StyleImportDoc.EXAMPLE_JSON + "," + StyleImportDoc.EXAMPLE_JSON + "]";
        assertEquals(2, StyleSpec.listFromJson(batch).size());
    }

    // ==================== StyleRegistry 端到端 ====================

    @Test
    public void registry_customStyleResolvableById() throws JSONException {
        String json = "[{\"id\":\"custom_a\",\"name\":\"导入甲\",\"variant\":\"neon\"}]";
        List<StyleSpec> customs = StyleRegistry.parseCustom(json);
        assertEquals(1, customs.size());
        assertEquals("custom_a", customs.get(0).id);
        // byId：导入样式立即可选
        assertEquals("导入甲", StyleRegistry.byId("custom_a", json).name);
        assertEquals(StyleSpec.VARIANT_NEON,
                StyleRegistry.byId("custom_a", json).variant);
        // 未知 custom id → 回落默认样式（而不是崩溃）
        assertEquals("m3e_glass", StyleRegistry.byId("custom_不存在", json).id);
        // 完全未知 id → 回落默认
        assertEquals("m3e_glass", StyleRegistry.byId("不存在", json).id);
        // 内置样式照常
        assertEquals("standard", StyleRegistry.byId("standard", json).id);
        assertEquals("ios_liquid", StyleRegistry.byId("ios_liquid", json).id);
    }

    @Test
    public void registry_parseCustom_garbageAndEmpty() {
        assertTrue(StyleRegistry.parseCustom("").isEmpty());
        assertTrue(StyleRegistry.parseCustom(null).isEmpty());
        assertTrue(StyleRegistry.parseCustom("坏 JSON").isEmpty());
        // "{}" 是合法的单个样式形态（全默认值），应解析出 1 个
        assertEquals(1, StyleRegistry.parseCustom("{}").size());
    }

    @Test
    public void registry_parseCustom_appliesClamp() {
        String json = "[{\"id\":\"custom_x\",\"cornerRadiusDp\":999}]";
        assertEquals(48f, StyleRegistry.byId("custom_x", json).cornerRadiusDp, 0.001f);
    }

    // ==================== 视图池复用键依赖的内容哈希 ====================

    @Test
    public void contentHash_sameForIdentical_differentForChangedColor() throws JSONException {
        StyleSpec a = StyleSpec.fromJson("{\"id\":\"c1\",\"variant\":\"glass\"}");
        StyleSpec b = StyleSpec.fromJson("{\"id\":\"c1\",\"variant\":\"glass\"}");
        assertEquals(a.contentHash(), b.contentHash());
        StyleSpec c = StyleSpec.fromJson(
                "{\"id\":\"c1\",\"variant\":\"glass\",\"dark\":{\"accent\":\"#FF000000\"}}");
        assertTrue(a.contentHash() != c.contentHash());
    }
}
