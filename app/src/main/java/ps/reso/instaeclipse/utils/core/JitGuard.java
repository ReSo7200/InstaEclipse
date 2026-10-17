package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Keeps ART's JIT away from LSPlant's generated hook stubs (class "LSPHooker_", one per hooked
 * method, defined from an in-memory dex). On Android 13 compiling such a stub crashes the JIT in
 * DexCache::SetResolvedType (SIGSEGV in "Jit thread pool"), so any hook whose target is called a
 * few thousand times kills Instagram. Every hook callback calls {@link #tick}, which takes a
 * backtrace, finds the stub classes on it and marks their methods kAccCompileDontBother.
 */
public final class JitGuard {
    private static final int K_ACC_COMPILE_DONT_BOTHER = 0x02000000; // art/libdexfile/dex/modifiers.h
    private static final int ACCESS_FLAGS_OFFSET = 4;                  // ArtMethod: declaring_class_, access_flags_

    private static int calls;
    private static boolean broken;
    private static final Set<Class<?>> done = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static Field backtrace, artMethod;
    private static Method peekInt, pokeInt;

    private JitGuard() {}

    /** hooked members whose stub was already looked for; replaced (never mutated) so reads need no lock */
    private static volatile IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();

    /** Called first thing in every hook callback with the hooked member. The first call for each
     *  member scans right away (its stub is on the stack now); after that every 64th call samples. */
    public static void tick(Object member) {
        if (broken) return;
        boolean first = member != null && !seen.containsKey(member);
        if (!first && ((++calls) & 0x3F) != 0) return;
        if (first) {
            synchronized (JitGuard.class) {
                IdentityHashMap<Object, Boolean> next = new IdentityHashMap<>(seen);
                next.put(member, Boolean.TRUE);
                seen = next;
            }
        }
        try {
            scan();
        } catch (Throwable t) {
            broken = true;
            ModuleLog.line("(IE|JitGuard) disabled: " + t);
        }
    }

    private static void scan() throws Throwable {
        if (backtrace == null) {
            backtrace = Throwable.class.getDeclaredField("backtrace");
            backtrace.setAccessible(true);
            artMethod = Class.forName("java.lang.reflect.Executable").getDeclaredField("artMethod");
            artMethod.setAccessible(true);
            Class<?> mem = Class.forName("libcore.io.Memory");
            peekInt = mem.getDeclaredMethod("peekInt", long.class, boolean.class);
            pokeInt = mem.getDeclaredMethod("pokeInt", long.class, int.class, boolean.class);
            peekInt.setAccessible(true);
            pokeInt.setAccessible(true);
        }
        // ART's backtrace: [0] = method/pc array, [1..] = the declaring class of each frame.
        Object bt = backtrace.get(new Throwable());
        if (!(bt instanceof Object[] frames)) return;
        for (Object f : frames) {
            if (!(f instanceof Class<?> c) || done.contains(c) || !c.getName().startsWith("LSPHooker_")) continue;
            done.add(c);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getDeclaringClass() != c) continue; // the backup copy of the original method
                long art = (long) artMethod.get(m);
                if (art == 0) continue;
                int flags = (int) peekInt.invoke(null, art + ACCESS_FLAGS_OFFSET, false);
                // sanity check the layout: the low modifier bits must match what reflection reports
                if ((flags & 0x0F) != (m.getModifiers() & 0x0F)) throw new IllegalStateException("ArtMethod layout " + Integer.toHexString(flags));
                if ((flags & K_ACC_COMPILE_DONT_BOTHER) == 0) {
                    pokeInt.invoke(null, art + ACCESS_FLAGS_OFFSET, flags | K_ACC_COMPILE_DONT_BOTHER, false);
                    n++;
                }
            }
            if (done.size() <= 3 || done.size() % 25 == 0)
                ModuleLog.line("(IE|JitGuard) stubs protected: " + done.size() + " (last: " + n + " of "
                        + java.util.Arrays.toString(c.getDeclaredMethods()) + ")");
        }
    }
}
