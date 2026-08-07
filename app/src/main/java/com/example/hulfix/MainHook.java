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
import android.view.ViewOutlineProvider;
import android.graphics.Outline;
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
        // 初始状态：一个极小的凝聚点，像液体表面张力下的水珠
        view.setAlpha(0f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(0.05f);
        view.setScaleY(0.05f);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // 内容层初始隐藏
        if (mIconView != null) { mIconView.setAlpha(0f); mIconView.setScaleX(0.4f); mIconView.setScaleY(0.4f); }
        if (mTitleView != null) { mTitleView.setAlpha(0f); mTitleView.setTranslationY(12f); }
        if (mTextView != null) { mTextView.setAlpha(0f); mTextView.setTranslationY(8f); }
        // 背景模糊初始隐藏
        if (mBgImageView != null) mBgImageView.setAlpha(0f);
        // 圆角初始为完全圆形（水滴状）
        if (mGlassView != null) mGlassView.setCornerRadius(WIN_H * 0.5f);

        mEnterAnim = ValueAnimator.ofFloat(0f, 1f);
        mEnterAnim.setDuration(520);
        mEnterAnim.setInterpolator(null); // 自定义分段曲线，不用统一插值器
        mEnterAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            mEnterProgress = t;

            // === Liquid Glass 弹出动画：凝聚 → 爆开 → 表面张力回弹 → 平静 ===
            float containerAlpha, containerScale, cornerRadius, popGlow;

            if (t < 0.15f) {
                // 阶段1：凝聚（0~78ms）—— 液体聚集成水珠
                float p = t / 0.15f;
                float ease = p * p * (3f - 2f * p); // smoothstep
                containerAlpha = 0.0f + 0.35f * ease;
                containerScale = 0.05f + 0.55f * ease; // 0.05 → 0.60
                cornerRadius = WIN_H * 0.5f; // 保持完全圆形
                popGlow = 0f;
            } else if (t < 0.45f) {
                // 阶段2：爆开膨胀（78~234ms）—— 水滴落在玻璃上扩散
                float p = (t - 0.15f) / 0.30f;
                // spring-like overshoot: 快速冲过目标再回弹
                float spring = (float)(1.0 - Math.exp(-5.0 * p) * Math.cos(8.0 * p));
                containerAlpha = 0.35f + 0.60f * spring;
                containerScale = 0.60f + 0.52f * spring; // 0.60 → 1.12 overshoot
                cornerRadius = WIN_H * 0.5f * (1f - p * 0.85f); // 圆形逐渐变平
                popGlow = (float)Math.sin(p * Math.PI) * 0.9f; // 光晕在膨胀时亮起
            } else if (t < 0.75f) {
                // 阶段3：表面张力回弹（234~390ms）—— 液体表面张力让玻璃稳定
                float p = (t - 0.45f) / 0.30f;
                float ease = p * p * (3f - 2f * p);
                // 从 overshoot 回弹：1.12 → 0.94 → 1.04 → 1.0
                float decay = (float)Math.exp(-3.0 * p);
                float oscillation = (float)Math.cos(6.0 * p);
                containerAlpha = 0.95f + 0.05f * ease;
                containerScale = 1.04f + 0.08f * decay * oscillation - 0.04f * ease;
                cornerRadius = 28f + (WIN_H * 0.5f - 28f) * 0.15f * (1f - ease); // 接近最终圆角
                popGlow = 0.9f * (1f - ease); // 光晕逐渐消散
            } else {
                // 阶段4：平静定型（390~520ms）—— 完全稳定
                float p = (t - 0.75f) / 0.25f;
                float ease = p * p * (3f - 2f * p);
                containerAlpha = 1f;
                containerScale = 1.0f + 0.01f * (1f - ease); // 从 1.01 平滑到 1.0
                cornerRadius = 28f;
                popGlow = 0f;
            }

            view.setAlpha(containerAlpha);
            view.setScaleX(containerScale);
            view.setScaleY(containerScale);
            if (mGlassView != null) {
                mGlassView.setCornerRadius(cornerRadius);
                mGlassView.setPopGlow(popGlow);
            }

            // === 背景模糊：从中心向外扩散 ===
            if (mBgImageView != null) {
                float bgAlpha;
                if (t < 0.10f) {
                    bgAlpha = t * 8f; // 快速启动
                } else if (t < 0.40f) {
                    float p = (t - 0.10f) / 0.30f;
                    bgAlpha = 0.8f + 0.2f * (p * p * (3f - 2f * p));
                } else {
                    bgAlpha = 1f;
                }
                mBgImageView.setAlpha(Math.min(1f, bgAlpha));
            }

            // === 内容分层浮现：像从玻璃内部浮出 ===
            // 图标：t=0.18 开始，带弹性浮出
            if (mIconView != null && t > 0.18f) {
                float ip = Math.min(1f, (t - 0.18f) / 0.30f);
                float iease = ip * ip * (3f - 2f * ip);
                float ispring = (float)(1.0 - Math.exp(-4.0 * ip) * Math.cos(6.0 * ip));
                mIconView.setAlpha(iease);
                float iconScale = 0.4f + 0.6f * ispring;
                mIconView.setScaleX(iconScale);
                mIconView.setScaleY(iconScale);
            }
            // 标题：t=0.32 开始，从下方滑入 + 淡入
            if (mTitleView != null && t > 0.32f) {
                float tp = Math.min(1f, (t - 0.32f) / 0.28f);
                float tease = tp * tp * (3f - 2f * tp);
                mTitleView.setAlpha(tease);
                mTitleView.setTranslationY(12f * (1f - tease));
            }
            // 文字：t=0.48 开始，淡入 + 轻微上浮
            if (mTextView != null && t > 0.48f) {
                float cp = Math.min(1f, (t - 0.48f) / 0.28f);
                float cease = cp * cp * (3f - 2f * cp);
                mTextView.setAlpha(cease);
                mTextView.setTranslationY(8f * (1f - cease));
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
                view.setTranslationY(0f);
                mEnterProgress = 1f;
                if (mBgImageView != null) mBgImageView.setAlpha(1f);
                if (mIconView != null) { mIconView.setAlpha(1f); mIconView.setScaleX(1f); mIconView.setScaleY(1f); }
                if (mTitleView != null) { mTitleView.setAlpha(1f); mTitleView.setTranslationY(0f); }
                if (mTextView != null) { mTextView.setAlpha(1f); mTextView.setTranslationY(0f); }
                if (mGlassView != null) {
                    mGlassView.setCornerRadius(28f);
                    mGlassView.setPopGlow(0f);
                }
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
        mBounceAnim.setDuration(380);
        mBounceAnim.setInterpolator(null);
        mBounceAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            // 回弹：1.5个周期，中等振幅
            float decay = (float) Math.exp(-5 * t);
            float oscillation = (float) Math.sin(t * Math.PI * 4);
            float offset = 20f * decay * oscillation * direction;  // 12f → 20f
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

        Bitmap oldBmp = mBlurredBgBitmap;
        mBlurredBgBitmap = screen;

        // Android 12+ (API 31): 使用硬件加速 RenderEffect 模糊，效果更现代、更流畅
        if (Build.VERSION.SDK_INT >= 31) {
            mHandler.post(() -> {
                try {
                    if (mBgImageView != null) {
                        mBgImageView.setImageBitmap(screen);
                        mBgImageView.setScaleX(1.03f);
                        mBgImageView.setScaleY(1.03f);
                        // 硬件加速高斯模糊，比 RenderScript 更现代
                        // radius 单位是像素，*2.5 是为了匹配原有 RenderScript 的视觉效果
                        mBgImageView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                            BLUR_RADIUS * 2.5f, BLUR_RADIUS * 2.5f,
                            android.graphics.Shader.TileMode.CLAMP));
                        XposedBridge.log(TAG + "[DIAG] updateBackground: RenderEffect blur applied (API 31+)");
                    }
                    if (oldBmp != null && !oldBmp.isRecycled()) oldBmp.recycle();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "[DIAG] RenderEffect blur failed, fallback to RenderScript: " + t);
                    if (mBgImageView != null) {
                        Bitmap blurred = fastBlur(screen);
                        if (blurred != null) {
                            mBgImageView.setRenderEffect(null);
                            mBgImageView.setImageBitmap(blurred);
                        }
                    }
                }
            });
            return;
        }

        // Android 8~11: 继续使用 RenderScript
        Bitmap blurred = fastBlur(screen);
        screen.recycle();
        if (blurred == null) {
            XposedBridge.log(TAG + "[DIAG] updateBackground: fastBlur returned null");
            return;
        }
        mBlurredBgBitmap = blurred;
        XposedBridge.log(TAG + "[DIAG] updateBackground: blur done " + blurred.getWidth() + "x" + blurred.getHeight());
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
        // 清除 RenderEffect（Android 12+）
        if (mBgImageView != null) {
            try { mBgImageView.setRenderEffect(null); } catch (Throwable ignored) {}
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
    // iOS 26 Liquid Glass 风格液态玻璃视图
    // 参考 Apple WWDC 2025 Liquid Glass 设计语言
    // 特征：多层厚度、环境光溢色、镜面高光、边缘倒角、内部反射
    // ============================================================
    private class LiquidGlassView extends View {

        // === 多层玻璃绘制工具 ===
        private final Paint mBackSurfacePaint;      // 后表面（更深、更暗）
        private final Paint mFrontSurfacePaint;       // 前表面（主要可见层）
        private final Paint mVolumePaint;             // 玻璃厚度/体积感
        private final Paint mSpecularPaint;           // 镜面高光（顶部明亮反射）
        private final Paint mInnerReflectionPaint;    // 内部反射
        private final Paint mAmbientSpillPaint;       // 环境光溢色（边缘）
        private final Paint mBevelHighlightPaint;     // 边缘倒角高光
        private final Paint mBevelShadowPaint;        // 边缘倒角阴影
        private final Paint mMicroNoisePaint;         // 微观纹理
        private final Paint mCausticPaint;            // 焦散光效（玻璃聚焦效果）

        // === 动态纹理 ===
        private final Bitmap mMicroNoiseBitmap;
        private final BitmapShader mMicroNoiseShader;
        private final Matrix mNoiseMatrix;

        // === 高光渐变 ===
        private LinearGradient mSpecularGradient;
        private final Matrix mSpecularMatrix;
        private RadialGradient mAmbientGradient;
        private final Matrix mAmbientMatrix;

        // === 动画器 ===
        private ValueAnimator mBreathAnimator;
        private ValueAnimator mShimmerAnimator;
        private ValueAnimator mNoiseAnimator;
        private ValueAnimator mCausticAnimator;

        // === 动画状态 ===
        private float mBreathAlpha = 0.92f;
        private float mShimmerOffset = 0f;
        private float mNoiseOffset = 0f;
        private float mCausticPhase = 0f;

        // === 几何 ===
        private final int mViewWidth;
        private final int mViewHeight;
        private float mCornerRadius;
        private final boolean mIsDark;
        private final RectF mDrawRect;
        private final RectF mInsetRect;
        private final android.graphics.Path mClipPath;
        private final android.graphics.Path mInnerClipPath;

        // === 交互状态 ===
        private float mTouchX = -1f;
        private float mTouchY = -1f;
        private float mTouchPressure = 0f;
        private float mPopGlow = 0f;

        public LiquidGlassView(Context context, int w, int h, boolean isDark) {
            super(context);
            mViewWidth = w;
            mViewHeight = h;
            mCornerRadius = 28f;
            mIsDark = isDark;
            mDrawRect = new RectF(0, 0, w, h);
            mInsetRect = new RectF(2f, 2f, w - 2f, h - 2f);
            mClipPath = new android.graphics.Path();
            mInnerClipPath = new android.graphics.Path();

            // 初始化所有绘制层
            mBackSurfacePaint = initBackSurface(w, h, isDark);
            mFrontSurfacePaint = initFrontSurface(w, h, isDark);
            mVolumePaint = initVolumeLayer(w, h, isDark);
            mSpecularPaint = initSpecular(w, h);
            mInnerReflectionPaint = initInnerReflection(w, h, isDark);
            mAmbientSpillPaint = initAmbientSpill(isDark);
            mBevelHighlightPaint = initBevelHighlight(isDark);
            mBevelShadowPaint = initBevelShadow(isDark);
            mMicroNoisePaint = initMicroNoise();
            mCausticPaint = initCaustic(w, h);

            // 微观噪点纹理
            mMicroNoiseBitmap = createMicroNoiseBitmap(256, 256);
            mMicroNoiseShader = new BitmapShader(mMicroNoiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mMicroNoisePaint.setShader(mMicroNoiseShader);

            mNoiseMatrix = new Matrix();
            mSpecularMatrix = new Matrix();
            mAmbientMatrix = new Matrix();

            startAnimations();
        }

        // ====== 初始化各绘制层 ======

        private Paint initBackSurface(int w, int h, boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 后表面：更深、更饱和，模拟玻璃背面的深色
            int baseColor = isDark ? 0x30000000 : 0x18FFFFFF;
            paint.setColor(baseColor);
            return paint;
        }

        private Paint initFrontSurface(int w, int h, boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 前表面：主可见层，带微妙的径向渐变营造球面感
            int centerColor = isDark ? 0x28000000 : 0x20FFFFFF;
            int edgeColor = isDark ? 0x1A000000 : 0x14FFFFFF;
            RadialGradient grad = new RadialGradient(
                w * 0.5f, h * 0.4f, Math.max(w, h) * 0.7f,
                new int[]{centerColor, edgeColor},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
            paint.setShader(grad);
            return paint;
        }

        private Paint initVolumeLayer(int w, int h, boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 体积感：玻璃边缘的厚度暗示
            int innerColor = isDark ? 0x00000000 : 0x00FFFFFF;
            int outerColor = isDark ? 0x15000000 : 0x10FFFFFF;
            RadialGradient grad = new RadialGradient(
                w * 0.5f, h * 0.5f, Math.max(w, h) * 0.5f,
                new int[]{innerColor, outerColor},
                new float[]{0.6f, 1f},
                Shader.TileMode.CLAMP);
            paint.setShader(grad);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            return paint;
        }

        private Paint initSpecular(int w, int h) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 镜面高光：顶部明亮的弧形反射带
            mSpecularGradient = new LinearGradient(
                0, 0, 0, h * 0.45f,
                new int[]{0x00FFFFFF, 0x45FFFFFF, 0x10FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.CLAMP);
            paint.setShader(mSpecularGradient);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            return paint;
        }

        private Paint initInnerReflection(int w, int h, boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 内部反射：底部微妙的二次反射
            int reflColor = isDark ? 0x08000000 : 0x0AFFFFFF;
            LinearGradient grad = new LinearGradient(
                0, h * 0.6f, 0, h,
                0x00000000, reflColor,
                Shader.TileMode.CLAMP);
            paint.setShader(grad);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            return paint;
        }

        private Paint initAmbientSpill(boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            // 环境光溢色：边缘处背景颜色渗入玻璃
            paint.setColor(isDark ? 0x20FFFFFF : 0x18FFFFFF);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            return paint;
        }

        private Paint initBevelHighlight(boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            // 倒角高光：顶部/左侧更亮
            paint.setColor(isDark ? 0x60FFFFFF : 0x85FFFFFF);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            return paint;
        }

        private Paint initBevelShadow(boolean isDark) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.0f);
            // 倒角阴影：底部/右侧更暗
            paint.setColor(isDark ? 0x30000000 : 0x20FFFFFF);
            return paint;
        }

        private Paint initMicroNoise() {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
            return paint;
        }

        private Paint initCaustic(int w, int h) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            // 焦散：玻璃聚焦光线的微妙光斑
            paint.setColor(0x08FFFFFF);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            return paint;
        }

        // ====== 微观噪点纹理 ======

        private Bitmap createMicroNoiseBitmap(int w, int h) {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint p = new Paint();
            java.util.Random r = new java.util.Random(54321);
            // 非常细腻的微观纹理，模拟玻璃表面的微观不规则
            for (int i = 0; i < 400; i++) {
                float x = r.nextFloat() * w;
                float y = r.nextFloat() * h;
                float radius = 0.3f + r.nextFloat() * 1.2f;
                int a = 3 + r.nextInt(12);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawCircle(x, y, radius, p);
            }
            // 添加一些更小的点
            for (int i = 0; i < 200; i++) {
                float x = r.nextFloat() * w;
                float y = r.nextFloat() * h;
                int a = 2 + r.nextInt(6);
                p.setColor(Color.argb(a, 200, 220, 255));
                c.drawPoint(x, y, p);
            }
            return bmp;
        }

        // ====== 动画 ======

        private void startAnimations() {
            // 呼吸：更微妙的透明度波动
            mBreathAnimator = ValueAnimator.ofFloat(0.88f, 0.96f);
            mBreathAnimator.setDuration(4000);
            mBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mBreathAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mBreathAnimator.addUpdateListener(anim -> {
                mBreathAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            mBreathAnimator.start();

            // 高光扫过：模拟光源移动
            mShimmerAnimator = ValueAnimator.ofFloat(-0.8f, 1.8f);
            mShimmerAnimator.setDuration(5000);
            mShimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mShimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
            mShimmerAnimator.setInterpolator(new LinearInterpolator());
            mShimmerAnimator.addUpdateListener(anim -> {
                mShimmerOffset = (float) anim.getAnimatedValue();
                mSpecularMatrix.setTranslate(mShimmerOffset * mViewWidth * 0.3f, 0);
                mSpecularGradient.setLocalMatrix(mSpecularMatrix);
                invalidate();
            });
            mShimmerAnimator.start();

            // 噪点微动：模拟玻璃内部微观流动
            mNoiseAnimator = ValueAnimator.ofFloat(0f, 1f);
            mNoiseAnimator.setDuration(6000);
            mNoiseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mNoiseAnimator.setInterpolator(new LinearInterpolator());
            mNoiseAnimator.addUpdateListener(anim -> {
                mNoiseOffset = (float) anim.getAnimatedValue();
                mNoiseMatrix.setTranslate(mNoiseOffset * 128, mNoiseOffset * 64);
                mMicroNoiseShader.setLocalMatrix(mNoiseMatrix);
                invalidate();
            });
            mNoiseAnimator.start();

            // 焦散缓慢波动
            mCausticAnimator = ValueAnimator.ofFloat(0f, (float)(Math.PI * 2));
            mCausticAnimator.setDuration(8000);
            mCausticAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mCausticAnimator.setInterpolator(new LinearInterpolator());
            mCausticAnimator.addUpdateListener(anim -> {
                mCausticPhase = (float) anim.getAnimatedValue();
                invalidate();
            });
            mCausticAnimator.start();
        }

        // ====== 公共接口 ======

        public void setCornerRadius(float radius) {
            mCornerRadius = radius;
            invalidate();
        }

        public void setTouchPoint(float x, float y, float pressure) {
            mTouchX = x; mTouchY = y; mTouchPressure = pressure;
            invalidate();
        }

        public void clearTouchPoint() {
            mTouchX = -1f; mTouchY = -1f; mTouchPressure = 0f;
            invalidate();
        }

        public void setPopGlow(float glow) {
            mPopGlow = Math.max(0f, Math.min(1f, glow));
            invalidate();
        }

        // ====== 绘制主流程 ======

        @Override
        protected void onDraw(Canvas canvas) {
            // 外层裁剪路径
            mClipPath.reset();
            mClipPath.addRoundRect(mDrawRect, mCornerRadius, mCornerRadius, android.graphics.Path.Direction.CW);

            // 内层裁剪路径（用于内部效果）
            float innerInset = 2.5f;
            mInnerClipPath.reset();
            mInnerClipPath.addRoundRect(
                new RectF(innerInset, innerInset, mViewWidth - innerInset, mViewHeight - innerInset),
                Math.max(0f, mCornerRadius - innerInset), Math.max(0f, mCornerRadius - innerInset),
                android.graphics.Path.Direction.CW);

            int saveCount = canvas.save();
            canvas.clipPath(mClipPath);

            // 1. 后表面（玻璃背面）
            drawBackSurface(canvas);

            // 2. 前表面（玻璃正面，主可见层）
            drawFrontSurface(canvas);

            // 3. 体积感（厚度暗示）
            drawVolumeLayer(canvas);

            // 4. 内部反射
            drawInnerReflection(canvas);

            // 5. 镜面高光
            drawSpecular(canvas);

            // 6. 焦散光效
            drawCaustic(canvas);

            // 7. 微观噪点纹理
            drawMicroNoise(canvas);

            // 8. 边缘效果（倒角 + 环境光溢色）
            drawEdgeEffects(canvas);

            // 9. 触摸凹陷
            drawTouchDent(canvas);

            canvas.restoreToCount(saveCount);
        }

        // ====== 各层绘制方法 ======

        private void drawBackSurface(Canvas canvas) {
            mBackSurfacePaint.setAlpha((int)(200 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mBackSurfacePaint);
        }

        private void drawFrontSurface(Canvas canvas) {
            mFrontSurfacePaint.setAlpha((int)(220 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mFrontSurfacePaint);
        }

        private void drawVolumeLayer(Canvas canvas) {
            mVolumePaint.setAlpha((int)(160 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mVolumePaint);
        }

        private void drawInnerReflection(Canvas canvas) {
            mInnerReflectionPaint.setAlpha((int)(100 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mInnerReflectionPaint);
        }

        private void drawSpecular(Canvas canvas) {
            // 镜面高光强度受呼吸和弹出光晕影响
            int baseAlpha = (int)(70 * mBreathAlpha);
            int glowAlpha = (int)(100 * mPopGlow);
            mSpecularPaint.setAlpha(Math.min(255, baseAlpha + glowAlpha));
            canvas.drawRect(mDrawRect, mSpecularPaint);
        }

        private void drawCaustic(Canvas canvas) {
            // 焦散光斑：缓慢移动的微妙光点
            int baseAlpha = (int)(25 * mBreathAlpha);
            int glowAlpha = (int)(40 * mPopGlow);
            mCausticPaint.setAlpha(Math.min(255, baseAlpha + glowAlpha));

            float cx1 = mViewWidth * 0.3f + (float)Math.sin(mCausticPhase) * 8f;
            float cy1 = mViewHeight * 0.25f + (float)Math.cos(mCausticPhase * 0.7f) * 5f;
            float r1 = 15f + (float)Math.sin(mCausticPhase * 1.3f) * 3f;
            canvas.drawCircle(cx1, cy1, r1, mCausticPaint);

            float cx2 = mViewWidth * 0.7f + (float)Math.cos(mCausticPhase * 0.8f) * 6f;
            float cy2 = mViewHeight * 0.35f + (float)Math.sin(mCausticPhase * 1.1f) * 4f;
            float r2 = 10f + (float)Math.cos(mCausticPhase * 1.5f) * 2f;
            canvas.drawCircle(cx2, cy2, r2, mCausticPaint);
        }

        private void drawMicroNoise(Canvas canvas) {
            int baseAlpha = (int)(35 * mBreathAlpha);
            int glowAlpha = (int)(20 * mPopGlow);
            mMicroNoisePaint.setAlpha(Math.min(255, baseAlpha + glowAlpha));
            canvas.drawRect(mDrawRect, mMicroNoisePaint);
        }

        private void drawEdgeEffects(Canvas canvas) {
            // 环境光溢色：玻璃边缘的柔和光晕
            int spillAlpha = (int)(50 * mBreathAlpha + 80 * mPopGlow);
            mAmbientSpillPaint.setAlpha(Math.min(255, spillAlpha));
            float spillInset = 1.5f;
            RectF spillRect = new RectF(spillInset, spillInset,
                mViewWidth - spillInset, mViewHeight - spillInset);
            canvas.drawRoundRect(spillRect,
                Math.max(0f, mCornerRadius - spillInset),
                Math.max(0f, mCornerRadius - spillInset),
                mAmbientSpillPaint);

            // 倒角高光（顶部和左侧更亮）
            int bevelAlpha = (int)(90 * mBreathAlpha + 120 * mPopGlow);
            mBevelHighlightPaint.setAlpha(Math.min(255, bevelAlpha));
            float bevelInset = 0.8f;
            RectF bevelRect = new RectF(bevelInset, bevelInset,
                mViewWidth - bevelInset, mViewHeight - bevelInset);
            canvas.drawRoundRect(bevelRect,
                Math.max(0f, mCornerRadius - bevelInset),
                Math.max(0f, mCornerRadius - bevelInset),
                mBevelHighlightPaint);

            // 倒角阴影（底部和右侧更暗）
            int shadowAlpha = (int)(50 * mBreathAlpha);
            mBevelShadowPaint.setAlpha(Math.min(255, shadowAlpha));
            float shadowInset = 0.5f;
            RectF shadowRect = new RectF(shadowInset, shadowInset,
                mViewWidth - shadowInset, mViewHeight - shadowInset);
            canvas.drawRoundRect(shadowRect,
                Math.max(0f, mCornerRadius - shadowInset),
                Math.max(0f, mCornerRadius - shadowInset),
                mBevelShadowPaint);
        }

        private void drawTouchDent(Canvas canvas) {
            if (mTouchX >= 0 && mTouchPressure > 0.01f) {
                float dentR = 40f * mTouchPressure;
                // 主凹陷
                Paint dentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dentPaint.setColor(mIsDark ? 0x20000000 : 0x14FFFFFF);
                canvas.drawCircle(mTouchX, mTouchY, dentR, dentPaint);
                // 凹陷边缘高光（模拟玻璃被按压时的折射）
                Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                rimPaint.setStyle(Paint.Style.STROKE);
                rimPaint.setColor(Color.argb((int)(60 * mTouchPressure), 255, 255, 255));
                rimPaint.setStrokeWidth(2.5f * mTouchPressure);
                canvas.drawCircle(mTouchX, mTouchY, dentR, rimPaint);
                // 凹陷内部二次反射
                Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                innerPaint.setColor(Color.argb((int)(15 * mTouchPressure), 255, 255, 255));
                canvas.drawCircle(mTouchX, mTouchY, dentR * 0.5f, innerPaint);
            }
        }

        public void stopAnimations() {
            if (mBreathAnimator != null) { mBreathAnimator.cancel(); mBreathAnimator = null; }
            if (mShimmerAnimator != null) { mShimmerAnimator.cancel(); mShimmerAnimator = null; }
            if (mNoiseAnimator != null) { mNoiseAnimator.cancel(); mNoiseAnimator = null; }
            if (mCausticAnimator != null) { mCausticAnimator.cancel(); mCausticAnimator = null; }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimations();
            if (mMicroNoiseBitmap != null && !mMicroNoiseBitmap.isRecycled()) mMicroNoiseBitmap.recycle();
        }
    }

} // MainHook
