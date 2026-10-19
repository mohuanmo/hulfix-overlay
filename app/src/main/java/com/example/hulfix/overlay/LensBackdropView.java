package com.example.hulfix.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

/**
 * 液态玻璃背景层（真折射引擎的载体 View，动态光效实时渲染宿主）。
 *
 * <p>职责：绘制"玻璃后方的内容代理"——渐变底色 + 放大的 App 大图标 + 斜向光泽带；
 * 当 {@link LensEngine} 可用时，这些内容经 {@link LensEngineImpl} 的 AGSL 着色器
 * 发生<b>真实物理折射</b>（SDF 法线 + Snell 定律 + 色散 + 菲涅尔高光 + 动态光效）后呈现。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li><b>始终自绘背景</b>：即使引擎不可用（API &lt; 33 / 编译失败），{@link #onDraw}
 *       画出的渐变底与原 {@code GradientDrawable} 路径视觉一致，渲染器因此可以无差别使用
 *       本 View，降级逻辑对上层完全透明；</li>
 *   <li><b>只折射自己</b>：RenderEffect 仅作用于本 View 子树（无子 View，即仅自身绘制），
 *       前景文字/图标作为兄弟 View 保持绝对清晰——这正是真玻璃的层次；</li>
 *   <li><b>实时渲染引擎</b>：{@link LensDynamics} 逐帧演算光源 / 环境光 / 自适应阴影 /
 *       流动相位，经 {@link Choreographer} 对齐 vsync 推送并 invalidate。
 *       帧循环仅在"动态开启 + 强度>0 + 已挂载"时运行——关闭动态即零常驻开销，
 *       观感回到纯静态折射；</li>
 *   <li><b>背景适应</b>：内容（渐变 / 图标）变化时采样其平均亮度，作为环境光基准——
 *       玻璃持续适应其下方的内容；</li>
 *   <li><b>API 安全</b>：本类方法签名不含任何 API 31+ 类型，旧设备上可安全加载
 *       （Choreographer 为 API 16）。</li>
 * </ul>
 */
public final class LensBackdropView extends View {

