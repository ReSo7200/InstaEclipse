package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.ThreadNames;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;

/**
 * Export a DM chat to an HTML file (#225): header button in the open chat.
 *
 * - Thread message store = class using "getUnseenMessageCount directMessage is null" (X.01eV on
 *   452, X.04r2 on 451); instances are remembered from its constructor. Its synchronized
 *   (boolean) -> ArrayList method returns the loaded messages (confirmed + pending, sorted).
 * - Each message is serialized with IG's own JSON serializer (static void (generator, message)
 *   method using "timestamp_in_micro" + "client_context") into a StringWriter-backed generator
 *   from the factory's (Writer) -> generator method, then read as JSON: text, user_id, timestamp,
 *   item_type. No obfuscated field names.
 * - Sender names: Users reachable from the store (participants) by id → username.
 * Only messages already loaded are exported — use Jump to first message first for a full chat.
 */
public class ChatExportHook {

    private static final String TAG = "(IE|ChatExport) ";
    private static final String BTN_TAG = "ie_chat_export_btn";
    private static final String USER = "com.instagram.user.model.User";
    private static final Map<Object, Boolean> STORES = Collections.synchronizedMap(new WeakHashMap<>());
    private static Method messagesMethod, serializer, factoryWriterMethod;
    private static Class<?> factoryClass;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> store = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("getUnseenMessageCount directMessage is null")))) {
                store = cl.loadClass(md.getClassName());
                break;
            }
            if (store == null) { ModuleLog.line(TAG + "❌ message store not found"); return; }
            for (Method m : store.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == boolean.class && m.getReturnType() == java.util.ArrayList.class) messagesMethod = m;
            }
            for (Constructor<?> c : store.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) { STORES.put(param.thisObject, Boolean.TRUE); }
                });
            }
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("timestamp_in_micro", "client_context").returnType("void").paramCount(2)))) {
                Method m = md.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers())) { serializer = m; break; }
            }
            if (serializer != null) {
                Class<?> gen = serializer.getParameterTypes()[0];
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes("java.io.Writer").returnType(gen.getName())))) {
                    Method m = md.getMethodInstance(cl);
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    try {
                        m.getDeclaringClass().getConstructor();
                        factoryWriterMethod = m;
                        factoryClass = m.getDeclaringClass();
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
            if (messagesMethod != null) messagesMethod.setAccessible(true);
            ModuleLog.line(TAG + "✅ store " + store.getName() + " messages=" + (messagesMethod != null)
                    + " serializer=" + (serializer != null) + " factory=" + (factoryClass != null ? factoryClass.getName() : "none"));
            if (messagesMethod != null && serializer != null && factoryClass != null) FeatureStatusTracker.setHooked("ChatExport");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /** Adds the export button next to the module's Unsent header button. */
    static void inject(Activity a) {
        if (!FeatureFlags.chatExport) return;
        try {
            View root = a.getWindow().getDecorView();
            if (root.findViewWithTag(BTN_TAG) != null) return;
            View anchor = root.findViewWithTag("ie_unsent_btn");
            if (anchor == null || !(anchor.getParent() instanceof ViewGroup parent)) return;
            android.widget.ImageView btn = new android.widget.ImageView(a);
            btn.setTag(BTN_TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_download, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            float d = a.getResources().getDisplayMetrics().density;
            int pad = (int) (9 * d);
            btn.setPadding(pad, pad, pad, pad);
            btn.setContentDescription(I18n.t(a, R.string.ig_chat_export));
            int sz = (int) (40 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> export(a));
            parent.addView(btn, parent.indexOfChild(anchor) + 1);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ inject: " + t);
        }
    }

    private static void export(Activity a) {
        String tracked = CurrentThreadTracker.currentThreadId();
        if (tracked == null) try { tracked = UnsentThreadButtonHook.openThread.get(); } catch (Throwable ignored) {}
        final String threadId = tracked;
        Object store = findStore(threadId);
        ModuleLog.line(TAG + "export thread=" + threadId + " store=" + (store != null));
        if (store == null || messagesMethod == null) {
            Toast.makeText(a, I18n.t(a, R.string.ig_chat_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, I18n.t(a, R.string.ig_chat_export_working), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<?> msgs = (List<?>) messagesMethod.invoke(store, true);
                Map<String, String> names = participants(store);
                String title = threadId != null ? ThreadNames.get(threadId) : null;
                if (title == null || title.isEmpty()) title = "chat";
                StringBuilder html = new StringBuilder();
                html.append("<!doctype html><meta charset=utf-8><title>").append(Html.escapeHtml(title))
                        .append("</title><style>body{font-family:sans-serif;max-width:720px;margin:auto;background:#111;color:#eee}"
                                + ".m{margin:6px 0;padding:8px 12px;border-radius:12px;background:#262626}"
                                + ".u{color:#9ad;font-weight:bold}.t{color:#888;font-size:12px}</style><h2>")
                        .append(Html.escapeHtml(title)).append("</h2>");
                int n = 0;
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                for (Object m : msgs) {
                    JSONObject j = toJson(m);
                    if (j == null) continue;
                    String uid = j.optString("user_id", "");
                    String who = names.getOrDefault(uid, uid);
                    String text = j.optString("text", "");
                    String type = j.optString("item_type", "");
                    long ts = 0;
                    try { ts = Long.parseLong(j.optString("timestamp", "0")) / 1000L; } catch (Throwable ignored) {}
                    if (text.isEmpty()) text = "[" + (type.isEmpty() ? "media" : type) + "]";
                    html.append("<div class=m><span class=u>").append(Html.escapeHtml(who)).append("</span> <span class=t>")
                            .append(ts > 0 ? fmt.format(new Date(ts)) : "").append("</span><br>")
                            .append(Html.escapeHtml(text).replace("\n", "<br>")).append("</div>");
                    n++;
                }
                String safe = title.replaceAll("[^A-Za-z0-9._-]", "_");
                String file = "chat_" + safe + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".html";
                try (OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openChatExport(a, file)) {
                    out.write(html.toString().getBytes(StandardCharsets.UTF_8));
                }
                final int count = n;
                ModuleLog.line(TAG + "exported " + count + " messages to " + file);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(a, I18n.t(a, R.string.ig_chat_export_done, count, file), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ export: " + t);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(a, I18n.t(a, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static JSONObject toJson(Object message) {
        try {
            if (!serializer.getParameterTypes()[1].isInstance(message)) return null;
            StringWriter sw = new StringWriter();
            Object factory = factoryClass.getConstructor().newInstance();
            Object gen = factoryWriterMethod.invoke(factory, sw);
            serializer.invoke(null, gen, message);
            if (gen instanceof java.io.Flushable f) f.flush();
            return new JSONObject(sw.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The live store for exactly this thread (its graph holds the thread id). Never guesses: stores of
     *  other chats (inbox prefetch) stay alive too, so without a confirmed match nothing is exported. */
    private static Object findStore(String threadId) {
        if (threadId == null) return null;
        Object[] all;
        synchronized (STORES) { all = STORES.keySet().toArray(); }
        for (Object s : all) if (s != null && graphHasString(s, threadId)) return s;
        return null;
    }

    private static boolean graphHasString(Object root, String needle) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{root, 0});
        while (!q.isEmpty() && seen.size() < 400) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof String s) { if (s.equals(needle)) return true; continue; }
            if (d >= 3 || o instanceof List) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                }
        }
        return false;
    }

    /** user id → username for every User reachable from the store. */
    private static Map<String, String> participants(Object store) {
        Map<String, String> out = new HashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{store, 0});
        while (!q.isEmpty() && seen.size() < 3000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { if (d < 4) for (Object it : l) q.add(new Object[]{it, d + 1}); continue; }
            if (o.getClass().getName().equals(USER)) {
                String u = UserUtils.callUsernameGetter(o);
                String id = userId(o);
                if (u != null && id != null) out.put(id, u);
                continue;
            }
            if (d >= 4) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                }
        }
        return out;
    }

    /** The User's id: its only non-static final String field (A06 on 452). */
    private static String userId(Object user) {
        for (Field f : user.getClass().getDeclaredFields()) {
            if (f.getType() != String.class || Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
            try { f.setAccessible(true); Object v = f.get(user); if (v instanceof String s && s.matches("\\d+")) return s; } catch (Throwable ignored) {}
        }
        return null;
    }
}
