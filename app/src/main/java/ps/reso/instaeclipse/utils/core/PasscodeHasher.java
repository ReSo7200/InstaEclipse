package ps.reso.instaeclipse.utils.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashing for the DM / whole-app lock passcode.
 *
 * <p>New hashes use PBKDF2-HMAC-SHA256 and are stored as {@code pbkdf2$<iterations>$<hex>}.
 * Hashes created by older builds are a bare hex {@code sha256(salt + passcode)}; they still
 * verify, and {@link #needsUpgrade} tells the caller to re-hash after a successful unlock.
 *
 * <p>A short numeric PIN has a tiny keyspace, so no hash makes it strong against someone who
 * can read Instagram's private prefs. PBKDF2 only raises the cost of that offline guess.
 */
public final class PasscodeHasher {

    static final int ITERATIONS = 60_000;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2$";

    private PasscodeHasher() {}

    public static String hash(String passcode, String salt) {
        return PREFIX + ITERATIONS + "$" + pbkdf2(passcode, salt, ITERATIONS);
    }

    public static boolean verify(String passcode, String salt, String stored) {
        if (passcode == null || stored == null || stored.isEmpty()) return false;
        String s = salt == null ? "" : salt;
        String candidate;
        if (stored.startsWith(PREFIX)) {
            String[] parts = stored.split("\\$");
            if (parts.length != 3) return false;
            int iterations;
            try {
                iterations = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (iterations < 1) return false;
            candidate = PREFIX + iterations + "$" + pbkdf2(passcode, s, iterations);
        } else {
            candidate = sha256Hex(s + passcode);
        }
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
    }

    /** True for legacy SHA-256 hashes or PBKDF2 hashes with fewer iterations than today. */
    public static boolean needsUpgrade(String stored) {
        if (stored == null || stored.isEmpty()) return false;
        if (!stored.startsWith(PREFIX)) return true;
        String[] parts = stored.split("\\$");
        try {
            return parts.length != 3 || Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String pbkdf2(String passcode, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(passcode.toCharArray(),
                ("instaeclipse-lock:" + salt).getBytes(StandardCharsets.UTF_8), iterations, KEY_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return toHex(f.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
