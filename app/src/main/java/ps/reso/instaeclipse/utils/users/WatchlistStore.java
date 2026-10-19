package ps.reso.instaeclipse.utils.users;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profile analyzer watchlist: other profiles whose Followers and Following lists you check.
 *
 * Like FollowersHistory, but for a watched profile B and both of its lists. Each analysis is a full
 * load of B's Followers and Following (FollowListExportHook drives IG's pager); both lists are kept
 * as B's last snapshot (id → {username, full name}) and each analysis is compared with it:
 * who B newly followed / unfollowed, who newly followed B / stopped following B. Everything lives in
 * Instagram's private files, per logged-in account: an index (watched profiles and their counts) and
 * one file per watched profile (snapshot and event log, newest first, capped).
 */
public final class WatchlistStore {

    public static final String B_FOLLOWED = "b_followed";       // B started following someone
    public static final String B_UNFOLLOWED = "b_unfollowed";   // B stopped following someone
    public static final String GAINED_FOLLOWER = "gained";      // someone started following B
    public static final String LOST_FOLLOWER = "lost";          // someone stopped following B
    static final int MAX_EVENTS = 3000;
    static final int MAX_PROFILES = 100;

    /** One noticed change of a watched profile. */
    public static final class Event {
        public final long ts, since;
        public final String type, id, username, fullName;

        public Event(long ts, long since, String type, String id, String username, String fullName) {
            this.ts = ts;
            this.since = since;
            this.type = type;
            this.id = id;
            this.username = username;
            this.fullName = fullName;
        }
    }

    /** A watched profile as listed in the index. */
    public static final class Entry {
        public String id, username;
        public long added, lastCheck;
        public int followers = -1, following = -1, events;
    }

    /** A watched profile's saved state: the last snapshot of both lists and its event log. */
    public static final class Profile {
        public String id, username;
        public long lastCheck;
        public Map<String, String[]> followers = new LinkedHashMap<>();
        public Map<String, String[]> following = new LinkedHashMap<>();
        public List<Event> events = new ArrayList<>();
    }

    /** What one analysis found. {@code first}: no earlier snapshot, nothing compared. */
    public static final class Result {
        public final boolean first;
        public final int followers, following;
        public final long previousCheck;
        public final List<Event> added;

        Result(boolean first, int followers, int following, long previousCheck, List<Event> added) {
            this.first = first;
            this.followers = followers;
            this.following = following;
            this.previousCheck = previousCheck;
            this.added = added;
        }

        public List<Event> of(String type) {
            List<Event> out = new ArrayList<>();
            for (Event e : added) if (type.equals(e.type)) out.add(e);
            return out;
        }
    }

    private WatchlistStore() {}

    // ---- pure logic ----

