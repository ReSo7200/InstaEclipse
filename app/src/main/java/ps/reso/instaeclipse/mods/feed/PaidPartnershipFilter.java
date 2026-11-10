package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Decides whether a parsed Media is a paid partnership or a sponsored (boosted) post (#275).
 *
 * <p>The Media getters are found by the JSON keys they read: {@code is_paid_partnership}
 * (Boolean), {@code sponsor_tags} (List) and {@code injected} (the ad payload a boosted post
 * carries). Owner is Media on IG 447+, LiveTreeMediaDict on older builds (reached through the
 * Media field of that type), as for the has_liked getter.
 *
 * <p>Reels the user asked for by id ({@code clips/items/?clips_media_ids=...}: a reel opened from
 * DMs, a profile, a share link) are remembered and never dropped.
 */
public final class PaidPartnershipFilter {

    private static final String TAG = "(InstaEclipse | PaidPartnership): ";
    private static final String[] OWNERS = {"com.instagram.feed.media.Media",
            "com.instagram.feed.media.LiveTreeMediaDict"};

    private static volatile Method paidGetter;     // is_paid_partnership -> Boolean
    private static volatile Method sponsorGetter;  // sponsor_tags -> List
    private static volatile Method injectedGetter; // injected -> ad payload (boosted / sponsored)
    private static volatile Method idGetter;       // Media.getId() -> "pk_ownerId"
    private static volatile boolean resolved;

    /** Media pks requested by id (opened reels); bounded, newest kept. */
    private static final Map<String, Boolean> OPENED = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 200;
        }
    };

    private PaidPartnershipFilter() {}

    /** Idempotent: resolves the getters and hooks the "reels by id" request builders. */
    public static synchronized void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (resolved) return;
        resolved = true;
        paidGetter = resolveGetter(bridge, classLoader, "PaidPartnershipGetter", "is_paid_partnership", "java.lang.Boolean");
        sponsorGetter = resolveGetter(bridge, classLoader, "SponsorTagsGetter", "sponsor_tags", "java.util.List");
        injectedGetter = resolveGetter(bridge, classLoader, "MediaInjectedGetter", "injected", null);
        idGetter = resolveId(bridge, classLoader);
        hookOpenedRequests(bridge, classLoader);
    }

    /** True when at least one of the getters was found. */
    public static boolean isReady() {
        return paidGetter != null || sponsorGetter != null || injectedGetter != null;
    }

    /** True for a paid partnership / sponsored media that was not opened on purpose. */
    public static boolean shouldHide(Object media) {
        if (media == null || !FeatureFlags.hidePaidPartnerships) return false;
        try {
            if (!isPaidOrSponsored(media)) return false;
            return !isOpened(media);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isPaidOrSponsored(Object media) {
        Object v = call(paidGetter, media);
        if (Boolean.TRUE.equals(v)) return true;
        v = call(sponsorGetter, media);
        if (v instanceof Collection && !((Collection<?>) v).isEmpty()) return true;
        return call(injectedGetter, media) != null;
    }

    private static boolean isOpened(Object media) {
        Method g = idGetter;
        if (g == null) return false;
        Object id = call(g, media);
        if (!(id instanceof String)) return false;
        String s = (String) id;
        int cut = s.indexOf('_');
        String pk = cut > 0 ? s.substring(0, cut) : s;
        synchronized (OPENED) {
            return OPENED.containsKey(pk);
        }
    }

    /** Calls a getter that may live on Media or on a field of Media (LiveTreeMediaDict). */
    private static Object call(Method getter, Object media) {
        if (getter == null || media == null) return null;
        try {
            Object target = media;
            Class<?> owner = getter.getDeclaringClass();
            if (!owner.isInstance(media)) {
                target = null;
                for (Field f : SafeReflect.fields(media.getClass())) {
                    if (!owner.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    target = f.get(media);
                    break;
                }
                if (target == null) return null;
            }
            return getter.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The media's id getter, found through DexKit (declaredClass + name + no params), cached like the
     * other anchors. {@code Media.getId()} is a stable, unobfuscated method, so the direct lookup is a
     * last-resort fallback only; it is never anchored by an obfuscated name.
     */
    private static Method resolveId(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod("MediaIdGetter", classLoader);
                if (cached != null) return cached;
            }
            for (String owner : OWNERS) {
                try {
                    List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                            MethodMatcher.create().declaredClass(owner).name("getId")
                                    .paramCount(0).returnType("java.lang.String")));
                    for (MethodData md : found) {
                        Method m = md.getMethodInstance(classLoader);
                        m.setAccessible(true);
                        DexKitCache.saveMethod("MediaIdGetter", m);
                        ModuleLog.line(TAG + "✅ id getter " + owner + "." + m.getName());
                        return m;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ id getter: " + t.getMessage());
        }
        try {
            Method m = Class.forName(OWNERS[0], false, classLoader).getMethod("getId");
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ Media.getId not found");
            return null;
        }
    }

    private static Method resolveGetter(DexKitBridge bridge, ClassLoader classLoader, String cacheKey,
                                        String jsonKey, String returnType) {
        try {
            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod(cacheKey, classLoader);
                if (cached != null) return cached;
            }
            for (String owner : OWNERS) {
                List<MethodData> found;
                try {
                    MethodMatcher mm = MethodMatcher.create()
                            .declaredClass(owner)
                            .usingEqStrings(List.of(jsonKey))
                            .paramCount(0);
                    if (returnType != null) mm.returnType(returnType);
                    found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(mm));
                } catch (Throwable t) { continue; }
                Method hit = null;
                int matches = 0;
                for (MethodData md : found) {
                    Method m;
                    try { m = md.getMethodInstance(classLoader); } catch (Throwable t) { continue; }
                    if (m.getReturnType().isPrimitive()) continue; // void / boolean helpers
                    hit = m;
                    matches++;
                }
                if (matches != 1) hit = null; // none or ambiguous: try the next owner
                if (hit == null) continue;
                hit.setAccessible(true);
                DexKitCache.saveMethod(cacheKey, hit);
                ModuleLog.line(TAG + "✅ " + jsonKey + " getter " + owner + "." + hit.getName());
                return hit;
            }
            ModuleLog.line(TAG + "❌ " + jsonKey + " getter not found");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + jsonKey + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * Records the media ids of every "reels by id" request (IG 452: ClipsApiUtilHelper builders
     * that put {@code clips_media_ids}). Those responses go through the same list parser as
     * the Reels tab, so without this the reel the user opened could be filtered out.
     */
    private static void hookOpenedRequests(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook record = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hidePaidPartnerships || param.args == null) return;
                for (Object a : param.args) {
                    if (a instanceof String) remember((String) a);
                    else if (a instanceof Collection) {
                        for (Object o : (Collection<?>) a) if (o instanceof String) remember((String) o);
                    }
                }
            }
        };
        int n = 0;
        // Request builders put both strings; the lone key is a fallback for other builds.
        List<List<String>> queries = List.of(List.of("clips/items/", "clips_media_ids"), List.of("clips_media_ids"));
        for (List<String> strings : queries) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                        MethodMatcher.create().usingEqStrings(strings)))) {
                    if (n >= 16) break;
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (!hasStringParam(m)) continue; // skip string tables and other helpers
                        XposedBridge.hookMethod(m, record);
                        n++;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ clips_media_ids lookup failed: " + t.getMessage());
            }
            if (n > 0) break;
        }
        ModuleLog.line(TAG + (n > 0 ? "✅ " : "⚠️ ") + n + " reels-by-id request builders hooked");
    }

    private static boolean hasStringParam(Method m) {
        for (Class<?> p : m.getParameterTypes()) {
            if (p == String.class || Collection.class.isAssignableFrom(p)) return true;
        }
        return false;
    }

    /** Keeps every run of 6+ digits (media pks, possibly in a JSON/comma list). */
    static void remember(String s) {
        int i = 0, len = s.length();
        while (i < len) {
            if (!Character.isDigit(s.charAt(i))) { i++; continue; }
            int start = i;
            while (i < len && Character.isDigit(s.charAt(i))) i++;
            if (i - start >= 6) {
                synchronized (OPENED) {
                    OPENED.put(s.substring(start, i), Boolean.TRUE);
                }
            }
        }
    }
}
