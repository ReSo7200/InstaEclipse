package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.users.UserUtils;

public class FeedVideoDownloadHook {

    private static final String DOWNLOAD_BTN_TAG = "ie_media_download_btn";

    /** View tag key used by ReelDownloadHook to bind a Media object to the reel like_button. */
    static final int TAG_REEL_MEDIA = "ie_reel_media".hashCode();

    // ── Class/method refs resolved once at hook install time ─────────────────
    private static Class<?> mediaExtKtClass;
    private static Class<?> mediaClass;
    static Class<?> mutableMediaDictIntfClass;
    private static Method   methodImageUrl;         // MediaExtKt: static (Context, Media) -> String

    // VideoVersionIntf – stable public interface with getUrl()
    static Class<?> videoVersionIntfClass;
    static Method   videoVersionGetUrl;             // VideoVersionIntf.getUrl() -> String

    // All () -> List candidates from MutableMediaDictIntf + its superinterfaces
    static final List<Method> carouselCandidates = new ArrayList<>();

    // User class + the method on MutableMediaDictIntf that returns it — resolved via DexKit
    private static Class<?> userClass;
    private static Method   dictUserGetter;    // () -> UserClass on MutableMediaDictIntf
    // userUsernameGetter lives in UserUtils — resolved here and stored there

    // ── Uri.parse fallback buffer ─────────────────────────────────────────────
    private static final class UrlEntry {
        final String url; final long time;
        UrlEntry(String u) { url = u; time = System.currentTimeMillis(); }
    }
    private static final int MAX_URLS = 200;
    private static final Deque<UrlEntry> urlBuffer      = new ArrayDeque<>();
    private static final Deque<UrlEntry> videoUrlBuffer = new ArrayDeque<>(); // DexKit-captured video URLs
    private static final WeakHashMap<View, List<String>> buttonUrls = new WeakHashMap<>();
    static final ExecutorService executor    = Executors.newCachedThreadPool();
    static final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // Username + media ID resolved at download trigger time
    private volatile String currentDownloadUsername = null;
    private volatile String currentDownloadMediaId  = null;

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(ClassLoader classLoader) {
        // Load Media and MediaExtKt
        try {
            mediaClass      = classLoader.loadClass("com.instagram.feed.media.Media");
            mediaExtKtClass = classLoader.loadClass("com.instagram.feed.media.MediaExtKt");
            // Find static (Context, Media) -> String method (name changes every version)
            for (Method m : mediaExtKtClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2
                        && "android.content.Context".equals(p[0].getName())
                        && p[1] == mediaClass
                        && m.getReturnType() == String.class) {
                    m.setAccessible(true);
                    methodImageUrl = m;
                    break;
                }
            }
        } catch (Throwable ignored) {}

        // Load VideoVersionIntf (stable public interface with getUrl())
        try {
            videoVersionIntfClass = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf");
            videoVersionGetUrl = videoVersionIntfClass.getMethod("getUrl");
        } catch (Throwable ignored) {}

