/* Field-key fingerprint approach adapted from HushGram MediaBridges.kt.
 * Copyright 2026 HushGram contributors, https://github.com/SysAdminDoc/HushGram
 * SPDX-License-Identifier: GPL-3.0-only. Uses DexKit at runtime, not Morphe bridge stubs.
 */
package ps.reso.instaeclipse.mods.media;

import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Resolved against the loaded APK, rebuilt every process start, with no persisted symbol cache. */
final class SelectedMediaAdapter implements SelectedMediaSnapshot.Access {
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static volatile SelectedMediaAdapter installed;
    private final Map<String, List<Method>> bindings = new HashMap<>();
    private final Class<?> mediaClass;
    private Class<?> reelItemClass;
    private Method reelItemMedia;

    private SelectedMediaAdapter(Class<?> mediaClass) { this.mediaClass = mediaClass; }

    static synchronized void install(DexKitBridge bridge, ClassLoader loader) {
        if (installed != null && installed.mediaClass.getClassLoader() == loader) return;
        try {
            SelectedMediaAdapter adapter = new SelectedMediaAdapter(loader.loadClass(MEDIA));
            for (String key : new String[]{"video_dash_manifest", "video_versions", "image_versions2",
                    "carousel_media", "user", "has_audio", "media_type", "original_media_type",
                    "is_story_image_with_music"}) adapter.resolve(bridge, loader, MEDIA, key);
            adapter.resolve(bridge, loader, "com.instagram.user.model.User", "username");
            for (String key : new String[]{"url", "width", "height", "frame_rate", "codecs", "bitrate"}) {
                adapter.resolve(bridge, loader, "com.instagram.api.schemas.ImmutablePandoVideoVersion", key);
                adapter.resolve(bridge, loader, "com.instagram.model.mediasize.VideoVersion", key);
            }
            adapter.resolve(bridge, loader, "com.instagram.model.mediasize.ImmutablePandoImageInfo", "candidates");
            adapter.named(loader, MEDIA, "id", "getId");
            adapter.resolveStoryItem(bridge, loader);
            for (String type : new String[]{"com.instagram.common.typedurl.ImageUrl",
                    "com.instagram.model.mediasize.VideoVersionIntf"}) {
                adapter.named(loader, type, "url", "getUrl");
                adapter.named(loader, type, "width", "getWidth");
                adapter.named(loader, type, "height", "getHeight");
            }
            installed = adapter;
            ModuleLog.line("(IE|MediaAdapter) resolved properties=" + adapter.bindings.size()
                    + " manifest=" + adapter.bindings.containsKey("video_dash_manifest")
                    + " direct=" + adapter.bindings.containsKey("video_versions")
                    + " story=" + (adapter.reelItemMedia != null));
        } catch (Throwable ignored) {
            installed = null;
            ModuleLog.line("(IE|MediaAdapter) installation unavailable");
        }
    }

    static SelectedMediaSnapshot capture(Object media) throws Exception {
        return capture(null, media, "post");
    }

