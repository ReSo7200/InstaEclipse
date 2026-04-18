package ps.reso.instaeclipse.mods.feed;

import android.view.MotionEvent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class ReelsClientHook {

    private static final String TARGET_CLASS = "instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout";

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(TARGET_CLASS, classLoader,
                    "onInterceptTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) return;
                            param.setResult(true);
                            FeatureStatusTracker.setHooked("DisableReels");
                        }
                    });
            XposedBridge.log("(InstaEclipse | ReelsClient): ✅ Hooked reels swipe layout");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | ReelsClient): ❌ " + t.getMessage());
        }
    }
}
