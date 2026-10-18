package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Member;
import java.util.Map;

/**
 * What the active Xposed framework provides. {@link XposedBridge} forwards to it; the entry class
 * that the framework loaded installs it ({@link LegacyBackend} or the API 102 one).
 */
public interface HookBackend {

    XC_MethodHook.Unhook hook(Member method, XC_MethodHook callback);

    Object invokeOriginal(Member method, Object thisObject, Object[] args) throws Throwable;

    void log(String message, Throwable t);

    /** The companion app's settings file ("instaeclipse_cache"), or null when unreadable. */
    Map<String, ?> companionPrefs();

    /** Name shown in logs ("legacy" / "libxposed 102 on Vector …"). */
    String name();
}
