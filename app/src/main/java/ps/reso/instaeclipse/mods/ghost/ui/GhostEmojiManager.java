package ps.reso.instaeclipse.mods.ghost.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

public class GhostEmojiManager {

    @SuppressLint("StaticFieldLeak")
    private static ImageView sTintedView;

    @SuppressLint("DiscouragedApi")
    public static void addGhostEmojiNextToInbox(Activity activity, boolean showGhost) {
        try {
            int id1 = activity.getResources().getIdentifier("action_bar_inbox_button", "id", activity.getPackageName());
            int id2 = activity.getResources().getIdentifier("direct_tab", "id", activity.getPackageName());

            View v = activity.findViewById(id1);
            if (v == null) v = activity.findViewById(id2);
            if (v == null) return;

            ImageView iv = findImageView(v);
            if (iv == null) return;

            sTintedView = iv;

            if (showGhost) {
                iv.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_ATOP);
            } else {
                iv.clearColorFilter();
            }
        } catch (Exception ignored) {
        }
    }

    /** Recursively finds the first ImageView in the view tree. */
    private static ImageView findImageView(View view) {
        if (view instanceof ImageView iv) return iv;
        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                ImageView found = findImageView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
