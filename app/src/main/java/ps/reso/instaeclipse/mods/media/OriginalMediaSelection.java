package ps.reso.instaeclipse.mods.media;

import java.util.List;

import ps.reso.instaeclipse.utils.download.DownloadRequest;

/** Ownership guard for an upstream selector's answer, never a substitute quality policy. */
final class OriginalMediaSelection {
    static final int MAX_URL_CHARS = 4096;

    private OriginalMediaSelection() { }

    static final class Plan {
        final String url, audioUrl, fallbackUrl;
        Plan(String url, String audioUrl, String fallbackUrl) {
            if (!bounded(url) || (audioUrl != null && !bounded(audioUrl))
                    || (fallbackUrl != null && !bounded(fallbackUrl)))
                throw new IllegalArgumentException("Invalid original selection");
            this.url = url; this.audioUrl = audioUrl; this.fallbackUrl = fallbackUrl;
        }
    }

    static final class Audio {
        final String url;
        final boolean extract, postWide;
        final long startMs, durationMs;
        Audio(String url, boolean extract, long startMs, long durationMs, boolean postWide) {
            if (!bounded(url) || startMs < 0 || startMs > 86400000 || durationMs < -1 || durationMs > 86400000)
                throw new IllegalArgumentException("Invalid audio selection");
            this.url = url; this.extract = extract; this.startMs = startMs;
            this.durationMs = durationMs; this.postWide = postWide;
        }
    }

    static Plan guardedPlan(String original, Plan plan,
            List<DownloadRequest.Rendition> selected, String manifest) {
        String direct = video(original, selected);
        if (direct == null) return null;
        if (plan == null) return new Plan(direct, null, null);
        if (plan.fallbackUrl == null)
            return direct.equals(plan.url) && plan.audioUrl == null ? plan : null;
        if (!direct.equals(plan.fallbackUrl) || manifest == null) return null;
        // The unchanged upstream parser determines the selected upgrade. Membership is
        // checked against this item's detached manifest, never a global URL buffer.
        try {
            String[] best = DashAudio.bestVideo(manifest, false);
            String[] compatible = DashAudio.bestVideo(manifest, true);
            boolean selectedVideo = best != null && plan.url.equals(best[0])
                    || compatible != null && plan.url.equals(compatible[0]);
            String audio = DashAudio.audioUrl(manifest);
            return selectedVideo && java.util.Objects.equals(plan.audioUrl, audio) ? plan : null;
        } catch (RuntimeException malformedOriginal) {
            return null; // An unavailable Original choice must not corrupt the Custom capture.
        }
    }

    static String video(String original, List<DownloadRequest.Rendition> selected) {
        if (!bounded(original)) return null;
        for (DownloadRequest.Rendition rendition : selected)
            if (original.equals(rendition.url)) return original;
        return null;
    }

    static String image(String original, List<String> selected) {
        return bounded(original) && selected.contains(original) ? original : null;
    }

    private static boolean bounded(String url) {
        return url != null && !url.isEmpty() && url.length() <= MAX_URL_CHARS;
    }
}
