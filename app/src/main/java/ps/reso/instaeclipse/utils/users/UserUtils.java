package ps.reso.instaeclipse.utils.users;

import java.lang.reflect.Method;

/**
 * Utilities for extracting usernames from obfuscated Instagram User objects.
 *
 * {@link #userUsernameGetter} is resolved once by MediaDownloadHook via DexKit
 * (anchored on the stable int constant -265713450) and stored here so any hook
 * can call {@link #callUsernameGetter} without duplicating resolution logic.
 */
public class UserUtils {

    /** No-arg String getter on Instagram's User model, resolved via DexKit. */
    public static Method userUsernameGetter;

    /**
     * Returns the username from an Instagram User object, or null if not found.
     * Primary path: {@link #userUsernameGetter} (DexKit-resolved).
     * Fallback: reflective getUsername() lookup.
     */
    public static String callUsernameGetter(Object user) {
        if (user == null) return null;
        if (userUsernameGetter != null) {
            try {
                Object r = userUsernameGetter.invoke(user);
                if (r instanceof String s && isValidUsername(s)) return s;
            } catch (Throwable ignored) {}
        }
        try {
            Object r = user.getClass().getMethod("getUsername").invoke(user);
            if (r instanceof String s && isValidUsername(s)) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isValidUsername(String s) {
        return s != null && s.length() >= 2 && s.length() <= 30
                && s.matches("[a-zA-Z0-9._]+")
                && !s.matches("\\d+");
    }
}
