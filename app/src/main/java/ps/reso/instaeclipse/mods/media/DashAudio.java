package ps.reso.instaeclipse.mods.media;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the audio track URL out of a video's DASH manifest (Media "video_dash_manifest", #234).
 * Instagram serves reels/videos as separate audio + video DASH streams; the audio AdaptationSet's
 * BaseURL is a plain CDN URL to an AAC-in-MP4 (.m4a) file. Framework-free so it is unit-tested.
 */
public final class DashAudio {

    private static final Pattern ADAPTATION = Pattern.compile(
            "<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern REPRESENTATION = Pattern.compile(
            "<Representation\\b([^>]*)>(.*?)</Representation>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE_URL = Pattern.compile(
            "<BaseURL[^>]*>\\s*([^<]+?)\\s*</BaseURL>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern BANDWIDTH = Pattern.compile("\\bbandwidth\\s*=\\s*\"(\\d+)\"");

    private DashAudio() {}

    /** Highest-bandwidth audio BaseURL in the manifest, or null when there is no audio track. */
    public static String audioUrl(String manifest) {
        if (manifest == null || manifest.isEmpty()) return null;
        Matcher sets = ADAPTATION.matcher(manifest);
        while (sets.find()) {
            String attrs = sets.group(1), body = sets.group(2);
            if (!isAudio(attrs) && !isAudio(firstRepresentationAttrs(body))) continue;
            String best = null;
            long bestBw = -1;
            Matcher reps = REPRESENTATION.matcher(body);
            while (reps.find()) {
                Matcher u = BASE_URL.matcher(reps.group(2));
                if (!u.find()) continue;
                long bw = bandwidth(reps.group(1));
                if (bw > bestBw) { bestBw = bw; best = u.group(1); }
            }
            if (best == null) {
                Matcher u = BASE_URL.matcher(body);
                if (u.find()) best = u.group(1);
            }
            if (best != null) return unescape(best.trim());
        }
        return null;
    }

    private static final Pattern HEIGHT = Pattern.compile("\\bheight\\s*=\\s*\"(\\d+)\"");
    private static final Pattern CODECS = Pattern.compile("\\bcodecs\\s*=\\s*\"([^\"]+)\"");

    /**
     * The tallest video Representation as {BaseURL, height}, or null. Ties go to the higher bitrate;
     * {@code skipAv1} leaves AV1 streams out (MediaMuxer can not write AV1 before Android 14).
     */
    public static String[] bestVideo(String manifest, boolean skipAv1) {
        if (manifest == null || manifest.isEmpty()) return null;
        String best = null;
        long bestH = -1, bestBw = -1;
        Matcher sets = ADAPTATION.matcher(manifest);
        while (sets.find()) {
            String attrs = sets.group(1), body = sets.group(2);
            if (isAudio(attrs) || isAudio(firstRepresentationAttrs(body))) continue;
            Matcher reps = REPRESENTATION.matcher(body);
            while (reps.find()) {
                String ra = reps.group(1);
                Matcher u = BASE_URL.matcher(reps.group(2));
                Matcher h = HEIGHT.matcher(ra);
                if (!u.find() || !h.find()) continue;
                long height = Long.parseLong(h.group(1)), bw = bandwidth(ra);
                Matcher c = CODECS.matcher(ra);
                if (skipAv1 && c.find() && c.group(1).toLowerCase(java.util.Locale.ROOT).startsWith("av01")) continue;
                if (height > bestH || (height == bestH && bw > bestBw)) {
                    best = u.group(1); bestH = height; bestBw = bw;
                }
            }
        }
        return best == null ? null : new String[]{unescape(best.trim()), String.valueOf(bestH)};
    }

    private static boolean isAudio(String attrs) {
        if (attrs == null) return false;
        String a = attrs.toLowerCase(java.util.Locale.ROOT);
        return a.contains("contenttype=\"audio\"") || a.contains("mimetype=\"audio/");
    }

    private static String firstRepresentationAttrs(String body) {
        Matcher m = REPRESENTATION.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static long bandwidth(String attrs) {
        Matcher m = BANDWIDTH.matcher(attrs);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }
}
