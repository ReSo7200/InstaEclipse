package ps.reso.instaeclipse.utils.version;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VersionComparatorTest {

    @Test
    public void comparesNumerically() {
        assertTrue(VersionComparator.compare("0.7.1", "0.7.0") > 0);
        assertTrue(VersionComparator.compare("0.10.0", "0.9.9") > 0);
        assertTrue(VersionComparator.compare("0.6.9", "0.7.0") < 0);
        assertEquals(0, VersionComparator.compare("0.7", "0.7.0"));
    }

    @Test
    public void ignoresPrefixAndSuffix() {
        assertEquals(0, VersionComparator.compare("v0.7.0", "0.7.0"));
        assertEquals(0, VersionComparator.compare("0.7.0-hotfix", "0.7.0"));
        assertTrue(VersionComparator.compare("1.0.0-beta", "0.9") > 0);
    }

    @Test
    public void handlesGarbage() {
        assertEquals(0, VersionComparator.compare(null, ""));
        assertTrue(VersionComparator.compare("0.0.1", null) > 0);
    }
}
