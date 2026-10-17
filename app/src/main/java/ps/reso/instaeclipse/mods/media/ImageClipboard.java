package ps.reso.instaeclipse.mods.media;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Copy a photo straight to the clipboard (#228) instead of saving it to the gallery.
 *
 * Clipboard images must be content URIs other apps may read. Instagram ships
 * androidx.core.content.FileProvider (authority com.instagram.fileprovider on the official app)
 * whose path config exposes {@code <cache-path name="cache" path="/">}, with
 * grantUriPermissions="true". So we download into IG's cache dir and put
 * content://&lt;authority&gt;/cache/&lt;file&gt; on the clipboard; the clipboard service grants the
 * pasting app read access. The URI is built by hand (FileProvider's path strategy) rather than via
 * FileProvider's helpers, and the authority is looked up at runtime so clones work too.
 */
final class ImageClipboard {

    private static final String DIR = "ie_clip";
    private static volatile String authority;

    private ImageClipboard() {}

    static void copy(Context ctx, String imageUrl) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_copying_image), Toast.LENGTH_SHORT).show();
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                String auth = authority(ctx);
                if (auth == null) throw new IllegalStateException("no FileProvider");
                File dir = new File(ctx.getCacheDir(), DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File out = new File(dir, "image_" + System.currentTimeMillis() + ".jpg");
                FeedVideoDownloadHook.downloadToFileAndGetType(imageUrl, out);
                Uri uri = new Uri.Builder().scheme("content").authority(auth)
                        .appendPath("cache").appendPath(DIR).appendPath(out.getName()).build();
                FeedVideoDownloadHook.mainHandler.post(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newUri(ctx.getContentResolver(), "Image", uri));
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_image_copied), Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        fail(ctx, t);
                    }
                });
            } catch (Throwable t) {
                FeedVideoDownloadHook.mainHandler.post(() -> fail(ctx, t));
            }
        });
    }

    private static void fail(Context ctx, Throwable t) {
        ModuleLog.line("(IE|CopyImage) ❌ " + t);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())),
                Toast.LENGTH_SHORT).show();
    }

    /** Authority of the host's androidx FileProvider (com.instagram.fileprovider on official IG). */
    private static String authority(Context ctx) {
        if (authority != null) return authority;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_PROVIDERS);
            if (pi.providers != null) {
                for (ProviderInfo p : pi.providers) {
                    if ("androidx.core.content.FileProvider".equals(p.name) && p.authority != null) {
                        authority = p.authority.split(";")[0];
                        return authority;
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|CopyImage) provider lookup: " + t);
        }
        return null;
    }
}
