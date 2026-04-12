package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;

public class IGNetworkInterceptor {

    public void handleInterceptor(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader classLoader = lpparam.classLoader;

            // Locate the TigonServiceLayer class dynamically
            Class<?> tigonClass = classLoader.loadClass("com.instagram.api.tigon.TigonServiceLayer");
            Method[] methods = tigonClass.getDeclaredMethods();

            Class<?> random_param_1 = null;
            Class<?> random_param_2 = null;
            Class<?> random_param_3 = null;
            String uriFieldName = null;

            // Analyze methods in TigonServiceLayer
            for (Method method : methods) {
                if (method.getName().equals("startRequest") && method.getParameterCount() == 3) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    random_param_1 = paramTypes[0];
                    random_param_2 = paramTypes[1];
                    random_param_3 = paramTypes[2];
                    break;
                }
            }

            // Dynamically identify the URI field in c5aE
            if (random_param_1 != null) {
                for (Field field : random_param_1.getDeclaredFields()) {
                    if (field.getType().equals(URI.class)) {
                        uriFieldName = field.getName();
                        break;
                    }
                }
            }

            // If classes and fields are resolved, hook the method
            if (random_param_1 != null && random_param_2 != null && random_param_3 != null && uriFieldName != null) {
                String finalUriFieldName = uriFieldName;
                XposedHelpers.findAndHookMethod("com.instagram.api.tigon.TigonServiceLayer", classLoader, "startRequest",
                        random_param_1, random_param_2, random_param_3, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object requestObj = param.args[0]; // Dynamic object
                                URI uri = (URI) XposedHelpers.getObjectField(requestObj, finalUriFieldName);

                                if (uri != null && uri.getPath() != null) {
                                    // Check all conditions passed in as predicates
                                    boolean shouldDrop = false;

                                    // Ghost Mode URIs
                                    if (FeatureFlags.isGhostSeen){
                                        shouldDrop |= uri.getPath().contains("/threads/") && uri.getPath().contains("/opened");
                                    }
                                    if (FeatureFlags.keepEphemeralMessages) {
                                        shouldDrop |= uri.getPath().contains("/mark_ephemeral_item_ranges_viewed");
                                    }
                                    if (FeatureFlags.isGhostScreenshot) {
                                        shouldDrop |= uri.getPath().endsWith("/screenshot/") || uri.getPath().endsWith("/ephemeral_screenshot/");
                                    }
                                    if (FeatureFlags.isGhostViewOnce) {
                                        shouldDrop |= uri.getPath().endsWith("/item_replayed/");
                                        shouldDrop |= (uri.getPath().contains("/direct") && uri.getPath().endsWith("/item_seen/"));
                                    }
                                    if (FeatureFlags.isGhostStory) {
                                        shouldDrop |= uri.getPath().contains("/api/v2/media/seen/");
                                        FeatureStatusTracker.setHooked("GhostStories");
                                    }
                                    if (FeatureFlags.isGhostLive) {
                                        shouldDrop |= uri.getPath().contains("/heartbeat_and_get_viewer_count/");
                                        FeatureStatusTracker.setHooked("GhostLive");
                                    }

                                    // Distraction Free
                                    if (FeatureFlags.disableStories) {
                                        shouldDrop |= uri.getPath().contains("/feed/reels_tray/")
                                                || uri.getPath().contains("feed/get_latest_reel_media/")
                                                || uri.getPath().contains("direct_v2/pending_inbox/?visual_message")
                                                || uri.getPath().contains("stories/hallpass/")
                                                || uri.getPath().contains("/api/v1/feed/reels_media_stream/");
                                    }
                                    if (FeatureFlags.disableFeed) {
                                        shouldDrop |= uri.getPath().endsWith("/feed/timeline/");
                                    }
                                    if (FeatureFlags.disableReels && !FeatureFlags.disableReelsExceptDM) {
                                        shouldDrop |= uri.getPath().endsWith("/qp/batch_fetch/")
                                                || uri.getPath().contains("api/v1/clips")
                                                || uri.getPath().contains("clips")
                                                || uri.getPath().contains("mixed_media")
                                                || uri.getPath().contains("mixed_media/discover/stream/");
                                    }
                                    if (FeatureFlags.disableReelsExceptDM) {
                                        if (uri.getPath().startsWith("/api/v1/direct_v2/")) {
                                            return;
                                        }
                                        shouldDrop |= (uri.getPath().startsWith("/api/v1/clips/") && uri.getQuery() != null
                                                && (uri.getQuery().contains("next_media_ids=")
                                                || uri.getQuery().contains("max_id=")))
                                                || uri.getPath().contains("/clips/discover/")
                                                || uri.getPath().contains("/mixed_media/discover/stream/");
                                    }
                                    if (FeatureFlags.disableExplore) {
                                        shouldDrop |= uri.getPath().contains("/discover/topical_explore")
                                                || uri.getPath().contains("/discover/topical_explore_stream")
                                                || (uri.getHost().contains("i.instagram.com") && uri.getPath().contains("/api/v1/fbsearch/top_serp/"));
                                    }
                                    if (FeatureFlags.disableComments) {
                                        shouldDrop |= uri.getPath().contains("/api/v1/media/") && uri.getPath().contains("comments/");
                                    }

                                    // Ads
                                    if (FeatureFlags.isAdBlockEnabled) {
                                        shouldDrop |= uri.getPath().contains("profile_ads/get_profile_ads/")
                                                || uri.getPath().contains("/async_ads/")
                                                || uri.getPath().contains("/feed/injected_reels_media/")
                                                || uri.getPath().equals("/api/v1/ads/graphql/");
                                    }

                                    // Analytics
                                    if (FeatureFlags.isAnalyticsBlocked) {
                                        shouldDrop |= uri.getHost().contains("graph.instagram.com")
                                                || uri.getHost().contains("graph.facebook.com")
                                                || uri.getPath().contains("/logging_client_events");
                                    }

                                    // Misc
                                    if (FeatureFlags.disableRepost) {
                                        shouldDrop |= uri.getPath().contains("/media/create_note/");
                                    }
                                    if (FeatureFlags.disableDiscoverPeople) {
                                        shouldDrop |= uri.getPath().contains("/discover/ayml/");
                                        FeatureStatusTracker.setHooked("DisableDiscoverPeople");
                                    }

                                    if (shouldDrop) {
                                        // XposedBridge.log("the URI was blocked: " + uri.getPath());
                                        // Modify the URI to divert the request to a harmless endpoint
                                        try {
                                            URI fakeUri = new URI("https", "127.0.0.1", "/404", null);
                                            XposedHelpers.setObjectField(requestObj, finalUriFieldName, fakeUri);
                                            // XposedBridge.log("🚫 [InstaEclipse] Changed URI to: " + fakeUri);
                                        } catch (Exception e) {
                                            // XposedBridge.log("❌ [InstaEclipse] Failed to modify URI: " + e.getMessage());
                                        }
                                    }
                                    /*
                                     DEV Purposes
                                     */

                            /*else {
                                        XposedBridge.log("Logging: " + uri.getHost() + uri.getPath());
                                    }*/


                                    if (FeatureFlags.showFollowerToast) {
                                        String path = uri.getPath();
                                        if (path != null && path.startsWith("/api/v1/friendships/show/")) {
                                            String[] parts = path.split("/");
                                            if (parts.length >= 6) {
                                                String capturedId = parts[5];
                                                FollowIndicatorTracker.currentlyViewedUserId = capturedId;
                                                XposedBridge.log("(IE|Interceptor) captured userId=" + capturedId);
                                            }
                                        }
                                    }

                                }
                            }
                        }
                );
            } else {
                XposedBridge.log("Could not resolve required classes or fields.");
            }

        } catch (Exception e) {
            XposedBridge.log("Error in interceptor: " + e.getMessage());
        }
    }
}
