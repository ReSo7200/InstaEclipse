package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadRequestValidatorTest {

    @Test
    public void acceptsInstagramCdnHttps() {
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://scontent-cph2-1.cdninstagram.com/v/t51/x.jpg?a=1"));
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://video.fcph1-1.fna.fbcdn.net/o1/v/t2/x.mp4"));
        assertTrue(DownloadRequestValidator.isAllowedMediaUrl("https://lookaside.fbsbx.com/ig_messaging_cdn/?asset_id=1"));
    }

    @Test
    public void rejectsOtherHostsAndSchemes() {
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("http://scontent.cdninstagram.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://evil.com/cdninstagram.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://cdninstagram.com.evil.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("https://user@evil.cdninstagram.com/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl("file:///sdcard/x.jpg"));
        assertFalse(DownloadRequestValidator.isAllowedMediaUrl(null));
    }

    @Test
    public void fileNamesStayInsideTheFolder() {
        String name = DownloadRequestValidator.sanitizeFileName("../../etc/passwd", "x");
        assertFalse(name.contains("/") || name.startsWith("."));
        assertEquals("fallback", DownloadRequestValidator.sanitizeFileName("...", "fallback"));
        assertEquals("user_post_1of2.jpg", DownloadRequestValidator.sanitizeFileName("user_post_1of2.jpg", "x"));
    }

    @Test
    public void subdirSegmentsAreCleaned() {
        assertEquals("Chats/a T", DownloadRequestValidator.sanitizeSubdir("Chats/a T"));
        assertEquals("Chats/x", DownloadRequestValidator.sanitizeSubdir("../Chats/./x"));
        assertNull(DownloadRequestValidator.sanitizeSubdir("../.."));
    }
}
