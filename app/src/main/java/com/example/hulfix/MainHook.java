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
    private static final float MIN_FLING_VELOCITY = 200f;
    private static final float SWIPE_INTENT_THRESHOLD = 40f;
    private static final float CLICK_THRESHOLD = 12f;

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
    private static final long USER_IGNORE_COOLDOWN_MS = 2000;

    private long mGlobalCooldownTime = 0;
    private static final long GLOBAL_COOLDOWN_MS = 1000;

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
        XposedBridge.log(TAG + ": ====== HULFix Overlay v26 loaded ======");
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

            boolean userIgnored = mUserDismissedKey != null && key.equals(mUserDismissedKey)
                && SystemClock.elapsedRealtime() - mUserDismissTime < USER_IGNORE_COOLDOWN_MS;
            XposedBridge.log(TAG + "[DIAG] userIgnored=" + userIgnored);
            if (userIgnored) return;

            boolean globalCooldown = isGlobalCooldown();
            XposedBridge.log(TAG + "[DIAG] isGlobalCooldown=" + globalCooldown);
            if (globalCooldown) return;

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
        try {
            boolean r = XposedHelpers.getBooleanField(mStatusBar, "mExpandedVisible");
            XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=" + r + " (mExpandedVisible)");
            return r;
        } catch (Throwable t1) {
            try {
                boolean r = XposedHelpers.getBooleanField(mStatusBar, "mIsExpanded");
                XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=" + r + " (mIsExpanded)");
                return r;
            } catch (Throwable t2) {
                try {
                    boolean r = XposedHelpers.getBooleanField(mStatusBar, "mPanelExpanded");
                    XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=" + r + " (mPanelExpanded)");
                    return r;
                } catch (Throwable ignored) {
                    XposedBridge.log(TAG + "[DIAG] isStatusBarExpanded=false (all fields missing)");
                    return false;
                }
            }
        }
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

    private void hookPanelExpansion(XC_LoadPackage.LoadPackageParam lpparam) {
        // LineageOS 20 GSI: 只保留 CentralSurfacesImpl 路径，其他类不存在
        try {
            Class<?> csClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl", lpparam.classLoader);
            hookAllMethodsCompat(csClass, "expandNotificationsPanel", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    mIsPanelExpanded = true;
                    triggerGlobalCooldown();
                }
            });
            // 也 hook setExpandedFraction 如果存在
            try {
                XposedHelpers.findAndHookMethod(csClass, "setExpandedFraction",
                    float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            float fraction = (float) param.args[0];
                            if (fraction > 0.05f) {
                                mIsPanelExpanded = true;
                                triggerGlobalCooldown();
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
        if (enter != null) enter.cancel();
        if (exit != null) exit.cancel();
        if (bounce != null) bounce.cancel();
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
                if (mEnterAnim == null) return;
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
                if (mExitAnim == null) return;
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
                if (mBounceAnim == null) return;
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
                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = (bigText != null && bigText.length() > 0) ? bigText.toString() : text.toString();
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: title=" + title + ", content=" + content);
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode() & 0x7FFFFFFF);

                if (key.equals(mCurrentKey) && mCurrentOverlay != null) {
                    if (newHash.equals(mCurrentContentHash)) {
                        XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: same content hash, skipping");
                        return;
                    }
                }
                XposedBridge.log(TAG + "[DIAG] showCustomHeadsUp: removing old overlay");
                removeOverlayImmediate();
                mCurrentContentHash = newHash;

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

                // === 触摸事件处理（只移动 contentContainer）===
                contentContainer.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;
                    boolean lockedHorizontal = false;
                    boolean lockedVertical = false;

                    @Override public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startX = event.getRawX(); startY = event.getRawY();
                                lockedHorizontal = false; lockedVertical = false;
                                mTouchMaxDx = 0f; mTouchMaxDy = 0f;
                                if (mVelocityTracker != null) mVelocityTracker.recycle();
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
                                    if (Math.abs(dx) > DIRECTION_LOCK_SLOP || Math.abs(dy) > DIRECTION_LOCK_SLOP) {
                                        if (Math.abs(dx) > Math.abs(dy)) lockedHorizontal = true;
                                        else lockedVertical = true;
                                    }
                                }
                                mTouchMaxDx = Math.max(mTouchMaxDx, Math.abs(dx));
                                mTouchMaxDy = Math.max(mTouchMaxDy, Math.abs(dy));
                                if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
                                if (lockedHorizontal) { v.setTranslationX(dx); v.setTranslationY(0); }
                                else if (lockedVertical) { v.setTranslationX(0); v.setTranslationY(dy); }
                                else { v.setTranslationX(dx); v.setTranslationY(dy); }
                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                v.setAlpha(Math.max(0.5f, 1f - dist / 300f));
                                // 同步移动整个 root（玻璃+背景+文字一起动）
                                if (mCurrentOverlay != null) {
                                    mCurrentOverlay.setTranslationX(dx);
                                    mCurrentOverlay.setTranslationY(dy);
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
                                v.setTranslationX(0f);
                                v.setTranslationY(0f);
                                v.setAlpha(1f);
                                if (mCurrentOverlay != null) {
                                    mCurrentOverlay.setTranslationX(0f);
                                    mCurrentOverlay.setTranslationY(0f);
                                }
                                if (mGlassView != null) mGlassView.clearTouchPoint();
                                return true;
                            case MotionEvent.ACTION_UP:
                                if (mGlassView != null) mGlassView.clearTouchPoint();
                                if (mCurrentOverlay != null) {
                                    mCurrentOverlay.setTranslationX(0f);
                                    mCurrentOverlay.setTranslationY(0f);
                                }
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

                                if (totalDy < -SWIPE_DESTROY_THRESHOLD && !isHorizontal) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    dismissOverlayAnimated(1); return true;
                                }
                                if (totalDx < -SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    dismissOverlayAnimated(0); return true;
                                }
                                if (totalDx > SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    if (mCurrentKey != null) {
                                        mUserDismissedKey = mCurrentKey;
                                        mUserDismissTime = SystemClock.elapsedRealtime();
                                    }
                                    dismissOverlayAnimated(2); return true;
                                }
                                if (totalDy > PULLDOWN_THRESHOLD && !isHorizontal) {
                                    expandStatusBar(); removeOverlayImmediate(); return true;
                                }
                                boolean hasSwipeIntent = (mTouchMaxDx > SWIPE_INTENT_THRESHOLD)
                                    || (mTouchMaxDy > SWIPE_INTENT_THRESHOLD);
                                boolean isFastFling = (Math.abs(velocityX) > MIN_FLING_VELOCITY)
                                    || (Math.abs(velocityY) > MIN_FLING_VELOCITY);
                                if (hasSwipeIntent || isFastFling) {
                                    startBounceAnimation(v, totalDx < 0 ? -1f : 1f);
                                    return true;
                                }
                                if (Math.abs(totalDx) < CLICK_THRESHOLD && Math.abs(totalDy) < CLICK_THRESHOLD) {
                                    performContentClick(contentIntent);
                                    dismissOverlayAnimated(1);
                                    return true;
                                }
                                startBounceAnimation(v, totalDx < 0 ? -1f : 1f);
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
                mCurrentKey = key;
                mCurrentOverlay = root;
                XposedBridge.log(TAG + ": Shown: " + title);
                XposedBridge.log(TAG + "[DIAG] Overlay shown successfully, key=" + key);

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
            try { contentIntent.send(); } catch (Throwable ignored) {}
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
        mHandler.post(() -> {
            XposedBridge.log(TAG + "[DIAG] dismissOverlayAnimated: inside handler");
            if (mAutoDismissRunnable != null) {
                mHandler.removeCallbacks(mAutoDismissRunnable);
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
        try {
            int w = input.getWidth(), h = input.getHeight();
            int smallW = Math.max(1, w / BLUR_SCALE_FACTOR);
            int smallH = Math.max(1, h / BLUR_SCALE_FACTOR);
            Bitmap small = Bitmap.createScaledBitmap(input, smallW, smallH, false);
            android.renderscript.RenderScript rs = android.renderscript.RenderScript.create(mContext);
            android.renderscript.Allocation inputAlloc = android.renderscript.Allocation.createFromBitmap(rs, small);
            android.renderscript.Allocation outputAlloc = android.renderscript.Allocation.createTyped(rs, inputAlloc.getType());
            android.renderscript.ScriptIntrinsicBlur blur = android.renderscript.ScriptIntrinsicBlur.create(
                rs, android.renderscript.Element.U8_4(rs));
            blur.setRadius(BLUR_RADIUS);
            blur.setInput(inputAlloc);
            blur.forEach(outputAlloc);
            Bitmap blurredSmall = Bitmap.createBitmap(smallW, smallH, small.getConfig());
            outputAlloc.copyTo(blurredSmall);
            rs.destroy();
            blur.destroy();
            inputAlloc.destroy();
            outputAlloc.destroy();
            small.recycle();
            Bitmap result = Bitmap.createScaledBitmap(blurredSmall, w, h, true);
            blurredSmall.recycle();
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
        String keyToRemove = mCurrentKey;
        final View overlayToRemove = mCurrentOverlay;
        if (overlayToRemove != null) {
            try {
                overlayToRemove.setAlpha(0f);
                if (mContentView != null) mContentView.setOnTouchListener(null);
            } catch (Throwable ignored) {}
            mHandler.postDelayed(() -> {
                try {
                    if (overlayToRemove.getParent() != null) {
                        mWindowManager.removeView(overlayToRemove);
                        XposedBridge.log(TAG + "[DIAG] removeView success");
                    } else {
                        XposedBridge.log(TAG + "[DIAG] removeView skipped - no parent");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "[DIAG] removeView failed: " + t);
                    try { mWindowManager.removeViewImmediate(overlayToRemove); } catch (Throwable ignored) {}
                }
            }, 50);
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
        mCurrentKey = null;
        mCurrentRowView = null;
        mCurrentOverlay = null;
        mCurrentContentHash = null;
    }

    private class LiquidGlassView extends View {
        private final Paint mBasePaint;
        private final Paint mHighlightPaint;
        private final Paint mCausticPaint;
        private final Paint mReflectionPaint;
        private final Paint mShadowPaint;
        private final Paint mInnerGlowPaint;
        private final Paint mDentPaint;
        private final Paint mDentRimPaint;

        private final Bitmap mNoiseBitmap;
        private final BitmapShader mNoiseShader;
        private final Matrix mNoiseMatrix;

        private ValueAnimator mFlowAnimator;
        private ValueAnimator mCausticAnimator;
        private ValueAnimator mBreathAnimator;
        private ValueAnimator mInnerGlowAnimator;

        private float mFlowOffset = 0f;
        private float mCausticAngle = 0f;
        private float mBreathAlpha = 0.95f;
        private float mInnerGlowIntensity = 0.3f;
        private final int mViewWidth;
        private final int mViewHeight;
        private float mCornerRadius;
        private final boolean mIsDark;
        private RectF mDrawRect;
        private final android.graphics.Path mClipPath;

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

            int centerColor = isDark ? 0x22FFFFFF : 0x28FFFFFF;
            int midColor = isDark ? 0x10FFFFFF : 0x15FFFFFF;
            int edgeColor = isDark ? 0x08FFFFFF : 0x0AFFFFFF;
            RadialGradient volumeGrad = new RadialGradient(
                w * 0.5f, h * 0.4f, Math.max(w, h) * 0.8f,
                new int[]{centerColor, midColor, edgeColor, 0x00000000},
                new float[]{0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.CLAMP);
            mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBasePaint.setShader(volumeGrad);

            mNoiseBitmap = createNoiseBitmap(256, 256);
            mNoiseShader = new BitmapShader(mNoiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mNoiseMatrix = new Matrix();
            mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mHighlightPaint.setShader(mNoiseShader);
            mHighlightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            mCausticPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mCausticPaint.setStyle(Paint.Style.STROKE);
            mCausticPaint.setStrokeWidth(2.5f);
            mCausticPaint.setMaskFilter(new android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL));

            int reflStart = isDark ? 0x45FFFFFF : 0x60FFFFFF;
            int reflMid = isDark ? 0x20FFFFFF : 0x30FFFFFF;
            LinearGradient reflGrad = new LinearGradient(
                0, 0, 0, h * 0.55f,
                new int[]{reflStart, reflMid, 0x00FFFFFF},
                new float[]{0f, 0.2f, 1f},
                Shader.TileMode.CLAMP);
            mReflectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mReflectionPaint.setShader(reflGrad);
            mReflectionPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            int shadowEnd = isDark ? 0x30000000 : 0x18FFFFFF;
            LinearGradient shadowGrad = new LinearGradient(
                0, h * 0.5f, 0, h,
                0x00000000, shadowEnd, Shader.TileMode.CLAMP);
            mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mShadowPaint.setShader(shadowGrad);

            mInnerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mInnerGlowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(10f, android.graphics.BlurMaskFilter.Blur.NORMAL));

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
            for (int i = 0; i < 30; i++) {
                float x = r.nextFloat() * w;
                float y = r.nextFloat() * h * 0.4f;
                float radius = 8f + r.nextFloat() * 20f;
                int a = 12 + r.nextInt(30);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawCircle(x, y, radius, p);
            }
            for (int i = 0; i < 100; i++) {
                float x = r.nextFloat() * w;
                float y = r.nextFloat() * h;
                float radius = 1f + r.nextFloat() * 3f;
                int a = 15 + r.nextInt(40);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawCircle(x, y, radius, p);
            }
            for (int i = 0; i < 6; i++) {
                float y = r.nextFloat() * h * 0.3f;
                float bandH = 2f + r.nextFloat() * 5f;
                int a = 8 + r.nextInt(15);
                p.setColor(Color.argb(a, 255, 255, 255));
                c.drawRect(0, y, w, y + bandH, p);
            }
            return bmp;
        }

        private void startAnimations() {
            mFlowAnimator = ValueAnimator.ofFloat(0f, 1f);
            mFlowAnimator.setDuration(2500);
            mFlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mFlowAnimator.setInterpolator(new LinearInterpolator());
            mFlowAnimator.addUpdateListener(anim -> {
                mFlowOffset = (float) anim.getAnimatedValue();
                mNoiseMatrix.setTranslate(mFlowOffset * 512, mFlowOffset * 64);
                mNoiseShader.setLocalMatrix(mNoiseMatrix);
                invalidate();
            });
            mFlowAnimator.start();

            mCausticAnimator = ValueAnimator.ofFloat(0f, 1f);
            mCausticAnimator.setDuration(6000);
            mCausticAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mCausticAnimator.setInterpolator(new LinearInterpolator());
            mCausticAnimator.addUpdateListener(anim -> {
                mCausticAngle = (float) anim.getAnimatedValue();
                float[] hsv = new float[]{200f + (float)(Math.sin(mCausticAngle * Math.PI * 2) * 25), 0.25f, 0.9f};
                mCausticPaint.setColor(Color.HSVToColor(hsv));
                invalidate();
            });
            mCausticAnimator.start();

            mBreathAnimator = ValueAnimator.ofFloat(0.90f, 0.98f);
            mBreathAnimator.setDuration(4000);
            mBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mBreathAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mBreathAnimator.addUpdateListener(anim -> {
                mBreathAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            mBreathAnimator.start();

            mInnerGlowAnimator = ValueAnimator.ofFloat(0.2f, 0.5f);
            mInnerGlowAnimator.setDuration(3000);
            mInnerGlowAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mInnerGlowAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mInnerGlowAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mInnerGlowAnimator.addUpdateListener(anim -> {
                mInnerGlowIntensity = (float) anim.getAnimatedValue();
                invalidate();
            });
            mInnerGlowAnimator.start();
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
            // 外部阴影（在 clip 之前绘制，超出玻璃区域）
            Paint outerShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            outerShadow.setColor(mIsDark ? 0x50000000 : 0x30FFFFFF);
            outerShadow.setMaskFilter(new android.graphics.BlurMaskFilter(18f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            RectF shadowRect = new RectF(-6, 4, mViewWidth + 6, mViewHeight + 14);
            canvas.drawRoundRect(shadowRect, mCornerRadius, mCornerRadius, outerShadow);

            // 第二层外发光（焦散感）
            Paint outerGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
            outerGlow.setColor(mIsDark ? 0x18AADDFF : 0x10FFCC88);
            outerGlow.setMaskFilter(new android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            RectF glowRect = new RectF(-3, 2, mViewWidth + 3, mViewHeight + 8);
            canvas.drawRoundRect(glowRect, mCornerRadius, mCornerRadius, outerGlow);

            mClipPath.reset();
            mClipPath.addRoundRect(mDrawRect, mCornerRadius, mCornerRadius, android.graphics.Path.Direction.CW);
            canvas.clipPath(mClipPath);

            canvas.drawRect(mDrawRect, mBasePaint);

            mHighlightPaint.setAlpha((int)(35 * mBreathAlpha));
            canvas.drawRect(mDrawRect, mHighlightPaint);

            canvas.drawRect(mDrawRect, mReflectionPaint);

            mCausticPaint.setAlpha((int)(55 * mBreathAlpha));
            RectF innerRect = new RectF(2, 2, mViewWidth - 2, mViewHeight - 2);
            canvas.drawRoundRect(innerRect, mCornerRadius - 1, mCornerRadius - 1, mCausticPaint);

            int glowColor = mIsDark ?
                Color.argb((int)(25 * mInnerGlowIntensity), 180, 210, 255) :
                Color.argb((int)(20 * mInnerGlowIntensity), 200, 230, 255);
            mInnerGlowPaint.setColor(glowColor);
            float glowR = mCornerRadius * 1.8f;
            canvas.drawCircle(mCornerRadius, mCornerRadius, glowR, mInnerGlowPaint);
            canvas.drawCircle(mViewWidth - mCornerRadius, mCornerRadius, glowR, mInnerGlowPaint);

            canvas.drawRect(mDrawRect, mShadowPaint);

            if (mTouchX >= 0 && mTouchPressure > 0.01f) {
                float dentR = 35f * mTouchPressure;
                mDentPaint.setColor(mIsDark ? 0x25000000 : 0x18FFFFFF);
                canvas.drawCircle(mTouchX, mTouchY, dentR, mDentPaint);
                mDentRimPaint.setColor(Color.argb((int)(50 * mTouchPressure), 255, 255, 255));
                mDentRimPaint.setStrokeWidth(2f * mTouchPressure);
                canvas.drawCircle(mTouchX, mTouchY, dentR, mDentRimPaint);
            }
        }

        public void stopAnimations() {
            if (mFlowAnimator != null) mFlowAnimator.cancel();
            if (mCausticAnimator != null) mCausticAnimator.cancel();
            if (mBreathAnimator != null) mBreathAnimator.cancel();
            if (mInnerGlowAnimator != null) mInnerGlowAnimator.cancel();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimations();
            if (mNoiseBitmap != null && !mNoiseBitmap.isRecycled()) mNoiseBitmap.recycle();
        }
    }

}
