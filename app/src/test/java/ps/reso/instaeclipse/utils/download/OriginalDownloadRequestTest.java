package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OriginalDownloadRequestTest {
    private static final String URL = "https://scontent.cdninstagram.com/selected.mp4";
    private static DownloadRequest.Rendition selected() {
        return new DownloadRequest.Rendition(URL, 1080, 1920, 30, "vp09", 1_000_000);
    }
    private static DownloadRequest.Options originalOptions() {
        return new DownloadRequest.Options(720, true, true, true);
    }
    private static DownloadRequest request(String manifest, List<DownloadRequest.Rendition> direct,
            DownloadRequest.AudioExpectation audio) {
        return new DownloadRequest("fixture", "", "fixture.mp4", manifest, direct, originalOptions(), audio);
    }
    private static DownloadRequest original() {
        return request("", Collections.singletonList(selected()), DownloadRequest.AudioExpectation.UNKNOWN);
    }

    @Test public void oldConstructorAndOldJsonRemainCustom() {
        DownloadRequest.Options options = new DownloadRequest.Options(720, true, true);
        assertFalse(options.originalSelection);
        DownloadRequest custom = new DownloadRequest("fixture", "", "fixture.mp4", "",
                Collections.singletonList(selected()), options, DownloadRequest.AudioExpectation.PRESENT);
        JsonObject json = JsonParser.parseString(custom.toJson()).getAsJsonObject();
        json.getAsJsonObject("options").remove("originalSelection");
        DownloadRequest restored = DownloadRequest.fromJson(json.toString());
        assertFalse(restored.options.originalSelection);
        assertEquals(720, restored.options.maxShortSide);
        assertTrue(restored.options.compatibility); assertTrue(restored.options.allowFallback);
        assertEquals(DownloadRequest.AudioExpectation.PRESENT, restored.audio);
    }

    @Test public void originalSnapshotAndRoundTripKeepOnlyTheSelectedDirectFile() {
        List<DownloadRequest.Rendition> input = new ArrayList<>(); input.add(selected());
        DownloadRequest snapshot = request("", input, DownloadRequest.AudioExpectation.UNKNOWN);
        input.clear();
        DownloadRequest restored = DownloadRequest.fromJson(snapshot.toJson());
        assertTrue(restored.options.originalSelection);
        assertEquals(0, restored.options.maxShortSide);
        assertFalse(restored.options.compatibility); assertFalse(restored.options.allowFallback);
        assertEquals("", restored.manifest);
        assertEquals(DownloadRequest.AudioExpectation.UNKNOWN, restored.audio);
        assertEquals(1, restored.direct.size()); assertEquals(URL, restored.direct.get(0).url);
        assertEquals(1080, restored.direct.get(0).width); assertEquals("vp09", restored.direct.get(0).codecs);
        assertThrows(UnsupportedOperationException.class, () -> restored.direct.clear());
    }

    @Test public void originalRejectsDashMultipleCandidatesMissingUrlAndAudioPromises() {
        List<DownloadRequest.Rendition> one = Collections.singletonList(selected());
        assertThrows(IllegalArgumentException.class, () -> request("<MPD/>", one, null));
        assertThrows(IllegalArgumentException.class, () -> request(" ", one, null));
        assertThrows(IllegalArgumentException.class, () -> request("", null, null));
        assertThrows(IllegalArgumentException.class, () -> request("", Collections.emptyList(), null));
        assertThrows(IllegalArgumentException.class, () -> request("", Arrays.asList(selected(), selected()), null));
        assertThrows(IllegalArgumentException.class, () -> request("", Arrays.asList(null, selected()), null));
        assertThrows(IllegalArgumentException.class, () -> request("", Collections.singletonList(null), null));
        assertThrows(IllegalArgumentException.class, () -> request("", Collections.singletonList(
                new DownloadRequest.Rendition("", 0, 0, 0, "", 0)), null));
        for (DownloadRequest.AudioExpectation audio : Arrays.asList(
                DownloadRequest.AudioExpectation.PRESENT, DownloadRequest.AudioExpectation.SILENT))
            assertThrows(IllegalArgumentException.class, () -> request("", one, audio));
    }

    @Test public void jsonCannotSmuggleDashOrAdditionalCandidatesIntoOriginal() {
        JsonObject manifest = JsonParser.parseString(original().toJson()).getAsJsonObject();
        manifest.addProperty("manifest", "<MPD/>");
        assertThrows(IllegalArgumentException.class, () -> DownloadRequest.fromJson(manifest.toString()));
        JsonObject extra = JsonParser.parseString(original().toJson()).getAsJsonObject();
        extra.getAsJsonArray("direct").add(extra.getAsJsonArray("direct").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> DownloadRequest.fromJson(extra.toString()));
        JsonObject audio = JsonParser.parseString(original().toJson()).getAsJsonObject();
        audio.addProperty("audio", "PRESENT");
        assertThrows(IllegalArgumentException.class, () -> DownloadRequest.fromJson(audio.toString()));
    }

    @Test public void incomingOriginalOptionsCannotReenableCustomControls() {
        JsonObject json = JsonParser.parseString(original().toJson()).getAsJsonObject();
        JsonObject options = json.getAsJsonObject("options");
        options.addProperty("maxShortSide", 720);
        options.addProperty("compatibility", true); options.addProperty("allowFallback", true);
        DownloadRequest restored = DownloadRequest.fromJson(json.toString());
        assertEquals(0, restored.options.maxShortSide);
        assertFalse(restored.options.compatibility); assertFalse(restored.options.allowFallback);
    }

    @Test public void originalDoesNotFilterRankJoinOrRequireAudio() throws Exception {
        DownloadRequest request = original();
        RenditionPicker.Plan plan = RenditionPicker.plan(request);
        assertEquals(DashManifest.State.ABSENT, plan.manifest.state);
        assertEquals(1, plan.choices.size()); assertEquals(0, plan.excludedByCeiling);
        RenditionPicker.Choice choice = plan.choices.get(0);
        assertSame(request.direct.get(0), choice.video);
        assertFalse(choice.dash); assertFalse(choice.expectAudio); assertNull(choice.audio); assertNull(choice.unsupported);
        // The selected VP9/1080p file survives the ignored 720p/compatibility preferences.
        assertEquals(URL, DownloadEngine.attemptPlan(request, plan, (attempted, fallback) -> {
            assertSame(choice, attempted); assertFalse(fallback); return attempted.video.url;
        }));
        DownloadEngine.verifySelection(new DownloadEngine.Metadata(1080, 1920, 0, 1_000_000,
                "video/x-vnd.on2.vp9", "", 0), request.options);
    }

    @Test public void originalStillRefusesDisallowedMediaUrlsBeforeAttempt() {
        for (String url : Arrays.asList("http://scontent.cdninstagram.com/file.mp4", "https://example.com/file.mp4",
                "https://127.0.0.1/file.mp4")) {
            DownloadRequest request = request("", Collections.singletonList(
                    new DownloadRequest.Rendition(url, 0, 0, 0, "", 0)), null);
            AtomicInteger attempts = new AtomicInteger();
            DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class, () ->
                    DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> attempts.incrementAndGet()));
            assertEquals("NO_MEDIA", failure.status); assertEquals(0, attempts.get()); assertFalse(failure.canOfferFallback);
        }
    }

    @Test public void originalFailureNeverRetriesOrOffersCustomFallback() {
        DownloadRequest request = original(); AtomicInteger attempts = new AtomicInteger();
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class, () ->
                DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> {
                    attempts.incrementAndGet(); throw new DownloadEngine.Failure("VERIFY", "Fixture verification failed", true);
                }));
        assertEquals(1, attempts.get()); assertEquals("VERIFY", failure.status);
        assertFalse(failure.canOfferFallback); assertFalse(failure.getMessage().contains("fallback"));
    }

    @Test public void summariesIdentifyOriginalAndCustomWithoutChangingCustomRanking() {
        DownloadRequest original = original(); RenditionPicker.Plan originalPlan = RenditionPicker.plan(original);
        assertEquals("Original direct-file selection", DownloadEngine.selectionSummary(original, originalPlan, originalPlan.choices.get(0)));
        DownloadRequest custom = new DownloadRequest("fixture", "", "fixture.mp4", "", Arrays.asList(
                new DownloadRequest.Rendition("https://scontent.cdninstagram.com/lower.mp4", 720, 1280, 30, "avc1", 0),
                selected()), new DownloadRequest.Options(0, false, false), null);
        RenditionPicker.Plan customPlan = RenditionPicker.plan(custom);
        assertEquals(URL, customPlan.choices.get(0).video.url);
        assertEquals("Custom; No manifest exposed; best discovered direct candidate",
                DownloadEngine.selectionSummary(custom, customPlan, customPlan.choices.get(0)));
    }
}
