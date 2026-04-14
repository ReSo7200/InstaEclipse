package ps.reso.instaeclipse.mods.misc;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class DisableVideoAutoPlayHook {

    public void handleAutoPlayDisable(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("AutoPlayDisable", Module.hostClassLoader);
            if (cached != null) {
                hookMethod(cached);
                return;
            }
        }
        try {
            findAndHookDynamicMethod(bridge);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | AutoPlayDisable): Error: " + e.getMessage());
        }
    }

    private void findAndHookDynamicMethod(DexKitBridge bridge) {
        try {
            // Step 1: Find methods referencing "ig_disable_video_autoplay"
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("ig_disable_video_autoplay")
                    )
            );

            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ No matching methods found.");
                return;
            }

            // Step 2: Find the correct method: boolean return type, 1 parameter
            for (MethodData method : methods) {
                boolean returnTypeMatch = String.valueOf(method.getReturnType()).contains("boolean");
                boolean paramTypesMatch = method.getParamTypes().size() == 1;

                if (returnTypeMatch && paramTypesMatch) {
                    hookMethod(method);
                    return;
                }
            }

            XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ No matching methods with correct signature.");
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ Error during method discovery: " + e.getMessage());
        }
    }

    private void hookMethod(MethodData method) {
        try {
            Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
            DexKitCache.saveMethod("AutoPlayDisable", targetMethod);
            hookMethod(targetMethod);
            XposedBridge.log("(InstaEclipse | AutoPlayDisable): ✅ Hooked (dynamic check): " +
                    method.getClassName() + "." + method.getName());
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ Error hooking method: " + e.getMessage());
        }
    }

    private void hookMethod(Method targetMethod) {
        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (FeatureFlags.disableVideoAutoPlay) param.setResult(true);
            }
        });
    }
}
