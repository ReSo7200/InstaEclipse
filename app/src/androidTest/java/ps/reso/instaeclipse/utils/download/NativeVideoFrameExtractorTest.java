package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Offline synthetic assets only. No Instagram, network, gallery publication or private media. */
@RunWith(AndroidJUnit4.class)
public class NativeVideoFrameExtractorTest {
    private final List<File> temporary = new ArrayList<>();
    private final DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();

    @After public void removeFixtureCopies() { for (File file : temporary) file.delete(); }
    private File file(String extension) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("ie_frame_fixture_", extension, context.getCacheDir());
        temporary.add(file); return file;
    }
    private File fixture(String name) throws Exception {
        File file = file(".mp4");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("dash-fixtures/" + name);
             FileOutputStream output = new FileOutputStream(file)) {
            byte[] bytes = new byte[32768]; int read;
            while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
        }
        return file;
    }
    private DownloadEngine.Metadata inspect(File video) throws Exception {
        return DownloadEngine.inspect(video, false,
                new DownloadRequest.Rendition("https://scontent.cdninstagram.com/synthetic.mp4", 0, 0, 0, "", 0), cancel);
    }

    private void assertPng(String asset, int width, int height) throws Exception {
        File video = fixture(asset), png = file(".png");
        DownloadEngine.Metadata metadata = inspect(video);
        Bitmap preview = null, decoded = null, reference = null;
        try (VideoFrameExtractor extractor = VideoFrameExtractor.open(video, metadata, cancel)) {
            preview = extractor.preview(300_000, cancel);
            assertTrue(preview.getWidth() <= 960 && preview.getHeight() <= 960);
            assertEquals("Preview must not use RGB_565", Bitmap.Config.ARGB_8888, preview.getConfig());
            VideoFrameExtractor.SavedFrame saved = extractor.savePng(300_000, png, cancel);
            assertEquals(width, saved.width); assertEquals(height, saved.height);
            assertEquals(300_000, saved.timeUs); assertEquals(png.length(), saved.bytes); assertTrue(saved.bytes > 45);
            decoded = BitmapFactory.decodeFile(png.getAbsolutePath());
            assertNotNull(decoded); assertEquals(width, decoded.getWidth()); assertEquals(height, decoded.getHeight());
            assertEquals(Bitmap.Config.ARGB_8888, decoded.getConfig());
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
                    params.setPreferredConfig(Bitmap.Config.ARGB_8888);
                    reference = retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST, params);
                    assertEquals(Bitmap.Config.ARGB_8888, params.getActualConfig());
                } else {
                    reference = retriever.getFrameAtTime(300_000, MediaMetadataRetriever.OPTION_CLOSEST);
                }
            } finally { retriever.release(); }
            assertNotNull(reference);
            assertEquals("Full decode must not use RGB_565", Bitmap.Config.ARGB_8888, reference.getConfig());
            // Compare decoded colors row by row without allocating another full-frame pixel array.
            int[] expectedRow = new int[width], actualRow = new int[width];
            for (int y = 0; y < height; y++) {
                reference.getPixels(expectedRow, 0, width, 0, y, width, 1);
                decoded.getPixels(actualRow, 0, width, 0, y, width, 1);
                assertArrayEquals("PNG colors at row " + y, expectedRow, actualRow);
            }
        } finally {
            if (preview != null) preview.recycle();
            if (decoded != null) decoded.recycle();
            if (reference != null) reference.recycle();
        }
    }

    @Test public void h264FrameExportsExactDecodedPixels() throws Exception { assertPng("h264.mp4", 320, 240); }
    @Test public void hevcFrameExportsExactDecodedPixels() throws Exception { assertPng("hevc.mp4", 320, 240); }
    @Test public void av1FrameExportsExactDecodedPixels() throws Exception { assertPng("av1.mp4", 320, 240); }
    @Test public void portraitVp9PreviewIsBoundedButPngIsFull1080() throws Exception { assertPng("vp9-1080.mp4", 1080, 1920); }
    @Test public void rotatedVideoExportsItsDisplayDimensions() throws Exception { assertPng("h264-rotated.mp4", 240, 320); }

    @Test public void timestampRejectsNegativeAndClampsPastEnd() throws Exception {
        File video = fixture("h264.mp4"); DownloadEngine.Metadata metadata = inspect(video);
        try (VideoFrameExtractor extractor = VideoFrameExtractor.open(video, metadata, cancel)) {
            assertEquals("TIME", assertThrows(VideoFrameExtractor.FrameFailure.class, () -> extractor.preview(-1, cancel)).code);
            VideoFrameExtractor.SavedFrame saved = extractor.savePng(Long.MAX_VALUE, file(".png"), cancel);
            assertEquals(metadata.durationUs - 1, saved.timeUs);
        }
    }

    @Test public void cancellationNeverLeavesAPngAndClosedSessionCannotDecode() throws Exception {
        File video = fixture("h264.mp4"), png = file(".png"); DownloadEngine.Metadata metadata = inspect(video);
        VideoFrameExtractor extractor = VideoFrameExtractor.open(video, metadata, cancel);
        cancel.cancel();
        assertEquals("CANCELLED", assertThrows(VideoFrameExtractor.FrameFailure.class, () -> extractor.savePng(0, png, cancel)).code);
        assertFalse(png.exists());
        extractor.close(); extractor.close();
        assertEquals("CLOSED", assertThrows(VideoFrameExtractor.FrameFailure.class,
                () -> extractor.preview(0, new DownloadEngine.Cancellation())).code);
    }

    @Test public void decoderCannotSilentlyUpscaleAnUnexpectedlySmallFrame() throws Exception {
        File video = fixture("h264.mp4"), png = file(".png");
        DownloadEngine.Metadata metadata = inspect(video);
        DownloadEngine.Metadata wrong = new DownloadEngine.Metadata(640, 480, 0, metadata.durationUs, metadata.videoCodec, "", 0);
        try (VideoFrameExtractor extractor = VideoFrameExtractor.open(video, wrong, cancel)) {
            assertEquals("DIMENSIONS", assertThrows(VideoFrameExtractor.FrameFailure.class, () -> extractor.savePng(0, png, cancel)).code);
            assertFalse(png.exists());
        }
    }

    @Test public void unreasonableExportMemoryIsRefusedBeforeDecoding() throws Exception {
        File video = fixture("h264.mp4"), png = file(".png");
        DownloadEngine.Metadata metadata = new DownloadEngine.Metadata(Integer.MAX_VALUE, Integer.MAX_VALUE, 0,
                3_000_000, "video/avc", "", 0);
        try (VideoFrameExtractor extractor = VideoFrameExtractor.open(video, metadata, cancel)) {
            assertEquals("MEMORY", assertThrows(VideoFrameExtractor.FrameFailure.class, () -> extractor.savePng(0, png, cancel)).code);
            assertFalse(png.exists());
        }
    }

    @Test public void existingNonemptyDestinationIsPreserved() throws Exception {
        File video = fixture("h264.mp4"), png = file(".png");
        try (FileOutputStream output = new FileOutputStream(png)) { output.write(new byte[] {1, 2, 3}); }
        try (VideoFrameExtractor extractor = VideoFrameExtractor.open(video, inspect(video), cancel)) {
            assertEquals("DESTINATION", assertThrows(VideoFrameExtractor.FrameFailure.class, () -> extractor.savePng(0, png, cancel)).code);
            assertEquals(3, png.length());
        }
    }
}
