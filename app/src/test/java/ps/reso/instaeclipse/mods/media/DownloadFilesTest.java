package ps.reso.instaeclipse.mods.media;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import static org.junit.Assert.*;

public class DownloadFilesTest {
    @Test public void copyChecksCancellationBetweenWrites() throws Exception {
        File file = fixture(100000);
        DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length); cancel.cancel();
            }
        };
        try {
            try { DownloadFiles.copy(file, output, cancel); fail("cancelled copy succeeded"); }
            catch (IOException expected) { assertEquals("Download cancelled", expected.getMessage()); }
            assertTrue(output.size() < file.length());
        } finally { file.delete(); }
    }
    @Test public void copyCompletesExactBytesAndRejectsEmptySource() throws Exception {
        File file = fixture(12345);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DownloadFiles.copy(file, output, new DownloadEngine.Cancellation());
            assertEquals(12345, output.size());
            for (byte b : output.toByteArray()) assertEquals(7, b);
            new FileOutputStream(file).close();
            try { DownloadFiles.copy(file, output, null); fail("empty source succeeded"); }
            catch (IOException expected) { assertEquals("Incomplete file copy", expected.getMessage()); }
        } finally { file.delete(); }
    }
    @Test public void namesCannotEscapeTheirDestinationFolder() {
        assertEquals("unknown", DownloadFiles.segment("..", 64));
        assertEquals(".._.._payload", DownloadFiles.segment("../../payload", 64));
        assertEquals("abcdef", DownloadFiles.segment("abcdefghi", 6));
    }
    @Test public void explicitRetryKeepsOriginalIdentityOptionsAndCandidates() {
        DownloadRequest original = new DownloadRequest("selected", "owner", "fixed.mp4", "<MPD/>",
                Arrays.asList(new DownloadRequest.Rendition("https://cdninstagram.com/fixture.mp4", 1080, 1920, 30, "avc1", 4000000)),
                new DownloadRequest.Options(1080, true, false), DownloadRequest.AudioExpectation.PRESENT);
        DownloadRequest retry = SelectedMediaDownloads.allowFallback(original);
        assertEquals(original.mediaId, retry.mediaId);
        assertEquals(original.filename, retry.filename);
        assertEquals(original.manifest, retry.manifest);
        assertEquals(original.direct.get(0).url, retry.direct.get(0).url);
        assertEquals(1080, retry.options.maxShortSide);
        assertTrue(retry.options.compatibility);
        assertTrue(retry.options.allowFallback);
        assertFalse(original.options.allowFallback);
        assertEquals(original.audio, retry.audio);
    }
    private static File fixture(int count) throws IOException {
        File file = File.createTempFile("ie-copy-test-", ".bin");
        byte[] bytes = new byte[count]; Arrays.fill(bytes, (byte) 7);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return file;
    }
}
