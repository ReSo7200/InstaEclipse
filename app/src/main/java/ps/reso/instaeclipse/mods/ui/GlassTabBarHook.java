package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.mods.ui.theme.IgColorRemapEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Glass navigation bar (#281): a floating, see-through, blurred bottom tab bar.
 *
 * Instagram already ships a floating tab bar (MainTabUtil styles FLOATING / FIXED /
 * FIXED_IN_FLOATING_POSITION) behind a MobileConfig gate. With the gate on, Instagram itself lays
 * the content out under the bar, publishes the content inset, moves badges and keeps the Reels tab
 * on its own fixed bar. So we:
 *  1. force that gate to true. It is the static boolean(UserSession) that InstagramMainActivity
 *     calls right before it stores the style in MainTabUtil's static style field; its class also
 *     has a static int(Context, UserSession) (the content inset). Anchored on the string
 *     "MainTabUtil.applyTabBarStyle" (the apply method) and on that field, never on X.* names or
 *     the MobileConfig id (which changes between builds: 452.0.0.0.60 vs 452.0.0.33.64).
 *  2. replace the drawing of Instagram's pill drawable (found at runtime: the drawable wrapped by
 *     the tab_bar background InsetDrawable) with a translucent pill in the bar's own color. On
 *     Android 12+ with hardware drawing, the content behind the pill is drawn into a RenderNode
 *     with a blur RenderEffect. The blur is refreshed only when something scrolls or lays out, so
 *     an idle screen costs nothing.
 *
 * Tabs, the profile avatar and badges are Instagram's own views and are not touched. Every step
 * is guarded: if a lookup fails we log and leave Instagram's bar as it is.
 */
public final class GlassTabBarHook {
    private static final String TAG = "(IE|GlassTabBar) ";
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String CACHE_GATE = "GlassTabBarGate";

    private static int tabBarId = 0;
    private static int tabBarShadowId = 0;
    private static boolean idsResolved;
    private static volatile boolean pillHooked;
    private static Class<?> pillClass;
    private static List<Field> pillPaints;

    private static final Set<View> watchedBars = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, Glass> glassByBar = new WeakHashMap<>();

    // ---------------------------------------------------------------------------------------------
    // 1) The floating tab bar gate
    // ---------------------------------------------------------------------------------------------

