package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hides two header buttons that have no view id of their own:
 *  - the Threads button in a profile's action bar (right_action_bar_buttons), and
 *  - the Blend button in a DM thread header (header_right_buttons).
 * They are matched by their content description ("…Threads…" / "Blend…": product names that
 * Instagram keeps untranslated), inside those containers only. A layout listener on each
 * activity's window re-hides them whenever Instagram rebuilds the header.
 */
public class HideHeaderButtonsHook {
    private static final String TAG = "(IE|HideButtons) ";
    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private static int profileBarId, dmHeaderId;

    public void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!FeatureFlags.hideThreadsButton && !FeatureFlags.hideDmBlend) return;
                    View decor = ((Activity) param.thisObject).getWindow().getDecorView();
                    if (!watched.add(decor)) return;
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> scan(decor));
                    scan(decor);
                }
            });
            FeatureStatusTracker.setHooked("HideThreadsButton");
            FeatureStatusTracker.setHooked("HideDmBlend");
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void scan(View decor) {
        try {
            if (profileBarId == 0 || dmHeaderId == 0) {
                String pkg = decor.getContext().getPackageName();
                profileBarId = decor.getResources().getIdentifier("right_action_bar_buttons", "id", pkg);
                dmHeaderId = decor.getResources().getIdentifier("header_right_buttons", "id", pkg);
            }
            if (FeatureFlags.hideThreadsButton && profileBarId != 0)
                hideIn(decor.findViewById(profileBarId), "Threads", false);
            if (FeatureFlags.hideDmBlend && dmHeaderId != 0)
                hideIn(decor.findViewById(dmHeaderId), "Blend", true);
        } catch (Throwable ignored) {}
    }

    private static void hideIn(View container, String word, boolean prefix) {
        if (!(container instanceof ViewGroup g)) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            CharSequence d = c.getContentDescription();
            if (d == null || c.getVisibility() == View.GONE) continue;
            String s = d.toString();
            if (prefix ? s.startsWith(word) : s.contains(word)) c.setVisibility(View.GONE);
        }
    }
}
