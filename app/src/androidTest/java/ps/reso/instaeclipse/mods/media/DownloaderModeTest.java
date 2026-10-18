package ps.reso.instaeclipse.mods.media;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import static org.junit.Assert.*;

/** Synthetic captured values only: no downloads, host UI, Binder calls, or preference writes. */
@RunWith(AndroidJUnit4.class)
public class DownloaderModeTest {
    private static final String ORIGINAL_VIDEO = "https://cdninstagram.com/mode-fixture-original.mp4";
    private static final String CUSTOM_VIDEO = "https://cdninstagram.com/mode-fixture-custom.mp4";
    private static final String ORIGINAL_IMAGE = "https://cdninstagram.com/mode-fixture-original.jpg";
    private static final String CUSTOM_IMAGE = "https://cdninstagram.com/mode-fixture-custom.jpg";
    private static final String MANIFEST = "<MPD type=\"static\" mediaPresentationDuration=\"PT1S\"/>";
    private static final DownloadRequest.Options CUSTOM_OPTIONS = new DownloadRequest.Options(720, true, true);
    private boolean originalTimestamp;
    private boolean originalOfferAudio;

    private final SelectedMediaSnapshot.Access access = new SelectedMediaSnapshot.Access() {
        @Override public SelectedMediaSnapshot.Value read(Object receiver, String key) {
            Map<?, ?> model = (Map<?, ?>) receiver;
            return model.containsKey(key) ? SelectedMediaSnapshot.Value.of(model.get(key))
                    : SelectedMediaSnapshot.Value.missing();
        }
        @Override public String originalVideo(Object receiver) {
            return (String) ((Map<?, ?>) receiver).get("fixture_original_video");
        }
        @Override public String originalImage(Object receiver, boolean carouselChild) {
            return (String) ((Map<?, ?>) receiver).get("fixture_original_image");
        }
        @Override public OriginalMediaSelection.Audio audio(Object receiver, Object post) {
            return (OriginalMediaSelection.Audio) ((Map<?, ?>) receiver).get("fixture_audio");
        }
    };

    @Before public void deterministicFilenames() {
        originalTimestamp = FeatureFlags.downloaderAddTimestamp;
        originalOfferAudio = FeatureFlags.offerAudioDownload;
        FeatureFlags.downloaderAddTimestamp = false;
    }
    @After public void restoreInMemoryFlag() {
        FeatureFlags.downloaderAddTimestamp = originalTimestamp;
        FeatureFlags.offerAudioDownload = originalOfferAudio;
    }

    @Test public void feedAndReelAudioPermissionIsCapturedAndSurvivesModeChanges() throws Exception {
        SelectedMediaSnapshot snapshot = audioSnapshot();
        for (String type : List.of("post", "reel")) {
            FeatureFlags.offerAudioDownload = false;
            SelectedMediaDownloads.Captured disabled =
                    new SelectedMediaDownloads.Captured(snapshot, type, CUSTOM_OPTIONS, false);
            FeatureFlags.offerAudioDownload = true;
            assertFalse(disabled.offersAudio());
            assertFalse(disabled.withOriginal(true).offersAudio());
            SelectedMediaDownloads.Captured enabled =
                    new SelectedMediaDownloads.Captured(snapshot, type, CUSTOM_OPTIONS, true);
            FeatureFlags.offerAudioDownload = false;
            assertTrue(enabled.offersAudio());
            assertTrue(enabled.withOriginal(false).offersAudio());
            assertSame(snapshot.separateAudio, enabled.media.separateAudio);
        }
    }

