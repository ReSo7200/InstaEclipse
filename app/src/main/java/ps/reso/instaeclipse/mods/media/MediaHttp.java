package ps.reso.instaeclipse.mods.media;

import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared network settings for downloads made inside Instagram's process. */
final class MediaHttp {

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 30_000;

    private MediaHttp() {}

    /** Without timeouts a stalled CDN connection blocks its worker thread forever. */
    static void applyTimeouts(HttpURLConnection conn) {
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
    }

    /**
     * Bounded pool for download work: at most {@code threads} concurrent downloads, extra work
     * queues instead of spawning a thread per request, and idle threads exit.
     */
    static ExecutorService newDownloadExecutor(int threads) {
        AtomicInteger n = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "InstaEclipse-DL-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
