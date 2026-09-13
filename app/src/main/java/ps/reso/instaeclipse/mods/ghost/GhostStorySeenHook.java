package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide Story / Reel Views.
 *
 * MODERN (IG 442+/447): seen-reports go through a per-session store (LX/00dS,
 * "PendingReelSeenStateStore") extending the batch base LX/04Qy ("PendingActionStore").
 * Two send paths, both building the "media/seen/" request via the item builder (LX/00dT.A04):
 *   1. Batch: reels inserted into the pending map (base A0D(String,Object)/A0E(Map)) and flushed.
 *   2. Immediate: on story-viewer stop/back, the store's A0O(item)V builds+sends one item
 *      directly (bypasses the pending map — this is the path a normal story view takes).
 * Fix: no-op BOTH the inserts and the immediate sender, scoped to the store, when the feature
 * is on. All are void / result-unused → NPE-safe (unlike nulling the builder A04).
 *
 * LEGACY (IG <=441, e.g. 436): a single {@code final void ()} method that references
 * "media/seen/" directly is the sender; nulling it suppresses the report. Kept as a fallback
 * so older Instagram versions/clones keep working.
 */
public class GhostStorySeenHook {

    private static final String CACHE_KEY        = "GhostStorySeen_v2";     // modern: store class name
    private static final String CACHE_KEY_LEGACY = "GhostStorySeen_legacy"; // legacy: sender method

    public void handleStorySeenBlock(DexKitBridge bridge) {
        // Fast path: whichever path was resolved+cached last (per IG version).
        if (DexKitCache.isCacheValid()) {
            String storeName = DexKitCache.loadString(CACHE_KEY);
            if (storeName != null) {
                try {
                    Class<?> storeClass = Class.forName(storeName, false, Module.hostClassLoader);
                    int n = hookStoreSendersScopedTo(storeClass);
                    ModuleLog.line("(InstaEclipse | StoryBlock): ✅ Hooked (cached) " + n + " sender(s) on " + storeName);
                    FeatureStatusTracker.setHooked("GhostStories");
                    return;
                } catch (Throwable t) {
                    ModuleLog.line("(InstaEclipse | StoryBlock): ⚠️ Cached modern resolve failed: " + t.getMessage());
                }
            }
            Method legacy = DexKitCache.loadMethod(CACHE_KEY_LEGACY, Module.hostClassLoader);
            if (legacy != null) {
                XposedBridge.hookMethod(legacy, legacyHook());
                ModuleLog.line("(InstaEclipse | StoryBlock): ✅ Hooked (cached legacy): "
                        + legacy.getDeclaringClass().getName() + "." + legacy.getName());
                FeatureStatusTracker.setHooked("GhostStories");
                return;
            }
        }

        // Modern first (447), then legacy (436) — keep the old path for older versions.
        if (tryModernBatchBlock(bridge)) return;
        if (tryLegacyBlock(bridge)) return;
        ModuleLog.line("(InstaEclipse | StoryBlock): ❌ Story-seen sender not resolved on any path");
    }

    /** IG 442+/447: block the reel-seen store's inserts + immediate sender. */
    private boolean tryModernBatchBlock(DexKitBridge bridge) {
        try {
            List<MethodData> seenMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("media/seen/")));
            if (seenMethods.isEmpty()) return false;

            // The (1-arg, object-returning) builder — its declaring class is the pending ITEM.
            MethodData builder = null;
            for (MethodData m : seenMethods) {
                if (m.getParamTypes().size() == 1 && !"void".equals(String.valueOf(m.getReturnType()))) {
                    builder = m;
                    break;
                }
            }
            if (builder == null) return false; // older shape (no builder) → let legacy handle it

            String itemClassName = builder.getClassName();
            String storeClassName = null;
            for (MethodData caller : builder.getCallers()) {
                String cn = caller.getClassName();
                if (cn != null && !cn.equals(itemClassName)) { storeClassName = cn; break; }
            }
            if (storeClassName == null) return false;

