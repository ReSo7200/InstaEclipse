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
    private static final String SESSION = "com.instagram.common.session.UserSession";

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

    /** Post of the comments sheet opened last (the sheet fragment holds its Media). */
    private static volatile java.lang.ref.WeakReference<Object> sheetMedia = new java.lang.ref.WeakReference<>(null);

    private static volatile String sheetMediaId;

    /** The post id among the fragment's arguments: a "media_id"-like key, else a "<pk>_<ownerId>" value. */
    private static String mediaIdFromArgs(Object fragment) {
        try {
            Field f = null;
            for (Class<?> k = fragment.getClass(); k != null && f == null; k = k.getSuperclass())
                try { f = k.getDeclaredField("mArguments"); } catch (NoSuchFieldException ignored) {}
            if (f == null) return null;
            f.setAccessible(true);
            android.os.Bundle b = (android.os.Bundle) f.get(fragment);
            if (b == null) return null;
            String byValue = null;
            for (String key : b.keySet()) {
                Object v = b.get(key);
                if (!(v instanceof String s)) continue;
                if (key.toLowerCase(java.util.Locale.ROOT).contains("media_id") && s.matches("[0-9]+(_[0-9]+)?")) return s;
                if (byValue == null && s.matches("[0-9]{6,}_[0-9]+")) byValue = s;
            }
            return byValue;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Comments sheet = fragment whose method uses "Failed to ingest comments close signal" (X.0Ivg on
     *  452); after onViewCreated its Media is remembered so comment downloads know the post's author. */
    public static void trackCommentSheet(org.luckypray.dexkit.DexKitBridge bridge, ClassLoader cl) {
        try {
            for (org.luckypray.dexkit.result.MethodData md : bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create().usingStrings("Failed to ingest comments close signal")))) {
                Class<?> frag = cl.loadClass(md.getClassName());
                Method ovc = frag.getDeclaredMethod("onViewCreated", android.view.View.class, android.os.Bundle.class);
                de.robv.android.xposed.XposedBridge.hookMethod(ovc, new de.robv.android.xposed.XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        Object m = firstOfType(p.thisObject, MEDIA, 3, null);
                        sheetMedia = new java.lang.ref.WeakReference<>(m);
                        sheetMediaId = mediaIdFromArgs(p.thisObject);
                        ModuleLog.line("(IE|CommentMedia) sheet media=" + (m != null) + " id=" + sheetMediaId);
                    }
                });
                return;
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|CommentMedia) sheet hook: " + t);
        }
    }

    /** {commenter, post author} usernames: the comment's own User, and the owner of the post's Media
     *  (found by media id among the comment sheet's objects). Either may be null. */
    public static String[] names(Object comment, String mediaId, Object... roots) {
        String commenter = null, author = null;
        if (mediaId == null) mediaId = sheetMediaId; // the menu callback doesn't always pass the post id
        try {
            Object u = firstOfType(comment, "com.instagram.user.model.User", 2, null);
            if (u != null) commenter = ps.reso.instaeclipse.utils.users.UserUtils.callUsernameGetter(u);
            String pk = mediaId != null ? mediaId.split("_")[0] : null;
            // IG's session media cache (com.instagram.feed.media.MediaCache keeps its real names)
            Object session = null;
            for (Object r : roots) if (session == null && r != null) session = firstOfType(r, SESSION, 3, null);
            if (session != null && mediaId != null) {
                Class<?> cache = session.getClass().getClassLoader().loadClass("com.instagram.feed.media.MediaCache");
                Object mc = cache.getMethod("getInstance", session.getClass()).invoke(null, session);
                Method get = cache.getMethod("get", String.class);
                Object m = get.invoke(mc, mediaId);
                if (m == null && !mediaId.equals(pk)) m = get.invoke(mc, pk);
                if (m != null) author = FeedVideoDownloadHook.extractUsernameFromMediaObject(m);
            }
            for (Object r : roots) { // fallback: the post's Media among the sheet's objects
                if (author != null || r == null || pk == null) break;
                Object m = firstOfType(r, MEDIA, 4, pk);
                if (m != null) author = FeedVideoDownloadHook.extractUsernameFromMediaObject(m);
            }
            if (author == null && sheetMedia.get() != null) author = FeedVideoDownloadHook.extractUsernameFromMediaObject(sheetMedia.get());
            if (author == null) ModuleLog.line("(IE|CommentMedia) post author not found for " + mediaId + " session=" + (session != null));
        } catch (Throwable t) {
            ModuleLog.line("(IE|CommentMedia) names: " + t);
        }
        return new String[]{commenter, author};
    }

    /** First object of class {@code type} reachable from root (if idPrefix != null: one holding a String
     *  field starting with it). */
    private static Object firstOfType(Object root, String type, int maxDepth, String idPrefix) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{root, 0});
        while (!q.isEmpty() && seen.size() < 3000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            if (o.getClass().getName().equals(type)) {
                if (idPrefix == null || hasStringPrefix(o, idPrefix)) return o;
                continue;
            }
            if (o instanceof List<?> l) { if (d < maxDepth) for (Object it : l) q.add(new Object[]{it, d + 1}); continue; }
            if (d >= maxDepth) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class
                            || Context.class.isAssignableFrom(f.getType()) || android.view.View.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                }
        }
        return null;
    }

    private static boolean hasStringPrefix(Object o, String prefix) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); if (f.get(o) instanceof String s && s.startsWith(prefix)) return true; } catch (Throwable ignored) {}
            }
        return false;
    }

    /** Single-file comment media (GIF or photo) is saved directly as
     *  {commenter}_comment_{gif|photo}_{postAuthor}_{commentId}_{date}.{gif|jpg}; several files open the
     *  regular download chooser. */
    public static void download(Context ctx, List<String> urls, String commentId, String commenter, String postAuthor) {
        String id = commentId != null ? commentId : "0";
        if (urls.size() == 1) {
            String url = urls.get(0);
            boolean gif = isGif(url);
            String fn = (commenter != null ? commenter : "unknown") + "_comment_" + (gif ? "gif" : "photo") + "_"
                    + (postAuthor != null ? postAuthor : "unknown") + "_" + id + "_"
                    + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date())
                    + (gif ? ".gif" : ".jpg"); // the saver corrects the extension from the real file type
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, ps.reso.instaeclipse.utils.i18n.I18n.t(ctx, ps.reso.instaeclipse.R.string.ig_comment_gif_downloading),
                    android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try { FeedVideoDownloadHook.downloadAndSave(ctx, url, fn, false, "comment"); }
                catch (Throwable t) { ModuleLog.line("(IE|CommentMedia) download: " + t); }
            }).start();
            return;
        }
        FeedVideoDownloadHook.showPostDownloadDialog(ctx, urls, "comment", id, 0);
    }

    private static boolean isGif(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        return u.contains("giphy.com") || path.endsWith(".gif");
    }
}
