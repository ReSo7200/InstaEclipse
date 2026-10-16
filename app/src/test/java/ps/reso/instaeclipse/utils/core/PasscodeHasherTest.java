package ps.reso.instaeclipse.utils.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasscodeHasherTest {

    @Test
    public void newHashRoundTrips() {
        String stored = PasscodeHasher.hash("1234", "abcd");
        assertTrue(stored.startsWith("pbkdf2$" + PasscodeHasher.ITERATIONS + "$"));
        assertTrue(PasscodeHasher.verify("1234", "abcd", stored));
        assertFalse(PasscodeHasher.verify("1235", "abcd", stored));
        assertFalse(PasscodeHasher.verify("1234", "other", stored));
        assertFalse(PasscodeHasher.needsUpgrade(stored));
    }

    @Test
    public void saltChangesHash() {
        assertNotEquals(PasscodeHasher.hash("1234", "a"), PasscodeHasher.hash("1234", "b"));
    }

    @Test
    public void legacyHashesStillVerifyAndAskForUpgrade() {
        String legacySalted = PasscodeHasher.sha256Hex("salt" + "0000");
        assertTrue(PasscodeHasher.verify("0000", "salt", legacySalted));
        assertTrue(PasscodeHasher.needsUpgrade(legacySalted));

        String legacyUnsalted = PasscodeHasher.sha256Hex("0000");
        assertTrue(PasscodeHasher.verify("0000", "", legacyUnsalted));
        assertTrue(PasscodeHasher.verify("0000", null, legacyUnsalted));
    }

    @Test
    public void rejectsMalformedInput() {
        assertFalse(PasscodeHasher.verify("1", "s", null));
        assertFalse(PasscodeHasher.verify("1", "s", ""));
        assertFalse(PasscodeHasher.verify(null, "s", PasscodeHasher.hash("1", "s")));
        assertFalse(PasscodeHasher.verify("1", "s", "pbkdf2$x$abc"));
        assertFalse(PasscodeHasher.verify("1", "s", "pbkdf2$0$abc"));
        assertFalse(PasscodeHasher.needsUpgrade(""));
    }
}
