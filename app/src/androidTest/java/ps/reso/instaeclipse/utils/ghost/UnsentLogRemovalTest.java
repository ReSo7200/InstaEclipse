package ps.reso.instaeclipse.utils.ghost;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic entries and a fresh test-cache file only; never loads or mutates real history. */
@RunWith(AndroidJUnit4.class)
public class UnsentLogRemovalTest {
    private File folder, destination;

    @Before public void prepare() throws Exception {
        folder = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "ie_unsent_fixture_").toFile();
        destination = new File(folder, "fixture.json");
    }
    @After public void cleanup() {
        if (folder == null) return;
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) for (File child : children) assertTrue(child.delete());
            }
            assertTrue(file.delete());
        }
        assertTrue(folder.delete());
    }

    @Test public void removesOnlySnapshotIdentityEvenWhenEveryStoredFieldMatches() throws Exception {
        UnsentLog.Entry selected = entry(1, "thread", "same text");
        UnsentLog.Entry identical = entry(1, "thread", "same text");
        UnsentLog.Entry otherThread = entry(2, "other-thread", "same text");
        List<UnsentLog.Entry> entries = new ArrayList<>(List.of(selected, identical, otherThread));
        UnsentLog.writeSnapshot(destination, entries);
        assertEquals(UnsentLog.RemovalResult.REMOVED, UnsentLog.removeEntryFrom(entries, selected, destination));
        assertEquals(2, entries.size());
        assertSame(identical, entries.get(0));
        assertSame(otherThread, entries.get(1));
        JSONArray persisted = disk();
        assertEquals(2, persisted.length());
        assertEquals(1, persisted.getJSONObject(0).getLong("t"));
        assertEquals("thread", persisted.getJSONObject(0).getString("th"));
        assertEquals("fixture label", persisted.getJSONObject(0).getString("s"));
        assertEquals("same text", persisted.getJSONObject(0).getString("m"));
        assertEquals("other-thread", persisted.getJSONObject(1).getString("th"));
    }

    @Test public void staleOrRecreatedSnapshotCannotRemoveAReplacementWithMatchingText() throws Exception {
        UnsentLog.Entry current = entry(1, "thread", "same text");
        List<UnsentLog.Entry> entries = new ArrayList<>(List.of(current));
        UnsentLog.writeSnapshot(destination, entries);
        byte[] before = Files.readAllBytes(destination.toPath());
        assertEquals(UnsentLog.RemovalResult.STALE,
                UnsentLog.removeEntryFrom(entries, entry(1, "thread", "same text"), destination));
        assertEquals(UnsentLog.RemovalResult.STALE, UnsentLog.removeEntryFrom(entries, null, destination));
        assertSame(current, entries.get(0));
        assertArrayEquals(before, Files.readAllBytes(destination.toPath()));
    }

    @Test public void atomicWriteFailurePreservesBothEntriesAndExistingFile() throws Exception {
        UnsentLog.Entry selected = entry(1, "thread", "selected fixture");
        UnsentLog.Entry retained = entry(2, "thread", "retained fixture");
        List<UnsentLog.Entry> entries = new ArrayList<>(List.of(selected, retained));
        UnsentLog.writeSnapshot(destination, entries);
        byte[] before = Files.readAllBytes(destination.toPath());
        // A nonempty directory at AtomicFile's reserved staging path forces startWrite to fail.
        File staging = new File(destination.getPath() + ".new");
        assertTrue(staging.mkdir());
        Files.write(new File(staging, "fixture-sentinel").toPath(), new byte[]{1});
        assertEquals(UnsentLog.RemovalResult.FAILED, UnsentLog.removeEntryFrom(entries, selected, destination));
        assertEquals(2, entries.size());
        assertSame(selected, entries.get(0));
        assertSame(retained, entries.get(1));
        assertArrayEquals(before, Files.readAllBytes(destination.toPath()));
    }

    @Test public void removingFinalEntryWritesEmptyJsonAndRepeatedCallbackIsStale() throws Exception {
        UnsentLog.Entry selected = entry(1, "thread", "fixture \"quoted\" \n \u05d0\u0645");
        List<UnsentLog.Entry> entries = new ArrayList<>(List.of(selected));
        UnsentLog.writeSnapshot(destination, entries);
        assertEquals(selected.text, disk().getJSONObject(0).getString("m"));
        assertEquals(UnsentLog.RemovalResult.REMOVED, UnsentLog.removeEntryFrom(entries, selected, destination));
        assertTrue(entries.isEmpty());
        assertEquals(0, disk().length());
        byte[] before = Files.readAllBytes(destination.toPath());
        assertEquals(UnsentLog.RemovalResult.STALE, UnsentLog.removeEntryFrom(entries, selected, destination));
        assertArrayEquals(before, Files.readAllBytes(destination.toPath()));
    }

    private JSONArray disk() throws Exception {
        return new JSONArray(new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8));
    }
    private static UnsentLog.Entry entry(long time, String thread, String text) {
        return new UnsentLog.Entry(time, thread, "fixture label", text);
    }
}
