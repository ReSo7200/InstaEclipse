package ps.reso.instaeclipse.mods.ads;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom share domain: links Instagram copies or shares point at another host (e.g. an
 * embed-friendly mirror) instead of instagram.com. Only the host is swapped; the path is kept.
 */
public final class ShareDomain {

    private static final Pattern HOST = Pattern.compile("(https?://)(?:www\\.)?instagram\\.com/");
    private static final Pattern VALID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

    private ShareDomain() {}

    /** Every instagram.com link in {@code text} pointed at {@code domain}; unchanged when domain is empty. */
    public static String apply(String text, String domain) {
        if (text == null || domain == null || domain.isEmpty() || !text.contains("instagram.com/")) return text;
        return HOST.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(domain) + "/");
    }

    /**
     * A bare host name from user input ("https://Example.com/" → "example.com"), "" for empty
     * input (turns the feature off), or null when it isn't a valid host.
     */
    public static String normalize(String input) {
        if (input == null) return "";
        String d = input.trim().toLowerCase(Locale.ROOT);
        d = d.replaceFirst("^[a-z]+://", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (d.isEmpty()) return "";
        if (d.length() > 253 || !VALID.matcher(d).matches() || d.endsWith("instagram.com")) return null;
        return d;
    }
}
