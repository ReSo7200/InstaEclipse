package ps.reso.instaeclipse.utils.ghost;

import android.app.AndroidAppHelper;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Persistent log of messages that were unsent (or deleted) by the other party, captured by
 * KeepUnsentMessagesHook. Stored as JSON in Instagram's own filesDir, so it survives thread
 * refreshes and app restarts (unlike the in-thread copy, which IG re-syncs away). Read back by the
 * in-IG "Unsent Messages" viewer (DialogUtils) — both run in the Instagram process, so no
 * cross-process channel is needed.
 *
 * Each entry: { t: captured-at epoch millis, s: sender (best-effort), m: message text }.
 */
public class UnsentLog {

    public static class Entry {
        public final long time;       // when we captured it (wall clock)
        public final String thread;   // thread id it belongs to (for per-thread filtering)
        public final String sender;   // best-effort sender label (may be "")
        public final String text;
        public Entry(long time, String thread, String sender, String text) {
            this.time = time; this.thread = thread; this.sender = sender; this.text = text;
        }
    }

    private static final String FILE_NAME = "ie_unsent_log.json";
    private static final int MAX_ENTRIES = 1000;

    private static final List<Entry> cache = new ArrayList<>();
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
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                cache.add(new Entry(o.optLong("t"), o.optString("th", ""), o.optString("s", ""), o.optString("m", "")));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentLog) load failed: " + t.getMessage());
        }
    }

    private static synchronized void persist() {
        try {
            File f = file();
            if (f == null) return;
            JSONArray arr = new JSONArray();
            for (Entry e : cache) {
                JSONObject o = new JSONObject();
                o.put("t", e.time); o.put("th", e.thread); o.put("s", e.sender); o.put("m", e.text);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentLog) persist failed: " + t.getMessage());
        }
    }

    /** Add a captured unsent message (deduped against a recent identical text in the same thread). */
    public static synchronized void add(String thread, String sender, String text) {
        if (text == null || text.isEmpty()) return;
        ensureLoaded();
        String th = thread == null ? "" : thread;
        // Skip if the same text was just logged for this thread (avoid reconcile-driven dupes).
        for (int i = cache.size() - 1, seen = 0; i >= 0 && seen < 10; i--, seen++) {
            Entry e = cache.get(i);
            if (th.equals(e.thread) && text.equals(e.text)) return;
        }
        cache.add(new Entry(System.currentTimeMillis(), th, sender == null ? "" : sender, text));
        while (cache.size() > MAX_ENTRIES) cache.remove(0);
        persist();
    }

    /** All entries, newest first. */
    public static synchronized List<Entry> getAll() {
        ensureLoaded();
        List<Entry> out = new ArrayList<>(cache);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Entries for one thread id, newest first. */
    public static synchronized List<Entry> getForThread(String thread) {
        ensureLoaded();
        List<Entry> out = new ArrayList<>();
        for (Entry e : cache) if (e.thread.equals(thread)) out.add(e);
        java.util.Collections.reverse(out);
        return out;
    }

    public static synchronized int size() { ensureLoaded(); return cache.size(); }

    public static synchronized void clear() {
        cache.clear();
        persist();
    }

    /** Remove only one thread's entries. */
    public static synchronized void clearThread(String thread) {
        ensureLoaded();
        if (thread == null) return;
        boolean changed = cache.removeIf(e -> thread.equals(e.thread));
        if (changed) persist();
    }
}
