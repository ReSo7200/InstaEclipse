package ps.reso.instaeclipse.utils.dialog;

import ps.reso.instaeclipse.utils.feature.ExtrasFeatures;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Checkable;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class DialogUtils {

    private static Dialog currentDialog;

    private static final int C_SHEET    = 0xFF202123;
    private static final int C_CARD     = 0xFF202123;
    private static final int C_PRESSED  = 0xFF2C2C30;
    private static final int C_HAIRLINE = 0xFF2D2F33;
    private static final int C_TEXT     = 0xFFFFFFFF; // primary text
    private static final int C_TEXT2    = 0xFFA8A8A8;
    private static final int C_HANDLE   = 0xFF8F949E;
    private static final int C_DANGER   = 0xFFED4956;
    private static final int C_ACCENT   = 0xFF0095F6;

    private static boolean isDarkTheme(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static int cSheet(Context ctx) {
        if (ctx != null) {
            try {
                int resId = ctx.getResources().getIdentifier("igds_bottom_sheet_background", "color", ctx.getPackageName());
                if (resId != 0) return ctx.getColor(resId);
            } catch (Throwable ignored) {}
            try {
                int resId = ctx.getResources().getIdentifier("igds_color_elevated_background", "color", ctx.getPackageName());
                if (resId != 0) return ctx.getColor(resId);
            } catch (Throwable ignored) {}
            try {
                int resId = ctx.getResources().getIdentifier("bottom_sheet_background", "color", ctx.getPackageName());
                if (resId != 0) return ctx.getColor(resId);
            } catch (Throwable ignored) {}
        }
        return isDarkTheme(ctx) ? C_SHEET : 0xFFFFFFFF;
    }

    private static int cCard(Context ctx) { return cSheet(ctx); }

    private static int cPressed(Context ctx) {
        if (ctx != null) {
            try {
                int resId = ctx.getResources().getIdentifier("igds_color_highlight_background", "color", ctx.getPackageName());
                if (resId != 0) return ctx.getColor(resId);
            } catch (Throwable ignored) {}
        }
        return isDarkTheme(ctx) ? C_PRESSED : 0xFFEAEAEA;
    }

    private static int cHairline(Context ctx) {
        if (ctx != null) {
            try {
                int resId = ctx.getResources().getIdentifier("igds_color_separator", "color", ctx.getPackageName());
                if (resId != 0) return ctx.getColor(resId);
            } catch (Throwable ignored) {}
        }
        return isDarkTheme(ctx) ? C_HAIRLINE : 0xFFEFEFEF;
    }

    private static int cText(Context ctx) { return isDarkTheme(ctx) ? C_TEXT : 0xFF262626; }
    private static int cText2(Context ctx) { return isDarkTheme(ctx) ? C_TEXT2 : 0xFF737373; }

    private static int cHandle(Context ctx) {
        if (ctx != null) {
            try {
                int resId = ctx.getResources().getIdentifier("igds_color_secondary_icon", "color", ctx.getPackageName());
                if (resId != 0) return ctx.getColor(resId);
            } catch (Throwable ignored) {}
        }
        return isDarkTheme(ctx) ? C_HANDLE : 0xFFDBDBDB;
    }

    private static float getSheetCornerRadius(Context context) {
        if (context != null) {
            try {
                int resId = context.getResources().getIdentifier("bottom_sheet_corner_radius", "dimen", context.getPackageName());
                if (resId != 0) {
                    float r = context.getResources().getDimension(resId);
                    if (r > 0) return r;
                }
            } catch (Throwable ignored) {}
            try {
                int resId = context.getResources().getIdentifier("igds_bottom_sheet_corner_radius", "dimen", context.getPackageName());
                if (resId != 0) {
                    float r = context.getResources().getDimension(resId);
                    if (r > 0) return r;
                }
            } catch (Throwable ignored) {}
        }
        return dp(context, 30);
    }

    private static int accent() { return ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.accentOr(C_TEXT); }

    private static final String A_APPEARANCE = "#0095F6";
    private static final String A_PRIVACY    = "#0095F6";
    private static final String A_MEDIA      = "#0095F6";
    private static final String A_SOCIAL     = "#0095F6";
    private static final String A_TOOLS      = "#0095F6";

    private static final int R_CARD = 0;

    @SuppressLint("UseCompatLoadingForDrawables")
    public static void showEclipseOptionsDialog(Context context) {
        SettingsManager.init(context);
        clearSectionStack();

        LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable background = new GradientDrawable();
        background.setColor(cSheet(context));
        float corner = getSheetCornerRadius(context);
        background.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        outer.setBackground(background);

        outer.addView(createDragHandle(context));

        TextView title = new TextView(context);
        title.setText(stripEdgeEmoji(I18n.t(context, R.string.ig_dialog_title)));
        title.setTextColor(cText(context));
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.START);
        title.setPadding(dp(context, 20), dp(context, 2), dp(context, 20), dp(context, 14));
        outer.addView(title);
        outer.addView(createHeaderDivider(context));

        LinearLayout mainLayout = buildMainMenuLayout(context);
        ScrollView scrollView = createScrollableContainer(context, mainLayout, 0.75f);
        outer.addView(scrollView);

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
        showBottomSheetDialog(currentDialog, outer);
    }

    public static void showSimpleDialog(Context context, String title, String message) {
        try {
            IeDialog dlg = new IeDialog(context).title(title).message(message);
            dlg.primary(I18n.t(context, R.string.ig_dialog_ok), null);
            dlg.show();
        } catch (Exception e) {
            // handle UI crash fallback
        }
    }

    @SuppressLint("SetTextI18n")
    private static LinearLayout buildMainMenuLayout(Context context) {
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(0, 0, 0, dp(context, 20));

        // ---- APPEARANCE ---- look & feel: theme, feed, navigation bar, reels & stories
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_appearance)));
        LinearLayout appearance = createGroupCard(context);
        appearance.addView(createMenuRow(context, R.drawable.ic_palette, I18n.t(context, R.string.ig_dialog_menu_theme), A_APPEARANCE, () -> showThemeOptions(context), onCount(context, "customThemeEnabled", "customFontEnabled", "customEmojiEnabled")));
        appearance.addView(createMenuRow(context, R.drawable.ic_sparkle, I18n.t(context, R.string.menu_feed), A_APPEARANCE, () -> showFeedOptions(context), onCount(context, "hideSuggestionsInFeed", "hideThreadsSuggestions", "hideLikedPosts", "hideCommunityNotes", "hidePlusUpsells", "followingOnlyFeed", "hideStoriesTray", "disableDiscoverPeople", "expandCaptions", "exactTimestamps", "highResolutionImages", "enablePhotoZoom", "disableVideoAutoPlay")));
        appearance.addView(createMenuRow(context, R.drawable.ic_tune, I18n.t(context, R.string.menu_navigation), A_APPEARANCE, () -> showNavigationOptions(context), navCount(context)));
        appearance.addView(createMenuRow(context, R.drawable.ic_movie, I18n.t(context, R.string.menu_reels), A_APPEARANCE, () -> showReelsOptions(context), onCount(context, "reelsAutoScroll", "reelsLockScroll", "forceReelScrubber", "holdLeftSlowMotion", "disableRepost")));
        appearance.addView(createMenuRow(context, R.drawable.ic_story_ring, I18n.t(context, R.string.menu_stories), A_APPEARANCE, () -> showStoriesOptions(context), onCount(context, "disableStoryFlipping", "enableStoryMentions", "storyLinks", "storyInfo", "storyViewerSearch")));
        mainLayout.addView(appearance);

        // ---- PRIVACY ---- ghost, messages, hidden chats, lock, ads/tracking, location, distraction-free
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_privacy)));
        LinearLayout privacy = createGroupCard(context);
        privacy.addView(createMenuRow(context, R.drawable.ic_eye, I18n.t(context, R.string.ig_dialog_menu_ghost_settings), A_PRIVACY, () -> showGhostOptions(context), onCount(context, "isGhostSeen", "isGhostTyping", "isGhostStory", "isGhostLive", "isGhostScreenshot", "allowScreenshots", "isGhostViewOnce", "permanentViewMode", "readReceiptChats", "spoofLastSeen", "airplaneMode")));
        privacy.addView(createMenuRow(context, R.drawable.ic_chat, I18n.t(context, R.string.menu_messages), A_PRIVACY, () -> showMessagesOptions(context), onCount(context, "keepEphemeralMessages", "keepUnsentMessages", "keepEditHistory", "jumpToFirstMessage", "translateMessages", "bulkUnsend", "chatExport", "hideNotesTray", "hideDmBlend", "hideShareCreateGroup")));
        privacy.addView(createMenuRow(context, R.drawable.ic_eye_off, I18n.t(context, R.string.ig_hide_chats_title), A_PRIVACY, () -> showHideChatsOptions(context), onCount(context, "hideSpecificChats")));
        privacy.addView(createMenuRow(context, R.drawable.ic_shield, I18n.t(context, R.string.ig_dialog_misc_lock_section), A_PRIVACY, () -> showLockOptions(context), onCount(context, "lockDirectMessages", "lockWholeApp", "lockSavedCollections", "lockUseFingerprint")));
        privacy.addView(createMenuRow(context, R.drawable.ic_block, I18n.t(context, R.string.menu_ads_tracking), A_PRIVACY, () -> showAdOptions(context), onCount(context, "isAdBlockEnabled", "isAnalyticsBlocked", "disableTrackingLinks", "openLinksExternally", "removeMetaAI", "hideOnboardingPrompts")));
        privacy.addView(createMenuRow(context, R.drawable.ic_pin, I18n.t(context, R.string.ig_dialog_menu_location), A_PRIVACY, () -> showLocationOptions(context), onCount(context, "spoofLocation")));
        privacy.addView(createMenuRow(context, R.drawable.ic_notification, I18n.t(context, R.string.ig_dialog_menu_distraction_free), A_PRIVACY, () -> showDistractionOptions(context), onCount(context, "isExtremeMode", "disableStories", "disableFeed", "disableReels", "disableReelsExceptDM", "disableExplore", "disableComments")));
        mainLayout.addView(privacy);

        // ---- MEDIA ---- downloading, post-menu extras and uploads
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_media)));
        LinearLayout media = createGroupCard(context);
        media.addView(createMenuRow(context, R.drawable.ic_download, I18n.t(context, R.string.ig_dialog_menu_downloader), A_MEDIA, () -> showDownloaderOptions(context), onCount(context, "enablePostDownload", "enableStoryDownload", "enableReelDownload", "enableProfileDownload", "enableVoiceDownload", "enableStickerDownload", "enableDmMediaDownload", "offerAudioDownload", "saveInstants", "cacheStories", "downloaderUsernameFolder", "downloaderAddTimestamp")));
        media.addView(createMenuRow(context, R.drawable.ic_save, I18n.t(context, R.string.menu_post_menu), A_MEDIA, () -> showPostMenuOptions(context), onCount(context, "copyMediaLink", "offerCopyImage", "offerMediaInfo", "offerPlaybackSpeed", "maxUploadQuality", "uploadInstants")));
        mainLayout.addView(media);

        // ---- SOCIAL ---- likes, comments, profiles
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_social)));
        LinearLayout social = createGroupCard(context);
        social.addView(createMenuRow(context, R.drawable.ic_heart, I18n.t(context, R.string.menu_likes_comments), A_SOCIAL, () -> showLikesCommentsOptions(context), onCount(context, "disableDoubleTapLike", "likeUndo", "enableCopyComment", "commentSearch", "enableCaptionCopy")));
        social.addView(createMenuRow(context, R.drawable.ic_eye, I18n.t(context, R.string.menu_profiles), A_SOCIAL, () -> showProfilesOptions(context), onCount(context, "profileFollowsYouLabel", "fancyBioFont", "showFollowerToast", "followListExport", "followersHistory", "savedOnProfile", "hideThreadsButton")));
        mainLayout.addView(social);

        // ---- TOOLS ---- app & account, developer, backup, restart, cache, about
        mainLayout.addView(sectionHeader(context, I18n.t(context, R.string.feat_group_tools)));
        LinearLayout tools = createGroupCard(context);
        tools.addView(createMenuRow(context, R.drawable.ic_settings_gear, I18n.t(context, R.string.menu_app), A_TOOLS, () -> showAppOptions(context), onCount(context, "unlimitedAccounts", "fixNotificationRegistration", "showFeatureToasts")));
        tools.addView(createMenuRow(context, R.drawable.ic_tune, I18n.t(context, R.string.ig_dialog_menu_dev_options), A_TOOLS, () -> showDevOptions(context), onCount(context, "isDevEnabled", "removeBuildExpiredPopup")));
        tools.addView(createMenuRow(context, R.drawable.ic_save, I18n.t(context, R.string.ig_dialog_menu_backup_restore), A_TOOLS, () -> showBackupRestoreOptions(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_restart, I18n.t(context, R.string.ig_dialog_menu_restart), A_TOOLS, () -> showRestartSection(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_dialog_clear_cache), A_TOOLS, () -> showClearCacheSection(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_timer, I18n.t(context, R.string.ig_dialog_section_auto_clear_cache), A_TOOLS, () -> showAutoClearCacheSection(context)));
        tools.addView(createMenuRow(context, R.drawable.ic_info, I18n.t(context, R.string.ig_dialog_menu_about), A_TOOLS, () -> showAboutDialog(context)));
        mainLayout.addView(tools);

        // Footer Credit
        TextView footer = new TextView(context);
        footer.setText("@reso7200  ·  " + moduleVersion(context));
        footer.setTextColor(cText2(context));
        footer.setTextSize(13);
        footer.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 8));
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        mainLayout.addView(footer);

        return mainLayout;
    }

    private static TextView sectionHeader(Context context, String text) {
        TextView header = new TextView(context);
        header.setText(stripEdgeEmoji(text == null ? "" : text));
        header.setTextColor(cText2(context));
        header.setTextSize(13);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 6));
        return header;
    }

    private static LinearLayout createGroupCard(Context context) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, 0, 0, 0);
        return group;
    }

    /** labelWithEmoji carries an emoji since it's shared with the companion app's plain-text
     *  menu (which still wants it) — but here a real vector icon renders in the chip instead,
     *  so the emoji is stripped. Translators place it on either side of the text (leading in
     *  most locales, trailing in Arabic), so this strips from whichever end it's on rather than
     *  assuming a fixed position. */
    private static View createMenuRow(Context context, int iconRes, String labelWithEmoji, String accentHex, Runnable onClick) {
        return createMenuRow(context, iconRes, labelWithEmoji, accentHex, onClick, null);
    }

    /** "N on" / "Off" for a category, read live from FeatureFlags (counted every time the menu opens). */
    private static String onCount(Context context, String... flagNames) {
        return onCount(context, 0, flagNames);
    }

    /** Navigation: the switch count, plus "· Tab order changed" when the tab order isn't Instagram's. */
    private static String navCount(Context context) {
        String base = onCount(context, "hideTabHome", "hideTabReels", "hideTabCreate", "hideTabMessages",
                "hideTabActivity", "hideTabProfile", "disableSwipeToCamera");
        return ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.isCustomTabOrder(FeatureFlags.navTabOrder)
                ? base + " · " + I18n.t(context, R.string.nav_tab_order_changed) : base;
    }

    private static String onCount(Context context, int extra, String... flagNames) {
        int on = extra;
        for (String name : flagNames) {
            try {
                java.lang.reflect.Field f = FeatureFlags.class.getField(name);
                if (f.getType() == boolean.class && f.getBoolean(null)) on++;
            } catch (Throwable ignored) {}
        }
        return on > 0 ? I18n.t(context, R.string.feat_count_on, on) : I18n.t(context, R.string.feat_count_off);
    }

    // ==== FLAG-BOUND ROWS ====
    // Rows bound to a FeatureFlags boolean by field name (read/written by reflection, like onCount),
    // so a menu is just a list of rows and no index→flag switch has to be kept in step with it.

    private static boolean flagValue(String flag) {
        try {
            return FeatureFlags.class.getField(flag).getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setFlagValue(String flag, boolean value) {
        try {
            FeatureFlags.class.getField(flag).setBoolean(null, value);
        } catch (Throwable ignored) {}
    }

    /** Switch for FeatureFlags.{flag}: toggling sets the flag, saves, then runs {@code after} (may be null). */
    private static ToggleRow flagSwitch(Context context, int iconRes, String accentHex, int labelRes, String flag, Runnable after) {
        ToggleRow row = createSwitch(context, iconRes, accentHex, I18n.t(context, labelRes), flagValue(flag));
        row.setOnCheckedChangeListener((b, checked) -> {
            setFlagValue(flag, checked);
            SettingsManager.saveAllFlags();
            if (after != null) after.run();
        });
        return row;
    }

    private static ToggleRow flagSwitch(Context context, int iconRes, String accentHex, int labelRes, String flag) {
        return flagSwitch(context, iconRes, accentHex, labelRes, flag, null);
    }

    /** Switch for an Extras feature, with the label and icon from its ExtrasFeatures entry. */
    private static ToggleRow extraSwitch(Context context, String accentHex, String flag, Runnable after) {
        ExtrasFeatures.Entry e = ExtrasFeatures.entry(flag);
        return flagSwitch(context, e.icon, accentHex, e.label, flag, after);
    }

    private static ToggleRow extraSwitch(Context context, String accentHex, String flag) {
        return extraSwitch(context, accentHex, flag, null);
    }

    /**
     * Adds the "Enable/Disable all" master switch plus a card holding {@code rows} to layout. The
     * master flips every row (each row's own listener sets its flag); any row change re-syncs it.
     * Returns the card so a menu can append extra rows to it.
     */
    private static LinearLayout addMasterGroup(Context context, LinearLayout layout, ToggleRow... rows) {
        ToggleRow master = createSwitch(context, I18n.t(context, R.string.ig_dialog_enable_disable_all), areAllEnabled(rows));
        CompoundButton.OnCheckedChangeListener flipAll = (b, checked) -> {
            for (ToggleRow r : rows) r.setChecked(checked);
        };
        master.setOnCheckedChangeListener(flipAll);
        for (ToggleRow r : rows) {
            r.addOnCheckedChangeListener((b, checked) -> {
                master.setOnCheckedChangeListener(null);
                master.setChecked(areAllEnabled(rows));
                master.setOnCheckedChangeListener(flipAll);
            });
        }

        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, master));
        layout.addView(createDivider(context));
        LinearLayout group = cardOf(context, rows);
        layout.addView(group);
        return group;
    }

    /** A grouped card holding the given rows. */
    private static LinearLayout cardOf(Context context, View... rows) {
        LinearLayout group = card(context);
        for (View r : rows) group.addView(r);
        return group;
    }

    /** InstaEclipse's own version, read from the module APK (works inside Instagram's process). */
    private static String moduleVersion(Context context) {
        try {
            String apk = ps.reso.instaeclipse.Xposed.Module.moduleSourceDir;
            if (apk != null) {
                android.content.pm.PackageInfo pi = context.getPackageManager().getPackageArchiveInfo(apk, 0);
                if (pi != null && pi.versionName != null) return "v" + pi.versionName;
            }
        } catch (Throwable ignored) {}
        return I18n.t(context, R.string.version);
    }

    private static String hostVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Version + health line under the menu title: InstaEclipse version, Instagram version, and how
     * many enabled features actually hooked on this Instagram build. Tapping the health chip lists
     * the ones that did not, which is what to check after Instagram updates itself.
     */
    private static View buildStatusChips(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 14));

        LinearLayout versions = new LinearLayout(context);
        versions.setOrientation(LinearLayout.HORIZONTAL);
        versions.setGravity(Gravity.CENTER);
        versions.addView(statusChip(context, "InstaEclipse " + moduleVersion(context), cText2(context), null));
        versions.addView(statusChip(context, I18n.t(context, R.string.menu_chip_instagram, hostVersion(context)), cText2(context), null));
        box.addView(versions);

        java.util.Map<String, Boolean> status = FeatureStatusTracker.getStatus();
        int total = status.size(), active = 0;
        java.util.List<String> inactive = new java.util.ArrayList<>();
        synchronized (status) {
            for (java.util.Map.Entry<String, Boolean> e : status.entrySet()) {
                if (Boolean.TRUE.equals(e.getValue())) active++;
                else inactive.add(FeatureStatusTracker.getLabel(context, e.getKey()));
            }
        }
        final int finalActive = active;
        if (total > 0) {
            int color = active == total ? 0xFF30D158 : 0xFFFF9F0A;
            View health = statusChip(context, "●  " + I18n.t(context, R.string.menu_chip_active, active, total), color, v -> {
                StringBuilder msg = new StringBuilder();
                if (inactive.isEmpty()) {
                    msg.append(I18n.t(context, R.string.menu_inactive_all_good));
                } else {
                    msg.append(I18n.t(context, R.string.menu_inactive_hint)).append("\n");
                    java.util.Collections.sort(inactive);
                    for (String label : inactive) msg.append("\n•  ").append(label);
                }
                // Title is the count itself ("55/55 active"); the body says all-good or lists what failed.
                showSimpleDialog(context, I18n.t(context, R.string.menu_chip_active, finalActive, total), msg.toString());
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(context, 6);
            health.setLayoutParams(lp);
            box.addView(health);
        }
        return box;
    }

    private static TextView statusChip(Context context, String text, int textColor, View.OnClickListener onClick) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(12);
        chip.setSingleLine(true);
        chip.setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard(context));
        bg.setCornerRadius(dp(context, 14));
        bg.setStroke(1, cHairline(context));
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 3);
        lp.rightMargin = dp(context, 3);
        chip.setLayoutParams(lp);
        if (onClick != null) {
            chip.setClickable(true);
            chip.setOnClickListener(onClick);
        }
        return chip;
    }

    private static View createMenuRow(Context context, int iconRes, String labelWithEmoji, String accentHex,
                                      Runnable onClick, String subtitleText) {
        String label = stripEdgeEmoji(labelWithEmoji);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowRipple(context, 0));

        android.widget.ImageView iconView = new android.widget.ImageView(context);
        boolean isDestructive = "#FF453A".equalsIgnoreCase(accentHex) || "#ED4956".equalsIgnoreCase(accentHex);
        int tint = isDestructive ? C_DANGER : cText(context);
        Drawable icon = loadModuleIcon(iconRes, tint);
        if (icon != null) iconView.setImageDrawable(icon);
        iconView.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        iconLp.rightMargin = dp(context, 20);
        iconView.setLayoutParams(iconLp);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(tint);
        View labelBlock = labelView;
        if (subtitleText != null) {
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(labelView);
            TextView sub = new TextView(context);
            sub.setText(subtitleText);
            sub.setTextSize(12);
            sub.setTextColor(cText2(context));
            texts.addView(sub);
            labelBlock = texts;
        }
        labelBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView chevron = new android.widget.ImageView(context);
        Drawable chev = loadModuleIcon(R.drawable.ic_chevron_right, cText2(context));
        if (chev != null) chevron.setImageDrawable(chev);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18));
        chevLp.rightMargin = dp(context, 4);
        chevron.setLayoutParams(chevLp);
        chevron.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);

        row.addView(iconView);
        row.addView(labelBlock);
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
        View divider = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 8));
        divider.setLayoutParams(params);
        divider.setBackgroundColor(Color.TRANSPARENT);
        return divider;
    }

    private static View createHeaderDivider(Context context) {
        View line = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = dp(context, 16);
        params.rightMargin = dp(context, 16);
        line.setLayoutParams(params);
        line.setBackgroundColor(cHairline(context));
        return line;
    }

    private static View createHairline(Context context) {
        View line = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dp(context, 20), dp(context, 4), dp(context, 20), dp(context, 4));
        line.setLayoutParams(params);
        line.setBackgroundColor(cHairline(context));
        return line;
    }

    private static Drawable rowRipple(Context ctx, int cornerPx) {
        int pressedColor = cPressed(ctx);
        try {
            Drawable mask;
            if (cornerPx > 0) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.WHITE);
                g.setCornerRadius(cornerPx);
                mask = g;
            } else {
                mask = new ColorDrawable(Color.WHITE);
            }
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(pressedColor), null, mask);
        } catch (Throwable t) {
            StateListDrawable sld = new StateListDrawable();
            sld.addState(new int[]{android.R.attr.state_pressed}, roundedColor(pressedColor, cornerPx));
            sld.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            return sld;
        }
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout group = new LinearLayout(ctx);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 4);
        lp.bottomMargin = dp(ctx, 4);
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
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_restart_not_found), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            String packageName = context.getPackageName();
            ModuleLog.line("InstaEclipse: Restart failed for " + packageName + " - " + e.getMessage());
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_restart_failed, e.getMessage()), Toast.LENGTH_LONG).show();
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
                    ps.reso.instaeclipse.utils.ipc.CompanionBridge.launchActivity(instagramActivity, importIntent);
                } catch (Exception e) {
                    ModuleLog.line("InstaEclipse | Failed to start JsonImportActivity: " + e.getMessage());
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
                    String json;
                    try (java.io.InputStream input = new java.io.FileInputStream(source)) {
                        json = ps.reso.instaeclipse.utils.ipc.BrokerPayload.readConfig(input);
                    }
                    Intent exportIntent = new Intent();
                    exportIntent.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                    exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    exportIntent.putExtra("json_content", json);
                    ps.reso.instaeclipse.utils.ipc.CompanionBridge.launchActivity(instagramActivity, exportIntent);
                } catch (Exception e) {
                    showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_failed_read_config, e.getMessage()));
                }
            } else {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
            }
        }));

        layout.addView(createActionRow(context, R.drawable.ic_restart, I18n.t(context, R.string.ig_dialog_dev_restore_default_config), "#FF9F0A", v -> {
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_dialog_dev_restore_default_config))
                    .message(I18n.t(context, R.string.ig_dialog_dev_restore_default_config_confirm));
            dlg.secondary(I18n.t(context, R.string.ig_dialog_cancel), null);
            dlg.primary(I18n.t(context, R.string.ig_dialog_yes), () ->
                    ConfigManager.restoreDefaultConfig(context, Module.moduleSourceDir));
            dlg.show();
        }));

        layout.addView(createDivider(context));

        ToggleRow buildExpiredSwitch = createSwitch(context, R.drawable.ic_block, "#FF453A", I18n.t(context, R.string.ig_dialog_dev_remove_build_expired), FeatureFlags.removeBuildExpiredPopup);
        buildExpiredSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.removeBuildExpiredPopup = isChecked;
            SettingsManager.saveAllFlags();
        });
        layout.addView(buildExpiredSwitch);
        layout.addView(createActionRow(context, R.drawable.ic_movie, spoofDeviceLabel(context), "#0A84FF",
                v -> showSpoofDeviceDialog(context, (TextView) findFirstText(v))));

        // Save current dev mode flag when dialog is closed
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_dev_options), layout, SettingsManager::saveAllFlags);
    }

    static String[] spoofDeviceNames(Context context) {
        String[] ids = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES;
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            String n = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.deviceName(ids[i]);
            names[i] = n != null ? n : I18n.t(context, R.string.ig_dialog_extras_share_domain_off);
        }
        return names;
    }

    private static String spoofDeviceLabel(Context context) {
        int i = java.util.Arrays.asList(ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES).indexOf(FeatureFlags.spoofDevice);
        return I18n.t(context, R.string.ig_spoof_device) + "  ·  " + spoofDeviceNames(context)[Math.max(i, 0)];
    }

    /** Which device Instagram sees (FeatureFlags.spoofDevice); applies on the next start. */
    private static void showSpoofDeviceDialog(Context context, TextView label) {
        String[] ids = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES;
        int current = Math.max(java.util.Arrays.asList(ids).indexOf(FeatureFlags.spoofDevice), 0);
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_spoof_device))
                .message(I18n.t(context, R.string.ig_spoof_device_note))
                .choices(spoofDeviceNames(context), current, which -> {
                    FeatureFlags.spoofDevice = ids[which];
                    SettingsManager.saveAllFlags();
                    if (label != null) label.setText(spoofDeviceLabel(context));
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context,
                            I18n.t(context, R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
                })
                .show();
    }

    /** Viewer for the persistent Unsent Messages log (captured by KeepUnsentMessagesHook). */
    public static void showThreadUnsent(Context context, String threadId) { showThreadUnsent(context, threadId, null); }

    /** Styled per-thread Unsent Messages viewer, opened from the in-thread bin button. */
    public static void showThreadUnsent(Context context, String threadId, String chatName) {
        LinearLayout layout = createSwitchLayout(context);

        // Never widen to every chat: an unknown thread (null) shows only the entries that were
        // logged without a chat, so one chat's unsent messages can't appear inside another.
        final java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries =
                ps.reso.instaeclipse.utils.ghost.UnsentLog.getForThread(threadId != null ? threadId : "");

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault());
        final String who = (chatName != null && !chatName.trim().isEmpty()) ? chatName.trim()
                : (threadId != null ? threadId : "unknown");

        if (!entries.isEmpty()) {
            layout.addView(createActionRow(context, R.drawable.ic_upload, I18n.t(context, R.string.ig_dialog_unsent_export), "#0A84FF", v -> {
                String path = exportUnsentJson(context, who, entries);
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, path != null
                        ? I18n.t(context, R.string.ig_dialog_unsent_exported, path)
                        : I18n.t(context, R.string.ig_toast_download_failed, "export"), Toast.LENGTH_LONG).show();
            }));
            layout.addView(createActionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_dialog_unsent_clear), "#FF453A", v -> {
                try {
                    IeDialog dlg = new IeDialog(context)
                            .title(I18n.t(context, R.string.ig_dialog_unsent_clear))
                            .message(I18n.t(context, R.string.ig_dialog_unsent_clear_confirm));
                    dlg.secondary(context.getString(android.R.string.cancel), null);
                    dlg.primary(I18n.t(context, R.string.ig_dialog_unsent_clear_yes), () -> {
                        ps.reso.instaeclipse.utils.ghost.UnsentLog.clearThread(threadId != null ? threadId : "");
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_unsent_cleared), Toast.LENGTH_SHORT).show();
                        showThreadUnsent(context, threadId);
                    });
                    dlg.show();
                } catch (Throwable ignored) {}
            }));
            layout.addView(createDivider(context));
        }

        if (entries.isEmpty()) {
            layout.addView(createInfoSection(context, I18n.t(context, R.string.ig_dialog_unsent_title),
                    I18n.t(context, R.string.ig_dialog_unsent_empty)));
        } else {
            // One bordered card per deleted message: sender (accent) and time on top, text below.
            for (int i = 0; i < entries.size(); i++) {
                ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e = entries.get(i);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(context, 14);
                row.setPadding(pad, dp(context, 11), pad, dp(context, 12));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(cCard(context));
                bg.setCornerRadius(R_CARD);
                bg.setStroke(Math.max(1, dp(context, 1)), cHairline(context));
                row.setBackground(bg);

                LinearLayout meta = new LinearLayout(context);
                meta.setGravity(Gravity.CENTER_VERTICAL);
                TextView sender = new TextView(context);
                sender.setText(e.sender);
                if (e.sender.isEmpty()) sender.setVisibility(View.GONE);
                sender.setTextColor(cText(context));
                sender.setTextSize(13);
                sender.setTypeface(Typeface.DEFAULT_BOLD);
                sender.setSingleLine(true);
                sender.setEllipsize(android.text.TextUtils.TruncateAt.END);
                meta.addView(sender, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView when = new TextView(context);
                when.setText(fmt.format(new java.util.Date(e.time)));
                when.setTextColor(cText2(context));
                when.setTextSize(12);
                when.setPadding(e.sender.isEmpty() ? 0 : dp(context, 8), 0, 0, 0);
                meta.addView(when);

                TextView body = new TextView(context);
                body.setText(e.text);
                body.setTextColor(cText(context));
                body.setTextSize(15);
                body.setPadding(0, dp(context, 5), 0, 0);
                body.setTextIsSelectable(true);
                row.addView(meta);
                row.addView(body);

                row.addView(createActionRow(context, R.drawable.ic_delete,
                        I18n.t(context, R.string.ie_unsent_remove_copy), "#FF453A", v -> {
                    IeDialog confirmation = new IeDialog(context)
                            .title(I18n.t(context, R.string.ie_unsent_remove_copy))
                            .message(I18n.t(context, R.string.ie_unsent_remove_copy_confirm));
                    confirmation.secondary(context.getString(android.R.string.cancel), null);
                    confirmation.primary(I18n.t(context, R.string.ie_unsent_remove_copy), () -> {
                                ps.reso.instaeclipse.utils.ghost.UnsentLog.RemovalResult result =
                                        ps.reso.instaeclipse.utils.ghost.UnsentLog.removeEntry(e);
                                int notice = result == ps.reso.instaeclipse.utils.ghost.UnsentLog.RemovalResult.REMOVED
                                        ? R.string.ie_unsent_copy_removed
                                        : result == ps.reso.instaeclipse.utils.ghost.UnsentLog.RemovalResult.STALE
                                        ? R.string.ie_unsent_copy_stale : R.string.ie_unsent_copy_remove_failed;
                                if (result != ps.reso.instaeclipse.utils.ghost.UnsentLog.RemovalResult.FAILED)
                                    layout.removeView(row);
                                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context,
                                        I18n.t(context, notice), Toast.LENGTH_SHORT).show();
                            });
                    confirmation.show();
                }));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, i == 0 ? dp(context, 4) : dp(context, 8), 0, 0);
                layout.addView(row, lp);
            }
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
            ModuleLog.line("(IE|UnsentExport) " + t.getMessage());
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
            String name = ps.reso.instaeclipse.utils.ghost.ThreadNames.resolve(threadId);
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
                IeDialog dlg = new IeDialog(context)
                        .title(I18n.t(context, R.string.ig_dialog_unsent_clear))
                        .message(I18n.t(context, R.string.ig_dialog_unsent_clear_confirm));
                dlg.secondary(context.getString(android.R.string.cancel), null);
                dlg.primary(I18n.t(context, R.string.ig_dialog_unsent_clear_yes), () -> {
                    ps.reso.instaeclipse.utils.ghost.UnsentLog.clear();
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_unsent_cleared), Toast.LENGTH_SHORT).show();
                    showUnsentMessages(context); // refresh
                });
                dlg.show();
            } catch (Throwable ignored) {}
        }));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_unsent_title), layout, () -> {});
    }

    private static void showGhostOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(createActionRow(context, R.drawable.ic_tune, I18n.t(context, R.string.ig_dialog_customize_quick_toggle), "#5E5CE6", v -> showGhostQuickToggleOptions(context)));

        // Each ghost toggle applies at once and refreshes the ghost emoji next to the inbox.
        Runnable refreshGhostEmoji = () -> {
            Activity activity = UIHookManager.getCurrentActivity();
            if (activity != null) {
                GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
            }
        };
        addMasterGroup(context, layout,
                flagSwitch(context, R.drawable.ic_eye_off, "#5E5CE6", R.string.ig_dialog_ghost_hide_dm_seen, "isGhostSeen", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_chat, "#5E5CE6", R.string.ig_dialog_ghost_hide_typing, "isGhostTyping", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_story_ring, "#5E5CE6", R.string.ig_dialog_ghost_hide_story_views, "isGhostStory", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_live, "#5E5CE6", R.string.ig_dialog_ghost_hide_live_presence, "isGhostLive", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_camera, "#5E5CE6", R.string.ig_dialog_ghost_bypass_screenshot, "isGhostScreenshot", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_camera, "#5E5CE6", R.string.ig_dialog_ghost_allow_screenshots_dms, "allowScreenshots", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_eye_off, "#5E5CE6", R.string.ig_dialog_ghost_hide_view_once, "isGhostViewOnce", refreshGhostEmoji),
                flagSwitch(context, R.drawable.ic_eye, "#5E5CE6", R.string.ig_dialog_ghost_permanent_view_once, "permanentViewMode", refreshGhostEmoji));

        // Outside the master switch: last-seen spoofing and airplane mode.
        layout.addView(createDivider(context));
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_eye, "#5E5CE6", R.string.ig_dialog_ghost_read_receipt_chats, "readReceiptChats"),
                flagSwitch(context, R.drawable.ic_timer, "#5E5CE6", R.string.ig_dialog_misc_spoof_last_seen, "spoofLastSeen"),
                // Airplane mode takes effect at once: stop / restart the live MQTT connection.
                extraSwitch(context, "#5E5CE6", "airplaneMode", ps.reso.instaeclipse.mods.extras.AirplaneModeHook::apply)));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_ghost_mode), layout, () -> {});
    }

    /** DM extras: keep disappearing/unsent messages, edit history, chat tools, inbox cleanup. */
    private static void showMessagesOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_timer, "#5E5CE6", R.string.ig_dialog_ghost_keep_disappearing, "keepEphemeralMessages"),
                flagSwitch(context, R.drawable.ic_chat, "#5E5CE6", R.string.ig_dialog_ghost_keep_unsent, "keepUnsentMessages", () -> {
                    ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook.onSettingsChanged();
                    ps.reso.instaeclipse.mods.ghost.ConversationMenuHook.refreshAll();
                }),
                flagSwitch(context, R.drawable.ic_chat, "#5E5CE6", R.string.ie_keep_unsent_in_chat, "keepUnsentInChat",
                        ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook::onSettingsChanged),
                flagSwitch(context, R.drawable.ic_delete, "#5E5CE6", R.string.ie_show_deleted_button, "showDeletedMessagesButton",
                        ps.reso.instaeclipse.mods.ghost.ConversationMenuHook::refreshAll),
                createActionRow(context, R.drawable.ic_chat, I18n.t(context, R.string.ie_chat_actions), "#5E5CE6",
                        v -> ps.reso.instaeclipse.mods.ghost.ConversationMenuHook.show(activityOf(context))),
                createActionRow(context, R.drawable.ic_chat, I18n.t(context, R.string.ig_dialog_unsent_title), "#5E5CE6", v -> showUnsentMessages(context)),
                flagSwitch(context, R.drawable.ic_chat, "#5E5CE6", R.string.ig_dialog_ghost_edit_history, "keepEditHistory"),
                flagSwitch(context, R.drawable.ic_jump_first, "#5E5CE6", R.string.ig_dialog_ghost_jump_first, "jumpToFirstMessage"),
                flagSwitch(context, R.drawable.ic_chat, "#5E5CE6", R.string.ig_dialog_translate_messages, "translateMessages"),
                flagSwitch(context, R.drawable.ic_chat, "#5E5CE6", R.string.ig_dialog_bulk_unsend, "bulkUnsend"),
                flagSwitch(context, R.drawable.ic_download, "#5E5CE6", R.string.ig_dialog_ghost_chat_export, "chatExport"),
                flagSwitch(context, R.drawable.ic_block, "#5E5CE6", R.string.ig_dialog_distraction_hide_notes_tray, "hideNotesTray"),
                flagSwitch(context, R.drawable.ic_block, "#5E5CE6", R.string.ig_dialog_hide_dm_blend, "hideDmBlend"),
                flagSwitch(context, R.drawable.ic_block, "#5E5CE6", R.string.ig_dialog_misc_hide_share_create_group, "hideShareCreateGroup")));
        layout.addView(createInfoSection(context, "", I18n.t(context, R.string.ie_keep_unsent_in_chat_hint)));
        showSectionDialog(context, I18n.t(context, R.string.menu_messages), layout, () -> {});
    }


    private static void showAdOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        LinearLayout adCard = addMasterGroup(context, layout,
                flagSwitch(context, R.drawable.ic_shield, "#FF453A", R.string.ig_dialog_ad_block_ads, "isAdBlockEnabled"),
                flagSwitch(context, R.drawable.ic_shield, "#FF453A", R.string.ig_dialog_ad_block_analytics, "isAnalyticsBlocked"),
                flagSwitch(context, R.drawable.ic_link, "#FF453A", R.string.ig_dialog_ad_disable_tracking, "disableTrackingLinks"),
                flagSwitch(context, R.drawable.ic_link, "#FF453A", R.string.ig_dialog_open_links_externally, "openLinksExternally"));
        // The master group's card ends with the custom share-domain row, as before.
        adCard.addView(createActionRow(context, R.drawable.ic_link, shareDomainLabel(context), "#FF453A",
                v -> showShareDomainDialog(context, (TextView) findFirstText(v))));

        // Outside the master switch.
        layout.addView(createDivider(context));
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_sparkle, "#FF453A", R.string.ig_dialog_misc_remove_meta_ai, "removeMetaAI"),
                extraSwitch(context, "#FF453A", "hideOnboardingPrompts")));

        showSectionDialog(context, I18n.t(context, R.string.menu_ads_tracking), layout, () -> {});
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
                IeDialog builder = new IeDialog(context)
                        .title(I18n.t(context, R.string.ig_dialog_distraction_extreme_title))
                        .message(I18n.t(context, R.string.ig_dialog_distraction_extreme_message));
                Runnable cancelExtreme = () -> extremeModeSwitch.setChecked(false);
                builder.secondary(I18n.t(context, R.string.ig_dialog_cancel), cancelExtreme);
                builder.onClose(cancelExtreme); // ✕ = Cancel
                builder.primary(I18n.t(context, R.string.ig_dialog_yes), () -> {
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

    /** Bottom navigation bar (#238): hide tabs, reorder them, pick the startup tab, gestures. */
    private static void showNavigationOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(sectionHeader(context, I18n.t(context, R.string.menu_nav_tabs)));
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_block, "#30D158", R.string.ig_nav_tab_home, "hideTabHome"),
                flagSwitch(context, R.drawable.ic_block, "#30D158", R.string.ig_nav_tab_reels, "hideTabReels"),
                flagSwitch(context, R.drawable.ic_block, "#30D158", R.string.ig_nav_tab_create, "hideTabCreate"),
                flagSwitch(context, R.drawable.ic_block, "#30D158", R.string.ig_nav_tab_messages, "hideTabMessages"),
                flagSwitch(context, R.drawable.ic_block, "#30D158", R.string.ig_nav_tab_activity, "hideTabActivity"),
                flagSwitch(context, R.drawable.ic_block, "#30D158", R.string.ig_nav_tab_profile, "hideTabProfile")));

        layout.addView(sectionHeader(context, I18n.t(context, R.string.menu_nav_order)));
        layout.addView(cardOf(context,
                createActionRow(context, R.drawable.ic_tune, I18n.t(context, R.string.ig_nav_tab_order), "#30D158",
                        v -> showTabOrderDialog(context)),
                createActionRow(context, R.drawable.ic_tune, startupTabLabel(context), "#30D158",
                        v -> showStartupTabDialog(context, (TextView) findFirstText(v)))));

        layout.addView(sectionHeader(context, I18n.t(context, R.string.menu_nav_gestures)));
        layout.addView(cardOf(context, extraSwitch(context, "#30D158", "disableSwipeToCamera")));

        showSectionDialog(context, I18n.t(context, R.string.menu_navigation), layout, () -> {});
    }

    static String[] startupTabNames(Context context) {
        return new String[]{
                I18n.t(context, R.string.ig_dialog_extras_share_domain_off),
                I18n.t(context, R.string.ig_nav_name_home),
                I18n.t(context, R.string.ig_nav_name_reels),
                I18n.t(context, R.string.ig_nav_name_messages),
                I18n.t(context, R.string.ig_nav_name_search),
                I18n.t(context, R.string.ig_nav_name_profile),
                I18n.t(context, R.string.ig_nav_name_activity)};
    }

    private static String startupTabLabel(Context context) {
        int i = java.util.Arrays.asList(ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS).indexOf(FeatureFlags.startupTab);
        return I18n.t(context, R.string.ig_dialog_extras_startup_tab) + "  ·  " + startupTabNames(context)[Math.max(i, 0)];
    }

    /** Which tab Instagram opens on (FeatureFlags.startupTab). */
    private static void showStartupTabDialog(Context context, TextView label) {
        String[] tabs = ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS;
        int current = Math.max(java.util.Arrays.asList(tabs).indexOf(FeatureFlags.startupTab), 0);
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_dialog_extras_startup_tab))
                .choices(startupTabNames(context), current, which -> {
                    FeatureFlags.startupTab = tabs[which];
                    SettingsManager.saveAllFlags();
                    if (label != null) label.setText(startupTabLabel(context));
                })
                .show();
    }

    /** #238: reorder the bottom-nav tabs (FeatureFlags.navTabOrder). */
    private static void showTabOrderDialog(Context context) {
        final String[] ids = ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.ORDERABLE_TABS;
        final java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("feed_tab", I18n.t(context, R.string.ig_nav_name_home));
        labels.put("clips_tab", I18n.t(context, R.string.ig_nav_name_reels));
        labels.put("search_tab", I18n.t(context, R.string.ig_nav_name_search));
        labels.put("creation_tab", I18n.t(context, R.string.ig_nav_name_create));
        labels.put("direct_tab", I18n.t(context, R.string.ig_nav_name_messages));
        labels.put("news_tab", I18n.t(context, R.string.ig_nav_name_activity));
        labels.put("profile_tab", I18n.t(context, R.string.ig_nav_name_profile));
        final java.util.List<String> order = new java.util.ArrayList<>();
        for (String n : FeatureFlags.navTabOrder.split(","))
            if (java.util.Arrays.asList(ids).contains(n.trim()) && !order.contains(n.trim())) order.add(n.trim());
        for (String n : ids) if (!order.contains(n)) order.add(n);
        final IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_nav_tab_order));
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dlg.dp(16), dlg.dp(2), dlg.dp(6), dlg.dp(2));
                row.setBackground(dlg.cardBg());
                TextView name = new TextView(context);
                name.setText((i + 1) + ".  " + labels.get(order.get(i)));
                name.setTextColor(dlg.text);
                name.setTextSize(15);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                for (final int dir : new int[]{-1, 1}) {
                    TextView b = new TextView(context);
                    b.setText(dir < 0 ? "↑" : "↓");
                    b.setTextSize(20);
                    b.setGravity(Gravity.CENTER);
                    boolean on = dir < 0 ? idx > 0 : idx < order.size() - 1;
                    b.setTextColor(on ? dlg.text : (dlg.sub & 0x00FFFFFF) | 0x55000000);
                    if (on) b.setOnClickListener(v -> {
                        java.util.Collections.swap(order, idx, idx + dir);
                        render[0].run();
                    });
                    row.addView(b, new LinearLayout.LayoutParams(dlg.dp(44), dlg.dp(44)));
                }
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dlg.dp(8);
                list.addView(row, rlp);
            }
        };
        render[0].run();
        dlg.view(list);
        TextView hint = new TextView(context);
        hint.setText(I18n.t(context, R.string.ig_nav_tab_order_hint));
        hint.setTextColor(dlg.sub);
        hint.setTextSize(13);
        dlg.view(hint);
        dlg.secondary(I18n.t(context, R.string.ig_nav_tab_order_reset), () -> { FeatureFlags.navTabOrder = ""; SettingsManager.saveAllFlags(); });
        dlg.secondary(context.getString(android.R.string.cancel), null);
        dlg.primary(context.getString(android.R.string.ok), () -> { FeatureFlags.navTabOrder = String.join(",", order); SettingsManager.saveAllFlags(); });
        dlg.show();
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


    /** Feed: what shows up in it (clean-feed filters) and how posts render. */
    private static void showFeedOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        layout.addView(sectionHeader(context, I18n.t(context, R.string.ig_dialog_menu_clean_feed)));
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_hide_suggested, "hideSuggestionsInFeed"),
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_hide_threads, "hideThreadsSuggestions"),
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_hide_liked, "hideLikedPosts"),
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_hide_community_notes, "hideCommunityNotes"),
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_hide_plus, "hidePlusUpsells"),
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_following_only, "followingOnlyFeed"),
                extraSwitch(context, "#64D2FF", "hideStoriesTray"),
                flagSwitch(context, R.drawable.ic_block, "#64D2FF", R.string.ig_dialog_misc_disable_discover_people, "disableDiscoverPeople")));

        // feat_options reads "Options:" (written for the companion app), so drop the colon for a header.
        layout.addView(sectionHeader(context, I18n.t(context, R.string.feat_options).replaceAll("[:：]\\s*$", "")));
        // Date format: only used (and tappable) while "Show exact dates" is on (#265).
        View tsFormat = createActionRow(context, R.drawable.ic_timer, timestampFormatLabel(context), "#64D2FF",
                v -> showTimestampFormatDialog(context, (TextView) findFirstText(v)));
        setRowEnabled(tsFormat, FeatureFlags.exactTimestamps);
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_sparkle, "#64D2FF", R.string.ig_dialog_clean_feed_expand_captions, "expandCaptions"),
                flagSwitch(context, R.drawable.ic_timer, "#64D2FF", R.string.ig_dialog_misc_exact_timestamps, "exactTimestamps",
                        () -> setRowEnabled(tsFormat, FeatureFlags.exactTimestamps)),
                tsFormat,
                extraSwitch(context, "#64D2FF", "highResolutionImages"),
                flagSwitch(context, R.drawable.ic_search, "#64D2FF", R.string.ig_dialog_misc_photo_zoom, "enablePhotoZoom"),
                flagSwitch(context, R.drawable.ic_movie, "#64D2FF", R.string.ig_dialog_misc_disable_video_autoplay, "disableVideoAutoPlay")));

        showSectionDialog(context, I18n.t(context, R.string.menu_feed), layout, () -> {});
    }

    private static View createDropdownRow(Context context, int iconRes, String title,
                                          String[] labels, int[] values, int currentValue,
                                          java.util.function.IntConsumer onSelect) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowRipple(context, 0));

        if (iconRes != 0) {
            ImageView iconView = new ImageView(context);
            Drawable icon = loadModuleIcon(iconRes, cText(context));
            if (icon != null) iconView.setImageDrawable(icon);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
            iconLp.rightMargin = dp(context, 16);
            iconView.setLayoutParams(iconLp);
            iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            row.addView(iconView);
        }

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(cText(context));
        row.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(context, 12), dp(context, 6), dp(context, 10), dp(context, 6));

        GradientDrawable pillBg = new GradientDrawable();
        boolean dark = isDarkTheme(context);
        pillBg.setColor(dark ? 0x22FFFFFF : 0x0F000000);
        pillBg.setCornerRadius(dp(context, 10));
        pillBg.setStroke(1, dark ? 0x33FFFFFF : 0x1F000000);
        pill.setBackground(pillBg);

        int currentIdx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == currentValue) {
                currentIdx = i;
                break;
            }
        }

        TextView valueText = new TextView(context);
        valueText.setText(labels[currentIdx]);
        valueText.setTextSize(13);
        valueText.setTextColor(cText(context));
        valueText.setTypeface(null, Typeface.BOLD);
        pill.addView(valueText);

        ImageView chevron = new ImageView(context);
        Drawable dropArrow = loadModuleIcon(R.drawable.ic_chevron_right, cText2(context));
        if (dropArrow != null) {
            chevron.setImageDrawable(dropArrow);
            chevron.setRotation(90f);
        }
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(context, 16), dp(context, 16));
        chevLp.leftMargin = dp(context, 4);
        chevron.setLayoutParams(chevLp);
        chevron.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pill.addView(chevron);

        row.addView(pill);

        final int[] curIdx = {currentIdx};
        Runnable openDropdown = () -> {
            boolean shownPopup = false;
            try {
                android.widget.PopupMenu popup = new android.widget.PopupMenu(context, pill);
                for (int i = 0; i < labels.length; i++) {
                    popup.getMenu().add(0, i, i, labels[i]);
                }
                popup.setOnMenuItemClickListener(item -> {
                    int selected = item.getItemId();
                    curIdx[0] = selected;
                    valueText.setText(labels[selected]);
                    onSelect.accept(values[selected]);
                    return true;
                });
                popup.show();
                shownPopup = true;
            } catch (Throwable ignored) {}

            if (!shownPopup) {
                new IeDialog(context)
                        .title(title)
                        .choices(labels, curIdx[0], which -> {
                            curIdx[0] = which;
                            valueText.setText(labels[which]);
                            onSelect.accept(values[which]);
                        })
                        .show();
            }
        };

        row.setOnClickListener(v -> openDropdown.run());
        pill.setOnClickListener(v -> openDropdown.run());

        return row;
    }

    /** Reels & Stories: forced reel quality, reel scrolling/scrubber, story behaviour. */
    private static void showReelsOptions(Context context) {
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

        layout.addView(cardOf(context,
                createDropdownRow(context, R.drawable.ic_movie,
                        I18n.t(context, R.string.ig_dialog_quality_force_reels),
                        labels, values, FeatureFlags.forceReelQuality,
                        val -> {
                            FeatureFlags.forceReelQuality = val;
                            SettingsManager.saveAllFlags();
                        }),
                extraSwitch(context, "#FF375F", "reelsAutoScroll"),
                extraSwitch(context, "#FF375F", "reelsLockScroll"),
                flagSwitch(context, R.drawable.ic_movie, "#FF375F", R.string.ig_dialog_downloader_reel_scrubber, "forceReelScrubber"),
                flagSwitch(context, R.drawable.ic_movie, "#FF375F", R.string.ig_dialog_reels_hold_left_slow, "holdLeftSlowMotion"),
                flagSwitch(context, R.drawable.ic_block, "#FF375F", R.string.ig_dialog_misc_disable_repost, "disableRepost")));

        showSectionDialog(context, I18n.t(context, R.string.menu_reels), layout, () -> {});
    }

    private static void showStoriesOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_story_ring, "#FF375F", R.string.ig_dialog_misc_disable_story_autoswipe, "disableStoryFlipping"),
                flagSwitch(context, R.drawable.ic_at, "#FF375F", R.string.ig_dialog_misc_view_story_mentions, "enableStoryMentions"),
                flagSwitch(context, R.drawable.ic_link, "#FF375F", R.string.ig_dialog_misc_story_links, "storyLinks"),
                flagSwitch(context, R.drawable.ic_info, "#FF375F", R.string.ig_dialog_misc_story_info, "storyInfo"),
                flagSwitch(context, R.drawable.ic_search, "#FF375F", R.string.ig_dialog_misc_viewer_search, "storyViewerSearch")));
        View ringRow = createActionRow(context, R.drawable.ic_palette, storyRingColorLabel(context).toString(), "#FF375F",
                v -> showStoryRingColorDialog(context, (TextView) findFirstText(v)));
        View ringText = findFirstText(ringRow);
        if (ringText instanceof TextView tv) tv.setText(storyRingColorLabel(context)); // with the color swatch
        layout.addView(cardOf(context,
                createActionRow(context, R.drawable.ic_story_ring, storyTraySizeLabel(context), "#FF375F",
                        v -> showStoryTraySizeDialog(context, (TextView) findFirstText(v))),
                ringRow));

        showSectionDialog(context, I18n.t(context, R.string.menu_stories), layout, () -> {});
    }

    public static final int[] STORY_TRAY_SIZES = {100, 90, 80, 70, 60, 50};
    public static final String[] STORY_RING_PRESETS = {"#3897F0", "#A855F7", "#FF3B30", "#FFD60A", "#FFFFFF",
            "#FF0066,#7638FA", "#00C6FF,#0072FF"};

    private static String storyTraySizeLabel(Context context) {
        return I18n.t(context, R.string.ig_story_tray_size) + "  ·  " + FeatureFlags.storyTrayScale + "%";
    }

    /** Story tray size (#283): applies at once, the tray resizes on its next layout. */
    private static void showStoryTraySizeDialog(Context context, TextView label) {
        CharSequence[] rows = new CharSequence[STORY_TRAY_SIZES.length];
        int checked = 0;
        for (int i = 0; i < rows.length; i++) {
            rows[i] = STORY_TRAY_SIZES[i] + "%";
            if (STORY_TRAY_SIZES[i] == FeatureFlags.storyTrayScale) checked = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_story_tray_size))
                .choices(rows, checked, which -> {
                    FeatureFlags.storyTrayScale = STORY_TRAY_SIZES[which];
                    SettingsManager.saveAllFlags();
                    if (label != null) label.setText(storyTraySizeLabel(context));
                })
                .show();
    }

    /** A color value shown as swatch(es) and its hex. */
    public static CharSequence storyRingSwatch(String value) {
        int[] colors = ps.reso.instaeclipse.mods.ui.StoryTrayHook.parseColors(value);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (colors != null) for (int c : colors) {
            int start = sb.length();
            sb.append("\u25CF");
            sb.setSpan(new android.text.style.ForegroundColorSpan(c), start, sb.length(), 0);
        }
        return sb.append("  ").append(value.replace(",", " \u2192 "));
    }

    private static CharSequence storyRingColorLabel(Context context) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
                I18n.t(context, R.string.ig_story_ring_color) + "  ·  ");
        return FeatureFlags.storyRingColor.isEmpty()
                ? sb.append(I18n.t(context, R.string.ig_story_ring_default))
                : sb.append(storyRingSwatch(FeatureFlags.storyRingColor));
    }

    /** Unseen story ring color (#283): Instagram's, a preset, or a custom one / two-color gradient. */
    private static void showStoryRingColorDialog(Context context, TextView label) {
        CharSequence[] rows = new CharSequence[STORY_RING_PRESETS.length + 2];
        rows[0] = I18n.t(context, R.string.ig_story_ring_default);
        int checked = FeatureFlags.storyRingColor.isEmpty() ? 0 : rows.length - 1;
        for (int i = 0; i < STORY_RING_PRESETS.length; i++) {
            rows[i + 1] = storyRingSwatch(STORY_RING_PRESETS[i]);
            if (STORY_RING_PRESETS[i].equalsIgnoreCase(FeatureFlags.storyRingColor)) checked = i + 1;
        }
        rows[rows.length - 1] = checked == rows.length - 1
                ? new android.text.SpannableStringBuilder(I18n.t(context, R.string.ig_story_ring_custom) + "  ").append(storyRingSwatch(FeatureFlags.storyRingColor))
                : I18n.t(context, R.string.ig_story_ring_custom);
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_story_ring_color))
                .choices(rows, checked, which -> {
                    if (which == rows.length - 1) { showCustomStoryRingColor(context, label); return; }
                    saveStoryRingColor(context, which == 0 ? "" : STORY_RING_PRESETS[which - 1], label);
                })
                .show();
    }

    private static void showCustomStoryRingColor(Context context, TextView label) {
        try {
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_story_ring_custom))
                    .message(I18n.t(context, R.string.ig_story_ring_custom_hint));
            final android.widget.EditText input = dlg.input("#3897F0");
            input.setText(FeatureFlags.storyRingColor);
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                String v = input.getText().toString().trim().replace(" ", "");
                if (ps.reso.instaeclipse.mods.ui.StoryTrayHook.parseColors(v) == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_ring_invalid), Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] parts = v.split(",");
                StringBuilder norm = new StringBuilder();
                for (String p : parts) norm.append(norm.length() > 0 ? "," : "").append(p.startsWith("#") ? p : "#" + p);
                saveStoryRingColor(context, norm.toString().toUpperCase(java.util.Locale.ROOT), label);
            });
            dlg.show();
        } catch (Throwable ignored) {}
    }

    private static void saveStoryRingColor(Context context, String value, TextView label) {
        FeatureFlags.storyRingColor = value;
        SettingsManager.saveAllFlags();
        if (label != null) label.setText(storyRingColorLabel(context));
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context,
                I18n.t(context, R.string.ig_toast_restart_manual), Toast.LENGTH_SHORT).show();
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

        // 6. Lockable Saved collections (#219) — 🔒 button in a collection; uses the code above.
        ToggleRow lockColl = createSwitch(context, R.drawable.ic_shield, "#BF5AF2",
                I18n.t(context, R.string.ig_locked_coll_setting_short), FeatureFlags.lockSavedCollections);
        lockColl.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.lockSavedCollections = checked;
            SettingsManager.saveAllFlags();
        });
        cardView.addView(lockColl);

        return cardView;
    }

    /** Likes & Comments: like gestures, copy/search in comments and captions. */
    private static void showLikesCommentsOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_heart, A_SOCIAL, R.string.ig_dialog_misc_disable_double_tap_like, "disableDoubleTapLike"),
                flagSwitch(context, R.drawable.ic_heart, A_SOCIAL, R.string.ig_dialog_misc_like_undo, "likeUndo"),
                flagSwitch(context, R.drawable.ic_content_copy, A_SOCIAL, R.string.ig_dialog_misc_copy_comment, "enableCopyComment"),
                flagSwitch(context, R.drawable.ic_search, A_SOCIAL, R.string.ig_dialog_misc_comment_search, "commentSearch"),
                flagSwitch(context, R.drawable.ic_content_copy, A_SOCIAL, R.string.ig_dialog_misc_copy_caption, "enableCaptionCopy")));
        showSectionDialog(context, I18n.t(context, R.string.menu_likes_comments), layout, () -> {});
    }

    /** Profiles: follows-you label, follower toast, follow-list export, Saved on profile. */
    private static void showProfilesOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_notification, A_SOCIAL, R.string.ig_dialog_misc_profile_follows_label, "profileFollowsYouLabel"),
                flagSwitch(context, R.drawable.ic_notification, A_SOCIAL, R.string.ig_dialog_fancy_bio_font, "fancyBioFont"),
                flagSwitch(context, R.drawable.ic_block, A_SOCIAL, R.string.ig_dialog_hide_threads_button, "hideThreadsButton"),
                flagSwitch(context, R.drawable.ic_notification, A_SOCIAL, R.string.ig_dialog_misc_show_follower_toast, "showFollowerToast"),
                flagSwitch(context, R.drawable.ic_download, A_SOCIAL, R.string.ig_dialog_misc_follow_export, "followListExport"),
                flagSwitch(context, R.drawable.ic_eye, A_SOCIAL, R.string.ig_feat_followers_history, "followersHistory"),
                flagSwitch(context, R.drawable.ic_save, A_SOCIAL, R.string.ig_saved_on_profile, "savedOnProfile")));
        showSectionDialog(context, I18n.t(context, R.string.menu_profiles), layout, () -> {});
    }

    /** App & Account: account limit, notification fix, InstaEclipse's own toasts. */
    private static void showAppOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(cardOf(context,
                extraSwitch(context, "#0A84FF", "unlimitedAccounts"),
                extraSwitch(context, "#0A84FF", "fixNotificationRegistration"),
                flagSwitch(context, R.drawable.ic_notification, "#0A84FF", R.string.ig_dialog_misc_show_feature_toasts, "showFeatureToasts")));
        showSectionDialog(context, I18n.t(context, R.string.menu_app), layout, () -> {});
    }

    /** Top-level "Lock" section (Privacy group) — the grouped passcode-lock card. */
    private static void showLockOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(buildLockCard(context));
        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_misc_lock_section), layout, () -> {});
    }

    /** Hide Specific Chats: account-scoped manager and an independent optional header shortcut. */
    private static void showHideChatsOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        LinearLayout hideCard = card(context);
        ToggleRow hideChats = createSwitch(context, R.drawable.ic_eye_off, A_PRIVACY,
                I18n.t(context, R.string.ig_dialog_misc_hide_chats), FeatureFlags.hideSpecificChats);
        hideChats.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.hideSpecificChats = checked;
            SettingsManager.saveAllFlags();
            ps.reso.instaeclipse.mods.ghost.ConversationMenuHook.refreshAll();
            ps.reso.instaeclipse.mods.ghost.HideChatsHook.refreshKnownAccounts();
        });
        hideCard.addView(hideChats);
        hideCard.addView(flagSwitch(context, R.drawable.ic_eye_off, A_PRIVACY,
                R.string.ie_show_hide_button, "showHideChatButton",
                ps.reso.instaeclipse.mods.ghost.ConversationMenuHook::refreshAll));
        hideCard.addView(createActionRow(context, R.drawable.ic_chat,
                I18n.t(context, R.string.ie_chat_actions), A_PRIVACY,
                v -> ps.reso.instaeclipse.mods.ghost.ConversationMenuHook.show(activityOf(context))));
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
        Activity activity = activityOf(context);
        String account = ps.reso.instaeclipse.utils.ghost.CurrentConversation.accountId(activity);
        if (account == null) {
            layout.addView(createInfoSection(context, "", I18n.t(context, R.string.ie_chat_account_unresolved)));
            showSectionDialog(context, I18n.t(context, R.string.ig_hide_chats_manage), layout, () -> {});
            return;
        }
        java.util.Map<String, String> hidden = ps.reso.instaeclipse.utils.ghost.HiddenThreads.all(account);
        if (ps.reso.instaeclipse.utils.ghost.HiddenThreads.lastError() != null) {
            layout.addView(createInfoSection(context, "", I18n.t(context, R.string.ie_hidden_state_unavailable)));
        }
        if (!ps.reso.instaeclipse.utils.ghost.HiddenThreads.legacyEntries().isEmpty()) {
            layout.addView(createInfoSection(context, "", I18n.t(context, R.string.ie_hidden_legacy)));
        }
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
                if (!account.equals(ps.reso.instaeclipse.utils.ghost.CurrentConversation.accountId(activity))) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context,
                            I18n.t(context, R.string.ie_chat_changed), Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!ps.reso.instaeclipse.utils.ghost.HiddenThreads.unhide(account, id).success) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context,
                            I18n.t(context, R.string.ie_hidden_save_failed), Toast.LENGTH_SHORT).show();
                    return;
                }
                ps.reso.instaeclipse.mods.ghost.HideChatsHook.refreshAccount(account);
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
                ((View) v).setEnabled(false);
                ((View) v).setAlpha(0.4f);
            }));
        }
        layout.addView(cardView);
        showSectionDialog(context, I18n.t(context, R.string.ig_hide_chats_manage), layout, () -> {});
    }

    private static boolean suppressLockToggle = false;

    /** A numeric passcode field for the lock dialogs: the dialog's rounded input, centered and large. */
    private static android.widget.EditText pinInput(IeDialog dlg, String hint) {
        android.widget.EditText e = dlg.input(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(20);
        e.setLetterSpacing(0.2f);
        return e;
    }

    private static void toastWrongPass(Context context) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.lockoutSecondsLeft() > 0
                ? ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.failureText(context)
                : I18n.t(context, R.string.ig_dialog_misc_lock_dms_wrong), Toast.LENGTH_SHORT).show();
    }

    /** Turning the Lock-DMs toggle OFF requires the current passcode. */
    private static void promptDisableLock(Context context, ToggleRow lockSwitch) {
        promptDisableLock(context, lockSwitch, false);
    }

    /** Turning a lock toggle OFF requires the current passcode; on success disable + save, otherwise
     *  the toggle stays on. wholeApp=true disables the whole-app lock, else the DM lock.
     *  Cancel / ✕ leave the toggle on. */
    private static void promptDisableLock(Context context, ToggleRow lockSwitch, boolean wholeApp) {
        try {
            IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_dialog_misc_lock_dms_disable));
            final android.widget.EditText cur = pinInput(dlg, I18n.t(context, R.string.ig_dialog_misc_lock_dms_current));
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                if (ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.checkPass(cur.getText().toString())) {
                    if (wholeApp) FeatureFlags.lockWholeApp = false;
                    else          FeatureFlags.lockDirectMessages = false;
                    suppressLockToggle = true;
                    lockSwitch.setChecked(false);
                    suppressLockToggle = false;
                    SettingsManager.saveAllFlags();
                } else {
                    toastWrongPass(context);
                }
            });
            dlg.show();
        } catch (Throwable ignored) {}
    }

    /** If a DM passcode already exists, require the current one before changing it. */
    private static void promptChangeDmPasscode(Context context) {
        String existing = FeatureFlags.lockDirectPasscode;
        if (existing == null || existing.isEmpty()) { promptSetNewDmPasscode(context); return; }
        try {
            IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_dialog_misc_lock_dms_current));
            final android.widget.EditText cur = pinInput(dlg, I18n.t(context, R.string.ig_dialog_misc_lock_dms_current));
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                if (ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.checkPass(cur.getText().toString())) {
                    promptSetNewDmPasscode(context);
                } else {
                    toastWrongPass(context);
                }
            });
            dlg.show();
        } catch (Throwable ignored) {}
    }

    private static void setRowEnabled(View row, boolean on) {
        row.setEnabled(on);
        row.setAlpha(on ? 1f : 0.4f);
    }

    /** The format, or "Device default", with a sample of the current time. */
    private static String timestampFormatName(Context context, String pattern) {
        String sample = ps.reso.instaeclipse.utils.text.TimestampFormat.format(pattern, new java.util.Date(), java.util.Locale.getDefault());
        return pattern.isEmpty() ? I18n.t(context, R.string.ig_timestamp_format_default) + "  ·  " + sample : sample;
    }

    private static String timestampFormatLabel(Context context) {
        return I18n.t(context, R.string.ig_timestamp_format) + "  ·  " + timestampFormatName(context, FeatureFlags.exactTimestampFormat);
    }

    /** Date format chooser: presets shown as today's date in that format, plus a custom pattern. */
    private static void showTimestampFormatDialog(Context context, TextView label) {
        try {
            String[] presets = ps.reso.instaeclipse.utils.text.TimestampFormat.PRESETS;
            String current = FeatureFlags.exactTimestampFormat;
            CharSequence[] rows = new CharSequence[presets.length + 1];
            int checked = presets.length; // custom
            for (int i = 0; i < presets.length; i++) {
                rows[i] = timestampFormatName(context, presets[i]);
                if (presets[i].equals(current)) checked = i;
            }
            rows[presets.length] = checked == presets.length
                    ? I18n.t(context, R.string.ig_timestamp_format_custom) + "  ·  " + timestampFormatName(context, current)
                    : I18n.t(context, R.string.ig_timestamp_format_custom);
            new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_timestamp_format))
                    .choices(rows, checked, i -> {
                        if (i == presets.length) { showCustomTimestampFormat(context, label); return; }
                        saveTimestampFormat(context, presets[i], label);
                    })
                    .show();
        } catch (Throwable ignored) {}
    }

    private static void showCustomTimestampFormat(Context context, TextView label) {
        try {
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_timestamp_format_custom))
                    .message(I18n.t(context, R.string.ig_timestamp_format_custom_hint));
            final android.widget.EditText input = dlg.input("yyyy-MM-dd HH:mm:ss");
            input.setText(FeatureFlags.exactTimestampFormat.isEmpty() ? "yyyy-MM-dd HH:mm:ss" : FeatureFlags.exactTimestampFormat);
            final TextView preview = dlg.quote("", 2);
            Runnable update = () -> {
                String p = input.getText().toString();
                preview.setText(ps.reso.instaeclipse.utils.text.TimestampFormat.isValid(p) && !p.isEmpty()
                        ? ps.reso.instaeclipse.utils.text.TimestampFormat.format(p, new java.util.Date(), java.util.Locale.getDefault())
                        : I18n.t(context, R.string.ig_timestamp_format_invalid));
            };
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(android.text.Editable s) { update.run(); }
            });
            update.run();
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                String p = input.getText().toString().trim();
                if (!ps.reso.instaeclipse.utils.text.TimestampFormat.isValid(p)) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_timestamp_format_invalid), Toast.LENGTH_SHORT).show();
                    return;
                }
                saveTimestampFormat(context, p, label);
            });
            dlg.show();
        } catch (Throwable ignored) {}
    }

    private static void saveTimestampFormat(Context context, String pattern, TextView label) {
        FeatureFlags.exactTimestampFormat = pattern;
        SettingsManager.saveAllFlags();
        if (label != null) label.setText(timestampFormatLabel(context));
    }

    private static String shareDomainLabel(Context context) {
        String d = FeatureFlags.customShareDomain;
        return I18n.t(context, R.string.ig_dialog_extras_share_domain) + "  ·  "
                + (d.isEmpty() ? I18n.t(context, R.string.ig_dialog_extras_share_domain_off) : d);
    }

    private static View findFirstText(View v) {
        if (v instanceof TextView) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof TextView && ((TextView) c).getText().length() > 2) return c;
                View inner = findFirstText(c);
                if (inner != null && ((TextView) inner).getText().length() > 2) return inner;
            }
        }
        return null;
    }

    /** Custom share domain: replaces instagram.com in links Instagram copies or shares. */
    private static void showShareDomainDialog(Context context, TextView label) {
        try {
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_dialog_extras_share_domain))
                    .message(I18n.t(context, R.string.ig_dialog_extras_share_domain_hint));
            final android.widget.EditText input = dlg.input("ddinstagram.com");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            input.setText(FeatureFlags.customShareDomain);
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                String raw = input.getText().toString();
                String domain = ps.reso.instaeclipse.mods.ads.ShareDomain.normalize(raw);
                if (domain == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_extras_share_domain_invalid), Toast.LENGTH_SHORT).show();
                    return;
                }
                FeatureFlags.customShareDomain = domain;
                SettingsManager.saveAllFlags();
                if (label != null) label.setText(shareDomainLabel(context));
            });
            dlg.show();
        } catch (Throwable ignored) {}
    }

    /** Prompt for a new passcode (empty clears it). */
    private static void promptSetNewDmPasscode(Context context) {
        try {
            IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_dialog_misc_lock_dms_set));
            final android.widget.EditText input = pinInput(dlg, I18n.t(context, R.string.ig_dialog_misc_lock_dms_hint));
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                String pass = input.getText().toString();
                if (pass.isEmpty()) {
                    FeatureFlags.lockDirectPasscode = "";
                    FeatureFlags.lockDirectSalt = "";
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_misc_lock_dms_cleared), Toast.LENGTH_SHORT).show();
                } else {
                    ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.newSalt(); // fresh salt per set
                    FeatureFlags.lockDirectPasscode =
                            ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook.hashPass(pass);
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_misc_lock_dms_saved), Toast.LENGTH_SHORT).show();
                }
                SettingsManager.saveAllFlags();
            });
            dlg.show();
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

        LinearLayout toggleCard = card(context);
        toggleCard.addView(spoofSwitch);
        layout.addView(toggleCard);

        boolean set = ps.reso.instaeclipse.mods.location.LocationPresets.valid(FeatureFlags.spoofLat, FeatureFlags.spoofLng);
        String place = !set ? I18n.t(context, R.string.ig_dialog_location_unset)
                : !FeatureFlags.spoofLabel.isEmpty() ? FeatureFlags.spoofLabel
                : ps.reso.instaeclipse.mods.location.LocationPresets.coords(FeatureFlags.spoofLat, FeatureFlags.spoofLng);

        LinearLayout current = card(context);
        current.addView(createInfoSection(context, I18n.t(context, R.string.ig_dialog_location_current_title), place));
        current.addView(createActionRow(context, R.drawable.ic_search, I18n.t(context, R.string.ig_dialog_location_pick), "#FFD60A", v -> {
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
        layout.addView(current);

        java.util.List<ps.reso.instaeclipse.mods.location.LocationPresets.Preset> recent =
                ps.reso.instaeclipse.mods.location.LocationPresets.parse(FeatureFlags.spoofRecent);
        if (!recent.isEmpty()) {
            layout.addView(sectionHeader(context, I18n.t(context, R.string.ig_dialog_location_recent)));
            LinearLayout recentCard = card(context);
            for (ps.reso.instaeclipse.mods.location.LocationPresets.Preset p : recent) {
                boolean active = set && ps.reso.instaeclipse.mods.location.LocationPresets.distanceMeters(
                        p.lat, p.lng, FeatureFlags.spoofLat, FeatureFlags.spoofLng) < 5;
                RadioRow row = new RadioRow(context, p.title(), active);
                row.setOnClickListener(v -> {
                    FeatureFlags.spoofLat = p.lat;
                    FeatureFlags.spoofLng = p.lng;
                    FeatureFlags.spoofLabel = p.label;
                    FeatureFlags.spoofRecent = ps.reso.instaeclipse.mods.location.LocationPresets.serialize(
                            ps.reso.instaeclipse.mods.location.LocationPresets.remember(recent, p));
                    SettingsManager.saveAllFlags();
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_location_applied), Toast.LENGTH_SHORT).show();
                    if (currentDialog != null) {
                        try { currentDialog.dismiss(); } catch (Exception ignored) {}
                        currentDialog = null;
                    }
                    showLocationOptions(context);
                });
                recentCard.addView(row);
            }
            layout.addView(recentCard);
        }

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
        // Follow the phone's mode, like the customizer: in dark mode pick the dark theme, in light
        // mode the light-mode behaviour.
        if (ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.isNightNow()) {
            colorsCard.addView(createActionRow(context, R.drawable.ic_palette,
                    I18n.t(context, R.string.theme_dark_mode) + ": " + darkModeLabel(context, FeatureFlags.themePresetId),
                    "#FF2D55", v -> pickDarkModeTheme(context)));
        } else {
            colorsCard.addView(createActionRow(context, R.drawable.ic_palette,
                    I18n.t(context, R.string.theme_light_mode) + ": " + lightModeLabel(context, FeatureFlags.themeLightPresetId),
                    "#FF2D55", v -> pickLightModeTheme(context)));
        }
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

    private static String lightModeLabel(Context context, int value) {
        if (value == 0) return I18n.t(context, R.string.theme_light_same);
        if (value == -1) return I18n.t(context, R.string.theme_light_stock);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(context, value);
    }

    private static String darkModeLabel(Context context, int value) {
        if (value == 0) return I18n.t(context, R.string.theme_section_custom);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(context, value);
    }

    /** Dark-mode theme: one of the dark presets, or the custom palette when that is in use. */
    private static void pickDarkModeTheme(Context context) {
        int[] presets = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.darkModeChoices();
        final int[] values;
        if (FeatureFlags.themePresetId == 0) {
            values = new int[presets.length + 1];
            System.arraycopy(presets, 0, values, 1, presets.length);
        } else {
            values = presets;
        }
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = darkModeLabel(context, values[i]);
            if (values[i] == FeatureFlags.themePresetId) sel = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.theme_dark_mode))
                .choices(labels, sel, which -> {
                    FeatureFlags.themePresetId = values[which];
                    SettingsManager.saveAllFlags();
                    if (currentDialog != null) {
                        try { currentDialog.dismiss(); } catch (Exception ignored) {}
                        currentDialog = null;
                    }
                    ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.invalidate();
                    ps.reso.instaeclipse.mods.ui.theme.IgThemeHook.refreshCurrentActivity();
                })
                .show();
    }

    /** Light-mode behaviour of the custom theme (#251): same theme, stock Instagram, or a preset. */
    private static void pickLightModeTheme(Context context) {
        final int[] values = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.lightModeChoices();
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = lightModeLabel(context, values[i]);
            if (values[i] == FeatureFlags.themeLightPresetId) sel = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.theme_light_mode))
                .choices(labels, sel, which -> {
                    FeatureFlags.themeLightPresetId = values[which];
                    SettingsManager.saveAllFlags();
                    if (currentDialog != null) {
                        try { currentDialog.dismiss(); } catch (Exception ignored) {}
                        currentDialog = null;
                    }
                    ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.invalidate();
                    ps.reso.instaeclipse.mods.ui.theme.IgThemeHook.refreshCurrentActivity();
                })
                .show();
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
                    else ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker),
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
                    else ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker),
                            Toast.LENGTH_SHORT).show();
                }));
        return fontCard;
    }

    private static class RadioRow extends LinearLayout {
        private final ImageView checkmark;

        RadioRow(Context context, String label, boolean checked) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14));
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);
            setBackground(rowRipple(context, 0));

            TextView labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(cText(context));
            labelView.setTextSize(16);
            labelView.setPadding(0, dp(context, 4), dp(context, 12), dp(context, 4));
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            labelView.setLayoutParams(lp);

            checkmark = new ImageView(context);
            Drawable check = loadModuleIcon(R.drawable.ic_check_circle, accent());
            if (check != null) checkmark.setImageDrawable(check);
            checkmark.setVisibility(checked ? VISIBLE : INVISIBLE);
            LayoutParams checkLp = new LayoutParams(dp(context, 20), dp(context, 20));
            checkmark.setLayoutParams(checkLp);
            checkmark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

            addView(labelView);
            addView(checkmark);
        }

        void setChecked(boolean checked) {
            checkmark.setVisibility(checked ? VISIBLE : INVISIBLE);
        }
    }

    private static String downloadQualityLabel(Context context, int ceiling) {
        return ceiling <= 0 ? I18n.t(context, R.string.ie_download_best)
                : I18n.t(context, R.string.ie_download_ceiling, ceiling);
    }

    private static void showDownloadQuality(Context context) {
        if (FeatureFlags.downloaderUseOriginal) return;
        int[] ceilings = {0, 2160, 1440, 1080, 720, 480};
        String[] labels = new String[ceilings.length];
        int selected = 0;
        for (int i = 0; i < ceilings.length; i++) {
            labels[i] = downloadQualityLabel(context, ceilings[i]);
            if (ceilings[i] == FeatureFlags.downloaderQualityMaxShortSide) selected = i;
        }
        new android.app.AlertDialog.Builder(context)
                .setTitle(I18n.t(context, R.string.ie_download_quality))
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (!FeatureFlags.downloaderUseOriginal) {
                        FeatureFlags.downloaderQualityMaxShortSide = ceilings[which];
                        SettingsManager.saveAllFlags();
                    }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showDownloaderOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        ToggleRow original = createSwitch(context, R.drawable.ic_download, "#FF9F0A",
                I18n.t(context, R.string.ie_downloader_use_original), FeatureFlags.downloaderUseOriginal);
        layout.addView(original);
        layout.addView(createInfoSection(context, "", I18n.t(context, R.string.ie_downloader_mode_hint)));
        View quality = createActionRow(context, R.drawable.ic_download,
                I18n.t(context, R.string.ie_download_quality) + " · "
                        + downloadQualityLabel(context, FeatureFlags.downloaderQualityMaxShortSide),
                "#FF9F0A", v -> showDownloadQuality(context));
        layout.addView(quality);
        ToggleRow compatibility = createSwitch(context, R.drawable.ic_tune, "#FF9F0A",
                I18n.t(context, R.string.ie_download_compatibility), FeatureFlags.downloaderCompatibility);
        compatibility.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.downloaderCompatibility = checked;
            SettingsManager.saveAllFlags();
        });
        ToggleRow lowerFallback = createSwitch(context, R.drawable.ic_download, "#FF9F0A",
                I18n.t(context, R.string.ie_download_fallback), FeatureFlags.downloaderAllowFallback);
        lowerFallback.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.downloaderAllowFallback = checked;
            SettingsManager.saveAllFlags();
        });
        layout.addView(compatibility);
        layout.addView(lowerFallback);
        Runnable updateCustomControls = () -> {
            boolean custom = !FeatureFlags.downloaderUseOriginal;
            quality.setEnabled(custom);
            quality.setAlpha(custom ? 1f : 0.38f);
            compatibility.setEnabled(custom);
            lowerFallback.setEnabled(custom);
        };
        original.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.downloaderUseOriginal = checked;
            updateCustomControls.run();
            SettingsManager.saveAllFlags();
        });
        updateCustomControls.run();
        layout.addView(createInfoSection(context, "", I18n.t(context, R.string.ie_download_quality_hint)));


        addMasterGroup(context, layout,
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_posts, "enablePostDownload"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_stories, "enableStoryDownload"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_reels, "enableReelDownload"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_profiles, "enableProfileDownload"));

        layout.addView(createDivider(context));
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_voice, "enableVoiceDownload"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_stickers, "enableStickerDownload"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_dm_media, "enableDmMediaDownload"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_downloader_offer_audio, "offerAudioDownload"),
                // Save Instants (#184) — long-press a received Instant to save its media.
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_misc_save_instants, "saveInstants"),
                // Cache Stories (24h) — keep viewed stories locally so they survive expiry/deletion.
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_misc_cache_stories, "cacheStories"),
                createActionRow(context, R.drawable.ic_eye, I18n.t(context, R.string.ig_story_cache_view), "#FF9F0A", v -> showCachedStories(context))));

        // Where and how files are saved.
        String folderRaw = FeatureFlags.downloaderCustomPath.isEmpty()
                ? android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/Download/InstaEclipse"
                : FeatureFlags.downloaderCustomPath;
        // Strip everything up to and including the primary storage root ("…/0/")
        // so "/storage/emulated/0/Download/InstaEclipse" → "Download/InstaEclipse"
        String folderDisplay = folderRaw.replaceFirst("^.*/0/", "");
        layout.addView(sectionHeader(context, I18n.t(context, R.string.ig_dialog_section_downloader_settings)));
        // The folder is picked with the system folder picker, which only the companion app can run.
        TextView folderHint = new TextView(context);
        folderHint.setText(I18n.t(context, R.string.ig_downloader_folder_hint));
        folderHint.setTextSize(13);
        folderHint.setTextColor(cText2(context));
        folderHint.setPadding(40, 0, 32, 20);
        LinearLayout folderBlock = new LinearLayout(context);
        folderBlock.setOrientation(LinearLayout.VERTICAL);
        folderBlock.addView(createInfoSection(context, I18n.t(context, R.string.ig_dialog_downloader_folder), folderDisplay));
        folderBlock.addView(folderHint);
        layout.addView(cardOf(context,
                folderBlock,
                flagSwitch(context, R.drawable.ic_folder, "#FF9F0A", R.string.ig_dialog_downloader_username_subfolder, "downloaderUsernameFolder"),
                flagSwitch(context, R.drawable.ic_timer, "#FF9F0A", R.string.ig_dialog_downloader_add_timestamp, "downloaderAddTimestamp")));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_downloader), layout, () -> {});
    }

    /** Post ⋮ menu extras (copy link/image, media info, playback speed) and upload/Instants tweaks. */
    private static void showPostMenuOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);
        layout.addView(cardOf(context,
                // Copy Media Link (#117) — copies the current slide's direct CDN url to the clipboard.
                flagSwitch(context, R.drawable.ic_link, "#FF9F0A", R.string.ig_dialog_downloader_copy_link, "copyMediaLink"),
                flagSwitch(context, R.drawable.ic_content_copy, "#FF9F0A", R.string.ig_dialog_downloader_offer_copy_image, "offerCopyImage"),
                flagSwitch(context, R.drawable.ic_search, "#FF9F0A", R.string.ig_dialog_downloader_offer_media_info, "offerMediaInfo"),
                flagSwitch(context, R.drawable.ic_movie, "#FF9F0A", R.string.ig_dialog_downloader_offer_speed, "offerPlaybackSpeed")));
        layout.addView(createDivider(context));
        layout.addView(cardOf(context,
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_max_upload_quality, "maxUploadQuality"),
                flagSwitch(context, R.drawable.ic_download, "#FF9F0A", R.string.ig_dialog_misc_upload_instants, "uploadInstants")));
        showSectionDialog(context, I18n.t(context, R.string.menu_post_menu), layout, () -> {});
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
                        IeDialog dlg = new IeDialog(context)
                                .title(I18n.t(context, R.string.ig_story_cache_clear))
                                .message(I18n.t(context, R.string.ig_story_cache_clear_confirm));
                        dlg.secondary(context.getString(android.R.string.cancel), null);
                        dlg.primary(I18n.t(context, R.string.ig_story_cache_clear), () -> {
                            ps.reso.instaeclipse.utils.media.StoryCache.clearAll();
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_cache_cleared), Toast.LENGTH_SHORT).show();
                            showCachedStories(context); // refresh so the deleted rows disappear
                        });
                        dlg.show();
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
            String label = fmt.format(new java.util.Date(e.at))
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
            badge.setTextColor(expired ? Color.WHITE : cText(context));
            badge.setTextSize(11);
            badge.setTypeface(null, android.graphics.Typeface.BOLD);
            badge.setPadding(Math.round(10 * d), Math.round(4 * d), Math.round(10 * d), Math.round(4 * d));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(expired ? Color.parseColor("#FF453A") : (isDarkTheme(context) ? 0xFF36373A : 0xFFE0E0E0));
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
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, "Couldn't open story", Toast.LENGTH_SHORT).show();
        }
    }

    /** Copies a cached story's local file into the gallery/download folder (off the UI thread). */
    private static void saveCachedStoryToGallery(Context context, ps.reso.instaeclipse.utils.media.StoryCache.Entry e) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_cache_saving), Toast.LENGTH_SHORT).show();
        ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.saveLocalFileToGallery(
                context, e.path,
                (e.author == null || e.author.isEmpty()) ? "story" : e.author, e.id, e.video,
                I18n.t(context, R.string.ig_story_cache_saved), I18n.t(context, R.string.ig_story_cache_save_fail));
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
                    ps.reso.instaeclipse.utils.ipc.CompanionBridge.launchActivity(instagramActivity, exportIntent);
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
                ps.reso.instaeclipse.utils.ipc.CompanionBridge.launchActivity(instagramActivity, importIntent);
            } else {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
            }
        }));

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_backup_restore), layout, () -> {});
    }

    private static void showAboutDialog(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(context, 8), dp(context, 12), dp(context, 8), dp(context, 8));

        ImageView icon = new ImageView(context);
        Drawable logo = loadModuleIcon(R.drawable.ic_logo, cText(context));
        if (logo != null) icon.setImageDrawable(logo);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(context, 84), dp(context, 84));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        ilp.topMargin = dp(context, 8);
        ilp.bottomMargin = dp(context, 4);
        layout.addView(icon, ilp);

        TextView title = new TextView(context);
        title.setText(I18n.t(context, R.string.ig_dialog_title));
        title.setTextColor(cText(context));
        title.setTextSize(24f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(context, 12), 0, 0);
        layout.addView(title);

        TextView tagline = new TextView(context);
        tagline.setText(I18n.t(context, R.string.about_tagline));
        tagline.setTextColor(cText2(context));
        tagline.setTextSize(14f);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(context, 2), 0, dp(context, 10));
        layout.addView(tagline);

        // Version chips + feature health (same chips as the menu header).
        View chips = buildStatusChips(context);
        layout.addView(chips);

        LinearLayout links = card(context);
        links.addView(createActionRow(context, R.drawable.ic_github_logo, I18n.t(context, R.string.about_source), "#8E8E93",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.GITHUB)));
        links.addView(createActionRow(context, R.drawable.ic_telegram_logo, I18n.t(context, R.string.about_channel), "#29B6F6",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.TELEGRAM)));
        links.addView(createActionRow(context, R.drawable.ic_github_logo, I18n.t(context, R.string.about_contributors), "#BF5AF2",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.GITHUB + "/graphs/contributors")));
        layout.addView(links);

        TextView footer = new TextView(context);
        footer.setText(I18n.t(context, R.string.about_footer));
        footer.setTextColor(cText2(context));
        footer.setTextSize(12f);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(context, 14), 0, 0);
        layout.addView(footer);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_about), layout, () -> {
        });

        // Entrance: icon pops in, the rest rises in just after it.
        icon.setScaleX(0.7f);
        icon.setScaleY(0.7f);
        icon.setAlpha(0f);
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        int i = 0;
        float d = context.getResources().getDisplayMetrics().density;
        for (View v : new View[]{title, tagline, chips, links, footer}) {
            v.setAlpha(0f);
            v.setTranslationY(12 * d);
            v.animate().alpha(1f).translationY(0f).setStartDelay(120 + 60L * i++).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    private static void openLink(Context context, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** A drawable from the module's own APK, in its own colours. */
    private static Drawable loadModuleDrawable(int res) {
        try {
            return android.content.res.XModuleResources.createInstance(ps.reso.instaeclipse.Xposed.Module.moduleSourceDir, null)
                    .getDrawable(res, null);
        } catch (Throwable t) {
            return null;
        }
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
    /** Section pages opened from another section (e.g. Ghost Mode -> Send read receipts to): the
     *  parent is hidden, not dismissed, so Back can bring it back as it was. {dialog, title} */
    private static final java.util.ArrayDeque<Object[]> sectionStack = new java.util.ArrayDeque<>();
    private static String currentSectionTitle; // null while the main menu (or nothing) is showing

    private static void clearSectionStack() {
        for (Object[] e : sectionStack) {
            try { ((Dialog) e[0]).dismiss(); } catch (Exception ignored) {}
        }
        sectionStack.clear();
        currentSectionTitle = null;
    }

    private static void showSectionDialog(Context context, String title, LinearLayout contentLayout, Runnable onSave) {
        if (currentDialog != null) {
            if (currentSectionTitle != null && !currentSectionTitle.equals(title) && currentDialog.isShowing()) {
                currentDialog.hide(); // opened from another section: keep it to come back to
                sectionStack.push(new Object[]{currentDialog, currentSectionTitle});
            } else {
                try { currentDialog.dismiss(); } catch (Exception ignored) {}
            }
            currentDialog = null;
        }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, 0);

        GradientDrawable background = new GradientDrawable();
        background.setColor(cSheet(context));
        float corner = getSheetCornerRadius(context);
        background.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        container.setBackground(background);

        container.addView(createDragHandle(context));

        // Header row: back arrow + title
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(context, 12), dp(context, 4), dp(context, 20), dp(context, 12));
        header.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView backBtn = new android.widget.ImageView(context);
        Drawable backIcon = loadModuleIcon(R.drawable.ic_arrow_back, cText(context));
        if (backIcon != null) backBtn.setImageDrawable(backIcon);
        int bp = dp(context, 8);
        backBtn.setPadding(bp, bp, bp, bp);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40));
        backLp.rightMargin = dp(context, 8);
        backBtn.setLayoutParams(backLp);
        backBtn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        backBtn.setBackground(rowRipple(context, dp(context, 20)));
        backBtn.setClickable(true);
        backBtn.setFocusable(true);
        backBtn.setOnClickListener(v -> {
            onSave.run();
            SettingsManager.saveAllFlags();
            Object[] parent = sectionStack.poll();
            if (parent != null) {
                if (currentDialog != null) { try { currentDialog.dismiss(); } catch (Exception ignored) {} }
                currentDialog = (Dialog) parent[0];
                currentSectionTitle = (String) parent[1];
                currentDialog.show();
            } else {
                showEclipseOptionsDialog(context);
            }
        });

        TextView titleView = new TextView(context);
        titleView.setText(stripEdgeEmoji(title));
        titleView.setTextColor(cText(context));
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        header.addView(backBtn);
        header.addView(titleView);
        container.addView(header);
        container.addView(createHeaderDivider(context));

        LinearLayout contentWrapper = new LinearLayout(context);
        contentWrapper.setOrientation(LinearLayout.VERTICAL);
        contentWrapper.setPadding(0, 0, 0, 0);
        contentWrapper.addView(contentLayout);

        // Bottom padding for nav bar
        View bottomPad = new View(context);
        bottomPad.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 28)));
        contentWrapper.addView(bottomPad);

        ScrollView scrollView = createScrollableContainer(context, contentWrapper, 0.75f);
        container.addView(scrollView);

        currentDialog = createBottomSheetDialog(context, container);
        currentSectionTitle = title;
        currentDialog.setOnCancelListener(d -> clearSectionStack());
        currentDialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) backBtn.performClick();
            return true;
        });
        showBottomSheetDialog(currentDialog, container);
    }

    private static int blendColors(int from, int to, float ratio) {
        float inverse = 1f - ratio;
        float a = Color.alpha(from) * inverse + Color.alpha(to) * ratio;
        float r = Color.red(from) * inverse + Color.red(to) * ratio;
        float g = Color.green(from) * inverse + Color.green(to) * ratio;
        float b = Color.blue(from) * inverse + Color.blue(to) * ratio;
        return Color.argb((int) a, (int) r, (int) g, (int) b);
    }

    private static float dp(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    public static class IgSwitch extends View implements Checkable {
        private boolean isChecked;
        private float progress;
        private ValueAnimator animator;
        private CompoundButton.OnCheckedChangeListener onCheckedChangeListener;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF borderRect = new RectF();

        private final int cTrackOff;
        private final int cTrackOn;
        private final int cBorderOff;
        private final int cThumbOff;
        private final int cThumbOn;

        public IgSwitch(Context context) {
            super(context);
            boolean dark = isDarkTheme(context);
            cTrackOff  = dark ? 0xFF2B3036 : 0xFFE0E0E0;
            cTrackOn   = dark ? 0xFFFFFFFF : 0xFF262626;
            cBorderOff = dark ? 0xFF4C4F56 : 0xFFCCCCCC;
            cThumbOff  = dark ? 0xFFA4AAB8 : 0xFF737373;
            cThumbOn   = dark ? 0xFF12151A : 0xFFFFFFFF;

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(context, 1.5f));

            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 32));
        }

        @Override
        public boolean isChecked() {
            return isChecked;
        }

        @Override
        public void setChecked(boolean checked) {
            setChecked(checked, isAttachedToWindow() && isLaidOut());
        }

        public void setChecked(boolean checked, boolean animate) {
            boolean changed = this.isChecked != checked;
            this.isChecked = checked;
            if (changed && onCheckedChangeListener != null) {
                onCheckedChangeListener.onCheckedChanged(null, checked);
            }

            float target = checked ? 1f : 0f;
            if (animate) {
                if (animator != null) animator.cancel();
                animator = ValueAnimator.ofFloat(progress, target);
                animator.setDuration(200);
                animator.setInterpolator(new DecelerateInterpolator());
                animator.addUpdateListener(a -> {
                    progress = (float) a.getAnimatedValue();
                    invalidate();
                });
                animator.start();
            } else {
                if (animator != null) animator.cancel();
                progress = target;
                invalidate();
            }
        }

        @Override
        public void toggle() {
            setChecked(!isChecked);
        }

        public void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener listener) {
            this.onCheckedChangeListener = listener;
        }

        @Override
        public boolean performClick() {
            toggle();
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;

            trackPaint.setColor(blendColors(cTrackOff, cTrackOn, progress));
            trackRect.set(0, 0, w, h);
            canvas.drawRoundRect(trackRect, r, r, trackPaint);

            if (progress < 1f) {
                float stroke = borderPaint.getStrokeWidth();
                float inset = stroke / 2f;
                int alpha = Math.round(255 * (1f - progress));
                borderPaint.setColor(cBorderOff);
                borderPaint.setAlpha(alpha);
                borderRect.set(inset, inset, w - inset, h - inset);
                canvas.drawRoundRect(borderRect, r - inset, r - inset, borderPaint);
            }

            Context c = getContext();
            float rOff = dp(c, 8f);
            float rOn  = dp(c, 12.5f);
            float thumbR = rOff + (rOn - rOff) * progress;

            float centerX = r + (w - 2f * r) * progress;
            float centerY = h / 2f;

            thumbPaint.setColor(blendColors(cThumbOff, cThumbOn, progress));
            canvas.drawCircle(centerX, centerY, thumbR, thumbPaint);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (animator != null) {
                animator.cancel();
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Switch.class.getName());
            info.setCheckable(true);
            info.setChecked(isChecked);
        }
    }

    private static class ToggleRow extends LinearLayout {
        private final IgSwitch toggle;
        private final TextView labelView;
        private CompoundButton.OnCheckedChangeListener primary;
        private final java.util.List<CompoundButton.OnCheckedChangeListener> observers = new java.util.ArrayList<>();

        ToggleRow(Context context, String label, boolean checked) {
            this(context, 0, null, label, checked);
        }

        ToggleRow(Context context, int iconRes, String accentHex, String label, boolean checked) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14));
            setGravity(Gravity.CENTER_VERTICAL);
            setClickable(true);
            setFocusable(true);
            setBackground(rowRipple(context, 0));

            if (iconRes != 0) {
                addView(buildIconChip(context, iconRes, accentHex));
            }

            labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(cText(context));
            labelView.setTextSize(16);
            labelView.setPadding(0, dp(context, 4), dp(context, 12), dp(context, 4));
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            labelView.setLayoutParams(lp);

            toggle = new IgSwitch(context);
            toggle.setChecked(checked, false);
            toggle.setClickable(false);
            toggle.setFocusable(false);
            toggle.setOnCheckedChangeListener((b, c) -> {
                if (primary != null) primary.onCheckedChanged(b, c);
                for (CompoundButton.OnCheckedChangeListener o : observers) o.onCheckedChanged(b, c);
            });

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
            primary = l;
        }

        void addOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener l) {
            observers.add(l);
        }

        void makeBold() {
            labelView.setTypeface(null, Typeface.BOLD);
            labelView.setTextSize(16);
        }
    }

    private static ToggleRow createSwitch(Context context, String label, boolean defaultState) {
        return new ToggleRow(context, label, defaultState);
    }

    private static ToggleRow createSwitch(Context context, int iconRes, String accentHex, String label, boolean defaultState) {
        return new ToggleRow(context, iconRes, accentHex, label, defaultState);
    }

    private static View buildIconChip(Context context, int iconRes, String accentHex) {
        android.widget.ImageView iconView = new android.widget.ImageView(context);
        boolean isDestructive = "#FF453A".equalsIgnoreCase(accentHex) || "#ED4956".equalsIgnoreCase(accentHex);
        int tint = isDestructive ? C_DANGER : cText(context);
        Drawable icon = loadModuleIcon(iconRes, tint);
        if (icon != null) iconView.setImageDrawable(icon);
        iconView.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        iconLp.rightMargin = dp(context, 20);
        iconView.setLayoutParams(iconLp);
        return iconView;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout createSwitchLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return layout;
    }

    private static View createInfoSection(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(cText(context));
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(context);
        valueView.setText(value);
        valueView.setTextSize(13);
        valueView.setTextColor(cText2(context));
        valueView.setMaxLines(1);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        valueView.setPadding(dp(context, 16), 0, 0, 0);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(labelView);
        row.addView(valueView);
        return row;
    }

    private static View createActionRow(Context context, int iconRes, String label, String accentHex, View.OnClickListener onClick) {
        boolean isDestructive = "#FF453A".equalsIgnoreCase(accentHex) || "#ED4956".equalsIgnoreCase(accentHex);
        int tint = isDestructive ? C_DANGER : cText(context);
        android.widget.ImageView iconView = new android.widget.ImageView(context);
        Drawable icon = loadModuleIcon(iconRes, tint);
        if (icon != null) iconView.setImageDrawable(icon);
        return createActionRow(context, iconView, label, accentHex, onClick);
    }

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
        boolean isDestructive = "#FF453A".equalsIgnoreCase(accentHex) || "#ED4956".equalsIgnoreCase(accentHex);
        int itemColor = isDestructive ? C_DANGER : cText(context);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowRipple(context, 0));

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
        iconLp.rightMargin = dp(context, 20);
        iconView.setLayoutParams(iconLp);
        if (iconView instanceof android.widget.ImageView) {
            ((android.widget.ImageView) iconView).setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        }

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(itemColor);
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
        wrapper.setPadding(0, dp(context, 12), 0, dp(context, 10));

        View handle = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(context, 32), Math.round(dp(context, 2.5f)));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        handle.setLayoutParams(lp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(cHandle(context));
        handleBg.setCornerRadius(dp(context, 1.5f));
        handle.setBackground(handleBg);

        wrapper.addView(handle);
        return wrapper;
    }

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

    private static boolean isTouchInsideView(View view, MotionEvent ev) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] && x <= (loc[0] + view.getWidth()) &&
               y >= loc[1] && y <= (loc[1] + view.getHeight());
    }

    private static ScrollView findScrollView(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ScrollView sv = findScrollView(vg.getChildAt(i));
                if (sv != null) return sv;
            }
        }
        return null;
    }

    private static void animateClose(View contentView, Dialog dialog, boolean[] isClosing) {
        if (isClosing[0]) return;
        isClosing[0] = true;
        int h = contentView.getHeight();
        if (h <= 0) h = dp(contentView.getContext(), 400);
        contentView.animate()
                .translationY(h)
                .setDuration(180)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.8f))
                .withEndAction(() -> {
                    try { dialog.dismiss(); } catch (Exception ignored) {}
                })
                .start();
    }

    private static class SheetRootLayout extends FrameLayout {
        private final View sheetView;
        private final Dialog dialog;
        private final boolean[] isClosing;
        private final int touchSlop;
        private float downX;
        private float downY;
        private boolean isDraggingSheet = false;
        private VelocityTracker velocityTracker;
        private ScrollView targetScrollView;

        SheetRootLayout(Context context, View sheetView, Dialog dialog, boolean[] isClosing) {
            super(context);
            this.sheetView = sheetView;
            this.dialog = dialog;
            this.isClosing = isClosing;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        private ScrollView getScrollView() {
            if (targetScrollView == null) {
                targetScrollView = findScrollView(sheetView);
            }
            return targetScrollView;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (isClosing[0]) return false;
            int action = ev.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                downX = ev.getRawX();
                downY = ev.getRawY();
                isDraggingSheet = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                }
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                targetScrollView = findScrollView(sheetView);
                return false;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                }
                float dy = ev.getRawY() - downY;
                float dx = ev.getRawX() - downX;

                if (dy > touchSlop && dy > Math.abs(dx)) {
                    ScrollView sv = getScrollView();
                    boolean inScrollView = sv != null && isTouchInsideView(sv, ev);
                    if (!inScrollView || sv.getScrollY() <= 0) {
                        isDraggingSheet = true;
                        return true;
                    }
                }
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isDraggingSheet = false;
            }

            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (isClosing[0]) return true;
            int action = ev.getActionMasked();

            if (velocityTracker != null) {
                velocityTracker.addMovement(ev);
            }

            if (action == MotionEvent.ACTION_DOWN) {
                downX = ev.getRawX();
                downY = ev.getRawY();
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (isDraggingSheet) {
                    float dy = ev.getRawY() - downY;
                    sheetView.setTranslationY(Math.max(0f, dy));
                    return true;
                } else {
                    float dy = ev.getRawY() - downY;
                    float dx = ev.getRawX() - downX;
                    if (dy > touchSlop && dy > Math.abs(dx)) {
                        ScrollView sv = getScrollView();
                        boolean inScrollView = sv != null && isTouchInsideView(sv, ev);
                        if (!inScrollView || sv.getScrollY() <= 0) {
                            isDraggingSheet = true;
                            downY = ev.getRawY();
                            sheetView.setTranslationY(0f);
                            return true;
                        }
                    }
                }
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (isDraggingSheet) {
                    isDraggingSheet = false;
                    float currentTransY = sheetView.getTranslationY();
                    float vy = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        vy = velocityTracker.getYVelocity();
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }

                    int dismissThreshold = dp(getContext(), 80);
                    if (currentTransY > dismissThreshold || vy > 1200f) {
                        animateClose(sheetView, dialog, isClosing);
                    } else {
                        sheetView.animate()
                                .translationY(0f)
                                .setDuration(160)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    }
                    return true;
                } else {
                    if (!isTouchInsideView(sheetView, ev)) {
                        animateClose(sheetView, dialog, isClosing);
                        return true;
                    }
                }
            }

            return super.onTouchEvent(ev);
        }
    }

    private static Dialog createBottomSheetDialog(Context context, View contentView) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        boolean[] isClosing = new boolean[]{false};
        SheetRootLayout root = new SheetRootLayout(context, contentView, dialog, isClosing);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (!isClosing[0]) {
                    animateClose(contentView, dialog, isClosing);
                    return true;
                }
            }
            return false;
        });

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        contentView.setLayoutParams(sheetLp);
        contentView.setClickable(true);

        root.addView(contentView);
        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.70f);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                window.setNavigationBarColor(Color.TRANSPARENT);
            }
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setPadding(0, 0, 0, 0);
            }
        }
        return dialog;
    }

    private static void showBottomSheetDialog(Dialog dialog, View sheetView) {
        if (dialog == null) return;
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setPadding(0, 0, 0, 0);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    int flags = decor.getSystemUiVisibility();
                    if (!isDarkTheme(sheetView != null ? sheetView.getContext() : decor.getContext())) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    } else {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                    decor.setSystemUiVisibility(flags);
                }
            }
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp != null) {
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = WindowManager.LayoutParams.MATCH_PARENT;
                lp.horizontalMargin = 0f;
                lp.verticalMargin = 0f;
                window.setAttributes(lp);
            }
        }

        if (sheetView != null) {
            sheetView.setTranslationY(dp(sheetView.getContext(), 600));
            sheetView.post(() -> {
                int h = sheetView.getHeight();
                if (h <= 0) h = dp(sheetView.getContext(), 400);
                sheetView.setTranslationY(h);
                sheetView.animate()
                        .translationY(0)
                        .setDuration(260)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                        .start();
            });
        }
    }

    private static LinearLayout createEnableAllSwitch(Context context, ToggleRow enableAllRow) {
        enableAllRow.makeBold();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(context, 2), 0, dp(context, 2));

        container.addView(enableAllRow);
        container.addView(createHairline(context));
        return container;
    }

    private static boolean areAllEnabled(ToggleRow[] rows) {
        for (ToggleRow r : rows) {
            if (!r.isChecked()) return false;
        }
        return true;
    }

}
