package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class GhostViewOnceHook {

    public void handleViewOnceBlock(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostViewOnce", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, buildViewOnceHook());
                ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked (cached): "
                        + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostViewOnce");
                return;
            }
        }

        // Primary anchor (IG 447+): the visual view-once "item seen" reporter was inlined
        // into the handler. It contains BOTH the stable REST path and the visual media type
        // "raven_media". The voice sibling uses the SAME path but "voice_media", so ANDing
        // "raven_media" selects only the view-once (visual) reporter — never voice.
        if (tryHookViewOnce(bridge,
                FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("direct_v2/visual_threads/%s/item_seen/", "raven_media")),
                "endpoint+raven_media")) {
            return;
        }

        // Legacy fallback (IG 436 and older): the reporter inlined the marker name
        // "visual_item_seen" rather than the REST path. Same (3-param, void) shape.
        if (tryHookViewOnce(bridge,
                FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("visual_item_seen")),
                "visual_item_seen")) {
            return;
        }

        ModuleLog.line("(InstaEclipse | ViewOnce): ❌ No view-once seen-reporter method found");
    }

    /**
     * Finds a (3-param, void) method matching the query and hooks it. Both anchors resolve
     * to a visual-only "report seen" handler, so unconditional setResult(null) is safe.
     */
    private boolean tryHookViewOnce(DexKitBridge bridge, FindMethod query, String tag) {
        try {
            List<MethodData> methods = bridge.findMethod(query);
            for (MethodData method : methods) {
                if (method.getParamTypes().size() != 3) continue;
                if (!String.valueOf(method.getReturnType()).contains("void")) continue;

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                DexKitCache.saveMethod("GhostViewOnce", reflectMethod);
                XposedBridge.hookMethod(reflectMethod, buildViewOnceHook());
                ModuleLog.line("(InstaEclipse | ViewOnce): ✅ Hooked (" + tag + "): "
                        + method.getClassName() + "." + method.getName());
                FeatureStatusTracker.setHooked("GhostViewOnce");
                return true;
            }
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | ViewOnce): ❌ Exception (" + tag + "): " + e.getMessage());
        }
        return false;
    }

    private static XC_MethodHook buildViewOnceHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // The hooked method is the visual/view-once seen-reporter only (selected via
                // "raven_media" on 447 / hardcoded "visual_item_seen" on 436). Skipping it
                // suppresses just the view-once "seen/opened" report; voice/other sends are
                // different methods and are untouched.
                if (FeatureFlags.isGhostViewOnce) param.setResult(null);
            }
        };
    }
}
