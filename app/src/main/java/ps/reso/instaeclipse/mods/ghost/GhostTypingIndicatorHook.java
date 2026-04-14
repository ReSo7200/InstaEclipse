package ps.reso.instaeclipse.mods.ghost;

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
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class GhostTypingIndicatorHook {

    public void handleTypingBlock(DexKitBridge bridge) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.isGhostTyping) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostTyping", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                XposedBridge.log("(InstaEclipse | TypingBlock): ✅ Hooked (dynamic check): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostTyping");
                return;
            }
        }

        try {
            // Step 1: Find methods containing the string "is_typing_indicator_enabled"
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("is_typing_indicator_enabled")));

            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | TypingBlock): ❌ No methods found containing 'is_typing_indicator_enabled'");
                return;
            }

            for (MethodData method : methods) {
                ClassDataList paramTypes = method.getParamTypes();
                String returnType = String.valueOf(method.getReturnType());

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    // Skip method if it can't be resolved
                    continue;
                }

                int modifiers = reflectMethod.getModifiers();

                // Step 2: Match: static final void method(ClassType, boolean)
                if (Modifier.isStatic(modifiers) &&
                        Modifier.isFinal(modifiers) &&
                        returnType.contains("void") &&
                        paramTypes.size() == 2 &&
                        String.valueOf(paramTypes.get(1)).contains("boolean")) {

                    try {
                        DexKitCache.saveMethod("GhostTyping", reflectMethod);
                        XposedBridge.hookMethod(reflectMethod, hook);

                        XposedBridge.log("(InstaEclipse | TypingBlock): ✅ Hooked (dynamic check): " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("GhostTyping");
                        return;

                    } catch (Throwable e) {
                        XposedBridge.log("(InstaEclipse | TypingBlock): ❌ Hook error: " + e.getMessage());
                    }
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | TypingBlock): ❌ Exception: " + t.getMessage());
        }
    }
}
