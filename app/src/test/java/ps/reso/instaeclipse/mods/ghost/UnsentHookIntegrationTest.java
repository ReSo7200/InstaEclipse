package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.UnsentRetentionState;
import static org.junit.Assert.*;

/** Synthetic native-list shapes and in-memory flags only; never reads saved history or settings. */
public class UnsentHookIntegrationTest {
    static class Parent { List<Object> live = new ArrayList<>(); }
    static class Entry extends Parent { List<Object> pending = new ArrayList<>(); }
    static class StaticOnly { static List<Object> shared = new ArrayList<>(); }

    @Test public void liveListUsesIdentityAndFindsInheritedOwnedField() throws Exception {
        Entry entry = new Entry();
        List<Object> equalButUnowned = new ArrayList<>();
        assertEquals(entry.live, equalButUnowned);
        assertSame(entry.live, KeepUnsentMessagesHook.findLiveList(new Object[]{entry, equalButUnowned, entry.live}));
        assertNull(KeepUnsentMessagesHook.findLiveList(new Object[]{entry, equalButUnowned}));
    }

    @Test public void ambiguousNativeListsAndStaticFieldsAreNeverMutated() throws Exception {
        Entry entry = new Entry();
        entry.live.add("live"); entry.pending.add("pending");
        assertNull(KeepUnsentMessagesHook.findLiveList(new Object[]{entry, entry.live, entry.pending}));
        assertEquals(Arrays.asList("live"), entry.live);
        assertEquals(Arrays.asList("pending"), entry.pending);
        assertNull(KeepUnsentMessagesHook.findLiveList(new Object[]{new StaticOnly(), StaticOnly.shared}));
        assertSame(entry.live, KeepUnsentMessagesHook.findLiveList(new Object[]{entry, entry.live, entry.live}));
    }

    @Test public void turningEitherFeatureOffRevokesProtectedObjectsAndReenableDoesNotRestoreThem() throws Exception {
        boolean oldLog = FeatureFlags.keepUnsentMessages, oldLive = FeatureFlags.keepUnsentInChat;
        Field field = KeepUnsentMessagesHook.class.getDeclaredField("retained");
        field.setAccessible(true);
        UnsentRetentionState state = (UnsentRetentionState) field.get(null);
        state.clear();
        try {
            for (boolean turnLogOff : new boolean[]{false, true}) {
                FeatureFlags.keepUnsentMessages = true; FeatureFlags.keepUnsentInChat = true;
                Object entry = new Object(), store = new Object(), message = new Object();
                assertTrue(state.protect(entry, store, "100", "200", "synthetic-message", "101", message));
                KeepUnsentMessagesHook.onSettingsChanged();
                assertEquals(1, state.size());
                if (turnLogOff) FeatureFlags.keepUnsentMessages = false;
                else FeatureFlags.keepUnsentInChat = false;
                KeepUnsentMessagesHook.onSettingsChanged();
                assertTrue(state.snapshot(entry).isEmpty());
                FeatureFlags.keepUnsentMessages = true; FeatureFlags.keepUnsentInChat = true;
                KeepUnsentMessagesHook.onSettingsChanged();
                assertEquals(0, state.size());
            }
        } finally {
            state.clear();
            FeatureFlags.keepUnsentMessages = oldLog; FeatureFlags.keepUnsentInChat = oldLive;
        }
    }
}
