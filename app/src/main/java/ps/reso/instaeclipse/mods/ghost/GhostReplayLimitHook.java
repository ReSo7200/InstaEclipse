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
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("Entry should exist before function call",
                                    "Visual message is missing from thread entry")));

            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() != void.class) continue;
                    DexKitCache.saveMethod("Replays_update", m);
                    XposedBridge.hookMethod(m, hook);
                    XposedBridge.log("(IE|Replays) ✅ update hook → " + md.getClassName() + "." + md.getName());
                    return;
                } catch (Throwable ignored) {}
            }
            XposedBridge.log("(IE|Replays) ❌ update method not found");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Replays) ❌ hookUpdateMethod: " + t);
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
                XposedBridge.log("[IE] ✅ Ghost Replay – parseFromJson");
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
                    XposedBridge.log("(IE|Replays) ✅ parseFromJson hook → " + md.getClassName() + "." + md.getName());
                } catch (Throwable ignored) {}
            }
            if (hooked.isEmpty()) {
                XposedBridge.log("(IE|Replays) ❌ parseFromJson method not found");
            } else {
                DexKitCache.saveMethods("Replays_parse", hooked);
                XposedBridge.log("[IE] ✅ Ghost Replay – parseFromJson");
            }
        } catch (Throwable t) {
            XposedBridge.log("(IE|Replays) ❌ hookParseFromJsonMethod: " + t);
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
                XposedBridge.log("[IE] ✅ Ghost Replay – sync");
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
                    XposedBridge.log("[IE] ✅ Ghost Replay – sync");
                    XposedBridge.log("(IE|Replays) ✅ sync hook → " + md.getClassName() + "." + md.getName());
                    FeatureStatusTracker.setHooked("UnlimitedReplays");
                    return;
                } catch (Throwable ignored) {}
            }
            XposedBridge.log("(IE|Replays) ❌ sync method not found");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Replays) ❌ hookSyncMethod: " + t);
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