package ps.reso.instaeclipse.mods.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import java.util.LinkedHashSet;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
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

    // ── Highlight "Download all" (#250) ─────────────────────────────────────
    // Reel model = class using "Reel with pending media should have an owner." (X.04aX on 452,
    // X.04ey on 451). A highlight's reel has a String id starting with "highlight:"; its
    // (UserSession) -> List methods return the ReelItems (the whole highlight is fetched by
    // feed/reels_media/ when it opens).
    private static Class<?> reelClass;

    private static Object findHighlightReel(Object root) {
        if (reelClass == null || root == null) return null;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<>();
        q.add(new Object[]{root, 0});
        while (!q.isEmpty() && seen.size() < 2000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (reelClass.isInstance(o)) {
                if (isHighlight(o)) return o;
                continue;
            }
            if (d >= 4) continue;
            if (o instanceof android.app.Activity || o instanceof android.view.View || o instanceof android.content.Context
                    || o instanceof android.content.res.Resources || o.getClass().getName().startsWith("androidx.")) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.") && !n.startsWith("instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                }
        }
        return null;
    }

    private static Object highlightFromParam(XC_MethodHook.MethodHookParam param) {
        Object holder = findReelItemHolder(param);
        Object r = findHighlightReel(holder);
        if (r == null) r = findHighlightReel(param.thisObject);
        if (r == null && param.args != null) for (Object a : param.args) { r = findHighlightReel(a); if (r != null) break; }
        return r;
    }

    private static boolean isHighlight(Object reel) {
        for (Class<?> k = reel.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(reel);
                    if (v instanceof String str && str.startsWith("highlight:")) return true;
                } catch (Throwable ignored) {}
            }
        return false;
    }

    private static List<?> highlightItems(Object reel, Object holder) {
        return highlightItemsWith(reel, readFieldByTypeName(holder, "com.instagram.common.session.UserSession"));
    }

    private static List<?> highlightItemsWith(Object reel, Object session) {
        List<?> best = null;
        for (Method m : reel.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || !List.class.isAssignableFrom(m.getReturnType())
                    || !p[0].getName().equals("com.instagram.common.session.UserSession")) continue;
            try {
                Object r = m.invoke(reel, session);
                if (r instanceof List<?> l && (best == null || l.size() > best.size())) best = l;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static void downloadHighlight(Context ctx, Object reel, Object holder) {
        downloadHighlightItems(ctx, highlightItems(reel, holder));
    }

    private static void downloadHighlightItems(Context ctx, List<?> items) {
        if (items == null || items.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Object> copy = new ArrayList<>(items);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_highlight_downloading, copy.size()), Toast.LENGTH_LONG).show();
        ModuleLog.line("(IE|Highlight) downloading " + copy.size() + " items");
        new Thread(() -> {
            int ok = 0;
            for (Object item : copy) {
                try {
                    StoryMediaOptions m = extractStoryMediaOptions(ctx, item, true);
                    if (m == null) continue;
                    boolean video = m.videoUrl != null;
                    String url = video ? m.videoUrl : m.imageUrl;
                    String user = extractUsernameFromReelItemHolder(item);
                    String id = extractMediaIdFromReelItemHolder(item);
                    String fn = FeedVideoDownloadHook.buildFilename(user, "highlight", id, video);
                    FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, video, user);
                    ok++;
                    Thread.sleep(400);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Highlight) item: " + t);
                }
            }
            final int done = ok;
            mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_highlight_done, done, copy.size()), Toast.LENGTH_LONG).show());
        }).start();
    }

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        SelectedMediaAdapter.install(bridge, classLoader);
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Reel with pending media should have an owner.")))) {
                reelClass = classLoader.loadClass(md.getClassName());
                break;
            }
            ModuleLog.line("(IE|Highlight) reel class " + (reelClass != null ? reelClass.getName() : "not found"));
        } catch (Throwable t) {
            ModuleLog.line("(IE|Highlight) " + t);
        }
        try {
            videoVersionIntfClass = classLoader.loadClass("com.instagram.model.mediasize.VideoVersionIntf");
            videoVersionGetUrl    = videoVersionIntfClass.getMethod("getUrl");
        } catch (Throwable ignored) {}

        installButtonInjectorHook(bridge, classLoader);
        installHighlightButtonInjectorHook(bridge, classLoader);
        installClickHandlerHook(bridge, classLoader);
        installHighlightTrayHook(bridge, classLoader);
        resolveExpiringGetter(bridge, classLoader);
        installStoryCaptureHook(bridge, classLoader);
    }

    // ── Highlight long-press menu (profile tray): "Download whole highlight" ──
    // Helper = class of the menu builder A0G(View, ?, ReelItem, Integer), the only method using
    // "HighlightsTrayDialogHelper" (X.0MGm on 452); it holds the Activity, UserSession and the highlight
    // reel. The builder shows either the legacy dialog (rows via add(String, OnClickListener), then a
    // no-arg -> List collects them) or the newer popup (List of rows passed to set(List); rows come from
    // the helper's static (rowIface, String) -> List factory, rowIface = boolean method + void click).
    private static final ThreadLocal<Object> trayHelper = new ThreadLocal<>();
    private static final Set<Object> trayDialogsDone = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private void installHighlightTrayHook(DexKitBridge bridge, ClassLoader cl) {
        try {
            MethodData builder = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("HighlightsTrayDialogHelper").paramCount(4)))) { builder = md; break; }
            if (builder == null) { ModuleLog.line("(IE|HighlightTray) builder not found"); return; }
            Method build = builder.getMethodInstance(cl);
            final Class<?> helper = build.getDeclaringClass();
            Method addRow = null, legacyItems = null, popupSet = null, rowFactory = null;
            for (MethodData inv : builder.getInvokes()) {
                try {
                    if (inv.isConstructor()) continue;
                    Method m = inv.getMethodInstance(cl);
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == String.class && p[1] == View.OnClickListener.class && m.getReturnType() == void.class) addRow = m;
                    else if (p.length == 1 && p[0] == List.class && m.getReturnType() == void.class && m.getDeclaringClass().getName().startsWith("X.")) popupSet = m;
                    else if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass() == helper && p.length == 2
                            && p[0].isInterface() && p[1] == String.class && m.getReturnType() == List.class) rowFactory = m;
                } catch (Throwable ignored) {}
            }
            if (addRow != null)
                for (MethodData inv : builder.getInvokes()) {
                    try {
                        if (inv.isConstructor() || !inv.getClassName().equals(addRow.getDeclaringClass().getName())) continue;
                        Method m = inv.getMethodInstance(cl);
                        if (m.getParameterCount() == 0 && m.getReturnType() == List.class) legacyItems = m;
                    } catch (Throwable ignored) {}
                }
            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    if (FeatureFlags.enableStoryDownload) trayHelper.set(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method); trayHelper.remove(); }
            });
            if (addRow != null && legacyItems != null) {
                final Method add = addRow;
                XposedBridge.hookMethod(legacyItems, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        Object h = trayHelper.get();
                        if (h == null || !trayDialogsDone.add(p.thisObject)) return;
                        try {
                            Context c = AndroidAppHelper.currentApplication();
                            add.invoke(p.thisObject, I18n.t(c, R.string.ig_highlight_download_all), (View.OnClickListener) v -> trayDownload(h));
                        } catch (Throwable t) { ModuleLog.line("(IE|HighlightTray) legacy row: " + t); }
                    }
                });
            }
            if (popupSet != null && rowFactory != null) {
                final Method factory = rowFactory;
                final Class<?> iface = rowFactory.getParameterTypes()[0];
                XposedBridge.hookMethod(popupSet, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        Object h = trayHelper.get();
                        if (h == null || !(p.args[0] instanceof List<?>)) return;
                        try {
                            Object click = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{iface}, (proxy, m, a) -> {
                                if (m.getReturnType() == boolean.class) return true;
                                if (m.getReturnType() == void.class && m.getDeclaringClass() == iface) { mainHandler.post(() -> trayDownload(h)); return null; }
                                if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                if (m.getName().equals("equals")) return proxy == a[0];
                                if (m.getName().equals("toString")) return "IEHighlightRow";
                                return null;
                            });
                            List<?> rows = (List<?>) factory.invoke(null, click, I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_highlight_download_all));
                            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) p.args[0];
                            try { list.addAll(rows); }
                            catch (UnsupportedOperationException e) { List<Object> copy = new ArrayList<>(list); copy.addAll(rows); p.args[0] = copy; }
                        } catch (Throwable t) { ModuleLog.line("(IE|HighlightTray) popup row: " + t); }
                    }
                });
            }
            findReelLoader(bridge, cl);
            ModuleLog.line("(IE|HighlightTray) ✅ " + helper.getName() + " legacy=" + (addRow != null && legacyItems != null)
                    + " popup=" + (popupSet != null && rowFactory != null) + " loader=" + (loaderGetter != null && loaderFetch != null));
        } catch (Throwable t) {
            ModuleLog.line("(IE|HighlightTray) " + t);
        }
    }

    /** Long-press row tapped: download every item of the highlight the helper was built for. */
    private static void trayDownload(Object helper) {
        Object reel = reelClass != null ? readFieldByTypeName(helper, reelClass.getName()) : null;
        Object session = readFieldByTypeName(helper, "com.instagram.common.session.UserSession");
        Context ctx = (Context) readFieldByTypeName(helper, "android.app.Activity");
        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (ctx == null || reel == null) { ModuleLog.line("(IE|HighlightTray) no reel/context"); return; }
        List<?> items = highlightItemsWith(reel, session);
        ModuleLog.line("(IE|HighlightTray) items loaded: " + (items != null ? items.size() : -1));
        if (items != null && !items.isEmpty()) { downloadHighlightItems(ctx, items); return; }
        loadThenDownload(ctx, reel, session); // highlight never opened: only its cover is known
    }

    // Reel loader = first ctor param of the class using "reel_store_on_story_unavailable" (X.02jU on
    // 452), obtained from a static (UserSession) -> loader getter. register(callback, reelId, String,
    // boolean) calls back at once when the reel is already loaded, else when fetch(reason enum, reelId,
    // module, Map) completes; callback = success(String id, boolean) / failure(String id). The loader
    // only keeps a WeakReference to the callback, so a strong one is held here until it fires.
    private static Method loaderGetter, loaderRegister, loaderUnregister, loaderFetch;
    private static final Set<Object> pendingLoads = Collections.synchronizedSet(new java.util.HashSet<>());

    private static void findReelLoader(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> loader = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("reel_store_on_story_unavailable")))) {
                for (java.lang.reflect.Constructor<?> c : cl.loadClass(md.getClassName()).getDeclaredConstructors())
                    if (c.getParameterCount() > 0 && c.getParameterTypes()[0].getName().startsWith("X.")) { loader = c.getParameterTypes()[0]; break; }
                if (loader != null) break;
            }
            if (loader == null) return;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType(loader.getName()).paramTypes("com.instagram.common.session.UserSession")))) {
                Method m = md.getMethodInstance(cl);
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) { m.setAccessible(true); loaderGetter = m; break; }
            }
            for (Method m : loader.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4 && p[0].isInterface() && p[1] == String.class && p[2] == String.class && p[3] == boolean.class) loaderRegister = m;
                else if (p.length == 4 && p[0].isEnum() && p[1] == String.class && p[2] == String.class && p[3] == java.util.Map.class) loaderFetch = m;
            }
            if (loaderRegister != null)
                for (Method m : loader.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == loaderRegister.getParameterTypes()[0] && p[1] == String.class) loaderUnregister = m;
                }
        } catch (Throwable t) {
            ModuleLog.line("(IE|HighlightTray) loader: " + t);
        }
    }

    private static String highlightId(Object reel) {
        for (Class<?> k = reel.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); if (f.get(reel) instanceof String s && s.startsWith("highlight:")) return s; } catch (Throwable ignored) {}
            }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void loadThenDownload(Context ctx, Object reel, Object session) {
        String id = highlightId(reel);
        if (id == null || session == null || loaderGetter == null || loaderRegister == null || loaderFetch == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Object loader = loaderGetter.invoke(null, session);
            Class<?> cbType = loaderRegister.getParameterTypes()[0];
            Object[] self = new Object[1];
            Object cb = java.lang.reflect.Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType}, (proxy, m, a) -> {
                switch (m.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == a[0];
                    case "toString": return "IEHighlightLoad";
                }
                if (a == null || a.length == 0 || !id.equals(a[0])) return null;
                boolean success = a.length == 2;
                mainHandler.post(() -> {
                    if (!pendingLoads.remove(self[0])) return; // fire once
                    try { if (loaderUnregister != null) loaderUnregister.invoke(loader, self[0], id); } catch (Throwable ignored) {}
                    List<?> items = success ? highlightItemsWith(reel, session) : null;
                    ModuleLog.line("(IE|HighlightTray) load " + (success ? "ok" : "failed") + ": " + (items != null ? items.size() : -1) + " items");
                    downloadHighlightItems(ctx, items);
                });
                return null;
            });
            self[0] = cb;
            pendingLoads.add(cb);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_highlight_loading), Toast.LENGTH_SHORT).show();
            loaderRegister.invoke(loader, cb, id, "profile", false);
            if (!pendingLoads.contains(cb)) return; // was already loaded: callback ran
            Class<? extends Enum> reason = (Class<? extends Enum>) loaderFetch.getParameterTypes()[0];
            Object why;
            try { why = Enum.valueOf(reason, "PROFILE_HIGHLIGHTS_TRAY"); } catch (Throwable e) { why = Enum.valueOf(reason, "ON_TAP"); }
            loaderFetch.invoke(loader, why, id, "profile", null);
            ModuleLog.line("(IE|HighlightTray) fetching " + id);
            mainHandler.postDelayed(() -> { // give up after 20 s
                if (pendingLoads.remove(cb)) ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            }, 20000);
        } catch (Throwable t) {
            ModuleLog.line("(IE|HighlightTray) load: " + t);
        }
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
            @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
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

    /**
     * Every CharSequence[] option builder of the story-options helper class (X.0mt9 on 452).
     * IG 452 moved the "[INTERNAL] …" rows into a private List helper (A0c), so the builder used
     * for other people's stories (A0t) no longer contains the anchor string itself; taking all
     * CharSequence[] methods of the anchor's declaring class(es) catches it again.
     */
    /**
     * Every story-menu tap dispatcher: the void methods behind "[INTERNAL] Pause Playback". Others'
     * stories and your own story use different ones (your own is a static helper), so features that
     * add menu rows hook them all and check the tapped label themselves.
     */
    public static java.util.List<Method> storyClickDispatchers(DexKitBridge bridge, ClassLoader cl) {
        java.util.List<Method> out = ps.reso.instaeclipse.utils.core.DexKitCache.isCacheValid()
                ? ps.reso.instaeclipse.utils.core.DexKitCache.loadMethods("StoryClickDispatchers", cl) : null;
        if (out != null && !out.isEmpty()) return out;
        out = new java.util.ArrayList<>();
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("void").usingStrings("[INTERNAL] Pause Playback")))) {
                try { out.add(md.getMethodInstance(cl)); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Story) ❌ click dispatchers: " + t);
        }
        if (!out.isEmpty()) ps.reso.instaeclipse.utils.core.DexKitCache.saveMethods("StoryClickDispatchers", out);
        return out;
    }

    public static java.util.Set<Method> storyOptionBuilders(List<MethodData> anchorMethods, ClassLoader classLoader) {
        java.util.Set<Method> out = new java.util.LinkedHashSet<>();
        java.util.Set<Class<?>> owners = new java.util.LinkedHashSet<>();
        for (MethodData md : anchorMethods) {
            try {
                Method m = md.getMethodInstance(classLoader);
                owners.add(m.getDeclaringClass());
                if (isCharSequenceArray(m.getReturnType())) out.add(m);
            } catch (Throwable ignored) {}
        }
        for (Class<?> c : owners) {
            for (Method m : c.getDeclaredMethods()) {
                if (isCharSequenceArray(m.getReturnType())) out.add(m);
            }
        }
        return out;
    }

    private static boolean isCharSequenceArray(Class<?> rt) {
        return rt.isArray() && CharSequence.class.isAssignableFrom(rt.getComponentType());
    }

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
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.enableStoryDownload && !FeatureFlags.isGhostStory && !FeatureFlags.offerPlaybackSpeed) return;
                    if (!(param.getResult() instanceof CharSequence[] original) || original == null) return;

                    java.util.List<CharSequence> add = new java.util.ArrayList<>();
                    if (FeatureFlags.enableStoryDownload)
                        add.add(I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title));
                    // Selective "Mark as seen" (#226): only meaningful while Hide Story Views is on.
                    // Not on your own story (its menu comes from the static builder): you can't
                    // "see" your own story.
                    if (FeatureFlags.isGhostStory && !java.lang.reflect.Modifier.isStatic(param.method.getModifiers()))
                        add.add(I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_story_mark_seen));
                    // Highlight "Download all" (#250)
                    if (FeatureFlags.enableStoryDownload && highlightFromParam(param) != null)
                        add.add(I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_highlight_download_all));
                    // Story playback speed (Instagram Plus–style, our own implementation)
                    if (FeatureFlags.offerPlaybackSpeed)
                        add.add(I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_playback_speed_title));

                    // Guard: don't inject twice
                    for (CharSequence cs : original) {
                        if (cs == null) continue;
                        add.removeIf(a -> a.toString().contentEquals(cs));
                    }
                    if (add.isEmpty()) return;

                    CharSequence[] extended = new CharSequence[original.length + add.size()];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    for (int i = 0; i < add.size(); i++) extended[original.length + i] = add.get(i);
                    param.setResult(extended);
                }
            };

            int hooked = 0;
            for (Method m : storyOptionBuilders(methods, classLoader)) {
                try {
                    XposedBridge.hookMethod(m, injector);
                    hooked++;
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

    private static XC_MethodHook menuButtonInjector() {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableStoryDownload || param.hasThrowable()) return;
                Object original = param.getResult();
                String label = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);
                Object extended = StoryMenuOptions.appendDownload(original, label);
                if (extended != original) param.setResult(extended);
            }
        };
    }

    private void installHighlightButtonInjectorHook(DexKitBridge bridge, ClassLoader loader) {
        // The native Highlight sheet iterates two alternative ArrayList builders. Only one
        // carries the semantic anchor; discover its paired builder through that sheet's
        // call graph. Both must belong to the same exact ReelItem owner as the dispatcher.
        try {
            Class<?> itemType = loader.loadClass("com.instagram.model.reels.ReelItem");
            List<MethodData> anchors = new ArrayList<>();
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.util.ArrayList").usingStrings("story_highlight_action_sheet")))) {
                if (StoryMenuOptions.isOwnedListBuilder(data.getMethodInstance(loader), itemType)) {
                    anchors.add(data);
                }
            }
            if (anchors.size() != 1) {
                ModuleLog.line("(IE|Story) Highlight menu anchor unavailable or ambiguous");
                return;
            }
            MethodData anchor = anchors.get(0);
            String ownerName = anchor.getDeclaredClassName();
            Set<Method> builders = new LinkedHashSet<>();
            for (MethodData caller : anchor.getCallers()) {
                if (!ownerName.equals(caller.getDeclaredClassName())
                        || !"void".equals(caller.getReturnTypeName())) continue;
                for (MethodData invoked : caller.getInvokes()) {
                    if (!ownerName.equals(invoked.getDeclaredClassName())
                            || !"java.util.ArrayList".equals(invoked.getReturnTypeName())) continue;
                    Method method = invoked.getMethodInstance(loader);
                    if (StoryMenuOptions.isOwnedListBuilder(method, itemType)) builders.add(method);
                }
            }
            // No independent/global menu injection when the native sheet cannot be identified.
            if (!builders.contains(anchor.getMethodInstance(loader))) return;
            XC_MethodHook injector = menuButtonInjector();
            int hooked = 0;
            for (Method builder : builders) {
                XposedBridge.hookMethod(builder, injector);
                hooked++;
            }
            ModuleLog.line("(IE|Story) Highlight injector hooked " + hooked + " builder(s)");
        } catch (Throwable error) {
            ModuleLog.line("(IE|Story) Highlight menu binding failed: " + error.getClass().getSimpleName());
        }
    }


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
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.enableStoryDownload && !FeatureFlags.isGhostStory && !FeatureFlags.offerPlaybackSpeed) return;

                // 1. Find which button was tapped
                CharSequence tapped = null;
                for (Object arg : param.args) {
                    if (arg instanceof CharSequence cs) { tapped = cs; break; }
                }
                if (tapped == null) return;

                String allLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_highlight_download_all);
                if (FeatureFlags.enableStoryDownload && allLabel.contentEquals(tapped)) {
                    param.setResult(null);
                    Object holder = findReelItemHolder(param);
                    Object reel = highlightFromParam(param);
                    Context ctx = findContextAcrossParam(param, holder != null ? holder : param.thisObject);
                    if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                    if (ctx != null && reel != null) downloadHighlight(ctx, reel, holder);
                    return;
                }
                String speedLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_playback_speed_title);
                if (FeatureFlags.offerPlaybackSpeed && speedLabel.contentEquals(tapped)) {
                    param.setResult(null);
                    Object holder = findReelItemHolder(param);
                    Context ctx = findContextAcrossParam(param, holder != null ? holder : param.thisObject);
                    if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                    // IG paused the story when its menu opened and resumes it only for options it
                    // handles itself; we swallowed this tap, so replay the dispatcher with IG's own
                    // "[INTERNAL] Resume Playback" once our chooser closes.
                    final java.lang.reflect.Member method = param.method;
                    final Object self = param.thisObject;
                    final Object[] args = param.args.clone();
                    Runnable resume = () -> {
                        try {
                            for (int i = 0; i < args.length; i++)
                                if (args[i] instanceof CharSequence) { args[i] = "[INTERNAL] Resume Playback"; break; }
                            ps.reso.instaeclipse.xposedcompat.XposedBridge.invokeOriginalMethod(method, self, args);
                        } catch (Throwable t) {
                            ModuleLog.line("(IE|Story) resume after speed: " + t);
                        }
                    };
                    if (ctx != null) PlaybackSpeedHook.showChooser(ctx, resume);
                    return;
                }

                // Selective "Mark as seen" (#226)
                String seenLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_story_mark_seen);
                if (FeatureFlags.isGhostStory && seenLabel.contentEquals(tapped)) {
                    param.setResult(null);
                    Object holder = findReelItemHolder(param);
                    Object effective = holder != null ? holder : param.thisObject;
                    Context ctx = findContextAcrossParam(param, effective);
                    String id = extractMediaIdFromReelItemHolder(effective);
                    int n = ps.reso.instaeclipse.mods.ghost.GhostStorySeenHook.markSeen(id);
                    if (ctx != null) ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, n > 0
                            ? R.string.ig_story_marked_seen : R.string.ig_story_mark_seen_none), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!FeatureFlags.enableStoryDownload) return;
                String dlLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);
                if (!dlLabel.contentEquals(tapped)) return;

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

                try {
                    Object selected = SelectedMediaAdapter.findStoryMedia((Method) param.method,
                            param.thisObject, param.args);
                    SelectedMediaDownloads.Captured captured = SelectedMediaDownloads.capture(ctx, selected, "story");
                    SelectedMediaDownloads.showPost(ctx, captured);
                } catch (Throwable unavailable) { SelectedMediaDownloads.unavailable(ctx, unavailable); }

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

    /** The original Story selectors, restricted to the already resolved selected Media. */
    static String originalVideoFromSelected(Object media) {
        String video = FeedVideoDownloadHook.bestVideoUrlFromMedia(media);
        if (video == null) video = findVideoUrl(media,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (video != null) return video;
        List<String> urls = new ArrayList<>();
        scanCdnUrls(media, urls, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        for (String url : urls) if (FeedVideoDownloadHook.isVideoUrl(url)) return url;
        return null;
    }

    static String originalImageFromSelected(Context context, Object media) {
        String image = FeedVideoDownloadHook.imageUrlFromMedia(context, media);
        if (image != null && FeedVideoDownloadHook.isVideoUrl(image)) image = null;
        if (image != null) return image;
        List<CandidateInfo> candidates = new ArrayList<>();
        collectImageCandidates(media, candidates, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (!candidates.isEmpty()) {
            candidates.sort((a, b) -> Integer.compare(b.area, a.area));
            return candidates.get(0).url;
        }
        List<String> urls = new ArrayList<>();
        scanCdnUrls(media, urls, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        urls.removeIf(FeedVideoDownloadHook::isVideoUrl);
        return urls.isEmpty() ? null : pickBestUrl(urls);
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
        return extractStoryMediaOptions(ctx, holder, false);
    }

    /** {@code direct}: holder IS the ReelItem (don't look for a nested ReelItem field). */
    private static StoryMediaOptions extractStoryMediaOptions(Context ctx, Object holder, boolean direct) {
        if (holder == null) return null;
        try {
            Object reelItem = direct ? holder : readFieldByTypeName(holder, "com.instagram.model.reels.ReelItem");
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
    /** {url, area} of the largest image candidate reachable from {@code media}, or null. */
    static Object[] largestImage(Object media) {
        List<CandidateInfo> candidates = new ArrayList<>();
        collectImageCandidates(media, candidates, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        CandidateInfo best = null;
        for (CandidateInfo c : candidates) if (best == null || c.area > best.area) best = c;
        return best == null ? null : new Object[]{best.url, best.area};
    }

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
            case NOT_FOUND -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
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
            CharSequence[] rows = new CharSequence[choices.size()];
            // One tappable row per available format (📷 photo / 🎬 video with music).
            for (int i = 0; i < choices.size(); i++)
                rows[i] = (choices.get(i).video ? "🎬  " : "📷  ") + labels.get(i);
            IeDialog dlg = new IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ig_story_download_choice_title))
                    .message(I18n.t(ctx, R.string.ig_story_download_choice_subtitle))
                    .choices(rows, -1, which -> {
                        StoryMedia choice = choices.get(which);
                        startDownload(ctx, choice.url, choice.video, username, mediaId);
                    });
            dlg.secondary(ctx.getString(android.R.string.cancel), null);
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line("(IE|Story) format dialog failed: " + t);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed,
                    t.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void startDownload(Context ctx, String url, boolean isVideo,
                               String username, String mediaId) {
        String fn = FeedVideoDownloadHook.buildFilename(username, "story", mediaId, isVideo);
        ModuleLog.line("(IE|Story|DL) username=" + username + " mediaId=" + mediaId
                + " file=" + fn);
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, isVideo ? I18n.t(ctx, R.string.ig_toast_downloading_story_video) : I18n.t(ctx, R.string.ig_toast_downloading_story_photo), Toast.LENGTH_SHORT).show();
        mainHandler.post(() -> new Thread(() -> {
            try {
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVideo, username);
                if (!delegated) {
                    mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                            I18n.t(ctx, R.string.ig_toast_story_saved), Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable e) {
                mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx,
                        I18n.t(ctx, R.string.ig_toast_download_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        }).start());
    }

    private static void downloadToStream(String url, java.io.OutputStream out) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        MediaHttp.applyTimeouts(conn);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
        conn.connect();
        try (java.io.InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { conn.disconnect(); }
    }
}
