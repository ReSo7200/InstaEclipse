package ps.reso.instaeclipse.mods.ui;

import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;

public class UIHookManager {

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;

    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    private static boolean isAnyGhostOptionEnabled() {
        return GhostModeUtils.isGhostModeActive();
    }

    public static void setupHooks(Activity activity) {
        // Hook Search Tab (open InstaEclipse Settings)
        String[] possibleSearch = {"search_tab", "action_bar_end_action_buttons"};

        for (String id : possibleSearch) {
            @SuppressLint("DiscouragedApi")
            int viewId = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);

            if (view != null) {
                processSearchView(activity, view, id);
            } else {
                // VIEW NOT FOUND YET: Wait for the layout to change and try again
                final View decorView = activity.getWindow().getDecorView();
                decorView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        View lateView = activity.findViewById(viewId);
                        if (lateView != null) {
                            processSearchView(activity, lateView, id);
                            // Remove listener so we don't keep calling this unnecessarily
                            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
            }
        }

        // Hook Inbox Button (toggle Ghost Quick Options)
        String[] possibleIds = {"action_bar_inbox_button", "direct_tab"};

        for (String id : possibleIds) {
            @SuppressLint("DiscouragedApi") int viewId = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);
            if (view != null) {
                hookLongPress(activity, id, v -> {
                    GhostModeUtils.toggleSelectedGhostOptions(activity);
                    VibrationUtil.vibrate(activity);
                    return true;
                });
                break;
            }
        }

        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Mark messages (DM) as seen by holding on gallery button
        hookLongPress(activity, "row_thread_composer_button_gallery", v -> {
            VibrationUtil.vibrate(activity);

            if (!FeatureFlags.isGhostSeen) {
                return true;
            }

            FeatureFlags.isGhostSeen = false;

            activity.getWindow().getDecorView().post(() -> {
                try {
                    // Look for the exact message list view by ID
                    @SuppressLint("DiscouragedApi") int messageListId = activity.getResources().getIdentifier("message_list", "id", activity.getPackageName());
                    View view = activity.findViewById(messageListId);

                    if (view instanceof ViewGroup messageList) {

                        // Try scrolling via translation if standard scroll methods don't exist
                        messageList.scrollBy(0, -100); // scroll up

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            messageList.scrollBy(0, 100); // scroll back down

                            FeatureFlags.isGhostSeen = true;
                            Toast.makeText(activity, I18n.t(activity, R.string.ig_toast_message_marked_read), Toast.LENGTH_SHORT).show();

                        }, 300);


                    } else {
                        XposedBridge.log("⚠️ message_list not a ViewGroup or not found — fallback to reset flag");

                        new Handler(Looper.getMainLooper()).postDelayed(() -> FeatureFlags.isGhostSeen = true, 300);
                    }
                } catch (Exception e) {
                    XposedBridge.log("❌ Exception in scroll logic: " + Log.getStackTraceString(e));
                }
            });

            return true;
        });

    }

    // Hook long press method
    private static void hookLongPress(Activity activity, String viewName, View.OnLongClickListener listener) {
        try {
            @SuppressLint("DiscouragedApi") int viewId = activity.getResources().getIdentifier(viewName, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);

            if (view != null) {
                view.setOnLongClickListener(listener);
            }
        } catch (Exception ignored) {
        }
    }

    public void mainActivity(ClassLoader classLoader) {
        // Hook onCreate of Instagram Main
        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = (Activity) param.thisObject;
                currentActivity = activity;
                activity.runOnUiThread(() -> {
                    try {
                        setupHooks(activity);
                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());
                        if (!FeatureFlags.showFeatureToasts || CustomToast.toastShown) return;
                        CustomToast.toastShown = true;

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            StringBuilder sb = new StringBuilder(I18n.t(activity, R.string.ig_toast_instaeclipse_loaded) + "\n");
                            for (Map.Entry<String, Boolean> entry : FeatureStatusTracker.getStatus().entrySet()) {
                                sb.append(entry.getValue() ? "✅ " : "❌ ").append(entry.getKey()).append("\n");
                            }
                            CustomToast.showCustomToast(activity.getApplicationContext(), sb.toString().trim());
                        }, 1000);
                    } catch (Exception ignored) {

                    }
                });
            }
        });

        // Hook onResume - Instagram Main
        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = (Activity) param.thisObject;
                currentActivity = activity;
                activity.runOnUiThread(() -> {
                    try {
                        setupHooks(activity);
                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());

                        if (FeatureFlags.isImportingConfig) {
                            // De-bounce: flip it off first so it won't re-trigger on next onResume
                            FeatureFlags.isImportingConfig = false;
                            ConfigManager.importConfigFromClipboard(activity);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        });


        // Hook getBottomSheetNavigator - Instagram Main
        BottomSheetHookUtil.hookBottomSheetNavigator(Module.dexKitBridge);

        // Hook onResume - Model
        XposedHelpers.findAndHookMethod("com.instagram.modal.ModalActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        });
    }

    private static void applySearchHook(Activity activity, View v) {
        v.setOnLongClickListener(view -> {
            DialogUtils.showEclipseOptionsDialog(activity);
            VibrationUtil.vibrate(activity);
            return true;
        });
    }

    private static void processSearchView(Activity activity, View view, String id) {
        if (id.equals("action_bar_end_action_buttons") && view instanceof ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                CharSequence description = child.getContentDescription();
                if (description != null && description.toString().toLowerCase().contains("search")) {
                    applySearchHook(activity, child);
                }
            }
        } else {
            applySearchHook(activity, view);
        }
    }

}