    @Test public void carouselChildrenKeepCapturedAudioPermission() throws Exception {
        Map<String, Object> child = video("audio-child");
        child.put("fixture_audio", audio());
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(Map.of("id", "audio-carousel",
                "user", Map.of("username", "fixture-owner"), "carousel_media", List.of(child)), access);
        FeatureFlags.offerAudioDownload = false;
        SelectedMediaDownloads.Captured disabled =
                new SelectedMediaDownloads.Captured(snapshot, "post", CUSTOM_OPTIONS, false);
        FeatureFlags.offerAudioDownload = true;
        assertFalse(disabled.children.get(0).offersAudio());
        assertFalse(disabled.withOriginal(true).children.get(0).offersAudio());
        SelectedMediaDownloads.Captured enabled =
                new SelectedMediaDownloads.Captured(snapshot, "post", CUSTOM_OPTIONS, true);
        FeatureFlags.offerAudioDownload = false;
        assertTrue(enabled.children.get(0).offersAudio());
        assertTrue(enabled.withOriginal(false).children.get(0).offersAudio());
    }

    @Test public void storyAvailableAudioIsUnchangedAndMissingAudioIsNeverOffered() throws Exception {
        FeatureFlags.offerAudioDownload = false;
        SelectedMediaDownloads.Captured story =
                new SelectedMediaDownloads.Captured(audioSnapshot(), "story", CUSTOM_OPTIONS, false);
        assertTrue(story.offersAudio());
        assertTrue(story.withOriginal(true).offersAudio());
        FeatureFlags.offerAudioDownload = true;
        assertFalse(captured(video("no-separate-audio"), false).offersAudio());
    }

    private SelectedMediaSnapshot audioSnapshot() throws Exception {
        Map<String, Object> model = video("audio-fixture");
        model.put("user", Map.of("username", "fixture-owner"));
        model.put("fixture_audio", audio());
        return SelectedMediaSnapshot.capture(model, access);
    }

    private static OriginalMediaSelection.Audio audio() {
        return new OriginalMediaSelection.Audio("https://cdninstagram.com/audio-fixture.m4a",
                false, 0, -1, false);
    }

