package ps.reso.instaeclipse.utils.text;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Date/time format for "Show exact dates" (#265): the device's short date + time, one of the
 * presets, or the user's own SimpleDateFormat pattern. Framework-free so it is unit-tested.
 */
public final class TimestampFormat {

    /** Pattern choices; "" (the first) is the device's own short date + time. */
    public static final String[] PRESETS = {
            "",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd/MM/yyyy HH:mm",
            "MM/dd/yyyy h:mm a",
            "dd.MM.yyyy HH:mm",
            "d MMM yyyy, HH:mm",
            "EEE, d MMM yyyy HH:mm",
    };

    private TimestampFormat() {}

    /** A pattern SimpleDateFormat accepts (empty = the device default, also valid). */
    public static boolean isValid(String pattern) {
        if (pattern == null) return false;
        if (pattern.isEmpty()) return true;
        if (pattern.length() > 64) return false;
        try {
            new SimpleDateFormat(pattern, Locale.US).format(new Date(0));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code date} in {@code pattern}; the device's short format when it is empty or invalid. */
    public static String format(String pattern, Date date, Locale locale) {
        if (pattern != null && !pattern.isEmpty() && isValid(pattern)) {
            return new SimpleDateFormat(pattern, locale).format(date);
        }
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(date);
    }
}
