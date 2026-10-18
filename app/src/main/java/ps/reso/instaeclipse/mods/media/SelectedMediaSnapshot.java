package ps.reso.instaeclipse.mods.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ps.reso.instaeclipse.utils.download.DownloadRequest;

/** Detached at the user action. Never stores an Instagram object, View or mutable model list. */
final class SelectedMediaSnapshot {
    interface Access {
        Value read(Object receiver, String property) throws Exception;
        default String originalVideo(Object receiver) throws Exception { return null; }
        default OriginalMediaSelection.Plan originalPlan(Object receiver, String progressive) throws Exception { return null; }
        default OriginalMediaSelection.Audio audio(Object receiver, Object post) throws Exception { return null; }
        default String originalImage(Object receiver, boolean carouselChild) throws Exception { return null; }
    }

    static final class Value {
        final boolean bound;
        final Object value;
        Value(boolean bound, Object value) { this.bound = bound; this.value = value; }
        static Value missing() { return new Value(false, null); }
        static Value of(Object value) { return new Value(true, value); }
    }

    final String id, owner, manifest, imageUrl, originalVideoUrl, originalImageUrl;
    final boolean video, photoWithMusic;
    final OriginalMediaSelection.Plan originalPlan;
    final OriginalMediaSelection.Audio separateAudio;
    final DownloadRequest.AudioExpectation audio;
    final List<DownloadRequest.Rendition> renditions;
    final List<String> imageUrls;
    final List<SelectedMediaSnapshot> children;

    private SelectedMediaSnapshot(String id, String owner, String manifest, String imageUrl,
            boolean video, boolean photoWithMusic, DownloadRequest.AudioExpectation audio,
            List<DownloadRequest.Rendition> renditions, List<String> imageUrls,
            String originalVideoUrl, String originalImageUrl, OriginalMediaSelection.Plan originalPlan,
            OriginalMediaSelection.Audio separateAudio, List<SelectedMediaSnapshot> children) {
        this.id = id; this.owner = owner; this.manifest = manifest; this.imageUrl = imageUrl;
        this.video = video; this.photoWithMusic = photoWithMusic; this.audio = audio;
        this.renditions = Collections.unmodifiableList(new ArrayList<>(renditions));
        this.imageUrls = Collections.unmodifiableList(new ArrayList<>(imageUrls));
        this.originalVideoUrl = OriginalMediaSelection.video(originalVideoUrl, this.renditions);
        this.originalImageUrl = OriginalMediaSelection.image(originalImageUrl, this.imageUrls);
        this.originalPlan = OriginalMediaSelection.guardedPlan(originalVideoUrl, originalPlan, this.renditions, manifest);
        this.separateAudio = separateAudio;
        this.children = Collections.unmodifiableList(new ArrayList<>(children));
    }

    static SelectedMediaSnapshot capture(Object selected, Access access) throws Exception {
        if (selected == null) throw new IllegalStateException("Selected media is unavailable");
        return capture(selected, access, 0, null, selected);
    }

