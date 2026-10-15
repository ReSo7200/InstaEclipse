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
    private static volatile List<String[]> latest = new ArrayList<>(); // {username, text}

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
                            if (!s.startsWith("http") && (text == null || s.length() > text.length())) text = s;
                        } else if (d < 4 && !f.getType().isPrimitive() && !(v instanceof Number) && !(v instanceof Boolean)) {
                            q.add(new Object[]{v, d + 1});
                        }
                    } catch (Throwable ignored) {}
                }
            if (user != null && text != null && !text.isEmpty() && !text.matches("[0-9_:.-]+")) {
                String u = UserUtils.callUsernameGetter(user);
                if (u != null && !text.equals(u)) out.putIfAbsent(u + "\u0000" + text, new String[]{u, text});
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Compose roots can't hold child views: use the nearest real ViewGroup above them. */
    private static View host(View root) {
        View v = root;
        while (v != null && v.getClass().getName().contains("ComposeView")) {
            v = v.getParent() instanceof View p ? p : null;
        }
        return v != null ? v : root;
    }

    private static void addPill(View root) {
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
        Context ctx = act != null ? act : c;
        List<String[]> all = new ArrayList<>(latest);
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText et = new EditText(ctx);
        et.setHint(I18n.t(ctx, R.string.ig_comment_search_hint, all.size()));
        et.setSingleLine(true);
        box.addView(et);
        ListView lv = new ListView(ctx);
        List<String> shown = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, shown);
        lv.setAdapter(ad);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (420 * d)));
        Runnable refresh = () -> {
            String q = et.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            texts.clear();
            for (String[] r : all) {
                if (q.isEmpty() || r[0].toLowerCase(Locale.ROOT).contains(q) || r[1].toLowerCase(Locale.ROOT).contains(q)) {
                    shown.add("@" + r[0] + ": " + r[1]);
                    texts.add(r[1]);
                }
            }
            ad.notifyDataSetChanged();
        };
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            public void onTextChanged(CharSequence s, int a, int b, int n) {}
            public void afterTextChanged(Editable s) { refresh.run(); }
        });
        lv.setOnItemClickListener((p, v, i, id) -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("comment", texts.get(i)));
            Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied), Toast.LENGTH_SHORT).show();
        });
        refresh.run();
        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(I18n.t(ctx, R.string.ig_comment_search))
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
