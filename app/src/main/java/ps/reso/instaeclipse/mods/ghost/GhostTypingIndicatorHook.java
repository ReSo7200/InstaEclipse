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
import ps.reso.instaeclipse.utils.log.ModuleLog;

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
                ModuleLog.line("(InstaEclipse | TypingBlock): ✅ Hooked (dynamic check): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostTyping");
                return;
            }
        }

        try {
            // Step 1: Find methods containing the string "is_typing_indicator_enabled"
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("is_typing_indicator_enabled")));

            if (methods.isEmpty()) {
                ModuleLog.line("(InstaEclipse | TypingBlock): ❌ No methods found containing 'is_typing_indicator_enabled'");
                if (FeatureFlags.isGhostTyping) FeatureStatusTracker.setBroken("GhostTyping");
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

                if (!returnType.contains("void")) continue;
                int modifiers = reflectMethod.getModifiers();

                // Old shape (<= 429): static final void method(ClassType, boolean)
                // New shape (437+): instance final void method(boolean) — the string is now
                // just a QuickPerformanceLogger marker label, not a functional flag key, and
                // the leading ClassType param was dropped entirely.
                boolean matchesOldShape = Modifier.isStatic(modifiers) &&
                        Modifier.isFinal(modifiers) &&
                        paramTypes.size() == 2 &&
                        String.valueOf(paramTypes.get(1)).contains("boolean");

                boolean matchesNewShape = !Modifier.isStatic(modifiers) &&
                        Modifier.isFinal(modifiers) &&
                        paramTypes.size() == 1 &&
                        String.valueOf(paramTypes.get(0)).contains("boolean");

                if (matchesOldShape || matchesNewShape) {
                    try {
                        DexKitCache.saveMethod("GhostTyping", reflectMethod);
                        XposedBridge.hookMethod(reflectMethod, hook);

                        ModuleLog.line("(InstaEclipse | TypingBlock): ✅ Hooked (dynamic check): " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("GhostTyping");
                        return;

                    } catch (Throwable e) {
                        ModuleLog.line("(InstaEclipse | TypingBlock): ❌ Hook error: " + e.getMessage());
                    }
                }
            }

            ModuleLog.line("(InstaEclipse | TypingBlock): ❌ No candidate matched the expected method shape");
            if (FeatureFlags.isGhostTyping) FeatureStatusTracker.setBroken("GhostTyping");

        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | TypingBlock): ❌ Exception: " + t.getMessage());
        }
    }
}
