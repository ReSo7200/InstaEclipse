package ps.reso.instaeclipse.mods.media;

import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class StoryDownloadHook {



    // VideoVersionIntf resolved once at install time — same interface used by feed downloader
    private static Class<?> videoVersionIntfClass;
    private static Method   videoVersionGetUrl;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final class StoryMedia {
        final String url;
        final boolean video;

        StoryMedia(String url, boolean video) {
            this.url = url;
            this.video = video;
        }
    }

    private static final class StoryMediaOptions {
        final String imageUrl;
        final String videoUrl;
        final boolean modelSaysVideo;

        StoryMediaOptions(String imageUrl, String videoUrl, boolean modelSaysVideo) {
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.modelSaysVideo = modelSaysVideo;
        }
    }

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            videoVersionIntfClass = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf");
            videoVersionGetUrl    = videoVersionIntfClass.getMethod("getUrl");
        } catch (Throwable ignored) {}

        installButtonInjectorHook(bridge, classLoader);
        installClickHandlerHook(bridge, classLoader);
        resolveExpiringGetter(bridge, classLoader);
        installStoryCaptureHook(bridge, classLoader);
    }

    // ── Story cache: capture each VIEWED story (per-page bind, fires for every story shown) ──
    private static java.lang.reflect.Method expiringGetter; // Media."expiring_at" getter, () -> Long

    private void resolveExpiringGetter(DexKitBridge bridge, ClassLoader cl) {
        try {
            if (DexKitCache.isCacheValid()) {
                java.lang.reflect.Method c = DexKitCache.loadMethod("StoryCache_expiring", cl);
                if (c != null) { c.setAccessible(true); expiringGetter = c; return; }
            }
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass("com.instagram.feed.media.Media").paramCount(0)
                    .returnType("java.lang.Long").usingStrings("expiring_at")))) {
                try {
                    java.lang.reflect.Method m = md.getMethodInstance(cl);
                    m.setAccessible(true); expiringGetter = m;
                    DexKitCache.saveMethod("StoryCache_expiring", m);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) expiring getter: " + t); }
    }

    /**
     * Hooks the story viewer's per-page bind (ReelViewerFragment.onCurrentActiveItemBound — anchored
     * by that stable string; the method name is obfuscated) which fires for EVERY story shown. Its
     * first ReelItem param is the current story; we record it to the 24h cache.
     */
    private void installStoryCaptureHook(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook capture = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.cacheStories) return;
                Object reelItem = null;
                for (Object a : p.args) {
                    if (a != null && a.getClass().getName().equals("com.instagram.model.reels.ReelItem")) { reelItem = a; break; }
                }
                if (reelItem != null) captureFromReelItem(reelItem);
            }
        };
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("ReelViewerFragment.onCurrentActiveItemBound")))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), capture); n++; } catch (Throwable ignored) {}
            }
            if (n > 0 && FeatureFlags.cacheStories) FeatureStatusTracker.setHooked("CacheStories");
            ModuleLog.line("(IE|StoryCache) capture hook: " + n + " method(s)");
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) ⚠️ capture hook: " + t.getMessage()); }
    }

    private void captureFromReelItem(Object reelItem) {
        try {
            Object media = findMediaObject(reelItem);
            if (media == null) return;
            Context ctx = AndroidAppHelper.currentApplication();
            String id;
            try {
                Object rid = reelItem.getClass().getMethod("getId").invoke(reelItem);
                id = (rid instanceof String s && !s.isEmpty()) ? s.split("_")[0] : null;
            } catch (Throwable t) { id = null; }
            if (id == null || ps.reso.instaeclipse.utils.media.StoryCache.has(id)) return;
            List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
            if (urls == null || urls.isEmpty()) return;
            final String url = urls.get(0);
            final boolean video = FeedVideoDownloadHook.isVideoUrl(url);
            // Use the ReelItem-based username resolver (handles a ReelItem passed directly) — the
            // media-dictionary resolver doesn't populate for story media, giving "unknown".
            String author = extractUsernameFromReelItemHolder(reelItem);
            if (author == null || author.isEmpty()) author = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
            long expiring = 0;
            if (expiringGetter != null) {
                try {
                    Object v = expiringGetter.invoke(media);
                    if (v instanceof Long l && l > 0) expiring = l < 100000000000L ? l * 1000L : l; // sec→ms
                } catch (Throwable ignored) {}
            }
            final String fid = id, fauthor = author; final long fexp = expiring;
            FeedVideoDownloadHook.executor.submit(() ->
                    ps.reso.instaeclipse.utils.media.StoryCache.capture(fid, fauthor, url, video, fexp));
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) captureFromReelItem: " + t); }
    }

    // ── Hook 1: inject "Download" into the story options button list ──────────
    //
    // Found via "[INTERNAL] Pause Playback" string + CharSequence[] return type, 1 param.
    // afterHookedMethod: appends our "Download" entry to the returned CharSequence[] array.

    private void installButtonInjectorHook(DexKitBridge bridge, ClassLoader classLoader) {
        // Hook EVERY CharSequence[]-returning candidate behind the "[INTERNAL] Pause Playback"
        // anchor — NOT just the first 1-arg one. Instagram builds the option list with a DIFFERENT
        // method for your OWN story (a 3-arg static helper: Delete/Archive/Save video/…) than for
        // someone else's (1-arg: Report/Mute/AI info). Filtering paramCount(1) + first-match only
        // ever caught the others'-story builder, so Download never appeared on your own stories.
        // Own-story Download matters because it grabs the rendered video_version and KEEPS the
        // music, which IG's native Save drops. (Ported from PR #200 by izadiegizabal.) Anchored on
        // the stable string only, so it stays valid across versions; static + instance both accepted.
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("[INTERNAL] Pause Playback")));
            if (methods.isEmpty()) {
                ModuleLog.line("(IE|Story) ❌ Button builder method not found");
                return;
            }

            XC_MethodHook injector = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableStoryDownload) return;
                    if (!(param.getResult() instanceof CharSequence[] original) || original == null) return;

                    // Guard: don't inject twice
                    String dlLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);
                    for (CharSequence cs : original) {
                        if (cs != null && dlLabel.contentEquals(cs)) return;
                    }

                    CharSequence[] extended = new CharSequence[original.length + 1];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    extended[original.length] = dlLabel;
                    param.setResult(extended);
                }
            };

            int hooked = 0;
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    Class<?> rt = m.getReturnType();
                    if (rt.isArray() && CharSequence.class.isAssignableFrom(rt.getComponentType())) {
                        XposedBridge.hookMethod(m, injector);
                        hooked++;
                    }
                } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|Story) button injector hooked " + hooked + " builder(s)");
            if (hooked == 0) ModuleLog.line("(IE|Story) ❌ No CharSequence[] return type candidate found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Story) ❌ Button builder DexKit: " + t);
        }
    }


    // ── Hook 2: handle click on our "Download" option ────────────────────────
    //
    // Found via "explore_viewer" + "friendships/mute_friend_reel/%s/" strings.
    // beforeHookedMethod: reads the CharSequence param (the tapped label); if it
    // equals "Download", triggers the download. Context and ReelItem are resolved
    // from fields on 'this' or same-class params.

    private void installClickHandlerHook(DexKitBridge bridge, ClassLoader classLoader) {
        // Anchor ONLY on the common "[INTERNAL] Pause Playback" string and hook EVERY void
        // dispatcher behind it. The old matcher also required "explore_viewer" +
        // "mute_friend_reel" — but those exist ONLY on someone-else's-story dispatcher, so the
        // self-story handler could never match and Download did nothing on your own stories.
        // The self-story dispatcher is a STATIC helper (takes the outer class as a param), so we
        // must not exclude statics. Our runtime label check (tapped == "Download") gates it, so
        // hooking the extra dispatchers is harmless. (Ported from PR #200 by izadiegizabal.)
        List<MethodData> methods;
        try {
            methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("void")
                            .usingStrings("[INTERNAL] Pause Playback")));
        } catch (Throwable t) {
            ModuleLog.line("(IE|Story) ❌ Click handler DexKit: " + t);
            return;
        }
        if (methods == null || methods.isEmpty()) {
            ModuleLog.line("(IE|Story) ❌ Click handler not found");
            return;
        }

        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableStoryDownload) return;

                // 1. Find which button was tapped
                CharSequence tapped = null;
                for (Object arg : param.args) {
                    if (arg instanceof CharSequence cs) { tapped = cs; break; }
                }
                String dlLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);
                if (tapped == null || !dlLabel.contentEquals(tapped)) return;

                // 2. Consume the event — Instagram won't process an option it didn't add
                param.setResult(null);

                // 3. Locate the ReelItem holder — 'this' or any same-class param (self-story
                //    passes the outer class as an argument).
                Object holder = findReelItemHolder(param);
                Object effectiveHolder = holder != null ? holder : param.thisObject;

                // 4. Context — the self-story dispatcher passes the ReelItem and the Context on
                //    SEPARATE args, so search 'this' AND every argument, not just the holder.
                Context ctx = findContextAcrossParam(param, effectiveHolder);
                if (ctx == null) {
                    ModuleLog.line("(IE|Story) ❌ Context not found");
                    return;
                }

                // 5. Extract story URL via ReelItem → media object field graph
                StoryMediaOptions media = extractStoryMediaOptions(ctx, effectiveHolder);
                if (media == null) {
                    Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_story_url_not_found), Toast.LENGTH_SHORT).show();
                    return;
                }

                String username = extractUsernameFromReelItemHolder(effectiveHolder);
                String mediaId = extractMediaIdFromReelItemHolder(effectiveHolder);
                handleStoryMedia(ctx, media, username, mediaId);
            }
        };

        int hooked = 0;
        for (MethodData md : methods) {
            try {
                Method m = md.getMethodInstance(classLoader);
                if (m.getParameterCount() == 0) continue; // dispatchers receive the tapped label
                XposedBridge.hookMethod(m, clickHook);
                hooked++;
            } catch (Throwable ignored) {}
        }
        if (hooked == 0) {
            ModuleLog.line("(IE|Story) ❌ no click dispatcher hooked");
            return;
        }
        ModuleLog.line("(IE|Story) click handler hooked " + hooked + " dispatcher(s)");
        FeatureStatusTracker.setHooked("StoryDownload");
    }

    /** Context lookup for the click dispatcher: try the ReelItem holder, then 'this', then each
     *  argument (self-story passes the Context on a separate arg, or an arg may BE a Context). */
    private static Context findContextAcrossParam(XC_MethodHook.MethodHookParam param, Object preferred) {
        Context c = findContext(preferred);
        if (c != null) return c;
        if (param.thisObject != preferred) {
            c = findContext(param.thisObject);
            if (c != null) return c;
        }
        for (Object arg : param.args) {
            if (arg instanceof Context ctx) return ctx;
            c = findContext(arg);
            if (c != null) return c;
        }
        return null;
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    /**
     * Finds the object (either 'this' or a same-class parameter) that holds the
     * ReelItem field. The click handler sometimes receives a reference to the outer
     * class as a parameter rather than p0/this.
     */
    private static Object findReelItemHolder(XC_MethodHook.MethodHookParam param) {
        if (hasReelItemField(param.thisObject)) return param.thisObject;
        // Check method parameters — the outer class is sometimes passed as an arg
        for (Object arg : param.args) {
            if (arg != null && hasReelItemField(arg)) return arg;
        }
        return null;
    }

    private static boolean hasReelItemField(Object obj) {
        if (obj == null) return false;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().equals("com.instagram.model.reels.ReelItem")) return true;
            }
            cls = cls.getSuperclass();
        }
        return false;
    }

    /**
     * Extracts every downloadable representation from the holder object.
     *   1. Reads the ReelItem field from the holder.
     *   2. Searches for VideoVersionIntf → video URL (videos).
     *   3. Searches for image Candidate objects (CDN URL + width int + height int) and
     *      picks the one with the largest pixel area (photos).
     *   4. Falls back to raw CDN string scan with area-based ranking.
     *
     * A photo story with music commonly exposes both image_versions2 (the original
     * still) and video_versions (the rendered story with audio). Do not return after
     * finding the MP4: keeping both URLs is what lets the user choose the JPG instead.
     */
    private static StoryMediaOptions extractStoryMediaOptions(Context ctx, Object holder) {
        if (holder == null) return null;
        try {
            Object reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem");
            ModuleLog.line("(IE|Story) reelItem=" +
                    (reelItem != null ? reelItem.getClass().getName() : "null"));

            Object target = reelItem != null ? reelItem : holder;
            Object mediaObject = findMediaObject(target);
            Object modelTarget = mediaObject != null ? mediaObject : target;
            boolean modelSaysVideo = FeedVideoDownloadHook.isMediaVideo(modelTarget)
                    || (modelTarget != target && FeedVideoDownloadHook.isMediaVideo(target));

            // Use the same source-aware extractor as feed/reels first. It understands
            // Pando video_versions getters whose URLs no longer expose a video-looking path.
            String videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(modelTarget);
            if (videoUrl == null && modelTarget != target) {
                videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(target);
            }

            // Try video URL via VideoVersionIntf scan
            if (videoUrl == null && videoVersionIntfClass != null && videoVersionGetUrl != null) {
                videoUrl = findVideoUrl(target,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (videoUrl != null) {
                    FeedVideoDownloadHook.rememberVideoUrl(videoUrl);
                }
            }

            // MediaExtKt knows the canonical image_versions2 URL and avoids choosing a
            // smaller music-sticker/album-art image when a Media object is available.
            String imageUrl = mediaObject != null
                    ? FeedVideoDownloadHook.imageUrlFromMedia(ctx, mediaObject) : null;
            if (imageUrl != null && FeedVideoDownloadHook.isVideoUrl(imageUrl)) {
                imageUrl = null;
            }

            // Walk the graph looking for image Candidate objects when the canonical
            // Media helper is unavailable.
            // A Candidate has a CDN URL string field + at least two int fields with
            // plausible pixel dimensions. Field names are obfuscated so we match by type
            // and value range. Pick the candidate with the largest width×height area.
            if (imageUrl == null) {
                List<CandidateInfo> candidates = new ArrayList<>();
                collectImageCandidates(target, candidates,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                ModuleLog.line("(IE|Story) imageCandidates=" + candidates.size());
                if (!candidates.isEmpty()) {
                    candidates.sort((a, b) -> Integer.compare(b.area, a.area));
                    imageUrl = candidates.get(0).url;
                    ModuleLog.line("(IE|Story) bestCandidate area=" + candidates.get(0).area);
                }
            }

            // Last resort: split a raw CDN scan into image and video candidates. This
            // never silently labels an image cover as a video (the issue #204 failure).
            List<String> cdnUrls = new ArrayList<>();
            scanCdnUrls(target, cdnUrls, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            List<String> imageUrls = new ArrayList<>();
            for (String candidate : cdnUrls) {
                if (FeedVideoDownloadHook.isVideoUrl(candidate)) {
                    if (videoUrl == null) {
                        videoUrl = candidate;
                        FeedVideoDownloadHook.rememberVideoUrl(candidate);
                    }
                } else {
                    imageUrls.add(candidate);
                }
            }
            if (imageUrl == null && !imageUrls.isEmpty()) imageUrl = pickBestUrl(imageUrls);

            if (imageUrl == null && videoUrl == null) return null;
            return new StoryMediaOptions(imageUrl, videoUrl, modelSaysVideo);

        } catch (Throwable t) {
            ModuleLog.line("(IE|Story) extractStoryMediaOptions error: " + t);
        }
        return null;
    }

    /** Resolves the Media nested in ReelItem without relying on obfuscated method names. */
    private static Object findMediaObject(Object obj) {
        if (obj == null) return null;
        if (obj.getClass().getName().equals("com.instagram.feed.media.Media")) return obj;

        Object direct = readFieldByTypeName(obj, "com.instagram.feed.media.Media");
        if (direct != null) return direct;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method method : cls.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                Class<?> returnType = method.getReturnType();
                if (returnType.isPrimitive() || returnType == String.class
                        || returnType == void.class) continue;
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(obj);
                    if (result != null && result.getClass().getName()
                            .equals("com.instagram.feed.media.Media")) return result;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Reads the first field whose declared type name equals {@code typeName}. */
    private static Object readFieldByTypeName(Object obj, String typeName) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Depth-limited field-graph walk looking for a VideoVersionIntf and calling getUrl(). */
    private static String findVideoUrl(Object obj, Set<Object> visited, int depth) {
        if (obj == null || depth > 5 || !visited.add(obj)) return null;
        if (videoVersionIntfClass == null || videoVersionGetUrl == null) return null;

        if (videoVersionIntfClass.isInstance(obj)) {
            try {
                String url = (String) videoVersionGetUrl.invoke(obj);
                if (url != null && isCdnUrl(url)) {
                    FeedVideoDownloadHook.rememberVideoUrl(url);
                    return url;
                }
            } catch (Throwable ignored) {}
        }

        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook."))
            return null;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        for (Object elem : list) {
                            if (videoVersionIntfClass.isInstance(elem)) {
                                try {
                                    String url = (String) videoVersionGetUrl.invoke(elem);
                                    if (url != null && isCdnUrl(url)) {
                                        FeedVideoDownloadHook.rememberVideoUrl(url);
                                        return url;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    } else {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook.")) {
                            String found = findVideoUrl(val, visited, depth + 1);
                            if (found != null) return found;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Depth-limited field-graph scan for Instagram CDN URL strings. */
    private static void scanCdnUrls(Object obj, List<String> out, int depth, Set<Object> visited) {
        if (obj == null || depth > 5 || out.size() >= 20) return;
        if (!visited.add(obj)) return;
        String cn = obj.getClass().getName();
        if (cn.startsWith("android.") || cn.startsWith("java.lang.") || cn.startsWith("kotlin.")) return;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof String s) {
                        if (isCdnUrl(s) && !out.contains(s)) out.add(s);
                    } else if (val instanceof List<?> list) {
                        for (Object item : list) scanCdnUrls(item, out, depth + 1, visited);
                    } else {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook.")) {
                            scanCdnUrls(val, out, depth + 1, visited);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    // ── Image candidate scanner ───────────────────────────────────────────────

    private static final class CandidateInfo {
        final String url;
        final int    area;
        CandidateInfo(String url, int area) { this.url = url; this.area = area; }
    }

    /**
     * Walks the object graph looking for Instagram image Candidate objects.
     * A Candidate is identified by having:
     *   • At least one String field/method that is a CDN image URL (not video)
     *   • At least two int/long fields/methods whose values are plausible pixel dimensions (50–20 000 px)
     *
     * Field names are ignored — they are obfuscated in Instagram builds.
     * No-arg methods are also probed to handle Pando/LiveTree JNI-backed nodes where
     * data is not exposed as Java fields (fixes lower-quality photos on some story types).
     * The two largest plausible-dimension ints are multiplied to estimate the area.
     */
    private static void collectImageCandidates(Object obj, List<CandidateInfo> out,
                                               Set<Object> visited, int depth) {
        if (obj == null || depth > 7 || out.size() >= 40) return;
        if (!visited.add(obj)) return;

        String cn = obj.getClass().getName();
        if (cn.startsWith("android.") || cn.startsWith("java.lang.") || cn.startsWith("kotlin.")) return;

        // Scan this object's own fields looking for (url + dims) pattern
        String candidateUrl = null;
        List<Integer> dims = new ArrayList<>();

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try {
                    if (f.getType() == String.class) {
                        String v = (String) f.get(obj);
                        if (v != null && isCdnUrl(v) && !isVideoUrl(v)) candidateUrl = v;
                    } else if (f.getType() == int.class) {
                        int v = f.getInt(obj);
                        if (v >= 50 && v <= 20_000) dims.add(v);
                    } else if (f.getType() == long.class) {
                        long v = f.getLong(obj);
                        if (v >= 50 && v <= 20_000) dims.add((int) v);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        // Method probe for Pando/LiveTree JNI-backed nodes — data not exposed as Java fields
        if (cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("com.facebook.")) {
            cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    try {
                        m.setAccessible(true);
                        Class<?> ret = m.getReturnType();
                        if (ret == String.class) {
                            Object r = m.invoke(obj);
                            if (r instanceof String s && isCdnUrl(s) && !isVideoUrl(s)
                                    && candidateUrl == null) candidateUrl = s;
                        } else if (ret == int.class) {
                            Object r = m.invoke(obj);
                            if (r instanceof Integer v && v >= 50 && v <= 20_000) dims.add(v);
                        } else if (ret == long.class) {
                            Object r = m.invoke(obj);
                            if (r instanceof Long v && v >= 50 && v <= 20_000) dims.add((int)(long) v);
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        }

        if (candidateUrl != null && dims.size() >= 2) {
            dims.sort(Collections.reverseOrder());
            out.add(new CandidateInfo(candidateUrl, dims.get(0) * dims.get(1)));
            return; // leaf candidate — don't recurse further into it
        }

        // Not a candidate — recurse into Instagram/Facebook/X. objects and lists
        cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        for (Object item : list)
                            collectImageCandidates(item, out, visited, depth + 1);
                    } else if (!(val instanceof String)) {
                        String vcn = val.getClass().getName();
                        if (vcn.startsWith("X.") || vcn.startsWith("com.instagram.")
                                || vcn.startsWith("com.facebook.")) {
                            collectImageCandidates(val, out, visited, depth + 1);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Context findContext(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(obj);
                        if (v instanceof Context ctx) return ctx;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static boolean isCdnUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        if (!url.contains("cdninstagram.com") && !url.contains("fbcdn.net")) return false;
        if (url.contains("/t51.") && url.contains("-19/")) return false; // profile pics
        return true;
    }

    private static boolean isVideoUrl(String url) {
        return FeedVideoDownloadHook.isVideoUrl(url);
    }

    /**
     * Prefer video URLs; among images pick the one with the largest pixel area.
     * Instagram embeds resolution as NNNxNNN in CDN paths (e.g. 1080x1920), so
     * parsing it directly is the most reliable way to select the full-size copy.
     */
    private static String pickBestUrl(List<String> urls) {
        for (String u : urls) if (isVideoUrl(u)) return u;
        String best = null;
        int bestArea = 0;
        for (String u : urls) {
            int area = parseUrlArea(u);
            if (area > bestArea) { bestArea = area; best = u; }
        }
        return best != null ? best : urls.get(0);
    }

    /** Extracts the largest NNNxNNN area found inside a CDN URL. */
    private static int parseUrlArea(String url) {
        int maxArea = 0;
        int i = 0;
        while (i < url.length()) {
            // Find a digit run
            if (!Character.isDigit(url.charAt(i))) { i++; continue; }
            int numStart = i;
            while (i < url.length() && Character.isDigit(url.charAt(i))) i++;
            // Must be followed by 'x'
            if (i >= url.length() || url.charAt(i) != 'x') continue;
            i++; // skip 'x'
            if (i >= url.length() || !Character.isDigit(url.charAt(i))) continue;
            int numMid = i;
            while (i < url.length() && Character.isDigit(url.charAt(i))) i++;
            try {
                int w = Integer.parseInt(url.substring(numStart, numMid - 1));
                int h = Integer.parseInt(url.substring(numMid, i));
                int area = w * h;
                if (area > maxArea) maxArea = area;
            } catch (NumberFormatException ignored) {}
        }
        return maxArea;
    }

    // ── Username extraction ───────────────────────────────────────────────────

    /**
     * Tries to extract the story author's username from the holder or ReelItem object.
     * ReelItem is non-obfuscated so getUser() and getUsername() are stable method names.
     */
    private static String extractUsernameFromReelItemHolder(Object holder) {
        if (holder == null) {
            ModuleLog.line("(IE|Story|Username) holder is null");
            return null;
        }
        ModuleLog.line("(IE|Story|Username) searching in holder=" + holder.getClass().getName());
        try {
            // Step 1: find the ReelItem field on the holder
            Object reelItem = null;
            Class<?> cls = holder.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(holder);
                        if (val != null && val.getClass().getName()
                                .equals("com.instagram.model.reels.ReelItem")) {
                            reelItem = val;
                            ModuleLog.line("(IE|Story|Username) found ReelItem in field="
                                    + f.getName() + " on " + cls.getName());
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (reelItem != null) break;
                cls = cls.getSuperclass();
            }
            if (reelItem == null && holder.getClass().getName()
                    .equals("com.instagram.model.reels.ReelItem")) {
                reelItem = holder;
                ModuleLog.line("(IE|Story|Username) holder is itself a ReelItem");
            }
            if (reelItem == null) {
                ModuleLog.line("(IE|Story|Username) ❌ ReelItem not found in holder");
                return null;
            }

            // Step 2: probe all no-arg non-primitive methods on ReelItem.
            // Priority: find a method returning com.instagram.user.model.User.
            // Fallback: if a method returns com.instagram.feed.media.Media → delegate to feed extractor.
            for (Method m : reelItem.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> ret = m.getReturnType();
                if (ret.isPrimitive() || ret == String.class || ret == void.class) continue;
                try {
                    m.setAccessible(true);
                    Object candidate = m.invoke(reelItem);
                    if (candidate == null) continue;

                    String candidateClass = candidate.getClass().getName();

                    // Direct User object — use DexKit-resolved getter (stable int constant -265713450)
                    if (candidateClass.equals("com.instagram.user.model.User")) {
                        String username = UserUtils.callUsernameGetter(candidate);
                        if (username != null) {
                            ModuleLog.line("(IE|Story|Username) reelItem." + m.getName() + "() [User] → " + username);
                            return username;
                        }
                        continue;
                    }

                    // Media object — delegate to feed extractor (has LiveTreeMediaDict path)
                    if (candidateClass.equals("com.instagram.feed.media.Media")) {
                        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(candidate);
                        if (username != null) {
                            ModuleLog.line("(IE|Story|Username) reelItem." + m.getName()
                                    + "() [Media] → " + username);
                            return username;
                        }
                        continue; // don't probe String methods on Media
                    }
                } catch (Throwable ignored) {}
            }

            ModuleLog.line("(IE|Story|Username) ❌ username not found on ReelItem methods");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Story|Username) ❌ Exception: " + t);
        }
        return null;
    }

    private static boolean looksLikeUsername(String s) {
        return s != null && s.length() >= 2 && s.length() <= 30
                && s.matches("[a-zA-Z0-9._]+")
                && !s.matches("\\d+");   // exclude pure numeric IDs
    }

    /** Extracts the short media ID from the ReelItem held by the holder (first segment of getId()). */
    private static String extractMediaIdFromReelItemHolder(Object holder) {
        if (holder == null) return null;
        try {
            Object reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem");
            if (reelItem == null && holder.getClass().getName().equals("com.instagram.model.reels.ReelItem")) {
                reelItem = holder;
            }
            if (reelItem == null) return null;
            Object id = reelItem.getClass().getMethod("getId").invoke(reelItem);
            if (id instanceof String s && !s.isEmpty()) return s.split("_")[0];
        } catch (Throwable ignored) {}
        return null;
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    private void handleStoryMedia(Context ctx, StoryMediaOptions media,
                                  String username, String mediaId) {
        StoryDownloadChoicePolicy.Decision decision = StoryDownloadChoicePolicy.decide(
                media.imageUrl != null, media.videoUrl != null, media.modelSaysVideo);
        switch (decision) {
            case DOWNLOAD_PHOTO -> startDownload(ctx, media.imageUrl, false, username, mediaId);
            case DOWNLOAD_VIDEO -> startDownload(ctx, media.videoUrl, true, username, mediaId);
            case ASK -> showStoryFormatDialog(ctx, media, username, mediaId);
            case NOT_FOUND -> Toast.makeText(ctx,
                    I18n.t(ctx, R.string.ig_toast_story_url_not_found), Toast.LENGTH_SHORT).show();
        }
    }

    private void showStoryFormatDialog(Context ctx, StoryMediaOptions media,
                                       String username, String mediaId) {
        List<CharSequence> labels = new ArrayList<>();
        List<StoryMedia> choices = new ArrayList<>();

        // Photo first: this is the requested path for photo stories carrying music.
        if (media.imageUrl != null) {
            labels.add(I18n.t(ctx, R.string.ig_story_download_photo));
            choices.add(new StoryMedia(media.imageUrl, false));
        }
        if (media.videoUrl != null) {
            labels.add(I18n.t(ctx, R.string.ig_story_download_video_music));
            choices.add(new StoryMedia(media.videoUrl, true));
        }

        try {
            float dp = ctx.getResources().getDisplayMetrics().density;
            boolean dk = (ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

            int sheetBg   = dk ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
            int cardBg    = dk ? Color.parseColor("#2C2C2E") : Color.parseColor("#FFFFFF");
            int textPrim  = dk ? Color.WHITE                 : Color.parseColor("#1C1C1E");
            int textSec   = dk ? Color.parseColor("#AEAEB2") : Color.parseColor("#6C6C70");
            int handleClr = dk ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

            LinearLayout sheet = new LinearLayout(ctx);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setBackground(roundRect(sheetBg, 20, ctx, dp));
            int hPad = (int) (20 * dp);
            sheet.setPadding(hPad, (int) (12 * dp), hPad, (int) (28 * dp));

            // Grab handle
            View handle = new View(ctx);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams((int) (40 * dp), (int) (4 * dp));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = (int) (16 * dp);
            handle.setLayoutParams(handleLp);
            handle.setBackground(roundRect(handleClr, 2, ctx, dp));
            sheet.addView(handle);

            // Title
            TextView title = new TextView(ctx);
            title.setText(I18n.t(ctx, R.string.ig_story_download_choice_title));
            title.setTextColor(textPrim);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setTypeface(null, Typeface.BOLD);
            sheet.addView(title);

            // Subtitle
            TextView subtitle = new TextView(ctx);
            subtitle.setText(I18n.t(ctx, R.string.ig_story_download_choice_subtitle));
            subtitle.setTextColor(textSec);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = (int) (2 * dp);
            subLp.bottomMargin = (int) (14 * dp);
            subtitle.setLayoutParams(subLp);
            sheet.addView(subtitle);

            final Dialog dialog = new Dialog(ctx);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            // One tappable card row per available format (📷 photo / 🎬 video with music).
            for (int i = 0; i < choices.size(); i++) {
                final StoryMedia choice = choices.get(i);
                TextView row = new TextView(ctx);
                row.setText((choice.video ? "🎬  " : "📷  ") + labels.get(i));
                row.setTextColor(textPrim);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.setTypeface(null, Typeface.BOLD);
                int rowPad = (int) (16 * dp);
                row.setPadding(rowPad, rowPad, rowPad, rowPad);
                row.setBackground(roundRect(cardBg, 12, ctx, dp));
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = (int) (8 * dp);
                row.setLayoutParams(rowLp);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    startDownload(ctx, choice.url, choice.video, username, mediaId);
                });
                sheet.addView(row);
            }

            // Cancel pill
            Button cancel = makePillButton(ctx, ctx.getString(android.R.string.cancel), cardBg, textPrim, dp);
            cancel.setOnClickListener(v -> dialog.dismiss());
            sheet.addView(cancel);

            dialog.setContentView(sheet);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setGravity(Gravity.BOTTOM);
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                WindowManager.LayoutParams wlp = w.getAttributes();
                int margin = (int) (12 * dp);
                wlp.x = margin;
                wlp.y = margin;
                w.setAttributes(wlp);
            }
            dialog.show();
        } catch (Throwable t) {
            ModuleLog.line("(IE|Story) format dialog failed: " + t);
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed,
                    t.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /** Rounded-rect drawable (matches the story-mention sheet styling). */
    private static GradientDrawable roundRect(int color, float radiusDp, Context ctx, float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * dp);
        return d;
    }

    /** Pill button (matches the story-mention sheet styling). */
    private static Button makePillButton(Context ctx, String label, int bgColor, int textColor, float dp) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackground(roundRect(bgColor, 14, ctx, dp));
        btn.setAllCaps(false);
        btn.setPadding((int) (20 * dp), (int) (14 * dp), (int) (20 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * dp);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void startDownload(Context ctx, String url, boolean isVideo,
                               String username, String mediaId) {
        String fn = FeedVideoDownloadHook.buildFilename(username, "story", mediaId, isVideo);
        ModuleLog.line("(IE|Story|DL) username=" + username + " mediaId=" + mediaId
                + " file=" + fn);
        Toast.makeText(ctx, isVideo ? I18n.t(ctx, R.string.ig_toast_downloading_story_video) : I18n.t(ctx, R.string.ig_toast_downloading_story_photo), Toast.LENGTH_SHORT).show();
        mainHandler.post(() -> new Thread(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVideo, username);
                if (!delegated) {
                    mainHandler.post(() -> Toast.makeText(ctx,
                            I18n.t(ctx, R.string.ig_toast_story_saved), Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable e) {
                mainHandler.post(() -> Toast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start());
    }

    private static void downloadToStream(String url, java.io.OutputStream out) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
        conn.connect();
        try (java.io.InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { conn.disconnect(); }
    }
}
