package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Modern flavor (libxposed API 102): mirrors the companion's "instaeclipse_cache" prefs into the
 * framework's remote preferences group of the same name, which the hooked app reads through
 * {@code getRemotePreferences} ({@code ModernBackend#companionPrefs}) on a cold start. A full copy
 * when the framework service connects, then every change as it happens. The existing settings
 * code keeps writing the local prefs as before.
 */
public final class RemotePrefsMirror {

    private static final String TAG = "IE|RemotePrefs";
    private static final String GROUP = "instaeclipse_cache";

    private static SharedPreferences local;
    private static volatile SharedPreferences remote;

    // held strongly: SharedPreferences keeps its listeners weakly
    private static final SharedPreferences.OnSharedPreferenceChangeListener ON_CHANGE = (prefs, key) -> {
        SharedPreferences r = remote;
        if (r == null) return;
        try {
            if (key == null) copyAll(prefs, r); // the local file was cleared
            else copyKey(prefs, r, key);
        } catch (Throwable t) {
            Log.w(TAG, "mirror " + key + ": " + t);
        }
    };

    private RemotePrefsMirror() {}

    public static synchronized void start(Context context) {
        if (local != null) return;
        local = CommonUtils.companionCache(context);
        local.registerOnSharedPreferenceChangeListener(ON_CHANGE);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService service) {
                try {
                    SharedPreferences r = service.getRemotePreferences(GROUP);
                    copyAll(local, r);
                    remote = r;
                    Log.i(TAG, "connected to " + service.getFrameworkName() + " " + service.getFrameworkVersion()
                            + " (API " + service.getApiVersion() + "), mirrored " + local.getAll().size() + " values");
                } catch (Throwable t) {
                    Log.w(TAG, "remote preferences unavailable: " + t);
                }
            }

            @Override
            public void onServiceDied(@NonNull XposedService service) {
                remote = null;
            }
        });
    }

    /** True once the framework service connected and the settings are mirrored. */
    public static boolean isConnected() {
        return remote != null;
    }

    private static void copyAll(SharedPreferences from, SharedPreferences to) {
        SharedPreferences.Editor e = to.edit();
        e.clear();
        for (Map.Entry<String, ?> entry : from.getAll().entrySet()) put(e, entry.getKey(), entry.getValue());
        e.apply();
    }

    private static void copyKey(SharedPreferences from, SharedPreferences to, String key) {
        SharedPreferences.Editor e = to.edit();
        Object v = from.getAll().get(key);
        if (v == null) e.remove(key);
        else put(e, key, v);
        e.apply();
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor e, String key, Object v) {
        if (v instanceof String s) e.putString(key, s);
        else if (v instanceof Boolean b) e.putBoolean(key, b);
        else if (v instanceof Integer i) e.putInt(key, i);
        else if (v instanceof Long l) e.putLong(key, l);
        else if (v instanceof Float f) e.putFloat(key, f);
        else if (v instanceof Set<?> set) e.putStringSet(key, (Set<String>) set);
    }
}
