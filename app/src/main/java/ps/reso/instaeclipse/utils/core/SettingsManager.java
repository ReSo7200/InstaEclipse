package ps.reso.instaeclipse.utils.core;

import ps.reso.instaeclipse.utils.feature.ExtrasFeatures;
import android.content.Context;
import android.content.SharedPreferences;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;

public class SettingsManager {
    private static final String PREF_NAME = "instaeclipse_prefs";
    private static SharedPreferences prefs;
    private static Boolean appliedHideState;
    private static boolean hostProcess;
    /** Application context, for pushing in-app changes to the companion (CompanionSync). */
    private static Context appContext;

    public static void init(Context context) {
        hostProcess = context != null && CommonUtils.SUPPORTED_PACKAGES.contains(context.getPackageName());
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    public static void saveAllFlags() {
        // Snapshot to stamp the keys this save changes (see CompanionSync).
        java.util.Map<String, ?> before = new java.util.HashMap<>(prefs.getAll());
        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean("isDevEnabled", FeatureFlags.isDevEnabled);

        // Ghost Mode
        editor.putBoolean("isGhostModeEnabled", FeatureFlags.isGhostModeEnabled);
        editor.putBoolean("isGhostSeen", FeatureFlags.isGhostSeen);
        editor.putBoolean("isGhostTyping", FeatureFlags.isGhostTyping);
        editor.putBoolean("isGhostScreenshot", FeatureFlags.isGhostScreenshot);
        editor.putBoolean("isGhostViewOnce", FeatureFlags.isGhostViewOnce);
        editor.putBoolean("isGhostStory", FeatureFlags.isGhostStory);
        editor.putBoolean("isGhostLive", FeatureFlags.isGhostLive);
        editor.putBoolean("allowScreenshots", FeatureFlags.allowScreenshots);
        editor.putBoolean("keepEphemeralMessages", FeatureFlags.keepEphemeralMessages);
        editor.putBoolean("permanentViewMode", FeatureFlags.permanentViewMode);
        editor.putBoolean("keepUnsentMessages", FeatureFlags.keepUnsentMessages);
        editor.putBoolean("autoClearCache", FeatureFlags.autoClearCache);
        editor.putInt("autoClearCacheSizeMb", FeatureFlags.autoClearCacheSizeMb);
        editor.putBoolean("removeMetaAI", FeatureFlags.removeMetaAI);
        editor.putBoolean("lockDirectMessages", FeatureFlags.lockDirectMessages);
        editor.putString("lockDirectPasscode", FeatureFlags.lockDirectPasscode);
        editor.putString("lockDirectSalt", FeatureFlags.lockDirectSalt);
        editor.putBoolean("lockDirectAlways", FeatureFlags.lockDirectAlways);
        editor.putBoolean("lockWholeApp", FeatureFlags.lockWholeApp);
        editor.putBoolean("hideSpecificChats", FeatureFlags.hideSpecificChats);
        editor.putBoolean("showHideChatButton", FeatureFlags.showHideChatButton);
        editor.putBoolean("showDeletedMessagesButton", FeatureFlags.showDeletedMessagesButton);
        editor.putBoolean("keepUnsentInChat", FeatureFlags.keepUnsentInChat);
        editor.putBoolean("downloaderUseOriginal", FeatureFlags.downloaderUseOriginal);
        editor.putInt("downloaderQualityMaxShortSide", FeatureFlags.downloaderQualityMaxShortSide);
        editor.putInt("storyTrayScale", FeatureFlags.storyTrayScale);
        editor.putInt("feedCornerRadius", FeatureFlags.feedCornerRadius);
        editor.putString("storyRingColor", FeatureFlags.storyRingColor);
        editor.putBoolean("downloaderCompatibility", FeatureFlags.downloaderCompatibility);
        editor.putBoolean("downloaderAllowFallback", FeatureFlags.downloaderAllowFallback);
        editor.putBoolean("lockUseFingerprint", FeatureFlags.lockUseFingerprint);

        // Quick Toggles
        editor.putBoolean("quickToggleSeen", FeatureFlags.quickToggleSeen);
        editor.putBoolean("quickToggleTyping", FeatureFlags.quickToggleTyping);
        editor.putBoolean("quickToggleScreenshot", FeatureFlags.quickToggleScreenshot);
        editor.putBoolean("quickToggleViewOnce", FeatureFlags.quickToggleViewOnce);
        editor.putBoolean("quickToggleStory", FeatureFlags.quickToggleStory);
        editor.putBoolean("quickToggleLive", FeatureFlags.quickToggleLive);
        editor.putBoolean("quickToggleEphemeral", FeatureFlags.quickToggleEphemeral);
        editor.putBoolean("quickTogglePermanentView", FeatureFlags.quickTogglePermanentView);
        editor.putBoolean("quickToggleAllowScreenshots", FeatureFlags.quickToggleAllowScreenshots);

        // Distraction Free
        editor.putBoolean("isExtremeMode", FeatureFlags.isExtremeMode);
        editor.putBoolean("isDistractionFree", FeatureFlags.isDistractionFree);
        editor.putBoolean("disableStories", FeatureFlags.disableStories);
        editor.putBoolean("disableFeed", FeatureFlags.disableFeed);
        editor.putBoolean("disableReels", FeatureFlags.disableReels);
        editor.putBoolean("disableReelsExceptDM", FeatureFlags.disableReelsExceptDM);
        editor.putBoolean("disableExplore", FeatureFlags.disableExplore);
        editor.putBoolean("disableComments", FeatureFlags.disableComments);

        // Clean Feed
        editor.putBoolean("hideSuggestionsInFeed", FeatureFlags.hideSuggestionsInFeed);
        editor.putBoolean("hideThreadsSuggestions", FeatureFlags.hideThreadsSuggestions);
        editor.putBoolean("hideLikedPosts", FeatureFlags.hideLikedPosts);
        editor.putBoolean("confirmFeedRefresh", FeatureFlags.confirmFeedRefresh);
        editor.putBoolean("hideCommunityNotes", FeatureFlags.hideCommunityNotes);
        editor.putBoolean("followingOnlyFeed", FeatureFlags.followingOnlyFeed);
        editor.putBoolean("hidePlusUpsells", FeatureFlags.hidePlusUpsells);
        editor.putBoolean("expandCaptions", FeatureFlags.expandCaptions);
        editor.putBoolean("offerAudioDownload", FeatureFlags.offerAudioDownload);
        editor.putBoolean("enableVoiceDownload", FeatureFlags.enableVoiceDownload);
        editor.putBoolean("enableStickerDownload", FeatureFlags.enableStickerDownload);
        editor.putBoolean("enableDmMediaDownload", FeatureFlags.enableDmMediaDownload);
        editor.putBoolean("offerCopyImage", FeatureFlags.offerCopyImage);
        editor.putBoolean("offerMediaInfo", FeatureFlags.offerMediaInfo);
        editor.putBoolean("offerPlaybackSpeed", FeatureFlags.offerPlaybackSpeed);
        editor.putBoolean("maxUploadQuality", FeatureFlags.maxUploadQuality);
        editor.putBoolean("forceReelScrubber", FeatureFlags.forceReelScrubber);
        editor.putBoolean("holdLeftSlowMotion", FeatureFlags.holdLeftSlowMotion);
        editor.putBoolean("keepEditHistory", FeatureFlags.keepEditHistory);
        editor.putBoolean("jumpToFirstMessage", FeatureFlags.jumpToFirstMessage);
        editor.putBoolean("translateMessages", FeatureFlags.translateMessages);
        editor.putBoolean("bulkUnsend", FeatureFlags.bulkUnsend);
        editor.putBoolean("chatExport", FeatureFlags.chatExport);
        editor.putBoolean("hideTabHome", FeatureFlags.hideTabHome);
        editor.putBoolean("hideTabReels", FeatureFlags.hideTabReels);
        editor.putBoolean("hideTabCreate", FeatureFlags.hideTabCreate);
        editor.putBoolean("hideTabMessages", FeatureFlags.hideTabMessages);
        editor.putBoolean("hideTabActivity", FeatureFlags.hideTabActivity);
        editor.putBoolean("hideTabProfile", FeatureFlags.hideTabProfile);
        editor.putBoolean("hideNotesTray", FeatureFlags.hideNotesTray);
        editor.putBoolean("hideDmBlend", FeatureFlags.hideDmBlend);
        editor.putBoolean("readReceiptChats", FeatureFlags.readReceiptChats);
        editor.putBoolean("hideThreadsButton", FeatureFlags.hideThreadsButton);

        // Ads
        editor.putBoolean("isAdBlockEnabled", FeatureFlags.isAdBlockEnabled);
        editor.putBoolean("isAnalyticsBlocked", FeatureFlags.isAnalyticsBlocked);
        editor.putBoolean("disableTrackingLinks", FeatureFlags.disableTrackingLinks);
        editor.putBoolean("openLinksExternally", FeatureFlags.openLinksExternally);

        // Misc
        editor.putBoolean("isMiscEnabled", FeatureFlags.isMiscEnabled);
        editor.putBoolean("disableStoryFlipping", FeatureFlags.disableStoryFlipping);
        editor.putBoolean("disableVideoAutoPlay", FeatureFlags.disableVideoAutoPlay);
        editor.putBoolean("spoofLastSeen", FeatureFlags.spoofLastSeen);
        editor.putBoolean("disableSearchHistory", FeatureFlags.disableSearchHistory);
        editor.putBoolean("confirmCalls", FeatureFlags.confirmCalls);
        editor.putBoolean("spoofLocation", FeatureFlags.spoofLocation);
        editor.putString("spoofLat", String.valueOf(FeatureFlags.spoofLat));
        editor.putString("spoofLng", String.valueOf(FeatureFlags.spoofLng));
        editor.putString("spoofLabel", FeatureFlags.spoofLabel);
        editor.putString("spoofRecent", FeatureFlags.spoofRecent);
        editor.putInt("forceReelQuality", FeatureFlags.forceReelQuality);
        editor.putBoolean("disableRepost", FeatureFlags.disableRepost);
        editor.putBoolean("hideShareCreateGroup", FeatureFlags.hideShareCreateGroup);
        editor.putBoolean("exactTimestamps", FeatureFlags.exactTimestamps);
        editor.putBoolean("likeUndo", FeatureFlags.likeUndo);
        editor.putBoolean("profileFollowsYouLabel", FeatureFlags.profileFollowsYouLabel);
        editor.putBoolean("fancyBioFont", FeatureFlags.fancyBioFont);
        editor.putBoolean("savedOnProfile", FeatureFlags.savedOnProfile);
        editor.putBoolean("lockSavedCollections", FeatureFlags.lockSavedCollections);
        editor.putString("lockedCollections", FeatureFlags.lockedCollections);
        editor.putBoolean("storyViewerSearch", FeatureFlags.storyViewerSearch);
        editor.putBoolean("storyLinks", FeatureFlags.storyLinks);
        editor.putBoolean("storyInfo", FeatureFlags.storyInfo);
        editor.putBoolean("commentSearch", FeatureFlags.commentSearch);
        editor.putBoolean("followListExport", FeatureFlags.followListExport);
        editor.putBoolean("followersHistory", FeatureFlags.followersHistory);
        editor.putBoolean("showFollowerToast", FeatureFlags.showFollowerToast);
        editor.putBoolean("showFeatureToasts", FeatureFlags.showFeatureToasts);
        editor.putBoolean("enableStoryMentions", FeatureFlags.enableStoryMentions);
        editor.putBoolean("disableDiscoverPeople", FeatureFlags.disableDiscoverPeople);
        editor.putBoolean("removeBuildExpiredPopup", FeatureFlags.removeBuildExpiredPopup);
        editor.putBoolean("enableCopyComment", FeatureFlags.enableCopyComment);
        editor.putBoolean("enableCaptionCopy", FeatureFlags.enableCaptionCopy);
        editor.putBoolean("disableDoubleTapLike", FeatureFlags.disableDoubleTapLike);
        editor.putBoolean("enablePhotoZoom", FeatureFlags.enablePhotoZoom);
        editor.putBoolean("enablePostDownload", FeatureFlags.enablePostDownload);
        editor.putBoolean("enableStoryDownload", FeatureFlags.enableStoryDownload);
        editor.putBoolean("enableReelDownload", FeatureFlags.enableReelDownload);
        editor.putBoolean("enableProfileDownload", FeatureFlags.enableProfileDownload);
        editor.putBoolean("downloaderUsernameFolder", FeatureFlags.downloaderUsernameFolder);
        editor.putBoolean("downloaderAddTimestamp", FeatureFlags.downloaderAddTimestamp);
        editor.putBoolean("copyMediaLink", FeatureFlags.copyMediaLink);
        editor.putBoolean("saveInstants", FeatureFlags.saveInstants);
        editor.putBoolean("uploadInstants", FeatureFlags.uploadInstants);
        editor.putBoolean("cacheStories", FeatureFlags.cacheStories);
        editor.putBoolean("customFontEnabled", FeatureFlags.customFontEnabled);
        editor.putString("customFontPath", FeatureFlags.customFontPath);
        editor.putString("navTabOrder", FeatureFlags.navTabOrder);
        editor.putString("startupTab", FeatureFlags.startupTab);
        editor.putString("customShareDomain", FeatureFlags.customShareDomain);
        editor.putString("exactTimestampFormat", FeatureFlags.exactTimestampFormat);
        editor.putString("spoofDevice", FeatureFlags.spoofDevice);
        editor.putBoolean("customEmojiEnabled", FeatureFlags.customEmojiEnabled);
        editor.putString("customEmojiPath", FeatureFlags.customEmojiPath);
        editor.putString("downloaderCustomPath", FeatureFlags.downloaderCustomPath);
        editor.putString("downloaderCustomUri",  FeatureFlags.downloaderCustomUri);

        // Custom Theme
        editor.putBoolean("customThemeEnabled", FeatureFlags.customThemeEnabled);
        editor.putInt("themePresetId", FeatureFlags.themePresetId);
        editor.putInt("themeLightPresetId", FeatureFlags.themeLightPresetId);
        editor.putString("themePaletteJson", FeatureFlags.themePaletteJson);

        for (String flag : ExtrasFeatures.flags()) editor.putBoolean(flag, ExtrasFeatures.get(flag));

        editor.apply();
        stampChangedKeys(before);
        // Instagram -> companion: so the companion app shows what was just changed here.
        CompanionSync.pushAsync(appContext);

        FeatureManager.refreshFeatureStatus();
        if (hostProcess) {
            ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook.onSettingsChanged();
            ps.reso.instaeclipse.mods.ghost.ConversationMenuHook.refreshAll();
            if (appliedHideState != null && appliedHideState != FeatureFlags.hideSpecificChats)
                ps.reso.instaeclipse.mods.ghost.HideChatsHook.refreshKnownAccounts();
            appliedHideState = FeatureFlags.hideSpecificChats;
        }
    }

    /** Marks keys whose value this save changed, so an older companion value never overrides them. */
    private static void stampChangedKeys(java.util.Map<String, ?> before) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = null;
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String k = entry.getKey();
            if (k.startsWith("__")) continue;
            if (java.util.Objects.equals(before.get(k), entry.getValue())) continue;
            if (e == null) e = prefs.edit();
            e.putLong("__igts_" + k, now);
        }
        if (e != null) e.apply();
    }

    public static void loadAllFlags(Context context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }

        FeatureFlags.isDevEnabled = prefs.getBoolean("isDevEnabled", false);

        // Ghost Mode
        FeatureFlags.isGhostModeEnabled = prefs.getBoolean("isGhostModeEnabled", false);
        FeatureFlags.isGhostSeen = prefs.getBoolean("isGhostSeen", false);
        FeatureFlags.isGhostTyping = prefs.getBoolean("isGhostTyping", false);
        FeatureFlags.isGhostScreenshot = prefs.getBoolean("isGhostScreenshot", false);
        FeatureFlags.isGhostViewOnce = prefs.getBoolean("isGhostViewOnce", false);
        FeatureFlags.isGhostStory = prefs.getBoolean("isGhostStory", false);
        FeatureFlags.isGhostLive = prefs.getBoolean("isGhostLive", false);
        FeatureFlags.allowScreenshots = prefs.getBoolean("allowScreenshots", false);
        FeatureFlags.keepEphemeralMessages = prefs.getBoolean("keepEphemeralMessages", false);
        FeatureFlags.permanentViewMode = prefs.getBoolean("permanentViewMode", false);
        FeatureFlags.keepUnsentMessages = prefs.getBoolean("keepUnsentMessages", false);
        FeatureFlags.autoClearCache = prefs.getBoolean("autoClearCache", false);
        FeatureFlags.autoClearCacheSizeMb = prefs.getInt("autoClearCacheSizeMb", 100);
        FeatureFlags.removeMetaAI = prefs.getBoolean("removeMetaAI", false);
        FeatureFlags.lockDirectMessages = prefs.getBoolean("lockDirectMessages", false);
        FeatureFlags.lockDirectPasscode = prefs.getString("lockDirectPasscode", "");
        FeatureFlags.lockDirectSalt = prefs.getString("lockDirectSalt", "");
        FeatureFlags.lockDirectAlways = prefs.getBoolean("lockDirectAlways", false);
        FeatureFlags.lockWholeApp = prefs.getBoolean("lockWholeApp", false);
        FeatureFlags.hideSpecificChats = prefs.getBoolean("hideSpecificChats", false);
        FeatureFlags.showHideChatButton = prefs.getBoolean("showHideChatButton", false);
        FeatureFlags.showDeletedMessagesButton = prefs.getBoolean("showDeletedMessagesButton", false);
        FeatureFlags.keepUnsentInChat = prefs.getBoolean("keepUnsentInChat", false);
        FeatureFlags.downloaderUseOriginal = prefs.getBoolean("downloaderUseOriginal", true);
        FeatureFlags.downloaderQualityMaxShortSide = prefs.getInt("downloaderQualityMaxShortSide", 0);
        FeatureFlags.storyTrayScale = prefs.getInt("storyTrayScale", 100);
        FeatureFlags.feedCornerRadius = prefs.getInt("feedCornerRadius", 0);
        FeatureFlags.storyRingColor = prefs.getString("storyRingColor", "");
        FeatureFlags.downloaderCompatibility = prefs.getBoolean("downloaderCompatibility", false);
        FeatureFlags.downloaderAllowFallback = prefs.getBoolean("downloaderAllowFallback", false);
        FeatureFlags.lockUseFingerprint = prefs.getBoolean("lockUseFingerprint", true);

        // Quick Toggles
        FeatureFlags.quickToggleSeen = prefs.getBoolean("quickToggleSeen", false);
        FeatureFlags.quickToggleTyping = prefs.getBoolean("quickToggleTyping", false);
        FeatureFlags.quickToggleScreenshot = prefs.getBoolean("quickToggleScreenshot", false);
        FeatureFlags.quickToggleViewOnce = prefs.getBoolean("quickToggleViewOnce", false);
        FeatureFlags.quickToggleStory = prefs.getBoolean("quickToggleStory", false);
        FeatureFlags.quickToggleLive = prefs.getBoolean("quickToggleLive", false);
        FeatureFlags.quickToggleEphemeral = prefs.getBoolean("quickToggleEphemeral", false);
        FeatureFlags.quickTogglePermanentView = prefs.getBoolean("quickTogglePermanentView", false);
        FeatureFlags.quickToggleAllowScreenshots = prefs.getBoolean("quickToggleAllowScreenshots", false);

        // Distraction Free
        FeatureFlags.isExtremeMode = prefs.getBoolean("isExtremeMode", false);
        FeatureFlags.isDistractionFree = prefs.getBoolean("isDistractionFree", false);
        FeatureFlags.disableStories = prefs.getBoolean("disableStories", false);
        FeatureFlags.disableFeed = prefs.getBoolean("disableFeed", false);
        FeatureFlags.disableReels = prefs.getBoolean("disableReels", false);
        FeatureFlags.disableReelsExceptDM = prefs.getBoolean("disableReelsExceptDM", false);
        FeatureFlags.disableExplore = prefs.getBoolean("disableExplore", false);
        FeatureFlags.disableComments = prefs.getBoolean("disableComments", false);

        // Clean Feed
        FeatureFlags.hideSuggestionsInFeed = prefs.getBoolean("hideSuggestionsInFeed", false);
        FeatureFlags.hideThreadsSuggestions = prefs.getBoolean("hideThreadsSuggestions", false);
        FeatureFlags.hideLikedPosts = prefs.getBoolean("hideLikedPosts", false);
        FeatureFlags.confirmFeedRefresh = prefs.getBoolean("confirmFeedRefresh", false);
        FeatureFlags.hideCommunityNotes = prefs.getBoolean("hideCommunityNotes", false);
        FeatureFlags.followingOnlyFeed = prefs.getBoolean("followingOnlyFeed", false);
        FeatureFlags.hidePlusUpsells = prefs.getBoolean("hidePlusUpsells", false);
        FeatureFlags.expandCaptions = prefs.getBoolean("expandCaptions", false);
        FeatureFlags.offerAudioDownload = prefs.getBoolean("offerAudioDownload", false);
        FeatureFlags.enableVoiceDownload = prefs.getBoolean("enableVoiceDownload", false);
        FeatureFlags.enableStickerDownload = prefs.getBoolean("enableStickerDownload", false);
        FeatureFlags.enableDmMediaDownload = prefs.getBoolean("enableDmMediaDownload", false);
        FeatureFlags.offerCopyImage = prefs.getBoolean("offerCopyImage", false);
        FeatureFlags.offerMediaInfo = prefs.getBoolean("offerMediaInfo", false);
        FeatureFlags.offerPlaybackSpeed = prefs.getBoolean("offerPlaybackSpeed", false);
        FeatureFlags.maxUploadQuality = prefs.getBoolean("maxUploadQuality", false);
        FeatureFlags.forceReelScrubber = prefs.getBoolean("forceReelScrubber", false);
        FeatureFlags.holdLeftSlowMotion = prefs.getBoolean("holdLeftSlowMotion", false);
        FeatureFlags.keepEditHistory = prefs.getBoolean("keepEditHistory", false);
        FeatureFlags.jumpToFirstMessage = prefs.getBoolean("jumpToFirstMessage", false);
        FeatureFlags.translateMessages = prefs.getBoolean("translateMessages", false);
        FeatureFlags.bulkUnsend = prefs.getBoolean("bulkUnsend", false);
        FeatureFlags.chatExport = prefs.getBoolean("chatExport", false);
        FeatureFlags.hideTabHome = prefs.getBoolean("hideTabHome", false);
        FeatureFlags.hideTabReels = prefs.getBoolean("hideTabReels", false);
        FeatureFlags.hideTabCreate = prefs.getBoolean("hideTabCreate", false);
        FeatureFlags.hideTabMessages = prefs.getBoolean("hideTabMessages", false);
        FeatureFlags.hideTabActivity = prefs.getBoolean("hideTabActivity", false);
        FeatureFlags.hideTabProfile = prefs.getBoolean("hideTabProfile", false);
        FeatureFlags.hideNotesTray = prefs.getBoolean("hideNotesTray", false);
        FeatureFlags.hideDmBlend = prefs.getBoolean("hideDmBlend", false);
        FeatureFlags.readReceiptChats = prefs.getBoolean("readReceiptChats", false);
        FeatureFlags.hideThreadsButton = prefs.getBoolean("hideThreadsButton", false);

        // Ads
        FeatureFlags.isAdBlockEnabled = prefs.getBoolean("isAdBlockEnabled", false);
        FeatureFlags.isAnalyticsBlocked = prefs.getBoolean("isAnalyticsBlocked", false);
        FeatureFlags.disableTrackingLinks = prefs.getBoolean("disableTrackingLinks", false);
        FeatureFlags.openLinksExternally = prefs.getBoolean("openLinksExternally", false);

        // Misc
        FeatureFlags.isMiscEnabled = prefs.getBoolean("isMiscEnabled", false);
        FeatureFlags.disableStoryFlipping = prefs.getBoolean("disableStoryFlipping", false);
        FeatureFlags.disableVideoAutoPlay = prefs.getBoolean("disableVideoAutoPlay", false);
        FeatureFlags.spoofLastSeen = prefs.getBoolean("spoofLastSeen", false);
        FeatureFlags.disableSearchHistory = prefs.getBoolean("disableSearchHistory", false);
        FeatureFlags.confirmCalls = prefs.getBoolean("confirmCalls", false);
        FeatureFlags.spoofLocation = prefs.getBoolean("spoofLocation", false);
        FeatureFlags.spoofLat = readDoublePref(prefs, "spoofLat", 0.0);
        FeatureFlags.spoofLng = readDoublePref(prefs, "spoofLng", 0.0);
        FeatureFlags.spoofLabel = prefs.getString("spoofLabel", "");
        FeatureFlags.spoofRecent = prefs.getString("spoofRecent", "");
        FeatureFlags.forceReelQuality = prefs.getInt("forceReelQuality", 0);
        FeatureFlags.disableRepost = prefs.getBoolean("disableRepost", false);
        FeatureFlags.hideShareCreateGroup = prefs.getBoolean("hideShareCreateGroup", false);
        FeatureFlags.exactTimestamps = prefs.getBoolean("exactTimestamps", false);
        FeatureFlags.likeUndo = prefs.getBoolean("likeUndo", false);
        FeatureFlags.profileFollowsYouLabel = prefs.getBoolean("profileFollowsYouLabel", false);
        FeatureFlags.fancyBioFont = prefs.getBoolean("fancyBioFont", false);
        FeatureFlags.savedOnProfile = prefs.getBoolean("savedOnProfile", false);
        FeatureFlags.lockSavedCollections = prefs.getBoolean("lockSavedCollections", false);
        FeatureFlags.lockedCollections = prefs.getString("lockedCollections", "");
        FeatureFlags.storyViewerSearch = prefs.getBoolean("storyViewerSearch", false);
        FeatureFlags.storyLinks = prefs.getBoolean("storyLinks", false);
        FeatureFlags.storyInfo = prefs.getBoolean("storyInfo", false);
        FeatureFlags.commentSearch = prefs.getBoolean("commentSearch", false);
        FeatureFlags.followListExport = prefs.getBoolean("followListExport", false);
        FeatureFlags.followersHistory = prefs.getBoolean("followersHistory", false);
        FeatureFlags.showFollowerToast = prefs.getBoolean("showFollowerToast", false);
        FeatureFlags.showFeatureToasts = prefs.getBoolean("showFeatureToasts", false);
        FeatureFlags.enableStoryMentions = prefs.getBoolean("enableStoryMentions", false);
        FeatureFlags.disableDiscoverPeople = prefs.getBoolean("disableDiscoverPeople", false);
        FeatureFlags.removeBuildExpiredPopup = prefs.getBoolean("removeBuildExpiredPopup", false);
        FeatureFlags.enableCopyComment = prefs.getBoolean("enableCopyComment", false);
        FeatureFlags.enableCaptionCopy = prefs.getBoolean("enableCaptionCopy", false);
        FeatureFlags.disableDoubleTapLike = prefs.getBoolean("disableDoubleTapLike", false);
        FeatureFlags.enablePhotoZoom = prefs.getBoolean("enablePhotoZoom", false);
        FeatureFlags.enablePostDownload = prefs.getBoolean("enablePostDownload", false);
        FeatureFlags.enableStoryDownload = prefs.getBoolean("enableStoryDownload", false);
        FeatureFlags.enableReelDownload = prefs.getBoolean("enableReelDownload", false);
        FeatureFlags.enableProfileDownload = prefs.getBoolean("enableProfileDownload", false);
        FeatureFlags.downloaderUsernameFolder = prefs.getBoolean("downloaderUsernameFolder", false);
        FeatureFlags.downloaderAddTimestamp   = prefs.getBoolean("downloaderAddTimestamp", false);
        FeatureFlags.copyMediaLink            = prefs.getBoolean("copyMediaLink", false);
        FeatureFlags.saveInstants             = prefs.getBoolean("saveInstants", false);
        FeatureFlags.uploadInstants           = prefs.getBoolean("uploadInstants", false);
        FeatureFlags.cacheStories             = prefs.getBoolean("cacheStories", false);
        FeatureFlags.customFontEnabled        = prefs.getBoolean("customFontEnabled", false);
        FeatureFlags.customFontPath           = prefs.getString("customFontPath", "");
        FeatureFlags.navTabOrder              = prefs.getString("navTabOrder", "");
        FeatureFlags.startupTab               = prefs.getString("startupTab", "");
        FeatureFlags.customShareDomain        = prefs.getString("customShareDomain", "");
        FeatureFlags.exactTimestampFormat     = prefs.getString("exactTimestampFormat", "");
        FeatureFlags.spoofDevice              = prefs.getString("spoofDevice", "");
        FeatureFlags.customEmojiEnabled       = prefs.getBoolean("customEmojiEnabled", false);
        FeatureFlags.customEmojiPath          = prefs.getString("customEmojiPath", "");
        FeatureFlags.downloaderCustomPath     = prefs.getString("downloaderCustomPath", "");
        FeatureFlags.downloaderCustomUri      = prefs.getString("downloaderCustomUri",  "");

        // Custom Theme
        FeatureFlags.customThemeEnabled = prefs.getBoolean("customThemeEnabled", false);
        FeatureFlags.themePresetId = prefs.getInt("themePresetId", 1);
        // #251: "theme in dark mode only" is the default so a custom theme never breaks light mode
        // (a dark palette's surfaces/text are illegible on IG's light chrome). The old default was 0
        // ("same theme as dark mode"), which rendered light mode unreadable; migrate it to -1 once
        // so existing users are fixed, without overriding anyone who later re-selects it on purpose.
        FeatureFlags.themeLightPresetId = prefs.getInt("themeLightPresetId", -1);
        if (!prefs.getBoolean("themeLightMigratedV1", false)) {
            if (FeatureFlags.themeLightPresetId == 0) FeatureFlags.themeLightPresetId = -1;
            prefs.edit()
                    .putInt("themeLightPresetId", FeatureFlags.themeLightPresetId)
                    .putBoolean("themeLightMigratedV1", true)
                    .apply();
        }
        FeatureFlags.themePaletteJson = prefs.getString("themePaletteJson", "");

        for (String flag : ExtrasFeatures.flags()) ExtrasFeatures.set(flag, prefs.getBoolean(flag, false));

        FeatureManager.refreshFeatureStatus();
        if (hostProcess) {
            ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook.onSettingsChanged();
            ps.reso.instaeclipse.mods.ghost.ConversationMenuHook.refreshAll();
            if (appliedHideState != null && appliedHideState != FeatureFlags.hideSpecificChats)
                ps.reso.instaeclipse.mods.ghost.HideChatsHook.refreshKnownAccounts();
            appliedHideState = FeatureFlags.hideSpecificChats;
        }
    }

    private static double readDoublePref(SharedPreferences p, String key, double fallback) {
        try {
            return Double.parseDouble(p.getString(key, String.valueOf(fallback)));
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
