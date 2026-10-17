package ps.reso.instaeclipse.utils.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Crash-safe file replacement for the small JSON stores (hidden threads, thread names, unsent
 * log). Content is written to a temp file, synced, then renamed over the target, so a crash
 * mid-write leaves the previous version intact instead of a truncated file that loses
 * everything on the next load. Async writes run in order on one background thread.
 */
public final class AtomicFiles {

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InstaEclipse-Store");
        t.setDaemon(true);
        return t;
    });

    private AtomicFiles() {}

    public static void write(File target, String content) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        try {
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename failed: " + target.getName(), e);
        }
    }

    /** Writes off the caller's thread; writes to the same file keep their order. */
    public static void writeAsync(File target, String content) {
        WRITER.execute(() -> {
            try {
                write(target, content);
            } catch (Throwable t) {
                ModuleLog.line("(IE|Store) save " + target.getName() + " failed: " + t.getMessage());
            }
        });
    }
}
