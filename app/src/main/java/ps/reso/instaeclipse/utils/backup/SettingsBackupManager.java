package ps.reso.instaeclipse.utils.backup;

import org.json.JSONException;
import org.json.JSONObject;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class SettingsBackupManager {

    private static final int VERSION = 1;

    /** Serialises every known FeatureFlag into a versioned JSON string. */
    public static String toJson() throws JSONException {
        JSONObject s = new JSONObject();

        // Developer
        s.put("isDevEnabled",            FeatureFlags.isDevEnabled);
        s.put("removeBuildExpiredPopup", FeatureFlags.removeBuildExpiredPopup);

        // Ghost Mode
        s.put("isGhostSeen",             FeatureFlags.isGhostSeen);
        s.put("isGhostTyping",           FeatureFlags.isGhostTyping);
        s.put("isGhostScreenshot",       FeatureFlags.isGhostScreenshot);
        s.put("isGhostViewOnce",         FeatureFlags.isGhostViewOnce);
        s.put("enableUnlimitedReplays",  FeatureFlags.enableUnlimitedReplays);
        s.put("isGhostStory",            FeatureFlags.isGhostStory);
        s.put("isGhostLive",             FeatureFlags.isGhostLive);
        s.put("allowScreenshots",        FeatureFlags.allowScreenshots);
        s.put("keepEphemeralMessages",   FeatureFlags.keepEphemeralMessages);

        s.put("permanentViewMode",       FeatureFlags.permanentViewMode);

        // Quick Toggles
        s.put("quickToggleSeen",         FeatureFlags.quickToggleSeen);
        s.put("quickToggleTyping",       FeatureFlags.quickToggleTyping);
        s.put("quickToggleScreenshot",   FeatureFlags.quickToggleScreenshot);
        s.put("quickToggleViewOnce",     FeatureFlags.quickToggleViewOnce);
        s.put("quickToggleStory",        FeatureFlags.quickToggleStory);
        s.put("quickToggleLive",         FeatureFlags.quickToggleLive);
        s.put("quickToggleEphemeral",    FeatureFlags.quickToggleEphemeral);
        s.put("quickToggleReplays",      FeatureFlags.quickToggleReplays);
        s.put("quickTogglePermanentView",FeatureFlags.quickTogglePermanentView);
        s.put("quickToggleAllowScreenshots", FeatureFlags.quickToggleAllowScreenshots);

        // Ads
        s.put("isAdBlockEnabled",        FeatureFlags.isAdBlockEnabled);
        s.put("isAnalyticsBlocked",      FeatureFlags.isAnalyticsBlocked);
        s.put("disableTrackingLinks",    FeatureFlags.disableTrackingLinks);

        // Distraction Free
        s.put("isExtremeMode",           FeatureFlags.isExtremeMode);
        s.put("disableStories",          FeatureFlags.disableStories);
        s.put("disableFeed",             FeatureFlags.disableFeed);
        s.put("disableReels",            FeatureFlags.disableReels);
        s.put("disableReelsExceptDM",    FeatureFlags.disableReelsExceptDM);
        s.put("disableExplore",          FeatureFlags.disableExplore);
        s.put("disableComments",         FeatureFlags.disableComments);
        s.put("disableDiscoverPeople",   FeatureFlags.disableDiscoverPeople);

        // Miscellaneous
        s.put("disableStoryFlipping",    FeatureFlags.disableStoryFlipping);
        s.put("disableVideoAutoPlay",    FeatureFlags.disableVideoAutoPlay);
        s.put("disableRepost",           FeatureFlags.disableRepost);
        s.put("showFollowerToast",       FeatureFlags.showFollowerToast);
        s.put("showFeatureToasts",       FeatureFlags.showFeatureToasts);
        s.put("enableStoryMentions",     FeatureFlags.enableStoryMentions);

        // Downloader
        s.put("enablePostDownload",      FeatureFlags.enablePostDownload);
        s.put("enableStoryDownload",     FeatureFlags.enableStoryDownload);
        s.put("enableReelDownload",      FeatureFlags.enableReelDownload);
        s.put("enableProfileDownload",   FeatureFlags.enableProfileDownload);
        s.put("downloaderUsernameFolder",FeatureFlags.downloaderUsernameFolder);
        s.put("downloaderAddTimestamp",  FeatureFlags.downloaderAddTimestamp);

        JSONObject root = new JSONObject();
        root.put("version",  VERSION);
        root.put("settings", s);
        return root.toString(2);
    }

    /**
     * Applies a backup JSON string to the in-memory FeatureFlags.
     * Supports both the versioned {"version":1,"settings":{...}} format
     * and a flat {key:value} format for forward-compatibility.
     * Unknown keys are silently ignored so older backups work on newer builds.
     */
    public static void fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject s = root.has("settings") ? root.getJSONObject("settings") : root;

        if (s.has("isDevEnabled"))            FeatureFlags.isDevEnabled            = s.getBoolean("isDevEnabled");
        if (s.has("removeBuildExpiredPopup")) FeatureFlags.removeBuildExpiredPopup = s.getBoolean("removeBuildExpiredPopup");

        if (s.has("isGhostSeen"))            FeatureFlags.isGhostSeen            = s.getBoolean("isGhostSeen");
        if (s.has("isGhostTyping"))          FeatureFlags.isGhostTyping          = s.getBoolean("isGhostTyping");
        if (s.has("isGhostScreenshot"))      FeatureFlags.isGhostScreenshot      = s.getBoolean("isGhostScreenshot");
        if (s.has("isGhostViewOnce"))        FeatureFlags.isGhostViewOnce        = s.getBoolean("isGhostViewOnce");
        if (s.has("enableUnlimitedReplays")) FeatureFlags.enableUnlimitedReplays = s.getBoolean("enableUnlimitedReplays");
        if (s.has("isGhostStory"))           FeatureFlags.isGhostStory           = s.getBoolean("isGhostStory");
        if (s.has("isGhostLive"))            FeatureFlags.isGhostLive            = s.getBoolean("isGhostLive");
        if (s.has("allowScreenshots"))         FeatureFlags.allowScreenshots         = s.getBoolean("allowScreenshots");
        if (s.has("keepEphemeralMessages"))    FeatureFlags.keepEphemeralMessages    = s.getBoolean("keepEphemeralMessages");
        if (s.has("permanentViewMode"))        FeatureFlags.permanentViewMode        = s.getBoolean("permanentViewMode");

        if (s.has("quickToggleSeen"))        FeatureFlags.quickToggleSeen        = s.getBoolean("quickToggleSeen");
        if (s.has("quickToggleTyping"))      FeatureFlags.quickToggleTyping      = s.getBoolean("quickToggleTyping");
        if (s.has("quickToggleScreenshot"))  FeatureFlags.quickToggleScreenshot  = s.getBoolean("quickToggleScreenshot");
        if (s.has("quickToggleViewOnce"))    FeatureFlags.quickToggleViewOnce    = s.getBoolean("quickToggleViewOnce");
        if (s.has("quickToggleStory"))       FeatureFlags.quickToggleStory       = s.getBoolean("quickToggleStory");
        if (s.has("quickToggleLive"))        FeatureFlags.quickToggleLive        = s.getBoolean("quickToggleLive");
        if (s.has("quickToggleEphemeral"))   FeatureFlags.quickToggleEphemeral   = s.getBoolean("quickToggleEphemeral");
        if (s.has("quickToggleReplays"))     FeatureFlags.quickToggleReplays     = s.getBoolean("quickToggleReplays");
        if (s.has("quickTogglePermanentView")) FeatureFlags.quickTogglePermanentView = s.getBoolean("quickTogglePermanentView");
        if (s.has("quickToggleAllowScreenshots")) FeatureFlags.quickToggleAllowScreenshots = s.getBoolean("quickToggleAllowScreenshots");

        if (s.has("isAdBlockEnabled"))       FeatureFlags.isAdBlockEnabled       = s.getBoolean("isAdBlockEnabled");
        if (s.has("isAnalyticsBlocked"))     FeatureFlags.isAnalyticsBlocked     = s.getBoolean("isAnalyticsBlocked");
        if (s.has("disableTrackingLinks"))   FeatureFlags.disableTrackingLinks   = s.getBoolean("disableTrackingLinks");

        if (s.has("isExtremeMode"))          FeatureFlags.isExtremeMode          = s.getBoolean("isExtremeMode");
        if (s.has("disableStories"))         FeatureFlags.disableStories         = s.getBoolean("disableStories");
        if (s.has("disableFeed"))            FeatureFlags.disableFeed            = s.getBoolean("disableFeed");
        if (s.has("disableReels"))           FeatureFlags.disableReels           = s.getBoolean("disableReels");
        if (s.has("disableReelsExceptDM"))   FeatureFlags.disableReelsExceptDM   = s.getBoolean("disableReelsExceptDM");
        if (s.has("disableExplore"))         FeatureFlags.disableExplore         = s.getBoolean("disableExplore");
        if (s.has("disableComments"))        FeatureFlags.disableComments        = s.getBoolean("disableComments");
        if (s.has("disableDiscoverPeople"))  FeatureFlags.disableDiscoverPeople  = s.getBoolean("disableDiscoverPeople");

        if (s.has("disableStoryFlipping"))   FeatureFlags.disableStoryFlipping   = s.getBoolean("disableStoryFlipping");
        if (s.has("disableVideoAutoPlay"))   FeatureFlags.disableVideoAutoPlay   = s.getBoolean("disableVideoAutoPlay");
        if (s.has("disableRepost"))          FeatureFlags.disableRepost          = s.getBoolean("disableRepost");
        if (s.has("showFollowerToast"))      FeatureFlags.showFollowerToast      = s.getBoolean("showFollowerToast");
        if (s.has("showFeatureToasts"))      FeatureFlags.showFeatureToasts      = s.getBoolean("showFeatureToasts");
        if (s.has("enableStoryMentions"))    FeatureFlags.enableStoryMentions    = s.getBoolean("enableStoryMentions");

        if (s.has("enablePostDownload"))     FeatureFlags.enablePostDownload     = s.getBoolean("enablePostDownload");
        if (s.has("enableStoryDownload"))    FeatureFlags.enableStoryDownload    = s.getBoolean("enableStoryDownload");
        if (s.has("enableReelDownload"))     FeatureFlags.enableReelDownload     = s.getBoolean("enableReelDownload");
        if (s.has("enableProfileDownload"))  FeatureFlags.enableProfileDownload  = s.getBoolean("enableProfileDownload");
        if (s.has("downloaderUsernameFolder")) FeatureFlags.downloaderUsernameFolder = s.getBoolean("downloaderUsernameFolder");
        if (s.has("downloaderAddTimestamp")) FeatureFlags.downloaderAddTimestamp  = s.getBoolean("downloaderAddTimestamp");
    }
}
