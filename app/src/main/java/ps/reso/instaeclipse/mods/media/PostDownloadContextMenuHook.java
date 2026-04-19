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
    }

    // ── Step 1: MediaOption$Option.DOWNLOAD ──────────────────────────────────

    private static void loadMediaOptionEnum(ClassLoader cl) {
        try {
            mediaOptionEnumClass = cl.loadClass(
                    "com.instagram.feed.media.mediaoption.MediaOption$Option");
            Object[] values = (Object[]) mediaOptionEnumClass.getMethod("values").invoke(null);
            for (Object v : values) {
                if (downloadOptionValue == null && v.toString().equals("DOWNLOAD")) {
                    downloadOptionValue = v;
                }
            }
            if (downloadOptionValue == null)
                XposedBridge.log("(IE|Post) ❌ DOWNLOAD enum value not found");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Post) ❌ loadMediaOptionEnum: " + t);
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
                XposedBridge.log("(IE|Post) ❌ MediaOptionsOverflowMenuCreator class not found");
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
                XposedBridge.log("(IE|Post) ❌ addButtonMethod not found in " + creatorClassName);
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
            XposedBridge.log("(IE|Post) ❌ findCreatorClassAndAddButtonMethod: " + t);
        }
    }

    // ── Hook A: intercept every addButton call, inject Download once per menu ─

    private static void installAddButtonHook() {
        if (addButtonMethod == null || downloadOptionValue == null || enumNormalValue == null) {
            XposedBridge.log("(IE|Post) ❌ Cannot install addButton hook — prerequisites missing");
            return;
        }

        XposedBridge.hookMethod(addButtonMethod, new XC_MethodHook() {

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(sAddingDownload.get())) return;
                if (!FeatureFlags.enablePostDownload) return;
                // Suppress Instagram's own native DOWNLOAD button — we add ours instead
                if (param.args[idxOption] == downloadOptionValue) param.setResult(null);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enablePostDownload) return;
                if (Boolean.TRUE.equals(sAddingDownload.get())) return;
                if (param.args[idxOption] == downloadOptionValue) return;

                Object self = param.args[idxSelf];
                synchronized (processedCreators) {
                    if (processedCreators.contains(self)) return;
                    processedCreators.add(self);
                }

                Object[] callArgs = new Object[addButtonMethod.getParameterCount()];
                System.arraycopy(param.args, 0, callArgs, 0, callArgs.length);
                callArgs[idxEnum]   = enumNormalValue;
                callArgs[idxOption] = downloadOptionValue;
                callArgs[idxText]   = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_dl_title);

                sAddingDownload.set(true);
                try {
                    addButtonMethod.invoke(null, callArgs);
                } catch (Throwable t) {
                    XposedBridge.log("(IE|Post) ❌ addButton invoke failed: " + t);
                } finally {
                    sAddingDownload.set(false);
                }
            }
        });

        FeatureStatusTracker.setHooked("PostDownload");
        XposedBridge.log("(IE|Post) ✅ Post download hook installed");
    }

    // ── Hook B: click handler ─────────────────────────────────────────────────
    //
    // DexKit finds void methods with sole param MediaOption$Option — a stable unobfuscated type.
    // No string constants used, so obfuscation of surrounding code doesn't matter.

    private static void installClickHandlerHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enablePostDownload) return;
                onOptionClicked(param);
            }
        };

        // Cache hit: restore all previously-found click handler methods
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("PostDownload_click", classLoader);
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

            List<Method> hooked = new ArrayList<>();
            for (MethodData md : results) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, clickHook);
                    hooked.add(m);
                } catch (Throwable t) {
                    XposedBridge.log("(IE|Post) ❌ Failed to hook click candidate: " + t);
                }
            }

            if (hooked.isEmpty()) {
                XposedBridge.log("(IE|Post) ❌ No click handler methods could be hooked");
            } else {
                DexKitCache.saveMethods("PostDownload_click", hooked);
            }

        } catch (Throwable t) {
            XposedBridge.log("(IE|Post) ❌ installClickHandlerHook: " + t);
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
                    if (a != null && a.getClass().isEnum() && a.toString().contains("DOWNLOAD")) {
                        clicked = a; break;
                    }
                }
            }

            if (clicked == null || !clicked.toString().equals("DOWNLOAD")) return;

            param.setResult(null); // consume the event

            Context ctx = findContext(param.thisObject);
            if (ctx == null) {
                XposedBridge.log("(IE|Post) ❌ Context not found in click handler");
                return;
            }

            Object media = findMedia(param.thisObject);
            if (media == null) {
                XposedBridge.log("(IE|Post) ❌ Media not found in click handler");
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_no_media_for_post), Toast.LENGTH_SHORT).show();
                return;
            }

            triggerDownload(ctx, media, param.thisObject);
        } catch (Throwable t) {
            XposedBridge.log("(IE|Post) ❌ onOptionClicked: " + t);
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

        // Carousel index: try the known field name first; fall back to scanning all int fields
        // for one whose value fits [0, urlCount) — resilient to field renames across builds.
        int carouselIdx = findCarouselIndex(clickHandler, urls.size());

        final String finalUser = username;
        final String finalId   = mediaId;
        FeedVideoDownloadHook.mainHandler.post(() ->
                FeedVideoDownloadHook.showPostDownloadDialog(ctx, urls, finalUser, finalId, carouselIdx));
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
