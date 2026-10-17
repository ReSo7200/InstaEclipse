package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.ThreadNames;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Unsent messages log (anti-unsend). GitHub issue #25.
 *
 * <p>When the other party unsends a DM (or you delete one), Instagram removes it locally through the
 * DirectThreadStore remove primitive (X/08ci.Glz(DirectThreadKey,String,String)V -> Gm0(...,boolean)V
 * on 452). Right before that runs, the message is looked up in its thread entry and its text is
 * saved to {@link ps.reso.instaeclipse.utils.ghost.UnsentLog}, read back by the "Unsent messages"
 * viewer and the in-thread button. Instagram then removes the message as usual.
 *
 * <p>Keeping the message in the chat itself was dropped: Instagram's thread syncs remove it again
 * (and from its local database) through paths that could not be covered reliably.
 *
 * <p>Robustness: no obfuscated X.* names are hardcoded. The store is anchored by its "Posted
 * RemoveThreadEvent..." string; the thread entry class by its reconcile's "...deleted locally or
 * not" string. The message is matched by scanning its String fields for the removed id, and its
 * body is the longest human-looking String in its object graph.
 */
public class KeepUnsentMessagesHook {

    private static final String[] STORE_ANCHORS = {
            "Posted RemoveThreadEvent with null thread_id for threadKey = ",
            "Entry should exist before function call"
    };
    private static final String RECONCILE_ANCHOR =
            " is not in cache, may need to double check if this message is deleted locally or not";
    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";
    private static final String CACHE_KEY = "KeepUnsent_remove";

