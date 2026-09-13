package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Makes view-once and view-twice (replayable) media behave like permanent media.
 *
 * Instagram parses the server's JSON "view_mode" field in unsafeParseFromJson
 * (class X/1Ui in build 423) and stores it as a plain String on the media model.
 *
 * Possible values:
 *   "once"       — view once (disappears after one open)
 *   "replayable" — view twice (one extra replay allowed)
 *   "permanent"  — normal media, always accessible
 *
 * We hook the parser method after it runs and replace any non-permanent
 * view_mode value with "permanent" so Instagram renders the media normally.
 *
 * DexKit fingerprint: method using both "archived_media_timestamp" AND "view_mode"
 * with exactly 1 parameter (the JSON reader). This distinguishes it from the
 * companion serializer method in the same class which has 2 parameters.
 */
public class GhostPermanentViewHook {

    // We rewrite the view_mode to a non-ephemeral value so IG shows the media as permanent /
    // re-viewable. To let ViewOnceBadgeHook still restore IG's native once-vs-twice corner badge,
    // we encode the ORIGINAL type in the value we write: view-once -> PERMANENT_ONCE, view-twice
    // (replayable) -> PERMANENT_TWICE. IG treats any value that is not exactly "once"/"replayable"
    // as non-ephemeral, so both markers get the permanent behavior; ViewOnceBadgeHook maps them
    // back to "once"/"replayable" only at the DM render props so the correct badge is drawn.
    // IG's re-viewable/no-consume behavior requires the view_mode to be EXACTLY "permanent" (a
    // different marker makes view-twice consume again). So both types get "permanent"; the original
    // once-vs-twice type is carried to the render side via ORIGINAL_BY_KEY, keyed by any id/timestamp
    // long on the media model that the DM render props (0E4S) also expose.
    public static final String PERMANENT = "permanent";
    public static final java.util.Map<Long, String> ORIGINAL_BY_KEY =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ViewOnceMedia", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, buildHook());
                FeatureStatusTracker.setHooked("PermanentViewMode");
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("archived_media_timestamp", "view_mode")
                            .paramCount(1)));

            if (methods.isEmpty()) {
                ModuleLog.line("(IE|ViewOnceMedia) ❌ unsafeParseFromJson not found");
                return;
            }

            // Pick the method whose return type is not void (the parser returns the model object;
            // the serializer returns void). Fall back to the first candidate if none match.
            Method target = null;
            for (MethodData md : methods) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() != void.class) {
                        target = m;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (target == null) {
                // Fallback: just use the first found
                try {
                    target = methods.get(0).getMethodInstance(classLoader);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|ViewOnceMedia) ❌ Could not resolve method: " + t);
                    return;
                }
            }

            ModuleLog.line("(IE|ViewOnceMedia) ✅ hooking "
                    + target.getDeclaringClass().getName() + "." + target.getName());

            DexKitCache.saveMethod("ViewOnceMedia", target);
            XposedBridge.hookMethod(target, buildHook());

            FeatureStatusTracker.setHooked("PermanentViewMode");
            ModuleLog.line("(IE|ViewOnceMedia) ✅ hooked");

        } catch (Throwable t) {
            ModuleLog.line("(IE|ViewOnceMedia) ❌ " + t);
        }
    }

    private static XC_MethodHook buildHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.permanentViewMode) return;
                Object result = param.getResult();
                if (result == null) return;

                // Pass 1: read seen_count (small int field) so we don't "un-consume" media whose
                // CDN URL is already gone; collect id/timestamp keys from the object graph (the
                // parsed model X/02b7 holds a nested Media whose id also appears on the render side
                // as ExtendedImageUrl.A07 — that shared id is our once/twice join key).
                int seenCount = 0;
                java.util.List<Long> keys = new java.util.ArrayList<>();
                Class<?> cls = result.getClass();
                while (cls != null && cls != Object.class) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (f.getType() != int.class) continue;
                        f.setAccessible(true);
                        try { seenCount = f.getInt(result); } catch (Throwable ignored) {}
                    }
                    cls = cls.getSuperclass();
                }
                collectIdKeys(result, keys, 0,
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

                // Pass 2: rewrite the view_mode to "permanent" (both types) so IG shows it as
                // re-viewable media; stash the original once/twice type by each key for the render side.
                cls = result.getClass();
                while (cls != null && cls != Object.class) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (f.getType() != String.class) continue;
                        f.setAccessible(true);
                        try {
                            String val = (String) f.get(result);
                            String original = null;
                            if ("once".equals(val)) {
                                if (seenCount >= 1) return; // already viewed — URL gone
                                original = "once";
                            } else if ("replayable".equals(val) || "allow_replay".equals(val)) {
                                if (seenCount >= 2) return; // replayable allows 2 views
                                original = "replayable";
                            }
                            if (original != null) {
                                for (Long k : keys) ORIGINAL_BY_KEY.put(k, original);
                                ModuleLog.line("(IE|ViewOnceMedia) mode=" + original + " keys=" + keys);
                                f.set(result, PERMANENT);
                            }
                        } catch (Throwable ignored) {}
                    }
                    cls = cls.getSuperclass();
                }
            }
        };
    }

    /**
     * Walks the parsed model's object graph (depth-limited) collecting id/timestamp longs and
     * numeric-string ids (e.g. the nested Media / ExtendedImageUrl media id). These are the keys
     * the render side (ViewOnceBadgeHook) also derives, letting us join the original view-once type.
     */
    private static void collectIdKeys(Object obj, java.util.List<Long> out, int depth, java.util.Set<Object> seen) {
        if (obj == null || depth > 4 || out.size() > 60 || !seen.add(obj)) return;
        if (obj instanceof Object[]) {
            for (Object e : (Object[]) obj) collectIdKeys(e, out, depth + 1, seen);
            return;
        }
        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> t = f.getType();
                try {
                    if (t == long.class) {
                        long v = f.getLong(obj);
                        if (v != 0 && v != Long.MAX_VALUE && !out.contains(v)) out.add(v);
                    } else if (t == String.class) {
                        String s = (String) f.get(obj);
                        if (s != null && s.length() >= 8 && s.length() <= 22 && s.chars().allMatch(Character::isDigit)) {
                            try { long v = Long.parseLong(s); if (!out.contains(v)) out.add(v); } catch (NumberFormatException ignored) {}
                        }
                    } else if (!t.isPrimitive()) {
                        collectIdKeys(f.get(obj), out, depth + 1, seen);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}
