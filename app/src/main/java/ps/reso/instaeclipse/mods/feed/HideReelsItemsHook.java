package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Reels tab filters, applied while the clips list response is parsed so no empty pages appear:
 * <ul>
 *   <li>"Hide suggestions in Reels" (#298): drops {@code netego_media} units (suggested accounts,
 *       Threads / Meta AI cross-promo cards ...) from {@code items_with_ads}.</li>
 *   <li>"Hide paid partnerships" (#275): drops paid partnership / sponsored reels and
 *       {@code ad_media} units.</li>
 * </ul>
 *
 * <p>IG 452 parsers (found by their JSON keys, never by obfuscated name):
 * <ul>
 *   <li>ClipsItemsListResponse ({@code X.09TF}): "items_with_ads", "clips_special_response_type".
 *       Only parsed items nested in it are filtered.</li>
 *   <li>ClipsItemDictWithAds ({@code X.0A8a}): "netego_media", "ad_media", "qp_unit", "blending_unit".
 *       Union type: media / netego / ad / QP / ... - one is set.</li>
 *   <li>ClipsItemDict ({@code X.09Ta}): "direct_item_id", "direct_sent_timestamp" (the plain
 *       "items" list). Also used for DM reels and single-reel fetches, hence the list gate.</li>
 * </ul>
 * The list parser skips null items, so returning null drops the unit cleanly.
 */
public class HideReelsItemsHook {

    private static final String TAG = "(InstaEclipse | ReelsFilter): ";

    /** > 0 while a clips list response is being parsed on this thread. */
    private static final ThreadLocal<int[]> LIST_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** Class names of the netego / ad unit models (by their GraphQL type name strings). */
    private static volatile Set<String> netegoClasses = Collections.emptySet();
    private static volatile Set<String> adClasses = Collections.emptySet();

    /** Media field(s) per parsed item class, resolved once per class. */
    private static final ConcurrentHashMap<Class<?>, Field[]> MEDIA_FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> OBJECT_FIELDS = new ConcurrentHashMap<>();

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        PaidPartnershipFilter.install(bridge, classLoader);

        netegoClasses = classesUsing(bridge, "XDTNetEgoMediaItem");
        adClasses = classesUsing(bridge, "XDTAdMediaItem");
        if (netegoClasses.isEmpty()) ModuleLog.line(TAG + "⚠️ netego model not found");

        Set<Class<?>> listParsers = findParsers(bridge, classLoader, new String[][]{
                {"items_with_ads", "clips_special_response_type"},
                {"items_with_ads", "unable_to_load_chaining_media", "paging_info"}});
        Set<Class<?>> unionParsers = findParsers(bridge, classLoader, new String[][]{
                {"netego_media", "ad_media", "qp_unit"},
                {"netego_media", "ad_media"}});
        Set<Class<?>> dictParsers = findParsers(bridge, classLoader, new String[][]{
                {"direct_item_id", "direct_sent_timestamp", "ClipsItemDict"}});

        XC_MethodHook depth = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                LIST_DEPTH.get()[0]++;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int[] d = LIST_DEPTH.get();
                if (d[0] > 0) d[0]--;
            }
        };

        XC_MethodHook unionFilter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hideSuggestionsInReels && !FeatureFlags.hidePaidPartnerships) return;
                Object item = param.getResult();
                if (item == null) return;
                try {
                    if (shouldDropUnion(item)) param.setResult(null);
                } catch (Throwable ignored) {}
            }
        };

        XC_MethodHook dictFilter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hidePaidPartnerships || LIST_DEPTH.get()[0] <= 0) return;
                Object item = param.getResult();
                if (item == null) return;
                try {
                    if (PaidPartnershipFilter.shouldHide(mediaOf(item))) param.setResult(null);
                } catch (Throwable ignored) {}
            }
        };

        int lists = hookParsers(listParsers, depth);
        int unions = hookParsers(unionParsers, unionFilter);
        int dicts = lists > 0 ? hookParsers(dictParsers, dictFilter) : 0;
        ModuleLog.line(TAG + (unions > 0 ? "✅" : "❌") + " hooked list=" + lists + " union=" + unions + " dict=" + dicts);

        if (unions > 0) {
            if (FeatureFlags.hideSuggestionsInReels && !netegoClasses.isEmpty())
                FeatureStatusTracker.setHooked("HideSuggestionsInReels");
            if (FeatureFlags.hidePaidPartnerships && PaidPartnershipFilter.isReady())
                FeatureStatusTracker.setHooked("HidePaidPartnerships");
        }
    }

    private static boolean shouldDropUnion(Object item) {
        Object media = mediaOf(item);
        if (media != null) {
            // Paid partnerships are only dropped inside a clips list (never a lone parsed reel).
            return LIST_DEPTH.get()[0] > 0 && PaidPartnershipFilter.shouldHide(media);
        }
        // Only a media-or-unit item (declares a Media field) is ever dropped, never a container.
        if (mediaFields(item.getClass()).length == 0) return false;
        for (Field f : objectFields(item.getClass())) {
            Object v;
            try { v = f.get(item); } catch (Throwable t) { continue; }
            if (v == null) continue;
            String name = v.getClass().getName();
            if (FeatureFlags.hideSuggestionsInReels && netegoClasses.contains(name)) return true;
            if (FeatureFlags.hidePaidPartnerships && adClasses.contains(name)) return true;
        }
        return false;
    }

    static Object mediaOf(Object item) {
        for (Field f : mediaFields(item.getClass())) {
            try {
                Object m = f.get(item);
                if (m != null) return m;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Field[] mediaFields(Class<?> c) {
        Field[] cached = MEDIA_FIELDS.get(c);
        if (cached != null) return cached;
        List<Field> out = new java.util.ArrayList<>();
        for (Field f : SafeReflect.fields(c)) {
            if (!f.getType().getName().equals("com.instagram.feed.media.Media")) continue;
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) {}
        }
        Field[] arr = out.toArray(new Field[0]);
        MEDIA_FIELDS.put(c, arr);
        return arr;
    }

    /** Instance fields that can hold a unit model (not primitives, strings, numbers or Media). */
    private static Field[] objectFields(Class<?> c) {
        Field[] cached = OBJECT_FIELDS.get(c);
        if (cached != null) return cached;
        List<Field> out = new java.util.ArrayList<>();
        for (Field f : SafeReflect.fields(c)) {
            Class<?> t = f.getType();
            if (t.isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String n = t.getName();
            if (n.startsWith("java.") || n.equals("com.instagram.feed.media.Media")) continue;
            try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) {}
        }
        Field[] arr = out.toArray(new Field[0]);
        OBJECT_FIELDS.put(c, arr);
        return arr;
    }

    /** Declaring classes of methods that use this exact string (the model's GraphQL type name). */
    private static Set<String> classesUsing(DexKitBridge bridge, String typeName) {
        Set<String> out = new HashSet<>();
        try {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().usingEqStrings(List.of(typeName))))) {
                out.add(md.getClassName());
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ " + typeName + " lookup failed: " + t.getMessage());
        }
        return out;
    }

    /**
     * JSON parser classes (bridge parse methods) whose parse method uses all strings of one of
     * the queries. Every query is tried; all distinct matches are kept.
     */
    private static Set<Class<?>> findParsers(DexKitBridge bridge, ClassLoader classLoader, String[][] queries) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (String[] strings : queries) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                        MethodMatcher.create().usingEqStrings(List.of(strings))))) {
                    try {
                        Class<?> c = Class.forName(md.getClassName(), false, classLoader);
                        if (hasParseBridge(c)) out.add(c);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ query failed: " + t.getMessage());
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** Bridge (Object parse(JsonParser)) methods: one parameter, Object result. */
    private static boolean hasParseBridge(Class<?> c) {
        for (Method m : SafeReflect.methods(c)) {
            if (isParseBridge(m)) return true;
        }
        return false;
    }

    private static boolean isParseBridge(Method m) {
        return m.isBridge() && m.getParameterCount() == 1 && m.getReturnType() == Object.class
                && !m.getParameterTypes()[0].getName().startsWith("java.");
    }

    private static int hookParsers(Set<Class<?>> classes, XC_MethodHook hook) {
        int n = 0;
        for (Class<?> c : classes) {
            for (Method m : SafeReflect.methods(c)) {
                if (!isParseBridge(m)) continue;
                try {
                    XposedBridge.hookMethod(m, hook);
                    n++;
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "⚠️ hook failed " + c.getName() + "." + m.getName() + ": " + t.getMessage());
                }
            }
        }
        return n;
    }
}
