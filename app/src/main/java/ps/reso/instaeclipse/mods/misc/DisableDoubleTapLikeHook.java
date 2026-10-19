package ps.reso.instaeclipse.mods.misc;

import android.view.GestureDetector;
import android.view.View;
import android.view.ViewParent;

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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class DisableDoubleTapLikeHook {

    private final Set<String> hookedMethods = new HashSet<>();

    // Feed like methods found via the split anchors (IG 447+), cached as a list.
    private static final String CACHE_FEED_SPLIT = "DoubleTapLikeFeedSplit";
    // Reel like action (IG 447+): the LikeNavigator.onLikeMedia lambda that picks the source
    // "DOUBLE_TAP_MEDIA" vs "BUTTON", runs the heart animation, updates local like state, marks
    // "LIKE_FIRED" and sends the like (X.0C6G.invoke on 452, X.0AxE on 451, X.0Hol on 447).
    // Reel double-taps reach it from the clips GestureDetector listener's onDoubleTap, so the
    // stack-gated HOOK blocks only double-tap likes; the like button (no onDoubleTap frame) works.
    private static final String CACHE_REELS_LIKE = "DoubleTapLikeReelsLike";
    private static final String CACHE_BOUND_ACTION = "DoubleTapLikeBoundAction";

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (!FeatureFlags.disableDoubleTapLike) return;
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement frame : stack) {
                String methodName = frame.getMethodName();
                if ("onDoubleTap".equals(methodName) || "onDoubleTapEvent".equals(methodName)) {
                    block(param);
                    return;
                }
            }
        }
    };

    private static final XC_MethodHook REELS_GESTURE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (!FeatureFlags.disableDoubleTapLike) return;
            if (!looksLikeClipsDoubleTapGesture(param.thisObject)) return;
            block(param);
        }
    };

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        boolean cachedFeedHooked = false;
        // Prefer the proven action boundary: suppressing the gesture callback itself can
        // also swallow native skip/forward actions and gesture bookkeeping.
        boolean cachedReelsHooked = installReelsLikeAction(bridge, classLoader);
        if (DexKitCache.isCacheValid()) {
            Method feedCached = DexKitCache.loadMethod("DoubleTapLike", classLoader);
            Method reelsCached = DexKitCache.loadMethod("DoubleTapLikeReels", classLoader);
            List<Method> reelsGestureCached = DexKitCache.loadMethods("DoubleTapLikeReelsGestures", classLoader);

            cachedFeedHooked = hookMethod(feedCached, HOOK)
                    | hookMethods(DexKitCache.loadMethods(CACHE_FEED_SPLIT, classLoader), HOOK) > 0;
            if (!cachedReelsHooked)
                cachedReelsHooked = hookMethods(DexKitCache.loadMethods(CACHE_REELS_LIKE, classLoader), HOOK) > 0;
            if (!cachedReelsHooked) cachedReelsHooked = hookMethod(reelsCached, HOOK);
            if (!cachedReelsHooked)
                cachedReelsHooked = hookMethods(reelsGestureCached, REELS_GESTURE_HOOK) > 0;

            if (cachedFeedHooked && cachedReelsHooked) {
                ModuleLog.line("(InstaEclipse | DoubleTapLike): Hooked (cached)");
                FeatureStatusTracker.setHooked("DisableDoubleTapLike");
                return;
            }
        }
        try {
            findAndHook(bridge, classLoader, cachedFeedHooked, cachedReelsHooked);
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): " + e.getMessage());
            if (FeatureFlags.disableDoubleTapLike) FeatureStatusTracker.setBroken("DisableDoubleTapLike");
        }
    }

    private void findAndHook(DexKitBridge bridge, ClassLoader classLoader, boolean feedAlreadyHooked, boolean reelsAlreadyHooked) {
        boolean feedHooked = feedAlreadyHooked;
        boolean reelsHooked = reelsAlreadyHooked;

        if (!feedHooked) {
            List<MethodData> feedMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("double_tap_on_liked", "used_double_tap")
                    )
            );
            boolean splitAnchors = false;
            if (feedMethods.isEmpty()) {
                splitAnchors = true;
                // IG 447+/451: the two strings were split across two methods — the like/unlike
                // toggle (X.0GAJ.A01 on 451: "like","unlike","double_tap_on_liked") and the like
                // request builder (X.0A0e.A04 on 451: "used_double_tap"). Both are void and run
                // synchronously under GestureDetector.onDoubleTap, so the same stack-gated HOOK
                // blocks only double-tap likes; button likes still go through.
                feedMethods = new ArrayList<>();
                feedMethods.addAll(bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("double_tap_on_liked", "like", "unlike")
                                .returnType("void"))));
                feedMethods.addAll(bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("used_double_tap", "used_double_tap_hint_impressions")
                                .returnType("void"))));
            }
            List<Method> splitHooked = new ArrayList<>();
            for (MethodData md : feedMethods) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    if (!splitAnchors) DexKitCache.saveMethod("DoubleTapLike", method);
                    if (hookMethod(method, HOOK)) {
                        feedHooked = true;
                        ModuleLog.line("(InstaEclipse | DoubleTapLike): Feed hooked on " + md.getClassName() + "." + md.getMethodName());
                        if (!splitAnchors) break;
                        splitHooked.add(method);
                    }
                } catch (Exception e) {
                    ModuleLog.line("(InstaEclipse | DoubleTapLike): Feed: " + e.getMessage());
                }
            }
            if (!splitHooked.isEmpty()) DexKitCache.saveMethods(CACHE_FEED_SPLIT, splitHooked);
            if (feedMethods.isEmpty()) {
                ModuleLog.line("(InstaEclipse | DoubleTapLike): Feed method not found");
            }
        }

        if (!reelsHooked) {
            try {
                List<Method> reelLike = new ArrayList<>();
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("LIKE_FIRED", "DOUBLE_TAP_MEDIA")))) {
                    Method m = md.getMethodInstance(classLoader);
                    if (hookMethod(m, HOOK)) {
                        reelLike.add(m);
                        ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels like action hooked on " + md.getClassName() + "." + md.getMethodName());
                    }
                }
                if (!reelLike.isEmpty()) {
                    DexKitCache.saveMethods(CACHE_REELS_LIKE, reelLike);
                    reelsHooked = true;
                }
            } catch (Throwable e) {
                ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels like action: " + e.getMessage());
            }
        }

        if (!reelsHooked) {
            List<Method> reelsGestureMethods = findReelsGestureMethods(bridge, classLoader);
            if (hookMethods(reelsGestureMethods, REELS_GESTURE_HOOK) > 0) {
                DexKitCache.saveMethods("DoubleTapLikeReelsGestures", reelsGestureMethods);
                reelsHooked = true;
                ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels gesture callbacks hooked: " + reelsGestureMethods.size());
            }
        }

        if (!reelsHooked) {
            List<ClassData> reelsClasses = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingStrings("clips_doubletap", "LIKE_FIRED")
                    )
            );
            for (ClassData cd : reelsClasses) {
                List<MethodData> ecgMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(cd.getName())
                                .usingStrings("clips_doubletap")
                        )
                );
                for (MethodData md : ecgMethods) {
                    try {
                        Method method = md.getMethodInstance(classLoader);
                        if (hookMethod(method, HOOK)) {
                            DexKitCache.saveMethod("DoubleTapLikeReels", method);
                            reelsHooked = true;
                            ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels legacy hooked on " + cd.getName() + "." + md.getMethodName());
                        }
                    } catch (Exception e) {
                        ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels: " + e.getMessage());
                    }
                }
            }
        }
        if (!reelsHooked) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels entry not found");
        }
        if (feedHooked && reelsHooked) {
            FeatureStatusTracker.setHooked("DisableDoubleTapLike");
        } else if (FeatureFlags.disableDoubleTapLike) {
            FeatureStatusTracker.setBroken("DisableDoubleTapLike");
        }
    }

    private boolean installReelsLikeAction(DexKitBridge bridge, ClassLoader loader) {
        try {
            List<Method> proof = DexKitCache.isCacheValid()
                    ? DexKitCache.loadMethods(CACHE_BOUND_ACTION, loader) : null;
            if (proof == null || proof.size() != 4 || LikeActionBinding.create(proof.get(0),
                    proof.get(1), proof.get(2), proof.subList(3, 4)) == null) {
                // A negative result is version-scoped by DexKitCache, just like method entries.
                if (DexKitCache.isCacheValid() && "absent".equals(DexKitCache.loadString(CACHE_BOUND_ACTION)))
                    return false;
                List<MethodData> setters = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("android_purge_26_q3_GestureActionHandler_setOnLikeMediaAction")));
                List<MethodData> dispatchers = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("android_purge_26_q3_GestureActionHandler_handleDoubleTapMedia")));
                List<MethodData> actions = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .name("invoke").usingStrings("android_purge_26_q3_LikeNavigator_onLikeMedia",
                                "DOUBLE_TAP_MEDIA", "LIKE_FIRED")));
                if (setters.size() != 1 || dispatchers.size() != 1 || actions.size() != 1) {
                    DexKitCache.saveString(CACHE_BOUND_ACTION, "absent");
                    return false;
                }
                Method setter = setters.get(0).getMethodInstance(loader);
                Method dispatcher = dispatchers.get(0).getMethodInstance(loader);
                Method action = actions.get(0).getMethodInstance(loader);
                List<Method> invoked = new ArrayList<>();
                for (MethodData data : dispatchers.get(0).getInvokes()) {
                    try { invoked.add(data.getMethodInstance(loader)); }
                    catch (ReflectiveOperationException | LinkageError ignored) { }
                }
                if (LikeActionBinding.create(setter, dispatcher, action, invoked) == null) return false;
                Method contract = setter.getParameterTypes()[0].getMethod("invoke",
                        Object.class, Object.class, Object.class, Object.class);
                proof = Arrays.asList(setter, dispatcher, action, contract);
                DexKitCache.saveMethods(CACHE_BOUND_ACTION, proof);
            }
            Method setter = proof.get(0), dispatcher = proof.get(1), action = proof.get(2);
            if (hookedMethods.contains(signature(setter)) && hookedMethods.contains(signature(action))) return true;
            if (hookedMethods.contains(signature(action))) return false;
            BoundLikeCallbacks callbacks = new BoundLikeCallbacks(dispatcher);
            setter.setAccessible(true);
            action.setAccessible(true);
            XC_MethodHook.Unhook setterHook = XposedBridge.hookMethod(setter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!param.hasThrowable() && param.args.length == 1
                            && action.getDeclaringClass().isInstance(param.args[0])) callbacks.bind(param.args[0]);
                }
            });
            try {
                XposedBridge.hookMethod(action, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (callbacks.shouldBlock(FeatureFlags.disableDoubleTapLike, param.thisObject,
                                param.args, () -> Thread.currentThread().getStackTrace())) {
                            // The proven void dispatcher discards the result. Other cases of
                            // this merged lambda and native gesture side effects stay intact.
                            param.setResult(null);
                        }
                    }
                });
            } catch (Throwable failure) {
                setterHook.unhook();
                throw failure;
            }
            hookedMethods.add(signature(setter));
            hookedMethods.add(signature(action));
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels like action bound");
            return true;
        } catch (Throwable ignored) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Scoped Reels action unavailable");
            return false;
        }
    }

    /** Pure reflection checks, shared by discovery and regression fixtures. */
    static final class LikeActionBinding {
        static LikeActionBinding create(Method setter, Method dispatcher, Method action, List<Method> invokes) {
            if (setter == null || dispatcher == null || action == null || invokes == null) return null;
            if (setter.getDeclaringClass() != dispatcher.getDeclaringClass()
                    || setter.getReturnType() != void.class || dispatcher.getReturnType() != void.class
                    || setter.getParameterTypes().length != 1 || action.getReturnType() != Object.class
                    || !"invoke".equals(action.getName()) || !instanceMethod(setter)
                    || !instanceMethod(dispatcher) || !instanceMethod(action)) return null;
            Class<?> contract = setter.getParameterTypes()[0];
            Class<?>[] dispatchTypes = dispatcher.getParameterTypes();
            if (!contract.isInterface() || !contract.isAssignableFrom(action.getDeclaringClass())
                    || dispatchTypes.length != 6 || dispatchTypes[0] != View.class
                    || dispatchTypes[4] != boolean.class || dispatchTypes[5] != boolean.class) return null;
            Class<?>[] types = action.getParameterTypes();
            if (types.length != 4) return null;
            for (Class<?> type : types) if (type != Object.class) return null;
            try {
                Method invoke = contract.getMethod("invoke", Object.class, Object.class, Object.class, Object.class);
                if (invoke.getReturnType() != Object.class || !invokes.contains(invoke)) return null;
            } catch (NoSuchMethodException e) { return null; }
            return new LikeActionBinding();
        }

        private static boolean instanceMethod(Method method) {
            return !Modifier.isStatic(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers());
        }
    }

    /** Identity-scoped because one merged lambda class implements many unrelated actions. */
    static final class BoundLikeCallbacks {
        private final List<WeakReference<Object>> bound = new ArrayList<>();
        private final String dispatcherClass;
        private final String dispatcherName;

        BoundLikeCallbacks(Method dispatcher) {
            dispatcherClass = dispatcher.getDeclaringClass().getName();
            dispatcherName = dispatcher.getName();
        }

        synchronized void bind(Object callback) {
            if (callback == null || contains(callback)) return;
            if (bound.size() >= 128) bound.remove(0);
            bound.add(new WeakReference<>(callback));
        }

        synchronized boolean shouldBlock(boolean enabled, Object callback, Object[] args,
                Supplier<StackTraceElement[]> stackSource) {
            if (!enabled || callback == null || args == null || args.length != 4
                    || !Boolean.TRUE.equals(args[3]) || stackSource == null || !contains(callback)) return false;
            StackTraceElement[] stack = stackSource.get();
            if (stack == null) return false;
            for (StackTraceElement frame : stack) {
                if (dispatcherClass.equals(frame.getClassName()) && dispatcherName.equals(frame.getMethodName())) return true;
            }
            return false;
        }

        private boolean contains(Object callback) {
            boolean found = false;
            Iterator<WeakReference<Object>> iterator = bound.iterator();
            while (iterator.hasNext()) {
                Object existing = iterator.next().get();
                if (existing == null) iterator.remove();
                else if (existing == callback) found = true;
            }
            return found;
        }
    }

    private List<Method> findReelsGestureMethods(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> methods = new ArrayList<>();
        try {
            List<MethodData> callbacks = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .name("onDoubleTap")
                            .paramTypes("android.view.MotionEvent")
                            .returnType("boolean")
                    )
            );
            for (MethodData md : callbacks) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    if (isLikelyClipsGestureClass(method.getDeclaringClass())) {
                        methods.add(method);
                    }
                } catch (Throwable e) {
                    ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels gesture candidate skipped: " + e.getMessage());
                }
            }
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels gesture search failed: " + e.getMessage());
        }
        return methods;
    }

    private boolean hookMethod(Method method, XC_MethodHook hook) {
        if (method == null) return false;
        try {
            String key = signature(method);
            if (hookedMethods.contains(key)) return true;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, hook);
            hookedMethods.add(key);
            return true;
        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Hook failed: " + e.getMessage());
            return false;
        }
    }

    private int hookMethods(List<Method> methods, XC_MethodHook hook) {
        if (methods == null || methods.isEmpty()) return 0;
        int hooked = 0;
        for (Method method : methods) {
            if (hookMethod(method, hook)) hooked++;
        }
        return hooked;
    }

    private static void block(XC_MethodHook.MethodHookParam param) {
        if (param.method instanceof Method) {
            Class<?> returnType = ((Method) param.method).getReturnType();
            if (returnType == boolean.class || returnType == Boolean.class) {
                param.setResult(Boolean.TRUE);
                return;
            }
        }
        param.setResult(null);
    }

    private static boolean isLikelyClipsGestureClass(Class<?> clazz) {
        if (clazz == null || !GestureDetector.SimpleOnGestureListener.class.isAssignableFrom(clazz)) {
            return false;
        }
        int score = 0;
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if ("com.instagram.clips.intf.ClipsViewerConfig".equals(typeName)) score += 3;
                if ("com.instagram.common.session.UserSession".equals(typeName)) score++;
                if ("android.view.GestureDetector".equals(typeName)) score++;
                if ("android.view.ScaleGestureDetector".equals(typeName)) score++;
                if (typeName.contains("ClipsViewer")) score += 2;
            }
        }
        return score >= 4;
    }

    private static boolean looksLikeClipsDoubleTapGesture(Object target) {
        if (target == null) return false;
        Class<?> clazz = target.getClass();
        if (isLikelyClipsGestureClass(clazz)) return true;
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    if (!View.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof View && looksLikeClipsView((View) value)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean looksLikeClipsView(View view) {
        for (View current = view; current != null; ) {
            String entryName = resourceEntryName(current);
            if (entryName != null && (entryName.contains("clips_video_container")
                    || entryName.contains("clips_viewer_video_layout")
                    || entryName.contains("sponsored_clips_showreel_view"))) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static String resourceEntryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String signature(Method method) {
        StringBuilder sb = new StringBuilder(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append(paramType.getName()).append(',');
        }
        return sb.append(')').toString();
    }
}
