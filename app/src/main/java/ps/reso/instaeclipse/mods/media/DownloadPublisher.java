package ps.reso.instaeclipse.mods.media;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;

import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/** Publishes only engine-verified files. Failed/cancelled copies remove their own partial output. */
final class DownloadPublisher {
    private static final Set<String> MEDIA_ROOTS = new HashSet<>(Arrays.asList("Download", "Downloads",
            "Pictures", "DCIM", "Movies", "Music", "Ringtones", "Alarms", "Notifications", "Podcasts", "Audiobooks"));

    static void save(Context ctx, File source, String filename, String mime, String owner,
            DownloadEngine.Cancellation cancellation) throws Exception {
        save(ctx, source, filename, mime, owner, FeatureFlags.downloaderCustomPath,
                FeatureFlags.downloaderUsernameFolder, cancellation);
    }

    /** Companion settings come from its cache; loading a host hook class is never required. */
    static void saveFrame(Context ctx, File source, String filename, String owner,
            DownloadEngine.Cancellation cancellation) throws Exception {
        SharedPreferences settings = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(ctx);
        String uri = settings.getString("downloaderCustomUri", "");
        boolean usernameFolder = settings.getBoolean("downloaderUsernameFolder", false);
        if (!uri.isEmpty()) {
            saveToSaf(ctx, source, filename, "image/png", uri, owner, usernameFolder, cancellation);
            return;
        }
        save(ctx, source, filename, "image/png", owner,
                settings.getString("downloaderCustomPath", ""), usernameFolder, cancellation);
    }

    static void save(Context ctx, File source, String filename, String mime, String owner,
            String customPath, boolean usernameFolder, DownloadEngine.Cancellation cancellation) throws Exception {
        DownloadFiles.check(cancellation);
        if (!customPath.isEmpty() && !customPath.startsWith("content://")) {
            try {
                saveRaw(ctx, source, new File(customPath), filename, mime, owner, usernameFolder, cancellation);
                return;
            } catch (IOException failure) { DownloadFiles.check(cancellation); /* existing MediaStore path fallback */ }
        }
        if (Build.VERSION.SDK_INT < 29) {
            saveRaw(ctx, source, new File(Environment.getExternalStorageDirectory(), "InstaEclipse"),
                    filename, mime, owner, usernameFolder, cancellation);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                mediaStorePath(customPath, Environment.getExternalStorageDirectory().getAbsolutePath(),
                        DownloadFiles.segment(owner, 64), usernameFolder));
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Cannot create gallery item");
        boolean published = false;
        try {
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot write gallery item");
                DownloadFiles.copy(source, out, cancellation);
            }
            DownloadFiles.check(cancellation);
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (ctx.getContentResolver().update(uri, values, null, null) != 1)
                throw new IOException("Cannot publish gallery item");
            published = true;
        } finally {
            if (!published) ctx.getContentResolver().delete(uri, null, null);
        }
    }

    private static void saveRaw(Context ctx, File source, File dir, String filename, String mime,
            String owner, boolean usernameFolder, DownloadEngine.Cancellation cancellation) throws IOException {
        if (usernameFolder && owner != null && !owner.isEmpty())
            dir = new File(dir, DownloadFiles.segment(owner, 64));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create download folder");
        File temp = File.createTempFile(".ie_pending_", ".tmp", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                DownloadFiles.copy(source, out, cancellation);
                out.getFD().sync();
            }
            DownloadFiles.check(cancellation);
            // Reserve a new destination so a failed save never overwrites an existing download.
            File target = new File(dir, filename);
            int suffix = 0;
            while (!target.createNewFile()) {
                if (++suffix > 1000) throw new IOException("Too many duplicate filenames");
                int dot = filename.lastIndexOf('.');
                target = new File(dir, (dot < 0 ? filename : filename.substring(0, dot)) + "_" + suffix
                        + (dot < 0 ? "" : filename.substring(dot)));
            }
            if (!temp.renameTo(target)) {
                target.delete();
                throw new IOException("Cannot publish downloaded file");
            }
            MediaScannerConnection.scanFile(ctx, new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
        } finally { temp.delete(); }
    }

    static String mediaStorePath(String customPath, String externalRoot, String owner, boolean usernameFolder) {
        String base = "Download/InstaEclipse";
        if (!customPath.isEmpty() && !customPath.startsWith("content://")
                && customPath.startsWith(externalRoot + "/")) {
            String relative = customPath.substring(externalRoot.length() + 1);
            String top = relative.split("/")[0];
            base = MEDIA_ROOTS.contains(top) ? relative : "Download/" + relative;
        }
        if (usernameFolder && owner != null && !owner.isEmpty()) base += "/" + owner;
        return base;
    }

    static Uri saveToSaf(Context ctx, File source, String filename, String mime, String saveUri,
            String owner, boolean usernameFolder, DownloadEngine.Cancellation cancellation) throws Exception {
        DownloadFiles.check(cancellation);
        DocumentFile dir = DocumentFile.fromTreeUri(ctx, Uri.parse(saveUri));
        if (dir == null || !dir.canWrite())
            throw new IOException("SAF folder not writable — was the permission revoked?");
        if (usernameFolder && owner != null && !owner.isEmpty()) {
            String folder = DownloadFiles.segment(owner, 64);
            DocumentFile sub = dir.findFile(folder);
            if (sub == null || !sub.isDirectory()) sub = dir.createDirectory(folder);
            if (sub == null) throw new IOException("Cannot create username sub-folder");
            dir = sub;
        }
        DocumentFile file = dir.createFile(mime, filename);
        if (file == null) throw new IOException("SAF createFile returned null");
        boolean complete = false;
        try {
            try (OutputStream out = ctx.getContentResolver().openOutputStream(file.getUri())) {
                if (out == null) throw new IOException("SAF openOutputStream returned null");
                DownloadFiles.copy(source, out, cancellation);
            }
            DownloadFiles.check(cancellation);
            long size = file.length();
            if (size > 0 && size != source.length()) throw new IOException("Incomplete SAF copy");
            complete = true;
            return file.getUri();
        } finally {
            if (!complete) file.delete();
        }
    }

}
