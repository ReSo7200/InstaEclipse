package ps.reso.instaeclipse.utils.feature;

public class FeatureFlags {

    // Dev Options
    public static boolean isDevEnabled = false;

    // Ghost Mode
    public static boolean isGhostModeEnabled = false;
    public static boolean isGhostSeen = false;
    public static boolean isGhostTyping = false;
    public static boolean isGhostScreenshot = false;
    public static boolean isGhostViewOnce = false;
    public static boolean enableUnlimitedReplays = false;
    public static boolean isGhostStory = false;
    public static boolean isGhostLive = false;
    public static boolean allowScreenshots = false;
    public static boolean keepEphemeralMessages = false;
    public static boolean permanentViewMode = false;

    // Which ghost mode features the quick toggle will control
    public static boolean quickToggleSeen = false;
    public static boolean quickToggleTyping = false;
    public static boolean quickToggleScreenshot = false;
    public static boolean quickToggleViewOnce = false;
    public static boolean quickToggleStory = false;
    public static boolean quickToggleLive = false;
    public static boolean quickToggleEphemeral = false;
    public static boolean quickToggleReplays = false;
    public static boolean quickTogglePermanentView = false;
    public static boolean quickToggleAllowScreenshots = false;


    // Distraction Free
    public static boolean isExtremeMode = false; // Extreme Mode
    public static boolean isDistractionFree = false;
    public static boolean disableStories = false;
    public static boolean disableFeed = false;
    public static boolean disableReels = false;
    public static boolean disableReelsExceptDM = false;
    public static boolean disableExplore = false;
    public static boolean disableComments = false;

    // Ads and Analytics
    public static boolean isAdBlockEnabled = false;
    public static boolean isAnalyticsBlocked = false;
    public static boolean disableTrackingLinks = false;

    // Misc Options
    public static boolean isMiscEnabled = false;
    public static boolean disableStoryFlipping = false;
    public static boolean disableVideoAutoPlay = false;
    public static boolean showFollowerToast = false;
    public static boolean showFeatureToasts = false;
    public static boolean disableRepost = false;


    public static boolean enableStoryMentions = false;
    public static boolean disableDiscoverPeople = false;
    public static boolean removeBuildExpiredPopup = false;
    public static boolean enableCopyComment = false;

    // Downloader
    public static boolean enablePostDownload = false;
    public static boolean enableStoryDownload = false;
    public static boolean enableReelDownload = false;
    public static boolean enableProfileDownload = false;
    public static boolean downloaderUsernameFolder = false;
    public static boolean downloaderAddTimestamp = false;
    public static String  downloaderCustomPath = "";   // human-readable display path
    public static String  downloaderCustomUri  = "";   // SAF tree URI string for actual writes
}
