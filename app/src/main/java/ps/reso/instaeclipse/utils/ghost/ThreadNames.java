package ps.reso.instaeclipse.utils.ghost;

import android.app.AndroidAppHelper;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Persistent best-effort map of DM thread id -> display name (username), stored as JSON in
 * Instagram's own filesDir. Populated whenever a thread is opened (KeepUnsentMessagesHook resolves
 * the open thread's header handle), and read back by the Unsent Messages viewer so its per-chat
 * folders can be labelled with a username instead of an opaque thread id.
 */
public class ThreadNames {

    private static final String FILE_NAME = "ie_thread_names.json";
    private static final Map<String, String> cache = new HashMap<>();
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
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                cache.put(k, o.optString(k, ""));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|ThreadNames) load failed: " + t.getMessage());
        }
    }

    private static synchronized void persist() {
        try {
            File f = file();
            if (f == null) return;
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : cache.entrySet()) o.put(e.getKey(), e.getValue());
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|ThreadNames) persist failed: " + t.getMessage());
        }
    }

    /** Record a thread's display name (no-op for empty inputs or an unchanged value). */
    public static synchronized void put(String threadId, String name) {
        if (threadId == null || threadId.isEmpty() || name == null || name.trim().isEmpty()) return;
        ensureLoaded();
        String v = name.trim();
        if (v.equals(cache.get(threadId))) return;
        cache.put(threadId, v);
        persist();
    }

    /** Best-known name for a thread id, or "" if unknown. */
    public static synchronized String get(String threadId) {
        if (threadId == null) return "";
        ensureLoaded();
        String v = cache.get(threadId);
        return v == null ? "" : v;
    }
}
