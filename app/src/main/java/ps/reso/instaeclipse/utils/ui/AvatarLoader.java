package ps.reso.instaeclipse.utils.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal, dependency-free async image loader for small circular avatars (e.g. GitHub profile
 * pictures on the Home contributor cards). Decodes off the main thread, caches decoded bitmaps in
 * memory, applies a circular mask, and reveals the target ImageView only on success — so a missing
 * link or a failed fetch simply leaves the monogram fallback showing underneath.
 */
public final class AvatarLoader {

    private AvatarLoader() {}

    // ~6 MB of decoded avatars is plenty for a short credits list.
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static Bitmap getCached(String url) {
        if (url == null) return null;
        return CACHE.get(url);
    }

    public static Bitmap fetchBitmap(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        Bitmap cached = CACHE.get(url);
        if (cached != null) return cached;
        Bitmap bmp = fetch(url);
        if (bmp != null) {
            CACHE.put(url, bmp);
        }
        return bmp;
    }

    /** Load {@code url} into {@code target} as a circle. The target is tagged with the URL so a
     *  late response for a reused view is ignored. */
    public static void loadCircular(final String url, final ImageView target) {
        if (url == null || target == null) return;
        target.setTag(url);

        Bitmap cached = CACHE.get(url);
        if (cached != null) { apply(target, cached); return; }

        POOL.execute(() -> {
            final Bitmap bmp = fetchBitmap(url);
            if (bmp == null) return;
            MAIN.post(() -> {
                if (url.equals(target.getTag())) apply(target, bmp);
            });
        });
    }

    private static void apply(ImageView iv, Bitmap bmp) {
        RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(iv.getResources(), bmp);
        d.setCircular(true);
        iv.setImageDrawable(d);
        iv.setVisibility(View.VISIBLE);
    }

    private static Bitmap fetch(String urlString) {
        HttpURLConnection conn = null;
        try {
            String currentUrl = urlString;
            for (int redirects = 0; redirects < 5; redirects++) {
                conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "InstaEclipse");
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    if (newUrl == null) return null;
                    conn.disconnect();
                    currentUrl = newUrl;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) return null;
                try (InputStream in = conn.getInputStream()) {
                    return BitmapFactory.decodeStream(in);
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
