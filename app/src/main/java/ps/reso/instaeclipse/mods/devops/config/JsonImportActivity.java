package ps.reso.instaeclipse.mods.devops.config;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

import ps.reso.instaeclipse.R;

public class JsonImportActivity extends Activity {

    private static final int PICK_JSON_FILE = 1234;
    static final String ACTION_IMPORT_CONFIG = "ps.reso.instaeclipse.ACTION_IMPORT_CONFIG";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.json_select_config)), PICK_JSON_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_JSON_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    String json = readStream(inputStream).trim();
                    if (json.startsWith("{") && json.endsWith("}")) {
                        String targetPackage = getIntent().getStringExtra("target_package");
                        if (targetPackage == null || targetPackage.isEmpty()) {
                            Toast.makeText(this, getString(R.string.json_target_not_specified), Toast.LENGTH_LONG).show();
                        } else {
                            String action = getIntent().getStringExtra("broadcast_action");
                            if (action == null || action.isEmpty()) action = ACTION_IMPORT_CONFIG;
                            Intent broadcast = new Intent(action);
                            broadcast.setPackage(targetPackage);
                            broadcast.putExtra("json_content", json);
                            sendBroadcast(broadcast);
                            Toast.makeText(this, getString(R.string.json_sent), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.json_not_valid), Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.json_read_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, getString(R.string.json_cancelled), Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    @SuppressLint("NewApi")
    private String readStream(InputStream inputStream) {
        Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8).useDelimiter("\\A");
        return scanner.hasNext() ? scanner.next() : "";
    }
}
