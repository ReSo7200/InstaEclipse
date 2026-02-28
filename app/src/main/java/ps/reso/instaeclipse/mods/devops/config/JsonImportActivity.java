package ps.reso.instaeclipse.mods.devops.config;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class JsonImportActivity extends Activity {

    private static final int PICK_JSON_FILE = 1234;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeatureFlags.isImportingConfig = false;
        openJsonPicker();
    }

    private void openJsonPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.ig_config_select_json)), PICK_JSON_FILE);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_JSON_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    String json = readStream(inputStream).trim();

                    // Validate before enabling the flag
                    if (json.startsWith("{") && json.endsWith("}")) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("json", json);
                        clipboard.setPrimaryClip(clip);

                        FeatureFlags.isImportingConfig = true; // <- only now turn it ON
                        //Toast.makeText(this, "Config copied, returning to import…", Toast.LENGTH_SHORT).show();
                    } else {
                        FeatureFlags.isImportingConfig = false;
                        Toast.makeText(this, getString(R.string.ig_config_not_valid_json), Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    FeatureFlags.isImportingConfig = false; // <- make sure we reset on error
                    Toast.makeText(this, getString(R.string.ig_config_failed_read_file, e.getMessage()), Toast.LENGTH_LONG).show();
                }
            } else {
                // User pressed back / cancelled
                FeatureFlags.isImportingConfig = false; // <- ensure OFF on cancel
                Toast.makeText(this, getString(R.string.ig_config_cancelled_no_file), Toast.LENGTH_SHORT).show();
            }
        }
        finish(); // Done, return to Instagram
    }

    private String readStream(InputStream inputStream) {
        @SuppressLint({"NewApi", "LocalSuppress"}) Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }
}
