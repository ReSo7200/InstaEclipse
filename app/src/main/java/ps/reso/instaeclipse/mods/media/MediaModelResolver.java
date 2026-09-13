package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves Instagram's legacy and modern media dictionary models independently. */
final class MediaModelResolver {

    static final String MUTABLE_DICT_CLASS = "com.instagram.feed.media.MutableMediaDictIntf";
    static final String LIVE_TREE_DICT_CLASS = "com.instagram.feed.media.LiveTreeMediaDict";

    static final class Result {
        final Class<?> mutableDictClass;
        final Class<?> liveTreeDictClass;
        final List<Method> listCandidates;

        Result(Class<?> mutableDictClass, Class<?> liveTreeDictClass,
               List<Method> listCandidates) {
            this.mutableDictClass = mutableDictClass;
            this.liveTreeDictClass = liveTreeDictClass;
            this.listCandidates = listCandidates;
        }
    }

    private MediaModelResolver() {}

    static Result resolve(ClassLoader classLoader) {
        return resolve(classLoader, null);
    }

    static Result resolve(ClassLoader classLoader, Class<?> discoveredDictClass) {
        Class<?> mutable = tryLoad(classLoader, MUTABLE_DICT_CLASS);
        Class<?> liveTree = tryLoad(classLoader, LIVE_TREE_DICT_CLASS);
        if (liveTree == null) liveTree = discoveredDictClass;
        List<Method> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (mutable != null) {
            addListMethods(mutable, candidates, seen);
            for (Class<?> superInterface : mutable.getInterfaces()) {
                if (isInstagramModelClass(superInterface.getName())) {
                    addListMethods(superInterface, candidates, seen);
                }
            }
        }

        // Never nest this lookup under MutableMediaDictIntf. Recent Instagram versions
        // can remove the legacy interface while retaining the concrete Pando model.
        if (liveTree != null) addListMethods(liveTree, candidates, seen);
        return new Result(mutable, liveTree, candidates);
    }

    static Object findDictionary(Object media, Result model, int maxDepth) {
        if (media == null || model == null || maxDepth < 0) return null;
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Object found = findObjectOfType(media, model.liveTreeDictClass, maxDepth, visited);
        if (found != null) return found;
        visited.clear();
        return findObjectOfType(media, model.mutableDictClass, maxDepth, visited);
    }

    static Object findObjectOfType(Object root, Class<?> target, int maxDepth) {
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return findObjectOfType(root, target, maxDepth, visited);
    }

    private static Object findObjectOfType(Object obj, Class<?> target, int depth,
                                           Set<Object> visited) {
        if (obj == null || target == null || depth < 0 || !visited.add(obj)) return null;
        if (target.isInstance(obj)) return obj;

        if (obj instanceof Map<?, ?> map) {
            if (depth == 0) return null;
            for (Object value : map.values()) {
                Object nested = findObjectOfType(value, target, depth - 1, visited);
                if (nested != null) return nested;
            }
            return null;
        }
        if (obj instanceof Iterable<?> iterable) {
            if (depth == 0) return null;
            for (Object value : iterable) {
                Object nested = findObjectOfType(value, target, depth - 1, visited);
                if (nested != null) return nested;
            }
            return null;
        }
        if (obj instanceof Object[] array) {
            if (depth == 0) return null;
            for (Object value : array) {
                Object nested = findObjectOfType(value, target, depth - 1, visited);
                if (nested != null) return nested;
            }
            return null;
        }

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value == null) continue;
                    if (target.isInstance(value)) return value;
                    boolean traversable = isInstagramModelClass(value.getClass().getName())
                            || value instanceof Iterable<?> || value instanceof Map<?, ?>
                            || value instanceof Object[];
                    if (depth == 0 || !traversable) continue;
                    Object nested = findObjectOfType(value, target, depth - 1, visited);
                    if (nested != null) return nested;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        // Some Pando models expose their backing node only through a no-arg getter.
        // Probe model-returning getters after the field walk, with the same depth limit.
        if (depth > 0 && isInstagramModelClass(obj.getClass().getName())) {
            cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Method method : cls.getDeclaredMethods()) {
                    if (method.getParameterCount() != 0
                            || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                    Class<?> returnType = method.getReturnType();
                    if (returnType.isPrimitive() || returnType == String.class
                            || returnType == Class.class) continue;
                    if (!target.isAssignableFrom(returnType)
                            && !isInstagramModelClass(returnType.getName())
                            && !Collection.class.isAssignableFrom(returnType)
                            && !Map.class.isAssignableFrom(returnType)) continue;
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(obj);
                        Object nested = findObjectOfType(value, target, depth - 1, visited);
                        if (nested != null) return nested;
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Class<?> tryLoad(ClassLoader classLoader, String name) {
        try {
            return classLoader.loadClass(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addListMethods(Class<?> type, List<Method> out, Set<String> seen) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0
                    || !List.class.isAssignableFrom(method.getReturnType())) continue;
            String key = method.getDeclaringClass().getName() + '#' + method.getName()
                    + ':' + method.getReturnType().getName();
            if (!seen.add(key)) continue;
            try { method.setAccessible(true); } catch (Throwable ignored) {}
            out.add(method);
        }
    }

    private static boolean isInstagramModelClass(String name) {
        return name.startsWith("X.")
                || name.startsWith("com.instagram.")
                || name.startsWith("com.facebook.")
                || name.startsWith("ps.reso.instaeclipse.mods.media.");
    }
}
