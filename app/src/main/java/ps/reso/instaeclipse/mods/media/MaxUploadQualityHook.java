package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Force "Upload at highest quality" always on.
 *
 * Instagram gates full-quality photo/video upload behind a user switch in
 * Settings → Data usage and media quality → "Upload at highest quality", stored as the int
 * preference "high_quality_media_upload" (1 = on). A single stable helper reads it:
 *
 *     static boolean A00(session) { return prefs.getInt("high_quality_media_upload", 0) == 1; }
 *
 * Every upload path consults that helper, so forcing its boolean result to true makes Instagram
 * behave exactly as if the user had enabled the switch — no transcode internals touched, the
 * app's own highest-quality code path does the work. The helper is matched by the literal
 * preference string plus its (one-arg → boolean) shape, never an obfuscated class name, so it
 * survives version bumps as long as the preference key is unchanged.
 */
public class MaxUploadQualityHook {

    private static final String TAG = "(IE|MaxUploadQuality) ";
    private static final String PREF_KEY = "high_quality_media_upload";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(List.of(PREF_KEY))
                            .returnType("boolean")
                            .paramCount(1)));

            int n = 0;
            for (MethodData md : results) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!FeatureFlags.maxUploadQuality) return;
                            param.setResult(Boolean.TRUE);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {}
            }

            if (n == 0) {
                ModuleLog.line(TAG + "❌ highest-quality-upload getter not found");
                if (FeatureFlags.maxUploadQuality) FeatureStatusTracker.setBroken("MaxUploadQuality");
                return;
            }

            FeatureStatusTracker.setHooked("MaxUploadQuality");
            ModuleLog.line(TAG + "✅ hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ install – " + t);
            if (FeatureFlags.maxUploadQuality) FeatureStatusTracker.setBroken("MaxUploadQuality");
        }
    }
}
