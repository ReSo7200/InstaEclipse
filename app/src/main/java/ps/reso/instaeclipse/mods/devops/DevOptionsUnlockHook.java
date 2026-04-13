package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class DevOptionsUnlockHook {

    private static final long IS_EMPLOYEE_CONFIG_ID = 36310864701161762L;

    public void handleDevOptions(DexKitBridge bridge) {
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
            // Tier 1: Existing String-based search
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): 🔍 Discovery Tier 1 (String)...");
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("is_employee"))
            );

            boolean found = false;
            if (!classes.isEmpty()) {
                for (ClassData classData : classes) {
                    String className = classData.getName();
                    if (!className.startsWith("X.")) continue;

                    List<MethodData> methods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(className)
                                    .usingStrings("is_employee"))
                    );

                    for (MethodData method : methods) {
                        if (inspectInvokedMethods(bridge, method)) {
                            found = true;
                            break;
                        }
                    }
                    if (found) break;
                }
            }

            // Tier 2: Failover to MobileConfig ID (The "Golden Anchor")
            if (!found) {
                XposedBridge.log("(InstaEclipse | DevOptionsEnable): ⚠️ Tier 1 failed. Discovery Tier 2 (Config ID)...");
                List<MethodData> idMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingNumbers(IS_EMPLOYEE_CONFIG_ID)
                                .returnType("boolean")
                                .paramCount(1))
                );

                if (!idMethods.isEmpty()) {
                    String targetClass = idMethods.get(0).getClassName();
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 🎯 Found via Config ID in: " + targetClass);
                    DexKitCache.saveString("DevOptionsClass", targetClass);
                    hookAllBooleanMethodsInClass(bridge, targetClass);
                    found = true;
                }
            }

            // Final Debug Trace: If both fail, log where the string is used ANYWHERE
            if (!found) {
                XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Tier 2 failed. Debugging global references...");
                List<MethodData> debugMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("is_employee")));
                for (MethodData m : debugMethods) {
                    XposedBridge.log("(InstaEclipse | DevOptionsDebug): String 'is_employee' found in: " + m.getClassName() + "." + m.getName());
                }
            }

        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error during discovery: " + e.getMessage());
        }
    }

    private boolean inspectInvokedMethods(DexKitBridge bridge, MethodData method) {
        try {
            List<MethodData> invokedMethods = method.getInvokes();
            if (invokedMethods.isEmpty()) return false;

            for (MethodData invokedMethod : invokedMethods) {
                String returnType = String.valueOf(invokedMethod.getReturnType());
                if (!returnType.contains("boolean")) continue;

                List<String> paramTypes = new ArrayList<>();
                for (Object param : invokedMethod.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (paramTypes.size() == 1 && paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {
                    String targetClass = invokedMethod.getClassName();
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 📦 Hooking via String detection: " + targetClass);
                    DexKitCache.saveString("DevOptionsClass", targetClass);
                    hookAllBooleanMethodsInClass(bridge, targetClass);
                    return true;
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error inspecting invoked methods: " + e.getMessage());
        }
        return false;
    }

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
                    .matcher(MethodMatcher.create().declaredClass(className))
            );

            for (MethodData method : methods) {
                String returnType = String.valueOf(method.getReturnType());
                List<String> paramTypes = new ArrayList<>();
                for (Object param : method.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (returnType.contains("boolean") && paramTypes.size() == 1 && paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {
                    try {
                        Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
                        XposedHelpers.findAndHookMethod(targetMethod.getDeclaringClass(), targetMethod.getName(), targetMethod.getParameterTypes()[0], new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (FeatureFlags.isDevEnabled) {
                                    param.setResult(true);
                                    FeatureStatusTracker.setHooked("DevOptions");
                                }
                            }
                        });
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ✅ Hooked: " + method.getClassName() + "." + method.getName());
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