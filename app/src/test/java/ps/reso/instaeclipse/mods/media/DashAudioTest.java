package ps.reso.instaeclipse.mods.media;

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
}
