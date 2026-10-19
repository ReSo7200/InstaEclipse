package ps.reso.instaeclipse.mods.feed;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Adds a long-press-to-zoom overlay to feed photos (issue #174) so photos can be examined
 * full-screen without navigating away to the post's profile/permalink view — Instagram's
 * classic feed row (com.instagram.feed.widget.IgProgressImageView) has no zoom machinery of
 * its own, so this hook grabs the currently-displayed Drawable and shows it in a
 * self-contained pinch/pan zoom overlay (ZoomableImageView) rather than trying to graft in
 * Instagram's own zoom classes (used for profile-picture/Stories zoom), which are tightly
 * coupled to their own render/animation callbacks.
 */
public class FeedPhotoZoomHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // IG 448+ moved the view to common.ui.widget.imageview; the legacy feed.widget name is kept
    // as a fallback for older builds. Same API in both (onAttachedToWindow + getIgImageView).
    private static final String[] VIEW_CLASSES = {
            "com.instagram.common.ui.widget.imageview.IgProgressImageView",
            "com.instagram.feed.widget.IgProgressImageView",
    };

    // IgProgressImageView is a shared, general-purpose component — reused for feed rows,
    // DM media previews, and story frames alike — so it carries no identity of its own.
    // "row_feed_button_like" is the same feed-row-only anchor FeedVideoDownloadHook already
    // relies on to tell a feed post apart from a reel/DM context. Cached once resolved.
    private static volatile int sFeedLikeButtonId = 0;

    public void install(ClassLoader classLoader) {
        try {
            ViewBinding binding = ViewBinding.resolve(classLoader, VIEW_CLASSES);
            if (binding == null) {
                ModuleLog.line("(IE|PhotoZoom) compatible image wrapper not found");
                if (FeatureFlags.enablePhotoZoom) FeatureStatusTracker.setBroken("PhotoZoom");
                return;
            }

            // Hooked at onAttachedToWindow, not the constructor: the view has no parent yet
            // at construction time, so the feed-row ancestor check below would always fail.
            XposedBridge.hookMethod(binding.attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    // setOnLongClickListener() has a side effect beyond registering the
                    // callback: it also flips the view's LONG_CLICKABLE flag, which changes
                    // Android's internal tap-vs-long-press disambiguation timing for that
                    // view. Attaching it unconditionally to every IgProgressImageView
                    // instance app-wide was altering touch dispatch on every image view
                    // regardless of whether Photo Zoom was even enabled or whether the view
                    // was actually a feed post — causing intermittent dropped taps on
                    // story-advance and DM media opens. Gate on BOTH the feature flag and a
                    // feed-row identity check BEFORE attaching, not just inside the callback,
                    // so the flag is left untouched everywhere else.
                    if (!FeatureFlags.enablePhotoZoom) return;
                    try {
                        View view = (View) param.thisObject;
                        if (!isInsideFeedRow(view)) return;
                        view.setOnLongClickListener(v -> {
                            if (!FeatureFlags.enablePhotoZoom || !isInsideFeedRow(v)) return false;
                            try {
                                Object imgViewObj = binding.image.invoke(v);
                                if (!(imgViewObj instanceof ImageView)) return false;
                                Bitmap snapshot = viewToBitmap((ImageView) imgViewObj);
                                if (snapshot == null) return false;
                                showZoomOverlay(v.getContext(), snapshot);
                                return true;
                            } catch (Throwable t) {
                                ModuleLog.line("(IE|PhotoZoom) ❌ long-press: " + t);
                                return false;
                            }
                        });
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|PhotoZoom) ❌ attach hook: " + t);
                    }
                }
            });

            FeatureStatusTracker.setHooked("PhotoZoom");
            ModuleLog.line("(IE|PhotoZoom) ✅ hook installed on " + binding.attach.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.line("(IE|PhotoZoom) ❌ install: " + t);
            if (FeatureFlags.enablePhotoZoom) FeatureStatusTracker.setBroken("PhotoZoom");
        }
    }

    /** Resolve a typed child and an owned lifecycle before changing any host listener. */
    static final class ViewBinding {
        final Method attach;
        final Method image;

        private ViewBinding(Method attach, Method image) {
            this.attach = attach;
            this.image = image;
        }

        static ViewBinding resolve(ClassLoader loader, String... names) {
            for (String name : names) {
                try {
                    Class<?> type = loader.loadClass(name);
                    if (!ViewGroup.class.isAssignableFrom(type)) continue;
                    Method attach = type.getDeclaredMethod("onAttachedToWindow");
                    Method image = type.getMethod("getIgImageView");
                    if (attach.getReturnType() != void.class
                            || Modifier.isStatic(attach.getModifiers())
                            || Modifier.isAbstract(attach.getModifiers())
                            || Modifier.isStatic(image.getModifiers())
                            || !ImageView.class.isAssignableFrom(image.getReturnType())) continue;
                    attach.setAccessible(true);
                    image.setAccessible(true);
                    return new ViewBinding(attach, image);
                } catch (ReflectiveOperationException | LinkageError ignored) {
                }
            }
            return null;
        }
    }

    /**
     * True only when {@code view} sits inside a classic feed post row — verified by finding
     * "row_feed_button_like" somewhere in the same row's view tree. Walks a bounded number of
     * ancestors (the row root is typically a few levels up) and, at each level, searches that
     * ancestor's own subtree (siblings like the like/save buttons, not just further ancestors).
     */
    private static boolean isInsideFeedRow(View view) {
        sFeedLikeButtonId = ps.reso.instaeclipse.utils.ui.ResIds.id(view.getContext(), "row_feed_button_like");
        if (sFeedLikeButtonId == 0) return false; // can't verify — be conservative and skip

        ViewParent parent = view.getParent();
        for (int depth = 0; depth < 6 && parent instanceof ViewGroup group; depth++) {
            if (containsDescendantWithId(group, sFeedLikeButtonId, 4)) return true;
            parent = group.getParent();
        }
        return false;
    }

    private static boolean containsDescendantWithId(ViewGroup group, int targetId, int depth) {
        if (depth < 0) return false;
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getId() == targetId) return true;
            if (child instanceof ViewGroup childGroup && containsDescendantWithId(childGroup, targetId, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static void showZoomOverlay(Context ctx, Bitmap bitmap) {
        MAIN.post(() -> {
            try {
                Dialog dialog = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                ZoomableImageView zoomView = new ZoomableImageView(ctx);
                zoomView.setImageBitmap(bitmap);
                zoomView.setBackgroundColor(Color.BLACK);
                zoomView.setOnDismissRequest(dialog::dismiss);

                dialog.setContentView(zoomView);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                }
                dialog.show();
            } catch (Throwable t) {
                ModuleLog.line("(IE|PhotoZoom) ❌ overlay: " + t);
            }
        });
    }

    // Instagram's feed image view is backed by a Fresco-style drawable that manages its own
    // internal scaling and ignores ImageView's matrix/scaleType, so re-drawing the Drawable
    // directly onto a bare canvas didn't work. Screenshotting the actual View instead captures
    // exactly what's on screen, regardless of how the underlying drawable renders itself.
    private static Bitmap viewToBitmap(View v) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        v.draw(canvas);
        return bitmap;
    }
}
