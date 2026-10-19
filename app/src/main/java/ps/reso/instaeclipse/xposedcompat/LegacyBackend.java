package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Member;
import java.util.Map;

/**
 * Backend for frameworks with the legacy API (LSPatch, LSPosed before libxposed 101): our
 * callbacks run inside real {@code de.robv.android.xposed.XC_MethodHook}s. Only loaded by the
 * legacy entry, so frameworks without these classes never touch it.
 */
public final class LegacyBackend implements HookBackend {

    @Override
    public XC_MethodHook.Unhook hook(Member method, XC_MethodHook callback) {
        de.robv.android.xposed.XC_MethodHook.Unhook u = de.robv.android.xposed.XposedBridge.hookMethod(method,
                new de.robv.android.xposed.XC_MethodHook(callback.priority) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XC_MethodHook.MethodHookParam p = mirror(param);
                        callback.callBefore(p);
                        writeBack(p, param);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        XC_MethodHook.MethodHookParam p = mirror(param);
                        callback.callAfter(p);
                        writeBack(p, param);
                    }
                });
        return new XC_MethodHook.Unhook() {
            @Override public Member getHookedMethod() { return u.getHookedMethod(); }
            @Override public void unhook() { u.unhook(); }
        };
    }

    private static XC_MethodHook.MethodHookParam mirror(de.robv.android.xposed.XC_MethodHook.MethodHookParam src) {
        XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
        p.method = src.method;
        p.thisObject = src.thisObject;
        p.args = src.args; // same array: element writes reach the call directly
        p.setOutcome(src.getResult(), src.getThrowable());
        return p;
    }

    private static void writeBack(XC_MethodHook.MethodHookParam p, de.robv.android.xposed.XC_MethodHook.MethodHookParam dst) {
        dst.thisObject = p.thisObject;
        dst.args = p.args;
        if (p.returnEarly) {
            if (p.getThrowable() != null) dst.setThrowable(p.getThrowable());
            else dst.setResult(p.getResult());
        } else if (p.getThrowable() != dst.getThrowable() || p.getResult() != dst.getResult()) {
            // after-callback changed the outcome
            if (p.getThrowable() != null) dst.setThrowable(p.getThrowable());
            else dst.setResult(p.getResult());
        }
    }

    @Override
    public Object invokeOriginal(Member method, Object thisObject, Object[] args) throws Throwable {
        return de.robv.android.xposed.XposedBridge.invokeOriginalMethod(method, thisObject, args);
    }

    @Override
    public void log(String message, Throwable t) {
        de.robv.android.xposed.XposedBridge.log(message);
        if (t != null) de.robv.android.xposed.XposedBridge.log(t);
    }

    private de.robv.android.xposed.XSharedPreferences companion;

    @Override
    public synchronized Map<String, ?> companionPrefs() {
        try {
            if (companion == null) {
                companion = new de.robv.android.xposed.XSharedPreferences("ps.reso.instaeclipse", "instaeclipse_cache");
            }
            companion.reload();
            return companion.getAll();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String name() {
        return "legacy XposedBridge " + de.robv.android.xposed.XposedBridge.getXposedVersion();
    }
}
