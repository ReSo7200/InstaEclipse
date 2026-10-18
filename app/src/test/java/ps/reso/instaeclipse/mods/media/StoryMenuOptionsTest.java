package ps.reso.instaeclipse.mods.media;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class StoryMenuOptionsTest {
    @Test public void highlightListGetsDownloadWithoutChangingNativeRows() {
        ArrayList<CharSequence> original = new ArrayList<>(Arrays.asList("Remove from highlight", "Edit highlight", "Share"));
        Object result = StoryMenuOptions.appendDownload(original, "Download");
        assertTrue(result instanceof ArrayList);
        assertEquals(List.of("Remove from highlight", "Edit highlight", "Share", "Download"), result);
        assertEquals(3, original.size());
        assertNotSame(original, result);
    }

    @Test public void duplicateLabelIsComparedByContentAndNotAddedTwice() {
        ArrayList<CharSequence> original = new ArrayList<>(List.of(new StringBuilder("Download")));
        assertSame(original, StoryMenuOptions.appendDownload(original, "Download"));
        Object once = StoryMenuOptions.appendDownload(new ArrayList<>(List.of("Share")), "Download");
        assertSame(once, StoryMenuOptions.appendDownload(once, "Download"));
    }

    @Test public void legacyArrayRetainsItsRuntimeTypeAndOriginalContents() {
        String[] original = {"Save video", "Archive"};
        Object result = StoryMenuOptions.appendDownload(original, "Download");
        assertEquals(String[].class, result.getClass());
        assertArrayEquals(new String[]{"Save video", "Archive", "Download"}, (String[]) result);
        assertArrayEquals(new String[]{"Save video", "Archive"}, original);
    }

    @Test public void styledLabelsAndNullEntriesArePreserved() {
        CharSequence styled = new StringBuilder("Share");
        CharSequence[] original = {styled, null};
        CharSequence[] result = (CharSequence[]) StoryMenuOptions.appendDownload(original, "Download");
        assertSame(styled, result[0]);
        assertNull(result[1]);
        assertEquals("Download", result[2]);
    }

    @Test public void nonLabelListAndUnsupportedArrayFailClosed() {
        ArrayList<Object> objects = new ArrayList<>(List.of("Share", new Object()));
        assertSame(objects, StoryMenuOptions.appendDownload(objects, "Download"));
        StringBuilder[] builders = {new StringBuilder("Share")};
        assertSame(builders, StoryMenuOptions.appendDownload(builders, "Download"));
    }

    @Test public void unresolvedTranslationDoesNotAddABlankRow() {
        ArrayList<String> original = new ArrayList<>(List.of("Share"));
        assertSame(original, StoryMenuOptions.appendDownload(original, ""));
        assertSame(original, StoryMenuOptions.appendDownload(original, null));
        assertNull(StoryMenuOptions.appendDownload(null, "Download"));
    }

    static final class Item { }
    static final class Owner {
        Item current;
        static Item unrelated;
        static ArrayList<String> labels(Owner owner) { return null; }
        static ArrayList<String> highlightLabels(Owner owner, Object helper) { return null; }
        static ArrayList<String> unrelated(Object controller) { return null; }
        ArrayList<String> instance(Owner owner) { return null; }
    }
    static final class AmbiguousOwner {
        Item current, preloaded;
        static ArrayList<String> labels(AmbiguousOwner owner) { return null; }
    }

    @Test public void listBuilderRequiresTheExactNativeOwnerAndItemField() throws Exception {
        assertTrue(StoryMenuOptions.isOwnedListBuilder(Owner.class.getDeclaredMethod("labels", Owner.class), Item.class));
        assertTrue(StoryMenuOptions.isOwnedListBuilder(Owner.class.getDeclaredMethod("highlightLabels", Owner.class, Object.class), Item.class));
        assertFalse(StoryMenuOptions.isOwnedListBuilder(Owner.class.getDeclaredMethod("unrelated", Object.class), Item.class));
        assertFalse(StoryMenuOptions.isOwnedListBuilder(Owner.class.getDeclaredMethod("instance", Owner.class), Item.class));
        assertFalse(StoryMenuOptions.isOwnedListBuilder(AmbiguousOwner.class.getDeclaredMethod("labels", AmbiguousOwner.class), Item.class));
    }
}
