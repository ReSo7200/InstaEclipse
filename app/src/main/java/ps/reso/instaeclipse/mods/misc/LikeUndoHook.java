package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Like undo window (#235): a like is held for {@link #DELAY_MS} behind a small "Liked · UNDO" bar
 * and only then sent; tapping UNDO drops it.
 *
 * Every post/reel like and unlike request goes through one builder (X.0E0b.A05 on 452,
 * X.0A0e.A05 on 451) — the feed double-tap/heart, reels and the other surfaces call it. Instagram
 * flips the media's local has_liked state BEFORE calling it, so "media reads as liked" means this
 * call is a like (unlikes are sent immediately). Anchor: "source_of_like" + "fetchLikeCount".
 *
 * Undo only cancels the request; the heart may stay filled until the post is refreshed.
 */
public class LikeUndoHook {

    private static final String TAG = "(InstaEclipse | LikeUndo): ";
    private static final String CACHE_KEY = "LikeUndoSender";
    private static final long DELAY_MS = 4000;
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public void install(DexKitBridge bridge) {
        Method target = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader) : null;
        if (target == null) {
            try {
                List<MethodData> found = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("source_of_like", "fetchLikeCount")
                        .returnType("void")));
                for (MethodData md : found) {
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    for (Class<?> p : m.getParameterTypes()) {
                        if (p.getName().equals("com.instagram.feed.media.Media")) { target = m; break; }
                    }
                    if (target != null) break;
                }
                if (target == null) {
                    ModuleLog.line(TAG + "❌ like sender not found (" + found.size() + " candidates)");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, target);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ " + t);
                return;
            }
        }
        final Method sender = target;
        XposedBridge.hookMethod(sender, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.likeUndo || REPLAYING.get() == Boolean.TRUE) return;
                Object media = null;
                for (Object a : param.args) {
                    if (a != null && a.getClass().getName().equals("com.instagram.feed.media.Media")) { media = a; break; }
                }
                if (media == null || !HideSuggestedFeedItemsHook.isLiked(media)) return; // unlike → send now
                Activity act = UIHookManager.getCurrentActivity();
                if (act == null) return;
                param.setResult(null);
                hold(act, param.method, param.thisObject, param.args.clone());
            }
        });
        ModuleLog.line(TAG + "✅ Hooked " + sender.getDeclaringClass().getName() + "." + sender.getName());
        FeatureStatusTracker.setHooked("LikeUndo");
    }

    private static void hold(Activity act, Member method, Object self, Object[] args) {
        ModuleLog.line(TAG + "like held for " + DELAY_MS + " ms");
        MAIN.post(() -> {
            final boolean[] undone = {false};
            final View bar = showBar(act, () -> undone[0] = true);
            MAIN.postDelayed(() -> {
                removeBar(bar);
                if (undone[0]) {
                    ModuleLog.line(TAG + "like cancelled by UNDO");
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_like_undo_cancelled), Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    REPLAYING.set(Boolean.TRUE);
                    XposedBridge.invokeOriginalMethod(method, self, args);
                    ModuleLog.line(TAG + "like sent after the undo window");
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "⚠️ send failed: " + t);
                } finally {
                    REPLAYING.remove();
                }
            }, DELAY_MS);
        });
    }

    private static View showBar(Activity act, Runnable onUndo) {
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            float d = act.getResources().getDisplayMetrics().density;
            TextView tv = new TextView(act);
            tv.setText(I18n.t(act, R.string.ig_like_undo_bar));
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            int ph = (int) (16 * d), pv = (int) (12 * d);
            tv.setPadding(ph, pv, ph, pv);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE262626);
            bg.setCornerRadius(12 * d);
            tv.setBackground(bg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = (int) (110 * d);
            tv.setOnClickListener(v -> {
                onUndo.run();
                removeBar(v);
            });
            decor.addView(tv, lp);
            return tv;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ bar: " + t);
            return null;
        }
    }

    private static void removeBar(View bar) {
        if (bar != null && bar.getParent() instanceof ViewGroup g) g.removeView(bar);
    }
}
