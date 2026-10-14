package ps.reso.instaeclipse.utils.ghost;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Shared utility for resolving DirectThreadKey thread IDs across various hook injection points.
 */
public class ThreadIdResolver {

    /**
     * Extracts the thread id string from a DirectThreadKey (first non-empty String field).
     * Walks superclasses and skips static fields.
     */
    public static String threadIdOf(Object dtk) {
        if (dtk == null) return null;
        try {
            for (Class<?> c = dtk.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(dtk);
                    if (v instanceof String && !((String) v).isEmpty()) return (String) v;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Masks an ID for logging (e.g. "abcd...").
     */
    public static String maskId(String id) {
        if (id == null) return "null";
        if (id.length() <= 4) return id + "…";
        return id.substring(0, 4) + "…";
    }

    /**
     * Strategy 1: Read the open thread's id from the thread ACTIVITY itself.
     */
    public static String resolveFromActivity(Activity activity) {
        try {
            // 1. Intent extras
            Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object dtk = scanForDirectThreadKey(ex.get(k), 0,
                            Collections.newSetFromMap(new IdentityHashMap<>()), 4);
                    if (dtk != null) { String id = threadIdOf(dtk); if (id != null) return id; }
                }
            }
            // 2. The activity object graph
            Object dtk = scanForDirectThreadKey(activity, 0,
                    Collections.newSetFromMap(new IdentityHashMap<>()), 6);
            return dtk != null ? threadIdOf(dtk) : null;
        } catch (Throwable t) { return null; }
    }

    private static Object scanForDirectThreadKey(Object obj, int depth, Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.")
                || obj instanceof Bundle;
        if (!descendable) return null;
        if (obj instanceof Bundle) {
            try {
                Bundle b = (Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scanForDirectThreadKey(b.get(k), depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scanForDirectThreadKey(v, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Strategy 4: Walk the header + ancestors, read the thread tag key and any DirectThreadKey.
     */
    public static String resolveFromTag(View header, int tagKeyId) {
        String found = null;
        View v = header;
        int up = 0;
        while (v != null && up++ < 8) {
            try {
                if (tagKeyId != 0) {
                    Object t = v.getTag(tagKeyId);
                    if (t != null) {
                        String id = threadIdFromAny(t);
                        if (id != null && found == null) found = id;
                    }
                }
                Object plain = v.getTag();
                if (plain != null) {
                    String id = threadIdFromAny(plain);
                    if (id != null && found == null) found = id;
                }
            } catch (Throwable ignored) {}
            v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
        }
        return found;
    }

    private static String threadIdFromAny(Object obj) {
        if (obj == null) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return threadIdOf(obj);
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().getName().contains("DirectThreadKey")) {
                    f.setAccessible(true);
                    Object k = f.get(obj);
                    if (k != null) return threadIdOf(k);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Main resolution loop resolving in order: Activity -> bound -> tracked -> tag-key probe.
     * Logs diagnostic output using the provided module tag.
     */
    public static String resolveThreadId(String logTag, Activity activity, String boundId, String trackedId, View header, int tagKeyId) {
        String threadId = resolveFromActivity(activity);
        if (threadId != null) {
            ModuleLog.line("(" + logTag + ") resolved via Activity; id=" + maskId(threadId));
            return threadId;
        }

        if (boundId != null) {
            ModuleLog.line("(" + logTag + ") resolved via bound ID; id=" + maskId(boundId));
            return boundId;
        }

        if (trackedId != null) {
            ModuleLog.line("(" + logTag + ") resolved via tracked ID; id=" + maskId(trackedId));
            return trackedId;
        }

        threadId = resolveFromTag(header, tagKeyId);
        if (threadId != null) {
            ModuleLog.line("(" + logTag + ") resolved via tag-key probe; id=" + maskId(threadId));
            return threadId;
        }

        ModuleLog.line("(" + logTag + ") ⚠️ all ID resolution strategies failed");
        return null;
    }
}
