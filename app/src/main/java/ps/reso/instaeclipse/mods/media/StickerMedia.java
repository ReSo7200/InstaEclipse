package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class StickerMedia {
    private StickerMedia() {}

    private record Node(Object value, int depth, boolean sticker) {}
    public static List<String> urls(Object root) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(new Node(root, 0, stickerType(root)));
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean genericImage = hasMessageType(root, "GENERIC_XMA"::equals);
        String best = null;
        long bestScore = -1;
        while (!queue.isEmpty() && seen.size() < 400) {
            Node node = queue.remove();
            Object value = node.value();
            if (value == null || value instanceof Enum<?> || !seen.add(value) || node.depth() > 7) continue;
            if (value instanceof Iterable<?> values) {
                for (Object item : values) {
                    if (queue.size() >= 400) break;
                    queue.add(new Node(item, node.depth() + 1, node.sticker()));
                }
                continue;
            }
            String type = value.getClass().getName();
            if (!type.startsWith("X.") && !type.startsWith("com.instagram.")) continue;
            if (type.equals("com.instagram.user.model.User") || type.equals("com.instagram.feed.media.Media")
                    || type.endsWith("DirectAnimatedMediaUser")) continue;
            String lower = value.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            boolean sticker = node.sticker() || (genericImage && type.equals("com.instagram.model.mediasize.ExtendedImageUrl"))
                    || lower.contains("sticker") || lower.contains("giphy") || lower.contains("animatedmedia")
                    || lower.contains("gifurl") || stickerAction(value) || stickerType(value);
            long area = dimension(value, "getWidth") * dimension(value, "getHeight");
            for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        Object child = field.get(value);
                        if (sticker && child instanceof String url && valid(url)
                                && (type.startsWith("com.instagram.model.direct.") || type.startsWith("com.instagram.model.mediasize.")
                                || field.getName().toLowerCase(Locale.ROOT).contains("url") || imagePath(url))) {
                            long score = area * 2 + (imagePath(url) ? 1 : 0);
                            if (score > bestScore) { best = url; bestScore = score; }
                        } else if (!(child instanceof String) && queue.size() < 400) queue.add(new Node(child, node.depth() + 1, sticker));
                    } catch (ReflectiveOperationException | RuntimeException ignored) {}
                }
            }
            for (Method method : value.getClass().getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                        || method.getDeclaringClass() == Object.class || method.getName().equals("toString")) continue;
                String rt = method.getReturnType().getName();
                boolean urlGetter = sticker && method.getReturnType() == String.class
                        && (type.startsWith("com.instagram.api.schemas.") || method.getName().equals("getUrl")
                        || method.getName().equals("getImageUrl") || method.getName().equals("getSrc"));
                if (!urlGetter && !rt.startsWith("com.instagram.api.schemas.")
                        && !(sticker && Iterable.class.isAssignableFrom(method.getReturnType()))) continue;
                try {
                    Object child = method.invoke(value);
                    if (urlGetter && child instanceof String url && valid(url)) {
                        long score = area * 2 + (imagePath(url) ? 1 : 0);
                        if (score > bestScore) { best = url; bestScore = score; }
                    } else if (child != value && queue.size() < 400) queue.add(new Node(child, node.depth() + 1, sticker));
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return best == null ? Collections.emptyList() : Collections.singletonList(best);
    }

    private static boolean stickerAction(Object value) {
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(value) instanceof String s && s.startsWith("instagram://")
                            && s.substring(12).split("[?/]", 2)[0].contains("sticker")) return true;
                } catch (IllegalAccessException ignored) {}
            }
        return false;
    }

    private static boolean hasMessageType(Object root, Predicate<String> matches) {
        if (root == null) return false;
        for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isEnum()) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(root) instanceof Enum<?> e && matches.test(e.name())) return true;
                } catch (IllegalAccessException ignored) {}
            }
        return false;
    }

    private static boolean stickerType(Object root) {
        return hasMessageType(root, name -> name.contains("STICKER") || name.equals("ANIMATED_MEDIA"));
    }

    private static long dimension(Object value, String getter) {
        try {
            Object n = value.getClass().getMethod(getter).invoke(value);
            if (n instanceof Number number) return Math.max(0, Math.min(16384, number.longValue()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return 0;
    }

    private static boolean valid(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static boolean imagePath(String url) {
        String path = url.split("\\?", 2)[0].toLowerCase(Locale.ROOT);
        return path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".png")
                || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".mp4");
    }
}
