package ps.reso.instaeclipse.mods.ui;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Proper icons for the rows we add to Instagram's post / reel "..." menus. Those rows ride on
 * internal debug options, which Instagram draws with the Meta logo. Once the sheet is on screen
 * we find our rows by their label (control_option_text) and set a fitting Instagram icon on the
 * row's control_option_icon; a pre-draw listener re-applies it when the list re-binds or scrolls.
 */
public final class MenuIcons {

    private MenuIcons() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Weak maps rather than view tags: our R.id values could collide with Instagram's tag keys.
    private static final Map<View, Boolean> WATCHED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final Map<ImageView, Integer> APPLIED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static volatile long lastStart;
    private static Map<String, String> iconsByLabel;

    /** Label -> Instagram drawable name, in the current language. */
    private static Map<String, String> icons() {
        Map<String, String> m = iconsByLabel;
        if (m != null) return m;
        Context c = AndroidAppHelper.currentApplication();
        if (c == null) return new HashMap<>();
        m = new HashMap<>();
        m.put(I18n.t(c, R.string.ig_media_info_title), "instagram_info_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_playback_speed_title), "instagram_circle_play_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_download_choice_copy_image), "instagram_photo_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_caption_copy_menu_item), "instagram_copy_pano_outline_24");
        m.put(I18n.t(c, R.string.ig_caption_translate_menu_item), "instagram_translate_pano_outline_24");
        iconsByLabel = m;
        return m;
    }

    /** Call when our rows were just added to a menu; fixes their icons once the sheet shows. */
    public static void fixSoon() {
        try {
            start();
        } catch (Throwable t) {
            ModuleLog.line("(IE|MenuIcons) start: " + t);
        }
    }

    private static void start() {
        long now = SystemClock.uptimeMillis();
        if (now - lastStart < 300) return;
        lastStart = now;
        iconsByLabel = null; // pick up a language change
        final int[] tries = {0};
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean found = false;
                try {
                    for (View root : rootViews()) found |= watch(root);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|MenuIcons) " + t);
                }
                if (!found && ++tries[0] < 25) MAIN.postDelayed(this, 80);
            }
        }, 60);
    }

    /** Applies once and keeps re-applying while this window is showing. */
    private static boolean watch(View root) {
        if (!apply(root)) return false;
        try {
            listen(root);
        } catch (Throwable t) {
            ModuleLog.line("(IE|MenuIcons) listen: " + t);
        }
        return true;
    }

    private static void listen(View root) {
        if (WATCHED.put(root, Boolean.TRUE) == null) {
            // Pre-draw (throttled), not global layout: rows scrolled into view are bound without a
            // layout pass on the window, so a layout listener misses them.
            final long[] last = {0};
            final Runnable catchUp = () -> apply(root); // the frame skipped by the throttle
            ViewTreeObserver.OnPreDrawListener l = () -> {
                long t = SystemClock.uptimeMillis();
                if (t - last[0] > 80) { last[0] = t; apply(root); }
                else { root.removeCallbacks(catchUp); root.postDelayed(catchUp, 100); }
                return true;
            };
            root.getViewTreeObserver().addOnPreDrawListener(l);
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnPreDrawListener(l);
                    WATCHED.remove(v);
                    v.removeOnAttachStateChangeListener(this);
                }
            });
        }
    }

    /** True if at least one of our rows is in this window. Never throws: this also runs from a
     *  pre-draw listener on Instagram's UI thread, where an exception would crash the app; on any
     *  failure the row simply keeps Instagram's own icon. */
    private static boolean apply(View root) {
        try {
            return applyUnsafe(root);
        } catch (Throwable t) {
            ModuleLog.line("(IE|MenuIcons) apply: " + t);
            return false;
        }
    }

    private static boolean applyUnsafe(View root) {
        Context c = root.getContext();
        int textId = c.getResources().getIdentifier("control_option_text", "id", c.getPackageName());
        int iconId = c.getResources().getIdentifier("control_option_icon", "id", c.getPackageName());
        if (textId == 0 || iconId == 0) return false;
        Map<String, String> map = icons();
        boolean any = false;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v.getId() == textId && v instanceof TextView tv) {
                String icon = tv.getText() == null ? null : map.get(tv.getText().toString());
                if (icon == null) continue;
                any = true;
                if (!(tv.getParent() instanceof ViewGroup row)) continue;
                View iv = row.findViewById(iconId);
                if (!(iv instanceof ImageView img)) continue;
                int res = c.getResources().getIdentifier(icon, "drawable", c.getPackageName());
                if (res == 0) res = c.getResources().getIdentifier(icon.replace("_pano", ""), "drawable", c.getPackageName());
                if (res == 0 || Integer.valueOf(res).equals(APPLIED.get(img))) continue;
                img.setImageResource(res); // keeps the row's tint
                APPLIED.put(img, res);
            } else if (v instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
            }
        }
        return any;
    }

    /** Every window's root view (activity, dialogs, bottom sheets). */
    @SuppressWarnings("unchecked")
    private static List<View> rootViews() {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object inst = wmg.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = wmg.getDeclaredField("mViews");
            f.setAccessible(true);
            synchronized (inst) {
                return new java.util.ArrayList<>((List<View>) f.get(inst));
            }
        } catch (Throwable t) {
            List<View> out = new java.util.ArrayList<>();
            android.app.Activity a = UIHookManager.getCurrentActivity();
            if (a != null && a.getWindow() != null) out.add(a.getWindow().getDecorView());
            return out;
        }
    }
}
