package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class MediaTypeDetectorTest {

    @Test
    public void detectsMp4ByFtypEvenWhenUrlAndNameLookLikeImage() {
        byte[] header = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0
        };

        assertEquals(MediaTypeDetector.Kind.VIDEO,
                MediaTypeDetector.sniff(header, header.length));
        assertEquals("story_123.mp4", MediaTypeDetector.withCorrectExtension(
                "story_123.jpg", MediaTypeDetector.Kind.VIDEO));
    }

    @Test
    public void detectsJpegAndCorrectsWrongVideoExtension() {
        byte[] header = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0};

        assertEquals(MediaTypeDetector.Kind.IMAGE,
                MediaTypeDetector.sniff(header, header.length));
        assertEquals("story_123.jpg", MediaTypeDetector.withCorrectExtension(
                "story_123.mp4", MediaTypeDetector.Kind.IMAGE));
    }

    @Test
    public void doesNotTreatHeicAsVideo() {
        byte[] header = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0
        };

        assertEquals(MediaTypeDetector.Kind.IMAGE,
                MediaTypeDetector.sniff(header, header.length));
    }

    @Test
    public void usesContentTypeWhenSignatureIsUnknown() {
        assertEquals(MediaTypeDetector.Kind.VIDEO,
                MediaTypeDetector.fromContentType("video/mp4; charset=binary"));
        assertEquals(MediaTypeDetector.Kind.IMAGE,
                MediaTypeDetector.fromContentType("image/webp"));
        assertEquals(MediaTypeDetector.Kind.UNKNOWN,
                MediaTypeDetector.sniff("unknown".getBytes(StandardCharsets.US_ASCII), 7));
    }
}
