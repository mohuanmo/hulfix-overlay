package com.example.hulfix;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
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

    /* ===== 窗口位置和尺寸（基于用户设备像素坐标） ===== */
    // 包围盒：left=min(1390,1386)=1386, right=max(2059,2057)=2059
    //         top=77, bottom=196
    private static final int WIN_X = 1386;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 673;   // 2059 - 1386
    private static final int WIN_H = 119;   // 196 - 77

    /* ===== 手势阈值 ===== */
    private static final float SWIPE_THRESHOLD = 90f;
    private static final float DAMPING = 0.82f;

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    // 单实例：只保留最新一条通知
    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private Runnable mCurrentDismissRunnable = null;
    private boolean mIsAnimating = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v4 loaded ======");

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
                            if (!isLandscape(rowView)) return;

                            StatusBarNotification sbn = getSbnFromRow(rowView);
                            if (sbn == null) return;

                            if (!shouldShowHeadsUp(sbn, rowView)) return;

                            String key = sbn.getKey();
                            param.setResult(null);
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
    /*  通知过滤：只让真正该弹 Heads-Up 的新消息通过                    */
    /* ================================================================ */
    private boolean shouldShowHeadsUp(StatusBarNotification sbn, View rowView) {
        // 1. 过滤常驻通知（音乐播放、VPN、录屏等）
        if (sbn.isOngoing()) {
            XposedBridge.log(TAG + ": Filter ongoing: " + sbn.getKey());
            return false;
        }
        // 2. 过滤不可清除通知
        if (!sbn.isClearable()) {
            XposedBridge.log(TAG + ": Filter not clearable: " + sbn.getKey());
            return false;
        }
        // 3. 只接受最近 3 秒内的新通知（避免旧通知反复触发）
        long age = System.currentTimeMillis() - sbn.getPostTime();
        if (age > 3000) {
            XposedBridge.log(TAG + ": Filter old (" + age + "ms): " + sbn.getKey());
            return false;
        }

        // 4. SystemUI 内部已标记为 Heads-Up
        try {
            Boolean isHeadsUp = (Boolean) XposedHelpers.callMethod(rowView, "isHeadsUp");
            if (isHeadsUp != null && isHeadsUp) return true;
        } catch (Throwable ignored) {}

        // 5. 传统 priority / category / flag 判断
        Notification n = sbn.getNotification();
        if (n.priority >= Notification.PRIORITY_HIGH) return true;
        String cat = n.category;
        if (Notification.CATEGORY_CALL.equals(cat) ||
            Notification.CATEGORY_ALARM.equals(cat) ||
            Notification.CATEGORY_MESSAGE.equals(cat) ||
            Notification.CATEGORY_EVENT.equals(cat) ||
            Notification.CATEGORY_REMINDER.equals(cat)) {
            return true;
        }
        if ((n.flags & 0x00000080) != 0) return true;
        if (n.fullScreenIntent != null) return true;

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
    /*  莫奈取色：获取系统 accent1_100 作为背景                         */
    /* ================================================================ */
    private int getMonetBackgroundColor() {
        try {
            android.content.res.Resources res = mContext.getResources();
            int resId = res.getIdentifier("system_accent1_100", "color", "android");
            if (resId != 0) {
                int color = res.getColor(resId, null);
                // 90% 不透明度 + Monet 淡色
                return (0xE6 << 24) | (color & 0x00FFFFFF);
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Monet color failed: " + e);
        }
        return 0xE6FFFFFF; // 回退：90% 白
    }

    /* ================================================================ */
    /*  下拉状态栏（下滑手势时调用）                                    */
    /* ================================================================ */
    private void expandStatusBar() {
        try {
            Object sbm = mContext.getSystemService(Context.STATUS_BAR_SERVICE);
            if (sbm != null) {
                sbm.getClass().getMethod("expandNotificationsPanel").invoke(sbm);
                XposedBridge.log(TAG + ": Status bar expanded");
                return;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": expand via StatusBarManager failed: " + e);
        }
        // 备用方案：IStatusBarService AIDL
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "statusbar");
            Class<?> stubClass = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Object service = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);
            service.getClass().getMethod("expandNotificationsPanel").invoke(service);
            XposedBridge.log(TAG + ": Status bar expanded (AIDL)");
        } catch (Exception e2) {
            XposedBridge.log(TAG + ": expand AIDL failed: " + e2);
        }
    }

    /* ================================================================ */
    /*  创建窗口参数                                                    */
    /* ================================================================ */
    private WindowManager.LayoutParams createParams(int windowType) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WIN_W, WIN_H, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = WIN_X;
        p.y = WIN_Y;
        return p;
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
                // 新通知替换旧通知
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
                container.setPadding(18, 10, 18, 10);
                container.setGravity(Gravity.CENTER_VERTICAL);

                // 莫奈背景 + 圆角
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(24);
                bg.setColor(getMonetBackgroundColor());
                container.setBackground(bg);
                container.setElevation(14);

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
                titleView.setTextColor(0xFF000000); // 黑色
                titleView.setTextSize(13);
                titleView.setMaxLines(1);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(titleView);

                TextView contentView = new TextView(mContext);
                contentView.setText(content);
                contentView.setTextColor(0xFF333333); // 深灰
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
                        XposedBridge.log(TAG + ": PendingIntent failed: " + e);
                    }
                    animateRemoveCurrent(0, -60);
                });

                // ==================== 触摸手势（跟手动画） ====================
                container.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;
                    long startTime;
                    boolean isClick;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (mIsAnimating) return true;

                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startX = event.getRawX();
                                startY = event.getRawY();
                                startTime = event.getEventTime();
                                isClick = true;
                                v.animate().cancel();
                                v.setScaleX(0.96f);
                                v.setScaleY(0.96f);
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float moveDx = event.getRawX() - startX;
                                float moveDy = event.getRawY() - startY;
                                if (Math.abs(moveDx) > 10 || Math.abs(moveDy) > 10) {
                                    isClick = false;
                                }
                                // 跟手拖拽（带阻尼）
                                v.setTranslationX(moveDx * DAMPING);
                                v.setTranslationY(moveDy * DAMPING);
                                return true;

                            case MotionEvent.ACTION_UP:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;

                                // 恢复缩放
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();

                                boolean horizontal = Math.abs(dx) > Math.abs(dy);

                                if (!horizontal && dy < -SWIPE_THRESHOLD) {
                                    // 上滑 → 飞出销毁
                                    animateRemoveCurrent(dx * DAMPING, -350);
                                    return true;
                                }
                                if (!horizontal && dy > SWIPE_THRESHOLD) {
                                    // 下滑 → 下拉状态栏 + 向下滑出销毁
                                    expandStatusBar();
                                    animateRemoveCurrent(dx * DAMPING, 200);
                                    return true;
                                }
                                if (horizontal && dx < -SWIPE_THRESHOLD) {
                                    // 左滑 → 飞出销毁
                                    animateRemoveCurrent(-450, dy * DAMPING);
                                    return true;
                                }
                                if (horizontal && dx > SWIPE_THRESHOLD) {
                                    // 右滑 → 飞出销毁
                                    animateRemoveCurrent(450, dy * DAMPING);
                                    return true;
                                }

                                if (isClick) {
                                    v.performClick();
                                } else {
                                    // 未触发手势 → 弹性回弹
                                    v.animate()
                                        .translationX(0)
                                        .translationY(0)
                                        .setDuration(280)
                                        .setInterpolator(new OvershootInterpolator(0.7f))
                                        .start();
                                }
                                return true;
                        }
                        return false;
                    }
                });

                // ==================== 窗口添加（尝试最高层级） ====================
                WindowManager.LayoutParams params = createParams(
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                );
                boolean added = false;
                try {
                    mWindowManager.addView(container, params);
                    added = true;
                    XposedBridge.log(TAG + ": Using TYPE_SYSTEM_OVERLAY");
                } catch (Exception e) {
                    XposedBridge.log(TAG + ": TYPE_SYSTEM_OVERLAY failed: " + e);
                }
                if (!added) {
                    try {
                        params = createParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                        mWindowManager.addView(container, params);
                        added = true;
                        XposedBridge.log(TAG + ": Fallback to TYPE_APPLICATION_OVERLAY");
                    } catch (Exception e2) {
                        XposedBridge.log(TAG + ": Both window types failed: " + e2);
                        return;
                    }
                }

                mCurrentKey = key;
                mCurrentOverlay = container;

                XposedBridge.log(TAG + ": Shown: " + title);

                // 入场动画：从上方淡入滑入
                container.setAlpha(0f);
                container.setTranslationY(-35);
                container.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                    .start();

                // 5 秒后自动消失
                mCurrentDismissRunnable = () -> animateRemoveCurrent(0, -60);
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

            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }

            // 计算当前 translation，让动画从当前位置继续
            float curX = mCurrentOverlay.getTranslationX();
            float curY = mCurrentOverlay.getTranslationY();

            mCurrentOverlay.animate()
                .alpha(0f)
                .translationX(curX + endX)
                .translationY(curY + endY)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    removeOverlayViewOnly();
                    mIsAnimating = false;
                })
                .start();
        });
    }

    /* ================================================================ */
    /*  外部调用：立即移除（无动画）                                    */
    /* ================================================================ */
    private void removeCurrentOverlay() {
        if (mHandler == null) return;
        mHandler.post(() -> removeCurrentOverlayInternal(false));
    }

    /* ================================================================ */
    /*  内部移除                                                        */
    /* ================================================================ */
    private void removeCurrentOverlayInternal(boolean waitAnimation) {
        try {
            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }
            if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                if (mIsAnimating && waitAnimation) return;
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
