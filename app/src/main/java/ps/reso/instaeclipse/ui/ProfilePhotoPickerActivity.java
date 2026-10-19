package ps.reso.instaeclipse.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.profile.SpoofAvatar;

/**
 * Profile spoofer: picks the picture for your own profile when asked from Instagram's menu
 * (Instagram's process can't open a picker for the module). No UI of its own: opens the system
 * picker, stores the picture (SpoofAvatar) as the spoofAvatar setting, sends it to Instagram and
 * closes.
 */
public class ProfilePhotoPickerActivity extends Activity {

    private static final int PICK = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // picker already open (recreated)
        try {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, getString(R.string.ig_profile_spoofer_choose_photo)), PICK);
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode != PICK || uri == null) {
            finish();
            return;
        }
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String b64 = SpoofAvatar.encode(app.getContentResolver(), uri);
            runOnUiThread(() -> {
                if (b64 == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(app,
                            getString(R.string.ig_profile_spoofer_photo_failed), Toast.LENGTH_SHORT).show();
                } else {
                    save(app, b64);
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(app,
                            getString(R.string.ig_profile_spoofer_photo_saved), Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }, "IE-ProfilePhoto").start();
    }

    /** Stores the picture in the companion's settings and sends it to Instagram. */
    public static void save(android.content.Context context, String b64) {
        Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        b.putExtra("key", "spoofAvatar");
        b.putExtra("value", b64);
        CommonUtils.broadcastToInstagram(context, b);
        try {
            new java.io.File(context.getApplicationInfo().dataDir + "/shared_prefs/instaeclipse_cache.xml")
                    .setReadable(true, false);
        } catch (Throwable ignored) {}
    }
}
