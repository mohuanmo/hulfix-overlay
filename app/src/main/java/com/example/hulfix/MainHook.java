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
    private static final long SHIELD_DELAY_MS = 400;
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
    private long mLastDismissTime = 0;

    private String mUserDismissedKey = null;
    private long mUserDismissTime = 0;
    private static final long USER_DISMISS_COOLDOWN_MS = 2000;

    private long mGlobalCooldownTime = 0;
    private static final long GLOBAL_COOLDOWN_MS = 1000;

    private Object mHeadsUpManager = null;
    private Object mStatusBar = null;

    private BroadcastReceiver mScreenReceiver = null;
    private boolean mBroadcastRegistered = false;

    private ValueAnimator mEnterAnim = null;
    private ValueAnimator mExitAnim = null;
    private ValueAnimator mBounceAnim = null;

    private float mTouchMaxDx = 0f;
    private float mTouchMaxDy = 0f;
    private android.view.VelocityTracker mVelocityTracker = null;

    private boolean mIsPanelExpanded = false;

    private View mContentView = null;
    private ImageView mBgImageView = null;
    private LiquidGlassView mGlassView = null;
    private Bitmap mBlurredBgBitmap = null;
    private Runnable mBgUpdateRunnable = null;
    private static final long BG_UPDATE_INTERVAL_MS = 500;
    private static final float BLUR_RADIUS = 10f;
    private static final int BLUR_SCALE_FACTOR = 4;


    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": ====== HULFix Overlay v26 loaded ======");
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
        hookHeadsUpRowTouch(lpparam);
        captureHeadsUpManager(lpparam);
        captureStatusBar(lpparam);
    }

    private void captureHeadsUpManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> headsUpClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.policy.HeadsUpManager", lpparam.classLoader);
            XposedBridge.hookAllMethods(headsUpClass, "addNotification",
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

    private void captureStatusBar(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> statusBarClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.phone.StatusBar", lpparam.classLoader);
            XposedBridge.hookAllConstructors(statusBarClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    mStatusBar = param.thisObject;
                    XposedBridge.log(TAG + ": StatusBar captured via constructor");
                }
            });
            XposedHelpers.findAndHookMethod(statusBarClass, "start", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBar == null) {
                        mStatusBar = param.thisObject;
                        XposedBridge.log(TAG + ": StatusBar captured via start()");
                    }
                }
            });
            XposedBridge.hookAllMethods(statusBarClass, "addNotification", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (mStatusBar == null) {
                        mStatusBar = param.thisObject;
                        XposedBridge.log(TAG + ": StatusBar captured via addNotification");
                    }
                }
            });
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "expandNotificationsPanel", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        triggerGlobalCooldown();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook expandNotificationsPanel skipped: " + t); }
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "setExpandedVisible", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        boolean visible = (boolean) param.args[0];
                        mIsPanelExpanded = visible;
                        if (visible) triggerGlobalCooldown();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook setExpandedVisible skipped: " + t); }
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "makeExpandedVisible", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        triggerGlobalCooldown();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook makeExpandedVisible skipped: " + t); }
            try {
                Class<?> panelControllerClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.PanelViewController", lpparam.classLoader);
                XposedBridge.hookAllMethods(panelControllerClass, "onTrackingStarted", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        triggerGlobalCooldown();
                    }
                });
                XposedBridge.hookAllMethods(panelControllerClass, "onTrackingStopped", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (mStatusBar != null) {
                            try {
                                mIsPanelExpanded = XposedHelpers.getBooleanField(mStatusBar, "mExpandedVisible");
                            } catch (Throwable t) {
                                mIsPanelExpanded = false;
                            }
                        }
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook PanelViewController skipped: " + t); }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": StatusBar hooks failed: " + t);
        }
    }

    private void removeSystemHeadsUpEntry(String key) {
        if (mHeadsUpManager != null && key != null) {
            try {
                XposedHelpers.callMethod(mHeadsUpManager, "removeNotification", key, true);
            } catch (Throwable t) {
                try { XposedHelpers.callMethod(mHeadsUpManager, "removeNotification", key); }
                catch (Throwable ignored) {}
            }
        }
    }

    private void removeSystemNotificationView(String key) {
        if (mStatusBar != null && key != null) {
            try {
                Class<?> nvClass = XposedHelpers.findClass(
                    "android.service.notification.NotificationVisibility",
                    mStatusBar.getClass().getClassLoader());
                Object nv = XposedHelpers.callStaticMethod(nvClass, "obtain", key, 0, 0, false);
                XposedHelpers.callMethod(mStatusBar, "removeNotification", key, nv);
            } catch (Throwable t1) {
                try { XposedHelpers.callMethod(mStatusBar, "removeNotification", key); }
                catch (Throwable t2) {
                    try {
                        Object presenter = XposedHelpers.getObjectField(mStatusBar, "mPresenter");
                        if (presenter != null) XposedHelpers.callMethod(presenter, "removeNotification", key);
                    } catch (Throwable t3) {
                        try {
                            Object entryManager = XposedHelpers.getObjectField(mStatusBar, "mEntryManager");
                            if (entryManager != null) XposedHelpers.callMethod(entryManager, "removeNotification", key);
                        } catch (Throwable t4) {}
                    }
                }
            }
        }
    }

    private boolean isFreshNotification(StatusBarNotification sbn) {
        return System.currentTimeMillis() - sbn.getPostTime() <= NOTIFICATION_MAX_AGE_MS;
    }

    private boolean isKeyguardLocked() {
        if (mContext == null) return false;
        try {
            KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) { return false; }
    }

    private boolean isStatusBarExpanded() {
        if (mIsPanelExpanded) return true;
        if (mStatusBar == null) return false;
        try { return XposedHelpers.getBooleanField(mStatusBar, "mExpandedVisible"); }
        catch (Throwable t1) {
            try { return XposedHelpers.getBooleanField(mStatusBar, "mIsExpanded"); }
            catch (Throwable t2) {
                try { return XposedHelpers.getBooleanField(mStatusBar, "mPanelExpanded"); }
                catch (Throwable ignored) { return false; }
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
        return SystemClock.elapsedRealtime() - mGlobalCooldownTime < GLOBAL_COOLDOWN_MS;
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

    private void hookHeadsUpIsVisible(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rowClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader);
            XposedHelpers.findAndHookMethod(rowClass, "setHeadsUpIsVisible", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (isGlobalCooldown()) { param.setResult(null); return; }
                        if (isStatusBarExpanded()) { param.setResult(null); return; }
                        View rowView = (View) param.thisObject;
                        if (!isLandscape(rowView)) return;
                        StatusBarNotification sbn = getSbnFromRow(rowView);
                        if (sbn == null || BLOCK_PKG.equals(sbn.getPackageName())) return;
                        String key = sbn.getKey();
                        long now = SystemClock.elapsedRealtime();
                        if (key.equals(mUserDismissedKey) && (now - mUserDismissTime) < USER_DISMISS_COOLDOWN_MS) {
                            param.setResult(null); return;
                        }
                        if (key.equals(mCurrentKey) && mCurrentOverlay != null) { param.setResult(null); return; }
                        if (key.equals(mCurrentKey) && (now - mLastDismissTime) < COOLDOWN_MS) {
                            param.setResult(null);
                            removeSystemHeadsUpEntry(key);
                            removeSystemNotificationView(key);
                            return;
                        }
                        if (!isFreshNotification(sbn)) { param.setResult(null); return; }
                        param.setResult(null);
                        if (mContext == null) {
                            mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                            mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                        }
                        mCurrentRowView = rowView;
                        showCustomHeadsUp(sbn);
                    } catch (Throwable t) { XposedBridge.log(TAG + ": setHeadsUpIsVisible error: " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": setHeadsUpIsVisible hook failed: " + t); }
    }

    private void hookAnimatingAway(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rowClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader);
            XposedHelpers.findAndHookMethod(rowClass, "setHeadsUpAnimatingAway", boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (isGlobalCooldown()) { param.setResult(null); return; }
                        if (isStatusBarExpanded()) { param.setResult(null); return; }
                        boolean animatingAway = (boolean) param.args[0];
                        View rowView = (View) param.thisObject;
                        if (mCurrentKey == null && mCurrentRowView == rowView) return;
                        if (animatingAway) return;
                        boolean isHeadsUp = false;
                        try { isHeadsUp = (boolean) XposedHelpers.callMethod(rowView, "isHeadsUp"); }
                        catch (Throwable ignored) {}
                        if (!isHeadsUp || !isLandscape(rowView)) return;
                        StatusBarNotification sbn = getSbnFromRow(rowView);
                        if (sbn == null || BLOCK_PKG.equals(sbn.getPackageName())) return;
                        String key = sbn.getKey();
                        long now = SystemClock.elapsedRealtime();
                        if (key.equals(mUserDismissedKey) && (now - mUserDismissTime) < USER_DISMISS_COOLDOWN_MS) {
                            param.setResult(null); return;
                        }
                        if (key.equals(mCurrentKey) && mCurrentOverlay != null) { param.setResult(null); return; }
                        if (key.equals(mCurrentKey) && (now - mLastDismissTime) < COOLDOWN_MS) {
                            param.setResult(null);
                            removeSystemHeadsUpEntry(key);
                            removeSystemNotificationView(key);
                            return;
                        }
                        if (!isFreshNotification(sbn)) { param.setResult(null); return; }
                        param.setResult(null);
                        if (mContext == null) {
                            mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                            mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                        }
                        mCurrentRowView = rowView;
                        showCustomHeadsUp(sbn);
                    } catch (Throwable t) { XposedBridge.log(TAG + ": setHeadsUpAnimatingAway error: " + t); }
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": setHeadsUpAnimatingAway hook failed: " + t); }
    }

    private void hookHeadsUpRowTouch(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rowClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                lpparam.classLoader);
            XposedHelpers.findAndHookMethod(rowClass, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (mCurrentKey == null) return;
                    try {
                        View rowView = (View) param.thisObject;
                        StatusBarNotification sbn = getSbnFromRow(rowView);
                        if (sbn != null && mCurrentKey.equals(sbn.getKey())) {
                            param.setResult(true);
                        }
                    } catch (Throwable t) {}
                }
            });
            XposedBridge.log(TAG + ": HeadsUpRow touch hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookHeadsUpRowTouch failed: " + t);
        }
    }

    private StatusBarNotification getSbnFromRow(View rowView) {
        try {
            Object entry = XposedHelpers.getObjectField(rowView, "mEntry");
            if (entry == null) entry = XposedHelpers.getObjectField(rowView, "mSbn");
            if (entry instanceof StatusBarNotification) return (StatusBarNotification) entry;
            if (entry != null) {
                Object sbn = XposedHelpers.getObjectField(entry, "mSbn");
                if (sbn instanceof StatusBarNotification) return (StatusBarNotification) sbn;
            }
        } catch (Throwable t) {}
        return null;
    }

    private boolean isLandscape(View view) {
        return view.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void cancelAllAnimations() {
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
        cancelAllAnimations();
        view.setAlpha(0f);
        view.setTranslationY(-60f);
        view.setTranslationX(0f);
        view.setScaleX(0.1f);
        view.setScaleY(0.2f);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mEnterAnim = ValueAnimator.ofFloat(0f, 1f);
        mEnterAnim.setDuration(280);
        mEnterAnim.setInterpolator(new DecelerateInterpolator(1.0f));
        mEnterAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float decel = 1f - (1f - t) * (1f - t);
            float scaleX, scaleY;
            if (t < 0.35f) {
                float p = t / 0.35f;
                scaleX = 0.1f + 1.05f * p;
                scaleY = 0.2f + 0.3f * p;
            } else if (t < 0.7f) {
                float p = (t - 0.35f) / 0.35f;
                scaleX = 1.15f - 0.15f * p;
                scaleY = 0.5f + 0.55f * p;
            } else {
                float p = (t - 0.7f) / 0.3f;
                float bounce = (float) Math.sin(p * Math.PI) * 0.02f;
                scaleX = 1.0f + bounce;
                scaleY = 1.05f - 0.05f * p + bounce;
            }
            view.setAlpha(decel);
            view.setTranslationY(-60f * (1f - decel));
            view.setScaleX(scaleX);
            view.setScaleY(scaleY);
        });
        mEnterAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (mEnterAnim == null) return;
                mEnterAnim = null;
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
        });
        mEnterAnim.start();
    }

    private void startExitAnimation(final View view, final Runnable onEnd, final int exitDirection) {
        cancelAllAnimations();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mExitAnim = ValueAnimator.ofFloat(0f, 1f);
        mExitAnim.setDuration(180);
        mExitAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        mExitAnim.addUpdateListener(anim -> {
            float ease = (float) anim.getAnimatedValue();
            float realEase = ease * ease;
            view.setAlpha(1f - realEase);
            switch (exitDirection) {
                case 1: view.setTranslationY(-120f * realEase); view.setTranslationX(0f); break;
                case 2: view.setTranslationX(120f * realEase); view.setTranslationY(0f); break;
                default: view.setTranslationX(-120f * realEase); view.setTranslationY(0f); break;
            }
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
        cancelAllAnimations();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mBounceAnim = ValueAnimator.ofFloat(0f, 1f);
        mBounceAnim.setDuration(350);
        mBounceAnim.setInterpolator(null);
        mBounceAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float decay = (float) Math.exp(-6 * t);
            float oscillation = (float) Math.sin(t * Math.PI * 3.5);
            float offset = 22f * decay * oscillation * direction;
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
        if (mContext == null || mWindowManager == null) return;
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        if (isKeyguardLocked() || isStatusBarExpanded()) return;
        registerScreenReceiver();

        final String key = sbn.getKey();
        final Notification notification = sbn.getNotification();
        final PendingIntent contentIntent = notification.contentIntent;

        mHandler.post(() -> {
            try {
                Bundle extras = notification.extras;
                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = (bigText != null && bigText.length() > 0) ? bigText.toString() : text.toString();
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode() & 0x7FFFFFFF);

                if (key.equals(mCurrentKey) && mCurrentOverlay != null) {
                    if (newHash.equals(mCurrentContentHash)) return;
                }
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

                TextView readBtn = new TextView(mContext);
                readBtn.setText("已读");
                readBtn.setTextColor(0xFF64B5F6);
                readBtn.setTextSize(12);
                readBtn.setPadding(12, 4, 12, 4);
                LinearLayout.LayoutParams readLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                readLp.gravity = Gravity.CENTER_VERTICAL;
                readBtn.setLayoutParams(readLp);
                readBtn.setOnClickListener(v -> {
                    try {
                        PendingIntent deleteIntent = notification.deleteIntent;
                        if (deleteIntent != null) deleteIntent.send();
                    } catch (Exception ignored) {}
                    dismissOverlayAnimated(1);
                });
                contentContainer.addView(readBtn);

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

                mWindowManager.addView(root, params);
                mCurrentKey = key;
                mCurrentOverlay = root;
                XposedBridge.log(TAG + ": Shown: " + title);

                // 首次截屏+模糊
                updateBackground();
                startBackgroundUpdate();

                startEnterAnimation(contentContainer);
                mAutoDismissRunnable = () -> dismissOverlayAnimated(1);
                mHandler.postDelayed(mAutoDismissRunnable, AUTO_DISMISS_MS);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": showCustomHeadsUp error: " + t);
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
        if (mHandler == null) return;
        mHandler.post(() -> {
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
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Bitmap screenshot = null;
            try {
                screenshot = (Bitmap) XposedHelpers.callStaticMethod(scClass, "screenshot");
            } catch (Throwable t1) {
                try {
                    screenshot = (Bitmap) XposedHelpers.callStaticMethod(scClass, "screenshot", WIN_W, WIN_H);
                } catch (Throwable t2) {
                    try {
                        Object rect = android.graphics.Rect.class.getConstructor(int.class, int.class, int.class, int.class)
                            .newInstance(WIN_X, WIN_Y, WIN_X + WIN_W, WIN_Y + WIN_H);
                        screenshot = (Bitmap) XposedHelpers.callStaticMethod(scClass, "screenshot", rect);
                    } catch (Throwable t3) { return null; }
                }
            }
            if (screenshot != null) {
                int x = WIN_X, y = WIN_Y, w = WIN_W, h = WIN_H;
                if (x + w > screenshot.getWidth()) w = screenshot.getWidth() - x;
                if (y + h > screenshot.getHeight()) h = screenshot.getHeight() - y;
                if (w > 0 && h > 0) {
                    Bitmap cropped = Bitmap.createBitmap(screenshot, x, y, w, h);
                    screenshot.recycle();
                    return cropped;
                }
                return screenshot;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": captureScreenBackground failed: " + t);
        }
        return null;
    }

    private Bitmap fastBlur(Bitmap input) {
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
            return result;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fastBlur failed: " + t);
            return input;
        }
    }

    private void updateBackground() {
        if (mBgImageView == null || mCurrentOverlay == null || mCurrentOverlay.getParent() == null) return;
        if (mContentView != null && (mContentView.getTranslationX() != 0f || mContentView.getTranslationY() != 0f)) return;
        Bitmap screen = captureScreenBackground();
        if (screen == null) return;
        Bitmap blurred = fastBlur(screen);
        screen.recycle();
        if (blurred == null) return;
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
        stopBackgroundUpdate();
        mBgUpdateRunnable = () -> {
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) return;
            updateBackground();
            mHandler.postDelayed(mBgUpdateRunnable, BG_UPDATE_INTERVAL_MS);
        };
        mHandler.postDelayed(mBgUpdateRunnable, BG_UPDATE_INTERVAL_MS);
    }

    private void stopBackgroundUpdate() {
        if (mBgUpdateRunnable != null) {
            mHandler.removeCallbacks(mBgUpdateRunnable);
            mBgUpdateRunnable = null;
        }
    }

    private void removeOverlayImmediate() {
        cancelAllAnimations();
        stopBackgroundUpdate();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
        String keyToRemove = mCurrentKey;
        View rowViewSnapshot = mCurrentRowView;
        final View overlayToShield = mCurrentOverlay;
        removeSystemHeadsUpEntry(keyToRemove);
        removeSystemNotificationView(keyToRemove);
         if (rowViewSnapshot != null) {
            try { XposedHelpers.callMethod(rowViewSnapshot, "setHeadsUp", false); }
            catch (Throwable t1) {
                try { XposedHelpers.callMethod(rowViewSnapshot, "setHeadsUpAnimatingAway", true); }
                catch (Throwable ignored) {}
            }
        }
        if (overlayToShield != null) {
            try {
                overlayToShield.setAlpha(0f);
                if (mContentView != null) mContentView.setOnTouchListener(null);
            } catch (Throwable ignored) {}
            mHandler.postDelayed(() -> {
                try {
                    if (overlayToShield.getParent() != null) mWindowManager.removeView(overlayToShield);
                } catch (Throwable t) {
                    try { mWindowManager.removeViewImmediate(overlayToShield); } catch (Throwable ignored) {}
                }
            }, SHIELD_DELAY_MS);
        }
        if (mBlurredBgBitmap != null) {
            try { if (!mBlurredBgBitmap.isRecycled()) mBlurredBgBitmap.recycle(); } catch (Throwable ignored) {}
            mBlurredBgBitmap = null;
        }
        mBgImageView = null;
        mContentView = null;
        if (mGlassView != null) {
            mGlassView.stopAnimations();
            mGlassView = null;
        }
        if (keyToRemove != null) mLastDismissTime = SystemClock.elapsedRealtime();
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
        private final float mCornerRadius;
        private final boolean mIsDark;
        private final RectF mDrawRect;
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