    static SelectedMediaSnapshot capture(Context context, Object media, String type) throws Exception {
        SelectedMediaAdapter adapter = installed;
        if (adapter == null) throw new IllegalStateException("Media adapter unavailable for this Instagram build");
        if (!adapter.mediaClass.isInstance(media)) throw new IllegalStateException("Selected media is unresolved");
        boolean story = "story".equals(type);
        SelectedMediaSnapshot.Access access = new SelectedMediaSnapshot.Access() {
            @Override public SelectedMediaSnapshot.Value read(Object receiver, String property) throws Exception {
                return adapter.read(receiver, property);
            }

            @Override public String originalVideo(Object receiver) {
                if (context == null || !adapter.mediaClass.isInstance(receiver)) return null;
                return story ? StoryDownloadHook.originalVideoFromSelected(receiver)
                        : FeedVideoDownloadHook.bestVideoUrlFromMedia(receiver);
            }

            @Override public OriginalMediaSelection.Plan originalPlan(Object receiver, String progressive) {
                return FeedVideoDownloadHook.originalPlan(receiver, progressive);
            }

            @Override public OriginalMediaSelection.Audio audio(Object receiver, Object post) {
                Object[] source = FeedVideoDownloadHook.audioSource(receiver, post);
                return source == null ? null : new OriginalMediaSelection.Audio((String) source[0],
                        (Boolean) source[1], ((Number) source[2]).longValue(),
                        ((Number) source[3]).longValue(), (Boolean) source[4]);
            }

            @Override public String originalImage(Object receiver, boolean carouselChild) {
                if (context == null || !adapter.mediaClass.isInstance(receiver)) return null;
                return story ? StoryDownloadHook.originalImageFromSelected(context, receiver)
                        : FeedVideoDownloadHook.originalImageFromSelected(context, receiver, carouselChild);
            }
        };
        SelectedMediaSnapshot result;
        Boolean previous = ForceReelQualityHook.RAW.get();
        ForceReelQualityHook.RAW.set(Boolean.TRUE);
        try { result = SelectedMediaSnapshot.capture(media, access); }
        finally { if (previous == null) ForceReelQualityHook.RAW.remove(); else ForceReelQualityHook.RAW.set(previous); }
        ModuleLog.line("(IE|MediaAdapter) snapshot video=" + result.video + " manifest="
                + (result.manifest != null) + " direct=" + result.renditions.size()
                + " carousel=" + result.children.size());
        return result;
    }

    /** Only a unique captured Media is accepted. No method probing, global last-item or URL buffer. */
    static Object findSelectedMedia(Object holder) {
        SelectedMediaAdapter adapter = installed;
        return adapter == null ? null : uniqueObject(holder, adapter.mediaClass, 4);
    }

    static Object findSelectedMediaAcross(Object... holders) {
        SelectedMediaAdapter adapter = installed;
        return adapter == null ? null : uniqueAcross(holders, adapter.mediaClass, 4);
    }

    static Object findPostMenuMedia(Object handler, Class<?> creatorType) {
        SelectedMediaAdapter adapter = installed;
        return adapter == null ? null : ownedMenuMedia(handler, creatorType, adapter.mediaClass);
    }

    static Object ownedMenuMedia(Object handler, Class<?> creatorType, Class<?> mediaType) {
        // The tapped feed menu owns its selected Media. Its unrelated fragment and
        // listener fields can reach other posts and must not affect this selection.
        Object creator = uniqueDirectTypedField(handler, creatorType);
        return creator == null ? null : uniqueDirectTypedField(creator, mediaType);
    }

    static Object findReelMenuMedia(Object handler, Class<?> ownerType) {
        SelectedMediaAdapter adapter = installed;
        return adapter == null ? null : ownedDirectMedia(handler, ownerType, adapter.mediaClass);
    }

    static Object ownedDirectMedia(Object handler, Class<?> ownerType, Class<?> mediaType) {
        // Used only with the runtime-resolved native Reel option dispatcher owner.
        if (handler == null || ownerType == null || !ownerType.isInstance(handler)) return null;
        return uniqueDirectTypedField(handler, mediaType);
    }

