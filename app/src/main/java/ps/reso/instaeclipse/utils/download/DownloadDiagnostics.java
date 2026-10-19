package ps.reso.instaeclipse.utils.download;

import java.io.IOException;

/** Persistent failure taxonomy. Matches internal messages; never returns any part of an exception. */
final class DownloadDiagnostics {
    enum Phase { DOWNLOAD_VIDEO, DOWNLOAD_AUDIO, INSPECT_VIDEO, INSPECT_AUDIO, JOIN_PORTABLE, JOIN_NATIVE,
        INSPECT_OUTPUT, VERIFY_OUTPUT }
    private DownloadDiagnostics() { }

    /** Numbers from the failed inspection only; no file, URL, track name or caught cause retained. */
    static final class CoverageFailure extends IOException {
        final boolean video;
        final long durationUs, minPtsUs, maxPtsUs, fileBytes, fragmentDurationUs;
        final int sampleCount;
        final String fragmentCause;
        CoverageFailure(boolean video, long durationUs, long minPtsUs, long maxPtsUs, int sampleCount,
                long fileBytes, long fragmentDurationUs, Throwable fragmentError) {
            super("Incomplete media samples");
            this.video = video;
            this.durationUs = durationUs;
            this.minPtsUs = sampleCount == 0 ? 0 : minPtsUs;
            this.maxPtsUs = sampleCount == 0 ? 0 : maxPtsUs;
            this.sampleCount = sampleCount;
            this.fileBytes = fileBytes;
            this.fragmentDurationUs = fragmentDurationUs;
            this.fragmentCause = fragmentError == null ? "OK" : cause(fragmentError);
        }
    }

    static String describe(Phase phase, Throwable error) {
        String result = "phase=" + phase.name() + " cause=" + cause(error);
        if (error instanceof CoverageFailure) {
            CoverageFailure coverage = (CoverageFailure) error;
            // Primitive values have bounded decimal representations, even at their type limits.
            result += " track=" + (coverage.video ? "VIDEO" : "AUDIO")
                    + " durationUs=" + coverage.durationUs + " minPtsUs=" + coverage.minPtsUs
                    + " maxPtsUs=" + coverage.maxPtsUs + " samples=" + coverage.sampleCount
                    + " fileBytes=" + coverage.fileBytes + " fragmentDurationUs=" + coverage.fragmentDurationUs
                    + " fragmentCause=" + coverage.fragmentCause;
        }
        return result;
    }

    static String joinedAudio(DownloadEngine.Metadata source, DownloadEngine.Metadata output) {
        String reason = !output.hasAudio ? "AUDIO_MISSING"
                : source.audioSamples != output.audioSamples ? "AUDIO_SAMPLE_COUNT" : "AUDIO_DURATION";
        return "phase=VERIFY_OUTPUT cause=" + reason
                + " srcSamples=" + source.audioSamples + " outSamples=" + output.audioSamples
                + " srcDurationUs=" + source.audioDurationUs + " outDurationUs=" + output.audioDurationUs
                + " srcFirstUs=" + source.audioFirstUs + " outFirstUs=" + output.audioFirstUs
                + " srcLastUs=" + source.audioLastUs + " outLastUs=" + output.audioLastUs
                + " srcCodec=" + audioFamily(source.audioCodec) + " outCodec=" + audioFamily(output.audioCodec)
                + " srcProfile=" + source.audioProfile + " outProfile=" + output.audioProfile;
    }

    private static String audioFamily(String mime) {
        if (mime == null || mime.isEmpty()) return "ABSENT";
        switch (mime) {
            case "audio/mp4a-latm": return "AAC";
            case "audio/opus": return "OPUS";
            case "audio/vorbis": return "VORBIS";
            case "audio/mpeg": return "MP3";
            default: return "OTHER";
        }
    }

