package ps.reso.instaeclipse.utils.users;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WatchlistStoreTest {

    private static Map<String, String[]> users(String... ids) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String id : ids) m.put(id, new String[]{"u" + id, "Name " + id});
        return m;
    }

    @Test
    public void firstAnalysisOnlySavesSnapshot() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1", "2"), users("3"), 1000);
        assertTrue(r.first);
        assertTrue(r.added.isEmpty());
        assertEquals(2, r.followers);
        assertEquals(1, r.following);
        assertEquals(1000, p.lastCheck);
        assertEquals(2, p.followers.size());
    }

    @Test
    public void secondAnalysisFindsAllFourKinds() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.apply(p, users("1", "2"), users("3", "4"), 1000);
        // follower 1 left, 5 arrived; B unfollowed 3, followed 6
        WatchlistStore.Result r = WatchlistStore.apply(p, users("2", "5"), users("4", "6"), 2000);
        assertFalse(r.first);
        assertEquals(1000, r.previousCheck);
        assertEquals(4, r.added.size());
        assertEquals("6", r.of(WatchlistStore.B_FOLLOWED).get(0).id);
        assertEquals("3", r.of(WatchlistStore.B_UNFOLLOWED).get(0).id);
        assertEquals("5", r.of(WatchlistStore.GAINED_FOLLOWER).get(0).id);
        WatchlistStore.Event lost = r.of(WatchlistStore.LOST_FOLLOWER).get(0);
        assertEquals("1", lost.id);
        assertEquals("u1", lost.username); // name kept from the earlier snapshot
        assertEquals(2000, lost.ts);
        assertEquals(1000, lost.since);
        assertEquals(4, p.events.size());
        assertTrue(p.followers.containsKey("5"));
    }

    @Test
    public void unchangedListsAddNothingAndLogStaysNewestFirst() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.apply(p, users("1"), users("2"), 1000);
        WatchlistStore.apply(p, users("1", "3"), users("2"), 2000);
        WatchlistStore.Result r = WatchlistStore.apply(p, users("1", "3"), users("2"), 3000);
        assertTrue(r.added.isEmpty());
        WatchlistStore.apply(p, users("1"), users("2"), 4000);
        assertEquals(4000, p.events.get(0).ts);
        assertEquals(WatchlistStore.LOST_FOLLOWER, p.events.get(0).type);
        assertEquals(2000, p.events.get(1).ts);
    }

    @Test
    public void eventLogIsCapped() {
        WatchlistStore.Profile p = new WatchlistStore.Profile();
        p.id = "9";
        WatchlistStore.apply(p, users(), users(), 1);
        String[] many = new String[WatchlistStore.MAX_EVENTS + 50];
        for (int i = 0; i < many.length; i++) many[i] = String.valueOf(100 + i);
        WatchlistStore.apply(p, users(many), users(), 2);
        assertEquals(WatchlistStore.MAX_EVENTS, p.events.size());
    }

    @Test
    public void indexTouchMovesProfileToTopAndKeepsAddedDate() {
        List<WatchlistStore.Entry> index = new ArrayList<>();
        WatchlistStore.Profile a = new WatchlistStore.Profile();
        a.id = "1";
        a.username = "alpha";
        WatchlistStore.Profile b = new WatchlistStore.Profile();
        b.id = "2";
        WatchlistStore.touch(index, a, 10);
        WatchlistStore.touch(index, b, 20);
        assertEquals("2", index.get(0).id);
        assertEquals(-1, index.get(0).followers); // never analyzed
        WatchlistStore.apply(a, users("7", "8"), users("9"), 30);
        WatchlistStore.touch(index, a, 30);
        assertEquals(2, index.size());
        assertEquals("1", index.get(0).id);
        assertEquals(10, index.get(0).added);
        assertEquals(2, index.get(0).followers);
        assertEquals(1, index.get(0).following);
        assertEquals("alpha", index.get(0).username);
    }
}
