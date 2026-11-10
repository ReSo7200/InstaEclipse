package ps.reso.instaeclipse.mods.extras;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Fill the black bars around Reels with a blurred, darkened copy of the reel's cover (#273).
 *
 * <p>The Reels video lives in IG's clips_viewer_video_layout container (a FrameLayout; class
 * "LithoClipsViewerVideoLayout", obfuscated). It holds the cover image (IgImageView, exposed as
 * {@code getImagePlaceHolder()}) and the video (SimpleVideoLayout); IG sizes both to the video's
 * aspect ratio and leaves the rest of the container empty, which is the black bar. A small view is
 * added to the container, drawn behind its children (negative Z, so IG's own children keep their
 * indexes: IG removes the video by index when it swaps it), that paints the cover bitmap with a
 * RenderEffect blur and a dark scrim.
 *
 * <p>Cost: the view is laid out at 1/{@value #SCALE} size and scaled up, so its blur layer is
 * tiny. It redraws only when the cover bitmap changes (checked by reference on pre-draw).
 * RenderEffect needs Android 12; older Android is skipped.
 *
 * <p>Anchors: the container's constructor uses the purge marker
 * "android_purge_26_q2_LithoClipsViewerVideoLayout_init"; fallback: the class declaring
 * getImagePlaceHolder() + getVideoPlaceHolder(). Applies to reels opened after the switch is on.
 */
public class ReelsBlurBarsHook {

    private static final String TAG = "(InstaEclipse | ReelsBlur): ";
    private static final String CACHE_KEY = "Extras_ReelsVideoLayoutClass";
    private static final int SCALE = 8;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ModuleLog.line(TAG + "needs Android 12+, skipped");
            return;
        }
        Class<?> layout = findLayoutClass(bridge, cl);
        if (layout == null) return;
        final Method placeholder;
        try {
            placeholder = layout.getMethod("getImagePlaceHolder");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ getImagePlaceHolder missing on " + layout.getName());
            return;
        }
        XposedBridge.hookAllConstructors(layout, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.reelsBlurBars || !(param.thisObject instanceof FrameLayout host)) return;
                try {
                    if (!(placeholder.invoke(host) instanceof ImageView cover)) return;
                    host.addView(new BackdropView(host.getContext(), cover));
                    FeatureStatusTracker.setHooked("reelsBlurBars");
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "attach failed: " + t);
                }
            }
        });
        ModuleLog.line(TAG + "✅ video layout " + layout.getName());
    }

    private static Class<?> findLayoutClass(DexKitBridge bridge, ClassLoader cl) {
        String name = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY) : null;
        if ("missing".equals(name)) return null;
        if (name == null) {
            try {
                for (MethodData md : ExtrasLookup.byStrings(bridge, "android_purge_26_q2_LithoClipsViewerVideoLayout_init")) {
                    if (md.isConstructor()) {
                        name = md.getClassName();
                        break;
                    }
                }
                if (name == null) {
                    List<ClassData> found = DexKitQueryCache.findClass(bridge, FindClass.create().matcher(ClassMatcher.create()
                            .methods(MethodsMatcher.create()
                                    .add(MethodMatcher.create().name("getImagePlaceHolder"))
                                    .add(MethodMatcher.create().name("getVideoPlaceHolder")))));
                    if (found.size() == 1) name = found.get(0).getName();
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "lookup failed: " + t);
            }
            DexKitCache.saveString(CACHE_KEY, name == null ? "missing" : name);
        }
        if (name == null) {
            ModuleLog.line(TAG + "❌ video layout not found in this Instagram version");
            return null;
        }
        try {
            return cl.loadClass(name);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + name + ": " + t);
            return null;
        }
    }

    /**
     * Draws the cover bitmap center-cropped, darkened and blurred. Measured at 1/SCALE of the host
     * and scaled up from its top-left corner, so it covers the whole host with a small layer.
     */
    static final class BackdropView extends View implements ViewTreeObserver.OnPreDrawListener {

        private final ImageView cover;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private Bitmap shown;
        private Method igGetBitmap;
        private boolean igGetBitmapResolved;

        BackdropView(Context context, ImageView cover) {
            super(context);
            this.cover = cover;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            setLayoutParams(lp);
            setTranslationZ(-1f); // drawn before IG's children without changing their indexes
            setOutlineProvider(null);
            setPivotX(0f);
            setPivotY(0f);
            setScaleX(SCALE);
            setScaleY(SCALE);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension((w + SCALE - 1) / SCALE, (h + SCALE - 1) / SCALE);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                getViewTreeObserver().addOnPreDrawListener(this);
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onDetachedFromWindow() {
            try {
                getViewTreeObserver().removeOnPreDrawListener(this);
            } catch (Throwable ignored) {}
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onPreDraw() {
            try {
                // Keep the last cover if IG drops it while the video plays.
                Bitmap b = FeatureFlags.reelsBlurBars ? coverBitmap() : null;
                if (FeatureFlags.reelsBlurBars ? (b != null && b != shown) : shown != null) {
                    shown = b;
                    invalidate();
                }
            } catch (Throwable ignored) {}
            return true;
        }

        private Bitmap coverBitmap() {
            Drawable d = cover.getDrawable();
            if (d instanceof BitmapDrawable bd) {
                Bitmap b = bd.getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            if (!igGetBitmapResolved) { // IgImageView.getBitmap(), for non-bitmap drawables
                igGetBitmapResolved = true;
                try {
                    Method m = cover.getClass().getMethod("getBitmap");
                    if (m.getReturnType() == Bitmap.class) igGetBitmap = m;
                } catch (Throwable ignored) {}
            }
            if (igGetBitmap == null) return null;
            try {
                Object o = igGetBitmap.invoke(cover);
                if (o instanceof Bitmap b && !b.isRecycled()) return b;
            } catch (Throwable ignored) {}
            return null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap b = shown;
            int w = getWidth(), h = getHeight();
            if (b == null || b.isRecycled() || !FeatureFlags.reelsBlurBars || w <= 0 || h <= 0) return;
            try {
                int bw = b.getWidth(), bh = b.getHeight();
                if (bw <= 0 || bh <= 0) return;
                // Center-crop the bitmap into the view.
                if ((long) bw * h > (long) bh * w) {
                    int cw = (int) ((long) bh * w / h);
                    src.set((bw - cw) / 2, 0, (bw + cw) / 2, bh);
                } else {
                    int ch = (int) ((long) bw * h / w);
                    src.set(0, (bh - ch) / 2, bw, (bh + ch) / 2);
                }
                dst.set(0, 0, w, h);
                canvas.drawBitmap(b, src, dst, bitmapPaint);
                canvas.drawColor(0x8C000000); // darken so the bars stay calm behind the video
            } catch (Throwable ignored) {}
        }
    }
}
