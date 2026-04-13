package ps.reso.instaeclipse.mods.network;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;

public class IGNetworkInterceptor {

    // -------------------------------------------------------------------------
    // Follow-status response interception
    //
    // TigonServiceLayer.startRequest(request, successCallback, errorCallback)
    // fires when a request is about to be sent. For /api/v1/friendships/show/
    // we store the userId from the URL, then hook the success callback's class
    // so we can read the response body when it arrives and parse "followed_by"
    // directly — no DexKit / internal-boolean-hook dependency.
    // -------------------------------------------------------------------------

    /** identity-hash of a pending success-callback → userId being checked */
    private static final ConcurrentHashMap<Integer, String> sPendingCallbacks =
            new ConcurrentHashMap<>();

    /** callback class names that have already been hooked (hook once per class) */
    private static final Set<String> sHookedCallbackClasses =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * Lazily hooks all non-static, non-nullary methods of {@code cb}'s class and
     * stores the identity-hash → userId mapping so we can parse the response when
     * the callback fires.
     */
    private static void registerCallback(Object cb, String userId) {
        if (cb == null) return;
        sPendingCallbacks.put(System.identityHashCode(cb), userId);

        String className = cb.getClass().getName();
        if (sHookedCallbackClasses.contains(className)) return;
        sHookedCallbackClasses.add(className);

        for (Method m : cb.getClass().getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        int hash = System.identityHashCode(p.thisObject);
                        String uid = sPendingCallbacks.get(hash);
                        if (uid == null) return;
                        // Check args first
                        for (Object arg : p.args) {
                            Boolean followedBy = parseFollowedBy(arg);
                            if (followedBy != null) {
                                sPendingCallbacks.remove(hash);
                                // XposedBridge.log("(IE|FollowToast) path=callback-arg followedBy=" + followedBy + " userId=" + uid + " method=" + p.method.getName() + " argType=" + arg.getClass().getSimpleName());
                                showFollowToast(uid, followedBy);
                                return;
                            }
                        }

                        // TigonServiceLayer often stores the response body as a field on
                        // the callback instance rather than passing it as an argument.
                        Boolean followedByFromObj = parseFollowedBy(p.thisObject);
                        if (followedByFromObj != null) {
                            sPendingCallbacks.remove(hash);
                            // XposedBridge.log("(IE|FollowToast) path=callback-field followedBy=" + followedByFromObj + " userId=" + uid + " method=" + p.method.getName());
                            showFollowToast(uid, followedByFromObj);
                            return;
                        }
                        // Dump field names+types of each arg so we can see where the body lives
                        for (Object a : p.args) {
                            if (a == null) continue;
                            StringBuilder dump = new StringBuilder();
                            Class<?> dc = a.getClass();
                            while (dc != null && dc != Object.class) {
                                for (Field f : dc.getDeclaredFields()) {
                                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                                    f.setAccessible(true);
                                    try {
                                        Object v = f.get(a);
                                        String repr = v == null ? "null"
                                                : v.getClass().getSimpleName() + ":" + String.valueOf(v).substring(0, Math.min(60, String.valueOf(v).length()));
                                        dump.append(f.getName()).append("=").append(repr).append("; ");
                                    } catch (Throwable ignored) {}
                                }
                                dc = dc.getSuperclass();
                            }
                            // XposedBridge.log("(IE|FollowToast) no followed_by found cbClass=" + p.thisObject.getClass().getSimpleName() + " method=" + p.method.getName() + " argClass=" + a.getClass().getSimpleName() + " fields=[" + dump.toString().trim() + "]");
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Attempts to find and return the "followed_by" boolean from {@code arg},
     * which may be a String, byte[], or object with a body-accessor method.
     * Returns null if the argument doesn't contain friendship data.
     */
    private static Boolean parseFollowedBy(Object arg) {
        // Depth 3 is required because: Callback -> Response Wrapper (3ve) -> Payload/Body
        String body = toJsonString(arg, 3);
        if (body == null || !body.contains("\"followed_by\"")) return null;
        try {
            return new org.json.JSONObject(body).optBoolean("followed_by", false);
        } catch (Throwable ignored) {
            return body.contains("\"followed_by\":true") || body.contains("\"followed_by\": true");
        }
    }

    /**
     * Converts {@code obj} to a JSON string containing "followed_by", or null.
     * Checks the value itself, common accessor methods, and up to 2 levels of
     * declared fields — covers both arg-based and thisObject-based responses.
     */
    private static String toJsonString(Object obj) {
        return toJsonString(obj, 2);
    }

    private static String toJsonString(Object obj, int depth) {
        if (obj == null || depth < 0) return null;

        // Direct ByteBuffer handling for method=A09
        if (obj instanceof java.nio.ByteBuffer) {
            try {
                java.nio.ByteBuffer dup = ((java.nio.ByteBuffer) obj).duplicate();
                if (dup.hasArray()) {
                    // Use limit() to get the actual data size in the buffer
                    String s = new String(dup.array(), dup.arrayOffset(), dup.limit(), java.nio.charset.StandardCharsets.UTF_8);
                    return s.contains("followed_by") ? s : null;
                }
            } catch (Throwable ignored) {}
        }

        if (obj instanceof byte[]) {
            try {
                return new String((byte[]) obj, "UTF-8");
            } catch (Throwable ignored) {}
        }

        // Recursive field scanning to find the body inside wrapper objects
        if (depth > 0) {
            Class<?> cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try {
                        Object val = f.get(obj);
                        if (val != null && val != obj && !(val instanceof Number)) {
                            String s = toJsonString(val, depth - 1);
                            if (s != null && s.contains("followed_by")) return s;
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /** Shows the follow-status toast on the main thread if userId is still pending. */
    private static void showFollowToast(String userId, boolean followedBy) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // Check against the last ID captured by the Tigon Interceptor
                String currentTarget = FollowIndicatorTracker.currentlyViewedUserId;
                if (currentTarget == null || !userId.equals(currentTarget)) {
                    return;
                }

                Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
                String statusStr = followedBy
                        ? I18n.t(ctx, R.string.ig_toast_follows_you)
                        : I18n.t(ctx, R.string.ig_toast_not_follows_you);

                // Toast format: (ID) Follows you / Does not follow you
                CustomToast.showCustomToast(ctx, "(" + userId + ") " + statusStr);

                // Clear tracker so we don't process duplicate callback triggers
                FollowIndicatorTracker.currentlyViewedUserId = null;
            } catch (Throwable ignored) {}
        });
    }

    // -------------------------------------------------------------------------

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
                                        shouldDrop |= uri.getPath().contains("discover/chaining/");
                                        FeatureStatusTracker.setHooked("DisableDiscoverPeople");
                                    }

                                    if (shouldDrop) {
                                        try {
                                            URI fakeUri = new URI("https", "127.0.0.1", "/404", null);
                                            XposedHelpers.setObjectField(requestObj, finalUriFieldName, fakeUri);
                                        } catch (Exception e) {
                                            // ignore
                                        }
                                    }
                                    String path = uri.getPath();
                                    if (FeatureFlags.showFollowerToast && path.startsWith("/api/v1/friendships/show/")) {
                                        String[] parts = path.split("/");
                                        if (parts.length >= 6) {
                                            final String capturedId = parts[5];

                                            // 1. Mark the target for the toast
                                            FollowIndicatorTracker.currentlyViewedUserId = capturedId;
                                            XposedBridge.log("(IE|Interceptor) captured userId=" + capturedId);

                                            // 2. Register callbacks for the LIVE response bytes
                                            if (param.args.length > 1) registerCallback(param.args[1], capturedId);
                                            if (param.args.length > 2) registerCallback(param.args[2], capturedId);
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
