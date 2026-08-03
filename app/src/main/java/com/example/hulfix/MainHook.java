package com.example.hulfix;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "HULFix";
    private static final long AUTO_DISMISS_MS = 5000;
    private static final long DEBOUNCE_MS = 3000;

    /* ===== 窗口位置 ===== */
    private static final int WIN_X = 1386;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 673;
    private static final int WIN_H = 119;

    /* ===== 手势阈值 ===== */
    private static final float SWIPE_DESTROY_THRESHOLD = 80f;
    private static final float PULLDOWN_THRESHOLD = 120f;

    /* ===== 屏蔽列表 ===== */
    private static final String BLOCK_PKG = "com.omarea.vtools";

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    /* ===== 单实例 ===== */
    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private Runnable mCurrentDismissRunnable = null;
    private long mLastShowTime = 0;
    private boolean mIsDismissing = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v13 loaded ======");

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
        hookStatusBarExpand(lpparam);
    }

    /* ================================================================ */
    /*  Hook 1：系统要显示 Heads-Up 时，阻止系统显示，我们自己显示     */
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

                            // 屏蔽指定应用
                            if (BLOCK_PKG.equals(sbn.getPackageName())) {
                                XposedBridge.log(TAG + ": Blocked " + BLOCK_PKG);
                                return;
                            }

                            String key = sbn.getKey();
                            XposedBridge.log(TAG + ": HeadsUpIsVisible before, key=" + key);

                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            showCustomHeadsUp(sbn);

                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setHeadsUpIsVisible before error: " + t);
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
    /*  Hook 2：兜底触发                                               */
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

                            if (BLOCK_PKG.equals(sbn.getPackageName())) return;

                            String key = sbn.getKey();
                            if (key.equals(mCurrentKey)) return;

                            XposedBridge.log(TAG + ": AnimatingAway before, key=" + key);

                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            showCustomHeadsUp(sbn);

                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setHeadsUpAnimatingAway before error: " + t);
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
    /*  Hook 3：下拉通知栏时自动关闭悬浮窗                             */
    /* ================================================================ */
    private void hookStatusBarExpand(XC_LoadPackage.LoadPackageParam lpparam) {
        // 尝试 Hook StatusBar.makeExpandedVisible
        try {
            Class<?> statusBarClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.StatusBar",
                lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(statusBarClass, "makeExpandedVisible",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        removeCurrentOverlay();
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked StatusBar.makeExpandedVisible");
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": makeExpandedVisible not found, trying expandNotificationsPanel");
        }

        // 备用：Hook expandNotificationsPanel
        try {
            Class<?> statusBarClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.StatusBar",
                lpparam.classLoader
            );
            XposedHelpers.findAndHookMethod(statusBarClass, "expandNotificationsPanel",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        removeCurrentOverlay();
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked StatusBar.expandNotificationsPanel");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookStatusBarExpand failed: " + t);
        }
    }

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

        // 去重：同一通知 3 秒内不重复显示
        long now = SystemClock.elapsedRealtime();
        if (key.equals(mCurrentKey) && (now - mLastShowTime) < DEBOUNCE_MS) {
            XposedBridge.log(TAG + ": Debounced: " + key);
            return;
        }

        mHandler.post(() -> {
            try {
                // 【锁屏检测】锁屏时不显示悬浮窗
                KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null && km.isKeyguardLocked()) {
                    XposedBridge.log(TAG + ": Keyguard locked, skip: " + key);
                    return;
                }

                // 单实例：新通知替换旧的（带动画移除）
                dismissCurrentOverlayAnimated();

                Notification notification = sbn.getNotification();
                Bundle extras = notification.extras;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = bigText.length() > 0 ? bigText.toString() : text.toString();

                // ===== 根容器 =====
                LinearLayout container = new LinearLayout(mContext);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setPadding(20, 14, 20, 14);
                container.setGravity(Gravity.CENTER_VERTICAL);

                // ===== 毛玻璃效果（提高对比度） =====
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(28);
                bg.setColor(0xD9FFFFFF); // 85% 白，比之前的 70% 更不透明
                bg.setStroke(2, 0x80FFFFFF); // 50% 白描边，更明显
                container.setBackground(bg);
                container.setElevation(18);

                // ===== 图标 =====
                ImageView iconView = new ImageView(mContext);
                android.graphics.drawable.Icon icon = notification.getSmallIcon();
                if (icon != null) {
                    iconView.setImageIcon(icon);
                }
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(44, 44);
                iconLp.gravity = Gravity.CENTER_VERTICAL;
                iconView.setLayoutParams(iconLp);
                container.addView(iconView);

                // ===== 文字区域 =====
                LinearLayout textContainer = new LinearLayout(mContext);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                );
                textLp.setMargins(14, 0, 0, 0);
                textLp.gravity = Gravity.CENTER_VERTICAL;
                textContainer.setLayoutParams(textLp);

                TextView titleView = new TextView(mContext);
                titleView.setText(title);
                titleView.setTextColor(0xFF000000);
                titleView.setTextSize(14);
                titleView.setMaxLines(1);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(titleView);

                TextView contentView = new TextView(mContext);
                contentView.setText(content);
                contentView.setTextColor(0xFF333333);
                contentView.setTextSize(12);
                contentView.setMaxLines(1);
                contentView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(contentView);

                container.addView(textContainer);

                // ===== 已读按钮（淡蓝色文字） =====
                TextView readBtn = new TextView(mContext);
                readBtn.setText("已读");
                readBtn.setTextColor(0xFF64B5F6); // Material Blue 300，淡蓝
                readBtn.setTextSize(12);
                readBtn.setPadding(12, 4, 12, 4);
                LinearLayout.LayoutParams readLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                readLp.gravity = Gravity.CENTER_VERTICAL;
                readBtn.setLayoutParams(readLp);
                readBtn.setOnClickListener(v -> {
                    try {
                        // 发送 deleteIntent 模拟清除通知
                        PendingIntent deleteIntent = notification.deleteIntent;
                        if (deleteIntent != null) {
                            deleteIntent.send();
                            XposedBridge.log(TAG + ": DeleteIntent sent");
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": DeleteIntent send failed: " + e);
                    }
                    dismissCurrentOverlayAnimated();
                });
                container.addView(readBtn);

                // ===== 点击跳转（点击非按钮区域） =====
                PendingIntent contentIntent = notification.contentIntent;
                container.setOnClickListener(v -> {
                    try {
                        if (contentIntent != null) {
                            contentIntent.send();
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": PendingIntent send failed: " + e);
                    }
                    dismissCurrentOverlayAnimated();
                });

                // ===== 跟手触摸手势 =====
                container.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startX = event.getRawX();
                                startY = event.getRawY();
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;
                                v.setTranslationX(dx);
                                v.setTranslationY(dy);
                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                float alpha = Math.max(0.5f, 1f - dist / 300f);
                                v.setAlpha(alpha);
                                return true;

                            case MotionEvent.ACTION_UP:
                                float totalDx = event.getRawX() - startX;
                                float totalDy = event.getRawY() - startY;
                                boolean isHorizontal = Math.abs(totalDx) > Math.abs(totalDy);

                                if (totalDy < -SWIPE_DESTROY_THRESHOLD && !isHorizontal) {
                                    dismissCurrentOverlayAnimated();
                                    return true;
                                }
                                if (totalDx < -SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    dismissCurrentOverlayAnimated();
                                    return true;
                                }
                                if (totalDx > SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    dismissCurrentOverlayAnimated();
                                    return true;
                                }
                                if (totalDy > PULLDOWN_THRESHOLD && !isHorizontal) {
                                    expandStatusBar();
                                    dismissCurrentOverlayAnimated();
                                    return true;
                                }
                                v.animate()
                                    .translationX(0)
                                    .translationY(0)
                                    .alpha(1f)
                                    .setDuration(200)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                                    .start();
                                return true;
                        }
                        return false;
                    }
                });

                // ===== 窗口参数 =====
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WIN_W,
                    WIN_H,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = WIN_X;
                params.y = WIN_Y;

                mWindowManager.addView(container, params);
                mCurrentKey = key;
                mCurrentOverlay = container;
                mIsDismissing = false;
                mLastShowTime = SystemClock.elapsedRealtime();

                XposedBridge.log(TAG + ": Shown: " + title);

                // ===== 入场动画 =====
                container.setAlpha(0f);
                container.setTranslationY(-40);
                container.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

                mCurrentDismissRunnable = () -> dismissCurrentOverlayAnimated();
                mHandler.postDelayed(mCurrentDismissRunnable, AUTO_DISMISS_MS);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": showCustomHeadsUp error: " + t);
            }
        });
    }

    /* ===== 展开状态栏 ===== */
    private void expandStatusBar() {
        try {
            Object sbm = mContext.getSystemService("statusbar");
            if (sbm != null) {
                Method expand = sbm.getClass().getMethod("expandNotificationsPanel");
                expand.invoke(sbm);
                XposedBridge.log(TAG + ": expandNotificationsPanel OK");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": expandNotificationsPanel failed: " + t);
        }
    }

    /* ===== 带动画的移除（出场动画） ===== */
    private void dismissCurrentOverlayAnimated() {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) {
                cleanupOverlay();
                return;
            }
            if (mIsDismissing) return;
            mIsDismissing = true;

            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }

            // 出场动画：向上滑出 + 淡出
            mCurrentOverlay.animate()
                .alpha(0f)
                .translationY(-60)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    try {
                        if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                            mWindowManager.removeView(mCurrentOverlay);
                        }
                    } catch (Throwable ignored) {}
                    cleanupOverlay();
                })
                .start();
        });
    }

    /* ===== 立即移除（无动画，用于替换旧通知） ===== */
    private void removeCurrentOverlay() {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }
            if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                try {
                    mWindowManager.removeView(mCurrentOverlay);
                } catch (Throwable ignored) {}
            }
            cleanupOverlay();
        });
    }

    private void cleanupOverlay() {
        mCurrentOverlay = null;
        mCurrentKey = null;
        mIsDismissing = false;
    }
}
