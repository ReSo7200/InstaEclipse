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
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Limits the home feed to accounts the user follows by forcing the feed/timeline request's
 * {@code pagination_source} parameter to {@code "following"}.
 *
 * <p>The feed request builder (IG 452: {@code X.02zt.A01}) is found by its "homecoming_all" +
 * "X-IG-PRIMED-FEED-REQUEST" strings. It fills a generic request builder (a parameter, X.03gG on
 * 452) through {@code (String, String)} adders — some add query/body params, others headers.
 * Every such adder is hooked, but only acts while the feed builder is on the stack
 * (ThreadLocal), so no other API request is touched. If IG skipped the parameter on this code
 * path, it is appended after the builder returns through an adder seen adding a param.
 */
public class FollowingOnlyFeedHook {

    private static final String TAG = "(InstaEclipse | FollowingFeed): ";
    private static final String KEY = "pagination_source";
    private static final String VALUE = "following";

    private static final String CACHE_BUILD = "FollowingFeed_build";
    private static final String CACHE_ADDERS = "FollowingFeed_adders";

    /** Per-call state; non-null only while the feed request builder is running. */
    private static final class CallState {
        boolean paramSeen;
        Method paramAdder;
        Object builder;
    }

    private static final ThreadLocal<CallState> STATE = new ThreadLocal<>();

    private static boolean isParamKey(String key) {
        if (key.isEmpty()) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(c == '_' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        Method build = null;
        List<Method> adders = null;
        if (DexKitCache.isCacheValid()) {
            if ("missing".equals(DexKitCache.loadString(CACHE_BUILD))) {
                ModuleLog.line(TAG + "not available on this version (cached)");
                return;
            }
            build = DexKitCache.loadMethod(CACHE_BUILD, classLoader);
            adders = DexKitCache.loadMethods(CACHE_ADDERS, classLoader);
        }

        if (build == null || adders == null || adders.isEmpty()) {
            build = findBuildMethod(bridge, classLoader);
            if (build == null) {
                DexKitCache.saveString(CACHE_BUILD, "missing");
                ModuleLog.line(TAG + "❌ feed request builder not found");
                return;
            }
            adders = findAdders(build);
            if (adders.isEmpty()) {
                ModuleLog.line(TAG + "❌ no param adders on the request builder");
                return;
            }
            DexKitCache.saveMethod(CACHE_BUILD, build);
            DexKitCache.saveMethods(CACHE_ADDERS, adders);
        }
        final int builderArg = builderIndex(build);

        XposedBridge.hookMethod(build, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.followingOnlyFeed) return;
                CallState st = new CallState();
                st.builder = param.args[builderArg];
                STATE.set(st);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                CallState st = STATE.get();
                STATE.remove();
                if (st == null || st.paramSeen || st.paramAdder == null || st.builder == null) return;
                try {
                    st.paramAdder.setAccessible(true);
                    st.paramAdder.invoke(st.builder, KEY, VALUE);
                    ModuleLog.probe("(IE|FollowingFeed) pagination_source appended");
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "append failed: " + t);
                }
            }
        });

        XC_MethodHook adderHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                CallState st = STATE.get();
                if (st == null || param.thisObject != st.builder || !(param.args[0] instanceof String)) return;
                String key = (String) param.args[0];
                if (KEY.equals(key)) {
                    st.paramSeen = true;
                    param.args[1] = VALUE;
                    ModuleLog.probe("(IE|FollowingFeed) pagination_source forced");
                } else if (st.paramAdder == null && isParamKey(key)) {
                    // Headers are "X-IG-…"-style; body/query params are lower_snake_case.
                    st.paramAdder = (Method) param.method;
                }
            }
        };
        for (Method m : adders) XposedBridge.hookMethod(m, adderHook);
        FeatureStatusTracker.setHooked("FollowingOnlyFeed");
        ModuleLog.line(TAG + "✅ hooked " + build.getDeclaringClass().getName() + "."
                + build.getName() + " + " + adders.size() + " adders");
    }

    private static Method findBuildMethod(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("homecoming_all", "X-IG-PRIMED-FEED-REQUEST")))) {
                if (md.isConstructor() || md.isStaticInitializer()) continue;
                Method m = md.getMethodInstance(classLoader);
                if (builderIndex(m) >= 0) return m;
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + t);
        }
        return null;
    }

    /** Index of the request-builder parameter: the one whose class declares (String, String) adders. */
    private static int builderIndex(Method build) {
        Class<?>[] p = build.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (!p[i].isPrimitive() && !p[i].getName().startsWith("java.")
                    && !p[i].getName().startsWith("android.") && !adders(p[i]).isEmpty()) return i;
        }
        return -1;
    }

    private static List<Method> findAdders(Method build) {
        return adders(build.getParameterTypes()[builderIndex(build)]);
    }

    /** Every non-static {@code void (String, String)} method declared on the request builder. */
    private static List<Method> adders(Class<?> builder) {
        List<Method> out = new ArrayList<>();
        for (Method m : builder.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && !Modifier.isStatic(m.getModifiers())
                    && p.length == 2 && p[0] == String.class && p[1] == String.class) {
                out.add(m);
            }
        }
        return out;
    }
}
