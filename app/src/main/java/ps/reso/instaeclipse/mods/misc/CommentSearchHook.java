package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;

/**
 * Search inside the comments sheet (#230).
 *
 * - Comment list view-model = declaring class of the method using
 *   "select_comment_screen_pin_comment_tap" (X.054n on 452, X.0BCO on 451). Its static UI-state
 *   builder (the static method taking the VM class among its params and returning an object)
 *   is re-run on every update; after it we collect the rows that carry a User (author) and the
 *   comment text (longest String field) — i.e. every comment loaded so far.
 * - Sheet fragment = class whose onViewCreated uses "Failed to ingest comments close signal"
 *   (X.0Ivg on 452, X.0RtS on 451); a small "Search comments" pill is added there.
 * - The pill opens a dialog: type to filter by author or text; tap a result to copy it.
 * Only comments IG has loaded are searched (scroll to load more first).
 */
public class CommentSearchHook {

    private static final String TAG = "(IE|CommentSearch) ";
    private static final String USER = "com.instagram.user.model.User";
    /** {username, text, comment id, parent comment id ("" = top level), likes, reply count, created (s)} */
    private static volatile List<String[]> latest = new ArrayList<>();
    // IG's own comment JSON serializer (static void (generator, Comment) using "parent_comment_id")
    // and the JSON factory's (Writer) -> generator method: field names stay readable across versions
    private static Method commentSerializer, jsonWriterMethod;
    private static Class<?> jsonFactory;
    private static final Map<Object, String[]> ROW_CACHE = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** the open comments sheet (its list is scrolled by "Load all") */
    private static java.lang.ref.WeakReference<View> sheet = new java.lang.ref.WeakReference<>(null);
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile Runnable loading;
    /** ids / tracking tokens (long, no spaces, base64-ish) are never the comment text */
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9+/=_-]{32,}");

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> vm = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("select_comment_screen_pin_comment_tap")))) {
                vm = cl.loadClass(md.getClassName());
                break;
            }
            if (vm == null) { ModuleLog.line(TAG + "❌ view-model not found"); return; }
            int builders = 0;
            for (Method m : vm.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType().isPrimitive()
                        || m.getReturnType() == void.class || m.getParameterCount() < 10) continue;
                boolean takesVm = false;
                for (Class<?> p : m.getParameterTypes()) if (p == vm) takesVm = true;
                if (!takesVm) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!FeatureFlags.commentSearch || param.getResult() == null) return;
                        List<String[]> rows = collect(param.getResult());
                        if (!rows.isEmpty()) latest = rows;
                    }
                });
                builders++;
            }
            try {
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("parent_comment_id", "child_comment_count").returnType("void").paramCount(2)))) {
                    Method m = md.getMethodInstance(cl);
                    if (Modifier.isStatic(m.getModifiers())) { commentSerializer = m; break; }
                }
                if (commentSerializer != null) {
                    Class<?> gen = commentSerializer.getParameterTypes()[0];
                    for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .paramTypes("java.io.Writer").returnType(gen.getName())))) {
                        Method m = md.getMethodInstance(cl);
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        try { m.getDeclaringClass().getConstructor(); jsonWriterMethod = m; jsonFactory = m.getDeclaringClass(); break; }
                        catch (NoSuchMethodException ignored) {}
                    }
                    commentSerializer.setAccessible(true);
                }
                ModuleLog.line(TAG + "comment json: serializer=" + (commentSerializer != null) + " factory=" + (jsonFactory != null));
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ comment json: " + t);
            }
            int frags = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("Failed to ingest comments close signal")))) {
                Class<?> frag = cl.loadClass(md.getClassName());
                try {
                    Method ovc = frag.getDeclaredMethod("onViewCreated", View.class, android.os.Bundle.class);
                    XposedBridge.hookMethod(ovc, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (FeatureFlags.commentSearch && param.args[0] instanceof View root) root.post(() -> addPill(host(root)));
                        }
                    });
                    frags++;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            ModuleLog.line(TAG + "✅ vm " + vm.getName() + " builders=" + builders + " fragments=" + frags);
            if (builders > 0 && frags > 0) FeatureStatusTracker.setHooked("CommentSearch");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /** Every (author, text) row reachable from the UI state, in order, de-duplicated. */
    private static List<String[]> collect(Object state) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object[]> q = new java.util.ArrayDeque<>();
        q.add(new Object[]{state, 0});
        while (!q.isEmpty() && seen.size() < 6000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) {
                for (Object it : l) q.add(new Object[]{it, d + 1});
                continue;
            }
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            Object user = null;
            String text = null;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        if (f.getType().getName().equals(USER)) user = v;
                        else if (v instanceof String s) {
                            if (!s.startsWith("http") && !TOKEN.matcher(s).matches()
                                    && (text == null || s.length() > text.length())) text = s;
                        } else if (d < 4 && !f.getType().isPrimitive() && !(v instanceof Number) && !(v instanceof Boolean)) {
                            q.add(new Object[]{v, d + 1});
                        }
                    } catch (Throwable ignored) {}
                }
            if (user != null && text != null && !text.isEmpty() && !text.matches("[0-9_:.-]+")) {
                String u = UserUtils.callUsernameGetter(user);
                if (u == null || text.equals(u)) continue;
                String[] row = commentRow(o, u);
                if (row != null) out.put(row[2], row); // by comment id (newer state wins)
                else out.putIfAbsent(u + "\u0000" + text, new String[]{u, text, "", "", "", "", ""});
            }
        }
        // the row view-models wrap the Comment model: keep the model's row (it has ids), drop the wrapper's
        Set<String> withId = new java.util.HashSet<>();
        for (String[] r : out.values()) if (!r[2].isEmpty()) withId.add(r[0] + "\u0000" + r[1]);
        List<String[]> rows = new ArrayList<>();
        for (String[] r : out.values()) if (!r[2].isEmpty() || !withId.contains(r[0] + "\u0000" + r[1])) rows.add(r);
        return rows;
    }

    /** A Comment model as a row, via IG's own serializer (cached: the models are immutable). */
    private static String[] commentRow(Object c, String user) {
        if (commentSerializer == null || jsonFactory == null) return null;
        if (!commentSerializer.getParameterTypes()[1].isInstance(c)) {
            if (!rowLogged) { rowLogged = true; ModuleLog.line(TAG + "row class " + c.getClass().getName() + " is not " + commentSerializer.getParameterTypes()[1].getName()); }
            return null;
        }
        String[] cached = ROW_CACHE.get(c);
        if (cached != null) return cached;
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            Object gen = jsonWriterMethod.invoke(jsonFactory.getConstructor().newInstance(), sw);
            commentSerializer.invoke(null, gen, c);
            if (gen instanceof java.io.Flushable f) f.flush();
            org.json.JSONObject j = new org.json.JSONObject(sw.toString());
            String id = j.optString("pk", "");
            if (id.isEmpty()) return null;
            String parent = j.optString("parent_comment_id", "");
            if (parent.equals("null") || parent.equals("0")) parent = "";
            String[] row = {user, j.optString("text", ""), id, parent,
                    j.optString("comment_like_count", ""), j.optString("child_comment_count", ""), j.optString("created_at", "")};
            ROW_CACHE.put(c, row);
            return row;
        } catch (Throwable t) {
            if (!rowLogged) { rowLogged = true; ModuleLog.line(TAG + "⚠️ comment json: " + (t.getCause() != null ? t.getCause() : t)); }
            return null;
        }
    }

    private static boolean rowLogged;

    /** Compose roots can't hold child views: use the nearest real ViewGroup above them. */
    private static View host(View root) {
        View v = root;
        while (v != null && v.getClass().getName().contains("ComposeView")) {
            v = v.getParent() instanceof View p ? p : null;
        }
        return v != null ? v : root;
    }

    private static void addPill(View root) {
        sheet = new java.lang.ref.WeakReference<>(root);
        if (!(root instanceof ViewGroup g) || root.findViewWithTag("ie_comment_search") != null) return;
        ViewGroup row = headerRow(g);
        if (row != null && row.findViewWithTag("ie_comment_search") != null) return;
        Context ctx = root.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(ctx);
        pill.setTag("ie_comment_search");
        pill.setText("🔍 " + I18n.t(ctx, R.string.ig_comment_search));
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        int ph = (int) (12 * d), pv = (int) (6 * d);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC3A3A3C);
        bg.setCornerRadius(16 * d);
        pill.setBackground(bg);
        pill.setOnClickListener(v -> {
            try {
                showSearch(v.getContext());
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ search dialog: " + t);
            }
        });
        // In the sheet header the bottom sheet's touch handling swallows the click (DOWN/UP reach
        // the pill but performClick never runs), so act on UP ourselves.
        pill.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (e.getX() >= 0 && e.getY() >= 0 && e.getX() <= v.getWidth() && e.getY() <= v.getHeight()) v.performClick();
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.topMargin = (int) (34 * d);   // header row, left of the centered "Comments" title
        lp.leftMargin = (int) (12 * d);
        pill.setClickable(true);
        pill.setElevation(30 * d);
        pill.setTranslationZ(30 * d);
        // Preferred: in the sheet's header row next to the "Comments" title (on 452 the list
        // container starts below the header, so a fixed top margin put the pill on the first comment).
        if (row != null) {
            try {
                ViewGroup.LayoutParams hl = headerParams(row, (int) (12 * d));
                if (hl != null) {
                    // compact in the header (the full label would cover the centred title)
                    pill.setText("🔍");
                    pill.setContentDescription(I18n.t(ctx, R.string.ig_comment_search));
                    row.addView(pill, hl);
                    if (!(row instanceof FrameLayout)) { // centred like the title: move it to the row start
                        final int margin = (int) (12 * d);
                        pill.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                            v.setTranslationX(rtl ? (row.getWidth() - margin) - r : margin - l);
                        });
                    }
                    return;
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ header pill: " + t);
            }
        }
        try {
            if (g instanceof FrameLayout) { g.addView(pill, lp); pill.bringToFront(); }
            else {
                FrameLayout overlay = new FrameLayout(ctx);
                overlay.addView(pill, lp);
                g.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ pill: " + t);
        }
    }

    /** The layout holding the sheet's "Comments" title (id title_text_view), nearest to the sheet. */
    @android.annotation.SuppressLint("DiscouragedApi")
    private static ViewGroup headerRow(View sheet) {
        int id = sheet.getResources().getIdentifier("title_text_view", "id", sheet.getContext().getPackageName());
        if (id == 0) return null;
        for (View v = sheet; v != null; v = v.getParent() instanceof View p ? p : null) {
            View title = v.findViewById(id);
            if (title != null) return title.getParent() instanceof ViewGroup g ? g : null;
        }
        return null;
    }

    /** Layout params that put the pill at the start of the header row, vertically centred
     *  (FrameLayout gravity, or ConstraintLayout constraints to the parent). */
    private static ViewGroup.LayoutParams headerParams(ViewGroup row, int startMargin) throws Exception {
        int w = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (row instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, w, Gravity.START | Gravity.CENTER_VERTICAL);
            lp.setMarginStart(startMargin);
            return lp;
        }
        // ConstraintLayout (its LayoutParams class is renamed by R8): copy the title's params, which
        // centre it in the row, then slide the pill to the row start once it is laid out.
        View title = null;
        for (int i = 0; i < row.getChildCount(); i++)
            if (row.getChildAt(i) instanceof TextView t && t.getId() != View.NO_ID
                    && "title_text_view".equals(row.getResources().getResourceEntryName(t.getId()))) title = t;
        if (title == null) return null;
        ViewGroup.LayoutParams src = title.getLayoutParams();
        // Only (int,int) survives R8: build one and copy the primitive fields (the constraints);
        // object fields (the layout's internal widget) must stay the pill's own.
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams)
                src.getClass().getConstructor(int.class, int.class).newInstance(w, w);
        for (Class<?> k = src.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getType().isPrimitive()) continue;
                try { f.setAccessible(true); f.set(lp, f.get(src)); } catch (Throwable ignored) {}
            }
        lp.width = w;
        lp.height = w;
        return lp;
    }

    private static void showSearch(Context c) {
        // The pill's own activity: the "current" one can be a different, hidden activity (e.g. the
        // main activity behind UrlHandlerActivity when the post was opened from a link).
        Activity act = null;
        for (Context k = c; k != null; k = k instanceof android.content.ContextWrapper w ? w.getBaseContext() : null)
            if (k instanceof Activity a) { act = a; break; }
        if (act == null) act = UIHookManager.getCurrentActivity();
        final Context ctx = act != null ? act : c;
        final float d = ctx.getResources().getDisplayMetrics().density;
        final boolean dark = (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        final int cBg = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        final int cField = dark ? 0xFF2C2C2E : 0xFFEFEFF2;
        final int cText = dark ? 0xFFF5F5F5 : 0xFF111111;
        final int cSub = dark ? 0xFF9A9AA0 : 0xFF6B6B70;
        final int cDivider = dark ? 0x22FFFFFF : 0x14000000;
        final int cAccent = 0xFF0095F6;
        final int cHighlight = dark ? 0x660095F6 : 0x400095F6;

        final List<String[]> all = new ArrayList<>(latest);
        final List<Object[]> shown = new ArrayList<>();
        final Set<String> expanded = new java.util.HashSet<>();
        final int[] matches = {0};
        final String[] query = {""};

        android.app.Dialog dlg = new android.app.Dialog(ctx);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * d);
        card.setPadding(pad, pad, pad, (int) (10 * d));
        card.setBackground(round(cBg, 22 * d));

        // header: title + close
        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(I18n.t(ctx, R.string.ig_comment_search));
        title.setTextColor(cText);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(cSub);
        close.setPadding((int) (10 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        close.setOnClickListener(v -> dlg.dismiss());
        header.addView(close);
        card.addView(header);

        // search field with clear button
        LinearLayout field = new LinearLayout(ctx);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(round(cField, 14 * d));
        field.setPadding((int) (12 * d), 0, (int) (6 * d), 0);
        TextView glass = new TextView(ctx);
        glass.setText("🔍");
        glass.setTextSize(15);
        field.addView(glass);
        EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setBackground(null);
        et.setTextColor(cText);
        et.setHintTextColor(cSub);
        et.setTextSize(15);
        et.setHint(I18n.t(ctx, R.string.ig_comment_search_field_hint));
        et.setPadding((int) (8 * d), (int) (12 * d), (int) (8 * d), (int) (12 * d));
        field.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = new TextView(ctx);
        clear.setText("✕");
        clear.setTextColor(cSub);
        clear.setTextSize(14);
        clear.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> et.setText(""));
        field.addView(clear);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = (int) (14 * d);
        card.addView(field, flp);

        // status row: counts + Load all pill
        LinearLayout status = new LinearLayout(ctx);
        status.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = new TextView(ctx);
        count.setTextColor(cSub);
        count.setTextSize(13);
        status.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView loadBtn = new TextView(ctx);
        loadBtn.setText(I18n.t(ctx, R.string.ig_comment_search_load_all));
        loadBtn.setTextColor(Color.WHITE);
        loadBtn.setTextSize(13);
        loadBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        loadBtn.setPadding((int) (14 * d), (int) (7 * d), (int) (14 * d), (int) (7 * d));
        loadBtn.setBackground(round(cAccent, 16 * d));
        status.addView(loadBtn);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (10 * d);
        card.addView(status, slp);

        android.widget.ProgressBar bar = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.INVISIBLE);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(cAccent));
        card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (6 * d)));

        // results
        ListView lv = new ListView(ctx);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(cDivider));
        lv.setDividerHeight(Math.max(1, (int) d / 2));
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        lv.setVerticalScrollBarEnabled(false);
        android.widget.BaseAdapter ad = new android.widget.BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public int getViewTypeCount() { return 2; }
            public int getItemViewType(int i) { return (int) shown.get(i)[0]; }
            public boolean isEnabled(int i) { return true; }
            public View getView(int i, View conv, ViewGroup parent) {
                Object[] e = shown.get(i);
                String[] r = (String[]) e[1];
                boolean reply = (boolean) e[2];
                int indent = reply ? (int) (46 * d) : 0;
                if ((int) e[0] == 1) { // "View N replies" / "Hide replies"
                    LinearLayout t = new LinearLayout(ctx);
                    t.setGravity(Gravity.CENTER_VERTICAL);
                    t.setPadding((int) (48 * d), (int) (2 * d), 0, (int) (10 * d));
                    View line = new View(ctx);
                    line.setBackgroundColor(cSub);
                    t.addView(line, new LinearLayout.LayoutParams((int) (24 * d), Math.max(1, (int) d)));
                    TextView tv = new TextView(ctx);
                    tv.setText((String) e[3]);
                    tv.setTextColor(cSub);
                    tv.setTextSize(13);
                    tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    tv.setPadding((int) (10 * d), 0, 0, 0);
                    t.addView(tv);
                    return t;
                }
                LinearLayout row = new LinearLayout(ctx);
                row.setPadding(indent, (int) ((reply ? 7 : 11) * d), 0, (int) ((reply ? 7 : 11) * d));
                TextView av = new TextView(ctx);
                av.setText(r[0].isEmpty() ? "?" : r[0].substring(0, 1).toUpperCase(Locale.ROOT));
                av.setGravity(Gravity.CENTER);
                av.setTextColor(Color.WHITE);
                av.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                av.setTextSize(reply ? 12 : 14);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(avatarColor(r[0]));
                av.setBackground(circle);
                int sz = (int) ((reply ? 28 : 36) * d);
                row.addView(av, new LinearLayout.LayoutParams(sz, sz));
                LinearLayout col = new LinearLayout(ctx);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding((int) (12 * d), 0, 0, 0);
                LinearLayout top = new LinearLayout(ctx);
                top.setGravity(Gravity.CENTER_VERTICAL);
                TextView user = new TextView(ctx);
                user.setText(highlight(r[0], query[0], cHighlight));
                user.setTextColor(cText);
                user.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                user.setTextSize(13.5f);
                user.setSingleLine(true);
                top.addView(user);
                String meta = meta(r);
                if (!meta.isEmpty()) {
                    TextView m = new TextView(ctx);
                    m.setText("  " + meta);
                    m.setTextColor(cSub);
                    m.setTextSize(12);
                    m.setSingleLine(true);
                    top.addView(m);
                }
                col.addView(top);
                TextView text = new TextView(ctx);
                text.setText(highlight(r[1], query[0], cHighlight));
                text.setTextColor(cText);
                text.setTextSize(14);
                text.setLineSpacing(2 * d, 1f);
                text.setPadding(0, (int) (2 * d), 0, 0);
                col.addView(text);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                return row;
            }
        };
        lv.setAdapter(ad);
        TextView empty = new TextView(ctx);
        empty.setText(I18n.t(ctx, R.string.ig_comment_search_empty));
        empty.setTextColor(cSub);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, (int) (40 * d), 0, (int) (40 * d));
        FrameLayout results = new FrameLayout(ctx);
        results.addView(lv);
        results.addView(empty);
        int h = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.55f);
        card.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        TextView tip = new TextView(ctx);
        tip.setText(I18n.t(ctx, R.string.ig_comment_search_tip));
        tip.setTextColor(cSub);
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, (int) (8 * d), 0, 0);
        card.addView(tip);

        Runnable refresh = () -> {
            query[0] = et.getText().toString().trim();
            String q = query[0].toLowerCase(Locale.ROOT);
            // tree: top-level comments in IG's order, their loaded replies (oldest first) under them;
            // a reply whose parent isn't loaded stays top level
            Map<String, String[]> byId = new java.util.HashMap<>();
            for (String[] r : all) if (!r[2].isEmpty()) byId.put(r[2], r);
            Map<String, List<String[]>> kids = new java.util.HashMap<>();
            List<String[]> tops = new ArrayList<>();
            for (String[] r : all) {
                if (!r[3].isEmpty() && byId.containsKey(r[3])) kids.computeIfAbsent(r[3], k -> new ArrayList<>()).add(r);
                else tops.add(r);
            }
            for (List<String[]> l : kids.values()) l.sort((x, y) -> Long.compare(num(x[6]), num(y[6])));
            shown.clear();
            matches[0] = 0;
            for (String[] t : tops) {
                List<String[]> ch = kids.getOrDefault(t[2], Collections.emptyList());
                if (q.isEmpty()) {
                    shown.add(new Object[]{0, t, false, null});
                    if (ch.isEmpty()) continue;
                    if (expanded.contains(t[2])) {
                        for (String[] kid : ch) shown.add(new Object[]{0, kid, true, null});
                        shown.add(new Object[]{1, t, false, I18n.t(ctx, R.string.ig_comment_search_hide_replies)});
                    } else {
                        shown.add(new Object[]{1, t, false, I18n.t(ctx, R.string.ig_comment_search_view_replies, ch.size())});
                    }
                    continue;
                }
                boolean topHit = hit(t, q);
                List<String[]> hits = new ArrayList<>();
                for (String[] kid : ch) if (hit(kid, q)) hits.add(kid);
                if (!topHit && hits.isEmpty()) continue;
                matches[0] += (topHit ? 1 : 0) + hits.size();
                shown.add(new Object[]{0, t, false, null}); // parent shown for context
                for (String[] kid : hits) shown.add(new Object[]{0, kid, true, null});
            }
            ad.notifyDataSetChanged();
            empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
            count.setText(q.isEmpty() ? I18n.t(ctx, R.string.ig_comment_search_count, all.size())
                    : I18n.t(ctx, R.string.ig_comment_search_matches, all.size(), matches[0]));
        };
        // take in whatever has loaded so far (live while loading, and when stopped or finished)
        Runnable pull = () -> {
            all.clear();
            all.addAll(latest);
            refresh.run();
        };
        Runnable idle = () -> {
            bar.setVisibility(View.INVISIBLE);
            loadBtn.setText(I18n.t(ctx, R.string.ig_comment_search_load_all));
            pull.run();
        };
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            public void onTextChanged(CharSequence s, int a, int b, int n) {}
            public void afterTextChanged(Editable s) { refresh.run(); }
        });
        loadBtn.setOnClickListener(v -> {
            if (loading != null) { stopLoading(); idle.run(); return; }
            bar.setVisibility(View.VISIBLE);
            loadAll(n -> {
                loadBtn.setText(I18n.t(ctx, R.string.ig_comment_search_stop));
                if (n != all.size()) pull.run();
            }, idle);
        });
        lv.setOnItemClickListener((p, v, i, id) -> {
            Object[] e = shown.get(i);
            String[] r = (String[]) e[1];
            if ((int) e[0] == 1) { // expand / collapse replies
                if (!expanded.remove(r[2])) expanded.add(r[2]);
                refresh.run();
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("comment", r[1]));
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied), Toast.LENGTH_SHORT).show();
        });
        refresh.run();

        dlg.setContentView(card);
        dlg.setOnDismissListener(x -> stopLoading());
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private static boolean hit(String[] r, String q) {
        return r[0].toLowerCase(Locale.ROOT).contains(q) || r[1].toLowerCase(Locale.ROOT).contains(q);
    }

    private static long num(String s) {
        try { return Long.parseLong(s); } catch (Throwable t) { return 0; }
    }

    /** "1,615 ♥ · 12h" from the row's likes and creation time. */
    private static String meta(String[] r) {
        StringBuilder b = new StringBuilder();
        long likes = num(r[4]);
        if (likes > 0) b.append(String.format(Locale.getDefault(), "%,d ♥", likes));
        long created = num(r[6]);
        if (created > 0) {
            long s = Math.max(0, System.currentTimeMillis() / 1000 - created);
            String age = s < 60 ? s + "s" : s < 3600 ? s / 60 + "m" : s < 86400 ? s / 3600 + "h"
                    : s < 604800 ? s / 86400 + "d" : s / 604800 + "w";
            if (b.length() > 0) b.append(" · ");
            b.append(age);
        }
        return b.toString();
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static final int[] AVATAR_COLORS = {0xFFE1306C, 0xFF833AB4, 0xFFF77737, 0xFF0095F6, 0xFF00A86B, 0xFFC13584, 0xFFFD1D1D, 0xFF5851DB};

    private static int avatarColor(String user) {
        return AVATAR_COLORS[(user.hashCode() & 0x7fffffff) % AVATAR_COLORS.length];
    }

    /** The text with every case-insensitive occurrence of the query given a tinted background. */
    private static CharSequence highlight(String text, String query, int color) {
        if (query.isEmpty()) return text;
        android.text.SpannableString s = new android.text.SpannableString(text);
        String lt = text.toLowerCase(Locale.ROOT), lq = query.toLowerCase(Locale.ROOT);
        for (int i = lt.indexOf(lq); i >= 0 && lt.length() == text.length(); i = lt.indexOf(lq, i + lq.length())) {
            s.setSpan(new android.text.style.BackgroundColorSpan(color), i, i + lq.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + lq.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private static void stopLoading() {
        if (loading != null) MAIN.removeCallbacks(loading);
        loading = null;
    }

    /** Scrolls the sheet's comment list to its end until nothing new loads for 3 s (IG fetches
     *  the next page itself near the end). Progress = comments collected so far. */
    private static void loadAll(java.util.function.IntConsumer progress, Runnable done) {
        View root = sheet.get();
        View list = root != null ? scrollable(root) : null; // inside the sheet, not the feed behind it
        ModuleLog.line(TAG + "load all: list=" + (list != null ? list.getClass().getName() + " h=" + list.getHeight() : null));
        if (list == null) {
            ModuleLog.line(TAG + "⚠️ load all: comment list not found");
            done.run();
            return;
        }
        final long started = System.currentTimeMillis();
        final long[] lastChange = {started};
        final int[] lastCount = {latest.size()};
        loading = new Runnable() {
            @Override
            public void run() {
                if (loading != this) return;
                if (!list.isAttachedToWindow() || System.currentTimeMillis() - started > 5 * 60_000) {
                    loading = null;
                    done.run();
                    return;
                }
                scrollDown(list);
                int count = latest.size();
                progress.accept(count);
                if (count != lastCount[0] || list.canScrollVertically(1)) {
                    if (count != lastCount[0]) lastChange[0] = System.currentTimeMillis();
                    lastCount[0] = count;
                }
                // RecyclerView: at the end + nothing new for 3 s. Compose: nothing new for 4 s.
                boolean rv = isRecyclerView(list.getClass());
                long idle = System.currentTimeMillis() - lastChange[0];
                if (rv ? idle > 3000 && !list.canScrollVertically(1) : idle > 4000) {
                    loading = null; // at the end and nothing new for 3 s
                    ModuleLog.line(TAG + "load all: " + count + " comments");
                    done.run();
                    return;
                }
                MAIN.postDelayed(this, rv ? 300 : 450);
            }
        };
        MAIN.post(loading);
    }

    /** The comment list: the tallest shown RecyclerView, else the tallest vertically scrollable view. */
    private static View scrollable(View root) {
        View rv = null, any = null, compose = null;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (!v.isShown()) continue;
            if (isRecyclerView(v.getClass()) && (rv == null || v.getHeight() > rv.getHeight())) rv = v;
            if ((v.canScrollVertically(1) || v.canScrollVertically(-1)) && (any == null || v.getHeight() > any.getHeight())) any = v;
            // 452: the list is a Compose LazyColumn (doesn't report scrollability to the View system)
            if (v.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView")
                    && (compose == null || v.getHeight() > compose.getHeight())) compose = v;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return rv != null ? rv : any != null ? any : compose;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }

    /** RecyclerView scrolls directly; other lists (Compose) get a synthetic upward drag. */
    private static void scrollDown(View list) {
        if (isRecyclerView(list.getClass())) {
            list.scrollBy(0, list.getHeight() * 3);
            return;
        }
        long t = android.os.SystemClock.uptimeMillis();
        float x = list.getWidth() / 2f, y0 = list.getHeight() * 0.8f, y1 = list.getHeight() * 0.2f;
        android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y0, 0);
        list.dispatchTouchEvent(down);
        for (int i = 1; i <= 6; i++) {
            android.view.MotionEvent mv = android.view.MotionEvent.obtain(t, t + i * 10L,
                    android.view.MotionEvent.ACTION_MOVE, x, y0 + (y1 - y0) * i / 6f, 0);
            list.dispatchTouchEvent(mv);
            mv.recycle();
        }
        android.view.MotionEvent up = android.view.MotionEvent.obtain(t, t + 70, android.view.MotionEvent.ACTION_UP, x, y1, 0);
        list.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }
}
