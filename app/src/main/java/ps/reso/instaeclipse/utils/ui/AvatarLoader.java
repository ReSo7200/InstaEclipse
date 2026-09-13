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

    /** Load {@code url} into {@code target} as a circle. The target is tagged with the URL so a
     *  late response for a reused view is ignored. */
    public static void loadCircular(final String url, final ImageView target) {
        if (url == null || target == null) return;
        target.setTag(url);

        Bitmap cached = CACHE.get(url);
        if (cached != null) { apply(target, cached); return; }

        POOL.execute(() -> {
            final Bitmap bmp = fetch(url);
            if (bmp == null) return;
            CACHE.put(url, bmp);
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

    private static Bitmap fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true); // github.com/<user>.png 302s to the CDN
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "InstaEclipse");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
