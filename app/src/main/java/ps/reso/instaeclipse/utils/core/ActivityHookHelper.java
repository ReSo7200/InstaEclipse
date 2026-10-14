package ps.reso.instaeclipse.utils.core;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ActivityHookHelper {

    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    public static void hookOnResume(ClassLoader classLoader, String logTag, XC_MethodHook hook, String... activities) {
        for (String act : activities) {
            boolean success = false;
            try {
                // 1. Try XposedHelpers exact lookup on the class itself
                Class<?> clazz = XposedHelpers.findClass(act, classLoader);
                try {
                    XposedHelpers.findAndHookMethod(clazz, "onResume", hook);
                    success = true;
                    continue; // Done for this activity
                } catch (Throwable ignored) {}

                // 2. Walk the superclasses using reflection (up to Activity)
                Class<?> current = clazz;
                while (current != null && current != Object.class && current.getName().startsWith("com.")) {
                    try {
                        Method m = current.getDeclaredMethod("onResume");
                        if (!Modifier.isAbstract(m.getModifiers())) {
                            XposedBridge.hookMethod(m, hook);
                            success = true;
                            break;
                        }
                    } catch (Throwable ignored) {}
                    current = current.getSuperclass();
                }

                if (success) continue;

                // 3. For InstagramMainActivity, fall back to DexKit search logic (similar to UIHookManager)
                if (act.equals(INSTAGRAM_MAIN_ACTIVITY) && Module.dexKitBridge != null) {
                    List<MethodData> candidates = Module.dexKitBridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
                                    .modifiers(Modifier.PUBLIC)
                                    .paramCount(0)
                                    .returnType("void")
                            )
                    );
                    for (MethodData methodData : candidates) {
                        String methodName = methodData.getName();
                        if (methodName == null || methodName.isEmpty()) continue;
                        if (methodName.contains("<init>") || methodName.contains("<clinit>")) continue;
                        // Avoid tiny methods
                        if (methodData.getOpCodes().size() < 20) continue;

                        try {
                            XposedHelpers.findAndHookMethod(INSTAGRAM_MAIN_ACTIVITY, classLoader, methodName, hook);
                            success = true;
                            ModuleLog.line("(" + logTag + ") hooked onResume via DexKit fallback: " + methodName);
                            break;
                        } catch (Throwable ignored) {}
                    }
                }

                if (!success) {
                    ModuleLog.line("(" + logTag + ") ⚠️ hook " + act + ": could not resolve onResume");
                }
            } catch (Throwable t) {
                ModuleLog.line("(" + logTag + ") ⚠️ hook " + act + " failed: " + t.getMessage());
            }
        }
    }
}
