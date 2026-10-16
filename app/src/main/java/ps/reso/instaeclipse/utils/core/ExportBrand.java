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

    /** End-of-page footer. */
    public static String htmlFooter() {
        return "<div style=\"margin:24px 0 8px;text-align:center;font:500 12px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;opacity:.7\">"
                + "Exported with <b>◐ " + NAME + "</b> · " + escape(now()) + "</div>";
    }

    /** Trailing CSV line (after a blank line, so the data rows stay a clean table). */
    public static String csvFooter() {
        return "\n\"Exported with " + NAME + "\",\"" + now() + "\"\n";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