    private LensEngineImpl mImpl;
    private LensUniforms mUniforms;
    /** setLensUniforms 传入的原始高光色（强调色跟随关闭时恢复用）。 */
    private int mBaseHighlightArgb;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] mBgColors = {0xF51C1C1E, 0xF51C1C1E};
    private float mBgAlphaScale = 1f;
    private int mAccentColor = 0xFFA8C7FA;
    private Drawable mIcon;

    // ==================== 动态光效（实时渲染引擎） ====================

    private final LensDynamics mDynamics = new LensDynamics();
    private boolean mDynamicEnabled = true;
    private boolean mAccentFollow;
    /** 系统强调色缓存（0 = 未解析）。 */
    private int mSystemAccent;
    private boolean mFrameRunning;
    private long mLastFrameNs;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mFrameRunning) return;
            if (mLastFrameNs == 0L) mLastFrameNs = frameTimeNanos;
            float dt = (frameTimeNanos - mLastFrameNs) / 1_000_000_000f;
            mLastFrameNs = frameTimeNanos;
            stepFrame(dt);
            if (mFrameRunning) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

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
        if (u != null) mBaseHighlightArgb = u.highlightArgb();
        applyUniforms();
        maybeStartFrameLoop();
    }

    /** 背景渐变（调用方负责按 bgAlpha 缩放 alpha）。 */
    public void setBackdropColors(int[] colors, float alphaScale) {
        if (colors == null || colors.length < 2) return;
        mBgColors = colors;
        mBgAlphaScale = alphaScale;
        recomputeBackdropLuma();
        invalidate();
    }

    public void setAccentColor(int accent) {
        mAccentColor = accent;
        invalidate();
    }

    /** 玻璃后方的大图标（折射的主要结构来源）。 */
    public void setBackdropIcon(Drawable icon) {
        mIcon = icon;
        recomputeBackdropLuma();
        invalidate();
    }

    // ==================== 动态光效配置与交互输入 ====================

    /**
     * 动态光效配置（设置页驱动）。
     *
     * @param enabled        总开关（false = 纯静态折射，帧循环不运行）
     * @param strength       动态强度 0..1（0 = 静态设计值）
     * @param adaptiveShadow 自适应阴影强度 0..1
     * @param accentFollow   高光色跟随系统强调色
     */
    public void setDynamicConfig(boolean enabled, float strength, float adaptiveShadow,
                                 boolean accentFollow) {
        mDynamicEnabled = enabled;
        mAccentFollow = accentFollow;
        mDynamics.setConfig(strength, adaptiveShadow);
        if (accentFollow) {
            maybeRefreshAccent();
        } else if (mSystemAccent != 0) {
            // 关闭跟随：恢复样式调色板高光色
            mSystemAccent = 0;
            applyHighlightArgb(mBaseHighlightArgb);
        }
        maybeStartFrameLoop();
    }

    /** 触摸光源位置（归一化 -1..1，相对卡片中心）。由手势处理器调用。 */
    public void setTouchLight(float nx, float ny, boolean active) {
        mDynamics.setTouch(nx, ny, active);
    }

    /** 按压反馈 0..1。 */
    public void setPressProgress(float p) {
        mDynamics.setPress(p);
    }

    /** 滑动能量（拖动过程中持续注入，光泽加速流动）。 */
    public void addSwipeEnergy(float amount) {
        mDynamics.addSwipe(amount);
    }

    /** 消息弹出脉冲：一次高光/环境光爆发，随后衰减。 */
    public void pulseSceneEnergy(float amount) {
        mDynamics.pulseScene(amount);
    }

    /**
     * 开始入场：环境光/阴影随卡片飞入渐亮，并注入一次弹出脉冲。
     *
     * @param durationMs 入场时长（用于把进度 ramp 对齐动画节奏）
     */
    public void beginEntrance(long durationMs) {
        mDynamics.beginEntrance((float) Math.max(160L, durationMs));
        mDynamics.pulseScene(1f);
        maybeStartFrameLoop();
    }

    /** 视图池复用前的状态复位（内容由 rebindBuild 另行刷新）。 */
    public void resetDynamic() {
        mDynamics.reset();
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
        maybeStartFrameLoop();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        maybeStartFrameLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopFrameLoop();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 深色模式 / 壁纸切换会改变系统强调色
        if (mAccentFollow) {
            mSystemAccent = 0;
            maybeRefreshAccent();
        }
    }

    // ==================== 帧循环 ====================

    /** 启动条件：动态开启 + 强度>0 + 引擎可用 + uniform 就绪 + 已挂载 + 尺寸已知。 */
    private void maybeStartFrameLoop() {
        boolean should = mDynamicEnabled
                && mDynamics.strength() > 0.01f
                && mImpl != null && !mImpl.isBroken()
                && mUniforms != null
                && getWidth() > 0 && getHeight() > 0
                && isAttachedToWindow();
        if (should && !mFrameRunning) {
            mFrameRunning = true;
            mLastFrameNs = 0L;
            Choreographer.getInstance().postFrameCallback(mFrameCallback);
        } else if (!should && mFrameRunning) {
            stopFrameLoop();
        }
    }

    private void stopFrameLoop() {
        if (!mFrameRunning) return;
        mFrameRunning = false;
        Choreographer.getInstance().removeFrameCallback(mFrameCallback);
        // 冻结前把动态 uniform 复位到静态设计值，避免停在中途帧。
        // 注意：不重置 mDynamics 内部状态——滑块调节导致的重启应从中途继续，
        // 只有 beginEntrance（新一次弹出）才做完整 reset。
        if (mImpl != null && !mImpl.isBroken() && mUniforms != null) {
            mImpl.bindDynamic(mUniforms.withDynamic(
                    LensUniforms.DEFAULT_LIGHT_X, LensUniforms.DEFAULT_LIGHT_Y,
                    LensUniforms.DEFAULT_AMBIENT, LensUniforms.DEFAULT_SHADOW,
                    LensUniforms.DEFAULT_FLOW_PHASE));
        }
    }

    /** 每帧：演算 → 推送动态 uniform → 重绘。 */
    private void stepFrame(float dt) {
        mDynamics.step(dt);
        mImpl.bindDynamicRaw(mDynamics.lightX(), mDynamics.lightY(), mDynamics.ambient(),
                mDynamics.shadow(), mDynamics.flowPhase());
        invalidate();
    }

    // ==================== 背景内容适应（环境光基准） ====================

    /**
     * 采样"玻璃后方内容"的平均亮度：渐变底色（权重 0.6）与 App 大图标（权重 0.4）。
     *
     * <p>内容变化时才计算（8×8 降采样，微秒级），不随帧刷新——光效引擎以此作为
     * 环境光基准，实现"持续适应其下方背景内容"。</p>
     */
    private void recomputeBackdropLuma() {
        float grad = 0f;
        float wSum = 0f;
        int n = mBgColors.length;
        for (int i = 0; i < n; i++) {
            // 顶部权重高：视觉重心在卡片上部
            float w = i == 0 ? 0.6f : (i == n - 1 ? 0.4f : 0.6f / Math.max(1, n - 1));
            grad += lumaOf(mBgColors[i]) * w;
            wSum += w;
        }
        if (wSum > 0f) grad /= wSum;

        float icon = iconLuma(mIcon);
        mDynamics.setBackdropLuma(icon >= 0f ? grad * 0.55f + icon * 0.45f : grad);
    }

    /** 图标平均亮度 0..1；不可采样返回 -1（调用方回落纯渐变亮度）。 */
    private static float iconLuma(Drawable d) {
        if (d == null) return -1f;
        try {
            Bitmap bm = null;
            if (d instanceof BitmapDrawable) {
                bm = ((BitmapDrawable) d).getBitmap();
            } else {
                int w = d.getIntrinsicWidth();
                int h = d.getIntrinsicHeight();
                if (w <= 0 || h <= 0) return -1f;
                int n = 8;
                bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm);
                d.setBounds(0, 0, n, n);
                d.draw(c);
            }
            return bitmapLuma(bm);
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** 位图降采样到 8×8 后的平均亮度（透明像素不计入）。 */
    private static float bitmapLuma(Bitmap bm) {
        if (bm == null || bm.isRecycled()) return -1f;
        try {
            int n = 8;
            Bitmap small = bm.getWidth() == n && bm.getHeight() == n
                    ? bm : Bitmap.createScaledBitmap(bm, n, n, true);
            int[] px = new int[n * n];
            small.getPixels(px, 0, n, 0, 0, n, n);
            if (small != bm) small.recycle();
            double sum = 0;
            int count = 0;
            for (int p : px) {
                if (((p >>> 24) & 0xFF) < 16) continue;
                sum += lumaOf(p);
                count++;
            }
            return count == 0 ? -1f : (float) (sum / count);
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** Rec.709 相对亮度 0..1。 */
    private static double lumaOf(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    // ==================== 强调色跟随 ====================

    /** 系统强调色变化时刷新高光 uniform（内容未变，只换色）。 */
    private void maybeRefreshAccent() {
        if (!mAccentFollow) return;
        int accent = AccentColors.systemAccent(getContext());
        if (accent == 0 || accent == mSystemAccent) return;
        mSystemAccent = accent;
        applyHighlightArgb(AccentColors.lighten(accent, 0.35f));
    }

    /** 以新高光色重建 uniform 并全量推送（保留其余参数与当前动态状态）。 */
    private void applyHighlightArgb(int argb) {
        if (mImpl == null || mUniforms == null) return;
        try {
            mUniforms = LensUniforms.createDynamic(
                    mUniforms.radiusPx, mUniforms.edgeWidthPx, mUniforms.thicknessPx,
                    mUniforms.edgeBoost, mUniforms.dispersion, mUniforms.specular,
                    mUniforms.ior, argb, mUniforms.phase,
                    mDynamics.lightX(), mDynamics.lightY(), mDynamics.ambient(),
                    mDynamics.shadow(), mDynamics.flowPhase());
            applyUniforms();
        } catch (Throwable ignored) {
        }
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
