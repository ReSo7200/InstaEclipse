package ps.reso.instaeclipse.mods.media;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Injects a "Download" entry into the feed-post three-dots (⋮) menu.
 *
 * Hook A — static add-button method on MediaOptionsOverflowMenuCreator
 *   Found via DexKit: findClass("MediaOptionsOverflowMenuCreator") → findMethod(declaredClass, void).
 *   Hooks every addButton call; injects our Download entry once per menu popup.
 *
 * Hook B — options click handler
 *   Found via DexKit: void method with sole param MediaOption$Option (stable, unobfuscated type).
 *   Fires on every option tap; we handle DOWNLOAD and trigger the download.
 *
 * Carousel index: read from the first int field on thisObject whose value fits [0, urlCount).
 *   Tries a known field name first (fast path), then falls back to scanning all int fields
 *   so a rename in a future build doesn't break it.
 */
public class PostDownloadContextMenuHook {



    // ── Resolved at install time ──────────────────────────────────────────────

    // com.instagram.feed.media.mediaoption.MediaOption$Option — stable public enum
    private static Class<?> mediaOptionEnumClass;
    private static Object   downloadOptionValue;     // MediaOption$Option.DOWNLOAD
    private static Object   copyLinkOptionValue;     // MediaOption$Option.COPY_LINK (#117)

    // Obfuscated creator class found via "MediaOptionsOverflowMenuCreator" string
    private static Class<?> menuCreatorClass;

    // Static "add one button to list" method on menuCreatorClass — no hardcoded name
    private static Method   addButtonMethod;
    private static Object   enumNormalValue;

    // Param indices — resolved once when addButtonMethod is found
    private static int idxEnum   = 0;
    private static int idxOption = 1;
    private static int idxSelf   = 2;
    private static int idxText   = 3;
    private static int idxList   = 4;

    // ── Guards ────────────────────────────────────────────────────────────────

