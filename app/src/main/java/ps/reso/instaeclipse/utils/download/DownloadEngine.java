/*
 * HushGram-derived fetch/join engine, adapted for InstaEclipse. GPL-3.0-only.
 * See third-party/HushGram/NOTICE.md. No Morphe runtime dependency.
 */
package ps.reso.instaeclipse.utils.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Blocking worker API. The caller owns publishing the verified file and closing Result. */
public final class DownloadEngine {
    /** Cheap shape check for the IPC boundary. Fetch still validates DNS and every redirect. */
    public static boolean isAllowedMediaUrl(String url) {
        return MediaUrlPolicy.shapeRefusal(url) == null;
    }
    private DownloadEngine() { }
    private static final long RESERVE_BYTES = 16L * 1024 * 1024;
    public interface Progress { void onProgress(String phase, long done, long total); }
    public static final class Cancellation {
        private volatile boolean cancelled;
        private Runnable closer;
        public void cancel() {
            Runnable close;
            synchronized (this) { cancelled = true; close = closer; }
            if (close != null) try { close.run(); } catch (RuntimeException ignored) { }
        }
        public boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
        synchronized void reading(Runnable close) {
            closer = close;
            if (cancelled && close != null) close.run();
        }
    }
    public static final class Failure extends IOException {
        public final boolean canOfferFallback;
        public final String status;
        /** Fixed diagnostic tags only; never exception messages, URLs, IDs or file paths. */
        public final String diagnostic;
        Failure(String status, String message, boolean offer) {
            this(status, message, offer, "");
        }
        private Failure(String status, String message, boolean offer, String diagnostic) {
            super(message); this.status = status; this.canOfferFallback = offer; this.diagnostic = diagnostic;
        }
    }
    public static final class Result implements AutoCloseable {
        public final File file;
        public final String mime, summary;
        public final boolean lowerFallback;
        public final Metadata metadata;
        Result(File file, String mime, Metadata metadata, boolean lowerFallback, String discovery) {
            this.file = file; this.mime = mime; this.metadata = metadata; this.lowerFallback = lowerFallback;
            this.summary = discovery + "; " + metadata + (lowerFallback ? "; explicitly allowed fallback used" : "");
        }
        @Override public void close() { Downloader.delete(file); }
    }
    public static final class Metadata {
        public final int width, height, rotation, audioProfile;
        public final long durationUs;
        public final String videoCodec, audioCodec;
        public final boolean hasAudio;
        public final long audioDurationUs, videoFirstUs, videoLastUs, audioFirstUs, audioLastUs;
        public final int videoSamples, audioSamples;
        Metadata(int width, int height, int rotation, long durationUs, String videoCodec, String audioCodec, int audioProfile) {
            this(width, height, rotation, durationUs, videoCodec, audioCodec, audioProfile,
                    0, 0, 0, 0, 0, 0, 0);
        }
        Metadata(int width, int height, int rotation, long durationUs, String videoCodec, String audioCodec, int audioProfile,
                long audioDurationUs, long videoFirstUs, long videoLastUs, long audioFirstUs, long audioLastUs,
                int videoSamples, int audioSamples) {
            this.width = width; this.height = height; this.rotation = rotation; this.durationUs = durationUs;
            this.videoCodec = videoCodec; this.audioCodec = audioCodec; this.hasAudio = !audioCodec.isEmpty();
            this.audioProfile = audioProfile;
            this.audioDurationUs = audioDurationUs; this.videoFirstUs = videoFirstUs; this.videoLastUs = videoLastUs;
            this.audioFirstUs = audioFirstUs; this.audioLastUs = audioLastUs;
            this.videoSamples = videoSamples; this.audioSamples = audioSamples;
        }
        @Override public String toString() {
            return width + "x" + height + " " + videoCodec + " audio=" + (hasAudio ? audioCodec : "absent")
                    + " audioProfile=" + audioProfile + " durationMs=" + durationUs / 1000 + " rotation=" + rotation
                    + " samples=" + videoSamples + "/" + audioSamples;
        }
    }

