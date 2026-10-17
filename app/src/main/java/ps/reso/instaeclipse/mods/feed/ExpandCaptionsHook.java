package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Auto-expand feed captions (#246): no "… more" truncation.
 *
 * The feed caption is built by one static builder (X.00bW.A03 on 452, X.00jG.A03 on 451: the
 * 7-arg static non-void method using " [...]"), which calls the shared line-truncation helper
 * (X.04oF.A00(cfg, prefix, body, suffix, int maxLines, boolean) → CharSequence). The helper
 * returns the body untouched when it fits in maxLines, and the builder only appends the "more"
 * span when the text got shorter. While the builder runs (ThreadLocal), raise maxLines to
 * Integer.MAX_VALUE. The helper's other ~15 callers are left alone.
 *
 * IG caches built caption layouts, so toggling takes effect after an Instagram restart.
 */
public class ExpandCaptionsHook {

    private static final String TAG = "(InstaEclipse | ExpandCaptions): ";
    private static final String CACHE_KEY = "ExpandCaptions_builder_helper";
    private static final ThreadLocal<Integer> IN_BUILDER = new ThreadLocal<>();

    public void install(DexKitBridge bridge) {
        List<Method> pair = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, Module.hostClassLoader) : null;
        if (pair == null || pair.size() != 2) {
            pair = find(bridge);
            if (pair == null) return;
            DexKitCache.saveMethods(CACHE_KEY, pair);
        }
        Method builder = pair.get(0), helper = pair.get(1);
        XposedBridge.hookMethod(builder, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Integer d = IN_BUILDER.get();
                IN_BUILDER.set(d == null ? 1 : d + 1);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Integer d = IN_BUILDER.get();
                if (d == null || d <= 1) IN_BUILDER.remove(); else IN_BUILDER.set(d - 1);
            }
        });
        XposedBridge.hookMethod(helper, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.expandCaptions || IN_BUILDER.get() == null) return;
                if (param.args[4] instanceof Integer) param.args[4] = Integer.MAX_VALUE;
            }
        });
        ModuleLog.line(TAG + "✅ Hooked " + builder.getDeclaringClass().getName() + "." + builder.getName()
                + " → " + helper.getDeclaringClass().getName() + "." + helper.getName());
        FeatureStatusTracker.setHooked("ExpandCaptions");
    }

    private static List<Method> find(DexKitBridge bridge) {
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(" [...]").paramCount(7)))) {
                Method b = md.getMethodInstance(Module.hostClassLoader);
                if (!Modifier.isStatic(b.getModifiers()) || b.getReturnType() == void.class) continue;
                for (MethodData inv : md.getInvokes()) {
                    try {
                        if (!inv.isMethod()) continue;
                        Method h = inv.getMethodInstance(Module.hostClassLoader);
                        Class<?>[] p = h.getParameterTypes();
                        if (Modifier.isStatic(h.getModifiers()) && h.getReturnType() == CharSequence.class
                                && p.length == 6 && p[1] == CharSequence.class && p[2] == CharSequence.class
                                && p[3] == CharSequence.class && p[4] == int.class && p[5] == boolean.class) {
                            List<Method> out = new ArrayList<>();
                            out.add(b);
                            out.add(h);
                            return out;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            ModuleLog.line(TAG + "❌ caption builder / truncation helper not found");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
        return null;
    }
}
