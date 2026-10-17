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
 * Instagram side of the settings sync. Instagram -> companion: {@link #pushAsync}. The rest of
 * this class is the companion -> Instagram half, for changes the live broadcast
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

    /**
     * Instagram -> companion: pushes Instagram's settings (with their __igts_ / __applied_
     * stamps) to the companion's {@link PrefsSyncProvider}, which keeps any newer companion
     * change (see {@link PrefsMerge}). Called at startup and after every in-app change, so the
     * companion shows the same values without having to reach a live Instagram process.
     * Back-to-back requests are coalesced into one push.
     */
    public static void pushAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!PUSH_PENDING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            PUSH_PENDING.set(false);
            push(app);
        });
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PUSH_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static void push(Context context) {
        try {
            Map<String, ?> all = context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE).getAll();
            android.os.Bundle b = new android.os.Bundle();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String k = entry.getKey();
                Object v = entry.getValue();
                if (PrefsMerge.isSharedStamp(k, v)) b.putLong(k, (Long) v);
                else if (!PrefsMerge.isSharedSetting(k, v)) continue;
                else if (v instanceof Boolean) b.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) b.putInt(k, (Integer) v);
                else b.putString(k, (String) v);
            }
            android.os.Bundle r = context.getContentResolver()
                    .call(PrefsSyncProvider.URI, PrefsSyncProvider.METHOD_PUSH, null, b);
            int n = r != null ? r.getInt(PrefsSyncProvider.RESULT_UPDATED, 0) : 0;
            if (n > 0) ModuleLog.line("(InstaEclipse) Sync: updated " + n + " setting(s) in the companion app");
        } catch (Throwable t) {
            // Companion not installed / too old (no provider): the ACTION_REQUEST_PREFS reply
            // still syncs when the companion is opened while Instagram is running.
            ModuleLog.line("(InstaEclipse) Sync: companion push failed: " + t);
        }
    }

    /** Live broadcast applied: remember its stamp so the next pull doesn't apply it again. */
    public static void markApplied(Context context, String key, long ts) {
        if (key == null || ts <= 0) return;
        context.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE)
                .edit().putLong(APPLIED + key, ts).apply();
    }
}
