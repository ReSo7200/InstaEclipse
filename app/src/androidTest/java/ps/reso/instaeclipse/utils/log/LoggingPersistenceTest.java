package ps.reso.instaeclipse.utils.log;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.AtomicFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Only synthetic status messages in a fresh test-cache directory; never opens production logs. */
@RunWith(AndroidJUnit4.class)
public class LoggingPersistenceTest {
    private File folder;
    private Context context;
    private CountDownLatch unblockIo;
    private CountDownLatch unblockMirror;

    @Before public void prepare() throws Exception {
        resetLogger();
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        folder = Files.createTempDirectory(base.getCacheDir().toPath(), "ie_log_fixture_").toFile();
        context = new ContextWrapper(base) { @Override public File getFilesDir() { return folder; } };
    }
    @After public void cleanup() throws Exception {
        if (unblockIo != null) unblockIo.countDown();
        if (unblockMirror != null) unblockMirror.countDown();
        resetLogger();
        // Every child belongs to this test's unique directory; no recursive or shared cleanup.
        File[] children = folder.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) {
                File[] logs = child.listFiles();
                if (logs != null) for (File log : logs) assertTrue(log.delete());
            }
            assertTrue(child.delete());
        }
        assertTrue(folder.delete());
    }
    private File file() { return new File(folder, "fixture.log"); }
    private String disk() throws Exception { return new String(Files.readAllBytes(file().toPath()), StandardCharsets.UTF_8); }

    @Test public void coldStartKeepsCompletionBeforeAnyActivityInitialization() throws Exception {
        Logging.append("fixture download completed: 1080x1920 audio present");
        assertTrue(Logging.getSnapshot().contains("fixture download completed"));
        assertFalse("A context is still required for persistence", Logging.flush());
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        assertEquals(Logging.getSnapshot(), disk());
    }

    @Test public void coldInitRestoresHistoryThenPendingErrorsAndSurvivesReload() throws Exception {
        Files.write(file().toPath(), "earlier synthetic status\n".getBytes(StandardCharsets.UTF_8));
        Logging.append("fixture download failed: cancelled");
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        String saved = disk();
        assertTrue(saved.startsWith("earlier synthetic status\n"));
        assertTrue(saved.contains("fixture download failed: cancelled"));
        resetLogger();
        Logging.init(context, "fixture.log");
        Logging.init(context, "ignored-second-init.log");
        assertEquals(saved, Logging.getSnapshot());
        assertFalse(new File(folder, "ignored-second-init.log").exists());
    }

    @Test public void flushMakesQueuedRepeatedStatusesDurableExactlyOnce() throws Exception {
        Logging.init(context, "fixture.log");
        pauseIo();
        Logging.append("fixture downloading");
        Logging.append("fixture downloading");
        Logging.append("fixture verified");
        Logging.append("fixture completed");
        String expected = Logging.getSnapshot();
        assertTrue(expected.contains("fixture downloading (×2)"));
        FutureTask<Boolean> flush = new FutureTask<>(Logging::flush);
        new Thread(flush, "fixture-flush").start();
        assertFalse("The flush must use the IO queue", flush.isDone());
        resumeAndDrainIo();
        assertTrue(flush.get(5, TimeUnit.SECONDS));
        assertEquals("Queued writes must not append an already snapshotted entry", expected, disk());
    }

    @Test public void clearCannotResurrectStatusesFromQueuedWrites() throws Exception {
        Logging.init(context, "fixture.log");
        pauseIo();
        Logging.append("fixture before clear");
        Logging.clear();
        resumeAndDrainIo();
        assertTrue(Logging.flush());
        assertEquals("", Logging.getSnapshot());
        assertEquals("", disk());
    }

    @Test public void regularBurstBatchesWithoutMovingTheFirstDeadline() throws Exception {
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        Handler original = (Handler) value("ioHandler");
        class RecordingHandler extends Handler {
            int schedules;
            long deadline;
            RecordingHandler() { super(original.getLooper()); }
            @Override public boolean sendMessageAtTime(Message message, long when) {
                schedules++; deadline = when; return true;
            }
        }
        RecordingHandler recording = new RecordingHandler();
        set("ioHandler", recording);
        try {
            long before = SystemClock.uptimeMillis();
            Logging.append("fixture first");
            long deadline = recording.deadline;
            assertTrue("Regular status writes should be batched", deadline >= before + 750);
            for (int i = 0; i < 100; i++) Logging.append("fixture burst " + i);
            assertEquals(1, recording.schedules);
            assertEquals("A continuing burst must not postpone persistence", deadline, recording.deadline);
        } finally {
            set("ioHandler", original);
        }
        assertTrue(Logging.flush());
        assertEquals(Logging.getSnapshot(), disk());
    }

    @Test public void preInitBufferAndUtf8FileStayWithinByteLimit() throws Exception {
        StringBuilder multibyte = new StringBuilder();
        for (int i = 0; i < 1200; i++) multibyte.append('€');
        for (int i = 0; i < 1500; i++) Logging.append("fixture event " + i + " " + multibyte);
        assertTrue(Logging.getSnapshot().getBytes(StandardCharsets.UTF_8).length <= 4 * 1024 * 1024);
        assertFalse(Logging.getSnapshot().contains("fixture event 0 "));
        assertTrue(Logging.getSnapshot().contains("fixture event 1499 "));
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        assertTrue(file().length() <= 4 * 1024 * 1024);
        assertEquals(Logging.getSnapshot(), disk());
    }

    @Test public void failedWriteRetainsPendingStatusForExplicitRetry() throws Exception {
        File blocked = new File(folder, "blocked");
        Files.write(blocked.toPath(), new byte[] {1});
        Context unavailable = new ContextWrapper(context) { @Override public File getFilesDir() { return blocked; } };
        Logging.init(unavailable, "fixture.log");
        Logging.append("fixture completion waiting for storage");
        assertFalse(Logging.flush());
        assertTrue(Logging.getSnapshot().contains("fixture completion waiting for storage"));
        assertTrue(blocked.delete());
        assertTrue(blocked.mkdir());
        assertTrue(Logging.flush());
        String saved = new String(Files.readAllBytes(new File(blocked, "fixture.log").toPath()), StandardCharsets.UTF_8);
        assertEquals(Logging.getSnapshot(), saved);
    }

    @Test public void oversizedLineTruncationKeepsUnicodeAndReloadConsistent() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4095; i++) text.append('x');
        text.appendCodePoint(0x1F600).append("fixture tail");
        Logging.append(text.toString());
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        String saved = disk();
        assertTrue(saved.contains("[truncated]"));
        assertEquals(Logging.getSnapshot(), saved);
        resetLogger();
        Logging.init(context, "fixture.log");
        assertEquals(saved, Logging.getSnapshot());
    }

    @Test public void failedAtomicRenameDoesNotAcknowledgeOrDropPendingSnapshot() throws Exception {
        // Android 11+ writes .new then renames at finishWrite. Older implementations move the
        // original name at startWrite, so a directory does not isolate this commit failure.
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 30);
        assertTrue(file().mkdir());
        File blocker = new File(file(), "synthetic-blocker");
        Files.write(blocker.toPath(), new byte[] {1});
        Logging.init(context, "fixture.log");
        Logging.append("fixture completion waiting for atomic commit");
        assertFalse("A failed final rename must not be reported as durable", Logging.flush());
        assertTrue(file().isDirectory());
        assertTrue(blocker.isFile());
        assertTrue((Boolean) value("dirtyFile"));
        String expected = Logging.getSnapshot();
        assertTrue(expected.contains("waiting for atomic commit"));
        assertTrue(blocker.delete());
        assertTrue(file().delete());
        assertTrue("The same pending revision must remain retryable", Logging.flush());
        assertEquals(expected, disk());
        assertFalse((Boolean) value("dirtyFile"));
    }

    @Test public void clearBeforeColdInitDoesNotRestoreClearedHistory() throws Exception {
        Files.write(file().toPath(), "earlier synthetic status\n".getBytes(StandardCharsets.UTF_8));
        Logging.append("fixture before clear");
        Logging.clear();
        Logging.append("fixture after clear");
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        assertFalse(disk().contains("earlier"));
        assertFalse(disk().contains("before clear"));
        assertTrue(disk().contains("after clear"));
    }

    @Test public void interruptedAtomicWriteRecoversTheLastCompleteSnapshot() throws Exception {
        Files.write(file().toPath(), "previous complete snapshot\n".getBytes(StandardCharsets.UTF_8));
        AtomicFile atomic = new AtomicFile(file());
        FileOutputStream interrupted = atomic.startWrite();
        interrupted.write("partial snapshot".getBytes(StandardCharsets.UTF_8));
        interrupted.close(); // model process termination before finishWrite, using synthetic data only
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        assertEquals("previous complete snapshot\n", disk());
    }

    @Test public void mirroredSnapshotsAndClearPreserveUpstreamFdBehavior() throws Exception {
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        File mirror = new File(folder, "mirror.log");
        attachMirror(mirror, null, null);
        Logging.append("fixture mirrored");
        assertTrue(Logging.flush());
        assertEquals(disk(), read(mirror));
        Logging.append("fixture mirrored");
        assertTrue(Logging.flush());
        assertEquals(disk(), read(mirror));
        assertTrue(read(mirror).contains("(×2)"));
        Logging.clear();
        assertTrue(Logging.flush());
        assertEquals("", disk());
        assertEquals("", read(mirror));
    }

    @Test public void blockedMirrorWriteDoesNotHoldBufferLockOrLoseNewerRevision() throws Exception {
        Logging.init(context, "fixture.log");
        assertTrue(Logging.flush());
        CountDownLatch enteredMirror = new CountDownLatch(1);
        unblockMirror = new CountDownLatch(1);
        File mirror = new File(folder, "mirror.log");
        attachMirror(mirror, enteredMirror, unblockMirror);
        Logging.append("fixture snapshot in flight");
        FutureTask<Boolean> flush = new FutureTask<>(Logging::flush);
        new Thread(flush, "fixture-flush").start();
        assertTrue(enteredMirror.await(5, TimeUnit.SECONDS));
        FutureTask<String> append = new FutureTask<>(() -> {
            Logging.append("fixture arrived during IO");
            return Logging.getSnapshot();
        });
        new Thread(append, "fixture-append").start();
        assertTrue("Buffer callers must not wait for file/mirror writes",
                append.get(1, TimeUnit.SECONDS).contains("arrived during IO"));
        unblockMirror.countDown();
        assertTrue(flush.get(5, TimeUnit.SECONDS));
        assertTrue("The older snapshot must not mark the newer revision durable", Logging.flush());
        assertEquals(Logging.getSnapshot(), disk());
        assertEquals(disk(), read(mirror));
    }

    private String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private void attachMirror(File file, CountDownLatch entered, CountDownLatch release) throws Exception {
        FutureTask<Void> attach = new FutureTask<>(() -> {
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_APPEND);
            FileOutputStream stream = new FileOutputStream(descriptor.getFileDescriptor()) {
                @Override public void write(byte[] bytes) throws java.io.IOException {
                    if (entered != null) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("fixture interrupted");
                        }
                    }
                    super.write(bytes);
                }
            };
            set("mirrorPfd", descriptor);
            set("mirrorOut", stream);
            return null;
        });
        assertTrue(((Handler) value("ioHandler")).post(attach));
        attach.get(5, TimeUnit.SECONDS);
    }

    private void pauseIo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        unblockIo = new CountDownLatch(1);
        Handler handler = (Handler) value("ioHandler");
        handler.post(() -> {
            entered.countDown();
            try { unblockIo.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }
    private void resumeAndDrainIo() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        ((Handler) value("ioHandler")).post(drained::countDown);
        unblockIo.countDown();
        assertTrue(drained.await(5, TimeUnit.SECONDS));
    }
    private static Object value(String name) throws Exception {
        Field field = Logging.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static void set(String name, Object value) throws Exception {
        Field field = Logging.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static void resetLogger() throws Exception {
        Handler handler = (Handler) value("ioHandler");
        HandlerThread thread = (HandlerThread) value("ioThread");
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            FutureTask<Void> close = new FutureTask<>(() -> {
                Method method = Logging.class.getDeclaredMethod("closeMirror");
                method.setAccessible(true);
                method.invoke(null);
                return null;
            });
            assertTrue(handler.post(close));
            close.get(5, TimeUnit.SECONDS);
        }
        if (thread != null) {
            thread.quitSafely();
            thread.join(5000);
            assertFalse("Logger worker did not stop", thread.isAlive());
        }
        synchronized (value("LOCK")) {
            ((ArrayDeque<?>) value("LINES")).clear();
            set("initialized", false); set("logFile", null); set("ioHandler", null); set("ioThread", null);
            set("dirtyFile", false); set("flushScheduled", false); set("bufferedBytes", 0L); set("lastBody", null);
            set("clearedBeforeInit", false); set("revision", 0L); set("mirrorContext", null);
            set("lastBodyTime", null); set("lastBodyRepeat", 1);
        }
    }
}
