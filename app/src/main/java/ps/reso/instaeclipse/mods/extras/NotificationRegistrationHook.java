package ps.reso.instaeclipse.mods.extras;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Instagram's legacy push registration builds a broadcast PendingIntent for
 * "com.google.example.invalidpackage" without FLAG_IMMUTABLE/FLAG_MUTABLE, which Android 12+
 * rejects with an exception. The missing flag is added to that one intent only.
 */
public final class NotificationRegistrationHook {

    public void install() {
        if (Build.VERSION.SDK_INT < 31) return;
        XposedHelpers.findAndHookMethod(PendingIntent.class, "getBroadcast", Context.class, int.class,
                Intent.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                        if (!FeatureFlags.fixNotificationRegistration || !(p.args[2] instanceof Intent)
                                || !"com.google.example.invalidpackage".equals(((Intent) p.args[2]).getPackage())) return;
                        int flags = (int) p.args[3];
                        if ((flags & (PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE)) == 0)
                            p.args[3] = flags | PendingIntent.FLAG_IMMUTABLE;
                    }
                });
        FeatureStatusTracker.setHooked("fixNotificationRegistration");
        ModuleLog.line("(InstaEclipse | NotificationFix): ✅ hooked");
    }
}
