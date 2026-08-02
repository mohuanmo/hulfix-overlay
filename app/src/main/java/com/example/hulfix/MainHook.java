package com.example.hulfix;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "HULFix";
    private static final long AUTO_DISMISS_MS = 5000;
    private static final long ANIM_DURATION_MS = 220;

    /* ===== 配置开关 ===== */
    // true  = 半透明磨砂感（90%白，AOSP Android 13 可用）
    // false = 纯白圆角（最稳妥，所有系统通用）
    private static final boolean USE_FROSTED_GLASS = true;

    /* ===== 窗口固定位置（基于用户设备像素坐标） ===== */
    // 左上(1549,80)  右上(2042,80)
    // 左下(1550,196) 右下(2039,196)
    private static final int WIN_X = 1549;
    private static final int WIN_Y = 80;
    private static final int WIN_W = 493;   // 2042 - 1549
    private static final int WIN_H = 116;   // 196 - 80

    /* ===== 手势阈值 ===== */
    private static final float SWIPE_THRESHOLD = 100f;

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    // 单例：只保留最新一条通知
    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private Runnable mCurrentDismissRunnable = null;
    private boolean mIsAnimating = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v3 loaded ======");
        XposedBridge.log(TAG + ": UI mode: " + (USE_FROSTED_GLASS ? "Frosted" : "White"));

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
    }

    /* ================================================================ */
    /*  Hook 点 1：系统决定显示 Heads-Up 时触发                        */
    /* ================================================================ */
    private void hookHeadsUpIsVisible(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rowClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(rowClass, "setHeadsUpIsVisible",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            View rowView = (View) param.thisObject;

                            // 只处理横屏
                            if (!isLandscape(rowView)) return;

                            StatusBarNotification sbn = getSbnFromRow(rowView);
                            if (sbn == null) return;

                            // 【核心过滤】判断这条消息是否真的应该弹 Heads-Up
                            if (!shouldShowHeadsUp(sbn, rowView)) {
                                XposedBridge.log(TAG + ": Filtered (not heads-up worthy): " + sbn.getKey());
                                return;
                            }

                            String key = sbn.getKey();

                            // 阻止系统自己显示（修复横屏 Bug）
                            param.setResult(null);

                            // 去重：已经在显示的同一条通知，跳过
                            if (key.equals(mCurrentKey)) return;

                            XposedBridge.log(TAG + ": New HeadsUp: " + key);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            showCustomHeadsUp(sbn);

                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setHeadsUpIsVisible error: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked setHeadsUpIsVisible");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setHeadsUpIsVisible hook failed: " + t);
        }
    }

    /* ================================================================ */
    /*  Hook 点 2：动画结束后的兜底触发                                */
    /* ================================================================ */
    private void hookAnimatingAway(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rowClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader
            );

            XposedHelpers.findAndHookMethod(rowClass, "setHeadsUpAnimatingAway", boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            boolean animatingAway = (boolean) param.args[0];
                            if (animatingAway) return;

                            View rowView = (View) param.thisObject;
                            if (!isLandscape(rowView)) return;

                            StatusBarNotification sbn = getSbnFromRow(rowView);
                            if (sbn == null) return;

                            if (!shouldShowHeadsUp(sbn, rowView)) return;

                            String key = sbn.getKey();
                            if (key.equals(mCurrentKey)) return;

                            XposedBridge.log(TAG + ": AnimatingAway fallback: " + key);

                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            showCustomHeadsUp(sbn);

                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setHeadsUpAnimatingAway error: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked setHeadsUpAnimatingAway");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setHeadsUpAnimatingAway hook failed: " + t);
        }
    }

    /* ================================================================ */
    /*  通知过滤：只让真正该弹 Heads-Up 的消息通过                      */
    /* ================================================================ */
    private boolean shouldShowHeadsUp(StatusBarNotification sbn, View rowView) {
        try {
            // 1. 优先检查 SystemUI 内部标记：row 是否处于 headsUp 状态
            Boolean isHeadsUp = (Boolean) XposedHelpers.callMethod(rowView, "isHeadsUp");
            if (isHeadsUp != null && isHeadsUp) return true;
        } catch (Throwable ignored) {}

        Notification n = sbn.getNotification();

        // 2. 传统 priority 检查（很多应用仍然设置这个）
        if (n.priority >= Notification.PRIORITY_HIGH) return true;

        // 3. 重要类别（来电、闹钟、消息、事件）
        String cat = n.category;
        if (Notification.CATEGORY_CALL.equals(cat) ||
            Notification.CATEGORY_ALARM.equals(cat) ||
            Notification.CATEGORY_MESSAGE.equals(cat) ||
            Notification.CATEGORY_EVENT.equals(cat) ||
            Notification.CATEGORY_REMINDER.equals(cat)) {
            return true;
        }

        // 4. 检查 flags（FLAG_HIGH_PRIORITY 等）
        if ((n.flags & 0x00000080) != 0) return true; // FLAG_HIGH_PRIORITY

        // 5. 检查是否使用了全屏意图（通常用于来电、闹钟）
        if (n.fullScreenIntent != null) return true;

        // 默认不过滤，让系统已经判定为 Heads-Up 的通过
        // 如果上面都未命中，但系统调用了 setHeadsUpIsVisible，通常也是应该显示的
        return true;
    }

    /* ================================================================ */
    /*  反射获取 StatusBarNotification                                  */
    /* ================================================================ */
    private StatusBarNotification getSbnFromRow(View rowView) {
        try {
            Object entry = XposedHelpers.getObjectField(rowView, "mEntry");
            if (entry == null) {
                entry = XposedHelpers.getObjectField(rowView, "mSbn");
            }
            if (entry instanceof StatusBarNotification) {
                return (StatusBarNotification) entry;
            }
            if (entry != null) {
                Object sbn = XposedHelpers.getObjectField(entry, "mSbn");
                if (sbn instanceof StatusBarNotification) {
                    return (StatusBarNotification) sbn;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getSbn failed: " + t);
        }
        return null;
    }

    private boolean isLandscape(View view) {
        return view.getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE;
    }

    /* ================================================================ */
    /*  显示自定义 Heads-Up 悬浮窗                                      */
    /* ================================================================ */
    private void showCustomHeadsUp(StatusBarNotification sbn) {
        if (mContext == null || mWindowManager == null) return;
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        final String key = sbn.getKey();

        mHandler.post(() -> {
            try {
                // 新通知来时，旧通知直接替换（取消旧动画和定时器）
                removeCurrentOverlayInternal(false);

                Notification notification = sbn.getNotification();
                Bundle extras = notification.extras;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = (bigText != null && bigText.length() > 0)
                    ? bigText.toString()
                    : (text != null ? text.toString() : "");

                // ==================== 根容器 ====================
                LinearLayout container = new LinearLayout(mContext);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setPadding(18, 12, 18, 12);
                container.setGravity(Gravity.CENTER_VERTICAL);

                // 背景：两套方案切换
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(24);
                if (USE_FROSTED_GLASS) {
                    // 方案 A：半透明磨砂感（90% 不透明度）
                    // AOSP Android 13 下效果接近原生通知背景
                    bg.setColor(0xE6FFFFFF);
                    // 加一层很淡的描边，增强层次感
                    bg.setStroke(1, 0x33FFFFFF);
                } else {
                    // 方案 B：纯白圆角（最稳妥）
                    bg.setColor(0xFFFFFFFF);
                }
                container.setBackground(bg);

                // 阴影（API 21+，增强立体感）
                container.setElevation(12);

                // ==================== 图标 ====================
                ImageView iconView = new ImageView(mContext);
                android.graphics.drawable.Icon icon = notification.getSmallIcon();
                if (icon != null) {
                    iconView.setImageIcon(icon);
                }
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(40, 40);
                iconLp.gravity = Gravity.CENTER_VERTICAL;
                iconView.setLayoutParams(iconLp);
                container.addView(iconView);

                // ==================== 文字区域 ====================
                LinearLayout textContainer = new LinearLayout(mContext);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                );
                textLp.setMargins(12, 0, 0, 0);
                textLp.gravity = Gravity.CENTER_VERTICAL;
                textContainer.setLayoutParams(textLp);

                TextView titleView = new TextView(mContext);
                titleView.setText(title);
                titleView.setTextColor(0xFF000000);
                titleView.setTextSize(13);
                titleView.setMaxLines(1);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(titleView);

                TextView contentView = new TextView(mContext);
                contentView.setText(content);
                contentView.setTextColor(0xFF444444);
                contentView.setTextSize(11);
                contentView.setMaxLines(1);
                contentView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(contentView);

                container.addView(textContainer);

                // ==================== 点击跳转 ====================
                PendingIntent contentIntent = notification.contentIntent;
                container.setOnClickListener(v -> {
                    if (mIsAnimating) return;
                    try {
                        if (contentIntent != null) {
                            contentIntent.send();
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": PendingIntent send failed: " + e);
                    }
                    animateRemoveCurrent(0, -80); // 点击后轻微上滑消失
                });

                // ==================== 触摸手势 ====================
                container.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;
                    boolean isClick = true;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (mIsAnimating) return true;

                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startX = event.getRawX();
                                startY = event.getRawY();
                                isClick = true;
                                // 按下时轻微缩小，给触觉反馈
                                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;
                                if (Math.abs(dx) > 20 || Math.abs(dy) > 20) {
                                    isClick = false;
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                                float deltaX = event.getRawX() - startX;
                                float deltaY = event.getRawY() - startY;

                                // 恢复缩放
                                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();

                                // 上滑销毁（deltaY < 0）
                                if (deltaY < -SWIPE_THRESHOLD && Math.abs(deltaY) > Math.abs(deltaX)) {
                                    animateRemoveCurrent(0, -250);
                                    return true;
                                }

                                // 右滑销毁（deltaX > 0）
                                if (deltaX > SWIPE_THRESHOLD && Math.abs(deltaX) > Math.abs(deltaY)) {
                                    animateRemoveCurrent(350, 0);
                                    return true;
                                }

                                // 左滑销毁（deltaX < 0）
                                if (deltaX < -SWIPE_THRESHOLD && Math.abs(deltaX) > Math.abs(deltaY)) {
                                    animateRemoveCurrent(-350, 0);
                                    return true;
                                }

                                // 下滑：尽量让事件穿透给 SystemUI 下拉状态栏
                                // 由于 FLAG_NOT_TOUCH_MODAL 已设置，返回 false 事件会穿透
                                if (deltaY > SWIPE_THRESHOLD && Math.abs(deltaY) > Math.abs(deltaX)) {
                                    // 先移除悬浮窗，避免阻挡后续滑动
                                    removeCurrentOverlay();
                                    return false; // 事件继续传递
                                }

                                // 其他情况视为点击
                                if (isClick) {
                                    v.performClick();
                                }
                                return true;
                        }
                        return false;
                    }
                });

                // ==================== 窗口参数 ====================
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WIN_W,
                    WIN_H,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = WIN_X;
                params.y = WIN_Y;

                mWindowManager.addView(container, params);

                mCurrentKey = key;
                mCurrentOverlay = container;

                XposedBridge.log(TAG + ": Shown: " + title);

                // 入场动画：从上方滑入 + 淡入
                container.setAlpha(0f);
                container.setTranslationY(-40);
                container.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

                // 5 秒后自动消失
                mCurrentDismissRunnable = () -> animateRemoveCurrent(0, -80);
                mHandler.postDelayed(mCurrentDismissRunnable, AUTO_DISMISS_MS);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": showCustomHeadsUp error: " + t);
            }
        });
    }

    /* ================================================================ */
    /*  带动画的移除                                                    */
    /* ================================================================ */
    private void animateRemoveCurrent(float endX, float endY) {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) return;
            if (mIsAnimating) return;
            mIsAnimating = true;

            // 取消自动消失定时器
            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }

            mCurrentOverlay.animate()
                .alpha(0f)
                .translationX(endX)
                .translationY(endY)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    removeOverlayViewOnly();
                    mIsAnimating = false;
                })
                .start();
        });
    }

    /* ================================================================ */
    /*  外部调用：立即移除（无动画，用于替换旧通知或下滑穿透）          */
    /* ================================================================ */
    private void removeCurrentOverlay() {
        if (mHandler == null) return;
        mHandler.post(() -> removeCurrentOverlayInternal(false));
    }

    /* ================================================================ */
    /*  内部移除                                                        */
    /*  @param animate 是否等待当前动画结束（替换旧通知时用 false）     */
    /* ================================================================ */
    private void removeCurrentOverlayInternal(boolean waitAnimation) {
        try {
            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }

            if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                // 如果正在播放移除动画，等动画结束会自动清理，这里不重复 remove
                if (mIsAnimating && waitAnimation) {
                    return;
                }
                mWindowManager.removeView(mCurrentOverlay);
                XposedBridge.log(TAG + ": Removed: " + mCurrentKey);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": removeOverlay error: " + t);
        } finally {
            mCurrentOverlay = null;
            mCurrentKey = null;
            mIsAnimating = false;
        }
    }

    private void removeOverlayViewOnly() {
        try {
            if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                mWindowManager.removeView(mCurrentOverlay);
            }
        } catch (Throwable ignored) {}
        mCurrentOverlay = null;
        mCurrentKey = null;
    }
}
