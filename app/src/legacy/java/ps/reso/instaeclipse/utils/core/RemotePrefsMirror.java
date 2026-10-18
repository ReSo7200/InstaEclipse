package ps.reso.instaeclipse.utils.core;

import android.content.Context;

/**
 * Legacy flavor: nothing to mirror. Instagram reads the companion's "instaeclipse_cache" through
 * XSharedPreferences on legacy frameworks (see the modern flavor's version for API 102).
 */
public final class RemotePrefsMirror {

    private RemotePrefsMirror() {}

    public static void start(Context context) {}

    public static boolean isConnected() {
        return false;
    }
}
