package ps.reso.instaeclipse.utils.ghost;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntBiFunction;
import org.junit.Test;

public class UnsentRetentionStateTest {
    private final UnsentRetentionState state = new UnsentRetentionState();
    private final Object entry = new Object(), store = new Object(), message = new Object();

    private boolean protect(Object owner, String id, String sender) {
        return state.protect(owner, store, "101", "201", id, sender, message);
    }

    @Test public void outgoingUnsendIsNeverRetained() {
        assertFalse(protect(entry, "message-1", "101"));
        assertEquals(0, state.size()); assertTrue(state.snapshot(entry).isEmpty());
    }

    @Test public void knownIncomingMessageRetainsExactOwnerAndObject() {
        assertTrue(protect(entry, "message-1", "102"));
        UnsentRetentionState.Record record = state.snapshot(entry).get(0);
        assertSame(store, record.store); assertSame(message, record.message);
        assertEquals("101", record.accountId); assertEquals("201", record.threadId);
        assertEquals("message-1", record.messageId); assertEquals("102", record.senderId);
        assertEquals(1, state.size());
    }

    @Test public void outgoingRemovalPurgesPreviousRetentionAndCannotReappearInLaterSnapshots() {
        assertTrue(protect(entry, "message-1", "102"));
        assertFalse(protect(entry, "message-1", "101"));
        assertTrue(state.snapshot(entry).isEmpty()); assertEquals(0, state.size());
        assertTrue(protect(entry, "message-2", "102"));
        assertEquals(1, state.snapshot(entry).size());
        assertEquals("message-2", state.snapshot(entry).get(0).messageId);
    }

    @Test public void unknownOwnershipAllowsDeletionAndPurgesOnlyMatchingEntryId() {
        Object otherEntry = new Object();
        assertTrue(protect(entry, "same-id", "102"));
        assertTrue(protect(entry, "other-id", "102"));
        assertTrue(protect(otherEntry, "same-id", "102"));
        assertFalse(protect(entry, "same-id", null));
        assertEquals(2, state.size());
        assertEquals("other-id", state.snapshot(entry).get(0).messageId);
        assertEquals("same-id", state.snapshot(otherEntry).get(0).messageId);
    }

    @Test public void invalidEvidenceIsNeverRetainedAndRevokesMatchingRecord() {
        String[] invalidNumeric = {null, "", "0", "01", "-1", "name", "1 ", "1".repeat(65)};
        for (String invalid : invalidNumeric) {
            assertTrue(protect(entry, "message-1", "102"));
            assertFalse(state.protect(entry, store, invalid, "201", "message-1", "102", message));
            assertEquals(0, state.size());
            assertTrue(protect(entry, "message-1", "102"));
            assertFalse(protect(entry, "message-1", invalid));
            assertEquals(0, state.size());
        }
        for (String invalid : new String[] {null, "", " ", "x".repeat(UnsentRetentionState.MAX_ID_CHARS + 1)}) {
            assertFalse(state.protect(entry, store, "101", "201", invalid, "102", message));
            assertFalse(state.protect(entry, store, "101", invalid, "message-1", "102", message));
        }
        assertFalse(state.protect(null, store, "101", "201", "message-1", "102", message));
        assertTrue(protect(entry, "message-1", "102"));
        assertFalse(state.protect(entry, null, "101", "201", "message-1", "102", message));
        assertTrue(protect(entry, "message-1", "102"));
        assertFalse(state.protect(entry, store, "101", "201", "message-1", "102", null));
        assertEquals(0, state.size());
    }

    @Test public void equalEntryObjectsAndSameIdsAcrossAccountsRemainIdentityScoped() {
        Object first = new String("equal"), second = new String("equal");
        Object secondStore = new Object();
        assertTrue(state.protect(first, store, "101", "201", "same-id", "102", message));
        assertTrue(state.protect(second, secondStore, "102", "202", "same-id", "101", message));
        assertEquals(2, state.size());
        assertEquals("101", state.snapshot(first).get(0).accountId);
        assertEquals("102", state.snapshot(second).get(0).accountId);
        state.clearOwner(first);
        assertTrue(state.snapshot(first).isEmpty()); assertEquals(1, state.snapshot(second).size());
        assertEquals(1, state.size());
    }

    @Test public void reusedEntryCannotMixStoreAccountOrThreadOwners() {
        assertTrue(protect(entry, "old-account", "102"));
        assertTrue(state.protect(entry, store, "103", "201", "new-account", "102", message));
        assertEquals(1, state.size()); assertEquals("new-account", state.snapshot(entry).get(0).messageId);
        assertTrue(state.protect(entry, store, "103", "202", "new-thread", "102", message));
        assertEquals(1, state.size()); assertEquals("new-thread", state.snapshot(entry).get(0).messageId);
        Object newStore = new Object();
        assertTrue(state.protect(entry, newStore, "103", "202", "new-store", "102", message));
        assertEquals(1, state.size()); assertSame(newStore, state.snapshot(entry).get(0).store);
    }

