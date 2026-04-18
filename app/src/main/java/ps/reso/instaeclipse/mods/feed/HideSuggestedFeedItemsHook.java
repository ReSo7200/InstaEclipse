package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class HideSuggestedFeedItemsHook {

    private static final String CACHE_KEY_PARSER = "FeedItemParserClass";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSuggestionsInFeed) return;

                Object result = param.getResult();
                if (result == null) return;

                for (Field f : result.getClass().getDeclaredFields()) {
                    if (f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        try {
                            f.setAccessible(true);
                            if (f.get(result) != null) return;
                        } catch (Throwable ignored) {}
                    }
                }

                param.setResult(null);
                FeatureStatusTracker.setHooked("HideSuggestionsInFeed");
            }
        };

        if (DexKitCache.isCacheValid()) {
            String cached = DexKitCache.loadString(CACHE_KEY_PARSER);
            if (cached != null) {
                try {
                    hookBridgeMethod(cached, classLoader, filterHook);
                    return;
                } catch (Throwable t) {
                    XposedBridge.log("(InstaEclipse | HideSuggested): ⚠️ Cache hook failed: " + t.getMessage());
                }
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create().usingStrings(
                                    "clips_netego", "suggested_users", "Unknown FeedItem Type"
                            )
                    )
            );

            if (methods.isEmpty()) {
                methods = bridge.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create().usingStrings("clips_netego", "media_or_ad")
                        )
                );
            }

            if (methods.isEmpty()) {
                methods = bridge.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create().usingStrings("clips_netego", "stories_netego", "bloks_netego")
                        )
                );
            }

            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse | HideSuggested): ❌ FeedItem parser not found.");
                return;
            }

            String targetClass = methods.get(0).getClassName();
            DexKitCache.saveString(CACHE_KEY_PARSER, targetClass);
            hookBridgeMethod(targetClass, classLoader, filterHook);
            XposedBridge.log("(InstaEclipse | HideSuggested): ✅ Hooked: " + targetClass);

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | HideSuggested): ❌ Exception: " + t.getMessage());
        }
    }

    private void hookBridgeMethod(String className, ClassLoader classLoader, XC_MethodHook hook)
            throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, classLoader);
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isBridge()) {
                XposedBridge.hookMethod(m, hook);
            }
        }
    }
}
