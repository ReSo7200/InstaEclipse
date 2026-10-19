package ps.reso.instaeclipse.fragments;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.Contributor;

public class HomeFragment extends Fragment {

    // px/s scroll speed — feels like a gentle conveyor belt
    private static final float SCROLL_SPEED_DP_PER_SEC = 48f;

    private static final String PREFS_NAME = "instaeclipse_cache";
    private static final String PREF_PINNED = "homePinnedShortcuts";
    private static final String DEFAULT_PINNED = "isGhostSeen,isGhostTyping,isGhostStory,isAdBlockEnabled";
    private static final int MAX_PINNED = 8;

    private static final int COLOR_OK = 0xFF30D158;
    private static final int COLOR_ERROR = 0xFFFF453A;
    private static final int COLOR_INSTAGRAM = 0xFFFF375F;

    /**
     * Boolean pref keys that represent user-facing features (mirrors the switches in
     * FeaturesFragment). Quick-toggle menu config (quickToggle*), Extreme Mode and derived flags
     * are deliberately excluded so the hero count reflects actual enabled features.
     */
    private static final String[] FEATURE_KEYS = {
            // Ghost mode
            "isGhostSeen", "isGhostTyping", "isGhostStory", "isGhostLive", "allowScreenshots",
            "isGhostScreenshot", "isGhostViewOnce", "permanentViewMode", "keepEphemeralMessages",
            "keepUnsentMessages", "keepEditHistory", "jumpToFirstMessage", "translateMessages", "bulkUnsend", "chatExport",
            // Ads & analytics
            "isAdBlockEnabled", "isAnalyticsBlocked", "disableTrackingLinks", "openLinksExternally",
            // Clean feed
            "hideSuggestionsInFeed", "hideThreadsSuggestions", "hideLikedPosts", "confirmFeedRefresh", "hideCommunityNotes", "followingOnlyFeed",
            "hidePlusUpsells", "expandCaptions",
            // Distraction-free
            "disableStories", "disableFeed", "disableReels", "disableReelsExceptDM", "disableExplore",
            "disableComments", "hideNotesTray", "hideDmBlend", "hideThreadsButton", "readReceiptChats",
            // Navigation bar
            "hideTabHome", "hideTabReels", "hideTabCreate", "hideTabMessages", "hideTabActivity", "hideTabProfile",
            // Misc
            "disableStoryFlipping", "disableVideoAutoPlay", "spoofLastSeen", "disableSearchHistory", "confirmCalls", "disableRepost", "showFollowerToast",
            "showFeatureToasts", "enableStoryMentions", "disableDiscoverPeople", "enableCopyComment",
            "disableDoubleTapLike", "enableCaptionCopy", "enablePhotoZoom", "removeMetaAI",
            "hideShareCreateGroup", "exactTimestamps", "likeUndo", "profileFollowsYouLabel", "fancyBioFont", "savedOnProfile",
            "storyViewerSearch", "storyLinks", "storyInfo", "commentSearch", "followListExport", "followersHistory",
            // Lock / hide chats
            "lockDirectMessages", "lockWholeApp", "lockSavedCollections", "lockUseFingerprint",
            "hideSpecificChats",
            // Location / theme
            "spoofLocation", "customThemeEnabled", "customFontEnabled", "customEmojiEnabled",
            // Downloader
            "enablePostDownload", "enableStoryDownload", "enableReelDownload", "enableProfileDownload",
            "downloaderUsernameFolder", "downloaderAddTimestamp", "copyMediaLink", "offerAudioDownload",
            "enableVoiceDownload", "enableStickerDownload", "enableDmMediaDownload", "offerCopyImage", "offerMediaInfo", "offerPlaybackSpeed",
            "forceReelScrubber", "holdLeftSlowMotion", "maxUploadQuality", "saveInstants", "uploadInstants", "cacheStories",
            // Developer
            "isDevEnabled", "removeBuildExpiredPopup"
    };

    /** Distraction keys FeaturesFragment locks while Extreme Mode is on. */
    private static final List<String> EXTREME_LOCKED = Arrays.asList(
            "disableStories", "disableFeed", "disableReels", "disableReelsExceptDM",
            "disableExplore", "disableComments");

