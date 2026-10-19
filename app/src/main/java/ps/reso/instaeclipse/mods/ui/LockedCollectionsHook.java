package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Locked Saved collections (#219): a 🔒 button in a saved collection's action bar locks it;
 * opening a locked collection then asks for the InstaEclipse passcode (or fingerprint, like Lock
 * DMs) every time, and leaving the prompt closes the collection again.
 *
 * A collection screen is a fragment whose arguments carry SaveFragment.ARGUMENT_SAVED_* keys
 * (string constants Instagram keeps); the collection id comes from ARGUMENT_SAVED_COLLECTION_ID
 * or the SavedCollection parcelable. Locked ids are the "lockedCollections" pref.
 */
public class LockedCollectionsHook {
    private static final String TAG = "(IE|LockedColl) ";
    private static final String BTN_TAG = "ie_lock_collection_btn";
    private static final String ARG_PREFIX = "SaveFragment.ARGUMENT_SAVED_";
    private static boolean prompting;
    /** the collection screen on display (its root view) and its id, for re-adding the button */
    private static java.lang.ref.WeakReference<View> active;
    private static String activeId;
    private static final Set<View> watchedBars = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** screen root -> its fragment, hidden while the passcode prompt is up */
    private static final java.util.Map<View, Object> hidden = new java.util.WeakHashMap<>();
    /** fragments unlocked by a prompt (stay unlocked until they are gone, e.g. back from a post) */
    private static final Set<Object> unlockedFrags = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(cl.loadClass("androidx.fragment.app.Fragment"), "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!FeatureFlags.lockSavedCollections) return;
                    Bundle args = arguments(param.thisObject);
                    String id = args != null ? collectionId(args) : null;
                    View root;
                    try { root = (View) param.thisObject.getClass().getMethod("getView").invoke(param.thisObject); } catch (Throwable t) { return; }
                    if (id == null) {
                        // another screen came up (e.g. back to the Saved list): the action bar is
                        // shared, so take our button off once the collection is no longer shown
                        if (root != null) root.postDelayed(() -> removeIfLeft(activityOf(root)), 300);
                        return;
                    }
                    Activity a = activityOf(root);
                    if (a == null) return;
                    // ask once per opening: coming back from a post inside it doesn't ask again
                    if (isLocked(id) && !isUnlocked(param.thisObject)) gate(a, param.thisObject, root);
                    active = new java.lang.ref.WeakReference<>(root);
                    activeId = id;
                    root.postDelayed(() -> addButton(a, id), 400);
                }
            });
            FeatureStatusTracker.setHooked("LockSavedCollections");
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /** True if this fragment, or one it is nested in, was unlocked by a prompt. */
    private static boolean isUnlocked(Object frag) {
        try {
            Field pf = null;
            for (Class<?> c = frag.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                if (c.getName().equals("androidx.fragment.app.Fragment")) { pf = c.getDeclaredField("mParentFragment"); break; }
            if (pf != null) pf.setAccessible(true);
            for (Object f = frag; f != null; f = pf != null ? pf.get(f) : null) if (unlockedFrags.contains(f)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static Bundle arguments(Object frag) {
        for (Class<?> c = frag.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().equals("androidx.fragment.app.Fragment")) continue;
            try {
                Field f = c.getDeclaredField("mArguments");
                f.setAccessible(true);
                return (Bundle) f.get(frag);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** The saved collection this fragment shows, or null when it isn't a collection screen. */
    private static String collectionId(Bundle args) {
        try {
            boolean saved = false;
            for (String k : args.keySet()) if (k.startsWith(ARG_PREFIX)) { saved = true; break; }
            if (!saved) return null;
            Object id = args.get(ARG_PREFIX + "COLLECTION_ID");
            if (id instanceof String s && !s.isEmpty()) return s;
            for (String k : args.keySet()) {
                Object v = args.get(k);
                if (v instanceof Parcelable p && p.getClass().getName().endsWith("SavedCollection")) {
                    for (Class<?> c = p.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                        for (Field f : c.getDeclaredFields()) {
                            if (f.getType() != String.class) continue;
                            f.setAccessible(true);
                            Object s = f.get(p);
                            if (s instanceof String str && !str.isEmpty() && str.matches("[0-9A-Z_]+")) return str;
                        }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "args: " + t);
        }
        return null;
    }

    private static Set<String> locked() {
        Set<String> s = new LinkedHashSet<>();
        for (String id : FeatureFlags.lockedCollections.split(",")) if (!id.trim().isEmpty()) s.add(id.trim());
        return s;
    }

    private static boolean isLocked(String id) {
        return locked().contains(id);
    }

    private static boolean hasPasscode() {
        return FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty();
    }

    /** Removes the lock button when the collection screen it belongs to is no longer shown. */
    @android.annotation.SuppressLint("DiscouragedApi")
    private static void removeIfLeft(Activity a) {
        try {
            if (a == null) return;
            View r = active != null ? active.get() : null;
            if (r != null && r.isShown()) return;
            int barId = a.getResources().getIdentifier("action_bar", "id", a.getPackageName());
            View bar = barId != 0 ? a.findViewById(barId) : null;
            View btn = bar != null ? bar.findViewWithTag(BTN_TAG) : null;
            if (btn != null && btn.getParent() instanceof ViewGroup p) p.removeView(btn);
        } catch (Throwable ignored) {}
    }

    /** 🔒 / 🔓 before the action bar's last button (the collection's ⋮ Options). */
    @android.annotation.SuppressLint("DiscouragedApi")
    private static void addButton(Activity a, String id) {
        try {
            int barId = a.getResources().getIdentifier("action_bar", "id", a.getPackageName());
            View bar = barId != 0 ? a.findViewById(barId) : null;
            if (!(bar instanceof ViewGroup g)) return;
            // IG rebuilds the shared action bar (e.g. when the ⋮ sheet opens), dropping our button:
            // put it back while that collection screen is still the one shown
            if (watchedBars.add(g))
                g.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    View r = active != null ? active.get() : null;
                    if (r == null || !r.isShown()) {
                        View stale = g.findViewWithTag(BTN_TAG);
                        if (stale != null && stale.getParent() instanceof ViewGroup p) p.post(() -> p.removeView(stale));
                        return;
                    }
                    if (!FeatureFlags.lockSavedCollections || activeId == null) return;
                    if (g.findViewWithTag(BTN_TAG) == null) g.post(() -> addButton(a, activeId));
                });
            View old = g.findViewWithTag(BTN_TAG);
            if (old != null && old.getParent() instanceof ViewGroup p) p.removeView(old);
            // the Options button's parent: the deepest group holding the bar's right-most clickable
            ViewGroup host = g;
            View options = null;
            for (int i = g.getChildCount() - 1; i >= 0 && options == null; i--)
                if (g.getChildAt(i).isClickable() && g.getChildAt(i).getVisibility() == View.VISIBLE) options = g.getChildAt(i);
            // While the collection loads (and while leaving it) there is no Options button yet and
            // the right-most clickable is the back arrow + title block; going before it would push
            // them aside. Wait for Options: the bar's layout listener calls this again.
            if (options == null || options.getWidth() == 0 || options.getLeft() < g.getWidth() / 2) return;
            android.widget.ImageView btn = new android.widget.ImageView(a);
            btn.setTag(BTN_TAG);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            showState(a, btn, isLocked(id));
            btn.setContentDescription(I18n.t(a, isLocked(id) ? R.string.ig_locked_coll_unlock : R.string.ig_locked_coll_lock));
            int w = options.getWidth() > 0 ? options.getWidth() : LockDirectMessagesHook.dp(a, 48);
            ViewGroup.LayoutParams lp = options.getLayoutParams();
            ViewGroup.LayoutParams mine;
            try {
                mine = lp.getClass().getConstructor(lp.getClass()).newInstance(lp);
            } catch (Throwable t) {
                mine = new ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            mine.width = w;
            btn.setLayoutParams(mine);
            btn.setOnClickListener(v -> toggle(a, id, btn));
            host.addView(btn, host.indexOfChild(options));
            // keep it just left of Options when Options is right-aligned (e.g. in a FrameLayout)
            final View opt = options;
            final int shift = w;
            btn.post(() -> {
                if (Math.abs(btn.getLeft() - opt.getLeft()) < 4) btn.setTranslationX(-shift);
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "button: " + t);
        }
    }

    /** Locked: solid white lock. Unlocked: dimmed outline lock (Instagram has no unlock icon). */
    @android.annotation.SuppressLint("DiscouragedApi")
    private static void showState(Activity a, android.widget.ImageView btn, boolean locked) {
        int icon = a.getResources().getIdentifier(locked ? "instagram_lock_pano_filled_24" : "instagram_lock_pano_outline_24",
                "drawable", a.getPackageName());
        if (icon != 0) btn.setImageResource(icon);
        btn.setColorFilter(android.graphics.Color.WHITE);
        btn.setAlpha(locked ? 1f : 0.45f);
        btn.setContentDescription(I18n.t(a, locked ? R.string.ig_locked_coll_unlock : R.string.ig_locked_coll_lock));
    }

    private static void toggle(Activity a, String id, android.widget.ImageView btn) {
        if (!hasPasscode()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_locked_coll_need_passcode), Toast.LENGTH_LONG).show();
            return;
        }
        Runnable flip = () -> {
            Set<String> s = locked();
            boolean nowLocked = !s.remove(id);
            if (nowLocked) s.add(id);
            FeatureFlags.lockedCollections = String.join(",", s);
            SettingsManager.saveAllFlags();
            showState(a, btn, nowLocked);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, nowLocked ? R.string.ig_locked_coll_locked : R.string.ig_locked_coll_unlocked),
                    Toast.LENGTH_SHORT).show();
        };
        if (isLocked(id)) ask(a, flip, null); // removing a lock needs the passcode too
        else flip.run();
    }

    /** Covers a locked collection until unlocked; cancelling leaves it. */
    /** The collection screen (and its grid/reels child fragments, which carry the same arguments)
     *  stays hidden behind one passcode prompt; Cancel leaves it. */
    private static void gate(Activity a, Object frag, View root) {
        root.setAlpha(0f);
        hidden.put(root, frag);
        if (prompting) return;
        prompting = true;
        ask(a, () -> {
            prompting = false;
            for (java.util.Map.Entry<View, Object> e : hidden.entrySet()) {
                e.getKey().setAlpha(1f);
                unlockedFrags.add(e.getValue());
            }
            hidden.clear();
        }, () -> {
            prompting = false;
            hidden.clear();
            a.onBackPressed();
        });
    }

    /** Passcode dialog (+ fingerprint when enabled); onOk after success, onCancel otherwise. */
    private static void ask(Activity a, Runnable onOk, Runnable onCancel) {
        final ps.reso.instaeclipse.utils.dialog.IeDialog d = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title("🔒 " + I18n.t(a, R.string.ig_locked_coll_title))
                .cancelable(false);
        final EditText code = d.input(I18n.t(a, R.string.ig_locked_coll_hint));
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setGravity(Gravity.CENTER);
        code.setTextSize(22);
        code.setLetterSpacing(0.4f);
        final boolean[] done = {false};
        Runnable success = () -> {
            if (done[0]) return;
            done[0] = true;
            LockDirectMessagesHook.hideKeyboard(a, code);
            d.dismiss();
            if (onOk != null) onOk.run();
        };
        Runnable cancel = () -> {
            LockDirectMessagesHook.hideKeyboard(a, code);
            if (onCancel != null) onCancel.run();
        };
        d.secondary(I18n.t(a, R.string.ig_dialog_cancel), cancel);
        d.onClose(cancel); // ✕ = Cancel (never leaves the locked content showing)
        d.primaryKeepOpen(I18n.t(a, R.string.ig_locked_coll_open), () -> {
            if (LockDirectMessagesHook.checkPass(code.getText().toString())) success.run();
            else { code.setText(""); code.setError(LockDirectMessagesHook.lockoutSecondsLeft() > 0
                    ? LockDirectMessagesHook.failureText(a) : "✗"); }
        });
        d.show();
        if (FeatureFlags.lockUseFingerprint && LockDirectMessagesHook.canAuthenticate(a))
            LockDirectMessagesHook.promptBiometric(a, success);
    }

    private static Activity activityOf(View v) {
        if (v == null) return null;
        for (android.content.Context k = v.getContext(); k != null; k = k instanceof android.content.ContextWrapper w ? w.getBaseContext() : null)
            if (k instanceof Activity a) return a;
        return null;
    }
}
