package com.example.hulfix.ui;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.example.hulfix.core.Settings;
import com.example.hulfix.overlay.AnimPresets;
import com.example.hulfix.overlay.OverlayRenderer;
import com.example.hulfix.overlay.StyleSpec;

/**
 * 样式预览控件：在设置页内用与真实悬浮窗完全相同的 {@link OverlayRenderer} 渲染
 * 一条模拟通知，并可反复播放进入/退出动画（所见即所得）。
 *
 * <p>预览使用固定示例数据；预览视图限宽居中，平板/折叠屏下不会拉伸变形。</p>
 *
 * <p><b>性能设计（设置页卡顿的主要修复点）</b>：</p>
 * <ul>
 *   <li><b>外观指纹</b>：{@link #setStyle} 先比对指纹，未变化时只热更新真折射
 *       uniform，<b>不重建视图树</b>。液态玻璃预览每次重建都会经
 *       {@link LensBackdropView} → {@link LensEngine#tryCreate()} 编译一次
 *       AGSL RuntimeShader（毫秒级重操作）——页面切换 / 滑块拖动都会触发
 *       refresh，不判指纹就意味着每次切换都重新编译一遍；</li>
 *   <li><b>轻量模式</b>：{@link #createLightweight} 用于样式列表里的迷你预览
 *       （190×96dp），跳过真折射着色器（该尺寸下折射细节不可见，视觉差异可忽略），
 *       把"N 张卡片 = N 次着色器编译"降为零。</li>
 * </ul>
 */
public class StylePreviewView extends FrameLayout implements AnimPresets.Host {

    private static final String DEMO_APP = "示例应用";
    private static final String DEMO_TITLE = "你有一条新消息";
    private static final String DEMO_TEXT = "这是悬浮窗的正文预览效果，看看这个样式怎么样。";

    private StyleSpec mSpec;
    private Settings mSettings = new Settings();
    private OverlayRenderer.Build mBuild;
    private final AnimPresets.Player mPlayer = new AnimPresets.Player();
    private boolean mDark = true;
    /** 轻量模式：跳过 AGSL 真折射（迷你预览用）。 */
    private boolean mLightweight;
    /** 上次重建时的外观指纹；用于跳过不必要的重建（见类注释）。 */
    private String mAppliedFingerprint;

    public StylePreviewView(Context context) {
        super(context);
        init();
    }

