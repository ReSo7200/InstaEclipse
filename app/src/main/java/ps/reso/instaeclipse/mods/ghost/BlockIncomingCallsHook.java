package ps.reso.instaeclipse.mods.ghost;

import android.app.Notification;

import java.util.Set;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Block incoming Instagram calls (voice/video DM calls), hidden but still in history.
 *
 * When ON, the incoming-call ring + full-screen pop-up never appear: we drop the one notification
 * Instagram posts to ring and auto-launch the call screen. The call is not declined and nothing is
 * sent to the caller — it simply rings out on their side and lands in your chat as a missed call,
 * exactly as if you had not picked up.
 *
 * How we pick only the incoming ring, and never an active call:
 *   Instagram builds two kinds of CATEGORY_CALL notification. The incoming ring is the only one
 *   with a full-screen intent (it is what wakes the screen and shows the accept/decline UI). The
 *   ongoing-call notification (and any foreground-service notification) has no full-screen intent.
 *   So we drop only CATEGORY_CALL notifications that carry a full-screen intent and are not a
 *   foreground-service notification — leaving calls you start, and calls already in progress,
 *   completely untouched.
 *
 * Framework-level hook on NotificationManager.notify(...), so it does not depend on any obfuscated
 * Instagram class and keeps working across Instagram versions. Default OFF; guarded throughout.
 */
public final class BlockIncomingCallsHook {

    private static final String TAG = "(IE|BlockCalls) ";
    private static boolean loggedDrop;

    public void install(ClassLoader cl) {
        try {
            Class<?> nm = XposedHelpers.findClass("android.app.NotificationManager", cl);
            XposedBridge.hookAllMethods(nm, "notify", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.blockIncomingCalls) return;
                    try {
                        Notification n = lastNotification(param.args);
                        if (n == null || !isIncomingCallRing(n)) return;
                        if (!isBlocked(n)) return;
                        param.setResult(null); // never posted: no ring, no heads-up, no full-screen launch
                        if (!loggedDrop) {
                            loggedDrop = true;
                            ModuleLog.line(TAG + "✅ dropped an incoming-call ring notification");
                        }
                    } catch (Throwable ignored) {
                        // on any doubt, let Instagram post normally — never break calls
                    }
                }
            });
            FeatureStatusTracker.setHooked("BlockIncomingCalls");
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /** notify(int, Notification) / notify(String, int, Notification): the Notification is the last arg. */
    private static Notification lastNotification(Object[] args) {
        if (args == null) return null;
        for (int i = args.length - 1; i >= 0; i--)
            if (args[i] instanceof Notification) return (Notification) args[i];
        return null;
    }

    /** The incoming ring: a call notification with a full-screen intent, not a foreground-service one. */
    private static boolean isIncomingCallRing(Notification n) {
        if (!Notification.CATEGORY_CALL.equals(n.category)) return false;
        if (n.fullScreenIntent == null) return false;              // ongoing/active calls have none
        return (n.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0; // never a foreground-service notif
    }

    /**
     * Global switch blocks every incoming call. The per-chat block list (by thread) is matched here
     * too once populated; until then only the global switch decides.
     */
    private static boolean isBlocked(Notification n) {
        if (FeatureFlags.blockIncomingCalls) return true;
        Set<String> list = FeatureFlags.blockCallsThreads;
        return list != null && !list.isEmpty() && threadBlocked(n, list);
    }

    /** Placeholder for per-chat matching (thread id captured from the rsys RingNotification). */
    private static boolean threadBlocked(Notification n, Set<String> list) {
        return false;
    }
}
