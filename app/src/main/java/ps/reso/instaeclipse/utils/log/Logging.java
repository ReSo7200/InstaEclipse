package ps.reso.instaeclipse.utils.log;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent, size-bounded ring buffer of log lines, used to power the in-app log viewer
 * (LoggingFragment). Runs independently in whichever process calls init() — Instagram's
 * hooked process and the companion app each keep their own buffer/file, since a hooked
 * process can't directly read another process's memory.
 *
 * In Instagram's process the buffer is additionally mirrored into a file owned by the companion
 * app (see {@link LogMirrorProvider} / {@link #enableMirror}), so the companion's Logs tab can
 * show Instagram's log even when Instagram is backgrounded or no longer running.
 *
 * Consecutive identical lines are collapsed into a single "(×N)" entry to avoid the buffer
 * filling up with noise from a hook that logs the same thing on every call.
 */
public final class Logging {

    private static final long FLUSH_DELAY_MS = 750;
    // Broadcast extras ride the Binder transaction buffer (~1MB total, shared across all
    // in-flight transactions in the process), and Java strings are UTF-16 (2 bytes/char), so
    // this must stay well under half a million chars or the reply broadcast triggers
    // TransactionTooLargeException -> the receiving process gets killed with
    // CannotDeliverBroadcastException instead of ever seeing the intent.
    private static final int IPC_MAX_CHARS = 150000;
    private static final long MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LINES = 10000;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX_LINES + 16);
    private static final ThreadLocal<SimpleDateFormat> TS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private static boolean initialized;
    private static File logFile;
    private static HandlerThread ioThread;
    private static Handler ioHandler;
    private static boolean dirtyFile;
    private static String lastBody;
    private static String lastBodyTime;
    private static int lastBodyRepeat = 1;

    /** Lines logged before init() (e.g. DexKit failures before Application.attach). */
    private static final ArrayList<String> PRE_INIT = new ArrayList<>();
    private static final int PRE_INIT_MAX = 500;

    // Mirror into the companion app's storage (Instagram process only).
    private static final Uri MIRROR_URI = Uri.parse("content://ps.reso.instaeclipse.logmirror/log");
    private static Context mirrorContext;
    private static ParcelFileDescriptor mirrorPfd;
    // Mirror and log-file state is only touched on the IO thread; LOCK guards LINES alone, so
    // logging from the main thread never waits for disk I/O.
    private static volatile FileOutputStream mirrorOut;
    private static long mirrorBytes;

    private static final Runnable REWRITE_RUNNABLE = Logging::rewriteFileRunnable;

    private Logging() {}

    public static void init(Context context) {
        init(context, "instaeclipse_logging.log");
    }

    public static void init(Context context, String filename) {
        synchronized (LOCK) {
            if (initialized) return;
            logFile = new File(context.getFilesDir(), filename);
            ensureIoThread();
            loadFromFileLocked();
            initialized = true;
            if (!PRE_INIT.isEmpty()) {
                for (String e : PRE_INIT) {
                    trimLinesIfNeeded();
                    LINES.addLast(e);
                }
                PRE_INIT.clear();
                dirtyFile = true;
                scheduleFlushLocked();
            }
        }
    }

    /**
     * Instagram process only: open (asynchronously) an append-only FD on the companion's mirror
     * file and keep every future line flowing into it. Safe to call repeatedly; failures (e.g.
     * companion not installed) are logged to logcat and retried on the next {@link #refreshMirror}.
     */
    public static void enableMirror(Context context) {
        refreshMirror(context, null);
    }

    /**
     * Re-opens the mirror FD (handles the companion having cleared its data since Instagram
     * started) and rewrites it from the in-memory buffer, then runs {@code after} on the IO thread.
     */
    public static void refreshMirror(Context context, Runnable after) {
        if (context == null) {
            if (after != null) after.run();
            return;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Handler h;
        synchronized (LOCK) {
            mirrorContext = app;
            ensureIoThread();
            h = ioHandler;
        }
        h.post(() -> {
            ParcelFileDescriptor fresh = null;
            try {
                ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(MIRROR_URI, "wa");
                if (pfd != null) {
                    // dup() + close(): the PFD from ContentResolver pins a stable provider
                    // reference (Instagram would be killed if the companion process dies). A dup
                    // is a plain kernel FD with no such link.
                    fresh = pfd.dup();
                    try { pfd.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                Log.w("InstaEclipse", "[Logging] log mirror unavailable: " + t);
            }
            closeMirror();
            if (fresh != null) {
                mirrorPfd = fresh;
                mirrorOut = new FileOutputStream(fresh.getFileDescriptor());
                rewriteMirror(snapshotLines());
            }
            if (after != null) {
                try { after.run(); } catch (Throwable ignored) {}
            }
        });
    }

    /** True while Instagram's buffer is being mirrored into the companion's storage. */
    public static boolean isMirrorActive() {
        return mirrorOut != null;
    }

    /** Copy of the buffered lines, taken under LOCK so the IO thread can write without it. */
    private static java.util.List<String> snapshotLines() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    private static void closeMirror() {
        if (mirrorPfd != null) {
            try { mirrorPfd.close(); } catch (Throwable ignored) {}
        }
        mirrorPfd = null;
        mirrorOut = null;
        mirrorBytes = 0;
    }

    private static void writeMirror(byte[] data) {
        if (mirrorOut == null) return;
        try {
            mirrorOut.write(data);
            mirrorBytes += data.length;
        } catch (Throwable t) {
            // EBADF/EPIPE etc.: drop it; the companion's next request re-attaches.
            closeMirror();
        }
    }

    private static void rewriteMirror(java.util.List<String> lines) {
        if (mirrorPfd == null) return;
        try {
            Os.ftruncate(mirrorPfd.getFileDescriptor(), 0);
        } catch (Throwable t) {
            closeMirror();
            return;
        }
        mirrorBytes = 0;
        StringBuilder sb = new StringBuilder(lines.size() * 96);
        for (String s : lines) sb.append(s).append('\n');
        if (sb.length() > 0) writeMirror(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void ensureIoThread() {
        if (ioThread != null) return;
        ioThread = new HandlerThread("InstaEclipse-Logging-IO");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
    }

    private static void loadFromFileLocked() {
        if (logFile == null || !logFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                trimLinesIfNeeded();
                LINES.addLast(line);
            }
        } catch (Throwable ignored) {}
    }

    public static void append(String line) {
        if (line == null || line.isEmpty()) return;
        String time = TS.get().format(new Date());
        synchronized (LOCK) {
            if (!initialized) {
                if (PRE_INIT.size() < PRE_INIT_MAX) PRE_INIT.add(time + " " + line);
                return;
            }
            if (line.equals(lastBody) && !LINES.isEmpty()) {
                lastBodyRepeat++;
                LINES.pollLast();
                LINES.addLast(lastBodyTime + " " + line + " (×" + lastBodyRepeat + ")");
                dirtyFile = true;
                scheduleFlushLocked();
                return;
            }
            String entry = time + " " + line;
            boolean hadDirty = dirtyFile;
            dirtyFile = false;
            lastBody = line;
            lastBodyRepeat = 1;
            lastBodyTime = time;
            trimLinesIfNeeded();
            LINES.addLast(entry);
            postIoWrite(hadDirty, entry);
        }
    }

    private static void trimLinesIfNeeded() {
        while (LINES.size() >= MAX_LINES) LINES.pollFirst();
    }

    private static void scheduleFlushLocked() {
        if (ioHandler == null) return;
        ioHandler.removeCallbacks(REWRITE_RUNNABLE);
        ioHandler.postDelayed(REWRITE_RUNNABLE, FLUSH_DELAY_MS);
    }

    private static void rewriteFileRunnable() {
        java.util.List<String> lines;
        synchronized (LOCK) {
            dirtyFile = false;
            lines = new ArrayList<>(LINES);
        }
        rewriteFile(lines);
    }

    private static void postIoWrite(boolean rewriteFirst, String entry) {
        if (ioHandler == null) return;
        ioHandler.post(() -> {
            if (rewriteFirst) rewriteFile(snapshotLines());
            else appendLineToFile(entry);
        });
    }

    private static void appendLineToFile(String entry) {
        if (mirrorOut != null) {
            if (mirrorBytes > MAX_FILE_BYTES) rewriteMirror(snapshotLines());
            else writeMirror((entry + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (logFile == null) return;
        try {
            if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                rewriteLocalFile(snapshotLines());
                return;
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
                bw.write(entry);
                bw.newLine();
            }
        } catch (Throwable ignored) {}
    }

    private static void rewriteFile(java.util.List<String> lines) {
        rewriteMirror(lines);
        rewriteLocalFile(lines);
    }

    private static void rewriteLocalFile(java.util.List<String> lines) {
        if (logFile == null) return;
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, false))) {
            for (String s : lines) {
                bw.write(s);
                bw.newLine();
            }
        } catch (Throwable ignored) {}
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            lastBody = null;
            lastBodyRepeat = 1;
            lastBodyTime = null;
            dirtyFile = false;
        }
        if (ioHandler != null) {
            ioHandler.removeCallbacks(REWRITE_RUNNABLE);
            ioHandler.post(() -> {
                rewriteMirror(new ArrayList<>()); // empty -> truncates the mirror
                if (logFile != null && logFile.exists()) {
                    try { logFile.delete(); } catch (Throwable ignored) {}
                }
            });
        }
    }

    public static String getSnapshot() {
        synchronized (LOCK) {
            return buildSnapshot(false);
        }
    }

    public static String getSnapshotForIpc() {
        synchronized (LOCK) {
            return buildSnapshot(true);
        }
    }

    private static String buildSnapshot(boolean forIpc) {
        if (LINES.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(LINES.size() * 96);
        for (String s : LINES) sb.append(s).append('\n');
        String snap = sb.toString();
        if (!forIpc || snap.length() <= IPC_MAX_CHARS) return snap;
        return "(truncated — older lines omitted)\n\n" + snap.substring(snap.length() - IPC_MAX_CHARS + 40);
    }
}
