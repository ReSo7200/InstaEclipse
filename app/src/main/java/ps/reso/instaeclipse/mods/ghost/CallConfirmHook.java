package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Ask before calling (#277): a tap on a chat's audio or video call button asks first, and the
 * call starts only after "Call".
 *
 * <p>The buttons are the children of the thread header's header_right_buttons. They have no view
 * ids or fixed texts, but each one's click listener holds the button's model, whose type is an enum
 * with readable constant names (AUDIO_CALL, VIDEO_CALL, BLEND_ADD); that is what is matched, so it
 * works in every language. A tap on a call button is held in View.performClick; "Call" replays it,
 * so Instagram's own call code runs unchanged. Each button is looked at once (cached in a tag).
 */
public final class CallConfirmHook {

    private static final String TAG = "(IE|CallConfirm) ";
    private static final int KIND_TAG = 0x7e1e0277;
    private static final String NONE = "";

    private static int headerButtonsId = -1;
    /** The view whose replayed tap must go through. */
    private static View passThrough;

    private CallConfirmHook() {}

    public static void install() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.confirmCalls) return;
                    View v = (View) param.thisObject;
                    if (v == passThrough) { passThrough = null; return; }
                    if (!inHeaderButtons(v)) return;
                    String kind = kind(v);
                    if (kind.equals(NONE)) return;
                    param.setResult(true);
                    ModuleLog.line(TAG + "held " + kind);
                    ask(v, kind.equals("VIDEO_CALL"));
                }
            });
            FeatureStatusTracker.setHooked("ConfirmCalls");
            ModuleLog.line(TAG + "✅ hooked View.performClick");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    @SuppressWarnings("DiscouragedApi")
    private static boolean inHeaderButtons(View v) {
        ViewParent p = v.getParent();
        if (!(p instanceof View pv)) return false;
        if (headerButtonsId == -1) {
            headerButtonsId = v.getResources().getIdentifier("header_right_buttons", "id", v.getContext().getPackageName());
        }
        return headerButtonsId != 0 && pv.getId() == headerButtonsId;
    }

    /** AUDIO_CALL, VIDEO_CALL or NONE, from the enum in the button's click listener (cached). */
    private static String kind(View v) {
        Object cached = v.getTag(KIND_TAG);
        if (cached instanceof String s) return s;
        String found = NONE;
        try {
            Object info = field(View.class, "mListenerInfo", v);
            Object listener = info != null ? field(info.getClass(), "mOnClickListener", info) : null;
            found = findCallEnum(listener, 0);
        } catch (Throwable ignored) {}
        v.setTag(KIND_TAG, found);
        return found;
    }

    private static String findCallEnum(Object o, int depth) throws IllegalAccessException {
        if (o == null || depth > 3 || o instanceof View || o instanceof Context) return NONE;
        if (o instanceof Enum<?> e) {
            String n = e.name();
            return n.equals("AUDIO_CALL") || n.equals("VIDEO_CALL") ? n : NONE;
        }
        String cn = o.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return NONE;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                f.setAccessible(true);
                String r = findCallEnum(f.get(o), depth + 1);
                if (!r.equals(NONE)) return r;
            }
        }
        return NONE;
    }

    private static Object field(Class<?> c, String name, Object o) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void ask(View button, boolean video) {
        // The chat runs in its own activity (ModalActivity): show the dialog on the one that
        // holds the button, not on the main activity behind it.
        Context ctx = button.getContext();
        for (Context c = ctx; c instanceof android.content.ContextWrapper w; c = w.getBaseContext()) {
            if (c instanceof Activity) { ctx = c; break; }
        }
        try {
            IeDialog dlg = new IeDialog(ctx)
                    .title(I18n.t(ctx, video ? R.string.ig_call_confirm_video : R.string.ig_call_confirm_audio));
            dlg.secondary(ctx.getString(android.R.string.cancel), null);
            dlg.primary(I18n.t(ctx, R.string.ig_call_confirm_call), () -> {
                passThrough = button;
                button.performClick();
            });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ dialog: " + t);
        }
    }
}
