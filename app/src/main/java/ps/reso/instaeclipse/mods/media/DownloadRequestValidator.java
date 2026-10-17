package ps.reso.instaeclipse.mods.media;

import java.net.URI;
import java.util.Locale;

/**
 * Input validation for {@link DownloadSaveService}. The service is exported (Instagram runs
 * under a different uid), so any app can start it and every extra is untrusted. Pure Java so it
 * can be unit-tested on the JVM.
 */
public final class DownloadRequestValidator {

    /** Hosts Instagram serves media from. Subdomains are allowed. */
    private static final String[] ALLOWED_HOST_SUFFIXES = {
            "cdninstagram.com",
            "fbcdn.net",
            "fbsbx.com",
    };

    static final int MAX_NAME_LENGTH = 120;

    private DownloadRequestValidator() {}

    /** True only for https URLs whose host is (a subdomain of) an Instagram/Facebook CDN. */
    public static boolean isAllowedMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getRawUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String suffix : ALLOWED_HOST_SUFFIXES) {
                if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Strips path separators, control characters and leading dots so the value can only ever
     * name a single entry inside the chosen SAF folder. Returns {@code fallback} if nothing
     * usable is left.
     */
    public static String sanitizeFileName(String name, String fallback) {
        if (name == null) return fallback;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        while (out.startsWith(".")) out = out.substring(1);
        if (out.length() > MAX_NAME_LENGTH) out = out.substring(out.length() - MAX_NAME_LENGTH);
        return out.isEmpty() ? fallback : out;
    }

    /** A relative folder path ("a/b") with every segment sanitized; "." / ".." segments dropped. */
    public static String sanitizeSubdir(String path) {
        if (path == null) return null;
        StringBuilder out = new StringBuilder();
        for (String seg : path.split("[/\\\\]+")) {
            String clean = sanitizeFileName(seg, null);
            if (clean == null) continue;
            if (out.length() > 0) out.append('/');
            out.append(clean);
        }
        return out.length() == 0 ? null : out.toString();
    }
}
