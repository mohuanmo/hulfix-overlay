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
        mSpec = spec;
        mDark = dark;
        rebuild();
    }

    public void replay() {
        rebuild();
        playEnter();
    }

    public void playEnter() {
        if (mBuild == null) return;
        mBuild.root.setAlpha(0f);
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
            // 真折射背景层需要"玻璃后方有内容"才能看出折射结构
            mBuild = OverlayRenderer.build(getContext(), mSpec, mSettings, mDark,
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