    public static Result execute(Context context, DownloadRequest request, Cancellation cancellation, Progress listener)
            throws Failure {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new Failure("THREAD", "Downloads must run on a worker thread", false);
        Cancellation cancel = cancellation == null ? new Cancellation() : cancellation;
        Progress progress = listener == null ? (phase, done, total) -> { } : listener;
        RenditionPicker.Plan plan = RenditionPicker.plan(request);
        try {
            return attemptPlan(request, plan, (choice, fallback) -> run(context.getCacheDir(), request, choice,
                    cancel, progress, fallback, selectionSummary(request, plan, choice)));
        } finally {
            cancel.reading(null);
        }
    }

    static String selectionSummary(DownloadRequest request, RenditionPicker.Plan plan, RenditionPicker.Choice choice) {
        if (request.options.originalSelection) return "Original direct-file selection";
        return "Custom; " + (plan.manifest.state == DashManifest.State.ABSENT
                ? "No manifest exposed; best discovered direct candidate" : choice.dash
                ? "Selected exposed DASH tracks" : "Selected discovered direct candidate");
    }

    /** Fetches one media file into an empty internal-cache destination; the caller owns publication. */
    public static String fetch(Context context, String url, File destination, String expectedMime,
            Cancellation cancellation, Progress listener) throws Failure {
        return fetch(context, url, destination, expectedMime, cancellation, listener, false);
    }

    /** A source for upstream audio extraction may contain video, but must contain real audio. */
    public static String fetchAudioSource(Context context, String url, File destination,
            Cancellation cancellation, Progress listener) throws Failure {
        return fetch(context, url, destination, "audio/mp4", cancellation, listener, true);
    }

    private static String fetch(Context context, String url, File destination, String expectedMime,
            Cancellation cancellation, Progress listener, boolean audioSource) throws Failure {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new Failure("THREAD", "Downloads must run on a worker thread", false);
        Downloader.Kind kind = fetchKind(expectedMime);
        if (destination == null) throw new Failure("STORAGE", "No temporary media destination", false);
        try {
            String cache = context.getCacheDir().getCanonicalPath() + File.separator;
            if (!destination.getCanonicalPath().startsWith(cache) || (destination.exists() && destination.length() != 0))
                throw new Failure("STORAGE", "Media destination must be an empty internal-cache file", false);
        } catch (IOException e) {
            if (e instanceof Failure) throw (Failure) e;
            throw new Failure("STORAGE", "Temporary media destination is unavailable", false);
        }
        Cancellation cancel = cancellation == null ? new Cancellation() : cancellation;
        Progress listenerOrSilent = listener == null ? (phase, done, total) -> { } : listener;
        boolean complete = false;
        try {
            checkCancelled(cancel);
            Downloader.Result fetched = Downloader.fetch(url, kind, destination,
                    new MediaUrlPolicy(MediaUrlPolicy.DNS, hop -> !MediaUrlPolicy.proxied(hop)),
                    capacity(context.getCacheDir().getUsableSpace(), false), progress(cancel, listenerOrSilent, "Downloading media"));
            require(fetched);
            if (kind == Downloader.Kind.AUDIO) inspect(destination, true, null, cancel, audioSource);
            else if (kind == Downloader.Kind.VIDEO)
                inspect(destination, false, new DownloadRequest.Rendition(url, 0, 0, 0, "", 0), cancel);
            checkCancelled(cancel);
            complete = true;
            return kind == Downloader.Kind.AUDIO && "video/mp4".equals(fetched.mime) ? "audio/mp4" : fetched.mime;
        } catch (Failure e) { throw e;
        } catch (IOException | RuntimeException e) {
            checkCancelled(cancel);
            throw new Failure("VERIFY", "Downloaded media could not be verified (" + e.getClass().getSimpleName() + ")", false);
        } finally {
            cancel.reading(null);
            if (!complete) Downloader.delete(destination);
        }
    }
    static Downloader.Kind fetchKind(String mime) throws Failure {
        if (mime != null && mime.startsWith("image/")) return Downloader.Kind.IMAGE;
        if (mime != null && mime.startsWith("audio/")) return Downloader.Kind.AUDIO;
        if (mime != null && mime.startsWith("video/")) return Downloader.Kind.VIDEO;
        throw new Failure("REFUSED", "Requested media type is unsupported", false);
    }

