package ps.reso.instaeclipse.share;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.ShareLinkDownloadHook;

/**
 * "Download with InstaEclipse" in the Android share sheet (#236): takes a shared Instagram
 * post/reel link and opens it in Instagram with a marker; the module (running in Instagram)
 * then saves the media and sends Instagram back to the background. No UI of its own.
 */
public class ShareDownloadActivity extends Activity {
    private static final Pattern LINK = Pattern.compile(
            "https?://(?:www\\.|m\\.)?instagram\\.com/(?:[A-Za-z0-9._]+/)?(?:p|reel|reels|tv)/[A-Za-z0-9_-]+[^\\s]*");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String link = findLink(getIntent());
        if (link == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.share_dl_no_link, Toast.LENGTH_SHORT).show();
        } else {
            Intent ig = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    .setPackage("com.instagram.android")
                    .putExtra(ShareLinkDownloadHook.EXTRA, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(ig);
            } catch (ActivityNotFoundException e) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.share_dl_no_instagram, Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    private static String findLink(Intent i) {
        if (i == null) return null;
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            text = t != null ? t.toString() : null;
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            text = i.getData().toString();
        }
        if (text == null) return null;
        Matcher m = LINK.matcher(text);
        return m.find() ? m.group() : null;
    }
}
