package ps.reso.instaeclipse.mods.ui.utils;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;

public class BottomSheetHookUtil {

    private static final String CACHE_KEY = "BottomSheet";

    public static void hookBottomSheetNavigator(DexKitBridge bridge) {
        // Try cache first
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader);
            if (cached != null) {
                hookMethod(cached);
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings("BottomSheetConstants"))
            );

            for (MethodData method : methods) {
                if (!method.getClassName().equals("com.instagram.mainactivity.InstagramMainActivity")) continue;

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                int modifiers = reflectMethod.getModifiers();
                String returnType = String.valueOf(method.getReturnType());
                ClassDataList paramTypes = method.getParamTypes();

                if (!Modifier.isStatic(modifiers)
                        && Modifier.isFinal(modifiers)
                        && !returnType.contains("void")
                        && paramTypes.size() == 0) {
                    DexKitCache.saveMethod(CACHE_KEY, reflectMethod);
                    hookMethod(reflectMethod);
                    return;
                }
            }

        } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | BottomSheet): ❌ DexKit exception: " + e.getMessage());
        }
    }

    private static void hookMethod(Method reflectMethod) {
        // This getter is called on every navigation action, layout pass, and scroll
        // event — it is a hot path. We do NOT post any work to the main thread here.
        // onCreate and onResume hooks are responsible for all UI setup and ghost emoji
        // updates. This hook exists only to locate the method; its body is intentionally
        // empty to avoid any per-call overhead.
        XposedBridge.hookMethod(reflectMethod, new XC_MethodHook() { });
        XposedBridge.log("(InstaEclipse | BottomSheet): ✅ Hooked: " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName());
    }
}

