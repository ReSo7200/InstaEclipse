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

        // Miscellaneous
        if (FeatureFlags.showFollowerToast) {
            FeatureStatusTracker.setEnabled("ShowFollowerToast");
        } else {
            FeatureStatusTracker.setDisabled("ShowFollowerToast");
        }

        if (FeatureFlags.disableTrackingLinks) {
            FeatureStatusTracker.setEnabled("DisableTrackingLinks");
        } else {
            FeatureStatusTracker.setDisabled("DisableTrackingLinks");
        }
    }
}