    private static SelectedMediaSnapshot capture(Object selected, Access a, int depth,
            String parentOwner, Object post) throws Exception {
        // Preserve upstream discovery order before the new getters materialize native data.
        // A missing legacy helper does not make the independent custom request unavailable.
        String originalVideo = null, originalImage = null;
        try { originalVideo = a.originalVideo(selected); } catch (Exception | LinkageError ignored) { }
        try { originalImage = a.originalImage(selected, depth > 0); } catch (Exception | LinkageError ignored) { }
        OriginalMediaSelection.Plan originalPlan = null;
        OriginalMediaSelection.Audio separateAudio = null;
        try { originalPlan = a.originalPlan(selected, originalVideo); } catch (Exception | LinkageError ignored) { }
        try { separateAudio = a.audio(selected, post); } catch (Exception | LinkageError ignored) { }
        String id = string(a.read(selected, "id").value);
        if (id == null || id.length() > 200) throw new IllegalStateException("Media identity unavailable");
        Object user = a.read(selected, "user").value;
        String owner = user == null ? parentOwner : string(a.read(user, "username").value);
        List<SelectedMediaSnapshot> children = new ArrayList<>();
        Object carousel = a.read(selected, "carousel_media").value;
        if (carousel instanceof List<?> items && !items.isEmpty()) {
            if (depth > 0 || items.size() > 100) throw new IllegalStateException("Unsupported carousel model");
            for (Object child : new ArrayList<>(items)) children.add(capture(child, a, depth + 1, owner, post));
        }
        Value dash = a.read(selected, "video_dash_manifest");
        String manifest = string(dash.value);
        if (manifest != null && manifest.length() > DownloadRequest.MAX_MANIFEST_CHARS)
            throw new IllegalStateException("DASH manifest exceeds safe capture size");
        Value versions = a.read(selected, "video_versions");
        List<DownloadRequest.Rendition> renditions = new ArrayList<>();
        if (versions.value instanceof List<?> items) {
            if (items.size() > DownloadRequest.MAX_RENDITIONS) throw new IllegalStateException("Too many media renditions");
            for (Object item : new ArrayList<>(items)) {
                String url = string(a.read(item, "url").value);
                if (url == null) continue;
                renditions.add(new DownloadRequest.Rendition(url,
                        positiveInt(a.read(item, "width").value),
                        positiveInt(a.read(item, "height").value),
                        positiveDouble(a.read(item, "frame_rate").value),
                        string(a.read(item, "codecs").value),
                        positiveLong(a.read(item, "bitrate").value)));
            }
        }
        int type = positiveInt(a.read(selected, "media_type").value);
        boolean video = type == 2 || manifest != null || !renditions.isEmpty();
        if ((video || (type != 1 && children.isEmpty())) && (!dash.bound || !versions.bound))
            throw new IllegalStateException("Video adapter incomplete for this Instagram build");
        String image = null;
        List<String> imageUrls = new ArrayList<>();
        long bestArea = -1;
        Object imageInfo = a.read(selected, "image_versions2").value;
        Object candidates = imageInfo == null ? null : a.read(imageInfo, "candidates").value;
        if (candidates instanceof List<?> items) {
            if (items.size() > 100) throw new IllegalStateException("Too many image candidates");
            for (Object item : new ArrayList<>(items)) {
                String url = string(a.read(item, "url").value);
                if (url != null) {
                    if (url.length() > OriginalMediaSelection.MAX_URL_CHARS)
                        throw new IllegalStateException("Image URL exceeds safe capture size");
                    if (!imageUrls.contains(url)) imageUrls.add(url);
                }
                long area = (long) positiveInt(a.read(item, "width").value)
                        * positiveInt(a.read(item, "height").value);
                if (url != null && area > bestArea) { bestArea = area; image = url; }
            }
        }
        boolean music = Boolean.TRUE.equals(a.read(selected, "is_story_image_with_music").value)
                || (video && positiveInt(a.read(selected, "original_media_type").value) == 1);
        Object hasAudio = a.read(selected, "has_audio").value;
        DownloadRequest.AudioExpectation audio = Boolean.TRUE.equals(hasAudio) || music
                ? DownloadRequest.AudioExpectation.PRESENT
                : Boolean.FALSE.equals(hasAudio) ? DownloadRequest.AudioExpectation.SILENT
                : DownloadRequest.AudioExpectation.UNKNOWN;
        if (!video && image == null && children.isEmpty())
            throw new IllegalStateException("Selected media has no resolved downloadable candidates");
        // A failed or missing video binding must never silently turn a video into its cover.
        if (video && manifest == null && renditions.isEmpty())
            throw new IllegalStateException("Selected video has no exposed downloadable candidates");
        return new SelectedMediaSnapshot(id, owner, manifest, image, video, music, audio,
                renditions, imageUrls, originalVideo, originalImage, originalPlan, separateAudio, children);
    }

    DownloadRequest request(String filename, DownloadRequest.Options options) {
        return new DownloadRequest(id, owner, filename, manifest, renditions, options, audio);
    }

    private static String string(Object v) { return v instanceof String s && !s.isEmpty() ? s : null; }
    private static int positiveInt(Object v) { long n = positiveLong(v); return n > 100000 ? 0 : (int) n; }
    private static long positiveLong(Object v) { return v instanceof Number n && n.longValue() > 0 ? n.longValue() : 0; }
    private static double positiveDouble(Object v) { return v instanceof Number n && n.doubleValue() > 0 && Double.isFinite(n.doubleValue()) ? n.doubleValue() : 0; }
}
