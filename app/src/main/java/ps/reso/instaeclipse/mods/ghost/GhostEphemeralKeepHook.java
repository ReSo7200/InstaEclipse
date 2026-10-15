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
import ps.reso.instaeclipse.utils.log.ModuleLog;

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
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.keepEphemeralMessages) param.setResult(null);
            }
        };

        // IG 447+ (verified 447/451/452): the removal itself lives in two methods of the thread
        // model (X.01eV on 452, X.04r2 on 451, X.01gH on 447), called on chat exit, by the
        // per-thread expiry timer and when a thread is loaded from disk:
        //   (Z)Z  remove expired disappearing messages — unique string below
        //   ()Z   remove vanish-mode ("is_shh_mode") messages — the class's only ()boolean
        // Both return "anything removed"; callers only commit on true, so false = keep all.
        XC_MethodHook keep = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.keepEphemeralMessages) param.setResult(false);
            }
        };
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("Ephemeral_vanish_v2", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, keep);
                FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                return;
            }
        }
        try {
            List<Method> found = new java.util.ArrayList<>();
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("Unexpected null timestamp for disappearing message, removed message")
                            .paramTypes("boolean")
                            .returnType("boolean")))) {
                Method expired = md.getMethodInstance(classLoader);
                found.add(expired);
                Method shh = null;
                int candidates = 0;
                for (Method m : expired.getDeclaringClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class
                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) { shh = m; candidates++; }
                }
                if (candidates == 1) found.add(shh);
                else ModuleLog.line("(IE|Ephemeral) ⚠️ vanish-mode purge ambiguous (" + candidates + " ()Z methods)");
                break;
            }
            if (!found.isEmpty()) {
                for (Method m : found) {
                    XposedBridge.hookMethod(m, keep);
                    ModuleLog.line("(IE|Ephemeral) ✅ local purge hook → "
                            + m.getDeclaringClass().getName() + "." + m.getName());
                }
                DexKitCache.saveMethods("Ephemeral_vanish_v2", found);
                FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                return;
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Ephemeral) ⚠️ local purge lookup: " + t.getMessage());
        }

        // Legacy (<=447): the orchestrating (DirectThreadKey, boolean) method.
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
                    ModuleLog.line("(IE|Ephemeral) ✅ vanish-local-delete hook → "
                            + md.getClassName() + "." + md.getName());
                    FeatureStatusTracker.setHooked("KeepEphemeralMessages");
                    return;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|Ephemeral) ❌ vanish-local-delete method not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Ephemeral) ❌ hookVanishLocalDelete: " + t.getMessage());
        }
    }

    /** Blocks any void method that dispatches mark_ephemeral_item_ranges_viewed. */
    private void hookServerPing(DexKitBridge bridge) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
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
                ModuleLog.line("(IE|Ephemeral) ✅ server-ping hook → "
                        + md.getClassName() + "." + md.getName());
                return;
            }
            ModuleLog.line("(IE|Ephemeral) ❌ server-ping method not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Ephemeral) ❌ hookServerPing: " + t.getMessage());
        }
    }

    /**
     * Zeroes any long field on parsed model objects whose value looks like a future
     * epoch-ms timestamp, so the local expiry countdown never starts.
     */
    private void hookExpiryParser(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepEphemeralMessages) return;
                Object result = param.getResult();
                if (result != null) clearExpiryTimestamp(result);
            }
        };

        // Key renamed from "Ephemeral_expiry": that list included the static per-FIELD parser
        // (X.00zF.A01 on 452, called once for every JSON field of every DM message — ~5k calls
        // per 40 s of feed scrolling). Hooking such a hot method made ART's JIT compile its
        // hook backup, which crashes Instagram (SIGSEGV in DexCache::SetResolvedType) on
        // Vector/Android 13. It also never had an object to clean (static, void).
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("Ephemeral_expiry_v2", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, hook);
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("message_expiration_timestamp_ms")));

            // Hook only whole-object parse entry points (one call per parsed model, returning
            // it): the parser classes' parseFromJsonParser/unsafeParseFromJson, plus any anchor
            // method that returns an object. Never the static void per-field helpers.
            java.util.Set<Method> targets = new java.util.LinkedHashSet<>();
            java.util.Set<Class<?>> owners = new java.util.LinkedHashSet<>();
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    owners.add(m.getDeclaringClass());
                    if (m.getReturnType() != void.class && !m.getReturnType().isPrimitive()) targets.add(m);
                } catch (Throwable ignored) {}
            }
            for (Class<?> c : owners) {
                for (Method m : c.getDeclaredMethods()) {
                    String n = m.getName();
                    if ((n.equals("parseFromJsonParser") || n.equals("unsafeParseFromJson"))
                            && m.getReturnType() != void.class) targets.add(m);
                }
            }
            List<Method> hooked = new java.util.ArrayList<>();
            for (Method m : targets) {
                try {
                    XposedBridge.hookMethod(m, hook);
                    hooked.add(m);
                    ModuleLog.line("(IE|Ephemeral) ✅ expiry-parser hook → "
                            + m.getDeclaringClass().getName() + "." + m.getName());
                } catch (Throwable ignored) {}
            }
            if (hooked.isEmpty()) {
                ModuleLog.line("(IE|Ephemeral) ❌ no expiry-parser methods hooked");
            } else {
                DexKitCache.saveMethods("Ephemeral_expiry_v2", hooked);
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Ephemeral) ❌ hookExpiryParser: " + t.getMessage());
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
