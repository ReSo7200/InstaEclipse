package ps.reso.instaeclipse.mods.location;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Random;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Makes Instagram see the coordinates picked in LocationPickerActivity instead of the device's.
 *
 * <p>Every device fix reaches the app over Binder as a parcelled {@link Location}: platform
 * LocationManager listeners, {@code getCurrentLocation}, PendingIntent updates and Play
 * Services' fused provider alike. Rewriting the object as it is unparcelled
 * ({@code Location.CREATOR.createFromParcel}) therefore covers every delivery path at once and
 * keeps the object's fields consistent, so {@code distanceTo()} and friends agree with the
 * getters. Smaller hooks round it off:
 * <ul>
 *   <li>getters on locations built inside the process (e.g. a cached last fix) are rewritten
 *   lazily, limited to device providers so Instagram's own place objects are untouched;</li>
 *   <li>the mock flags read false, and a fix is handed out immediately on request so Instagram
 *   gets one even indoors without GPS;</li>
 *   <li>location permission checks answer "granted" while spoofing, so Instagram works with the
 *   spoofed position without ever being given the real permission (see hookPermissions).</li>
 * </ul>
 * Each fix lands a few meters from the target with a varying accuracy, like a real receiver.
 */
public class LocationSpoofHook {

    private static final String TAG = "(InstaEclipse | SpoofLocation): ";
    private static volatile boolean sHooked = false;
    private static final Random RANDOM = new Random();

    public void install(ClassLoader classLoader) {
        if (sHooked) return;
        synchronized (LocationSpoofHook.class) {
            if (sHooked) return;
            sHooked = true;
            try {
                hookUnparcel();
                hookGetters();
                hookManager();
                hookPermissions();
                FeatureStatusTracker.setHooked("SpoofLocation");
                ModuleLog.line(TAG + "✅ Hooked (parcel + getters + LocationManager + permission)");
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ Install failed: " + t.getMessage());
            }
        }
    }

    static boolean spoofing() {
        return FeatureFlags.spoofLocation && LocationPresets.valid(FeatureFlags.spoofLat, FeatureFlags.spoofLng);
    }

