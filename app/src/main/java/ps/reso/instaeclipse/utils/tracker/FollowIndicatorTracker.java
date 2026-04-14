package ps.reso.instaeclipse.utils.tracker;

import java.util.concurrent.ConcurrentHashMap;

public class FollowIndicatorTracker {

    /** ID of the profile currently being checked, set by the network interceptor. */
    public static volatile String currentlyViewedUserId = null;
    /** Epoch ms when currentlyViewedUserId was last set. */
    public static volatile long capturedAt = 0;

    /**
     * Populated by the Xposed hook; consumed by the network interceptor when the
     * hook fires before the network capture (the common case).
     */
    public static final ConcurrentHashMap<String, ObservedFollowResult> observedResults
            = new ConcurrentHashMap<>();

    public static class ObservedFollowResult {
        public final boolean followedBy;
        public final String username; // may be null in obfuscated builds
        public final long timestamp;

        public ObservedFollowResult(boolean followedBy, String username, long timestamp) {
            this.followedBy = followedBy;
            this.username = username;
            this.timestamp = timestamp;
        }
    }
}
