package ps.reso.instaeclipse.utils.ipc;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LegacyBrokerPayloadTest {
    private static final String CDN = "https://scontent.cdninstagram.com/video.mp4";
    private Bundle video() {
        Bundle b = new Bundle(); b.putString("url", CDN); b.putString("filename", "video.mp4");
        b.putString("mimeType", "video/mp4"); b.putString("username", "synthetic"); return b;
    }
    private void rejects(Bundle b) {
        try { LegacyBrokerPayload.validate(b); fail("Request must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void preservesCapturedFallbackAndAudio() {
        Bundle b=video(); b.putString("audioUrl", CDN); b.putString("fallbackUrl", CDN);
        Bundle copy=LegacyBrokerPayload.validate(b); b.putString("fallbackUrl", "changed");
        assertEquals(CDN, copy.getString("fallbackUrl")); assertEquals(CDN, copy.getString("audioUrl"));
    }
    @Test public void rejectsUnknownFieldsAndWrongTypes() {
        Bundle b=video(); b.putString("saveUri", "content://unapproved/folder"); rejects(b);
        b=video(); b.putString("extractAudio", "true"); rejects(b);
        b=video(); b.putInt("trimStartMs", 3); rejects(b);
    }
    @Test public void rejectsUntrustedFallbackAndOversizedTrim() {
        Bundle b=video(); b.putString("fallbackUrl", "https://example.com/video.mp4"); rejects(b);
        b=video(); b.putLong("trimDurationMs", Long.MAX_VALUE); rejects(b);
    }
    @Test public void audioClipIsDetached() {
        Bundle b=video(); b.putString("mimeType", "audio/mp4"); b.putBoolean("extractAudio",true);
        b.putLong("trimStartMs",123); b.putLong("trimDurationMs",456);
        Bundle out=LegacyBrokerPayload.validate(b); assertTrue(out.getBoolean("extractAudio"));
        assertEquals(123,out.getLong("trimStartMs")); assertEquals(456,out.getLong("trimDurationMs"));
    }
    @Test public void exportUsesCompanionFolderAndCopiesArrays() {
        Bundle b=new Bundle(); b.putString("chatExportSubdir","chats/synthetic");
        b.putString("htmlFilename","export.html"); b.putString("htmlB64","PGh0bWw+PC9odG1sPg==");
        b.putString("saveUri","content://not-used/folder");
        String[] urls={CDN}; b.putStringArray("mediaUrls",urls);
        b.putStringArray("mediaNames",new String[]{"video.mp4"});
        b.putStringArray("mediaMimes",new String[]{"video/mp4"});
        Bundle out=LegacyBrokerPayload.validate(b); urls[0]="changed";
        assertFalse(out.containsKey("saveUri")); assertEquals(CDN,out.getStringArray("mediaUrls")[0]);
        b.putString("chatExportSubdir","chats/.."); rejects(b);
    }
}
