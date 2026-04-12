package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * Makes view-once and view-twice (replayable) media behave like permanent media.
 *
 * Instagram parses the server's JSON "view_mode" field in unsafeParseFromJson
 * (class X/1Ui in build 423) and stores it as a plain String on the media model.
 *
 * Possible values:
 *   "once"       — view once (disappears after one open)
 *   "replayable" — view twice (one extra replay allowed)
 *   "permanent"  — normal media, always accessible
 *
 * We hook the parser method after it runs and replace any non-permanent
 * view_mode value with "permanent" so Instagram renders the media normally.
 *
 * DexKit fingerprint: method using both "archived_media_timestamp" AND "view_mode"
 * with exactly 1 parameter (the JSON reader). This distinguishes it from the
 * companion serializer method in the same class which has 2 parameters.
 */
public class GhostPermanentViewHook {

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ViewOnceMedia", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, buildHook());
                FeatureStatusTracker.setHooked("PermanentViewMode");
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("archived_media_timestamp", "view_mode")
                            .paramCount(1)));

            if (methods.isEmpty()) {
                XposedBridge.log("(IE|ViewOnceMedia) ❌ unsafeParseFromJson not found");
                return;
            }

            // Pick the method whose return type is not void (the parser returns the model object;
            // the serializer returns void). Fall back to the first candidate if none match.
            Method target = null;
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() != void.class) {
                        target = m;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (target == null) {
                // Fallback: just use the first found
                try {
                    target = methods.get(0).getMethodInstance(classLoader);
                } catch (Throwable t) {
                    XposedBridge.log("(IE|ViewOnceMedia) ❌ Could not resolve method: " + t);
                    return;
                }
            }

            XposedBridge.log("(IE|ViewOnceMedia) ✅ hooking "
                    + target.getDeclaringClass().getName() + "." + target.getName());

            DexKitCache.saveMethod("ViewOnceMedia", target);
            XposedBridge.hookMethod(target, buildHook());

            FeatureStatusTracker.setHooked("PermanentViewMode");
            XposedBridge.log("(IE|ViewOnceMedia) ✅ hooked");

        } catch (Throwable t) {
            XposedBridge.log("(IE|ViewOnceMedia) ❌ " + t);
        }
    }

    private static XC_MethodHook buildHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.permanentViewMode) return;
                Object result = param.getResult();
                if (result == null) return;
                Class<?> cls = result.getClass();
                while (cls != null && cls != Object.class) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (f.getType() != String.class) continue;
                        f.setAccessible(true);
                        try {
                            String val = (String) f.get(result);
                            if ("once".equals(val) || "replayable".equals(val)
                                    || "allow_replay".equals(val)) {
                                f.set(result, "permanent");
                            }
                        } catch (Throwable ignored) {}
                    }
                    cls = cls.getSuperclass();
                }
            }
        };
    }
}
