package de.robv.android.xposed;

public final class XposedBridge {
    public static void log(String text) {
        android.util.Log.i("Xposed", text);
    }

    public static XC_MethodHook.Unhook[] hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        return null;
    }
}
