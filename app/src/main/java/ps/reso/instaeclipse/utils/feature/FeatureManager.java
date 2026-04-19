package ps.reso.instaeclipse.utils.feature;

import ps.reso.instaeclipse.R;

public class FeatureManager {

    public static void refreshFeatureStatus() {
        // Developer Options
        if (FeatureFlags.isDevEnabled) {
            FeatureStatusTracker.setEnabled("DevOptions", R.string.ig_dialog_section_dev_options);
        } else {
            FeatureStatusTracker.setDisabled("DevOptions");
        }

        // Ghost Mode
        if (FeatureFlags.isGhostSeen) {
            FeatureStatusTracker.setEnabled("GhostSeen", R.string.ig_dialog_ghost_hide_dm_seen);
        } else {
            FeatureStatusTracker.setDisabled("GhostSeen");
        }

        if (FeatureFlags.isGhostTyping) {
            FeatureStatusTracker.setEnabled("GhostTyping", R.string.ig_dialog_ghost_hide_typing);
        } else {
            FeatureStatusTracker.setDisabled("GhostTyping");
        }

        if (FeatureFlags.isGhostScreenshot) {
            FeatureStatusTracker.setEnabled("GhostScreenshot", R.string.ig_dialog_ghost_bypass_screenshot);
        } else {
            FeatureStatusTracker.setDisabled("GhostScreenshot");
        }

        if (FeatureFlags.isGhostViewOnce) {
            FeatureStatusTracker.setEnabled("GhostViewOnce", R.string.ig_dialog_ghost_hide_view_once);
        } else {
            FeatureStatusTracker.setDisabled("GhostViewOnce");
        }

        if (FeatureFlags.enableUnlimitedReplays) {
            FeatureStatusTracker.setEnabled("UnlimitedReplays", R.string.ig_dialog_ghost_unlimited_replays);
        } else {
            FeatureStatusTracker.setDisabled("UnlimitedReplays");
        }

        if (FeatureFlags.isGhostStory) {
            FeatureStatusTracker.setEnabled("GhostStories", R.string.ig_dialog_ghost_hide_story_views);
        } else {
            FeatureStatusTracker.setDisabled("GhostStories");
        }

        if (FeatureFlags.isGhostLive) {
            FeatureStatusTracker.setEnabled("GhostLive", R.string.ig_dialog_ghost_hide_live_presence);
        } else {
            FeatureStatusTracker.setDisabled("GhostLive");
        }

        if (FeatureFlags.allowScreenshots) {
            FeatureStatusTracker.setEnabled("AllowScreenshots", R.string.ig_dialog_ghost_allow_screenshots_dms);
        } else {
            FeatureStatusTracker.setDisabled("AllowScreenshots");
        }

        if (FeatureFlags.keepEphemeralMessages) {
            FeatureStatusTracker.setEnabled("KeepEphemeralMessages", R.string.ig_dialog_ghost_keep_disappearing);
        } else {
            FeatureStatusTracker.setDisabled("KeepEphemeralMessages");
        }

        if (FeatureFlags.permanentViewMode) {
            FeatureStatusTracker.setEnabled("PermanentViewMode", R.string.ig_dialog_ghost_permanent_view_once);
        } else {
            FeatureStatusTracker.setDisabled("PermanentViewMode");
        }

        // Clean Feed
        if (FeatureFlags.hideSuggestionsInFeed) {
            FeatureStatusTracker.setEnabled("HideSuggestionsInFeed", R.string.ig_dialog_clean_feed_hide_suggested);
        } else {
            FeatureStatusTracker.setDisabled("HideSuggestionsInFeed");
        }

        // Miscellaneous
        if (FeatureFlags.disableTrackingLinks) {
            FeatureStatusTracker.setEnabled("DisableTrackingLinks", R.string.ig_dialog_ad_disable_tracking);
        } else {
            FeatureStatusTracker.setDisabled("DisableTrackingLinks");
        }

        if (FeatureFlags.showFollowerToast) {
            FeatureStatusTracker.setEnabled("FollowerToast", R.string.ig_dialog_misc_show_follower_toast);
        } else {
            FeatureStatusTracker.setDisabled("FollowerToast");
        }

        if (FeatureFlags.enableStoryMentions) {
            FeatureStatusTracker.setEnabled("StoryMentions", R.string.ig_dialog_misc_view_story_mentions);
        } else {
            FeatureStatusTracker.setDisabled("StoryMentions");
        }

        if (FeatureFlags.disableDiscoverPeople) {
            FeatureStatusTracker.setEnabled("DisableDiscoverPeople", R.string.ig_dialog_misc_disable_discover_people);
        } else {
            FeatureStatusTracker.setDisabled("DisableDiscoverPeople");
        }

        if (FeatureFlags.removeBuildExpiredPopup) {
            FeatureStatusTracker.setEnabled("RemoveBuildExpiredPopup", R.string.ig_dialog_dev_remove_build_expired);
        } else {
            FeatureStatusTracker.setDisabled("RemoveBuildExpiredPopup");
        }

        if (FeatureFlags.enablePostDownload) {
            FeatureStatusTracker.setEnabled("PostDownload", R.string.ig_dialog_downloader_posts);
        } else {
            FeatureStatusTracker.setDisabled("PostDownload");
        }

        if (FeatureFlags.enableStoryDownload) {
            FeatureStatusTracker.setEnabled("StoryDownload", R.string.ig_dialog_downloader_stories);
        } else {
            FeatureStatusTracker.setDisabled("StoryDownload");
        }

        if (FeatureFlags.enableReelDownload) {
            FeatureStatusTracker.setEnabled("ReelDownload", R.string.ig_dialog_downloader_reels);
        } else {
            FeatureStatusTracker.setDisabled("ReelDownload");
        }

        if (FeatureFlags.enableProfileDownload) {
            FeatureStatusTracker.setEnabled("ProfileDownload", R.string.ig_dialog_downloader_profiles);
        } else {
            FeatureStatusTracker.setDisabled("ProfileDownload");
        }

        if (FeatureFlags.disableDoubleTapLike) {
            FeatureStatusTracker.setEnabled("DisableDoubleTapLike", R.string.ig_dialog_misc_disable_double_tap_like);
        } else {
            FeatureStatusTracker.setDisabled("DisableDoubleTapLike");
        }
    }
}