    /** Extract the thread id string from a DirectThreadKey (first non-empty String field). */
    private static String threadIdOf(Object dtk) {
        if (dtk == null) return null;
        try {
            for (Class<?> c = dtk.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(dtk);
                    if (v instanceof String && !((String) v).isEmpty()) return (String) v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Thread id of the DM thread currently in the foreground (set on thread open). Used by the
     *  in-thread unsent button to show only THIS chat's log. */
    public static volatile String currentThreadId = null;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveEntryClass(bridge, classLoader);
        hookRemovePrimitive(bridge, classLoader);
        hookCurrentThread(bridge, classLoader);
    }

    /** Track the open thread: the (DirectThreadKey, boolean)->void "igThreadIgid" method fires
     *  when a thread becomes visible; stash its DirectThreadKey id. */
    private void hookCurrentThread(DexKitBridge bridge, ClassLoader classLoader) {
        // Version-proof source: the resumed thread fragment's DirectThreadKey. On IG 451 the
        // "igThreadIgid" string below no longer sits on a (DirectThreadKey, boolean) method.
        ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker.install(classLoader);
        ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker.addListener((activity, id) -> {
            currentThreadId = id;
            rememberThreadName(id, activity);
        });
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("igThreadIgid")
                            .paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")));
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (param.args.length > 0) {
                        String id = threadIdOf(param.args[0]);
                        Object flag = param.args.length > 1 ? param.args[1] : null;
                        ModuleLog.line("(IE|KeepUnsent|PROBE) igThreadIgid id=" + id + " flag=" + flag);
                        // Only treat flag==true as "thread entered/visible" to avoid background sync
                        // overwriting the current thread with other threads' ids.
                        if (id != null && Boolean.TRUE.equals(flag)) {
                            currentThreadId = id;
                            rememberThreadName(id);
                        }
                    }
                }
            };
            int n = 0;
            for (MethodData md : methods) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), hook); n++; }
                catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|KeepUnsent) current-thread tracker hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ⚠️ current-thread tracker: " + t.getMessage());
        }
    }

    /** Resolve the open thread's display name (username) shortly after it becomes visible and
     *  persist it as threadId -> name, so the Unsent Messages folders can label chats by username
     *  instead of an opaque id. Posted with a delay so the thread header has laid out. */
    private static void rememberThreadName(final String threadId) {
        rememberThreadName(threadId, null);
    }

    /** @param host the activity hosting the thread (ModalActivity or the main one), if known. */
    private static void rememberThreadName(final String threadId, final android.app.Activity host) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (!threadId.equals(currentThreadId)) return; // user moved on
                    // The header on screen belongs to the resumed thread fragment; an id reported
                    // by a background sync must not take that chat's name.
                    String open = ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker.currentThreadId();
                    if (open != null && !open.equals(threadId)) return;
                    android.app.Activity a = host != null ? host : UIHookManager.getCurrentActivity();
                    if (a == null) return;
                    View root = a.getWindow() != null ? a.getWindow().getDecorView() : null;
                    if (!(root instanceof ViewGroup)) return;
                    String name = findThreadName(a, (ViewGroup) root);
                    if (name != null) ThreadNames.put(threadId, name);
                } catch (Throwable ignored) {}
            }, 600);
        } catch (Throwable ignored) {}
    }

    /** Resolve the DM thread's display name. Prefer the stable header title view
     *  (com.instagram.android:id/header_title — a Button/TextView holding e.g. "just abdul"), since
     *  the display name can contain spaces the handle regex would reject; fall back to the
     *  handle-token scan only if that view is absent. */
    @SuppressLint("DiscouragedApi")
    private static String findThreadName(android.app.Activity a, ViewGroup root) {
        // Only ever read the DM thread header: a whole-window scan picked up message words
        // ("New", "Finished") and other chats' names.
        int headerId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
        View header = headerId != 0 ? root.findViewById(headerId) : null;
        if (!(header instanceof ViewGroup headerGroup)) return null;
        root = headerGroup;
        try {
            int titleId = a.getResources().getIdentifier("header_title", "id", a.getPackageName());
            if (titleId != 0) {
                View t = root.findViewById(titleId);
                if (t instanceof android.widget.TextView tv) {
                    CharSequence cs = tv.getText();
                    if (cs != null) {
                        String s = cs.toString().trim();
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return findHandle(root);
    }

    /** Depth-first scan for a handle-like TextView token (@username style: letters/digits/._), the
     *  DM thread header's title. Bounded so it stays cheap on a large view tree. Requires at least
     *  one letter so a stray all-digit token (timestamp, count) is never mistaken for a name. */
    private static String findHandle(ViewGroup vg) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(vg);
        int visited = 0;
        while (!stack.isEmpty() && visited < 400) {
            View v = stack.pop();
            visited++;
            if (v instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) v).getText();
                if (cs != null) {
                    String s = cs.toString().trim();
                    // handle-like AND contains at least one letter (never an all-digit count/time)
                    if (s.matches("[a-zA-Z0-9._]{2,30}") && s.matches(".*[a-zA-Z].*")) return s;
                }
            } else if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.push(g.getChildAt(i));
            }
        }
        return null;
    }

    // ── Removal: log the message's text, then let Instagram remove it ───────────
    private void hookRemovePrimitive(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepUnsentMessages || param.args.length == 0) return;
                String threadId = threadIdOf(param.args[0]);
                for (Object a : param.args) {
                    if (a instanceof String && ((String) a).length() >= 6) logRemoved(param.thisObject, param.args[0], threadId, (String) a);
                }
            }
        };

        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods(CACHE_KEY, classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, hook);
                FeatureStatusTracker.setHooked("KeepUnsentMessages");
                ModuleLog.line("(IE|KeepUnsent) ✅ remove hooked (cached) " + cached.size());
                return;
            }
        }

        try {
            LinkedHashSet<Class<?>> stores = new LinkedHashSet<>();
            for (String anchor : STORE_ANCHORS) {
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(anchor)))) {
                    try { stores.add(md.getMethodInstance(classLoader).getDeclaringClass()); } catch (Throwable ignored) {}
                }
                if (!stores.isEmpty()) break;
            }
            java.util.List<Method> hooked = new java.util.ArrayList<>();
            for (Class<?> store : stores) {
                for (Method m : store.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class || !Modifier.isSynchronized(m.getModifiers())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    boolean r3 = p.length == 3 && p[0].getName().equals(DTK) && p[1] == String.class && p[2] == String.class;
                    boolean r4 = p.length == 4 && p[0].getName().equals(DTK) && p[1] == String.class && p[2] == String.class && p[3] == boolean.class;
                    if (!r3 && !r4) continue;
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, hook);
                        hooked.add(m);
                        ModuleLog.line("(IE|KeepUnsent) ✅ remove hook → " + store.getName() + "." + m.getName() + " (" + p.length + "-arg)");
                    } catch (Throwable t) { ModuleLog.line("(IE|KeepUnsent) ⚠️ " + t.getMessage()); }
                }
                if (!hooked.isEmpty()) break;
            }
            if (hooked.isEmpty()) { ModuleLog.line("(IE|KeepUnsent) ❌ remove primitive not found"); return; }
            DexKitCache.saveMethods(CACHE_KEY, hooked);
            FeatureStatusTracker.setHooked("KeepUnsentMessages");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ❌ remove: " + t);
        }
    }

    // ── Thread entry lookup ──────────────────────────────────────────────────────
    /** The per-thread entry class (01eV on 452), which holds the thread's loaded messages. */
    private static Class<?> entryClass;

    /** The entry class declares the reconcile anchored by RECONCILE_ANCHOR. */
    private static void resolveEntryClass(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(RECONCILE_ANCHOR)))) {
                entryClass = md.getMethodInstance(classLoader).getDeclaringClass();
                ModuleLog.line("(IE|KeepUnsent) ✅ thread entry → " + entryClass.getName());
                return;
            }
            ModuleLog.line("(IE|KeepUnsent) ❌ thread entry not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ❌ thread entry: " + t);
        }
    }

    /** Ids already logged; the 3-arg remove calls the 4-arg one, so each removal is seen twice. */
    private static final Set<String> loggedIds = Collections.synchronizedSet(new LinkedHashSet<String>() {
        @Override public boolean add(String e) {
            if (size() >= 500) { java.util.Iterator<String> it = iterator(); if (it.hasNext()) { it.next(); it.remove(); } }
            return super.add(e);
        }
    });

    /** Finds message {@code id} in the store's entry for {@code threadKey} and logs its text. */
    @SuppressWarnings("unchecked")
    private static void logRemoved(Object store, Object threadKey, String threadId, String id) {
        if (entryClass == null || store == null || threadKey == null || loggedIds.contains(id)) return;
        try {
            Object entry = null;
            for (Method m : store.getClass().getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].getName().equals(DTK) && m.getReturnType() == entryClass) {
                    m.setAccessible(true);
                    entry = m.invoke(store, threadKey);
                    break;
                }
            }
            if (entry == null) return;
            for (Field f : entryClass.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType()) || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(entry);
                if (!(v instanceof List)) continue;
                synchronized (v) {
                    for (Object msg : (List<Object>) v) {
                        if (!carriesId(msg, id, 0, Collections.newSetFromMap(new IdentityHashMap<>()))) continue;
                        String body = bodyOf(msg);
                        if (body == null) return;
                        loggedIds.add(id);
                        String who = ThreadNames.resolve(threadId);
                        ps.reso.instaeclipse.utils.ghost.UnsentLog.add(threadId, who, body);
                        ModuleLog.line("(IE|KeepUnsent) ✅ logged (thread=" + threadId + " who=" + who + "): "
                                + (body.length() > 24 ? body.substring(0, 24) + "…" : body));
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ⚠️ log failed: " + t);
        }
    }

    /** The longest human-looking String in the message's object graph (its text). */
    private static String bodyOf(Object msg) {
        String[] best = new String[1];
        findBody(msg, 0, Collections.newSetFromMap(new IdentityHashMap<>()), best);
        return best[0];
    }

    private static void findBody(Object obj, int depth, Set<Object> seen, String[] best) {
        if (obj == null || depth > 3 || !seen.add(obj)) return;
        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v;
                try { v = f.get(obj); } catch (Throwable e) { continue; }
                if (v instanceof String) {
                    String s = (String) v;
                    if (looksLikeBody(s) && (best[0] == null || s.length() > best[0].length())) best[0] = s;
                } else if (v != null && !f.getType().isPrimitive()) {
                    findBody(v, depth + 1, seen, best);
                }
            }
        }
    }

    private static boolean looksLikeBody(String s) {
        if (s == null || s.length() < 2) return false;
        if (s.startsWith("mid.") || s.contains("://") || s.contains("cdn")) return false;
        if (s.matches("[0-9]+") || s.matches("[a-z0-9_]+")) return false;   // ids / snake_case tokens
        // Reject enum/type tokens: strings with letters that are ALL uppercase (TEXT, ACTION_LOG,
        // RAVEN_MEDIA, EXPIRING_MEDIA, LINK, …). Real message text is not all-caps.
        if (s.equals(s.toUpperCase()) && !s.equals(s.toLowerCase())) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) { hasLetter = true; break; }
        return hasLetter;
    }

    /** True when {@code id} is one of the message's String fields (depth-limited). */
    private static boolean carriesId(Object obj, String id, int depth, Set<Object> seen) {
        if (obj == null || depth > 3 || !seen.add(obj)) return false;
        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return false;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v;
                try { v = f.get(obj); } catch (Throwable e) { continue; }
                if (id.equals(v)) return true;
                if (v != null && !(v instanceof String) && !f.getType().isPrimitive() && carriesId(v, id, depth + 1, seen)) return true;
            }
        }
        return false;
    }
}
