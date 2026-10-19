package ps.reso.instaeclipse.mods.ghost;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentConversation;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.ghost.InboxListFilter;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Local inbox-result projection only. Header actions live in the shared discreet-menu hook. */
final class HiddenInboxFilter {
    private static final String ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static final String CACHE = "HideChats_inbox_account_v3";
    private static final String KEY = "com.instagram.model.direct.DirectThreadKey";
    private static boolean observed, filtered, unknownAccount, unknownRow;
    private static Class<?> inboxStoreClass;
    private static Method refreshMethod;
    private static final Map<String, WeakReference<Object>> inboxStores = new ConcurrentHashMap<>();
    private static final AtomicInteger refreshRequests = new AtomicInteger();
    private static final Map<String, Integer> pendingRefresh = new ConcurrentHashMap<>();
    public void install(DexKitBridge bridge, ClassLoader loader) {
        CurrentConversation.install(bridge, loader);
        installRefresh(bridge, loader);
        List<Method> methods = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE, loader) : null;
        if (methods == null || methods.isEmpty() || methods.stream().anyMatch(m -> !validMethod(m))) {
            methods = new ArrayList<>();
            try {
                for (MethodData data : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(ANCHOR)))) {
                    Method method = data.getMethodInstance(loader);
                    if (validMethod(method)) methods.add(method);
                }
                DexKitCache.saveMethods(CACHE, methods);
            } catch (Throwable failure) { ModuleLog.line("(IE|HideChats) inbox discovery failed"); }
        }
        int hooked = 0;
        for (Method method : methods) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        rememberInboxStore(param.thisObject);
                        String requestAccount = CurrentConversation.accountIdOf(param.thisObject);
                        Integer request = requestAccount == null ? null : pendingRefresh.remove(requestAccount);
                        if (request != null) ModuleLog.line("(IE|HideChats) local inbox rebuild observed, request=" + request);
                        if (!observed) { observed = true; ModuleLog.line("(IE|HideChats) inbox builder executed"); }
                        if (!FeatureFlags.hideSpecificChats || !(param.getResult() instanceof List<?> list) || list.isEmpty()) return;
                        try {
                            // Scope from THIS store, never the foreground account or last seen session.
                            String account = CurrentConversation.accountIdOf(param.thisObject);
                            if (account == null) {
                                if (!unknownAccount) { unknownAccount = true; ModuleLog.line("(IE|HideChats) store account unresolved; no rows changed"); }
                                return;
                            }
                            if (HiddenThreads.all(account).isEmpty()) return;
                            Object replacement = InboxListFilter.filtered(list, method.getReturnType(), row -> {
                                String id = threadIdOfRow(row);
                                if (id == null && !unknownRow) {
                                    unknownRow = true;
                                    ModuleLog.line("(IE|HideChats) row identity unresolved; row retained");
                                }
                                return id != null && HiddenThreads.isHidden(account, id);
                            });
                            if (replacement != list) {
                                param.setResult(replacement);
                                if (!filtered) { filtered = true; ModuleLog.line("(IE|HideChats) account-scoped inbox result filtered"); }
                            }
                        } catch (Throwable failure) { ModuleLog.line("(IE|HideChats) filter declined incompatible data"); }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {}
        }
        if (hooked > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
        ModuleLog.line("(IE|HideChats) inbox methods resolved=" + hooked + "; execution requires a real inbox");
    }
    private static void installRefresh(DexKitBridge bridge, ClassLoader loader) {
        try {
            Set<Class<?>> classes = new LinkedHashSet<>();
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("DirectThreadStoreImpl.notifyFolderChange"))))
                classes.add(data.getMethodInstance(loader).getDeclaringClass());
            if (classes.size() != 1) return;
            Class<?> type = classes.iterator().next();
            List<Method> candidates = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && parameters.length == 1 && parameters[0].isEnum()) candidates.add(method);
            }
            if (candidates.size() != 1) return;
            Method method = candidates.get(0);
            Object[] folders = method.getParameterTypes()[0].getEnumConstants();
            if (folders == null || folders.length == 0 || folders.length > 32) return;
            method.setAccessible(true);
            inboxStoreClass = type;
            refreshMethod = method;
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) rememberInboxStore(param.thisObject);
                }
            });
            ModuleLog.line("(IE|HideChats) local inbox refresh method resolved");
        } catch (Throwable failure) { ModuleLog.line("(IE|HideChats) local refresh unavailable"); }
    }
    private static void rememberInboxStore(Object store) {
        if (store == null || inboxStoreClass == null) return;
        try {
            Object inbox = null;
            if (inboxStoreClass.isInstance(store)) inbox = store;
            else for (Field field : store.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != inboxStoreClass) continue;
                field.setAccessible(true);
                Object candidate = field.get(store);
                if (candidate != null) {
                    if (inbox != null && inbox != candidate) return;
                    inbox = candidate;
                }
            }
            String account = CurrentConversation.accountIdOf(inbox);
            if (account != null) inboxStores.put(account, new WeakReference<>(inbox));
        } catch (Throwable ignored) {}
    }
    /** Requests only local observer rebuilds. true means scheduled; hook telemetry confirms execution.
     * The installed method posts/coalesces an in-process Runnable per inbox folder, rebuilds through
     * the filtered store, and publishes the list. It does not fetch, delete, or move server threads. */
    public static boolean refreshAccount(String account) {
        Method method = refreshMethod;
        WeakReference<Object> reference = account == null ? null : inboxStores.get(account);
        Object store = reference == null ? null : reference.get();
        if (method == null || store == null || !account.equals(CurrentConversation.accountIdOf(store))) return false;
        int request = refreshRequests.incrementAndGet();
        pendingRefresh.put(account, request);
        try {
            for (Object folder : method.getParameterTypes()[0].getEnumConstants()) method.invoke(store, folder);
            ModuleLog.line("(IE|HideChats) local inbox refresh scheduled, request=" + request);
            return true;
        } catch (Throwable failure) {
            pendingRefresh.remove(account);
            ModuleLog.line("(IE|HideChats) local inbox refresh failed");
            return false;
        }
    }
    public static int refreshKnownAccounts() {
        int scheduled = 0;
        for (String account : new ArrayList<>(inboxStores.keySet())) {
            if (refreshAccount(account)) scheduled++;
        }
        return scheduled;
    }
    private static boolean validMethod(Method method) {
        if (method == null || Modifier.isStatic(method.getModifiers())) return false;
        Class<?> result = method.getReturnType();
        return result == List.class || result == ArrayList.class || result == java.util.LinkedList.class
                || result == java.util.Collection.class || result == Iterable.class;
    }
    public static String threadIdOfRow(Object row) {
        if (row == null) return null;
        if (row.getClass().getName().equals(KEY)) return CurrentConversation.threadIdOfKey(row);
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (Class<?> type = row.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(KEY)) continue;
                    field.setAccessible(true);
                    Object key = field.get(row);
                    if (key == null) continue;
                    String id = CurrentConversation.threadIdOfKey(key);
                    if (id == null) return null;
                    ids.add(id);
                }
            }
            // Current 447 row model exposes its key through the DirectThreadKey-returning accessor.
            // Resolve by signature, not the obfuscated CTG name. Refuse contradictory identities.
            if (ids.isEmpty()) for (Method method : row.getClass().getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || !method.getReturnType().getName().equals(KEY)) continue;
                Object key = method.invoke(row);
                if (key == null) continue;
                String id = CurrentConversation.threadIdOfKey(key);
                if (id == null) return null;
                ids.add(id);
            }
        } catch (Throwable failure) { return null; }
        return ids.size() == 1 ? ids.iterator().next() : null;
    }
}
