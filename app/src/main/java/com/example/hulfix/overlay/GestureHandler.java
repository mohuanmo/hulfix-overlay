package com.example.hulfix.overlay;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import com.example.hulfix.core.Settings;

/**
 * 悬浮窗手势处理：方向锁定的滑动消失、下拉展开通知栏、点击打开、长按忽略。
 *
 * <p>移植自旧版 MainHook 的触摸逻辑，全部阈值/开关改为由 {@link Settings} 驱动，
 * 并通过 {@link Callback} 与控制器解耦（控制器决定状态机何时接受触摸）。</p>
 */
public final class GestureHandler implements View.OnTouchListener {

    public interface Callback {
        /** 当前是否接受触摸（入场/退场动画进行中返回 false）。 */
        boolean isInteractive();

        void onTap();

        /** 用户划掉。direction: 0=左 1=上 2=右 3=下 */
        void onSwipeDismiss(int direction);

        void onPullDownExpand();

        void onLongPressIgnore();

        /** 手指按住并开始移动。 */
        void onDragStart();

        /** 拖动中（dx/dy 相对按下点）。 */
        void onDrag(float dx, float dy, boolean horizontalLocked, boolean verticalLocked);

        /** 拖动透明度反馈。 */
        void onDragAlpha(float alpha);

        /** 抬手/取消。 */
        void onDragEnd(boolean hasMoved);

        void onPressFeedback(boolean down);

        /**
         * 触摸光源（归一化 -1..1，相对被触摸视图中心；y 向下为正）。
         *
         * <p>动态光效引擎用：手指按在玻璃上时，镜面高光跟随手指位置，
         * 抬手后光源平滑游弋回默认方向。down=false 表示光源脱手。</p>
         */
        void onLightTouch(float nx, float ny, boolean down);
    }

    private static final float DIRECTION_LOCK_SLOP = 25f;
    private static final float CLICK_THRESHOLD = 8f;
    private static final float SWIPE_INTENT_THRESHOLD = 40f;
    private static final float VISUAL_FACTOR = 2.5f;

    private final Settings mSettings;
    private final Callback mCallback;

