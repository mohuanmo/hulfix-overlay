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

    private static final float SWIPE_DESTROY_THRESHOLD = 70f;
    private static final float PULLDOWN_THRESHOLD = 120f;
    private static final float DIRECTION_LOCK_SLOP = 25f;
    private static final float ANGLE_LOCK_DEGREES = 45f; // 角度容错：±45°内为水平，之外为垂直
    private static final float MIN_FLING_VELOCITY = 200f;
    private static final float SWIPE_INTENT_THRESHOLD = 40f;
    private static final float CLICK_THRESHOLD = 8f;

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

    private ValueAnimator mEnterAnim = null;
    private ValueAnimator mExitAnim = null;
    private ValueAnimator mBounceAnim = null;
    private float mEnterProgress = 0f;

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
        ValueAnimator enter = mEnterAnim;
        ValueAnimator exit = mExitAnim;
        ValueAnimator bounce = mBounceAnim;
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
        // === 初始状态：完全在屏幕右边缘外，像一滴液体悬在边缘等待落下 ===
        // 横屏通知在右上角，从右侧远处滑入
        // 使用足够大的偏移确保完全在屏幕外，不受屏幕分辨率影响
        view.setAlpha(0f);
        view.setTranslationX(WIN_W * 2.0f);   // 向右偏移 2 倍宽度，确保完全在屏幕外
        view.setTranslationY(-WIN_H * 3.0f);  // 向上偏移 3 倍高度，从远处斜上方进入
        view.setScaleX(0.0f);                  // 完全无体积，像未凝聚的液态
        view.setScaleY(0.0f);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 内容层初始完全隐藏
        if (mIconView != null) { mIconView.setAlpha(0f); mIconView.setScaleX(0.2f); mIconView.setScaleY(0.2f); }
        if (mTitleView != null) { mTitleView.setAlpha(0f); mTitleView.setTranslationY(20f); }
        if (mTextView != null) { mTextView.setAlpha(0f); mTextView.setTranslationY(16f); }

        mEnterAnim = ValueAnimator.ofFloat(0f, 1f);
        mEnterAnim.setDuration(700);  // 增加到 700ms，让整个过程更从容
        mEnterAnim.setInterpolator(null);
        mEnterAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            mEnterProgress = t;

            // === Liquid Glass 弹出动画：从边缘流淌 → 凝聚成形 → 膨胀展开 → 回弹稳定 ===
            float containerAlpha, containerScale, cornerRadius, popGlow;
            float transX, transY;  // 位移

            if (t < 0.15f) {
                // 阶段1：边缘涌现（0~105ms）—— 液体从远处"渗透"进来
                float p = t / 0.15f;
                float ease = p * p * (3f - 2f * p); // smoothstep
                containerAlpha = 0.0f + 0.20f * ease;       // 0 → 0.20，极淡地出现
                containerScale = 0.0f + 0.12f * ease;       // 0 → 0.12，从无到有凝聚
                // 从远处滑入：translationX 从 2*WIN_W → 0，translationY 从 -3*WIN_H → 0
                transX = WIN_W * 2.0f * (1f - ease);
                transY = -WIN_H * 3.0f * (1f - ease);
                cornerRadius = WIN_H * 0.5f; // 保持完全圆形（水滴状）
                popGlow = 0f;
            } else if (t < 0.40f) {
                // 阶段2：流淌凝聚（105~280ms）—— 液体继续滑入并快速凝聚
                float p = (t - 0.15f) / 0.25f;
                float ease = p * p * (3f - 2f * p);
                // 弹性滑入：用 spring 让到达目标位置时有轻微回弹
                float spring = (float)(1.0 - Math.exp(-4.0 * p) * Math.cos(6.0 * p));
                containerAlpha = 0.20f + 0.55f * spring;      // 0.20 → 0.75
                containerScale = 0.12f + 0.58f * spring;      // 0.12 → 0.70
                // 位移：从远处平滑到目标位置
                float posProgress = Math.min(1f, spring);
                transX = WIN_W * 2.0f * (1f - posProgress);
                transY = -WIN_H * 3.0f * (1f - posProgress);
                cornerRadius = WIN_H * 0.5f * (1f - p * 0.4f); // 开始变平
                popGlow = p * 0.3f; // 光晕开始微亮
            } else if (t < 0.60f) {
                // 阶段3：膨胀展开（245~420ms）—— 水滴落在玻璃上扩散
                float p = (t - 0.35f) / 0.25f;
                // spring-like overshoot: 快速冲过目标再回弹
                float spring = (float)(1.0 - Math.exp(-5.0 * p) * Math.cos(8.0 * p));
                containerAlpha = 0.75f + 0.25f * spring;      // 0.75 → 1.0
                containerScale = 0.70f + 0.45f * spring;      // 0.70 → 1.15 overshoot
                transX = 0f; // 已到达目标位置
                transY = 0f;
                cornerRadius = WIN_H * 0.5f * (1f - 0.4f - p * 0.55f); // 继续变平
                popGlow = (float)Math.sin(p * Math.PI) * 0.95f; // 光晕在膨胀时最亮
            } else if (t < 0.82f) {
                // 阶段4：表面张力回弹（420~574ms）—— 液体表面张力让玻璃稳定
                float p = (t - 0.60f) / 0.22f;
                float ease = p * p * (3f - 2f * p);
                // 从 overshoot 回弹：1.15 → 0.96 → 1.03 → 1.0
                float decay = (float)Math.exp(-3.5 * p);
                float oscillation = (float)Math.cos(7.0 * p);
                containerAlpha = 1f;
                containerScale = 1.02f + 0.13f * decay * oscillation - 0.02f * ease;
                transX = 0f; transY = 0f;
                cornerRadius = 28f + (WIN_H * 0.5f - 28f) * 0.08f * (1f - ease); // 接近最终圆角
                popGlow = 0.95f * (1f - ease); // 光晕逐渐消散
            } else {
                // 阶段5：平静定型（574~700ms）—— 完全稳定
                float p = (t - 0.82f) / 0.18f;
                float ease = p * p * (3f - 2f * p);
                containerAlpha = 1f;
                containerScale = 1.0f + 0.008f * (1f - ease); // 从 1.008 平滑到 1.0
                transX = 0f; transY = 0f;
                cornerRadius = 28f;
                popGlow = 0f;
            }

            view.setAlpha(containerAlpha);
            view.setScaleX(containerScale);
            view.setScaleY(containerScale);
            view.setTranslationX(transX);
            view.setTranslationY(transY);

            // === 内容分层浮现：像从玻璃内部浮出，比容器更晚 ===
            // 图标：t=0.25 开始，带弹性浮出
            if (mIconView != null && t > 0.25f) {
                float ip = Math.min(1f, (t - 0.25f) / 0.35f);
                float iease = ip * ip * (3f - 2f * ip);
                float ispring = (float)(1.0 - Math.exp(-4.0 * ip) * Math.cos(6.0 * ip));
                mIconView.setAlpha(iease);
                float iconScale = 0.2f + 0.8f * ispring;
                mIconView.setScaleX(iconScale);
                mIconView.setScaleY(iconScale);
            }
            // 标题：t=0.40 开始，从下方滑入 + 淡入
            if (mTitleView != null && t > 0.40f) {
                float tp = Math.min(1f, (t - 0.40f) / 0.30f);
                float tease = tp * tp * (3f - 2f * tp);
                mTitleView.setAlpha(tease);
                mTitleView.setTranslationY(20f * (1f - tease));
            }
            // 文字：t=0.55 开始，淡入 + 轻微上浮
            if (mTextView != null && t > 0.55f) {
                float cp = Math.min(1f, (t - 0.55f) / 0.28f);
                float cease = cp * cp * (3f - 2f * cp);
                mTextView.setAlpha(cease);
                mTextView.setTranslationY(16f * (1f - cease));
            }
        });
        mEnterAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (mEnterAnim != animation) return;
                mEnterAnim = null;
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                view.setAlpha(1f);
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setTranslationX(0f);
                view.setTranslationY(0f);
                mEnterProgress = 1f;
                // 恢复 root 的可见性
                if (mCurrentOverlay != null) mCurrentOverlay.setAlpha(1f);
                if (mIconView != null) { mIconView.setAlpha(1f); mIconView.setScaleX(1f); mIconView.setScaleY(1f); }
                if (mTitleView != null) { mTitleView.setAlpha(1f); mTitleView.setTranslationY(0f); }
                if (mTextView != null) { mTextView.setAlpha(1f); mTextView.setTranslationY(0f); }
            }
        });
        mEnterAnim.start();
    }
    private void startExitAnimation(final View view, final Runnable onEnd, final int exitDirection) {
        cancelAllAnimations();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 内容先收回（Stagger 反向）
        if (mTextView != null) mTextView.animate().alpha(0f).setDuration(60).start();
        if (mTitleView != null) mTitleView.animate().alpha(0f).translationX(-15f).setDuration(80).setStartDelay(30).start();
        if (mIconView != null) mIconView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(80).setStartDelay(60).start();

        mExitAnim = ValueAnimator.ofFloat(0f, 1f);
        mExitAnim.setDuration(280);
        mExitAnim.setInterpolator(null);
        mExitAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float alpha = 1f - t;
            float scale = 1f - 0.15f * t;
            float transX = 0f, transY = 0f;
            if (exitDirection == 0) {
                // 左滑退出
                transX = -WIN_W * 1.2f * t;
                transY = -WIN_H * 0.3f * t;
            } else if (exitDirection == 1) {
                // 上滑退出
                transX = 0f;
                transY = -WIN_H * 2.0f * t;
            } else {
                // 右滑退出
                transX = WIN_W * 1.2f * t;
                transY = -WIN_H * 0.3f * t;
            }
            // 加速曲线
            float accel = t * t;
            transX *= accel;
            transY *= accel;
            alpha = Math.max(0f, 1f - accel);
            scale = Math.max(0.01f, 1f - 0.2f * accel);

            view.setAlpha(Math.max(0f, alpha));
            view.setScaleX(Math.max(0.01f, scale));
            view.setScaleY(Math.max(0.01f, scale));
            view.setTranslationX(transX);
            view.setTranslationY(transY);
        });
        mExitAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (mExitAnim != animation) return;
                mExitAnim = null;
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                if (onEnd != null) onEnd.run();
            }
        });
        mExitAnim.start();
    }

    private void startBounceAnimation(final View view, final float direction) {
        cancelAllAnimations();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mBounceAnim = ValueAnimator.ofFloat(0f, 1f);
        mBounceAnim.setDuration(400);
        mBounceAnim.setInterpolator(null);
        mBounceAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            // 弹性回弹：从当前位置弹回原点
            float decay = (float)Math.exp(-5.0 * t);
            float oscillation = (float)Math.cos(10.0 * t);
            float offset = direction * 30f * decay * oscillation;
            view.setTranslationX(offset);
            view.setAlpha(Math.min(1f, 0.7f + 0.3f * (1f - decay)));
        });
        mBounceAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (mBounceAnim != animation) return;
                mBounceAnim = null;
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                view.setTranslationX(0f);
                view.setAlpha(1f);
            }
        });
        mBounceAnim.start();
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

                // === 根容器：FrameLayout（系统圆角阴影 + 裁切）===
                FrameLayout root = new FrameLayout(mContext);
                root.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                // 使用系统 Elevation 画圆角阴影（比 BlurMaskFilter 更可靠）
                root.setElevation(16f);
                root.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 28f);
                    }
                });
                root.setClipToOutline(true);

                // === 内容容器（可移动）===
                LinearLayout contentContainer = new LinearLayout(mContext);
                contentContainer.setOrientation(LinearLayout.HORIZONTAL);
                contentContainer.setPadding(28, 18, 28, 18);
                contentContainer.setGravity(Gravity.CENTER_VERTICAL);
                contentContainer.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                // 硬件层会绕过父视图 ClipToOutline，改为 NONE 让 root 统一裁剪
                contentContainer.setLayerType(View.LAYER_TYPE_NONE, null);

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

                // === 触摸事件处理（角度判定方向 + 严格方向锁定）===
                contentContainer.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;
                    boolean lockedHorizontal = false;
                    boolean lockedVertical = false;
                    boolean hasMoved = false;

                    @Override public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
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
                                // Touch feedback on root overlay, not contentContainer
                                if (mCurrentOverlay != null) {
                                    mCurrentOverlay.setAlpha(Math.max(0.5f, 1f - dist / 300f));
                                }
                                // === 严格方向锁定移动 ===
                                // 水平锁定：只移动 X 轴，Y 轴固定为 0
                                // 垂直锁定：只移动 Y 轴，X 轴固定为 0
                                if (mCurrentOverlay != null) {
                                    if (lockedHorizontal) {
                                        mCurrentOverlay.setTranslationX(dx);
                                        mCurrentOverlay.setTranslationY(0);
                                    } else if (lockedVertical) {
                                        mCurrentOverlay.setTranslationX(0);
                                        mCurrentOverlay.setTranslationY(dy);
                                    } else {
                                        mCurrentOverlay.setTranslationX(dx);
                                        mCurrentOverlay.setTranslationY(dy);
                                    }
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
                                    mCurrentOverlay.setTranslationX(0f);
                                    mCurrentOverlay.setTranslationY(0f);
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

                                // 优先判断滑动销毁（需要方向锁定或明显移动）
                                boolean significantMove = hasMoved || Math.abs(totalDx) > SWIPE_DESTROY_THRESHOLD || Math.abs(totalDy) > SWIPE_DESTROY_THRESHOLD;

                                if (significantMove && totalDy < -SWIPE_DESTROY_THRESHOLD && !isHorizontal) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    dismissOverlayAnimated(1); return true;
                                }
                                if (significantMove && totalDx < -SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    dismissOverlayAnimated(0); return true;
                                }
                                if (significantMove && totalDx > SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    dismissOverlayAnimated(2); return true;
                                }
                                if (totalDy > PULLDOWN_THRESHOLD && !isHorizontal) {
                                    expandStatusBar(); removeOverlayImmediate(); return true;
                                }

                                // 点击判断：只有在没有明显移动时才判定为点击
                                if (!hasMoved && Math.abs(totalDx) < CLICK_THRESHOLD && Math.abs(totalDy) < CLICK_THRESHOLD) {
                                    performContentClick(contentIntent);
                                    dismissOverlayAnimated(1);
                                    return true;
                                }

                                // 有滑动意图但未达到阈值，回弹
                                boolean hasSwipeIntent = (mTouchMaxDx > SWIPE_INTENT_THRESHOLD)
                                    || (mTouchMaxDy > SWIPE_INTENT_THRESHOLD);
                                boolean isFastFling = (Math.abs(velocityX) > MIN_FLING_VELOCITY)
                                    || (Math.abs(velocityY) > MIN_FLING_VELOCITY);
                                if (hasSwipeIntent || isFastFling) {
                                    if (mCurrentOverlay != null) startBounceAnimation(mCurrentOverlay, totalDx < 0 ? -1f : 1f);
                                    return true;
                                }
                                if (mCurrentOverlay != null) startBounceAnimation(mCurrentOverlay, totalDx < 0 ? -1f : 1f);
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        mEnterProgress = 0f;
    }
} // MainHook
