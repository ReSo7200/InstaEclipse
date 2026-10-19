package ps.reso.instaeclipse.utils.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

public class DexKitQueryCacheTest {

    private static FindMethod getter(String key) {
        return FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass("com.instagram.feed.media.Media").paramCount(0).usingNumbers(key.hashCode()));
    }

    @Test
    public void sameQueryGivesSameKey() {
        String a = DexKitQueryCache.key("fm", getter("video_versions"));
        assertNotNull(a);
        assertEquals(a, DexKitQueryCache.key("fm", getter("video_versions")));
    }

    @Test
    public void differentQueriesGetDifferentKeys() {
        assertNotEquals(DexKitQueryCache.key("fm", getter("video_versions")),
                DexKitQueryCache.key("fm", getter("image_versions2")));
        assertNotEquals(DexKitQueryCache.key("fm", FindMethod.create().matcher(MethodMatcher.create().usingStrings("a"))),
                DexKitQueryCache.key("fm", FindMethod.create().matcher(MethodMatcher.create().usingStrings("a").paramCount(0))));
    }

    @Test
    public void methodAndClassQueriesDoNotShareKeys() {
        String m = DexKitQueryCache.key("fm", FindMethod.create().matcher(MethodMatcher.create().usingStrings("NotesTrayAdapter")));
        String c = DexKitQueryCache.key("fc", FindClass.create().matcher(ClassMatcher.create().usingStrings("NotesTrayAdapter")));
        assertNotNull(c);
        assertNotEquals(m, c);
    }
}
