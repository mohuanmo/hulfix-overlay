package com.example.hulfix;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
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
    private static final long AUTO_DISMISS_MS = 5000;
    private static final long ANIM_DURATION_MS = 220;

    /* ===== 窗口固定位置（基于用户设备像素坐标） ===== */
    // 左上(1390,77)  右上(2059,77)
    // 左下(1386,196) 右下(2057,196)
    // 取平均外接矩形
    private static final int WIN_X = 1388;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 670;   // ~2058-1388
    private static final int WIN_H = 119;   // 196-77

    /* ===== 手势阈值 ===== */
    private static final float SWIPE_DOWN_THRESHOLD = 120f;   // 下拉状态栏（较大，防误触）
    private static final float SWIPE_UP_THRESHOLD = 80f;      // 上滑移除
    private static final float SWIPE_HORIZONTAL_THRESHOLD = 80f; // 左/右滑移除

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;

    // 单例：只保留最新一条通知
    private String mCurrentKey = null;
    private View mCurrentOverlay = null;
    private Runnable mCurrentDismissRunnable = null;
    private boolean mIsAnimating = false;

    // Monet 颜色缓存
    private int mMonetBgColor = -1;
    private int mMonetStrokeColor = -1;
    private boolean mMonetInitFailed = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v4 loaded ======");

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
    }

    /* ================================================================ */
    /*  Hook 点 1：系统决定显示 Heads-Up 时触发                        */
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

                            // 【核心过滤】排除常驻/静音通知，只保留真正该弹的
                            if (!shouldShowHeadsUp(sbn, rowView)) {
                                XposedBridge.log(TAG + ": Filtered: " + sbn.getKey());
                                return;
                            }

                            String key = sbn.getKey();

                            // 阻止系统自己显示（修复横屏 Bug）
                            param.setResult(null);

                            // 去重：已经在显示的同一条通知，跳过
                            if (key.equals(mCurrentKey)) return;

                            XposedBridge.log(TAG + ": New HeadsUp: " + key);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

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
    /*  Hook 点 2：动画结束后的兜底触发                                */
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
                            if (animatingAway) return;

                            View rowView = (View) param.thisObject;
                            if (!isLandscape(rowView)) return;

                            StatusBarNotification sbn = getSbnFromRow(rowView);
                            if (sbn == null) return;

                            if (!shouldShowHeadsUp(sbn, rowView)) return;

                            String key = sbn.getKey();
                            if (key.equals(mCurrentKey)) return;

                            XposedBridge.log(TAG + ": AnimatingAway fallback: " + key);

                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

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

    /* ================================================================ */
    /*  通知过滤：排除常驻/静音，只让真正该弹 Heads-Up 的消息通过       */
    /* ================================================================ */
    private boolean shouldShowHeadsUp(StatusBarNotification sbn, View rowView) {
        Notification n = sbn.getNotification();
        int flags = n.flags;

        // 1. 排除常驻通知（前台服务、正在进行的事件）
        if (sbn.isOngoing()) return false;
        if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;

        // 2. 排除"只提醒一次"且非高优先级的通知（通常是静音的）
        if ((flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
            if (n.priority < Notification.PRIORITY_HIGH && n.fullScreenIntent == null) {
                return false;
            }
        }

        // 3. 排除不可清除的低优先级通知（系统级常驻）
        if (!sbn.isClearable() && n.priority < Notification.PRIORITY_HIGH) {
            return false;
        }

        // 4. SystemUI 内部已标记为 Heads-Up
        try {
            Boolean isHeadsUp = (Boolean) XposedHelpers.callMethod(rowView, "isHeadsUp");
            if (isHeadsUp != null && isHeadsUp) return true;
        } catch (Throwable ignored) {}

        // 5. 高优先级
        if (n.priority >= Notification.PRIORITY_HIGH) return true;

        // 6. 重要类别
        String cat = n.category;
        if (Notification.CATEGORY_CALL.equals(cat) ||
            Notification.CATEGORY_ALARM.equals(cat) ||
            Notification.CATEGORY_MESSAGE.equals(cat) ||
            Notification.CATEGORY_EVENT.equals(cat) ||
            Notification.CATEGORY_REMINDER.equals(cat)) {
            return true;
        }

        // 7. 全屏意图（来电、闹钟）
        if (n.fullScreenIntent != null) return true;

        // 8. 高优先级 flag
        if ((n.flags & 0x00000080) != 0) return true;

        // 兜底：系统已经调用 setHeadsUpIsVisible，通常意味着应该显示
        return true;
    }

    /* ================================================================ */
    /*  反射获取 StatusBarNotification                                  */
    /* ================================================================ */
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
    /*  Monet 颜色初始化：跟随系统壁纸/主题色调                         */
    /* ================================================================ */
    private void initMonetColors() {
        if (mMonetBgColor != -1 || mMonetInitFailed) return;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(mContext);
            WallpaperColors colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null && colors.getPrimaryColor() != null) {
                int primary = colors.getPrimaryColor().toArgb();
                mMonetBgColor = mixWithWhite(primary, 0.10f);   // 10% 主色 + 90% 白
                mMonetStrokeColor = mixWithWhite(primary, 0.22f); // 22% 主色 + 78% 白
                XposedBridge.log(TAG + ": Monet color from wallpaper: " + Integer.toHexString(primary));
                return;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": WallpaperColors failed: " + t);
        }

        // 回退：尝试系统主题 colorAccent
        try {
            TypedValue tv = new TypedValue();
            mContext.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true);
            mMonetBgColor = mixWithWhite(tv.data, 0.10f);
            mMonetStrokeColor = mixWithWhite(tv.data, 0.22f);
            XposedBridge.log(TAG + ": Monet color from accent: " + Integer.toHexString(tv.data));
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": colorAccent failed: " + t);
        }

        // 最终回退：纯白
        mMonetBgColor = 0xFFFFFFFF;
        mMonetStrokeColor = 0xFFE8E8E8;
        mMonetInitFailed = true;
        XposedBridge.log(TAG + ": Monet fallback to white");
    }

    private int mixWithWhite(int color, float ratio) {
        int r = (int) (Color.red(color) * ratio + 255 * (1 - ratio));
        int g = (int) (Color.green(color) * ratio + 255 * (1 - ratio));
        int b = (int) (Color.blue(color) * ratio + 255 * (1 - ratio));
        return Color.rgb(
            Math.min(255, Math.max(0, r)),
            Math.min(255, Math.max(0, g)),
            Math.min(255, Math.max(0, b))
        );
    }

    /* ================================================================ */
    /*  下拉状态栏（直接调用系统服务，不透传触摸事件）                  */
    /* ================================================================ */
    private void expandNotificationsPanel() {
        try {
            Object sbservice = mContext.getSystemService("statusbar");
            if (sbservice == null) return;
            Class<?> clazz = Class.forName("android.app.StatusBarManager");
            java.lang.reflect.Method expand = clazz.getMethod("expandNotificationsPanel");
            expand.invoke(sbservice);
            XposedBridge.log(TAG + ": Expanded notification panel");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": expandNotificationsPanel failed: " + t);
        }
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
                // 新通知来时，旧通知直接替换
                removeCurrentOverlayInternal(false);

                // 初始化 Monet 颜色（首次显示时）
                initMonetColors();

                Notification notification = sbn.getNotification();
                Bundle extras = notification.extras;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = (bigText != null && bigText.length() > 0)
                    ? bigText.toString()
                    : (text != null ? text.toString() : "");

                // ==================== 根容器 ====================
                LinearLayout container = new LinearLayout(mContext);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setPadding(18, 12, 18, 12);
                container.setGravity(Gravity.CENTER_VERTICAL);

                // Monet 背景：圆角 + 淡色
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(24);
                bg.setColor(mMonetBgColor);
                bg.setStroke(1, mMonetStrokeColor);
                container.setBackground(bg);
                container.setElevation(12);

                // ==================== 图标 ====================
                ImageView iconView = new ImageView(mContext);
                android.graphics.drawable.Icon icon = notification.getSmallIcon();
                if (icon != null) {
                    iconView.setImageIcon(icon);
                }
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(40, 40);
                iconLp.gravity = Gravity.CENTER_VERTICAL;
                iconView.setLayoutParams(iconLp);
                container.addView(iconView);

                // ==================== 文字区域（黑色文字）====================
                LinearLayout textContainer = new LinearLayout(mContext);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                );
                textLp.setMargins(12, 0, 0, 0);
                textLp.gravity = Gravity.CENTER_VERTICAL;
                textContainer.setLayoutParams(textLp);

                // 标题：黑色，13sp，单行省略
                TextView titleView = new TextView(mContext);
                titleView.setText(title);
                titleView.setTextColor(0xFF000000);
                titleView.setTextSize(13);
                titleView.setMaxLines(1);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(titleView);

                // 内容：黑色（稍浅），11sp，单行省略
                TextView contentView = new TextView(mContext);
                contentView.setText(content);
                contentView.setTextColor(0xFF222222);
                contentView.setTextSize(11);
                contentView.setMaxLines(1);
                contentView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textContainer.addView(contentView);

                container.addView(textContainer);

                // ==================== 点击跳转 ====================
                PendingIntent contentIntent = notification.contentIntent;
                container.setOnClickListener(v -> {
                    if (mIsAnimating) return;
                    try {
                        if (contentIntent != null) {
                            contentIntent.send();
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": PendingIntent send failed: " + e);
                    }
                    animateRemove(0, -1); // 点击后轻微上滑消失
                });

                // ==================== 触摸手势（跟手 + 动画）====================
                container.setOnTouchListener(new View.OnTouchListener() {
                    float startRawX, startRawY;
                    boolean isClick = true;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (mIsAnimating) return true;

                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startRawX = event.getRawX();
                                startRawY = event.getRawY();
                                isClick = true;
                                // 按下反馈：轻微缩小
                                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float moveDx = event.getRawX() - startRawX;
                                float moveDy = event.getRawY() - startRawY;
                                // 【跟手动画】实时跟随手指
                                v.setTranslationX(moveDx);
                                v.setTranslationY(moveDy);
                                if (Math.abs(moveDx) > 15 || Math.abs(moveDy) > 15) {
                                    isClick = false;
                                }
                                return true;

                            case MotionEvent.ACTION_UP:
                                float upDx = event.getRawX() - startRawX;
                                float upDy = event.getRawY() - startRawY;

                                // 恢复缩放
                                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();

                                // === 手势判断 ===
                                // 下滑 > 120px → 直接下拉状态栏（不透传）
                                if (upDy > SWIPE_DOWN_THRESHOLD && upDy > Math.abs(upDx)) {
                                    removeCurrentOverlay();
                                    expandNotificationsPanel();
                                    return true;
                                }

                                // 上滑 → 移除
                                if (upDy < -SWIPE_UP_THRESHOLD && Math.abs(upDy) > Math.abs(upDx)) {
                                    animateRemove(0, -1);
                                    return true;
                                }

                                // 右滑 → 移除
                                if (upDx > SWIPE_HORIZONTAL_THRESHOLD && upDx > Math.abs(upDy)) {
                                    animateRemove(1, 0);
                                    return true;
                                }

                                // 左滑 → 移除
                                if (upDx < -SWIPE_HORIZONTAL_THRESHOLD && Math.abs(upDx) > Math.abs(upDy)) {
                                    animateRemove(-1, 0);
                                    return true;
                                }

                                // 点击
                                if (isClick) {
                                    v.performClick();
                                    return true;
                                }

                                // 其他情况 → 回弹原位
                                v.animate()
                                    .translationX(0)
                                    .translationY(0)
                                    .setDuration(200)
                                    .setInterpolator(new DecelerateInterpolator())
                                    .start();
                                return true;
                        }
                        return false;
                    }
                });

                // ==================== 窗口参数 ====================
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WIN_W,
                    WIN_H,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = WIN_X;
                params.y = WIN_Y;

                mWindowManager.addView(container, params);

                mCurrentKey = key;
                mCurrentOverlay = container;

                XposedBridge.log(TAG + ": Shown: " + title);

                // 入场动画：从上方滑入 + 淡入
                container.setAlpha(0f);
                container.setTranslationY(-30);
                container.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

                // 5 秒后自动消失
                mCurrentDismissRunnable = () -> animateRemove(0, -1);
                mHandler.postDelayed(mCurrentDismissRunnable, AUTO_DISMISS_MS);

            } catch (Throwable t) {
                XposedBridge.log(TAG + ": showCustomHeadsUp error: " + t);
            }
        });
    }

    /* ================================================================ */
    /*  带动画的移除（支持方向）                                        */
    /* ================================================================ */
    private void animateRemove(int dirX, int dirY) {
        if (mHandler == null) return;
        mHandler.post(() -> {
            if (mCurrentOverlay == null || mCurrentOverlay.getParent() == null) return;
            if (mIsAnimating) return;
            mIsAnimating = true;

            // 取消自动消失定时器
            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }

            // 计算飞出目标（基于当前 translation）
            float currentTx = mCurrentOverlay.getTranslationX();
            float currentTy = mCurrentOverlay.getTranslationY();
            float targetX = currentTx + dirX * 350;
            float targetY = currentTy + dirY * 350;

            mCurrentOverlay.animate()
                .alpha(0f)
                .translationX(targetX)
                .translationY(targetY)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    removeOverlayViewOnly();
                    mIsAnimating = false;
                })
                .start();
        });
    }

    /* ================================================================ */
    /*  外部调用：立即移除（无动画）                                    */
    /* ================================================================ */
    private void removeCurrentOverlay() {
        if (mHandler == null) return;
        mHandler.post(() -> removeCurrentOverlayInternal(false));
    }

    /* ================================================================ */
    /*  内部移除                                                        */
    /* ================================================================ */
    private void removeCurrentOverlayInternal(boolean waitAnimation) {
        try {
            if (mCurrentDismissRunnable != null) {
                mHandler.removeCallbacks(mCurrentDismissRunnable);
                mCurrentDismissRunnable = null;
            }
            if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                if (mIsAnimating && waitAnimation) return;
                mWindowManager.removeView(mCurrentOverlay);
                XposedBridge.log(TAG + ": Removed: " + mCurrentKey);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": removeOverlay error: " + t);
        } finally {
            mCurrentOverlay = null;
            mCurrentKey = null;
            mIsAnimating = false;
        }
    }

    private void removeOverlayViewOnly() {
        try {
            if (mCurrentOverlay != null && mCurrentOverlay.getParent() != null) {
                mWindowManager.removeView(mCurrentOverlay);
            }
        } catch (Throwable ignored) {}
        mCurrentOverlay = null;
        mCurrentKey = null;
    }
}
