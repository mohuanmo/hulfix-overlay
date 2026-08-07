package de.robv.android.xposed;

import java.lang.reflect.Constructor;

public final class XposedBridge {
    public static void log(String text) {
        android.util.Log.i("Xposed", text);
    }

    public static XC_MethodHook.Unhook[] hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook[] hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) {
        return null;
    }
}
