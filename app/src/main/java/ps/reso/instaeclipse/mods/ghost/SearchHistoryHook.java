package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Don't save searches (#280): profiles, tags and places you open from Search stay out of Recent.
 *
 * <p>Instagram keeps Recent in two places. On the server, a tap on a result is registered with
 * fbsearch/register_recent_search_click/, which IGNetworkInterceptor drops. On the phone,
 * RecentSearchCache (found by its "Error saving recent searches" log) adds the entry to the top of
 * its list and saves it; that add is skipped here. Searches saved before stay until cleared in
 * Instagram.
 */
public final class SearchHistoryHook {

    private static final String TAG = "(IE|SearchHistory) ";
    private static final String CACHE_KEY = "RecentSearchCache_add";

    private SearchHistoryHook() {}

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method add = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
            if (add == null) {
                List<MethodData> owners = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("Error saving recent searches. Clearing results.")));
                if (owners.isEmpty()) {
                    ModuleLog.line(TAG + "❌ RecentSearchCache not found");
                    return;
                }
                // The add: (Object) -> void that puts the new entry at the top (List.add(0, entry)).
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(owners.get(0).getClassName())
                        .paramCount(1).returnType("void")
                        .addInvoke("Ljava/util/List;->add(ILjava/lang/Object;)V")))) {
                    Method m = md.getMethodInstance(cl);
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes()[0] == List.class) continue;
                    add = m;
                    break;
                }
                if (add == null) {
                    ModuleLog.line(TAG + "❌ add method not found in " + owners.get(0).getClassName());
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, add);
            }
            XposedBridge.hookMethod(add, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.disableSearchHistory) param.setResult(null);
                }
            });
            FeatureStatusTracker.setHooked("DisableSearchHistory");
            ModuleLog.line(TAG + "✅ " + add.getDeclaringClass().getName() + "." + add.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }
}
