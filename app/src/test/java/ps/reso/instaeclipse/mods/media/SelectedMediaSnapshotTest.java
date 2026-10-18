package ps.reso.instaeclipse.mods.media;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import static org.junit.Assert.*;

public class SelectedMediaSnapshotTest {
    private final SelectedMediaSnapshot.Access access = (receiver, key) -> {
        Map<?, ?> map = (Map<?, ?>) receiver;
        return map.containsKey(key) ? SelectedMediaSnapshot.Value.of(map.get(key))
                : SelectedMediaSnapshot.Value.missing();
    };
    private Map<String, Object> video() {
        Map<String, Object> media = new HashMap<>();
        media.put("id", "fixture-item");
        media.put("media_type", 2);
        media.put("video_dash_manifest", "<MPD/>");
        media.put("video_versions", new ArrayList<>(List.of(new HashMap<>(Map.of(
                "url", "https://cdninstagram.com/fixture.mp4", "width", 1280, "height", 720)))));
        return media;
    }
    private SelectedMediaSnapshot.Access withOriginals() {
        return new SelectedMediaSnapshot.Access() {
            @Override public SelectedMediaSnapshot.Value read(Object receiver, String key) throws Exception {
                return access.read(receiver, key);
            }
            @Override public String originalVideo(Object receiver) {
                return (String) ((Map<?, ?>) receiver).get("originalVideo");
            }
            @Override public String originalImage(Object receiver, boolean carouselChild) {
                return (String) ((Map<?, ?>) receiver).get("originalImage");
            }
        };
    }
    @Test public void detachedSnapshotSurvivesSwipeModelAndListMutation() throws Exception {
        Map<String, Object> media = video();
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(media, access);
        media.put("id", "different-item");
        media.put("video_dash_manifest", "different-manifest");
        ((List<?>) media.get("video_versions")).clear();
        assertEquals("fixture-item", snapshot.id);
        assertEquals("<MPD/>", snapshot.manifest);
        assertEquals(1, snapshot.renditions.size());
        assertEquals(720, snapshot.renditions.get(0).height);
        try { snapshot.renditions.clear(); fail("mutable snapshot"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void carouselMusicIsCapturedPerChildWithExactParentAndSurvivesMutation() throws Exception {
        Map<String, Object> first = video(), second = video();
        second.put("id", "second-fixture");
        Map<String, Object> parent = new HashMap<>(Map.of("id", "album",
                "carousel_media", new ArrayList<>(List.of(first, second))));
        List<Object> visited = new ArrayList<>();
        SelectedMediaSnapshot.Access musicAccess = new SelectedMediaSnapshot.Access() {
            @Override public SelectedMediaSnapshot.Value read(Object receiver, String key) throws Exception {
                return access.read(receiver, key);
            }
            @Override public OriginalMediaSelection.Audio audio(Object receiver, Object album) {
                assertSame(parent, album);
                visited.add(receiver);
                return new OriginalMediaSelection.Audio("https://cdninstagram.com/music.mp4",
                        true, receiver == first ? 4000 : 8000, 30000, true);
            }
        };
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(parent, musicAccess);
        ((List<?>) parent.get("carousel_media")).clear();
        assertEquals(3, visited.size());
        assertEquals(2, snapshot.children.size());
        assertEquals(4000, snapshot.children.get(0).separateAudio.startMs);
        assertEquals(8000, snapshot.children.get(1).separateAudio.startMs);
        assertTrue(snapshot.children.get(0).separateAudio.postWide);
    }
    @Test public void unresolvedManifestBindingDiffersFromAbsentManifest() throws Exception {
        Map<String, Object> media = video();
        media.put("video_dash_manifest", null);
        assertNull(SelectedMediaSnapshot.capture(media, access).manifest);
        media.remove("video_dash_manifest");
        try { SelectedMediaSnapshot.capture(media, access); fail("unknown DASH binding accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("adapter incomplete")); }
    }
    @Test public void metadataComesFromModelNotUrlText() throws Exception {
        Map<String, Object> media = video();
        media.put("video_versions", List.of(Map.of("url", "https://cdninstagram.com/2160x3840.mp4")));
        DownloadRequest.Rendition rendition = SelectedMediaSnapshot.capture(media, access).renditions.get(0);
        assertEquals(0, rendition.width);
        assertEquals(0, rendition.height);
        assertEquals(0, rendition.frameRate, 0);
    }
    @Test public void photoWithMusicRetainsBothChoicesAndAudioExpectation() throws Exception {
        Map<String, Object> media = video();
        media.put("is_story_image_with_music", true);
        media.put("image_versions2", Map.of("candidates", List.of(
                Map.of("url", "https://cdninstagram.com/small.jpg", "width", 320, "height", 640),
                Map.of("url", "https://cdninstagram.com/full.jpg", "width", 1080, "height", 2160))));
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(media, access);
        assertTrue(snapshot.photoWithMusic);
        assertTrue(snapshot.video);
        assertEquals("https://cdninstagram.com/full.jpg", snapshot.imageUrl);
        assertEquals(DownloadRequest.AudioExpectation.PRESENT, snapshot.audio);
    }
    @Test public void silentMediaIsExplicitAndUnknownRemainsUnknown() throws Exception {
        Map<String, Object> media = video();
        assertEquals(DownloadRequest.AudioExpectation.UNKNOWN, SelectedMediaSnapshot.capture(media, access).audio);
        media.put("has_audio", false);
        assertEquals(DownloadRequest.AudioExpectation.SILENT, SelectedMediaSnapshot.capture(media, access).audio);
    }
    @Test public void videoWithoutRenditionsNeverFallsBackToCover() throws Exception {
        Map<String, Object> media = video();
        media.put("video_dash_manifest", null);
        media.put("video_versions", List.of());
        media.put("image_versions2", Map.of("candidates", List.of(Map.of("url", "https://cdninstagram.com/cover.jpg"))));
        try { SelectedMediaSnapshot.capture(media, access); fail("cover accepted for video"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("no exposed")); }
    }
    @Test public void mixedCarouselCapturesEveryItemWithoutChangingInput() throws Exception {
        Map<String, Object> photo = new HashMap<>(Map.of("id", "photo", "media_type", 1,
                "image_versions2", Map.of("candidates", List.of(Map.of("url", "https://cdninstagram.com/photo.jpg")))));
        List<Object> children = new ArrayList<>(List.of(photo, video()));
        Map<String, Object> parent = new HashMap<>(Map.of("id", "carousel", "carousel_media", children));
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(parent, access);
        assertEquals(2, children.size());
        children.clear();
        assertEquals(2, snapshot.children.size());
        assertFalse(snapshot.children.get(0).video);
        assertTrue(snapshot.children.get(1).video);
    }

    @Test public void originalPhotoRemainsDistinctFromCustomLargestPhoto() throws Exception {
        Map<String, Object> media = video();
        String small = "https://cdninstagram.com/small.jpg", large = "https://cdninstagram.com/large.jpg";
        media.put("image_versions2", Map.of("candidates", List.of(
                Map.of("url", small, "width", 320, "height", 640),
                Map.of("url", large, "width", 1080, "height", 2160))));
        media.put("originalVideo", "https://cdninstagram.com/fixture.mp4");
        media.put("originalImage", small);
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(media, withOriginals());
        assertEquals(small, snapshot.originalImageUrl);
        assertEquals(large, snapshot.imageUrl);
        assertEquals("https://cdninstagram.com/fixture.mp4", snapshot.originalVideoUrl);
        assertEquals("<MPD/>", snapshot.request("fixture.mp4", new DownloadRequest.Options(0, false, false)).manifest);
    }

    @Test public void originalWrongItemOrChangedSignedUrlIsRejectedWithoutSubstitution() throws Exception {
        Map<String, Object> media = video();
        media.put("originalVideo", "https://cdninstagram.com/fixture.mp4?different=1");
        media.put("originalImage", "https://cdninstagram.com/neighbor.jpg");
        media.put("image_versions2", Map.of("candidates", List.of(Map.of("url", "https://cdninstagram.com/selected.jpg"))));
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(media, withOriginals());
        assertNull(snapshot.originalVideoUrl);
        assertNull(snapshot.originalImageUrl);
        assertEquals(1, snapshot.renditions.size());
        assertEquals("https://cdninstagram.com/selected.jpg", snapshot.imageUrl);
    }

    @Test public void originalNullOrFailureDoesNotChangeCustomCapture() throws Exception {
        Map<String, Object> media = video();
        SelectedMediaSnapshot defaultSnapshot = SelectedMediaSnapshot.capture(media, access);
        assertNull(defaultSnapshot.originalVideoUrl);
        assertNull(defaultSnapshot.originalImageUrl);
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(media, new SelectedMediaSnapshot.Access() {
            @Override public SelectedMediaSnapshot.Value read(Object receiver, String key) throws Exception {
                return access.read(receiver, key);
            }
            @Override public String originalVideo(Object receiver) throws Exception { throw new Exception("unavailable"); }
            @Override public String originalImage(Object receiver, boolean child) { throw new LinkageError(); }
        });
        assertNull(snapshot.originalVideoUrl);
        assertNull(snapshot.originalImageUrl);
        assertEquals(defaultSnapshot.manifest, snapshot.manifest);
        assertEquals(defaultSnapshot.renditions.get(0).url, snapshot.renditions.get(0).url);
    }

    @Test public void originalRunsBeforeCustomGettersMaterializeModel() throws Exception {
        List<String> calls = new ArrayList<>();
        SelectedMediaSnapshot.capture(video(), new SelectedMediaSnapshot.Access() {
            @Override public SelectedMediaSnapshot.Value read(Object receiver, String key) throws Exception {
                calls.add(key); return access.read(receiver, key);
            }
            @Override public String originalVideo(Object receiver) { calls.add("originalVideo"); return null; }
            @Override public String originalImage(Object receiver, boolean child) {
                assertFalse(child); calls.add("originalImage"); return null;
            }
        });
        assertEquals(List.of("originalVideo", "originalImage", "id"), calls.subList(0, 3));
    }

    @Test public void carouselOriginalChoicesArePerChildAndDetached() throws Exception {
        Map<String, Object> first = video(), second = video();
        String firstUrl = "https://cdninstagram.com/fixture.mp4", secondUrl = "https://cdninstagram.com/second.mp4";
        first.put("originalVideo", firstUrl);
        second.put("video_versions", new ArrayList<>(List.of(Map.of("url", secondUrl))));
        second.put("originalVideo", firstUrl); // A neighbor reached by a legacy graph walk must not pass.
        List<Object> items = new ArrayList<>(List.of(first, second));
        Map<String, Object> parent = new HashMap<>(Map.of("id", "carousel", "carousel_media", items));
        parent.put("originalVideo", firstUrl);
        List<Boolean> flags = new ArrayList<>();
        SelectedMediaSnapshot.Access originals = withOriginals();
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(parent, new SelectedMediaSnapshot.Access() {
            @Override public SelectedMediaSnapshot.Value read(Object receiver, String key) throws Exception { return access.read(receiver, key); }
            @Override public String originalVideo(Object receiver) throws Exception { return originals.originalVideo(receiver); }
            @Override public String originalImage(Object receiver, boolean child) { flags.add(child); return null; }
        });
        items.clear(); first.put("originalVideo", secondUrl);
        ((List<?>) first.get("video_versions")).clear();
        assertEquals(List.of(false, true, true), flags);
        assertNull(snapshot.originalVideoUrl);
        assertEquals(firstUrl, snapshot.children.get(0).originalVideoUrl);
        assertNull(snapshot.children.get(1).originalVideoUrl);
        assertEquals(secondUrl, snapshot.children.get(1).renditions.get(0).url);
    }

    @Test public void imageAllowlistIsBoundedDeduplicatedAndImmutable() throws Exception {
        Map<String, Object> media = video();
        String url = "https://cdninstagram.com/photo.jpg";
        Map<String, Object> candidate = new HashMap<>(Map.of("url", url));
        List<Object> candidates = new ArrayList<>(List.of(candidate, candidate));
        media.put("image_versions2", Map.of("candidates", candidates));
        media.put("originalImage", url);
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(media, withOriginals());
        candidate.put("url", "https://cdninstagram.com/other.jpg"); candidates.clear();
        assertEquals(List.of(url), snapshot.imageUrls);
        assertEquals(url, snapshot.originalImageUrl);
        try { snapshot.imageUrls.clear(); fail("mutable image allowlist"); }
        catch (UnsupportedOperationException expected) { }
        for (int i = 0; i < 101; i++) candidates.add(candidate);
        try { SelectedMediaSnapshot.capture(media, access); fail("unbounded candidates"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Too many image")); }
        candidates.clear(); candidates.add(Map.of("url", "x".repeat(4097)));
        try { SelectedMediaSnapshot.capture(media, access); fail("unbounded URL"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Image URL exceeds")); }
    }
}
