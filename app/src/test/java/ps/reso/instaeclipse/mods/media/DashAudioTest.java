package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DashAudioTest {

    private static final String MPD =
            "<?xml version=\"1.0\"?><MPD><Period>"
                    + "<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\">"
                    + "<Representation id=\"v1\" bandwidth=\"900000\"><BaseURL>https://cdn/v.mp4?a=1&amp;b=2</BaseURL></Representation>"
                    + "</AdaptationSet>"
                    + "<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\">"
                    + "<Representation id=\"a1\" bandwidth=\"64000\"><BaseURL>https://cdn/low.mp4?x=1&amp;y=2</BaseURL></Representation>"
                    + "<Representation id=\"a2\" bandwidth=\"128000\"><BaseURL>https://cdn/high.mp4?x=1&amp;y=2</BaseURL></Representation>"
                    + "</AdaptationSet></Period></MPD>";

    @Test
    public void picksHighestBandwidthAudioAndUnescapes() {
        assertEquals("https://cdn/high.mp4?x=1&y=2", DashAudio.audioUrl(MPD));
    }

    @Test
    public void detectsAudioFromRepresentationMimeType() {
        String mpd = "<MPD><AdaptationSet>"
                + "<Representation mimeType=\"audio/mp4\" bandwidth=\"1\"><BaseURL> https://cdn/a.mp4 </BaseURL></Representation>"
                + "</AdaptationSet></MPD>";
        assertEquals("https://cdn/a.mp4", DashAudio.audioUrl(mpd));
    }

    @Test
    public void returnsNullWithoutAudioTrack() {
        String videoOnly = "<MPD><AdaptationSet contentType=\"video\"><Representation bandwidth=\"1\">"
                + "<BaseURL>https://cdn/v.mp4</BaseURL></Representation></AdaptationSet></MPD>";
        assertNull(DashAudio.audioUrl(videoOnly));
        assertNull(DashAudio.audioUrl(""));
        assertNull(DashAudio.audioUrl(null));
    }

    private static final String VIDEO_MPD = "<MPD><Period>"
            + "<AdaptationSet contentType=\"video\">"
            + "<Representation height=\"1920\" bandwidth=\"1250000\" codecs=\"av01.0.09M.08\"><BaseURL>https://cdn/av1080.mp4</BaseURL></Representation>"
            + "<Representation height=\"1280\" bandwidth=\"900000\" codecs=\"av01.0.08M.08\"><BaseURL>https://cdn/av720.mp4</BaseURL></Representation>"
            + "</AdaptationSet>"
            + "<AdaptationSet contentType=\"video\">"
            + "<Representation height=\"1280\" bandwidth=\"700000\" codecs=\"avc1.64001F\"><BaseURL>https://cdn/h720a.mp4</BaseURL></Representation>"
            + "<Representation height=\"1280\" bandwidth=\"800000\" codecs=\"avc1.64001F\"><BaseURL>https://cdn/h720b.mp4?a=1&amp;b=2</BaseURL></Representation>"
            + "</AdaptationSet>"
            + "<AdaptationSet contentType=\"audio\">"
            + "<Representation height=\"9999\" bandwidth=\"64000\"><BaseURL>https://cdn/a.mp4</BaseURL></Representation>"
            + "</AdaptationSet></Period></MPD>";

    @Test
    public void bestVideoPicksTallestStream() {
        assertArrayEquals(new String[]{"https://cdn/av1080.mp4", "1920"}, DashAudio.bestVideo(VIDEO_MPD, false));
    }

    @Test
    public void bestVideoCanSkipAv1() {
        assertArrayEquals(new String[]{"https://cdn/h720b.mp4?a=1&b=2", "1280"}, DashAudio.bestVideo(VIDEO_MPD, true));
        assertNull(DashAudio.bestVideo(null, true));
    }
}
