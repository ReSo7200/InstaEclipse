# Legacy Xposed API in use (XposedBridge API 82)

Snapshot of the code BEFORE the migration (branch point 6f70d5a). Regenerate from the current code with `python docs/xposed-api-102/inventory.py .`.
Everything here must keep working through the compat layer (see MODERN_API.md).

## Totals

| API | Uses |
|---|---|
| `XposedBridge.hookMethod` | 155 |
| `XposedBridge.hookAllMethods` | 8 |
| `XposedBridge.hookAllConstructors` | 2 |
| `XposedBridge.invokeOriginalMethod` | 4 |
| `XposedBridge.log` | 4 |
| `XposedHelpers.findAndHookMethod` | 66 |
| `XposedHelpers.findAndHookConstructor` | 1 |
| `XposedHelpers.findClass` | 12 |
| `XposedHelpers.getObjectField` | 9 |
| `XposedHelpers.getBooleanField` | 1 |
| `XposedHelpers.getStaticObjectField` | 2 |
| `XposedHelpers.setStaticObjectField` | 2 |
| `XposedHelpers.callMethod` | 1 |
| `XposedHelpers.callStaticMethod` | 2 |
| `XposedHelpers.get/setAdditionalInstanceField` | 2 |
| `new XC_MethodHook` | 191 |
| `param.getThrowable/setThrowable` | 15 |
| `XC_MethodHook.Unhook` | 4 |
| `XSharedPreferences` | 11 |
| `IXposedHookLoadPackage / LoadPackageParam` | 5 |
| `IXposedHookZygoteInit / StartupParam` | 3 |
| `XModuleResources (module icons/strings)` | 11 |
| `AndroidAppHelper.currentApplication` | 38 |

**103 files** import `de.robv.android.xposed`.

## Entry and packaging (legacy)

- `app/src/main/assets/xposed_init` -> `ps.reso.instaeclipse.Xposed.Module`
- `app/libs/api-82.jar` (compileOnly)
- Settings on cold start: the hooked app reads the companion app prefs through `XSharedPreferences`
  (`CompanionSync`, `FeedVideoDownloadHook` download folder); live changes come by broadcast.

## Per file

