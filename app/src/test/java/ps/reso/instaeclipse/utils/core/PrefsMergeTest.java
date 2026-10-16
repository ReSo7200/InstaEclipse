package ps.reso.instaeclipse.utils.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PrefsMergeTest {

    @Test
    public void instagramChangeReachesCompanion() {
        Map<String, Object> ig = new HashMap<>();
        ig.put("disableSwipeToCamera", true);
        ig.put("__igts_disableSwipeToCamera", 200L);
        Map<String, Object> cc = new HashMap<>();
        cc.put("disableSwipeToCamera", false);
        cc.put("__ts_disableSwipeToCamera", 100L);

        Map<String, Object> out = PrefsMerge.select(ig, cc);
        assertEquals(Boolean.TRUE, out.get("disableSwipeToCamera"));
    }

    @Test
    public void newerCompanionChangeIsKept() {
        Map<String, Object> ig = new HashMap<>();
        ig.put("isGhostSeen", false);
        ig.put("__igts_isGhostSeen", 100L);
        ig.put("__applied_isGhostSeen", 50L);
        Map<String, Object> cc = new HashMap<>();
        cc.put("isGhostSeen", true);
        cc.put("__ts_isGhostSeen", 300L);

        assertTrue(PrefsMerge.select(ig, cc).isEmpty());
    }

    @Test
    public void appliedCompanionChangeNoLongerBlocksInstagramValue() {
        // Instagram applied the companion's ts=300 value, then the key changed in Instagram
        // before __igts_ stamps existed: Instagram's current value is the effective one.
        Map<String, Object> ig = new HashMap<>();
        ig.put("hideTabReels", false);
        ig.put("__applied_hideTabReels", 300L);
        Map<String, Object> cc = new HashMap<>();
        cc.put("hideTabReels", true);
        cc.put("__ts_hideTabReels", 300L);

        assertEquals(Boolean.FALSE, PrefsMerge.select(ig, cc).get("hideTabReels"));
    }

    @Test
    public void unstampedValuesFollowInstagram() {
        Map<String, Object> ig = new HashMap<>();
        ig.put("forceReelQuality", 2);
        ig.put("startupTab", "reels");
        Map<String, Object> out = PrefsMerge.select(ig, new HashMap<>());
        assertEquals(2, out.get("forceReelQuality"));
        assertEquals("reels", out.get("startupTab"));
    }

    @Test
    public void secretsStampsAndUnchangedValuesAreSkipped() {
        Map<String, Object> ig = new HashMap<>();
        ig.put("lockDirectPasscode", "hash");
        ig.put("lockDirectSalt", "salt");
        ig.put(IpcSecurity.EXTRA_NONCE, "abc");
        ig.put("__igts_x", 5L);
        ig.put("someLong", 7L);
        ig.put("same", true);
        Map<String, Object> cc = new HashMap<>();
        cc.put("same", true);

        assertTrue(PrefsMerge.select(ig, cc).isEmpty());
        assertFalse(PrefsMerge.isSharedSetting("__igts_x", 5L));
        assertTrue(PrefsMerge.isSharedStamp("__igts_x", 5L));
        assertTrue(PrefsMerge.isSharedStamp("__applied_x", 5L));
        assertFalse(PrefsMerge.isSharedStamp("__ts_x", 5L));
    }
}