    private static final ThreadLocal<Boolean> sAddingDownload =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Set<Object> processedCreators =
            Collections.newSetFromMap(new WeakHashMap<>());

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        loadMediaOptionEnum(classLoader);
        findCreatorClassAndAddButtonMethod(bridge, classLoader);
        installAddButtonHook();
        installClickHandlerHook(bridge, classLoader);
        installAllowlistPatchHook(bridge, classLoader);
    }

    // ── Step 1: MediaOption$Option.DOWNLOAD ──────────────────────────────────

    private static void loadMediaOptionEnum(ClassLoader cl) {
        try {
            mediaOptionEnumClass = cl.loadClass(
                    "com.instagram.feed.media.mediaoption.MediaOption$Option");
            Object[] values = (Object[]) mediaOptionEnumClass.getMethod("values").invoke(null);
            for (Object v : values) {
                String name = v.toString();
                if (downloadOptionValue == null && name.equals("DOWNLOAD")) {
                    downloadOptionValue = v;
                } else if (copyLinkOptionValue == null && name.equals("COPY_LINK")) {
                    copyLinkOptionValue = v;
                }
            }
            if (downloadOptionValue == null)
                ModuleLog.line("(IE|Post) ❌ DOWNLOAD enum value not found");
            if (copyLinkOptionValue == null)
                ModuleLog.line("(IE|Post) ❌ COPY_LINK enum value not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ loadMediaOptionEnum: " + t);
        }
    }

    // ── Step 2: find MediaOptionsOverflowMenuCreator + its add-button method ─
    //
    // Pass 1: findClass by string "MediaOptionsOverflowMenuCreator" (avoids <clinit> crash
    //         that occurs when calling getMethodInstance() on a <clinit> MethodData).
    // Pass 2: findMethod(declaredClass, returnType=void) → filter for the static method
    //         that takes MediaOption$Option + ArrayList as params.

    private static void findCreatorClassAndAddButtonMethod(DexKitBridge bridge,
                                                            ClassLoader classLoader) {
        // Cache hit: restore addButtonMethod and parameter indices without DexKit
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("PostDownload_addButton", classLoader);
            if (cached != null) {
                addButtonMethod = cached;
                addButtonMethod.setAccessible(true);
                menuCreatorClass = cached.getDeclaringClass();
                String idxStr = DexKitCache.loadString("PostDownload_addButtonIdx");
                if (idxStr != null) {
                    String[] parts = idxStr.split(",");
                    if (parts.length == 5) {
                        try {
                            idxEnum   = Integer.parseInt(parts[0]);
                            idxOption = Integer.parseInt(parts[1]);
                            idxSelf   = Integer.parseInt(parts[2]);
                            idxText   = Integer.parseInt(parts[3]);
                            idxList   = Integer.parseInt(parts[4]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                // Resolve enumNormalValue from the button-type enum parameter
                if (idxEnum >= 0) {
                    try {
                        Class<?> btnTypeEnum = cached.getParameterTypes()[idxEnum];
                        Object[] vals = (Object[]) btnTypeEnum.getMethod("values").invoke(null);
                        Object first = null;
                        for (Object v : vals) {
                            if (first == null) first = v;
                            if (enumNormalValue == null && v.toString().equalsIgnoreCase("normal"))
                                enumNormalValue = v;
                        }
                        if (enumNormalValue == null)
                            for (Object v : vals)
                                if (v.toString().equalsIgnoreCase("action")) { enumNormalValue = v; break; }
                        if (enumNormalValue == null) enumNormalValue = first;
                    } catch (Throwable ignored) {}
                }
                return;
            }
        }

        try {
            List<ClassData> pass1 = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingStrings("MediaOptionsOverflowMenuCreator")));

            if (pass1.isEmpty()) {
                ModuleLog.line("(IE|Post) ❌ MediaOptionsOverflowMenuCreator class not found");
                return;
            }

            String creatorClassName = pass1.get(0).getName();
            menuCreatorClass = classLoader.loadClass(creatorClassName);

            List<MethodData> pass2 = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(creatorClassName)
                            .returnType("void")));

            for (MethodData md : pass2) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!Modifier.isStatic(m.getModifiers())) continue;

                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 4) continue;

                    int eIdx = -1, oIdx = -1, sIdx = -1, tIdx = -1, lIdx = -1;
                    for (int i = 0; i < p.length; i++) {
                        if (mediaOptionEnumClass != null && p[i] == mediaOptionEnumClass) {
                            oIdx = i;
                        } else if (ArrayList.class.isAssignableFrom(p[i])) {
                            lIdx = i;
                        } else if (p[i] == menuCreatorClass) {
                            sIdx = i;
                        } else if (CharSequence.class.isAssignableFrom(p[i])) {
                            tIdx = i;
                        } else if (p[i].isEnum() && eIdx < 0 && oIdx < 0) {
                            eIdx = i;
                        }
                    }

                    if (oIdx < 0 || lIdx < 0) continue;

                    addButtonMethod = m;
                    addButtonMethod.setAccessible(true);
                    idxEnum   = eIdx >= 0 ? eIdx : 0;
                    idxOption = oIdx;
                    idxSelf   = sIdx >= 0 ? sIdx : 2;
                    idxText   = tIdx >= 0 ? tIdx : 3;
                    idxList   = lIdx;
                    break;
                } catch (Throwable ignored) {}
            }

            if (addButtonMethod == null) {
                ModuleLog.line("(IE|Post) ❌ addButtonMethod not found in " + creatorClassName);
                return;
            }
            DexKitCache.saveMethod("PostDownload_addButton", addButtonMethod);
            DexKitCache.saveString("PostDownload_addButtonIdx",
                    idxEnum + "," + idxOption + "," + idxSelf + "," + idxText + "," + idxList);

            // Resolve the "normal" button-type enum value
            Class<?> btnTypeEnumClass = addButtonMethod.getParameterTypes()[idxEnum];
            Object[] btnVals = (Object[]) btnTypeEnumClass.getMethod("values").invoke(null);
            Object firstVal = null;
            for (Object v : btnVals) {
                if (firstVal == null) firstVal = v;
                if (enumNormalValue == null && v.toString().equalsIgnoreCase("normal")) {
                    enumNormalValue = v;
                }
            }
            if (enumNormalValue == null) {
                for (Object v : btnVals) {
                    if (v.toString().equalsIgnoreCase("action")) { enumNormalValue = v; break; }
                }
            }
            if (enumNormalValue == null) enumNormalValue = firstVal;

        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ findCreatorClassAndAddButtonMethod: " + t);
        }
    }

    // ── Hook A: intercept every addButton call, inject Download once per menu ─

    private static void installAddButtonHook() {
        if (addButtonMethod == null || enumNormalValue == null
                || (downloadOptionValue == null && copyLinkOptionValue == null)) {
            ModuleLog.line("(IE|Post) ❌ Cannot install addButton hook — prerequisites missing");
            return;
        }

        XposedBridge.hookMethod(addButtonMethod, new XC_MethodHook() {

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(sAddingDownload.get())) return;
                Object opt = param.args[idxOption];
                // Suppress IG's own native rows — we inject our own versions instead
                if (FeatureFlags.enablePostDownload && opt == downloadOptionValue) {
                    param.setResult(null);
                } else if (FeatureFlags.copyMediaLink && opt == copyLinkOptionValue) {
                    param.setResult(null);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(sAddingDownload.get())) return;

                boolean wantDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null;
                boolean wantCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null;
                if (!wantDownload && !wantCopyLink) return;

                // Don't react to our own target option types (suppressed in beforeHook)
                Object opt = param.args[idxOption];
                if (opt == downloadOptionValue || opt == copyLinkOptionValue) return;

                Object self = param.args[idxSelf];
                boolean alreadyProcessed;
                synchronized (processedCreators) {
                    alreadyProcessed = processedCreators.contains(self);
                    if (!alreadyProcessed) processedCreators.add(self);
                }
                if (alreadyProcessed) return;

                if (wantDownload) injectRow(param, downloadOptionValue, R.string.ig_dl_title);
                if (wantCopyLink) injectRow(param, copyLinkOptionValue, R.string.ig_copy_link_title);
            }
        });

        if (downloadOptionValue != null) FeatureStatusTracker.setHooked("PostDownload");
        if (copyLinkOptionValue != null) FeatureStatusTracker.setHooked("CopyMediaLink");
        ModuleLog.line("(IE|Post) ✅ Post menu inject hook installed");
    }

    /** Adds one button (Download / Copy Media Link) to the menu currently being built. */
    private static void injectRow(XC_MethodHook.MethodHookParam param, Object optionValue, int labelResId) {
        Object[] callArgs = new Object[addButtonMethod.getParameterCount()];
        System.arraycopy(param.args, 0, callArgs, 0, callArgs.length);
        callArgs[idxEnum]   = enumNormalValue;
        callArgs[idxOption] = optionValue;
        callArgs[idxText]   = I18n.t(AndroidAppHelper.currentApplication(), labelResId);

        sAddingDownload.set(true);
        try {
            addButtonMethod.invoke(null, callArgs);
        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ addButton invoke failed: " + t);
        } finally {
            sAddingDownload.set(false);
        }
    }

    // ── Hook B: click handler ─────────────────────────────────────────────────
    //
    // DexKit finds void methods with sole param MediaOption$Option — a stable unobfuscated type.
    // No string constants used, so obfuscation of surrounding code doesn't matter.

    private static void installClickHandlerHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enablePostDownload && !FeatureFlags.copyMediaLink) return;
                onOptionClicked(param);
            }
        };

        // Cache hit: restore all previously-found click handler methods
        // (cache key bumped to _v2 — the old key could hold private analytics-only
        // lookalikes hooked before the public-only filter below was added)
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("PostDownload_click_v2", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, clickHook);
                return;
            }
        }

        try {
            String optionClassName = "com.instagram.feed.media.mediaoption.MediaOption$Option";

            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("void")
                            .paramTypes(optionClassName)));

            // Resolve all non-static candidates first so we can decide the private-exclusion
            // policy from the whole set, rather than filtering as we go.
            List<Method> candidates = new ArrayList<>();
            for (MethodData md : results) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    candidates.add(m);
                } catch (Throwable ignored) {}
            }

            // Exclude private methods — these are consistently internal analytics/logging
            // helpers that also receive the tapped option for telemetry (e.g. 5RY.A08 on
            // the reel menu), not the real click dispatcher (public in every case seen so
            // far: RAu.A08 for posts, 5RY.A0O for reels). Hooking the private lookalikes
            // caused duplicate/spurious download triggers. Only apply the exclusion when a
            // public candidate also exists — on a build where the real dispatcher itself
            // turns out to be private with no public sibling, skipping it entirely would
            // just break the feature outright, which is worse than the duplicate-trigger
            // risk this filter guards against.
            boolean hasPublicCandidate = candidates.stream().anyMatch(m -> Modifier.isPublic(m.getModifiers()));

            List<Method> hooked = new ArrayList<>();
            for (Method m : candidates) {
                try {
                    if (hasPublicCandidate && Modifier.isPrivate(m.getModifiers())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, clickHook);
                    hooked.add(m);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Post) ❌ Failed to hook click candidate: " + t);
                }
            }

            if (hooked.isEmpty()) {
                ModuleLog.line("(IE|Post) ❌ No click handler methods could be hooked");
            } else {
                DexKitCache.saveMethods("PostDownload_click_v2", hooked);
            }

        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ installClickHandlerHook: " + t);
        }
    }

    // ── Hook C: allowlist patch ─────────────────────────────────────────────────
    //
    // IG's newer "SimplifiedMediaOverflowBottomSheet" menu filters the button list
    // down to a hardcoded allowlist of MediaOption$Option values before rendering
    // (DOWNLOAD isn't one of them), silently dropping our injected entry even though
    // Hook A added it successfully. Found via DexKit field-usage matching — the
    // allowlist-builder method is the unique static (boolean)->List method that
    // references these three particular MediaOption$Option constants together.
    // We patch its return value to also include DOWNLOAD so our entry survives.

    private static void installAllowlistPatchHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook allowlistHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object result = param.getResult();
                    if (!(result instanceof List<?> original)) return;

                    boolean addDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null
                            && !original.contains(downloadOptionValue);
                    boolean addCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null
                            && !original.contains(copyLinkOptionValue);
                    if (!addDownload && !addCopyLink) return;

                    List<Object> patched = new ArrayList<>(original);
                    if (addDownload) patched.add(downloadOptionValue);
                    if (addCopyLink) patched.add(copyLinkOptionValue);
                    param.setResult(patched);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Post) ❌ allowlist patch failed: " + t);
                }
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("PostDownload_allowlist", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, allowlistHook);
                return;
            }
        }

        try {
            String optionClassName = "com.instagram.feed.media.mediaoption.MediaOption$Option";
            String typeDesc = "L" + optionClassName.replace('.', '/') + ";";
            String prefix = typeDesc + "->";

            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("boolean")
                            .returnType("java.util.List")
                            .addUsingField(prefix + "REPORT:" + typeDesc)
                            .addUsingField(prefix + "HIDE_OPTIONS:" + typeDesc)
                            .addUsingField(prefix + "GEN_AI_INFO:" + typeDesc)));

            if (results.isEmpty()) {
                ModuleLog.line("(IE|Post) ⚠️ Allowlist method not found (menu may not be filtered on this build)");
                return;
            }

            Method target = results.get(0).getMethodInstance(classLoader);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, allowlistHook);
            DexKitCache.saveMethod("PostDownload_allowlist", target);
            ModuleLog.line("(IE|Post) ✅ Allowlist patch hooked: " +
                    target.getDeclaringClass().getName() + "." + target.getName());

        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ installAllowlistPatchHook: " + t);
        }
    }

    // ── Click dispatch ────────────────────────────────────────────────────────

    private static void onOptionClicked(XC_MethodHook.MethodHookParam param) {
        try {
            if (Boolean.TRUE.equals(sAddingDownload.get())) return;

            // Find MediaOption$Option argument
            Object clicked = null;
            for (Object a : param.args) {
                if (a != null && mediaOptionEnumClass != null && mediaOptionEnumClass.isInstance(a)) {
                    clicked = a; break;
                }
            }
            if (clicked == null) {
                for (Object a : param.args) {
                    if (a != null && a.getClass().isEnum()
                            && (a.toString().contains("DOWNLOAD") || a.toString().contains("COPY_LINK"))) {
                        clicked = a; break;
                    }
                }
            }
            if (clicked == null) return;

            String option = clicked.toString();
            boolean isDownload = option.equals("DOWNLOAD") && FeatureFlags.enablePostDownload;
            boolean isCopyLink = option.equals("COPY_LINK") && FeatureFlags.copyMediaLink;
            if (!isDownload && !isCopyLink) return;

            param.setResult(null); // consume the event

            Object thisObj = param.thisObject;

            Context ctx = findContext(thisObj);
            if (ctx == null) {
                ModuleLog.line("(IE|Post) ❌ Context not found in click handler");
                return;
            }

            Object media = findMediaViaMenuCreator(thisObj);
            if (media == null) media = findMedia(thisObj);
            if (media == null) {
                ModuleLog.line("(IE|Post) ❌ Media not found in click handler");
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
                return;
            }

            if (isCopyLink) triggerCopyLink(ctx, media, thisObj);
            else            triggerDownload(ctx, media, thisObj);
        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ onOptionClicked: " + t);
        }
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    private static void triggerDownload(Context ctx, Object media, Object clickHandler) {
        String username = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
        if (username == null) username = "post";

        String mediaId = "0";
        try {
            Object id = media.getClass().getMethod("getId").invoke(media);
            if (id instanceof String s && !s.isEmpty()) mediaId = s;
        } catch (Throwable ignored) {}

        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);

        // Carousel index: live view scan is primary (reads the actual visible slide, and
        // now only trusts itself when exactly one on-screen carousel matches the target
        // size — see ReelDownloadHook.findCarouselIndexFromView). Field-guessing on
        // clickHandler is the fallback for when the scan is ambiguous or finds nothing;
        // it's unreliable specifically for reels whose DOWNLOAD row was patched into the
        // shared post-style option list (see ReelDownloadHook.installReduceOptionsListPatch),
        // where clickHandler carries no real position field.
        int viewIdx = urls.size() > 1
                ? ReelDownloadHook.findCarouselIndexFromView(ctx, urls.size())
                : -1;
        final int carouselIdx = viewIdx >= 0 ? viewIdx : findCarouselIndex(clickHandler, urls.size());

        final String finalUser = username;
        final String finalId   = mediaId;
        FeedVideoDownloadHook.mainHandler.post(() ->
                FeedVideoDownloadHook.showPostDownloadDialog(ctx, urls, finalUser, finalId, carouselIdx));
    }

    // ── Copy Media Link (#117) ────────────────────────────────────────────────
    //
    // Copies the direct CDN url of the currently-visible slide to the clipboard.
    // Reuses the same URL extraction + carousel-index resolution as the downloader
    // so the copied link always matches the slide the user is looking at.

    private static void triggerCopyLink(Context ctx, Object media, Object clickHandler) {
        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        if (urls == null || urls.isEmpty()) {
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_copy_link_none), Toast.LENGTH_SHORT).show();
            return;
        }
        // Single URL copies straight to clipboard; a carousel shows a per-slide chooser
        // (see showCopyLinkSheet — the visible slide can't be resolved reliably in the feed).
        FeedVideoDownloadHook.mainHandler.post(() -> FeedVideoDownloadHook.showCopyLinkSheet(ctx, urls));
    }

    /**
     * Finds the current carousel slide index from the click handler object.
     * Tries a known field name first (fast path), then scans all int fields for the
     * first value in [0, urlCount) — only the index field will be in that range.
     */
    static int findCarouselIndex(Object obj, int urlCount) {
        if (obj == null || urlCount <= 1) return 0;

        // Fast path: try the currently known field name
        try {
            Field f = obj.getClass().getDeclaredField("A00");
            f.setAccessible(true);
            int v = f.getInt(obj);
            if (v >= 0 && v < urlCount) return v;
        } catch (Throwable ignored) {}

        // Fallback: scan all int fields for a value that fits as a carousel index
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType() != int.class) continue;
            f.setAccessible(true);
            try {
                int v = f.getInt(obj);
                if (v > 0 && v < urlCount) return v; // v > 0 skips 0-value flags/uninitialised
            } catch (Throwable ignored) {}
        }

        return 0;
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    private static Context findContext(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(obj);
                    if (v instanceof Context c) return c;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Preferred media lookup: reads the click handler's own reference to the menu-creator
     * (QpF-equivalent) instance and pulls its Media field directly, instead of the generic
     * BFS in {@link #findMedia}. The generic scan can land on an unrelated Media-typed field
     * elsewhere in the object graph first (e.g. a hosting Fragment's own "created with" post
     * reference, which doesn't update as the user navigates between posts within that
     * Fragment) — the menu-creator's own Media field is always the one that was current
     * when THIS specific menu was built, so it can't go stale that way.
     */
    private static Object findMediaViaMenuCreator(Object clickHandler) {
        if (clickHandler == null || menuCreatorClass == null) return null;
        try {
            Object creator = null;
            Class<?> cls = clickHandler.getClass();
            outer:
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != menuCreatorClass) continue;
                    f.setAccessible(true);
                    Object v = f.get(clickHandler);
                    if (v != null) { creator = v; break outer; }
                }
                cls = cls.getSuperclass();
            }
            if (creator == null) return null;

            Class<?> cCls = creator.getClass();
            while (cCls != null && cCls != Object.class) {
                for (Field f : cCls.getDeclaredFields()) {
                    if (f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        f.setAccessible(true);
                        return f.get(creator);
                    }
                }
                cCls = cCls.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object findMedia(Object obj) {
        return findMediaDepth(obj, 0);
    }

    private static Object findMediaDepth(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        Class<?> cls = obj.getClass();
        if (cls.isPrimitive() || cls.getName().startsWith("java.") || cls.getName().startsWith("android."))
            return null;

        List<Object> nextLevel = depth < 2 ? new ArrayList<>() : null;

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(obj);
                    if (v == null) continue;
                    String name = v.getClass().getName();
                    if (name.equals("com.instagram.feed.media.Media")) return v;
                    if (nextLevel != null && !name.startsWith("java.") && !name.startsWith("android."))
                        nextLevel.add(v);
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        if (nextLevel != null) {
            for (Object child : nextLevel) {
                Object found = findMediaDepth(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
}
