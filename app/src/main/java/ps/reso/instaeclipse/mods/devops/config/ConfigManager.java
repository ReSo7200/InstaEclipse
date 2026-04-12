package ps.reso.instaeclipse.mods.devops.config;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class ConfigManager {

    public static void importConfigFromJson(Context context, String json) {
        new Thread(() -> {
            try {
                if (json == null || json.isEmpty()) throw new IllegalArgumentException("Empty JSON");
                if (!json.startsWith("{") || !json.endsWith("}")) throw new IllegalArgumentException("Not valid JSON");

                File dest = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (FileOutputStream fos = new FileOutputStream(dest, false)) {
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context.getApplicationContext(), I18n.t(context, R.string.ig_toast_config_imported), Toast.LENGTH_LONG).show();
                    XposedBridge.log("InstaEclipse | ✅ JSON imported into mc_overrides.json");
                });
            } catch (Exception e) {
                XposedBridge.log("InstaEclipse | ❌ Import failed: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context.getApplicationContext(), I18n.t(context, R.string.ig_toast_config_import_failed), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }
}