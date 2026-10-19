package ps.reso.instaeclipse.utils.log;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;
import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final int MAX_LINE_CHARS = 4096;
    private static final int MAX_ENTRY_CHARS = MAX_LINE_CHARS + 128;

    private static final Object LOCK = new Object();
    private static final Object INIT_LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>(MAX_LINES + 16);
    private static final ThreadLocal<SimpleDateFormat> TS =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US));

    private static boolean initialized;
    private static File logFile;
    private static HandlerThread ioThread;
    private static Handler ioHandler;
    private static boolean dirtyFile;
    private static boolean flushScheduled;
    private static boolean clearedBeforeInit;
    private static long bufferedBytes;
    private static long revision;
    private static String lastBody;
    private static String lastBodyTime;
    private static int lastBodyRepeat = 1;

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
        synchronized (INIT_LOCK) {
            synchronized (LOCK) {
                if (initialized) return;
                logFile = new File(context.getFilesDir(), filename);
                ensureIoThread();
            }
            // Startup reads also stay outside the buffer lock. Concurrent startup events are
            // retained in the same bounded buffer, then placed after the persisted history.
            List<String> history = readHistory(logFile);
            synchronized (LOCK) {
                List<String> pending = new ArrayList<>(LINES);
                LINES.clear();
                bufferedBytes = 0;
                if (!clearedBeforeInit) for (String entry : history) addLineLocked(entry);
                for (String entry : pending) addLineLocked(entry);
                initialized = true;
                dirtyFile = true;
                revision++;
                scheduleFlushLocked(0);
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
                Log.w("InstaEclipse", "[Logging] log mirror unavailable (" + t.getClass().getSimpleName() + ")");
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

    private static List<String> readHistory(File file) {
        List<String> history = new ArrayList<>();
        if (file == null) return history;
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            long skip = Math.max(0, input.getChannel().size() - MAX_FILE_BYTES);
            input.getChannel().position(skip);
            BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (skip > 0) readBoundedLine(br); // discard a potentially partial UTF-8 line
            String line;
            ArrayDeque<String> tail = new ArrayDeque<>();
            while ((line = readBoundedLine(br)) != null) {
                if (tail.size() == MAX_LINES) tail.removeFirst();
                tail.addLast(line);
            }
            history.addAll(tail);
        } catch (Throwable ignored) {}
        return history;
    }

    private static String readBoundedLine(BufferedReader reader) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (line.length() < MAX_ENTRY_CHARS) line.append((char) c);
        }
        if (c == -1 && line.length() == 0) return null;
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') line.setLength(--end);
        if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) line.setLength(end - 1);
        return line.toString();
    }

    public static void append(String line) {
        if (line == null || line.isEmpty()) return;
        if (line.length() > MAX_LINE_CHARS) {
            int end = Character.isHighSurrogate(line.charAt(MAX_LINE_CHARS - 1)) ? MAX_LINE_CHARS - 1 : MAX_LINE_CHARS;
            line = line.substring(0, end) + " [truncated]";
        }
        String time = TS.get().format(new Date());
        synchronized (LOCK) {
            if (line.equals(lastBody) && !LINES.isEmpty()) {
                lastBodyRepeat++;
                bufferedBytes -= lineBytes(LINES.removeLast());
                addLineLocked(lastBodyTime + " " + line + " (×" + lastBodyRepeat + ")");
                dirtyFile = true;
                revision++;
                scheduleFlushLocked(FLUSH_DELAY_MS);
                return;
            }
            String entry = time + " " + line;
            dirtyFile = true;
            revision++;
            lastBody = line;
            lastBodyRepeat = 1;
            lastBodyTime = time;
            addLineLocked(entry);
            scheduleFlushLocked(FLUSH_DELAY_MS);
        }
    }

    private static long lineBytes(String entry) {
        return entry.getBytes(StandardCharsets.UTF_8).length + 1L;
    }

    private static void addLineLocked(String entry) {
        LINES.addLast(entry);
        bufferedBytes += lineBytes(entry);
        while (LINES.size() > MAX_LINES || bufferedBytes > MAX_FILE_BYTES)
            bufferedBytes -= lineBytes(LINES.removeFirst());
    }

    private static void scheduleFlushLocked(long delayMs) {
        if (!initialized || ioHandler == null) return;
        // Keep the first deadline even under a continuous stream of repeated/new events.
        if (flushScheduled && delayMs > 0) return;
        ioHandler.removeCallbacks(REWRITE_RUNNABLE);
        flushScheduled = ioHandler.postDelayed(REWRITE_RUNNABLE, delayMs);
    }

    private static void rewriteFileRunnable() {
        persistOnIoThread();
    }

    /** Only the IO thread writes either destination; no disk operation holds LOCK. */
    private static boolean persistOnIoThread() {
        List<String> lines;
        long capturedRevision;
        synchronized (LOCK) {
            flushScheduled = false;
            if (!initialized) return false;
            if (!dirtyFile) return true;
            lines = new ArrayList<>(LINES);
            capturedRevision = revision;
        }
        boolean saved = rewriteLocalFile(lines);
        rewriteMirror(lines);
        synchronized (LOCK) {
            if (saved && revision == capturedRevision) dirtyFile = false;
        }
        return saved;
    }

    /**
     * Persists buffered lines before a worker finishes. Call off the main thread: it waits up
     * to ten seconds for the IO queue. A false result leaves the buffer available for retry.
     * The local file is atomic; the existing companion mirror FD remains best-effort.
     */
    public static boolean flush() {
        Handler handler;
        synchronized (LOCK) {
            if (!initialized) return false;
            handler = ioHandler;
        }
        if (Looper.myLooper() == handler.getLooper()) return flushOnIoThread();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean saved = new AtomicBoolean();
        if (!handler.post(() -> {
            try { saved.set(flushOnIoThread()); }
            finally { done.countDown(); }
        })) return false;
        try { return done.await(10, TimeUnit.SECONDS) && saved.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean flushOnIoThread() {
        synchronized (LOCK) {
            ioHandler.removeCallbacks(REWRITE_RUNNABLE);
            flushScheduled = false;
        }
        return persistOnIoThread();
    }

    private static boolean rewriteLocalFile(List<String> lines) {
        if (logFile == null) return false;
        AtomicFile file = new AtomicFile(logFile);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            for (String s : lines) {
                bw.write(s);
                bw.write('\n');
            }
            bw.flush();
            // AtomicFile logs some sync/rename failures without throwing. Detect sync failure
            // here, then verify the committed bytes before acknowledging this revision.
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            return matchesCommittedSnapshot(file, lines);
        } catch (Throwable ignored) {
            if (output != null) file.failWrite(output);
            return false;
        }
    }

    /** Bounded read-back on the IO thread; never allocates a second full-file snapshot. */
    private static boolean matchesCommittedSnapshot(AtomicFile file, List<String> lines) throws java.io.IOException {
        try (BufferedInputStream input = new BufferedInputStream(file.openRead())) {
            long bytes = 0;
            for (String line : lines) {
                byte[] expected = (line + '\n').getBytes(StandardCharsets.UTF_8);
                bytes += expected.length;
                if (bytes > MAX_FILE_BYTES) return false;
                for (byte value : expected) if (input.read() != (value & 0xff)) return false;
            }
            return input.read() == -1;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
            bufferedBytes = 0;
            lastBody = null;
            lastBodyRepeat = 1;
            lastBodyTime = null;
            dirtyFile = true;
            revision++;
            if (!initialized) clearedBeforeInit = true;
            scheduleFlushLocked(0);
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
