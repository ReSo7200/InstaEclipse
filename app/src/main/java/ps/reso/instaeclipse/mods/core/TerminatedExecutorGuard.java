package ps.reso.instaeclipse.mods.core;

import java.util.concurrent.ThreadPoolExecutor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Crash guard for the carousel / realtime teardown race seen on Instagram 446+ and 447.0.0.39+.
 *
 * When a carousel's realtime media stream (and, downstream, the Analytics2 event pipeline) is torn
 * down — e.g. right after a download or copy-media action — IG's native
 * {@code TigonRepeatingForwardingRequestToken} / analytics processor can still submit a task to a
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor} that has ALREADY shut down. The executor's
 * default {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} then throws
 * {@link java.util.concurrent.RejectedExecutionException}, which unwinds back through native JNI and
 * hard-crashes Instagram (every crash variant terminates at AbortPolicy.rejectedExecution). The task
 * can never run on a dead executor, so the only sane outcome is to drop it — exactly what a
 * DiscardPolicy would do — instead of crashing.
 *
 * This hook narrows to precisely that: it intercepts {@code AbortPolicy.rejectedExecution} and
 * swallows the rejection ONLY when the target executor is already shut down. A rejection on a live
 * executor (genuine queue-full backpressure) still throws normally, so nothing about normal
 * operation changes. The hook sits on the rejection path only (never a hot path) and is fully
 * try/catch-guarded.
 */
public class TerminatedExecutorGuard {

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.util.concurrent.ThreadPoolExecutor$AbortPolicy", classLoader,
                    "rejectedExecution",
                    Runnable.class, ThreadPoolExecutor.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ThreadPoolExecutor ex = (ThreadPoolExecutor) param.args[1];
                                // isShutdown() is true for SHUTDOWN/STOP/TERMINATED — in every one of
                                // those states no new task can ever run, so dropping it is correct.
                                if (ex != null && ex.isShutdown()) {
                                    param.setResult(null); // return void without throwing => task dropped
                                    ModuleLog.probe("(IE|ExecGuard) dropped task rejected by shut-down executor");
                                }
                            } catch (Throwable ignored) {
                                // Never let the guard itself interfere with rejection handling.
                            }
                        }
                    }
            );
            ModuleLog.line("(IE|ExecGuard) ✅ installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|ExecGuard) ⚠️ install failed: " + t.getMessage());
        }
    }
}
