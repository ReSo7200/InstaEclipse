package ps.reso.instaeclipse.utils.feature;

public class FeatureManager {

    public static void refreshFeatureStatus() {
        // Developer Options
        if (FeatureFlags.isDevEnabled) {
            FeatureStatusTracker.setEnabled("DevOptions");
        } else {
            FeatureStatusTracker.setDisabled("DevOptions");
        }

        // Ghost Mode
        if (FeatureFlags.isGhostSeen) {
            FeatureStatusTracker.setEnabled("GhostSeen");
        } else {
            FeatureStatusTracker.setDisabled("GhostSeen");
        }

        if (FeatureFlags.isGhostTyping) {
            FeatureStatusTracker.setEnabled("GhostTyping");
        } else {
            FeatureStatusTracker.setDisabled("GhostTyping");
        }

        if (FeatureFlags.isGhostScreenshot) {
            FeatureStatusTracker.setEnabled("GhostScreenshot");
        } else {
            FeatureStatusTracker.setDisabled("GhostScreenshot");
        }

        if (FeatureFlags.isGhostViewOnce) {
            FeatureStatusTracker.setEnabled("GhostViewOnce");
        } else {
            FeatureStatusTracker.setDisabled("GhostViewOnce");
        }

        if (FeatureFlags.enableUnlimitedReplays) {
            FeatureStatusTracker.setEnabled("UnlimitedReplays");
        } else {
            FeatureStatusTracker.setDisabled("UnlimitedReplays");
        }

        if (FeatureFlags.isGhostStory) {
            FeatureStatusTracker.setEnabled("GhostStories");
        } else {
            FeatureStatusTracker.setDisabled("GhostStories");
        }

        if (FeatureFlags.isGhostLive) {
            FeatureStatusTracker.setEnabled("GhostLive");
        } else {
            FeatureStatusTracker.setDisabled("GhostLive");
        }

        if (FeatureFlags.allowScreenshots) {
            FeatureStatusTracker.setEnabled("AllowScreenshots");
        } else {
            FeatureStatusTracker.setDisabled("AllowScreenshots");
        }

        if (FeatureFlags.keepEphemeralMessages) {
            FeatureStatusTracker.setEnabled("KeepEphemeralMessages");
        } else {
            FeatureStatusTracker.setDisabled("KeepEphemeralMessages");
        }

        if (FeatureFlags.permanentViewMode) {
            FeatureStatusTracker.setEnabled("PermanentViewMode");
        } else {
            FeatureStatusTracker.setDisabled("PermanentViewMode");
        }

        // Miscellaneous
        if (FeatureFlags.disableTrackingLinks) {
            FeatureStatusTracker.setEnabled("DisableTrackingLinks");
        } else {
            FeatureStatusTracker.setDisabled("DisableTrackingLinks");
        }

        if (FeatureFlags.showFollowerToast) {
            FeatureStatusTracker.setEnabled("FollowerToast");
        } else {
            FeatureStatusTracker.setDisabled("FollowerToast");
        }

        if (FeatureFlags.enableStoryMentions) {
            FeatureStatusTracker.setEnabled("StoryMentions");
        } else {
            FeatureStatusTracker.setDisabled("StoryMentions");
        }

        if (FeatureFlags.disableDiscoverPeople) {
            FeatureStatusTracker.setEnabled("DisableDiscoverPeople");
        } else {
            FeatureStatusTracker.setDisabled("DisableDiscoverPeople");
        }

        if (FeatureFlags.removeBuildExpiredPopup) {
            FeatureStatusTracker.setEnabled("RemoveBuildExpiredPopup");
        } else {
            FeatureStatusTracker.setDisabled("RemoveBuildExpiredPopup");
        }

        if (FeatureFlags.enablePostDownload) {
            FeatureStatusTracker.setEnabled("PostDownload");
        } else {
            FeatureStatusTracker.setDisabled("PostDownload");
        }

        if (FeatureFlags.enableStoryDownload) {
            FeatureStatusTracker.setEnabled("StoryDownload");
        } else {
            FeatureStatusTracker.setDisabled("StoryDownload");
        }

        if (FeatureFlags.enableReelDownload) {
            FeatureStatusTracker.setEnabled("ReelDownload");
        } else {
            FeatureStatusTracker.setDisabled("ReelDownload");
        }

        if (FeatureFlags.enableProfileDownload) {
            FeatureStatusTracker.setEnabled("ProfileDownload");
        } else {
            FeatureStatusTracker.setDisabled("ProfileDownload");
        }
    }
}
