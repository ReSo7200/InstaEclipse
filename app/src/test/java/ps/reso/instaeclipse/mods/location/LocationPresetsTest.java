package ps.reso.instaeclipse.mods.location;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LocationPresetsTest {

    @Test
    public void parsesPastedCoordinates() {
        assertArrayEquals(new double[]{41.0082, 28.9784}, LocationPresets.parseCoordinates("41.0082, 28.9784"), 1e-9);
        assertArrayEquals(new double[]{-33.86, 151.2}, LocationPresets.parseCoordinates(" -33.86;151.2 "), 1e-9);
        assertArrayEquals(new double[]{40.7, -74.0}, LocationPresets.parseCoordinates("40.7 -74.0"), 1e-9);
    }

    @Test
    public void rejectsNonCoordinates() {
        assertNull(LocationPresets.parseCoordinates("Kadıköy, İstanbul"));
        assertNull(LocationPresets.parseCoordinates("95.0, 10.0"));   // latitude out of range
        assertNull(LocationPresets.parseCoordinates("0, 0"));         // treated as "unset"
    }

    @Test
    public void rememberMovesPickToFrontAndDropsNearDuplicates() {
        LocationPresets.Preset a = new LocationPresets.Preset(41.0, 29.0, "A");
        LocationPresets.Preset b = new LocationPresets.Preset(39.9, 32.8, "B");
        LocationPresets.Preset aAgain = new LocationPresets.Preset(41.0002, 29.0002, "A2"); // ~28 m away

        List<LocationPresets.Preset> out = LocationPresets.remember(Arrays.asList(a, b), aAgain);
        assertEquals(2, out.size());
        assertEquals("A2", out.get(0).label);
        assertEquals("B", out.get(1).label);
    }

    @Test
    public void rememberCapsHistory() {
        List<LocationPresets.Preset> recent = new ArrayList<>();
        for (int i = 0; i < 10; i++) recent.add(new LocationPresets.Preset(10 + i, 20 + i, "P" + i));
        List<LocationPresets.Preset> out = LocationPresets.remember(recent, new LocationPresets.Preset(50, 50, "new"));
        assertEquals(LocationPresets.MAX_RECENT, out.size());
        assertEquals("new", out.get(0).label);
    }

    @Test
    public void distanceIsHaversine() {
        // Istanbul (Sultanahmet) -> Ankara (Kızılay): ~350 km great-circle
        double d = LocationPresets.distanceMeters(41.0055, 28.9768, 39.9208, 32.8541);
        assertTrue(d > 345_000 && d < 355_000);
    }

    @Test
    public void jitterStaysWithinRadius() {
        for (double u1 = 0; u1 < 1; u1 += 0.1) {
            for (double u2 = 0; u2 < 1; u2 += 0.1) {
                double[] p = LocationPresets.jitter(60.0, 10.0, u1, u2);
                double d = LocationPresets.distanceMeters(60.0, 10.0, p[0], p[1]);
                assertTrue("jitter " + d, d <= LocationPresets.JITTER_METERS + 0.05);
            }
        }
    }
}
