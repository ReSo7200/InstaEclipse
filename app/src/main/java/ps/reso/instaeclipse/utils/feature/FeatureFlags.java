package ps.reso.instaeclipse.utils.feature;

// Written from the settings receiver / menus, read from hook threads: volatile for visibility.
public class FeatureFlags {
    // Upstream selection stays the default; Custom opts into the verified downloader.
    public static volatile boolean showHideChatButton = false;
    public static volatile boolean showDeletedMessagesButton = false;
    public static volatile boolean keepUnsentInChat = false;
    public static volatile boolean downloaderUseOriginal = true;
    public static volatile int downloaderQualityMaxShortSide = 0;
    public static volatile int feedCornerRadius = 0;       // rounded feed post corners in dp (#276), 0 = square
    public static volatile int storyTrayScale = 100;       // story tray size in percent (#283), 100 = Instagram's
    public static volatile String storyRingColor = "";     // unseen story ring: "#RRGGBB" or "#RRGGBB,#RRGGBB", "" = Instagram's (#283)
    public static volatile boolean downloaderCompatibility = false;
    public static volatile boolean downloaderAllowFallback = false;

    // Dev Options
    public static volatile boolean isDevEnabled = false;

    // Ghost Mode
    public static volatile boolean isGhostModeEnabled = false;
    public static volatile boolean isGhostSeen = false;
    public static volatile boolean isGhostTyping = false;
    public static volatile boolean isGhostScreenshot = false;
    public static volatile boolean isGhostViewOnce = false;
    public static volatile boolean isGhostStory = false;
    public static volatile boolean isGhostLive = false;
    public static volatile boolean allowScreenshots = false;
    public static volatile boolean keepEphemeralMessages = false;
    public static volatile boolean permanentViewMode = false;
    public static volatile boolean keepUnsentMessages = false;

    // Auto-clear cache
    public static volatile boolean autoClearCache = false;
    public static volatile int autoClearCacheSizeMb = 100;

    // Remove Meta AI (#179)
    public static volatile boolean removeMetaAI = false;

    // Lock DMs (#182) — passcode stored as salted SHA-256 hash (never plaintext)
    public static volatile boolean lockDirectMessages = false;
    public static volatile String lockDirectPasscode = "";
    public static volatile String lockDirectSalt = ""; // per-install random salt; "" = legacy unsalted
    public static volatile boolean lockDirectAlways = false; // re-lock whenever leaving the inbox (not just on app close)
    public static volatile boolean lockWholeApp = false; // lock the ENTIRE app on launch/return (same passcode as Lock DMs)
    public static volatile boolean lockSavedCollections = false; // #219: 🔒 button on saved collections
    public static volatile String lockedCollections = "";         // #219: locked saved collection ids (comma-separated)
    public static volatile boolean lockUseFingerprint = true; // offer biometric unlock when the device has one enrolled
    // Customize navigation bar (#238): hide individual bottom-nav tabs.
    public static volatile boolean hideTabHome = false;
    public static volatile boolean hideTabReels = false;
    public static volatile boolean hideTabCreate = false;
    public static volatile boolean hideTabMessages = false;
    public static volatile boolean hideTabActivity = false;
    public static volatile boolean hideTabProfile = false;
    public static volatile boolean readReceiptChats = false; // per-chat read receipts, chosen in the chat menu
    public static volatile boolean hideDmBlend = false; // hide the Blend button in DM thread headers
    public static volatile boolean hideThreadsButton = false; // hide the Threads button on profiles
    public static volatile boolean hideNotesTray = false; // hide the Notes tray in the DM inbox (#241)
    public static volatile boolean chatExport = false; // header button: export the open chat to HTML (#225)
    public static volatile boolean jumpToFirstMessage = false; // header button: scroll a DM to its first message (#244)
    public static volatile boolean translateMessages = false; // "Translate" in the DM message long-press menu
    public static volatile boolean bulkUnsend = false; // "Unsend multiple" in the DM message long-press menu (#245)
    public static volatile boolean keepEditHistory = false; // show previous text of edited DMs (#243)
    public static volatile boolean offerMediaInfo = false; // "Media info" row in the post menu (#231)
    public static volatile boolean offerPlaybackSpeed = false; // "Playback speed" row for feed videos (#247)
    public static volatile boolean forceReelScrubber = false; // always show IG's reel seek bar (#247)
    public static volatile boolean holdLeftSlowMotion = false; // hold the left half of a reel: 0.5x instead of IG's 2x
    public static volatile boolean offerCopyImage = false; // offer "Copy image to clipboard" when downloading a photo (#228)
    public static volatile boolean enableVoiceDownload = false; // Download row for DM voice messages (#223)
    public static volatile boolean enableStickerDownload = false; // Download row for DM stickers and GIFs
    public static volatile boolean enableDmMediaDownload = false; // Download row for DM photos/videos (named + foldered like posts)
    public static volatile boolean offerAudioDownload = false; // ask Video / Audio only when downloading a video (#234)
    public static volatile boolean maxUploadQuality = false; // force IG's "Upload at highest quality" switch always on
    public static volatile boolean expandCaptions = false; // feed captions without "… more" (#246)
    public static volatile boolean hidePlusUpsells = false; // hide Instagram Plus upsell buttons / badges
    public static volatile boolean hideCommunityNotes = false; // hide community notes on posts/reels (#259)
    // Extras (see ExtrasFeatures)
    public static volatile boolean hideStoriesTray = false; // hide the story tray only; stories still open from profiles
    public static volatile boolean fixNotificationRegistration = false; // add FLAG_IMMUTABLE to IG's legacy push registration intent
    public static volatile boolean hideOnboardingPrompts = false; // skip contact/location onboarding pages
    public static volatile boolean highResolutionImages = false; // pick the largest image candidate
    public static volatile boolean airplaneMode = false; // keep Instagram's realtime connection down (appear offline)
    public static volatile boolean unlimitedAccounts = false; // lift the 5-account limit
    public static volatile boolean reelsLockScroll = false; // lock swiping between reels
    public static volatile boolean reelsAutoScroll = false; // unlock Instagram's own Reels auto-scroll option
    public static volatile boolean disableSwipeToCamera = false; // sideways swipe on the feed no longer opens the camera
    public static volatile boolean followingOnlyFeed = false; // home feed shows only accounts you follow
    public static volatile boolean confirmFeedRefresh = false; // ask before pull-to-refresh reloads the home feed (#278)
    public static volatile boolean hideLikedPosts = false; // drop already-liked posts from the feed at parse time (#249)
    public static volatile boolean hideSpecificChats = false; // hide chosen DM threads from the inbox (per-thread)

