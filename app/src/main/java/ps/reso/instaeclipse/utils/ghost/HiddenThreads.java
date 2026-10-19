package ps.reso.instaeclipse.utils.ghost;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

import ps.reso.instaeclipse.utils.core.AtomicFiles;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Persistent set of DM thread ids the user has chosen to hide from the inbox (Hide Specific Chats).
 * Stored as JSON in Instagram's own filesDir, so it survives restarts. A captured title is kept
 * alongside each id purely so the unhide manager can show a human-readable label. Runs in the
 * Instagram process (same as the inbox filter + the hide button), so no cross-process channel.
 *
 * Account-scoped v2 document. The old unscoped document is read as unassigned legacy data;
 * it is never attributed to whichever account happens to be open during migration.
 */
public class HiddenThreads {

    private static HiddenThreadStore store;

    private static synchronized HiddenThreadStore store() {
        if (store != null) return store;
        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) return null; // Application.attach may not have completed yet; retry later.
        File folder = ctx.getFilesDir();
        store = new HiddenThreadStore(new HiddenThreadStore.Storage() {
            private final File target = new File(folder, "ie_hidden_threads_v2.json");
            private String readFile(File file) throws IOException {
                if (!file.exists()) return null;
                if (file.length() > HiddenThreadStore.MAX_BYTES) throw new IOException("State too large");
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (bytes.length > HiddenThreadStore.MAX_BYTES) throw new IOException("State too large");
                return new String(bytes, StandardCharsets.UTF_8);
            }
            public String read() throws IOException { return readFile(target); }
            public String readLegacy() throws IOException {
                return readFile(new File(folder, "ie_hidden_threads.json"));
            }
            public void write(String document) throws IOException { AtomicFiles.write(target, document); }
        });
        return store;
    }

    public static boolean isHidden(String account, String thread) {
        HiddenThreadStore current = store();
        return current != null && current.isHidden(account, thread);
    }

    public static HiddenThreadStore.Result setHidden(String account, String thread, String title, boolean hidden) {
        HiddenThreadStore current = store();
        if (current == null) return new HiddenThreadStore.Result(false, false, "Application storage is unavailable.");
        HiddenThreadStore.Result result = current.setHidden(account, thread, title, hidden);
        if (!result.success) ModuleLog.line("(IE|HiddenThreads) persistence failed; original state preserved");
        return result;
    }

    public static HiddenThreadStore.Result unhide(String account, String thread) {
        return setHidden(account, thread, "", false);
    }

    /** Snapshot of this account's hidden threads for the unhide manager. */
    public static Map<String, String> all(String account) {
        HiddenThreadStore current = store();
        return current == null ? Collections.emptyMap() : current.all(account);
    }

    public static Map<String, String> legacyEntries() {
        HiddenThreadStore current = store();
        return current == null ? Collections.emptyMap() : current.legacyEntries();
    }

    public static String lastError() {
        HiddenThreadStore current = store();
        return current == null ? "Application storage is unavailable." : current.lastError();
    }
}
