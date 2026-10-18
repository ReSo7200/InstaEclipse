package ps.reso.instaeclipse.mods.media;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.*;

public class FrameSessionCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void closingCancelledSessionRemovesOnlyItsTemporaryFiles() throws Exception {
        File cache = temporary.newFolder();
        File unrelated = new File(cache, "unrelated.bin");
        Files.write(unrelated.toPath(), new byte[] {9});
        File sessionDirectory;
        try {
            try (FrameSessionCache.Session session = FrameSessionCache.create(cache)) {
                sessionDirectory = session.directory();
                Files.write(new File(sessionDirectory, "video.part").toPath(), new byte[] {1, 2});
                Files.write(new File(sessionDirectory, "frame.jpg").toPath(), new byte[] {3});
                throw new IOException("fixture cancellation");
            }
        } catch (IOException expected) { assertEquals("fixture cancellation", expected.getMessage()); }
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(unrelated.toPath()));
        assertEquals(0, new File(cache, "ie_frames").list().length);
    }

    @Test public void startupCleanupPreservesActiveSessionsAcrossCanonicalAliases() throws Exception {
        File cache = temporary.newFolder();
        try (FrameSessionCache.Session first = FrameSessionCache.create(cache);
             FrameSessionCache.Session second = FrameSessionCache.create(new File(cache, "."))) {
            File data = new File(first.directory(), "active.mp4");
            Files.write(data.toPath(), new byte[] {5});
            assertNotEquals(first.directory(), second.directory());
            assertTrue(FrameSessionCache.cleanupOrphans(cache));
            assertTrue(FrameSessionCache.cleanupOrphans(new File(cache, ".")));
            assertTrue(data.isFile());
            assertTrue(second.directory().isDirectory());
            first.close();
            first.close();
            assertFalse(first.directory().exists());
            assertTrue(second.directory().isDirectory());
        }
    }

    @Test public void startupRemovesMarkedOrphansButLeavesUnownedSessionNames() throws Exception {
        File cache = temporary.newFolder();
        File orphan = orphan(cache);
        Files.write(new File(orphan, "interrupted.mp4").toPath(), new byte[] {1});
        File unmarked = new File(orphan.getParentFile(), "session-" + UUID.randomUUID());
        assertTrue(unmarked.mkdir());
        File privateFile = new File(unmarked, "keep.bin");
        Files.write(privateFile.toPath(), new byte[] {6});
        assertFalse(FrameSessionCache.cleanupOrphans(cache));
        assertFalse(orphan.exists());
        assertArrayEquals(new byte[] {6}, Files.readAllBytes(privateFile.toPath()));
    }

    @Test public void unexpectedNestedDirectoriesAreNeverTraversedOrPartiallyDeleted() throws Exception {
        File cache = temporary.newFolder();
        File orphan = orphan(cache);
        File nested = new File(orphan, "nested");
        assertTrue(nested.mkdir());
        File keep = new File(nested, "keep.bin");
        Files.write(keep.toPath(), new byte[] {7});
        assertFalse(FrameSessionCache.cleanupOrphans(cache));
        assertTrue(new File(orphan, ".ie_frame_session").isFile());
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(keep.toPath()));
    }

    @Test public void oversizedSessionFailsClosedBeforeDeletingItsMarkerOrFiles() throws Exception {
        File cache = temporary.newFolder();
        File orphan = orphan(cache);
        for (int i = 0; i < 128; i++) Files.createFile(new File(orphan, "fixture-" + i).toPath());
        assertFalse(FrameSessionCache.cleanupOrphans(cache));
        assertEquals(129, orphan.list().length);
    }

    @Test public void rootFileAndOutsideCacheDataArePreserved() throws Exception {
        File cache = temporary.newFolder();
        File root = new File(cache, "ie_frames");
        Files.write(root.toPath(), new byte[] {8});
        assertFalse(FrameSessionCache.cleanupOrphans(cache));
        try { FrameSessionCache.create(cache); fail("Accepted non-directory root"); }
        catch (IOException expected) { }
        assertArrayEquals(new byte[] {8}, Files.readAllBytes(root.toPath()));
    }

    private static File orphan(File cache) throws IOException {
        File root = new File(cache, "ie_frames");
        if (!root.isDirectory() && !root.mkdir()) throw new IOException("Fixture root unavailable");
        File orphan = new File(root, "session-" + UUID.randomUUID());
        Files.createDirectory(orphan.toPath());
        Files.createFile(new File(orphan, ".ie_frame_session").toPath());
        return orphan;
    }
}
