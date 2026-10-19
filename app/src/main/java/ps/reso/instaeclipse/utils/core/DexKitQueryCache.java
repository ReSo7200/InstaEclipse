package ps.reso.instaeclipse.utils.core;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.base.BaseQuery;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.List;

/**
 * Remembers DexKit query results for the running Instagram version (#285).
 *
 * A {@code findMethod}/{@code findClass} scan walks the whole APK (~90 ms each on a mid-range
 * phone), and the hooks run about a hundred of them while Instagram's main thread waits in
 * {@code Application.attach}. The same query on the same APK always returns the same members, so
 * the descriptors found on the first launch are stored in {@link DexKitCache} under a hash of the
 * query's serialized form, and later launches rebuild the result with
 * {@link DexKitBridge#getMethodData(String)} / {@link DexKitBridge#getClassData(String)}, which are
 * direct lookups. The result is real DexKit data, so callers keep using it as before.
 */
public final class DexKitQueryCache {

    /** {@code BaseQuery.innerBuild(FlatBufferBuilder)}: the bytes DexKit itself sends to the native
     *  scan. Reflective, as flatbuffers is only on DexKit's runtime classpath. */
    private static final Method INNER_BUILD, SIZED_BYTES;
    private static final Constructor<?> NEW_BUILDER;

    static {
        Method build = null, bytes = null;
        Constructor<?> ctor = null;
        try {
            Class<?> fbb = Class.forName("com.google.flatbuffers.FlatBufferBuilder", false, BaseQuery.class.getClassLoader());
            ctor = fbb.getConstructor();
            bytes = fbb.getMethod("sizedByteArray");
            build = BaseQuery.class.getDeclaredMethod("innerBuild", fbb);
            build.setAccessible(true);
        } catch (Throwable ignored) {
            build = null; // no key: every query goes straight to DexKit
        }
        INNER_BUILD = build;
        SIZED_BYTES = bytes;
        NEW_BUILDER = ctor;
    }

    private DexKitQueryCache() {}

    /** {@code bridge.findMethod(query)}, answered from the cache when this query ran before. */
    public static MethodDataList findMethod(DexKitBridge bridge, FindMethod query) {
        String key = key("fm", query);
        String cached = load(key);
        if (cached != null) {
            MethodDataList hit = methods(bridge, cached);
            if (hit != null) return hit;
        }
        MethodDataList result = bridge.findMethod(query);
        if (key != null) DexKitCache.saveString(key, joinMethods(result));
        return result;
    }

    /** {@code bridge.findClass(query)}, answered from the cache when this query ran before. */
    public static ClassDataList findClass(DexKitBridge bridge, FindClass query) {
        String key = key("fc", query);
        String cached = load(key);
        if (cached != null) {
            ClassDataList hit = classes(bridge, cached);
            if (hit != null) return hit;
        }
        ClassDataList result = bridge.findClass(query);
        if (key != null) {
            StringBuilder sb = new StringBuilder();
            for (ClassData c : result) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.getDescriptor());
            }
            DexKitCache.saveString(key, sb.toString());
        }
        return result;
    }

    /** {@code method.getCallers()}: a cross-reference scan of the whole APK on a miss. */
    public static MethodDataList callers(DexKitBridge bridge, MethodData method) {
        String key = "q_cl_" + method.getDescriptor();
        String cached = load(key);
        if (cached != null) {
            MethodDataList hit = methods(bridge, cached);
            if (hit != null) return hit;
        }
        MethodDataList result = method.getCallers();
        DexKitCache.saveString(key, joinMethods(result));
        return result;
    }

    private static String load(String key) {
        if (key == null || !DexKitCache.isCacheValid()) return null;
        return DexKitCache.loadString(key);
    }

    /** Hash of the query as DexKit serializes it; null when it cannot be built. */
    static String key(String kind, BaseQuery query) {
        if (INNER_BUILD == null) return null;
        try {
            Object fbb = NEW_BUILDER.newInstance();
            INNER_BUILD.invoke(query, fbb);
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((byte[]) SIZED_BYTES.invoke(fbb));
            StringBuilder sb = new StringBuilder("q_").append(kind).append('_');
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String joinMethods(List<MethodData> methods) {
        StringBuilder sb = new StringBuilder();
        for (MethodData m : methods) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.getDescriptor());
        }
        return sb.toString();
    }

    /** Null when a stored descriptor no longer resolves (treated as a miss). */
    private static MethodDataList methods(DexKitBridge bridge, String cached) {
        MethodDataList list = new MethodDataList();
        if (cached.isEmpty()) return list;
        try {
            for (String desc : cached.split("\n")) {
                MethodData m = bridge.getMethodData(desc);
                if (m == null) return null;
                list.add(m);
            }
        } catch (Throwable t) {
            return null;
        }
        return list;
    }

    private static ClassDataList classes(DexKitBridge bridge, String cached) {
        ClassDataList list = new ClassDataList();
        if (cached.isEmpty()) return list;
        try {
            for (String desc : cached.split("\n")) {
                ClassData c = bridge.getClassData(desc);
                if (c == null) return null;
                list.add(c);
            }
        } catch (Throwable t) {
            return null;
        }
        return list;
    }
}
