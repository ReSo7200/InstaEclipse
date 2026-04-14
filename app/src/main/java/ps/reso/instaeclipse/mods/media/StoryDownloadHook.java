package ps.reso.instaeclipse.mods.media;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

public class StoryDownloadHook {



    // VideoVersionIntf resolved once at install time — same interface used by feed downloader
    private static Class<?> videoVersionIntfClass;
    private static Method   videoVersionGetUrl;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Username + media ID resolved at download trigger time
    private volatile String currentStoryUsername = null;
    private volatile String currentStoryMediaId  = null;

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            videoVersionIntfClass = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf");
            videoVersionGetUrl    = videoVersionIntfClass.getMethod("getUrl");
        } catch (Throwable ignored) {}

        installButtonInjectorHook(bridge, classLoader);
        installClickHandlerHook(bridge, classLoader);
    }

    // ── Hook 1: inject "Download" into the story options button list ──────────
    //
    // Found via "[INTERNAL] Pause Playback" string + CharSequence[] return type, 1 param.
    // afterHookedMethod: appends our "Download" entry to the returned CharSequence[] array.

    private void installButtonInjectorHook(DexKitBridge bridge, ClassLoader classLoader) {
        Method method = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethod("StoryDownload_button", classLoader) : null;

        if (method == null) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("[INTERNAL] Pause Playback")
                                .paramCount(1)));

                if (methods.isEmpty()) {
                    XposedBridge.log("(IE|Story) ❌ Button builder method not found");
                    return;
                }

                for (MethodData md : methods) {
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (m.getReturnType().isArray() &&
                                CharSequence.class.isAssignableFrom(m.getReturnType().getComponentType())) {
                            method = m;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                XposedBridge.log("(IE|Story) ❌ Button builder DexKit: " + t);
                return;
            }
        }

        if (method == null) {
            XposedBridge.log("(IE|Story) ❌ No CharSequence[] return type candidate found");
            return;
        }
        DexKitCache.saveMethod("StoryDownload_button", method);

        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableStoryDownload) return;
                    CharSequence[] original = (CharSequence[]) param.getResult();
                    if (original == null) return;

                    // Guard: don't inject twice
                    String dlLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);
                    for (CharSequence cs : original) {
                        if (dlLabel.contentEquals(cs)) return;
                    }

                    CharSequence[] extended = new CharSequence[original.length + 1];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    extended[original.length] = dlLabel;
                    param.setResult(extended);
                }
            });

        } catch (Throwable t) {
            XposedBridge.log("(IE|Story) ❌ Button builder hook: " + t);
        }
    }

    // ── Hook 2: handle click on our "Download" option ────────────────────────
    //
    // Found via "explore_viewer" + "friendships/mute_friend_reel/%s/" strings.
    // beforeHookedMethod: reads the CharSequence param (the tapped label); if it
    // equals "Download", triggers the download. Context and ReelItem are resolved
    // from fields on 'this' or same-class params.

    private void installClickHandlerHook(DexKitBridge bridge, ClassLoader classLoader) {
        Method method = DexKitCache.isCacheValid()
                ? DexKitCache.loadMethod("StoryDownload_click", classLoader) : null;

        if (method == null) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .usingStrings("explore_viewer",
                                        "friendships/mute_friend_reel/%s/",
                                        "[INTERNAL] Pause Playback")));

                if (methods.isEmpty()) {
                    XposedBridge.log("(IE|Story) ❌ Click handler not found");
                    return;
                }
                method = methods.get(0).getMethodInstance(classLoader);
                DexKitCache.saveMethod("StoryDownload_click", method);
            } catch (Throwable t) {
                XposedBridge.log("(IE|Story) ❌ Click handler DexKit: " + t);
                return;
            }
        }

        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {

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

                    // 3. Locate the object that holds ReelItem — it is either 'this' or a
                    //    parameter of the same declaring class (piko's smali shows the latter).
                    Object holder = findReelItemHolder(param);
                    XposedBridge.log("(IE|Story) holder=" + (holder != null ? holder.getClass().getName() : "null"));

                    // 4. Extract the Context
                    Context ctx = findContext(holder != null ? holder : param.thisObject);
                    if (ctx == null) {
                        XposedBridge.log("(IE|Story) ❌ Context not found");
                        return;
                    }

                    // 5. Extract story URL via ReelItem → media object field graph
                    String url = extractStoryUrl(holder != null ? holder : param.thisObject);
                    XposedBridge.log("(IE|Story) url=" + url);

                    if (url == null || url.isEmpty()) {
                        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_story_url_not_found), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Object effectiveHolder = holder != null ? holder : param.thisObject;
                    currentStoryUsername = extractUsernameFromReelItemHolder(effectiveHolder);
                    currentStoryMediaId  = extractMediaIdFromReelItemHolder(effectiveHolder);
                    startDownload(ctx, url, isVideoUrl(url));
                }
            });

            FeatureStatusTracker.setHooked("StoryDownload");

        } catch (Throwable t) {
            XposedBridge.log("(IE|Story) ❌ Click handler hook: " + t);
        }
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
     * Extracts the story media URL from the holder object.
     *   1. Reads the ReelItem field from the holder.
     *   2. Searches for VideoVersionIntf → video URL (videos).
     *   3. Searches for image Candidate objects (CDN URL + width int + height int) and
     *      picks the one with the largest pixel area (photos).
     *   4. Falls back to raw CDN string scan with area-based ranking.
     */
    private static String extractStoryUrl(Object holder) {
        if (holder == null) return null;
        try {
            Object reelItem = readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem");
            XposedBridge.log("(IE|Story) reelItem=" +
                    (reelItem != null ? reelItem.getClass().getName() : "null"));

            Object target = reelItem != null ? reelItem : holder;

            // Try video URL via VideoVersionIntf scan
            if (videoVersionIntfClass != null && videoVersionGetUrl != null) {
                String videoUrl = findVideoUrl(target,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                if (videoUrl != null) return videoUrl;
            }

            // For photo stories: walk the graph looking for image Candidate objects.
            // A Candidate has a CDN URL string field + at least two int fields with
            // plausible pixel dimensions. Field names are obfuscated so we match by type
            // and value range. Pick the candidate with the largest width×height area.
            List<CandidateInfo> candidates = new ArrayList<>();
            collectImageCandidates(target, candidates,
                    Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            XposedBridge.log("(IE|Story) imageCandidates=" + candidates.size());
            if (!candidates.isEmpty()) {
                candidates.sort((a, b) -> Integer.compare(b.area, a.area));
                XposedBridge.log("(IE|Story) bestCandidate area=" + candidates.get(0).area
                        + " url=" + candidates.get(0).url.substring(0, Math.min(80, candidates.get(0).url.length())));
                return candidates.get(0).url;
            }

            // Last resort: raw CDN string scan
            List<String> cdnUrls = new ArrayList<>();
            scanCdnUrls(target, cdnUrls, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (!cdnUrls.isEmpty()) return pickBestUrl(cdnUrls);

        } catch (Throwable t) {
            XposedBridge.log("(IE|Story) extractStoryUrl error: " + t);
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
                if (url != null && isCdnUrl(url)) return url;
            } catch (Throwable ignored) {}
        }

        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.") && !cn.startsWith("com.facebook."))
            return null;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;
                    if (val instanceof List<?> list) {
                        for (Object elem : list) {
                            if (videoVersionIntfClass.isInstance(elem)) {
                                try {
                                    String url = (String) videoVersionGetUrl.invoke(elem);
                                    if (url != null && isCdnUrl(url)) return url;
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
        return url.contains("t50.") || url.contains("/o1/");
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
            XposedBridge.log("(IE|Story|Username) holder is null");
            return null;
        }
        XposedBridge.log("(IE|Story|Username) searching in holder=" + holder.getClass().getName());
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
                            XposedBridge.log("(IE|Story|Username) found ReelItem in field="
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
                XposedBridge.log("(IE|Story|Username) holder is itself a ReelItem");
            }
            if (reelItem == null) {
                XposedBridge.log("(IE|Story|Username) ❌ ReelItem not found in holder");
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
                            XposedBridge.log("(IE|Story|Username) reelItem." + m.getName() + "() [User] → " + username);
                            return username;
                        }
                        continue;
                    }

                    // Media object — delegate to feed extractor (has LiveTreeMediaDict path)
                    if (candidateClass.equals("com.instagram.feed.media.Media")) {
                        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(candidate);
                        if (username != null) {
                            XposedBridge.log("(IE|Story|Username) reelItem." + m.getName()
                                    + "() [Media] → " + username);
                            return username;
                        }
                        continue; // don't probe String methods on Media
                    }
                } catch (Throwable ignored) {}
            }

            XposedBridge.log("(IE|Story|Username) ❌ username not found on ReelItem methods");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Story|Username) ❌ Exception: " + t);
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

    private void startDownload(Context ctx, String url, boolean isVideo) {
        String fn = FeedVideoDownloadHook.buildFilename(currentStoryUsername, "story", currentStoryMediaId, isVideo);
        XposedBridge.log("(IE|Story|DL) username=" + currentStoryUsername + " mediaId=" + currentStoryMediaId
                + " file=" + fn);
        Toast.makeText(ctx, isVideo ? I18n.t(ctx, R.string.ig_toast_downloading_story_video) : I18n.t(ctx, R.string.ig_toast_downloading_story_photo), Toast.LENGTH_SHORT).show();
        mainHandler.post(() -> new Thread(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVideo, currentStoryUsername);
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
