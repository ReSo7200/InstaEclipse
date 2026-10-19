package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Skips Instagram's optional "sync contacts" and "share location" onboarding pages. Bloks screens
 * are opened through a {@code (Context, IgBloksScreenConfig)} launcher on the screen fetcher
 * (the class logging "BKDataFetcher.fetch"); when the fetcher's screen id is one of those two
 * pages the launch is dropped. Android permission APIs are never touched.
 */
public final class OnboardingPromptHook {

    private static final String TAG = "(InstaEclipse | Onboarding): ";
    private static final String CACHE = "Extras_OnboardingPrompt";
    private static final String CONTACTS = "com.bloks.www.bloks.ig.ndx.ci.entry.screen";
    private static final String LOCATION = "com.bloks.www.bloks.ig.ndx.ls.entry.screen";

    public void install(DexKitBridge bridge, ClassLoader cl) {
        List<Method> launchers = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE, cl) : null;
        if (launchers == null || launchers.isEmpty()) {
            launchers = new ArrayList<>();
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("BKDataFetcher.fetch")
                    .paramTypes("android.content.Context", "com.instagram.bloks.hosting.IgBloksScreenConfig")
                    .returnType("void")))) {
                try {
                    Method m = md.getMethodInstance(cl);
                    if (!Modifier.isStatic(m.getModifiers())) launchers.add(m);
                } catch (Throwable ignored) {}
            }
            if (launchers.isEmpty()) {
                ModuleLog.line(TAG + "❌ Bloks screen launcher not found");
                return;
            }
            DexKitCache.saveMethods(CACHE, launchers);
        }
        for (Method m : launchers) {
            List<Field> fields = new ArrayList<>();
            for (Field f : m.getDeclaringClass().getDeclaredFields()) {
                if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    if (!FeatureFlags.hideOnboardingPrompts) return;
                    for (Field field : fields) {
                        Object id = field.get(p.thisObject);
                        if (CONTACTS.equals(id) || LOCATION.equals(id)) {
                            p.setResult(null);
                            ModuleLog.line(TAG + "skipped " + id);
                            return;
                        }
                    }
                }
            });
        }
        FeatureStatusTracker.setHooked("hideOnboardingPrompts");
        ModuleLog.line(TAG + "✅ hooked " + launchers.size() + " launcher(s)");
    }
}
