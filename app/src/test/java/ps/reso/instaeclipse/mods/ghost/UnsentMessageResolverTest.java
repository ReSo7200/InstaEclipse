package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.junit.Test;

public class UnsentMessageResolverTest {
    static final class Session {
        String account = "100";
        boolean ended;
        String getUserId() { return account; }
        boolean hasEnded() { return ended; }
    }
    static final class Message {
        String id, client, sender;
        String unrelatedText = "not-an-identifier";
        Message(String id, String client, String sender) { this.id = id; this.client = client; this.sender = sender; }
        String messageId() { return id; }
        String clientContext() { return client; }
    }
    static final class Entry {
        final Session session;
        List<Message> live = new ArrayList<>(), pending = new ArrayList<>();
        Entry(Session session) { this.session = session; }
        Message byId(String id) {
            for (Message message : live) if (id.equals(message.id)) return message;
            return null;
        }
        Message byClient(String client) {
            for (Message message : pending) if (client.equals(message.client)) return message;
            for (Message message : live) if (client.equals(message.client)) return message;
            return null;
        }
    }
    static final class Store {
        final Session session;
        final Entry entry;
        final Object key = new Object();
        Store(Session session) { this.session = session; entry = new Entry(session); }
        Entry entry(Object key) { return this.key == key ? entry : null; }
    }

    private UnsentMessageResolver resolver() throws Exception {
        return resolver((left, right) -> ((Message) left).id.compareTo(((Message) right).id));
    }

    private UnsentMessageResolver resolver(Comparator<Object> order) throws Exception {
        return new UnsentMessageResolver(Store.class.getDeclaredMethod("entry", Object.class),
                Entry.class.getDeclaredMethod("byId", String.class), Entry.class.getDeclaredMethod("byClient", String.class),
                Message.class.getDeclaredMethod("messageId"), Message.class.getDeclaredMethod("clientContext"),
                Message.class.getDeclaredField("sender"), Store.class.getDeclaredField("session"),
                Entry.class.getDeclaredField("session"), Session.class.getDeclaredMethod("getUserId"),
                Session.class.getDeclaredMethod("hasEnded"),
                List.of(Entry.class.getDeclaredField("live"), Entry.class.getDeclaredField("pending")), order);
    }

    @Test public void identifiesOwnAndReceivedMessagesFromRemovingStoresSession() throws Exception {
        UnsentMessageResolver resolver = resolver();
        Store store = new Store(new Session());
        Message own = new Message("own-id", "own-client", "100"), received = new Message("received-id", "received-client", "200");
        store.entry.live.add(own); store.entry.live.add(received);
        UnsentMessageResolver.Selection a = resolver.lookup(store, store.key, own.id, own.client);
        assertNotNull(a); assertTrue(a.own); assertSame(own, a.message);
        UnsentMessageResolver.Selection b = resolver.lookup(store, store.key, received.id, received.client);
        assertNotNull(b); assertFalse(b.own); assertSame(store.entry, b.threadEntry);
        assertEquals("100", b.accountId); assertEquals("200", b.senderId);
        assertEquals(received.id, b.identity());
    }

