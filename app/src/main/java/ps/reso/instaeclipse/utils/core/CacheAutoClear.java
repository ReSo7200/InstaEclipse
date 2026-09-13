package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.io.File;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Auto-clears Instagram's cache when the app is closed/backgrounded, if the cache is over a
 * user-set size (GitHub feature request).
 *
 * Clearing runs when the app goes to the background — i.e. when the last visible activity stops —
 * so nothing on screen references the files being deleted (this avoids the broken-image
 * placeholders that mid-session clearing caused). When the user returns, Instagram simply
 * rebuilds the cache.
 *
 * Instagram keeps almost nothing in getExternalCacheDir(); the bulk of its image/video cache
 * lives under getCacheDir() (Fresco image pipeline, exoplayer, etc.) and in some builds under
 * subdirs of filesDir(). We therefore measure and clear the standard cache dirs AND any obvious
 * cache-like subdirectory of filesDir (names containing "cache"). The size check uses the OS's
 * authoritative cache figure (what Android Settings shows), which is cache IG safely regenerates.
 */
public class CacheAutoClear {

    private static volatile boolean installed = false;
    private static int startedActivities = 0;           // >0 = app in foreground
    private static long lastClear = 0;                  // throttle rapid background/foreground flips

    /**
     * Registers a background listener once per process. Called from the module's main activity
     * setup; safe to call repeatedly. Replaces the old clear-at-startup behaviour.
     */
    public static void maybeClear(Context ctx) {
        if (installed || ctx == null) return;
        Context appCtx = ctx.getApplicationContext();
        if (!(appCtx instanceof Application)) return;
        installed = true;
        final Application app = (Application) appCtx;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { startedActivities++; }
            @Override public void onActivityStopped(Activity a) {
                startedActivities--;
                if (startedActivities <= 0) {
                    startedActivities = 0;
                    onAppBackgrounded(app);
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        ModuleLog.line("(IE|CacheAutoClear) background listener installed");
    }

    private static void onAppBackgrounded(Context app) {
        if (!FeatureFlags.autoClearCache) return;
        long now = System.currentTimeMillis();
        if (now - lastClear < 30_000) return;   // avoid repeat clears on quick app-switches
        lastClear = now;
        new Thread(() -> {
            try {
                int limitMb = FeatureFlags.autoClearCacheSizeMb > 0 ? FeatureFlags.autoClearCacheSizeMb : 100;
                long limit = (long) limitMb * 1024 * 1024;

                // Measure with the OS's authoritative cache figure (matches what Android Settings
                // shows and counts cache the app registered on all volumes); fall back to summing
                // the cache dirs directly when StorageStatsManager is unavailable.
                File[] targets = cacheTargets(app);
                long osCache = osCacheBytes(app);
                long dirTotal = 0;
                for (File t : targets) dirTotal += dirSize(t);
                long measured = osCache > 0 ? osCache : dirTotal;

                ModuleLog.line("(IE|CacheAutoClear) backgrounded — cache=" + (measured / (1024 * 1024))
                        + "MB (limit " + limitMb + "MB)");

                if (measured >= limit) {
                    long freed = 0;
                    for (File t : targets) { freed += dirSize(t); clearContents(t); }
                    ModuleLog.line("(IE|CacheAutoClear) ✅ cleared ~" + (freed / (1024 * 1024)) + "MB");
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|CacheAutoClear) ❌ " + t);
            }
        }, "ie-cacheclear").start();
    }

    /**
     * The set of directories treated as "cache": the standard internal + external cache dirs,
     * plus any first-level subdirectory of filesDir whose name looks cache-like (some IG builds
     * put the Fresco/media cache under files/ rather than cache/). code_cache is included too.
     */
    private static File[] cacheTargets(Context app) {
        java.util.LinkedHashSet<File> out = new java.util.LinkedHashSet<>();
        addIfDir(out, app.getCacheDir());
        addIfDir(out, app.getExternalCacheDir());
        try { addIfDir(out, app.getCodeCacheDir()); } catch (Throwable ignored) {}
        try {
            File files = app.getFilesDir();
            File[] kids = files != null ? files.listFiles() : null;
            if (kids != null) {
                for (File k : kids) {
                    if (k.isDirectory() && k.getName().toLowerCase().contains("cache")) addIfDir(out, k);
                }
            }
        } catch (Throwable ignored) {}
        return out.toArray(new File[0]);
    }

    private static void addIfDir(java.util.Set<File> set, File d) {
        if (d != null && d.isDirectory()) set.add(d);
    }

    /** OS-attributed cache bytes for this app's own uid (no permission needed); 0 if unavailable. */
    private static long osCacheBytes(Context app) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return 0;
            android.app.usage.StorageStatsManager ssm =
                    (android.app.usage.StorageStatsManager) app.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) return 0;
            android.app.usage.StorageStats st = ssm.queryStatsForUid(
                    android.os.storage.StorageManager.UUID_DEFAULT, android.os.Process.myUid());
            return st.getCacheBytes();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long dirSize(File d) {
        if (d == null || !d.exists()) return 0;
        long s = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }

    private static void clearContents(File d) {
        if (d == null || !d.isDirectory()) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
