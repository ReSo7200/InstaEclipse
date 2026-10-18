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
 *   <li><b>Instagram → companion</b>: Instagram receives a private, expiring Binder reply capability.
 *   No exported reply receiver or public broadcast carries logs or preferences.</li>
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

    /** Replies are private expiring Binder capabilities, never exported broadcast receivers. */
    public static ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending requestReply(
            Context context, Intent request, String replyAction, BroadcastReceiver receiver) {
        Intent addressed = new Intent(request);
        if (addressed.getPackage() == null) addressed.setPackage(CommonUtils.IG_PACKAGE_NAME);
        return ps.reso.instaeclipse.utils.ipc.ModuleReplies.request(context, addressed,
                data -> receiver.onReceive(context, new Intent(replyAction).putExtras(data)));
    }

    /** Authoritative UID and pinned signing history are required for provider access too. */
    public static String supportedCallerPackage(Context context, int callingUid) {
        if (context == null || callingUid == android.os.Process.myUid()) return null;
        return ps.reso.instaeclipse.utils.ipc.CompanionBridge.isTrustedCaller(context, callingUid)
                ? CommonUtils.IG_PACKAGE_NAME : null;
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
