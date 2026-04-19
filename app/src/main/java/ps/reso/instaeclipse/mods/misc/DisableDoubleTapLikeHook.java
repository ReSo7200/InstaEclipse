package ps.reso.instaeclipse.mods.misc;

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
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class DisableDoubleTapLikeHook {

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (!FeatureFlags.disableDoubleTapLike) return;
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement frame : stack) {
                String m = frame.getMethodName();
                if ("onDoubleTap".equals(m) || "onDoubleTapEvent".equals(m)) {
                    param.setResult(null);
                    return;
                }
            }
        }
    };

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method feedCached = DexKitCache.loadMethod("DoubleTapLike", classLoader);
            Method reelsCached = DexKitCache.loadMethod("DoubleTapLikeReels", classLoader);
            if (feedCached != null && reelsCached != null) {
                XposedBridge.hookMethod(feedCached, HOOK);
                XposedBridge.hookMethod(reelsCached, HOOK);
                XposedBridge.log("(InstaEclipse | DoubleTapLike): ✅ Hooked (cached)");
                FeatureStatusTracker.setHooked("DisableDoubleTapLike");
                return;
            }
        }
        try {
            findAndHook(bridge, classLoader);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DoubleTapLike): ❌ " + e.getMessage());
        }
    }

    private void findAndHook(DexKitBridge bridge, ClassLoader classLoader) {
        // Feed like dispatcher
        List<MethodData> feedMethods = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .usingStrings("double_tap_on_liked", "used_double_tap")
                )
        );
        for (MethodData md : feedMethods) {
            try {
                Method m = md.getMethodInstance(classLoader);
                DexKitCache.saveMethod("DoubleTapLike", m);
                XposedBridge.hookMethod(m, HOOK);
                XposedBridge.log("(InstaEclipse | DoubleTapLike): ✅ Feed hooked on " + md.getClassName() + "." + md.getMethodName());
                FeatureStatusTracker.setHooked("DisableDoubleTapLike");
                break;
            } catch (Exception e) {
                XposedBridge.log("(InstaEclipse | DoubleTapLike): ❌ Feed: " + e.getMessage());
            }
        }
        if (feedMethods.isEmpty()) {
            XposedBridge.log("(InstaEclipse | DoubleTapLike): ❌ Feed method not found");
        }

        // Reels double-tap entry point
        List<ClassData> reelsClasses = bridge.findClass(FindClass.create()
                .matcher(ClassMatcher.create()
                        .usingStrings("clips_doubletap", "LIKE_FIRED")
                )
        );
        boolean reelsHooked = false;
        for (ClassData cd : reelsClasses) {
            List<MethodData> ecgMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(cd.getName())
                            .usingStrings("clips_doubletap")
                    )
            );
            for (MethodData md : ecgMethods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    XposedBridge.hookMethod(m, HOOK);
                    if (!reelsHooked) {
                        DexKitCache.saveMethod("DoubleTapLikeReels", m);
                        reelsHooked = true;
                    }
                    XposedBridge.log("(InstaEclipse | DoubleTapLike): ✅ Reels hooked on " + cd.getName() + "." + md.getMethodName());
                } catch (Exception e) {
                    XposedBridge.log("(InstaEclipse | DoubleTapLike): ❌ Reels: " + e.getMessage());
                }
            }
        }
        if (!reelsHooked) {
            XposedBridge.log("(InstaEclipse | DoubleTapLike): ❌ Reels entry not found");
        }
    }
}
