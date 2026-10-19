package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.widget.TextView;
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

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
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
 *
 * Hold for slow motion: IG's hold-for-2x fires on both halves of a reel. With
 * FeatureFlags.holdLeftSlowMotion on, a 2x request from a hold that started on the left half of
 * the screen becomes 0.5x (touch side from Activity.dispatchTouchEvent; the rewrite happens in the
 * same Hero / player setters as the chosen speed), and IG's "2X" hint reads "½X" meanwhile.
 */
public class PlaybackSpeedHook {

    private static final String TAG = "(InstaEclipse | PlaybackSpeed): ";
    static final float[] SPEEDS = {0.5f, 1f, 1.25f, 1.5f, 2f, 3f};
    private static volatile float speed = 1f;
    private static volatile WeakReference<Object> lastPlayer;
    private static final List<Method> SETTERS = new ArrayList<>();
    private static final List<Method> WRAPPERS = new ArrayList<>();
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
    static final float SLOW = 0.5f;
    private static volatile boolean leftDown;
    private static volatile boolean slowActive;
    private static volatile WeakReference<Activity> touchActivity;
    private static final java.util.regex.Pattern TWO_X =
            java.util.regex.Pattern.compile("(?<![\\d.,])2(\\s?[xX×])(?![\\p{L}\\d])");

