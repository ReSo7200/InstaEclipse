package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XSharedPreferences;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Instagram side of the companion -> Instagram settings sync, for changes the live broadcast
 * missed: Instagram was not running, or was frozen in the background when the companion sent
 * them.
 *
 * <p>The companion stamps every setting it sends with {@code __ts_<key>} in its
 * {@code instaeclipse_cache} prefs (see {@link CommonUtils#broadcastToInstagram}), which the
 * hooked app can read through XSharedPreferences. On startup and whenever an activity resumes,
 * a companion value is applied when its stamp is newer than both the last companion value
 * applied for that key ({@code __applied_<key>}) and the last change made to that key from
 * inside Instagram ({@code __igts_<key>}, written by {@link SettingsManager#saveAllFlags}), so an
 * older companion value never overwrites a newer in-app change.
 */
public final class CompanionSync {

    static final String TS = "__ts_";
    static final String APPLIED = "__applied_";
    static final String IG_TS = "__igts_";
    private static final long MIN_INTERVAL_MS = 1500;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InstaEclipse-Sync");
        t.setDaemon(true);
        return t;
    });

    private static XSharedPreferences companion;
    private static long lastPull;

    private CompanionSync() {}

    /** Pull on a background thread (activity resume). */
    public static void pullAsync(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        WORKER.execute(() -> pull(app));
    }

    /** Applies newer companion settings; returns true when something changed. */
    public static synchronized boolean pull(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (lastPull != 0 && now - lastPull < MIN_INTERVAL_MS) return false;
        lastPull = now;
        try {
            // Small file, throttled, off the main thread: always re-read (hasFileChanged() missed
            // same-size rewrites).
            if (companion == null) {
                companion = new XSharedPreferences(CommonUtils.MY_PACKAGE_NAME, "instaeclipse_cache");
            }
            companion.reload();
            Map<String, ?> all = companion.getAll();
            if (all == null || all.isEmpty()) return false;

            SharedPreferences ig = context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor e = ig.edit();
            int applied = 0;
            boolean airplaneChanged = false;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String tsKey = entry.getKey();
                if (!tsKey.startsWith(TS) || !(entry.getValue() instanceof Long)) continue;
                String key = tsKey.substring(TS.length());
                long ts = (Long) entry.getValue();
                if (ts <= ig.getLong(APPLIED + key, 0) || ts <= ig.getLong(IG_TS + key, 0)) continue;
                Object v = all.get(key);
                if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) e.putInt(key, (Integer) v);
                else if (v instanceof String) e.putString(key, (String) v);
                else continue;
                e.putLong(APPLIED + key, ts);
                if ("airplaneMode".equals(key)) airplaneChanged = true;
                applied++;
            }
            if (applied == 0) return false;
            e.commit();
            SettingsManager.loadAllFlags(context);
            FeatureManager.refreshFeatureStatus();
            // Airplane mode stops / restarts the live connection right away, as in the menu.
            if (airplaneChanged) ps.reso.instaeclipse.mods.extras.AirplaneModeHook.apply();
            ModuleLog.line("(InstaEclipse) Sync: applied " + applied + " setting(s) changed in the companion app");
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse) Sync: companion pull failed: " + t);
            return false;
        }
    }

    /** Live broadcast applied: remember its stamp so the next pull doesn't apply it again. */
    public static void markApplied(Context context, String key, long ts) {
        if (key == null || ts <= 0) return;
        context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE)
                .edit().putLong(APPLIED + key, ts).apply();
    }
}
