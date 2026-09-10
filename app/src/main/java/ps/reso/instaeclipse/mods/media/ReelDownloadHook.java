package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class ReelDownloadHook {

    private static Class<?> controllerClass;
    private static Method   hookMethod;

    private static Method buttonAdderMethod;
    private static Field  activityField;

    // Cached field path to the carousel position holder on the controller.
    // The position holder is identified structurally: a non-framework object field
    // whose class has exactly ONE int field (survives obfuscation renames).
    private static Field cachedOuterField = null;
    private static Field cachedInnerField = null;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installNativeDownloadGateUnlock(bridge, classLoader);
        installReduceOptionsListPatch(bridge, classLoader);

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ReelDownload", classLoader);
            if (cached != null) {
                controllerClass = cached.getDeclaringClass();
                hookMethod = cached;
                cached.setAccessible(true);
                FeatureStatusTracker.setHooked("ReelDownload");
                XposedBridge.hookMethod(cached, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableReelDownload) return;
                        onOptionsBuilt(param);
                    }
                });
                ModuleLog.line("(IE|Reel) ✅ hooked: " + hookMethod.getDeclaringClass().getName() + "." + hookMethod.getName());
                return;
            }
        }

        try {
            var methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("ClipsOrganicMediaItemViewMoreOptionsController")));

            if (methods.isEmpty()) {
                ModuleLog.line("(IE|Reel) ❌ ClipsOrganicMediaItemViewMoreOptionsController not found");
                return;
            }

            // The controller string can be referenced from more than one method; collect every
            // distinct declaring class and pick whichever actually carries the options-builder.
            java.util.LinkedHashSet<Class<?>> candidates = new java.util.LinkedHashSet<>();
            for (var md : methods) {
                try { candidates.add(md.getMethodInstance(classLoader).getDeclaringClass()); }
                catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|Reel) string matches=" + methods.size()
                    + " candidateClasses=" + candidates.size());

            // Options-builder: void(com.instagram.feed.media.Media, <ButtonAdder>), where the
            // ButtonAdder param exposes an (Context, OnClickListener, String, int) add method.
            // Prefer that precise shape; fall back to the first void(Media, non-primitive).
            Method target = null;
            Method fallback = null;
            for (Class<?> cand : candidates) {
                int voidMedia = 0;
                for (Method m : cand.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length < 2) continue;
                    if (!params[0].getName().equals("com.instagram.feed.media.Media")) continue;
                    if (params[1].isPrimitive() || params[1] == String.class) continue;
                    voidMedia++;
                    if (fallback == null) fallback = m;
                    if (hasButtonAdderMethod(params[1])) { target = m; break; }
                }
                if (voidMedia > 0) {
                    ModuleLog.line("(IE|Reel) candidate " + cand.getName()
                            + " void(Media,X)=" + voidMedia);
                }
                if (target != null) { controllerClass = cand; break; }
            }
            if (target == null && fallback != null) {
                target = fallback;
                controllerClass = fallback.getDeclaringClass();
            }

            if (target == null) {
                ModuleLog.line("(IE|Reel) ❌ hook method (Media, ButtonAdder)V not found");
                return;
            }

            target.setAccessible(true);
            hookMethod = target;
            DexKitCache.saveMethod("ReelDownload", target);
            FeatureStatusTracker.setHooked("ReelDownload");

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableReelDownload) return;
                    onOptionsBuilt(param);
                }
            });
            ModuleLog.line("(IE|Reel) ✅ hooked: " + hookMethod.getDeclaringClass().getName() + "." + hookMethod.getName());

        } catch (Throwable t) {
            ModuleLog.line("(IE|Reel) ❌ install: " + t);
        }
    }

    /**
     * True when {@code adder} exposes the reel-menu button-adder method
     * {@code (Context, View.OnClickListener, String, int)} — the same shape
     * {@link #onOptionsBuilt} invokes. Identifies the correct options-builder overload
     * structurally, so it survives obfuscated renames across Instagram versions.
     */
    private static boolean hasButtonAdderMethod(Class<?> adder) {
        if (adder == null) return false;
        for (Method m : adder.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4
                    && Context.class.isAssignableFrom(p[0])
                    && View.OnClickListener.class.isAssignableFrom(p[1])
                    && p[2] == String.class
                    && p[3] == int.class) return true;
        }
        return false;
    }

    // ── Reduced options-list patch ──────────────────────────────────────────────
    //
    // IG's newer, simplified reel overflow menu builds its option list via one
    // method that returns a plain ArrayList<MediaOption$Option> (SAVE/UNSAVE,
    // PLAYBACK_CONTROLS, WHY_AM_I_SEEING_THIS, INTERESTED, NOT_INTERESTED,
    // TAG_OPTIONS, REPORT, REQUEST_COMMUNITY_NOTE, DEBUG_STICKER_TRANSLATION) —
    // DOWNLOAD was dropped entirely from this list, unlike the older/fuller
    // overflow-menu code path. Found via field-usage matching on two of its
    // distinctive enum references. Appending DOWNLOAD to the returned (mutable)
    // ArrayList lets it flow through the same generic per-option row builder
    // (LX/5RY;->A0Q -> LX/QIy;->A04) used for every other option here — same
    // shared row primitive the post menu uses, so PostDownloadContextMenuHook's
    // app-wide click-handler hook already covers whatever dispatches its click.
    private static void installReduceOptionsListPatch(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            Object downloadOption = null;
            Class<?> optionClass = classLoader.loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            for (Object v : (Object[]) optionClass.getMethod("values").invoke(null)) {
                if (v.toString().equals("DOWNLOAD")) { downloadOption = v; break; }
            }
            if (downloadOption == null) {
                ModuleLog.line("(IE|Reel) ❌ DOWNLOAD enum value not found");
                return;
            }
            final Object download = downloadOption;

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableReelDownload) return;
                    try {
                        Object result = param.getResult();
                        if (result instanceof List<?> list && !list.contains(download)) {
                            @SuppressWarnings("unchecked")
                            List<Object> mutable = (List<Object>) list;
                            mutable.add(download);
                        }
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|Reel) ❌ options-list patch failed: " + t);
                    }
                }
            };

            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod("ReelOptionsListBuilder", classLoader);
                if (cached != null) {
                    XposedBridge.hookMethod(cached, hook);
                    return;
                }
            }

            String optionDesc = "Lcom/instagram/feed/media/mediaoption/MediaOption$Option;";
            var methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("java.util.ArrayList")
                            .addUsingField(optionDesc + "->PLAYBACK_CONTROLS:" + optionDesc)
                            .addUsingField(optionDesc + "->UNSAVE:" + optionDesc)));

            if (methods.isEmpty()) {
                ModuleLog.line("(IE|Reel) ⚠️ Reduced options-list builder not found");
                return;
            }

            Method target = methods.get(0).getMethodInstance(classLoader);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, hook);
            DexKitCache.saveMethod("ReelOptionsListBuilder", target);
            FeatureStatusTracker.setHooked("ReelDownload");
            ModuleLog.line("(IE|Reel) ✅ Options-list patch hooked: " +
                    target.getDeclaringClass().getName() + "." + target.getName());

        } catch (Throwable t) {
            ModuleLog.line("(IE|Reel) ❌ installReduceOptionsListPatch: " + t);
        }
    }

    // ── Native download-row unlock ──────────────────────────────────────────────
    //
    // IG 437+ moved the reel overflow menu to the same shared row-builder (QIy) used
    // by the post menu, and it already has a fully-working, native DOWNLOAD row —
    // gated behind two eligibility checks (a "can this media be downloaded" gate and
    // a "is the viewer restricted" gate). When both pass, native code adds the row
    // via the same QIy.A04 primitive posts use, with a working click handler already
    // wired to Instagram's own save-to-camera-roll flow. Bypassing the two gates is
    // far simpler and more robust than reconstructing that row/click machinery
    // ourselves. Found via each gate's distinct hardcoded MobileConfig param ID.
    private static void installNativeDownloadGateUnlock(DexKitBridge bridge, ClassLoader classLoader) {
        // "Can this media be downloaded" — force true.
        installGateHook(bridge, classLoader, "ReelDownloadGate_eligible",
                36313978552585585L, // 0x81035f00020d71
                "com.instagram.common.session.UserSession", "com.instagram.feed.media.Media",
                true);

        // "Is the viewer restricted from downloading" — force false.
        installGateHook(bridge, classLoader, "ReelDownloadGate_restricted",
                36313978552847731L, // 0x81035f00060d73
                "com.instagram.common.session.UserSession", "boolean",
                false);
    }

    private static void installGateHook(DexKitBridge bridge, ClassLoader classLoader,
                                         String cacheKey, long configId,
                                         String param1Type, String param2Type,
                                         boolean forcedResult) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ModuleLog.line("(IE|Reel|DEBUG) gate fired: " + cacheKey + " enabled=" + FeatureFlags.enableReelDownload);
                if (FeatureFlags.enableReelDownload) param.setResult(forcedResult);
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(cacheKey, classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                return;
            }
        }

        try {
            var methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes(param1Type, param2Type)
                            .returnType("boolean")
                            .usingNumbers(configId)));

            if (methods.isEmpty()) {
                ModuleLog.line("(IE|Reel) ⚠️ Gate method not found for config " + configId);
                return;
            }

            Method target = methods.get(0).getMethodInstance(classLoader);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, hook);
            DexKitCache.saveMethod(cacheKey, target);
            FeatureStatusTracker.setHooked("ReelDownload");
            ModuleLog.line("(IE|Reel) ✅ Gate unlocked: " +
                    target.getDeclaringClass().getName() + "." + target.getName() + " -> " + forcedResult);

        } catch (Throwable t) {
            ModuleLog.line("(IE|Reel) ❌ installGateHook(" + cacheKey + "): " + t);
        }
    }

    /**
     * Fallback index resolver: structurally locates the carousel position holder on the
     * controller. The holder is the unique non-framework field whose class has exactly
     * ONE int field — this property survives obfuscation renames across IG versions.
     * Values outside [0, 200) are excluded to filter out config constants.
     * Result is cached after first resolution.
     */
    private static int findReelCarouselIndex(Object controller) {
        if (controller == null) return 0;

        if (cachedOuterField != null && cachedInnerField != null) {
            try {
                Object holder = cachedOuterField.get(controller);
                if (holder != null) return cachedInnerField.getInt(holder);
            } catch (Throwable ignored) {}
            cachedOuterField = null;
            cachedInnerField = null;
        }

        int bestIdx = Integer.MAX_VALUE;
        Field bestOuter = null;
        Field bestInner = null;

        Class<?> c = controller.getClass();
        while (c != null && c != Object.class) {
            for (Field outerF : c.getDeclaredFields()) {
                if (outerF.getType().isPrimitive()) continue;
                String pkg = outerF.getType().getName();
                if (pkg.startsWith("android.") || pkg.startsWith("java.")
                        || pkg.startsWith("androidx.") || pkg.startsWith("kotlin.")) continue;
                outerF.setAccessible(true);
                Object nested;
                try { nested = outerF.get(controller); } catch (Throwable ignored) { continue; }
                if (nested == null) continue;

                Field singleIntField = null;
                int intCount = 0;
                Class<?> nc = nested.getClass();
                while (nc != null && nc != Object.class) {
                    String npkg = nc.getName();
                    if (npkg.startsWith("android.") || npkg.startsWith("java.")
                            || npkg.startsWith("androidx.") || npkg.startsWith("kotlin.")) break;
                    for (Field nf : nc.getDeclaredFields()) {
                        if (nf.getType() != int.class) continue;
                        intCount++;
                        singleIntField = nf;
                        if (intCount > 1) break;
                    }
                    if (intCount > 1) break;
                    nc = nc.getSuperclass();
                }

                if (intCount == 1 && singleIntField != null) {
                    singleIntField.setAccessible(true);
                    try {
                        int idx = singleIntField.getInt(nested);
                        if (idx >= 0 && idx < 200 && idx < bestIdx) {
                            bestIdx   = idx;
                            bestOuter = outerF;
                            bestInner = singleIntField;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            c = c.getSuperclass();
        }

        if (bestOuter != null) {
            cachedOuterField = bestOuter;
            cachedInnerField = bestInner;
            return bestIdx;
        }
        return 0;
    }

    /**
     * Primary index resolver: walks the activity's live view hierarchy for a
     * ViewPager / ViewPager2 / ReboundViewPager / horizontal RecyclerView whose adapter
     * item count equals {@code carouselSize} and returns its current data index.
     * Multiple unrelated carousels can coincidentally share the same item count (e.g.
     * two feed posts both showing 4 photos) — trusting the first DFS hit in that case
     * previously misattributed the index to the wrong post. So every match is collected
     * and the result is only trusted when exactly one candidate matches; otherwise the
     * caller falls back to the data-layer field.
     *
     * @return current position [0, carouselSize), or -1 if not found / ambiguous
     */
    static int findCarouselIndexFromView(Context ctx, int carouselSize) {
        if (!(ctx instanceof Activity)) return -1;
        try {
            View root = ((Activity) ctx).getWindow().getDecorView();
            List<Integer> matches = new java.util.ArrayList<>();
            collectCarouselMatches(root, carouselSize, matches);
            return matches.size() == 1 ? matches.get(0) : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Returns adapter item count, trying RecyclerView-style then PagerAdapter-style. */
    private static int adapterCount(Object adapter) {
        try { return (int) adapter.getClass().getMethod("getItemCount").invoke(adapter); } catch (Throwable ignored) {}
        try { return (int) adapter.getClass().getMethod("getCount").invoke(adapter); } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * Recursive DFS over the view tree, collecting the resolved index of every carousel
     * whose adapter size matches — does not stop at the first hit. ViewPager / ViewPager2 /
     * ReboundViewPager are AndroidX / Instagram common-UI classes — stable names, no obfuscation.
     */
    private static void collectCarouselMatches(View view, int carouselSize, List<Integer> out) {
        String cn = view.getClass().getName();

        // ViewPager / ViewPager2 / ReboundViewPager and any subclass
        if (cn.contains("ViewPager")) {
            try {
                Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    // Standard pagers: getCurrentItem()
                    // ReboundViewPager (Instagram looping carousel): getCurrentDataIndex()
                    for (String getter : new String[]{
                            "getCurrentItem", "getCurrentDataIndex",
                            "getCurrentWrappedDataIndex", "getCurrentRawDataIndex"}) {
                        try {
                            int cur = (int) view.getClass().getMethod(getter).invoke(view);
                            if (cur >= 0) { out.add(cur); break; }
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Horizontal RecyclerView (carousel, not the vertical feed list)
        if (cn.contains("RecyclerView")) {
            try {
                Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                if (adapter != null && adapterCount(adapter) == carouselSize) {
                    Object lm = view.getClass().getMethod("getLayoutManager").invoke(view);
                    if (lm != null) {
                        try {
                            int orientation = (int) lm.getClass().getMethod("getOrientation").invoke(lm);
                            if (orientation != 0 /* HORIZONTAL */) lm = null;
                        } catch (Throwable ignored) {}
                        if (lm != null) {
                            Integer pos = null;
                            try {
                                int p = (int) lm.getClass()
                                        .getMethod("findFirstCompletelyVisibleItemPosition").invoke(lm);
                                if (p >= 0) pos = p;
                            } catch (Throwable ignored) {}
                            if (pos == null) {
                                try {
                                    int p = (int) lm.getClass()
                                            .getMethod("findFirstVisibleItemPosition").invoke(lm);
                                    if (p >= 0) pos = p;
                                } catch (Throwable ignored) {}
                            }
                            if (pos != null) out.add(pos);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectCarouselMatches(vg.getChildAt(i), carouselSize, out);
            }
        }
    }

    private static void onOptionsBuilt(XC_MethodHook.MethodHookParam param) {
        try {
            Object controller  = param.thisObject;
            Object media       = param.args[0];
            Object buttonAdder = param.args[1];

            if (activityField == null) {
                for (Field f : controller.getClass().getDeclaredFields()) {
                    if (Activity.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        activityField = f;
                        break;
                    }
                }
            }
            if (activityField == null) {
                ModuleLog.line("(IE|Reel) ❌ no Activity field on controller");
                return;
            }

            Activity activity = (Activity) activityField.get(controller);
            if (activity == null) return;

            if (buttonAdderMethod == null) {
                for (Method m : buttonAdder.getClass().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (!Context.class.isAssignableFrom(p[0])) continue;
                    if (!View.OnClickListener.class.isAssignableFrom(p[1])) continue;
                    if (p[2] != String.class) continue;
                    if (p[3] != int.class) continue;
                    m.setAccessible(true);
                    buttonAdderMethod = m;
                    break;
                }
            }
            if (buttonAdderMethod == null) {
                ModuleLog.line("(IE|Reel) ❌ buttonAdderMethod not found");
                return;
            }

            int icon = resolveDownloadIcon(activity);
            final Activity actCopy      = activity;
            final Object mediaCopy      = media;
            final Object controllerCopy = controller;

            buttonAdderMethod.invoke(buttonAdder, activity,
                    (View.OnClickListener) v -> startReelDownload(actCopy, mediaCopy, controllerCopy),
                    I18n.t(activity, R.string.ig_dl_title), icon);

        } catch (Throwable t) {
            ModuleLog.line("(IE|Reel) ❌ onOptionsBuilt: " + t);
        }
    }

    private static void startReelDownload(Context ctx, Object media, Object controller) {
        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
        if (username == null) username = "reel";

        String mediaId = "0";
        try {
            Object id = media.getClass().getMethod("getId").invoke(media);
            if (id instanceof String s && !s.isEmpty()) mediaId = s;
        } catch (Throwable ignored) {}

        String videoUrl = FeedVideoDownloadHook.bestVideoUrlFromMedia(media);

        if (videoUrl != null) {
            final String fn        = FeedVideoDownloadHook.buildFilename(username, "reel", mediaId, true);
            final String finalUrl  = videoUrl;
            final String finalUser = username;
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_downloading_reel), Toast.LENGTH_SHORT).show();
            FeedVideoDownloadHook.executor.submit(() -> {
                try {
                    boolean delegated = FeedVideoDownloadHook.downloadAndSave(ctx, finalUrl, fn, true, finalUser);
                    if (!delegated) {
                        FeedVideoDownloadHook.mainHandler.post(() ->
                                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_reel_saved), Toast.LENGTH_SHORT).show());
                    }
                } catch (Throwable e) {
                    FeedVideoDownloadHook.mainHandler.post(() ->
                            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_reel_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
                }
            });
            return;
        }

        // No direct video — may be a photo carousel reel
        List<String> allUrls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        if (allUrls.isEmpty()) {
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_reel_url_not_found), Toast.LENGTH_SHORT).show();
            return;
        }

        // Primary: live view state (always correct). Fallback: data-layer field (stale at slide 0).
        int viewIndex    = findCarouselIndexFromView(ctx, allUrls.size());
        int currentIndex = viewIndex >= 0 ? viewIndex : findReelCarouselIndex(controller);

        final String finalUsername = username;
        final String finalMediaId  = mediaId;
        final int    finalIndex    = currentIndex;
        FeedVideoDownloadHook.mainHandler.post(() ->
                FeedVideoDownloadHook.showPostDownloadDialog(ctx, allUrls, finalUsername, finalMediaId, finalIndex));
    }

    /** Reads the icon drawable ID from MediaOption$Option.DOWNLOAD enum value. */
    private static int resolveDownloadIcon(Context ctx) {
        try {
            Class<?> optionClass = ctx.getClassLoader()
                    .loadClass("com.instagram.feed.media.mediaoption.MediaOption$Option");
            for (Object val : (Object[]) optionClass.getMethod("values").invoke(null)) {
                if (val.toString().contains("DOWNLOAD")) {
                    Field f = val.getClass().getField("iconDrawable");
                    return (int) f.get(val);
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
