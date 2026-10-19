package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentConversation;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.ghost.HeaderGestureBinding;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** One owner for optional shortcuts and the non-destructive title gesture. */
public final class ConversationMenuHook {
    private static final String HIDE = "ie_hidechat_btn", UNSENT = "ie_unsent_btn";
    private static final Map<Activity, Registration> registrations = new WeakHashMap<>();
    private static final Map<View, WeakReference<Registration>> titles = new WeakHashMap<>();
    private static boolean installed;
    private static WeakReference<Dialog> openDialog = new WeakReference<>(null);

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        // Hook the actual superclass callback; inherited methods and renamed Activities work too.
        XposedBridge.hookAllMethods(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Activity a = (Activity) p.thisObject;
                if (!"com.instagram.android".equals(a.getPackageName())) return;
                register(a);
            }
        });
        XC_MethodHook release = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Dialog dialog = openDialog.get();
                if (dialog != null && dialog.getOwnerActivity() == p.thisObject) dialog.dismiss();
                Registration r = registrations.remove((Activity) p.thisObject);
                if (r != null) r.release();
            }
        };
        XposedBridge.hookAllMethods(Activity.class, "onPause", release);
        XposedBridge.hookAllMethods(Activity.class, "onDestroy", release);
        // No replacement native click/long-click listener is ever installed.
        XposedBridge.hookAllMethods(View.class, "performLongClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                WeakReference<Registration> ref = titles.get(v);
                Registration registration = ref == null ? null : ref.get();
                if (registration == null || !registration.gesture.accepts(v)) return;
                Activity a = registration.activity.get();
                if (a == null) return;
                show(a);
                p.setResult(true);
            }
        });
    }

    public static void refreshAll() {
        for (Registration r : new ArrayList<>(registrations.values())) {
            Activity a = r.activity.get();
            if (a != null) a.runOnUiThread(r::scan);
        }
    }

    private static void register(Activity a) {
        Registration old = registrations.remove(a);
        if (old != null) old.release();
        if (a.isFinishing() || a.isDestroyed() || a.getWindow() == null) return;
        Registration r = new Registration(a);
        registrations.put(a, r);
        a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(r);
        r.scan();
    }

    private static boolean hasNativeLongClick(View v) {
        try {
            Object info = XposedHelpers.getObjectField(v, "mListenerInfo");
            return info != null && XposedHelpers.getObjectField(info, "mOnLongClickListener") != null;
        } catch (Throwable ignored) { return true; } // Uninspectable listener: preserve native behavior.
    }

    private static View find(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    private static View findInHeader(Activity a, View header, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : header.findViewById(id);
    }

    private static boolean inside(View child, View ancestor) {
        for (View v = child; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null)
            if (v == ancestor) return true;
        return false;
    }

    private static final class Registration implements ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<Activity> activity;
        final WeakReference<View> decor;
        final HeaderGestureBinding<View> gesture = new HeaderGestureBinding<>(
                new HeaderGestureBinding.Access<View>() {
                    @Override public View parent(View view) {
                        return view.getParent() instanceof View ? (View) view.getParent() : null;
                    }
                    @Override public boolean clickable(View view) { return view.isClickable(); }
                    @Override public boolean longClickable(View view) { return view.isLongClickable(); }
                    @Override public boolean nativeLongClick(View view) { return hasNativeLongClick(view); }
                    @Override public boolean usable(View view) {
                        return view.isAttachedToWindow() && view.isShown();
                    }
                    @Override public void setLongClickable(View view, boolean value) { view.setLongClickable(value); }
                }, new HeaderGestureBinding.Observer<View>() {
                    @Override public void acquired(View view) { titles.put(view, new WeakReference<>(Registration.this)); }
                    @Override public void released(View view) { titles.remove(view); }
                });
        Registration(Activity a) {
            activity = new WeakReference<>(a);
            decor = new WeakReference<>(a.getWindow().getDecorView());
        }
        @Override public void onGlobalLayout() { scan(); }
        void scan() {
            Activity a = activity.get();
            View root = decor.get();
            if (a == null || root == null || a.isDestroyed() || a.isFinishing()) { release(); return; }
            View header = find(a, "direct_thread_header");
            if (header == null || !header.isShown()) {
                releaseTitleReference();
                remove(root, HIDE); remove(root, UNSENT);
                return;
            }
            View next = findInHeader(a, header, "header_title");
            if (next == null) next = findInHeader(a, header, "thread_title");
            if (next == null) next = findInHeader(a, header, "action_bar_title");
            // The helper claims the existing native tap target without replacing any listener.
            gesture.update(next, header, FeatureFlags.hideSpecificChats || FeatureFlags.keepUnsentMessages);
            shortcut(a, root, header, HIDE,
                    FeatureFlags.hideSpecificChats && FeatureFlags.showHideChatButton,
                    R.drawable.ic_eye_off, R.string.ie_hide_this_chat);
            shortcut(a, root, header, UNSENT,
                    FeatureFlags.keepUnsentMessages && FeatureFlags.showDeletedMessagesButton,
                    R.drawable.ic_delete, R.string.ie_view_deleted_for_chat);
        }
        void releaseTitleReference() { gesture.release(); }
        void release() {
            releaseTitleReference();
            View root = decor.get();
            if (root != null) {
                ViewTreeObserver observer = root.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                remove(root, HIDE); remove(root, UNSENT);
            }
        }
    }

    private static void remove(View root, String tag) {
        View existing;
        while ((existing = root.findViewWithTag(tag)) != null) {
            if (!(existing.getParent() instanceof ViewGroup)) return;
            existing.setOnClickListener(null);
            ((ViewGroup) existing.getParent()).removeView(existing);
        }
    }

    private static void shortcut(Activity a, View root, View header, String tag,
                                 boolean show, int iconRes, int labelRes) {
        if (!show) { remove(root, tag); return; }
        if (root.findViewWithTag(tag) != null) return;
        View targetView = findInHeader(a, header, "header_right_buttons");
        if (targetView == null) targetView = findInHeader(a, header, "action_bar_end_action_buttons");
        if (!(targetView instanceof LinearLayout)) {
            View back = findInHeader(a, header, "header_left_button");
            if (back == null) back = findInHeader(a, header, "action_bar_button_back");
            targetView = back != null && back.getParent() instanceof LinearLayout
                    ? (View) back.getParent() : null;
        }
        if (!(targetView instanceof LinearLayout) || !targetView.isShown() || !inside(targetView, header)) return;
        LinearLayout target = (LinearLayout) targetView;
        int color = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES ? Color.WHITE : Color.BLACK;
        android.graphics.drawable.Drawable icon = DialogUtils.moduleIcon(iconRes, color);
        if (icon == null) return; // Never create an invisible focus/click target.
        ImageView button = new ImageView(a);
        button.setTag(tag);
        button.setImageDrawable(icon);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int size = Math.round(40 * a.getResources().getDisplayMetrics().density);
        int pad = Math.round(9 * a.getResources().getDisplayMetrics().density);
        button.setPadding(pad, pad, pad, pad);
        button.setContentDescription(I18n.t(a, labelRes));
        button.setFocusable(true);
        button.setOnClickListener(v -> {
            if (UNSENT.equals(tag)) showDeleted(a); else show(a);
        });
        target.addView(button, new LinearLayout.LayoutParams(size, size));
    }

    public static void show(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed() || (openDialog.get() != null && openDialog.get().isShowing())) return;
        CurrentConversation.Snapshot snapshot = CurrentConversation.resolve(a);
        if (snapshot == null) { message(a, R.string.ig_hide_chat_no_thread); return; }
        boolean hidden = HiddenThreads.isHidden(snapshot.accountId, snapshot.threadId);
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (FeatureFlags.hideSpecificChats || hidden) {
            labels.add(I18n.t(a, hidden ? R.string.ie_unhide_this_chat : R.string.ie_hide_this_chat));
            actions.add(() -> {
                if (!CurrentConversation.revalidate(a, snapshot)) { message(a, R.string.ie_chat_changed); return; }
                if (!hidden && !FeatureFlags.hideSpecificChats) { message(a, R.string.ie_chat_changed); return; }
                View title = find(a, "header_title");
                if (title == null) title = find(a, "thread_title");
                String label = title instanceof TextView ? ((TextView) title).getText().toString() : "";
                ps.reso.instaeclipse.utils.ghost.HiddenThreadStore.Result result =
                        HiddenThreads.setHidden(snapshot.accountId, snapshot.threadId, label, !hidden);
                if (!result.success) { message(a, R.string.ie_hidden_save_failed); return; }
                HideChatsHook.refreshAccount(snapshot.accountId);
                message(a, hidden ? R.string.ig_hide_chat_unhidden : R.string.ig_hide_chat_hidden);
                if (!hidden) a.onBackPressed();
            });
        }
        if (FeatureFlags.keepUnsentMessages) {
            labels.add(I18n.t(a, R.string.ie_view_deleted_for_chat));
            actions.add(() -> {
                if (!CurrentConversation.revalidate(a, snapshot) || !FeatureFlags.keepUnsentMessages) {
                    message(a, R.string.ie_chat_changed); return;
                }
                DialogUtils.showThreadUnsent(a, snapshot.threadId);
            });
        }
        if (labels.isEmpty()) { message(a, R.string.ie_chat_features_disabled); return; }

        try {
            IeDialog menu = new IeDialog(a).title(I18n.t(a, R.string.ie_chat_actions))
                    .choices(labels.toArray(new String[0]), -1, which -> actions.get(which).run())
                    .onDismiss(() -> openDialog.clear());
            menu.secondary(a.getString(android.R.string.cancel), null);
            Dialog dialog = menu.show();
            dialog.setOwnerActivity(a);
            openDialog = new WeakReference<>(dialog);
        }
        catch (RuntimeException failure) { openDialog.clear(); }
    }

    private static void showDeleted(Activity a) {
        CurrentConversation.Snapshot snapshot = CurrentConversation.resolve(a);
        if (!FeatureFlags.keepUnsentMessages || snapshot == null
                || !CurrentConversation.revalidate(a, snapshot)) {
            message(a, R.string.ig_hide_chat_no_thread); return;
        }
        DialogUtils.showThreadUnsent(a, snapshot.threadId);
    }

    private static void message(Activity a, int resource) {
        if (a != null) Toast.makeText(a, I18n.t(a, resource), Toast.LENGTH_LONG).show();
    }
}
