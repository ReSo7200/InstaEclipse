package ps.reso.instaeclipse.utils.download;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Worker-only frame decoding from an already verified local video. No network or publication.
 * PNG preserves the decoded ARGB_8888 frame, not the video's original encoded samples. Platform
 * decoding may tone-map HDR or reduce higher bit depth; original HDR/color fidelity is not promised.
 */
public final class VideoFrameExtractor implements AutoCloseable {
    private static final int PREVIEW_EDGE = 960;
    private static final long MAX_EXPORT_PIXELS = 64L * 1024 * 1024;
    private static final long MEMORY_RESERVE = 16L * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] PNG_END = {0, 0, 0, 0, 73, 69, 78, 68, (byte)174, 66, 96, (byte)130};
    private final MediaMetadataRetriever retriever;
    private final long durationUs;
    private final int displayWidth, displayHeight;
    private boolean closed;

    public static final class FrameFailure extends IOException {
        public final String code;
        private FrameFailure(String code, String message) { super(message); this.code = code; }
    }

    public static final class SavedFrame {
        public final long timeUs, bytes;
        public final int width, height;
        private SavedFrame(long timeUs, long bytes, int width, int height) {
            this.timeUs = timeUs; this.bytes = bytes; this.width = width; this.height = height;
        }
    }

    private VideoFrameExtractor(MediaMetadataRetriever retriever, DownloadEngine.Metadata metadata) {
        this.retriever = retriever;
        this.durationUs = metadata.durationUs;
        int rotation = Math.floorMod(metadata.rotation, 360);
        this.displayWidth = rotation == 90 || rotation == 270 ? metadata.height : metadata.width;
        this.displayHeight = rotation == 90 || rotation == 270 ? metadata.width : metadata.height;
    }

    /** The caller must serialize operations on its worker and close the helper there. */
    public static VideoFrameExtractor open(File verifiedVideo, DownloadEngine.Metadata metadata,
            DownloadEngine.Cancellation cancel) throws FrameFailure {
        worker(); checkCancelled(cancel);
        if (verifiedVideo == null || !verifiedVideo.isFile() || verifiedVideo.length() <= 0
                || metadata == null || metadata.width <= 0 || metadata.height <= 0 || metadata.durationUs <= 0
                || metadata.videoCodec == null || !metadata.videoCodec.startsWith("video/")
                || Math.floorMod(metadata.rotation, 90) != 0)
            throw fail("INPUT", "A verified local video is required");
        MediaMetadataRetriever retriever = null;
        boolean opened = false;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(verifiedVideo.getAbsolutePath());
            checkCancelled(cancel);
            VideoFrameExtractor result = new VideoFrameExtractor(retriever, metadata);
            opened = true;
            return result;
        } catch (FrameFailure failure) { throw failure;
        } catch (OutOfMemoryError error) { throw fail("MEMORY", "Not enough memory to open this video");
        } catch (RuntimeException error) { throw fail("DECODE", "This video could not be opened for frame decoding");
        } finally {
            if (!opened && retriever != null) releaseQuietly(retriever);
        }
    }

    /** Returns a display-oriented preview at most 960 pixels on either edge; caller recycles it. */
    public synchronized Bitmap preview(long timeUs, DownloadEngine.Cancellation cancel) throws FrameFailure {
        worker(); ensureOpen(); checkCancelled(cancel);
        long timestamp = timestamp(timeUs);
        double scale = Math.min(1.0, PREVIEW_EDGE / (double)Math.max(displayWidth, displayHeight));
        int width = Math.max(1, (int)Math.floor(displayWidth * scale));
        int height = Math.max(1, (int)Math.floor(displayHeight * scale));
        Bitmap bitmap = null;
        boolean returned = false;
        try {
            bitmap = decode(timestamp, width, height);
            checkCancelled(cancel);
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0
                    || bitmap.getWidth() > PREVIEW_EDGE || bitmap.getHeight() > PREVIEW_EDGE)
                throw fail("DECODE", "A bounded preview frame could not be decoded");
            requireArgb8888(bitmap);
            returned = true;
            return bitmap;
        } catch (FrameFailure failure) { throw failure;
        } catch (OutOfMemoryError error) { throw fail("MEMORY", "Not enough memory to decode the preview");
        } catch (RuntimeException error) { throw fail("DECODE", "The preview frame could not be decoded");
        } finally {
            if (!returned && bitmap != null) bitmap.recycle();
        }
    }

    /** Writes a full display-resolution PNG to an absent or empty file; deletes partial output. */
    public synchronized SavedFrame savePng(long timeUs, File destination, DownloadEngine.Cancellation cancel)
            throws FrameFailure {
        worker(); ensureOpen();
        long timestamp = timestamp(timeUs);
        if (destination == null || (destination.exists() && (!destination.isFile() || destination.length() != 0)))
            throw fail("DESTINATION", "The frame destination must be an empty temporary file");
        Bitmap bitmap = null;
        boolean complete = false;
        try {
            checkCancelled(cancel);
            guardFullFrameMemory();
            bitmap = decode(timestamp, 0, 0);
            checkCancelled(cancel);
            if (bitmap == null) throw fail("DECODE", "The full-resolution frame could not be decoded");
            requireArgb8888(bitmap);
            // The retriever applies the video's display rotation. Never upscale a decoder result.
            if (bitmap.getWidth() != displayWidth || bitmap.getHeight() != displayHeight)
                throw fail("DIMENSIONS", "The decoder did not return the video's full display resolution");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    throw fail("WRITE", "The frame could not be encoded as PNG");
                output.flush();
                output.getFD().sync();
            }
            checkCancelled(cancel);
            verifyPng(destination);
            checkCancelled(cancel);
            complete = true;
            return new SavedFrame(timestamp, destination.length(), displayWidth, displayHeight);
        } catch (FrameFailure failure) { throw failure;
        } catch (OutOfMemoryError error) { throw fail("MEMORY", "Not enough memory to export a full-resolution frame");
        } catch (IOException error) { throw fail("WRITE", "The PNG frame could not be written completely");
        } catch (RuntimeException error) { throw fail("DECODE", "The frame could not be decoded or encoded");
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (!complete) destination.delete();
        }
    }

    private Bitmap decode(long timeUs, int width, int height) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
            params.setPreferredConfig(Bitmap.Config.ARGB_8888);
            return width > 0
                    ? retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height, params)
                    : retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, params);
        }
        // API28/29 lack the time-based BitmapParams overload. Verify the actual returned config;
        // converting RGB_565 afterward would not restore the lost color precision.
        return width > 0
                ? retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                : retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    private static void requireArgb8888(Bitmap bitmap) throws FrameFailure {
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888)
            throw fail("COLOR", "The decoder did not return the required 8-bit color frame");
    }

    private long timestamp(long requested) throws FrameFailure {
        if (requested < 0) throw fail("TIME", "The frame time must be nonnegative");
        return Math.min(requested, durationUs - 1);
    }

    private void guardFullFrameMemory() throws FrameFailure {
        long pixels = (long)displayWidth * displayHeight;
        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        if (pixels <= 0 || pixels > MAX_EXPORT_PIXELS || pixels > (Long.MAX_VALUE - MEMORY_RESERVE) / 8
                || pixels * 8 + MEMORY_RESERVE > available || pixels * 4 > runtime.maxMemory() / 3)
            throw fail("MEMORY", "This full-resolution frame exceeds the safe memory budget");
    }

    private void verifyPng(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 45) throw fail("PNG_VERIFY", "The PNG frame is incomplete");
            byte[] start = new byte[PNG_SIGNATURE.length], end = new byte[PNG_END.length];
            input.readFully(start);
            input.seek(input.length() - end.length); input.readFully(end);
            if (!Arrays.equals(start, PNG_SIGNATURE) || !Arrays.equals(end, PNG_END))
                throw fail("PNG_VERIFY", "The PNG frame is incomplete");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth != displayWidth || bounds.outHeight != displayHeight || !"image/png".equals(bounds.outMimeType))
            throw fail("PNG_VERIFY", "The PNG frame dimensions could not be verified");
    }

    private void ensureOpen() throws FrameFailure {
        if (closed) throw fail("CLOSED", "The video frame session is closed");
    }
    private static void worker() throws FrameFailure {
        if (Looper.myLooper() == Looper.getMainLooper()) throw fail("THREAD", "Frame decoding must run on a worker thread");
    }
    private static void checkCancelled(DownloadEngine.Cancellation cancel) throws FrameFailure {
        if (cancel != null && cancel.isCancelled()) throw fail("CANCELLED", "Frame extraction cancelled");
    }
    private static FrameFailure fail(String code, String message) { return new FrameFailure(code, message); }
    private static void releaseQuietly(MediaMetadataRetriever retriever) {
        try { retriever.release(); } catch (IOException | RuntimeException ignored) { }
    }

    @Override public synchronized void close() throws FrameFailure {
        worker();
        if (closed) return;
        closed = true;
        try { retriever.release(); }
        catch (IOException | RuntimeException error) { throw fail("DECODE", "The frame decoder could not be released cleanly"); }
    }
}
