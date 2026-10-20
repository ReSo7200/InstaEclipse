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

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

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
 *
 * <p>Ask before liking holds the heart button's tap before Instagram fills it (see
 * {@link #hookHeartButtons}); other likes (double tap) are held here at the sender.
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
                List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
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
                if (!(FeatureFlags.likeUndo || FeatureFlags.confirmLikes) || REPLAYING.get() == Boolean.TRUE) return;
                Object media = null;
                for (Object a : param.args) {
                    if (a != null && a.getClass().getName().equals("com.instagram.feed.media.Media")) { media = a; break; }
                }
                if (media == null || !HideSuggestedFeedItemsHook.isLiked(media)) return; // unlike → send now
                // A heart tap confirmed a moment ago: Instagram sends it now, unless Undo holds it.
                boolean confirmed = !FeatureFlags.confirmLikes || System.currentTimeMillis() <= confirmedUntil;
                if (confirmed && !FeatureFlags.likeUndo) return;
                Activity act = UIHookManager.getCurrentActivity();
                if (act == null) return;
                param.setResult(null);
                Member method = param.method;
                Object self = param.thisObject;
                Object[] args = param.args.clone();
                if (!confirmed) MAIN.post(() -> confirm(act, () -> {
                    if (FeatureFlags.likeUndo) hold(act, method, self, args);
                    else send(method, self, args);
                }));
                else hold(act, method, self, args);
            }
        });
        ModuleLog.line(TAG + "✅ Hooked " + sender.getDeclaringClass().getName() + "." + sender.getName());
        FeatureStatusTracker.setHooked("LikeUndo");
        hookHeartButtons();
        hookLikeEntry(sender);
        FeatureStatusTracker.setHooked("ConfirmLikes");
    }

    /** Post like buttons (feed, carousel slide, reels): their heart shows "selected" once liked. */
    private static final String[] HEARTS = {"row_feed_button_like", "row_feed_media_per_media_like_button", "clips_ufi_like_button"};
    private static final java.util.Set<Integer> heartIds = new java.util.HashSet<>();
    private static volatile long confirmedUntil;
    private static boolean replayingTap;

    /**
     * Ask before liking holds the heart tap itself, before Instagram fills the heart, and replays it
     * on Like; the like then passes the sender for a moment. Double-tap likes have no button, so
     * they are still held at the sender (the heart may stay filled after Cancel).
     */
    private static void hookHeartButtons() {
        XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.confirmLikes || replayingTap) return;
                View v = (View) param.thisObject;
                if (heartIds.isEmpty()) {
                    for (String name : HEARTS) {
                        int id = v.getResources().getIdentifier(name, "id", v.getContext().getPackageName());
                        if (id != 0) heartIds.add(id);
                    }
                    if (heartIds.isEmpty()) heartIds.add(-1);
                }
                // The heart itself, or the unnamed touch area that wraps it (feed).
                View heart = heartIds.contains(v.getId()) ? v : null;
                if (heart == null && v instanceof ViewGroup g)
                    for (int i = 0; i < g.getChildCount() && heart == null; i++)
                        if (heartIds.contains(g.getChildAt(i).getId())) heart = g.getChildAt(i);
                if (heart == null || heart.isSelected()) return; // a filled heart: this is an unlike
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Activity act = activityOf(v.getContext());
                if (act == null) return;
                param.setResult(true);
                MAIN.post(() -> confirm(act, () -> {
                    confirmedUntil = System.currentTimeMillis() + 3000;
                    replayingTap = true;
                    try { v.performClick(); } finally { replayingTap = false; }
                }));
            }
        });
    }

    /**
     * Ask before liking, for hearts that aren't Views (the Compose feed heart on 451): the like
     * entry (X.0A0e.A04 on 451, X.0E0b.A04 on 452; it takes the Activity and the Media, in the
     * sender's class) updates the post's like state and then calls the sender. Held here, before
     * the heart fills, so Cancel leaves it empty; Like replays it.
     */
    private static void hookLikeEntry(Method sender) {
        int hooked = 0;
        for (Method m : sender.getDeclaringClass().getDeclaredMethods()) {
            if (m.equals(sender) || m.getReturnType() != void.class
                    || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            boolean activity = false, media = false;
            for (Class<?> p : m.getParameterTypes()) {
                if (p == Activity.class) activity = true;
                if (p.getName().equals("com.instagram.feed.media.Media")) media = true;
            }
            if (!activity || !media) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.confirmLikes || REPLAYING.get() == Boolean.TRUE
                            || System.currentTimeMillis() <= confirmedUntil) return;
                    Object media = null;
                    for (Object a : param.args) {
                        if (a != null && a.getClass().getName().equals("com.instagram.feed.media.Media")) { media = a; break; }
                    }
                    if (media == null || HideSuggestedFeedItemsHook.isLiked(media)) return; // already liked: not a new like
                    Activity act = UIHookManager.getCurrentActivity();
                    if (act == null) return;
                    param.setResult(null);
                    Member method = param.method;
                    Object self = param.thisObject;
                    Object[] args = param.args.clone();
                    MAIN.post(() -> confirm(act, () -> {
                        confirmedUntil = System.currentTimeMillis() + 3000;
                        send(method, self, args);
                    }));
                }
            });
            hooked++;
        }
        ModuleLog.line(TAG + "like entry hooks=" + hooked);
    }

    private static Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper w) {
            if (c instanceof Activity a) return a;
            c = w.getBaseContext();
        }
        return UIHookManager.getCurrentActivity();
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
                send(method, self, args);
            }, DELAY_MS);
        });
    }

    /** Ask before liking: the like goes out only on Like; Cancel drops it. */
    private static void confirm(Activity act, Runnable onLike) {
        try {
            ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(act)
                    .title(I18n.t(act, R.string.ig_confirm_like_title))
                    .message(I18n.t(act, R.string.ig_confirm_like_message));
            final boolean[] answered = {false};
            dlg.secondary(act.getString(android.R.string.cancel), () -> answered[0] = true);
            dlg.primary(I18n.t(act, R.string.ig_confirm_like_yes), () -> { answered[0] = true; onLike.run(); });
            dlg.onDismiss(() -> { if (!answered[0]) ModuleLog.line(TAG + "like not confirmed"); });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ confirm: " + t);
        }
    }

    private static void send(Member method, Object self, Object[] args) {
        try {
            // Called through the hook (which lets it pass while replaying): invoking the original
            // directly isn't supported the same way on every framework (LSPatch).
            REPLAYING.set(Boolean.TRUE);
            Method m = (Method) method;
            m.setAccessible(true);
            m.invoke(self, args);
            ModuleLog.line(TAG + "like sent");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ send failed: " + t);
        } finally {
            REPLAYING.remove();
        }
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
