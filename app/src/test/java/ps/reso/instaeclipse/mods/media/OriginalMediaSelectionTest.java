package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import ps.reso.instaeclipse.utils.download.DownloadRequest;

public class OriginalMediaSelectionTest {
    private static final String DIRECT = "https://cdninstagram.com/direct.mp4";
    private static final String DASH = "https://cdninstagram.com/video.mp4";
    private static final String AUDIO = "https://cdninstagram.com/audio.mp4";
    private static final String MPD = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
            + "<Representation height=\"1080\" bandwidth=\"3000000\" codecs=\"avc1\"><BaseURL>"
            + DASH + "</BaseURL></Representation></AdaptationSet><AdaptationSet mimeType=\"audio/mp4\">"
            + "<Representation bandwidth=\"128000\"><BaseURL>" + AUDIO
            + "</BaseURL></Representation></AdaptationSet></Period></MPD>";
    private static List<DownloadRequest.Rendition> direct() {
        return List.of(new DownloadRequest.Rendition(DIRECT, 1280, 720, 30, "avc1", 0));
    }

    @Test public void upstream081UpgradeRetainsAudioAndProgressiveFallback() {
        OriginalMediaSelection.Plan plan = new OriginalMediaSelection.Plan(DASH, AUDIO, DIRECT);
        assertSame(plan, OriginalMediaSelection.guardedPlan(DIRECT, plan, direct(), MPD));
        assertEquals(DASH, plan.url);
        assertEquals(AUDIO, plan.audioUrl);
        assertEquals(DIRECT, plan.fallbackUrl);
    }

    @Test public void unrelatedVideoAudioOrFallbackIsRejected() {
        String unrelated = "https://cdninstagram.com/unrelated.mp4";
        assertNull(OriginalMediaSelection.guardedPlan(DIRECT,
                new OriginalMediaSelection.Plan(unrelated, AUDIO, DIRECT), direct(), MPD));
        assertNull(OriginalMediaSelection.guardedPlan(DIRECT,
                new OriginalMediaSelection.Plan(DASH, unrelated, DIRECT), direct(), MPD));
        assertNull(OriginalMediaSelection.guardedPlan(DIRECT,
                new OriginalMediaSelection.Plan(DASH, AUDIO, unrelated), direct(), MPD));
        assertNull(OriginalMediaSelection.guardedPlan(unrelated,
                new OriginalMediaSelection.Plan(DASH, AUDIO, unrelated), direct(), MPD));
    }

    @Test public void absentUpgradePreservesUpstreamDirectRatherThanChoosingDash() {
        OriginalMediaSelection.Plan plan = OriginalMediaSelection.guardedPlan(DIRECT, null, direct(), MPD);
        assertEquals(DIRECT, plan.url);
        assertNull(plan.audioUrl);
        assertNull(plan.fallbackUrl);
    }

    @Test public void malformedUpstreamMetadataOnlyDisablesOriginalUpgrade() {
        assertNull(OriginalMediaSelection.guardedPlan(DIRECT,
                new OriginalMediaSelection.Plan(DASH, AUDIO, DIRECT), direct(),
                MPD.replace("height=\"1080\"", "height=\"999999999999999999999999999\"")));
    }

    @Test public void separateMusicClipIsBoundedAndDetached() {
        OriginalMediaSelection.Audio clip = new OriginalMediaSelection.Audio(AUDIO, true, 4000, 30000, true);
        assertTrue(clip.postWide);
        assertTrue(clip.extract);
        assertEquals(4000, clip.startMs);
        assertEquals(30000, clip.durationMs);
        try { new OriginalMediaSelection.Audio(AUDIO, true, -1, 30000, true); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void guardKeepsUpstreamWinnerRatherThanChoosingCustomQuality() {
        String lower = "https://cdninstagram.com/m86/low.mp4", higher = "https://cdninstagram.com/high.mp4";
        List<DownloadRequest.Rendition> selected = List.of(
                new DownloadRequest.Rendition(higher, 3840, 2160, 60, "av01", 0),
                new DownloadRequest.Rendition(lower, 1280, 720, 30, "avc1", 0));
        assertEquals(lower, OriginalMediaSelection.video(lower, selected));
        assertNull(OriginalMediaSelection.video(null, selected));
        assertNull(OriginalMediaSelection.video(lower + "?changed=1", selected));
    }

    @Test public void imageGuardRequiresExactBoundedMembership() {
        String image = "https://cdninstagram.com/photo.jpg";
        assertEquals(image, OriginalMediaSelection.image(image, List.of(image)));
        assertNull(OriginalMediaSelection.image(image, List.of(image + "?changed=1")));
        assertNull(OriginalMediaSelection.image("", List.of("")));
        String tooLong = "x".repeat(4097);
        assertNull(OriginalMediaSelection.image(tooLong, List.of(tooLong)));
    }
}
