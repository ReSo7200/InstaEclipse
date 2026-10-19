package ps.reso.instaeclipse.mods.ghost;

import android.view.Window;
import android.view.WindowManager;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Strips FLAG_SECURE from every Window.setFlags / Window.addFlags call so the
 * user can take screenshots even when Instagram would normally block them.
 *
 * Instagram sets FLAG_SECURE on several windows (DM threads, stories, reels)
 * which causes the system to show "App doesn't allow screenshots". We intercept
 * both entry points before the flag reaches the WindowManager so no patching
 * of Instagram's internal classes is required.
 */
public class ScreenshotPermissionHook {

    public void install(ClassLoader classLoader) {
        try {
            // Hook Window.setFlags(int flags, int mask)
            XposedHelpers.findAndHookMethod(Window.class, "setFlags",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!FeatureFlags.allowScreenshots) return;
                            param.args[0] = (int) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
                            param.args[1] = (int) param.args[1] & ~WindowManager.LayoutParams.FLAG_SECURE;
                        }
                    });

            // Hook Window.addFlags(int flags)
            XposedHelpers.findAndHookMethod(Window.class, "addFlags",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!FeatureFlags.allowScreenshots) return;
                            param.args[0] = (int) param.args[0] & ~WindowManager.LayoutParams.FLAG_SECURE;
                        }
                    });

            ModuleLog.line("(InstaEclipse | ScreenshotPermission): ✅ Hooked Window.setFlags + addFlags");
            FeatureStatusTracker.setHooked("AllowScreenshots");

        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | ScreenshotPermission): ❌ " + e.getMessage());
        }
    }
}
