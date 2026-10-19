package ps.reso.instaeclipse.utils.i18n;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import androidx.annotation.StringRes;

import ps.reso.instaeclipse.utils.core.CommonUtils;

/**
 * Loads string resources from the InstaEclipse module APK while running inside
 * the host app (Instagram) process.
 *
 * createPackageContext already inherits the device's current locale/configuration,
 * so Android's resource system automatically picks the correct values-xx folder.
 * No manual locale override needed. Some frameworks (Vector v2.2) hide the module
 * package from the host, so createPackageContext throws; then the module APK is
 * opened directly (XModuleResources on Module.moduleSourceDir) with the host's
 * configuration, which keeps the same locale selection.
 */
public final class I18n {

    private static volatile Resources apkResources;
    private static volatile Configuration apkConfig;

    private I18n() {}

    public static String t(Context hostContext, @StringRes int resId, Object... args) {
        try {
            Resources res = moduleResources(hostContext);
            return args.length == 0 ? res.getString(resId) : res.getString(resId, args);
        } catch (Exception e) {
            return "";
        }
    }

    /** The module's Resources, localized like the host context. */
    public static Resources moduleResources(Context hostContext) throws Exception {
        try {
            return hostContext.createPackageContext(
                    CommonUtils.MY_PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY).getResources();
        } catch (Exception notVisible) {
            Configuration cfg = hostContext.getResources().getConfiguration();
            Resources res = apkResources;
            if (res == null || apkConfig == null || !apkConfig.equals(cfg)) {
                String apk = ps.reso.instaeclipse.Xposed.Module.moduleSourceDir;
                if (apk == null) throw notVisible;
                Resources x = ps.reso.instaeclipse.xposedcompat.ModuleResources.get(hostContext);
                if (x == null) throw notVisible;
                res = new Resources(x.getAssets(), hostContext.getResources().getDisplayMetrics(), cfg);
                apkConfig = new Configuration(cfg);
                apkResources = res;
            }
            return res;
        }
    }
}
