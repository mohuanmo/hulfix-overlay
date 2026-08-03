package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class XposedHelpers {
    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = findFieldRecursiveImpl(obj.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        try {
            Object value = getObjectField(obj, fieldName);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            Object value = getObjectField(obj, fieldName);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            Object value = getObjectField(obj, fieldName);
            if (value instanceof Long) {
                return (Long) value;
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field field = findFieldRecursiveImpl(obj.getClass(), fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception ignored) {}
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        try {
            Field field = findFieldRecursiveImpl(obj.getClass(), fieldName);
            field.setAccessible(true);
            field.setBoolean(obj, value);
        } catch (Exception ignored) {}
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            Field field = findFieldRecursiveImpl(obj.getClass(), fieldName);
            field.setAccessible(true);
            field.setInt(obj, value);
        } catch (Exception ignored) {}
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        try {
            return findFieldRecursiveImpl(clazz, fieldName);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Field findFieldRecursiveImpl(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return findFieldRecursiveImpl(superClass, fieldName);
            }
            throw e;
        }
    }
}
