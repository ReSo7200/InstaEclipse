package ps.reso.instaeclipse.mods.misc;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class DisableVideoAutoPlayHook {
    private static final String PLAY_DRAWABLE_FIELD = "InstaEclipseManualVideoPlayDrawable";
    private static final Set<Drawable> sPlayDrawables =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Drawable.ConstantState> sPlayDrawableStates =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile boolean sOverlayLifecycleHooked = false;

    public void handleAutoPlayDisable(DexKitBridge bridge) {
        // The play-overlay hooks sit on very hot framework methods (Resources.getDrawable,
        // ImageView.setImageDrawable, View.performClick). Hooking those app-wide triggers ART's JIT
        // SIGSEGV ("Jit thread pool", DexCache::SetResolvedType) on IG 451+, so they are only
        // installed when the feature is actually on (turning it on later applies after a restart).
        if (FeatureFlags.disableVideoAutoPlay) hookManualPlayOverlayLifecycle();
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("AutoPlayDisable", Module.hostClassLoader);
            if (cached != null) {
                hookMethod(cached);
                return;
            }
        }
        try {
            findAndHookDynamicMethod(bridge);
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | AutoPlayDisable): Error: " + e.getMessage());
        }
    }

    private void findAndHookDynamicMethod(DexKitBridge bridge) {
        try {
            // Step 1: Find methods referencing "ig_disable_video_autoplay"
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("ig_disable_video_autoplay")
                    )
            );

            if (methods.isEmpty()) {
                ModuleLog.line("(InstaEclipse | AutoPlayDisable): ❌ No matching methods found.");
                return;
            }

            // Step 2: Find the correct method: boolean return type, 1 parameter
            for (MethodData method : methods) {
                boolean returnTypeMatch = String.valueOf(method.getReturnType()).contains("boolean");
                boolean paramTypesMatch = method.getParamTypes().size() == 1;

                if (returnTypeMatch && paramTypesMatch) {
                    hookMethod(method);
                    return;
                }
            }

            ModuleLog.line("(InstaEclipse | AutoPlayDisable): ❌ No matching methods with correct signature.");
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | AutoPlayDisable): ❌ Error during method discovery: " + e.getMessage());
        }
    }

    private void hookMethod(MethodData method) {
        try {
            Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
            DexKitCache.saveMethod("AutoPlayDisable", targetMethod);
            hookMethod(targetMethod);
            ModuleLog.line("(InstaEclipse | AutoPlayDisable): ✅ Hooked (dynamic check): " +
                    method.getClassName() + "." + method.getName());
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | AutoPlayDisable): ❌ Error hooking method: " + e.getMessage());
        }
    }

    private void hookMethod(Method targetMethod) {
        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.disableVideoAutoPlay) param.setResult(true);
            }
        });
    }

    private void hookManualPlayOverlayLifecycle() {
        if (sOverlayLifecycleHooked) return;
        synchronized (DisableVideoAutoPlayHook.class) {
            if (sOverlayLifecycleHooked) return;
            sOverlayLifecycleHooked = true;
        }

        try {
            hookPlayDrawableLoads();
            hookPlayImageBinding();
            hookPlayOverlayClicks();
            ModuleLog.line("(InstaEclipse | AutoPlayDisable): Hooked manual play overlay lifecycle.");
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | AutoPlayDisable): Error hooking play overlay lifecycle: " + t.getMessage());
        }
    }

    private void hookPlayDrawableLoads() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.disableVideoAutoPlay) return;
                if (!(param.thisObject instanceof Resources) || !(param.getResult() instanceof Drawable)) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;

                Resources resources = (Resources) param.thisObject;
                int resId = (Integer) param.args[0];
                if (isManualVideoPlayDrawableResource(resources, resId)) {
                    rememberPlayDrawable((Drawable) param.getResult());
                }
            }
        };

        XposedBridge.hookAllMethods(Resources.class, "getDrawable", hook);
        XposedBridge.hookAllMethods(Resources.class, "getDrawableForDensity", hook);
    }

    private void hookPlayImageBinding() {
        XposedBridge.hookAllMethods(ImageView.class, "setImageResource", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.disableVideoAutoPlay) return;
                if (!(param.thisObject instanceof ImageView)) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;

                ImageView imageView = (ImageView) param.thisObject;
                int resId = (Integer) param.args[0];
                if (isManualVideoPlayDrawableResource(imageView.getResources(), resId)) {
                    markPlayDrawableView(imageView);
                }
            }
        });

        XposedBridge.hookAllMethods(ImageView.class, "setImageDrawable", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.disableVideoAutoPlay) return;
                if (!(param.thisObject instanceof ImageView)) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;

                Drawable drawable = (Drawable) param.args[0];
                if (isRememberedPlayDrawable(drawable)) {
                    markPlayDrawableView((ImageView) param.thisObject);
                }
            }
        });
    }

    private void hookPlayOverlayClicks() {
        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.disableVideoAutoPlay || !(param.thisObject instanceof View)) return;

                View view = (View) param.thisObject;
                if (isManualVideoPlayTarget(view)) {
                    schedulePlayOverlayHide(view);
                }
            }
        };

        XposedBridge.hookAllMethods(View.class, "performClick", clickHook);
        XposedBridge.hookAllMethods(View.class, "callOnClick", clickHook);
    }

    private static void schedulePlayOverlayHide(final View clickedView) {
        Runnable hide = () -> hideNearbyPlayOverlays(clickedView);
        clickedView.post(hide);
        clickedView.postDelayed(hide, 80L);
        clickedView.postDelayed(hide, 250L);
        clickedView.postDelayed(hide, 700L);
    }

    private static void hideNearbyPlayOverlays(View clickedView) {
        View anchor = closestPlayOverlayView(clickedView);
        if (anchor == null) anchor = clickedView;

        hidePlayOverlayIfSafe(anchor);
        hidePlayOverlayDescendants(anchor, 0);

        View current = anchor;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;

            View parentView = (View) parent;
            if (isPlayOverlayContainer(parentView)) {
                hidePlayOverlayIfSafe(parentView);
            }
            hidePlayOverlayDescendants(parentView, 0);
            current = parentView;
        }
    }

    private static View closestPlayOverlayView(View view) {
        View current = view;
        for (int depth = 0; depth < 7 && current != null; depth++) {
            if (isHideablePlayOverlay(current)) return current;

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void hidePlayOverlayDescendants(View view, int depth) {
        if (!(view instanceof ViewGroup) || depth > 4) return;

        ViewGroup group = (ViewGroup) view;
        int childCount = Math.min(group.getChildCount(), 80);
        for (int i = 0; i < childCount; i++) {
            View child = group.getChildAt(i);
            hidePlayOverlayIfSafe(child);
            hidePlayOverlayDescendants(child, depth + 1);
        }
    }

    private static void hidePlayOverlayIfSafe(View view) {
        if (isHideablePlayOverlay(view)) {
            view.setPressed(false);
            view.setVisibility(View.GONE);
        }
    }

    private static boolean isManualVideoPlayTarget(View view) {
        if (isMarkedPlayDrawableView(view) && hasVideoContext(view)) return true;

        View current = view;
        for (int depth = 0; depth < 7 && current != null; depth++) {
            String name = resourceEntryName(current);
            if (isStrongPlayOverlayName(name)) return true;
            if (isGenericPlayOverlayName(name) && hasVideoContext(current)) return true;

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        return false;
    }

    private static boolean isHideablePlayOverlay(View view) {
        if (isMainVideoSurface(view)) return false;
        if (isMarkedPlayDrawableView(view) && hasVideoContext(view)) return true;

        String name = resourceEntryName(view);
        if (isStrongPlayOverlayName(name)) return true;
        return isGenericPlayOverlayName(name) && hasVideoContext(view);
    }

    private static boolean isPlayOverlayContainer(View view) {
        String name = resourceEntryName(view);
        return "view_play_button_container".equals(name)
                || "play_button_stub".equals(name)
                || "play_icon_view_stub".equals(name)
                || "zero_rating_video_play_button_stub".equals(name);
    }

    private static boolean hasVideoContext(View view) {
        View current = view;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            String name = resourceEntryName(current);
            if (name != null && (name.contains("video")
                    || name.contains("clips")
                    || name.contains("reel")
                    || name.contains("media"))) {
                return true;
            }

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean isMainVideoSurface(View view) {
        String name = resourceEntryName(view);
        if (name == null) return false;
        return "video_player_container".equals(name)
                || "video_player_view".equals(name)
                || "clips_video_player".equals(name)
                || "clips_video_container".equals(name)
                || "map_video_player_container".equals(name)
                || "content_note_quick_reply_video_player_container".equals(name);
    }

    private static boolean isStrongPlayOverlayName(String name) {
        if (name == null) return false;
        return "video_play_icon".equals(name)
                || "video_play_pause_button".equals(name)
                || "video_play_toggle_button".equals(name)
                || "fullscreen_video_play_icon".equals(name)
                || "clips_play_button".equals(name)
                || "view_play_button".equals(name)
                || "view_play_button_container".equals(name)
                || "zero_rating_video_play_button_stub".equals(name);
    }

    private static boolean isGenericPlayOverlayName(String name) {
        if (name == null) return false;
        return "play_button".equals(name)
                || "play_button_stub".equals(name)
                || "play_icon_view_stub".equals(name)
                || "suggested_media_play_button".equals(name)
                || "question_media_play_button".equals(name)
                || "mention_thumbnail_video_play_button".equals(name);
    }

    private static boolean isManualVideoPlayDrawableResource(Resources resources, int resId) {
        if (resources == null || resId == 0) return false;
        try {
            String type = resources.getResourceTypeName(resId);
            if (!"drawable".equals(type)) return false;

            String name = resources.getResourceEntryName(resId);
            return "play_button".equals(name) || "play_button_large".equals(name);
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static String resourceEntryName(View view) {
        if (view == null || view.getId() == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException ignored) {
            return null;
        }
    }

    private static void rememberPlayDrawable(Drawable drawable) {
        sPlayDrawables.add(drawable);
        Drawable.ConstantState state = drawable.getConstantState();
        if (state != null) sPlayDrawableStates.add(state);
    }

    private static boolean isRememberedPlayDrawable(Drawable drawable) {
        if (drawable == null) return false;
        if (sPlayDrawables.contains(drawable)) return true;

        Drawable.ConstantState state = drawable.getConstantState();
        return state != null && sPlayDrawableStates.contains(state);
    }

    private static void markPlayDrawableView(ImageView imageView) {
        XposedHelpers.setAdditionalInstanceField(imageView, PLAY_DRAWABLE_FIELD, Boolean.TRUE);
    }

    private static boolean isMarkedPlayDrawableView(View view) {
        return view instanceof ImageView
                && Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(view, PLAY_DRAWABLE_FIELD));
    }
}