    interface Attempt<T> { T run(RenditionPicker.Choice choice, boolean fallback) throws Failure; }
    static <T> T attemptPlan(DownloadRequest request, RenditionPicker.Plan plan, Attempt<T> attempt) throws Failure {
        boolean invalidManifest = plan.manifest.state != DashManifest.State.ABSENT && plan.manifest.state != DashManifest.State.SUPPORTED;
        if (invalidManifest && !request.options.allowFallback)
            throw new Failure("MANIFEST", plan.manifest.reason + ". Choose an explicit direct fallback to continue.", !plan.choices.isEmpty());
        if (plan.choices.isEmpty()) throw new Failure(plan.excludedByCeiling > 0 ? "CEILING" : "NO_MEDIA",
                plan.excludedByCeiling > 0 ? "No discovered video fits the selected resolution ceiling"
                        : "No downloadable video candidate was exposed for this item", false);
        Failure first = null;
        for (int i = 0; i < plan.choices.size(); i++) {
            RenditionPicker.Choice choice = plan.choices.get(i);
            try {
                if (choice.unsupported != null) throw new Failure("UNSUPPORTED", choice.unsupported, false);
                return attempt.run(choice, invalidManifest || i > 0);
            } catch (Failure failure) {
                if (first == null) first = failure;
                boolean terminal = failure.status.equals("CANCELLED") || failure.status.equals("STORAGE");
                if (terminal) throw failure;
                if (!request.options.allowFallback) {
                    boolean offer = i + 1 < plan.choices.size();
                    throw new Failure(failure.status, failure.getMessage() + (offer
                            ? ". Best selection failed; choose an explicit lower-quality fallback to retry." : ""), offer, failure.diagnostic);
                }
            }
        }
        throw new Failure(first.status, first.getMessage() + ". No permitted candidate completed successfully.", false, first.diagnostic);
    }

