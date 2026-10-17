package ps.reso.instaeclipse.mods.media;

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
    private static final List<Method> WRAPPERS = new ArrayList<>();
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
    private static final java.util.Map<Object, Boolean> PLAYERS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static void remember(Object o) {
        if (o != null) PLAYERS.put(o, Boolean.TRUE);
    }

    /** Applies the speed to every live story/reel player; true if any accepted it. */
    private static boolean applyToPlayers(float s) {
        boolean ok = false;
        Object[] live;
        synchronized (PLAYERS) { live = PLAYERS.keySet().toArray(); }
        for (Object p : live) {
            for (Method m : WRAPPERS) {
                if (!m.getDeclaringClass().isInstance(p)) continue;
                try {
                    APPLYING.set(Boolean.TRUE);
                    m.invoke(p, s);
                    ok = true;
                } catch (Throwable ignored) {
                } finally {
                    APPLYING.remove();
                }
            }
        }
        return ok;
    }

    public void install(DexKitBridge bridge, ClassLoader cl) {
        installFeed(bridge, cl);
        installHero(bridge, cl);
        installReelScrubber(bridge, cl);
    }

    // Hero layer: every IG video (feed, reels, stories, highlights) plays through the Hero player
    // client (X.09cA on 451). Story viewers never go through the feed/clips wrappers above, so the
    // speed is set here: (player, speed, pitch) static setter "HeroManager.setPlaybackSpeed",
    // re-applied each time a player prepares ("HeroManager.prepareAndMayPlay").
    private static Method heroSet;
    private static final java.util.Map<Object, Boolean> HEROES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static Method findStatic(DexKitBridge bridge, ClassLoader cl, String key, String str, int params) throws Throwable {
        Method m = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(key, cl) : null;
        if (m != null) return m;
        for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings(str).returnType("void")))) {
            Method c = md.getMethodInstance(cl);
            if (Modifier.isStatic(c.getModifiers()) && c.getParameterTypes().length == params) {
                DexKitCache.saveMethod(key, c);
                return c;
            }
        }
        return null;
    }

    private void installHero(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method set = findStatic(bridge, cl, "PlaybackSpeed_heroSet", "HeroManager.setPlaybackSpeed", 3);
            Method prepare = findStatic(bridge, cl, "PlaybackSpeed_heroPrepare", "HeroManager.prepareAndMayPlay", 1);
            if (set == null || prepare == null) {
                ModuleLog.line(TAG + "⚠️ hero set=" + set + " prepare=" + prepare);
                return;
            }
            set.setAccessible(true);
            heroSet = set;
            XposedBridge.hookMethod(set, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (param.args[0] != null) HEROES.put(param.args[0], Boolean.TRUE);
                    if (APPLYING.get() == Boolean.TRUE) return;
                    // IG resets to 1x on its own (e.g. after hold-for-2x); keep the chosen speed.
                    if (FeatureFlags.offerPlaybackSpeed && speed != 1f && param.args[1] instanceof Float f && f == 1f)
                        param.args[1] = speed;
                }
            });
            XposedBridge.hookMethod(prepare, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    Object p = param.args[0];
                    if (p == null) return;
                    HEROES.put(p, Boolean.TRUE);
                    if (FeatureFlags.offerPlaybackSpeed && speed != 1f) setHero(p, speed);
                }
            });
            ModuleLog.line(TAG + "✅ hero " + set.getDeclaringClass().getName() + "." + set.getName()
                    + ", prepare " + prepare.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ hero: " + t);
        }
    }

    private static boolean setHero(Object p, float s) {
        Method m = heroSet;
        if (m == null) return false;
        try {
            APPLYING.set(Boolean.TRUE);
            m.invoke(null, p, s, 1f);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            APPLYING.remove();
        }
    }

    /** Applies the speed to every live Hero player; true if any accepted it. */
    private static boolean applyToHeroes(float s) {
        Object[] live;
        synchronized (HEROES) { live = HEROES.keySet().toArray(); }
        boolean ok = false;
        for (Object p : live) ok |= setHero(p, s);
        return ok;
    }

    private void installFeed(DexKitBridge bridge, ClassLoader cl) {
        try {
            List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods("PlaybackSpeed_setters", cl) : null;
            Method start = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("PlaybackSpeed_start", cl) : null;
            if (cached == null || cached.isEmpty() || start == null) {
                List<ClassData> players = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings("IgFeedVideoPlayer: Persistent Visible Cover Image")));
                start = null;
                // 452.0.0.22+ moved the (Media, …)boolean start method into a subclass of the class
                // that holds the string, so look there too.
                List<ClassData> scan = new ArrayList<>(players);
                for (ClassData cd : players) {
                    try {
                        scan.addAll(bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                                .superClass(cd.getName()))));
                    } catch (Throwable ignored) {}
                }
                for (ClassData cd : scan) {
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
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
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
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        lastPlayer = new WeakReference<>(param.thisObject);
                        if (FeatureFlags.offerPlaybackSpeed && speed != 1f) apply(param.thisObject, speed);
                    }
                });
            }
            // Story / reel players set speed through their own (float) method that calls the Groot
            // setter (X.0S4W / X.0Cfw .HFy on 452). Remember their instances and, when a custom
            // speed is chosen, apply it there too (and override IG's own 1x calls).
            int storyHooks = 0;
            for (Method setter : SETTERS) {
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes("float").returnType("void").addInvoke(MethodMatcher.create(setter))))) {
                    Method m;
                    try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    if (Modifier.isStatic(m.getModifiers()) || SETTERS.contains(m)) continue;
                    m.setAccessible(true);
                    WRAPPERS.add(m);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            remember(param.thisObject);
                            if (APPLYING.get() == Boolean.TRUE) return;
                            if (FeatureFlags.offerPlaybackSpeed && speed != 1f && param.args[0] instanceof Float f && f == 1f)
                                param.args[0] = speed;
                        }
                    });
                    for (java.lang.reflect.Constructor<?> c : m.getDeclaringClass().getDeclaredConstructors()) {
                        XposedBridge.hookMethod(c, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method); remember(param.thisObject); }
                        });
                    }
                    storyHooks++;
                }
            }
            ModuleLog.line(TAG + "story/reel speed methods: " + storyHooks);
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
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
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
        showChooser(ctx, null);
    }

    /** @param onClosed run when the chooser closes (stories use it to resume the paused viewer). */
    static void showChooser(Context ctx, Runnable onClosed) {
        String[] labels = new String[SPEEDS.length];
        int checked = 1;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = (SPEEDS[i] == (int) SPEEDS[i] ? String.valueOf((int) SPEEDS[i]) : String.valueOf(SPEEDS[i])) + "×";
            if (SPEEDS[i] == speed) checked = i;
        }
        Context host = ps.reso.instaeclipse.mods.ui.UIHookManager.dialogHost(ctx);
        new ps.reso.instaeclipse.utils.dialog.IeDialog(host)
                .title(I18n.t(ctx, R.string.ig_playback_speed_title))
                .choices(labels, checked, which -> {
                    speed = SPEEDS[which];
                    WeakReference<Object> ref = lastPlayer;
                    Object p = ref != null ? ref.get() : null;
                    boolean ok = applyToHeroes(speed);
                    ok |= applyToPlayers(speed);
                    ok |= p != null && apply(p, speed);
                    ModuleLog.line(TAG + "chosen " + speed + "x, applied now=" + ok);
                    if (!ok) ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_playback_speed_next), Toast.LENGTH_SHORT).show();
                })
                .onDismiss(onClosed)
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
