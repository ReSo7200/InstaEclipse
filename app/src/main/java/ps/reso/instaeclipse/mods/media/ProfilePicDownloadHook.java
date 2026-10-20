package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.net.HttpURLConnection;
import java.net.URL;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.feed.ZoomableImageView;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Profile Picture Downloader
 *
 * Strategy:
 *   Hook View.onAttachedToWindow() globally, filter for "expanded_profile_pic" by resource name
 *   (cached as an int ID after first resolution). When found, attach a long-press listener that
 *   opens the picture full-screen and square (pinch to zoom) with a Download button.
 *
 *   Full size: the circle shows a small copy (ImageUrl from IgImageView). The viewed profile's User
 *   (UserDetailFragment -> UserDetailViewModel -> User, tracked on resume) carries the original as
 *   hd_profile_pic_url_info (User ()ProfilePicUrlInfo using that string; ProfilePicUrlInfo keeps a
 *   plain getUrl()). It's used when it is the same picture (same file name as the circle's URL),
 *   otherwise the circle's URL is kept.
 *
 * Gated by FeatureFlags.enableProfileDownload.
 */
public class ProfilePicDownloadHook {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String  HOOKED_TAG  = "ie_profile_dl";

    /** Cached resource ID for "expanded_profile_pic"; 0 = not yet resolved. */
    private static volatile int expandedPicViewId = 0;

    // ── Install ───────────────────────────────────────────────────────────────

