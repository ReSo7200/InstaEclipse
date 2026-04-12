package ps.reso.instaeclipse.mods.ads;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class AdBlocker {

    public void disableSponsoredContent(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (FeatureFlags.isAdBlockEnabled) param.setResult(false);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("AdBlocker", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                FeatureStatusTracker.setHooked("AdBlocker");
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create().usingStrings("SponsoredContentController.insertItem")
                    )
            );

            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | AdBlocker): ❌ No methods found referencing 'SponsoredContentController.insertItem'");
                return;
            }

            for (MethodData method : methods) {
                String returnType = String.valueOf(method.getReturnType());
                if (!returnType.contains("boolean")) continue;

                try {
                    Method targetMethod = method.getMethodInstance(classLoader);
                    DexKitCache.saveMethod("AdBlocker", targetMethod);
                    XposedBridge.hookMethod(targetMethod, hook);

                    XposedBridge.log("(InstaEclipse | AdBlocker): ✅ Hooked (dynamic check): " +
                            method.getClassName() + "." + method.getName());
                    FeatureStatusTracker.setHooked("AdBlocker");
                    return; // Stop after first successful hook

                } catch (Throwable hookEx) {
                    XposedBridge.log("(InstaEclipse | AdBlocker): ❌ Failed to hook: " +
                            method.getName() + " → " + hookEx.getMessage());
                }
            }

            XposedBridge.log("(InstaEclipse | AdBlocker): ❌ No valid methods hooked.");

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | AdBlocker): ❌ Exception: " + t.getMessage());
        }
    }
}
