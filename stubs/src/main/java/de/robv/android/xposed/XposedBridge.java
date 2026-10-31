package de.robv.android.xposed;

public final class XposedBridge {
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) { return null; }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook callback) { return null; }
    public static void log(String text) {}
    public static void log(Throwable t) {}
}