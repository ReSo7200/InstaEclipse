package ps.reso.instaeclipse.mods.media;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Save a received Instant (#184). "Instants" is internally `quicksnap`, and its viewer is Jetpack
 * Compose (so no button can be injected). An Instant item is the model whose Media lives in field
 * A01; the module's existing downloader understands that Media directly.
 *
 * Two literal-named Compose classes (stable across obfuscation — Kotlin package names survive):
 *  - QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$16$1$8$1 — the double-tap coroutine
 *    for the CURRENT card; its constructor receives the current Instant item as arg[6]. We hook
 *    the ctor (after) and cache that item's Media.
 *  - PointerTouchKt$handleTapAndLongPressWithRelease$2$1$1$1 — the viewer's tap/long-press pointer
 *    handler (package com.instagram.quicksnap.viewer.compose, so it only fires inside the Instant
 *    viewer). Its constructor stores the onLongPress callback in field A09 (a kotlin Function0).
 *    We wrap that Function0 so a long-press saves the cached Instant, then still runs IG's original.
 *
 * The item/Media field names (A01/A06/A09) are the obfuscated field letters read reflectively;
 * we do NOT hardcode any obfuscated CLASS name — the two hooked classes are literal-named.
 */
public class InstantSaveHook {

    private static final String VIEWER_LAMBDA =
            "com.instagram.quicksnap.viewer.compose.QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$16$1$8$1";
    private static final String LONGPRESS_LAMBDA =
            "com.instagram.quicksnap.viewer.compose.PointerTouchKt$handleTapAndLongPressWithRelease$2$1$1$1";

    // The current on-screen Instant's Media (com.instagram.feed.media.Media), cached at bind time.
    private static volatile Object currentMedia;

    public void install(ClassLoader cl) {
        cacheCurrentInstant(cl);
        wrapLongPress(cl);
    }

    /** Hook the current-card coroutine ctor and cache arg[6]'s Media (field A01). */
    private void cacheCurrentInstant(ClassLoader cl) {
        try {
            Class<?> lambda = XposedHelpers.findClass(VIEWER_LAMBDA, cl);
            for (java.lang.reflect.Constructor<?> c : lambda.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object media = findInstantMedia(p.args);
                            if (media != null) currentMedia = media;
                        } catch (Throwable ignored) {}
                    }
                });
            }
            ModuleLog.line("(IE|InstantSave) ✅ viewer cache hooked");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantSave) ⚠️ viewer cache: " + t.getMessage());
        }
    }

    /**
     * Finds the current Instant's Media among the ctor args by FIELD TYPE, not by the obfuscated
     * field letter — the arg is the Instant item, and its Media lives in the (only) field whose
     * type is com.instagram.feed.media.Media. This survives field-letter/arg-order drift across
     * versions (nothing here is hardcoded to "A01"/index 6).
     */
    private static Object findInstantMedia(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            String cn = a.getClass().getName();
            if (cn.startsWith("java.") || cn.startsWith("android") || cn.startsWith("kotlin")) continue;
            for (java.lang.reflect.Field f : a.getClass().getDeclaredFields()) {
                if (!"com.instagram.feed.media.Media".equals(f.getType().getName())) continue;
                try {
                    f.setAccessible(true);
                    Object media = f.get(a);
                    if (media != null) return media;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // Debounce so both preloaded viewer instances (and any duplicate invoke) don't double-save.
    private static volatile long lastSaveMs = 0;

    /**
     * Hook the viewer pointer handler and wrap its onLongPress callback (field A0A — verified
     * on-device: A0A fires exactly once per gesture at the long-press threshold, while A0B is the
     * ordinary press/tap callback that fires repeatedly). On long-press we save the cached Instant,
     * then still run IG's original callback so its own behaviour (pause, etc.) is preserved.
     */
    private void wrapLongPress(ClassLoader cl) {
        try {
            final Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl);
            Class<?> handler = XposedHelpers.findClass(LONGPRESS_LAMBDA, cl);
            for (java.lang.reflect.Constructor<?> c : handler.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            final Object orig = XposedHelpers.getObjectField(p.thisObject, "A0A");
                            if (orig == null || !function1.isInstance(orig)) return;
                            if (orig.getClass().getName().startsWith("$Proxy")) return; // already wrapped
                            Object wrapper = Proxy.newProxyInstance(cl, new Class[]{function1}, (proxy, method, a) -> {
                                if ("invoke".equals(method.getName()) && FeatureFlags.saveInstants) {
                                    long now = android.os.SystemClock.uptimeMillis();
                                    if (now - lastSaveMs > 1000) { lastSaveMs = now; saveCurrent(); }
                                }
                                return orig != null ? method.invoke(orig, a) : null;
                            });
                            XposedHelpers.setObjectField(p.thisObject, "A0A", wrapper);
                        } catch (Throwable ignored) {}
                    }
                });
            }
            FeatureStatusTracker.setHooked("SaveInstants");
            ModuleLog.line("(IE|InstantSave) ✅ long-press hooked");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantSave) ⚠️ long-press: " + t.getMessage());
        }
    }

    /** Saves the cached current Instant's media via the module's downloader. */
    private static void saveCurrent() {
        final Object media = currentMedia;
        final Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return;
        if (media == null) {
            FeedVideoDownloadHook.mainHandler.post(() ->
                    Toast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_save_none), Toast.LENGTH_SHORT).show());
            return;
        }
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
                if (urls == null || urls.isEmpty()) {
                    FeedVideoDownloadHook.mainHandler.post(() ->
                            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_save_none), Toast.LENGTH_SHORT).show());
                    return;
                }
                String url = urls.get(0);
                boolean isVid = FeedVideoDownloadHook.isVideoUrl(url);
                String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
                if (username == null) username = "instant";
                String mediaId = "0";
                try {
                    Object id = media.getClass().getMethod("getId").invoke(media);
                    if (id instanceof String s && !s.isEmpty()) mediaId = s;
                } catch (Throwable ignored) {}
                String fn = FeedVideoDownloadHook.buildFilename(username, "instant", mediaId, isVid);
                final String fUser = username;
                FeedVideoDownloadHook.mainHandler.post(() ->
                        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_saving), Toast.LENGTH_SHORT).show());
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVid, fUser);
                if (!delegated) {
                    FeedVideoDownloadHook.mainHandler.post(() ->
                            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_saved), Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable e) {
                ModuleLog.line("(IE|InstantSave) ❌ save failed: " + e);
            }
        });
    }
}
