package ps.reso.instaeclipse.utils.download;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class DownloadDiagnosticsTest {
    @Test public void audioMismatchIncludesOnlyFixedCodecAndNumericMetadata() {
        DownloadEngine.Metadata video = audioEvidence(0, 0, 0, 0, "", 0);
        DownloadEngine.Metadata source = audioEvidence(647, 15_000_000, -23_220, 14_980_000, "audio/mp4a-latm", 42);
        DownloadEngine.Metadata output = audioEvidence(646, 14_990_000, 0, 14_980_000, "audio/mp4a-latm", 42);
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.verifyJoined(video, source, output));
        assertEquals("phase=VERIFY_OUTPUT cause=AUDIO_SAMPLE_COUNT srcSamples=647 outSamples=646"
                + " srcDurationUs=15000000 outDurationUs=14990000 srcFirstUs=-23220 outFirstUs=0"
                + " srcLastUs=14980000 outLastUs=14980000 srcCodec=AAC outCodec=AAC srcProfile=42 outProfile=42",
                failure.diagnostic);
        assertEquals("VERIFY", failure.status);
        assertTrue(failure.getMessage().contains(failure.diagnostic));
        assertNull(failure.getCause());
    }

    @Test public void unknownCodecTextNeverEntersAudioMismatchAndWrapperPreservesEvidence() {
        DownloadEngine.Metadata video = audioEvidence(0, 0, 0, 0, "", 0);
        DownloadEngine.Metadata source = audioEvidence(1, 15_000_000, 0, 0,
                "https://example.invalid/private?token=synthetic-secret", Integer.MAX_VALUE);
        DownloadEngine.Metadata output = audioEvidence(1, 12_000_000, 0, 0, "audio/mp4a-latm", 2);
        DownloadRequest request = request(false);
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> {
                    DownloadEngine.verifyJoined(video, source, output); return true;
                }));
        assertTrue(failure.diagnostic.startsWith("phase=VERIFY_OUTPUT cause=AUDIO_DURATION "));
        assertTrue(failure.diagnostic.contains("srcCodec=OTHER outCodec=AAC"));
        assertTrue(failure.diagnostic.length() < 550);
        assertFalse(failure.getMessage().contains("synthetic"));
        assertFalse(failure.diagnostic.contains("https"));
        assertTrue(failure.canOfferFallback);
        DownloadEngine.Failure missing = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.verifyJoined(video, source, video));
        assertTrue(missing.diagnostic.startsWith("phase=VERIFY_OUTPUT cause=AUDIO_MISSING "));
        assertTrue(missing.diagnostic.contains("outCodec=ABSENT"));
    }

    private static DownloadEngine.Metadata audioEvidence(int samples, long duration, long first, long last,
            String codec, int profile) {
        return new DownloadEngine.Metadata(320, 240, 0, 15_000_000, "video/avc", codec, profile,
                duration, 0, 14_000_000, first, last, 15, samples);
    }

    @Test public void coverageEvidenceUsesOnlyTypedNumbersAndFixedTags() {
        DownloadDiagnostics.CoverageFailure error = new DownloadDiagnostics.CoverageFailure(
                true, 3_000_000, -33_333, 1_966_667, 60, 12345, 3_000_000, null);
        DownloadEngine.Failure failure = DownloadEngine.diagnosedFailure(DownloadDiagnostics.Phase.INSPECT_VIDEO, error);
        assertEquals("phase=INSPECT_VIDEO cause=SAMPLE_COVERAGE track=VIDEO durationUs=3000000 minPtsUs=-33333"
                + " maxPtsUs=1966667 samples=60 fileBytes=12345 fragmentDurationUs=3000000 fragmentCause=OK", failure.diagnostic);
        assertNull(error.getCause());
        assertNull(failure.getCause());
    }

    @Test public void coverageParserErrorsNeverExposePrivateTextAndNumbersStayBounded() {
        DownloadDiagnostics.CoverageFailure error = new DownloadDiagnostics.CoverageFailure(
                false, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, -1,
                new IOException("https://example.invalid/private?token=synthetic-secret"));
        String diagnostic = DownloadDiagnostics.describe(DownloadDiagnostics.Phase.INSPECT_AUDIO, error);
        assertTrue(diagnostic.endsWith("fragmentDurationUs=-1 fragmentCause=PLATFORM_IO"));
        assertTrue(diagnostic.contains("track=AUDIO"));
        assertTrue(diagnostic.length() < 400);
        assertFalse(diagnostic.contains("synthetic"));
        assertFalse(diagnostic.contains("https"));
        assertNull(error.getCause());
    }

    @Test public void emptyCoverageHasNoSentinelTimestampsAndCountMismatchIsExplicit() {
        DownloadDiagnostics.CoverageFailure error = new DownloadDiagnostics.CoverageFailure(
                true, 3_000_000, Long.MAX_VALUE, Long.MIN_VALUE, 0, 12345, -1,
                new IOException("Fragment and extractor sample counts differ"));
        assertEquals("phase=INSPECT_VIDEO cause=SAMPLE_COVERAGE track=VIDEO durationUs=3000000 minPtsUs=0"
                + " maxPtsUs=0 samples=0 fileBytes=12345 fragmentDurationUs=-1 fragmentCause=FRAGMENT_SAMPLE_COUNT",
                DownloadDiagnostics.describe(DownloadDiagnostics.Phase.INSPECT_VIDEO, error));
    }

    @Test public void unknownMessagesNeverEnterFailureOrDiagnostic() {
        String[] privateMessages = {
            "https://scontent.cdninstagram.com/private.mp4?token=synthetic-secret&id=synthetic-id",
            "C:\\private\\synthetic-owner\\media.mp4",
            "Incomplete media samples https://example.invalid/?token=synthetic-secret",
            "the video file has a header box of synthetic-secret bytes",
            new String(new char[300]).replace('\0', 'x'), null
        };
        for (String message : privateMessages) {
            DownloadEngine.Failure failure = DownloadEngine.diagnosedFailure(
                    DownloadDiagnostics.Phase.INSPECT_VIDEO, new IOException(message));
            assertEquals("phase=INSPECT_VIDEO cause=PLATFORM_IO", failure.diagnostic);
            assertEquals("Selected tracks could not be joined or verified [" + failure.diagnostic + "]", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test public void knownInspectionErrorsHaveDistinctFixedCategories() {
        assertEquals("DIMENSION_MISMATCH", DownloadDiagnostics.cause(new IOException("Actual dimensions differ from selected rendition")));
        assertEquals("CODEC_MISMATCH", DownloadDiagnostics.cause(new IOException("Actual codec differs from selected rendition")));
        assertEquals("SAMPLE_COVERAGE", DownloadDiagnostics.cause(new IOException("Incomplete media samples")));
        assertEquals("FRAGMENT_SAMPLE_COUNT", DownloadDiagnostics.cause(new IOException("Fragment and extractor sample counts differ")));
        assertEquals("PLATFORM_RUNTIME", DownloadDiagnostics.cause(new IllegalStateException("Incomplete media samples")));
    }

    @Test public void ownedMp4TemplatesClassifyOnlyExactMessages() {
        for (String kind : new String[] {"video", "audio"}) {
            assertEquals("MP4_NONFRAGMENTED_SAMPLES", DownloadDiagnostics.cause(new IOException("the " + kind + " track keeps samples outside its fragments")));
            assertEquals("MP4_SAMPLE_BOUNDS", DownloadDiagnostics.cause(new IOException("a fragment's samples run past the end of the " + kind + " file")));
            assertEquals("MP4_BOX_BOUNDS", DownloadDiagnostics.cause(new IOException("a box of the " + kind + " file is cut short")));
            assertEquals("MP4_HEADER_LIMIT", DownloadDiagnostics.cause(new IOException("the " + kind + " file has a header box of 999999999 bytes")));
        }
        assertEquals("PLATFORM_IO", DownloadDiagnostics.cause(new IOException("the private-owner file has no moov")));
        assertEquals("PLATFORM_IO", DownloadDiagnostics.cause(new IOException("the video file has no moov; path=/private")));
    }

    @Test public void eachPhaseIsAConstantAndCauseChainsAreNeverExposed() {
        for (DownloadDiagnostics.Phase phase : DownloadDiagnostics.Phase.values()) {
            IOException error = new IOException("Required media track missing", new IOException("https://example.invalid/?secret=synthetic"));
            assertEquals("phase=" + phase.name() + " cause=TRACK_MISSING", DownloadDiagnostics.describe(phase, error));
        }
    }

    @Test public void strictFallbackWrapperPreservesSafeDiagnostic() {
        DownloadRequest request = request(false);
        AtomicInteger attempts = new AtomicInteger();
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> {
                    attempts.incrementAndGet();
                    throw DownloadEngine.diagnosedFailure(DownloadDiagnostics.Phase.JOIN_PORTABLE,
                            new IOException("the video track keeps samples outside its fragments"));
                }));
        assertEquals(1, attempts.get());
        assertTrue(failure.canOfferFallback);
        assertEquals("phase=JOIN_PORTABLE cause=MP4_NONFRAGMENTED_SAMPLES", failure.diagnostic);
        assertTrue(failure.getMessage().contains(failure.diagnostic));
    }

    @Test public void exhaustedFallbackPreservesFirstFailureAndOldConstructorIsEmpty() {
        DownloadRequest request = request(true);
        AtomicInteger attempts = new AtomicInteger();
        DownloadEngine.Failure failure = assertThrows(DownloadEngine.Failure.class,
                () -> DownloadEngine.attemptPlan(request, RenditionPicker.plan(request), (choice, fallback) -> {
                    if (attempts.incrementAndGet() == 1)
                        throw DownloadEngine.diagnosedFailure(DownloadDiagnostics.Phase.INSPECT_AUDIO,
                                new IOException("Incomplete media samples"));
                    throw new DownloadEngine.Failure("JOIN", "A later attempt failed", false);
                }));
        assertEquals(2, attempts.get());
        assertFalse(failure.canOfferFallback);
        assertEquals("phase=INSPECT_AUDIO cause=SAMPLE_COVERAGE", failure.diagnostic);
        assertTrue(failure.getMessage().contains(failure.diagnostic));
        assertEquals("", new DownloadEngine.Failure("JOIN", "Existing failure", false).diagnostic);
    }

    private static DownloadRequest request(boolean fallback) {
        return new DownloadRequest("synthetic-item", "synthetic-owner", "fixture.mp4", null,
                Arrays.asList(new DownloadRequest.Rendition("https://scontent.cdninstagram.com/high.mp4", 1080, 1920, 0, "avc1.4d401f", 0),
                        new DownloadRequest.Rendition("https://scontent.cdninstagram.com/low.mp4", 720, 1280, 0, "avc1.4d401f", 0)),
                new DownloadRequest.Options(0, false, fallback), DownloadRequest.AudioExpectation.UNKNOWN);
    }
}
