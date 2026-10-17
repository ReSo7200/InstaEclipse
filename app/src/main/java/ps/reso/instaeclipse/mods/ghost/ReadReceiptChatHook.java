package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.ReadReceiptWhitelist;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Per-chat read receipts (#227), chosen from inside the chat: with "Send read receipts to specific
 * chats" on, the thread header menu gets "Send read receipts in this chat" / "Stop read receipts in
 * this chat". Same triggers and thread-id lookup as HideChatsHook (thread activities' onResume plus
 * the thread-fragment tracker), so it works whether the chat is a ModalActivity or a fragment.
 * GhostDMSeenHook and the network interceptor let "Seen" through for listed threads.
 */
public class ReadReceiptChatHook {

    private static int threadHeaderId, headerTitleId;
    private static final Set<View> pendingDecors = Collections.newSetFromMap(new WeakHashMap<>());

    public void install(ClassLoader classLoader) {
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                LifecycleHookUtil.hookAfter(classLoader, act, "onResume", (a, param) -> {
                    if (FeatureFlags.readReceiptChats) a.runOnUiThread(() -> register(a));
                });
            } catch (Throwable t) {
                ModuleLog.line("(IE|ReadReceiptChat) ⚠️ hook " + act + ": " + t);
            }
        }
        CurrentThreadTracker.install(classLoader);
        CurrentThreadTracker.addListener((a, threadId) -> {
            if (FeatureFlags.readReceiptChats) register(a);
        });
        FeatureStatusTracker.setHooked("ReadReceiptChats");
        ModuleLog.line("(IE|ReadReceiptChat) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private static void register(Activity a) {
        try {
            if (threadHeaderId == 0) {
                threadHeaderId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
                headerTitleId = a.getResources().getIdentifier("header_title", "id", a.getPackageName());
            }
            View decor = a.getWindow().getDecorView();
            if (tryInject(a)) return;
            if (!pendingDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (tryInject(a)) {
                        pendingDecors.remove(decor);
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(IE|ReadReceiptChat) ⚠️ register: " + t);
        }
    }

    /** True once the entry is in this thread's menu (the header must be on screen). */
    private static boolean tryInject(Activity a) {
        View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
        if (header == null) return false;
        if (ThreadHeaderMenu.has(a, "read")) return true;
        String threadId = HideChatsHook.resolveThreadId(a);
        if (threadId == null) return false;
        return addEntry(a, threadId, header);
    }

    /** (Re)adds the entry so its label matches the chat's current state. */
    private static boolean addEntry(Activity a, String threadId, View header) {
        boolean on = ReadReceiptWhitelist.isListed(threadId);
        String label = I18n.t(a, on ? R.string.ig_read_receipt_chat_off : R.string.ig_read_receipt_chat_on);
        return ThreadHeaderMenu.add(a, "read", label, () -> {
            boolean now = !ReadReceiptWhitelist.isListed(threadId);
            ReadReceiptWhitelist.set(threadId, titleOf(header), now);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, now ? R.string.ig_toast_read_receipt_on : R.string.ig_toast_read_receipt_off),
                    Toast.LENGTH_SHORT).show();
            addEntry(a, threadId, header);
        });
    }

    private static String titleOf(View header) {
        View t = headerTitleId != 0 ? header.findViewById(headerTitleId) : null;
        return t instanceof TextView tv && tv.getText() != null ? tv.getText().toString().trim() : "";
    }
}
