package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class GhostScreenshotDetectionHook {

    public void handleScreenshotBlock(DexKitBridge bridge) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (FeatureFlags.isGhostScreenshot) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostScreenshot", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                XposedBridge.log("(InstaEclipse | ScreenshotBlock): ✅ Hooked (dynamic check): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostScreenshot");
                return;
            }
        }

        try {
            // Step 1: Find class referencing "ScreenshotNotificationManager"
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("ScreenshotNotificationManager")));

            if (classes.isEmpty()) {
                XposedBridge.log("(InstaEclipse | ScreenshotBlock): ❌ No class found containing 'ScreenshotNotificationManager'");
                return;
            }

            for (ClassData classData : classes) {
                String className = classData.getName();

                // Step 2: Find all methods in that class
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().declaredClass(className)));

                for (MethodData method : methods) {
                    ClassDataList paramTypes = method.getParamTypes();
                    String returnType = String.valueOf(method.getReturnType());

                    // Match: void method(long)
                    if (returnType.contains("void") &&
                            paramTypes.size() == 1 &&
                            String.valueOf(paramTypes.get(0)).contains("long")) {

                        try {
                            Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
                            DexKitCache.saveMethod("GhostScreenshot", targetMethod);
                            XposedBridge.hookMethod(targetMethod, hook);

                            XposedBridge.log("(InstaEclipse | ScreenshotBlock): ✅ Hooked (dynamic check): " +
                                    method.getClassName() + "." + method.getName());
                            FeatureStatusTracker.setHooked("GhostScreenshot");
                            return;

                        } catch (Throwable e) {
                            XposedBridge.log("(InstaEclipse | ScreenshotBlock): ❌ Hook error: " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | ScreenshotBlock): ❌ Exception: " + e.getMessage());
        }
    }
}
