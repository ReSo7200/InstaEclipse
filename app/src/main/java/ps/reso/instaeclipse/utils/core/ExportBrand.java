package ps.reso.instaeclipse.utils.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * InstaEclipse branding shared by every export (chat HTML, comments HTML, follow-list CSV):
 * a banner at the top and a footer at the end of HTML pages, a trailing line in CSV files.
 * Inline styles only, so it looks the same whatever page it is put in.
 */
public final class ExportBrand {

    public static final String NAME = "InstaEclipse";

    private ExportBrand() {}

    private static String now() {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date());
    }

    /** Top-of-page banner: gradient wordmark + what was exported and when. */
    public static String htmlBanner(String what) {
        return "<div style=\"display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;"
                + "margin:0 0 14px;padding:12px 16px;border-radius:16px;background:linear-gradient(135deg,#4f5bd5,#962fbf 40%,#d62976 75%,#fa7e1e);"
                + "color:#fff;font:600 14px/1.3 -apple-system,system-ui,Segoe UI,Roboto,sans-serif\">"
                + "<span style=\"font-size:18px;font-weight:800;letter-spacing:.3px\">◐ " + NAME + "</span>"
                + "<span style=\"opacity:.9;font-weight:500\">" + escape(what) + " · " + escape(now()) + "</span></div>";
    }

    public static final String GITHUB   = "https://github.com/ReSo7200/InstaEclipse";
    public static final String TELEGRAM = "https://t.me/InstaEclipse";

    /** The Instagram build this release is tested on; "Get APK" opens its APKMirror page. */
    public static final String TESTED_INSTAGRAM = "451.0.0.20.80";
    public static final String TESTED_INSTAGRAM_APK = "https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-"
            + TESTED_INSTAGRAM.replace('.', '-') + "-release/";

    private static final String GITHUB_SVG =
            "<svg width=15 height=15 viewBox=\"0 0 24 24\" fill=\"currentColor\" style=\"vertical-align:-2px\"><path d=\"M12 .5C5.37.5 0 5.87 0 12.5c0 5.3 3.44 9.8 8.21 11.39.6.11.82-.26.82-.58v-2.03c-3.34.73-4.04-1.61-4.04-1.61-.55-1.39-1.33-1.76-1.33-1.76-1.09-.75.08-.73.08-.73 1.2.09 1.84 1.24 1.84 1.24 1.07 1.83 2.8 1.3 3.49.99.11-.78.42-1.3.76-1.6-2.67-.3-5.47-1.33-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.13-.3-.54-1.52.12-3.18 0 0 1.01-.32 3.3 1.23a11.5 11.5 0 0 1 6 0c2.29-1.55 3.3-1.23 3.3-1.23.66 1.66.25 2.88.12 3.18.77.84 1.23 1.91 1.23 3.22 0 4.61-2.8 5.63-5.48 5.92.43.37.81 1.1.81 2.22v3.29c0 .32.22.69.83.57A12 12 0 0 0 24 12.5C24 5.87 18.63.5 12 .5z\"/></svg>";
    private static final String TELEGRAM_SVG =
            "<svg width=15 height=15 viewBox=\"0 0 24 24\" fill=\"currentColor\" style=\"vertical-align:-2px\"><path d=\"M23.95 2.9 20.3 20.6c-.27 1.22-1 1.52-2.02.95l-5.58-4.12-2.69 2.59c-.3.3-.55.55-1.12.55l.4-5.68L19.6 4.98c.45-.4-.1-.63-.69-.23L5.7 13.03.26 11.33c-1.18-.37-1.2-1.18.25-1.75L22.43 1.2c.98-.37 1.84.22 1.52 1.7z\"/></svg>";

    /** End-of-page footer: gradient wordmark + GitHub / Telegram buttons. */
    public static String htmlFooter() {
        return "<div style=\"margin:30px 0 10px;text-align:center;font:500 12px/1.5 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;color:#8a8a92\">"
                + "<div style=\"font-weight:800;font-size:16px;letter-spacing:.3px;margin-bottom:10px;"
                + "background:linear-gradient(135deg,#4f5bd5,#962fbf 40%,#d62976 72%,#fa7e1e);-webkit-background-clip:text;background-clip:text;color:transparent\">◐ " + NAME + "</div>"
                + "<div style=\"display:flex;gap:8px;justify-content:center;flex-wrap:wrap;margin-bottom:10px\">"
                + button(GITHUB, GITHUB_SVG, "GitHub") + button(TELEGRAM, TELEGRAM_SVG, "Telegram")
                + "</div>"
                + "Exported " + escape(now()) + "</div>";
    }

    /** A pill link that reads on both light and dark pages (translucent accent fill + accent text). */
    private static String button(String url, String svg, String label) {
        return "<a href=\"" + url + "\" target=_blank rel=noopener style=\"display:inline-flex;align-items:center;gap:7px;"
                + "padding:8px 16px;border-radius:999px;text-decoration:none;font-weight:700;font-size:13px;"
                + "color:#a46bff;background:rgba(150,47,191,.12);border:1px solid rgba(150,47,191,.32)\">"
                + svg + label + "</a>";
    }

    /** Trailing CSV line (after a blank line, so the data rows stay a clean table). */
    public static String csvFooter() {
        return "\n\"Exported with " + NAME + "\",\"" + now() + "\"\n";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
