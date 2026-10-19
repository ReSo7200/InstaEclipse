package ps.reso.instaeclipse.mods.media;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import ps.reso.instaeclipse.utils.download.VideoFrameExtractor;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Private, broker-launched picker. URLs and source video never enter saved instance state. */
public final class VideoFramePickerActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DownloadEngine.Cancellation cancellation = new DownloadEngine.Cancellation();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    // The following three resources are accessed only by the serial worker.
    private FrameSessionCache.Session session;
    private DownloadEngine.Result downloaded;
    private VideoFrameExtractor extractor;
    private DownloadRequest request;
    private ImageView preview;
    private TextView status, position;
    private SeekBar slider;
    private Button save;
    private ProgressBar progress;
    private Bitmap shownBitmap;
    private long durationUs, selectedUs;
    private long lastProgressAt;
    private int exportWidth, exportHeight;
    private boolean ready, saving;
    private Runnable pendingPreview;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Logging.init(this, "instaeclipse_companion.log");
        buildUi();
        if (state != null) { failMessage(getString(R.string.ie_frame_reopen)); return; }
        try {
            String json = getIntent().getStringExtra(BrokerPayload.FRAME_REQUEST);
            request = BrokerPayload.request(BrokerPayload.activity(BrokerPayload.FRAME,
                    Collections.singletonMap(BrokerPayload.FRAME_REQUEST, json)).get(BrokerPayload.FRAME_REQUEST));
            getIntent().removeExtra(BrokerPayload.FRAME_REQUEST);
        } catch (RuntimeException invalid) { failMessage(getString(R.string.ie_frame_reopen)); return; }
        worker.execute(this::prepare);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(R.string.ie_save_video_frame); title.setTextSize(23);
        title.setAccessibilityHeading(true); root.addView(title);
        TextView help = new TextView(this);
        help.setText(R.string.ie_frame_help); help.setTextSize(14);
        help.setPadding(0, dp(10), 0, dp(12)); root.addView(help);
        preview = new ImageView(this);
        preview.setContentDescription(getString(R.string.ie_frame_preview));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        status = new TextView(this); status.setText(R.string.ie_frame_preparing);
        status.setPadding(0, dp(8), 0, dp(8)); root.addView(status);
        progress = new ProgressBar(this); root.addView(progress);
        position = new TextView(this); position.setGravity(Gravity.CENTER); root.addView(position);
        slider = new SeekBar(this); slider.setMax(10_000); slider.setEnabled(false);
        slider.setContentDescription(getString(R.string.ie_frame_position)); root.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || !ready || saving) return;
                long end = Math.max(0, durationUs - 1);
                selectedUs = end / 10_000 * value + end % 10_000 * value / 10_000;
                updatePosition(); schedulePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        save = new Button(this); save.setText(R.string.ie_frame_save); save.setEnabled(false);
        save.setOnClickListener(view -> saveSelected()); root.addView(save);
        Button cancel = new Button(this); cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> finish()); root.addView(cancel);
        setContentView(root);
    }

    private void prepare() {
        try {
            session = FrameSessionCache.create(getCacheDir());
            Context sourceContext = new ContextWrapper(getApplicationContext()) {
                @Override public File getCacheDir() { return session.directory(); }
            };
            downloaded = DownloadEngine.execute(sourceContext, request, cancellation, (phase, done, total) -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastProgressAt < 250 && done != total) return;
                lastProgressAt = now;
                main.post(() -> { if (alive()) status.setText(total > 0
                        ? getString(R.string.ie_frame_preparing_progress, Math.min(100, done * 100 / total))
                        : getString(R.string.ie_frame_preparing)); });
            });
            extractor = VideoFrameExtractor.open(downloaded.file, downloaded.metadata, cancellation);
            long duration = downloaded.metadata.durationUs;
            int rotation = Math.floorMod(downloaded.metadata.rotation, 360);
            int width = rotation == 90 || rotation == 270 ? downloaded.metadata.height : downloaded.metadata.width;
            int height = rotation == 90 || rotation == 270 ? downloaded.metadata.width : downloaded.metadata.height;
            main.post(() -> {
                if (!alive()) return;
                durationUs = duration; exportWidth = width; exportHeight = height;
                ready = true; slider.setEnabled(true);
                updatePosition(); schedulePreview();
            });
        } catch (Exception | OutOfMemoryError failure) { failed(failure); }
    }

    private void schedulePreview() {
        save.setEnabled(false); status.setText(R.string.ie_frame_loading);
        progress.setVisibility(View.VISIBLE);
        int current = generation.incrementAndGet();
        long time = selectedUs;
        if (pendingPreview != null) main.removeCallbacks(pendingPreview);
        pendingPreview = () -> {
            if (!alive()) return;
            worker.execute(() -> {
                if (closed.get() || current != generation.get()) return;
                try {
                    Bitmap bitmap = extractor.preview(time, cancellation);
                    main.post(() -> {
                        if (!alive() || current != generation.get()) { bitmap.recycle(); return; }
                        Bitmap old = shownBitmap;
                        shownBitmap = bitmap; preview.setImageBitmap(bitmap);
                        // Detach the previous drawable before releasing its pixel allocation.
                        if (old != null) old.recycle();
                        status.setText(getString(R.string.ie_frame_ready, exportWidth, exportHeight));
                        progress.setVisibility(View.GONE); save.setEnabled(true);
                    });
                } catch (Exception | OutOfMemoryError failure) {
                    if (current == generation.get()) failed(failure);
                }
            });
        };
        main.postDelayed(pendingPreview, 160);
    }

    private void saveSelected() {
        if (!ready || saving || !save.isEnabled()) return;
        saving = true; slider.setEnabled(false); save.setEnabled(false);
        status.setText(R.string.ie_frame_saving); progress.setVisibility(View.VISIBLE);
        long time = selectedUs;
        worker.execute(() -> {
            try {
                File png = File.createTempFile("frame_", ".png", session.directory());
                VideoFrameExtractor.SavedFrame saved = extractor.savePng(time, png, cancellation);
                String base = request.filename.replaceFirst("\\.[^.]*$", "");
                String filename = DownloadFiles.segment(base, 180) + "_frame_" + saved.timeUs / 1000 + "ms.png";
                DownloadPublisher.saveFrame(this, png, filename, request.owner, cancellation);
                boolean cleaned = cleanup();
                ModuleLog.line("(IE|Frame) PNG published width=" + saved.width + " height=" + saved.height
                        + " timeUs=" + saved.timeUs + " temporaryCleanup=" + cleaned);
                Logging.flush();
                main.post(() -> {
                    if (!alive()) return;
                    Toast.makeText(this, getString(cleaned ? R.string.ie_frame_saved : R.string.ie_frame_cleanup_pending,
                            saved.width, saved.height), Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception | OutOfMemoryError failure) { failed(failure); }
        });
    }

    private void failed(Throwable failure) {
        generation.incrementAndGet();
        boolean cleaned = cleanup();
        String code = failure instanceof VideoFrameExtractor.FrameFailure
                ? ((VideoFrameExtractor.FrameFailure) failure).code : "PREPARE_OR_SAVE";
        ModuleLog.line("(IE|Frame) failed cause=" + code + " temporaryCleanup=" + cleaned);
        Logging.flush();
        main.post(() -> {
            if (!alive()) return;
            String message = getString(cleaned ? R.string.ie_frame_failed : R.string.ie_frame_cleanup_pending);
            if (cleaned && failure instanceof VideoFrameExtractor.FrameFailure)
                message += "\n" + failure.getMessage(); // Helper-owned fixed text, never a platform exception.
            failMessage(message);
        });
    }

    /** Serial-worker only; called after cancellation, failure, save, and lifecycle destruction. */
    private boolean cleanup() {
        boolean cleaned = true;
        if (extractor != null) {
            try { extractor.close(); } catch (Exception failure) { cleaned = false; }
            extractor = null;
        }
        if (downloaded != null) { downloaded.close(); downloaded = null; }
        if (session != null) {
            try { session.close(); } catch (Exception failure) { cleaned = false; }
            session = null;
        }
        return cleaned;
    }

    private void failMessage(String message) {
        if (pendingPreview != null) main.removeCallbacks(pendingPreview);
        ready = false; slider.setEnabled(false); save.setEnabled(false);
        progress.setVisibility(View.GONE); status.setText(message);
    }
    private boolean alive() { return !closed.get() && !isFinishing() && !isDestroyed(); }
    private void updatePosition() { position.setText(getString(R.string.ie_frame_time, time(selectedUs), time(durationUs))); }
    private static String time(long us) {
        long ms = Math.max(0, us / 1000);
        return String.format(Locale.getDefault(), "%d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        // No request, URLs, media identifiers, filenames or file paths are saved here.
    }
    @Override protected void onDestroy() {
        closed.set(true); cancellation.cancel(); generation.incrementAndGet();
        if (pendingPreview != null) main.removeCallbacks(pendingPreview);
        preview.setImageDrawable(null);
        if (shownBitmap != null) { shownBitmap.recycle(); shownBitmap = null; }
        worker.execute(() -> { if (!cleanup()) ModuleLog.line("(IE|Frame) temporary cleanup pending"); Logging.flush(); });
        worker.shutdown();
        super.onDestroy();
    }
}
