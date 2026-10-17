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
            if (!FeatureFlags.jumpToFirstMessage) return;
            final int[] tries = {0};
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (inject(activity)) return;
                    if (++tries[0] < 15) MAIN.postDelayed(this, 300);
                    else ModuleLog.line("(IE|JumpFirst) ⚠️ gave up: header=" + (threadHeaderId != 0 && activity.findViewById(threadHeaderId) != null)
                            + " unsentBtn=" + (activity.getWindow().getDecorView().findViewWithTag("ie_unsent_btn") != null)
                            + " endBtns=" + (actionBarEndId != 0 && activity.findViewById(actionBarEndId) != null));
                }
            }, 300);
        });
        ModuleLog.line("(IE|JumpFirst) ✅ installed");
        FeatureStatusTracker.setHooked("JumpToFirstMessage");
    }

    @SuppressLint("DiscouragedApi")
    /** Returns true once the button is in the header (or can't be placed in this screen). */
    static boolean inject(Activity a) {
        try {
            if (threadHeaderId == 0) {
                threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
                actionBarEndId = a.getResources().getIdentifier("action_bar_end_action_buttons", "id", a.getPackageName());
            }
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (header == null) return false;
            if (ThreadHeaderMenu.has(a, "jump")) return true;
            ThreadHeaderMenu.add(a, "jump", I18n.t(a, R.string.ig_jump_first_message), () -> toggle(a));
            ModuleLog.line("(IE|JumpFirst) ✅ added to the thread menu");
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|JumpFirst) ⚠️ inject: " + t);
            return true;
        }
    }

    private static void toggle(Activity a) {
        run(a, null);
    }

    /** Scrolls to the first message; {@code onDone} runs once it's reached (or the list is
     *  missing / the 5 min cap hits), not when the user stops it. Tapping again stops. */
    static void run(Activity a, Runnable onDone) {
        if (running != null) {
            MAIN.removeCallbacks(running);
            running = null;
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_jump_first_stopped), Toast.LENGTH_SHORT).show();
            return;
        }
        View list = findMessageList(a.getWindow().getDecorView());
        if (list == null) {
            ModuleLog.line("(IE|JumpFirst) ⚠️ message list not found");
            if (onDone != null) onDone.run();
            return;
        }
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_jump_first_loading), Toast.LENGTH_SHORT).show();
        final long started = System.currentTimeMillis();
        final long[] lastChange = {System.currentTimeMillis()};
        final int[] lastCount = {itemCount(list)};
        final int[] lastOffset = {scrollOffset(list)};
        running = new Runnable() {
            @Override
            public void run() {
                if (running != this) return;
                if (!list.isAttachedToWindow() || System.currentTimeMillis() - started > 5 * 60_000) {
                    running = null;
                    if (onDone != null) onDone.run();
                    return;
                }
                list.scrollBy(0, list.canScrollVertically(-1) ? -list.getHeight() * 4 : -1);
                int count = itemCount(list), off = scrollOffset(list);
                if (count != lastCount[0] || off != lastOffset[0]) {
                    lastCount[0] = count;
                    lastOffset[0] = off;
                    lastChange[0] = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastChange[0] > 3000) {
                    // nothing moved and nothing new loaded for 3 s: this is the first message
                    running = null;
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_jump_first_done), Toast.LENGTH_SHORT).show();
                    if (onDone != null) onDone.run();
                    return;
                }
                MAIN.postDelayed(this, 250);
            }
        };
        MAIN.post(running);
    }

    private static int itemCount(View list) {
        try {
            Object ad = list.getClass().getMethod("getAdapter").invoke(list);
            if (ad == null) return -1;
            for (java.lang.reflect.Method m : ad.getClass().getMethods())
                if (m.getName().equals("getItemCount") && m.getParameterCount() == 0) return (int) m.invoke(ad);
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int scrollOffset(View list) {
        try {
            java.lang.reflect.Method m = View.class.getDeclaredMethod("computeVerticalScrollOffset");
            m.setAccessible(true);
            return (int) m.invoke(list);
        } catch (Throwable t) {
            return list.getScrollY();
        }
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
