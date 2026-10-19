package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** Hook entry points with the legacy XposedBridge names, run on the active {@link HookBackend}. */
public final class XposedBridge {

    private static volatile HookBackend backend;

    private XposedBridge() {}

    /** Installed once by the entry class the framework loaded. */
    public static void setBackend(HookBackend b) {
        backend = b;
    }

    public static HookBackend backend() {
        HookBackend b = backend;
        if (b == null) throw new IllegalStateException("InstaEclipse: no Xposed backend installed");
        return b;
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (!(method instanceof Method) && !(method instanceof Constructor)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + method);
        }
        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + method);
        }
        return backend().hook(method, callback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String name, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> out = new HashSet<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && !Modifier.isAbstract(m.getModifiers())) out.add(hookMethod(m, callback));
        }
        return out;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> out = new HashSet<>();
        for (Constructor<?> c : clazz.getDeclaredConstructors()) out.add(hookMethod(c, callback));
        return out;
    }

    /** Calls the original method, skipping every hook on it. */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        return backend().invokeOriginal(method, thisObject, args);
    }

    public static void log(String text) {
        HookBackend b = backend;
        if (b != null) b.log(text, null);
        else android.util.Log.i("InstaEclipse", text);
    }

    public static void log(Throwable t) {
        HookBackend b = backend;
        if (b != null) b.log(String.valueOf(t), t);
        else android.util.Log.e("InstaEclipse", String.valueOf(t), t);
    }
}
