package ps.reso.instaeclipse.utils.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class TimestampFormatTest {

    private TimeZone saved;
    // 2026-10-18 14:05:09 UTC
    private static final Date WHEN = new Date(1792332309000L);

    @Before
    public void utc() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restore() {
        TimeZone.setDefault(saved);
    }

    @Test
    public void formatsPatterns() {
        assertEquals("2026-10-18 14:05:09", TimestampFormat.format("yyyy-MM-dd HH:mm:ss", WHEN, Locale.US));
        assertEquals("18/10/2026 14:05", TimestampFormat.format("dd/MM/yyyy HH:mm", WHEN, Locale.US));
        assertEquals("Sun, 18 Oct 2026 14:05", TimestampFormat.format("EEE, d MMM yyyy HH:mm", WHEN, Locale.US));
    }

    @Test
    public void emptyOrInvalidFallsBackToDeviceDefault() {
        String device = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.US).format(WHEN);
        assertEquals(device, TimestampFormat.format("", WHEN, Locale.US));
        assertEquals(device, TimestampFormat.format("yyyy-qq", WHEN, Locale.US));
        assertEquals(device, TimestampFormat.format(null, WHEN, Locale.US));
    }

    @Test
    public void validatesPatterns() {
        assertTrue(TimestampFormat.isValid(""));
        assertTrue(TimestampFormat.isValid("yyyy-MM-dd 'at' HH:mm"));
        assertFalse(TimestampFormat.isValid("yyyy-qq"));
        assertFalse(TimestampFormat.isValid(null));
        for (String p : TimestampFormat.PRESETS) assertTrue(p, TimestampFormat.isValid(p));
    }
}
