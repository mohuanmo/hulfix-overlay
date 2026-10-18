package com.example.hulfix.overlay;

/**
 * 真折射引擎的 uniform 参数包（<b>纯 JVM</b>，可单测）。
 *
 * <p>所有数值在 {@link #create} 中统一钳制：NaN / Infinity 回落、越界截断，
 * 保证无论设置页滑块、JSON 导入还是代码默认值出现异常，传入着色器的永远是合法值
 * （非法 uniform 是 GPU 侧崩溃或花屏的常见源头）。</p>
 *
 * <p>单位约定：px 值均为真实像素（调用方负责 density 换算）；比例值见常量范围。</p>
 */
public final class LensUniforms {

    // ==================== 合法范围（与设置页滑块范围保持一致） ====================

    public static final float MAX_THICKNESS_PX = 48f;   // 折射位移倍率
    public static final float MAX_EDGE_BOOST = 1f;      // 边缘透镜增幅
    public static final float MAX_DISPERSION = 1f;      // 色散强度
    public static final float MAX_SPECULAR = 2f;        // 镜面高光
    public static final float MIN_IOR = 1.01f;          // 折射率下限（=1 时无折射）
    public static final float MAX_IOR = 2f;             // 折射率上限（钻石）
    public static final float MAX_RADIUS_PX = 512f;
    public static final float MAX_EDGE_WIDTH_PX = 256f;
    public static final float TWO_PI = (float) (Math.PI * 2.0);

    public final float radiusPx;
    public final float edgeWidthPx;
    public final float thicknessPx;
    public final float edgeBoost;
    public final float dispersion;
    public final float specular;
    public final float ior;
    public final float phase;
    private final int mHighlightArgb;

    private LensUniforms(float radiusPx, float edgeWidthPx, float thicknessPx, float edgeBoost,
                         float dispersion, float specular, float ior, int highlightArgb,
                         float phase) {
        this.radiusPx = radiusPx;
        this.edgeWidthPx = edgeWidthPx;
        this.thicknessPx = thicknessPx;
        this.edgeBoost = edgeBoost;
        this.dispersion = dispersion;
        this.specular = specular;
        this.ior = ior;
        this.mHighlightArgb = highlightArgb;
        this.phase = phase;
    }

    /**
     * 构造并钳制。NaN / Infinity 一律回落到对应下限（NaN 传播进 GPU 会产生不可预期的花屏，
     * 必须在 Java 侧拦截）。
     */
    public static LensUniforms create(float radiusPx, float edgeWidthPx, float thicknessPx,
                                      float edgeBoost, float dispersion, float specular,
                                      float ior, int highlightArgb, float phase) {
        return new LensUniforms(
                clamp(radiusPx, 0f, MAX_RADIUS_PX),
                clamp(edgeWidthPx, 1f, MAX_EDGE_WIDTH_PX),
                clamp(thicknessPx, 0f, MAX_THICKNESS_PX),
                clamp(edgeBoost, 0f, MAX_EDGE_BOOST),
                clamp(dispersion, 0f, MAX_DISPERSION),
                clamp(specular, 0f, MAX_SPECULAR),
                clamp(ior, MIN_IOR, MAX_IOR),
                highlightArgb,
                clamp(phase, 0f, TWO_PI));
    }

    /** 高光色 RGB 三个分量（0..1 float[3]，对应 uniform float3 uHighlight）。 */
    public float[] highlightRgb() {
        return new float[]{
                ((mHighlightArgb >> 16) & 0xFF) / 255f,
                ((mHighlightArgb >> 8) & 0xFF) / 255f,
                (mHighlightArgb & 0xFF) / 255f
        };
    }

    public int highlightArgb() {
        return mHighlightArgb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LensUniforms)) return false;
        LensUniforms that = (LensUniforms) o;
        return Float.compare(that.radiusPx, radiusPx) == 0
                && Float.compare(that.edgeWidthPx, edgeWidthPx) == 0
                && Float.compare(that.thicknessPx, thicknessPx) == 0
                && Float.compare(that.edgeBoost, edgeBoost) == 0
                && Float.compare(that.dispersion, dispersion) == 0
                && Float.compare(that.specular, specular) == 0
                && Float.compare(that.ior, ior) == 0
                && Float.compare(that.phase, phase) == 0
                && mHighlightArgb == that.mHighlightArgb;
    }

    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(radiusPx);
        result = 31 * result + Float.floatToIntBits(edgeWidthPx);
        result = 31 * result + Float.floatToIntBits(thicknessPx);
        result = 31 * result + Float.floatToIntBits(edgeBoost);
        result = 31 * result + Float.floatToIntBits(dispersion);
        result = 31 * result + Float.floatToIntBits(specular);
        result = 31 * result + Float.floatToIntBits(ior);
        result = 31 * result + Float.floatToIntBits(phase);
        result = 31 * result + mHighlightArgb;
        return result;
    }

    @Override
    public String toString() {
        return "LensUniforms{r=" + radiusPx + " edge=" + edgeWidthPx
                + " thick=" + thicknessPx + " boost=" + edgeBoost
                + " disp=" + dispersion + " spec=" + specular + " ior=" + ior
                + " hl=#" + Integer.toHexString(mHighlightArgb) + "}";
    }

    /** NaN → lo；其余 clamp 到 [lo, hi]。 */
    static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
