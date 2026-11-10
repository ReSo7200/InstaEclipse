package ps.reso.instaeclipse.patcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Installs the patched Instagram APK set through a {@link PackageInstaller} session. A single base
 * APK or a base-plus-splits set are both handled by writing every APK into one session. The system
 * shows its own install prompt; the user confirms it.
 */
public final class PatchInstaller {

    public static final String ACTION_STATUS = "ps.reso.instaeclipse.patcher.INSTALL_STATUS";

    private PatchInstaller() {
    }

    public static void install(Context ctx, List<File> apks, PatchLogger log) throws IOException {
        if (apks == null || apks.isEmpty()) throw new IOException("Nothing to install");
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            byte[] buffer = new byte[65536];
            for (File apk : apks) {
                log.log("Staging " + apk.getName());
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite(apk.getName(), 0, apk.length())) {
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    session.fsync(out);
                }
            }

            Intent intent = new Intent(ctx, PatchInstallReceiver.class).setAction(ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(ctx.getApplicationContext(), sessionId, intent, flags);

            log.log("Opening the system installer");
            session.commit(pending.getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }
}
