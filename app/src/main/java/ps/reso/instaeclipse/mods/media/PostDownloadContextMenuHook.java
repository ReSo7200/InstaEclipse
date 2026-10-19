package ps.reso.instaeclipse.mods.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.enums.StringMatchType;
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

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
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
    // "Copy image" row (#228). IG has no such option, so the row borrows an internal debug-only
    // value that never appears in a normal user's menu (present 436 → 452) and gets our label;
    // its taps are consumed below before IG's handler sees them.
    private static final String COPY_IMAGE_CARRIER = "EXPLORE_INTERNAL_DEBUG_LOGS";
    private static Object   copyImageOptionValue;
    // "Media info" (#231) and "Playback speed" (#247) rows: two more internal debug-only values
    // (present 436 → 452) carry our labels the same way.
    static final String MEDIA_INFO_CARRIER = "DEBUG_SHOPS_AD";
    static final String SPEED_CARRIER = "PROMOTE_DEBUG";
    private static Object   mediaInfoOptionValue;
    private static Object   speedOptionValue;

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
                } else if (copyImageOptionValue == null && name.equals(COPY_IMAGE_CARRIER)) {
                    copyImageOptionValue = v;
                } else if (mediaInfoOptionValue == null && name.equals(MEDIA_INFO_CARRIER)) {
                    mediaInfoOptionValue = v;
                } else if (speedOptionValue == null && name.equals(SPEED_CARRIER)) {
                    speedOptionValue = v;
                }
            }
            ps.reso.instaeclipse.mods.misc.CaptionCopyContextMenuHook.EXTRA_REEL_LABELS.put(MEDIA_INFO_CARRIER, R.string.ig_media_info_title);
            ps.reso.instaeclipse.mods.misc.CaptionCopyContextMenuHook.EXTRA_REEL_LABELS.put(SPEED_CARRIER, R.string.ig_playback_speed_title);
            for (Object v : values) {
                if (v == null) continue;
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
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (Boolean.TRUE.equals(sAddingDownload.get())) return;
                Object opt = param.args[idxOption];
                // Suppress IG's own native rows — we inject our own versions instead
                if (FeatureFlags.enablePostDownload && opt == downloadOptionValue) {
                    param.setResult(null);
                } else if (FeatureFlags.copyMediaLink && opt == copyLinkOptionValue) {
                    param.setResult(null);
                } else if (FeatureFlags.offerCopyImage && opt == copyImageOptionValue) {
                    param.setResult(null);
                } else if (FeatureFlags.offerMediaInfo && opt == mediaInfoOptionValue) {
                    param.setResult(null);
                } else if (FeatureFlags.offerPlaybackSpeed && opt == speedOptionValue) {
                    param.setResult(null);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (Boolean.TRUE.equals(sAddingDownload.get())) return;

                boolean wantDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null;
                boolean wantCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null;
                boolean wantCopyImage = FeatureFlags.offerCopyImage && copyImageOptionValue != null;
                boolean wantInfo = FeatureFlags.offerMediaInfo && mediaInfoOptionValue != null;
                boolean wantSpeed = FeatureFlags.offerPlaybackSpeed && speedOptionValue != null;
                if (!wantDownload && !wantCopyLink && !wantCopyImage && !wantInfo && !wantSpeed) return;

                // Don't react to our own target option types (suppressed in beforeHook)
                Object opt = param.args[idxOption];
                if (opt == downloadOptionValue || opt == copyLinkOptionValue
                        || (opt != null && (opt == copyImageOptionValue || opt == mediaInfoOptionValue
                                || opt == speedOptionValue))) return;

                Object self = param.args[idxSelf];
                boolean alreadyProcessed;
                synchronized (processedCreators) {
                    alreadyProcessed = processedCreators.contains(self);
                    if (!alreadyProcessed) processedCreators.add(self);
                }
                if (alreadyProcessed) return;

                if (wantDownload) injectRow(param, downloadOptionValue, R.string.ig_dl_title);
                if (wantCopyLink) injectRow(param, copyLinkOptionValue, R.string.ig_copy_link_title);
                if (wantCopyImage) injectRow(param, copyImageOptionValue, R.string.ig_download_choice_copy_image);
                if (wantInfo) injectRow(param, mediaInfoOptionValue, R.string.ig_media_info_title);
                if (wantSpeed) injectRow(param, speedOptionValue, R.string.ig_playback_speed_title);
                // Our carriers are debug options Instagram draws with the Meta logo; give them real icons.
                if (wantCopyImage || wantInfo || wantSpeed) ps.reso.instaeclipse.mods.ui.MenuIcons.fixSoon();
            }
        });

        if (downloadOptionValue != null) FeatureStatusTracker.setHooked("PostDownload");
        if (copyLinkOptionValue != null) FeatureStatusTracker.setHooked("CopyMediaLink");
        if (copyImageOptionValue != null) FeatureStatusTracker.setHooked("CopyImage");
        if (mediaInfoOptionValue != null) FeatureStatusTracker.setHooked("MediaInfo");
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

    private static Method reelClickDispatcher;

    private static void resolveReelClickDispatcher(DexKitBridge bridge, ClassLoader loader) {
        reelClickDispatcher = null;
        try {
            List<MethodData> matches = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("void")
                    .paramTypes("com.instagram.feed.media.mediaoption.MediaOption$Option")
                    .usingStrings(List.of("ClipsOrganicMoreOptionsHelper_handleOptionSelected"),
                            StringMatchType.Contains, false)));
            Set<Method> methods = new java.util.LinkedHashSet<>();
            for (MethodData data : matches) {
                Method method = data.getMethodInstance(loader);
                if (!Modifier.isStatic(method.getModifiers())) methods.add(method);
            }
            if (methods.size() == 1) reelClickDispatcher = methods.iterator().next();
        } catch (Throwable ignored) { }
        ModuleLog.line("(IE|Post) selected Reel dispatcher bound=" + (reelClickDispatcher != null));
    }

    private static void installClickHandlerHook(DexKitBridge bridge, ClassLoader classLoader) {
        resolveReelClickDispatcher(bridge, classLoader);
        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.enablePostDownload && !FeatureFlags.enableReelDownload
                        && !FeatureFlags.copyMediaLink && !FeatureFlags.offerCopyImage
                        && !FeatureFlags.offerMediaInfo && !FeatureFlags.offerPlaybackSpeed) return;
                onOptionClicked(param);
            }
        };

        // Cache hit: restore all previously-found click handler methods
        // (cache key bumped to _v2 — the old key could hold private analytics-only
        // lookalikes hooked before the public-only filter below was added)
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("PostDownload_click_v3", classLoader);
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
                    if (reelClickDispatcher != null && m.getDeclaringClass() == reelClickDispatcher.getDeclaringClass()
                            && !m.equals(reelClickDispatcher)) continue;
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
                DexKitCache.saveMethods("PostDownload_click_v3", hooked);
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
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    Object result = param.getResult();
                    if (!(result instanceof List<?> original)) return;

                    boolean addDownload = FeatureFlags.enablePostDownload && downloadOptionValue != null
                            && !original.contains(downloadOptionValue);
                    boolean addCopyLink = FeatureFlags.copyMediaLink && copyLinkOptionValue != null
                            && !original.contains(copyLinkOptionValue);
                    boolean addCopyImage = FeatureFlags.offerCopyImage && copyImageOptionValue != null
                            && !original.contains(copyImageOptionValue);
                    boolean addInfo = FeatureFlags.offerMediaInfo && mediaInfoOptionValue != null
                            && !original.contains(mediaInfoOptionValue);
                    boolean addSpeed = FeatureFlags.offerPlaybackSpeed && speedOptionValue != null
                            && !original.contains(speedOptionValue);
                    if (!addDownload && !addCopyLink && !addCopyImage && !addInfo && !addSpeed) return;

                    List<Object> patched = new ArrayList<>(original);
                    if (addDownload) patched.add(downloadOptionValue);
                    if (addCopyLink) patched.add(copyLinkOptionValue);
                    if (addCopyImage) patched.add(copyImageOptionValue);
                    if (addInfo) patched.add(mediaInfoOptionValue);
                    if (addSpeed) patched.add(speedOptionValue);
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
            // ReelDownloadHook adds DOWNLOAD to the reel menu (and unlocks IG's native row) when
            // REEL download is on. If we only consumed the tap for POST download, a reel tap with
            // only Reel download enabled fell through to Instagram's own save flow, which burns in
            // a watermark (#256). Route it to the module's clean downloader for either toggle.
            boolean reelMenu = reelClickDispatcher != null && param.method.equals(reelClickDispatcher);
            boolean isDownload = option.equals("DOWNLOAD")
                    && (reelMenu ? FeatureFlags.enableReelDownload : FeatureFlags.enablePostDownload);
            boolean isCopyLink = option.equals("COPY_LINK") && FeatureFlags.copyMediaLink;
            boolean isCopyImage = option.equals(COPY_IMAGE_CARRIER) && FeatureFlags.offerCopyImage;
            boolean isInfo = option.equals(MEDIA_INFO_CARRIER) && FeatureFlags.offerMediaInfo;
            boolean isSpeed = option.equals(SPEED_CARRIER) && FeatureFlags.offerPlaybackSpeed;
            if (!isDownload && !isCopyLink && !isCopyImage && !isInfo && !isSpeed) return;

            param.setResult(null); // consume the event

            Object thisObj = param.thisObject;

            Context ctx = findContext(thisObj);
            if (ctx == null) {
                ModuleLog.line("(IE|Post) ❌ Context not found in click handler");
                return;
            }

            if (isSpeed) { PlaybackSpeedHook.showChooser(ctx); return; }
            Object media;
            if (isDownload) {
                media = SelectedMediaAdapter.findPostMenuMedia(thisObj, menuCreatorClass);
                if (media == null && reelClickDispatcher != null && param.method.equals(reelClickDispatcher))
                    media = SelectedMediaAdapter.findReelMenuMedia(thisObj, reelClickDispatcher.getDeclaringClass());
            } else {
                media = findMediaViaMenuCreator(thisObj);
                if (media == null) media = findMedia(thisObj);
            }
            if (media == null) {
                ModuleLog.line("(IE|Post) ❌ Media not found in click handler");
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
                return;
            }

            if (isInfo) { MediaInfo.show(ctx, media, thisObj); return; }
            if (isCopyLink)       triggerCopyLink(ctx, media, thisObj);
            else if (isCopyImage) triggerCopyImage(ctx, media, thisObj);
            else                  triggerDownload(ctx, media, thisObj);
        } catch (Throwable t) {
            ModuleLog.line("(IE|Post) ❌ onOptionClicked: " + t);
        }
    }

    // ── Download dispatch ─────────────────────────────────────────────────────

    private static void triggerDownload(Context ctx, Object media, Object clickHandler) {
        SelectedMediaDownloads.captureAndShowPost(ctx, media, "post");
    }

    // ── Copy image (#228) ─────────────────────────────────────────────────────
    //
    // Copies the photo being viewed (carousel: the visible slide, resolved the same way as
    // Download) straight to the clipboard. Video slides have no still image to copy.

    private static void triggerCopyImage(Context ctx, Object media, Object clickHandler) {
        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        if (urls == null || urls.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
            return;
        }
        int viewIdx = urls.size() > 1 ? ReelDownloadHook.findCarouselIndexFromView(ctx, urls.size()) : -1;
        int idx = viewIdx >= 0 ? viewIdx : findCarouselIndex(clickHandler, urls.size());
        String url = urls.get(idx >= 0 && idx < urls.size() ? idx : 0);
        if (FeedVideoDownloadHook.isVideoUrl(url)) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_copy_image_video), Toast.LENGTH_SHORT).show();
            return;
        }
        ImageClipboard.copy(ctx, url);
    }

    // ── Copy Media Link (#117) ────────────────────────────────────────────────
    //
    // Copies the direct CDN url of the currently-visible slide to the clipboard.
    // Reuses the same URL extraction + carousel-index resolution as the downloader
    // so the copied link always matches the slide the user is looking at.

    private static void triggerCopyLink(Context ctx, Object media, Object clickHandler) {
        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
        if (urls == null || urls.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_copy_link_none), Toast.LENGTH_SHORT).show();
            return;
        }
        // Single URL copies straight to clipboard; a carousel shows a per-slide chooser
        // (see showCopyLinkSheet — the visible slide can't be resolved reliably in the feed).
        FeedVideoDownloadHook.mainHandler.post(() -> FeedVideoDownloadHook.showCopyLinkSheet(ctx, urls));
    }

    /**
     * Finds the current carousel slide index from the click handler object by scanning its int
     * fields (no obfuscated field name) for the first value in (0, urlCount) — only the index
     * field will be in that range. Slide 0 is the default when nothing matches.
     */
    static int findCarouselIndex(Object obj, int urlCount) {
        if (obj == null || urlCount <= 1) return 0;

        // Scan all int fields for a value that fits as a carousel index
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
