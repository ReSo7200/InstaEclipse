package ps.reso.instaeclipse.mods.ui.theme;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Colors that never pass through Resources/Context/TypedArray.getColor: text colors and tints
 * (ColorStateList), plain color backgrounds (ColorDrawable / GradientDrawable) and icon tints
 * (color filters). Hooked where those colors are created, not where they are drawn — drawing
 * (Paint.setColor) runs every frame and is too hot for a hook on Android 13's JIT.
 */
final class IgThemeColorSourceHooks {

    // Source list -> its themed copy, and every themed copy we produced (so it is never re-themed).
    private static final Map<ColorStateList, ColorStateList> CSL_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<ColorStateList> THEMED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile int cacheGeneration = -1;

    private IgThemeColorSourceHooks() {}

    static void install(ClassLoader cl) {
        int n = 0;
        n += hookComposeDrawing(cl);
        n += hookCslResult(TypedArray.class, "getColorStateList", int.class);
        n += hookCslResult(Resources.class, "getColorStateList", int.class, Resources.Theme.class);
        n += hookCslResult(Resources.class, "getColorStateList", int.class);
        n += hookColorArg(ColorStateList.class, "valueOf", int.class);
        n += hookColorArg(ColorDrawable.class, "setColor", int.class);
        n += hookColorArgCtor(ColorDrawable.class, int.class);
        n += hookCachedColorDrawables();
        n += hookColorDrawableDraw();
        n += hookColorArg(GradientDrawable.class, "setColor", int.class);
        n += hookCslArg(GradientDrawable.class, "setColor", ColorStateList.class);
        n += hookColorArgCtor(PorterDuffColorFilter.class, int.class, PorterDuff.Mode.class);
        if (Build.VERSION.SDK_INT >= 29) n += hookColorArgCtor(BlendModeColorFilter.class, int.class, BlendMode.class);
        ModuleLog.line("(InstaEclipse | Theme): color-source hooks installed=" + n);
    }

    private static boolean active() {
        return IgThemeEngine.isActive() && !IgColorRemapEngine.isBypassing() && IgColorRemapEngine.isReady();
    }

    /** The themed copy of {@code in} (same states, every color remapped), cached per source list. */
    static ColorStateList theme(ColorStateList in) {
        if (in == null || !active() || THEMED.contains(in)) return in;
        int gen = IgColorRemapEngine.generation();
        if (gen != cacheGeneration) {
            CSL_CACHE.clear();
            cacheGeneration = gen;
        }
        ColorStateList cached = CSL_CACHE.get(in);
        if (cached != null) return cached;
        ColorStateList out = in;
        try {
            int[][] states = (int[][]) XposedHelpers.getObjectField(in, "mStateSpecs");
            int[] colors = (int[]) XposedHelpers.getObjectField(in, "mColors");
            if (states != null && colors != null && states.length == colors.length) {
                int[] mapped = new int[colors.length];
                boolean changed = false;
                for (int i = 0; i < colors.length; i++) {
                    mapped[i] = IgColorRemapEngine.remap(colors[i]);
                    changed |= mapped[i] != colors[i];
                }
                if (changed) {
                    out = new ColorStateList(states, mapped);
                    THEMED.add(out);
                }
            }
        } catch (Throwable ignored) {}
        CSL_CACHE.put(in, out);
        return out;
    }

