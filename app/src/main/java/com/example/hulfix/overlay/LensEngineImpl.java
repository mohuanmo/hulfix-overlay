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
