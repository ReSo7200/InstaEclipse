package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;

/**
 * Profile Picture Downloader
 *
 * Strategy:
 *   Hook View.onAttachedToWindow() globally, filter for "expanded_profile_pic" by resource name
 *   (cached as an int ID after first resolution). When found, attach a long-press listener that
 *   reads the ImageUrl field (getUrl()) from IgImageView and downloads via FeedVideoDownloadHook helpers.
 *
 * Gated by FeatureFlags.enableProfileDownload.
 */
public class ProfilePicDownloadHook {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String  HOOKED_TAG  = "ie_profile_dl";

    /** Cached resource ID for "expanded_profile_pic"; 0 = not yet resolved. */
    private static volatile int expandedPicViewId = 0;

    // ── Install ───────────────────────────────────────────────────────────────

    public static void install() {
        // Mark status before hook setup so the toast shows correctly
        if (FeatureFlags.enableProfileDownload) {
            FeatureStatusTracker.setEnabled("ProfileDownload");
            FeatureStatusTracker.setHooked("ProfileDownload");
        }

        // Hook View.onAttachedToWindow — fires once per view attachment, works for any
        // window type (Activity, Dialog, BottomSheet) without relying on layout listeners.
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableProfileDownload) return;
                View v = (View) param.thisObject;
                int vid = v.getId();
                if (vid == View.NO_ID) return;

                // Fast path: cached int comparison (only resolves resource name once)
                if (expandedPicViewId != 0) {
                    if (vid != expandedPicViewId) return;
                } else {
                    try {
                        String name = v.getResources().getResourceEntryName(vid);
                        if (!"expanded_profile_pic".equals(name)) return;
                        expandedPicViewId = vid;
                    } catch (Throwable ignored) { return; }
                }

                injectLongPress(v);
            }
        });
    }

    // ── UI injection ──────────────────────────────────────────────────────────

    private static void injectLongPress(View view) {
        try {
            view.setTag(HOOKED_TAG);
            view.setOnLongClickListener(v -> {
                // Resolve activity lazily at tap time — context is valid at this point
                Context ctx = v.getContext();
                Activity activity = activityFromContext(ctx);

                String url = extractUrl(v);
                if (url == null) {
                    Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found), Toast.LENGTH_SHORT).show();
                    XposedBridge.log("(IE|ProfileDL) ❌ URL extraction failed");
                    return true;
                }
                String username = activity != null ? extractUsername(activity) : null;
                String filename  = FeedVideoDownloadHook.buildFilename(username, "profile", null, false);

                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_profile_pic), Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try (OutputStream out = FeedVideoDownloadHook.openOutputStream(
                            ctx, filename, false, username)) {
                        downloadToStream(url, out);
                        mainHandler.post(() -> Toast.makeText(ctx,
                                I18n.t(ctx, R.string.ig_toast_profile_pic_saved), Toast.LENGTH_SHORT).show());
                    } catch (Throwable e) {
                        XposedBridge.log("(IE|ProfileDL) ❌ download: " + e.getMessage());
                        mainHandler.post(() -> Toast.makeText(ctx,
                                I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                    }
                }).start();
                return true;
            });

        } catch (Throwable t) {
            XposedBridge.log("(IE|ProfileDL) ❌ injectLongPress: " + t.getMessage());
        }
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    /**
     * Extracts the image URL from the profile pic view (CircularImageView extends IgImageView).
     * Scans known ImageUrl-typed fields by name; tries multiple candidates in order.
     */
    private static String extractUrl(View view) {
        for (String fieldName : new String[]{"A0E", "A0D", "A0c"}) {
            try {
                String url = getUrlFromImageUrlField(view, fieldName);
                if (url != null) return url;
            } catch (Throwable ignored) {}
        }

        // Fallback: tag-based URI
        try {
            Object tag = view.getTag();
            if (tag instanceof Uri) return tag.toString();
            if (tag instanceof String s && s.startsWith("http")) return s;
        } catch (Throwable ignored) {}

        XposedBridge.log("(IE|ProfileDL) ❌ all URL strategies failed for " + view.getClass().getName());
        return null;
    }

    /**
     * Walks the class hierarchy to find a field by name, reads it as an ImageUrl,
     * then calls getUrl() on it (ImageUrl is a non-obfuscated interface).
     */
    private static String getUrlFromImageUrlField(View view, String fieldName) throws Throwable {
        Class<?> cls = view.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object imageUrl = f.get(view);
                if (imageUrl == null) return null;
                java.lang.reflect.Method getUrl = imageUrl.getClass().getMethod("getUrl");
                Object result = getUrl.invoke(imageUrl);
                if (result instanceof String s && s.startsWith("http")) return s;
                return null;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    // ── Username extraction ───────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    private static String extractUsername(Activity activity) {
        try {
            android.app.ActionBar ab = activity.getActionBar();
            if (ab != null && ab.getTitle() != null) {
                String t = ab.getTitle().toString().trim();
                if (looksLikeUsername(t)) return t;
            }
        } catch (Throwable ignored) {}

        try {
            int titleId = activity.getResources()
                    .getIdentifier("action_bar_title", "id", activity.getPackageName());
            if (titleId != 0) {
                android.widget.TextView tv = activity.findViewById(titleId);
                if (tv != null) {
                    String t = tv.getText().toString().trim();
                    if (looksLikeUsername(t)) return t;
                }
            }
        } catch (Throwable ignored) {}

        try {
            CharSequence t = activity.getTitle();
            if (t != null && looksLikeUsername(t.toString().trim())) return t.toString().trim();
        } catch (Throwable ignored) {}

        return null;
    }

    private static boolean looksLikeUsername(String s) {
        return s != null && s.length() >= 1 && s.length() <= 30
                && s.matches("[a-zA-Z0-9._]+")
                && !s.matches("\\d+");
    }

    // ── Context → Activity ────────────────────────────────────────────────────

    private static Activity activityFromContext(Context ctx) {
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private static void downloadToStream(String url, OutputStream out) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
        conn.connect();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }
}
