package com.example.hulfix;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "HULFix";
    private static final long AUTO_DISMISS_MS = 6000;
    private static final long COOLDOWN_MS = 3000;
    private static final long NOTIFICATION_MAX_AGE_MS = 3000;

    /* ===== 窗口位置 ===== */
    private static final int WIN_X = 1386;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 673;
    private static final int WIN_H = 119;

    /* ===== 手势阈值 ===== */
    private static final float SWIPE_DESTROY_THRESHOLD = 70f;
    private static final float PULLDOWN_THRESHOLD = 120f;
    private static final float DIRECTION_LOCK_SLOP = 25f;

    /* ===== 屏蔽列表 ===== */
    private static final String BLOCK_PKG = "com.omarea.vtools";

    private static final int WINDOW_TYPE = 2017; // TYPE_STATUS_BAR_SUB_PANEL

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    /* ===== 单实例 ===== */
    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private View mCurrentRowView = null;
    private String mCurrentContentHash = null;
    private Runnable mAutoDismissRunnable = null;
    private long mLastDismissTime = 0;

    /* ===== 系统实例 ===== */
    private Object mHeadsUpManager = null;
    private Object mStatusBar = null;

    /* ===== 屏幕广播 ===== */
    private BroadcastReceiver mScreenReceiver = null;
    private boolean mBroadcastRegistered = false;

    /* ===== 手动动画 Runnable ===== */
    private Runnable mEnterAnimRunnable = null;
    private Runnable mExitAnimRunnable = null;
    private Runnable mBounceAnimRunnable = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v22 loaded ======");

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
        captureHeadsUpManager(lpparam);
        captureStatusBar(lpparam);
    }

    /* ================================================================ */
    /*  捕获 HeadsUpManager 实例                                        */
    /* ================================================================ */
    private void captureHeadsUpManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> headsUpClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.HeadsUpManager",
                lpparam.classLoader
            );
            XposedBridge.hookAllMethods(headsUpClass, "addNotification",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        mHeadsUpManager = param.thisObject;
                    }
                }
            );
            XposedBridge.log(TAG + ": HeadsUpManager capture hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": HeadsUpManager capture failed: " + t);
        }
    }

    /* ================================================================ */
    /*  捕获 StatusBar 实例 + 下拉检测                                  */
    /* ================================================================ */
    private void captureStatusBar(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> statusBarClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.StatusBar",
                lpparam.classLoader
            );

            // 1. 构造函数捕获（最早）
            XposedBridge.hookAllConstructors(statusBarClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        mStatusBar = param.thisObject;
                        XposedBridge.log(TAG + ": StatusBar captured via constructor");
                    }
                }
            );

            // 2. start() 捕获（备用）
            XposedHelpers.findAndHookMethod(statusBarClass, "start",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mStatusBar == null) {
                            mStatusBar = param.thisObject;
                            XposedBridge.log(TAG + ": StatusBar captured via start()");
                        }
                    }
                }
            );

            // 3. addNotification 捕获（最终 fallback）
            XposedBridge.hookAllMethods(statusBarClass, "addNotification",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (mStatusBar == null) {
                            mStatusBar = param.thisObject;
                            XposedBridge.log(TAG + ": StatusBar captured via addNotification");
                        }
                    }
                }
            );

            // 4. 下拉状态栏检测：展开时销毁 overlay
            XposedHelpers.findAndHookMethod(statusBarClass, "expandNotificationsPanel",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (mCurrentOverlay != null) {
                            XposedBridge.log(TAG + ": expandNotificationsPanel, destroy overlay");
                            removeOverlayImmediate();
                        }
                    }
                }
            );

            XposedBridge.log(TAG + ": StatusBar hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": StatusBar hooks failed: " + t);
        }
    }

    /* ================================================================ */
    /*  从 HeadsUpManager 中彻底移除通知 Entry                          */
    /* ================================================================ */
    private void removeSystemHeadsUpEntry(String key) {
        if (mHeadsUpManager != null && key != null) {
            try {
                XposedHelpers.callMethod(mHeadsUpManager, "removeNotification", key, true);
                XposedBridge.log(TAG + ": HeadsUp entry removed(true): " + key);
            } catch (Throwable t) {
                try {
                    XposedHelpers.callMethod(mHeadsUpManager, "removeNotification", key);
                    XposedBridge.log(TAG + ": HeadsUp entry removed(fallback): " + key);
                } catch (Throwable ignored) {}
            }
        }
    }

    /* ================================================================ */
    /*  从 StatusBar 彻底移除通知视图                                   */
    /* ================================================================ */
    private void removeSystemNotificationView(String key) {
        if (mStatusBar != null && key != null) {
            try {
                XposedHelpers.callMethod(mStatusBar, "removeNotification", key);
                XposedBridge.log(TAG + ": StatusBar notification removed: " + key);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": StatusBar removeNotification failed: " + t);
            }
        }
    }

    /* ================================================================ */
    /*  判断通知是否新鲜（3 秒内）                                      */
    /* ================================================================ */
    private boolean isFreshNotification(StatusBarNotification sbn) {
        long age = System.currentTimeMillis() - sbn.getPostTime();
        return age <= NOTIFICATION_MAX_AGE_MS;
    }

    /* ================================================================ */
    /*  【v22 新增】判断当前是否锁屏                                    */
    /* ================================================================ */
    private boolean isKeyguardLocked() {
        if (mContext == null) return false;
        try {
            KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ================================================================ */
    /*  【v22 新增】判断状态栏是否已展开                                */
    /* ================================================================ */
    private boolean isStatusBarExpanded() {
        if (mStatusBar == null) return false;
        try {
            return (boolean) XposedHelpers.getBooleanField(mStatusBar, "mExpandedVisible");
        } catch (Throwable t1) {
            try {
                return (boolean) XposedHelpers.getBooleanField(mStatusBar, "mIsExpanded");
            } catch (Throwable t2) {
                try {
                    return (boolean) XposedHelpers.getBooleanField(mStatusBar, "mPanelExpanded");
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    /* ================================================================ */
    /*  【v22 新增】注册屏幕息屏广播接收器                              */
    /* ================================================================ */
    private void registerScreenReceiver() {
        if (mBroadcastRegistered || mContext == null) return;
        try {
            mScreenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        if (mCurrentOverlay != null) {
                            XposedBridge.log(TAG + ": Screen OFF, destroy overlay immediately");
                            removeOverlayImmediate();
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenReceiver, filter);
            mBroadcastRegistered = true;
            XposedBridge.log(TAG + ": Screen receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Screen receiver register failed: " + t);
        }
    }

    /* ================================================================ */
    /*  Hook 1: setHeadsUpIsVisible                                     */
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

                            if (BLOCK_PKG.equals(sbn.getPackageName())) return;

                            String key = sbn.getKey();

                            long now = SystemClock.elapsedRealtime();
                            if (key.equals(mCurrentKey) && mCurrentOverlay != null) {
                                param.setResult(null);
                                return;
                            }
                            if (key.equals(mCurrentKey) && (now - mLastDismissTime) < COOLDOWN_MS) {
                                XposedBridge.log(TAG + ": Cooldown skip: " + key);
                                param.setResult(null);
                                return;
                            }

                            if (!isFreshNotification(sbn)) {
                                long age = System.currentTimeMillis() - sbn.getPostTime();
                                XposedBridge.log(TAG + ": Skip stale in IsVisible, age=" + age + "ms, key=" + key);
                                param.setResult(null);
                                return;
                            }

                            XposedBridge.log(TAG + ": HeadsUpIsVisible before, key=" + key);
                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            mCurrentRowView = rowView;
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
    /*  Hook 2: setHeadsUpAnimatingAway                                 */
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
                            View rowView = (View) param.thisObject;

                            if (mCurrentKey == null && mCurrentRowView == rowView) {
                                return;
                            }

                            if (animatingAway) return;

                            boolean isHeadsUp = false;
                            try {
                                isHeadsUp = (boolean) XposedHelpers.callMethod(rowView, "isHeadsUp");
                            } catch (Throwable ignored) {}
                            if (!isHeadsUp) return;

                            if (!isLandscape(rowView)) return;

                            StatusBarNotification sbn = getSbnFromRow(rowView);
                            if (sbn == null) return;

                            if (BLOCK_PKG.equals(sbn.getPackageName())) return;

                            String key = sbn.getKey();

                            long now = SystemClock.elapsedRealtime();
                            if (key.equals(mCurrentKey) && mCurrentOverlay != null) {
                                param.setResult(null);
                                return;
                            }
                            if (key.equals(mCurrentKey) && (now - mLastDismissTime) < COOLDOWN_MS) {
                                XposedBridge.log(TAG + ": Cooldown skip(AnimatingAway): " + key);
                                param.setResult(null);
                                return;
                            }

                            if (!isFreshNotification(sbn)) {
                                long age = System.currentTimeMillis() - sbn.getPostTime();
                                XposedBridge.log(TAG + ": Skip stale in AnimatingAway, age=" + age + "ms, key=" + key);
                                param.setResult(null);
                                return;
                            }

                            XposedBridge.log(TAG + ": AnimatingAway before, key=" + key);
                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            mCurrentRowView = rowView;
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
    /*  取消所有手动动画                                                */
    /* ================================================================ */
    private void cancelAllAnimations() {
        if (mEnterAnimRunnable != null) {
            mHandler.removeCallbacks(mEnterAnimRunnable);
            mEnterAnimRunnable = null;
        }
        if (mExitAnimRunnable != null) {
            mHandler.removeCallbacks(mExitAnimRunnable);
            mExitAnimRunnable = null;
        }
        if (mBounceAnimRunnable != null) {
            mHandler.removeCallbacks(mBounceAnimRunnable);
            mBounceAnimRunnable = null;
        }
    }

    /* ================================================================ */
    /*  入场动画                                                        */
    /* ================================================================ */
    private void startEnterAnimation(final View view) {
        cancelAllAnimations();
        view.setAlpha(0f);
        view.setTranslationY(-40f);
        view.setTranslationX(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);

        mEnterAnimRunnable = new Runnable() {
            int step = 0;
            final int totalSteps = 10;
            final long stepMs = 16;

            @Override
            public void run() {
                step++;
                float f = step / (float) totalSteps;
                float ease = 1f - (1f - f) * (1f - f);
                view.setAlpha(ease);
                view.setTranslationY(-40f * (1f - ease));
                view.setScaleX(0.96f + 0.04f * ease);
                view.setScaleY(0.96f + 0.04f * ease);
                if (step < totalSteps) {
                    mHandler.postDelayed(this, stepMs);
                } else {
                    mEnterAnimRunnable = null;
                }
            }
        };
        mHandler.post(mEnterAnimRunnable);
    }

    /* ================================================================ */
    /*  离场动画                                                        */
    /* ================================================================ */
    private void startExitAnimation(final View view, final Runnable onEnd) {
        cancelAllAnimations();
        mExitAnimRunnable = new Runnable() {
            int step = 0;
            final int totalSteps = 8;
            final long stepMs = 16;

            @Override
            public void run() {
                step++;
                float f = step / (float) totalSteps;
                float ease = f * f;
                view.setAlpha(1f - ease);
                view.setTranslationX(-80f * ease);
                if (step < totalSteps) {
                    mHandler.postDelayed(this, stepMs);
                } else {
                    mExitAnimRunnable = null;
                    if (onEnd != null) onEnd.run();
                }
            }
        };
        mHandler.post(mExitAnimRunnable);
    }

    /* ================================================================ */
    /*  回弹动画                                                        */
    /* ================================================================ */
    private void startBounceAnimation(final View view) {
        cancelAllAnimations();
        mBounceAnimRunnable = new Runnable() {
            int step = 0;
            final int totalSteps = 12;
            final long stepMs = 16;

            @Override
            public void run() {
                step++;
                float f = step / (float) totalSteps;
                float sin = (float) Math.sin(f * Math.PI);
                float offset = 18f * sin * (1f - f);
                view.setTranslationX(offset);
                view.setAlpha(1f);
                if (step < totalSteps) {
                    mHandler.postDelayed(this, stepMs);
                } else {
                    view.setTranslationX(0f);
                    mBounceAnimRunnable = null;
                }
            }
        };
        mHandler.post(mBounceAnimRunnable);
    }

    /* ================================================================ */
    /*  显示自定义 Heads-Up                                             */
    /* ================================================================ */
    private void showCustomHeadsUp(StatusBarNotification sbn) {
        if (mContext == null || mWindowManager == null) return;
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        // 【v22 新增】锁屏时不显示
        if (isKeyguardLocked()) {
            XposedBridge.log(TAG + ": Keyguard locked, skip showing");
            return;
        }

        // 【v22 新增】状态栏已展开时不显示
        if (isStatusBarExpanded()) {
            XposedBridge.log(TAG + ": StatusBar expanded, skip showing");
            return;
        }

        // 【v22 新增】注册屏幕息屏广播（延迟注册，只执行一次）
        registerScreenReceiver();

        final String key = sbn.getKey();
        final Notification notification = sbn.getNotification();
        final PendingIntent contentIntent = notification.contentIntent;

        mHandler.post(() -> {
            try {
                removeSystemHeadsUpEntry(key);

                Bundle extras = notification.extras;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = bigText.length() > 0 ? bigText.toString() : text.toString();
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode());

                if (key.equals(mCurrentKey) && mCurrentOverlay != null) {
                    if (newHash.equals(mCurrentContentHash)) {
                        XposedBridge.log(TAG + ": Same content, skip duplicate, key=" + key);
                        return;
                    } else {
                        XposedBridge.log(TAG + ": Content changed, force update, key=" + key);
                    }
                }

                removeOverlayImmediate();
                mCurrentContentHash = newHash;

                // ===== 根容器 =====
                LinearLayout container = new LinearLayout(mContext);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setPadding(20, 14, 20, 14);
                container.setGravity(Gravity.CENTER_VERTICAL);

                // ===== 毛玻璃效果 =====
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(28);
                bg.setColor(0xD9FFFFFF);
                bg.setStroke(2, 0x80FFFFFF);
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

                // ===== 已读按钮 =====
                TextView readBtn = new TextView(mContext);
                readBtn.setText("已读");
                readBtn.setTextColor(0xFF64B5F6);
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
                        PendingIntent deleteIntent = notification.deleteIntent;
                        if (deleteIntent != null) {
                            deleteIntent.send();
                            XposedBridge.log(TAG + ": DeleteIntent sent");
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": DeleteIntent failed: " + e);
                    }
                    dismissOverlayAnimated();
                });
                container.addView(readBtn);

                // ===== 方向锁定滑动 + 点击跳转（合并到 onTouch 中）=====
                container.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;
                    boolean lockedHorizontal = false;
                    boolean lockedVertical = false;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startX = event.getRawX();
                                startY = event.getRawY();
                                lockedHorizontal = false;
                                lockedVertical = false;
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;

                                if (!lockedHorizontal && !lockedVertical) {
                                    if (Math.abs(dx) > DIRECTION_LOCK_SLOP || Math.abs(dy) > DIRECTION_LOCK_SLOP) {
                                        if (Math.abs(dx) > Math.abs(dy)) {
                                            lockedHorizontal = true;
                                        } else {
                                            lockedVertical = true;
                                        }
                                    }
                                }

                                if (lockedHorizontal) {
                                    v.setTranslationX(dx);
                                    v.setTranslationY(0);
                                } else if (lockedVertical) {
                                    v.setTranslationX(0);
                                    v.setTranslationY(dy);
                                } else {
                                    v.setTranslationX(dx);
                                    v.setTranslationY(dy);
                                }

                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                float alpha = Math.max(0.5f, 1f - dist / 300f);
                                v.setAlpha(alpha);
                                return true;

                            case MotionEvent.ACTION_UP:
                                float totalDx = event.getRawX() - startX;
                                float totalDy = event.getRawY() - startY;

                                boolean isHorizontal;
                                if (lockedHorizontal) {
                                    isHorizontal = true;
                                } else if (lockedVertical) {
                                    isHorizontal = false;
                                } else {
                                    isHorizontal = Math.abs(totalDx) > Math.abs(totalDy);
                                }

                                // 滑动手势判断
                                if (totalDy < -SWIPE_DESTROY_THRESHOLD && !isHorizontal) {
                                    dismissOverlayAnimated();
                                    return true;
                                }
                                if (totalDx < -SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    dismissOverlayAnimated();
                                    return true;
                                }
                                if (totalDx > SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    dismissOverlayAnimated();
                                    return true;
                                }
                                if (totalDy > PULLDOWN_THRESHOLD && !isHorizontal) {
                                    expandStatusBar();
                                    dismissOverlayAnimated();
                                    return true;
                                }

                                // 不是滑动手势，视为点击，执行跳转
                                if (Math.abs(totalDx) < SWIPE_DESTROY_THRESHOLD && Math.abs(totalDy) < SWIPE_DESTROY_THRESHOLD) {
                                    performContentClick(contentIntent);
                                    dismissOverlayAnimated();
                                    return true;
                                }

                                startBounceAnimation(v);
                                return true;
                        }
                        return false;
                    }
                });

                // ===== 窗口参数 =====
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WIN_W,
                    WIN_H,
                    WINDOW_TYPE,
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

                XposedBridge.log(TAG + ": Shown: " + title);

                startEnterAnimation(container);

                mAutoDismissRunnable = () -> dismissOverlayAnimated();
                mHandler.postDelayed(mAutoDismissRunnable, AUTO_DISMISS_MS);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": showCustomHeadsUp error: " + t);
            }
        });
    }

    /* ================================================================ */
    /*  【v22 修复】点击跳转：带 ActivityOptions                        */
    /* ================================================================ */
    private void performContentClick(PendingIntent contentIntent) {
        if (contentIntent == null) return;
        try {
            Bundle opts = createLaunchOptions();
            if (opts != null) {
                XposedHelpers.callMethod(contentIntent, "send",
                    mContext, 0, null, null, null, null, opts);
                XposedBridge.log(TAG + ": ContentIntent sent with ActivityOptions");
            } else {
                contentIntent.send(mContext, 0, null);
                XposedBridge.log(TAG + ": ContentIntent sent with context");
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": ContentIntent failed: " + e);
            try {
                contentIntent.send();
                XposedBridge.log(TAG + ": ContentIntent sent (direct fallback)");
            } catch (Throwable ignored) {}
        }
    }

    private Bundle createLaunchOptions() {
        try {
            Class<?> aoClass = Class.forName("android.app.ActivityOptions");
            Object ao = XposedHelpers.callStaticMethod(aoClass, "makeBasic");
            XposedHelpers.callMethod(ao, "setLaunchWindowingMode", 1); // WINDOWING_MODE_FULLSCREEN
            return (Bundle) XposedHelpers.callMethod(ao, "toBundle");
        } catch (Throwable t) {
            return null;
        }
    }

    /* ===== 展开状态栏 ===== */
    private void expandStatusBar() {
        try {
            Object sbm = mContext.getSystemService("statusbar");
            if (sbm != null) {
                java.lang.reflect.Method expand = sbm.getClass().getMethod("expandNotificationsPanel");
                expand.invoke(sbm);
                XposedBridge.log(TAG + ": expandNotificationsPanel OK");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": expandNotificationsPanel failed: " + t);
        }
    }

    /* ===== 带动画的移除 ===== */
    private void dismissOverlayAnimated() {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mAutoDismissRunnable != null) {
                mHandler.removeCallbacks(mAutoDismissRunnable);
                mAutoDismissRunnable = null;
            }
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) {
                removeOverlayImmediate();
                return;
            }
            startExitAnimation(mCurrentOverlay, () -> {
                removeOverlayImmediate();
            });
        });
    }

    /* ===== 立即移除（【v22】清理逻辑统一，删除死代码） ===== */
    private void removeOverlayImmediate() {
        cancelAllAnimations();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }

        String keyToRemove = mCurrentKey;
        View rowViewSnapshot = mCurrentRowView;

        // 1. 清理系统 Heads-Up entry
        removeSystemHeadsUpEntry(keyToRemove);

        // 2. 清理系统通知视图
        removeSystemNotificationView(keyToRemove);

        // 3. 清理 rowView 状态
        if (rowViewSnapshot != null) {
            try {
                XposedHelpers.callMethod(rowViewSnapshot, "setHeadsUp", false);
            } catch (Throwable t1) {
                try {
                    XposedHelpers.callMethod(rowViewSnapshot, "setHeadsUpAnimatingAway", true);
                } catch (Throwable ignored) {}
            }
        }

        // 4. 移除窗口
        if (mCurrentOverlay != null) {
            try {
                if (mCurrentOverlay.getParent() != null) {
                    mWindowManager.removeView(mCurrentOverlay);
                }
            } catch (Throwable t) {
                try {
                    mWindowManager.removeViewImmediate(mCurrentOverlay);
                } catch (Throwable ignored) {}
            }
        }

        // 5. 更新 dismiss time
        if (keyToRemove != null) {
            mLastDismissTime = SystemClock.elapsedRealtime();
        }

        // 6. 清理所有引用（统一在这里，避免重复）
        mCurrentKey = null;
        mCurrentRowView = null;
        mCurrentOverlay = null;
        mCurrentContentHash = null;
    }
}
