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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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
        boolean cachedReelsHooked = false;
        if (DexKitCache.isCacheValid()) {
            Method feedCached = DexKitCache.loadMethod("DoubleTapLike", classLoader);
            Method reelsCached = DexKitCache.loadMethod("DoubleTapLikeReels", classLoader);
            List<Method> reelsGestureCached = DexKitCache.loadMethods("DoubleTapLikeReelsGestures", classLoader);

            cachedFeedHooked = hookMethod(feedCached, HOOK)
                    | hookMethods(DexKitCache.loadMethods(CACHE_FEED_SPLIT, classLoader), HOOK) > 0;
            boolean cachedLegacyReelsHooked = hookMethod(reelsCached, HOOK);
            boolean cachedGestureReelsHooked = hookMethods(reelsGestureCached, REELS_GESTURE_HOOK) > 0;
            boolean cachedReelsLikeHooked = hookMethods(DexKitCache.loadMethods(CACHE_REELS_LIKE, classLoader), HOOK) > 0;
            cachedReelsHooked = cachedLegacyReelsHooked || cachedGestureReelsHooked || cachedReelsLikeHooked;

            if (cachedFeedHooked && (cachedGestureReelsHooked || cachedReelsLikeHooked)) {
                ModuleLog.line("(InstaEclipse | DoubleTapLike): Hooked (cached)");
                FeatureStatusTracker.setHooked("DisableDoubleTapLike");
                return;
            }
        }
        try {
            findAndHook(bridge, classLoader, cachedFeedHooked, cachedReelsHooked);
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): " + e.getMessage());
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

        List<Method> reelsGestureMethods = findReelsGestureMethods(bridge, classLoader);
        if (hookMethods(reelsGestureMethods, REELS_GESTURE_HOOK) > 0) {
            DexKitCache.saveMethods("DoubleTapLikeReelsGestures", reelsGestureMethods);
            reelsHooked = true;
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels gesture callbacks hooked: " + reelsGestureMethods.size());
        }

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
        if (!reelsHooked) {
            ModuleLog.line("(InstaEclipse | DoubleTapLike): Reels entry not found");
        }
        if (feedHooked || reelsHooked) {
            FeatureStatusTracker.setHooked("DisableDoubleTapLike");
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
            if (!hookedMethods.add(key)) return true;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, hook);
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
