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
}
