package ps.reso.instaeclipse.mods.ui.utils;

import android.app.Activity;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Hooks an activity lifecycle method even when the activity does not declare its own override.
 *
 * XposedHelpers.findAndHookMethod only looks at DECLARED methods, so "InstagramMainActivity#onResume"
 * throws NoSuchMethodError on IG 448+ (the override was moved into the shared base class
 * com.instagram.base.activity.IgFragmentActivity). This walks up from the target class to the class
 * that actually declares the method, hooks that method ONCE, and dispatches only to callbacks whose
 * target class matches the runtime instance — so it works whether or not IG declares the override,
 * and old builds (where the target declares it) behave exactly as before.
 */
public final class LifecycleHookUtil {

    public interface AfterCallback {
        void after(Activity activity, XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    private record Entry(Class<?> target, AfterCallback callback) {}

    private static final Map<Method, List<Entry>> DISPATCH = new HashMap<>();

    private LifecycleHookUtil() {}

    /**
     * @return a short description of the hooked method (for logging)
     * @throws NoSuchMethodException if neither the class nor any superclass declares it
     */
    public static synchronized String hookAfter(ClassLoader classLoader, String className, String methodName,
                                                AfterCallback callback, Class<?>... paramTypes)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> target = Class.forName(className, false, classLoader);
        Method declared = null;
        for (Class<?> c = target; c != null; c = c.getSuperclass()) {
            try {
                declared = c.getDeclaredMethod(methodName, paramTypes);
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (declared == null) throw new NoSuchMethodException(className + "#" + methodName);

        List<Entry> entries = DISPATCH.get(declared);
        if (entries == null) {
            final List<Entry> list = new CopyOnWriteArrayList<>();
            entries = list;
            DISPATCH.put(declared, list);
            XposedBridge.hookMethod(declared, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    Object self = param.thisObject;
                    if (!(self instanceof Activity activity)) return;
                    for (Entry e : list) {
                        if (!e.target().isInstance(self)) continue;
                        try {
                            e.callback().after(activity, param);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
        entries.add(new Entry(target, callback));
        return declared.getDeclaringClass().getName() + "#" + methodName;
    }
}
