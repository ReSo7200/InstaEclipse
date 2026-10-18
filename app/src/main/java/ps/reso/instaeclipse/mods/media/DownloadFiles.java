package ps.reso.instaeclipse.mods.media;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import ps.reso.instaeclipse.utils.download.DownloadEngine;

/** Shared file operations, with no host/Xposed dependency. */
final class DownloadFiles {
    static String segment(String value, int max) {
        String safe = value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "unknown";
        return safe.substring(0, Math.min(max, safe.length()));
    }

    static void copy(File source, OutputStream out, DownloadEngine.Cancellation cancellation) throws IOException {
        long count = 0;
        try (FileInputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                check(cancellation);
                out.write(buffer, 0, read);
                count += read;
            }
        }
        out.flush();
        check(cancellation);
        if (count != source.length() || count == 0) throw new IOException("Incomplete file copy");
    }

    static void check(DownloadEngine.Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new IOException("Download cancelled");
    }
}
