package com.example.hulfix.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * 液态玻璃背景层（真折射引擎的载体 View）。
 *
 * <p>职责：绘制"玻璃后方的内容代理"——渐变底色 + 放大的 App 大图标 + 斜向光泽带；
 * 当 {@link LensEngine} 可用时，这些内容经 {@link LensEngineImpl} 的 AGSL 着色器
 * 发生<b>真实物理折射</b>（SDF 法线 + Snell 定律 + 色散 + 菲涅尔高光）后呈现。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li><b>始终自绘背景</b>：即使引擎不可用（API &lt; 33 / 编译失败），{@link #onDraw}
 *       画出的渐变底与原 {@code GradientDrawable} 路径视觉一致，渲染器因此可以无差别使用
 *       本 View，降级逻辑对上层完全透明；</li>
 *   <li><b>只折射自己</b>：RenderEffect 仅作用于本 View 子树（无子 View，即仅自身绘制），
 *       前景文字/图标作为兄弟 View 保持绝对清晰——这正是真玻璃的层次；</li>
 *   <li><b>静态 uniform</b>：不在动画中逐帧刷新，仅在尺寸/参数变化时 bind + invalidate，
 *       悬浮窗停留期间零额外 GPU 开销；</li>
 *   <li><b>API 安全</b>：本类方法签名不含任何 API 31+ 类型，旧设备上可安全加载。</li>
 * </ul>
 */
public final class LensBackdropView extends View {

    private LensEngineImpl mImpl;
    private LensUniforms mUniforms;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] mBgColors = {0xF51C1C1E, 0xF51C1C1E};
    private float mBgAlphaScale = 1f;
    private int mAccentColor = 0xFFA8C7FA;
    private Drawable mIcon;

    public LensBackdropView(Context context) {
        super(context);
        init();
    }

    public LensBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LensBackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mImpl = LensEngine.tryCreate();
        if (mImpl != null) {
            try {
                if (!mImpl.applyTo(this)) mImpl = null;
            } catch (Throwable t) {
                mImpl = null;
            }
        }
    }

    /** 真折射是否真正生效（false = 已走兼容模拟路径）。 */
    public boolean isLensActive() {
        return mImpl != null && !mImpl.isBroken();
    }

    // ==================== 内容注入 ====================

    public void setLensUniforms(LensUniforms u) {
        mUniforms = u;
        applyUniforms();
    }

    /** 背景渐变（调用方负责按 bgAlpha 缩放 alpha）。 */
    public void setBackdropColors(int[] colors, float alphaScale) {
        if (colors == null || colors.length < 2) return;
        mBgColors = colors;
        mBgAlphaScale = alphaScale;
        invalidate();
    }

    public void setAccentColor(int accent) {
        mAccentColor = accent;
        invalidate();
    }

    /** 玻璃后方的大图标（折射的主要结构来源）。 */
    public void setBackdropIcon(Drawable icon) {
        mIcon = icon;
        invalidate();
    }

    // ==================== uniform 生命周期 ====================

    private void applyUniforms() {
        if (mImpl == null || mUniforms == null || getWidth() == 0 || getHeight() == 0) return;
        mImpl.bind(mUniforms, getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 布局完成（尺寸已知）后才能推送 uResolution
        applyUniforms();
    }

    // ==================== 绘制（玻璃后方内容代理） ====================

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float scale = mBgAlphaScale;
        mPaint.setShader(null);
        mPaint.setColor(0);

        // 1) 垂直渐变底（与旧路径 GradientDrawable 相同的配色，alpha 已由调用方缩放）
        int top = scaleAlpha(mBgColors[0], scale);
        int bottom = scaleAlpha(mBgColors[mBgColors.length - 1], scale);
        LinearGradient lg = new LinearGradient(0, 0, 0, h, top, bottom,
                Shader.TileMode.CLAMP);
        mPaint.setShader(lg);
        canvas.drawRect(0, 0, w, h, mPaint);
        mPaint.setShader(null);

        // 2) 玻璃后方的 App 大图标：放大 + 低透明度，给折射提供结构
        //    （折射的观感主要来自边缘处图内容的拉伸与色散）
        if (mIcon != null) {
            int size = (int) (Math.min(w, h) * 1.35f);
            int left = (w - size) / 2;
            int topI = (h - size) / 2;
            mIcon.setBounds(left, topI, left + size, topI + size);
            int alpha = (int) (255 * 0.30f * scale);
            mIcon.setAlpha(Math.max(0, Math.min(255, alpha)));
            mIcon.draw(canvas);
            mIcon.setAlpha(255);
        }

        // 3) 斜向光泽带：高频内容让色散在边缘清晰可见
        float bandH = h * 0.55f;
        LinearGradient sheen = new LinearGradient(0, 0, w, bandH,
                withAlpha(mAccentColor, (int) (0x28 * scale)),
                withAlpha(mAccentColor, 0x00),
                Shader.TileMode.CLAMP);
        mPaint.setShader(sheen);
        canvas.drawRect(0, 0, w, bandH, mPaint);
        mPaint.setShader(null);
    }

    // ==================== 颜色工具 ====================

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) & 0xFF) << 24 | (argb & 0x00FFFFFF);
    }

    private static int scaleAlpha(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        return withAlpha(argb, (int) (a * f));
    }
}
