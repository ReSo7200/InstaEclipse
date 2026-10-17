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
import ps.reso.instaeclipse.utils.log.ModuleLog;

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
    // Written from every download thread; volatile is enough for the throttle heuristic.
    private volatile long lastNotifMs  = 0;
    private volatile int  lastNotifPct = -1;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 30_000;
    /** Hard cap on a single download so a hostile or broken stream can't fill the disk. */
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    private static final class SavedMedia {
        final Uri uri;
        final String filename;
        final String mimeType;

        SavedMedia(Uri uri, String filename, String mimeType) {
            this.uri = uri;
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.app.Notification starting = buildProgressNotification("Starting…", 0, 0, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, starting, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, starting);
        }

        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }

        // Chat export (ChatExportHook): write a generated HTML + download its media into a
        // nested subfolder, so the whole export lands in the user's SAF folder and is visible.
        if (intent.getStringExtra("chatExportSubdir") != null) {
            handleChatExport(intent, startId);
            return START_NOT_STICKY;
        }

        String url       = intent.getStringExtra("url");
        String audioUrl  = intent.getStringExtra("audioUrl"); // null → single-stream
        String filename  = intent.getStringExtra("filename");
        String mimeType  = intent.getStringExtra("mimeType");
        String username  = intent.getStringExtra("username");
        // Audio-only from a plain MP4 (no separate DASH audio track): pull the audio out locally.
        boolean extractAudio = intent.getBooleanExtra("extractAudio", false);
        // Only part of the source (a post's music clip): start and length in ms; -1 = all of it.
        long trimStartMs    = intent.getLongExtra("trimStartMs", 0);
        long trimDurationMs = intent.getLongExtra("trimDurationMs", -1);

        if (url == null || filename == null) { stopSelf(startId); return START_NOT_STICKY; }
        // This service is exported so Instagram's process can reach it, which means any app can
        // start it. Only accept Instagram CDN URLs and single-segment file names.
        if (!DownloadRequestValidator.isAllowedMediaUrl(url)
                || (audioUrl != null && !DownloadRequestValidator.isAllowedMediaUrl(audioUrl))) {
            ModuleLog.line("(IE|DL) Rejected download request: URL is not an Instagram CDN https URL");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        filename = DownloadRequestValidator.sanitizeFileName(filename, "instaeclipse_" + System.currentTimeMillis());
        if (username != null) username = DownloadRequestValidator.sanitizeFileName(username, null);
        if (mimeType != null && !mimeType.startsWith("image/") && !mimeType.startsWith("video/")
                && !mimeType.startsWith("audio/")) {
            mimeType = null;
        }

        SharedPreferences cache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(this);
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
        final boolean fExtract = extractAudio;
        final long fTrimStart = trimStartMs, fTrimDur = trimDurationMs;

        new Thread(() -> {
            try {
                SavedMedia saved = fExtract
                        ? downloadExtractAudioAndSave(fUrl, fFile, fSave, fUser, fUF, fTrimStart, fTrimDur)
                        : fAudio != null
                        ? downloadMergeAndSave(fUrl, fAudio, fFile, fMime, fSave, fUser, fUF)
                        : downloadAndSave(fUrl, fFile, fMime, fSave, fUser, fUF);
                postDoneNotification(sid, "Saved: " + saved.filename, saved.mimeType, saved.uri);
                showToast(getString(R.string.ig_toast_file_saved, saved.filename));
            } catch (Throwable e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                postDoneNotification(sid, "Download failed: " + reason, null, null, false);
                showToast(getString(R.string.ig_toast_download_failed, reason));
            } finally {
                stopSelf(sid);
            }
        }, "ie-saf-" + startId).start();

        return START_NOT_STICKY;
    }

    // ── Download helpers ──────────────────────────────────────────────────────

    private SavedMedia downloadAndSave(String url, String filename, String mimeType,
                                       String saveUri, String username, boolean usernameFolder)
            throws Exception {
        File tmp = File.createTempFile("ie_dl_", ".bin", getCacheDir());
        try {
            pushProgress("Downloading…", 0, 100, false);
            String responseType = downloadToFile(url, tmp, (done, total) -> {
                if (total > 0) {
                    int pct = (int) (done * 95 / total);
                    maybeUpdateProgress("Downloading…", pct, 100);
                } else {
                    maybeUpdateProgress("Downloading…", 0, 0, true);
                }
            });
            // Audio-only (#234): an .m4a is an MP4 container and would be sniffed as video — keep it.
            MediaTypeDetector.Result detected = mimeType != null && mimeType.startsWith("audio/")
                    ? new MediaTypeDetector.Result(MediaTypeDetector.Kind.UNKNOWN, mimeType, filename)
                    : MediaTypeDetector.resolve(tmp, responseType, mimeType, filename);
            ModuleLog.line("(IE|DL|Type) requested=" + mimeType + " response=" + responseType
                    + " detected=" + detected.kind + " file=" + detected.filename);
            pushProgress("Saving…", 97, 100, false);
            Uri uri = writeViaSaf(tmp, detected.filename, detected.mimeType,
                    saveUri, username, usernameFolder);
            return new SavedMedia(uri, detected.filename, detected.mimeType);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private SavedMedia downloadMergeAndSave(String videoUrl, String audioUrl, String filename,
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
            String corrected = MediaTypeDetector.withCorrectExtension(
                    filename, MediaTypeDetector.Kind.VIDEO);
            Uri uri = writeViaSaf(out, corrected, "video/mp4",
                    saveUri, username, usernameFolder);
            return new SavedMedia(uri, corrected, "video/mp4");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tv.delete();
            //noinspection ResultOfMethodCallIgnored
            ta.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    /** Downloads a video and saves only its audio track as .m4a (AAC copied as-is, no re-encode). */
    private SavedMedia downloadExtractAudioAndSave(String videoUrl, String filename, String saveUri,
                                                   String username, boolean usernameFolder,
                                                   long trimStartMs, long trimDurationMs)
            throws Exception {
        File cacheDir = getCacheDir();
        long ts = System.currentTimeMillis();
        File tv  = new File(cacheDir, "ie_xv_" + ts + ".mp4");
        File out = new File(cacheDir, "ie_xa_" + ts + ".m4a");
        try {
            pushProgress("Downloading audio…", 0, 100, false);
            downloadToFile(videoUrl, tv, (done, total) -> {
                if (total > 0) maybeUpdateProgress("Downloading audio…", (int) (done * 85 / total), 100);
            });
            pushProgress("Extracting audio…", 90, 100, true);
            extractAudioTrack(tv.getAbsolutePath(), out.getAbsolutePath(),
                    trimStartMs * 1000, trimDurationMs > 0 ? trimDurationMs * 1000 : -1);
            String name = filename.replaceAll("\\.(mp4|m4a)$", "") + ".m4a";
            Uri uri = writeViaSaf(out, name, "audio/mp4", saveUri, username, usernameFolder);
            return new SavedMedia(uri, name, "audio/mp4");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tv.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    /** Copies the audio track of {@code videoPath} into an audio-only MP4 (.m4a) at {@code outPath}. */
    static void extractAudioTrack(String videoPath, String outPath) throws Exception {
        extractAudioTrack(videoPath, outPath, 0, -1);
    }

    /**
     * Same, keeping only {@code durationUs} of audio from {@code startUs} (re-timed to start at 0);
     * a duration of -1 keeps the rest of the track. Samples are copied, never re-encoded.
     */
    static void extractAudioTrack(String videoPath, String outPath, long startUs, long durationUs)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(videoPath);
            int ai = selectTrack(ex, "audio/");
            if (ai < 0) throw new Exception("This video has no audio track");
            mux = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int ao = mux.addTrack(ex.getTrackFormat(ai));
            mux.start();
            copyTrackRange(ex, mux, ao, startUs, durationUs);
            mux.stop();
        } finally {
            ex.release();
            if (mux != null) mux.release();
        }
    }

    @SuppressLint("WrongConstant")
    private static void copyTrackRange(MediaExtractor ex, MediaMuxer mux, int outTrack,
                                       long startUs, long durationUs) {
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long end = durationUs > 0 ? startUs + durationUs : Long.MAX_VALUE;
        ex.seekTo(Math.max(0, startUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            long t = ex.getSampleTime();
            if (t > end) break;
            if (t >= startUs) {
                info.offset = 0;
                info.size = sz;
                info.presentationTimeUs = t - startUs;
                info.flags = ex.getSampleFlags();
                mux.writeSampleData(outTrack, buf, info);
            }
            ex.advance();
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

    // ── Chat export (HTML + media batch into a nested subfolder) ───────────────

    private void handleChatExport(Intent intent, int startId) {
        final String subdir       = DownloadRequestValidator.sanitizeSubdir(intent.getStringExtra("chatExportSubdir"));
        final String htmlFilename = DownloadRequestValidator.sanitizeFileName(intent.getStringExtra("htmlFilename"), null);
        final String htmlB64      = intent.getStringExtra("htmlB64");
        final String[] urls  = intent.getStringArrayExtra("mediaUrls");
        final String[] names = intent.getStringArrayExtra("mediaNames");
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                names[i] = DownloadRequestValidator.sanitizeFileName(names[i], "media_" + (i + 1));
            }
        }
        final String[] mimes = intent.getStringArrayExtra("mediaMimes");
        final int messageCount = intent.getIntExtra("messageCount", 0);

        SharedPreferences cache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(this);
        String saveUri = cache.getString("downloaderCustomUri", "");
        if (saveUri.isEmpty()) saveUri = intent.getStringExtra("saveUri");
        final String fSave = saveUri;
        if (fSave == null || fSave.isEmpty()) {
            showToast(getString(R.string.ig_toast_no_download_folder));
            stopSelf(startId);
            return;
        }

        new Thread(() -> {
            int saved = 0;
            int total = urls != null ? urls.length : 0;
            try {
                DocumentFile dir = resolveNestedDir(fSave, subdir);
                pushProgress("Saving chat export…", 0, 100, true);
                // The HTML references media by bare filename (src="0001_photo.jpg"). That only
                // resolves when the page is opened with file:// access — but file managers open it
                // through a content:// URI, where sibling files can't be reached. So as each media is
                // downloaded (and saved to the folder), inline it into the HTML as a base64 data URI,
                // making the page self-contained and render everywhere. Oversized media (large videos)
                // are left as a file reference to keep the HTML openable.
                StringBuilder html = (htmlB64 != null)
                        ? new StringBuilder(new String(android.util.Base64.decode(htmlB64, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8))
                        : null;
                long inlined = 0;
                final long INLINE_EACH_MAX  = 16L * 1024 * 1024; // skip inlining a single file over 16 MB
                final long INLINE_TOTAL_MAX = 48L * 1024 * 1024; // keep the whole HTML openable
                for (int i = 0; i < total; i++) {
                    maybeUpdateProgress("Saving media " + (i + 1) + "/" + total,
                            (int) ((i + 1) * 100L / Math.max(1, total)), 100);
                    File tmp = File.createTempFile("ie_cm_", ".bin", getCacheDir());
                    try {
                        downloadToFile(urls[i], tmp, null);
                        long len = tmp.length();
                        if (len > 0) {
                            writeFileToDir(dir, tmp, names[i], mimes[i]);
                            saved++;
                            if (html != null && len <= INLINE_EACH_MAX && inlined + len <= INLINE_TOTAL_MAX) {
                                String token = "src=\"" + names[i] + "\"";
                                int idx = html.indexOf(token);
                                if (idx >= 0) {
                                    String dataUri = "src=\"data:" + mimes[i] + ";base64,"
                                            + android.util.Base64.encodeToString(readAll(tmp), android.util.Base64.NO_WRAP) + "\"";
                                    html.replace(idx, idx + token.length(), dataUri);
                                    inlined += len;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally { //noinspection ResultOfMethodCallIgnored
                        tmp.delete(); }
                }
                Uri htmlUri = null;
                if (html != null && htmlFilename != null) {
                    pushProgress("Saving chat export…", 99, 100, true);
                    File htmp = File.createTempFile("ie_html_", ".html", getCacheDir());
                    try {
                        try (FileOutputStream fo = new FileOutputStream(htmp)) {
                            fo.write(html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        htmlUri = writeFileToDir(dir, htmp, htmlFilename, "text/html");
                    } finally { //noinspection ResultOfMethodCallIgnored
                        htmp.delete(); }
                }
                final int fSaved = saved;
                ModuleLog.line("(IE|Export|Service) saved html + " + fSaved + "/" + total + " media to " + subdir);
                postDoneNotification(startId, getString(R.string.ig_export_media_saved, fSaved, subdir), "text/html", htmlUri, true);
                showToast(getString(R.string.ig_export_media_saved, fSaved, subdir));
            } catch (Throwable e) {
                ModuleLog.line("(IE|ChatExport|Service) failed: " + e);
                postDoneNotification(startId, "Export failed: " + e.getMessage(), null, null, false);
                showToast(getString(R.string.ig_toast_download_failed, String.valueOf(e.getMessage())));
            } finally {
                stopSelf(startId);
            }
        }, "ie-chatexport-" + startId).start();
    }

    /** Reads a (small) file fully into memory — used to base64-inline media into the export HTML. */
    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Resolves (creating each segment) a nested subfolder like "chats/chat_x" under the SAF tree. */
    private DocumentFile resolveNestedDir(String saveUri, String subPath) throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(saveUri));
        if (dir == null || !dir.canWrite()) {
            throw new Exception("SAF folder not writable — was the permission revoked?");
        }
        if (subPath != null) {
            for (String seg : subPath.split("/")) {
                if (seg.isEmpty()) continue;
                DocumentFile sub = dir.findFile(seg);
                if (sub == null || !sub.isDirectory()) sub = dir.createDirectory(seg);
                if (sub == null) throw new Exception("Cannot create folder: " + seg);
                dir = sub;
            }
        }
        return dir;
    }

    /** Writes src into an already-resolved SAF directory, replacing any existing file of that name. */
    private Uri writeFileToDir(DocumentFile dir, File src, String filename, String mimeType)
            throws Exception {
        DocumentFile existing = dir.findFile(filename);
        if (existing != null) //noinspection ResultOfMethodCallIgnored
            existing.delete();
        DocumentFile file = dir.createFile(mimeType != null ? mimeType : "application/octet-stream", filename);
        if (file == null) throw new Exception("SAF createFile returned null: " + filename);
        try (FileInputStream in = new FileInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(file.getUri())) {
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

    private static String downloadToFile(String url, File dest, ProgressCallback cb)
            throws Exception {
        HttpURLConnection conn = openValidatedConnection(url);
        String contentType = conn.getContentType();
        long total = conn.getContentLengthLong(); // -1 if server doesn't send Content-Length
        if (total > MAX_DOWNLOAD_BYTES) {
            conn.disconnect();
            throw new java.io.IOException("File too large");
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                downloaded += n;
                if (downloaded > MAX_DOWNLOAD_BYTES) throw new java.io.IOException("File too large");
                if (cb != null) cb.onProgress(downloaded, total);
            }
        } finally {
            conn.disconnect();
        }
        return contentType;
    }

    /**
     * Opens {@code url}, following up to 5 redirects manually so that every hop is checked
     * against the CDN allowlist. Returns a connection with a 200 response.
     */
    private static HttpURLConnection openValidatedConnection(String url) throws Exception {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            if (!DownloadRequestValidator.isAllowedMediaUrl(current)) {
                throw new java.io.IOException("Not an Instagram CDN link");
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) return conn;
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (code / 100 != 3 || location == null) throw new java.io.IOException("HTTP " + code);
            current = new URL(new URL(current), location).toString();
        }
        throw new java.io.IOException("Too many redirects");
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
        postDoneNotification(startId, text, mimeType, fileUri, true);
    }

    /** @param ok false only for a real failure (uses the error icon); success uses the download-done
     *  check icon even when there is no file to open, so a finished export never looks like a warning. */
    private void postDoneNotification(int startId, String text, String mimeType, Uri fileUri, boolean ok) {
        int icon = ok
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
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show());
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
