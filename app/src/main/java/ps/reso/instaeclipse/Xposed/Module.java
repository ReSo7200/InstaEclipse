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
import ps.reso.instaeclipse.mods.feed.HideSuggestedFeedItemsHook;
import ps.reso.instaeclipse.mods.feed.ReelsClientHook;
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
import ps.reso.instaeclipse.mods.misc.DisableDoubleTapLikeHook;
import ps.reso.instaeclipse.mods.misc.DisableStoryFlippingHook;
import ps.reso.instaeclipse.mods.misc.DisableVideoAutoPlayHook;
import ps.reso.instaeclipse.mods.misc.StoryMentionHook;
import ps.reso.instaeclipse.mods.network.IGNetworkInterceptor;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;


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
        // Ensure preferences are loaded


        // Hook into Instagram and its clones
        if (SUPPORTED_PACKAGES.contains(lpparam.packageName)) {
            try {
                if (dexKitBridge == null) {
                    // Load the .so file from your module (if not already loaded)
                    System.load(moduleLibDir + "/libdexkit.so");
                    // XposedBridge.log("libdexkit.so loaded successfully.");

                    // Initialize DexKitBridge with the target app's APK
                    dexKitBridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
                    // XposedBridge.log("DexKitBridge initialized with target APK: " + lpparam.appInfo.sourceDir);
                }

                // Use the target app's ClassLoader
                hostClassLoader = lpparam.classLoader;

                // Call the method to hook the target app
                hookInstagram(lpparam);

            } catch (Exception e) {
                XposedBridge.log("(InstaEclipse): Failed to initialize DexKitBridge for " + lpparam.packageName + ": " + e.getMessage());
            }
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
                        XposedBridge.log("(DexKitCache) ❌ init failed: " + e.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {

                    // Setup context, preferences
                    Context context = (Context) param.args[0];
                    SettingsManager.init(context);
                    SettingsManager.loadAllFlags(context);

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
                        XposedBridge.log("(InstaEclipse | ImportReceiver): ❌ " + e.getMessage());
                    }
                    try {
                        UIHookManager.registerSettingsRestoreReceiver(context);
                    } catch (Throwable e) {
                        XposedBridge.log("(InstaEclipse | RestoreReceiver): ❌ " + e.getMessage());
                    }
                    UIHookManager instagramUI = new UIHookManager();
                    instagramUI.mainActivity(hostClassLoader);

                    IGNetworkInterceptor interceptor = new IGNetworkInterceptor();

                    // --- Feature Hooks ---

                    // Developer Options
                    try {
                        new DevOptionsUnlockHook().handleDevOptions(dexKitBridge);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | DevOptions): ❌ Failed to hook");
                    }

                    // Ghost Mode
                    try {
                        new GhostDMSeenHook().handleSeenBlock(dexKitBridge); // DM Seen
                        new GhostDMMarkAsReadHook(moduleSourceDir).install(lpparam.classLoader); // Mark as Read Button
                        new GhostChannelMarkAsReadHook().install(lpparam.classLoader); // Channel Mark as Read Button
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostSeen): ❌ Failed to hook");
                    }

                    try {
                        new GhostTypingIndicatorHook().handleTypingBlock(dexKitBridge); // DM Typing
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostTyping): ❌ Failed to hook");
                    }

                    try {
                        new GhostScreenshotDetectionHook().handleScreenshotBlock(dexKitBridge); // Screenshot
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostScreenshot): ❌ Failed to hook");
                    }

                    try {
                        new ScreenshotPermissionHook().install(lpparam.classLoader); // Allow Screenshots
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | ScreenshotPermission): ❌ Failed to hook");
                    }

                    try {
                        new GhostViewOnceHook().handleViewOnceBlock(dexKitBridge); // View Once
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostViewOnce): ❌ Failed to hook");
                    }

                    try {
                        new GhostReplayLimitHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | UnlimitedReplays): ❌ Failed to hook");
                    }

                    try {
                        new GhostStorySeenHook().handleStorySeenBlock(dexKitBridge); // Story Seen
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostStorySeen): ❌ Failed to hook");
                    }

                    // Hide in-feed widget units (suggested users panels, surveys, carousels, etc.)
                    try {
                        new HideSuggestedFeedItemsHook().install(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | HideSuggested): ❌ Failed to hook");
                    }

                    // Client-side reels blocker (intercepts the reels viewer)
                    try {
                        new ReelsClientHook().install(hostClassLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | ReelsClient): ❌ Failed to hook");
                    }

                    // Ads Blocker
                    try {
                        new AdBlocker().disableSponsoredContent(dexKitBridge, hostClassLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | AdBlocker): ❌ Failed to hook");
                    }

                    // tracking link disable
                    try {
                        new TrackingLinkDisable().disableTrackingLinks(hostClassLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | TrackingLinkDisable): ❌ Failed to hook");
                    }

                    // Miscellaneous
                    try {
                        new DisableStoryFlippingHook().handleStoryFlippingDisable(dexKitBridge); // Story Flipping
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | StoryFlipping): ❌ Failed to hook");
                    }

                    // Story Mentions
                    try {
                        new StoryMentionHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | StoryMentions): ❌ Failed to hook");
                    }

                    // Comment Copy
                    try {
                        new CommentCopyHook().install(lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | CopyComment): ❌ Failed to hook");
                    }

                    // Disable Double Tap to Like
                    try {
                        new DisableDoubleTapLikeHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | DoubleTapLike): ❌ Failed to hook");
                    }

                    try {
                        new DisableVideoAutoPlayHook().handleAutoPlayDisable(dexKitBridge); // Video Autoplay
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ Failed to hook");
                    }

                    // Build Expired Popup
                    try {
                        new BuildExpiredPopupHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | BuildExpired): ❌ Failed to hook");
                    }

                    // Media Download (feed)
                    try {
                        new FeedVideoDownloadHook().install(lpparam.classLoader);
                        FeedVideoDownloadHook.installVideoUrlCaptureHook(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | MediaDownload): ❌ Failed to hook");
                    }

                    // Post Download — three-dots menu (replaces floating button + long-press)
                    try {
                        new PostDownloadContextMenuHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | PostDownload): ❌ Failed to hook");
                    }

                    // Keep Ephemeral Messages
                    try {
                        new GhostEphemeralKeepHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | EphemeralHook): ❌ Failed to hook");
                    }

                    // Permanent View Mode (view-once / view-twice → permanent)
                    try {
                        new GhostPermanentViewHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | ViewOnceMedia): ❌ Failed to hook");
                    }

                    // Story Download
                    try {
                        new StoryDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | StoryDownload): ❌ Failed to hook");
                    }

                    // Reel Download
                    try {
                        new ReelDownloadHook().install(dexKitBridge, lpparam.classLoader);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | ReelDownload): ❌ Failed to hook");
                    }

                    // Profile Picture Download
                    try {
                        ProfilePicDownloadHook.install();
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | ProfileDownload): ❌ Failed to hook");
                    }

                    // Network Interceptor
                    try {
                        interceptor.handleInterceptor(lpparam);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | Interceptor): ❌ Failed to hook");
                    }

                }

            });

        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse): Failed to hook " + lpparam.packageName + ": " + e.getMessage());
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

                    XposedBridge.log("(InstaEclipse) Sync: Updating " + key + " to " + value);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putBoolean(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);
                    FeatureManager.refreshFeatureStatus();

                } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING".equals(action)) {
                    String key = intent.getStringExtra("key");
                    String value = intent.getStringExtra("value");

                    XposedBridge.log("(InstaEclipse) Sync: Updating string pref " + key);

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putString(key, value).apply();

                    SettingsManager.loadAllFlags(ctx);

                } else if ("ps.reso.instaeclipse.ACTION_REQUEST_PREFS".equals(action)) {
                    XposedBridge.log("(InstaEclipse) Sync: Companion app requested current preferences.");

                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
                    Intent reply = new Intent("ps.reso.instaeclipse.ACTION_SEND_PREFS");
                    reply.setPackage("ps.reso.instaeclipse");

                    Bundle bundle = new Bundle();
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        if (entry.getValue() instanceof Boolean) {
                            bundle.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                        } else if (entry.getValue() instanceof String) {
                            bundle.putString(entry.getKey(), (String) entry.getValue());
                        }
                    }
                    reply.putExtras(bundle);
                    ctx.sendBroadcast(reply);

                } else if ("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG".equals(action)) {
                    XposedBridge.log("(InstaEclipse) Sync: Companion app requested Dev Config export.");
                    try {
                        java.io.File source = new java.io.File(ctx.getFilesDir(), "mobileconfig/mc_overrides.json");
                        if (!source.exists()) {
                            XposedBridge.log("(InstaEclipse) Export: mc_overrides.json not found.");
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
                        XposedBridge.log("(InstaEclipse) Export: config reply sent to companion.");
                    } catch (Exception e) {
                        XposedBridge.log("(InstaEclipse) Export: failed: " + e.getMessage());
                    }

                } else if ("ps.reso.instaeclipse.ACTION_BACKUP_SETTINGS".equals(action)) {
                    XposedBridge.log("(InstaEclipse) Sync: Companion app requested Settings backup.");
                    try {
                        String json = ps.reso.instaeclipse.utils.backup.SettingsBackupManager.toJson();
                        Intent exportIntent = new Intent();
                        exportIntent.setComponent(new android.content.ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                        exportIntent.putExtra("json_content", json);
                        exportIntent.putExtra("file_name", "instaeclipse_settings.json");
                        exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(exportIntent);
                    } catch (Exception e) {
                        XposedBridge.log("(InstaEclipse) Failed to create backup: " + e.getMessage());
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        filter.addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        filter.addAction("ps.reso.instaeclipse.ACTION_REQUEST_PREFS");
        filter.addAction("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");
        filter.addAction("ps.reso.instaeclipse.ACTION_BACKUP_SETTINGS");

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            // On API < 33 there are no EXPORTED/NOT_EXPORTED flags.
            // ContextCompat.RECEIVER_NOT_EXPORTED injects a custom permission that the
            // companion app doesn't hold, silently blocking its broadcasts from reaching
            // this receiver.  Use the plain two-arg overload instead so the companion app
            // can send ACTION_UPDATE_PREF_STRING and friends without restriction.
            context.registerReceiver(receiver, filter);
        }
    }
}
