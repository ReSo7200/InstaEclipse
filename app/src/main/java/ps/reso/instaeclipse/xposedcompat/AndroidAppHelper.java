package ps.reso.instaeclipse.xposedcompat;

import android.app.Application;

/** The hooked app's Application (legacy {@code android.app.AndroidAppHelper} is framework-only). */
public final class AndroidAppHelper {

    private static volatile Application app;

    private AndroidAppHelper() {}

    /** Set by the bootstrap as soon as the app's Application exists. */
    public static void setCurrentApplication(Application a) {
        if (a != null) app = a;
    }

    public static Application currentApplication() {
        Application a = app;
        if (a != null) return a;
        try {
            Object v = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (v instanceof Application found) app = a = found;
        } catch (Throwable ignored) {}
        return a;
    }
}
