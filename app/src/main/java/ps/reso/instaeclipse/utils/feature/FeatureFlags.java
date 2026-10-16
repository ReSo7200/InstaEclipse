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
    public static boolean lockSavedCollections = false; // #219: 🔒 button on saved collections
    public static String lockedCollections = "";         // #219: locked saved collection ids (comma-separated)
    public static boolean lockUseFingerprint = true; // offer biometric unlock when the device has one enrolled
    // Customize navigation bar (#238): hide individual bottom-nav tabs.
    public static boolean hideTabHome = false;
    public static boolean hideTabReels = false;
    public static boolean hideTabCreate = false;
    public static boolean hideTabMessages = false;
    public static boolean hideTabActivity = false;
    public static boolean hideTabProfile = false;
    public static boolean hideNotesTray = false; // hide the Notes tray in the DM inbox (#241)
    public static boolean chatExport = false; // header button: export the open chat to HTML (#225)
    public static boolean jumpToFirstMessage = false; // header button: scroll a DM to its first message (#244)
    public static boolean keepEditHistory = false; // show previous text of edited DMs (#243)
    public static boolean markUnsentInThread = false; // prefix kept unsent messages with a marker in the chat (#222)
    public static boolean offerMediaInfo = false; // "Media info" row in the post menu (#231)
    public static boolean offerPlaybackSpeed = false; // "Playback speed" row for feed videos (#247)
    public static boolean forceReelScrubber = false; // always show IG's reel seek bar (#247)
    public static boolean offerCopyImage = false; // offer "Copy image to clipboard" when downloading a photo (#228)
    public static boolean enableVoiceDownload = false; // Download row for DM voice messages (#223)
    public static boolean offerAudioDownload = false; // ask Video / Audio only when downloading a video (#234)
    public static boolean maxUploadQuality = false; // force IG's "Upload at highest quality" switch always on
    public static boolean expandCaptions = false; // feed captions without "… more" (#246)
    public static boolean hidePlusUpsells = false; // hide Instagram Plus upsell buttons / badges
    public static boolean hideCommunityNotes = false; // hide community notes on posts/reels (#259)
    // Extras (see ExtrasFeatures)
    public static boolean disableSwipeToCamera = false; // sideways swipe on the feed no longer opens the camera
    public static boolean followingOnlyFeed = false; // home feed shows only accounts you follow
    public static boolean hideLikedPosts = false; // drop already-liked posts from the feed at parse time (#249)
    public static boolean hideSpecificChats = false; // hide chosen DM threads from the inbox (per-thread)
    public static boolean hideChatButtonStealth = false; // no eye icon; long-press the chat header to hide (#221)

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
    public static boolean openLinksExternally = false;

    // Misc Options
    public static boolean isMiscEnabled = false;
    public static boolean disableStoryFlipping = false;
    public static boolean disableVideoAutoPlay = false;
    public static boolean spoofLastSeen = false;
    public static boolean showFollowerToast = false;
    public static boolean showFeatureToasts = false;
    public static boolean disableRepost = false;
    public static boolean followListExport = false; // Export CSV pill on followers/following lists (#224)
    public static boolean commentSearch = false; // search the loaded comments of a post (#230)
    public static boolean storyViewerSearch = false; // search box over your story viewers list
    public static boolean savedOnProfile = false;        // #237: Saved button on your own profile
    public static boolean profileFollowsYouLabel = false; // "Follows you" label on profiles (#239)
    public static boolean likeUndo = false; // hold likes for a few seconds with an Undo bar (#235)
    public static boolean exactTimestamps = false; // absolute date/time instead of "3h ago" (#232)
    public static boolean hideShareCreateGroup = false; // hide "Create group" on the DM share sheet (#240)


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
    // Light-mode behaviour (#251): 0 = same theme in light & dark, -1 = stock Instagram in light
    // mode (theme only in dark mode), >0 = this built-in preset in light mode.
    public static int themeLightPresetId = -1; // #251: theme in dark mode only by default (light stays stock)
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
    public static String  navTabOrder = "";             // #238: comma-separated bottom-nav tab id names ("" = Instagram's order)
    public static String  customFontPath = "";         // path to the user-picked font in the module's filesDir
    public static boolean customEmojiEnabled = false;  // replace IG's emoji font (needs an EmojiCompat-format .ttf)
    public static String  customEmojiPath = "";        // path to the user-picked EmojiCompat emoji font
    public static String  downloaderCustomPath = "";   // human-readable display path
    public static String  downloaderCustomUri  = "";   // SAF tree URI string for actual writes
}
