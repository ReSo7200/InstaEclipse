package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;

public class ChatBackButtonLongPressHook {

    public static void attach(Activity activity, View header) {
        if (!FeatureFlags.hideSpecificChats || !FeatureFlags.hideChatBackLongPress) return;

        String pkg = activity.getPackageName();
        int backBtnId1 = activity.getResources().getIdentifier("action_bar_button_back", "id", pkg);
        int backBtnId2 = activity.getResources().getIdentifier("header_left_button", "id", pkg);

        View backView = null;
        if (header != null) {
            if (backBtnId1 != 0) backView = header.findViewById(backBtnId1);
            if (backView == null && backBtnId2 != 0) backView = header.findViewById(backBtnId2);
        }
        if (backView == null) {
            if (backBtnId1 != 0) backView = activity.findViewById(backBtnId1);
            if (backView == null && backBtnId2 != 0) backView = activity.findViewById(backBtnId2);
        }

        if (backView != null) {
            final View finalBackView = backView;
            finalBackView.setClickable(true);
            finalBackView.setOnTouchListener(new View.OnTouchListener() {
                private float startX, startY;
                private boolean triggered;
                private final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
                private final Handler handler = new Handler(Looper.getMainLooper());
                private final Runnable longPressRunnable = new Runnable() {
                    @Override
                    public void run() {
                        triggered = true;
                        finalBackView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                        String account = ps.reso.instaeclipse.utils.ghost.CurrentConversation.accountId(activity);
                        String threadId = HideChatsHook.resolveThreadId(activity);
                        if (account == null || threadId == null) {
                            Toast.makeText(activity, ps.reso.instaeclipse.utils.i18n.I18n.t(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                            return;
                        }

                        boolean hidden = HiddenThreads.isHidden(account, threadId);
                        ps.reso.instaeclipse.utils.ghost.HiddenThreadStore.Result result = HiddenThreads.setHidden(account, threadId, "", !hidden);
                        if (result.success) {
                            Toast.makeText(activity, ps.reso.instaeclipse.utils.i18n.I18n.t(activity, !hidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
                        }
                        HideChatsHook.refreshActiveInbox(activity);
                    }
                };

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = event.getX();
                            startY = event.getY();
                            triggered = false;
                            long delay = Math.max(1, FeatureFlags.hideChatsBackLongPressSeconds) * 1000L;
                            handler.postDelayed(longPressRunnable, delay);
                            break;
                        case MotionEvent.ACTION_MOVE:
                            if (!triggered) {
                                float dx = Math.abs(event.getX() - startX);
                                float dy = Math.abs(event.getY() - startY);
                                if (dx > touchSlop || dy > touchSlop) {
                                    handler.removeCallbacks(longPressRunnable);
                                }
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            handler.removeCallbacks(longPressRunnable);
                            if (triggered) {
                                v.setPressed(false);
                                return true;
                            }
                            break;
                    }
                    return false;
                }
            });
        }
    }
}
