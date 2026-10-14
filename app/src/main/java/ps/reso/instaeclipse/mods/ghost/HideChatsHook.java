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
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
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
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static int threadHeaderId, backButtonId, tagKeyId;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installInboxFilter(bridge, classLoader);
        installHeaderButton(classLoader);
    }

    // ── 1. Inbox thread-list filter ────────────────────────────────────────────
    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
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
                    String id = ps.reso.instaeclipse.utils.ghost.ThreadIdResolver.threadIdOf(key);
                    if (id != null) return id;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 2. Hide/unhide button in the thread header ─────────────────────────────
    private void installHeaderButton(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats) return;
                // Guard in case a superclass was hooked that triggers for a different activity
                if (!(param.thisObject instanceof Activity)) return;
                final Activity a = (Activity) param.thisObject;
                String name = a.getClass().getName();
                if (!name.contains("ModalActivity") && !name.contains("InstagramMainActivity")) return;

                a.runOnUiThread(() -> registerListener(a));
            }
        };

        ps.reso.instaeclipse.utils.core.ActivityHookHelper.hookOnResume(
                classLoader,
                "IE|HideChats",
                resume,
                "com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"
        );

        ModuleLog.line("(IE|HideChats) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        backButtonId   = r.getIdentifier("action_bar_button_back", "id", pkg);
        tagKeyId       = r.getIdentifier("direct_thread_view_layout_tag_key", "id", pkg);
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
        } catch (Throwable ignored) {}
    }

    private boolean tryInject(Activity activity) {
        try {
            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            if (header == null) return false;

            ViewGroup target;
            int insertAt;
            View back = backButtonId != 0 ? activity.findViewById(backButtonId) : null;
            if (back != null && back.getParent() instanceof ViewGroup) {
                target = (ViewGroup) back.getParent();
                int bi = target.indexOfChild(back);
                boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                insertAt = rtl ? bi : bi + 1;
            } else if (header instanceof ViewGroup) {
                target = (ViewGroup) header;
                insertAt = 0;
            } else return false;

            if (target.findViewWithTag(TAG) != null) return true;

            android.widget.ImageView btn = new android.widget.ImageView(activity);
            btn.setTag(TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(activity, 9);
            btn.setPadding(pad, pad, pad, pad);
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription("Hide chat");
            int sz = dp(activity, 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);

            final View headerRoot = header;
            final String[] bound = { KeepUnsentMessagesHook.currentThreadId };

            btn.setOnClickListener(v -> {
                String threadId = ps.reso.instaeclipse.utils.ghost.ThreadIdResolver.resolveThreadId(
                        "IE|HideChats",
                        activity,
                        bound[0],
                        KeepUnsentMessagesHook.currentThreadId,
                        headerRoot,
                        tagKeyId
                );

                if (threadId == null) {
                    Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
                Toast.makeText(activity,
                        I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                        Toast.LENGTH_SHORT).show();
                ModuleLog.line("(IE|HideChats) toggled thread=" + ps.reso.instaeclipse.utils.ghost.ThreadIdResolver.maskId(threadId) + " hidden=" + nowHidden);
            });
            try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
            catch (Throwable t) { target.addView(btn); }
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inject: " + t.getMessage());
            return false;
        }
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

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