    static String cause(Throwable error) {
        if (error instanceof CoverageFailure) return "SAMPLE_COVERAGE";
        if (!(error instanceof IOException)) return "PLATFORM_RUNTIME";
        String message = error.getMessage();
        // Platform errors can contain signed addresses or paths. Only exact owned messages are
        // classified; bounded template matches below also return constants, never captured text.
        if (message == null || message.length() > 256) return "PLATFORM_IO";
        switch (message) {
            case "Encrypted media": return "ENCRYPTED_TRACK";
            case "Invalid or encrypted sample": return "SAMPLE_INVALID_OR_ENCRYPTED";
            case "Sample count limit": return "SAMPLE_LIMIT";
            case "Incomplete media samples": return "SAMPLE_COVERAGE";
            case "Required media track missing": return "TRACK_MISSING";
            case "Ambiguous audio tracks": return "AUDIO_AMBIGUOUS";
            case "Audio/video durations differ": return "AV_DURATION_MISMATCH";
            case "Actual dimensions differ from selected rendition": return "DIMENSION_MISMATCH";
            case "Actual codec differs from selected rendition": return "CODEC_MISMATCH";
            case "Sample buffer exceeds limit": return "SAMPLE_BUFFER_LIMIT";
            case "Ambiguous source tracks": return "SOURCE_TRACK_AMBIGUOUS";
            case "Missing source track": return "SOURCE_TRACK_MISSING";
            case "Invalid sample": return "SAMPLE_INVALID";
            case "Fragment and extractor sample counts differ": return "FRAGMENT_SAMPLE_COUNT";
            case "Invalid fragment duration": return "FRAGMENT_DURATION";
            case "Indexed and extractor sample counts differ": return "INDEX_SAMPLE_COUNT";
            case "Invalid indexed sample duration":
            case "Invalid indexed decode time":
            case "Invalid indexed duration": return "INDEX_DURATION";
            case "Unproven indexed leading gap":
            case "Discontinuous indexed decode time": return "INDEX_TIMING_GAP";
            case "Unsupported indexed edit list": return "INDEX_EDIT_UNSUPPORTED";
            case "Mixed indexed sample layouts": return "INDEX_MIXED_LAYOUT";
            case "Indexed media data limit": return "INDEX_LIMIT";
            case "Indexed file has trailing partial box": return "INDEX_BOX_BOUNDS";
            case "Indexed samples outside media data": return "INDEX_SAMPLE_BOUNDS";
            case "Missing indexed sample table":
            case "Invalid indexed size table":
            case "Invalid indexed sample size": return "INDEX_SIZE_TABLE";
            case "Missing indexed chunk table":
            case "Invalid indexed chunk table": return "INDEX_CHUNK_TABLE";
            case "Unsupported indexed timing version":
            case "Invalid indexed timing table": return "INDEX_TIMING_TABLE";
            case "a box header is cut short":
            case "a box runs past the box that holds it": return "MP4_BOX_BOUNDS";
        }
        for (String kind : new String[] {"video", "audio"}) {
            String file = "the " + kind + " file ", track = "the " + kind + " track";
            if (message.equals("a box of " + file + "is cut short")
                    || message.equals("a box runs past the end of the " + kind + " file")) return "MP4_BOX_BOUNDS";
            if (message.equals(file + "has two moov boxes")) return "MP4_DUPLICATE_MOOV";
            if (message.equals(file + "has a fragment before its moov")) return "MP4_FRAGMENT_BEFORE_MOOV";
            if (message.equals(file + "has no moov")) return "MP4_MOOV_MISSING";
            if (message.equals(file + "holds no samples")) return "MP4_SAMPLES_MISSING";
            if (message.equals(file + "holds no " + (kind.equals("video") ? "picture" : "sound") + " track")) return "MP4_TRACK_MISSING";
            if (message.equals(track + " has no timescale")) return "MP4_TIMESCALE_MISSING";
            if (message.equals(track + " has no header")) return "MP4_TRACK_HEADER_MISSING";
            if (message.equals(track + " has no id")) return "MP4_TRACK_ID_MISSING";
            if (message.equals(track + "'s edit list is cut short")) return "MP4_EDIT_BOUNDS";
            if (message.equals(track + " has no media header")) return "MP4_MEDIA_HEADER_MISSING";
            if (message.equals(track + " has no sample table")) return "MP4_SAMPLE_TABLE_MISSING";
            if (message.equals(track + " has no sample description")
                    || message.equals(track + "'s sample descriptions are cut short")) return "MP4_SAMPLE_DESCRIPTION";
            if (message.equals(track + " is encrypted")) return "ENCRYPTED_TRACK";
            if (message.equals(track + " keeps samples outside its fragments")) return "MP4_NONFRAGMENTED_SAMPLES";
            if (message.equals("a fragment of the " + kind + " file has no header")) return "MP4_FRAGMENT_HEADER_MISSING";
            if (message.equals("a fragment of the " + kind + " file puts its data after another track's")) return "MP4_FRAGMENT_LAYOUT";
            if (message.equals("a fragment of the " + kind + " file names a sample description it hasn't got")) return "MP4_SAMPLE_DESCRIPTION";
            if (message.equals("a run of the " + kind + " file lists more samples than it holds")) return "MP4_RUN_BOUNDS";
            if (message.equals(file + "lists too many samples")) return "SAMPLE_LIMIT";
            if (message.equals("a sample of the " + kind + " file is over 2 GB")) return "MP4_SAMPLE_SIZE";
            if (message.equals("a fragment's samples run past the end of the " + kind + " file")
                    || message.equals(file + "ended before its samples did")) return "MP4_SAMPLE_BOUNDS";
            if (message.equals(track + "'s edit list shows nothing")) return "MP4_EMPTY_EDIT";
        }
        if (message.matches("the (video|audio) file has a header box of [0-9]{1,20} bytes")) return "MP4_HEADER_LIMIT";
        return "PLATFORM_IO";
    }
}
