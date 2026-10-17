package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Load the largest image Instagram already offers. The picker
 * {@code static ExtendedImageUrl (Integer, List candidates, int)} (IG 452: one per media-size
 * helper) chooses a candidate for the view's size; the hook returns the candidate with the
 * biggest area instead. It only picks among the real candidates and never builds CDN URLs.
 */
public final class HighResolutionImageHook {

    private static final String TAG = "(InstaEclipse | HighResImages): ";
    private static final String CACHE = "Extras_HighResImage";
    private static final String IMAGE = "com.instagram.model.mediasize.ExtendedImageUrl";

    public void install(DexKitBridge bridge, ClassLoader cl) {
        List<Method> pickers = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE, cl) : null;
        if (pickers == null || pickers.isEmpty()) {
            pickers = new ArrayList<>();
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType(IMAGE).paramTypes("java.lang.Integer", "java.util.List", "int")))) {
                try {
                    pickers.add(md.getMethodInstance(cl));
                } catch (Throwable ignored) {}
            }
            if (pickers.isEmpty()) {
                ModuleLog.line(TAG + "❌ image picker not found");
                return;
            }
            DexKitCache.saveMethods(CACHE, pickers);
        }
        for (Method m : pickers) {
            Class<?> resultType = m.getReturnType();
            Method width, height;
            try {
                width = resultType.getMethod("getWidth");
                height = resultType.getMethod("getHeight");
            } catch (NoSuchMethodException missing) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    if (!FeatureFlags.highResolutionImages) return;
                    if (p.hasThrowable() || !(p.args[1] instanceof List)) return;
                    Object best = null;
                    long area = 0;
                    for (Object candidate : (List<?>) p.args[1]) {
                        if (!resultType.isInstance(candidate)) continue;
                        Object w = width.invoke(candidate), h = height.invoke(candidate);
                        if (!(w instanceof Number) || !(h instanceof Number)) continue;
                        long x = ((Number) w).longValue(), y = ((Number) h).longValue();
                        if (x <= 0 || y <= 0 || x > 65536 || y > 65536) continue;
                        if (x * y > area) {
                            area = x * y;
                            best = candidate;
                        }
                    }
                    if (best != null) p.setResult(best);
                }
            });
        }
        FeatureStatusTracker.setHooked("highResolutionImages");
        ModuleLog.line(TAG + "✅ hooked " + pickers.size() + " picker(s)");
    }
}