    private float mStartX, mStartY;
    private boolean mLockedHorizontal, mLockedVertical, mHasMoved;
    private float mMaxDx, mMaxDy;
    private VelocityTracker mTracker;
    private boolean mLongPressFired;
    /** onScreenLocation 复用数组（MOVE 事件高频，避免每帧分配）。 */
    private final int[] mLocation = new int[2];
    private final Runnable mLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSettings.gestureLongPress && !mLongPressFired && !mHasMoved) {
                mLongPressFired = true;
                mCallback.onLongPressIgnore();
            }
        }
    };

    public GestureHandler(Settings settings, Callback callback) {
        mSettings = settings;
        mCallback = callback;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (!mCallback.isInteractive()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mStartX = event.getRawX();
                mStartY = event.getRawY();
                mLockedHorizontal = false;
                mLockedVertical = false;
                mHasMoved = false;
                mMaxDx = 0f;
                mMaxDy = 0f;
                mLongPressFired = false;
                if (mTracker != null) {
                    mTracker.recycle();
                }
                mTracker = VelocityTracker.obtain();
                mTracker.addMovement(event);
                mCallback.onPressFeedback(true);
                reportLight(v, event, true);
                if (mSettings.gestureLongPress) {
                    v.postDelayed(mLongPressRunnable, 500);
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - mStartX;
                float dy = event.getRawY() - mStartY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                reportLight(v, event, true);
                if (!mLockedHorizontal && !mLockedVertical && distance > DIRECTION_LOCK_SLOP) {
                    mHasMoved = true;
                    v.removeCallbacks(mLongPressRunnable);
                    double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
                    if (angleDeg < 0) angleDeg += 360;
                    boolean horizontal = (angleDeg >= 315 || angleDeg <= 45)
                            || (angleDeg >= 135 && angleDeg <= 225);
                    if (horizontal && mSettings.gestureHorizontal) {
                        mLockedHorizontal = true;
                        mCallback.onDragStart();
                    } else if (!horizontal && mSettings.gestureVertical) {
                        mLockedVertical = true;
                        mCallback.onDragStart();
                    }
                } else if (mLockedHorizontal || mLockedVertical) {
                    mHasMoved = true;
                }

                mMaxDx = Math.max(mMaxDx, Math.abs(dx));
                mMaxDy = Math.max(mMaxDy, Math.abs(dy));
                if (mTracker != null) mTracker.addMovement(event);

                float progress = Math.min(1f, distance / (mSettings.swipeThresholdPx * VISUAL_FACTOR));
                if (mLockedHorizontal) {
                    mCallback.onDrag(dx, 0f, true, false);
                    mCallback.onDragAlpha(Math.max(0.15f, 1f - progress));
                } else if (mLockedVertical) {
                    mCallback.onDrag(0f, dy, false, true);
                    mCallback.onDragAlpha(Math.max(0.15f, 1f - progress));
                } else if (mHasMoved) {
                    mCallback.onDrag(dx, dy, false, false);
                    mCallback.onDragAlpha(Math.max(0.5f, 1f - progress * 0.6f));
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                v.removeCallbacks(mLongPressRunnable);
                recycleTracker();
                mCallback.onPressFeedback(false);
                reportLight(v, event, false);
                mCallback.onDragEnd(mHasMoved);
                return true;

            case MotionEvent.ACTION_UP: {
                v.removeCallbacks(mLongPressRunnable);
                mCallback.onPressFeedback(false);
                reportLight(v, event, false);
                float totalDx = event.getRawX() - mStartX;
                float totalDy = event.getRawY() - mStartY;
                float vx = 0f, vy = 0f;
                if (mTracker != null) {
                    mTracker.addMovement(event);
                    mTracker.computeCurrentVelocity(1000);
                    vx = mTracker.getXVelocity();
                    vy = mTracker.getYVelocity();
                }
                recycleTracker();

                boolean horizontal;
                if (mLockedHorizontal) horizontal = true;
                else if (mLockedVertical) horizontal = false;
                else horizontal = Math.abs(totalDx) > Math.abs(totalDy);

                // 点击（尊重"点击打开通知"开关：关闭时仅回弹，不打开也不消失）
                if (!mHasMoved && !mLongPressFired
                        && Math.abs(totalDx) < CLICK_THRESHOLD
                        && Math.abs(totalDy) < CLICK_THRESHOLD) {
                    if (mSettings.gestureTap) {
                        mCallback.onTap();
                    } else {
                        mCallback.onDragEnd(false);
                    }
                    return true;
                }

                // 长按已触发忽略：不再回弹，交给 dismiss 流程
                if (mLongPressFired && !mHasMoved) {
                    return true;
                }

                // 下拉展开通知栏
                if (mSettings.gesturePullDown && totalDy > 120f && !horizontal) {
                    mCallback.onPullDownExpand();
                    return true;
                }

                // 过阈值 → 消失（必须尊重方向开关：未建立方向锁的滑动可能是被关闭的方向）
                float dist = (float) Math.sqrt(totalDx * totalDx + totalDy * totalDy);
                boolean dirAllowed = horizontal
                        ? mSettings.gestureHorizontal : mSettings.gestureVertical;
                boolean crossed = dist >= mSettings.swipeThresholdPx;
                boolean fastFling = Math.abs(vx) > mSettings.flingVelocityPxS
                        || Math.abs(vy) > mSettings.flingVelocityPxS;
                if (dirAllowed && (crossed || (fastFling && (mMaxDx > SWIPE_INTENT_THRESHOLD
                        || mMaxDy > SWIPE_INTENT_THRESHOLD)))) {
                    int dir;
                    if (horizontal) {
                        dir = totalDx > 0 ? 2 : 0;
                    } else {
                        dir = totalDy > 0 ? 3 : 1;
                    }
                    mCallback.onSwipeDismiss(dir);
                    return true;
                }

                // 未过阈值 → 回弹
                mCallback.onDragEnd(mHasMoved);
                return true;
            }
            default:
                return false;
        }
    }

    private void recycleTracker() {
        if (mTracker != null) {
            mTracker.recycle();
            mTracker = null;
        }
    }

    /**
     * 把触点换算成相对视图中心的光源位置（归一化 -1..1）回报给控制器。
     *
     * <p>动态光效引擎的输入：手指即光源。触点可能在视图外（按下后滑出卡片），
     * 归一化后由引擎侧钳制，这里只负责换算与缩放。</p>
     */
    private void reportLight(View v, MotionEvent event, boolean down) {
        if (!down) {
            mCallback.onLightTouch(0f, 0f, false);
            return;
        }
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        v.getLocationOnScreen(mLocation);
        float nx = (event.getRawX() - (mLocation[0] + w * 0.5f)) / (w * 0.5f);
        float ny = (event.getRawY() - (mLocation[1] + h * 0.5f)) / (h * 0.5f);
        if (nx < -1f) nx = -1f;
        if (nx > 1f) nx = 1f;
        if (ny < -1f) ny = -1f;
        if (ny > 1f) ny = 1f;
        mCallback.onLightTouch(nx, ny, true);
    }
}
