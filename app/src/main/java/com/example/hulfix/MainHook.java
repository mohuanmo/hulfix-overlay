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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.BitmapShader;
import android.graphics.RadialGradient;
import android.graphics.LinearGradient;
import android.graphics.SweepGradient;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.animation.LinearInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
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
    private static final long APP_COOLDOWN_MS = 500;

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
    private ImageView mBgImageView = null;
    private LiquidGlassView mGlassView = null;
    private Bitmap mBlurredBgBitmap = null;
    private Runnable mBgUpdateRunnable = null;
    private static final long BG_UPDATE_INTERVAL_MS = 500;
    private static final float BLUR_RADIUS = 22f;
    private static final int BLUR_SCALE_FACTOR = 4;

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
                        XposedBridge.log(TAG + ": hookMethod failed for " + methodName + " in " + currentClass.getName() + ": " + t);
                    }
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        XposedBridge.log(TAG + ": hookAllMethodsCompat '" + methodName + "' hooked " + hookedCount + " method(s) in class hierarchy");
    }

    private void hookAllConstructorsCompat(Class<?> clazz, XC_MethodHook callback) {
        for (java.lang.reflect.Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(constructor, callback);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hookConstructor failed: " + t);
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
                        XposedBridge.log(TAG + ": Context and WindowManager initialized");
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
            XposedBridge.log(TAG + ": HeadsUpManager capture hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": HeadsUpManager capture failed: " + t);
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
                XposedBridge.log(TAG + ": " + className + ".addNotification hooked");
                hooked = true;
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + className + " not found or hook failed: " + t);
            }
        }
        if (!hooked) {
            XposedBridge.log(TAG + ": hookNotificationEntry failed - no valid class found");
        }

        // === 新增：Hook NotificationListener.onNotificationPosted（直接接收 StatusBarNotification）===
        try {
            Class<?> listenerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.NotificationListener", lpparam.classLoader);
            hookAllMethodsCompat(listenerClass, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) {
                        XposedBridge.log(TAG + ": NotificationListener.onNotificationPosted triggered, pkg=" + sbn.getPackageName());
                        processNotification(sbn);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotificationListener.onNotificationPosted hook skipped: " + t);
        }
        // === 同时 Hook 父类 NotificationListenerService 的 onNotificationPosted ===
        try {
            Class<?> nlsClass = XposedHelpers.findClass(
                "android.service.notification.NotificationListenerService", lpparam.classLoader);
            hookAllMethodsCompat(nlsClass, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) {
                        XposedBridge.log(TAG + ": NotificationListenerService.onNotificationPosted triggered, pkg=" + sbn.getPackageName());
                        processNotification(sbn);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotificationListenerService.onNotificationPosted hook skipped: " + t);
        }

        // === 新增：Hook NotifCollection.onNotificationPosted ===
        try {
            Class<?> notifCollClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotifCollection", lpparam.classLoader);
            hookAllMethodsCompat(notifCollClass, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) {
                        XposedBridge.log(TAG + ": NotifCollection.onNotificationPosted triggered, pkg=" + sbn.getPackageName());
                        processNotification(sbn);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotifCollection.onNotificationPosted hook skipped: " + t);
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
                            XposedBridge.log(TAG + ": NotificationEntry CONSTRUCTOR triggered, pkg=" + statusBarNotification.getPackageName());
                            processNotification(statusBarNotification);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": NotificationEntry constructor hook error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": NotificationEntry constructors hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotificationEntry hook skipped: " + t);
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
                            XposedBridge.log(TAG + ": NotificationEntry UPDATE triggered, pkg=" + statusBarNotification.getPackageName());
                            processNotification(statusBarNotification);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": NotificationEntry update hook error: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": NotificationEntry.updateNotification hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotificationEntry.updateNotification hook skipped: " + t);
        }
    }

    private StatusBarNotification extractSbnFromArgs(Object[] args) {
        XposedBridge.log(TAG + "[DIAG] extractSbnFromArgs called, args count=" + args.length);
        // 1. 直接查找 StatusBarNotification 参数
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            String type = arg != null ? arg.getClass().getName() : "null";
            XposedBridge.log(TAG + "[DIAG] arg[" + i + "] type=" + type);
            if (arg instanceof StatusBarNotification) {
                XposedBridge.log(TAG + "[DIAG] Found StatusBarNotification at arg[" + i + "]");
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
                    XposedBridge.log(TAG + "[DIAG] Extracted SBN via getSbn() from arg[" + i + "]");
                    return (StatusBarNotification) result;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[DIAG] getSbn() failed for arg[" + i + "]: " + t.getMessage());
            }
            try {
                Object result = XposedHelpers.getObjectField(arg, "mSbn");
                if (result instanceof StatusBarNotification) {
                    XposedBridge.log(TAG + "[DIAG] Extracted SBN via mSbn field from arg[" + i + "]");
                    return (StatusBarNotification) result;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[DIAG] mSbn field failed for arg[" + i + "]: " + t.getMessage());
            }
            try {
                Object result = XposedHelpers.getObjectField(arg, "sbn");
                if (result instanceof StatusBarNotification) {
                    XposedBridge.log(TAG + "[DIAG] Extracted SBN via sbn field from arg[" + i + "]");
                    return (StatusBarNotification) result;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[DIAG] sbn field failed for arg[" + i + "]: " + t.getMessage());
            }
        }
        XposedBridge.log(TAG + "[DIAG] extractSbnFromArgs: FAILED to find StatusBarNotification");
        return null;
    }

    private void processNotification(StatusBarNotification sbn) {
        try {
            XposedBridge.log(TAG + "[DIAG] === processNotification START ===");
            XposedBridge.log(TAG + "[DIAG] pkg=" + (sbn != null ? sbn.getPackageName() : "null")
                + ", key=" + (sbn != null ? sbn.getKey() : "null"));
            if (sbn == null) {
                XposedBridge.log(TAG + "[DIAG] sbn is null, returning");
                return;
            }
            final String key = sbn.getKey();
            final Notification notification = sbn.getNotification();

            if (BLOCK_PKG.equals(sbn.getPackageName())) {
                XposedBridge.log(TAG + "[DIAG] BLOCKED by BLOCK_PKG: " + sbn.getPackageName());
                return;
            }
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                XposedBridge.log(TAG + "[DIAG] BLOCKED by FLAG_ONGOING_EVENT");
                return;
            }
            if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                XposedBridge.log(TAG + "[DIAG] BLOCKED by FLAG_FOREGROUND_SERVICE");
                return;
            }
            boolean fresh = isFreshNotification(sbn);
            XposedBridge.log(TAG + "[DIAG] isFreshNotification=" + fresh);
            if (!fresh) return;

            // 全局冷却检查
            if (isGlobalCooldown()) {
                XposedBridge.log(TAG + "[DIAG] BLOCKED by global cooldown");
                return;
            }

            // 勿扰模式检查
            if (isDoNotDisturb()) {
                XposedBridge.log(TAG + "[DIAG] BLOCKED by Do Not Disturb");
                return;
            }

            // 用户手动划掉后的冷却：只对完全相同的通知 key 生效
            boolean userIgnored = mUserDismissedKey != null && key.equals(mUserDismissedKey)
                && SystemClock.elapsedRealtime() - mUserDismissTime < USER_IGNORE_COOLDOWN_MS;
            XposedBridge.log(TAG + "[DIAG] userIgnored=" + userIgnored);
            if (userIgnored) return;

            // 应用级别冷却：同一应用 500ms 内只显示一次，不影响其他应用
            String pkg = sbn.getPackageName();
            Long lastAppTime = mAppCooldownMap.get(pkg);
            boolean appCooldown = lastAppTime != null && SystemClock.elapsedRealtime() - lastAppTime < APP_COOLDOWN_MS;
            XposedBridge.log(TAG + "[DIAG] appCooldown=" + appCooldown + " for " + pkg);
            if (appCooldown) return;

            boolean panelExpanded = isStatusBarExpanded();
            XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=" + panelExpanded);
            if (panelExpanded) return;

            boolean keyguard = isKeyguardLocked();
            XposedBridge.log(TAG + "[DIAG] isKeyguardLocked=" + keyguard);
            if (keyguard) return;

            XposedBridge.log(TAG + "[DIAG] All checks passed, calling showCustomHeadsUp");
            showCustomHeadsUp(sbn);
            XposedBridge.log(TAG + "[DIAG] === processNotification END ===");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[DIAG] processNotification ERROR: " + t);
            XposedBridge.log(TAG + "[DIAG] Stack: " + android.util.Log.getStackTraceString(t));
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
                    XposedBridge.log(TAG + ": CentralSurfacesImpl captured via constructor");
                }
            });
            XposedHelpers.findAndHookMethod(csClass, "start", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBar == null) {
                        mStatusBar = param.thisObject;
                        XposedBridge.log(TAG + ": CentralSurfacesImpl captured via start()");
                    }
                }
            });
            hookAllMethodsCompat(csClass, "addNotification", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBar == null) {
                        mStatusBar = param.thisObject;
                        XposedBridge.log(TAG + ": CentralSurfacesImpl captured via addNotification");
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
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook expandNotificationsPanel skipped: " + t); }
            try {
                XposedHelpers.findAndHookMethod(csClass, "setExpandedVisible", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        boolean visible = (boolean) param.args[0];
                        mIsPanelExpanded = visible;
                        if (visible) triggerGlobalCooldown();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook setExpandedVisible skipped: " + t); }
            try {
                XposedHelpers.findAndHookMethod(csClass, "makeExpandedVisible", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        triggerGlobalCooldown();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook makeExpandedVisible skipped: " + t); }
            XposedBridge.log(TAG + ": CentralSurfacesImpl hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CentralSurfacesImpl hooks failed: " + t);
        }
    }

    private boolean isFreshNotification(StatusBarNotification sbn) {
        long age = System.currentTimeMillis() - sbn.getPostTime();
        boolean result = age <= NOTIFICATION_MAX_AGE_MS;
        XposedBridge.log(TAG + "[DIAG] isFreshNotification: age=" + age + "ms, max=" + NOTIFICATION_MAX_AGE_MS + "ms, result=" + result);
        return result;
    }

    private boolean isKeyguardLocked() {
        if (mContext == null) {
            XposedBridge.log(TAG + "[DIAG] isKeyguardLocked: mContext null, returning false");
            return false;
        }
        try {
            KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            boolean result = km != null && km.isKeyguardLocked();
            XposedBridge.log(TAG + "[DIAG] isKeyguardLocked=" + result);
            return result;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[DIAG] isKeyguardLocked exception: " + t);
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
        XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded: mIsPanelExpanded=" + mIsPanelExpanded + ", mStatusBar=" + (mStatusBar != null));
        if (mIsPanelExpanded) {
            XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=true (mIsPanelExpanded)");
            return true;
        }
        if (mStatusBar == null) {
            XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=false (mStatusBar null)");
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
                XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=" + r + " (" + fieldName + "=" + val + ")");
                return r;
            } catch (Throwable ignored) {}
        }
        XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=false (all fields missing)");
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
        XposedBridge.log(TAG + "[DIAG] isGlobalCooldown=" + result + " (elapsed=" + elapsed + "ms, threshold=" + GLOBAL_COOLDOWN_MS + "ms)");
        return result;
    }

    private void triggerGlobalCooldown() {
        XposedBridge.log(TAG + "[DIAG] triggerGlobalCooldown called");
        mGlobalCooldownTime = SystemClock.elapsedRealtime();
        if (mCurrentOverlay != null) removeOverlayImmediate();
    }

    private void registerScreenReceiver() {
        XposedBridge.log(TAG + "[DIAG] registerScreenReceiver called, registered=" + mBroadcastRegistered + ", ctx=" + (mContext != null));
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
                    XposedBridge.log(TAG + "[DIAG] Panel expanded via expandNotificationsPanel");
                }
            });
            // 面板收起
            hookAllMethodsCompat(csClass, "collapsePanels", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    mIsPanelExpanded = false;
                    XposedBridge.log(TAG + "[DIAG] Panel collapsed via collapsePanels");
                }
            });
            hookAllMethodsCompat(csClass, "animateCollapsePanels", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    mIsPanelExpanded = false;
                    XposedBridge.log(TAG + "[DIAG] Panel collapsed via animateCollapsePanels");
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
                                XposedBridge.log(TAG + "[DIAG] Panel expanded via setExpandedFraction, fraction=" + fraction);
                            } else if (!mIsPanelExpanded && wasExpanded) {
                                XposedBridge.log(TAG + "[DIAG] Panel collapsed via setExpandedFraction, fraction=" + fraction);
                            }
                        }
                    });
            } catch (Throwable ignored) {}
            XposedBridge.log(TAG + ": CentralSurfacesImpl panel hooks applied");
        } catch (Throwable t) { XposedBridge.log(TAG + ": hookPanelExpansion failed: " + t); }
    }

    private void cancelAllAnimations() {
        XposedBridge.log(TAG + "[DIAG] cancelAllAnimations called");
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
        XposedBridge.log(TAG + "[DIAG] startEnterAnimation called");
        cancelAllAnimations();
        // 初始状态：完全看不见的圆点
        view.setAlpha(0f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(0f);
        view.setScaleY(0f);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 内容层初始隐藏
        if (mIconView != null) { mIconView.setAlpha(0f); mIconView.setScaleX(0f); mIconView.setScaleY(0f); }
        if (mTitleView != null) { mTitleView.setAlpha(0f); mTitleView.setTranslationX(-30f); }
        if (mTextView != null) { mTextView.setAlpha(0f); }
        // 背景模糊初始隐藏
        if (mBgImageView != null) mBgImageView.setAlpha(0f);
        // 圆角初始为圆形
        if (mGlassView != null) mGlassView.setCornerRadius(WIN_H * 0.5f);

        mEnterAnim = ValueAnimator.ofFloat(0f, 1f);
        mEnterAnim.setDuration(520);
        mEnterAnim.setInterpolator(null);
        mEnterAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            mEnterProgress = t;
            float containerAlpha, containerScale, cornerRadius;

            // === 容器主体动画 ===
            if (t < 0.08f) {
                // 阶段1：圆点凝聚（0~42ms）
                float p = t / 0.08f;
                float ease = p * p;
                containerAlpha = ease * 0.3f;
                containerScale = ease * 0.15f;
                cornerRadius = WIN_H * 0.5f;
            } else if (t < 0.28f) {
                // 阶段2：爆发膨胀到 overshoot（42~146ms）
                float p = (t - 0.08f) / 0.20f;
                float ease = 1f - (1f - p) * (1f - p) * (1f - p);
                containerAlpha = 0.3f + ease * 0.7f;
                containerScale = 0.15f + 1.0f * ease;
                cornerRadius = WIN_H * 0.5f * (1f - ease * 0.85f) + 28f * (ease * 0.85f);
            } else if (t < 0.55f) {
                // 阶段3：弹性回弹（146~286ms）—— 阻尼弹簧 2~3次抖动
                float p = (t - 0.28f) / 0.27f;
                float decay = (float) Math.exp(-4.5f * p);
                float oscillation = (float) Math.sin(p * Math.PI * 4.5f);
                float bounce = decay * oscillation * 0.12f;
                containerAlpha = 1f;
                containerScale = 1.15f - 0.15f * p + bounce;
                cornerRadius = 28f + (WIN_H * 0.08f) * decay * Math.abs(oscillation);
            } else if (t < 0.75f) {
                // 阶段4：稳定收敛（286~390ms）
                float p = (t - 0.55f) / 0.20f;
                float ease = p * p * (3f - 2f * p);
                containerAlpha = 1f;
                containerScale = 1.0f;
                cornerRadius = 28f + (WIN_H * 0.08f) * (1f - ease);
            } else {
                // 阶段5：稳定呼吸（390~520ms）
                float p = (t - 0.75f) / 0.25f;
                float breath = (float) Math.sin(p * Math.PI * 2) * 0.003f;
                containerAlpha = 1f;
                containerScale = 1.0f + breath;
                cornerRadius = 28f;
            }

            view.setAlpha(containerAlpha);
            view.setScaleX(containerScale);
            view.setScaleY(containerScale);
            if (mGlassView != null) mGlassView.setCornerRadius(cornerRadius);

            // === 背景模糊淡入（膨胀到60%左右开始）===
            if (mBgImageView != null) {
                float bgAlpha = 0f;
                if (t > 0.25f) {
                    float bp = Math.min(1f, (t - 0.25f) / 0.35f);
                    bgAlpha = bp * bp * (3f - 2f * bp);
                }
                mBgImageView.setAlpha(bgAlpha);
            }

            // === 内容 Stagger ===
            // 图标：t=0.18 开始弹出（94ms）
            if (mIconView != null && t > 0.18f) {
                float ip = Math.min(1f, (t - 0.18f) / 0.18f);
                float iease = 1f - (1f - ip) * (1f - ip) * (1f - ip);
                float ibounce = (float) Math.sin(ip * Math.PI) * 0.15f;
                mIconView.setAlpha(iease);
                mIconView.setScaleX(iease + ibounce);
                mIconView.setScaleY(iease + ibounce);
            }
            // 标题：t=0.30 开始从左滑入（156ms）
            if (mTitleView != null && t > 0.30f) {
                float tp = Math.min(1f, (t - 0.30f) / 0.20f);
                float tease = tp * tp * (3f - 2f * tp);
                mTitleView.setAlpha(tease);
                mTitleView.setTranslationX(-30f * (1f - tease));
            }
            // 内容文字：t=0.42 开始淡入（218ms）
            if (mTextView != null && t > 0.42f) {
                float cp = Math.min(1f, (t - 0.42f) / 0.18f);
                float cease = cp * cp * (3f - 2f * cp);
                mTextView.setAlpha(cease);
            }
        });
        mEnterAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                // 使用局部变量避免竞态：cancelAllAnimations 可能已经清空了 mEnterAnim
                if (mEnterAnim != animation) return;
                mEnterAnim = null;
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                view.setAlpha(1f);
                view.setScaleX(1f);
                view.setScaleY(1f);
                mEnterProgress = 1f;
                if (mBgImageView != null) mBgImageView.setAlpha(1f);
                if (mIconView != null) { mIconView.setAlpha(1f); mIconView.setScaleX(1f); mIconView.setScaleY(1f); }
                if (mTitleView != null) { mTitleView.setAlpha(1f); mTitleView.setTranslationX(0f); }
                if (mTextView != null) mTextView.setAlpha(1f);
                if (mGlassView != null) mGlassView.setCornerRadius(28f);
            }
        });
        mEnterAnim.start();
    }

    private void startExitAnimation(final View view, final Runnable onEnd, final int exitDirection) {
        XposedBridge.log(TAG + "[DIAG] startExitAnimation called, direction=" + exitDirection);
        cancelAllAnimations();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 内容先收回（Stagger 反向）
        if (mTextView != null) mTextView.animate().alpha(0f).setDuration(60).start();
        if (mTitleView != null) mTitleView.animate().alpha(0f).translationX(-15f).setDuration(80).setStartDelay(30).start();
        if (mIconView != null) mIconView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(80).setStartDelay(60).start();
        if (mBgImageView != null) mBgImageView.animate().alpha(0f).setDuration(100).start();

        mExitAnim = ValueAnimator.ofFloat(0f, 1f);
        mExitAnim.setDuration(280);
        mExitAnim.setInterpolator(null);
        mExitAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float alpha, scale, cornerRadius, transX, transY;
            if (t < 0.25f) {
                // 阶段1：吸一下（0~70ms）
                float p = t / 0.25f;
                float ease = p * p;
                alpha = 1f - ease * 0.1f;
                scale = 1f - ease * 0.12f;
                cornerRadius = 28f + (WIN_H * 0.5f - 28f) * ease * 0.3f;
                transX = 0f; transY = 0f;
            } else if (t < 0.65f) {
                // 阶段2：缩成圆点（70~182ms）
                float p = (t - 0.25f) / 0.40f;
                float ease = p * p * p;
                alpha = 0.9f - ease * 0.7f;
                scale = 0.88f - ease * 0.88f;
                cornerRadius = 28f + (WIN_H * 0.5f - 28f) * (0.3f + ease * 0.7f);
                switch (exitDirection) {
                    case 1: transX = 0f; transY = -10f * ease; break;
                    case 2: transX = 10f * ease; transY = 0f; break;
                    default: transX = -10f * ease; transY = 0f; break;
                }
            } else {
                // 阶段3："啵"地消失（182~280ms）
                float p = (t - 0.65f) / 0.35f;
                float ease = p * p * p * p;
                alpha = 0.2f * (1f - ease);
                scale = ease * 0.02f;
                cornerRadius = WIN_H * 0.5f;
                switch (exitDirection) {
                    case 1: transX = 0f; transY = -10f - 20f * ease; break;
                    case 2: transX = 10f + 20f * ease; transY = 0f; break;
                    default: transX = -10f - 20f * ease; transY = 0f; break;
                }
            }
            view.setAlpha(Math.max(0f, alpha));
            view.setScaleX(Math.max(0.01f, scale));
            view.setScaleY(Math.max(0.01f, scale));
            view.setTranslationX(transX);
            view.setTranslationY(transY);
            if (mGlassView != null) mGlassView.setCornerRadius(cornerRadius);
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
        XposedBridge.log(TAG + "[DIAG] startBounceAnimation called, direction=" + direction);
        cancelAllAnimations();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mBounceAnim = ValueAnimator.ofFloat(0f, 1f);
        mBounceAnim.setDuration(450);
        mBounceAnim.setInterpolator(null);
        mBounceAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            // 强果冻感：衰减更慢，振荡更多（2.5个周期）
            float decay = (float) Math.exp(-4 * t);
            float oscillation = (float) Math.sin(t * Math.PI * 5);
            float offset = 28f * decay * oscillation * direction;
            view.setTranslationX(offset);
            view.setAlpha(1f);
        });
        mBounceAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (mBounceAnim != animation) return;
                mBounceAnim = null;
                view.setTranslationX(0f);
                view.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        mBounceAnim.start();
    }

    private void showCustomHeadsUp(StatusBarNotification sbn) {
        XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp called");
        XposedBridge.log(TAG + "[DIAG] mContext=" + (mContext != null) + ", mWindowManager=" + (mWindowManager != null) + ", mHandler=" + (mHandler != null));
        if (mContext == null || mWindowManager == null) {
            XposedBridge.log(TAG + "[DIAG] ABORT: mContext or mWindowManager is null");
            return;
        }
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        if (isKeyguardLocked() || isStatusBarExpanded()) {
            XposedBridge.log(TAG + "[DIAG] ABORT: keyguard or panel expanded");
            return;
        }
        registerScreenReceiver();
        XposedBridge.log(TAG + "[DIAG] Screen receiver registered");

        final String key = sbn.getKey();
        final Notification notification = sbn.getNotification();
        final PendingIntent contentIntent = notification.contentIntent;

        mHandler.post(() -> {
            try {
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: inside Handler post");
                Bundle extras = notification.extras;
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: extras=" + (extras != null));
                String title = extras != null ? extras.getString(Notification.EXTRA_TITLE, "") : "";
                CharSequence text = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT, "") : "";
                CharSequence bigText = extras != null ? extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "") : "";
                String content = "";
                if (bigText != null && bigText.length() > 0) {
                    content = bigText.toString();
                } else if (text != null) {
                    content = text.toString();
                }
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: title=" + title + ", content=" + content);
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode() & 0x7FFFFFFF);

                synchronized (mOverlayLock) {
                    if (key != null && key.equals(mCurrentKey)) {
                        if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                            if (newHash.equals(mCurrentContentHash)) {
                                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: same content hash, skipping");
                                return;
                            }
                        } else {
                            // overlay 已消失但状态未清空，强制重置
                            XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: stale state detected, clearing");
                            mCurrentKey = null;
                            mCurrentContentHash = null;
                            mCurrentOverlay = null;
                        }
                    }
                    XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: removing old overlay");
                    removeOverlayImmediate();
                    mCurrentContentHash = newHash;
                }

                boolean isDark = isDarkMode();
                int glassBaseColor = isDark ? 0x18000000 : 0x1AFFFFFF;
                int edgeColor = isDark ? 0x40FFFFFF : 0x55FFFFFF;
                int topHighlightStart = isDark ? 0x35FFFFFF : 0x60FFFFFF;
                int bottomGlowEnd = isDark ? 0x12FFFFFF : 0x18FFFFFF;
                int textColorPrimary = isDark ? 0xFFFFFFFF : 0xFF000000;
                int textColorSecondary = isDark ? 0xFFCCCCCC : 0xFF333333;

                // === 根容器：FrameLayout ===
                FrameLayout root = new FrameLayout(mContext);
                root.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                root.setElevation(20f);

                // === 第1层：模糊背景 ImageView ===
                ImageView bgView = new ImageView(mContext);
                bgView.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(bgView);
                mBgImageView = bgView;

                // === 第2层：液态玻璃效果层（Shader 动态渲染）===
                LiquidGlassView glassOverlay = new LiquidGlassView(mContext, WIN_W, WIN_H, isDark);
                glassOverlay.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                root.addView(glassOverlay);
                mGlassView = glassOverlay;

                // === 第3层：内容容器（可移动）===
                LinearLayout contentContainer = new LinearLayout(mContext);
                contentContainer.setOrientation(LinearLayout.HORIZONTAL);
                contentContainer.setPadding(20, 14, 20, 14);
                contentContainer.setGravity(Gravity.CENTER_VERTICAL);
                contentContainer.setLayoutParams(new FrameLayout.LayoutParams(WIN_W, WIN_H));
                contentContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);

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
                                if (mGlassView != null) mGlassView.setTouchPoint(event.getX(), event.getY(), 1.0f);
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
                                            XposedBridge.log(TAG + "[DIAG] Direction locked: HORIZONTAL (angle=" + (int)angleDeg + "°)");
                                        } else {
                                            lockedVertical = true;
                                            XposedBridge.log(TAG + "[DIAG] Direction locked: VERTICAL (angle=" + (int)angleDeg + "°)");
                                        }
                                    }
                                } else {
                                    hasMoved = true;
                                }
                                mTouchMaxDx = Math.max(mTouchMaxDx, Math.abs(dx));
                                mTouchMaxDy = Math.max(mTouchMaxDy, Math.abs(dy));
                                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                v.setAlpha(Math.max(0.5f, 1f - dist / 300f));
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
                                if (mGlassView != null) mGlassView.setTouchPoint(event.getX(), event.getY(), Math.min(1.0f, dist / 80f));
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
                                if (mGlassView != null) mGlassView.clearTouchPoint();
                                return true;
                            case MotionEvent.ACTION_UP:
                                if (mGlassView != null) mGlassView.clearTouchPoint();
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
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: LayoutParams created, type=" + WINDOW_TYPE + ", x=" + WIN_X + ", y=" + WIN_Y);

                try {
                    XposedBridge.log(TAG + "[DIAG] Adding overlay view to WindowManager");
                    mWindowManager.addView(root, params);
                    XposedBridge.log(TAG + "[DIAG] addView SUCCESS");
                } catch (IllegalStateException e) {
                    XposedBridge.log(TAG + "[DIAG] addView failed - view already added, removing old first");
                    removeOverlayImmediate();
                    try {
                        mWindowManager.addView(root, params);
                        XposedBridge.log(TAG + "[DIAG] addView retry SUCCESS");
                    } catch (Throwable t2) {
                        XposedBridge.log(TAG + "[DIAG] addView retry failed: " + t2);
                        return;
                    }
                } catch (Throwable e) {
                    XposedBridge.log(TAG + "[DIAG] addView failed: " + e);
                    XposedBridge.log(TAG + "[DIAG] addView exception: " + android.util.Log.getStackTraceString(e));
                    return;
                }

                synchronized (mOverlayLock) {
                    mCurrentKey = key;
                    mCurrentOverlay = root;
                }
                XposedBridge.log(TAG + ": Shown: " + title);
                XposedBridge.log(TAG + "[DIAG] Overlay shown successfully, key=" + key);

                // 记录该应用的最后显示时间（应用级别冷却）
                mAppCooldownMap.put(sbn.getPackageName(), SystemClock.elapsedRealtime());

                // 首次截屏+模糊
                updateBackground();
                startBackgroundUpdate();

                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: starting enter animation");
                startEnterAnimation(contentContainer);
                mAutoDismissRunnable = () -> {
                    XposedBridge.log(TAG + "[DIAG] Auto-dismiss triggered");
                    dismissOverlayAnimated(1);
                };
                mHandler.postDelayed(mAutoDismissRunnable, AUTO_DISMISS_MS);
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: auto-dismiss scheduled in " + AUTO_DISMISS_MS + "ms");
            } catch (Throwable t) {
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp ERROR: " + t);
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp stack: " + android.util.Log.getStackTraceString(t));
            }
        });
    }

    private void performContentClick(PendingIntent contentIntent) {
        XposedBridge.log(TAG + "[DIAG] performContentClick called, intent=" + (contentIntent != null));
        if (contentIntent == null) return;
        try {
            Bundle opts = createLaunchOptions();
            if (opts != null) {
                XposedHelpers.callMethod(contentIntent, "send", mContext, 0, null, null, null, null, opts);
            } else {
                contentIntent.send(mContext, 0, null);
            }
        } catch (Throwable e) {
            XposedBridge.log(TAG + "[DIAG] performContentClick primary failed: " + e);
            try { contentIntent.send(mContext, 0, null); } catch (Throwable e2) {
                XposedBridge.log(TAG + "[DIAG] performContentClick fallback failed: " + e2);
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
        XposedBridge.log(TAG + "[DIAG] expandStatusBar called");
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
        XposedBridge.log(TAG + "[DIAG] dismissOverlayAnimated called, direction=" + exitDirection);
        if (mHandler == null) {
            XposedBridge.log(TAG + "[DIAG] dismissOverlayAnimated: mHandler null");
            return;
        }
        final Handler handler = mHandler;
        handler.post(() -> {
            XposedBridge.log(TAG + "[DIAG] dismissOverlayAnimated: inside handler");
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

    private Bitmap captureScreenBackground() {
        XposedBridge.log(TAG + "[DIAG] captureScreenBackground called");
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Bitmap screenshot = null;
            try {
                screenshot = (Bitmap) XposedHelpers.callStaticMethod(scClass, "screenshot");
                XposedBridge.log(TAG + "[DIAG] screenshot() success: " + (screenshot != null ? screenshot.getWidth() + "x" + screenshot.getHeight() : "null"));
            } catch (Throwable t1) {
                XposedBridge.log(TAG + "[DIAG] screenshot() failed: " + t1.getMessage());
                try {
                    screenshot = (Bitmap) XposedHelpers.callStaticMethod(scClass, "screenshot", WIN_W, WIN_H);
                    XposedBridge.log(TAG + "[DIAG] screenshot(w,h) success: " + (screenshot != null ? screenshot.getWidth() + "x" + screenshot.getHeight() : "null"));
                } catch (Throwable t2) {
                    XposedBridge.log(TAG + "[DIAG] screenshot(w,h) failed: " + t2.getMessage());
                    try {
                        Object rect = android.graphics.Rect.class.getConstructor(int.class, int.class, int.class, int.class)
                            .newInstance(WIN_X, WIN_Y, WIN_X + WIN_W, WIN_Y + WIN_H);
                        screenshot = (Bitmap) XposedHelpers.callStaticMethod(scClass, "screenshot", rect);
                        XposedBridge.log(TAG + "[DIAG] screenshot(rect) success: " + (screenshot != null ? screenshot.getWidth() + "x" + screenshot.getHeight() : "null"));
                    } catch (Throwable t3) {
                        XposedBridge.log(TAG + "[DIAG] screenshot(rect) failed: " + t3.getMessage());
                        return null;
                    }
                }
            }
            if (screenshot != null) {
                int x = WIN_X, y = WIN_Y, w = WIN_W, h = WIN_H;
                if (x + w > screenshot.getWidth()) w = screenshot.getWidth() - x;
                if (y + h > screenshot.getHeight()) h = screenshot.getHeight() - y;
                if (w > 0 && h > 0) {
                    Bitmap cropped = Bitmap.createBitmap(screenshot, x, y, w, h);
                    screenshot.recycle();
                    XposedBridge.log(TAG + "[DIAG] captureScreenBackground cropped: " + w + "x" + h);
                    return cropped;
                }
                return screenshot;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[DIAG] captureScreenBackground failed: " + t);
            XposedBridge.log(TAG + "[DIAG] Stack: " + android.util.Log.getStackTraceString(t));
        }
        return null;
    }

    private Bitmap fastBlur(Bitmap input) {
        XposedBridge.log(TAG + "[DIAG] fastBlur called, input=" + (input != null ? input.getWidth() + "x" + input.getHeight() : "null"));
        if (input == null) return null;
        android.renderscript.RenderScript rs = null;
        android.renderscript.ScriptIntrinsicBlur blur = null;
        android.renderscript.Allocation inputAlloc = null;
        android.renderscript.Allocation outputAlloc = null;
        Bitmap small = null;
        Bitmap blurredSmall = null;
        try {
            int w = input.getWidth(), h = input.getHeight();
            int smallW = Math.max(1, w / BLUR_SCALE_FACTOR);
            int smallH = Math.max(1, h / BLUR_SCALE_FACTOR);
            small = Bitmap.createScaledBitmap(input, smallW, smallH, false);
            rs = android.renderscript.RenderScript.create(mContext);
            inputAlloc = android.renderscript.Allocation.createFromBitmap(rs, small);
            outputAlloc = android.renderscript.Allocation.createTyped(rs, inputAlloc.getType());
            blur = android.renderscript.ScriptIntrinsicBlur.create(
                rs, android.renderscript.Element.U8_4(rs));
            blur.setRadius(BLUR_RADIUS);
            blur.setInput(inputAlloc);
            blur.forEach(outputAlloc);
            blurredSmall = Bitmap.createBitmap(smallW, smallH, small.getConfig());
            outputAlloc.copyTo(blurredSmall);
            Bitmap result = Bitmap.createScaledBitmap(blurredSmall, w, h, true);
            // 叠加磨砂噪点
            try {
                Canvas noiseCanvas = new Canvas(result);
                Paint noisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                noisePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
                noisePaint.setAlpha(18);
                java.util.Random rnd = new java.util.Random(42);
                for (int i = 0; i < 400; i++) {
                    float nx = rnd.nextFloat() * w;
                    float ny = rnd.nextFloat() * h;
                    float nr = 0.5f + rnd.nextFloat() * 1.5f;
                    int na = 8 + rnd.nextInt(20);
                    noisePaint.setColor(Color.argb(na, 255, 255, 255));
                    noiseCanvas.drawCircle(nx, ny, nr, noisePaint);
                }
                for (int i = 0; i < 200; i++) {
                    float nx = rnd.nextFloat() * w;
                    float ny = rnd.nextFloat() * h;
                    float nr = 0.3f + rnd.nextFloat() * 0.8f;
                    int na = 5 + rnd.nextInt(12);
                    noisePaint.setColor(Color.argb(na, 0, 0, 0));
                    noiseCanvas.drawCircle(nx, ny, nr, noisePaint);
                }
                noisePaint.setXfermode(null);
            } catch (Throwable t) {}
            return result;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[DIAG] fastBlur FAILED: " + t);
            XposedBridge.log(TAG + "[DIAG] fastBlur stack: " + android.util.Log.getStackTraceString(t));
            return input;
        } finally {
            if (blur != null) blur.destroy();
            if (inputAlloc != null) inputAlloc.destroy();
            if (outputAlloc != null) outputAlloc.destroy();
            if (rs != null) rs.destroy();
            if (small != null) small.recycle();
            if (blurredSmall != null) blurredSmall.recycle();
        }
    }

    private void updateBackground() {
        XposedBridge.log(TAG + "[DIAG] updateBackground called");
        if (mBgImageView == null || mCurrentOverlay == null || mCurrentOverlay.getParent() == null) {
            XposedBridge.log(TAG + "[DIAG] updateBackground skipped: bgView=" + (mBgImageView != null) + ", overlay=" + (mCurrentOverlay != null));
            return;
        }
        if (mContentView != null && (mContentView.getTranslationX() != 0f || mContentView.getTranslationY() != 0f)) {
            XposedBridge.log(TAG + "[DIAG] updateBackground skipped: content moving");
            return;
        }
        Bitmap screen = captureScreenBackground();
        if (screen == null) {
            XposedBridge.log(TAG + "[DIAG] updateBackground: captureScreenBackground returned null");
            return;
        }
        XposedBridge.log(TAG + "[DIAG] updateBackground: screen captured " + screen.getWidth() + "x" + screen.getHeight());
        Bitmap blurred = fastBlur(screen);
        screen.recycle();
        if (blurred == null) {
            XposedBridge.log(TAG + "[DIAG] updateBackground: fastBlur returned null");
            return;
        }
        XposedBridge.log(TAG + "[DIAG] updateBackground: blur done " + blurred.getWidth() + "x" + blurred.getHeight());
        Bitmap oldBmp = mBlurredBgBitmap;
        mBlurredBgBitmap = blurred;
        mHandler.post(() -> {
            try {
                if (mBgImageView != null) {
                    mBgImageView.setScaleX(1.03f);
                    mBgImageView.setScaleY(1.03f);
                    mBgImageView.setImageBitmap(blurred);
                }
                if (oldBmp != null && !oldBmp.isRecycled()) oldBmp.recycle();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": updateBackground post error: " + t);
            }
        });
    }

    private void startBackgroundUpdate() {
        XposedBridge.log(TAG + "[DIAG] startBackgroundUpdate called");
        stopBackgroundUpdate();
        mBgUpdateRunnable = () -> {
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) return;
            updateBackground();
            mHandler.postDelayed(mBgUpdateRunnable, BG_UPDATE_INTERVAL_MS);
        };
        mHandler.postDelayed(mBgUpdateRunnable, BG_UPDATE_INTERVAL_MS);
    }

    private void stopBackgroundUpdate() {
        XposedBridge.log(TAG + "[DIAG] stopBackgroundUpdate called");
        if (mBgUpdateRunnable != null) {
            mHandler.removeCallbacks(mBgUpdateRunnable);
            mBgUpdateRunnable = null;
        }
    }

    private void removeOverlayImmediate() {
        XposedBridge.log(TAG + "[DIAG] removeOverlayImmediate called, key=" + mCurrentKey);
        cancelAllAnimations();
        stopBackgroundUpdate();
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
                            XposedBridge.log(TAG + "[DIAG] removeView success");
                        } else {
                            XposedBridge.log(TAG + "[DIAG] removeView skipped - no parent");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "[DIAG] removeView failed: " + t);
                        try { wm.removeViewImmediate(overlayToRemove); } catch (Throwable ignored) {}
                    }
                });
            }
        } else {
            XposedBridge.log(TAG + "[DIAG] removeOverlayImmediate: no overlay to remove");
        }
        Bitmap oldBitmap = mBlurredBgBitmap;
        mBlurredBgBitmap = null;
        if (oldBitmap != null) {
            try { if (!oldBitmap.isRecycled()) oldBitmap.recycle(); } catch (Throwable ignored) {}
        }
        mBgImageView = null;
        mContentView = null;
        mIconView = null;
        mTitleView = null;
        mTextView = null;
        mEnterProgress = 0f;
        if (mGlassView != null) {
            mGlassView.stopAnimations();
            mGlassView = null;
        }
    }

    // ============================================================
    // 增强版液态玻璃视图 —— 光影动效重设计
    // 替换 MainHook.java 中 1541-1802 行的 LiquidGlassView 类
    // ============================================================
    private class LiquidGlassView extends View {
        // ---- 基础 Paint（构造时创建，onDraw 中复用） ----
        private final Paint mBasePaint;           // 体积渐变底色
        private final Paint mNoisePaint;          // 噪点纹理（增强版）
        private final Paint mReflectionPaint;     // 顶部反射光
        private final Paint mShadowPaint;         // 底部阴影
        private final Paint mInnerGlowPaint;      // 角落内发光
        private final Paint mDentPaint;           // 触摸压痕
        private final Paint mDentRimPaint;        // 触摸压痕边缘

        // ---- 新增：光影动效 Paint ----
        private final Paint mRimLightPaint;       // 菲涅尔边缘流光（SweepGradient）
        private final Paint mSpecularPaint;       // 镜面扫光（LinearGradient）
        private final Paint mCausticPaint;        // 环境焦散（增强版）
        private final Paint mOuterShadowPaint;    // 外部阴影
        private final Paint mOuterGlowPaint;      // 外部光晕（呼吸脉动）
        private final Paint mVolumeLightPaint;    // 体积光（中心→边缘）

        // ---- Shader & Matrix（复用，避免 GC） ----
        private final Bitmap mNoiseBitmap;
        private final BitmapShader mNoiseShader;
        private final Matrix mNoiseMatrix;
        private final Matrix mRimMatrix;          // 边缘流光旋转矩阵
        private final Matrix mSpecularMatrix;     // 扫光平移矩阵
        private final Matrix mCausticMatrix;      // 焦散变换矩阵
        private SweepGradient mRimGradient;       // 边缘流光渐变
        private LinearGradient mSpecularGradient; // 镜面扫光渐变
        private LinearGradient mCausticGradient;  // 焦散渐变

        // ---- 动画器 ----
        private ValueAnimator mFlowAnimator;      // 噪点流动
        private ValueAnimator mCausticAnimator;   // 焦散动画
        private ValueAnimator mBreathAnimator;    // 呼吸透明度
        private ValueAnimator mInnerGlowAnimator; // 内发光脉动
        private ValueAnimator mRimLightAnimator;  // 【新增】边缘流光旋转
        private ValueAnimator mSpecularAnimator;  // 【新增】镜面扫光平移
        private ValueAnimator mGlowPulseAnimator; // 【新增】外发光呼吸
        private ValueAnimator mCausticFlowAnimator; // 【新增】焦散流动

        // ---- 动画状态值 ----
        private float mFlowOffset = 0f;
        private float mCausticPhase = 0f;
        private float mBreathAlpha = 0.95f;
        private float mInnerGlowIntensity = 0.3f;
        private float mRimAngle = 0f;             // 【新增】边缘流光角度 0-360
        private float mSpecularPos = 0f;          // 【新增】扫光位置 0-1
        private float mGlowIntensity = 0.5f;      // 【新增】外发光强度
        private float mCausticFlowOffset = 0f;    // 【新增】焦散流动偏移

        // ---- 尺寸 & 主题 ----
        private final int mViewWidth;
        private final int mViewHeight;
        private float mCornerRadius;
        private final boolean mIsDark;
        private RectF mDrawRect;
        private final android.graphics.Path mClipPath;
        private final android.graphics.Path mRimPath; // 边缘流光路径

        // ---- 触摸状态 ----
        private float mTouchX = -1f;
        private float mTouchY = -1f;
        private float mTouchPressure = 0f;

        public LiquidGlassView(Context context, int w, int h, boolean isDark) {
            super(context);
            mViewWidth = w;
            mViewHeight = h;
            mCornerRadius = 28f;
            mIsDark = isDark;
            mDrawRect = new RectF(0, 0, w, h);
            mClipPath = new android.graphics.Path();
            mRimPath = new android.graphics.Path();

            // ========== Layer 3: 基础体积渐变 ==========
            int centerColor = isDark ? 0x28FFFFFF : 0x30FFFFFF;
            int midColor = isDark ? 0x14FFFFFF : 0x1AFFFFFF;
            int edgeColor = isDark ? 0x08FFFFFF : 0x0CFFFFFF;
            RadialGradient volumeGrad = new RadialGradient(
                w * 0.5f, h * 0.45f, Math.max(w, h) * 0.85f,
                new int[]{centerColor, midColor, edgeColor, 0x00000000},
                new float[]{0f, 0.3f, 0.65f, 1f},
                Shader.TileMode.CLAMP);
            mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBasePaint.setShader(volumeGrad);

            // ========== Layer 4: 菲涅尔边缘流光（SweepGradient 旋转）==========
            // 创建沿边缘旋转的白色高光
            int rimWhite = isDark ? 0x88FFFFFF : 0xAAFFFFFF;
            int rimFade = isDark ? 0x10FFFFFF : 0x18FFFFFF;
            mRimGradient = new SweepGradient(
                w * 0.5f, h * 0.5f,
                new int[]{rimFade, rimWhite, rimFade, rimWhite, rimFade},
                new float[]{0f, 0.15f, 0.5f, 0.85f, 1f});
            mRimMatrix = new Matrix();
            mRimLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRimLightPaint.setShader(mRimGradient);
            mRimLightPaint.setStyle(Paint.Style.STROKE);
            mRimLightPaint.setStrokeWidth(2.0f);
            mRimLightPaint.setMaskFilter(new android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL));

            // ========== Layer 5: 镜面扫光（LinearGradient 平移）==========
            // 斜向光泽带，模拟光源扫过
            int specWhite = isDark ? 0x70FFFFFF : 0x90FFFFFF;
            int specFade = 0x00FFFFFF;
            mSpecularGradient = new LinearGradient(
                0, 0, w * 0.6f, h * 0.4f,
                new int[]{specFade, specWhite, specFade},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
            mSpecularMatrix = new Matrix();
            mSpecularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSpecularPaint.setShader(mSpecularGradient);
            mSpecularPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            // ========== Layer 7: 噪点纹理（增强 alpha）==========
            mNoiseBitmap = createNoiseBitmap(256, 256);
            mNoiseShader = new BitmapShader(mNoiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mNoiseMatrix = new Matrix();
            mNoisePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mNoisePaint.setShader(mNoiseShader);
            mNoisePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            // ========== Layer 6: 顶部反射光（增强）==========
            int reflStart = isDark ? 0x55FFFFFF : 0x75FFFFFF;
            int reflMid = isDark ? 0x28FFFFFF : 0x38FFFFFF;
            LinearGradient reflGrad = new LinearGradient(
                0, 0, 0, h * 0.5f,
                new int[]{reflStart, reflMid, 0x00FFFFFF},
                new float[]{0f, 0.15f, 1f},
                Shader.TileMode.CLAMP);
            mReflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mReflectionPaint.setShader(reflGrad);
            mReflectionPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            // ========== Layer 8: 环境焦散（增强版，流动路径）==========
            mCausticGradient = new LinearGradient(
                0, 0, w, 0,
                new int[]{0x00FFFFFF, 0x40AADDFF, 0x00FFFFFF, 0x40FFCC88, 0x00FFFFFF},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                Shader.TileMode.REPEAT);
            mCausticMatrix = new Matrix();
            mCausticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mCausticPaint.setShader(mCausticGradient);
            mCausticPaint.setStyle(Paint.Style.STROKE);
            mCausticPaint.setStrokeWidth(3f);
            mCausticPaint.setMaskFilter(new android.graphics.BlurMaskFilter(6f, android.graphics.BlurMaskFilter.Blur.NORMAL));

            // ========== Layer 9: 内发光（增强）==========
            mInnerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mInnerGlowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.NORMAL));

            // ========== Layer 10: 底部阴影 ==========
            int shadowEnd = isDark ? 0x35000000 : 0x20FFFFFF;
            LinearGradient shadowGrad = new LinearGradient(
                0, h * 0.45f, 0, h,
                0x00000000, shadowEnd, Shader.TileMode.CLAMP);
            mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mShadowPaint.setShader(shadowGrad);

            // ========== Layer 0: 外部阴影（增强）==========
            mOuterShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mOuterShadowPaint.setColor(mIsDark ? 0x60000000 : 0x38FFFFFF);
            mOuterShadowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.NORMAL));

            // ========== Layer 1: 外部光晕（呼吸脉动）==========
            mOuterGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mOuterGlowPaint.setColor(mIsDark ? 0x18AADDFF : 0x12E8F4FF);
            mOuterGlowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL));

            // ========== 体积光（中心→边缘呼吸）==========
            int volCenter = isDark ? 0x18FFFFFF : 0x20FFFFFF;
            int volEdge = 0x00000000;
            RadialGradient volGrad = new RadialGradient(
                w * 0.5f, h * 0.5f, Math.max(w, h) * 0.5f,
                volCenter, volEdge, Shader.TileMode.CLAMP);
            mVolumeLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mVolumeLightPaint.setShader(volGrad);
            mVolumeLightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            // ========== 触摸压痕 ==========
            mDentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mDentRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mDentRimPaint.setStyle(Paint.Style.STROKE);

            startAnimations();
        }

        public void setCornerRadius(float radius) {
            mCornerRadius = radius;
            invalidate();
        }

        private Bitmap createNoiseBitmap(int w, int h) {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            java.util.Random r = new java.util.Random(12345);
            // 大光斑（模拟玻璃表面的大反射）
            for (int i = 0; i < 25; i++) {
                float x = r.nextFloat() * w;
                float y = r.nextFloat() * h * 0.35f;
                float radius = 10f + r.nextFloat() * 28f;
                int a = 18 + r.nextInt(35);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawCircle(x, y, radius, p);
            }
            // 中光斑
            for (int i = 0; i < 80; i++) {
                float x = r.nextFloat() * w;
                float y = r.nextFloat() * h;
                float radius = 2f + r.nextFloat() * 5f;
                int a = 20 + r.nextInt(50);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawCircle(x, y, radius, p);
            }
            // 水平光带（模拟玻璃表面的拉丝纹理）
            for (int i = 0; i < 8; i++) {
                float y = r.nextFloat() * h * 0.25f;
                float bandH = 1.5f + r.nextFloat() * 4f;
                int a = 10 + r.nextInt(20);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawRect(0, y, w, y + bandH, p);
            }
            // 斜向光带（增强动态感）
            for (int i = 0; i < 5; i++) {
                float startX = r.nextFloat() * w;
                float startY = r.nextFloat() * h * 0.3f;
                float len = 40f + r.nextFloat() * 100f;
                int a = 8 + r.nextInt(15);
                p.setColor(Color.argb(a, 255, 255, 255));
                p.setStrokeWidth(1f + r.nextFloat() * 2f);
                c.drawLine(startX, startY, startX + len * 0.7f, startY + len * 0.3f, p);
            }
            return bmp;
        }

        private void startAnimations() {
            // ---- 噪点流动（3000ms） ----
            mFlowAnimator = ValueAnimator.ofFloat(0f, 1f);
            mFlowAnimator.setDuration(3000);
            mFlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mFlowAnimator.setInterpolator(new LinearInterpolator());
            mFlowAnimator.addUpdateListener(anim -> {
                mFlowOffset = (float) anim.getAnimatedValue();
                mNoiseMatrix.setTranslate(mFlowOffset * 512, mFlowOffset * 64);
                mNoiseShader.setLocalMatrix(mNoiseMatrix);
                invalidate();
            });
            mFlowAnimator.start();

            // ---- 焦散颜色变化（4000ms） ----
            mCausticAnimator = ValueAnimator.ofFloat(0f, 1f);
            mCausticAnimator.setDuration(4000);
            mCausticAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mCausticAnimator.setInterpolator(new LinearInterpolator());
            mCausticAnimator.addUpdateListener(anim -> {
                mCausticPhase = (float) anim.getAnimatedValue();
                float[] hsv = new float[]{
                    190f + (float)(Math.sin(mCausticPhase * Math.PI * 2) * 30),
                    0.3f, 0.95f
                };
                int causticColor = Color.HSVToColor(hsv);
                // 更新焦散渐变的中间颜色
                mCausticGradient = new LinearGradient(
                    0, 0, mViewWidth, 0,
                    new int[]{0x00FFFFFF, causticColor, 0x00FFFFFF, causticColor, 0x00FFFFFF},
                    new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                    Shader.TileMode.REPEAT);
                mCausticPaint.setShader(mCausticGradient);
                invalidate();
            });
            mCausticAnimator.start();

            // ---- 焦散流动（2500ms） ----
            mCausticFlowAnimator = ValueAnimator.ofFloat(0f, 1f);
            mCausticFlowAnimator.setDuration(2500);
            mCausticFlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mCausticFlowAnimator.setInterpolator(new LinearInterpolator());
            mCausticFlowAnimator.addUpdateListener(anim -> {
                mCausticFlowOffset = (float) anim.getAnimatedValue();
                mCausticMatrix.setTranslate(mCausticFlowOffset * mViewWidth * 2, 0);
                mCausticGradient.setLocalMatrix(mCausticMatrix);
                invalidate();
            });
            mCausticFlowAnimator.start();

            // ---- 呼吸透明度（3000ms） ----
            mBreathAnimator = ValueAnimator.ofFloat(0.88f, 0.98f);
            mBreathAnimator.setDuration(3000);
            mBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mBreathAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mBreathAnimator.addUpdateListener(anim -> {
                mBreathAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            mBreathAnimator.start();

            // ---- 内发光脉动（2500ms） ----
            mInnerGlowAnimator = ValueAnimator.ofFloat(0.25f, 0.6f);
            mInnerGlowAnimator.setDuration(2500);
            mInnerGlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mInnerGlowAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mInnerGlowAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mInnerGlowAnimator.addUpdateListener(anim -> {
                mInnerGlowIntensity = (float) anim.getAnimatedValue();
                invalidate();
            });
            mInnerGlowAnimator.start();

            // ---- 【新增】边缘流光旋转（2000ms） ----
            mRimLightAnimator = ValueAnimator.ofFloat(0f, 360f);
            mRimLightAnimator.setDuration(2000);
            mRimLightAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mRimLightAnimator.setInterpolator(new LinearInterpolator());
            mRimLightAnimator.addUpdateListener(anim -> {
                mRimAngle = (float) anim.getAnimatedValue();
                mRimMatrix.setRotate(mRimAngle, mViewWidth * 0.5f, mViewHeight * 0.5f);
                mRimGradient.setLocalMatrix(mRimMatrix);
                invalidate();
            });
            mRimLightAnimator.start();

            // ---- 【新增】镜面扫光平移（3500ms） ----
            mSpecularAnimator = ValueAnimator.ofFloat(-1.5f, 2.5f);
            mSpecularAnimator.setDuration(3500);
            mSpecularAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mSpecularAnimator.setInterpolator(new LinearInterpolator());
            mSpecularAnimator.addUpdateListener(anim -> {
                mSpecularPos = (float) anim.getAnimatedValue();
                // 斜向扫光：从左上到右下
                float offsetX = mSpecularPos * mViewWidth * 0.8f;
                float offsetY = mSpecularPos * mViewHeight * 0.5f;
                mSpecularMatrix.setTranslate(offsetX, offsetY);
                mSpecularGradient.setLocalMatrix(mSpecularMatrix);
                invalidate();
            });
            mSpecularAnimator.start();

            // ---- 【新增】外发光呼吸（2000ms） ----
            mGlowPulseAnimator = ValueAnimator.ofFloat(0.12f, 0.28f);
            mGlowPulseAnimator.setDuration(2000);
            mGlowPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mGlowPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mGlowPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mGlowPulseAnimator.addUpdateListener(anim -> {
                mGlowIntensity = (float) anim.getAnimatedValue();
                invalidate();
            });
            mGlowPulseAnimator.start();
        }

        public void setTouchPoint(float x, float y, float pressure) {
            mTouchX = x; mTouchY = y; mTouchPressure = pressure;
            invalidate();
        }

        public void clearTouchPoint() {
            mTouchX = -1f; mTouchY = -1f; mTouchPressure = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // ========== Layer 0: 外部阴影（增强） ==========
            RectF shadowRect = new RectF(-8, 5, mViewWidth + 8, mViewHeight + 18);
            canvas.drawRoundRect(shadowRect, mCornerRadius, mCornerRadius, mOuterShadowPaint);

            // ========== Layer 1: 外部光晕（呼吸脉动） ==========
            mOuterGlowPaint.setAlpha((int)(mGlowIntensity * 255));
            RectF glowRect = new RectF(-5, 3, mViewWidth + 5, mViewHeight + 10);
            canvas.drawRoundRect(glowRect, mCornerRadius, mCornerRadius, mOuterGlowPaint);

            // ========== Clip 区域 ==========
            mClipPath.reset();
            mClipPath.addRoundRect(mDrawRect, mCornerRadius, mCornerRadius, android.graphics.Path.Direction.CW);
            canvas.clipPath(mClipPath);

            // ========== Layer 3: 基础体积渐变 ==========
            canvas.drawRect(mDrawRect, mBasePaint);

            // ========== Layer 4: 菲涅尔边缘流光（SweepGradient 旋转） ==========
            // 在内容层之上绘制旋转的边缘高光
            mRimLightPaint.setAlpha((int)(40 * mBreathAlpha));
            // 创建比 drawRect 稍小的内边距路径，让流光在边缘内侧
            float rimInset = 2f;
            RectF rimRect = new RectF(rimInset, rimInset, mViewWidth - rimInset, mViewHeight - rimInset);
            mRimPath.reset();
            mRimPath.addRoundRect(rimRect, mCornerRadius - rimInset, mCornerRadius - rimInset, android.graphics.Path.Direction.CW);
            canvas.drawPath(mRimPath, mRimLightPaint);

            // ========== Layer 5: 镜面扫光（LinearGradient 平移） ==========
            // 模拟一道光源斜向扫过玻璃表面
            mSpecularPaint.setAlpha((int)(45 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mSpecularPaint);

            // ========== Layer 6: 顶部反射光（增强） ==========
            mReflectionPaint.setAlpha((int)(85 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mReflectionPaint);

            // ========== Layer 7: 噪点纹理（增强 alpha） ==========
            // 从原来的 35 提升到 55，更明显
            mNoisePaint.setAlpha((int)(55 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mNoisePaint);

            // ========== Layer 8: 环境焦散（增强版，流动） ==========
            mCausticPaint.setAlpha((int)(65 * mBreathAlpha));
            float causticInset = 3f;
            RectF causticRect = new RectF(causticInset, causticInset, mViewWidth - causticInset, mViewHeight - causticInset);
            canvas.drawRoundRect(causticRect, mCornerRadius - causticInset, mCornerRadius - causticInset, mCausticPaint);

            // ========== Layer 9: 内发光（增强） ==========
            int glowColor = mIsDark ?
                Color.argb((int)(35 * mInnerGlowIntensity), 180, 210, 255) :
                Color.argb((int)(28 * mInnerGlowIntensity), 200, 230, 255);
            mInnerGlowPaint.setColor(glowColor);
            float glowR = mCornerRadius * 2.2f;
            // 四个角都有内发光
            canvas.drawCircle(mCornerRadius, mCornerRadius, glowR, mInnerGlowPaint);
            canvas.drawCircle(mViewWidth - mCornerRadius, mCornerRadius, glowR, mInnerGlowPaint);
            canvas.drawCircle(mCornerRadius, mViewHeight - mCornerRadius, glowR * 0.6f, mInnerGlowPaint);
            canvas.drawCircle(mViewWidth - mCornerRadius, mViewHeight - mCornerRadius, glowR * 0.6f, mInnerGlowPaint);

            // ========== 体积光呼吸（中心→边缘） ==========
            mVolumeLightPaint.setAlpha((int)(30 * mGlowIntensity));
            canvas.drawRect(mDrawRect, mVolumeLightPaint);

            // ========== Layer 10: 底部阴影 ==========
            canvas.drawRect(mDrawRect, mShadowPaint);

            // ========== Layer 11: 触摸压痕 ==========
            if (mTouchX >= 0 && mTouchPressure > 0.01f) {
                float dentR = 40f * mTouchPressure;
                mDentPaint.setColor(mIsDark ? 0x30000000 : 0x20FFFFFF);
                canvas.drawCircle(mTouchX, mTouchY, dentR, mDentPaint);
                mDentRimPaint.setColor(Color.argb((int)(60 * mTouchPressure), 255, 255, 255));
                mDentRimPaint.setStrokeWidth(2.5f * mTouchPressure);
                canvas.drawCircle(mTouchX, mTouchY, dentR, mDentRimPaint);

                // 触摸点局部高光增强
                Paint touchGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
                touchGlow.setColor(Color.argb((int)(40 * mTouchPressure), 255, 255, 255));
                touchGlow.setMaskFilter(new android.graphics.BlurMaskFilter(20f * mTouchPressure, android.graphics.BlurMaskFilter.Blur.NORMAL));
                canvas.drawCircle(mTouchX, mTouchY, dentR * 2f, touchGlow);
            }
        }

        public void stopAnimations() {
            if (mFlowAnimator != null) { mFlowAnimator.cancel(); mFlowAnimator = null; }
            if (mCausticAnimator != null) { mCausticAnimator.cancel(); mCausticAnimator = null; }
            if (mBreathAnimator != null) { mBreathAnimator.cancel(); mBreathAnimator = null; }
            if (mInnerGlowAnimator != null) { mInnerGlowAnimator.cancel(); mInnerGlowAnimator = null; }
            if (mRimLightAnimator != null) { mRimLightAnimator.cancel(); mRimLightAnimator = null; }
            if (mSpecularAnimator != null) { mSpecularAnimator.cancel(); mSpecularAnimator = null; }
            if (mGlowPulseAnimator != null) { mGlowPulseAnimator.cancel(); mGlowPulseAnimator = null; }
            if (mCausticFlowAnimator != null) { mCausticFlowAnimator.cancel(); mCausticFlowAnimator = null; }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimations();
            if (mNoiseBitmap != null && !mNoiseBitmap.isRecycled()) mNoiseBitmap.recycle();
        }
    }

}
