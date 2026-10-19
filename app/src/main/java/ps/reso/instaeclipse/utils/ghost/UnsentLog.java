package ps.reso.instaeclipse.utils.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static boolean loadFailed = false;

    public enum RemovalResult { REMOVED, STALE, FAILED }

    private static File file() {
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        File f = file();
        if (f == null) return;
        loaded = true;
        try {
            if (!f.exists() && !new File(f.getPath() + ".bak").exists()) return;
            byte[] b = new AtomicFile(f).readFully();
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            List<Entry> next = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                next.add(new Entry(o.optLong("t"), o.optString("th", ""), o.optString("s", ""), o.optString("m", "")));
            }
            cache.addAll(next);
        } catch (Throwable t) {
            loadFailed = true;
            ModuleLog.line("(IE|UnsentLog) local history could not be loaded");
        }
    }

    private static synchronized void persist() {
        if (loadFailed) return; // Never overwrite unreadable history with a partial cache.
        try {
            writeSnapshot(file(), cache);
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentLog) local history save failed");
        }
    }

    /** Remove only the exact row held by a viewer snapshot, after its replacement is durable. */
    public static synchronized RemovalResult removeEntry(Entry selected) {
        ensureLoaded();
        if (loadFailed) return RemovalResult.FAILED;
        return removeEntryFrom(cache, selected, file());
    }

    /** Synthetic-list/file seam; never looks up real user history. */
    static RemovalResult removeEntryFrom(List<Entry> entries, Entry selected, File destination) {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i) == selected) { index = i; break; }
        if (index < 0) return RemovalResult.STALE;
        List<Entry> next = new ArrayList<>(entries);
        next.remove(index);
        try { writeSnapshot(destination, next); }
        catch (IOException failure) { return RemovalResult.FAILED; }
        entries.remove(index);
        return RemovalResult.REMOVED;
    }

    static void writeSnapshot(File destination, List<Entry> entries) throws IOException {
        if (destination == null) throw new IOException("Local history destination unavailable");
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                JSONObject object = new JSONObject();
                object.put("t", entry.time); object.put("th", entry.thread);
                object.put("s", entry.sender); object.put("m", entry.text);
                array.put(object);
            }
            byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
            output = atomic.startWrite();
            output.write(bytes);
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            // Some AtomicFile rename failures are only logged by Android. Verify the commit
            // before exposing a removal to callers or dropping the selected cached object.
            if (!java.util.Arrays.equals(bytes, atomic.readFully()))
                throw new IOException("Local history commit could not be verified");
        } catch (Exception failure) {
            atomic.failWrite(output);
            throw new IOException("Local history could not be saved", failure);
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