    private static int hookCslResult(Class<?> cls, String name, Object... params) {
        Object[] args = append(params, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                JitGuard.tick(p.method);
                if (p.getThrowable() != null || !(p.getResult() instanceof ColorStateList)) return;
                ColorStateList themed = theme((ColorStateList) p.getResult());
                if (themed != p.getResult()) p.setResult(themed);
            }
        });
        return hook(() -> XposedHelpers.findAndHookMethod(cls, name, args), cls, name);
    }

    private static int hookCslArg(Class<?> cls, String name, Object... params) {
        Object[] args = append(params, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                JitGuard.tick(p.method);
                if (p.args[0] instanceof ColorStateList) p.args[0] = theme((ColorStateList) p.args[0]);
            }
        });
        return hook(() -> XposedHelpers.findAndHookMethod(cls, name, args), cls, name);
    }

    private static final XC_MethodHook REMAP_FIRST_INT = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            JitGuard.tick(p.method);
            if (!active() || !(p.args[0] instanceof Integer)) return;
            int c = (Integer) p.args[0];
            int r = IgColorRemapEngine.remap(c);
            if (r != c) p.args[0] = r;
        }
    };

    private static int hookColorArg(Class<?> cls, String name, Object... params) {
        Object[] args = append(params, REMAP_FIRST_INT);
        return hook(() -> XposedHelpers.findAndHookMethod(cls, name, args), cls, name);
    }

    private static int hookColorArgCtor(Class<?> cls, Object... params) {
        Object[] args = append(params, REMAP_FIRST_INT);
        return hook(() -> XposedHelpers.findAndHookConstructor(cls, args), cls, "<init>");
    }

    /**
     * Color backgrounds reused from Android's drawable cache are rebuilt from a shared ColorState via
     * a private constructor, so a color cached before the theme was ready would stay stock forever.
     * Recolor those copies (mutated first, so the shared cached state itself is left alone).
     */
    private static int hookCachedColorDrawables() {
        try {
            ps.reso.instaeclipse.xposedcompat.XposedBridge.hookAllConstructors(ColorDrawable.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    JitGuard.tick(p.method);
                    if (p.args.length != 2 || !active()) return;
                    ColorDrawable d = (ColorDrawable) p.thisObject;
                    int c = d.getColor();
                    int r = IgColorRemapEngine.remap(c);
                    if (r != c) {
                        d.mutate();
                        d.setColor(r);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Theme): ⚠️ ColorDrawable(state): " + t);
            return 0;
        }
    }

    // Compose screens (e.g. the DM inbox) draw straight onto an android.graphics.Paint and never
    // touch resources, so their colors are themed at Paint.setColor — but only while a Compose view
    // is recording its drawing (UI thread), leaving ordinary views and the module's own UI alone.
    private static int composeDrawDepth;

    private static int hookComposeDrawing(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("androidx.compose.ui.platform.AndroidComposeView", cl,
                    "dispatchDraw", android.graphics.Canvas.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            JitGuard.tick(p.method);
                            composeDrawDepth++;
                        }

                        @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                            if (composeDrawDepth > 0) composeDrawDepth--;
                        }
                    });
            XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setColor", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (composeDrawDepth == 0) return;
                    JitGuard.tick(p.method);
                    if (!active()) return;
                    int c = (Integer) p.args[0];
                    int r = IgColorRemapEngine.remap(c);
                    if (r != c) p.args[0] = r;
                }
            });
            if (Build.VERSION.SDK_INT >= 29) {
                XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setColor", long.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (composeDrawDepth == 0) return;
                        JitGuard.tick(p.method);
                        long c = (Long) p.args[0];
                        if (!active() || !android.graphics.Color.isSrgb(c)) return;
                        int argb = android.graphics.Color.toArgb(c);
                        int r = IgColorRemapEngine.remap(argb);
                        if (r != argb) p.args[0] = android.graphics.Color.pack(r);
                    }
                });
            }
            return 1;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Theme): ⚠️ compose drawing: " + t);
            return 0;
        }
    }

    // Drawables already checked: true = belongs to the module's own UI (left alone).
    private static final Map<Drawable, Boolean> DRAW_SEEN = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The constructor/setColor hooks miss call sites where ART inlined those tiny methods into
     * precompiled framework code (e.g. a header's plain white background). draw() is a virtual
     * call every background goes through, so it is the dependable place: recolor once, after which
     * the themed color maps to itself and each later frame costs a single table lookup.
     */
    private static int hookColorDrawableDraw() {
        try {
            XposedHelpers.findAndHookMethod(ColorDrawable.class, "draw", android.graphics.Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    JitGuard.tick(p.method);
                    if (!active()) return;
                    ColorDrawable d = (ColorDrawable) p.thisObject;
                    Boolean moduleUi = DRAW_SEEN.get(d);
                    if (moduleUi == null) {
                        Object cb = d.getCallback();
                        moduleUi = cb instanceof android.view.View && IgColorRemapEngine.isModuleUiView((android.view.View) cb);
                        DRAW_SEEN.put(d, moduleUi);
                    }
                    if (moduleUi) return;
                    int c = d.getColor();
                    int r = IgColorRemapEngine.remap(c);
                    if (r != c) {
                        d.mutate();
                        d.setColor(r);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Theme): ⚠️ ColorDrawable.draw: " + t);
            return 0;
        }
    }

    private static Object[] append(Object[] params, Object hook) {
        Object[] out = new Object[params.length + 1];
        System.arraycopy(params, 0, out, 0, params.length);
        out[params.length] = hook;
        return out;
    }

    private static int hook(Runnable r, Class<?> cls, String name) {
        try {
            r.run();
            return 1;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | Theme): ⚠️ " + cls.getSimpleName() + "." + name + ": " + t);
            return 0;
        }
    }
}
