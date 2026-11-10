package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.ReadReceiptWhitelist;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Handles Ghost Mode for Direct Messages (DM) in Instagram.
 *
 * Opening a chat calls the "mark_thread_seen-" sender, which only queues a mark-seen mutation.
 * The queue then applies the seen state locally (optimistic observer, committed when the
 * mutation reports "uploaded") and runs the mutation's executor, which sends
 * IGDirectItemSeenMutation ("xig_direct_item_seen") to the server.
 *
 * When the executor is found, the sender is left alone and only the executor is stopped: it
 * reports success to the queue without sending anything, so the chat is marked read on this
 * phone (blue dot and "new messages" cleared) while the other person never gets "Seen" (#299).
 * If the executor is not found, the old behaviour is kept: the sender is blocked, so nothing is
 * queued and nothing is sent (the chat then stays unread locally).
 *
 * Whether a queued mutation may reach the server is decided when it is queued (per thread, the
 * latest call wins, like Instagram's own de-duplication): allowed when Hide DM Seen is off at that
 * moment (this also covers the Mark as Read buttons, which turn it off briefly) or the chat is on
 * the read receipt whitelist.
 */
public class GhostDMSeenHook {

    private static final String EXEC_KEY = "GhostSeenExec";
    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";

    /** Thread id -> whether its latest queued mark-seen may be sent to the server. */
    private static final Map<String, Boolean> sendAllowed = new ConcurrentHashMap<>();
    /** True once the executor is hooked: the sender then records decisions instead of blocking. */
    private static volatile boolean localSeenMode = false;

    private static final Map<Class<?>, Method> threadKeyGetters = new ConcurrentHashMap<>();

    /** Thread id -> args of its latest held-back sender call, replayed by the Mark as Read buttons. */
    private static final Map<String, Object[]> heldBack = new ConcurrentHashMap<>();
    private static volatile Method sender;
    private static final ThreadLocal<Boolean> FORCE_SEND = new ThreadLocal<>();

    public void handleSeenBlock(DexKitBridge bridge) {
        try {
            localSeenMode = hookExecutor(bridge);
        } catch (Throwable t) {
            localSeenMode = false;
            ModuleLog.line("(InstaEclipse | GhostModeSeen): ⚠️ executor lookup failed: " + t);
        }
        if (!localSeenMode) {
            ModuleLog.line("(InstaEclipse | GhostModeSeen): ⚠️ seen executor not found, blocking the sender (chats stay unread locally)");
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                // Per-chat whitelist (#227): the sender's first String arg is the thread id
                // ("mark_thread_seen-" + thread_id on 447/451/452) — let whitelisted chats through.
                String threadId = null;
                for (Object a : param.args) {
                    if (a instanceof String s) { threadId = s; break; }
                }
                boolean allow = FORCE_SEND.get() == Boolean.TRUE
                        || !FeatureFlags.isGhostSeen
                        || (threadId != null && ReadReceiptWhitelist.isAllowed(threadId));
                if (localSeenMode) {
                    // Let Instagram queue it (local read state); the executor hook stops the send.
                    if (threadId != null) {
                        remember(threadId, allow);
                        if (allow) {
                            heldBack.remove(threadId);
                        } else if (param.method instanceof Method m) {
                            sender = m;
                            if (heldBack.size() > 512) heldBack.clear();
                            heldBack.put(threadId, param.args.clone());
                        }
                    }
                    return;
                }
                if (!allow) param.setResult(null);
            }
        };

        // Cache hit — skip DexKit
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("GhostSeen", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.line("(InstaEclipse | GhostModeSeen): ✅ Hooked: " + cached.getDeclaringClass().getName() + "." + cached.getName());
                FeatureStatusTracker.setHooked("GhostSeen");
                return;
            }
        }

        try {
            // Step 1: Find all methods containing "mark_thread_seen-"
            List<MethodData> methods = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("mark_thread_seen-")));

            if (methods.isEmpty()) {
                ModuleLog.line("(InstaEclipse | GhostModeSeen): ❌ No methods found using 'mark_thread_seen-'");
                return;
            }

            for (MethodData method : methods) {
                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue; // Skip methods that can't be resolved
                }

                int modifiers = reflectMethod.getModifiers();
                String returnType = String.valueOf(method.getReturnType());
                ClassDataList paramTypes = method.getParamTypes();

                // Step 2: Match: static final void method(?, ?, ?, ...)
                if (Modifier.isStatic(modifiers)
                        && Modifier.isFinal(modifiers)
                        && returnType.contains("void")
                        && paramTypes.size() >= 3) {

                    try {
                        DexKitCache.saveMethod("GhostSeen", reflectMethod);
                        XposedBridge.hookMethod(reflectMethod, hook);

                        ModuleLog.line("(InstaEclipse | GhostModeSeen): ✅ Hooked: " +
                                method.getClassName() + "." + method.getName());
                        FeatureStatusTracker.setHooked("GhostSeen");
                        return;

                    } catch (Throwable e) {
                        ModuleLog.line("(InstaEclipse | GhostModeSeen): ❌ Hook error: " + e.getMessage());
                    }
                }
            }

        } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | GhostModeSeen): ❌ DexKit exception: " + e.getMessage());
        }
    }

    /**
     * Mark as Read buttons: once a chat is read locally, Instagram no longer calls the sender for
     * it (it checks the local seen state first), so scrolling alone would not send "Seen" any more.
     * This replays the latest held-back sender call for the thread with sending allowed.
     *
     * @return true when a held-back call was replayed.
     */
    public static boolean sendHeldBackSeen(String threadId) {
        if (!localSeenMode || threadId == null) return false;
        Object[] args = heldBack.get(threadId);
        Method m = sender;
        if (args == null || m == null) return false;
        FORCE_SEND.set(Boolean.TRUE);
        try {
            m.setAccessible(true);
            m.invoke(null, args);
            ModuleLog.line("(InstaEclipse | GhostModeSeen): sent held-back seen for thread " + threadId);
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | GhostModeSeen): ⚠️ replay failed: " + t);
            return false;
        } finally {
            FORCE_SEND.remove();
        }
    }

    private static void remember(String threadId, boolean allow) {
        if (sendAllowed.size() > 512) sendAllowed.clear();
        sendAllowed.put(threadId, allow);
    }

    // ---------------------------------------------------------------------------------------
    // Executor: the mutation handler's execute(context, callback, mutation) that builds and sends
    // IGDirectItemSeenMutation. On 447/452 it uses "xig_direct_item_seen" itself; on 451 the
    // string sits in a static request builder, so the executor is its caller.
    // ---------------------------------------------------------------------------------------

    private static boolean hookExecutor(DexKitBridge bridge) {
        Method exec = null;
        if (DexKitCache.isCacheValid()) exec = DexKitCache.loadMethod(EXEC_KEY, Module.hostClassLoader);
        if (exec == null || !isExecutorShape(exec)) {
            exec = findExecutor(bridge);
            if (exec == null) return false;
            DexKitCache.saveMethod(EXEC_KEY, exec);
        }
        final int cbIndex = callbackIndex(exec);
        final Method done = cbIndex < 0 ? null : callbackMethod(exec.getParameterTypes()[cbIndex]);
        if (done == null) return false;
        done.setAccessible(true);

        XposedBridge.hookMethod(exec, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    if (!FeatureFlags.isGhostSeen) return;
                    String threadId = null;
                    for (int i = 0; i < param.args.length && threadId == null; i++) {
                        if (i != cbIndex) threadId = threadIdOfMutation(param.args[i]);
                    }
                    Boolean allow = threadId == null ? null : sendAllowed.get(threadId);
                    if (allow == null) {
                        // Queued before this process started (persisted queue) or untracked.
                        allow = threadId != null && ReadReceiptWhitelist.isAllowed(threadId);
                    }
                    if (allow) return;
                    Object callback = param.args[cbIndex];
                    if (callback == null) return;
                    // Report success (no error) without sending: the queue marks the mutation
                    // "uploaded" and commits the seen state locally.
                    done.invoke(callback, null, null);
                    param.setResult(null);
                    ModuleLog.line("(InstaEclipse | GhostModeSeen): kept seen local for thread " + threadId);
                } catch (Throwable t) {
                    // Never let the receipt go out because of a failure here.
                    param.setResult(null);
                    ModuleLog.line("(InstaEclipse | GhostModeSeen): ⚠️ executor hook: " + t);
                }
            }
        });
        ModuleLog.line("(InstaEclipse | GhostModeSeen): ✅ Hooked executor: "
                + exec.getDeclaringClass().getName() + "." + exec.getName());
        return true;
    }

    private static Method findExecutor(DexKitBridge bridge) {
        if (bridge == null) return null;
        List<MethodData> users = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings("xig_direct_item_seen")));
        for (MethodData md : users) {
            Method m = resolve(md);
            if (m != null && isExecutorShape(m)) return m;
        }
        // 451: the string is in a static request builder; the executor calls it.
        for (MethodData md : users) {
            Method m = resolve(md);
            if (m == null || !Modifier.isStatic(m.getModifiers())) continue;
            try {
                for (MethodData caller : md.getCallers()) {
                    Method c = resolve(caller);
                    if (c != null && isExecutorShape(c)) return c;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method resolve(MethodData md) {
        try {
            if (!md.isMethod()) return null;
            return md.getMethodInstance(Module.hostClassLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Instance void method with 3 params, one of which is a callback interface. */
    private static boolean isExecutorShape(Method m) {
        if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) return false;
        if (m.getParameterTypes().length != 3) return false;
        int cb = callbackIndex(m);
        return cb >= 0 && callbackMethod(m.getParameterTypes()[cb]) != null;
    }

    private static int callbackIndex(Method m) {
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (p[i].isInterface() && callbackMethod(p[i]) != null) return i;
        }
        return -1;
    }

    /** The callback's "finished(error, message)" method: abstract void (Object, String). */
    private static Method callbackMethod(Class<?> iface) {
        if (!iface.isInterface()) return null;
        Method found = null;
        for (Method m : iface.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[1] == String.class && !p[0].isPrimitive() && p[0] != String.class) {
                if (found != null) return null; // ambiguous
                found = m;
            }
        }
        return found;
    }

    /** Thread id of a mark-seen mutation, via its no-arg DirectThreadKey accessor. */
    private static String threadIdOfMutation(Object mutation) {
        if (mutation == null) return null;
        try {
            Class<?> c = mutation.getClass();
            Method getter = threadKeyGetters.get(c);
            if (getter == null) {
                for (Method m : c.getMethods()) {
                    if (m.getParameterTypes().length == 0 && DTK.equals(m.getReturnType().getName())) {
                        getter = m;
                        break;
                    }
                }
                if (getter == null) return null;
                getter.setAccessible(true);
                threadKeyGetters.put(c, getter);
            }
            return threadIdOfKey(getter.invoke(mutation));
        } catch (Throwable t) {
            return null;
        }
    }

    /** First non-empty String instance field of a DirectThreadKey (the thread id). */
    private static String threadIdOfKey(Object dtk) {
        if (dtk == null) return null;
        try {
            for (Class<?> c = dtk.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(dtk);
                    if (v instanceof String s && !s.isEmpty()) return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
