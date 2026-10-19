package ps.reso.instaeclipse.xposedcompat;

import android.content.pm.ApplicationInfo;

/** Holder for {@link LoadPackageParam}, named like the legacy class so call sites stay the same. */
public final class XC_LoadPackage {

    private XC_LoadPackage() {}

    /** The app being loaded, filled by whichever entry the framework used. */
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
