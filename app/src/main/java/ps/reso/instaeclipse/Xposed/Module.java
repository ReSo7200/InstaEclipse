package ps.reso.instaeclipse.Xposed;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import org.luckypray.dexkit.DexKitBridge;

import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.mods.ads.AdBlocker;
import ps.reso.instaeclipse.mods.feed.FeedPhotoZoomHook;
import ps.reso.instaeclipse.mods.location.LocationSpoofHook;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.mods.media.ForceReelQualityHook;
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.ads.TrackingLinkDisable;
import ps.reso.instaeclipse.mods.devops.BuildExpiredPopupHook;
import ps.reso.instaeclipse.mods.devops.DevOptionsUnlockHook;
import ps.reso.instaeclipse.mods.ghost.GhostChannelMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMMarkAsReadHook;
import ps.reso.instaeclipse.mods.ghost.GhostDMSeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostEphemeralKeepHook;
import ps.reso.instaeclipse.mods.ghost.GhostPermanentViewHook;
import ps.reso.instaeclipse.mods.ghost.ViewOnceBadgeHook;
import ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook;
import ps.reso.instaeclipse.mods.ghost.GhostScreenshotDetectionHook;
import ps.reso.instaeclipse.mods.ghost.GhostStorySeenHook;
import ps.reso.instaeclipse.mods.ghost.GhostTypingIndicatorHook;
import ps.reso.instaeclipse.mods.ghost.GhostViewOnceHook;
import ps.reso.instaeclipse.mods.ghost.ScreenshotPermissionHook;
import ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook;
import ps.reso.instaeclipse.mods.media.PostDownloadContextMenuHook;
import ps.reso.instaeclipse.mods.media.ProfilePicDownloadHook;
import ps.reso.instaeclipse.mods.media.ReelDownloadHook;
import ps.reso.instaeclipse.mods.media.StoryDownloadHook;
import ps.reso.instaeclipse.mods.misc.CommentCopyHook;
import ps.reso.instaeclipse.mods.misc.CaptionCopyContextMenuHook;
import ps.reso.instaeclipse.mods.misc.DisableDoubleTapLikeHook;
import ps.reso.instaeclipse.mods.misc.DisableStoryFlippingHook;
import ps.reso.instaeclipse.mods.misc.DisableVideoAutoPlayHook;
import ps.reso.instaeclipse.mods.misc.StoryMentionHook;
import ps.reso.instaeclipse.mods.network.IGNetworkInterceptor;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeHook;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.log.ModuleLog;


