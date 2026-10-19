package ps.reso.instaeclipse;

import android.app.Application;

import ps.reso.instaeclipse.utils.core.RemotePrefsMirror;

/** Companion app process start: keeps the settings Instagram reads on a cold start in sync. */
public class InstaEclipseApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // modern flavor: mirrors "instaeclipse_cache" into Vector's remote preferences (libxposed
        // API 102, read by the hooked app); legacy flavor: no-op (Instagram uses XSharedPreferences)
        RemotePrefsMirror.start(this);
    }
}
