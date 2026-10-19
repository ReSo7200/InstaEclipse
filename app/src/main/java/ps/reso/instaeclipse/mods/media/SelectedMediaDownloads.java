package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.app.AlertDialog;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.File;

import ps.reso.instaeclipse.utils.ipc.CompanionBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** All chooser callbacks close over these detached values, never Instagram models or Views. */
final class SelectedMediaDownloads {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    enum Choice { PHOTO, VIDEO, FRAME, AUDIO }

    static final class Captured {
        final SelectedMediaSnapshot media;
        final DownloadRequest video;
        final DownloadRequest originalVideo;
        final boolean original;
        final String type;
        final DownloadRequest.Options options;
        final String photoUrl;
        final String photoFilename;
        final String customPhotoFilename, originalPhotoFilename;
        final String audioFilename;
        final boolean audioAllowed;
        final List<Captured> children;

        Captured(SelectedMediaSnapshot media, String type, DownloadRequest.Options options) {
            this(media, type, options, false);
        }

        Captured(SelectedMediaSnapshot media, String type, DownloadRequest.Options options, boolean original) {
            this(media, type, options, original, FeatureFlags.offerAudioDownload);
        }

        private Captured(SelectedMediaSnapshot media, String type, DownloadRequest.Options options,
                         boolean original, boolean offerAudioDownload) {
            this.media = media;
            this.type = type;
            this.options = options;
            this.original = original;
            // Feed/Reel audio is opt-in; keep Story's existing available-format behavior.
            audioAllowed = "story".equals(type) || offerAudioDownload;
            String base = FeedVideoDownloadHook.buildFilename(DownloadFiles.segment(media.owner, 64), type,
                    DownloadFiles.segment(media.id, 112), false);
            base = base.substring(0, base.lastIndexOf('.'));
            audioFilename = base + "_audio.m4a";
            // Keep the complete Custom request for the frame picker, regardless of the toggle.
            video = media.video ? media.request(filename(base, true, false), options) : null;
            originalVideo = media.video && media.originalVideoUrl != null
                    ? new DownloadRequest(media.id, media.owner, filename(base, true, true), null,
                    List.of(new DownloadRequest.Rendition(media.originalVideoUrl, 0, 0, 0, "", 0)),
                    new DownloadRequest.Options(0, false, false, true), DownloadRequest.AudioExpectation.UNKNOWN) : null;
            photoUrl = original ? media.originalImageUrl : media.imageUrl;
            customPhotoFilename = filename(base, false, false);
            originalPhotoFilename = filename(base, false, true);
            photoFilename = original ? originalPhotoFilename : customPhotoFilename;
            ArrayList<Captured> items = new ArrayList<>();
            for (SelectedMediaSnapshot child : media.children)
                items.add(new Captured(child, type, options, original, audioAllowed));
            children = Collections.unmodifiableList(items);
        }

        private Captured(Captured previous, boolean original) {
            media = previous.media;
            type = previous.type;
            options = previous.options;
            this.original = original;
            video = previous.video;
            originalVideo = previous.originalVideo;
            customPhotoFilename = previous.customPhotoFilename;
            originalPhotoFilename = previous.originalPhotoFilename;
            audioFilename = previous.audioFilename;
            audioAllowed = previous.audioAllowed;
            photoUrl = original ? media.originalImageUrl : media.imageUrl;
            photoFilename = original ? originalPhotoFilename : customPhotoFilename;
            ArrayList<Captured> items = new ArrayList<>();
            for (Captured child : previous.children) items.add(child.withOriginal(original));
            children = Collections.unmodifiableList(items);
        }

        Captured withOriginal(boolean value) { return new Captured(this, value); }
        DownloadRequest selectedVideo() { return original ? originalVideo : video; }
        boolean offersAudio() { return audioAllowed && media.separateAudio != null; }
    }

    static Captured capture(Context ctx, Object media, String type) throws Exception {
        boolean offerAudioDownload = FeatureFlags.offerAudioDownload;
        return new Captured(SelectedMediaAdapter.capture(ctx, media, type), type,
                new DownloadRequest.Options(FeatureFlags.downloaderQualityMaxShortSide,
                        FeatureFlags.downloaderCompatibility, FeatureFlags.downloaderAllowFallback),
                FeatureFlags.downloaderUseOriginal, offerAudioDownload);
    }

