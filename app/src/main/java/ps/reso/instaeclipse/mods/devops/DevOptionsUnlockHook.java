package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * Unlocks Instagram's developer / employee options.
 *
 * Strategy:
 *  1. Find every obfuscated method that references the "is_employee" string (only in X.* classes).
 *  2. For each such method, inspect the methods it *calls* (getInvokes).
 *  3. Collect only the ones that match boolean(UserSession) — those are the specific employee-
 *     gate checks (is_employee, is_employee_or_test_user, employee_options …).
 *  4. Hook only those collected methods, not the entire class.
 *
 * This avoids the previous "hook every boolean in the class" approach which was both noisy
 * and potentially incorrect, since the class can contain many unrelated boolean checks.
 */
public class DevOptionsUnlockHook {

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (FeatureFlags.isDevEnabled) {
                param.setResult(true);
            }
        }
    };

    public void handleDevOptions(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("DevOptionsMethods", Module.hostClassLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, HOOK);
                FeatureStatusTracker.setHooked("DevOptions");
                return;
            }
        }

        try {
            findAndHook(bridge);
        } catch (Throwable t) {
            XposedBridge.log("(IE|DevOptions) ❌ " + t);
        }
    }

    private void findAndHook(DexKitBridge bridge) {
        // Step 1: find all X.* methods that reference "is_employee"
        List<MethodData> isEmployeeMethods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .usingStrings("is_employee")));

        if (isEmployeeMethods.isEmpty()) {
            XposedBridge.log("(IE|DevOptions) ❌ no methods referencing 'is_employee'");
            return;
        }

        List<Method> targets = new ArrayList<>();

        for (MethodData method : isEmployeeMethods) {
            if (!method.getClassName().startsWith("X.")) continue;

            // Step 2: walk the call-graph one level and collect boolean(UserSession) callees
            List<MethodData> invokes;
            try {
                invokes = method.getInvokes();
            } catch (Throwable ignored) {
                continue;
            }

            for (MethodData invoked : invokes) {
                if (!String.valueOf(invoked.getReturnType()).contains("boolean")) continue;
                List<?> params = invoked.getParamTypes();
                if (params.size() != 1) continue;
                if (!String.valueOf(params.get(0))
                        .contains("com.instagram.common.session.UserSession")) continue;

                try {
                    Method m = invoked.getMethodInstance(Module.hostClassLoader);
                    if (!targets.contains(m)) targets.add(m);
                } catch (Throwable ignored) {}
            }
        }

        if (targets.isEmpty()) {
            XposedBridge.log("(IE|DevOptions) ❌ no boolean(UserSession) targets found");
            return;
        }

        DexKitCache.saveMethods("DevOptionsMethods", targets);
        for (Method m : targets) {
            XposedBridge.hookMethod(m, HOOK);
            XposedBridge.log("(IE|DevOptions) ✅ hooked " + m.getDeclaringClass().getName() + "." + m.getName());
        }
        FeatureStatusTracker.setHooked("DevOptions");
    }
}
