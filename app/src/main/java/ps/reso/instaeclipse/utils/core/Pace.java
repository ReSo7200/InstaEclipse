package ps.reso.instaeclipse.utils.core;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Human-like pacing for loops that make Instagram send requests (paging a follower list,
 * bulk unsend, Load all comments, Download all posts). A fixed, fast interval with no breaks
 * looks like a script and is what Instagram's rate limits and "action blocked" checks react to,
 * so every step waits a random time, with a longer rest every few steps.
 */
public final class Pace {
    private Pace() {}

    /** True when "Lower ban risk" is on. */
    public static boolean on() {
        return ps.reso.instaeclipse.utils.feature.FeatureFlags.safePacing;
    }

    /** {@code fastMs} when "Lower ban risk" is off, otherwise a random delay in {@code minMs}..{@code maxMs}. */
    public static long between(long fastMs, long minMs, long maxMs) {
        return on() ? between(minMs, maxMs) : fastMs;
    }

    /** {@code fastMs} when "Lower ban risk" is off, otherwise {@link #next(int, long, long, int, long, long)}. */
    public static long next(long fastMs, int step, long minMs, long maxMs, int every, long restMinMs, long restMaxMs) {
        return on() ? next(step, minMs, maxMs, every, restMinMs, restMaxMs) : fastMs;
    }

    /** A random delay between {@code minMs} and {@code maxMs}. */
    public static long between(long minMs, long maxMs) {
        if (maxMs <= minMs) return minMs;
        return ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
    }

    /**
     * The delay before step {@code step} (1-based): {@link #between} normally, and a longer
     * rest of {@code restMinMs}..{@code restMaxMs} after every {@code every} steps.
     */
    public static long next(int step, long minMs, long maxMs, int every, long restMinMs, long restMaxMs) {
        if (every > 0 && step > 0 && step % every == 0) return between(restMinMs, restMaxMs);
        return between(minMs, maxMs);
    }
}
