package ps.reso.instaeclipse.mods.media;

/** Pure decision policy for Story download variants. */
final class StoryDownloadChoicePolicy {

    enum Decision {
        NOT_FOUND,
        DOWNLOAD_PHOTO,
        DOWNLOAD_VIDEO,
        ASK
    }

    private StoryDownloadChoicePolicy() {}

    static Decision decide(boolean hasImage, boolean hasVideo, boolean modelSaysVideo) {
        if (hasImage && hasVideo) return Decision.ASK;
        if (hasVideo) return Decision.DOWNLOAD_VIDEO;

        // If Instagram identifies the Story as video but only a cover was resolved,
        // require an explicit photo choice instead of silently saving it as an MP4.
        if (hasImage) return modelSaysVideo ? Decision.ASK : Decision.DOWNLOAD_PHOTO;
        return Decision.NOT_FOUND;
    }
}
