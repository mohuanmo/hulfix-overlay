package com.example.hulfix;

import android.app.Notification;
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
    private static final long ANIM_DURATION_MS = 200;

    /* ===== UI 方案开关 ===== */
    // true  = 半透明磨砂（90%白 + 描边，AOSP 13 效果接近原生）
    // false = 纯白圆角（最稳妥）
    private static final boolean USE_FROSTED_GLASS = true;

    /* ===== 窗口位置（基于用户设备像素坐标） ===== */
    // 左上(1390,77)  右上(2059,77)
    // 左下(1386,196) 右下(2057,196)
    // 取最小包围矩形
    private static final int WIN_X = 1386;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 673;   // 2059 - 1386
    private static final int WIN_H = 119;   // 196 - 77

    /* ===== 手势阈值 ===== */
    private static final float SWIPE_DESTROY_THRESHOLD = 100f;   // 上滑/左滑销毁
    private static final float PULLDOWN_THRESHOLD = 150f;        // 下滑下拉状态栏（更大，防误触）
    private static final float MOVE_THRESHOLD = 12f;             // 判定为滑动的最小位移

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private Runnable mCurrentDismissRunnable = null;
    private boolean mIsAnimating = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v4 loaded ======");
        XposedBridge.log(TAG + ": UI mode: " + (USE_FROSTED_GLASS ? "Frosted" : "White"));

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
    }

    /* ================================================================ */
    /*  Hook 1：系统要显示 Heads-Up 时触发                             */
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
    /*  Hook 2：动画结束兜底触发                                       */
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
            Boolean isHeadsUp = (Boolean) XposedHelpers.callMethod(rowView, "isHeadsUp");
            if (isHeadsUp != null && isHeadsUp) return true;
        } catch (Throwable ignored) {}

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

                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(24);
                if (USE_FROSTED_GLASS) {
                    bg.setColor(0xE6FFFFFF);
                    bg.setStroke(1, 0x33FFFFFF);
                } else {
                    bg.setColor(0xFFFFFFFF);
                }
                container.setBackground(bg);
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
                    animateRemoveCurrent(0, -80);
                });

                // ==================== 跟手触摸手势 ====================
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
                                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start();
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;

                                if (Math.abs(dx) > MOVE_THRESHOLD || Math.abs(dy) > MOVE_THRESHOLD) {
                                    isClick = false;
                                }

                                if (!isClick) {
                                    // 跟手移动
                                    v.setTranslationX(dx);
                                    v.setTranslationY(dy);

                                    // 距离越远越透明（最低 0.5）
                                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                    float alpha = Math.max(0.5f, 1f - dist / 350f);
                                    v.setAlpha(alpha);
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                                float totalDx = event.getRawX() - startX;
                                float totalDy = event.getRawY() - startY;
                                boolean isHorizontal = Math.abs(totalDx) > Math.abs(totalDy);

                                v.animate().scaleX(1f).scaleY(1f).setDuration(60).start();

                                // 上滑销毁
                                if (totalDy < -SWIPE_DESTROY_THRESHOLD && !isHorizontal) {
                                    animateRemoveCurrent(totalDx, -350);
                                    return true;
                                }

                                // 左滑销毁
                                if (totalDx < -SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    animateRemoveCurrent(-450, totalDy);
                                    return true;
                                }

                                // 下滑：下拉状态栏 + 销毁（阈值更大，防误触）
                                if (totalDy > PULLDOWN_THRESHOLD && !isHorizontal) {
                                    expandNotificationsPanel();
                                    animateRemoveCurrent(totalDx, 150);
                                    return true;
                                }

                                // 点击
                                if (isClick) {
                                    v.performClick();
                                    return true;
                                }

                                // 滑动但未超阈值：回弹
                                v.animate()
                                    .translationX(0)
                                    .translationY(0)
                                    .alpha(1f)
                                    .setDuration(220)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f))
                                    .start();
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

                // 入场动画
                container.setAlpha(0f);
                container.setTranslationY(-30);
                container.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

                mCurrentDismissRunnable = () -> animateRemoveCurrent(0, -80);
                mHandler.postDelayed(mCurrentDismissRunnable, AUTO_DISMISS_MS);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": showCustomHeadsUp error: " + t);
            }
        });
    }

    /* ================================================================ */
    /*  下拉状态栏（通过 StatusBarManager hide API）                    */
    /* ================================================================ */
    private void expandNotificationsPanel() {
        try {
            Object sbm = mContext.getSystemService("statusbar");
            if (sbm != null) {
                XposedHelpers.callMethod(sbm, "expandNotificationsPanel");
                XposedBridge.log(TAG + ": expandNotificationsPanel OK");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": expandNotificationsPanel failed: " + t);
        }
    }

    /* ================================================================ */
    /*  带动画的移除（从当前跟手位置继续飞出）                        */
    /* ================================================================ */
    private void animateRemoveCurrent(float extraDeltaX, float extraDeltaY) {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) return;
            if (mIsAnimating) return;
            mIsAnimating = true;

            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }

            float curTx = mCurrentOverlay.getTranslationX();
            float curTy = mCurrentOverlay.getTranslationY();

            mCurrentOverlay.animate()
                .alpha(0f)
                .translationX(curTx + extraDeltaX)
                .translationY(curTy + extraDeltaY)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    removeOverlayViewOnly();
                    mIsAnimating = false;
                })
                .start();
        });
    }

    private void removeCurrentOverlay() {
        if (mHandler == null) return;
        mHandler.post(() -> removeCurrentOverlayInternal(false));
    }

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
