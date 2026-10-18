package ps.reso.instaeclipse.mods.media;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DownloadPublisherPathTest {
    @Test public void explicitCompanionSettingsKeepExistingGalleryFolderSemantics() {
        assertEquals("Download/InstaEclipse", path("", false));
        assertEquals("Pictures/Custom", path("/storage/emulated/0/Pictures/Custom", false));
        assertEquals("Downloads/Custom", path("/storage/emulated/0/Downloads/Custom", false));
        assertEquals("Download/Custom/owner", path("/storage/emulated/0/Custom", true));
        assertEquals("Download/InstaEclipse/owner", path("/outside/Custom", true));
        assertEquals("Download/InstaEclipse", path("content://synthetic/folder", false));
    }
    private static String path(String custom, boolean ownerFolder) {
        return DownloadPublisher.mediaStorePath(custom, "/storage/emulated/0", "owner", ownerFolder);
    }
}
