package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Saved one tap away on your own profile (#237): a bookmark button in the profile action bar
 * (next to Threads / Options) that opens Instagram's own Saved screen.
 *
 * Opening Saved reuses exactly what instagram://saved does: SavedUrlHandlerActivity (class name
 * kept by R8) builds the Saved fragment with a static (session, String, boolean, boolean) factory
 * and shows it with a static (Fragment, FragmentActivity, session) navigator; both are taken from
 * that method's invokes. The own profile is the one whose action bar has the create (+) button.
 */
public class SavedOnProfileHook {
    private static final String TAG = "(IE|SavedProfile) ";
    private static final String HANDLER = "com.instagram.urlhandlers.saved.SavedUrlHandlerActivity";
    private static final String FRAGMENT = "com.instagram.profile.fragment.UserDetailFragment";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String BTN_TAG = "ie_saved_profile_btn";

    private static Method factory, navigator, navPush, navCommit, navMake;
    private static java.lang.reflect.Constructor<?> navCtor;
    private static boolean logged;
    private static final java.util.Set<View> watched =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public void install(DexKitBridge bridge, ClassLoader cl) {
        if (!FeatureFlags.savedOnProfile) return; // applies after an Instagram restart
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(HANDLER).paramCount(3)))) {
                for (MethodData inv : md.getInvokes()) {
                    if (!inv.isMethod()) continue;
                    Method m;
                    try { m = inv.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    Class<?>[] p = m.getParameterTypes();
                    Class<?> fragment = cl.loadClass("androidx.fragment.app.Fragment");
                    // 452+: the handler builds the navigator itself (make(activity, session) ->
                    // push(Bundle, Fragment) -> commit()) instead of calling one static helper.
                    if (!Modifier.isStatic(m.getModifiers())) {
                        if (p.length == 2 && p[0] == android.os.Bundle.class && p[1] == fragment) navPush = m;
                        else if (p.length == 0 && m.getReturnType() == void.class && navPush != null
                                && m.getDeclaringClass() == navPush.getDeclaringClass()) navCommit = m;
                        continue;
                    }
                    if (p.length == 2 && Activity.class.isAssignableFrom(p[0]) && m.getReturnType() != void.class
                            && !m.getReturnType().isPrimitive()) { navMake = m; continue; }
                    if (p.length == 4 && p[1] == String.class && p[2] == boolean.class && p[3] == boolean.class
                            && fragment.isAssignableFrom(m.getReturnType())) factory = m;
                    else if (p.length == 3 && p[0] == fragment && Activity.class.isAssignableFrom(p[1])
                            && m.getReturnType() == void.class) {
                        navigator = m;
                        // It runs IG's FragmentNavigator with the back stack turned off (link-handler
                        // use): keep its pieces to push Saved with a back stack (Back -> profile).
                        for (MethodData n : inv.getInvokes()) {
                            try {
                                if (n.isConstructor()) navCtor = n.getConstructorInstance(cl);
                                else if (n.isMethod()) {
                                    Method nm = n.getMethodInstance(cl);
                                    Class<?>[] q = nm.getParameterTypes();
                                    if (q.length == 2 && q[0] == android.os.Bundle.class && q[1] == fragment) navPush = nm;
                                    else if (q.length == 0 && nm.getReturnType() == void.class) navCommit = nm;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }
            boolean built = navMake != null && navPush != null && navCommit != null
                    && navMake.getReturnType() == navPush.getDeclaringClass();
            if (!built) navMake = null;
            if (factory == null || (navigator == null && navMake == null)) {
                ModuleLog.line(TAG + "❌ factory=" + factory + " navigator=" + navigator + " navMake=" + navMake);
                return;
            }
            factory.setAccessible(true);
            if (navigator != null) navigator.setAccessible(true);
            if (navMake != null) { navMake.setAccessible(true); navPush.setAccessible(true); navCommit.setAccessible(true); }
            XposedHelpers.findAndHookMethod(cl.loadClass("androidx.fragment.app.Fragment"), "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!FeatureFlags.savedOnProfile || !isProfileFragment(param.thisObject)) return;
                    final Object frag = param.thisObject;
                    View root;
                    try { root = (View) frag.getClass().getMethod("getView").invoke(frag); } catch (Throwable t) { return; }
                    if (root == null) return;
                    root.postDelayed(() -> inject(frag, root), 500);
                    // IG rebuilds the action bar buttons after resume (dropping ours): re-add when missing
                    if (watched.add(root))
                        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                            if (FeatureFlags.savedOnProfile && root.findViewWithTag(BTN_TAG) == null) inject(frag, root);
                        });
                }
            });
            FeatureStatusTracker.setHooked("SavedOnProfile");
            ModuleLog.line(TAG + "✅ " + factory.getDeclaringClass().getName() + "." + factory.getName()
                    + " / " + (navigator != null ? navigator : navMake));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static boolean isProfileFragment(Object f) {
        for (Class<?> c = f.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            if (c.getName().equals(FRAGMENT)) return true;
        return false;
    }

    @SuppressLint("DiscouragedApi")
    private static void inject(Object frag, View root) {
        try {
            String pkg = root.getContext().getPackageName();
            android.content.res.Resources r = root.getResources();
            int createId = r.getIdentifier("profile_header_create_button", "id", pkg);
            int rightId = r.getIdentifier("right_action_bar_buttons", "id", pkg);
            if (createId == 0 || rightId == 0 || root.findViewById(createId) == null) return; // not your profile
            if (!(root.findViewById(rightId) instanceof ViewGroup bar) || bar.findViewWithTag(BTN_TAG) != null) return;
            ImageView btn = new ImageView(root.getContext());
            btn.setTag(BTN_TAG);
            int icon = r.getIdentifier("instagram_save_outline_24", "drawable", pkg);
            if (icon != 0) {
                btn.setImageResource(icon);
                // tint like the neighbouring action bar icons
                View sib = bar.getChildCount() > 0 ? bar.getChildAt(0) : null;
                if (sib instanceof ImageView iv && iv.getImageTintList() != null) btn.setImageTintList(iv.getImageTintList());
                else if (sib instanceof ImageView iv && iv.getColorFilter() != null) btn.setColorFilter(iv.getColorFilter());
                else btn.setColorFilter(Color.WHITE);
            }
            btn.setScaleType(ImageView.ScaleType.CENTER);
            btn.setContentDescription(I18n.t(root.getContext(), R.string.ig_saved_on_profile_button));
            int w = bar.getChildCount() > 0 && bar.getChildAt(0).getWidth() > 0 ? bar.getChildAt(0).getWidth()
                    : (int) (48 * r.getDisplayMetrics().density);
            btn.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
            btn.setOnClickListener(v -> openSaved(frag, v));
            bar.addView(btn, 0);
            if (!logged) { logged = true; ModuleLog.line(TAG + "button added"); }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ inject: " + t);
        }
    }

    /** The UserSession held by the fragment (fields are typed with an obfuscated interface, so
     *  match the value's class), looking one object deeper if needed. */
    private static Object findSession(Object o, int depth) {
        java.util.List<Object> next = new java.util.ArrayList<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v.getClass().getName().equals(SESSION)) return v;
                    String n = v.getClass().getName();
                    if (n.startsWith("X.") || n.startsWith("com.instagram.")) next.add(v);
                } catch (Throwable ignored) {}
            }
        if (depth > 1) for (Object n : next) { Object s = findSession(n, depth - 1); if (s != null) return s; }
        return null;
    }

    private static void openSaved(Object frag, View v) {
        try {
            Object session = findSession(frag, 2);
            Activity act = null;
            for (android.content.Context k = v.getContext(); k != null && act == null;
                 k = k instanceof android.content.ContextWrapper w ? w.getBaseContext() : null)
                if (k instanceof Activity a) act = a;
            if (session == null || act == null) {
                ModuleLog.line(TAG + "⚠️ session=" + session + " activity=" + act);
                return;
            }
            Object saved = factory.invoke(null, session, null, false, false);
            if (navMake != null) {
                Object nav = navMake.invoke(null, act, session);
                navPush.invoke(nav, null, saved);
                navCommit.invoke(nav);
            } else if (navCtor != null && navPush != null && navCommit != null
                    && navCtor.getParameterTypes().length == 2 && navCtor.getDeclaringClass() == navPush.getDeclaringClass()) {
                Object nav = navCtor.newInstance(act, session); // back stack on by default
                navPush.invoke(nav, null, saved);
                navCommit.invoke(nav);
            } else {
                navigator.invoke(null, saved, act, session);
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ open: " + t);
        }
    }
}
