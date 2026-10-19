package ps.reso.instaeclipse.Xposed;

import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import ps.reso.instaeclipse.xposedcompat.ModernBackend;
import ps.reso.instaeclipse.xposedcompat.XC_LoadPackage;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Entry for libxposed API 101/102 frameworks (Vector 2.2+), listed in
 * META-INF/xposed/java_init.list. Runs the same {@link Module} bootstrap as {@link LegacyEntry}.
 */
public class ModernEntry extends XposedModule {

    private final Module module = new Module();
    private String processName;

    public ModernEntry() {
        super();
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.setBackend(new ModernBackend(this));
        ApplicationInfo self = getModuleApplicationInfo();
        Module.setModulePaths(self.sourceDir, self.nativeLibraryDir);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        XC_LoadPackage.LoadPackageParam p = new XC_LoadPackage.LoadPackageParam();
        p.packageName = param.getPackageName();
        p.processName = processName;
        p.classLoader = param.getClassLoader();
        p.appInfo = param.getApplicationInfo();
        p.isFirstApplication = param.isFirstPackage();
        module.handleLoadPackage(p);
    }
}
