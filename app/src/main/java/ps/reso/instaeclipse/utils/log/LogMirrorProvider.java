package ps.reso.instaeclipse.utils.log;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

import ps.reso.instaeclipse.utils.core.CommonUtils;

/**
 * Lives in the companion app. Hands a hooked Instagram process an append-mode file descriptor
 * on a file inside the companion's own storage ({@code files/ig_logs/<package>.log}).
 *
 * <p>Instagram's {@link Logging} keeps that descriptor open and writes every log line into it,
 * so the companion's Logs tab can read Instagram's log straight from disk even when Instagram
 * is backgrounded, frozen or dead — no live request/reply round-trip needed.
 *
 * <p>Only the supported Instagram packages may open it, and only for writing.
 */
public class LogMirrorProvider extends ContentProvider {

    public static final String AUTHORITY = CommonUtils.MY_PACKAGE_NAME + ".logmirror";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/log");
    private static final String DIR = "ig_logs";

    /** Directory (in the companion's private storage) holding one mirrored log per package. */
    public static File mirrorDir(Context context) {
        return new File(context.getFilesDir(), DIR);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("no context");
        if (!mode.contains("w")) throw new SecurityException("write-only");

        int uid = Binder.getCallingUid();
        String pkg = null;
        if (uid == Process.myUid()) {
            pkg = CommonUtils.IG_PACKAGE_NAME; // local testing only
        } else {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null) {
                for (String p : pkgs) {
                    if (CommonUtils.SUPPORTED_PACKAGES.contains(p)) {
                        pkg = p;
                        break;
                    }
                }
            }
        }
        if (pkg == null) throw new SecurityException("caller is not a supported Instagram package");

        File dir = mirrorDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("cannot create " + dir);
        File f = new File(dir, pkg + ".log");
        // Always append-only for the caller; it truncates with ftruncate() when it rewrites.
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_APPEND);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "text/plain";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
