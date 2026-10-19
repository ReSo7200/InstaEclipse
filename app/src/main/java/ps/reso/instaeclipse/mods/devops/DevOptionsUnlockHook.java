package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class DevOptionsUnlockHook {

    private static final String MOBILECONFIG_GETTER_CLASS = "com.facebook.mobileconfig.factory.MobileConfigUnsafeContext";
    private static final String USER_SESSION_CLASS = "com.instagram.common.session.UserSession";

    // Structural-tier tuning. Confirmed against IG 436: among ~140 (UserSession)->boolean
    // methods that read MobileConfig, the real "is employee" gate is the one minimal,
    // branch-free pass-through (~9 opcodes) reused from 9 unrelated classes — every other
    // candidate is either a bigger cached/branchy check (40+ opcodes) or has 1-2 callers.
    private static final int MAX_GATE_OPCODES = 16;
    private static final int MIN_CALLER_FAN_IN = 3;

    // Legacy hardcoded MobileConfig IDs, kept only as a last-resort fallback below the
    // structural tier. Meta regenerates this ID per build and NOT in version order (IG 436
    // used a higher schema byte than IG 437), so this list is inherently a losing game —
    // do not add to it; the structural tier (Tier 2) should make it unnecessary going forward.
    private static final long[] IS_EMPLOYEE_CONFIG_IDS = {
            36310834636390667L, // IG 436 (LX/02mx;->A00, 0x8100830000010b)
            36310830341423371L, // IG 437+ (LX/5sG;->A00, 0x8100820000010b)
            36310856111227168L, // IG <= 429 (LX/02il;->A00, 0x81008800000120)
            36310864701161762L, // older observed build
    };

    public void handleDevOptions(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cachedMethod = DexKitCache.loadMethod("DevOptionsMethod", Module.hostClassLoader);
            if (cachedMethod != null) {
                hookExactMethod(cachedMethod);
                return;
            }
            String cachedClass = DexKitCache.loadString("DevOptionsClass");
            if (cachedClass != null) {
                hookBooleanMethodsViaReflection(cachedClass);
                return;
            }
        }
        try {
            findAndHookDynamicMethod(bridge);
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Error handling Dev Options: " + e.getMessage());
        }
    }

    private void findAndHookDynamicMethod(DexKitBridge bridge) {
        try {
            // Tier 1: Existing String-based search
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): 🔍 Discovery Tier 1 (String)...");
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

            // Tier 2: Structural discovery — no string, no hardcoded ID. Finds the gate by
            // its shape: a minimal, branch-free (UserSession)->boolean check that reads
            // MobileConfig directly and is reused from many unrelated classes. Survives
            // both string stripping and per-build MobileConfig ID churn.
            if (!found) {
                ModuleLog.line("(InstaEclipse | DevOptionsEnable): ⚠️ Tier 1 failed. Discovery Tier 2 (Structural)...");
                MethodData structural = resolveEmployeeGateStructurally(bridge);
                if (structural != null) {
                    try {
                        Method targetMethod = structural.getMethodInstance(Module.hostClassLoader);
                        DexKitCache.saveMethod("DevOptionsMethod", targetMethod);
                        hookExactMethod(targetMethod);
                        ModuleLog.line("(InstaEclipse | DevOptionsEnable): 🎯 Found via structural match: "
                                + structural.getClassName() + "." + structural.getName());
                        found = true;
                    } catch (Throwable e) {
                        ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Structural match failed to bind: " + e.getMessage());
                    }
                }
            }

            // Tier 3: Failover to hardcoded MobileConfig IDs (legacy, last resort)
            if (!found) {
                ModuleLog.line("(InstaEclipse | DevOptionsEnable): ⚠️ Tier 2 failed. Discovery Tier 3 (Config ID)...");
                for (long configId : IS_EMPLOYEE_CONFIG_IDS) {
                    List<MethodData> idMethods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingNumbers(configId)
                                    .returnType("boolean")
                                    .paramCount(1))
                    );

                    if (!idMethods.isEmpty()) {
                        String targetClass = idMethods.get(0).getClassName();
                        ModuleLog.line("(InstaEclipse | DevOptionsEnable): 🎯 Found via Config ID " + configId + " in: " + targetClass);
                        DexKitCache.saveString("DevOptionsClass", targetClass);
                        hookAllBooleanMethodsInClass(bridge, targetClass);
                        found = true;
                        break;
                    }
                }
            }

            // Final Debug Trace: If all tiers fail, log where the string is used ANYWHERE
            if (!found) {
                ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Tier 3 failed. Debugging global references...");
                List<MethodData> debugMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("is_employee")));
                for (MethodData m : debugMethods) {
                    ModuleLog.line("(InstaEclipse | DevOptionsDebug): String 'is_employee' found in: " + m.getClassName() + "." + m.getName());
                }
            }

        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Error during discovery: " + e.getMessage());
        }
    }

    /**
     * Resolves the "is employee" gate by shape instead of by name/ID:
     * 1. Find MobileConfig's raw boolean-by-id getter — declaring class is
     *    {@code MobileConfigUnsafeContext}, which keeps its real package name across
     *    builds (shared Meta infra, not app-feature code), and it has exactly one
     *    {@code (long)->boolean} overload, so this step is always unambiguous.
     * 2. Collect every {@code (UserSession)->boolean} method that calls it directly —
     *    there are ~140 of these on a typical build, one per MobileConfig-backed flag.
     * 3. Discard anything bigger than a minimal pass-through (cached/branchy checks for
     *    other flags are much larger), then rank survivors by how many distinct classes
     *    call them. Ordinary single-feature checks have 1-2 callers; the shared employee
     *    gate is reused everywhere.
     */
    private MethodData resolveEmployeeGateStructurally(DexKitBridge bridge) {
        try {
            List<MethodData> getters = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(MOBILECONFIG_GETTER_CLASS)
                            .returnType("boolean")
                            .paramTypes("long")));
            if (getters.isEmpty()) return null;

            MethodsMatcher getterInvoke = MethodsMatcher.create();
            for (MethodData getter : getters) {
                getterInvoke.add(MethodMatcher.create(getter.getDescriptor()));
            }

            List<MethodData> candidates = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("boolean")
                            .paramTypes(USER_SESSION_CLASS)
                            .invokeMethods(getterInvoke)));

            MethodData best = null;
            int bestFanIn = 0;
            for (MethodData candidate : candidates) {
                if (candidate.getOpCodes().size() > MAX_GATE_OPCODES) continue;

                Set<String> callerClasses = new HashSet<>();
                for (MethodData caller : candidate.getCallers()) {
                    callerClasses.add(caller.getClassName());
                }
                if (callerClasses.size() > bestFanIn) {
                    bestFanIn = callerClasses.size();
                    best = candidate;
                }
            }
            return bestFanIn >= MIN_CALLER_FAN_IN ? best : null;
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Structural discovery error: " + e.getMessage());
            return null;
        }
    }

    private void hookExactMethod(Method m) {
        try {
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.isDevEnabled) {
                        param.setResult(true);
                        FeatureStatusTracker.setHooked("DevOptions");
                    }
                }
            });
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ✅ Hooked: " + m.getDeclaringClass().getName() + "." + m.getName());
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Failed to hook resolved method: " + e.getMessage());
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
                    ModuleLog.line("(InstaEclipse | DevOptionsEnable): 📦 Hooking via String detection: " + targetClass);
                    DexKitCache.saveString("DevOptionsClass", targetClass);
                    hookAllBooleanMethodsInClass(bridge, targetClass);
                    return true;
                }
            }
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Error inspecting invoked methods: " + e.getMessage());
        }
        return false;
    }

    private void hookBooleanMethodsViaReflection(String className) {
        try {
            Class<?> clazz = Module.hostClassLoader.loadClass(className);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
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
                ModuleLog.line("(InstaEclipse | DevOptionsEnable): ✅ Hooked (cache): " + className + "." + m.getName());
            }
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Reflection fallback failed: " + e.getMessage());
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
                            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                                if (FeatureFlags.isDevEnabled) {
                                    param.setResult(true);
                                    FeatureStatusTracker.setHooked("DevOptions");
                                }
                            }
                        });
                        ModuleLog.line("(InstaEclipse | DevOptionsEnable): ✅ Hooked: " + method.getClassName() + "." + method.getName());
                    } catch (Throwable e) {
                        ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Failed to hook " + method.getName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DevOptionsEnable): ❌ Error while hooking class: " + className + " → " + e.getMessage());
        }
    }
}