    private static Result run(File cache, DownloadRequest request, RenditionPicker.Choice choice,
            Cancellation cancellation, Progress progress, boolean fallback, String discovery) throws Failure {
        List<File> files = new ArrayList<>();
        File keep = null;
        DownloadDiagnostics.Phase phase = DownloadDiagnostics.Phase.DOWNLOAD_VIDEO;
        try {
            checkCancelled(cancellation);
            long cap = capacity(cache.getUsableSpace(), choice.dash);
            File video = temp(cache, files), audio = null;
            Downloader.Progress reporting = progress(cancellation, progress, "Downloading video");
            MediaUrlPolicy policy = new MediaUrlPolicy(MediaUrlPolicy.DNS, url -> !MediaUrlPolicy.proxied(url));
            Downloader.Result fetched = Downloader.fetch(choice.video.url, Downloader.Kind.VIDEO, video,
                    policy, cap, reporting);
            require(fetched);
            String mime = fetched.mime;
            Metadata before = null, beforeAudio = null;
            if (choice.dash) {
                if (choice.audio != null) {
                    phase = DownloadDiagnostics.Phase.DOWNLOAD_AUDIO;
                    audio = temp(cache, files);
                    require(Downloader.fetch(choice.audio.url, Downloader.Kind.AUDIO, audio, policy,
                            Math.min(Downloader.MAX_BYTES - video.length(), Math.min(cap, capacity(cache.getUsableSpace(), true))),
                            progress(cancellation, progress, "Downloading audio")));
                }
                checkCancelled(cancellation);
                if (cache.getUsableSpace() < video.length() + (audio == null ? 0 : audio.length()) + RESERVE_BYTES)
                    throw new Failure("STORAGE", "Insufficient free space to join the selected tracks", false);
                File joined = temp(cache, files);
                reporting.joining();
                phase = DownloadDiagnostics.Phase.INSPECT_VIDEO;
                before = inspect(video, false, choice.video, cancellation);
                if (before.hasAudio) throw new Failure("JOIN", "DASH video unexpectedly contains embedded audio", false);
                if (audio != null) {
                    phase = DownloadDiagnostics.Phase.INSPECT_AUDIO;
                    beforeAudio = inspect(audio, true, null, cancellation);
                }
                phase = usesPortableJoin(before, beforeAudio)
                        ? DownloadDiagnostics.Phase.JOIN_PORTABLE : DownloadDiagnostics.Phase.JOIN_NATIVE;
                boolean completed = joinTracks(video, audio, joined, before, beforeAudio, cancellation, progress);
                if (!completed) throw new Failure("CANCELLED", "Download cancelled", false);
                if (joined.length() > Downloader.MAX_BYTES)
                    throw new Failure("TOO_LARGE", "Joined output exceeds the media size limit", false);
                video = joined;
                mime = "video/mp4";
            }
            phase = DownloadDiagnostics.Phase.INSPECT_OUTPUT;
            progress.onProgress("Verifying saved tracks", 0, -1);
            Metadata verified = inspect(video, choice.expectAudio, choice.video, cancellation);
            phase = DownloadDiagnostics.Phase.VERIFY_OUTPUT;
            if (before != null) verifyJoined(before, beforeAudio, verified);
            verifySelection(verified, request.options);
            checkCancelled(cancellation);
            keep = video;
            return new Result(video, mime, verified, fallback, discovery);
        } catch (Failure e) { throw e;
        } catch (IOException | RuntimeException e) {
            checkCancelled(cancellation);
            throw diagnosedFailure(phase, e);
        } finally {
            for (File file : files) if (file != keep) Downloader.delete(file);
        }
    }
    static Failure diagnosedFailure(DownloadDiagnostics.Phase phase, Throwable error) {
        String diagnostic = DownloadDiagnostics.describe(phase, error);
        return new Failure("JOIN", "Selected tracks could not be joined or verified [" + diagnostic + "]", false, diagnostic);
    }
    static long capacity(long usable, boolean needsJoin) throws Failure {
        if (usable <= RESERVE_BYTES + 1024 * 1024) throw new Failure("STORAGE", "Insufficient temporary storage", false);
        return Math.min(Downloader.MAX_BYTES, (usable - RESERVE_BYTES) / (needsJoin ? 3 : 1));
    }
    static void verifySelection(Metadata verified, DownloadRequest.Options options) throws Failure {
        if (options.maxShortSide > 0 && Math.min(verified.width, verified.height) > options.maxShortSide)
            throw new Failure("CEILING", "Actual saved dimensions exceed the selected resolution ceiling", false);
    }
    static void verifyJoined(Metadata video, Metadata audio, Metadata output) throws Failure {
        if (Math.abs(video.durationUs - output.durationUs) > durationTolerance(video.durationUs)
                || video.rotation != output.rotation || video.videoSamples != output.videoSamples)
            throw new Failure("VERIFY", "Joined video duration, samples or rotation differs from its source", false);
        if (audio == null) return;
        if (!output.hasAudio || audio.audioSamples != output.audioSamples
                || Math.abs(audio.audioDurationUs - output.audioDurationUs) > durationTolerance(audio.audioDurationUs)) {
            String diagnostic = DownloadDiagnostics.joinedAudio(audio, output);
            throw new Failure("VERIFY", "Joined audio duration or samples differs from its source [" + diagnostic + "]",
                    false, diagnostic);
        }
        long originalStartGap = video.videoFirstUs - audio.audioFirstUs;
        long outputStartGap = output.videoFirstUs - output.audioFirstUs;
        long originalEndGap = video.videoLastUs - audio.audioLastUs;
        long outputEndGap = output.videoLastUs - output.audioLastUs;
        if (Math.abs(originalStartGap - outputStartGap) > 50_000 || Math.abs(originalEndGap - outputEndGap) > 50_000)
            throw new Failure("VERIFY", "Joined audio/video timing differs from the selected source tracks", false);
    }
    private static File temp(File cache, List<File> files) throws Failure {
        try { File file = File.createTempFile("ie_dash_", ".mp4", cache); files.add(file); return file; }
        catch (IOException e) { throw new Failure("STORAGE", "Temporary storage could not create a media file", false); }
    }
    static void require(Downloader.Result result) throws Failure {
        if (!result.ok()) throw new Failure(result.status == Downloader.Status.STORAGE_ERROR ? "STORAGE" : result.status.name(),
                "Selected media download failed: " + result.reason, false);
    }
    private static Downloader.Progress progress(Cancellation cancel, Progress progress, String phase) {
        return new Downloader.Progress() {
            @Override public void transferred(long done, long total) { progress.onProgress(phase, done, total); }
            @Override public void reading(Runnable closer) { cancel.reading(closer); }
            @Override public boolean cancelled() { return cancel.isCancelled(); }
            @Override public void joining() { progress.onProgress("Joining tracks without re-encoding", 0, -1); }
        };
    }
    private static void checkCancelled(Cancellation cancel) throws Failure {
        if (cancel.isCancelled()) throw new Failure("CANCELLED", "Download cancelled", false);
    }
    private static long durationTolerance(long duration) { return Math.max(500_000L, duration / 20); }

