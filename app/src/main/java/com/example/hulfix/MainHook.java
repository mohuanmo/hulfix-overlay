package com.example.hulfix;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.view.View;

import com.example.hulfix.overlay.OverlayController;
import com.example.hulfix.overlay.OverlayControllerHolder;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * HULFix Xposed 入口。
 *
 * <p>本类只负责"在哪些位置挂钩"，全部业务逻辑（冷却引擎 / 消息台账去重 / 场景检测 /
 * 样式渲染 / 动画 / 手势）在 {@link OverlayController} 中。</p>
 *
 * <p>SystemUI 内部同一条通知会流经多个入口（NotifPipeline / NotificationListener /
 * NotificationEntry 构造与 update ...），因此 hook 数量刻意多于必要值：重复触发由
 * {@link com.example.hulfix.core.MessageLedger} 的去重逻辑兜底，这是"一条消息只弹一次"
 * 的第一道（也是主）防线。</p>
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "HULFix";

    /**
     * 控制器惰性持有：Xposed 在 zygote fork 阶段就 {@code Class.newInstance()} 本入口类，
     * 此刻主 Looper 尚未就绪。若在此处直接 {@code OverlayControllerHolder.get()} 构造控制器，
     * 旧版会触发 {@code ExceptionInInitializerError} 让整个模块类加载失败、钩子零挂载
     * （表现为"模块完全没反应"）。改为首次访问时才取单例——那一刻必然已在 SystemUI 的通知 / UI 流程中。
     */
    private volatile OverlayController mController;

    private OverlayController controller() {
        OverlayController c = mController;
        if (c == null) {
            synchronized (this) {
                c = mController;
                if (c == null) {
                    c = OverlayControllerHolder.get();
                    mController = c;
                }
            }
        }
        return c;
    }

    private void hookAllMethodsCompat(Class<?> clazz, String methodName, XC_MethodHook callback) {
        Class<?> currentClass = clazz;
        while (currentClass != null) {
            for (java.lang.reflect.Method method : currentClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    try {
                        XposedBridge.hookMethod(method, callback);
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": ====== HULFix Overlay v33 (bounded blur + settings json channel) loaded ======");

        // 1. 拿到 SystemUI Context → 初始化控制器（设置仓库 / 场景检测 / 广播）
        try {
            Class<?> appClass = XposedHelpers.findClass("android.app.Application", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(appClass, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    controller().init((Context) param.args[0]);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook Application.attach: " + t);
        }

        // 2. 面板展开状态（供场景检测：通知栏展开时不弹）
        hookPanelExpansion(lpparam);

        // 3. 通知入口（多路兼容 + 去重兜底）
        hookNotificationEntries(lpparam);
    }

    private void hookPanelExpansion(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> csClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.CentralSurfacesImpl", lpparam.classLoader);
            hookAllMethodsCompat(csClass, "expandNotificationsPanel", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    controller().sceneDetector().setPanelExpanded(true);
                }
            });
            hookAllMethodsCompat(csClass, "collapsePanels", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    controller().sceneDetector().setPanelExpanded(false);
                }
            });
            hookAllMethodsCompat(csClass, "animateCollapsePanels", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    controller().sceneDetector().setPanelExpanded(false);
                }
            });
            try {
                XposedHelpers.findAndHookMethod(csClass, "setExpandedFraction", float.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                                float fraction = (float) param.args[0];
                                controller().sceneDetector().setPanelExpanded(fraction > 0.05f);
                            }
                        });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
        }
    }

    private void hookNotificationEntries(XC_LoadPackage.LoadPackageParam lpparam) {
        // A. 各版本 SystemUI 的通知添加入口（逐个尝试，命中即用）
        String[] classNames = {
                "com.android.systemui.statusbar.notification.NotificationEntryManager",
                "com.android.systemui.statusbar.notification.NotifPipeline",
                "com.android.systemui.statusbar.notification.collection.NotifCollection",
                "com.android.systemui.statusbar.notification.NotificationListener",
                "com.android.systemui.statusbar.phone.CentralSurfacesImpl"
        };
        for (String className : classNames) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);
                hookAllMethodsCompat(clazz, "addNotification", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        StatusBarNotification sbn = extractSbnFromArgs(param.args);
                        if (sbn != null) controller().offer(sbn);
                    }
                });
                hookAllMethodsCompat(clazz, "removeNotification", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        StatusBarNotification sbn = extractSbnFromArgs(param.args);
                        if (sbn != null) controller().onNotificationRemoved(sbn);
                    }
                });
                XposedBridge.log(TAG + ": hooked add/removeNotification on " + className);
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": " + className + " not found: " + t);
            }
        }

        // B. NotificationListener.onNotificationPosted（类内 + 父类 NotificationListenerService）
        for (String cn : new String[]{
                "com.android.systemui.statusbar.notification.NotificationListener",
                "android.service.notification.NotificationListenerService"}) {
            try {
                Class<?> clazz = XposedHelpers.findClass(cn, lpparam.classLoader);
                hookAllMethodsCompat(clazz, "onNotificationPosted", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        StatusBarNotification sbn = extractSbnFromArgs(param.args);
                        if (sbn != null) controller().offer(sbn);
                    }
                });
                hookAllMethodsCompat(clazz, "onNotificationRemoved", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        StatusBarNotification sbn = extractSbnFromArgs(param.args);
                        if (sbn != null) controller().onNotificationRemoved(sbn);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // C. NotifCollection.onNotificationPosted
        try {
            Class<?> notifCollClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.collection.NotifCollection",
                    lpparam.classLoader);
            hookAllMethodsCompat(notifCollClass, "onNotificationPosted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    StatusBarNotification sbn = extractSbnFromArgs(param.args);
                    if (sbn != null) controller().offer(sbn);
                }
            });
        } catch (Throwable ignored) {
        }

        // D. 兜底1：NotificationEntry 构造（通知进入 SystemUI 的必经之路）
        // E. 兜底2：NotificationEntry.updateNotification（内容更新）
        for (String method : new String[]{"<init>", "updateNotification"}) {
            try {
                Class<?> entryClass = XposedHelpers.findClass(
                        "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                        lpparam.classLoader);
                if ("<init>".equals(method)) {
                    hookAllConstructorsCompat(entryClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            offerFromEntry(param.thisObject);
                        }
                    });
                } else {
                    hookAllMethodsCompat(entryClass, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            offerFromEntry(param.thisObject);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void offerFromEntry(Object entry) {
        try {
            Object sbn = XposedHelpers.getObjectField(entry, "mSbn");
            if (sbn == null) sbn = XposedHelpers.callMethod(entry, "getSbn");
            if (sbn instanceof StatusBarNotification) {
                controller().offer((StatusBarNotification) sbn);
            }
        } catch (Throwable t) {
        }
    }

    private StatusBarNotification extractSbnFromArgs(Object[] args) {
        // 1. 直接参数
        for (Object arg : args) {
            if (arg instanceof StatusBarNotification) return (StatusBarNotification) arg;
        }
        // 2. 从 NotificationEntry 类参数反射（先 getSbn 后字段，字段快但异常贵，故分层）
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Object result = XposedHelpers.getObjectField(arg, "mSbn");
                if (result instanceof StatusBarNotification) return (StatusBarNotification) result;
            } catch (Throwable ignored) {
            }
        }
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Object result = XposedHelpers.callMethod(arg, "getSbn");
                if (result instanceof StatusBarNotification) return (StatusBarNotification) result;
            } catch (Throwable ignored) {
            }
        }
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Object result = XposedHelpers.getObjectField(arg, "sbn");
                if (result instanceof StatusBarNotification) return (StatusBarNotification) result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
