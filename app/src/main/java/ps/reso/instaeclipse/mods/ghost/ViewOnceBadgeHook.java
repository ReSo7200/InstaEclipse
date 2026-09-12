package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Restores Instagram's NATIVE view-once / view-twice corner icon that the "Show View-Once/Twice
 * as Normal Media" feature (permanentViewMode) hides by rewriting view_mode -> "permanent".
 *
 * Render path (447): the visual message's render-props object (X/0E4S) carries the view_mode
 * String; the composable X/0YKc.A00 (RavenCornerIcon) draws the once vs twice icon based on it.
 * Because permanentViewMode rewrites the model's view_mode to "permanent" at parse, by the time
 * the props are built the mode is "permanent" and no icon is drawn.
 *
 * Fix: GhostPermanentViewHook stashes the ORIGINAL "once"/"replayable" (keyed by media id) before
 * the rewrite. Here we hook the props constructor (anchored on a ctor that inlines BOTH the "once"
 * and "replayable" strings — that is 0E4S), and when its mode is "permanent" and one of its
 * id-like String fields matches the stash, we put the original mode back so IG draws its own icon.
 *
 * Gated on permanentViewMode (the icon is only missing when that feature is active).
 * NOTE: this build carries PROBE logging to confirm field layout / id-matching on-device.
 */
public class ViewOnceBadgeHook {

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = buildHook();

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ViewOnceBadge", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.line("(IE|VOBadge) ✅ hooked (cached): "
                        + cached.getDeclaringClass().getName());
                FeatureStatusTracker.setHooked("PermanentViewMode");
                return;
            }
        }

        try {
            // The render-props ctor inlines BOTH "once" and "replayable" to compute its
            // "isRaven" flag. RavenCornerIcon (0YKc.A00) also references both, so require <init>.
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .name("<init>")
                            .usingStrings("once", "replayable")));
            if (methods.isEmpty()) {
                ModuleLog.line("(IE|VOBadge) ❌ view-once props ctor not found");
                return;
            }
            // PROBE build: hook EVERY match (ctor or method). The body is gated on
            // permanentViewMode and only logs / restores, so hooking several is harmless and
            // tells us which class actually carries the view_mode we must restore.
            int hooked = 0;
            for (MethodData md : methods) {
                try {
                    Member member = md.isConstructor()
                            ? md.getConstructorInstance(classLoader)
                            : md.getMethodInstance(classLoader);
                    XposedBridge.hookMethod((java.lang.reflect.Member) member, hook);
                    ModuleLog.line("(IE|VOBadge) ✅ hooked " + md.getClassName()
                            + (md.isConstructor() ? ".<init>" : "." + md.getName()));
                    hooked++;
                } catch (Throwable e) {
                    ModuleLog.line("(IE|VOBadge) ⚠️ skip " + md.getClassName() + ": " + e);
                }
            }
            if (hooked == 0) {
                ModuleLog.line("(IE|VOBadge) ❌ props ctor found but none reflectable");
                return;
            }
            FeatureStatusTracker.setHooked("PermanentViewMode");
        } catch (Throwable t) {
            ModuleLog.line("(IE|VOBadge) ❌ " + t);
        }
    }

    private XC_MethodHook buildHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.permanentViewMode) return;
                Object[] args = param.args;
                if (args == null) return;
                try {
                    // GhostPermanentViewHook rewrote the model's view_mode to "permanent" (both
                    // types, so IG keeps it re-viewable). Map it BACK to the real ephemeral value
                    // here (render props only) so IG's own ctor computes the ephemeral-gate boolean
                    // and draws the correct native 1-vs-2 badge. The original once/twice type is
                    // looked up from ORIGINAL_BY_KEY via an id/timestamp long shared with the model.
                    int modeIdx = -1;
                    for (int i = 0; i < args.length; i++) {
                        if (GhostPermanentViewHook.PERMANENT.equals(args[i])) { modeIdx = i; break; }
                    }
                    if (modeIdx < 0) return; // not a rewritten view-once bubble

                    java.util.List<Long> candidates = new java.util.ArrayList<>();
                    collectLongs(args, candidates, 0,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                    String original = null;
                    for (Long k : candidates) {
                        String hit = GhostPermanentViewHook.ORIGINAL_BY_KEY.get(k);
                        if (hit != null) { original = hit; break; }
                    }
                    args[modeIdx] = (original != null) ? original : "once";
                    ModuleLog.line("(IE|VOBadge|PROBE) resolved=" + args[modeIdx]
                            + " match=" + (original != null) + " candLongs=" + candidates);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|VOBadge) ❌ hook body: " + t);
                }
            }
        };
    }

    /** Collect id/timestamp longs from the ctor args graph (shallow) as join-key candidates. */
    private static void collectLongs(Object obj, java.util.List<Long> out, int depth, java.util.Set<Object> seen) {
        if (obj == null || depth > 4 || out.size() > 60 || !seen.add(obj)) return;
        if (obj instanceof Object[]) {
            for (Object e : (Object[]) obj) collectLongs(e, out, depth + 1, seen);
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
                        // media ids often arrive as numeric strings (e.g. ExtendedImageUrl.A07)
                        String s = (String) f.get(obj);
                        if (s != null && s.length() >= 8 && s.length() <= 22 && s.chars().allMatch(Character::isDigit)) {
                            try { long v = Long.parseLong(s); if (!out.contains(v)) out.add(v); } catch (NumberFormatException ignored) {}
                        }
                    } else if (!t.isPrimitive()) {
                        collectLongs(f.get(obj), out, depth + 1, seen);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}
