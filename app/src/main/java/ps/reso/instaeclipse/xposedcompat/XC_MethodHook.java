package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Member;

/**
 * Before/after hook callback, same shape as the legacy {@code de.robv.android.xposed.XC_MethodHook}
 * so feature code keeps its hooks unchanged. {@link XposedBridge} runs it on the active backend
 * (legacy XposedBridge, or the libxposed API 102 interceptor chain).
 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    /** Runs {@link #beforeHookedMethod} (for backends outside this package). */
    public final void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    /** Runs {@link #afterHookedMethod} (for backends outside this package). */
    public final void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    /** The hooked call: its method, receiver, arguments and (after the call) its outcome. */
    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        /** set when a before-callback decided the outcome, so the original is not called */
        public boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }

        /** Backend use: the original's outcome, without marking the call as decided. */
        public void setOutcome(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
        }
    }

    /** Returned by the hook methods; removes the hook. */
    public interface Unhook {
        Member getHookedMethod();

        void unhook();
    }
}
