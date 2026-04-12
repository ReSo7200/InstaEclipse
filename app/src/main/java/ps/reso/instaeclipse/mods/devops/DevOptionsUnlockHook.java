package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class DevOptionsUnlockHook {

    public void handleDevOptions(DexKitBridge bridge) {
        // Cache hit: we only stored the target class name; re-hook its boolean methods via reflection
        if (DexKitCache.isCacheValid()) {
            String cachedClass = DexKitCache.loadString("DevOptionsClass");
            if (cachedClass != null) {
                hookBooleanMethodsViaReflection(cachedClass);
                return;
            }
        }
        try {
            findAndHookDynamicMethod(bridge);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error handling Dev Options: " + e.getMessage());
        }
    }

    private void findAndHookDynamicMethod(DexKitBridge bridge) {
        try {
            // Step 1: Find classes referencing "is_employee"
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("is_employee"))
            );

            if (classes.isEmpty()) return;

            for (ClassData classData : classes) {
                String className = classData.getName();
                if (!className.startsWith("X.")) continue;

                // Step 2: Find methods referencing "is_employee" within the class
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(className)
                                .usingStrings("is_employee"))
                );

                if (methods.isEmpty()) continue;

                for (MethodData method : methods) {
                    inspectInvokedMethods(bridge, method);
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error during discovery: " + e.getMessage());
        }
    }

    private void inspectInvokedMethods(DexKitBridge bridge, MethodData method) {
        try {
            List<MethodData> invokedMethods = method.getInvokes();
            if (invokedMethods.isEmpty()) return;

            for (MethodData invokedMethod : invokedMethods) {
                String returnType = String.valueOf(invokedMethod.getReturnType());

                if (!returnType.contains("boolean")) continue;

                List<String> paramTypes = new java.util.ArrayList<>();
                for (Object param : invokedMethod.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (paramTypes.size() == 1 &&
                        paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {

                    String targetClass = invokedMethod.getClassName();
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 📦 Hooking boolean methods in: " + targetClass);
                    DexKitCache.saveString("DevOptionsClass", targetClass);
                    hookAllBooleanMethodsInClass(bridge, targetClass);
                    return;
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error inspecting invoked methods: " + e.getMessage());
        }
    }

    /** Cache-hit path: hooks the target class's boolean(UserSession) methods without DexKit. */
    private void hookBooleanMethodsViaReflection(String className) {
        try {
            Class<?> clazz = Module.hostClassLoader.loadClass(className);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.isDevEnabled) {
                        param.setResult(true);
                        FeatureStatusTracker.setHooked("DevOptions");
                    }
                }
            };
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (!params[0].getName().equals("com.instagram.common.session.UserSession")) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, hook);
                XposedBridge.log("(InstaEclipse | DevOptionsEnable): ✅ Hooked (cache): " + className + "." + m.getName());
            }
        } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Reflection fallback failed: " + e.getMessage());
        }
    }

    private void hookAllBooleanMethodsInClass(DexKitBridge bridge, String className) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(className))
            );

            for (MethodData method : methods) {
                String returnType = String.valueOf(method.getReturnType());
                List<String> paramTypes = new java.util.ArrayList<>();
                for (Object param : method.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (returnType.contains("boolean") &&
                        paramTypes.size() == 1 &&
                        paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {

                    try {
                        Method targetMethod = method.getMethodInstance(Module.hostClassLoader);

                        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (FeatureFlags.isDevEnabled) {
                                    param.setResult(true);
                                    FeatureStatusTracker.setHooked("DevOptions");
                                }
                            }
                        });

                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ✅ Hooked: " +
                                method.getClassName() + "." + method.getName());

                    } catch (Throwable e) {
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Failed to hook " + method.getName() + ": " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error while hooking class: " + className + " → " + e.getMessage());
        }
    }
}