    private static final String FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment";
    private static final String VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel";
    private static final String USER = "com.instagram.user.model.User";
    private static volatile Method hdGetter;
    private static volatile WeakReference<Object> lastProfile;

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        resolveHdGetter(bridge, cl);
        try {
            XposedHelpers.findAndHookMethod(cl.loadClass("androidx.fragment.app.Fragment"), "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.enableProfileDownload) return;
                    for (Class<?> c = param.thisObject.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        if (c.getName().equals(FRAGMENT)) {
                            lastProfile = new WeakReference<>(param.thisObject);
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|ProfileDL) ⚠️ profile tracking: " + t);
        }
        install();
    }

    private static void resolveHdGetter(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method m = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("ProfilePic_hdGetter", cl) : null;
            if (m == null) {
                List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(USER)
                        .usingEqStrings(List.of("hd_profile_pic_url_info"))
                        .returnType("com.instagram.api.schemas.ProfilePicUrlInfo")
                        .paramCount(0)));
                if (found.size() != 1) {
                    ModuleLog.line("(IE|ProfileDL) ⚠️ hd_profile_pic_url_info getter: " + found.size() + " matches");
                    return;
                }
                m = found.get(0).getMethodInstance(cl);
                DexKitCache.saveMethod("ProfilePic_hdGetter", m);
            }
            m.setAccessible(true);
            hdGetter = m;
            ModuleLog.line("(IE|ProfileDL) ✅ HD getter User." + m.getName());
        } catch (Throwable t) {
            ModuleLog.line("(IE|ProfileDL) ⚠️ HD getter: " + t);
        }
    }

    /** The viewed profile's original picture when it is the same image as displayedUrl, else null. */
    private static String hdUrl(String displayedUrl) {
        try {
            Method getter = hdGetter;
            WeakReference<Object> ref = lastProfile;
            Object frag = ref != null ? ref.get() : null;
            if (getter == null || frag == null) return null;
            Object vm = fieldOfType(frag, VIEW_MODEL);
            Object user = vm != null ? fieldOfType(vm, USER) : null;
            Object info = user != null ? getter.invoke(user) : null;
            if (info == null) return null;
            Object url = info.getClass().getMethod("getUrl").invoke(info);
            if (!(url instanceof String hd) || !hd.startsWith("http")) return null;
            if (!fileName(hd).equals(fileName(displayedUrl))) {
                ModuleLog.line("(IE|ProfileDL) HD picture belongs to another profile, keeping the displayed one");
                return null;
            }
            return hd;
        } catch (Throwable t) {
            ModuleLog.line("(IE|ProfileDL) ⚠️ HD url: " + t);
            return null;
        }
    }

    private static String fileName(String url) {
        String path = Uri.parse(url).getPath();
        return path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
    }

    private static Object fieldOfType(Object o, String type) throws IllegalAccessException {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (!f.getType().getName().equals(type)) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) return v;
            }
        }
        return null;
    }

    public static void install() {
        // Mark status before hook setup so the toast shows correctly
        if (FeatureFlags.enableProfileDownload) {
            FeatureStatusTracker.setEnabled("ProfileDownload", R.string.ig_dialog_downloader_profiles);
            FeatureStatusTracker.setHooked("ProfileDownload");
        }

        // Hook View.onAttachedToWindow — fires once per view attachment, works for any
        // window type (Activity, Dialog, BottomSheet) without relying on layout listeners.
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.enableProfileDownload) return;
                View v = (View) param.thisObject;
                if (isProfileAvatarView(v)) {
                    injectLongPress(v);
                }
            }
        });
    }

    private static boolean isProfileAvatarView(View v) {
        try {
            int vid = v.getId();
            if (vid == View.NO_ID) return false;
            String name = v.getResources().getResourceEntryName(vid);
            if (name == null) return false;
            return name.equals("expanded_profile_pic")
                    || name.equals("row_profile_header_imageview")
                    || name.equals("circular_image_view")
                    || name.equals("avatar_image_view")
                    || name.equals("profile_header_avatar_container")
                    || name.equals("row_profile_header_avatar_container")
                    || (name.contains("profile_header") && (name.contains("image") || name.contains("avatar")));
        } catch (Throwable ignored) {
            return false;
        }
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
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found), Toast.LENGTH_SHORT).show();
                    ModuleLog.line("(IE|ProfileDL) ❌ URL extraction failed");
                    return true;
                }
                String username = activity != null ? extractUsername(activity) : null;
                String hd = hdUrl(url);
                ModuleLog.line("(IE|ProfileDL) viewer: " + (hd != null ? "HD original" : "displayed copy"));
                showViewer(ctx, hd != null ? hd : url, username);
                return true;
            });

        } catch (Throwable t) {
            ModuleLog.line("(IE|ProfileDL) ❌ injectLongPress: " + t.getMessage());
        }
    }

    // ── Viewer ────────────────────────────────────────────────────────────────

    /**
     * Shows the uncropped profile picture enlarged inside a modern square container (1:1 ratio)
     * with pinch-to-zoom, pan, HD loading, and download/share actions.
     */
    private static void showViewer(Context ctx, String url, String username) {
        android.app.Dialog dialog = new android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float dp = ctx.getResources().getDisplayMetrics().density;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        int squareSize = (int) (Math.min(screenWidth, screenHeight) * 0.88f);

        // Semi-transparent dimmed root background
        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0xCC000000);
        root.setOnClickListener(v -> dialog.dismiss());

        // Centered Content Layout
        android.widget.LinearLayout contentLayout = new android.widget.LinearLayout(ctx);
        contentLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        contentLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        contentLayout.setLayoutParams(contentLp);

        // Header: Username & Dimensions
        android.widget.LinearLayout headerLayout = new android.widget.LinearLayout(ctx);
        headerLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headerLayout.setGravity(Gravity.CENTER_VERTICAL);
        headerLayout.setPadding((int) (8 * dp), 0, (int) (8 * dp), (int) (12 * dp));

        TextView userTitle = new TextView(ctx);
        userTitle.setText(username != null && !username.isEmpty() ? "@" + username : "");
        userTitle.setTextColor(Color.WHITE);
        userTitle.setTextSize(16);
        userTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        headerLayout.addView(userTitle, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView sizeView = new TextView(ctx);
        sizeView.setTextColor(0xB3FFFFFF);
        sizeView.setTextSize(12);
        headerLayout.addView(sizeView);

        contentLayout.addView(headerLayout, new android.widget.LinearLayout.LayoutParams(
                squareSize, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Square Card Container (1:1)
        FrameLayout squareCard = new FrameLayout(ctx);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF161616);
        cardBg.setCornerRadius(20 * dp);
        cardBg.setStroke((int) (1.5f * dp), 0x33FFFFFF);
        squareCard.setBackground(cardBg);
        squareCard.setClipToOutline(true);
        // Intercept clicks on card so touching the picture doesn't dismiss the modal
        squareCard.setOnClickListener(v -> {});

        ZoomableImageView image = new ZoomableImageView(ctx);
        image.setOnDismissRequest(dialog::dismiss);
        squareCard.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ProgressBar spinner = new ProgressBar(ctx);
        squareCard.addView(spinner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        contentLayout.addView(squareCard, new android.widget.LinearLayout.LayoutParams(squareSize, squareSize));

        // Action Buttons Row: Download, Copy Link, Close
        android.widget.LinearLayout actionsLayout = new android.widget.LinearLayout(ctx);
        actionsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actionsLayout.setGravity(Gravity.CENTER);
        actionsLayout.setPadding(0, (int) (16 * dp), 0, 0);

        // 1. Download Pill
        TextView downloadBtn = createActionPill(ctx, dp, "📥 " + I18n.t(ctx, R.string.ig_download_choice_title), 0xFF0A84FF);
        downloadBtn.setOnClickListener(v -> {
            dialog.dismiss();
            save(ctx, url, username);
        });
        actionsLayout.addView(downloadBtn);

        // Spacer
        View spacer = new View(ctx);
        actionsLayout.addView(spacer, new android.widget.LinearLayout.LayoutParams((int) (12 * dp), 1));

        // 2. Copy Link Pill
        TextView copyBtn = createActionPill(ctx, dp, "🔗 " + I18n.t(ctx, R.string.ig_dialog_downloader_copy_link), 0x33FFFFFF);
        copyBtn.setOnClickListener(v -> {
            dialog.dismiss();
            copyLinkToClipboard(ctx, url);
        });
        actionsLayout.addView(copyBtn);

        // Spacer
        View spacer2 = new View(ctx);
        actionsLayout.addView(spacer2, new android.widget.LinearLayout.LayoutParams((int) (12 * dp), 1));

        // 3. Close Pill
        TextView closeBtn = createActionPill(ctx, dp, "✕", 0x22FFFFFF);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        actionsLayout.addView(closeBtn);

        contentLayout.addView(actionsLayout, new android.widget.LinearLayout.LayoutParams(
                squareSize, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(contentLayout);
        dialog.setContentView(root);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();

        new Thread(() -> {
            Bitmap bmp = null;
            try {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                downloadToStream(url, buf);
                byte[] bytes = buf.toByteArray();
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Throwable t) {
                ModuleLog.line("(IE|ProfileDL) ⚠️ viewer load: " + t);
            }
            final Bitmap b = bmp;
            mainHandler.post(() -> {
                spinner.setVisibility(View.GONE);
                if (b == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_profile_pic_url_not_found), Toast.LENGTH_SHORT).show();
                    return;
                }
                image.setImageBitmap(b);
                sizeView.setText(b.getWidth() + " × " + b.getHeight() + " px");
            });
        }).start();
    }

    private static TextView createActionPill(Context ctx, float dp, String text, int bgColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        int ph = (int) (16 * dp), pv = (int) (10 * dp);
        tv.setPadding(ph, pv, ph, pv);

        GradientDrawable pill = new GradientDrawable();
        pill.setColor(bgColor);
        pill.setCornerRadius(20 * dp);
        tv.setBackground(pill);
        return tv;
    }

    private static void copyLinkToClipboard(Context ctx, String url) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Profile Pic URL", url));
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.logging_copied), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {}
    }

    private static void save(Context ctx, String url, String username) {
        String filename = FeedVideoDownloadHook.buildFilename(username, "profile", null, false);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_profile_pic), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, filename, false, username);
                if (!delegated) {
                    mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                            I18n.t(ctx, R.string.ig_toast_profile_pic_saved), Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable e) {
                ModuleLog.line("(IE|ProfileDL) ❌ download: " + e.getMessage());
                mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    private static final String IMAGE_URL = "com.instagram.common.typedurl.ImageUrl";

    /**
     * Extracts the image URL from the profile pic view (CircularImageView extends IgImageView).
     * IgImageView keeps the non-obfuscated getter getTypedUrl() (the displayed ImageUrl); if a
     * build drops it, scans the hierarchy for ImageUrl-typed fields (A0E/A0D/A0e on 452) by TYPE,
     * never by the obfuscated field letter. ImageUrl itself is a non-obfuscated interface.
     */
    private static String extractUrl(View view) {
        try {
            java.lang.reflect.Method getter = findMethod(view.getClass(), "getTypedUrl");
            if (getter != null) {
                String url = urlOf(getter.invoke(view));
                if (url != null) return url;
            }
        } catch (Throwable ignored) {}

        try {
            String url = getUrlFromImageUrlFields(view);
            if (url != null) return url;
        } catch (Throwable ignored) {}

        // Fallback: tag-based URI
        try {
            Object tag = view.getTag();
            if (tag instanceof Uri) return tag.toString();
            if (tag instanceof String s && s.startsWith("http")) return s;
        } catch (Throwable ignored) {}

        ModuleLog.line("(IE|ProfileDL) ❌ all URL strategies failed for " + view.getClass().getName());
        return null;
    }

    /**
     * Walks the class hierarchy and reads every field typed as ImageUrl, returning the first one
     * whose getUrl() yields an http(s) URL. getDeclaredFields() order is unspecified, but all the
     * candidates point at the same displayed picture (main / tracking / fallback copy).
     */
    private static String getUrlFromImageUrlFields(View view) {
        for (Class<?> cls = view.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!IMAGE_URL.equals(f.getType().getName())) continue;
                try {
                    f.setAccessible(true);
                    String url = urlOf(f.get(view));
                    if (url != null) return url;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** ImageUrl.getUrl() if it is an http(s) URL, else null. */
    private static String urlOf(Object imageUrl) throws Throwable {
        if (imageUrl == null) return null;
        Object result = imageUrl.getClass().getMethod("getUrl").invoke(imageUrl);
        return result instanceof String s && s.startsWith("http") ? s : null;
    }

    private static java.lang.reflect.Method findMethod(Class<?> cls, String name) {
        for (; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            try {
                java.lang.reflect.Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
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
        MediaHttp.applyTimeouts(conn);
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
