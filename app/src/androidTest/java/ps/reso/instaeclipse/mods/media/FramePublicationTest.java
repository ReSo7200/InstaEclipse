package ps.reso.instaeclipse.mods.media;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import ps.reso.instaeclipse.utils.download.DownloadEngine;

import static org.junit.Assert.*;

/** Publishes generated pixels only. Queries use one unique test filename; preferences are read-only. */
@RunWith(AndroidJUnit4.class)
public class FramePublicationTest {
    @Test public void galleryPreservesPngBytesMimeAndDimensions() throws Exception {
        Assume.assumeTrue("MediaStore Downloads requires API 29", Build.VERSION.SDK_INT >= 29);
        Context context = context();
        String name = uniqueName();
        Uri created = null;
        try (PngFixture png = new PngFixture(context)) {
            try {
                DownloadPublisher.save(context, png.file, name, "image/png", null,
                        "", false, new DownloadEngine.Cancellation());
                created = findGallery(context, name);
                assertNotNull("Synthetic gallery output missing", created);
                assertPng(context, created, png.bytes);
            } finally {
                if (created == null) created = findGallery(context, name);
                if (created != null) assertEquals("Synthetic gallery cleanup failed", 1,
                        context.getContentResolver().delete(created, null, null));
            }
        }
    }

    @Test public void configuredSafPreservesPngBytesMimeAndDimensions() throws Exception {
        Context context = context();
        String tree = context.getSharedPreferences("instaeclipse_cache", Context.MODE_PRIVATE)
                .getString("downloaderCustomUri", "");
        Assume.assumeTrue("No configured SAF test destination", tree != null && !tree.isEmpty());
        Uri created = null;
        try (PngFixture png = new PngFixture(context)) {
            try {
                created = DownloadPublisher.saveToSaf(context, png.file, uniqueName(), "image/png",
                        tree, null, false, new DownloadEngine.Cancellation());
                assertNotNull("Synthetic SAF output missing", created);
                assertPng(context, created, png.bytes);
            } finally {
                if (created != null) assertTrue("Synthetic SAF cleanup failed",
                        DocumentsContract.deleteDocument(context.getContentResolver(), created));
            }
        }
    }

    @Test public void cancellationBeforeCopyCreatesNoGalleryItem() throws Exception {
        Assume.assumeTrue("MediaStore Downloads requires API 29", Build.VERSION.SDK_INT >= 29);
        Context context = context();
        String name = uniqueName();
        try (PngFixture png = new PngFixture(context)) {
            try {
                DownloadEngine.Cancellation cancellation = new DownloadEngine.Cancellation();
                cancellation.cancel();
                try {
                    DownloadPublisher.save(context, png.file, name, "image/png", null, "", false, cancellation);
                    fail("Cancelled publication succeeded");
                } catch (IOException expected) {
                    assertEquals("Download cancelled", expected.getMessage());
                }
                assertNull("Cancelled publication left an item", findGallery(context, name));
            } finally {
                Uri created = findGallery(context, name);
                if (created != null) assertEquals("Cancelled fixture cleanup failed", 1,
                        context.getContentResolver().delete(created, null, null));
            }
        }
    }

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static String uniqueName() { return "ie_frame_fixture_" + UUID.randomUUID() + ".png"; }

    private static Uri findGallery(Context context, String name) {
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try (Cursor cursor = context.getContentResolver().query(collection,
                new String[] {MediaStore.MediaColumns._ID}, MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                new String[] {name}, null)) {
            assertNotNull("Synthetic output query unavailable", cursor);
            if (!cursor.moveToFirst()) return null;
            long id = cursor.getLong(0);
            assertFalse("Duplicate synthetic output", cursor.moveToNext());
            return ContentUris.withAppendedId(collection, id);
        }
    }

    private static void assertPng(Context context, Uri uri, byte[] expected) throws IOException {
        assertEquals("image/png", context.getContentResolver().getType(uri));
        byte[] actual;
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertNotNull("Synthetic output unreadable", input);
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 65536) throw new IOException("Unexpected synthetic image size");
                output.write(buffer, 0, count);
            }
            actual = output.toByteArray();
        }
        assertArrayEquals("Published image bytes differ", expected, actual);
        Bitmap decoded = BitmapFactory.decodeByteArray(actual, 0, actual.length);
        assertNotNull("Published PNG cannot be decoded", decoded);
        try {
            assertEquals(3, decoded.getWidth());
            assertEquals(2, decoded.getHeight());
        } finally { decoded.recycle(); }
    }

    private static final class PngFixture implements AutoCloseable {
        final File file;
        final byte[] bytes;

        PngFixture(Context context) throws IOException {
            Bitmap bitmap = Bitmap.createBitmap(new int[] {
                    0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff, 0xff000000, 0x80ff00ff
            }, 3, 2, Bitmap.Config.ARGB_8888);
            try (ByteArrayOutputStream encoded = new ByteArrayOutputStream()) {
                assertTrue("Synthetic PNG encoding failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded));
                bytes = encoded.toByteArray();
            } finally { bitmap.recycle(); }
            file = File.createTempFile("ie-frame-fixture-", ".png", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
            catch (IOException failure) { file.delete(); throw failure; }
        }

        @Override public void close() {
            assertTrue("Synthetic cache cleanup failed", !file.exists() || file.delete());
        }
    }
}
