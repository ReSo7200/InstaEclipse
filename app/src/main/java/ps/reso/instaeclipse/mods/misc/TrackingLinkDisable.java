package ps.reso.instaeclipse.mods.misc;
import android.content.ClipData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
public class TrackingLinkDisable {
    public TrackingLinkDisable(){

    }
    public void disableTrackingLinks(ClassLoader classLoader) throws Throwable{
        Class<?> clipboardManagerClass = XposedHelpers.findClass("android.content.ClipboardManager", classLoader);
        XposedHelpers.findAndHookMethod(clipboardManagerClass, "setPrimaryClip",
                Class.forName("android.content.ClipData"), new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if(FeatureFlags.disableTrackingLinks) {
                            ClipData clipData = (ClipData) param.args[0];
                            if (clipData == null || clipData.getItemCount() == 0 || clipData.getItemAt(0) == null || clipData.getItemAt(0).getText() == null) {
                                return;
                            }
                            String x = clipData.getItemAt(0).getText().toString();
                            if (x.contains("https://www.instagram.com/") && x.contains("?igsh=")) {
                                param.args[0] = ClipData.newPlainText("URL", x.replaceAll("\\?igsh=[a-zA-Z0-9=+/]*", ""));
                            }
                        }

                    }
                });



    }
}