    /** Binder deliveries: rewrite each Location as it is created from its parcel. */
    private static void hookUnparcel() throws NoSuchMethodException {
        Method create = Location.CREATOR.getClass().getDeclaredMethod("createFromParcel", Parcel.class);
        XposedBridge.hookMethod(create, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (spoofing() && param.getResult() instanceof Location) apply((Location) param.getResult());
            }
        });
    }

    /** In-process locations: rewrite on first read. Cheap when the location is already spoofed. */
    private static void hookGetters() throws NoSuchMethodException {
        XC_MethodHook lazy = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!spoofing() || !(param.thisObject instanceof Location)) return;
                Location loc = (Location) param.thisObject;
                if (isDeviceFix(loc) && !isSpoofed(loc)) apply(loc);
            }
        };
        XposedBridge.hookMethod(Location.class.getDeclaredMethod("getLatitude"), lazy);
        XposedBridge.hookMethod(Location.class.getDeclaredMethod("getLongitude"), lazy);

        XC_MethodHook ACTIVE_ONLY_FALSE = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (spoofing()) param.setResult(false);
            }
        };

        XposedBridge.hookMethod(Location.class.getDeclaredMethod("isFromMockProvider"), ACTIVE_ONLY_FALSE);
        try {
            XposedBridge.hookMethod(Location.class.getDeclaredMethod("isMock"), ACTIVE_ONLY_FALSE); // API 31+
        } catch (NoSuchMethodException ignored) {
            // older platform: isFromMockProvider only
        }
    }

    private static void hookManager() {
        for (Method m : LocationManager.class.getDeclaredMethods()) {
            switch (m.getName()) {
                case "getLastKnownLocation":
                case "getLastLocation":
                    // No cached fix (or no permission, see hookPermissions): hand out the target.
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!spoofing()) return;
                            if (param.getThrowable() instanceof SecurityException || param.getResult() == null) {
                                param.setResult(fakeLocation(LocationManager.GPS_PROVIDER));
                            }
                        }
                    });
                    break;
                case "requestLocationUpdates":
                case "requestSingleUpdate":
                    // Deliver a fix right away; real ones that follow are rewritten by
                    // hookUnparcel(). Without the real permission the platform throws, which
                    // is swallowed so the app keeps working with the spoofed fix alone.
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!spoofing()) return;
                            if (param.getThrowable() instanceof SecurityException) param.setResult(null);
                            LocationListener listener = findListener(param.args);
                            if (listener == null) return;
                            try {
                                listener.onLocationChanged(fakeLocation(LocationManager.GPS_PROVIDER));
                            } catch (Throwable ignored) {
                                // listener threw on our fix; the real delivery still follows
                            }
                        }
                    });
                    break;
                case "getCurrentLocation":
                    // (provider[/request], CancellationSignal, Executor, Consumer<Location>)
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @SuppressWarnings("unchecked")
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!spoofing()) return;
                            java.util.concurrent.Executor executor = null;
                            java.util.function.Consumer<Location> consumer = null;
                            for (Object a : param.args) {
                                if (a instanceof java.util.concurrent.Executor) executor = (java.util.concurrent.Executor) a;
                                if (a instanceof java.util.function.Consumer) consumer = (java.util.function.Consumer<Location>) a;
                            }
                            if (executor == null || consumer == null) return;
                            java.util.function.Consumer<Location> target = consumer;
                            Location fix = fakeLocation(LocationManager.GPS_PROVIDER);
                            executor.execute(() -> target.accept(fix));
                            param.setResult(null);
                        }
                    });
                    break;
                case "isProviderEnabled":
                case "isLocationEnabled":
                case "isLocationEnabledForUser":
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (spoofing() && m.getReturnType() == boolean.class) param.setResult(true);
                        }
                    });
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Lets Instagram use the spoofed location without holding the real location permission:
     * permission checks for fine/coarse location answer "granted" while spoofing, so the app
     * asks for a fix, and the manager hooks above answer with the spoofed one. The real
     * position never reaches the app.
     */
    private static void hookPermissions() {
        try {
            Class<?> impl = Class.forName("android.app.ContextImpl");
            XC_MethodHook grant = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!spoofing() || param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String perm = (String) param.args[0];
                    if (!perm.equals(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            && !perm.equals(android.Manifest.permission.ACCESS_COARSE_LOCATION)) return;
                    if (param.args.length >= 3 && param.args[2] instanceof Integer
                            && (Integer) param.args[2] != android.os.Process.myUid()) return;
                    param.setResult(android.content.pm.PackageManager.PERMISSION_GRANTED);
                }
            };
            for (Method m : impl.getDeclaredMethods()) {
                String n = m.getName();
                if ((n.equals("checkPermission") || n.equals("checkSelfPermission") || n.equals("checkCallingOrSelfPermission"))
                        && m.getReturnType() == int.class) {
                    XposedBridge.hookMethod(m, grant);
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "permission hook failed: " + t);
        }
    }

    private static LocationListener findListener(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof LocationListener) return (LocationListener) a;
        }
        return null;
    }

    /** Platform providers only; places Instagram builds itself use their own provider names. */
    static boolean isDeviceFix(Location loc) {
        String p = loc.getProvider();
        return p != null && (p.equals(LocationManager.GPS_PROVIDER) || p.equals(LocationManager.NETWORK_PROVIDER)
                || p.equals(LocationManager.PASSIVE_PROVIDER) || p.equals("fused"));
    }

    /** Extras key marking a Location this hook already rewrote (survives copies of the object). */
    static final String MARK = "ie_spoofed";

    private static boolean isSpoofed(Location loc) {
        android.os.Bundle extras = loc.getExtras();
        return extras != null && extras.getBoolean(MARK, false);
    }

    /** Moves {@code loc} to a jittered point around the target and clears its mock marks. */
    static void apply(Location loc) {
        double[] p = LocationPresets.jitter(FeatureFlags.spoofLat, FeatureFlags.spoofLng, RANDOM.nextDouble(), RANDOM.nextDouble());
        loc.setLatitude(p[0]);
        loc.setLongitude(p[1]);
        loc.setAccuracy(6f + RANDOM.nextFloat() * 10f);
        try {
            Location.class.getMethod("setMock", boolean.class).invoke(loc, false); // API 31+
        } catch (Throwable ignored) {
            // older platform: isFromMockProvider() is hooked instead
        }
        android.os.Bundle extras = loc.getExtras() != null ? new android.os.Bundle(loc.getExtras()) : new android.os.Bundle();
        extras.remove("mockLocation");
        extras.putBoolean(MARK, true);
        loc.setExtras(extras);
    }

    static Location fakeLocation(String provider) {
        Location loc = new Location(provider);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        loc.setAltitude(40.0 + RANDOM.nextDouble() * 20.0);
        apply(loc);
        return loc;
    }

}
