package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.app.Notification;

import java.lang.reflect.Field;
import java.util.Set;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Block incoming Instagram calls (voice/video DM calls), hidden but still a missed call in history.
 *
 * When blocked, the incoming-call screen and ring never appear. The call is not declined and
 * nothing is sent to the caller — it rings out on their side and lands in your chat as a missed
 * call, exactly as if you had not picked up. The missed-call record is written by Instagram's
 * signaling layer, which we never touch, so history is intact.
 *
 * Scope:
 *  - "All" (global switch {@link FeatureFlags#blockIncomingCalls}): every incoming call.
 *  - "Selected" ({@link FeatureFlags#blockCallsThreads}, a set of DM thread ids set from each
 *    chat's menu): only calls from those chats.
 *
 * How it works (three guarded hooks; any that can't attach is skipped, the rest still work):
 *  1. Instagram's rsys engine materialises every incoming ring as a {@code RingNotification}
 *     (unobfuscated, carries {@code igThreadId}) via {@code RingNotification.createFromMcfType}.
 *     We read that thread id there — it is our "an incoming call just arrived" signal and the
 *     caller's chat for per-chat matching.
 *  2. The incoming-call screen is {@code RtcCallActivity}. In its onCreate, if a ring arrived in
 *     the last few seconds (so this is an incoming call, not one you started) and it is blocked,
 *     we finish() the activity before it is shown — no screen, no in-app ringtone.
 *  3. When Instagram is in the background the ring is a full-screen CATEGORY_CALL notification; we
 *     drop that one from NotificationManager.notify. The ongoing/active-call notification has no
 *     full-screen intent, so active and outgoing calls are never affected.
 *
 * Default OFF; guarded throughout — on any doubt Instagram behaves normally.
 */
public final class BlockIncomingCallsHook {

    private static final String TAG = "(IE|BlockCalls) ";
    private static final long RING_WINDOW_MS = 12_000L;

    private static volatile String pendingThread;
    private static volatile long pendingAt;
    private static boolean loggedActivity, loggedNotif;

    public void install(ClassLoader cl) {
        boolean any = false;
        // 1) capture the incoming ring's thread id
        try {
            Class<?> ring = XposedHelpers.findClass("com.instagram.rtc.rsys.models.RingNotification", cl);
            final Field threadField = fieldOrNull(ring, "igThreadId");
            XposedBridge.hookAllMethods(ring, "createFromMcfType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!enabled()) return;
                    try {
                        Object rn = param.getResult();
                        String thread = null;
                        if (rn != null && threadField != null) {
                            Object v = threadField.get(rn);
                            if (v != null) thread = v.toString();
                        }
                        pendingThread = thread;
                        pendingAt = System.currentTimeMillis();
                    } catch (Throwable ignored) {}
                }
            });
            any = true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ ring signal unavailable: " + t);
        }

        // 2) the incoming-call screen
        try {
            Class<?> act = XposedHelpers.findClass("com.instagram.rtc.activity.RtcCallActivity", cl);
            XposedBridge.hookAllMethods(act, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!enabled()) return;
                    try {
                        if (!recentIncoming() || !isBlocked(pendingThread)) return;
                        if (param.thisObject instanceof Activity a) {
                            a.finish();
                            clearPending();
                            if (!loggedActivity) {
                                loggedActivity = true;
                                ModuleLog.line(TAG + "✅ blocked an incoming call screen");
                            }
                        }
                    } catch (Throwable ignored) {
                        // never break the call UI
                    }
                }
            });
            any = true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ call screen unavailable: " + t);
        }

        // 3) the background full-screen ring notification
        try {
            Class<?> nm = XposedHelpers.findClass("android.app.NotificationManager", cl);
            XposedBridge.hookAllMethods(nm, "notify", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!enabled()) return;
                    try {
                        Notification n = lastNotification(param.args);
                        if (n == null || !isIncomingCallRing(n)) return;
                        // Global blocks all; per-chat blocks only if the just-rung thread matches.
                        boolean block = FeatureFlags.blockIncomingCalls
                                || (recentIncoming() && isBlocked(pendingThread));
                        if (!block) return;
                        param.setResult(null);
                        if (!loggedNotif) {
                            loggedNotif = true;
                            ModuleLog.line(TAG + "✅ dropped an incoming-call ring notification");
                        }
                    } catch (Throwable ignored) {}
                }
            });
            any = true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ notification hook unavailable: " + t);
        }

        if (any) {
            FeatureStatusTracker.setHooked("BlockIncomingCalls");
            ModuleLog.line(TAG + "✅ installed");
        } else {
            ModuleLog.line(TAG + "❌ no hook point attached");
        }
    }

    private static boolean enabled() {
        Set<String> list = FeatureFlags.blockCallsThreads;
        return FeatureFlags.blockIncomingCalls || (list != null && !list.isEmpty());
    }

    /** Global switch blocks everything; otherwise only chats on the per-chat list. */
    private static boolean isBlocked(String thread) {
        if (FeatureFlags.blockIncomingCalls) return true;
        Set<String> list = FeatureFlags.blockCallsThreads;
        return thread != null && list != null && list.contains(thread);
    }

    private static boolean recentIncoming() {
        return pendingAt > 0 && System.currentTimeMillis() - pendingAt < RING_WINDOW_MS;
    }

    private static void clearPending() {
        pendingAt = 0;
        pendingThread = null;
    }

    private static Field fieldOrNull(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
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
        if (n.fullScreenIntent == null) return false;
        return (n.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0;
    }
}
