package ps.reso.instaeclipse.utils.ghost;

import android.app.AndroidAppHelper;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Per-chat read-receipt whitelist (#227): DM thread ids that still get "Seen" while Hide DM Seen
 * (ghost mode) is on for everyone else. Same storage scheme as {@link HiddenThreads} (JSON in IG's
 * filesDir, id -> title), read in the Instagram process by GhostDMSeenHook and the network
 * interceptor.
 *
 * JSON: { "<threadId>": "<title>", ... }
 */
public class ReadReceiptWhitelist {

    private static final String FILE_NAME = "ie_read_receipt_whitelist.json";
    // Insertion-ordered so the unhide manager lists most-recently-hidden sensibly.
    private static final Map<String, String> map = new LinkedHashMap<>();
    private static boolean loaded = false;

    private static File file() {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (f == null || !f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, o.optString(k, ""));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|ReadReceipts) load failed: " + t);
        }
    }

    private static synchronized void persist() {
        try {
            File f = file();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) o.put(e.getKey(), e.getValue());
            // temp file + rename: a crash mid-write keeps the previous version intact
            ps.reso.instaeclipse.utils.core.AtomicFiles.write(f, o.toString());
        } catch (Throwable t) {
            ModuleLog.line("(IE|ReadReceipts) save failed: " + t);
        }
    }

    /** True when Instagram should send read receipts for this thread despite ghost mode. */
    public static synchronized boolean isAllowed(String threadId) {
        return ps.reso.instaeclipse.utils.feature.FeatureFlags.readReceiptChats && isHidden(threadId);
    }

    /** True when the chat is on the list (regardless of the feature switch), for the chat menu. */
    public static synchronized boolean isListed(String threadId) {
        return isHidden(threadId);
    }

    /** True when the request path names a whitelisted thread (/direct_v2/threads/<id>/…). */
    public static synchronized boolean pathMentionsAllowed(String path) {
        if (path == null || !ps.reso.instaeclipse.utils.feature.FeatureFlags.readReceiptChats) return false;
        ensureLoaded();
        for (String id : map.keySet()) if (path.contains("/" + id + "/")) return true;
        return false;
    }

    private static synchronized boolean isHidden(String threadId) {
        if (threadId == null || threadId.isEmpty()) return false;
        ensureLoaded();
        return map.containsKey(threadId);
    }

    /** Toggles hidden state; returns the NEW state (true = now hidden). */
    public static synchronized boolean toggle(String threadId, String title) {
        if (threadId == null || threadId.isEmpty()) return false;
        ensureLoaded();
        boolean nowHidden;
        if (map.containsKey(threadId)) { map.remove(threadId); nowHidden = false; }
        else { map.put(threadId, title == null ? "" : title); nowHidden = true; }
        persist();
        return nowHidden;
    }

    /** Adds or removes a thread (title kept for the manager label). */
    public static synchronized void set(String threadId, String title, boolean allowed) {
        if (threadId == null || threadId.isEmpty()) return;
        ensureLoaded();
        if (allowed) map.put(threadId, title == null ? "" : title);
        else map.remove(threadId);
        persist();
    }

    public static synchronized void unhide(String threadId) {
        ensureLoaded();
        if (map.remove(threadId) != null) persist();
    }

    /** Snapshot of hidden threads (id -> title) for the unhide manager. */
    public static synchronized Map<String, String> all() {
        ensureLoaded();
        return new LinkedHashMap<>(map);
    }

    public static synchronized boolean isEmpty() {
        ensureLoaded();
        return map.isEmpty();
    }
}
