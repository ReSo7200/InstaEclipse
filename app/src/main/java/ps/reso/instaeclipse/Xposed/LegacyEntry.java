package ps.reso.instaeclipse.Xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.xposedcompat.LegacyBackend;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Entry for frameworks with the legacy Xposed API (assets/xposed_init): LSPatch and LSPosed builds
 * before libxposed 101. Frameworks with API 101+ load {@link ModernEntry} instead.
 */
public class LegacyEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private final Module module = new Module();

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.setBackend(new LegacyBackend());
        Module.setModulePaths(startupParam.modulePath, null);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        ps.reso.instaeclipse.xposedcompat.XC_LoadPackage.LoadPackageParam p =
                new ps.reso.instaeclipse.xposedcompat.XC_LoadPackage.LoadPackageParam();
        p.packageName = lpparam.packageName;
        p.processName = lpparam.processName;
        p.classLoader = lpparam.classLoader;
        p.appInfo = lpparam.appInfo;
        p.isFirstApplication = lpparam.isFirstApplication;
        module.handleLoadPackage(p);
    }
}