    // Which ghost mode features the quick toggle will control
    public static volatile boolean quickToggleSeen = false;
    public static volatile boolean quickToggleTyping = false;
    public static volatile boolean quickToggleScreenshot = false;
    public static volatile boolean quickToggleViewOnce = false;
    public static volatile boolean quickToggleStory = false;
    public static volatile boolean quickToggleLive = false;
    public static volatile boolean quickToggleEphemeral = false;
    public static volatile boolean quickTogglePermanentView = false;
    public static volatile boolean quickToggleAllowScreenshots = false;


    // Distraction Free
    public static volatile boolean isExtremeMode = false; // Extreme Mode
    public static volatile boolean isDistractionFree = false;
    public static volatile boolean disableStories = false;
    public static volatile boolean disableFeed = false;
    public static volatile boolean disableReels = false;
    public static volatile boolean disableReelsExceptDM = false;
    public static volatile boolean disableExplore = false;
    public static volatile boolean disableComments = false;

    // Ads and Analytics
    public static volatile boolean isAdBlockEnabled = false;
    public static volatile boolean isAnalyticsBlocked = false;
    public static volatile boolean disableTrackingLinks = false;
    public static volatile boolean openLinksExternally = false;

    // Misc Options
    public static volatile boolean isMiscEnabled = false;
    public static volatile boolean disableStoryFlipping = false;
    public static volatile boolean disableVideoAutoPlay = false;
    public static volatile boolean spoofLastSeen = false;
    public static volatile boolean disableSearchHistory = false; // keep searches out of Recent (#280)
    public static volatile boolean confirmCalls = false;       // ask before a chat call starts (#277)
    public static volatile boolean showFollowerToast = false;
    public static volatile boolean showFeatureToasts = false;
    public static volatile boolean disableRepost = false;
    public static volatile boolean followListExport = false; // Export CSV pill on followers/following lists (#224)
    public static volatile boolean followersHistory = false; // who unfollowed / followed you, checked on your Followers tab
    public static volatile boolean commentSearch = false; // search the loaded comments of a post (#230)
    public static volatile boolean storyInfo = false; // Story Info / Quiz Answer / Poll Results in the story menu
    public static volatile boolean storyLinks = false; // copy link-sticker urls from the story menu
    public static volatile boolean storyViewerSearch = false; // search box over your story viewers list
    public static volatile boolean savedOnProfile = false;        // #237: Saved button on your own profile
    public static volatile boolean profileFollowsYouLabel = false; // "Follows you" label on profiles (#239)
    public static volatile boolean fancyBioFont = false; // Edit profile: fancy (Unicode) fonts for the bio
    public static volatile boolean likeUndo = false; // hold likes for a few seconds with an Undo bar (#235)
    public static volatile boolean exactTimestamps = false; // absolute date/time instead of "3h ago" (#232)
    public static volatile String  exactTimestampFormat = ""; // its SimpleDateFormat pattern, "" = device default (#265)
    public static volatile String  spoofDevice = "";          // device Instagram sees: "" = real, "pixel", "s26" (DeviceSpoofHook)
    public static volatile boolean hideShareCreateGroup = false; // hide "Create group" on the DM share sheet (#240)


