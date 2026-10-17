package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide Specific Chats: let the user hide chosen DM threads from the Direct inbox.
 *
 * Two parts:
 *  1. Inbox filter — hooks the DirectThreadStore's inbox thread-summary builder (anchored by the
 *     stable string "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries") and removes rows whose
 *     thread id is in the persistent hidden set (HiddenThreads). Each row exposes a
 *     com.instagram.model.direct.DirectThreadKey; its first String field is the thread id (same
 *     resolution KeepUnsentMessagesHook/UnsentThreadButtonHook already use).
 *  2. Hide toggle — injects an eye-off button into the OPEN thread's header (same global-layout
 *     pattern as UnsentThreadButtonHook); tapping it hides/unhides the current thread.
 *
 * Gated on FeatureFlags.hideSpecificChats.
 */
public class HideChatsHook {

    private static final String TAG = "ie_hidechat_btn";
    private static final java.util.Set<View> pendingDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static int threadHeaderId, backButtonId, headerTitleId;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installInboxFilter(bridge, classLoader);
        installHeaderButton(classLoader);
    }

    // ── 1. Inbox thread-list filter ────────────────────────────────────────────
    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hideSpecificChats || HiddenThreads.isEmpty()) return;
                Object r = param.getResult();
                if (!(r instanceof java.util.List<?> list) || list.isEmpty()) return;
                try {
                    java.util.Iterator<?> it = ((java.util.List<?>) r).iterator();
                    while (it.hasNext()) {
                        String id = threadIdOfRow(it.next());
                        if (id != null && HiddenThreads.isHidden(id)) it.remove();
                    }
                } catch (Throwable ignored) {}
            }
        };
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), filter); n++; }
                catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
        }
    }

    /** Finds the DirectThreadKey on an inbox row object and returns its thread id (first String). */
    private static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().contains("DirectThreadKey")) continue;
                    f.setAccessible(true);
                    Object key = f.get(row);
                    String id = firstStringField(key);
                    if (id != null) return id;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 2. Hide/unhide button in the thread header ─────────────────────────────
    private void installHeaderButton(ClassLoader classLoader) {
        // IG 448+ no longer declares onResume on InstagramMainActivity (inherited from
        // IgFragmentActivity), so a plain findAndHookMethod throws; LifecycleHookUtil hooks the
        // declaring class and filters by instance (works on old builds too).
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                LifecycleHookUtil.hookAfter(classLoader, act, "onResume", (a, param) -> {
                    if (!FeatureFlags.hideSpecificChats) return;
                    a.runOnUiThread(() -> registerListener(a));
                });
            } catch (Throwable t) { ModuleLog.line("(IE|HideChats) ⚠️ hook " + act + ": " + t); }
        }
        // A thread opened inside InstagramMainActivity is a fragment transaction (no activity
        // onResume) — inject when the thread fragment itself resumes.
        CurrentThreadTracker.install(classLoader);
        CurrentThreadTracker.addListener((a, threadId) -> {
            if (FeatureFlags.hideSpecificChats) registerListener(a);
        });
        ModuleLog.line("(IE|HideChats) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        backButtonId   = r.getIdentifier("action_bar_button_back", "id", pkg);
        headerTitleId  = r.getIdentifier("header_title", "id", pkg);
    }

    private void registerListener(final Activity activity) {
        try {
            ensureIds(activity);
            if (tryInject(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            // One pending listener per window: registerListener runs on every activity resume and
            // every thread-fragment resume, and listeners used to pile up on the main activity.
            if (!pendingDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (tryInject(activity)) {
                        pendingDecors.remove(decor);
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean tryInject(Activity activity) {
        try {
            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            if (header == null) return false;

            View oldBtn = header.getRootView().findViewWithTag(TAG); // pre-menu builds' eye button
            if (oldBtn != null && oldBtn.getParent() instanceof ViewGroup p) p.removeView(oldBtn);
            if (ThreadHeaderMenu.has(activity, "hide")) return true;
            return addEntry(activity);
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inject: " + t.getMessage());
            return false;
        }
    }

    /** "Hide chat" or "Unhide chat", matching this chat's current state; re-added after a toggle. */
    private boolean addEntry(Activity activity) {
        String threadId = resolveThreadId(activity);
        boolean hidden = threadId != null && HiddenThreads.isHidden(threadId);
        return ThreadHeaderMenu.add(activity, "hide",
                I18n(activity, hidden ? R.string.ig_unhide_chat : R.string.ig_hide_chat),
                () -> { toggleCurrent(activity); addEntry(activity); });
    }

    private void toggleCurrent(Activity activity) {
        String threadId = resolveThreadId(activity);
        if (threadId == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity,
                I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                Toast.LENGTH_SHORT).show();
        ModuleLog.line("(IE|HideChats) toggled thread=" + threadId + " hidden=" + nowHidden);
    }

    private static String I18n(Activity a, int res) {
        return ps.reso.instaeclipse.utils.i18n.I18n.t(a, res);
    }

    /**
     * Best-effort title of the open thread (username, for the unhide-manager label). The
     * thread_title id resolves but findViewById returns null here, so scan the header subtree for
     * its TextViews and pick the @username (a handle-like token), falling back to the first name.
     */
    private String threadTitle(Activity a) {
        try {
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (!(header instanceof ViewGroup)) return "";
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t; // handle-like
            return texts.isEmpty() ? "" : texts.get(0);
        } catch (Throwable t) { return ""; }
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

    // ── thread-id resolution (mirrors UnsentThreadButtonHook.resolveFromActivity — proven) ──
    /** The open DM thread's id for {@code activity} (shared with ReadReceiptChatHook). */
    static String resolveThreadId(Activity activity) {
        try {
            // 0. The resumed thread fragment's DirectThreadKey (works for threads hosted in
            //    InstagramMainActivity as well as ModalActivity — issue #254).
            String tracked = CurrentThreadTracker.currentThreadId();
            if (tracked != null) return tracked;
            // 1. Intent extras (thread key usually passed here); depth 4.
            android.os.Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object dtk = scan(ex.get(k), 0,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 4);
                    if (dtk != null) { String id = firstStringField(dtk); if (id != null) return id; }
                }
            }
            // 2. Activity object graph (hosted thread fragment holds the key); depth 6.
            Object dtk = scan(activity, 0,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 6);
            return dtk != null ? firstStringField(dtk) : null;
        } catch (Throwable t) { return null; }
    }

    /** Find a DirectThreadKey in obj's field graph (depth-limited; descends IG/obfuscated + Bundles). */
    private static Object scan(Object obj, int depth, java.util.Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.")
                || obj instanceof android.os.Bundle;
        if (!descendable) return null;
        if (obj instanceof android.os.Bundle) {
            try {
                android.os.Bundle b = (android.os.Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scan(b.get(k), depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scan(v, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstStringField(Object o) {
        if (o == null) return null;
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String s && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
