package ps.reso.instaeclipse.mods.media;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owns only immediate files in dedicated frame sessions; never traverses links or subdirectories. */
public final class FrameSessionCache {
    private static final String DIRECTORY = "ie_frames";
    private static final String MARKER = ".ie_frame_session";
    private static final Pattern SESSION = Pattern.compile(
            "session-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_ROOT_ENTRIES = 256;
    private static final int MAX_SESSION_ENTRIES = 128;
    private static final Set<Path> ACTIVE = new HashSet<>();

    private FrameSessionCache() { }

    public static synchronized Session create(File cacheRoot) throws IOException {
        Path root = ownedRoot(cacheRoot, true);
        Path directory = root.resolve("session-" + UUID.randomUUID());
        Files.createDirectory(directory);
        try {
            Files.createFile(directory.resolve(MARKER));
        } catch (IOException failure) {
            Files.deleteIfExists(directory);
            throw failure;
        }
        ACTIVE.add(directory);
        return new Session(root, directory);
    }

    /** Best-effort startup cleanup. False means some entries were unsafe, over-limit or undeletable. */
    public static synchronized boolean cleanupOrphans(File cacheRoot) {
        try {
            Path root = ownedRoot(cacheRoot, false);
            if (root == null) return true;
            boolean complete = true;
            int inspected = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) {
                    if (++inspected > MAX_ROOT_ENTRIES) return false;
                    if (!SESSION.matcher(entry.getFileName().toString()).matches() || ACTIVE.contains(entry)) continue;
                    try {
                        if (!deleteSession(root, entry)) complete = false;
                    } catch (IOException unavailable) { complete = false; }
                }
            }
            return complete;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static Path ownedRoot(File cacheRoot, boolean create) throws IOException {
        if (cacheRoot == null) throw new IOException("Missing frame cache root");
        Path cache = cacheRoot.toPath().toRealPath();
        if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid frame cache root");
        Path root = cache.resolve(DIRECTORY);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null;
            Files.createDirectory(root);
        }
        if (!safeDirectory(root)) throw new IOException("Invalid frame session root");
        return root;
    }

    private static boolean safeDirectory(Path directory) throws IOException {
        return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                && directory.toRealPath().equals(directory);
    }

    private static boolean deleteSession(Path root, Path directory) throws IOException {
        // Recheck the complete boundary even for Session.close(): the directory may have changed.
        if (!directory.getParent().equals(root) || !SESSION.matcher(directory.getFileName().toString()).matches()
                || !safeDirectory(root)) return false;
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return true;
        if (!safeDirectory(directory)) return false;
        Path marker = directory.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 0) return false;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (files.size() >= MAX_SESSION_ENTRIES || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || !entry.toRealPath().getParent().equals(directory)) return false;
                files.add(entry);
            }
        }
        // Keep the marker until other files are gone so failed deletion can be retried next startup.
        for (Path file : files) if (!file.equals(marker)) Files.deleteIfExists(file);
        Files.delete(marker);
        Files.delete(directory);
        return true;
    }

    public static final class Session implements AutoCloseable {
        private final Path root;
        private final Path directory;
        private boolean closed;

        private Session(Path root, Path directory) { this.root = root; this.directory = directory; }

        public File directory() { return directory.toFile(); }

        @Override public void close() throws IOException {
            synchronized (FrameSessionCache.class) {
                if (closed) return;
                try {
                    if (!deleteSession(root, directory)) throw new IOException("Frame session cleanup incomplete");
                    closed = true;
                } finally { ACTIVE.remove(directory); }
            }
        }
    }
}
