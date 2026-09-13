package ps.reso.instaeclipse.utils.log;

import android.util.Log;

/**
 * Drop-in replacement for direct XposedBridge.log(...) calls: prefixes the caller's
 * class/method/line, still logs to logcat, and additionally appends to Logging's in-memory
 * ring buffer so the message shows up in InstaEclipse's in-app log viewer, not just adb logcat.
 *
 * Logs to logcat via android.util.Log rather than XposedBridge.log: LSPosed refuses to let a
 * module hook XposedBridge's own methods ("Do not allow hooking inner methods"), which is
 * what made the original auto-capture-via-hook approach a dead end, and Class.forName("de.robv
 * .android.xposed.XposedBridge") also fails under LSPosed's legacy-API compat even though a
 * direct compiled reference to XposedBridge works fine. android.util.Log has neither problem
 * and works identically in the companion app's own (un-hooked) process.
 */
public final class ModuleLog {

    private static final String TAG = "InstaEclipse";

    /** Verbose/debug logging. Off by default so per-event PROBE/DEBUG chatter and the stack-walk
     *  they trigger don't ship in release. Normal status lines (line()) always log. */
    public static volatile boolean verbose = false;

    private ModuleLog() {}

    /** Debug-only line: logs nothing unless {@link #verbose} is on. Use for high-frequency or
     *  sensitive PROBE/DEBUG output so it stays out of release logs by default. */
    public static void probe(String msg) {
        if (verbose) line(msg);
    }

    private static String getCallerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < stack.length; i++) {
            String cn = stack[i].getClassName();
            if (!cn.equals(ModuleLog.class.getName()) && !cn.equals(Logging.class.getName())
                    && !cn.equals(Thread.class.getName()) && !cn.startsWith("de.robv.android.xposed.")) {
                String simpleName = cn.substring(cn.lastIndexOf('.') + 1);
                return "[" + simpleName + "." + stack[i].getMethodName() + ":" + stack[i].getLineNumber() + "] ";
            }
        }
        return "";
    }

    public static void line(String msg) {
        String formatted = getCallerInfo() + msg;
        Logging.append(formatted);
        Log.i(TAG, formatted);
    }

    public static void line(String msg, Throwable t) {
        String stackTraceStr = t != null ? Log.getStackTraceString(t) : "";
        String fullMsg = msg + (stackTraceStr.isEmpty() ? "" : "\n" + stackTraceStr);
        String formatted = getCallerInfo() + fullMsg;
        Logging.append(formatted);
        Log.i(TAG, formatted);
    }
}
