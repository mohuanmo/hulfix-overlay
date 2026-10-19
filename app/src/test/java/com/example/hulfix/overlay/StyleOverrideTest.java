package com.example.hulfix.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.example.hulfix.core.Settings;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 模板 + 调整层（overrides）单元测试。
 *
 * <p>覆盖 {@link StyleRegistry#byId(String, String, String)} 的三参解析：
 * 局部补丁语义、越界钳制、模板不可变、自定义样式叠加、坏 JSON 兜底。</p>
 */
public class StyleOverrideTest {

    private static final String CUSTOM =
            "[{\"id\":\"custom_1\",\"name\":\"我的\",\"variant\":\"neon\",\"cornerRadiusDp\":10}]";

    @Test
    public void patchOverridesSingleFieldOnly() {
        // 只改圆角：模板其余参数（模糊、内边距、动画……）必须保持原值
        StyleSpec s = StyleRegistry.byId("ios_liquid", "[]",
                "{\"ios_liquid\":{\"cornerRadiusDp\":12}}");
        assertEquals(12f, s.cornerRadiusDp, 0.01f);
        assertEquals(36f, s.blurRadiusDp, 0.01f);      // 模板值
        assertEquals(24f, s.paddingHDp, 0.01f);        // 模板值
        assertEquals(Settings.ANIM_SCALE, s.enterAnim); // 模板值
        assertEquals(0xFF0A84FF, s.dark.accent);        // 模板值
    }

    @Test
    public void emptyOverrideReturnsTemplate() {
        StyleSpec tpl = StyleRegistry.byId("standard", "[]");
        StyleSpec s = StyleRegistry.byId("standard", "[]", "{}");
        assertEquals(tpl.cornerRadiusDp, s.cornerRadiusDp, 0.001f);
        assertEquals(tpl.dark.accent, s.dark.accent);
        StyleSpec s2 = StyleRegistry.byId("standard", "[]", null);
        assertEquals(tpl.cornerRadiusDp, s2.cornerRadiusDp, 0.001f);
    }

    @Test
    public void patchIsClamped() {
        StyleSpec s = StyleRegistry.byId("standard", "[]",
                "{\"standard\":{\"cornerRadiusDp\":9999,\"maxTextLines\":99}}");
        assertEquals(48f, s.cornerRadiusDp, 0.01f);
        assertEquals(5, s.maxTextLines);
    }

    @Test
    public void patchDoesNotMutateTemplate() {
        StyleRegistry.byId("m3e_glass", "[]", "{\"m3e_glass\":{\"cornerRadiusDp\":0}}");
        // 模板必须保持出厂定义（后续调用拿到的仍是 28dp）
        StyleSpec fresh = StyleRegistry.byId("m3e_glass", "[]");
        assertEquals(28f, fresh.cornerRadiusDp, 0.01f);
        for (StyleSpec b : StyleRegistry.builtins()) {
            if (b.id.equals("m3e_glass")) assertEquals(28f, b.cornerRadiusDp, 0.01f);
        }
    }

    @Test
    public void patchScopedToStyleId() {
        // 给 A 的补丁不能泄漏到 B
        String overrides = "{\"standard\":{\"cornerRadiusDp\":40}}";
        StyleSpec a = StyleRegistry.byId("standard", "[]", overrides);
        StyleSpec b = StyleRegistry.byId("m3e_glass", "[]", overrides);
        assertEquals(40f, a.cornerRadiusDp, 0.01f);
        assertEquals(28f, b.cornerRadiusDp, 0.01f);
    }

    @Test
    public void patchAppliesToCustomStyle() {
        String overrides = "{\"custom_1\":{\"cornerRadiusDp\":30,\"pulseDot\":true}}";
        StyleSpec s = StyleRegistry.byId("custom_1", CUSTOM, overrides);
        assertEquals(30f, s.cornerRadiusDp, 0.01f);   // 补丁覆盖导入值 10
        assertTrue(s.pulseDot);                        // 补丁新增
        assertEquals("neon", s.variant);               // 导入值保留
    }

    @Test
    public void badOverrideJsonFallsBackToTemplate() {
        StyleSpec s = StyleRegistry.byId("standard", "[]", "{not json");
        assertEquals(16f, s.cornerRadiusDp, 0.01f);
    }

    @Test
    public void patchCanSwitchVariant() {
        StyleSpec s = StyleRegistry.byId("standard", "[]",
                "{\"standard\":{\"variant\":\"liquid\"}}");
        assertEquals(StyleSpec.VARIANT_LIQUID, s.variant);
    }

    @Test
    public void patchPaletteColors() {
        StyleSpec s = StyleRegistry.byId("m3e_glass", "[]",
                "{\"m3e_glass\":{\"dark\":{\"accent\":\"#FF112233\"}}}");
        assertEquals(0xFF112233, s.dark.accent);
        // 浅色未指定 → 模板值
        assertEquals(0xFF6750A4, s.light.accent);
    }

    @Test
    public void overridePatchExtraction() {
        JSONObject patch = StyleRegistry.overridePatch(
                "{\"ios_liquid\":{\"cornerRadiusDp\":8}}", "ios_liquid");
        assertTrue(patch != null && patch.length() == 1);
        assertEquals(null, StyleRegistry.overridePatch("{}", "ios_liquid"));
        assertEquals(null, StyleRegistry.overridePatch(null, "ios_liquid"));
        assertEquals(null, StyleRegistry.overridePatch("bad", "ios_liquid"));
    }

    @Test
    public void applyJsonPartialSemanticsOnCopy() throws Exception {
        StyleSpec base = StyleRegistry.byId("neon_pulse", "[]");
        StyleSpec tweaked = base.copy().applyJson(new JSONObject("{\"cornerRadiusDp\":4}"));
        assertEquals(4f, tweaked.cornerRadiusDp, 0.01f);
        // 未出现的键保持实例原值
        assertEquals(base.strokeWidthDp, tweaked.strokeWidthDp, 0.001f);
        assertEquals(base.dark.bgColors[0], tweaked.dark.bgColors[0]);
        // 原实例不受影响
        assertEquals(24f, base.cornerRadiusDp, 0.01f);
    }

    @Test
    public void copyDeepCopiesPalettes() {
        StyleSpec base = StyleRegistry.byId("neon_pulse", "[]");
        StyleSpec c = base.copy();
        assertNotSame(base.dark.bgColors, c.dark.bgColors);
        assertNotSame(base.dark, c.dark);
        c.dark.bgColors[0] = 0;
        assertNotSame(0, base.dark.bgColors[0]);
    }

    @Test
    public void settingsRoundTripWithOverrides() {
        Settings s = new Settings();
        s.styleOverridesJson = "{\"standard\":{\"cornerRadiusDp\":20}}";
        Settings back = Settings.fromJson(s.toJson());
        assertTrue(back != null);
        assertEquals("{\"standard\":{\"cornerRadiusDp\":20}}", back.styleOverridesJson);
        // 缺省（旧版本设置）回落空对象
        Settings legacy = Settings.fromJson("{\"hulfix_enabled\":true}");
        assertTrue(legacy != null);
        assertEquals("{}", legacy.styleOverridesJson);
    }
}
