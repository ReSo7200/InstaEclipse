package ps.reso.instaeclipse.Xposed;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;

import org.luckypray.dexkit.DexKitBridge;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.mods.devops.DevOptionsEnable;
import ps.reso.instaeclipse.mods.ads.AdBlocker;
import ps.reso.instaeclipse.mods.ghost.ScreenshotDetection;
import ps.reso.instaeclipse.mods.ghost.SeenState;
import ps.reso.instaeclipse.mods.ghost.StorySeen;
import ps.reso.instaeclipse.mods.ghost.TypingStatus;
import ps.reso.instaeclipse.mods.ghost.ViewOnce;
import ps.reso.instaeclipse.mods.misc.AutoPlayDisable;
import ps.reso.instaeclipse.mods.misc.FollowerIndicator;
import ps.reso.instaeclipse.mods.misc.StoryFlipping;
import ps.reso.instaeclipse.mods.misc.TrackingLinkDisable;
import ps.reso.instaeclipse.mods.network.Interceptor;
import ps.reso.instaeclipse.mods.ui.InstagramUI;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.core.CommonUtils;


@SuppressLint("UnsafeDynamicallyLoadedCode")
public class Module implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    public static DexKitBridge dexKitBridge;
    public static ClassLoader hostClassLoader;
    private static String moduleSourceDir;
    private static String moduleLibDir;

    // for dev usage
    /*
    public static void showToast(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(AndroidAppHelper.currentApplication().getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
    */

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log("(InstaEclipse): Zygote initialized.");

        // Save the module's APK path
        moduleSourceDir = startupParam.modulePath;

        // Detect ABI correctly
        String abi = Build.SUPPORTED_ABIS[0]; // Primary ABI
        String abiFolder;

        if (abi.equalsIgnoreCase("arm64-v8a")) abiFolder = "arm64";
        else if (abi.equalsIgnoreCase("armeabi-v7a") || abi.equalsIgnoreCase("armeabi") || abi.equalsIgnoreCase("armv8i"))
            abiFolder = "arm";
        else if (abi.equalsIgnoreCase("x86")) abiFolder = "x86";
        else if (abi.equalsIgnoreCase("x86_64")) abiFolder = "x86_64";
        else abiFolder = abi; // fallback just in case

        moduleLibDir = moduleSourceDir.substring(0, moduleSourceDir.lastIndexOf("/")) + "/lib/" + abiFolder;

        XposedBridge.log("(InstaEclipse) Module paths initialized:" + "\nSourceDir: " + moduleSourceDir + "\nLibDir: " + moduleLibDir);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        // Ensure preferences are loaded


        XposedBridge.log("(InstaEclipse): Loaded package: " + lpparam.packageName);

        // Hook into your module
        if (lpparam.packageName.equals(CommonUtils.MY_PACKAGE_NAME)) {
            try {

                if (dexKitBridge == null) {
                    // Load the .so file from your module
                    System.load(moduleLibDir + "/libdexkit.so");
                    XposedBridge.log("libdexkit.so loaded successfully.");

                    // Initialize DexKitBridge with your module's APK (for module-specific tasks, if needed)
                    dexKitBridge = DexKitBridge.create(moduleSourceDir);

                    XposedBridge.log("DexKitBridge initialized for InstaEclipse.");
                }

                // Hook your module
                hookOwnModule(lpparam);

            } catch (Exception e) {
                XposedBridge.log("(InstaEclipse): Failed to initialize DexKitBridge for InstaEclipse: " + e.getMessage());
            }
        }

        // Hook into Instagram
        if (lpparam.packageName.equals(CommonUtils.IG_PACKAGE_NAME)) {
            try {
                if (dexKitBridge == null) {
                    // Load the .so file from your module (if not already loaded)
                    System.load(moduleLibDir + "/libdexkit.so");
                    // XposedBridge.log("libdexkit.so loaded successfully.");

                    // Initialize DexKitBridge with Instagram's APK
                    dexKitBridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
                    // XposedBridge.log("DexKitBridge initialized with Instagram's APK: " + lpparam.appInfo.sourceDir);
                }

                // Use Instagram's ClassLoader
                hostClassLoader = lpparam.classLoader;

                // Call the method to hook Instagram
                hookInstagram(lpparam);

            } catch (Exception e) {
                XposedBridge.log("(InstaEclipse): Failed to initialize DexKitBridge for Instagram: " + e.getMessage());
            }
        }
    }

    private void hookOwnModule(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            findAndHookMethod(CommonUtils.MY_PACKAGE_NAME + ".MainActivity", lpparam.classLoader, "isModuleActive", XC_MethodReplacement.returnConstant(true));
            // XposedBridge.log("InstaEclipse | Successfully hooked isModuleActive().");
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse): Failed to hook MainActivity: " + e.getMessage());
        }
    }


    private void hookInstagram(XC_LoadPackage.LoadPackageParam lpparam) {

        try {


            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {

                    XposedBridge.log("InstaEclipse: Settings loaded via Application.attach");

                    // Setup context, preferences
                    Context context = (Context) param.args[0];
                    SettingsManager.init(context);
                    SettingsManager.loadAllFlags(context);
                    FeatureManager.refreshFeatureStatus(); // Update internal feature states

                    InstagramUI instagramUI = new InstagramUI();
                    instagramUI.mainActivity(hostClassLoader);

                    XposedBridge.log("(InstaEclipse): Instagram package detected. Starting feature hooks...");

                    Interceptor interceptor = new Interceptor();

                    // --- Feature Hooks ---

                    // Developer Options
                    try {
                        new DevOptionsEnable().handleDevOptions(dexKitBridge);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | DevOptions): ❌ Failed to hook");
                    }

                    // Ghost Mode
                    try {
                        new SeenState().handleSeenBlock(dexKitBridge); // DM Seen
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostSeen): ❌ Failed to hook");
                    }

                    try {
                        new TypingStatus().handleTypingBlock(dexKitBridge); // DM Typing
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostTyping): ❌ Failed to hook");
                    }

                    try {
                        new ScreenshotDetection().handleScreenshotBlock(dexKitBridge); // Screenshot
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostScreenshot): ❌ Failed to hook");
                    }

                    try {
                        new ViewOnce().handleViewOnceBlock(dexKitBridge); // View Once
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostViewOnce): ❌ Failed to hook");
                    }

                    try {
                        new StorySeen().handleStorySeenBlock(dexKitBridge); // Story Seen
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | GhostStorySeen): ❌ Failed to hook");
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
                        new StoryFlipping().handleStoryFlippingDisable(dexKitBridge); // Story Flipping
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | StoryFlipping): ❌ Failed to hook");
                    }

                    try {
                        new AutoPlayDisable().handleAutoPlayDisable(dexKitBridge); // Video Autoplay
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ Failed to hook");
                    }

                    try {
                        FollowerIndicator followerIndicator = new FollowerIndicator();
                        String bridge = followerIndicator.findFollowerStatusMethod(Module.dexKitBridge);
                        followerIndicator.checkFollow(hostClassLoader, bridge, Module.dexKitBridge);
                    } catch (Throwable ignored) {
                        XposedBridge.log("(InstaEclipse | AutoPlayDisable): ❌ Failed to hook");
                    }

                    // Custom Toast
                    if (FeatureFlags.showFeatureToasts) {
                        try {

                            CustomToast.hookMainActivity(lpparam);

                        } catch (Throwable ignored) {
                            XposedBridge.log("(InstaEclipse | CustomToast): ❌ Failed to hook");
                        }
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
            XposedBridge.log("(InstaEclipse): Failed to hook Instagram: " + e.getMessage());
        }
    }
}