    private static Object uniqueDirectTypedField(Object holder, Class<?> type) {
        if (holder == null || type == null) return null;
        Object selected = null;
        try {
            for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value == null) continue;
                    if (selected != null && selected != value) return null;
                    selected = value;
                }
            }
        } catch (Throwable ignored) { return null; }
        return selected;
    }

    static Object findStoryMedia(Method dispatcher, Object receiver, Object[] args) throws Exception {
        SelectedMediaAdapter adapter = installed;
        if (adapter == null || adapter.reelItemMedia == null) return null;
        Object item = ownedStoryItem(dispatcher, receiver, args, adapter.reelItemClass);
        // Stop at ReelItem; nested wrappers and its secondary Media field are not selection.
        return item == null ? null : adapter.reelItemMedia.invoke(item);
    }

    static Object ownedStoryItem(Method dispatcher, Object receiver, Object[] args, Class<?> itemType) {
        if (dispatcher == null || args == null || itemType == null) return null;
        Class<?> ownerType = dispatcher.getDeclaringClass();
        Class<?>[] parameterTypes = dispatcher.getParameterTypes();
        if (parameterTypes.length != args.length) return null;
        Set<Object> owners = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!Modifier.isStatic(dispatcher.getModifiers())) {
            if (!ownerType.isInstance(receiver)) return null;
            owners.add(receiver);
        }
        for (int i = 0; i < args.length; i++) {
            if (parameterTypes[i] == ownerType && args[i] != null) {
                if (!ownerType.isInstance(args[i])) return null;
                owners.add(args[i]);
            } else if (parameterTypes[i] == itemType && args[i] != null) {
                if (!itemType.isInstance(args[i])) return null;
                targets.add(args[i]);
            }
        }
        if (owners.size() > 1 || targets.size() > 1) return null;
        // The native overflow dispatcher reads its own direct ReelItem field. Its other
        // controller/listener arguments can reach preloaded Stories and are not selection.
        // Static helpers receive the same declaring-class owner as an explicit argument.
        try {
            for (Object owner : owners) {
                for (Class<?> c = ownerType; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType() != itemType) continue;
                        field.setAccessible(true);
                        Object value = field.get(owner);
                        if (value != null) targets.add(value);
                    }
                }
            }
        } catch (Throwable ignored) { return null; }
        return targets.size() == 1 ? targets.iterator().next() : null;
    }
    private void resolveStoryItem(DexKitBridge bridge, ClassLoader loader) {
        try {
            String type = "com.instagram.model.reels.ReelItem";
            Set<Method> matches = new LinkedHashSet<>();
            for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(type).paramCount(0).returnType(MEDIA)
                    .usingEqStrings(List.of("Required value was null."))))) {
                Method method = data.getMethodInstance(loader);
                if (!Modifier.isStatic(method.getModifiers())) matches.add(method);
            }
            if (matches.size() == 1) {
                reelItemClass = loader.loadClass(type);
                reelItemMedia = matches.iterator().next();
                reelItemMedia.setAccessible(true);
            }
        } catch (Throwable ignored) { }
    }

    static Object uniqueObject(Object holder, Class<?> type, int depth) {
        return uniqueAcross(new Object[]{holder}, type, depth);
    }

    private static final class Node {
        final Object value;
        final int depth;
        Node(Object value, int depth) { this.value = value; this.depth = depth; }
    }

    static Object uniqueAcross(Object[] holders, Class<?> type, int depth) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> found = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        for (Object holder : holders) if (holder != null) queue.add(new Node(holder, depth));
        // Breadth first: an alias is always expanded with its greatest remaining depth.
        // Reaching the work bound fails closed; it must not hide an unvisited second item.
        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            Object holder = node.value;
            if (!seen.add(holder)) continue;
            if (seen.size() >= 128) return null;
            if (type.isInstance(holder)) {
                found.add(holder);
                if (found.size() > 1) return null;
                continue;
            }
            if (node.depth == 0) continue;
            String name = holder.getClass().getName();
            if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("androidx.")) continue;
            for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object value = f.get(holder);
                        if (value != null) queue.addLast(new Node(value, node.depth - 1));
                    } catch (Throwable ignored) { }
                }
            }
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }
    private void named(ClassLoader loader, String type, String key, String name) {
        try {
            Method method = loader.loadClass(type).getMethod(name);
            if (method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())) add(key, method);
        } catch (Throwable ignored) { }
    }

    private void resolve(DexKitBridge bridge, ClassLoader loader, String type, String key) {
        // Schema-key tables also match <clinit>()V. Filter its DEX metadata BEFORE trying
        // Java reflection: DexKit cannot turn a class initializer into a java.lang.Method.
        Resolution result = new Resolution();
        collectCandidates(bridge, loader, type, key, false, result);
        collectCandidates(bridge, loader, type, key, true, result);
        if (result.errors != 0 || result.matches.size() != 1) return;
        try {
            Method method = result.matches.iterator().next();
            add(key, method);
            // Resolve the interface declaration from this build's concrete Pando getter;
            // it dispatches correctly when a sibling non-Pando implementation is returned.
            for (Class<?> iface : method.getDeclaringClass().getInterfaces()) {
                try { add(key, iface.getMethod(method.getName())); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static final class Resolution {
        final Set<Method> matches = new LinkedHashSet<>();
        int errors;
    }

    private void collectCandidates(DexKitBridge bridge, ClassLoader loader, String type,
            String key, boolean numeric, Resolution result) {
        try {
            MethodMatcher matcher = MethodMatcher.create().declaredClass(type).paramCount(0);
            if (numeric) matcher.usingNumbers(key.hashCode());
            else matcher.usingEqStrings(List.of(key));
            List<MethodData> candidates = bridge.findMethod(FindMethod.create().matcher(matcher));
            for (MethodData data : candidates) {
                try {
                    Method method = getterCandidate(key, data.getName(), data.getReturnTypeName(),
                            data.getParamCount(), data.getModifiers(), () -> data.getMethodInstance(loader));
                    if (method != null) result.matches.add(method);
                } catch (Throwable ignored) {
                    result.errors++;
                }
            }
        } catch (Throwable ignored) {
            result.errors++;
        }
    }

    private static boolean acceptable(String key, Method method) {
        if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) return false;
        Class<?> rt = method.getReturnType();
        return switch (key) {
            case "video_versions", "carousel_media", "candidates" -> List.class.isAssignableFrom(rt);
            case "video_dash_manifest", "url", "username", "codecs" -> rt == String.class;
            case "has_audio", "is_story_image_with_music" -> rt == Boolean.class || rt == boolean.class;
            case "width", "height", "frame_rate", "bitrate", "media_type", "original_media_type" ->
                    Number.class.isAssignableFrom(rt) || rt == int.class || rt == long.class || rt == float.class || rt == double.class;
            default -> !rt.isPrimitive();
        };
    }

    @FunctionalInterface interface MethodLookup { Method get() throws Throwable; }

    static Method getterCandidate(String key, String name, String returnType,
            int parameterCount, int modifiers, MethodLookup lookup) throws Throwable {
        if (name == null || name.startsWith("<") || "void".equals(returnType)
                || parameterCount != 0 || Modifier.isStatic(modifiers)) return null;
        Method method = lookup.get();
        return acceptable(key, method) ? method : null;
    }

    private void add(String key, Method method) {
        method.setAccessible(true);
        List<Method> methods = bindings.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!methods.contains(method)) methods.add(method);
    }

    @Override public SelectedMediaSnapshot.Value read(Object receiver, String property) throws Exception {
        if (receiver == null) return SelectedMediaSnapshot.Value.missing();
        List<Method> candidates = bindings.get(property);
        if (candidates == null) return SelectedMediaSnapshot.Value.missing();
        Method selected = null;
        for (Method candidate : candidates) {
            if (!candidate.getDeclaringClass().isInstance(receiver)) continue;
            if (selected != null && !selected.getName().equals(candidate.getName()))
                throw new IllegalStateException("Ambiguous media property: " + property);
            selected = candidate;
        }
        if (selected == null) return SelectedMediaSnapshot.Value.missing();
        try { return SelectedMediaSnapshot.Value.of(selected.invoke(receiver)); }
        catch (Throwable ignored) { throw new IllegalStateException("Media property read failed: " + property); }
    }
}
