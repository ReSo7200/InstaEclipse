package ps.reso.instaeclipse.mods.extras;

import android.view.View;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ViewPager2Input;

/**
 * Reels viewer controls, keyed on IG's own "android_purge_…" markers:
 * <ul>
 *   <li>Auto-scroll: IG's opt-in auto-scroll gate
 *   {@code ClipsViewerExperimentUtil.isOptInAutoscrollEnabled} is forced true, which unlocks IG's
 *   native "Auto scroll" option in the Reels ⋯ menu.</li>
 *   <li>Lock scrolling: {@code ClipsViewPagerImpl.getViewAtIndex} runs on every page bind; it
 *   toggles the pager's ViewPager2 user input (see {@link ViewPager2Input}), and pull-to-refresh
 *   is disabled with it.</li>
 * </ul>
 */
public class ReelsControlsHook {

    private static final String TAG = "(InstaEclipse | Reels): ";

    public void install(DexKitBridge bridge, ClassLoader cl) {
        installAutoScroll(bridge, cl);
        installScrollLock(bridge, cl);
    }


    private void installAutoScroll(DexKitBridge bridge, ClassLoader cl) {
        Method gate = ExtrasLookup.method("Extras_ReelsAutoScroll", cl, () -> {
            for (MethodData md : ExtrasLookup.byStrings(bridge,
                    "android_purge_26_q2_ClipsViewerExperimentUtil_isOptInAutoscrollEnabled")) {
                if ("boolean".equals(md.getReturnTypeName())) return md.getMethodInstance(cl);
            }
            return null;
        });
        if (gate == null) return;
        XposedBridge.hookMethod(gate, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.reelsAutoScroll) param.setResult(true);
            }
        });
        FeatureStatusTracker.setHooked("reelsAutoScroll");
        ModuleLog.line(TAG + "✅ auto-scroll gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
    }

    private void installScrollLock(DexKitBridge bridge, ClassLoader cl) {
        Method bind = ExtrasLookup.method("Extras_ReelsPagerBind", cl, () -> {
            for (MethodData md : ExtrasLookup.byStrings(bridge, "android_purge_26_q3_ClipsViewPagerImpl_getViewAtIndex")) {
                if (!md.isConstructor()) return md.getMethodInstance(cl);
            }
            return null;
        });
        Field pager = bind == null ? null : viewPagerField(bind.getDeclaringClass());
        if (pager != null) {
            // setUserInputEnabled() when the build kept it, else IG's renamed mUserInputEnabled
            // field (resolved via DexKit, shared with DistractionFreeUIHook's main tab pager).
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                private boolean failLogged;

                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!(pager.get(param.thisObject) instanceof View vp)) return;
                    if (!ViewPager2Input.setEnabled(vp, !FeatureFlags.reelsLockScroll) && !failLogged) {
                        failLogged = true;
                        ModuleLog.line(TAG + "❌ ViewPager2 user-input flag not resolved");
                    }
                }
            });
            FeatureStatusTracker.setHooked("reelsLockScroll");
            ModuleLog.line(TAG + "✅ pager " + bind.getDeclaringClass().getName() + "." + pager.getName());
        }

        // Pull-to-refresh on top of the Reels pager would still reload the feed.
        try {
            Class<?> refresh = XposedHelpers.findClass("instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout", cl);
            XposedHelpers.findAndHookMethod(refresh, "onInterceptTouchEvent", android.view.MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.reelsLockScroll) param.setResult(false);
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "refresh layout hook failed: " + t);
        }
    }

    private static Field viewPagerField(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (f.getType().getName().equals("androidx.viewpager2.widget.ViewPager2")) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }
}
