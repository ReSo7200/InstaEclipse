package ps.reso.instaeclipse.mods.devops.config;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.DataBackup;

/**
 * Picks an InstaEclipse data backup (zip) and sends it to Instagram in pieces
 * (DataBackup.ACTION_RESTORE), where it is put back. Only an Instagram variant can be the target.
 */
public class DataImportActivity extends Activity {

    private static final int PICK = 4321;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        // Opens where backups are saved (Download/InstaEclipse).
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/InstaEclipse"));
        startActivityForResult(intent, PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK) { finish(); return; }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, getString(R.string.json_cancelled), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String target = getIntent().getStringExtra("target_package");
        if (target == null || !ps.reso.instaeclipse.utils.ipc.CompanionBridge.isTrustedHost(this, target)) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, getString(R.string.json_target_not_specified), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            List<String> pieces = DataBackup.pieces(in);
            String id = UUID.randomUUID().toString();
            for (int i = 0; i < pieces.size(); i++) {
                Intent b = new Intent(DataBackup.ACTION_RESTORE);
                b.setPackage(target);
                b.putExtra("id", id);
                b.putExtra("index", i);
                b.putExtra("total", pieces.size());
                b.putExtra("piece", pieces.get(i));
                sendBroadcast(b);
            }
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, getString(R.string.json_sent), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, getString(R.string.ig_data_restore_failed), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
