package com.example.hulfix.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link LensUniforms} 钳制与转换测试。 */
public class LensUniformsTest {

    private static final int WHITE = 0xFFFFFFFF;

    @Test
    public void validValuesPassThrough() {
        LensUniforms u = LensUniforms.create(30f, 12f, 14f, 0.75f, 0.35f, 1f, 1.5f, WHITE, 1f);
        assertEquals(30f, u.radiusPx, 0.0001f);
        assertEquals(12f, u.edgeWidthPx, 0.0001f);
        assertEquals(14f, u.thicknessPx, 0.0001f);
        assertEquals(0.75f, u.edgeBoost, 0.0001f);
        assertEquals(0.35f, u.dispersion, 0.0001f);
        assertEquals(1f, u.specular, 0.0001f);
        assertEquals(1.5f, u.ior, 0.0001f);
        assertEquals(1f, u.phase, 0.0001f);
    }

    @Test
    public void outOfRangeClamped() {
        LensUniforms u = LensUniforms.create(9999f, 9999f, 9999f, 5f, 5f, 9f, 9f, WHITE, 99f);
        assertEquals(LensUniforms.MAX_RADIUS_PX, u.radiusPx, 0.0001f);
        assertEquals(LensUniforms.MAX_EDGE_WIDTH_PX, u.edgeWidthPx, 0.0001f);
        assertEquals(LensUniforms.MAX_THICKNESS_PX, u.thicknessPx, 0.0001f);
        assertEquals(LensUniforms.MAX_EDGE_BOOST, u.edgeBoost, 0.0001f);
        assertEquals(LensUniforms.MAX_DISPERSION, u.dispersion, 0.0001f);
        assertEquals(LensUniforms.MAX_SPECULAR, u.specular, 0.0001f);
        assertEquals(LensUniforms.MAX_IOR, u.ior, 0.0001f);
        assertEquals(LensUniforms.TWO_PI, u.phase, 0.0001f);
    }

    @Test
    public void negativeClampedToFloor() {
        LensUniforms u = LensUniforms.create(-5f, -5f, -5f, -1f, -1f, -1f, -1f, WHITE, -3f);
        assertEquals(0f, u.radiusPx, 0.0001f);
        assertEquals(1f, u.edgeWidthPx, 0.0001f);   // edgeWidth 下限 1px，防 shader 除零
        assertEquals(0f, u.thicknessPx, 0.0001f);
        assertEquals(0f, u.edgeBoost, 0.0001f);
        assertEquals(0f, u.dispersion, 0.0001f);
        assertEquals(0f, u.specular, 0.0001f);
        assertEquals(LensUniforms.MIN_IOR, u.ior, 0.0001f); // IOR=1 时无折射，下限 1.01
        assertEquals(0f, u.phase, 0.0001f);
    }

    @Test
    public void nanFallsBackToFloor() {
        // NaN 传播进 GPU 会产生花屏，必须在 Java 侧拦截
        LensUniforms u = LensUniforms.create(
                Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN, WHITE, Float.NaN);
        assertEquals(0f, u.radiusPx, 0.0001f);
        assertEquals(1f, u.edgeWidthPx, 0.0001f);
        assertEquals(0f, u.thicknessPx, 0.0001f);
        assertEquals(LensUniforms.MIN_IOR, u.ior, 0.0001f);
        assertEquals(0f, u.phase, 0.0001f);
    }

    @Test
    public void infinityClamped() {
        LensUniforms u = LensUniforms.create(
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY, WHITE, Float.POSITIVE_INFINITY);
        assertEquals(LensUniforms.MAX_THICKNESS_PX, u.thicknessPx, 0.0001f);
        assertEquals(LensUniforms.MAX_IOR, u.ior, 0.0001f);
        assertEquals(LensUniforms.TWO_PI, u.phase, 0.0001f);
    }

    @Test
    public void highlightRgbInUnitRange() {
        LensUniforms white = LensUniforms.create(1f, 1f, 1f, 0f, 0f, 0f, 1.5f, WHITE, 0f);
        assertArrayEquals(new float[]{1f, 1f, 1f}, white.highlightRgb(), 0.0001f);

        LensUniforms black = LensUniforms.create(1f, 1f, 1f, 0f, 0f, 0f, 1.5f, 0xFF000000, 0f);
        assertArrayEquals(new float[]{0f, 0f, 0f}, black.highlightRgb(), 0.0001f);

        // iOS 蓝 0xFF007AFF → (0, 122/255, 1)
        LensUniforms blue = LensUniforms.create(1f, 1f, 1f, 0f, 0f, 0f, 1.5f, 0xFF007AFF, 0f);
        assertArrayEquals(new float[]{0f, 122f / 255f, 1f}, blue.highlightRgb(), 0.0001f);
    }

