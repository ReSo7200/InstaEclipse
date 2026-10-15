package ps.reso.instaeclipse.mods.misc;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Open links in the external browser instead of Instagram's in-app browser (#229).
 *
 * IG opens web links with an explicit Intent to its in-app browser activity
 * (com.instagram.inappbrowser.fragments.BrowserLiteInMainProcessIGActivity on 451, or
 * com.facebook.browser.lite.BrowserLiteActivity) carrying the URL as the Intent data. Every in-process
 * activity start goes through android.app.Instrumentation#execStartActivity, so we hook that
 * (framework API — no IG class names or DexKit needed) and, for an in-app-browser Intent with an
 * external http(s) URL, rewrite the Intent in place into a plain ACTION_VIEW for the system's default
 * browser. The l.instagram.com link shim is unwrapped to the real destination, and IG's private
 * extras are dropped (another app must never have to unparcel IG classes). Instagram/Facebook URLs
 * are left alone so IG keeps handling its own pages. Gated on FeatureFlags.openLinksExternally.
 */
public class ExternalBrowserHook {

    public void install() {
        try {
            int n = XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.openLinksExternally) return;
                    for (Object a : param.args) {
                        if (a instanceof Intent intent) {
                            redirect(intent);
                            return;
                        }
                    }
                }
            }).size();
            FeatureStatusTracker.setHooked("OpenLinksExternally");
            ModuleLog.line("(IE|ExtBrowser) ✅ hooked execStartActivity x" + n);
        } catch (Throwable t) {
            ModuleLog.line("(IE|ExtBrowser) ❌ install: " + t);
        }
    }

    private static void redirect(Intent intent) {
        try {
            ComponentName cn = intent.getComponent();
            if (cn == null || !isInAppBrowser(cn.getClassName())) return;
            Uri target = unwrapShim(intent.getData());
            if (target == null) return;
            String scheme = target.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return;
            if (isMetaOwned(target.getHost())) return;

            intent.setComponent(null);
            intent.setPackage(null);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(target);
            intent.replaceExtras((android.os.Bundle) null);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ModuleLog.probe("(IE|ExtBrowser) opening externally: " + target.getHost());
        } catch (Throwable t) {
            ModuleLog.line("(IE|ExtBrowser) ⚠️ redirect: " + t);
        }
    }

    private static boolean isInAppBrowser(String cls) {
        return cls.startsWith("com.instagram.inappbrowser.")
                || cls.startsWith("com.facebook.browser.lite.");
    }

    /** l.instagram.com/?u=<encoded real url>&e=… is IG's outbound link shim. */
    private static Uri unwrapShim(Uri uri) {
        if (uri == null) return null;
        try {
            String host = uri.getHost();
            if (host != null && (host.equalsIgnoreCase("l.instagram.com") || host.equalsIgnoreCase("l.facebook.com"))) {
                String u = uri.getQueryParameter("u");
                if (u != null && !u.isEmpty()) return Uri.parse(u);
            }
        } catch (Throwable ignored) {}
        return uri;
    }

    private static boolean isMetaOwned(String host) {
        if (host == null) return true;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("instagram.com") || h.endsWith(".instagram.com")
                || h.equals("facebook.com") || h.endsWith(".facebook.com")
                || h.equals("threads.net") || h.endsWith(".threads.net")
                || h.equals("threads.com") || h.endsWith(".threads.com");
    }
}
