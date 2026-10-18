package ps.reso.instaeclipse.core

import ps.reso.instaeclipse.R

object HookRegistry {

    private val hooks = listOf(
        Hook(
            id = "enablePostDownload",
            name = "Download Posts",
            description = "Save single photos and full carousels",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = true
        ),
        Hook(
            id = "enableStoryDownload",
            name = "Download Stories",
            description = "Grab stories before they expire, including your own with the music kept intact",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = true
        ),
        Hook(
            id = "enableReelDownload",
            name = "Download Reels",
            description = "Save reels straight to your gallery",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = true
        ),
        Hook(
            id = "enableProfileDownload",
            name = "Download Profile Pictures",
            description = "Long press a profile to save the full-size picture",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = true
        ),
        Hook(
            id = "enableVoiceDownload",
            name = "Download Voice Messages",
            description = "Long-press a DM voice message to download it directly",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),
        Hook(
            id = "offerAudioDownload",
            name = "Separate Audio Download Option",
            description = "Ask whether to save Video or Audio only when downloading a video or reel",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),
        Hook(
            id = "offerCopyImage",
            name = "Copy Image to Clipboard",
            description = "Offer 'Copy image to clipboard' when downloading a photo",
            category = HookCategory.POST_MENU,
            defaultEnabled = false
        ),
        Hook(
            id = "offerMediaInfo",
            name = "Show Media Info",
            description = "Add Media Info row in post overflow menu with resolution, codec, bitrate, and music links",
            category = HookCategory.POST_MENU,
            defaultEnabled = false
        ),
        Hook(
            id = "offerPlaybackSpeed",
            name = "Video Playback Speed",
            description = "Control video playback speed (0.5x – 2.0x) for feed videos and reels",
            category = HookCategory.POST_MENU,
            defaultEnabled = false
        ),
        Hook(
            id = "forceReelScrubber",
            name = "Always Show Reel Scrubber",
            description = "Keep Instagram's video progress scrubber visible on all reels",
            category = HookCategory.REELS,
            defaultEnabled = false
        ),
        Hook(
            id = "holdLeftSlowMotion",
            name = "Hold Left Side for Slow Motion",
            description = "Hold the left half of a reel to play at 0.5× instead of 2×, right half for 2×",
            category = HookCategory.REELS,
            defaultEnabled = false
        ),
        Hook(
            id = "reelsLockScroll",
            name = "Lock Reels Scrolling",
            description = "Prevent accidental vertical swipes between reels",
            category = HookCategory.REELS,
            defaultEnabled = false
        ),
        Hook(
            id = "reelsAutoScroll",
            name = "Auto-Scroll Reels",
            description = "Automatically advance to the next reel when the current one finishes",
            category = HookCategory.REELS,
            defaultEnabled = false
        ),
        Hook(
            id = "maxUploadQuality",
            name = "Force Maximum Upload Quality",
            description = "Force Instagram's 'Upload at highest quality' toggle always on",
            category = HookCategory.POST_MENU,
            defaultEnabled = false
        ),
        Hook(
            id = "downloaderUsernameFolder",
            name = "Save in Username Subfolder",
            description = "Sort saved media into a folder per account automatically",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),
        Hook(
            id = "downloaderAddTimestamp",
            name = "Add Timestamp to Filename",
            description = "Add the download date and time to each saved file",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),
        Hook(
            id = "enableDmMediaDownload",
            name = "Download DM Photos & Videos (long-press)",
            description = "Save received direct photos and videos to your storage with full quality",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),
        Hook(
            id = "enableStickerDownload",
            name = "Download Stickers and GIFs",
            description = "Long-press a sticker or GIF in a DM to download it",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false,
            nameRes = R.string.ig_dialog_downloader_stickers
        ),
        Hook(
            id = "downloaderUseOriginal",
            name = "Use Original Download",
            description = "Save the file Instagram selected as is; off picks the quality yourself",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = true,
            nameRes = R.string.ie_downloader_use_original
        ),
        Hook(
            id = "downloaderCompatibility",
            name = "Compatibility Mode",
            description = "Prefer H.264 video that plays everywhere",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false,
            nameRes = R.string.ie_download_compatibility
        ),
        Hook(
            id = "downloaderAllowFallback",
            name = "Allow Lower-Quality Fallback",
            description = "Save a lower quality when the best one fails",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false,
            nameRes = R.string.ie_download_fallback
        ),
        Hook(
            id = "copyMediaLink",
            name = "Copy Media Link",
            description = "Copy the direct CDN link for a post, reel, or a specific carousel slide",
            category = HookCategory.POST_MENU,
            defaultEnabled = false
        ),
        Hook(
            id = "saveInstants",
            name = "Save Instants (long-press to save)",
            description = "Long press to save an Instant you are viewing",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),
        Hook(
            id = "uploadInstants",
            name = "Upload Instant from Gallery",
            description = "Pick an image from your gallery and send it as an Instant",
            category = HookCategory.POST_MENU,
            defaultEnabled = false
        ),
        Hook(
            id = "cacheStories",
            name = "Cache Stories for 24h",
            description = "Quietly keep viewed stories for a day so you can reopen or save ones that were deleted or expired",
            category = HookCategory.DOWNLOADER,
            defaultEnabled = false
        ),

        Hook(
            id = "isGhostSeen",
            name = "Hide DM Seen",
            description = "Read messages without sending a read receipt",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "isGhostTyping",
            name = "Hide Typing Indicator",
            description = "Type freely, the other person never sees the dots",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "isGhostStory",
            name = "Hide Story Views",
            description = "Watch stories without showing up in the viewer list",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "isGhostLive",
            name = "Hide Live Presence",
            description = "Join lives without being counted as a viewer",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "allowScreenshots",
            name = "Allow Screenshots in DMs",
            description = "Re-enable screenshots where Instagram blocks them",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "isGhostScreenshot",
            name = "Bypass Screenshot Detection",
            description = "Screenshot disappearing DM media without tipping anyone off",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "isGhostViewOnce",
            name = "Hide View Once Opened",
            description = "Open view-once media without marking it as seen",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "permanentViewMode",
            name = "Unlock View-Once/Twice",
            description = "Keep view-once and view-twice media around instead of it vanishing",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "keepEphemeralMessages",
            name = "Keep Disappearing Messages",
            description = "Stop ephemeral messages from deleting themselves",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "keepUnsentMessages",
            name = "Keep Unsent Messages",
            description = "When someone unsends a message, keep a private copy grouped per person",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "keepUnsentInChat",
            name = "Keep Unsent Messages in Chat",
            description = "Keep received unsent messages visible in the chat",
            category = HookCategory.MESSAGES,
            defaultEnabled = false,
            nameRes = R.string.ie_keep_unsent_in_chat
        ),
        Hook(
            id = "showDeletedMessagesButton",
            name = "Deleted Messages Button",
            description = "Show a button for the deleted messages log",
            category = HookCategory.MESSAGES,
            defaultEnabled = false,
            nameRes = R.string.ie_show_deleted_button
        ),
        Hook(
            id = "bulkUnsend",
            name = "Unsend Multiple Messages",
            description = "Long-press one of your messages to select and bulk-unsend in chat",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "keepEditHistory",
            name = "Keep DM Edit History",
            description = "Show original text and revision history for edited direct messages",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "jumpToFirstMessage",
            name = "Jump to First Message",
            description = "Add in-chat header button to scroll to the very start of the conversation",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "translateMessages",
            name = "Translate Messages",
            description = "Add in-line translate button in the message long-press menu",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "chatExport",
            name = "Export Chat to HTML",
            description = "Export conversation with all media and voice notes to a self-contained HTML file",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "readReceiptChats",
            name = "Per-Chat Read Receipts",
            description = "Selectively control read receipts per individual chat thread",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "hideNotesTray",
            name = "Hide DM Notes Tray",
            description = "Remove the Notes tray from the top of the direct messages inbox",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "hideDmBlend",
            name = "Hide Reels Blend Button",
            description = "Remove the Blend icon from direct message headers",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),
        Hook(
            id = "hideShareCreateGroup",
            name = "Hide 'Create Group' in Share Sheet",
            description = "Hide the 'Create group' row when sharing posts or reels to DMs",
            category = HookCategory.MESSAGES,
            defaultEnabled = false
        ),

        Hook(
            id = "lockDirectMessages",
            name = "Lock DMs",
            description = "Passcode gate just the inbox, leave the rest of the app open",
            category = HookCategory.LOCK,
            defaultEnabled = false
        ),
        Hook(
            id = "lockDirectAlways",
            name = "Re-Lock When Leaving",
            description = "Require passcode every time you leave the locked section, not just on app close",
            category = HookCategory.LOCK,
            defaultEnabled = false
        ),
        Hook(
            id = "lockWholeApp",
            name = "Lock Whole App",
            description = "Require a passcode (or your fingerprint) to open Instagram at all",
            category = HookCategory.LOCK,
            defaultEnabled = false
        ),
        Hook(
            id = "lockSavedCollections",
            name = "Lock Saved Collections",
            description = "Add passcode protection to individual saved collections",
            category = HookCategory.LOCK,
            defaultEnabled = false
        ),
        Hook(
            id = "lockUseFingerprint",
            name = "Use Fingerprint to Unlock",
            description = "Use the device biometric prompt instead of typing the code",
            category = HookCategory.LOCK,
            defaultEnabled = true
        ),

        Hook(
            id = "hideSpecificChats",
            name = "Hide Specific Chats",
            description = "Pick chats that should quietly disappear from your inbox, manage them later from the menu",
            category = HookCategory.HIDE_CHATS,
            defaultEnabled = false
        ),
        Hook(
            id = "showHideChatButton",
            name = "Hide Chat Button",
            description = "Show the button that hides a chat",
            category = HookCategory.HIDE_CHATS,
            defaultEnabled = false,
            nameRes = R.string.ie_show_hide_button
        ),

        Hook(
            id = "hideSuggestionsInFeed",
            name = "Hide Suggestions in Feed",
            description = "Strip out suggested posts, suggested reels, and other non-followed clutter",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "hideThreadsSuggestions",
            name = "Hide Threads Suggestions",
            description = "Drop the Threads cross-promo units on their own",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "followingOnlyFeed",
            name = "Following-only Feed",
            description = "Home feed shows only posts and reels from accounts you follow",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "hideLikedPosts",
            name = "Hide Posts You Already Liked",
            description = "Filter out already-liked posts from your home timeline",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "hideCommunityNotes",
            name = "Hide Community Notes",
            description = "Hide community notes banners on feed posts and reels",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "hidePlusUpsells",
            name = "Hide Instagram Plus Upsells",
            description = "Hide Instagram Plus upsell buttons, banners, and badges",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "expandCaptions",
            name = "Always Show Full Captions",
            description = "Display complete post captions without the '… more' truncation",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "exactTimestamps",
            name = "Exact Date & Timestamps",
            description = "Show absolute dates and times instead of relative timestamps like '3h ago'",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "highResolutionImages",
            name = "Load Highest Resolution Images",
            description = "Always request and display the highest resolution image candidate",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "hideStoriesTray",
            name = "Hide Story Tray",
            description = "Hide the top stories tray on the feed (stories remain accessible from profiles)",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "hideTabHome",
            name = "Hide Home Tab",
            description = "Hide the Home button from the bottom navigation bar",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),
        Hook(
            id = "hideTabReels",
            name = "Hide Reels Tab",
            description = "Hide the Reels button from the bottom navigation bar",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),
        Hook(
            id = "hideTabCreate",
            name = "Hide Create Tab",
            description = "Hide the Create (+) button from the bottom navigation bar",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),
        Hook(
            id = "hideTabMessages",
            name = "Hide Messages Tab",
            description = "Hide the Messages button from the bottom navigation bar",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),
        Hook(
            id = "hideTabActivity",
            name = "Hide Activity Tab",
            description = "Hide the Notifications/Activity button from the bottom navigation bar",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),
        Hook(
            id = "hideTabProfile",
            name = "Hide Profile Tab",
            description = "Hide the Profile button from the bottom navigation bar",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),

        Hook(
            id = "isExtremeMode",
            name = "Extreme Mode (Irreversible until reinstall)",
            description = "Strip distractions hard until you reinstall",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            isExtreme = true,
            requiresAnyOf = listOf(
                "disableStories", "disableFeed", "disableReels",
                "disableReelsExceptDM", "disableExplore", "disableComments"
            )
        ),
        Hook(
            id = "disableStories",
            name = "Disable Stories",
            description = "Turn off Stories in Instagram",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            disabledWhenTrue = "isExtremeMode"
        ),
        Hook(
            id = "disableFeed",
            name = "Disable Feed",
            description = "Turn off the home timeline feed",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            disabledWhenTrue = "isExtremeMode"
        ),
        Hook(
            id = "disableReels",
            name = "Disable Reels",
            description = "Turn off Reels in Instagram",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            disabledWhenTrue = "isExtremeMode",
            cascadeOffKey = "disableReelsExceptDM"
        ),
        Hook(
            id = "disableReelsExceptDM",
            name = "Disable Reels Except in DMs",
            description = "Allow viewing reels links received directly in private messages",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            disabledWhenTrue = "isExtremeMode",
            dependsOn = "disableReels"
        ),
        Hook(
            id = "disableExplore",
            name = "Disable Explore",
            description = "Turn off the Explore page discovery grid",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            disabledWhenTrue = "isExtremeMode"
        ),
        Hook(
            id = "disableComments",
            name = "Disable Comments",
            description = "Turn off comments across all posts and reels",
            category = HookCategory.DISTRACTION_FREE,
            defaultEnabled = false,
            disabledWhenTrue = "isExtremeMode"
        ),

        Hook(
            id = "forceReelQuality",
            name = "Force Reels Quality",
            description = "Pin reels to a fixed quality instead of the adaptive bitrate",
            category = HookCategory.REELS,
            defaultEnabled = true
        ),

        Hook(
            id = "customThemeEnabled",
            name = "Enable Custom Theme",
            description = "Recolor the app with built-in presets or a full custom color palette",
            category = HookCategory.THEME,
            defaultEnabled = false
        ),
        Hook(
            id = "customFontEnabled",
            name = "Custom Font",
            description = "Load your own .ttf or .otf and use it across the app, chats and captions included",
            category = HookCategory.THEME,
            defaultEnabled = false
        ),
        Hook(
            id = "customEmojiEnabled",
            name = "Custom Emoji",
            description = "Swap in your own color emoji font",
            category = HookCategory.THEME,
            defaultEnabled = false
        ),

        Hook(
            id = "isAdBlockEnabled",
            name = "Block Ads",
            description = "Drop sponsored posts and ad units",
            category = HookCategory.ADS,
            defaultEnabled = true
        ),
        Hook(
            id = "isAnalyticsBlocked",
            name = "Block Analytics",
            description = "Cut Instagram's analytics and telemetry calls",
            category = HookCategory.ADS,
            defaultEnabled = true
        ),
        Hook(
            id = "disableTrackingLinks",
            name = "Disable Tracking Links",
            description = "Strip tracking parameters from links you share and copy, referral tokens included",
            category = HookCategory.ADS,
            defaultEnabled = true
        ),
        Hook(
            id = "openLinksExternally",
            name = "Open Links Externally",
            description = "Open web links directly in your default browser rather than in-app browser",
            category = HookCategory.ADS,
            defaultEnabled = false
        ),

        Hook(
            id = "spoofLocation",
            name = "Spoof GPS Location",
            description = "Report a location of your choosing to Instagram",
            category = HookCategory.LOCATION,
            defaultEnabled = false
        ),

        Hook(
            id = "profileFollowsYouLabel",
            name = "Show 'Follows You' on Profiles",
            description = "Display an indicator badge showing whether an account follows you back",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "savedOnProfile",
            name = "Saved Button on Profile",
            description = "Add a quick shortcut button to saved collections on your own profile",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "followListExport",
            name = "Export Followers/Following to CSV",
            description = "Export followers or following lists to a CSV file",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "followersHistory",
            name = "Followers History",
            description = "Track who unfollowed or followed you, checked on your Followers tab",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "fancyBioFont",
            name = "Fancy Fonts in Bio",
            description = "Pick a font style to apply to text typed in your bio (Unicode script, Fraktur, bold, underline, strikethrough)",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "commentSearch",
            name = "Search Post Comments",
            description = "Add search box in the comments sheet to filter comments and reply trees",
            category = HookCategory.INTERACTIONS,
            defaultEnabled = false
        ),
        Hook(
            id = "storyViewerSearch",
            name = "Search Story Viewers",
            description = "Add a search bar over your story viewers list",
            category = HookCategory.STORIES,
            defaultEnabled = false
        ),
        Hook(
            id = "storyInfo",
            name = "Story Info & Answers",
            description = "View quiz answers, poll results, and sticker metadata in the story menu",
            category = HookCategory.STORIES,
            defaultEnabled = false
        ),
        Hook(
            id = "storyLinks",
            name = "Copy Story Link Stickers",
            description = "Easily copy link-sticker URLs directly from the story menu",
            category = HookCategory.STORIES,
            defaultEnabled = false
        ),
        Hook(
            id = "likeUndo",
            name = "Undo Window for Likes",
            description = "Hold likes for a few seconds with an Undo bar to prevent accidental likes",
            category = HookCategory.INTERACTIONS,
            defaultEnabled = false
        ),
        Hook(
            id = "hideThreadsButton",
            name = "Hide Threads Button on Profile",
            description = "Hide the Threads profile badge button",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "disableSwipeToCamera",
            name = "Disable Swipe to Camera",
            description = "Stop sideways swipes on the feed from opening the camera",
            category = HookCategory.NAVIGATION,
            defaultEnabled = false
        ),
        Hook(
            id = "airplaneMode",
            name = "Airplane Mode (Appear Offline)",
            description = "Keep Instagram's realtime WebSocket disconnected so you appear offline",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "unlimitedAccounts",
            name = "Unlimited Accounts",
            description = "Lift Instagram's 5-account limit",
            category = HookCategory.APP,
            defaultEnabled = false
        ),
        Hook(
            id = "fixNotificationRegistration",
            name = "Fix Notification Registration",
            description = "Add FLAG_IMMUTABLE to push registration intents on Android 12+",
            category = HookCategory.APP,
            defaultEnabled = false
        ),
        Hook(
            id = "hideOnboardingPrompts",
            name = "Hide Onboarding Prompts",
            description = "Skip contact sync and location onboarding dialogs",
            category = HookCategory.ADS,
            defaultEnabled = false
        ),
        Hook(
            id = "disableStoryFlipping",
            name = "Disable Story Auto-Swipe",
            description = "Stop stories from advancing on their own",
            category = HookCategory.STORIES,
            defaultEnabled = false
        ),
        Hook(
            id = "disableVideoAutoPlay",
            name = "Disable Video Autoplay",
            description = "Videos wait until you tap them",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "spoofLastSeen",
            name = "Spoof Last Seen (freeze)",
            description = "Freeze your \"active\" status instead of updating it live",
            category = HookCategory.GHOST,
            defaultEnabled = false
        ),
        Hook(
            id = "disableRepost",
            name = "Disable Repost",
            description = "Keep the repost button from actually reposting",
            category = HookCategory.REELS,
            defaultEnabled = false
        ),
        Hook(
            id = "showFollowerToast",
            name = "Show Follower Toast",
            description = "Get a heads-up on whether someone follows you back when you open their profile",
            category = HookCategory.PROFILES,
            defaultEnabled = false
        ),
        Hook(
            id = "showFeatureToasts",
            name = "Show Feature Toasts",
            description = "Shows an IGDS toast notification of loaded features",
            category = HookCategory.APP,
            defaultEnabled = false
        ),
        Hook(
            id = "enableStoryMentions",
            name = "View Story Mentions",
            description = "See every @mention in a story at once",
            category = HookCategory.STORIES,
            defaultEnabled = true
        ),
        Hook(
            id = "disableDiscoverPeople",
            name = "Disable Discover People",
            description = "Remove the \"people you may know\" row",
            category = HookCategory.FEED,
            defaultEnabled = false
        ),
        Hook(
            id = "enableCopyComment",
            name = "Copy Comment",
            description = "Copy any comment with one tap",
            category = HookCategory.INTERACTIONS,
            defaultEnabled = true
        ),
        Hook(
            id = "disableDoubleTapLike",
            name = "Disable Double Tap to Like",
            description = "Stop accidental likes from a stray double tap",
            category = HookCategory.INTERACTIONS,
            defaultEnabled = false
        ),
        Hook(
            id = "enableCaptionCopy",
            name = "Copy Caption",
            description = "Copy a post or reel caption from the overflow menu",
            category = HookCategory.INTERACTIONS,
            defaultEnabled = true
        ),
        Hook(
            id = "enablePhotoZoom",
            name = "Photo Zoom (Long-Press)",
            description = "Long press a feed photo to open it full screen with pinch to zoom",
            category = HookCategory.FEED,
            defaultEnabled = true
        ),
        Hook(
            id = "removeMetaAI",
            name = "Remove Meta AI",
            description = "Take the Meta AI entry points out of search, the composer, and the reel more-options button",
            category = HookCategory.ADS,
            defaultEnabled = false
        ),

        Hook(
            id = "isDevEnabled",
            name = "Enable Developer Mode",
            description = "Open the full internal developer/QE panel",
            category = HookCategory.DEV_OPTIONS,
            defaultEnabled = true
        ),
        Hook(
            id = "removeBuildExpiredPopup",
            name = "Remove Build Expired Popup",
            description = "Dismiss the \"build expired\" nag on older builds",
            category = HookCategory.DEV_OPTIONS,
            defaultEnabled = true
        ),
        Hook(
            id = "autoClearCache",
            name = "Auto-Clear Cache",
            description = "Clears Instagram's media cache when you leave the app, if it's over the chosen size",
            category = HookCategory.APP,
            defaultEnabled = false
        )
    )

    private val hooksById: Map<String, Hook> by lazy { hooks.associateBy { it.id } }

    fun getAllHooks(): List<Hook> = hooks

    fun getHook(id: String): Hook? = hooksById[id]

}
