package ps.reso.instaeclipse.mods.media;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Playback speed for feed videos and a forced reel seek bar (#247).
 *
 * Feed: IG's feed player (class using "IgFeedVideoPlayer: Persistent Visible Cover Image",
 * X.05fU on 452) starts a video via its (Media, …)boolean method; we keep the last instance.
 * The underlying Groot player's speed setter is the (float) caller of the method that uses
 * "setPlaybackSpeed" (X.01Nh.A0L → X.01Om.A0R on 452) — the same path IG's own hold-for-2x uses.
 * The chosen speed is re-applied to every video that starts afterwards (1x = off).
 *
 * Reels: IG's own attached scrubber is gated by ClipsExperimentUtil.shouldShowAttachedScrubber
 * ((UserSession, long, boolean, boolean)boolean using its purge-flag string); forced true.
 */
public class PlaybackSpeedHook {

    private static final String TAG = "(InstaEclipse | PlaybackSpeed): ";
    static final float[] SPEEDS = {0.5f, 1f, 1.25f, 1.5f, 2f, 3f};
    private static volatile float speed = 1f;
    private static volatile WeakReference<Object> lastPlayer;
    private static final List<Method> SETTERS = new ArrayList<>();

    public void install(DexKitBridge bridge, ClassLoader cl) {
        installFeed(bridge, cl);
        installReelScrubber(bridge, cl);
    }

    private void installFeed(DexKitBridge bridge, ClassLoader cl) {
        try {
            List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods("PlaybackSpeed_setters", cl) : null;
            Method start = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("PlaybackSpeed_start", cl) : null;
            if (cached == null || cached.isEmpty() || start == null) {
                List<ClassData> players = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings("IgFeedVideoPlayer: Persistent Visible Cover Image")));
                start = null;
                for (ClassData cd : players) {
                    for (Method m : cd.getInstance(cl).getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length > 0 && p[0].getName().equals("com.instagram.feed.media.Media")
                                && m.getReturnType() == boolean.class && !Modifier.isStatic(m.getModifiers())) {
                            start = m;
                            break;
                        }
                    }
                    if (start != null) break;
                }
                cached = new ArrayList<>();
                for (MethodData sp : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("setPlaybackSpeed")))) {
                    for (MethodData caller : sp.getCallers()) {
                        try {
                            Method m = caller.getMethodInstance(cl);
                            Class<?>[] p = m.getParameterTypes();
                            if (p.length == 1 && p[0] == float.class && !Modifier.isStatic(m.getModifiers())) cached.add(m);
                        } catch (Throwable ignored) {}
                    }
                }
                if (start == null || cached.isEmpty()) {
                    ModuleLog.line(TAG + "❌ feed player start=" + start + " setters=" + cached.size());
                    return;
                }
                DexKitCache.saveMethod("PlaybackSpeed_start", start);
                DexKitCache.saveMethods("PlaybackSpeed_setters", cached);
            }
            for (Method m : cached) { m.setAccessible(true); SETTERS.add(m); }
            XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    lastPlayer = new WeakReference<>(param.thisObject);
                    if (FeatureFlags.offerPlaybackSpeed && speed != 1f) {
                        Object p = param.thisObject;
                        FeedVideoDownloadHook.mainHandler.postDelayed(() -> apply(p, speed), 400);
                    }
                }
            });
            // Autoplaying feed videos don't always pass through the start method above; the player
            // impl's "Playback started " callback fires for whichever video actually starts.
            Method started = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("PlaybackSpeed_started", cl) : null;
            if (started == null) {
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("Playback started ")))) {
                    Method m = md.getMethodInstance(cl);
                    if (!Modifier.isStatic(m.getModifiers())) { started = m; break; }
                }
                if (started != null) DexKitCache.saveMethod("PlaybackSpeed_started", started);
            }
            if (started != null) {
                XposedBridge.hookMethod(started, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        lastPlayer = new WeakReference<>(param.thisObject);
                        if (FeatureFlags.offerPlaybackSpeed && speed != 1f) apply(param.thisObject, speed);
                    }
                });
            }
            ModuleLog.line(TAG + "✅ feed player " + start.getDeclaringClass().getName() + "." + start.getName()
                    + ", started-cb " + (started != null ? started.getDeclaringClass().getName() + "." + started.getName() : "none")
                    + ", setters " + SETTERS.size());
            FeatureStatusTracker.setHooked("PlaybackSpeed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ feed: " + t);
        }
    }

    private void installReelScrubber(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method gate = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("PlaybackSpeed_scrubberGate", cl) : null;
            if (gate == null) {
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("android_purge_26_q2_ClipsExperimentUtil_shouldShowAttachedScrubber")
                        .returnType("boolean")))) {
                    Method m = md.getMethodInstance(cl);
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4 && p[1] == long.class) { gate = m; break; }
                }
                if (gate == null) {
                    ModuleLog.line(TAG + "⚠️ reel scrubber gate not found");
                    return;
                }
                DexKitCache.saveMethod("PlaybackSpeed_scrubberGate", gate);
            }
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.forceReelScrubber) param.setResult(true);
                }
            });
            ModuleLog.line(TAG + "✅ reel scrubber gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ reel scrubber: " + t);
        }
    }

    /** Speed chooser from the post ⋯ menu; applies to the playing feed video and the next ones. */
    static void showChooser(Context ctx) {
        String[] labels = new String[SPEEDS.length];
        int checked = 1;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = (SPEEDS[i] == (int) SPEEDS[i] ? String.valueOf((int) SPEEDS[i]) : String.valueOf(SPEEDS[i])) + "×";
            if (SPEEDS[i] == speed) checked = i;
        }
        android.app.Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        Context host = act != null && !act.isFinishing() ? act : ctx;
        new AlertDialog.Builder(new ContextThemeWrapper(host, android.R.style.Theme_DeviceDefault_Dialog_Alert))
                .setTitle(I18n.t(ctx, R.string.ig_playback_speed_title))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    speed = SPEEDS[which];
                    WeakReference<Object> ref = lastPlayer;
                    Object p = ref != null ? ref.get() : null;
                    boolean ok = p != null && apply(p, speed);
                    if (!ok) Toast.makeText(ctx, I18n.t(ctx, R.string.ig_playback_speed_next), Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .show();
    }

    /** Finds the Groot player reachable from the feed player and sets its speed. */
    private static boolean apply(Object feedPlayer, float s) {
        Object target = find(feedPlayer, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (target == null) {
            ModuleLog.line(TAG + "⚠️ no player object for speed");
            return false;
        }
        for (Method m : SETTERS) {
            if (!m.getDeclaringClass().isInstance(target)) continue;
            try {
                m.invoke(target, s);
                ModuleLog.line(TAG + "speed " + s + "× via " + m.getDeclaringClass().getName() + "." + m.getName());
                return true;
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ set speed: " + t);
            }
        }
        return false;
    }

    private static Object find(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 3 || !seen.add(o)) return null;
        for (Method m : SETTERS) if (m.getDeclaringClass().isInstance(o)) return o;
        String n = o.getClass().getName();
        if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object r = find(f.get(o), depth + 1, seen);
                    if (r != null) return r;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