    /** A pinnable toggle on the Home dashboard. */
    private static final class Shortcut {
        final String key;
        final int labelRes;
        final int iconRes;
        final int accent;

        Shortcut(String key, int labelRes, int iconRes, int accent) {
            this.key = key;
            this.labelRes = labelRes;
            this.iconRes = iconRes;
            this.accent = accent;
        }
    }

    // Same icons / accent colours FeaturesFragment uses for these rows.
    private static final List<Shortcut> CATALOG = Arrays.asList(
            new Shortcut("isGhostSeen", R.string.ig_dialog_ghost_hide_dm_seen, R.drawable.ic_eye_off, 0xFF5E5CE6),
            new Shortcut("isGhostTyping", R.string.ig_dialog_ghost_hide_typing, R.drawable.ic_chat, 0xFF5E5CE6),
            new Shortcut("isGhostStory", R.string.ig_dialog_ghost_hide_story_views, R.drawable.ic_story_ring, 0xFF5E5CE6),
            new Shortcut("isGhostViewOnce", R.string.ig_dialog_ghost_hide_view_once, R.drawable.ic_eye_off, 0xFF5E5CE6),
            new Shortcut("isGhostScreenshot", R.string.ig_dialog_ghost_bypass_screenshot, R.drawable.ic_camera, 0xFF5E5CE6),
            new Shortcut("enablePostDownload", R.string.ig_dialog_downloader_posts, R.drawable.ic_download, 0xFFFF9F0A),
            new Shortcut("enableReelDownload", R.string.ig_dialog_downloader_reels, R.drawable.ic_download, 0xFFFF9F0A),
            new Shortcut("enableStoryDownload", R.string.ig_dialog_downloader_stories, R.drawable.ic_download, 0xFFFF9F0A),
            new Shortcut("isAdBlockEnabled", R.string.ig_dialog_ad_block_ads, R.drawable.ic_shield, 0xFFFF453A),
            new Shortcut("customThemeEnabled", R.string.theme_enable, R.drawable.ic_palette, 0xFFFF375F),
            new Shortcut("disableReels", R.string.ig_dialog_distraction_disable_reels, R.drawable.ic_movie, 0xFF30D158),
            new Shortcut("disableStories", R.string.ig_dialog_distraction_disable_stories, R.drawable.ic_story_ring, 0xFF30D158),
            new Shortcut("disableExplore", R.string.ig_dialog_distraction_disable_explore, R.drawable.ic_search, 0xFF30D158),
            new Shortcut("maxUploadQuality", R.string.ig_dialog_max_upload_quality, R.drawable.ic_upload, 0xFFFF9F0A)
    );

    private SharedPreferences localCache;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MaterialButton launchInstagramButton;
    private MaterialButton restartInstagramButton;
    private MaterialCardView instagramStatusCard;
    private TextView instagramStatusText;
    private TextView versionText;
    private TextView instagramVariantText;
    private MaterialButton instagramMultiButton;
    private ImageView instagramLogo;
    private ImageButton instagramInfoIcon;
    private View logoContainer;
    private View statusPill;
    private ImageView statusDot;
    private TextView statusPillText;
    private TextView featuresCountText;
    private LinearLayout shortcutsContainer;
    private TextView shortcutsEmpty;
    private TextView shortcutsHint;

    private String activePackage;
    private List<String> installedPackages;
    private boolean moduleConfirmed;
    private boolean pingReceiverRegistered;

    private AutoScroller contributorsScroller;
    private AutoScroller specialThanksScroller;

    /**
     * Liveness ping: the module answers ACTION_REQUEST_PREFS with ACTION_SEND_PREFS from inside a
     * running Instagram process. A reply proves the module is loaded; no reply just means
     * Instagram isn't running, so we fall back to the plain install state. We only use the reply
     * as a signal — FeaturesFragment remains the one that syncs its contents into the cache.
     */
    private String pingNonce;
    private ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending pingReply;

