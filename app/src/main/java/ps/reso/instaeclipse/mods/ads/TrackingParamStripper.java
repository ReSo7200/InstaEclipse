package ps.reso.instaeclipse.mods.ads;

/** Pure (framework-free) tracking-param removal used by {@link TrackingLinkDisable}; unit-tested. */
public final class TrackingParamStripper {

    private TrackingParamStripper() {}

    // Instagram links inside arbitrary share text ("Check this out https://www.instagram.com/p/…").
    private static final java.util.regex.Pattern IG_URL = java.util.regex.Pattern.compile(
            "(?i)(?:https?://)?(?:[a-z0-9-]+\\.)*instagram\\.com/[^\\s]*");
    private static final java.util.regex.Pattern FOUR_LETTERS = java.util.regex.Pattern.compile(
            "^[a-z]{4}$");

    /** True for query keys that only track the sharer/session (never change what the link opens). */
    private static boolean isTrackingParam(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.equals("igshid") || k.equals("igsh") || k.equals("ig_rid") || k.equals("ig_mid")
                || k.equals("stkn") || k.startsWith("utm_") || k.equals("story_media_id")
                || k.equals("saved_by") || k.equals("saved-by")
                || k.equals("psln") // per-share id on "Copy link" (IG 452)
                || FOUR_LETTERS.matcher(k).matches();
    }

    /**
     * Returns the text with tracking params removed from every Instagram link in it, or null when
     * nothing changed. Only the tracking keys are dropped; every other param (e.g. img_index, which
     * selects the carousel slide — #252), their order and any #fragment are preserved.
     */
    public static String strip(String text) {
        if (text == null || !text.toLowerCase(java.util.Locale.ROOT).contains("instagram.com/")) return null;
        java.util.regex.Matcher m = IG_URL.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String url = m.group();
            String cleaned = stripUrl(url);
            out.append(text, last, m.start()).append(cleaned);
            last = m.end();
            if (!cleaned.equals(url)) changed = true;
        }
        if (!changed) return null;
        out.append(text, last, text.length());
        return out.toString();
    }

    private static String stripUrl(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        int h = url.indexOf('#', q);
        String base = url.substring(0, q);
        String query = h >= 0 ? url.substring(q + 1, h) : url.substring(q + 1);
        String fragment = h >= 0 ? url.substring(h) : "";
        StringBuilder kept = new StringBuilder();
        boolean removed = false;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (isTrackingParam(key)) { removed = true; continue; }
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        if (!removed) return url;
        return kept.length() > 0 ? base + "?" + kept + fragment : base + fragment;
    }
}
