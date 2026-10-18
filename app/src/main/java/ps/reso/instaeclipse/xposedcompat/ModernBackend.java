package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Backend for libxposed API 101/102 (Vector 2.2+). Each legacy-style before/after callback becomes
 * one interceptor on the chain, keeping the legacy rules: a result or throwable set in "before"
 * skips the original (and lower hooks) but "after" still runs; an exception thrown by a callback
 * is logged and ignored, never passed to the app. Only loaded by the API 102 entry.
 */
public final class ModernBackend implements HookBackend {

    private static final String TAG = "InstaEclipse";
    private final XposedInterface xposed;

    public ModernBackend(XposedInterface xposed) {
        this.xposed = xposed;
    }

    @Override
    public XC_MethodHook.Unhook hook(Member member, XC_MethodHook callback) {
        Executable target = (Executable) member;
        XposedInterface.HookHandle handle = xposed.hook(target)
                .setPriority(callback.priority)
                .intercept(chain -> intercept(chain, member, callback));
        return new XC_MethodHook.Unhook() {
            @Override public Member getHookedMethod() { return member; }
            @Override public void unhook() { handle.unhook(); }
        };
    }

    private Object intercept(XposedInterface.Chain chain, Member member, XC_MethodHook callback) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = member;
        param.thisObject = chain.getThisObject();
        param.args = chain.getArgs().toArray();

        try {
            callback.callBefore(param);
        } catch (Throwable t) {
            logError("before-callback of " + member + " threw", t);
            param.returnEarly = false;
            param.setOutcome(null, null);
        }

        if (!param.returnEarly) {
            try {
                Object result = param.thisObject != chain.getThisObject() && param.thisObject != null
                        ? chain.proceedWith(param.thisObject, param.args)
                        : chain.proceed(param.args);
                param.setOutcome(result, null);
            } catch (Throwable t) {
                param.setOutcome(null, t);
            }
        }

        Object lastResult = param.getResult();
        Throwable lastThrowable = param.getThrowable();
        try {
            callback.callAfter(param);
        } catch (Throwable t) {
            logError("after-callback of " + member + " threw", t);
            param.setOutcome(lastResult, lastThrowable); // keep the outcome from before the crash
        }

        if (param.getThrowable() != null) throw param.getThrowable();
        if (member instanceof Constructor<?>) return null;
        return param.getResult();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object invokeOriginal(Member member, Object thisObject, Object[] args) throws Throwable {
        try {
            Object[] safeArgs = args != null ? args : new Object[0];
            if (member instanceof Method m) {
                XposedInterface.Invoker invoker = xposed.getInvoker(m);
                invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
                return invoker.invoke(thisObject, safeArgs);
            }
            XposedInterface.CtorInvoker<?> invoker = xposed.getInvoker((Constructor<?>) member);
            invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
            // calling an original constructor on an existing object = invokeSpecial
            return thisObject != null ? invoker.invokeSpecial(thisObject, safeArgs) : invoker.newInstance(safeArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    @Override
    public void log(String message, Throwable t) {
        log(message, t, android.util.Log.INFO);
    }

    private void logError(String message, Throwable t) {
        log(message, t, android.util.Log.ERROR);
    }

    private void log(String message, Throwable t, int priority) {
        if (t != null) xposed.log(priority, TAG, message, t);
        else xposed.log(priority, TAG, message);
    }

    @Override
    public Map<String, ?> companionPrefs() {
        try {
            // written by the companion app through the libxposed service (remote preferences)
            return xposed.getRemotePreferences("instaeclipse_cache").getAll();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String name() {
        return "libxposed " + xposed.getApiVersion() + " on " + xposed.getFrameworkName() + " "
                + xposed.getFrameworkVersion() + " (" + xposed.getFrameworkVersionCode() + ")";
    }
}
