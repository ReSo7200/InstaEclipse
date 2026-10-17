package ps.reso.instaeclipse.mods.feed;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Self-contained pinch-to-zoom + pan + double-tap-to-reset image view, used to show a
 * feed photo full-screen without navigating away (issue #174). Standard
 * ScaleGestureDetector/Matrix pattern; no Instagram internals involved.
 */
@android.annotation.SuppressLint("AppCompatCustomView") // runs inside Instagram
class ZoomableImageView extends ImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix matrix = new Matrix();
    private float scale = 1f;
    private float lastTouchX, lastTouchY;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Runnable onDismissRequest;

    ZoomableImageView(Context ctx) {
        super(ctx);
        setScaleType(ScaleType.MATRIX);
        setClickable(true);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = clamp(scale * detector.getScaleFactor());
                float factor = newScale / scale;
                scale = newScale;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
                setImageMatrix(matrix);
                return true;
            }
        });

        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > MIN_SCALE + 0.01f) {
                    resetZoom();
                } else {
                    float factor = DOUBLE_TAP_SCALE / scale;
                    scale = DOUBLE_TAP_SCALE;
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    fixTranslation();
                    setImageMatrix(matrix);
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onDismissRequest != null) onDismissRequest.run();
                return true;
            }
        });
    }

    void setOnDismissRequest(Runnable r) {
        this.onDismissRequest = r;
    }

    private static float clamp(float s) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, s));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) resetZoom();
    }

    private void resetZoom() {
        Drawable d = getDrawable();
        int vw = getWidth(), vh = getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        float fitScale = Math.min((float) vw / dw, (float) vh / dh);
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate((vw - dw * fitScale) / 2f, (vh - dh * fitScale) / 2f);
        scale = 1f;
        setImageMatrix(matrix);
    }

    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);

        int vw = getWidth(), vh = getHeight();
        float deltaX = 0, deltaY = 0;

        if (rect.width() <= vw) {
            deltaX = (vw - rect.width()) / 2f - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < vw) {
            deltaX = vw - rect.right;
        }

        if (rect.height() <= vh) {
            deltaY = (vh - rect.height()) / 2f - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < vh) {
            deltaY = vh - rect.bottom;
        }

        matrix.postTranslate(deltaX, deltaY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (scale > MIN_SCALE + 0.01f && event.getPointerCount() == 1) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    matrix.postTranslate(dx, dy);
                    fixTranslation();
                    setImageMatrix(matrix);
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                }
                break;
        }
        return true;
    }
}
