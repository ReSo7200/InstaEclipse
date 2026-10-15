package ps.reso.instaeclipse.mods.misc;

import android.content.res.Resources;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Exact timestamps (#232): "3 hours ago" / "92 weeks ago" / "3w" → "20/09/2026 16:32".
 *
 * Every relative timestamp IG renders (feed, comments, stories, DMs) goes through one static
 * formatter in the time-utils class (X.03A7 on 451, X.03do on 447, X.03ib on 436):
 * {@code static String A08(Resources, Enum style, <self>, Integer, double a, double b, boolean×4)}.
 * It formats {@code b - a} seconds. For "ago" texts a = the timestamp and b = now; for countdowns
 * ("x left") the order is flipped (a = now, b = future), which we leave alone.
 *
 * Anchor: the class is the only one using "%d:%02d:%02d", "MMMM d" and "MMM d" (436/447/451);
 * the method is picked by shape, not name.
 */
public class ExactTimestampHook {

    private static final String TAG = "(InstaEclipse | ExactTime): ";
    private static final String CACHE_KEY = "ExactTimestampFormatter";

    public void install(DexKitBridge bridge) {
        Method target = null;
        if (DexKitCache.isCacheValid()) target = DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader);

        if (target == null) {
            try {
                List<ClassData> classes = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create().usingStrings("%d:%02d:%02d", "MMMM d", "MMM d")));
                for (ClassData cd : classes) {
                    target = findFormatter(cd.getInstance(Module.hostClassLoader));
                    if (target != null) break;
                }
                if (target == null) {
                    ModuleLog.line(TAG + "❌ formatter not found (" + classes.size() + " candidate classes)");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, target);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ " + t);
                return;
            }
        }

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.exactTimestamps) return;
                try {
                    double a = (Double) param.args[4];
                    double b = (Double) param.args[5];
                    double now = System.currentTimeMillis() / 1000d;
                    // Only "time ago": b is "now" and a lies in the past. Countdowns/durations untouched.
                    if (Math.abs(b - now) > 300 || a <= 0 || a > b) return;
                    Locale locale = Locale.getDefault();
                    if (param.args[0] instanceof Resources res) {
                        locale = res.getConfiguration().getLocales().get(0);
                    }
                    DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
                    param.setResult(f.format(new Date((long) (a * 1000))));
                } catch (Throwable ignored) {
                }
            }
        });
        ModuleLog.line(TAG + "✅ Hooked " + target.getDeclaringClass().getName() + "." + target.getName());
        FeatureStatusTracker.setHooked("ExactTimestamps");
    }

    /** static String m(Resources, Enum, ownClass, Integer, double, double, boolean, boolean, boolean, boolean) */
    private static Method findFormatter(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != String.class || p.length != 10) continue;
            if (p[0] != Resources.class || p[3] != Integer.class
                    || p[4] != double.class || p[5] != double.class) continue;
            boolean flags = true;
            for (int i = 6; i < 10; i++) flags &= p[i] == boolean.class;
            if (flags) return m;
        }
        return null;
    }
}
