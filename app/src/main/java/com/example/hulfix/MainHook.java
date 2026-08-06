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
    private static final long USER_DISMISS_COOLDOWN_MS = 5000;

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": ====== HULFix Overlay v27 loaded ======");
        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());
        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
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
                        if (mCurrentOverlay != null) removeOverlayImmediate();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook expandNotificationsPanel skipped: " + t); }
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "setExpandedVisible", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        boolean visible = (boolean) param.args[0];
                        mIsPanelExpanded = visible;
                        if (visible && mCurrentOverlay != null) removeOverlayImmediate();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook setExpandedVisible skipped: " + t); }
            try {
                XposedHelpers.findAndHookMethod(statusBarClass, "makeExpandedVisible", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        if (mCurrentOverlay != null) removeOverlayImmediate();
                    }
                });
            } catch (Throwable t) { XposedBridge.log(TAG + ": hook makeExpandedVisible skipped: " + t); }
            try {
                Class<?> panelControllerClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.PanelViewController", lpparam.classLoader);
                XposedBridge.hookAllMethods(panelControllerClass, "onTrackingStarted", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = true;
                        if (mCurrentOverlay != null) removeOverlayImmediate();
                    }
                });
                XposedBridge.hookAllMethods(panelControllerClass, "onTrackingStopped", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        mIsPanelExpanded = false;
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
                        if (key.equals(mCurrentKey) && (now - mLastDismissTime) < COOLDOWN_MS) { param.setResult(null); return; }
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
                        if (key.equals(mCurrentKey) && (now - mLastDismissTime) < COOLDOWN_MS) { param.setResult(null); return; }
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
        if (mEnterAnim != null) { mEnterAnim.cancel(); mEnterAnim = null; }
        if (mExitAnim != null) { mExitAnim.cancel(); mExitAnim = null; }
        if (mBounceAnim != null) { mBounceAnim.cancel(); mBounceAnim = null; }
        if (mCurrentOverlay != null) {
            mCurrentOverlay.setAlpha(1f);
            mCurrentOverlay.setTranslationX(0f);
            mCurrentOverlay.setTranslationY(0f);
            mCurrentOverlay.setScaleX(1f);
            mCurrentOverlay.setScaleY(1f);
        }
    }

    private void startEnterAnimation(final View view) {
        mHandler.post(() -> {
            cancelAllAnimations();
            view.setAlpha(0f);
            view.setTranslationY(-40f);
            view.setTranslationX(0f);
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            mEnterAnim = ValueAnimator.ofFloat(0f, 1f);
            mEnterAnim.setDuration(160);
            mEnterAnim.setInterpolator(new DecelerateInterpolator(1.0f));
            mEnterAnim.addUpdateListener(anim -> {
                float ease = (float) anim.getAnimatedValue();
                view.setAlpha(ease);
                view.setTranslationY(-40f * (1f - ease));
                view.setScaleX(0.96f + 0.04f * ease);
                view.setScaleY(0.96f + 0.04f * ease);
            });
            mEnterAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { mEnterAnim = null; }
            });
            mEnterAnim.start();
        });
    }

    private void startExitAnimation(final View view, final Runnable onEnd, final boolean slideUpward) {
        cancelAllAnimations();
        mExitAnim = ValueAnimator.ofFloat(0f, 1f);
        mExitAnim.setDuration(128);
        mExitAnim.setInterpolator(new DecelerateInterpolator(1.0f));
        mExitAnim.addUpdateListener(anim -> {
            float ease = (float) anim.getAnimatedValue();
            float realEase = ease * ease;
            view.setAlpha(1f - realEase);
            if (slideUpward) view.setTranslationY(-80f * realEase);
            else view.setTranslationX(-80f * realEase);
        });
        mExitAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                mExitAnim = null;
                if (onEnd != null) onEnd.run();
            }
        });
        mExitAnim.start();
    }

    private void startBounceAnimation(final View view, final float direction) {
        cancelAllAnimations();
        mBounceAnim = ValueAnimator.ofFloat(0f, 1f);
        mBounceAnim.setDuration(300);
        mBounceAnim.setInterpolator(null);
        mBounceAnim.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float decay = (float) Math.exp(-5 * t);
            float oscillation = (float) Math.sin(t * Math.PI * 3);
            float offset = 18f * decay * oscillation * direction;
            view.setTranslationX(offset);
            view.setAlpha(1f);
        });
        mBounceAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                mBounceAnim = null;
                view.setTranslationX(0f);
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
                removeSystemHeadsUpEntry(key);
                Bundle extras = notification.extras;
                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = bigText.length() > 0 ? bigText.toString() : text.toString();
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode());

                if (key.equals(mCurrentKey) && mCurrentOverlay != null) {
                    if (newHash.equals(mCurrentContentHash)) return;
                }
                removeOverlayImmediate();
                mCurrentContentHash = newHash;

                boolean isDark = isDarkMode();
                int glassBaseColor = isDark ? 0x26000000 : 0x26FFFFFF;
                int edgeColor = isDark ? 0x30FFFFFF : 0x45FFFFFF;
                int topHighlightStart = isDark ? 0x20FFFFFF : 0x50FFFFFF;
                int bottomGlowEnd = isDark ? 0x10FFFFFF : 0x18FFFFFF;
                int textColorPrimary = isDark ? 0xFFFFFFFF : 0xFF000000;
                int textColorSecondary = isDark ? 0xFFCCCCCC : 0xFF333333;

                LinearLayout container = new LinearLayout(mContext);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setPadding(20, 14, 20, 14);
                container.setGravity(Gravity.CENTER_VERTICAL);

                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(28);
                bg.setColor(glassBaseColor);

                GradientDrawable topHighlight = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { topHighlightStart, 0x00FFFFFF });
                topHighlight.setShape(GradientDrawable.RECTANGLE);
                topHighlight.setCornerRadius(28);

                GradientDrawable bottomGlow = new GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[] { bottomGlowEnd, 0x00FFFFFF });
                bottomGlow.setShape(GradientDrawable.RECTANGLE);
                bottomGlow.setCornerRadius(28);

                GradientDrawable edgeGlow = new GradientDrawable();
                edgeGlow.setShape(GradientDrawable.RECTANGLE);
                edgeGlow.setCornerRadius(28);
                edgeGlow.setStroke(1, edgeColor);
                edgeGlow.setColor(0x00000000);

                GradientDrawable innerShadow = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] { 0x08000000, 0x00000000 });
                innerShadow.setShape(GradientDrawable.RECTANGLE);
                innerShadow.setCornerRadius(28);

                android.graphics.drawable.LayerDrawable glassBg =
                    new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] { bg, topHighlight, bottomGlow, edgeGlow, innerShadow });
                container.setBackground(glassBg);
                container.setElevation(12);

                ImageView iconView = new ImageView(mContext);
                android.graphics.drawable.Icon icon = notification.getSmallIcon();
                if (icon != null) iconView.setImageIcon(icon);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(44, 44);
                iconLp.gravity = Gravity.CENTER_VERTICAL;
                iconView.setLayoutParams(iconLp);
                container.addView(iconView);

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
                container.addView(textContainer);

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
                    dismissOverlayAnimated();
                });
                container.addView(readBtn);

                container.setOnTouchListener(new View.OnTouchListener() {
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

                                if (totalDy < -SWIPE_DESTROY_THRESHOLD && !isHorizontal) {
                                    dismissOverlayAnimated(); return true;
                                }
                                if (totalDx < -SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    dismissOverlayAnimated(); return true;
                                }
                                if (totalDx > SWIPE_DESTROY_THRESHOLD && isHorizontal) {
                                    dismissOverlayAnimated(); return true;
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
            Class<?> aoClass = Class.forName("android.app.ActivityOptions");
            Object ao = XposedHelpers.callStaticMethod(aoClass, "makeBasic");
            XposedHelpers.callMethod(ao, "setLaunchWindowingMode", 1);
            return (Bundle) XposedHelpers.callMethod(ao, "toBundle");
        } catch (Throwable t) { return null; }
    }

    private void expandStatusBar() {
        try {
            Object sbm = mContext.getSystemService("statusbar");
            if (sbm != null) {
                java.lang.reflect.Method expand = sbm.getClass().getMethod("expandNotificationsPanel");
                expand.invoke(sbm);
            }
        } catch (Throwable t) {}
    }

    private void dismissOverlayAnimated() { dismissOverlayAnimated(false); }

    private void dismissOverlayAnimated(final boolean slideUpward) {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mAutoDismissRunnable != null) {
                mHandler.removeCallbacks(mAutoDismissRunnable);
                mAutoDismissRunnable = null;
            }
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) {
                removeOverlayImmediate(); return;
            }
            startExitAnimation(mCurrentOverlay, () -> removeOverlayImmediate(), slideUpward);
        });
    }

    private void hideOverlayOnly() {
        cancelAllAnimations();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
        if (mCurrentOverlay != null) {
            mCurrentOverlay.setAlpha(1f);
            mCurrentOverlay.setTranslationX(0f);
            mCurrentOverlay.setTranslationY(0f);
            mCurrentOverlay.setScaleX(1f);
            mCurrentOverlay.setScaleY(1f);
        }
        if (mCurrentRowView != null) {
            try { XposedHelpers.callMethod(mCurrentRowView, "setHeadsUp", false); }
            catch (Throwable t1) {
                try { XposedHelpers.callMethod(mCurrentRowView, "setHeadsUpAnimatingAway", true); }
                catch (Throwable ignored) {}
            }
        }
        if (mCurrentOverlay != null) {
            try {
                if (mCurrentOverlay.getParent() != null) mWindowManager.removeView(mCurrentOverlay);
            } catch (Throwable t) {
                try { mWindowManager.removeViewImmediate(mCurrentOverlay); } catch (Throwable ignored) {}
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
                overlayToShield.setOnTouchListener((v, event) -> true);
            } catch (Throwable ignored) {}
            mHandler.postDelayed(() -> {
                try {
                    if (overlayToShield.getParent() != null) mWindowManager.removeView(overlayToShield);
                } catch (Throwable t) {
                    try { mWindowManager.removeViewImmediate(overlayToShield); } catch (Throwable ignored) {}
                }
            }, SHIELD_DELAY_MS);
        }
        if (keyToRemove != null) mLastDismissTime = SystemClock.elapsedRealtime();
        mUserDismissedKey = keyToRemove;
        mUserDismissTime = SystemClock.elapsedRealtime();
        mCurrentKey = null;
        mCurrentRowView = null;
        mCurrentOverlay = null;
        mCurrentContentHash = null;
    }
}
