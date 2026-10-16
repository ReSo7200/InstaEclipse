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
 * Keep Unsent Messages (anti-unsend). GitHub issue #25.
 *
 * When the other party unsends a DM (or you delete-for-you), Instagram removes the message locally
 * through two paths:
 *   1. LIVE: the DirectThreadStore remove primitive X/08bh.GYr(DirectThreadKey,String,String)V ->
 *      GYs(...,boolean)V. No-oping it keeps the message in the open thread.
 *   2. REFRESH/SYNC: the thread-entry reconcile X/01gH.A0C(01gH,List,List,List,List,List)V rebuilds
 *      the live message list from the server response; a message the server dropped ends up in the
 *      reconcile's "removed" out-list and is erased from the live list — bypassing GYr. So on
 *      refresh the kept message vanishes again.
 *
 * Fix: capture the id of every message we block at GYr/GYs into a protected set, then in A0C move
 * any protected message back out of the "removed" list into the live list so it survives sync.
 *
 * Robustness: no obfuscated X.* names are hardcoded. The store is anchored by its "Posted
 * RemoveThreadEvent..." string; the reconcile by its "...deleted locally or not" string. Inside A0C
 * the live list is found by identity (the List arg that IS a field on the 01gH instance, i.e. the
 * one reconcile rewrites in place); protected messages are matched by scanning each candidate
 * message's String fields for an id present in the protected set (so per-version id-field renames
 * don't matter).
 *
 * Protected ids (and their thread) are saved to Instagram's filesDir, so after a restart the kept
 * message is still recognised on its next reconcile and gets its "Unsent" marker back.
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

    /** Message ids we blocked from local removal this session (bounded). */
    private static final Set<String> protectedIds = Collections.synchronizedSet(new LinkedHashSet<String>() {
        @Override public boolean add(String e) {
            if (size() >= 500) { java.util.Iterator<String> it = iterator(); if (it.hasNext()) { it.next(); it.remove(); } }
            return super.add(e);
        }
    });

    /** message id -> thread id, captured from the DirectThreadKey at removal time (bounded). */
    private static final java.util.Map<String, String> threadIdByMsg =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, String>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 500; }
            });

    private static final String IDS_FILE = "ie_unsent_ids.json";
    private static volatile boolean idsLoaded = false;

    /** Restores the ids protected in earlier sessions (once, lazily: the app context must exist). */
    private static void ensureIdsLoaded() {
        if (idsLoaded) return;
        synchronized (protectedIds) {
            if (idsLoaded) return;
            java.io.File f = idsFile();
            if (f == null) return;
            idsLoaded = true;
            if (!f.exists()) return;
            try {
                org.json.JSONObject o = new org.json.JSONObject(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String id = it.next();
                    protectedIds.add(id);
                    String th = o.optString(id, "");
                    if (!th.isEmpty()) threadIdByMsg.put(id, th);
                }
                ModuleLog.line("(IE|KeepUnsent) restored " + o.length() + " protected id(s)");
            } catch (Throwable t) {
                ModuleLog.line("(IE|KeepUnsent) ids load failed: " + t.getMessage());
            }
        }
    }

    private static void persistIds() {
        java.io.File f = idsFile();
        if (f == null) return;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            synchronized (protectedIds) {
                for (String id : protectedIds) {
                    String th = threadIdByMsg.get(id);
                    o.put(id, th == null ? "" : th);
                }
            }
            ps.reso.instaeclipse.utils.core.AtomicFiles.write(f, o.toString());
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ids save failed: " + t.getMessage());
        }
    }

    private static java.io.File idsFile() {
        android.content.Context ctx = android.app.AndroidAppHelper.currentApplication();
        return ctx == null ? null : new java.io.File(ctx.getFilesDir(), IDS_FILE);
    }

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

    /** Cached kept-message objects, SCOPED PER thread-store instance (the 01gH passed to reconcile),
     *  so a kept message is only ever re-injected into its OWN thread — never leaked into other
     *  chats. Outer map keyed by identity of the 01gH owner; inner map is id -> 018z message. */
    private static final java.util.Map<Object, java.util.Map<String, Object>> keptByThread =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** Thread id of the DM thread currently in the foreground (set on thread open). Used by the
     *  in-thread unsent button to show only THIS chat's log. */
    public static volatile String currentThreadId = null;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        hookRemovePrimitive(bridge, classLoader);
        hookReconcile(bridge, classLoader);
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

    // ── 1. LIVE removal: no-op + capture the id ──────────────────────────────────
    private void hookRemovePrimitive(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepUnsentMessages) return;
                ensureIdsLoaded();
                // Record the message-id String arg(s) (GYr/GYs pass id as the String params).
                String threadId = param.args.length > 0 ? threadIdOf(param.args[0]) : null;
                StringBuilder ids = new StringBuilder();
                for (Object a : param.args) {
                    if (a instanceof String && ((String) a).length() >= 6) {
                        protectedIds.add((String) a);
                        if (threadId != null) threadIdByMsg.put((String) a, threadId);
                        ids.append(' ').append(a);
                    }
                }
                ModuleLog.line("(IE|KeepUnsent) blocked local removal; thread=" + threadId + " ids=[" + ids.toString().trim() + "]");
                if (ids.length() > 0) persistIds();
                param.setResult(null); // skip local removal
                if (FeatureFlags.markUnsentInThread && param.args.length > 0) {
                    for (Object a : param.args) {
                        if (a instanceof String && ((String) a).length() >= 6) markLive(param.thisObject, param.args[0], (String) a);
                    }
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

    // ── 1b. Marker without reopening the chat ───────────────────────────────────
    /** The per-thread entry class (01eV on 452): reconcile's declaring class. */
    private static Class<?> entryClass;
    /** Thread change event (08fA on 452) built as (type, DirectThreadKey, added, removed, updated, boolean). */
    private static java.lang.reflect.Constructor<?> updateEventCtor;
    private static Object messageUpdatedType;

    /** Resolves Instagram's MESSAGE_UPDATED thread event, which re-renders a message in place. */
    private static void resolveUpdateEvent(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            for (org.luckypray.dexkit.result.ClassData cd : bridge.findClass(org.luckypray.dexkit.query.FindClass.create()
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create().usingStrings("MESSAGE_UPDATED", "MESSAGE_REMOVED")))) {
                Class<?> type = cd.getInstance(classLoader);
                if (!type.isEnum()) continue;
                Object updated = null;
                for (Object k : type.getEnumConstants()) if ("MESSAGE_UPDATED".equals(((Enum<?>) k).name())) updated = k;
                if (updated == null) continue;
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .name("<init>")
                        .paramTypes(type.getName(), DTK, "java.util.List", "java.util.List", "java.util.List", "boolean")))) {
                    updateEventCtor = md.getConstructorInstance(classLoader);
                    updateEventCtor.setAccessible(true);
                    messageUpdatedType = updated;
                    ModuleLog.line("(IE|KeepUnsent) ✅ update event → " + updateEventCtor.getDeclaringClass().getName());
                    return;
                }
            }
            ModuleLog.line("(IE|KeepUnsent) ⚠️ update event not found; marker shows on reopen");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ⚠️ update event: " + t);
        }
    }

    /**
     * Marks the message whose removal was just blocked and re-renders it right away: finds it in
     * the thread entry the store holds for {@code threadKey}, prefixes its text, and posts
     * MESSAGE_UPDATED on the store's event bus the way Instagram does after an edit.
     */
    @SuppressWarnings("unchecked")
    private static void markLive(Object store, Object threadKey, String id) {
        if (entryClass == null || store == null || threadKey == null) return;
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
            Object msg = null;
            for (Field f : entryClass.getDeclaredFields()) {
                if (msg != null) break;
                if (!List.class.isAssignableFrom(f.getType()) || Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(entry);
                if (!(v instanceof List)) continue;
                synchronized (v) {
                    for (Object cur : (List<Object>) v) {
                        if (id.equals(matchProtected(cur))) { msg = cur; break; }
                    }
                }
            }
            if (msg == null) return;
            java.util.Map<String, Object> owned = keptByThread.get(entry);
            if (owned == null) { owned = new java.util.LinkedHashMap<>(); keptByThread.put(entry, owned); }
            owned.put(id, msg);
            markUnsent(msg, id);
            postUpdated(store, threadKey, msg);
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ⚠️ live marker: " + t);
        }
    }

    /** Posts MESSAGE_UPDATED for {@code msg} on the store's event bus (a field whose type takes the event). */
    private static void postUpdated(Object store, Object threadKey, Object msg) throws Exception {
        if (updateEventCtor == null) return;
        Object event = updateEventCtor.newInstance(messageUpdatedType, threadKey, null, null,
                Collections.singletonList(msg), false);
        for (Field f : store.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            for (Method m : f.getType().getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() != void.class || p.length != 1 || !p[0].isInterface()
                        || !p[0].isAssignableFrom(event.getClass()) || Modifier.isStatic(m.getModifiers())) continue;
                f.setAccessible(true);
                Object bus = f.get(store);
                if (bus == null) continue;
                m.setAccessible(true);
                m.invoke(bus, event);
                ModuleLog.line("(IE|KeepUnsent) ✅ marker shown live");
                return;
            }
        }
    }

    // ── 2. REFRESH/SYNC reconcile: keep protected messages in the live list ──────
    private void hookReconcile(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = new XC_MethodHook() {
            // BEFORE: while the message is still present in the live list (or arriving), cache the
            // actual message object so we can re-inject it on later passes that no longer carry it.
            @Override
            @SuppressWarnings("unchecked")
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepUnsentMessages) return;
                ensureIdsLoaded();
                if (protectedIds.isEmpty()) return;
                Object[] args = param.args;
                if (args == null || args.length == 0 || args[0] == null) return;
                try {
                    for (int i = 1; i < args.length; i++) {
                        if (!(args[i] instanceof List)) continue;
                        for (Object msg : (List<Object>) args[i]) {
                            String hit = matchProtected(msg);
                            if (hit == null) continue;
                            java.util.Map<String, Object> owned = keptByThread.get(args[0]);
                            if (owned == null) { owned = new java.util.LinkedHashMap<>(); keptByThread.put(args[0], owned); }
                            owned.put(hit, msg);
                            markUnsent(msg, hit); // one-time: prepend an "Unsent" marker to its text
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // AFTER: ensure every cached protected message is present in the live list, and scrub it
            // from any removed/delta out-list so the removal delta won't fire.
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.keepUnsentMessages) return;
                Object[] args = param.args;
                if (args == null || args.length < 2 || args[0] == null) return;
                // Only act for THIS thread's own kept messages — never touch other chats.
                java.util.Map<String, Object> owned = keptByThread.get(args[0]);
                if (owned == null || owned.isEmpty()) return;
                try {
                    List<Object> liveList = findLiveList(args);
                    if (liveList == null) return;

                    // Scrub this thread's protected messages out of the non-live (delta) lists.
                    for (int i = 1; i < args.length; i++) {
                        if (!(args[i] instanceof List) || args[i] == liveList) continue;
                        java.util.Iterator<Object> it = ((List<Object>) args[i]).iterator();
                        while (it.hasNext()) {
                            String hit = matchProtected(it.next());
                            if (hit != null && owned.containsKey(hit)) it.remove();
                        }
                    }

                    // Re-inject this thread's cached protected messages if missing from its live list.
                    int added = 0;
                    synchronized (owned) {
                        for (Object msg : owned.values()) {
                            boolean present = false;
                            for (Object cur : liveList) if (cur == msg) { present = true; break; }
                            if (!present) { liveList.add(msg); added++; }
                        }
                    }
                    if (added > 0) ModuleLog.line("(IE|KeepUnsent) ✅ re-injected " + added + " kept msg(s) into this thread");
                } catch (Throwable t) {
                    ModuleLog.line("(IE|KeepUnsent) ❌ reconcile body: " + t);
                }
            }
        };

        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(RECONCILE_ANCHOR)));
            LinkedHashSet<Class<?>> classes = new LinkedHashSet<>();
            for (MethodData md : methods) {
                try { classes.add(md.getMethodInstance(classLoader).getDeclaringClass()); } catch (Throwable ignored) {}
            }
            for (Class<?> c : classes) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != void.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    // A0C: (01gH, List, List, List, List, List) — first param is the declaring class,
                    // the rest are Lists.
                    if (p.length != 6) continue;
                    if (p[0] != c) continue;
                    boolean rest = true;
                    for (int i = 1; i < 6; i++) if (!List.class.isAssignableFrom(p[i])) { rest = false; break; }
                    if (!rest) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, hook);
                    entryClass = c;
                    ModuleLog.line("(IE|KeepUnsent) ✅ reconcile hook → " + c.getName() + "." + m.getName());
                    resolveUpdateEvent(bridge, classLoader);
                    return;
                }
            }
            ModuleLog.line("(IE|KeepUnsent) ❌ reconcile method not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|KeepUnsent) ❌ reconcile resolve: " + t);
        }
    }

    /** The live message list = the List arg that IS a field on the 01gH instance (args[0]) —
     *  reconcile rewrites that field in place, so adding to it persists into the store. */
    @SuppressWarnings("unchecked")
    private static List<Object> findLiveList(Object[] args) {
        if (args[0] == null) return null;
        Set<Object> fieldLists = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field f : args[0].getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            try { Object v = f.get(args[0]); if (v != null) fieldLists.add(v); } catch (Throwable ignored) {}
        }
        for (int i = 1; i < args.length; i++) {
            if (args[i] instanceof List && fieldLists.contains(args[i])) return (List<Object>) args[i];
        }
        return null;
    }

    private static final String UNSENT_PREFIX = "Unsent: ";
    // Optional in-thread marker (#222), prepended to the kept message's text when enabled.
    private static final String UNSENT_MARK = "\uD83D\uDEAB Unsent \u00B7 ";
    /** Ids whose text we've already marked, so we prepend the marker exactly once. */
    private static final Set<String> markedIds = Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Capture the kept message's body text into the persistent per-thread log (once). We locate the
     * body String structurally — the longest String in the message graph that reads as human text
     * (has a letter, isn't an id / "mid.*" / lowercase_token / url / all-digits) — but we do NOT
     * mutate the in-thread message; the "Unsent" surfacing is the dedicated per-thread log button.
     */
    private static void markUnsent(Object msg, String id) {
        if (msg == null || !markedIds.add(id)) return;
        try {
            Object[] best = new Object[3]; // {ownerObj, Field, String value}
            int[] bestScore = {0};
            findBodyField(msg, 0, Collections.newSetFromMap(new IdentityHashMap<>()), best, bestScore);
            if (best[2] == null) { markedIds.remove(id); return; }
            String val = (String) best[2];
            String threadId = threadIdByMsg.get(id);
            // Embed the chat's username (best-effort) so the Unsent viewer can show it per folder.
            String who = ThreadNames.get(threadId);
            ps.reso.instaeclipse.utils.ghost.UnsentLog.add(threadId, who, val); // persistent per-thread log
            ModuleLog.line("(IE|KeepUnsent) ✅ logged (thread=" + threadId + " who=" + who + "): " + (val.length() > 24 ? val.substring(0, 24) + "…" : val));
            // Visual indicator in the chat itself (#222, opt-in): the kept message object is the one
            // IG keeps rendering, so prefixing its body field shows the marker on the bubble.
            if (FeatureFlags.markUnsentInThread && !val.startsWith(UNSENT_MARK)) {
                try {
                    ((Field) best[1]).set(best[0], UNSENT_MARK + val);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|KeepUnsent) ⚠️ marker: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            markedIds.remove(id);
            ModuleLog.line("(IE|KeepUnsent) ⚠️ log failed: " + t.getMessage());
        }
    }

    private static void findBodyField(Object obj, int depth, Set<Object> seen, Object[] best, int[] bestScore) {
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
                    if (looksLikeBody(s) && s.length() > bestScore[0]) {
                        bestScore[0] = s.length(); best[0] = obj; best[1] = f; best[2] = s;
                    }
                } else if (v != null && !f.getType().isPrimitive()) {
                    findBodyField(v, depth + 1, seen, best, bestScore);
                }
            }
        }
    }

    private static boolean looksLikeBody(String s) {
        if (s == null || s.length() < 2 || s.startsWith(UNSENT_PREFIX) || s.startsWith(UNSENT_MARK)) return false;
        if (s.startsWith("mid.") || s.contains("://") || s.contains("cdn")) return false;
        if (s.matches("[0-9]+") || s.matches("[a-z0-9_]+")) return false;   // ids / snake_case tokens
        // Reject enum/type tokens: strings with letters that are ALL uppercase (TEXT, ACTION_LOG,
        // RAVEN_MEDIA, EXPIRING_MEDIA, LINK, …). Real message text is not all-caps.
        if (s.equals(s.toUpperCase()) && !s.equals(s.toLowerCase())) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) { hasLetter = true; break; }
        return hasLetter;
    }

    /** Returns the protected id a message carries (in a String field, depth-limited), else null. */
    private static String matchProtected(Object msg) {
        return scanIds(msg, 0, Collections.newSetFromMap(new IdentityHashMap<>()), true);
    }

    private static java.util.List<String> candidateIds(Object msg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        collectIdStrings(msg, 0, Collections.newSetFromMap(new IdentityHashMap<>()), out);
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    /** Depth-limited scan of String fields; if matchOnly, returns first value in protectedIds. */
    private static String scanIds(Object obj, int depth, Set<Object> seen, boolean matchOnly) {
        if (obj == null || depth > 3 || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v;
                try { v = f.get(obj); } catch (Throwable e) { continue; }
                if (v instanceof String) {
                    if (protectedIds.contains(v)) return (String) v;
                } else if (v != null && !f.getType().isPrimitive()) {
                    String r = scanIds(v, depth + 1, seen, matchOnly);
                    if (r != null) return r;
                }
            }
        }
        return null;
    }

    private static void collectIdStrings(Object obj, int depth, Set<Object> seen, java.util.List<String> out) {
        if (obj == null || depth > 3 || out.size() > 20 || !seen.add(obj)) return;
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
                    if (s.length() >= 6 && s.length() <= 40 && !out.contains(s)) out.add(s);
                } else if (v != null && !f.getType().isPrimitive()) {
                    collectIdStrings(v, depth + 1, seen, out);
                }
            }
        }
    }

    private static String safeClass(Object o) { return o == null ? "null" : o.getClass().getName(); }
}
