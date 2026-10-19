package ps.reso.instaeclipse.mods.devops.config;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ConfigManager {

    /** Always restores the bundled default config. */
    public static void restoreDefaultConfig(Context context, String moduleApkPath) {
        new Thread(() -> {
            try {
                String json = readAssetFromApk(moduleApkPath, "assets/default_mc_overrides.json");
                if (json == null || json.isEmpty()) return;
                writeConfigJson(context, json);
                new Handler(Looper.getMainLooper()).post(() ->
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context.getApplicationContext(),
                                I18n.t(context, R.string.ig_toast_default_config_restored), Toast.LENGTH_SHORT).show()
                );
                ModuleLog.line("InstaEclipse | ✅ Default config restored.");
            } catch (Exception e) {
                ModuleLog.line("InstaEclipse | ❌ Default config restore failed: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() ->
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context.getApplicationContext(),
                                I18n.t(context, R.string.ig_toast_config_import_failed), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    public static void importConfigFromJson(Context context, String json) {
        new Thread(() -> {
            try {
                writeConfigJson(context, json);
                new Handler(Looper.getMainLooper()).post(() -> {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context.getApplicationContext(), I18n.t(context, R.string.ig_toast_config_imported), Toast.LENGTH_LONG).show();
                    ModuleLog.line("InstaEclipse | ✅ JSON imported into mc_overrides.json");
                });
            } catch (Exception e) {
                ModuleLog.line("InstaEclipse | ❌ Import failed: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() ->
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context.getApplicationContext(), I18n.t(context, R.string.ig_toast_config_import_failed), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    /** Reads an asset entry from the module APK (accessed as a zip). */
    private static String readAssetFromApk(String apkPath, String assetEntry) throws Exception {
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(assetEntry);
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry);
                 Scanner sc = new Scanner(is, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                return sc.hasNext() ? sc.next() : null;
            }
        }
    }

    /** Validates and writes JSON to Instagram's mc_overrides.json file. */
    private static void writeConfigJson(Context context, String json) throws Exception {
        if (json == null || json.isEmpty()) throw new IllegalArgumentException("Empty JSON");
        // Parse for real: a truncated/garbled file must never replace Instagram's overrides.
        new org.json.JSONObject(json);

        File dest = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Temp file + rename, so a crash mid-write can't leave a half file behind.
        ps.reso.instaeclipse.utils.core.AtomicFiles.write(dest, json);
    }
}