            Class<?> storeClass = Class.forName(storeClassName, false, Module.hostClassLoader);
            int n = hookStoreSendersScopedTo(storeClass);
            if (n == 0) return false;

            DexKitCache.saveString(CACHE_KEY, storeClassName);
            ModuleLog.line("(InstaEclipse | StoryBlock): ✅ Hooked " + n + " sender(s) on store " + storeClassName
                    + " (item builder: " + itemClassName + "." + builder.getName() + ")");
            FeatureStatusTracker.setHooked("GhostStories");
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | StoryBlock): ⚠️ modern path error: " + t.getMessage());
            return false;
        }
    }

    /** IG <=441 (436): the sender is a single {@code final void ()} method referencing "media/seen/". */
    private boolean tryLegacyBlock(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("media/seen/")));
            for (MethodData method : methods) {
                Method m;
                try { m = method.getMethodInstance(Module.hostClassLoader); }
                catch (Throwable e) { continue; }
                int mod = m.getModifiers();
                if (Modifier.isFinal(mod) && m.getReturnType() == void.class && m.getParameterCount() == 0) {
                    DexKitCache.saveMethod(CACHE_KEY_LEGACY, m);
                    XposedBridge.hookMethod(m, legacyHook());
                    ModuleLog.line("(InstaEclipse | StoryBlock): ✅ Hooked (legacy): "
                            + method.getClassName() + "." + method.getName());
                    FeatureStatusTracker.setHooked("GhostStories");
                    return true;
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | StoryBlock): ⚠️ legacy path error: " + t.getMessage());
        }
        return false;
    }

    private static XC_MethodHook legacyHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.isGhostStory) param.setResult(null);
            }
        };
    }

    /**
     * Hooks (to no-op, when isGhostStory) the modern reel-seen store's send paths:
     *   - base inserts: (String,Object)->void and (Map)->void   [batch path]
     *   - store's own immediate sender: (item)->void            [A0O, direct path]
     * Base inserts are shared with other stores, so they are gated on the receiver being an
     * instance of {@code storeClass}; the immediate sender is declared on the store itself.
     * Returns the number of methods hooked.
     */
    private int hookStoreSendersScopedTo(final Class<?> storeClass) {
        XC_MethodHook gate = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.isGhostStory
                        && param.thisObject != null
                        && storeClass.isInstance(param.thisObject)) {
                    param.setResult(null); // void: skip the send / queue — nothing reported
                }
            }
        };

        int hooked = 0;

        // Batch inserts live on the base class (PendingActionStore).
        for (Class<?> c = storeClass.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != void.class) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean perItem = p.length == 2 && p[0] == String.class && p[1] == Object.class;
                boolean bulk    = p.length == 1 && Map.class.isAssignableFrom(p[0]);
                if (!perItem && !bulk) continue;
                try { m.setAccessible(true); XposedBridge.hookMethod(m, gate); hooked++; found = true; }
                catch (Throwable t) { ModuleLog.line("(InstaEclipse | StoryBlock): ⚠️ insert hook failed: " + t.getMessage()); }
            }
            if (found) break; // inserts all live on the same base level
        }

        // Immediate sender: the store's OWN void method taking a single non-framework object
        // (the pending item, e.g. LX/00dT). That is A0O — builds+sends one seen directly.
        for (Method m : storeClass.getDeclaredMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1) continue;
            Class<?> pt = p[0];
            if (pt.isPrimitive() || pt == String.class || Map.class.isAssignableFrom(pt)
                    || java.util.Collection.class.isAssignableFrom(pt)) continue;
            try { m.setAccessible(true); XposedBridge.hookMethod(m, gate); hooked++; }
            catch (Throwable t) { ModuleLog.line("(InstaEclipse | StoryBlock): ⚠️ immediate-sender hook failed: " + t.getMessage()); }
        }

        return hooked;
    }
}
