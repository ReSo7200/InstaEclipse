package ps.reso.instaeclipse.utils.media;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Viewed Instants kept on this phone so they can be opened again after they are swiped away. */
public class InstantHistory {

    private static final String INDEX = "ie_instant_history.json";
    private static final String DIR = "ie_instants";
    // Kept until cleared, but never more than this: the oldest Instants go first (same cap as Cache Stories).
    private static final long MAX_BYTES = 500L * 1024 * 1024;

    private static final List<StoryCache.Entry> cache = new ArrayList<>();
    private static boolean loaded = false;

    private static Context ctx() { return AndroidAppHelper.currentApplication(); }
    private static File indexFile() { Context c = ctx(); return c == null ? null : new File(c.getFilesDir(), INDEX); }
    /** Per-author folder: filesDir/ie_instants/<username>/ (falls back to "instant"). */
    private static File instantDir(String author) {
        Context c = ctx(); if (c == null) return null;
        String sub = (author == null || author.isEmpty()) ? "instant" : author.replaceAll("[^A-Za-z0-9._-]", "_");
        File d = new File(new File(c.getFilesDir(), DIR), sub);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = indexFile();
            if (f == null || !f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONArray arr = new JSONArray(new String(b, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                cache.add(new StoryCache.Entry(o.optString("id"), o.optString("author"), o.optString("url"),
                        o.optBoolean("video"), o.optString("path"), o.optLong("at"), o.optLong("expiringAt")));
            }
        } catch (Throwable t) { ModuleLog.line("(IE|InstantHistory) load failed: " + t); }
        prune();
    }

    private static synchronized void persist() {
        try {
            File f = indexFile(); if (f == null) return;
            JSONArray arr = new JSONArray();
            for (StoryCache.Entry e : cache) {
                JSONObject o = new JSONObject();
                o.put("id", e.id); o.put("author", e.author); o.put("url", e.url);
                o.put("video", e.video); o.put("path", e.path); o.put("at", e.at);
                o.put("expiringAt", e.expiringAt);
                arr.put(o);
            }
            // temp file + rename: a crash mid-write keeps the previous version intact
            ps.reso.instaeclipse.utils.core.AtomicFiles.write(f, arr.toString());
        } catch (Throwable t) { ModuleLog.line("(IE|InstantHistory) save failed: " + t); }
    }

    /** Drops entries whose file is gone, then the oldest while over the size cap; deletes their files. */
    private static synchronized void prune() {
        long total = 0;
        for (java.util.Iterator<StoryCache.Entry> it = cache.iterator(); it.hasNext(); ) {
            StoryCache.Entry e = it.next();
            File mf = new File(e.path);
            if (!mf.exists()) {
                //noinspection ResultOfMethodCallIgnored
                mf.delete();
                it.remove();
            } else {
                total += mf.length();
            }
        }
        // Size cap: cache is append-ordered (oldest first), so evict from the front.
        for (java.util.Iterator<StoryCache.Entry> it = cache.iterator(); it.hasNext() && total > MAX_BYTES; ) {
            StoryCache.Entry e = it.next();
            File mf = new File(e.path);
            total -= mf.length();
            //noinspection ResultOfMethodCallIgnored
            mf.delete();
            it.remove();
        }
    }

    public static synchronized boolean has(String id) {
        if (id == null || id.isEmpty()) return false;
        ensureLoaded();
        for (StoryCache.Entry e : cache) if (id.equals(e.id)) return true;
        return false;
    }

    /**
     * Captures an Instant: downloads its media to the private files dir and indexes it. Runs the
     * network I/O on the caller's thread — call from a background executor. No-op if already cached.
     */
    public static void capture(String id, String author, String url, boolean video, long expiringAt) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) return;
        if (has(id)) return;
        try {
            File dir = instantDir(author); if (dir == null) return;
            String ext = video ? "mp4" : "jpg";
            File out = new File(dir, id.replaceAll("[^A-Za-z0-9._-]", "_") + "." + ext);
            if (!download(url, out)) return;
            synchronized (InstantHistory.class) {
                cache.add(new StoryCache.Entry(id, author == null ? "" : author, url, video, out.getAbsolutePath(),
                        System.currentTimeMillis(), expiringAt));
                persist();
                prune();
            }
            ModuleLog.line("(IE|InstantHistory) saved instant " + id + " by " + author);
        } catch (Throwable t) { ModuleLog.line("(IE|InstantHistory) capture failed: " + t); }
    }

    private static boolean download(String urlStr, File out) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Instagram");
            if (c.getResponseCode() / 100 != 2) return false;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            return out.length() > 0;
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantHistory) download failed: " + t);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        } finally { if (c != null) c.disconnect(); }
    }

    /** Snapshot of kept Instants, newest first, for the viewer. */
    public static synchronized List<StoryCache.Entry> entries() {
        ensureLoaded();
        List<StoryCache.Entry> copy = new ArrayList<>(cache);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public static synchronized void clearAll() {
        ensureLoaded();
        for (StoryCache.Entry e : cache) { //noinspection ResultOfMethodCallIgnored
            new File(e.path).delete(); }
        cache.clear();
        persist();
    }
}