    static Metadata inspect(File file, boolean expectAudio, DownloadRequest.Rendition expected,
            Cancellation cancellation) throws IOException {
        return inspect(file, expectAudio, expected, cancellation, false);
    }

    static Metadata inspect(File file, boolean expectAudio, DownloadRequest.Rendition expected,
            Cancellation cancellation, boolean audioSource) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            if (ex.getPsshInfo() != null && !ex.getPsshInfo().isEmpty()) throw new IOException("Encrypted media");
            int width = 0, height = 0, rotation = 0, videos = 0, audios = 0, audioProfile = 0;
            long videoDuration = 0, audioDuration = 0;
            long videoFirst = 0, videoLast = 0, audioFirst = 0, audioLast = 0;
            int videoSamples = 0, audioSamples = 0;
            String videoCodec = "", audioCodec = "";
            for (int i = 0; i < ex.getTrackCount(); i++) {
                checkCancelled(cancellation);
                if (isDolbyVisionAlias(ex, i)) continue;
                MediaFormat format = ex.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || (!mime.startsWith("video/") && !mime.startsWith("audio/"))) continue;
                boolean video = mime.startsWith("video/");
                long duration = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
                if (video) {
                    videos++; videoCodec = mime;
                    width = format.getInteger(MediaFormat.KEY_WIDTH); height = format.getInteger(MediaFormat.KEY_HEIGHT);
                    rotation = format.containsKey(MediaFormat.KEY_ROTATION) ? format.getInteger(MediaFormat.KEY_ROTATION) : 0;
                } else {
                    audios++; audioCodec = mime;
                    audioProfile = format.containsKey(MediaFormat.KEY_AAC_PROFILE) ? format.getInteger(MediaFormat.KEY_AAC_PROFILE) : 0;
                }
                int sampleCount = 0;
                long minTime = Long.MAX_VALUE, maxTime = Long.MIN_VALUE;
                // A seek to time zero can skip negative preroll after a plain-MP4 edit list,
                // while the fragmented path still returns it. A fresh extractor per track
                // reads every encoded sample from its true start, including that preroll.
                MediaExtractor samples = new MediaExtractor();
                try {
                    samples.setDataSource(file.getAbsolutePath());
                    samples.selectTrack(i);
                    ByteBuffer sample = ByteBuffer.allocate(sampleBuffer(format));
                    while (true) {
                        checkCancelled(cancellation);
                        sample.clear();
                        int size = samples.readSampleData(sample, 0);
                        if (size < 0) break;
                        if (size > sample.capacity() || (samples.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                            throw new IOException("Invalid or encrypted sample");
                        minTime = Math.min(minTime, samples.getSampleTime()); maxTime = Math.max(maxTime, samples.getSampleTime());
                        if (++sampleCount > 2_000_000) throw new IOException("Sample count limit");
                        if (!samples.advance()) break;
                    }
                } finally {
                    samples.release();
                }
                checkCancelled(cancellation);
                duration = verifyCoverage(file, video, duration, minTime, maxTime, sampleCount);
                checkCancelled(cancellation);
                if (video) { videoDuration = duration; videoFirst = minTime; videoLast = maxTime; videoSamples = sampleCount; }
                else { audioDuration = duration; audioFirst = minTime; audioLast = maxTime; audioSamples = sampleCount; }
            }
            boolean expectVideo = expected != null;
            if ((expectVideo && (videos != 1 || width <= 0 || height <= 0))
                    || (!expectVideo && videos != 0 && !audioSource) || videos > 1
                    || (expectAudio && audios == 0)) throw new IOException("Required media track missing");
            if (audios > 1) throw new IOException("Ambiguous audio tracks");
            if (videos > 0 && audios > 0 && Math.abs(videoDuration - audioDuration) > Math.max(1_000_000, videoDuration / 10))
                throw new IOException("Audio/video durations differ");
            if (expected != null && expected.width > 0 && expected.height > 0
                    && (width != expected.width || height != expected.height)) throw new IOException("Actual dimensions differ from selected rendition");
            if (expected != null && !expected.codecs.isEmpty() && !codecMatches(expected.codecs, videoCodec))
                throw new IOException("Actual codec differs from selected rendition");
            return new Metadata(width, height, rotation, videoDuration, videoCodec, audioCodec, audioProfile,
                    audioDuration, videoFirst, videoLast, audioFirst, audioLast, videoSamples, audioSamples);
        } finally { ex.release(); }
    }

    static long verifyCoverage(File file, boolean video, long duration, long minTime, long maxTime,
            int sampleCount) throws IOException {
        if (duration > 0 && sampleCount > 0 && maxTime - minTime + durationTolerance(duration) >= duration)
            return duration;
        // PTS describes a sample's start, not its end. For sparse/held samples, prove the final
        // duration from the bounded index, including exact count and byte-range agreement.
        long indexedDuration;
        try { indexedDuration = Mp4Join.indexedDurationUs(file, video, sampleCount); }
        catch (IOException | RuntimeException error) {
            throw new DownloadDiagnostics.CoverageFailure(video, duration, minTime, maxTime,
                    sampleCount, file.length(), -1, error);
        }
        if (duration > 0 && Math.abs(indexedDuration - duration) > durationTolerance(duration))
            throw new DownloadDiagnostics.CoverageFailure(video, duration, minTime, maxTime,
                    sampleCount, file.length(), indexedDuration, null);
        return duration > 0 ? duration : indexedDuration;
    }
    private static boolean codecMatches(String expected, String actual) {
        if (expected.startsWith("avc1") || expected.startsWith("avc3")) return actual.equals("video/avc");
        if (expected.startsWith("hvc1") || expected.startsWith("hev1")) return actual.equals("video/hevc");
        if (expected.startsWith("vp09")) return actual.equals("video/x-vnd.on2.vp9");
        if (expected.startsWith("av01")) return actual.equals("video/av01");
        return false;
    }
    private static int sampleBuffer(MediaFormat format) throws IOException {
        int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 4 * 1024 * 1024;
        if (size < 0 || size > 32 * 1024 * 1024) throw new IOException("Sample buffer exceeds limit");
        return Math.max(size, 1024 * 1024);
    }
    /**
     * Whether track {@code i} is the Dolby Vision view of another track. For backward-compatible
     * Dolby Vision (an AV1 or HEVC picture with a {@code dvvC}/{@code dvcC} box, as Instagram's
     * HDR streams are) Android 13 lists the one MP4 track twice under the same track id: as
     * video/dolby-vision and as its base codec. Only the base codec's entry is a separate picture.
     */
    static boolean isDolbyVisionAlias(MediaExtractor ex, int i) {
        MediaFormat f = ex.getTrackFormat(i);
        if (!MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(f.getString(MediaFormat.KEY_MIME))
                || !f.containsKey(MediaFormat.KEY_TRACK_ID)) return false;
        int id = f.getInteger(MediaFormat.KEY_TRACK_ID);
        for (int j = 0; j < ex.getTrackCount(); j++) {
            if (j == i) continue;
            MediaFormat g = ex.getTrackFormat(j);
            String mime = g.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/") && !MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION.equals(mime)
                    && g.containsKey(MediaFormat.KEY_TRACK_ID) && g.getInteger(MediaFormat.KEY_TRACK_ID) == id) return true;
        }
        return false;
    }

    private static int select(MediaExtractor ex, String prefix) throws IOException {
        int found = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            if (isDolbyVisionAlias(ex, i)) continue;
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                if (found >= 0) throw new IOException("Ambiguous source tracks"); found = i;
            }
        }
        if (found < 0) throw new IOException("Missing source track");
        ex.selectTrack(found); return found;
    }

    static boolean usesPortableJoin(Metadata video, Metadata audio) {
        // Sparse/held tracks need their indexed final duration. Native muxing only receives PTS
        // and can change their duration and B-frame timing; the portable writer copies both.
        return video.videoCodec.equals("video/x-vnd.on2.vp9") || video.videoCodec.equals("video/av01")
                || needsIndexedEnd(video.durationUs, video.videoFirstUs, video.videoLastUs, video.videoSamples)
                || (audio != null && needsIndexedEnd(audio.audioDurationUs, audio.audioFirstUs,
                        audio.audioLastUs, audio.audioSamples));
    }

    private static boolean needsIndexedEnd(long duration, long first, long last, int samples) {
        return samples > 0 && duration > 0 && last - first + durationTolerance(duration) < duration;
    }

    /** Shared production route for already inspected DASH sources; no quality or encoding change. */
    static boolean joinTracks(File video, File audio, File out, Metadata sourceVideo, Metadata sourceAudio,
            Cancellation cancellation, Progress listener) throws IOException {
        checkCancelled(cancellation);
        return usesPortableJoin(sourceVideo, sourceAudio)
                ? Mp4Join.join(video, audio, out, progress(cancellation, listener, "Joining tracks without re-encoding"))
                : nativeJoin(video, audio, out, cancellation);
    }

    /** Adapted from HushGram DashSave: interleaved stream-copy and a shared negative-time offset. */
    static boolean nativeJoin(File video, File audio, File out, Cancellation cancellation) throws IOException {
        MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
        MediaMuxer mux = null; boolean started = false;
        try {
            v.setDataSource(video.getAbsolutePath());
            MediaFormat vf = v.getTrackFormat(select(v, "video/"));
            MediaFormat af = null;
            if (audio != null) { a.setDataSource(audio.getAbsolutePath()); af = a.getTrackFormat(select(a, "audio/")); }
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) mux.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION));
            int vt = mux.addTrack(vf), at = af == null ? -1 : mux.addTrack(af);
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(sampleBuffer(vf), af == null ? 0 : sampleBuffer(af)));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            mux.start(); started = true;
            long lead = Math.min(0, Math.min(v.getSampleTime(), af == null ? 0 : a.getSampleTime()));
            boolean vd = false, ad = af == null;
            while (!vd || !ad) {
                checkCancelled(cancellation);
                boolean takeVideo = !vd && (ad || v.getSampleTime() <= a.getSampleTime());
                MediaExtractor source = takeVideo ? v : a;
                buffer.clear(); int size = source.readSampleData(buffer, 0);
                if (size < 0) { if (takeVideo) vd = true; else ad = true; continue; }
                if (size > buffer.capacity() || (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                    throw new IOException("Invalid sample");
                info.set(0, size, source.getSampleTime() - lead,
                        (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(takeVideo ? vt : at, buffer, info);
                if (!source.advance()) { if (takeVideo) vd = true; else ad = true; }
            }
            mux.stop(); started = false;
            return true;
        } finally {
            // Each cleanup runs even when another native cleanup fails.
            try { if (mux != null) { try { if (started) mux.stop(); } finally { mux.release(); } } }
            finally { try { v.release(); } finally { a.release(); } }
        }
    }
}
