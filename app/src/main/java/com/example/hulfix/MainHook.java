package com.example.hulfix;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.HashMap;
import java.util.Map;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "HULFix";
    private static final long AUTO_DISMISS_MS = 5000;

    /* ===== 窗口位置 ===== */
    private static final int WIN_X = 1386;
    private static final int WIN_Y = 77;
    private static final int WIN_W = 673;
    private static final int WIN_H = 119;

    private Context mContext;
    private WindowManager mWindowManager;
    private Handler mHandler;
    private final Map<String, View> mActiveOverlays = new HashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": ====== HULFix Overlay v9 loaded ======");

        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        hookHeadsUpIsVisible(lpparam);
        hookAnimatingAway(lpparam);
        hookNotificationEntry(lpparam);
    }

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

                            String key = sbn.getKey();
                            XposedBridge.log(TAG + ": HeadsUpIsVisible triggered, key=" + key);

                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            showCustomHeadsUp(sbn);

                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setHeadsUpIsVisible hook error: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked setHeadsUpIsVisible");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setHeadsUpIsVisible hook failed: " + t);
        }
    }

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

                            String key = sbn.getKey();
                            if (mActiveOverlays.containsKey(key)) return;

                            XposedBridge.log(TAG + ": AnimatingAway(false) + landscape, key=" + key);

                            param.setResult(null);

                            if (mContext == null) {
                                mContext = (Context) XposedHelpers.callMethod(rowView, "getContext");
                                mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                            }

                            showCustomHeadsUp(sbn);

                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": setHeadsUpAnimatingAway hook error: " + t);
                        }
                    }
                }
            );
            XposedBridge.log(TAG + ": Hooked setHeadsUpAnimatingAway");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setHeadsUpAnimatingAway hook failed: " + t);
        }
    }

    private void hookNotificationEntry(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> entryClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                lpparam.classLoader
            );
            XposedBridge.log(TAG + ": Found NotificationEntry class");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NotificationEntry not found");
        }
    }

    /* ===== Monet 取色：获取系统壁纸主色，失败回退白色 ===== */
    private int getMonetColor() {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(mContext);
            Object colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null) {
                Object primary = XposedHelpers.callMethod(colors, "getPrimaryColor");
                if (primary != null) {
                    int argb = (int) XposedHelpers.callMethod(primary, "toArgb");
                    XposedBridge.log(TAG + ": Monet color=" + Integer.toHexString(argb));
                    return argb;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Monet get failed: " + t);
        }
        return 0xFFFFFFFF; // 回退白色
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

    private void showCustomHeadsUp(StatusBarNotification sbn) {
        if (mContext == null || mWindowManager == null) return;
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }

        final String key = sbn.getKey();

        mHandler.post(() -> {
            try {
                removeOverlayInternal(key);

                Notification notification = sbn.getNotification();
                Bundle extras = notification.extras;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT, "");
                CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "");
                String content = bigText.length() > 0 ? bigText.toString() : text.toString();

                // ===== 背景颜色：Monet 取色，失败回退白色 =====
                int monetColor = getMonetColor();
                int bgColor = 0xE6FFFFFF; // 默认 90% 白
                if (monetColor != 0xFFFFFFFF) {
                    // 使用 Monet 颜色，90% 不透明度
                    bgColor = (0xE6000000) | (monetColor & 0xFFFFFF);
                }

                LinearLayout container = new LinearLayout(mContext);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setPadding(18, 12, 18, 12);

                // 圆角背景
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(24);
                bg.setColor(bgColor);
                bg.setStroke(1, 0x33FFFFFF);
                container.setBackground(bg);
                container.setElevation(12);

                ImageView iconView = new ImageView(mContext);
                android.graphics.drawable.Icon icon = notification.getSmallIcon();
                if (icon != null) {
                    iconView.setImageIcon(icon);
                }
                iconView.setLayoutParams(new LinearLayout.LayoutParams(40, 40));
                container.addView(iconView);

                LinearLayout textContainer = new LinearLayout(mContext);
                textContainer.setOrientation(LinearLayout.VERTICAL);
                textContainer.setPadding(12, 0, 0, 0);

                TextView titleView = new TextView(mContext);
                titleView.setText(title);
                titleView.setTextColor(0xFF000000);
                titleView.setTextSize(13);
                titleView.setMaxLines(1);
                textContainer.addView(titleView);

                TextView contentView = new TextView(mContext);
                contentView.setText(content);
                contentView.setTextColor(0xFF444444);
                contentView.setTextSize(11);
                contentView.setMaxLines(1);
                textContainer.addView(contentView);

                container.addView(textContainer);

                PendingIntent contentIntent = notification.contentIntent;
                container.setOnClickListener(v -> {
                    try {
                        if (contentIntent != null) {
                            contentIntent.send();
                        }
                    } catch (Exception e) {
                        XposedBridge.log(TAG + ": PendingIntent send failed: " + e);
                    }
                    removeOverlay(key);
                });

                // ===== 跟手触摸手势 + 下滑状态栏 =====
                container.setOnTouchListener(new View.OnTouchListener() {
                    float startX, startY;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startX = event.getRawX();
                                startY = event.getRawY();
                                return true;

                            case MotionEvent.ACTION_MOVE:
                                float dx = event.getRawX() - startX;
                                float dy = event.getRawY() - startY;
                                v.setTranslationX(dx);
                                v.setTranslationY(dy);
                                // 距离越远越透明
                                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                                float alpha = Math.max(0.5f, 1f - dist / 300f);
                                v.setAlpha(alpha);
                                return true;

                            case MotionEvent.ACTION_UP:
                                float totalDx = event.getRawX() - startX;
                                float totalDy = event.getRawY() - startY;
                                boolean isHorizontal = Math.abs(totalDx) > Math.abs(totalDy);

                                // 上滑销毁
                                if (totalDy < -100 && !isHorizontal) {
                                    removeOverlay(key);
                                    return true;
                                }
                                // 左滑销毁
                                if (totalDx < -100 && isHorizontal) {
                                    removeOverlay(key);
                                    return true;
                                }
                                // 下滑：展开状态栏 + 销毁
                                if (totalDy > 150 && !isHorizontal) {
                                    expandStatusBar();
                                    removeOverlay(key);
                                    return true;
                                }
                                // 未超阈值：回弹
                                v.animate()
                                    .translationX(0)
                                    .translationY(0)
                                    .alpha(1f)
                                    .setDuration(200)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                                    .start();
                                return true;
                        }
                        return false;
                    }
                });

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
                mActiveOverlays.put(key, container);

                XposedBridge.log(TAG + ": Custom Heads-Up shown: " + title);

                // 入场动画
                container.setAlpha(0f);
                container.setTranslationY(-30);
                container.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

                mHandler.postDelayed(() -> removeOverlay(key), AUTO_DISMISS_MS);

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

    private void removeOverlay(String key) {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        mHandler.post(() -> removeOverlayInternal(key));
    }

    private void removeOverlayInternal(String key) {
        try {
            View view = mActiveOverlays.remove(key);
            if (view != null && view.getParent() != null) {
                mWindowManager.removeView(view);
                XposedBridge.log(TAG + ": Overlay removed: " + key);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": removeOverlay error: " + t);
        }
    }
}