    static void captureAndShowPost(Context ctx, Object media, String type) {
        try {
            Captured captured = capture(ctx, media, type); // Synchronous, at Download tap.
            // The chooser numbers are always tied to the snapshot. We deliberately do not infer
            // a current slide from unrelated visible pagers or arbitrary controller int fields.
            showPost(ctx, captured);
        } catch (Throwable error) { unavailable(ctx, error); }
    }

    static void showPost(Context ctx, Captured captured) {
        if (captured.children.isEmpty()) { showItemChoices(ctx, captured); return; }
        if (!canShow(ctx)) return;
        ArrayList<CharSequence> labels = new ArrayList<>();
        for (int i = 0; i < captured.children.size(); i++)
            labels.add(I18n.t(ctx, R.string.ie_download_item, i + 1));
        labels.add(I18n.t(ctx, R.string.ig_dl_carousel_all, captured.children.size()));
        IeDialog dialog = new IeDialog(ctx).title(I18n.t(ctx, R.string.ie_download_select_item));
        dialog.choices(labels.toArray(new CharSequence[0]), -1, which -> {
            if (which == captured.children.size()) {
                for (Captured child : captured.children) start(ctx, child, child.video != null);
            } else showItemChoices(ctx, captured.children.get(which));
        });
        dialog.secondary(modeLabel(ctx, captured), () -> showPost(ctx, switchMode(captured)));
        dialog.show();
    }

