package ps.reso.instaeclipse.utils.ghost;

import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class HiddenThreadStoreTest {
    static final class MemoryStorage implements HiddenThreadStore.Storage {
        String saved, legacy;
        boolean fail;
        public String read() { return saved; }
        public String readLegacy() { return legacy; }
        public void write(String value) throws IOException {
            if (fail) throw new IOException("simulated full disk");
            saved = value;
        }
    }
    @Test public void isolatesAccountsAndSurvivesReload() {
        MemoryStorage io = new MemoryStorage();
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertTrue(store.setHidden("1", "99", "fixture", true).success);
        assertTrue(store.isHidden("1", "99"));
        assertFalse(store.isHidden("2", "99"));
        assertTrue(new HiddenThreadStore(io).isHidden("1", "99"));
    }
    @Test public void failedWriteDoesNotChangeVisibleState() {
        MemoryStorage io = new MemoryStorage();
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertTrue(store.setHidden("1", "99", "fixture", true).success);
        String previous = io.saved;
        io.fail = true;
        HiddenThreadStore.Result failed = store.setHidden("1", "99", "fixture", false);
        assertFalse(failed.success);
        assertTrue(failed.hidden);
        assertNotNull(failed.error);
        assertTrue(store.isHidden("1", "99"));
        assertEquals(previous, io.saved);
        assertTrue(new HiddenThreadStore(io).isHidden("1", "99"));
        io.fail = false;
        HiddenThreadStore.Result retried = store.setHidden("1", "99", "fixture", false);
        assertTrue(retried.success);
        assertFalse(retried.hidden);
        assertNull(store.lastError());
        assertFalse(new HiddenThreadStore(io).isHidden("1", "99"));
    }
    @Test public void unhideRestoresOnlySelectedAccountAndKeepsOtherEntriesAcrossReload() {
        MemoryStorage io = new MemoryStorage();
        io.legacy = "{\"98\":\"unassigned fixture\"}";
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertTrue(store.setHidden("1", "99", "account one", true).success);
        assertTrue(store.setHidden("1", "100", "another chat", true).success);
        assertTrue(store.setHidden("2", "99", "account two", true).success);
        List<String> source = Collections.unmodifiableList(Arrays.asList("99", "100", "101"));
        assertEquals(Collections.singletonList("101"),
                InboxListFilter.filtered(source, List.class, id -> store.isHidden("1", (String) id)));

        assertTrue(store.setHidden("1", "99", "account one", false).success);
        HiddenThreadStore reloaded = new HiddenThreadStore(io);
        assertEquals(Arrays.asList("99", "101"),
                InboxListFilter.filtered(source, List.class, id -> reloaded.isHidden("1", (String) id)));
        assertEquals(Arrays.asList("100", "101"),
                InboxListFilter.filtered(source, List.class, id -> reloaded.isHidden("2", (String) id)));
        assertEquals(Arrays.asList("99", "100", "101"), source);
        assertEquals(Collections.singletonMap("100", "another chat"), reloaded.all("1"));
        assertEquals(Collections.singletonMap("99", "account two"), reloaded.all("2"));
        assertEquals(Collections.singletonMap("98", "unassigned fixture"), reloaded.legacyEntries());
        assertEquals("{\"98\":\"unassigned fixture\"}", io.legacy);
    }
    @Test public void editingManagerSnapshotsCannotChangePersistedOrOtherAccountState() {
        MemoryStorage io = new MemoryStorage();
        io.legacy = "{\"98\":\"unassigned fixture\"}";
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertTrue(store.setHidden("1", "99", "account one", true).success);
        assertTrue(store.setHidden("2", "100", "account two", true).success);
        String saved = io.saved;
        Map<String, String> shown = store.all("1");
        shown.clear();
        shown.put("100", "different row");
        store.all("2").clear();
        store.legacyEntries().clear();
        assertEquals(Collections.singletonMap("99", "account one"), store.all("1"));
        assertEquals(Collections.singletonMap("100", "account two"), store.all("2"));
        assertEquals(Collections.singletonMap("98", "unassigned fixture"), store.legacyEntries());
        assertEquals(saved, io.saved);
        assertEquals(store.all("1"), new HiddenThreadStore(io).all("1"));
    }
    @Test public void legacyRemainsUnassignedAndSourceUnchanged() {
        MemoryStorage io = new MemoryStorage();
        io.legacy = "{\"99\":\"legacy fixture\"}";
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertFalse(store.isHidden("1", "99"));
        assertEquals("legacy fixture", store.legacyEntries().get("99"));
        assertTrue(store.setHidden("1", "100", "new fixture", true).success);
        assertEquals("{\"99\":\"legacy fixture\"}", io.legacy);
        assertEquals("legacy fixture", new HiddenThreadStore(io).legacyEntries().get("99"));
    }
    @Test public void unknownFutureSchemaIsPreserved() {
        MemoryStorage io = new MemoryStorage();
        io.saved = "{\"version\":3,\"accounts\":{},\"legacyUnassigned\":{}}";
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertFalse(store.setHidden("1", "99", "fixture", true).success);
        assertTrue(io.saved.contains("3"));
    }
    @Test public void failedMigrationWriteKeepsUnassignedLegacy() {
        MemoryStorage io = new MemoryStorage();
        io.legacy = "{\"99\":\"fixture\"}";
        io.fail = true;
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertFalse(store.setHidden("1", "100", "new fixture", true).success);
        assertEquals("fixture", store.legacyEntries().get("99"));
        assertFalse(store.isHidden("1", "100"));
        assertNull(io.saved);
    }
    @Test public void migrationCannotWriteAnUnreadableOversizedEnvelope() {
        MemoryStorage io = new MemoryStorage();
        io.legacy = "{\"99\":\"" + "a".repeat(HiddenThreadStore.MAX_BYTES - 9) + "\"}";
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertFalse(store.setHidden("1", "100", "fixture", true).success);
        assertNull(io.saved);
        assertEquals(1, store.legacyEntries().size());
    }
    @Test public void corruptStateIsNotOverwritten() {
        MemoryStorage io = new MemoryStorage();
        io.saved = "{broken";
        HiddenThreadStore store = new HiddenThreadStore(io);
        assertFalse(store.setHidden("1", "99", "fixture", true).success);
        assertEquals("{broken", io.saved);
    }
}
