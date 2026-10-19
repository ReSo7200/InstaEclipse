package ps.reso.instaeclipse.mods.extras;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * "Airplane mode": keeps Instagram's realtime (MQTT) connection down while the feed, profiles and
 * other REST browsing keep working. With MQTT down there is no presence ("active now"), no typing
 * or live seen events and no in-app incoming calls; DMs are neither pushed in nor sent live.
 *
 * <p>{@code RealtimeClientManager} keeps its method names: {@code startMqttClient} is skipped
 * while the mode is on, and {@link #apply} stops or restarts every live manager the moment the
 * toggle changes in the Instagram menu, so no restart is needed. Incoming realtime events are
 * dropped too.
 */
public class AirplaneModeHook {

    private static final String TAG = "(InstaEclipse | Airplane): ";
    private static final String MANAGER = "com.instagram.realtimeclient.RealtimeClientManager";
    private static final List<WeakReference<Object>> MANAGERS = new ArrayList<>();

    private static Method start;
    private static Method stop;
    private static volatile boolean loggedBlock;

    public void install(ClassLoader cl) {
        Class<?> manager = XposedHelpers.findClass(MANAGER, cl);
        try {
            start = manager.getDeclaredMethod("startMqttClient");
            stop = manager.getDeclaredMethod("stopMqttClient");
            start.setAccessible(true);
            stop.setAccessible(true);
        } catch (NoSuchMethodException e) {
            ModuleLog.line(TAG + "❌ MQTT start/stop methods missing");
            return;
        }

        XC_MethodHook track = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                synchronized (MANAGERS) {
                    MANAGERS.add(new WeakReference<>(param.thisObject));
                }
            }
        };
        for (java.lang.reflect.Constructor<?> c : manager.getDeclaredConstructors()) {
            XposedBridge.hookMethod(c, track);
        }

        XposedBridge.hookMethod(start, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.airplaneMode) return;
                param.setResult(null);
                if (!loggedBlock) {
                    loggedBlock = true;
                    ModuleLog.line(TAG + "MQTT start blocked");
                }
            }
        });

        try {
            Class<?> handler = XposedHelpers.findClass("com.instagram.realtimeclient.MainRealtimeEventHandler", cl);
            XposedBridge.hookAllMethods(handler, "onRealtimeEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.airplaneMode) param.setResult(null);
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "event handler hook failed: " + t);
        }

        FeatureStatusTracker.setHooked("airplaneMode");
        ModuleLog.line(TAG + "✅ hooked");
    }

    /** Applies the current {@link FeatureFlags#airplaneMode} to every live realtime manager. */
    public static void apply() {
        Method m = FeatureFlags.airplaneMode ? stop : start;
        if (m == null) return;
        List<Object> live = new ArrayList<>();
        synchronized (MANAGERS) {
            for (int i = MANAGERS.size() - 1; i >= 0; i--) {
                Object o = MANAGERS.get(i).get();
                if (o == null) MANAGERS.remove(i);
                else live.add(o);
            }
        }
        for (Object o : live) {
            try {
                m.invoke(o);
            } catch (Throwable t) {
                ModuleLog.line(TAG + m.getName() + " failed: " + t);
            }
        }
        ModuleLog.line(TAG + m.getName() + " on " + live.size() + " manager(s)");
    }
}