| File | Uses |
|---|---|
| `ps/reso/instaeclipse/Xposed/AndroidAppHelper.java` | imports only:  |
| `ps/reso/instaeclipse/Xposed/LegacyBackend.java` | XposedBridge.hookMethod x1, XposedBridge.invokeOriginalMethod x1, XposedBridge.log x2, new XC_MethodHook x2, param.getThrowable/setThrowable x9, XC_MethodHook.Unhook x3, XSharedPreferences x2 |
| `ps/reso/instaeclipse/Xposed/Module.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1, XSharedPreferences x3, IXposedHookLoadPackage / LoadPackageParam x4, IXposedHookZygoteInit / StartupParam x3, AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/Xposed/ModuleResources.java` | XModuleResources (module icons/strings) x1, AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/Xposed/XC_MethodHook.java` | XC_MethodHook.Unhook x1 |
| `ps/reso/instaeclipse/mods/ads/AdBlocker.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ads/TrackingLinkDisable.java` | XposedHelpers.findAndHookMethod x6, XposedHelpers.findClass x2, new XC_MethodHook x4 |
| `ps/reso/instaeclipse/mods/core/TerminatedExecutorGuard.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/devops/BuildExpiredPopupHook.java` | XposedBridge.hookMethod x4, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/devops/DevOptionsUnlockHook.java` | XposedBridge.hookMethod x2, XposedHelpers.findAndHookMethod x1, new XC_MethodHook x3 |
| `ps/reso/instaeclipse/mods/devops/config/ConfigManager.java` | imports only: XposedBridge |
| `ps/reso/instaeclipse/mods/extras/AccountLimitHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/extras/AirplaneModeHook.java` | XposedBridge.hookMethod x2, XposedBridge.hookAllMethods x1, XposedHelpers.findClass x2, new XC_MethodHook x3 |
| `ps/reso/instaeclipse/mods/extras/HighResolutionImageHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1, param.getThrowable/setThrowable x1 |
| `ps/reso/instaeclipse/mods/extras/NotificationRegistrationHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/extras/OnboardingPromptHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/extras/ReelsControlsHook.java` | XposedBridge.hookMethod x2, XposedHelpers.findAndHookMethod x1, XposedHelpers.findClass x1, new XC_MethodHook x3 |
| `ps/reso/instaeclipse/mods/extras/SwipeToCameraHook.java` | XposedHelpers.findAndHookMethod x1, XposedHelpers.findClass x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/feed/ExpandCaptionsHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/feed/FeedPhotoZoomHook.java` | XposedHelpers.findAndHookMethod x1, XposedHelpers.callMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/feed/FollowingOnlyFeedHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/feed/HideCommunityNotesHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/feed/HideSuggestedFeedItemsHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/ChatExportHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/EditHistoryHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ghost/GhostChannelMarkAsReadHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/GhostDMMarkAsReadHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1, XModuleResources (module icons/strings) x2 |
| `ps/reso/instaeclipse/mods/ghost/GhostDMSeenHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/GhostEphemeralKeepHook.java` | XposedBridge.hookMethod x8, new XC_MethodHook x4 |
| `ps/reso/instaeclipse/mods/ghost/GhostPermanentViewHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/GhostScreenshotDetectionHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/GhostStorySeenHook.java` | XposedBridge.hookMethod x4, XposedBridge.invokeOriginalMethod x1, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ghost/GhostTypingIndicatorHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/GhostViewOnceHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/HideChatsHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ghost/KeepUnsentMessagesHook.java` | XposedBridge.hookMethod x3, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ghost/ScreenshotPermissionHook.java` | XposedHelpers.findAndHookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ghost/ViewOnceBadgeHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/location/LocationSpoofHook.java` | XposedBridge.hookMethod x10, new XC_MethodHook x8, param.getThrowable/setThrowable x2 |
| `ps/reso/instaeclipse/mods/media/CommentMedia.java` | XposedBridge.hookMethod x1 |
| `ps/reso/instaeclipse/mods/media/FeedVideoDownloadHook.java` | XposedBridge.hookMethod x2, XposedHelpers.findAndHookMethod x2, new XC_MethodHook x3, XSharedPreferences x2 |
| `ps/reso/instaeclipse/mods/media/ForceReelQualityHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/media/InstantSaveHook.java` | XposedBridge.hookMethod x2, XposedHelpers.findClass x2, new XC_MethodHook x2, AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/mods/media/InstantUploadHook.java` | XposedBridge.hookMethod x2, XposedHelpers.findAndHookMethod x1, XposedHelpers.findClass x2, new XC_MethodHook x3, AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/mods/media/MaxUploadQualityHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/media/PlaybackSpeedHook.java` | XposedBridge.hookMethod x7, new XC_MethodHook x7 |
| `ps/reso/instaeclipse/mods/media/PostDownloadContextMenuHook.java` | XposedBridge.hookMethod x5, new XC_MethodHook x3, AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/mods/media/ProfilePicDownloadHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/media/ReelDownloadHook.java` | XposedBridge.hookMethod x6, new XC_MethodHook x4 |
| `ps/reso/instaeclipse/mods/media/ShareLinkDownloadHook.java` | XposedBridge.hookAllConstructors x1, XposedHelpers.findAndHookMethod x2, XposedHelpers.findClass x1, XposedHelpers.getObjectField x2, XposedHelpers.getBooleanField x1, XposedHelpers.callStaticMethod x1, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/media/StoryDownloadHook.java` | XposedBridge.hookMethod x6, XposedBridge.invokeOriginalMethod x1, new XC_MethodHook x6, AndroidAppHelper.currentApplication x11 |
| `ps/reso/instaeclipse/mods/media/VoiceMessageDownloadHook.java` | XposedBridge.hookMethod x6, new XC_MethodHook x6, AndroidAppHelper.currentApplication x4 |
| `ps/reso/instaeclipse/mods/misc/CaptionCopyContextMenuHook.java` | XposedBridge.hookMethod x10, XposedHelpers.findAndHookMethod x2, new XC_MethodHook x8, AndroidAppHelper.currentApplication x2 |
| `ps/reso/instaeclipse/mods/misc/CommentCopyHook.java` | XposedBridge.hookMethod x4, XposedHelpers.findAndHookMethod x2, new XC_MethodHook x4 |
| `ps/reso/instaeclipse/mods/misc/CommentSearchHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/misc/DisableDoubleTapLikeHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/misc/DisableStoryFlippingHook.java` | XposedBridge.hookMethod x2, XposedHelpers.findAndHookMethod x2, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/misc/DisableVideoAutoPlayHook.java` | XposedBridge.hookMethod x1, XposedBridge.hookAllMethods x6, XposedHelpers.get/setAdditionalInstanceField x2, new XC_MethodHook x5 |
| `ps/reso/instaeclipse/mods/misc/DoubleTapExtrasHook.java` | XposedBridge.hookMethod x3, new XC_MethodHook x3 |
| `ps/reso/instaeclipse/mods/misc/ExactTimestampHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/misc/ExternalBrowserHook.java` | XposedBridge.hookAllMethods x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/misc/FollowListExportHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/misc/FollowStatusHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1, AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/mods/misc/LikeUndoHook.java` | XposedBridge.hookMethod x1, XposedBridge.invokeOriginalMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/misc/StoryInfoHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2, XModuleResources (module icons/strings) x1, AndroidAppHelper.currentApplication x2 |
| `ps/reso/instaeclipse/mods/misc/StoryLinksHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2, AndroidAppHelper.currentApplication x2 |
| `ps/reso/instaeclipse/mods/misc/StoryMentionHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2, AndroidAppHelper.currentApplication x2 |
| `ps/reso/instaeclipse/mods/network/IGNetworkInterceptor.java` | XposedBridge.hookMethod x1, XposedHelpers.findAndHookMethod x1, XposedHelpers.getObjectField x1, new XC_MethodHook x1, IXposedHookLoadPackage / LoadPackageParam x1 |
| `ps/reso/instaeclipse/mods/ui/CustomFontHook.java` | XposedBridge.hookMethod x1, XposedHelpers.findAndHookMethod x7, XposedHelpers.getObjectField x1, XposedHelpers.getStaticObjectField x2, XposedHelpers.setStaticObjectField x2, XposedHelpers.callStaticMethod x1, new XC_MethodHook x6 |
| `ps/reso/instaeclipse/mods/ui/DisableRepostHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ui/DistractionFreeUIHook.java` | XposedBridge.hookMethod x1 |
| `ps/reso/instaeclipse/mods/ui/HideHeaderButtonsHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/HideNotesTrayHook.java` | XposedBridge.hookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ui/LockDirectMessagesHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/LockedCollectionsHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/MenuIcons.java` | AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/mods/ui/ProfileFollowsYouHook.java` | XposedBridge.hookMethod x1, XposedHelpers.findAndHookMethod x1, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ui/RemoveMetaAIHook.java` | XposedBridge.hookMethod x6, XposedHelpers.findAndHookMethod x2, new XC_MethodHook x10 |
| `ps/reso/instaeclipse/mods/ui/SavedOnProfileHook.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/ShareSheetCleanupHook.java` | XposedHelpers.findAndHookMethod x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/mods/ui/StartupTabHook.java` | XposedBridge.hookMethod x3, new XC_MethodHook x3 |
| `ps/reso/instaeclipse/mods/ui/StoryViewerSearchHook.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/UIHookManager.java` | XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/theme/IgThemeColorSourceHooks.java` | XposedBridge.hookAllConstructors x1, XposedHelpers.findAndHookMethod x7, XposedHelpers.findAndHookConstructor x1, XposedHelpers.getObjectField x2, new XC_MethodHook x8, param.getThrowable/setThrowable x1 |
| `ps/reso/instaeclipse/mods/ui/theme/IgThemeEngine.java` | AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/mods/ui/theme/IgThemeHook.java` | XposedHelpers.findAndHookMethod x9, XposedHelpers.getObjectField x1, new XC_MethodHook x8, param.getThrowable/setThrowable x2 |
| `ps/reso/instaeclipse/mods/ui/utils/BottomSheetHookUtil.java` | XposedBridge.hookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/utils/LifecycleHookUtil.java` | XposedBridge.hookMethod x1, XposedHelpers.findAndHookMethod x1, new XC_MethodHook x1 |
| `ps/reso/instaeclipse/mods/ui/utils/VibrationUtil.java` | imports only: XposedBridge |
| `ps/reso/instaeclipse/utils/core/CompanionSync.java` | XSharedPreferences x4 |
| `ps/reso/instaeclipse/utils/core/DexKitCache.java` | imports only: XposedBridge |
| `ps/reso/instaeclipse/utils/core/ModuleActivityLauncher.java` | imports only: XposedBridge |
| `ps/reso/instaeclipse/utils/dialog/DialogUtils.java` | XModuleResources (module icons/strings) x3 |
| `ps/reso/instaeclipse/utils/ghost/CurrentThreadTracker.java` | XposedHelpers.findAndHookMethod x2, XposedHelpers.getObjectField x2, new XC_MethodHook x2 |
| `ps/reso/instaeclipse/utils/ghost/HiddenThreads.java` | AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/utils/ghost/ReadReceiptWhitelist.java` | AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/utils/ghost/ThreadNames.java` | AndroidAppHelper.currentApplication x3 |
| `ps/reso/instaeclipse/utils/ghost/UnsentLog.java` | AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/utils/i18n/I18n.java` | XModuleResources (module icons/strings) x3 |
| `ps/reso/instaeclipse/utils/log/ModuleLog.java` | XposedBridge.log x2 |
| `ps/reso/instaeclipse/utils/media/StoryCache.java` | AndroidAppHelper.currentApplication x1 |
| `ps/reso/instaeclipse/utils/toast/CustomToast.java` | imports only: XposedBridge |
| `ps/reso/instaeclipse/utils/toast/IeToast.java` | XModuleResources (module icons/strings) x1 |
