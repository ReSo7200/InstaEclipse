package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;

import ps.reso.instaeclipse.utils.ghost.ThreadIdResolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ThreadIdResolverTest {

    @Test
    public void testMaskId() {
        assertEquals("null", ThreadIdResolver.maskId(null));
        assertEquals("abc…", ThreadIdResolver.maskId("abc"));
        assertEquals("abcd…", ThreadIdResolver.maskId("abcd"));
        assertEquals("abcd…", ThreadIdResolver.maskId("abcdefg"));
    }

    public static class DirectThreadKeyMock {
        public String id;
        public DirectThreadKeyMock(String id) {
            this.id = id;
        }
    }

    public static class MockDirectThreadKeySubclass extends DirectThreadKeyMock {
        public static String staticString = "ignore_me";
        public String otherValue = "other";

        public MockDirectThreadKeySubclass(String id) {
            super(id);
        }
    }

    @Test
    public void testThreadIdOf() {
        // DirectThreadKeyMock does not have 'DirectThreadKey' in the name strictly evaluated by the mock here,
        // but ThreadIdResolver.threadIdOf only iterates through fields and looks for Strings, not strictly name for `threadIdOf`.
        // Let's create an anonymous class to be sure if necessary, but we are just passing an object.

        MockDirectThreadKeySubclass dtk = new MockDirectThreadKeySubclass("my_thread_id");

        // It skips staticString, and finds "other" first in the subclass because declared fields of subclass are scanned first!
        // Wait, the real implementation actually scans up the class hierarchy, so it checks the subclass first.
        // If the real DirectThreadKey has the ID in the subclass, it finds it.
        // Actually `firstStringField` logic iterates:
        // class subclass -> fields (otherValue)
        // class superclass -> fields (id)

        // Let's mock exactly what we expect.
        Object fakeKey = new Object() {
            String fakeId = "real_id";
        };

        assertEquals("real_id", ThreadIdResolver.threadIdOf(fakeKey));

        // Skip static
        Object fakeKeyWithStatic = new Object() {
            static String staticVar = "static";
            String fakeId = "real_id";
        };

        assertEquals("real_id", ThreadIdResolver.threadIdOf(fakeKeyWithStatic));
    }
}