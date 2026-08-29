package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StoryDownloadChoicePolicyTest {

    @Test
    public void asksWhenPhotoAndMusicVideoAreBothAvailable() {
        assertEquals(StoryDownloadChoicePolicy.Decision.ASK,
                StoryDownloadChoicePolicy.decide(true, true, true));
    }

    @Test
    public void downloadsPlainPhotoDirectly() {
        assertEquals(StoryDownloadChoicePolicy.Decision.DOWNLOAD_PHOTO,
                StoryDownloadChoicePolicy.decide(true, false, false));
    }

    @Test
    public void downloadsVideoDirectlyWhenNoPhotoVariantExists() {
        assertEquals(StoryDownloadChoicePolicy.Decision.DOWNLOAD_VIDEO,
                StoryDownloadChoicePolicy.decide(false, true, true));
    }

    @Test
    public void neverSilentlyTreatsVideoCoverAsVideo() {
        assertEquals(StoryDownloadChoicePolicy.Decision.ASK,
                StoryDownloadChoicePolicy.decide(true, false, true));
    }

    @Test
    public void reportsMissingMedia() {
        assertEquals(StoryDownloadChoicePolicy.Decision.NOT_FOUND,
                StoryDownloadChoicePolicy.decide(false, false, false));
    }
}
