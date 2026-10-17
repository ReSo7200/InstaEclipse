package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
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

    @Test
    public void keepsGifAsGifEvenWhenRequestedAsJpeg() throws Exception {
        File f = File.createTempFile("ie_gif", ".bin");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[16]);
        }
        MediaTypeDetector.Result r = MediaTypeDetector.resolve(f, "image/gif", "image/jpeg", "comment_gif_1.jpg");
        assertEquals("image/gif", r.mimeType);
        assertEquals("comment_gif_1.gif", r.filename);
        f.delete();
    }
}
