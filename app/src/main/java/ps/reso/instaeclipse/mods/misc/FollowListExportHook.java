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
 * the fragment) is written as username,full_name,id. Only loaded rows are exported — scroll to
 * the end of the list first for a complete file.
 */
public class FollowListExportHook {

    private static final String TAG = "(IE|FollowExport) ";
    private static final String USER = "com.instagram.user.model.User";
    private static Method fullNameGetter;

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
            final Class<?> fragClass = frag;
            XposedBridge.hookMethod(ovc, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.followListExport || !fragClass.isInstance(param.thisObject)) return;
                    if (param.args[0] instanceof View root) root.post(() -> addPill(root));
                }
            });
            ModuleLog.line(TAG + "✅ " + frag.getName() + " fullName=" + (fullNameGetter != null));
            FeatureStatusTracker.setHooked("FollowListExport");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
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
        pill.setOnClickListener(v -> export(root));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = (int) (24 * d);
        lp.rightMargin = (int) (16 * d);
        g.addView(pill, lp);
        pill.bringToFront();
    }

    private static void export(View root) {
        AbsListView list = findList(root);
        Adapter ad = list != null ? list.getAdapter() : null;
        if (ad instanceof HeaderViewListAdapter h) ad = h.getWrappedAdapter();
        if (ad == null) {
            Toast.makeText(root.getContext(), I18n.t(root.getContext(), R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, String[]> users = collect(ad);
        if (users.isEmpty()) {
            Toast.makeText(root.getContext(), I18n.t(root.getContext(), R.string.ig_follow_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        android.content.Context ctx = act != null ? act : root.getContext();
        new Thread(() -> {
            try {
                StringBuilder csv = new StringBuilder("username,full_name,id\n");
                for (String[] u : users.values()) csv.append(q(u[0])).append(',').append(q(u[1])).append(',').append(q(u[2])).append('\n');
                String file = "follow_list_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
                try (OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openExport(ctx, file, "text/csv")) {
                    out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM for Excel
                    out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                }
                ModuleLog.line(TAG + "exported " + users.size() + " users to " + file);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_follow_export_done, users.size(), file), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ export: " + t);
            }
        }).start();
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
    private static Map<String, String[]> collect(Object adapter) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{adapter, 0});
        while (!q.isEmpty() && seen.size() < 20000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { for (Object it : l) q.add(new Object[]{it, d + 1}); continue; }
            if (o instanceof Map<?, ?> m) { for (Object it : m.values()) q.add(new Object[]{it, d + 1}); continue; }
            if (o.getClass().getName().equals(USER)) {
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
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
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