    @Test public void switchingCarouselModeReusesEachDetachedItemAndPreservesQueuedRequests() throws Exception {
        Map<String, Object> videoModel = video("video-child");
        Map<String, Object> photoModel = photo("photo-child");
        List<Object> mutableChildren = new ArrayList<>(List.of(videoModel, photoModel));
        Map<String, Object> parent = new HashMap<>(Map.of("id", "carousel-fixture",
                "user", Map.of("username", "fixture-owner"), "carousel_media", mutableChildren));
        SelectedMediaSnapshot snapshot = SelectedMediaSnapshot.capture(parent, access);
        SelectedMediaDownloads.Captured custom = new SelectedMediaDownloads.Captured(snapshot, "post", CUSTOM_OPTIONS, false);
        DownloadRequest queued = custom.children.get(0).selectedVideo();
        String queuedJson = queued.toJson();
        videoModel.put("id", "later-visible-item");
        videoModel.put("fixture_original_video", "https://cdninstagram.com/later.mp4");
        mutableChildren.clear();
        FeatureFlags.downloaderAddTimestamp = true; // A later setting must not rename an existing capture.

        SelectedMediaDownloads.Captured original = custom.withOriginal(true);
        SelectedMediaDownloads.Captured returned = original.withOriginal(false);
        assertNotSame(custom, original);
        assertSame(snapshot, original.media);
        assertSame(CUSTOM_OPTIONS, original.options);
        assertEquals(2, original.children.size());
        for (int i = 0; i < custom.children.size(); i++) {
            assertSame(custom.children.get(i).media, original.children.get(i).media);
            assertSame(custom.children.get(i).media, returned.children.get(i).media);
            assertSame(custom.children.get(i).video, original.children.get(i).video);
            assertSame(custom.children.get(i).originalVideo, original.children.get(i).originalVideo);
            assertSame(custom.children.get(i).video, returned.children.get(i).video);
            assertSame(custom.children.get(i).media.originalPlan, original.children.get(i).media.originalPlan);
            assertEquals(custom.children.get(i).audioFilename, original.children.get(i).audioFilename);
            assertEquals(custom.children.get(i).originalPhotoFilename, original.children.get(i).photoFilename);
            assertEquals(custom.children.get(i).photoFilename, returned.children.get(i).photoFilename);
            assertFalse(custom.children.get(i).original);
            assertTrue(original.children.get(i).original);
            assertFalse(returned.children.get(i).original);
            assertEquals("fixture-owner", original.children.get(i).media.owner);
        }
        assertEquals("video-child", original.children.get(0).media.id);
        assertEquals("photo-child", original.children.get(1).media.id);
        assertSame(queued, custom.children.get(0).selectedVideo());
        assertEquals(queuedJson, queued.toJson());
        assertEquals(queuedJson, returned.children.get(0).selectedVideo().toJson());
        assertCustomOptions(queued.options);
        try { original.children.clear(); fail("Captured carousel must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void originalDirectDescriptorRetainsProgressiveFallbackAndDistinctFilename() throws Exception {
        SelectedMediaDownloads.Captured custom = captured(video("video-fixture"), false);
        SelectedMediaDownloads.Captured original = custom.withOriginal(true);
        DownloadRequest selected = original.selectedVideo();
        assertSame(original.originalVideo, selected);
        assertEquals(custom.media.id, selected.mediaId);
        assertEquals(custom.media.owner, selected.owner);
        assertEquals("", selected.manifest);
        assertEquals(1, selected.direct.size());
        assertEquals(ORIGINAL_VIDEO, selected.direct.get(0).url);
        assertEquals(0, selected.direct.get(0).width);
        assertEquals(0, selected.direct.get(0).height);
        assertTrue(selected.options.originalSelection);
        assertEquals(0, selected.options.maxShortSide);
        assertFalse(selected.options.compatibility);
        assertFalse(selected.options.allowFallback);
        assertEquals(DownloadRequest.AudioExpectation.UNKNOWN, selected.audio);
        assertTrue(selected.filename.endsWith("_original.mp4"));
        assertTrue(custom.selectedVideo().filename.endsWith("_custom.mp4"));
        assertNotEquals(custom.selectedVideo().filename, selected.filename);
        assertEquals(MANIFEST, custom.selectedVideo().manifest);
        assertEquals(2, custom.selectedVideo().direct.size());
        assertEquals(DownloadRequest.AudioExpectation.PRESENT, custom.selectedVideo().audio);
        assertCustomOptions(custom.selectedVideo().options);
    }

    @Test public void originalPhotoUsesCapturedOriginalCandidateWithoutChangingCustomPhoto() throws Exception {
        SelectedMediaDownloads.Captured custom = captured(photo("photo-fixture"), false);
        SelectedMediaDownloads.Captured original = custom.withOriginal(true);
        assertSame(custom.media, original.media);
        assertNull(custom.video);
        assertNull(original.selectedVideo());
        assertEquals(CUSTOM_IMAGE, custom.photoUrl);
        assertEquals(ORIGINAL_IMAGE, original.photoUrl);
        assertTrue(custom.photoFilename.endsWith("_custom.jpg"));
        assertTrue(original.photoFilename.endsWith("_original.jpg"));
        assertNotEquals(custom.photoFilename, original.photoFilename);
        assertEquals(CUSTOM_IMAGE, original.withOriginal(false).photoUrl);
    }

    @Test public void unresolvedOriginalSelectionDoesNotSubstituteCustomCandidates() throws Exception {
        Map<String, Object> model = video("unresolved-fixture");
        model.put("fixture_original_video", "https://cdninstagram.com/unrelated.mp4");
        model.remove("fixture_original_image");
        SelectedMediaDownloads.Captured original = captured(model, true);
        assertNull(original.originalVideo);
        assertNull(original.selectedVideo());
        assertNull(original.photoUrl);
        assertEquals(MANIFEST, original.video.manifest);
        assertEquals(2, original.video.direct.size());
        assertEquals(CUSTOM_IMAGE, original.withOriginal(false).photoUrl);
        assertCustomOptions(original.video.options);
    }

    @Test public void framePayloadRetainsFullCustomCandidatesAndResetsModeWithoutMutatingQueue() throws Exception {
        SelectedMediaDownloads.Captured original = captured(video("frame-fixture"), true);
        assertEquals(MANIFEST, original.video.manifest);
        assertEquals(2, original.video.direct.size());
        assertFalse(original.video.options.originalSelection);
        String fullCustomJson = original.video.toJson();
        String selectedOriginalJson = original.selectedVideo().toJson();

        // The frame action passes Captured.video, even when the selected downloader is Original.
        DownloadRequest frame = frameRequest(original.video);
        assertEquals(original.video.mediaId, frame.mediaId);
        assertEquals(original.video.owner, frame.owner);
        assertEquals(original.video.filename, frame.filename);
        assertEquals(MANIFEST, frame.manifest);
        assertEquals(2, frame.direct.size());
        assertEquals(ORIGINAL_VIDEO, frame.direct.get(0).url);
        assertEquals(CUSTOM_VIDEO, frame.direct.get(1).url);
        assertEquals(1920, frame.direct.get(1).height);
        assertEquals(DownloadRequest.AudioExpectation.PRESENT, frame.audio);
        assertFrameOptions(frame.options);
        // The boundary also clears a direct-file Original flag if one reaches it explicitly.
        assertFrameOptions(frameRequest(original.selectedVideo()).options);
        assertEquals(fullCustomJson, original.video.toJson());
        assertEquals(selectedOriginalJson, original.selectedVideo().toJson());
        assertCustomOptions(original.video.options);
    }

    private SelectedMediaDownloads.Captured captured(Map<String, Object> model, boolean original) throws Exception {
        model.put("user", Map.of("username", "fixture-owner"));
        return new SelectedMediaDownloads.Captured(SelectedMediaSnapshot.capture(model, access),
                "story", CUSTOM_OPTIONS, original);
    }
    private static DownloadRequest frameRequest(DownloadRequest input) {
        String json = input.toJson();
        Map<String, String> outgoing = new HashMap<>(Map.of(BrokerPayload.FRAME_REQUEST, json));
        Map<String, String> accepted = BrokerPayload.activity(BrokerPayload.FRAME, outgoing);
        assertEquals(json, outgoing.get(BrokerPayload.FRAME_REQUEST));
        outgoing.clear();
        return DownloadRequest.fromJson(accepted.get(BrokerPayload.FRAME_REQUEST));
    }
    private static void assertCustomOptions(DownloadRequest.Options options) {
        assertEquals(720, options.maxShortSide);
        assertTrue(options.compatibility);
        assertTrue(options.allowFallback);
        assertFalse(options.originalSelection);
    }
    private static void assertFrameOptions(DownloadRequest.Options options) {
        assertEquals(0, options.maxShortSide);
        assertFalse(options.compatibility);
        assertFalse(options.allowFallback);
        assertFalse(options.originalSelection);
    }
    private static Map<String, Object> photo(String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("media_type", 1);
        result.put("fixture_original_image", ORIGINAL_IMAGE);
        result.put("image_versions2", Map.of("candidates", List.of(
                Map.of("url", ORIGINAL_IMAGE, "width", 320, "height", 640),
                Map.of("url", CUSTOM_IMAGE, "width", 1080, "height", 2160))));
        return result;
    }
    private static Map<String, Object> video(String id) {
        Map<String, Object> result = photo(id);
        result.put("media_type", 2);
        result.put("video_dash_manifest", MANIFEST);
        result.put("fixture_original_video", ORIGINAL_VIDEO);
        result.put("has_audio", true);
        result.put("video_versions", List.of(
                Map.of("url", ORIGINAL_VIDEO, "width", 360, "height", 640),
                Map.of("url", CUSTOM_VIDEO, "width", 1080, "height", 1920)));
        return result;
    }
}