    /**
     * Changes from {@code before} to {@code after} (both id → {username, full name}): ids that are
     * gone get {@code goneType}, new ids {@code newType}. Names come from the side that has the id.
     */
    public static List<Event> diff(Map<String, String[]> before, Map<String, String[]> after, long now, long since,
                                   String goneType, String newType) {
        List<Event> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : after.entrySet())
            if (!before.containsKey(e.getKey())) out.add(event(now, since, newType, e.getKey(), e.getValue()));
        for (Map.Entry<String, String[]> e : before.entrySet())
            if (!after.containsKey(e.getKey())) out.add(event(now, since, goneType, e.getKey(), e.getValue()));
        return out;
    }

    private static Event event(long now, long since, String type, String id, String[] u) {
        return new Event(now, since, type, id, u != null && u.length > 0 ? u[0] : null, u != null && u.length > 1 ? u[1] : null);
    }

    /**
     * Applies complete Followers and Following lists of the profile to {@code p}: the differences
     * are prepended to its log (capped) and the lists become the new snapshot.
     */
    public static Result apply(Profile p, Map<String, String[]> followers, Map<String, String[]> following, long now) {
        boolean first = p.lastCheck == 0;
        long previous = p.lastCheck;
        List<Event> added = new ArrayList<>();
        if (!first) {
            added.addAll(diff(p.following, following, now, previous, B_UNFOLLOWED, B_FOLLOWED));
            added.addAll(diff(p.followers, followers, now, previous, LOST_FOLLOWER, GAINED_FOLLOWER));
        }
        List<Event> all = new ArrayList<>(added);
        all.addAll(p.events);
        while (all.size() > MAX_EVENTS) all.remove(all.size() - 1);
        p.events = all;
        p.followers = new LinkedHashMap<>(followers);
        p.following = new LinkedHashMap<>(following);
        p.lastCheck = now;
        return new Result(first, followers.size(), following.size(), previous, added);
    }

    /** Index row for {@code p} (keeps the date it was added). */
    public static void touch(List<Entry> index, Profile p, long now) {
        Entry e = find(index, p.id);
        if (e == null) {
            e = new Entry();
            e.id = p.id;
            e.added = now;
        } else index.remove(e);
        if (p.username != null) e.username = p.username;
        e.lastCheck = p.lastCheck;
        e.followers = p.lastCheck > 0 ? p.followers.size() : -1;
        e.following = p.lastCheck > 0 ? p.following.size() : -1;
        e.events = p.events.size();
        index.add(0, e); // most recently analyzed first
        while (index.size() > MAX_PROFILES) index.remove(index.size() - 1);
    }

    public static Entry find(List<Entry> index, String id) {
        for (Entry e : index) if (e.id.equals(id)) return e;
        return null;
    }

    // ---- storage (Instagram's private files, per logged-in account) ----

    private static File indexFile(Context ctx, String self) {
        return new File(ctx.getFilesDir(), "ie_watchlist_" + self + ".json");
    }

    private static File profileFile(Context ctx, String self, String id) {
        return new File(ctx.getFilesDir(), "ie_watch_" + self + "_" + id + ".json");
    }

    private static File selfFile(Context ctx) {
        return new File(ctx.getFilesDir(), "ie_watchlist_self");
    }

    private static volatile String lastSelf;

    /** The account last seen logged in (from a list's session argument), so the menu can find its
     *  watchlist; kept on disk across restarts. */
    public static void setSelf(Context ctx, String self) {
        if (self == null || self.equals(lastSelf)) return;
        lastSelf = self;
        new Thread(() -> {
            try { write(selfFile(ctx), self); } catch (Throwable ignored) {}
        }).start();
    }

    public static String self(Context ctx) {
        if (lastSelf != null) return lastSelf;
        try {
            File f = selfFile(ctx);
            if (f.exists()) lastSelf = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {}
        return lastSelf != null && !lastSelf.isEmpty() ? lastSelf : null;
    }

    public static synchronized List<Entry> loadIndex(Context ctx, String self) {
        List<Entry> out = new ArrayList<>();
        try {
            File f = indexFile(ctx, self);
            if (!f.exists()) return out;
            JSONArray a = new JSONObject(read(f)).optJSONArray("profiles");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optString("id");
                e.username = o.optString("u", null);
                e.added = o.optLong("added");
                e.lastCheck = o.optLong("last");
                e.followers = o.optInt("fc", -1);
                e.following = o.optInt("gc", -1);
                e.events = o.optInt("ev");
                if (!e.id.isEmpty()) out.add(e);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized void saveIndex(Context ctx, String self, List<Entry> index) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : index) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            if (e.username != null) o.put("u", e.username);
            o.put("added", e.added);
            o.put("last", e.lastCheck);
            o.put("fc", e.followers);
            o.put("gc", e.following);
            o.put("ev", e.events);
            a.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("profiles", a);
        write(indexFile(ctx, self), root.toString());
    }

    /** Adds {@code id} to the watchlist (no snapshot yet) if it isn't there. */
    public static synchronized void add(Context ctx, String self, String id, String username) {
        try {
            List<Entry> index = loadIndex(ctx, self);
            Entry e = find(index, id);
            if (e != null) {
                if (username != null && !username.equals(e.username)) { e.username = username; saveIndex(ctx, self, index); }
                return;
            }
            e = new Entry();
            e.id = id;
            e.username = username;
            e.added = System.currentTimeMillis();
            index.add(0, e);
            while (index.size() > MAX_PROFILES) index.remove(index.size() - 1);
            saveIndex(ctx, self, index);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void remove(Context ctx, String self, String id) {
        try {
            List<Entry> index = loadIndex(ctx, self);
            Entry e = find(index, id);
            if (e != null) { index.remove(e); saveIndex(ctx, self, index); }
        } catch (Throwable ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        profileFile(ctx, self, id).delete();
    }

    public static synchronized Profile loadProfile(Context ctx, String self, String id) {
        Profile p = new Profile();
        p.id = id;
        try {
            File f = profileFile(ctx, self, id);
            if (!f.exists()) return p;
            JSONObject o = new JSONObject(read(f));
            p.username = o.optString("u", null);
            p.lastCheck = o.optLong("last");
            readUsers(o.optJSONObject("followers"), p.followers);
            readUsers(o.optJSONObject("following"), p.following);
            JSONArray ev = o.optJSONArray("events");
            if (ev != null) {
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    p.events.add(new Event(e.optLong("ts"), e.optLong("since"), e.optString("type"),
                            e.optString("id"), e.optString("u", null), e.optString("n", null)));
                }
            }
        } catch (Throwable ignored) {
        }
        return p;
    }

    public static synchronized void saveProfile(Context ctx, String self, Profile p) throws Exception {
        JSONArray ev = new JSONArray();
        for (Event e : p.events) {
            JSONObject o = new JSONObject();
            o.put("ts", e.ts);
            o.put("since", e.since);
            o.put("type", e.type);
            o.put("id", e.id);
            if (e.username != null) o.put("u", e.username);
            if (e.fullName != null) o.put("n", e.fullName);
            ev.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("id", p.id);
        if (p.username != null) root.put("u", p.username);
        root.put("last", p.lastCheck);
        root.put("followers", writeUsers(p.followers));
        root.put("following", writeUsers(p.following));
        root.put("events", ev);
        write(profileFile(ctx, self, p.id), root.toString());
    }

    private static void readUsers(JSONObject users, Map<String, String[]> into) {
        if (users == null) return;
        for (java.util.Iterator<String> it = users.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONArray u = users.optJSONArray(id);
            into.put(id, new String[]{u != null ? u.optString(0, null) : null, u != null ? u.optString(1, null) : null});
        }
    }

    private static JSONObject writeUsers(Map<String, String[]> users) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String[]> e : users.entrySet()) {
            JSONArray u = new JSONArray();
            String[] v = e.getValue();
            u.put(v != null && v.length > 0 && v[0] != null ? v[0] : JSONObject.NULL);
            u.put(v != null && v.length > 1 && v[1] != null ? v[1] : JSONObject.NULL);
            o.put(e.getKey(), u);
        }
        return o;
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Write to a temp file, then rename over the target, so a crash never leaves half a file. */
    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp);
    }
}
