package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import ps.reso.instaeclipse.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Foreground service that runs in the companion-app process and saves downloaded media
 * to a SAF (Storage Access Framework) folder.
 *
 * <p>The companion app holds the persistable SAF permission for folders picked via
 * FeaturesFragment.  The Xposed module (running in Instagram's process) cannot use
 * that permission directly, so it forwards the CDN URL(s) to this service as plain
 * string extras.  The service downloads the media itself — no file-descriptor passing
 * across different UIDs is required.
 *
 * <p>For video+audio-separate streams the service also handles the merge step locally.
 * Download progress is reported via a live-updating foreground notification.
 */
public class DownloadSaveService extends Service {

    private static final String CHANNEL_ID  = "ie_dl_save";
    private static final int    NOTIF_ID    = 0x49455344; // "IESD" — ongoing progress
    private static final int    DONE_NOTIF_BASE = 0x49455345; // per-startId completion notifs
    private static final String CACHE_PREFS = "instaeclipse_cache";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36";

    /** Throttle: minimum ms between notification updates. */
    private static final long NOTIF_INTERVAL_MS = 250;

    private NotificationManager nm;
    private long lastNotifMs  = 0;
    private int  lastNotifPct = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildProgressNotification("Starting…", 0, 0, true));

        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }

        String url       = intent.getStringExtra("url");
        String audioUrl  = intent.getStringExtra("audioUrl"); // null → single-stream
        String filename  = intent.getStringExtra("filename");
        String mimeType  = intent.getStringExtra("mimeType");
        String username  = intent.getStringExtra("username");

        if (url == null || filename == null) { stopSelf(startId); return START_NOT_STICKY; }

        SharedPreferences cache = getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        String saveUri          = cache.getString("downloaderCustomUri", "");
        boolean usernameFolder  = cache.getBoolean("downloaderUsernameFolder", false);

        if (saveUri.isEmpty()) {
            showToast(getString(R.string.ig_toast_no_download_folder));
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final int    sid    = startId;
        final String fUrl   = url, fAudio = audioUrl, fFile = filename;
        final String fMime  = mimeType, fUser = username, fSave = saveUri;
        final boolean fUF   = usernameFolder;

        new Thread(() -> {
            try {
                Uri savedUri = fAudio != null
                        ? downloadMergeAndSave(fUrl, fAudio, fFile, fMime, fSave, fUser, fUF)
                        : downloadAndSave(fUrl, fFile, fMime, fSave, fUser, fUF);
                postDoneNotification(sid, "Saved: " + fFile, fMime, savedUri);
                showToast(getString(R.string.ig_toast_file_saved, fFile));
            } catch (Throwable e) {
                postDoneNotification(sid, "Download failed: " + e.getMessage(), null, null);
                showToast(getString(R.string.ig_toast_download_failed, e.getMessage()));
            } finally {
                stopSelf(sid);
            }
        }, "ie-saf-" + startId).start();

        return START_NOT_STICKY;
    }

    // ── Download helpers ──────────────────────────────────────────────────────

    private Uri downloadAndSave(String url, String filename, String mimeType,
                                String saveUri, String username, boolean usernameFolder)
            throws Exception {
        File tmp = File.createTempFile("ie_dl_", mimeType.contains("video") ? ".mp4" : ".jpg",
                getCacheDir());
        try {
            pushProgress("Downloading…", 0, 100, false);
            downloadToFile(url, tmp, (done, total) -> {
                if (total > 0) {
                    int pct = (int) (done * 95 / total);
                    maybeUpdateProgress("Downloading…", pct, 100);
                } else {
                    maybeUpdateProgress("Downloading…", 0, 0, true);
                }
            });
            pushProgress("Saving…", 97, 100, false);
            return writeViaSaf(tmp, filename, mimeType, saveUri, username, usernameFolder);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private Uri downloadMergeAndSave(String videoUrl, String audioUrl, String filename,
                                     String mimeType, String saveUri,
                                     String username, boolean usernameFolder)
            throws Exception {
        File cacheDir = getCacheDir();
        long ts = System.currentTimeMillis();
        File tv  = new File(cacheDir, "ie_sv_" + ts + ".mp4");
        File ta  = new File(cacheDir, "ie_sa_" + ts + ".mp4");
        File out = new File(cacheDir, "ie_sm_" + ts + ".mp4");
        try {
            // Video download: 0–60 %
            pushProgress("Downloading video…", 0, 100, false);
            downloadToFile(videoUrl, tv, (done, total) -> {
                if (total > 0) {
                    int pct = (int) (done * 60 / total);
                    maybeUpdateProgress("Downloading video…", pct, 100);
                }
            });

            // Audio download: 60–80 %
            pushProgress("Downloading audio…", 60, 100, false);
            downloadToFile(audioUrl, ta, (done, total) -> {
                if (total > 0) {
                    int pct = 60 + (int) (done * 20 / total);
                    maybeUpdateProgress("Downloading audio…", pct, 100);
                }
            });

            // Merge: 80–95 % (indeterminate — MediaMuxer gives no callbacks)
            pushProgress("Merging…", 80, 100, true);
            mergeVideoAudio(tv.getAbsolutePath(), ta.getAbsolutePath(), out.getAbsolutePath());

            pushProgress("Saving…", 97, 100, false);
            return writeViaSaf(out, filename, mimeType, saveUri, username, usernameFolder);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tv.delete();
            //noinspection ResultOfMethodCallIgnored
            ta.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    // ── SAF write ────────────────────────────────────────────────────────────

    private Uri writeViaSaf(File src, String filename, String mimeType,
                             String saveUri, String username, boolean usernameFolder)
            throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(saveUri));
        if (dir == null || !dir.canWrite()) {
            throw new Exception("SAF folder not writable — was the permission revoked?");
        }

        if (usernameFolder && username != null && !username.isEmpty()) {
            DocumentFile sub = dir.findFile(username);
            if (sub == null || !sub.isDirectory()) sub = dir.createDirectory(username);
            if (sub == null) throw new Exception("Cannot create username sub-folder");
            dir = sub;
        }

        DocumentFile file = dir.createFile(mimeType, filename);
        if (file == null) throw new Exception("SAF createFile returned null");

        try (FileInputStream in  = new FileInputStream(src);
             OutputStream    os  = getContentResolver().openOutputStream(file.getUri())) {
            if (os == null) throw new Exception("SAF openOutputStream returned null");
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        return file.getUri();
    }

    // ── Network / merge utilities ─────────────────────────────────────────────

    @FunctionalInterface
    interface ProgressCallback {
        /** @param bytesRead bytes downloaded so far; @param totalBytes -1 if unknown */
        void onProgress(long bytesRead, long totalBytes);
    }

    private static void downloadToFile(String url, File dest, ProgressCallback cb)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", UA);
        conn.connect();
        long total = conn.getContentLengthLong(); // -1 if server doesn't send Content-Length
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (cb != null) cb.onProgress(downloaded, total);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void mergeVideoAudio(String vp, String ap, String op) throws Exception {
        MediaExtractor vEx = new MediaExtractor(), aEx = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(op, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            vEx.setDataSource(vp); aEx.setDataSource(ap);
            int vi = selectTrack(vEx, "video/"), ai = selectTrack(aEx, "audio/");
            if (vi < 0 || ai < 0) throw new Exception("Missing video or audio track");
            int vo = mux.addTrack(vEx.getTrackFormat(vi));
            int ao = mux.addTrack(aEx.getTrackFormat(ai));
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            copyTrack(vEx, mux, vo, buf, info);
            copyTrack(aEx, mux, ao, buf, info);
            mux.stop();
        } finally { vEx.release(); aEx.release(); mux.release(); }
    }

    private static int selectTrack(MediaExtractor ex, String mime) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(mime)) { ex.selectTrack(i); return i; }
        }
        return -1;
    }

    @SuppressLint("WrongConstant")
    private static void copyTrack(MediaExtractor ex, MediaMuxer mux, int outTrack,
                                   ByteBuffer buf, MediaCodec.BufferInfo info) {
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.offset = 0; info.size = sz;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    /**
     * Unconditionally updates the foreground notification (use for phase transitions).
     */
    private void pushProgress(String text, int progress, int max, boolean indeterminate) {
        lastNotifPct = progress;
        lastNotifMs  = System.currentTimeMillis();
        nm.notify(NOTIF_ID, buildProgressNotification(text, progress, max, indeterminate));
    }

    /** Convenience overload for determinate progress. */
    private void pushProgress(String text, int progress, int max) {
        pushProgress(text, progress, max, false);
    }

    /**
     * Throttled update — called on every chunk read. Skips the notify call if
     * less than {@link #NOTIF_INTERVAL_MS} have passed AND progress changed < 2 %.
     */
    private void maybeUpdateProgress(String text, int pct, int max) {
        maybeUpdateProgress(text, pct, max, false);
    }

    private void maybeUpdateProgress(String text, int pct, int max, boolean indeterminate) {
        long now = System.currentTimeMillis();
        if (Math.abs(pct - lastNotifPct) < 2 && now - lastNotifMs < NOTIF_INTERVAL_MS) return;
        lastNotifPct = pct;
        lastNotifMs  = now;
        nm.notify(NOTIF_ID, buildProgressNotification(text, pct, max, indeterminate));
    }

    private Notification buildProgressNotification(String text, int progress, int max,
                                                    boolean indeterminate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("InstaEclipse")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(max, progress, indeterminate)
                    .setOngoing(true)
                    .build();
        }
        //noinspection deprecation
        return new Notification.Builder(this)
                .setContentTitle("InstaEclipse")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(max, progress, indeterminate)
                .setOngoing(true)
                .build();
    }

    /**
     * Posts a non-ongoing completion/error notification that persists after the service stops.
     * Uses {@code DONE_NOTIF_BASE + startId} so concurrent downloads don't collide.
     */
    /**
     * Posts a persistent completion notification.
     * If {@code fileUri} is non-null, tapping the notification opens the saved file
     * in the device's default viewer (gallery, video player, etc.).
     */
    private void postDoneNotification(int startId, String text, String mimeType, Uri fileUri) {
        int icon = fileUri != null
                ? android.R.drawable.stat_sys_download_done
                : android.R.drawable.stat_notify_error;

        // Build a tap-to-open PendingIntent when we have a valid file URI
        android.app.PendingIntent contentIntent = null;
        if (fileUri != null && mimeType != null) {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_ONE_SHOT
                    : android.app.PendingIntent.FLAG_ONE_SHOT;
            contentIntent = android.app.PendingIntent.getActivity(
                    this, DONE_NOTIF_BASE + startId, viewIntent, piFlags);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("InstaEclipse")
               .setContentText(text)
               .setSmallIcon(icon)
               .setAutoCancel(true);
        if (contentIntent != null) builder.setContentIntent(contentIntent);
        nm.notify(DONE_NOTIF_BASE + startId, builder.build());
    }

    private void showToast(String msg) {
        // Use application context — service context is invalid after stopSelf() fires
        Context appCtx = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show());
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "InstaEclipse Downloads", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
