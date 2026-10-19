package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Story tray (#283): a smaller tray, so more stories fit in a row, and a custom color for the ring
 * around unseen stories.
 *
 * <p>Size: the tray (com.instagram.android:id/reels_tray_container) holds the horizontal list of
 * stories. That list is scaled from its top left and widened by the same factor, so it still spans
 * the screen and shows more items; the container's height shrinks to match, so the feed moves up.
 * Android maps touches through the scale, so taps land on the right story.
 *
 * <p>Ring color: every story ring gradient is built by GradientSpinner's static
 * (GradientSpinner, float[], float[], float[], int[] colors, int, int) -> LinearGradient; the colors
 * are swapped there. Close Friends' green ring is left alone, so it keeps its meaning.
 */
public final class StoryTrayHook {

    private static final String SPINNER = "com.instagram.ui.widget.gradientspinner.GradientSpinner";
    private static final String TAG = "(IE|StoryTray) ";
    /** Tag on the tray list: its height before scaling. */
    private static final int ORIGINAL_HEIGHT_TAG = 0x7e1e0283;

    private static int trayId;
    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Set<View> pending = Collections.newSetFromMap(new WeakHashMap<>());

    private StoryTrayHook() {}

    public static void install(ClassLoader cl) {
        try {
            Class<?> spinner = cl.loadClass(SPINNER);
            int hooked = 0;
            for (Method m : spinner.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != android.graphics.LinearGradient.class) continue;
                int colorsArg = -1;
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) if (p[i] == int[].class) colorsArg = i;
                if (colorsArg < 0) continue;
                final int arg = colorsArg;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        int[] custom = parseColors(FeatureFlags.storyRingColor);
                        if (custom == null || !(param.args[arg] instanceof int[] colors) || colors.length == 0) return;
                        if (isCloseFriends(colors)) return;
                        param.args[arg] = spread(custom, colors.length);
                    }
                });
                hooked++;
            }
            ModuleLog.line(TAG + "ring gradient hooks=" + hooked);
            if (hooked > 0) FeatureStatusTracker.setHooked("StoryRingColor");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ ring: " + t);
        }
    }

    // ---- ring color ----

    /** "#RRGGBB" or "#RRGGBB,#RRGGBB" (a gradient); null for Instagram's own colors or invalid input. */
    public static int[] parseColors(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String[] parts = value.split(",");
        if (parts.length > 2) return null;
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.matches("#?[0-9A-Fa-f]{6}")) return null;
            out[i] = 0xFF000000 | Integer.parseInt(p.startsWith("#") ? p.substring(1) : p, 16);
        }
        return out;
    }

    /** {@code count} colors running from the first custom color to the last. */
    static int[] spread(int[] custom, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            if (custom.length == 1 || count == 1) { out[i] = custom[0]; continue; }
            float f = (float) i / (count - 1);
            out[i] = blend(custom[0], custom[custom.length - 1], f);
        }
        return out;
    }

    private static int blend(int a, int b, float f) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Close Friends' ring: every color a clear green. */
    private static boolean isCloseFriends(int[] colors) {
        float[] hsv = new float[3];
        for (int c : colors) {
            Color.colorToHSV(c, hsv);
            if (hsv[0] < 80 || hsv[0] > 170 || hsv[1] < 0.3f) return false;
        }
        return true;
    }

    // ---- tray size ----

    /** Called on every main / modal resume (UIHookManager); keeps the tray sized while it lays out. */
    public static void watchActivity(Activity a) {
        if (a == null) return;
        try {
            if (trayId == 0) {
                @SuppressLint("DiscouragedApi")
                int id = a.getResources().getIdentifier("reels_tray_container", "id", a.getPackageName());
                trayId = id;
            }
            if (trayId == 0) return;
            View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null) return;
            apply(decor);
            if (!watched.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (FeatureFlags.storyTrayScale >= 100 && !anyScaled(decor)) return;
                if (!pending.add(decor)) return;
                MAIN.postDelayed(() -> {
                    pending.remove(decor);
                    apply(decor);
                }, 150);
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ " + t);
        }
    }

    private static boolean anyScaled(View decor) {
        List<View> trays = new ArrayList<>();
        collect(decor, trays);
        for (View t : trays) if (t.getTag(ORIGINAL_HEIGHT_TAG) != null) return true;
        return false;
    }

    private static void apply(View decor) {
        List<View> trays = new ArrayList<>();
        collect(decor, trays);
        for (View tray : trays) scale((ViewGroup) tray, FeatureFlags.storyTrayScale);
    }

    private static void collect(View v, List<View> out) {
        if (v.getId() == trayId && v instanceof ViewGroup) { out.add(v); return; }
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
    }

    /** Scales the tray's list to {@code percent} of its size; 100 puts Instagram's layout back. */
    private static void scale(ViewGroup container, int percent) {
        if (container.getChildCount() == 0) return;
        View list = container.getChildAt(0);
        Object saved = list.getTag(ORIGINAL_HEIGHT_TAG);
        if (percent >= 100 || percent < 30) {
            if (saved == null) return;
            list.setScaleX(1f);
            list.setScaleY(1f);
            ViewGroup.LayoutParams lp = list.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = (int) saved;
            list.setLayoutParams(lp);
            ViewGroup.LayoutParams clp = container.getLayoutParams();
            clp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            container.setLayoutParams(clp);
            list.setTag(ORIGINAL_HEIGHT_TAG, null);
            return;
        }
        int height = saved instanceof Integer h ? h : list.getHeight();
        int width = container.getWidth();
        if (height <= 0 || width <= 0) return;
        float s = percent / 100f;
        int wantWidth = Math.round(width / s), wantHeight = Math.round(height * s);
        ViewGroup.LayoutParams lp = list.getLayoutParams();
        ViewGroup.LayoutParams clp = container.getLayoutParams();
        if (saved != null && list.getScaleX() == s && lp.width == wantWidth && clp.height == wantHeight) return;
        list.setTag(ORIGINAL_HEIGHT_TAG, height);
        list.setPivotX(0f);
        list.setPivotY(0f);
        list.setScaleX(s);
        list.setScaleY(s);
        lp.width = wantWidth;
        lp.height = height;
        list.setLayoutParams(lp);
        clp.height = wantHeight;
        container.setLayoutParams(clp);
        container.setClipChildren(true);
        FeatureStatusTracker.setHooked("StoryTraySize");
    }
}
