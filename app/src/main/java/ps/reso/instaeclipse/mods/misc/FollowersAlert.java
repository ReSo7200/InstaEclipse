package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.ProfileSpoofHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.users.FollowersHistory;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;

/** Tells you when your follower count is below your last saved list, or the last check is old. */
public final class FollowersAlert {

    private static final long CHECK_EVERY = 5 * 60_000L;
    private static final long ALERT_EVERY = 12 * 3_600_000L;
    private static final long STALE = 7 * 24 * 3_600_000L;
    private static volatile long lastRun;

    private FollowersAlert() {}

    public static void observe(Object user, Object count) {
        if (!FeatureFlags.followersHistory || !(count instanceof Number n)) return;
        long now = System.currentTimeMillis();
        if (now - lastRun < CHECK_EVERY || !ProfileSpoofHook.isOwn(user)) return;
        lastRun = now;
        final int real = n.intValue();
        new Thread(() -> run(real, now), "IE-FollowersAlert").start();
    }

    private static void run(int real, long now) {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            String self = ProfileSpoofHook.selfId();
            if (ctx == null || self == null) return;
            SharedPreferences prefs = ctx.getSharedPreferences("ie_followers_alert", Context.MODE_PRIVATE);
            if (now - prefs.getLong("last_" + self, 0) < ALERT_EVERY) return;
            FollowersHistory.State state = FollowersHistory.load(ctx, self);
            if (state.lastCheck == 0) return;
            DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
            String text = null;
            int fewer = state.users.size() - real;
            if (fewer > 0) {
                text = I18n.t(ctx, R.string.ig_followers_alert_fewer, fewer, df.format(new Date(state.lastCheck)));
            } else if (now - state.lastCheck >= STALE) {
                text = I18n.t(ctx, R.string.ig_followers_alert_stale, (now - state.lastCheck) / 86_400_000L);
            }
            if (text == null) return;
            prefs.edit().putLong("last_" + self, now).apply();
            final String shown = text;
            new Handler(Looper.getMainLooper()).post(() -> IeToast.makeText(ctx, shown, Toast.LENGTH_LONG).show());
        } catch (Throwable ignored) {
        }
    }
}
