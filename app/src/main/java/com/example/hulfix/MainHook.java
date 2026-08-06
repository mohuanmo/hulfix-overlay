package com.example.hulfix;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.animation.AnimatorSet;
import android.view.animation.DecelerateInterpolator;
import android.animation.ObjectAnimator;
import android.view.animation.OvershootInterpolator;
import android.animation.ValueAnimator;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
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
    private static final float MIN_FLING_VELOCITY = 200f;
    private static final long SHIELD_DELAY_MS = 400;
    private static final float SWIPE_INTENT_THRESHOLD = 40f;

    /* ===== 液态玻璃参数 ===== */
    private static final float IDLE_BLUR_RADIUS = 1.2f;   // 【v26】静止时的常驻轻微雾化
    private static final float ENTER_BLUR_START = 8f;    // 入场起始雾化量
    private static final float EXIT_BLUR_END = 16f;      // 出场结束雾化量
    private static final float TOUCH_DOWN_BLUR = 2f;     // 按压时的额外雾化
    private static final float BOUNCE_BLUR = 2f;         // 回弹时的额外雾化
    private static final float BLUR_DELTA_THRESHOLD = 0.3f; // RenderEffect 更新阈值

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

    /* ===== 用户主动 dismiss 记录（防止下拉后重新刷新） ===== */
    private String mUserDismissedKey = null;
    private long mUserDismissTime = 0;
    private static final long USER_DISMISS_COOLDOWN_MS = 5000;

    /* ===== 系统实例 ===== */
    private Object mHeadsUpManager = null;
    private Object mStatusBar = null;

    /* ===== 屏幕广播 ===== */
    private BroadcastReceiver mScreenReceiver = null;
    private boolean mBroadcastRegistered = false;

    /* ===== ValueAnimator 实例 ===== */
    private ValueAnimator mEnterAnim = null;
    private ValueAnimator mExitAnim = null;
    private ValueAnimator mBounceAnim = null;

    /* ===== 液态玻璃 blur 状态（分层管理） ===== */
    private float mAnimBlurRadius = 0f;      // 动画驱动
    private float mTouchBlurRadius = 0f;     // 触摸驱动
    private float mLastAppliedBlur = -1f;    // 用于阈值去重

    /* ===== 手势追踪（防误判） ===== */
    private float mTouchMaxDx = 0f;
    private float mTouchMaxDy = 0f;
    private android.view.VelocityTracker mVelocityTracker = null;

    /* ===== 状态栏展开标志 ===== */
    private boolean mIsPanelExpanded = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v26 loaded ======");

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
            //    尝试 hook 多个可能的方法，因为不同 ROM 调用路径不同

            // 4a. expandNotificationsPanel — 代码/API 触发展开
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "expandNotificationsPanel",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            mIsPanelExpanded = true;
                            if (mCurrentOverlay != null) {
                                XposedBridge.log(TAG + ": expandNotificationsPanel, remove overlay immediately");
                                removeOverlayImmediate();
                            }
                        }
                    }
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook expandNotificationsPanel skipped: " + t);
            }

            // 4b. setExpandedVisible — 某些 ROM 的展开状态入口
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "setExpandedVisible",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean visible = (boolean) param.args[0];
                            mIsPanelExpanded = visible;
                            if (visible && mCurrentOverlay != null) {
                                XposedBridge.log(TAG + ": setExpandedVisible(true), remove overlay immediately");
                                removeOverlayImmediate();
                            }
                        }
                    }
                );
                XposedBridge.log(TAG + ": hooked setExpandedVisible(boolean)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook setExpandedVisible skipped: " + t);
            }

            // 4c. makeExpandedVisible — AOSP 13 手势下拉状态栏的核心入口
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "makeExpandedVisible",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            mIsPanelExpanded = true;
                            if (mCurrentOverlay != null) {
                                XposedBridge.log(TAG + ": makeExpandedVisible, remove overlay immediately");
                                removeOverlayImmediate();
                            }
                        }
                    }
                );
                XposedBridge.log(TAG + ": hooked makeExpandedVisible");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook makeExpandedVisible skipped: " + t);
            }

            // 5. PanelViewController — 用户下拉状态栏时立即清理 overlay，停止时重置标志
            // 这是最早能检测到手势下拉的 hook 点，比 StatusBar 的方法更可靠
            try {
                Class<?> panelControllerClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.PanelViewController",
                    lpparam.classLoader
                );
                XposedBridge.hookAllMethods(panelControllerClass, "onTrackingStarted",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            mIsPanelExpanded = true;
                            if (mCurrentOverlay != null) {
                                XposedBridge.log(TAG + ": PanelViewController.onTrackingStarted, remove overlay immediately");
                                removeOverlayImmediate();
                            }
                        }
                    }
                );
                XposedBridge.hookAllMethods(panelControllerClass, "onTrackingStopped",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mIsPanelExpanded = false;
                            XposedBridge.log(TAG + ": PanelViewController.onTrackingStopped, panel collapsed");
                        }
                    }
                );
                XposedBridge.log(TAG + ": hooked PanelViewController.onTrackingStarted/onTrackingStopped");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook PanelViewController skipped: " + t);
            }

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
    /*  从 StatusBar 彻底移除通知视图                                  */
    /* ================================================================ */
    private void removeSystemNotificationView(String key) {
        if (mStatusBar != null && key != null) {
            // 尝试多种 AOSP 13 可能的签名
            boolean removed = false;
            // 1. 双参数 (String, NotificationVisibility)
            try {
                Class<?> nvClass = XposedHelpers.findClass(
                    "android.service.notification.NotificationVisibility",
                    mStatusBar.getClass().getClassLoader()
                );
                Object nv = XposedHelpers.callStaticMethod(nvClass, "obtain",
                    key, 0, 0, false);
                XposedHelpers.callMethod(mStatusBar, "removeNotification", key, nv);
                XposedBridge.log(TAG + ": StatusBar notification removed(via NV): " + key);
                removed = true;
            } catch (Throwable t1) {
                // 2. 单参数 fallback
                try {
                    XposedHelpers.callMethod(mStatusBar, "removeNotification", key);
                    XposedBridge.log(TAG + ": StatusBar notification removed(single): " + key);
                    removed = true;
                } catch (Throwable t2) {
                    // 3. 通过 NotificationPresenter
                    try {
                        Object presenter = XposedHelpers.getObjectField(mStatusBar, "mPresenter");
                        if (presenter != null) {
                            XposedHelpers.callMethod(presenter, "removeNotification", key);
                            XposedBridge.log(TAG + ": StatusBar notification removed(via presenter): " + key);
                            removed = true;
                        }
                    } catch (Throwable t3) {
                        // 4. 通过 NotificationEntryManager
                        try {
                            Object entryManager = XposedHelpers.getObjectField(mStatusBar, "mEntryManager");
                            if (entryManager != null) {
                                XposedHelpers.callMethod(entryManager, "removeNotification", key);
                                XposedBridge.log(TAG + ": StatusBar notification removed(via entryManager): " + key);
                                removed = true;
                            }
                        } catch (Throwable t4) {}
                    }
                }
            }
            if (!removed) {
                XposedBridge.log(TAG + ": StatusBar removeNotification all attempts failed for: " + key);
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
    /*  【v22 新增】判断当前是否锁屏                                   */
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
        if (mIsPanelExpanded) return true;
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
    /*  【v22 新增】注册屏幕息屏广播接收器                            */
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
                            // 【v24】状态栏已展开时不创建 overlay（双重保险）
                            if (isStatusBarExpanded()) {
                                XposedBridge.log(TAG + ": StatusBar expanded, skip HeadsUpIsVisible");
                                param.setResult(null);
                                return;
                            }

                            View rowView = (View) param.thisObject;
                            if (!isLandscape(rowView)) return;

                            StatusBarNotification sbn = getSbnFromRow(rowView);
                            if (sbn == null) return;

                            if (BLOCK_PKG.equals(sbn.getPackageName())) return;

                            String key = sbn.getKey();

                            long now = SystemClock.elapsedRealtime();
                            // 【v25】用户主动 dismiss 后冷却期内不重新创建
                            if (key.equals(mUserDismissedKey) && (now - mUserDismissTime) < USER_DISMISS_COOLDOWN_MS) {
                                XposedBridge.log(TAG + ": User dismissed recently, skip: " + key);
                                param.setResult(null);
                                return;
                            }

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
                            // 【v24】状态栏已展开时不创建 overlay（双重保险）
                            if (isStatusBarExpanded()) {
                                XposedBridge.log(TAG + ": StatusBar expanded, skip AnimatingAway");
                                param.setResult(null);
                                return;
                            }

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
                            // 【v25】用户主动 dismiss 后冷却期内不重新创建
                            if (key.equals(mUserDismissedKey) && (now - mUserDismissTime) < USER_DISMISS_COOLDOWN_MS) {
                                XposedBridge.log(TAG + ": User dismissed recently(AnimatingAway), skip: " + key);
                                param.setResult(null);
                                return;
                            }

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
    /*  【v26 新增】统一 blur 应用入口（含阈值去重）                    */
    /* ================================================================ */
    private void applyBlur(View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (view == null) return;
        float total = IDLE_BLUR_RADIUS + mAnimBlurRadius + mTouchBlurRadius;
        if (Math.abs(total - mLastAppliedBlur) < BLUR_DELTA_THRESHOLD) return;
        mLastAppliedBlur = total;
        if (total > 0.5f) {
            view.setRenderEffect(RenderEffect.createBlurEffect(
                total, total, Shader.TileMode.CLAMP));
        } else {
            view.setRenderEffect(null);
        }
    }

    /* ================================================================ */
    /*  取消所有手动动画                                                */
    /* ================================================================ */
    private void cancelAllAnimations() {
        if (mEnterAnim != null) {
            mEnterAnim.cancel();
            mEnterAnim = null;
        }
        if (mExitAnim != null) {
            mExitAnim.cancel();
            mExitAnim = null;
        }
        if (mBounceAnim != null) {
            mBounceAnim.cancel();
            mBounceAnim = null;
        }
        // 【v26】清理动画 blur 状态，防止残留
        mAnimBlurRadius = 0f;
        if (mCurrentOverlay != null) {
            applyBlur(mCurrentOverlay);
            mCurrentOverlay.setAlpha(1f);
            mCurrentOverlay.setTranslationX(0f);
            mCurrentOverlay.setTranslationY(0f);
            mCurrentOverlay.setScaleX(1f);
            mCurrentOverlay.setScaleY(1f);
        }
    }

    /* ================================================================ */
    /*  入场动画                                                        */
    /* ================================================================ */
    private void startEnterAnimation(final View view) {
        mHandler.post(() -> {
            cancelAllAnimations();
            view.setAlpha(0f);
            view.setTranslationY(-40f);
            view.setTranslationX(0f);
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            mAnimBlurRadius = ENTER_BLUR_START; // 从雾化开始
            applyBlur(view);

            mEnterAnim = ValueAnimator.ofFloat(0f, 1f);
            mEnterAnim.setDuration(160);
            mEnterAnim.setInterpolator(new DecelerateInterpolator(1.0f));
            mEnterAnim.addUpdateListener(anim -> {
                float ease = (float) anim.getAnimatedValue();
                view.setAlpha(ease);
                view.setTranslationY(-40f * (1f - ease));
                view.setScaleX(0.96f + 0.04f * ease);
                view.setScaleY(0.96f + 0.04f * ease);
                // 【v26】动态去模糊，终点保留 IDLE_BLUR_RADIUS
                mAnimBlurRadius = (1f - ease) * ENTER_BLUR_START;
                applyBlur(view);
            });
            // 【v26】增加结束监听，防止快速替换通知时状态竞争
            mEnterAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    mEnterAnim = null;
                    mAnimBlurRadius = 0f;
                    applyBlur(view);
                }
            });
            mEnterAnim.start();
        });
    }

    /* ================================================================ */
    /*  离场动画                                                        */
    /* ================================================================ */
    private void startExitAnimation(final View view, final Runnable onEnd, final boolean slideUpward) {
        cancelAllAnimations();

        mExitAnim = ValueAnimator.ofFloat(0f, 1f);
        mExitAnim.setDuration(128);
        mExitAnim.setInterpolator(new DecelerateInterpolator(1.0f));
        mExitAnim.addUpdateListener(anim -> {
            float ease = (float) anim.getAnimatedValue();
            // ease-in: f^2
            float realEase = ease * ease;
            view.setAlpha(1f - realEase);
            if (slideUpward) {
                view.setTranslationY(-80f * realEase);
            } else {
                view.setTranslationX(-80f * realEase);
            }
            // 【v26】退出时逐渐雾化
            mAnimBlurRadius = realEase * EXIT_BLUR_END;
            applyBlur(view);
        });
        mExitAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mExitAnim = null;
                mAnimBlurRadius = 0f;
                applyBlur(view);
                if (onEnd != null) onEnd.run();
            }
        });
        mExitAnim.start();
    }

    /* ================================================================ */
    /*  回弹动画（【v26】重写：阻尼振荡替代 sin+Overshoot 叠加）        */
    /* ================================================================ */
    private void startBounceAnimation(final View view, final float direction) {
        cancelAllAnimations();

        mAnimBlurRadius = BOUNCE_BLUR;
        applyBlur(view);

        mBounceAnim = ValueAnimator.ofFloat(0f, 1f);
        mBounceAnim.setDuration(300);
        mBounceAnim.setInterpolator(null); // 线性，自己控制物理曲线
        mBounceAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            // 【v26】阻尼振荡：e^(-5t) * sin(3πt)
            // t=0.17 时达到第一次正向峰值，t=0.33 过零，t=0.50 负向小回弹
            float decay = (float) Math.exp(-5 * t);
            float oscillation = (float) Math.sin(t * Math.PI * 3);
            float offset = 18f * decay * oscillation * direction;
            view.setTranslationX(offset);
            view.setAlpha(1f);
        });
        mBounceAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mBounceAnim = null;
                view.setTranslationX(0f);
                mAnimBlurRadius = 0f;
                applyBlur(view);
            }
        });
        mBounceAnim.start();
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

                // ===== 液态玻璃效果 =====
                // 1. 主背景：极低透明度 + 主题色 tint
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(28);
                bg.setColor(0xB3FFFFFF);  // 70% 透明度白色（更通透）
                bg.setStroke(1, 0x60FFFFFF);  // 更细更淡的边缘

                // 2. 顶部高光层：模拟玻璃表面反射
                int[] highlightColors = new int[] {
                    0x50FFFFFF,   // 顶部微亮
                    0x00FFFFFF,   // 中间透明
                    0x20FFFFFF    // 底部微亮
                };
                GradientDrawable highlight = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, highlightColors);
                highlight.setShape(GradientDrawable.RECTANGLE);
                highlight.setCornerRadius(28);

                // 3. 组合背景（主背景 + 高光叠加）
                android.graphics.drawable.LayerDrawable glassBg =
                    new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] { bg, highlight });
                container.setBackground(glassBg);
                container.setElevation(12);  // 略降阴影，更轻盈

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
                                mTouchMaxDx = 0f;
                                mTouchMaxDy = 0f;
                                if (mVelocityTracker != null) {
                                    mVelocityTracker.recycle();
                                }
                                mVelocityTracker = android.view.VelocityTracker.obtain();
                                mVelocityTracker.addMovement(event);

                                // 【v26】按压时像水一样凹陷 + 微模糊
                                v.animate().scaleX(0.97f).scaleY(0.97f)
                                    .setDuration(80).setInterpolator(new DecelerateInterpolator()).start();
                                mTouchBlurRadius = TOUCH_DOWN_BLUR;
                                applyBlur(v);
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

                                // 【v26 修复】记录最大位移和速度，用于区分滑动意图 vs 点击
                                mTouchMaxDx = Math.max(mTouchMaxDx, Math.abs(dx));
                                mTouchMaxDy = Math.max(mTouchMaxDy, Math.abs(dy));
                                if (mVelocityTracker != null) {
                                    mVelocityTracker.addMovement(event);
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
                                // 【v26】释放时恢复形状 + 去模糊
                                v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(150).setInterpolator(new OvershootInterpolator(0.5f)).start();
                                mTouchBlurRadius = 0f;
                                applyBlur(v);

                                float totalDx = event.getRawX() - startX;
                                float totalDy = event.getRawY() - startY;

                                // 计算离开速度
                                float velocityX = 0f, velocityY = 0f;
                                if (mVelocityTracker != null) {
                                    mVelocityTracker.addMovement(event);
                                    mVelocityTracker.computeCurrentVelocity(1000);
                                    velocityX = mVelocityTracker.getXVelocity();
                                    velocityY = mVelocityTracker.getYVelocity();
                                    mVelocityTracker.recycle();
                                    mVelocityTracker = null;
                                }

                                boolean isHorizontal;
                                if (lockedHorizontal) {
                                    isHorizontal = true;
                                } else if (lockedVertical) {
                                    isHorizontal = false;
                                } else {
                                    isHorizontal = Math.abs(totalDx) > Math.abs(totalDy);
                                }

                                // 滑动手势判断（使用终点位移）
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
                                    removeOverlayImmediate();
                                    return true;
                                }

                                // 【v26 修复】用最大位移 + 离开速度判断用户是否有滑动意图
                                // 避免"滑出去又滑回来"被误判为点击
                                boolean hasSwipeIntent = (mTouchMaxDx > SWIPE_INTENT_THRESHOLD)
                                    || (mTouchMaxDy > SWIPE_INTENT_THRESHOLD);
                                boolean isFastFling = (Math.abs(velocityX) > MIN_FLING_VELOCITY)
                                    || (Math.abs(velocityY) > MIN_FLING_VELOCITY);

                                if (hasSwipeIntent || isFastFling) {
                                    // 用户确实想滑动，只是回弹了 → 回弹动画，不跳转
                                    XposedBridge.log(TAG + ": Swipe intent detected (maxDx=" + mTouchMaxDx
                                        + ", maxDy=" + mTouchMaxDy + ", vx=" + velocityX + ", vy=" + velocityY
                                        + "), bounce back");
                                    startBounceAnimation(v, totalDx < 0 ? -1f : 1f);
                                    return true;
                                }

                                // 真正的点击（位移小 + 速度慢）
                                if (Math.abs(totalDx) < SWIPE_DESTROY_THRESHOLD && Math.abs(totalDy) < SWIPE_DESTROY_THRESHOLD) {
                                    performContentClick(contentIntent);
                                    dismissOverlayAnimated();
                                    return true;
                                }

                                startBounceAnimation(v, totalDx < 0 ? -1f : 1f);
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

                mAutoDismissRunnable = () -> dismissOverlayAnimated(true);
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
        dismissOverlayAnimated(false);
    }

    private void dismissOverlayAnimated(final boolean slideUpward) {
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
            }, slideUpward);
        });
    }

    /* ===== 立即移除（【v22】清理逻辑统一，删除死代码） ===== */
    /* ================================================================ */
    /*  仅隐藏 overlay（用户主动下拉/展开状态栏时使用，不清理系统通知）  */
    /* ================================================================ */
    private void hideOverlayOnly() {
        cancelAllAnimations();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }

        // 重置视觉状态，防止动画中途被掐断导致属性残留
        if (mCurrentOverlay != null) {
            mCurrentOverlay.setAlpha(1f);
            mCurrentOverlay.setTranslationX(0f);
            mCurrentOverlay.setTranslationY(0f);
            mCurrentOverlay.setScaleX(1f);
            mCurrentOverlay.setScaleY(1f);
        }

        // 重置 rowView 状态，让系统知道 overlay 已隐藏
        if (mCurrentRowView != null) {
            try {
                XposedHelpers.callMethod(mCurrentRowView, "setHeadsUp", false);
            } catch (Throwable t1) {
                try {
                    XposedHelpers.callMethod(mCurrentRowView, "setHeadsUpAnimatingAway", true);
                } catch (Throwable ignored) {}
            }
        }

        // 移除窗口
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

        mCurrentKey = null;
        mCurrentRowView = null;
        mCurrentOverlay = null;
        mCurrentContentHash = null;
        mLastDismissTime = SystemClock.elapsedRealtime();
    }

    private void removeOverlayImmediate() {
        cancelAllAnimations();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }

        String keyToRemove = mCurrentKey;
        View rowViewSnapshot = mCurrentRowView;
        final View overlayToShield = mCurrentOverlay; // 【v26】透明盾牌引用

        // 1. 清理系统 Heads-Up entry
        removeSystemHeadsUpEntry(keyToRemove);

        // 2. 清理系统通知视图（尝试多种签名）
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

        // 4. 【v26 修复】透明盾牌：延迟移除窗口，防止触摸落到系统残留通知
        //    设置 alpha=0f 前先清理 RenderEffect，防止 blur 残留像素
        if (overlayToShield != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    overlayToShield.setRenderEffect(null);
                }
                overlayToShield.setAlpha(0f);
                overlayToShield.setOnTouchListener((v, event) -> true); // 消费所有触摸
            } catch (Throwable ignored) {}
            mHandler.postDelayed(() -> {
                try {
                    if (overlayToShield.getParent() != null) {
                        mWindowManager.removeView(overlayToShield);
                    }
                } catch (Throwable t) {
                    try {
                        mWindowManager.removeViewImmediate(overlayToShield);
                    } catch (Throwable ignored) {}
                }
            }, SHIELD_DELAY_MS);
        }

        // 5. 更新 dismiss time
        if (keyToRemove != null) {
            mLastDismissTime = SystemClock.elapsedRealtime();
        }

        // 6. 记录用户主动 dismiss，防止系统重新创建 Heads-Up
        mUserDismissedKey = keyToRemove;
        mUserDismissTime = SystemClock.elapsedRealtime();

        // 7. 立即清空引用（新通知可以正常创建，透明盾牌用局部变量保持）
        mCurrentKey = null;
        mCurrentRowView = null;
        mCurrentOverlay = null;
        mCurrentContentHash = null;
        mLastAppliedBlur = -1f; // 【v26】重置 blur 缓存
    }
}