    @Test public void snapshotIsImmutableDetachedAndReplacementDoesNotGrowState() {
        assertTrue(protect(entry, "message-1", "102"));
        List<UnsentRetentionState.Record> snapshot = state.snapshot(entry);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        Object replacement = new Object();
        assertTrue(state.protect(entry, store, "101", "201", "message-1", "102", replacement));
        assertEquals(1, state.size()); assertSame(message, snapshot.get(0).message);
        assertSame(replacement, state.snapshot(entry).get(0).message);
        state.forget(entry, "message-1");
        assertEquals(1, snapshot.size()); assertTrue(state.snapshot(entry).isEmpty());
        state.forget(entry, "message-1"); state.clearOwner(entry);
        assertEquals(0, state.size());
    }

    @Test public void oldestThreadsAreEvictedAtTheThreadBound() {
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < UnsentRetentionState.MAX_THREADS + 1; i++) {
            Object owner = new Object(); entries.add(owner);
            assertTrue(protect(owner, "same-id", "102"));
        }
        assertEquals(UnsentRetentionState.MAX_THREADS, state.size());
        assertTrue(state.snapshot(entries.get(0)).isEmpty());
        assertEquals(1, state.snapshot(entries.get(1)).size());
        assertEquals(1, state.snapshot(entries.get(entries.size() - 1)).size());
    }

    @Test public void messageBoundAppliesAcrossAllThreadsAndClearReleasesTheirRecords() {
        Object second = new Object();
        for (int i = 0; i < 300; i++) assertTrue(protect(entry, "first-" + i, "102"));
        for (int i = 0; i < 300; i++) assertTrue(protect(second, "second-" + i, "102"));
        assertEquals(UnsentRetentionState.MAX_MESSAGES, state.size());
        assertEquals(200, state.snapshot(entry).size());
        assertEquals("first-100", state.snapshot(entry).get(0).messageId);
        assertEquals(300, state.snapshot(second).size());
        state.clearOwner(entry); assertEquals(300, state.size());
        state.clearOwner(second); assertEquals(0, state.size());
    }

    private static final class Message {
        final String id, sender;
        Message(String id, String sender) { this.id = id; this.sender = sender; }
    }
    private static final Comparator<Object> MESSAGE_ORDER = Comparator.comparing(candidate -> ((Message) candidate).id);

    private final UnsentRetentionState.Validator validator = new UnsentRetentionState.Validator() {
        @Override public String identity(Object candidate) {
            return candidate instanceof Message ? ((Message) candidate).id : null;
        }
        @Override public boolean received(UnsentRetentionState.Record record, Object candidate) {
            return candidate instanceof Message && ConversationIdentity.validId(((Message) candidate).sender)
                    && !record.accountId.equals(((Message) candidate).sender)
                    && record.senderId.equals(((Message) candidate).sender);
        }
        @Override public int insertionIndex(UnsentRetentionState.Record record, List<Object> live) {
            int found = Collections.binarySearch(live, record.message, MESSAGE_ORDER);
            return found >= 0 ? found : -found - 1;
        }
    };

    private UnsentRetentionState.Validator placement(ToIntBiFunction<UnsentRetentionState.Record, List<Object>> index) {
        return new UnsentRetentionState.Validator() {
            @Override public String identity(Object candidate) { return validator.identity(candidate); }
            @Override public boolean received(UnsentRetentionState.Record record, Object candidate) {
                return validator.received(record, candidate);
            }
            @Override public int insertionIndex(UnsentRetentionState.Record record, List<Object> live) {
                return index.applyAsInt(record, live);
            }
        };
    }

    private boolean remember(Object owner, Message candidate) {
        return state.protect(owner, store, "101", "201", candidate.id, candidate.sender, candidate);
    }

    @Test public void outgoingRemovalPassesThroughProtectionAndReconcileWithoutRestoration() {
        Message outgoing = new Message("outgoing", "101");
        assertFalse(remember(entry, outgoing));
        List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Collections.singletonList(outgoing));
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertTrue(live.isEmpty()); assertEquals(Collections.singletonList(outgoing), removed);
    }

    @Test public void receivedMessageIsReinsertedOnceAndRemovalDeltaIsScrubbed() {
        Message incoming = new Message("received", "102"), unrelated = new Message("unrelated", "101");
        assertTrue(remember(entry, incoming));
        List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Arrays.asList(incoming, unrelated));
        assertEquals(1, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertEquals(Collections.singletonList(incoming), live);
        assertEquals(Collections.singletonList(unrelated), removed);
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertEquals(1, live.size());
    }

    @Test public void receivedCloneAlreadyInLiveListPreventsDuplicateReinsertion() {
        Message cached = new Message("received", "102"), clone = new Message("received", "102");
        assertTrue(remember(entry, cached));
        List<Object> live = new ArrayList<>(Collections.singletonList(clone));
        List<Object> removed = new ArrayList<>(Collections.singletonList(cached));
        assertEquals(0, state.reconcile(entry, live, Arrays.asList(live, removed), placement((record, current) -> {
            throw new AssertionError("An existing same-ID clone must not request placement");
        })));
        assertEquals(Collections.singletonList(clone), live); assertTrue(removed.isEmpty());
        assertEquals(1, state.size());
    }

    @Test public void endedAccountPurgesRetentionWithoutTouchingRemovalDelta() {
        Message incoming = new Message("received", "102"); assertTrue(remember(entry, incoming));
        List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Collections.singletonList(incoming));
        UnsentRetentionState.Validator ended = new UnsentRetentionState.Validator() {
            @Override public String identity(Object candidate) { return validator.identity(candidate); }
            @Override public boolean received(UnsentRetentionState.Record record, Object candidate) { return false; }
            @Override public int insertionIndex(UnsentRetentionState.Record record, List<Object> current) {
                throw new AssertionError("Ended account must not request placement");
            }
        };
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), ended));
        assertTrue(live.isEmpty()); assertEquals(Collections.singletonList(incoming), removed);
        assertEquals(0, state.size());
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertTrue(live.isEmpty());
    }

    @Test public void conflictingOutgoingCandidatePurgesBeforeAnyListMutation() {
        for (boolean conflictInLive : new boolean[] {false, true}) {
            Message cached = new Message("same-id", "102"), outgoing = new Message("same-id", "101");
            assertTrue(remember(entry, cached));
            List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Collections.singletonList(cached));
            if (conflictInLive) live.add(outgoing); else removed.add(outgoing);
            List<Object> beforeLive = new ArrayList<>(live), beforeRemoved = new ArrayList<>(removed);
            assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), validator));
            assertEquals(beforeLive, live); assertEquals(beforeRemoved, removed); assertEquals(0, state.size());
        }
    }

    @Test public void reconciliationNeverUsesAnotherEntrysSameId() {
        Message incoming = new Message("same-id", "102"); Object other = new Object();
        assertTrue(remember(entry, incoming));
        List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Collections.singletonList(incoming));
        assertEquals(0, state.reconcile(other, live, Collections.singletonList(removed), validator));
        assertTrue(live.isEmpty()); assertEquals(Collections.singletonList(incoming), removed);
        assertEquals(1, state.snapshot(entry).size());
    }

    @Test public void explicitPurgeCannotBeUndoneByReconciliationOrAnOldSnapshot() {
        Message incoming = new Message("received", "102"); assertTrue(remember(entry, incoming));
        List<UnsentRetentionState.Record> stale = state.snapshot(entry);
        state.forget(entry, incoming.id);
        List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Collections.singletonList(stale.get(0).message));
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertTrue(live.isEmpty()); assertEquals(1, removed.size()); assertEquals(0, state.size());
    }

    @Test public void unavailableValidatorPurgesWithoutChangingLists() {
        Message incoming = new Message("received", "102"); assertTrue(remember(entry, incoming));
        List<Object> live = new ArrayList<>(), removed = new ArrayList<>(Collections.singletonList(incoming));
        UnsentRetentionState.Validator unavailable = new UnsentRetentionState.Validator() {
            @Override public String identity(Object candidate) { throw new IllegalStateException("Synthetic unavailable getter"); }
            @Override public boolean received(UnsentRetentionState.Record record, Object candidate) { return true; }
            @Override public int insertionIndex(UnsentRetentionState.Record record, List<Object> current) {
                throw new AssertionError("Unavailable identity must not request placement");
            }
        };
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), unavailable));
        assertEquals(0, state.size()); assertTrue(live.isEmpty()); assertEquals(1, removed.size());
    }

    @Test public void restoredMiddleMessagePreservesBinarySearchOrder() {
        Message first = new Message("10", "102"), middle = new Message("20", "102"), last = new Message("30", "102");
        assertTrue(remember(entry, middle));
        List<Object> live = new ArrayList<>(Arrays.asList(first, last));
        List<Object> removed = new ArrayList<>(Collections.singletonList(middle));
        assertEquals(1, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertEquals(Arrays.asList(first, middle, last), live); assertTrue(removed.isEmpty());
        assertEquals(1, Collections.binarySearch(live, middle, MESSAGE_ORDER));
        assertEquals(2, Collections.binarySearch(live, last, MESSAGE_ORDER));
    }

    @Test public void multipleRestoresUseEachUpdatedListsNativeOrder() {
        Message ten = new Message("10", "102"), twenty = new Message("20", "102");
        Message thirty = new Message("30", "102"), forty = new Message("40", "102");
        Message fifteen = new Message("15", "102"), thirtyFive = new Message("35", "102");
        for (Message incoming : Arrays.asList(forty, twenty, ten, thirty)) assertTrue(remember(entry, incoming));
        List<Object> live = new ArrayList<>(Arrays.asList(fifteen, thirtyFive));
        List<Object> removed = new ArrayList<>(Arrays.asList(forty, twenty, ten, thirty));
        assertEquals(4, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertEquals(Arrays.asList(ten, fifteen, twenty, thirty, thirtyFive, forty), live);
        for (int i = 0; i < live.size(); i++) assertEquals(i, Collections.binarySearch(live, live.get(i), MESSAGE_ORDER));
        assertTrue(removed.isEmpty());
    }

    @Test public void invalidInsertionIndicesPurgeBeforeTouchingEitherList() {
        for (int index : new int[] {-1, 2, Integer.MAX_VALUE}) {
            Message cached = new Message("20", "102"), existing = new Message("10", "102");
            assertTrue(remember(entry, cached));
            List<Object> live = new ArrayList<>(Collections.singletonList(existing));
            List<Object> removed = new ArrayList<>(Collections.singletonList(cached));
            assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), placement((record, current) -> index)));
            assertEquals(Collections.singletonList(existing), live);
            assertEquals(Collections.singletonList(cached), removed); assertEquals(0, state.size());
        }
    }

    @Test public void placementExceptionPurgesBeforeTouchingEitherList() {
        Message cached = new Message("20", "102"), existing = new Message("10", "102");
        assertTrue(remember(entry, cached));
        List<Object> live = new ArrayList<>(Collections.singletonList(existing));
        List<Object> removed = new ArrayList<>(Collections.singletonList(cached));
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), placement((record, current) -> {
            throw new IllegalStateException("Synthetic unavailable comparator");
        })));
        assertEquals(Collections.singletonList(existing), live);
        assertEquals(Collections.singletonList(cached), removed); assertEquals(0, state.size());
    }

    @Test public void unresolvedRemovalRevokesOnlyItsNativeStore() {
        Object sameStoreOtherEntry = new Object(), otherStore = new Object(), otherEntry = new Object();
        assertTrue(protect(entry, "message-1", "102"));
        assertTrue(state.protect(sameStoreOtherEntry, store, "101", "202", "message-2", "102", message));
        assertTrue(state.protect(otherEntry, otherStore, "301", "401", "message-1", "302", message));
        state.clearStore(store);
        assertEquals(1, state.size());
        assertTrue(state.snapshot(entry).isEmpty());
        assertTrue(state.snapshot(sameStoreOtherEntry).isEmpty());
        assertEquals(1, state.snapshot(otherEntry).size());
        state.clearStore(new Object());
        assertEquals(1, state.size());
    }

    @Test public void featureOffRevokesAllOwnersAndCannotRestoreOldSnapshots() {
        Message first = new Message("10", "102"), second = new Message("20", "102");
        Object otherEntry = new Object();
        assertTrue(remember(entry, first));
        assertTrue(state.protect(otherEntry, store, "101", "200", second.id, second.sender, second));
        List<UnsentRetentionState.Record> oldSnapshot = state.snapshot(entry);
        state.clear();
        assertEquals(0, state.size());
        assertTrue(state.snapshot(entry).isEmpty());
        assertTrue(state.snapshot(otherEntry).isEmpty());
        assertEquals(1, oldSnapshot.size());
        List<Object> live = new ArrayList<>();
        List<Object> removed = new ArrayList<>(Arrays.asList(first, second));
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), validator));
        assertTrue(live.isEmpty());
        assertEquals(Arrays.asList(first, second), removed);
        assertTrue(remember(entry, first));
        assertEquals(1, state.size());
    }

    @Test public void placementCannotMutateTheLiveListBeforeValidation() {
        Message cached = new Message("20", "102"), existing = new Message("10", "102");
        assertTrue(remember(entry, cached));
        List<Object> live = new ArrayList<>(Collections.singletonList(existing));
        List<Object> removed = new ArrayList<>(Collections.singletonList(cached));
        assertEquals(0, state.reconcile(entry, live, Collections.singletonList(removed), placement((record, current) -> {
            current.clear(); return 0;
        })));
        assertEquals(Collections.singletonList(existing), live);
        assertEquals(Collections.singletonList(cached), removed); assertEquals(0, state.size());
    }
}
