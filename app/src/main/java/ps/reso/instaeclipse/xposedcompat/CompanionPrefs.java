package ps.reso.instaeclipse.xposedcompat;

import java.util.Collections;
import java.util.Map;

/**
 * The companion app's "instaeclipse_cache" settings as seen from the hooked app: through
 * XSharedPreferences on legacy frameworks, through remote preferences on libxposed 102.
 */
public final class CompanionPrefs {

    private CompanionPrefs() {}

    /** All values, or an empty map when the framework can't read them (yet). */
    public static Map<String, ?> all() {
        try {
            Map<String, ?> m = XposedBridge.backend().companionPrefs();
            return m != null ? m : Collections.emptyMap();
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    public static String getString(String key, String def) {
        Object v = all().get(key);
        return v instanceof String s ? s : def;
    }
}