@SuppressLint("UnsafeDynamicallyLoadedCode")
public class Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    // List of supported Instagram package names (maintained in CommonUtils)
    private static final List<String> SUPPORTED_PACKAGES = CommonUtils.SUPPORTED_PACKAGES;
    public static DexKitBridge dexKitBridge;
    public static ClassLoader hostClassLoader;
    public static String moduleSourceDir;
    private static String moduleLibDir;

    // for dev usage
    /*
    public static void showToast(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(AndroidAppHelper.currentApplication().getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
    */

    @Override
    public void initZygote(StartupParam startupParam) {
        moduleSourceDir = startupParam.modulePath;

        String abi = Build.SUPPORTED_ABIS[0];
        String abiFolder;
        if (abi.equalsIgnoreCase("arm64-v8a")) abiFolder = "arm64";
        else if (abi.equalsIgnoreCase("armeabi-v7a") || abi.equalsIgnoreCase("armeabi") || abi.equalsIgnoreCase("armv8i"))
            abiFolder = "arm";
        else if (abi.equalsIgnoreCase("x86")) abiFolder = "x86";
        else if (abi.equalsIgnoreCase("x86_64")) abiFolder = "x86_64";
        else abiFolder = abi;

        moduleLibDir = moduleSourceDir.substring(0, moduleSourceDir.lastIndexOf("/")) + "/lib/" + abiFolder;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Ensure preferences are loaded


        // Hook into Instagram and its clones
        // Debug kill switch: `adb shell setprop debug.instaeclipse.off 1` loads the module but
        // installs no hooks (isolates crashes to the framework vs. this module).
        if (SUPPORTED_PACKAGES.contains(lpparam.packageName) && "1".equals(debugProp("debug.instaeclipse.off"))) {
            android.util.Log.i("InstaEclipse", "debug.instaeclipse.off=1 -> no hooks installed");
            return;
        }
        if (SUPPORTED_PACKAGES.contains(lpparam.packageName)) {
            // Only the main app process: Instagram's helper processes (":fbns" push, browser,
            // video …) never show our features, so hooking them only costs startup time and memory.
            if (lpparam.processName != null && !lpparam.processName.equals(lpparam.packageName)) {
                return;
            }
            try {
                if (dexKitBridge == null) {
                    // Load the .so file from your module (if not already loaded)
                    System.load(moduleLibDir + "/libdexkit.so");
                    // ModuleLog.line("libdexkit.so loaded successfully.");

                    // Initialize DexKitBridge with the target app's APK
                    dexKitBridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
                    // ModuleLog.line("DexKitBridge initialized with target APK: " + lpparam.appInfo.sourceDir);
                }

                // Use the target app's ClassLoader
                hostClassLoader = lpparam.classLoader;

                // Call the method to hook the target app
                hookInstagram(lpparam);

            } catch (Exception e) {
                ModuleLog.line("(InstaEclipse): Failed to initialize DexKitBridge for " + lpparam.packageName + ": " + e.getMessage());
            }
        }
    }

    /** DexKitCache key set once every feature hook has been installed for this IG version. */
    private static final String CACHE_COMPLETE_KEY = "InstallComplete";

    private static java.util.Set<String> skipped;

    /** Debug bisect switch: `setprop debug.instaeclipse.skip A,B` skips those hook installs. */
    static boolean skip(String name) {
        if (skipped == null) {
            skipped = new java.util.HashSet<>();
            for (String prop : new String[]{"skip", "skip2", "skip3", "skip4", "skip5", "skip6"}) // props max 91 chars each
                for (String k : debugProp("debug.instaeclipse." + prop).split(",")) if (!k.trim().isEmpty()) skipped.add(k.trim());
            if (!skipped.isEmpty()) ModuleLog.line("(InstaEclipse) debug skip: " + skipped);
        }
        return skipped.contains(name);
    }

    /** Reads a system property (debug switches); empty string when unavailable. */
    static String debugProp(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void hookInstagram(XC_LoadPackage.LoadPackageParam lpparam) {

        try {


            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    // Install CommentCopyButtonHook BEFORE Instagram's Application.attach() runs
                    // so we catch any ViewBinding pre-inflation that happens during attach()
                    Context context = (Context) param.args[0];
                    SettingsManager.init(context);
                    SettingsManager.loadAllFlags(context);

                    // Init DexKit cache — checks IG version to decide if saved descriptors are valid.
                    // Must run before any hook that calls DexKitCache.isCacheValid().
                    try {
                        android.content.pm.PackageInfo pi =
                                context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                        long vc = pi.getLongVersionCode();
                        DexKitCache.init(context, String.valueOf(vc));
                    } catch (Throwable e) {
                        ModuleLog.line("(DexKitCache) ❌ init failed: " + e.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);

                    // Setup context, preferences
                    Context context = (Context) param.args[0];
                    SettingsManager.init(context);
                    // Settings changed in the companion while Instagram wasn't running.
                    ps.reso.instaeclipse.utils.core.CompanionSync.pull(context);
                    SettingsManager.loadAllFlags(context);
                    // ...and the other way: bring the companion's copy up to date with changes
                    // made in this menu (newer side wins per key, see PrefsMerge).
                    ps.reso.instaeclipse.utils.core.CompanionSync.pushAsync(context);
                    // ...and while it sat frozen in the background: check again on every resume.
                    if (param.thisObject instanceof android.app.Application) {
                        ((android.app.Application) param.thisObject).registerActivityLifecycleCallbacks(
                                new android.app.Application.ActivityLifecycleCallbacks() {
                                    @Override public void onActivityResumed(android.app.Activity a) {
                                        ps.reso.instaeclipse.utils.core.CompanionSync.pullAsync(a);
                                    }
                                    @Override public void onActivityCreated(android.app.Activity a, Bundle b) {}
                                    @Override public void onActivityStarted(android.app.Activity a) {}
                                    @Override public void onActivityPaused(android.app.Activity a) {}
                                    @Override public void onActivityStopped(android.app.Activity a) {}
                                    @Override public void onActivitySaveInstanceState(android.app.Activity a, Bundle b) {}
                                    @Override public void onActivityDestroyed(android.app.Activity a) {}
                                });
                    }

                    // In-app log viewer: every ModuleLog.line(...) call across the hook codebase
                    // appends to this buffer, which the companion app can read via IPC.
                    Logging.init(context, "instaeclipse_module.log");
                    // Mirror the buffer into a file owned by the companion app (LogMirrorProvider)
                    // so its Logs tab works even when Instagram is backgrounded or killed.
                    Logging.enableMirror(context);

                    // Pull downloader path from companion app's cache so it's available even
                    // when Instagram was started without ever receiving the sync broadcast.
                    try {
                        XSharedPreferences cp = new XSharedPreferences(CommonUtils.MY_PACKAGE_NAME, "instaeclipse_cache");
                        cp.reload();
                        String path = cp.getString("downloaderCustomPath", "");
                        String uri  = cp.getString("downloaderCustomUri",  "");
                        if (!path.isEmpty()) FeatureFlags.downloaderCustomPath = path;
                        if (!uri.isEmpty())  FeatureFlags.downloaderCustomUri  = uri;
                    } catch (Throwable ignored) {
                    }

                    FeatureManager.refreshFeatureStatus(); // Update internal feature states

                    // Activate the LSPosed Sync Bridge to listen to FeaturesFragment updates
                    registerSyncReceiver(context);

                    try {
                        UIHookManager.registerConfigImportReceiver(context);
                    } catch (Throwable e) {
                        ModuleLog.line("(InstaEclipse | ImportReceiver): ❌ " + e.getMessage());
                    }
                    try {
                        UIHookManager.registerSettingsRestoreReceiver(context);
                    } catch (Throwable e) {
                        ModuleLog.line("(InstaEclipse | RestoreReceiver): ❌ " + e.getMessage());
                    }
                    final Runnable installFeatureHooks = () -> {
                    UIHookManager instagramUI = new UIHookManager();
                    if (!skip("instagramUI")) instagramUI.mainActivity(hostClassLoader);

                    IGNetworkInterceptor interceptor = new IGNetworkInterceptor();

                    // --- Feature Hooks ---

                    // Developer Options
                    try {
                        if (!skip("DevOptionsUnlockHook")) new DevOptionsUnlockHook().handleDevOptions(dexKitBridge);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | DevOptions): ❌ Failed to hook");
                    }

                    // Ghost Mode
                    try {
                        if (!skip("GhostDMSeenHook")) new GhostDMSeenHook().handleSeenBlock(dexKitBridge); // DM Seen
                        if (!skip("GhostDMMarkAsReadHook")) new GhostDMMarkAsReadHook(moduleSourceDir).install(lpparam.classLoader); // Mark as Read Button
                        if (!skip("GhostChannelMarkAsReadHook")) new GhostChannelMarkAsReadHook().install(lpparam.classLoader); // Channel Mark as Read Button
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostSeen): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("GhostTypingIndicatorHook")) new GhostTypingIndicatorHook().handleTypingBlock(dexKitBridge); // DM Typing
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostTyping): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("GhostScreenshotDetectionHook")) new GhostScreenshotDetectionHook().handleScreenshotBlock(dexKitBridge); // Screenshot
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostScreenshot): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("ScreenshotPermissionHook")) new ScreenshotPermissionHook().install(lpparam.classLoader); // Allow Screenshots
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ScreenshotPermission): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("GhostViewOnceHook")) new GhostViewOnceHook().handleViewOnceBlock(dexKitBridge); // View Once
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostViewOnce): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("GhostStorySeenHook")) new GhostStorySeenHook().handleStorySeenBlock(dexKitBridge); // Story Seen
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostStorySeen): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("KeepUnsentMessagesHook")) new KeepUnsentMessagesHook().install(dexKitBridge, lpparam.classLoader); // Keep Unsent
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | KeepUnsent): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("UnsentThreadButtonHook")) new ps.reso.instaeclipse.mods.ghost.UnsentThreadButtonHook().install(lpparam.classLoader); // per-thread unsent button
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | UnsentBtn): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("HideChatsHook")) new ps.reso.instaeclipse.mods.ghost.HideChatsHook().install(dexKitBridge, lpparam.classLoader); // Hide Specific Chats
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | HideChats): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("CustomFontHook")) new ps.reso.instaeclipse.mods.ui.CustomFontHook().install(lpparam.classLoader); // Custom UI font
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | CustomFont): ❌ Failed to hook");
                    }

                    try {
                        ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook metaAi = new ps.reso.instaeclipse.mods.ui.RemoveMetaAIHook();
                        if (!skip("metaAi")) metaAi.install(lpparam.classLoader);              // composer/search XML layouts
                        if (!skip("metaAi")) metaAi.installReels(dexKitBridge, lpparam.classLoader); // reels Litho unit (#179)
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | RemoveMetaAI): ❌ Failed to hook");
                    }

                    // Disable Repost (feed + reels) — UI/action level; network drop is ineffective
                    try {
                        if (!skip("DisableRepostHook")) new ps.reso.instaeclipse.mods.ui.DisableRepostHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | DisableRepost): ❌ Failed to hook");
                    }

                    try {
                        if (!skip("LockDirectMessagesHook")) new ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook().install(lpparam.classLoader); // Lock DMs (#182)
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | LockDMs): ❌ Failed to hook");
                    }

                    // Hide in-feed widget units (suggested users panels, surveys, carousels, etc.)
                    try {
                        if (!skip("HideSuggestedFeedItemsHook")) new HideSuggestedFeedItemsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | HideSuggested): ❌ Failed to hook");
                    }

                    // Ads Blocker
                    try {
                        if (!skip("AdBlocker")) new AdBlocker().disableSponsoredContent(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | AdBlocker): ❌ Failed to hook");
                    }

                    // tracking link disable
                    try {
                        if (!skip("TrackingLinkDisable")) new TrackingLinkDisable().disableTrackingLinks(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | TrackingLinkDisable): ❌ Failed to hook");
                    }

                    // Exact timestamps instead of "3h ago" (#232)
                    try {
                        if (!skip("ExactTimestampHook")) new ps.reso.instaeclipse.mods.misc.ExactTimestampHook().install(dexKitBridge);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ExactTime): ❌ Failed to hook");
                    }

                    // Auto-expand feed captions (#246)
                    try {
                        if (!skip("ExpandCaptionsHook")) new ps.reso.instaeclipse.mods.feed.ExpandCaptionsHook().install(dexKitBridge);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ExpandCaptions): ❌ Failed to hook");
                    }

                    // Hide community notes (#259)
                    try {
                        if (!skip("HideCommunityNotesHook")) new ps.reso.instaeclipse.mods.feed.HideCommunityNotesHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | CommunityNotes): ❌ Failed to hook");
                    }

                    // Extras: tapping a reel mutes instead of pausing
                    try {
                        if (!skip("ReelsControlsHook")) new ps.reso.instaeclipse.mods.extras.ReelsControlsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | Reels): ❌ Failed to hook: " + t);
                    }

                    // Extras: lift the 5-account limit
                    try {
                        if (!skip("AccountLimitHook")) new ps.reso.instaeclipse.mods.extras.AccountLimitHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | UnlimitedAccounts): ❌ Failed to hook: " + t);
                    }

                    // Extras: keep Instagram's realtime connection down (appear offline)
                    try {
                        if (!skip("AirplaneModeHook")) new ps.reso.instaeclipse.mods.extras.AirplaneModeHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | Airplane): ❌ Failed to hook: " + t);
                    }

                    // Extras: pick the largest image candidate
                    try {
                        if (!skip("HighResolutionImageHook")) new ps.reso.instaeclipse.mods.extras.HighResolutionImageHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | HighResImages): ❌ Failed to hook: " + t);
                    }

                    // Extras: skip contact/location onboarding pages
                    try {
                        if (!skip("OnboardingPromptHook")) new ps.reso.instaeclipse.mods.extras.OnboardingPromptHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | Onboarding): ❌ Failed to hook: " + t);
                    }

                    // Extras: add FLAG_IMMUTABLE to IG's legacy push registration intent
                    try {
                        if (!skip("NotificationRegistrationHook")) new ps.reso.instaeclipse.mods.extras.NotificationRegistrationHook().install();
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | NotificationFix): ❌ Failed to hook: " + t);
                    }

                    // Startup tab
                    try {
                        if (!skip("StartupTabHook")) new ps.reso.instaeclipse.mods.ui.StartupTabHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | StartupTab): ❌ Failed to hook: " + t);
                    }

                    // Extras: no swipe to camera from the feed
                    try {
                        if (!skip("SwipeToCameraHook")) new ps.reso.instaeclipse.mods.extras.SwipeToCameraHook().install(hostClassLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | SwipeCamera): ❌ Failed to hook: " + t);
                    }

                    // home feed shows only accounts you follow
                    try {
                        if (!skip("FollowingOnlyFeedHook")) new ps.reso.instaeclipse.mods.feed.FollowingOnlyFeedHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | FollowingOnlyFeed): ❌ Failed to hook");
                    }

                    // "Follows you" label on profiles (#239)
                    try {
                        if (!skip("ProfileFollowsYouHook")) new ps.reso.instaeclipse.mods.ui.ProfileFollowsYouHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | FollowsYou): ❌ Failed to hook");
                    }

                    // Playback speed for feed videos + forced reel seek bar (#247)
                    try {
                        if (!skip("PlaybackSpeedHook")) new ps.reso.instaeclipse.mods.media.PlaybackSpeedHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | PlaybackSpeed): ❌ Failed to hook");
                    }

                    // Hide the DM Notes tray (#241)
                    try {
                        if (!skip("HideNotesTrayHook")) new ps.reso.instaeclipse.mods.ui.HideNotesTrayHook().install(dexKitBridge);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | NotesTray): ❌ Failed to hook");
                    }

                    // Download DM voice messages (#223)
                    try {
                        if (!skip("VoiceMessageDownloadHook")) new ps.reso.instaeclipse.mods.media.VoiceMessageDownloadHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|VoiceDL) ❌ Failed to hook");
                    }

                    // Keep DM edit history (#243)
                    try {
                        if (!skip("EditHistoryHook")) new ps.reso.instaeclipse.mods.ghost.EditHistoryHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|EditHistory) ❌ Failed to hook");
                    }

                    // Jump to the first message of a DM (#244)
                    try {
                        if (!skip("JumpToFirstMessageHook")) new ps.reso.instaeclipse.mods.ghost.JumpToFirstMessageHook().install();
                        if (!skip("ReadReceiptChatHook")) new ps.reso.instaeclipse.mods.ghost.ReadReceiptChatHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|JumpFirst) ❌ Failed to hook");
                    }

                    // Search your story viewers (Plus-style)
                    try {
                        if (!skip("StoryViewerSearchHook")) new ps.reso.instaeclipse.mods.ui.StoryViewerSearchHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|ViewerSearch) ❌ Failed to hook");
                    }

                    // Search inside the comments sheet (#230)
                    try {
                        if (!skip("CommentSearchHook")) new ps.reso.instaeclipse.mods.misc.CommentSearchHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|CommentSearch) ❌ Failed to hook");
                    }

                    // Export a DM chat to HTML (#225)
                    try {
                        if (!skip("ChatExportHook")) new ps.reso.instaeclipse.mods.ghost.ChatExportHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|ChatExport) ❌ Failed to hook");
                    }

                    // Export followers / following to CSV (#224)
                    try {
                        if (!skip("FollowListExportHook")) new ps.reso.instaeclipse.mods.misc.FollowListExportHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|FollowExport) ❌ Failed to hook");
                    }

                    // Like undo window (#235) — after HideSuggestedFeedItemsHook (has_liked getter)
                    try {
                        if (!skip("LikeUndoHook")) new ps.reso.instaeclipse.mods.misc.LikeUndoHook().install(dexKitBridge);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | LikeUndo): ❌ Failed to hook");
                    }

                    // Hide "Create group" on the DM share sheet (#240)
                    try {
                        if (!skip("ShareSheetCleanupHook")) new ps.reso.instaeclipse.mods.ui.ShareSheetCleanupHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ShareSheet): ❌ Failed to hook");
                    }

                    // Open links in the external browser (#229)
                    try {
                        if (!skip("ExternalBrowserHook")) new ps.reso.instaeclipse.mods.misc.ExternalBrowserHook().install();
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ExternalBrowser): ❌ Failed to hook");
                    }

                    // Miscellaneous
                    try {
                        if (!skip("DisableStoryFlippingHook")) new DisableStoryFlippingHook().handleStoryFlippingDisable(dexKitBridge); // Story Flipping
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | StoryFlipping): ❌ Failed to hook");
                    }

                    // Story Mentions
                    try {
                        if (!skip("StoryMentionHook")) new StoryMentionHook().install(dexKitBridge, lpparam.classLoader);
                        if (!skip("StoryLinksHook")) new ps.reso.instaeclipse.mods.misc.StoryLinksHook().install(dexKitBridge, lpparam.classLoader);
                        if (!skip("StoryInfoHook")) new ps.reso.instaeclipse.mods.misc.StoryInfoHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | StoryMentions): ❌ Failed to hook");
                    }

                    // Comment Copy
                    try {
                        if (!skip("CommentCopyHook")) new CommentCopyHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | CopyComment): ❌ Failed to hook");
                    }

                    // Caption Copy
                    try {
                        if (!skip("CaptionCopyContextMenuHook")) new CaptionCopyContextMenuHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | Caption): ❌ Failed to hook");
                    }

                    // Disable Double Tap to Like
                    try {
                        if (!skip("DisableDoubleTapLikeHook")) new DisableDoubleTapLikeHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | DoubleTapLike): ❌ Failed to hook");
                    }
                    // ...and in comments and DMs
                    try {
                        if (!skip("DoubleTapExtrasHook")) new ps.reso.instaeclipse.mods.misc.DoubleTapExtrasHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | DoubleTapLike): ❌ comments/DMs: " + t);
                    }

                    // Photo Zoom (long-press)
                    try {
                        if (!skip("FeedPhotoZoomHook")) new FeedPhotoZoomHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | PhotoZoom): ❌ Failed to hook");
                    }

                    // Location Spoof
                    try {
                        if (!skip("LocationSpoofHook")) new LocationSpoofHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | SpoofLocation): ❌ Failed to hook");
                    }

                    // Custom Theme
                    try {
                        if (!skip("IgThemeHook")) new IgThemeHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | Theme): ❌ Failed to hook");
                    }

                    // Force Reel Quality
                    try {
                        if (!skip("ForceReelQualityHook")) new ForceReelQualityHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ Failed to hook");
                    }

                    // Force "Upload at highest quality" always on
                    try {
                        if (!skip("MaxUploadQualityHook")) new ps.reso.instaeclipse.mods.media.MaxUploadQualityHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|MaxUploadQuality) ❌ Failed to hook");
                    }

                    try {
                        if (!skip("DisableVideoAutoPlayHook")) new DisableVideoAutoPlayHook().handleAutoPlayDisable(dexKitBridge); // Video Autoplay
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | AutoPlayDisable): ❌ Failed to hook");
                    }

                    // Build Expired Popup
                    try {
                        if (!skip("BuildExpiredPopupHook")) new BuildExpiredPopupHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | BuildExpired): ❌ Failed to hook");
                    }

                    // Media Download (feed)
                    try {
                        if (!skip("FeedVideoDownloadHook")) new FeedVideoDownloadHook().install(lpparam.classLoader);
                        if (!skip("FeedVideoDownloadHook")) FeedVideoDownloadHook.installVideoUrlCaptureHook(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | MediaDownload): ❌ Failed to hook");
                    }

                    // Post Download — three-dots menu (replaces floating button + long-press)
                    try {
                        if (!skip("PostDownloadContextMenuHook")) new PostDownloadContextMenuHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | PostDownload): ❌ Failed to hook");
                    }

                    // Save Instants (#184) — long-press a received Instant (quicksnap) to save it
                    try {
                        if (!skip("InstantSaveHook")) new ps.reso.instaeclipse.mods.media.InstantSaveHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | InstantSave): ❌ Failed to hook");
                    }

                    // Upload Instants from gallery (#199) — swap gallery bitmap into quicksnap send
                    try {
                        if (!skip("InstantUploadHook")) new ps.reso.instaeclipse.mods.media.InstantUploadHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | InstantUpload): ❌ Failed to hook");
                    }

                    // Keep Ephemeral Messages
                    try {
                        if (!skip("GhostEphemeralKeepHook")) new GhostEphemeralKeepHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | EphemeralHook): ❌ Failed to hook");
                    }

                    // Permanent View Mode (view-once / view-twice → permanent)
                    try {
                        if (!skip("GhostPermanentViewHook")) new GhostPermanentViewHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ViewOnceMedia): ❌ Failed to hook");
                    }

                    // Restore IG's native view-once/twice corner icon when Permanent View is on
                    try {
                        if (!skip("ViewOnceBadgeHook")) new ViewOnceBadgeHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | VOBadge): ❌ Failed to hook");
                    }

                    // Locked saved collections (#219)
                    try {
                        if (!skip("LockedCollectionsHook")) new ps.reso.instaeclipse.mods.ui.LockedCollectionsHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|LockedColl) ❌ Failed to hook");
                    }

                    // Saved button on your own profile (#237)
                    try {
                        if (!skip("SavedOnProfileHook")) new ps.reso.instaeclipse.mods.ui.SavedOnProfileHook().install(dexKitBridge, hostClassLoader);
                        if (!skip("HideHeaderButtonsHook")) new ps.reso.instaeclipse.mods.ui.HideHeaderButtonsHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|SavedProfile) ❌ Failed to hook");
                    }

                    // Download a post/reel link shared to InstaEclipse from another app (#236)
                    try {
                        if (!skip("ShareLinkDownloadHook")) new ps.reso.instaeclipse.mods.media.ShareLinkDownloadHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(IE|ShareDL) ❌ Failed to hook");
                    }

                    // Story Download
                    try {
                        if (!skip("StoryDownloadHook")) new StoryDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | StoryDownload): ❌ Failed to hook");
                    }

                    // Reel Download
                    try {
                        if (!skip("ReelDownloadHook")) new ReelDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ReelDownload): ❌ Failed to hook");
                    }

                    // Profile Picture Download
                    try {
                        if (!skip("ProfilePicDownloadHook")) ProfilePicDownloadHook.install();
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ProfileDownload): ❌ Failed to hook");
                    }

                    // Network Interceptor
                    try {
                        if (!skip("interceptor")) interceptor.handleInterceptor(lpparam);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | Interceptor): ❌ Failed to hook");
                    }

                    // Crash guard: drop tasks rejected by already-shut-down executors (carousel/
                    // realtime teardown race on IG 446+/447.0.0.39+) instead of letting AbortPolicy
                    // throw and hard-crash the app.
                    try {
                        if (!skip("TerminatedExecutorGuard")) new ps.reso.instaeclipse.mods.core.TerminatedExecutorGuard().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ExecGuard): ❌ Failed to hook");
                    }

                    // Every lookup is cached now: later launches install inline.
                    DexKitCache.saveString(CACHE_COMPLETE_KEY, "1");
                    };

                    if (DexKitCache.isCacheValid() && "1".equals(DexKitCache.loadString(CACHE_COMPLETE_KEY))) {
                        // Warm cache: installing is quick (reflection only), so hooks are in place
                        // before Instagram's own startup code runs.
                        installFeatureHooks.run();
                    } else {
                        // First launch for this Instagram version (or an interrupted one): DexKit has
                        // to scan the APK, which takes many seconds. Inside Application.attach that
                        // risks Android killing the app for not finishing startup, before the cache
                        // is even written. Resolve in the background instead; features turn on a few
                        // seconds after launch, and from the next launch on everything is cached.
                        ModuleLog.line("(InstaEclipse): first launch for this Instagram version — installing hooks in background");
                        Thread t = new Thread(() -> {
                            long start = android.os.SystemClock.elapsedRealtime();
                            installFeatureHooks.run();
                            ModuleLog.line("(InstaEclipse): background hook installation took "
                                    + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
                        }, "InstaEclipse-Init");
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        t.start();
                    }
                }

            });

        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse): Failed to hook " + lpparam.packageName + ": " + e.getMessage());
        }
    }

    /**
     * Injects a dynamic receiver into Instagram to listen for settings changes
     * sent from the InstaEclipse companion app (FeaturesFragment staging system).
     */
    private void registerSyncReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF".equals(action)) {
                    String key = intent.getStringExtra("key");
                    ps.reso.instaeclipse.utils.core.CompanionSync.markApplied(ctx, key, intent.getLongExtra("ts", 0));
                    boolean value = intent.getBooleanExtra("value", false);

                    ModuleLog.line("(InstaEclipse) Sync: Updating " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putBoolean(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    if ("airplaneMode".equals(key)) ps.reso.instaeclipse.mods.extras.AirplaneModeHook.apply();
                    FeatureManager.refreshFeatureStatus();
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING".equals(action)) {
                    String key = intent.getStringExtra("key");
                    ps.reso.instaeclipse.utils.core.CompanionSync.markApplied(ctx, key, intent.getLongExtra("ts", 0));
                    String value = intent.getStringExtra("value");

                    ModuleLog.line("(InstaEclipse) Sync: Updating string pref " + key);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putString(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT".equals(action)) {
                    String key = intent.getStringExtra("key");
                    ps.reso.instaeclipse.utils.core.CompanionSync.markApplied(ctx, key, intent.getLongExtra("ts", 0));
                    int value = intent.getIntExtra("value", 0);

                    ModuleLog.line("(InstaEclipse) Sync: Updating int pref " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putInt(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    FeatureManager.refreshFeatureStatus();
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if (CommonUtils.ACTION_REQUEST_LOGS.equals(action)) {
                    // Re-attach/rewrite the companion-side mirror file first (covers the companion
                    // having cleared its data), then reply; the companion re-reads the file on
                    // reply and only uses the inline text as a fallback.
                    Logging.refreshMirror(ctx, () -> {
                        try {
                            Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                            reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                            // Mirror active -> companion reads the file; skip the big (binder-limited) extra.
                            reply.putExtra(CommonUtils.EXTRA_LOG_TEXT, Logging.isMirrorActive() ? "" : Logging.getSnapshotForIpc());
                            reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                            ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ctx.sendBroadcast(reply);
                        } catch (Throwable t) {
                            Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                            reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                            reply.putExtra(CommonUtils.EXTRA_LOG_ERROR, String.valueOf(t.getMessage()));
                            reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                            ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ctx.sendBroadcast(reply);
                        }
                    });

                } else if (CommonUtils.ACTION_CLEAR_LOGS.equals(action)) {
                    Logging.clear();

                } else if ("ps.reso.instaeclipse.ACTION_REQUEST_PREFS".equals(action)) {
                    ModuleLog.line("(InstaEclipse) Sync: Companion app requested current preferences.");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_PREFS");
                    reply.setPackage("ps.reso.instaeclipse");

                    Bundle bundle = new Bundle();
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        // The lock passcode hash and its salt never leave Instagram's process.
                        if ("lockDirectPasscode".equals(entry.getKey()) || "lockDirectSalt".equals(entry.getKey())) continue;
                        // Change stamps, so the companion keeps its own newer changes (PrefsMerge).
                        if (ps.reso.instaeclipse.utils.core.PrefsMerge.isSharedStamp(entry.getKey(), entry.getValue())) {
                            bundle.putLong(entry.getKey(), (Long) entry.getValue());
                        } else if (entry.getValue() instanceof Boolean) {
                            bundle.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        } else if (entry.getValue() instanceof String) {
                            bundle.putString(entry.getKey(), (String) entry.getValue());
                        } else if (entry.getValue() instanceof Integer) {
                            bundle.putInt(entry.getKey(), (Integer) entry.getValue());
                        }
                    }
                    reply.putExtras(bundle);
                    ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ctx.sendBroadcast(reply);

                } else if ("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG".equals(action)) {
                    ModuleLog.line("(InstaEclipse) Sync: Companion app requested Dev Config export.");
                    try {
                        java.io.File source = new java.io.File(ctx.getFilesDir(), "mobileconfig/mc_overrides.json");
                        if (!source.exists()) {
                            ModuleLog.line("(InstaEclipse) Export: mc_overrides.json not found.");
                            Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                            reply.setPackage("ps.reso.instaeclipse");
                            reply.putExtra("error", "mc_overrides.json not found.");
                            ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ctx.sendBroadcast(reply);
                            return;
                        }
                        StringBuilder sb = new StringBuilder();
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(source))) {
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                        }
                        Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
                        reply.setPackage("ps.reso.instaeclipse");
                        reply.putExtra("json_content", sb.toString().trim());
                        ps.reso.instaeclipse.utils.core.IpcSecurity.echoNonce(intent, reply);
                            ctx.sendBroadcast(reply);
                        ModuleLog.line("(InstaEclipse) Export: config reply sent to companion.");
                    } catch (Exception e) {
                        ModuleLog.line("(InstaEclipse) Export: failed: " + e.getMessage());
                    }

                } else if ("ps.reso.instaeclipse.ACTION_BACKUP_SETTINGS".equals(action)) {
                    ModuleLog.line("(InstaEclipse) Sync: Companion app requested Settings backup.");
                    try {
                        String json = ps.reso.instaeclipse.utils.backup.SettingsBackupManager.toJson();
                        Intent exportIntent = new Intent();
                        exportIntent.setComponent(new android.content.ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                        exportIntent.putExtra("json_content", json);
                        exportIntent.putExtra("file_name", "instaeclipse_settings.json");
                        exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(exportIntent);
                    } catch (Exception e) {
                        ModuleLog.line("(InstaEclipse) Failed to create backup: " + e.getMessage());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
        filter.addAction(CommonUtils.ACTION_REQUEST_LOGS);
        filter.addAction(CommonUtils.ACTION_CLEAR_LOGS);
        filter.addAction("ps.reso.instaeclipse.ACTION_REQUEST_PREFS");
        filter.addAction("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");
        filter.addAction("ps.reso.instaeclipse.ACTION_BACKUP_SETTINGS");

        // Only the companion (holding the signature permission) can reach this receiver.
        ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver, filter);
    }
}
