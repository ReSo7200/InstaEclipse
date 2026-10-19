package ps.reso.instaeclipse.utils.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ps.reso.instaeclipse.R;

/**
 * Downloads to a chosen folder are saved by this app, started from Instagram. When the system
 * keeps the app asleep (battery optimization, Samsung's Sleeping apps, Xiaomi autostart) Instagram
 * can't start it and those downloads fall back to Download. So the app asks once to run
 * unrestricted: when a folder is chosen, and on opening while a folder is set.
 */
public final class BackgroundAccess {

    private static final String ASKED = "backgroundAccessAsked";

    private BackgroundAccess() {}

    public static boolean unrestricted(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /** Asks if still restricted. {@code always}: also when asked before (a folder was just chosen). */
    public static void askIfNeeded(Context ctx, SharedPreferences prefs, boolean always) {
        if (unrestricted(ctx) || (!always && prefs.getBoolean(ASKED, false))) return;
        prefs.edit().putBoolean(ASKED, true).apply();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.background_access_title)
                .setMessage(R.string.background_access_message)
                .setPositiveButton(R.string.background_access_allow, (d, w) -> request(ctx))
                .setNegativeButton(R.string.background_access_later, null)
                .show();
    }

    @SuppressLint("BatteryLife") // sideloaded companion: running unrestricted is what downloads need
    private static void request(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Throwable t) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName())));
            } catch (Throwable ignored) { }
        }
    }

    /**
     * The chosen download folder is reachable only while its grant is kept (it can be lost after a
     * reinstall or clearing data, while the setting comes back through sync).
     */
    public static boolean folderGrantLost(Context ctx, String uri) {
        if (uri == null || uri.isEmpty() || !uri.startsWith("content://")) return false;
        for (android.content.UriPermission p : ctx.getContentResolver().getPersistedUriPermissions())
            if (p.getUri().toString().equals(uri) && p.isWritePermission()) return false;
        return true;
    }

    /** Asks to choose the folder again; {@code choose} opens the folder picker. */
    public static void askToRechooseFolder(Context ctx, Runnable choose) {
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.folder_access_lost_title)
                .setMessage(R.string.folder_access_lost_message)
                .setPositiveButton(R.string.folder_access_lost_choose, (d, w) -> choose.run())
                .setNegativeButton(R.string.background_access_later, null)
                .show();
    }

    /** Android 9 and older save to shared storage with the storage permission. */
    public static boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }
}
