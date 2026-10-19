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

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
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
 *
 * Followers history: on your own Followers tab a "Followers history" pill loads the whole list the
 * same way and hands it to FollowersHistory (compared with the last check: who unfollowed / followed).
 * Own Followers tab = the fragment's FollowListFragment.FollowListData argument holds the FOLLOWERS
 * entry of IG's list-type enum (constant names are kept) and a String equal to your user id, which is
 * the part after ':' of the IgSessionManager.SESSION_TOKEN_KEY argument. Only a list that loaded to
 * the end (no next_max_id, not stopped) is compared.
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
                    if ((!FeatureFlags.followListExport && !FeatureFlags.followersHistory)
                            || !fragClass.isInstance(param.thisObject)) return;
                    if (param.args[0] instanceof View root) {
                        live.put(param.thisObject, root);
                        Object frag = param.thisObject;
                        root.post(() -> {
                            if (FeatureFlags.followListExport) addPill(root);
                            if (FeatureFlags.followersHistory && ownFollowersOf(frag) != null) addHistoryPill(root);
                        });
                    }
                }
            });
            try { findPager(bridge, cl); } catch (Throwable t) { ModuleLog.line(TAG + "⚠️ pager: " + t); }
            ModuleLog.line(TAG + "✅ " + frag.getName() + " fullName=" + (fullNameGetter != null));
            FeatureStatusTracker.setHooked("FollowListExport");
            FeatureStatusTracker.setHooked("FollowersHistory");
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
            loadAll((View) vis[1], vis[0], R.string.ig_follow_export, complete -> export((View) vis[1], vis[0]));
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

    /** Ask IG's pager for the next page until next_max_id is gone, then run {@code done} (true when
     *  the list loaded to the end). The pager only works while the list screen is resumed, so a
     *  non-cancelable progress dialog keeps the user here; its button (or a second tap on the pill)
     *  stops early. */
    private static void loadAll(View root, Object frag, int titleRes, java.util.function.Consumer<Boolean> done) {
        Object pager = null;
        try { if (pagerField != null) pager = pagerField.get(frag); } catch (Throwable ignored) {}
        if (pager == null || pagerLoadMore == null) { done.accept(false); return; }
        loading = true;
        final Object pg = pager;
        Handler h = new Handler(Looper.getMainLooper());
        ps.reso.instaeclipse.utils.dialog.IeDialog dlg = progressDialog(root.getContext(), titleRes);
        new Runnable() {
            int idle = 0;
            boolean first = true;
            boolean stopped = false;
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
                    stopped = true;
                }
                idle = more ? 0 : idle + 1;
                AbsListView lv = findList(root);
                int n = lv != null && lv.getAdapter() != null ? lv.getAdapter().getCount() : 0;
                if (dlg != null) dlg.message(I18n.t(root.getContext(), R.string.ig_follow_export_loading, n));
                if (!loading || idle >= 2 || !root.isAttachedToWindow()) {
                    // complete = the pager ran out of pages on its own (not stopped, still on screen)
                    boolean complete = loading && idle >= 2 && !stopped && root.isAttachedToWindow();
                    loading = false;
                    try { if (dlg != null) dlg.dismiss(); } catch (Throwable ignored) {}
                    done.accept(complete);
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

    private static ps.reso.instaeclipse.utils.dialog.IeDialog progressDialog(android.content.Context c, int titleRes) {
        try {
            // the Activity that hosts the list (UIHookManager's current one can be a different, hidden one)
            android.content.Context ctx = c;
            while (!(ctx instanceof Activity) && ctx instanceof android.content.ContextWrapper w && w.getBaseContext() != null) ctx = w.getBaseContext();
            if (!(ctx instanceof Activity)) {
                Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                ctx = act != null ? act : c;
            }
            ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(ctx)
                    .title(I18n.t(ctx, titleRes))
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
    private static Map<String, String[]> listUsers(View root) {
        AbsListView list = findList(root);
        Adapter ad = list != null ? list.getAdapter() : null;
        if (ad instanceof HeaderViewListAdapter h) ad = h.getWrappedAdapter();
        return ad != null ? collect(ad, true) : new LinkedHashMap<>();
    }

    private static void export(View root, Object frag) {
        Map<String, String[]> users = listUsers(root);
        if (users.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(root.getContext(), I18n.t(root.getContext(), R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        android.content.Context ctx = act != null ? act : root.getContext();
        String file = "follow_list_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        new Thread(() -> writeCsv(ctx, users, file)).start();
    }

    // ---- Followers history ----

    /** Your user id when {@code frag} is your own Followers tab, else null. */
    private static String ownFollowersOf(Object frag) {
        try {
            android.os.Bundle b = (android.os.Bundle) frag.getClass().getMethod("getArguments").invoke(frag);
            if (b == null) return null;
            Object token = b.get("IgSessionManager.SESSION_TOKEN_KEY");
            Object data = b.get("FollowListFragment.FollowListData");
            if (!(token instanceof String t) || data == null) return null;
            String self = t.substring(t.lastIndexOf(':') + 1);
            boolean followers = false, own = false;
            for (Field f : data.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(data);
                if (v instanceof Enum<?> e && e.name().equals("FOLLOWERS")) followers = true;
                if (self.equals(v)) own = true;
            }
            return followers && own && self.matches("\\d+") ? self : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addHistoryPill(View root) {
        View host = root;
        while (host != null && !(host instanceof FrameLayout)) host = host.getParent() instanceof View p ? p : null;
        if (!(host instanceof FrameLayout g) || g.findViewWithTag("ie_followers_history") != null) return;
        float d = root.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(root.getContext());
        pill.setTag("ie_followers_history");
        pill.setText("👥 " + I18n.t(root.getContext(), R.string.ig_followers_history));
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        int ph = (int) (14 * d), pv = (int) (8 * d);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE5E5CE6);
        bg.setCornerRadius(18 * d);
        pill.setBackground(bg);
        pill.setElevation(20 * d);
        pill.setOnClickListener(v -> {
            if (loading) { loading = false; return; }
            Object[] vis = visibleList();
            String self = vis != null ? ownFollowersOf(vis[0]) : null;
            if (self == null) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(v.getContext(), I18n.t(v.getContext(), R.string.ig_followers_history_wrong_tab), Toast.LENGTH_SHORT).show();
                return;
            }
            View listRoot = (View) vis[1];
            loadAll(listRoot, vis[0], R.string.ig_followers_history, complete -> checkHistory(listRoot, self, complete));
        });
        // above the Export CSV pill when both are shown
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = (int) ((FeatureFlags.followListExport ? 72 : 24) * d);
        lp.rightMargin = (int) (16 * d);
        g.addView(pill, lp);
        pill.bringToFront();
    }

    private static void checkHistory(View root, String self, boolean complete) {
        Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        android.content.Context ctx = act != null ? act : root.getContext();
        Map<String, String[]> list = listUsers(root);
        Map<String, String[]> users = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : list.entrySet()) {
            String id = e.getValue()[2];
            if (id != null) users.put(id, new String[]{e.getValue()[0], e.getValue()[1]});
        }
        new Thread(() -> {
            ps.reso.instaeclipse.utils.users.FollowersHistory.State state =
                    ps.reso.instaeclipse.utils.users.FollowersHistory.load(ctx, self);
            ps.reso.instaeclipse.utils.users.FollowersHistory.Result result = null;
            String error = null;
            if (complete && !users.isEmpty()) {
                result = ps.reso.instaeclipse.utils.users.FollowersHistory.apply(state, users, System.currentTimeMillis());
                try {
                    ps.reso.instaeclipse.utils.users.FollowersHistory.save(ctx, self, state);
                } catch (Throwable t) {
                    error = t.toString();
                    ModuleLog.line(TAG + "❌ history save: " + t);
                }
                ModuleLog.line(TAG + "history: " + users.size() + " followers, " + result.added.size() + " new events"
                        + (result.first ? " (first check)" : ""));
            } else {
                ModuleLog.line(TAG + "history: list incomplete (" + users.size() + "), not compared");
            }
            final ps.reso.instaeclipse.utils.users.FollowersHistory.Result r = result;
            final String err = error;
            new Handler(Looper.getMainLooper()).post(() -> showHistory(ctx, self, state, r, complete && !users.isEmpty(), err));
        }).start();
    }

    private static void showHistory(android.content.Context ctx, String self,
                                    ps.reso.instaeclipse.utils.users.FollowersHistory.State state,
                                    ps.reso.instaeclipse.utils.users.FollowersHistory.Result r,
                                    boolean compared, String error) {
        try {
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
            ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ig_followers_history));
            String head;
            if (!compared) head = I18n.t(ctx, R.string.ig_followers_history_partial);
            else if (r.first) head = I18n.t(ctx, R.string.ig_followers_history_first, r.count);
            else {
                int lost = 0, gained = 0;
                for (ps.reso.instaeclipse.utils.users.FollowersHistory.Event e : r.added)
                    if (ps.reso.instaeclipse.utils.users.FollowersHistory.LOST.equals(e.type)) lost++; else gained++;
                head = I18n.t(ctx, R.string.ig_followers_history_changes, df.format(new Date(r.previousCheck)), lost, gained);
            }
            if (state.lastCheck > 0)
                head += "\n" + I18n.t(ctx, R.string.ig_followers_history_summary, state.users.size(), df.format(new Date(state.lastCheck)));
            if (error != null) head += "\n⚠️ " + error;
            dlg.message(head);

            if (state.events.isEmpty()) {
                TextView empty = new TextView(dlg.ctx);
                empty.setText(I18n.t(ctx, R.string.ig_followers_history_empty));
                empty.setTextColor(dlg.sub);
                empty.setTextSize(14);
                dlg.view(empty);
            } else {
                android.widget.LinearLayout rows = new android.widget.LinearLayout(dlg.ctx);
                rows.setOrientation(android.widget.LinearLayout.VERTICAL);
                int shown = 0;
                for (ps.reso.instaeclipse.utils.users.FollowersHistory.Event e : state.events) {
                    if (shown++ >= 300) break;
                    boolean lost = ps.reso.instaeclipse.utils.users.FollowersHistory.LOST.equals(e.type);
                    android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                    sb.append(lost ? "− " : "+ ");
                    sb.append("@").append(e.username != null ? e.username : e.id);
                    sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
                    if (e.fullName != null && !e.fullName.isEmpty()) sb.append("  ").append(e.fullName);
                    int start = sb.length();
                    sb.append("\n").append(I18n.t(ctx, lost ? R.string.ig_followers_history_unfollowed : R.string.ig_followers_history_followed))
                            .append(" · ").append(df.format(new Date(e.ts)));
                    sb.setSpan(new android.text.style.ForegroundColorSpan(lost ? 0xFFFF453A : 0xFF34C759), start, sb.length(), 0);
                    sb.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, sb.length(), 0);
                    TextView row = new TextView(dlg.ctx);
                    row.setText(sb);
                    row.setTextColor(dlg.text);
                    row.setTextSize(14);
                    row.setPadding(0, dlg.dp(6), 0, dlg.dp(6));
                    row.setTextIsSelectable(true);
                    rows.addView(row);
                }
                dlg.view(rows);
                TextView note = new TextView(dlg.ctx);
                note.setText(I18n.t(ctx, R.string.ig_followers_history_note));
                note.setTextColor(dlg.sub);
                note.setTextSize(12);
                dlg.view(note);
            }
            if (state.lastCheck > 0) {
                dlg.secondary(I18n.t(ctx, R.string.ig_followers_history_clear), () ->
                        new Thread(() -> ps.reso.instaeclipse.utils.users.FollowersHistory.clear(ctx, self)).start());
            }
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ history dialog: " + t);
        }
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
