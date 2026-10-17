package ps.reso.instaeclipse.mods.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * "Follows you" label on profiles (#239), instead of only the follower toast.
 *
 * After UserDetailFragment's onUserUpdated (static method using "UserDetailFragment.onUserUpdated":
 * A0d on 452, A0h on 451) the viewed User is read from the fragment's UserDetailViewModel and its
 * followed_by getter (User ()Boolean using "followed_by"; null = unknown/own profile → no label).
 * A small coloured "· Follows you" / "· Doesn't follow you" span is appended to the profile name
 * (profile_header_full_name_above_vanity / profile_header_full_name) and re-applied when IG
 * rebinds the text.
 */
public class ProfileFollowsYouHook {

    private static final String TAG = "(InstaEclipse | FollowsYou): ";
    private static final String FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment";
    private static final String VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel";
    private static final String USER = "com.instagram.user.model.User";
    private static final String MARK = " · "; // separator our span starts with
    private static Method followedByGetter;
    private static volatile java.lang.ref.WeakReference<Object> lastFragment;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method updated = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("FollowsYou_onUserUpdated", cl) : null;
            followedByGetter = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("FollowsYou_followedBy", cl) : null;
            if (updated == null || followedByGetter == null) {
                List<MethodData> u = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(FRAGMENT).usingStrings("UserDetailFragment.onUserUpdated")));
                List<MethodData> f = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(USER).paramCount(0).returnType("java.lang.Boolean")
                        .usingEqStrings(java.util.List.of("followed_by"))));
                if (u.isEmpty() || f.size() != 1) {
                    ModuleLog.line(TAG + "❌ onUserUpdated=" + u.size() + " followed_by=" + f.size());
                    return;
                }
                updated = u.get(0).getMethodInstance(cl);
                followedByGetter = f.get(0).getMethodInstance(cl);
                DexKitCache.saveMethod("FollowsYou_onUserUpdated", updated);
                DexKitCache.saveMethod("FollowsYou_followedBy", followedByGetter);
            }
            followedByGetter.setAccessible(true);
            XposedBridge.hookMethod(updated, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.profileFollowsYouLabel) return;
                    Object frag = param.thisObject;
                    if (frag == null) for (Object a : param.args) if (a != null && a.getClass().getName().equals(FRAGMENT)) frag = a;
                    if (frag == null) return;
                    lastFragment = new java.lang.ref.WeakReference<>(frag);
                    apply(frag, null);
                }
            });
            // Also on every resume of a profile screen (onUserUpdated doesn't fire on all profiles).
            Class<?> base = cl.loadClass("androidx.fragment.app.Fragment");
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(base, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.profileFollowsYouLabel) return;
                    Object frag = param.thisObject;
                    for (Class<?> c = frag.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        if (c.getName().equals(FRAGMENT)) {
                            lastFragment = new java.lang.ref.WeakReference<>(frag);
                            View root;
                            try { root = (View) frag.getClass().getMethod("getView").invoke(frag); } catch (Throwable t) { return; }
                            if (root != null) root.postDelayed(() -> apply(frag, null), 700);
                            return;
                        }
                    }
                }
            });
            ModuleLog.line(TAG + "✅ Hooked " + updated.getName() + " / followed_by " + followedByGetter.getName());
            FeatureStatusTracker.setHooked("ProfileFollowsYou");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /**
     * Result of IG's /friendships/show/ call (parsed by FollowStatusHook): used when the User
     * model doesn't carry followed_by yet (common for accounts you don't follow).
     */
    /** userId -> followed_by from /friendships/show/, kept so a result that arrives before the
     *  profile screen registers is still applied. */
    private static final java.util.Map<String, Boolean> RESULTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void onFollowResult(String userId, boolean followedBy) {
        if (!FeatureFlags.profileFollowsYouLabel || userId == null) return;
        RESULTS.put(userId, followedBy);
        java.lang.ref.WeakReference<Object> ref = lastFragment;
        Object frag = ref != null ? ref.get() : null;
        if (frag == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Object vm = fieldOfType(frag, VIEW_MODEL);
                Object user = vm != null ? fieldOfType(vm, USER) : null;
                if (user != null && sameUser(user, userId)) apply(frag, followedBy);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ follow result: " + t);
            }
        });
    }

    private static void apply(Object frag, Boolean known) {
        try {
            View root = (View) frag.getClass().getMethod("getView").invoke(frag);
            if (root == null) return;
            Boolean followsMe = known != null ? known : followedBy(frag);
            if (followsMe == null) followsMe = cachedResult(frag);
            if (followsMe == null) {
                // result may still be on its way: look again shortly
                final Object f = frag;
                root.postDelayed(() -> { Boolean c = cachedResult(f); if (c != null) apply(f, c); }, 1500);
                return;
            }
            final boolean fm = followsMe;
            Context ctx = root.getContext();
            for (String idName : new String[]{"profile_header_full_name_above_vanity", "profile_header_full_name"}) {
                @SuppressWarnings("DiscouragedApi")
                int id = ctx.getResources().getIdentifier(idName, "id", ctx.getPackageName());
                View v = id != 0 ? root.findViewById(id) : null;
                if (v instanceof TextView tv && tv.getVisibility() == View.VISIBLE) {
                    if (tv.getText() == null || tv.getText().length() == 0) continue; // no display name
                    label(tv, fm);
                    if (tv.getTag(R.id.ie_follows_you_tag) == null) {
                        tv.setTag(R.id.ie_follows_you_tag, Boolean.TRUE);
                        tv.addOnLayoutChangeListener((w, l, t, r, b, ol, ot, or, ob) ->
                                w.post(() -> { if (FeatureFlags.profileFollowsYouLabel) label((TextView) w, fm); }));
                    }
                    return;
                }
            }
            // No visible display name: show the label on its own in the (hidden/empty) name view.
            for (String idName : new String[]{"profile_header_full_name_above_vanity", "profile_header_full_name"}) {
                @SuppressWarnings("DiscouragedApi")
                int id = ctx.getResources().getIdentifier(idName, "id", ctx.getPackageName());
                View v = id != 0 ? root.findViewById(id) : null;
                if (v instanceof TextView tv) {
                    standalone(tv, fm);
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ apply: " + t);
        }
    }

    private static void standalone(TextView tv, boolean followsMe) {
        String text = I18n.t(tv.getContext(), followsMe ? R.string.ig_profile_follows_you : R.string.ig_profile_not_following_you);
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.setSpan(new ForegroundColorSpan(followsMe ? 0xFF34C759 : 0xFFFF453A), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.8f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
        tv.setVisibility(View.VISIBLE);
    }

    private static Boolean cachedResult(Object frag) {
        try {
            Object vm = fieldOfType(frag, VIEW_MODEL);
            Object user = vm != null ? fieldOfType(vm, USER) : null;
            if (user == null) return null;
            for (java.util.Map.Entry<String, Boolean> e : RESULTS.entrySet())
                if (sameUserStrict(user, e.getKey())) return e.getValue();
        } catch (Throwable ignored) {}
        return null;
    }

    /** True only when one of the User's String fields equals userId. */
    private static boolean sameUserStrict(Object user, String userId) {
        for (Class<?> c = user.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); if (userId.equals(f.get(user))) return true; } catch (Throwable ignored) {}
            }
        return false;
    }

    private static void label(TextView tv, boolean followsMe) {
        CharSequence cur = tv.getText();
        if (cur == null || cur.length() == 0) return;
        String yes = I18n.t(tv.getContext(), R.string.ig_profile_follows_you);
        String no = I18n.t(tv.getContext(), R.string.ig_profile_not_following_you);
        String s = cur.toString();
        if (s.endsWith(MARK + yes) || s.endsWith(MARK + no)) return;
        String text = MARK + (followsMe ? yes : no);
        SpannableStringBuilder sb = new SpannableStringBuilder(cur);
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(followsMe ? 0xFF34C759 : 0xFFFF453A), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.8f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(sb);
    }

    /**
     * True when the viewed User's id is userId. The pk is a String field on the model on most
     * builds; when it isn't readable (Pando-backed), accept: the friendship request was made
     * by the profile currently open.
     */
    private static boolean sameUser(Object user, String userId) {
        boolean sawString = false;
        for (Class<?> c = user.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(user);
                    if (v == null) continue;
                    sawString = true;
                    if (userId.equals(v)) return true;
                } catch (Throwable ignored) {}
            }
        }
        return !sawString;
    }

    private static Boolean followedBy(Object frag) throws Exception {
        Object vm = fieldOfType(frag, VIEW_MODEL);
        Object user = vm != null ? fieldOfType(vm, USER) : null;
        if (user == null) return null;
        Object r = followedByGetter.invoke(user);
        return r instanceof Boolean b ? b : null;
    }

    private static Object fieldOfType(Object o, String type) throws IllegalAccessException {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals(type)) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) return v;
            }
        }
        return null;
    }
}
