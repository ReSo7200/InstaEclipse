package ps.reso.instaeclipse.mods.ui.theme;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Custom Theme: hooks the standard Android resource/color-resolution APIs (Resources.Theme,
 * Resources, Context, TypedArray) plus Activity/PhoneWindow lifecycle, so a custom palette is
 * substituted everywhere Instagram resolves a themed color. Deliberately limited to stable
 * Android SDK surfaces — Instagram's own Compose/React-Native rendering paths need separate,
 * per-version reverse-engineered hooks and are not covered here.
 */
public class IgThemeHook {

    private static volatile boolean installed;
    private static volatile Field typedArrayAttrsField;
    private static volatile Field typedArrayResourcesField;

    public void install(ClassLoader classLoader) {
        if (installed) return;
        // These hooks sit on very hot framework methods (getColor, resolveAttribute, TypedArray);
        // hooking them app-wide triggers ART's JIT SIGSEGV on IG 451+, so they are only installed
        // when the custom theme is on (turning it on later applies after an Instagram restart).
        if (!FeatureFlags.customThemeEnabled) {
            ModuleLog.line("(InstaEclipse | Theme): off -> hooks not installed");
            return;
        }
        try {
            // Theme.resolveAttribute and Context.getColor are NOT hooked: both are hot enough that
            // ART's JIT crashes IG within a minute (Context.getColor ends in Resources.getColor
            // anyway). ?attr colours are themed through IG's own attr→colour helpers instead.
            hookAttrColorHelpers(classLoader);
            hookGetColor(classLoader);
            hookTypedArrayGetColor(classLoader);
            hookActivityLifecycle();
            hookPhoneWindowColors(classLoader);
            installed = true;
            FeatureStatusTracker.setHooked("CustomTheme");
            ModuleLog.line("(InstaEclipse | Theme): hooks installed enabled=" + FeatureFlags.customThemeEnabled);
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Theme): hook failed", t);
        }
    }


    /** IG's static (Context, attr) -> int helpers that resolve a theme attribute to a colour
     *  (X.0jhi.A02 / X.00LB.A0Z on 452: getTheme().resolveAttribute(), then TypedValue.data or
     *  getColor(resourceId)). Helpers that return the resourceId are left alone: the colour they
     *  lead to is themed by the Resources.getColor hook. */
    private void hookAttrColorHelpers(final ClassLoader cl) {
        int n = 0;
        try {
            org.luckypray.dexkit.DexKitBridge bridge = ps.reso.instaeclipse.Xposed.Module.dexKitBridge;
            if (bridge == null) return;
            for (org.luckypray.dexkit.result.MethodData md : bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .modifiers(java.lang.reflect.Modifier.STATIC)
                            .paramTypes("android.content.Context", "int").returnType("int")
                            .addInvoke(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                    .declaredClass("android.content.res.Resources$Theme").name("resolveAttribute"))))) {
                boolean data = false, color = false;
                for (org.luckypray.dexkit.result.UsingFieldData uf : md.getUsingFields())
                    if ("android.util.TypedValue".equals(uf.getField().getClassName()) && "data".equals(uf.getField().getName())) data = true;
                for (org.luckypray.dexkit.result.MethodData inv : md.getInvokes())
                    if ("getColor".equals(inv.getName())) color = true;
                if (!data && !color) continue;
                final boolean viaGetColor = color && !data;
                de.robv.android.xposed.XposedBridge.hookMethod(md.getMethodInstance(cl), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (IgColorRemapEngine.isBypassing() || !IgThemeEngine.themeEnabled()
                                || param.getThrowable() != null || !(param.args[0] instanceof Context ctx)) return;
                        int attrId = (Integer) param.args[1];
                        if (!IgThemeEngine.isInitialized()) IgThemeEngine.ensureInitialized(ctx);
                        Integer override = IgThemeEngine.colorForAttr(attrId);
                        if (override != null) { param.setResult(override); return; }
                        if (viaGetColor || !IgColorRemapEngine.isReady()) return; // getColor is themed itself
                        int v = (Integer) param.getResult(), remapped = IgColorRemapEngine.remap(v);
                        if (remapped == v) return;
                        TypedValue tv = new TypedValue(); // only remap real colour attributes
                        if (ctx.getTheme().resolveAttribute(attrId, tv, true) && tv.resourceId == 0
                                && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT)
                            param.setResult(remapped);
                    }
                });
                n++;
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Theme): attr helpers: " + t);
        }
        ModuleLog.line("(InstaEclipse | Theme): attr colour helpers hooked: " + n);
    }

    private void hookGetColor(final ClassLoader cl) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()) return;
                int resId = (Integer) param.args[0];
                if (IgThemeEngine.looksLikeDirectColor(resId)) {
                    param.setResult(IgColorRemapEngine.remap(resId));
                    return;
                }
                if (IgThemeEngine.looksLikeResourceId(resId)) {
                    if (!IgThemeEngine.isInitialized()) {
                        Resources res = (Resources) param.thisObject;
                        IgThemeEngine.ensureInitialized(res, cl);
                    }
                    Integer override = IgThemeEngine.colorForResource(resId);
                    if (override != null) param.setResult(override);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.themeEnabled() || IgColorRemapEngine.isBypassing()
                        || param.getThrowable() != null || !IgColorRemapEngine.isReady()) return;
                int resId = (Integer) param.args[0];
                if (!IgThemeEngine.looksLikeDirectColor(resId) && IgThemeEngine.looksLikeResourceId(resId)
                        && IgThemeEngine.colorForResource(resId) == null) {
                    Object result = param.getResult();
                    if (result instanceof Integer) {
                        int resolved = (Integer) result;
                        int remapped = IgColorRemapEngine.remap(resolved);
                        if (remapped != resolved) param.setResult(remapped);
                    }
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getColor", int.class, Resources.Theme.class, hook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getColor", int.class, hook);
        } catch (Throwable ignored) {}
    }

    private void hookTypedArrayGetColor(final ClassLoader cl) {
        XposedHelpers.findAndHookMethod(TypedArray.class, "getColor", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (IgColorRemapEngine.isBypassing() || !IgThemeEngine.themeEnabled()) return;
                try {
                    TypedArray ta = (TypedArray) param.thisObject;
                    int index = (Integer) param.args[0];
                    int[] attrs = typedArrayAttributes(ta);
                    if (attrs == null || index < 0 || index >= attrs.length) return;
                    if (!IgThemeEngine.isInitialized()) {
                        Resources res = typedArrayResources(ta);
                        if (res != null) IgThemeEngine.ensureInitialized(res, cl);
                    }
                    Integer override = IgThemeEngine.colorForAttr(attrs[index]);
                    if (override != null) {
                        param.setResult(override);
                        return;
                    }
                    if (IgColorRemapEngine.isReady()) {
                        Object result = param.getResult();
                        if (result instanceof Integer) {
                            int resolved = (Integer) result;
                            int remapped = IgColorRemapEngine.remap(resolved);
                            if (remapped != resolved) param.setResult(remapped);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private void hookActivityLifecycle() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.isActive()) return;
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
                applyWindowColors(activity);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!IgThemeEngine.isActive()) return;
                Activity activity = (Activity) param.thisObject;
                IgThemeEngine.ensureInitialized(activity);
                IgColorRemapEngine.ensureBuilt(activity);
                applyWindowColors(activity);
            }
        });
    }

    private void hookPhoneWindowColors(ClassLoader cl) {
        XC_MethodHook statusHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (IgThemeEngine.isActive()) param.args[0] = IgThemeEngine.getActivePalette().statusBar;
            }
        };
        XC_MethodHook navHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (IgThemeEngine.isActive()) param.args[0] = IgThemeEngine.getActivePalette().navigation;
            }
        };
        if (!tryHookPhoneWindow(cl, statusHook, navHook)) tryHookPhoneWindow(null, statusHook, navHook);
    }

    private boolean tryHookPhoneWindow(ClassLoader cl, XC_MethodHook statusHook, XC_MethodHook navHook) {
        try {
            XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow", cl, "setStatusBarColor", int.class, statusHook);
            XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow", cl, "setNavigationBarColor", int.class, navHook);
            ModuleLog.line("(InstaEclipse | Theme): PhoneWindow color hooks installed");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Called after a settings sync so a theme change is visible immediately, rather than
     * waiting for the user to navigate away and back (which is the only other time the
     * Activity onResume/onCreate hooks would naturally re-run).
     */
    public static void refreshCurrentActivity() {
        Activity activity = UIHookManager.getCurrentActivity();
        if (activity == null || activity.isFinishing()) return;
        IgThemeEngine.ensureInitialized(activity);
        if (!FeatureFlags.customThemeEnabled) return;
        IgColorRemapEngine.ensureBuilt(activity);
        applyWindowColors(activity);
        activity.getWindow().getDecorView().post(activity::recreate);
    }

    static void applyWindowColors(Activity activity) {
        if (activity == null || !IgThemeEngine.isActive()) return;
        try {
            IgThemePalette palette = IgThemeEngine.getActivePalette();
            Window window = activity.getWindow();
            if (window == null) return;
            window.setStatusBarColor(palette.statusBar);
            window.setNavigationBarColor(palette.navigation);
            View decor = window.getDecorView();
            if (decor != null) decor.setBackgroundColor(palette.background);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setStatusBarContrastEnforced(false);
                window.setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    boolean lightBg = Color.red(palette.background) + Color.green(palette.background) + Color.blue(palette.background) > 382;
                    controller.setSystemBarsAppearance(lightBg ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
                return;
            }
            applyLegacySystemUiVisibility(window, palette.background);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    private static void applyLegacySystemUiVisibility(Window window, int backgroundColor) {
        int flags = window.getDecorView().getSystemUiVisibility();
        boolean lightBg = Color.red(backgroundColor) + Color.green(backgroundColor) + Color.blue(backgroundColor) > 382;
        if (lightBg) {
            flags = flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags = flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private static int[] typedArrayAttributes(TypedArray ta) {
        try {
            Field field = typedArrayAttrsField;
            if (field == null) {
                field = TypedArray.class.getDeclaredField("mAttributes");
                field.setAccessible(true);
                typedArrayAttrsField = field;
            }
            return (int[]) field.get(ta);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Resources typedArrayResources(TypedArray ta) {
        try {
            Field field = typedArrayResourcesField;
            if (field == null) {
                field = TypedArray.class.getDeclaredField("mResources");
                field.setAccessible(true);
                typedArrayResourcesField = field;
            }
            return (Resources) field.get(ta);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
