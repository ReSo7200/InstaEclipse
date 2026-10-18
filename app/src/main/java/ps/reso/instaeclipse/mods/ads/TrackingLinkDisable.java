package ps.reso.instaeclipse.mods.ads;

import android.content.ClipData;
import android.content.Intent;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

// Strips Instagram tracking params (igshid/igsh/utm_*) from links the user copies or shares —
// only the tracking keys; other params such as img_index are kept (#252, TrackingParamStripper).
// Covers three framework paths (all obfuscation-proof, no IG class names):
//   1. android.content.ClipboardManager.setPrimaryClip - modern clipboard copies.
//   2. android.text.ClipboardManager.setText - IG's in-app "Copy link" action sheet (legacy API
//      that bypasses path 1 entirely).
//   3. android.content.Intent.putExtra(EXTRA_TEXT) - the external "Share to app" sheet, which
//      never touches the clipboard.
// Gated on FeatureFlags.disableTrackingLinks; also swaps the host for FeatureFlags.customShareDomain.
public class TrackingLinkDisable {

    public void disableTrackingLinks(ClassLoader classLoader) throws Throwable {
        FeatureStatusTracker.setHooked("DisableTrackingLinks");

        // 1. Modern clipboard (ClipData) 
        Class<?> clipboardManagerClass = XposedHelpers.findClass("android.content.ClipboardManager", classLoader);
        XposedHelpers.findAndHookMethod(clipboardManagerClass, "setPrimaryClip",
                Class.forName("android.content.ClipData"), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!active()) return;
                        ClipData clipData = (ClipData) param.args[0];
                        if (clipData == null || clipData.getItemCount() == 0) return;
                        ClipData.Item item = clipData.getItemAt(0);
                        if (item == null || item.getText() == null) return;
                        ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) setPrimaryClip intercepted");
                        String cleaned = rewrite(item.getText().toString());
                        if (cleaned != null) param.args[0] = ClipData.newPlainText("URL", cleaned);
                    }
                });

        // 2. Legacy clipboard (setText) -- IG's in-app "Copy link" button 
        try {
            Class<?> legacyClipboard = XposedHelpers.findClass("android.text.ClipboardManager", classLoader);
            XposedHelpers.findAndHookMethod(legacyClipboard, "setText", CharSequence.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!active()) return;
                    if (param.args[0] == null) return;
                    ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) setText intercepted");
                    String cleaned = rewrite(param.args[0].toString());
                    if (cleaned != null) param.args[0] = cleaned;
                }
            });
        } catch (Throwable ignored) {}

        // 3. External share sheet -- Intent.putExtra(EXTRA_TEXT, ...) 
        XC_MethodHook putExtraHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!active()) return;
                if (!(param.args[0] instanceof String) || param.args[1] == null) return;
                if (!Intent.EXTRA_TEXT.equals(param.args[0])) return;
                ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) EXTRA_TEXT intercepted");
                String cleaned = rewrite(param.args[1].toString());
                if (cleaned != null) param.args[1] = cleaned;
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, String.class, putExtraHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "putExtra", String.class, CharSequence.class, putExtraHook);
        } catch (Throwable ignored) {}

        // 4. System share sheet (android ChooserActivity) -- its built-in "Copy" button copies the
        // target Intent's EXTRA_TEXT in the SYSTEM process, so our in-app clipboard hooks never see
        // it. Sanitize the target Intent here, at the choke point where IG builds the chooser (runs
        // in IG's process), which fixes both the system "Copy" and every "Share to <app>" target.
        XC_MethodHook chooserHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!active()) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Intent target)) return;
                sanitizeIntentText(target);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "createChooser",
                    Intent.class, CharSequence.class, chooserHook);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Intent.class, "createChooser",
                    Intent.class, CharSequence.class, android.content.IntentSender.class, chooserHook);
        } catch (Throwable ignored) {}
    }

    /** Strips tracking params from a share Intent's text (EXTRA_TEXT) and any ClipData it carries,
     *  so the system chooser's "Copy" and every "Share to app" target get the clean link. */
    private static void sanitizeIntentText(Intent target) {
        try {
            CharSequence text = target.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                ps.reso.instaeclipse.utils.log.ModuleLog.probe("(IE|Track) chooser EXTRA_TEXT intercepted");
                String cleaned = rewrite(text.toString());
                if (cleaned != null) target.putExtra(Intent.EXTRA_TEXT, cleaned);
            }
            android.content.ClipData cd = target.getClipData();
            if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
                String cleaned = rewrite(cd.getItemAt(0).getText().toString());
                if (cleaned != null) target.setClipData(android.content.ClipData.newPlainText("URL", cleaned));
            }
        } catch (Throwable ignored) {}
    }

    /** Tracking-param stripping and/or the custom share domain are on. */
    static boolean active() {
        return FeatureFlags.disableTrackingLinks || !FeatureFlags.customShareDomain.isEmpty();
    }

    /** Applies both link rewrites; null when nothing changed. */
    static String rewrite(String text) {
        if (text == null) return null;
        String out = text;
        if (FeatureFlags.disableTrackingLinks) {
            String stripped = TrackingParamStripper.strip(out);
            if (stripped != null) out = stripped;
        }
        out = ShareDomain.apply(out, FeatureFlags.customShareDomain);
        return out.equals(text) ? null : out;
    }
}
