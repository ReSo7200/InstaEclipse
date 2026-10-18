package ps.reso.instaeclipse.utils.ghost;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded, session-only retention. Callers must revalidate ownership before using a snapshot. */
public final class UnsentRetentionState {
    public static final int MAX_THREADS = 100;
    public static final int MAX_MESSAGES = 500;
    public static final int MAX_ID_CHARS = 256;

    public interface Validator {
        String identity(Object message);
        boolean received(Record record, Object candidate);
        /** Return a native-order insertion index in [0, live.size()]; live is read-only. */
        int insertionIndex(Record record, List<Object> live);
    }

    public static final class Record {
        public final Object store, message;
        public final String accountId, threadId, messageId, senderId;

        private Record(Object store, String accountId, String threadId, String messageId,
                String senderId, Object message) {
            this.store = store;
            this.accountId = accountId;
            this.threadId = threadId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.message = message;
        }
    }

    private final IdentityHashMap<Object, LinkedHashMap<String, Record>> byEntry = new IdentityHashMap<>();
    private final ArrayDeque<Object> entryOrder = new ArrayDeque<>();
    private int messageCount;

    /** Returns true only for a known received message; rejected ownership revokes prior retention. */
    public synchronized boolean protect(Object threadEntry, Object store, String accountId,
            String threadId, String messageId, String senderId, Object message) {
        if (threadEntry == null || store == null || message == null
                || !ConversationIdentity.validId(accountId) || !ConversationIdentity.validId(senderId)
                || accountId.equals(senderId) || !validKey(threadId) || !validKey(messageId)) {
            forget(threadEntry, messageId);
            return false;
        }
        LinkedHashMap<String, Record> records = byEntry.get(threadEntry);
        if (records != null) {
            Record owner = records.values().iterator().next();
            if (owner.store != store || !owner.accountId.equals(accountId) || !owner.threadId.equals(threadId)) {
                clearOwner(threadEntry);
                records = null;
            }
        }
        if (records == null) {
            records = new LinkedHashMap<>();
            byEntry.put(threadEntry, records);
            entryOrder.addLast(threadEntry);
        }
        if (records.remove(messageId) == null) messageCount++;
        records.put(messageId, new Record(store, accountId, threadId, messageId, senderId, message));

        while (byEntry.size() > MAX_THREADS) clearOwner(entryOrder.peekFirst());
        // Evict the oldest record from the oldest retained entry. Both dimensions stay bounded.
        while (messageCount > MAX_MESSAGES) {
            Object oldest = entryOrder.peekFirst();
            forget(oldest, byEntry.get(oldest).keySet().iterator().next());
        }
        return true;
    }

    /** Detached immutable list; neither later purges nor caller list edits can change this state. */
    public synchronized List<Record> snapshot(Object threadEntry) {
        LinkedHashMap<String, Record> records = byEntry.get(threadEntry);
        return records == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(records.values()));
    }

    /**
     * Reconciles this entry only. The caller owns the mutable lists and must hold their native
     * entry lock. Validate every matching object before suppressing any removal for its ID.
     */
    public synchronized int reconcile(Object threadEntry, List<Object> live,
            List<? extends List<Object>> deltas, Validator validator) {
        if (live == null || validator == null) { clearOwner(threadEntry); return 0; }
        int added = 0;
        for (Record record : snapshot(threadEntry)) {
            boolean present = false, valid = true;
            int insertionIndex = -1;
            IdentityHashMap<Object, Boolean> receivedDeltas = new IdentityHashMap<>();
            try {
                valid = record.messageId.equals(validator.identity(record.message))
                        && validator.received(record, record.message);
                if (valid) for (Object candidate : live) {
                    if (!record.messageId.equals(validator.identity(candidate))) continue;
                    if (!validator.received(record, candidate)) { valid = false; break; }
                    present = true;
                }
                if (valid && deltas != null) for (List<Object> delta : deltas) {
                    if (delta == null || delta == live) continue;
                    for (Object candidate : delta) {
                        if (!record.messageId.equals(validator.identity(candidate))) continue;
                        if (!validator.received(record, candidate)) { valid = false; break; }
                        receivedDeltas.put(candidate, Boolean.TRUE);
                    }
                    if (!valid) break;
                }
                if (valid && !present) {
                    insertionIndex = validator.insertionIndex(record, Collections.unmodifiableList(live));
                    valid = insertionIndex >= 0 && insertionIndex <= live.size();
                }
            } catch (RuntimeException | LinkageError unavailable) {
                valid = false;
            }
            if (!valid) { forget(threadEntry, record.messageId); continue; }
            // A validator may reenter this synchronized state and revoke the record.
            LinkedHashMap<String, Record> current = byEntry.get(threadEntry);
            if (current == null || current.get(record.messageId) != record) continue;
            if (!present) { live.add(insertionIndex, record.message); added++; }
            if (deltas != null && !receivedDeltas.isEmpty()) for (List<Object> delta : deltas) {
                if (delta == null || delta == live) continue;
                for (Iterator<Object> candidates = delta.iterator(); candidates.hasNext();) {
                    if (receivedDeltas.containsKey(candidates.next())) candidates.remove();
                }
            }
        }
        return added;
    }

    public synchronized void forget(Object threadEntry, String messageId) {
        LinkedHashMap<String, Record> records = byEntry.get(threadEntry);
        if (records == null || records.remove(messageId) == null) return;
        messageCount--;
        if (records.isEmpty()) clearOwner(threadEntry);
    }

    public synchronized void clearOwner(Object threadEntry) {
        LinkedHashMap<String, Record> removed = byEntry.remove(threadEntry);
        if (removed == null) return;
        messageCount -= removed.size();
        for (Iterator<Object> entries = entryOrder.iterator(); entries.hasNext();) {
            if (entries.next() == threadEntry) { entries.remove(); break; }
        }
    }

    /** An unresolved removal revokes only that native store's protection, never another account. */
    public synchronized void clearStore(Object store) {
        for (Object entry : new ArrayList<>(entryOrder)) {
            LinkedHashMap<String, Record> records = byEntry.get(entry);
            if (records != null && !records.isEmpty() && records.values().iterator().next().store == store)
                clearOwner(entry);
        }
    }

    public synchronized int size() { return messageCount; }

    /** Feature-off must revoke session-only protection without deleting saved history. */
    public synchronized void clear() {
        byEntry.clear();
        entryOrder.clear();
        messageCount = 0;
    }

    private static boolean validKey(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_ID_CHARS && !value.trim().isEmpty();
    }
}
