package ps.reso.instaeclipse.utils.profile;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Small painted icons for the top bar of a copied profile, drawn so Instagram's theme can't recolor them. */
public final class ProfileIcons {

    private ProfileIcons() {}

    public static final class PlusBadge extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        public PlusBadge() {
            fill.setColor(0xFFFFFFFF);
            stroke.setColor(0xFF0C1014);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill);
            stroke.setStrokeWidth(r * 0.2f);
            float s = r * 0.5f;
            c.drawLine(b.exactCenterX() - s, b.exactCenterY(), b.exactCenterX() + s, b.exactCenterY(), stroke);
            c.drawLine(b.exactCenterX(), b.exactCenterY() - s, b.exactCenterX(), b.exactCenterY() + s, stroke);
        }
        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { fill.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
