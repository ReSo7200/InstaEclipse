package ps.reso.instaeclipse.utils.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class DialogUtils {

    private static AlertDialog currentDialog;

    @SuppressLint("UseCompatLoadingForDrawables")
    public static void showEclipseOptionsDialog(Context context) {
        SettingsManager.init(context);

        LinearLayout mainLayout = buildMainMenuLayout(context);
        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(mainLayout);

        if (currentDialog != null && currentDialog.isShowing()) {
            try { currentDialog.dismiss(); } catch (Exception ignored) {}
        }
        currentDialog = null;

        currentDialog = createBottomSheetDialog(context, scrollView);
        currentDialog.show();
    }

    public static void showSimpleDialog(Context context, String title, String message) {
        try {
            new AlertDialog.Builder(context).setTitle(title).setMessage(message)
                    .setPositiveButton(I18n.t(context, R.string.ig_dialog_ok), null).show();
        } catch (Exception e) {
            // handle UI crash fallback
        }
    }

    @SuppressLint("SetTextI18n")
    private static LinearLayout buildMainMenuLayout(Context context) {
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(0, 0, 0, 0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#1C1C1E"));
        background.setCornerRadii(new float[]{40, 40, 40, 40, 0, 0, 0, 0});
        mainLayout.setBackground(background);

        mainLayout.addView(createDragHandle(context));

        // Title
        TextView title = new TextView(context);
        title.setText(I18n.t(context, R.string.ig_dialog_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(40, 8, 40, 20);
        mainLayout.addView(title);

        mainLayout.addView(createDivider(context));

        // Now building menu manually

        // 0 - Developer Options => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_dev_options), () -> showDevOptions(context)));

        // 1 - Ghost Mode Settings => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_ghost_settings), () -> showGhostOptions(context)));

        // 2 - Ad/Analytics Block => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_ad_analytics), () -> showAdOptions(context)));

        // 3 - Distraction-Free Instagram => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_distraction_free), () -> showDistractionOptions(context)));

        // 4 - Misc Features => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_misc), () -> showMiscOptions(context)));

        // 5 - Downloader => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_downloader), () -> showDownloaderOptions(context)));

        // 7 - Backup & Restore => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_backup_restore), () -> showBackupRestoreOptions(context)));

        // 8 - About => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_about), () -> showAboutDialog(context)));

        // 9 - Restart Instagram => OPEN PAGE
        mainLayout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_menu_restart), () -> showRestartSection(context)));

        mainLayout.addView(createDivider(context));

        // Footer Credit
        TextView footer = new TextView(context);
        footer.setText("@reso7200");
        footer.setTextColor(Color.parseColor("#8E8E93"));
        footer.setTextSize(13);
        footer.setPadding(40, 20, 40, 4);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        mainLayout.addView(footer);

        // Embedded Close Button
        TextView closeButton = new TextView(context);
        closeButton.setText(I18n.t(context, R.string.ig_dialog_close));
        closeButton.setTextColor(Color.parseColor("#FF453A"));
        closeButton.setTextSize(16);
        closeButton.setPadding(40, 20, 40, 40);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setTypeface(null, Typeface.BOLD);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#20FF453A")));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        closeButton.setBackground(states);

        closeButton.setOnClickListener(v -> {
            if (currentDialog != null) { try { currentDialog.dismiss(); } catch (Exception ignored) {} currentDialog = null; }
        });

        mainLayout.addView(closeButton);

        SettingsManager.saveAllFlags();

        Activity activity = UIHookManager.getCurrentActivity();
        if (activity != null) {
            GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
        }

        return mainLayout;
    }


    private static void showGhostQuickToggleOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create switches for customizing what gets toggled
        ToggleRow[] toggleSwitches = new ToggleRow[]{
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_hide_seen),           FeatureFlags.quickToggleSeen),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_hide_typing),         FeatureFlags.quickToggleTyping),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_disable_screenshot),  FeatureFlags.quickToggleScreenshot),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_hide_view_once),      FeatureFlags.quickToggleViewOnce),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_hide_story_seen),     FeatureFlags.quickToggleStory),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_hide_live_seen),      FeatureFlags.quickToggleLive),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_keep_ephemeral),      FeatureFlags.quickToggleEphemeral),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_unlimited_replays),   FeatureFlags.quickToggleReplays),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_permanent_view),      FeatureFlags.quickTogglePermanentView),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_quick_allow_screenshots),   FeatureFlags.quickToggleAllowScreenshots)};

        // Create Enable/Disable All switch
        ToggleRow enableAllSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(toggleSwitches));

        // Master listener
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s :toggleSwitches) {
                s.setChecked(isChecked);
            }
        });

        // Individual switch listeners (update master switch automatically)
        for (int i = 0; i < toggleSwitches.length; i++) {
            final int index = i;
            toggleSwitches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(toggleSwitches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (ToggleRow s2 :toggleSwitches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Update corresponding FeatureFlag instantly
                switch (index) {
                    case 0:
                        FeatureFlags.quickToggleSeen = isChecked;
                        break;
                    case 1:
                        FeatureFlags.quickToggleTyping = isChecked;
                        break;
                    case 2:
                        FeatureFlags.quickToggleScreenshot = isChecked;
                        break;
                    case 3:
                        FeatureFlags.quickToggleViewOnce = isChecked;
                        break;
                    case 4:
                        FeatureFlags.quickToggleStory = isChecked;
                        break;
                    case 5:
                        FeatureFlags.quickToggleLive = isChecked;
                        break;
                    case 6:
                        FeatureFlags.quickToggleEphemeral = isChecked;
                        break;
                    case 7:
                        FeatureFlags.quickToggleReplays = isChecked;
                        break;
                    case 8:
                        FeatureFlags.quickTogglePermanentView = isChecked;
                        break;
                    case 9:
                        FeatureFlags.quickToggleAllowScreenshots = isChecked;
                        break;
                }

                // Save immediately
                SettingsManager.saveAllFlags();

                // Update ghost emoji immediately
                Activity activity = UIHookManager.getCurrentActivity();
                if (activity != null) {
                    GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                }
            });
        }


        // Add views to layout
        layout.addView(createDivider(context)); // Divider above
        layout.addView(createEnableAllSwitch(context, enableAllSwitch)); // Styled enable all switch
        layout.addView(createDivider(context)); // Divider below

        for (ToggleRow s :toggleSwitches) {
            layout.addView(s);
        }

        // Show dialog
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_quick_toggle), layout, () -> {
        });

    }


    private static View createDivider(Context context) {
        View divider = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2);
        params.setMargins(0, 20, 0, 20);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(Color.DKGRAY);
        return divider;
    }

    /**
     * Clears the application's cache and restarts it.
     * Works for any package name this module is running in.
     *
     * @param context The application context.
     */
    private static void restartApp(Context context) {
        try {
            String packageName = context.getPackageName();
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

            if (launchIntent != null) {
                clearAppCache(context);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                Runtime.getRuntime().exit(0);
            } else {
                Toast.makeText(context, I18n.t(context, R.string.ig_dialog_restart_not_found), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            String packageName = context.getPackageName();
            XposedBridge.log("InstaEclipse: Restart failed for " + packageName + " - " + e.getMessage());
            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_restart_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Clears the cache directory for the current application.
     *
     * @param context The application context.
     */
    private static void clearAppCache(Context context) {
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.isDirectory()) {
                deleteRecursive(cacheDir);
                XposedBridge.log("InstaEclipse: Cache cleared for " + context.getPackageName());
            } else {
                XposedBridge.log("InstaEclipse: Cache directory not found for " + context.getPackageName());
            }
        } catch (Exception e) {
            XposedBridge.log("InstaEclipse: Failed to clear cache for " + context.getPackageName() + " - " + e.getMessage());
        }
    }

    /**
     * Recursively deletes a file or directory.
     *
     * @param fileOrDirectory The file or directory to delete.
     */
    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        // A direct result for a file or an empty directory
        fileOrDirectory.delete();
    }


    // ==== SECTIONS ====

    @SuppressLint("SetTextI18n")
    private static void showDevOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Developer Mode Switch
        ToggleRow devModeSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_dev_enable), FeatureFlags.isDevEnabled);
        devModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.isDevEnabled = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(devModeSwitch);
        layout.addView(createDivider(context));

        layout.addView(createActionRow(context, "📥", I18n.t(context, R.string.ig_dialog_dev_import), "#30D158", v -> {
            Activity instagramActivity = UIHookManager.getCurrentActivity();
            if (instagramActivity != null && !instagramActivity.isFinishing()) {
                Intent importIntent = new Intent();
                importIntent.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
                importIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                importIntent.putExtra("target_package", context.getPackageName());
                try {
                    instagramActivity.startActivity(importIntent);
                } catch (Exception e) {
                    XposedBridge.log("InstaEclipse | ❌ Failed to start JsonImportActivity: " + e.getMessage());
                    showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_unable_open_ui));
                }
            } else {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
            }
        }));

        layout.addView(createActionRow(context, "📤", I18n.t(context, R.string.ig_dialog_dev_export), "#0A84FF", v -> {
            Activity instagramActivity = UIHookManager.getCurrentActivity();
            if (instagramActivity != null && !instagramActivity.isFinishing()) {
                try {
                    File source = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
                    if (!source.exists()) {
                        showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_mc_overrides_not_found));
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(source))) {
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    }
                    String json = sb.toString().trim();
                    Intent exportIntent = new Intent();
                    exportIntent.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                    exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    exportIntent.putExtra("json_content", json);
                    instagramActivity.startActivity(exportIntent);
                } catch (Exception e) {
                    showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_failed_read_config, e.getMessage()));
                }
            } else {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
            }
        }));
        layout.addView(createDivider(context));

        ToggleRow buildExpiredSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_dev_remove_build_expired), FeatureFlags.removeBuildExpiredPopup);
        buildExpiredSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.removeBuildExpiredPopup = isChecked;
            SettingsManager.saveAllFlags();
        });
        layout.addView(buildExpiredSwitch);

        // Save current dev mode flag when dialog is closed
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_dev_options), layout, SettingsManager::saveAllFlags);
    }

    private static void showGhostOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        ToggleRow[] switches = new ToggleRow[]{
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_hide_dm_seen),         FeatureFlags.isGhostSeen),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_hide_typing),          FeatureFlags.isGhostTyping),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_hide_story_views),     FeatureFlags.isGhostStory),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_hide_live_presence),   FeatureFlags.isGhostLive),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_allow_screenshots_dms),FeatureFlags.allowScreenshots),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_bypass_screenshot),    FeatureFlags.isGhostScreenshot),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_hide_view_once),       FeatureFlags.isGhostViewOnce),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_unlimited_replays),    FeatureFlags.enableUnlimitedReplays),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_permanent_view_once),  FeatureFlags.permanentViewMode),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_ghost_keep_disappearing),    FeatureFlags.keepEphemeralMessages)};

        layout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_customize_quick_toggle), () -> showGhostQuickToggleOptions(context)));

        ToggleRow enableAllSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(switches));

        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s :switches) {
                s.setChecked(isChecked);
            }
        });

        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (ToggleRow s2 :switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Set FeatureFlag immediately
                switch (index) {
                    case 0:
                        FeatureFlags.isGhostSeen = isChecked;
                        break;
                    case 1:
                        FeatureFlags.isGhostTyping = isChecked;
                        break;
                    case 2:
                        FeatureFlags.isGhostScreenshot = isChecked;
                        break;
                    case 3:
                        FeatureFlags.isGhostViewOnce = isChecked;
                        break;
                    case 4:
                        FeatureFlags.enableUnlimitedReplays = isChecked;
                        break;
                    case 5:
                        FeatureFlags.isGhostStory = isChecked;
                        break;
                    case 6:
                        FeatureFlags.isGhostLive = isChecked;
                        break;
                    case 7:
                        FeatureFlags.allowScreenshots = isChecked;
                        break;
                    case 8:
                        FeatureFlags.keepEphemeralMessages = isChecked;
                        break;
                    case 9:
                        FeatureFlags.permanentViewMode = isChecked;
                        break;
                }

                // Save immediately
                SettingsManager.saveAllFlags();

                // Update ghost emoji immediately
                Activity activity = UIHookManager.getCurrentActivity();
                if (activity != null) {
                    GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                }
            });
        }

        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (ToggleRow s :switches) {
            layout.addView(s);
        }

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_ghost_mode), layout, () -> {
            // No need to set FeatureFlags here anymore because handled instantly
        });
    }


    private static void showAdOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create switches
        ToggleRow adBlock = createSwitch(context, I18n.t(context, R.string.ig_dialog_ad_block_ads), FeatureFlags.isAdBlockEnabled);

        ToggleRow analytics = createSwitch(context, I18n.t(context, R.string.ig_dialog_ad_block_analytics), FeatureFlags.isAnalyticsBlocked);

        ToggleRow trackingLinks = createSwitch(context, I18n.t(context, R.string.ig_dialog_ad_disable_tracking), FeatureFlags.disableTrackingLinks);

        ToggleRow[] switches = new ToggleRow[]{adBlock, analytics, trackingLinks};

        // Create Enable/Disable All switch
        ToggleRow enableAllSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(switches));

        // Master listener
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s :switches) {
                s.setChecked(isChecked);
            }
        });

        // Individual switch listeners
        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (ToggleRow s2 :switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Update FeatureFlag immediately
                if (index == 0) FeatureFlags.isAdBlockEnabled = isChecked;
                if (index == 1) FeatureFlags.isAnalyticsBlocked = isChecked;
                if (index == 2) FeatureFlags.disableTrackingLinks = isChecked;

                // Save immediately
                SettingsManager.saveAllFlags();
            });
        }


        // Add views
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (ToggleRow s :switches) {
            layout.addView(s);
        }

        // Show the dialog
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_ad_analytics), layout, () -> {
        });
    }


    private static void showDistractionOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Child switches
        ToggleRow extremeModeSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_extreme_mode), FeatureFlags.isExtremeMode);
        ToggleRow disableStoriesSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_disable_stories), FeatureFlags.disableStories);
        ToggleRow disableFeedSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_disable_feed), FeatureFlags.disableFeed);
        ToggleRow disableReelsSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_disable_reels), FeatureFlags.disableReels);
        ToggleRow onlyInDMSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_disable_reels_except_dm), FeatureFlags.disableReelsExceptDM);
        ToggleRow disableExploreSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_disable_explore), FeatureFlags.disableExplore);
        ToggleRow disableCommentsSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_distraction_disable_comments), FeatureFlags.disableComments);

        ToggleRow[] switches = new ToggleRow[]{disableStoriesSwitch, disableFeedSwitch, disableReelsSwitch, onlyInDMSwitch, disableExploreSwitch, disableCommentsSwitch};


        // Enable/Disable All
        ToggleRow enableAllSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(switches));

        if (FeatureFlags.isExtremeMode) {
            disableAllSwitches(switches, enableAllSwitch, onlyInDMSwitch);
            extremeModeSwitch.setChecked(true);
            extremeModeSwitch.setEnabled(false);
        }

        // Helper: extreme mode is only available when at least one feature is selected
        Runnable updateExtremeSwitchEnabled = () -> {
            if (!FeatureFlags.isExtremeMode) {
                boolean anyEnabled = false;
                for (ToggleRow s : switches) {
                    if (s.isChecked()) { anyEnabled = true; break; }
                }
                extremeModeSwitch.setEnabled(anyEnabled);
            }
        };

        // Initial state: disable extreme mode toggle if nothing is selected yet
        updateExtremeSwitchEnabled.run();

        extremeModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(I18n.t(context, R.string.ig_dialog_distraction_extreme_title));
                builder.setMessage(I18n.t(context, R.string.ig_dialog_distraction_extreme_message));
                builder.setPositiveButton(I18n.t(context, R.string.ig_dialog_yes), (dialog, which) -> {
                    FeatureFlags.isExtremeMode = true;
                    FeatureFlags.isDistractionFree = true;

                    // Save user’s current selections before freezing them
                    FeatureFlags.disableStories = disableStoriesSwitch.isChecked();
                    FeatureFlags.disableFeed = disableFeedSwitch.isChecked();
                    FeatureFlags.disableReels = disableReelsSwitch.isChecked();
                    FeatureFlags.disableReelsExceptDM = onlyInDMSwitch.isChecked();
                    FeatureFlags.disableExplore = disableExploreSwitch.isChecked();
                    FeatureFlags.disableComments = disableCommentsSwitch.isChecked();
                    SettingsManager.saveAllFlags();

                    // Disable all UI switches to lock them
                    disableAllSwitches(switches, enableAllSwitch, onlyInDMSwitch);
                    extremeModeSwitch.setEnabled(false);
                });
                builder.setNegativeButton(I18n.t(context, R.string.ig_dialog_cancel), (dialog, which) -> extremeModeSwitch.setChecked(false));
                builder.show();
            }
        });

        // Master switch listener
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s : switches) {
                s.setChecked(isChecked);
                s.setEnabled(true);
            }
            if (!isChecked) {
                onlyInDMSwitch.setChecked(false);
                onlyInDMSwitch.setEnabled(false);
            }
            updateExtremeSwitchEnabled.run();
        });

        // Parent-child logic for Reels
        disableReelsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            onlyInDMSwitch.setEnabled(isChecked);
            if (!isChecked) {
                onlyInDMSwitch.setChecked(false);
                onlyInDMSwitch.setEnabled(false);
            }
            updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
            updateExtremeSwitchEnabled.run();
            SettingsManager.saveAllFlags();
        });

        // Child logic for "Except in DMs"
        onlyInDMSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !disableReelsSwitch.isChecked()) {
                disableReelsSwitch.setChecked(true);
            }
            updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
            updateExtremeSwitchEnabled.run();
            SettingsManager.saveAllFlags();
        });

        // All other switches
        for (ToggleRow s : new ToggleRow[]{disableStoriesSwitch, disableFeedSwitch, disableExploreSwitch, disableCommentsSwitch}) {
            s.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
                updateExtremeSwitchEnabled.run();
                SettingsManager.saveAllFlags();
            });
        }

        // Init "Except in DMs" state
        onlyInDMSwitch.setEnabled(disableReelsSwitch.isChecked());

        // Layout building
        layout.addView(extremeModeSwitch);
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (ToggleRow s :switches) {
            layout.addView(s);
        }

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_distraction_free), layout, () -> {
            FeatureFlags.disableStories = disableStoriesSwitch.isChecked();
            FeatureFlags.disableFeed = disableFeedSwitch.isChecked();
            FeatureFlags.disableReels = disableReelsSwitch.isChecked();
            FeatureFlags.disableReelsExceptDM = onlyInDMSwitch.isChecked();
            FeatureFlags.disableExplore = disableExploreSwitch.isChecked();
            FeatureFlags.disableComments = disableCommentsSwitch.isChecked();
        });

        SettingsManager.saveAllFlags();
    }

    private static void disableAllSwitches(ToggleRow[] switches, ToggleRow master, ToggleRow onlyInDMSwitch) {
        for (ToggleRow s : switches) {
            if (s == onlyInDMSwitch) {
                s.setEnabled(s.isChecked());
            } else {
                s.setEnabled(!s.isChecked());
            }
        }
        master.setEnabled(false);
    }

    private static void updateMasterSwitch(ToggleRow enableAllRow, ToggleRow[] switches, ToggleRow disableReelsSwitch, ToggleRow onlyInDMSwitch) {
        enableAllRow.setOnCheckedChangeListener(null);
        enableAllRow.setChecked(areAllEnabled(switches));
        enableAllRow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s : switches) {
                s.setChecked(isChecked);
            }
            onlyInDMSwitch.setEnabled(disableReelsSwitch.isChecked());
        });
    }


    private static void showMiscOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create all child switches
        ToggleRow[] switches = new ToggleRow[]{
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_disable_story_autoswipe), FeatureFlags.disableStoryFlipping),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_disable_video_autoplay),  FeatureFlags.disableVideoAutoPlay),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_disable_repost),          FeatureFlags.disableRepost),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_show_feature_toasts),     FeatureFlags.showFeatureToasts),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_show_follower_toast),     FeatureFlags.showFollowerToast),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_view_story_mentions),     FeatureFlags.enableStoryMentions),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_disable_discover_people), FeatureFlags.disableDiscoverPeople),
                createSwitch(context, I18n.t(context, R.string.ig_dialog_misc_copy_comment),            FeatureFlags.enableCopyComment)
        };

        // Create Enable/Disable All switch
        ToggleRow enableAllSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(switches));

        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s :switches) {
                s.setChecked(isChecked);
            }
        });

        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (ToggleRow s2 :switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Update FeatureFlags
                switch (index) {
                    case 0:
                        FeatureFlags.disableStoryFlipping = isChecked;
                        break;
                    case 1:
                        FeatureFlags.disableVideoAutoPlay = isChecked;
                        break;
                    case 2:
                        FeatureFlags.disableRepost = isChecked;
                        break;
                    case 3:
                        FeatureFlags.showFeatureToasts = isChecked;
                        break;
                    case 4:
                        FeatureFlags.showFollowerToast = isChecked;
                        break;
                    case 5:
                        FeatureFlags.enableStoryMentions = isChecked;
                        break;
                    case 6:
                        FeatureFlags.disableDiscoverPeople = isChecked;
                        break;
                    case 7:
                        FeatureFlags.enableCopyComment = isChecked;
                        break;
                }

                SettingsManager.saveAllFlags();
            });
        }

        // Add views to layout
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (ToggleRow s :switches) {
            layout.addView(s);
        }

        // Show dialog
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_misc), layout, () -> {
        });
    }


    private static void showDownloaderOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_downloader_settings), () -> showDownloaderSettings(context)));

        ToggleRow postSwitch    = createSwitch(context, I18n.t(context, R.string.ig_dialog_downloader_posts),    FeatureFlags.enablePostDownload);
        ToggleRow storySwitch   = createSwitch(context, I18n.t(context, R.string.ig_dialog_downloader_stories),  FeatureFlags.enableStoryDownload);
        ToggleRow reelSwitch    = createSwitch(context, I18n.t(context, R.string.ig_dialog_downloader_reels),    FeatureFlags.enableReelDownload);
        ToggleRow profileSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_downloader_profiles), FeatureFlags.enableProfileDownload);

        ToggleRow[] switches = new ToggleRow[]{postSwitch, storySwitch, reelSwitch, profileSwitch};

        ToggleRow enableAllSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(switches));

        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (ToggleRow s :switches) {
                s.setChecked(isChecked);
            }
        });

        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (ToggleRow s2 :switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                if (index == 0) FeatureFlags.enablePostDownload    = isChecked;
                if (index == 1) FeatureFlags.enableStoryDownload   = isChecked;
                if (index == 2) FeatureFlags.enableReelDownload    = isChecked;
                if (index == 3) FeatureFlags.enableProfileDownload = isChecked;

                SettingsManager.saveAllFlags();
            });
        }

        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (ToggleRow s :switches) {
            layout.addView(s);
        }

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_downloader), layout, () -> {});
    }

    private static void showDownloaderSettings(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(createClickableSection(context, I18n.t(context, R.string.ig_dialog_downloader_folder), () -> showFolderPickerDialog(context)));

        ToggleRow usernameFolderSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_downloader_username_subfolder), FeatureFlags.downloaderUsernameFolder);
        ToggleRow timestampSwitch = createSwitch(context, I18n.t(context, R.string.ig_dialog_downloader_add_timestamp), FeatureFlags.downloaderAddTimestamp);

        usernameFolderSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.downloaderUsernameFolder = isChecked;
            SettingsManager.saveAllFlags();
        });
        timestampSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.downloaderAddTimestamp = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(createDivider(context));
        layout.addView(usernameFolderSwitch);
        layout.addView(timestampSwitch);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_downloader_settings), layout, () -> {});
    }

    private static void showFolderPickerDialog(Context context) {
        Activity activity = unwrapActivity(context);
        if (activity != null) {
            UIHookManager.launchFolderPicker(activity);
        } else {
            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker), Toast.LENGTH_SHORT).show();
        }
    }

    private static Activity unwrapActivity(Context context) {
        while (context instanceof android.content.ContextWrapper wrapper) {
            if (context instanceof Activity a) return a;
            context = wrapper.getBaseContext();
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private static void showBackupRestoreOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(createActionRow(context, "💾", I18n.t(context, R.string.ig_dialog_backup_settings), "#30D158", v -> {
            try {
                String json = ps.reso.instaeclipse.utils.backup.SettingsBackupManager.toJson();
                Activity instagramActivity = UIHookManager.getCurrentActivity();
                if (instagramActivity != null && !instagramActivity.isFinishing()) {
                    Intent exportIntent = new Intent();
                    exportIntent.setComponent(new ComponentName("ps.reso.instaeclipse",
                            "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                    exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    exportIntent.putExtra("json_content", json);
                    exportIntent.putExtra("file_name", "instaeclipse_settings.json");
                    instagramActivity.startActivity(exportIntent);
                }
            } catch (Exception e) {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_backup_failed, e.getMessage()));
            }
        }));

        layout.addView(createActionRow(context, "📂", I18n.t(context, R.string.ig_dialog_restore_settings), "#0A84FF", v -> {
            Activity instagramActivity = UIHookManager.getCurrentActivity();
            if (instagramActivity != null && !instagramActivity.isFinishing()) {
                Intent importIntent = new Intent();
                importIntent.setComponent(new ComponentName("ps.reso.instaeclipse",
                        "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
                importIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                importIntent.putExtra("target_package", context.getPackageName());
                importIntent.putExtra("broadcast_action", "ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS");
                instagramActivity.startActivity(importIntent);
            } else {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
            }
        }));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_backup_restore), layout, () -> {});
    }

    private static void showAboutDialog(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 24, 40, 16);

        TextView title = new TextView(context);
        title.setText(I18n.t(context, R.string.ig_dialog_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);

        TextView creator = new TextView(context);
        creator.setText(I18n.t(context, R.string.ig_dialog_about_created_by));
        creator.setTextColor(Color.parseColor("#8E8E93"));
        creator.setTextSize(14f);
        creator.setGravity(Gravity.CENTER);
        creator.setPadding(0, 0, 0, 32);

        layout.addView(title);
        layout.addView(creator);
        LinearLayout linksRow = new LinearLayout(context);
        linksRow.setOrientation(LinearLayout.HORIZONTAL);
        linksRow.setGravity(Gravity.CENTER);

        View githubBtn = createActionRow(context, "🌐", I18n.t(context, R.string.ig_dialog_about_github), "#0A84FF", v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ReSo7200/InstaEclipse"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        githubBtn.setLayoutParams(btnLp);

        View tgBtn = createActionRow(context, "✈️", I18n.t(context, R.string.ig_dialog_about_telegram), "#29B6F6", v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/InstaEclipse"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        });
        tgBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        linksRow.addView(githubBtn);
        linksRow.addView(tgBtn);
        layout.addView(linksRow);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_about), layout, () -> {
        });
    }

    @SuppressLint("SetTextI18n")
    private static void showRestartSection(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView message = new TextView(context);
        message.setText(I18n.t(context, R.string.ig_dialog_restart_message));
        message.setTextColor(Color.WHITE);
        message.setTextSize(18f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, 30);

        layout.addView(message);
        layout.addView(createActionRow(context, "🔁", I18n.t(context, R.string.ig_dialog_restart_now), "#FF453A", v -> restartApp(context)));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_restart), layout, () -> {
        });
    }


    // ==== HELPERS ====

    @SuppressLint("SetTextI18n")
    private static void showSectionDialog(Context context, String title, LinearLayout contentLayout, Runnable onSave) {
        if (currentDialog != null) { try { currentDialog.dismiss(); } catch (Exception ignored) {} currentDialog = null; }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, 0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#1C1C1E"));
        background.setCornerRadii(new float[]{40, 40, 40, 40, 0, 0, 0, 0});
        container.setBackground(background);

        container.addView(createDragHandle(context));

        // Header row: back arrow + title
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(24, 4, 24, 16);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = new TextView(context);
        backBtn.setText("‹");
        backBtn.setTextColor(Color.parseColor("#0A84FF"));
        backBtn.setTextSize(36);
        backBtn.setIncludeFontPadding(false);
        backBtn.setGravity(Gravity.CENTER_VERTICAL);
        backBtn.setPadding(4, 0, 32, 4);
        backBtn.setMinWidth(0);
        backBtn.setMinimumWidth(0);
        StateListDrawable backBtnBg = new StateListDrawable();
        backBtnBg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#20FFFFFF")));
        backBtnBg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        backBtn.setBackground(backBtnBg);
        backBtn.setClickable(true);
        backBtn.setFocusable(true);
        backBtn.setOnClickListener(v -> {
            onSave.run();
            SettingsManager.saveAllFlags();
            showEclipseOptionsDialog(context);
        });

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(null, Typeface.BOLD);

        header.addView(backBtn);
        header.addView(titleView);
        container.addView(header);
        container.addView(createDivider(context));

        // Content with horizontal padding
        LinearLayout contentWrapper = new LinearLayout(context);
        contentWrapper.setOrientation(LinearLayout.VERTICAL);
        contentWrapper.setPadding(24, 0, 24, 0);
        contentWrapper.addView(contentLayout);
        container.addView(contentWrapper);

        container.addView(createDivider(context));

        // Bottom padding for nav bar
        View bottomPad = new View(context);
        bottomPad.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48));
        container.addView(bottomPad);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);

        currentDialog = createBottomSheetDialog(context, scrollView);
        currentDialog.show();
    }


    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private static class ToggleRow extends LinearLayout {
        private final Switch toggle;

        ToggleRow(Context context, String label, boolean checked) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(8, 4, 8, 4);
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);

            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#2C2C2E")));
            bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            setBackground(bg);

            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(Color.WHITE);
            labelView.setTextSize(16);
            labelView.setPadding(0, 20, 16, 20);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            labelView.setLayoutParams(lp);

            toggle = new Switch(context);
            toggle.setChecked(checked);
            toggle.setThumbTintList(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.parseColor("#555555"), Color.parseColor("#448AFF"), Color.parseColor("#FFFFFF")}));
            toggle.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.parseColor("#777777"), Color.parseColor("#1C4C78"), Color.parseColor("#CFD8DC")}));
            toggle.setClickable(false);
            toggle.setFocusable(false);

            addView(labelView);
            addView(toggle);
            setOnClickListener(v -> { if (isEnabled()) toggle.setChecked(!toggle.isChecked()); });
        }

        boolean isChecked() { return toggle.isChecked(); }
        void setChecked(boolean checked) { toggle.setChecked(checked); }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            toggle.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.38f);
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener l) {
            toggle.setOnCheckedChangeListener(l);
        }

        void makeBold() {
            ((TextView) getChildAt(0)).setTypeface(null, Typeface.BOLD);
            ((TextView) getChildAt(0)).setTextSize(17);
        }
    }

    private static ToggleRow createSwitch(Context context, String label, boolean defaultState) {
        return new ToggleRow(context, label, defaultState);
    }

    private static LinearLayout createSwitchLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 8, 16, 8);
        return layout;
    }

    private static View createClickableSection(Context context, String label, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(40, 24, 32, 24);
        row.setGravity(Gravity.CENTER_VERTICAL);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#2C2C2E")));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(states);
        row.setClickable(true);
        row.setFocusable(true);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(17);
        labelView.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(lp);

        TextView chevron = new TextView(context);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(Color.parseColor("#8E8E93"));
        chevron.setPadding(8, 0, 0, 0);

        row.addView(labelView);
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static View createActionRow(Context context, String emoji, String label, String accentHex, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(40, 22, 32, 22);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#2C2C2E")));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);

        // Colored icon badge
        TextView iconView = new TextView(context);
        iconView.setText(emoji);
        iconView.setTextSize(18);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(Color.parseColor(accentHex + "33")); // 20% opacity tint
        iconBg.setCornerRadius(14);
        iconView.setBackground(iconBg);
        iconView.setPadding(14, 10, 14, 10);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.rightMargin = 24;
        iconView.setLayoutParams(iconLp);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(Color.parseColor(accentHex));
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(iconView);
        row.addView(labelView);
        row.setOnClickListener(onClick);
        return row;
    }

    private static View createDragHandle(Context context) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
        wrapper.setPadding(0, 14, 0, 8);

        View handle = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(120, 6);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(lp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(Color.parseColor("#48484A"));
        handleBg.setCornerRadius(3);
        handle.setBackground(handleBg);

        wrapper.addView(handle);
        return wrapper;
    }

    private static AlertDialog createBottomSheetDialog(Context context, View contentView) {
        AlertDialog dialog = new AlertDialog.Builder(context).setView(contentView).setCancelable(true).create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
        }
        return dialog;
    }


    private static LinearLayout createEnableAllSwitch(Context context, ToggleRow enableAllRow) {
        enableAllRow.makeBold();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 4, 8, 4);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#2C2C2E"));
        background.setCornerRadius(16);
        container.setBackground(background);

        container.addView(enableAllRow);
        return container;
    }

    private static boolean areAllEnabled(ToggleRow[] rows) {
        for (ToggleRow r : rows) {
            if (!r.isChecked()) return false;
        }
        return true;
    }

}