    public StylePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StylePreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * 创建轻量预览实例（样式列表里的迷你卡片用）。
     *
     * <p>轻量模式强制走传统渐变背景：迷你预览只有 190×96dp，真折射的边缘细节
     * 根本不可见，而每个真折射 {@link LensBackdropView} 都要编译一次 AGSL
     * 着色器——列表有 4~6 张卡时这就是可感知的建页卡顿。</p>
     */
    public static StylePreviewView createLightweight(Context context) {
        StylePreviewView v = new StylePreviewView(context);
        v.mLightweight = true;
        return v;
    }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        int pad = dp(16);
        setPadding(pad, pad, pad, pad);
    }

    public void setSettings(Settings settings) {
        mSettings = settings != null ? settings : new Settings();
    }

    public void setStyle(StyleSpec spec, boolean dark) {
        // 指纹未变 → 不重建：滑块拖动 / 页面切换的 refresh 走这条捷径，
        // 避免反复编译 AGSL 着色器与重建整棵视图树
        if (mBuild != null && mAppliedFingerprint != null
                && mAppliedFingerprint.equals(fingerprint(spec, dark, mSettings))) {
            refreshLensUniforms();
            return;
        }
        mSpec = spec;
        mDark = dark;
        rebuild();
        mAppliedFingerprint = fingerprint(spec, dark, mSettings);
    }

    /**
     * 外观指纹：只收影响<b>构建路径</b>的字段（几何 / 配色 / 显隐 / 明暗）。
     *
     * <p>真折射滑杆参数（厚度 / 边缘 / 色散 / 高光 / IOR）刻意不入指纹——它们
     * 只改 uniform，走 {@link #refreshLensUniforms} 热更新即可，否则拖一次滑杆
     * 就重建一次视图树 + 编译一次着色器。</p>
     */
    private static String fingerprint(StyleSpec spec, boolean dark, Settings s) {
        return spec.contentHash() + "|" + dark
                + "|lens=" + s.lensEnabled
                + "|bg=" + s.bgAlpha
                + "|corner=" + s.cornerScale
                + "|w=" + s.widthScale
                + "|lines=" + s.maxTextLines
                + "|app=" + s.showAppName;
    }

    /** 滑块直改 uniform：按最新设置重算折射参数并推送（不重建、不编译）。 */
    private void refreshLensUniforms() {
        if (mBuild == null || mBuild.lensView == null || mBuild.lensUniforms == null) return;
        try {
            mBuild.lensUniforms = OverlayRenderer.lensUniformsFor(getContext(), mSpec, mSettings,
                    mBuild.cornerRadiusPx, mBuild.lensUniforms.highlightArgb());
            mBuild.lensView.setLensUniforms(mBuild.lensUniforms);
            // 动态光效配置同样热更新（开关 / 强度 / 阴影 / 强调色跟随）
            mBuild.lensView.setDynamicConfig(mSettings.lensDynamic, mSettings.lensDynamicStrength,
                    mSettings.lensAdaptiveShadow, mSettings.lensAccentFollow);
        } catch (Throwable ignored) {
        }
    }

    public void replay() {
        rebuild();
        playEnter();
    }

    public void playEnter() {
        if (mBuild == null) return;
        mBuild.root.setAlpha(0f);
        // 动态光效：预览也走真实入场（环境光渐亮 + 弹出脉冲），所见即所得
        if (mBuild.lensView != null) {
            mBuild.lensView.beginEntrance(Math.max(160, mSettings.enterDurationMs));
        }
        AnimPresets.playEnter(this, mSpec, mSettings, mPlayer, null);
    }

    public void playExit() {
        if (mBuild == null) return;
        AnimPresets.playExit(this, mSpec, mSettings, mPlayer, -1, this::rebuild);
    }

    private void rebuild() {
        mPlayer.cancelAll();
        removeAllViews();
        if (mSpec == null) return;
        try {
            // 传入演示图标：一方面让预览更接近真实通知；更重要的是液态玻璃样式的
            // 真折射背景层需要"玻璃后方有内容"才能看出折射结构。
            // 轻量模式（迷你预览）改用关闭真折射的设置副本：该尺寸下折射细节
            // 不可见，却能为每张卡片省下一次 AGSL 着色器编译。
            Settings effective = mSettings;
            if (mLightweight && mSettings.lensEnabled) {
                effective = mSettings.copy();
                effective.lensEnabled = false;
            }
            mBuild = OverlayRenderer.build(getContext(), mSpec, effective, mDark,
                    DEMO_APP, DEMO_TITLE, DEMO_TEXT,
                    Icon.createWithResource(getContext(),
                            com.example.hulfix.R.drawable.ic_notification), 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    mBuild.widthPx, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            addView(mBuild.root, lp);
            // 默认直接可见；只有主动 replay() 时才从透明开始播放入场动画
            mBuild.root.setAlpha(1f);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mPlayer.cancelAll();
    }

    // ==================== AnimPresets.Host ====================

    @Override
    public View root() {
        return mBuild != null ? mBuild.root : this;
    }

    @Override
    public View icon() {
        return mBuild != null ? mBuild.iconView : null;
    }

    @Override
    public View appName() {
        return mBuild != null ? mBuild.appNameView : null;
    }

    @Override
    public View title() {
        return mBuild != null ? mBuild.titleView : null;
    }

    @Override
    public View text() {
        return mBuild != null ? mBuild.textView : null;
    }

    @Override
    public View pulse() {
        return mBuild != null ? mBuild.pulseDot : null;
    }

    @Override
    public float widthPx() {
        return mBuild != null ? mBuild.widthPx : getWidth();
    }

    @Override
    public float heightPx() {
        return mBuild != null ? mBuild.estimatedHeightPx : getHeight();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
