package ps.reso.instaeclipse.mods.devops.config;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ps.reso.instaeclipse.R;

public class JsonExportActivity extends Activity {

    private static final int SAVE_JSON_FILE = 5678;
    private String jsonContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        jsonContent = getIntent().getStringExtra("json_content");
        if (jsonContent == null || jsonContent.isEmpty()) {
            Toast.makeText(this, getString(R.string.export_no_config_data), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String fileName = getIntent().getStringExtra("file_name");
        intent.putExtra(Intent.EXTRA_TITLE, (fileName != null && !fileName.isEmpty()) ? fileName : "mc_overrides_exported.json");
        startActivityForResult(intent, SAVE_JSON_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SAVE_JSON_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri == null) {
                    Toast.makeText(this, getString(R.string.export_invalid_uri), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    assert out != null;
                    out.write(jsonContent.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Toast.makeText(this, getString(R.string.export_config_success), Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.export_config_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                }
            }
            finish();
        }
    }
}
