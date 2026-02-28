package ps.reso.instaeclipse.mods.devops.config;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class ConfigManager {

    // Import meta config from clipboard
    public static void importConfigFromClipboard(Context context) {

        android.app.ProgressDialog progress = new android.app.ProgressDialog(context);
        progress.setMessage(I18n.t(context, R.string.ig_config_importing));
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            try {
                if (clipboard == null || !clipboard.hasPrimaryClip()) throw new IllegalStateException("Empty clipboard");

                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData == null || clipData.getItemCount() == 0) throw new IllegalStateException("Empty clipboard");

                CharSequence clipText = clipData.getItemAt(0).getText();
                if (clipText == null || clipText.length() == 0) throw new IllegalStateException("Empty clipboard");

                String json = clipText.toString().trim();
                if (!json.startsWith("{") || !json.endsWith("}")) throw new IllegalArgumentException("Clipboard is not valid JSON");

                File dest = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (FileOutputStream fos = new FileOutputStream(dest, false)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }

                // (Optional) clear clipboard to avoid stale re-imports later
                try { clipboard.setPrimaryClip(ClipData.newPlainText("", "")); } catch (Exception ignored) {}

                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    Toast.makeText(context, I18n.t(context, R.string.ig_config_import_success), Toast.LENGTH_LONG).show();
                    XposedBridge.log("InstaEclipse | ✅ JSON imported from clipboard into mc_overrides.json");
                });
            } catch (Exception e) {
                XposedBridge.log("InstaEclipse | ❌ Clipboard import failed: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    Toast.makeText(context, I18n.t(context, R.string.ig_config_import_failed), Toast.LENGTH_LONG).show();
                });
            } finally {
                // 100% guarantee the flag is OFF after an attempt
                FeatureFlags.isImportingConfig = false;
            }
        }).start();
    }


    // Export meta config to Device
    public static void exportCurrentDevConfig(Context context) {
        if (!FeatureFlags.isExportingConfig) {
            return;
        }
        try {
            File source = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
            if (!source.exists()) {
                XposedBridge.log("InstaEclipse | ❌ mc_overrides.json not found.");
                return;
            }

            StringBuilder jsonBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(source))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line).append("\n");
                }
            }

            String jsonContent = jsonBuilder.toString().trim();

            if (!jsonContent.startsWith("{") || !jsonContent.endsWith("}")) {
                XposedBridge.log("InstaEclipse | ❌ mc_overrides.json does not contain valid JSON.");
                return;
            }

            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("json", jsonContent);
                clipboard.setPrimaryClip(clip);
                XposedBridge.log("InstaEclipse | ✅ Copied mc_overrides.json to clipboard.");
            }

        } catch (Exception e) {
            XposedBridge.log("InstaEclipse | ❌ Failed to export config: " + e.getMessage());
        } finally {
            FeatureFlags.isExportingConfig = false;
        }

    }
}
