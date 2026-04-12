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
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * Prevents disappearing/vanish-mode messages from being deleted locally.
 *
 * Three hooks:
 *  1. hookVanishLocalDelete — no-ops the method that drives local deletion of
 *                             vanish/ephemeral messages. Found via "igThreadIgid"
 *                             + (DirectThreadKey, boolean) → void signature.
 *  2. hookServerPing        — blocks the outgoing mark_ephemeral_item_ranges_viewed
 *                             server call (belt-and-suspenders with the Interceptor).
 *  3. hookExpiryParser      — zeroes message_expiration_timestamp_ms long fields after
 *                             model parsing so no local countdown timer is started.
 */
public class GhostEphemeralKeepHook {

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        hookVanishLocalDelete(bridge, classLoader);
        hookServerPing(bridge);
        hookExpiryParser(bridge, classLoader);
    }

    /**
     * No-ops the method that deletes ephemeral/vanish messages from the local thread model.
     * Found via "igThreadIgid" combined with (DirectThreadKey, boolean) → void signature.
     */
    private void hookVanishLocalDelete(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.keepEphemeralMessages) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("Ephemeral_vanish", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("igThreadIgid")
                            .paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")
                            .returnType("void")));

            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    DexKitCache.saveMethod("Ephemeral_vanish", m);
                    XposedBridge.hookMethod(m, hook);
                    XposedBridge.log("(IE|Ephemeral) ✅ vanish-local-delete hook → "
                            + md.getClassName() + "." + md.getName());
                    FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                    return;
                } catch (Throwable ignored) {}
            }
            XposedBridge.log("(IE|Ephemeral) ❌ vanish-local-delete method not found");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Ephemeral) ❌ hookVanishLocalDelete: " + t.getMessage());
        }
    }

    /** Blocks any void method that dispatches mark_ephemeral_item_ranges_viewed. */
    private void hookServerPing(DexKitBridge bridge) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.keepEphemeralMessages) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("Ephemeral_ping", Module.hostClassLoader);
            if (cached != null) { XposedBridge.hookMethod(cached, hook); return; }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("mark_ephemeral_item_ranges_viewed")));

            for (MethodData md : methods) {
                Method method;
                try {
                    method = md.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }
                if (method.getReturnType() != void.class) continue;

                DexKitCache.saveMethod("Ephemeral_ping", method);
                XposedBridge.hookMethod(method, hook);
                XposedBridge.log("(IE|Ephemeral) ✅ server-ping hook → "
                        + md.getClassName() + "." + md.getName());
                return;
            }
            XposedBridge.log("(IE|Ephemeral) ❌ server-ping method not found");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Ephemeral) ❌ hookServerPing: " + t.getMessage());
        }
    }

    /**
     * Zeroes any long field on parsed model objects whose value looks like a future
     * epoch-ms timestamp, so the local expiry countdown never starts.
     */
    private void hookExpiryParser(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.keepEphemeralMessages) return;
                clearExpiryTimestamp(param.thisObject);
                Object result = param.getResult();
                if (result != null && result != param.thisObject) clearExpiryTimestamp(result);
            }
        };

        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("Ephemeral_expiry", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, hook);
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("message_expiration_timestamp_ms")));

            List<Method> hooked = new java.util.ArrayList<>();
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    XposedBridge.hookMethod(m, hook);
                    hooked.add(m);
                    XposedBridge.log("(IE|Ephemeral) ✅ expiry-parser hook → "
                            + md.getClassName() + "." + md.getName());
                } catch (Throwable ignored) {}
            }
            if (hooked.isEmpty()) {
                XposedBridge.log("(IE|Ephemeral) ❌ no expiry-parser methods hooked");
            } else {
                DexKitCache.saveMethods("Ephemeral_expiry", hooked);
            }
        } catch (Throwable t) {
            XposedBridge.log("(IE|Ephemeral) ❌ hookExpiryParser: " + t.getMessage());
        }
    }

    private static void clearExpiryTimestamp(Object obj) {
        if (obj == null) return;
        long now = System.currentTimeMillis();
        long year2100 = 4_102_444_800_000L;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType() != long.class) continue;
                f.setAccessible(true);
                long val = f.getLong(obj);
                if (val > now && val < year2100) {
                    f.setLong(obj, 0L);
                }
            }
        } catch (Throwable ignored) {}
    }
}