    @Test
    public void equalsContract() {
        LensUniforms a = LensUniforms.create(10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, 0x80FFFFFF, 1f);
        LensUniforms b = LensUniforms.create(10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, 0x80FFFFFF, 1f);
        LensUniforms c = LensUniforms.create(10f, 8f, 15f, 0.5f, 0.2f, 1f, 1.5f, 0x80FFFFFF, 1f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    public void defaultSettingsProduceUsableUniforms() {
        // 模拟默认设置 + 2.75 密度下的 28dp 圆角
        LensUniforms u = LensUniforms.create(
                28f * 2.75f, Math.max(8f * 2.75f, 28f * 2.75f * 0.35f),
                14f * 2.75f, 0.75f, 0.35f, 1f, 1.5f, 0x40FFFFFF, 0f);
        assertTrue(u.radiusPx > 0);
        assertTrue(u.edgeWidthPx >= 8f * 2.75f);
        assertTrue(u.thicknessPx > 0);
        assertTrue(u.ior > 1f);
    }

    // ==================== 动态光效字段 ====================

    @Test
    public void createCarriesStaticDynamicDefaults() {
        // 9 参构造（旧路径）必须自带合法的动态默认值，保证着色器不收到未定义 uniform
        LensUniforms u = LensUniforms.create(10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, WHITE, 0f);
        assertEquals(LensUniforms.DEFAULT_LIGHT_X, u.lightX, 0.0001f);
        assertEquals(LensUniforms.DEFAULT_LIGHT_Y, u.lightY, 0.0001f);
        assertEquals(LensUniforms.DEFAULT_AMBIENT, u.ambient, 0.0001f);
        assertEquals(LensUniforms.DEFAULT_SHADOW, u.shadow, 0.0001f);
        assertEquals(LensUniforms.DEFAULT_FLOW_PHASE, u.flowPhase, 0.0001f);
    }

    @Test
    public void createDynamicClampsAllDynamicFields() {
        LensUniforms u = LensUniforms.createDynamic(
                10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, WHITE, 1f,
                99f, -99f, 99f, -99f, 99f);
        assertEquals(LensUniforms.MAX_LIGHT_DIR, u.lightX, 0.0001f);
        assertEquals(-LensUniforms.MAX_LIGHT_DIR, u.lightY, 0.0001f);
        assertEquals(LensUniforms.MAX_AMBIENT, u.ambient, 0.0001f);
        assertEquals(0f, u.shadow, 0.0001f);
        assertEquals(LensUniforms.TWO_PI, u.flowPhase, 0.0001f);
    }

    @Test
    public void createDynamicNaNFallsBack() {
        LensUniforms u = LensUniforms.createDynamic(
                10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, WHITE, 1f,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        assertEquals(0f, u.lightX, 0.0001f);   // NaN → 方向下限 0（回中）
        assertEquals(0f, u.lightY, 0.0001f);
        assertEquals(0f, u.ambient, 0.0001f);
        assertEquals(0f, u.shadow, 0.0001f);
        assertEquals(0f, u.flowPhase, 0.0001f);
    }

    @Test
    public void withDynamicKeepsStaticFields() {
        LensUniforms base = LensUniforms.create(30f, 12f, 14f, 0.75f, 0.35f, 1f, 1.5f, 0xFF112233, 2f);
        LensUniforms dyn = base.withDynamic(-0.5f, 0.25f, 0.8f, 0.1f, 1.5f);
        // 静态外观参数原样保留
        assertEquals(30f, dyn.radiusPx, 0.0001f);
        assertEquals(1.5f, dyn.ior, 0.0001f);
        assertEquals(0xFF112233, dyn.highlightArgb());
        // 动态字段按新值
        assertEquals(-0.5f, dyn.lightX, 0.0001f);
        assertEquals(0.25f, dyn.lightY, 0.0001f);
        assertEquals(0.8f, dyn.ambient, 0.0001f);
        assertEquals(0.1f, dyn.shadow, 0.0001f);
        assertEquals(1.5f, dyn.flowPhase, 0.0001f);
    }

    @Test
    public void dynamicFieldsParticipateInEquals() {
        LensUniforms a = LensUniforms.create(10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, WHITE, 1f)
                .withDynamic(0.1f, 0.2f, 0.3f, 0.4f, 0.5f);
        LensUniforms b = LensUniforms.create(10f, 8f, 14f, 0.5f, 0.2f, 1f, 1.5f, WHITE, 1f)
                .withDynamic(0.1f, 0.2f, 0.3f, 0.4f, 0.6f);
        assertNotEquals(a, b);
    }
}
