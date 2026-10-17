package ps.reso.instaeclipse.mods.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TrackingParamStripperTest {

    @Test
    public void keepsCarouselIndexWhileDroppingTracking() {
        // #252: sharing slide 2 of a carousel must keep img_index.
        assertEquals("https://www.instagram.com/p/ABC123/?img_index=2",
                TrackingParamStripper.strip("https://www.instagram.com/p/ABC123/?img_index=2&stkn=xyz"));
    }

    @Test
    public void dropsShareLinkId() {
        assertEquals("https://www.instagram.com/p/DdWEH9hFDtG/",
                TrackingParamStripper.strip("https://www.instagram.com/p/DdWEH9hFDtG/?psln=cHNzeXhlMDJsYm5k"));
    }

    @Test
    public void removesWholeQueryWhenOnlyTrackingParams() {
        assertEquals("https://www.instagram.com/reel/XYZ/",
                TrackingParamStripper.strip("https://www.instagram.com/reel/XYZ/?igsh=abc&utm_source=ig_web_copy_link"));
    }

    @Test
    public void keepsOrderOfRemainingParamsAndFragment() {
        assertEquals("https://instagram.com/p/A/?a=1&b=2#c",
                TrackingParamStripper.strip("https://instagram.com/p/A/?igshid=1&a=1&utm_medium=x&b=2#c"));
    }

    @Test
    public void cleansLinkInsideShareText() {
        assertEquals("Look at this https://www.instagram.com/p/A/?img_index=3 wow",
                TrackingParamStripper.strip("Look at this https://www.instagram.com/p/A/?igsh=q&img_index=3 wow"));
    }

    @Test
    public void returnsNullWhenNothingToStrip() {
        assertNull(TrackingParamStripper.strip("https://www.instagram.com/p/A/?img_index=2"));
        assertNull(TrackingParamStripper.strip("https://www.instagram.com/p/A/"));
        assertNull(TrackingParamStripper.strip("https://example.com/?utm_source=x"));
        assertNull(TrackingParamStripper.strip(null));
    }

    @Test
    public void tracksParamKeysCaseInsensitively() {
        assertEquals("https://www.instagram.com/p/A/",
                TrackingParamStripper.strip("https://www.instagram.com/p/A/?IGSH=abc"));
    }
}
