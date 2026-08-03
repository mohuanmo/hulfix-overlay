package com.example.hulfix;

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
    private static final long AUTO_DISMISS_MS = 6000;
    private static final long COOLDOWN_MS = 3000;          // 【改】从 7000 缩短到 3000
    private static final long NOTIFICATION_MAX_AGE_MS = 3000; // 【新增】新鲜通知阈值

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
    private String mCurrentContentHash = null;             // 【新增】当前显示内容哈希
    private Runnable mAutoDismissRunnable = null;
    private long mLastDismissTime = 0;

    /* ===== 手动动画 Runnable ===== */
    private Runnable mEnterAnimRunnable = null;
    private Runnable mExitAnimRunnable = null;
    private Runnable mBounceAnimRunnable = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v18 loaded ======");

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
    }

    /* ================================================================ */
    /*  【新增】判断通知是否新鲜（3 秒内），防止旧通知借尸还魂            */
    /* ================================================================ */
    private boolean isFreshNotification(StatusBarNotification sbn) {
        long age = System.currentTimeMillis() - sbn.getPostTime();
        return age <= NOTIFICATION_MAX_AGE_MS;
    }

    /* ================================================================ */
    /*  Hook 1                                                          */
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

                            // 冷却检测
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

                            // 【新增】新鲜度过滤：只处理 3 秒内的新通知
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
    /*  Hook 2                                                          */
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

                            // v17 保护逻辑保留
                            if (mCurrentKey == null && mCurrentRowView == rowView) {
                                return;
                            }

                            if (animatingAway) return;

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

                            // 【新增】新鲜度过滤：只处理 3 秒内的新通知
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
    /*  清理系统 Heads-Up 状态                                          */
    /* ================================================================ */
    private void clearSystemHeadsUp() {
        if (mCurrentRowView != null) {
            try {
                XposedHelpers.callMethod(mCurrentRowView, "setHeadsUp", false);
                XposedBridge.log(TAG + ": setHeadsUp(false) called");
            } catch (Throwable t1) {
                try {
                    XposedHelpers.callMethod(mCurrentRowView, "setHeadsUpAnimatingAway", true);
                    XposedBridge.log(TAG + ": setHeadsUpAnimatingAway(true) called");
                } catch (Throwable ignored) {}
            }
            mCurrentRowView = null;
        }
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

        mEnterAnimRunnable = new Runnable() {
            int frame = 0;
            final int totalFrames = 18;
            @Override
            public void run() {
                if (view.getParent() == null) {
                    mEnterAnimRunnable = null;
                    return;
                }
                frame++;
                float fraction = Math.min(1f, (float) frame / totalFrames);
                float decel = 1f - (1f - fraction) * (1f - fraction);
                view.setAlpha(decel);
                view.setTranslationY(-40f * (1f - decel));
                if (frame < totalFrames) {
                    mHandler.postDelayed(this, 16);
                } else {
                    mEnterAnimRunnable = null;
                }
            }
        };
        mHandler.post(mEnterAnimRunnable);
    }

    /* ================================================================ */
    /*  出场动画                                                        */
    /* ================================================================ */
    private void startExitAnimation(final View view, final Runnable onEnd) {
        cancelAllAnimations();
        final float startAlpha = view.getAlpha();
        final float startTy = view.getTranslationY();
        final float startTx = view.getTranslationX();

        mExitAnimRunnable = new Runnable() {
            int frame = 0;
            final int totalFrames = 13;
            @Override
            public void run() {
                if (view.getParent() == null) {
                    mExitAnimRunnable = null;
                    if (onEnd != null) onEnd.run();
                    return;
                }
                frame++;
                float fraction = Math.min(1f, (float) frame / totalFrames);
                float accel = fraction * fraction;
                view.setAlpha(startAlpha * (1f - accel));
                view.setTranslationY(startTy - 60f * accel);
                view.setTranslationX(startTx);
                if (frame < totalFrames) {
                    mHandler.postDelayed(this, 16);
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
        final float startTx = view.getTranslationX();
        final float startTy = view.getTranslationY();
        final float startAlpha = view.getAlpha();

        mBounceAnimRunnable = new Runnable() {
            int frame = 0;
            final int totalFrames = 15;
            @Override
            public void run() {
                if (view.getParent() == null) {
                    mBounceAnimRunnable = null;
                    return;
                }
                frame++;
                float fraction = Math.min(1f, (float) frame / totalFrames);
                float overshoot = (float) Math.sin(fraction * Math.PI) * 0.15f * (1f - fraction);
                float eased = fraction + overshoot;
                view.setTranslationX(startTx * (1f - eased));
                view.setTranslationY(startTy * (1f - eased));
                view.setAlpha(startAlpha + (1f - startAlpha) * fraction);
                if (frame < totalFrames) {
                    mHandler.postDelayed(this, 16);
                } else {
                    mBounceAnimRunnable = null;
                    view.setTranslationX(0);
                    view.setTranslationY(0);
                    view.setAlpha(1f);
                }
            }
        };
        mHandler.post(mBounceAnimRunnable);
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

        mHandler.post(() -> {
            try {
                Notification notification = sbn.getNotification();
                Bundle extras = notification.extras;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = bigText.length() > 0 ? bigText.toString() : text.toString();
                String newContent = title + "|" + content;
                String newHash = Integer.toHexString(newContent.hashCode());

                // 【新增】内容变化检测
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

                // ===== 点击跳转 =====
                PendingIntent contentIntent = notification.contentIntent;
                container.setOnClickListener(v -> {
                    try {
                        if (contentIntent != null) {
                            contentIntent.send();
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": PendingIntent send failed: " + e);
                    }
                    dismissOverlayAnimated();
                });

                // ===== 方向锁定滑动 =====
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

                // 入场动画
                startEnterAnimation(container);

                // 自动消失
                mAutoDismissRunnable = () -> dismissOverlayAnimated();
                mHandler.postDelayed(mAutoDismissRunnable, AUTO_DISMISS_MS);

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

    /* ===== 立即移除 ===== */
    private void removeOverlayImmediate() {
        cancelAllAnimations();
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
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
        if (mCurrentKey != null) {
            mLastDismissTime = SystemClock.elapsedRealtime();
        }
        clearSystemHeadsUp();
        cleanupOverlayState();
    }

    private void cleanupOverlayState() {
        mCurrentOverlay = null;
        mCurrentKey = null;
        mCurrentContentHash = null;  // 【新增】清理内容哈希
    }
}