    /** Hold-for-2x from the left half becomes slow motion; IG's 1x reset ends it. */
    private static float holdSpeed(float f) {
        if (!FeatureFlags.holdLeftSlowMotion) return f;
        if (f == 2f && leftDown) {
            if (!slowActive) {
                ModuleLog.line(TAG + "hold on the left: " + SLOW + "x");
                slowActive = true;
                // IG shows its "2X" hint before it sets the speed: fix the one already on screen
                FeedVideoDownloadHook.mainHandler.post(PlaybackSpeedHook::relabel);
                FeedVideoDownloadHook.mainHandler.postDelayed(PlaybackSpeedHook::relabel, 200);
            }
            return SLOW;
        }
        if (f == 1f) slowActive = false;
        return f;
    }
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
        installHoldSide();
    }

    private void installHoldSide() {
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.holdLeftSlowMotion || INJECTING || !(param.args[0] instanceof MotionEvent ev)) return;
                    if (onTouch((Activity) param.thisObject, ev)) param.setResult(true);
                }
            });
            // IG's hint under the finger ("Slide down to lock 2X speed") is Litho text, laid out with
            // StaticLayout/BoringLayout: only touched while a slow hold is active, and only the
            // "2X" token. TextView.setText covers the same hint if it's ever a plain TextView.
            XC_MethodHook hint = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!slowActive || param.args.length == 0 || !(param.args[0] instanceof CharSequence cs) || cs.length() > 80) return;
                    java.util.regex.Matcher m = TWO_X.matcher(cs);
                    if (!m.find()) return;
                    String out = m.replaceAll("½$1"); // same width as "2X": the hint stays on one line
                    // StaticLayout.Builder.obtain(source, start, end, …): keep a whole-text range whole
                    if (param.args.length > 2 && param.args[1] instanceof Integer st && param.args[2] instanceof Integer en) {
                        if (st != 0 || en != cs.length()) return;
                        param.args[2] = out.length();
                    }
                    param.args[0] = out;
                }
            };
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class), hint);
            XposedBridge.hookAllMethods(android.text.StaticLayout.Builder.class, "obtain", hint);
            XposedBridge.hookAllMethods(android.text.BoringLayout.class, "make", hint);
            XposedBridge.hookAllMethods(android.text.BoringLayout.class, "replaceOrMake", hint);
            FeatureStatusTracker.setHooked("HoldSlowMotion");
            ModuleLog.line(TAG + "✅ hold side tracking");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ hold side: " + t);
        }
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
                    if (param.args[1] instanceof Float f0 && f0 == 2f && FeatureFlags.holdLeftSlowMotion) {
                        param.args[1] = holdSpeed(f0);
                        return;
                    }
                    if (param.args[1] instanceof Float f1) holdSpeed(f1);
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

    // IG 452 only speeds up a hold on the left edge of a reel; a hold elsewhere pauses it. The
    // right-half hold (left of the like/comment/share column) is ours: just before IG's long press
    // fires, IG gets an ACTION_CANCEL (so it never pauses) and the rest of the gesture is ours,
    // 2x until the finger lifts.
    private static volatile boolean rightActive;
    private static boolean rightPending;
    private static boolean INJECTING;
    private static float downX, downY;
    private static final Runnable RIGHT_HOLD = () -> {
        WeakReference<Activity> ref = touchActivity;
        Activity a = ref != null ? ref.get() : null;
        if (a == null || !rightPending) return;
        rightActive = true;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0);
        INJECTING = true;
        try {
            a.dispatchTouchEvent(cancel);
        } catch (Throwable ignored) {
        } finally {
            INJECTING = false;
            cancel.recycle();
        }
        boolean ok = applyToPlayers(2f);
        ok |= applyToHeroes(2f);
        ModuleLog.line(TAG + "hold on the right: 2x, applied=" + ok);
    };
    /** @return true to swallow the event (the rest of a right-side hold). */
    private static boolean onTouch(Activity a, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                touchActivity = new WeakReference<>(a);
                android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
                downX = ev.getRawX();
                downY = ev.getRawY();
                leftDown = downX < dm.widthPixels / 2f;
                if (!leftDown && downX < dm.widthPixels * 0.82f && downY > dm.heightPixels * 0.15f
                        && downY < dm.heightPixels * 0.85f && reelOnScreen(a)) {
                    rightPending = true;
                    // a bit before IG's own long press (getLongPressTimeout) so IG never pauses
                    FeedVideoDownloadHook.mainHandler.postDelayed(RIGHT_HOLD,
                            android.view.ViewConfiguration.getLongPressTimeout() * 3L / 4);
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (rightActive) return true;
                int slop = android.view.ViewConfiguration.get(a).getScaledTouchSlop();
                if (rightPending && Math.hypot(ev.getRawX() - downX, ev.getRawY() - downY) > slop) cancelRight();
                return false;
            }
            case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                boolean was = rightActive;
                cancelRight();
                if (was) {
                    float back = FeatureFlags.offerPlaybackSpeed ? speed : 1f;
                    applyToPlayers(back);
                    applyToHeroes(back);
                }
                return was;
            }
            default -> {
                return rightActive;
            }
        }
    }

    private static void cancelRight() {
        rightPending = false;
        rightActive = false;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(RIGHT_HOLD);
    }

    private static int clipsContainerId = -1;

    /** True when a reel is on screen (IG's clips_video_container is shown). */
    private static boolean reelOnScreen(Activity a) {
        if (clipsContainerId == -1)
            clipsContainerId = ps.reso.instaeclipse.utils.ui.ResIds.id(a, "clips_video_container");
        if (clipsContainerId == 0 || a.getWindow() == null) return false;
        android.view.View v = a.getWindow().getDecorView().findViewById(clipsContainerId);
        return v != null && v.isShown();
    }

    private static void relabel() {
        WeakReference<Activity> ref = touchActivity;
        Activity a = ref != null ? ref.get() : null;
        if (!slowActive || a == null || a.getWindow() == null) return;
        relabel(a.getWindow().getDecorView(), 0);
    }

    private static void relabel(android.view.View v, int depth) {
        if (depth > 40 || v.getVisibility() != android.view.View.VISIBLE) return;
        if (v instanceof TextView tv) {
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() <= 80 && TWO_X.matcher(cs).find()) tv.setText(cs.toString());
        } else if (v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) relabel(g.getChildAt(i), depth + 1);
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
                            if (param.args[0] instanceof Float f0 && f0 == 2f && FeatureFlags.holdLeftSlowMotion) {
                                param.args[0] = holdSpeed(f0);
                                return;
                            }
                            if (param.args[0] instanceof Float f1) holdSpeed(f1);
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
            FeatureStatusTracker.setHooked("ForceReelScrubber");
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