    public static volatile boolean enableStoryMentions = false;
    public static volatile boolean disableDiscoverPeople = false;
    public static volatile boolean removeBuildExpiredPopup = false;
    public static volatile boolean enableCopyComment = false;
    public static volatile boolean enableCaptionCopy = false;
    public static volatile boolean disableDoubleTapLike = false;
    public static volatile boolean enablePhotoZoom = false;

    // Location Spoof
    public static volatile boolean spoofLocation = false;
    public static volatile double spoofLat = 0.0;
    public static volatile double spoofLng = 0.0;
    public static volatile String spoofLabel = "";   // place name for spoofLat/spoofLng
    public static volatile String spoofRecent = "";  // JSON history, see LocationPresets

    // Video Quality (0 = auto/off, else desired height in px, or Integer.MAX_VALUE for max available)
    public static volatile int forceReelQuality = 0;

    // Custom Theme (themePresetId: 0 = custom palette from themePaletteJson, else a built-in preset id)
    public static volatile boolean customThemeEnabled = false;
    public static volatile int themePresetId = 1;
    // Light-mode behaviour (#251): 0 = same theme in light & dark, -1 = stock Instagram in light
    // mode (theme only in dark mode), >0 = this built-in preset in light mode.
    public static volatile int themeLightPresetId = -1; // #251: theme in dark mode only by default (light stays stock)
    public static volatile String themePaletteJson = "";

    // Clean Feed
    public static volatile boolean hideSuggestionsInFeed = false;
    public static volatile boolean hideThreadsSuggestions = false;

    // Downloader
    public static volatile boolean enablePostDownload = false;
    public static volatile boolean enableStoryDownload = false;
    public static volatile boolean enableReelDownload = false;
    public static volatile boolean enableProfileDownload = false;
    public static volatile boolean downloaderUsernameFolder = false;
    public static volatile boolean downloaderAddTimestamp = false;
    public static volatile boolean copyMediaLink = false;      // #117 — inject "Copy Media Link" (direct CDN url) into the post ⋮ menu
    public static volatile boolean saveInstants = false;        // #184 — long-press a received Instant (quicksnap) to save it
    public static volatile boolean uploadInstants = false;      // #199 — send an Instant from gallery (bitmap-swap into quicksnap send)
    public static volatile boolean cacheStories = false;        // cache viewed stories locally for 24h (survive expiry/deletion)
    public static volatile boolean customFontEnabled = false;   // replace IG's UI text font with a user .ttf/.otf
    public static volatile String  customShareDomain = "";       // replaces instagram.com in copied/shared links ("" = off)
    public static volatile String  startupTab = "";              // tab enum name Instagram opens on ("" = its default)
    public static volatile String  navTabOrder = "";             // #238: comma-separated bottom-nav tab id names ("" = Instagram's order)
    public static volatile String  customFontPath = "";         // path to the user-picked font in the module's filesDir
    public static volatile boolean customEmojiEnabled = false;  // replace IG's emoji font (needs an EmojiCompat-format .ttf)
    public static volatile String  customEmojiPath = "";        // path to the user-picked EmojiCompat emoji font
    public static volatile String  downloaderCustomPath = "";   // human-readable display path
    public static volatile String  downloaderCustomUri  = "";   // SAF tree URI string for actual writes
}
