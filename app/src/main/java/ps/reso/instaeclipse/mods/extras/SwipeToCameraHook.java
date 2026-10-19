package ps.reso.instaeclipse.mods.extras;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Stops a sideways swipe on the home feed from opening the camera. {@code SwipeNavigationContainer}
 * settles every swipe through {@code setInternalPosition(PositionConfig)}; a swipe-sourced config
 * ("swipe") with a negative target position means "go to the camera panel". Its target is reset
 * to 0 so the container springs back to the feed. The camera button still works.
 */
public class SwipeToCameraHook {

    private static final String TAG = "(InstaEclipse | SwipeCamera): ";
    private static final String PKG = "com.instagram.ui.swipenavigation.container.";

    public void install(ClassLoader cl) {
        Class<?> container = XposedHelpers.findClass(PKG + "SwipeNavigationContainer", cl);
        Class<?> config = XposedHelpers.findClass(PKG + "PositionConfig", cl);

        Field position = null;
        List<Field> strings = new ArrayList<>();
        for (Field f : config.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == float.class) {
                if (position != null) {
                    ModuleLog.line(TAG + "❌ PositionConfig shape changed");
                    return;
                }
                position = f;
            } else if (f.getType() == String.class) {
                strings.add(f);
            }
        }
        if (position == null) return;
        position.setAccessible(true);
        for (Field f : strings) f.setAccessible(true);

        Method clamped;
        try {
            clamped = container.getDeclaredMethod("getClampedPosition");
            clamped.setAccessible(true);
        } catch (NoSuchMethodException e) {
            clamped = null;
        }

        final Field target = position;
        final Method current = clamped;
        XposedHelpers.findAndHookMethod(container, "setInternalPosition", config, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.disableSwipeToCamera) return;
                Object cfg = param.args[0];
                if (cfg == null || target.getFloat(cfg) >= 0f || !fromSwipe(cfg, strings)) return;
                // Only a swipe that starts on the feed; leaving an already-open camera still works.
                if (current != null && (float) current.invoke(param.thisObject) < 0f) return;
                target.setFloat(cfg, 0f);
            }
        });
        FeatureStatusTracker.setHooked("disableSwipeToCamera");
        ModuleLog.line(TAG + "✅ hooked");
    }

    private static boolean fromSwipe(Object cfg, List<Field> strings) throws IllegalAccessException {
        for (Field f : strings) {
            if ("swipe".equals(f.get(cfg))) return true;
        }
        return false;
    }
}
