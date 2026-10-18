package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Offline, synthetic-only checks. This suite neither hooks Instagram nor accesses its files. */
@RunWith(AndroidJUnit4.class)
public class NativeDownloadEngineTest {
    private final List<File> files = new ArrayList<>();
    private final DownloadEngine.Cancellation cancellation = new DownloadEngine.Cancellation();

    @After public void cleanTemporaryFiles() { for (File f : files) f.delete(); }
    private File file() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File f = File.createTempFile("ie_fixture_", ".mp4", context.getCacheDir()); files.add(f); return f;
    }
    private File fixture(String name) throws IOException {
        File f = file();
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("dash-fixtures/" + name);
             FileOutputStream out = new FileOutputStream(f)) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        return f;
    }
    private static DownloadRequest.Rendition expected(int width, int height, String codec) {
        return new DownloadRequest.Rendition("https://scontent.cdninstagram.com/synthetic.mp4", width, height, 30, codec, 0);
    }
    private DownloadEngine.Metadata joinAndCheck(String videoName, String audioName, int width, int height, String codec,
            Boolean portable) throws Exception {
        File video = fixture(videoName), audio = audioName == null ? null : fixture(audioName), output = file();
        DownloadRequest.Rendition expected = expected(width, height, codec);
        DownloadEngine.Metadata originalVideo = DownloadEngine.inspect(video, false, expected, cancellation);
        DownloadEngine.Metadata originalAudio = audio == null ? null : DownloadEngine.inspect(audio, true, null, cancellation);
        if (portable == null) assertTrue(DownloadEngine.usesPortableJoin(originalVideo, originalAudio));
        assertTrue(portable == null
                ? DownloadEngine.joinTracks(video, audio, output, originalVideo, originalAudio,
                        cancellation, (phase, done, total) -> { })
                : portable ? Mp4Join.join(video, audio, output, Downloader.SILENT)
                        : DownloadEngine.nativeJoin(video, audio, output, cancellation));
        DownloadEngine.Metadata saved;
        try {
            saved = DownloadEngine.inspect(output, audio != null, expected, cancellation);
        } catch (DownloadDiagnostics.CoverageFailure failure) {
            throw new AssertionError(DownloadDiagnostics.describe(DownloadDiagnostics.Phase.INSPECT_OUTPUT, failure), failure);
        }
        try {
            DownloadEngine.verifyJoined(originalVideo, originalAudio, saved);
        } catch (DownloadEngine.Failure failure) {
            throw new AssertionError(failure.getMessage() + " " + failure.diagnostic, failure);
        }
        List<Packet> sourceVideo = packets(video, "video/"), outputVideo = packets(output, "video/");
        long shift = outputVideo.get(0).pts - sourceVideo.get(0).pts;
        equalPackets(sourceVideo, outputVideo, shift, false);
        if (audio != null) {
            assertEquals("AAC LC fixture codec", "audio/mp4a-latm", originalAudio.audioCodec);
            assertEquals("AAC LC fixture profile", 2, originalAudio.audioProfile);
            assertEquals(originalAudio.audioCodec, saved.audioCodec);
            assertEquals(originalAudio.audioProfile, saved.audioProfile);
            equalPackets(packets(audio, "audio/"), packets(output, "audio/"), shift, true);
        }
        assertEquals(width, saved.width); assertEquals(height, saved.height);
        assertEquals(audio != null, saved.hasAudio);
        assertTrue(saved.durationUs > 0);
        android.util.Log.i("IE-DashFixture", saved.toString());
        return saved;
    }
    @Test public void nativeH264AacPreservesPacketsAndSynchronization() throws Exception {
        assertEquals("video/avc", joinAndCheck("h264.mp4", "aac.mp4", 320, 240, "avc1", false).videoCodec);
    }
    @Test public void audioExtractionSourceCanContainVideoButDashAudioCannot() throws Exception {
        File video = fixture("h264.mp4"), audio = fixture("aac.mp4"), combined = file();
        assertTrue(DownloadEngine.nativeJoin(video, audio, combined, cancellation));
        DownloadEngine.Metadata source = DownloadEngine.inspect(combined, true, null, cancellation, true);
        assertTrue(source.hasAudio);
        assertTrue(source.audioSamples > 0);
        assertTrue(source.videoSamples > 0);
        try {
            DownloadEngine.inspect(combined, true, null, cancellation);
            fail("DASH audio accepted a video track");
        } catch (IOException expected) { }
        try {
            DownloadEngine.inspect(video, true, null, cancellation, true);
            fail("Audio extraction accepted a silent video");
        } catch (IOException expected) { }
    }
    @Test public void nativeHevcAacPreservesPacketsAndSynchronization() throws Exception {
        assertEquals("video/hevc", joinAndCheck("hevc.mp4", "aac.mp4", 320, 240, "hvc1", false).videoCodec);
    }
    @Test public void nativeNegativeCompositionTimesKeepOneSharedAudioVideoOffset() throws Exception {
        joinAndCheck("h264-negative.mp4", "aac.mp4", 320, 240, "avc1", false);
    }
    @Test public void fragmentedSourceWithoutDeclaredDurationUsesCompleteSampleTables() throws Exception {
        DownloadEngine.Metadata metadata = DownloadEngine.inspect(fixture("h264-negative.mp4"), false,
                expected(320, 240, "avc1"), cancellation);
        assertEquals(3_000_000L, metadata.durationUs);
        assertEquals(90, metadata.videoSamples);
    }
    @Test public void sparseVideoIncludesTheFinalFramesDuration() throws Exception {
        DownloadEngine.Metadata metadata = DownloadEngine.inspect(fixture("h264-sparse15s.mp4"), false,
                expected(320, 240, "avc1"), cancellation);
        assertEquals(15_000_000L, metadata.durationUs);
        assertEquals(15, metadata.videoSamples);
        assertEquals(14_000_000L, metadata.videoLastUs - metadata.videoFirstUs);
    }
    @Test public void oneHeldFrameHasItsFullIndexedDuration() throws Exception {
        DownloadEngine.Metadata metadata = DownloadEngine.inspect(fixture("h264-held15s.mp4"), false,
                expected(320, 240, "avc1"), cancellation);
        assertEquals(15_000_000L, metadata.durationUs);
        assertEquals(1, metadata.videoSamples);
        assertEquals(metadata.videoFirstUs, metadata.videoLastUs);
    }
    @Test public void selectedSparseVideoAndAudioKeepAllPacketsAndTiming() throws Exception {
        assertEquals(15, joinAndCheck("h264-sparse15s.mp4", "aac15s.mp4", 320, 240, "avc1", null).videoSamples);
    }
    @Test public void selectedHeldFrameAndAudioKeepFullDuration() throws Exception {
        DownloadEngine.Metadata output = joinAndCheck("h264-held15s.mp4", "aac15s.mp4", 320, 240, "avc1", null);
        assertEquals(1, output.videoSamples);
        assertEquals(15_000_000L, output.durationUs);
    }
    @Test public void selectedSparseVideoWithPrimedAudioPreservesPacketsAndTiming() throws Exception {
        assertEquals(15, joinAndCheck("h264-sparse15s.mp4", "aac-primed15s.mp4", 320, 240, "avc1", null).videoSamples);
    }
    @Test public void portableSparseVideoAndAudioKeepAllPacketsAndTiming() throws Exception {
        assertEquals(15, joinAndCheck("h264-sparse15s.mp4", "aac15s.mp4", 320, 240, "avc1", true).videoSamples);
    }
    @Test public void portableHeldFrameOutputRetainsFullDuration() throws Exception {
        DownloadEngine.Metadata output = joinAndCheck("h264-held15s.mp4", "aac15s.mp4", 320, 240, "avc1", true);
        assertEquals(1, output.videoSamples);
        assertEquals(15_000_000L, output.durationUs);
    }
    @Test public void truncatedHeldFrameCannotPassIndexedVerification() throws Exception {
        File broken = fixture("h264-held15s.mp4");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(broken, "rw")) { f.setLength(f.length() / 2); }
        assertThrows(Exception.class, () -> DownloadEngine.inspect(broken, false, expected(320, 240, "avc1"), cancellation));
    }
    @Test public void nativeRotationIsPreserved() throws Exception {
        int rotation = joinAndCheck("h264-rotated.mp4", "aac.mp4", 320, 240, "avc1", false).rotation;
        // Android uses clockwise orientation; FFmpeg's matrix is counter-clockwise.
        assertTrue("Expected a quarter-turn rotation, got " + rotation, rotation == 90 || rotation == 270);
    }
    @Test public void portableVp9ProducesVerified1080PortraitWithAac() throws Exception {
        assertEquals("video/x-vnd.on2.vp9", joinAndCheck("vp9-1080.mp4", "aac-1s.mp4", 1080, 1920, "vp09", true).videoCodec);
    }
    @Test public void portableAv1IsReadableByTheDeviceExtractor() throws Exception {
        assertEquals("video/av01", joinAndCheck("av1.mp4", "aac.mp4", 320, 240, "av01", true).videoCodec);
    }
    @Test public void silentVideoAndExpectedMissingAudioAreDifferentOutcomes() throws Exception {
        assertFalse(joinAndCheck("h264.mp4", null, 320, 240, "avc1", false).hasAudio);
        File video = fixture("h264.mp4");
        assertThrows(IOException.class, () -> DownloadEngine.inspect(video, true, expected(320, 240, "avc1"), cancellation));
    }
    @Test public void actualDimensionsEnforceCeilingWhenMetadataWasUnknown() throws Exception {
        DownloadEngine.Metadata metadata = DownloadEngine.inspect(fixture("vp9-1080.mp4"), false, expected(0, 0, ""), cancellation);
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.verifySelection(metadata, new DownloadRequest.Options(720, false, false)));
        assertEquals("CEILING", failure.status);
    }
    @Test public void cancellationDoesNotReturnASuccessfulNativeJoin() throws Exception {
        File video = fixture("h264.mp4"), audio = fixture("aac.mp4"), output = file();
        cancellation.cancel();
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.nativeJoin(video, audio, output, cancellation));
        assertEquals("CANCELLED", failure.status);
    }
    @Test public void truncatedFileCannotPassSampleVerification() throws Exception {
        File broken = fixture("h264.mp4");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(broken, "rw")) { f.setLength(f.length() / 2); }
        assertThrows(Exception.class, () -> DownloadEngine.inspect(broken, false, expected(320, 240, "avc1"), cancellation));
    }
    @Test public void truncatedSourceWithoutDeclaredDurationStillFails() throws Exception {
        File broken = fixture("h264-negative.mp4");
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(broken, "rw")) { f.setLength(f.length() / 2); }
        assertThrows(Exception.class, () -> DownloadEngine.inspect(broken, false, expected(320, 240, "avc1"), cancellation));
    }
    @Test public void platformXmlParserAcceptsSupportedManifestAndRejectsDeclarations() {
        String xml = "<MPD><Period><AdaptationSet mimeType='video/mp4'><Representation codecs='avc1' width='320' height='240'>"
                + "<BaseURL>https://scontent.cdninstagram.com/synthetic.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>";
        assertEquals(DashManifest.State.SUPPORTED, DashManifest.parse(xml).state);
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse("<!DOCTYPE MPD SYSTEM 'file:///unread'>" + xml).state);
    }
    private static final class Packet {
        final long pts; final int flags; final byte[] digest;
        Packet(long pts, int flags, byte[] digest) { this.pts = pts; this.flags = flags; this.digest = digest; }
    }
    private static List<Packet> packets(File file, String kind) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath()); int selected = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(kind)) { selected = i; break; }
            }
            assertTrue("Missing synthetic fixture track", selected >= 0); ex.selectTrack(selected);
            List<Packet> packets = new ArrayList<>(); ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            while (true) {
                buffer.clear(); int n = ex.readSampleData(buffer, 0); if (n < 0) break;
                digest.update(buffer.array(), 0, n);
                packets.add(new Packet(ex.getSampleTime(), ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC, digest.digest()));
                if (!ex.advance()) break;
            }
            assertFalse(packets.isEmpty()); return packets;
        } finally { ex.release(); }
    }
    private static void equalPackets(List<Packet> source, List<Packet> output, long sharedShift, boolean aacLc) {
        assertEquals("Sample count", source.size(), output.size());
        for (int i = 0; i < source.size(); i++) {
            assertArrayEquals("Encoded bytes for sample " + i, source.get(i).digest, output.get(i).digest);
            // Android MPEG4Source::fragmentedRead marks only sample 0 of a fragment as sync.
            // Its plain-MP4 path marks every AAC-LC sample as sync. This is extractor metadata
            // normalization, not a byte/timing change. Keep video flags exact, and require the
            // correct all-sync AAC-LC output rather than dropping sync verification altogether.
            assertEquals("Sync flags for sample " + i, aacLc ? MediaExtractor.SAMPLE_FLAG_SYNC : source.get(i).flags,
                    output.get(i).flags);
            assertEquals("Presentation time for sample " + i, source.get(i).pts + sharedShift, output.get(i).pts, 2500);
        }
    }
}
