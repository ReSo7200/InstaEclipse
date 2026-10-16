package ps.reso.instaeclipse.utils.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Hardening for the broadcast bridge between the companion app and the hooked Instagram process.
 *
 * <ul>
 *   <li><b>Companion → Instagram</b>: receivers inside Instagram are registered with
 *   {@link #PERMISSION}, a signature-level permission only the companion APK holds. Other
 *   installed apps can no longer flip settings, overwrite mc_overrides.json or restore
 *   arbitrary backups by broadcasting to Instagram.</li>
 *   <li><b>Instagram → companion</b>: Instagram cannot hold our signature permission, so the
 *   companion attaches a random nonce to each request and only accepts replies echoing it.
 *   Requests are package-targeted, so no other app can observe the nonce.</li>
 * </ul>
 */
public final class IpcSecurity {

    /** Signature permission declared in AndroidManifest.xml; required from senders into Instagram. */
    public static final String PERMISSION = "ps.reso.instaeclipse.permission.MODULE_IPC";
    public static final String EXTRA_NONCE = "ie_nonce";

    private static final SecureRandom RNG = new SecureRandom();

    private IpcSecurity() {}

    /** Registers a receiver in the hooked app that only accepts broadcasts from the companion. */
    public static void registerCompanionOnlyReceiver(Context context, BroadcastReceiver receiver,
                                                     IntentFilter filter) {
        ContextCompat.registerReceiver(context, receiver, filter, PERMISSION, null,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /**
     * Registers a receiver in the companion for replies from the hooked app. It must be exported
     * (the reply comes from another uid); authenticity is enforced with {@link #nonceMatches}.
     */
    public static void registerReplyReceiver(Context context, BroadcastReceiver receiver,
                                             IntentFilter filter) {
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    public static String newNonce() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Copies the request nonce (if any) onto a reply. Used on the Instagram side. */
    public static void echoNonce(Intent request, Intent reply) {
        String nonce = request.getStringExtra(EXTRA_NONCE);
        if (nonce != null) reply.putExtra(EXTRA_NONCE, nonce);
    }

    /** Constant-time check that {@code reply} carries the nonce we sent. */
    public static boolean nonceMatches(String expected, Intent reply) {
        if (expected == null || reply == null) return false;
        String got = reply.getStringExtra(EXTRA_NONCE);
        if (got == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                got.getBytes(StandardCharsets.UTF_8));
    }
}
