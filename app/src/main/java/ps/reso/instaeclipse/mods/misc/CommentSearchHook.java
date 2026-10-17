package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
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
    /** {username, text, comment id, parent comment id ("" = top level), likes, reply count, created (s), media id};
     *  the post caption row has no ids (its user = the post author) */
    private static volatile List<String[]> latest = new ArrayList<>();
    /** The latest comment UI-state object and the comments-sheet fragment — walked at export time to
     *  find the post's Media object (so the post's own photos/videos can be saved with the comments). */
    private static volatile Object lastState;
    private static java.lang.ref.WeakReference<Object> lastFragment = new java.lang.ref.WeakReference<>(null);
    // IG's own comment JSON serializer (static void (generator, Comment) using "parent_comment_id")
    // and the JSON factory's (Writer) -> generator method: field names stay readable across versions
    private static Method commentSerializer, jsonWriterMethod;
    private static Class<?> jsonFactory;
    private static final Map<Object, String[]> ROW_CACHE = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** the open comments sheet (its list is scrolled by "Load all") */
    private static java.lang.ref.WeakReference<View> sheet = new java.lang.ref.WeakReference<>(null);
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile Runnable loading;
    /** Remembered state of the "Also save the post's photos/videos" export option. */
    private static boolean exportPostMedia = true;
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
                        if (!rows.isEmpty()) { latest = rows; lastState = param.getResult(); }
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
                            if (param.thisObject != null) lastFragment = new java.lang.ref.WeakReference<>(param.thisObject);
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
            if (user == null) continue;
            // a real Comment model counts even without text (GIF / image comments)
            if (commentSerializer != null && commentSerializer.getParameterTypes()[1].isInstance(o)) {
                String u = UserUtils.callUsernameGetter(user);
                String[] row = u != null ? commentRow(o, u) : null;
                if (row != null) { out.put(row[2], row); continue; } // by comment id (newer state wins)
            }
            if (text != null && !text.isEmpty() && !text.matches("[0-9_:.-]+")) {
                String u = UserUtils.callUsernameGetter(user);
                if (u == null || text.equals(u)) continue;
                out.putIfAbsent(u + "\u0000" + text, new String[]{u, text, "", "", "", "", "", "", "", ""});
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
                    j.optString("comment_like_count", ""), j.optString("child_comment_count", ""), j.optString("created_at", ""),
                    j.optString("media_id", ""), "", ""};
            String[] media = commentMedia(j);
            row[8] = media[0];
            row[9] = media[1];
            if (row[1].isEmpty() && !media[0].isEmpty()) row[1] = "gif".equals(media[1]) ? "🖼 GIF" : "🖼 Image";
            ROW_CACHE.put(c, row);
            return row;
        } catch (Throwable t) {
            if (!rowLogged) { rowLogged = true; ModuleLog.line(TAG + "⚠️ comment json: " + (t.getCause() != null ? t.getCause() : t)); }
            return null;
        }
    }

    /** {url, "gif" | "image"} of a GIF / image comment ("" when it has none). */
    private static String[] commentMedia(org.json.JSONObject j) {
        org.json.JSONObject gif = j.optJSONObject("giphy_media_info");
        if (gif != null) {
            String u = firstUrl(gif, 0);
            if (!u.isEmpty()) return new String[]{u, "gif"};
        }
        for (String k : new String[]{"media_info", "media_comment_info", "media"}) {
            Object o = j.opt(k);
            if (o instanceof org.json.JSONObject || o instanceof org.json.JSONArray) {
                String u = firstUrl(o, 0);
                if (!u.isEmpty()) return new String[]{u, u.contains(".gif") || u.contains("giphy") ? "gif" : "image"};
            }
        }
        return new String[]{"", ""};
    }

    /** First image-looking https URL in a JSON tree (keys named url / uri first). */
    private static String firstUrl(Object o, int depth) {
        if (o == null || depth > 6) return "";
        if (o instanceof org.json.JSONObject jo) {
            for (String k : new String[]{"url", "uri", "image_url"}) {
                String v = jo.optString(k, "");
                if (v.startsWith("https://")) return v;
            }
            for (java.util.Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = firstUrl(jo.opt(it.next()), depth + 1);
                if (!r.isEmpty()) return r;
            }
        } else if (o instanceof org.json.JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 10; i++) {
                String r = firstUrl(ja.opt(i), depth + 1);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
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
        final int cAccent = ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.accentOr(0xFF0095F6);
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
        TextView exportBtn = new TextView(ctx);
        exportBtn.setText(I18n.t(ctx, R.string.ig_comment_export));
        exportBtn.setTextColor(cAccent);
        exportBtn.setTextSize(13);
        exportBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        exportBtn.setPadding((int) (14 * d), (int) (6 * d), (int) (14 * d), (int) (6 * d));
        GradientDrawable outline = round(Color.TRANSPARENT, 16 * d);
        outline.setStroke(Math.max(1, (int) (1.5f * d)), cAccent);
        exportBtn.setBackground(outline);
        exportBtn.setOnClickListener(v -> {
            if (all.isEmpty()) return;
            List<String[]> rows = new ArrayList<>(all);
            ps.reso.instaeclipse.utils.dialog.IeDialog exDlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ig_comment_export));
            android.widget.CheckBox postBox = exDlg.checkBox(I18n.t(ctx, R.string.ig_comment_export_post_media), exportPostMedia);
            exDlg.secondary(I18n.t(ctx, R.string.ig_dialog_cancel), null);
            exDlg.primary(I18n.t(ctx, R.string.ig_comment_export), () -> {
                exportPostMedia = postBox.isChecked();
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_comment_export_working), Toast.LENGTH_SHORT).show();
                exportComments(ctx, rows, exportPostMedia);
            });
            exDlg.show();
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMarginEnd((int) (8 * d));
        status.addView(exportBtn, elp);
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
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied), Toast.LENGTH_SHORT).show();
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

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** Post shortcode from its media pk (base64url of the number; the reverse of a link's code). */
    static String shortcode(String mediaId) {
        try {
            String pk = mediaId.contains("_") ? mediaId.substring(0, mediaId.indexOf('_')) : mediaId;
            java.math.BigInteger n = new java.math.BigInteger(pk);
            if (n.signum() <= 0) return "";
            StringBuilder b = new StringBuilder();
            java.math.BigInteger sixtyFour = java.math.BigInteger.valueOf(64);
            while (n.signum() > 0) {
                java.math.BigInteger[] qr = n.divideAndRemainder(sixtyFour);
                b.append(ALPHABET.charAt(qr[1].intValue()));
                n = qr[0];
            }
            return b.reverse().toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String esc(String s) {
        return android.text.Html.escapeHtml(s == null ? "" : s).replace("\n", "<br>");
    }

    /** Saves the loaded comments as a self-contained HTML page (tree of replies, search box). */
    private static void exportComments(Context ctx, List<String[]> rows, boolean includePostMedia) {
        new Thread(() -> {
            try {
                String author = "", caption = "", mediaId = "";
                List<String[]> comments = new ArrayList<>();
                for (String[] r : rows) {
                    if (r[2].isEmpty() && author.isEmpty()) { author = r[0]; caption = r[1]; continue; }
                    if (r[2].isEmpty()) continue;
                    comments.add(r);
                    if (mediaId.isEmpty() && r.length > 7) mediaId = r[7];
                }
                String code = shortcode(mediaId);
                String link = code.isEmpty() ? "" : "https://www.instagram.com/p/" + code + "/";

                // Media the companion service will download + embed: {url, local name, mime}.
                List<String[]> media = new ArrayList<>();
                int[] ctr = {0};
                // The post's own photos/videos (carousel-aware) via the downloader's extractor, shown
                // in the hero. Downloaded + embedded so they stay on device and never expire.
                StringBuilder postGallery = new StringBuilder();
                if (includePostMedia && !mediaId.isEmpty()) {
                    try {
                        List<String> purls = postMediaUrls(ctx, mediaId);
                        int pi = 0;
                        for (String u : purls) {
                            if (u == null || !u.startsWith("http")) continue;
                            boolean vid = u.contains(".mp4");
                            String ext = vid ? ".mp4" : mediaExt(u, "image");
                            String name = String.format(Locale.US, "post_%02d%s", ++pi, ext);
                            media.add(new String[]{u, name, vid ? "video/mp4" : mediaMime(ext)});
                            postGallery.append(vid
                                    ? "<video class=media controls preload=none src=\"" + name + "\" onerror=\"this.remove()\"></video>"
                                    : "<img class=media loading=lazy src=\"" + name + "\" onerror=\"this.remove()\">");
                        }
                        ModuleLog.line(TAG + "post media: " + pi + " item(s) for " + mediaId);
                    } catch (Throwable t) { ModuleLog.line(TAG + "post media failed: " + t); }
                }
                Map<String, List<String[]>> kids = new java.util.HashMap<>();
                Set<String> ids = new java.util.HashSet<>();
                for (String[] r : comments) ids.add(r[2]);
                List<String[]> tops = new ArrayList<>();
                for (String[] r : comments) {
                    if (!r[3].isEmpty() && ids.contains(r[3])) kids.computeIfAbsent(r[3], k -> new ArrayList<>()).add(r);
                    else tops.add(r);
                }
                for (List<String[]> l : kids.values()) l.sort((x, y) -> Long.compare(num(x[6]), num(y[6])));
                int replies = comments.size() - tops.size();
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault());
                String now = fmt.format(new java.util.Date());

                StringBuilder h = new StringBuilder(64 * 1024);
                h.append("<!doctype html><html><head><meta charset=utf-8>")
                 .append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
                 .append("<title>Comments").append(author.isEmpty() ? "" : " · @" + esc(author)).append("</title><style>")
                 .append(":root{--bg:#fafafa;--card:#fff;--text:#111;--sub:#737373;--line:#ececec;--accent:#0095f6;--mark:#cfe8fd}")
                 .append("@media(prefers-color-scheme:dark){:root{--bg:#0b0b0c;--card:#18181a;--text:#f2f2f2;--sub:#9a9aa0;--line:#2a2a2d;--accent:#3aa8ff;--mark:#0b4a7a}}")
                 .append("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}")
                 .append(".wrap{max-width:720px;margin:0 auto;padding:16px}")
                 .append(".hero{background:linear-gradient(135deg,#feda75,#fa7e1e 25%,#d62976 50%,#962fbf 75%,#4f5bd5);border-radius:20px;padding:2px;margin-bottom:14px}")
                 .append(".heroin{background:var(--card);border-radius:18px;padding:18px}")
                 .append(".hero h1{margin:0 0 4px;font-size:20px}.hero a{color:var(--accent);text-decoration:none;word-break:break-all}")
                 .append(".cap{margin-top:10px;color:var(--text);white-space:normal;max-height:9em;overflow:auto}")
                 .append(".stats{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.chip{background:var(--bg);border:1px solid var(--line);border-radius:999px;padding:4px 12px;font-size:13px;color:var(--sub)}")
                 .append(".search{position:sticky;top:0;z-index:2;padding:8px 0;background:var(--bg)}")
                 .append(".search input{width:100%;padding:12px 16px;border-radius:14px;border:1px solid var(--line);background:var(--card);color:var(--text);font-size:15px;outline:none}")
                 .append(".c{display:flex;gap:12px;padding:12px 14px;background:var(--card);border:1px solid var(--line);border-radius:16px;margin-top:10px}")
                 .append(".av{flex:none;width:38px;height:38px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700}")
                 .append(".body{min-width:0;flex:1}.u{font-weight:700}.m{color:var(--sub);font-size:12px;margin-left:6px}.t{margin-top:2px;overflow-wrap:anywhere}")
                 .append(".replies{margin:6px 0 0 50px;border-left:2px solid var(--line);padding-left:10px}")
                 .append(".replies .c{margin-top:6px;padding:10px 12px;border-radius:14px}.replies .av{width:28px;height:28px;font-size:12px}")
                 .append("details>summary{cursor:pointer;color:var(--sub);font-size:13px;font-weight:600;margin:6px 0 0 50px;list-style:none}")
                 .append("details>summary::before{content:'— ';}mark{background:var(--mark);color:inherit;border-radius:3px}")
                 .append(".foot{color:var(--sub);font-size:12px;text-align:center;margin:20px 0 8px}.hide{display:none!important}")
                 .append(".media{display:block;margin-top:8px;max-width:min(260px,100%);max-height:320px;border-radius:12px;border:1px solid var(--line)}")
                 .append(".postmedia{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.postmedia .media{margin-top:0;max-width:min(200px,46%)}")
                 .append(".tag{display:inline-block;margin-top:6px;padding:2px 10px;border-radius:999px;background:var(--bg);border:1px solid var(--line);color:var(--sub);font-size:12px}")
                 .append("</style></head><body><div class=wrap>");
                h.append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlBanner("Comments export"));
                h.append("<div class=hero><div class=heroin><h1>💬 Comments")
                 .append(author.isEmpty() ? "" : " on @" + esc(author) + "'s post").append("</h1>");
                if (!link.isEmpty()) h.append("<a href=\"").append(link).append("\">").append(link).append("</a>");
                if (!caption.isEmpty()) h.append("<div class=cap>").append(esc(caption)).append("</div>");
                if (postGallery.length() > 0) h.append("<div class=postmedia>").append(postGallery).append("</div>");
                h.append("<div class=stats><span class=chip>").append(tops.size()).append(" comments</span><span class=chip>")
                 .append(replies).append(" replies</span><span class=chip>Exported ").append(esc(now)).append("</span></div></div></div>");
                h.append("<div class=search><input id=q placeholder=\"Search names or words…\" autocomplete=off></div><div id=list>");
                for (String[] t : tops) {
                    h.append("<div class=thread>");
                    commentHtml(h, t, fmt, media, ctr);
                    List<String[]> ch = kids.get(t[2]);
                    if (ch != null && !ch.isEmpty()) {
                        h.append("<details><summary>").append(ch.size()).append(ch.size() == 1 ? " reply" : " replies")
                         .append("</summary><div class=replies>");
                        for (String[] r : ch) commentHtml(h, r, fmt, media, ctr);
                        h.append("</div></details>");
                    }
                    h.append("</div>");
                }
                h.append("</div><div class=foot>Only comments loaded in the app are included</div>")
                 .append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlFooter()).append("</div>")
                 .append("<script>const q=document.getElementById('q');const th=[...document.querySelectorAll('.thread')];")
                 .append("th.forEach(t=>t.querySelectorAll('.u,.t').forEach(e=>e.dataset.o=e.innerHTML));")
                 .append("function mk(e,s){const o=e.dataset.o;if(!s){e.innerHTML=o;return false;}const i=e.textContent.toLowerCase().indexOf(s);")
                 .append("if(i<0){e.innerHTML=o;return false;}const re=new RegExp('('+s.replace(/[.*+?^${}()|[\\]\\\\]/g,'\\\\$&')+')','gi');")
                 .append("e.innerHTML=o.replace(/(^|>)([^<]+)/g,(m,a,b)=>a+b.replace(re,'<mark>$1</mark>'));return true;}")
                 .append("q.addEventListener('input',()=>{const s=q.value.trim().toLowerCase();th.forEach(t=>{")
                 .append("const cs=[...t.querySelectorAll('.c')];let any=false;cs.forEach((c,i)=>{let h=false;")
                 .append("c.querySelectorAll('.u,.t').forEach(e=>{if(mk(e,s))h=true;});c.dataset.h=h?1:0;if(h)any=true;")
                 .append("if(i>0)c.classList.toggle('hide',!!s&&!h);});t.classList.toggle('hide',!!s&&!any);")
                 .append("const d=t.querySelector('details');if(d)d.open=!!s&&cs.slice(1).some(c=>c.dataset.h==1);});});</script>")
                 .append("</body></html>");

                String safe = (author.isEmpty() ? "post" : author).replaceAll("[^A-Za-z0-9._-]", "_");
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
                String folder = "comments_" + safe + (code.isEmpty() ? "" : "_" + code) + "_" + stamp;
                String file = folder + ".html";
                final int cCount = comments.size();

                // Preferred path: hand the HTML + comment media to the companion service, which saves
                // everything into <SAF folder>/comments/<folder>/ and inlines the media as base64 data
                // URIs — so GIF/image comments are kept on device and never expire.
                String saf = FeatureFlags.downloaderCustomUri;
                boolean delegated = false;
                if (saf != null && !saf.isEmpty()) {
                    try {
                        android.content.Intent it = new android.content.Intent();
                        it.setClassName("ps.reso.instaeclipse",
                                "ps.reso.instaeclipse.mods.media.DownloadSaveService");
                        it.putExtra("chatExportSubdir", "comments/" + folder);
                        it.putExtra("htmlFilename", file);
                        it.putExtra("htmlB64", android.util.Base64.encodeToString(
                                h.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
                        it.putExtra("saveUri", saf);
                        if (!media.isEmpty()) {
                            String[] us = new String[media.size()], ns = new String[media.size()], ms = new String[media.size()];
                            for (int i = 0; i < media.size(); i++) { us[i] = media.get(i)[0]; ns[i] = media.get(i)[1]; ms[i] = media.get(i)[2]; }
                            it.putExtra("mediaUrls", us);
                            it.putExtra("mediaNames", ns);
                            it.putExtra("mediaMimes", ms);
                        }
                        ctx.startForegroundService(it);
                        delegated = true;
                        ModuleLog.line(TAG + "delegated comment export (" + cCount + " comments, "
                                + media.size() + " media) to companion service: " + folder);
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "comment export delegate failed, writing locally: " + t);
                    }
                }

                if (!delegated) {
                    // No SAF folder set: fall back to the old single-file export with the media still
                    // pointing at the Instagram CDN links (which work only until they expire).
                    String html = h.toString();
                    for (String[] m : media) html = html.replace("src=\"" + m[1] + "\"", "src=\"" + esc(m[0]) + "\"");
                    try (java.io.OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openCommentsExport(ctx, file)) {
                        out.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    ModuleLog.line(TAG + "exported " + cCount + " comments to " + file);
                    MAIN.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_comment_export_done, cCount, file), Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ export: " + t);
                MAIN.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static void commentHtml(StringBuilder h, String[] r, java.text.SimpleDateFormat fmt,
                                    List<String[]> media, int[] ctr) {
        String initial = r[0].isEmpty() ? "?" : r[0].substring(0, 1).toUpperCase(Locale.ROOT);
        h.append("<div class=c><div class=av style=\"background:#")
         .append(String.format(Locale.US, "%06X", avatarColor(r[0]) & 0xFFFFFF)).append("\">").append(esc(initial))
         .append("</div><div class=body><span class=u>").append(esc(r[0])).append("</span><span class=m>");
        long likes = num(r[4]), created = num(r[6]);
        if (likes > 0) h.append(String.format(Locale.getDefault(), "%,d ♥", likes));
        if (created > 0) h.append(likes > 0 ? " · " : "").append(esc(fmt.format(new java.util.Date(created * 1000))));
        h.append("</span><div class=t>").append(esc(r[1])).append("</div>");
        if (r.length > 9 && !r[8].isEmpty()) {
            String label = "gif".equals(r[9]) ? "GIF" : "Image";
            // Reference the media by a local name; the companion service downloads it into the export
            // folder and inlines it as a base64 data URI, so it no longer expires with the CDN link.
            String kind = r[9].isEmpty() ? "image" : r[9];
            String ext = mediaExt(r[8], kind);
            String name = String.format(Locale.US, "c%04d_%s%s", ++ctr[0], kind, ext);
            media.add(new String[]{r[8], name, mediaMime(ext)});
            h.append("<img class=media loading=lazy referrerpolicy=no-referrer alt=\"").append(label).append("\" src=\"")
             .append(name).append("\" onerror=\"this.outerHTML='<span class=tag>🖼 ").append(label)
             .append(" (unavailable)</span>'\">");
        }
        h.append("</div></div>");
    }

    /** File extension for a comment-media URL (".gif"/".webp"/".mp4"/".jpg"). */
    private static String mediaExt(String url, String kind) {
        try {
            String p = url.split("\\?")[0];
            int dot = p.lastIndexOf('.'), slash = p.lastIndexOf('/');
            if (dot > slash && dot >= 0 && p.length() - dot <= 5) return p.substring(dot).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        return "gif".equals(kind) ? ".gif" : ".jpg";
    }

    private static String mediaMime(String ext) {
        switch (ext) {
            case ".gif":  return "image/gif";
            case ".webp": return "image/webp";
            case ".png":  return "image/png";
            case ".mp4":  return "video/mp4";
            default:       return "image/jpeg";
        }
    }

    /** The post's own media URLs (photos/videos, carousel-aware): finds the post Media object in the
     *  retained comment UI state by matching its getId() to the post's media id, then reuses the
     *  downloader's extractor. Empty when the Media object can't be found. */
    private static List<String> postMediaUrls(Context ctx, String mediaId) {
        if (mediaId == null || mediaId.isEmpty()) return new ArrayList<>();
        String pk = mediaId.split("_")[0];
        for (Object root : new Object[]{lastFragment.get(), lastState}) {
            if (root == null) continue;
            List<String> urls = walkForMedia(ctx, root, mediaId, pk);
            if (!urls.isEmpty()) return urls;
        }
        return new ArrayList<>();
    }

    /** BFS a graph for the Media whose getId() matches the post id, then extract its URLs. */
    private static List<String> walkForMedia(Context ctx, Object root, String mediaId, String pk) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<>();
        q.add(root);
        Map<Class<?>, Method> idGetters = new java.util.HashMap<>();
        int candidates = 0;
        while (!q.isEmpty() && seen.size() < 12000) {
            Object o = q.poll();
            if (o == null || !seen.add(o)) continue;
            try {
                if (o instanceof List<?> l) { for (Object it : l) q.add(it); continue; }
                if (o instanceof java.util.Map<?, ?> mp) { for (Object v : mp.values()) q.add(v); continue; }
                String n = o.getClass().getName();
                if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                // Is this the post Media? Media objects expose a public getId().
                if (!idGetters.containsKey(o.getClass())) {
                    Method g = null;
                    try { g = o.getClass().getMethod("getId"); if (g.getReturnType() != String.class) g = null; }
                    catch (Throwable ignored) {}
                    idGetters.put(o.getClass(), g);
                }
                Method g = idGetters.get(o.getClass());
                if (g != null) {
                    Object id = g.invoke(o);
                    if (id instanceof String s && !s.isEmpty()) {
                        candidates++;
                        if (s.equals(mediaId) || s.split("_")[0].equals(pk)) {
                            List<String> urls;
                            try { urls = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, o); }
                            catch (Throwable ex) { urls = null; }
                            ModuleLog.line(TAG + "post media candidate " + o.getClass().getName() + " id=" + s + " urls=" + (urls == null ? 0 : urls.size()));
                            if (urls != null && !urls.isEmpty()) return urls;
                        }
                    }
                }
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                        try { f.setAccessible(true); Object v = f.get(o); if (v != null) q.add(v); } catch (Throwable ignored) {}
                    }
            } catch (Throwable ignored) {}
        }
        ModuleLog.line(TAG + "walkForMedia: " + seen.size() + " nodes, " + candidates + " id-bearing, no match for " + mediaId);
        return new ArrayList<>();
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
                // open every visible "View N replies" link first; scroll on once none are left
                int opened = expandReplies(list);
                if (opened > 0) lastChange[0] = System.currentTimeMillis();
                else scrollDown(list);
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

    private static final java.util.regex.Pattern VIEW_REPLIES =
            java.util.regex.Pattern.compile("(?i)^\\s*view\\s+(all\\s+)?\\d[\\d,.]*\\s+(more\\s+)?repl(y|ies)\\s*$");
    private static Method childIdMethod;
    private static boolean a11yLogged;

    /** Clicks the "View N (more) replies" links currently on screen through the list's accessibility
     *  tree (Compose draws them, so there are no Views to click). Returns how many were clicked. */
    @android.annotation.SuppressLint("SoonBlockedPrivateApi") // guarded; falls back when blocked
    private static int expandReplies(View list) {
        try {
            android.view.accessibility.AccessibilityNodeProvider p = list.getAccessibilityNodeProvider();
            if (p == null) { logA11y("no node provider"); return 0; }
            if (childIdMethod == null) {
                childIdMethod = android.view.accessibility.AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
                childIdMethod.setAccessible(true);
            }
            int clicked = 0, nodes = 0;
            java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
            q.add(View.NO_ID);
            Set<Integer> seen = new java.util.HashSet<>();
            while (!q.isEmpty() && nodes < 3000) {
                int id = q.poll();
                if (!seen.add(id)) continue;
                android.view.accessibility.AccessibilityNodeInfo n = p.createAccessibilityNodeInfo(id);
                if (n == null) continue;
                nodes++;
                CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                if (id != View.NO_ID && t != null && VIEW_REPLIES.matcher(t).matches()) {
                    // the link text isn't clickable itself (a click on it is accepted but does nothing):
                    // click the nearest clickable node above it
                    int target = id;
                    android.view.accessibility.AccessibilityNodeInfo cur = n;
                    for (int up = 0; up < 5 && cur != null && !cur.isClickable(); up++) {
                        long parent = (long) parentIdMethod().invoke(cur);
                        target = (int) (parent >> 32);
                        cur = p.createAccessibilityNodeInfo(target);
                    }
                    boolean ok = cur != null && cur.isClickable()
                            && p.performAction(target, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null);
                    logA11y("click '" + t + "' via " + (target == id ? "itself" : "parent " + target) + " -> " + ok);
                    if (ok) clicked++;
                }
                for (int i = 0; i < n.getChildCount(); i++) q.add((int) (((long) childIdMethod.invoke(n, i)) >> 32));
            }
            if (!a11yLogged) { a11yLogged = true; logA11y("nodes=" + nodes + " clicked=" + clicked); }
            if (clicked > 0) ModuleLog.line(TAG + "load all: opened " + clicked + " reply thread(s)");
            return clicked;
        } catch (Throwable t) {
            logA11y(String.valueOf(t.getCause() != null ? t.getCause() : t));
            return 0;
        }
    }

    private static Method parentIdMethod;

    @android.annotation.SuppressLint("SoonBlockedPrivateApi") // guarded; callers fall back when blocked
    private static Method parentIdMethod() throws NoSuchMethodException {
        if (parentIdMethod == null) {
            parentIdMethod = android.view.accessibility.AccessibilityNodeInfo.class.getDeclaredMethod("getParentNodeId");
            parentIdMethod.setAccessible(true);
        }
        return parentIdMethod;
    }

    private static void logA11y(String msg) {
        ModuleLog.line(TAG + "replies: " + msg);
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
