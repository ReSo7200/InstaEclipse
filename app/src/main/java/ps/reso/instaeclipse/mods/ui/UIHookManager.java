package ps.reso.instaeclipse.mods.ui;

import static org.luckypray.dexkit.query.FindMethod.create;
import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.luckypray.dexkit.result.MethodData;

import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.utils.core.SettingsManager;
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
        try {
            // Precise search for the standard onCreate(Bundle) signature
            var methods = Module.dexKitBridge.findMethod(create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass("com.instagram.mainactivity.InstagramMainActivity")
                            .name("onCreate")
                            .paramTypes("android.os.Bundle")
                            .returnType("void")
                    )
            );

            // Fallback: If "onCreate" is renamed/obfuscated but still takes a Bundle
            if (methods.isEmpty()) {
                XposedBridge.log("(InstaEclipse): ⚠️ Specific onCreate not found, searching by signature...");
                methods = Module.dexKitBridge.findMethod(create()
                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                .declaredClass("com.instagram.mainactivity.InstagramMainActivity")
                                .paramTypes("android.os.Bundle")
                                .returnType("void")
                        )
                );
            }

            if (!methods.isEmpty()) {
                // Get the first match
                var methodData = methods.get(0);
                java.lang.reflect.Method targetMethod = methodData.getMethodInstance(classLoader);

                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Activity activity = (Activity) param.thisObject;
                        currentActivity = activity;

                        // Use runOnUiThread to ensure we are touching the UI safely
                        activity.runOnUiThread(() -> {
                            try {
                                // 1. Initialize Hooks
                                setupHooks(activity);

                                // 2. Delay UI injections slightly.
                                // Instagram's Main is complex; the Inbox/UI might not be inflated immediately.
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        // Add the Ghost Emoji next to Inbox
                                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());

                                        // 3. Show Success Toast
                                        if (FeatureFlags.showFeatureToasts && !CustomToast.toastShown) {
                                            CustomToast.toastShown = true;

                                            StringBuilder sb = new StringBuilder("InstaEclipse Loaded 🎯\n");
                                            for (Map.Entry<String, Boolean> entry : FeatureStatusTracker.getStatus().entrySet()) {
                                                sb.append(entry.getValue() ? "✅ " : "❌ ").append(entry.getKey()).append("\n");
                                            }
                                            CustomToast.showCustomToast(activity.getApplicationContext(), sb.toString().trim());
                                        }
                                    } catch (Exception innerE) {
                                        XposedBridge.log("(InstaEclipse): UI Injection Error: " + innerE.getMessage());
                                    }
                                }, 1500); // 1.5s delay to let the UI settle

                            } catch (Exception e) {
                                XposedBridge.log("(InstaEclipse): UI logic error in onCreate: " + e);
                            }
                        });
                    }
                });
            } else {
                XposedBridge.log("(InstaEclipse): ❌ Failed to find any onCreate candidate in InstagramMainActivity");
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse): ❌ DexKit discovery failed: " + e.getMessage());
        }

        // Hook onResume - Instagram Main
        try {
            List<MethodData> candidates = Module.dexKitBridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass("com.instagram.mainactivity.InstagramMainActivity")
                            .modifiers(java.lang.reflect.Modifier.PUBLIC)
                            .paramCount(0)
                            .returnType("void")
                    )
            );

            for (MethodData methodData : candidates) {
                String methodName = methodData.getName();

                // Skip constructors and static initializers
                if (methodName.contains("<init>") || methodName.contains("<clinit>")) {
                    continue;
                }

                // Filter by opcode size to find the substantial lifecycle method
                if (methodData.getOpCodes().size() < 20) {
                    continue;
                }

                java.lang.reflect.Method targetMethod = methodData.getMethodInstance(classLoader);
                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Activity activity = (Activity) param.thisObject;
                        currentActivity = activity;
                        activity.runOnUiThread(() -> {
                            try {
                                setupHooks(activity);
                                addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());
                            } catch (Exception e) {
                                XposedBridge.log("(InstaEclipse) UI Error: " + e);
                            }
                        });
                    }
                });
                break;
            }
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse): ❌ onResume discovery failed: " + t.getMessage());
        }

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

    /** Registers a broadcast receiver in the Instagram process to handle config imports. */
    public static void registerConfigImportReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                if (json != null && !json.isEmpty()) {
                    ConfigManager.importConfigFromJson(ctx, json);
                }
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"),
                    android.content.Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"));
        }
    }

    /** Registers a receiver in the Instagram process to restore settings from a backup JSON. */
    public static void registerSettingsRestoreReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                if (json == null || json.isEmpty()) return;
                new Thread(() -> {
                    try {
                        ps.reso.instaeclipse.utils.backup.SettingsBackupManager.fromJson(json);
                        SettingsManager.saveAllFlags();
                        ps.reso.instaeclipse.utils.feature.FeatureManager.refreshFeatureStatus();
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(),
                                "✅ " + I18n.t(ctx, R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(),
                                "❌ " + I18n.t(ctx, R.string.ig_toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        };
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver,
                        new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"),
                        android.content.Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver,
                        new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"));
            }
            } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | RestoreReceiver): ❌ " + e.getMessage());
        }
    }

}