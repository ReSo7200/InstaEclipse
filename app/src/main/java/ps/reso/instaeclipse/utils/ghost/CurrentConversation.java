package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.app.Application;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Shared foreground binding, independent of retention and never sourced from a last-thread ID. */
public final class CurrentConversation {
    private static final String KEY = "com.instagram.model.direct.DirectThreadKey";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final ConversationState state = new ConversationState();
    private static final List<WeakReference<Object>> fragments = new ArrayList<>();
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static Method keyFormatter;
    private static final Set<Method> rebindHooks = new LinkedHashSet<>();
    private static final List<WeakReference<Object>> boundControllers = new ArrayList<>();
    private static boolean installed, lifecycleRegistered;
    private static String lastStatus;
    private CurrentConversation() {}

    public static final class Snapshot {
        public final String accountId, threadId;
        public final long generation;
        private final ConversationState.Token token;
        private final WeakReference<Activity> activity;
        private Snapshot(Activity activity, ConversationState.Token token) {
            this.activity = new WeakReference<>(activity); this.token = token;
            accountId = token.accountId; threadId = token.threadId; generation = token.generation;
        }
    }

    public static synchronized void install(DexKitBridge bridge, ClassLoader loader) {
        if (installed) return;
        installed = true;
        resolveKeyFormatter(bridge, loader);
        registerApplication(AndroidAppHelper.currentApplication());
        // Dispatchers, not a concrete Instagram Activity's declared onResume: they cover inherited
        // lifecycle implementations and fragment-only transitions inside a reused MainTabActivity.
        int hooked = 0;
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, loader);
            for (String name : new String[]{"performResume", "performPause", "performDestroyView"}) {
                Method method = findNoArg(fragment, name);
                if (method == null) continue;
                final boolean resumed = name.equals("performResume");
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        state.invalidate();
                        if (!resumed) removeFragment(param.thisObject);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (resumed) {
                            removeFragment(param.thisObject);
                            fragments.add(new WeakReference<>(param.thisObject));
                        }
                    }
                });
                hooked++;
            }
        } catch (Throwable failure) { status("fragment lifecycle unavailable"); }
        ModuleLog.line("(IE|Conversation) lifecycle dispatchers resolved=" + hooked);
        // igThreadIgid is also an ephemeral-removal path in this build. It is telemetry only:
        // neither a true boolean nor a background store callback proves a foreground conversation.
        try {
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("igThreadIgid").paramTypes(KEY, "boolean").returnType("void")))) {
                XposedBridge.hookMethod(data.getMethodInstance(loader), new XC_MethodHook() {
                    private boolean logged;
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!logged) {
                            logged = true;
                            ModuleLog.line("(IE|Conversation) igThreadIgid callback observed; not used as foreground authority");
                        }
                    }
                });
            }
        } catch (Throwable ignored) { status("igThreadIgid telemetry unavailable"); }
    }
    /** Called with the actual attach receiver: currentApplication may still be null at attach. */
    public static synchronized void registerApplication(Application application) {
        if (lifecycleRegistered || application == null) return;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                if (foreground.get() != a) state.invalidate();
                foreground = new WeakReference<>(a);
            }
            public void onActivityPaused(Activity a) {
                if (foreground.get() == a) { foreground.clear(); state.invalidate(); }
            }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {
                if (foreground.get() == a) { foreground.clear(); state.invalidate(); }
            }
        });
        lifecycleRegistered = true;
    }
    private static void resolveKeyFormatter(DexKitBridge bridge, ClassLoader loader) {
        // Version-scoped cache plus an algorithm revision; descriptors are checked before reuse.
        String cacheKey = "ConversationKeyFormatter_v2";
        Method cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(cacheKey, loader) : null;
        if (validFormatter(cached)) { keyFormatter = cached; return; }
        try {
            List<Method> found = new ArrayList<>();
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(KEY).usingStrings("thread:", "recipients:").returnType("java.lang.String")))) {
                Method method = data.getMethodInstance(loader);
                if (validFormatter(method)) found.add(method);
            }
            if (found.size() == 1) { keyFormatter = found.get(0); DexKitCache.saveMethod(cacheKey, keyFormatter); }
        } catch (Throwable ignored) {}
    }
    private static boolean validFormatter(Method method) {
        return method != null && method.getDeclaringClass().getName().equals(KEY)
                && !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                && method.getReturnType() == String.class;
    }
    public static String threadIdOfKey(Object key) {
        if (key == null || !key.getClass().getName().equals(KEY)) return null;
        try {
            if (keyFormatter != null) {
                String value = (String) keyFormatter.invoke(key);
                if (value != null && value.startsWith("thread:")) {
                    String id = value.substring(7);
                    return ConversationIdentity.validId(id) ? id : null;
                }
                return null; // recipient-only keys cannot identify a persisted conversation
            }
            // Verified semantic labels in installed 447's DirectThreadKey.toString distinguish
            // mThreadId from mThreadV2Id. Never select the first String or confuse both identities.
            return ConversationIdentity.fromKeyDescription(key.toString());
        } catch (Throwable ignored) { return null; }
    }
    public static Snapshot resolve(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper() || activity == null || activity.isFinishing()
                || activity.isDestroyed() || foreground.get() != activity) return unresolved("no resumed activity");
        int id = activity.getResources().getIdentifier("direct_thread_header", "id", activity.getPackageName());
        View header = id == 0 ? null : activity.findViewById(id);
        if (header == null || !header.isShown() || !header.isAttachedToWindow()) return unresolved("no visible thread header");
        Object owner = ownerOfHeader(activity, header);
        if (owner == null) return unresolved("no unique foreground fragment");
        Set<String> ownerAccounts = directAccounts(owner);
        String account = ownerAccounts.isEmpty() ? accountFromOwnedControllers(owner) : uniqueAccount(ownerAccounts);
        Set<String> activityAccounts = directAccounts(activity);
        if (account == null || (!activityAccounts.isEmpty() && !account.equals(uniqueAccount(activityAccounts))))
            return unresolved("account unresolved or changing");
        Set<String> threads = new LinkedHashSet<>();
        List<Object> resolvedControllers = new ArrayList<>();
        try {
            addDirectKeys(owner, threads);
            for (Object controller : ownedControllers(owner)) {
                Set<String> controllerThreads = new LinkedHashSet<>();
                addDirectKeys(controller, controllerThreads);
                if (controllerThreads.isEmpty()) continue;
                if (!account.equals(accountIdOf(controller))) return unresolved("controller account changing");
                threads.addAll(controllerThreads);
                if (!watchRebinding(controller)) return unresolved("thread rebinding hook unavailable");
                resolvedControllers.add(controller);
            }
        } catch (Throwable failure) { return unresolved("thread ownership unsupported"); }
        if (threads.size() != 1 || threads.contains("")) return unresolved("thread unresolved or ambiguous");
        boundControllers.clear();
        for (Object controller : resolvedControllers) boundControllers.add(new WeakReference<>(controller));
        ConversationState.Token token = state.bind(owner, header, account, threads.iterator().next());
        if (token == null) return unresolved("invalid identity");
        status("foreground account and thread resolved");
        return new Snapshot(activity, token);
    }
    public static boolean revalidate(Activity activity, Snapshot previous) {
        if (previous == null || previous.activity.get() != activity || !state.matches(previous.token)) return false;
        Snapshot current = resolve(activity);
        return current != null && state.matches(previous.token);
    }
    public static String accountId(Activity activity) {
        if (activity == null || foreground.get() != activity) return null;
        Set<String> accounts = directAccounts(activity);
        if (accounts.contains("")) return null;
        for (WeakReference<Object> ref : new ArrayList<>(fragments)) {
            Object fragment = ref.get();
            if (fragment != null && call(fragment, "getActivity") == activity && Boolean.TRUE.equals(call(fragment, "isResumed"))) {
                Set<String> fragmentAccounts = directAccounts(fragment);
                String account = fragmentAccounts.isEmpty() ? accountFromOwnedControllers(fragment) : uniqueAccount(fragmentAccounts);
                if (account != null) accounts.add(account);
                else if (!fragmentAccounts.isEmpty()) return null;
            }
        }
        return accounts.size() == 1 ? accounts.iterator().next() : null;
    }
    public static String accountIdOf(Object owner) { return uniqueAccount(directAccounts(owner)); }
    private static String uniqueAccount(Set<String> accounts) {
        return accounts.size() == 1 && !accounts.contains("") ? accounts.iterator().next() : null;
    }
    private static Set<String> directAccounts(Object owner) {
        Set<String> accounts = new LinkedHashSet<>();
        if (owner == null) return accounts;
        try {
            Method getter = findNoArg(owner.getClass(), "getSession");
            if (getter != null) {
                Object session = getter.invoke(owner);
                if (session == null || !session.getClass().getName().equals(SESSION)) accounts.add("");
                else addAccount(accounts, session);
            }
            addAccount(accounts, owner);
            for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(SESSION)) continue;
                    field.setAccessible(true);
                    Object session = field.get(owner);
                    if (session != null) addAccount(accounts, session);
                }
            }
        } catch (Throwable failure) { accounts.add(""); }
        return accounts;
    }
    private static String accountFromOwnedControllers(Object owner) {
        Set<String> accounts = new LinkedHashSet<>();
        try {
            for (Object candidate : ownedControllers(owner)) {
                Set<String> evidence = directAccounts(candidate);
                if (!evidence.isEmpty()) accounts.addAll(evidence);
            }
        } catch (Throwable failure) { return null; }
        return uniqueAccount(accounts);
    }
    private static List<Object> ownedControllers(Object owner) throws IllegalAccessException {
        List<Object> controllers = new ArrayList<>();
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object candidate = field.get(owner);
                if (candidate != null && !(candidate instanceof View) && !(candidate instanceof Activity)
                        && !isFragment(candidate) && belongsTo(candidate, owner)) controllers.add(candidate);
            }
        }
        return controllers;
    }
    /** 447's controller has one instance (DirectThreadKey)->void rebind method. Hook by shape
     * after proving controller ownership, so same-Fragment A -> B -> A invalidates old callbacks. */
    private static boolean watchRebinding(Object controller) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : controller.getClass().getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                    && parameters.length == 1 && parameters[0].getName().equals(KEY)) candidates.add(method);
        }
        if (candidates.size() != 1) return false;
        Method method = candidates.get(0);
        if (rebindHooks.contains(method)) return true;
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (WeakReference<Object> reference : boundControllers) {
                        if (reference.get() == param.thisObject) { state.invalidate(); break; }
                    }
                }
            });
            rebindHooks.add(method);
            ModuleLog.line("(IE|Conversation) owned controller rebind method resolved");
            return true;
        } catch (Throwable failure) { return false; }
    }
    private static void addDirectKeys(Object owner, Set<String> threads) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(KEY)) continue;
                field.setAccessible(true);
                Object key = field.get(owner);
                if (key != null) {
                    String id = threadIdOfKey(key);
                    threads.add(id == null ? "" : id);
                }
            }
        }
    }
    /** Installed 447's thread controller directly references its owning Fragment. */
    private static boolean belongsTo(Object candidate, Object owner) {
        String name = candidate.getClass().getName();
        if (!name.startsWith("X.") && !name.startsWith("com.instagram.")) return false;
        for (Class<?> c = candidate.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInstance(owner)) continue;
                try { field.setAccessible(true); if (field.get(candidate) == owner) return true; }
                catch (Throwable ignored) {}
            }
        }
        return false;
    }
    private static void addAccount(Set<String> accounts, Object value) {
        if (value == null || !value.getClass().getName().equals(SESSION)) return;
        Object ended = call(value, "hasEnded");
        Object account = call(value, "getUserId");
        if (!Boolean.FALSE.equals(ended) || !(account instanceof String)
                || !ConversationIdentity.validId((String) account)) accounts.add("");
        else accounts.add((String) account);
    }
    private static Object ownerOfHeader(Activity activity, View header) {
        Object winner = null; View winnerView = null;
        for (WeakReference<Object> ref : new ArrayList<>(fragments)) {
            Object fragment = ref.get();
            if (fragment == null || call(fragment, "getActivity") != activity
                    || !Boolean.TRUE.equals(call(fragment, "isResumed"))
                    || !Boolean.TRUE.equals(call(fragment, "isVisible"))) continue;
            Object view = call(fragment, "getView");
            if (!(view instanceof View root) || !within(header, root)) continue;
            if (winnerView == null || within(root, winnerView)) { winner = fragment; winnerView = root; }
            else if (!within(winnerView, root)) return null;
        }
        return winner;
    }
    private static boolean within(View child, View root) {
        for (ViewParent parent = child.getParent(); ; parent = parent.getParent()) {
            if (child == root || parent == root) return true;
            if (parent == null) return false;
        }
    }
    private static boolean isFragment(Object value) {
        if (value == null) return false;
        for (Class<?> c = value.getClass(); c != null; c = c.getSuperclass())
            if (c.getName().equals("androidx.fragment.app.Fragment")) return true;
        return false;
    }
    private static Method findNoArg(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { Method method = c.getDeclaredMethod(name); method.setAccessible(true); return method; }
            catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
    private static Object call(Object owner, String name) {
        try { Method method = findNoArg(owner.getClass(), name); return method == null ? null : method.invoke(owner); }
        catch (Throwable ignored) { return null; }
    }
    private static void removeFragment(Object fragment) {
        fragments.removeIf(ref -> ref.get() == null || ref.get() == fragment);
    }
    private static Snapshot unresolved(String reason) { state.invalidate(); status(reason); return null; }
    private static void status(String status) {
        if (!status.equals(lastStatus)) { lastStatus = status; ModuleLog.line("(IE|Conversation) " + status); }
    }
}
