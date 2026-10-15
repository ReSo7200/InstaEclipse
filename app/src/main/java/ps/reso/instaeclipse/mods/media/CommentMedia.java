package ps.reso.instaeclipse.mods.media;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Media attached to a comment (#242): photo comments carry a com.instagram.feed.media.Media
 * (media_info / media_comment_info), GIF comments a Giphy schema object whose images expose a
 * real-named getUrl(). Pando-backed schemas keep their data behind getters, so besides fields we
 * also call no-arg getters whose return type is Media or a Comment/Giphy/MediaInfo schema type.
 */
public final class CommentMedia {

    private static final String MEDIA = "com.instagram.feed.media.Media";

    private CommentMedia() {}

    /** Downloadable URLs for the comment's attached media; empty when it has none. */
    public static List<String> urls(Context ctx, Object comment) {
        List<String> out = new ArrayList<>();
        if (comment == null) return out;
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<Object[]> queue = new ArrayDeque<>();
            queue.add(new Object[]{comment, 0});
            while (!queue.isEmpty() && seen.size() < 400) {
                Object[] e = queue.poll();
                Object o = e[0];
                int depth = (int) e[1];
                if (o == null || !seen.add(o)) continue;
                Class<?> c = o.getClass();
                if (c.getName().equals(MEDIA)) {
                    List<String> u = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, o);
                    if (u != null && !u.isEmpty()) return u;
                    continue;
                }
                if (c.getName().toLowerCase().contains("giphy")) {
                    String g = giphyUrl(o);
                    if (g != null) { out.add(g); return out; }
                }
                if (depth >= 5) continue;
                String n = c.getName();
                if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                        try { f.setAccessible(true); queue.add(new Object[]{f.get(o), depth + 1}); } catch (Throwable ignored) {}
                    }
                }
                for (Method m : c.getMethods()) {
                    if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                    String rt = m.getReturnType().getName();
                    if (rt.equals(MEDIA) || (rt.startsWith("com.instagram.api.schemas.")
                            && (rt.contains("Comment") || rt.contains("Giphy") || rt.contains("MediaInfo")))) {
                        try { queue.add(new Object[]{m.invoke(o), depth + 1}); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|CommentMedia) ⚠️ " + t);
        }
        return out;
    }

    /** First http URL reachable through getUrl()-style getters of a Giphy schema object. */
    private static String giphyUrl(Object o) {
        try {
            for (Method m : o.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() == String.class && m.getName().equals("getUrl")) {
                    Object r = m.invoke(o);
                    if (r instanceof String s && s.startsWith("http")) return s;
                }
            }
            for (Method m : o.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType().getName().startsWith("com.instagram.api.schemas.")) {
                    Object r = m.invoke(o);
                    if (r != null && r != o) {
                        for (Method g : r.getClass().getMethods()) {
                            if (g.getParameterCount() == 0 && g.getReturnType() == String.class && g.getName().equals("getUrl")) {
                                Object u = g.invoke(r);
                                if (u instanceof String s && s.startsWith("http")) return s;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Opens the regular download chooser for the comment's media. */
    public static void download(Context ctx, List<String> urls, String commentId) {
        FeedVideoDownloadHook.showPostDownloadDialog(ctx, urls, "comment", commentId != null ? commentId : "0", 0);
    }
}
