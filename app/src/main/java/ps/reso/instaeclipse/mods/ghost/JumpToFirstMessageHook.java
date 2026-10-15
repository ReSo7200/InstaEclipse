package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Jump to the first message of a DM thread (#244).
 *
 * A header button (next to IG's right-side action buttons) keeps scrolling the thread's message
 * list up. IG loads older pages itself when the list nears its oldest end, so we only stop once
 * the list can't scroll further up and has stopped growing for a while. UI-only: no IG internals,
 * just the RecyclerView (by class name) and View scrolling. Tap again to stop.
 */
public class JumpToFirstMessageHook {

    private static final String TAG = "ie_jump_first_btn";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int threadHeaderId, actionBarEndId;
    private static volatile Runnable running;

    public void install() {
        CurrentThreadTracker.addListener((activity, threadId) -> {
            if (FeatureFlags.jumpToFirstMessage) MAIN.postDelayed(() -> inject(activity), 600);
        });
        ModuleLog.line("(IE|JumpFirst) ✅ installed");
        FeatureStatusTracker.setHooked("JumpToFirstMessage");
    }

    @SuppressLint("DiscouragedApi")
    private static void inject(Activity a) {
        try {
            if (threadHeaderId == 0) {
                threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
                actionBarEndId = a.getResources().getIdentifier("action_bar_end_action_buttons", "id", a.getPackageName());
            }
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            View end = actionBarEndId != 0 ? a.findViewById(actionBarEndId) : null;
            if (header == null || !(end instanceof ViewGroup target)) return;
            if (target.findViewWithTag(TAG) != null) return;

            android.widget.ImageView btn = new android.widget.ImageView(a);
            btn.setTag(TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_jump_first, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            int pad = (int) (9 * a.getResources().getDisplayMetrics().density);
            btn.setPadding(pad, pad, pad, pad);
            btn.setContentDescription(I18n.t(a, R.string.ig_jump_first_message));
            int sz = (int) (40 * a.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> toggle(a));
            target.addView(btn, 0);
        } catch (Throwable t) {
            ModuleLog.line("(IE|JumpFirst) ⚠️ inject: " + t);
        }
    }

    private static void toggle(Activity a) {
        if (running != null) {
            MAIN.removeCallbacks(running);
            running = null;
            Toast.makeText(a, I18n.t(a, R.string.ig_jump_first_stopped), Toast.LENGTH_SHORT).show();
            return;
        }
        View list = findMessageList(a.getWindow().getDecorView());
        if (list == null) {
            ModuleLog.line("(IE|JumpFirst) ⚠️ message list not found");
            return;
        }
        Toast.makeText(a, I18n.t(a, R.string.ig_jump_first_loading), Toast.LENGTH_SHORT).show();
        final long started = System.currentTimeMillis();
        final long[] stuckSince = {0};
        running = new Runnable() {
            @Override
            public void run() {
                if (running != this) return;
                if (!list.isAttachedToWindow() || System.currentTimeMillis() - started > 5 * 60_000) { running = null; return; }
                if (list.canScrollVertically(-1)) {
                    stuckSince[0] = 0;
                    list.scrollBy(0, -list.getHeight() * 4);
                } else {
                    if (stuckSince[0] == 0) stuckSince[0] = System.currentTimeMillis();
                    // nudge so IG's "near the top" pagination fires, then give it time to load
                    list.scrollBy(0, -1);
                    if (System.currentTimeMillis() - stuckSince[0] > 4000) {
                        running = null;
                        Toast.makeText(a, I18n.t(a, R.string.ig_jump_first_done), Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                MAIN.postDelayed(this, 250);
            }
        };
        MAIN.post(running);
    }

    /** The tallest visible RecyclerView below the thread header = the message list. */
    private static View findMessageList(View root) {
        View best = null;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            View v = st.pop();
            if (v.getVisibility() == View.VISIBLE && isRecyclerView(v.getClass()) && v.isShown()
                    && (best == null || v.getHeight() > best.getHeight())) best = v;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return best;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }
}
