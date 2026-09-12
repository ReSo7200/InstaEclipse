package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.UnsentLog;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Injects an "unsent messages" button into the DM thread's top action bar. Tapping it opens a
 * dialog listing the messages the other party unsent in THIS thread (from UnsentLog), so they
 * survive refresh/restart. The thread header is a classic Android View action bar (IGDS), so we
 * add a View to it via a global-layout listener (thread open is a fragment transaction, so no
 * activity onResume fires). Gated on FeatureFlags.keepUnsentMessages.
 *
 * Current-thread id is read from the thread root's tag key (direct_thread_view_layout_tag_key) —
 * this build logs what it finds there to confirm the id source on-device.
 */
public class UnsentThreadButtonHook {

    private static final String TAG = "ie_unsent_btn";
    private static int threadHeaderId, actionBarEndId, tagKeyId, backButtonId, leftContainerId, threadTitleId, threadSubtitleId;

    public void install(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.keepUnsentMessages) return;
                final Activity activity = (Activity) param.thisObject;
                activity.runOnUiThread(() -> registerListener(activity));
            }
        };
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resume);
            } catch (Throwable t) {
                ModuleLog.line("(IE|UnsentBtn) ⚠️ hook " + act + ": " + t.getMessage());
            }
        }
        ModuleLog.line("(IE|UnsentBtn) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0 || backButtonId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId  = r.getIdentifier("direct_thread_header", "id", pkg);
        actionBarEndId  = r.getIdentifier("action_bar_end_action_buttons", "id", pkg);
        tagKeyId        = r.getIdentifier("direct_thread_view_layout_tag_key", "id", pkg);
        backButtonId    = r.getIdentifier("action_bar_button_back", "id", pkg);
        leftContainerId = r.getIdentifier("direct_thread_action_bar_left_aligned_container", "id", pkg);
        threadTitleId   = r.getIdentifier("thread_title", "id", pkg);
        threadSubtitleId = r.getIdentifier("thread_subtitle", "id", pkg);
    }

    /** Best-effort chat name for the export folder — prefer the @username (thread subtitle),
     *  fall back to the display-name title. */
    private String threadTitle(Activity a) {
        // The thread_title/thread_subtitle ids resolve but findViewById returns null here, so scan
        // the known-present header subtree for its TextViews and pick the @username (a handle-like
        // token with no spaces), falling back to the first visible name, else null.
        try {
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (!(header instanceof ViewGroup)) return null;
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t; // handle-like
            return texts.isEmpty() ? null : texts.get(0);
        } catch (Throwable t) { return null; }
    }

    private void collectTexts(ViewGroup vg, java.util.List<String> out) {
        for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) c).getText();
                if (cs != null && cs.toString().trim().length() > 0) out.add(cs.toString().trim());
            } else if (c instanceof ViewGroup) {
                collectTexts((ViewGroup) c, out);
            }
        }
    }

    private String textOf(Activity a, int id) {
        try {
            if (id != 0) {
                View t = a.findViewById(id);
                if (t instanceof android.widget.TextView) {
                    CharSequence cs = ((android.widget.TextView) t).getText();
                    if (cs != null && cs.toString().trim().length() > 0) return cs.toString().trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void registerListener(final Activity activity) {
        try {
            ensureIds(activity);
            if (tryInject(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (tryInject(activity)) {
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentBtn) ⚠️ register: " + t.getMessage());
        }
    }

    /** Returns true once the button is present (so the listener can detach). */
    private boolean tryInject(Activity activity) {
        try {
            // Only inside a DM thread: the thread header must exist.
            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            if (header == null) return false;

            // Preferred placement: right after the back button (left cluster). Fallbacks: the
            // left-aligned container, then the right-side action buttons.
            ViewGroup target;
            int insertAt;
            View back = backButtonId != 0 ? activity.findViewById(backButtonId) : null;
            if (back != null && back.getParent() instanceof ViewGroup) {
                target = (ViewGroup) back.getParent();
                int bi = target.indexOfChild(back);
                // To sit visually to the RIGHT of the back arrow: after it in LTR, before it in RTL
                // (in RTL, a later child renders further left).
                boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                insertAt = rtl ? bi : bi + 1;
            } else if (leftContainerId != 0 && activity.findViewById(leftContainerId) instanceof ViewGroup) {
                target = activity.findViewById(leftContainerId);
                insertAt = 0;
            } else if (actionBarEndId != 0 && activity.findViewById(actionBarEndId) instanceof ViewGroup) {
                target = activity.findViewById(actionBarEndId);
                insertAt = 0;
            } else if (header instanceof ViewGroup) {
                target = (ViewGroup) header;
                insertAt = 0;
            } else return false;

            if (target.findViewWithTag(TAG) != null) return true; // already injected

            android.widget.ImageView btn = new android.widget.ImageView(activity);
            btn.setTag(TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(
                            ps.reso.instaeclipse.R.drawable.ic_delete, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            else { btn.setBackgroundColor(Color.TRANSPARENT); } // fail-safe (no crash if icon missing)
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(activity, 9);
            btn.setPadding(pad, pad, pad, pad);
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription("Unsent messages");
            int sz = dp(activity, 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);

            final View headerRoot = header;
            // Capture the thread id AT INJECTION (thread just opened, before any refresh churn),
            // bound to THIS button — a stable per-thread fallback if the live read fails.
            final String[] bound = { KeepUnsentMessagesHook.currentThreadId };
            btn.setOnClickListener(v -> {
                // PRIMARY: read the actually-open thread from the foreground fragment (immune to
                // background igThreadIgid churn). Fallbacks: id bound at open, then tracked, then tags.
                String threadId = resolveFromActivity(activity);
                if (threadId == null) threadId = bound[0];
                if (threadId == null) threadId = KeepUnsentMessagesHook.currentThreadId;
                if (threadId == null) threadId = resolveThreadId(headerRoot);
                ModuleLog.line("(IE|UnsentBtn) open thread=" + threadId);
                ps.reso.instaeclipse.utils.dialog.DialogUtils.showThreadUnsent(activity, threadId, threadTitle(activity));
            });
            try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
            catch (Throwable t) { target.addView(btn); }
            ModuleLog.line("(IE|UnsentBtn) ✅ injected next to back in " + target.getClass().getSimpleName());
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentBtn) ⚠️ inject: " + t.getMessage());
            return false;
        }
    }


    /**
     * Read the open thread's id from the thread ACTIVITY itself. A DM thread runs in its own
     * ModalActivity, launched with that thread's DirectThreadKey, so the key lives in the
     * activity's intent extras / object graph for the activity's whole lifetime — immune to
     * background igThreadIgid churn and header rebuilds during a refresh. Name-agnostic (matched by
     * class name containing "DirectThreadKey"), so obfuscation doesn't matter.
     */
    private String resolveFromActivity(Activity activity) {
        try {
            // 1. Intent extras (fastest, cleanest — the thread key is usually passed here).
            android.os.Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object dtk = scanForDirectThreadKey(ex.get(k), 0,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 4);
                    if (dtk != null) { String id = firstStringField(dtk); if (id != null) return id; }
                }
            }
            // 2. The activity object graph (its hosted thread fragment holds the key).
            Object dtk = scanForDirectThreadKey(activity, 0,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 6);
            return dtk != null ? firstStringField(dtk) : null;
        } catch (Throwable t) { return null; }
    }

    /** Find a DirectThreadKey instance in obj's field graph (depth-limited, package-pruned). */
    private Object scanForDirectThreadKey(Object obj, int depth, java.util.Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        // Only descend through IG/obfuscated objects and android Bundles; prune framework/JDK.
        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.")
                || obj instanceof android.os.Bundle;
        if (!descendable) return null;
        if (obj instanceof android.os.Bundle) {
            try {
                android.os.Bundle b = (android.os.Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scanForDirectThreadKey(b.get(k), depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scanForDirectThreadKey(v, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** PROBE: walk the header + ancestors, read the thread tag key and any DirectThreadKey, log all. */
    private String resolveThreadId(View header) {
        String found = null;
        View v = header;
        int up = 0;
        while (v != null && up++ < 8) {
            try {
                if (tagKeyId != 0) {
                    Object t = v.getTag(tagKeyId);
                    if (t != null) {
                        ModuleLog.line("(IE|UnsentBtn|PROBE) tagKey on " + v.getClass().getSimpleName()
                                + " = " + t.getClass().getName());
                        String id = threadIdFromAny(t);
                        if (id != null && found == null) found = id;
                    }
                }
                Object plain = v.getTag();
                if (plain != null) {
                    String id = threadIdFromAny(plain);
                    if (id != null && found == null) found = id;
                }
            } catch (Throwable ignored) {}
            v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
        }
        return found;
    }

    /** If obj is (or contains) a DirectThreadKey, return its thread id (first non-empty String field). */
    private String threadIdFromAny(Object obj) {
        if (obj == null) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return firstStringField(obj);
        // shallow: some tags wrap the key
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().getName().contains("DirectThreadKey")) {
                    f.setAccessible(true);
                    Object k = f.get(obj);
                    if (k != null) return firstStringField(k);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String firstStringField(Object o) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(o);
                    if (val instanceof String && !((String) val).isEmpty()) return (String) val;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
