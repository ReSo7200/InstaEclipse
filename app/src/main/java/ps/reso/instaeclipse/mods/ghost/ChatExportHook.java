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

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
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
    private static final String SESSION = "com.instagram.common.session.UserSession";
    /** the viewer's user id, found next to the participants (set by {@link #participants}) */
    private static volatile String selfId;
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
                    protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method); STORES.put(param.thisObject, Boolean.TRUE); }
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

    /** Adds "Export chat" to the module's thread header menu. */
    static void inject(Activity a) {
        if (!FeatureFlags.chatExport || ThreadHeaderMenu.has(a, "export")) return;
        ThreadHeaderMenu.add(a, "export", I18n.t(a, R.string.ig_chat_export), () -> ask(a));
    }

    private static boolean loadAll = true;
    private static boolean downloadMedia = true;

    /** Export dialog: optionally scroll to the first message first so the whole chat is loaded, and
     *  optionally download photos/videos/voice/GIFs/shared previews into the export's own folder. */
    private static void ask(Activity a) {
        ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(a)
                .title(I18n.t(a, R.string.ig_chat_export));
        android.widget.CheckBox box = dlg.checkBox(I18n.t(a, R.string.ig_chat_export_load_all), loadAll);
        android.widget.CheckBox mediaBox = dlg.checkBox(I18n.t(a, R.string.ig_chat_export_media), downloadMedia);
        dlg.secondary(I18n.t(a, R.string.ig_dialog_cancel), null);
        dlg.primary(I18n.t(a, R.string.ig_chat_export), () -> {
            loadAll = box.isChecked();
            downloadMedia = mediaBox.isChecked();
            if (loadAll) JumpToFirstMessageHook.run(a, () -> export(a));
            else export(a);
        });
        dlg.show();
    }

    private static void export(Activity a) {
        String tracked = CurrentThreadTracker.currentThreadId();
        if (tracked == null) try { tracked = UnsentThreadButtonHook.openThread.get(); } catch (Throwable ignored) {}
        final String threadId = tracked;
        Object store = findStore(threadId);
        ModuleLog.line(TAG + "export thread=" + threadId + " store=" + (store != null));
        if (store == null || messagesMethod == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_none), Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean withMedia = downloadMedia;
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_working), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<?> msgs = (List<?>) messagesMethod.invoke(store, true);
                Map<String, String> names = participants(store);
                String title = threadId != null ? ThreadNames.resolve(threadId) : null;
                if (title == null || title.isEmpty()) title = "chat";
                String user = chatUsername(msgs, names);
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                // first pass: JSON per message + item id -> message (for reply quotes)
                List<Object[]> rows = new ArrayList<>();
                Map<String, JSONObject> byId = new HashMap<>();
                for (Object m : msgs) {
                    JSONObject j = toJson(m);
                    if (j == null) continue;
                    rows.add(new Object[]{m, j});
                    String id = j.optString("item_id", "");
                    if (!id.isEmpty()) byId.put(id, j);
                }

                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String safe = (user != null ? user : title).replaceAll("[^A-Za-z0-9._-]", "_");
                String folder = "chat_" + safe + "_" + stamp;   // the export's own folder
                String file = folder + ".html";

                // media manifest (no in-process download — Instagram's process can't write to the
                // user's SAF folder, so the companion service fetches + saves these): row index ->
                // {name, kind}, plus parallel url/name/mime lists handed to the service.
                Map<Integer, String[]> media = new HashMap<>();
                List<String> mUrls = new ArrayList<>(), mNames = new ArrayList<>(), mMimes = new ArrayList<>();
                if (withMedia) {
                    for (int i = 0; i < rows.size(); i++) {
                        JSONObject j = (JSONObject) rows.get(i)[1];
                        String[] mm = mediaForMessage(j, j.optString("item_type", ""));
                        if (mm == null || !mm[0].startsWith("https://")) continue;
                        // Extension from the final mime so the saved name matches the HTML link.
                        String mime = mimeFor(mm[1], mm[2]);
                        String name = String.format(Locale.US, "%04d_%s%s", i + 1, mm[1], extForMime(mime));
                        media.put(i, new String[]{name, mm[1]});
                        mUrls.add(mm[0]); mNames.add(name); mMimes.add(mime);
                    }
                }

                // Who is "me": in a 1:1 chat the title is the other person, so the viewer is the other
                // sender → their messages align right (self-coloured bubbles).
                java.util.LinkedHashSet<String> senders = new java.util.LinkedHashSet<>();
                for (Object[] r : rows) senders.add(names.getOrDefault(((JSONObject) r[1]).optString("user_id", ""), ""));
                String selfName = selfId != null ? names.get(selfId) : null;
                if (selfName == null && senders.size() == 2) for (String s : senders) if (!s.isEmpty() && !s.equalsIgnoreCase(title)) selfName = s;
                SimpleDateFormat daySep = new SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault());

                StringBuilder body = new StringBuilder();
                long first = 0, last = 0, lastDay = -1;
                Map<String, Integer> perUser = new LinkedHashMap<>();
                for (int i = 0; i < rows.size(); i++) {
                    Object[] r = rows.get(i);
                    JSONObject j = (JSONObject) r[1];
                    String uid = j.optString("user_id", "");
                    String who = names.getOrDefault(uid, uid);
                    perUser.merge(who, 1, Integer::sum);
                    long ts = timestampMs(j);
                    if (ts > 0) {
                        if (first == 0) first = ts;
                        last = ts;
                        long day = (ts + java.util.TimeZone.getDefault().getOffset(ts)) / 86400000L;
                        if (day != lastDay) { lastDay = day; body.append("<div class=date-sep><span>").append(esc(daySep.format(new Date(ts)))).append("</span></div>"); }
                    }
                    boolean self = selfName != null && who.equals(selfName);
                    JSONObject replied = repliedTo(r[0], j.optString("item_id", ""), byId);
                    body.append(messageHtml(j, self, who, ts, replied, names, media.get(i), fmt));
                }
                int n = rows.size();
                StringBuilder html = new StringBuilder();
                html.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>")
                        .append(esc(title)).append("</title><style>").append(CHAT_CSS).append("</style></head><body><div class=wrap>")
                        .append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlBanner("Chat export"))
                        .append("<div class=head><h1>").append(esc(title))
                        .append(user != null && !user.equalsIgnoreCase(title) ? " <span class=sub>@" + esc(user) + "</span>" : "").append("</h1><div class=sub>").append(n).append(" messages");
                if (first > 0) html.append(" · ").append(fmt.format(new Date(first))).append(" – ").append(fmt.format(new Date(last)));
                html.append("</div><div class=chips>");
                for (Map.Entry<String, Integer> e : perUser.entrySet())
                    html.append("<span class=chip>").append(esc(e.getKey())).append(" · ").append(e.getValue()).append("</span>");
                html.append("</div></div>").append(body)
                        .append(ps.reso.instaeclipse.utils.core.ExportBrand.htmlFooter()).append("</div></body></html>");

                final int count = n;
                // Preferred path: hand the HTML + media list to the companion app's DownloadSaveService,
                // which owns the user's SAF folder (Instagram's own process can't write there, so a
                // local write would land in Download/ and be hidden from file managers). The service
                // writes everything into <SAF folder>/chats/<folder>/ and shows its own notification.
                String saf = FeatureFlags.downloaderCustomUri;
                boolean delegated = false;
                if (saf != null && !saf.isEmpty()) {
                    try {
                        android.content.Intent it = new android.content.Intent();
                        it.setClassName("ps.reso.instaeclipse",
                                "ps.reso.instaeclipse.mods.media.DownloadSaveService");
                        it.putExtra("chatExportSubdir", "chats/" + folder);
                        it.putExtra("htmlFilename", file);
                        it.putExtra("htmlB64", android.util.Base64.encodeToString(
                                html.toString().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP));
                        it.putExtra("saveUri", saf);
                        it.putExtra("messageCount", count);
                        if (!mUrls.isEmpty()) {
                            it.putExtra("mediaUrls", mUrls.toArray(new String[0]));
                            it.putExtra("mediaNames", mNames.toArray(new String[0]));
                            it.putExtra("mediaMimes", mMimes.toArray(new String[0]));
                        }
                        ps.reso.instaeclipse.utils.ipc.CompanionBridge.downloadLegacy(a, it, error -> {
                            if (error != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                    android.widget.Toast.makeText(a, error, android.widget.Toast.LENGTH_LONG).show());
                        });
                        delegated = true;
                        ModuleLog.line(TAG + "delegated export (" + count + " msgs, " + mUrls.size()
                                + " media) to companion service: " + folder);
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "delegate failed, writing locally: " + t);
                    }
                }

                if (!delegated) {
                    // No SAF folder set: write in-process via MediaStore (lands in Download/… and is
                    // only reachable via the gallery or a PC, but at least the data is saved).
                    int saved = 0;
                    for (int i = 0; i < mUrls.size(); i++) {
                        File tmp = null;
                        try {
                            tmp = File.createTempFile("ie_chat_media", null, a.getCacheDir());
                            boolean ok;
                            try (OutputStream fout = new FileOutputStream(tmp)) { ok = httpGet(mUrls.get(i), fout); }
                            if (ok && tmp.length() > 0) {
                                try (InputStream in = new FileInputStream(tmp);
                                     OutputStream mout = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook
                                             .openChatExportFile(a, folder, mNames.get(i), mMimes.get(i))) {
                                    byte[] buf = new byte[32768];
                                    int rd;
                                    while ((rd = in.read(buf)) != -1) mout.write(buf, 0, rd);
                                }
                                saved++;
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            if (tmp != null) tmp.delete();
                        }
                    }
                    try (OutputStream out = ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.openChatExportFile(a, folder, file, "text/html")) {
                        out.write(html.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    final int fsaved = saved;
                    ModuleLog.line(TAG + "exported locally " + count + " messages, " + fsaved + " media to " + folder);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (fsaved > 0)
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_done_media, count, fsaved, folder), Toast.LENGTH_LONG).show();
                        else
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_chat_export_done, count, file), Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ export: " + t);
                new Handler(Looper.getMainLooper()).post(() ->
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_toast_download_failed, String.valueOf(t.getMessage())), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** Downloads a URL straight into {@code out}. Returns false (leaving {@code out} to the caller) on any error. */
    private static boolean httpGet(String url, OutputStream out) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.connect();
            if (conn.getResponseCode() / 100 != 2) return false;
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** {url, kind, ext} of the best downloadable media in a message, or null. kind ∈
     *  photo / video / voice / gif / preview (shared-content thumbnail). */
    static String[] mediaForMessage(JSONObject j, String type) {
        Object payload = j.opt(type);
        if (!(payload instanceof JSONObject) && !"text".equals(type)) {
            Object m = j.opt("media");
            if (m != null) payload = m;
        }
        if (payload == null) payload = j;
        switch (type) {
            case "media": case "raven_media": case "visual_media": {
                boolean video = "2".equals(first(payload, "media_type")) || nodeFor(payload, "video_versions", 0) != null;
                if (video) {
                    String u = deepUrl(nodeFor(payload, "video_versions", 0), 0);
                    if (!u.isEmpty()) return new String[]{u, "video", ".mp4"};
                }
                String u = deepUrl(nodeFor(payload, "image_versions2", 0), 0);
                if (u.isEmpty()) u = deepUrl(payload, 0);
                if (!u.isEmpty()) return new String[]{u, "photo", ".jpg"};
                return null;
            }
            case "voice_media": {
                String u = first(payload, "audio_src");
                if (u.isEmpty()) u = deepUrl(nodeFor(payload, "audio", 0), 0);
                if (u.isEmpty()) u = deepUrl(payload, 0);
                if (!u.isEmpty()) return new String[]{u, "voice", extFromUrl(u, ".mp4")};
                return null;
            }
            case "animated_media": {
                String u = deepUrl(payload, 0);
                if (u.isEmpty()) return null;
                return new String[]{u, u.contains(".mp4") ? "video" : "gif", extFromUrl(u, ".gif")};
            }
            case "media_share": case "clip": case "felix_share":
            case "xma_media_share": case "xma_clip": case "xma_felix_share":
            case "story_share": case "xma_story_share":
            case "reel_share": case "xma_reel_share":
            case "xma_reel_mention": case "xma_story_mention":
            case "profile": case "xma_profile":
            case "link": case "xma_link": case "generic_xma": case "xma": {
                String u = deepUrl(nodeFor(payload, "image_versions2", 0), 0);
                if (u.isEmpty()) u = deepUrl(payload, 0);
                if (!u.isEmpty()) return new String[]{u, "preview", extFromUrl(u, ".jpg")};
                return null;
            }
            default:
                return null;
        }
    }

    /** First https URL in a JSON subtree (url / uri / audio_src etc. preferred). */
    private static String deepUrl(Object o, int d) {
        if (o == null || d > 6) return "";
        if (o instanceof JSONObject jo) {
            for (String k : new String[]{"url", "uri", "image_url", "audio_src", "playable_url", "src"}) {
                String v = jo.optString(k, "");
                if (v.startsWith("https://")) return v;
            }
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = deepUrl(jo.opt(it.next()), d + 1);
                if (!r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 15; i++) {
                String r = deepUrl(ja.opt(i), d + 1);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    /** First object/array stored under {@code key} anywhere in a JSON subtree. */
    private static Object nodeFor(Object o, String key, int d) {
        if (o == null || d > 6) return null;
        if (o instanceof JSONObject jo) {
            Object direct = jo.opt(key);
            if (direct instanceof JSONObject || direct instanceof JSONArray) return direct;
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                Object r = nodeFor(jo.opt(it.next()), key, d + 1);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 15; i++) {
                Object r = nodeFor(ja.opt(i), key, d + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** File extension from a URL's path (".jpg"), or {@code fallback}. */
    private static String extFromUrl(String url, String fallback) {
        try {
            String p = url.split("\\?")[0];
            int dot = p.lastIndexOf('.'), slash = p.lastIndexOf('/');
            if (dot > slash && dot >= 0 && p.length() - dot <= 5) return p.substring(dot).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static String mimeFor(String kind, String ext) {
        switch (kind) {
            case "video": return "video/mp4";
            case "voice": return ".webm".equals(ext) ? "audio/webm" : "audio/mp4";
            case "gif":   return ".webp".equals(ext) ? "image/webp" : "image/gif";
            default:      return ".png".equals(ext) ? "image/png" : ".webp".equals(ext) ? "image/webp" : "image/jpeg";
        }
    }

    /** The canonical extension for a mime, so the saved file name matches what MediaStore expects. */
    private static String extForMime(String mime) {
        switch (mime) {
            case "video/mp4":  return ".mp4";
            case "audio/mp4":  return ".m4a";
            case "audio/webm": return ".webm";
            case "image/gif":  return ".gif";
            case "image/webp": return ".webp";
            case "image/png":  return ".png";
            default:           return ".jpg";
        }
    }

    /** Inline element referencing a media file by name (the companion service rewrites the src to a
     *  base64 data URI so the page is self-contained; onerror hides any media that failed to save). */
    private static String mediaHtml(String rel, String kind) {
        switch (kind) {
            case "video": return "<video class=media controls preload=none src=\"" + rel + "\" onerror=\"this.remove()\"></video>";
            case "voice": return "<audio class=media controls preload=none src=\"" + rel + "\" onerror=\"this.remove()\"></audio>";
            default:      return "<img class=media loading=lazy src=\"" + rel + "\" onerror=\"this.remove()\">"; // photo, gif, preview
        }
    }

    private static String esc(String s) { return Html.escapeHtml(s == null ? "" : s); }

    private static final int[] AVATAR = {0xE1306C, 0x833AB4, 0xF77737, 0x0095F6, 0x00A86B, 0xC13584, 0xFD1D1D, 0x5851DB};

    private static String avatarColor(String u) {
        return String.format(Locale.US, "%06X", AVATAR[((u == null ? 0 : u.hashCode()) & 0x7fffffff) % AVATAR.length]);
    }

    private static String initial(String u) {
        return u == null || u.isEmpty() ? "?" : u.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static Object payloadOf(JSONObject j, String type) {
        Object payload = j.opt(type);
        if (!(payload instanceof JSONObject) && !"text".equals(type)) { Object m = j.opt("media"); if (m != null) payload = m; }
        return payload;
    }

    private static String stripBrackets(String s) {
        s = s.trim();
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return s;
    }

    /** One message as a chat row (bubble), or a centered system line for action/call logs. */
    private static String messageHtml(JSONObject j, boolean self, String who, long ts,
                                      JSONObject replied, Map<String, String> names, String[] mrel, SimpleDateFormat fmt) {
        String type = j.optString("item_type", "");
        if (type.equals("action_log") || type.equals("video_call_event")
                || type.equals("placeholder") || type.equals("expired_placeholder")) {
            String t = stripBrackets(describe(j));
            return "<div class=sys><span>" + esc(t) + (ts > 0 ? " · " + esc(fmt.format(new Date(ts))) : "") + "</span></div>";
        }
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"row").append(self ? " me" : "").append("\">");
        if (!self) b.append("<div class=av style=\"background:#").append(avatarColor(who)).append("\">").append(esc(initial(who))).append("</div>");
        b.append("<div class=bub>");
        if (!self) b.append("<div class=nm>").append(esc(who)).append("</div>");
        if (replied != null) {
            String ru = replied.optString("user_id", "");
            b.append("<div class=q><b>").append(esc(names.getOrDefault(ru, ru))).append("</b> ")
             .append(esc(clip(describe(replied), 100))).append("</div>");
        }
        b.append(contentHtml(j, type, mrel));
        b.append(editHistory(j, fmt));
        b.append("<div class=meta>");
        if (ts > 0) b.append(esc(fmt.format(new Date(ts))));
        if (j.optInt("edit_count", 0) > 0 || j.has("edit_history")) b.append("<span class=badge>edited</span>");
        if (j.optBoolean("is_pinned", false)) b.append("<span class=badge>📌 pinned</span>");
        if (j.optBoolean("show_forward_attribution", false)) b.append("<span class=badge>↪ forwarded</span>");
        b.append("</div>");
        String react = reactions(j, names);
        if (!react.isEmpty()) {
            b.append("<div class=react>");
            for (String part : react.split(", ")) b.append("<span class=rx>").append(esc(part)).append("</span>");
            b.append("</div>");
        }
        return b.append("</div></div>").toString();
    }

    /** The message body HTML, styled per item type (media, shared card, story reply, like, text). */
    private static String contentHtml(JSONObject j, String type, String[] mrel) {
        String text = j.optString("text", "");
        switch (type) {
            case "like":
                return "<div class=like>❤️</div>";
            case "media": case "raven_media": case "visual_media": case "animated_media": case "voice_media": {
                StringBuilder s = new StringBuilder();
                if (mrel != null) s.append(mediaHtml(mrel[0], mrel[1]));
                else s.append("<div class=ph>").append(esc(describe(j))).append("</div>");
                if (!text.isEmpty()) s.append("<div class=txt>").append(esc(text).replace("\n", "<br>")).append("</div>");
                return s.toString();
            }
            case "media_share": case "clip": case "felix_share":
            case "xma_media_share": case "xma_clip": case "xma_felix_share":
            case "story_share": case "xma_story_share":
                return sharedCard(j, type, mrel, text, false);
            case "reel_share": case "xma_reel_share":
                return sharedCard(j, type, mrel, text, true);
            case "xma_reel_mention": case "xma_story_mention": {
                String s = "<div class=card><div class=ci><div class=cl>✨ Mentioned you in a story</div></div></div>";
                return mrel != null ? "<div class=card><img loading=lazy src=\"" + mrel[0] + "\" onerror=\"this.remove()\"><div class=ci><div class=cl>✨ Story mention</div></div></div>" : s;
            }
            case "profile": case "xma_profile": {
                String u = first(payloadOf(j, type), "username");
                return "<div class=card><div class=ci><div class=cl>👤 Shared profile</div>"
                        + (u.isEmpty() ? "" : "<div class=cu>@" + esc(u) + "</div>") + "</div></div>";
            }
            case "text":
                return "<div class=txt>" + esc(text).replace("\n", "<br>") + "</div>";
            default: {
                StringBuilder s = new StringBuilder();
                if (mrel != null) s.append(mediaHtml(mrel[0], mrel[1]));
                String d = text.isEmpty() ? describe(j) : text;
                if (!d.isEmpty()) s.append("<div class=txt>").append(esc(d).replace("\n", "<br>")).append("</div>");
                return s.toString();
            }
        }
    }

    /** A card for a shared post/reel/story, or a story-reply card (preview + label + @user + caption). */
    private static String sharedCard(JSONObject j, String type, String[] mrel, String text, boolean storyReply) {
        Object payload = payloadOf(j, type);
        String user = first(payload, "username");
        String caption = first(findObj(payload, "caption", 0), "text");
        String code = first(payload, "code");
        boolean story = type.contains("story");
        boolean reel = storyReply || "clips".equals(first(payload, "product_type")) || findObj(payload, "clips_metadata", 0) != null;
        String label = storyReply ? "↩ Story reply" : story ? "↗ Shared story" : reel ? "↗ Shared reel" : "↗ Shared post";
        String link = code.isEmpty() ? "" : "https://www.instagram.com/" + (reel && !storyReply ? "reel/" : "p/") + code + "/";
        StringBuilder s = new StringBuilder();
        boolean hasPrev = mrel != null && "preview".equals(mrel[1]);
        s.append(link.isEmpty() ? "<div class=card>" : "<a class=card href=\"" + link + "\" target=_blank rel=noopener>");
        if (hasPrev) s.append("<img loading=lazy src=\"").append(mrel[0]).append("\" onerror=\"this.remove()\">");
        s.append("<div class=ci><div class=cl>").append(label).append("</div>");
        if (!user.isEmpty()) s.append("<div class=cu>").append(storyReply ? "to @" : "@").append(esc(user)).append("</div>");
        if (!caption.isEmpty()) s.append("<div class=cc>").append(esc(clip(caption, 220))).append("</div>");
        s.append("</div>").append(link.isEmpty() ? "</div>" : "</a>");
        // the reply text / any accompanying caption text sits under the card
        if (!text.isEmpty()) s.append("<div class=txt>").append(esc(text).replace("\n", "<br>")).append("</div>");
        return s.toString();
    }

    private static final String CHAT_CSS =
            ":root{--bg:#f4f4f6;--panel:#fff;--text:#0b0b0c;--sub:#8a8a92;--line:#e7e7ec;--other:#fff;--me:#3797f0;--meTx:#fff;--accent:#c13584}"
            + "@media(prefers-color-scheme:dark){:root{--bg:#0a0a0b;--panel:#161618;--text:#f2f2f3;--sub:#9a9aa0;--line:#262629;--other:#232326;--me:#3797f0;--meTx:#fff;--accent:#e06ebb}}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}"
            + ".wrap{max-width:760px;margin:0 auto;padding:16px}"
            + ".head{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:16px 18px;margin-bottom:8px}"
            + ".head h1{margin:0;font-size:21px;word-break:break-word}.head .sub{color:var(--sub);font-size:13px;margin-top:4px}"
            + ".chips{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}"
            + ".chip{background:var(--bg);border:1px solid var(--line);border-radius:999px;padding:4px 12px;font-size:13px;color:var(--sub)}"
            + ".date-sep{text-align:center;margin:18px 0 8px}.date-sep span{background:var(--panel);border:1px solid var(--line);color:var(--sub);font-size:12px;border-radius:999px;padding:4px 12px}"
            + ".row{display:flex;gap:8px;margin:8px 0;align-items:flex-end}.row.me{flex-direction:row-reverse}"
            + ".av{flex:none;width:30px;height:30px;border-radius:50%;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;font-size:13px}"
            + ".bub{max-width:78%;background:var(--other);border:1px solid var(--line);border-radius:18px;padding:9px 13px;overflow-wrap:anywhere}"
            + ".row.me .bub{background:var(--me);color:var(--meTx);border-color:transparent}"
            + ".nm{font-weight:700;font-size:12.5px;color:var(--accent);margin-bottom:3px}"
            + ".txt{white-space:normal}.ph{opacity:.8;font-style:italic}"
            + ".meta{font-size:11px;color:var(--sub);margin-top:5px;display:flex;gap:6px;flex-wrap:wrap;align-items:center}"
            + ".row.me .meta{color:rgba(255,255,255,.82);justify-content:flex-end}"
            + ".badge{font-size:10.5px;padding:1px 7px;border-radius:999px;background:var(--bg);border:1px solid var(--line);color:var(--sub)}"
            + ".row.me .badge{background:rgba(255,255,255,.2);border-color:transparent;color:#fff}"
            + ".q{border-left:3px solid var(--accent);padding:4px 9px;margin:0 0 6px;font-size:13px;color:var(--sub);background:var(--bg);border-radius:9px}"
            + ".row.me .q{background:rgba(255,255,255,.16);color:rgba(255,255,255,.92);border-left-color:rgba(255,255,255,.65)}"
            + ".media{display:block;max-width:100%;max-height:420px;border-radius:14px;margin:3px 0}audio.media{width:min(260px,70vw);height:40px}"
            + ".react{display:flex;gap:4px;flex-wrap:wrap;margin-top:5px}.row.me .react{justify-content:flex-end}"
            + ".rx{font-size:12px;background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:1px 8px;color:var(--text)}"
            + ".card{display:block;text-decoration:none;color:inherit;border:1px solid var(--line);border-radius:14px;overflow:hidden;margin:3px 0;background:var(--bg)}"
            + ".row.me .card{background:rgba(255,255,255,.14);border-color:rgba(255,255,255,.28)}"
            + ".card img{display:block;width:100%;max-height:280px;object-fit:cover}.card .ci{padding:9px 12px}"
            + ".card .cl{font-size:11px;text-transform:uppercase;letter-spacing:.4px;color:var(--accent);font-weight:800}"
            + ".row.me .card .cl{color:#fff}.card .cu{font-weight:700;font-size:13px;margin-top:2px}"
            + ".card .cc{font-size:12.5px;color:var(--sub);margin-top:3px}.row.me .card .cc{color:rgba(255,255,255,.86)}"
            + ".sys{text-align:center;color:var(--sub);font-size:12.5px;margin:12px 0}.sys span{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:4px 12px}"
            + ".e{font-size:12px;opacity:.78;margin-top:4px;border-left:2px solid var(--line);padding-left:8px}.row.me .e{border-left-color:rgba(255,255,255,.5)}"
            + ".like{font-size:30px;line-height:1.1}";

    private static long timestampMs(JSONObject j) {
        try { return Long.parseLong(j.optString("timestamp", "0")) / 1000L; } catch (Throwable t) { return 0; }
    }

    private static String clip(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Readable text for a message: its text, or a description of what it holds (no media files). */
    static String describe(JSONObject j) {
        String text = j.optString("text", "");
        String type = j.optString("item_type", "");
        Object payload = j.opt(type);
        // shared posts/clips (xma_*) keep the shared media under "media"
        if (!(payload instanceof JSONObject) && !type.equals("text")) payload = j.opt("media");
        String extra = switch (type) {
            case "text" -> "";
            case "like" -> "❤️";
            case "link" -> first(payload, "text", "link_url");
            case "media" -> mediaKind(payload, false);
            case "raven_media", "visual_media" -> disappearing(j.opt("visual_media") != null ? j.opt("visual_media") : payload);
            case "voice_media" -> "[voice message" + duration(payload) + "]";
            case "animated_media" -> "[GIF]";
            case "media_share", "clip", "felix_share", "xma_media_share", "xma_clip", "xma_felix_share" ->
                    shared(payload, "post");
            case "story_share", "xma_story_share" -> shared(payload, "story");
            case "reel_share", "xma_reel_share" -> ("[story reply] " + first(payload, "text")).trim();
            case "xma_reel_mention", "xma_story_mention" -> "[mentioned you in a story]";
            case "profile", "xma_profile" -> {
                String u = first(payload, "username");
                yield u.isEmpty() ? "[shared profile]" : "[shared profile] @" + u;
            }
            case "generic_xma", "xma", "xma_link" -> {
                String t = first(payload, "title_text", "header_title_text", "target_url");
                yield t.isEmpty() ? "[shared content]" : "[shared] " + t;
            }
            case "action_log" -> "[" + first(payload, "description") + "]";
            case "video_call_event" -> "[" + first(payload, "description") + "]";
            case "placeholder", "expired_placeholder" -> "[" + first(payload, "message", "title") + "]";
            default -> {
                String t = first(payload, "title_text", "text", "description", "target_url");
                yield type.isEmpty() ? "" : "[" + type.replace('_', ' ') + "]" + (t.isEmpty() ? "" : " " + t);
            }
        };
        if (text.isEmpty()) return extra.isEmpty() ? "[" + (type.isEmpty() ? "message" : type) + "]" : extra;
        if (extra.isEmpty() || type.equals("link") || extra.equals(text)) return text;
        return extra + "\n" + text;
    }

    private static String mediaKind(Object payload, boolean once) {
        String kind = "2".equals(first(payload, "media_type")) ? "video" : "photo";
        return "[" + (once ? "disappearing " : "") + kind + "]";
    }

    /** "[disappearing photo · opened]" (RAVEN_OPENED / REPLAYED / DELIVERED ...). */
    private static String disappearing(Object visual) {
        String s = mediaKind(visual, true);
        String state = visual instanceof JSONObject v && v.optJSONObject("expiring_media_action_summary") != null
                ? v.optJSONObject("expiring_media_action_summary").optString("type", "") : "";
        if (state.startsWith("RAVEN_")) state = state.substring(6);
        return state.isEmpty() ? s : s.substring(0, s.length() - 1) + " · " + state.toLowerCase(Locale.ROOT) + "]";
    }

    private static String duration(Object payload) {
        try {
            double ms = Double.parseDouble(first(payload, "duration"));
            return String.format(Locale.US, ", %.1fs", ms / 1000.0);
        } catch (Throwable t) { return ""; }
    }

    /** Earlier versions of an edited message, oldest first. */
    private static String editHistory(JSONObject j, SimpleDateFormat fmt) {
        JSONArray h = j.optJSONArray("edit_history");
        if (h == null || h.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length(); i++) {
            JSONObject e = h.optJSONObject(i);
            if (e == null) continue;
            long ts = e.optLong("timestamp", 0);
            b.append("<div class=e>✎ ").append(Html.escapeHtml(e.optString("body", "")));
            if (ts > 0) b.append(" <span class=t>").append(fmt.format(new Date(ts))).append("</span>");
            b.append("</div>");
        }
        return b.toString();
    }

    /** "[shared post by @user] link + caption" for shared posts/reels/stories. */
    private static String shared(Object payload, String what) {
        String user = first(payload, "username");
        String caption = first(findObj(payload, "caption", 0), "text");
        String code = first(payload, "code");
        boolean reel = "clips".equals(first(payload, "product_type")) || findObj(payload, "clips_metadata", 0) != null;
        StringBuilder b = new StringBuilder("[shared ").append(reel && what.equals("post") ? "reel" : what);
        if (!user.isEmpty()) b.append(" by @").append(user);
        b.append("]");
        if (!code.isEmpty()) b.append(" https://www.instagram.com/").append(reel ? "reel/" : "p/").append(code).append("/");
        if (!caption.isEmpty()) b.append("\n").append(clip(caption, 300));
        return b.toString();
    }

    /** First non-empty scalar for any of the keys, searched depth-first in a JSON tree. */
    private static String first(Object o, String... keys) {
        for (String k : keys) { String v = find(o, k, 0); if (v != null && !v.isEmpty()) return v; }
        return "";
    }

    private static String find(Object o, String key, int d) {
        if (o == null || d > 6) return null;
        if (o instanceof JSONObject jo) {
            Object v = jo.opt(key);
            if (v != null && v != JSONObject.NULL && !(v instanceof JSONObject) && !(v instanceof JSONArray)) return String.valueOf(v);
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = find(jo.opt(it.next()), key, d + 1);
                if (r != null && !r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 20; i++) {
                String r = find(ja.opt(i), key, d + 1);
                if (r != null && !r.isEmpty()) return r;
            }
        }
        return null;
    }

    private static Object findObj(Object o, String key, int d) {
        if (!(o instanceof JSONObject jo) || d > 6) return null;
        if (jo.opt(key) instanceof JSONObject v) return v;
        for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
            Object r = findObj(jo.opt(it.next()), key, d + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** "😂 name, ❤️ name" from the message's reactions (emoji reactions, else legacy likes). */
    private static String reactions(JSONObject j, Map<String, String> names) {
        JSONObject r = j.optJSONObject("reactions");
        if (r == null) return "";
        List<String> out = new ArrayList<>();
        JSONArray emojis = r.optJSONArray("emojis");
        if (emojis != null) for (int i = 0; i < emojis.length(); i++) {
            JSONObject e = emojis.optJSONObject(i);
            if (e == null) continue;
            String uid = e.optString("sender_id", "");
            out.add(e.optString("emoji", "❤️") + " " + names.getOrDefault(uid, uid));
        }
        JSONArray likes = r.optJSONArray("likes");
        if (out.isEmpty() && likes != null) for (int i = 0; i < likes.length(); i++) {
            JSONObject e = likes.optJSONObject(i);
            String uid = e != null ? e.optString("sender_id", "") : "";
            out.add("❤️ " + names.getOrDefault(uid, uid));
        }
        return String.join(", ", out);
    }

    /** The message this one replies to: the model holds the replied message (or its item id). */
    private static JSONObject repliedTo(Object message, String ownId, Map<String, JSONObject> byId) {
        Class<?> msgClass = serializer.getParameterTypes()[1];
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(message);
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        addFields(q, message, 1);
        while (!q.isEmpty() && seen.size() < 150) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof String s) {
                if (!s.equals(ownId) && byId.containsKey(s)) return byId.get(s);
                continue;
            }
            if (msgClass.isInstance(o)) {
                JSONObject j = toJson(o);
                if (j != null && !ownId.equals(j.optString("item_id", ""))) return j;
                continue;
            }
            if (d >= 2) continue;
            String n = o.getClass().getName();
            if (n.startsWith("X.") || n.startsWith("com.instagram.")) addFields(q, o, d + 1);
        }
        return null;
    }

    private static void addFields(ArrayDeque<Object[]> q, Object o, int depth) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try { f.setAccessible(true); q.add(new Object[]{f.get(o), depth}); } catch (Throwable ignored) {}
            }
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

    /** The username the export is named after: the other person in a 1:1 chat, yourself in a chat
     *  with yourself, null in a group (the thread title is used then). */
    private static String chatUsername(List<?> msgs, Map<String, String> names) {
        String me = selfId;
        java.util.LinkedHashSet<String> others = new java.util.LinkedHashSet<>();
        for (String id : names.keySet()) if (!id.equals(me)) others.add(id);
        if (others.size() == 1) return names.get(others.iterator().next());
        if (others.isEmpty()) return me != null ? names.get(me) : null;
        // more users reachable than the thread's members (e.g. authors of shared posts): go by senders
        java.util.LinkedHashSet<String> senders = new java.util.LinkedHashSet<>();
        for (Object m : msgs) {
            JSONObject j = toJson(m);
            String id = j != null ? j.optString("user_id", "") : "";
            if (!id.isEmpty() && !id.equals(me) && names.containsKey(id)) senders.add(id);
        }
        return senders.size() == 1 && me != null ? names.get(senders.iterator().next()) : null;
    }

    /** user id → username for every User reachable from the store (also finds {@link #selfId}). */
    private static Map<String, String> participants(Object store) {
        Map<String, String> out = new HashMap<>();
        selfId = null;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{store, 0});
        while (!q.isEmpty() && seen.size() < 3000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { if (d < 4) for (Object it : l) q.add(new Object[]{it, d + 1}); continue; }
            if (selfId == null && o.getClass().getName().equals(SESSION)) {
                try { selfId = (String) o.getClass().getMethod("getUserId").invoke(o); } catch (Throwable ignored) {}
                continue;
            }
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
