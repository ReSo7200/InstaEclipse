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
    public static boolean isGhostStory = false;
    public static boolean isGhostLive = false;
    public static boolean allowScreenshots = false;
    public static boolean keepEphemeralMessages = false;
    public static boolean permanentViewMode = false;
    public static boolean keepUnsentMessages = false;

    // Auto-clear cache
    public static boolean autoClearCache = false;
    public static int autoClearCacheSizeMb = 100;

    // Remove Meta AI (#179)
    public static boolean removeMetaAI = false;

    // Lock DMs (#182) — passcode stored as salted SHA-256 hash (never plaintext)
    public static boolean lockDirectMessages = false;
    public static String lockDirectPasscode = "";
    public static String lockDirectSalt = ""; // per-install random salt; "" = legacy unsalted
    public static boolean lockDirectAlways = false; // re-lock whenever leaving the inbox (not just on app close)
    public static boolean lockWholeApp = false; // lock the ENTIRE app on launch/return (same passcode as Lock DMs)
    public static boolean lockUseFingerprint = true; // offer biometric unlock when the device has one enrolled
    public static boolean hideSpecificChats = false; // hide chosen DM threads from the inbox (per-thread)

    // Which ghost mode features the quick toggle will control
    public static boolean quickToggleSeen = false;
    public static boolean quickToggleTyping = false;
    public static boolean quickToggleScreenshot = false;
    public static boolean quickToggleViewOnce = false;
    public static boolean quickToggleStory = false;
    public static boolean quickToggleLive = false;
    public static boolean quickToggleEphemeral = false;
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
    public static boolean spoofLastSeen = false;
    public static boolean showFollowerToast = false;
    public static boolean showFeatureToasts = false;
    public static boolean disableRepost = false;


    public static boolean enableStoryMentions = false;
    public static boolean disableDiscoverPeople = false;
    public static boolean removeBuildExpiredPopup = false;
    public static boolean enableCopyComment = false;
    public static boolean enableCaptionCopy = false;
    public static boolean disableDoubleTapLike = false;
    public static boolean enablePhotoZoom = false;

    // Location Spoof
    public static boolean spoofLocation = false;
    public static double spoofLat = 0.0;
    public static double spoofLng = 0.0;

    // Video Quality (0 = auto/off, else desired height in px, or Integer.MAX_VALUE for max available)
    public static int forceReelQuality = 0;

    // Custom Theme (themePresetId: 0 = custom palette from themePaletteJson, else a built-in preset id)
    public static boolean customThemeEnabled = false;
    public static int themePresetId = 1;
    public static String themePaletteJson = "";

    // Clean Feed
    public static boolean hideSuggestionsInFeed = false;
    public static boolean hideThreadsSuggestions = false;

    // Downloader
    public static boolean enablePostDownload = false;
    public static boolean enableStoryDownload = false;
    public static boolean enableReelDownload = false;
    public static boolean enableProfileDownload = false;
    public static boolean downloaderUsernameFolder = false;
    public static boolean downloaderAddTimestamp = false;
    public static boolean copyMediaLink = false;      // #117 — inject "Copy Media Link" (direct CDN url) into the post ⋮ menu
    public static boolean saveInstants = false;        // #184 — long-press a received Instant (quicksnap) to save it
    public static boolean uploadInstants = false;      // #199 — send an Instant from gallery (bitmap-swap into quicksnap send)
    public static boolean cacheStories = false;        // cache viewed stories locally for 24h (survive expiry/deletion)
    public static boolean customFontEnabled = false;   // replace IG's UI text font with a user .ttf/.otf
    public static String  customFontPath = "";         // path to the user-picked font in the module's filesDir
    public static boolean customEmojiEnabled = false;  // replace IG's emoji font (needs an EmojiCompat-format .ttf)
    public static String  customEmojiPath = "";        // path to the user-picked EmojiCompat emoji font
    public static String  downloaderCustomPath = "";   // human-readable display path
    public static String  downloaderCustomUri  = "";   // SAF tree URI string for actual writes
}
