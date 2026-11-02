package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

public class GhostViewOnceHook {

    private static final String CACHE_KEY = "GhostViewOnce_v2";

    public void handleViewOnceBlock(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods(CACHE_KEY, Module.hostClassLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, buildViewOnceHook());
                ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked (cached) " + cached.size());
                FeatureStatusTracker.setHooked("GhostViewOnce");
                return;
            }
        }

        List<Method> hooked = new java.util.ArrayList<>();
        // Primary anchor (IG 447+): the visual view-once "item seen" reporter was inlined
        // into the handler. It contains BOTH the stable REST path and the visual media type
        // "raven_media". The voice sibling uses the SAME path but "voice_media", so ANDing
        // "raven_media" selects only the view-once (visual) reporter — never voice.
        // Disappearing media, then media shown as permanent (Unlock View-Once/Twice), then older builds.
        collect(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("direct_v2/visual_threads/%s/item_seen/", "raven_media")), hooked);
        collect(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("direct_v2/visual_threads/%s/visual_items/%s/seen/")), hooked);
        // Legacy fallback (IG 436 and older): the reporter inlined the marker name
        // "visual_item_seen" rather than the REST path. Same (3-param, void) shape.
        if (hooked.isEmpty()) {
            collect(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("visual_item_seen")), hooked);
        }

        collectItemSeen(bridge, hooked);

        if (hooked.isEmpty()) {
            ModuleLog.line("(InstaEclipse | ViewOnce): ❌ No view-once seen-reporter method found");
            return;
        }
        DexKitCache.saveMethods(CACHE_KEY, hooked);
        FeatureStatusTracker.setHooked("GhostViewOnce");
    }

    /** The seen mutation sent for disappearing messages. */
    private void collectItemSeen(DexKitBridge bridge, List<Method> hooked) {
        try {
            for (MethodData method : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings("IGDirectItemSeenMutation", "xig_direct_item_seen")))) {
                if (!String.valueOf(method.getReturnType()).contains("void")) continue;
                try {
                    Method m = method.getMethodInstance(Module.hostClassLoader);
                    if (hooked.contains(m)) continue;
                    XposedBridge.hookMethod(m, buildViewOnceHook());
                    hooked.add(m);
                    ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked item seen "
                            + method.getClassName() + "." + method.getName());
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | ViewOnce): ❌ " + e.getMessage());
        }
    }

    /**
     * Finds (3-param, void) methods matching the query and hooks them. Both anchors resolve
     * to a visual-only "report seen" handler, so unconditional setResult(null) is safe.
     */
    /** Hooks every (3-param, void) reporter the query finds. */
    private void collect(DexKitBridge bridge, FindMethod query, List<Method> hooked) {
        try {
            for (MethodData method : DexKitQueryCache.findMethod(bridge, query)) {
                if (method.getParamTypes().size() != 3) continue;
                if (!String.valueOf(method.getReturnType()).contains("void")) continue;
                try {
                    Method m = method.getMethodInstance(Module.hostClassLoader);
                    if (hooked.contains(m)) continue;
                    XposedBridge.hookMethod(m, buildViewOnceHook());
                    hooked.add(m);
                    ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked "
                            + method.getClassName() + "." + method.getName());
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | ViewOnce): ❌ " + e.getMessage());
        }
    }

    private static XC_MethodHook buildViewOnceHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                // The hooked method is the visual/view-once seen-reporter only (selected via
                // "raven_media" on 447 / hardcoded "visual_item_seen" on 436). Skipping it
                // suppresses just the view-once "seen/opened" report; voice/other sends are
                // different methods and are untouched.
                if (FeatureFlags.isGhostViewOnce || (param.args.length > 3 && FeatureFlags.isGhostSeen)) param.setResult(null);
            }
        };
    }
}
