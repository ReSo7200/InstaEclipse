package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Member lists that never throw. On Android 12 and older, {@code getDeclaredFields()} and friends
 * throw NoClassDefFoundError for a whole class when one member mentions a newer platform type
 * (Instagram's classes reference e.g. android.window.OnBackInvokedDispatcher, Android 13). The
 * hooks walk Instagram's classes by member types, so such a class is treated as having no members
 * instead of aborting the walk (seen on Redmi phones: Story Info and Story links stopped working).
 */
public final class SafeReflect {
    private static final Field[] NO_FIELDS = new Field[0];
    private static final Method[] NO_METHODS = new Method[0];
    private static final Constructor<?>[] NO_CONSTRUCTORS = new Constructor<?>[0];

    private SafeReflect() {}

    public static Field[] fields(Class<?> c) {
        try { return c.getDeclaredFields(); } catch (Throwable t) { return NO_FIELDS; }
    }

    public static Field[] publicFields(Class<?> c) {
        try { return c.getFields(); } catch (Throwable t) { return NO_FIELDS; }
    }

    public static Method[] methods(Class<?> c) {
        try { return c.getDeclaredMethods(); } catch (Throwable t) { return NO_METHODS; }
    }

    public static Method[] publicMethods(Class<?> c) {
        try { return c.getMethods(); } catch (Throwable t) { return NO_METHODS; }
    }

    public static Constructor<?>[] constructors(Class<?> c) {
        try { return c.getDeclaredConstructors(); } catch (Throwable t) { return NO_CONSTRUCTORS; }
    }
}
