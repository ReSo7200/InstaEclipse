package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Adapter;
import android.widget.FrameLayout;
import android.widget.HeaderViewListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;

/**
 * Export a followers / following list to CSV (#224).
 *
 * FollowListFragment = class whose method uses "Invalid entry type for FollowListFragment"
 * (X.0D9r on 452, X.0D96 on 451). After its onViewCreated a small "Export CSV" pill is added;
 * on tap, every com.instagram.user.model.User held by the list's adapter (the ListView inside
 * the fragment) is written as username,full_name,id. No scrolling: on tap, IG's own list pager
 * (X.0DRF on 452: fragment field with isLoading(), next max_id in its String field) is asked for the
 * next page until there is no next_max_id — IG sends the API requests and parses each page into the
 * adapter — then every user in the adapter's row lists is written (so the profile owner and the
 * logged-in user, held in plain fields, are left out). A progress dialog keeps the user on the list
 * (IG's pager only runs while it is open); "Stop & save" ends early.
 */
public class FollowListExportHook {

    private static final String TAG = "(IE|FollowExport) ";
    private static final String USER = "com.instagram.user.model.User";
    private static Method fullNameGetter;
    private static volatile boolean loading;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> frag = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Invalid entry type for FollowListFragment")))) {
                frag = cl.loadClass(md.getClassName());
                break;
            }
            if (frag == null) { ModuleLog.line(TAG + "❌ FollowListFragment not found"); return; }
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(USER).paramCount(0).returnType("java.lang.String").usingNumbers(-1677176261)))) {
                fullNameGetter = md.getMethodInstance(cl); // "full_name".hashCode()
                break;
            }
            Method ovc = null;
            for (Class<?> c = frag; c != null && ovc == null; c = c.getSuperclass()) {
                try { ovc = c.getDeclaredMethod("onViewCreated", View.class, android.os.Bundle.class); } catch (NoSuchMethodException ignored) {}
            }
            if (ovc == null) { ModuleLog.line(TAG + "❌ onViewCreated not found"); return; }
            fragClass = frag;
            XposedBridge.hookMethod(ovc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.followListExport || !fragClass.isInstance(param.thisObject)) return;
                    if (param.args[0] instanceof View root) {
                        live.put(param.thisObject, root);
                        root.post(() -> addPill(root));
                    }
                }
            });
            try { findPager(bridge, cl); } catch (Throwable t) { ModuleLog.line(TAG + "⚠️ pager: " + t); }
            ModuleLog.line(TAG + "✅ " + frag.getName() + " fullName=" + (fullNameGetter != null));
            FeatureStatusTracker.setHooked("FollowListExport");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /** Live list fragments → their root view (followers / following / mutual tabs each have one). */
    private static final Map<Object, View> live = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** The tab whose list is on screen right now. The tab pager keeps neighbouring tabs attached
     *  (isShown() is true for them too), so take the one with the largest on-screen area. */
    private static Object[] visibleList() {
        Object[] best = null;
        long bestArea = 0;
        android.graphics.Rect rc = new android.graphics.Rect();
        synchronized (live) {
            for (Map.Entry<Object, View> e : live.entrySet()) {
                View r = e.getValue();
                if (r == null || !r.isShown() || findList(r) == null || !r.getGlobalVisibleRect(rc)) continue;
                long area = (long) rc.width() * rc.height();
                if (area > bestArea) { bestArea = area; best = new Object[]{e.getKey(), r}; }
            }
        }
        return best;
    }

    private static void addPill(View root) {
        View host = root;
        while (host != null && !(host instanceof FrameLayout)) host = host.getParent() instanceof View p ? p : null;
        if (!(host instanceof FrameLayout g) || g.findViewWithTag("ie_follow_export") != null) return;
        float d = root.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(root.getContext());
        pill.setTag("ie_follow_export");
        pill.setText("⬇ " + I18n.t(root.getContext(), R.string.ig_follow_export));
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        int ph = (int) (14 * d), pv = (int) (8 * d);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE0A84FF);
        bg.setCornerRadius(18 * d);
        pill.setBackground(bg);
        pill.setElevation(20 * d);
        pill.setOnClickListener(v -> {
            if (loading) { loading = false; return; } // second tap: stop loading, export now
            Object[] vis = visibleList();
            if (vis == null) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(v.getContext(), I18n.t(v.getContext(), R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
                return;
            }
            loadAllThenExport((View) vis[1], pill, vis[0]);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = (int) (24 * d);
        lp.rightMargin = (int) (16 * d);
        g.addView(pill, lp);
        pill.bringToFront();
    }

    // ---- Paging: drive IG's own pager (no scrolling) and read every parsed page response ----

    private static Class<?> fragClass;
    private static Field pagerField, nextMaxIdField;
    private static Method pagerIsLoading, pagerLoadMore;

    /** IG's own list pager: fragment field whose class has isLoading() and keeps the next max_id in a
     *  String field; its "load more if idle" method (no-arg void, checks the lifecycle first) sends the
     *  next /friendships/<id>/following|followers/ page request. */
    private static void findPager(DexKitBridge bridge, ClassLoader cl) throws Throwable {
        // pager = fragment field whose class has isLoading() and keeps the next max_id in a String field
        for (Class<?> c = fragClass; c != null && pagerField == null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                try {
                    Method il = f.getType().getDeclaredMethod("isLoading");
                    if (il.getReturnType() != boolean.class) continue;
                    for (Field sf : f.getType().getDeclaredFields())
                        if (sf.getType() == String.class && !Modifier.isStatic(sf.getModifiers())) { sf.setAccessible(true); nextMaxIdField = sf; }
                    if (nextMaxIdField == null) continue;
                    f.setAccessible(true);
                    pagerField = f;
                    pagerIsLoading = il;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        if (pagerField != null) {
            // "load more if idle": the no-arg void method that checks the lifecycle first
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(pagerField.getType().getName()).paramCount(0).returnType("void")
                    .addInvoke(MethodMatcher.create().name("getLifecycle"))))) {
                pagerLoadMore = md.getMethodInstance(cl);
                break;
            }
        }
        ModuleLog.line(TAG + "pager=" + (pagerField != null ? pagerField.getType().getName() : null)
                + " loadMore=" + (pagerLoadMore != null ? pagerLoadMore.getName() : null));
    }

    /** Ask IG's pager for the next page until next_max_id is gone, then export. The pager only works
     *  while the list screen is resumed, so a non-cancelable progress dialog keeps the user here; its
     *  button (or a second tap on the pill) stops early and saves what has loaded. */
    private static void loadAllThenExport(View root, TextView pill, Object frag) {
        Object pager = null;
        try { if (pagerField != null) pager = pagerField.get(frag); } catch (Throwable ignored) {}
        if (pager == null || pagerLoadMore == null) { export(root, frag); return; }
        loading = true;
        final Object pg = pager;
        Handler h = new Handler(Looper.getMainLooper());
        ps.reso.instaeclipse.utils.dialog.IeDialog dlg = progressDialog(root.getContext());
        new Runnable() {
            int idle = 0;
            boolean first = true;
            @Override public void run() {
                boolean more = false;
                try {
                    // isLoading() is the footer-spinner flag (true while more pages exist), so don't gate
                    // on it: the load-more method itself skips when a request is in flight or failed.
                    more = nextMaxIdField.get(pg) != null;
                    if (more) pagerLoadMore.invoke(pg);
                    if (first) { first = false; ModuleLog.line(TAG + "pager start: more=" + more); }
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "⚠️ pager: " + t);
                    loading = false;
                }
                idle = more ? 0 : idle + 1;
                AbsListView lv = findList(root);
                int n = lv != null && lv.getAdapter() != null ? lv.getAdapter().getCount() : 0;
                if (dlg != null) dlg.message(I18n.t(root.getContext(), R.string.ig_follow_export_loading, n));
                if (!loading || idle >= 2 || !root.isAttachedToWindow()) {
                    loading = false;
                    try { if (dlg != null) dlg.dismiss(); } catch (Throwable ignored) {}
                    export(root, frag);
                    return;
                }
                h.postDelayed(this, 500);
            }
        }.run();
    }

    private static void writeCsv(android.content.Context ctx, Map<String, String[]> users, String file) {
        try {
            StringBuilder csv = new StringBuilder("username,full_name,id\n");
            for (String[] u : users.values()) csv.append(q(u[0])).append(',').append(q(u[1])).append(',').append(q(u[2])).append('\n');
            csv.append(ps.reso.instaeclipse.utils.core.ExportBrand.csvFooter());
            try (OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openExport(ctx, file, "text/csv")) {
                out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM for Excel
                out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            }
            ModuleLog.line(TAG + "exported " + users.size() + " users to " + file);
            new Handler(Looper.getMainLooper()).post(() ->
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_follow_export_done, users.size(), file), Toast.LENGTH_LONG).show());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ export: " + t);
        }
    }

    private static ps.reso.instaeclipse.utils.dialog.IeDialog progressDialog(android.content.Context c) {
        try {
            // the Activity that hosts the list (UIHookManager's current one can be a different, hidden one)
            android.content.Context ctx = c;
            while (!(ctx instanceof Activity) && ctx instanceof android.content.ContextWrapper w && w.getBaseContext() != null) ctx = w.getBaseContext();
            if (!(ctx instanceof Activity)) {
                Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                ctx = act != null ? act : c;
            }
            ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ig_follow_export))
                    .message(I18n.t(ctx, R.string.ig_follow_export_loading, 0))
                    .cancelable(false); // leaving the list would stop IG's pager
            android.widget.ProgressBar bar = new android.widget.ProgressBar(d.ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(d.accent));
            d.view(bar);
            d.primary(I18n.t(ctx, R.string.ig_follow_export_stop), () -> loading = false);
            d.onClose(() -> loading = false);
            d.show();
            ModuleLog.line(TAG + "progress dialog on " + ctx.getClass().getName());
            return d;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ dialog: " + t);
            return null;
        }
    }

    /** Every user of the pages IG has received, as held by the list's adapter. */
    private static void export(View root, Object frag) {
        AbsListView list = findList(root);
        Adapter ad = list != null ? list.getAdapter() : null;
        if (ad instanceof HeaderViewListAdapter h) ad = h.getWrappedAdapter();
        Map<String, String[]> users = ad != null ? collect(ad, true) : new LinkedHashMap<>();
        if (users.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(root.getContext(), I18n.t(root.getContext(), R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        android.content.Context ctx = act != null ? act : root.getContext();
        String file = "follow_list_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        new Thread(() -> writeCsv(ctx, users, file)).start();
    }

    private static String q(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static AbsListView findList(View root) {
        ArrayDeque<View> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v instanceof AbsListView l && l.isShown()) return l;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }

    /** id → {username, full name, id} for every User reachable from the adapter, in order. */
    private static Map<String, String[]> collect(Object adapter, boolean rowsOnly) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{adapter, 0, false}); // {object, depth, reached through a List/Map (= a row)}
        while (!q.isEmpty() && seen.size() < 20000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            boolean row = (boolean) e[2];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { for (Object it : l) q.add(new Object[]{it, d + 1, true}); continue; }
            if (o instanceof Map<?, ?> m) { for (Object it : m.values()) q.add(new Object[]{it, d + 1, true}); continue; }
            if (o.getClass().getName().equals(USER)) {
                if (rowsOnly && !row) continue; // the profile owner / logged-in user held in plain fields, not a list row
                String u = UserUtils.callUsernameGetter(o);
                String id = userId(o);
                String full = null;
                try { if (fullNameGetter != null) full = (String) fullNameGetter.invoke(o); } catch (Throwable ignored) {}
                if (u != null) out.putIfAbsent(id != null ? id : u, new String[]{u, full, id});
                continue;
            }
            if (d >= 4) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class
                            || android.content.Context.class.isAssignableFrom(f.getType()) || View.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1, row}); } catch (Throwable ignored) {}
                }
        }
        return out;
    }

    private static String userId(Object user) {
        for (Field f : user.getClass().getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
            try { f.setAccessible(true); Object v = f.get(user); if (v instanceof String s && s.matches("\\d+")) return s; } catch (Throwable ignored) {}
        }
        return null;
    }
}
