package ps.reso.instaeclipse.mods.feed;

import android.annotation.SuppressLint;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Rounded feed posts (#276): the corners of a post's photo or video are rounded to the chosen
 * radius.
 *
 * <p>Every post's media sits in a MediaFrameLayout (id media_group, a named class). Each one gets a
 * layout listener when it is created; on layout the post's outermost media view (the video_container
 * host around it when there is one, so overlays are clipped too) is clipped to a rounded outline.
 * No view tree is walked, so a feed full of playing videos costs nothing extra.
 */
public final class RoundedFeedPostsHook {

    private static final String MEDIA_FRAME = "com.instagram.ui.widget.framelayout.MediaFrameLayout";
    /** Tag on the clipped view: the radius applied, in pixels. */
    private static final int RADIUS_TAG = 0x7e1e0276;

    private static int videoContainerId = -1;

    private RoundedFeedPostsHook() {}

    public static void install(ClassLoader cl) {
        try {
            Class<?> frame = cl.loadClass(MEDIA_FRAME);
            View.OnLayoutChangeListener onLayout = (v, l, t, r, b, ol, ot, or, ob) -> apply(v);
            XposedBridge.hookAllConstructors(frame, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (param.thisObject instanceof View v) v.addOnLayoutChangeListener(onLayout);
                }
            });
            FeatureStatusTracker.setHooked("RoundedFeedPosts");
            ModuleLog.line("(IE|RoundedPosts) ✅ hooked " + MEDIA_FRAME);
        } catch (Throwable t) {
            ModuleLog.line("(IE|RoundedPosts) ❌ " + t);
        }
    }

    /** The view to clip: the outermost video_container around the media frame, else the frame. */
    @SuppressLint("DiscouragedApi")
    private static View target(View frame) {
        if (videoContainerId == -1) {
            videoContainerId = frame.getResources().getIdentifier("video_container", "id", frame.getContext().getPackageName());
        }
        View best = frame;
        android.view.ViewParent p = frame.getParent();
        for (int depth = 0; p instanceof View pv && depth < 4; depth++, p = pv.getParent()) {
            if (videoContainerId != 0 && pv.getId() == videoContainerId
                    && pv.getWidth() == frame.getWidth() && pv.getHeight() == frame.getHeight()) best = pv;
        }
        return best;
    }

    private static void apply(View frame) {
        try {
            View v = target(frame);
            int dp = FeatureFlags.feedCornerRadius;
            Object applied = v.getTag(RADIUS_TAG);
            if (dp <= 0) {
                if (applied == null) return;
                v.setTag(RADIUS_TAG, null);
                v.setClipToOutline(false);
                v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                return;
            }
            float radius = dp * v.getResources().getDisplayMetrics().density;
            if (applied instanceof Float f && f == radius && v.getClipToOutline()) return;
            v.setTag(RADIUS_TAG, radius);
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            v.setClipToOutline(true);
            v.invalidateOutline();
        } catch (Throwable ignored) {}
    }
}
