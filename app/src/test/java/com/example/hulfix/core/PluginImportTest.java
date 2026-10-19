package com.example.hulfix.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.hulfix.overlay.StyleSpec;
import com.example.hulfix.ui.PluginImportDoc;

import org.junit.Test;

import java.util.List;

/**
 * 插件导入单元测试：校验规则、id 重写、引擎预设、序列化往返，
 * 以及「文档示例真的能导入」（文档与实现不漂移）。
 */
public class PluginImportTest {

    private static final String ID = "plugin_1700000000000";

    @Test
    public void docExampleImports() {
        PluginManager.Plugin p = PluginManager.parseImport(PluginImportDoc.EXAMPLE_JSON, ID);
        assertNotNull("文档示例必须可导入", p);
        assertEquals("极简薄雾", p.name);
        assertEquals(1, p.version);
        assertEquals("HULFix 社区", p.author);
        assertEquals(1, p.styles.size());
        StyleSpec s = p.styles.get(0);
        assertEquals("薄雾", s.name);
        assertEquals(StyleSpec.VARIANT_LIQUID, s.variant);
        assertEquals(22f, s.cornerRadiusDp, 0.01f);
        assertEquals(30f, s.blurRadiusDp, 0.01f);
        assertEquals(0x33FFFFFF, s.light.bgColors[0]);
        assertEquals(0xFF0A84FF, s.dark.accent);
        assertNotNull(p.engine);
        assertEquals("强折射薄玻璃", p.engine.name);
        assertEquals(22.0, p.engine.params.optDouble("hulfix_lens_thickness", 0), 0.01);
        assertTrue(p.engine.params.optBoolean("hulfix_lens_dynamic", false));
    }

    @Test
    public void styleIdsAreRewritten() {
        PluginManager.Plugin p = PluginManager.parseImport(
                "{\"name\":\"多样式\",\"styles\":[{\"name\":\"甲\"},{\"name\":\"乙\"}]}", ID);
        assertNotNull(p);
        assertEquals(2, p.styles.size());
        assertEquals("plugin_" + ID + "_0", p.styles.get(0).id);
        assertEquals("plugin_" + ID + "_1", p.styles.get(1).id);
        // id → 插件 id 反查
        assertEquals(ID, PluginManager.pluginIdOfStyleId(p.styles.get(1).id));
        assertNull(PluginManager.pluginIdOfStyleId("custom_1"));
        assertNull(PluginManager.pluginIdOfStyleId("standard"));
    }

    @Test
    public void rejectsMissingName() {
        assertNull(PluginManager.parseImport("{\"styles\":[{\"name\":\"甲\"}]}", ID));
        assertNull(PluginManager.parseImport("{\"name\":\"  \",\"styles\":[{\"name\":\"甲\"}]}", ID));
    }

    @Test
    public void rejectsEmptyPlugin() {
        // 既无样式又无引擎预设 → 拒绝
        assertNull(PluginManager.parseImport("{\"name\":\"空\"}", ID));
    }

    @Test
    public void rejectsBadJson() {
        assertNull(PluginManager.parseImport("{broken", ID));
        assertNull(PluginManager.parseImport(null, ID));
        assertNull(PluginManager.parseImport("", ID));
    }

    @Test
    public void engineOnlyPluginAllowed() {
        PluginManager.Plugin p = PluginManager.parseImport(
                "{\"name\":\"纯引擎\",\"engine\":{\"name\":\"P\",\"params\":{\"hulfix_lens_ior\":1.8}}}", ID);
        assertNotNull(p);
        assertTrue(p.styles.isEmpty());
        assertNotNull(p.engine);
        assertEquals(1.8, p.engine.params.optDouble("hulfix_lens_ior", 0), 0.01);
    }

    @Test
    public void engineParamWhitelist() {
        assertTrue(PluginManager.isEngineParamKey("hulfix_lens_thickness"));
        assertTrue(PluginManager.isEngineParamKey("hulfix_lens_accent_follow"));
        assertFalse(PluginManager.isEngineParamKey("hulfix_lens_bogus"));
        assertFalse(PluginManager.isEngineParamKey("hulfix_style_id"));
        assertFalse(PluginManager.isEngineParamKey(""));
    }

    @Test
    public void badStyleVariantFallsBack() {
        PluginManager.Plugin p = PluginManager.parseImport(
                "{\"name\":\"X\",\"styles\":[{\"name\":\"甲\",\"variant\":\"not_a_variant\"}]}", ID);
        assertNotNull(p);
        assertEquals(StyleSpec.VARIANT_GLASS, p.styles.get(0).variant);
    }

    @Test
    public void pluginsRoundTrip() throws Exception {
        PluginManager.Plugin p = PluginManager.parseImport(PluginImportDoc.EXAMPLE_JSON, ID);
        assertNotNull(p);
        p.enabled = false;
        String json = PluginManager.serializePlugins(java.util.Collections.singletonList(p));
        List<PluginManager.Plugin> back = PluginManager.parsePlugins(json);
        assertEquals(1, back.size());
        PluginManager.Plugin b = back.get(0);
        assertEquals(p.id, b.id);
        assertEquals(p.name, b.name);
        assertFalse(b.enabled);
        assertEquals(1, b.styles.size());
        assertEquals(22f, b.styles.get(0).cornerRadiusDp, 0.01);
        assertNotNull(b.engine);
        assertEquals(22.0, b.engine.params.optDouble("hulfix_lens_thickness", 0), 0.01);
    }

    @Test
    public void parsePluginsToleratesBadJson() {
        assertTrue(PluginManager.parsePlugins("").isEmpty());
        assertTrue(PluginManager.parsePlugins(null).isEmpty());
        assertTrue(PluginManager.parsePlugins("not json").isEmpty());
    }

    @Test
    public void enginePresetsOfEnabledOnly() {
        PluginManager.Plugin a = PluginManager.parseImport(
                "{\"name\":\"A\",\"engine\":{\"name\":\"PA\",\"params\":{}}}", ID + "a");
        PluginManager.Plugin b = PluginManager.parseImport(
                "{\"name\":\"B\",\"engine\":{\"name\":\"PB\",\"params\":{}}}", ID + "b");
        assertNotNull(a);
        assertNotNull(b);
        a.enabled = true;
        b.enabled = false;
        List<PluginManager.EnginePreset> presets =
                PluginManager.enginePresetsOf(java.util.Arrays.asList(a, b));
        assertEquals(1, presets.size());
        assertEquals("PA", presets.get(0).name);
    }
}
