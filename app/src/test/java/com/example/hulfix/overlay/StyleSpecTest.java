package com.example.hulfix.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.hulfix.core.Settings;
import com.example.hulfix.overlay.StyleSpec.Palette;

import org.junit.Test;

import java.util.List;

/**
 * 样式规范单元测试：4 种内置样式、JSON 往返、导入校验、数值钳制。
 */
public class StyleSpecTest {

    @Test
    public void fourBuiltins() {
        List<StyleSpec> all = StyleRegistry.builtins();
        assertEquals(4, all.size());
        assertEquals("standard", all.get(0).id);
        assertEquals("m3e_glass", all.get(1).id);
        assertEquals("ios_liquid", all.get(2).id);
        assertEquals("neon_pulse", all.get(3).id);
    }

    @Test
    public void byId_resolvesBuiltinsAndCustom() {
        String customJson = "[{\"id\":\"custom_1\",\"name\":\"我的\",\"variant\":\"neon\"}]";
        assertEquals("standard",
                StyleRegistry.byId("standard", "[]").id);
        assertEquals("custom_1",
                StyleRegistry.byId("custom_1", customJson).id);
        // 未知 id 回落到默认
        assertEquals("m3e_glass",
                StyleRegistry.byId("not_exist", "[]").id);
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        for (StyleSpec spec : StyleRegistry.builtins()) {
            StyleSpec back = StyleSpec.fromJson(spec.toJson());
            assertEquals(spec.id, back.id);
            assertEquals(spec.name, back.name);
            assertEquals(spec.variant, back.variant);
            assertEquals(spec.cornerRadiusDp, back.cornerRadiusDp, 0.01f);
            assertEquals(spec.blurRadiusDp, back.blurRadiusDp, 0.01f);
            assertEquals(spec.dark.accent, back.dark.accent);
            assertEquals(spec.light.bgColors[0], back.light.bgColors[0]);
            assertEquals(spec.enterAnim, back.enterAnim);
            assertEquals(spec.progressRing, back.progressRing);
        }
    }

    @Test
    public void importSingleAndWrapped() throws Exception {
        StyleSpec single = StyleSpec.fromJson(
                "{\"id\":\"c1\",\"name\":\"单样式\",\"variant\":\"liquid\"}");
        assertEquals("c1", single.id);

        List<StyleSpec> wrapped = StyleSpec.listFromJson(
                "{\"styles\":[{\"id\":\"a\",\"variant\":\"glass\"},{\"id\":\"b\",\"variant\":\"neon\"}]}");
        assertEquals(2, wrapped.size());
        assertEquals("a", wrapped.get(0).id);
    }

    @Test
    public void invalidVariantFallsBackToGlass() throws Exception {
        StyleSpec s = StyleSpec.fromJson(
                "{\"id\":\"x\",\"variant\":\"not_a_variant\"}");
        assertEquals(StyleSpec.VARIANT_GLASS, s.variant);
    }

    @Test
    public void badJsonThrows() {
        try {
            StyleSpec.fromJson("{broken");
            fail("should throw");
        } catch (Exception expected) {
        }
    }

    @Test
    public void clampOutOfRange() throws Exception {
        StyleSpec s = StyleSpec.fromJson(
                "{\"id\":\"x\",\"cornerRadiusDp\":9999,\"blurRadiusDp\":-5,\"maxTextLines\":99,\"enterDurationMs\":10}");
        assertEquals(48f, s.cornerRadiusDp, 0.01f);
        assertEquals(0f, s.blurRadiusDp, 0.01f);
        assertEquals(5, s.maxTextLines);
        assertEquals(60, s.enterDurationMs);
    }

    @Test
    public void colorParsing() {
        assertEquals(0xFF112233, StyleSpec.parseColor("#112233", 0));
        assertEquals(0x80112233, StyleSpec.parseColor("#80112233", 0));
        assertEquals(0xFFFFFFFF, StyleSpec.parseColor("#FFFFFFFF", 0));
        assertEquals(-1, StyleSpec.parseColor("bad", -1));
        assertEquals("#80112233", StyleSpec.colorHex(0x80112233));
    }

    @Test
    public void paletteCustomization() throws Exception {
        StyleSpec s = StyleSpec.fromJson(
                "{\"id\":\"x\",\"dark\":{\"bgColors\":[\"#112233\",\"#445566\"],\"accent\":\"#FF0000\"}}");
        assertEquals(0xFF112233, s.dark.bgColors[0]);
        assertEquals(0xFF445566, s.dark.bgColors[1]);
        assertEquals(0xFFFF0000, s.dark.accent);
        // 未指定的字段保留默认
        Palette def = new Palette();
        assertEquals(def.textPrimary, s.dark.textPrimary);
    }

    @Test
    public void settingsDefaultsMatchLegacyConstants() {
        Settings s = new Settings();
        // 与旧版硬编码一致
        assertEquals(6000, s.autoDismissMs);
        assertEquals(1000, s.globalCooldownMs);
        assertEquals(140f, s.swipeThresholdPx, 0.01f);
        assertEquals(200f, s.flingVelocityPxS, 0.01f);
        assertEquals(Settings.ANCHOR_TOP_RIGHT, s.anchor);
        assertEquals(Settings.ANIM_FOLLOW_STYLE, s.enterAnim);
    }
}