        // Load MutableMediaDictIntf and collect () -> List methods from it
        // AND its direct superinterfaces only (Instagram 423+ moved Cz7() to LX/IdM).
        // Do NOT recurse deeper — LX/IdM's own ancestors flood us with unrelated methods.
        try {
            mutableMediaDictIntfClass = classLoader.loadClass("com.instagram.feed.media.MutableMediaDictIntf");
            Set<String> seen = new HashSet<>();
            // Declared methods on MutableMediaDictIntf itself (DIS, BJ4, CjW, ...)
            for (Method m : mutableMediaDictIntfClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                    if (seen.add(m.getName())) { m.setAccessible(true); carouselCandidates.add(m); }
                }
            }
            // Direct superinterfaces only (captures Cz7() from LX/IdM without going deeper)
            for (Class<?> superIface : mutableMediaDictIntfClass.getInterfaces()) {
                String sn = superIface.getName();
                if (!sn.startsWith("com.instagram.") && !sn.startsWith("com.facebook.") && !sn.startsWith("X.")) continue;
                for (Method m : superIface.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                        if (seen.add(m.getName())) { m.setAccessible(true); carouselCandidates.add(m); }
                    }
                }
            }
        } catch (Throwable ignored) {}

        installUriCaptureHook();
        // installViewHook(); — replaced by PostDownloadContextMenuHook (three-dots menu)
        // installLongPressHook();
        // installShareHook();
    }

    // ── Hook 1: Uri.parse (fallback buffer) ──────────────────────────────────

    private void installUriCaptureHook() {
        try {
            XposedHelpers.findAndHookMethod(Uri.class, "parse", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enablePostDownload) return;
                            String s = (String) param.args[0];
                            if (s == null || !isCdnMediaUrl(s)) return;
                            synchronized (urlBuffer) {
                                if (!urlBuffer.isEmpty() && urlBuffer.peekFirst().url.equals(s))
                                    return;
                                urlBuffer.addFirst(new UrlEntry(s));
                                while (urlBuffer.size() > MAX_URLS) urlBuffer.removeLast();
                            }
                        }
                    });
            FeatureStatusTracker.setHooked("PostDownload");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): ❌ Uri.parse hook: " + t);
        }
    }

    // ── Hook 2: View.onAttachedToWindow ──────────────────────────────────────

    private void installViewHook() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enablePostDownload) return;
                            View view = (View) param.thisObject;
                            Context ctx = view.getContext();

                            @SuppressLint("DiscouragedApi")
                            int feedLikeId = ctx.getResources().getIdentifier(
                                    "row_feed_button_like", "id", ctx.getPackageName());
                            @SuppressLint("DiscouragedApi")
                            int reelLikeId = ctx.getResources().getIdentifier(
                                    "like_button", "id", ctx.getPackageName());
                            @SuppressLint("DiscouragedApi")
                            int clipsUfiId = ctx.getResources().getIdentifier(
                                    "clips_ufi_component", "id", ctx.getPackageName());

                            int viewId = view.getId();
                            boolean isFeedLike = feedLikeId != 0 && viewId == feedLikeId;
                            boolean isReelLike = reelLikeId != 0 && viewId == reelLikeId
                                    && hasAncestorWithId(view, clipsUfiId);

                            if (!isFeedLike && !isReelLike) return;
                            if (!(view.getParent() instanceof ViewGroup parent)) return;

                            long now = System.currentTimeMillis();
                            List<String> snapshot = snapshotUrlsSince(now - 10_000);

                            if (isFeedLike) {
                                // Feed post: inject floating download button
                                View existing = parent.findViewWithTag(DOWNLOAD_BTN_TAG);
                                if (existing != null) {
                                    synchronized (buttonUrls) { buttonUrls.put(existing, snapshot); }
                                    return;
                                }
                                injectDownloadButton(view, parent, ctx, snapshot);
                            } else {
                                // Reel: long-press the like button to download.
                                // ReelDownloadHook tags this view with the Media object via TAG_REEL_MEDIA.
                                view.setOnLongClickListener(lv -> {
                                    if (!FeatureFlags.enablePostDownload) return false;
                                    Object media = lv.getTag(TAG_REEL_MEDIA);
                                    if (media != null) {
                                        String url = bestVideoUrlFromMedia(media);
                                        if (url != null) {
                                            XposedBridge.log("(IE|Reel) media tag hit, url=" + url);
                                            onDownloadClicked(ctx, List.of(url), lv);
                                            return true;
                                        }
                                        XposedBridge.log("(IE|Reel) media tag set but no video URL found in object");
                                    }
                                    // Fallback: filter buffer for m86 URLs only (combined stream, one per reel)
                                    List<String> all = snapshotUrlsSince(System.currentTimeMillis() - 60_000);
                                    List<String> m86 = new ArrayList<>();
                                    for (String u : all) { if (u.contains("/m86/") || u.contains("%2Fm86%2F")) m86.add(u); }
                                    List<String> pick = m86.isEmpty() ? all : m86;
                                    if (pick.isEmpty()) {
                                        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_reel_url_scroll), Toast.LENGTH_SHORT).show();
                                        return true;
                                    }
                                    XposedBridge.log("(IE|Reel) buffer fallback, m86=" + m86.size() + " total=" + all.size());
                                    // Take only the most recent URL (first in deque = newest)
                                    onDownloadClicked(ctx, List.of(pick.get(0)), lv);
                                    return true;
                                });
                                XposedBridge.log("(IE|Reel) long-press hook set on like_button");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | MediaDownload): ❌ View hook: " + t);
        }
    }

    // ── Button injection ──────────────────────────────────────────────────────

    private void injectDownloadButton(View saveBtn, ViewGroup parent,
                                       Context ctx, List<String> snapshot) {
        ImageButton btn = new ImageButton(ctx);
        btn.setTag(DOWNLOAD_BTN_TAG);
        btn.setImageResource(android.R.drawable.stat_sys_download);
        btn.setColorFilter(Color.WHITE);
        btn.setBackground(null);
        btn.setContentDescription("Download media");

        int size = dp(ctx, 34);
        ViewGroup.LayoutParams lp;
        if (parent instanceof LinearLayout) {
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(size, size);
            llp.gravity = Gravity.CENTER_VERTICAL;
            llp.setMargins(dp(ctx, 4), 0, dp(ctx, 4), 0);
            lp = llp;
        } else {
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(size, size);
            flp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            flp.setMargins(0, 0, dp(ctx, 8), 0);
            lp = flp;
        }
        btn.setLayoutParams(lp);
        synchronized (buttonUrls) { buttonUrls.put(btn, snapshot); }

        btn.setOnClickListener(v -> {
            List<String> urls = resolveUrls(saveBtn, v);
            if (urls.isEmpty()) {
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
                return;
            }
            onDownloadClicked(ctx, urls, saveBtn);
        });

        // Long-press the like button as fallback download trigger.
        // This is the primary path when LithoViews prevents button injection.
        saveBtn.setOnLongClickListener(lv -> {
            if (!FeatureFlags.enablePostDownload) return false;
            List<String> urls = resolveUrls(saveBtn, btn);
            if (urls.isEmpty()) {
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media), Toast.LENGTH_SHORT).show();
                return true;
            }
            onDownloadClicked(ctx, urls, saveBtn);
            return true;
        });

        parent.post(() -> {
            try {
                parent.addView(btn);
                btn.bringToFront();
            } catch (Exception e) {
                XposedBridge.log("(IE|DL) Cannot inject download button: " + e.getMessage());
            }
        });
    }

    // ── URL resolution — three-tier ───────────────────────────────────────────
    //
    // Tier 1: Reflect on the save button's click listener to find the exact Media
    //   object captured in its closure. Extract video URL via VideoVersionIntf.getUrl()
    //   or image URL via MediaExtKt helper. This is per-post with no timing ambiguity.
    //
    // Tier 2: buttonUrls snapshot taken when row_feed_button_save attached.
    //
    // Tier 3: Last 30 s of the Uri.parse buffer (catches lazy-loaded carousels).

    @SuppressLint("DiscouragedApi")
    private List<String> resolveUrls(View likeBtn, View downloadBtn) {
        // Tier-1a: like button's listener (works for standard feed posts)
        List<String> urls = urlsFromSaveBtnListener(likeBtn);
        XposedBridge.log("(IE|DL) Tier-1a urls=" + urls.size());
        if (!urls.isEmpty()) return urls;

        // Tier-1b: bookmark/save button's listener.
        // The save button always captures the Media object (it needs it for save-to-collection).
        // IMPORTANT: row_feed_button_save is NOT a sibling of the like button — it sits in
        // the action bar parent (one level above the left-buttons group). Walk up up to 4
        // parent levels so we reach the action bar container and find it there.
        Context ctx = likeBtn.getContext();
        int saveResId = ctx.getResources().getIdentifier(
                "row_feed_button_save", "id", ctx.getPackageName());
        if (saveResId != 0) {
            android.view.ViewParent p = likeBtn.getParent();
            for (int i = 0; i < 4 && p instanceof ViewGroup vg; i++, p = vg.getParent()) {
                View realSaveBtn = vg.findViewById(saveResId);
                if (realSaveBtn != null) {
                    XposedBridge.log("(IE|DL) Tier-1b found save btn at parent level " + i);
                    urls = urlsFromSaveBtnListener(realSaveBtn);
                    XposedBridge.log("(IE|DL) Tier-1b urls=" + urls.size());
                    if (!urls.isEmpty()) return urls;
                    break; // found the button but listener had no URLs — no point going wider
                }
            }
        }

        return new ArrayList<>();
    }

    // ── Tier 1: Save-button listener search ───────────────────────────────────
    //
    // Strategy:
    //   1. Get the OnClickListener set by Instagram on the save button.
    //   2. Find the captured Media object in its closure (depth-limited field scan).
    //   3. From the MutableMediaDictIntf on the Media object:
    //      a. Check if any () -> List candidate returns VideoVersionIntf items
    //         → single video post: extract URL via getUrl(), return it.
    //      b. Check if any () -> List candidate returns >= 2 non-video items
    //         → carousel: try to extract per-item URLs.
    //      c. Fall back to MediaExtKt image URL helper for single photo posts.

    private static List<String> urlsFromSaveBtnListener(View saveBtn) {
        try {
            Object listener = getOnClickListener(saveBtn);
            if (listener == null) return new ArrayList<>();

            // Broad CDN URL scan of the listener's object graph (for plain String fields)
            List<String> urls = new ArrayList<>();
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            scanForCdnUrls(listener, urls, 0, visited);

            if (mediaClass != null) {
                Object media = findFieldOfType(listener, mediaClass, 4);

                if (media != null) {
                    // ── Step A: Video detection ────────────────────────────────
                    // Two sub-passes for robustness:
                    //  A1 – field-graph scan (fast, works when Pando cache is populated)
                    //  A2 – method invocation on carouselCandidates (reaches JNI-backed data
                    //       that isn't exposed as a Java field until DIS() is called)
                    String videoUrl = findVideoUrlInObject(media,
                            Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                    XposedBridge.log("(IE|DL) stepA1 videoUrl=" + (videoUrl != null
                            ? videoUrl.substring(0, Math.min(80, videoUrl.length())) : "null"));

                    if (videoUrl == null && mutableMediaDictIntfClass != null && !carouselCandidates.isEmpty()) {
                        // A2: invoke every () -> List method; any that returns VideoVersionIntf items
                        //     is the video-versions list. Size >= 1 is enough (single video post).
                        Object dictIntf = findFieldAssignableTo(media, mutableMediaDictIntfClass);
                        if (dictIntf != null && videoVersionIntfClass != null && videoVersionGetUrl != null) {
                            outer:
                            for (Method candidate : carouselCandidates) {
                                try {
                                    Object listObj = candidate.invoke(dictIntf);
                                    if (!(listObj instanceof List<?> items) || items.isEmpty()) continue;
                                    if (!videoVersionIntfClass.isInstance(items.get(0))) continue;
                                    for (Object item : items) {
                                        if (!videoVersionIntfClass.isInstance(item)) continue;
                                        try {
                                            String u = (String) videoVersionGetUrl.invoke(item);
                                            if (u != null && isCdnMediaUrl(u)) { videoUrl = u; break outer; }
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                        XposedBridge.log("(IE|DL) stepA2 videoUrl=" + (videoUrl != null
                                ? videoUrl.substring(0, Math.min(80, videoUrl.length())) : "null"));
                    }
                    if (videoUrl != null) return List.of(videoUrl);

                    // ── Step B: Carousel detection ─────────────────────────────
                    // Try every () -> List method on MutableMediaDictIntf (and its direct
                    // superinterfaces) to find the carousel item list.
                    if (mutableMediaDictIntfClass != null && !carouselCandidates.isEmpty()) {
                        Object dictIntf = findFieldAssignableTo(media, mutableMediaDictIntfClass);
                        XposedBridge.log("(IE|DL) dictIntf=" + (dictIntf != null
                                ? dictIntf.getClass().getName() : "null"));

                        if (dictIntf != null) {
                            for (Method candidate : carouselCandidates) {
                                try {
                                    Object listObj = candidate.invoke(dictIntf);
                                    if (!(listObj instanceof List<?> items) || items.size() < 2) continue;
                                    // Skip VideoVersionIntf lists — already handled in Step A
                                    if (videoVersionIntfClass != null && !items.isEmpty()
                                            && videoVersionIntfClass.isInstance(items.get(0))) continue;

                                    XposedBridge.log("(IE|Car) candidate=" + candidate.getName()
                                            + " items=" + items.size());
                                    List<String> carouselUrls = new ArrayList<>();

                                    for (int idx = 0; idx < items.size(); idx++) {
                                        Object item = items.get(idx);
                                        if (item == null) continue;

                                        // 1. If item is a video carousel item — get its video URL
                                        String itemVideo = findVideoUrlInObject(item,
                                                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                                        if (itemVideo != null) { carouselUrls.add(itemVideo); continue; }

                                        // 2. Try MediaExtKt helper — works when items are Media objects
                                        //    (piko shows newer Instagram carousel items are Media objects)
                                        if (methodImageUrl != null) {
                                            try {
                                                Object r = methodImageUrl.invoke(null, saveBtn.getContext(), item);
                                                if (r instanceof String s && isCdnMediaUrl(s)) {
                                                    XposedBridge.log("(IE|Car) item[" + idx + "] mediaExtKt=" + s.substring(0, Math.min(60, s.length())));
                                                    carouselUrls.add(s);
                                                    continue;
                                                }
                                            } catch (Throwable ignored) {}
                                        }

                                        // 3. Probe all no-param String methods (Pando JNI nodes: LX/VPC, LX/5q9)
                                        String probed = probeCdnUrlViaStringMethods(item);
                                        XposedBridge.log("(IE|Car) item[" + idx + "] probed=" + probed);
                                        if (probed != null) { carouselUrls.add(probed); continue; }

                                        // 4. Generic CDN field scan as last resort
                                        List<String> scanned = new ArrayList<>();
                                        scanForCdnUrls(item, scanned, 0,
                                                Collections.newSetFromMap(new IdentityHashMap<>()));
                                        if (!scanned.isEmpty()) carouselUrls.add(pickBestImageUrl(scanned));
                                    }

                                    XposedBridge.log("(IE|Car) carouselUrls=" + carouselUrls.size());
                                    if (carouselUrls.size() >= 2) return carouselUrls;
                                } catch (Throwable ignored) {}
                            }
                        }
                    }

                    // ── Step C: Single photo ───────────────────────────────────
                    if (methodImageUrl != null) {
                        try {
                            Object img = methodImageUrl.invoke(null, saveBtn.getContext(), media);
                            if (img instanceof String s && isCdnMediaUrl(s))
                                return List.of(s);
                        } catch (Throwable ignored) {}
                    }
                }

                // Fallback: prefer non-video URLs found by the object graph scan
                List<String> images = new ArrayList<>();
                for (String u : urls) { if (!isVideoUrl(u)) images.add(u); }
                if (!images.isEmpty()) return List.of(pickBestImageUrl(images));
            }

            return urls;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * Probes all no-parameter String-returning methods on {@code obj} (including superclass
     * declared methods) and returns the first one that yields an Instagram CDN URL.
     *
     * This is needed for Pando/LiveTree JNI nodes (LX/VPC carousel items, LX/5q9) whose
     * image URLs are only accessible via obfuscated JNI-backed methods, not via fields.
     */
    private static String probeCdnUrlViaStringMethods(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            String cn = cls.getName();
            if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook.")) break;
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(obj);
                    if (r instanceof String s && isCdnMediaUrl(s)) return s;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Depth-limited field-graph scan for any VideoVersionIntf instance inside {@code obj}.
     * Returns the first CDN URL found via {@code VideoVersionIntf.getUrl()}, or null.
     *
     * This is the primary video-detection path. It is version-independent: it does not
     * depend on knowing the obfuscated name of the method that returns the video-version
     * list (DIS(), or whatever it is renamed to in newer Instagram builds).
     */
    static String findVideoUrlInObject(Object obj, Set<Object> visited, int depth) {
        if (obj == null || depth > 5 || !visited.add(obj)) return null;
        if (videoVersionIntfClass == null || videoVersionGetUrl == null) return null;

        // Direct hit: obj itself implements VideoVersionIntf
        if (videoVersionIntfClass.isInstance(obj)) {
            try {
                String url = (String) videoVersionGetUrl.invoke(obj);
                if (url != null && isCdnMediaUrl(url)) return url;
            } catch (Throwable ignored) {}
        }

        Class<?> cls = obj.getClass();
        String cn = cls.getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook.")) return null;

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    if (val instanceof List<?> list) {
                        // List field — check if any element is a VideoVersionIntf
                        for (Object elem : list) {
                            if (elem != null && videoVersionIntfClass.isInstance(elem)) {
                                try {
                                    String url = (String) videoVersionGetUrl.invoke(elem);
                                    if (url != null && isCdnMediaUrl(url)) return url;
                                } catch (Throwable ignored) {}
                            }
                        }
                    } else {
                        // Recurse into Instagram/Facebook objects only
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook.")) {
                            String found = findVideoUrlInObject(val, visited, depth + 1);
                            if (found != null) return found;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Collects ALL CDN video URLs found by walking the VideoVersionIntf graph inside {@code obj}.
     * Prefers m86 URLs (combined audio+video stream) — those are sorted to the front of the list.
     */
    static void collectAllVideoUrls(Object obj, List<String> out, Set<Object> visited, int depth) {
        if (obj == null || depth > 5 || !visited.add(obj)) return;
        if (videoVersionIntfClass == null || videoVersionGetUrl == null) return;

        if (videoVersionIntfClass.isInstance(obj)) {
            try {
                String url = (String) videoVersionGetUrl.invoke(obj);
                if (url != null && isCdnMediaUrl(url) && !out.contains(url)) out.add(url);
            } catch (Throwable ignored) {}
            return; // don't recurse into VideoVersionIntf objects
        }

        Class<?> cls = obj.getClass();
        String cn = cls.getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook.")) return;

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        for (Object elem : list)
                            collectAllVideoUrls(elem, out, visited, depth + 1);
                    } else {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook."))
                            collectAllVideoUrls(val, out, visited, depth + 1);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    /** Returns the best video URL from the media object: prefers m86 (combined stream). */
    static String bestVideoUrlFromMedia(Object media) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> all = new ArrayList<>();
        collectAllVideoUrls(media, all, visited, 0);
        if (all.isEmpty()) return null;
        for (String u : all) { if (u.contains("/m86/") || u.contains("%2Fm86%2F")) return u; }
        return all.get(0); // fallback: first found
    }

    /**
     * Tries to call getUrl() on an object if it's available (handles VideoVersionIntf
     * and any other object that exposes a stable getUrl() method).
     */
    private static String tryGetUrl(Object obj) {
        if (obj == null) return null;
        try {
            Method m = obj.getClass().getMethod("getUrl");
            Object result = m.invoke(obj);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Among multiple resolutions of the same image, prefer the full-size original. */
    private static String pickBestImageUrl(List<String> images) {
        for (String url : images) {
            if (!url.contains("/s150x") && !url.contains("/s240x") &&
                !url.contains("/s320x") && !url.contains("/s480x") &&
                !url.contains("/s640x") && !url.contains("_s.jpg")) {
                return url;
            }
        }
        return images.get(0);
    }

    /** Reads View.mListenerInfo.mOnClickListener via reflection. */
    private static Object getOnClickListener(View view) {
        try {
            Field liField = View.class.getDeclaredField("mListenerInfo");
            liField.setAccessible(true);
            Object li = liField.get(view);
            if (li == null) return null;
            Field clField = li.getClass().getDeclaredField("mOnClickListener");
            clField.setAccessible(true);
            return clField.get(li);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Recursively scans an object's fields for Instagram CDN URL strings.
     * Only descends into X.* / com.instagram.* / com.facebook.* objects.
     */
    private static final int MAX_SCAN_DEPTH = 6;
    private static final int MAX_SCAN_URLS  = 20;

    private static void scanForCdnUrls(Object obj, List<String> out,
                                        int depth, Set<Object> visited) {
        if (obj == null || depth > MAX_SCAN_DEPTH || out.size() >= MAX_SCAN_URLS) return;
        if (!visited.add(obj)) return;

        Class<?> cls = obj.getClass();
        String cn = cls.getName();
        if (cn.startsWith("android.") || cn.startsWith("java.lang.")  ||
            cn.startsWith("java.util.concurrent.") || cn.startsWith("kotlin.")) return;

        // Also try getUrl() for Pando tree nodes that expose it via method (not field)
        String directUrl = tryGetUrl(obj);
        if (directUrl != null && isCdnMediaUrl(directUrl) && !out.contains(directUrl))
            out.add(directUrl);

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    if (val instanceof String s) {
                        if (isCdnMediaUrl(s) && !out.contains(s)) out.add(s);
                    } else if (val instanceof List<?> list) {
                        for (Object item : list) scanForCdnUrls(item, out, depth + 1, visited);
                    } else if (val instanceof Object[] arr) {
                        for (Object item : arr) scanForCdnUrls(item, out, depth + 1, visited);
                    } else {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.")               ||
                            vcn.startsWith("com.instagram.")   ||
                            vcn.startsWith("com.facebook.")) {
                            scanForCdnUrls(val, out, depth + 1, visited);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private static Object findFieldOfType(Object obj, Class<?> target, int depth) {
        if (obj == null || target == null || depth < 0) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (target.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        if (depth > 0) {
            cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(obj);
                        if (v == null) continue;
                        String vcn = v.getClass().getName();
                        if (!vcn.startsWith("X.") && !vcn.startsWith("com.instagram.") &&
                                !vcn.startsWith("com.facebook.")) continue;
                        Object r = findFieldOfType(v, target, depth - 1);
                        if (r != null) return r;
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Finds the first field on {@code obj} whose declared type is assignable to
     * {@code targetType}. Used to locate interface-typed fields.
     */
    static Object findFieldAssignableTo(Object obj, Class<?> targetType) {
        if (obj == null || targetType == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (targetType.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(obj);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Buffer helpers ────────────────────────────────────────────────────────

    private static List<String> snapshotUrlsSince(long from) {
        List<String> r = new ArrayList<>();
        synchronized (urlBuffer) {
            for (UrlEntry e : urlBuffer) {
                if (e.time >= from) r.add(e.url);
                else break;
            }
        }
        return r;
    }

    private static List<String> snapshotVideoUrlsSince(long from) {
        List<String> r = new ArrayList<>();
        synchronized (videoUrlBuffer) {
            for (UrlEntry e : videoUrlBuffer) {
                if (e.time >= from) r.add(e.url);
                else break;
            }
        }
        return r;
    }

    /**
     * DexKit-based hook on {@code VideoVersionIntf.getUrl()} — installed once at startup.
     *
     * Finds all concrete classes implementing VideoVersionIntf at runtime using DexKit,
     * hooks their {@code getUrl()} method, and passively captures returned CDN URLs into
     * {@code videoUrlBuffer}. This is version-proof: it doesn't depend on knowing the
     * obfuscated method name that returns the video-versions list (DIS(), etc.).
     *
     * Used as a supplement to the Uri.parse buffer (Tier 3) when Tiers 1 and 2 fail.
     */
    public static void installVideoUrlCaptureHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook urlHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enablePostDownload) return;
                Object result = param.getResult();
                if (!(result instanceof String url)) return;
                if (!isCdnMediaUrl(url)) return;
                synchronized (videoUrlBuffer) {
                    if (!videoUrlBuffer.isEmpty() && videoUrlBuffer.peekFirst().url.equals(url)) return;
                    videoUrlBuffer.addFirst(new UrlEntry(url));
                    while (videoUrlBuffer.size() > MAX_URLS) videoUrlBuffer.removeLast();
                }
            }
        };

        // Cache hit: hook all previously-found getUrl() implementations directly
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("VideoUrlCapture", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, urlHook);
                XposedBridge.log("(IE|DL|DexKit) VideoUrlCapture: " + cached.size() + " method(s) from cache");
                resolveUsernameGetter(bridge, classLoader);
                return;
            }
        }

        try {
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .addInterface("com.instagram.model.mediasize.VideoVersionIntf",
                                    StringMatchType.Equals, false)));

            XposedBridge.log("(IE|DL|DexKit) VideoVersionIntf implementors found: " + classes.size());

            List<Method> hooked = new ArrayList<>();
            for (ClassData classData : classes) {
                try {
                    List<MethodData> methods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(classData.getName())
                                    .name("getUrl")
                                    .returnType("java.lang.String")
                                    .paramCount(0)));

                    for (MethodData methodData : methods) {
                        try {
                            Method m = methodData.getMethodInstance(classLoader);
                            XposedBridge.hookMethod(m, urlHook);
                            hooked.add(m);
                            XposedBridge.log("(IE|DL|DexKit) ✅ Hooked getUrl() on "
                                    + classData.getName());
                        } catch (Throwable e) {
                            XposedBridge.log("(IE|DL|DexKit) ❌ Hook failed for "
                                    + classData.getName() + ": " + e.getMessage());
                        }
                    }
                } catch (Throwable e) {
                    XposedBridge.log("(IE|DL|DexKit) ❌ findMethod failed for "
                            + classData.getName() + ": " + e.getMessage());
                }
            }
            if (!hooked.isEmpty()) DexKitCache.saveMethods("VideoUrlCapture", hooked);
        } catch (Throwable e) {
            XposedBridge.log("(IE|DL|DexKit) ❌ installVideoUrlCaptureHook: " + e.getMessage());
        }

        resolveUsernameGetter(bridge, classLoader);
    }

    /**
     * Uses DexKit to find the user class (via "username_missing_during_update") and then
     * locates the no-arg method on MutableMediaDictIntf (or its superinterfaces) that
     * returns an instance of that class. This gives us a stable way to get the post author
     * from the LiveTreeMediaDict without guessing obfuscated method names.
     */
    private static void resolveUsernameGetter(DexKitBridge bridge, ClassLoader classLoader) {
        // Cache hit: restore userClass and userUsernameGetter without DexKit
        if (DexKitCache.isCacheValid()) {
            String cachedClassName = DexKitCache.loadString("UserClass");
            Method cachedGetter    = DexKitCache.loadMethod("UsernameGetter", classLoader);
            if (cachedClassName != null) {
                try {
                    userClass = classLoader.loadClass(cachedClassName);
                    if (cachedGetter != null) {
                        UserUtils.userUsernameGetter = cachedGetter;
                        XposedBridge.log("(IE|DL|Username) Restored from cache: userClass=" + cachedClassName);
                    }
                    // dictUserGetter is pure-reflection — fall through to scan below
                    resolveDictUserGetter(classLoader);
                    return;
                } catch (Throwable ignored) {}
            }
        }

        try {
            // Step 1: find the user class via the stable validation string
            List<MethodData> userMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("username_missing_during_update")));

            if (userMethods.isEmpty()) {
                XposedBridge.log("(IE|DL|Username) ❌ username_missing_during_update not found");
                return;
            }

            userClass = userMethods.get(0).getMethodInstance(classLoader).getDeclaringClass();
            DexKitCache.saveString("UserClass", userClass.getName());
            XposedBridge.log("(IE|DL|Username) userClass=" + userClass.getName());

            // Resolve the username getter on User via the stable GraphQL field ID -265713450.
            try {
                List<MethodData> ugMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("com.instagram.user.model.User")
                                .returnType("java.lang.String")
                                .paramCount(0)
                                .usingNumbers(-265713450)));
                if (!ugMethods.isEmpty()) {
                    UserUtils.userUsernameGetter = ugMethods.get(0).getMethodInstance(classLoader);
                    UserUtils.userUsernameGetter.setAccessible(true);
                    DexKitCache.saveMethod("UsernameGetter", UserUtils.userUsernameGetter);
                    XposedBridge.log("(IE|DL|Username) userUsernameGetter=" + UserUtils.userUsernameGetter.getName());
                } else {
                    XposedBridge.log("(IE|DL|Username) ❌ userUsernameGetter not found via -265713450");
                }
            } catch (Throwable t) {
                XposedBridge.log("(IE|DL|Username) ❌ userUsernameGetter resolution: " + t);
            }

            resolveDictUserGetter(classLoader);

        } catch (Throwable t) {
            XposedBridge.log("(IE|DL|Username) ❌ resolveUsernameGetter: " + t);
        }
    }

    private static void resolveDictUserGetter(ClassLoader classLoader) {
        if (mutableMediaDictIntfClass == null || userClass == null) {
            XposedBridge.log("(IE|DL|Username) ❌ mutableMediaDictIntfClass or userClass not resolved");
            return;
        }
        // Scan MutableMediaDictIntf + its direct superinterfaces for a
        // no-arg method whose return type is assignable to userClass
        List<Class<?>> toScan = new ArrayList<>();
        toScan.add(mutableMediaDictIntfClass);
        for (Class<?> iface : mutableMediaDictIntfClass.getInterfaces()) {
            String n = iface.getName();
            if (n.startsWith("com.instagram.") || n.startsWith("X.") || n.startsWith("com.facebook."))
                toScan.add(iface);
        }
        for (Class<?> cls : toScan) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> ret = m.getReturnType();
                if (ret == void.class || ret.isPrimitive()) continue;
                if (userClass.isAssignableFrom(ret)) {
                    m.setAccessible(true);
                    dictUserGetter = m;
                    XposedBridge.log("(IE|DL|Username) dictUserGetter=" + m.getName()
                            + " on " + cls.getName() + " returns " + ret.getName());
                    return;
                }
            }
        }
        XposedBridge.log("(IE|DL|Username) ❌ no dictUserGetter found on MutableMediaDictIntf tree");
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    /**
     * Resolves the post author's username by scanning the media object already captured
     * in the save/like button's click listener closure.
     * Strategy: like button listener → if no media, walk up to save button → then scan
     * the media object graph (depth ≤ 2) for an object with getUsername().
     */
    @SuppressLint("DiscouragedApi")
    private String getUsernameFromView(View likeBtn) {
        if (likeBtn == null) {
            XposedBridge.log("(IE|DL|Username) likeBtn is null");
            return null;
        }
        if (mediaClass == null) {
            XposedBridge.log("(IE|DL|Username) mediaClass not resolved, cannot extract username");
            return null;
        }

        // 1. Try like button's own listener
        Object media = getMediaFromListener(getOnClickListener(likeBtn));
        XposedBridge.log("(IE|DL|Username) media from likeBtn listener=" + (media != null ? media.getClass().getName() : "null"));

        // 2. Fall back to save button's listener (same walk-up as resolveUrls Tier-1b)
        if (media == null) {
            Context ctx = likeBtn.getContext();
            int saveResId = ctx.getResources().getIdentifier(
                    "row_feed_button_save", "id", ctx.getPackageName());
            if (saveResId != 0) {
                android.view.ViewParent p = likeBtn.getParent();
                for (int i = 0; i < 4 && p instanceof ViewGroup vg; i++, p = vg.getParent()) {
                    View saveBtn = vg.findViewById(saveResId);
                    if (saveBtn != null) {
                        media = getMediaFromListener(getOnClickListener(saveBtn));
                        XposedBridge.log("(IE|DL|Username) media from saveBtn (level=" + i + ")="
                                + (media != null ? media.getClass().getName() : "null"));
                        if (media != null) break;
                    }
                }
            }
        }

        if (media == null) {
            XposedBridge.log("(IE|DL|Username) ❌ media object not found");
            return null;
        }

        // Primary: call dictUserGetter on LiveTreeMediaDict to get the User object
        if (dictUserGetter != null && mutableMediaDictIntfClass != null) {
            try {
                Object dictIntf = findFieldAssignableTo(media, mutableMediaDictIntfClass);
                XposedBridge.log("(IE|DL|Username) dictIntf=" + (dictIntf != null ? dictIntf.getClass().getName() : "null"));
                if (dictIntf != null) {
                    Object user = dictUserGetter.invoke(dictIntf);
                    XposedBridge.log("(IE|DL|Username) user=" + (user != null ? user.getClass().getName() : "null"));
                    if (user != null) {
                        String username = UserUtils.callUsernameGetter(user);
                        XposedBridge.log("(IE|DL|Username) callUsernameGetter=" + username);
                        if (username != null) return username;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("(IE|DL|Username) dictUserGetter invoke failed: " + t);
            }
        } else {
            XposedBridge.log("(IE|DL|Username) dictUserGetter not resolved yet"
                    + " (dictUserGetter=" + dictUserGetter + ")");
        }

        // Fallback: field scan
        String username = scanObjectForUsername(media, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        XposedBridge.log("(IE|DL|Username) fallback scan resolved=" + username);
        return username;
    }

    private Object getMediaFromListener(Object listener) {
        if (listener == null || mediaClass == null) return null;
        return findFieldOfType(listener, mediaClass, 4);
    }

    /** Extracts the short media ID (first segment of the Instagram ID) from the view's media object. */
    @SuppressLint("DiscouragedApi")
    private String getMediaIdFromView(View likeBtn) {
        if (likeBtn == null || mediaClass == null) return null;
        try {
            Object media = getMediaFromListener(getOnClickListener(likeBtn));
            if (media == null) {
                Context ctx = likeBtn.getContext();
                int saveResId = ctx.getResources().getIdentifier("row_feed_button_save", "id", ctx.getPackageName());
                if (saveResId != 0) {
                    android.view.ViewParent p = likeBtn.getParent();
                    for (int i = 0; i < 4 && p instanceof ViewGroup vg; i++, p = vg.getParent()) {
                        View saveBtn = vg.findViewById(saveResId);
                        if (saveBtn != null) {
                            media = getMediaFromListener(getOnClickListener(saveBtn));
                            if (media != null) break;
                        }
                    }
                }
            }
            if (media == null) return null;
            Object id = media.getClass().getMethod("getId").invoke(media);
            if (id instanceof String s && !s.isEmpty()) return s.split("_")[0];
        } catch (Throwable ignored) {}
        return null;
    }

    // ── Filename + directory helpers (package-accessible for StoryDownloadHook) ──

    static String buildFilename(String username, String type, String mediaId, boolean isVideo) {
        String u  = (username != null && !username.isEmpty()) ? username : "unknown";
        String id = (mediaId  != null && !mediaId.isEmpty())  ? mediaId  : String.valueOf(System.currentTimeMillis());
        String ext = isVideo ? ".mp4" : ".jpg";
        StringBuilder sb = new StringBuilder(u).append('_').append(type).append('_').append(id);
        if (FeatureFlags.downloaderAddTimestamp) {
            sb.append('_').append(new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
        }
        return sb.append(ext).toString();
    }

    /**
     * Opens a writable OutputStream for the download destination, handling all storage strategies:
     *   - SAF tree URI (custom folder picked by user) — works on all APIs, full path control
     *   - MediaStore Downloads (API 29+, default) — scoped-storage safe, no root permission needed
     *   - Legacy direct file (API < 29) — plain FileOutputStream on external storage
     */
    static OutputStream openOutputStream(Context ctx, String filename, boolean isVideo, String username)
            throws Exception {
        String mimeType = isVideo ? "video/mp4" : "image/jpeg";

        if (!FeatureFlags.downloaderCustomUri.isEmpty()) {
            return openSafOutputStream(ctx, filename, mimeType, username);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return openMediaStoreOutputStream(ctx, filename, mimeType, username);
        }

        // Legacy API < 29: direct file write
        File dir = new File(Environment.getExternalStorageDirectory(), "InstaEclipse");
        if (FeatureFlags.downloaderUsernameFolder && username != null && !username.isEmpty()) {
            dir = new File(dir, username);
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new FileOutputStream(new File(dir, filename));
    }

    private static OutputStream openSafOutputStream(Context ctx, String filename, String mimeType, String username)
            throws Exception {
        Uri treeUri = Uri.parse(FeatureFlags.downloaderCustomUri);
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId);
        if (FeatureFlags.downloaderUsernameFolder && username != null && !username.isEmpty()) {
            dirUri = findOrCreateSafDir(ctx, treeUri, rootDocId, username);
        }
        Uri fileUri = DocumentsContract.createDocument(ctx.getContentResolver(), dirUri, mimeType, filename);
        if (fileUri == null) throw new Exception("SAF createDocument returned null");
        OutputStream out = ctx.getContentResolver().openOutputStream(fileUri);
        if (out == null) throw new Exception("SAF openOutputStream returned null");
        return out;
    }

    private static Uri findOrCreateSafDir(Context ctx, Uri treeUri, String parentDocId, String dirName)
            throws Exception {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        try (Cursor c = ctx.getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                             DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (dirName.equals(c.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                }
            }
        }
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId);
        Uri newDir = DocumentsContract.createDocument(ctx.getContentResolver(), parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR, dirName);
        if (newDir == null) throw new Exception("SAF createDocument (dir) returned null");
        return newDir;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("NewApi")
    private static OutputStream openMediaStoreOutputStream(Context ctx, String filename, String mimeType, String username)
            throws Exception {
        String relPath = "InstaEclipse";
        if (FeatureFlags.downloaderUsernameFolder && username != null && !username.isEmpty()) {
            relPath += "/" + username;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + relPath);
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri itemUri = ctx.getContentResolver().insert(collection, values);
        if (itemUri == null) throw new Exception("MediaStore insert failed");
        OutputStream out = ctx.getContentResolver().openOutputStream(itemUri);
        if (out == null) throw new Exception("MediaStore openOutputStream returned null");
        return out;
    }

    /** Copies tempFile into the correct download destination then deletes the temp. */
    static void saveFileToDestination(Context ctx, File tempFile, String filename, boolean isVideo, String username)
            throws Exception {
        try (FileInputStream in = new FileInputStream(tempFile);
             OutputStream out = openOutputStream(ctx, filename, isVideo, username)) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /**
     * Package-accessible: collects Instagram CDN media URLs from the given object graph.
     * Used by PostDownloadContextMenuHook as a fallback URL source.
     */
    static List<String> collectCdnUrls(Object obj) {
        List<String> out = new ArrayList<>();
        scanForCdnUrls(obj, out, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    /**
     * Package-accessible: extracts the image URL from a Media object using MediaExtKt helper.
     * Returns null if not available (e.g. MediaExtKt not resolved or media is a video-only post).
     */
    static String imageUrlFromMedia(Context ctx, Object media) {
        if (methodImageUrl == null || ctx == null || media == null) return null;
        try {
            Object r = methodImageUrl.invoke(null, ctx, media);
            return (r instanceof String s && isCdnMediaUrl(s)) ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Package-accessible: extracts all downloadable URLs from a Media object.
     * Returns a single-entry list for plain photo/video posts, multi-entry for carousels.
     * Steps: (A) video, (B) carousel via MutableMediaDictIntf, (C) single photo, (D) CDN scan.
     */
    static List<String> extractAllUrlsFromMedia(Context ctx, Object media) {
        if (media == null) return new ArrayList<>();

        // Step A: single video
        String videoUrl = bestVideoUrlFromMedia(media);
        if (videoUrl != null) return new ArrayList<>(List.of(videoUrl));

        // Step B: carousel (MutableMediaDictIntf candidates)
        if (mutableMediaDictIntfClass != null && !carouselCandidates.isEmpty()) {
            Object dictIntf = findFieldAssignableTo(media, mutableMediaDictIntfClass);
            if (dictIntf != null) {
                for (Method candidate : carouselCandidates) {
                    try {
                        Object listObj = candidate.invoke(dictIntf);
                        if (!(listObj instanceof List<?> items) || items.size() < 2) continue;
                        if (videoVersionIntfClass != null && !items.isEmpty()
                                && videoVersionIntfClass.isInstance(items.get(0))) continue;

                        List<String> carouselUrls = new ArrayList<>();
                        for (int idx = 0; idx < items.size(); idx++) {
                            Object item = items.get(idx);
                            if (item == null) continue;
                            String itemVideo = bestVideoUrlFromMedia(item);
                            if (itemVideo != null) { carouselUrls.add(itemVideo); continue; }
                            if (methodImageUrl != null && ctx != null) {
                                try {
                                    Object r = methodImageUrl.invoke(null, ctx, item);
                                    if (r instanceof String s && isCdnMediaUrl(s)) {
                                        carouselUrls.add(s); continue;
                                    }
                                } catch (Throwable ignored) {}
                            }
                            String probed = probeCdnUrlViaStringMethods(item);
                            if (probed != null) { carouselUrls.add(probed); continue; }
                            List<String> scanned = new ArrayList<>();
                            scanForCdnUrls(item, scanned, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                            if (!scanned.isEmpty()) carouselUrls.add(pickBestImageUrl(scanned));
                        }
                        if (carouselUrls.size() >= 2) return carouselUrls;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Step C: single photo
        String imageUrl = imageUrlFromMedia(ctx, media);
        if (imageUrl != null) return new ArrayList<>(List.of(imageUrl));

        // Step D: CDN scan fallback
        List<String> cdnUrls = collectCdnUrls(media);
        if (!cdnUrls.isEmpty()) return new ArrayList<>(List.of(cdnUrls.get(0)));

        return new ArrayList<>();
    }

    /**
     * Package-accessible: shows download dialog for a post.
     * Single URL → direct download. Multiple (carousel) → "Download current / Download all" dialog.
     * currentIndex = the visible carousel slide (from findCarouselIndex). Must be called on main thread.
     */
    @SuppressLint("DefaultLocale")
    static void showPostDownloadDialog(Context ctx, List<String> urls,
                                       String username, String mediaId, int currentIndex) {
        if (urls.isEmpty()) {
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_post_url_not_found), Toast.LENGTH_SHORT).show();
            return;
        }
        if (urls.size() == 1) {
            String url = urls.get(0);
            boolean isVid = isVideoUrl(url);
            String fn = buildFilename(username, "post", mediaId, isVid);
            Toast.makeText(ctx, isVid ? I18n.t(ctx, R.string.ig_toast_downloading_video) : I18n.t(ctx, R.string.ig_toast_downloading_photo), Toast.LENGTH_SHORT).show();
            executor.submit(() -> {
                try (OutputStream out = openOutputStream(ctx, fn, isVid, username)) {
                    downloadToStream(url, out);
                    mainHandler.post(() -> Toast.makeText(ctx,
                            isVid ? I18n.t(ctx, R.string.ig_toast_video_saved) : I18n.t(ctx, R.string.ig_toast_photo_saved),
                            Toast.LENGTH_SHORT).show());
                } catch (Throwable e) {
                    XposedBridge.log("(IE|Post|DL) single failed: " + e);
                    mainHandler.post(() -> Toast.makeText(ctx,
                            I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                }
            });
            return;
        }

        // Carousel: modern bottom sheet with two pill buttons
        int n = urls.size();
        int safeIdx = (currentIndex >= 0 && currentIndex < n) ? currentIndex : 0;
        showCarouselBottomSheet(ctx, urls, username, mediaId, n, safeIdx);
    }

    // ── Modern bottom sheet for carousel download ─────────────────────────────

    private static boolean isDarkTheme(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static GradientDrawable roundRect(int color, float radiusDp, Context ctx) {
        float r = radiusDp * ctx.getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    private static Button makePillButton(Context ctx, String label,
                                         int bgColor, int textColor, float dp) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackground(roundRect(bgColor, 14, ctx));
        btn.setAllCaps(false);
        btn.setPadding((int)(20 * dp), (int)(14 * dp), (int)(20 * dp), (int)(14 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(10 * dp);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static void showCarouselBottomSheet(Context ctx, List<String> urls,
                                                 String username, String mediaId,
                                                 int n, int safeIdx) {
        try {
            float dp   = ctx.getResources().getDisplayMetrics().density;
            boolean dk = isDarkTheme(ctx);

            int sheetBg    = dk ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
            int textPrim   = dk ? Color.WHITE                 : Color.parseColor("#1C1C1E");
            int textSec    = dk ? Color.parseColor("#AEAEB2") : Color.parseColor("#6C6C70");
            int accentBg   = Color.parseColor("#0A84FF");
            int secondBg   = dk ? Color.parseColor("#3A3A3C") : Color.parseColor("#E5E5EA");
            int secondText = dk ? Color.WHITE                 : Color.parseColor("#1C1C1E");
            int handleClr  = dk ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

            LinearLayout sheet = new LinearLayout(ctx);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setBackground(roundRect(sheetBg, 20, ctx));
            int hPad = (int)(20 * dp);
            sheet.setPadding(hPad, (int)(12 * dp), hPad, (int)(28 * dp));

            // Drag handle
            View handle = new View(ctx);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                    (int)(40 * dp), (int)(4 * dp));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = (int)(16 * dp);
            handle.setLayoutParams(handleLp);
            handle.setBackground(roundRect(handleClr, 2, ctx));
            sheet.addView(handle);

            // Title
            TextView title = new TextView(ctx);
            title.setText(I18n.t(ctx, R.string.ig_dl_title));
            title.setTextColor(textPrim);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            titleLp.bottomMargin = (int)(4 * dp);
            title.setLayoutParams(titleLp);
            sheet.addView(title);

            // Subtitle
            TextView subtitle = new TextView(ctx);
            subtitle.setText(I18n.t(ctx, R.string.ig_dl_carousel_subtitle, n));
            subtitle.setTextColor(textSec);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.bottomMargin = (int)(14 * dp);
            subtitle.setLayoutParams(subLp);
            sheet.addView(subtitle);

            Dialog dialog = new Dialog(ctx);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            // Button: Download current
            String currentLabel = I18n.t(ctx, R.string.ig_dl_carousel_current, safeIdx + 1, n);
            Button btnCurrent = makePillButton(ctx, currentLabel, accentBg, Color.WHITE, dp);
            btnCurrent.setOnClickListener(v -> {
                dialog.dismiss();
                String url = urls.get(safeIdx);
                boolean isVid = isVideoUrl(url);
                String fn = buildFilename(username, "post", mediaId, isVid);
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading), Toast.LENGTH_SHORT).show();
                executor.submit(() -> {
                    try (OutputStream out = openOutputStream(ctx, fn, isVid, username)) {
                        downloadToStream(url, out);
                        mainHandler.post(() -> Toast.makeText(ctx,
                                I18n.t(ctx, R.string.ig_toast_saved), Toast.LENGTH_SHORT).show());
                    } catch (Throwable e) {
                        mainHandler.post(() -> Toast.makeText(ctx,
                                I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                    }
                });
            });
            sheet.addView(btnCurrent);

            // Button: Download all
            Button btnAll = makePillButton(ctx, I18n.t(ctx, R.string.ig_dl_carousel_all, n),
                    secondBg, secondText, dp);
            btnAll.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_all_n_items, n), Toast.LENGTH_SHORT).show();
                executor.submit(() -> {
                    int failed = 0;
                    for (String url : urls) {
                        boolean isVid = isVideoUrl(url);
                        String fn = buildFilename(username, "post", mediaId, isVid);
                        try (OutputStream out = openOutputStream(ctx, fn, isVid, username)) {
                            downloadToStream(url, out);
                        } catch (Throwable e) {
                            failed++;
                            XposedBridge.log("(IE|Post|DL) item failed: " + e);
                        }
                    }
                    final int finalFailed = failed;
                    mainHandler.post(() -> {
                        if (finalFailed == 0) {
                            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_all_items_saved, n),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_items_partial_saved,
                                    n - finalFailed, n, finalFailed), Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            });
            sheet.addView(btnAll);

            dialog.setContentView(sheet);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setGravity(Gravity.BOTTOM);
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
                WindowManager.LayoutParams wlp = w.getAttributes();
                int margin = (int)(12 * dp);
                wlp.x = margin;
                wlp.y = margin;
                w.setAttributes(wlp);
            }
            dialog.show();

        } catch (Throwable t) {
            XposedBridge.log("(IE|Post) ❌ showCarouselBottomSheet: " + t);
        }
    }

    /**
     * Package-accessible: extracts username from a com.instagram.feed.media.Media object
     * using the DexKit-resolved dictUserGetter. Used by StoryDownloadHook.
     */
    static String extractUsernameFromMediaObject(Object media) {
        if (media == null || dictUserGetter == null || mutableMediaDictIntfClass == null) return null;
        try {
            Object dictIntf = findFieldAssignableTo(media, mutableMediaDictIntfClass);
            if (dictIntf == null) return null;
            Object user = dictUserGetter.invoke(dictIntf);
            return UserUtils.callUsernameGetter(user);
        } catch (Throwable ignored) {}
        return null;
    }

    /** @deprecated Use {@link UserUtils#callUsernameGetter(Object)} directly. */
    @Deprecated
    public static String callUsernameGetter(Object user) {
        return UserUtils.callUsernameGetter(user);
    }

    /**
     * Walks the object graph up to depth 3 looking for any object that has a
     * no-arg getUsername() method returning a valid Instagram username string.
     * At depth 0 (the Media object itself), logs all field names + types to
     * help diagnose where the user object is nested.
     */
    private static String scanObjectForUsername(Object obj, int depth,
                                                 Set<Object> visited) {
        if (obj == null || depth > 3 || visited.contains(obj)) return null;
        visited.add(obj);

        // Try getUsername() on this object directly
        try {
            Object result = obj.getClass().getMethod("getUsername").invoke(obj);
            if (result instanceof String s && !s.isEmpty() && s.matches("[a-zA-Z0-9._]{1,30}")) {
                XposedBridge.log("(IE|DL|Username) getUsername() hit on "
                        + obj.getClass().getName() + " → " + s);
                return s;
            }
        } catch (Throwable ignored) {}

        if (depth >= 3) return null;

        // Scan all non-primitive, non-String, non-array fields — no class filter,
        // rely on depth limit + visited set to prevent runaway recursion
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class || ft.isArray()) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val == null) continue;
                    String u = scanObjectForUsername(val, depth + 1, visited);
                    if (u != null) return u;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private void onDownloadClicked(Context ctx, List<String> urls, View saveBtn) {
        currentDownloadUsername = getUsernameFromView(saveBtn);
        currentDownloadMediaId  = getMediaIdFromView(saveBtn);
        XposedBridge.log("(IE|DL) onDownloadClicked username=" + currentDownloadUsername + " mediaId=" + currentDownloadMediaId);
        List<String> videos = new ArrayList<>();
        List<String> images = new ArrayList<>();
        for (String url : urls) {
            if (isVideoUrl(url)) videos.add(url);
            else                 images.add(url);
        }
        XposedBridge.log("(IE|DL) total=" + urls.size()
                + " videos=" + videos.size() + " images=" + images.size());
        for (int i = 0; i < videos.size(); i++)
            XposedBridge.log("(IE|DL) video[" + i + "]=" + videos.get(i));
        for (int i = 0; i < images.size(); i++)
            XposedBridge.log("(IE|DL) image[" + i + "]=" + images.get(i));

        if (!videos.isEmpty() && !images.isEmpty()) {
            handleMixedContent(ctx, urls, videos, images, saveBtn);
        } else if (!videos.isEmpty()) {
            handleVideoDownload(ctx, videos, saveBtn);
        } else if (images.size() > 1) {
            showCarouselDialog(ctx, images, saveBtn);
        } else if (!images.isEmpty()) {
            startDirectDownload(ctx, images.get(0), false);
        }
    }

    private void handleMixedContent(Context ctx, List<String> allUrls,
                                     List<String> videos, List<String> images, View saveBtn) {
        executor.submit(() -> {
            String videoUrl = videos.get(0);
            TrackInfo t = probeUrl(videoUrl);
            XposedBridge.log("(IE|DL) probeUrl=" + videoUrl
                    + " hasVideo=" + t.hasVideo + " hasAudio=" + t.hasAudio);
            mainHandler.post(() -> {
                if (!t.hasVideo && t.hasAudio) {
                    // Audio-only background track — download the image instead
                    startDirectDownload(ctx, images.get(0), false);
                } else {
                    // Real video mixed with images — show carousel dialog for all items
                    showCarouselDialog(ctx, allUrls, saveBtn);
                }
            });
        });
    }

    private void handleVideoDownload(Context ctx, List<String> videos, View saveBtn) {
        if (videos.size() == 1) {
            startDirectDownload(ctx, videos.get(0), true);
            return;
        }
        // Multiple video URLs → video carousel, show selection dialog immediately.
        // (DASH streams only ever produce a single URL via our Step-A resolver;
        //  multiple URLs always come from Step-B carousel item extraction.)
        showCarouselDialog(ctx, videos, saveBtn);
    }

    private void showCarouselDialog(Context ctx, List<String> urls, View saveBtn) {
        int idx = saveBtn != null ? findCarouselPosition(saveBtn) : 0;
        if (idx >= urls.size()) idx = 0;
        final int current = idx;
        int n = urls.size();
        new AlertDialog.Builder(ctx)
                .setTitle(I18n.t(ctx, R.string.ig_dl_title))
                .setItems(new CharSequence[]{
                        I18n.t(ctx, R.string.ig_dl_carousel_current, current + 1, n),
                        I18n.t(ctx, R.string.ig_dl_carousel_all, n)
                }, (d, w) -> {
                    if (w == 0) {
                        String url = urls.get(current);
                        startDirectDownload(ctx, url, isVideoUrl(url));
                    } else {
                        for (String u : urls) startDirectDownload(ctx, u, isVideoUrl(u));
                        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_n_items, n), Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private static int findCarouselPosition(View anchor) {
        View container = anchor;
        for (int i = 0; i < 8 && container.getParent() instanceof View; i++) {
            container = (View) container.getParent();
        }
        if (!(container instanceof ViewGroup vg)) return 0;
        int pos = searchForPager(vg, 0);
        return pos >= 0 ? pos : 0;
    }

    private static int searchForPager(ViewGroup group, int depth) {
        if (depth > 8) return -1;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            for (String methodName : new String[]{"getCurrentItem", "getCurrentDataIndex"}) {
                try {
                    Method m = child.getClass().getMethod(methodName);
                    Object r = m.invoke(child);
                    if (r instanceof Integer val && val >= 0) return val;
                } catch (Throwable ignored) {}
            }
            if (child instanceof ViewGroup vg) {
                int r = searchForPager(vg, depth + 1);
                if (r >= 0) return r;
            }
        }
        return -1;
    }

    private void startDirectDownload(Context ctx, String url, boolean isVideo) {
        String fn = buildFilename(currentDownloadUsername, "post", currentDownloadMediaId, isVideo);
        XposedBridge.log("(IE|DL) startDirectDownload file=" + fn);
        Toast.makeText(ctx, isVideo ? I18n.t(ctx, R.string.ig_toast_downloading_video) : I18n.t(ctx, R.string.ig_toast_downloading_photo), Toast.LENGTH_SHORT).show();
        executor.submit(() -> {
            try (OutputStream out = openOutputStream(ctx, fn, isVideo, currentDownloadUsername)) {
                downloadToStream(url, out);
                mainHandler.post(() -> Toast.makeText(ctx,
                        isVideo ? I18n.t(ctx, R.string.ig_toast_video_saved) : I18n.t(ctx, R.string.ig_toast_photo_saved),
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                XposedBridge.log("(IE|DL) download failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                mainHandler.post(() -> Toast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void downloadAndMerge(Context ctx, String videoUrl, String audioUrl) {
        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_merging_video_audio), Toast.LENGTH_SHORT).show();
        executor.submit(() -> {
            File tv = null, ta = null, merged = null;
            try {
                File cache = ctx.getCacheDir();
                long ts = System.currentTimeMillis();
                tv     = new File(cache, "ie_v_" + ts + ".mp4");
                ta     = new File(cache, "ie_a_" + ts + ".mp4");
                merged = new File(cache, "ie_m_" + ts + ".mp4");
                downloadToFile(videoUrl, tv);
                downloadToFile(audioUrl, ta);
                String fn = buildFilename(currentDownloadUsername, "post", currentDownloadMediaId, true);
                mergeVideoAudio(tv.getAbsolutePath(), ta.getAbsolutePath(), merged.getAbsolutePath());
                saveFileToDestination(ctx, merged, fn, true, currentDownloadUsername);
                mainHandler.post(() -> Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_video_saved),
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable e) {
                mainHandler.post(() -> startDirectDownload(ctx, videoUrl, true));
            } finally {
                if (tv     != null) //noinspection ResultOfMethodCallIgnored
                    tv.delete();
                if (ta     != null) //noinspection ResultOfMethodCallIgnored
                    ta.delete();
                if (merged != null) //noinspection ResultOfMethodCallIgnored
                    merged.delete();
            }
        });
    }

    private static void downloadToFile(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
        conn.connect();
        try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        } finally { conn.disconnect(); }
    }

    static void downloadToStream(String url, OutputStream out) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
        conn.connect();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { conn.disconnect(); }
    }

    private static void mergeVideoAudio(String vp, String ap, String op) throws Exception {
        MediaExtractor vEx = new MediaExtractor(), aEx = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(op, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            vEx.setDataSource(vp); aEx.setDataSource(ap);
            int vi = selectTrack(vEx, "video/"), ai = selectTrack(aEx, "audio/");
            if (vi < 0 || ai < 0) throw new Exception("Missing tracks");
            int vo = mux.addTrack(vEx.getTrackFormat(vi)), ao = mux.addTrack(aEx.getTrackFormat(ai));
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            copyTrack(vEx, mux, vo, buf, info); copyTrack(aEx, mux, ao, buf, info);
            mux.stop();
        } finally { vEx.release(); aEx.release(); mux.release(); }
    }

    private static int selectTrack(MediaExtractor ex, String mime) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(mime)) { ex.selectTrack(i); return i; }
        }
        return -1;
    }

    @SuppressLint("WrongConstant")
    private static void copyTrack(MediaExtractor ex, MediaMuxer mux, int out,
                                  ByteBuffer buf, MediaCodec.BufferInfo info) {
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.offset = 0; info.size = sz;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(out, buf, info);
            ex.advance();
        }
    }

    private static TrackInfo probeUrl(String url) {
        MediaExtractor ex = new MediaExtractor();
        boolean hv = false, ha = false;
        try {
            ex.setDataSource(url);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (m.startsWith("video/")) hv = true;
                if (m.startsWith("audio/")) ha = true;
            }
        } catch (Throwable ignored) { } finally { ex.release(); }
        return new TrackInfo(hv, ha);
    }

    private static final class TrackInfo {
        final boolean hasVideo, hasAudio;
        TrackInfo(boolean v, boolean a) { hasVideo = v; hasAudio = a; }
    }

    /**
     * Returns true if this CDN URL points to an Instagram feed media item
     * (photo or video) — not a profile picture, UI asset, or other non-media content.
     *
     * Key CDN path segments:
     *   t51.2885-15  = feed photo (INCLUDE)
     *   t51.2885-19  = profile picture (EXCLUDE)
     *   t50.2886-16  = feed video (INCLUDE)
     *   t51.39750    = exclude (story thumbnails / non-feed content)
     */
    static boolean isCdnMediaUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false;
        // Exclude profile pictures: the t51 CDN path always uses suffix -19 for avatars
        // regardless of the bucket number (t51.2885-19, t51.82787-19, etc.)
        // Pattern: /t51.<digits>-19/
        if (url.contains("/t51.") && url.contains("-19/")) return false;
        // Exclude other known non-feed content
        if (url.contains("t51.39750")) return false;
        return true;
    }

    /**
     * Returns true if this CDN URL is a video (not a still image or audio-only track).
     *
     * Instagram CDN naming convention:
     *   t50.xxxx = all video CDN path segments (t50.2886-16, t50.29441-2, t50.16800-16, etc.)
     *   t51.xxxx = image content
     *   /o1/     = Reels/Clips video (path may omit t50 segment)
     *
     * Known audio-only (exclude):
     *   /o1/v/t2/ = background music track for Reels
     */
    static boolean isVideoUrl(String url) {
        // All Instagram video CDN path segments begin with t50.
        // Covers all variants: t50.2886-16, t50.29441-2, t50.16800-16, etc.
        if (url.contains("t50.")) return true;
        // Reels/Clips CDN paths use /o1/ regardless of whether they carry a t50 segment.
        // Note: /o1/v/t2/ is NOT audio-only — it is the standard Reels progressive MP4 path.
        if (url.contains("/o1/")) return true;
        return false;
    }

    private static boolean hasAncestorWithId(View view, int targetId) {
        if (targetId == 0) return false;
        android.view.ViewParent p = view.getParent();
        for (int i = 0; i < 6 && p instanceof View v; i++, p = v.getParent()) {
            if (v.getId() == targetId) return true;
        }
        return false;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
