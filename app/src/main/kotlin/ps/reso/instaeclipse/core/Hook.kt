package ps.reso.instaeclipse.core

import ps.reso.instaeclipse.R

enum class HookGroup(val title: String, val labelRes: Int) {
    APPEARANCE("Appearance", R.string.feat_group_appearance),
    PRIVACY("Privacy", R.string.feat_group_privacy),
    MEDIA("Media", R.string.feat_group_media),
    SOCIAL("Social", R.string.feat_group_social),
    TOOLS("Tools", R.string.feat_group_tools)
}

enum class HookCategory(val title: String, val labelRes: Int, val group: HookGroup) {
    THEME("Theme", R.string.ig_dialog_menu_theme, HookGroup.APPEARANCE),
    FEED("Feed", R.string.menu_feed, HookGroup.APPEARANCE),
    NAVIGATION("Navigation", R.string.menu_navigation, HookGroup.APPEARANCE),
    REELS("Reels", R.string.menu_reels, HookGroup.APPEARANCE),
    STORIES("Stories", R.string.menu_stories, HookGroup.APPEARANCE),

    GHOST("Ghost Mode", R.string.ig_dialog_menu_ghost_settings, HookGroup.PRIVACY),
    MESSAGES("Messages", R.string.menu_messages, HookGroup.PRIVACY),
    HIDE_CHATS("Hide Specific Chats", R.string.ig_hide_chats_title, HookGroup.PRIVACY),
    LOCK("App Lock", R.string.ig_dialog_misc_lock_section, HookGroup.PRIVACY),
    ADS("Ads & Tracking", R.string.menu_ads_tracking, HookGroup.PRIVACY),
    LOCATION("Location", R.string.ig_dialog_menu_location, HookGroup.PRIVACY),
    DISTRACTION_FREE("Distraction-Free", R.string.ig_dialog_menu_distraction_free, HookGroup.PRIVACY),

    DOWNLOADER("Downloader", R.string.ig_dialog_menu_downloader, HookGroup.MEDIA),
    POST_MENU("Post Menu", R.string.menu_post_menu, HookGroup.MEDIA),

    INTERACTIONS("Likes & Comments", R.string.menu_likes_comments, HookGroup.SOCIAL),
    PROFILES("Profiles", R.string.menu_profiles, HookGroup.SOCIAL),

    APP("App & Account", R.string.menu_app, HookGroup.TOOLS),
    DEV_OPTIONS("Developer Options", R.string.ig_dialog_menu_dev_options, HookGroup.TOOLS)
}

data class Hook(
    val id: String,
    val name: String,
    val description: String,
    val category: HookCategory,
    val defaultEnabled: Boolean = false,
    val isExtreme: Boolean = false,
    val requiresAnyOf: List<String> = emptyList(),
    val disabledWhenTrue: String? = null,
    val dependsOn: String? = null,
    val cascadeOffKey: String? = null,
    /** Translated title; [name] is the English fallback. */
    val nameRes: Int? = null
)
