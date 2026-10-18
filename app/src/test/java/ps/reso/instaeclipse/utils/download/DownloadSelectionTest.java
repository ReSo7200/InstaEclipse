package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DownloadSelectionTest {
    private static final String HOST = "https://scontent.cdninstagram.com/";
    private static String rep(String id, int width, int height, String codec) {
        return "<Representation id='" + id + "' mimeType='video/mp4' width='" + width + "' height='" + height
                + "' codecs='" + codec + "' frameRate='30000/1001' bandwidth='1200000'><BaseURL>" + HOST + id + ".mp4</BaseURL></Representation>";
    }
    private static String manifest(String reps, String extra) {
        return "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' type='static'><Period><AdaptationSet>" + reps
                + "</AdaptationSet>" + extra + "</Period></MPD>";
    }
    private static String sound(String codec) {
        return "<AdaptationSet mimeType='audio/mp4' codecs='" + codec + "'><Representation id='sound' bandwidth='128000'><BaseURL>"
                + HOST + "sound.mp4</BaseURL></Representation></AdaptationSet>";
    }
    private static DownloadRequest.Rendition direct(String name, int side, String codec) {
        return new DownloadRequest.Rendition(HOST + name + ".mp4", side, side * 16 / 9, 0, codec, 0);
    }
    private static DownloadRequest request(String xml, boolean fallback, DownloadRequest.Rendition... direct) {
        return new DownloadRequest("test-item", "test-owner", "fixture.mp4", xml, Arrays.asList(direct),
                new DownloadRequest.Options(0, false, fallback), DownloadRequest.AudioExpectation.UNKNOWN);
    }

    @Test public void dash1080BeatsDirect720AndPairsAudio() {
        RenditionPicker.Plan plan = RenditionPicker.plan(request(manifest(rep("hi", 1080, 1920, "av01.0.08M.08"), sound("mp4a.40.2")),
                false, direct("lo", 720, "avc1.4d401f")));
        assertEquals(2, plan.choices.size());
        assertTrue(plan.choices.get(0).dash);
        assertEquals(1080, plan.choices.get(0).video.width);
        assertTrue(plan.choices.get(0).expectAudio);
        assertNotNull(plan.choices.get(0).audio);
    }
    @Test public void bestHasNo1080CeilingAndUsesActualDimensionsNotLabels() {
        String xml = manifest(rep("ultra", 2160, 3840, "vp09.00.40.08").replace("id='ultra'", "id='ultra' FBQualityLabel='480p'"), "");
        RenditionPicker.Plan p = RenditionPicker.plan(request(xml, false, direct("1080p", 1080, "avc1")));
        assertEquals(2160, p.choices.get(0).video.shortSide());
    }
    @Test public void sameResolutionDoesNotAutomaticallyPreferDirect() {
        DownloadRequest.Rendition direct = new DownloadRequest.Rendition(HOST + "direct.mp4", 1080, 1920, 30, "avc1", 8_000_000);
        String dash = manifest(rep("dash", 1080, 1920, "avc1").replace("30000/1001", "60"), "");
        assertTrue(RenditionPicker.plan(request(dash, false, direct)).choices.get(0).dash);
    }
    @Test public void bitrateAcrossUnrelatedCodecsDoesNotRankQuality() {
        DownloadRequest.Options best = new DownloadRequest.Options(0, false, false);
        DownloadRequest.Rendition a = new DownloadRequest.Rendition(HOST + "a", 1080, 1920, 30, "av01", 1);
        DownloadRequest.Rendition b = new DownloadRequest.Rendition(HOST + "b", 1080, 1920, 30, "avc1", Long.MAX_VALUE);
        int before = RenditionPicker.compare(a, b, best);
        DownloadRequest.Rendition flipped = new DownloadRequest.Rendition(b.url, b.width, b.height, 30, b.codecs, 1);
        assertEquals(Integer.signum(before), Integer.signum(RenditionPicker.compare(a, flipped, best)));
    }
    @Test public void unknownMetadataStaysUnknownDespiteUrlMarkers() {
        DownloadRequest.Rendition unknown = direct("2160p_9999x9999", 0, "");
        RenditionPicker.Plan p = RenditionPicker.plan(request("", false, unknown, direct("measured", 720, "")));
        assertEquals(720, p.choices.get(0).video.shortSide());
        assertEquals(0, unknown.shortSide());
        assertEquals(DashManifest.State.ABSENT, p.manifest.state);
    }
    @Test public void ceilingExcludesAboveInsteadOfSilentlyExceeding() {
        DownloadRequest.Options limit = new DownloadRequest.Options(720, false, false);
        assertTrue(RenditionPicker.compare(direct("720", 720, ""), direct("1080", 1080, ""), limit) < 0);
        DownloadRequest r = new DownloadRequest("1", "", "fixture.mp4", "", Collections.singletonList(direct("above", 1080, "")), limit, null);
        RenditionPicker.Plan plan = RenditionPicker.plan(r);
        assertTrue(plan.choices.isEmpty());
        DownloadEngine.Failure f = assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.attemptPlan(r, plan, (c, fallback) -> true));
        assertEquals("CEILING", f.status);
    }
    @Test public void compatibilityMustNotBypassResolutionCeiling() {
        DownloadRequest r = new DownloadRequest("1", "", "fixture.mp4", "", Arrays.asList(direct("above-h264", 1080, "avc1"),
                direct("below-av1", 720, "av01")), new DownloadRequest.Options(720, true, false), null);
        RenditionPicker.Plan plan = RenditionPicker.plan(r);
        assertEquals(1, plan.choices.size()); assertEquals(720, plan.choices.get(0).video.shortSide());
    }
    @Test public void compatibilityPrefersH264ButAllowsOnlyAvailableHevc() throws Exception {
        DownloadRequest.Options options = new DownloadRequest.Options(0, true, false);
        DownloadRequest.Rendition hevc = direct("hevc", 1080, "hvc1");
        DownloadRequest.Rendition h264 = direct("h264", 720, "avc1");
        assertTrue(RenditionPicker.compare(h264, hevc, options) < 0);
        DownloadRequest r = new DownloadRequest("1", "", "fixture.mp4", "", Collections.singletonList(hevc), options, null);
        assertNull(RenditionPicker.plan(r).choices.get(0).unsupported);
        DownloadEngine.verifySelection(new DownloadEngine.Metadata(1080, 1920, 0, 1_000_000,
                "video/hevc", "audio/mp4a-latm", 42), options);
    }
    @Test public void compatibilityKeepsLessCommonAacWhenThatIsTheOnlyAudio() {
        DownloadRequest r = new DownloadRequest("1", "", "fixture.mp4",
                manifest(rep("hevc", 1080, 1920, "hvc1"), sound("mp4a.40.42")), Collections.emptyList(),
                new DownloadRequest.Options(0, true, false), null);
        RenditionPicker.Choice choice = RenditionPicker.plan(r).choices.get(0);
        assertNotNull(choice.audio); assertEquals("mp4a.40.42", choice.audio.codecs); assertNull(choice.unsupported);
    }
    @Test public void unknownMetadataStillMustPassMeasuredCeiling() throws Exception {
        DownloadRequest r = new DownloadRequest("1", "", "fixture.mp4", "", Collections.singletonList(direct("unknown", 0, "")),
                new DownloadRequest.Options(720, false, false), null);
        assertEquals(1, RenditionPicker.plan(r).choices.size());
        DownloadEngine.Metadata measured = new DownloadEngine.Metadata(1080, 1920, 0, 1_000_000, "video/avc", "", 0);
        assertEquals("CEILING", assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.verifySelection(measured, r.options)).status);
        DownloadEngine.verifySelection(measured, new DownloadRequest.Options(0, false, false));
    }
    @Test public void unsupportedSoundNeverBecomesSilentVideo() {
        RenditionPicker.Plan p = RenditionPicker.plan(request(manifest(rep("video", 1080, 1920, "avc1"), sound("opus")), false));
        assertTrue(p.choices.get(0).expectAudio);
        assertNotNull(p.choices.get(0).unsupported);
    }
    @Test public void silentManifestNeedsNoAudio() {
        RenditionPicker.Choice choice = RenditionPicker.plan(request(manifest(rep("silent", 1080, 1920, "avc1"), ""), false)).choices.get(0);
        assertFalse(choice.expectAudio); assertNull(choice.audio); assertNull(choice.unsupported);
    }
    @Test public void inheritedMetadataAndXmlEntitiesAreSupported() {
        String xml = manifest(rep("video", 1080, 1920, "avc1").replace(".mp4</BaseURL>", ".mp4?a=1&amp;b=2</BaseURL>"), "");
        DashManifest p = DashManifest.parse(xml);
        assertEquals(DashManifest.State.SUPPORTED, p.state);
        assertTrue(p.tracks.get(0).url.endsWith("?a=1&b=2"));
        assertEquals(29.97002997, p.tracks.get(0).frameRate, 0.00001);
    }
    @Test public void encryptionSegmentsMultiplePeriodsAndExternalEntitiesRefused() {
        String xml = manifest(rep("video", 1080, 1920, "avc1"), "");
        assertEquals(DashManifest.State.ENCRYPTED, DashManifest.parse(xml.replace("<AdaptationSet>", "<AdaptationSet><ContentProtection/>" )).state);
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml.replace("<AdaptationSet>", "<AdaptationSet><SegmentTemplate/>" )).state);
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml.replace("</MPD>", "<Period/></MPD>" )).state);
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse("<!DOCTYPE MPD SYSTEM 'file:///private'>" + xml).state);
    }
    @Test public void relativeForeignAndMultipleBaseUrlsAreRefused() {
        String xml = manifest(rep("video", 1080, 1920, "avc1"), "");
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml.replace(HOST, "https://example.com/")).state);
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml.replace(HOST, "")).state);
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml.replace("</BaseURL>", "</BaseURL><BaseURL>" + HOST + "other.mp4</BaseURL>")).state);
    }
    @Test public void boundedParserRefusesTooManyTracksAndHugeInput() {
        assertEquals(DashManifest.State.TOO_LARGE, DashManifest.parse("x".repeat(DownloadRequest.MAX_MANIFEST_CHARS + 1)).state);
        assertEquals(DashManifest.State.TOO_LARGE, DashManifest.parse(manifest(rep("v", 720, 1280, "avc1").repeat(65), "")).state);
        assertEquals(DashManifest.State.INVALID, DashManifest.parse("<MPD><broken></MPD>").state);
    }
    @Test public void alternativeAudioGroupsAndSeparateInitializationAreExplicitlyUnsupported() {
        String xml = manifest(rep("v", 1080, 1920, "avc1"), sound("mp4a.40.2") + sound("mp4a.40.5"));
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml).state);
        xml = manifest(rep("v", 1080, 1920, "avc1").replace("</Representation>",
                "<SegmentBase><Initialization sourceURL='init.mp4'/></SegmentBase></Representation>"), "");
        assertEquals(DashManifest.State.UNSUPPORTED, DashManifest.parse(xml).state);
    }
    @Test public void truncatedNetworkResponseIsNotMisreportedAsLowStorage() {
        DownloadEngine.Failure truncated = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.require(Downloader.Result.fail(Downloader.Status.WRITE_ERROR, "the body was incomplete")));
        assertEquals("WRITE_ERROR", truncated.status);
        DownloadEngine.Failure disk = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.require(Downloader.Result.fail(Downloader.Status.STORAGE_ERROR, "the cache could not hold the file")));
        assertEquals("STORAGE", disk.status);
    }
    @Test public void singleFileFetchRequiresAnExplicitMediaCategory() throws Exception {
        assertEquals(Downloader.Kind.IMAGE, DownloadEngine.fetchKind("image/jpeg"));
        assertEquals(Downloader.Kind.AUDIO, DownloadEngine.fetchKind("audio/mp4"));
        assertEquals(Downloader.Kind.VIDEO, DownloadEngine.fetchKind("video/mp4"));
        assertEquals("REFUSED", assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.fetchKind("text/html")).status);
        assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.fetchKind(null));
    }
    @Test public void verificationPreservesRelativeAudioTimingAndAllSamples() throws Exception {
        DownloadEngine.Metadata video = new DownloadEngine.Metadata(320, 240, 90, 1_000_000, "video/avc", "", 0,
                0, 0, 966666, 0, 0, 30, 0);
        DownloadEngine.Metadata audio = new DownloadEngine.Metadata(0, 0, 0, 0, "", "audio/mp4a-latm", 2,
                1_000_000, 0, 0, -21000, 980000, 0, 48);
        DownloadEngine.Metadata good = new DownloadEngine.Metadata(320, 240, 90, 1_000_000, "video/avc", "audio/mp4a-latm", 2,
                1_000_000, 21000, 987666, 0, 1001000, 30, 48);
        DownloadEngine.verifyJoined(video, audio, good);
        DownloadEngine.Metadata shiftedAudio = new DownloadEngine.Metadata(320, 240, 90, 1_000_000, "video/avc", "audio/mp4a-latm", 2,
                1_000_000, 21000, 987666, 250000, 1251000, 30, 48);
        assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.verifyJoined(video, audio, shiftedAudio));
        DownloadEngine.Metadata droppedFrame = new DownloadEngine.Metadata(320, 240, 90, 1_000_000, "video/avc", "audio/mp4a-latm", 2,
                1_000_000, 21000, 987666, 0, 1001000, 29, 48);
        assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.verifyJoined(video, audio, droppedFrame));
    }
    @Test public void snapshotAndJsonRoundTripCannotFollowMutableModels() {
        List<DownloadRequest.Rendition> input = new ArrayList<>(); input.add(direct("first", 720, "avc1"));
        DownloadRequest r = new DownloadRequest("1", "account", "story.mp4", "", input, null, null);
        input.clear(); input.add(direct("next", 1080, "av01"));
        assertEquals(720, r.direct.get(0).width);
        assertThrows(UnsupportedOperationException.class, () -> r.direct.clear());
        DownloadRequest restored = DownloadRequest.fromJson(r.toJson());
        assertEquals(720, restored.direct.get(0).width);
        assertFalse(restored.options.allowFallback);
        assertFalse(restored.options.compatibility);
        assertThrows(IllegalArgumentException.class, () -> DownloadRequest.fromJson("x".repeat(DownloadRequest.MAX_JSON_CHARS + 1)));
    }
    @Test public void failedBestNeverRunsLowerWhenFallbackDisabled() {
        DownloadRequest request = request("", false, direct("best", 1080, "avc1"), direct("lower", 720, "avc1"));
        AtomicInteger attempts = new AtomicInteger();
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.attemptPlan(request,
                RenditionPicker.plan(request), (choice, fallback) -> { attempts.incrementAndGet(); throw new DownloadEngine.Failure("EXPIRED", "Expired URL", false); }));
        assertEquals(1, attempts.get()); assertTrue(failure.canOfferFallback);
    }
    @Test public void explicitFallbackRunsLowerAndMarksIt() throws Exception {
        DownloadRequest request = request("", true, direct("best", 1080, "avc1"), direct("lower", 720, "avc1"));
        int side = DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> {
            if (choice.video.width == 1080) throw new DownloadEngine.Failure("JOIN", "Mux failed", false);
            assertTrue(fallback); return choice.video.width;
        });
        assertEquals(720, side);
    }
    @Test public void cancellationAndLowStorageNeverTriggerFallback() {
        DownloadRequest request = request("", true, direct("best", 1080, "avc1"), direct("lower", 720, "avc1"));
        for (String status : Arrays.asList("CANCELLED", "STORAGE")) {
            AtomicInteger attempts = new AtomicInteger();
            assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> {
                attempts.incrementAndGet(); throw new DownloadEngine.Failure(status, "Stopped", false);
            }));
            assertEquals(1, attempts.get());
        }
        assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.capacity(1024, true));
    }
    @Test public void failedManifestIsDistinctFromAbsentAndRequiresExplicitFallback() {
        DownloadRequest r = request("<MPD><Period/><Period/></MPD>", false, direct("direct", 720, ""));
        AtomicInteger attempts = new AtomicInteger();
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class, () -> DownloadEngine.attemptPlan(r,
                RenditionPicker.plan(r), (c, fallback) -> attempts.incrementAndGet()));
        assertEquals(0, attempts.get()); assertTrue(failure.canOfferFallback);
        assertEquals("MANIFEST", failure.status);
    }
}