    private static void showItemChoices(Context ctx, Captured captured) {
        if (!canShow(ctx)) return;
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Choice> choices = new ArrayList<>();
        if (captured.media.imageUrl != null) {
            labels.add(I18n.t(ctx, R.string.ig_story_download_photo));
            choices.add(Choice.PHOTO);
        }
        if (captured.video != null) {
            labels.add(I18n.t(ctx, R.string.ie_download_video));
            choices.add(Choice.VIDEO);
            labels.add(I18n.t(ctx, R.string.ie_save_video_frame));
            choices.add(Choice.FRAME);
        }
        if (captured.offersAudio()) {
            labels.add(I18n.t(ctx, R.string.ig_download_choice_audio));
            choices.add(Choice.AUDIO);
        }
        IeDialog dialog = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_dl_title));
        dialog.choices(labels.toArray(new CharSequence[0]), -1,
                which -> startChoice(ctx, captured, choices.get(which)));
        dialog.secondary(modeLabel(ctx, captured), () -> showItemChoices(ctx, switchMode(captured)));
        dialog.show();
    }

    static String modeLabel(Context ctx, Captured captured) {
        return I18n.t(ctx, R.string.ie_downloader_mode,
                I18n.t(ctx, captured.original ? R.string.ie_downloader_original : R.string.ie_download_best));
    }

    static Captured switchMode(Captured captured) {
        Captured next = captured.withOriginal(!captured.original);
        FeatureFlags.downloaderUseOriginal = next.original;
        SettingsManager.saveAllFlags();
        return next;
    }

    static void startChoice(Context ctx, Captured captured, Choice choice) {
        switch (choice) {
            case PHOTO -> start(ctx, captured, false);
            case VIDEO -> start(ctx, captured, true);
            case FRAME -> startFrame(ctx, captured.video);
            case AUDIO -> {
                OriginalMediaSelection.Audio audio = captured.media.separateAudio;
                if (captured.offersAudio()) FeedVideoDownloadHook.downloadAudio(ctx, audio.url,
                        captured.audioFilename,
                        captured.media.owner, audio.extract, audio.startMs, audio.durationMs);
            }
        }
    }

    static void startFrame(Context ctx, DownloadRequest request) {
        if (request == null) { unavailable(ctx, null); return; }
        try {
            CompanionBridge.saveFrame(ctx, request, error -> {
                if (error != null) failed(ctx, error);
            });
        } catch (Throwable error) {
            failed(ctx, "Could not open video frame picker");
        }
    }

    static void start(Context ctx, Captured captured, boolean video) {
        if (captured.original && (video ? captured.media.originalPlan == null : captured.photoUrl == null)) {
            toast(ctx, I18n.t(ctx, R.string.ie_downloader_original_unavailable));
            return;
        }
        if (video && captured.original) { startOriginal(ctx, captured); return; }
        if (video) { startVideo(ctx, captured.selectedVideo()); return; }
        if (captured.photoUrl == null) { unavailable(ctx, null); return; }
        try {
            if (FeedVideoDownloadHook.hasCompanionDestination()) {
                CompanionBridge.downloadLegacy(ctx, captured.photoUrl, null, captured.photoFilename,
                        "image/jpeg", captured.media.owner, error -> {
                            if (error != null) failed(ctx, error);
                            else toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_photo));
                        });
                return;
            }
        } catch (Throwable error) { failed(ctx, "Could not start download service"); return; }
        DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();
        Context app = ctx.getApplicationContext();
        WORKER.submit(() -> {
            ProgressUi progress = new ProgressUi(ctx, cancel);
            progress.show();
            File temp = null;
            try {
                temp = File.createTempFile("ie_photo_", ".bin", app.getCacheDir());
                String mime = DownloadEngine.fetch(app, captured.photoUrl, temp, "image/jpeg", cancel, progress);
                MediaTypeDetector.Result detected = MediaTypeDetector.verified(mime, captured.photoFilename);
                DownloadPublisher.save(app, temp, detected.filename, detected.mimeType, captured.media.owner, cancel);
                toast(ctx, I18n.t(ctx, R.string.ig_toast_file_saved, detected.filename));
            } catch (Throwable error) {
                failed(ctx, cancel.isCancelled() ? I18n.t(ctx, R.string.ie_download_cancelled)
                        : error instanceof DownloadEngine.Failure failure ? failure.getMessage() : "Photo download failed");
            } finally { if (temp != null) temp.delete(); progress.close(); }
        });
    }

    private static void startOriginal(Context ctx, Captured captured) {
        OriginalMediaSelection.Plan plan = captured.media.originalPlan;
        String filename = captured.originalVideo.filename;
        WORKER.submit(() -> {
            try {
                // Preserve upstream 0.8.1 automatic progressive fallback and native merge.
                String base = plan.fallbackUrl == null ? plan.url : plan.fallbackUrl;
                String[] upgrade = plan.fallbackUrl == null ? null : new String[]{plan.url, plan.audioUrl};
                boolean delegated = FeedVideoDownloadHook.downloadCapturedOriginal(ctx, base,
                        filename, true, captured.media.owner, upgrade);
                if (!delegated) toast(ctx, I18n.t(ctx, R.string.ig_toast_file_saved, filename));
            } catch (Throwable failure) { failed(ctx, "Original download failed"); }
        });
    }

    static void startVideo(Context ctx, DownloadRequest request) {
        if (request == null) { unavailable(ctx, null); return; }
        try {
            if (FeedVideoDownloadHook.hasCompanionDestination()) {
                CompanionBridge.download(ctx, request, error -> {
                    if (error != null) failed(ctx, error);
                    else toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_video));
                });
                return;
            }
        } catch (Throwable error) { failed(ctx, "Could not start download service"); return; }

        DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();
        Context app = ctx.getApplicationContext();
        WORKER.submit(() -> {
            ProgressUi progress = new ProgressUi(ctx, cancel);
            progress.show();
            try (DownloadEngine.Result result = DownloadEngine.execute(app, request, cancel, progress)) {
                DownloadPublisher.save(app, result.file, request.filename, result.mime, request.owner, cancel);
                toast(ctx, I18n.t(ctx, result.lowerFallback ? R.string.ie_download_fallback_used
                        : R.string.ie_download_verified, result.summary));
                ModuleLog.line("(IE|Download) published " + result.summary);
            } catch (DownloadEngine.Failure failure) {
                progress.close();
                if (cancel.isCancelled()) toast(ctx, I18n.t(ctx, R.string.ie_download_cancelled));
                else if (failure.canOfferFallback && !request.options.allowFallback && canShow(ctx)) {
                    // Occupy this serial worker until the prompt is dismissed, so later batch
                    // progress cannot cover a decision about this captured item.
                    if (offerFallbackAndWait(ctx, failure.getMessage(), cancel))
                        startVideo(ctx, allowFallback(request));
                } else failed(ctx, failure.getMessage());
            } catch (Throwable error) {
                failed(ctx, cancel.isCancelled() ? I18n.t(ctx, R.string.ie_download_cancelled)
                        : "Could not save verified video");
            } finally {
                progress.close();
            }
        });
    }

    private static boolean offerFallbackAndWait(Context ctx, String failure,
            DownloadEngine.Cancellation cancellation) {
        CountDownLatch dismissed = new CountDownLatch(1);
        AtomicBoolean retry = new AtomicBoolean(false);
        AtomicReference<AlertDialog> prompt = new AtomicReference<>();
        FeedVideoDownloadHook.mainHandler.post(() -> {
            if (!canShow(ctx) || cancellation.isCancelled()) { dismissed.countDown(); return; }
            try {
                AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle(I18n.t(ctx, R.string.ig_dl_title))
                        .setMessage(failure + "\n\n" + I18n.t(ctx, R.string.ie_download_fallback_prompt))
                        .setPositiveButton(I18n.t(ctx, R.string.ie_download_retry_lower),
                                (ignored, which) -> retry.set(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setOnDismissListener(ignored -> dismissed.countDown()).create();
                prompt.set(dialog);
                dialog.show();
            } catch (Throwable ignored) { dismissed.countDown(); }
        });
        try {
            while (!dismissed.await(250, TimeUnit.MILLISECONDS)) {
                if (!canShow(ctx) || cancellation.isCancelled()) return false;
            }
            return retry.get() && canShow(ctx) && !cancellation.isCancelled();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancellation.cancel();
            return false;
        } finally {
            FeedVideoDownloadHook.mainHandler.post(() -> {
                try { AlertDialog dialog = prompt.get(); if (dialog != null) dialog.dismiss(); }
                catch (Throwable ignored) { }
            });
        }
    }

    private static final class ProgressUi implements DownloadEngine.Progress {
        private final Context ctx;
        private final DownloadEngine.Cancellation cancellation;
        private final AtomicReference<ProgressDialog> dialog = new AtomicReference<>();
        private long lastUpdate;
        ProgressUi(Context ctx, DownloadEngine.Cancellation cancellation) {
            this.ctx = ctx; this.cancellation = cancellation;
        }
        void show() {
            FeedVideoDownloadHook.mainHandler.post(() -> {
                if (!canShow(ctx)) return;
                try {
                    ProgressDialog view = new ProgressDialog(ctx);
                    view.setTitle(I18n.t(ctx, R.string.ig_dl_title));
                    view.setMessage(I18n.t(ctx, R.string.ig_toast_downloading));
                    view.setIndeterminate(true);
                    view.setCancelable(true);
                    view.setOnCancelListener(ignored -> cancellation.cancel());
                    view.setButton(ProgressDialog.BUTTON_NEGATIVE, ctx.getString(android.R.string.cancel),
                            (ignored, which) -> cancellation.cancel());
                    dialog.set(view);
                    view.show();
                } catch (Throwable ignored) { }
            });
        }
        @Override public void onProgress(String phase, long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastUpdate < 250 && done != total) return;
            lastUpdate = now;
            FeedVideoDownloadHook.mainHandler.post(() -> {
                ProgressDialog view = dialog.get();
                if (view != null && canShow(ctx) && view.isShowing()) view.setMessage(phase
                        + (total > 0 ? " " + Math.min(100, done * 100 / total) + "%" : ""));
            });
        }
        void close() {
            FeedVideoDownloadHook.mainHandler.post(() -> {
                try { ProgressDialog view = dialog.get(); if (view != null) view.dismiss(); }
                catch (Throwable ignored) { }
            });
        }
    }

    static DownloadRequest allowFallback(DownloadRequest original) {
        return new DownloadRequest(original.mediaId, original.owner, original.filename,
                original.manifest, original.direct, new DownloadRequest.Options(
                original.options.maxShortSide, original.options.compatibility, true,
                original.options.originalSelection), original.audio);
    }

    static void unavailable(Context ctx, Throwable error) {
        ModuleLog.line("(IE|MediaAdapter) capture unavailable"
                + (error == null ? "" : " (" + error.getClass().getSimpleName() + ")"));
        toast(ctx, I18n.t(ctx, R.string.ie_download_unavailable));
    }

    private static String filename(String base, boolean video, boolean original) {
        return base + (original ? "_original" : "_custom") + (video ? ".mp4" : ".jpg");
    }

    private static boolean canShow(Context context) {
        Context c = context;
        for (int i = 0; i < 8; i++) {
            if (c instanceof Activity a) return !a.isFinishing() && !a.isDestroyed();
            if (!(c instanceof ContextWrapper wrapper) || wrapper.getBaseContext() == c) break;
            c = wrapper.getBaseContext();
        }
        return false;
    }

    private static void failed(Context ctx, String reason) {
        toast(ctx, I18n.t(ctx, R.string.ig_toast_download_failed, reason));
    }
    private static void toast(Context ctx, String message) {
        FeedVideoDownloadHook.mainHandler.post(() -> Toast.makeText(ctx.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
