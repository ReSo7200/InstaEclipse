package ps.reso.instaeclipse.utils.users;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FollowersHistoryTest {

    private static Map<String, String[]> users(String... idsAndNames) {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (int i = 0; i < idsAndNames.length; i += 2) m.put(idsAndNames[i], new String[]{idsAndNames[i + 1], null});
        return m;
    }

    @Test
    public void diffFindsLostAndGained() {
        List<FollowersHistory.Event> d = FollowersHistory.diff(
                users("1", "amy", "2", "bob", "3", "cat"),
                users("1", "amy", "3", "cat", "4", "dan"), 200, 100);
        assertEquals(2, d.size());
        assertEquals(FollowersHistory.LOST, d.get(0).type);
        assertEquals("bob", d.get(0).username);
        assertEquals(FollowersHistory.GAINED, d.get(1).type);
        assertEquals("dan", d.get(1).username);
        assertEquals(200, d.get(1).ts);
        assertEquals(100, d.get(1).since);
    }

    @Test
    public void renamedUserIsNotAChange() {
        assertTrue(FollowersHistory.diff(users("1", "old_name"), users("1", "new_name"), 2, 1).isEmpty());
    }

    @Test
    public void firstCheckOnlySavesTheSnapshot() {
        FollowersHistory.State s = new FollowersHistory.State();
        FollowersHistory.Result r = FollowersHistory.apply(s, users("1", "amy", "2", "bob"), 1000);
        assertTrue(r.first);
        assertEquals(2, r.count);
        assertTrue(r.added.isEmpty());
        assertEquals(1000, s.lastCheck);
        assertEquals(2, s.users.size());
    }

    @Test
    public void laterChecksPrependEvents() {
        FollowersHistory.State s = new FollowersHistory.State();
        FollowersHistory.apply(s, users("1", "amy", "2", "bob"), 1000);
        FollowersHistory.Result r1 = FollowersHistory.apply(s, users("1", "amy"), 2000);
        assertFalse(r1.first);
        assertEquals(1000, r1.previousCheck);
        assertEquals(1, r1.added.size());
        FollowersHistory.Result r2 = FollowersHistory.apply(s, users("1", "amy", "5", "eve"), 3000);
        assertEquals(2, r2.all.size());
        assertEquals("eve", r2.all.get(0).username); // newest first
        assertEquals("bob", r2.all.get(1).username);
        assertEquals(3000, s.lastCheck);
    }
}
