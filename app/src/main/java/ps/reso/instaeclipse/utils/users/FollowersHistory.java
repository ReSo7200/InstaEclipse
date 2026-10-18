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
 * Followers history: who unfollowed you and who followed you, between checks.
 *
 * A check is a full load of your own Followers list (FollowListExportHook drives IG's pager). The
 * list is kept as the last snapshot (id → {username, full name}) in Instagram's private files, one
 * file per account; each check is compared with it and every difference is added to the event log.
 * A change is only seen when you check, so an event's date is when it was noticed, and the
 * previous check's date bounds when it happened.
 */
public final class FollowersHistory {

    public static final String LOST = "lost";
    public static final String GAINED = "gained";
    private static final int MAX_EVENTS = 2000;

    /** One noticed change. */
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

    /** What a check found. {@code first}: no earlier snapshot, nothing compared. */
    public static final class Result {
        public final boolean first;
        public final int count;
        public final long previousCheck;
        public final List<Event> added;
        public final List<Event> all;

        Result(boolean first, int count, long previousCheck, List<Event> added, List<Event> all) {
            this.first = first;
            this.count = count;
            this.previousCheck = previousCheck;
            this.added = added;
            this.all = all;
        }
    }

    /** Saved state: the last snapshot and the event log, newest event first. */
    public static final class State {
        public long lastCheck;
        public Map<String, String[]> users = new LinkedHashMap<>();
        public List<Event> events = new ArrayList<>();
    }

    private FollowersHistory() {}

    /**
     * Changes from {@code before} to {@code after} (both id → {username, full name}): ids that are
     * gone are LOST, new ids are GAINED. Usernames come from the side that has the id.
     */
    public static List<Event> diff(Map<String, String[]> before, Map<String, String[]> after, long now, long since) {
        List<Event> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : before.entrySet())
            if (!after.containsKey(e.getKey())) out.add(event(now, since, LOST, e.getKey(), e.getValue()));
        for (Map.Entry<String, String[]> e : after.entrySet())
            if (!before.containsKey(e.getKey())) out.add(event(now, since, GAINED, e.getKey(), e.getValue()));
        return out;
    }

    private static Event event(long now, long since, String type, String id, String[] u) {
        return new Event(now, since, type, id, u != null && u.length > 0 ? u[0] : null, u != null && u.length > 1 ? u[1] : null);
    }

    /**
     * Applies a complete followers list (id → {username, full name}) to {@code state}: the
     * differences are prepended to the log (capped) and the list becomes the new snapshot.
     */
    public static Result apply(State state, Map<String, String[]> users, long now) {
        boolean first = state.lastCheck == 0;
        long previous = state.lastCheck;
        List<Event> added = first ? new ArrayList<>() : diff(state.users, users, now, previous);
        List<Event> all = new ArrayList<>(added);
        all.addAll(state.events);
        while (all.size() > MAX_EVENTS) all.remove(all.size() - 1);
        state.events = all;
        state.users = new LinkedHashMap<>(users);
        state.lastCheck = now;
        return new Result(first, users.size(), previous, added, all);
    }

    // ---- storage (Instagram's private files, one file per account) ----

    private static File file(Context ctx, String selfId) {
        return new File(ctx.getFilesDir(), "ie_followers_history_" + selfId + ".json");
    }

    public static State load(Context ctx, String selfId) {
        State s = new State();
        try {
            File f = file(ctx, selfId);
            if (!f.exists()) return s;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            s.lastCheck = o.optLong("lastCheck");
            JSONObject users = o.optJSONObject("users");
            if (users != null) {
                for (java.util.Iterator<String> it = users.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONArray u = users.optJSONArray(id);
                    s.users.put(id, new String[]{u != null ? u.optString(0, null) : null, u != null ? u.optString(1, null) : null});
                }
            }
            JSONArray ev = o.optJSONArray("events");
            if (ev != null) {
                for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    s.events.add(new Event(e.optLong("ts"), e.optLong("since"), e.optString("type"),
                            e.optString("id"), e.optString("u", null), e.optString("n", null)));
                }
            }
        } catch (Throwable ignored) {
        }
        return s;
    }

    public static void save(Context ctx, String selfId, State s) throws Exception {
        JSONObject users = new JSONObject();
        for (Map.Entry<String, String[]> e : s.users.entrySet()) {
            JSONArray u = new JSONArray();
            u.put(e.getValue()[0] != null ? e.getValue()[0] : JSONObject.NULL);
            u.put(e.getValue().length > 1 && e.getValue()[1] != null ? e.getValue()[1] : JSONObject.NULL);
            users.put(e.getKey(), u);
        }
        JSONArray ev = new JSONArray();
        for (Event e : s.events) {
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
        root.put("lastCheck", s.lastCheck);
        root.put("users", users);
        root.put("events", ev);
        File f = file(ctx, selfId);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + tmp);
    }

    public static void clear(Context ctx, String selfId) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx, selfId).delete();
    }
}
