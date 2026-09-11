package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class GhostReplayLimitHook {

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        hookUpdateMethod(bridge, classLoader);
        hookParseFromJsonMethod(bridge, classLoader);
        hookSyncMethod(bridge, classLoader);
    }

    /**
     * Hooks the DM thread entry update that marks the visual message as seen.
     * Skipping it keeps the local "seen" state at 0.
     */
    private void hookUpdateMethod(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.enableUnlimitedReplays) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("Replays_update", classLoader);
            if (cached != null) { XposedBridge.hookMethod(cached, hook); return; }
        }

        try {
            // The update method used to be uniquely anchored by two strings, but IG 447 dropped
            // the word "Visual" from "Visual message is missing from thread entry", so the
            // two-string AND-query no longer resolves. The method survived with the SAME stable
            // signature, so anchor on that instead:
            //   declared-synchronized void (com.instagram.model.direct.DirectThreadKey, String, String, boolean)
            // Resolve the DM thread-store class via a string it still contains on 436+447
            // ("Entry should exist before function call"), then match the signature within it.
            // com.instagram.model.direct.DirectThreadKey is a stable class name; no X.* is hardcoded.
            List<MethodData> anchor = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("Entry should exist before function call")));

            java.util.LinkedHashSet<Class<?>> candidates = new java.util.LinkedHashSet<>();
            for (MethodData md : anchor) {
                try { candidates.add(md.getMethodInstance(classLoader).getDeclaringClass()); }
                catch (Throwable ignored) {}
            }

            for (Class<?> c : candidates) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class) continue;
                    if (!java.lang.reflect.Modifier.isSynchronized(m.getModifiers())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (!p[0].getName().equals("com.instagram.model.direct.DirectThreadKey")) continue;
                    if (p[1] != String.class || p[2] != String.class || p[3] != boolean.class) continue;
                    m.setAccessible(true);
                    DexKitCache.saveMethod("Replays_update", m);
                    XposedBridge.hookMethod(m, hook);
                    ModuleLog.line("(IE|Replays) ✅ update hook → "
                            + m.getDeclaringClass().getName() + "." + m.getName());
                    return;
                }
            }
            ModuleLog.line("(IE|Replays) ❌ update method not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Replays) ❌ hookUpdateMethod: " + t);
        }
    }

    /**
     * Hooks parseFromJson that reads "seen_count" and "tap_models" from the server
     * response. After it runs, zeroes any small int field on thisObject — those are
     * the replay counters; IDs and timestamps are longs and won't match.
     */
    private void hookParseFromJsonMethod(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableUnlimitedReplays) return;
                zeroReplayCountFields(param.thisObject);
                if (param.getResult() != null && param.getResult() != param.thisObject)
                    zeroReplayCountFields(param.getResult());
            }
        };

        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("Replays_parse", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, hook);
                ModuleLog.line("[IE] ✅ Ghost Replay – parseFromJson");
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("seen_count", "tap_models")));

            List<Method> hooked = new ArrayList<>();
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    XposedBridge.hookMethod(m, hook);
                    hooked.add(m);
                    ModuleLog.line("(IE|Replays) ✅ parseFromJson hook → " + md.getClassName() + "." + md.getName());
                } catch (Throwable ignored) {}
            }
            if (hooked.isEmpty()) {
                ModuleLog.line("(IE|Replays) ❌ parseFromJson method not found");
            } else {
                DexKitCache.saveMethods("Replays_parse", hooked);
                ModuleLog.line("[IE] ✅ Ghost Replay – parseFromJson");
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Replays) ❌ hookParseFromJsonMethod: " + t);
        }
    }

    /**
     * Hooks the synchronized method (UserSession as first param, 3 params total)
     * that persists the seen/replay count to local store. Skipping it stops the
     * counter from being committed.
     */
    private void hookSyncMethod(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.enableUnlimitedReplays) param.setResult(null);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("Replays_sync", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.line("[IE] ✅ Ghost Replay – sync");
                FeatureStatusTracker.setHooked("UnlimitedReplays");
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("com.instagram.common.session.UserSession", null, null)
                            .returnType("void")));

            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!java.lang.reflect.Modifier.isSynchronized(m.getModifiers())) continue;
                    DexKitCache.saveMethod("Replays_sync", m);
                    XposedBridge.hookMethod(m, hook);
                    ModuleLog.line("[IE] ✅ Ghost Replay – sync");
                    ModuleLog.line("(IE|Replays) ✅ sync hook → " + md.getClassName() + "." + md.getName());
                    FeatureStatusTracker.setHooked("UnlimitedReplays");
                    return;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|Replays) ❌ sync method not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Replays) ❌ hookSyncMethod: " + t);
        }
    }

    /**
     * Zeroes int fields whose value is in [1, 10] on the given object.
     * Replay/seen counts are always tiny (1 or 2); IDs and timestamps are longs.
     */
    private static void zeroReplayCountFields(Object obj) {
        if (obj == null) return;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                int val = f.getInt(obj);
                if (val >= 1 && val <= 10) {
                    f.setInt(obj, 0);
                }
            }
        } catch (Throwable ignored) {}
    }
}