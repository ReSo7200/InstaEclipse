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

        if (FeatureFlags.keepUnsentMessages) {
            FeatureStatusTracker.setEnabled("KeepUnsentMessages", R.string.ig_dialog_ghost_keep_unsent);
        } else {
            FeatureStatusTracker.setDisabled("KeepUnsentMessages");
        }

        // Auto-Clear Cache is a filesystem operation (clears IG's cache dirs on background),
        // not an Instagram-code hook, so it has no meaningful ✅/❌ "hooked" state — intentionally
        // omitted from the load toast (it was always showing a ❌ despite working).

        if (FeatureFlags.removeMetaAI) {
            FeatureStatusTracker.setEnabled("RemoveMetaAI", R.string.ig_dialog_misc_remove_meta_ai);
        } else {
            FeatureStatusTracker.setDisabled("RemoveMetaAI");
        }

        if (FeatureFlags.lockDirectMessages) {
            FeatureStatusTracker.setEnabled("LockDirectMessages", R.string.ig_dialog_misc_lock_dms);
        } else {
            FeatureStatusTracker.setDisabled("LockDirectMessages");
        }

        if (FeatureFlags.hideSpecificChats) {
            FeatureStatusTracker.setEnabled("HideSpecificChats", R.string.ig_hide_chats_title);
        } else {
            FeatureStatusTracker.setDisabled("HideSpecificChats");
        }

        // Clean Feed
        if (FeatureFlags.hideSuggestionsInFeed) {
            FeatureStatusTracker.setEnabled("HideSuggestionsInFeed", R.string.ig_dialog_clean_feed_hide_suggested);
        } else {
            FeatureStatusTracker.setDisabled("HideSuggestionsInFeed");
        }

        if (FeatureFlags.hideThreadsSuggestions) {
            FeatureStatusTracker.setEnabled("HideThreadsSuggestions", R.string.ig_dialog_clean_feed_hide_threads);
        } else {
            FeatureStatusTracker.setDisabled("HideThreadsSuggestions");
        }

        if (FeatureFlags.expandCaptions) {
            FeatureStatusTracker.setEnabled("ExpandCaptions", R.string.ig_dialog_clean_feed_expand_captions);
        } else {
            FeatureStatusTracker.setDisabled("ExpandCaptions");
        }

        if (FeatureFlags.hideCommunityNotes) {
            FeatureStatusTracker.setEnabled("HideCommunityNotes", R.string.ig_dialog_clean_feed_hide_community_notes);
        } else {
            FeatureStatusTracker.setDisabled("HideCommunityNotes");
        }
        if (FeatureFlags.followingOnlyFeed) {
            FeatureStatusTracker.setEnabled("FollowingOnlyFeed", R.string.ig_dialog_clean_feed_following_only);
        } else {
            FeatureStatusTracker.setDisabled("FollowingOnlyFeed");
        }


        if (FeatureFlags.confirmFeedRefresh) {
            FeatureStatusTracker.setEnabled("ConfirmFeedRefresh", R.string.ig_confirm_refresh);
        } else {
            FeatureStatusTracker.setDisabled("ConfirmFeedRefresh");
        }

        if (FeatureFlags.hideLikedPosts) {
            FeatureStatusTracker.setEnabled("HideLikedPosts", R.string.ig_dialog_clean_feed_hide_liked);
        } else {
            FeatureStatusTracker.setDisabled("HideLikedPosts");
        }


        // Miscellaneous
        if (FeatureFlags.disableTrackingLinks) {
            FeatureStatusTracker.setEnabled("DisableTrackingLinks", R.string.ig_dialog_ad_disable_tracking);
        } else {
            FeatureStatusTracker.setDisabled("DisableTrackingLinks");
        }

        if (FeatureFlags.profileFollowsYouLabel) {
            FeatureStatusTracker.setEnabled("ProfileFollowsYou", R.string.ig_dialog_misc_profile_follows_label);
        } else {
            FeatureStatusTracker.setDisabled("ProfileFollowsYou");
        }

        if (FeatureFlags.likeUndo) {
            FeatureStatusTracker.setEnabled("LikeUndo", R.string.ig_dialog_misc_like_undo);
        } else {
            FeatureStatusTracker.setDisabled("LikeUndo");
        }

        if (FeatureFlags.exactTimestamps) {
            FeatureStatusTracker.setEnabled("ExactTimestamps", R.string.ig_dialog_misc_exact_timestamps);
        } else {
            FeatureStatusTracker.setDisabled("ExactTimestamps");
        }

        if (FeatureFlags.hideShareCreateGroup) {
            FeatureStatusTracker.setEnabled("HideShareCreateGroup", R.string.ig_dialog_misc_hide_share_create_group);
        } else {
            FeatureStatusTracker.setDisabled("HideShareCreateGroup");
        }

        if (FeatureFlags.openLinksExternally) {
            FeatureStatusTracker.setEnabled("OpenLinksExternally", R.string.ig_dialog_open_links_externally);
        } else {
            FeatureStatusTracker.setDisabled("OpenLinksExternally");
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

        if (FeatureFlags.forceReelQuality > 0) {
            FeatureStatusTracker.setEnabled("ForceReelQuality", R.string.ig_dialog_quality_force_reels);
        } else {
            FeatureStatusTracker.setDisabled("ForceReelQuality");
        }

        if (FeatureFlags.spoofLocation) {
            FeatureStatusTracker.setEnabled("SpoofLocation", R.string.ig_dialog_location_spoof_enable);
        } else {
            FeatureStatusTracker.setDisabled("SpoofLocation");
        }

        if (FeatureFlags.spoofLastSeen) {
            FeatureStatusTracker.setEnabled("SpoofLastSeen", R.string.ig_dialog_misc_spoof_last_seen);
        } else {
            FeatureStatusTracker.setDisabled("SpoofLastSeen");
        }

        if (FeatureFlags.customThemeEnabled) {
            FeatureStatusTracker.setEnabled("CustomTheme", R.string.theme_title);
        } else {
            FeatureStatusTracker.setDisabled("CustomTheme");
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

        if (FeatureFlags.copyMediaLink) {
            FeatureStatusTracker.setEnabled("CopyMediaLink", R.string.ig_copy_link_title);
        } else {
            FeatureStatusTracker.setDisabled("CopyMediaLink");
        }

        if (FeatureFlags.saveInstants) {
            FeatureStatusTracker.setEnabled("SaveInstants", R.string.ig_instant_save_title);
        } else {
            FeatureStatusTracker.setDisabled("SaveInstants");
        }

        if (FeatureFlags.uploadInstants) {
            FeatureStatusTracker.setEnabled("UploadInstants", R.string.ig_instant_upload_title);
        } else {
            FeatureStatusTracker.setDisabled("UploadInstants");
        }

        if (FeatureFlags.cacheStories) {
            FeatureStatusTracker.setEnabled("CacheStories", R.string.ig_story_cache_title);
        } else {
            FeatureStatusTracker.setDisabled("CacheStories");
        }

        if (FeatureFlags.customFontEnabled) {
            FeatureStatusTracker.setEnabled("CustomFont", R.string.ig_custom_font_title);
        } else {
            FeatureStatusTracker.setDisabled("CustomFont");
        }

        if (FeatureFlags.customEmojiEnabled) {
            FeatureStatusTracker.setEnabled("CustomEmoji", R.string.ig_custom_emoji_title);
        } else {
            FeatureStatusTracker.setDisabled("CustomEmoji");
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

        // ── Newer features (concise labels for the load toast's dense grid) ───
        reg(FeatureFlags.chatExport,           "ChatExport",           R.string.ig_chat_export);
        reg(FeatureFlags.fancyBioFont,         "FancyBioFont",         R.string.ig_dialog_fancy_bio_font);
        reg(FeatureFlags.keepEditHistory,      "EditHistory",          R.string.ig_feat_edit_history);
        reg(FeatureFlags.jumpToFirstMessage,   "JumpToFirstMessage",   R.string.ig_jump_first_message);
        reg(FeatureFlags.translateMessages,    "ChatTranslate",        R.string.ig_dialog_translate_messages);
        reg(FeatureFlags.bulkUnsend,           "BulkUnsend",           R.string.ig_dialog_bulk_unsend);
        reg(FeatureFlags.lockSavedCollections, "LockSavedCollections", R.string.ig_feat_lock_collections);
        reg(FeatureFlags.disableRepost,        "DisableRepost",        R.string.ig_dialog_misc_disable_repost);
        reg(FeatureFlags.commentSearch,        "CommentSearch",        R.string.ig_feat_comment_search);
        reg(FeatureFlags.storyViewerSearch,    "StoryViewerSearch",    R.string.ig_feat_viewer_search);
        reg(FeatureFlags.storyLinks,           "StoryLinks",           R.string.ig_feat_story_links);
        reg(FeatureFlags.storyInfo,            "StoryInfo",            R.string.ig_feat_story_info);
        reg(FeatureFlags.followListExport,     "FollowListExport",     R.string.ig_feat_follow_export);
        reg(FeatureFlags.followersHistory,     "FollowersHistory",     R.string.ig_feat_followers_history);
        reg(FeatureFlags.savedOnProfile,       "SavedOnProfile",       R.string.ig_feat_saved_on_profile);
        reg(FeatureFlags.hideNotesTray,        "HideNotesTray",        R.string.ig_feat_hide_notes);
        reg(FeatureFlags.hideDmBlend,          "HideDmBlend",          R.string.ig_feat_hide_blend);
        reg(FeatureFlags.readReceiptChats,     "ReadReceiptChats",     R.string.ig_feat_read_receipt_chats);
        reg(FeatureFlags.hideThreadsButton,    "HideThreadsButton",    R.string.ig_feat_hide_threads);
        reg(FeatureFlags.offerPlaybackSpeed,   "PlaybackSpeed",        R.string.ig_playback_speed_title);
        reg(FeatureFlags.holdLeftSlowMotion,   "HoldSlowMotion",       R.string.ig_dialog_reels_hold_left_slow);
        reg(FeatureFlags.enableVoiceDownload,  "VoiceDownload",        R.string.ig_feat_voice_download);
        reg(FeatureFlags.enableStickerDownload, "StickerDownload",     R.string.ig_feat_sticker_download);
        reg(!FeatureFlags.storyRingColor.isEmpty(), "StoryRingColor",  R.string.ig_story_ring_color);
        reg(FeatureFlags.feedCornerRadius > 0, "RoundedFeedPosts",     R.string.ig_feed_corner_radius);
        reg(FeatureFlags.disableSearchHistory, "DisableSearchHistory", R.string.ig_disable_search_history);
        reg(FeatureFlags.confirmCalls,         "ConfirmCalls",         R.string.ig_call_confirm);
        reg(FeatureFlags.enableDmMediaDownload, "DmMediaDownload",     R.string.ig_dialog_downloader_dm_media);
        reg(FeatureFlags.maxUploadQuality,     "MaxUploadQuality",     R.string.ig_feat_max_upload_quality);
        reg(FeatureFlags.enableCaptionCopy,    "CaptionCopy",          R.string.ig_caption_copy_title);
        reg(FeatureFlags.enableCopyComment,    "CopyComment",          R.string.ig_dialog_misc_copy_comment);
        reg(FeatureFlags.enablePhotoZoom,      "PhotoZoom",            R.string.ig_feat_photo_zoom);
        reg(FeatureFlags.isAdBlockEnabled,     "AdBlocker",            R.string.ig_dialog_ad_block_ads);
        reg(FeatureFlags.isAnalyticsBlocked,   "BlockAnalytics",       R.string.ig_dialog_ad_block_analytics);
        reg(FeatureFlags.hidePlusUpsells,      "HidePlusUpsells",      R.string.ig_dialog_clean_feed_hide_plus);
        reg(FeatureFlags.disableVideoAutoPlay, "DisableVideoAutoPlay", R.string.ig_dialog_misc_disable_video_autoplay);
        reg(FeatureFlags.forceReelScrubber,    "ForceReelScrubber",    R.string.ig_dialog_downloader_reel_scrubber);
        reg(FeatureFlags.disableStoryFlipping, "DisableStoryFlipping", R.string.ig_dialog_misc_disable_story_autoswipe);
        reg(FeatureFlags.lockWholeApp,         "LockWholeApp",         R.string.ig_dialog_misc_lock_whole_app);
        reg(FeatureFlags.offerAudioDownload,   "AudioDownload",        R.string.ig_dialog_downloader_offer_audio);
        reg(FeatureFlags.offerCopyImage,       "CopyImage",            R.string.ig_dialog_downloader_offer_copy_image);
        reg(FeatureFlags.offerMediaInfo,       "MediaInfo",            R.string.ig_dialog_downloader_offer_media_info);
        reg(!FeatureFlags.spoofDevice.isEmpty(), "SpoofDevice",        R.string.ig_spoof_device);
        reg(FeatureFlags.storyTrayScale < 100, "StoryTraySize",        R.string.ig_story_tray_size);
        reg(FeatureFlags.autoClearCache,       "AutoClearCache",       R.string.ig_dialog_auto_clear_cache);
        reg(FeatureFlags.hideTabHome || FeatureFlags.hideTabReels || FeatureFlags.hideTabCreate
                || FeatureFlags.hideTabMessages || FeatureFlags.hideTabActivity || FeatureFlags.hideTabProfile,
                "HideNavTabs", R.string.menu_nav_tabs);
        // Distraction Free: each section turned off
        reg(FeatureFlags.disableStories,       "DisableStoriesSection",  R.string.ig_dialog_distraction_disable_stories);
        reg(FeatureFlags.disableFeed,          "DisableFeedSection",     R.string.ig_dialog_distraction_disable_feed);
        reg(FeatureFlags.disableReels || FeatureFlags.disableReelsExceptDM,
                "DisableReelsSection", R.string.ig_dialog_distraction_disable_reels);
        reg(FeatureFlags.disableExplore,       "DisableExploreSection",  R.string.ig_dialog_distraction_disable_explore);
        reg(FeatureFlags.disableComments,      "DisableCommentsSection", R.string.ig_dialog_distraction_disable_comments);

        if (!FeatureFlags.startupTab.isEmpty()) {
            FeatureStatusTracker.setEnabled("StartupTab", R.string.ig_dialog_extras_startup_tab);
        } else {
            FeatureStatusTracker.setDisabled("StartupTab");
        }

        for (ExtrasFeatures.Entry e : ExtrasFeatures.ALL) {
            if (ExtrasFeatures.get(e.flag)) FeatureStatusTracker.setEnabled(e.flag, e.label);
            else FeatureStatusTracker.setDisabled(e.flag);
        }
    }

    /** Register a feature in the load toast when enabled, or drop it when off. */
    private static void reg(boolean enabled, String key, int labelResId) {
        if (enabled) FeatureStatusTracker.setEnabled(key, labelResId);
        else FeatureStatusTracker.setDisabled(key);
    }
}