    private final BroadcastReceiver pingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ps.reso.instaeclipse.ACTION_SEND_PREFS".equals(intent.getAction())
                    && ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(pingNonce, intent)) {
                moduleConfirmed = true;
                if (isAdded() && getView() != null) updateStatusPill();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        localCache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(requireContext());

        launchInstagramButton = view.findViewById(R.id.launch_instagram_button);
        restartInstagramButton = view.findViewById(R.id.restart_instagram_button);
        MaterialButton downloadButton = view.findViewById(R.id.download_instagram_button);

        instagramStatusCard = view.findViewById(R.id.instagram_status_card);
        instagramStatusText = view.findViewById(R.id.instagram_status_text);
        versionText = view.findViewById(R.id.home_version_text);
        instagramVariantText = view.findViewById(R.id.instagram_variant_text);
        instagramMultiButton = view.findViewById(R.id.instagram_multi_button);
        instagramLogo = view.findViewById(R.id.instagram_logo);
        instagramInfoIcon = view.findViewById(R.id.instagram_info_icon);
        logoContainer = view.findViewById(R.id.home_logo_container);
        statusPill = view.findViewById(R.id.home_status_pill);
        statusDot = view.findViewById(R.id.home_status_dot);
        statusPillText = view.findViewById(R.id.home_status_pill_text);
        featuresCountText = view.findViewById(R.id.home_features_count);
        shortcutsContainer = view.findViewById(R.id.home_shortcuts_container);
        shortcutsEmpty = view.findViewById(R.id.home_shortcuts_empty);
        shortcutsHint = view.findViewById(R.id.home_shortcuts_hint);

        int primary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary);
        applyTonalSquare(view.findViewById(R.id.home_features_icon_container),
                view.findViewById(R.id.home_features_icon), primary);
        applyTonalSquare(view.findViewById(R.id.home_howto_icon_container),
                view.findViewById(R.id.search_icon), primary);

        GradientDrawable variantBg = new GradientDrawable();
        variantBg.setCornerRadius(dp(14));
        variantBg.setColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHighest));
        instagramVariantText.setBackground(variantBg);

        checkInstagramStatus();

        restartInstagramButton.setOnClickListener(v -> restartInstagram());
        downloadButton.setOnClickListener(v -> {
            String url = ps.reso.instaeclipse.utils.core.ExportBrand.TESTED_INSTAGRAM_APK;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
        view.findViewById(R.id.home_shortcuts_edit).setOnClickListener(v -> showEditShortcutsDialog());

        setupContributorsAndSpecialThanks(view);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (contributorsScroller != null) contributorsScroller.start();
        if (specialThanksScroller != null) specialThanksScroller.start();
        // Prefs may have changed in the Features tab or from inside Instagram.
        updateFeatureCount();
        renderShortcuts();
        pingModule();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (contributorsScroller != null) contributorsScroller.stop();
        if (specialThanksScroller != null) specialThanksScroller.stop();
        if (pingReceiverRegistered) {
            if (pingReply != null) { pingReply.cancel(); pingReply = null; }
            pingReceiverRegistered = false;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (contributorsScroller != null) contributorsScroller.stop();
        if (specialThanksScroller != null) specialThanksScroller.stop();
        mainHandler.removeCallbacksAndMessages(null);
    }

    // =========================================================
    // STATUS HERO
    // =========================================================

    private void checkInstagramStatus() {
        PackageManager pm = requireContext().getPackageManager();

        installedPackages = new ArrayList<>();
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                installedPackages.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        if (installedPackages.isEmpty()) {
            activePackage = null;
            instagramStatusText.setText(getString(R.string.not_installed_instagram));
            versionText.setText(getString(R.string.home_status_no_version));
            instagramLogo.setImageResource(R.drawable.ic_cancel);
            applyTonalSquare(logoContainer, instagramLogo, COLOR_ERROR);
            instagramVariantText.setVisibility(View.GONE);
            instagramMultiButton.setVisibility(View.GONE);
            instagramInfoIcon.setVisibility(View.GONE);
            launchInstagramButton.setEnabled(false);
            restartInstagramButton.setEnabled(false);
            updateStatusPill();
            return;
        }

        // Prefer the official package; fall back to first found mod
        activePackage = installedPackages.contains(CommonUtils.IG_PACKAGE_NAME)
                ? CommonUtils.IG_PACKAGE_NAME
                : installedPackages.get(0);

        instagramStatusText.setText(getString(R.string.installed_instagram_version));
        instagramLogo.setImageResource(R.drawable.ic_instagram_logo);
        applyTonalSquare(logoContainer, instagramLogo, COLOR_INSTAGRAM);
        instagramVariantText.setVisibility(View.VISIBLE);
        instagramInfoIcon.setVisibility(View.VISIBLE);

        if (installedPackages.size() > 1) {
            instagramMultiButton.setVisibility(View.VISIBLE);
            instagramMultiButton.setOnClickListener(v -> showDetectedVersionsDialog(pm));
        } else {
            instagramMultiButton.setVisibility(View.GONE);
        }

        bindPackageActions(pm, activePackage);
        updateStatusPill();
    }

    private void bindPackageActions(PackageManager pm, String pkg) {
        activePackage = pkg;

        try {
            String versionName = pm.getPackageInfo(pkg, 0).versionName;
            versionText.setText(getString(R.string.home_status_version, versionName));
        } catch (PackageManager.NameNotFoundException e) {
            versionText.setText("");
        }

        instagramVariantText.setText(CommonUtils.getVariantLabel(pkg));

        instagramInfoIcon.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        launchInstagramButton.setOnClickListener(v -> launchPackage(pkg));
    }

    private void launchPackage(String pkg) {
        if (!isAdded()) return;
        Intent launchIntent = requireContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (launchIntent != null) {
            startActivity(launchIntent);
        } else {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(getActivity(), getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateStatusPill() {
        int color;
        String label;
        if (installedPackages == null || installedPackages.isEmpty()) {
            color = COLOR_ERROR;
            label = getString(R.string.home_status_not_installed);
        } else if (moduleConfirmed) {
            color = COLOR_OK;
            label = getString(R.string.home_status_module_active);
        } else {
            color = COLOR_OK;
            label = getString(R.string.home_status_installed);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor((color & 0x00FFFFFF) | 0x29000000);
        statusPill.setBackground(bg);
        statusDot.setImageTintList(ColorStateList.valueOf(color));
        statusPillText.setTextColor(color);
        statusPillText.setText(label);
    }

    private void updateFeatureCount() {
        if (featuresCountText == null) return;
        int count = 0;
        for (String key : FEATURE_KEYS) {
            if (localCache.getBoolean(key, false)) count++;
        }
        for (String key : ps.reso.instaeclipse.utils.feature.ExtrasFeatures.flags()) {
            if (localCache.getBoolean(key, false)) count++;
        }
        featuresCountText.setText(getResources().getQuantityString(R.plurals.home_features_enabled, count, count));
    }

    private void pingModule() {
        if (installedPackages == null || installedPackages.isEmpty() || pingReceiverRegistered) return;
        IntentFilter filter = new IntentFilter("ps.reso.instaeclipse.ACTION_SEND_PREFS");
        try {
            // Same registration as FeaturesFragment: the reply comes from the Instagram process.
            if (pingReply != null) pingReply.cancel();
            pingReceiverRegistered = true;
            pingNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
            pingReply = ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(requireContext(),
                    new Intent("ps.reso.instaeclipse.ACTION_REQUEST_PREFS").setPackage(activePackage)
                            .putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, pingNonce),
                    "ps.reso.instaeclipse.ACTION_SEND_PREFS", pingReceiver);
        } catch (Throwable ignored) { }
    }

    // =========================================================
    // QUICK ACTIONS
    // =========================================================

    /**
     * FeaturesFragment's "Restart App" only asks the user to kill Instagram manually. Here we
     * first try a root force-stop + relaunch; without root we fall back to that same message and
     * open Instagram's App info page, where the Force stop button lives.
     */
    private void restartInstagram() {
        final String pkg = activePackage;
        if (pkg == null) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
            return;
        }
        restartInstagramButton.setEnabled(false);
        new Thread(() -> {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
                if (p.waitFor(15, TimeUnit.SECONDS)) {
                    ok = p.exitValue() == 0;
                } else {
                    p.destroy();
                }
            } catch (Throwable ignored) { }
            final boolean rooted = ok;
            mainHandler.post(() -> {
                if (!isAdded() || getView() == null) return;
                restartInstagramButton.setEnabled(true);
                if (rooted) {
                    moduleConfirmed = false;
                    updateStatusPill();
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.home_restart_done), Toast.LENGTH_SHORT).show();
                    mainHandler.postDelayed(() -> launchPackage(pkg), 400);
                } else {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + pkg));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Throwable ignored) { }
                }
            });
        }, "ie-restart-ig").start();
    }

    // =========================================================
    // QUICK SHORTCUTS
    // =========================================================

    private List<String> getPinnedKeys() {
        String raw = localCache.getString(PREF_PINNED, null);
        if (raw == null) raw = DEFAULT_PINNED;
        List<String> out = new ArrayList<>();
        for (String k : raw.split(",")) {
            String key = k.trim();
            if (!key.isEmpty() && findShortcut(key) != null && !out.contains(key)) out.add(key);
            if (out.size() >= MAX_PINNED) break;
        }
        return out;
    }

    @Nullable
    private static Shortcut findShortcut(String key) {
        for (Shortcut s : CATALOG) if (s.key.equals(key)) return s;
        return null;
    }

    private void renderShortcuts() {
        if (shortcutsContainer == null) return;
        shortcutsContainer.removeAllViews();
        List<String> pinned = getPinnedKeys();
        boolean extreme = localCache.getBoolean("isExtremeMode", false);
        for (String key : pinned) {
            Shortcut s = findShortcut(key);
            if (s != null) shortcutsContainer.addView(buildShortcutRow(s, extreme && EXTREME_LOCKED.contains(s.key)));
        }
        boolean empty = shortcutsContainer.getChildCount() == 0;
        shortcutsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        shortcutsHint.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private View buildShortcutRow(Shortcut s, boolean locked) {
        Context ctx = requireContext();

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        TypedValue ripple = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        FrameLayout iconBox = new FrameLayout(ctx);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(s.iconRes);
        icon.setContentDescription(null);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        iconBox.addView(icon, iconLp);
        applyTonalSquare(iconBox, icon, s.accent);
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(s.labelRes);
        title.setTextAppearance(resolveStyle(com.google.android.material.R.attr.textAppearanceBodyLarge));
        title.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(title);
        if (locked) {
            TextView sub = new TextView(ctx);
            sub.setText(R.string.home_shortcuts_locked_extreme);
            sub.setTextAppearance(resolveStyle(com.google.android.material.R.attr.textAppearanceBodySmall));
            sub.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant));
            texts.addView(sub);
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(14));
        textLp.setMarginEnd(dp(12));
        row.addView(texts, textLp);

        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setChecked(localCache.getBoolean(s.key, false));
        sw.setContentDescription(getString(s.labelRes));
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (locked) {
            sw.setEnabled(false);
            row.setEnabled(false);
            row.setAlpha(0.4f);
        } else {
            sw.setOnCheckedChangeListener((btn, checked) -> {
                setFeature(s.key, checked);
                updateFeatureCount();
            });
            row.setOnClickListener(v -> sw.toggle());
        }
        return row;
    }

    /**
     * Writes a boolean feature pref exactly like FeaturesFragment does: commit() to the
     * companion's cache, make the file world-readable for XSharedPreferences on a cold IG start,
     * and broadcast ACTION_UPDATE_PREF so a running Instagram applies it live.
     */
    private void setFeature(String key, boolean value) {
        SharedPreferences.Editor editor = localCache.edit();
        editor.putBoolean(key, value);
        // Mirror FeaturesFragment's cascade: turning Reels back on also clears "except in DMs".
        boolean cascade = !value && "disableReels".equals(key)
                && localCache.getBoolean("disableReelsExceptDM", false);
        if (cascade) editor.putBoolean("disableReelsExceptDM", false);
        editor.commit();
        makeLocalCacheWorldReadable();

        sendPrefBroadcast(key, value);
        if (cascade) sendPrefBroadcast("disableReelsExceptDM", false);
    }

    private void sendPrefBroadcast(String key, boolean value) {
        Intent intent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        intent.putExtra("key", key);
        intent.putExtra("value", value);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intent);
    }

    private void makeLocalCacheWorldReadable() {
        try {
            java.io.File prefsFile = new java.io.File(
                    requireContext().getApplicationInfo().dataDir + "/shared_prefs/" + PREFS_NAME + ".xml");
            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) { }
    }

    private void showEditShortcutsDialog() {
        final List<String> current = getPinnedKeys();
        final String[] labels = new String[CATALOG.size()];
        final boolean[] checked = new boolean[CATALOG.size()];
        for (int i = 0; i < CATALOG.size(); i++) {
            labels[i] = getString(CATALOG.get(i).labelRes);
            checked[i] = current.contains(CATALOG.get(i).key);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_shortcuts_dialog_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        int count = 0;
                        for (boolean b : checked) if (b) count++;
                        if (count > MAX_PINNED) {
                            checked[which] = false;
                            ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(),
                                    getString(R.string.home_shortcuts_limit, MAX_PINNED), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // Keep the existing order for shortcuts that stay pinned, append new ones.
                    List<String> result = new ArrayList<>();
                    for (String key : current) {
                        int idx = indexOfShortcut(key);
                        if (idx >= 0 && checked[idx]) result.add(key);
                    }
                    for (int i = 0; i < CATALOG.size(); i++) {
                        String key = CATALOG.get(i).key;
                        if (checked[i] && !result.contains(key) && result.size() < MAX_PINNED) result.add(key);
                    }
                    localCache.edit().putString(PREF_PINNED, String.join(",", result)).commit();
                    makeLocalCacheWorldReadable();
                    renderShortcuts();
                })
                .setNegativeButton(R.string.ig_dialog_cancel, null)
                .show();
    }

    private static int indexOfShortcut(String key) {
        for (int i = 0; i < CATALOG.size(); i++) if (CATALOG.get(i).key.equals(key)) return i;
        return -1;
    }

    // =========================================================
    // STYLE HELPERS
    // =========================================================

    /** 40dp rounded-square tonal container, same recipe as FeaturesFragment's row icons. */
    private void applyTonalSquare(View container, ImageView icon, int accent) {
        if (container == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((accent & 0x00FFFFFF) | 0x33000000);
        bg.setCornerRadius(dp(12));
        container.setBackground(bg);
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(accent));
    }

    private int resolveStyle(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // =========================================================
    // CONTRIBUTORS
    // =========================================================

    private void setupContributorsAndSpecialThanks(View rootView) {
        HorizontalScrollView contributorsScroll = rootView.findViewById(R.id.contributors_scroll);
        HorizontalScrollView specialThanksScroll = rootView.findViewById(R.id.special_thanks_scroll);
        LinearLayout contributorsContainer = rootView.findViewById(R.id.contributors_container);
        LinearLayout specialThanksContainer = rootView.findViewById(R.id.special_thanks_container);

        List<Contributor> contributors = Arrays.asList(
                new Contributor("ReSo7200", "https://github.com/ReSo7200", "https://linkedin.com/in/abdalhaleem-altamimi", null),
                new Contributor("swakwork", "https://github.com/swakwork", null, null),
                new Contributor("isma3iloiso", "https://github.com/isma3iloiso", null, null),
                new Contributor("Placeholder6", "https://github.com/Placeholder6", null, null),
                new Contributor("frknkrc44", "https://github.com/frknkrc44", null, null),
                new Contributor("BrianML", "https://github.com/brianml31", null, "https://t.me/instamoon_channel"),
                new Contributor("silvzr", "https://github.com/silvzr", null, null),
                new Contributor("oct", "https://github.com/oct888", null, null),
                new Contributor("HalfManBear", "https://github.com/halfmanbear", null, null),
                new Contributor("ar5to", "https://github.com/ar5to", null, "https://t.me/ar5to"),
                new Contributor("particle-box", "https://github.com/particle-box", null, null),
                new Contributor("rsr", null, null, "https://t.me/rsr1337"),
                // Merged-PR authors that were missing from this list.
                new Contributor("akifakif32", "https://github.com/akifakif32", null, null),
                new Contributor("HackZy01", "https://github.com/HackZy01", null, null),
                new Contributor("Xiddoc", "https://github.com/Xiddoc", null, null),
                new Contributor("GlitchDaBest", "https://github.com/GlitchDaBest", null, null),
                new Contributor("NicKz101", "https://github.com/NicKz101", null, null),
                new Contributor("HcNguyen111", "https://github.com/HcNguyen111", null, null),
                new Contributor("Lxchoooo", "https://github.com/Lxchoooo", null, null),
                new Contributor("EscapeA", "https://github.com/EscapeA", null, null),
                new Contributor("Figim", "https://github.com/Figim", null, null),
                new Contributor("oka1da", "https://github.com/oka1da", null, null),
                new Contributor("uurcan7", "https://github.com/uurcan7", null, null),
                new Contributor("zarzet", "https://github.com/zarzet", null, null),
                new Contributor("xxOrdulu52xx", "https://github.com/xxOrdulu52xx", null, null),
                new Contributor("dpwbusr", "https://github.com/dpwbusr", null, null),
                new Contributor("d9k6s", "https://github.com/d9k6s", null, null),
                new Contributor("izadiegizabal", "https://github.com/izadiegizabal", null, null),
                new Contributor("b4lol", "https://github.com/b4lol", null, null)
        );

        List<Contributor> specialThanks = Arrays.asList(
                new Contributor("xHookman", "https://github.com/xHookman", null, null),
                new Contributor("Bluepapilte", null, null, "https://t.me/instasmashrepo"),
                new Contributor("BdrcnAYYDIN", null, null, "https://t.me/BdrcnAYYDIN"),
                new Contributor("Amàzing World", null, null, null)
        );

        // Inflate each list twice so the loop restarts seamlessly
        inflateCards(contributors, contributorsContainer);
        inflateCards(contributors, contributorsContainer);
        inflateCards(specialThanks, specialThanksContainer);
        inflateCards(specialThanks, specialThanksContainer);

        // Start infinite scroll once layout is complete
        contributorsContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        contributorsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int halfWidth = contributorsContainer.getWidth() / 2;
                        contributorsScroller = new AutoScroller(contributorsScroll, halfWidth);
                        contributorsScroller.attachTouchPause();
                        if (isResumed()) contributorsScroller.start();
                    }
                });

        specialThanksContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        specialThanksContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int halfWidth = specialThanksContainer.getWidth() / 2;
                        specialThanksScroller = new AutoScroller(specialThanksScroll, halfWidth);
                        specialThanksScroller.attachTouchPause();
                        if (isResumed()) specialThanksScroller.start();
                    }
                });
    }

    /**
     * Continuous, seamless loop scroller for a duplicated-content HorizontalScrollView. It advances
     * the scroll by a fixed speed every animation frame, reading the LIVE scroll position and
     * wrapping it modulo one copy's width — so after a user drag it simply continues from wherever
     * the finger left off (the content is duplicated, so the wrap is invisible). Momentum fling is
     * disabled on the view (LoopScrollView), which is what removes the old snap-back / random jump.
     */
    private final class AutoScroller implements Runnable {
        private final HorizontalScrollView sv;
        private final int halfWidth;
        private final float stepPxPerMs;
        private boolean running;
        private boolean paused;
        private long lastFrame;

        AutoScroller(HorizontalScrollView sv, int halfWidth) {
            this.sv = sv;
            this.halfWidth = halfWidth;
            this.stepPxPerMs = SCROLL_SPEED_DP_PER_SEC * getResources().getDisplayMetrics().density / 1000f;
        }

        void start() {
            if (running || halfWidth <= 0) return;
            running = true;
            lastFrame = 0;
            sv.postOnAnimation(this);
        }

        void stop() {
            running = false;
            sv.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (!running) return;
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            if (lastFrame == 0) lastFrame = now;
            long dt = now - lastFrame;
            lastFrame = now;
            if (!paused && halfWidth > 0) {
                int x = sv.getScrollX() + Math.round(stepPxPerMs * dt);
                x %= halfWidth;
                if (x < 0) x += halfWidth;
                sv.scrollTo(x, 0);
            }
            sv.postOnAnimation(this);
        }

        @SuppressLint("ClickableViewAccessibility")
        void attachTouchPause() {
            sv.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        paused = true;      // let the user drag freely
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        paused = false;     // resume from the live position (no fling to fight)
                        lastFrame = 0;
                        break;
                }
                return false; // keep normal drag handling
            });
        }
    }

    private void inflateCards(List<Contributor> list, LinearLayout container) {
        for (Contributor c : list) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.contributor_card, container, false);
            setupContributorCard(v, c);
            container.addView(v);
        }
    }

    // A small palette of pleasant, saturated hues for the monogram avatars — each contributor gets
    // a stable colour derived from their name so the row reads as a set of distinct people.
    private static final int[] AVATAR_COLORS = {
            0xFF5E5CE6, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFF64D2FF,
            0xFFBF5AF2, 0xFFFF9500, 0xFF32D74B, 0xFF0A84FF, 0xFFFFD60A
    };

    private void setupContributorCard(View view, Contributor contributor) {
        TextView nameTextView = view.findViewById(R.id.contributor_name);
        nameTextView.setText(contributor.name());

        // Monogram avatar: first letter on a per-name coloured circle.
        TextView avatar = view.findViewById(R.id.contributor_avatar);
        if (avatar != null) {
            String name = contributor.name() == null ? "" : contributor.name().trim();
            String initial = "?";
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if (Character.isLetterOrDigit(ch)) { initial = String.valueOf(Character.toUpperCase(ch)); break; }
            }
            int color = AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length];
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color);
            avatar.setText(initial);
            avatar.setBackground(circle);
        }

        // Overlay the GitHub profile photo when the contributor has a GitHub link — github.com/<user>.png
        // resolves to their avatar. On no-link/failure the monogram above stays visible.
        ImageView avatarImg = view.findViewById(R.id.contributor_avatar_img);
        if (avatarImg != null) {
            avatarImg.setVisibility(View.GONE); // reset (fresh inflate, but be safe)
            String gh = contributor.githubUrl();
            if (gh != null && !gh.trim().isEmpty()) {
                String u = gh.trim();
                if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                ps.reso.instaeclipse.utils.ui.AvatarLoader.loadCircular(u + ".png?size=144", avatarImg);
            }
        }

        ImageButton githubButton = view.findViewById(R.id.github_button);
        if (contributor.githubUrl() != null) {
            githubButton.setVisibility(View.VISIBLE);
            githubButton.setOnClickListener(v -> openLink(contributor.githubUrl()));
        } else {
            githubButton.setVisibility(View.GONE);
        }

        ImageButton linkedinButton = view.findViewById(R.id.linkedin_button);
        if (contributor.linkedinUrl() != null) {
            linkedinButton.setVisibility(View.VISIBLE);
            linkedinButton.setOnClickListener(v -> openLink(contributor.linkedinUrl()));
        } else {
            linkedinButton.setVisibility(View.GONE);
        }

        ImageButton telegramButton = view.findViewById(R.id.telegram_button);
        if (contributor.telegramUrl() != null) {
            telegramButton.setVisibility(View.VISIBLE);
            telegramButton.setOnClickListener(v -> openLink(contributor.telegramUrl()));
        } else {
            telegramButton.setVisibility(View.GONE);
        }
    }

    private void showDetectedVersionsDialog(PackageManager pm) {
        String[] labels = new String[installedPackages.size()];
        for (int i = 0; i < installedPackages.size(); i++) {
            String pkg = installedPackages.get(i);
            String version = "?";
            try { version = pm.getPackageInfo(pkg, 0).versionName; }
            catch (PackageManager.NameNotFoundException ignored) { }
            labels[i] = CommonUtils.getVariantLabel(pkg) + "  —  v" + version;
        }

        int currentIndex = installedPackages.indexOf(activePackage);
        final int[] selectedIndex = {currentIndex};

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Detected versions")
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton("Use this", (dialog, which) -> {
                    String chosen = installedPackages.get(selectedIndex[0]);
                    if (!chosen.equals(activePackage)) {
                        bindPackageActions(pm, chosen);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openLink(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
