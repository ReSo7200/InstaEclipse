package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Back leaves Home (#272): on the home feed, scrolled down, Back leaves Instagram instead of
 * scrolling the feed to the top.
 *
 * <p>The main activity's onBackPressed is hooked by name (an Android method, so no lookup). Back is
 * taken over only when the home feed (MainFeedRecyclerView, a named class) is what is in front:
 * the view under the middle of the screen belongs to it and no bottom sheet (comments, menus) is
 * open. A profile, a story or a sheet opened from the feed covers it, so Back keeps going to
 * Instagram there. At the top of the feed Instagram already leaves, so that is left alone too.
 */
public final class BackLeavesHomeHook {

    private static final String TAG = "(IE|BackLeavesHome) ";
    private static final String MAIN_CLASS = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String FEED_LIST = "MainFeedRecyclerView";
    private static int sheetId = -1;
    private static Class<?> mainActivity;

    public void install(ClassLoader cl) {
        try {
            Method back = null;
            mainActivity = cl.loadClass(MAIN_CLASS);
            for (Class<?> c = mainActivity; c != null && back == null; c = c.getSuperclass()) {
                try { back = c.getDeclaredMethod("onBackPressed"); } catch (NoSuchMethodException ignored) { }
            }
            if (back == null) {
                ModuleLog.line(TAG + "❌ onBackPressed not found");
                return;
            }
            XposedBridge.hookMethod(back, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.backLeavesHome || !(param.thisObject instanceof Activity a)
                            || !mainActivity.isInstance(a)) return;
                    try {
                        if (!homeFeedInFront(a)) return;
                        a.moveTaskToBack(true);
                        param.setResult(null);
                    } catch (Throwable ignored) { }
                }
            });
            FeatureStatusTracker.setHooked("BackLeavesHome");
            ModuleLog.line(TAG + "✅ " + back.getDeclaringClass().getName() + ".onBackPressed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static boolean homeFeedInFront(Activity a) {
        View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
        if (!(decor instanceof ViewGroup root)) return false;
        if (sheetId == -1) sheetId = a.getResources().getIdentifier("bottom_sheet_container_view", "id", a.getPackageName());
        if (sheetId != 0 && anyShown(root, sheetId)) return false;
        View top = topViewAt(root, root.getWidth() / 2, (int) (root.getHeight() * 0.45f));
        for (View v = top; v != null; v = v.getParent() instanceof View p ? p : null) {
            if (v.getClass().getName().endsWith(FEED_LIST)) return v.canScrollVertically(-1);
        }
        return false;
    }

    /** The front-most shown view under (x, y): later children draw on top. */
    private static View topViewAt(ViewGroup group, int x, int y) {
        Rect r = new Rect();
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (!child.isShown() || child.getAlpha() <= 0f || !child.getGlobalVisibleRect(r) || !r.contains(x, y)) continue;
            if (child instanceof ViewGroup g) {
                View inner = topViewAt(g, x, y);
                if (inner != null) return inner;
                // An empty, see-through container (Instagram keeps a few stacked over the screen)
                // covers nothing: look at what is under it.
                if (g.getBackground() == null && !g.isClickable()) continue;
                return child;
            }
            return child;
        }
        return null;
    }

    private static boolean anyShown(ViewGroup group, int id) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getId() == id && child.isShown() && child.getHeight() > 0) return true;
            if (child instanceof ViewGroup g && anyShown(g, id)) return true;
        }
        return false;
    }
}