    public void install(DexKitBridge bridge, ClassLoader cl) {
        if (!FeatureFlags.glassTabBar) return; // the style is chosen when Instagram starts
        try {
            Method gate = null;
            if (DexKitCache.isCacheValid()) gate = DexKitCache.loadMethod(CACHE_GATE, cl);
            if (gate == null) {
                gate = findGate(bridge, cl);
                if (gate != null) DexKitCache.saveMethod(CACHE_GATE, gate);
            }
            if (gate == null) return; // findGate logged why
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (FeatureFlags.glassTabBar) param.setResult(true);
                }
            });
            FeatureStatusTracker.setHooked("GlassTabBar");
            ModuleLog.line(TAG + "✅ floating gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static Method findGate(DexKitBridge bridge, ClassLoader cl) throws Throwable {
        // MainTabUtil.applyTabBarStyle(Activity, UserSession, Style)
        Method apply = null;
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("MainTabUtil.applyTabBarStyle")))) {
            Method m;
            try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && Activity.class.isAssignableFrom(p[0]) && p[2].isEnum()) { apply = m; break; }
        }
        if (apply == null) {
            ModuleLog.line(TAG + "❌ MainTabUtil.applyTabBarStyle not found");
            return null;
        }
        Class<?> util = apply.getDeclaringClass();
        Class<?> style = apply.getParameterTypes()[2];
        Field styleField = null;
        for (Field f : util.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()) && f.getType() == style) {
                styleField = f;
                break;
            }
        }
        if (styleField == null) {
            ModuleLog.line(TAG + "❌ style field not found in " + util.getName());
            return null;
        }
        String fieldDesc = "L" + util.getName().replace('.', '/') + ";->" + styleField.getName()
                + ":L" + style.getName().replace('.', '/') + ";";
        Class<?> session = cl.loadClass(SESSION);
        Set<Method> gates = new LinkedHashSet<>();
        for (MethodData caller : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(MAIN_ACTIVITY).addUsingField(fieldDesc)))) {
            for (MethodData inv : caller.getInvokes()) {
                if (!inv.isMethod()) continue;
                Method m;
                try { m = inv.getMethodInstance(cl); } catch (Throwable t) { continue; }
                Class<?>[] p = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != boolean.class
                        || p.length != 1 || p[0] != session) continue;
                if (hasInsetSibling(m.getDeclaringClass(), session)) gates.add(m);
            }
        }
        if (gates.size() != 1) {
            ModuleLog.line(TAG + "❌ floating gate candidates: " + gates);
            return null;
        }
        return gates.iterator().next();
    }

    /** The gate's class also has a static int(Context, UserSession): the floating content inset. */
    private static boolean hasInsetSibling(Class<?> c, Class<?> session) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == int.class
                    && p.length == 2 && p[0] == Context.class && p[1] == session) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 2) The glass look
    // ---------------------------------------------------------------------------------------------

    /** Called on every resume of the main activity (UIHookManager.setupHooks). */
    public static void watchActivity(Activity a) {
        if (a == null || !FeatureFlags.glassTabBar) return;
        try {
            ensureIds(a);
            if (tabBarId == 0) return;
            View bar = a.findViewById(tabBarId);
            if (bar == null) return;
            hookPill(bar);
            if (!watchedBars.add(bar)) return;
            // Refresh the blur when the content behind moves. RecyclerView reports its scrolls
            // through onScrollChanged, so feed / profile / pager swipes all land here; an idle
            // screen triggers nothing (no per-frame redraw).
            bar.getViewTreeObserver().addOnScrollChangedListener(() -> refresh(bar));
            bar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                hookPill(bar);
                refresh(bar);
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ watch: " + t);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Activity a) {
        if (idsResolved) return;
        String pkg = a.getPackageName();
        tabBarId = a.getResources().getIdentifier("tab_bar", "id", pkg);
        tabBarShadowId = a.getResources().getIdentifier("tab_bar_shadow", "id", pkg);
        idsResolved = true;
    }

    private static void refresh(View bar) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        if (!(bar.getBackground() instanceof InsetDrawable bg) || pillClass == null) return;
        Drawable inner = bg.getDrawable();
        if (inner != null && inner.getClass() == pillClass && bar.isShown()) bar.invalidate();
    }

    /** Hooks draw() of the drawable class Instagram uses for the floating pill (once). */
    private static synchronized void hookPill(View bar) {
        if (pillHooked) return;
        if (!(bar.getBackground() instanceof InsetDrawable bg)) return; // not floating (yet)
        Drawable inner = bg.getDrawable();
        if (inner == null) return;
        Class<?> c = inner.getClass();
        if (c.getName().startsWith("android.")) return; // only Instagram's own pill drawable
        List<Field> paints = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == Paint.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                paints.add(f);
            }
        }
        Method draw;
        try {
            draw = c.getDeclaredMethod("draw", Canvas.class);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ pill draw(): " + t);
            pillHooked = true; // don't retry every layout
            return;
        }
        pillPaints = paints;
        pillClass = c;
        pillHooked = true;
        XposedBridge.hookMethod(draw, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (!FeatureFlags.glassTabBar) return;
                try {
                    Drawable d = (Drawable) param.thisObject;
                    View host = hostBar(d);
                    if (host == null) return; // the same drawable class used somewhere else
                    Rect b = d.getBounds();
                    if (b.isEmpty()) return;
                    Glass g;
                    synchronized (glassByBar) {
                        g = glassByBar.get(host);
                        if (g == null) {
                            g = new Glass(host.getResources().getDisplayMetrics().density);
                            glassByBar.put(host, g);
                        }
                    }
                    g.draw((Canvas) param.args[0], b, host, fillColor(d));
                    param.setResult(null);
                } catch (Throwable t) {
                    // fall through to Instagram's own drawing
                }
            }
        });
        ModuleLog.line(TAG + "✅ pill drawable " + c.getName() + " (" + paints.size() + " paints)");
    }

    /** The tab bar view a pill drawable belongs to (InsetDrawable callback chain), else null. */
    private static View hostBar(Drawable d) {
        Drawable.Callback cb = d.getCallback();
        if (cb instanceof Drawable wrapper) cb = wrapper.getCallback();
        if (cb instanceof View v && v.getId() == tabBarId && tabBarId != 0) return v;
        return null;
    }

    /** The pill's fill color: Instagram sets it on one Paint (the other one is the shadow). */
    private static int fillColor(Drawable d) {
        int color = Color.BLACK;
        boolean found = false;
        List<Field> paints = pillPaints;
        if (paints != null) {
            for (Field f : paints) {
                try {
                    Paint p = (Paint) f.get(d);
                    if (p == null) continue;
                    int c = p.getColor();
                    if (!found || (c | 0xFF000000) != Color.BLACK) {
                        color = c;
                        found = true;
                    }
                    if ((c | 0xFF000000) != Color.BLACK) break;
                } catch (Throwable ignored) {}
            }
        }
        if (IgThemeEngine.isActive()) {
            try { color = IgColorRemapEngine.remap(color); } catch (Throwable ignored) {}
        }
        return color;
    }

    /** Draws one tab bar's glass pill. Main thread only. */
    private static final class Glass {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Object clipNode, blurNode; // RenderNode (API 31+), kept as Object for older devices
        private boolean drawing;

        Glass(float density) {
            this.density = density;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density * 0.75f));
        }

        void draw(Canvas c, Rect b, View bar, int color) {
            rect.set(b);
            float radius = rect.height() / 2f;
            boolean dark = luminance(color) < 0.5f;
            boolean blurred = false;
            if (Build.VERSION.SDK_INT >= 31 && c.isHardwareAccelerated() && !drawing) {
                drawing = true;
                try {
                    blurred = drawBlur(c, b, bar, color | 0xFF000000, radius);
                } catch (Throwable t) {
                    blurred = false;
                } finally {
                    drawing = false;
                }
            }
            // Tint: more opaque without blur so the icons stay readable over busy content.
            float alpha = blurred ? (dark ? 0.55f : 0.68f) : 0.86f;
            int a = Math.round(Color.alpha(color) * alpha);
            fill.setColor((color & 0x00FFFFFF) | (a << 24));
            c.drawRoundRect(rect, radius, radius, fill);
            edge.setColor(dark ? 0x26FFFFFF : 0x14000000);
            float inset = edge.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            c.drawRoundRect(rect, radius - inset, radius - inset, edge);
        }

        /** Blurs what is drawn behind the bar (its earlier siblings) into the pill. */
        @android.annotation.TargetApi(31)
        private boolean drawBlur(Canvas c, Rect b, View bar, int opaque, float radius) {
            if (!(bar.getParent() instanceof ViewGroup parent)) return false;
            int w = b.width(), h = b.height();
            if (clipNode == null) {
                RenderNode clip = new RenderNode("ie_glass_clip");
                RenderNode blur = new RenderNode("ie_glass_blur");
                float r = 20f * density;
                blur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                clipNode = clip;
                blurNode = blur;
            }
            RenderNode clip = (RenderNode) clipNode;
            RenderNode blur = (RenderNode) blurNode;

            blur.setPosition(0, 0, w, h);
            RecordingCanvas rc = blur.beginRecording(w, h);
            try {
                rc.drawColor(opaque);
                float barX = bar.getLeft() + bar.getTranslationX() + b.left;
                float barY = bar.getTop() + bar.getTranslationY() + b.top;
                int idx = parent.indexOfChild(bar);
                for (int i = 0; i < idx; i++) {
                    View v = parent.getChildAt(i);
                    if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) continue;
                    if (v.getId() == tabBarShadowId && tabBarShadowId != 0) continue;
                    float dx = v.getLeft() + v.getTranslationX() - barX;
                    float dy = v.getTop() + v.getTranslationY() - barY;
                    if (dx >= w || dy >= h || dx + v.getWidth() <= 0 || dy + v.getHeight() <= 0) continue;
                    int save = rc.save();
                    rc.translate(dx - v.getScrollX(), dy - v.getScrollY());
                    v.draw(rc);
                    rc.restoreToCount(save);
                }
            } finally {
                blur.endRecording();
            }

            clip.setPosition(b.left, b.top, b.right, b.bottom);
            Outline o = new Outline();
            o.setRoundRect(0, 0, w, h, radius);
            clip.setOutline(o);
            clip.setClipToOutline(true);
            RecordingCanvas cc = clip.beginRecording(w, h);
            try {
                cc.drawRenderNode(blur);
            } finally {
                clip.endRecording();
            }
            c.drawRenderNode(clip);
            return true;
        }

        private static float luminance(int color) {
            return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
        }
    }
}
