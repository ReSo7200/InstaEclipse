package ps.reso.instaeclipse.mods.ui;

import static org.luckypray.dexkit.query.FindMethod.create;
import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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

    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;
    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    /**
     * Activities that already have a pending OnGlobalLayoutListener registered.
     * Used only to prevent duplicate listener registrations when the search view
     * is not yet visible at setup time.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sGlobalListenerPending =
            new java.util.WeakHashMap<>();
    /**
     * Tracks whether the GlobalLayoutListener path has completed for an activity,
     * so we don't keep registering new listeners on every resume after search is found.
     */
    private static final java.util.WeakHashMap<Activity, Boolean> sSearchWiringDone =
            new java.util.WeakHashMap<>();

    // Resource IDs are constant for a given IG install — cache them statically.
    private static volatile int sSearchTabId = 0;
    private static volatile int sActionBarEndId = 0;
    private static volatile int sInboxButtonId = 0;
    private static volatile int sDirectTabId = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIdsCached(Activity activity) {
        if (sSearchTabId != 0 && sActionBarEndId != 0
                && sInboxButtonId != 0 && sDirectTabId != 0) return;
        String pkg = activity.getPackageName();
        android.content.res.Resources res = activity.getResources();
        if (sSearchTabId == 0)
            sSearchTabId = res.getIdentifier("search_tab", "id", pkg);
        if (sActionBarEndId == 0)
            sActionBarEndId = res.getIdentifier("action_bar_end_action_buttons", "id", pkg);
        if (sInboxButtonId == 0)
            sInboxButtonId = res.getIdentifier("action_bar_inbox_button", "id", pkg);
        if (sDirectTabId == 0)
            sDirectTabId = res.getIdentifier("direct_tab", "id", pkg);
    }

    public static void setupHooks(Activity activity) {
        // Ghost emoji visibility must update on every resume (reflects current ghost state).
        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Cache resource IDs once per IG install (string table lookup is non-trivial).
        ensureIdsCached(activity);

        // Always re-apply the search long-press listener. Instagram may overwrite it after
        // a config change (e.g. when InstaEclipse settings are toggled), so we cannot skip
        // this on resume — we just avoid the expensive getIdentifier() call via the cache.
        boolean anySearchFound = false;
        if (sSearchTabId != 0) {
            View v = activity.findViewById(sSearchTabId);
            if (v != null) { processSearchView(activity, v, "search_tab"); anySearchFound = true; }
        }
        if (!anySearchFound && sActionBarEndId != 0) {
            View v = activity.findViewById(sActionBarEndId);
            if (v != null) { processSearchView(activity, v, "action_bar_end_action_buttons"); anySearchFound = true; }
        }

        // Register at most ONE GlobalLayoutListener per activity to retry search wiring
        // when the view isn't inflated yet. Skip if we already found search, if a listener
        // is already pending, or if the listener already completed successfully.
        if (!anySearchFound
                && !Boolean.TRUE.equals(sGlobalListenerPending.get(activity))
                && !Boolean.TRUE.equals(sSearchWiringDone.get(activity))) {
            sGlobalListenerPending.put(activity, true);
            final View decorView = activity.getWindow().getDecorView();
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    boolean found = false;
                    if (sSearchTabId != 0) {
                        View lateView = activity.findViewById(sSearchTabId);
                        if (lateView != null) { processSearchView(activity, lateView, "search_tab"); found = true; }
                    }
                    if (!found && sActionBarEndId != 0) {
                        View lateView = activity.findViewById(sActionBarEndId);
                        if (lateView != null) { processSearchView(activity, lateView, "action_bar_end_action_buttons"); found = true; }
                    }
                    if (found) {
                        decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        sGlobalListenerPending.remove(activity);
                        sSearchWiringDone.put(activity, true);
                    }
                }
            });
        }

    }

    public void mainActivity(ClassLoader classLoader) {
        // Hook onCreate of Instagram Main
        try {
            // Precise search for the standard onCreate(Bundle) signature
            var methods = Module.dexKitBridge.findMethod(create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
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
                                .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
                                .paramTypes("android.os.Bundle")
                                .returnType("void")
                        )
                );
            }

            if (!methods.isEmpty()) {
                String methodName = methods.get(0).getName();
                if (methodName == null || methodName.isEmpty()) {
                    XposedBridge.log("(InstaEclipse): ❌ Invalid onCreate method name discovered");
                    return;
                }
                XposedHelpers.findAndHookMethod(INSTAGRAM_MAIN_ACTIVITY, classLoader, methodName, Bundle.class, new XC_MethodHook() {
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
                                        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

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
                            .declaredClass(INSTAGRAM_MAIN_ACTIVITY)
                            .modifiers(java.lang.reflect.Modifier.PUBLIC)
                            .paramCount(0)
                            .returnType("void")
                    )
            );

            for (MethodData methodData : candidates) {
                String methodName = methodData.getName();
                if (methodName == null || methodName.isEmpty()) {
                    continue;
                }

                // Skip constructors and static initializers
                if (methodName.contains("<init>") || methodName.contains("<clinit>")) {
                    continue;
                }

                // Filter by opcode size to find the substantial lifecycle method
                if (methodData.getOpCodes().size() < 20) {
                    continue;
                }

                XposedHelpers.findAndHookMethod(INSTAGRAM_MAIN_ACTIVITY, classLoader, methodName, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final Activity activity = (Activity) param.thisObject;
                        currentActivity = activity;
                        activity.runOnUiThread(() -> {
                            try {
                                setupHooks(activity);
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

        // Hook View.performLongClick — inbox long-press override.
        // setOnLongClickListener is unreliable when Instagram has a parent-level touch
        // interceptor or a custom view that overrides long-press dispatch. Hooking
        // performLongClick() fires BEFORE any listener/interceptor chain and lets us
        // fully own the event by returning true via setResult.
        // This only fires when the user actually long-presses something — not a hot path.
        XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (sInboxButtonId == 0 && sDirectTabId == 0) return;
                View view = (View) param.thisObject;
                int id = view.getId();
                if (id != sInboxButtonId && id != sDirectTabId) return;
                Activity activity = currentActivity;
                if (activity == null) return;
                GhostModeUtils.toggleSelectedGhostOptions(activity);
                VibrationUtil.vibrate(activity);
                param.setResult(true); // consume — skip Instagram's handler entirely
            }
        });

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
