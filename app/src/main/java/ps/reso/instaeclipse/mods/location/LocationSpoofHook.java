package ps.reso.instaeclipse.mods.location;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.core.location.LocationCompat;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Spoofs GPS location system-wide by hooking LocationManager's last-known-location and
 * update-request paths, plus FusedLocationProviderClient's getLastLocation() where Play
 * Services is available — so Instagram (and any other app querying location) sees the
 * coordinates set via LocationPickerActivity instead of the device's real position.
 */
public class LocationSpoofHook {

    private static volatile boolean sHooked = false;

    public void install(ClassLoader classLoader) {
        if (sHooked) return;
        synchronized (LocationSpoofHook.class) {
            if (sHooked) return;
            sHooked = true;

            try {
                for (Method m : LocationManager.class.getDeclaredMethods()) {
                    String n = m.getName();
                    if (n.equals("getLastKnownLocation") || n.equals("getLastLocation")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                                if (!FeatureFlags.spoofLocation) return;
                                String provider = (param.getResult() instanceof Location)
                                        ? ((Location) param.getResult()).getProvider() : "gps";
                                param.setResult(buildFakeLocation(provider));
                            }
                        });
                    }
                    if (n.equals("requestLocationUpdates") || n.equals("requestSingleUpdate")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                                if (!FeatureFlags.spoofLocation) return;
                                LocationListener listener = findListener(param.args);
                                if (listener == null) return;
                                try {
                                    listener.onLocationChanged(buildFakeLocation("gps"));
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                }

                try {
                    Class<?> fused = classLoader.loadClass("com.google.android.gms.location.FusedLocationProviderClient");
                    for (Method m : fused.getDeclaredMethods()) {
                        if (m.getName().equals("getLastLocation") && m.getParameterTypes().length == 0) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                                    if (!FeatureFlags.spoofLocation) return;
                                    try {
                                        Object task = param.getResult();
                                        if (task == null) return;
                                        Method setResult = task.getClass().getMethod("setResult", Object.class);
                                        setResult.invoke(task, buildFakeLocation("gps"));
                                    } catch (Throwable ignored) {}
                                }
                            });
                        }
                    }
                } catch (Throwable ignored) {
                    // Play Services location client not present — LocationManager hooks still cover it
                }

                FeatureStatusTracker.setHooked("SpoofLocation");
                ModuleLog.line("(InstaEclipse | SpoofLocation): ✅ Hooked LocationManager.");
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | SpoofLocation): ❌ Install failed: " + t.getMessage());
            }
        }
    }

    private static LocationListener findListener(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof LocationListener) return (LocationListener) a;
        }
        return null;
    }

    private static Location buildFakeLocation(String provider) {
        Location loc = new Location(provider != null ? provider : "gps");
        loc.setLatitude(FeatureFlags.spoofLat);
        loc.setLongitude(FeatureFlags.spoofLng);
        loc.setAltitude(0.0d);
        loc.setAccuracy(3.0f);
        loc.setSpeed(0.0f);
        loc.setBearing(0.0f);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        loc.setVerticalAccuracyMeters(1.0f);
        loc.setSpeedAccuracyMetersPerSecond(0.1f);
        loc.setBearingAccuracyDegrees(0.1f);
        try {
            Bundle extras = new Bundle();
            extras.putBoolean(LocationCompat.EXTRA_IS_MOCK, false);
            loc.setExtras(extras);
        } catch (Throwable ignored) {}
        return loc;
    }
}
