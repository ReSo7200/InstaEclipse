package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * One module button in the DM thread header (next to the back arrow) whose popup lists the
 * thread actions (unsent messages, export, jump to first, hide chat). Each feature registers its
 * entry with {@link #add}; a separate 40dp button per feature pushed the chat name off the header.
 */
final class ThreadHeaderMenu {
    static final String TAG = "ie_thread_menu";
    private static final String[] ORDER = {"unsent", "export", "jump", "read", "hide"};

    /** menu button -> key -> entry; weak so a closed thread's views can go */
    private static final Map<View, Map<String, Entry>> entries = new WeakHashMap<>();

    private record Entry(CharSequence label, Runnable action) {}

    private ThreadHeaderMenu() {}

    /** True if the open thread's menu already lists {@code key}. */
    static boolean has(Activity a, String key) {
        View btn = a.getWindow().getDecorView().findViewWithTag(TAG);
        Map<String, Entry> m = btn != null ? entries.get(btn) : null;
        return m != null && m.containsKey(key);
    }

    /** Adds (or replaces) an entry, creating the menu button if needed. False if the header isn't there. */
    static boolean add(Activity a, String key, CharSequence label, Runnable action) {
        try {
            View btn = a.getWindow().getDecorView().findViewWithTag(TAG);
            if (btn == null) btn = createButton(a);
            if (btn == null) return false;
            Map<String, Entry> m = entries.get(btn);
            if (m == null) entries.put(btn, m = new LinkedHashMap<>());
            m.put(key, new Entry(label, action));
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|ThreadMenu) ⚠️ add " + key + ": " + t);
            return false;
        }
    }

    @SuppressLint("DiscouragedApi")
    private static View createButton(Activity a) {
        String pkg = a.getPackageName();
        int headerId = a.getResources().getIdentifier("direct_thread_header", "id", pkg);
        int backId = a.getResources().getIdentifier("action_bar_button_back", "id", pkg);
        View header = headerId != 0 ? a.findViewById(headerId) : null;
        if (header == null) return null;
        ViewGroup target;
        int insertAt;
        View back = backId != 0 ? a.findViewById(backId) : null;
        if (back != null && back.getParent() instanceof ViewGroup g) {
            target = g;
            int bi = g.indexOfChild(back);
            // visually to the right of the back arrow: after it in LTR, before it in RTL
            insertAt = g.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? bi : bi + 1;
        } else if (header instanceof ViewGroup g) {
            target = g;
            insertAt = 0;
        } else return null;

        android.widget.ImageView btn = new android.widget.ImageView(a);
        btn.setTag(TAG);
        android.graphics.drawable.Drawable icon =
                ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_tune, Color.WHITE);
        if (icon != null) btn.setImageDrawable(icon);
        btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (9 * d);
        btn.setPadding(pad, pad, pad, pad);
        btn.setContentDescription(I18n.t(a, R.string.ig_thread_menu));
        int sz = (int) (40 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.gravity = Gravity.CENTER_VERTICAL;
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> show(v));
        try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
        catch (Throwable t) { target.addView(btn); }
        ModuleLog.line("(IE|ThreadMenu) ✅ button injected in " + target.getClass().getSimpleName());
        return btn;
    }

    private static void show(View btn) {
        Map<String, Entry> m = entries.get(btn);
        if (m == null || m.isEmpty()) return;
        java.util.List<Entry> list = new java.util.ArrayList<>();
        for (String k : ORDER) if (m.containsKey(k)) list.add(m.get(k));
        for (Map.Entry<String, Entry> e : m.entrySet())
            if (!java.util.Arrays.asList(ORDER).contains(e.getKey())) list.add(e.getValue());
        CharSequence[] labels = new CharSequence[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).label();
        new IeDialog(btn.getContext())
                .title(I18n.t(btn.getContext(), R.string.ig_thread_menu))
                .choices(labels, -1, which -> {
                    try { list.get(which).action().run(); }
                    catch (Throwable t) { ModuleLog.line("(IE|ThreadMenu) ⚠️ action: " + t); }
                })
                .show();
    }
}
