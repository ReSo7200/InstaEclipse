package ps.reso.instaeclipse.utils.media;

import android.app.AndroidAppHelper;
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

/**
 * Local 24h cache of viewed stories, so they stay viewable after they expire or the poster deletes
 * them. Modeled on UnsentLog: a JSON index in Instagram's filesDir + the media files themselves
 * under filesDir/ie_stories/. Populated as the user views stories (StoryDownloadHook capture), read
 * back by the in-IG viewer (DialogUtils). Pruned by a 24h TTL and a total-size cap on every access.
 *
 * Index entry: { id, author, url, video(bool), path, at(captured epoch ms) }.
 */
public class StoryCache {

    private static final String INDEX = "ie_story_cache.json";
    private static final String DIR = "ie_stories";
    private static final long TTL_MS = 24L * 60 * 60 * 1000;     // 24 hours
    private static final long MAX_BYTES = 500L * 1024 * 1024;    // 500 MB cap

    public static class Entry {
        public final String id, author, url, path;
        public final boolean video;
        public final long at;          // captured-at epoch ms
        public final long expiringAt;  // IG's expiring_at epoch ms (0 = unknown)
        Entry(String id, String author, String url, boolean video, String path, long at, long expiringAt) {
            this.id = id; this.author = author; this.url = url; this.video = video; this.path = path;
            this.at = at; this.expiringAt = expiringAt;
        }
        /** True once past IG's expiry (the story would no longer be in the live tray). */
        public boolean isExpired() { return expiringAt > 0 && System.currentTimeMillis() > expiringAt; }
    }

    private static final List<Entry> cache = new ArrayList<>();
    private static boolean loaded = false;

    private static Context ctx() { return AndroidAppHelper.currentApplication(); }
    private static File indexFile() { Context c = ctx(); return c == null ? null : new File(c.getFilesDir(), INDEX); }
    /** Per-author folder: filesDir/ie_stories/<username>/ (falls back to "unknown"). */
    private static File storyDir(String author) {
        Context c = ctx(); if (c == null) return null;
        String sub = (author == null || author.isEmpty()) ? "unknown" : author.replaceAll("[^A-Za-z0-9._-]", "_");
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
                cache.add(new Entry(o.optString("id"), o.optString("author"), o.optString("url"),
                        o.optBoolean("video"), o.optString("path"), o.optLong("at"), o.optLong("expiringAt")));
            }
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) load failed: " + t); }
        prune();
    }

    private static synchronized void persist() {
        try {
            File f = indexFile(); if (f == null) return;
            JSONArray arr = new JSONArray();
            for (Entry e : cache) {
                JSONObject o = new JSONObject();
                o.put("id", e.id); o.put("author", e.author); o.put("url", e.url);
                o.put("video", e.video); o.put("path", e.path); o.put("at", e.at);
                o.put("expiringAt", e.expiringAt);
                arr.put(o);
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) save failed: " + t); }
    }

    /** Drops entries older than 24h, then oldest-first while over the size cap; deletes their files. */
    private static synchronized void prune() {
        long now = System.currentTimeMillis();
        long total = 0;
        for (java.util.Iterator<Entry> it = cache.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            File mf = new File(e.path);
            if (now - e.at > TTL_MS || !mf.exists()) {
                //noinspection ResultOfMethodCallIgnored
                mf.delete();
                it.remove();
            } else {
                total += mf.length();
            }
        }
        // Size cap: cache is append-ordered (oldest first), so evict from the front.
        for (java.util.Iterator<Entry> it = cache.iterator(); it.hasNext() && total > MAX_BYTES; ) {
            Entry e = it.next();
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
        for (Entry e : cache) if (id.equals(e.id)) return true;
        return false;
    }

    /**
     * Captures a story: downloads its media to the private cache dir and indexes it. Runs the
     * network I/O on the caller's thread — call from a background executor. No-op if already cached.
     */
    public static void capture(String id, String author, String url, boolean video, long expiringAt) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) return;
        if (has(id)) return;
        try {
            File dir = storyDir(author); if (dir == null) return;
            String ext = video ? "mp4" : "jpg";
            File out = new File(dir, id.replaceAll("[^A-Za-z0-9._-]", "_") + "." + ext);
            if (!download(url, out)) return;
            synchronized (StoryCache.class) {
                cache.add(new Entry(id, author == null ? "" : author, url, video, out.getAbsolutePath(),
                        System.currentTimeMillis(), expiringAt));
                persist();
                prune();
            }
            ModuleLog.line("(IE|StoryCache) cached story " + id + " by " + author);
        } catch (Throwable t) { ModuleLog.line("(IE|StoryCache) capture failed: " + t); }
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
            ModuleLog.line("(IE|StoryCache) download failed: " + t);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        } finally { if (c != null) c.disconnect(); }
    }

    /** Snapshot of cached stories, newest first, for the viewer. */
    public static synchronized List<Entry> entries() {
        ensureLoaded();
        List<Entry> copy = new ArrayList<>(cache);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public static synchronized void clearAll() {
        ensureLoaded();
        for (Entry e : cache) { //noinspection ResultOfMethodCallIgnored
            new File(e.path).delete(); }
        cache.clear();
        persist();
    }
}