    @Test public void clientContextOnlyLookupUsesPendingListAndKeepsIdentifiersSeparate() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message pending = new Message(null, "pending-context", "100"); store.entry.pending.add(pending);
        UnsentMessageResolver.Selection result = resolver.lookup(store, store.key, null, pending.client);
        assertNotNull(result); assertTrue(result.own); assertNull(result.messageId);
        assertEquals(pending.client, result.clientContext); assertEquals(pending.client, result.identity());
        assertEquals(pending.client, resolver.identityOf(pending));
    }

    @Test public void nullSenderEndedSessionAndInvalidAccountAreUnknown() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message message = new Message("id", "context", null); store.entry.live.add(message);
        assertNull(resolver.lookup(store, store.key, "id", null));
        message.sender = "0";
        assertNull(resolver.lookup(store, store.key, "id", null));
        message.sender = "200"; store.session.ended = true;
        assertNull(resolver.lookup(store, store.key, "id", null));
        store.session.ended = false; store.session.account = "not-an-account";
        assertNull(resolver.lookup(store, store.key, "id", null));
    }

    @Test public void conflictingIdentifiersAndDuplicateClientAliasesAreUnknown() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message one = new Message("first", "first-client", "200"), two = new Message("second", "second-client", "200");
        store.entry.live.add(one); store.entry.live.add(two);
        assertNull(resolver.lookup(store, store.key, one.id, two.client));
        two.client = one.client;
        assertNull(resolver.lookup(store, store.key, null, one.client));
        assertNull(resolver.lookup(store, store.key, one.id, one.client));
        two.client = "second-client"; two.id = one.id;
        assertNull(resolver.lookup(store, store.key, one.id, null));
    }

    @Test public void sameObjectAcrossNativeListsIsNotAnAmbiguousMessage() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message message = new Message("id", "context", "200");
        store.entry.live.add(message); store.entry.pending.add(message);
        assertNotNull(resolver.lookup(store, store.key, message.id, message.client));
    }

    @Test public void doesNotMatchArbitraryTextOrAnotherThreadKey() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message message = new Message("id", "context", "200"); message.unrelatedText = "another-id";
        store.entry.live.add(message);
        assertNull(resolver.lookup(store, new Object(), message.id, null));
        assertNull(resolver.lookup(store, store.key, message.unrelatedText, null));
        assertNull(resolver.lookup(store, store.key, "", null));
        assertNull(resolver.lookup(store, store.key, null, null));
    }

    @Test public void reconciliationRejectsOwnChangedSenderEndedOrChangedAccount() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message message = new Message("id", "context", "200"); store.entry.live.add(message);
        assertTrue(resolver.stillReceived(store, store.entry, message, "100", "id", "200"));
        message.sender = "100";
        assertFalse(resolver.stillReceived(store, store.entry, message, "100", "id", "200"));
        message.sender = "200"; store.session.ended = true;
        assertFalse(resolver.stillReceived(store, store.entry, message, "100", "id", "200"));
        store.session.ended = false; store.session.account = "300";
        assertFalse(resolver.stillReceived(store, store.entry, message, "100", "id", "200"));
        store.session.account = "100"; message.id = "different";
        assertFalse(resolver.stillReceived(store, store.entry, message, "100", "id", "200"));
        assertFalse(resolver.stillReceived(store, store.entry, message, "100", "different", "100"));
    }

    @Test public void sameAccountOnAnotherSessionDoesNotAuthorizeAnEntry() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session()), other = new Store(new Session());
        Message message = new Message("id", "context", "200");
        assertFalse(resolver.stillReceived(store, other.entry, message, "100", "id", "200"));
    }

    @Test public void excessiveNativeListSizeFailsWithoutTraversingIt() throws Exception {
        UnsentMessageResolver resolver = resolver(); Store store = new Store(new Session());
        Message message = new Message("id", "context", "200");
        store.entry.live = Collections.nCopies(UnsentMessageResolver.MAX_MESSAGES + 1, message);
        assertNull(resolver.lookup(store, store.key, "id", null));
    }

    @Test public void insertionFollowsNativeOrderAtStartMiddleAndEndWithoutMutating() throws Exception {
        UnsentMessageResolver resolver = resolver();
        Message b = new Message("b", null, "200"), d = new Message("d", null, "200");
        List<Object> live = List.of(b, d);
        assertEquals(0, resolver.insertionIndex(live, new Message("a", null, "200")));
        assertEquals(1, resolver.insertionIndex(live, new Message("c", null, "200")));
        assertEquals(2, resolver.insertionIndex(live, new Message("e", null, "200")));
        assertEquals(List.of(b, d), live);
        assertEquals(0, resolver.insertionIndex(List.of(), b));
    }

    @Test public void insertionUsesComparatorDirectionInsteadOfAssumingIdOrder() throws Exception {
        UnsentMessageResolver resolver = resolver((left, right) -> ((Message) right).id.compareTo(((Message) left).id));
        List<Object> live = List.of(new Message("d", null, "200"), new Message("b", null, "200"));
        assertEquals(0, resolver.insertionIndex(live, new Message("e", null, "200")));
        assertEquals(1, resolver.insertionIndex(live, new Message("c", null, "200")));
        assertEquals(2, resolver.insertionIndex(live, new Message("a", null, "200")));
    }

    @Test public void insertionRejectsUnsortedInvalidAndDuplicateIds() throws Exception {
        UnsentMessageResolver resolver = resolver();
        Message a = new Message("a", null, "200"), c = new Message("c", null, "200");
        assertEquals(-1, resolver.insertionIndex(List.of(c, a), new Message("b", null, "200")));
        assertEquals(-1, resolver.insertionIndex(List.of(a, a), c));
        assertEquals(-1, resolver.insertionIndex(List.of(a), new Message("a", "different-client", "200")));
        assertEquals(-1, resolver.insertionIndex(List.of(a), new Message(null, "client-only", "200")));
        assertEquals(-1, resolver.insertionIndex(List.of(new Message(null, "client-only", "200")), c));
        assertEquals(-1, resolver.insertionIndex(List.of(new Object()), c));
        assertEquals(-1, resolver.insertionIndex(Collections.singletonList(null), c));
        assertEquals(-1, resolver.insertionIndex(List.of(a), new Object()));
        assertEquals(-1, resolver.insertionIndex(null, c));
    }

    @Test public void insertionRejectsComparatorCollisionEvenForDifferentIds() throws Exception {
        UnsentMessageResolver resolver = resolver((left, right) ->
                Integer.compare(((Message) left).id.length(), ((Message) right).id.length()));
        Message a = new Message("a", null, "200"), b = new Message("b", null, "200");
        assertEquals(-1, resolver.insertionIndex(List.of(a), b));
        assertEquals(-1, resolver.insertionIndex(List.of(a, b), new Message("cc", null, "200")));
    }

    @Test public void insertionRejectsUnavailableThrowingAndInvalidComparators() throws Exception {
        Message a = new Message("a", null, "200"), b = new Message("b", null, "200");
        assertEquals(-1, resolver(null).insertionIndex(List.of(), a));
        assertEquals(-1, resolver((left, right) -> { throw new IllegalStateException(); }).insertionIndex(List.of(a), b));
        assertEquals(-1, resolver((left, right) -> 1).insertionIndex(List.of(), a));
        assertEquals(-1, resolver((left, right) -> left == right ? 0 : 1).insertionIndex(List.of(a), b));
    }

    @Test public void insertionRefusesFullListBeforeNativeComparisons() throws Exception {
        Message message = new Message("id", null, "200");
        UnsentMessageResolver resolver = resolver((left, right) -> { fail("Full list must not be traversed"); return 0; });
        assertEquals(-1, resolver.insertionIndex(Collections.nCopies(UnsentMessageResolver.MAX_MESSAGES, message), message));
    }
}
