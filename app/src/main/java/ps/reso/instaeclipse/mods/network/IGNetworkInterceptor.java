package ps.reso.instaeclipse.mods.network;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.mods.misc.FollowStatusHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class IGNetworkInterceptor {

    private static final URI FAKE_URI = URI.create("https://127.0.0.1/404");
    private static final String FAKE_URL = "https://127.0.0.1/404";

    /**
     * Neutralise a dropped request by redirecting it to a dead local URL. IG 446+/447.0.0.39+ keeps
     * the request URL in MULTIPLE fields on the request object (e.g. a String copy plus two java.net.URI
     * copies), and the actual dispatch reads one of the copies — not necessarily the single URI field
     * we resolve for inspection. Rewriting only that one field is silently ignored, so the request
     * still goes out. Rewrite EVERY url-bearing field: every java.net.URI field, and every String
     * field that currently holds this request's URL (matched by the original value / same path).
     */
    private static void neutralizeUrlFields(Object requestObj, URI original) {
        String orig = original.toString();
        String origPath = original.getPath();
        for (java.lang.reflect.Field f : requestObj.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(requestObj);
                if (v instanceof URI) {
                    f.set(requestObj, FAKE_URI);
                } else if (v instanceof String s && s.startsWith("http")
                        && (s.equals(orig) || (origPath != null && !origPath.isEmpty() && s.contains(origPath)))) {
                    f.set(requestObj, FAKE_URL);
                }
            } catch (Throwable ignored) {}
        }
    }

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

            // Dynamically identify the URI field in the request object
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
                              try {
                                Object requestObj = param.args[0];
                                URI uri = (URI) XposedHelpers.getObjectField(requestObj, finalUriFieldName);

                                if (uri != null && uri.getPath() != null) {
                                    final String host = uri.getHost(); // null for opaque/relative URIs — guard before use
                                    boolean shouldDrop = false;


                                    // Ghost Mode URIs
                                    if (FeatureFlags.isGhostSeen) {
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
                                        // Version-agnostic: getPath() excludes the ?reel=... query, so it
                                        // reads /api/vN/media/seen/ on both old (v2) and new (447: v1) builds.
                                        shouldDrop |= uri.getPath().contains("/media/seen/");
                                        FeatureStatusTracker.setHooked("GhostStories");
                                    }
                                    if (FeatureFlags.isGhostLive) {
                                        shouldDrop |= uri.getPath().contains("/heartbeat_and_get_viewer_count/");
                                        FeatureStatusTracker.setHooked("GhostLive");
                                    }
                                    // Remove Meta AI (#179): stop Meta AI from replying/loading.
                                    if (FeatureFlags.removeMetaAI) {
                                        String p = uri.getPath();
                                        shouldDrop |= p.contains("/ig_meta_ai_side_chat_send_contextual_query/")
                                                || p.contains("/ig_meta_ai_side_chat_new_session/")
                                                || p.contains("/create_ig_meta_ai_side_chat/")
                                                || p.contains("/genai/response")
                                                || (uri.getHost() != null && uri.getHost().contains("aistudio.instagram.com"));
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
                                    // Full Disable Reels takes precedence over the except-DM exception:
                                    // when it is on, every clips endpoint is dropped — including
                                    // "Allow in DM" (disableReelsExceptDM) is the switch that controls DM
                                    // reels: when it is ON, DM-opened reels (/api/v1/clips/items/) are
                                    // allowed and only the reels feed/discover is dropped; when it is OFF,
                                    // full Disable Reels drops every clips endpoint including clips/items/.
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
                                                || (host != null && host.contains("i.instagram.com") && uri.getPath().contains("/api/v1/fbsearch/top_serp/"));
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
                                        shouldDrop |= (host != null && (host.contains("graph.instagram.com")
                                                || host.contains("graph.facebook.com")))
                                                || uri.getPath().contains("/logging_client_events");
                                    }

                                    // Misc
                                    if (FeatureFlags.spoofLastSeen) {
                                        String p = uri.getPath();
                                        shouldDrop |= p.contains("/push/setForegroundState/")
                                                || p.contains("/accounts/update_active_status")
                                                || p.contains("/notes/create_note")
                                                || p.contains("/accounts/set_presence_disabled")
                                                || p.contains("/update_active_status")
                                                || p.contains("/banyan/banyan/")
                                                || p.endsWith("/last_active/")
                                                || p.contains("/presence/");
                                        FeatureStatusTracker.setHooked("SpoofLastSeen");
                                    }
                                    // NOTE: Disable Repost is handled at the UI/action level in
                                    // mods.ui.DisableRepostHook — NOT here. Dropping the repost network
                                    // request is ineffective because IG applies the repost optimistically
                                    // client-side, so the repost completes even when the request is dropped.
                                    if (FeatureFlags.disableDiscoverPeople) {
                                        shouldDrop |= uri.getPath().contains("/discover/ayml/");
                                        shouldDrop |= uri.getPath().contains("discover/chaining/");
                                        FeatureStatusTracker.setHooked("DisableDiscoverPeople");
                                    }

                                    if (shouldDrop) {
                                        neutralizeUrlFields(requestObj, uri);
                                    }

                                    // Follow status
                                    if (FeatureFlags.showFollowerToast) {
                                        FeatureStatusTracker.setHooked("FollowerToast");
                                        FollowStatusHook.handleRequest(uri, param.args);
                                    }
                                }
                              } catch (Throwable ignored) {
                                  // A malformed/opaque request must never crash IG's network dispatch.
                              }
                            }
                        }
                );
            } else {
                ModuleLog.line("(InstaEclipse | Interceptor): Could not resolve required classes or fields.");
            }

        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | Interceptor): ❌ " + e.getMessage());
        }
    }
}
