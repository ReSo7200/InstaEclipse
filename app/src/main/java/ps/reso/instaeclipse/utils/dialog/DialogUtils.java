package ps.reso.instaeclipse.utils.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.location.LocationPickerActivity;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.ModuleActivityLauncher;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class DialogUtils {

    private static AlertDialog currentDialog;

    // ==== 0.7 DESIGN TOKENS ====
    // One palette drives every dialog surface so the in-IG UI reads as a single, coherent sheet.
    // Colors are ARGB ints (used directly with setColor/setTextColor — no parseColor round-trip).
    private static final int C_SHEET    = 0xFF17171B; // bottom-sheet background (deep near-black)
    private static final int C_CARD     = 0xFF202027; // grouped card fill (one step lighter)
    private static final int C_PRESSED  = 0xFF2E2E37; // row pressed / highlight
    private static final int C_HAIRLINE = 0xFF2C2C34; // dividers, borders
    private static final int C_TEXT     = 0xFFFFFFFF; // primary text
    private static final int C_TEXT2    = 0xFF9A9AA3; // secondary/muted text
    private static final int C_HANDLE   = 0xFF48484A; // drag handle
    private static final int C_DANGER   = 0xFFFF453A; // destructive accent (close, delete)
    private static final int C_ACCENT   = 0xFF0A84FF; // primary/interactive accent (back, links)

    /** Section accent palette — assigned per thematic group so each menu section has its own hue. */
    private static final String A_APPEARANCE = "#FF375F"; // vivid pink/red
    private static final String A_PRIVACY    = "#5E5CE6"; // indigo
    private static final String A_MEDIA      = "#FF9F0A"; // amber
    private static final String A_TOOLS      = "#8E8E93"; // neutral gray

    /** Card corner radius (px) — the rounded "grouped list" idiom. */
    private static final int R_CARD = 26;

    @SuppressLint("UseCompatLoadingForDrawables")
    public static void showEclipseOptionsDialog(Context context) {
        SettingsManager.init(context);

        LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable background = new GradientDrawable();
        background.setColor(C_SHEET);
        background.setCornerRadii(new float[]{48, 48, 48, 48, 0, 0, 0, 0});
        outer.setBackground(background);

        // Pinned header: drag handle + title + subtitle (stays visible while the list scrolls)
        outer.addView(createDragHandle(context));
        TextView title = new TextView(context);
        title.setText(I18n.t(context, R.string.ig_dialog_title));
        title.setTextColor(C_TEXT);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.01f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(40, 10, 40, 2);
        outer.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText(I18n.t(context, R.string.ig_dialog_subtitle));
        subtitle.setTextColor(C_TEXT2);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(40, 0, 40, 18);
        outer.addView(subtitle);
        outer.addView(createDivider(context));

        // Scrollable middle: the feature category list + footer credit
        LinearLayout mainLayout = buildMainMenuLayout(context);
        ScrollView scrollView = createScrollableContainer(context, mainLayout, 0.62f);
        outer.addView(scrollView);

        // Pinned footer: Close button (always reachable without scrolling)
        TextView closeButton = new TextView(context);
        closeButton.setText(I18n.t(context, R.string.ig_dialog_close));
        closeButton.setTextColor(C_DANGER);
        closeButton.setTextSize(16);
        closeButton.setPadding(40, 20, 40, 40);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setTypeface(null, Typeface.BOLD);
        StateListDrawable closeStates = new StateListDrawable();
        closeStates.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable((C_DANGER & 0x00FFFFFF) | 0x20000000));
        closeStates.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        closeButton.setBackground(closeStates);
        closeButton.setOnClickListener(v -> {
            if (currentDialog != null) { try { currentDialog.dismiss(); } catch (Exception ignored) {} currentDialog = null; }
        });
        outer.addView(closeButton);

        SettingsManager.saveAllFlags();

        Activity activity = UIHookManager.getCurrentActivity();
        if (activity != null) {
            GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
        }

        if (currentDialog != null && currentDialog.isShowing()) {
            try { currentDialog.dismiss(); } catch (Exception ignored) {}
        }
        currentDialog = null;

        currentDialog = createBottomSheetDialog(context, outer);
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
        mainLayout.setPadding(24, 0, 24, 0);

        // ---- APPEARANCE ---- look & feel: theme, fonts/emoji (inside theme), quality, feed cleanup
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_appearance)));
        LinearLayout appearance = createGroupCard(context);
        appearance.addView(createMenuRow(context, R.drawable.ic_palette, I18n.t(context, R.string.ig_dialog_menu_theme), A_APPEARANCE, () -> showThemeOptions(context)));
        appearance.addView(createMenuRow(context, R.drawable.ic_movie, I18n.t(context, R.string.ig_dialog_menu_quality), A_APPEARANCE, () -> showQualityOptions(context)));
        appearance.addView(createMenuRow(context, R.drawable.ic_sparkle, I18n.t(context, R.string.ig_dialog_menu_clean_feed), A_APPEARANCE, () -> showCleanFeedOptions(context)));
        mainLayout.addView(appearance);

        // ---- PRIVACY ---- ghost, lock, hidden chats, ad/analytics blocking, distraction-free
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_privacy)));
        LinearLayout privacy = createGroupCard(context);
        privacy.addView(createMenuRow(context, R.drawable.ic_eye, I18n.t(context, R.string.ig_dialog_menu_ghost_settings), A_PRIVACY, () -> showGhostOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_shield, I18n.t(context, R.string.ig_dialog_misc_lock_section), A_PRIVACY, () -> showLockOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_eye_off, I18n.t(context, R.string.ig_hide_chats_title), A_PRIVACY, () -> showHideChatsOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_block, I18n.t(context, R.string.ig_dialog_menu_ad_analytics), A_PRIVACY, () -> showAdOptions(context)));
        privacy.addView(createMenuRow(context, R.drawable.ic_notification, I18n.t(context, R.string.ig_dialog_menu_distraction_free), A_PRIVACY, () -> showDistractionOptions(context)));
        mainLayout.addView(privacy);

        // ---- MEDIA ---- downloading, location spoofing
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_media)));
        LinearLayout media = createGroupCard(context);
        media.addView(createMenuRow(context, R.drawable.ic_download, I18n.t(context, R.string.ig_dialog_menu_downloader), A_MEDIA, () -> showDownloaderOptions(context)));
        media.addView(createMenuRow(context, R.drawable.ic_pin, I18n.t(context, R.string.ig_dialog_menu_location), A_MEDIA, () -> showLocationOptions(context)));
        mainLayout.addView(media);

        // ---- TOOLS ---- misc toggles, developer, backup, restart, cache, about
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_tools)));
        LinearLayout tools = createGroupCard(context);
        tools.addView(createMenuRow(context, R.drawable.ic_settings_gear, I18n.t(context, R.string.ig_dialog_menu_misc), A_TOOLS, () -> showMiscOptions(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_tune, I18n.t(context, R.string.ig_dialog_menu_dev_options), A_TOOLS, () -> showDevOptions(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_save, I18n.t(context, R.string.ig_dialog_menu_backup_restore), A_TOOLS, () -> showBackupRestoreOptions(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_restart, I18n.t(context, R.string.ig_dialog_menu_restart), A_TOOLS, () -> showRestartSection(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_dialog_clear_cache), A_TOOLS, () -> showClearCacheSection(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_timer, I18n.t(context, R.string.ig_dialog_section_auto_clear_cache), A_TOOLS, () -> showAutoClearCacheSection(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_info, I18n.t(context, R.string.ig_dialog_menu_about), A_TOOLS, () -> showAboutDialog(context)));
        mainLayout.addView(tools);

        // Footer Credit
        TextView footer = new TextView(context);
        footer.setText("@reso7200");
        footer.setTextColor(C_TEXT2);
        footer.setTextSize(13);
        footer.setPadding(16, 26, 16, 8);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        mainLayout.addView(footer);

        return mainLayout;
    }

    /** Settings-app style group header: uppercase, letter-spaced, muted. */
    private static TextView sectionHeader(Context context, String text) {
        TextView header = new TextView(context);
        header.setText(text == null ? "" : text.toUpperCase(java.util.Locale.getDefault()));
        header.setTextColor(C_TEXT2);
        header.setTextSize(12);
        header.setTypeface(null, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(20, 22, 16, 10);
        return header;
    }

    private static LinearLayout createGroupCard(Context context) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable groupBg = new GradientDrawable();
        groupBg.setColor(C_CARD);
        groupBg.setCornerRadius(R_CARD);
        group.setBackground(groupBg);
        group.setPadding(6, 6, 6, 6);
        return group;
    }

    /** labelWithEmoji carries an emoji since it's shared with the companion app's plain-text
     *  menu (which still wants it) — but here a real vector icon renders in the chip instead,
     *  so the emoji is stripped. Translators place it on either side of the text (leading in
     *  most locales, trailing in Arabic), so this strips from whichever end it's on rather than
     *  assuming a fixed position. */
    private static View createMenuRow(Context context, int iconRes, String labelWithEmoji, String accentHex, Runnable onClick) {
        String label = stripEdgeEmoji(labelWithEmoji);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 9), dp(context, 10), dp(context, 9));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowRipple(context, 16));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(C_TEXT);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView chevron = new android.widget.ImageView(context);
        Drawable chev = loadModuleIcon(R.drawable.ic_chevron_right, C_TEXT2);
        if (chev != null) chevron.setImageDrawable(chev);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20));
        chevLp.rightMargin = dp(context, 4);
        chevron.setLayoutParams(chevLp);

        row.addView(buildIconChip(context, iconRes, accentHex));
        row.addView(labelView);
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static GradientDrawable roundedColor(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp);
        return d;
    }

    // Matches a run of emoji-ish codepoints (Unicode "Symbol, Other"/"Symbol, Modifier" plus
    // variation selectors and ZWJ) anchored to either end of the string, with any adjoining
    // whitespace. \p{So} covers the vast majority of emoji; the rest are combining marks used
    // alongside them (skin tone modifiers, VS16, ZWJ for multi-part emoji).
    private static final java.util.regex.Pattern LEADING_EMOJI =
            java.util.regex.Pattern.compile("^[\\p{So}\\p{Sk}\\u200D\\uFE0F]+\\s*");
    private static final java.util.regex.Pattern TRAILING_EMOJI =
            java.util.regex.Pattern.compile("\\s*[\\p{So}\\p{Sk}\\u200D\\uFE0F]+$");

    private static String stripEdgeEmoji(String text) {
        String stripped = LEADING_EMOJI.matcher(text).replaceFirst("");
        stripped = TRAILING_EMOJI.matcher(stripped).replaceFirst("");
        return stripped.trim();
    }


    private static void showGhostQuickToggleOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create switches for customizing what gets toggled
        ToggleRow[] toggleSwitches = new ToggleRow[]{
                createSwitch(context, R.drawable.ic_eye_off, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_hide_seen),           FeatureFlags.quickToggleSeen),
                createSwitch(context, R.drawable.ic_chat, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_hide_typing),         FeatureFlags.quickToggleTyping),
                createSwitch(context, R.drawable.ic_camera, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_disable_screenshot),  FeatureFlags.quickToggleScreenshot),
                createSwitch(context, R.drawable.ic_eye_off, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_hide_view_once),      FeatureFlags.quickToggleViewOnce),
                createSwitch(context, R.drawable.ic_story_ring, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_hide_story_seen),     FeatureFlags.quickToggleStory),
                createSwitch(context, R.drawable.ic_live, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_hide_live_seen),      FeatureFlags.quickToggleLive),
                createSwitch(context, R.drawable.ic_timer, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_keep_ephemeral),      FeatureFlags.quickToggleEphemeral),
                createSwitch(context, R.drawable.ic_eye, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_permanent_view),      FeatureFlags.quickTogglePermanentView),
                createSwitch(context, R.drawable.ic_camera, "#5E5CE6", I18n.t(context, R.string.ig_dialog_quick_allow_screenshots),   FeatureFlags.quickToggleAllowScreenshots)};

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
                        FeatureFlags.quickTogglePermanentView = isChecked;
                        break;
                    case 8:
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
        // Low-key spacer rather than a hard hairline — the grouped cards already delimit content,
        // so loose full-width rules between them looked dated. Kept as a small transparent gap.
        View divider = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 8));
        divider.setLayoutParams(params);
        divider.setBackgroundColor(Color.TRANSPARENT);
        return divider;
    }

    /** Thin inset hairline for separating rows *inside* a grouped card (e.g. radio lists). */
    private static View createHairline(Context context) {
        View line = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dp(context, 14), 0, dp(context, 14), 0);
        line.setLayoutParams(params);
        line.setBackgroundColor(C_HAIRLINE);
        return line;
    }

    /** Rounded ripple for any tappable row; falls back to a pressed-state drawable if unavailable. */
    private static Drawable rowRipple(Context ctx, int cornerPx) {
        try {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(cornerPx);
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(C_PRESSED), null, mask);
        } catch (Throwable t) {
            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, roundedColor(C_PRESSED, cornerPx));
            sld.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            return sld;
        }
    }

    /** One rounded "card" container idiom (matches the main menu) for grouping section rows. */
    private static LinearLayout card(Context ctx) {
        LinearLayout group = new LinearLayout(ctx);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(R_CARD);
        group.setBackground(bg);
        group.setPadding(6, 6, 6, 6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        group.setLayoutParams(lp);
        return group;
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
            ModuleLog.line("InstaEclipse: Restart failed for " + packageName + " - " + e.getMessage());
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
                ModuleLog.line("InstaEclipse: Cache cleared for " + context.getPackageName());
            } else {
                ModuleLog.line("InstaEclipse: Cache directory not found for " + context.getPackageName());
            }
        } catch (Exception e) {
            ModuleLog.line("InstaEclipse: Failed to clear cache for " + context.getPackageName() + " - " + e.getMessage());
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
        ToggleRow devModeSwitch = createSwitch(context, R.drawable.ic_tune, "#0A84FF", I18n.t(context, R.string.ig_dialog_dev_enable), FeatureFlags.isDevEnabled);
        devModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.isDevEnabled = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(devModeSwitch);
        layout.addView(createDivider(context));

        layout.addView(createActionRow(context, R.drawable.ic_download, I18n.t(context, R.string.ig_dialog_dev_import), "#30D158", v -> {
            Activity instagramActivity = UIHookManager.getCurrentActivity();
            if (instagramActivity != null && !instagramActivity.isFinishing()) {
                Intent importIntent = new Intent();
                importIntent.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
                importIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                importIntent.putExtra("target_package", context.getPackageName());
                try {
                    instagramActivity.startActivity(importIntent);
                } catch (Exception e) {
                    ModuleLog.line("InstaEclipse | ❌ Failed to start JsonImportActivity: " + e.getMessage());
                    showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_unable_open_ui));
                }
            } else {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
            }
        }));

        layout.addView(createActionRow(context, R.drawable.ic_upload, I18n.t(context, R.string.ig_dialog_dev_export), "#0A84FF", v -> {
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

        layout.addView(createActionRow(context, R.drawable.ic_restart, I18n.t(context, R.string.ig_dialog_dev_restore_default_config), "#FF9F0A", v -> {
            new AlertDialog.Builder(context)
                    .setTitle(I18n.t(context, R.string.ig_dialog_dev_restore_default_config))
                    .setMessage(I18n.t(context, R.string.ig_dialog_dev_restore_default_config_confirm))
                    .setPositiveButton(I18n.t(context, R.string.ig_dialog_yes), (dialog, which) ->
                            ConfigManager.restoreDefaultConfig(context, Module.moduleSourceDir))
                    .setNegativeButton(I18n.t(context, R.string.ig_dialog_cancel), null)
                    .show();
        }));

        layout.addView(createDivider(context));

        ToggleRow buildExpiredSwitch = createSwitch(context, R.drawable.ic_block, "#FF453A", I18n.t(context, R.string.ig_dialog_dev_remove_build_expired), FeatureFlags.removeBuildExpiredPopup);
        buildExpiredSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.removeBuildExpiredPopup = isChecked;
            SettingsManager.saveAllFlags();
        });
        layout.addView(buildExpiredSwitch);

        // Save current dev mode flag when dialog is closed
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_dev_options), layout, SettingsManager::saveAllFlags);
    }

    /** Viewer for the persistent Unsent Messages log (captured by KeepUnsentMessagesHook). */
    public static void showThreadUnsent(Context context, String threadId) { showThreadUnsent(context, threadId, null); }

    /** Styled per-thread Unsent Messages viewer, opened from the in-thread bin button. */
    public static void showThreadUnsent(Context context, String threadId, String chatName) {
        LinearLayout layout = createSwitchLayout(context);

        final java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries =
                (threadId != null) ? ps.reso.instaeclipse.utils.ghost.UnsentLog.getForThread(threadId)
                                   : ps.reso.instaeclipse.utils.ghost.UnsentLog.getAll();

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault());
        final String who = (chatName != null && !chatName.trim().isEmpty()) ? chatName.trim()
                : (threadId != null ? threadId : "unknown");

        if (!entries.isEmpty()) {
            layout.addView(createActionRow(context, R.drawable.ic_upload, I18n.t(context, R.string.ig_dialog_unsent_export), "#0A84FF", v -> {
                String path = exportUnsentJson(context, who, entries);
                Toast.makeText(context, path != null
                        ? I18n.t(context, R.string.ig_dialog_unsent_exported, path)
                        : I18n.t(context, R.string.ig_toast_download_failed, "export"), Toast.LENGTH_LONG).show();
            }));
            layout.addView(createActionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_dialog_unsent_clear), "#FF453A", v -> {
                try {
                    Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                    new AlertDialog.Builder(themed)
                            .setTitle(I18n.t(context, R.string.ig_dialog_unsent_clear))
                            .setMessage(I18n.t(context, R.string.ig_dialog_unsent_clear_confirm))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(I18n.t(context, R.string.ig_dialog_unsent_clear_yes), (d, w) -> {
                                ps.reso.instaeclipse.utils.ghost.UnsentLog.clearThread(threadId);
                                Toast.makeText(context, I18n.t(context, R.string.ig_dialog_unsent_cleared), Toast.LENGTH_SHORT).show();
                                showThreadUnsent(context, threadId);
                            })
                            .show();
                } catch (Throwable ignored) {}
            }));
            layout.addView(createDivider(context));
        }

        if (entries.isEmpty()) {
            layout.addView(createInfoSection(context, I18n.t(context, R.string.ig_dialog_unsent_title),
                    I18n.t(context, R.string.ig_dialog_unsent_empty)));
        } else {
            LinearLayout msgCard = card(context);
            for (int i = 0; i < entries.size(); i++) {
                ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e = entries.get(i);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(context, 14);
                row.setPadding(pad, dp(context, 12), pad, dp(context, 12));
                TextView meta = new TextView(context);
                meta.setText(fmt.format(new java.util.Date(e.time)) + (e.sender.isEmpty() ? "" : " · " + e.sender));
                meta.setTextColor(C_TEXT2);
                meta.setTextSize(12);
                TextView body = new TextView(context);
                body.setText(e.text);
                body.setTextColor(C_TEXT);
                body.setTextSize(15);
                body.setPadding(0, dp(context, 4), 0, 0);
                body.setTextIsSelectable(true);
                row.addView(meta);
                row.addView(body);
                msgCard.addView(row);
                if (i < entries.size() - 1) msgCard.addView(createHairline(context));
            }
            layout.addView(msgCard);
        }

        showSectionDialog(context, who != null && !who.isEmpty() ? who : I18n.t(context, R.string.ig_dialog_unsent_title), layout, () -> {});
    }

    /** Write the thread's unsent messages as JSON into Download/InstaEclipse/<chat>/ ; returns a
     *  display path, or null on failure. Uses MediaStore on API 29+, direct file otherwise. */
    private static String exportUnsentJson(Context context, String chatName,
                                           java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries) {
        try {
            String safe = chatName.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safe.length() > 40) safe = safe.substring(0, 40);
            String fileName = "unsent_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date()) + ".json";

            org.json.JSONObject root = new org.json.JSONObject();
            root.put("chat", chatName);
            root.put("exported_at", System.currentTimeMillis());
            org.json.JSONArray arr = new org.json.JSONArray();
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            for (ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e : entries) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("time", e.time);
                o.put("time_iso", iso.format(new java.util.Date(e.time)));
                if (e.sender != null && !e.sender.isEmpty()) o.put("sender", e.sender);
                o.put("text", e.text);
                arr.put(o);
            }
            root.put("messages", arr);
            byte[] data = root.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String relDir = "Download/InstaEclipse/" + safe;

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relDir);
                android.net.Uri uri = context.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (java.io.OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) { os.write(data); os.flush(); }
                    }
                    return relDir + "/" + fileName;
                }
            }
            // Fallback: direct file into public Downloads.
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "InstaEclipse/" + safe);
            if (dir.exists() || dir.mkdirs()) {
                java.io.File out = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) { fos.write(data); }
                return out.getAbsolutePath();
            }
            return null;
        } catch (Throwable t) {
            ModuleLog.line("(IE|UnsentExport) ❌ " + t.getMessage());
            return null;
        }
    }

    private static void showUnsentMessages(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries =
                ps.reso.instaeclipse.utils.ghost.UnsentLog.getAll();

        if (entries.isEmpty()) {
            layout.addView(createInfoSection(context, I18n.t(context, R.string.ig_dialog_ghost_keep_unsent),
                    I18n.t(context, R.string.ig_dialog_unsent_empty)));
            showSectionDialog(context, I18n.t(context, R.string.ig_dialog_unsent_title), layout, () -> {});
            return;
        }

        // Folder drill-down (like the story cache): one folder per chat/thread. Entries come
        // newest-first, so the first non-empty sender seen for a thread is its most recent label.
        java.util.LinkedHashMap<String, java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry>> byThread =
                new java.util.LinkedHashMap<>();
        java.util.HashMap<String, String> nameFor = new java.util.HashMap<>();
        for (ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e : entries) {
            String key = (e.thread == null || e.thread.isEmpty()) ? "" : e.thread;
            byThread.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
            if (!nameFor.containsKey(key) && e.sender != null && !e.sender.trim().isEmpty()) {
                nameFor.put(key, e.sender.trim());
            }
        }

        LinearLayout foldersCard = card(context);
        for (java.util.Map.Entry<String, java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry>> t : byThread.entrySet()) {
            final String threadId = t.getKey();
            final int count = t.getValue().size();
            // Prefer the live resolved thread name (updated from the header on every thread open) over
            // the per-entry sender captured at log time — the latter can be stale/wrong (e.g. a token
            // grabbed before the name resolved), and the live map is always the most current label.
            String name = ps.reso.instaeclipse.utils.ghost.ThreadNames.get(threadId);
            if (name == null || name.isEmpty()) {
                name = nameFor.get(threadId); // stored per-entry sender fallback
            }
            if (name == null || name.isEmpty()) {
                name = threadId.isEmpty() ? I18n.t(context, R.string.ig_dialog_unsent_unknown_chat) : threadId;
            }
            final String display = name;
            foldersCard.addView(createActionRow(context, R.drawable.ic_folder,
                    display + "   (" + count + ")", "#5E5CE6",
                    v -> showThreadUnsent(context, threadId.isEmpty() ? null : threadId, display)));
        }
        layout.addView(foldersCard);

        layout.addView(createActionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_dialog_unsent_clear), "#FF453A", v -> {
            try {
                Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                new AlertDialog.Builder(themed)
                        .setTitle(I18n.t(context, R.string.ig_dialog_unsent_clear))
                        .setMessage(I18n.t(context, R.string.ig_dialog_unsent_clear_confirm))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(I18n.t(context, R.string.ig_dialog_unsent_clear_yes), (d, w) -> {
                            ps.reso.instaeclipse.utils.ghost.UnsentLog.clear();
                            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_unsent_cleared), Toast.LENGTH_SHORT).show();
                            showUnsentMessages(context); // refresh
                        })
                        .show();
            } catch (Throwable ignored) {}
        }));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_unsent_title), layout, () -> {});
    }

    private static void showGhostOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        ToggleRow[] switches = new ToggleRow[]{
                createSwitch(context, R.drawable.ic_eye_off, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_hide_dm_seen),         FeatureFlags.isGhostSeen),
                createSwitch(context, R.drawable.ic_chat, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_hide_typing),          FeatureFlags.isGhostTyping),
                createSwitch(context, R.drawable.ic_story_ring, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_hide_story_views),     FeatureFlags.isGhostStory),
                createSwitch(context, R.drawable.ic_live, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_hide_live_presence),   FeatureFlags.isGhostLive),
                createSwitch(context, R.drawable.ic_camera, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_allow_screenshots_dms),FeatureFlags.allowScreenshots),
                createSwitch(context, R.drawable.ic_camera, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_bypass_screenshot),    FeatureFlags.isGhostScreenshot),
                createSwitch(context, R.drawable.ic_eye_off, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_hide_view_once),       FeatureFlags.isGhostViewOnce),
                createSwitch(context, R.drawable.ic_eye, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_permanent_view_once),  FeatureFlags.permanentViewMode),
                createSwitch(context, R.drawable.ic_timer, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_keep_disappearing),    FeatureFlags.keepEphemeralMessages),
                createSwitch(context, R.drawable.ic_chat, "#5E5CE6", I18n.t(context, R.string.ig_dialog_ghost_keep_unsent),         FeatureFlags.keepUnsentMessages)};

        layout.addView(createActionRow(context, R.drawable.ic_chat, I18n.t(context, R.string.ig_dialog_unsent_title), "#5E5CE6", v -> showUnsentMessages(context)));
        layout.addView(createActionRow(context, R.drawable.ic_tune, I18n.t(context, R.string.ig_dialog_customize_quick_toggle), "#5E5CE6", v -> showGhostQuickToggleOptions(context)));

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
                        FeatureFlags.isGhostStory = isChecked;
                        break;
                    case 3:
                        FeatureFlags.isGhostLive = isChecked;
                        break;
                    case 4:
                        FeatureFlags.allowScreenshots = isChecked;
                        break;
                    case 5:
                        FeatureFlags.isGhostScreenshot = isChecked;
                        break;
                    case 6:
                        FeatureFlags.isGhostViewOnce = isChecked;
                        break;
                    case 7:
                        FeatureFlags.permanentViewMode = isChecked;
                        break;
                    case 8:
                        FeatureFlags.keepEphemeralMessages = isChecked;
                        break;
                    case 9:
                        FeatureFlags.keepUnsentMessages = isChecked;
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

        LinearLayout groupCard = card(context);
        for (ToggleRow s :switches) {
            groupCard.addView(s);
        }
        layout.addView(groupCard);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_ghost_mode), layout, () -> {
            // No need to set FeatureFlags here anymore because handled instantly
        });
    }


    private static void showAdOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create switches
        ToggleRow adBlock = createSwitch(context, R.drawable.ic_shield, "#FF453A", I18n.t(context, R.string.ig_dialog_ad_block_ads), FeatureFlags.isAdBlockEnabled);

        ToggleRow analytics = createSwitch(context, R.drawable.ic_shield, "#FF453A", I18n.t(context, R.string.ig_dialog_ad_block_analytics), FeatureFlags.isAnalyticsBlocked);

        ToggleRow trackingLinks = createSwitch(context, R.drawable.ic_link, "#FF453A", I18n.t(context, R.string.ig_dialog_ad_disable_tracking), FeatureFlags.disableTrackingLinks);

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

        LinearLayout groupCard = card(context);
        for (ToggleRow s :switches) {
            groupCard.addView(s);
        }
        layout.addView(groupCard);

        // Show the dialog
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_ad_analytics), layout, () -> {
        });
    }


    private static void showDistractionOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Child switches
        ToggleRow extremeModeSwitch = createSwitch(context, R.drawable.ic_block, "#FF453A", I18n.t(context, R.string.ig_dialog_distraction_extreme_mode), FeatureFlags.isExtremeMode);
        ToggleRow disableStoriesSwitch = createSwitch(context, R.drawable.ic_story_ring, "#30D158", I18n.t(context, R.string.ig_dialog_distraction_disable_stories), FeatureFlags.disableStories);
        ToggleRow disableFeedSwitch = createSwitch(context, R.drawable.ic_block, "#30D158", I18n.t(context, R.string.ig_dialog_distraction_disable_feed), FeatureFlags.disableFeed);
        ToggleRow disableReelsSwitch = createSwitch(context, R.drawable.ic_movie, "#30D158", I18n.t(context, R.string.ig_dialog_distraction_disable_reels), FeatureFlags.disableReels);
        ToggleRow onlyInDMSwitch = createSwitch(context, R.drawable.ic_movie, "#30D158", I18n.t(context, R.string.ig_dialog_distraction_disable_reels_except_dm), FeatureFlags.disableReelsExceptDM);
        ToggleRow disableExploreSwitch = createSwitch(context, R.drawable.ic_search, "#30D158", I18n.t(context, R.string.ig_dialog_distraction_disable_explore), FeatureFlags.disableExplore);
        ToggleRow disableCommentsSwitch = createSwitch(context, R.drawable.ic_chat, "#30D158", I18n.t(context, R.string.ig_dialog_distraction_disable_comments), FeatureFlags.disableComments);

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

        // Parent-child logic for Reels. Set the live FeatureFlag immediately (not only on dialog
        // dismiss) so the network interceptor picks up the change on the very next request — no
        // Instagram restart needed to switch the option.
        disableReelsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.disableReels = isChecked;
            onlyInDMSwitch.setEnabled(isChecked);
            if (!isChecked) {
                onlyInDMSwitch.setChecked(false);
                onlyInDMSwitch.setEnabled(false);
                FeatureFlags.disableReelsExceptDM = false;
            }
            updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
            updateExtremeSwitchEnabled.run();
            SettingsManager.saveAllFlags();
        });

        // Child logic for "Except in DMs"
        onlyInDMSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.disableReelsExceptDM = isChecked;
            if (isChecked && !disableReelsSwitch.isChecked()) {
                disableReelsSwitch.setChecked(true);
                FeatureFlags.disableReels = true;
            }
            updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
            updateExtremeSwitchEnabled.run();
            SettingsManager.saveAllFlags();
        });

        // All other switches — set the matching live FeatureFlag immediately too.
        for (ToggleRow s : new ToggleRow[]{disableStoriesSwitch, disableFeedSwitch, disableExploreSwitch, disableCommentsSwitch}) {
            s.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (s == disableStoriesSwitch) FeatureFlags.disableStories = isChecked;
                else if (s == disableFeedSwitch) FeatureFlags.disableFeed = isChecked;
                else if (s == disableExploreSwitch) FeatureFlags.disableExplore = isChecked;
                else if (s == disableCommentsSwitch) FeatureFlags.disableComments = isChecked;
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

        LinearLayout groupCard = card(context);
        for (ToggleRow s :switches) {
            groupCard.addView(s);
        }
        layout.addView(groupCard);

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


    private static void showCleanFeedOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        ToggleRow hideSuggestedSwitch = createSwitch(context, R.drawable.ic_sparkle, "#64D2FF", I18n.t(context, R.string.ig_dialog_clean_feed_hide_suggested), FeatureFlags.hideSuggestionsInFeed);

        hideSuggestedSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.hideSuggestionsInFeed = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(hideSuggestedSwitch);

        ToggleRow hideThreadsSwitch = createSwitch(context, R.drawable.ic_sparkle, "#64D2FF", I18n.t(context, R.string.ig_dialog_clean_feed_hide_threads), FeatureFlags.hideThreadsSuggestions);

        hideThreadsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.hideThreadsSuggestions = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(hideThreadsSwitch);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_clean_feed), layout, () -> {});
    }


    /**
     * Builds the grouped "Lock" card: the two independent lock toggles (Lock DMs / Lock whole app —
     * enable either, or both), the "always ask" option, and the set/change-passcode row. Disabling
     * either lock requires the current passcode.
     */
    private static LinearLayout buildLockCard(Context context) {
        LinearLayout cardView = card(context);

        // 1. Set / change the passcode (shared by both locks; stored as a salted SHA-256 hash).
        cardView.addView(createActionRow(context, R.drawable.ic_shield,
                I18n.t(context, R.string.ig_dialog_misc_lock_dms_set), "#BF5AF2",
                v -> promptChangeDmPasscode(context)));

        // 2. Lock whole app — gate the ENTIRE app (independent of Lock DMs).
        ToggleRow lockApp = createSwitch(context, R.drawable.ic_shield, "#BF5AF2",
                I18n.t(context, R.string.ig_dialog_misc_lock_whole_app), FeatureFlags.lockWholeApp);
        lockApp.setOnCheckedChangeListener((b, checked) -> {
            if (suppressLockToggle) return;
            if (!checked && FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty()) {
                suppressLockToggle = true;
                lockApp.setChecked(true);
                suppressLockToggle = false;
                promptDisableLock(context, lockApp, true);
            } else {
                FeatureFlags.lockWholeApp = checked;
                SettingsManager.saveAllFlags();
            }
        });
        cardView.addView(lockApp);

        // 3. Lock DMs — gate just the DM inbox.
        ToggleRow lockDms = createSwitch(context, R.drawable.ic_shield, "#BF5AF2",
                I18n.t(context, R.string.ig_dialog_misc_lock_dms), FeatureFlags.lockDirectMessages);
        lockDms.setOnCheckedChangeListener((b, checked) -> {
            if (suppressLockToggle) return;
            if (!checked && FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty()) {
                suppressLockToggle = true;
                lockDms.setChecked(true);
                suppressLockToggle = false;
                promptDisableLock(context, lockDms, false);
            } else {
                FeatureFlags.lockDirectMessages = checked;
                SettingsManager.saveAllFlags();
            }
        });
        cardView.addView(lockDms);

        // 4. Re-lock every time — re-prompt whenever you leave the locked surface (not just on close).
        ToggleRow alwaysAsk = createSwitch(context, R.drawable.ic_shield, "#BF5AF2",
                I18n.t(context, R.string.ig_dialog_misc_lock_dms_always), FeatureFlags.lockDirectAlways);
        alwaysAsk.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.lockDirectAlways = checked;
            SettingsManager.saveAllFlags();
        });
        cardView.addView(alwaysAsk);

        // 5. Use fingerprint — prompt biometrics on unlock when the device has one enrolled.
        ToggleRow useFp = createSwitch(context, R.drawable.ic_shield, "#BF5AF2",
                I18n.t(context, R.string.ig_dialog_misc_lock_fingerprint), FeatureFlags.lockUseFingerprint);
        useFp.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.lockUseFingerprint = checked;
            SettingsManager.saveAllFlags();
        });
        cardView.addView(useFp);

        return cardView;
    }

    private static void showMiscOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create all child switches
        ToggleRow[] switches = new ToggleRow[]{
                createSwitch(context, R.drawable.ic_story_ring, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_disable_story_autoswipe), FeatureFlags.disableStoryFlipping),
                createSwitch(context, R.drawable.ic_movie, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_disable_video_autoplay),  FeatureFlags.disableVideoAutoPlay),
                createSwitch(context, R.drawable.ic_block, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_disable_repost),          FeatureFlags.disableRepost),
                createSwitch(context, R.drawable.ic_notification, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_show_feature_toasts),     FeatureFlags.showFeatureToasts),
                createSwitch(context, R.drawable.ic_notification, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_show_follower_toast),     FeatureFlags.showFollowerToast),
                createSwitch(context, R.drawable.ic_at, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_view_story_mentions),     FeatureFlags.enableStoryMentions),
                createSwitch(context, R.drawable.ic_block, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_disable_discover_people), FeatureFlags.disableDiscoverPeople),
                createSwitch(context, R.drawable.ic_content_copy, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_copy_comment),            FeatureFlags.enableCopyComment),
                createSwitch(context, R.drawable.ic_heart, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_disable_double_tap_like), FeatureFlags.disableDoubleTapLike),
                createSwitch(context, R.drawable.ic_content_copy, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_copy_caption),            FeatureFlags.enableCaptionCopy),
                createSwitch(context, R.drawable.ic_search, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_photo_zoom),               FeatureFlags.enablePhotoZoom),
                createSwitch(context, R.drawable.ic_timer, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_spoof_last_seen),          FeatureFlags.spoofLastSeen),
                createSwitch(context, R.drawable.ic_sparkle, "#BF5AF2", I18n.t(context, R.string.ig_dialog_misc_remove_meta_ai),        FeatureFlags.removeMetaAI)
                // Lock controls moved to their own grouped "Lock" card below (see buildLockCard).
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
                    case 8:
                        FeatureFlags.disableDoubleTapLike = isChecked;
                        break;
                    case 9:
                        FeatureFlags.enableCaptionCopy = isChecked;
                        break;
                    case 10:
                        FeatureFlags.enablePhotoZoom = isChecked;
                        break;
                    case 11:
                        FeatureFlags.spoofLastSeen = isChecked;
                        break;
                    case 12:
                        FeatureFlags.removeMetaAI = isChecked;
                        break;
                }

                SettingsManager.saveAllFlags();
            });
        }

        // Add views to layout
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        LinearLayout groupCard = card(context);
        for (ToggleRow s :switches) {
            groupCard.addView(s);
        }
        layout.addView(groupCard);

        // Lock, Hide Chats (Privacy group) and Custom Font/Emoji (Custom Theme) are now their own
        // top-level menu sections — no longer buried inside Misc.

        // Show dialog
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_misc), layout, () -> {
        });
    }

    /** Top-level "Lock" section (Privacy group) — the grouped passcode-lock card. */
    private static void showLockOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(buildLockCard(context));
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_misc_lock_section), layout, () -> {});
    }

    /** Top-level "Hide Specific Chats" section (Privacy group) — enable toggle + unhide manager.
     *  Hiding itself is done from inside a chat (the eye-off button in the thread header). */
    private static void showHideChatsOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        LinearLayout hideCard = card(context);
        ToggleRow hideChats = createSwitch(context, R.drawable.ic_eye_off, A_PRIVACY,
                I18n.t(context, R.string.ig_dialog_misc_hide_chats), FeatureFlags.hideSpecificChats);
        hideChats.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.hideSpecificChats = checked;
            SettingsManager.saveAllFlags();
        });
        hideCard.addView(hideChats);
        hideCard.addView(createActionRow(context, R.drawable.ic_eye,
                I18n.t(context, R.string.ig_hide_chats_manage), A_PRIVACY, v -> showHiddenChats(context)));
        layout.addView(hideCard);

        layout.addView(createInfoSection(context, "",
                I18n.t(context, R.string.ig_hide_chats_hint)));

        showSectionDialog(context, I18n.t(context, R.string.ig_hide_chats_title), layout, () -> {});
    }

    /** Unwraps an Activity from a Context (dialogs run inside IG's activity, possibly wrapped). */
    private static Activity activityOf(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Manager listing hidden chats with per-row unhide buttons. */
    private static void showHiddenChats(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        java.util.Map<String, String> hidden = ps.reso.instaeclipse.utils.ghost.HiddenThreads.all();
        if (hidden.isEmpty()) {
            layout.addView(createInfoSection(context, I18n.t(context, R.string.ig_hide_chats_title),
                    I18n.t(context, R.string.ig_hide_chats_empty)));
            showSectionDialog(context, I18n.t(context, R.string.ig_hide_chats_manage), layout, () -> {});
            return;
        }
        LinearLayout cardView = card(context);
        for (java.util.Map.Entry<String, String> e : hidden.entrySet()) {
            final String id = e.getKey();
            String label = (e.getValue() == null || e.getValue().isEmpty()) ? id : e.getValue();
            cardView.addView(createActionRow(context, R.drawable.ic_eye, label + "  ·  "
                    + I18n.t(context, R.string.ig_hide_chats_unhide), "#BF5AF2", v -> {
                ps.reso.instaeclipse.utils.ghost.HiddenThreads.unhide(id);
                Toast.makeText(context, I18n.t(context, R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
                ((View) v).setEnabled(false);
                ((View) v).setAlpha(0.4f);
            }));
        }
        layout.addView(cardView);
        showSectionDialog(context, I18n.t(context, R.string.ig_hide_chats_manage), layout, () -> {});
    }

    private static boolean suppressLockToggle = false;

    /** A styled numeric passcode field for the lock dialogs (rounded, centered, large). */
    private static android.widget.EditText makePinField(Context ctx, String hint) {
        android.widget.EditText e = new android.widget.EditText(ctx);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint(hint);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(20);
        e.setLetterSpacing(0.2f);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(C_TEXT2);
        // Size the box to a handful of digits instead of stretching — no wide empty margins.
        e.setEms(6);
        e.setMaxLines(1);
        e.setMinWidth(0);
        e.setMinHeight(0);
        e.setMinimumWidth(0);
        e.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dpF(ctx, 12));
        e.setBackground(bg);
        e.setPadding(dpI(ctx, 12), dpI(ctx, 10), dpI(ctx, 12), dpI(ctx, 10));
        return e;
    }

    /** Wrapper that centers the compact passcode box (sized to its content, not the dialog width). */
    private static View wrapPin(Context ctx, View field) {
        LinearLayout w = new LinearLayout(ctx);
        w.setGravity(Gravity.CENTER_HORIZONTAL);
        w.setPadding(dpI(ctx, 20), dpI(ctx, 10), dpI(ctx, 20), dpI(ctx, 6));
        w.addView(field, new LinearLayout.LayoutParams(-2, -2)); // wrap_content — box hugs the digits
        return w;
    }

    private static float dpF(Context c, int v) { return v * c.getResources().getDisplayMetrics().density; }
    private static int dpI(Context c, int v) { return Math.round(dpF(c, v)); }

    /** Turning the Lock-DMs toggle OFF requires the current passcode. */
    private static void promptDisableLock(Context context, ToggleRow lockSwitch) {
        promptDisableLock(context, lockSwitch, false);
    }

    /** Turning a lock toggle OFF requires the current passcode; on success disable + save, otherwise
     *  the toggle stays on. wholeApp=true disables the whole-app lock, else the DM lock. */
    private static void promptDisableLock(Context context, ToggleRow lockSwitch, boolean wholeApp) {
        try {
            Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            final android.widget.EditText cur = makePinField(themed, I18n.t(context, R.string.ig_dialog_misc_lock_dms_current));
            new AlertDialog.Builder(themed)
                    .setTitle(I18n.t(context, R.string.ig_dialog_misc_lock_dms_disable))
                    .setView(wrapPin(themed, cur))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.hashPass(cur.getText().toString())
                                .equals(FeatureFlags.lockDirectPasscode)) {
                            if (wholeApp) FeatureFlags.lockWholeApp = false;
                            else          FeatureFlags.lockDirectMessages = false;
                            suppressLockToggle = true;
                            lockSwitch.setChecked(false);
                            suppressLockToggle = false;
                            SettingsManager.saveAllFlags();
                        } else {
                            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_misc_lock_dms_wrong), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    /** If a DM passcode already exists, require the current one before changing it. */
    private static void promptChangeDmPasscode(Context context) {
        String existing = FeatureFlags.lockDirectPasscode;
        if (existing == null || existing.isEmpty()) { promptSetNewDmPasscode(context); return; }
        try {
            Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            final android.widget.EditText cur = makePinField(themed, I18n.t(context, R.string.ig_dialog_misc_lock_dms_current));
            new AlertDialog.Builder(themed)
                    .setTitle(I18n.t(context, R.string.ig_dialog_misc_lock_dms_current))
                    .setView(wrapPin(themed, cur))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.hashPass(cur.getText().toString()).equals(existing)) {
                            promptSetNewDmPasscode(context);
                        } else {
                            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_misc_lock_dms_wrong), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    /** Prompt for a new passcode (empty clears it). */
    private static void promptSetNewDmPasscode(Context context) {
        try {
            Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            final android.widget.EditText input = makePinField(themed, I18n.t(context, R.string.ig_dialog_misc_lock_dms_hint));
            new AlertDialog.Builder(themed)
                    .setTitle(I18n.t(context, R.string.ig_dialog_misc_lock_dms_set))
                    .setView(wrapPin(themed, input))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String pass = input.getText().toString();
                        if (pass.isEmpty()) {
                            FeatureFlags.lockDirectPasscode = "";
                            FeatureFlags.lockDirectSalt = "";
                            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_misc_lock_dms_cleared), Toast.LENGTH_SHORT).show();
                        } else {
                            ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.newSalt(); // fresh salt per set
                            FeatureFlags.lockDirectPasscode =
                                    ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.hashPass(pass);
                            Toast.makeText(context, I18n.t(context, R.string.ig_dialog_misc_lock_dms_saved), Toast.LENGTH_SHORT).show();
                        }
                        SettingsManager.saveAllFlags();
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    private static void showLocationOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        ToggleRow spoofSwitch = createSwitch(context, R.drawable.ic_pin, "#FFD60A",
                I18n.t(context, R.string.ig_dialog_location_spoof_enable), FeatureFlags.spoofLocation);
        spoofSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.spoofLocation = isChecked;
            SettingsManager.saveAllFlags();
        });

        String coordLabel = (FeatureFlags.spoofLat == 0.0 && FeatureFlags.spoofLng == 0.0)
                ? I18n.t(context, R.string.ig_dialog_location_unset)
                : I18n.t(context, R.string.ig_dialog_location_current, FeatureFlags.spoofLat, FeatureFlags.spoofLng);

        layout.addView(createDivider(context));
        layout.addView(spoofSwitch);
        layout.addView(createDivider(context));
        layout.addView(createActionRow(context, R.drawable.ic_pin,
                I18n.t(context, R.string.ig_dialog_location_pick) + " — " + coordLabel, "#FFD60A", v -> {
                    Bundle extras = new Bundle();
                    extras.putDouble(LocationPickerActivity.EXTRA_LAT, FeatureFlags.spoofLat);
                    extras.putDouble(LocationPickerActivity.EXTRA_LNG, FeatureFlags.spoofLng);
                    if (ModuleActivityLauncher.launch(context,
                            "ps.reso.instaeclipse.mods.location.LocationPickerActivity", extras)) {
                        if (currentDialog != null) {
                            try { currentDialog.dismiss(); } catch (Exception ignored) {}
                            currentDialog = null;
                        }
                    }
                }));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_location), layout, () -> {
        });
    }

    private static void showThemeOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // ---- Colors ----
        // Enable-custom-theme + open the full color customizer.
        ToggleRow themeSwitch = createSwitch(context, R.drawable.ic_palette, "#FF2D55",
                I18n.t(context, R.string.theme_enable), FeatureFlags.customThemeEnabled);
        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.customThemeEnabled = isChecked;
            SettingsManager.saveAllFlags();
            ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.invalidate();
            ps.reso.instaeclipse.mods.ui.theme.IgThemeHook.refreshCurrentActivity();
        });

        LinearLayout colorsCard = card(context);
        colorsCard.addView(themeSwitch);
        colorsCard.addView(createActionRow(context, R.drawable.ic_palette,
                I18n.t(context, R.string.theme_customize), "#FF2D55", v -> {
                    if (ModuleActivityLauncher.launch(context,
                            "ps.reso.instaeclipse.ui.theme.ThemeCustomizerActivity", null)) {
                        if (currentDialog != null) {
                            try { currentDialog.dismiss(); } catch (Exception ignored) {}
                            currentDialog = null;
                        }
                    }
                }));
        layout.addView(sectionHeader(context, I18n.t(context, R.string.theme_section_colors)));
        layout.addView(colorsCard);

        // ---- Fonts & Emoji ----
        // Custom Font + Custom Emoji now live under Custom Theme (moved out of Misc). Both apply on
        // the next Instagram start (Typeface cache). Emoji uses the font-fallback method, so any
        // color emoji .ttf/.otf works.
        layout.addView(sectionHeader(context, I18n.t(context, R.string.theme_section_fonts)));
        layout.addView(buildFontsCard(context));

        showSectionDialog(context, I18n.t(context, R.string.theme_title), layout, () -> {
        });
    }

    /** Grouped card: Custom Font (toggle + picker) and Custom Emoji (toggle + picker). Shared by the
     *  Custom Theme section. Applies on next Instagram start. */
    private static LinearLayout buildFontsCard(Context context) {
        LinearLayout fontCard = card(context);

        ToggleRow fontSwitch = createSwitch(context, R.drawable.ic_palette, "#FF2D55",
                I18n.t(context, R.string.ig_dialog_misc_custom_font), FeatureFlags.customFontEnabled);
        fontSwitch.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.customFontEnabled = checked;
            SettingsManager.saveAllFlags();
        });
        fontCard.addView(fontSwitch);
        fontCard.addView(createActionRow(context, R.drawable.ic_palette,
                I18n.t(context, R.string.ig_custom_font_pick), "#FF2D55", v -> {
                    Activity act = activityOf(context);
                    if (act != null) ps.reso.instaeclipse.mods.ui.CustomFontHook.launchPicker(act);
                    else Toast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker),
                            Toast.LENGTH_SHORT).show();
                }));

        ToggleRow emojiSwitch = createSwitch(context, R.drawable.ic_palette, "#FF2D55",
                I18n.t(context, R.string.ig_dialog_misc_custom_emoji), FeatureFlags.customEmojiEnabled);
        emojiSwitch.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.customEmojiEnabled = checked;
            SettingsManager.saveAllFlags();
        });
        fontCard.addView(emojiSwitch);
        fontCard.addView(createActionRow(context, R.drawable.ic_palette,
                I18n.t(context, R.string.ig_custom_emoji_pick), "#FF2D55", v -> {
                    Activity act = activityOf(context);
                    if (act != null) ps.reso.instaeclipse.mods.ui.CustomFontHook.launchEmojiPicker(act);
                    else Toast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker),
                            Toast.LENGTH_SHORT).show();
                }));
        return fontCard;
    }

    private static String qualityLabel(Context context, int h) {
        if (h == 360) return I18n.t(context, R.string.ig_dialog_quality_360);
        if (h == 480) return I18n.t(context, R.string.ig_dialog_quality_480);
        if (h == 720) return I18n.t(context, R.string.ig_dialog_quality_720);
        if (h == 1080) return I18n.t(context, R.string.ig_dialog_quality_1080);
        if (h == Integer.MAX_VALUE) return I18n.t(context, R.string.ig_dialog_quality_max);
        return I18n.t(context, R.string.ig_dialog_quality_auto);
    }

    private static class RadioRow extends LinearLayout {
        private final TextView checkmark;

        RadioRow(Context context, String label, boolean checked) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(8, 4, 8, 4);
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);

            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(C_PRESSED));
            bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            setBackground(bg);

            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(Color.WHITE);
            labelView.setTextSize(16);
            labelView.setPadding(0, 20, 16, 20);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            labelView.setLayoutParams(lp);

            checkmark = new TextView(context);
            checkmark.setText("✓");
            checkmark.setTextColor(Color.parseColor("#0A84FF"));
            checkmark.setTextSize(18);
            checkmark.setTypeface(null, Typeface.BOLD);
            checkmark.setVisibility(checked ? VISIBLE : INVISIBLE);

            addView(labelView);
            addView(checkmark);
        }

        void setChecked(boolean checked) {
            checkmark.setVisibility(checked ? VISIBLE : INVISIBLE);
        }
    }

    private static void showQualityOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        String[] labels = {
                I18n.t(context, R.string.ig_dialog_quality_auto),
                I18n.t(context, R.string.ig_dialog_quality_360),
                I18n.t(context, R.string.ig_dialog_quality_480),
                I18n.t(context, R.string.ig_dialog_quality_720),
                I18n.t(context, R.string.ig_dialog_quality_1080),
                I18n.t(context, R.string.ig_dialog_quality_max)
        };
        int[] values = {0, 360, 480, 720, 1080, Integer.MAX_VALUE};
        int current = FeatureFlags.forceReelQuality;

        RadioRow[] rows = new RadioRow[labels.length];
        for (int i = 0; i < labels.length; i++) {
            rows[i] = new RadioRow(context, labels[i], values[i] == current);
        }
        for (int i = 0; i < rows.length; i++) {
            int idx = i;
            rows[i].setOnClickListener(v -> {
                FeatureFlags.forceReelQuality = values[idx];
                SettingsManager.saveAllFlags();
                for (RadioRow r : rows) r.setChecked(false);
                rows[idx].setChecked(true);
            });
            layout.addView(rows[i]);
            if (i < rows.length - 1) layout.addView(createDivider(context));
        }

        showSectionDialog(context,
                I18n.t(context, R.string.ig_dialog_quality_force_reels) + " — " + qualityLabel(context, current),
                layout, () -> {
        });
    }

    private static void showDownloaderOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(createActionRow(context, R.drawable.ic_folder, I18n.t(context, R.string.ig_dialog_downloader_settings), "#FF9F0A", v -> showDownloaderSettings(context)));

        ToggleRow postSwitch    = createSwitch(context, R.drawable.ic_download, "#FF9F0A", I18n.t(context, R.string.ig_dialog_downloader_posts),    FeatureFlags.enablePostDownload);
        ToggleRow storySwitch   = createSwitch(context, R.drawable.ic_download, "#FF9F0A", I18n.t(context, R.string.ig_dialog_downloader_stories),  FeatureFlags.enableStoryDownload);
        ToggleRow reelSwitch    = createSwitch(context, R.drawable.ic_download, "#FF9F0A", I18n.t(context, R.string.ig_dialog_downloader_reels),    FeatureFlags.enableReelDownload);
        ToggleRow profileSwitch = createSwitch(context, R.drawable.ic_download, "#FF9F0A", I18n.t(context, R.string.ig_dialog_downloader_profiles), FeatureFlags.enableProfileDownload);

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

        LinearLayout groupCard = card(context);
        for (ToggleRow s :switches) {
            groupCard.addView(s);
        }
        layout.addView(groupCard);

        // Copy Media Link (#117) — standalone; injects a "Copy Media Link" row into the
        // post ⋮ menu that copies the current slide's direct CDN url to the clipboard.
        ToggleRow copyLinkSwitch = createSwitch(context, R.drawable.ic_link, "#FF9F0A",
                I18n.t(context, R.string.ig_dialog_downloader_copy_link), FeatureFlags.copyMediaLink);
        copyLinkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.copyMediaLink = isChecked;
            SettingsManager.saveAllFlags();
        });
        // Save Instants (#184) — long-press a received Instant to save its media.
        ToggleRow saveInstantsSwitch = createSwitch(context, R.drawable.ic_download, "#FF9F0A",
                I18n.t(context, R.string.ig_dialog_misc_save_instants), FeatureFlags.saveInstants);
        saveInstantsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.saveInstants = isChecked;
            SettingsManager.saveAllFlags();
        });

        ToggleRow uploadInstantsSwitch = createSwitch(context, R.drawable.ic_download, "#FF9F0A",
                I18n.t(context, R.string.ig_dialog_misc_upload_instants), FeatureFlags.uploadInstants);
        uploadInstantsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.uploadInstants = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(createDivider(context));
        LinearLayout copyLinkCard = card(context);
        copyLinkCard.addView(copyLinkSwitch);
        copyLinkCard.addView(saveInstantsSwitch);
        copyLinkCard.addView(uploadInstantsSwitch);

        // Cache Stories (24h) — keep viewed stories locally so they survive expiry/deletion.
        ToggleRow cacheStoriesSwitch = createSwitch(context, R.drawable.ic_download, "#FF9F0A",
                I18n.t(context, R.string.ig_dialog_misc_cache_stories), FeatureFlags.cacheStories);
        cacheStoriesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.cacheStories = isChecked;
            SettingsManager.saveAllFlags();
        });
        copyLinkCard.addView(cacheStoriesSwitch);
        copyLinkCard.addView(createActionRow(context, R.drawable.ic_eye,
                I18n.t(context, R.string.ig_story_cache_view), "#FF9F0A", v -> showCachedStories(context)));
        layout.addView(copyLinkCard);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_downloader), layout, () -> {});
    }

    /** Viewer for the 24h story cache — a list per author; tap to view; plus a clear action. */
    private static void showCachedStories(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry> entries =
                ps.reso.instaeclipse.utils.media.StoryCache.entries();
        if (entries.isEmpty()) {
            layout.addView(createInfoSection(context, I18n.t(context, R.string.ig_story_cache_title),
                    I18n.t(context, R.string.ig_story_cache_empty)));
            showSectionDialog(context, I18n.t(context, R.string.ig_story_cache_view), layout, () -> {});
            return;
        }
        // Level 1: one "folder" row per username; tap opens that user's cached stories.
        java.util.LinkedHashMap<String, java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry>> byUser =
                new java.util.LinkedHashMap<>();
        for (ps.reso.instaeclipse.utils.media.StoryCache.Entry e : entries) {
            String author = (e.author == null || e.author.isEmpty()) ? "unknown" : e.author;
            byUser.computeIfAbsent(author, k -> new java.util.ArrayList<>()).add(e);
        }
        LinearLayout foldersCard = card(context);
        for (java.util.Map.Entry<String, java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry>> u : byUser.entrySet()) {
            final String author = u.getKey();
            final java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry> list = u.getValue();
            foldersCard.addView(createActionRow(context, R.drawable.ic_folder,
                    author + "   (" + list.size() + ")", "#FF9F0A", v -> showUserStories(context, author, list)));
        }
        layout.addView(foldersCard);
        layout.addView(createActionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_story_cache_clear),
                "#FF453A", v -> {
                    // Confirm before permanently deleting every cached story, then refresh the view.
                    try {
                        Context themed = new android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
                        new AlertDialog.Builder(themed)
                                .setTitle(I18n.t(context, R.string.ig_story_cache_clear))
                                .setMessage(I18n.t(context, R.string.ig_story_cache_clear_confirm))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(I18n.t(context, R.string.ig_story_cache_clear), (d, w) -> {
                                    ps.reso.instaeclipse.utils.media.StoryCache.clearAll();
                                    Toast.makeText(context, I18n.t(context, R.string.ig_story_cache_cleared), Toast.LENGTH_SHORT).show();
                                    showCachedStories(context); // refresh so the deleted rows disappear
                                })
                                .show();
                    } catch (Throwable ignored) {}
                }));
        showSectionDialog(context, I18n.t(context, R.string.ig_story_cache_view), layout, () -> {});
    }

    /** Level 2: the cached stories for one username. */
    private static void showUserStories(Context context, String author,
                                        java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry> list) {
        LinearLayout layout = createSwitchLayout(context);
        LinearLayout cardView = card(context);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault());
        for (ps.reso.instaeclipse.utils.media.StoryCache.Entry e : list) {
            String label = (e.video ? "🎬  " : "🖼  ") + fmt.format(new java.util.Date(e.at))
                    + (e.isExpired() ? "   ·  Expired" : "");
            cardView.addView(createActionRow(context, e.video ? R.drawable.ic_movie : R.drawable.ic_eye,
                    label, "#FF9F0A", v -> openCachedStory(context, e)));
        }
        layout.addView(cardView);
        showSectionDialog(context, author, layout, () -> {});
    }

    /** Opens one cached story full-screen: ImageView for photos, VideoView for videos. */
    private static void openCachedStory(Context context, ps.reso.instaeclipse.utils.media.StoryCache.Entry e) {
        try {
            android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            android.widget.FrameLayout root = new android.widget.FrameLayout(context);
            root.setBackgroundColor(Color.BLACK);
            root.setOnClickListener(v -> dialog.dismiss());
            if (e.video) {
                android.widget.VideoView vv = new android.widget.VideoView(context);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -1);
                lp.gravity = Gravity.CENTER;
                vv.setLayoutParams(lp);
                vv.setVideoPath(e.path);
                vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
                root.addView(vv);
            } else {
                android.widget.ImageView iv = new android.widget.ImageView(context);
                iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(e.path));
                root.addView(iv);
            }
            // Native-ish story chrome: a full progress segment at the very top, then a header row
            // (author + time) with a Saved/Expired badge — so it reads like IG's own story viewer.
            float d = context.getResources().getDisplayMetrics().density;
            int pad = Math.round(12 * d);

            View bar = new View(context);
            bar.setBackgroundColor(Color.WHITE);
            android.widget.FrameLayout.LayoutParams barLp = new android.widget.FrameLayout.LayoutParams(-1, Math.round(3 * d));
            barLp.leftMargin = pad; barLp.rightMargin = pad; barLp.topMargin = Math.round(8 * d);
            bar.setLayoutParams(barLp);
            root.addView(bar);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.FrameLayout.LayoutParams hLp = new android.widget.FrameLayout.LayoutParams(-1, -2);
            hLp.topMargin = Math.round(16 * d); hLp.leftMargin = pad; hLp.rightMargin = pad;
            header.setLayoutParams(hLp);

            TextView name = new TextView(context);
            String who = (e.author == null || e.author.isEmpty()) ? "story" : e.author;
            java.text.SimpleDateFormat f2 = new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault());
            name.setText(who + "   " + f2.format(new java.util.Date(e.at)));
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, -2, 1f);
            name.setLayoutParams(nLp);
            header.addView(name);

            TextView badge = new TextView(context);
            boolean expired = e.isExpired();
            badge.setText(expired ? "Expired" : "Saved");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(11);
            badge.setTypeface(null, android.graphics.Typeface.BOLD);
            badge.setPadding(Math.round(10 * d), Math.round(4 * d), Math.round(10 * d), Math.round(4 * d));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(Color.parseColor(expired ? "#FF453A" : "#0A84FF"));
            bg.setCornerRadius(Math.round(10 * d));
            badge.setBackground(bg);
            header.addView(badge);
            root.addView(header);

            // Save-to-gallery button (bottom-right): copies the cached file to the gallery.
            TextView save = new TextView(context);
            save.setText("⤓ Save");
            save.setTextColor(Color.WHITE);
            save.setTextSize(14);
            save.setTypeface(null, android.graphics.Typeface.BOLD);
            save.setPadding(Math.round(18 * d), Math.round(10 * d), Math.round(18 * d), Math.round(10 * d));
            android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
            sbg.setColor(Color.parseColor("#CC0A84FF"));
            sbg.setCornerRadius(Math.round(22 * d));
            save.setBackground(sbg);
            android.widget.FrameLayout.LayoutParams sLp = new android.widget.FrameLayout.LayoutParams(-2, -2);
            sLp.gravity = Gravity.BOTTOM | Gravity.END;
            sLp.rightMargin = pad; sLp.bottomMargin = Math.round(28 * d);
            save.setLayoutParams(sLp);
            save.setOnClickListener(v -> saveCachedStoryToGallery(context, e));
            root.addView(save);

            dialog.setContentView(root);
            dialog.show();
        } catch (Throwable t) {
            Toast.makeText(context, "Couldn't open story", Toast.LENGTH_SHORT).show();
        }
    }

    /** Copies a cached story's local file into the gallery/download folder (off the UI thread). */
    private static void saveCachedStoryToGallery(Context context, ps.reso.instaeclipse.utils.media.StoryCache.Entry e) {
        Toast.makeText(context, I18n.t(context, R.string.ig_story_cache_saving), Toast.LENGTH_SHORT).show();
        ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.saveLocalFileToGallery(
                context, e.path,
                (e.author == null || e.author.isEmpty()) ? "story" : e.author, e.id, e.video,
                I18n.t(context, R.string.ig_story_cache_saved), I18n.t(context, R.string.ig_story_cache_save_fail));
    }

    private static void showDownloaderSettings(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        String folderRaw = FeatureFlags.downloaderCustomPath.isEmpty()
                ? android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/Download/InstaEclipse"
                : FeatureFlags.downloaderCustomPath;
        // Strip everything up to and including the primary storage root ("…/0/")
        // so "/storage/emulated/0/Download/InstaEclipse" → "Download/InstaEclipse"
        String folderDisplay = folderRaw.replaceFirst("^.*/0/", "");
        layout.addView(createInfoSection(context,
                I18n.t(context, R.string.ig_dialog_downloader_folder), folderDisplay));

        ToggleRow usernameFolderSwitch = createSwitch(context, R.drawable.ic_folder, "#FF9F0A", I18n.t(context, R.string.ig_dialog_downloader_username_subfolder), FeatureFlags.downloaderUsernameFolder);
        ToggleRow timestampSwitch = createSwitch(context, R.drawable.ic_timer, "#FF9F0A", I18n.t(context, R.string.ig_dialog_downloader_add_timestamp), FeatureFlags.downloaderAddTimestamp);

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

        layout.addView(createActionRow(context, R.drawable.ic_save, I18n.t(context, R.string.ig_dialog_backup_settings), "#30D158", v -> {
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

        layout.addView(createActionRow(context, R.drawable.ic_folder, I18n.t(context, R.string.ig_dialog_restore_settings), "#0A84FF", v -> {
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
        creator.setTextColor(C_TEXT2);
        creator.setTextSize(14f);
        creator.setGravity(Gravity.CENTER);
        creator.setPadding(0, 0, 0, 32);

        layout.addView(title);
        layout.addView(creator);
        LinearLayout linksRow = new LinearLayout(context);
        linksRow.setOrientation(LinearLayout.HORIZONTAL);
        linksRow.setGravity(Gravity.CENTER);

        View githubBtn = createActionRow(context, R.drawable.ic_github_logo, I18n.t(context, R.string.ig_dialog_about_github), "#8E8E93", v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ReSo7200/InstaEclipse"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        githubBtn.setLayoutParams(btnLp);

        View tgBtn = createActionRow(context, R.drawable.ic_telegram_logo, I18n.t(context, R.string.ig_dialog_about_telegram), "#29B6F6", v -> {
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
        layout.addView(createActionRow(context, R.drawable.ic_restart, I18n.t(context, R.string.ig_dialog_restart_now), "#FF453A", v -> restartApp(context)));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_restart), layout, () -> {
        });
    }


    private static void showClearCacheSection(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView message = new TextView(context);
        message.setText(I18n.t(context, R.string.ig_dialog_clear_cache_message));
        message.setTextColor(Color.WHITE);
        message.setTextSize(16f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, 30);

        layout.addView(message);
        layout.addView(createActionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_dialog_clear_cache_now), "#FF9F0A", v -> {
            ps.reso.instaeclipse.utils.core.DexKitCache.clearCache();
            restartApp(context);
        }));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_clear_cache), layout, () -> {});
    }

    /**
     * Auto-Clear Cache — its own Tools section, separate from the hooks-cache clear above.
     * Clears Instagram's media cache automatically when the app is closed/backgrounded, if the
     * cache is over the chosen size. Cache only (IG regenerates it); never touches app data.
     */
    private static void showAutoClearCacheSection(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(createInfoSection(context,
                I18n.t(context, R.string.ig_dialog_auto_clear_cache),
                I18n.t(context, R.string.ig_dialog_auto_clear_cache_desc)));

        ToggleRow autoClear = createSwitch(context, R.drawable.ic_timer, "#FF9F0A",
                I18n.t(context, R.string.ig_dialog_auto_clear_cache), FeatureFlags.autoClearCache);
        autoClear.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.autoClearCache = checked;
            SettingsManager.saveAllFlags();
        });

        // IG's real cache is small (self-trimmed, typically <150MB) — high thresholds never
        // fire, so the reachable options are low. The bulk of IG storage is *data*, not cache.
        final int[] presets = {50, 100, 200, 500};
        final View sizeRow = createActionRow(context, R.drawable.ic_folder,
                I18n.t(context, R.string.ig_dialog_auto_clear_cache_size, FeatureFlags.autoClearCacheSizeMb), "#FF9F0A", v -> {});
        sizeRow.setOnClickListener(v -> {
            int cur = FeatureFlags.autoClearCacheSizeMb, idx = 0;
            for (int i = 0; i < presets.length; i++) if (presets[i] == cur) { idx = i; break; }
            FeatureFlags.autoClearCacheSizeMb = presets[(idx + 1) % presets.length];
            SettingsManager.saveAllFlags();
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    if (vg.getChildAt(i) instanceof TextView) {
                        ((TextView) vg.getChildAt(i)).setText(
                                I18n.t(context, R.string.ig_dialog_auto_clear_cache_size, FeatureFlags.autoClearCacheSizeMb));
                        break;
                    }
                }
            }
        });

        layout.addView(createDivider(context));
        LinearLayout card = card(context);
        card.addView(autoClear);
        card.addView(sizeRow);
        layout.addView(card);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_auto_clear_cache), layout, () -> {});
    }

    // ==== HELPERS ====

    @SuppressLint("SetTextI18n")
    private static void showSectionDialog(Context context, String title, LinearLayout contentLayout, Runnable onSave) {
        if (currentDialog != null) { try { currentDialog.dismiss(); } catch (Exception ignored) {} currentDialog = null; }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, 0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(C_SHEET);
        background.setCornerRadii(new float[]{48, 48, 48, 48, 0, 0, 0, 0});
        container.setBackground(background);

        container.addView(createDragHandle(context));

        // Header row: back arrow + title
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(20, 2, 24, 14);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView backBtn = new TextView(context);
        backBtn.setText("‹");
        backBtn.setTextColor(C_ACCENT);
        backBtn.setTextSize(34);
        backBtn.setIncludeFontPadding(false);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setMinWidth(0);
        backBtn.setMinimumWidth(0);
        int bp = dp(context, 6);
        backBtn.setPadding(bp, bp, dp(context, 16), bp);
        StateListDrawable backBtnBg = new StateListDrawable();
        backBtnBg.addState(new int[]{android.R.attr.state_pressed}, roundedColor(C_PRESSED, dp(context, 10)));
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
        titleView.setTextColor(C_TEXT);
        titleView.setTextSize(21);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setLetterSpacing(0.01f);

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

        ScrollView scrollView = createScrollableContainer(context, container);

        currentDialog = createBottomSheetDialog(context, scrollView);
        currentDialog.show();
    }


    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private static class ToggleRow extends LinearLayout {
        private final Switch toggle;
        private final TextView labelView;

        ToggleRow(Context context, String label, boolean checked) {
            this(context, 0, null, label, checked);
        }

        ToggleRow(Context context, int iconRes, String accentHex, String label, boolean checked) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(8, 4, 8, 4);
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);

            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(C_PRESSED));
            bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            setBackground(bg);

            if (iconRes != 0) {
                addView(buildIconChip(context, iconRes, accentHex));
            }

            labelView = new TextView(context);
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
            labelView.setTypeface(null, Typeface.BOLD);
            labelView.setTextSize(17);
        }
    }

    private static ToggleRow createSwitch(Context context, String label, boolean defaultState) {
        return new ToggleRow(context, label, defaultState);
    }

    private static ToggleRow createSwitch(Context context, int iconRes, String accentHex, String label, boolean defaultState) {
        return new ToggleRow(context, iconRes, accentHex, label, defaultState);
    }

    /** The same 36dp rounded, tinted icon chip used by the main menu's nav rows. */
    private static View buildIconChip(Context context, int iconRes, String accentHex) {
        android.widget.ImageView iconView = new android.widget.ImageView(context);
        int accent = Color.parseColor(accentHex);
        Drawable icon = loadModuleIcon(iconRes, accent);
        if (icon != null) iconView.setImageDrawable(icon);
        iconView.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int iconPad = dp(context, 8);
        iconView.setPadding(iconPad, iconPad, iconPad, iconPad);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor((accent & 0x00FFFFFF) | 0x33000000);
        chipBg.setCornerRadius(12);
        iconView.setBackground(chipBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
        iconLp.rightMargin = dp(context, 14);
        iconView.setLayoutParams(iconLp);
        return iconView;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout createSwitchLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 8, 16, 8);
        return layout;
    }

    private static View createInfoSection(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(40, 24, 32, 24);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(17);
        labelView.setTextColor(Color.WHITE);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(context);
        valueView.setText(value);
        valueView.setTextSize(13);
        valueView.setTextColor(C_TEXT2);
        valueView.setMaxLines(1);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        valueView.setPadding(16, 0, 0, 0);
        // weight=1 / width=0: value fills remaining space and truncates at start if too long
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(labelView);
        row.addView(valueView);
        return row;
    }

    private static View createActionRow(Context context, String emoji, String label, String accentHex, View.OnClickListener onClick) {
        TextView iconView = new TextView(context);
        iconView.setText(emoji);
        iconView.setTextSize(18);
        return createActionRow(context, iconView, label, accentHex, onClick);
    }

    /** Same visual chip as the emoji variant, but with a real vector logo (tinted to match). */
    private static View createActionRow(Context context, int iconRes, String label, String accentHex, View.OnClickListener onClick) {
        android.widget.ImageView iconView = new android.widget.ImageView(context);
        Drawable icon = loadModuleIcon(iconRes, Color.parseColor(accentHex));
        if (icon != null) iconView.setImageDrawable(icon);
        return createActionRow(context, iconView, label, accentHex, onClick);
    }

    /** This dialog runs inside Instagram's own process, so a drawable resource ID must be
     *  resolved against our OWN module's resource table (via XModuleResources), not Instagram's
     *  — ContextCompat.getDrawable(context, iconRes) would resolve against whatever Instagram's
     *  own resource table happens to have at that numeric ID, since IDs aren't portable across
     *  APKs. Same pattern already used by GhostDMMarkAsReadHook for its icon. */
    @SuppressLint("UseCompatLoadingForDrawables")
    /** Public wrapper so hooks (e.g. the DM thread button) can load a tinted module vector. */
    public static Drawable moduleIcon(int iconRes, int tintColor) {
        return loadModuleIcon(iconRes, tintColor);
    }

    private static Drawable loadModuleIcon(int iconRes, int tintColor) {
        try {
            Drawable icon = android.content.res.XModuleResources.createInstance(Module.moduleSourceDir, null)
                    .getDrawable(iconRes, null);
            icon = icon.mutate();
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN));
            return icon;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View createActionRow(Context context, View iconView, String label, String accentHex, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Aligned with the menu-row metrics so action rows sit flush inside the same grouped cards.
        row.setPadding(dp(context, 8), dp(context, 11), dp(context, 10), dp(context, 11));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowRipple(context, 16));

        GradientDrawable iconBg = new GradientDrawable();
        // Color.parseColor's 8-digit form is #AARRGGBB (alpha FIRST) — appending alpha as a
        // suffix would misparse it as an opaque color instead of a translucent tint.
        int accentColor = Color.parseColor(accentHex);
        iconBg.setColor((accentColor & 0x00FFFFFF) | 0x33000000); // 20% opacity tint
        iconBg.setCornerRadius(12);
        iconView.setBackground(iconBg);
        int ip = dp(context, 8);
        iconView.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
        iconLp.rightMargin = dp(context, 14);
        iconView.setLayoutParams(iconLp);
        if (iconView instanceof android.widget.ImageView) {
            ((android.widget.ImageView) iconView).setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        }

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
        handleBg.setColor(C_HANDLE);
        handleBg.setCornerRadius(3);
        handle.setBackground(handleBg);

        wrapper.addView(handle);
        return wrapper;
    }

    /**
     * A dialog window created with WRAP_CONTENT height makes its ScrollView measure at its full,
     * unconstrained content height too — so long menus just overflow past the top of the screen
     * instead of actually scrolling. Capping the ScrollView's own measured height (via AT_MOST)
     * keeps short menus compact while making tall ones internally scrollable.
     */
    private static class MaxHeightScrollView extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScrollView(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int cappedSpec = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, cappedSpec);
        }
    }

    private static ScrollView createScrollableContainer(Context context, View content) {
        return createScrollableContainer(context, content, 0.82f);
    }

    private static ScrollView createScrollableContainer(Context context, View content, float heightFraction) {
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        MaxHeightScrollView scrollView = new MaxHeightScrollView(context, Math.round(screenHeight * heightFraction));
        scrollView.addView(content);
        return scrollView;
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
        container.setPadding(6, 2, 6, 2);

        // Faint accent tint so the master control reads as distinct from the toggle card below it.
        GradientDrawable background = new GradientDrawable();
        background.setColor((C_ACCENT & 0x00FFFFFF) | 0x24000000);
        background.setCornerRadius(R_CARD);
        background.setStroke(1, (C_ACCENT & 0x00FFFFFF) | 0x40000000);
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
