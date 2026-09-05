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
import ps.reso.instaeclipse.mods.ghost.GhostReplayLimitHook;
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
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(AndroidAppHelper.currentApplication().getApplicationContext(), text, Toast.LENGTH_LONG).show());
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
        if (!SUPPORTED_PACKAGES.contains(lpparam.packageName)
                || !lpparam.packageName.equals(lpparam.processName)) {
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

    private void hookInstagram(XC_LoadPackage.LoadPackageParam lpparam) {

        try {


            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
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
                protected void afterHookedMethod(MethodHookParam param) {

                    // Setup context, preferences
                    Context context = (Context) param.args[0];
                    SettingsManager.init(context);
                    SettingsManager.loadAllFlags(context);

                    // In-app log viewer: every ModuleLog.line(...) call across the hook codebase
                    // appends to this buffer, which the companion app can read via IPC.
                    Logging.init(context, "instaeclipse_module.log");

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
                    UIHookManager instagramUI = new UIHookManager();
                    instagramUI.mainActivity(hostClassLoader);

                    IGNetworkInterceptor interceptor = new IGNetworkInterceptor();

                    // --- Feature Hooks ---

                    // Developer Options
                    try {
                        new DevOptionsUnlockHook().handleDevOptions(dexKitBridge);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | DevOptions): ❌ Failed to hook");
                    }

                    // Ghost Mode
                    try {
                        new GhostDMSeenHook().handleSeenBlock(dexKitBridge); // DM Seen
                        new GhostDMMarkAsReadHook(moduleSourceDir).install(lpparam.classLoader); // Mark as Read Button
                        new GhostChannelMarkAsReadHook().install(lpparam.classLoader); // Channel Mark as Read Button
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostSeen): ❌ Failed to hook");
                    }

                    try {
                        new GhostTypingIndicatorHook().handleTypingBlock(dexKitBridge); // DM Typing
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostTyping): ❌ Failed to hook");
                    }

                    try {
                        new GhostScreenshotDetectionHook().handleScreenshotBlock(dexKitBridge); // Screenshot
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostScreenshot): ❌ Failed to hook");
                    }

                    try {
                        new ScreenshotPermissionHook().install(lpparam.classLoader); // Allow Screenshots
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ScreenshotPermission): ❌ Failed to hook");
                    }

                    try {
                        new GhostViewOnceHook().handleViewOnceBlock(dexKitBridge); // View Once
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostViewOnce): ❌ Failed to hook");
                    }

                    try {
                        new GhostReplayLimitHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | UnlimitedReplays): ❌ Failed to hook");
                    }

                    try {
                        new GhostStorySeenHook().handleStorySeenBlock(dexKitBridge); // Story Seen
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | GhostStorySeen): ❌ Failed to hook");
                    }

                    // Hide in-feed widget units (suggested users panels, surveys, carousels, etc.)
                    try {
                        new HideSuggestedFeedItemsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | HideSuggested): ❌ Failed to hook");
                    }

                    // Ads Blocker
                    try {
                        new AdBlocker().disableSponsoredContent(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | AdBlocker): ❌ Failed to hook");
                    }

                    // tracking link disable
                    try {
                        new TrackingLinkDisable().disableTrackingLinks(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | TrackingLinkDisable): ❌ Failed to hook");
                    }

                    // Miscellaneous
                    try {
                        new DisableStoryFlippingHook().handleStoryFlippingDisable(dexKitBridge); // Story Flipping
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | StoryFlipping): ❌ Failed to hook");
                    }

                    // Story Mentions
                    try {
                        new StoryMentionHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | StoryMentions): ❌ Failed to hook");
                    }

                    // Comment Copy
                    try {
                        new CommentCopyHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | CopyComment): ❌ Failed to hook");
                    }

                    // Caption Copy
                    try {
                        new CaptionCopyContextMenuHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | Caption): ❌ Failed to hook");
                    }

                    // Disable Double Tap to Like
                    try {
                        new DisableDoubleTapLikeHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | DoubleTapLike): ❌ Failed to hook");
                    }

                    // Photo Zoom (long-press)
                    try {
                        new FeedPhotoZoomHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | PhotoZoom): ❌ Failed to hook");
                    }

                    // Location Spoof
                    try {
                        new LocationSpoofHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | SpoofLocation): ❌ Failed to hook");
                    }

                    // Custom Theme
                    try {
                        new IgThemeHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | Theme): ❌ Failed to hook");
                    }

                    // Force Reel Quality
                    try {
                        new ForceReelQualityHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ Failed to hook");
                    }

                    try {
                        new DisableVideoAutoPlayHook().handleAutoPlayDisable(dexKitBridge); // Video Autoplay
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | AutoPlayDisable): ❌ Failed to hook");
                    }

                    // Build Expired Popup
                    try {
                        new BuildExpiredPopupHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | BuildExpired): ❌ Failed to hook");
                    }

                    // Media Download (feed)
                    try {
                        new FeedVideoDownloadHook().install(lpparam.classLoader);
                        FeedVideoDownloadHook.installVideoUrlCaptureHook(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | MediaDownload): ❌ Failed to hook");
                    }

                    // Post Download — three-dots menu (replaces floating button + long-press)
                    try {
                        new PostDownloadContextMenuHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | PostDownload): ❌ Failed to hook");
                    }

                    // Keep Ephemeral Messages
                    try {
                        new GhostEphemeralKeepHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | EphemeralHook): ❌ Failed to hook");
                    }

                    // Permanent View Mode (view-once / view-twice → permanent)
                    try {
                        new GhostPermanentViewHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ViewOnceMedia): ❌ Failed to hook");
                    }

                    // Story Download
                    try {
                        new StoryDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | StoryDownload): ❌ Failed to hook");
                    }

                    // Reel Download
                    try {
                        new ReelDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ReelDownload): ❌ Failed to hook");
                    }

                    // Profile Picture Download
                    try {
                        ProfilePicDownloadHook.install();
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | ProfileDownload): ❌ Failed to hook");
                    }

                    // Network Interceptor
                    try {
                        interceptor.handleInterceptor(lpparam);
                    } catch (Throwable ignored) {
                        ModuleLog.line("(InstaEclipse | Interceptor): ❌ Failed to hook");
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
                    boolean value = intent.getBooleanExtra("value", false);

                    ModuleLog.line("(InstaEclipse) Sync: Updating " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putBoolean(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    FeatureManager.refreshFeatureStatus();
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING".equals(action)) {
                    String key = intent.getStringExtra("key");
                    String value = intent.getStringExtra("value");

                    ModuleLog.line("(InstaEclipse) Sync: Updating string pref " + key);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putString(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT".equals(action)) {
                    String key = intent.getStringExtra("key");
                    int value = intent.getIntExtra("value", 0);

                    ModuleLog.line("(InstaEclipse) Sync: Updating int pref " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putInt(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    FeatureManager.refreshFeatureStatus();
                    IgThemeEngine.invalidate();
                    IgThemeHook.refreshCurrentActivity();

                } else if (CommonUtils.ACTION_REQUEST_LOGS.equals(action)) {
                    try {
                        Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                        reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                        reply.putExtra(CommonUtils.EXTRA_LOG_TEXT, Logging.getSnapshotForIpc());
                        reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                        ctx.sendBroadcast(reply);
                    } catch (Throwable t) {
                        Intent reply = new Intent(CommonUtils.ACTION_LOGS_REPLY);
                        reply.setPackage(CommonUtils.MY_PACKAGE_NAME);
                        reply.putExtra(CommonUtils.EXTRA_LOG_ERROR, String.valueOf(t.getMessage()));
                        reply.putExtra(CommonUtils.EXTRA_LOG_SOURCE, ctx.getPackageName());
                        ctx.sendBroadcast(reply);
                    }

                } else if (CommonUtils.ACTION_CLEAR_LOGS.equals(action)) {
                    Logging.clear();

                } else if ("ps.reso.instaeclipse.ACTION_REQUEST_PREFS".equals(action)) {
                    ModuleLog.line("(InstaEclipse) Sync: Companion app requested current preferences.");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_PREFS");
                    reply.setPackage("ps.reso.instaeclipse");

                    Bundle bundle = new Bundle();
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        if (entry.getValue() instanceof Boolean) {
                            bundle.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        } else if (entry.getValue() instanceof String) {
                            bundle.putString(entry.getKey(), (String) entry.getValue());
                        } else if (entry.getValue() instanceof Integer) {
                            bundle.putInt(entry.getKey(), (Integer) entry.getValue());
                        }
                    }
                    reply.putExtras(bundle);
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

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        }
    }
}
