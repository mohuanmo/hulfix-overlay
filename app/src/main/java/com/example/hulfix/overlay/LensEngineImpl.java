package com.example.hulfix.overlay;

import android.annotation.TargetApi;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

/**
 * AGSL 真折射引擎实现（<b>仅 API 33+ 加载</b>）。
 *
 * <p>持有编译好的 {@link RuntimeShader} 与挂载到 View 的 {@link RenderEffect}。
 * 所有调用都可能因驱动兼容性问题抛异常，因此每个入口都自行 try/catch 并在失败后
 * 置 {@code mBroken}，让上层平滑降级到传统模拟路径。</p>
 *
 * <p>本类被 {@link LensEngine} 门面隔离保护：API &lt; 33 的设备不会加载本类。</p>
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
public final class LensEngineImpl {

    private final RuntimeShader mShader;
    private boolean mBroken;

    private LensEngineImpl(RuntimeShader shader) {
        mShader = shader;
    }

    /** 编译 AGSL 源码；编译失败抛给调用方（门面会捕获并降级）。 */
    public static LensEngineImpl create() {
        return new LensEngineImpl(new RuntimeShader(LensShader.source()));
    }

    public boolean isBroken() {
        return mBroken;
    }

    /** 把当前 uniform 值推送到着色器。尺寸为 View 的像素尺寸。 */
    public void bind(LensUniforms u, int widthPx, int heightPx) {
        if (mBroken || u == null || widthPx <= 0 || heightPx <= 0) return;
        try {
            mShader.setFloatUniform(LensShader.U_RESOLUTION, (float) widthPx, (float) heightPx);
            mShader.setFloatUniform(LensShader.U_RADIUS_PX, u.radiusPx);
            mShader.setFloatUniform(LensShader.U_EDGE_WIDTH_PX, u.edgeWidthPx);
            mShader.setFloatUniform(LensShader.U_THICKNESS, u.thicknessPx);
            mShader.setFloatUniform(LensShader.U_EDGE_BOOST, u.edgeBoost);
            mShader.setFloatUniform(LensShader.U_DISPERSION, u.dispersion);
            mShader.setFloatUniform(LensShader.U_SPECULAR, u.specular);
            mShader.setFloatUniform(LensShader.U_IOR, u.ior);
            mShader.setFloatUniform(LensShader.U_HIGHLIGHT, u.highlightRgb());
            mShader.setFloatUniform(LensShader.U_PHASE, u.phase);
            // 动态光效 uniform：实时渲染引擎逐帧刷新（无交互时保持常量，零额外开销）
            mShader.setFloatUniform(LensShader.U_LIGHT_DIR, u.lightX, u.lightY);
            mShader.setFloatUniform(LensShader.U_AMBIENT, u.ambient);
            mShader.setFloatUniform(LensShader.U_SHADOW, u.shadow);
            mShader.setFloatUniform(LensShader.U_FLOW, u.flowPhase);
        } catch (Throwable t) {
            mBroken = true;
        }
    }

    /**
     * 逐帧热路径：只推送 4 个动态 uniform（光源/环境光/阴影/流动相位）。
     *
     * <p>静态外观参数（尺寸、折射率、色散等）不随帧变化，无需重复推送——
     * 每帧全量 bind 15 个 uniform 在低端 GPU 上也是可省的开销。</p>
     */
    public void bindDynamic(LensUniforms u) {
        if (mBroken || u == null) return;
        bindDynamicRaw(u.lightX, u.lightY, u.ambient, u.shadow, u.flowPhase);
    }

    /**
     * 逐帧热路径的零分配形态：直接收原始 float（帧循环内部使用，
     * 避免每帧 new 一个 {@link LensUniforms} 造成 GC 抖动）。
     */
    public void bindDynamicRaw(float lightX, float lightY, float ambient,
                               float shadow, float flowPhase) {
        if (mBroken) return;
        try {
            mShader.setFloatUniform(LensShader.U_LIGHT_DIR, lightX, lightY);
            mShader.setFloatUniform(LensShader.U_AMBIENT, ambient);
            mShader.setFloatUniform(LensShader.U_SHADOW, shadow);
            mShader.setFloatUniform(LensShader.U_FLOW, flowPhase);
        } catch (Throwable t) {
            mBroken = true;
        }
    }

    /**
     * 在 View 上挂载折射 RenderEffect（作用于该 View 自身绘制的内容，
     * 兄弟 View 如文字内容层不受影响）。
     *
     * @return true 表示挂载成功；失败时已置 broken，调用方应降级
     */
    public boolean applyTo(View v) {
        if (mBroken || v == null) return false;
        try {
            RenderEffect effect = RenderEffect.createRuntimeShaderEffect(
                    mShader, LensShader.U_BACKDROP);
            v.setRenderEffect(effect);
            return true;
        } catch (Throwable t) {
            mBroken = true;
            return false;
        }
    }
}
