package ps.reso.instaeclipse.mods.ui;

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


import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.CleanUiManager;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class UIHookManager {

    private static final String INSTAGRAM_MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;
    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    /** Activity to show our dialogs on: the one {@code ctx} belongs to (posts, stories and
     *  highlights opened from a profile run in their own activity above the main one), else the
     *  main activity, else {@code ctx} itself. */
    public static android.content.Context dialogHost(android.content.Context ctx) {
        android.content.Context c = ctx;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity a) {
                if (!a.isFinishing()) return a;
                break;
            }
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        Activity main = currentActivity;
        return main != null && !main.isFinishing() ? main : ctx;
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
    private static volatile int sTabBarId = 0;
    private static volatile int sFeedTabId = 0;

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
        if (sTabBarId == 0)
            sTabBarId = res.getIdentifier("tab_bar", "id", pkg);
        if (sFeedTabId == 0)
            sFeedTabId = res.getIdentifier("feed_tab", "id", pkg);
    }

    // Tracks views we've already attached the Clean UI listener to
    private static final java.util.WeakHashMap<View, Boolean> sCleanUiListenerAttached = new java.util.WeakHashMap<>();

    private static void setupCleanUiGesture(Activity activity, View v) {
        if (v == null || sCleanUiListenerAttached.containsKey(v)) return;
        sCleanUiListenerAttached.put(v, true);

        v.setOnTouchListener(new View.OnTouchListener() {
            private int tapCount = 0;
            private boolean longPressTriggered = false;
            private float startX, startY;
            private final Handler handler = new Handler(Looper.getMainLooper());

            private final Runnable longPressRunnable = () -> {
                longPressTriggered = true;
                v.setPressed(false);
                tapCount = 0;
                VibrationUtil.vibrate(activity);
                CleanUiManager.toggleCleanUi(activity);
            };

            private final Runnable resetTapCountRunnable = () -> {
                tapCount = 0;
            };

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                int mode = FeatureFlags.cleanUiToggleGesture;
                if (mode == 0) return false;

                int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        longPressTriggered = false;

                        if (mode == 1) {
                            handler.postDelayed(longPressRunnable, 500);
                        } else if (mode == 2) {
                            if (tapCount == 2) {
                                handler.postDelayed(longPressRunnable, 450);
                            }
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(event.getX() - startX, event.getY() - startY) > slop) {
                            handler.removeCallbacks(longPressRunnable);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);

                        if (longPressTriggered) {
                            return true; // Consume event so normal scroll/refresh doesn't happen
                        }

                        if (event.getAction() == MotionEvent.ACTION_UP && mode == 2) {
                            tapCount++;
                            handler.removeCallbacks(resetTapCountRunnable);
                            handler.postDelayed(resetTapCountRunnable, 350);
                        }
                        break;
                }
                return false;
            }
        });
    }

    private static void processCleanUiHomeTab(Activity activity) {
        if (FeatureFlags.cleanUiToggleGesture == 0) return;

        View homeTab = null;
        if (sFeedTabId != 0) {
            homeTab = activity.findViewById(sFeedTabId);
        }
        if (homeTab == null && sTabBarId != 0) {
            View tabBar = activity.findViewById(sTabBarId);
            if (tabBar instanceof ViewGroup && ((ViewGroup) tabBar).getChildCount() > 0) {
                homeTab = ((ViewGroup) tabBar).getChildAt(0);
            }
        }
        if (homeTab != null) {
            setupCleanUiGesture(activity, homeTab);
        }
    }

    public static void setupHooks(Activity activity) {
        // Ghost emoji visibility must update on every resume (reflects current ghost state).
        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Auto-clear IG cache if it has grown past the configured size (throttled internally).
        ps.reso.instaeclipse.utils.core.CacheAutoClear.maybeClear(activity);

        // Lock DMs: arm the inbox passcode watcher on the main activity (its onCreate is
        // obfuscated, so a direct hook fails — this runs from the module's resolved main hook).
        ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.watchActivity(activity);

        // Remove Meta AI: collapse the "About this reel" Content Deep Dive section (AI summary +
        // "Ask Meta AI…" composer) inside the reel/post action sheet. It is a Litho-rendered
        // section not reachable via the option/builder hooks, so we strip it at the view level.
        ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook.watchActionSheet(activity);

        // Distraction Free: collapse the story tray / Reels tab at the view level (the network drop
        // alone leaves IG's cached stories/reels visible on IG 447.0.0.39+).
        ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.watchActivity(activity);
        ps.reso.instaeclipse.mods.ui.StoryTrayHook.watchActivity(activity);

        // Fancy bio fonts: the bio's "Change font" button opens the module's font picker.
        ps.reso.instaeclipse.mods.ui.FancyBioFontHook.watchActivity(activity);

                // Attach Batch Hide Chats Hook for Direct Multi-Select
        View root = activity.findViewById(android.R.id.content);
        if (root != null) {
            ps.reso.instaeclipse.mods.ghost.BatchHideChatsHook.attach(activity, root);
        }

        // Cache resource IDs once per IG install (string table lookup is non-trivial).
        ensureIdsCached(activity);

        processCleanUiHomeTab(activity);

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
                    processCleanUiHomeTab(activity);
                }
            });
        }

    }

    public void mainActivity(ClassLoader classLoader) {
        // Hook onCreate / onResume of Instagram Main.
        // IG 448+ no longer declares these overrides on InstagramMainActivity (they live in the
        // shared base com.instagram.base.activity.IgFragmentActivity, and the activity only has
        // obfuscated delegates like A1p(Bundle, X)). The old DexKit lookup (declaredClass +
        // "onCreate"/(Bundle)V, then a "public void() with >=20 opcodes" guess for onResume) found
        // nothing there, so setupHooks() — the settings entry, ghost emoji, Distraction-Free view
        // hooks, feature toast — never ran from the main activity. LifecycleHookUtil hooks the
        // class that actually declares the method and filters by instance, so it resolves on old
        // builds (declared override, same as before) and new ones alike.
        try {
            String where = LifecycleHookUtil.hookAfter(classLoader, INSTAGRAM_MAIN_ACTIVITY, "onCreate",
                    (activity, param) -> onMainActivityCreated(activity), Bundle.class);
            ModuleLog.line("(InstaEclipse): ✅ main onCreate hooked via " + where);
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse): ❌ main onCreate hook failed: " + t);
        }

        try {
            String where = LifecycleHookUtil.hookAfter(classLoader, INSTAGRAM_MAIN_ACTIVITY, "onResume",
                    (activity, param) -> {
                        currentActivity = activity;
                        activity.runOnUiThread(() -> {
                            try {
                                setupHooks(activity);
                            } catch (Exception e) {
                                ModuleLog.line("(InstaEclipse) UI Error: " + e);
                            }
                        });
                    });
            ModuleLog.line("(InstaEclipse): ✅ main onResume hooked via " + where);
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse): ❌ main onResume hook failed: " + t);
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
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
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

        // Hook onResume - Modal
        try {
            LifecycleHookUtil.hookAfter(classLoader, "com.instagram.modal.ModalActivity", "onResume",
                    (activity, param) -> activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity);
                        } catch (Exception ignored) {
                        }
                    }));
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse): ⚠️ ModalActivity onResume hook failed: " + t);
        }
    }

    private static void onMainActivityCreated(Activity activity) {
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

                            // Pass the raw KEY -> hooked map; showFeatureGrid resolves
                            // each key's label and groups it into a category itself.
                            java.util.LinkedHashMap<String, Boolean> status =
                                    new java.util.LinkedHashMap<>(FeatureStatusTracker.getStatus());
                            CustomToast.showFeatureGrid(activity.getApplicationContext(),
                                    I18n.t(activity, R.string.ig_toast_features_loaded), status);
                        }
                    } catch (Exception innerE) {
                        ModuleLog.line("(InstaEclipse): UI Injection Error: " + innerE.getMessage());
                    }
                }, 1500); // 1.5s delay to let the UI settle

            } catch (Exception e) {
                ModuleLog.line("(InstaEclipse): UI logic error in onCreate: " + e);
            }
        });
    }

    private static void applySearchHook(Activity activity, View v) {
        if (!FeatureFlags.secretMenuDoubleTap) {
            v.setOnLongClickListener(view -> {
                DialogUtils.showEclipseOptionsDialog(activity);
                VibrationUtil.vibrate(activity);
                return true;
            });
            v.setOnTouchListener(null);
            return;
        }

        v.setOnLongClickListener(null); // remove standard listener to avoid conflicts
        v.setOnTouchListener(new View.OnTouchListener() {
            private int tapCount = 0;
            private long lastTapTime = 0;
            private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            private final Runnable resetTaps = () -> tapCount = 0;
            private final Runnable longPressAction = () -> {
                if (tapCount == 2) {
                    DialogUtils.showEclipseOptionsDialog(activity);
                    ps.reso.instaeclipse.mods.ui.utils.VibrationUtil.vibrate(activity);
                    tapCount = 0;
                }
            };

            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        long time = System.currentTimeMillis();
                        if (time - lastTapTime > 350) {
                            tapCount = 0;
                        }
                        tapCount++;
                        lastTapTime = time;
                        handler.removeCallbacks(resetTaps);
                        handler.removeCallbacks(longPressAction);

                        if (tapCount == 3) {
                            handler.postDelayed(longPressAction, 450);
                        }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressAction);
                        handler.postDelayed(resetTaps, 350);
                        break;
                }
                return false; // Let normal clicks through
            }
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
                try { ps.reso.instaeclipse.utils.ipc.BrokerPayload.validateConfig(json); }
                catch (IllegalArgumentException malformed) {
                    ModuleLog.line("(IE|Config) Invalid or oversized configuration rejected");
                    return;
                }
                if (json != null && !json.isEmpty()) {
                    ConfigManager.importConfigFromJson(ctx, json);
                }
            }
        };
        ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver,
                new IntentFilter("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG"));
    }

    /** Registers a receiver in the Instagram process to restore settings from a backup JSON. */
    public static void registerSettingsRestoreReceiver(android.content.Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ctx, Intent intent) {
                String json = intent.getStringExtra("json_content");
                try { ps.reso.instaeclipse.utils.ipc.BrokerPayload.validateConfig(json); }
                catch (IllegalArgumentException malformed) {
                    ModuleLog.line("(IE|Config) Invalid or oversized configuration rejected");
                    return;
                }
                if (json == null || json.isEmpty()) return;
                new Thread(() -> {
                    try {
                        ps.reso.instaeclipse.utils.backup.SettingsBackupManager.fromJson(json);
                        SettingsManager.saveAllFlags();
                        ps.reso.instaeclipse.utils.feature.FeatureManager.refreshFeatureStatus();
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx.getApplicationContext(),
                                "✅ " + I18n.t(ctx, R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        Handler mainHandler = new Handler(Looper.getMainLooper());
                        mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx.getApplicationContext(),
                                "❌ " + I18n.t(ctx, R.string.ig_toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
        };
        try {
            ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver,
                    new IntentFilter("ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS"));
            } catch (Throwable e) {
            ModuleLog.line("(InstaEclipse | RestoreReceiver): ❌ " + e.getMessage());
        }
    }

}
