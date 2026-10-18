package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;

/**
 * Search your story's viewers (Instagram Plus–style, our own implementation; no Plus check is
 * touched). Filters the viewers Instagram has already loaded.
 *
 * - Viewer adapter: declaring class of the method using "search_result_rendered"
 *   (X.0U9B on 452, X.0Q5U on 451). Its private (List, boolean×3) void method is the only place
 *   viewer rows are built; we filter that list. Its public no-arg method using
 *   "dismissed_reel_viewers_list_megaphone" rebuilds the list (called after the query changes).
 * - Viewer record: class using "XDTViewerFeedbackDict" holds the viewer's
 *   com.instagram.user.model.User (found by type); username via UserUtils.
 * - UI: an EditText added above the RecyclerView that uses this adapter.
 */
public class StoryViewerSearchHook {

    private static final String TAG = "(IE|ViewerSearch) ";
    private static final Map<Object, String> QUERIES = new WeakHashMap<>();
    private static final Map<Object, Boolean> INJECTED = new WeakHashMap<>();
    private static Method rebuild;
    private static Class<?> recordClass;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> adapter = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("search_result_rendered")))) {
                adapter = cl.loadClass(md.getClassName());
                break;
            }
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("XDTViewerFeedbackDict")))) {
                recordClass = cl.loadClass(md.getClassName());
                break;
            }
            if (adapter == null) { ModuleLog.line(TAG + "❌ viewer adapter not found"); return; }
            Method build = null;
            for (Method m : adapter.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 4 && p[0] == List.class
                        && p[1] == boolean.class && p[2] == boolean.class && p[3] == boolean.class) build = m;
            }
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(adapter.getName()).paramCount(0).returnType("void")
                    .usingStrings("dismissed_reel_viewers_list_megaphone")))) {
                rebuild = md.getMethodInstance(cl);
                break;
            }
            if (build == null) { ModuleLog.line(TAG + "❌ row builder not found"); return; }
            build.setAccessible(true);
            if (rebuild != null) rebuild.setAccessible(true);

            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.storyViewerSearch) return;
                    Object ad = param.thisObject;
                    injectSearchBox(ad);
                    String q = QUERIES.get(ad);
                    if (q == null || q.isEmpty() || !(param.args[0] instanceof List<?> list)) return;
                    List<Object> out = new ArrayList<>();
                    for (Object item : list) {
                        // keep non-viewer rows (headers, footers) untouched
                        if (recordClass != null && !recordClass.isInstance(item)) { out.add(item); continue; }
                        String u = username(item);
                        if (u != null && u.toLowerCase(Locale.ROOT).contains(q)) out.add(item);
                    }
                    param.args[0] = out;
                }
            });
            ModuleLog.line(TAG + "✅ adapter " + adapter.getName() + "." + build.getName()
                    + " rebuild=" + (rebuild != null ? rebuild.getName() : "none"));
            FeatureStatusTracker.setHooked("StoryViewerSearch");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static String username(Object record) {
        try {
            for (Class<?> k = record.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals("com.instagram.user.model.User")) continue;
                    f.setAccessible(true);
                    return UserUtils.callUsernameGetter(f.get(record));
                }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Adds the search box above the RecyclerView showing this adapter (once per adapter). */
    private static void injectSearchBox(Object adapter) {
        if (INJECTED.containsKey(adapter)) return;
        Activity act = UIHookManager.getCurrentActivity();
        if (act == null) return;
        act.runOnUiThread(() -> {
            if (INJECTED.containsKey(adapter)) return;
            View list = findListFor(act.getWindow().getDecorView(), adapter);
            if (list == null || !(list.getParent() instanceof ViewGroup parent)) return;
            INJECTED.put(adapter, Boolean.TRUE);
            float d = act.getResources().getDisplayMetrics().density;
            EditText et = new EditText(act);
            et.setHint(I18n.t(act, R.string.ig_viewer_search_hint));
            et.setSingleLine(true);
            et.setTextColor(Color.WHITE);
            et.setHintTextColor(0x99FFFFFF);
            int ph = (int) (14 * d), pv = (int) (10 * d);
            et.setPadding(ph, pv, ph, pv);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33FFFFFF);
            bg.setCornerRadius(10 * d);
            et.setBackground(bg);
            et.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    QUERIES.put(adapter, s.toString().trim().toLowerCase(Locale.ROOT));
                    try { if (rebuild != null) rebuild.invoke(adapter); } catch (Throwable t) {
                        ModuleLog.line(TAG + "⚠️ rebuild: " + t);
                    }
                }
            });
            ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = (int) (12 * d);
            lp.setMargins(m, m / 2, m, m / 2);
            try {
                parent.addView(et, parent.indexOfChild(list), lp);
                ModuleLog.line(TAG + "search box added in " + parent.getClass().getSimpleName());
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ add box: " + t);
            }
        });
    }

    private static View findListFor(View root, Object adapter) {
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            try {
                Method ga = v.getClass().getMethod("getAdapter");
                if (ga.invoke(v) == adapter) return v;
            } catch (Throwable ignored) {}
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }
}
