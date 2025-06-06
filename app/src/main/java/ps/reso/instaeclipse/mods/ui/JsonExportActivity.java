package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class JsonExportActivity extends Activity {

    private static final int SAVE_JSON_FILE = 5678;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openJsonSaver();
    }

    private void openJsonSaver() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "mc_overrides_exported.json");
        startActivityForResult(intent, SAVE_JSON_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_JSON_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                Toast.makeText(this, "❌ Invalid URI", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null || !clipboard.hasPrimaryClip()) {
                    Toast.makeText(this, "❌ Clipboard is empty.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData == null || clipData.getItemCount() == 0) {
                    Toast.makeText(this, "❌ Clipboard has no data.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                CharSequence text = clipData.getItemAt(0).getText();
                if (text == null || text.length() == 0) {
                    Toast.makeText(this, "❌ Clipboard text is empty.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                String json = text.toString().trim();
                if (!json.startsWith("{") || !json.endsWith("}")) {
                    Toast.makeText(this, "❌ Clipboard does not contain valid JSON.", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }

                try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                    outputStream.write(json.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    Toast.makeText(this, "✅ JSON exported successfully.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "❌ Failed to save file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }

                finish();

            }, 300); // Delay of 300ms
        }
    }
}
