/* HushGram-based downloader integration. SPDX-License-Identifier: GPL-3.0-only */
package ps.reso.instaeclipse.utils.download;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Snapshot of one selected item. Contains no Instagram model or live account/view references. */
public final class DownloadRequest {
    public static final int MAX_MANIFEST_CHARS = 128 * 1024;
    public static final int MAX_JSON_CHARS = 256 * 1024;
    public static final int MAX_RENDITIONS = 32;
    public enum AudioExpectation { PRESENT, SILENT, UNKNOWN }
    public final String mediaId, owner, filename, manifest;
    public final List<Rendition> direct;
    public final Options options;
    public final AudioExpectation audio;

    public DownloadRequest(String mediaId, String owner, String filename, String manifest,
            List<Rendition> direct, Options options, AudioExpectation audio) {
        this.mediaId = text(mediaId, 256);
        this.owner = text(owner, 128);
        this.filename = text(filename, 255);
        this.manifest = text(manifest, MAX_MANIFEST_CHARS);
        if (direct != null && direct.size() > MAX_RENDITIONS) throw new IllegalArgumentException("Too many renditions");
        ArrayList<Rendition> copy = new ArrayList<>();
        if (direct != null) for (Rendition r : direct) {
            if (r != null) copy.add(new Rendition(r.url, r.width, r.height, r.frameRate, r.codecs, r.bitrate));
        }
        this.direct = Collections.unmodifiableList(copy);
        this.options = options == null ? new Options(0, false, false)
                : new Options(options.maxShortSide, options.compatibility, options.allowFallback, options.originalSelection);
        this.audio = audio == null ? AudioExpectation.UNKNOWN : audio;
        if (this.options.originalSelection && (!this.manifest.isEmpty()
                || direct == null || direct.size() != 1 || copy.size() != 1 || copy.get(0).url.isEmpty()
                || this.audio != AudioExpectation.UNKNOWN))
            throw new IllegalArgumentException("Original selection requires one direct file, no manifest, and unknown audio");
    }

    public static final class Rendition {
        public final String url, codecs;
        public final int width, height;
        public final double frameRate;
        public final long bitrate;
        public Rendition(String url, int width, int height, double frameRate, String codecs, long bitrate) {
            this.url = text(url, 4096);
            this.codecs = text(codecs, 128).toLowerCase(Locale.ROOT);
            this.width = dimension(width);
            this.height = dimension(height);
            this.frameRate = Double.isFinite(frameRate) && frameRate > 0 && frameRate <= 1000 ? frameRate : 0;
            this.bitrate = Math.max(0, bitrate);
        }
        public int shortSide() { return Math.min(width, height); }
        @Override public String toString() { return width + "x" + height + " fps=" + frameRate + " codec=" + codecs; }
    }

    public static final class Options {
        public final int maxShortSide;
        public final boolean compatibility, allowFallback, originalSelection;
        public Options(int maxShortSide, boolean compatibility, boolean allowFallback) {
            this(maxShortSide, compatibility, allowFallback, false);
        }
        public Options(int maxShortSide, boolean compatibility, boolean allowFallback, boolean originalSelection) {
            if (!originalSelection && (maxShortSide < 0 || maxShortSide > 32768))
                throw new IllegalArgumentException("Invalid resolution ceiling");
            this.originalSelection = originalSelection;
            // Original copies the caller's selected direct file; Custom controls cannot alter it.
            this.maxShortSide = originalSelection ? 0 : maxShortSide;
            this.compatibility = !originalSelection && compatibility;
            this.allowFallback = !originalSelection && allowFallback;
        }
    }

    /** Private explicit-component service transport, never a log string or persistent queue. */
    public String toJson() {
        String json = new Gson().toJson(this);
        if (json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Download request exceeds IPC limit");
        return json;
    }
    public static DownloadRequest fromJson(String json) {
        if (json == null || json.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Invalid download request");
        try {
            DownloadRequest r = new Gson().fromJson(json, DownloadRequest.class);
            if (r == null) throw new IllegalArgumentException();
            return new DownloadRequest(r.mediaId, r.owner, r.filename, r.manifest, r.direct, r.options, r.audio);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid download request");
        }
    }
    private static String text(String s, int limit) {
        if (s == null) return "";
        if (s.length() > limit) throw new IllegalArgumentException("Download metadata exceeds limit");
        return s;
    }
    private static int dimension(int n) { return n > 0 && n <= 32768 ? n : 0; }
}
