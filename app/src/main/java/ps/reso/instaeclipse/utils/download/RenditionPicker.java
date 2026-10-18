/* Based on HushGram ranking; GPL-3.0-only. See third-party/HushGram/NOTICE.md. */
package ps.reso.instaeclipse.utils.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dimensions and frame rate decide quality; unrelated codecs' bitrates never decide it. */
final class RenditionPicker {
    static final class Choice {
        final DownloadRequest.Rendition video;
        final DashManifest.Track audio;
        final boolean dash, expectAudio;
        final String unsupported;
        Choice(DownloadRequest.Rendition video, DashManifest.Track audio, boolean dash, boolean expectAudio, String unsupported) {
            this.video = video; this.audio = audio; this.dash = dash;
            this.expectAudio = expectAudio; this.unsupported = unsupported;
        }
    }
    static final class Plan {
        final DashManifest manifest;
        final List<Choice> choices;
        final int excludedByCeiling;
        Plan(DashManifest manifest, List<Choice> choices, int excludedByCeiling) {
            this.manifest = manifest; this.choices = Collections.unmodifiableList(choices);
            this.excludedByCeiling = excludedByCeiling;
        }
    }
    static Plan plan(DownloadRequest request) {
        if (request.options.originalSelection) {
            DownloadRequest.Rendition selected = request.direct.get(0);
            List<Choice> original = new ArrayList<>();
            if (MediaUrlPolicy.shapeRefusal(selected.url) == null)
                original.add(new Choice(selected, null, false, false, null));
            return new Plan(DashManifest.parse(""), original, 0);
        }
        DashManifest manifest = DashManifest.parse(request.manifest);
        List<Choice> choices = new ArrayList<>();
        boolean manifestSound = manifest.tracks.stream().anyMatch(DashManifest.Track::audio);
        boolean expectAudio = request.audio == DownloadRequest.AudioExpectation.PRESENT || manifestSound;
        Set<String> seen = new HashSet<>();
        for (DownloadRequest.Rendition direct : request.direct) {
            if (MediaUrlPolicy.shapeRefusal(direct.url) == null && seen.add(direct.url))
                choices.add(new Choice(direct, null, false, expectAudio, null));
        }
        DashManifest.Track audio = pickAudio(manifest.tracks);
        for (DashManifest.Track track : manifest.tracks) {
            if (!track.video()) continue;
            String refusal = supportedVideo(track.codecs) ? null : "Selected DASH video codec is unsupported";
            if (expectAudio && audio == null) refusal = "Selected DASH video has no supported corresponding audio";
            DownloadRequest.Rendition video = new DownloadRequest.Rendition(track.url, track.width, track.height,
                    track.frameRate, track.codecs, track.bandwidth);
            choices.add(new Choice(video, audio, true, expectAudio, refusal));
        }
        int discovered = choices.size();
        if (request.options.maxShortSide > 0)
            choices.removeIf(c -> c.video.shortSide() > request.options.maxShortSide);
        choices.sort((a, b) -> compare(a.video, b.video, request.options));
        return new Plan(manifest, choices, discovered - choices.size());
    }
    static int compare(DownloadRequest.Rendition a, DownloadRequest.Rendition b, DownloadRequest.Options options) {
        int order;
        if (options.maxShortSide > 0) {
            int aGroup = ceilingGroup(a, options.maxShortSide), bGroup = ceilingGroup(b, options.maxShortSide);
            if (aGroup != bGroup) return Integer.compare(aGroup, bGroup);
        }
        if (options.compatibility && (order = Integer.compare(compatibility(b.codecs), compatibility(a.codecs))) != 0) return order;
        if ((order = Integer.compare(b.shortSide(), a.shortSide())) != 0) return order;
        if ((order = Long.compare((long) b.width * b.height, (long) a.width * a.height)) != 0) return order;
        if ((order = Double.compare(b.frameRate, a.frameRate)) != 0) return order;
        if (a.codecs.isEmpty() != b.codecs.isEmpty()) return a.codecs.isEmpty() ? 1 : -1;
        // Bitrate only resolves a tie within the exact same known codec/profile and frame rate.
        if (!a.codecs.isEmpty() && a.codecs.equals(b.codecs) && a.bitrate > 0 && b.bitrate > 0
                && (order = Long.compare(b.bitrate, a.bitrate)) != 0) return order;
        if ((order = a.codecs.compareTo(b.codecs)) != 0) return order;
        // Stable neutral tie-break. Neither direct/DASH nor URL labels get a quality bonus.
        return a.url.compareTo(b.url);
    }
    private static int ceilingGroup(DownloadRequest.Rendition r, int ceiling) {
        return r.shortSide() == 0 ? 1 : r.shortSide() <= ceiling ? 0 : 2;
    }
    private static int compatibility(String codecs) {
        return codecs.startsWith("avc1") || codecs.startsWith("avc3") ? 2 : codecs.isEmpty() ? 1 : 0;
    }
    static boolean supportedVideo(String codecs) {
        return codecs.startsWith("avc1") || codecs.startsWith("avc3") || codecs.startsWith("hvc1")
                || codecs.startsWith("hev1") || codecs.startsWith("av01") || codecs.startsWith("vp09");
    }
    private static DashManifest.Track pickAudio(List<DashManifest.Track> tracks) {
        return tracks.stream().filter(t -> t.audio() && t.codecs.startsWith("mp4a.40."))
                .min(Comparator.<DashManifest.Track>comparingInt(t -> -audioCompatibility(t.codecs))
                        .thenComparing(t -> t.codecs)
                        .thenComparingLong(t -> -t.bandwidth).thenComparing(t -> t.url)).orElse(null);
    }
    private static int audioCompatibility(String codecs) {
        if (codecs.equals("mp4a.40.2")) return 2;
        return codecs.equals("mp4a.40.5") || codecs.equals("mp4a.40.29") ? 1 : 0;
    }
}
