package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Ask before following. Instagram's follow manager (the class holding "follow_unknown" and
 * "last_follow_status") handles every Follow button: it first marks the user as followed, which
 * flips the button, then hands the action to one static sender (manager, User, action enum, ...)
 * that queues the request and sends it. A follow (action "create") is held at that sender behind a
 * dialog: Follow sends it unchanged, Cancel puts the user's follow status back with the manager's
 * own setter, so the button returns to "Follow" and nothing was sent. Unfollows pass untouched.
 *
 * <p>Profile headers use the newer follow data source instead (FollowUserDataSourceImpl, a Kotlin
 * suspend function taking the same action enum). A follow there is ended with a
 * CancellationException before it does anything (Kotlin's quiet "cancelled"), and on Follow the
 * call is run again with a stand-in continuation; Cancel asks the manager to reset the profile's
 * status, as Instagram does when a follow fails.
 */
public class FollowConfirmHook {

    private static final String TAG = "(InstaEclipse | FollowConfirm): ";
    private static final String USER = "com.instagram.user.model.User";
    private static final String SENDER_KEY = "FollowManagerSender";
    private static final String SETTER_KEY = "FollowManagerSetStatus";
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The manager's (FollowStatus, User, boolean) setter, used to undo the flip on Cancel. */
    private static Method statusSetter;
    /** The manager's reset (profile model -> re-read status), found as its 1-arg method calling the setter. */
    private static Method resetMethod;
    /** User's current follow status (FollowStatus enum), read by the manager's setter. */
    private static Method statusGetter;
    private static int profileButtonId;
    private static boolean replayingClick;
    private static final String DATA_SOURCE = "com.instagram.user.follow.mvvm.data.FollowUserDataSourceImpl";

    public void install(DexKitBridge bridge) {
        Method sender = null;
        if (DexKitCache.isCacheValid()) {
            sender = DexKitCache.loadMethod(SENDER_KEY, Module.hostClassLoader);
            statusSetter = DexKitCache.loadMethod(SETTER_KEY, Module.hostClassLoader);
        }
        if (sender == null) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create().usingStrings("follow_unknown", "last_follow_status"))
                        .returnType("void")))) {
                    if (!md.isMethod()) continue;
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    Class<?>[] p = m.getParameterTypes();
                    if (Modifier.isStatic(m.getModifiers())) {
                        if (sender == null && has(p, m.getDeclaringClass()) && hasUser(p) && hasEnum(p)) sender = m;
                    } else if (statusSetter == null && p.length == 3 && p[0].isEnum()
                            && p[1].getName().equals(USER) && p[2] == boolean.class) {
                        statusSetter = m;
                    }
                }
                if (sender == null) {
                    ModuleLog.line(TAG + "❌ follow sender not found");
                    return;
                }
                DexKitCache.saveMethod(SENDER_KEY, sender);
                if (statusSetter != null) DexKitCache.saveMethod(SETTER_KEY, statusSetter);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ " + t);
                return;
            }
        }
        XposedBridge.hookMethod(sender, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.confirmFollows || REPLAYING.get() == Boolean.TRUE || !isFollow(param.args)) return;
                Activity act = UIHookManager.getCurrentActivity();
                if (act == null) return;
                param.setResult(null);
                Member method = param.method;
                Object[] args = param.args.clone();
                MAIN.post(() -> confirm(act, username(args), () -> send(method, args), () -> undo(method, args)));
            }
        });
        if (statusSetter != null) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(sender.getDeclaringClass().getName()).paramCount(1).returnType("void")
                        .addInvoke(MethodMatcher.create().declaredClass(sender.getDeclaringClass().getName())
                                .name(statusSetter.getName()).paramCount(3))))) {
                    if (!md.isMethod()) continue;
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes()[0].getName().equals(USER)) continue;
                    resetMethod = m;
                    break;
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ reset lookup: " + t);
            }
        }
        hookDataSource(sender.getDeclaringClass());
        if (statusSetter != null) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(statusSetter.getDeclaringClass().getName()).name(statusSetter.getName()).paramCount(3)))) {
                    for (MethodData c : md.getInvokes()) {
                        if (!c.isMethod() || !c.getClassName().equals(USER) || c.getParamCount() != 0) continue;
                        Method m = c.getMethodInstance(Module.hostClassLoader);
                        if (m.getReturnType() == statusSetter.getParameterTypes()[0]) { statusGetter = m; break; }
                    }
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ status getter: " + t);
            }
        }
        hookProfileButton();
        ModuleLog.line(TAG + "✅ Hooked " + sender.getDeclaringClass().getName() + "." + sender.getName()
                + (statusSetter != null ? ", undo " + statusSetter.getName() : ", no undo")
                + (resetMethod != null ? ", reset " + resetMethod.getName() : ", no reset")
                + (statusGetter != null ? ", status " + statusGetter.getName() : ", no status"));
        FeatureStatusTracker.setHooked("ConfirmFollows");
    }

    /**
     * The profile header's Follow button goes through its own handler (the profile fragment), so it
     * is held at the tap: while you don't follow the profile yet (read from its User model), the tap
     * waits for the dialog and is replayed on Follow. Taps on "Following" / "Requested" pass.
     */
    private static void hookProfileButton() {
        XposedBridge.hookAllMethods(android.view.View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.confirmFollows || replayingClick) return;
                android.view.View v = (android.view.View) param.thisObject;
                if (profileButtonId == 0) {
                    if (v.getId() == android.view.View.NO_ID) return;
                    profileButtonId = v.getResources().getIdentifier("profile_header_follow_button", "id", v.getContext().getPackageName());
                    if (profileButtonId == 0) profileButtonId = -1;
                }
                if (v.getId() != profileButtonId) return;
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                Activity act = activityOf(v.getContext());
                String username = ps.reso.instaeclipse.mods.ui.ProfileMenuHook.profileUsername(act);
                if (act == null || alreadyFollowing(act, username)) return;
                param.setResult(true);
                MAIN.post(() -> confirm(act, username, () -> {
                    replayingClick = true;
                    REPLAYING.set(Boolean.TRUE);
                    try { v.performClick(); } finally { replayingClick = false; REPLAYING.remove(); }
                    ModuleLog.line(TAG + "profile follow sent");
                }, () -> ModuleLog.line(TAG + "profile follow cancelled")));
            }
        });
    }

    private static Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper w) {
            if (c instanceof Activity a) return a;
            c = w.getBaseContext();
        }
        return UIHookManager.getCurrentActivity();
    }

    /** True only when the profile's User says you follow it or asked to; unknown counts as not. */
    private static boolean alreadyFollowing(Activity act, String username) {
        Method get = statusGetter;
        if (get == null || username == null) return false;
        try {
            Object user = ps.reso.instaeclipse.mods.ui.ProfileMenuHook.findUser(act, username);
            Object status = user != null ? get.invoke(user) : null;
            if (!(status instanceof Enum<?> e)) return false;
            String name = e.name().toUpperCase(java.util.Locale.ROOT);
            return name.contains("FOLLOWING") && !name.contains("NOTFOLLOWING") || name.contains("REQUESTED");
        } catch (Throwable t) {
            return false;
        }
    }

    /** The profile header's follow: FollowUserDataSourceImpl's suspend method taking the action enum. */
    private static void hookDataSource(Class<?> managerClass) {
        try {
            Class<?> ds = Module.hostClassLoader.loadClass(DATA_SOURCE);
            for (Method m : SafeReflect.methods(ds)) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != Object.class || p.length < 5) continue;
                if (continuationIndex(p) < 0 || !hasEnum(p)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!FeatureFlags.confirmFollows || REPLAYING.get() == Boolean.TRUE || !isFollow(param.args)) return;
                        Activity act = UIHookManager.getCurrentActivity();
                        if (act == null) return;
                        Method method = (Method) param.method;
                        Object self = param.thisObject;
                        Object[] args = param.args.clone();
                        param.setThrowable(new java.util.concurrent.CancellationException("follow held by InstaEclipse"));
                        MAIN.post(() -> confirm(act, ps.reso.instaeclipse.mods.ui.ProfileMenuHook.profileUsername(act), () -> resend(method, self, args),
                                () -> reset(self, args, managerClass)));
                    }
                });
                ModuleLog.line(TAG + "✅ profile follow: " + m.getName());
                return;
            }
            ModuleLog.line(TAG + "⚠️ profile follow method not found");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ profile follow: " + t);
        }
    }

    /** The continuation: the one interface parameter that isn't a Kotlin function. */
    private static int continuationIndex(Class<?>[] p) {
        int found = -1;
        for (int i = 0; i < p.length; i++) {
            if (!p[i].isInterface() || p[i].getName().startsWith("kotlin.jvm.functions.")) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    /** Runs the held call again; its result goes to a stand-in continuation that borrows the
     *  original's coroutine context. */
    private static void resend(Method method, Object self, Object[] args) {
        int ci = continuationIndex(method.getParameterTypes());
        Object original = args[ci];
        Class<?> type = method.getParameterTypes()[ci];
        Object standIn = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "equals": return proxy == a[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return "InstaEclipse follow";
                }
            }
            if (m.getParameterCount() == 0) return m.invoke(original); // the coroutine context
            return null; // resumeWith: the follow finished
        });
        Object[] again = args.clone();
        again[ci] = standIn;
        try {
            REPLAYING.set(Boolean.TRUE);
            XposedBridge.invokeOriginalMethod(method, self, again);
            ModuleLog.line(TAG + "profile follow sent");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ profile follow: " + t);
        } finally {
            REPLAYING.remove();
        }
    }

    /** Cancel: the manager re-reads the profile's real status (its reset after a failed follow). */
    private static void reset(Object dataSource, Object[] args, Class<?> managerClass) {
        ModuleLog.line(TAG + "profile follow cancelled");
        Method r = resetMethod;
        if (r == null) return;
        try {
            Object manager = null;
            for (Field f : SafeReflect.fields(dataSource.getClass())) {
                if (f.getType() != managerClass) continue;
                f.setAccessible(true);
                manager = f.get(dataSource);
            }
            if (manager == null) return;
            Class<?> modelType = r.getParameterTypes()[0];
            for (Object a : args) {
                if (a == null || !a.getClass().getName().startsWith("X.")) continue;
                for (Method get : a.getClass().getMethods()) {
                    if (get.getParameterCount() != 0 || get.getReturnType() != modelType) continue;
                    Object model = get.invoke(a);
                    if (model == null) continue;
                    r.setAccessible(true);
                    r.invoke(manager, model);
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ reset: " + t);
        }
    }

    private static boolean has(Class<?>[] types, Class<?> c) {
        for (Class<?> t : types) if (t == c) return true;
        return false;
    }

    private static boolean hasUser(Class<?>[] types) {
        for (Class<?> t : types) if (t.getName().equals(USER)) return true;
        return false;
    }

    private static boolean hasEnum(Class<?>[] types) {
        for (Class<?> t : types) if (t.isEnum()) return true;
        return false;
    }

    /** The action enum carries Instagram's path segment: "create" for a follow. */
    private static boolean isFollow(Object[] args) {
        for (Object a : args) {
            if (!(a instanceof Enum<?>)) continue;
            for (Field f : SafeReflect.fields(a.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    if ("create".equals(f.get(a))) return true;
                } catch (Throwable ignored) { }
            }
        }
        return false;
    }

    private static Object user(Object[] args) {
        for (Object a : args) if (a != null && a.getClass().getName().equals(USER)) return a;
        return null;
    }

    private static String username(Object[] args) {
        Object u = user(args);
        Method name = UserUtils.userUsernameGetter;
        if (u == null || name == null) return null;
        try { return (String) name.invoke(u); } catch (Throwable t) { return null; }
    }

    private static void confirm(Activity act, String username, Runnable onFollow, Runnable onCancel) {
        try {
            IeDialog dlg = new IeDialog(act)
                    .title(I18n.t(act, R.string.ig_confirm_follow_title))
                    .message(username != null ? I18n.t(act, R.string.ig_confirm_follow_message, username)
                            : I18n.t(act, R.string.ig_confirm_follow_message_any));
            final boolean[] followed = {false};
            dlg.secondary(act.getString(android.R.string.cancel), () -> { });
            dlg.primary(I18n.t(act, R.string.ig_confirm_follow_yes), () -> { followed[0] = true; onFollow.run(); });
            dlg.onDismiss(() -> { if (!followed[0]) onCancel.run(); });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ confirm: " + t);
            onCancel.run();
        }
    }

    private static void send(Member method, Object[] args) {
        try {
            REPLAYING.set(Boolean.TRUE);
            XposedBridge.invokeOriginalMethod(method, null, args);
            ModuleLog.line(TAG + "follow sent");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ send failed: " + t);
        } finally {
            REPLAYING.remove();
        }
    }

    /** Back to "not following": a follow only starts from there. */
    private static void undo(Member method, Object[] args) {
        ModuleLog.line(TAG + "follow cancelled");
        Method setter = statusSetter;
        Object user = user(args);
        Object manager = null;
        for (Object a : args) if (a != null && a.getClass() == ((Method) method).getDeclaringClass()) manager = a;
        if (setter == null || user == null || manager == null) return;
        try {
            Object notFollowing = null;
            for (Object c : setter.getParameterTypes()[0].getEnumConstants())
                if (((Enum<?>) c).name().toUpperCase(java.util.Locale.ROOT).contains("NOTFOLLOWING")) notFollowing = c;
            if (notFollowing == null) return;
            setter.setAccessible(true);
            setter.invoke(manager, notFollowing, user, true);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ undo: " + t);
        }
    }
}
