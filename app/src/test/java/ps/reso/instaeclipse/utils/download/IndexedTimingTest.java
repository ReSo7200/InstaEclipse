package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class IndexedTimingTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private File source(int count) throws IOException {
        FragmentedMp4ForTests fixture = FragmentedMp4ForTests.picture("avc1", 320, 240, 1000);
        FragmentedMp4ForTests.Fragment fragment = fixture.fragment(0L);
        for (int i = 0; i < count; i++) fragment.add(300 + i, 15000 / count, FragmentedMp4ForTests.SYNC, 0);
        return write(fixture.build());
    }
    private File write(byte[] bytes) throws IOException {
        File file = temp.newFile(); Files.write(file.toPath(), bytes); return file;
    }
    private File plain(File source) throws IOException {
        File file = temp.newFile(); assertTrue(Mp4Join.join(source, null, file, Downloader.SILENT)); return file;
    }
    private static int typeAt(byte[] bytes, String type) {
        byte[] wanted = type.getBytes(StandardCharsets.US_ASCII);
        for (int i = 4; i <= bytes.length - 4; i++) {
            if (bytes[i] == wanted[0] && bytes[i + 1] == wanted[1] && bytes[i + 2] == wanted[2] && bytes[i + 3] == wanted[3]) return i;
        }
        throw new AssertionError("Fixture box missing: " + type);
    }

    @Test public void sparseAndHeldFramesProveTheirWholeDurationInFragmentsAndPlainOutput() throws Exception {
        for (int count : new int[] {15, 1}) {
            File source = source(count), plain = plain(source);
            for (File file : new File[] {source, plain}) {
                assertEquals(15_000_000L, Mp4Join.indexedDurationUs(file, true, count));
                assertEquals(15_000_000L, DownloadEngine.verifyCoverage(file, true, 15_000_000, 0,
                        count == 1 ? 0 : 14_000_000, count));
                assertEquals(15_000_000L, DownloadEngine.verifyCoverage(file, true, 0, 0, 0, count));
            }
        }
    }

    @Test public void indexedProofNeverEnablesPlainInputJoining() throws Exception {
        File plain = plain(source(15));
        assertThrows(IOException.class, () -> Mp4Join.join(plain, null, temp.newFile(), Downloader.SILENT));
    }

    @Test public void selectedRoutePreservesSparseAndHeldAvcTimingWithoutNativeEstimates() throws Exception {
        for (int count : new int[] {15, 1}) {
            File source = source(count), output = temp.newFile();
            DownloadEngine.Metadata metadata = video("video/avc", 15_000_000, count == 1 ? 0 : 14_000_000, count);
            assertTrue(DownloadEngine.usesPortableJoin(metadata, null));
            assertTrue(DownloadEngine.joinTracks(source, null, output, metadata, null,
                    new DownloadEngine.Cancellation(), (phase, done, total) -> {}));
            assertEquals(15_000_000L, Mp4Join.indexedDurationUs(output, true, count));
            assertEquals(count, PlainMp4ForTests.read(Files.readAllBytes(output.toPath())).tracks.get(0).count());
        }
    }

    @Test public void routeKeepsDenseAvcHevcNativeAndVp9Av1Portable() {
        assertFalse(DownloadEngine.usesPortableJoin(video("video/avc", 3_000_000, 2_966_667, 90), null));
        assertFalse(DownloadEngine.usesPortableJoin(video("video/hevc", 3_000_000, 2_966_667, 90), null));
        assertTrue(DownloadEngine.usesPortableJoin(video("video/hevc", 15_000_000, 14_000_000, 15), null));
        assertTrue(DownloadEngine.usesPortableJoin(video("video/x-vnd.on2.vp9", 3_000_000, 2_966_667, 90), null));
        assertTrue(DownloadEngine.usesPortableJoin(video("video/av01", 3_000_000, 2_966_667, 90), null));
    }

    private static DownloadEngine.Metadata video(String codec, long duration, long last, int count) {
        return new DownloadEngine.Metadata(320, 240, 0, duration, codec, "", 0,
                0, 0, last, 0, 0, count, 0);
    }

    @Test public void omittedSamplesAndInflatedDeclaredDurationStillFail() throws Exception {
        for (File file : new File[] {source(15), plain(source(15))}) {
            assertThrows(IOException.class, () -> DownloadEngine.verifyCoverage(file, true, 15_000_000, 0, 13_000_000, 14));
            assertThrows(IOException.class, () -> DownloadEngine.verifyCoverage(file, true, 30_000_000, 0, 14_000_000, 15));
            assertThrows(IOException.class, () -> Mp4Join.indexedDurationUs(file, true, 16));
            assertThrows(IOException.class, () -> Mp4Join.indexedDurationUs(file, true, 0));
        }
    }

    @Test public void truncatedLastSampleFailsEvenWhenDeclaredCountMatches() throws Exception {
        for (int count : new int[] {15, 1}) {
            for (File file : new File[] {source(count), plain(source(count))}) {
                try (RandomAccessFile truncated = new RandomAccessFile(file, "rw")) { truncated.setLength(truncated.length() - 1); }
                assertThrows(IOException.class, () -> Mp4Join.indexedDurationUs(file, true, count));
            }
        }
    }

    @Test public void missingFragmentGapsCannotBeInventedAsHeldSamples() throws Exception {
        FragmentedMp4ForTests fixture = FragmentedMp4ForTests.picture("avc1", 320, 240, 1000);
        for (long start : new long[] {0, 10000}) {
            FragmentedMp4ForTests.Fragment fragment = fixture.fragment(start);
            for (int i = 0; i < 5; i++) fragment.add(300, 1000, FragmentedMp4ForTests.SYNC, 0);
        }
        File gap = write(fixture.build());
        assertThrows(IOException.class, () -> DownloadEngine.verifyCoverage(gap, true, 15_000_000, 0, 14_000_000, 10));
        fixture.fragments.remove(0);
        File prefixMissing = write(fixture.build());
        assertThrows(IOException.class, () -> DownloadEngine.verifyCoverage(prefixMissing, true, 15_000_000, 10_000_000, 14_000_000, 5));
    }

    @Test public void plainTimingCountsSizesAndChunkMappingsMustAgree() throws Exception {
        byte[] original = Files.readAllBytes(plain(source(15)).toPath());
        String[] boxes = {"stts", "stsz", "stsc", "stco"};
        int[] offsets = {12, 12, 12, 12};
        int[] values = {14, 2_000_001, 2, 0};
        for (int i = 0; i < boxes.length; i++) {
            byte[] broken = original.clone();
            ByteBuffer.wrap(broken).putInt(typeAt(broken, boxes[i]) + offsets[i], values[i]);
            File file = write(broken);
            assertThrows(boxes[i], IOException.class, () -> Mp4Join.indexedDurationUs(file, true, 15));
        }
    }

    @Test public void plainWideChunkOffsetsAreCheckedInsideMediaData() throws Exception {
        byte[] narrow = Files.readAllBytes(plain(source(1)).toPath());
        int at = typeAt(narrow, "stco");
        byte[] wide = new byte[narrow.length + 4];
        System.arraycopy(narrow, 0, wide, 0, at + 12);
        System.arraycopy(narrow, at + 16, wide, at + 20, narrow.length - at - 16);
        ByteBuffer b = ByteBuffer.wrap(wide);
        b.putInt(at - 4, b.getInt(at - 4) + 4);
        b.putInt(at, 0x636f3634);
        b.putLong(at + 12, (ByteBuffer.wrap(narrow).getInt(at + 12) & 0xffffffffL) + 4);
        for (String parent : new String[] {"moov", "trak", "mdia", "minf", "stbl"}) {
            int header = typeAt(wide, parent) - 4; b.putInt(header, b.getInt(header) + 4);
        }
        assertEquals(15_000_000L, Mp4Join.indexedDurationUs(write(wide), true, 1));
        b.putLong(at + 12, wide.length - 1);
        assertThrows(IOException.class, () -> Mp4Join.indexedDurationUs(write(wide), true, 1));
    }

    @Test public void boundedCompositionTimingAndEditsMatchFragmentAndPlainProof() throws Exception {
        FragmentedMp4ForTests fixture = FragmentedMp4ForTests.picture("avc1", 320, 240, 1000);
        fixture.edits = new long[][] {{1000, -1, 0x00010000}, {0, 0, 0x00010000}};
        FragmentedMp4ForTests.Fragment fragment = fixture.fragment(0L);
        fragment.fields |= FragmentedMp4ForTests.OFFSET;
        fragment.add(300, 1000, FragmentedMp4ForTests.SYNC, 1000);
        fragment.add(301, 1000, FragmentedMp4ForTests.SYNC, 0);
        File source = write(fixture.build()), plain = plain(source);
        assertEquals(Mp4Join.indexedDurationUs(source, true, 2), Mp4Join.indexedDurationUs(plain, true, 2));
        byte[] broken = Files.readAllBytes(plain.toPath());
        ByteBuffer.wrap(broken).putInt(typeAt(broken, "ctts") + 12, 3);
        assertThrows(IOException.class, () -> Mp4Join.indexedDurationUs(write(broken), true, 2));
    }
}
