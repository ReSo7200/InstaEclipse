package ps.reso.instaeclipse.utils.ghost;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConversationSafetyTest {
    @Test public void keyExtractionUsesSemanticLabelsAndRejectsUnknownShapes() {
        assertEquals("340282366841710301244259778623", ConversationIdentity.fromKeyDescription(
                "DirectThreadKey{mThreadId='340282366841710301244259778623', mThreadV2Id='987654321'}"));
        assertNull(ConversationIdentity.fromKeyDescription("DirectThreadKey{mThreadId='null', mThreadV2Id='987654321'}"));
        assertNull(ConversationIdentity.fromKeyDescription("987654321"));
        assertNull(ConversationIdentity.fromKeyDescription("DirectThreadKey{mThreadId='Alice', mThreadV2Id='987654321'}"));
        assertNull(ConversationIdentity.fromKeyDescription("DirectThreadKey{mThreadId='123', mThreadId='456', mThreadV2Id='987'}"));
        assertFalse(ConversationIdentity.validId("0000"));
        assertFalse(ConversationIdentity.validId("123x"));
        assertFalse(ConversationIdentity.validId(" 123"));
    }
    @Test public void immutableFilterKeepsSourceAndReturnContract() {
        List<String> source = Collections.unmodifiableList(Arrays.asList("a", "b", "c"));
        Object result = InboxListFilter.filtered(source, List.class, "b"::equals);
        assertEquals(Arrays.asList("a", "c"), result);
        assertEquals(Arrays.asList("a", "b", "c"), source);
        assertSame(source, InboxListFilter.filtered(source, SpecialList.class, "b"::equals));
        assertSame(source, InboxListFilter.filtered(source, List.class, "z"::equals));
    }
    static class SpecialList extends ArrayList<String> {}
    @Test public void lifecycleAndAccountChangesRejectStaleCallbacks() {
        ConversationState state = new ConversationState();
        Object screen = new Object(), view = new Object();
        ConversationState.Token first = state.bind(screen, view, "1", "99");
        assertTrue(state.matches(first));
        state.bind(screen, view, "1", "100");
        assertFalse(state.matches(first));
        ConversationState.Token second = state.bind(screen, view, "1", "100");
        state.bind(screen, view, "2", "100");
        assertFalse(state.matches(second));
        ConversationState.Token third = state.bind(screen, view, "2", "100");
        state.invalidate();
        state.bind(screen, view, "2", "100");
        assertFalse(state.matches(third));
    }
    @Test public void returningToSameConversationOrAccountCannotReviveCapturedAction() {
        ConversationState state = new ConversationState();
        Object screen = new Object(), view = new Object();
        ConversationState.Token captured = state.bind(screen, view, "1", "99");
        assertSame(captured, state.bind(screen, view, "1", "99"));
        state.bind(screen, view, "1", "100");
        ConversationState.Token returned = state.bind(screen, view, "1", "99");
        assertFalse(state.matches(captured));
        assertTrue(state.matches(returned));

        state.bind(screen, view, "2", "99");
        ConversationState.Token accountReturned = state.bind(screen, view, "1", "99");
        assertFalse(state.matches(returned));
        assertTrue(state.matches(accountReturned));
    }
    @Test public void recreatedOwnerAndHeaderRejectActionsForSameConversationIdentity() {
        ConversationState state = new ConversationState();
        Object firstOwner = new Object(), secondOwner = new Object();
        Object firstView = new Object(), secondView = new Object();
        ConversationState.Token original = state.bind(firstOwner, firstView, "1", "99");
        ConversationState.Token newHeader = state.bind(firstOwner, secondView, "1", "99");
        assertFalse(state.matches(original));
        assertTrue(state.matches(newHeader));
        ConversationState.Token newOwner = state.bind(secondOwner, secondView, "1", "99");
        assertFalse(state.matches(newHeader));
        assertTrue(state.matches(newOwner));
    }
    @Test public void unresolvedBindingInvalidatesPreviouslyCapturedAction() {
        Object owner = new Object(), view = new Object();
        Object[][] unresolved = {
                {null, view, "1", "99"}, {owner, null, "1", "99"},
                {owner, view, null, "99"}, {owner, view, "1", "000"},
                {owner, view, "account name", "99"}, {owner, view, "1", "thread name"}
        };
        for (Object[] input : unresolved) {
            ConversationState state = new ConversationState();
            ConversationState.Token captured = state.bind(owner, view, "1", "99");
            assertNull(state.bind(input[0], input[1], (String) input[2], (String) input[3]));
            assertFalse(state.matches(captured));
            ConversationState.Token resolved = state.bind(owner, view, "1", "99");
            assertFalse(state.matches(captured));
            assertTrue(state.matches(resolved));
        }
    }
}
