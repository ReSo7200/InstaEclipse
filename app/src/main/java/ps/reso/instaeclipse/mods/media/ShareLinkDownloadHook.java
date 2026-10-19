package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Download a post/reel from a link shared to InstaEclipse in another app (#236).
 *
 * The companion app's share target ({@code ShareDownloadActivity}) opens the link in Instagram
 * with {@link #EXTRA}. Here, once Instagram has loaded that post into its session MediaCache, its
 * media is saved with the module's downloader and Instagram is sent to the background, so the
 * user lands back in the app they shared from. (A silent fetch without the UI would need the
 * module to make authenticated API calls of its own.)
 */
public class ShareLinkDownloadHook {
    public static final String EXTRA = "ps.reso.instaeclipse.SHARE_DOWNLOAD";
    private static final String TAG = "(IE|ShareDL) ";
    private static final Pattern SHORTCODE = Pattern.compile("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final long TIMEOUT_MS = 25_000, STEP_MS = 500;

    /** every UserSession built in this process (several with multiple accounts) */
    private static final java.util.Set<Object> sessions =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    private static String pendingPk;

    public void install(ClassLoader cl) {
        // The session: UserSession is built once per login/restore (cold, unlike MediaCache lookups).
        try {
            XposedBridge.hookAllConstructors(XposedHelpers.findClass("com.instagram.common.session.UserSession", cl), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    sessions.add(param.thisObject);
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ session hook: " + t);
        }
        XC_MethodHook onIntent = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                Activity a = (Activity) param.thisObject;
                Intent i = param.args.length > 0 && param.args[0] instanceof Intent in ? in : a.getIntent();
                if (i == null || !i.getBooleanExtra(EXTRA, false)) return;
                i.removeExtra(EXTRA); // once (not again on recreation)
                start(a, i.getData());
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, onIntent);
            XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, onIntent);
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ activity hooks: " + t);
        }
    }

    private static void start(Activity a, Uri uri) {
        String pk = uri != null ? pkFromLink(uri.toString()) : null;
        ModuleLog.line(TAG + "link=" + uri + " pk=" + pk);
        if (pk == null) {
            toast(a, I18n.t(a, R.string.ig_share_dl_not_found));
            return;
        }
        pendingPk = pk;
        toast(a, I18n.t(a, R.string.ig_share_dl_loading));
        final long end = System.currentTimeMillis() + TIMEOUT_MS;
        final Context app = a.getApplicationContext();
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (!pk.equals(pendingPk)) return; // a newer link took over
                Object media = findMedia(pk);
                if (media != null) {
                    pendingPk = null;
                    save(app, media, pk);
                    // back to the app the link came from: a bit later and re-checked, since the link
                    // handler may still be opening the post/reel viewer, bringing Instagram back up
                    for (long delay : new long[]{1500, 3500, 6000})
                        FeedVideoDownloadHook.mainHandler.postDelayed(() -> {
                            Activity front = resumedActivity();
                            if (front != null) front.moveTaskToBack(true);
                        }, delay);
                } else if (System.currentTimeMillis() < end) {
                    FeedVideoDownloadHook.mainHandler.postDelayed(this, STEP_MS);
                } else {
                    pendingPk = null;
                    ModuleLog.line(TAG + "timed out pk=" + pk);
                    toast(app, I18n.t(app, R.string.ig_share_dl_not_found));
                }
            }
        };
        FeedVideoDownloadHook.mainHandler.postDelayed(poll, STEP_MS);
    }

    /** This process's resumed activity, from ActivityThread's records (the link handler hands off
     *  to viewer activities the module doesn't otherwise track). */
    private static Activity resumedActivity() {
        try {
            Object at = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread");
            Map<?, ?> records = (Map<?, ?>) XposedHelpers.getObjectField(at, "mActivities");
            for (Object r : records.values()) {
                if (XposedHelpers.getBooleanField(r, "paused")) continue;
                Object act = XposedHelpers.getObjectField(r, "activity");
                if (act instanceof Activity a && !a.isFinishing()) return a;
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "resumed activity: " + t);
        }
        return null;
    }

    /** Media pk from a post/reel link: the shortcode is the pk in base64url (first 11 chars). */
    static String pkFromLink(String link) {
        Matcher m = SHORTCODE.matcher(link);
        if (!m.find()) return null;
        String code = m.group(1);
        if (code.length() > 11) code = code.substring(0, 11); // longer codes = private-post suffix
        BigInteger pk = BigInteger.ZERO;
        for (char c : code.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) return null;
            pk = pk.shiftLeft(6).add(BigInteger.valueOf(v));
        }
        return pk.toString();
    }

    /** The post's Media from a session's MediaCache (its map, so the key format doesn't matter). */
    private static Object findMedia(String pk) {
        Object[] all;
        synchronized (sessions) { all = sessions.toArray(); }
        for (Object s : all) {
            try {
                Class<?> cache = s.getClass().getClassLoader().loadClass("com.instagram.feed.media.MediaCache");
                Object mc = cache.getMethod("getInstance", s.getClass()).invoke(null, s);
                for (Field f : cache.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Map<?, ?> map = (Map<?, ?>) f.get(mc);
                    if (map == null) continue;
                    for (Map.Entry<?, ?> e : map.entrySet()) { // keys are "<pk>_<owner id>"
                        String k = String.valueOf(e.getKey());
                        if (k.equals(pk) || k.startsWith(pk + "_")) return e.getValue();
                    }
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "cache: " + t);
            }
        }
        return null;
    }

    private static void save(Context ctx, Object media, String pk) {
        FeedVideoDownloadHook.executor.submit(() -> {
            try {
                List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, media);
                ModuleLog.line(TAG + "pk=" + pk + " urls=" + (urls == null ? 0 : urls.size()));
                if (urls == null || urls.isEmpty()) {
                    toast(ctx, I18n.t(ctx, R.string.ig_share_dl_not_found));
                    return;
                }
                String user = FeedVideoDownloadHook.extractUsernameFromMediaObject(media);
                if (user == null) user = "instagram";
                toast(ctx, I18n.t(ctx, R.string.ig_share_dl_saving, urls.size()));
                for (int i = 0; i < urls.size(); i++) {
                    String url = urls.get(i);
                    boolean vid = FeedVideoDownloadHook.isVideoUrl(url);
                    String id = urls.size() > 1 ? pk + "_" + (i + 1) : pk;
                    FeedVideoDownloadHook.downloadAndSave(ctx, url,
                            FeedVideoDownloadHook.buildFilename(user, vid ? "video" : "photo", id, vid), vid, user);
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ save: " + t);
            }
        });
    }

    private static void toast(Context c, String msg) {
        FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, msg, Toast.LENGTH_SHORT).show());
    }
}
