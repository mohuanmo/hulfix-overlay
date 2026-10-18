package com.example.hulfix.overlay;

import android.view.Choreographer;
import android.view.View;

/**
 * 轻量物理弹簧动画（自研，替代 androidx.dynamicanimation —— 模块不能引入 AndroidX
 * 依赖，而 SystemUI 的类加载器里也没有这些类）。
 *
 * <ul>
 *   <li>半隐式欧拉积分，数值稳定，参数语义与 {@code androidx} SpringAnimation 一致
 *       （stiffness 刚度 / dampingRatio 阻尼比）；</li>
 *   <li>由 {@link Choreographer} 驱动，天然对齐 vsync，120Hz 设备自动跑满 120fps；</li>
 *   <li>每帧只写一个 float 属性，无反射、无装箱；</li>
 *   <li>到位后自动吸附并回调，不会无限抖动。</li>
 * </ul>
 */
public final class SpringAnimation implements Choreographer.FrameCallback {

    public static final int PROP_TRANSLATION_X = 0;
    public static final int PROP_TRANSLATION_Y = 1;
    public static final int PROP_SCALE_X = 2;
    public static final int PROP_SCALE_Y = 3;
    public static final int PROP_ALPHA = 4;

    public interface OnEndListener {
        void onEnd(SpringAnimation animation, boolean settled);
    }

    private static final float DEFAULT_STIFFNESS = 420f;
    private static final float DEFAULT_DAMPING_RATIO = 0.72f;
    private static final long MAX_FRAME_NS = 64_000_000L; // 卡帧保护：单帧最多按 64ms 积分
    private static final float VELOCITY_THRESHOLD = 0.01f;
    private static final float VALUE_THRESHOLD = 0.01f;

    private final View mView;
    private final int mProperty;

    private float mValue;
    private float mTarget;
    private float mVelocity;
    private float mStiffness = DEFAULT_STIFFNESS;
    private float mDampingRatio = DEFAULT_DAMPING_RATIO;
    private boolean mRunning;
    private boolean mCanceled;
    private long mLastFrameNs = -1;
    private OnEndListener mListener;

    public SpringAnimation(View view, int property) {
        mView = view;
        mProperty = property;
    }

    public static SpringAnimation of(View view, int property, float target) {
        SpringAnimation a = new SpringAnimation(view, property);
        a.mTarget = target;
        return a;
    }

    public SpringAnimation setStiffness(float stiffness) {
        mStiffness = Math.max(1f, stiffness);
        return this;
    }

    /** 阻尼比：1 = 临界阻尼（无回弹）；&lt;1 欠阻尼（有弹性）；&gt;1 过阻尼。 */
    public SpringAnimation setDampingRatio(float ratio) {
        mDampingRatio = Math.max(0.05f, ratio);
        return this;
    }

    public SpringAnimation setDampingRatioBouncy() {
        return setDampingRatio(0.5f);
    }

    public SpringAnimation setDampingRatioNoBouncy() {
        return setDampingRatio(1f);
    }

    public SpringAnimation setVelocity(float unitsPerSecond) {
        mVelocity = unitsPerSecond;
        return this;
    }

    public SpringAnimation setStartValue(float value) {
        mValue = value;
        applyValue();
        return this;
    }

    public SpringAnimation setStartVelocity(float unitsPerSecond) {
        mVelocity = unitsPerSecond;
        return this;
    }

    public SpringAnimation setListener(OnEndListener l) {
        mListener = l;
        return this;
    }

    public float getValue() {
        return mValue;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void start() {
        if (mRunning) return;
        mRunning = true;
        mCanceled = false;
        mLastFrameNs = -1;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void cancel() {
        if (!mRunning) return;
        mRunning = false;
        mCanceled = true;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    /** 立即结束并吸附到目标值。 */
    public void skipToEnd() {
        cancel();
        mValue = mTarget;
        applyValue();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mRunning) return;
        if (mLastFrameNs < 0) {
            mLastFrameNs = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        long deltaNs = frameTimeNanos - mLastFrameNs;
        if (deltaNs <= 0) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        if (deltaNs > MAX_FRAME_NS) deltaNs = MAX_FRAME_NS;
        mLastFrameNs = frameTimeNanos;
        float dt = deltaNs / 1_000_000_000f;

        float damping = mDampingRatio * 2f * (float) Math.sqrt(mStiffness);
        float springForce = -mStiffness * (mValue - mTarget);
        float dampingForce = -damping * mVelocity;
        mVelocity += (springForce + dampingForce) * dt;
        mValue += mVelocity * dt;

        if (Math.abs(mValue - mTarget) < VALUE_THRESHOLD
                && Math.abs(mVelocity) < VELOCITY_THRESHOLD * mStiffness * 0.05f) {
            mValue = mTarget;
            applyValue();
            mRunning = false;
            if (mListener != null) mListener.onEnd(this, true);
            return;
        }
        applyValue();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void applyValue() {
        switch (mProperty) {
            case PROP_TRANSLATION_X:
                mView.setTranslationX(mValue);
                break;
            case PROP_TRANSLATION_Y:
                mView.setTranslationY(mValue);
                break;
            case PROP_SCALE_X:
                mView.setScaleX(mValue);
                break;
            case PROP_SCALE_Y:
                mView.setScaleY(mValue);
                break;
            case PROP_ALPHA:
                mView.setAlpha(mValue);
                break;
            default:
        }
    }
}
