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
 * Persistent set of DM thread ids the user has chosen to hide from the inbox (Hide Specific Chats).
 * Stored as JSON in Instagram's own filesDir, so it survives restarts. A captured title is kept
 * alongside each id purely so the unhide manager can show a human-readable label. Runs in the
 * Instagram process (same as the inbox filter + the hide button), so no cross-process channel.
 *
 * JSON: { "<threadId>": "<title>", ... }
 */
public class HiddenThreads {

    private static final String FILE_NAME = "ie_hidden_threads.json";
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
            ModuleLog.line("(IE|HiddenThreads) load failed: " + t);
        }
    }

    private static synchronized void persist() {
        try {
            File f = file();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) o.put(e.getKey(), e.getValue());
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|HiddenThreads) save failed: " + t);
        }
    }

    public static synchronized boolean isHidden(String threadId) {
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
