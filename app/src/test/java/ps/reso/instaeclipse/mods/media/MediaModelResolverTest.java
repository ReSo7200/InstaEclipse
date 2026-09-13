package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.List;

public class MediaModelResolverTest {

    @Test
    public void resolvesLiveTreeWhenLegacyInterfaceIsMissing() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (MediaModelResolver.MUTABLE_DICT_CLASS.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                if (MediaModelResolver.LIVE_TREE_DICT_CLASS.equals(name)) {
                    return ModernDict.class;
                }
                return super.loadClass(name);
            }
        };

        MediaModelResolver.Result result = MediaModelResolver.resolve(loader);

        assertNull(result.mutableDictClass);
        assertEquals(ModernDict.class, result.liveTreeDictClass);
        assertEquals(1, result.listCandidates.size());
        assertEquals("videoVersions", result.listCandidates.get(0).getName());
    }

    @Test
    public void findsConcreteDictionaryStoredInObjectTypedField() {
        ModernDict dict = new ModernDict();
        MediaContainer media = new MediaContainer(dict);
        MediaModelResolver.Result model = new MediaModelResolver.Result(
                null, ModernDict.class, List.of());

        Object found = MediaModelResolver.findDictionary(media, model, 2);

        assertNotNull(found);
        assertSame(dict, found);
    }

    @Test
    public void findsDictionaryExposedOnlyByModelGetter() {
        MethodOnlyContainer media = new MethodOnlyContainer();
        MediaModelResolver.Result model = new MediaModelResolver.Result(
                null, ModernDict.class, List.of());

        Object found = MediaModelResolver.findDictionary(media, model, 2);

        assertNotNull(found);
        assertEquals(ModernDict.class, found.getClass());
    }

    @Test
    public void findsDictionaryInsideIterableWrapper() {
        ModernDict dict = new ModernDict();

        Object found = MediaModelResolver.findObjectOfType(List.of(dict), ModernDict.class, 2);

        assertSame(dict, found);
    }

    static final class MediaContainer {
        private final Object backing;

        MediaContainer(Object backing) {
            this.backing = backing;
        }
    }

    static final class ModernDict {
        public List<String> videoVersions() {
            return List.of("video");
        }

        public String unrelated() {
            return "ignored";
        }
    }

    static final class MethodOnlyContainer {
        public ModernDict getExtendedData() {
            return new ModernDict();
        }
    }
}
