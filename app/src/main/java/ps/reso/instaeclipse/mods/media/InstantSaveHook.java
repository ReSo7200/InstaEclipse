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
 *    viewer). Its constructor stores the onLongPress callback (a kotlin Function1) in a field.
 *    We wrap that Function1 so a long-press saves the cached Instant, then still runs IG's original.
 *
 * No obfuscated class OR field name is hardcoded: the two hooked classes are literal-named, the
 * Media is found by field type and the long-press callback by constructor-argument order.
 */
public class InstantSaveHook {

    // Compose renumbers this lambda between builds ($16$1$8$1 on 447, $16$1$11$1 on 451). Known
    // names first (newest first), then a DexKit search by the ctor's stable non-obfuscated types.
    private static final String VIEWER_PREFIX =
            "com.instagram.quicksnap.viewer.compose.QuickSnapMediaViewerScreenKt$QuickSnapMediaViewerScreen$";
    private static final String[] VIEWER_LAMBDAS = {
            VIEWER_PREFIX + "16$1$11$1",
            VIEWER_PREFIX + "16$1$8$1",
    };
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
            Class<?> lambda = resolveViewerLambda(cl);
            if (lambda == null) {
                ModuleLog.line("(IE|InstantSave) ⚠️ viewer cache: viewer lambda not found");
                return;
            }
            for (java.lang.reflect.Constructor<?> c : lambda.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        try {
                            Object media = findInstantMedia(p.args);
                            if (media != null) currentMedia = media;
                        } catch (Throwable ignored) {}
                    }
                });
            }
            ModuleLog.line("(IE|InstantSave) ✅ viewer cache hooked: " + lambda.getName());
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantSave) ⚠️ viewer cache: " + t.getMessage());
        }
    }

    private static Class<?> resolveViewerLambda(ClassLoader cl) {
        for (String name : VIEWER_LAMBDAS) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (isViewerLambda(c)) return c;
            } catch (Throwable ignored) {}
        }
        try {
            if (ps.reso.instaeclipse.Xposed.Module.dexKitBridge == null) return null;
            for (org.luckypray.dexkit.result.ClassData cd : ps.reso.instaeclipse.Xposed.Module.dexKitBridge.findClass(
                    org.luckypray.dexkit.query.FindClass.create().matcher(
                            org.luckypray.dexkit.query.matchers.ClassMatcher.create().className(
                                    VIEWER_PREFIX, org.luckypray.dexkit.query.enums.StringMatchType.StartsWith)))) {
                if (cd.getName().contains("cardCenter")) continue;
                try {
                    Class<?> c = cd.getInstance(cl);
                    if (isViewerLambda(c)) return c;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantSave) ⚠️ viewer lambda search: " + t.getMessage());
        }
        return null;
    }

    /** The current-card coroutine: a ctor taking UserSession + AnimatedParticleEffectState (447/451). */
    private static boolean isViewerLambda(Class<?> c) {
        for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
            boolean session = false, particles = false;
            for (Class<?> t : k.getParameterTypes()) {
                String n = t.getName();
                if (n.equals("com.instagram.common.session.UserSession")) session = true;
                if (n.endsWith(".AnimatedParticleEffectState")) particles = true;
            }
            if (session && particles) return true;
        }
        return false;
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
     * Hook the viewer pointer handler and wrap its onLongPress callback (A0A on 452 — verified
     * on-device: it fires exactly once per gesture at the long-press threshold, while the other
     * Function1 is the ordinary press/tap callback that fires repeatedly). On long-press we save the
     * cached Instant, then still run IG's original callback so its own behaviour (pause, etc.) is
     * preserved. The field is picked by {@link #longPressField}, not by its obfuscated letter.
     */
    private void wrapLongPress(ClassLoader cl) {
        try {
            final Class<?> function1 = XposedHelpers.findClass("kotlin.jvm.functions.Function1", cl);
            Class<?> handler = XposedHelpers.findClass(LONGPRESS_LAMBDA, cl);
            for (java.lang.reflect.Constructor<?> c : handler.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        try {
                            java.lang.reflect.Field field = longPressField(p, function1);
                            if (field == null) return;
                            final Object orig = field.get(p.thisObject);
                            if (orig == null || !function1.isInstance(orig)) return;
                            if (orig.getClass().getName().startsWith("$Proxy")) return; // already wrapped
                            Object wrapper = Proxy.newProxyInstance(cl, new Class[]{function1}, (proxy, method, a) -> {
                                if ("invoke".equals(method.getName()) && FeatureFlags.saveInstants) {
                                    long now = android.os.SystemClock.uptimeMillis();
                                    if (now - lastSaveMs > 1000) { lastSaveMs = now; saveCurrent(); }
                                }
                                return orig != null ? method.invoke(orig, a) : null;
                            });
                            field.set(p.thisObject, wrapper);
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

    private static volatile java.lang.reflect.Field longPressField;
    private static volatile boolean longPressMissLogged;

    /**
     * The pointer lambda captures (continuation, onPress: Function0, onTap: Function1,
     * onLongPress: Function1, slop: Float, timeout: Long) — Kotlin keeps capture order in the ctor
     * (452: p3 -> A0B tap, p4 -> A0A long-press). So the long-press callback is the SECOND
     * Function1 ctor argument; the field is the Function1 field holding that same instance.
     * Resolved on the first construction and reused (field letters are fixed for one build).
     */
    private static java.lang.reflect.Field longPressField(XC_MethodHook.MethodHookParam p, Class<?> function1) {
        java.lang.reflect.Field cached = longPressField;
        if (cached != null) return cached;
        Object longPress = null;
        int seen = 0;
        for (Object a : p.args) {
            if (a != null && function1.isInstance(a) && ++seen == 2) { longPress = a; break; }
        }
        if (longPress != null) {
            for (java.lang.reflect.Field f : p.thisObject.getClass().getDeclaredFields()) {
                if (f.getType() != function1) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(p.thisObject) == longPress) {
                        ModuleLog.line("(IE|InstantSave) long-press field = " + f.getName());
                        return longPressField = f;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (!longPressMissLogged) {
            longPressMissLogged = true;
            ModuleLog.line("(IE|InstantSave) ⚠️ long-press field not found (Function1 args: " + seen + ")");
        }
        return null;
    }

    /** Saves the cached current Instant's media via the module's downloader. */
    private static void saveCurrent() {
        final Object media = currentMedia;
        final Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return;
        if (media == null) {
            FeedVideoDownloadHook.mainHandler.post(() ->
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_save_none), Toast.LENGTH_SHORT).show());
            return;
        }
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
                if (urls == null || urls.isEmpty()) {
                    FeedVideoDownloadHook.mainHandler.post(() ->
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_save_none), Toast.LENGTH_SHORT).show());
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
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_saving), Toast.LENGTH_SHORT).show());
                boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, isVid, fUser);
                if (!delegated) {
                    FeedVideoDownloadHook.mainHandler.post(() ->
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_instant_saved), Toast.LENGTH_SHORT).show());
                }
            } catch (Throwable e) {
                ModuleLog.line("(IE|InstantSave) ❌ save failed: " + e);
            }
        });
    }
}
