package ps.reso.instaeclipse.xposedcompat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;

/**
 * The module APK's own resources inside the hooked app (replaces the framework-only
 * {@code XModuleResources}). Built through the public PackageManager API from the APK path, so
 * it works on every framework.
 */
public final class ModuleResources {

    private static volatile String apkPath;
    private static volatile Resources cached;

    private ModuleResources() {}

    /** The module APK path, set by the entry the framework loaded. */
    public static void setApkPath(String path) {
        apkPath = path;
        cached = null;
    }

    public static String apkPath() {
        return apkPath;
    }

    /** The module's Resources, or null when there is no context yet. */
    public static Resources get() {
        Resources r = cached;
        if (r != null) return r;
        Context ctx = AndroidAppHelper.currentApplication();
        return ctx != null ? get(ctx) : null;
    }

    public static Resources get(Context ctx) {
        Resources r = cached;
        if (r != null) return r;
        String path = apkPath;
        if (path == null || ctx == null) return null;
        try {
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = "ps.reso.instaeclipse";
            ai.sourceDir = path;
            ai.publicSourceDir = path;
            r = ctx.getPackageManager().getResourcesForApplication(ai);
            cached = r;
            return r;
        } catch (Throwable t) {
            try {
                android.content.res.AssetManager am = android.content.res.AssetManager.class.getDeclaredConstructor().newInstance();
                android.content.res.AssetManager.class.getMethod("addAssetPath", String.class).invoke(am, path);
                r = new Resources(am, ctx.getResources().getDisplayMetrics(), ctx.getResources().getConfiguration());
                cached = r;
                return r;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
