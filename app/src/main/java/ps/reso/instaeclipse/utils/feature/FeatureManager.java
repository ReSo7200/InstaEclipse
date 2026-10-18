package ps.reso.instaeclipse.utils.feature;

import ps.reso.instaeclipse.R;

public class FeatureManager {

    private static void reg(boolean enabled, String key, int labelResId) {
        if (enabled) FeatureStatusTracker.setEnabled(key, labelResId);
        else FeatureStatusTracker.setDisabled(key);
    }

    public static void refreshFeatureStatus() {
        reg(FeatureFlags.isDevEnabled, "DevOptions", R.string.ig_dialog_section_dev_options);

        reg(FeatureFlags.isGhostSeen, "GhostSeen", R.string.ig_dialog_ghost_hide_dm_seen);
        reg(FeatureFlags.isGhostTyping, "GhostTyping", R.string.ig_dialog_ghost_hide_typing);
        reg(FeatureFlags.isGhostScreenshot, "GhostScreenshot", R.string.ig_dialog_ghost_bypass_screenshot);
        reg(FeatureFlags.isGhostViewOnce, "GhostViewOnce", R.string.ig_dialog_ghost_hide_view_once);
        reg(FeatureFlags.isGhostStory, "GhostStories", R.string.ig_dialog_ghost_hide_story_views);
        reg(FeatureFlags.isGhostLive, "GhostLive", R.string.ig_dialog_ghost_hide_live_presence);
        reg(FeatureFlags.allowScreenshots, "AllowScreenshots", R.string.ig_dialog_ghost_allow_screenshots_dms);
        reg(FeatureFlags.keepEphemeralMessages, "KeepEphemeralMessages", R.string.ig_dialog_ghost_keep_disappearing);
        reg(FeatureFlags.permanentViewMode, "PermanentViewMode", R.string.ig_dialog_ghost_permanent_view_once);
        reg(FeatureFlags.keepUnsentMessages, "KeepUnsentMessages", R.string.ig_dialog_ghost_keep_unsent);

        reg(FeatureFlags.removeMetaAI, "RemoveMetaAI", R.string.ig_dialog_misc_remove_meta_ai);
        reg(FeatureFlags.lockDirectMessages, "LockDirectMessages", R.string.ig_dialog_misc_lock_dms);
        reg(FeatureFlags.hideSpecificChats, "HideSpecificChats", R.string.ig_hide_chats_title);

        reg(FeatureFlags.hideSuggestionsInFeed, "HideSuggestionsInFeed", R.string.ig_dialog_clean_feed_hide_suggested);
        reg(FeatureFlags.hideThreadsSuggestions, "HideThreadsSuggestions", R.string.ig_dialog_clean_feed_hide_threads);
        reg(FeatureFlags.expandCaptions, "ExpandCaptions", R.string.ig_dialog_clean_feed_expand_captions);
        reg(FeatureFlags.hideCommunityNotes, "HideCommunityNotes", R.string.ig_dialog_clean_feed_hide_community_notes);
        reg(FeatureFlags.followingOnlyFeed, "FollowingOnlyFeed", R.string.ig_dialog_clean_feed_following_only);
        reg(FeatureFlags.hideLikedPosts, "HideLikedPosts", R.string.ig_dialog_clean_feed_hide_liked);

        reg(FeatureFlags.disableTrackingLinks, "DisableTrackingLinks", R.string.ig_dialog_ad_disable_tracking);
        reg(FeatureFlags.openLinksExternally, "OpenLinksExternally", R.string.ig_dialog_open_links_externally);
        reg(FeatureFlags.showFollowerToast, "FollowerToast", R.string.ig_dialog_misc_show_follower_toast);
        reg(FeatureFlags.enableStoryMentions, "StoryMentions", R.string.ig_dialog_misc_view_story_mentions);
        reg(FeatureFlags.disableDiscoverPeople, "DisableDiscoverPeople", R.string.ig_dialog_misc_disable_discover_people);
        reg(FeatureFlags.forceReelQuality > 0, "ForceReelQuality", R.string.ig_dialog_quality_force_reels);
        reg(FeatureFlags.spoofLocation, "SpoofLocation", R.string.ig_dialog_location_spoof_enable);
        reg(FeatureFlags.spoofLastSeen, "SpoofLastSeen", R.string.ig_dialog_misc_spoof_last_seen);
        reg(FeatureFlags.customThemeEnabled, "CustomTheme", R.string.theme_title);
        reg(FeatureFlags.removeBuildExpiredPopup, "RemoveBuildExpiredPopup", R.string.ig_dialog_dev_remove_build_expired);
        reg(FeatureFlags.enablePostDownload, "PostDownload", R.string.ig_dialog_downloader_posts);
        reg(FeatureFlags.copyMediaLink, "CopyMediaLink", R.string.ig_copy_link_title);
        reg(FeatureFlags.saveInstants, "SaveInstants", R.string.ig_instant_save_title);
        reg(FeatureFlags.uploadInstants, "UploadInstants", R.string.ig_instant_upload_title);
        reg(FeatureFlags.cacheStories, "CacheStories", R.string.ig_story_cache_title);
        reg(FeatureFlags.customFontEnabled, "CustomFont", R.string.ig_custom_font_title);
        reg(FeatureFlags.customEmojiEnabled, "CustomEmoji", R.string.ig_custom_emoji_title);
        reg(FeatureFlags.enableStoryDownload, "StoryDownload", R.string.ig_dialog_downloader_stories);
        reg(FeatureFlags.enableReelDownload, "ReelDownload", R.string.ig_dialog_downloader_reels);
        reg(FeatureFlags.enableProfileDownload, "ProfileDownload", R.string.ig_dialog_downloader_profiles);
        reg(FeatureFlags.disableDoubleTapLike, "DisableDoubleTapLike", R.string.ig_dialog_misc_disable_double_tap_like);

        reg(FeatureFlags.profileFollowsYouLabel, "ProfileFollowsYou", R.string.ig_dialog_misc_profile_follows_label);
        reg(FeatureFlags.likeUndo, "LikeUndo", R.string.ig_dialog_misc_like_undo);
        reg(FeatureFlags.exactTimestamps, "ExactTimestamps", R.string.ig_dialog_misc_exact_timestamps);
        reg(FeatureFlags.hideShareCreateGroup, "HideShareCreateGroup", R.string.ig_dialog_misc_hide_share_create_group);

        reg(FeatureFlags.chatExport, "ChatExport", R.string.ig_chat_export);
        reg(FeatureFlags.fancyBioFont, "FancyBioFont", R.string.ig_dialog_fancy_bio_font);
        reg(FeatureFlags.keepEditHistory, "EditHistory", R.string.ig_feat_edit_history);
        reg(FeatureFlags.jumpToFirstMessage, "JumpToFirstMessage", R.string.ig_jump_first_message);
        reg(FeatureFlags.translateMessages, "ChatTranslate", R.string.ig_dialog_translate_messages);
        reg(FeatureFlags.bulkUnsend, "BulkUnsend", R.string.ig_dialog_bulk_unsend);
        reg(FeatureFlags.lockSavedCollections, "LockSavedCollections", R.string.ig_feat_lock_collections);
        reg(FeatureFlags.disableRepost, "DisableRepost", R.string.ig_dialog_misc_disable_repost);
        reg(FeatureFlags.commentSearch, "CommentSearch", R.string.ig_feat_comment_search);
        reg(FeatureFlags.storyViewerSearch, "StoryViewerSearch", R.string.ig_feat_viewer_search);
        reg(FeatureFlags.storyLinks, "StoryLinks", R.string.ig_feat_story_links);
        reg(FeatureFlags.storyInfo, "StoryInfo", R.string.ig_feat_story_info);
        reg(FeatureFlags.followListExport, "FollowListExport", R.string.ig_feat_follow_export);
        reg(FeatureFlags.followersHistory, "FollowersHistory", R.string.ig_feat_followers_history);
        reg(FeatureFlags.savedOnProfile, "SavedOnProfile", R.string.ig_feat_saved_on_profile);
        reg(FeatureFlags.hideNotesTray, "HideNotesTray", R.string.ig_feat_hide_notes);
        reg(FeatureFlags.hideDmBlend, "HideDmBlend", R.string.ig_feat_hide_blend);
        reg(FeatureFlags.readReceiptChats, "ReadReceiptChats", R.string.ig_feat_read_receipt_chats);
        reg(FeatureFlags.hideThreadsButton, "HideThreadsButton", R.string.ig_feat_hide_threads);
        reg(FeatureFlags.offerPlaybackSpeed, "PlaybackSpeed", R.string.ig_playback_speed_title);
        reg(FeatureFlags.holdLeftSlowMotion, "HoldSlowMotion", R.string.ig_dialog_reels_hold_left_slow);
        reg(FeatureFlags.enableVoiceDownload, "VoiceDownload", R.string.ig_feat_voice_download);
        reg(FeatureFlags.enableDmMediaDownload, "DmMediaDownload", R.string.ig_dialog_downloader_dm_media);
        reg(FeatureFlags.maxUploadQuality, "MaxUploadQuality", R.string.ig_feat_max_upload_quality);
        reg(FeatureFlags.enableCaptionCopy, "CaptionCopy", R.string.ig_caption_copy_title);
        reg(FeatureFlags.enableCopyComment, "CopyComment", R.string.ig_dialog_misc_copy_comment);
        reg(FeatureFlags.enablePhotoZoom, "PhotoZoom", R.string.ig_feat_photo_zoom);

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
}
