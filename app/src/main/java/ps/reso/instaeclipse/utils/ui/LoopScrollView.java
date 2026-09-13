package ps.reso.instaeclipse.utils.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/**
 * A HorizontalScrollView with momentum fling disabled. Used by the Home contributors / special-thanks
 * carousels: they auto-scroll on a continuous loop, and a fling would race the loop driver and make
 * the strip snap back or jump to a random offset on release. Dragging still works — only the
 * post-release momentum is suppressed, so the auto-scroll can resume smoothly from the exact spot.
 */
public class LoopScrollView extends HorizontalScrollView {
    public LoopScrollView(Context context) { super(context); }
    public LoopScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    public LoopScrollView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    public void fling(int velocityX) {
        // no-op: the loop driver owns the motion; momentum would fight it.
    }
}
