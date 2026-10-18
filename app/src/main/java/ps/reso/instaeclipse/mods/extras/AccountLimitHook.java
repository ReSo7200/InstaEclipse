package ps.reso.instaeclipse.mods.extras;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Lifts the logged-in account limit (5). The account switcher's "add account" handler (the method
 * logging "account_switcher_max_limit_reached") asks a static {@code canAddAccount(session, max)}
 * check — {@code loggedInCount < max} — and forcing it true lets the add-account flow continue.
 */
public class AccountLimitHook {

    public void install(DexKitBridge bridge, ClassLoader cl) {
        Method check = ExtrasLookup.method("Extras_AccountLimit", cl, () -> {
            for (MethodData owner : ExtrasLookup.byStrings(bridge, "account_switcher_max_limit_reached")) {
                Method m = ExtrasLookup.firstStaticInvoke(owner, cl, c -> c.getReturnType() == boolean.class
                        && c.getParameterCount() == 2 && c.getParameterTypes()[1] == int.class);
                if (m != null) return m;
            }
            return null;
        });
        if (check == null) return;
        XposedBridge.hookMethod(check, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.unlimitedAccounts) param.setResult(true);
            }
        });
        FeatureStatusTracker.setHooked("unlimitedAccounts");
        ModuleLog.line("(InstaEclipse | UnlimitedAccounts): ✅ hooked " + check.getDeclaringClass().getName() + "." + check.getName());
    }
}
