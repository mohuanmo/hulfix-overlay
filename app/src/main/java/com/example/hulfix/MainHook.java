package com.example.hulfix;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "HULFix";
    private static final long AUTO_DISMISS_MS = 6000;
    private static final long COOLDOWN_MS = 3000;
    private static final long NOTIFICATION_MAX_AGE_MS = 8000;

    private static final int WIN_X = 1386;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 673;
    private static final int WIN_H = 119;

    private static final float SWIPE_DESTROY_THRESHOLD = 140f;   // 增大阈值，避免轻微滑动误销毁
    private static final float PULLDOWN_THRESHOLD = 120f;
    private static final float DIRECTION_LOCK_SLOP = 25f;
    private static final float ANGLE_LOCK_DEGREES = 45f; // 角度容错：±45°内为水平，之外为垂直
    private static final float MIN_FLING_VELOCITY = 200f;
    private static final float SWIPE_INTENT_THRESHOLD = 40f;
    private static final float CLICK_THRESHOLD = 8f;
    private static final float SWIPE_VISUAL_FACTOR = 2.5f; // alpha 衰减减缓系数，越大越迟钝

    private static final String BLOCK_PKG = "com.omarea.vtools";
    private static final int WINDOW_TYPE = 2017;

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private View mCurrentRowView = null;
    private String mCurrentContentHash = null;
    private Runnable mAutoDismissRunnable = null;

    private String mUserDismissedKey = null;
    private long mUserDismissTime = 0;
    private static final long USER_IGNORE_COOLDOWN_MS = 500;

    private long mGlobalCooldownTime = 0;
    private static final long GLOBAL_COOLDOWN_MS = 1000;

    // 应用级别冷却：每个应用独立计时，防止同一应用通知轰炸，但不影响其他应用
    private static final Map<String, Long> mAppCooldownMap = new ConcurrentHashMap<>();
    private static final long APP_COOLDOWN_MS = 200; // 降低冷却，允许微信等应用快速更新通知

    private Object mHeadsUpManager = null;
    private Object mStatusBar = null;

    private BroadcastReceiver mScreenReceiver = null;
    private boolean mBroadcastRegistered = false;

    private android.animation.Animator mEnterAnim = null;
    private android.animation.Animator mExitAnim = null;
    private android.animation.Animator mBounceAnim = null;

    // === 状态机：统一管理动画状态，防止并发冲突 ===
    private enum OverlayState { IDLE, ENTERING, SHOWING, EXITING, DRAGGING }
    private OverlayState mOverlayState = OverlayState.IDLE;

    private float mTouchMaxDx = 0f;
    private float mTouchMaxDy = 0f;
    private android.view.VelocityTracker mVelocityTracker = null;

    private boolean mIsPanelExpanded = false;

    private View mContentView = null;
    private ImageView mIconView = null;
    private TextView mTitleView = null;
    private TextView mTextView = null;

    private final Object mOverlayLock = new Object();

    private void hookAllMethodsCompat(Class<?> clazz, String methodName, XC_MethodHook callback) {
        Class<?> currentClass = clazz;
        int hookedCount = 0;
        while (currentClass != null) {
            for (java.lang.reflect.Method method : currentClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    try {
                        XposedBridge.hookMethod(method, callback);
                        hookedCount++;
                    } catch (Throwable t) {
                    }
                }
            }
            currentClass = currentClass.getSuperclass();
        }
    }

    private void hookAllConstructorsCompat(Class<?> clazz, XC_MethodHook callback) {
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(constructor, callback);
            } catch (Throwable t) {
            }
        }
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": ====== HULFix Overlay v27 loaded ======");
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        hookPanelExpansion(lpparam);
        captureHeadsUpManager(lpparam);
        captureStatusBar(lpparam);
        hookNotificationEntry(lpparam);

        // 获取 SystemUI 的 Context 和 WindowManager
        try {
            Class<?> appClass = XposedHelpers.findClass("android.app.Application", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(appClass, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mContext == null) {
                        mContext = (Context) param.args[0];
                        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook Application.attach: " + t);
        }
    }

    private void captureHeadsUpManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> headsUpClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.HeadsUpManager", lpparam.classLoader);
            hookAllMethodsCompat(headsUpClass, "addNotification",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mHeadsUpManager = param.thisObject;
                    }
                });
        } catch (Throwable t) {
        }
    }

    private void hookNotificationEntry(XC_LoadPackage.LoadPackageParam lpparam) {
        // 尝试多个可能的通知入口类（LineageOS 20 GSI 类结构可能不同）
        String[] classNames = {
            "com.android.systemui.statusbar.notification.NotificationEntryManager",
            "com.android.systemui.statusbar.notification.NotifPipeline",
            "com.android.systemui.statusbar.notification.collection.NotifCollection",
            "com.android.systemui.statusbar.notification.NotificationListener",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl"
        };
        boolean hooked = false;
        for (String className : classNames) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                hookAllMethodsCompat(clazz, "addNotification", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        StatusBarNotification sbn = extractSbnFromArgs(param.args);
                        if (sbn != null) processNotification(sbn);
                    }
                });
                hooked = true;
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + className + " not found or hook failed: " + t);
            }
        }
        if (!hooked) {
        }

        // === 新增：Hook NotificationListener.onNotificationPosted（直接接收 StatusBarNotification）===
        try {
            Class<?> listenerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.NotificationListener", lpparam.classLoader);
            hookAllMethodsCompat(listenerClass, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) {
                        processNotification(sbn);
                    }
                }
            });
        } catch (Throwable t) {
        }
        // === 同时 Hook 父类 NotificationListenerService 的 onNotificationPosted ===
        try {
            Class<?> nlsClass = XposedHelpers.findClass(
                "android.service.notification.NotificationListenerService", lpparam.classLoader);
            hookAllMethodsCompat(nlsClass, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) {
                        processNotification(sbn);
                    }
                }
            });
        } catch (Throwable t) {
        }

        // === 新增：Hook NotifCollection.onNotificationPosted ===
        try {
            Class<?> notifCollClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotifCollection", lpparam.classLoader);
            hookAllMethodsCompat(notifCollClass, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) {
                        processNotification(sbn);
                    }
                }
            });
        } catch (Throwable t) {
        }

        // === 兜底：Hook NotificationEntry 构造方法（通知进入 SystemUI 的必经之路）===
        try {
            Class<?> entryClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry", lpparam.classLoader);
            hookAllConstructorsCompat(entryClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object entry = param.thisObject;
                        Object sbn = XposedHelpers.getObjectField(entry, "mSbn");
                        if (sbn == null) {
                            sbn = XposedHelpers.callMethod(entry, "getSbn");
                        }
                        if (sbn instanceof StatusBarNotification) {
                            StatusBarNotification statusBarNotification = (StatusBarNotification) sbn;
                            processNotification(statusBarNotification);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": NotificationEntry constructor hook error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
        }

        // === 兜底2：Hook NotificationEntry.updateNotification（通知内容更新时触发）===
        try {
            Class<?> entryClass2 = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry", lpparam.classLoader);
            hookAllMethodsCompat(entryClass2, "updateNotification", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object entry = param.thisObject;
                        Object sbn = XposedHelpers.getObjectField(entry, "mSbn");
                        if (sbn == null) {
                            sbn = XposedHelpers.callMethod(entry, "getSbn");
                        }
                        if (sbn instanceof StatusBarNotification) {
                            StatusBarNotification statusBarNotification = (StatusBarNotification) sbn;
                            processNotification(statusBarNotification);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": NotificationEntry update hook error: " + t);
                    }
                }
            });
        } catch (Throwable t) {
        }
    }

    private StatusBarNotification extractSbnFromArgs(Object[] args) {
        // 1. 直接查找 StatusBarNotification 参数
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            String type = arg != null ? arg.getClass().getName() : "null";
            if (arg instanceof StatusBarNotification) {
                return (StatusBarNotification) arg;
            }
        }
        // 2. 从 NotificationEntry 反射获取 mSbn 或 getSbn()
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            try {
                Object result = XposedHelpers.callMethod(arg, "getSbn");
                if (result instanceof StatusBarNotification) {
                    return (StatusBarNotification) result;
                }
            } catch (Throwable t) {
            }
            try {
                Object result = XposedHelpers.getObjectField(arg, "mSbn");
                if (result instanceof StatusBarNotification) {
                    return (StatusBarNotification) result;
                }
            } catch (Throwable t) {
            }
            try {
                Object result = XposedHelpers.getObjectField(arg, "sbn");
                if (result instanceof StatusBarNotification) {
                    return (StatusBarNotification) result;
                }
            } catch (Throwable t) {
            }
        }
        return null;
    }

    private void processNotification(StatusBarNotification sbn) {
        try {
            if (sbn == null) {
                return;
            }
            final String key = sbn.getKey();
            final Notification notification = sbn.getNotification();

            if (BLOCK_PKG.equals(sbn.getPackageName())) {
                return;
            }
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                return;
            }
            if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                return;
            }
            boolean fresh = isFreshNotification(sbn);
            if (!fresh) return;

            // 全局冷却检查
            if (isGlobalCooldown()) {
                return;
            }

            // 勿扰模式检查
            if (isDoNotDisturb()) {
                return;
            }

            // 用户手动划掉后的冷却：只对完全相同的通知 key 生效
            boolean userIgnored = mUserDismissedKey != null && key.equals(mUserDismissedKey)
                && SystemClock.elapsedRealtime() - mUserDismissTime < USER_IGNORE_COOLDOWN_MS;
            if (userIgnored) return;

            // 应用级别冷却：同一应用 200ms 内只显示一次，不影响其他应用
            // 但对于更新通知（相同key不同内容），跳过冷却检查
            String pkg = sbn.getPackageName();
            Long lastAppTime = mAppCooldownMap.get(pkg);
            boolean appCooldown = lastAppTime != null && SystemClock.elapsedRealtime() - lastAppTime < APP_COOLDOWN_MS;
            // 检查是否是更新通知（相同key但内容不同）
            boolean isUpdate = mCurrentKey != null && mCurrentKey.equals(key);
            if (appCooldown && !isUpdate) return;

            boolean panelExpanded = isStatusBarExpanded();
            if (panelExpanded) return;

            boolean keyguard = isKeyguardLocked();
            if (keyguard) return;

            showCustomHeadsUp(sbn);
        } catch (Throwable t) {
        }
    }

    private void captureStatusBar(XC_LoadPackage.LoadPackageParam lpparam) {
        // LineageOS 20 GSI 使用 CentralSurfacesImpl 替代 StatusBar
        try {
            Class<?> csClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl", lpparam.classLoader);
            hookAllConstructorsCompat(csClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    mStatusBar = param.thisObject;
                }
            });
            XposedHelpers.findAndHookMethod(csClass, "start", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBar == null) {
                        mStatusBar = param.thisObject;
                    }
                }
            });
            hookAllMethodsCompat(csClass, "addNotification", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBar == null) {
                        mStatusBar = param.thisObject;
                    }
                }
            });
            try {
                XposedHelpers.findAndHookMethod(csClass, "expandNotificationsPanel", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        triggerGlobalCooldown();
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod(csClass, "setExpandedVisible", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        boolean visible = (boolean) param.args[0];
                        mIsPanelExpanded = visible;
                        if (visible) triggerGlobalCooldown();
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod(csClass, "makeExpandedVisible", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        triggerGlobalCooldown();
                    }
                });
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
        }
    }

    private boolean isFreshNotification(StatusBarNotification sbn) {
        // 使用通知的 when 字段（更新时间）而非 postTime（首次发布时间）
        // 这样更新通知（如微信从1条变成2条）不会被误判为过期
        long when = sbn.getNotification().when;
        long postTime = sbn.getPostTime();
        // when 可能为 0（某些应用不设置），此时回退到 postTime
        long referenceTime = (when > 0) ? Math.max(when, postTime) : postTime;
        long age = System.currentTimeMillis() - referenceTime;
        boolean result = age <= NOTIFICATION_MAX_AGE_MS;
        return result;
    }

    private boolean isKeyguardLocked() {
        if (mContext == null) {
            return false;
        }
        try {
            KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            boolean result = km != null && km.isKeyguardLocked();
            return result;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isDoNotDisturb() {
        if (mContext == null) return false;
        try {
            NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            int filter = nm.getCurrentInterruptionFilter();
            // INTERRUPTION_FILTER_NONE = 3, INTERRUPTION_FILTER_ALARMS = 4
            return filter == NotificationManager.INTERRUPTION_FILTER_NONE
                || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isStatusBarExpanded() {
        if (mIsPanelExpanded) {
            return true;
        }
        if (mStatusBar == null) {
            return false;
        }
        String[] fieldNames = {"mExpandedVisible", "mIsExpanded", "mPanelExpanded", "mPanelExpandedFraction", "mQsExpanded"};
        for (String fieldName : fieldNames) {
            try {
                Object val = XposedHelpers.getObjectField(mStatusBar, fieldName);
                boolean r = false;
                if (val instanceof Boolean) r = (Boolean) val;
                else if (val instanceof Float) r = (Float) val > 0.05f;
                else if (val instanceof Integer) r = (Integer) val > 0;
                return r;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean isDarkMode() {
        if (mContext == null) return false;
        try {
            int uiMode = mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return uiMode == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) { return false; }
    }

    private boolean isGlobalCooldown() {
        long elapsed = SystemClock.elapsedRealtime() - mGlobalCooldownTime;
        boolean result = elapsed < GLOBAL_COOLDOWN_MS;
        return result;
    }

    private void triggerGlobalCooldown() {
        mGlobalCooldownTime = SystemClock.elapsedRealtime();
        if (mCurrentOverlay != null) removeOverlayImmediate();
    }

    private void registerScreenReceiver() {
        if (mBroadcastRegistered || mContext == null) return;
        try {
            mScreenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && mCurrentOverlay != null)
                        removeOverlayImmediate();
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenReceiver, filter);
            mBroadcastRegistered = true;
        } catch (Throwable t) {}
    }

    private void unregisterScreenReceiver() {
        if (!mBroadcastRegistered || mContext == null || mScreenReceiver == null) return;
        try {
            mContext.unregisterReceiver(mScreenReceiver);
        } catch (Throwable ignored) {}
        mScreenReceiver = null;
        mBroadcastRegistered = false;
    }

    private void hookPanelExpansion(XC_LoadPackage.LoadPackageParam lpparam) {
        // LineageOS 20 GSI: 只保留 CentralSurfacesImpl 路径，其他类不存在
        try {
            Class<?> csClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl", lpparam.classLoader);
            // 面板展开
            hookAllMethodsCompat(csClass, "expandNotificationsPanel", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    mIsPanelExpanded = true;
                    triggerGlobalCooldown();
                }
            });
            // 面板收起
            hookAllMethodsCompat(csClass, "collapsePanels", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    mIsPanelExpanded = false;
                }
            });
            hookAllMethodsCompat(csClass, "animateCollapsePanels", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    mIsPanelExpanded = false;
                }
            });
            // 也 hook setExpandedFraction 如果存在
            try {
                XposedHelpers.findAndHookMethod(csClass, "setExpandedFraction",
                    float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            float fraction = (float) param.args[0];
                            boolean wasExpanded = mIsPanelExpanded;
                            mIsPanelExpanded = fraction > 0.05f;
                            if (mIsPanelExpanded && !wasExpanded) {
                                triggerGlobalCooldown();
                            } else if (!mIsPanelExpanded && wasExpanded) {
                            }
                        }
                    });
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
        }
    }

    private void cancelAllAnimations() {
        android.animation.Animator enter = mEnterAnim;
        android.animation.Animator exit = mExitAnim;
        android.animation.Animator bounce = mBounceAnim;
        mEnterAnim = null;
        mExitAnim = null;
        mBounceAnim = null;
        if (enter != null) {
            enter.removeAllListeners();
            enter.cancel();
        }
        if (exit != null) {
            exit.removeAllListeners();
            exit.cancel();
        }
        if (bounce != null) {
            bounce.removeAllListeners();
            bounce.cancel();
        }
        // === 同时取消所有 ViewPropertyAnimator，防止属性竞争 ===
        if (mCurrentOverlay != null) {
            mCurrentOverlay.animate().cancel();
        }
        if (mContentView != null) {
            mContentView.animate().cancel();
        }
        if (mIconView != null) {
            mIconView.animate().cancel();
        }
        if (mTitleView != null) {
            mTitleView.animate().cancel();
        }
        if (mTextView != null) {
            mTextView.animate().cancel();
        }
        if (mCurrentOverlay != null) {
            mCurrentOverlay.setAlpha(1f);
            mCurrentOverlay.setTranslationX(0f);
            mCurrentOverlay.setTranslationY(0f);
            mCurrentOverlay.setScaleX(1f);
            mCurrentOverlay.setScaleY(1f);
        }
    }

    private void startEnterAnimation(final View view) {
        cancelAllAnimations();
        mOverlayState = OverlayState.ENTERING;
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 初始状态：从右侧远处斜上方进入
        view.setAlpha(0f);
        view.setScaleX(0.5f);
        view.setScaleY(0.5f);
        view.setTranslationX(WIN_W * 1.2f);
        view.setTranslationY(-WIN_H * 0.8f);

        // 内容层初始隐藏
        if (mIconView != null) {
            mIconView.setAlpha(0f);
            mIconView.setScaleX(0.3f);
            mIconView.setScaleY(0.3f);
        }
        if (mTitleView != null) {
            mTitleView.setAlpha(0f);
            mTitleView.setTranslationY(20f);
        }
        if (mTextView != null) {
            mTextView.setAlpha(0f);
            mTextView.setTranslationY(16f);
        }

        // === 容器主进入动画 ===
        AnimatorSet containerSet = new AnimatorSet();
        ObjectAnimator cAlpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f);
        ObjectAnimator cScaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.5f, 1.06f, 1f);
        ObjectAnimator cScaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.5f, 1.06f, 1f);
        ObjectAnimator cTransX = ObjectAnimator.ofFloat(view, "translationX", WIN_W * 1.2f, 0f);
        ObjectAnimator cTransY = ObjectAnimator.ofFloat(view, "translationY", -WIN_H * 0.8f, 0f);
        containerSet.playTogether(cAlpha, cScaleX, cScaleY, cTransX, cTransY);
        containerSet.setDuration(300);
        containerSet.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());

        // === 图标弹性浮现 ===
        AnimatorSet iconSet = new AnimatorSet();
        if (mIconView != null) {
            ObjectAnimator iAlpha = ObjectAnimator.ofFloat(mIconView, "alpha", 0f, 1f);
            ObjectAnimator iScaleX = ObjectAnimator.ofFloat(mIconView, "scaleX", 0.3f, 1.12f, 1f);
            ObjectAnimator iScaleY = ObjectAnimator.ofFloat(mIconView, "scaleY", 0.3f, 1.12f, 1f);
            iconSet.playTogether(iAlpha, iScaleX, iScaleY);
            iconSet.setDuration(260);
            iconSet.setStartDelay(40);
            iconSet.setInterpolator(new android.view.animation.OvershootInterpolator(1.0f));
        }

        // === 标题滑入 ===
        AnimatorSet titleSet = new AnimatorSet();
        if (mTitleView != null) {
            ObjectAnimator tAlpha = ObjectAnimator.ofFloat(mTitleView, "alpha", 0f, 1f);
            ObjectAnimator tTransY = ObjectAnimator.ofFloat(mTitleView, "translationY", 20f, 0f);
            titleSet.playTogether(tAlpha, tTransY);
            titleSet.setDuration(220);
            titleSet.setStartDelay(80);
            titleSet.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        }

        // === 内容滑入 ===
        AnimatorSet textSet = new AnimatorSet();
        if (mTextView != null) {
            ObjectAnimator txAlpha = ObjectAnimator.ofFloat(mTextView, "alpha", 0f, 1f);
            ObjectAnimator txTransY = ObjectAnimator.ofFloat(mTextView, "translationY", 16f, 0f);
            textSet.playTogether(txAlpha, txTransY);
            textSet.setDuration(200);
            textSet.setStartDelay(120);
            textSet.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        }

        // 组合所有动画
        AnimatorSet master = new AnimatorSet();
        master.playTogether(containerSet, iconSet, titleSet, textSet);
        master.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                mOverlayState = OverlayState.SHOWING;
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                mOverlayState = (mOverlayState == OverlayState.ENTERING) ? OverlayState.IDLE : mOverlayState;
            }
        });
        mEnterAnim = master;
        master.start();
    }

    private void startExitAnimation(final View view, final Runnable onEnd, final int exitDirection) {
        cancelAllAnimations();
        mOverlayState = OverlayState.EXITING;
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 原生平移消失：根据方向平移出屏幕
        float endTransX = 0f, endTransY = 0f;
        switch (exitDirection) {
            case 0: // 左滑
                endTransX = -WIN_W * 1.5f;
                break;
            case 1: // 上滑
                endTransY = -WIN_H * 1.5f;
                break;
            case 2: // 右滑
                endTransX = WIN_W * 1.5f;
                break;
            default: // 兜底：上滑
                endTransY = -WIN_H * 1.5f;
                break;
        }

        AnimatorSet exitSet = new AnimatorSet();
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f);
        ObjectAnimator transX = ObjectAnimator.ofFloat(view, "translationX", view.getTranslationX(), endTransX);
        ObjectAnimator transY = ObjectAnimator.ofFloat(view, "translationY", view.getTranslationY(), endTransY);

        exitSet.playTogether(alpha, transX, transY);
        exitSet.setDuration(250);
        exitSet.setInterpolator(new android.view.animation.AccelerateInterpolator());
        exitSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                mOverlayState = OverlayState.IDLE;
                if (onEnd != null) onEnd.run();
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                mOverlayState = OverlayState.IDLE;
            }
        });
        mExitAnim = exitSet;
        exitSet.start();
    }

    private void startBounceAnimation(final View view) {
        cancelAllAnimations();
        mOverlayState = OverlayState.ENTERING; // 回弹过程中视为过渡状态，禁止触摸
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        AnimatorSet bounceSet = new AnimatorSet();
        ObjectAnimator alpha = ObjectAnimator.ofFloat(view, "alpha", view.getAlpha(), 1f);
        ObjectAnimator transX = ObjectAnimator.ofFloat(view, "translationX", view.getTranslationX(), 0f);
        ObjectAnimator transY = ObjectAnimator.ofFloat(view, "translationY", view.getTranslationY(), 0f);

        bounceSet.playTogether(alpha, transX, transY);
        bounceSet.setDuration(250);
        bounceSet.setInterpolator(new android.view.animation.DecelerateInterpolator());
        bounceSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                mOverlayState = OverlayState.SHOWING;
            }
            @Override public void onAnimationCancel(android.animation.Animator animation) {
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                mOverlayState = (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) ? OverlayState.SHOWING : OverlayState.IDLE;
            }
        });
        mBounceAnim = bounceSet;
        bounceSet.start();
    }

    private void showCustomHeadsUp(StatusBarNotification sbn) {
        if (mContext == null || mWindowManager == null) {
            return;
        }
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        if (isKeyguardLocked() || isStatusBarExpanded()) {
            return;
        }
        registerScreenReceiver();

        final String key = sbn.getKey();
        final Notification notification = sbn.getNotification();
        final PendingIntent contentIntent = notification.contentIntent;

        mHandler.post(() -> {
            try {
                Bundle extras = notification.extras;
                String title = extras != null ? extras.getString(Notification.EXTRA_TITLE, "") : "";
                CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT, "") : "";
                CharSequence bigText = extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "") : "";
                String content = "";
                if (bigText != null && bigText.length() > 0) {
                    content = bigText.toString();
                } else if (text != null) {
                    content = text.toString();
                }
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode() & 0x7FFFFFFF);

                synchronized (mOverlayLock) {
                    if (key != null && key.equals(mCurrentKey)) {
                        if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                            if (newHash.equals(mCurrentContentHash)) {
                                return;
                            }
                        } else {
                            // overlay 已消失但状态未清空，强制重置
                            mCurrentKey = null;
                            mCurrentContentHash = null;
                            mCurrentOverlay = null;
                        }
                    }
                    removeOverlayImmediate();
                    mCurrentContentHash = newHash;
                }

                boolean isDark = isDarkMode();
                int textColorPrimary = isDark ? 0xFFFFFFFF : 0xFF000000;
                int textColorSecondary = isDark ? 0xFFCCCCCC : 0xFF333333;

                // === 根容器：FrameLayout ===
                FrameLayout root = new FrameLayout(mContext);
                root.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));

                // === Liquid Glass 背景层 ===
                // 使用渐变模拟玻璃厚度与折射感：顶部亮 → 中部透 → 底部略暗
                android.graphics.drawable.GradientDrawable glassBg = new android.graphics.drawable.GradientDrawable();
                glassBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                glassBg.setCornerRadius(28f);
                int[] glassColors = isDark
                    ? new int[]{0xD91C1C1E, 0xC01C1C1E, 0xA61C1C1E}   // 深色：深灰蓝，上亮下暗
                    : new int[]{0xB3FFFFFF, 0x99FFFFFF, 0x80FFFFFF};  // 浅色：白，上亮下透
                glassBg.setColors(glassColors);
                glassBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
                View glassLayer = new View(mContext);
                glassLayer.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                glassLayer.setBackground(glassBg);
                root.addView(glassLayer);

                // === 顶部高光（模拟玻璃表面反光）===
                View highlightTop = new View(mContext);
                FrameLayout.LayoutParams hTopLp = new FrameLayout.LayoutParams(WIN_W, 2);
                hTopLp.gravity = Gravity.TOP;
                hTopLp.topMargin = 0;
                highlightTop.setLayoutParams(hTopLp);
                android.graphics.drawable.GradientDrawable highlightDrawable = new android.graphics.drawable.GradientDrawable();
                highlightDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                highlightDrawable.setCornerRadii(new float[]{28f,28f,28f,28f,0,0,0,0});
                highlightDrawable.setColors(new int[]{0x40FFFFFF, 0x00FFFFFF});
                highlightDrawable.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
                highlightTop.setBackground(highlightDrawable);
                root.addView(highlightTop);

                // === 底部阴影（模拟玻璃厚度与体积）===
                View shadowBottom = new View(mContext);
                FrameLayout.LayoutParams sBotLp = new FrameLayout.LayoutParams(WIN_W, 3);
                sBotLp.gravity = Gravity.BOTTOM;
                shadowBottom.setLayoutParams(sBotLp);
                android.graphics.drawable.GradientDrawable shadowDrawable = new android.graphics.drawable.GradientDrawable();
                shadowDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                shadowDrawable.setCornerRadii(new float[]{0,0,0,0,28f,28f,28f,28f});
                shadowDrawable.setColors(new int[]{0x00000000, isDark ? 0x50000000 : 0x20000000});
                shadowDrawable.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
                shadowBottom.setBackground(shadowDrawable);
                root.addView(shadowBottom);

                // === 边缘内发光（微妙的高光环）===
                View edgeGlow = new View(mContext);
                edgeGlow.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                android.graphics.drawable.GradientDrawable edgeDrawable = new android.graphics.drawable.GradientDrawable();
                edgeDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                edgeDrawable.setCornerRadius(28f);
                edgeDrawable.setStroke(1, isDark ? 0x18FFFFFF : 0x20FFFFFF);
                edgeGlow.setBackground(edgeDrawable);
                root.addView(edgeGlow);

                // === 内容容器（可移动）===
                LinearLayout contentContainer = new LinearLayout(mContext);
                contentContainer.setOrientation(LinearLayout.HORIZONTAL);
                contentContainer.setPadding(28, 18, 28, 18);
                contentContainer.setGravity(Gravity.CENTER_VERTICAL);
                contentContainer.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));

                ImageView iconView = new ImageView(mContext);
                android.graphics.drawable.Icon icon = notification.getSmallIcon();
                if (icon != null) iconView.setImageIcon(icon);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(44, 44);
                iconLp.gravity = Gravity.CENTER_VERTICAL;
                iconView.setLayoutParams(iconLp);
                contentContainer.addView(iconView);

                LinearLayout textContainer = new LinearLayout(mContext);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                textLp.setMargins(14, 0, 0, 0);
                textLp.gravity = Gravity.CENTER_VERTICAL;
                textContainer.setLayoutParams(textLp);

                TextView titleView = new TextView(mContext);
                titleView.setText(title);
                titleView.setTextColor(textColorPrimary);
                titleView.setTextSize(14);
                titleView.setMaxLines(1);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(titleView);

                TextView contentView = new TextView(mContext);
                contentView.setText(content);
                contentView.setTextColor(textColorSecondary);
                contentView.setTextSize(12);
                contentView.setMaxLines(1);
                contentView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(contentView);
                contentContainer.addView(textContainer);

                root.addView(contentContainer);
                mContentView = contentContainer;
                mIconView = iconView;
                mTitleView = titleView;
                mTextView = contentView;

                // === 圆角裁剪（确保所有层统一圆角）===
                root.setClipToOutline(true);
                root.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 28f);
                    }
                });

                // === 触摸事件处理（角度判定方向 + 严格方向锁定）===
                contentContainer.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;
                    boolean lockedHorizontal = false;
                    boolean lockedVertical = false;
                    boolean hasMoved = false;

                    @Override public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                // 状态机保护：入场/退场动画期间禁止触摸，防止属性冲突
                                if (mOverlayState == OverlayState.ENTERING || mOverlayState == OverlayState.EXITING) {
                                    return false;
                                }
                                startX = event.getRawX(); startY = event.getRawY();
                                lockedHorizontal = false; lockedVertical = false;
                                hasMoved = false;
                                mTouchMaxDx = 0f; mTouchMaxDy = 0f;
                                if (mVelocityTracker != null) {
                                    mVelocityTracker.recycle();
                                    mVelocityTracker = null;
                                }
                                mVelocityTracker = android.view.VelocityTracker.obtain();
                                mVelocityTracker.addMovement(event);
                                v.animate().scaleX(0.97f).scaleY(0.97f)
                                    .setDuration(80).setInterpolator(new DecelerateInterpolator()).start();
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;
                                if (!lockedHorizontal && !lockedVertical) {
                                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                                    if (distance > DIRECTION_LOCK_SLOP) {
                                        hasMoved = true;
                                        // === 角度判定方向 ===
                                        // atan2(dy,dx) 返回弧度，转换为度数 [0, 360)
                                        double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
                                        if (angleDeg < 0) angleDeg += 360;
                                        // 水平方向：右滑 315°-45°，左滑 135°-225°
                                        // 垂直方向：下滑 45°-135°，上滑 225°-315°
                                        boolean isAngleHorizontal = (angleDeg >= 315 || angleDeg <= 45)
                                            || (angleDeg >= 135 && angleDeg <= 225);
                                        if (isAngleHorizontal) {
                                            lockedHorizontal = true;
                                        } else {
                                            lockedVertical = true;
                                        }
                                    }
                                } else {
                                    hasMoved = true;
                                }
                                mTouchMaxDx = Math.max(mTouchMaxDx, Math.abs(dx));
                                mTouchMaxDy = Math.max(mTouchMaxDy, Math.abs(dy));
                                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                // === 原生平移消失：跟随手指滑动，alpha 渐变 ===
                                if (mCurrentOverlay != null && (lockedHorizontal || lockedVertical)) {
                                    float progress = Math.min(1f, dist / (SWIPE_DESTROY_THRESHOLD * SWIPE_VISUAL_FACTOR));

                                    // 平移直接跟随手指（1:1），不减缓
                                    if (lockedHorizontal) {
                                        mCurrentOverlay.setTranslationX(dx);
                                        mCurrentOverlay.setTranslationY(0);
                                    } else if (lockedVertical) {
                                        mCurrentOverlay.setTranslationX(0);
                                        mCurrentOverlay.setTranslationY(dy);
                                    }

                                    // alpha 随滑动距离缓慢渐变，最小保留 0.15 避免完全隐形
                                    mCurrentOverlay.setAlpha(Math.max(0.15f, 1f - progress));

                                } else if (mCurrentOverlay != null) {
                                    // 未锁定方向前，只平移+轻微透明度反馈
                                    mCurrentOverlay.setTranslationX(dx);
                                    mCurrentOverlay.setTranslationY(dy);
                                    float preProgress = Math.min(1f, dist / (SWIPE_DESTROY_THRESHOLD * SWIPE_VISUAL_FACTOR));
                                    mCurrentOverlay.setAlpha(Math.max(0.5f, 1f - preProgress * 0.6f));
                                }
                                return true;
                            case MotionEvent.ACTION_CANCEL:
                                if (mVelocityTracker != null) {
                                    mVelocityTracker.recycle();
                                    mVelocityTracker = null;
                                }
                                v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(150).setInterpolator(new OvershootInterpolator(0.5f)).start();
                                v.setAlpha(1f);
                                if (mCurrentOverlay != null) {
                                    mCurrentOverlay.animate().cancel();
                                    mCurrentOverlay.setTranslationX(0f);
                                    mCurrentOverlay.setTranslationY(0f);
                                    mCurrentOverlay.setAlpha(1f);
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                                v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(150).setInterpolator(new OvershootInterpolator(0.5f)).start();
                                float totalDx = event.getRawX() - startX;
                                float totalDy = event.getRawY() - startY;
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
                                if (lockedHorizontal) isHorizontal = true;
                                else if (lockedVertical) isHorizontal = false;
                                else isHorizontal = Math.abs(totalDx) > Math.abs(totalDy);

                                // 计算滑动进度
                                float finalDist = (float) Math.sqrt(totalDx * totalDx + totalDy * totalDy);
                                boolean crossedThreshold = finalDist >= SWIPE_DESTROY_THRESHOLD;

                                // 点击判断：只有在没有明显移动时才判定为点击
                                if (!hasMoved && Math.abs(totalDx) < CLICK_THRESHOLD && Math.abs(totalDy) < CLICK_THRESHOLD) {
                                    performContentClick(contentIntent);
                                    // 点击后平移上滑消失
                                    if (mCurrentOverlay != null) {
                                        mCurrentOverlay.animate()
                                            .translationY(-WIN_H * 1.5f)
                                            .alpha(0f)
                                            .setDuration(250)
                                            .setInterpolator(new DecelerateInterpolator())
                                            .withEndAction(() -> removeOverlayImmediate())
                                            .start();
                                    } else {
                                        removeOverlayImmediate();
                                    }
                                    return true;
                                }

                                // 下拉展开通知栏（垂直方向且向下滑动足够距离）
                                if (totalDy > PULLDOWN_THRESHOLD && !isHorizontal) {
                                    expandStatusBar();
                                    removeOverlayImmediate();
                                    return true;
                                }

                                // 滑动销毁（已经滑过阈值）
                                if (crossedThreshold) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    // 从当前位置继续平移出屏幕（不重置属性，避免"重播"）
                                    if (mCurrentOverlay != null) {
                                        // 只取消当前属性动画，不重置 translation/alpha
                                        mCurrentOverlay.animate().cancel();
                                        if (mContentView != null) mContentView.animate().cancel();

                                        float currentTransX = mCurrentOverlay.getTranslationX();
                                        float currentTransY = mCurrentOverlay.getTranslationY();
                                        float endTransX = currentTransX;
                                        float endTransY = currentTransY;

                                        // 根据滑动方向决定出屏方向，保持另一轴当前位置
                                        if (isHorizontal) {
                                            endTransX = totalDx > 0 ? WIN_W * 1.5f : -WIN_W * 1.5f;
                                        } else {
                                            endTransY = -WIN_H * 1.5f;
                                        }

                                        mCurrentOverlay.animate()
                                            .translationX(endTransX)
                                            .translationY(endTransY)
                                            .alpha(0f)
                                            .setDuration(220)
                                            .setInterpolator(new DecelerateInterpolator())
                                            .withEndAction(() -> removeOverlayImmediate())
                                            .start();
                                    } else {
                                        removeOverlayImmediate();
                                    }
                                    return true;
                                }

                                // 有滑动意图但未达到阈值，回弹恢复
                                boolean hasSwipeIntent = (mTouchMaxDx > SWIPE_INTENT_THRESHOLD)
                                    || (mTouchMaxDy > SWIPE_INTENT_THRESHOLD);
                                boolean isFastFling = (Math.abs(velocityX) > MIN_FLING_VELOCITY)
                                    || (Math.abs(velocityY) > MIN_FLING_VELOCITY);
                                if ((hasSwipeIntent || isFastFling) && mCurrentOverlay != null) {
                                    startBounceAnimation(mCurrentOverlay);
                                    return true;
                                }
                                if (mCurrentOverlay != null) {
                                    startBounceAnimation(mCurrentOverlay);
                                }
                                return true;
                        }
                        return false;
                    }
                });

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WIN_W, WIN_H, WINDOW_TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = WIN_X;
                params.y = WIN_Y;

                // 防止初始闪烁：先设置不可见，动画开始后再显示
                root.setAlpha(0f);
                try {
                    mWindowManager.addView(root, params);
                } catch (IllegalStateException e) {
                    removeOverlayImmediate();
                    try {
                        mWindowManager.addView(root, params);
                    } catch (Throwable t2) {
                        return;
                    }
                } catch (Throwable e) {
                    return;
                }

                synchronized (mOverlayLock) {
                    mCurrentKey = key;
                    mCurrentOverlay = root;
                }
                XposedBridge.log(TAG + ": Shown: " + title);

                // 记录该应用的最后显示时间（应用级别冷却）
                mAppCooldownMap.put(sbn.getPackageName(), SystemClock.elapsedRealtime());

                startEnterAnimation(contentContainer);
                mAutoDismissRunnable = () -> {
                    dismissOverlayAnimated(1);
                };
                mHandler.postDelayed(mAutoDismissRunnable, AUTO_DISMISS_MS);
            } catch (Throwable t) {
            }
        });
    }

    private void performContentClick(PendingIntent contentIntent) {
        if (contentIntent == null) return;
        try {
            Bundle opts = createLaunchOptions();
            if (opts != null) {
                XposedHelpers.callMethod(contentIntent, "send", mContext, 0, null, null, null, null, opts);
            } else {
                contentIntent.send(mContext, 0, null);
            }
        } catch (Throwable e) {
            try { contentIntent.send(mContext, 0, null); } catch (Throwable e2) {
            }
        }
    }

    private Bundle createLaunchOptions() {
        try {
            Class<?> aoClass = Class.forName("android.app.ActivityOptions", true, mContext.getClassLoader());
            Object ao = XposedHelpers.callStaticMethod(aoClass, "makeBasic");
            XposedHelpers.callMethod(ao, "setLaunchWindowingMode", 1);
            return (Bundle) XposedHelpers.callMethod(ao, "toBundle");
        } catch (Throwable t) { return null; }
    }

    private void expandStatusBar() {
        triggerGlobalCooldown();
        try {
            Object sbm = mContext.getSystemService("statusbar");
            if (sbm != null) {
                java.lang.reflect.Method expand = sbm.getClass().getMethod("expandNotificationsPanel");
                expand.invoke(sbm);
            }
        } catch (Throwable t) {}
    }

    private void dismissOverlayAnimated() { dismissOverlayAnimated(1); }

    private void dismissOverlayAnimated(final int exitDirection) {
        if (mHandler == null) {
            return;
        }
        final Handler handler = mHandler;
        handler.post(() -> {
            if (mAutoDismissRunnable != null) {
                handler.removeCallbacks(mAutoDismissRunnable);
                mAutoDismissRunnable = null;
            }
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) {
                removeOverlayImmediate(); return;
            }
            startExitAnimation(mCurrentOverlay, () -> removeOverlayImmediate(), exitDirection);
        });
    }

    private void removeOverlayImmediate() {
        cancelAllAnimations();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
        // 注销 ScreenReceiver
        unregisterScreenReceiver();

        final View overlayToRemove;
        synchronized (mOverlayLock) {
            overlayToRemove = mCurrentOverlay;
            mCurrentKey = null;
            mCurrentRowView = null;
            mCurrentOverlay = null;
            mCurrentContentHash = null;
        }

        if (overlayToRemove != null) {
            try {
                overlayToRemove.setAlpha(0f);
                if (mContentView != null) {
                    mContentView.setOnTouchListener(null);
                }
            } catch (Throwable ignored) {}
            final WindowManager wm = mWindowManager;
            if (wm != null) {
                mHandler.post(() -> {
                    try {
                        if (overlayToRemove.getParent() != null) {
                            wm.removeView(overlayToRemove);
                        } else {
                        }
                    } catch (Throwable t) {
                        try { wm.removeViewImmediate(overlayToRemove); } catch (Throwable ignored) {}
                    }
                });
            }
        } else {
        }

        mContentView = null;
        mIconView = null;
        mTitleView = null;
        mTextView = null;
    }
} // MainHook
